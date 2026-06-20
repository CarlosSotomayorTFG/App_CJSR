package com.dopamincheker

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.dopamincheker.databinding.ActivitySettingsBinding
import com.dopamincheker.detection.SessionExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etSubjectAlias.setText(SubjectPrefs.getAlias(this))

        binding.btnBack.setOnClickListener { finish() }

        binding.btnSaveAlias.setOnClickListener {
            val alias = binding.etSubjectAlias.text.toString().trim()
            SubjectPrefs.setAlias(this, alias)
            Toast.makeText(this, getString(R.string.subject_saved), Toast.LENGTH_SHORT).show()
            currentFocus?.let {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(it.windowToken, 0)
            }
        }

        binding.btnStartCalibration.setOnClickListener { startCalibration() }
        binding.btnDiagnosticHR.setOnClickListener {
            startActivity(Intent(this, BandHRTestActivity::class.java))
        }
        binding.btnDiagnosticBlink.setOnClickListener {
            startActivity(Intent(this, BlinkTestActivity::class.java))
        }
        binding.btnConclude.setOnClickListener { confirmConclude() }
    }

    private fun startCalibration() {
        val alias = binding.etSubjectAlias.text.toString().trim()
        if (alias.isEmpty()) {
            Toast.makeText(this, getString(R.string.calibration_no_subject), Toast.LENGTH_LONG).show()
            binding.etSubjectAlias.requestFocus()
            return
        }
        SubjectPrefs.setAlias(this, alias)

        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PLATFORM, MainActivity.PLATFORM_READING)
                .putExtra(MainActivity.EXTRA_IS_CALIBRATION, true)
        )
    }

    private fun confirmConclude() {
        val alias = SubjectPrefs.getAlias(this)
        val msg = getString(R.string.conclude_confirm_msg) +
            if (alias.isNotEmpty()) "\n\nSujeto: $alias" else "\n\nNo hay sujeto configurado."
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.conclude_confirm_title))
            .setMessage(msg)
            .setPositiveButton("Generar y compartir") { _, _ -> concludeExperiment(alias) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun concludeExperiment(alias: String) {
        lifecycleScope.launch {
            try {
                val zipFile = withContext(Dispatchers.IO) {
                    SessionExporter(this@SettingsActivity).zipAllSessions(alias)
                }

                if (!zipFile.exists() || zipFile.length() == 0L) {
                    Toast.makeText(this@SettingsActivity,
                        getString(R.string.conclude_no_data), Toast.LENGTH_LONG).show()
                    return@launch
                }

                val uri = FileProvider.getUriForFile(
                    this@SettingsActivity,
                    "${packageName}.provider",
                    zipFile
                )
                val subjectTag = if (alias.isNotEmpty()) alias else "experimento"
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_SUBJECT,
                        "${getString(R.string.export_email_subject)}$subjectTag")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Compartir datos"))
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity,
                    "Error al generar el archivo: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
