package com.sopifo.printagent.quick

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The inked part of a white page, in pixels; used to trim a PDF's empty margins. */
data class ContentBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    companion object {
        /** A channel darker than this counts as ink (anti-aliased page edges and specks stay white). */
        const val INK_THRESHOLD = 235

        /**
         * Bounding box of the ink in [pixels] (ARGB, row-major, [w] × [h]), padded by 1% of the
         * shorter side so nothing touches the paper edge; null for a blank page.
         */
        fun find(pixels: IntArray, w: Int, h: Int): ContentBounds? {
            require(pixels.size >= w * h) { "Too few pixels" }
            var top = h
            var bottom = -1
            var left = w
            var right = -1
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val p = pixels[row + x]
                    if ((p ushr 16 and 0xFF) < INK_THRESHOLD || (p ushr 8 and 0xFF) < INK_THRESHOLD || (p and 0xFF) < INK_THRESHOLD) {
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                        if (x < left) left = x
                        if (x > right) right = x
                    }
                }
            }
            if (bottom < 0) return null
            val pad = (min(w, h) * 0.01f).roundToInt()
            val l = max(0, left - pad)
            val t = max(0, top - pad)
            return ContentBounds(l, t, min(w, right + pad + 1) - l, min(h, bottom + pad + 1) - t)
        }
    }
}
