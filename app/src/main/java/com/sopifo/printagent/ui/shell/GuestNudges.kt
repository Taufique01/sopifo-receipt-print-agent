package com.sopifo.printagent.ui.shell

/** Prints made on this phone: all time, and this week's (Monday to Sunday, phone time). */
data class PrintCount(val total: Int = 0, val week: Int = 0, val weekCount: Int = 0) {
    /** This week's prints as of [nowMs] (0 once a new week started). */
    fun thisWeek(nowMs: Long): Int = if (week == GuestNudges.weekOf(nowMs)) weekCount else 0
}

/**
 * When the app (not signed in) points at a free shop (2.1.20). Printing itself is never limited:
 * - the guest home's card after the 5th print, ✕ = hidden for a week;
 * - the auto-print strip's tip, at most once a day.
 */
object GuestNudges {
    const val CARD_AFTER_PRINTS = 5
    const val CARD_HIDE_MS = 7L * 24 * 60 * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Phone's local day number. */
    fun dayOf(nowMs: Long, offsetMs: Long = java.util.TimeZone.getDefault().getOffset(nowMs).toLong()): Long =
        Math.floorDiv(nowMs + offsetMs, DAY_MS)

    /** Week number, weeks starting Monday (epoch day 0 was a Thursday). */
    fun weekOf(nowMs: Long, offsetMs: Long = java.util.TimeZone.getDefault().getOffset(nowMs).toLong()): Int =
        Math.floorDiv(dayOf(nowMs, offsetMs) + 3, 7L).toInt()

    fun add(count: PrintCount, n: Int, nowMs: Long, offsetMs: Long = java.util.TimeZone.getDefault().getOffset(nowMs).toLong()): PrintCount {
        val week = weekOf(nowMs, offsetMs)
        return PrintCount(
            total = count.total + n,
            week = week,
            weekCount = (if (count.week == week) count.weekCount else 0) + n,
        )
    }

    fun showShopCard(signedIn: Boolean, totalPrints: Int, hiddenUntil: Long, nowMs: Long): Boolean =
        !signedIn && totalPrints >= CARD_AFTER_PRINTS && nowMs >= hiddenUntil

    fun showStripTip(signedIn: Boolean, lastTipDay: Long, today: Long): Boolean = !signedIn && lastTipDay != today
}
