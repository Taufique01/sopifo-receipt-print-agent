package com.sopifo.printagent.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.RegistrationCode
import com.sopifo.printagent.data.RegistrationException
import com.sopifo.printagent.data.api.PrintJobDto
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.print.PrintException
import com.sopifo.printagent.service.ServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BondedDevice(val name: String, val address: String)

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

    private val _message = MutableStateFlow<String?>(null)
    /** One-shot user feedback (snackbar). */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _pending = MutableStateFlow(PendingJobsState())
    val pending: StateFlow<PendingJobsState> = _pending.asStateFlow()

    private val _bonded = MutableStateFlow<List<BondedDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BondedDevice>> = _bonded.asStateFlow()

    val appVersion: String get() = c.systemInfo.appVersion

    init {
        viewModelScope.launch(Dispatchers.IO) { _registered.value = c.device.hasSession() }
        refreshDeviceFacts()
    }

    fun refreshDeviceFacts() {
        _battery.value = c.systemInfo.batteryPercent()
        _ignoringBatteryOpt.value = c.systemInfo.isIgnoringBatteryOptimizations()
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

    fun loadPending() {
        _pending.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
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

    fun savePrinter(role: PrinterRole, device: BondedDevice, protocol: PrinterProtocol, widthDots: Int, labelWidthMm: Int, labelHeightMm: Int) {
        viewModelScope.launch {
            c.db.printerConfigDao().upsert(
                PrinterConfigEntity(
                    role = role,
                    name = device.name,
                    macAddress = device.address,
                    protocol = protocol,
                    widthDots = widthDots,
                    labelWidthMm = labelWidthMm,
                    labelHeightMm = labelHeightMm,
                ),
            )
            AppLog.i(TAG, "Printer saved", "role" to role, "name" to device.name)
            _message.value = "${role.label().replaceFirstChar { it.uppercase() }} printer saved"
            c.printers.probe(role)
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

    fun unregister() {
        viewModelScope.launch(Dispatchers.IO) {
            c.unregister()
            _registered.value = false
        }
    }

    private companion object {
        const val TAG = "UI"
    }
}

fun PrinterRole.label() = when (this) {
    PrinterRole.RECEIPT -> "receipt"
    PrinterRole.LABEL -> "label"
}
