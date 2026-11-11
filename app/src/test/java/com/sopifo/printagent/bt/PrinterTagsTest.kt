package com.sopifo.printagent.bt

import com.sopifo.printagent.data.db.PrinterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** App 2.1.15: two printers with the same name can be told apart in "Find printer". */
class PrinterTagsTest {
    @Test fun addressTailIsTheLastTwoParts() {
        assertEquals("…A3:F1", PrinterTags.addressTail("66:22:0b:7c:a3:f1"))
        assertEquals("…A3:F1", PrinterTags.addressTail("66:22:0B:7C:A3:F1"))
        assertEquals("", PrinterTags.addressTail(""))
    }

    @Test fun theSavedPrinterIsMarkedInUseByAddressNotName() {
        val saved = listOf(PrinterRole.LABEL to "66:22:0B:7C:A3:F1", PrinterRole.RECEIPT to "11:22:33:44:55:66")
        // Same name, different address: not in use.
        assertTrue(PrinterTags.rolesOf("66:22:0B:7C:00:01", saved).isEmpty())
        assertEquals(listOf(PrinterRole.LABEL), PrinterTags.rolesOf("66:22:0b:7c:a3:f1", saved))
        // One printer doing both jobs.
        val both = listOf(PrinterRole.LABEL to "AA:BB:CC:DD:EE:FF", PrinterRole.RECEIPT to "aa:bb:cc:dd:ee:ff")
        assertEquals(listOf(PrinterRole.LABEL, PrinterRole.RECEIPT), PrinterTags.rolesOf("AA:BB:CC:DD:EE:FF", both))
    }
}
