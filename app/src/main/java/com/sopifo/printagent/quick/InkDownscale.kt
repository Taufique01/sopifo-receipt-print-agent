package com.sopifo.printagent.quick

/**
 * Shrinks a page drawn at high resolution to printer dots and decides black or white per dot.
 * Each dot averages the source pixels it covers and turns black from about 22% ink, so text a
 * fraction of a dot thin still prints. Plain scaling then the printer's mid-grey cut-off keeps
 * only bold text on small paper.
 */
object InkDownscale {
    /** Average luminance below this prints black (~22% ink coverage). */
    const val INK_CUTOFF = 200

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** [src] (ARGB, [sw] × [sh]) as pure black/white ARGB, [tw] × [th]. */
    fun downscale(src: IntArray, sw: Int, sh: Int, tw: Int, th: Int, cutoff: Int = INK_CUTOFF): IntArray {
        require(sw > 0 && sh > 0 && tw > 0 && th > 0) { "Empty image" }
        require(src.size >= sw * sh) { "Too few pixels" }
        val lum = IntArray(sw * sh) { i ->
            val p = src[i]
            (299 * (p ushr 16 and 0xFF) + 587 * (p ushr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
        val out = IntArray(tw * th)
        for (y in 0 until th) {
            val y0 = (y.toLong() * sh / th).toInt()
            val y1 = maxOf(y0 + 1, (((y + 1).toLong() * sh + th - 1) / th).toInt().coerceAtMost(sh))
            for (x in 0 until tw) {
                val x0 = (x.toLong() * sw / tw).toInt()
                val x1 = maxOf(x0 + 1, (((x + 1).toLong() * sw + tw - 1) / tw).toInt().coerceAtMost(sw))
                var sum = 0L
                for (yy in y0 until y1) {
                    val row = yy * sw
                    for (xx in x0 until x1) sum += lum[row + xx]
                }
                val avg = sum / ((y1 - y0) * (x1 - x0))
                out[y * tw + x] = if (avg < cutoff) BLACK else WHITE
            }
        }
        return out
    }
}
