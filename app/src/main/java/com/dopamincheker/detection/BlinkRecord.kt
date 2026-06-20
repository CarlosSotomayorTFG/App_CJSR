package com.dopamincheker.detection

data class BlinkRecord(
    val id: Int,
    val frameStart: Int,
    val frameEnd: Int,
    val timeStartMs: Long,
    val timeEndMs: Long,
    val durationMs: Long,
    val nFramesClosed: Int,
    val earMinLeft: Float,
    val earMinRight: Float,
    val earMinAvg: Float,
    val earMeanLeft: Float,
    val earMeanRight: Float
)
