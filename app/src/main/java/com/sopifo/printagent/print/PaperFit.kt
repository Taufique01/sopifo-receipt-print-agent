package com.sopifo.printagent.print

/**
 * Size a job image prints at: scaled down (aspect kept) when it is wider than the paper or
 * taller than the print head allows, otherwise unchanged. Never padded: centring is left to
 * the printer (ESC a), which knows its real width, so a wrong paper setting can't push the
 * receipt off the edge. Plain Kotlin so it can be unit-tested on the JVM.
 */
data class PaperFit(val width: Int, val height: Int) {

    companion object {
        /**
         * [paperWidth] is the loaded paper in dots, or null when unknown: then the image is
         * only limited by the print head ([maxWidth]).
         */
        fun plan(imageWidth: Int, imageHeight: Int, maxWidth: Int, maxHeight: Int, paperWidth: Int?): PaperFit {
            require(imageWidth > 0 && imageHeight > 0) { "Empty image" }
            val limitW = minOf(paperWidth ?: maxWidth, maxWidth)
            val scale = minOf(1.0, limitW.toDouble() / imageWidth, maxHeight.toDouble() / imageHeight)
            if (scale >= 1.0) return PaperFit(imageWidth, imageHeight)
            return PaperFit((imageWidth * scale).toInt().coerceAtLeast(1), (imageHeight * scale).toInt().coerceAtLeast(1))
        }
    }
}
