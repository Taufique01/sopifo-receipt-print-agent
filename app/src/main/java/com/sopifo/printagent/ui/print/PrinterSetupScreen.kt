package com.sopifo.printagent.ui.print

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.sopifo.printagent.bt.FoundDevice
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.quick.QuickLabelSize
import com.sopifo.printagent.ui.screens.printerStateGood
import com.sopifo.printagent.ui.shell.AppSettings
import com.sopifo.printagent.ui.shell.HelpButton
import com.sopifo.printagent.ui.shell.HelpIcon
import com.sopifo.printagent.ui.shell.S
import com.sopifo.printagent.ui.shell.Tile
import com.sopifo.printagent.ui.shell.strings
import com.sopifo.printagent.ui.theme.AppIcons
import com.sopifo.printagent.ui.theme.StatusBad
import com.sopifo.printagent.ui.theme.StatusGood
import com.sopifo.printagent.ui.theme.StatusNeutral

enum class QuickStart { PDF, PHOTO, TEXT }

/** Label sizes the guide offers, drawn to scale (same names as the website's label size). */
private val GUIDE_SIZES = listOf(QuickLabelSize.S3X2, QuickLabelSize.S2X3, QuickLabelSize.S2X3L, QuickLabelSize.S3X3, QuickLabelSize.S3X4)

private val STEP_ORDER = listOf(SetupStep.LABEL, SetupStep.SIZE, SetupStep.TEST, SetupStep.RECEIPT, SetupStep.SERVICE)

/**
 * Printer setup (2.1.1). First time: a guide, one step per screen (printer → size & gap → test
 * → receipt printer → print with Sopifo). Afterwards: the same steps as a checklist; a needed
 * step that's missing or broken is red with "Fix", which opens just that step. Progress is kept
 * on the phone and carried over at login.
 */
@Composable
fun PrinterSetupScreen(
    onBack: () -> Unit,
    onAdvanced: () -> Unit,
    onQuick: ((QuickStart) -> Unit)?,
    loggedIn: Boolean,
    /** Logged in: open a website page (Printing → Design / History) inside the app. */
    onOpenWeb: ((String) -> Unit)? = null,
    onLogin: () -> Unit,
    onSignup: () -> Unit,
    /** Logged in: this phone's link to the shop (Disconnect / Connect phone). */
    phone: PhoneLink? = null,
    vm: PrintersViewModel = viewModel(),
) {
    val t = strings()
    val context = LocalContext.current
    val settings = AppSettings.get(context)
    val flags by settings.setup.collectAsStateWithLifecycle()
    val configs by vm.configs.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    val label = configs.firstOrNull { it.role == PrinterRole.LABEL }
    val receipt = configs.firstOrNull { it.role == PrinterRole.RECEIPT }
    val facts = PrinterFacts(hasLabel = label != null, labelReachable = label?.let { printerStateGood(status[PrinterRole.LABEL]) }, hasReceipt = receipt != null)
    val states = SetupChecklist.states(facts, flags)

    var guideStep by rememberSaveable { mutableStateOf<SetupStep?>(if (!flags.firstSetupDone) SetupStep.LABEL else null) }
    var fixStep by rememberSaveable { mutableStateOf<SetupStep?>(null) }
    var finished by rememberSaveable { mutableStateOf(false) }
    val current = fixStep ?: guideStep

    fun next(from: SetupStep) {
        if (fixStep != null) {
            fixStep = null
            return
        }
        val skipLabels = settings.setup.value.labelSkipped && label == null
        val order = if (skipLabels) listOf(SetupStep.RECEIPT) else STEP_ORDER
        val i = order.indexOf(from)
        val nextStep = order.getOrNull(i + 1)
        if (nextStep == null) {
            vm.finishFirstSetup()
            guideStep = null
            finished = true
        } else {
            guideStep = nextStep
        }
    }

    Box(Modifier.fillMaxSize().testTag("printer_setup")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (fixStep != null) fixStep = null else onBack() }, modifier = Modifier.testTag("setup_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t.back)
                }
                Text(t.printerSetup, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                if (current == null && !finished) {
                    Text("${SetupChecklist.doneCount(states)} / ${STEP_ORDER.size} ✓", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
                }
                HelpIcon(page = "Printer setup: ${current?.name ?: "checklist"}")
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when {
                    finished -> FinishView(t, loggedIn, onLogin, onSignup) { finished = false; onBack() }
                    current != null -> {
                        if (fixStep == null) {
                            val n = STEP_ORDER.indexOf(current) + 1
                            Text(String.format(t.stepOf, n, STEP_ORDER.size), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            LinearProgressIndicator({ n / STEP_ORDER.size.toFloat() }, Modifier.fillMaxWidth().height(8.dp))
                        }
                        when (current) {
                            SetupStep.LABEL -> StepLabel(vm, t, label?.name, onNext = { next(SetupStep.LABEL) }, onOnlyReceipts = {
                                vm.skipLabel()
                                if (fixStep != null) fixStep = null else guideStep = SetupStep.RECEIPT
                            })
                            SetupStep.SIZE -> StepSize(vm, t, label?.labelGapMm, onNext = { vm.confirmSize(); next(SetupStep.SIZE) })
                            SetupStep.TEST -> StepTest(vm, t, onNext = { next(SetupStep.TEST) })
                            SetupStep.RECEIPT -> StepReceipt(vm, t, hasLabel = label != null, receipt?.name, receipt?.paperWidthMm, onNext = { next(SetupStep.RECEIPT) }, onSkip = { vm.skipReceipt(); next(SetupStep.RECEIPT) })
                            SetupStep.SERVICE -> StepService(t, flags.printServiceUsed, if (loggedIn) null else SetupChecklist.freeLeft(flags.freeServicePrints), onNext = { next(SetupStep.SERVICE) })
                        }
                    }
                    else -> ChecklistView(
                        t = t,
                        states = states,
                        detail = { step ->
                            when (step) {
                                SetupStep.LABEL -> if (label == null) t.noPrinter else if (facts.labelReachable == false) t.notReachable else label.name
                                SetupStep.SIZE -> if (states[step] == StepState.DONE) "${settings.labelSize.value.title.substringBefore(" (")} · ${label?.labelGapMm ?: 0} mm" else "—"
                                SetupStep.TEST -> if (states[step] == StepState.DONE) t.printedOk else "—"
                                SetupStep.RECEIPT -> receipt?.let { "${it.name} · ${it.paperWidthMm ?: 58} mm" } ?: t.skipped
                                SetupStep.SERVICE -> if (states[step] == StepState.DONE) t.itWorked else t.notTurnedOn
                            }
                        },
                        onFix = { fixStep = it },
                        onTestLabel = { vm.printSample(PrintersViewModel.Sample.TEST_LABEL) },
                        onTestReceipt = { vm.printSample(PrintersViewModel.Sample.TEST_RECEIPT) },
                        onQuick = onQuick,
                        onAdvanced = onAdvanced,
                        onRunAgain = { guideStep = SetupStep.LABEL },
                        onOpenWeb = onOpenWeb,
                        phone = phone,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun StepTitle(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun BigButton(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp).testTag(tag)) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
    }
}

/** Step 1 (and "Other printer" in step 4): search nearby, tap Use, it pairs and saves. */
@Composable
private fun StepLabel(vm: PrintersViewModel, t: S, current: String?, onNext: () -> Unit, onOnlyReceipts: () -> Unit) {
    StepTitle(AppIcons.Tag, t.stepLabelTitle)
    Text(t.turnOnPrinterHint, style = MaterialTheme.typography.titleMedium)
    if (current != null) {
        Surface(shape = RoundedCornerShape(14.dp), color = StatusGood.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = StatusGood)
                Text(" $current", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = onNext, modifier = Modifier.testTag("keep_printer")) { Text(t.next, fontWeight = FontWeight.Bold) }
            }
        }
    }
    InlineFinder(vm, t, PrinterRole.LABEL, onChosen = onNext)
    FilledTonalButton(onClick = onOnlyReceipts, modifier = Modifier.testTag("only_receipts")) { Text("→ ${t.onlyReceipts}") }
    HelpButton(page = "Setup: label printer")
}

@Composable
private fun InlineFinder(vm: PrintersViewModel, t: S, role: PrinterRole, onChosen: () -> Unit) {
    val context = LocalContext.current
    val p by vm.picker.collectAsStateWithLifecycle()
    val chosen by vm.chosen.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++; vm.startSearch() }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++; vm.startSearch() }
    val hasPermission = remember(tick) { vm.finder.hasPermissions() }
    val btOn = remember(tick) { vm.finder.isEnabled }
    LaunchedEffect(role) { vm.startSearch() }
    LaunchedEffect(chosen) {
        if (chosen == role) {
            vm.consumeChosen()
            onChosen()
        }
    }
    when {
        !hasPermission -> BigButton("🔵 ${t.allowNearby}", "finder_allow") { permissions.launch(vm.finder.neededPermissions().toTypedArray()) }
        !btOn -> BigButton("🔵 ${t.bluetoothOff} · ${t.turnOn}", "finder_bt") {
            runCatching { enableBt.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
        }
        else -> {
            if (p.scanning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(t.searching, fontWeight = FontWeight.SemiBold)
                }
            }
            if (p.pairFailed) Text(t.pairFailed, color = StatusBad, fontWeight = FontWeight.SemiBold)
            val visible = if (p.showAll) p.devices else p.devices.filter { it.printerLike || it.paired }
            visible.forEach { d -> FinderRow(d, p.pairingAddress == d.address, t) { vm.choose(d, forRole = role, autoTest = false) } }
            if (!p.scanning && visible.isEmpty()) Text(t.noPrinterFound)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!p.scanning) FilledTonalButton(onClick = vm::startSearch, modifier = Modifier.testTag("finder_again")) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text(" ${t.searchAgain}")
                }
                val hidden = p.devices.count { !it.printerLike && !it.paired }
                if (hidden > 0 || p.showAll) FilledTonalButton(onClick = vm::toggleShowAll) { Text(if (p.showAll) "▴" else "+$hidden") }
            }
            Text("💡 ${t.pinHint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FinderRow(d: FoundDevice, pairing: Boolean, t: S, onUse: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("finder_${d.address}"),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (d.printerLike) AppIcons.Print else AppIcons.Bluetooth, contentDescription = null, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (d.paired) Text("✓ ${t.paired}", style = MaterialTheme.typography.labelSmall, color = StatusGood)
            }
            if (pairing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Button(onClick = onUse) { Text(t.use, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Step 2: label size cards drawn to scale, gap, calibrate. */
@Composable
private fun StepSize(vm: PrintersViewModel, t: S, gap: Int?, onNext: () -> Unit) {
    val context = LocalContext.current
    val size by AppSettings.get(context).labelSize.collectAsStateWithLifecycle()
    StepTitle(AppIcons.Tag, t.stepSizeTitle)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        GUIDE_SIZES.forEach { s ->
            val selected = s == size
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { vm.setSize(s) }.padding(4.dp).testTag("guide_size_${s.name}")) {
                // 609 dots (3 in) ≈ 72 dp: every card is to the same scale.
                val w = (s.w * 72f / 609f).dp
                val h = (s.h * 72f / 609f).dp
                Box(
                    Modifier.width(w).height(h)
                        .border(if (selected) 3.dp else 1.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.height(4.dp))
                Text(sizeName(s), style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Black else FontWeight.Normal)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t.gap, fontWeight = FontWeight.Bold)
        listOf(0, 2, 3).forEach { mm ->
            FilterChip(selected = gap == mm, onClick = { vm.setGap(mm) }, label = { Text(if (mm == 0) t.none else "$mm mm") }, modifier = Modifier.testTag("guide_gap_$mm"))
        }
    }
    FilledTonalButton(onClick = vm::calibrate, modifier = Modifier.fillMaxWidth()) { Text(t.calibrateHint) }
    BigButton(t.next, "guide_size_next", onClick = onNext)
}

private fun sizeName(s: QuickLabelSize) = when (s) {
    QuickLabelSize.S2X3 -> "2×3 ▯"
    QuickLabelSize.S2X3L -> "2×3 ▭"
    else -> s.title.substringBefore(" (").replace(" × ", "×").replace(" in", "")
}

/** Step 3: test label + sample courier labels, then "Did it print?". */
@Composable
private fun StepTest(vm: PrintersViewModel, t: S, onNext: () -> Unit) {
    val sent by vm.testSent.collectAsStateWithLifecycle()
    val p by vm.picker.collectAsStateWithLifecycle()
    var failed by remember { mutableStateOf(false) }
    StepTitle(AppIcons.Print, t.stepTestTitle)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(AppIcons.Tag, t.testLabel, Modifier.weight(1f).testTag("guide_test_label")) { vm.printSample(PrintersViewModel.Sample.TEST_LABEL) }
        Tile(AppIcons.Truck, t.samplePathao, Modifier.weight(1f).testTag("guide_pathao"), sub = t.shortLabel) { vm.printSample(PrintersViewModel.Sample.PATHAO) }
        Tile(AppIcons.Truck, t.sampleSteadfast, Modifier.weight(1f).testTag("guide_steadfast"), sub = t.shortLabel) { vm.printSample(PrintersViewModel.Sample.STEADFAST) }
    }
    if (p.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (sent) {
        Text(t.didItPrint, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Answer(Icons.Filled.Check, t.yes, StatusGood, "guide_yes") { failed = false; vm.answerTest(true); onNext() }
            Answer(Icons.Filled.Close, t.no, StatusBad, "guide_no") { failed = true; vm.answerTest(false) }
        }
    }
    if (failed) {
        Surface(shape = RoundedCornerShape(14.dp), color = StatusBad.copy(alpha = 0.1f)) {
            Text(t.testTips, Modifier.padding(12.dp))
        }
        HelpButton(page = "Setup: test print failed")
    }
}

@Composable
private fun Answer(icon: ImageVector, label: String, color: Color, tag: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(6.dp).testTag(tag)) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(40.dp)) }
        }
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
    }
}

/** Step 4 (optional): same printer or another, paper width, test receipt / invoice. */
@Composable
private fun StepReceipt(vm: PrintersViewModel, t: S, hasLabel: Boolean, current: String?, paper: Int?, onNext: () -> Unit, onSkip: () -> Unit) {
    var other by rememberSaveable { mutableStateOf(!hasLabel) }
    var chosenPaper by rememberSaveable { mutableIntStateOf(paper ?: 58) }
    StepTitle(AppIcons.Receipt, t.stepReceiptTitle)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t.paper, fontWeight = FontWeight.Bold)
        listOf(58, 80).forEach { mm ->
            FilterChip(selected = chosenPaper == mm, onClick = { chosenPaper = mm; if (current != null) vm.setPaper(mm) }, label = { Text("$mm mm") }, modifier = Modifier.testTag("guide_paper_$mm"))
        }
    }
    if (current != null) {
        Surface(shape = RoundedCornerShape(14.dp), color = StatusGood.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = StatusGood)
                Text(" $current", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(AppIcons.Receipt, t.testReceipt, Modifier.weight(1f).testTag("guide_test_receipt")) { vm.printSample(PrintersViewModel.Sample.TEST_RECEIPT) }
            Tile(AppIcons.Receipt, t.testInvoice, Modifier.weight(1f).testTag("guide_test_invoice")) { vm.printSample(PrintersViewModel.Sample.TEST_INVOICE) }
        }
        BigButton(t.next, "guide_receipt_next", onClick = onNext)
    } else {
        if (hasLabel) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.useLabelPrinterForReceipts(chosenPaper) }, modifier = Modifier.weight(1f).height(52.dp).testTag("guide_same_printer")) { Text(t.samePrinter, fontWeight = FontWeight.Bold) }
                FilledTonalButton(onClick = { other = true }, modifier = Modifier.weight(1f).height(52.dp).testTag("guide_other_printer")) { Text(t.otherPrinter, fontWeight = FontWeight.Bold) }
            }
        }
        if (other) InlineFinder(vm, t, PrinterRole.RECEIPT, onChosen = { vm.setPaper(chosenPaper) })
    }
    FilledTonalButton(onClick = onSkip, modifier = Modifier.testTag("guide_receipt_skip")) { Text("${t.skip} →") }
}

/** Step 5: turn on "Sopifo" in Android's print menu, open the courier app, print → ✅ by itself. */
@Composable
private fun StepService(t: S, worked: Boolean, freeLeft: Int?, onNext: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    StepTitle(AppIcons.Truck, t.stepServiceTitle)
    NumberRow(1, t.serviceStep1) {
        FilledTonalButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) } }, modifier = Modifier.testTag("guide_turn_on")) { Text(t.turnOnPrintService, fontWeight = FontWeight.Bold) }
    }
    NumberRow(2, t.serviceStep2) {}
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { open("https://merchant.pathao.com") }, modifier = Modifier.weight(1f)) { Text("Pathao") }
        FilledTonalButton(onClick = { open("https://steadfast.com.bd/login") }, modifier = Modifier.weight(1f)) { Text("Steadfast") }
        FilledTonalButton(onClick = { open("https://www.google.com") }, modifier = Modifier.weight(1f)) { Text("Chrome") }
    }
    NumberRow(3, t.serviceStep3) {}
    Surface(shape = RoundedCornerShape(16.dp), color = if (worked) StatusGood.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().testTag("guide_service_state")) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (worked) Icon(Icons.Filled.Check, null, tint = StatusGood) else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(if (worked) t.itWorked else t.waitingPrint, fontWeight = FontWeight.Bold)
        }
    }
    // Logged out: how many prints from other apps are still free.
    if (freeLeft != null) {
        Text("🎁 $freeLeft / ${SetupChecklist.FREE_SERVICE_PRINTS} · ${t.fullFeatures}", style = MaterialTheme.typography.bodySmall, color = if (freeLeft == 0) StatusBad else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("free_left"))
    }
    if (worked) BigButton(t.finish, "guide_finish", onClick = onNext)
    else FilledTonalButton(onClick = onNext, modifier = Modifier.testTag("guide_later")) { Text("${t.later} →") }
    HelpButton(page = "Setup: print with Sopifo")
}

@Composable
private fun NumberRow(n: Int, text: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("$n", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black) }
        }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun FinishView(t: S, loggedIn: Boolean, onLogin: () -> Unit, onSignup: () -> Unit, onDone: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text("🎉", style = MaterialTheme.typography.displayLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Text(t.printerReady, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    if (loggedIn) BigButton(t.done, "guide_done", onClick = onDone)
    else {
        HorizontalDivider()
        FullFeatures(onLogin, onSignup)
        FilledTonalButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(t.later) }
    }
}

/** Every step at a glance: ✅ done, 🔴 missing (with Fix), ⚪ skipped. */
@Composable
private fun ChecklistView(
    t: S,
    states: Map<SetupStep, StepState>,
    detail: (SetupStep) -> String,
    onFix: (SetupStep) -> Unit,
    onTestLabel: () -> Unit,
    onTestReceipt: () -> Unit,
    onQuick: ((QuickStart) -> Unit)?,
    onAdvanced: () -> Unit,
    onRunAgain: () -> Unit,
    onOpenWeb: ((String) -> Unit)?,
    phone: PhoneLink?,
) {
    val titles = mapOf(
        SetupStep.LABEL to t.stepLabelTitle,
        SetupStep.SIZE to t.stepSizeTitle,
        SetupStep.TEST to t.stepTestTitle,
        SetupStep.RECEIPT to t.stepReceiptTitle,
        SetupStep.SERVICE to t.stepServiceTitle,
    )
    STEP_ORDER.forEachIndexed { i, step ->
        val state = states[step] ?: StepState.MISSING
        val color = when (state) { StepState.DONE -> StatusGood; StepState.MISSING -> StatusBad; StepState.SKIPPED -> StatusNeutral }
        Surface(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(if (state == StepState.MISSING) 2.dp else 1.dp, if (state == StepState.MISSING) StatusBad else MaterialTheme.colorScheme.outlineVariant),
            color = if (state == StepState.MISSING) StatusBad.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().testTag("check_${step.name.lowercase()}_${state.name.lowercase()}"),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = color, modifier = Modifier.size(30.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        when (state) {
                            StepState.DONE -> Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            else -> Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Black)
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(titles.getValue(step), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
                    Text(detail(step), style = MaterialTheme.typography.bodySmall, color = if (state == StepState.MISSING) StatusBad else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state == StepState.MISSING) {
                    Button(onClick = { onFix(step) }, modifier = Modifier.testTag("fix_${step.name.lowercase()}")) { Text("${t.fix} →", fontWeight = FontWeight.Bold) }
                } else {
                    FilledTonalButton(onClick = { onFix(step) }) { Text(t.change) }
                }
            }
        }
    }
    BatteryCard(t)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(AppIcons.Tag, t.testLabel, Modifier.weight(1f).testTag("check_test_label"), onClick = onTestLabel)
        Tile(AppIcons.Receipt, t.testReceipt, Modifier.weight(1f).testTag("check_test_receipt"), onClick = onTestReceipt)
    }
    if (onQuick != null) {
        Text("⚡ ${t.quickPrint}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(AppIcons.Pdf, t.pdf, Modifier.weight(1f).testTag("quick_pdf")) { onQuick(QuickStart.PDF) }
            Tile(AppIcons.Photo, t.photo, Modifier.weight(1f).testTag("quick_photo")) { onQuick(QuickStart.PHOTO) }
            Tile(AppIcons.Bold, t.text, Modifier.weight(1f).testTag("quick_text")) { onQuick(QuickStart.TEXT) }
        }
    }
    // The shop-wide side lives on the website (Printing): how labels look, what printed.
    if (onOpenWeb != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(AppIcons.Tag, "🎨 ${t.designLink}", Modifier.weight(1f).testTag("open_design")) { onOpenWeb("dashboard/print?tab=settings") }
            Tile(AppIcons.Receipt, "🕘 ${t.historyLink}", Modifier.weight(1f).testTag("open_history")) { onOpenWeb("dashboard/print?tab=history") }
        }
    }
    if (phone != null) PhoneLinkButton(t, phone)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(onClick = onRunAgain, modifier = Modifier.weight(1f).testTag("run_again")) { Text(t.runAgain) }
        FilledTonalButton(onClick = onAdvanced, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Text(" ⋯")
        }
    }
}

/**
 * Create shop / Log in: a bold, motivating card (what a free shop gives, one big "Start now"),
 * then "Already have an account? Log in" as the second, tinted button.
 */
/**
 * Shown while Android may stop Sopifo in the background (battery optimisation on): prints from
 * Chrome / other apps can then stop half way. "Allow" asks Android; App settings for brands
 * (Xiaomi, Oppo, Vivo…) that hide it elsewhere. Hidden once Sopifo is unrestricted.
 */
@android.annotation.SuppressLint("BatteryLife")
@Composable
private fun BatteryCard(t: S) {
    val context = LocalContext.current
    fun unrestricted() = runCatching {
        context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)
    var ok by remember { mutableStateOf(unrestricted()) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { ok = unrestricted() }
    if (ok) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().testTag("battery_card"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔋 ${t.batteryTitle}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
            Text(t.batteryBody, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}")))
                    }
                }, modifier = Modifier.testTag("battery_allow")) { Text(t.batteryAllow, fontWeight = FontWeight.Bold) }
                FilledTonalButton(onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                    }
                }) { Text(t.batteryMore) }
            }
        }
    }
}

/** This phone's link to the shop: whether the shop's prints come to this phone. */
class PhoneLink(val connected: Boolean, val onDisconnect: () -> Unit, val onConnect: () -> Unit)

@Composable
private fun PhoneLinkButton(t: S, phone: PhoneLink) {
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(t.disconnectPhone, fontWeight = FontWeight.Bold) },
            text = { Text(t.disconnectConfirm) },
            confirmButton = {
                Button(
                    onClick = { confirm = false; phone.onDisconnect() },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = StatusBad),
                    modifier = Modifier.testTag("disconnect_confirm"),
                ) { Text(t.disconnectPhone, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { FilledTonalButton(onClick = { confirm = false }) { Text(t.back) } },
        )
    }
    if (phone.connected) {
        OutlinedButton(
            onClick = { confirm = true },
            border = androidx.compose.foundation.BorderStroke(1.dp, StatusBad.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("printers_disconnect"),
        ) {
            Icon(AppIcons.Bluetooth, contentDescription = null, tint = StatusBad)
            Text("  ${t.disconnectPhone}", color = StatusBad, fontWeight = FontWeight.Bold)
        }
    } else {
        Button(
            onClick = phone.onConnect,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = StatusGood),
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("printers_connect"),
        ) {
            Icon(AppIcons.Bluetooth, contentDescription = null)
            Text("  ${t.connectPhone}", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun FullFeatures(onLogin: () -> Unit, onSignup: () -> Unit) {
    val t = strings()
    val brand = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.Transparent,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onSignup).testTag("signup"),
        ) {
            Column(
                Modifier
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(brand, brand.copy(alpha = 0.78f), Color(0xFF7C3AED))))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🚀", style = MaterialTheme.typography.headlineMedium)
                    Column {
                        Text(t.shopCardTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Color.White)
                        Text(t.shopCardSub, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f))
                    }
                }
                val benefits = listOf(
                    AppIcons.Truck to t.benefitCourier,
                    AppIcons.Tag to t.benefitLabels,
                    AppIcons.Cash to t.benefitPos,
                    AppIcons.Bag to t.benefitOnline,
                    AppIcons.Sparkle to t.benefitAi,
                    AppIcons.Store to t.benefitMore,
                )
                benefits.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (icon, label) ->
                            Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.18f), modifier = Modifier.weight(1f)) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${t.startNow} →", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = brand)
                    }
                }
            }
        }
        androidx.compose.material3.FilledTonalButton(onClick = onLogin, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(54.dp).testTag("login")) {
            Text(t.haveAccount, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}
