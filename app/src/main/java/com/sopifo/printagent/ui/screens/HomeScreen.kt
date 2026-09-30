package com.sopifo.printagent.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.ui.AgentViewModel

// BatteryLife: an unattended print agent must not be deferred by Doze; see README "Battery exemption".
@SuppressLint("BatteryLife")
@Composable
fun HomeScreen(vm: AgentViewModel, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val config by vm.deviceConfig.collectAsStateWithLifecycle()
    val online by vm.cloudOnline.collectAsStateWithLifecycle()
    val printers by vm.printerStatus.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val ignoringOpt by vm.ignoringBatteryOptimizations.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(config?.storeName?.ifBlank { null } ?: "Sopifo Store", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("store_name"))

        if (config?.sessionValid == false) {
            SectionCard {
                Text("The server rejected this device's session. Re-register it from Diagnostics.", color = MaterialTheme.colorScheme.error)
            }
        }

        SectionCard(title = "Status") {
            StatusRow("Cloud", if (online == true) "Online" else "No Server Connection", good = online == true, tag = "cloud_status")
            StatusRow("Receipt printer", printerStateText(printers[PrinterRole.RECEIPT]), printerStateGood(printers[PrinterRole.RECEIPT]), tag = "receipt_status")
            StatusRow("Label printer", printerStateText(printers[PrinterRole.LABEL]), printerStateGood(printers[PrinterRole.LABEL]), tag = "label_status")
            StatusRow("Battery", battery?.let { "$it%" } ?: "Unknown", tag = "battery")
            StatusRow("Last sync", formatAgo(config?.lastSyncAt), tag = "last_sync")
        }

        SectionCard(title = "Test") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.testPrint(PrinterRole.RECEIPT) }, modifier = Modifier.weight(1f).testTag("test_receipt")) { Text("Test Receipt Print") }
                Button(onClick = { vm.testPrint(PrinterRole.LABEL) }, modifier = Modifier.weight(1f).testTag("test_label")) { Text("Test Label Print") }
            }
            OutlinedButton(onClick = vm::reconnectPrinters, modifier = Modifier.fillMaxWidth()) { Text("Reconnect printers") }
        }

        if (!ignoringOpt) {
            SectionCard(title = "Unattended operation") {
                Text("Allow the agent to run without battery restrictions so print jobs are never delayed.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
                    }
                }) { Text("Allow background running") }
                OutlinedButton(onClick = onRequestPermissions) { Text("Grant permissions") }
            }
        }
    }
}
