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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    const val UNLOCK = "dashboard/print?unlock=1"
    const val ORDERS = "dashboard/orders"
    const val COURIERS = "dashboard/couriers"
    const val BILLING = "dashboard/billing"
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

/** "Linked to Cloud Print": set-up, history and unlocking live on the website (log in there). */
@Composable
fun CloudPrintLinkCard(modifier: Modifier = Modifier) {
    val openDashboard = LocalOpenDashboard.current
    Card(modifier.fillMaxWidth().testTag("cloud_print_link")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(AppIcons.Print, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Linked to Cloud Print", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Set up printing, see history and unlock courier printing on the Sopifo website.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = { openDashboard(WebLinks.CLOUD_PRINT) },
                modifier = Modifier.testTag("visit_cloud_print"),
            ) { Text("Visit to log in") }
        }
    }
}

/** More → "Sopifo dashboard": the dashboard pages sellers use most, opened inside the app. */
@Composable
fun DashboardLinksCard(modifier: Modifier = Modifier) {
    val openDashboard = LocalOpenDashboard.current
    val links = listOf(
        Triple("Dashboard", "Overview, products and settings", WebLinks.DASHBOARD),
        Triple("Orders", "Create and manage orders, print one at a time", WebLinks.ORDERS),
        Triple("Couriers", "Book Steadfast / Pathao, track parcels", WebLinks.COURIERS),
        Triple("Cloud Print", "Pair phones, print settings, unlock printing", WebLinks.CLOUD_PRINT),
        Triple("Plan & payments", "Pro plan, AI credits and payments", WebLinks.BILLING),
    )
    Card(modifier.fillMaxWidth().testTag("dashboard_links")) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sopifo dashboard", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Opens inside the app", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            links.forEach { (title, subtitle, path) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { openDashboard(path) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("web_${path.substringAfterLast('/')}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open $title", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
