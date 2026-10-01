package com.sopifo.printagent.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

fun testPng(width: Int, height: Int): ByteArray {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bmp).apply {
        drawColor(Color.WHITE)
        drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.BLACK })
    }
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
}

@RunWith(AndroidJUnit4::class)
class PrintDataBuilderTest {
    private val builder = PrintDataBuilder()
    private val receipt = PrinterConfigEntity(PrinterRole.RECEIPT, "R", "00:11:22:33:44:55", PrinterProtocol.ESC_POS, 576)
    private val label = PrinterConfigEntity(PrinterRole.LABEL, "L", "00:11:22:33:44:66", PrinterProtocol.TSPL, 400, 50, 30)

    private fun rasterHeader(bytes: ByteArray): Pair<Int, Int> {
        val i = (0 until bytes.size - 8).first { bytes[it] == 0x1D.toByte() && bytes[it + 1] == 0x76.toByte() }
        val w = (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8)
        val h = (bytes[i + 6].toInt() and 0xFF) or ((bytes[i + 7].toInt() and 0xFF) shl 8)
        return w to h
    }

    @Test fun receiptPngIsEncodedAtNativeWidth() {
        val bytes = builder.fromPng(testPng(576, 100), receipt, copies = 1).bytes
        assertEquals(72 to 100, rasterHeader(bytes))
        // First data byte: left half black.
        assertEquals(0xFF, bytes[10].toInt() and 0xFF)
    }

    @Test fun widerImagesAreScaledDownToPaperWidth() {
        val bytes = builder.fromPng(testPng(1152, 200), receipt, copies = 1).bytes
        assertEquals(72 to 100, rasterHeader(bytes))
    }

    @Test fun eightyMmReceiptShrinksOntoFiftyEightMmPaperWithNote() {
        val data = builder.fromPng(testPng(576, 150), receipt.copy(paperWidthMm = 58), copies = 1)
        assertEquals(48 to 100, rasterHeader(data.bytes))
        assertTrue(data.note!!.contains("58 mm"))
    }

    @Test fun fiftyEightMmReceiptIsCentredOnEightyMmPaper() {
        val data = builder.fromPng(testPng(384, 100), receipt.copy(paperWidthMm = 80), copies = 1)
        assertEquals(72 to 100, rasterHeader(data.bytes))
        // 96-dot (12-byte) white margin, then the image's black left half starts.
        assertEquals(0x00, data.bytes[10].toInt() and 0xFF)
        assertEquals(0x00, data.bytes[10 + 11].toInt() and 0xFF)
        assertEquals(0xFF, data.bytes[10 + 12].toInt() and 0xFF)
        assertEquals(null, data.note)
    }

    @Test fun labelPngIsEncodedAsTspl() {
        val text = String(builder.fromPng(testPng(400, 240), label, copies = 1).bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("SIZE 50 mm,30 mm"))
        assertTrue(text.contains("BITMAP 0,0,50,240,0,"))
    }

    @Test fun labelSizeComesFromImageNotPrinterConfig() {
        // Dashboard 100×150 mm label on a printer whose (legacy) config says 50×30.
        val text = String(builder.fromPng(testPng(800, 1200), label, copies = 1).bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("SIZE 100 mm,150 mm"))
        assertTrue(text.contains("BITMAP 0,0,100,1200,0,"))
    }

    @Test fun receiptWidthComesFromImageNotPrinterConfig() {
        // Dashboard 58 mm receipt on a printer whose (legacy) config says 80 mm: not stretched.
        val bytes = builder.fromPng(testPng(384, 100), receipt, copies = 1).bytes
        assertEquals(48 to 100, rasterHeader(bytes))
    }

    @Test fun garbageBytesAreRejectedNotCrashing() {
        try {
            builder.fromPng(byteArrayOf(1, 2, 3, 4, 5), receipt, 1)
            fail("expected PrintException")
        } catch (e: PrintException) {
            assertEquals(PrintFailure.BAD_IMAGE, e.failure)
        }
    }

    @Test fun absurdDimensionsAreRejectedBeforeDecoding() {
        try {
            builder.fromPng(testPng(5000, 10), receipt, 1)
            fail("expected PrintException")
        } catch (e: PrintException) {
            assertEquals(PrintFailure.BAD_IMAGE, e.failure)
        }
    }

    @Test fun testPageRendersForBothPrinters() {
        assertTrue(builder.testPage(receipt).size > 100)
        assertTrue(String(builder.testPage(label), Charsets.ISO_8859_1).contains("PRINT 1,1"))
    }
}
