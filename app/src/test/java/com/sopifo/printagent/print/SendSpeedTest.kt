package com.sopifo.printagent.print

import com.sopifo.printagent.data.db.PrinterProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendSpeedTest {
    @Test
    fun fastKeepsThePacingUsedBefore() {
        // 512-byte writes every 8 ms: what all printers used before the setting existed.
        assertEquals(512, SendSpeed.chunkSize(SendSpeed.FAST_KBPS))
        assertEquals(8L, SendSpeed.chunkDelayMs(SendSpeed.FAST_KBPS))
    }

    @Test
    fun labelPrintersDefaultToSafeReceiptsToFast() {
        assertEquals(SendSpeed.SAFE_KBPS, SendSpeed.defaultFor(PrinterProtocol.TSPL))
        assertEquals(SendSpeed.FAST_KBPS, SendSpeed.defaultFor(PrinterProtocol.ESC_POS))
    }

    @Test
    fun safeSpeedSendsAboutEightKilobytesPerSecond() {
        // A 1.9 × 2.9 in label: 384 × 584 dots, 1 bit each.
        val bytes = 384 / 8 * 584
        val ms = SendSpeed.sendMs(bytes, SendSpeed.SAFE_KBPS)
        val kbps = bytes / 1024.0 / (ms / 1000.0)
        assertTrue("got $kbps KB/s", kbps in 7.0..8.5)
        assertTrue("about 3.5 s, got $ms ms", ms in 3_000L..4_000L)
    }

    @Test
    fun everyStepIsMonotonicAndWithinRange() {
        var last = Long.MAX_VALUE
        for (kbps in SendSpeed.STEPS) {
            val ms = SendSpeed.sendMs(30_000, kbps)
            assertTrue("$kbps KB/s should be faster than the step below", ms <= last)
            last = ms
        }
    }

    @Test
    fun watchdogAllowsTheSlowestSend() {
        val bytes = 100_000
        assertTrue(SendSpeed.writeTimeoutMs(bytes, 4) > SendSpeed.sendMs(bytes, 4))
    }
}
