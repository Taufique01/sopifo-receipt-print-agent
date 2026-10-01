package com.sopifo.printagent.print

import com.sopifo.printagent.data.db.PrinterRole
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonoBitmapTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val transparentBlack = 0x00000000

    @Test fun thresholdsAndPacksMsbFirst() {
        // 10 px wide: black, white, black ... -> 1010101010 -> 0xAA, 0x80
        val pixels = IntArray(10) { if (it % 2 == 0) black else white }
        val mono = MonoBitmap.fromArgb(10, 1, pixels)
        assertEquals(2, mono.bytesPerRow)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x80.toByte()), mono.data)
        assertTrue(mono.isBlack(0, 0))
        assertFalse(mono.isBlack(1, 0))
    }

    @Test fun transparentPixelsAreWhitePaper() {
        val mono = MonoBitmap.fromArgb(8, 1, IntArray(8) { transparentBlack })
        assertArrayEquals(byteArrayOf(0), mono.data)
    }

    @Test fun midGreyUsesLuminance() {
        assertTrue(MonoBitmap.isDark(0xFF404040.toInt(), 128))
        assertFalse(MonoBitmap.isDark(0xFFC0C0C0.toInt(), 128))
        // Pure blue is dark (low luminance), pure yellow is light.
        assertTrue(MonoBitmap.isDark(0xFF0000FF.toInt(), 128))
        assertFalse(MonoBitmap.isDark(0xFFFFFF00.toInt(), 128))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmpty() {
        MonoBitmap(0, 0, ByteArray(0))
    }
}

class EscPosEncoderTest {
    private fun solid(width: Int, height: Int) = MonoBitmap(width, height, ByteArray(((width + 7) / 8) * height) { 0xFF.toByte() })

    @Test fun headerRasterFeedAndCut() {
        val bytes = EscPosEncoder.encode(solid(16, 2), feedLines = 3, cut = true)
        // ESC @, then ESC a 1 (printer centres narrower images)
        assertArrayEquals(byteArrayOf(0x1B, 0x40, 0x1B, 0x61, 0x01), bytes.copyOfRange(0, 5))
        // GS v 0 m xL xH yL yH
        assertArrayEquals(byteArrayOf(0x1D, 0x76, 0x30, 0x00, 2, 0, 2, 0), bytes.copyOfRange(5, 13))
        // 4 data bytes, then ESC d 3, then GS V B 0
        assertArrayEquals(byteArrayOf(0x1B, 0x64, 3, 0x1D, 0x56, 0x42, 0x00), bytes.copyOfRange(17, 24))
        assertEquals(24, bytes.size)
    }

    @Test fun tallImagesAreSplitIntoBands() {
        val height = EscPosEncoder.BAND_HEIGHT * 2 + 10
        val bytes = EscPosEncoder.encode(solid(8, height), cut = false)
        val headers = (0 until bytes.size - 3).count { bytes[it] == 0x1D.toByte() && bytes[it + 1] == 0x76.toByte() && bytes[it + 2] == 0x30.toByte() }
        assertEquals(3, headers)
        // 5 init + 3*8 headers + data + 3 feed
        assertEquals(5 + 3 * 8 + height + 3, bytes.size)
    }

    @Test fun copiesAreClamped() {
        val one = EscPosEncoder.encode(solid(8, 1), copies = 1, cut = false).size
        val many = EscPosEncoder.encode(solid(8, 1), copies = 999, cut = false).size
        assertEquals(5 + (one - 5) * MAX_COPIES, many)
    }
}

class TsplEncoderTest {
    @Test fun bitmapIsInvertedAndFramedByCommands() {
        val mono = MonoBitmap(8, 2, byteArrayOf(0xF0.toByte(), 0x00))
        val bytes = TsplEncoder.encode(mono, widthMm = 50, heightMm = 30, gapMm = 2, copies = 2)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("SIZE 50 mm,30 mm\r\nGAP 2 mm,0 mm\r\n"))
        val marker = "BITMAP 0,0,1,2,0,"
        val start = text.indexOf(marker) + marker.length
        assertEquals(0x0F, bytes[start].toInt() and 0xFF)
        assertEquals(0xFF, bytes[start + 1].toInt() and 0xFF)
        assertTrue(text.endsWith("\r\nPRINT 1,2\r\n"))
    }
}

class JobTypeTest {
    @Test fun routesEveryJobTypeToTheRightPrinter() {
        assertEquals(PrinterRole.RECEIPT, JobType.fromWire("receipt")!!.printer)
        assertEquals(PrinterRole.RECEIPT, JobType.fromWire("scratchpad")!!.printer)
        assertEquals(PrinterRole.LABEL, JobType.fromWire("label")!!.printer)
        assertEquals(PrinterRole.LABEL, JobType.fromWire("barcode_label")!!.printer)
        assertEquals(PrinterRole.LABEL, JobType.fromWire("qr_label")!!.printer)
    }

    @Test fun parsingIsTolerantButStrict() {
        assertEquals(JobType.QR_LABEL, JobType.fromWire(" QR_Label "))
        assertNull(JobType.fromWire("invoice"))
        assertNull(JobType.fromWire(null))
        assertNull(JobType.fromWire(""))
    }
}
