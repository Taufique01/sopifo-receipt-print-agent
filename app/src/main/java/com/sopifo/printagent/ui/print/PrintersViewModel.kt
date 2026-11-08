package com.sopifo.printagent.ui.print

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.bt.BluetoothFinder
import com.sopifo.printagent.bt.FoundDevice
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.print.PrintDataBuilder
import com.sopifo.printagent.print.SendSpeed
import com.sopifo.printagent.ui.shell.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The "Select printer" pop-up for one section, and the "Did it print?" question after it. */
data class PickerState(
    val role: PrinterRole? = null,
    val scanning: Boolean = false,
    val devices: List<FoundDevice> = emptyList(),
    val showAll: Boolean = false,
    val pairingAddress: String? = null,
    val pairFailed: Boolean = false,
    /** A test page was just sent to this section's new printer. */
    val asking: PrinterRole? = null,
    val busy: PrinterRole? = null,
)

/**
 * Printers screen (app 2.0.1): two sections, Label printer and Receipt printer. "Select printer"
 * searches nearby, pairs inside the app, saves the printer for that section and prints a test.
 * Labels use TSPL and receipts ESC/POS; the language never shows (⋯ Advanced only).
 */
class PrintersViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as SopifoApp).container
    val finder = BluetoothFinder(app)
    val configs: StateFlow<List<PrinterConfigEntity>> =
        c.db.printerConfigDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val status = c.printers.status
    private val _picker = MutableStateFlow(PickerState())
    val picker: StateFlow<PickerState> = _picker.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var scanJob: Job? = null

    fun consumeMessage() { _message.value = null }

    fun openPicker(role: PrinterRole) {
        _picker.value = PickerState(role = role)
        scan()
    }

    fun closePicker() {
        scanJob?.cancel()
        _picker.update { it.copy(role = null, scanning = false, pairingAddress = null) }
    }

    fun scan() {
        if (!finder.hasPermissions() || !finder.isEnabled) return
        scanJob?.cancel()
        _picker.update { it.copy(scanning = true, pairFailed = false) }
        scanJob = viewModelScope.launch {
            try {
                finder.scan().collect { list -> _picker.update { it.copy(devices = list) } }
            } finally {
                _picker.update { it.copy(scanning = false) }
            }
        }
    }

    fun toggleShowAll() = _picker.update { it.copy(showAll = !it.showAll) }

    /** Pairs if needed, saves it for the open section, prints a test page, then asks. */
    fun choose(device: FoundDevice) {
        val role = _picker.value.role ?: return
        if (_picker.value.pairingAddress != null) return
        scanJob?.cancel()
        _picker.update { it.copy(pairingAddress = device.address, pairFailed = false) }
        viewModelScope.launch {
            val ok = device.paired || finder.pair(device.address)
            if (!ok) {
                _picker.update { it.copy(pairingAddress = null, pairFailed = true) }
                return@launch
            }
            save(role, device)
            _picker.update { it.copy(role = null, pairingAddress = null, busy = role) }
            runCatching { c.printers.testPrint(role) }.onFailure { _message.value = "✗ ${it.message}" }
            _picker.update { it.copy(busy = null, asking = role) }
        }
    }

    fun answer(printed: Boolean) {
        val role = _picker.value.asking
        _picker.update { it.copy(asking = null) }
        if (printed) {
            AppSettings.get(getApplication()).setTestPrinted()
        } else if (role != null) {
            // Open the pop-up again: maybe the wrong device, or the printer was off.
            openPicker(role)
        }
    }

    fun test(role: PrinterRole) {
        _picker.update { it.copy(busy = role) }
        viewModelScope.launch {
            runCatching { c.printers.testPrint(role) }
                .onSuccess { _message.value = "✓" }
                .onFailure { _message.value = "✗ ${it.message}" }
            _picker.update { it.copy(busy = null) }
        }
    }

    fun calibrate() {
        viewModelScope.launch {
            runCatching { c.printers.calibrate(PrinterRole.LABEL) }.onFailure { _message.value = "✗ ${it.message}" }
        }
    }

    fun setGap(mm: Int) = update(PrinterRole.LABEL) { it.copy(labelGapMm = mm, labelBlackMark = false) }

    fun setPaper(mm: Int) = update(PrinterRole.RECEIPT) { it.copy(paperWidthMm = mm) }

    fun remove(role: PrinterRole) {
        viewModelScope.launch {
            c.db.printerConfigDao().delete(role)
            c.printers.refreshPassive()
        }
    }

    private fun update(role: PrinterRole, change: (PrinterConfigEntity) -> PrinterConfigEntity) {
        viewModelScope.launch {
            val current = c.db.printerConfigDao().get(role) ?: return@launch
            c.db.printerConfigDao().upsert(change(current).copy(updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun save(role: PrinterRole, device: FoundDevice) {
        val previous = c.db.printerConfigDao().get(role)
        c.db.printerConfigDao().upsert(
            PrinterConfigEntity(
                role = role,
                name = device.name,
                macAddress = device.address,
                protocol = if (role == PrinterRole.LABEL) PrinterProtocol.TSPL else PrinterProtocol.ESC_POS,
                widthDots = PrintDataBuilder.MAX_ESC_POS_WIDTH,
                paperWidthMm = if (role == PrinterRole.RECEIPT) previous?.paperWidthMm ?: BluetoothFinder.guessPaperMm(device.name) else previous?.paperWidthMm,
                labelGapMm = previous?.labelGapMm ?: 2,
                labelBlackMark = previous?.labelBlackMark ?: false,
                sendSpeedKbps = previous?.sendSpeedKbps ?: SendSpeed.FAST_KBPS,
            ),
        )
        AppLog.i(TAG, "Printer selected", "role" to role, "name" to device.name)
        c.printers.probe(role)
    }

    private companion object {
        const val TAG = "Printers"
    }
}
