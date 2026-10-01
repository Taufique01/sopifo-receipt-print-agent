package com.sopifo.printagent.print

/**
 * Where a job image lands on the paper: scaled down (aspect kept) when it is wider than the
 * paper or taller than the print head allows, and centred on a [canvasWidth]-dot row when it is
 * narrower than known paper. Plain Kotlin so it can be unit-tested on the JVM.
 */
data class PaperFit(val width: Int, val height: Int, val canvasWidth: Int, val offsetX: Int) {

    companion object {
        /**
         * [paperWidth] is the loaded paper in dots, or null when unknown: then the image is
         * only limited by the print head ([maxWidth]) and never centred.
         */
        fun plan(imageWidth: Int, imageHeight: Int, maxWidth: Int, maxHeight: Int, paperWidth: Int?): PaperFit {
            require(imageWidth > 0 && imageHeight > 0) { "Empty image" }
            val limitW = minOf(paperWidth ?: maxWidth, maxWidth)
            val scale = minOf(1.0, limitW.toDouble() / imageWidth, maxHeight.toDouble() / imageHeight)
            val w = if (scale < 1.0) (imageWidth * scale).toInt().coerceAtLeast(1) else imageWidth
            val h = if (scale < 1.0) (imageHeight * scale).toInt().coerceAtLeast(1) else imageHeight
            val canvas = if (paperWidth != null && w < limitW) limitW else w
            return PaperFit(w, h, canvas, (canvas - w) / 2)
        }
    }
}
