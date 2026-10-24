package com.sopifo.printagent.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sopifo.printagent.security.AppLock
import com.sopifo.printagent.ui.AgentViewModel
import com.sopifo.printagent.ui.screens.DiagnosticsScreen
import com.sopifo.printagent.ui.screens.PrintersScreen
import com.sopifo.printagent.ui.screens.ScreenTitle
import com.sopifo.printagent.ui.theme.AppIcons

enum class MorePage(val title: String, val subtitle: String) {
    ACTIVITY("Activity", "Every print: what printed, what failed and why, retry"),
    PRINTERS("Printers", "Choose printers, send speed, test print"),
    LOCK("App lock", "PIN or fingerprint for orders and shipping"),
    DIAGNOSTICS("Diagnostics", "Status, logs, send logs to support, re-register"),
    HELP("Help", "Setting up the printer and this app"),
    DELETE("Delete account & data", "How to delete your Sopifo account and this app's data"),
}

/** More: Activity plus the setup and support pages, used rarely after setup. */
@Composable
fun MoreScreen(
    agent: AgentViewModel,
    activity: ActivityViewModel,
    lock: AppLock,
    onHelp: () -> Unit,
    openRequest: MorePage?,
    onOpenHandled: () -> Unit,
    lockedContent: @Composable (@Composable () -> Unit) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf<MorePage?>(null) }
    val openWeb = rememberOpenWeb()
    LaunchedEffect(openRequest) {
        if (openRequest != null) {
            page = openRequest
            onOpenHandled()
        }
    }
    val current = page
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            ScreenTitle("More")
            MorePage.entries.forEach { p ->
                MoreRow(p, icon(p)) {
                    when (p) {
                        MorePage.HELP -> onHelp()
                        MorePage.DELETE -> openWeb(WebLinks.DATA_DELETION)
                        else -> page = p
                    }
                }
                HorizontalDivider()
            }
        }
        return
    }
    BackHandler { page = null }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { page = null }, modifier = Modifier.testTag("more_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to More")
            }
            Text(current.title, style = MaterialTheme.typography.titleMedium)
        }
        when (current) {
            MorePage.ACTIVITY -> ActivityScreen(activity, agent, lockedContent)
            MorePage.PRINTERS -> PrintersScreen(agent)
            MorePage.LOCK -> lockedContent { AppLockSettings(lock) }
            MorePage.DIAGNOSTICS -> DiagnosticsScreen(agent)
            MorePage.HELP, MorePage.DELETE -> Unit
        }
    }
}

private fun icon(p: MorePage): ImageVector = when (p) {
    MorePage.ACTIVITY -> Icons.AutoMirrored.Filled.List
    MorePage.PRINTERS -> AppIcons.Print
    MorePage.LOCK -> Icons.Filled.Lock
    MorePage.DIAGNOSTICS -> AppIcons.Pulse
    MorePage.HELP -> Icons.Filled.Info
    MorePage.DELETE -> Icons.Filled.Delete
}

@Composable
private fun MoreRow(page: MorePage, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp).testTag("more_${page.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(page.title, style = MaterialTheme.typography.titleSmall)
            Text(page.subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.width(24.dp))
    }
}
