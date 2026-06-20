package com.dopamincheker.detection

data class FrameRecord(
    val frame: Int,
    val timeMs: Long,
    val faceDetected: Boolean,
    val earLeft: Float?,
    val earRight: Float?,
    val earAvg: Float?,
    val eyeClosed: Boolean,
    val inBlink: Boolean
)
