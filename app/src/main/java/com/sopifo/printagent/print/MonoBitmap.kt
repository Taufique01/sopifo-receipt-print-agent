package com.sopifo.printagent.print

/**
 * 1-bit raster, rows packed MSB-first, 1 = black dot. This is the printer-facing format shared
 * by the ESC/POS and TSPL encoders; it is plain Kotlin so it can be unit-tested on the JVM.
 */
class MonoBitmap(val width: Int, val height: Int, val data: ByteArray) {
    val bytesPerRow: Int = (width + 7) / 8

    init {
        require(width > 0 && height > 0) { "Empty bitmap" }
        require(data.size == bytesPerRow * height) { "Data size mismatch" }
    }

    fun isBlack(x: Int, y: Int): Boolean {
        val b = data[y * bytesPerRow + x / 8].toInt()
        return (b shr (7 - x % 8)) and 1 == 1
    }

    companion object {
        /**
         * Converts ARGB pixels to 1-bit. Transparent pixels count as white paper; the rest are
         * thresholded on luminance. Backend PNGs are already print-ready black/white artwork.
         */
        fun fromArgb(width: Int, height: Int, pixels: IntArray, threshold: Int = 128): MonoBitmap {
            require(pixels.size >= width * height) { "Pixel buffer too small" }
            val bytesPerRow = (width + 7) / 8
            val out = ByteArray(bytesPerRow * height)
            for (y in 0 until height) {
                val row = y * width
                val outRow = y * bytesPerRow
                for (x in 0 until width) {
                    if (isDark(pixels[row + x], threshold)) {
                        val idx = outRow + x / 8
                        out[idx] = (out[idx].toInt() or (0x80 ushr (x % 8))).toByte()
                    }
                }
            }
            return MonoBitmap(width, height, out)
        }

        internal fun isDark(argb: Int, threshold: Int): Boolean {
            val a = argb ushr 24 and 0xFF
            val r = argb shr 16 and 0xFF
            val g = argb shr 8 and 0xFF
            val b = argb and 0xFF
            // Composite over white paper.
            val rr = (r * a + 255 * (255 - a)) / 255
            val gg = (g * a + 255 * (255 - a)) / 255
            val bb = (b * a + 255 * (255 - a)) / 255
            val luminance = (299 * rr + 587 * gg + 114 * bb) / 1000
            return luminance < threshold
        }
    }
}
