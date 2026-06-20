package com.dopamincheker.detection

/**
 * Umbrales fisiológicos para el sistema de alerta en tiempo real.
 *
 * FRECUENCIA CARDÍACA
 *   HR_HIGH_BPM = 100 bpm
 *     Definición de taquicardia en reposo según la American Heart Association.
 *     En una tarea sedentaria como el uso de redes sociales, superar 100 bpm de
 *     forma sostenida indica activación simpática significativa.
 *   HR_SUSTAIN_WINDOW_S = 30 s
 *     Ventana mínima de sostenimiento para evitar falsos positivos por
 *     artefactos de movimiento puntual de la pulsera.
 *
 * TASA DE PARPADEO
 *   BLINK_LOW_PER_MIN = 8 parpadeos/min
 *     Umbral inferior de la zona de "computer vision syndrome" descrita por
 *     Sheppard & Wolffsohn (BMJ Open Ophthalmology, 2018). Tasas por debajo de
 *     este valor sostenidas en el tiempo se asocian a engagement extremo,
 *     disociación o fatiga visual incipiente (Cori et al., Sleep Med Rev, 2019).
 *     La tasa basal normal es 15-20 /min; 8 /min supone una reducción >50 %.
 *   BLINK_SUSTAIN_WINDOW_S = 60 s
 *     La reducción de parpadeo durante fijaciones cortas es normal; 60 s
 *     sostenidos distinguen fijación momentánea de alteración mantenida.
 *
 * GUARDAS TEMPORALES
 *   MIN_SESSION_S_FOR_HR_ALERT = 30 s
 *   MIN_SESSION_S_FOR_BLINK_ALERT = 90 s (ventana 60 s + margen 30 s)
 *     Evitan alertas prematuras mientras las métricas no tienen datos suficientes.
 */
object AlertThresholds {

    // Frecuencia cardíaca
    const val HR_HIGH_BPM           = 100
    const val HR_SUSTAIN_WINDOW_S   = 30

    // Tasa de parpadeo
    const val BLINK_LOW_PER_MIN         = 8f
    const val BLINK_SUSTAIN_WINDOW_S    = 60

    // Guardas temporales
    const val MIN_SESSION_S_FOR_HR_ALERT    = 30L
    const val MIN_SESSION_S_FOR_BLINK_ALERT = 90L
}
