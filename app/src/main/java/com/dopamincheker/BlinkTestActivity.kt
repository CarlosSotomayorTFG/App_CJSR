package com.dopamincheker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dopamincheker.camera.CameraManager
import com.dopamincheker.databinding.ActivityBlinkTestBinding
import com.dopamincheker.detection.BlinkDetector
import com.dopamincheker.detection.FaceLandmarkerHelper
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.ArrayDeque
import java.util.Locale

class BlinkTestActivity : AppCompatActivity() {

    companion object {
        private const val REQ_CAMERA = 2001
    }

    private lateinit var binding: ActivityBlinkTestBinding

    private var cameraManager: CameraManager? = null
    private var faceLandmarkerHelper: FaceLandmarkerHelper? = null
    private val blinkDetector = BlinkDetector()

    private var totalBlinks = 0
    private val blinkTimestamps = ArrayDeque<Long>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateBlinksPerMinute()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlinkTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        blinkDetector.onBlinkDetected = { record ->
            totalBlinks++
            val now = System.currentTimeMillis()
            blinkTimestamps.addLast(now)
            pruneTimestamps(now)
            runOnUiThread {
                binding.tvBlinkCount.text = totalBlinks.toString()
                log("Parpadeo #$totalBlinks - duración ${record.durationMs} ms  " +
                    "EAR mín ${String.format(Locale.US, "%.3f", record.earMinAvg)}")
            }
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnStartBlink.setOnClickListener { checkPermissionAndStart() }
        // Pulsación larga sobre "Iniciar cámara" > modo demostración (para capturas de la memoria).
        binding.btnStartBlink.setOnLongClickListener { startDemo(); true }
        binding.btnStopBlink.setOnClickListener { stopDemo(); stopCamera() }
    }

    // Modo demostración: simula la detección de parpadeo sin cámara
    private var demoActive = false
    private var demoFrame = 0
    private val demoRunnable = object : Runnable {
        override fun run() {
            if (!demoActive) return
            demoFrame++
            // Un "parpadeo" cada ~22 frames: el EAR cae por debajo del umbral durante 2 frames.
            val phase = demoFrame % 22
            val blinking = phase == 0 || phase == 1
            val ear = if (blinking) 0.072f + phase * 0.012f else 0.205f + (demoFrame % 3) * 0.004f
            binding.tvEar.text = String.format(Locale.US, "%.3f", ear)
            binding.tvEar.setTextColor(if (ear < 0.163f) 0xFFE05252.toInt() else 0xFF58D68D.toInt())
            binding.tvEyeState.text = if (blinking) "● PARPADEO  umbral: 0.163" else "umbral: 0.163"
            if (phase == 1) {
                totalBlinks++
                binding.tvBlinkCount.text = totalBlinks.toString()
                log("Parpadeo #$totalBlinks - duración 233 ms  EAR mín 0.072")
            }
            mainHandler.postDelayed(this, 55L)
        }
    }

    private fun startDemo() {
        stopCamera()
        demoActive = true
        demoFrame = 0
        totalBlinks = 0
        binding.tvBlinkCount.text = "0"
        binding.tvBpm.text = "17"
        binding.tvFaceStatus.text = "◉  Cara detectada"
        binding.tvFaceStatus.setTextColor(0xFF58D68D.toInt())
        binding.tvLog.text = ""
        mainHandler.post(demoRunnable)
    }

    private fun stopDemo() {
        demoActive = false
        mainHandler.removeCallbacks(demoRunnable)
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(reqCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(reqCode, perms, results)
        if (reqCode == REQ_CAMERA && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            log("Permiso de cámara denegado")
        }
    }

    private fun startCamera() {
        stopDemo()
        stopCamera()
        totalBlinks = 0
        blinkTimestamps.clear()
        binding.tvBlinkCount.text = "0"
        binding.tvBpm.text = "--"

        // Usar el umbral EAR calibrado del sujeto activo (si existe) en el diagnóstico.
        blinkDetector.earThreshold = SubjectPrefs.getEarThreshold(this, SubjectPrefs.getAlias(this))

        val helper = FaceLandmarkerHelper(
            context  = this,
            onResult = ::onLandmarks,
            onError  = { e -> log("Error MediaPipe: ${e.message}") }
        )
        helper.setup()
        faceLandmarkerHelper = helper

        cameraManager = CameraManager(this, this) { imageProxy ->
            helper.detectAsync(imageProxy)
        }
        cameraManager?.startCamera()
        mainHandler.post(uiUpdateRunnable)
        log("Cámara iniciada")
    }

    private fun onLandmarks(result: FaceLandmarkerResult) {
        val hasFace = result.faceLandmarks().isNotEmpty()
        if (!hasFace) {
            runOnUiThread {
                binding.tvFaceStatus.text = "◉  Sin cara detectada"
                binding.tvFaceStatus.setTextColor(0xFF888888.toInt())
                binding.tvEar.text = "--"
                binding.tvEyeState.text = "umbral: ${String.format(Locale.US, "%.3f", blinkDetector.earThreshold)}"
            }
            return
        }

        val landmarks = result.faceLandmarks()[0]
        val ear = blinkDetector.processLandmarks(landmarks, 0, System.currentTimeMillis())

        runOnUiThread {
            binding.tvFaceStatus.text = "◉  Cara detectada"
            binding.tvFaceStatus.setTextColor(0xFF58D68D.toInt())
            binding.tvEar.text = String.format(Locale.US, "%.3f", ear.avg)
            binding.tvEar.setTextColor(
                if (ear.eyeClosed) 0xFFE05252.toInt() else 0xFF58D68D.toInt()
            )
            binding.tvEyeState.text = if (ear.inBlink) "● PARPADEO  umbral: ${String.format(Locale.US, "%.3f", blinkDetector.earThreshold)}"
                                      else "umbral: ${String.format(Locale.US, "%.3f", blinkDetector.earThreshold)}"
        }
    }

    private fun updateBlinksPerMinute() {
        val now = System.currentTimeMillis()
        pruneTimestamps(now)
        binding.tvBpm.text = blinkTimestamps.size.toString()
    }

    private fun pruneTimestamps(now: Long) {
        val cutoff = now - 60_000L
        while (blinkTimestamps.isNotEmpty() && blinkTimestamps.first() < cutoff) {
            blinkTimestamps.removeFirst()
        }
    }

    private fun stopCamera() {
        mainHandler.removeCallbacks(uiUpdateRunnable)
        cameraManager?.shutdown()
        cameraManager = null
        faceLandmarkerHelper?.close()
        faceLandmarkerHelper = null
        log("Cámara detenida")
    }

    private fun log(msg: String) {
        runOnUiThread {
            binding.tvLog.append("$msg\n")
            binding.scrollLog.post { binding.scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDemo()
        stopCamera()
    }
}
