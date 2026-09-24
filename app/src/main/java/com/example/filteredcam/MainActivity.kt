package com.example.filteredcam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaActionSound
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import android.view.ScaleGestureDetector
import androidx.camera.core.ZoomState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.ImageButton
class MainActivity : AppCompatActivity() {

    private lateinit var liveImageView: ImageView
    private lateinit var captureButton: View
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentZoomRatio = 1f
    private var camera: Camera? = null
    private lateinit var focusRing: View
    private val shutterSound = MediaActionSound()
    private lateinit var zoomLabel: android.widget.TextView
    private lateinit var switchCameraButton: ImageButton
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private enum class FilterType { ORIGINAL, GRAYSCALE, SEPIA, INVERT, BRIGHT, VINTAGE, POLAROID, COOL_RETRO, CROSS_PROCESS }    private var selectedFilter = FilterType.ORIGINAL

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is required to use this app",
                    Toast.LENGTH_LONG
                ).show()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Let content draw behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Hide status bar and navigation bar
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        liveImageView = findViewById(R.id.liveImageView)
        captureButton = findViewById(R.id.captureButton)
        cameraExecutor = Executors.newSingleThreadExecutor()

        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        captureButton.setOnClickListener { takePhoto() }

        findViewById<ImageButton>(R.id.galleryButton).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        switchCameraButton = findViewById(R.id.switchCameraButton)
        switchCameraButton.setOnClickListener { switchCamera() }

        loadLastPhotoThumbnail()

        setupFilterButtons()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        focusRing = findViewById(R.id.focusRing)
        zoomLabel = findViewById(R.id.zoomLabel)
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zoomState = cam.cameraInfo.zoomState.value ?: return false

                val minZoom = zoomState.minZoomRatio
                val maxZoom = zoomState.maxZoomRatio

                val delta = detector.scaleFactor
                currentZoomRatio = (currentZoomRatio * delta).coerceIn(minZoom, maxZoom)

                cam.cameraControl.setZoomRatio(currentZoomRatio)
                zoomLabel.text = String.format(Locale.US, "%.1fx", currentZoomRatio)
                return true
            }
        })

        liveImageView.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && event.pointerCount == 1 && !scaleGestureDetector.isInProgress) {
                focusOnTouch(event.x, event.y)
            }
            view.performClick()
            true
        }
    }
    private fun setupFilterButtons() {
        findViewById<Button>(R.id.filterOriginal).setOnClickListener { selectedFilter = FilterType.ORIGINAL }
        findViewById<Button>(R.id.filterGrayscale).setOnClickListener { selectedFilter = FilterType.GRAYSCALE }
        findViewById<Button>(R.id.filterSepia).setOnClickListener { selectedFilter = FilterType.SEPIA }
        findViewById<Button>(R.id.filterInvert).setOnClickListener { selectedFilter = FilterType.INVERT }
        findViewById<Button>(R.id.filterBright).setOnClickListener { selectedFilter = FilterType.BRIGHT }
        findViewById<Button>(R.id.filterVintage).setOnClickListener { selectedFilter = FilterType.VINTAGE }
        findViewById<Button>(R.id.filterPolaroid).setOnClickListener { selectedFilter = FilterType.POLAROID }
        findViewById<Button>(R.id.filterCoolRetro).setOnClickListener { selectedFilter = FilterType.COOL_RETRO }
        findViewById<Button>(R.id.filterCrossProcess).setOnClickListener { selectedFilter = FilterType.CROSS_PROCESS }
    }
    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            imageCapture = ImageCapture.Builder().build()

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    currentCameraSelector,
                    imageCapture,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_LONG).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }
    private fun loadLastPhotoThumbnail() {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%FilteredCam%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idColumn)
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                findViewById<ImageButton>(R.id.galleryButton).setImageURI(uri)
            }
        }
    }
    private fun switchCamera() {
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        startCamera() // rebinds everything with the new selector
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
            val filtered = applyFilter(rotated, selectedFilter)

            runOnUiThread {
                liveImageView.setImageBitmap(filtered)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close() // CRITICAL: always close, or the camera freezes
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun focusOnTouch(x: Float, y: Float) {
        val cam = camera ?: return

        val factory = SurfaceOrientedMeteringPointFactory(
            liveImageView.width.toFloat(),
            liveImageView.height.toFloat()
        )
        val meteringPoint = factory.createPoint(x, y)

        val action = FocusMeteringAction.Builder(meteringPoint)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        cam.cameraControl.startFocusAndMetering(action)

        showFocusRing(x, y)
    }

    private fun showFocusRing(x: Float, y: Float) {
        focusRing.x = x - (focusRing.width / 2f)
        focusRing.y = y - (focusRing.height / 2f)
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1f
        focusRing.scaleX = 1.3f
        focusRing.scaleY = 1.3f

        val scaleX = ObjectAnimator.ofFloat(focusRing, "scaleX", 1.3f, 1f)
        val scaleY = ObjectAnimator.ofFloat(focusRing, "scaleY", 1.3f, 1f)
        val fadeOut = ObjectAnimator.ofFloat(focusRing, "alpha", 1f, 0f).apply {
            startDelay = 600
            duration = 300
        }

        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 200
            start()
        }
        fadeOut.start()
    }
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        shutterSound.play(MediaActionSound.SHUTTER_CLICK)

        val tempFile = File(externalCacheDir ?: cacheDir, "temp_capture.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity,
                        "Photo capture failed: ${exc.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val originalBitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                    val filteredBitmap = applyFilter(originalBitmap, selectedFilter)
                    saveBitmapToGallery(filteredBitmap)
                }
            }
        )
    }

    private fun applyFilter(bitmap: Bitmap, filter: FilterType): Bitmap {
        if (filter == FilterType.ORIGINAL) return bitmap

        val colorMatrix = when (filter) {
            FilterType.GRAYSCALE -> ColorMatrix().apply { setSaturation(0f) }
            FilterType.SEPIA -> ColorMatrix(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.INVERT -> ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.BRIGHT -> ColorMatrix(
                floatArrayOf(
                    1.4f, 0f, 0f, 0f, 30f,
                    0f, 1.4f, 0f, 0f, 30f,
                    0f, 0f, 1.4f, 0f, 30f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.VINTAGE -> ColorMatrix(
                floatArrayOf(
                    0.9f, 0.1f, 0.05f, 0f, 25f,
                    0.05f, 0.85f, 0.1f, 0f, 15f,
                    0.05f, 0.1f, 0.7f, 0f, 5f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.POLAROID -> ColorMatrix(
                floatArrayOf(
                    1.1f, 0.05f, 0f, 0f, 20f,
                    0.05f, 1.0f, 0.05f, 0f, 15f,
                    0f, 0.05f, 0.85f, 0f, 10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.COOL_RETRO -> ColorMatrix(
                floatArrayOf(
                    0.85f, 0f, 0.1f, 0f, 0f,
                    0f, 0.9f, 0.05f, 0f, 5f,
                    0.1f, 0.05f, 1.2f, 0f, 20f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.CROSS_PROCESS -> ColorMatrix(
                floatArrayOf(
                    1.3f, 0f, 0f, 0f, -20f,
                    0.1f, 1.2f, 0f, 0f, 10f,
                    0f, 0.1f, 0.8f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            else -> ColorMatrix()
        }

        val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return resultBitmap
    }
    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FilteredCam")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
            Toast.makeText(this, "Filtered photo saved!", Toast.LENGTH_SHORT).show()
            loadLastPhotoThumbnail() // refresh thumbnail with the photo we just took
        } ?: run {
            Toast.makeText(this, "Failed to save photo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        shutterSound.release()
        cameraExecutor.shutdown()
    }
}