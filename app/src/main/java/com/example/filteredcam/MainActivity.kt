package com.example.filteredcam

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.ContentUris
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
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.widget.LinearLayout
import android.widget.SeekBar
@androidx.media3.common.util.UnstableApi
class MainActivity : AppCompatActivity() {

    private enum class FilterType {
        ORIGINAL, GRAYSCALE, SEPIA, INVERT, BRIGHT, VINTAGE, POLAROID, COOL_RETRO, CROSS_PROCESS
    }

    private enum class CaptureMode { PHOTO, VIDEO }

    // Views
    private lateinit var liveImageView: ImageView
    private lateinit var captureButton: View
    private lateinit var focusRing: View
    private lateinit var zoomLabel: TextView
    private lateinit var recordingIndicator: TextView
    private lateinit var modePhotoLabel: TextView
    private lateinit var modeVideoLabel: TextView
    private lateinit var switchCameraButton: ImageButton
    private lateinit var filterChips: Map<FilterType, TextView>
    private lateinit var flashOverlay: View
    private lateinit var processingIndicator: View
    private val adjustments = ColorAdjustments()
    @Volatile private var activeColorMatrix: ColorMatrix? = null
    private val adjustResetters = mutableListOf<() -> Unit>()
    private var exposureMinIndex = 0
    private var exposureStepEv = 0f
    private val zoomHandler = Handler(Looper.getMainLooper())
    private val hideZoomRunnable = Runnable {
        zoomLabel.animate().alpha(0f).setDuration(300).start()
    }
    // Camera
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // State
    private var selectedFilter = FilterType.ORIGINAL
    private var currentMode = CaptureMode.PHOTO
    private var currentZoomRatio = 1f
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private val shutterSound = MediaActionSound()

    // Recording timer
    private var recordingSeconds = 0
    private val recordingHandler = Handler(Looper.getMainLooper())
    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            recordingSeconds++
            val mins = recordingSeconds / 60
            val secs = recordingSeconds % 60
            recordingIndicator.text = String.format(Locale.US, "● REC %02d:%02d", mins, secs)
            recordingHandler.postDelayed(this, 1000)
        }
    }

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            if (hasCameraPermission()) {
                if (camera == null) startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required to use this app", Toast.LENGTH_LONG).show()
            }
            if (!hasAudioPermission()) {
                Toast.makeText(this, "Microphone denied - videos will have no sound", Toast.LENGTH_LONG).show()
            }
        }
    private fun animateShutterPress() {
        captureButton.animate()
            .scaleX(0.88f).scaleY(0.88f)
            .setDuration(80)
            .withEndAction {
                captureButton.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }

    private fun flashScreen() {
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 0.8f
        flashOverlay.animate().alpha(0f).setDuration(200).start()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        liveImageView = findViewById(R.id.liveImageView)
        captureButton = findViewById(R.id.captureButton)
        focusRing = findViewById(R.id.focusRing)
        zoomLabel = findViewById(R.id.zoomLabel)
        recordingIndicator = findViewById(R.id.recordingIndicator)
        modePhotoLabel = findViewById(R.id.modePhotoLabel)
        modeVideoLabel = findViewById(R.id.modeVideoLabel)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        cameraExecutor = Executors.newSingleThreadExecutor()
        flashOverlay = findViewById(R.id.flashOverlay)
        processingIndicator = findViewById(R.id.processingIndicator)
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        captureButton.setOnClickListener {
            animateShutterPress()

            if (currentMode == CaptureMode.PHOTO) takePhoto() else toggleRecording()
        }
        findViewById<ImageButton>(R.id.galleryButton).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        switchCameraButton.setOnClickListener { switchCamera() }
        modePhotoLabel.setOnClickListener { setMode(CaptureMode.PHOTO) }
        modeVideoLabel.setOnClickListener { setMode(CaptureMode.VIDEO) }

        setupFilterButtons()
        setupAdjustmentPanel()
        setupGestures()
        updateModeUI()
        loadLastPhotoThumbnail()

        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (hasCameraPermission()) startCamera()
        if (missing.isNotEmpty()) permissionsLauncher.launch(missing.toTypedArray())
    }

    // ---------- Permissions ----------

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    // ---------- UI setup ----------
    private fun rebuildActiveMatrix() {
        activeColorMatrix =
            if (selectedFilter == FilterType.ORIGINAL && adjustments.isDefault) {
                null
            } else {
                ColorMatrix(getColorMatrixForFilter(selectedFilter)).apply {
                    if (!adjustments.isDefault) postConcat(adjustments.toColorMatrix())
                }
            }
    }

    private fun setupAdjustmentPanel() {
        val panel = findViewById<View>(R.id.adjustPanel)
        val rows = findViewById<LinearLayout>(R.id.adjustRows)
        val doneButton = findViewById<TextView>(R.id.adjustDone)
        doneButton.isSelected = true // white "primary" look

        findViewById<ImageButton>(R.id.adjustButton).setOnClickListener {
            panel.visibility = View.VISIBLE
        }
        doneButton.setOnClickListener { panel.visibility = View.GONE }
        findViewById<TextView>(R.id.adjustReset).setOnClickListener {
            adjustResetters.forEach { reset -> reset() }
            resetExposure()
        }

        addAdjustRow(
            parent = rows, label = "Brightness", min = -80f, max = 80f, default = 0f,
            format = { String.format(Locale.US, "%+.0f", it) },
            onChange = { adjustments.brightness = it; rebuildActiveMatrix() }
        )
        addAdjustRow(
            parent = rows, label = "Contrast", min = 0.5f, max = 1.5f, default = 1f,
            format = { String.format(Locale.US, "%.0f%%", it * 100) },
            onChange = { adjustments.contrast = it; rebuildActiveMatrix() }
        )
        addAdjustRow(
            parent = rows, label = "Saturation", min = 0f, max = 2f, default = 1f,
            format = { String.format(Locale.US, "%.0f%%", it * 100) },
            onChange = { adjustments.saturation = it; rebuildActiveMatrix() }
        )
        addAdjustRow(
            parent = rows, label = "Hue", min = -180f, max = 180f, default = 0f,
            format = { String.format(Locale.US, "%+.0f°", it) },
            onChange = { adjustments.hue = it; rebuildActiveMatrix() }
        )
        addAdjustRow(
            parent = rows, label = "Warmth", min = -100f, max = 100f, default = 0f,
            format = { String.format(Locale.US, "%+.0f", it) },
            onChange = { adjustments.warmth = it; rebuildActiveMatrix() }
        )
    }

    private fun addAdjustRow(
        parent: LinearLayout,
        label: String,
        min: Float,
        max: Float,
        default: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit
    ) {
        val row = layoutInflater.inflate(R.layout.item_adjust_row, parent, false)
        val seek = row.findViewById<SeekBar>(R.id.rowSeek)
        val valueText = row.findViewById<TextView>(R.id.rowValue)
        row.findViewById<TextView>(R.id.rowLabel).text = label

        // Sliders only deal in whole numbers 0..200, so map that to min.max
        val defaultProgress = ((default - min) / (max - min) * seek.max).toInt()
        seek.progress = defaultProgress
        valueText.text = format(default)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val value = min + (max - min) * progress / sb.max
                valueText.text = format(value)
                onChange(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        parent.addView(row)
        adjustResetters.add { seek.progress = defaultProgress }
    }

    private fun setupExposure() {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        val seek = findViewById<SeekBar>(R.id.exposureSeek)
        val label = findViewById<TextView>(R.id.exposureValue)

        seek.setOnSeekBarChangeListener(null)

        if (!state.isExposureCompensationSupported) {
            seek.isEnabled = false
            label.text = "N/A"
            return
        }

        val range = state.exposureCompensationRange
        exposureMinIndex = range.lower
        exposureStepEv = state.exposureCompensationStep.toFloat()

        seek.isEnabled = true
        seek.max = range.upper - range.lower
        seek.progress = -range.lower // the middle: index 0 = no compensation
        label.text = "0.0 EV"

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val index = progress + exposureMinIndex
                camera?.cameraControl?.setExposureCompensationIndex(index)
                label.text = String.format(Locale.US, "%+.1f EV", index * exposureStepEv)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun resetExposure() {
        findViewById<SeekBar>(R.id.exposureSeek).progress = -exposureMinIndex
    }
    private fun setupFilterButtons() {
        filterChips = mapOf(
            FilterType.ORIGINAL to findViewById<TextView>(R.id.filterOriginal),
            FilterType.GRAYSCALE to findViewById<TextView>(R.id.filterGrayscale),
            FilterType.SEPIA to findViewById<TextView>(R.id.filterSepia),
            FilterType.INVERT to findViewById<TextView>(R.id.filterInvert),
            FilterType.BRIGHT to findViewById<TextView>(R.id.filterBright),
            FilterType.VINTAGE to findViewById<TextView>(R.id.filterVintage),
            FilterType.POLAROID to findViewById<TextView>(R.id.filterPolaroid),
            FilterType.COOL_RETRO to findViewById<TextView>(R.id.filterCoolRetro),
            FilterType.CROSS_PROCESS to findViewById<TextView>(R.id.filterCrossProcess)
        )
        filterChips.forEach { (type, chip) -> chip.setOnClickListener { selectFilter(type) } }
        selectFilter(FilterType.ORIGINAL)
    }

    private fun selectFilter(type: FilterType) {
        selectedFilter = type
        rebuildActiveMatrix()
        filterChips.forEach { (t, chip) -> chip.isSelected = (t == type) }

        val scroll = findViewById<HorizontalScrollView>(R.id.filterScroll)
        filterChips[type]?.let { chip ->
            scroll.post {
                scroll.smoothScrollTo(chip.left - scroll.width / 2 + chip.width / 2, 0)
            }
        }
    }

    private fun setMode(mode: CaptureMode) {
        if (activeRecording != null) return // no switching mid-recording
        currentMode = mode
        updateModeUI()
    }

    private fun updateModeUI() {
        val isPhoto = currentMode == CaptureMode.PHOTO

        modePhotoLabel.setBackgroundResource(if (isPhoto) R.drawable.mode_selected_bg else 0)
        modeVideoLabel.setBackgroundResource(if (isPhoto) 0 else R.drawable.mode_selected_bg)
        modePhotoLabel.setTextColor(if (isPhoto) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
        modeVideoLabel.setTextColor(if (isPhoto) 0xFFAAAAAA.toInt() else 0xFFFFFFFF.toInt())

        captureButton.setBackgroundResource(
            if (isPhoto) R.drawable.shutter_button else R.drawable.shutter_button_video
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val zoomState = cam.cameraInfo.zoomState.value ?: return false

                    currentZoomRatio = (currentZoomRatio * detector.scaleFactor)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)

                    cam.cameraControl.setZoomRatio(currentZoomRatio)
                    zoomLabel.text = String.format(Locale.US, "%.1fx", currentZoomRatio)

                    zoomLabel.animate().cancel()
                    zoomLabel.alpha = 1f
                    zoomHandler.removeCallbacks(hideZoomRunnable)
                    zoomHandler.postDelayed(hideZoomRunnable, 1200)
                    return true
                }
            })

        liveImageView.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP &&
                event.pointerCount == 1 &&
                !scaleGestureDetector.isInProgress
            ) {
                focusOnTouch(event.x, event.y)
            }
            view.performClick()
            true
        }
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            imageCapture = ImageCapture.Builder().build()

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) } }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    currentCameraSelector,
                    imageCapture,
                    imageAnalysis,
                    videoCapture
                )
                setupExposure()
            } catch (exc: Exception) {
                exc.printStackTrace()
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        if (activeRecording != null) return
        currentCameraSelector =
            if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA
        currentZoomRatio = 1f
        zoomLabel.text = "1.0x"
        startCamera()
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
            val filtered = applyFilter(rotated)
            runOnUiThread { liveImageView.setImageBitmap(filtered) }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
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

    private fun focusOnTouch(x: Float, y: Float) {
        val cam = camera ?: return

        val factory = SurfaceOrientedMeteringPointFactory(
            liveImageView.width.toFloat(),
            liveImageView.height.toFloat()
        )
        val action = FocusMeteringAction.Builder(factory.createPoint(x, y))
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
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

    // ---------- Photo ----------

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        flashScreen()
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
                    val original = BitmapFactory.decodeFile(tempFile.absolutePath)
                    val corrected = correctOrientation(original, tempFile.absolutePath)
                    saveBitmapToGallery(applyFilter(corrected))
                }
            }
        )
    }

    private fun correctOrientation(bitmap: Bitmap, imagePath: String): Bitmap {
        val exif = ExifInterface(imagePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
            loadLastPhotoThumbnail()
        } ?: run {
            Toast.makeText(this, "Failed to save photo", Toast.LENGTH_SHORT).show()
        }
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
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                findViewById<ImageButton>(R.id.galleryButton).setImageURI(uri)
            }
        }
    }

    // ---------- Video ----------

    private fun toggleRecording() {
        if (activeRecording != null) stopRecording() else startRecording()
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (!hasAudioPermission()) {
            Toast.makeText(this, "Allow the microphone, then tap record again", Toast.LENGTH_LONG).show()
            permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        val videoCapture = videoCapture ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/FilteredCam")
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        activeRecording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recordingSeconds = 0
                        recordingIndicator.text = "● REC 00:00"
                        recordingIndicator.visibility = View.VISIBLE
                        recordingHandler.post(recordingTimerRunnable)
                        captureButton.setBackgroundResource(R.drawable.shutter_recording)
                    }
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        recordingIndicator.visibility = View.GONE
                        recordingHandler.removeCallbacks(recordingTimerRunnable)
                        updateModeUI()

                        if (!event.hasError()) {
                            if (activeColorMatrix != null) {
                                Toast.makeText(this, "Video saved! Applying filter...", Toast.LENGTH_SHORT).show()
                                filterLastRecordedVideo(event.outputResults.outputUri)
                            } else {
                                Toast.makeText(this, "Video saved!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this, "Recording error: ${event.error}", Toast.LENGTH_LONG).show()
                        }
                    }
                    else -> {}
                }
            }
    }

    private fun stopRecording() {
        activeRecording?.stop()
    }

    private fun filterLastRecordedVideo(videoUri: Uri) {
        processingIndicator.visibility = View.VISIBLE

        VideoFilterProcessor.applyFilterToVideo(
            context = this,
            sourceUri = videoUri,
            colorMatrix = activeColorMatrix ?: ColorMatrix(),
            onSuccess = { _ ->
                runOnUiThread {
                    processingIndicator.visibility = View.GONE
                    Toast.makeText(this, "Filtered video saved!", Toast.LENGTH_LONG).show()
                }
                // Remove the unfiltered original now that filtering succeeded
                contentResolver.delete(videoUri, null, null)
            },
            onError = { error ->
                runOnUiThread {
                    processingIndicator.visibility = View.GONE
                    Toast.makeText(this, "Video filter failed: $error", Toast.LENGTH_LONG).show()
                }
                // Keep the original if filtering failed
            }
        )
    }
    // ---------- Filters ----------

    private fun getColorMatrixForFilter(filter: FilterType): ColorMatrix {
        return when (filter) {
            FilterType.ORIGINAL -> ColorMatrix()
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
        }
    }

    private fun applyFilter(bitmap: Bitmap): Bitmap {
        val matrix = activeColorMatrix ?: return bitmap

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(result).drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }
    override fun onDestroy() {
        super.onDestroy()
        recordingHandler.removeCallbacks(recordingTimerRunnable)
        activeRecording?.stop()
        shutterSound.release()
        cameraExecutor.shutdown()
        zoomHandler.removeCallbacks(hideZoomRunnable)
    }
}