package com.sopifo.printagent.print

import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Printer bytes, plus a note for the job history when the image had to be shrunk to fit. */
class PrintData(val bytes: ByteArray, val note: String? = null)

/**
 * Turns a backend-rendered PNG into printer bytes: decode with hard size limits, threshold to
 * 1-bit, encode for the printer's protocol. Never draws content itself, apart from the
 * diagnostic test page.
 *
 * Paper width and label size are configured on the dashboard only. The backend renders at the
 * printer's native 203 dpi (8 dots/mm: 58 mm = 384, 80 mm = 576, 100×150 mm label = 800×1200),
 * so the image itself is the size: receipts print at the image width, and a TSPL label's SIZE is
 * the image size in mm. Images are only scaled down when they exceed the print head maximum.
 *
 * Safety net for a dashboard width that doesn't match the paper: when the receipt printer's
 * paper width is set on this device, wider receipts are scaled down to fit (instead of losing
 * the right-hand side) and narrower ones are centred.
 */
class PrintDataBuilder {

    fun fromPng(png: ByteArray, printer: PrinterConfigEntity, copies: Int): PrintData {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PrintException(PrintFailure.BAD_IMAGE, "Not a decodable image")
        if (bounds.outWidth > MAX_SOURCE_DIM || bounds.outHeight > MAX_SOURCE_HEIGHT) {
            throw PrintException(PrintFailure.BAD_IMAGE, "Image too large: ${bounds.outWidth}x${bounds.outHeight}")
        }
        val limitW = paperWidthDots(printer) ?: printableArea(printer).first
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= limitW) sample *= 2
        val decoded = try {
            BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } catch (e: OutOfMemoryError) {
            throw PrintException(PrintFailure.BAD_IMAGE, "Out of memory decoding image", e)
        } ?: throw PrintException(PrintFailure.BAD_IMAGE, "Image decode failed")
        return fromBitmap(decoded, printer, copies, sourceWidth = bounds.outWidth)
    }

    fun testPage(printer: PrinterConfigEntity): ByteArray {
        // 384 dots (48 mm) fits every supported paper width.
        val width = TEST_PAGE_WIDTH
        val height = 220
        val bmp = createBitmap(width, height)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val stroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f }
        canvas.drawRect(2f, 2f, width - 2f, height - 2f, stroke)
        val text = Paint().apply { color = Color.BLACK; isAntiAlias = false; typeface = Typeface.DEFAULT_BOLD; textSize = (width / 14f).coerceIn(18f, 36f) }
        val small = Paint(text).apply { typeface = Typeface.DEFAULT; textSize = text.textSize * 0.7f }
        canvas.drawText("Sopifo test print", 16f, text.textSize + 16f, text)
        canvas.drawText(printer.name.take(32), 16f, text.textSize * 2 + 24f, small)
        canvas.drawText(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), 16f, text.textSize * 3 + 24f, small)
        return fromBitmap(bmp, printer, 1, sourceWidth = width).bytes
    }

    private fun fromBitmap(source: Bitmap, printer: PrinterConfigEntity, copies: Int, sourceWidth: Int): PrintData {
        val (maxW, maxH) = printableArea(printer)
        val paperW = paperWidthDots(printer)
        var bmp = source
        try {
            val fit = PaperFit.plan(bmp.width, bmp.height, maxW, maxH, paperW)
            val shrunk = fit.width != bmp.width || fit.height != bmp.height
            if (shrunk) {
                val scaled = bmp.scale(fit.width, fit.height)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            // Downscaling greys out 1-dot strokes; a lighter cut-off keeps thin text and rules.
            val mono = toMono(bmp, fit.canvasWidth, fit.offsetX, if (shrunk) SCALED_THRESHOLD else DEFAULT_THRESHOLD)
            val note = if (paperW != null && sourceWidth > paperW) {
                "Shrunk to fit ${printer.paperWidthMm} mm paper; set receipt width to ${printer.paperWidthMm} mm in the dashboard"
            } else null
            val bytes = when (printer.protocol) {
                PrinterProtocol.ESC_POS -> EscPosEncoder.encode(mono, copies = copies, cut = printer.cutPaper)
                PrinterProtocol.TSPL -> TsplEncoder.encode(
                    mono,
                    widthMm = ceilDiv(mono.width, TsplEncoder.DOTS_PER_MM),
                    heightMm = ceilDiv(mono.height, TsplEncoder.DOTS_PER_MM),
                    gapMm = printer.labelGapMm,
                    copies = copies,
                )
            }
            return PrintData(bytes, note)
        } catch (e: OutOfMemoryError) {
            throw PrintException(PrintFailure.BAD_IMAGE, "Out of memory preparing image", e)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Row-by-row conversion so a long receipt never needs a full ARGB int array in memory. The
     * image is placed [offsetX] dots in on a [canvasWidth]-dot row (white margins for centring).
     */
    private fun toMono(bmp: Bitmap, canvasWidth: Int, offsetX: Int, threshold: Int): MonoBitmap {
        val w = bmp.width
        val h = bmp.height
        val bytesPerRow = (canvasWidth + 7) / 8
        val out = ByteArray(bytesPerRow * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val base = y * bytesPerRow
            for (x in 0 until w) {
                if (MonoBitmap.isDark(row[x], threshold)) {
                    val cx = x + offsetX
                    out[base + cx / 8] = (out[base + cx / 8].toInt() or (0x80 ushr (cx % 8))).toByte()
                }
            }
        }
        return MonoBitmap(canvasWidth, h, out)
    }

    /** Loaded receipt paper in dots, when known. Labels are sized by the image itself. */
    private fun paperWidthDots(printer: PrinterConfigEntity): Int? =
        if (printer.protocol == PrinterProtocol.ESC_POS) paperWidthDots(printer.paperWidthMm) else null

    /** Print head limits only; the actual size comes from the image. */
    private fun printableArea(printer: PrinterConfigEntity): Pair<Int, Int> = when (printer.protocol) {
        PrinterProtocol.ESC_POS -> MAX_ESC_POS_WIDTH to MAX_RECEIPT_HEIGHT
        PrinterProtocol.TSPL -> MAX_TSPL_WIDTH to MAX_TSPL_HEIGHT
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    companion object {
        const val MAX_SOURCE_DIM = 4096
        const val MAX_SOURCE_HEIGHT = 20000
        const val MAX_RECEIPT_HEIGHT = 12000
        /** 80 mm paper, the widest ESC/POS receipt the dashboard renders. */
        const val MAX_ESC_POS_WIDTH = 576
        /** 104 mm, a 4-inch label print head. */
        const val MAX_TSPL_WIDTH = 832
        const val MAX_TSPL_HEIGHT = 4096
        const val TEST_PAGE_WIDTH = 384
        const val PAPER_58_MM_DOTS = 384
        const val PAPER_80_MM_DOTS = 576
        private const val DEFAULT_THRESHOLD = 128
        private const val SCALED_THRESHOLD = 160

        fun paperWidthDots(paperWidthMm: Int?): Int? = when (paperWidthMm) {
            58 -> PAPER_58_MM_DOTS
            80 -> PAPER_80_MM_DOTS
            else -> null
        }
        const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024
    }
}
