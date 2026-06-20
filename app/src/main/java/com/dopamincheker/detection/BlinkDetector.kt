package com.dopamincheker.detection

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt

class BlinkDetector {

    companion object {
        private val LEFT_EYE_IDX  = intArrayOf(362, 385, 387, 263, 373, 380)
        private val RIGHT_EYE_IDX = intArrayOf(33,  160, 158, 133, 153, 144)

        const val DEFAULT_EAR_THRESHOLD = 0.15f
        const val CONSEC_FRAMES = 2
    }

    /** Umbral EAR por debajo del cual se considera el ojo cerrado. Variable: se calibra por sujeto
     *  durante la baseline (ver BlinkViewModel.computeAndStoreCalibratedThreshold). */
    var earThreshold: Float = DEFAULT_EAR_THRESHOLD

    private var earConsecBelow = 0
    private var blinkInProgress = false
    private var blinkCount = 0
    private var blinkStartFrame = 0
    private var blinkStartTimeMs = 0L
    private val earLeftDuringBlink  = mutableListOf<Float>()
    private val earRightDuringBlink = mutableListOf<Float>()

    var onBlinkDetected: ((BlinkRecord) -> Unit)? = null

    fun processLandmarks(
        landmarks: List<NormalizedLandmark>,
        frameIdx: Int,
        frameTimeMs: Long
    ): EarResult {
        val leftEAR  = computeEAR(landmarks, LEFT_EYE_IDX)
        val rightEAR = computeEAR(landmarks, RIGHT_EYE_IDX)
        val avgEAR   = (leftEAR + rightEAR) / 2f
        updateStateMachine(leftEAR, rightEAR, avgEAR, frameIdx, frameTimeMs)
        return EarResult(
            left      = leftEAR,
            right     = rightEAR,
            avg       = avgEAR,
            eyeClosed = avgEAR < earThreshold,
            inBlink   = blinkInProgress
        )
    }

    private fun computeEAR(landmarks: List<NormalizedLandmark>, idx: IntArray): Float {
        val p1 = landmarks[idx[0]]; val p2 = landmarks[idx[1]]
        val p3 = landmarks[idx[2]]; val p4 = landmarks[idx[3]]
        val p5 = landmarks[idx[4]]; val p6 = landmarks[idx[5]]
        val num = dist(p2, p6) + dist(p3, p5)
        val den = 2f * dist(p1, p4)
        return if (den > 0f) num / den else 0f
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x() - b.x(); val dy = a.y() - b.y()
        return sqrt(dx * dx + dy * dy)
    }

    private fun updateStateMachine(
        left: Float, right: Float, avg: Float,
        frameIdx: Int, frameTimeMs: Long
    ) {
        if (avg < earThreshold) {
            earConsecBelow++
            if (earConsecBelow >= CONSEC_FRAMES && !blinkInProgress) {
                blinkInProgress  = true
                blinkStartFrame  = frameIdx - earConsecBelow + 1
                blinkStartTimeMs = frameTimeMs
                earLeftDuringBlink.clear()
                earRightDuringBlink.clear()
            }
            if (blinkInProgress) {
                earLeftDuringBlink.add(left)
                earRightDuringBlink.add(right)
            }
        } else {
            if (blinkInProgress) {
                val record = BlinkRecord(
                    id            = ++blinkCount,
                    frameStart    = blinkStartFrame,
                    frameEnd      = frameIdx - 1,
                    timeStartMs   = blinkStartTimeMs,
                    timeEndMs     = frameTimeMs,
                    durationMs    = frameTimeMs - blinkStartTimeMs,
                    nFramesClosed = earLeftDuringBlink.size,
                    earMinLeft    = earLeftDuringBlink.minOrNull() ?: left,
                    earMinRight   = earRightDuringBlink.minOrNull() ?: right,
                    earMinAvg     = earLeftDuringBlink.zip(earRightDuringBlink)
                        .minOfOrNull { (l, r) -> (l + r) / 2f } ?: avg,
                    earMeanLeft   = earLeftDuringBlink.average().toFloat(),
                    earMeanRight  = earRightDuringBlink.average().toFloat()
                )
                onBlinkDetected?.invoke(record)
            }
            earConsecBelow  = 0
            blinkInProgress = false
            earLeftDuringBlink.clear()
            earRightDuringBlink.clear()
        }
    }

    data class EarResult(
        val left: Float,
        val right: Float,
        val avg: Float,
        val eyeClosed: Boolean,
        val inBlink: Boolean
    )
}
