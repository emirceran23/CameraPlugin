package com.example.camera2testapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CameraGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 1f
        style = Paint.Style.STROKE
        alpha = 100 // Semi-transparent
    }

    var gridType: GridType = GridType.RULE_OF_THIRDS
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        when (gridType) {
            GridType.RULE_OF_THIRDS -> drawRuleOfThirds(canvas)
            GridType.SQUARE -> drawSquareGrid(canvas)
            GridType.DIAGONAL -> drawDiagonalGrid(canvas)
        }
    }

    private fun drawRuleOfThirds(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()

        // Vertical lines
        canvas.drawLine(width / 3, 0f, width / 3, height, paint)
        canvas.drawLine(width * 2 / 3, 0f, width * 2 / 3, height, paint)

        // Horizontal lines
        canvas.drawLine(0f, height / 3, width, height / 3, paint)
        canvas.drawLine(0f, height * 2 / 3, width, height * 2 / 3, paint)
    }

    private fun drawSquareGrid(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        val size = minOf(width, height)
        val cellSize = size / 4

        // Vertical lines
        for (i in 1..3) {
            val x = i * cellSize
            canvas.drawLine(x, 0f, x, height, paint)
        }

        // Horizontal lines
        for (i in 1..3) {
            val y = i * cellSize
            canvas.drawLine(0f, y, width, y, paint)
        }
    }

    private fun drawDiagonalGrid(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()

        // Main diagonals
        canvas.drawLine(0f, 0f, width, height, paint)
        canvas.drawLine(width, 0f, 0f, height, paint)
    }

    enum class GridType {
        RULE_OF_THIRDS, SQUARE, DIAGONAL
    }

}