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

    /** Inline list (the guide): search without opening the pop-up. */
    fun startSearch() {
        _picker.update { it.copy(devices = emptyList(), pairFailed = false) }
        scan()
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

    /** The guide moves on once a printer is saved for this role. */
    private val _chosen = MutableStateFlow<PrinterRole?>(null)
    val chosen: StateFlow<PrinterRole?> = _chosen.asStateFlow()
    fun consumeChosen() { _chosen.value = null }

    /** Pairs if needed and saves it for [forRole] (or the open section); [autoTest] prints a test and asks. */
    fun choose(device: FoundDevice, forRole: PrinterRole? = null, autoTest: Boolean = true) {
        val role = forRole ?: _picker.value.role ?: return
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
            _chosen.value = role
            if (!autoTest) {
                _picker.update { it.copy(role = null, pairingAddress = null) }
                return@launch
            }
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

    private val settings = AppSettings.get(app)

    /** Last guide print, waiting for "Did it print?". */
    private val _testSent = MutableStateFlow(false)
    val testSent: StateFlow<Boolean> = _testSent.asStateFlow()

    enum class Sample { TEST_LABEL, PATHAO, STEADFAST, TEST_RECEIPT, TEST_INVOICE }

    /** The guide's test prints: the label printer's test page, sample courier labels (smart shrink), receipts. */
    fun printSample(sample: Sample) {
        val role = if (sample == Sample.TEST_RECEIPT || sample == Sample.TEST_INVOICE) PrinterRole.RECEIPT else PrinterRole.LABEL
        _picker.update { it.copy(busy = role) }
        viewModelScope.launch {
            try {
                val printer = c.printers.config(role) ?: throw IllegalStateException(if (role == PrinterRole.LABEL) "No label printer" else "No receipt printer")
                when (sample) {
                    Sample.TEST_LABEL, Sample.TEST_RECEIPT -> c.printers.testPrint(role)
                    else -> {
                        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            val bmp = when (sample) {
                                Sample.PATHAO -> shrink(com.sopifo.printagent.fast.SampleLabels.pathao(), printer)
                                Sample.STEADFAST -> shrink(com.sopifo.printagent.fast.SampleLabels.steadfast(), printer)
                                else -> sampleInvoice(printer)
                            }
                            PrintDataBuilder().fromDrawn(bmp, printer).bytes.also { bmp.recycle() }
                        }
                        c.printers.printFast(printer, bytes)
                    }
                }
                _testSent.value = true
            } catch (e: Exception) {
                _message.value = "✗ ${e.message}"
            } finally {
                _picker.update { it.copy(busy = null) }
            }
        }
    }

    private fun shrink(sample: android.graphics.Bitmap, printer: PrinterConfigEntity): android.graphics.Bitmap {
        val target = if (printer.protocol == PrinterProtocol.TSPL) settings.labelSize.value.target
        else com.sopifo.printagent.quick.QuickTarget(PrintDataBuilder.paperWidthDots(printer.paperWidthMm) ?: PrintDataBuilder.PAPER_58_MM_DOTS)
        return com.sopifo.printagent.quick.QuickPrintRenderer(getApplication()).smartFromBitmap(sample, target)
    }

    private fun sampleInvoice(printer: PrinterConfigEntity): android.graphics.Bitmap {
        val r = com.sopifo.printagent.fast.ReceiptRenderer
        val receipt = com.sopifo.printagent.fast.ReceiptRenderer.Receipt(
            number = "SF-1001", atMillis = System.currentTimeMillis(), shopName = "Sopifo Demo Shop",
            shopPhone = "01700-000000", shopAddress = "Mirpur, Dhaka",
            items = listOf(
                com.sopifo.printagent.fast.ReceiptRenderer.Item("T-shirt (M)", 2, 450.0, 900.0),
                com.sopifo.printagent.fast.ReceiptRenderer.Item("Cap", 1, 250.0, 250.0),
            ),
            subtotal = 1150.0, discount = 50.0, total = 1100.0, payment = "cash", received = 1200.0, change = 100.0,
            customerName = "Sample Customer", customerPhone = "01800-000000",
        )
        val width = PrintDataBuilder.paperWidthDots(printer.paperWidthMm) ?: PrintDataBuilder.PAPER_58_MM_DOTS
        return r.draw(receipt, width, if (settings.lang.value == "en") r.EN else r.BN)
    }

    /** "Did it print?" in the guide's test step. */
    fun answerTest(printed: Boolean) {
        _testSent.value = false
        settings.updateSetup { it.copy(testPrinted = printed || it.testPrinted, testFailed = !printed) }
    }

    fun clearTestSent() { _testSent.value = false }

    /** Sent to the website at once (2.1.15), so order and courier labels print at this size too. */
    fun setSize(size: com.sopifo.printagent.quick.QuickLabelSize) {
        settings.setLabelSize(size)
        c.phonePrinter.forgetDrawn()
        c.scope.launch { com.sopifo.printagent.ui.shell.SizeSync.sync(c) }
    }

    /** Step 2 done: size + gap confirmed (and sent to the website when logged in). */
    fun confirmSize() {
        settings.updateSetup { it.copy(sizeConfirmed = true) }
        c.scope.launch { com.sopifo.printagent.ui.shell.SizeSync.sync(c) }
    }

    /** Receipts on the label printer too ("Same printer"). */
    fun useLabelPrinterForReceipts(paperMm: Int) {
        viewModelScope.launch {
            val label = c.db.printerConfigDao().get(PrinterRole.LABEL) ?: return@launch
            c.db.printerConfigDao().upsert(label.copy(role = PrinterRole.RECEIPT, paperWidthMm = paperMm, updatedAt = System.currentTimeMillis()))
            settings.updateSetup { it.copy(receiptSkipped = false) }
            _chosen.value = PrinterRole.RECEIPT
        }
    }

    fun skipLabel() = settings.updateSetup { it.copy(labelSkipped = true) }
    fun unskipLabel() = settings.updateSetup { it.copy(labelSkipped = false) }
    fun skipReceipt() = settings.updateSetup { it.copy(receiptSkipped = true) }
    fun finishFirstSetup() = settings.updateSetup { it.copy(firstSetupDone = true) }

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
