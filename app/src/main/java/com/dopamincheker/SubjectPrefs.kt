package com.dopamincheker

import android.content.Context
import com.dopamincheker.detection.BlinkDetector
import java.io.File

object SubjectPrefs {
    private const val PREFS = "dopamin_prefs"
    private const val KEY_ALIAS = "subject_alias"
    private const val KEY_BASELINE_PREFIX = "baseline_done_"
    private const val KEY_EAR_THRESHOLD_PREFIX = "ear_threshold_"

    fun getAlias(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ALIAS, "") ?: ""

    fun setAlias(context: Context, alias: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALIAS, alias.trim()).apply()

    /** Umbral EAR individual del sujeto, derivado en la calibración. Si no se ha calibrado aún,
     *  devuelve el valor por defecto del detector. */
    fun getEarThreshold(context: Context, alias: String): Float {
        if (alias.isEmpty()) return BlinkDetector.DEFAULT_EAR_THRESHOLD
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat("$KEY_EAR_THRESHOLD_PREFIX${alias.lowercase()}", BlinkDetector.DEFAULT_EAR_THRESHOLD)
    }

    fun setEarThreshold(context: Context, alias: String, value: Float) {
        if (alias.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat("$KEY_EAR_THRESHOLD_PREFIX${alias.lowercase()}", value).apply()
    }

    fun setBaselineDone(context: Context, alias: String) {
        if (alias.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("$KEY_BASELINE_PREFIX${alias.lowercase()}", true).apply()
    }

    fun hasBaseline(context: Context, alias: String): Boolean {
        // Comprobación rápida en SharedPreferences (fuente de verdad primaria)
        if (alias.isNotEmpty()) {
            val flag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("$KEY_BASELINE_PREFIX${alias.lowercase()}", false)
            if (flag) return true
        }

        // Fallback: escaneo de archivos (compatibilidad con sesiones antiguas)
        val extDir = context.getExternalFilesDir(null) ?: return false
        val dir = File(extDir, "sessions")
        if (!dir.exists()) return false
        val prefix = if (alias.isNotEmpty()) "${alias}_baseline_" else "_baseline_"
        val found = dir.listFiles { f ->
            f.isFile && f.name.contains("_baseline_") &&
            (alias.isEmpty() || f.name.startsWith(prefix))
        }?.isNotEmpty() == true

        // Si se encontró por archivos, persistir el flag para que las comprobaciones futuras sean instantáneas
        if (found && alias.isNotEmpty()) setBaselineDone(context, alias)
        return found
    }
}
