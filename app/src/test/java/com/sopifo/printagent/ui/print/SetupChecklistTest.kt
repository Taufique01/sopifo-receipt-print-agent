package com.sopifo.printagent.ui.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Printer setup checklist (2.1.1): green done, red missing, grey skipped. */
class SetupChecklistTest {
    private val none = PrinterFacts(hasLabel = false, labelReachable = null, hasReceipt = false)
    private val label = PrinterFacts(hasLabel = true, labelReachable = true, hasReceipt = false)

    @Test fun newPhoneHasEveryNeededStepRedAndReceiptGrey() {
        val s = SetupChecklist.states(none, SetupFlags())
        assertEquals(StepState.MISSING, s[SetupStep.LABEL])
        assertEquals(StepState.MISSING, s[SetupStep.SIZE])
        assertEquals(StepState.MISSING, s[SetupStep.TEST])
        assertEquals(StepState.SKIPPED, s[SetupStep.RECEIPT])
        assertEquals(StepState.MISSING, s[SetupStep.SERVICE])
        assertEquals(SetupStep.LABEL, SetupChecklist.firstMissing(s))
    }

    @Test fun finishedSetupIsAllGreenOrGrey() {
        val f = SetupFlags(sizeConfirmed = true, testPrinted = true, printServiceUsed = true, firstSetupDone = true)
        val s = SetupChecklist.states(label, f)
        assertFalse(SetupChecklist.anyMissing(s))
        assertEquals(4, SetupChecklist.doneCount(s))
        assertNull(SetupChecklist.firstMissing(s))
    }

    @Test fun stepsTurnRedAgainWhenSomethingBreaks() {
        val f = SetupFlags(sizeConfirmed = true, testPrinted = true, printServiceUsed = true)
        val unreachable = SetupChecklist.states(label.copy(labelReachable = false), f)
        assertEquals(StepState.MISSING, unreachable[SetupStep.LABEL])
        val failedTest = SetupChecklist.states(label, f.copy(testFailed = true))
        assertEquals(StepState.MISSING, failedTest[SetupStep.TEST])
        val removedPrinter = SetupChecklist.states(none, f)
        assertEquals(StepState.MISSING, removedPrinter[SetupStep.SIZE])
    }

    @Test fun receiptsOnlySkipsTheLabelStepsAndNeedsAReceiptPrinter() {
        val f = SetupFlags(labelSkipped = true)
        val s = SetupChecklist.states(none, f)
        assertEquals(StepState.SKIPPED, s[SetupStep.LABEL])
        assertEquals(StepState.SKIPPED, s[SetupStep.SERVICE])
        assertEquals(StepState.MISSING, s[SetupStep.RECEIPT])
        val done = SetupChecklist.states(none.copy(hasReceipt = true), f)
        assertFalse(SetupChecklist.anyMissing(done))
    }

    @Test fun thirteenFreePrintsFromOtherAppsBeforeLogin() {
        assertTrue(SetupChecklist.allowServicePrint(loggedIn = false, freeUsed = 0))
        assertTrue(SetupChecklist.allowServicePrint(loggedIn = false, freeUsed = 12))
        assertFalse(SetupChecklist.allowServicePrint(loggedIn = false, freeUsed = 13))
        assertEquals(10, SetupChecklist.freeLeft(3))
        assertEquals(0, SetupChecklist.freeLeft(20))
        assertTrue(SetupChecklist.allowServicePrint(loggedIn = true, freeUsed = 50))
    }
}
