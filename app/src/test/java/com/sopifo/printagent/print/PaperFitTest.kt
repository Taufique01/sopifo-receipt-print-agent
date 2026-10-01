package com.sopifo.printagent.print

import org.junit.Assert.assertEquals
import org.junit.Test

class PaperFitTest {
    private val maxW = PrintDataBuilder.MAX_ESC_POS_WIDTH
    private val maxH = PrintDataBuilder.MAX_RECEIPT_HEIGHT

    @Test fun matchingPaperPrintsAsIs() {
        assertEquals(PaperFit(576, 1000), PaperFit.plan(576, 1000, maxW, maxH, paperWidth = 576))
        assertEquals(PaperFit(384, 1000), PaperFit.plan(384, 1000, maxW, maxH, paperWidth = 384))
    }

    @Test fun eightyMmReceiptShrinksOntoFiftyEightMmPaper() {
        // 576 → 384 is 2/3; the height follows so nothing is stretched.
        assertEquals(PaperFit(384, 1000), PaperFit.plan(576, 1500, maxW, maxH, paperWidth = 384))
    }

    @Test fun narrowerReceiptIsNeverPadded() {
        // 58 mm receipt with the app set to 80 mm: unchanged, so a 58 mm printer still fits it.
        assertEquals(PaperFit(384, 1000), PaperFit.plan(384, 1000, maxW, maxH, paperWidth = 576))
    }

    @Test fun unknownPaperKeepsImageAsReceived() {
        assertEquals(PaperFit(384, 1000), PaperFit.plan(384, 1000, maxW, maxH, paperWidth = null))
        assertEquals(PaperFit(576, 1000), PaperFit.plan(576, 1000, maxW, maxH, paperWidth = null))
    }

    @Test fun printHeadLimitStillAppliesWithoutPaperWidth() {
        assertEquals(PaperFit(576, 500), PaperFit.plan(1152, 1000, maxW, maxH, paperWidth = null))
    }

    @Test fun overlongReceiptIsScaledToMaxHeight() {
        val fit = PaperFit.plan(576, maxH * 2, maxW, maxH, paperWidth = 576)
        assertEquals(PaperFit(288, maxH), fit)
    }
}
