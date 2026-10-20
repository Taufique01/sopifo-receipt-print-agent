package com.sopifo.printagent.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerLayoutTest {
    private val sticker = StickerLayout(heightMm = 75, gapMm = 3)

    @Test
    fun everyPrintIsOneStickerPlusGap() {
        // 78 mm at 203 dpi.
        assertEquals(623, sticker.pitchDots)
        assertEquals(sticker.pitchDots, sticker.topDots + 448 + sticker.tailDots(448))
    }

    @Test
    fun theDashboardStickerImageFits() {
        // The dashboard renders 50 × 75 mm stickers 448 dots (56 mm) tall.
        assertTrue(sticker.maxContentDots >= 448)
    }

    @Test
    fun offsetMovesContentDownWithoutChangingThePitch() {
        val lower = sticker.copy(offsetTenths = 30)
        assertEquals(24, lower.topDots)
        assertEquals(sticker.pitchDots, lower.topDots + 100 + lower.tailDots(100))
        assertEquals(sticker.maxContentDots - 24, lower.maxContentDots)
    }

    @Test
    fun pitchAdjustCorrectsTheFeedPerSticker() {
        // +0.5 mm per sticker = 4 dots.
        assertEquals(sticker.pitchDots + 4, sticker.copy(pitchAdjustHundredths = 50).pitchDots)
        assertEquals(sticker.pitchDots - 4, sticker.copy(pitchAdjustHundredths = -50).pitchDots)
    }

    @Test
    fun tooTallContentNeverFeedsBackwards() {
        assertEquals(0, sticker.tailDots(10_000))
    }

    @Test
    fun paddingPutsTheContentAtTheOffsetAndFillsThePitch() {
        val content = MonoBitmap(8, 2, byteArrayOf(0xFF.toByte(), 0x0F))
        val printed = content.padded(top = 3, totalHeight = 10)
        assertEquals(10, printed.height)
        assertEquals(10, printed.data.size)
        assertTrue(printed.data.withIndex().all { (i, b) -> if (i == 3) b == 0xFF.toByte() else if (i == 4) b == 0x0F.toByte() else b == 0.toByte() })
        assertTrue(MonoBitmap.blank(384, 8).data.all { it == 0.toByte() })
    }
}
