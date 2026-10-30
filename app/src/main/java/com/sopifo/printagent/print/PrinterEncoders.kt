package com.sopifo.printagent.print

import java.io.ByteArrayOutputStream

/** ESC/POS raster output (GS v 0) for thermal receipt printers. */
object EscPosEncoder {
    /** Rows per GS v 0 block; small bands keep cheap printers' input buffers from overflowing. */
    const val BAND_HEIGHT = 128

    /**
     * NULs sent before each copy's commands. Cheap printers drop the first bytes after a
     * Bluetooth connect (or while cutting); without padding they lose the GS v 0 header and print
     * the image bytes as text: garbage at the top. ESC/POS ignores NUL, so dropping these is harmless.
     */
    const val SYNC_PADDING = 64
    /** Extra NULs after a cut when more copies follow: time for the cutter before the next image. */
    const val COPY_GAP_PADDING = 2048

    fun encode(image: MonoBitmap, copies: Int = 1, feedLines: Int = 4, cut: Boolean = true): ByteArray {
        val n = copies.coerceIn(1, MAX_COPIES)
        val out = ByteArrayOutputStream((image.data.size + 128) * n + COPY_GAP_PADDING * (n - 1))
        repeat(n) { copy ->
            if (copy > 0) out.write(ByteArray(COPY_GAP_PADDING))
            out.write(ByteArray(SYNC_PADDING))
            // ESC @ (re)initialises, so each copy starts from a known state even after a drop.
            out.write(byteArrayOf(0x1B, 0x40))
            // ESC a 1: the printer centres images narrower than its own head (58 mm receipt on an
            // 80 mm printer). Printers that ignore it print left-aligned; either way nothing is cropped.
            out.write(byteArrayOf(0x1B, 0x61, 0x01))
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

    /**
     * [gapMm] is the space between labels (0 = continuous paper); with [blackMark] it is the height
     * of the black mark printed on the back of the roll instead (BLINE).
     */
    fun encode(image: MonoBitmap, widthMm: Int, heightMm: Int, gapMm: Int, copies: Int = 1, blackMark: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream(image.data.size + 256)
        val header = buildString {
            // Blank lines first: bytes dropped right after connecting cost these, not SIZE.
            append(TSPL_SYNC_PADDING)
            append("SIZE $widthMm mm,$heightMm mm\r\n")
            append("${if (blackMark) "BLINE" else "GAP"} ${gapMm.coerceIn(0, MAX_GAP_MM)} mm,0 mm\r\n")
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

    /** Has the printer feed a few labels and measure the label length and gap (or black mark) itself. */
    fun calibrate(blackMark: Boolean): ByteArray =
        "${if (blackMark) "BLINEDETECT" else "GAPDETECT"}\r\n".toByteArray(Charsets.US_ASCII)

    const val MAX_GAP_MM = 10
    const val TSPL_SYNC_PADDING = "\r\n\r\n\r\n\r\n"
}

const val MAX_COPIES = 10
