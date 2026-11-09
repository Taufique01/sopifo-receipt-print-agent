package com.sopifo.printagent.ui.print

/** What the phone remembers about printer setup (AppSettings), carried over at login. */
data class SetupFlags(
    /** Step 2 confirmed (label size + gap). */
    val sizeConfirmed: Boolean = false,
    val testPrinted: Boolean = false,
    /** "Did it print?" was answered ❌ and not fixed since. */
    val testFailed: Boolean = false,
    val receiptSkipped: Boolean = false,
    /** "I only print receipts": the label steps don't apply. */
    val labelSkipped: Boolean = false,
    /** A print arrived through "Sopifo" in Android's Print menu. */
    val printServiceUsed: Boolean = false,
    /** The step-by-step guide was finished once: later visits show the checklist. */
    val firstSetupDone: Boolean = false,
    /** Prints through the Print menu while logged out (a few are free). */
    val freeServicePrints: Int = 0,
)

enum class StepState { DONE, MISSING, SKIPPED }

enum class SetupStep { LABEL, SIZE, TEST, RECEIPT, SERVICE }

/** What the phone knows about its printers right now. */
data class PrinterFacts(
    val hasLabel: Boolean,
    /** null = not known yet (no check since start). */
    val labelReachable: Boolean?,
    val hasReceipt: Boolean,
)

/**
 * The printer setup checklist (2.1.1): green done, red missing (needed), grey skipped
 * (optional). A step turns red again when something breaks: printer unreachable, the test
 * answered ❌. Pure, so it is unit-tested.
 */
object SetupChecklist {
    /** Prints through Android's Print menu before logging in (3, raised by 10 on 2026-11-08). */
    const val FREE_SERVICE_PRINTS = 23

    fun freeLeft(freeUsed: Int) = (FREE_SERVICE_PRINTS - freeUsed).coerceAtLeast(0)

    fun states(p: PrinterFacts, f: SetupFlags): Map<SetupStep, StepState> {
        if (f.labelSkipped && !p.hasLabel) {
            // Receipts only: the label steps don't apply; the receipt printer becomes the need.
            return mapOf(
                SetupStep.LABEL to StepState.SKIPPED,
                SetupStep.SIZE to StepState.SKIPPED,
                SetupStep.TEST to StepState.SKIPPED,
                SetupStep.RECEIPT to if (p.hasReceipt) StepState.DONE else StepState.MISSING,
                SetupStep.SERVICE to StepState.SKIPPED,
            )
        }
        return mapOf(
            SetupStep.LABEL to if (p.hasLabel && p.labelReachable != false) StepState.DONE else StepState.MISSING,
            SetupStep.SIZE to if (p.hasLabel && f.sizeConfirmed) StepState.DONE else StepState.MISSING,
            SetupStep.TEST to if (f.testPrinted && !f.testFailed) StepState.DONE else StepState.MISSING,
            SetupStep.RECEIPT to if (p.hasReceipt) StepState.DONE else StepState.SKIPPED,
            SetupStep.SERVICE to if (f.printServiceUsed) StepState.DONE else StepState.MISSING,
        )
    }

    fun anyMissing(states: Map<SetupStep, StepState>) = states.values.any { it == StepState.MISSING }

    fun doneCount(states: Map<SetupStep, StepState>) = states.values.count { it == StepState.DONE }

    /** The first step still missing (where "Fix" or the guide goes), or null. */
    fun firstMissing(states: Map<SetupStep, StepState>): SetupStep? = SetupStep.entries.firstOrNull { states[it] == StepState.MISSING }

    /** A print through "Sopifo" in the Print menu: always when logged in, else the first few. */
    fun allowServicePrint(loggedIn: Boolean, freeUsed: Int) = loggedIn || freeUsed < FREE_SERVICE_PRINTS
}
