package com.sopifo.printagent.quick

import com.sopifo.printagent.data.db.PrinterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * App 2.1.13: smart shrink is off for Print → Sopifo (the spooler) and off by default for PDFs
 * in Quick print / Share; Quick print / Share starts on the printer and label size used last.
 */
class QuickPrintChoicesTest {
    @Test fun spoolerDoesNotSmartShrink() {
        assertFalse(QuickPrintChoices.SPOOLER_SMART_SHRINK)
    }

    @Test fun pdfSmartShrinkIsOffByDefault() {
        assertFalse(QuickPrintChoices.PDF_SMART_DEFAULT)
    }

    @Test fun startsOnTheLastPrinterAndSize() {
        val c = QuickPrintChoices.restore(savedRole = "LABEL", savedSize = "S3X2", fallbackSize = QuickLabelSize.S2X3)
        assertEquals(PrinterRole.LABEL, c.role)
        assertEquals(QuickLabelSize.S3X2, c.labelSize)
    }

    @Test fun nothingSavedStartsOnReceiptAndThePrintersSize() {
        val c = QuickPrintChoices.restore(savedRole = null, savedSize = null, fallbackSize = QuickLabelSize.S2X3)
        assertEquals(PrinterRole.RECEIPT, c.role)
        assertEquals(QuickLabelSize.S2X3, c.labelSize)
    }

    @Test fun unknownSavedValuesFallBack() {
        val c = QuickPrintChoices.restore(savedRole = "PRINTER", savedSize = "S9X9", fallbackSize = QuickLabelSize.S58X40)
        assertEquals(PrinterRole.RECEIPT, c.role)
        assertEquals(QuickLabelSize.S58X40, c.labelSize)
    }
}
