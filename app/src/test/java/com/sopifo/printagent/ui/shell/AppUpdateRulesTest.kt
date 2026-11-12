package com.sopifo.printagent.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TC-542: below Required → update to continue; below Latest → the Update bar (✕ = 3 days for that version). */
class AppUpdateRulesTest {
    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test fun blocksOnlyBelowRequired() {
        assertTrue(AppUpdateRules.mustUpdate(AppUpdateInfo("2.1.20", "2.1.17"), "2.1.16"))
        assertTrue(AppUpdateRules.mustUpdate(AppUpdateInfo("2.1.20", "2.1.10"), "2.1.9"))
        assertFalse(AppUpdateRules.mustUpdate(AppUpdateInfo("2.1.20", "2.1.17"), "2.1.17"))
        assertFalse(AppUpdateRules.mustUpdate(AppUpdateInfo("2.1.20", "2.1.17"), "2.1.18"))
    }

    @Test fun noRequiredOrNoAnswerNeverBlocks() {
        assertFalse(AppUpdateRules.mustUpdate(AppUpdateInfo("2.1.20", "0"), "2.1.17"))
        assertFalse(AppUpdateRules.mustUpdate(AppUpdateInfo("2.1.20", null), "2.1.17"))
        assertFalse(AppUpdateRules.mustUpdate(null, "2.1.17"))
    }

    @Test fun barShowsBelowLatest() {
        assertTrue(AppUpdateRules.showBar(AppUpdateInfo("2.1.18", "0"), "2.1.17", null, 0, now))
        assertFalse(AppUpdateRules.showBar(AppUpdateInfo("2.1.17", "0"), "2.1.17", null, 0, now))
        assertFalse(AppUpdateRules.showBar(AppUpdateInfo("2.1.16", "0"), "2.1.17", null, 0, now))
        assertFalse(AppUpdateRules.showBar(null, "2.1.17", null, 0, now))
    }

    @Test fun closingHidesItThreeDaysForThatVersion() {
        val info = AppUpdateInfo("2.1.18", "0")
        assertFalse(AppUpdateRules.showBar(info, "2.1.17", "2.1.18", now - day, now))
        assertTrue(AppUpdateRules.showBar(info, "2.1.17", "2.1.18", now - 3 * day, now))
        // A newer version than the one closed shows at once.
        assertTrue(AppUpdateRules.showBar(AppUpdateInfo("2.1.19", "0"), "2.1.17", "2.1.18", now - day, now))
    }
}
