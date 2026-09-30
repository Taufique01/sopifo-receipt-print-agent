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

/**
 * Turns a backend-rendered PNG into printer bytes: decode with hard size limits, scale down to
 * the printable width, threshold to 1-bit, encode for the printer's protocol. Never draws content
 * itself, apart from the diagnostic test page.
 */
class PrintDataBuilder {

    fun fromPng(png: ByteArray, printer: PrinterConfigEntity, copies: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PrintException(PrintFailure.BAD_IMAGE, "Not a decodable image")
        if (bounds.outWidth > MAX_SOURCE_DIM || bounds.outHeight > MAX_SOURCE_HEIGHT) {
            throw PrintException(PrintFailure.BAD_IMAGE, "Image too large: ${bounds.outWidth}x${bounds.outHeight}")
        }
        val (maxW, maxH) = printableArea(printer)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
        val decoded = try {
            BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } catch (e: OutOfMemoryError) {
            throw PrintException(PrintFailure.BAD_IMAGE, "Out of memory decoding image", e)
        } ?: throw PrintException(PrintFailure.BAD_IMAGE, "Image decode failed")
        return fromBitmap(decoded, printer, copies)
    }

    fun testPage(printer: PrinterConfigEntity): ByteArray {
        val (maxW, maxH) = printableArea(printer)
        val height = minOf(maxH, 220)
        val bmp = createBitmap(maxW, height)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val stroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f }
        canvas.drawRect(2f, 2f, maxW - 2f, height - 2f, stroke)
        val text = Paint().apply { color = Color.BLACK; isAntiAlias = false; typeface = Typeface.DEFAULT_BOLD; textSize = (maxW / 14f).coerceIn(18f, 36f) }
        val small = Paint(text).apply { typeface = Typeface.DEFAULT; textSize = text.textSize * 0.7f }
        canvas.drawText("Sopifo test print", 16f, text.textSize + 16f, text)
        canvas.drawText(printer.name.take(32), 16f, text.textSize * 2 + 24f, small)
        canvas.drawText(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), 16f, text.textSize * 3 + 24f, small)
        return fromBitmap(bmp, printer, 1)
    }

    private fun fromBitmap(source: Bitmap, printer: PrinterConfigEntity, copies: Int): ByteArray {
        val (maxW, maxH) = printableArea(printer)
        var bmp = source
        try {
            val scale = minOf(1f, maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height)
            if (scale < 1f) {
                val scaled = bmp.scale((bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1))
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            val mono = toMono(bmp)
            return when (printer.protocol) {
                PrinterProtocol.ESC_POS -> EscPosEncoder.encode(mono, copies = copies, cut = printer.cutPaper)
                PrinterProtocol.TSPL -> TsplEncoder.encode(mono, printer.labelWidthMm, printer.labelHeightMm, printer.labelGapMm, copies)
            }
        } catch (e: OutOfMemoryError) {
            throw PrintException(PrintFailure.BAD_IMAGE, "Out of memory preparing image", e)
        } finally {
            bmp.recycle()
        }
    }

    /** Row-by-row conversion so a long receipt never needs a full ARGB int array in memory. */
    private fun toMono(bmp: Bitmap): MonoBitmap {
        val w = bmp.width
        val h = bmp.height
        val bytesPerRow = (w + 7) / 8
        val out = ByteArray(bytesPerRow * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val base = y * bytesPerRow
            for (x in 0 until w) {
                if (MonoBitmap.isDark(row[x], 128)) {
                    out[base + x / 8] = (out[base + x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
        }
        return MonoBitmap(w, h, out)
    }

    private fun printableArea(printer: PrinterConfigEntity): Pair<Int, Int> = when (printer.protocol) {
        PrinterProtocol.ESC_POS -> printer.widthDots.coerceIn(8, 2048) to MAX_RECEIPT_HEIGHT
        PrinterProtocol.TSPL -> minOf(printer.widthDots, printer.labelWidthMm * TsplEncoder.DOTS_PER_MM).coerceIn(8, 2048) to
            (printer.labelHeightMm * TsplEncoder.DOTS_PER_MM).coerceIn(8, 4096)
    }

    companion object {
        const val MAX_SOURCE_DIM = 4096
        const val MAX_SOURCE_HEIGHT = 20000
        const val MAX_RECEIPT_HEIGHT = 12000
        const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024
    }
}
