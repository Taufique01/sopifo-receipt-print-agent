package com.sopifo.printagent.ui.print

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.sopifo.printagent.bt.FoundDevice
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.print.PrinterState
import com.sopifo.printagent.quick.QuickLabelSize
import com.sopifo.printagent.ui.screens.printerStateGood
import com.sopifo.printagent.ui.shell.AppSettings
import com.sopifo.printagent.ui.shell.HelpIcon
import com.sopifo.printagent.ui.shell.S
import com.sopifo.printagent.ui.shell.Tile
import com.sopifo.printagent.ui.shell.strings
import com.sopifo.printagent.ui.theme.AppIcons
import com.sopifo.printagent.ui.theme.StatusBad
import com.sopifo.printagent.ui.theme.StatusGood
import com.sopifo.printagent.ui.theme.StatusNeutral

enum class QuickStart { PDF, PHOTO, TEXT }

/** Label sizes offered on the Label printer card (courier labels). */
private val LABEL_SIZES = listOf(QuickLabelSize.S2X3, QuickLabelSize.S3X2, QuickLabelSize.S3X3, QuickLabelSize.S3X4)

/**
 * Printers (app 2.0.1): two big sections — Label printer and Receipt printer. Each has one clear
 * action (Select printer / Change), only the settings a beginner needs (label size + gap, receipt
 * paper) and Test. Below: printing from other apps and Quick print.
 */
@Composable
fun PrintersHubScreen(
    onBack: () -> Unit,
    onAdvanced: () -> Unit,
    onQuick: ((QuickStart) -> Unit)?,
    vm: PrintersViewModel = viewModel(),
) {
    val t = strings()
    val context = LocalContext.current
    val configs by vm.configs.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val picker by vm.picker.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    val label = configs.firstOrNull { it.role == PrinterRole.LABEL }
    val receipt = configs.firstOrNull { it.role == PrinterRole.RECEIPT }
    val size by AppSettings.get(context).labelSize.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().testTag("printers_hub")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("printers_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t.back) }
                Text(t.printers, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                HelpIcon(page = "Printers")
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Section(AppIcons.Tag, t.labelPrinter, label, status[PrinterRole.LABEL], picker.busy == PrinterRole.LABEL, t,
                    onSelect = { vm.openPicker(PrinterRole.LABEL) }, onTest = { vm.test(PrinterRole.LABEL) }, onRemove = { vm.remove(PrinterRole.LABEL) },
                    onAdvanced = onAdvanced, tag = "label") {
                    ChipRow(t.size) {
                        LABEL_SIZES.forEach { s ->
                            FilterChip(selected = size == s, onClick = { AppSettings.get(context).setLabelSize(s) }, label = { Text(s.title.substringBefore(" (")) }, modifier = Modifier.testTag("size_${s.name}"))
                        }
                    }
                    ChipRow(t.gap) {
                        listOf(0, 2, 3).forEach { mm ->
                            FilterChip(
                                selected = label?.labelGapMm == mm && label.labelBlackMark.not(),
                                onClick = { vm.setGap(mm) },
                                label = { Text(if (mm == 0) t.none else "$mm mm") },
                                modifier = Modifier.testTag("gap_$mm"),
                            )
                        }
                        TextButton(onClick = vm::calibrate) { Text(t.calibrate) }
                    }
                }
                Section(AppIcons.Receipt, t.receiptPrinter, receipt, status[PrinterRole.RECEIPT], picker.busy == PrinterRole.RECEIPT, t,
                    onSelect = { vm.openPicker(PrinterRole.RECEIPT) }, onTest = { vm.test(PrinterRole.RECEIPT) }, onRemove = { vm.remove(PrinterRole.RECEIPT) },
                    onAdvanced = onAdvanced, tag = "receipt") {
                    ChipRow(t.paper) {
                        listOf(58, 80).forEach { mm ->
                            FilterChip(selected = receipt?.paperWidthMm == mm, onClick = { vm.setPaper(mm) }, label = { Text("$mm mm") }, modifier = Modifier.testTag("paper_$mm"))
                        }
                    }
                }
                // Print → Sopifo from the Pathao / Steadfast app, Chrome…
                Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(AppIcons.Truck, contentDescription = null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(t.printFromApps, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(t.printFromAppsBody, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) } }, modifier = Modifier.testTag("print_service_settings")) {
                            Text(t.turnOnPrintService)
                        }
                    }
                }
                if (onQuick != null) {
                    Text("⚡ ${t.quickPrint}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(AppIcons.Pdf, t.pdf, Modifier.weight(1f).testTag("quick_pdf")) { onQuick(QuickStart.PDF) }
                        Tile(AppIcons.Photo, t.photo, Modifier.weight(1f).testTag("quick_photo")) { onQuick(QuickStart.PHOTO) }
                        Tile(AppIcons.Bold, t.text, Modifier.weight(1f).testTag("quick_text")) { onQuick(QuickStart.TEXT) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (picker.role != null) PickerSheet(picker, vm, t)
    picker.asking?.let {
        AlertDialog(
            onDismissRequest = { vm.answer(true) },
            title = { Text(t.didItPrint, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BigAnswer(Icons.Filled.Check, t.yes, StatusGood, "answer_yes") { vm.answer(true) }
                    BigAnswer(Icons.Filled.Close, t.no, StatusBad, "answer_no") { vm.answer(false) }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun Section(
    icon: ImageVector,
    title: String,
    config: PrinterConfigEntity?,
    state: PrinterState?,
    busy: Boolean,
    t: S,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
    onAdvanced: () -> Unit,
    tag: String,
    settings: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.5.dp, if (config != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("section_$tag"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title.uppercase(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                if (config != null) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("⚙ Advanced") }, onClick = { menu = false; onAdvanced() })
                            DropdownMenuItem(text = { Text("🗑") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onRemove() })
                        }
                    }
                }
            }
            if (config == null) {
                Button(onClick = onSelect, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("select_$tag")) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(t.selectPrinter, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            } else {
                val good = printerStateGood(state)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(config.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Surface(Modifier.size(12.dp), shape = CircleShape, color = when (good) { true -> StatusGood; false -> StatusBad; null -> StatusNeutral }) {}
                    Text(" " + if (good == true) t.ready else t.notReady, style = MaterialTheme.typography.labelLarge)
                }
                settings()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onTest, enabled = !busy, modifier = Modifier.weight(1f).testTag("test_$tag")) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(AppIcons.Print, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(t.testPrint)
                    }
                    OutlinedButton(onClick = onSelect, modifier = Modifier.weight(1f).testTag("change_$tag")) { Text(t.change) }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(p: PickerState, vm: PrintersViewModel, t: S) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++; vm.scan() }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++; vm.scan() }
    val hasPermission = remember(tick) { vm.finder.hasPermissions() }
    val btOn = remember(tick) { vm.finder.isEnabled }
    val title = if (p.role == PrinterRole.LABEL) t.labelPrinter else t.receiptPrinter
    ModalBottomSheet(onDismissRequest = vm::closePicker, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding().testTag("printer_picker"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${t.selectPrinter} · $title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::scan, enabled = !p.scanning && hasPermission && btOn, modifier = Modifier.testTag("picker_search")) {
                    Icon(Icons.Filled.Search, contentDescription = t.searchAgain)
                }
            }
            when {
                !hasPermission -> PromptRow(AppIcons.Bluetooth, t.allowNearby, t.allow) { permissions.launch(vm.finder.neededPermissions().toTypedArray()) }
                !btOn -> PromptRow(AppIcons.Bluetooth, t.bluetoothOff, t.turnOn) {
                    runCatching { enableBt.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
                }
                else -> {
                    if (p.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (p.pairFailed) Text(t.pairFailed, color = MaterialTheme.colorScheme.error)
                    val visible = if (p.showAll) p.devices else p.devices.filter { it.printerLike || it.paired }
                    val paired = visible.filter { it.paired }
                    val nearby = visible.filter { !it.paired }
                    if (paired.isNotEmpty()) Text(t.paired.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    paired.forEach { d -> DeviceRow(d, p.pairingAddress == d.address, t) { vm.choose(d) } }
                    Text("${t.nearby.uppercase()}${if (p.scanning) " · ${t.searching}" else ""}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    nearby.forEach { d -> DeviceRow(d, p.pairingAddress == d.address, t) { vm.choose(d) } }
                    if (!p.scanning && nearby.isEmpty()) Text(t.noPrinterFound, style = MaterialTheme.typography.bodyMedium)
                    val hidden = p.devices.count { !it.printerLike && !it.paired }
                    if (hidden > 0 || p.showAll) TextButton(onClick = vm::toggleShowAll) { Text(if (p.showAll) "▴" else "▸ +$hidden") }
                    if (!p.scanning) OutlinedButton(onClick = vm::scan, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(t.searchAgain)
                    }
                    Text("💡 ${t.pinHint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, pairing: Boolean, t: S, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !pairing, onClick = onClick).testTag("device_${d.address}"),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (d.printerLike) AppIcons.Print else AppIcons.Bluetooth, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                pairing -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                d.paired -> Icon(Icons.Filled.Check, null, tint = StatusGood)
                else -> Text(t.pair, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PromptRow(icon: ImageVector, text: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("picker_prompt")) { Text(action, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun BigAnswer(icon: ImageVector, label: String, color: Color, tag: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(8.dp).testTag(tag)) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(76.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(44.dp)) }
        }
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
