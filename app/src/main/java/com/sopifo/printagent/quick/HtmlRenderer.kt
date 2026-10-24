package com.sopifo.printagent.quick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.ceil

/**
 * Quick print → HTML: the seller's HTML drawn by an off-screen WebView, one CSS pixel per printer
 * dot (8 per mm), so `width:100%` is the paper's width. JavaScript and file access are off;
 * links don't navigate. Needs WebView.enableSlowWholeDocumentDraw() (SopifoApp) to draw it all.
 */
object HtmlRenderer {
    /** Tallest HTML print from a roll (75 cm). */
    const val MAX_HEIGHT = 6000
    private const val TIMEOUT_MS = 15_000L

    /** Starter shown in the editor. */
    const val SAMPLE = """<h2 style="text-align:center;margin:8px 0">Sopifo Shop</h2>
<p style="text-align:center;margin:0">Thank you for your order!</p>
<hr>
<table style="width:100%">
  <tr><td>T-shirt × 2</td><td style="text-align:right">৳900</td></tr>
  <tr><td><b>Total</b></td><td style="text-align:right"><b>৳900</b></td></tr>
</table>"""

    suspend fun render(context: Context, html: String, widthPx: Int, heightPx: Int?): Bitmap = withContext(Dispatchers.Main) {
        withTimeout(TIMEOUT_MS) {
            val web = WebView(context.applicationContext)
            try {
                web.settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                web.setBackgroundColor(Color.WHITE)
                web.isVerticalScrollBarEnabled = false
                web.isHorizontalScrollBarEnabled = false
                val loaded = CompletableDeferred<Unit>()
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun onPageFinished(view: WebView, url: String?) { loaded.complete(Unit) }
                }
                layout(web, widthPx, heightPx ?: widthPx)
                web.loadDataWithBaseURL(null, document(html, widthPx), "text/html", "utf-8", null)
                loaded.await()
                delay(150) // let layout settle after the page finishes
                @Suppress("DEPRECATION")
                val height = heightPx ?: ceil(web.contentHeight * web.scale).toInt().coerceIn(1, MAX_HEIGHT)
                layout(web, widthPx, height)
                delay(100)
                createBitmap(widthPx, height).also { bmp ->
                    Canvas(bmp).apply { drawColor(Color.WHITE); web.draw(this) }
                }
            } finally {
                web.destroy()
            }
        }
    }

    private fun layout(web: WebView, w: Int, h: Int) {
        web.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        web.layout(0, 0, w, h)
    }

    /** Wraps the seller's HTML (a fragment or a whole page) with a viewport as wide as the paper. */
    private fun document(html: String, widthPx: Int) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=$widthPx">
<style>html,body{margin:0;padding:6px;box-sizing:border-box;width:${widthPx}px;background:#fff;color:#000;font-family:sans-serif;font-size:24px}img{max-width:100%}table{border-collapse:collapse}</style>
</head><body>$html</body></html>"""
}
