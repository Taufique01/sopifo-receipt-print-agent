package com.sopifo.printagent.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sopifo.printagent.ui.pages.WebLinks
import com.sopifo.printagent.ui.theme.AppIcons
import java.net.URI

/**
 * Where a link on the dashboard opens. The dashboard itself stays inside the app; what can't
 * work in a WebView goes to a Chrome tab: Facebook / Google sign-in and Facebook page connect
 * (blocked in WebViews), EPS payments (bKash / Nagad apps, card checks) and any other site.
 */
object DashboardWeb {
    enum class Route { INSIDE, CHROME, SYSTEM }

    fun route(url: String, appHost: String?): Route {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return Route.CHROME
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Route.SYSTEM
        val host = uri.host?.lowercase() ?: return Route.CHROME
        val ours = host == appHost?.lowercase() || host == "sopifo.com" || host.endsWith(".sopifo.com")
        if (!ours) return Route.CHROME
        // Facebook page connect starts on our server and continues on facebook.com.
        if (uri.path.orEmpty().startsWith("/api/facebook")) return Route.CHROME
        return Route.INSIDE
    }

    fun hostOf(url: String?): String? = try {
        url?.let { URI(it).host }
    } catch (_: Exception) {
        null
    }
}

/** The Sopifo dashboard (app.sopifo.com) inside the app, with back, reload and "Open in Chrome". */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardWebScreen(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val toolbarColor = MaterialTheme.colorScheme.primary.toArgb()
    val appHost = remember(url) { DashboardWeb.hostOf(url) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("Sopifo dashboard") }
    // Something opened in Chrome (payment, sign-in): show the result when the seller comes back.
    var handedOff by remember { mutableStateOf(false) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    fun toChrome(link: String) {
        handedOff = true
        WebLinks.open(context, link, toolbarColor)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (handedOff) {
            handedOff = false
            webView?.reload()
        }
    }
    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("dashboard_close")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close dashboard")
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }, modifier = Modifier.testTag("dashboard_reload")) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    IconButton(onClick = { toChrome(webView?.url ?: url) }, modifier = Modifier.testTag("dashboard_open_chrome")) {
                        Icon(AppIcons.OpenInNew, contentDescription = "Open in Chrome")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().testTag("dashboard_web")) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = "${settings.userAgentString} SopifoPrint"
                        // Stay logged in between visits.
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView, pageTitle: String?) {
                                if (!pageTitle.isNullOrBlank()) title = pageTitle
                            }

                            override fun onShowFileChooser(
                                view: WebView,
                                callback: ValueCallback<Array<Uri>>,
                                params: FileChooserParams,
                            ): Boolean {
                                fileCallback?.onReceiveValue(null)
                                fileCallback = callback
                                return try {
                                    fileChooser.launch(params.createIntent())
                                    true
                                } catch (_: Exception) {
                                    fileCallback = null
                                    false
                                }
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val link = request.url.toString()
                                return when (DashboardWeb.route(link, appHost)) {
                                    DashboardWeb.Route.INSIDE -> false
                                    DashboardWeb.Route.CHROME -> {
                                        toChrome(link)
                                        true
                                    }
                                    DashboardWeb.Route.SYSTEM -> {
                                        runCatching {
                                            val intent = if (link.startsWith("intent:")) Intent.parseUri(link, Intent.URI_INTENT_SCHEME)
                                            else Intent(Intent.ACTION_VIEW, request.url)
                                            ctx.startActivity(intent)
                                        }
                                        true
                                    }
                                }
                            }

                            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                                loading = true
                                failed = false
                            }

                            override fun onPageFinished(view: WebView, pageUrl: String?) {
                                loading = false
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) failed = true
                            }
                        }
                        // Files the dashboard downloads (labels, graphics) are saved by Chrome.
                        setDownloadListener { downloadUrl, _, _, _, _ ->
                            if (downloadUrl.startsWith("http")) {
                                toChrome(downloadUrl)
                            } else {
                                Toast.makeText(ctx, "Downloads open in Chrome", Toast.LENGTH_SHORT).show()
                                toChrome(this.url ?: url)
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            if (failed) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Could not load the dashboard. Check the internet connection.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { webView?.reload() }) { Text("Try again") }
                    OutlinedButton(onClick = { toChrome(webView?.url ?: url) }) { Text("Open in Chrome") }
                }
            }
        }
    }
}
