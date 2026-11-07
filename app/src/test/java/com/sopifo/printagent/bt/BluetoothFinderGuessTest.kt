package com.sopifo.printagent.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Add printer" guesses: which devices look like printers, labels vs receipts, 58 vs 80 mm. */
class BluetoothFinderGuessTest {
    @Test
    fun printerNamesAreRecognised() {
        listOf("XP-365B", "Xprinter XP-420B", "MTP-II", "BlueTooth Printer", "RPP02N", "POS-58", "GP-1324D", "MPT-II").forEach {
            assertTrue(it, BluetoothFinder.looksLikePrinter(it))
        }
        listOf("Galaxy Buds2", "JBL Flip 5", "Mi Band 6", "Car Audio").forEach {
            assertFalse(it, BluetoothFinder.looksLikePrinter(it))
        }
    }

    @Test
    fun labelPrintersAreLabels() {
        assertTrue(BluetoothFinder.guessLabelPrinter("XP-365B"))
        assertTrue(BluetoothFinder.guessLabelPrinter("Xprinter XP-420B"))
        assertTrue(BluetoothFinder.guessLabelPrinter("TSC TE200 Label"))
        assertFalse(BluetoothFinder.guessLabelPrinter("MTP-II"))
        assertFalse(BluetoothFinder.guessLabelPrinter("POS-58"))
    }

    @Test
    fun paperWidthFromTheName() {
        assertEquals(80, BluetoothFinder.guessPaperMm("POS-80 Printer"))
        assertEquals(58, BluetoothFinder.guessPaperMm("MTP-II"))
        assertEquals(58, BluetoothFinder.guessPaperMm("POS-58"))
    }
}
