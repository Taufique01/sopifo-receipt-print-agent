package com.sopifo.printagent.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.ConnectCode
import com.sopifo.printagent.data.ConnectPoll
import com.sopifo.printagent.data.RegistrationCode
import com.sopifo.printagent.data.RegistrationException
import com.sopifo.printagent.data.api.PrintJobDto
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.print.PrintDataBuilder
import com.sopifo.printagent.print.PrintException
import com.sopifo.printagent.print.SendSpeed
import com.sopifo.printagent.service.ServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BondedDevice(val name: String, val address: String)

/** The Connect screen: the code it shows, and what to tell the seller. */
data class ConnectState(
    val code: ConnectCode? = null,
    /** Getting a code from the server. */
    val loading: Boolean = false,
    /** Why there is no code or approval check right now ("No internet — retrying…"). */
    val error: String? = null,
    /** Why the phone is on this screen again: removed by the server, or disconnected offline. */
    val notice: String? = null,
)

data class PendingJobsState(
    val loading: Boolean = false,
    val jobs: List<PrintJobDto> = emptyList(),
    val error: String? = null,
    val cancelling: Set<String> = emptySet(),
)

class AgentViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as SopifoApp).container
    private val started = SharingStarted.WhileSubscribed(5_000)

    private val _registered = MutableStateFlow<Boolean?>(null)
    val registered: StateFlow<Boolean?> = _registered.asStateFlow()

    val deviceConfig = c.device.observeConfig().stateIn(viewModelScope, started, null)
    val printerConfigs = c.db.printerConfigDao().observeAll().stateIn(viewModelScope, started, emptyList())
    val printerStatus = c.printers.status
    val cloudOnline = c.cloudOnline
    val recentJobs = c.jobs.observeRecent().stateIn(viewModelScope, started, emptyList())
    val fcmStatus = c.fcm.status
    val serviceRunning = ServiceController.running

    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery.asStateFlow()
    private val _ignoringBatteryOpt = MutableStateFlow(true)
    val ignoringBatteryOptimizations: StateFlow<Boolean> = _ignoringBatteryOpt.asStateFlow()

    private val _registering = MutableStateFlow(false)
    val registering: StateFlow<Boolean> = _registering.asStateFlow()
    private val _registrationError = MutableStateFlow<String?>(null)
    val registrationError: StateFlow<String?> = _registrationError.asStateFlow()

    private val _connect = MutableStateFlow(ConnectState())
    val connect: StateFlow<ConnectState> = _connect.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** One-shot user feedback (snackbar). */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _pending = MutableStateFlow(PendingJobsState())
    val pending: StateFlow<PendingJobsState> = _pending.asStateFlow()

    private val _bonded = MutableStateFlow<List<BondedDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BondedDevice>> = _bonded.asStateFlow()

    val appVersion: String get() = c.systemInfo.appVersion

    /** "Send logs to support": uploads now; returns the reference code. Throws when offline. */
    suspend fun uploadLogs(): String = c.logUploader.upload("button")

    /** The log bundle file, for sharing it by hand. */
    suspend fun logBundle() = c.logUploader.buildBundle()

    init {
        viewModelScope.launch(Dispatchers.IO) { _registered.value = c.device.hasSession() }
        // The session can end without this screen asking: the server removed the phone.
        viewModelScope.launch {
            c.device.observeConfig().collect { config ->
                if (config == null && _registered.value == true && !c.device.hasSession()) onSignedOut()
            }
        }
        refreshDeviceFacts()
    }

    private fun onSignedOut() {
        _registered.value = false
        c.device.disconnectedFrom()?.let { store ->
            _connect.update { it.copy(notice = "This phone was disconnected from $store.") }
        }
    }

    /**
     * Runs while the Connect screen is in front: keeps a code and checks whether it was approved,
     * every 2 s for the first 2 minutes, then every 10 s. Coming back to the app (after approving
     * in the browser) restarts it, so the first check is immediate.
     */
    suspend fun runConnect(): Unit = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        if (_connect.value.notice == null) {
            c.device.disconnectedFrom()?.let { store -> _connect.update { it.copy(notice = "This phone was disconnected from $store.") } }
        }
        while (true) {
            val code = _connect.value.code ?: c.device.savedConnectCode()?.also { saved -> _connect.update { it.copy(code = saved) } } ?: fetchConnectCode()
            if (code != null) {
                try {
                    when (c.device.claimConnect(code)) {
                        ConnectPoll.CONNECTED -> {
                            _connect.value = ConnectState()
                            _registered.value = true
                            c.startAgent("connected")
                            return@withContext
                        }
                        // Replaced or expired: the next round gets a new code.
                        ConnectPoll.EXPIRED -> _connect.update { if (it.code?.requestId == code.requestId) it.copy(code = null) else it }
                        ConnectPoll.WAITING -> _connect.update { it.copy(error = null) }
                    }
                } catch (e: RegistrationException) {
                    _connect.update { it.copy(error = e.message) }
                }
            }
            val waited = SystemClock.elapsedRealtime() - started
            delay(if (waited < CONNECT_FAST_POLL_MS) 2_000 else 10_000)
        }
    }

    /** "New code" on the Connect screen. */
    fun newConnectCode() {
        if (_connect.value.loading) return
        viewModelScope.launch { fetchConnectCode() }
    }

    private suspend fun fetchConnectCode(): ConnectCode? {
        _connect.update { it.copy(loading = true) }
        return try {
            c.device.newConnectCode().also { code -> _connect.update { it.copy(code = code, error = null) } }
        } catch (e: RegistrationException) {
            _connect.update { it.copy(error = e.message) }
            null
        } catch (e: Exception) {
            AppLog.e(TAG, "Connect code failed", e)
            _connect.update { it.copy(error = "Could not get a code: ${e.message}") }
            null
        } finally {
            _connect.update { it.copy(loading = false) }
        }
    }

    fun refreshDeviceFacts() {
        _battery.value = c.systemInfo.batteryPercent()
        _ignoringBatteryOpt.value = c.systemInfo.isIgnoringBatteryOptimizations()
        c.printers.resetProbeBackoff()
        viewModelScope.launch { runCatching { c.printers.refreshPassive() } }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun register(raw: String) {
        if (_registering.value) return
        val code = RegistrationCode.parse(raw)
        if (code == null) {
            _registrationError.value = "That is not a valid registration code"
            return
        }
        _registering.value = true
        _registrationError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                c.device.register(code)
                _registered.value = true
                c.startAgent("registered")
            } catch (e: RegistrationException) {
                _registrationError.value = e.message
            } catch (e: Exception) {
                AppLog.e(TAG, "Registration crashed", e)
                _registrationError.value = "Registration failed: ${e.message}"
            } finally {
                _registering.value = false
            }
        }
    }

    fun onPermissionsChanged() {
        c.startAgent("permissions_changed")
        refreshDeviceFacts()
    }

    fun testPrint(role: PrinterRole) {
        viewModelScope.launch {
            _message.value = "Sending test print…"
            _message.value = try {
                c.printers.testPrint(role)
                "Test print sent to ${role.label()} printer"
            } catch (e: PrintException) {
                "Test print failed: ${e.message}"
            } catch (e: Exception) {
                AppLog.e(TAG, "Test print error", e)
                "Test print failed: ${e.message}"
            }
        }
    }

    /**
     * Loads the backend's pending jobs. With [printFirst] (the Refresh button) it first runs the
     * same pending-job recovery as the background triggers, so a listed job gets printed instead
     * of only shown. The processor's ledger and in-flight guard keep this from double printing.
     */
    fun loadPending(printFirst: Boolean = false) {
        _pending.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                if (printFirst) {
                    try {
                        val outcomes = c.processor.syncPending("manual_refresh")
                        if (outcomes.isNotEmpty()) {
                            _message.value = outcomes.entries.joinToString { "${it.value} ${it.key.name.lowercase()}" }
                        }
                    } catch (e: Exception) {
                        AppLog.w(TAG, "Manual pending sync failed", e)
                    }
                }
                val result = c.api.getPendingJobs()
                _pending.update { it.copy(loading = false, jobs = result.jobs) }
            } catch (e: Exception) {
                _pending.update { it.copy(loading = false, error = "Could not load pending jobs: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Cancels one job on the backend. There is intentionally no "cancel all" / "clear queue". */
    fun cancelJob(jobId: String) {
        _pending.update { it.copy(cancelling = it.cancelling + jobId) }
        viewModelScope.launch {
            try {
                c.api.cancelJob(jobId)
                _message.value = "Job $jobId cancelled"
                _pending.update { s -> s.copy(jobs = s.jobs.filterNot { it.id == jobId }) }
            } catch (e: Exception) {
                _message.value = "Cancel failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _pending.update { it.copy(cancelling = it.cancelling - jobId) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun loadBondedDevices() {
        if (!c.bluetooth.hasConnectPermission()) {
            _message.value = "Grant the Nearby devices (Bluetooth) permission first"
            return
        }
        _bonded.value = try {
            getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { BondedDevice(it.name ?: it.address, it.address) }
                .sortedBy { it.name.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
        if (_bonded.value.isEmpty()) _message.value = "No paired Bluetooth devices. Pair the printer in Android Bluetooth settings first."
    }

    fun savePrinter(role: PrinterRole, device: BondedDevice, protocol: PrinterProtocol) {
        viewModelScope.launch {
            val previous = c.db.printerConfigDao().get(role)
            c.db.printerConfigDao().upsert(
                PrinterConfigEntity(
                    role = role,
                    name = device.name,
                    macAddress = device.address,
                    protocol = protocol,
                    // Unused: the size comes from the dashboard-rendered image.
                    widthDots = PrintDataBuilder.MAX_ESC_POS_WIDTH,
                    // Changing to another printer keeps the chosen paper width and label gap.
                    paperWidthMm = previous?.paperWidthMm,
                    labelGapMm = previous?.labelGapMm ?: 2,
                    labelBlackMark = previous?.labelBlackMark ?: false,
                    sendSpeedKbps = SendSpeed.FAST_KBPS,
                ),
            )
            AppLog.i(TAG, "Printer saved", "role" to role, "name" to device.name)
            _message.value = "${role.label().replaceFirstChar { it.uppercase() }} printer saved"
            c.printers.probe(role)
        }
    }

    /** Label printer measures its own gap / black mark (TSPL GAPDETECT / BLINEDETECT). */
    fun calibrateLabels(role: PrinterRole) {
        viewModelScope.launch {
            _message.value = "Calibrating… the printer feeds a few labels"
            _message.value = try {
                c.printers.calibrate(role)
                "Calibration sent. Labels should now stop at the gap."
            } catch (e: Exception) {
                AppLog.e(TAG, "Calibrate error", e)
                "Calibration failed: ${e.message}"
            }
        }
    }

    fun updatePrinterSettings(config: PrinterConfigEntity) {
        viewModelScope.launch { c.db.printerConfigDao().upsert(config.copy(updatedAt = System.currentTimeMillis())) }
    }

    fun removePrinter(role: PrinterRole) {
        viewModelScope.launch {
            c.db.printerConfigDao().delete(role)
            c.printers.refreshPassive()
        }
    }

    fun reconnectPrinters() {
        viewModelScope.launch {
            _message.value = "Checking printers…"
            c.printers.probeAll()
            _message.value = null
        }
    }

    /** More → Disconnect this phone (and re-pairing after a forgotten app lock PIN). */
    fun unregister() {
        viewModelScope.launch(Dispatchers.IO) {
            val told = c.unregister()
            _connect.value = ConnectState(
                notice = if (told) null else "Also remove this phone from your Sopifo dashboard (Cloud Print → Devices).",
            )
            _registered.value = false
        }
    }

    private companion object {
        const val TAG = "UI"
        const val CONNECT_FAST_POLL_MS = 2 * 60_000L
    }
}

fun PrinterRole.label() = when (this) {
    PrinterRole.RECEIPT -> "receipt"
    PrinterRole.LABEL -> "label"
}
