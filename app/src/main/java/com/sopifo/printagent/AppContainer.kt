package com.sopifo.printagent

import android.content.Context
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.DeviceRepository
import com.sopifo.printagent.data.SystemInfo
import com.sopifo.printagent.data.api.ApiClient
import com.sopifo.printagent.data.api.ServerClock
import com.sopifo.printagent.data.api.UrlPolicy
import com.sopifo.printagent.data.db.AppDatabase
import com.sopifo.printagent.data.secure.KeystoreTokenStore
import com.sopifo.printagent.fcm.FcmManager
import com.sopifo.printagent.jobs.JobProcessor
import com.sopifo.printagent.jobs.JobRepository
import com.sopifo.printagent.print.BluetoothPrinterTransport
import com.sopifo.printagent.print.FilePrinterTransport
import com.sopifo.printagent.print.PrintDataBuilder
import com.sopifo.printagent.print.PrinterManager
import com.sopifo.printagent.print.RoutingPrinterTransport
import com.sopifo.printagent.service.ServiceController
import com.sopifo.printagent.work.WorkScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Manual dependency graph; one instance per process, owned by [SopifoApp]. */
class AppContainer(private val context: Context) {

    /** App-wide scope. SupervisorJob + handler: one failed task never cancels the others or crashes the app. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
        AppLog.e("AppScope", "Uncaught coroutine error", t)
    })

    val db: AppDatabase by lazy { AppDatabase.build(context) }
    val systemInfo = SystemInfo(context)
    val urlPolicy = UrlPolicy(allowLocalhostHttp = BuildConfig.ALLOW_LOCALHOST_HTTP)
    val serverClock = ServerClock()
    val fcm = FcmManager(context)
    val scheduler = WorkScheduler(context)
    private val tokenStore = KeystoreTokenStore(context)

    private val _cloudOnline = MutableStateFlow<Boolean?>(null)
    /** null = unknown yet, true = backend answered last request, false = last request failed at network level. */
    val cloudOnline: StateFlow<Boolean?> = _cloudOnline.asStateFlow()

    val device: DeviceRepository by lazy {
        DeviceRepository(context, db.deviceConfigDao(), tokenStore, urlPolicy, systemInfo, fcm) { api }
    }

    val api: ApiClient by lazy {
        ApiClient(
            baseUrlProvider = { device.baseUrl },
            tokenProvider = { device.jwt() },
            urlPolicy = urlPolicy,
            serverClock = serverClock,
            listener = object : ApiClient.Listener {
                override fun onReachability(reachable: Boolean) {
                    _cloudOnline.value = reachable
                }

                override fun onUnauthorized() {
                    scope.launch { device.onUnauthorized() }
                }
            },
        )
    }

    val bluetooth = BluetoothPrinterTransport(context)

    val printers: PrinterManager by lazy {
        val virtual = if (BuildConfig.DEBUG) FilePrinterTransport(File(context.filesDir, "virtual_prints")) else null
        PrinterManager(
            printerDao = db.printerConfigDao(),
            transport = RoutingPrinterTransport(bluetooth, virtual),
            builder = PrintDataBuilder(),
            environment = object : PrinterManager.Environment {
                override fun isBluetoothOn() = bluetooth.isBluetoothOn()
                override fun hasPermission() = bluetooth.hasConnectPermission()
            },
        )
    }

    val jobs: JobRepository by lazy { JobRepository(db.printedJobDao(), db.recentJobDao()) }

    val processor: JobProcessor by lazy {
        JobProcessor(api, jobs, printers, scheduler, serverClock, onBackendSync = { device.markSynced() })
    }

    /**
     * Brings the agent to its steady state. Called on app start, boot, app update and after
     * registration. Idempotent: every piece uses unique work / "already running" checks.
     */
    fun startAgent(reason: String) {
        scope.launch(Dispatchers.IO) {
            try {
                if (!device.hasSession()) return@launch
                scheduler.ensureWatchdog()
                scheduler.ensureHeartbeat()
                scheduler.enqueuePendingSync(reason)
                if (device.config()?.fcmTokenSynced != true) scheduler.enqueueFcmTokenSync(null)
                ServiceController.start(context, reason)
                printers.refreshPassive()
            } catch (e: Exception) {
                AppLog.e("AppContainer", "startAgent failed", e, "reason" to reason)
            }
        }
    }

    /** FCM (or debug) wake-up. A missing/invalid job id still triggers a pending sync. */
    fun handleWake(jobId: String?, source: String) {
        if (UrlPolicy.isValidJobId(jobId)) {
            AppLog.i("Wake", "Job wake-up", "job" to jobId, "source" to source)
            scheduler.enqueueJob(jobId!!, source)
        } else {
            AppLog.w("Wake", "Wake-up without valid job id; syncing pending", null, "source" to source)
            scheduler.enqueuePendingSync("${source}_wake", force = true)
        }
    }

    fun onNetworkLost() {
        _cloudOnline.value = false
    }

    suspend fun unregister() {
        scheduler.cancelAll()
        ServiceController.stop(context)
        device.unregister()
    }
}
