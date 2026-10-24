package com.sopifo.printagent.quick

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Fit: the whole image shows. Fill: the image covers the label and the overflow is cut off. */
enum class ImageFit(val title: String) { FIT("Fit"), FILL("Fill") }

/** Where a quick-print image lands on the page; [left]/[top] can be negative (cropped). */
data class ImagePlacement(val left: Int, val top: Int, val width: Int, val height: Int, val pageHeight: Int) {
    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 3f

        /**
         * Labels ([pageHeight] set): Fit/Fill inside the label less [margin], times [zoom], centred.
         * Rolls (no height): the paper's width times [zoom], centred; the page is as tall as the image.
         */
        fun plan(srcW: Int, srcH: Int, pageWidth: Int, pageHeight: Int?, fit: ImageFit, zoom: Float, margin: Int): ImagePlacement {
            require(srcW > 0 && srcH > 0) { "Empty image" }
            val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            if (pageHeight == null) {
                val w = max(1, (pageWidth * z).roundToInt())
                val h = max(1, (srcH * w.toFloat() / srcW).roundToInt())
                return ImagePlacement((pageWidth - w) / 2, 0, w, h, h)
            }
            val boxW = (pageWidth - margin * 2f).coerceAtLeast(1f)
            val boxH = (pageHeight - margin * 2f).coerceAtLeast(1f)
            val base = if (fit == ImageFit.FIT) min(boxW / srcW, boxH / srcH) else max(boxW / srcW, boxH / srcH)
            val w = max(1, (srcW * base * z).roundToInt())
            val h = max(1, (srcH * base * z).roundToInt())
            return ImagePlacement((pageWidth - w) / 2, (pageHeight - h) / 2, w, h, pageHeight)
        }
    }
}
