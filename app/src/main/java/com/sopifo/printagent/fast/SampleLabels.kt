package com.sopifo.printagent.fast

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

/**
 * Try prints (no account): sample courier labels drawn on the phone the way the courier's own
 * PDF looks — a small label in the corner of a big page — so smart shrink can be tried on the
 * seller's printer before signing up. All data is made up.
 */
object SampleLabels {
    /** Like Pathao's merchant-panel "3 × 3" print: an A4 page (150 dpi) with the label top-left. */
    fun pathao(): Bitmap = page { c ->
        val x = 60f
        var y = 60f
        val box = Rect(x.toInt(), y.toInt(), x.toInt() + 450, y.toInt() + 450)
        c.drawRect(box, stroke(2f))
        c.drawText("Pathao", x + 20, y + 52, text(40f, bold = true))
        c.drawText("merchant", x + 22, y + 80, text(20f))
        c.drawText("Sopifo Demo Shop", x + 170, y + 40, text(24f, bold = true))
        c.drawText("Dhaka - Mirpur", x + 170, y + 68, text(20f))
        c.drawText("Contact: 01700-000000", x + 170, y + 94, text(18f))
        c.drawText("DM0812SAMPLE01", x + 170, y + 120, text(20f, bold = true))
        y += 135f
        barcode(c, "DM0812SAMPLE01", Rect(x.toInt() + 15, y.toInt(), x.toInt() + 435, y.toInt() + 95))
        y += 125f
        c.drawText("Sample Customer", x + 15, y, text(26f, bold = true))
        y += 34f
        c.drawText("01800-000000 |", x + 15, y, text(22f))
        c.drawRect(x + 175, y - 24, x + 360, y + 8, fill())
        c.drawText("COD: BDT 500.00", x + 180, y, text(22f, white = true))
        y += 34f
        c.drawText("Order ID: SF-1001", x + 15, y, text(22f))
    }

    /** Like a Steadfast label: consignment number barcode, recipient, address, COD. */
    fun steadfast(): Bitmap = page { c ->
        val x = 60f
        var y = 60f
        c.drawRect(Rect(x.toInt(), y.toInt(), x.toInt() + 600, y.toInt() + 400), stroke(2f))
        c.drawText("Steadfast Courier", x + 20, y + 45, text(32f, bold = true))
        c.drawText("Invoice: SF-1002", x + 360, y + 45, text(20f))
        y += 70f
        barcode(c, "123456789", Rect(x.toInt() + 20, y.toInt(), x.toInt() + 580, y.toInt() + 90))
        y += 120f
        c.drawText("Consignment: 123456789", x + 20, y, text(22f, bold = true))
        y += 38f
        c.drawText("Sample Receiver · 01900-000000", x + 20, y, text(24f, bold = true))
        y += 34f
        c.drawText("House 1, Road 2, Dhanmondi, Dhaka", x + 20, y, text(22f))
        y += 46f
        c.drawRect(x + 20, y - 30, x + 300, y + 10, fill())
        c.drawText("COD ৳ 750", x + 30, y, text(28f, bold = true, white = true))
    }

    private fun page(draw: (Canvas) -> Unit): Bitmap {
        // A4 at ~150 dpi.
        val bmp = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        draw(c)
        return bmp
    }

    private fun barcode(c: Canvas, value: String, area: Rect) {
        val m = MultiFormatWriter().encode(value, BarcodeFormat.CODE_128, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
        val unit = maxOf(1, area.width() / m.width)
        val left = area.left + (area.width() - unit * m.width) / 2
        val p = fill()
        for (i in 0 until m.width) if (m.get(i, 0)) c.drawRect(Rect(left + i * unit, area.top, left + (i + 1) * unit, area.bottom), p)
    }

    private fun text(size: Float, bold: Boolean = false, white: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = if (white) Color.WHITE else Color.BLACK
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun stroke(w: Float) = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = w }
    private fun fill() = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

    /** Try pad: [text] big and bold, [widthDots] wide (label or roll), on white. */
    fun textBlock(text: String, widthDots: Int, heightDots: Int?): Bitmap {
        val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = widthDots / 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        val pad = widthDots / 25
        val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, widthDots - pad * 2).build()
        val h = heightDots ?: (layout.height + pad * 2)
        val bmp = Bitmap.createBitmap(widthDots, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.save()
        c.translate(pad.toFloat(), (if (heightDots != null) (h - layout.height) / 2 else pad).toFloat().coerceAtLeast(0f))
        layout.draw(c)
        c.restore()
        return bmp
    }
}
