package com.dopamincheker.band

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@SuppressLint("MissingPermission")
class HuamiBleManager(
    private val context: Context,
    private val secretKey: ByteArray,           // 16-byte auth token from huami-token
    private val onHrValue: (Int) -> Unit,
    private val onStatus: (String) -> Unit
) {

    companion object {
        val SVC_MAIN       = UUID.fromString("0000FEE0-0000-1000-8000-00805f9b34fb")
        val CHR_CHUNKED_W  = UUID.fromString("00000016-0000-3512-2118-0009af100700")
        val CHR_CHUNKED_R  = UUID.fromString("00000017-0000-3512-2118-0009af100700")
        val CHR_HR         = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
        val DSC_CCCD       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val EP_AUTH:       Short = 0x0082.toShort()
        const val EP_HR:         Short = 0x001d.toShort()
        const val EP_CONNECTION: Short = 0x0015.toShort()

        private const val HR_CONTINUE_INTERVAL_MS = 5_000L
    }

    private var gatt: BluetoothGatt? = null
    private var bondReceiver: BroadcastReceiver? = null
    private var chrChunkedW: BluetoothGattCharacteristic? = null
    private var chrChunkedR: BluetoothGattCharacteristic? = null
    private var chrHr: BluetoothGattCharacteristic? = null
    private var mtu = 23

    private var privateEC = ByteArray(24)
    private var publicEC  = ByteArray(48)

    private val writeQueue = ArrayDeque<() -> Unit>()
    private var writeBusy  = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var descriptorWriteCallback: (() -> Unit)? = null

    private val hrContinueRunnable: Runnable = object : Runnable {
        override fun run() {
            writeChunked(EP_HR, byteArrayOf(0x04, 0x02))
            mainHandler.postDelayed(this, HR_CONTINUE_INTERVAL_MS)
        }
    }
    private var hrContinueActive = false

    private fun enqueue(op: () -> Unit) {
        mainHandler.post {
            writeQueue.add(op)
            if (!writeBusy) drain()
        }
    }

    private fun drain() {
        if (writeQueue.isEmpty()) { writeBusy = false; return }
        writeBusy = true
        writeQueue.poll()?.invoke()
    }

    private fun writeComplete() = mainHandler.post { drain() }

    private var writeHandle = 0

    private fun encodeChunked(endpoint: Short, payload: ByteArray): List<ByteArray> {
        writeHandle++
        val chunks = mutableListOf<ByteArray>()
        val maxFirst = mtu - 3 - 11
        val maxCont  = mtu - 3 - 5

        var offset = 0
        var remaining = payload.size
        var count = 0

        while (remaining > 0) {
            val maxData = if (count == 0) maxFirst else maxCont
            val take    = minOf(remaining, maxData)
            val hdrSize = if (count == 0) 11 else 5
            val chunk   = ByteArray(hdrSize + take)

            var flags = 0
            if (count == 0)          flags = flags or 0x01   // first
            if (remaining <= maxData) flags = flags or 0x06   // last

            chunk[0] = 0x03
            chunk[1] = flags.toByte()
            chunk[2] = 0x00                    // extended flag byte
            chunk[3] = writeHandle.toByte()
            chunk[4] = count.toByte()

            if (count == 0) {
                chunk[5]  = (payload.size        and 0xFF).toByte()
                chunk[6]  = ((payload.size shr 8) and 0xFF).toByte()
                chunk[7]  = 0x00
                chunk[8]  = 0x00
                chunk[9]  = (endpoint.toInt()        and 0xFF).toByte()
                chunk[10] = ((endpoint.toInt() shr 8) and 0xFF).toByte()
            }

            System.arraycopy(payload, offset, chunk, hdrSize, take)
            chunks.add(chunk)
            offset    += take
            remaining -= take
            count++
        }
        return chunks
    }

    private var decHandle: Byte? = null
    private var decType:   Short = 0
    private var decLen:    Int   = 0
    private var decBuf:    ByteBuffer? = null

    // Saved from last completed message - used for ACK
    private var lastDecHandle:   Byte = 0
    private var lastDecCount:    Byte = 0
    private var lastDecNeedsAck: Boolean = false

    private fun decodeChunked(data: ByteArray): Pair<Short, ByteArray>? {
        if (data.isEmpty() || data[0] != 0x03.toByte()) return null
        Log.d("HuamiBLE", "< CHR_CHUNKED_R: ${data.toHex()}")
        var i = 1
        val flags      = data[i++].toInt() and 0xFF
        val firstChunk = (flags and 0x01) != 0
        val lastChunk  = (flags and 0x02) != 0    // bit 1: last chunk
        val needsAck   = (flags and 0x04) != 0    // bit 2: ACK required

        i++                           // skip extended-flag byte (0x00)
        val handle = data[i++]
        val count  = data[i++]

        if (firstChunk) {
            decLen  = (data[i].toInt() and 0xFF) or
                    ((data[i+1].toInt() and 0xFF) shl 8) or
                    ((data[i+2].toInt() and 0xFF) shl 16) or
                    ((data[i+3].toInt() and 0xFF) shl 24)
            i += 4
            decType = ((data[i].toInt() and 0xFF) or
                    ((data[i+1].toInt() and 0xFF) shl 8)).toShort()
            i += 2
            decHandle = handle
            decBuf    = ByteBuffer.allocate(decLen)
            lastDecNeedsAck = needsAck
        }

        lastDecHandle = handle
        lastDecCount  = count

        val buf = decBuf ?: return null
        val copyLen = minOf(data.size - i, buf.remaining())
        buf.put(data, i, copyLen)

        return if (lastChunk) {
            val type    = decType
            val payload = buf.array().copyOf(decLen)
            decHandle = null
            decBuf    = null
            Pair(type, payload)
        } else null
    }

    private fun encryptAes(data: ByteArray, key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    fun connect(macAddress: String) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device  = adapter.getRemoteDevice(macAddress)
        // La Band 7 exige un enlace BLE cifrado (bonded) para responder al endpoint de auth.
        // Si este móvil ya está emparejado con la pulsera, conectamos directamente.
        // Si no (p. ej. un dispositivo nuevo), creamos primero el bond a nivel de SO.
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            openGatt(device)
        } else {
            status("Pulsera no emparejada en este móvil. Emparejando...")
            ensureBondThenConnect(device)
        }
    }

    private fun openGatt(device: BluetoothDevice) {
        status("Conectando a ${device.address}...")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun ensureBondThenConnect(device: BluetoothDevice) {
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (dev?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDING ->
                        status("Emparejando... acepta la solicitud si la pulsera la muestra.")
                    BluetoothDevice.BOND_BONDED -> {
                        status("Emparejado correctamente. Conectando...")
                        unregisterBondReceiver()
                        openGatt(device)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        status("Emparejamiento fallido. Vuelve a pulsar Conectar.")
                        unregisterBondReceiver()
                    }
                }
            }
        }
        bondReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        if (!device.createBond()) {
            status("No se pudo iniciar el emparejamiento con la pulsera.")
            unregisterBondReceiver()
        }
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        bondReceiver = null
    }

    fun disconnect() {
        stopHrContinue()
        unregisterBondReceiver()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        // Reset all state so a subsequent connect() starts clean
        chrChunkedW = null; chrChunkedR = null; chrHr = null
        mtu = 23; writeHandle = 0; writeBusy = false; writeQueue.clear()
        descriptorWriteCallback = null
        decHandle = null; decBuf = null; decType = 0; decLen = 0
        status("Desconectado")
    }

    private fun stopHrContinue() {
        if (hrContinueActive) {
            mainHandler.removeCallbacks(hrContinueRunnable)
            hrContinueActive = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d("HuamiBLE", "onConnectionStateChange status=$status (${gattStatusName(status)}) newState=$newState bond=${g.device.bondState}")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@HuamiBleManager.status("[1] Conectado (status=$status). Descubriendo servicios...")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                this@HuamiBleManager.status("FALLO Desconectado en mitad del proceso (status=$status / ${gattStatusName(status)})")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d("HuamiBLE", "onServicesDiscovered status=$status (${gattStatusName(status)})")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                this@HuamiBleManager.status("FALLO Error descubriendo servicios: ${gattStatusName(status)}"); return
            }

            for (svc in g.services) {
                Log.d("HuamiBLE", "SVC ${svc.uuid}")
                for (chr in svc.characteristics)
                    Log.d("HuamiBLE", "  CHR ${chr.uuid} props=0x${"%02x".format(chr.properties)} " +
                        "(write=${chr.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0} " +
                        "writeNoResp=${chr.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0})")
            }

            val svc = g.getService(SVC_MAIN)
            if (svc == null) { this@HuamiBleManager.status("FALLO Servicio FEE0 no encontrado"); return }

            chrChunkedW = svc.getCharacteristic(CHR_CHUNKED_W)
            chrChunkedR = svc.getCharacteristic(CHR_CHUNKED_R)

            val hrSvc = g.getService(UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb"))
            chrHr = hrSvc?.getCharacteristic(CHR_HR)

            Log.d("HuamiBLE", "Características > W=${chrChunkedW != null} R=${chrChunkedR != null} HR=${chrHr != null}")
            if (chrChunkedR == null) { this@HuamiBleManager.status("FALLO CHR_CHUNKED_R no encontrada"); return }
            if (chrHr == null) { this@HuamiBleManager.status("FALLO CHR_HR no encontrada"); return }

            // Negociar el MTU antes de autenticar: en Android 12+ no se eleva de forma
            // automática, y la respuesta de autenticación no cabe en el MTU mínimo de 23 B.
            this@HuamiBleManager.status("[2] Servicios OK. Negociando MTU...")
            if (g.requestMtu(247)) return        // continúa en onMtuChanged
            proceedAfterMtu()                    // si no se pudo solicitar, seguimos con MTU=23
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this@HuamiBleManager.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            Log.d("HuamiBLE", "onMtuChanged mtu=$mtu status=$status (${gattStatusName(status)}) > uso=${this@HuamiBleManager.mtu}")
            this@HuamiBleManager.status("[3] MTU=${this@HuamiBleManager.mtu} (pedido 247, ${gattStatusName(status)}). Activando notificaciones...")
            proceedAfterMtu()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleChanged(ch.uuid, ch.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleChanged(ch.uuid, value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val name = when (ch.uuid) {
                CHR_CHUNKED_W -> "CHUNKED_W"
                CHR_CHUNKED_R -> "CHUNKED_R(ack)"
                CHR_HR        -> "HR"
                else          -> ch.uuid.toString()
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w("HuamiBLE", "FALLO onCharacteristicWrite $name status=$status (${gattStatusName(status)})")
                this@HuamiBleManager.status("FALLO Escritura RECHAZADA en $name: ${gattStatusName(status)} [status=$status]")
            } else {
                Log.d("HuamiBLE", "onCharacteristicWrite $name OK")
            }
            writeComplete()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            Log.d("HuamiBLE", "onDescriptorWrite (CCCD de ${d.characteristic.uuid}) status=$status (${gattStatusName(status)})")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                this@HuamiBleManager.status("FALLO Activación de notificaciones (CCCD) fallida: ${gattStatusName(status)} [status=$status]")
            }
            val cb = descriptorWriteCallback
            descriptorWriteCallback = null
            writeComplete()   // drain restores writeBusy=false before cb enqueues new ops
            cb?.invoke()
        }
    }

    private fun proceedAfterMtu() {
        val chunkedR = chrChunkedR ?: run { status("FALLO CHR_CHUNKED_R no disponible"); return }
        Log.d("HuamiBLE", "proceedAfterMtu > habilitando notificaciones en CHR_CHUNKED_R")
        enableNotify(chunkedR) { startAuth() }
    }

    private fun enableNotify(chr: BluetoothGattCharacteristic, next: () -> Unit) {
        val ok = gatt?.setCharacteristicNotification(chr, true)
        Log.d("HuamiBLE", "setCharacteristicNotification(${chr.uuid}) > $ok")
        val dsc = chr.getDescriptor(DSC_CCCD)
        if (dsc == null) {
            Log.w("HuamiBLE", "AVISO Sin descriptor CCCD en ${chr.uuid}; continúo sin escribirlo")
            next(); return
        }
        descriptorWriteCallback = next
        enqueue {
            Log.d("HuamiBLE", "> Escribiendo CCCD (ENABLE_NOTIFICATION) en ${chr.uuid}")
            writeDescriptor(dsc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    @SuppressLint("NewApi")
    private fun writeDescriptor(dsc: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeDescriptor(dsc, value)
        } else {
            @Suppress("DEPRECATION") dsc.value = value
            @Suppress("DEPRECATION") gatt?.writeDescriptor(dsc)
        }
    }

    private fun writeChunked(endpoint: Short, payload: ByteArray) {
        val chr = chrChunkedW ?: return
        encodeChunked(endpoint, payload).forEach { chunk ->
            enqueue {
                Log.d("HuamiBLE", "> CHR_CHUNKED_W: ${chunk.toHex()}")
                writeCharacteristic(chr, chunk)
            }
        }
    }

    private fun sendChunkedAck() {
        val chr = chrChunkedR ?: return
        val ack = byteArrayOf(0x04, 0x00, lastDecHandle, 0x01, lastDecCount)
        Log.d("HuamiBLE", "> ACK: ${ack.toHex()}")
        enqueue { writeCharacteristic(chr, ack) }
    }

    @SuppressLint("NewApi")
    private fun writeCharacteristic(chr: BluetoothGattCharacteristic, value: ByteArray) {
        // CHR_CHUNKED_W solo acepta "Write sin respuesta" (Write Command); un Write con
        // respuesta se rechaza con status 6 (REQUEST_NOT_SUPPORTED). Se elige el tipo
        // según las propiedades de la característica, usando NO_RESPONSE si está disponible.
        val writeType = if (chr.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val ok: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = gatt?.writeCharacteristic(chr, value, writeType)
            ok = rc == 0 // BluetoothStatusCodes.SUCCESS
            if (!ok) Log.w("HuamiBLE", "writeCharacteristic(API33+) devolvió rc=$rc para ${chr.uuid}")
        } else {
            @Suppress("DEPRECATION") chr.writeType = writeType
            @Suppress("DEPRECATION") chr.value = value
            @Suppress("DEPRECATION") ok = gatt?.writeCharacteristic(chr) == true
        }
        if (!ok) {
            Log.w("HuamiBLE", "FALLO writeCharacteristic NO aceptada por el sistema para ${chr.uuid}")
            status("FALLO El sistema rechazó enviar la escritura BLE (cola): ${chr.uuid.toString().take(8)}")
            writeComplete()
        }
    }

    private fun startAuth() {
        status("[4] Iniciando autenticación ECDH...")
        SecureRandom().nextBytes(privateEC)
        publicEC = ECDH_B163.ecdh_generate_public(privateEC) ?: run {
            status("FALLO Error generando clave pública ECDH (local)"); return
        }
        Log.d("HuamiBLE", "Clave pública ECDH generada (${publicEC.size} B). Enviando a EP_AUTH...")
        status("[5] Enviando clave pública a la pulsera. Esperando respuesta (paso 6)...")
        // Command: [0x04, 0x02, 0x00, 0x02, publicKey[48]]
        val cmd = ByteArray(52)
        cmd[0] = 0x04; cmd[1] = 0x02; cmd[2] = 0x00; cmd[3] = 0x02
        System.arraycopy(publicEC, 0, cmd, 4, 48)
        writeChunked(EP_AUTH, cmd)
    }

    private fun handleAuthPayload(payload: ByteArray) {
        when {
            payload.size >= 67 && payload[0] == 0x10.toByte() && payload[1] == 0x04.toByte() && payload[2] == 0x01.toByte() -> {
                status("[6] Respuesta recibida. Calculando secreto compartido...")
                val remoteRandom   = payload.copyOfRange(3, 19)
                val remotePublicEC = payload.copyOfRange(19, 67)
                val sharedEC = ECDH_B163.ecdh_generate_shared(privateEC, remotePublicEC) ?: run {
                    status("Error en ECDH"); return
                }
                val sessionKey = ByteArray(16) { i -> (sharedEC[i + 8].toInt() xor secretKey[i].toInt()).toByte() }
                val enc1 = encryptAes(remoteRandom, secretKey)
                val enc2 = encryptAes(remoteRandom, sessionKey)
                val cmd = ByteArray(33)
                cmd[0] = 0x05
                System.arraycopy(enc1, 0, cmd, 1, 16)
                System.arraycopy(enc2, 0, cmd, 17, 16)
                writeChunked(EP_AUTH, cmd)
            }
            payload.size >= 3 && payload[0] == 0x10.toByte() && payload[1] == 0x05.toByte() && payload[2] == 0x01.toByte() -> {
                status("[7] Autenticación correcta. Iniciando HR...")
                startHR()
            }
            payload.size >= 3 && payload[0] == 0x10.toByte() && payload[1] == 0x05.toByte() -> {
                status("Auth fallida (código ${payload[2].toInt() and 0xFF})")
            }
            else -> {
                status("Auth: respuesta desconocida ${payload.toHex()}")
            }
        }
    }

    private fun handleConnectionPayload(payload: ByteArray) {
        if (payload.isNotEmpty() && payload[0] == 0x03.toByte()) {
            writeChunked(EP_CONNECTION, byteArrayOf(0x04))
        }
    }

    private fun startHR() {
        val hr = chrHr ?: run { status("Error: CHR_HR no encontrada"); return }
        status("Auth OK. Iniciando monitorización HR...")
        enableNotify(hr) {
            writeChunked(EP_HR, byteArrayOf(0x04, 0x01))
            stopHrContinue()
            mainHandler.postDelayed(hrContinueRunnable, HR_CONTINUE_INTERVAL_MS)
            hrContinueActive = true
            status("Comando HR enviado. Esperando datos...")
        }
    }

    private fun handleChanged(uuid: UUID, value: ByteArray) {
        when (uuid) {
            CHR_CHUNKED_R -> {
                val result = decodeChunked(value) ?: return
                val (type, payload) = result
                Log.d("HuamiBLE", "< payload EP=0x${"%04x".format(type.toInt() and 0xFFFF)} (${payload.size}B): ${payload.toHex()}")
                when (type) {
                    EP_AUTH       -> handleAuthPayload(payload)
                    EP_CONNECTION -> handleConnectionPayload(payload)
                    EP_HR         -> { /* HR ACK via chunked - actual values come on CHR_HR */ }
                }
                if (lastDecNeedsAck) {
                    sendChunkedAck()
                }
            }
            CHR_HR -> {
                Log.d("HuamiBLE", "< CHR_HR: ${value.toHex()}")
                if (value.size < 2) return
                val hr = if ((value[0].toInt() and 0x01) == 0) {
                    value[1].toInt() and 0xFF
                } else {
                    ((value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8))
                }
                if (hr > 0) onHrValue(hr)
            }
        }
    }

    private fun status(msg: String) = mainHandler.post { onStatus(msg) }

    // Traduce los códigos de estado GATT a texto legible para el diagnóstico.
    // Los relevantes aquí: 5/15/137 = problema de cifrado/autenticación del enlace;
    // 133 = error genérico; 8/19/22 = caída de la conexión.
    private fun gattStatusName(status: Int): String = when (status) {
        0   -> "SUCCESS"
        1   -> "INVALID_HANDLE"
        3   -> "WRITE_NOT_PERMITTED"
        5   -> "INSUFFICIENT_AUTHENTICATION"
        6   -> "REQUEST_NOT_SUPPORTED"
        8   -> "CONN_TIMEOUT / INSUFF_AUTHORIZATION"
        13  -> "INVALID_ATTR_LENGTH"
        15  -> "INSUFFICIENT_ENCRYPTION"
        19  -> "TERMINATED_BY_PEER"
        22  -> "TERMINATED_LOCAL_HOST"
        62  -> "CONN_FAIL_ESTABLISHMENT"
        133 -> "GATT_ERROR (0x85)"
        137 -> "AUTH_FAIL (0x89)"
        143 -> "CONNECTION_CONGESTED"
        else -> "code=$status"
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
