package com.sopifo.printagent.ui.shell

import android.Manifest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberDrawerState
import com.sopifo.printagent.ui.print.PrinterSetupScreen
import com.sopifo.printagent.ui.print.PhoneLink
import com.sopifo.printagent.ui.MainActivity
import com.sopifo.printagent.ui.print.PrinterFacts
import com.sopifo.printagent.ui.print.SetupChecklist
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
    StoreItem("dashboard/print", AppIcons.Print) { it.printingMenu },
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
fun AppShell(
    vm: AgentViewModel,
    onLoggedOut: () -> Unit,
    /** From a tapped notification (Print → Sopifo refused a print): open Printers. */
    openRequest: String? = null,
    onOpenHandled: () -> Unit = {},
) {
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
    var showStore by remember { mutableStateOf(false) }
    val station by settings.station.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    LaunchedEffect(openRequest) {
        when (openRequest) {
            MainActivity.OPEN_PRINTERS -> { overlay = Overlay.PRINTERS; onOpenHandled() }
            // Already logged in: nothing to do.
            MainActivity.OPEN_LOGIN -> onOpenHandled()
        }
    }
    var quickMode by remember { mutableStateOf(QuickMode.PDF) }
    var setupHidden by rememberSaveable { mutableStateOf(false) }
    var linkState by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(lang, deviceConfig?.deviceId) { WebSession.prepareCookies(c, lang, deviceConfig?.deviceId) }

    // Logged in with a shop but this phone isn't the shop's printer yet: connect it, no code.
    val userDisconnected by settings.userDisconnected.collectAsStateWithLifecycle()
    LaunchedEffect(shop.slug, registered, userDisconnected) {
        if (shop.slug != null && registered == false && !userDisconnected && linkState == null) {
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
        showStore = false
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

    BackHandler(enabled = overlay == null && !showStore) {
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
    // Printer setup carried over from before login; red when a needed step is missing.
    val setupFlags by settings.setup.collectAsStateWithLifecycle()
    val printerMissing = SetupChecklist.anyMissing(
        SetupChecklist.states(
            PrinterFacts(labelCfg != null, labelCfg?.let { printerStateGood(status[PrinterRole.LABEL]) }, configs.any { it.role == PrinterRole.RECEIPT }),
            setupFlags,
        ),
    )
    // One label size for the app and the website: send the one chosen before login, else read the website's.
    LaunchedEffect(shop.slug) { if (shop.slug != null) SizeSync.sync(c) }
    run {
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
                        PrinterChip(chipCfg?.name, if (printerMissing) false else chipCfg?.let { printerStateGood(status[it.role]) }, t) { overlay = Overlay.PRINTERS }
                        IconButton(onClick = { openStore("dashboard/notifications") }, modifier = Modifier.testTag("shell_bell")) {
                            Icon(Icons.Filled.Notifications, contentDescription = t.notifications)
                        }
                        HelpIcon(page = "App: ${pathOf(tab)}")
                    },
                )
            },
            bottomBar = {
                ShellBottomBar(
                    t = t,
                    tab = tab,
                    pos = shop.pos,
                    storeOpen = showStore,
                    onTab = { next ->
                        if (next == ShellTab.PLUS && tab == ShellTab.PLUS) views[ShellTab.PLUS]?.loadUrl(WebSession.url(c, pathOf(ShellTab.PLUS)))
                        tab = next
                    },
                    onStore = { showStore = true },
                )
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
                            if (msg != t.linking) FilledTonalButton(onClick = { linkState = null }) { Text(t.retry) }
                        }
                    }
                }
                if (tab == ShellTab.HOME && !setupHidden && (printerMissing || shop.courierCount == 0)) {
                    SetupCard(
                        t,
                        printerDone = !printerMissing,
                        courierDone = shop.courierCount > 0,
                        onPrinter = { overlay = Overlay.PRINTERS },
                        onOpen = ::openStore,
                        onClose = { setupHidden = true },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        if (showStore) {
            StoreSheet(
                t = t,
                current = if (tab == ShellTab.STORE) storePath else null,
                station = station,
                onDismiss = { showStore = false },
                onItem = ::openStore,
                onStation = settings::setStation,
                onLogOut = {
                    showStore = false
                    WebSession.logOut(c)
                    settings.clearShop()
                    views.values.forEach { it.destroy() }
                    views.clear()
                    onLoggedOut()
                },
            )
        }
        overlay?.let { o ->
            when (o) {
                Overlay.PRINTERS -> FullScreenPage(onClose = { overlay = null }) {
                    PrinterSetupScreen(
                        onBack = { overlay = null },
                        onAdvanced = { overlay = Overlay.ADVANCED },
                        onOpenWeb = { path -> overlay = null; openStore(path) },
                        loggedIn = true,
                        onLogin = {},
                        onSignup = {},
                        // Disconnect / Connect phone lives here (moved from the Store menu).
                        phone = PhoneLink(
                            connected = registered == true,
                            onDisconnect = {
                                settings.setUserDisconnected(true)
                                vm.unregister()
                            },
                            onConnect = {
                                linkState = null
                                settings.setUserDisconnected(false)
                            },
                        ),
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

/**
 * Bottom bar like the website's phone bar: five equal columns, a small icon over a short label,
 * the round (＋) / POS button raised in the middle. Sits above the phone's gesture bar.
 */
@Composable
private fun ShellBottomBar(t: S, tab: ShellTab, pos: Boolean, storeOpen: Boolean, onTab: (ShellTab) -> Unit, onStore: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .drawBehind { drawLine(Color.Black.copy(alpha = 0.08f), androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) },
    ) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BarCell(AppIcons.Dashboard, t.overview, tab == ShellTab.HOME && !storeOpen, "tab_home") { onTab(ShellTab.HOME) }
            BarCell(AppIcons.Sparkle, t.studio, tab == ShellTab.AI && !storeOpen, "tab_ai") { onTab(ShellTab.AI) }
            // The raised round button, like the website's (＋) / POS.
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable(onClick = { onTab(ShellTab.PLUS) }).testTag("tab_plus"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(46.dp).offset(y = (-6).dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (pos) AppIcons.Cash else Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
                    }
                }
                Text(
                    if (pos) t.pos else t.add,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (tab == ShellTab.PLUS && !storeOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.offset(y = (-4).dp),
                )
            }
            BarCell(AppIcons.Bag, t.orders, tab == ShellTab.ORDERS && !storeOpen, "tab_orders") { onTab(ShellTab.ORDERS) }
            BarCell(AppIcons.Store, t.store, storeOpen || tab == ShellTab.STORE, "tab_store", onClick = onStore)
        }
        // The phone's gesture / button bar: the bar's labels never go under it.
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarCell(icon: ImageVector, label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Store menu like the website's phone Store menu: a sheet from the bottom with a 3-column grid
 * of bordered tiles (the open page filled), then print station, help and log out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreSheet(
    t: S,
    current: String?,
    station: Boolean,
    onDismiss: () -> Unit,
    onItem: (String) -> Unit,
    onStation: (Boolean) -> Unit,
    onLogOut: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = null) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp).navigationBarsPadding().testTag("store_menu")) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.store, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("store_close")) { Icon(Icons.Filled.Close, contentDescription = t.back) }
            }
            STORE_ITEMS.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item -> StoreTile(item.icon, item.label(t), current == item.path, Modifier.weight(1f), "store_${item.path.substringAfterLast('/').trimStart('#')}") { onItem(item.path) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            // One shorter joined bar: print station · help · log out.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    BarCell(AppIcons.Station, t.printStation + if (station) " ✓" else "", if (station) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, station, Modifier.weight(1f), "store_station") { onStation(!station) }
                    BarDivider()
                    BarCell(AppIcons.Chat, t.help, WhatsAppGreen, false, Modifier.weight(1f), "store_help") { SupportWhatsApp.open(context, "Menu") }
                    BarDivider()
                    BarCell(Icons.AutoMirrored.Filled.ExitToApp, t.logOut, StatusBad, false, Modifier.weight(1f), "store_logout", onClick = onLogOut)
                }
            }
        }
    }
}

/** A cell of the Store menu's joined bottom bar: icon and label side by side. */
@Composable
private fun BarCell(icon: ImageVector, label: String, tint: Color, active: Boolean, modifier: Modifier, tag: String, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BarDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun StoreTile(icon: ImageVector, label: String, active: Boolean, modifier: Modifier, tag: String, tint: Color? = null, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.height(76.dp).clickable(onClick = onClick).testTag(tag),
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 13.sp)
        }
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
                // Missing ones first, in red; done ones drop off.
                if (!printerDone) Step(AppIcons.Print, t.stepPrinter, false, needed = true, onClick = onPrinter)
                if (!courierDone) Step(AppIcons.Truck, t.stepCourier, false, needed = true) { onOpen("dashboard/couriers") }
                Step(AppIcons.Tag, t.autoPrint, false) { onOpen("dashboard/settings") }
                Step(AppIcons.Box, t.product, false) { onOpen("dashboard/products/new") }
                Step(AppIcons.Cash, t.pos, false) { onOpen("pos") }
            }
        }
    }
}

@Composable
private fun Step(icon: ImageVector, label: String, done: Boolean, needed: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (done) StatusGood.copy(alpha = 0.14f) else if (needed) StatusBad.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.width(84.dp).clickable(enabled = !done, onClick = onClick),
    ) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (done) Icons.Filled.Check else icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = if (done) StatusGood else if (needed) StatusBad else MaterialTheme.colorScheme.primary)
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
        FilledTonalButton(onClick = onRetry) {
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
