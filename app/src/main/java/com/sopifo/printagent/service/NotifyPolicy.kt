package com.sopifo.printagent.service

import com.sopifo.printagent.data.db.PrintRunEntity
import com.sopifo.printagent.data.db.RunState
import com.sopifo.printagent.print.PrinterState
import com.sopifo.printagent.runs.PrintRunner

/**
 * What Sopifo puts in the notification bar. One print from Chrome / another app should show
 * nothing from Sopifo when it works (Android's own "Printing…" is already there), and a
 * printer link closed between prints is normal, not an error to show.
 */
object NotifyPolicy {
    /** The always-there status line: only what needs the seller's attention. */
    fun statusText(online: Boolean?, receipt: PrinterState?, label: PrinterState?): String {
        val states = listOf(receipt, label)
        val base = when {
            states.any { it == PrinterState.BLUETOOTH_OFF } -> "Bluetooth is off"
            states.any { it == PrinterState.NO_PERMISSION } -> "Allow Bluetooth for Sopifo"
            states.all { it == null || it == PrinterState.NOT_CONFIGURED } -> "Set up a printer in Sopifo"
            else -> "Ready to print"
        }
        return if (online == false) "$base · offline" else base
    }

    private fun fromOtherApp(run: PrintRunEntity) = run.source == PrintRunner.SOURCE_OTHER_APPS

    /** No progress notification for prints from other apps (a page or two). */
    fun quietProgress(run: PrintRunEntity) = fromOtherApp(run)

    /** The result notification: always for app runs; for other apps only when something went wrong. */
    fun showResult(run: PrintRunEntity) = !(fromOtherApp(run) && run.state == RunState.DONE && run.failed == 0)
}
