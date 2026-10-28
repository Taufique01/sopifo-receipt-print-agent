package com.sopifo.printagent.ui.pages

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.androidbrowserhelper.trusted.TwaLauncher
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.ui.theme.AppIcons

/**
 * Pages on the Sopifo website the app sends the seller to. They open in a Chrome Custom Tab (a
 * browser tab over the app, ✕ returns here) so the seller's browser login, Facebook login,
 * printing, uploads and EPS payments all work — none of which work in a plain WebView.
 */
object WebLinks {
    const val DASHBOARD = "dashboard"
    const val CLOUD_PRINT = "dashboard/print"
    /** Cloud Print opened on its Settings tab: receipt and label layout, paper size. */
    const val PRINT_SETTINGS = "dashboard/print?tab=settings"
    const val UNLOCK = "dashboard/print?unlock=1"
    const val ORDERS = "dashboard/orders"
    const val COURIERS = "dashboard/couriers"
    const val BILLING = "dashboard/billing"
    /** How to delete the Sopifo account and the app's data (Google Play's account deletion link). */
    const val DATA_DELETION = "data-deletion"
    /** Where the seller dashboard lives (before pairing, the app doesn't know its server yet). */
    private const val FALLBACK_BASE = "https://app.sopifo.com"

    // Custom Tabs protocol extras (what androidx.browser's CustomTabsIntent sends).
    private const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"
    private const val EXTRA_TOOLBAR_COLOR = "android.support.customtabs.extra.TOOLBAR_COLOR"
    private const val EXTRA_TITLE_VISIBILITY = "android.support.customtabs.extra.TITLE_VISIBILITY"
    private const val SHOW_PAGE_TITLE = 1

    /** [path] on the server this phone is paired with (sopifo.com before pairing). */
    fun url(baseUrl: String?, path: String): String = "${baseUrl?.trimEnd('/') ?: FALLBACK_BASE}/$path"

    /** The server's unlock link as a path, only when it points at the paired server; else our own. */
    fun unlockPath(baseUrl: String?, fromServer: String?): String {
        val base = baseUrl?.trimEnd('/')
        return if (base != null && fromServer != null && fromServer.startsWith("$base/")) fromServer.removePrefix("$base/") else UNLOCK
    }

    /** Opens [url] in a Custom Tab tinted [toolbarColor]; browsers without Custom Tabs open it as a normal page. */
    fun open(context: Context, url: String, toolbarColor: Int? = null) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            // A session extra (even an empty one) is what makes Chrome open a Custom Tab.
            putExtras(Bundle().apply { putBinder(EXTRA_SESSION, null) })
            toolbarColor?.let { putExtra(EXTRA_TOOLBAR_COLOR, it) }
            putExtra(EXTRA_TITLE_VISIBILITY, SHOW_PAGE_TITLE)
            // From an Activity the tab stacks on the app (back / ✕ return here); otherwise it needs a task.
            if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

/**
 * Opens a dashboard page as a Trusted Web Activity: full screen, no address bar, once
 * app.sopifo.com confirms this app (/.well-known/assetlinks.json); otherwise TwaLauncher falls
 * back to a Chrome Custom Tab. The launcher is released when the screen goes away.
 */
@Composable
fun rememberOpenTrusted(baseUrl: String? = rememberServerBaseUrl()): (path: String) -> Unit {
    val context = LocalContext.current
    val launcher = remember(context) { TwaLauncher(context) }
    DisposableEffect(launcher) { onDispose { launcher.destroy() } }
    return remember(launcher, baseUrl) { { path -> launcher.launch(Uri.parse(WebLinks.url(baseUrl, path))) } }
}

/** Opens a website page in a Custom Tab with the app's colour: `openWeb(WebLinks.ORDERS)`. */
@Composable
fun rememberOpenWeb(baseUrl: String? = rememberServerBaseUrl()): (path: String) -> Unit {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.primary.toArgb()
    return remember(baseUrl, color) { { path -> WebLinks.open(context, WebLinks.url(baseUrl, path), color) } }
}

/**
 * Opens a dashboard page inside the app (DashboardWebScreen), e.g. `LocalOpenDashboard.current(WebLinks.ORDERS)`.
 * Provided by the main screen; Custom Tabs ([rememberOpenWeb]) are for things a WebView can't do.
 */
val LocalOpenDashboard = staticCompositionLocalOf<(path: String) -> Unit> { {} }

/** The server this phone is paired with, for website links. */
@Composable
fun rememberServerBaseUrl(): String? {
    val context = LocalContext.current
    return remember { (context.applicationContext as SopifoApp).container.device.baseUrl }
}

/** Why the Orders / Shipping page is empty: courier & bulk printing isn't unlocked for this shop. */
data class PrintLock(val message: String, val unlockUrl: String?, val price: Int? = null)

/**
 * Shown instead of the list when printing is locked. The pay button comes first: it opens Cloud Print
 * on the website with the unlock dialog (EPS); printing from the dashboard stays as the free option.
 */
@Composable
fun LockedCard(lock: PrintLock, baseUrl: String?) {
    val openDashboard = LocalOpenDashboard.current
    Card(
        Modifier.fillMaxWidth().testTag("print_locked"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Lock, contentDescription = null)
                Text("Pay to use all printing features", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = { openDashboard(WebLinks.unlockPath(baseUrl, lock.unlockUrl)) },
                modifier = Modifier.fillMaxWidth().testTag("unlock_printing"),
            ) { Text(lock.price?.let { "Pay ৳$it to unlock" } ?: "Pay to unlock printing") }
            Text(lock.message, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { openDashboard(WebLinks.ORDERS) },
                modifier = Modifier.fillMaxWidth().testTag("print_from_dashboard"),
            ) { Text("Print from dashboard") }
        }
    }
}
