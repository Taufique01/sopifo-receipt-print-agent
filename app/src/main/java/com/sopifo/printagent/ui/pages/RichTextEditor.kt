package com.sopifo.printagent.ui.pages

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sopifo.printagent.ui.theme.AppIcons
import org.json.JSONObject

/**
 * Quick print → Text: a simple rich text box (bold, italic, underline, size, alignment, bullets).
 * It's an editable page in a WebView; the formatting buttons run the browser's editing commands
 * and every change hands the HTML to [onChange] — the seller never sees any HTML.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun RichTextEditor(html: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val latestOnChange by rememberUpdatedState(onChange)
    var webView by remember { mutableStateOf<WebView?>(null) }
    // The first content only; after that the editor owns the text (re-setting it would move the cursor).
    val initial = remember { html }
    fun exec(command: String, value: String? = null) {
        val arg = value?.let { ", false, ${JSONObject.quote(it)}" } ?: ""
        webView?.evaluateJavascript("document.getElementById('ed').focus(); document.execCommand('$command'$arg);", null)
    }

    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FormatButton(AppIcons.Bold, "Bold") { exec("bold") }
            FormatButton(AppIcons.Italic, "Italic") { exec("italic") }
            FormatButton(AppIcons.Underlined, "Underline") { exec("underline") }
            FormatButton(AppIcons.AlignLeft, "Align left") { exec("justifyLeft") }
            FormatButton(AppIcons.AlignCenter, "Centre") { exec("justifyCenter") }
            FormatButton(AppIcons.AlignRight, "Align right") { exec("justifyRight") }
            FormatButton(AppIcons.BulletList, "Bullet list") { exec("insertUnorderedList") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // execCommand font sizes 1–7; 3 is normal.
            for ((label, size) in listOf("Small" to "2", "Normal" to "3", "Large" to "5", "Extra large" to "6")) {
                OutlinedButton(onClick = { exec("fontSize", size) }, modifier = Modifier.testTag("rich_size_$size")) { Text(label) }
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().height(200.dp).testTag("quick_text"),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.blockNetworkLoads = true
                        webViewClient = WebViewClient()
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onChange(value: String) {
                                post { latestOnChange(value) }
                            }
                        }, "Editor")
                        loadDataWithBaseURL(null, editorPage(initial), "text/html", "utf-8", null)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

@Composable
private fun FormatButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(40.dp).testTag("rich_${label.lowercase().replace(' ', '_')}")) {
        Icon(icon, contentDescription = label)
    }
}

/** An editable page: what's typed there is what prints (black on white). */
private fun editorPage(html: String) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html,body{margin:0;height:100%;background:#fff}
#ed{min-height:100%;box-sizing:border-box;padding:10px 12px;outline:none;color:#000;font-family:sans-serif;font-size:16px;line-height:1.35;word-wrap:break-word}
#ed:empty:before{content:attr(data-placeholder);color:#999}
ul{margin:0;padding-left:22px}
font[size="1"]{font-size:.6em}font[size="2"]{font-size:.8em}font[size="3"]{font-size:1em}font[size="4"]{font-size:1.25em}font[size="5"]{font-size:1.5em}font[size="6"]{font-size:2em}font[size="7"]{font-size:2.5em}
</style></head><body>
<div id="ed" contenteditable="true" data-placeholder="Type what to print…">$html</div>
<script>
const ed = document.getElementById('ed');
const send = () => Editor.onChange(ed.innerText.trim() ? ed.innerHTML : '');
ed.addEventListener('input', send);
</script></body></html>"""
