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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.data.db.PaperType
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.print.SendSpeed
import com.sopifo.printagent.print.StickerLayout
import com.sopifo.printagent.ui.AgentViewModel
import com.sopifo.printagent.ui.BondedDevice

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
                    if (config.stickerLayout() != null) StickerSetup(config, vm)
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
    PrinterRole.RECEIPT -> vm.savePrinter(role, device, PrinterProtocol.ESC_POS)
    PrinterRole.LABEL -> vm.savePrinter(role, device, PrinterProtocol.TSPL)
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
            for (mm in listOf(58, 80)) {
                FilterChip(
                    selected = config.paperWidthMm == mm,
                    onClick = { onChange(config.copy(paperWidthMm = mm)) },
                    label = { Text("$mm mm") },
                    modifier = Modifier.testTag("paper_$mm"),
                )
            }
        }
        Text("Paper", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = config.paperType == PaperType.ROLL,
                onClick = { onChange(config.copy(paperType = PaperType.ROLL)) },
                label = { Text("Receipt roll") },
            )
            FilterChip(
                selected = config.paperType == PaperType.STICKER,
                // Stickers sit on 58 mm-wide backing.
                onClick = { onChange(config.copy(paperType = PaperType.STICKER, paperWidthMm = config.paperWidthMm ?: 58)) },
                label = { Text("Label stickers") },
                modifier = Modifier.testTag("paper_sticker"),
            )
        }
        if (config.paperWidthMm == null) {
            Text(
                "Not set: receipts print exactly as received. Choose the paper loaded in this printer so wider receipts shrink to fit instead of being cut off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    SendSpeedSlider(config, onChange)
    // Layout size comes from the dashboard (the job image is rendered at that size).
    Text("Receipt and label layout size are set in the dashboard.", style = MaterialTheme.typography.bodySmall)
}

/** Bluetooth send speed: slower for small portable printers that drop labels sent too fast. */
@Composable
private fun SendSpeedSlider(config: PrinterConfigEntity, onChange: (PrinterConfigEntity) -> Unit) {
    val steps = SendSpeed.STEPS
    val saved = steps.indexOfFirst { it >= config.sendSpeedKbps }.takeIf { it >= 0 } ?: steps.lastIndex
    // Follows the finger while dragging; saved when released.
    var index by remember(config.role, config.sendSpeedKbps) { mutableIntStateOf(saved) }
    Text("Send speed", style = MaterialTheme.typography.labelLarge)
    Text(SendSpeed.label(steps[index]), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = index.toFloat(),
        onValueChange = { index = it.toInt().coerceIn(0, steps.lastIndex) },
        onValueChangeFinished = { if (steps[index] != config.sendSpeedKbps) onChange(config.copy(sendSpeedKbps = steps[index])) },
        valueRange = 0f..steps.lastIndex.toFloat(),
        steps = steps.size - 2,
        modifier = Modifier.testTag("send_speed_${config.role.name.lowercase()}"),
    )
    Text(
        "Lower it if prints sometimes don't come out at all (common on small portable label printers). Higher prints faster.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Label stickers on a receipt printer with no gap sensor (see StickerLayout): the sticker size,
 * then a guided setup with the app's exact paper moves. The printer's own feed button moves the
 * paper an unknown amount, so lining up always uses these buttons.
 */
@Composable
private fun StickerSetup(config: PrinterConfigEntity, vm: AgentViewModel) {
    val update: (PrinterConfigEntity) -> Unit = vm::updatePrinterSettings
    Text("Sticker height", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (mm in StickerLayout.HEIGHTS_MM) {
            FilterChip(selected = config.stickerHeightMm == mm, onClick = { update(config.copy(stickerHeightMm = mm)) }, label = { Text("$mm") })
        }
    }
    Text("Gap between stickers", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (mm in StickerLayout.GAPS_MM) {
            FilterChip(selected = config.stickerGapMm == mm, onClick = { update(config.copy(stickerGapMm = mm)) }, label = { Text("$mm mm") })
        }
    }

    SectionCard(title = "Sticker setup") {
        Text("1. Line up", style = MaterialTheme.typography.labelLarge)
        Text(
            "Put the gap between two stickers exactly at the printer's tear edge. Use these buttons, not the printer's feed button: they move the paper by exact amounts.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.nudgeSticker(config.role) }) { Text("Move 1 mm") }
            OutlinedButton(onClick = { vm.feedNextSticker(config) }) { Text("Next sticker") }
        }

        Text("2. Position", style = MaterialTheme.typography.labelLarge)
        Text("Print a test sticker: its box should sit fully on one sticker.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.printStickerTest(config.role, 1) }, modifier = Modifier.testTag("sticker_test")) { Text("Print test sticker") }
        Text("Box moved down: ${"%.1f".format(config.stickerOffsetTenths / 10.0)} mm", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { update(config.copy(stickerOffsetTenths = (config.stickerOffsetTenths - 10).coerceAtLeast(0))) },
                enabled = config.stickerOffsetTenths > 0,
            ) { Text("Move box up") }
            OutlinedButton(
                onClick = { update(config.copy(stickerOffsetTenths = (config.stickerOffsetTenths + 10).coerceAtMost(StickerLayout.MAX_OFFSET_TENTHS))) },
            ) { Text("Move box down") }
        }

        Text("3. Check a run", style = MaterialTheme.typography.labelLarge)
        Text(
            "Print 5 test stickers. If the later boxes creep up or down the stickers, correct it here, then try again.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { vm.printStickerTest(config.role, 5) }) { Text("Print 5 test stickers") }
        Text("Feed per sticker: ${"%+.2f".format(config.stickerPitchAdjust / 100.0)} mm", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Creeping up: the printer feeds short, so feed a little more per sticker.
            OutlinedButton(onClick = {
                update(config.copy(stickerPitchAdjust = (config.stickerPitchAdjust + 10).coerceAtMost(StickerLayout.MAX_PITCH_ADJUST_HUNDREDTHS)))
            }) { Text("Boxes creep up") }
            OutlinedButton(onClick = {
                update(config.copy(stickerPitchAdjust = (config.stickerPitchAdjust - 10).coerceAtLeast(-StickerLayout.MAX_PITCH_ADJUST_HUNDREDTHS)))
            }) { Text("Boxes creep down") }
        }

        Text(
            "Every print ends with the next gap at the tear edge. If the roll is moved, line a gap up again (step 1). " +
                "For printing many labels every day, a label printer with a gap sensor is more reliable.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
