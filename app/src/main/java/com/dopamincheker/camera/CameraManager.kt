package com.dopamincheker.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrameAnalyzed: (ImageProxy) -> Unit
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            onFrameAnalyzed(imageProxy)
                        } catch (e: Exception) {
                            imageProxy.close()
                        }
                    }
                }

            // Frontal > trasera > cualquier cámara disponible (último recurso para emulador)
            val availableCameras = cameraProvider.availableCameraInfos
            Log.d(TAG, "Cámaras disponibles: ${availableCameras.size}")

            val cameraSelector = when {
                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> {
                    Log.d(TAG, "Usando cámara frontal")
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> {
                    Log.w(TAG, "Cámara frontal no disponible, usando trasera")
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                availableCameras.isNotEmpty() -> {
                    Log.w(TAG, "Usando primera cámara disponible (modo emulador)")
                    CameraSelector.Builder()
                        .addCameraFilter { cameras -> listOf(cameras.first()) }
                        .build()
                }
                else -> {
                    Log.e(TAG, "El dispositivo no tiene ninguna cámara")
                    return@addListener
                }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
        }, ContextCompat.getMainExecutor(context))
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}
