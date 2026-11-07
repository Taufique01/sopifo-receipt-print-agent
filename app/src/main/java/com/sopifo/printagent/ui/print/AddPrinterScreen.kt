package com.sopifo.printagent.ui.print

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sopifo.printagent.bt.FoundDevice
import com.sopifo.printagent.ui.shell.HelpButton
import com.sopifo.printagent.ui.shell.strings
import com.sopifo.printagent.ui.theme.AppIcons
import com.sopifo.printagent.ui.theme.StatusGood

/** Full-screen "Add printer": search → tap → what it prints → test → "Did it print?". */
@Composable
fun AddPrinterScreen(onClose: () -> Unit, vm: PrinterSetupViewModel = viewModel()) {
    val t = strings()
    val context = LocalContext.current
    val s by vm.state.collectAsStateWithLifecycle()
    var permissionTick by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionTick++ }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionTick++ }

    val hasPermission = remember(permissionTick) { vm.finder.hasPermissions() }
    val btOn = remember(permissionTick) { vm.finder.isEnabled }
    LaunchedEffect(hasPermission, btOn) {
        if (hasPermission && btOn && s.step == SetupStep.SCAN && s.devices.isEmpty()) vm.scan()
    }

    Surface(Modifier.fillMaxSize().testTag("add_printer")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.testTag("add_printer_close")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t.back)
                }
                Text(t.addPrinter, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                HelpButton(page = "Add printer")
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    !hasPermission -> BigPrompt(AppIcons.Bluetooth, t.allowNearby, t.allow) {
                        permissions.launch(vm.finder.neededPermissions().toTypedArray())
                    }
                    !btOn -> BigPrompt(AppIcons.Bluetooth, t.bluetoothOff, t.turnOn) {
                        runCatching { enableBt.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
                    }
                    s.step == SetupStep.SCAN -> ScanList(s, vm)
                    s.step == SetupStep.KIND -> KindPicker(s, vm)
                    s.step == SetupStep.TESTING -> {
                        Spacer(Modifier.height(40.dp))
                        CircularProgressIndicator(Modifier.size(64.dp))
                        Text(t.printing, style = MaterialTheme.typography.titleMedium)
                    }
                    s.step == SetupStep.ASK -> {
                        Spacer(Modifier.height(24.dp))
                        Icon(AppIcons.Print, contentDescription = null, modifier = Modifier.size(88.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(t.didItPrint, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            AnswerButton(Icons.Filled.Check, t.yes, StatusGood, Modifier.testTag("printed_yes")) { vm.answer(true) }
                            AnswerButton(Icons.Filled.Close, t.no, MaterialTheme.colorScheme.error, Modifier.testTag("printed_no")) { vm.answer(false) }
                        }
                        HelpButton(page = "Test print")
                    }
                    else -> {
                        Spacer(Modifier.height(40.dp))
                        Surface(shape = CircleShape, color = StatusGood, modifier = Modifier.size(96.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(56.dp)) }
                        }
                        Text(t.ready, style = MaterialTheme.typography.headlineSmall)
                        Text(s.chosen?.name.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("setup_done")) { Text("✓") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanList(s: SetupState, vm: PrinterSetupViewModel) {
    val t = strings()
    if (s.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
    Icon(AppIcons.Bluetooth, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
    Text(if (s.scanning) t.searching else t.findPrinter, style = MaterialTheme.typography.titleMedium)
    Text(t.pinHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (s.error == "pair") Text(t.retry, color = MaterialTheme.colorScheme.error)
    val list = if (s.showAll) s.devices else s.devices.filter { it.printerLike }
    list.forEach { d -> DeviceRow(d, pairing = s.pairingAddress == d.address) { vm.choose(d) } }
    if (!s.scanning && list.isEmpty()) {
        Text(t.noPrinterFound, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::scan, enabled = !s.scanning, modifier = Modifier.testTag("scan_again")) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(t.searchAgain)
        }
        if (s.devices.any { !it.printerLike }) TextButton(onClick = vm::toggleShowAll) { Text(if (s.showAll) "−" else "+ ${s.devices.count { !it.printerLike }}") }
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, pairing: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !pairing, onClick = onClick).testTag("device_${d.address}"),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(if (d.printerLike) AppIcons.Print else AppIcons.Bluetooth, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pairing) CircularProgressIndicator(Modifier.size(24.dp)) else if (d.paired) Icon(Icons.Filled.Check, null, tint = StatusGood)
        }
    }
}

@Composable
private fun KindPicker(s: SetupState, vm: PrinterSetupViewModel) {
    val t = strings()
    Text(s.chosen?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(t.whatPrint, style = MaterialTheme.typography.headlineSmall)
    if (s.error == "noprint") Text(t.retry, color = MaterialTheme.colorScheme.error)
    KindCard(AppIcons.Tag, t.labels, t.labelsHint, s.kind == PrinterKind.LABELS, Modifier.testTag("kind_labels")) { vm.setKind(PrinterKind.LABELS) }
    KindCard(AppIcons.Receipt, t.receipts, t.receiptsHint, s.kind == PrinterKind.RECEIPTS, Modifier.testTag("kind_receipts")) { vm.setKind(PrinterKind.RECEIPTS) }
    if (s.kind == PrinterKind.RECEIPTS) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t.paper)
            listOf(58, 80).forEach { mm ->
                FilterChip(selected = s.paperMm == mm, onClick = { vm.setPaper(mm) }, label = { Text("$mm mm") }, modifier = Modifier.testTag("paper_$mm"))
            }
        }
    }
    Button(onClick = vm::useAndTest, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("use_and_test")) {
        Icon(AppIcons.Print, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("${t.useIt} · ${t.testPrint}", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun KindCard(icon: ImageVector, title: String, hint: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(hint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BigPrompt(icon: ImageVector, text: String, action: String, onClick: () -> Unit) {
    Spacer(Modifier.height(40.dp))
    Icon(icon, contentDescription = null, modifier = Modifier.size(88.dp), tint = MaterialTheme.colorScheme.primary)
    Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("prompt_action")) { Text(action, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun AnswerButton(icon: ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.clickable(onClick = onClick).padding(8.dp)) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(48.dp)) }
        }
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
