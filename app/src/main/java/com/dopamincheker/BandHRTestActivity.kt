package com.dopamincheker

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dopamincheker.band.BandCredentials
import com.dopamincheker.band.HuamiBleManager
import com.dopamincheker.databinding.ActivityBandHrTestBinding

class BandHRTestActivity : AppCompatActivity() {

    companion object {
        private val MAC = BandCredentials.MAC
        private val KEY = BandCredentials.KEY
        private const val REQ_BLE = 1001
    }

    private lateinit var binding: ActivityBandHrTestBinding
    private var manager: HuamiBleManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBandHrTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnConnect.setOnClickListener { checkPermissionsAndConnect() }
        // Pulsación larga sobre "Conectar" > modo demostración (para capturas de la memoria).
        binding.btnConnect.setOnLongClickListener { startDemo(); true }
        binding.btnDisconnect.setOnClickListener {
            stopDemo()
            manager?.disconnect()
            manager = null
        }
    }

    // Modo demostración: simula una conexión y lectura de FC sin pulsera real
    private val demoHandler = Handler(Looper.getMainLooper())
    private var demoActive = false
    private var demoIdx = 0
    private val demoBpm = intArrayOf(72, 73, 74, 73, 72, 71, 73, 75, 76, 75, 74, 72)

    private fun startDemo() {
        stopDemo()
        manager?.disconnect(); manager = null
        demoActive = true
        binding.tvLog.text = ""
        val steps = listOf(
            "[1] Conectado (status=0). Descubriendo servicios...",
            "[2] Servicios OK. Negociando MTU...",
            "[3] MTU=247 (pedido 247, SUCCESS). Activando notificaciones...",
            "[4] Iniciando autenticación ECDH...",
            "[5] Enviando clave pública a la pulsera. Esperando respuesta...",
            "[6] Respuesta recibida. Calculando secreto compartido...",
            "[7] Autenticación correcta. Iniciando HR...",
            "Comando HR enviado. Esperando datos..."
        )
        steps.forEachIndexed { i, msg -> demoHandler.postDelayed({ if (demoActive) log(msg) }, 220L * (i + 1)) }
        demoHandler.postDelayed({ demoHrTick() }, 220L * (steps.size + 2))
    }

    private fun demoHrTick() {
        if (!demoActive) return
        val bpm = demoBpm[demoIdx % demoBpm.size]; demoIdx++
        binding.tvHr.text = "$bpm bpm"
        log("< CHR_HR: FC recibida > $bpm bpm")
        demoHandler.postDelayed({ demoHrTick() }, 1500L)
    }

    private fun stopDemo() {
        demoActive = false
        demoHandler.removeCallbacksAndMessages(null)
    }

    private fun checkPermissionsAndConnect() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))    needed += Manifest.permission.BLUETOOTH_SCAN
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (!hasPermission(Manifest.permission.BLUETOOTH))         needed += Manifest.permission.BLUETOOTH
            if (!hasPermission(Manifest.permission.BLUETOOTH_ADMIN))   needed += Manifest.permission.BLUETOOTH_ADMIN
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (needed.isEmpty()) {
            startConnection()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_BLE)
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(reqCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(reqCode, perms, results)
        if (reqCode != REQ_BLE) return
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
            startConnection()
        } else {
            val permanentlyDenied = perms.filterIndexed { i, p ->
                results[i] != PackageManager.PERMISSION_GRANTED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(this, p)
            }
            if (permanentlyDenied.isNotEmpty()) {
                log("Permisos denegados permanentemente. Ve a Ajustes del sistema > Aplicaciones > DopaminCheker > Permisos y actívalos manualmente.")
            } else {
                log("Permisos BLE denegados. Pulsa Conectar e acepta los permisos.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startConnection() {
        manager?.disconnect()
        manager = HuamiBleManager(
            context   = this,
            secretKey = KEY,
            onHrValue = { hr ->
                runOnUiThread { binding.tvHr.text = "$hr bpm" }
            },
            onStatus  = { msg -> log(msg) }
        )
        manager?.connect(MAC)
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
        manager?.disconnect()
    }
}
