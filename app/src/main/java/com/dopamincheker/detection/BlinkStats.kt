package com.dopamincheker.detection

class BlinkStats {

    // Ventana deslizante para calcular BPM en tiempo real
    private val blinkTimestamps = ArrayDeque<Long>()

    // Registro completo de la sesión
    val allBlinks = mutableListOf<BlinkRecord>()
    val allFrames = mutableListOf<FrameRecord>()

    var totalBlinks = 0
        private set

    fun recordBlink(record: BlinkRecord) {
        val now = System.currentTimeMillis()
        blinkTimestamps.addLast(now)
        totalBlinks++
        allBlinks.add(record)
        pruneOldEntries(now)
    }

    fun recordFrame(frame: FrameRecord) {
        allFrames.add(frame)
    }

    fun getBlinksPerMinute(): Float {
        pruneOldEntries(System.currentTimeMillis())
        return blinkTimestamps.size.toFloat()
    }

    private fun pruneOldEntries(now: Long) {
        val cutoff = now - 60_000L
        while (blinkTimestamps.isNotEmpty() && blinkTimestamps.first() < cutoff) {
            blinkTimestamps.removeFirst()
        }
    }
}
