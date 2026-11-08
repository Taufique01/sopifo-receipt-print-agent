package com.sopifo.printagent.ui.shell

import android.Manifest
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberDrawerState
import com.sopifo.printagent.ui.print.PrintersHubScreen
import com.sopifo.printagent.ui.print.QuickStart
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
enum class Overlay { PRINTERS, QUICK, ADVANCED, RECENT }

private data class StoreItem(val path: String, val icon: ImageVector, val label: (S) -> String)

/** Store side menu = the website's sidebar, minus pricing / custom domain (no payments in the app). */
private val STORE_ITEMS = listOf(
    StoreItem("dashboard/products/new", Icons.Filled.Add) { it.addProduct },
    StoreItem("dashboard/products", AppIcons.Box) { it.products },
    StoreItem("pos", AppIcons.Cash) { it.pos },
    StoreItem("#printers", AppIcons.Print) { it.printers },
    StoreItem("dashboard/customers", AppIcons.Users) { it.customers },
    StoreItem("dashboard/couriers", AppIcons.Truck) { it.couriers },
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
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val station by settings.station.collectAsStateWithLifecycle()
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
            override fun openPrintSheet() { overlay = Overlay.PRINTERS }
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
                // POS / orders open: connect the printer now so the first print is instant.
                c.phonePrinter.warmFor(path)
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
        scope.launch { drawer.close() }
        if (path == "#printers") {
            overlay = Overlay.PRINTERS
            return
        }
        storePath = path
        val existing = views[ShellTab.STORE]
        if (existing != null) existing.loadUrl(WebSession.url(c, path)) else viewFor(ShellTab.STORE)
        tab = ShellTab.STORE
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

    BackHandler(enabled = overlay == null && drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = overlay == null && !drawer.isOpen) {
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

    val labelCfg = configs.firstOrNull { it.role == PrinterRole.LABEL }
    val chipCfg = labelCfg ?: configs.firstOrNull()
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(300.dp)) {
                StoreDrawer(
                    t = t,
                    shopName = shop.name,
                    lang = lang,
                    station = station,
                    onItem = ::openStore,
                    onStation = settings::setStation,
                    onLogOut = {
                        scope.launch { drawer.close() }
                        WebSession.logOut(c)
                        settings.clearShop()
                        views.values.forEach { it.destroy() }
                        views.clear()
                        onLoggedOut()
                    },
                )
            }
        },
    ) {
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                // Slim, one line: shop · printer chip · bell · help.
                TopAppBar(
                    expandedHeight = 52.dp,
                    title = {
                        Text(shop.name ?: t.appName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.testTag("shell_title"))
                    },
                    actions = {
                        PrinterChip(chipCfg?.name, chipCfg?.let { printerStateGood(status[it.role]) }, t) { overlay = Overlay.PRINTERS }
                        IconButton(onClick = { openStore("dashboard/notifications") }, modifier = Modifier.testTag("shell_bell")) {
                            Icon(Icons.Filled.Notifications, contentDescription = t.notifications)
                        }
                        HelpIcon(page = "App: ${pathOf(tab)}")
                    },
                )
            },
            bottomBar = {
                // Like the website's phone bar: Overview · Content Studio · (＋) · Orders · Store.
                NavigationBar(Modifier.height(68.dp)) {
                    BarItem(ShellTab.HOME, tab, AppIcons.Dashboard, t.overview) { tab = it }
                    BarItem(ShellTab.AI, tab, AppIcons.Sparkle, t.studio) { tab = it }
                    NavigationBarItem(
                        selected = tab == ShellTab.PLUS,
                        onClick = {
                            if (tab == ShellTab.PLUS) views[ShellTab.PLUS]?.loadUrl(WebSession.url(c, pathOf(ShellTab.PLUS)))
                            tab = ShellTab.PLUS
                        },
                        icon = {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, shadowElevation = 4.dp, modifier = Modifier.size(46.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(if (shop.pos) AppIcons.Cash else Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        },
                        label = { Text(if (shop.pos) t.pos else t.add, maxLines = 1, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.testTag("tab_plus"),
                    )
                    BarItem(ShellTab.ORDERS, tab, AppIcons.Bag, t.orders) { tab = it }
                    NavigationBarItem(
                        selected = tab == ShellTab.STORE,
                        onClick = { scope.launch { drawer.open() } },
                        icon = { Icon(Icons.Filled.Menu, contentDescription = null) },
                        label = { Text(t.store, maxLines = 1, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) },
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
                if (tab == ShellTab.HOME && !setupHidden && !(configs.isNotEmpty() && testPrinted && shop.courierCount > 0)) {
                    SetupCard(
                        t,
                        printerDone = configs.isNotEmpty(),
                        courierDone = shop.courierCount > 0,
                        onPrinter = { overlay = Overlay.PRINTERS },
                        onOpen = ::openStore,
                        onClose = { setupHidden = true },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        overlay?.let { o ->
            when (o) {
                Overlay.PRINTERS -> FullScreenPage(onClose = { overlay = null }) {
                    PrintersHubScreen(
                        onBack = { overlay = null },
                        onAdvanced = { overlay = Overlay.ADVANCED },
                        onQuick = { q ->
                            quickMode = when (q) { QuickStart.PDF -> QuickMode.PDF; QuickStart.PHOTO -> QuickMode.IMAGE; QuickStart.TEXT -> QuickMode.TEXT }
                            overlay = Overlay.QUICK
                        },
                    )
                }
                else -> FullScreenOverlay(onClose = { overlay = if (o == Overlay.ADVANCED) Overlay.PRINTERS else null; SharedFile.item.value = null }) {
                    when (o) {
                        Overlay.QUICK -> QuickOverlay(quickMode)
                        Overlay.ADVANCED -> PrintersScreen(vm)
                        Overlay.RECENT -> ActivityScreen(viewModel<ActivityViewModel>(), vm) { it() }
                        Overlay.PRINTERS -> Unit
                    }
                }
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

/** 🖨 chip in the top bar: the printer's name and a dot, or a red "Add" when none. */
@Composable
fun PrinterChip(name: String?, good: Boolean?, t: S, onClick: () -> Unit) {
    val none = name == null
    Surface(
        shape = RoundedCornerShape(50),
        color = if (none) StatusBad.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(end = 2.dp).clickable(onClick = onClick).testTag("shell_printers"),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(AppIcons.Print, contentDescription = t.printers, modifier = Modifier.size(18.dp), tint = if (none) StatusBad else MaterialTheme.colorScheme.onSurface)
            Text(
                if (none) t.addPrinter else name!!.take(10),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (none) StatusBad else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (!none) Surface(Modifier.size(8.dp), shape = CircleShape, color = when (good) { true -> StatusGood; false -> StatusBad; null -> StatusNeutral }) {}
        }
    }
}

/** Store side menu (like the website's sidebar) with print station, help and log out at the bottom. */
@Composable
private fun StoreDrawer(
    t: S,
    shopName: String?,
    lang: String,
    station: Boolean,
    onItem: (String) -> Unit,
    onStation: (Boolean) -> Unit,
    onLogOut: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp).testTag("store_drawer")) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AppIcons.Store, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text(shopName ?: t.appName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        STORE_ITEMS.forEach { item ->
            NavigationDrawerItem(
                label = { Text(item.label(t), fontWeight = FontWeight.SemiBold) },
                icon = { Icon(item.icon, contentDescription = null) },
                selected = false,
                onClick = { onItem(item.path) },
                modifier = Modifier.padding(horizontal = 10.dp).testTag("drawer_${item.path.substringAfterLast('/').trimStart('#')}"),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Row(Modifier.padding(horizontal = 22.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Station, contentDescription = null)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(t.printStation, fontWeight = FontWeight.SemiBold)
                Text(t.stationBody, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = station, onCheckedChange = onStation, modifier = Modifier.testTag("station_switch"))
        }
        NavigationDrawerItem(
            label = { Text(t.help, fontWeight = FontWeight.SemiBold) },
            icon = { Icon(AppIcons.Chat, contentDescription = null, tint = WhatsAppGreen) },
            selected = false,
            onClick = { SupportWhatsApp.open(context, "Menu") },
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        NavigationDrawerItem(
            label = { Text(t.logOut, fontWeight = FontWeight.SemiBold, color = StatusBad) },
            icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = StatusBad) },
            selected = false,
            onClick = onLogOut,
            modifier = Modifier.padding(horizontal = 10.dp).testTag("drawer_logout"),
        )
        Text(if (lang == "en") "Language: Settings" else "ভাষা: সেটিংস", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Opt-in setup: each item is something the seller may want; ✕ hides the card. */
@Composable
private fun SetupCard(
    t: S,
    printerDone: Boolean,
    courierDone: Boolean,
    onPrinter: () -> Unit,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        modifier = modifier.fillMaxWidth().testTag("setup_card"),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.setupTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.testTag("setup_close")) { Icon(Icons.Filled.Close, contentDescription = t.back) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Step(AppIcons.Print, t.stepPrinter, printerDone, onPrinter)
                Step(AppIcons.Truck, t.stepCourier, courierDone) { onOpen("dashboard/couriers") }
                Step(AppIcons.Tag, t.autoPrint, false) { onOpen("dashboard/settings") }
                Step(AppIcons.Box, t.product, false) { onOpen("dashboard/products/new") }
                Step(AppIcons.Cash, t.pos, false) { onOpen("pos") }
            }
        }
    }
}

@Composable
private fun Step(icon: ImageVector, label: String, done: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (done) StatusGood.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.width(84.dp).clickable(enabled = !done, onClick = onClick),
    ) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (done) Icons.Filled.Check else icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = if (done) StatusGood else MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

/** A native full screen over the shell with its own top row (Printers). */
@Composable
fun FullScreenPage(onClose: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize().statusBarsPadding()) { content() }
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
