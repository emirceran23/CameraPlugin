package com.example.camera2testapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camera2testapp.databinding.ActivityCalibrationBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.example.camera2testapp.CheckerboardOverlayView
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import org.json.JSONObject


class CalibrationActivity : AppCompatActivity() {

    companion object {
        private const val CAMERA_PERMISSION_CODE = 101
        private const val TAG = "CalibrationActivity"
    }

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var calibrator: CameraCalibration
    private lateinit var overlayView: CheckerboardOverlayView
    private val cameraProviderFuture by lazy { ProcessCameraProvider.getInstance(this) }

    // Statistics
    private var totalFrames = 0
    private var successfulFrames = 0
    private var isPaused = false


    // Cooldown parameters (milliseconds) to encourage capturing from different perspectives
    private val COOLDOWN_MS = 1500L
    private var lastSuccessfulTimestamp = 0L

    // Add field to keep the most recent frame for undistortion
    private var lastCapturedBitmap: Bitmap? = null

    // ADD focal length variable (in millimetres) retrieved from CameraCharacteristics
    private var focalLengthMm: Float? = null

    private val REQUIRED_FRAMES = 5 // automatic calibration threshold

    private var calibrationFinished = false

    private fun log(msg: String) {
        Log.d(TAG, msg)
    }

    // UI helpers for cooldown/instruction
    private fun setCooldownUI(active: Boolean, message: String) {
        runOnUiThread {
            binding.instructionTextView.text = message
            binding.instructionTextView.visibility = android.view.View.VISIBLE
            binding.cooldownProgress.visibility = if (active) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
    override fun onPause() {
        super.onPause()
        isPaused = true
        cameraProviderFuture.get().unbindAll()
    }
    
    override fun onResume() {
        super.onResume()
        isPaused = false
        if (!calibrationFinished) {
            startCamera()
        } else {
            Log.d(TAG, "Camera will not restart after calibration.")
        }
    }
    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log("onCreate")
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (!org.opencv.android.OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed")
            Toast.makeText(this, "OpenCV failed to load", Toast.LENGTH_LONG).show()
            finish()
            return
        } else {
            Log.d(TAG, "OpenCV initialized successfully")
        }


        // Initialize calibrator (Adjust board size & square length as needed)
        calibrator = CameraCalibration(9, 6, 25.0) // 9×6 inner corners, 25 mm squares

        overlayView = binding.overlayView

        cameraExecutor = Executors.newSingleThreadExecutor()

        log("Requesting permissions / starting camera")
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            )
        }

        binding.btnFinish.setOnClickListener {
            finishCalibration()
        }

        // Obtain focal length once (requires camera permission granted)
        focalLengthMm = try {
            getBackCameraFocalLengthMm()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query focal length", e)
            null
        }

    }

    private fun updateStatus() {
        runOnUiThread {
            binding.statusTextView.text = "Frames: $successfulFrames / $totalFrames"
        }
    }

    private fun startCamera() {
        log("startCamera() invoked. calibrationFinished=$calibrationFinished")
        if (calibrationFinished) {
            Log.d(TAG, "startCamera() called after calibration — ignoring.")
            return
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            log("CameraProvider obtained")

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, { imageProxy ->
                        processImageProxy(imageProxy)
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                log("Unbound all previous use cases")
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
                log("Preview & ImageAnalysis bound to lifecycle")
            } catch (exc: Exception) {
                Log.e(TAG, "Camera binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }
    

    private fun processImageProxy(imageProxy: ImageProxy) {
        
        if (isPaused || calibrationFinished) {
            imageProxy.close()
            return
        }
        val bitmap = imageProxy.toBitmap() ?: run {
            Log.w(TAG, "toBitmap returned null – skipping frame")
            imageProxy.close()
            return
        }

        // Keep reference to last captured frame (make a copy to avoid recycling issues)
        lastCapturedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

        totalFrames++
        Log.d(TAG, "Frame #$totalFrames converted. Size=${bitmap.width}x${bitmap.height}")
        val found = calibrator.addFrame(bitmap)

        if (found) {
            // show overlay no matter what
            calibrator.getLastDetectedCorners()?.let { /* …overlay… */ }

            val now = System.currentTimeMillis()
            val withinCooldown = now - lastSuccessfulTimestamp < COOLDOWN_MS

            if (!withinCooldown && successfulFrames < REQUIRED_FRAMES) {
                // ✅ This frame *really* counts
                successfulFrames++
                lastSuccessfulTimestamp = now
                setCooldownUI(true, "Good capture! Move phone to a new angle…")
                log("Frame accepted (#$successfulFrames)")
                log("Successful frames: $successfulFrames")
                log("calibrationFinished: $calibrationFinished")

                // -------- NEW –> if we just reached the quota, lock the pipeline *immediately*
                if (successfulFrames == REQUIRED_FRAMES && !calibrationFinished) {
                    log("Reached required frames. Setting calibrationFinished to true.")
                               // <-- block any more processing **now**
                    runOnUiThread { finishCalibration() }
                }
            } else {
                // ❌ We don't want to keep this detection – throw it away
                calibrator.discardLastFrame()            // (new helper, see below)
            }
        } else {
            overlayView.clear()
            setCooldownUI(false, "Align the checkerboard and hold still")
        }

        updateStatus()
        imageProxy.close()
    }

    private fun finishCalibration() {
        if (calibrationFinished) {
            Log.d(TAG, "finishCalibration called but already finished – ignoring")
            return
        }
        calibrationFinished = true
        log("finishCalibration started")
    
        
    
        setCooldownUI(true, "Calibrating… please wait")
        log("UI set to calibrating state")
    
        Thread {
            try {
                log("Background calibration thread started with $successfulFrames frames")
                val start = System.currentTimeMillis()
                val rms = calibrator.calibrate()
                log("calibrator.calibrate() returned $rms")
                val elapsed = System.currentTimeMillis() - start
                Log.d(TAG, "Calibration finished in $elapsed ms. RMS: $rms")
    
                runOnUiThread {
                    if (rms > 0) {
                        CameraPlugin.callback?.OnCalibrationFinished(true) // ✅ success
                    } else {
                        CameraPlugin.callback?.OnCalibrationFinished(false) // ❌ failed
                    }
                    handleCalibrationResult(rms)
                    try { cameraProviderFuture.get().unbindAll() } catch (_: Exception) {}
                    // INSERT_YOUR_CODE
                    // Inform Unity if calibration was successful
                    
                    cameraExecutor.shutdown()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Calibration error", e)
                runOnUiThread {
                    Toast.makeText(this, "Calibration failed: ${e.message}", Toast.LENGTH_LONG).show()
                    setCooldownUI(false, "Calibration failed")
                }
            }
        }.start()
    }
    

    // Executes on UI thread once calibration is finished
    private fun handleCalibrationResult(rms: Double) {
        log("handleCalibrationResult called, rms=$rms")
        if (rms <= 0) {
            Toast.makeText(this, "Not enough valid frames", Toast.LENGTH_SHORT).show()
            setCooldownUI(false, "Calibration failed")
            return
        }

        try {
            val jsonStr = calibrator.toJsonString()
            // Augment calibration JSON with additional metadata (focal length)
            val jsonObj = JSONObject(jsonStr)
            focalLengthMm?.let { jsonObj.put("focal_length_mm", it) }
            val file = File(getExternalFilesDir(null), "calibration.json")
            file.writeText(jsonObj.toString())

            log("Calibration successful; writing JSON and saving undistorted preview")
            Toast.makeText(
                this,
                "Calibration saved to ${file.absolutePath}\nRMS error: $rms",
                Toast.LENGTH_LONG
            ).show()

            // Undistort last frame and save to gallery
            lastCapturedBitmap?.let { bmp ->
                undistortAndSave(bmp)
            }

            setCooldownUI(false, "Calibration complete!")

            log("Activity finished with RESULT_OK")
            setResult(RESULT_OK)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save calibration", e)
            Toast.makeText(this, "Failed to save calibration: ${e.message}", Toast.LENGTH_LONG).show()
            setCooldownUI(false, "Calibration failed")
        }
    }

    // Undistorts the provided bitmap using the computed calibration parameters and saves it to the Pictures gallery
    private fun undistortAndSave(srcBitmap: Bitmap) {
        if (!calibrator.isCalibrated()) return
        try {
            val srcMat = org.opencv.core.Mat()
            org.opencv.android.Utils.bitmapToMat(srcBitmap, srcMat)
            val dstMat = org.opencv.core.Mat()
            org.opencv.calib3d.Calib3d.undistort(
                srcMat,
                dstMat,
                calibrator.getCameraMatrix(),
                calibrator.getDistCoeffs()
            )
            val undistorted = Bitmap.createBitmap(dstMat.cols(), dstMat.rows(), Bitmap.Config.ARGB_8888)
            org.opencv.android.Utils.matToBitmap(dstMat, undistorted)
            srcMat.release()
            dstMat.release()

            // Save to MediaStore
            val resolver = contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "undistorted_${System.currentTimeMillis()}.jpg")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Camera2TestApp")
                }
            }
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { outUri ->
                resolver.openOutputStream(outUri)?.use { os ->
                    undistorted.compress(Bitmap.CompressFormat.JPEG, 100, os)
                    os.flush()
                }
            }
            android.widget.Toast.makeText(this, "Undistorted image saved to gallery", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to undistort/save image", e)
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        log("onDestroy")
        cameraExecutor.shutdown()
    }

    private fun getBackCameraFocalLengthMm(): Float? {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                val focals = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                if (focals != null && focals.size > 0) {
                    return focals[0] // take the first reported focal length
                }
            }
        }
        return null // not found
    }
}

private fun ImageProxy.toBitmap(): Bitmap? {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    val imageBytes = out.toByteArray()
    Log.d("TAG", "Image bytes size: ${imageBytes.size}")
    Log.d("TAG", "Image width: $width, height: $height")
    Log.d("TAG", "Image format: ${ImageFormat.NV21}")
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
} 
