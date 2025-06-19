package com.example.camera2testapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

/**
 * Simple overlay that visualises the checkerboard corners detected in the live preview.
 * The positions are assumed to be given in the preview's pixel coordinate system.
 */
class CheckerboardOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val cornerPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    // Contains points row-major in the checkerboard order
    private var corners: List<PointF> = emptyList()
    private var cols: Int = 0
    private var rows: Int = 0

    /**
     * Updates the corner list to be rendered.
     * @param cornerPoints Points in preview pixel coordinates.
     * @param cornerCols   Number of inner corners along the width.
     * @param cornerRows   Number of inner corners along the height.
     */
    fun updateCorners(cornerPoints: List<PointF>, cornerCols: Int, cornerRows: Int) {
        corners = cornerPoints
        cols = cornerCols
        rows = cornerRows
        invalidate()
    }

    /** Clears the current drawing. */
    fun clear() {
        corners = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (corners.isEmpty()) return

        // Draw lines between neighbouring corners for better visualisation
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val idx = row * cols + col
                val p = corners[idx]

                // Draw horizontal connections (except for last col)
                if (col < cols - 1) {
                    val pRight = corners[idx + 1]
                    canvas.drawLine(p.x, p.y, pRight.x, pRight.y, linePaint)
                }
                // Draw vertical connections (except for last row)
                if (row < rows - 1) {
                    val pBottom = corners[idx + cols]
                    canvas.drawLine(p.x, p.y, pBottom.x, pBottom.y, linePaint)
                }

                // Draw the corner itself
                canvas.drawCircle(p.x, p.y, 6f, cornerPaint)
            }
        }
    }
} 