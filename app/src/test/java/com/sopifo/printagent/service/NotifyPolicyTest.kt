package com.sopifo.printagent.service

import com.sopifo.printagent.data.db.PrintRunEntity
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.data.db.RunState
import com.sopifo.printagent.print.PrinterState
import com.sopifo.printagent.runs.PrintRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One print from Chrome / another app must not fill the notification bar. */
class NotifyPolicyTest {
    private fun run(source: String, state: RunState, printed: Int, failed: Int) =
        PrintRunEntity("r", "Page", source, PrinterRole.LABEL, state, printed + failed, printed = printed, failed = failed, createdAt = 0, updatedAt = 0)

    @Test fun statusNeverSaysDisconnectedBetweenPrints() {
        assertEquals("Ready to print", NotifyPolicy.statusText(online = true, receipt = PrinterState.NOT_CONFIGURED, label = PrinterState.DISCONNECTED))
        assertEquals("Ready to print", NotifyPolicy.statusText(online = true, receipt = PrinterState.CONNECTED, label = PrinterState.NOT_CONFIGURED))
        assertEquals("Ready to print · offline", NotifyPolicy.statusText(online = false, receipt = null, label = PrinterState.DISCONNECTED))
        assertEquals("Set up a printer in Sopifo", NotifyPolicy.statusText(online = true, receipt = PrinterState.NOT_CONFIGURED, label = null))
        assertEquals("Bluetooth is off", NotifyPolicy.statusText(online = true, receipt = null, label = PrinterState.BLUETOOTH_OFF))
    }

    @Test fun otherAppPrintsShowNothingWhenTheyWork() {
        assertTrue(NotifyPolicy.quietProgress(run(PrintRunner.SOURCE_OTHER_APPS, RunState.RUNNING, 0, 0)))
        assertFalse(NotifyPolicy.showResult(run(PrintRunner.SOURCE_OTHER_APPS, RunState.DONE, 2, 0)))
        // Something went wrong: one notice.
        assertTrue(NotifyPolicy.showResult(run(PrintRunner.SOURCE_OTHER_APPS, RunState.STOPPED, 0, 0)))
        assertTrue(NotifyPolicy.showResult(run(PrintRunner.SOURCE_OTHER_APPS, RunState.DONE, 1, 1)))
    }

    @Test fun appRunsKeepTheirProgressAndResult() {
        assertFalse(NotifyPolicy.quietProgress(run("orders", RunState.RUNNING, 0, 0)))
        assertTrue(NotifyPolicy.showResult(run("orders", RunState.DONE, 3, 0)))
    }
}
