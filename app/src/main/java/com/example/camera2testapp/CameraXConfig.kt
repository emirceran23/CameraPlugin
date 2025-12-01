package com.example.camera2testapp

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/**
 * Configuration class for CameraX.
 * This class holds the configuration for CameraX use cases.
 */
data class CameraXConfig(
    val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    val resolutionSelector: ResolutionSelector? = null,
    val imageCaptureMode: Int = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
    val flashMode: Int = ImageCapture.FLASH_MODE_AUTO,
    val imageAnalysisStrategy: Int = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
) {
    companion object {
        fun default(): CameraXConfig {
            return CameraXConfig()
        }

        fun highQuality(): CameraXConfig {
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()

            return CameraXConfig(
                imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY,
                resolutionSelector = resolutionSelector
            )
        }
    }

    // rotation + optional builder customization lambdas

    fun buildPreview(
        rotation: Int? = null,
        configure: Preview.Builder.() -> Unit = {}
    ): Preview {
        val builder = Preview.Builder().apply {
            rotation?.let { setTargetRotation(it) }
            resolutionSelector?.let { setResolutionSelector(it) }
            configure()
        }
        return builder.build()
    }

    fun buildImageCapture(
        rotation: Int? = null,
        configure: ImageCapture.Builder.() -> Unit = {}
    ): ImageCapture {
        val builder = ImageCapture.Builder().apply {
            setCaptureMode(imageCaptureMode)
            setFlashMode(flashMode)
            rotation?.let { setTargetRotation(it) }
            resolutionSelector?.let { setResolutionSelector(it) }
            configure()
        }
        return builder.build()
    }

    fun buildImageAnalysis(
        rotation: Int? = null,
        configure: ImageAnalysis.Builder.() -> Unit = {}
    ): ImageAnalysis {
        val builder = ImageAnalysis.Builder().apply {
            setBackpressureStrategy(imageAnalysisStrategy)
            rotation?.let { setTargetRotation(it) }
            resolutionSelector?.let { setResolutionSelector(it) }
            configure()
        }
        return builder.build()
    }
}
