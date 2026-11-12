package com.sopifo.printagent.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ＋ opens Add order (POS in POS mode). After Order / Close its page moves on to Orders, so coming
 * back to ＋ from another tab loads Add order again; an Add order still in progress is kept.
 */
class PlusTabTest {
    private val base = "https://sopifo.com"

    @Test fun tappingPlusWhileOnItStartsOver() {
        assertTrue(PlusTab.shouldReload(alreadyOnPlus = true, currentUrl = "$base/dashboard/orders/new", plusPath = "dashboard/orders/new"))
    }

    @Test fun movedOnToOrdersLoadsAddOrderAgain() {
        assertTrue(PlusTab.shouldReload(false, "$base/dashboard/orders", "dashboard/orders/new"))
        assertTrue(PlusTab.shouldReload(false, "$base/dashboard/orders?status=DRAFT", "dashboard/orders/new"))
        assertTrue(PlusTab.shouldReload(false, "$base/dashboard/orders/cmw123abc", "dashboard/orders/new"))
        assertTrue(PlusTab.shouldReload(false, "$base/dashboard/orders/newsletter", "dashboard/orders/new"))
    }

    @Test fun addOrderInProgressIsKept() {
        assertFalse(PlusTab.shouldReload(false, "$base/dashboard/orders/new", "dashboard/orders/new"))
        assertFalse(PlusTab.shouldReload(false, "$base/dashboard/orders/new/", "dashboard/orders/new"))
        assertFalse(PlusTab.shouldReload(false, "$base/dashboard/orders/new?draft=1", "dashboard/orders/new"))
        // Still loading: nothing to compare yet.
        assertFalse(PlusTab.shouldReload(false, null, "dashboard/orders/new"))
    }

    @Test fun posModeKeepsThePosAndReloadsElsewhere() {
        assertFalse(PlusTab.shouldReload(false, "$base/pos", "pos"))
        assertFalse(PlusTab.shouldReload(false, "$base/pos/held", "pos"))
        assertTrue(PlusTab.shouldReload(false, "$base/dashboard/orders", "pos"))
        // The shop switched to POS mode since: the old Add order page isn't the POS.
        assertTrue(PlusTab.shouldReload(false, "$base/dashboard/orders/new", "pos"))
    }
}
