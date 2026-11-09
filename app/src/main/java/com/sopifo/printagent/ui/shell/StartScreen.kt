package com.sopifo.printagent.ui.shell

import android.app.Activity
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.sopifo.printagent.ui.theme.AppIcons
import com.sopifo.printagent.ui.theme.StatusGood

enum class StartRoute { LOGIN, SIGNUP, PRINTERS }

/**
 * First open, no account: set up the printer and try prints. Everything else is behind
 * "Log in for full features" (the normal sign-up; a shop is only made through Create shop).
 */
@Composable
fun StartScreen(notice: String?, onOpen: (StartRoute) -> Unit) {
    val t = strings()
    val settings = AppSettings.get(LocalContext.current)
    val lang by settings.lang.collectAsStateWithLifecycle()
    Column(
        // Nav-bar padding + extra room at the end: the Log in button no longer touches the bottom.
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 32.dp).testTag("start_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HelpIcon(page = "Start")
            Spacer(Modifier.weight(1f))
            FilterChip(selected = lang == "bn", onClick = { settings.setLang("bn") }, label = { Text("বাংলা") }, modifier = Modifier.testTag("lang_bn"))
            Spacer(Modifier.size(6.dp))
            FilterChip(selected = lang == "en", onClick = { settings.setLang("en") }, label = { Text("EN") }, modifier = Modifier.testTag("lang_en"))
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // The Sopifo logo (bag + name); on a white pill in dark mode so the dark name stays readable.
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier.testTag("start_logo"),
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.sopifo.printagent.R.drawable.sopifo_logo),
                    contentDescription = t.appName,
                    modifier = Modifier.padding(horizontal = if (dark) 14.dp else 0.dp, vertical = if (dark) 8.dp else 0.dp).height(54.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(t.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        notice?.let {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text("🔓 $it", Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
            }
        }
        BigAction(AppIcons.Print, t.setUpPrinter, primary = true, tag = "start_printers") { onOpen(StartRoute.PRINTERS) }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        com.sopifo.printagent.ui.print.FullFeatures(onLogin = { onOpen(StartRoute.LOGIN) }, onSignup = { onOpen(StartRoute.SIGNUP) })
    }
}

@Composable
private fun BigAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean, tag: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().height(76.dp).testTag(tag).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp), tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
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
