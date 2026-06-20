package com.dopamincheker

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dopamincheker.band.BandCredentials
import com.dopamincheker.band.HuamiBleManager
import com.dopamincheker.camera.CameraManager
import com.dopamincheker.databinding.ActivityMainBinding
import com.dopamincheker.ui.OverlayView
import com.dopamincheker.viewmodel.BlinkViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLATFORM        = "extra_platform"
        const val EXTRA_IS_CALIBRATION  = "extra_is_calibration"
        const val PLATFORM_YOUTUBE      = "youtube"
        const val PLATFORM_INSTAGRAM    = "instagram"
        const val PLATFORM_REDDIT       = "reddit"
        const val PLATFORM_TIKTOK       = "tiktok"
        const val PLATFORM_READING      = "lectura"

        private val PLATFORM_URLS = mapOf(
            PLATFORM_YOUTUBE   to "https://www.youtube.com",
            PLATFORM_INSTAGRAM to "https://www.instagram.com",
            PLATFORM_REDDIT    to "https://www.reddit.com",
            PLATFORM_TIKTOK    to "https://www.tiktok.com"
        )

        private val BAND_MAC = BandCredentials.MAC
        private val BAND_KEY = BandCredentials.KEY

        private const val CALIBRATION_DURATION_MS = 20 * 60 * 1000L
        private val CALIBRATION_CHECKPOINTS_MIN = listOf(5L, 10L, 15L)
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: BlinkViewModel by viewModels()
    private lateinit var cameraManager: CameraManager
    private lateinit var platform: String
    private var isCalibration = false
    private var bleManager: HuamiBleManager? = null

    private val calibrationHandler = Handler(Looper.getMainLooper())

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) initializeCamera() else showPermissionRationale()
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) connectBand()
        else Log.w("MainActivity", "BLE permissions denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        platform      = intent.getStringExtra(EXTRA_PLATFORM) ?: PLATFORM_YOUTUBE
        isCalibration = intent.getBooleanExtra(EXTRA_IS_CALIBRATION, false)

        applyWindowInsets()
        setupWebView()
        checkCameraPermission()
        checkBlePermissionsAndConnect()
        observeBlinkStats()

        // Pulsación larga sobre el botón de cerrar (X) > modo demostración (capturas de la memoria).
        // El overlay ignora los toques a propósito (los deja pasar al WebView), por eso no sirve.
        binding.btnEndSession.setOnLongClickListener { viewModel.startDemoMode(); true }

        if (isCalibration) {
            setupCalibrationMode()
        } else {
            setupBackNavigation()
            binding.btnEndSession.setOnClickListener { showEndSessionDialog() }
        }
    }

    private fun applyWindowInsets() {
        val basePx = (16 * resources.displayMetrics.density).toInt()

        // WebView: empieza justo debajo de la barra de estado
        ViewCompat.setOnApplyWindowInsetsListener(binding.webView) { v, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            (v.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = statusBar
            v.requestLayout()
            insets
        }

        // Botón cerrar e indicador de alerta: barra de estado + margen base
        listOf(binding.btnEndSession, binding.alertIndicator).forEach { view ->
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                (v.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin =
                    statusBar + basePx
                v.requestLayout()
                insets
            }
        }
    }

    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort      = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
        }

        if (platform == PLATFORM_READING) {
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
                .build()
            binding.webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)
            }
            binding.webView.loadUrl("https://appassets.androidplatform.net/assets/reader.html")
        } else {
            binding.webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (url.startsWith("http://") || url.startsWith("https://")) return false
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true
                    } catch (e: ActivityNotFoundException) { true }
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    Log.d("WebView", "onPageStarted: $url")
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame)
                        Log.e("WebView", "Error ${error.errorCode}: ${error.description} - ${request.url}")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame)
                        Log.e("WebView", "HTTP ${response.statusCode} - ${request.url}")
                }
            }
            binding.webView.loadUrl(PLATFORM_URLS[platform] ?: "https://www.youtube.com")
        }
    }

    private fun checkCameraPermission() {
        if (hasPermission(Manifest.permission.CAMERA)) initializeCamera()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun initializeCamera() {
        cameraManager = CameraManager(
            context         = this,
            lifecycleOwner  = this,
            onFrameAnalyzed = { imageProxy -> viewModel.processImageProxy(imageProxy) }
        )
        cameraManager.startCamera()
    }

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Permiso de cámara")
            .setMessage(getString(R.string.permission_camera_rationale))
            .setPositiveButton("Conceder") { _, _ -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun checkBlePermissionsAndConnect() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: permisos BLE dedicados, sin necesidad de localización
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))    needed += Manifest.permission.BLUETOOTH_SCAN
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            // Android < 12: el escaneo BLE requería permiso de ubicación
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isEmpty()) connectBand() else blePermissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun connectBand() {
        bleManager = HuamiBleManager(
            context   = this,
            secretKey = BAND_KEY,
            onHrValue = { bpm -> viewModel.addHrRecord(bpm) },
            onStatus  = { msg -> Log.d("BLE", msg) }
        )
        bleManager?.connect(BAND_MAC)
    }

    private fun observeBlinkStats() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.overlayView.updateData(
                        OverlayView.DisplayData(
                            blinksPerMinute        = state.blinksPerMinute,
                            totalBlinks            = state.totalBlinks,
                            sessionSeconds         = state.sessionSeconds,
                            faceDetected           = state.faceDetected,
                            currentBpm             = state.currentBpm,
                            calibrationRemainingMs = state.calibrationRemainingMs
                        )
                    )
                    binding.alertIndicator.updateState(state.alertState)
                }
            }
        }
    }

    private fun showEndSessionDialog() {
        AlertDialog.Builder(this)
            .setTitle("¿Terminar sesión?")
            .setMessage("¿Estás seguro de que quieres terminar la sesión?")
            .setPositiveButton("Sí") { _, _ ->
                lifecycleScope.launch {
                    val path = viewModel.saveSession(platform)
                    // path vacío = sesión descartada por ser demasiado corta (<1 min): no notificar
                    if (path.isNotEmpty()) {
                        android.widget.Toast.makeText(this@MainActivity,
                            "Sesión guardada en:\n$path", android.widget.Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun setupCalibrationMode() {
        binding.btnEndSession.visibility = android.view.View.GONE
        viewModel.enableCalibrationCountdown(CALIBRATION_DURATION_MS)

        CALIBRATION_CHECKPOINTS_MIN.forEach { min ->
            calibrationHandler.postDelayed({
                val remaining = (20 - min).toInt()
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.calibration_notification_title))
                    .setMessage("Han transcurrido $min min.\nRestan $remaining minutos.")
                    .setPositiveButton("Continuar", null)
                    .show()
            }, min * 60 * 1000L)
        }

        calibrationHandler.postDelayed({ finishCalibration() }, CALIBRATION_DURATION_MS)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.calibration_cancel_title))
                    .setMessage(getString(R.string.calibration_cancel_msg))
                    .setPositiveButton("Cancelar calibración") { _, _ ->
                        calibrationHandler.removeCallbacksAndMessages(null)
                        finish()
                    }
                    .setNegativeButton("Seguir calibrando", null)
                    .show()
            }
        })
    }

    private fun finishCalibration() {
        calibrationHandler.removeCallbacksAndMessages(null)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.calibration_complete_title))
            .setMessage(getString(R.string.calibration_complete_msg))
            .setPositiveButton("OK") { _, _ ->
                lifecycleScope.launch {
                    try { viewModel.saveSession(PLATFORM_READING, isBaseline = true) }
                    catch (_: Exception) {}
                    finish()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        viewModel.pauseSession()
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumeSession()
    }

    override fun onStop() {
        super.onStop()
        // viewModelScope sobrevive al onDestroy de la Activity, garantizando que la escritura IO complete
        viewModel.saveSessionAsync(platform, isCalibration)
    }

    override fun onDestroy() {
        calibrationHandler.removeCallbacksAndMessages(null)
        bleManager?.disconnect()
        bleManager = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.overlayView.invalidate()
        if (platform == PLATFORM_READING) {
            binding.webView.evaluateJavascript(
                "if(typeof rendition!=='undefined'){" +
                "  rendition.resize(window.innerWidth, window.innerHeight - PADDING*2);" +
                "}", null
            )
        }
    }
}
