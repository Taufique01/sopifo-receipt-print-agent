package com.sopifo.printagent.quick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a quick print goes: a receipt roll's width, or a fixed label size (TSPL printers). */
data class QuickTarget(val widthDots: Int, val heightDots: Int? = null) {
    val isLabel: Boolean get() = heightDots != null
}

/** Label sizes offered for quick prints on a label (TSPL) printer, at 203 dpi. */
enum class QuickLabelSize(val title: String, val w: Int, val h: Int) {
    S40X30("40 × 30 mm", 320, 240),
    S50X30("50 × 30 mm", 384, 240),
    S58X40("58 × 40 mm", 384, 320),
    S3X2("3 × 2 in", 609, 406),
    S2X3("2 × 3 in (50 × 75 mm)", 400, 600),
    S3X3("3 × 3 in (75 × 75 mm)", 600, 600),
    S3X4("3 × 4 in (75 × 100 mm)", 600, 800),
    S4X6("4 × 6 in", 812, 1218),
}

enum class QuickTextSize(val title: String, val dotsPerChar: Float) {
    SMALL("Small", 22f),
    MEDIUM("Medium", 30f),
    LARGE("Large", 44f),
}

/**
 * Draws quick prints on the phone (no server): text, an image, or PDF pages, as black-on-white
 * bitmaps the size of the paper. The printer manager turns them into printer bytes as usual.
 */
class QuickPrintRenderer(private val context: Context) {

    fun text(text: String, size: QuickTextSize, bold: Boolean, center: Boolean, target: QuickTarget): Bitmap {
        val pad = 12
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = size.dotsPerChar
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val width = target.widthDots - pad * 2
        val layout = StaticLayout.Builder.obtain(text.ifEmpty { " " }, 0, text.ifEmpty { " " }.length, paint, width)
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .build()
        val content = createBitmap(target.widthDots, layout.height + pad * 2)
        Canvas(content).apply {
            drawColor(Color.WHITE)
            translate(pad.toFloat(), pad.toFloat())
            layout.draw(this)
        }
        return fitToTarget(content, target)
    }

    /** An image fitted (whole image) or filled (covers, cropped) to the paper, then zoomed around the centre. */
    fun image(uri: Uri, target: QuickTarget, fit: ImageFit = ImageFit.FIT, zoom: Float = 1f): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("That file isn't an image this phone can read")
        // Decode just big enough for the zoomed size, so a 12 MP photo never fills the memory.
        val needed = (max(target.widthDots, target.heightDots ?: 0) * zoom.coerceAtLeast(1f)).roundToInt()
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= needed) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Couldn't open that image")
        val margin = if (target.isLabel) (min(target.widthDots, target.heightDots!!) * 0.04f).roundToInt() else 0
        val place = ImagePlacement.plan(decoded.width, decoded.height, target.widthDots, target.heightDots, fit, zoom, margin)
        val out = createBitmap(target.widthDots, min(place.pageHeight, MAX_ROLL_HEIGHT))
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, null, Rect(place.left, place.top, place.left + place.width, place.top + place.height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        decoded.recycle()
        return out
    }

    /** HTML drawn by an off-screen WebView at the paper's width (see HtmlRenderer), fitted like any page. */
    suspend fun html(html: String, target: QuickTarget): Bitmap =
        fitToTarget(HtmlRenderer.render(context, html, target.widthDots, target.heightDots), target)

    fun pdfPageCount(uri: Uri): Int = openPdf(uri) { it.pageCount }

    /** Pages [from]..[to] (1-based, inclusive), each the paper's width. */
    fun pdfPages(uri: Uri, from: Int, to: Int, target: QuickTarget): List<Bitmap> = openPdf(uri) { pdf ->
        (from.coerceAtLeast(1)..to.coerceAtMost(pdf.pageCount)).map { n ->
            pdf.openPage(n - 1).use { page ->
                val scale = target.widthDots.toFloat() / page.width
                val bmp = createBitmap(target.widthDots, max(1, (page.height * scale).roundToInt()))
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                fitToTarget(bmp, target)
            }
        }
    }

    /** Saves a bitmap for the print run (it reads the file when its turn comes). */
    fun save(bitmap: Bitmap): File {
        val dir = File(context.cacheDir, "quick").apply { mkdirs() }
        // Old quick prints go after a day.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > DAY_MS }?.forEach { it.delete() }
        val file = File(dir, "${UUID.randomUUID()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun <T> openPdf(uri: Uri, block: (PdfRenderer) -> T): T {
        val fd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Couldn't open that PDF")
        return fd.use { PdfRenderer(it).use(block) }
    }

    /** Transparent areas become white paper. */
    private fun onWhite(src: Bitmap): Bitmap {
        val out = createBitmap(src.width, src.height)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }

    /**
     * Rolls: the content scaled to the paper's width (never wider). Labels: the content fitted
     * inside the label with a small margin and centred, so the image is exactly the label size.
     */
    private fun fitToTarget(src: Bitmap, target: QuickTarget): Bitmap {
        if (!target.isLabel) {
            if (src.width == target.widthDots) return src
            val h = max(1, (src.height * target.widthDots.toFloat() / src.width).roundToInt())
            return Bitmap.createScaledBitmap(src, target.widthDots, h, true)
        }
        val w = target.widthDots
        val h = target.heightDots!!
        val margin = (min(w, h) * 0.04f).roundToInt()
        val scale = min((w - margin * 2f) / src.width, (h - margin * 2f) / src.height)
        val dw = max(1, (src.width * scale).roundToInt())
        val dh = max(1, (src.height * scale).roundToInt())
        val out = createBitmap(w, h)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            val left = (w - dw) / 2
            val top = (h - dh) / 2
            drawBitmap(src, null, Rect(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        /** Longest roll print from one image (1.5 m at 8 dots/mm). */
        const val MAX_ROLL_HEIGHT = 12000
    }
}
