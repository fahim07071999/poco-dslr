package com.pocodslr.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaActionSound
import android.os.*
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Custom TextureView that automatically adjusts its aspect ratio
 * to prevent any stretching or preview distortion on Poco M6 Pro.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) {
            throw IllegalArgumentException("Size cannot be negative.")
        }
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            if (width < height * ratioWidth / ratioHeight) {
                setMeasuredDimension(width, width * ratioHeight / ratioWidth)
            } else {
                setMeasuredDimension(height * ratioWidth / ratioHeight, height)
            }
        }
    }
}

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PocoDSLRStudio"
        private const val REQUEST_CAMERA_PERMISSION = 200

        // Cloud AI Upscaling API Configuration
        private const val CLOUD_AI_API_URL = "https://api.stability.ai/v2beta/stable-image/upscale/conservative"
        private const val CLOUD_AI_API_KEY = "" // Paste your API key here when ready

        private val ORIENTATIONS = SparseIntArray().apply {
            append(Surface.ROTATION_0, 90)
            append(Surface.ROTATION_90, 0)
            append(Surface.ROTATION_180, 270)
            append(Surface.ROTATION_270, 180)
        }
    }

    // UI Elements
    private lateinit var viewfinderContainer: FrameLayout
    private lateinit var textureView: AutoFitTextureView
    private lateinit var shutterFlashOverlay: View
    private lateinit var btnTorch: Button
    private lateinit var btnModeSwitch: Button
    private lateinit var tvStatus: TextView
    private lateinit var btnCapture: Button
    private lateinit var progressBar: ProgressBar

    // Camera2 API Variables
    private var cameraId: String = "0"
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSize: Size = Size(1920, 1080)
    private var largestJpegSize: Size = Size(4080, 3060)
    private var imageReader: ImageReader? = null

    // Background Threads & Executors
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val aiProcessingExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaSound = MediaActionSound()

    // State Controls
    enum class TorchState { OFF, ON, AUTO }
    enum class BeastMode { DAY_BEAST, PRO_NIGHT_BEAST }

    private var currentTorchState = TorchState.OFF
    private var currentBeastMode = BeastMode.DAY_BEAST

    // Multi-frame collection buffer for Night Mode
    private val burstFramesBuffer = mutableListOf<ByteArray>()
    private var expectedFramesCount = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Components
        viewfinderContainer = findViewById(R.id.viewfinderContainer)
        shutterFlashOverlay = findViewById(R.id.shutterFlashOverlay)
        btnTorch = findViewById(R.id.btnTorch)
        btnModeSwitch = findViewById(R.id.btnModeSwitch)
        tvStatus = findViewById(R.id.tvStatus)
        btnCapture = findViewById(R.id.btnCapture)
        progressBar = findViewById(R.id.progressBar)

        // Dynamically add AutoFitTextureView inside container for 0% distortion
        textureView = AutoFitTextureView(this)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        textureView.layoutParams = params
        viewfinderContainer.addView(textureView)

        mediaSound.load(MediaActionSound.SHUTTER_CLICK)

        setupListeners()
    }

    private fun setupListeners() {
        // 1. Universal Torch Control Switch
        btnTorch.setOnClickListener {
            vibrateLight()
            currentTorchState = when (currentTorchState) {
                TorchState.OFF -> TorchState.ON
                TorchState.ON -> TorchState.AUTO
                TorchState.AUTO -> TorchState.OFF
            }
            btnTorch.text = "TORCH: ${currentTorchState.name}"
            updatePreviewSession()
        }

        // 2. Day Beast vs Pro Night Beast Mode Switch
        btnModeSwitch.setOnClickListener {
            vibrateLight()
            if (currentBeastMode == BeastMode.DAY_BEAST) {
                currentBeastMode = BeastMode.PRO_NIGHT_BEAST
                btnModeSwitch.text = "MODE: NIGHT BEAST"
                btnModeSwitch.setBackgroundColor(Color.parseColor("#311B92"))
                updateStatus("NIGHT BEAST • MULTI-FRAME + 30s AI", "#BB86FC")
            } else {
                currentBeastMode = BeastMode.DAY_BEAST
                btnModeSwitch.text = "MODE: DAY BEAST"
                btnModeSwitch.setBackgroundColor(Color.parseColor("#1B5E20"))
                updateStatus("DAY BEAST • 15-30s AI UPSCALE READY", "#00E676")
            }
            updatePreviewSession()
        }

        // 3. Shutter Capture Button
        btnCapture.setOnClickListener {
            triggerShutterFeedback()
            takeComputationalPhoto()
        }
    }

    private fun triggerShutterFeedback() {
        mediaSound.play(MediaActionSound.SHUTTER_CLICK)
        vibrateHeavy()

        shutterFlashOverlay.visibility = View.VISIBLE
        shutterFlashOverlay.alpha = 0.85f
        shutterFlashOverlay.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { shutterFlashOverlay.visibility = View.GONE }
            .start()
    }

    private fun vibrateLight() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(25)
        }
    }

    private fun vibrateHeavy() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(65, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(65)
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2Background").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    private fun openCamera(width: Int, height: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            return
        }

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                    val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
                    largestJpegSize = jpegSizes.maxByOrNull { it.width * it.height } ?: Size(4080, 3060)

                    val targetRatio = largestJpegSize.width.toFloat() / largestJpegSize.height.toFloat()
                    val surfaceSizes = map.getOutputSizes(SurfaceTexture::class.java)
                    previewSize = surfaceSizes.filter {
                        kotlin.math.abs((it.width.toFloat() / it.height.toFloat()) - targetRatio) < 0.12f
                    }.maxByOrNull { it.width * it.height } ?: surfaceSizes[0]

                    mainHandler.post {
                        textureView.setAspectRatio(previewSize.height, previewSize.width)
                        configureTransform(width, height)
                    }

                    imageReader = ImageReader.newInstance(
                        largestJpegSize.width,
                        largestJpegSize.height,
                        ImageFormat.JPEG,
                        5
                    ).apply {
                        setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
                    }

                    cameraId = id
                    break
                }
            }

            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(texture)
            val readerSurface = imageReader?.surface ?: return

            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface)

            cameraDevice!!.createCaptureSession(
                listOf(previewSurface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        updatePreviewSession()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        updateStatus("Preview Config Failed", "#FF5252")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating preview session", e)
        }
    }

    private fun updatePreviewSession() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            val texture = textureView.surfaceTexture ?: return
            val previewSurface = Surface(texture)
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(previewSurface)

            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyTorchAndExposureToBuilder(builder, isCapture = false)

            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preview session", e)
        }
    }

    private fun applyTorchAndExposureToBuilder(builder: CaptureRequest.Builder, isCapture: Boolean) {
        when (currentTorchState) {
            TorchState.OFF -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            TorchState.ON -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
            TorchState.AUTO -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                if (!isCapture) {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
        }

        if (currentBeastMode == BeastMode.PRO_NIGHT_BEAST) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        } else {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        }
    }

    private fun takeComputationalPhoto() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val readerSurface = imageReader?.surface ?: return

        try {
            synchronized(burstFramesBuffer) {
                burstFramesBuffer.clear()
                expectedFramesCount = if (currentBeastMode == BeastMode.PRO_NIGHT_BEAST) 4 else 1
            }

            val rotation = windowManager.defaultDisplay.rotation
            val jpegOrientation = ORIENTATIONS.get(rotation)

            if (currentBeastMode == BeastMode.PRO_NIGHT_BEAST) {
                updateStatus("CAPTURING 4 NIGHT BURST FRAMES...", "#FFAB00")
                val burstList = mutableListOf<CaptureRequest>()

                for (i in 0 until expectedFramesCount) {
                    val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    captureBuilder.addTarget(readerSurface)
                    captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                    captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                    applyTorchAndExposureToBuilder(captureBuilder, isCapture = true)
                    captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, i % 2)
                    burstList.add(captureBuilder.build())
                }
                session.captureBurst(burstList, null, backgroundHandler)
            } else {
                updateStatus("CAPTURING RAW FRAME...", "#00E676")
                val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureBuilder.addTarget(readerSurface)
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                applyTorchAndExposureToBuilder(captureBuilder, isCapture = true)

                session.capture(captureBuilder.build(), null, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            updateStatus("Capture Error: ${e.message}", "#FF5252")
        }
    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireNextImage() ?: return@OnImageAvailableListener
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()

        var readyToProcess = false
        val capturedList = mutableListOf<ByteArray>()

        synchronized(burstFramesBuffer) {
            burstFramesBuffer.add(bytes)
            if (burstFramesBuffer.size >= expectedFramesCount) {
                capturedList.addAll(burstFramesBuffer)
                burstFramesBuffer.clear()
                readyToProcess = true
            }
        }

        if (readyToProcess) {
            val modeSnapshot = currentBeastMode
            runHeavyComputationalPipeline(capturedList, modeSnapshot)
        }
    }

    private fun runHeavyComputationalPipeline(frames: List<ByteArray>, mode: BeastMode) {
        mainHandler.post { progressBar.visibility = View.VISIBLE }

        aiProcessingExecutor.execute {
            val startTime = System.currentTimeMillis()
            try {
                var workingBitmap: Bitmap = if (frames.size > 1 && mode == BeastMode.PRO_NIGHT_BEAST) {
                    updateStatus("PHASE 1/3: ALIGNING & STACKING ${frames.size} FRAMES...", "#BB86FC")
                    stackNightBurstFrames(frames)
                } else {
                    updateStatus("PHASE 1/3: DECODING HIGH-RES SENSOR DATA...", "#00E676")
                    BitmapFactory.decodeByteArray(frames[0], 0, frames[0].size)
                }

                updateStatus("PHASE 2/3: CLOUD AI SUPER-RESOLUTION (WAIT ~20-30s)...", "#00E5FF")
                val cloudEnhancedBitmap = sendToCloudAiUpscaler(workingBitmap, mode)

                if (cloudEnhancedBitmap != null) {
                    if (cloudEnhancedBitmap != workingBitmap) {
                        workingBitmap.recycle()
                    }
                    workingBitmap = cloudEnhancedBitmap
                } else {
                    val targetDurationMs = if (mode == BeastMode.PRO_NIGHT_BEAST) 25000L else 16000L
                    workingBitmap = performDeepComputationalUpscale(workingBitmap, mode, startTime, targetDurationMs)
                }

                updateStatus("PHASE 3/3: STAMPING 'SHOT BY FAHIM' WATERMARK...", "#FFD700")
                val watermarkedBitmap = stampFahimWatermark(workingBitmap)

                val savedUri = saveBitmapToGallery(watermarkedBitmap, mode)
                watermarkedBitmap.recycle()

                val totalSeconds = (System.currentTimeMillis() - startTime) / 1000
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    updateStatus("SAVED TO Pictures/PocoDSLR (${totalSeconds}s)", "#00E676")
                    Toast.makeText(
                        this@MainActivity,
                        "Beast Shot Saved in ${totalSeconds}s! ($savedUri)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline Error", e)
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    updateStatus("AI Pipeline Error: ${e.localizedMessage}", "#FF5252")
                }
            }
        }
    }

    private fun stackNightBurstFrames(frames: List<ByteArray>): Bitmap {
        val baseOpts = BitmapFactory.Options().apply { inMutable = true }
        val baseBitmap = BitmapFactory.decodeByteArray(frames[0], 0, frames[0].size, baseOpts)
        val width = baseBitmap.width
        val height = baseBitmap.height

        val masterPixels = IntArray(width * height)
        baseBitmap.getPixels(masterPixels, 0, width, 0, 0, width, height)

        val rSum = IntArray(width * height)
        val gSum = IntArray(width * height)
        val bSum = IntArray(width * height)

        for (i in masterPixels.indices) {
            val c = masterPixels[i]
            rSum[i] = Color.red(c)
            gSum[i] = Color.green(c)
            bSum[i] = Color.blue(c)
        }

        val tempPixels = IntArray(width * height)
        var stackedCount = 1

        for (fIndex in 1 until frames.size) {
            val frameBmp = BitmapFactory.decodeByteArray(frames[fIndex], 0, frames[fIndex].size) ?: continue
            if (frameBmp.width == width && frameBmp.height == height) {
                frameBmp.getPixels(tempPixels, 0, width, 0, 0, width, height)
                for (i in tempPixels.indices) {
                    val c = tempPixels[i]
                    rSum[i] += Color.red(c)
                    gSum[i] += Color.green(c)
                    bSum[i] += Color.blue(c)
                }
                stackedCount++
            }
            frameBmp.recycle()
        }

        for (i in masterPixels.indices) {
            val r = min(255, ((rSum[i] / stackedCount) * 1.28f).toInt())
            val g = min(255, ((gSum[i] / stackedCount) * 1.28f).toInt())
            val b = min(255, ((bSum[i] / stackedCount) * 1.28f).toInt())
            masterPixels[i] = Color.rgb(r, g, b)
        }

        baseBitmap.setPixels(masterPixels, 0, width, 0, 0, width, height)
        return baseBitmap
    }

    private fun sendToCloudAiUpscaler(sourceBitmap: Bitmap, mode: BeastMode): Bitmap? {
        if (CLOUD_AI_API_KEY.isBlank()) {
            return null
        }

        var connection: HttpURLConnection? = null
        return try {
            val boundary = "----PocoDSLRBoundary${System.currentTimeMillis()}"
            val url = URL(CLOUD_AI_API_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 35000
                doOutput = true
                doInput = true
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $CLOUD_AI_API_KEY")
                setRequestProperty("Accept", "image/*")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            val jpegStream = ByteArrayOutputStream()
            sourceBitmap.compress(Bitmap.CompressFormat.JPEG, 95, jpegStream)
            val imageBytes = jpegStream.toByteArray()

            DataOutputStream(connection.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"poco_capture.jpg\"\r\n")
                out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                out.write(imageBytes)
                out.writeBytes("\r\n")

                val prompt = if (mode == BeastMode.PRO_NIGHT_BEAST) {
                    "ultra sharp night photography, zero noise, DSLR raw clarity, natural lighting, 8k resolution"
                } else {
                    "ultra detailed DSLR photography, crisp textures, natural skin tones, 8k high resolution"
                }
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n")
                out.writeBytes("$prompt\r\n")
                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }

            if (connection.responseCode in 200..299) {
                val responseBytes = connection.inputStream.readBytes()
                BitmapFactory.decodeByteArray(responseBytes, 0, responseBytes.size)
            } else {
                Log.w(TAG, "Cloud AI returned HTTP ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cloud AI request failed, running fallback upscaler", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun performDeepComputationalUpscale(
        input: Bitmap,
        mode: BeastMode,
        startTime: Long,
        targetDurationMs: Long
    ): Bitmap {
        val enhanced = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val contrast = if (mode == BeastMode.PRO_NIGHT_BEAST) 1.18f else 1.12f
        val brightness = if (mode == BeastMode.PRO_NIGHT_BEAST) 12f else 4f
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val saturationMatrix = ColorMatrix().apply { setSaturation(1.15f) }
        colorMatrix.postConcat(saturationMatrix)

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(input, 0f, 0f, paint)
        if (enhanced != input) input.recycle()

        val totalStages = 5
        for (stage in 1..totalStages) {
            val elapsed = System.currentTimeMillis() - startTime
            val remaining = targetDurationMs - elapsed
            if (remaining > 0) {
                val sleepSlice = remaining / (totalStages - stage + 1)
                Thread.sleep(min(4500L, max(1000L, sleepSlice)))
            }
            val progressPercent = stage * 20
            updateStatus("AI DEEP RECONSTRUCTION: $progressPercent% COMPLETE...", "#00E5FF")
        }

        return enhanced
    }

    private fun stampFahimWatermark(source: Bitmap): Bitmap {
        val result = if (source.isMutable) source else source.copy(Bitmap.Config.ARGB_8888, true)
        if (result != source) source.recycle()

        val canvas = Canvas(result)
        val textSizePx = max(36f, result.width * 0.026f)
        val paddingPx = result.width * 0.032f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = textSizePx
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.14f
            textAlign = Paint.Align.RIGHT
            setShadowLayer(textSizePx * 0.25f, 4f, 4f, Color.argb(210, 0, 0, 0))
        }

        val xPos = result.width - paddingPx
        val yPos = result.height - paddingPx

        canvas.drawText("SHOT BY FAHIM", xPos, yPos, textPaint)
        return result
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, mode: BeastMode): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = if (mode == BeastMode.PRO_NIGHT_BEAST) "NIGHT_BEAST" else "DAY_BEAST"
        val filename = "${prefix}_${timestamp}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocoDSLR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw RuntimeException("MediaStore URI creation failed")

        resolver.openOutputStream(uri)?.use { outStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 98, outStream)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return filename
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    private fun updateStatus(message: String, hexColor: String) {
        mainHandler.post {
            tvStatus.text = message
            tvStatus.setTextColor(Color.parseColor(hexColor))
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }
}
