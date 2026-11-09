package com.sopifo.printagent.printservice

import com.sopifo.printagent.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Print → Sopifo refusals: the real reason and the button that fixes it (not "Printer error"). */
class PrintRefusalTest {
    @Test fun freePrintsUsedUpAsksToLogIn() {
        val n = PrintRefusal.notice(PrintRefusal.Reason.LOGIN, "en")
        assertEquals("Sopifo couldn't print", n.title)
        assertTrue(n.text.startsWith("23 free prints"))
        assertEquals("Log in", n.button)
        assertEquals(MainActivity.OPEN_LOGIN, n.open)
    }

    @Test fun noLabelPrinterOpensPrinters() {
        val n = PrintRefusal.notice(PrintRefusal.Reason.NO_LABEL_PRINTER, "bn")
        assertEquals("Sopifo প্রিন্ট করতে পারেনি", n.title)
        assertEquals("প্রিন্টার খুলুন", n.button)
        assertEquals(MainActivity.OPEN_PRINTERS, n.open)
    }

    @Test fun otherErrorsShowTheirMessageAndOpenTheApp() {
        val n = PrintRefusal.notice(PrintRefusal.Reason.OTHER, "en", "PDF is damaged")
        assertEquals("PDF is damaged", n.text)
        assertEquals("Open Sopifo", n.button)
        assertNull(n.open)
        assertEquals("Something went wrong. Try again.", PrintRefusal.notice(PrintRefusal.Reason.OTHER, "en", " ").text)
    }
}
