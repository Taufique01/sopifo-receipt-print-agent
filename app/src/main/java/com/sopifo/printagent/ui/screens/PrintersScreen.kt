package com.sopifo.printagent.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.ui.AgentViewModel
import com.sopifo.printagent.ui.BondedDevice

private data class LabelSize(val w: Int, val h: Int)
private val LABEL_SIZES = listOf(LabelSize(40, 30), LabelSize(50, 30), LabelSize(60, 40), LabelSize(100, 50))

@Composable
fun PrintersScreen(vm: AgentViewModel) {
    val context = LocalContext.current
    val configs by vm.printerConfigs.collectAsStateWithLifecycle()
    val status by vm.printerStatus.collectAsStateWithLifecycle()
    val bonded by vm.bondedDevices.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<PrinterRole?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Printers")
        Text("Bluetooth only. Pair printers in Android Bluetooth settings, then choose them here.", style = MaterialTheme.typography.bodySmall)
        for (role in PrinterRole.entries) {
            val config = configs.firstOrNull { it.role == role }
            SectionCard(title = if (role == PrinterRole.RECEIPT) "Receipt printer" else "Label printer") {
                StatusRow("Status", printerStateText(status[role]), printerStateGood(status[role]))
                if (config == null) {
                    Text("Not configured")
                } else {
                    StatusRow("Name", config.name)
                    StatusRow("Bluetooth MAC", config.macAddress)
                    PrinterSettings(config, vm::updatePrinterSettings)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.loadBondedDevices(); picking = role }, modifier = Modifier.testTag("choose_${role.name.lowercase()}")) {
                        Text(if (config == null) "Choose printer" else "Change")
                    }
                    if (config != null) OutlinedButton(onClick = { vm.removePrinter(role) }) { Text("Remove") }
                }
            }
        }
        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) {
            Text("Open Bluetooth settings")
        }
    }

    picking?.let { role ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("Choose ${if (role == PrinterRole.RECEIPT) "receipt" else "label"} printer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (bonded.isEmpty()) Text("No paired devices found.")
                    bonded.forEach { device ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                savePicked(vm, role, device)
                                picking = null
                            }.padding(vertical = 10.dp),
                        ) {
                            Text(device.name)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Close") } },
        )
    }
}

private fun savePicked(vm: AgentViewModel, role: PrinterRole, device: BondedDevice) = when (role) {
    PrinterRole.RECEIPT -> vm.savePrinter(role, device, PrinterProtocol.ESC_POS, widthDots = 576, labelWidthMm = 50, labelHeightMm = 30)
    PrinterRole.LABEL -> vm.savePrinter(role, device, PrinterProtocol.TSPL, widthDots = 400, labelWidthMm = 50, labelHeightMm = 30)
}

@Composable
private fun PrinterSettings(config: PrinterConfigEntity, onChange: (PrinterConfigEntity) -> Unit) {
    Text("Command language", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = config.protocol == PrinterProtocol.ESC_POS, onClick = { onChange(config.copy(protocol = PrinterProtocol.ESC_POS)) }, label = { Text("ESC/POS") })
        FilterChip(selected = config.protocol == PrinterProtocol.TSPL, onClick = { onChange(config.copy(protocol = PrinterProtocol.TSPL)) }, label = { Text("TSPL") })
    }
    if (config.protocol == PrinterProtocol.ESC_POS) {
        Text("Paper width", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = config.widthDots == 384, onClick = { onChange(config.copy(widthDots = 384)) }, label = { Text("58 mm") })
            FilterChip(selected = config.widthDots == 576, onClick = { onChange(config.copy(widthDots = 576)) }, label = { Text("80 mm") })
        }
    } else {
        Text("Label size", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LABEL_SIZES.forEach { s ->
                FilterChip(
                    selected = config.labelWidthMm == s.w && config.labelHeightMm == s.h,
                    onClick = { onChange(config.copy(labelWidthMm = s.w, labelHeightMm = s.h, widthDots = s.w * 8)) },
                    label = { Text("${s.w}×${s.h}") },
                )
            }
        }
    }
}
