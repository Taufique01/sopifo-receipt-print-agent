package com.sopifo.printagent.ui.pages

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.print.PrintDataBuilder
import com.sopifo.printagent.quick.ImageFit
import com.sopifo.printagent.quick.QuickLabelSize
import com.sopifo.printagent.quick.QuickPrintRenderer
import com.sopifo.printagent.quick.QuickTarget
import com.sopifo.printagent.runs.ApiRunContent
import com.sopifo.printagent.runs.NewRunItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class QuickMode(val title: String) { TEXT("Text"), IMAGE("Image"), PDF("PDF") }

data class QuickPrintState(
    val mode: QuickMode = QuickMode.TEXT,
    val role: PrinterRole = PrinterRole.RECEIPT,
    val imageUri: Uri? = null,
    /** Image: show it whole (Fit) or cover the label (Fill), then zoom around the centre. */
    val imageFit: ImageFit = ImageFit.FIT,
    val imageZoom: Float = 1f,
    /** Text tab: the rich text as HTML (bold, sizes, alignment…), printed as shown. */
    val html: String = "",
    val pdfUri: Uri? = null,
    val pdfPages: Int = 0,
    val pageFrom: Int = 1,
    val pageTo: Int = 1,
    /** PDF: cut the page's white margins so the content fills the paper. */
    val pdfTrim: Boolean = true,
    /** PDF on a label: the whole page (Fit) or edge to edge (Fill). */
    val pdfFit: ImageFit = ImageFit.FIT,
    /** Used when the chosen printer is a label (TSPL) printer. */
    val labelSize: QuickLabelSize = QuickLabelSize.S50X30,
    val copies: Int = 1,
    /** The chosen printer is a label (TSPL) printer: it needs a label size. */
    val printerIsLabel: Boolean = false,
    val printerMissing: Boolean = false,
    val preview: Bitmap? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Quick print: text, an image or a PDF, drawn on the phone and printed on either printer. */
class QuickPrintViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as SopifoApp).container
    private val renderer = QuickPrintRenderer(app)

    private val _state = MutableStateFlow(QuickPrintState())
    val state: StateFlow<QuickPrintState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    private var previewJob: Job? = null

    init { refreshPreview() }

    fun update(change: (QuickPrintState) -> QuickPrintState) {
        _state.update(change)
        refreshPreview()
    }

    fun pickImage(uri: Uri?) = uri?.let { update { s -> s.copy(mode = QuickMode.IMAGE, imageUri = it) } }

    fun pickPdf(uri: Uri?) {
        uri ?: return
        viewModelScope.launch {
            try {
                val pages = withContext(Dispatchers.IO) { renderer.pdfPageCount(uri) }
                update { it.copy(mode = QuickMode.PDF, pdfUri = uri, pdfPages = pages, pageFrom = 1, pageTo = minOf(pages, MAX_PDF_PAGES)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't open that PDF: ${e.message}") }
            }
        }
    }

    /** The first page as it will print, redrawn shortly after each change. */
    private fun refreshPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            // Text is drawn by a WebView: wait for a pause in typing.
            delay(if (_state.value.mode == QuickMode.TEXT) 600 else 250)
            try {
                val bitmaps = withContext(Dispatchers.Default) { render(firstOnly = true) }
                _state.update { it.copy(preview = bitmaps.firstOrNull(), error = null) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(preview = null, error = e.message) }
            }
        }
    }

    private suspend fun target(): QuickTarget {
        val s = _state.value
        val printer = c.printers.config(s.role)
        val isLabel = printer?.protocol == PrinterProtocol.TSPL
        _state.update { it.copy(printerIsLabel = isLabel, printerMissing = printer == null) }
        return if (isLabel) {
            QuickTarget(s.labelSize.w, s.labelSize.h)
        } else {
            QuickTarget(PrintDataBuilder.paperWidthDots(printer?.paperWidthMm) ?: PrintDataBuilder.PAPER_58_MM_DOTS)
        }
    }

    private suspend fun render(firstOnly: Boolean): List<Bitmap> {
        val s = _state.value
        val target = target()
        return when (s.mode) {
            QuickMode.TEXT -> if (s.html.isBlank()) emptyList() else listOf(renderer.html(s.html, target))
            QuickMode.IMAGE -> s.imageUri?.let { listOf(renderer.image(it, target, s.imageFit, s.imageZoom)) } ?: emptyList()
            QuickMode.PDF -> s.pdfUri?.let {
                val to = if (firstOnly) s.pageFrom else s.pageTo
                renderer.pdfPages(it, s.pageFrom, to, target, s.pdfTrim, s.pdfFit)
            } ?: emptyList()
        }
    }

    fun print() {
        val s = _state.value
        if (s.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val files = withContext(Dispatchers.Default) { render(firstOnly = false).map { renderer.save(it) } }
                if (files.isEmpty()) {
                    _message.value = when (s.mode) {
                        QuickMode.TEXT -> "Type something to print"
                        QuickMode.IMAGE -> "Choose an image first"
                        QuickMode.PDF -> "Choose a PDF first"
                    }
                    return@launch
                }
                val items = (1..s.copies).flatMap { copy ->
                    files.mapIndexed { i, f ->
                        val page = if (files.size > 1) " · page ${s.pageFrom + i}" else ""
                        val copyText = if (s.copies > 1) " · copy $copy" else ""
                        NewRunItem(ApiRunContent.KIND_QUICK, f.absolutePath, "quick", "${s.mode.title}$page$copyText")
                    }
                }
                c.runner.start("Quick print", "quick", s.role, items)
                _message.value = "Printing ${items.size}… you can lock the phone"
            } catch (e: Exception) {
                _message.value = "Couldn't prepare the print: ${e.message}"
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        const val MAX_PDF_PAGES = 20
    }
}
