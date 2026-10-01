package com.sopifo.printagent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.ui.screens.DiagnosticsScreen
import com.sopifo.printagent.ui.screens.HelpScreen
import com.sopifo.printagent.ui.screens.HistoryScreen
import com.sopifo.printagent.ui.screens.HomeScreen
import com.sopifo.printagent.ui.screens.PendingScreen
import com.sopifo.printagent.ui.screens.PrintersScreen
import com.sopifo.printagent.ui.screens.RegistrationScreen
import com.sopifo.printagent.ui.theme.AppIcons

enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    PENDING("Pending", Icons.AutoMirrored.Filled.List),
    HISTORY("History", Icons.Filled.DateRange),
    PRINTERS("Printers", AppIcons.Print),
    // "Diagnostics" wraps onto two lines in a five-item bar on narrow phones.
    DIAGNOSTICS("Diagnose", AppIcons.Pulse),
}

@Composable
fun AppRoot(vm: AgentViewModel, onRequestPermissions: () -> Unit) {
    val registered by vm.registered.collectAsStateWithLifecycle()
    var showHelp by rememberSaveable { mutableStateOf(false) }
    if (showHelp) {
        HelpScreen(onClose = { showHelp = false })
        return
    }
    when (registered) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        false -> RegistrationScreen(vm, onHelp = { showHelp = true })
        true -> MainTabs(vm, onRequestPermissions, onHelp = { showHelp = true })
    }
}

/** The "i" button that opens the printer setup help page. */
@Composable
fun HelpButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.testTag("help_button")) {
        Icon(Icons.Outlined.Info, contentDescription = "Printer setup help")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(vm: AgentViewModel, onRequestPermissions: () -> Unit, onHelp: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Sopifo Print") },
                actions = { HelpButton(onHelp) },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.title, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip) },
                        modifier = Modifier.testTag("tab_${t.name.lowercase()}"),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(vm, onRequestPermissions)
                Tab.PENDING -> PendingScreen(vm)
                Tab.HISTORY -> HistoryScreen(vm)
                Tab.PRINTERS -> PrintersScreen(vm)
                Tab.DIAGNOSTICS -> DiagnosticsScreen(vm)
            }
        }
    }
}
