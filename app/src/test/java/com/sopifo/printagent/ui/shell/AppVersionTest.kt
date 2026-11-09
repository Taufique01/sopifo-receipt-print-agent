package com.sopifo.printagent.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** About → updates: "New version available" only when the website's version is really newer. */
class AppVersionTest {
    @Test fun comparesNumberByNumber() {
        assertTrue(AppVersion.isNewer("2.1.10", "2.1.9"))
        assertTrue(AppVersion.isNewer("2.2", "2.1.9"))
        assertTrue(AppVersion.isNewer("3.0.0", "2.9.9"))
        assertFalse(AppVersion.isNewer("2.1.9", "2.1.10"))
        assertFalse(AppVersion.isNewer("2.1.9", "2.1.9"))
        assertFalse(AppVersion.isNewer("2.1", "2.1.0"))
    }

    @Test fun oddValuesAreNeverNewer() {
        assertFalse(AppVersion.isNewer(null, "2.1.9"))
        assertFalse(AppVersion.isNewer("", "2.1.9"))
        assertFalse(AppVersion.isNewer("abc", "2.1.9"))
        assertTrue(AppVersion.isNewer("2.1.10", "2.1.9-debug"))
    }
}
