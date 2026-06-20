package com.dopamincheker.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dopamincheker.SubjectPrefs
import com.dopamincheker.detection.AlertState
import com.dopamincheker.detection.AlertThresholds
import com.dopamincheker.detection.BlinkDetector
import com.dopamincheker.detection.BlinkStats
import com.dopamincheker.detection.FaceLandmarkerHelper
import com.dopamincheker.detection.FrameRecord
import com.dopamincheker.detection.HrRecord
import com.dopamincheker.detection.SessionExporter
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlinkViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val blinksPerMinute: Float = 0f,
        val totalBlinks: Int = 0,
        val sessionSeconds: Long = 0L,
        val currentEar: Float = 0f,
        val faceDetected: Boolean = false,
        val currentBpm: Int = 0,
        val calibrationRemainingMs: Long? = null,
        val alertState: AlertState = AlertState.NORMAL
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val blinkDetector = BlinkDetector()
    private val blinkStats    = BlinkStats()

    private val faceLandmarkerHelper = FaceLandmarkerHelper(
        context  = application,
        onResult = ::onFaceLandmarksResult
    )

    private val sessionStartMs      = SystemClock.uptimeMillis()
    private val sessionStartEpochMs = System.currentTimeMillis()
    private var timerJob: Job? = null

    // Modo demostración para capturas (datos simulados, sin pulsera ni cámara).
    private var demoActive = false
    private var demoJob: Job? = null

    private val hrRecords = mutableListOf<HrRecord>()

    private var calibrationTotalMs: Long = 0
    private var calibrationActive = false

    // Histograma del EAR durante la calibración, para derivar el umbral individual del sujeto.
    // Bins de 0.005 en el rango [0, 0.5) > 100 bins.
    private val calibEarHist = IntArray(CALIB_HIST_BINS)
    private var calibSamples = 0

    private var frameCount = 0

    private var pausedAtMs: Long = 0L
    private var totalPausedMs: Long = 0L

    // Contadores de sostenimiento para alertas (se reinician si la señal vuelve a normal)
    private var hrAlertCount   = 0
    private var blinkAlertCount = 0

    init {
        faceLandmarkerHelper.setup()

        // Aplicar el umbral EAR individual del sujeto activo (si ya se calibró antes).
        val subject = SubjectPrefs.getAlias(application)
        blinkDetector.earThreshold = SubjectPrefs.getEarThreshold(application, subject)

        blinkDetector.onBlinkDetected = { record ->
            viewModelScope.launch {
                blinkStats.recordBlink(record)
                _uiState.update { state ->
                    state.copy(
                        blinksPerMinute = blinkStats.getBlinksPerMinute(),
                        totalBlinks     = blinkStats.totalBlinks
                    )
                }
            }
        }

        timerJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val elapsed = effectiveElapsedMs()
                val sessionSec = elapsed / 1000
                val remaining = if (calibrationActive)
                    maxOf(0L, calibrationTotalMs - elapsed) else null
                val alert = computeAlertState(sessionSec, remaining != null)
                _uiState.update { it.copy(
                    sessionSeconds         = sessionSec,
                    calibrationRemainingMs = remaining,
                    alertState             = alert
                ) }
            }
        }
    }

    fun enableCalibrationCountdown(totalMs: Long) {
        calibrationTotalMs = totalMs
        calibrationActive  = true
    }

    fun pauseSession() {
        pausedAtMs = SystemClock.uptimeMillis()
    }

    fun resumeSession() {
        if (pausedAtMs > 0L) {
            totalPausedMs += SystemClock.uptimeMillis() - pausedAtMs
            pausedAtMs = 0L
        }
    }

    private fun effectiveElapsedMs() =
        SystemClock.uptimeMillis() - sessionStartMs - totalPausedMs

    fun addHrRecord(bpm: Int) {
        if (demoActive) return
        hrRecords.add(HrRecord(epochMs = System.currentTimeMillis(), bpm = bpm))
        viewModelScope.launch { _uiState.update { it.copy(currentBpm = bpm) } }
    }

    fun processImageProxy(imageProxy: ImageProxy) {
        faceLandmarkerHelper.detectAsync(imageProxy)
    }

    /**
     * Activa el modo demostración: alimenta el overlay con valores fisiológicos plausibles
     * (FC ~72-76 bpm, ~16-19 parpadeos/min, cara detectada) sin necesitar pulsera ni cámara.
     * El cronómetro de sesión sigue corriendo de verdad. Pensado para capturas de la memoria.
     */
    fun startDemoMode() {
        if (demoActive) return
        demoActive = true
        demoJob = viewModelScope.launch {
            val bpmSeq   = intArrayOf(72, 73, 74, 75, 76, 75, 74, 73, 72, 74)
            val blinkSeq = floatArrayOf(16f, 17f, 18f, 17f, 19f, 18f, 16f, 17f, 18f, 17f)
            var total = 12
            var i = 0
            while (true) {
                _uiState.update { it.copy(
                    currentBpm      = bpmSeq[i % bpmSeq.size],
                    blinksPerMinute = blinkSeq[i % blinkSeq.size],
                    totalBlinks     = total,
                    faceDetected    = true,
                    alertState      = AlertState.NORMAL
                ) }
                if (i % 3 == 0) total++
                i++
                delay(1_200)
            }
        }
    }

    private fun onFaceLandmarksResult(result: FaceLandmarkerResult) {
        if (demoActive) return
        val hasFace     = result.faceLandmarks().isNotEmpty()
        val localFrame  = frameCount++
        val frameTimeMs = SystemClock.uptimeMillis() - sessionStartMs

        if (!hasFace) {
            viewModelScope.launch {
                blinkStats.recordFrame(
                    FrameRecord(localFrame, frameTimeMs, false, null, null, null, false, false)
                )
                _uiState.update { it.copy(faceDetected = false) }
            }
            return
        }

        val landmarks = result.faceLandmarks()[0]
        val ear = blinkDetector.processLandmarks(landmarks, localFrame, frameTimeMs)

        // Durante la calibración, acumular el EAR en el histograma para derivar el umbral del sujeto.
        if (calibrationActive && ear.avg > 0f) {
            val bin = (ear.avg / CALIB_BIN_WIDTH).toInt()
            if (bin in 0 until CALIB_HIST_BINS) {
                calibEarHist[bin]++
                calibSamples++
            }
        }

        viewModelScope.launch {
            blinkStats.recordFrame(
                FrameRecord(
                    frame        = localFrame,
                    timeMs       = frameTimeMs,
                    faceDetected = true,
                    earLeft      = ear.left,
                    earRight     = ear.right,
                    earAvg       = ear.avg,
                    eyeClosed    = ear.eyeClosed,
                    inBlink      = ear.inBlink
                )
            )
            _uiState.update { it.copy(currentEar = ear.avg, faceDetected = true) }
        }
    }

    fun saveSessionAsync(platform: String = "youtube", isBaseline: Boolean = false) {
        viewModelScope.launch { try { saveSession(platform, isBaseline) } catch (_: Exception) {} }
    }

    suspend fun saveSession(platform: String = "youtube", isBaseline: Boolean = false): String =
        withContext(Dispatchers.IO) {
            // No persistir sesiones de demostración (datos simulados, solo para capturas).
            if (demoActive) return@withContext ""
            // Descartar sesiones demasiado cortas (entradas accidentales): no generan datos.
            if (effectiveElapsedMs() < MIN_SESSION_MS) return@withContext ""

            val subject = SubjectPrefs.getAlias(getApplication())
            // Si es la calibración baseline, derivar y persistir el umbral EAR individual del sujeto
            // antes de exportar, para que las sesiones siguientes lo usen.
            if (isBaseline) computeAndStoreCalibratedThreshold(subject)
            val path = SessionExporter(getApplication()).exportSession(
                blinks              = blinkStats.allBlinks.toList(),
                frames              = blinkStats.allFrames.toList(),
                hrRecords           = hrRecords.toList(),
                sessionDurationMs   = effectiveElapsedMs(),
                sessionStartEpochMs = sessionStartEpochMs,
                platform            = platform,
                subject             = subject,
                isBaseline          = isBaseline
            )
            if (isBaseline) SubjectPrefs.setBaselineDone(getApplication(), subject)
            path
        }

    private fun computeAlertState(sessionSec: Long, isCalibration: Boolean): AlertState {
        val state = _uiState.value

        // Sin alertas durante calibración
        if (isCalibration) {
            hrAlertCount    = 0
            blinkAlertCount = 0
            return AlertState.NORMAL
        }

        // FC
        if (sessionSec >= AlertThresholds.MIN_SESSION_S_FOR_HR_ALERT && state.currentBpm > 0) {
            if (state.currentBpm > AlertThresholds.HR_HIGH_BPM) hrAlertCount++
            else hrAlertCount = 0
        }

        // Parpadeo
        // Solo evaluar cuando hay cara detectada y la ventana de 60 s está llena
        if (sessionSec >= AlertThresholds.MIN_SESSION_S_FOR_BLINK_ALERT && state.faceDetected) {
            if (state.blinksPerMinute < AlertThresholds.BLINK_LOW_PER_MIN) blinkAlertCount++
            else blinkAlertCount = 0
        }

        val hrAlert    = hrAlertCount    >= AlertThresholds.HR_SUSTAIN_WINDOW_S
        val blinkAlert = blinkAlertCount >= AlertThresholds.BLINK_SUSTAIN_WINDOW_S

        return when {
            hrAlert && blinkAlert -> AlertState.ALERT_BOTH
            hrAlert               -> AlertState.ALERT_HR
            blinkAlert            -> AlertState.ALERT_BLINK
            else                  -> AlertState.NORMAL
        }
    }

    /**
     * Deriva el umbral EAR individual del sujeto a partir del histograma acumulado durante
     * la calibración y lo persiste para las sesiones siguientes.
     *
     * Mecanismo: el EAR absoluto depende de la fisiología ocular y de la geometría cámara-cara,
     * pero la apertura basal del ojo (estado dominante) es estable y caracteriza al sujeto. Se
     * toma esa apertura como la MEDIANA del EAR durante los ~20 min de calibración (los parpadeos
     * son <10 % de los frames, así que la mediana cae en el ojo abierto) y se fija el umbral en
     * una fracción de ella: un parpadeo es una caída por debajo del OPEN_FRACTION de la apertura
     * habitual. Así el umbral se adapta a cada persona en lugar de usar un valor fijo.
     */
    private fun computeAndStoreCalibratedThreshold(subject: String) {
        if (calibSamples < CALIB_MIN_SAMPLES) return   // datos insuficientes: conservar umbral previo

        // Mediana del EAR a partir del histograma > apertura ocular basal del sujeto.
        val half = calibSamples / 2
        var acc = 0
        var medianBin = 0
        for (b in 0 until CALIB_HIST_BINS) {
            acc += calibEarHist[b]
            if (acc >= half) { medianBin = b; break }
        }
        val earOpen = (medianBin + 0.5f) * CALIB_BIN_WIDTH

        val threshold = (earOpen * OPEN_FRACTION)
            .coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

        blinkDetector.earThreshold = threshold
        SubjectPrefs.setEarThreshold(getApplication(), subject, threshold)
    }

    override fun onCleared() {
        timerJob?.cancel()
        demoJob?.cancel()
        faceLandmarkerHelper.close()
        super.onCleared()
    }

    companion object {
        // Sesiones de menos de 1 min se descartan (entradas accidentales).
        private const val MIN_SESSION_MS = 60_000L

        // Calibración del umbral EAR
        private const val CALIB_HIST_BINS = 100
        private const val CALIB_BIN_WIDTH = 0.005f      // bins de 0.005 en [0, 0.5)
        private const val CALIB_MIN_SAMPLES = 1_000      // ~33 s a 30 fps: mínimo para una mediana fiable
        private const val OPEN_FRACTION = 0.80f          // parpadeo = caída por debajo del 80 % de la apertura
        private const val MIN_THRESHOLD = 0.10f          // límites de seguridad
        private const val MAX_THRESHOLD = 0.25f
    }
}
