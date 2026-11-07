package com.sopifo.printagent.ui.shell

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.ui.pages.WebLinks
import com.sopifo.printagent.ui.screens.DashboardWeb
import org.json.JSONObject

/** What a dashboard WebView needs from the screen around it. */
interface WebHost {
    fun openPrintSheet()
    fun openFacebookLogin()
    fun pickFiles(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean
    fun requestCamera(onResult: (Boolean) -> Unit)
    fun onPage(url: String)
    fun onLoadState(loading: Boolean, failed: Boolean)
}

/**
 * The website inside the app. Same rules as DashboardWeb (other sites and Facebook page connect
 * go to Chrome) plus: app-mode cookies, the `SopifoApp` bridge on sopifo.com pages only, file
 * uploads, the camera for POS scanning, and window.print() through Android's print service.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
fun createSopifoWebView(activity: Activity, startUrl: String, host: WebHost): WebView {
    val app = (activity.applicationContext as SopifoApp).container
    val appHost = DashboardWeb.hostOf(startUrl)
    return WebView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = "${settings.userAgentString} SopifoApp"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        val bridge = SopifoBridge(activity, this, host)
        addJavascriptInterface(bridge, "SopifoApp")

        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                host.pickFiles(params, callback)

            override fun onPermissionRequest(request: PermissionRequest) {
                val ours = DashboardWeb.route(request.origin.toString(), appHost) == DashboardWeb.Route.INSIDE
                if (!ours || !request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    request.deny()
                    return
                }
                host.requestCamera { granted ->
                    if (granted) request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else request.deny()
                }
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val link = request.url.toString()
                return when (DashboardWeb.route(link, appHost)) {
                    DashboardWeb.Route.INSIDE -> false
                    DashboardWeb.Route.CHROME -> {
                        if (request.url.host?.contains("facebook.com") == true && request.url.path.orEmpty().contains("dialog/oauth")) {
                            host.openFacebookLogin()
                        } else {
                            WebLinks.open(activity, link)
                        }
                        true
                    }
                    DashboardWeb.Route.SYSTEM -> {
                        runCatching {
                            val intent = if (link.startsWith("intent:")) Intent.parseUri(link, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, request.url)
                            activity.startActivity(intent)
                        }
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                bridge.trusted = url != null && DashboardWeb.route(url, appHost) == DashboardWeb.Route.INSIDE
                host.onLoadState(loading = true, failed = false)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                bridge.trusted = url != null && DashboardWeb.route(url, appHost) == DashboardWeb.Route.INSIDE
                // A print page asking for the browser print dialog: Android's print service instead.
                if (bridge.trusted) view.evaluateJavascript("window.print=function(){SopifoApp.printUrl(location.href)};", null)
                host.onLoadState(loading = false, failed = false)
                url?.let(host::onPage)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                url?.let(host::onPage)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) host.onLoadState(loading = false, failed = true)
            }
        }
        setDownloadListener { downloadUrl, _, _, _, _ ->
            if (downloadUrl.startsWith("http")) WebLinks.open(activity, downloadUrl)
        }
        AppLog.i("SopifoWeb", "WebView created", "app_registered" to (app.device.baseUrl != null))
        loadUrl(startUrl)
    }
}

/** `window.SopifoApp` for sopifo.com pages (see src/lib/app-mode.ts on the website). */
class SopifoBridge(private val activity: Activity, private val webView: WebView, private val host: WebHost) {
    @Volatile var trusted = false
    private val main = Handler(Looper.getMainLooper())
    private val c = (activity.applicationContext as SopifoApp).container

    @JavascriptInterface
    fun printJob(jobId: String) {
        if (!trusted || !Regex("^[A-Za-z0-9_-]{8,64}$").matches(jobId)) return
        // The job was made for this phone: fetch and print it now (the push is only a backup).
        c.handleWake(jobId, source = "app")
    }

    @JavascriptInterface
    fun printUrl(url: String) {
        if (!trusted) return
        val ok = DashboardWeb.route(url, DashboardWeb.hostOf(WebSession.base(c))) == DashboardWeb.Route.INSIDE
        if (ok) main.post { PagePrinter.print(activity, url) }
    }

    @JavascriptInterface
    fun context(json: String) {
        if (!trusted) return
        runCatching {
            val o = JSONObject(json)
            AppSettings.get(activity).setShop(
                ShopFacts(
                    name = o.optString("shopName").takeIf { it.isNotBlank() && it != "null" },
                    slug = o.optString("shopSlug").takeIf { it.isNotBlank() && it != "null" },
                    pos = o.optString("sellingMode") == "POS",
                    courierCount = o.optInt("courierCount", 0),
                ),
            )
        }
    }

    @JavascriptInterface
    fun setLanguage(locale: String) {
        if (!trusted) return
        main.post {
            AppSettings.get(activity).setLang(locale)
            WebSession.prepareCookies(c, locale, null)
        }
    }

    @JavascriptInterface
    fun openPrintSheet() {
        if (trusted) main.post { host.openPrintSheet() }
    }

    @JavascriptInterface
    fun facebookLogin(next: String) {
        if (trusted) main.post { host.openFacebookLogin() }
    }
}

/** Prints a website page (invoice / label PDF view) with Android's print service. */
object PagePrinter {
    private var holder: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun print(context: Context, url: String) {
        val activity = context as? Activity ?: return
        val view = WebView(activity)
        holder = view
        view.settings.javaScriptEnabled = true
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(page: WebView, loaded: String?) {
                // Give fonts / barcodes a moment, then hand it to Android's print service.
                Handler(Looper.getMainLooper()).postDelayed({
                    val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                    pm.print("Sopifo", page.createPrintDocumentAdapter("Sopifo"), PrintAttributes.Builder().build())
                    holder = null
                }, 700)
            }
        }
        view.loadUrl(url)
    }
}
