package com.dopamincheker.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

class FaceLandmarkerHelper(
    private val context: Context,
    private val onResult: (FaceLandmarkerResult) -> Unit,
    private val onError: (Exception) -> Unit = {}
) {
    private var faceLandmarker: FaceLandmarker? = null

    // Garantiza timestamps estrictamente crecientes (requisito de LIVE_STREAM)
    private val frameTimestamp = AtomicLong(0)

    fun setup() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            // CPU es más estable que GPU cuando el WebView también usa el GPU para rendering
            .setDelegate(Delegate.CPU)
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { error ->
                Log.e(TAG, "MediaPipe error: ${error.message}")
                onError(error)
            }
            .build()

        try {
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "FaceLandmarker init failed - ¿falta face_landmarker.task en assets?", e)
            onError(e)
        }
    }

    fun detectAsync(imageProxy: ImageProxy) {
        val bitmap = imageProxyToBitmap(imageProxy)
        imageProxy.close()

        val mpImage = BitmapImageBuilder(bitmap).build()
        val ts = maxOf(SystemClock.uptimeMillis(), frameTimestamp.get() + 1)
        frameTimestamp.set(ts)

        try {
            faceLandmarker?.detectAsync(mpImage, ts)
        } catch (e: Exception) {
            Log.e(TAG, "detectAsync error", e)
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        val w = imageProxy.width
        val h = imageProxy.height

        // Construye NV21 (Y + VU intercalado) a partir de YUV_420_888
        val nv21 = ByteArray(w * h * 3 / 2)

        // Copia plano Y respetando el rowStride (puede haber padding al final de cada fila)
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        for (row in 0 until h) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, row * w, w)
        }

        // Intercala V y U para formar el plano VU de NV21
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        var nv21Index = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val uvOffset = row * uvRowStride + col * uvPixelStride
                nv21[nv21Index++] = vBuffer.get(uvOffset)
                nv21[nv21Index++] = uBuffer.get(uvOffset)
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, w, h), 90, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())!!
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }

    companion object {
        private const val TAG = "FaceLandmarkerHelper"
    }
}
