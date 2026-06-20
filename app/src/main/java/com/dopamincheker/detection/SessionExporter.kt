package com.dopamincheker.detection

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SessionExporter(private val context: Context) {

    fun exportSession(
        blinks: List<BlinkRecord>,
        frames: List<FrameRecord>,
        hrRecords: List<HrRecord>,
        sessionDurationMs: Long,
        sessionStartEpochMs: Long,
        platform: String = "youtube",
        subject: String = "",
        isBaseline: Boolean = false
    ): String {
        val dir = File(context.getExternalFilesDir(null), "sessions")
        dir.mkdirs()

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val tag = if (isBaseline) "baseline" else platform
        val prefix = if (subject.isNotEmpty()) "${subject}_${tag}_${ts}" else "${tag}_${ts}"

        saveBlinksCSV(blinks, File(dir, "${prefix}_parpadeos.csv"), sessionDurationMs, sessionStartEpochMs)
        saveRawCSV(frames, File(dir, "${prefix}_ear_raw.csv"), sessionStartEpochMs)
        saveHrCSV(hrRecords, File(dir, "${prefix}_hr.csv"))
        saveMetadata(blinks, hrRecords, sessionDurationMs, sessionStartEpochMs,
            platform, subject, isBaseline, File(dir, "${prefix}_sesion.csv"))

        return dir.absolutePath
    }

    fun zipAllSessions(subject: String): File {
        val sessionsDir = File(context.getExternalFilesDir(null), "sessions")
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val zipName = if (subject.isNotEmpty()) "${subject}_experimento_${ts}.zip"
                      else "experimento_${ts}.zip"
        val zipFile = File(context.getExternalFilesDir(null), zipName)

        val files = sessionsDir.listFiles { f ->
            f.isFile && (subject.isEmpty() || f.name.startsWith("${subject}_"))
        } ?: emptyArray()

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return zipFile
    }

    private fun saveBlinksCSV(
        blinks: List<BlinkRecord>,
        file: File,
        sessionDurationMs: Long,
        sessionStartEpochMs: Long
    ) {
        val durationS = sessionDurationMs / 1000.0
        val bpm = if (durationS > 0) blinks.size / (durationS / 60.0) else 0.0
        file.printWriter().use { out ->
            out.println(String.format(Locale.US,
                "# sesion_duracion_s=%.2f  total_parpadeos=%d  parpadeos_min=%.1f",
                durationS, blinks.size, bpm))
            out.println(
                "parpadeo_id,epoch_ms,frame_inicio,frame_fin," +
                "tiempo_inicio_ms,tiempo_fin_ms,duracion_ms,n_frames_cerrado," +
                "ear_min_izquierdo,ear_min_derecho,ear_min_promedio," +
                "ear_media_izquierdo,ear_media_derecho"
            )
            blinks.forEach { b ->
                out.println(
                    "${b.id},${sessionStartEpochMs + b.timeStartMs}," +
                    "${b.frameStart},${b.frameEnd}," +
                    "${b.timeStartMs},${b.timeEndMs},${b.durationMs},${b.nFramesClosed}," +
                    "${String.format(Locale.US, "%.4f", b.earMinLeft)},${String.format(Locale.US, "%.4f", b.earMinRight)}," +
                    "${String.format(Locale.US, "%.4f", b.earMinAvg)},${String.format(Locale.US, "%.4f", b.earMeanLeft)}," +
                    "${String.format(Locale.US, "%.4f", b.earMeanRight)}"
                )
            }
        }
    }

    private fun saveRawCSV(frames: List<FrameRecord>, file: File, sessionStartEpochMs: Long) {
        file.printWriter().use { out ->
            out.println(
                "frame,epoch_ms,tiempo_ms,cara_detectada," +
                "ear_izquierdo,ear_derecho,ear_promedio,ojo_cerrado,en_parpadeo"
            )
            frames.forEach { f ->
                out.println(
                    "${f.frame},${sessionStartEpochMs + f.timeMs},${f.timeMs}," +
                    "${f.faceDetected}," +
                    "${f.earLeft?.let { String.format(Locale.US, "%.4f", it) } ?: ""}," +
                    "${f.earRight?.let { String.format(Locale.US, "%.4f", it) } ?: ""}," +
                    "${f.earAvg?.let { String.format(Locale.US, "%.4f", it) } ?: ""}," +
                    "${f.eyeClosed},${f.inBlink}"
                )
            }
        }
    }

    private fun saveHrCSV(hrRecords: List<HrRecord>, file: File) {
        file.printWriter().use { out ->
            out.println("epoch_ms,bpm")
            hrRecords.forEach { r -> out.println("${r.epochMs},${r.bpm}") }
        }
    }

    private fun saveMetadata(
        blinks: List<BlinkRecord>,
        hrRecords: List<HrRecord>,
        sessionDurationMs: Long,
        sessionStartEpochMs: Long,
        platform: String,
        subject: String,
        isBaseline: Boolean,
        file: File
    ) {
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
        val durationS = sessionDurationMs / 1000.0
        val bpm = if (durationS > 0) blinks.size / (durationS / 60.0) else 0.0
        val hrMedia = if (hrRecords.isNotEmpty()) hrRecords.map { it.bpm }.average() else null

        file.printWriter().use { out ->
            out.println("clave,valor")
            out.println("sujeto,$subject")
            out.println("baseline,$isBaseline")
            out.println("plataforma,$platform")
            out.println("inicio_iso,${isoFmt.format(Date(sessionStartEpochMs))}")
            out.println("inicio_epoch_ms,$sessionStartEpochMs")
            out.println("duracion_s,${String.format(Locale.US, "%.2f", durationS)}")
            out.println("total_parpadeos,${blinks.size}")
            out.println("parpadeos_min,${String.format(Locale.US, "%.2f", bpm)}")
            out.println("fc_registros,${hrRecords.size}")
            out.println("fc_media_bpm,${hrMedia?.let { String.format(Locale.US, "%.1f", it) } ?: ""}")
        }
    }
}
