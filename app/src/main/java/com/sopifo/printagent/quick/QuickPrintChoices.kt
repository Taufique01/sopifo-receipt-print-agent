package com.sopifo.printagent.quick

import android.content.Context
import androidx.core.content.edit
import com.sopifo.printagent.data.db.PrinterRole

/**
 * Smart shrink and Quick print / Share choices (app 2.1.13).
 *  - Print → Sopifo (the spooler) never smart-shrinks: the page is trimmed and fitted as drawn.
 *  - A PDF in Quick print / Share starts with smart shrink off (the switch still turns it on).
 *  - Quick print / Share starts on the printer (receipt / label), label size and Fit / Fill (image
 *    and PDF each, 2.1.14) and PDF darkness (2.1.22) used last; with
 *    nothing saved, the receipt printer and the label size from the Printers screen. Kept apart
 *    from that label size, so a one-off Quick print size doesn't change Print → Sopifo or the website.
 */
object QuickPrintChoices {
    const val SPOOLER_SMART_SHRINK = false
    const val PDF_SMART_DEFAULT = false

    data class Choice(
        val role: PrinterRole,
        val labelSize: QuickLabelSize,
        val imageFit: ImageFit = ImageFit.FIT,
        val pdfFit: ImageFit = ImageFit.FIT,
        /** Share prints PDFs this dark too (2.1.22; it was always Normal). */
        val pdfDarkness: PdfDarkness = PdfDarkness.NORMAL,
    )

    fun restore(
        savedRole: String?,
        savedSize: String?,
        fallbackSize: QuickLabelSize,
        savedImageFit: String? = null,
        savedPdfFit: String? = null,
        savedPdfDarkness: String? = null,
    ): Choice = Choice(
        role = PrinterRole.entries.firstOrNull { it.name == savedRole } ?: PrinterRole.RECEIPT,
        labelSize = QuickLabelSize.entries.firstOrNull { it.name == savedSize } ?: fallbackSize,
        imageFit = ImageFit.entries.firstOrNull { it.name == savedImageFit } ?: ImageFit.FIT,
        pdfFit = ImageFit.entries.firstOrNull { it.name == savedPdfFit } ?: ImageFit.FIT,
        pdfDarkness = PdfDarkness.entries.firstOrNull { it.name == savedPdfDarkness } ?: PdfDarkness.NORMAL,
    )

    /**
     * 2.1.21: a PDF (Quick print or Share) is almost always a courier label, so it starts on the
     * label printer; the receipt printer only when it is the only one set up. With none set up,
     * the label printer (Quick print then says to add one). Not saved as the last choice, so Text
     * and Photo keep the printer chosen for them.
     */
    fun pdfRole(hasLabel: Boolean, hasReceipt: Boolean): PrinterRole =
        if (!hasLabel && hasReceipt) PrinterRole.RECEIPT else PrinterRole.LABEL

    private const val PREFS = "quick_print"
    private const val KEY_ROLE = "role"
    private const val KEY_SIZE = "label_size"
    private const val KEY_IMAGE_FIT = "image_fit"
    private const val KEY_PDF_FIT = "pdf_fit"
    private const val KEY_PDF_DARKNESS = "pdf_darkness"

    fun load(context: Context, fallbackSize: QuickLabelSize): Choice {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return restore(
            prefs.getString(KEY_ROLE, null),
            prefs.getString(KEY_SIZE, null),
            fallbackSize,
            prefs.getString(KEY_IMAGE_FIT, null),
            prefs.getString(KEY_PDF_FIT, null),
            prefs.getString(KEY_PDF_DARKNESS, null),
        )
    }

    fun save(context: Context, choice: Choice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_ROLE, choice.role.name)
            putString(KEY_SIZE, choice.labelSize.name)
            putString(KEY_IMAGE_FIT, choice.imageFit.name)
            putString(KEY_PDF_FIT, choice.pdfFit.name)
            putString(KEY_PDF_DARKNESS, choice.pdfDarkness.name)
        }
    }
}
