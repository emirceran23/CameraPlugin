package com.example.camera2testapp

import android.Manifest
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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import android.content.ContentValues
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.SparseIntArray
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import org.opencv.android.OpenCVLoader
import java.io.OutputStream
import org.opencv.core.*
import org.opencv.calib3d.Calib3d
import java.lang.Math.toDegrees
import java.nio.ByteOrder
import kotlin.math.asin
import kotlin.math.sin


private var smoothedYaw: Double? = null
private var yaw:Int? = null

private var previousYaw: Double? = null
// Store previous landmarks for stabilization
private var previousLandmarks: List<NormalizedLandmark>? = null
private val landmarkSmoothingAlpha = 0.2f // 0-1 (0=no change, 1=full update)
private lateinit var poseLandmarker: PoseLandmarker

class PoseProcessor(private val context: Context) {
    private lateinit var poseLandmarker: PoseLandmarker

    fun initPoseLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinPoseDetectionConfidence(0.8f)
            .setMinTrackingConfidence(0.7f)
            .build()

        poseLandmarker = PoseLandmarker.createFromOptions(context, options)
    }

    fun getPoseLandmarker(): PoseLandmarker {
        return poseLandmarker
    }
}






class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null

    // The face landmarker provided by MediaPipe Tasks.
    private lateinit var faceLandmarker: FaceLandmarker
    private lateinit var gridView: CameraGridView
    // For demonstration, approximate 3D face model points in some coordinate system (e.g. mm):
    // (These are fairly standard guess values. Adjust if needed for better accuracy.)
    // Approximate 3D points for face landmarks (in some consistent coordinate system):



    private var calibration_image_width = 0


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
        private var SENSOR_WIDTH_MM = 6.3f  // Example value (adjust for your device)

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initDebug())
            Log.e("OpenCV", "Unable to load OpenCV!");
        else
            Log.d("OpenCV", "OpenCV loaded Successfully!");
        /**
        try {
        tfliteModel = TFLiteModel(this)  // ✅ Initialize model here
        Log.d("TFLite", "✅ Model loaded successfully")
        } catch (e: Exception) {
        e.printStackTrace()
        Log.e("TFLite", "❌ Error initializing TFLite model: ${e.message}")
        }
        val inputShape = tfliteModel.getInterpreter().getInputTensor(0).shape()
        Log.d("TFLite", "Model Input Shape: ${inputShape.contentToString()}")
        val outputShape = tfliteModel.getInterpreter().getOutputTensor(0).shape()
        val outputDataType = tfliteModel.getInterpreter().getOutputTensor(0).dataType()
        Log.d("TFLite", "Model Output Shape: ${outputShape.contentToString()}, DataType: $outputDataType")
         */


        binding = ActivityMainBinding.inflate(layoutInflater) // Initialize FIRST
        setContentView(binding.root)



        gridView = binding.gridView




        // ✅ Initialize Pose Landmarker and store it
        val poseProcessor = PoseProcessor(this)
        poseProcessor.initPoseLandmarker()
        poseLandmarker = poseProcessor.getPoseLandmarker()

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


        // Initialize the face detector
        initFaceDetector()


        // Make the capture button visible
        binding.btnCapture.visibility = View.VISIBLE

        // Set up capture button click (manual capture)
        binding.btnCapture.setOnClickListener {
            capturePhoto()
        }
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
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
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
        val bitmap = binding.textureView.bitmap ?: return
        if (!::faceLandmarker.isInitialized) return
        if (!::poseLandmarker.isInitialized) {
            Log.e("PoseLandmarker", "poseLandmarker is not initialized!")
            return
        }
        if(calibrationFocalLength == null) {
            Log.e("Calibration", "Calibration focal length is not set yet!")
            return
        }

        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = faceLandmarker.detect(mpImage)
        val faceLandmarksList = result.faceLandmarks()

        val poseresult = poseLandmarker.detect(mpImage)
        val poseLandmarksList = poseresult.landmarks()
        if (poseLandmarksList.isNotEmpty()) {
            val landmarks = poseLandmarksList[0]  // First person detected

            val headPoseAngles = computeHeadPose(landmarks)
            headPoseAngles?.let { (pitch, yawvalue, roll) ->
                yaw = yawvalue.toInt()
            }
        }
        if (faceLandmarksList.isNotEmpty()) {
            val landmarks = faceLandmarksList[0]
            val currentYaw = yaw ?: 0  // Default to 0 if yaw is null

            // 1. Distance estimation
            val (distanceValue, distanceMessage) = estimateDistanceUsingIris(landmarks, bitmap)

            // 2. Face alignment
            val centerMessage = checkFaceCenter(landmarks)

            val rawLandmarks = faceLandmarksList[0]
            val stabilizedLandmarks = stabilizeLandmarks(rawLandmarks)

// Then use stabilizedLandmarks for head pose calculation
            val headPoseAngles = estimateHeadPose(stabilizedLandmarks, bitmap)
            var orientationMessage = ""


            headPoseAngles?.let { (pitch, yawvalue, roll) ->
                // Unify yaw to avoid ±180° jumps



                // Update UI
                runOnUiThread {
                    binding.headPoseTextView.text =
                        "Pitch: ${pitch.toInt()}°\nYaw: ${yaw}°\nRoll: ${roll.toInt()}°"
                }

                // Orientation check with unified yaw
                // Generate orientation correction messages
                val warnings = mutableListOf<String>()

                if (pitch > 2.0) {
                    warnings.add("⬇ Başınızı biraz aşağı eğin.")
                } else if (pitch < -2.0) {
                    warnings.add("⬆ Başınızı biraz yukarı kaldırın.")
                }

                if (currentYaw > 1) {
                    warnings.add("➡ Başınızı biraz sağa çevirin.")
                } else if (currentYaw < -1) {
                    warnings.add("⬅ Başınızı biraz sola çevirin.")
                }

                if (roll > 2.0) {
                    warnings.add("↻ Başınızı saat yönünde döndürün.")
                } else if (roll < -2.0) {
                    warnings.add("↺ Başınızı saat yönünün tersine döndürün.")
                }

                // ✅ Display warning messages or success message
                if (warnings.isEmpty()) {
                    orientationMessage = "✅ Baş pozisyonu uygun"

                    if (!isCapturing) {  // ✅ Prevent multiple captures
                        isCapturing = true
                        startVibrationSequence()  // ✅ Trigger vibration, then capture

                        // ✅ Reset isCapturing after 3 seconds to allow next capture
                        Handler(Looper.getMainLooper()).postDelayed({
                            isCapturing = false
                        }, 3000) // Adjust delay as needed (3 sec)
                    }
                } else {
                    orientationMessage = warnings.joinToString("\n")
                }


                runOnUiThread {
                    binding.orientationTextView.text = orientationMessage
                }
            }

            // Update distance & center UI
            runOnUiThread {
                binding.distanceTextView.text = distanceMessage
            }

            // Auto-capture conditions




        }
    }
    /**
     * Computes head pose angles (pitch, yaw, roll) such that:
     *   - 0° means facing the camera, head upright (no tilt).
     *   - Positive/negative angles show slight rotation from that neutral pose.
     */
    private fun computeHeadPose(landmarks: List<NormalizedLandmark>): Triple<Double, Double, Double>? {
        if (landmarks.size < 25) return null  // Ensure we have enough landmarks

        // Key points from Pose Landmarks
        val nose = landmarks[0]
        val leftShoulder = landmarks[11]
        val rightShoulder = landmarks[12]

        // 1. Compute the "body center" between shoulders.
        val bodyCenterX = (leftShoulder.x() + rightShoulder.x()) / 2.0
        val bodyCenterY = (leftShoulder.y() + rightShoulder.y()) / 2.0
        val bodyCenterZ = (leftShoulder.z() + rightShoulder.z()) / 2.0

        // 2. Vector from body center to nose:
        val nx = nose.x() - bodyCenterX
        val ny = nose.y() - bodyCenterY
        val nz = nose.z() - bodyCenterZ

        // In MediaPipe coordinates:
        //   - x increases left to right
        //   - y increases top to bottom
        //   - z often increases "into" the scene (camera-dependent)

        // We assume "facing the camera" roughly means the nose is in front of (and possibly slightly negative-z compared to) the shoulders.
        // We'll define forward as negative Z. Then:
        //   - Yaw ~ 0 when X=0 (nose is horizontally centered)
        //   - Pitch ~ 0 when Y=0 (nose is at same vertical as shoulders)
        //   - Roll ~ 0 when shoulders are horizontal

        // 3. Calculate Yaw (rotation around vertical Y-axis).
        //    We want 0° if nx=0 and nz < 0 (i.e. nose in front).
        //    => yaw = atan2(X, -Z) so that if X=0 and Z<0 => yaw=0
        val yawRad = atan2(nx, -nz)
        var yawDeg = Math.toDegrees(yawRad)

        // 4. Calculate Pitch (rotation around X-axis).
        //    If the user looks up, we want a positive pitch. If they look down, negative pitch.
        //    Because y in MediaPipe goes downward, we often put a negative sign. This is a convention choice.
        //    => pitch = -atan2(Y, -Z)
        val pitchRad = -atan2(ny, -nz)
        var pitchDeg = Math.toDegrees(pitchRad)

        // 5. Calculate Roll (tilt of head or shoulders).
        //    If the shoulders are level, roll = 0.
        val shoulderDy = leftShoulder.y() - rightShoulder.y()
        val shoulderDx = leftShoulder.x() - rightShoulder.x()
        // Negate to match an upright “0” when left & right shoulders are horizontally aligned
        val rollRad = -atan2(shoulderDy, shoulderDx).toDouble()
        var rollDeg = Math.toDegrees(rollRad)

        // 6. Unify angles into [-180, +180] so they don’t jump to ±179 vs. ±181, etc.
        yawDeg   = unifyAngle(yawDeg)
        pitchDeg = unifyAngle(pitchDeg)
        rollDeg  = unifyAngle(rollDeg)

        return Triple(pitchDeg, yawDeg, rollDeg)
    }

    /**
     * Helper to clamp angles into the [-180, 180] range.
     */
    private fun unifyAngle(angle: Double): Double {
        var result = angle % 360.0
        if (result > 180.0)  result -= 360.0
        if (result < -180.0) result += 360.0
        return result
    }



    private fun stabilizeLandmarks(
        currentLandmarks: List<NormalizedLandmark>
    ): List<NormalizedLandmark> {
        return if (previousLandmarks == null || previousLandmarks!!.size != currentLandmarks.size) {
            // First frame or landmark count changed
            currentLandmarks.also { previousLandmarks = it }
        } else {
            currentLandmarks.mapIndexed { i, current ->
                val prev = previousLandmarks!![i]
                NormalizedLandmark.create(
                    prev.x() + landmarkSmoothingAlpha * (current.x() - prev.x()),
                    prev.y() + landmarkSmoothingAlpha * (current.y() - prev.y()),
                    prev.z() + landmarkSmoothingAlpha * (current.z() - prev.z())
                )
            }.also { previousLandmarks = it }
        }
    }


    private fun rotationMatrixToEulerAngles(R: Mat): Triple<Double, Double, Double> {
        val m = DoubleArray(9)
        R.get(0, 0, m)

        // Row-major
        val r00 = m[0]; val r01 = m[1]; val r02 = m[2]
        val r10 = m[3]; val r11 = m[4]; val r12 = m[5]
        val r20 = m[6]; val r21 = m[7]; val r22 = m[8]

        // Calculate sy to check for gimbal lock
        val sy = kotlin.math.sqrt(r00 * r00 + r10 * r10)
        val singular = sy < 1e-6

        val x: Double
        val y: Double
        val z: Double

        if (!singular) {
            // Typical definitions:
            //  x = pitch, y = yaw, z = roll
            x = kotlin.math.atan2(r21, r22)         // pitch
            y = kotlin.math.atan2(-r20, sy)         // yaw
            z = kotlin.math.atan2(r10, r00)         // roll
        } else {
            // Fallback (close to gimbal lock)
            x = kotlin.math.atan2(-r12, r11)
            y = kotlin.math.atan2(-r20, sy)
            z = 0.0
        }

        // Convert to degrees
        val pitch = Math.toDegrees(x)
        val yaw   = Math.toDegrees(y)
        val roll  = Math.toDegrees(z)

        return Triple(pitch, yaw, roll)
    }
    // Helper function: Unwrap yaw to avoid jumps at ±180°
    private fun unifyYaw(newYaw: Double): Double {
        if (previousYaw == null) {
            previousYaw = newYaw
            return newYaw
        }
        var unwrappedYaw = newYaw
        val yawDiff = newYaw - previousYaw!!
        if (yawDiff > 180.0) {
            unwrappedYaw -= 360.0
        } else if (yawDiff < -180.0) {
            unwrappedYaw += 360.0
        }
        previousYaw = unwrappedYaw
        return unwrappedYaw
    }

    // Helper function: Smooth yaw using an exponential moving average.
    private fun smoothYaw(unwrappedYaw: Double): Double {
        val alpha = 0.3  // Adjust smoothing factor as needed
        if (smoothedYaw == null) {
            smoothedYaw = unwrappedYaw
            return unwrappedYaw
        }
        smoothedYaw = alpha * unwrappedYaw + (1 - alpha) * smoothedYaw!!
        return smoothedYaw!!
    }


    /**
     * Estimate head pose (Euler angles) using selected face landmarks and solvePnP.
     *
     * @param landmarks List of NormalizedLandmark from MediaPipe.
     * @param bitmap The current frame as a Bitmap.
     * @return Triple containing (pitch, yaw, roll) in degrees, or null if estimation fails.
     */
    private fun estimateHeadPose(
        landmarks: List<NormalizedLandmark>,
        bitmap: Bitmap
    ): Triple<Double, Double, Double>? {
        // Check if calibration is done; if not, skip head pose estimation.
        if (calibrationFocalLength == null) {
            Log.e(TAG, "Calibration focal length is not set yet!")
            return null
        }

        // Use the same landmark indices as in Python
        val selectedIndices = listOf(1, 9, 57, 130, 287, 359)
        if (landmarks.size <= selectedIndices.maxOrNull()!!) return null

        // Convert selected normalized landmarks to 2D image points (in pixels)
        val imagePoints = selectedIndices.map { index ->
            val lm = landmarks[index]
            Point(
                (lm.x() * bitmap.width).toDouble(),
                (lm.y() * bitmap.height).toDouble()
            )
        }
        val imagePointsMat = MatOfPoint2f()
        imagePointsMat.fromList(imagePoints)

        // Define the 3D model points (in the same order as the landmarks above)
        val modelPoints = listOf(
            Point3(285.0, 528.0, 200.0),
            Point3(285.0, 371.0, 152.0),
            Point3(197.0, 574.0, 128.0),
            Point3(173.0, 425.0, 108.0),
            Point3(360.0, 574.0, 128.0),
            Point3(391.0, 425.0, 108.0)
        )
        val modelPointsMat = MatOfPoint3f()
        modelPointsMat.fromList(modelPoints)

        // Construct the camera matrix:
        val w = bitmap.width.toDouble()
        val h = bitmap.height.toDouble()
        //val focalLength = (calibrationFocalLength!! * bitmap.width / SENSOR_WIDTH_MM).toDouble()
        val focalLength=w

        val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
        cameraMatrix.put(0, 0, focalLength)
        cameraMatrix.put(0, 1, 0.0)
        cameraMatrix.put(0, 2, w / 2.0)
        cameraMatrix.put(1, 0, 0.0)
        cameraMatrix.put(1, 1, focalLength)
        cameraMatrix.put(1, 2, h / 2.0)
        cameraMatrix.put(2, 0, 0.0)
        cameraMatrix.put(2, 1, 0.0)
        cameraMatrix.put(2, 2, 1.0)

        val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0)

        // Prepare output vectors for rotation and translation.
        val rvec = Mat()
        val tvec = Mat()

        // Solve for pose
        val success = Calib3d.solvePnP(modelPointsMat, imagePointsMat, cameraMatrix, distCoeffs, rvec, tvec)
        if (!success) return null

        // Convert rotation vector to rotation matrix.
        val rotationMatrix = Mat()
        Calib3d.Rodrigues(rvec, rotationMatrix)

        // Get Euler angles from rotation matrix.
        val (pitch, rawYaw, roll) = rotationMatrixToEulerAngles(rotationMatrix)

        // Use custom functions to smooth the yaw value and avoid jumps.
        val unwrappedYaw = unifyYaw(rawYaw)
        val finalYaw = smoothYaw(unwrappedYaw)

        return Triple(pitch, finalYaw, roll)
    }







    /**
     * Turns on the flashlight (torch mode) for the **back camera**.
     */
    private fun turnOnFlashlight() {
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraIdList = cameraManager.cameraIdList

            for (cameraId in cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val isFlashAvailable =
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

                // ✅ Check for BACK CAMERA instead of front camera
                if (isFlashAvailable && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraManager.setTorchMode(cameraId, true) // Turn ON flashlight
                    Log.d(TAG, "✅ Flaş Işığı Açıldı")
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error enabling flashlight: ${e.message}")
        }
    }

    /**
     * Turns off the flashlight (when app closes).
     */
    private fun turnOffFlashlight() {
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraIdList = cameraManager.cameraIdList

            for (cameraId in cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val isFlashAvailable =
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

                // ✅ Check for BACK CAMERA instead of front camera
                if (isFlashAvailable && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraManager.setTorchMode(cameraId, false) // Turn OFF flashlight
                    Log.d(TAG, "✅ Flashlight OFF for back camera")
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error disabling flashlight: ${e.message}")
        }
    }

    // Example updated estimateDistanceUsingIris that returns a Pair of (distanceValue, message)
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
        //val focalLengthPx = calibrationFocalLength!! * bitmap.width / SENSOR_WIDTH_MM!!
        val focalLengthPx =bitmap.width
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
        val irisLandmarkPoints = irisIndices.map { landmarks[it] }
        val leftmost = irisLandmarkPoints.minByOrNull { it.x() } ?: return 0f
        val rightmost = irisLandmarkPoints.maxByOrNull { it.x() } ?: return 0f

        val dx = (rightmost.x() - leftmost.x()) * bitmap.width
        val dy = (rightmost.y() - leftmost.y()) * bitmap.height

        return hypot(dx, dy)
    }


    /**
     * 1) Face-Center Check:
     *    - We compute the average X/Y among the landmarks to find the face center.
     *    - Compare to screen center with a threshold.
     */
    private fun checkFaceCenter(landmarks: List<NormalizedLandmark>): String {
        if (landmarks.isEmpty()) return ""

        // Compute average (x, y) of all landmarks
        val width = binding.textureView.width
        val height = binding.textureView.height

        val avgX = landmarks.map { it.x() }.average().toFloat() * width
        val avgY = landmarks.map { it.y() }.average().toFloat() * height

        // Screen center
        val centerX = width / 2f
        val centerY = height / 2f

        // Compare offsets
        val offsetX = avgX - centerX
        val offsetY = avgY - centerY

        // You can tweak thresholds (10%, 15%, etc.)
        val thresholdX = width * 0.15f
        val thresholdY = height * 0.15f

        // Build correction message
        var message = "✅ Yüzünüz ortalandı."

        if (offsetX > thresholdX) {
            message = "⬅ Yüzünüzü biraz SOLA kaydırın."
        } else if (offsetX < -thresholdX) {
            message = "➡ Yüzünüzü biraz SAĞA kaydırın."
        }

        if (offsetY > thresholdY) {
            message = "⬆ Yüzünüzü biraz YUKARI kaydırın."
        } else if (offsetY < -thresholdY) {
            message = "⬇ Yüzünüzü biraz AŞAĞI kaydırın."
        }

        return message
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

    fun convertFocalLengthToPixels(
        focalLengthMm: Double,
        sensorWidthMm: Double,
        imageWidthPx: Int
    ): Double {
        return (focalLengthMm / sensorWidthMm) * imageWidthPx
    }
    private fun startVibrationSequence() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val vibrationDelay = 500L // 500ms between each vibration

        // First vibration
        vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))

        Handler(Looper.getMainLooper()).postDelayed({
            // Second vibration
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        }, vibrationDelay)

        Handler(Looper.getMainLooper()).postDelayed({
            // Third vibration and prepare for capture
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))

            // Turn on flashlight and capture after a short delay
            Handler(Looper.getMainLooper()).postDelayed({
                capturePhoto()
            }, 200)
        }, vibrationDelay * 2)
    }




    //back camera


    private fun openCamera() {
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraIdList = manager.cameraIdList

            var backCameraId: String? = null

            // Find the back camera
            for (cameraId in cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    backCameraId = cameraId
                    break
                }
            }

            if (backCameraId == null) {
                Log.e(TAG, "Back camera not found!")
                return
            }

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            manager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {

                    cameraDevice = camera
                    startCameraPreview()
                    // Get sensor width in mm
                    SENSOR_WIDTH_MM = getSensorWidthMm() ?: 6.3f // Default to 6.3mm if unknown
                    Log.d(TAG, "📏 Kamera Sensör Genişliği: $SENSOR_WIDTH_MM mm")
                    if (!isCalibrated) {
                        // Capture calibration photo only once
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
            }, null)

        } catch (e: Exception) {
            Log.e(TAG, "Error opening back camera: ${e.message}")
        }
    }









    /**
    private fun openCamera() {
    try {
    val manager = getSystemService(CAMERA_SERVICE) as CameraManager
    val cameraIdList = manager.cameraIdList

    var frontCameraId: String? = null

    // Find the front camera
    for (cameraId in cameraIdList) {
    val characteristics = manager.getCameraCharacteristics(cameraId)
    val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
    if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
    frontCameraId = cameraId
    break
    }
    }

    if (frontCameraId == null) {
    Log.e(TAG, "Front camera not found!")
    return
    }

    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
    return
    }

    manager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
    override fun onOpened(camera: CameraDevice) {
    cameraDevice = camera
    startCameraPreview()
    turnOnFlashlight() // Turn on flashlight when camera opens
    }

    override fun onDisconnected(camera: CameraDevice) {
    camera.close()
    cameraDevice = null
    turnOffFlashlight() // Turn off flashlight when camera closes
    }

    override fun onError(camera: CameraDevice, error: Int) {
    camera.close()
    cameraDevice = null
    Log.e(TAG, "Camera error: $error")
    }
    }, null)

    } catch (e: Exception) {
    Log.e(TAG, "Error opening front camera: ${e.message}")
    }
    }
    */


    /**
     * Start the camera preview.
     */
    private fun startCameraPreview() {
        try {
            val texture = binding.textureView.surfaceTexture
            if (texture == null) {
                Log.e(TAG, "SurfaceTexture is null, retrying later")
                return
            }
            texture.setDefaultBufferSize(1920, 1080)
            val surface = Surface(texture)

            previewRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }

            imageReader = ImageReader.newInstance(3072, 4096, android.graphics.ImageFormat.JPEG, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let {
                    if (!isCalibrated) {
                        parseCalibrationImage(it) // Extract focal length
                        calibration_image_width = image.width
                    } else {
                        saveImageToGallery(it) // Save normal photo
                    }
                    it.close()
                }
            }, null)


            val surfaces = listOf(surface, imageReader!!.surface)
            cameraDevice!!.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        try {
                            cameraCaptureSession!!.setRepeatingRequest(
                                previewRequestBuilder!!.build(),
                                null,
                                null
                            )
                            Log.d(TAG, "✅ Flashlight is ON via CaptureRequest")
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera configuration failed")
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting preview: ${e.message}")
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



    /**
     * Capture a calibration photo to extract focal length from EXIF.
     */
    private fun captureCalibrationPhoto() {
        try {
            val captureBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())

            cameraCaptureSession!!.capture(captureBuilder.build(), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Kalibrasyon fotoğrafı çekilirken hata: ${e.message}")
        }
    }


    /**
     * Regular photo capture for saving image.
     */
    /**
     * Capture photo when face is aligned.
     */
    private fun capturePhoto() {
        try {
            // Step 1: Turn on the flashlight before capturing
            turnOnFlashlight()

            // Step 2: Add a short delay (200ms) to allow the flashlight to activate
            Handler(Looper.getMainLooper()).postDelayed({
                val captureBuilder =
                    cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureBuilder.addTarget(imageReader!!.surface)
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())

                // Enable flash for the actual capture
                captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)

                cameraCaptureSession!!.capture(captureBuilder.build(), null, null)

                imageReader!!.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    image?.let {
                        saveImageToGallery(it)
                        it.close()
                    }

                    // Step 3: Turn off flashlight after capturing
                    Handler(Looper.getMainLooper()).postDelayed({
                        turnOffFlashlight()
                    }, 500) // Small delay before turning off
                }, null)

                Log.d(TAG, "Photo captured with flash!")

            }, 200) // Flashlight activation delay
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo: ${e.message}")
            turnOffFlashlight() // Ensure flashlight turns off even if error occurs
        }
    }



    /**
     * Parse the calibration image's EXIF data to get the focal length.
     */
    private fun parseCalibrationImage(image: Image) {
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "calibration.jpg")
            FileOutputStream(file).use { output -> output.write(bytes) }

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
                            "🔍 Kalibrasyon tamamlandı! Odak Uzaklığı = ${calibrationFocalLength} mm",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    Log.d(TAG, "✅ Kalibrasyon odak uzaklığı: $calibrationFocalLength mm")
                }
            } else {
                Log.e(TAG, "❌ EXIF odak uzaklığı bulunamadı!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Kalibrasyon görüntüsü işlenirken hata: ${e.message}")
        }
    }




    private fun saveImageToGallery(image: Image) {
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val fileName = "Captured_${System.currentTimeMillis()}.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera") // Saves in the gallery
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = contentResolver
            val imageUri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            imageUri?.let { uri ->
                val outputStream: OutputStream? = resolver.openOutputStream(uri)
                outputStream?.use { it.write(bytes) }

                // Mark image as complete and visible in the gallery
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                Log.d(TAG, "Photo saved: $uri")

                runOnUiThread {
                    Toast.makeText(this, "Photo saved to Gallery", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving photo: ${e.message}")
        }
    }

    /**
     * Read and display EXIF data from the captured image.
     */
    private fun getExifData(photoPath: String) {
        try {
            val exif = ExifInterface(photoPath)
            val exifData = """
                Date: ${exif.getAttribute(ExifInterface.TAG_DATETIME)}
                ISO: ${exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)}
                Exposure Time: ${exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)}
                Aperture: ${exif.getAttribute(ExifInterface.TAG_F_NUMBER)}
                Focal Length: ${exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)}
                White Balance: ${exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE)}
                Flash: ${exif.getAttribute(ExifInterface.TAG_FLASH)}
                Camera Model: ${exif.getAttribute(ExifInterface.TAG_MODEL)}
                Camera Make: ${exif.getAttribute(ExifInterface.TAG_MAKE)}
                Latitude: ${exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)}
                Longitude: ${exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)}
            """.trimIndent()
            Log.d("EXIF", exifData)
            runOnUiThread {
                Toast.makeText(this, exifData, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("EXIF", "Error reading EXIF: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        if (binding.textureView.isAvailable) {
            openCamera()
        } else {
            setupTextureView()
        }
    }

    /**
     * Turns off flashlight when app is closed.
     */
    override fun onPause() {
        super.onPause()
        cameraDevice?.close()
        cameraDevice = null
        turnOffFlashlight()
    }
}
