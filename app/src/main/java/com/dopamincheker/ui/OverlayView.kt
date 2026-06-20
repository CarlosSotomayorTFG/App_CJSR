package com.dopamincheker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class DisplayData(
        val blinksPerMinute: Float = 0f,
        val totalBlinks: Int = 0,
        val sessionSeconds: Long = 0L,
        val faceDetected: Boolean = false,
        val currentBpm: Int = 0,
        val calibrationRemainingMs: Long? = null
    )

    private var data = DisplayData()
    private var wasCalibration = false

    // Background pill
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 10, 10, 20)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    // Labels
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 200, 200, 220)
        textSize = 20f
        typeface = Typeface.MONOSPACE
    }

    // Values
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    // Accent green (blinks, time)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 88, 214, 141)
        textSize = 30f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    // Orange for calibration
    private val calPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 240, 165, 0)
        textSize = 30f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    // Red for HR when no face
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 220, 80, 80)
        textSize = 20f
        typeface = Typeface.MONOSPACE
    }

    // Divider
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 255, 255, 255)
        style = Paint.Style.FILL
        strokeWidth = 1f
    }

    // Orange top border for calibration mode
    private val calBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 240, 165, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val bgRect = RectF()
    private val cornerRadius = 14f
    private val padH = 18f
    private val padV = 14f
    private val colGap = 20f

    // Column widths (computed in onMeasure)
    private var colHrW = 0f
    private var colBlinkW = 0f
    private var colTimeW = 0f

    fun updateData(newData: DisplayData) {
        val calChanged = (newData.calibrationRemainingMs != null) != wasCalibration
        wasCalibration = newData.calibrationRemainingMs != null
        data = newData
        if (calChanged) requestLayout()
        invalidate()
    }

    private fun hrString()    = if (data.currentBpm > 0) "${data.currentBpm}" else "-"
    private fun blinkString() = "${data.blinksPerMinute.toInt()}"
    private fun timeString(): String {
        val mm = data.sessionSeconds / 60
        val ss = data.sessionSeconds % 60
        return "%02d:%02d".format(mm, ss)
    }
    private fun remainingString(): String {
        val rem = (data.calibrationRemainingMs ?: 0L) / 1000L
        return "%02d:%02d".format(rem / 60, rem % 60)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rowH   = valuePaint.fontSpacing + labelPaint.fontSpacing + padV * 2
        val minHrW   = maxOf(valuePaint.measureText("200"), labelPaint.measureText("FC")) + padH * 2
        val minBlinkW = maxOf(valuePaint.measureText("99"), labelPaint.measureText("parp/min")) + padH * 2
        val minTimeW  = maxOf(valuePaint.measureText("00:00"), labelPaint.measureText(
            if (data.calibrationRemainingMs != null) "restante" else "sesión")) + padH * 2
        colHrW    = minHrW
        colBlinkW = minBlinkW
        colTimeW  = minTimeW
        val totalW = (colHrW + colBlinkW + colTimeW + colGap * 2).toInt()
        setMeasuredDimension(totalW, rowH.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val isCalibration = data.calibrationRemainingMs != null
        val h = height.toFloat()
        val w = width.toFloat()

        bgRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius,
            if (isCalibration) calBorderPaint else borderPaint)

        val vCenter = h / 2f
        val valueY  = vCenter + valuePaint.textSize * 0.35f
        val labelY  = valueY + labelPaint.fontSpacing * 0.82f

        // Column 1: Heart rate
        val hrX = padH
        val hrVal = hrString()
        val hrPaint = if (data.currentBpm > 0) accentPaint else valuePaint
        canvas.drawText(hrVal, hrX, valueY - labelPaint.fontSpacing * 0.4f, hrPaint)
        canvas.drawText("♥  FC", hrX, labelY - labelPaint.fontSpacing * 0.35f, labelPaint)

        // Divider
        val div1X = colHrW + colGap * 0.5f
        canvas.drawRect(div1X, padV, div1X + 1f, h - padV, dividerPaint)

        // Column 2: Blink rate
        val blinkX = colHrW + colGap
        val blinkPaint = if (isCalibration) calPaint else accentPaint
        val blinkLabel = if (isCalibration) "● BASELINE" else "◉  parp/min"
        canvas.drawText(if (isCalibration) "···" else blinkString(),
            blinkX, valueY - labelPaint.fontSpacing * 0.4f, blinkPaint)
        canvas.drawText(blinkLabel, blinkX, labelY - labelPaint.fontSpacing * 0.35f, labelPaint)

        // Divider
        val div2X = colHrW + colBlinkW + colGap * 1.5f
        canvas.drawRect(div2X, padV, div2X + 1f, h - padV, dividerPaint)

        // Column 3: Time
        val timeX = colHrW + colBlinkW + colGap * 2f
        val timeVal  = if (isCalibration) remainingString() else timeString()
        val timePaint = if (isCalibration) calPaint else valuePaint
        val timeLabel = if (isCalibration) "◷  restante" else "◷  sesión"

        if (!data.faceDetected && !isCalibration) {
            canvas.drawText(timeString(), timeX, valueY - labelPaint.fontSpacing * 0.4f, warnPaint)
            canvas.drawText("sin cara", timeX, labelY - labelPaint.fontSpacing * 0.35f, warnPaint)
        } else {
            canvas.drawText(timeVal, timeX, valueY - labelPaint.fontSpacing * 0.4f, timePaint)
            canvas.drawText(timeLabel, timeX, labelY - labelPaint.fontSpacing * 0.35f, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
