package com.sopifo.printagent

import android.content.Context
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.core.LogUploader
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
import com.sopifo.printagent.runs.ApiRunContent
import com.sopifo.printagent.runs.PrintRunner
import com.sopifo.printagent.runs.PrinterManagerPort
import com.sopifo.printagent.runs.RunReporter
import com.sopifo.printagent.security.AppLock
import com.sopifo.printagent.service.RunNotifications
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

                override fun onUnauthorized(code: String?) {
                    scope.launch {
                        device.onUnauthorized()
                        if (code == ApiClient.DEVICE_REMOVED) unregister(byServer = true)
                    }
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

    val logUploader: LogUploader by lazy { LogUploader(context, api, device, printers, systemInfo.appVersion) }

    val jobs: JobRepository by lazy { JobRepository(db.printedJobDao(), db.recentJobDao()) }

    /** Optional PIN / fingerprint lock for the pages with customer names. */
    val appLock = AppLock(context)

    val runNotifications: RunNotifications by lazy { RunNotifications(context).also { it.createChannels() } }

    /** Results of the app's own prints → server (history, printed marks). */
    val runReporter: RunReporter by lazy { RunReporter(db.printRunDao()) { api.reportPrintResults(it) } }

    /** Prints the seller starts in the app (Orders, Shipping, Quick print): straight to the printer. */
    val runner: PrintRunner by lazy {
        PrintRunner(
            dao = db.printRunDao(),
            printers = PrinterManagerPort(printers),
            content = ApiRunContent(api, printers),
            scope = scope,
            notifier = runNotifications,
            onResults = ::reportRunsSoon,
            // The foreground service keeps the app alive while the phone is locked.
            keepAlive = { ServiceController.start(context, "print_run") },
        )
    }

    /** Sends unsent print results now; if offline, a worker keeps trying. */
    fun reportRunsSoon() {
        scope.launch(Dispatchers.IO) {
            if (device.hasSession() && !runReporter.reportPending()) scheduler.enqueueRunReports()
        }
    }

    val processor: JobProcessor by lazy {
        JobProcessor(
            api, jobs, printers, scheduler, serverClock,
            onBatchesPending = { ids -> ids.forEach { scheduler.enqueueBatch(it, "pending_sync") } },
            onBackendSync = { device.markSynced() },
        )
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
                runner.recoverInterrupted()
                runner.prune()
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

    /** FCM wake-up for a bulk print: one worker fetches and prints the whole batch. */
    fun handleBatchWake(batchId: String, source: String) {
        if (UrlPolicy.isValidJobId(batchId)) {
            AppLog.i("Wake", "Batch wake-up", "batch" to batchId, "source" to source)
            scheduler.enqueueBatch(batchId, source)
        } else {
            AppLog.w("Wake", "Batch wake-up with invalid id; syncing pending", null, "source" to source)
            scheduler.enqueuePendingSync("${source}_wake", force = true)
        }
    }

    fun onNetworkLost() {
        _cloudOnline.value = false
    }

    private val sessionLock = Mutex()

    /**
     * Disconnects this phone and stops the agent; the app returns to its Connect screen.
     * [byServer]: the server already removed it (401 DEVICE_REMOVED), so it isn't told again and
     * the Connect screen says which shop it left. Returns false when the server couldn't be told
     * (offline): the phone then still shows on the dashboard until the seller removes it there.
     */
    suspend fun unregister(byServer: Boolean = false): Boolean = sessionLock.withLock {
        // A request racing the disconnect can report DEVICE_REMOVED after the session is gone.
        if (byServer && !device.hasSession()) return@withLock true
        val storeName = device.config()?.storeName
        val told = byServer || !device.hasSession() || device.tellServerUnregister()
        if (byServer) {
            AppLog.w("AppContainer", "Server removed this device; back to Connect", null)
            device.setDisconnectedFrom(storeName?.ifBlank { null } ?: "your shop")
        }
        appLock.clear()
        scheduler.cancelAll()
        ServiceController.stop(context)
        device.unregister()
        told
    }
}
