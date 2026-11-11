package com.sopifo.printagent.quick

import com.sopifo.printagent.data.db.PrinterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * App 2.1.13: smart shrink is off for Print → Sopifo (the spooler) and off by default for PDFs
 * in Quick print / Share; Quick print / Share starts on the printer, label size and Fit / Fill
 * (image and PDF, 2.1.14) used last.
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

    @Test fun remembersFitOrFillForImagesAndPdfs() {
        val c = QuickPrintChoices.restore("LABEL", "S3X2", QuickLabelSize.S2X3, savedImageFit = "FILL", savedPdfFit = "FILL")
        assertEquals(ImageFit.FILL, c.imageFit)
        assertEquals(ImageFit.FILL, c.pdfFit)
        val fresh = QuickPrintChoices.restore(null, null, QuickLabelSize.S2X3)
        assertEquals(ImageFit.FIT, fresh.imageFit)
        assertEquals(ImageFit.FIT, fresh.pdfFit)
        val odd = QuickPrintChoices.restore(null, null, QuickLabelSize.S2X3, savedImageFit = "STRETCH", savedPdfFit = "")
        assertEquals(ImageFit.FIT, odd.imageFit)
        assertEquals(ImageFit.FIT, odd.pdfFit)
    }
}
