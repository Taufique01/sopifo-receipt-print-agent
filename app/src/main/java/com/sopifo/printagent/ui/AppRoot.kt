package com.sopifo.printagent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.ui.pages.ActivityViewModel
import com.sopifo.printagent.ui.pages.LockGate
import com.sopifo.printagent.ui.pages.MorePage
import com.sopifo.printagent.ui.pages.MoreScreen
import com.sopifo.printagent.ui.pages.OrdersScreen
import com.sopifo.printagent.ui.pages.QuickPrintScreen
import com.sopifo.printagent.ui.pages.ShippingScreen
import com.sopifo.printagent.ui.screens.printerStateGood
import com.sopifo.printagent.ui.theme.StatusBad
import com.sopifo.printagent.ui.theme.StatusGood
import com.sopifo.printagent.ui.theme.StatusNeutral
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
import com.sopifo.printagent.ui.screens.HelpScreen
import com.sopifo.printagent.ui.screens.HomeScreen
import com.sopifo.printagent.ui.screens.RegistrationScreen
import com.sopifo.printagent.ui.theme.AppIcons

enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    ORDERS("Orders", AppIcons.Receipt),
    SHIPPING("Shipping", AppIcons.Truck),
    QUICK("Quick", AppIcons.Bolt),
    MORE("More", Icons.Filled.Menu),
}

@Composable
fun AppRoot(
    vm: AgentViewModel,
    onRequestPermissions: () -> Unit,
    openRequest: String? = null,
    onOpenHandled: () -> Unit = {},
) {
    val registered by vm.registered.collectAsStateWithLifecycle()
    var showHelp by rememberSaveable { mutableStateOf(false) }
    if (showHelp) {
        HelpScreen(onClose = { showHelp = false })
        return
    }
    when (registered) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        false -> RegistrationScreen(vm, onHelp = { showHelp = true })
        true -> MainTabs(vm, onRequestPermissions, onHelp = { showHelp = true }, openRequest, onOpenHandled)
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
private fun MainTabs(
    vm: AgentViewModel,
    onRequestPermissions: () -> Unit,
    onHelp: () -> Unit,
    openRequest: String?,
    onOpenHandled: () -> Unit,
) {
    val context = LocalContext.current
    val lock = (context.applicationContext as SopifoApp).container.appLock
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    val activity: ActivityViewModel = viewModel()
    // A print notification opens More → Activity.
    var moreRequest by remember { mutableStateOf<MorePage?>(null) }
    LaunchedEffect(openRequest) {
        if (openRequest == MainActivity.OPEN_ACTIVITY) {
            tab = Tab.MORE
            moreRequest = MorePage.ACTIVITY
            onOpenHandled()
        }
    }
    val locked: @Composable (@Composable () -> Unit) -> Unit = { content -> LockGate(lock, onRepair = vm::unregister, content = content) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Sopifo Print") },
                actions = {
                    PrinterStatusChips(vm)
                    HelpButton(onHelp)
                },
            )
        },
        bottomBar = {
            Column {
                RunStrip(activity) { tab = Tab.MORE; moreRequest = MorePage.ACTIVITY }
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
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(vm, onRequestPermissions)
                Tab.ORDERS -> locked { OrdersScreen(viewModel(), snackbar) }
                Tab.SHIPPING -> locked { ShippingScreen(viewModel(), snackbar) }
                Tab.QUICK -> QuickPrintScreen(viewModel(), snackbar)
                Tab.MORE -> MoreScreen(vm, activity, lock, onHelp, moreRequest, { moreRequest = null }, locked)
            }
        }
    }
}

/** Receipt ● Label ●: whether each set-up printer is reachable, on every page. */
@Composable
private fun PrinterStatusChips(vm: AgentViewModel) {
    val configs by vm.printerConfigs.collectAsStateWithLifecycle()
    val status by vm.printerStatus.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for (role in PrinterRole.entries) {
            if (configs.none { it.role == role }) continue
            val good = printerStateGood(status[role])
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("printer_chip_${role.name.lowercase()}")) {
                Surface(Modifier.size(8.dp), shape = CircleShape, color = when (good) { true -> StatusGood; false -> StatusBad; null -> StatusNeutral }) {}
                Text(
                    " " + if (role == PrinterRole.RECEIPT) "Receipt" else "Label",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** While the app prints: "Printing 12 of 40 · Stop", above the tabs on every page. */
@Composable
private fun RunStrip(activity: ActivityViewModel, onOpen: () -> Unit) {
    val run by activity.current.collectAsStateWithLifecycle()
    val r = run ?: return
    val done = r.printed + r.failed
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().testTag("run_strip")) {
        Column(Modifier.clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Printing ${r.title.lowercase()} · $done of ${r.total}" + if (r.failed > 0) " · ${r.failed} failed" else "",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { activity.stop(r.id) }) { Text("Stop") }
            }
            LinearProgressIndicator({ done.toFloat() / r.total.coerceAtLeast(1) }, Modifier.fillMaxWidth())
        }
    }
}
