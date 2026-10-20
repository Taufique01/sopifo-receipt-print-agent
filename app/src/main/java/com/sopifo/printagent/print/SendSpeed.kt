package com.sopifo.printagent.print

import com.sopifo.printagent.data.db.PrinterProtocol
import kotlin.math.ceil

/**
 * How fast print data goes over Bluetooth, per printer. In label mode (TSPL) a printer takes in
 * the whole label before printing; small portable ones (e.g. Xprinter XP-P210) silently drop
 * labels sent faster than they can take in — nothing prints and no error comes back. Receipts
 * (ESC/POS) print as they arrive, so they keep the fast default.
 */
object SendSpeed {
    /** What every printer used before this setting. */
    const val FAST_KBPS = 64

    /** Label printers' default: the XP-P210 lost every label over ~10 KB at 64 KB/s. */
    const val SAFE_KBPS = 8

    /** Slider stops, KB/s. */
    val STEPS = listOf(4, 6, 8, 12, 16, 24, 32, 48, 64)

    fun defaultFor(protocol: PrinterProtocol): Int =
        if (protocol == PrinterProtocol.TSPL) SAFE_KBPS else FAST_KBPS

    /** Smaller writes when slow, so the pauses between them stay short and even. */
    fun chunkSize(kbps: Int): Int = if (kbps >= 32) 512 else 128

    fun chunkDelayMs(kbps: Int): Long {
        val speed = kbps.coerceIn(STEPS.first(), STEPS.last())
        return ceil(chunkSize(speed) * 1000.0 / (speed * 1024)).toLong().coerceAtLeast(1)
    }

    /** Time to send [bytes] at [kbps], ms. */
    fun sendMs(bytes: Int, kbps: Int): Long {
        val chunks = (bytes + chunkSize(kbps) - 1) / chunkSize(kbps)
        return chunks * chunkDelayMs(kbps)
    }

    /** Write watchdog: twice the expected send time on top of a fixed allowance. */
    fun writeTimeoutMs(bytes: Int, kbps: Int): Long = 15_000L + 2 * sendMs(bytes, kbps)

    fun label(kbps: Int): String = when {
        kbps <= SAFE_KBPS -> "$kbps KB/s · Safe (small portable printers)"
        kbps >= FAST_KBPS -> "$kbps KB/s · Fast"
        else -> "$kbps KB/s"
    }
}
