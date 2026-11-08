package com.sopifo.printagent.fast

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A POS receipt drawn on the phone (app 2.0.1), from the sale the POS page hands over: no
 * server, no download, so the printer starts within a fraction of a second. Bangla text uses
 * the phone's own fonts. Width = the receipt paper (58 mm = 384 dots, 80 mm = 576 dots).
 */
object ReceiptRenderer {
    data class Item(val name: String, val qty: Int, val price: Double, val total: Double)

    data class Receipt(
        val number: String,
        val atMillis: Long,
        val shopName: String,
        val shopPhone: String?,
        val shopAddress: String?,
        val items: List<Item>,
        val subtotal: Double,
        val discount: Double,
        val total: Double,
        val payment: String,
        val received: Double?,
        val change: Double,
        val customerName: String?,
        val customerPhone: String?,
    )

    fun parse(json: String): Receipt {
        val o = JSONObject(json)
        val shop = o.optJSONObject("shop") ?: JSONObject()
        val customer = o.optJSONObject("customer") ?: JSONObject()
        val items = o.optJSONArray("items")
        val list = (0 until (items?.length() ?: 0)).map { i ->
            val it = items!!.getJSONObject(i)
            Item(it.optString("name"), it.optInt("qty", 1), it.optDouble("price", 0.0), it.optDouble("total", 0.0))
        }
        val at = runCatching { java.time.Instant.parse(o.optString("at")).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
        fun str(obj: JSONObject, key: String) = obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
        return Receipt(
            number = o.optString("number"),
            atMillis = at,
            shopName = shop.optString("name"),
            shopPhone = str(shop, "phone"),
            shopAddress = str(shop, "address"),
            items = list,
            subtotal = o.optDouble("subtotal", 0.0),
            discount = o.optDouble("discount", 0.0),
            total = o.optDouble("total", 0.0),
            payment = o.optString("payment"),
            received = if (o.isNull("received")) null else o.optDouble("received"),
            change = o.optDouble("change", 0.0),
            customerName = str(customer, "name"),
            customerPhone = str(customer, "phone"),
        )
    }

    /** Words for the receipt in the app's language. */
    data class Words(val receipt: String, val subtotal: String, val discount: String, val total: String, val paid: String, val change: String, val due: String, val thanks: String, val payment: (String) -> String)

    val BN = Words("রসিদ", "সাবটোটাল", "ছাড়", "মোট", "দিয়েছেন", "ফেরত", "বাকি", "ধন্যবাদ, আবার আসবেন!") { p ->
        when (p) { "cash" -> "নগদ"; "bkash" -> "বিকাশ"; "nagad" -> "নগদ (Nagad)"; "due" -> "বাকি"; else -> "অন্যান্য" }
    }
    val EN = Words("Receipt", "Subtotal", "Discount", "Total", "Paid", "Change", "Due", "Thank you, come again!") { p ->
        when (p) { "cash" -> "Cash"; "bkash" -> "bKash"; "nagad" -> "Nagad"; "due" -> "Due"; else -> "Other" }
    }

    fun draw(r: Receipt, widthDots: Int, words: Words): Bitmap {
        val w = widthDots
        val pad = (w * 0.02f).roundToInt()
        val inner = w - pad * 2
        val unit = w / 384f
        val normal = paint(22f * unit)
        val small = paint(19f * unit)
        val bold = paint(23f * unit, bold = true)
        val title = paint(32f * unit, bold = true)
        val big = paint(36f * unit, bold = true)
        val money = NumberFormat.getIntegerInstance(Locale.US)
        fun tk(v: Double) = "৳" + money.format(v.roundToInt())

        // Draw onto a tall canvas, then crop to what was used.
        val bmp = Bitmap.createBitmap(w, 6000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        var y = pad.toFloat()

        fun block(text: String, p: TextPaint, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, width: Int = inner, x: Float = pad.toFloat()) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(align).build()
            c.save()
            c.translate(x, y)
            layout.draw(c)
            c.restore()
            y += layout.height
        }
        fun row(left: String, right: String, p: TextPaint) {
            val rightW = p.measureText(right)
            val leftLayout = StaticLayout.Builder.obtain(left, 0, left.length, p, (inner - rightW - 8 * unit).toInt().coerceAtLeast(40)).build()
            c.save(); c.translate(pad.toFloat(), y); leftLayout.draw(c); c.restore()
            c.drawText(right, w - pad - rightW, y - p.ascent(), p)
            y += maxOf(leftLayout.height.toFloat(), p.descent() - p.ascent())
        }
        fun rule() {
            y += 6 * unit
            val line = Paint().apply { color = Color.BLACK; strokeWidth = 2f * unit; pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * unit, 4f * unit), 0f) }
            c.drawLine(pad.toFloat(), y, (w - pad).toFloat(), y, line)
            y += 8 * unit
        }

        block(r.shopName, title, Layout.Alignment.ALIGN_CENTER)
        listOfNotNull(r.shopPhone, r.shopAddress).forEach { block(it, small, Layout.Alignment.ALIGN_CENTER) }
        rule()
        row("${words.receipt} #${r.number.removePrefix("SF-")}", SimpleDateFormat("dd/MM/yy HH:mm", Locale.US).format(Date(r.atMillis)), small)
        if (r.customerName != null || r.customerPhone != null) row(r.customerName.orEmpty(), r.customerPhone.orEmpty(), small)
        rule()
        r.items.forEach { item ->
            block(item.name, normal)
            row("  ${item.qty} × ${tk(item.price)}", tk(item.total), small)
            y += 2 * unit
        }
        rule()
        if (r.discount > 0) {
            row(words.subtotal, tk(r.subtotal), normal)
            row(words.discount, "−" + tk(r.discount), normal)
        }
        row(words.total, tk(r.total), big)
        y += 4 * unit
        if (r.payment == "due") {
            row(words.due, tk(r.total), bold)
        } else {
            row(words.payment(r.payment), r.received?.let { tk(it) } ?: tk(r.total), normal)
            if (r.change > 0) row(words.change, tk(r.change), bold)
        }
        rule()
        block(words.thanks, normal, Layout.Alignment.ALIGN_CENTER)
        block("Sopifo", small, Layout.Alignment.ALIGN_CENTER)
        y += pad * 2

        val height = y.roundToInt().coerceIn(1, bmp.height)
        val out = Bitmap.createBitmap(bmp, 0, 0, w, height)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun paint(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
}
