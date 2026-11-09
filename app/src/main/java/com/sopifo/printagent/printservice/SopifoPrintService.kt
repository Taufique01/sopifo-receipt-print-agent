package com.sopifo.printagent.printservice

import android.net.Uri
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
import com.sopifo.printagent.quick.QuickLabelSize
import com.sopifo.printagent.ui.print.SetupChecklist
import com.sopifo.printagent.runs.ApiRunContent
import com.sopifo.printagent.runs.NewRunItem
import com.sopifo.printagent.ui.shell.AppSettings
import com.sopifo.printagent.ui.shell.WebSession
import com.sopifo.printagent.quick.ImageFit
import kotlinx.coroutines.launch
import com.sopifo.printagent.data.db.RunState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.sopifo.printagent.runs.PrintRunner
import com.sopifo.printagent.service.ServiceController
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
    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = object : PrinterDiscoverySession() {
        // Always answer Android with the Sopifo printer, its status and label sizes: a printer
        // Android has to guess about shows as "printer not available".
        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            addPrinters(listOf(printerInfo(withCapabilities = true)))
        }

        override fun onStopPrinterDiscovery() = Unit

        /** "Is the Sopifo printer still there?" (a remembered printer): yes. */
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
            if (printerIds.any { it.localId == PRINTER_LOCAL_ID }) addPrinters(listOf(printerInfo(withCapabilities = true)))
        }

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
            // The label sizes first (inch + mm), the setup size selected; A4 / Letter for apps
            // that only lay out on paper (Sopifo then shrinks the page to the setup size).
            val caps = PrinterCapabilitiesInfo.Builder(generatePrinterId(PRINTER_LOCAL_ID))
            LABEL_MEDIA.forEach { (q, media) -> caps.addMediaSize(media, q == size) }
            // A setup size not in the list (small stickers): A4 is the default, shrunk to it.
            caps.addMediaSize(PrintAttributes.MediaSize.ISO_A4, LABEL_MEDIA.none { it.first == size })
                .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                .addResolution(PrintAttributes.Resolution("203dpi", "203 dpi", 203, 203), true)
                .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            builder.setCapabilities(caps.build())
        }
        return builder.build()
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    /** After an install or update: jobs Android kept from before are cancelled, not failed one by one. */
    override fun onConnected() {
        val now = System.currentTimeMillis()
        activePrintJobs.forEach { job ->
            if (PrintRefusal.isLeftover(job.info.creationTime, now, job.isStarted || job.isBlocked)) {
                AppLog.i(TAG, "Leftover job cancelled", "age_s" to (now - job.info.creationTime) / 1000)
                runCatching { job.cancel() }
            }
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val c = (application as SopifoApp).container
        if (PrintRefusal.isLeftover(printJob.info.creationTime, System.currentTimeMillis(), printJob.isStarted || printJob.isBlocked)) {
            AppLog.i(TAG, "Leftover job cancelled", "age_s" to (System.currentTimeMillis() - printJob.info.creationTime) / 1000)
            printJob.cancel()
            return
        }
        printJob.start()
        val settings = AppSettings.get(this)
        val signals = WebSession.accountSignals(c)
        val loggedIn = signals.values.any { it }
        AppLog.i(TAG, "Print → Sopifo job", "logged_in" to loggedIn, "signals" to signals.toString(), "free_used" to settings.setup.value.freeServicePrints)
        // A few prints work before logging in (enough to finish printer setup), then login.
        if (!SetupChecklist.allowServicePrint(loggedIn, settings.setup.value.freeServicePrints)) {
            AppLog.i(TAG, "Free print limit hit", "limit" to SetupChecklist.FREE_SERVICE_PRINTS)
            PrintRefusal.show(this, PrintRefusal.Reason.LOGIN, settings.lang.value)
            printJob.fail(
                if (settings.lang.value == "en") "${SetupChecklist.FREE_SERVICE_PRINTS} free prints used. Open Sopifo and log in to keep printing from other apps"
                else "${SetupChecklist.FREE_SERVICE_PRINTS}টি ফ্রি প্রিন্ট শেষ। প্রিন্ট চালিয়ে যেতে Sopifo খুলে লগ ইন করুন",
            )
            return
        }
        val label = runBlocking { c.printers.config(PrinterRole.LABEL) }
        if (label == null) {
            AppLog.i(TAG, "No label printer")
            PrintRefusal.show(this, PrintRefusal.Reason.NO_LABEL_PRINTER, settings.lang.value)
            printJob.fail(NoLabelPrinter().message)
            return
        }
        val file = File(cacheDir, "printservice-${System.currentTimeMillis()}.pdf")
        try {
            val fd = printJob.document.data ?: throw IllegalStateException("No document")
            FileInputStream(fd.fileDescriptor).use { input -> file.outputStream().use { input.copyTo(it) } }
            fd.close()
        } catch (e: Exception) {
            AppLog.w(TAG, "Couldn't read the print document", e)
            PrintRefusal.show(this, PrintRefusal.Reason.OTHER, settings.lang.value, e.message)
            printJob.fail(e.message ?: "Couldn't read the document")
            return
        }
        val title = printJob.info.label.ifBlank { "Print" }
        // The size picked in Android's print screen; A4 / Letter → the setup size.
        val picked = printJob.info.attributes.mediaSize?.id
        val size = LABEL_MEDIA.firstOrNull { it.second.id == picked }?.first ?: settings.labelSize.value
        // The job stays open with Android until the label has printed (or failed): while a job
        // is open Android keeps Sopifo running, so it can't be frozen half way in the background.
        // The 30 s printer limit, 5 pages and a quick scan keep that to a few seconds.
        ServiceController.start(this, "print_service")
        AppLog.i(TAG, "Print → Sopifo taken", "printer" to label.name)
        c.scope.launch {
            var outcome = Outcome.FAILED
            try {
                // Logged in: the plan's prints from other apps this month (Free: 30).
                if (loggedIn) {
                    val quota = PlanQuota.take(c)
                    if (quota is PlanQuota.Answer.Refused) {
                        AppLog.i(TAG, "Plan limit: prints from other apps used up", "message" to quota.message)
                        PrintRefusal.show(this@SopifoPrintService, PrintRefusal.Reason.PLAN_LIMIT, settings.lang.value)
                        outcome = Outcome.CANCEL
                        return@launch
                    }
                }
                val renderer = QuickPrintRenderer(this@SopifoPrintService)
                val uri = Uri.fromFile(file)
                val total = renderer.pdfPageCount(uri)
                val pages = total.coerceAtMost(PrintRefusal.MAX_PAGES)
                val bitmaps = renderer.pdfPages(
                    uri, 1, pages, size.target,
                    trim = true, fit = ImageFit.FIT, darkness = PdfDarkness.NORMAL, smart = true,
                )
                val items = bitmaps.mapIndexed { i, bmp ->
                    val saved = renderer.save(bmp)
                    bmp.recycle()
                    NewRunItem(ApiRunContent.KIND_QUICK, saved.absolutePath, "quick", if (bitmaps.size > 1) "$title · ${i + 1}" else title)
                }
                val runId = c.runner.start(title, PrintRunner.SOURCE_OTHER_APPS, PrinterRole.LABEL, items)
                if (total > pages) PrintRefusal.show(this@SopifoPrintService, PrintRefusal.Reason.PAGES_CUT, settings.lang.value, "$pages/$total")
                // Printer setup step 5 ✓ ("Print with Sopifo" works); counts the free prints.
                settings.updateSetup { it.copy(printServiceUsed = true, freeServicePrints = if (loggedIn) it.freeServicePrints else it.freeServicePrints + 1) }
                AppLog.i(TAG, "Print → Sopifo queued", "pages" to items.size, "printer" to label.name)
                val ended = c.runner.awaitFinish(runId, JOB_OPEN_MAX_MS)
                outcome = when {
                    // Still printing after JOB_OPEN_MAX_MS (a long queue): let Android go; the run carries on.
                    ended == null -> Outcome.DONE
                    ended.state == RunState.DONE && ended.failed == 0 -> Outcome.DONE
                    // Sopifo's own notice says why (the run result); no extra Android "Printer error".
                    else -> Outcome.CANCEL
                }
                AppLog.i(TAG, "Print → Sopifo ended", "state" to (ended?.state ?: "still_running"), "printed" to (ended?.printed ?: -1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Print → Sopifo failed", e)
                PrintRefusal.show(this@SopifoPrintService, PrintRefusal.Reason.OTHER, settings.lang.value, e.message)
                outcome = Outcome.CANCEL
            } finally {
                file.delete()
                withContext(NonCancellable + Dispatchers.Main) {
                    runCatching {
                        when (outcome) {
                            Outcome.DONE -> if (!printJob.isCompleted) printJob.complete()
                            else -> if (!printJob.isCancelled && !printJob.isCompleted) printJob.cancel()
                        }
                    }
                }
            }
        }
    }

    private enum class Outcome { DONE, CANCEL, FAILED }

    private class NoLabelPrinter : IllegalStateException("No label printer: set one up in Sopifo → Printers")

    private companion object {
        /** Label sizes in the print screen: "3 × 2 in · 76 × 51 mm" (mils = 1/1000 inch). */
        val LABEL_MEDIA: List<Pair<QuickLabelSize, PrintAttributes.MediaSize>> = listOf(
            QuickLabelSize.S3X2 to PrintAttributes.MediaSize("sopifo_S3X2", "3 × 2 in · 76 × 51 mm", 3000, 2000),
            QuickLabelSize.S2X3 to PrintAttributes.MediaSize("sopifo_S2X3", "2 × 3 in · 50 × 75 mm", 2000, 3000),
            QuickLabelSize.S2X3L to PrintAttributes.MediaSize("sopifo_S2X3L", "2 × 3 in landscape · 75 × 50 mm", 2953, 1969),
            QuickLabelSize.S3X3 to PrintAttributes.MediaSize("sopifo_S3X3", "3 × 3 in · 76 × 76 mm", 3000, 3000),
            QuickLabelSize.S3X4 to PrintAttributes.MediaSize("sopifo_S3X4", "3 × 4 in · 76 × 102 mm", 3000, 4000),
            QuickLabelSize.S4X6 to PrintAttributes.MediaSize("sopifo_S4X6", "4 × 6 in · 102 × 152 mm", 4000, 6000),
        )
        const val TAG = "SopifoPrintService"
        const val PRINTER_LOCAL_ID = "sopifo-label"
        /** Longest Android's job stays open (a long queue): then "done", and the run carries on. */
        const val JOB_OPEN_MAX_MS = 3 * 60_000L
    }
}
