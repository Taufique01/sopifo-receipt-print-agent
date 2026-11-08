package com.sopifo.printagent.fast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.widget.Toast
import com.sopifo.printagent.AppContainer
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.api.PrintResultItemDto
import com.sopifo.printagent.data.api.PrintResultsRequest
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.print.PrintDataBuilder
import com.sopifo.printagent.print.PrintException
import com.sopifo.printagent.ui.shell.AppSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Fast printing inside the app (2.0.1): what the website hands the phone through the bridge.
 * - POS receipts are drawn here (ReceiptRenderer) and sent over the kept-open link.
 * - An order's label / invoice is drawn by the server ahead of time (when the order opens) and
 *   kept here, so tapping Print only sends it.
 * Each returns false when the phone can't do it (no printer for that job, not connected to a
 * shop): the website then prints the usual way.
 */
class PhonePrinter(private val context: Context, private val c: AppContainer) {
    private val cache = LruCache<String, ByteArray>(24)
    private val main = Handler(Looper.getMainLooper())
    private val builder = PrintDataBuilder()

    fun printReceipt(json: String): Boolean {
        val printer = runBlocking { c.printers.config(PrinterRole.RECEIPT) } ?: return false
        val receipt = runCatching { ReceiptRenderer.parse(json) }.getOrElse { return false }
        val started = SystemClock.elapsedRealtime()
        c.scope.launch {
            try {
                val words = if (AppSettings.get(context).lang.value == "en") ReceiptRenderer.EN else ReceiptRenderer.BN
                val width = PrintDataBuilder.paperWidthDots(printer.paperWidthMm) ?: PrintDataBuilder.PAPER_58_MM_DOTS
                val bitmap = ReceiptRenderer.draw(receipt, width, words)
                val data = builder.fromDrawn(bitmap, printer)
                bitmap.recycle()
                c.printers.printFast(printer, data.bytes)
                AppLog.i(TAG, "POS receipt printed", "ms" to (SystemClock.elapsedRealtime() - started), "number" to receipt.number)
            } catch (e: Exception) {
                AppLog.w(TAG, "POS receipt failed", e)
                toast("✗ ${friendly(e)}")
            }
        }
        return true
    }

    /** Draws an opened order's label (and invoice) ahead, so Print is instant. */
    fun prepare(kind: String, id: String) {
        if (c.device.baseUrl == null || !validKind(kind, id)) return
        val docs = if (kind == "order") listOf("label", "invoice") else listOf("label")
        c.scope.launch {
            for (doc in docs) {
                val key = "$kind:$id:$doc"
                if (cache.get(key) != null) continue
                val role = roleFor(doc)
                if (c.printers.config(role) == null) continue
                runCatching { c.api.renderSingle(kind, id, doc).png }
                    .onSuccess { cache.put(key, it) }
                    .onFailure { AppLog.w(TAG, "Pre-draw failed", it, "kind" to kind, "doc" to doc) }
            }
        }
    }

    fun printDoc(kind: String, id: String, doc: String): Boolean {
        if (c.device.baseUrl == null || !validKind(kind, id) || (doc != "label" && doc != "invoice")) return false
        val printer = runBlocking { c.printers.config(roleFor(doc)) } ?: return false
        val started = SystemClock.elapsedRealtime()
        c.scope.launch {
            val key = "$kind:$id:$doc"
            var status = "printed"
            var reason: String? = null
            try {
                val png = cache.get(key) ?: c.api.renderSingle(kind, id, doc).png
                val data = c.printers.buildFromPng(png, printer, 1)
                c.printers.printFast(printer, data.bytes)
                toast(if (doc == "invoice") "✓ 🧾" else "✓ 🏷")
                AppLog.i(TAG, "Printed from the app", "kind" to kind, "doc" to doc, "ms" to (SystemClock.elapsedRealtime() - started))
            } catch (e: Exception) {
                status = "failed"
                reason = friendly(e)
                toast("✗ $reason")
            }
            // The website's history and "Printed" badges.
            runCatching {
                c.api.reportPrintResults(
                    PrintResultsRequest(UUID.randomUUID().toString(), listOf(PrintResultItemDto(kind, id, doc, status, reason))),
                )
            }
        }
        return true
    }

    /** Opens the printer for the page the seller is on (POS → receipt, orders → label). */
    fun warmFor(path: String) {
        val roles = when {
            path.startsWith("/pos") -> listOf(PrinterRole.RECEIPT)
            path.startsWith("/dashboard/orders") || path.startsWith("/dashboard/couriers") -> listOf(PrinterRole.LABEL)
            else -> return
        }
        c.scope.launch { roles.forEach { runCatching { c.printers.warmUp(it) } } }
    }

    private fun roleFor(doc: String) = if (doc == "invoice") PrinterRole.RECEIPT else PrinterRole.LABEL

    private fun validKind(kind: String, id: String) = (kind == "order" || kind == "parcel") && Regex("^[A-Za-z0-9_-]{6,64}$").matches(id)

    private fun friendly(e: Throwable): String = when (e) {
        is PrintException -> e.message ?: "Printer error"
        else -> e.message ?: "Error"
    }

    private fun toast(text: String) = main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }

    private companion object {
        const val TAG = "PhonePrinter"
    }
}
