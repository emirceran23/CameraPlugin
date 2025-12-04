    package com.example.camera2testapp
    
    import android.Manifest
    import android.content.pm.PackageManager
    import android.graphics.Bitmap
    import androidx.exifinterface.media.ExifInterface
    import android.os.Bundle
    import android.os.Looper
    import android.util.Log
    import android.view.Surface
    import android.view.View
    import android.widget.Toast
    import androidx.appcompat.app.AppCompatActivity
    import androidx.core.app.ActivityCompat
    import androidx.core.content.ContextCompat
    import com.example.camera2testapp.databinding.ActivityMainBinding
    import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    import com.google.mediapipe.tasks.core.BaseOptions
    import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
    import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions
    import android.content.res.Configuration
    import kotlin.math.hypot
    import android.content.ContentValues
    import android.content.Context
    import android.graphics.BitmapFactory
    import android.graphics.PointF
    import android.os.VibrationEffect
    import android.os.Vibrator
    import android.provider.MediaStore
    import android.util.Size
    import android.util.SparseIntArray
    import com.google.mlkit.vision.common.InputImage
    import com.google.mlkit.vision.face.FaceDetection
    import com.google.mlkit.vision.face.FaceDetectorOptions
    import android.media.AudioManager
    import android.media.ToneGenerator
    import android.net.Uri
    import androidx.camera.lifecycle.ProcessCameraProvider
    import androidx.camera.core.Camera
    import androidx.camera.core.ImageCapture
    import androidx.camera.core.ImageAnalysis
    import androidx.camera.core.Preview
    import androidx.camera.core.CameraSelector
    import androidx.camera.core.ExperimentalGetImage
    import androidx.camera.core.ImageCaptureException
    import java.util.concurrent.Executors
    import androidx.camera.core.ImageProxy
    import androidx.camera.core.resolutionselector.ResolutionSelector
    import com.google.mediapipe.framework.image.BitmapImageBuilder
    import android.graphics.ImageFormat
    import android.graphics.YuvImage
    import android.graphics.Rect
    import java.io.ByteArrayOutputStream
    import java.io.File
    import java.io.FileOutputStream
    import android.os.Environment
    import androidx.camera.camera2.interop.Camera2CameraInfo
    import android.hardware.camera2.CameraCharacteristics
    import androidx.camera.camera2.interop.ExperimentalCamera2Interop
    
    
    
    private var finalDistanceValue: Float? = null
    private var lastPitch: Float? = null
    private var lastYaw: Float? = null
    private var lastRoll: Float? = null
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    
    private var cameraXConfig: CameraXConfig = CameraXConfig.highQuality()
    
    private var isErrorActive = false
    private var isProcessing = false
    private var isCameraBound = false
    private var isValid = false
    
    private var capturedPitch: Float? = null
    private var capturedYaw: Float? = null
    private var capturedRoll: Float? = null
    
    // head‑pose tolerance (in degrees). Default to 3°.
    private var headPoseThreshold = 3f
    private var isDistanceCheckEnabled = true
    
    private val uiHandler = android.os.Handler(Looper.getMainLooper())
    
    
    class MainActivity : AppCompatActivity() {
        private val ORIENTATIONS = SparseIntArray()
    
        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
    
        private lateinit var binding: ActivityMainBinding
    
        // The face landmarker provided by MediaPipe Tasks.
        private lateinit var faceLandmarker: FaceLandmarker
        private lateinit var gridView: CameraGridView
    
        // Flag to avoid repeated capture on every frame.
        private var isCapturing = false
        // Calibration flag and focal length (in mm, as read from EXIF)
        private var calibrationFocalLength: Float? = null
        private var isCalibrated = false
    
        companion object {
            private const val CAMERA_PERMISSION_CODE = 100
            private const val TAG = "MainActivity"
    
            // Real average iris diameter in mm
            private const val REAL_IRIS_DIAMETER_MM = 11.7f
            private var SENSOR_WIDTH_MM =4.1f  // Example value (adjust for your device)
        }
    
        @ExperimentalCamera2Interop
        @ExperimentalGetImage
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
    
            // Check if we should finish the activity
            if (intent.getBooleanExtra("finish", false)) {
                finish()
                return
            }
    
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            gridView = binding.gridView
    
            // Initialize distance check switch state
            binding.switchDistanceCheck.isChecked = isDistanceCheckEnabled
    
            // Request Camera Permission if not granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_CODE
                )
            } else {
                setupCamera()
    
            }
    
            applyPreviewAspectRatio()
    
            // Initialize the face detector
            initFaceDetector()
    
            // Make the capture button visible
            binding.btnCapture.visibility = View.VISIBLE
    
            // Threshold chip group listener
            binding.chipThresholdGroup.setOnCheckedStateChangeListener { group, checkedIds ->
                when (checkedIds.firstOrNull()) {
                    binding.chipThreshold0.id -> headPoseThreshold = 1f
                    binding.chipThreshold3.id -> headPoseThreshold = 3f
                    binding.chipThreshold5.id -> headPoseThreshold = 5f
                }
            }
    
            binding.switchDistanceCheck.setOnCheckedChangeListener { _, isChecked ->
                isDistanceCheckEnabled = isChecked
                val msg = if (isChecked) "📏 Mesafe kontrolü AÇIK" else "📏 Mesafe kontrolü KAPALI"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
    
            // Set up capture button click (manual capture)
            binding.btnCapture.setOnClickListener {
                capturePhotoWithCameraX()
            }
        }

        @ExperimentalCamera2Interop
        @ExperimentalGetImage
        private fun setupCamera() {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    startCameraWithConfig()

                    binding.previewView.post {
                        SENSOR_WIDTH_MM = getSensorWidth()
                        Log.d(TAG, "Sensör genişliği: $SENSOR_WIDTH_MM mm")

                        if (!isCalibrated) {
                            captureCalibrationPhoto()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Camera init failed", e)
                    Toast.makeText(this, "Kamera başlatılamadı", Toast.LENGTH_LONG).show()
                }
            }, ContextCompat.getMainExecutor(this))
        }


        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            applyPreviewAspectRatio()
        }

        private fun applyPreviewAspectRatio() {
            val lp = binding.previewContainer.layoutParams
            val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

            if (lp is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
                lp.dimensionRatio = if (isPortrait) "3:4" else "4:3"
                binding.previewContainer.layoutParams = lp
            }
            // Değilse hiç dokunma, crash olmasın
        }
    
        /**
         * Initialize MediaPipe FaceLandmarker.
         */
        private fun initFaceDetector() {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()
    
            val options = FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinFaceDetectionConfidence(0.8f)
                .setMinTrackingConfidence(0.7f)
                .build()
    
            faceLandmarker = FaceLandmarker.createFromOptions(this, options)
        }
    
        @ExperimentalCamera2Interop
        @ExperimentalGetImage
        private fun processFrameFromCameraX(imageProxy: ImageProxy) {
            if (isProcessing) {
                imageProxy.close()
                return
            }
            isProcessing = true
    
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                isProcessing = false
                imageProxy.close()
                return
            }
    
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
    
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .enableTracking()
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.15f)
                .build()
            val detector = FaceDetection.getClient(options)
    
            // Convert imageProxy to Bitmap for MediaPipe analysis
            val bitmap = imageProxyToBitmap(imageProxy)
    
            // --- MLKit part for head pose ---
            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        val face = faces[0]
                        val mlkitPitch = face.headEulerAngleX
                        val mlkitYaw = face.headEulerAngleY
                        val mlkitRoll = face.headEulerAngleZ
                        lastPitch = mlkitPitch
                        lastYaw = mlkitYaw
                        lastRoll = mlkitRoll
    
                        runOnUiThread {
                            binding.mlkitPoseTextView.text =
                                "Pitch: ${mlkitPitch.toInt()}°\n" +
                                "Yaw: ${mlkitYaw.toInt()}°\n" +
                                "Roll: ${mlkitRoll.toInt()}°"
                        }
    
                        // Now run MediaPipe for iris-based distance calculation
                        if (bitmap != null && calibrationFocalLength != null) {
                            val previewBitmap = binding.previewView.bitmap
                            previewBitmap?.let { runMediaPipeAnalysis(it) }
                        }
                    } else {
                        lastPitch = null
                        lastYaw = null
                        lastRoll = null
                        runOnUiThread {
                            binding.mlkitPoseTextView.text = "Yüz algılanamadı"
                            binding.headPoseArrowView.visibility = View.GONE
                            binding.orientationTextView.text = "Yüz algılanamadı"
                            binding.distanceTextView.text = "Mesafe ölçülemiyor"
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit yüz algılama hatası: ${e.message}")
                    lastPitch = null
                    lastYaw = null
                    lastRoll = null
                    runOnUiThread {
                        binding.mlkitPoseTextView.text = "Yüz algılanamadı"
                        binding.headPoseArrowView.visibility = View.GONE
                    }
                }
                .addOnCompleteListener {
                    isProcessing = false
                    imageProxy.close()
                }
        }
    
        @ExperimentalCamera2Interop
        private fun runMediaPipeAnalysis(bitmap: Bitmap) {
            if (!::faceLandmarker.isInitialized) return
    
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = faceLandmarker.detect(mpImage)
            val faceLandmarksList = result.faceLandmarks()
    
            if (faceLandmarksList.isNotEmpty()) {
                val landmarks = faceLandmarksList[0]
    
                // 1. Distance estimation using iris
                val (distanceValue, distanceMessage) = estimateDistanceUsingIris(landmarks, bitmap)
                finalDistanceValue = distanceValue
    
                // 2. Check if head pose is valid
                if (lastPitch == null || lastYaw == null || lastRoll == null) {
                    runOnUiThread {
                        binding.orientationTextView.text = "Yüz pozisyonu hesaplanıyor..."
                    }
                    return
                }
    
                // Update arrow view with head pose
                runOnUiThread {
                    binding.headPoseArrowView.visibility = View.VISIBLE
                    binding.headPoseArrowView.updateHeadPose(lastPitch!!, lastYaw!!, lastRoll!!, headPoseThreshold)
                    binding.distanceTextView.text = distanceMessage
                }
    
                // Check if pose is aligned
                val isPoseAligned = kotlin.math.abs(lastPitch!!) <= headPoseThreshold &&
                                   kotlin.math.abs(lastYaw!!) <= headPoseThreshold &&
                                   kotlin.math.abs(lastRoll!!) <= headPoseThreshold
    
                val isDistanceOk = !isDistanceCheckEnabled || (distanceValue >= 30.0 && distanceValue <= 35.0)
    
                // Auto-capture logic
                if (isPoseAligned && isDistanceOk) {
                    runOnUiThread {
                        binding.orientationTextView.text = "✅ Baş pozisyonu uygun"
                    }
    
                    if (!isCapturing) {
                        isCapturing = true
                        capturedPitch = lastPitch
                        capturedYaw = lastYaw
                        capturedRoll = lastRoll
    
                        // Vibrate to give feedback
                        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
    
    
                        capturePhotoWithCameraX()
    
                    }
                } else {
                    // Generate feedback message
                    val orientationMessage = if (isDistanceCheckEnabled && (distanceValue < 30.0 || distanceValue > 35.0)) {
                        "📏 Kamera ile hasta arası mesafeyi 30-35 cm arasına getirin."
                    } else {
                        "🎯 Okları takip ederek başınızı hizalayın"
                    }
    
                    runOnUiThread {
                        binding.orientationTextView.text = orientationMessage
                    }
                }
    
                // Send data to Unity
                val distance = finalDistanceValue ?: return
                val headPoseDict = HashMap<String, Float>()
                val isDistanceCheckEnabledFloat = if (isDistanceCheckEnabled) 1f else 0f
    
                headPoseDict["pitch"] = lastPitch!!
                headPoseDict["yaw"] = lastYaw!!
                headPoseDict["roll"] = lastRoll!!
                headPoseDict["distance"] = distance
                headPoseDict["isDistanceCheckEnabled"] = isDistanceCheckEnabledFloat
    
                try {
                    CameraPlugin.sendHeadPoseToUnity(headPoseDict)
                    Log.d("MLKit", "Head Pose Sent to Unity")
                } catch (e: Exception) {
                    Log.e("MLKit", "Error sending head pose to Unity: ${e.message}")
                }
            }
        }
    
        /**
         * Convert ImageProxy (YUV format) to Bitmap for MediaPipe processing
         */
        private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
            return try {
                val yBuffer = imageProxy.planes[0].buffer
                val uBuffer = imageProxy.planes[1].buffer
                val vBuffer = imageProxy.planes[2].buffer
    
                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()
    
                val nv21 = ByteArray(ySize + uSize + vSize)
    
                yBuffer.get(nv21, 0, ySize)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)
    
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
                val imageBytes = out.toByteArray()
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            } catch (e: Exception) {
                Log.e(TAG, "Error converting ImageProxy to Bitmap: ${e.message}")
                null
            }
        }
    
        @ExperimentalCamera2Interop
        private fun getSensorWidth(): Float {
            if (camera == null) return 4.1f // Kamera hazır değilse varsayılan değer
    
            // CameraX nesnesinden Camera2 bilgilerine erişim
            val camera2Info = Camera2CameraInfo.from(camera!!.cameraInfo)
    
            // Sensör boyutunu sorgula
            val sensorSize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    
            return if (sensorSize != null) {
                // Genellikle width daha büyüktür ama cihazın oryantasyonuna göre (landscape/portrait)
                // sensörün "genişlik" kabul ettiği kenar değişmez, fiziksel genişliği alırız.
                Log.d("SensorInfo", "Sensör: ${sensorSize.width}mm x ${sensorSize.height}mm")
                sensorSize.width // Örneğin 6.40 mm dönebilir
            } else {
                Log.w("SensorInfo", "Sensör boyutu okunamadı, varsayılan değer kullanılıyor.")
                4.1f // Varsayılan bir değer (ortalama bir mobil sensör)
            }
        }
    
        @ExperimentalCamera2Interop
        private fun estimateDistanceUsingIris(
            landmarks: List<NormalizedLandmark>,
            bitmap: Bitmap
        ): Pair<Float, String> {
            // Ensure calibration and sensor data are available
            if (calibrationFocalLength == null) {
                return Pair(0f, "⚠️ Önce kalibrasyon yapılmalı!")
            }
    
            if (SENSOR_WIDTH_MM <= 0f) {
                return Pair(0f, "⚠️ Sensör genişliği tespit edilemedi!")
            }
    
            val leftIrisLandmarks = listOf(468, 469, 470, 471, 472)
            val rightIrisLandmarks = listOf(473, 474, 475, 476, 477)
    
            val leftIrisDiameterPixels = calculateIrisDiameter(leftIrisLandmarks, landmarks, bitmap)
            val rightIrisDiameterPixels = calculateIrisDiameter(rightIrisLandmarks, landmarks, bitmap)
    
            val averageIrisDiameterPixels = (leftIrisDiameterPixels + rightIrisDiameterPixels) / 2f
    
            // Guard against division by zero or invalid measurements
            if (averageIrisDiameterPixels <= 0f) {
                return Pair(0f, "⚠️ İris çapı ölçülemedi")
            }
    
            // Convert focal length from mm to pixels for the current image width
            val focalLengthPx = calibrationFocalLength!! * bitmap.width / SENSOR_WIDTH_MM
            val distanceToCameraMm = (REAL_IRIS_DIAMETER_MM * focalLengthPx) / averageIrisDiameterPixels
            val distanceToCameraCm = distanceToCameraMm / 10
    
            val message = "📏 Tahmini Mesafe: %.2f cm".format(distanceToCameraCm)
            return Pair(distanceToCameraCm, message)
        }
    
        private fun calculateIrisDiameter(
            irisIndices: List<Int>,
            landmarks: List<NormalizedLandmark>,
            bitmap: Bitmap
        ): Float {
            // Landmark'ları piksel koordinatına çevir
            val pixelPoints = irisIndices.map {
                val landmark = landmarks[it]
                PointF(landmark.x() * bitmap.width, landmark.y() * bitmap.height)
            }
    
            // Ortalama merkez noktayı hesapla
            val centerX = pixelPoints.map { it.x }.average().toFloat()
            val centerY = pixelPoints.map { it.y }.average().toFloat()
    
            // Merkezden tüm noktalara olan uzaklıkları (yarıçap) al
            val radii = pixelPoints.map {
                hypot(it.x - centerX, it.y - centerY)
            }
    
            // Ortalama yarıçap * 2 = çap
            return radii.average().toFloat() * 2
        }
    
        /**
         * Capture a calibration photo to extract focal length from EXIF
         */
        private fun captureCalibrationPhoto() {
            val ic = imageCapture ?: return
    
            val fileName = "calibration_${System.currentTimeMillis()}.jpg"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName)
    
            val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
    
            ic.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        parseCalibrationImage(file)
                    }
    
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Calibration capture failed: ${exc.message}")
                    }
                }
            )
        }
    
        private fun parseCalibrationImage(file: File) {
            try {
                val exif = ExifInterface(file.absolutePath)
                val focalLengthStr = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                if (focalLengthStr != null) {
                    val parts = focalLengthStr.split("/")
                    if (parts.size == 2) {
                        val numerator = parts[0].toFloatOrNull() ?: 0f
                        val denominator = parts[1].toFloatOrNull() ?: 1f
                        calibrationFocalLength = numerator / denominator
                        isCalibrated = true
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Kalibrasyon tamam: Odak Uzaklığı = $calibrationFocalLength mm",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        Log.d(TAG, "Calibration focal length: $calibrationFocalLength mm")
                    }
                } else {
                    Log.e(TAG, "Focal length not found in EXIF!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "parseCalibrationImage error: ${e.message}")
            }
            finally {
                if (file.exists()) {
                    val deleted = file.delete()
                    Log.d(TAG, "Calibration image deleted: $deleted (${file.absolutePath})")
                }
            }
        }
    
        fun Context.playErrorSound() {
            // 🔊 Play short system error tone
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_NACK, 150)
    
            // 📳 Vibrate for 200ms (requires VIBRATE permission)
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    
        @ExperimentalCamera2Interop
        @ExperimentalGetImage
        private fun startCameraWithConfig() {
            val provider = cameraProvider ?: return
    
            // Use windowManager instead of previewView.display to avoid null pointer exception
            val rotation = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
    
            val preview = cameraXConfig.buildPreview(rotation).also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
    
            imageCapture = cameraXConfig.buildImageCapture(rotation).apply {
                flashMode = ImageCapture.FLASH_MODE_ON
            }
    
            val analysisExecutor = Executors.newSingleThreadExecutor()
            imageAnalysis = cameraXConfig.buildImageAnalysis(rotation).also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrameFromCameraX(imageProxy)
                }
            }
    
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this,
                cameraXConfig.cameraSelector,
                preview,
                imageCapture,
                imageAnalysis
            )
    
            isCameraBound = true
            val hasFlash = camera?.cameraInfo?.hasFlashUnit() ?: false
            Log.d(TAG, "Selected camera hasFlashUnit = $hasFlash")
        }
        private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
            val matrix = android.graphics.Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap // Döndürme gerekmiyorsa orijinali döndür
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    
        private fun validatePoseAndWriteExif(uri: Uri, distanceValue: Float?) {
            try {
                // 1. InputStream'i aç (Hem Bitmap hem EXIF için gerekli)
                var inputStream = contentResolver.openInputStream(uri) ?: return
    
                // 2. EXIF Oryantasyonunu oku
                // Not: InputStream okunduktan sonra tüketilebilir, o yüzden dikkatli olunmalı.
                // ExifInterface, stream'in başını okur.
                val exifInterface = ExifInterface(inputStream)
                val orientation = exifInterface.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                inputStream.close() // İlk stream'i kapat
    
                // 3. Bitmap için Stream'i tekrar aç (Çünkü ilk stream okunmuş olabilir)
                inputStream = contentResolver.openInputStream(uri) ?: return
                val bytes = inputStream.readBytes()
                inputStream.close()

                val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (rawBitmap == null) {
                    Log.e(TAG, "Bitmap decode edilemedi")
                    playErrorSound()
                    deleteImage(uri)
                    showErrorMessage("❌ Fotoğraf okunamadı.")
                    return
                }

                val correctedBitmap = rotateBitmap(rawBitmap, orientation)


                // 5. MLKit InputImage oluştur (Artık bitmap düz olduğu için rotation 0 veriyoruz)
                val inputImage = InputImage.fromBitmap(correctedBitmap, 0)
    
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL) // Göz olasılıkları için gerekli olabilir
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // Göz açık/kapalı için ZORUNLU
                    .enableTracking()
                    .build()
    
                val detector = FaceDetection.getClient(options)
    
                detector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        if (faces.isEmpty()) {
                            playErrorSound()
                            deleteImage(uri)
                            showErrorMessage("❌ Yüz algılanamadı.")
                            // Hata ayıklama için log: Bitmap boyutlarını yazdır
                            Log.w(TAG, "❌ Yüz yok. Bitmap: ${correctedBitmap.width}x${correctedBitmap.height}")
                            return@addOnSuccessListener
                        }
    
                        val face = faces[0]
                        val pitch = face.headEulerAngleX
                        val yaw = face.headEulerAngleY
                        val roll = face.headEulerAngleZ
    
                        // Göz açıklığı değerleri bazen null gelebilir, Elvis operatörü ile 0f atayalım
                        val rightProb = face.rightEyeOpenProbability ?: 0f
                        val leftProb = face.leftEyeOpenProbability ?: 0f
    
                        Log.d(TAG, "Face Detected: Pitch:$pitch, Yaw:$yaw, Roll:$roll, RightEye:$rightProb, LeftEye:$leftProb")
    
                        // Pozisyon kontrolü
                        val isPoseAligned =
                            kotlin.math.abs(pitch) <= headPoseThreshold &&
                                    kotlin.math.abs(yaw) <= headPoseThreshold &&
                                    kotlin.math.abs(roll) <= headPoseThreshold
    
                        if (!isPoseAligned) {
                            val issues = mutableListOf<String>()
                            if (kotlin.math.abs(pitch) > headPoseThreshold) issues.add("pitch")
                            if (kotlin.math.abs(yaw) > headPoseThreshold) issues.add("yaw")
                            if (kotlin.math.abs(roll) > headPoseThreshold) issues.add("roll")
    
                            val msg = "❌ Baş pozisyonu kaydı (${issues.joinToString(", ")}). Tekrar deneyin."
                            showErrorMessage(msg)
                            deleteImage(uri)
                            playErrorSound()
                            return@addOnSuccessListener
                        }
    
                        // Göz kontrolü
                        if (rightProb < 0.5f || leftProb < 0.5f) { // Eşik değeri biraz düşürdüm (0.6 -> 0.5) bazen MLKit katı olabiliyor
                            showErrorMessage("❌ Gözlerinizi Açın.\nFotoğraf çekilirken gözler kapalıydı.")
                            playErrorSound()
                            deleteImage(uri)
    
                            return@addOnSuccessListener
                        }
    
                        isValid = true
    
                        // ✅ Her şey yolunda -> EXIF'e yorum yaz
                        try {
                            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                                val fileExif = ExifInterface(pfd.fileDescriptor)
    
                                val pitchText = "%.2f".format(capturedPitch ?: pitch)
                                val yawText = "%.2f".format(capturedYaw ?: yaw)
                                val rollText = "%.2f".format(capturedRoll ?: roll)
                                val distText = "%.2f".format(distanceValue ?: 0f)
    
                                val userComment = "Distance: $distText cm, Pitch: $pitchText, Yaw: $yawText, Roll: $rollText"
                                fileExif.setAttribute(ExifInterface.TAG_USER_COMMENT, userComment)
    
                                // ÖNEMLİ: İşlenmiş bitmap'i Unity'e gönderdiğimiz için,
                                // dosyanın oryantasyon etiketini de "Normal" (1) yapmalıyız ki
                                // başka yerde açılınca tekrar dönmesin. Ancak burada dosyayı yeniden
                                // yazmıyoruz (sadece tag güncelliyoruz), o yüzden orijinal orientation kalmalı.
                                fileExif.saveAttributes()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "EXIF yazma hatası: ${e.message}")
                        }
    
                        // Unity'e DÜZELTİLMİŞ bitmap'i gönder
                        // Unity tarafında da resim yan gitmeyecektir böylece.
                        CameraPlugin.sendResultToUnity(correctedBitmap)
    
                        runOnUiThread {
                            Toast.makeText(this, "Fotoğraf başarıyla işlendi", Toast.LENGTH_SHORT).show()
                        }
    
                        // Activity'i kapat (Eğer isteniyorsa)
                        if(isValid) finish()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "MLKit hata: ${e.message}")
                        playErrorSound()
                        showErrorMessage("❌ Analiz hatası.")
                    }
    
            } catch (e: Exception) {
                Log.e(TAG, "validatePoseAndWriteExif genel hata: ${e.message}")
                playErrorSound()
                deleteImage(uri)
                showErrorMessage("❌ İşlem hatası.")
            }
        }
        private fun deleteImage(uri: Uri) {
            try {
                contentResolver.delete(uri, null, null)
                Log.d(TAG, "Deleted invalid image: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete image: ${e.message}")
            }
        }
    
        private fun capturePhotoWithCameraX() {
            val ic = imageCapture ?: return
            if (!isCameraBound || ic == null) {
                Log.w(TAG, "⚠️ capturePhotoWithCameraX: ImageCapture şu anda kameraya bağlı değil, çekim iptal.")
                isCapturing = false
                return
            }
    
            if (isErrorActive) {
                Log.d(TAG, "⚠️ Fotoğraf çekimi engellendi: aktif bir hata var.")
                return
            }
    
            val fileName = "Captured_${System.currentTimeMillis()}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            }
    
            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()
    
            ic.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        val uri = result.savedUri
                        if (uri != null) {
                            validatePoseAndWriteExif(uri, finalDistanceValue)
                        }
                        isCapturing = false
    
    
                    }
    
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Capture failed: ${exc.message}", exc)
                        playErrorSound()
                        showErrorMessage("❌ Fotoğraf çekilemedi.")
                        isCapturing = false
                    }
                }
            )
        }
    
    
    
    
        private fun showErrorMessage(message: String) {
            isErrorActive = true
            runOnUiThread {
                binding.captureErrorTextView.apply {
                    text = message
                    visibility = View.VISIBLE
                }
    
    
            }
            // Eski mesaj için bekleyen callback'leri temizle (üst üste binmesinler)
            uiHandler.removeCallbacksAndMessages(null)
    
            // 5 saniye sonra mesajı kaldır ve tekrar denemeye izin ver
            uiHandler.postDelayed({
                binding.captureErrorTextView.visibility = View.GONE
                isErrorActive = false
                isCapturing = false   // auto-capture tekrar tetiklenebilsin
            }, 5000)
        }
    
    
    }