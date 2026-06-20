package com.dopamincheker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.dopamincheker.detection.AlertState
import kotlin.math.sin

class AlertIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var alertState: AlertState = AlertState.NORMAL

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 10, 10, 20)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 26f
    }

    private val bgRect    = RectF()
    private val cornerR   = 14f
    private val padH      = 14f
    private val padV      = 10f
    private val dotRadius = 7f
    private val dotGap    = 10f

    fun updateState(state: AlertState) {
        if (state == alertState) return
        val wasAlert = alertState != AlertState.NORMAL
        alertState = state
        if (wasAlert != (state != AlertState.NORMAL)) requestLayout()
        invalidate()
    }

    private fun reasonText(): String = when (alertState) {
        AlertState.ALERT_HR    -> "FC ALTA"
        AlertState.ALERT_BLINK -> "PARP BAJO"
        AlertState.ALERT_BOTH  -> "FC+PARP"
        AlertState.NORMAL      -> ""
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (dotRadius * 2 + padV * 2).toInt()
        val w = if (alertState == AlertState.NORMAL) {
            (dotRadius * 2 + padH * 2).toInt()
        } else {
            (padH + dotRadius * 2 + dotGap + textPaint.measureText(reasonText()) + padH).toInt()
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        bgRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, cornerR, cornerR, bgPaint)
        canvas.drawRoundRect(bgRect, cornerR, cornerR, borderPaint)

        val cy = h / 2f
        val cx = padH + dotRadius

        if (alertState == AlertState.NORMAL) {
            dotPaint.color = Color.argb(180, 46, 204, 113)  // verde tenue
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)
        } else {
            // Pulso: alfa oscila entre 0.55 y 1.0 (período ~800 ms)
            val pulse = (0.55f + 0.45f * sin(System.currentTimeMillis() / 400.0)).toFloat()
            val red = Color.argb((pulse * 255).toInt(), 231, 76, 60)
            dotPaint.color = red
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)

            textPaint.color = red
            val textX = cx + dotRadius + dotGap
            val textY = cy + textPaint.textSize * 0.35f
            canvas.drawText(reasonText(), textX, textY, textPaint)

            postInvalidateOnAnimation()  // sigue dibujando para animar el pulso
        }
    }
}
