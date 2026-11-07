package com.sopifo.printagent.ui.shell

import android.app.Activity
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.ui.pages.WebLinks
import com.sopifo.printagent.ui.print.Tile
import com.sopifo.printagent.ui.theme.AppIcons
import com.sopifo.printagent.ui.theme.StatusGood

enum class StartRoute { LOGIN, SIGNUP, STATION, ADD_PRINTER, QUICK }

/**
 * First open, no account needed: find the printer and print a PDF / photo right away. Logging in
 * (or creating a shop) opens the dashboard and connects this phone as the shop's printer.
 */
@Composable
fun StartScreen(connectedStore: String?, onOpen: (StartRoute) -> Unit, onOpenStation: () -> Unit) {
    val t = strings()
    val settings = AppSettings.get(LocalContext.current)
    val lang by settings.lang.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("start_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Translate, contentDescription = t.language, modifier = Modifier.padding(end = 6.dp))
            FilterChip(selected = lang == "bn", onClick = { settings.setLang("bn") }, label = { Text("বাংলা") }, modifier = Modifier.testTag("lang_bn"))
            Spacer(Modifier.size(6.dp))
            FilterChip(selected = lang == "en", onClick = { settings.setLang("en") }, label = { Text("EN") }, modifier = Modifier.testTag("lang_en"))
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(84.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Print, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(t.appName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text(t.tagline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Icons.Filled.Search, t.findPrinter, Modifier.weight(1f).height(120.dp).testTag("start_find_printer")) { onOpen(StartRoute.ADD_PRINTER) }
            Tile(AppIcons.Pdf, t.printFile, Modifier.weight(1f).height(120.dp).testTag("start_print_file")) { onOpen(StartRoute.QUICK) }
        }
        Button(onClick = { onOpen(StartRoute.LOGIN) }, modifier = Modifier.fillMaxWidth().height(58.dp).testTag("start_login")) {
            Text(t.logIn, style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = { onOpen(StartRoute.SIGNUP) }, modifier = Modifier.fillMaxWidth().height(58.dp).testTag("start_signup")) {
            Icon(AppIcons.Store, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(t.createShop, style = MaterialTheme.typography.titleMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (connectedStore != null) onOpenStation() else onOpen(StartRoute.STATION) }, modifier = Modifier.testTag("start_station")) {
                Icon(AppIcons.Station, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(t.station)
            }
            HelpButton(page = "Start")
        }
        connectedStore?.let { store ->
            Surface(shape = RoundedCornerShape(14.dp), color = StatusGood.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Print, contentDescription = null, tint = StatusGood)
                    Spacer(Modifier.size(8.dp))
                    Text("✓ $store", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Sign in / sign up on the website (app mode). Lands on the dashboard → [onDone]. */
@Composable
fun WebLoginScreen(path: String, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val c = (context.applicationContext as SopifoApp).container
    val lang by AppSettings.get(context).lang.collectAsStateWithLifecycle()
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }
    val authCode by AuthReturn.code.collectAsStateWithLifecycle()
    LaunchedEffect(authCode) {
        val code = authCode ?: return@LaunchedEffect
        AuthReturn.code.value = null
        WebSession.facebookFinishUrl(activity, c, code)?.let { webView?.loadUrl(it) }
    }
    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("login_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings().back) }
            Text(strings().appName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            HelpIcon(page = "Log in")
        }
        if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("login_web"),
            factory = {
                WebSession.prepareCookies(c, lang, null)
                createSopifoWebView(activity, WebSession.url(c, path), object : WebHost {
                    override fun openPrintSheet() = Unit
                    override fun openFacebookLogin() = WebLinks.open(activity, WebSession.facebookStartUrl(activity, c))
                    override fun pickFiles(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean {
                        fileCallback = callback
                        return runCatching { chooser.launch(params.createIntent()); true }.getOrElse { fileCallback = null; false }
                    }
                    override fun requestCamera(onResult: (Boolean) -> Unit) = onResult(false)
                    override fun onPage(url: String) {
                        val p = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
                        if ((p.startsWith("/dashboard") || p.startsWith("/pos")) && WebSession.isLoggedIn(c)) onDone()
                    }
                    override fun onLoadState(loading: Boolean, failed: Boolean) { isLoading = loading }
                }).also { webView = it }
            },
        )
    }
}
