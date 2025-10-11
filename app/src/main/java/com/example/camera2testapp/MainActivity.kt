package com.example.camera2testapp

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import androidx.exifinterface.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camera2testapp.databinding.ActivityMainBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions
import android.content.res.Configuration
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.PointF
import android.graphics.RectF
import android.hardware.camera2.params.MeteringRectangle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Size
import android.util.SparseIntArray
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraMetadata
import android.os.HandlerThread
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.log
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import java.util.concurrent.atomic.AtomicBoolean


private var finalDistanceValue: Float? = null
private val handler = Handler(Looper.getMainLooper())
private val STATE_PREVIEW = 0
private val STATE_WAITING_PRECAPTURE = 1
private val STATE_WAITING_NON_PRECAPTURE = 2
private val STATE_PICTURE_TAKEN = 3
private var mState = STATE_PREVIEW
private var lastPitch: Float? = null
private var lastYaw: Float? = null
private var lastRoll: Float? = null
private var previewSize: Size? = null


private var isErrorActive = false




private var lastEyeFocusX: Int? = null
private var lastEyeFocusY: Int? = null
private var isProcessing = false
private var capturedPitch: Float? = null
private var capturedYaw: Float? = null
private var capturedRoll: Float? = null
private var surfaceWidth  = 0
private var surfaceHeight = 0
private var sensorOrientation = 0
// head‑pose tolerance (in degrees). Default to 3°.
private var headPoseThreshold = 3f
private var isDistanceCheckEnabled = true

// VIVO V21 ONLY - Store last metering rectangle for capture reuse
private var lastMeteringRect: MeteringRectangle? = null

// Flash strategy enums and management
enum class FlashStrategy {
    STANDARD,           // Standard AE precapture
    PRECAPTURE_SEQUENCE, // Samsung-style with timing
    TORCH_THEN_FLASH,   // Xiaomi/Huawei/Vivo-style continuous torch
    ADAPTIVE            // Try multiple approaches
}

data class FlashCapabilities(
    val hasFlash: Boolean,
    val supportsAlwaysFlash: Boolean,
    val supportsTorch: Boolean,
    val supportsAutoFlash: Boolean,
    val supportsPrecapture: Boolean
)

class MainActivity : AppCompatActivity() {
    private val ORIENTATIONS = SparseIntArray()

    init {
        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }

    private lateinit var binding: ActivityMainBinding

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

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
    
    // Flash management properties
    private var flashCapabilities: FlashCapabilities? = null
    private var preferredFlashStrategy: FlashStrategy = FlashStrategy.ADAPTIVE
    private var flashTimingMs: Long = 500L

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val TAG = "MainActivity"

        // Real average iris diameter in mm
        private const val REAL_IRIS_DIAMETER_MM = 11.7f
        private var SENSOR_WIDTH_MM = 4.1f  // Example value (adjust for your device)

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if we should finish the activity
        if (intent.getBooleanExtra("finish", false)) {
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater) // Initialize FIRST
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
            setupTextureView()
        }
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
            capturePhoto()
        }
        
        // Log device information for debugging
        logDeviceFlashInfo()
    }

    /**
     * Log device and flash information for debugging
     */
    private fun logDeviceFlashInfo() {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        val device = Build.DEVICE
        
        Log.i(TAG, "=== DEVICE FLASH INFO ===")
        Log.i(TAG, "Manufacturer: $manufacturer")
        Log.i(TAG, "Model: $model")
        Log.i(TAG, "Device: $device")
        Log.i(TAG, "Expected flash strategy: ${getOptimalFlashStrategy()}")
        Log.i(TAG, "Expected timing: ${getOptimalFlashTiming()}ms")
        Log.i(TAG, "Needs torch warmup: ${needsTorchWarmup()}")
        Log.i(TAG, "========================")
    }

    /**
     * Activity yön değişikliklerini dinle ve TextureView'e uygun dönüşümü uygla.
     */


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPreviewAspectRatio()

    }
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Throws(CameraAccessException::class)
    private fun getRotationCompensation(cameraId: String, activity: Activity, isFrontFacing: Boolean): Int {
        // Get the device's current rotation relative to its "native" orientation.
        // Then, from the ORIENTATIONS table, look up the angle the image must be
        // rotated to compensate for the device's rotation.
        val deviceRotation = activity.windowManager.defaultDisplay.rotation
        var rotationCompensation = ORIENTATIONS.get(deviceRotation)

        // Get the device's sensor orientation.
        val cameraManager = activity.getSystemService(CAMERA_SERVICE) as CameraManager
        val sensorOrientation = cameraManager
            .getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION)!!

        if (isFrontFacing) {
            rotationCompensation = (sensorOrientation + rotationCompensation) % 360
        } else { // back-facing
            rotationCompensation = (sensorOrientation - rotationCompensation + 360) % 360
        }
        return rotationCompensation
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



    private fun configureTransform(viewW: Int, viewH: Int) {
        val size = previewSize ?: return

        val rotation = windowManager.defaultDisplay.rotation      // 0,90,180,270
        val matrix   = Matrix()


        val viewRect  = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val bufRect   = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())   // note swap
        val centerX   = viewRect.centerX()
        val centerY   = viewRect.centerY()

        // 1) "Center-crop" so the buffer cleanly fills the view:
        if(rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufRect.offset(centerX-bufRect.centerX(), centerY-bufRect.centerY())
            matrix.setRectToRect(viewRect, bufRect, Matrix.ScaleToFit.FILL)
            val scale:Float =Math.max(viewW.toFloat()/size.width, viewH.toFloat()/size.height)
            matrix.postScale(scale,scale,centerX,centerY)
            matrix.postRotate(90*(rotation.toFloat()-2),centerX,centerY)
        }
        binding.textureView.setTransform(matrix)

    }













    private fun chooseOptimalSize(
        choices: Array<Size>,
        viewWidth: Int, viewHeight: Int,
        maxWidth: Int, maxHeight: Int,
        aspectRatio: Size
    ): Size {
        val bigEnough = choices.filter {
            it.width <= maxWidth && it.height <= maxHeight &&
                    it.height == it.width * aspectRatio.height / aspectRatio.width
        }
        return when {
            bigEnough.isNotEmpty() -> bigEnough.minByOrNull { it.width * it.height }!!
            else -> choices[0]
        }
    }

    private fun applyOrientationTransform(rawBitmap: Bitmap, rotation: Int): Bitmap {
        val matrix = Matrix()
        when (rotation) {
            Surface.ROTATION_90 -> matrix.postRotate(90f)
            Surface.ROTATION_180 -> matrix.postRotate(180f)
            Surface.ROTATION_270 -> matrix.postRotate(270f)
            // ROTATION_0: dönüş gerekmez.
        }
        return Bitmap.createBitmap(
            rawBitmap, 0, 0,
            rawBitmap.width, rawBitmap.height, matrix, true
        )
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

    /**
     * Set up the TextureView listener to start the camera preview.
     */
    private fun setupTextureView() {
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                surfaceWidth  = width
                surfaceHeight = height


                openCamera()
                configureTransform(surfaceWidth, surfaceHeight)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                surfaceWidth  = w
                surfaceHeight = h
                configureTransform(w, h)

            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                processFrame() // Process each frame for face detection and overlay drawing.
            }
        }
    }

    /**
     * Process each frame: detect faces, draw bounding boxes and landmarks,
     * compute distance using iris diameter (if calibration is complete), and update warnings.
     */
    private fun processFrame() {
        if (isProcessing) return  // yeni frame geldiyse ama hala önceki işleniyorsa, bunu atla
        isProcessing = true
        val rawBitmap = binding.textureView.bitmap ?: return
        val cameraId = cameraDevice?.id ?: return
        val isFrontFacing = cameraId.contains("front")
        val rotationComp = getRotationCompensation(cameraId, this, isFrontFacing)

        val inputImage = InputImage.fromBitmap(rawBitmap, rotationComp)
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .enableTracking()
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)  // Minimum yüz boyutu (0.0-1.0 arası)
            .build()
        val detector = FaceDetection.getClient(options)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0] // İlk yüz
                    val mlkitPitch = face.headEulerAngleX// Yukarı/aşağı bakma
                    val mlkitYaw = face.headEulerAngleY    // Sağa/sola dönme
                    val mlkitRoll = face.headEulerAngleZ   // Eğilme (tilt)
                    lastPitch = mlkitPitch
                    lastYaw = mlkitYaw
                    lastRoll = mlkitRoll
                    Log.d("MLKit", "Pitch: $mlkitPitch, Yaw: $mlkitYaw, Roll: $mlkitRoll")
                    
                    runOnUiThread {
                        binding.mlkitPoseTextView.text =
                            "Pitch: ${mlkitPitch.toInt()}°\n" +
                                    "Yaw: ${mlkitYaw.toInt()}°\n" +
                                    "Roll: ${mlkitRoll.toInt()}°"
                    }
                } else {
                    // Yüz algılanamadığında değerleri sıfırla
                    lastPitch = null
                    lastYaw = null
                    lastRoll = null
                    runOnUiThread {
                        binding.mlkitPoseTextView.text = "Yüz algılanamadı"
                    }
                }
                isProcessing = false
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ML Kit yüz algılama hatası: ${e.message}")
                lastPitch = null
                lastYaw = null
                lastRoll = null
                runOnUiThread {
                    binding.mlkitPoseTextView.text = "Yüz algılanamadı"
                }
                isProcessing = false
            }
            .addOnCompleteListener {
                isProcessing = false  // işlem tamamlandığında işaretçi sıfırlanır
            }

        // Cihazın mevcut ekran dönüşünü alıyoruz:
        val rotation = windowManager.defaultDisplay.rotation

        // Bitmap'i oryantasyon bilgisine göre dönüştürüyoruz:
        val bitmap = applyOrientationTransform(rawBitmap, rotationComp)

        // Hatalardan kaçınmak için modelin doğru initialize edildiğinden emin olun:
        if (!::faceLandmarker.isInitialized) return

        if(calibrationFocalLength == null) {
            Log.e("Calibration", "Calibration focal length is not set yet!")
            return
        }

        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = faceLandmarker.detect(mpImage)
        val faceLandmarksList = result.faceLandmarks()

        if (faceLandmarksList.isNotEmpty()) {
            val landmarks = faceLandmarksList[0]

            // 1. Distance estimation
            var (distanceValue, distanceMessage) = estimateDistanceUsingIris(landmarks, bitmap)
            finalDistanceValue=distanceValue
            // 2. Face alignment

            // ⬇ Göz koordinatına focus at
            val leftEye = landmarks.getOrNull(468)
            val rightEye = landmarks.getOrNull(473)
            if (leftEye != null && rightEye != null) {
                // Convert normalized coordinates to TextureView pixel coordinates
                val leftX = leftEye.x() * binding.textureView.width
                val leftY = leftEye.y() * binding.textureView.height
                val rightX = rightEye.x() * binding.textureView.width
                val rightY = rightEye.y() * binding.textureView.height
                val xCenter = ((leftX + rightX) / 2).toInt()
                val yCenter = ((leftY + rightY) / 2).toInt()
                
                // Ensure coordinates are within TextureView bounds
                val clampedX = clamp(xCenter, 0, binding.textureView.width - 1)
                val clampedY = clamp(yCenter, 0, binding.textureView.height - 1)
                
                lastEyeFocusX = clampedX
                lastEyeFocusY = clampedY
                
                Log.d(TAG, "Eye focus coordinates: TextureView($clampedX, $clampedY)")
            }

            var orientationMessage = ""

            // Orientation check with unified yaw
            // Update arrow view with head pose data
            if(lastPitch==null|| lastYaw==null|| lastRoll==null) {
                runOnUiThread {
                    binding.orientationTextView.text = "Yüz pozisyonu hesaplanıyor..."
                }
                return
            }

            // Update the arrow view with current head pose
            runOnUiThread {
                binding.headPoseArrowView.visibility = View.VISIBLE
                binding.headPoseArrowView.updateHeadPose(lastPitch!!, lastYaw!!, lastRoll!!, headPoseThreshold)
            }

            // Check if head pose is aligned
            val isPoseAligned = kotlin.math.abs(lastPitch!!) <= headPoseThreshold && 
                               kotlin.math.abs(lastYaw!!) <= headPoseThreshold && 
                               kotlin.math.abs(lastRoll!!) <= headPoseThreshold

            val isDistanceOk = !isDistanceCheckEnabled || (distanceValue >= 30.0 && distanceValue <= 35.0)

            // ✅ Check if everything is aligned for auto-capture
            if (isPoseAligned && isDistanceOk) {
                orientationMessage = "✅ Baş pozisyonu uygun"

                if (!isCapturing) {  // ✅ Prevent multiple captures
                    isCapturing = true
                    isProcessing = true
                    capturedPitch = lastPitch
                    capturedYaw = lastYaw
                    capturedRoll = lastRoll
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                    // Hemen fotoğraf çek
                    capturePhoto()  // ✅ Trigger vibration, then capture
                }
            } else {
                // Generate distance warning if needed
                if (isDistanceCheckEnabled && (distanceValue < 30.0 || distanceValue > 35.0)) {
                    orientationMessage = "📏 Kamera ile hasta arası mesafeyi 30-35 cm arasına getirin."
                } else {
                    orientationMessage = "🎯 Okları takip ederek başınızı hizalayın"
                }
            }

            runOnUiThread {
                binding.orientationTextView.text = orientationMessage
            }

            // Update distance & center UI
            runOnUiThread {
                binding.distanceTextView.text = distanceMessage
            }
        } else {
            // Yüz algılanamadığında UI'ı güncelle
            runOnUiThread {
                binding.orientationTextView.text = "Yüz algılanamadı"
                binding.distanceTextView.text = "Mesafe ölçülemiyor"
                // Hide arrow view when no face is detected
                binding.headPoseArrowView.visibility = View.GONE
            }
        }
        
        
        val distance = finalDistanceValue ?: return
        val headPoseDict = HashMap<String, Float>()
        val isDistanceCheckEnabledFloat = if (isDistanceCheckEnabled) 1f else 0f
        if (lastPitch == null || lastYaw == null || lastRoll == null) return
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
            Log.e("MLKit", "Pitch: $lastPitch, Yaw: $lastYaw, Roll: $lastRoll")
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




    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            processCaptureResult(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            processCaptureResult(result)
        }
        private fun processCaptureResult(result: CaptureResult) {
            when (mState) {
                STATE_WAITING_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    Log.d(TAG, "AE State: $aeState")
                    
                    // Kameranın precapture durumuna geçtiğini kontrol edin (örneğin, FLASH_REQUIRED veya CONVERGED)
                    if (aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                        mState = STATE_WAITING_NON_PRECAPTURE
                        
                        // For Xiaomi devices, add shorter delay before actual capture
                        val captureDelay = if (Build.MANUFACTURER.lowercase() in listOf("xiaomi", "redmi")) 50L else 0L
                        
                        handler.postDelayed({
                            captureStillPicture()  // AE hazır olduğunda final çekimi başlat
                        }, captureDelay)
                    }
                }
            }
        }
    }
    private fun runPrecaptureSequence() {
        try {
            // Initialize flash capabilities if not already done
            if (flashCapabilities == null) {
                flashCapabilities = detectFlashCapabilities()
            }
            
            // Determine the optimal strategy
            val strategy = getOptimalFlashStrategy()
            val timing = getOptimalFlashTiming()
            
            Log.d(TAG, "Using flash strategy: $strategy with timing: ${timing}ms")
            
            when (strategy) {
                FlashStrategy.TORCH_THEN_FLASH -> {
                    executeTorchThenFlash(timing)
                }
                FlashStrategy.PRECAPTURE_SEQUENCE -> {
                    executePrecaptureSequence(timing)
                }
                FlashStrategy.ADAPTIVE -> {
                    executeAdaptiveFlash()
                }
                else -> {
                    executeStandardFlash()
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "runPrecaptureSequence error: ${e.message}")
            // Fallback to standard flash
            executeStandardFlash()
        }
    }

    /**
     * Execute torch warmup then flash (for Xiaomi, Huawei, Vivo, etc.)
     */
    private fun executeTorchThenFlash(warmupMs: Long) {
        // VIVO V21 ONLY - Skip torch warmup, use precapture instead
        if (isVivoV21()) {
            Log.d(TAG, "Vivo V21: Skipping torch-then-flash, using precapture sequence")
            executePrecaptureSequence(250L)
            return
        }
        
        try {
            Log.d(TAG, "Executing torch-then-flash sequence with ${warmupMs}ms warmup")
            
            // 1) Enable torch mode with reduced intensity for Xiaomi devices
            previewRequestBuilder?.apply {
                set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                
                // For Xiaomi/Redmi devices, try to reduce AE compensation to prevent overexposure
                if (Build.MANUFACTURER.lowercase() in listOf("xiaomi", "redmi")) {
                    // Reduce exposure compensation to prevent overexposure
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -1)
                    // Use spot metering for better face exposure
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    Log.d(TAG, "Applied Xiaomi/Redmi specific exposure settings")
                }
            }
            
            cameraCaptureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)

            // 2) Wait for sensor adaptation, then trigger precapture with torch still on
            handler.postDelayed({
                // For Xiaomi devices, keep torch on and immediately trigger precapture
                if (Build.MANUFACTURER.lowercase() in listOf("xiaomi", "redmi")) {
                    // Keep torch mode but trigger precapture immediately
                    previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                    mState = STATE_WAITING_PRECAPTURE
                    cameraCaptureSession?.capture(previewRequestBuilder!!.build(), captureCallback, backgroundHandler)
                    Log.d(TAG, "Xiaomi: Triggered precapture with torch still on")
                } else {
                    // Standard approach for other devices
                    previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                    mState = STATE_WAITING_PRECAPTURE
                    cameraCaptureSession?.capture(previewRequestBuilder!!.build(), captureCallback, backgroundHandler)
                }
            }, warmupMs)
        } catch (e: Exception) {
            Log.e(TAG, "executeTorchThenFlash error: ${e.message}")
            executeStandardFlash()
        }
    }

    /**
     * Execute precapture sequence with timing (for Samsung, OnePlus, etc.)
     */
    private fun executePrecaptureSequence(timingMs: Long) {
        try {
            Log.d(TAG, "Executing precapture sequence with ${timingMs}ms timing")
            
            // Quick precapture trigger
            previewRequestBuilder?.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            mState = STATE_WAITING_PRECAPTURE
            
            // Add a small delay for better timing on these devices
            handler.postDelayed({
                cameraCaptureSession?.capture(previewRequestBuilder!!.build(), captureCallback, backgroundHandler)
            }, timingMs / 5) // Use 1/5th of the timing for precapture delay
        } catch (e: Exception) {
            Log.e(TAG, "executePrecaptureSequence error: ${e.message}")
            executeStandardFlash()
        }
    }

    /**
     * Execute adaptive flash (try standard first, fallback to torch)
     */
    private fun executeAdaptiveFlash() {
        try {
            Log.d(TAG, "Executing adaptive flash sequence")
            
            // Try standard approach first
            executeStandardFlash { success ->
                if (!success && needsTorchWarmup()) {
                    Log.d(TAG, "Standard flash failed, trying torch warmup")
                    // Fallback to torch method with moderate timing
                    executeTorchThenFlash(800L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAdaptiveFlash error: ${e.message}")
            executeStandardFlash()
        }
    }

    /**
     * Execute standard flash sequence
     */
    private fun executeStandardFlash(callback: ((Boolean) -> Unit)? = null) {
        try {
            Log.d(TAG, "Executing standard flash sequence")
            
            previewRequestBuilder?.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            mState = STATE_WAITING_PRECAPTURE
            cameraCaptureSession?.capture(previewRequestBuilder!!.build(), captureCallback, backgroundHandler)
            
            // Assume success for standard flash
            callback?.invoke(true)
        } catch (e: Exception) {
            Log.e(TAG, "executeStandardFlash error: ${e.message}")
            callback?.invoke(false)
        }
    }

    // Xiaomi gibi cihazları tespit etmek için yardımcı fonksiyon
    private fun isXiaomiDevice(): Boolean {
        Log.d("XiaomiCheck", "Manufacturer: ${Build.MANUFACTURER}")
        return Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    }

    // VIVO V21 ONLY - Detect Vivo V21 specifically for flash fix
    private fun isVivoV21(): Boolean {
        val manufacturer = Build.MANUFACTURER.equals("vivo", ignoreCase = true)
        val model = Build.MODEL?.contains("V21", ignoreCase = true) == true
        val isV21 = manufacturer && model
        if (isV21) {
            Log.d(TAG, "VIVO V21 detected - using special flash handling")
        }
        return isV21
    }

    // VIVO V21 ONLY - Create centered metering rectangle for fallback
    private fun getCenteredMeteringRect(sensorArraySize: android.graphics.Rect, fraction: Float = 0.22f): android.graphics.Rect {
        val centerX = sensorArraySize.centerX()
        val centerY = sensorArraySize.centerY()
        val halfWidth = (sensorArraySize.width() * fraction / 2).toInt()
        val halfHeight = (sensorArraySize.height() * fraction / 2).toInt()
        
        return android.graphics.Rect(
            centerX - halfWidth,
            centerY - halfHeight,
            centerX + halfWidth,
            centerY + halfHeight
        )
    }

    /**
     * Detect flash capabilities of the current device
     */
    private fun detectFlashCapabilities(): FlashCapabilities {
        return try {
            val characteristics = getCameraCharacteristics() ?: return FlashCapabilities(
                hasFlash = false,
                supportsAlwaysFlash = false,
                supportsTorch = false,
                supportsAutoFlash = false,
                supportsPrecapture = false
            )
            
            val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            val aeAvailableModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
            val supportedModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES) ?: intArrayOf()
            
            FlashCapabilities(
                hasFlash = flashAvailable,
                supportsAlwaysFlash = aeAvailableModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH),
                supportsTorch = flashAvailable, // Most devices with flash support torch
                supportsAutoFlash = aeAvailableModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH),
                supportsPrecapture = supportedModes.contains(CameraCharacteristics.CONTROL_MODE_AUTO)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting flash capabilities: ${e.message}")
            FlashCapabilities(false, false, false, false, false)
        }
    }

    /**
     * Get camera characteristics for the current device
     */
    private fun getCameraCharacteristics(): CameraCharacteristics? {
        return try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = cameraDevice?.id ?: return null
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera characteristics: ${e.message}")
            null
        }
    }

    /**
     * Determine the optimal flash strategy based on device manufacturer and capabilities
     */
    private fun getOptimalFlashStrategy(): FlashStrategy {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val capabilities = flashCapabilities ?: detectFlashCapabilities()
        
        // If no flash available, return standard
        if (!capabilities.hasFlash) {
            Log.d(TAG, "No flash available, using STANDARD strategy")
            return FlashStrategy.STANDARD
        }
        
        // VIVO V21 ONLY - Force PRECAPTURE_SEQUENCE to avoid torch warmup
        if (isVivoV21()) {
            Log.d(TAG, "Vivo V21 detected, using PRECAPTURE_SEQUENCE strategy (no torch)")
            return FlashStrategy.PRECAPTURE_SEQUENCE
        }
        
        return when (manufacturer) {
            "samsung" -> {
                Log.d(TAG, "Samsung device detected, using PRECAPTURE_SEQUENCE strategy")
                FlashStrategy.PRECAPTURE_SEQUENCE
            }
            "xiaomi", "redmi", "huawei", "vivo" -> {
                Log.d(TAG, "$manufacturer device detected, using TORCH_THEN_FLASH strategy")
                FlashStrategy.TORCH_THEN_FLASH
            }
            "oneplus" -> {
                Log.d(TAG, "OnePlus device detected, using PRECAPTURE_SEQUENCE strategy")
                FlashStrategy.PRECAPTURE_SEQUENCE
            }
            "google", "pixel" -> {
                Log.d(TAG, "Google/Pixel device detected, using STANDARD strategy")
                FlashStrategy.STANDARD
            }
            "lg", "sony", "motorola" -> {
                Log.d(TAG, "$manufacturer device detected, using PRECAPTURE_SEQUENCE strategy")
                FlashStrategy.PRECAPTURE_SEQUENCE
            }
            else -> {
                Log.d(TAG, "Unknown manufacturer ($manufacturer), using ADAPTIVE strategy")
                FlashStrategy.ADAPTIVE
            }
        }
    }

    /**
     * Get optimal flash timing based on device manufacturer
     */
    private fun getOptimalFlashTiming(): Long {
        return when (Build.MANUFACTURER.lowercase()) {
            "samsung" -> 250L        // Quick precapture
            "xiaomi" -> 1200L        // Reduced from 1500L - balance between warmup and timing
            "redmi" -> 1200L         // Redmi (Xiaomi sub-brand) needs same timing
            "huawei" -> 1000L        // Medium torch warmup
            "vivo" -> 1100L          // Vivo needs similar timing to Xiaomi
            "oneplus" -> 200L        // Very quick
            "google", "pixel" -> 150L // Pixel phones are fast
            "lg" -> 350L             // LG needs more time
            "sony" -> 300L           // Sony moderate timing
            "motorola" -> 400L       // Motorola moderate timing
            else -> 500L             // Safe default
        }
    }

    /**
     * Check if device needs torch warmup before flash
     */
    private fun needsTorchWarmup(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        
        // VIVO V21 ONLY - Disable torch warmup to prevent overexposure
        if (isVivoV21()) {
            Log.d(TAG, "Vivo V21: torch warmup disabled")
            return false
        }
        
        return manufacturer in listOf("xiaomi", "redmi", "huawei", "vivo", "oppo", "realme")
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




    private fun getSensorWidthMm(): Float? {
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraIdList = manager.cameraIdList

            for (cameraId in cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

                // Use the back camera
                if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    val sensorSize =
                        characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    return sensorSize?.width // Returns width in mm
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Sensor width could not be determined: ${e.message}")
        }
        return null
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return Math.max(min, Math.min(max, value))
    }

    //back camera


    //-------------------------------------------------------------------------
    //               CAMERA OPEN + PREVIEW + CAPTURE LOGIC
    //-------------------------------------------------------------------------

    private fun openCamera() {
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraIdList = manager.cameraIdList

            var backCameraId: String? = null
            for (cameraId in cameraIdList) {
                val chars = manager.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    backCameraId = cameraId
                    Log.d(TAG, "Selected back camera ID: $backCameraId") // <-- Added log line
                    // In openCamera(), instead of "largest = ... maxByOrNull { it.width * it.height }":
                    // inside openCamera(), after you get cameraCharacteristics:
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

// 1) get the raw SurfaceTexture sizes
                    val choices = map.getOutputSizes(SurfaceTexture::class.java)

// 2) decide your container's current pixel dims:

                    val portraitChoices = choices.filter { it.height > it.width }

// 3) pick the smallest "big enough" size
                    previewSize = portraitChoices
                        .maxByOrNull { it.width * it.height }
                        ?: choices[0]
// then create your ImageReader from the JPEG sizes only:
                    val jpegChoices = map.getOutputSizes(ImageFormat.JPEG)
                    val largestJpeg = jpegChoices.maxByOrNull { it.width * it.height }!!
                    imageReader = ImageReader.newInstance(
                        largestJpeg.width,
                        largestJpeg.height,
                        ImageFormat.JPEG, 2
                    )
                    break
                }
            }

            if (backCameraId == null) {
                Log.e(TAG, "No BACK camera found!")
                return
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val chars = manager.getCameraCharacteristics(backCameraId)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            manager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    
                    // Initialize flash capabilities early
                    flashCapabilities = detectFlashCapabilities()
                    preferredFlashStrategy = getOptimalFlashStrategy()
                    flashTimingMs = getOptimalFlashTiming()
                    
                    Log.d(TAG, "Flash capabilities: $flashCapabilities")
                    Log.d(TAG, "Preferred flash strategy: $preferredFlashStrategy")
                    Log.d(TAG, "Flash timing: ${flashTimingMs}ms")
                    
                    startCameraPreview()

                    // Grab sensor physical width (mm) once the back camera is chosen.
                    // This value is vital for distance estimation. Fallback to the
                    // previously hard-coded value if the query fails.
                    getSensorWidthMm()?.let {
                        SENSOR_WIDTH_MM = it
                    }
                    Log.d(TAG, "Sensor width (mm): $SENSOR_WIDTH_MM")

                    // Attempt calibration capture once
                    if (!isCalibrated) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            captureCalibrationPhoto()
                        }, 2000)
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Camera error: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening back camera: ${e.message}")
        }
    }
    private fun startCameraPreview() {
        try {
            val texture = binding.textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
            val surface = Surface(texture)

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                if (!isCalibrated) {
                    parseCalibrationImage(image)
                } else {
                    // Fotoğrafı kaydediyoruz
                    saveImageToGallery(image, finalDistanceValue)
                }

                image.close()

                // Kaydetme bitti. Artık tekrar fotoğraf çekilebilir:
            }, backgroundHandler)


            val surfaces = listOf(surface, imageReader!!.surface)
            cameraDevice!!.createCaptureSession(surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session

                        try {
                            // Burada tekrar flash modunu uygula:
                            updateFlashMode()
                            // Artık previewRequestBuilder flash moduna göre ayarlandı.
                            cameraCaptureSession?.setRepeatingRequest(
                                previewRequestBuilder!!.build(),
                                null,
                                backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera config failed")
                    }
                }, backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "startCameraPreview error: ${e.message}")
        }
    }



    private fun captureCalibrationPhoto() {
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())
            // Force flash off for calibration so we get an accurate EXIF focal length
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            cameraCaptureSession?.capture(captureBuilder.build(), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing calibration photo: ${e.message}")
        }
    }
    private fun captureStillPicture() {
        try {
            if (cameraDevice == null) return

            // 1) Stop preview repeating request
            cameraCaptureSession?.stopRepeating()

            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())

                // VIVO V21 ONLY - Special flash handling to prevent overexposure
                if (isVivoV21()) {
                    Log.d(TAG, "Vivo V21: Applying special flash settings")
                    
                    // Ensure torch is OFF and use clean flash settings
                    set(CaptureRequest.CONTROL_AE_LOCK, false)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                    set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    
                    // Ensure torch is turned off in preview
                    previewRequestBuilder?.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                    
                    // Copy AE/AF regions from preview to capture
                    lastMeteringRect?.let { meteringRect ->
                        set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                        set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
                        Log.d(TAG, "Vivo V21: Applied stored metering regions to capture")
                    } ?: run {
                        // Fallback: use centered metering rectangle
                        val characteristics = getCameraCharacteristics()
                        if (characteristics != null) {
                            val sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                            if (sensorArraySize != null) {
                                val centeredRect = getCenteredMeteringRect(sensorArraySize, 0.22f)
                                val meteringRect = MeteringRectangle(centeredRect, MeteringRectangle.METERING_WEIGHT_MAX)
                                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                                set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
                                Log.d(TAG, "Vivo V21: Applied fallback centered metering regions")
                            }
                        }
                    }
                } else {
                    // Enhanced flash logic based on device capabilities for other devices
                    val capabilities = flashCapabilities ?: detectFlashCapabilities()
                    if (capabilities.hasFlash) {
                        when (preferredFlashStrategy) {
                            FlashStrategy.TORCH_THEN_FLASH -> {
                                // For Xiaomi, Redmi, Huawei, Vivo - use controlled flash mode
                                if (Build.MANUFACTURER.lowercase() in listOf("xiaomi", "redmi")) {
                                    // Xiaomi/Redmi specific settings - use torch mode for capture
                                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                                    // Keep reduced exposure compensation for final capture
                                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -2) // Further reduce for capture
                                    // Use center-weighted metering for better face exposure
                                    set(CaptureRequest.CONTROL_AE_REGIONS, null) // Reset regions for global metering
                                    Log.d(TAG, "Applied Xiaomi/Redmi capture settings with torch mode")
                                } else {
                                    // Other torch-then-flash devices
                                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                                    set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                                }
                            }
                            FlashStrategy.PRECAPTURE_SEQUENCE -> {
                                // For Samsung, OnePlus - use auto flash with precapture
                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
                                set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                            }
                            else -> {
                                // Standard approach
                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                                set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                            }
                        }
                        
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                        set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                        Log.d(TAG, "Flash parameters configured for strategy: $preferredFlashStrategy")
                    }
                }
            }

            mState = STATE_PICTURE_TAKEN

            // Add device-specific delay before capture
            val captureDelay = when {
                isVivoV21() -> 250L          // VIVO V21 ONLY - Short delay for precapture sequence
                Build.MANUFACTURER.lowercase() in listOf("xiaomi", "redmi") -> 150L
                Build.MANUFACTURER.lowercase() in listOf("huawei", "vivo") -> 200L
                Build.MANUFACTURER.lowercase() in listOf("samsung", "oneplus") -> 100L
                else -> 150L
            }

            Handler(Looper.getMainLooper()).postDelayed({
                cameraCaptureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.d(TAG, "Photo captured successfully")
                        mState = STATE_PREVIEW

                        // VIVO V21 ONLY - Ensure torch stays off after capture
                        if (isVivoV21()) {
                            previewRequestBuilder?.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                            previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                            previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_LOCK, false)
                            Log.d(TAG, "Vivo V21: Ensured clean flash state after capture")
                        }
                        // For Xiaomi devices, turn off torch after capture
                        else if (Build.MANUFACTURER.lowercase() in listOf("xiaomi", "redmi")) {
                            previewRequestBuilder?.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                            previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                            Log.d(TAG, "Turned off torch after Xiaomi capture")
                        }

                        // Return to preview with updated flash mode
                        startCameraPreview()
                    }
                }, backgroundHandler)
            }, captureDelay)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "captureStillPicture error: ${e.message}")
        }
    }


    private fun capturePhoto() {
        try {
            if (isErrorActive) {
                Log.d(TAG, "⚠️ Fotoğraf çekimi engellendi: aktif bir hata var.")
                return
            }
            if (cameraDevice == null || imageReader == null || cameraCaptureSession == null) {
                Log.e(TAG, "❌ Missing camera components for capture.")
                return
            }

            // 1) Göz koordinatımız var mı?
            val x = lastEyeFocusX
            val y = lastEyeFocusY
            if (x != null && y != null) {
                Log.d(TAG, "capturePhoto: focusing on eyes at ($x, $y)")
                setFocusOnEyesOrFace(x, y, 300)
                
                // VIVO V21 ONLY - Shorter focus delay for better flash timing
                val focusDelay = when {
                    isVivoV21() -> 400L  // VIVO V21 ONLY - Balanced delay for focus + flash
                    Build.MANUFACTURER.lowercase() in listOf("xiaomi", "redmi") -> 1200L
                    else -> 300L
                }
                
                handler.postDelayed({
                    runPrecaptureSequence()
                }, focusDelay)
            } else {
                // Göz koordinatı yoksa direkt precapture
                runPrecaptureSequence()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in capturePhoto: ${e.message}")
        }
    }


    private fun parseCalibrationImage(image: Image) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "calibration.jpg")
            FileOutputStream(file).use { it.write(bytes) }

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
    }
    fun setFocusOnEyesOrFace(xCenter: Int, yCenter: Int, regionSize: Int = 200) {
        val localSession = cameraCaptureSession
        if (localSession == null) {
            Log.w(TAG, "Cannot set focus: cameraCaptureSession is null or closed.")
            return
        }

        if (cameraDevice == null) {
            Log.w(TAG, "Cannot set focus: cameraDevice is null.")
            return
        }

        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraDevice?.id ?: return)
            val sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            
            // Convert TextureView coordinates to sensor coordinates
            val textureWidth = binding.textureView.width
            val textureHeight = binding.textureView.height
            
            if (textureWidth == 0 || textureHeight == 0) {
                Log.w(TAG, "TextureView dimensions are zero, cannot set focus")
                return
            }
            
            // Map from TextureView coordinates to sensor coordinates
            val sensorX = (xCenter.toFloat() / textureWidth * sensorArraySize.width()).toInt()
            val sensorY = (yCenter.toFloat() / textureHeight * sensorArraySize.height()).toInt()
            
            // Create focus region with bounds checking
            val halfSize = regionSize / 2
            val left = clamp(sensorX - halfSize, 0, sensorArraySize.width() - 1)
            val top = clamp(sensorY - halfSize, 0, sensorArraySize.height() - 1)
            val right = clamp(left + regionSize, left + 1, sensorArraySize.width())
            val bottom = clamp(top + regionSize, top + 1, sensorArraySize.height())

            val meteringRect = MeteringRectangle(
                android.graphics.Rect(left, top, right, bottom), 
                MeteringRectangle.METERING_WEIGHT_MAX
            )

            // VIVO V21 ONLY - Store metering rectangle for capture reuse
            lastMeteringRect = meteringRect
            Log.d(TAG, "Focus region: TextureView($xCenter, $yCenter) -> Sensor($sensorX, $sensorY) -> Rect($left, $top, $right, $bottom)")
            if (isVivoV21()) {
                Log.d(TAG, "Vivo V21: Stored metering rectangle for capture reuse")
            }

            // Get lens info for manual focus distance calculation
            val minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val hyperfocalDistance = characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
            
            Log.d(TAG, "Lens info - Min focus distance: $minFocusDistance, Hyperfocal: $hyperfocalDistance")

            // Calculate manual focus distance from eye distance
            var focusDistance = 0f
            val eyeDistanceCm = finalDistanceValue
            
            if (eyeDistanceCm != null && eyeDistanceCm > 0f && minFocusDistance > 0f) {
                // Convert distance from cm to meters
                val eyeDistanceM = eyeDistanceCm / 100f
                
                // Calculate focus distance in diopters (1/meters)
                // Camera2 API uses diopters where 0 = infinity, higher values = closer
                focusDistance = 1f / eyeDistanceM
                
                // Clamp to camera's capabilities
                focusDistance = focusDistance.coerceAtMost(minFocusDistance)
                
                Log.d(TAG, "Calculated focus distance: ${eyeDistanceCm}cm -> ${eyeDistanceM}m -> ${focusDistance} diopters (max: $minFocusDistance)")
            }

            // Xiaomi-specific focus settings with manual distance
            previewRequestBuilder?.apply {
                if (Build.MANUFACTURER.lowercase() in listOf("xiaomi", "redmi", "vivo")) {
                    if (focusDistance > 0f && minFocusDistance > 0f) {
                        // Use manual focus with calculated distance
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                        Log.d(TAG, "${Build.MANUFACTURER}: Applied manual focus distance: $focusDistance diopters")
                    } else {
                        // Fallback to auto focus
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    }
                    
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
                } else {
                    // For other devices, use continuous picture mode with regions
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
                    
                    // If we have distance info, provide a hint
                    if (focusDistance > 0f && minFocusDistance > 0f) {
                        // Some devices support focus distance hints even in auto mode
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                        Log.d(TAG, "Other device: Applied focus distance hint: $focusDistance diopters")
                    }
                }

                // Trigger autofocus only for non-manual modes
                if (get(CaptureRequest.CONTROL_AF_MODE) != CaptureRequest.CONTROL_AF_MODE_OFF) {
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
            }

        // Capture the focus request
        cameraCaptureSession?.capture(
            previewRequestBuilder!!.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    val currentFocusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    Log.d(TAG, "Focus completed - AF state: $afState, Actual focus distance: $currentFocusDistance diopters")
                    
                    // Reset AF trigger for auto modes and resume preview
                    if (previewRequestBuilder?.get(CaptureRequest.CONTROL_AF_MODE) != CaptureRequest.CONTROL_AF_MODE_OFF) {
                        previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                    }
                    
                    try {
                        cameraCaptureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resuming preview after focus: ${e.message}")
                    }
                }
            },
            backgroundHandler
        )

        lastEyeFocusX = xCenter
        lastEyeFocusY = yCenter
        
    } catch (e: Exception) {
        Log.e(TAG, "Error setting focus: ${e.message}")
    }
    }
    private fun saveImageToGallery(image: Image, distanceValue: Float?) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val rotation = getJpegOrientation()
            val cameraId = cameraDevice?.id ?: return
            val isFrontFacing = cameraId.contains("front")
            val rotationComp = getRotationCompensation(cameraId, this, isFrontFacing)
            val inputImage = InputImage.fromBitmap(rawBitmap, rotation)
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
                        Log.w(TAG, "❌ No face detected. Skipping save.")

                        return@addOnSuccessListener
                    }
                    val face = faces[0]
                    val pitch = face.headEulerAngleX
                    val yaw = face.headEulerAngleY
                    val roll = face.headEulerAngleZ
                    val rightProb = face.rightEyeOpenProbability
                    val leftProb = face.leftEyeOpenProbability
                    Log.d(TAG, "Right Eye: $rightProb, Left Eye: $leftProb, Pose: Pitch=$pitch, Yaw=$yaw, Roll=$roll")

                    val poseWarnings = mutableListOf<String>()

                    // Check head pose alignment using the same threshold logic
                    val isPoseAligned = kotlin.math.abs(pitch) <= headPoseThreshold && 
                                       kotlin.math.abs(yaw) <= headPoseThreshold && 
                                       kotlin.math.abs(roll) <= headPoseThreshold

                    if (!isPoseAligned) {
                        // Build warning message for pose issues
                        val poseIssues = mutableListOf<String>()
                        if (kotlin.math.abs(pitch) > headPoseThreshold) poseIssues.add("pitch")
                        if (kotlin.math.abs(yaw) > headPoseThreshold) poseIssues.add("yaw") 
                        if (kotlin.math.abs(roll) > headPoseThreshold) poseIssues.add("roll")
                        
                        val fullMessage = "❌ Baş pozisyonu uygun değil (${poseIssues.joinToString(", ")}). Okları takip edin."
                        showErrorMessage(fullMessage)
                        playErrorSound()
                        Log.w(TAG, fullMessage)

                        return@addOnSuccessListener
                    }
                    if (rightProb == null || leftProb == null) {
                        showErrorMessage("❌ Göz açıklığı algılanamadı.\nYüzünüz yeterince net ya da doğru açıda olmayabilir.")
                        playErrorSound()
                        Log.w(TAG, "❌ Eye probability is null. Right: $rightProb, Left: $leftProb")


                        return@addOnSuccessListener
                    }

                    if (rightProb < 0.6f || leftProb < 0.6f) {
                        showErrorMessage("❌ Gözlerinizi Açın.\nHer iki göz de kapalı görünüyor.")
                        playErrorSound()
                        Log.w(TAG, "❌ Eyes appear open. Right: $rightProb, Left: $leftProb")


                        return@addOnSuccessListener
                    }



                    // ✅ Pose is valid, continue saving
                    val fileName = "Captured_${System.currentTimeMillis()}.jpg"
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                    uri?.let {
                        resolver.openOutputStream(it)?.use { os -> os.write(bytes) }
                        resolver.openFileDescriptor(it, "rw")?.use { pfd ->
                            val exif = ExifInterface(pfd.fileDescriptor)

                            val pitchText = "%.2f".format(capturedPitch ?: 100.0)
                            val yawText = "%.2f".format(capturedYaw ?: 100.0)
                            val rollText = "%.2f".format(capturedRoll ?: 100.0)
                            val distText = "%.2f".format(distanceValue ?: 0f)
                            val userComment = "Distance: $distText cm, Pitch: $pitchText°, Yaw: $yawText°, Roll: $rollText°"

                            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, userComment)
                            exif.saveAttributes()
                        }

                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(it, contentValues, null, null)
                        CameraPlugin.sendResultToUnity(rawBitmap)


                        Log.d(TAG, "✅ Photo saved with EXIF (pitch/yaw/roll): $uri")
                        runOnUiThread {
                            Toast.makeText(this, "Photo saved to Gallery", Toast.LENGTH_SHORT).show()
                        }
                        isCapturing = false
                        finish()
                    }



                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed: ${e.message}")
                    playErrorSound()
                    showErrorMessage("❌ Yüz algılanamadı.")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving photo: ${e.message}")
            playErrorSound()
            showErrorMessage("❌ Fotoğraf kaydı sırasında hata oluştu.")        }
    }




    private fun updateFlashMode() {
        previewRequestBuilder?.apply {
            // Klasik otomatik modları aktif ediyoruz
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

            // Sürekli flaş:
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)

            // Otomatik Beyaz Dengesi (AWB) devrede:
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            // Otomatik odak (sürekli resim):
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            // COLOR_CORRECTION_MODE_FAST veya HIGH_QUALITY
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)

            set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
            
            // Reset exposure compensation for preview (especially for Xiaomi/Redmi)
            if (Build.MANUFACTURER.lowercase() in listOf("xiaomi", "redmi")) {
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                Log.d(TAG, "Reset exposure compensation for preview")
            }
        }

        try {
            // ✅ Check if session and device are valid before sending the request
            if (cameraDevice != null && cameraCaptureSession != null) {
                cameraCaptureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
            } else {
                Log.w(TAG, "Skipped updateFlashMode: session or device is null or closed")
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "❌ Flash mode update failed: session already closed.")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating flash mode: ${e.message}")
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





    private fun getJpegOrientation(): Int {
        val ORIENTATIONS = SparseIntArray()
        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
        return ORIENTATIONS.get(windowManager.defaultDisplay.rotation)
    }

    override fun onResume() {
        super.onResume()
        try {
            // Start background thread first
            startBackgroundThread()
            
            // Reset state
            mState = STATE_PREVIEW
            isCapturing = false
            isProcessing = false
            
            // Check camera permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_CODE
                )
            } else {
                // Initialize camera if texture view is available
                if (binding.textureView.isAvailable) {
                    openCamera()
                    configureTransform(binding.textureView.width, binding.textureView.height)
                } else {
                    setupTextureView()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume: ${e.message}")
        }
    }
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackgroundThread").also { it.start() }
        // backgroundThread.getLooper() is guaranteed not null after start()
        backgroundHandler = Handler(backgroundThread!!.looper)
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
            // Stop the background thread
            stopBackgroundThread()
            
            // Close the camera session
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            
            // Close the camera device
            cameraDevice?.close()
            cameraDevice = null
            
            // Close the image reader
            imageReader?.close()
            imageReader = null
            
            // Reset state
            mState = STATE_PREVIEW
            isCapturing = false
            isProcessing = false
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPause: ${e.message}")
        }
    }
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "stopBackgroundThread: ${e.message}")
        }
    }



}