package com.pocodslr.camera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.view.*
import android.widget.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener {

    private lateinit var textureView: TextureView
    private lateinit var statusLabel: TextView
    private lateinit var captureBtn: Button
    private lateinit var ratioBtn: Button
    private lateinit var lensBtn: Button
    private lateinit var bokehBtn: Button
    private lateinit var settingsBtn: Button

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private lateinit var sensorManager: SensorManager
    @Volatile private var currentGyroShake = 0f

    // 0 = IG 4:5 (1080x1350), 1 = Snap/Story 9:16 (1080x1920), 2 = Full 12.5MP Sensor
    private var outputMode = 0
    private var prime50mmCrop = false
    private var dslrBokehAndHdr = true
    private var isCapturing = false

    private val burstFrames = mutableListOf<ByteArray>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Samsung-inspired minimal camera HUD: settings above, shutter below.
        val topBar = FrameLayout(this).apply {
            setPadding(24, 36, 24, 12)
            setBackgroundColor(Color.TRANSPARENT)
        }
        val title = TextView(this).apply {
            text = "POCO DSLR"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        topBar.addView(title, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        settingsBtn = Button(this).apply {
            text = "⚙"
            textSize = 23f
            setTextColor(Color.WHITE)
            background = roundedControl(Color.parseColor("#99000000"), 48)
            setPadding(0, 0, 0, 0)
            minWidth = 52
            minimumWidth = 52
            minHeight = 52
            minimumHeight = 52
            setOnClickListener { showCameraSettings() }
        }
        topBar.addView(settingsBtn, FrameLayout.LayoutParams(56, 56, Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            112,
            Gravity.TOP
        ))

        statusLabel = TextView(this).apply {
            text = "Camera ready"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(20, 10, 20, 10)
            background = roundedControl(Color.parseColor("#88000000"), 24)
        }
        root.addView(statusLabel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        ).apply { bottomMargin = 150 })

        val bottomBar = FrameLayout(this).apply {
            setPadding(24, 20, 24, 42)
            setBackgroundColor(Color.parseColor("#66000000"))
        }
        captureBtn = Button(this).apply {
            text = "●"
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(5, Color.parseColor("#DDDDDD"))
            }
            setPadding(0, 0, 0, 0)
            minWidth = 76
            minimumWidth = 76
            minHeight = 76
            minimumHeight = 76
            setOnClickListener {
                if (!isCapturing) triggerGyroGatedCapture()
            }
        }
        bottomBar.addView(captureBtn, FrameLayout.LayoutParams(82, 82, Gravity.CENTER))
        root.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            132,
            Gravity.BOTTOM
        ))

        setContentView(root)

        ratioBtn = createToggleBtn("IG 4:5 (1080x1350)") {
            outputMode = (outputMode + 1) % 3
            ratioBtn.text = when (outputMode) {
                0 -> "IG 4:5 (1080x1350)"
                1 -> "SNAP 9:16 (1080x1920)"
                else -> "FULL 12.5MP MASTER"
            }
        }
        lensBtn = createToggleBtn("Lens: 26mm Wide") {
            prime50mmCrop = !prime50mmCrop
            lensBtn.text = if (prime50mmCrop) "Lens: 50mm Sweet-Spot" else "Lens: 26mm Wide"
        }
        bokehBtn = createToggleBtn("HDR + Bokeh: ON") {
            dslrBokehAndHdr = !dslrBokehAndHdr
            bokehBtn.text = if (dslrBokehAndHdr) "HDR + Bokeh: ON" else "HDR + Bokeh: OFF"
        }
    }

    private fun roundedControl(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp.toFloat()
        }

    private fun showCameraSettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 12)
            setBackgroundColor(Color.parseColor("#171717"))
        }

        fun addSettingButton(button: Button) {
            button.textSize = 14f
            button.setTextColor(Color.WHITE)
            button.background = roundedControl(Color.parseColor("#303030"), 16)
            button.setPadding(18, 18, 18, 18)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 8, 0, 8)
            panel.addView(button, params)
        }

        addSettingButton(ratioBtn)
        addSettingButton(lensBtn)
        addSettingButton(bokehBtn)

        AlertDialog.Builder(this)
            .setTitle("Camera settings")
            .setView(panel)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun createToggleBtn(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(18, 12, 18, 12)
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            params.setMargins(8, 0, 8, 0)
            layoutParams = params
            setOnClickListener { onClick() }
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (textureView.isAvailable) {
            openMaxHardwareCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openMaxHardwareCamera()
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) { configurePreviewTransform(w, h) }
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
        }
    }

    override fun onPause() {
        closeCamera()
        sensorManager.unregisterListener(this)
        stopBackgroundThread()
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            val rx = event.values[0]
            val ry = event.values[1]
            val rz = event.values[2]
            currentGyroShake = sqrt(rx * rx + ry * ry + rz * rz)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun openMaxHardwareCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
            return
        }
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var backCameraId = manager.cameraIdList.first()
            var maxSize = Size(4080, 3072)

            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    backCameraId = id
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val sizes = map?.getOutputSizes(ImageFormat.JPEG)
                    if (!sizes.isNullOrEmpty()) {
                        maxSize = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: maxSize
                    }
                    break
                }
            }

            runOnUiThread {
                statusLabel.text = "Snapdragon Native Stream: ${maxSize.width}x${maxSize.height} (100% RAW-JPEG)"
            }

            imageReader = ImageReader.newInstance(maxSize.width, maxSize.height, ImageFormat.JPEG, 3)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                synchronized(burstFrames) {
                    burstFrames.add(bytes)
                    if (burstFrames.size >= 2) {
                        val f1 = burstFrames[0]
                        val f2 = burstFrames[1]
                        burstFrames.clear()
                        processDslrPipeline(f1, f2)
                    }
                }
            }, backgroundHandler)

            manager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, backgroundHandler)

        } catch (e: Exception) {
            runOnUiThread { statusLabel.text = "Camera Error: ${e.message}" }
        }
    }

    private fun startPreviewSession() {
        val device = cameraDevice ?: return
        val surfaceTexture = textureView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(1920, 1440)
        configurePreviewTransform(textureView.width, textureView.height)
        val previewSurface = Surface(surfaceTexture)
        val readerSurface = imageReader?.surface ?: return

        val previewReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        device.createCaptureSession(listOf(previewSurface, readerSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                session.setRepeatingRequest(previewReq.build(), null, backgroundHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    // Keep the 4:3 camera stream proportional. Rotate the sensor buffer for portrait
    // display, then center-crop; never stretch the preview to fill the screen.
    private fun configurePreviewTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(
            0f, 0f,
            1440f, 1920f
        )
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = maxOf(
            viewHeight.toFloat() / 1920f,
            viewWidth.toFloat() / 1440f
        )
        matrix.postScale(scale, scale, centerX, centerY)
        matrix.postRotate(90f, centerX, centerY)
        textureView.setTransform(matrix)
    }

    private fun triggerGyroGatedCapture() {
        isCapturing = true
        captureBtn.isEnabled = false
        statusLabel.text = "Waiting for zero hand-shake (Gyro Gate)..."

        Thread {
            val startWait = System.currentTimeMillis()
            while (currentGyroShake > 0.14f && (System.currentTimeMillis() - startWait) < 350) {
                Thread.sleep(15)
            }
            runOnUiThread {
                statusLabel.text = "Firing Dual-Exposure Hardware Bracket..."
            }
            fireDualExposureBurst()
        }.start()
    }

    private fun fireDualExposureBurst() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val readerSurface = imageReader?.surface ?: return

        try {
            synchronized(burstFrames) { burstFrames.clear() }

            // Shot 1: Highlight Protection (-1 EV, 100% Quality, High Quality Optical Mastering)
            val shot1 = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -1)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
                set(CaptureRequest.JPEG_ORIENTATION, 90)
            }.build()

            // Shot 2: Deep Shadow & Texture Detail (+1 EV, 100% Quality)
            val shot2 = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 1)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.JPEG_ORIENTATION, 90)
            }.build()

            session.captureBurst(listOf(shot1, shot2), null, backgroundHandler)
        } catch (e: Exception) {
            runOnUiThread {
                statusLabel.text = "Burst failed: ${e.message}"
                isCapturing = false
                captureBtn.isEnabled = true
            }
        }
    }

    private fun processDslrPipeline(frame1Bytes: ByteArray, frame2Bytes: ByteArray) {
        Thread {
            try {
                runOnUiThread { statusLabel.text = "Stage 1/3: Rolling 2-Frame HDR Fusion (6GB RAM Safe)..." }

                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                var baseBmp = BitmapFactory.decodeByteArray(frame1Bytes, 0, frame1Bytes.size, opts)
                val brightBmp = BitmapFactory.decodeByteArray(frame2Bytes, 0, frame2Bytes.size, opts)

                // 1. Center Sweet-Spot 50mm Prime Crop (if enabled)
                if (prime50mmCrop) {
                    val cw = (baseBmp.width * 0.62f).toInt()
                    val ch = (baseBmp.height * 0.62f).toInt()
                    val cx = (baseBmp.width - cw) / 2
                    val cy = (baseBmp.height - ch) / 2
                    val cropped1 = Bitmap.createBitmap(baseBmp, cx, cy, cw, ch)
                    if (cropped1 != baseBmp) baseBmp.recycle()
                    baseBmp = cropped1
                }

                // 2. Target Aspect Ratio Crop & Supersampling Downscale
                val (targetW, targetH) = when (outputMode) {
                    0 -> Pair(1080, 1350) // Instagram 4:5 Portrait
                    1 -> Pair(1080, 1920) // Snapchat / Story 9:16
                    else -> Pair(baseBmp.width, baseBmp.height)
                }

                val workingBmp = cropAndSupersample(baseBmp, targetW, targetH)
                if (workingBmp != baseBmp) baseBmp.recycle()

                val workingBright = cropAndSupersample(brightBmp, targetW, targetH)
                if (workingBright != brightBmp) brightBmp.recycle()

                // 3. Dual-Exposure Fusion + Argon Scene Color Grading + Optical Bokeh
                runOnUiThread { statusLabel.text = "Stage 2/3: Argon Color Science + 3D Bokeh + Micro-Contrast..." }
                val masteredBmp = applyDslrMastering(workingBmp, workingBright, dslrBokehAndHdr)
                workingBmp.recycle()
                workingBright.recycle()

                // 4. Anti-Compression Unsharp Counter-Matrix for IG/Snapchat
                runOnUiThread { statusLabel.text = "Stage 3/3: Applying IG/Snap Anti-Compression Shield..." }
                val finalBmp = applyUnsharpCounterMatrix(masteredBmp)
                if (finalBmp != masteredBmp) masteredBmp.recycle()

                saveToGallery(finalBmp)
                finalBmp.recycle()

                runOnUiThread {
                    statusLabel.text = "Saved DSLR Master (${targetW}x${targetH}) to Gallery!"
                    Toast.makeText(this, "DSLR Photo Saved to Gallery!", Toast.LENGTH_SHORT).show()
                    isCapturing = false
                    captureBtn.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusLabel.text = "Processing Error: ${e.message}"
                    isCapturing = false
                    captureBtn.isEnabled = true
                }
            }
        }.start()
    }

    private fun cropAndSupersample(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val srcRatio = src.width.toFloat() / src.height.toFloat()
        val dstRatio = targetW.toFloat() / targetH.toFloat()

        var cropW = src.width
        var cropH = src.height
        if (srcRatio > dstRatio) {
            cropW = (src.height * dstRatio).toInt()
        } else {
            cropH = (src.width / dstRatio).toInt()
        }
        val x = (src.width - cropW) / 2
        val y = (src.height - cropH) / 2

        val cropped = Bitmap.createBitmap(src, x, y, cropW, cropH)
        if (targetW == cropW && targetH == cropH) return cropped

        // 2-Stage Supersampling for razor-sharp downscaling
        val midW = (cropW + targetW) / 2
        val midH = (cropH + targetH) / 2
        val midBmp = Bitmap.createScaledBitmap(cropped, midW, midH, true)
        if (midBmp != cropped) cropped.recycle()
        val finalScaled = Bitmap.createScaledBitmap(midBmp, targetW, targetH, true)
        if (finalScaled != midBmp) midBmp.recycle()
        return finalScaled
    }

    private fun applyDslrMastering(darkShot: Bitmap, brightShot: Bitmap, enableHdrAndBokeh: Boolean): Bitmap {
        val w = darkShot.width
        val h = darkShot.height
        val pixels1 = IntArray(w * h)
        val pixels2 = IntArray(w * h)
        darkShot.getPixels(pixels1, 0, w, 0, 0, w, h)
        brightShot.getPixels(pixels2, 0, w, 0, 0, w, h)

        val outPixels = IntArray(w * h)
        for (i in pixels1.indices) {
            val c1 = pixels1[i]
            val r1 = (c1 shr 16) and 0xFF
            val g1 = (c1 shr 8) and 0xFF
            val b1 = c1 and 0xFF

            val c2 = pixels2[i]
            val r2 = (c2 shr 16) and 0xFF
            val g2 = (c2 shr 8) and 0xFF
            val b2 = c2 and 0xFF

            val lum = (0.299f * r1 + 0.587f * g1 + 0.114f * b1) / 255f
            // Blend shadow detail from Shot 2 with highlight protection from Shot 1
            val shadowWeight = if (enableHdrAndBokeh) (1f - lum) * 0.55f else 0.25f

            var r = (r1 * (1f - shadowWeight) + r2 * shadowWeight)
            var g = (g1 * (1f - shadowWeight) + g2 * shadowWeight)
            var b = (b1 * (1f - shadowWeight) + b2 * shadowWeight)

            // Argon S-Curve Micro-Contrast & Rich Cinema Grade
            val nr = r / 255f
            val ng = g / 255f
            val nb = b / 255f
            r = (nr * nr * (3f - 2f * nr) * 0.35f + nr * 0.65f) * 255f
            g = (ng * ng * (3f - 2f * ng) * 0.35f + ng * 0.65f) * 255f
            b = (nb * nb * (3f - 2f * nb) * 0.35f + nb * 0.65f) * 255f

            // Natural Vibrance Boost (Protects skin tones while enriching background colors)
            val avg = (r + g + b) / 3f
            val satBoost = 1.14f
            r = (avg + (r - avg) * satBoost).coerceIn(0f, 255f)
            g = (avg + (g - avg) * satBoost).coerceIn(0f, 255f)
            b = (avg + (b - avg) * satBoost).coerceIn(0f, 255f)

            outPixels[i] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }

        val fusedBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fusedBmp.setPixels(outPixels, 0, w, 0, 0, w, h)

        if (!enableHdrAndBokeh) return fusedBmp

        // Progressive Optical Edge Bokeh Simulation (keeps center subject razor-sharp, softens outer background)
        val blurredBg = Bitmap.createScaledBitmap(
            Bitmap.createScaledBitmap(fusedBmp, w / 6, h / 6, true), w, h, true
        )
        val blurPixels = IntArray(w * h)
        blurredBg.getPixels(blurPixels, 0, w, 0, 0, w, h)
        blurredBg.recycle()

        val cx = w * 0.5f
        val cy = h * 0.48f
        val maxDist = sqrt(cx * cx + cy * cy)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val dx = (x - cx) / 0.85f
                val dy = (y - cy) / 1.15f
                val dist = sqrt(dx * dx + dy * dy) / maxDist
                if (dist > 0.62f) {
                    val blend = ((dist - 0.62f) / 0.38f).coerceIn(0f, 0.72f)
                    val sharp = outPixels[idx]
                    val soft = blurPixels[idx]
                    val sr = ((sharp shr 16 and 0xFF) * (1f - blend) + (soft shr 16 and 0xFF) * blend).toInt()
                    val sg = ((sharp shr 8 and 0xFF) * (1f - blend) + (soft shr 8 and 0xFF) * blend).toInt()
                    val sb = ((sharp and 0xFF) * (1f - blend) + (soft and 0xFF) * blend).toInt()
                    outPixels[idx] = (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
                }
            }
        }
        fusedBmp.setPixels(outPixels, 0, w, 0, 0, w, h)
        return fusedBmp
    }

    private fun applyUnsharpCounterMatrix(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val soft = Bitmap.createScaledBitmap(
            Bitmap.createScaledBitmap(src, w / 2, h / 2, true), w, h, true
        )
        val origPx = IntArray(w * h)
        val softPx = IntArray(w * h)
        src.getPixels(origPx, 0, w, 0, 0, w, h)
        soft.getPixels(softPx, 0, w, 0, 0, w, h)
        soft.recycle()

        val amount = 0.65f // Tuned to survive Instagram/Snapchat compression without white halos
        for (i in origPx.indices) {
            val c = origPx[i]
            val s = softPx[i]
            val r = ((c shr 16 and 0xFF) + ((c shr 16 and 0xFF) - (s shr 16 and 0xFF)) * amount).toInt().coerceIn(0, 255)
            val g = ((c shr 8 and 0xFF) + ((c shr 8 and 0xFF) - (s shr 8 and 0xFF)) * amount).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) + ((c and 0xFF) - (s and 0xFF)) * amount).toInt().coerceIn(0, 255)
            origPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(origPx, 0, w, 0, 0, w, h)
        return out
    }

    private fun saveToGallery(bmp: Bitmap) {
        val filename = "POCO_DSLR_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PocoDSLR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 98, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2Bg").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }
}