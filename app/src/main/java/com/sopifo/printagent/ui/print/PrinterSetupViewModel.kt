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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupStep { SCAN, KIND, TESTING, ASK, DONE }

/** What the printer prints: labels & stickers (TSPL) or receipt rolls (ESC/POS, 58 / 80 mm). */
enum class PrinterKind { LABELS, RECEIPTS }

data class SetupState(
    val step: SetupStep = SetupStep.SCAN,
    val scanning: Boolean = false,
    val devices: List<FoundDevice> = emptyList(),
    val showAll: Boolean = false,
    val pairingAddress: String? = null,
    val chosen: FoundDevice? = null,
    val kind: PrinterKind = PrinterKind.LABELS,
    val paperMm: Int = 58,
    val error: String? = null,
)

/**
 * "Add printer": find it nearby, pair it in the app, say what it prints, test it, ask "Did it
 * print?". No printer language or Android settings; Advanced stays under More → Printers.
 */
class PrinterSetupViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as SopifoApp).container
    val finder = BluetoothFinder(app)
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()
    private var scanJob: Job? = null

    fun scan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = true, error = null, step = SetupStep.SCAN) }
        scanJob = viewModelScope.launch {
            try {
                finder.scan().collect { list -> _state.update { it.copy(devices = list) } }
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    fun toggleShowAll() = _state.update { it.copy(showAll = !it.showAll) }

    /** Pairs if needed, then asks what it prints (guessed from the name). */
    fun choose(device: FoundDevice) {
        if (_state.value.pairingAddress != null) return
        scanJob?.cancel()
        _state.update { it.copy(pairingAddress = device.address, error = null) }
        viewModelScope.launch {
            val ok = device.paired || finder.pair(device.address)
            _state.update {
                if (!ok) {
                    it.copy(pairingAddress = null, error = "pair")
                } else {
                    it.copy(
                        pairingAddress = null,
                        chosen = device.copy(paired = true),
                        step = SetupStep.KIND,
                        kind = if (BluetoothFinder.guessLabelPrinter(device.name)) PrinterKind.LABELS else PrinterKind.RECEIPTS,
                        paperMm = BluetoothFinder.guessPaperMm(device.name),
                    )
                }
            }
        }
    }

    fun setKind(kind: PrinterKind) = _state.update { it.copy(kind = kind) }
    fun setPaper(mm: Int) = _state.update { it.copy(paperMm = mm) }

    /** Saves the printer for its role and prints a test page. */
    fun useAndTest() {
        val s = _state.value
        val device = s.chosen ?: return
        val role = if (s.kind == PrinterKind.LABELS) PrinterRole.LABEL else PrinterRole.RECEIPT
        _state.update { it.copy(step = SetupStep.TESTING, error = null) }
        viewModelScope.launch {
            try {
                val previous = c.db.printerConfigDao().get(role)
                c.db.printerConfigDao().upsert(
                    PrinterConfigEntity(
                        role = role,
                        name = device.name,
                        macAddress = device.address,
                        protocol = if (role == PrinterRole.LABEL) PrinterProtocol.TSPL else PrinterProtocol.ESC_POS,
                        widthDots = PrintDataBuilder.MAX_ESC_POS_WIDTH,
                        paperWidthMm = if (role == PrinterRole.RECEIPT) s.paperMm else previous?.paperWidthMm,
                        labelGapMm = previous?.labelGapMm ?: 2,
                        labelBlackMark = previous?.labelBlackMark ?: false,
                        sendSpeedKbps = previous?.sendSpeedKbps ?: SendSpeed.FAST_KBPS,
                    ),
                )
                AppLog.i(TAG, "Printer set up in app", "role" to role, "name" to device.name)
                c.printers.probe(role)
                c.printers.testPrint(role)
                _state.update { it.copy(step = SetupStep.ASK) }
            } catch (e: Exception) {
                AppLog.w(TAG, "Setup test print failed", e)
                _state.update { it.copy(step = SetupStep.ASK, error = e.message ?: "print") }
            }
        }
    }

    fun answer(printed: Boolean) {
        if (printed) {
            AppSettings.get(getApplication()).setTestPrinted()
            _state.update { it.copy(step = SetupStep.DONE) }
        } else {
            _state.update { it.copy(step = SetupStep.KIND, error = "noprint") }
        }
    }

    fun restart() {
        _state.value = SetupState()
        scan()
    }

    private companion object {
        const val TAG = "PrinterSetup"
    }
}
