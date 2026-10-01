package com.sopifo.printagent.print

import java.io.ByteArrayOutputStream

/** ESC/POS raster output (GS v 0) for thermal receipt printers. */
object EscPosEncoder {
    /** Rows per GS v 0 block; small bands keep cheap printers' input buffers from overflowing. */
    const val BAND_HEIGHT = 128

    fun encode(image: MonoBitmap, copies: Int = 1, feedLines: Int = 4, cut: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream(image.data.size + 64)
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @  initialize
        // ESC a 1: the printer centres images narrower than its own head (58 mm receipt on an
        // 80 mm printer). Printers that ignore it print left-aligned; either way nothing is cropped.
        out.write(byteArrayOf(0x1B, 0x61, 0x01))
        repeat(copies.coerceIn(1, MAX_COPIES)) {
            var y = 0
            while (y < image.height) {
                val rows = minOf(BAND_HEIGHT, image.height - y)
                val w = image.bytesPerRow
                out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, (w and 0xFF).toByte(), (w shr 8 and 0xFF).toByte(), (rows and 0xFF).toByte(), (rows shr 8 and 0xFF).toByte()))
                out.write(image.data, y * w, rows * w)
                y += rows
            }
            out.write(byteArrayOf(0x1B, 0x64, feedLines.coerceIn(0, 255).toByte())) // ESC d n  feed
            if (cut) out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // GS V B 0  feed & partial cut
        }
        return out.toByteArray()
    }
}

/** TSPL output (BITMAP command) for TSC-compatible label printers at 203 dpi. */
object TsplEncoder {
    const val DOTS_PER_MM = 8

    fun encode(image: MonoBitmap, widthMm: Int, heightMm: Int, gapMm: Int, copies: Int = 1): ByteArray {
        val out = ByteArrayOutputStream(image.data.size + 256)
        val header = buildString {
            append("SIZE $widthMm mm,$heightMm mm\r\n")
            append("GAP $gapMm mm,0 mm\r\n")
            append("DIRECTION 1\r\n")
            append("REFERENCE 0,0\r\n")
            append("CLS\r\n")
            append("BITMAP 0,0,${image.bytesPerRow},${image.height},0,")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        // TSPL bitmaps use 0 = print dot, so invert our 1 = black representation.
        val inverted = ByteArray(image.data.size) { i -> (image.data[i].toInt() xor 0xFF).toByte() }
        out.write(inverted)
        out.write("\r\nPRINT 1,${copies.coerceIn(1, MAX_COPIES)}\r\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }
}

const val MAX_COPIES = 10
