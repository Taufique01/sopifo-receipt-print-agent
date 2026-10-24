package com.sopifo.printagent.quick

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePlacementTest {
    @Test fun fitShowsTheWholeImageCentredOnTheLabel() {
        // 1000 × 500 photo on a 400 × 600 label with a 16-dot margin: 368 wide, centred.
        val r = ImagePlacement.plan(1000, 500, 400, 600, ImageFit.FIT, zoom = 1f, margin = 16)
        assertEquals(16, r.left)
        assertEquals(368, r.width)
        assertEquals(184, r.height)
        assertEquals((600 - 184) / 2, r.top)
    }

    @Test fun fillCoversTheLabelAndCropsTheRest() {
        val r = ImagePlacement.plan(1000, 500, 400, 600, ImageFit.FILL, zoom = 1f, margin = 0)
        assertEquals(600, r.height)
        assertEquals(1200, r.width)
        assertEquals(-400, r.left) // centred: the sides are cut off
    }

    @Test fun zoomScalesAroundTheCentre() {
        val fit = ImagePlacement.plan(400, 400, 400, 400, ImageFit.FIT, zoom = 1f, margin = 0)
        val zoomed = ImagePlacement.plan(400, 400, 400, 400, ImageFit.FIT, zoom = 2f, margin = 0)
        val small = ImagePlacement.plan(400, 400, 400, 400, ImageFit.FIT, zoom = 0.5f, margin = 0)
        assertEquals(400, fit.width)
        assertEquals(800, zoomed.width)
        assertEquals(-200, zoomed.left)
        assertEquals(200, small.width)
        assertEquals(100, small.left)
    }

    @Test fun rollsFollowTheImageHeightAtThePaperWidth() {
        // No fixed height: the page is as tall as the scaled image.
        val r = ImagePlacement.plan(1000, 2000, 384, null, ImageFit.FIT, zoom = 1f, margin = 0)
        assertEquals(384, r.width)
        assertEquals(768, r.height)
        assertEquals(768, r.pageHeight)
    }
}
