package com.sopifo.printagent.ui.shell

import android.content.Context
import com.sopifo.printagent.AppContainer
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.print.PrintDataBuilder
import com.sopifo.printagent.quick.PdfDarkness
import com.sopifo.printagent.quick.PdfStretch
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.quick.QuickPrintChoices
import com.sopifo.printagent.quick.QuickPrintRenderer
import com.sopifo.printagent.quick.QuickTarget
import com.sopifo.printagent.runs.ApiRunContent
import com.sopifo.printagent.runs.NewRunItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Share → Sopifo prints at once (2.1.20, after the seller said Yes once): a strip shows
 * "🖨 Printing 3 on XP-420B… [Cancel]" for [WAIT_MS], then the pages go to the printer with the
 * last Quick print choices (printer, label size, Fit / Fill) and the app steps back to the app
 * the file came from. No printer for those choices → Quick print opens as before.
 */
object SharedAutoPrint {
    enum class Phase { WAIT, SENT, FAILED }

    data class Strip(
        val phase: Phase,
        /** Pages to print (0 while the file is still being read). */
        val pages: Int,
        val printer: String,
        /** Not signed in, first time today: "Book in Sopifo: labels print without Share [Try →]". */
        val tip: Boolean,
        val error: String? = null,
    )

    val strip = MutableStateFlow<Strip?>(null)

    /** "Try →" on the tip: the start screen opens sign-up (and the app stays open). */
    val signUpRequested = MutableStateFlow(false)

    const val WAIT_MS = 2_000L
    const val MAX_PAGES = 20

    private var job: Job? = null
    /** The file being printed: "Open" on a failed strip shows it in Quick print. */
    private var lastItem: SharedFile.Item? = null

    /** A shared file while auto-print is on. False = not possible here (Quick print should open). */
    fun begin(context: Context, c: AppContainer, item: SharedFile.Item, signedIn: Boolean): Boolean {
        val settings = AppSettings.get(context)
        val saved = QuickPrintChoices.load(context, settings.labelSize.value)
        // A shared PDF goes to the label printer (2.1.21); a photo to the printer used last.
        val choice = if (item.isPdf) {
            saved.copy(
                role = kotlinx.coroutines.runBlocking {
                    QuickPrintChoices.pdfRole(
                        hasLabel = c.printers.config(PrinterRole.LABEL) != null,
                        hasReceipt = c.printers.config(PrinterRole.RECEIPT) != null,
                    )
                },
            )
        } else {
            saved
        }
        val printer = kotlinx.coroutines.runBlocking { c.printers.config(choice.role) } ?: return false
        val today = GuestNudges.dayOf(System.currentTimeMillis())
        val tip = GuestNudges.showStripTip(signedIn, settings.stripTipDay, today)
        if (tip) settings.stripTipDay = today
        job?.cancel()
        lastItem = item
        strip.value = Strip(Phase.WAIT, 0, printer.name, tip)
        job = c.scope.launch {
            val target = if (printer.protocol == PrinterProtocol.TSPL) choice.labelSize.target
            else QuickTarget(PrintDataBuilder.paperWidthDots(printer.paperWidthMm) ?: PrintDataBuilder.PAPER_58_MM_DOTS)
            val started = System.currentTimeMillis()
            try {
                // Drawn while the strip counts down; Cancel stops it before anything prints.
                val files: List<File> = withContext(Dispatchers.Default) {
                    val renderer = QuickPrintRenderer(context)
                    val bitmaps = if (item.isPdf) {
                        val pages = renderer.pdfPageCount(item.uri)
                        strip.value = strip.value?.copy(pages = minOf(pages, MAX_PAGES))
                        renderer.pdfPages(
                            item.uri, 1, minOf(pages, MAX_PAGES), target,
                            trim = true, fit = choice.pdfFit, darkness = PdfDarkness.NORMAL, stretch = PdfStretch.X1,
                            smart = QuickPrintChoices.PDF_SMART_DEFAULT,
                        )
                    } else {
                        strip.value = strip.value?.copy(pages = 1)
                        listOf(renderer.turned(renderer.image(item.uri, target, choice.imageFit, 1f), target))
                    }
                    bitmaps.map { bmp -> renderer.save(bmp).also { bmp.recycle() } }
                }
                delay((WAIT_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                val title = if (item.isPdf) "Shared PDF" else "Shared photo"
                val items = files.mapIndexed { i, f ->
                    NewRunItem(ApiRunContent.KIND_QUICK, f.absolutePath, "quick", if (files.size > 1) "$title · page ${i + 1}" else title)
                }
                c.runner.start(title, "quick", choice.role, items)
                settings.countPrints(items.size)
                AppLog.i(TAG, "Shared file printed at once", "pages" to items.size, "printer" to printer.name)
                strip.value = strip.value?.copy(phase = Phase.SENT, pages = items.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Shared auto-print failed", e)
                strip.value = strip.value?.copy(phase = Phase.FAILED, error = e.message ?: "Couldn't read the file")
            }
        }
        return true
    }

    /** Cancel on the strip: nothing prints. */
    fun cancel() {
        job?.cancel()
        job = null
        strip.value = null
    }

    fun close() {
        strip.value = null
    }

    /** A failed auto-print: Quick print with the same file (fix the printer or the choices there). */
    fun openInQuickPrint() {
        strip.value = null
        lastItem?.let { SharedFile.item.value = it }
    }

    private const val TAG = "SharedAutoPrint"
}
