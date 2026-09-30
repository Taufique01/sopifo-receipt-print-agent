package com.sopifo.printagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.RegistrationCode
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.print.FilePrinterTransport
import com.sopifo.printagent.service.ServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Debug-only adb entry point for automated device tests:
 *   adb shell am broadcast -a com.sopifo.printagent.debug.COMMAND -p com.sopifo.printagent --es cmd <command> [...]
 * The result (JSON) is returned as the broadcast result data, which `am broadcast` prints.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as SopifoApp).container
        val cmd = intent.getStringExtra("cmd") ?: return
        val pending = goAsync()
        container.scope.launch(Dispatchers.IO) {
            val result = try {
                withTimeout(9_000) { run(context, container, cmd, intent) }
            } catch (e: Throwable) {
                AppLog.e("Debug", "Command failed", e, "cmd" to cmd)
                buildJsonObject { put("ok", false); put("error", "${e.javaClass.simpleName}: ${e.message}") }.toString()
            }
            pending.resultCode = 0
            pending.resultData = result
            pending.finish()
        }
    }

    private suspend fun run(context: Context, c: com.sopifo.printagent.AppContainer, cmd: String, intent: Intent): String {
        val prints = File(context.filesDir, "virtual_prints")
        when (cmd) {
            "register" -> {
                val code = RegistrationCode(intent.getStringExtra("token") ?: error("token required"), intent.getStringExtra("api"))
                c.device.register(code)
                c.startAgent("registered")
            }
            "virtual_printers" -> {
                c.db.printerConfigDao().upsert(PrinterConfigEntity(PrinterRole.RECEIPT, "Virtual receipt", FilePrinterTransport.VIRTUAL_MAC, PrinterProtocol.ESC_POS, 576))
                c.db.printerConfigDao().upsert(PrinterConfigEntity(PrinterRole.LABEL, "Virtual label", FilePrinterTransport.VIRTUAL_MAC, PrinterProtocol.TSPL, 400, 50, 30))
                c.printers.probeAll()
            }
            "wake" -> c.handleWake(intent.getStringExtra("job_id"), "debug_fcm")
            "sync" -> c.scheduler.enqueuePendingSync("debug", force = true)
            "heartbeat" -> c.scheduler.triggerHeartbeatNow()
            "start_service" -> ServiceController.start(context, "debug")
            "clear_prints" -> prints.listFiles()?.forEach { it.delete() }
            "unregister" -> c.unregister()
            "status" -> {}
            else -> error("unknown command $cmd")
        }
        val config = c.device.config()
        return buildJsonObject {
            put("ok", true)
            put("registered", c.device.hasSession())
            put("store", config?.storeName)
            put("session_valid", config?.sessionValid)
            put("last_sync", config?.lastSyncAt)
            put("service_running", ServiceController.running.value)
            put("cloud_online", c.cloudOnline.value)
            put("receipt", c.printers.status.value[PrinterRole.RECEIPT]?.wire)
            put("label", c.printers.status.value[PrinterRole.LABEL]?.wire)
            put("virtual_prints", prints.listFiles()?.size ?: 0)
            put("recent", c.db.recentJobDao().getAll().joinToString(",") { "${it.jobId}:${it.status}" })
        }.toString()
    }
}
