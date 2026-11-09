package com.sopifo.printagent.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** POS and Add order show without the app's top and bottom bars. */
class FullScreenPageTest {
    @Test fun posAndAddOrderAreFullScreen() {
        assertTrue(fullScreenPage("/pos"))
        assertTrue(fullScreenPage("/pos/today"))
        assertTrue(fullScreenPage("/dashboard/orders/new"))
        assertTrue(fullScreenPage("/dashboard/orders/new/"))
    }

    @Test fun otherPagesKeepTheBars() {
        assertFalse(fullScreenPage("/dashboard"))
        assertFalse(fullScreenPage("/dashboard/orders"))
        assertFalse(fullScreenPage("/dashboard/orders/abc123"))
        assertFalse(fullScreenPage("/positions"))
    }
}
