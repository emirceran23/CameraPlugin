package com.example.camera2testapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import android.os.Bundle
import android.os.Handler
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


private var finalDistanceValue: Float? = null
private val handler = Handler(Looper.getMainLooper())
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
private var capturedPitch: Float? = null
private var capturedYaw: Float? = null
private var capturedRoll: Float? = null

// head‑pose tolerance (in degrees). Default to 3°.
private var headPoseThreshold = 3f
private var isDistanceCheckEnabled = true





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
    // For demonstration, approximate 3D face model points in some coordinate system (e.g. mm):
    // (These are fairly standard guess values. Adjust if needed for better accuracy.)
    // Approximate 3D points for face landmarks (in some consistent coordinate system):





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
        private var SENSOR_WIDTH_MM = 4.1f  // Example value (adjust for your device)

    }

    @ExperimentalGetImage
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if we should finish the activity
        if (intent.getBooleanExtra("finish", false)) {
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater) // Initialize FIRST
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startCameraWithConfig()
            }
        }, ContextCompat.getMainExecutor(this))

        setContentView(binding.root)
        gridView = binding.gridView

        // Initialize distance check switch state
        binding.switchDistanceCheck.isChecked = isDistanceCheckEnabled

        
        applyPreviewAspectRatio()
        





        // Initialize the face detector
        initFaceDetector()


        // Make the capture button visible
        binding.btnCapture.visibility = View.VISIBLE
        // inside onCreate()
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

    
    /**
     * Activity yön değişikliklerini dinle ve TextureView'e uygun dönüşümü uygla.
     */


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPreviewAspectRatio()

    }
    

    /**
     * TextureView'in dönüşüm matrisini, cihazın mevcut yönüne göre günceller.
     */
    private fun applyPreviewAspectRatio() {
        val params = binding.previewContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        // Set 3:4 in portrait, 4:3 in landscape
        params.dimensionRatio = if (isPortrait) "3:4" else "4:3"

        binding.previewContainer.layoutParams = params
        binding.previewContainer.requestLayout()
    }



    













    

    

    /**
     * Initialize MediaPipe FaceLandmarker with a calibration step.
     */
    private fun initFaceDetector() {
        // Create BaseOptions with model file (ensure the asset "face_landmarker.task" exists in app/src/main/assets)
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()

        val options = FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions) // REQUIRED!
            .setMinFaceDetectionConfidence(0.8f)
            .setMinTrackingConfidence(0.7f)
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(this, options)
    }


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

        // --- MLKit part (same as your processFrame, minus TextureView bitmap) ---
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
                } else {
                    lastPitch = null
                    lastYaw = null
                    lastRoll = null
                    runOnUiThread {
                        binding.mlkitPoseTextView.text = "Yüz algılanamadı"
                    }
                }

                // TODO: convert imageProxy (YUV) to Bitmap and run MediaPipe + iris distance
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ML Kit yüz algılama hatası: ${e.message}")
                lastPitch = null
                lastYaw = null
                lastRoll = null
                runOnUiThread {
                    binding.mlkitPoseTextView.text = "Yüz algılanamadı"
                }
            }
            .addOnCompleteListener {
                isProcessing = false
                imageProxy.close()
            }
    }

    private fun saveDebugBitmap(bitmap: Bitmap) {
        try {
            val filename = "mediapipe_input_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/MediapipeDebug")
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                }
                Log.d(TAG, "✅ Saved debug bitmap to gallery: $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save debug bitmap: ${e.message}")
        }
    }




    
    

    

    

    
    private fun estimateDistanceUsingIris(
        landmarks: List<NormalizedLandmark>,
        bitmap: Bitmap
    ): Pair<Float, String> {
        // Ensure calibration and sensor data are available
        if (calibrationFocalLength == null) {
            return Pair(0f, "⚠️ Önce kalibrasyon yapılmalı!")
        }

        val sensorWidthMm = SENSOR_WIDTH_MM
        if (sensorWidthMm <= 0f) {
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
        val focalLengthPx = calibrationFocalLength!! * bitmap.width / sensorWidthMm
        val distanceToCameraMm = (REAL_IRIS_DIAMETER_MM * focalLengthPx) / averageIrisDiameterPixels
        var distanceToCameraCm = distanceToCameraMm / 10

        // 🔍 Loglar
        /**
        Log.d("DistanceEstimation", "leftIrisDiameterPixels = $leftIrisDiameterPixels")
        Log.d("DistanceEstimation", "rightIrisDiameterPixels = $rightIrisDiameterPixels")
        Log.d("DistanceEstimation", "averageIrisDiameterPixels = $averageIrisDiameterPixels")
        Log.d("DistanceEstimation", "bitmap.width = ${bitmap.width}")
        Log.d("DistanceEstimation", "calibrationFocalLength = $calibrationFocalLength")
        Log.d("DistanceEstimation", "SENSOR_WIDTH_MM = $SENSOR_WIDTH_MM")
        Log.d("DistanceEstimation", "focalLengthPx = $focalLengthPx")
        Log.d("DistanceEstimation", "distanceToCameraMm = $distanceToCameraMm")
        Log.d("DistanceEstimation", "distanceToCameraCm = $distanceToCameraCm")
        */


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

    @ExperimentalGetImage
    private fun startCameraWithConfig() {
        val provider = cameraProvider ?: return

        val rotation = binding.previewView.display.rotation

        // PREVIEW
        val preview = cameraXConfig.buildPreview(rotation).also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        // IMAGE CAPTURE
        imageCapture = cameraXConfig.buildImageCapture(rotation)

        // IMAGE ANALYSIS
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
    }

    private fun validatePoseAndWriteExif(uri: Uri, distanceValue: Float?) {
        try {
            // Read JPEG back from URI
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bytes = inputStream.readBytes()
            inputStream.close()

            val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            // CameraX already writes JPEG with correct orientation, rotation = 0
            val inputImage = InputImage.fromBitmap(rawBitmap, 0)

            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .enableTracking()
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
            val detector = FaceDetection.getClient(options)

            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        playErrorSound()
                        showErrorMessage("❌ Yüz algılanamadı.")
                        Log.w(TAG, "❌ No face detected. Skipping EXIF write.")
                        return@addOnSuccessListener
                    }

                    val face = faces[0]
                    val pitch = face.headEulerAngleX
                    val yaw = face.headEulerAngleY
                    val roll = face.headEulerAngleZ
                    val rightProb = face.rightEyeOpenProbability
                    val leftProb = face.leftEyeOpenProbability

                    // Head pose alignment (same logic as old saveImageToGallery)
                    val isPoseAligned =
                        kotlin.math.abs(pitch) <= headPoseThreshold &&
                        kotlin.math.abs(yaw) <= headPoseThreshold &&
                        kotlin.math.abs(roll) <= headPoseThreshold

                    if (!isPoseAligned) {
                        val issues = mutableListOf<String>()
                        if (kotlin.math.abs(pitch) > headPoseThreshold) issues.add("pitch")
                        if (kotlin.math.abs(yaw) > headPoseThreshold) issues.add("yaw")
                        if (kotlin.math.abs(roll) > headPoseThreshold) issues.add("roll")

                        val msg = "❌ Baş pozisyonu uygun değil (${issues.joinToString(", ")}). Okları takip edin."
                        showErrorMessage(msg)
                        playErrorSound()
                        Log.w(TAG, msg)
                        return@addOnSuccessListener
                    }

                    if (rightProb == null || leftProb == null) {
                        showErrorMessage("❌ Göz açıklığı algılanamadı.\nYüzünüz yeterince net ya da doğru açıda olmayabilir.")
                        playErrorSound()
                        return@addOnSuccessListener
                    }

                    if (rightProb < 0.6f || leftProb < 0.6f) {
                        showErrorMessage("❌ Gözlerinizi Açın.\nHer iki göz de kapalı görünüyor.")
                        playErrorSound()
                        return@addOnSuccessListener
                    }

                    // ✅ Pose is valid → write EXIF user comment
                    contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)

                        val pitchText = "%.2f".format(capturedPitch ?: pitch)
                        val yawText = "%.2f".format(capturedYaw ?: yaw)
                        val rollText = "%.2f".format(capturedRoll ?: roll)
                        val distText = "%.2f".format(distanceValue ?: 0f)
                        val userComment =
                            "Distance: $distText cm, Pitch: $pitchText°, Yaw: $yawText°, Roll: $rollText°"

                        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, userComment)
                        exif.saveAttributes()
                    }

                    // Send bitmap to Unity, same as old code
                    CameraPlugin.sendResultToUnity(rawBitmap)

                    runOnUiThread {
                        Toast.makeText(this, "Photo saved to Gallery", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed on saved image: ${e.message}")
                    playErrorSound()
                    showErrorMessage("❌ Yüz algılanamadı.")
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error validating/writing EXIF: ${e.message}")
            playErrorSound()
            showErrorMessage("❌ Fotoğraf kaydı sırasında hata oluştu.")
        }
    }
    private fun capturePhotoWithCameraX() {
        val ic = imageCapture ?: return

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
                        // Do what you currently do in saveImageToGallery(image, distanceValue)
                        // but using the URI instead of Image from ImageReader
                        validatePoseAndWriteExif(uri, finalDistanceValue)
                    }
                    isCapturing = false
                    finish()
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


    @ExperimentalGetImage
    override fun onResume() {
        super.onResume()
        try {
            isCapturing = false
            isProcessing = false
            
             if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startCameraWithConfig()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_CODE
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume: ${e.message}")
        }
    }
    
    private fun showErrorMessage(message: String) {
        isErrorActive = true
        runOnUiThread {
            binding.captureErrorTextView.apply {
                text = message
                visibility = View.VISIBLE
            }

            handler.postDelayed({
                binding.captureErrorTextView.visibility = View.GONE
                isErrorActive = false
                isCapturing = false
            }, 5000) // 5 saniye sonra hata mesajı gizlenecek ve yeniden çekim yapılabilir
        }
    }


    override fun onPause() {
        super.onPause()
        try {    
            cameraProvider?.unbindAll() 
            isCapturing = false
            isProcessing = false
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPause: ${e.message}")
        }
    }
    



}