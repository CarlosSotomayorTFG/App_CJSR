package com.dopamincheker.detection

enum class AlertState {
    NORMAL,
    ALERT_HR,       // FC > AlertThresholds.HR_HIGH_BPM sostenida HR_SUSTAIN_WINDOW_S segundos
    ALERT_BLINK,    // parpadeo < AlertThresholds.BLINK_LOW_PER_MIN sostenido BLINK_SUSTAIN_WINDOW_S segundos
    ALERT_BOTH
}
