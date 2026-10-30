package com.sopifo.printagent.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkDownscaleTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test fun aThinStrokeSurvivesShrinkingToAThird() {
        // A 1-pixel vertical line in a 30 × 30 page, shrunk to 10 × 10: a third of one dot column.
        val src = IntArray(30 * 30) { i -> if (i % 30 == 13) black else white }
        val out = InkDownscale.downscale(src, 30, 30, 10, 10)
        for (y in 0 until 10) {
            assertEquals(black, out[y * 10 + 4])
            assertEquals(white, out[y * 10 + 3])
            assertEquals(white, out[y * 10 + 5])
        }
    }

    @Test fun aSpeckBelowTheCutoffStaysWhite() {
        // One dark pixel in a 3 × 3 block is 11% ink.
        val src = IntArray(9) { i -> if (i == 4) black else white }
        assertEquals(white, InkDownscale.downscale(src, 3, 3, 1, 1)[0])
    }

    @Test fun outputIsOnlyBlackAndWhite() {
        val src = IntArray(40 * 40) { i -> 0xFF000000.toInt() or ((i % 256) * 0x010101) }
        val out = InkDownscale.downscale(src, 40, 40, 13, 17)
        assertEquals(13 * 17, out.size)
        assertTrue(out.all { it == black || it == white })
    }

    @Test fun sameSizeIsAPlainCutoff() {
        val src = intArrayOf(0xFFC0C0C0.toInt(), 0xFFD0D0D0.toInt()) // 192 → black, 208 → white
        val out = InkDownscale.downscale(src, 2, 1, 2, 1)
        assertEquals(black, out[0])
        assertEquals(white, out[1])
    }

    @Test fun paleBackgroundCountsAsPaperEvenOnDarkest() {
        val tint = IntArray(9) { 0xFFE6E6E6.toInt() } // 230: a tinted box
        assertEquals(white, InkDownscale.downscale(tint, 3, 3, 1, 1, PdfDarkness.DARKEST.cutoff)[0])
    }

    @Test fun darkerSettingsKeepFainterInk() {
        // 2 of 16 pixels black: 12.5% ink (average 223) — white on Normal, black on Darker and Darkest.
        val src = IntArray(16) { i -> if (i == 5 || i == 10) black else white }
        assertEquals(white, InkDownscale.downscale(src, 4, 4, 1, 1, PdfDarkness.NORMAL.cutoff)[0])
        assertEquals(black, InkDownscale.downscale(src, 4, 4, 1, 1, PdfDarkness.DARKER.cutoff)[0])
        assertEquals(black, InkDownscale.downscale(src, 4, 4, 1, 1, PdfDarkness.DARKEST.cutoff)[0])
    }
}
