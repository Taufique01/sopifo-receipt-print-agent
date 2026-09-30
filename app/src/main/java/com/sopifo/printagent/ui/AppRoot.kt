package com.sopifo.printagent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.ui.screens.DiagnosticsScreen
import com.sopifo.printagent.ui.screens.HistoryScreen
import com.sopifo.printagent.ui.screens.HomeScreen
import com.sopifo.printagent.ui.screens.PendingScreen
import com.sopifo.printagent.ui.screens.PrintersScreen
import com.sopifo.printagent.ui.screens.RegistrationScreen

enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    PENDING("Pending", Icons.AutoMirrored.Filled.List),
    HISTORY("History", Icons.Filled.DateRange),
    PRINTERS("Printers", Icons.Filled.Build),
    DIAGNOSTICS("Diagnostics", Icons.Filled.Info),
}

@Composable
fun AppRoot(vm: AgentViewModel, onRequestPermissions: () -> Unit) {
    val registered by vm.registered.collectAsStateWithLifecycle()
    when (registered) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        false -> RegistrationScreen(vm)
        true -> MainTabs(vm, onRequestPermissions)
    }
}

@Composable
private fun MainTabs(vm: AgentViewModel, onRequestPermissions: () -> Unit) {
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
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.title) },
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
