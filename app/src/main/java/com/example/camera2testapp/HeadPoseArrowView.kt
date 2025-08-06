package com.example.camera2testapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.*

class HeadPoseArrowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==== SABİT & PALETLER ==================================================
    private val COLOR_PITCH = Color.parseColor("#FF9800")     // turuncu
    private val COLOR_YAW   = Color.parseColor("#03A9F4")     // mavi
    private val COLOR_ROLL  = Color.parseColor("#E040FB")     // mor
    private val COLOR_BG    = 0xBB000000.toInt()              // yarı-opak siyah
    private val COLOR_OK    = Color.parseColor("#4CAF50")     // hizalı -> yeşil
    private val COLOR_KO    = Color.WHITE

    // ==== BOYUTLANDIRMA =====================================================
    private val arrowLen   = 64f
    private val arrowWide  = 26f
    private val ringRadius = 36f      // roll halkası
    private val ringStroke = 6f
    private val gap        = 72f
    private val panelSize  = 240

    // ==== ÇİZİM FIRÇALARI ===================================================
    private val pArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pCenter = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_BG }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStroke
    }

    // ==== DURUM DEĞİŞKENLERİ ===============================================
    private var pitch = 0f; private var yaw = 0f; private var roll = 0f
    private var thr = 3f; private var aligned = false

    // Public API -------------------------------------------------------------
    fun updateHeadPose(pitch: Float, yaw: Float, roll: Float, threshold: Float) {
        this.pitch = pitch; this.yaw = yaw; this.roll = roll; this.thr = threshold
        aligned = abs(pitch) <= thr && abs(yaw) <= thr && abs(roll) <= thr
        invalidate()
    }

    // ==== ÇİZİM =============================================================
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f; val cy = height / 2f
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 20f, 20f, pBg)

        // Merkez noktası ------------------------------------------------------
        pCenter.color = if (aligned) COLOR_OK else COLOR_KO
        c.drawCircle(cx, cy, if (aligned) 9f else 6f, pCenter)

        drawPitchYaw(c, cx, cy)
        drawRoll(c, cx, cy)

        // STATÜ ---------------------------------------------------------------
        pText.color = if (aligned) COLOR_OK else COLOR_KO
        c.drawText(if (aligned) "ALIGNED" else "ARROWS", cx, height - 14f, pText)
    }

    private fun drawPitchYaw(c: Canvas, cx: Float, cy: Float) {
        // UP / DOWN (pitch) ---------------------------------------------------
        drawArrow(c, cx, cy - gap, 0f,
            active = abs(pitch) > thr && pitch < 0, COLOR_PITCH)

        drawArrow(c, cx, cy + gap, 180f,
            active = abs(pitch) > thr && pitch > 0, COLOR_PITCH)

        // LEFT / RIGHT (yaw) --------------------------------------------------
        drawArrow(c, cx - gap, cy, 270f,
            active = abs(yaw) > thr && yaw > 0, COLOR_YAW)

        drawArrow(c, cx + gap, cy, 90f,
            active = abs(yaw) > thr && yaw < 0, COLOR_YAW)
    }

    private fun drawRoll(c: Canvas, cx: Float, cy: Float) {
        if (abs(roll) <= thr) return   // hizalıysa çizme

        val clockwise = roll < 0
        // halkayı köşelere değil, merkeze çiz — iki katmanlı
        val sweep = if (clockwise) 250f else -250f
        val start = if (clockwise) 155f else -65f

        pRing.color = COLOR_ROLL
        pRing.alpha = 220
        c.drawArc(RectF(cx - ringRadius, cy - ringRadius,
                        cx + ringRadius, cy + ringRadius),
                  start, sweep, false, pRing)

        // ok başı -------------------------------------------------------------
        val endDeg = start + sweep
        val endRad = Math.toRadians(endDeg.toDouble())
        val ax = cx + ringRadius * cos(endRad).toFloat()
        val ay = cy + ringRadius * sin(endRad).toFloat()

        val tan = endRad + if (clockwise) Math.PI / 2 else -Math.PI / 2
        val arr = Path().apply {
            val s = 16f
            moveTo(ax, ay)
            lineTo(ax - s * cos(tan + 0.45).toFloat(),
                   ay - s * sin(tan + 0.45).toFloat())
            lineTo(ax - s * cos(tan - 0.45).toFloat(),
                   ay - s * sin(tan - 0.45).toFloat())
            close()
        }
        pArrow.color = COLOR_ROLL; pArrow.alpha = 255
        c.drawPath(arr, pArrow)
    }

    private fun drawArrow(
        c: Canvas, x: Float, y: Float, rot: Float,
        active: Boolean, color: Int
    ) {
        pArrow.color = color
        pArrow.alpha = if (active) 255 else 70
        c.save(); c.rotate(rot, x, y)
        val p = Path().apply {
            moveTo(x, y - arrowLen / 2)
            lineTo(x - arrowWide / 2, y + arrowLen / 2)
            lineTo(x, y + arrowLen / 4)
            lineTo(x + arrowWide / 2, y + arrowLen / 2)
            close()
        }
        c.drawPath(p, pArrow)
        c.restore()
    }

    // Ölçü -------------------------------------------------------------------
    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(panelSize, panelSize)
    }
}
