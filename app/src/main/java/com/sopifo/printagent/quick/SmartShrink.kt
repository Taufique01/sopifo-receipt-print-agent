package com.sopifo.printagent.quick

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.sopifo.printagent.core.AppLog
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Smart shrink (app 2.0.1): a courier label (Pathao, Steadfast…) printed smaller with its barcode
 * still scannable. Shrinking the whole label makes the bars too thin for a 203 dpi printer, so:
 *
 * 1. Find each barcode on the big page (Google ML Kit, on the phone, offline): value, type, box.
 * 2. Shrink the label onto the small paper as usual.
 * 3. Map each box onto the small label, paint it white, and draw the same value again with bars
 *    on whole printer dots (as wide as its row allows), so it comes out sharp.
 * 4. Scan the result: it must read the same values, else the plain shrink is printed.
 */
object SmartShrink {
    private const val TAG = "SmartShrink"
    private const val SCAN_TIMEOUT_S = 8L

    data class Found(val value: String, val format: BarcodeFormat, val box: Rect)

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93, Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_ITF, Barcode.FORMAT_CODABAR,
                    Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX,
                )
                .build(),
        )
    }

    /** Barcodes on [bitmap] (call off the main thread). Empty when none or the scanner fails. */
    fun detect(bitmap: Bitmap): List<Found> = try {
        val result = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), SCAN_TIMEOUT_S, TimeUnit.SECONDS)
        result.mapNotNull { b ->
            val value = b.rawValue?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val box = b.boundingBox ?: return@mapNotNull null
            val format = zxingFormat(b.format) ?: return@mapNotNull null
            Found(value, format, box)
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "Barcode scan failed", e)
        emptyList()
    }

    /**
     * [label] is the plain shrink (pure black and white, label size); [found] are the barcodes of
     * the big content, which was placed at [place] (content pixels → label dots). Returns the
     * label with sharp barcodes, or null when there was nothing to redraw or the check failed.
     */
    fun redraw(label: Bitmap, found: List<Found>, srcWidth: Int, place: ImagePlacement, margin: Int): Bitmap? {
        if (found.isEmpty()) return null
        val scale = place.width.toFloat() / srcWidth
        val out = label.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val white = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        val black = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL; isAntiAlias = false }
        var drawn = 0
        for (f in found) {
            val r = Rect(
                place.left + (f.box.left * scale).roundToInt(),
                place.top + (f.box.top * scale).roundToInt(),
                place.left + (f.box.right * scale).roundToInt(),
                place.top + (f.box.bottom * scale).roundToInt(),
            )
            if (r.width() < 4 || r.height() < 4) continue
            val square = f.format == BarcodeFormat.QR_CODE || f.format == BarcodeFormat.DATA_MATRIX
            // Rotated linear barcodes (tall boxes) are left as they are.
            if (!square && r.height() > r.width() * 1.4f) continue
            // A linear barcode may grow sideways when nothing else is printed beside it.
            val area = if (!square && rowIsFree(label, r, margin)) Rect(margin, r.top, out.width - margin, r.bottom) else Rect(r)
            val matrix = encode(f) ?: continue
            canvas.drawRect(Rect(r.left - 2, r.top - 2, r.right + 2, r.bottom + 2), white)
            if (square) drawSquare(canvas, matrix, r, black) else drawLinear(canvas, matrix, area, black)
            drawn++
        }
        if (drawn == 0) {
            out.recycle()
            return null
        }
        // Must read back the same values, else the plain shrink is safer.
        val again = detect(out).map { it.value }.toSet()
        val ok = found.all { it.value in again }
        if (!ok) {
            AppLog.w(TAG, "Redrawn barcode didn't read back; plain shrink", null, "found" to found.size, "read" to again.size)
            out.recycle()
            return null
        }
        return out
    }

    private fun encode(f: Found): BitMatrix? = try {
        MultiFormatWriter().encode(f.value, f.format, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    } catch (e: Exception) {
        AppLog.w(TAG, "Couldn't redraw ${f.format}", e)
        null
    }

    /** Bars on whole dots: each module [unit] dots wide, centred in [area], full [area] height. */
    private fun drawLinear(c: Canvas, m: BitMatrix, area: Rect, black: Paint) {
        val unit = max(1, area.width() / m.width)
        val total = unit * m.width
        val left = area.left + (area.width() - total) / 2
        for (x in 0 until m.width) {
            if (m.get(x, 0)) c.drawRect(Rect(left + x * unit, area.top, left + (x + 1) * unit, area.bottom), black)
        }
    }

    private fun drawSquare(c: Canvas, m: BitMatrix, area: Rect, black: Paint) {
        val unit = max(1, min(area.width() / m.width, area.height() / m.height))
        val left = area.left + (area.width() - unit * m.width) / 2
        val top = area.top + (area.height() - unit * m.height) / 2
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) c.drawRect(Rect(left + x * unit, top + y * unit, left + (x + 1) * unit, top + (y + 1) * unit), black)
        }
    }

    /** No ink beside the barcode in its row (inside the label's margins). */
    private fun rowIsFree(label: Bitmap, r: Rect, margin: Int): Boolean {
        val top = r.top.coerceIn(0, label.height - 1)
        val bottom = r.bottom.coerceIn(top + 1, label.height)
        val rowW = label.width
        val px = IntArray(rowW)
        for (y in top until bottom) {
            label.getPixels(px, 0, rowW, 0, y, rowW, 1)
            for (x in margin until rowW - margin) {
                if (x in (r.left - 3)..(r.right + 3)) continue
                if (Color.red(px[x]) < 128) return false
            }
        }
        return true
    }

    private fun zxingFormat(f: Int): BarcodeFormat? = when (f) {
        Barcode.FORMAT_CODE_128 -> BarcodeFormat.CODE_128
        Barcode.FORMAT_CODE_39 -> BarcodeFormat.CODE_39
        Barcode.FORMAT_CODE_93 -> BarcodeFormat.CODE_93
        Barcode.FORMAT_EAN_13 -> BarcodeFormat.EAN_13
        Barcode.FORMAT_EAN_8 -> BarcodeFormat.EAN_8
        Barcode.FORMAT_UPC_A -> BarcodeFormat.UPC_A
        Barcode.FORMAT_ITF -> BarcodeFormat.ITF
        Barcode.FORMAT_CODABAR -> BarcodeFormat.CODABAR
        Barcode.FORMAT_QR_CODE -> BarcodeFormat.QR_CODE
        Barcode.FORMAT_DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
        else -> null
    }
}
