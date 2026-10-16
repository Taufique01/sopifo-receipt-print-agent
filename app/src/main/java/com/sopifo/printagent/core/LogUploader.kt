package com.sopifo.printagent.core

import android.content.Context
import com.sopifo.printagent.data.DeviceRepository
import com.sopifo.printagent.data.api.ApiClient
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.print.PrinterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Sends the log bundle (see [LogShare.buildFile]) to the backend, where support finds it under
 * the shop's folder. Reasons: `button` (Send logs to support), `request` (support asked, by push
 * or heartbeat), `crash` (after the app restarted from a crash), `print_failed` (rate-limited by
 * the scheduler). Returns the short reference code the seller can quote.
 */
class LogUploader(
    private val context: Context,
    private val api: ApiClient,
    private val device: DeviceRepository,
    private val printers: PrinterManager,
    private val appVersion: String,
) {
    suspend fun upload(reason: String): String = withContext(Dispatchers.IO) {
        val file = buildBundle(reason)
        val gzipped = gzip(file)
        val ref = api.uploadLogs(reason, appVersion, gzipped)
        AppLog.i(TAG, "Logs uploaded", "reason" to reason, "ref" to ref, "bytes" to file.length(), "sent" to gzipped.size)
        ref
    }

    /** The bundle file itself, also used to share it by hand (WhatsApp). Blocking I/O. */
    suspend fun buildBundle(reason: String = "share"): File {
        val config = device.config()
        val status = printers.status.value
        val printerConfigs = PrinterRole.entries.associateWith { printers.config(it) }
        val header = linkedMapOf(
            "Reason" to reason,
            "App version" to appVersion,
            "Store" to config?.storeName,
            "Device name" to config?.deviceName,
            "Device ID" to config?.deviceId,
        )
        for (role in PrinterRole.entries) {
            val p = printerConfigs[role]
            header["${role.name.lowercase()} printer"] =
                p?.let { "${it.name} ${it.macAddress} (${it.protocol}, paper ${it.paperWidthMm ?: "?"} mm) — ${status[role]?.wire}" } ?: "not configured"
        }
        return withContext(Dispatchers.IO) { LogShare.buildFile(context, header) }
    }

    private fun gzip(file: File): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz -> file.inputStream().use { it.copyTo(gz) } }
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "LogUploader"
    }
}
