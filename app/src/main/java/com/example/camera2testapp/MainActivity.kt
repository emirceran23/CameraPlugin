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
import androidx.appcompat.app.AlertDialog
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import java.io.OutputStream




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
        private var SENSOR_WIDTH_MM = 6.3f  // Example value (adjust for your device)

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater) // Initialize FIRST
        setContentView(binding.root)





        // Inflate layout using view binding (layout file: activity_main.xml)


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

            runOnUiThread {
                binding.overlayView.updateFaceData(result)
            }

            // 1️⃣ Compute Distance
            val distanceMessage = estimateDistanceUsingIris(landmarks, bitmap)

            // 2️⃣ Check if face is centered
            val centerMessage = checkFaceCenter(landmarks)

            // 3️⃣ Check if face is oriented properly
            val orientationMessage = checkFaceOrientation(landmarks)

            val messages = ArrayList<String>()
            if (!centerMessage.contains("✅")) messages.add(centerMessage)
            if (!orientationMessage.contains("✅")) messages.add(orientationMessage)

            val isFaceProperlyAligned = messages.isEmpty()

            // 4️⃣ If everything is good, capture photo
            if (isFaceProperlyAligned && !isCapturing) {
                isCapturing = true  // Prevent multiple captures
                capturePhoto()
            }

            // 5️⃣ Update UI (Distance + Warnings)
            runOnUiThread {
                val combinedMessage = "$distanceMessage\n" + messages.joinToString("\n")
                Log.d(TAG,"$distanceMessage\n")
                binding.warningTextView.text = combinedMessage
                binding.warningTextView.setTextColor(if (messages.isEmpty()) Color.GREEN else Color.RED)
            }
        }
    }

    /**
     * Turns on the flashlight (torch mode) when the camera is opened.
     */
    /**
     * Turns on the flashlight (torch mode) for the **back camera**.
     */
    private fun turnOnFlashlight() {
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraIdList = cameraManager.cameraIdList

            for (cameraId in cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val isFlashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
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
                val isFlashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
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
    private fun estimateDistanceUsingIris(landmarks: List<NormalizedLandmark>, bitmap: Bitmap): String {
        if (calibrationFocalLength == null || SENSOR_WIDTH_MM == null) {
            return "⚠️ Kalibrasyon gerekli! Odak uzaklığı veya sensör genişliği bilinmiyor."
        }

        val leftIrisLandmarks = listOf(468, 469, 470, 471, 472)
        val rightIrisLandmarks = listOf(473, 474, 475, 476, 477)

        val leftIrisDiameterPixels = calculateIrisDiameter(leftIrisLandmarks, landmarks, bitmap)
        val rightIrisDiameterPixels = calculateIrisDiameter(rightIrisLandmarks, landmarks, bitmap)

        val averageIrisDiameterPixels = (leftIrisDiameterPixels + rightIrisDiameterPixels) / 2

        val focalLengthPx = calibrationFocalLength!! * bitmap.width / SENSOR_WIDTH_MM!!

        val distanceToCameraMm = (REAL_IRIS_DIAMETER_MM * focalLengthPx) / averageIrisDiameterPixels
        val distanceToCameraCm = distanceToCameraMm / 10

        return "📏 Tahmini Mesafe: %.2f cm".format(distanceToCameraCm)
    }


    private fun calculateIrisDiameter(irisIndices: List<Int>, landmarks: List<NormalizedLandmark>, bitmap: Bitmap): Float {
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

    /**
     * 2) Face Orientation Check (Frontal / 90°):
     *    - Check “yaw” by the difference between nose.x and midpoint of cheeks.
     *    - Check “roll” by slope between left eye & right eye.
     *      If slope is big, user’s head is tilted.
     */
    private fun checkFaceOrientation(landmarks: List<NormalizedLandmark>): String {
        // Example: nose tip, left cheek, right cheek
        // (Using same indices you have, but see note below for eyes.)
        val nose = landmarks[1]
        val leftCheek = landmarks[234]
        val rightCheek = landmarks[454]

        // For roll, use left eye corners & right eye corners.
        // Some typical Face Mesh indices for eyes (use whichever works best in your model):
        //   - left eye outer corner: 33  / left eye inner corner: 133
        //   - right eye outer corner: 263 / right eye inner corner: 362
        // But if your model’s indices differ, adjust accordingly.
        val leftEyeOuter = landmarks[33]
        val rightEyeOuter = landmarks[263]

        // 2a) YAW check: nose.x vs midpoint(cheeks)
        val cheeksMidX = (leftCheek.x() + rightCheek.x()) / 2f
        val yawDeviation = nose.x() - cheeksMidX
        // Threshold for yaw (how “straight on” the face is left/right).
        val yawThreshold = 0.03f

        // 2b) ROLL check: slope between leftEyeOuter and rightEyeOuter
        val dx = rightEyeOuter.x() - leftEyeOuter.x()
        val dy = rightEyeOuter.y() - leftEyeOuter.y()
        // angle in radians
        val angleRadians = kotlin.math.atan2(dy, dx)
        // convert to degrees for easier reading
        val rollAngleDeg = Math.toDegrees(angleRadians.toDouble()).toFloat()
        // We only care if the tilt is more than e.g. ±5 degrees
        val rollThresholdDeg = 5f

        val sb = StringBuilder()
        var needsAdjustment = false

        // Yaw check
        if (yawDeviation > yawThreshold) {
            sb.append("⬅ Başınızı biraz SOLA çevirin.\n")
            needsAdjustment = true
        } else if (yawDeviation < -yawThreshold) {
            sb.append("➡ Başınızı biraz SAĞA çevirin.\n")
            needsAdjustment = true
        }

        // Roll check
        if (kotlin.math.abs(rollAngleDeg) > rollThresholdDeg) {
            if (rollAngleDeg > 0) {
                sb.append("↺ Başınızı saat yönünde biraz eğin.\n")
            } else {
                sb.append("↻  Başınızı saat yönünün tersine biraz eğin\n")
            }
            needsAdjustment = true
        }

        if (!needsAdjustment) {
            sb.append("✅ Yüzünüz doğru açıda.")
        }
        return sb.toString().trim()
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
                    val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    return sensorSize?.width // Returns width in mm
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Sensor width could not be determined: ${e.message}")
        }
        return null
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

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
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
                    //turnOnFlashlight() // Turn on flashlight when camera opens
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    //turnOffFlashlight() // Turn off flashlight when camera closes
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




    /**
     * Open the camera and turn on the flashlight.
     */
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

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                //set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH) // ✅ Enables Flashlight
            }

            imageReader = ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let {
                    if (!isCalibrated) {
                        parseCalibrationImage(it) // Extract focal length
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
                            cameraCaptureSession!!.setRepeatingRequest(previewRequestBuilder!!.build(), null, null)
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


    /**
     * Capture a calibration photo to extract focal length from EXIF.
     */
    private fun captureCalibrationPhoto() {
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)

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
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)

            cameraCaptureSession!!.capture(captureBuilder.build(), null, null)

            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let {
                    saveImageToGallery(it) // Save to gallery
                    it.close()
                }
            }, null)

            Log.d(TAG, "Photo captured!")

            // Prevent repeated captures
            Handler(Looper.getMainLooper()).postDelayed({
                isCapturing = false
            }, 2000)

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
                        Toast.makeText(this, "🔍 Kalibrasyon tamamlandı! Odak Uzaklığı = ${calibrationFocalLength} mm", Toast.LENGTH_SHORT).show()
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
            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

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
