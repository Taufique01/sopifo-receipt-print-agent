package com.sopifo.printagent.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TC-553: a copied / shared customer message → the "Make order" bar (needs a BD mobile number). */
class CopiedOrderTest {
    @Test fun findsTheNumberInEveryCommonShape() {
        assertEquals("01712345678", CopiedOrder.phoneIn("Rahim\n01712345678\nMirpur 10"))
        assertEquals("01712345678", CopiedOrder.phoneIn("call +880 1712-345678 please"))
        assertEquals("01712345678", CopiedOrder.phoneIn("8801712345678"))
        assertEquals("01712345678", CopiedOrder.phoneIn("017 1234 5678"))
        assertEquals("01912345678", CopiedOrder.phoneIn("নাম: সুমি, ফোন: ০১৯১২৩৪৫৬৭৮"))
    }

    @Test fun ignoresTextWithoutAMobileNumber() {
        assertNull(CopiedOrder.phoneIn("House 12, Road 5, Mirpur 10"))
        assertNull(CopiedOrder.phoneIn("order 9017123456789 total"))
        assertNull(CopiedOrder.phoneIn("01212345678")) // 012 isn't a mobile prefix
        assertNull(CopiedOrder.offerFor("Thanks! See you tomorrow"))
    }

    @Test fun previewIsTheFirstLineThatIsNotTheNumber() {
        assertEquals("Karim Mia", CopiedOrder.previewOf("01712345678\nKarim Mia\nRupnagar"))
        // A one-liner keeps its line (the number is part of it).
        assertEquals("Sumi Akter, 01912345678, Uttara", CopiedOrder.previewOf("Sumi Akter, 01912345678, Uttara"))
        val long = "House 12, Road 5, Block C, Mirpur 10, Dhaka 1216, near the mosque"
        assertTrue(CopiedOrder.previewOf("$long\n01712345678").endsWith("…"))
    }

    @Test fun offerCarriesTextPhoneAndPreview() {
        val offer = CopiedOrder.offerFor("Karim Mia\n01712345678\nRupnagar")!!
        assertEquals("01712345678", offer.phone)
        assertEquals("Karim Mia", offer.preview)
        assertNull(CopiedOrder.offerFor("01712345678 " + "x".repeat(5000)))
    }

    @Test fun onlyRecentCopiesCount() {
        val wall = 1_800_000_000_000L
        val boot = 50_000_000L
        assertTrue(CopiedOrder.isFresh(wall - 60_000, wall, boot))
        assertFalse(CopiedOrder.isFresh(wall - 20 * 60_000, wall, boot))
        // An older Android stamping time since boot.
        assertTrue(CopiedOrder.isFresh(boot - 60_000, wall, boot))
        assertFalse(CopiedOrder.isFresh(boot - 20 * 60_000, wall, boot))
    }
}
