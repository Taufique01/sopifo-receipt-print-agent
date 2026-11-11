package com.sopifo.printagent.ui

import com.sopifo.printagent.ui.shell.SizeSync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App 2.1.15: one label size for the app and the website. A size picked in the app goes to the
 * website right away; the website's size is checked again when Orders / Couriers open, so labels
 * (drawn by the website) never print at the old size.
 */
class SizeSyncPolicyTest {
    @Test fun ordersAndCouriersCheckTheSize() {
        assertTrue(SizeSync.dueOnPage("/dashboard/couriers", null, pending = false, sinceLastMs = Long.MAX_VALUE))
        assertTrue(SizeSync.dueOnPage("/dashboard/orders/abc123", null, pending = false, sinceLastMs = Long.MAX_VALUE))
        assertFalse(SizeSync.dueOnPage("/dashboard/products", null, pending = false, sinceLastMs = Long.MAX_VALUE))
        assertFalse(SizeSync.dueOnPage("/pos", null, pending = false, sinceLastMs = Long.MAX_VALUE))
    }

    @Test fun notOnEveryTabChangeButAlwaysWhenSomethingChanged() {
        // Couriers tabs change the URL: checked at most every 15 s…
        assertFalse(SizeSync.dueOnPage("/dashboard/couriers", "/dashboard/couriers", pending = false, sinceLastMs = 5_000))
        assertTrue(SizeSync.dueOnPage("/dashboard/couriers", "/dashboard/couriers", pending = false, sinceLastMs = 15_000))
        // …but at once after Cloud Print settings (the size may have changed there)…
        assertTrue(SizeSync.dueOnPage("/dashboard/couriers", "/dashboard/print", pending = false, sinceLastMs = 1_000))
        // …and while a size picked in the app still has to reach the website.
        assertTrue(SizeSync.dueOnPage("/dashboard/couriers", "/dashboard/couriers", pending = true, sinceLastMs = 1_000))
    }

    @Test fun preDrawnLabelsAreDroppedWhenTheWebsiteSizeChanged() {
        assertFalse(SizeSync.websiteChanged(lastSeen = "3x2in", now = "3x2in"))
        assertTrue(SizeSync.websiteChanged(lastSeen = "3x2in", now = "2x3in"))
        // First read since the app started: compared with the app's own size.
        assertFalse(SizeSync.websiteChanged(lastSeen = null, now = "3x2in", appWebId = "3x2in"))
        assertTrue(SizeSync.websiteChanged(lastSeen = null, now = "75x100mm", appWebId = "3x2in"))
        assertFalse(SizeSync.websiteChanged(lastSeen = "3x2in", now = null))
    }
}
