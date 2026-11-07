package com.sopifo.printagent.ui.print

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.ui.AgentViewModel
import com.sopifo.printagent.ui.screens.printerStateGood
import com.sopifo.printagent.ui.shell.AppSettings
import com.sopifo.printagent.ui.shell.HelpButton
import com.sopifo.printagent.ui.shell.strings
import com.sopifo.printagent.ui.theme.AppIcons
import com.sopifo.printagent.ui.theme.StatusBad
import com.sopifo.printagent.ui.theme.StatusGood
import com.sopifo.printagent.ui.theme.StatusNeutral

enum class PrintSheetAction { ADD_PRINTER, PRINTERS, QUICK_PDF, QUICK_PHOTO, QUICK_TEXT, RECENT }

/** 🖨 in the top bar: printers, Quick print, recent prints, print-station mode, help. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSheet(vm: AgentViewModel, onDismiss: () -> Unit, onAction: (PrintSheetAction) -> Unit) {
    val t = strings()
    val context = LocalContext.current
    val settings = AppSettings.get(context)
    val station by settings.station.collectAsStateWithLifecycle()
    val configs by vm.printerConfigs.collectAsStateWithLifecycle()
    val status by vm.printerStatus.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().testTag("print_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.printers, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Settings, contentDescription = t.settings, modifier = Modifier.clickable { onAction(PrintSheetAction.PRINTERS) }.padding(8.dp))
            }
            if (configs.isEmpty()) {
                Text(t.noPrinter, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            configs.sortedBy { it.role.ordinal }.forEach { cfg ->
                val good = printerStateGood(status[cfg.role])
                Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (cfg.role == PrinterRole.LABEL) AppIcons.Tag else AppIcons.Receipt, contentDescription = null, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cfg.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (cfg.role == PrinterRole.LABEL) t.labels else t.receipts, style = MaterialTheme.typography.bodySmall)
                        }
                        Surface(Modifier.size(12.dp), shape = CircleShape, color = when (good) { true -> StatusGood; false -> StatusBad; null -> StatusNeutral }) {}
                        Text(if (good == true) t.ready else t.notReady, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(Icons.Filled.Add, t.addPrinter, Modifier.weight(1f).testTag("sheet_add_printer")) { onAction(PrintSheetAction.ADD_PRINTER) }
                Tile(AppIcons.Print, t.testPrint, Modifier.weight(1f).testTag("sheet_test")) {
                    configs.firstOrNull()?.let { vm.testPrint(it.role) } ?: onAction(PrintSheetAction.ADD_PRINTER)
                }
            }
            Text(t.quickPrint, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(AppIcons.Pdf, t.pdf, Modifier.weight(1f).testTag("sheet_pdf")) { onAction(PrintSheetAction.QUICK_PDF) }
                Tile(AppIcons.Photo, t.photo, Modifier.weight(1f).testTag("sheet_photo")) { onAction(PrintSheetAction.QUICK_PHOTO) }
                Tile(AppIcons.Bold, t.text, Modifier.weight(1f).testTag("sheet_text")) { onAction(PrintSheetAction.QUICK_TEXT) }
            }
            Tile(Icons.AutoMirrored.Filled.List, t.recent, Modifier.fillMaxWidth().testTag("sheet_recent")) { onAction(PrintSheetAction.RECENT) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(AppIcons.Station, contentDescription = null, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.stationMode, style = MaterialTheme.typography.titleSmall)
                    Text(t.stationBody, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = station, onCheckedChange = settings::setStation, modifier = Modifier.testTag("station_switch"))
            }
            HelpButton(page = "Printers", modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
fun Tile(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(vertical = 16.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
