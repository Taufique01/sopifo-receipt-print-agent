package com.sopifo.printagent.print

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kept-open link pacing (2.1.5): the first print starts at once, the next waits for the printer. */
class HeldPacerTest {
    private var now = 1_000L
    private val pacer = HeldPacer({ now }, { bytes -> 300L + bytes / 20 })

    @Test fun firstPrintDoesNotWait() {
        assertEquals(0, pacer.waitMs())
    }

    @Test fun backToBackPrintsAreSpaced() {
        pacer.sent(60_000) // a long invoice: 300 + 3000 ms
        assertEquals(3_300, pacer.waitMs())
        now += 1_000
        assertEquals(2_300, pacer.waitMs())
    }

    @Test fun noWaitOnceThePrinterHadTime() {
        pacer.sent(20_000)
        now += 5_000
        assertEquals(0, pacer.waitMs())
    }
}
