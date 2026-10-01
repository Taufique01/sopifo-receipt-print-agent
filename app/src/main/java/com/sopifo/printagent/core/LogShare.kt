package com.sopifo.printagent.core

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * "Send logs to support": bundles the rotating log files into one text file and hands it to
 * WhatsApp addressed to the support number. Falls back to the system share sheet when neither
 * WhatsApp nor WhatsApp Business is installed.
 */
object LogShare {
    /** Support WhatsApp number, international format without "+". */
    const val SUPPORT_WHATSAPP = "8801340404043"

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    private const val DIR = "shared_logs"

    /** Blocking file I/O (up to ~1 MB); call off the main thread. */
    fun buildFile(context: Context, header: Map<String, String?>): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val out = File(dir, "sopifo-agent-log-$stamp.txt")
        out.bufferedWriter().use { w ->
            w.appendLine("Sopifo Print Agent log")
            w.appendLine("Exported: ${LocalDateTime.now()}")
            w.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            header.forEach { (k, v) -> w.appendLine("$k: ${v ?: "—"}") }
            w.appendLine()
            val files = AppLog.logFiles()
            if (files.isEmpty()) w.appendLine("(no log file)")
            // logFiles() lists the rotated (older) file first, so lines stay in time order.
            files.forEach { f ->
                f.bufferedReader().use { r -> r.copyTo(w) }
            }
        }
        return out
    }

    /** Must be called with an Activity context. */
    fun send(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        val text = "Sopifo Print Agent log (${file.name})"
        fun baseIntent() = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        for (pkg in WHATSAPP_PACKAGES) {
            try {
                context.startActivity(
                    baseIntent().setPackage(pkg)
                        // Opens the chat with the support number directly instead of the contact picker.
                        .putExtra("jid", "$SUPPORT_WHATSAPP@s.whatsapp.net"),
                )
                AppLog.i(TAG, "Logs shared via WhatsApp", "package" to pkg, "bytes" to file.length())
                return
            } catch (_: ActivityNotFoundException) {
                // Not installed; try the next one.
            }
        }

        AppLog.i(TAG, "WhatsApp not installed; using share sheet", "bytes" to file.length())
        context.startActivity(Intent.createChooser(baseIntent(), "Send logs to support (WhatsApp +$SUPPORT_WHATSAPP)"))
    }

    private const val TAG = "LogShare"
}
