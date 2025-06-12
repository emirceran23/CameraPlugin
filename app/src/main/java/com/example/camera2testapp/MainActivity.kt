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

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val TAG = "MainActivity"

        // Real average iris diameter in mm
        private const val REAL_IRIS_DIAMETER_MM = 11.7f
        private var SENSOR_WIDTH_MM = 4.1f  // Example value (adjust for your device)

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater) // Initialize FIRST
        setContentView(binding.root)
        gridView = binding.gridView

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
    }

    /**
     * Activity yön değişikliklerini dinle ve TextureView'e uygun dönüşümü uygula.
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
     * TextureView’in dönüşüm matrisini, cihazın mevcut yönüne göre günceller.
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

        // 1) “Center‑crop” so the buffer cleanly fills the view:
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

                }
                isProcessing = false
            }
            .addOnCompleteListener {
                isProcessing = false  // işlem tamamlandığında işaretçi sıfırlanır
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ML Kit yüz algılama hatası: ${e.message}")
                isProcessing = false
            }


        // Cihazın mevcut ekran dönüşünü alıyoruz:
        val rotation = windowManager.defaultDisplay.rotation

        // Bitmap’i oryantasyon bilgisine göre dönüştürüyoruz:
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


// Then use stabilizedLandmarks for head pose calculation
            // ⬇ Göz koordinatına focus at
            val leftEye = landmarks.getOrNull(468)
            val rightEye = landmarks.getOrNull(473)
            if (leftEye != null && rightEye != null) {
                val leftX = leftEye.x() * bitmap.width
                val leftY = leftEye.y() * bitmap.height
                val rightX = rightEye.x() * bitmap.width
                val rightY = rightEye.y() * bitmap.height
                val xCenter = ((leftX + rightX) / 2).toInt()
                val yCenter = ((leftY + rightY) / 2).toInt()
                lastEyeFocusX=xCenter
                lastEyeFocusY=yCenter
               // setFocusOnEyesOrFace(xCenter, yCenter, 300)
            }


            var orientationMessage = ""




            // Orientation check with unified yaw
            // Generate orientation correction messages
            val warnings = mutableListOf<String>()
            if(lastPitch==null|| lastYaw==null|| lastRoll==null)
                return
            // Pitch
            if      (lastPitch!! >  headPoseThreshold)  warnings.add("⬇ Başınızı biraz aşağı eğin.")
            else if (lastPitch!! < -headPoseThreshold) warnings.add("⬆ Başınızı biraz yukarı kaldırın.")

// Yaw
            if      (lastYaw!!   >  headPoseThreshold)  warnings.add("➡ Başınızı biraz sağa çevirin.")
            else if (lastYaw!!   < -headPoseThreshold)  warnings.add("⬅ Başınızı biraz sola çevirin.")

// Roll
            if      (lastRoll!!  >  headPoseThreshold)  warnings.add("↺ Başınızı saat yönünün tersine döndürün.")
            else if (lastRoll!!  < -headPoseThreshold)  warnings.add("↻ Başınızı saat yönünde döndürün.")

            if (isDistanceCheckEnabled && (distanceValue < 30.0 || distanceValue > 35.0)) {
                warnings.add("📏 Kamera ile hasta arası mesafeyi 30-35 cm arasına getirin.")
            }






            // ✅ Display warning messages or success message
            if (warnings.isEmpty()) {

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
                orientationMessage = warnings.joinToString("\n")
            }


            runOnUiThread {
                binding.orientationTextView.text = orientationMessage
            }


            // Update distance & center UI
            runOnUiThread {
                binding.distanceTextView.text = distanceMessage
            }

            // Auto-capture conditions




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
                    // Kameranın precapture durumuna geçtiğini kontrol edin (örneğin, FLASH_REQUIRED veya CONVERGED)
                    if (aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                        mState = STATE_WAITING_NON_PRECAPTURE
                        captureStillPicture()  // AE hazır olduğunda final çekimi başlat
                    }
                }
            }
        }
    }
    private fun runPrecaptureSequence() {
        try {
            // AE_PRECAPTURE tetikleyicisini başlatın
            previewRequestBuilder?.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            mState = STATE_WAITING_PRECAPTURE
            cameraCaptureSession?.capture(previewRequestBuilder!!.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "runPrecaptureSequence error: ${e.message}")
        }
    }


    private fun estimateDistanceUsingIris(
        landmarks: List<NormalizedLandmark>,
        bitmap: Bitmap
    ): Pair<Float, String> {
        if (calibrationFocalLength == null || SENSOR_WIDTH_MM == null) {
            return Pair(
                0f,
                "⚠️ Kalibrasyon gerekli! Odak uzaklığı veya sensör genişliği bilinmiyor."
            )
        }

        val leftIrisLandmarks = listOf(468, 469, 470, 471, 472)
        val rightIrisLandmarks = listOf(473, 474, 475, 476, 477)

        val leftIrisDiameterPixels = calculateIrisDiameter(leftIrisLandmarks, landmarks, bitmap)
        val rightIrisDiameterPixels = calculateIrisDiameter(rightIrisLandmarks, landmarks, bitmap)

        val averageIrisDiameterPixels = (leftIrisDiameterPixels + rightIrisDiameterPixels) / 2
        val focalLengthPx = calibrationFocalLength!! * bitmap.width / SENSOR_WIDTH_MM!!
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
                    // In openCamera(), instead of “largest = ... maxByOrNull { it.width * it.height }”:
                    // inside openCamera(), after you get cameraCharacteristics:
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

// 1) get the raw SurfaceTexture sizes
                    val choices = map.getOutputSizes(SurfaceTexture::class.java)

// 2) decide your container’s current pixel dims:

                    val portraitChoices = choices.filter { it.height > it.width }

// 3) pick the smallest “big enough” size
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
                    startCameraPreview()

                    // Grab sensor width
                    //SENSOR_WIDTH_MM = getSensorWidthMm() ?: 6.3f
                    Log.d(TAG, "Sensor width: $SENSOR_WIDTH_MM mm")

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

            // 1) preview repeating isteğini durdur
            cameraCaptureSession?.stopRepeating()

            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())

                // Eğer flash donanımı varsa, capture sırasında flash'ı zorunlu çalıştırıyoruz.
                val characteristics = (getSystemService(CAMERA_SERVICE) as CameraManager)
                    .getCameraCharacteristics(cameraDevice!!.id)
                val isFlashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (isFlashAvailable) {
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                    set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                    Log.d(TAG, "Capture için flash parametreleri ayarlandı")
                }
            }

            mState = STATE_PICTURE_TAKEN

            // 2) Kısa bir gecikme vererek capture isteğini gönderiyoruz.
            Handler(Looper.getMainLooper()).postDelayed({
                cameraCaptureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.d(TAG, "Fotoğraf çekildi")
                        mState = STATE_PREVIEW

                        // Tekrar preview'e dönüyoruz -> orada updateFlashMode() tetiklenecek
                        startCameraPreview()
                    }
                }, backgroundHandler)
            }, 150)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "captureStillPicture hatası: ${e.message}")
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
                // Kısa bir bekleme (ör. 300ms) vererek AF oturmasını sağlayın, sonra precapture
                handler.postDelayed({
                    runPrecaptureSequence()
                }, 300)
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

        // 2) cameraDevice hala açık mı?
        if (cameraDevice == null) {
            Log.w(TAG, "Cannot set focus: cameraDevice is null.")
            return
        }
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraDevice?.id ?: return)
        val sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        // Koordinatları sınırlar içinde tut
        val halfSize = regionSize / 2
        val left = clamp(xCenter - halfSize, 0, sensorArraySize.width() - 1)
        val top = clamp(yCenter - halfSize, 0, sensorArraySize.height() - 1)
        val right = clamp(left + regionSize, 0, sensorArraySize.width())
        val bottom = clamp(top + regionSize, 0, sensorArraySize.height())

        val meteringRect = MeteringRectangle(android.graphics.Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)

        // Builder'dan AF modunu ve AF bölgesini ayarlayalım:
        previewRequestBuilder?.apply {
            // Sürekli netlik modu
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            // AF ve AE bölgeleri (opsiyonel, AE de yüz merkezine ayarlamak isterseniz)
            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            lastEyeFocusX = xCenter
            lastEyeFocusY = yCenter
            // AF tetikle
           // set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        }

        // Tek seferlik bir capture isteği göndererek AF'yi başlatalım
        cameraCaptureSession?.capture(
            previewRequestBuilder!!.build(),
            null,
            handler
        )

        // Sonrasında da preview isteğini tekrar setRepeatingRequest ile döndürebilirsiniz.
        cameraCaptureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), null, null)
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

                    // Pitch
                    if      (pitch >  headPoseThreshold)  poseWarnings.add("⬇ Başınızı biraz aşağı eğin.")
                    else if (pitch < -headPoseThreshold) poseWarnings.add("⬆ Başınızı biraz yukarı kaldırın.")

// Yaw
                    if      (yaw   >  headPoseThreshold)  poseWarnings.add("➡ Başınızı biraz sağa çevirin.")
                    else if (yaw   < -headPoseThreshold)  poseWarnings.add("⬅ Başınızı biraz sola çevirin.")

// Roll
                    if      (roll  >  headPoseThreshold)  poseWarnings.add("↺ Başınızı saat yönünün tersine döndürün.")
                    else if (roll  < -headPoseThreshold)  poseWarnings.add("↻ Başınızı saat yönünde döndürün.")


                    if (poseWarnings.isNotEmpty()) {
                        val fullMessage = "❌ Baş pozisyonu uygun değil:\n" + poseWarnings.joinToString("\n")
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
                    showErrorMessage("❌ Yüz algılama hatası oluştu.")
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
        startBackgroundThread()
        if (binding.textureView.isAvailable) {
            openCamera() // now openCamera() uses backgroundHandler
            configureTransform(binding.textureView.width,binding.textureView.height)
        } else {
            setupTextureView()
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
        cameraDevice?.close()
        cameraDevice = null
        stopBackgroundThread()
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