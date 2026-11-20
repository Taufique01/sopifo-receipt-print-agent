package com.sopifo.printagent.quick

/**
 * Shrinks a page drawn at high resolution to printer dots and decides black or white per dot.
 * Each dot averages the source pixels it covers and turns black from a third ink (Normal), so
 * text a fraction of a dot thin still prints. Plain scaling then the printer's mid-grey cut-off
 * keeps only bold text on small paper.
 */
object InkDownscale {
    /** Average luminance below this prints black (a third ink coverage; same as Normal). */
    const val INK_CUTOFF = 170
    /** Pixels lighter than this (tinted backgrounds, pale shading) count as white paper. */
    const val BACKGROUND_CUTOFF = 220

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** [src] (ARGB, [sw] × [sh]) as pure black/white ARGB, [tw] × [th]. */
    fun downscale(
        src: IntArray,
        sw: Int,
        sh: Int,
        tw: Int,
        th: Int,
        cutoff: Int = INK_CUTOFF,
        background: Int = BACKGROUND_CUTOFF,
    ): IntArray {
        require(sw > 0 && sh > 0 && tw > 0 && th > 0) { "Empty image" }
        require(src.size >= sw * sh) { "Too few pixels" }
        val lum = IntArray(sw * sh) { i ->
            val p = src[i]
            val l = (299 * (p ushr 16 and 0xFF) + 587 * (p ushr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000
            if (l > background) 255 else l
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

/**
 * How dark a PDF prints: a dot averaging darker than [cutoff] prints black. Normal a third ink,
 * Darker ~22%, Darkest ~10% (hairlines and faint grey text). 2.1.22: Normal was 200 (today's
 * Darker) and printed shared courier labels too dark, its QR modules grown shut once the printer's
 * dots spread. Same values as the web scratchpad (src/lib/print/pdf-to-png.ts).
 */
enum class PdfDarkness(val title: String, val cutoff: Int) {
    NORMAL("Normal", 170),
    DARKER("Darker", 200),
    DARKEST("Darkest", 230),
}

/** A roll print's page this many times taller (letters taller, not wider). Same as the web. */
enum class PdfStretch(val title: String, val factor: Float) {
    X1("1×", 1f),
    X1_25("1.25×", 1.25f),
    X1_5("1.5×", 1.5f),
}
