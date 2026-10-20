package com.sopifo.printagent.print

import kotlin.math.roundToInt

/**
 * Label stickers on a receipt printer with no gap sensor. Every print is exactly one sticker +
 * gap tall ("pitch"), so a roll lined up once stays lined up: the seller puts a gap at the tear
 * edge, and each print ends with the next gap back at the tear edge.
 *
 * The top of the sticker under the print head has already passed it (the tear edge sits above
 * the head), so content starts a little way down the sticker. [offsetTenths] moves it further
 * down; [pitchAdjustHundredths] corrects a printer that feeds slightly long or short over a run.
 * Paper is moved with blank image rows, not feed commands: rows are always exactly one dot,
 * while feed units differ between printers.
 */
data class StickerLayout(
    val heightMm: Int,
    val gapMm: Int,
    /** Extra space above the content, 0.1 mm. */
    val offsetTenths: Int = 0,
    /** Per-sticker feed correction, 0.01 mm. */
    val pitchAdjustHundredths: Int = 0,
) {
    /** Dots one sticker + gap takes: what every print feeds in total. */
    val pitchDots: Int = ((heightMm + gapMm + pitchAdjustHundredths / 100.0) * DOTS_PER_MM).roundToInt()

    val topDots: Int = (offsetTenths / 10.0 * DOTS_PER_MM).roundToInt().coerceIn(0, pitchDots)

    /**
     * Tallest content that stays on the sticker: the part already past the head (up to
     * [RESERVED_MM] with the tear-edge distance and the bottom margin) is left blank.
     */
    val maxContentDots: Int =
        (((heightMm - RESERVED_MM) * DOTS_PER_MM).roundToInt() - topDots).coerceAtLeast(MIN_CONTENT_DOTS)

    /** Blank rows after content [contentDots] tall, so the print ends at the next gap. */
    fun tailDots(contentDots: Int): Int = (pitchDots - topDots - contentDots).coerceAtLeast(0)

    companion object {
        /** 203 dpi. */
        const val DOTS_PER_MM = 203 / 25.4
        /** Room for the tear-edge distance above the head plus a bottom margin. */
        const val RESERVED_MM = 18
        const val MIN_CONTENT_DOTS = 64
        const val MAX_OFFSET_TENTHS = 200
        const val MAX_PITCH_ADJUST_HUNDREDTHS = 300

        val HEIGHTS_MM = listOf(40, 50, 60, 75, 100)
        val GAPS_MM = listOf(2, 3, 4)
        const val DEFAULT_HEIGHT_MM = 75
        const val DEFAULT_GAP_MM = 3

        fun mmToDots(mm: Double): Int = (mm * DOTS_PER_MM).roundToInt()
    }
}
