package com.sopifo.printagent.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.core.LogShare
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.fcm.FcmState
import com.sopifo.printagent.ui.AgentViewModel
import kotlinx.coroutines.launch

// BatteryLife: an unattended print agent must not be deferred by Doze; see README "Battery exemption".
@SuppressLint("BatteryLife")
@Composable
fun DiagnosticsScreen(vm: AgentViewModel) {
    val context = LocalContext.current
    val config by vm.deviceConfig.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val fcm by vm.fcmStatus.collectAsStateWithLifecycle()
    val service by vm.serviceRunning.collectAsStateWithLifecycle()
    val printers by vm.printerStatus.collectAsStateWithLifecycle()
    val online by vm.cloudOnline.collectAsStateWithLifecycle()
    val ignoringOpt by vm.ignoringBatteryOptimizations.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    var sendingLogs by remember { mutableStateOf(false) }
    var sharingLogs by remember { mutableStateOf(false) }
    /** Result of the last "Send logs to support": the reference code, or why it failed. */
    var logResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Diagnostics")
        SectionCard {
            StatusRow("App version", vm.appVersion, tag = "diag_version")
            StatusRow("Store name", config?.storeName ?: "—")
            StatusRow("Device name", config?.deviceName ?: "—")
            StatusRow("Device ID", config?.deviceId ?: "—")
            StatusRow("Battery", battery?.let { "$it%" } ?: "Unknown")
            StatusRow("Last sync", formatTime(config?.lastSyncAt))
            StatusRow("Cloud", if (online == true) "Online" else "No Server Connection", online == true)
            StatusRow(
                "FCM status",
                when (fcm.state) {
                    FcmState.REGISTERED -> "Registered"
                    FcmState.TOKEN_READY -> "Token ready (not yet sent)"
                    FcmState.NO_TOKEN -> "No token yet"
                    FcmState.NOT_CONFIGURED -> "Not configured in this build"
                    FcmState.ERROR -> "Error: ${fcm.detail ?: "unknown"}"
                },
                fcm.state == FcmState.REGISTERED,
                tag = "diag_fcm",
            )
            StatusRow("Last FCM wake-up", formatTime(fcm.lastMessageAt))
            StatusRow("Foreground service", if (service) "Running" else "Stopped", service, tag = "diag_service")
            StatusRow("Receipt printer", printerStateText(printers[PrinterRole.RECEIPT]), printerStateGood(printers[PrinterRole.RECEIPT]))
            StatusRow("Label printer", printerStateText(printers[PrinterRole.LABEL]), printerStateGood(printers[PrinterRole.LABEL]))
            StatusRow("Battery optimisation", if (ignoringOpt) "Unrestricted" else "Restricted", ignoringOpt)
            StatusRow("Session", if (config?.sessionValid == false) "Rejected by server" else "Valid", config?.sessionValid != false)
        }

        if (!ignoringOpt) {
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())) }
            }) { Text("Allow unrestricted background running") }
        }
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }
        }) { Text("App settings (auto-launch, battery)") }
        // Uploads straight to Sopifo support (no other app involved); the seller gets a code to quote.
        OutlinedButton(
            enabled = !sendingLogs,
            modifier = Modifier.testTag("diag_send_logs"),
            onClick = {
                sendingLogs = true
                logResult = null
                scope.launch {
                    logResult = try {
                        "Sent to support. Reference: #${vm.uploadLogs()}"
                    } catch (e: Exception) {
                        AppLog.e("Diagnostics", "Sending logs failed", e)
                        "Could not send logs (${e.message ?: "no connection"}). Check the internet and try again, or share the file below."
                    } finally {
                        sendingLogs = false
                    }
                }
            },
        ) { Text(if (sendingLogs) "Sending logs…" else "Send logs to support") }
        logResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("diag_logs_result")) }
        TextButton(
            enabled = !sharingLogs,
            onClick = {
                sharingLogs = true
                scope.launch {
                    try {
                        LogShare.send(context, vm.logBundle())
                    } catch (e: Exception) {
                        AppLog.e("Diagnostics", "Sharing logs failed", e)
                    } finally {
                        sharingLogs = false
                    }
                }
            },
        ) { Text("Share log file (WhatsApp…)") }
        OutlinedButton(onClick = { confirmReset = true }) { Text("Re-register device", color = MaterialTheme.colorScheme.error) }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Re-register this device?") },
            text = { Text("The stored device session is removed from this phone. Printing stops until it is registered again. Jobs on the server are not affected.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.unregister() }) { Text("Remove session") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Keep") } },
        )
    }
}
