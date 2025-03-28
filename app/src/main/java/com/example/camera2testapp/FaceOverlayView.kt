package com.example.camera2testapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

class FaceOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paintBox = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val paintLandmarks = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        strokeWidth = 8f
    }



    private var faceBoundingBox: RectF? = null
    private var faceLandmarks: List<Pair<Float, Float>> = emptyList()

    fun updateFaceData(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isNotEmpty()) {
            val landmarks = result.faceLandmarks()[0]
            faceLandmarks = landmarks.map { Pair(it.x() * width, it.y() * height) }

            // Bounding box calculations (approximate)
            val minX = faceLandmarks.minOf { it.first }
            val minY = faceLandmarks.minOf { it.second }
            val maxX = faceLandmarks.maxOf { it.first }
            val maxY = faceLandmarks.maxOf { it.second }
            faceBoundingBox = RectF(minX, minY, maxX, maxY)
        } else {
            faceLandmarks = emptyList()
            faceBoundingBox = null
        }
        invalidate() // Refresh the view
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw the "ideal face position" box
        val boxMargin = width * 0.15f // 15% margin

        // Draw bounding box
        faceBoundingBox?.let {
            canvas.drawRect(it, paintBox)
        }

        // Draw face landmarks
        for (point in faceLandmarks) {
            canvas.drawCircle(point.first, point.second, 5f, paintLandmarks)
        }
    }
}
