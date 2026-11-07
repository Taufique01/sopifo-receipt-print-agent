package com.sopifo.printagent.ui.shell

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.view.ViewGroup
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.ui.AgentViewModel
import com.sopifo.printagent.ui.pages.ActivityScreen
import com.sopifo.printagent.ui.pages.ActivityViewModel
import com.sopifo.printagent.ui.pages.QuickMode
import com.sopifo.printagent.ui.pages.QuickPrintScreen
import com.sopifo.printagent.ui.pages.QuickPrintViewModel
import com.sopifo.printagent.ui.pages.WebLinks
import com.sopifo.printagent.ui.print.AddPrinterScreen
import com.sopifo.printagent.ui.print.PrintSheet
import com.sopifo.printagent.ui.print.PrintSheetAction
import com.sopifo.printagent.ui.print.Tile
import com.sopifo.printagent.ui.screens.PrintersScreen
import com.sopifo.printagent.ui.screens.printerStateGood
import com.sopifo.printagent.ui.theme.AppIcons
import com.sopifo.printagent.ui.theme.StatusBad
import com.sopifo.printagent.ui.theme.StatusGood
import com.sopifo.printagent.ui.theme.StatusNeutral
import kotlinx.coroutines.launch

/** The bottom bar, like the website's phone menu: Home · AI · (＋) · Orders · Store. */
enum class ShellTab { HOME, AI, PLUS, ORDERS, STORE }

/** Full-screen native pages over the shell. */
enum class Overlay { ADD_PRINTER, QUICK, PRINTERS, RECENT }

private data class StoreItem(val path: String, val icon: ImageVector, val label: (S) -> String)

/** Store menu = the website's phone Store menu, minus pricing / custom domain (no payments in the app). */
private val STORE_ITEMS = listOf(
    StoreItem("dashboard/products", AppIcons.Box) { it.products },
    StoreItem("dashboard/products/new", Icons.Filled.Add) { it.addProduct },
    StoreItem("pos", AppIcons.Cash) { it.pos },
    StoreItem("dashboard/customers", AppIcons.Users) { it.customers },
    StoreItem("dashboard/couriers", AppIcons.Truck) { it.couriers },
    StoreItem("dashboard/print", AppIcons.Print) { it.cloudPrint },
    StoreItem("dashboard/inventory", AppIcons.Barcode) { it.inventory },
    StoreItem("dashboard/facebook", AppIcons.Chat) { it.facebook },
    StoreItem("dashboard/storefront", AppIcons.Store) { it.storefront },
    StoreItem("dashboard/shop", AppIcons.Store) { it.marketplace },
    StoreItem("dashboard/analytics", AppIcons.Chart) { it.analytics },
    StoreItem("dashboard/reviews", Icons.Filled.Star) { it.reviews },
    StoreItem("dashboard/purchasing", AppIcons.Truck) { it.purchasing },
    StoreItem("dashboard/stock-counts", AppIcons.Barcode) { it.stockCounts },
    StoreItem("dashboard/settings", Icons.Filled.Settings) { it.settings },
)

/**
 * App 2.0 main screen: the website in app mode inside one WebView per tab (each keeps its page),
 * the app's own top bar (shop · 🖨 printers · 🔔 · 💬) and bottom bar. Logging in here connects the
 * phone as the shop's printer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(vm: AgentViewModel, onLoggedOut: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val c = (context.applicationContext as SopifoApp).container
    val settings = AppSettings.get(context)
    val t = strings()
    val shop by settings.shop.collectAsStateWithLifecycle()
    val lang by settings.lang.collectAsStateWithLifecycle()
    val registered by vm.registered.collectAsStateWithLifecycle()
    val deviceConfig by vm.deviceConfig.collectAsStateWithLifecycle()
    val configs by vm.printerConfigs.collectAsStateWithLifecycle()
    val status by vm.printerStatus.collectAsStateWithLifecycle()
    val testPrinted by settings.testPrinted.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(ShellTab.HOME) }
    var storePath by rememberSaveable { mutableStateOf("dashboard/settings") }
    var showSheet by remember { mutableStateOf(false) }
    var showStore by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var quickMode by remember { mutableStateOf(QuickMode.PDF) }
    var setupHidden by rememberSaveable { mutableStateOf(false) }
    var linkState by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(lang, deviceConfig?.deviceId) { WebSession.prepareCookies(c, lang, deviceConfig?.deviceId) }

    // Logged in with a shop but this phone isn't the shop's printer yet: connect it, no code.
    LaunchedEffect(shop.slug, registered) {
        if (shop.slug != null && registered == false && linkState == null) {
            linkState = t.linking
            linkState = when (val r = WebSession.linkDevice(c)) {
                WebSession.LinkResult.Linked -> {
                    vm.refreshRegistration()
                    null
                }
                is WebSession.LinkResult.Failed -> "${t.linkFailed} · ${r.message}"
            }
        }
    }

    // File uploads and the camera for the website (product photos, POS scanner).
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }
    var cameraResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraResult?.invoke(granted)
        cameraResult = null
    }

    val views = remember { mutableStateMapOf<ShellTab, WebView>() }
    val host = remember {
        object : WebHost {
            override fun openPrintSheet() { showSheet = true }
            override fun openFacebookLogin() = WebLinks.open(activity, WebSession.facebookStartUrl(activity, c))
            override fun pickFiles(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return runCatching { fileChooser.launch(params.createIntent()); true }.getOrElse { fileCallback = null; false }
            }
            override fun requestCamera(onResult: (Boolean) -> Unit) {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    onResult(true)
                } else {
                    cameraResult = onResult
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }
            override fun onPage(url: String) {
                // Signed out on the website (session ended): back to the start screen.
                val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
                if (path.startsWith("/sign-in") && !WebSession.isLoggedIn(c)) {
                    settings.clearShop()
                    onLoggedOut()
                }
            }
            override fun onLoadState(loading: Boolean, failed: Boolean) = Unit
        }
    }
    fun pathOf(t: ShellTab): String = when (t) {
        ShellTab.HOME -> "dashboard"
        ShellTab.AI -> "dashboard/content-studio"
        ShellTab.PLUS -> if (shop.pos) "pos" else "dashboard/orders/new"
        ShellTab.ORDERS -> "dashboard/orders"
        ShellTab.STORE -> storePath
    }
    fun viewFor(t: ShellTab): WebView = views.getOrPut(t) {
        createSopifoWebView(activity, WebSession.url(c, pathOf(t)), object : WebHost by host {
            override fun onLoadState(loading: Boolean, failed: Boolean) = setLoad(t, loading, failed)
        })
    }
    fun openStore(path: String) {
        storePath = path
        val existing = views[ShellTab.STORE]
        if (existing != null) existing.loadUrl(WebSession.url(c, path)) else viewFor(ShellTab.STORE)
        tab = ShellTab.STORE
        showStore = false
    }

    // Facebook came back from Chrome: finish the sign-in inside the visible web view.
    val authCode by AuthReturn.code.collectAsStateWithLifecycle()
    LaunchedEffect(authCode) {
        val code = authCode ?: return@LaunchedEffect
        AuthReturn.code.value = null
        WebSession.facebookFinishUrl(activity, c, code)?.let { viewFor(tab).loadUrl(it) }
    }
    // Share → Sopifo: Quick print with the file.
    val shared by SharedFile.item.collectAsStateWithLifecycle()
    LaunchedEffect(shared) { if (shared != null) { quickMode = if (shared!!.isPdf) QuickMode.PDF else QuickMode.IMAGE; overlay = Overlay.QUICK } }

    BackHandler(enabled = overlay == null) {
        val wv = views[tab]
        when {
            wv != null && wv.canGoBack() -> wv.goBack()
            tab != ShellTab.HOME -> tab = ShellTab.HOME
            else -> activity.moveTaskToBack(true)
        }
    }

    val (loadTab, tabLoading, tabFailed) = loadingState.value
    val loading = loadTab == tab && tabLoading
    val failed = loadTab == tab && tabFailed

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(shop.name ?: t.appName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("shell_title"))
                    },
                    actions = {
                        PrinterDots(configs.map { it.role }.toSet(), status.mapValues { printerStateGood(it.value) }) { showSheet = true }
                        IconButton(onClick = { openStore("dashboard/notifications") }, modifier = Modifier.testTag("shell_bell")) {
                            Icon(Icons.Filled.Notifications, contentDescription = t.notifications)
                        }
                        HelpIcon(page = "App: ${pathOf(tab)}")
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    BarItem(ShellTab.HOME, tab, Icons.Filled.Home, t.overview) { tab = it }
                    BarItem(ShellTab.AI, tab, AppIcons.Sparkle, t.ai) { tab = it }
                    NavigationBarItem(
                        selected = tab == ShellTab.PLUS,
                        onClick = {
                            if (tab == ShellTab.PLUS) views[ShellTab.PLUS]?.loadUrl(WebSession.url(c, pathOf(ShellTab.PLUS)))
                            tab = ShellTab.PLUS
                        },
                        icon = {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(if (shop.pos) AppIcons.Cash else Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        },
                        label = { Text(if (shop.pos) t.pos else t.addOrder, maxLines = 1) },
                        modifier = Modifier.testTag("tab_plus"),
                    )
                    BarItem(ShellTab.ORDERS, tab, AppIcons.Receipt, t.orders) { tab = it }
                    NavigationBarItem(
                        selected = tab == ShellTab.STORE,
                        onClick = { showStore = true },
                        icon = { Icon(AppIcons.Store, contentDescription = null) },
                        label = { Text(t.store, maxLines = 1) },
                        modifier = Modifier.testTag("tab_store"),
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                key(tab) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().testTag("web_${tab.name.lowercase()}"),
                        factory = { viewFor(tab).also { (it.parent as? ViewGroup)?.removeView(it) } },
                    )
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                if (failed) OfflineCard(t) { views[tab]?.reload() }
                linkState?.let { msg ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(msg, modifier = Modifier.weight(1f))
                            if (msg != t.linking) TextButton(onClick = { linkState = null }) { Text(t.retry) }
                        }
                    }
                }
                val printerDone = configs.isNotEmpty()
                val courierDone = shop.courierCount > 0
                if (tab == ShellTab.HOME && !setupHidden && !(printerDone && testPrinted && courierDone)) {
                    SetupCard(
                        t,
                        printerDone = printerDone,
                        testDone = testPrinted,
                        courierDone = courierDone,
                        onPrinter = { overlay = Overlay.ADD_PRINTER },
                        onCourier = { openStore("dashboard/couriers") },
                        onClose = { setupHidden = true },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        if (showSheet) {
            PrintSheet(vm, onDismiss = { showSheet = false }) { action ->
                showSheet = false
                when (action) {
                    PrintSheetAction.ADD_PRINTER -> overlay = Overlay.ADD_PRINTER
                    PrintSheetAction.PRINTERS -> overlay = Overlay.PRINTERS
                    PrintSheetAction.QUICK_PDF -> { quickMode = QuickMode.PDF; overlay = Overlay.QUICK }
                    PrintSheetAction.QUICK_PHOTO -> { quickMode = QuickMode.IMAGE; overlay = Overlay.QUICK }
                    PrintSheetAction.QUICK_TEXT -> { quickMode = QuickMode.TEXT; overlay = Overlay.QUICK }
                    PrintSheetAction.RECENT -> overlay = Overlay.RECENT
                }
            }
        }
        if (showStore) {
            ModalBottomSheet(onDismissRequest = { showStore = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("store_menu"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(STORE_ITEMS) { item -> Tile(item.icon, item.label(t)) { openStore(item.path) } }
                    item {
                        Tile(Icons.AutoMirrored.Filled.ExitToApp, if (lang == "en") "Log out" else "লগ আউট", tint = StatusBad) {
                            showStore = false
                            WebSession.logOut(c)
                            settings.clearShop()
                            views.values.forEach { it.destroy() }
                            views.clear()
                            onLoggedOut()
                        }
                    }
                }
            }
        }
        if (overlay == Overlay.ADD_PRINTER) {
            BackHandler { overlay = null }
            AddPrinterScreen(onClose = { overlay = null })
        } else overlay?.let { o ->
            FullScreenOverlay(onClose = { overlay = null; SharedFile.item.value = null }) {
                when (o) {
                    Overlay.ADD_PRINTER -> Unit
                    Overlay.QUICK -> QuickOverlay(quickMode)
                    Overlay.PRINTERS -> PrintersScreen(vm)
                    Overlay.RECENT -> ActivityScreen(viewModel<ActivityViewModel>(), vm) { it() }
                }
            }
        }
    }
}

private val loadingState = mutableStateOf(Triple(ShellTab.HOME, false, false))
private fun setLoad(tab: ShellTab, loading: Boolean, failed: Boolean) { loadingState.value = Triple(tab, loading, failed) }

@Composable
private fun QuickOverlay(mode: QuickMode) {
    val qvm: QuickPrintViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    val shared by SharedFile.item.collectAsStateWithLifecycle()
    LaunchedEffect(mode, shared) {
        val file = shared
        when {
            file != null && file.isPdf -> qvm.pickPdf(file.uri)
            file != null -> qvm.pickImage(file.uri)
            else -> qvm.update { it.copy(mode = mode) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        QuickPrintScreen(qvm, snackbar)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarItem(item: ShellTab, current: ShellTab, icon: ImageVector, label: String, onClick: (ShellTab) -> Unit) {
    NavigationBarItem(
        selected = current == item,
        onClick = { onClick(item) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label, maxLines = 1) },
        modifier = Modifier.testTag("tab_${item.name.lowercase()}"),
    )
}

/** 🖨 with a dot per printer (green ready / red not / grey none): opens the Print sheet. */
@Composable
fun PrinterDots(roles: Set<PrinterRole>, good: Map<PrinterRole, Boolean?>, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp).testTag("shell_printers"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(AppIcons.Print, contentDescription = strings().printers)
        if (roles.isEmpty()) {
            Surface(Modifier.size(8.dp), shape = CircleShape, color = StatusBad) {}
        } else {
            PrinterRole.entries.filter { it in roles }.forEach { role ->
                Surface(Modifier.size(8.dp), shape = CircleShape, color = when (good[role]) { true -> StatusGood; false -> StatusBad; null -> StatusNeutral }) {}
            }
        }
    }
}

@Composable
private fun SetupCard(
    t: S,
    printerDone: Boolean,
    testDone: Boolean,
    courierDone: Boolean,
    onPrinter: () -> Unit,
    onCourier: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth().testTag("setup_card"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.setupTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                HelpIcon(page = "Setup")
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = t.back) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Step(AppIcons.Print, t.stepPrinter, printerDone, Modifier.weight(1f), onPrinter)
                Step(AppIcons.Bolt, t.stepTest, testDone, Modifier.weight(1f), onPrinter)
                Step(AppIcons.Truck, t.stepCourier, courierDone, Modifier.weight(1f), onCourier)
            }
        }
    }
}

@Composable
private fun Step(icon: ImageVector, label: String, done: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (done) StatusGood.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.clickable(enabled = !done, onClick = onClick),
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp), tint = if (done) StatusGood else MaterialTheme.colorScheme.primary)
                if (done) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp).align(Alignment.BottomEnd).padding(0.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun OfflineCard(t: S, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📡", style = MaterialTheme.typography.displayMedium)
        Text(t.offline, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Text(" ${t.retry}")
        }
    }
}

@Composable
fun FullScreenOverlay(onClose: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.testTag("overlay_close")) {
                    Icon(Icons.Filled.Close, contentDescription = strings().back)
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
