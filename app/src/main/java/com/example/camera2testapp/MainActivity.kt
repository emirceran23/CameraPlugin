package com.example.camera2testapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.util.proto.RenderDataProto
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d.solvePnP
import org.opencv.core.Mat
import org.opencv.core.CvType
import org.opencv.core.Point
import org.opencv.calib3d.Calib3d
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null

    // The face landmarker provided by MediaPipe Tasks.
    private lateinit var faceLandmarker: FaceLandmarker

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // Inflate layout using view binding (layout file: activity_main.xml)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        try {
            System.loadLibrary("c++_shared") // Load libc++ first
            System.loadLibrary("opencv_java4") // Then load OpenCV
            Log.d("OpenCV", "OpenCV and libc++ loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("OpenCV", "Native library failed to load: ${e.message}")
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
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
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

        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = faceLandmarker.detect(mpImage)
        val faceLandmarksList = result.faceLandmarks()

        if (faceLandmarksList.isNotEmpty()) {
            val landmarks = faceLandmarksList[0]

            // Calculate bounding box using normalized coordinates
            val minX = landmarks.minOf { it.x() } * binding.textureView.width
            val maxX = landmarks.maxOf { it.x() } * binding.textureView.width
            val minY = landmarks.minOf { it.y() } * binding.textureView.height
            val maxY = landmarks.minOf { it.y() } * binding.textureView.height

            val left = minX.toInt()
            val top = minY.toInt()
            val right = maxX.toInt()
            val bottom = maxY.toInt()

            // Update FaceOverlayView with new face detection data
            runOnUiThread {
                binding.overlayView.updateFaceData(result)
            }

            // Ensure only one call to check position
            checkFacePosition(landmarks)

            // Estimate depth
            val estimatedDepth = estimateIrisDepth(landmarks, bitmap)

            // Prepare messages for UI
            val warningMessages = mutableListOf<String>()
            val statusMessages = mutableListOf<String>()

            // **Head Position Check using SolvePnP**
            val headPoseStatus = checkHeadPosition(landmarks, bitmap)
            if (!headPoseStatus.contains("✅")) {
                warningMessages.add(headPoseStatus)
            } else {
                statusMessages.add(headPoseStatus)
            }



            // **✅ Distance to Camera Check**
            val distanceStatus = checkDistanceToCamera(maxX - minX, maxY - minY)
            if (distanceStatus.contains("⚠️")) {
                warningMessages.add(distanceStatus)
            } else {
                statusMessages.add(distanceStatus)
            }

            // **✅ Frontalization Check**
            val frontalStatus = checkFrontalization(landmarks)
            if (frontalStatus.contains("⚠️")) {
                warningMessages.add(frontalStatus)
            } else {
                statusMessages.add(frontalStatus)
            }

            // **✅ Depth Message**
            val depthMessage = "📏 Estimated Distance: ${"%.1f".format(estimatedDepth)} cm"
            statusMessages.add(depthMessage)

            // **🔄 Update UI Only If Needed**
            runOnUiThread {
                // Show warnings in warningTextView
                binding.warningTextView.text = if (warningMessages.isNotEmpty()) {
                    warningMessages.joinToString("\n")
                } else {
                    "✅ No Warnings."
                }
                binding.warningTextView.setTextColor(if (warningMessages.isNotEmpty()) Color.RED else Color.GREEN)

                // Show status messages in a separate text view
                binding.statusTextView.text = statusMessages.joinToString("\n")
                binding.statusTextView.setTextColor(Color.BLUE)
            }

        }
    }




    private fun checkFacePosition(landmarks: List<NormalizedLandmark>) {
        if (landmarks.isEmpty()) return

        val faceCenterX = landmarks.map { it.x() }.average().toFloat() * binding.textureView.width
        val faceCenterY = landmarks.map { it.y() }.average().toFloat() * binding.textureView.height

        val screenCenterX = binding.textureView.width / 2f
        val screenCenterY = binding.textureView.height / 2f

        val offsetX = faceCenterX - screenCenterX
        val offsetY = faceCenterY - screenCenterY

        val thresholdX = binding.textureView.width * 0.18f  // Increased threshold for smoother guidance
        val thresholdY = binding.textureView.height * 0.18f

        var message = "✅ Face is centered."

        if (offsetX > thresholdX) {
            message = "⬅ Move your face LEFT slightly."
        } else if (offsetX < -thresholdX) {
            message = "➡ Move your face RIGHT slightly."
        }

        if (offsetY > thresholdY) {
            message = "⬆ Move your face UP slightly."
        } else if (offsetY < -thresholdY) {
            message = "⬇ Move your face DOWN slightly."
        }

        runOnUiThread {
            binding.warningTextView.text = message
            binding.warningTextView.setTextColor(if (message.contains("✅")) Color.GREEN else Color.RED)
        }
    }






    /**
     * Check head tilt by comparing the vertical position of the eyes.
     */
    private fun checkHeadPosition(landmarks: List<NormalizedLandmark>, bitmap: Bitmap): String {
        if (landmarks.size < 468) return "⚠️ Face not fully detected."

        // **Extract Key Face Landmarks**
        val noseTip = landmarks[1]  // Nose tip
        val chin = landmarks[199]   // Chin
        val leftEye = landmarks[33] // Left eye corner
        val rightEye = landmarks[263] // Right eye corner
        val leftMouth = landmarks[61] // Left mouth corner
        val rightMouth = landmarks[291] // Right mouth corner

        // **Convert to OpenCV MatOfPoint3f and MatOfPoint2f**
        val objectPoints = MatOfPoint3f(
            Point3(0.0, 0.0, 0.0),          // Nose tip
            Point3(0.0, -330.0, -65.0),     // Chin
            Point3(-225.0, 170.0, -135.0),  // Left eye
            Point3(225.0, 170.0, -135.0),   // Right eye
            Point3(-150.0, -150.0, -125.0), // Left mouth corner
            Point3(150.0, -150.0, -125.0)   // Right mouth corner
        )

        val imagePoints = MatOfPoint2f(
            Point(noseTip.x().toDouble() * bitmap.width, noseTip.y().toDouble() * bitmap.height),
            Point(chin.x().toDouble() * bitmap.width, chin.y().toDouble() * bitmap.height),
            Point(leftEye.x().toDouble() * bitmap.width, leftEye.y().toDouble() * bitmap.height),
            Point(rightEye.x().toDouble() * bitmap.width, rightEye.y().toDouble() * bitmap.height),
            Point(leftMouth.x().toDouble() * bitmap.width, leftMouth.y().toDouble() * bitmap.height),
            Point(rightMouth.x().toDouble() * bitmap.width, rightMouth.y().toDouble() * bitmap.height)
        )

        // **Step 3: Camera Matrix**
        val focalLength = bitmap.width.toDouble()
        val centerX = bitmap.width / 2.0
        val centerY = bitmap.height / 2.0
        val cameraMatrix = Mat(3, 3, CvType.CV_64F).apply {
            put(0, 0, focalLength, 0.0, centerX)
            put(1, 0, 0.0, focalLength, centerY)
            put(2, 0, 0.0, 0.0, 1.0)
        }

        val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0) // No lens distortion

        // **Step 4: SolvePnP to Get Rotation Vectors**
        val rotationVector = Mat()
        val translationVector = Mat()
        val success = Calib3d.solvePnP(objectPoints, imagePoints, cameraMatrix, distCoeffs, rotationVector, translationVector)

        if (!success) return "⚠️ Unable to determine head pose."

        // **Step 5: Convert Rotation Vector to Euler Angles**
        val rotationMatrix = Mat()
        Calib3d.Rodrigues(rotationVector, rotationMatrix)

        val angles = rotationMatrixToAngles(rotationMatrix)
        val yaw = angles[1]
        val pitch = angles[0]
        val roll = angles[2]


        // **Step 6: Provide Guidance Based on Thresholds**
        val yawThreshold = 12.0
        val pitchThreshold = 7.0
        val rollThreshold = 7.0

        val guidance = mutableListOf<String>()

        if (yaw > yawThreshold) guidance.add("⚠️ Turn your head LEFT slightly.")
        if (yaw < -yawThreshold) guidance.add("⚠️ Turn your head RIGHT slightly.")
        if (pitch > pitchThreshold) guidance.add("⚠️ Lower your head slightly.")
        if (pitch < -pitchThreshold) guidance.add("⚠️ Raise your head slightly.")
        if (roll > rollThreshold) guidance.add("⚠️ Tilt your head COUNTERCLOCKWISE slightly.")
        if (roll < -rollThreshold) guidance.add("⚠️ Tilt your head CLOCKWISE slightly.")

        return if (guidance.isEmpty()) "✅ Head is well-positioned." else guidance.joinToString("\n")
    }


    private fun rotationMatrixToAngles(rotationMatrix: Mat): DoubleArray {
        val r = FloatArray(9)
        rotationMatrix.get(0, 0, r)  // Extract matrix elements

        val sy = sqrt(r[0] * r[0] + r[3] * r[3])
        val singular = sy < 1e-6

        val x: Double
        val y: Double
        val z: Double

        if (!singular) {
            x = atan2(r[7].toDouble(), r[8].toDouble()) * (180 / Math.PI)
            y = atan2(-r[6].toDouble(), sy.toDouble()) * (180 / Math.PI)
            z = atan2(r[3].toDouble(), r[0].toDouble()) * (180 / Math.PI)
        } else {
            x = atan2(-r[5].toDouble(), r[4].toDouble()) * (180 / Math.PI)
            y = atan2(-r[6].toDouble(), sy.toDouble()) * (180 / Math.PI)
            z = 0.0
        }

        return doubleArrayOf(x, y, z) // Pitch, Yaw, Roll
    }















    /**
     * Check if the face size is within expected ranges.
     */
    private fun checkDistanceToCamera(boxWidth: Float, boxHeight: Float): String {
        return when {
            boxWidth < 0.3f || boxHeight < 0.3f -> "⚠️ Move closer until your face fills ~60% of the screen."
            boxWidth > 0.8f || boxHeight > 0.8f -> "⚠️ Move back until your face fits well in the frame."
            else -> "✅ Distance to camera is good."
        }
    }



    /**
     * Check if the face is frontal.
     */
    private fun checkFrontalization(landmarks: List<NormalizedLandmark>): String {
        val leftCheek = landmarks[234]  // Left cheek landmark
        val rightCheek = landmarks[454] // Right cheek landmark
        val nose = landmarks[1]         // Nose tip

        val faceCenterX = (leftCheek.x() + rightCheek.x()) / 2  // Average of cheeks
        val deviation = faceCenterX - nose.x()  // Difference between center and nose

        val threshold = 0.08  // New, more forgiving threshold (was 0.05)


        if(deviation > threshold) {
            return "➡ Move right slightly"
        }
        else if(deviation < -threshold) {
            return "⬅ Move left slightly"
        }
        else {
            return "✅ Face centered"

        }

    }



    /**
     * Estimate iris diameter and calculate distance for each eye.
     * For demonstration we assume iris landmarks are available at fixed indices.
     */
    private fun estimateIrisDepth(landmarks: List<NormalizedLandmark>, bitmap: Bitmap): Float? {
        // Ensure we have calibration focal length
        if (calibrationFocalLength == null) {
            Log.e("IrisDepth", "Calibration focal length not set!")
            return null
        }

        if (landmarks.size < 478) {
            Log.e("IrisDepth", "Not enough landmarks detected for iris measurement")
            return null
        }

        // Extract left and right iris points
        val leftIrisStart = landmarks[468]  // Start of left iris
        val leftIrisEnd = landmarks[472]    // End of left iris
        val rightIrisStart = landmarks[473] // Start of right iris
        val rightIrisEnd = landmarks[477]   // End of right iris

        // Compute iris diameters in pixels
        val leftIrisDiameter = distanceBetween(leftIrisStart, leftIrisEnd, bitmap)
        val rightIrisDiameter = distanceBetween(rightIrisStart, rightIrisEnd, bitmap)

        // Convert focal length from mm to pixels using sensor width
        val sensorWidth = bitmap.width.toFloat()  // Approximate assumption!!!!!!!!
        val focalLengthPx = calibrationFocalLength!! * sensorWidth

        // Compute depth for each eye
        val leftDepth = (11.7f * focalLengthPx) / leftIrisDiameter
        val rightDepth = (11.7f * focalLengthPx) / rightIrisDiameter

        // Average the two estimates for a more stable depth estimation
        val estimatedDepth = (leftDepth + rightDepth) / 2

        // Log depth results
        Log.d("IrisDepth", "Estimated Distance: Left Eye = ${"%.1f".format(leftDepth)} cm, Right Eye = ${"%.1f".format(rightDepth)} cm")

        return estimatedDepth
    }



    /**
     * Helper function to compute Euclidean distance between two normalized landmarks,
     * then scaled to the bitmap's width (assuming square scaling).
     */
    private fun distanceBetween(p1: NormalizedLandmark, p2: NormalizedLandmark, bitmap: Bitmap): Float {
        val dx = (p1.x() - p2.x()) * bitmap.width
        val dy = (p1.y() - p2.y()) * bitmap.height
        return hypot(dx, dy)
    }






    /**
    private fun openCamera() {
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList[0] // Using first camera

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCameraPreview()
                    // If not yet calibrated, capture a calibration image after a short delay.
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
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera: ${e.message}")
        }
    }*/

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
            Log.e(TAG, "Error opening front camera: ${e.message}")
        }
    }


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
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(surface)

            imageReader = ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let {
                    // If this is the calibration image, read focal length.
                    if (!isCalibrated) {
                        parseCalibrationImage(it)
                    } else {
                        saveImage(it)
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
                            cameraCaptureSession!!.setRepeatingRequest(previewRequestBuilder!!.build(), null, null)
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

    /**
     * Capture a calibration photo to extract focal length from EXIF.
     */
    private fun captureCalibrationPhoto() {
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            cameraCaptureSession!!.capture(captureBuilder.build(), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing calibration photo: ${e.message}")
        }
    }

    /**
     * Regular photo capture for saving image.
     */
    private fun capturePhoto() {
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            cameraCaptureSession!!.capture(captureBuilder.build(), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo: ${e.message}")
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

            // Save the calibration image temporarily
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "calibration.jpg")
            FileOutputStream(file).use { output ->
                output.write(bytes)
            }

            // Read EXIF data from the saved image
            val exif = ExifInterface(file.absolutePath)
            val focalLengthStr = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
            if (focalLengthStr != null) {
                // Focal length is usually stored as a fraction (e.g., "4/1")
                val parts = focalLengthStr.split("/")
                if (parts.size == 2) {
                    val numerator = parts[0].toFloatOrNull() ?: 0f
                    val denominator = parts[1].toFloatOrNull() ?: 1f
                    calibrationFocalLength = numerator / denominator
                    isCalibrated = true
                    runOnUiThread {
                        Toast.makeText(this, "Calibration complete: Focal length = ${calibrationFocalLength} mm", Toast.LENGTH_SHORT).show()
                    }
                    Log.d(TAG, "Calibration focal length: $calibrationFocalLength mm")
                }
            } else {
                Log.e(TAG, "Focal length EXIF not found.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing calibration image: ${e.message}")
        }
    }


    private fun saveImage(image: Image) {
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), ".jpg")
            FileOutputStream(file).use { output ->
                output.write(bytes)
            }
            Log.d(TAG, "Photo saved: ${file.absolutePath}")
            getExifData(file.absolutePath)
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

    override fun onPause() {
        super.onPause()
        cameraDevice?.close()
        cameraDevice = null
    }
}
