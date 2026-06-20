package com.dopamincheker

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dopamincheker.databinding.ActivityLauncherBinding

class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Padding dinámico para que el botón de Ajustes no quede bajo la barra de navegación
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentLayout) { v, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBar + (16 * resources.displayMetrics.density).toInt())
            insets
        }

        binding.cardYoutube.setOnClickListener   { openPlatformWithBaselineCheck(MainActivity.PLATFORM_YOUTUBE) }
        binding.cardInstagram.setOnClickListener { openPlatformWithBaselineCheck(MainActivity.PLATFORM_INSTAGRAM) }
        binding.cardReddit.setOnClickListener    { openPlatformWithBaselineCheck(MainActivity.PLATFORM_REDDIT) }
        binding.cardTiktok.setOnClickListener    { openPlatformWithBaselineCheck(MainActivity.PLATFORM_TIKTOK) }
        binding.cardReading.setOnClickListener   { openPlatform(MainActivity.PLATFORM_READING) }
        binding.btnSettings.setOnClickListener   { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        val alias = SubjectPrefs.getAlias(this)
        binding.tvSubjectLabel.text = if (alias.isNotEmpty()) "Sujeto activo: $alias"
                                      else getString(R.string.select_platform)
    }

    private fun openPlatformWithBaselineCheck(platform: String) {
        val alias = SubjectPrefs.getAlias(this)
        if (!SubjectPrefs.hasBaseline(this, alias)) {
            AlertDialog.Builder(this)
                .setTitle("Sin calibración")
                .setMessage("No existe un archivo de calibración baseline para este sujeto. Para obtener resultados comparables, realiza primero una sesión de Lectura (Baseline).")
                .setPositiveButton("Entendido") { _, _ -> openPlatform(platform) }
                .show()
        } else {
            openPlatform(platform)
        }
    }

    private fun openPlatform(platform: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PLATFORM, platform)
        )
    }
}
