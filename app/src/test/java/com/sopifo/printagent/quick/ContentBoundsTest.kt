package com.sopifo.printagent.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentBoundsTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun page(w: Int, h: Int, ink: (Int, Int) -> Boolean) =
        IntArray(w * h) { i -> if (ink(i % w, i / w)) black else white }

    @Test fun blankPageHasNoContent() {
        assertNull(ContentBounds.find(page(100, 100) { _, _ -> false }, 100, 100))
    }

    @Test fun boxInABigMarginIsFoundWithAOnePercentPad() {
        // A4-ish 200 × 280 page, ink from (60,100) to (139,149); pad = 1% of 200 = 2.
        val b = ContentBounds.find(page(200, 280) { x, y -> x in 60..139 && y in 100..149 }, 200, 280)!!
        assertEquals(58, b.left)
        assertEquals(98, b.top)
        assertEquals(84, b.width)
        assertEquals(54, b.height)
    }

    @Test fun padStopsAtThePageEdge() {
        val b = ContentBounds.find(page(100, 100) { x, y -> x == 0 && y == 99 }, 100, 100)!!
        assertEquals(0, b.left)
        assertEquals(98, b.top)
        assertEquals(2, b.width)
        assertEquals(2, b.height)
    }

    @Test fun lightGreySpecksAreNotInk() {
        val grey = 0xFFF0F0F0.toInt() // 240 > threshold
        val pixels = IntArray(50 * 50) { grey }
        assertNull(ContentBounds.find(pixels, 50, 50))
    }
}
