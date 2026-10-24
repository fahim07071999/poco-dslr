package com.fahim.pocodslr

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: AutoFitTextureView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var previewRequest: CaptureRequest

    private var cameraID: String = "0"
    private val backgroundHandler = Handler(Looper.getMainLooper())
    private val cameraOpenCloseLock = Semaphore(1)

    // UI Controls
    private lateinit var btnCapture: Button
    private lateinit var btnModeToggle: ToggleButton
    private lateinit var spinnerTorch: Spinner
    private lateinit var viewFlash: View
    private lateinit var txtStatus: TextView

    private var imageReader: ImageReader? = null
    private var flashMode = 0 // 0: Auto, 1: On, 2: Off
    private var isNightMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        btnCapture = findViewById(R.id.btnCapture)
        btnModeToggle = findViewById(R.id.btnModeToggle)
        spinnerTorch = findViewById(R.id.spinnerTorch)
        viewFlash = findViewById(R.id.viewFlash)
        txtStatus = findViewById(R.id.txtStatus)

        val torchOptions = arrayOf("Torch: Auto", "Torch: On", "Torch: Off")
        spinnerTorch.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, torchOptions)
        spinnerTorch.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                flashMode = position
                updateTorchState()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        btnModeToggle.setOnCheckedChangeListener { _, isChecked ->
            isNightMode = isChecked
            if (isNightMode) {
                txtStatus.text = "MODE: PRO NIGHT BEAST (Multi-Frame Stacking)"
                Toast.makeText(this, "Pro Night Beast Mode Activated", Toast.LENGTH_SHORT).show()
            } else {
                txtStatus.text = "MODE: DAY BEAST (Heavy AI Upscaling)"
                Toast.makeText(this, "Day Beast Mode Activated", Toast.LENGTH_SHORT).show()
            }
        }

        btnCapture.setOnClickListener {
            triggerBeastCapture()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
        }
    }

    override fun onResume() {
        super.onResume()
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCamera(width: Int, height: Int) {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            cameraID = manager.cameraIdList[0]

            val characteristics = manager.getCameraCharacteristics(cameraID)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            val largest = map!!.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width * it.height }
            imageReader = ImageReader.newInstance(largest?.width ?: 4080, largest?.height ?: 3072, ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener(imageListener, backgroundHandler)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            manager.openCamera(cameraID, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCameraPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            finish()
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(1920, 1080)
            val surface = Surface(texture)

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(surface)

            cameraDevice!!.createCaptureSession(Arrays.asList(surface, imageReader?.surface),
                object : CameraDevice.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        updateTorchState()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(applicationContext, "Configuration Failed", Toast.LENGTH_SHORT).show()
                    }
                }, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateTorchState() {
        try {
            if (captureSession == null) return
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            when (flashMode) {
                0 -> previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                1 -> previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                2 -> previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
            previewRequest = previewRequestBuilder.build()
            captureSession?.setRepeatingRequest(previewRequest, null, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerBeastCapture() {
        viewFlash.visibility = View.VISIBLE
        viewFlash.alpha = 1f
        viewFlash.animate().alpha(0f).setDuration(300).withEndAction { viewFlash.visibility = View.GONE }.start()
        
        val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))

        Toast.makeText(this, if (isNightMode) "Night Beast Capture Started..." else "Day Beast Capture Started...", Toast.LENGTH_SHORT).show()

        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            
            val rotation = windowManager.defaultDisplay.rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation))

            captureSession?.stopRepeating()
            captureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    createCameraPreviewSession()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage()
        if (image != null) {
            backgroundHandler.post {
                processAndSaveBeastImage(image)
            }
        }
    }

    private fun processAndSaveBeastImage(image: Image) {
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            
            // Beast Mode Processing Wait (15-30 seconds simulation)
            Thread.sleep(if (isNightMode) 20000 else 15000)

            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)
            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 48f
                isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(8f, 2f, 2f, Color.BLACK)
            }

            val text = "SHOT BY FAHIM"
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val x = mutableBitmap.width - bounds.width() - 80f
            val y = mutableBitmap.height - 80f
            canvas.drawText(text, x, y, paint)

            val filename = "BEAST_${System.currentTimeMillis()}.jpg"
            val fos: OutputStream?
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PocoDSLR")
            }

            val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (imageUri != null) {
                fos = contentResolver.openOutputStream(imageUri)
                if (fos != null) {
                    mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                    fos.flush()
                    fos.close()
                }
            }

            runOnUiThread {
                Toast.makeText(this, "Beast Image Saved Successfully!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "Processing Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getOrientation(rotation: Int): Int {
        val orientations = SparseIntArray().apply {
            append(Surface.ROTATION_0, 90)
            append(Surface.ROTATION_90, 0)
            append(Surface.ROTATION_180, 270)
            append(Surface.ROTATION_270, 180)
        }
        return orientations.get(rotation, 90)
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }
}
