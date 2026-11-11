package com.sopifo.printagent.quick

import android.content.Context
import androidx.core.content.edit
import com.sopifo.printagent.data.db.PrinterRole

/**
 * Smart shrink and Quick print / Share choices (app 2.1.13).
 *  - Print → Sopifo (the spooler) never smart-shrinks: the page is trimmed and fitted as drawn.
 *  - A PDF in Quick print / Share starts with smart shrink off (the switch still turns it on).
 *  - Quick print / Share starts on the printer (receipt / label) and label size used last; with
 *    nothing saved, the receipt printer and the label size from the Printers screen. Kept apart
 *    from that label size, so a one-off Quick print size doesn't change Print → Sopifo or the website.
 */
object QuickPrintChoices {
    const val SPOOLER_SMART_SHRINK = false
    const val PDF_SMART_DEFAULT = false

    data class Choice(val role: PrinterRole, val labelSize: QuickLabelSize)

    fun restore(savedRole: String?, savedSize: String?, fallbackSize: QuickLabelSize): Choice = Choice(
        role = PrinterRole.entries.firstOrNull { it.name == savedRole } ?: PrinterRole.RECEIPT,
        labelSize = QuickLabelSize.entries.firstOrNull { it.name == savedSize } ?: fallbackSize,
    )

    private const val PREFS = "quick_print"
    private const val KEY_ROLE = "role"
    private const val KEY_SIZE = "label_size"

    fun load(context: Context, fallbackSize: QuickLabelSize): Choice {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return restore(prefs.getString(KEY_ROLE, null), prefs.getString(KEY_SIZE, null), fallbackSize)
    }

    fun save(context: Context, choice: Choice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_ROLE, choice.role.name)
            putString(KEY_SIZE, choice.labelSize.name)
        }
    }
}
