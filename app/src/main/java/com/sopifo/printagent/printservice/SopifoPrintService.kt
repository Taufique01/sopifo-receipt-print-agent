package com.sopifo.printagent.printservice

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.quick.PdfDarkness
import com.sopifo.printagent.quick.QuickPrintRenderer
import com.sopifo.printagent.quick.QuickTarget
import com.sopifo.printagent.runs.ApiRunContent
import com.sopifo.printagent.runs.NewRunItem
import com.sopifo.printagent.ui.shell.AppSettings
import com.sopifo.printagent.ui.shell.WebSession
import com.sopifo.printagent.quick.ImageFit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream

/**
 * "Sopifo" in Android's Print menu (app 2.0.2): print from the Pathao / Steadfast app, Chrome or
 * any app straight to the Bluetooth label printer. The page comes as a PDF (A4 by default, as the
 * app designed it); Sopifo trims the empty paper, smart-shrinks it to the label size chosen on
 * the Printers screen (barcodes redrawn sharp) and prints it like a Quick print.
 * Needs a Sopifo login (full features) and a label printer.
 */
class SopifoPrintService : PrintService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = object : PrinterDiscoverySession() {
        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            addPrinters(listOf(printerInfo(withCapabilities = false)))
        }

        override fun onStopPrinterDiscovery() = Unit
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) = Unit

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            addPrinters(listOf(printerInfo(withCapabilities = true)))
        }

        override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit
        override fun onDestroy() = Unit
    }

    private fun printerInfo(withCapabilities: Boolean): PrinterInfo {
        val c = (application as SopifoApp).container
        val label = runBlocking { c.printers.config(PrinterRole.LABEL) }
        val size = AppSettings.get(this).labelSize.value
        val name = "Sopifo · ${label?.name ?: "—"} (${size.title.substringBefore(" (")})"
        val status = if (label != null) PrinterInfo.STATUS_IDLE else PrinterInfo.STATUS_UNAVAILABLE
        val builder = PrinterInfo.Builder(generatePrinterId(PRINTER_LOCAL_ID), name, status)
            .setDescription(if (label != null) "Bluetooth label printer" else "Set up a label printer in Sopifo")
        if (withCapabilities) {
            // The source app lays the page out as it designed it (A4 / 4×6); Sopifo shrinks it after.
            val caps = PrinterCapabilitiesInfo.Builder(generatePrinterId(PRINTER_LOCAL_ID))
                .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                .addMediaSize(PrintAttributes.MediaSize("sopifo_4x6", "4 × 6 in", 4000, 6000), false)
                .addResolution(PrintAttributes.Resolution("203dpi", "203 dpi", 203, 203), true)
                .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            builder.setCapabilities(caps)
        }
        return builder.build()
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val c = (application as SopifoApp).container
        printJob.start()
        if (!WebSession.isLoggedIn(c)) {
            printJob.fail(if (AppSettings.get(this).lang.value == "en") "Log in to Sopifo to print from other apps" else "অন্য অ্যাপ থেকে প্রিন্ট করতে Sopifo-তে লগ ইন করুন")
            return
        }
        val file = File(cacheDir, "printservice-${System.currentTimeMillis()}.pdf")
        try {
            val fd = printJob.document.data ?: throw IllegalStateException("No document")
            FileInputStream(fd.fileDescriptor).use { input -> file.outputStream().use { input.copyTo(it) } }
            fd.close()
        } catch (e: Exception) {
            AppLog.w(TAG, "Couldn't read the print document", e)
            printJob.fail(e.message ?: "Couldn't read the document")
            return
        }
        val title = printJob.info.label.ifBlank { "Print" }
        c.scope.launch {
            try {
                val label = c.printers.config(PrinterRole.LABEL) ?: throw IllegalStateException("No label printer: set one up in Sopifo")
                val size = AppSettings.get(this@SopifoPrintService).labelSize.value
                val renderer = QuickPrintRenderer(this@SopifoPrintService)
                val uri = Uri.fromFile(file)
                val pages = renderer.pdfPageCount(uri).coerceAtMost(MAX_PAGES)
                val bitmaps = renderer.pdfPages(
                    uri, 1, pages, QuickTarget(size.w, size.h),
                    trim = true, fit = ImageFit.FIT, darkness = PdfDarkness.NORMAL, smart = true,
                )
                val items = bitmaps.mapIndexed { i, bmp ->
                    val saved = renderer.save(bmp)
                    bmp.recycle()
                    NewRunItem(ApiRunContent.KIND_QUICK, saved.absolutePath, "quick", if (bitmaps.size > 1) "$title · ${i + 1}" else title)
                }
                c.runner.start(title, "print_service", PrinterRole.LABEL, items)
                AppLog.i(TAG, "Print → Sopifo queued", "pages" to items.size, "printer" to label.name)
                main.post { if (!printJob.isCompleted) printJob.complete() }
            } catch (e: Exception) {
                AppLog.w(TAG, "Print → Sopifo failed", e)
                main.post { if (!printJob.isFailed) printJob.fail(e.message ?: "Print failed") }
            } finally {
                file.delete()
            }
        }
    }

    private companion object {
        const val TAG = "SopifoPrintService"
        const val PRINTER_LOCAL_ID = "sopifo-label"
        const val MAX_PAGES = 20
    }
}
