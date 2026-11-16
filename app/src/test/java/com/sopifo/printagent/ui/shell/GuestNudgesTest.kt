package com.sopifo.printagent.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TC-561: printing is never limited; the free-shop card after 5 prints, the strip tip once a day. */
class GuestNudgesTest {
    private val dhaka = 6 * 60 * 60 * 1000L
    // Mon 2026-11-16 10:00 in Dhaka.
    private val monday = 1_794_801_600_000L

    @Test fun weekRunsMondayToSunday() {
        val sunday = monday - 24 * 60 * 60 * 1000L
        assertEquals(GuestNudges.weekOf(monday, dhaka), GuestNudges.weekOf(monday + 6 * 24 * 60 * 60 * 1000L, dhaka))
        assertEquals(GuestNudges.weekOf(monday, dhaka) - 1, GuestNudges.weekOf(sunday, dhaka))
    }

    @Test fun countsThisWeekAndStartsOverOnMonday() {
        var c = GuestNudges.add(PrintCount(), 3, monday, dhaka)
        c = GuestNudges.add(c, 2, monday + 60_000, dhaka)
        assertEquals(5, c.total)
        assertEquals(5, c.weekCount)
        val nextWeek = monday + 7 * 24 * 60 * 60 * 1000L
        c = GuestNudges.add(c, 1, nextWeek, dhaka)
        assertEquals(6, c.total)
        assertEquals(1, c.weekCount)
    }

    @Test fun shopCardAfterFivePrintsAndHiddenForAWeek() {
        assertFalse(GuestNudges.showShopCard(signedIn = false, totalPrints = 4, hiddenUntil = 0, nowMs = monday))
        assertTrue(GuestNudges.showShopCard(signedIn = false, totalPrints = 5, hiddenUntil = 0, nowMs = monday))
        assertFalse(GuestNudges.showShopCard(signedIn = true, totalPrints = 50, hiddenUntil = 0, nowMs = monday))
        val hidden = monday + GuestNudges.CARD_HIDE_MS
        assertFalse(GuestNudges.showShopCard(false, 9, hidden, monday + 1))
        assertTrue(GuestNudges.showShopCard(false, 9, hidden, hidden))
    }

    @Test fun stripTipOnceADayAndNeverWhenSignedIn() {
        val today = GuestNudges.dayOf(monday, dhaka)
        assertTrue(GuestNudges.showStripTip(signedIn = false, lastTipDay = today - 1, today = today))
        assertFalse(GuestNudges.showStripTip(signedIn = false, lastTipDay = today, today = today))
        assertFalse(GuestNudges.showStripTip(signedIn = true, lastTipDay = -1, today = today))
    }
}
