package com.sopifo.printagent.print

import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.db.PrinterConfigDao
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PrinterState(val wire: String) {
    NOT_CONFIGURED("not_configured"),
    CONNECTED("connected"),
    DISCONNECTED("disconnected"),
    BLUETOOTH_OFF("bluetooth_off"),
    NO_PERMISSION("no_permission"),
}

/**
 * Owns all printer I/O. One mutex per printer serialises jobs, probes and test prints so they
 * never interleave bytes on the same link. Status is "last known reachability": updated by every
 * job, by probes (service start, Bluetooth on, heartbeat) and by ACL link events.
 */
class PrinterManager(
    private val printerDao: PrinterConfigDao,
    private val transport: PrinterTransport,
    private val builder: PrintDataBuilder,
    private val environment: Environment,
) {
    interface Environment {
        fun isBluetoothOn(): Boolean
        fun hasPermission(): Boolean
    }

    private val locks = PrinterRole.entries.associateWith { Mutex() }
    private val _status = MutableStateFlow(PrinterRole.entries.associateWith { PrinterState.NOT_CONFIGURED })
    val status: StateFlow<Map<PrinterRole, PrinterState>> = _status.asStateFlow()

    suspend fun config(role: PrinterRole): PrinterConfigEntity? = printerDao.get(role)

    /**
     * Opens the printer (with retries), calls [beforeWrite] once connected, then sends [data].
     * [beforeWrite] is where the job is claimed in the idempotency ledger: nothing is claimed
     * unless a link is actually up, so connection failures remain safely retryable.
     */
    suspend fun print(
        printer: PrinterConfigEntity,
        data: ByteArray,
        attempts: Int = 3,
        shouldContinue: () -> Boolean = { true },
        beforeWrite: suspend () -> Unit = {},
    ) {
        locks.getValue(printer.role).withLock {
            var lastError: PrintException? = null
            for (attempt in 1..attempts) {
                if (!shouldContinue()) break
                val connection = try {
                    transport.open(printer)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PrintException) {
                    lastError = e
                    setStatus(printer.role, stateFor(e))
                    AppLog.w(TAG, "Open failed", e, "role" to printer.role, "attempt" to attempt, "failure" to e.failure)
                    if (!e.failure.retryable) throw e
                    if (attempt < attempts) delay(backoffMs(attempt))
                    continue
                } catch (e: Exception) {
                    lastError = PrintException(PrintFailure.CONNECT_FAILED, e.message ?: "open failed", e)
                    if (attempt < attempts) delay(backoffMs(attempt))
                    continue
                }
                try {
                    setStatus(printer.role, PrinterState.CONNECTED)
                    beforeWrite()
                    connection.write(data)
                    return
                } catch (e: PrintException) {
                    setStatus(printer.role, PrinterState.DISCONNECTED)
                    // Bytes may have partially reached the printer; do not silently retry the write.
                    throw e
                } finally {
                    connection.close()
                }
            }
            throw lastError ?: PrintException(PrintFailure.CONNECT_FAILED, "Printing aborted")
        }
    }

    /** Opens and immediately closes the link to learn whether the printer is reachable. */
    suspend fun probe(role: PrinterRole): PrinterState {
        val printer = printerDao.get(role)
        if (printer == null) {
            setStatus(role, PrinterState.NOT_CONFIGURED)
            return PrinterState.NOT_CONFIGURED
        }
        val lock = locks.getValue(role)
        // A job is using the printer right now, so it is evidently reachable; don't queue behind it.
        if (!lock.tryLock()) return _status.value.getValue(role)
        return try {
            val state = try {
                transport.open(printer).close()
                PrinterState.CONNECTED
            } catch (e: PrintException) {
                stateFor(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PrinterState.DISCONNECTED
            }
            setStatus(role, state)
            state
        } finally {
            lock.unlock()
        }
    }

    suspend fun probeAll() {
        for (role in PrinterRole.entries) {
            try {
                probe(role)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Probe failed", e, "role" to role)
            }
        }
    }

    /** Recomputes status without touching the radio (used for Bluetooth on/off and permission changes). */
    suspend fun refreshPassive() {
        val configured = printerDao.getAll().associateBy { it.role }
        for (role in PrinterRole.entries) {
            val state = when {
                configured[role] == null -> PrinterState.NOT_CONFIGURED
                !environment.hasPermission() -> PrinterState.NO_PERMISSION
                !environment.isBluetoothOn() -> PrinterState.BLUETOOTH_OFF
                else -> _status.value.getValue(role).let {
                    if (it == PrinterState.NOT_CONFIGURED || it == PrinterState.BLUETOOTH_OFF || it == PrinterState.NO_PERMISSION) PrinterState.DISCONNECTED else it
                }
            }
            setStatus(role, state)
        }
    }

    /** Link-level event from the system (ACL connected/disconnected) for a configured printer. */
    suspend fun onLinkEvent(macAddress: String, connected: Boolean) {
        val printer = printerDao.getAll().firstOrNull { it.macAddress.equals(macAddress, ignoreCase = true) } ?: return
        setStatus(printer.role, if (connected) PrinterState.CONNECTED else PrinterState.DISCONNECTED)
    }

    suspend fun testPrint(role: PrinterRole) {
        val printer = printerDao.get(role) ?: throw PrintException(PrintFailure.NOT_CONFIGURED, "No ${role.name.lowercase()} printer configured")
        print(printer, withContext(Dispatchers.Default) { builder.testPage(printer) })
    }

    /** Image decoding/thresholding is CPU-bound; keep it off the main thread whoever calls. */
    suspend fun buildFromPng(png: ByteArray, printer: PrinterConfigEntity, copies: Int): ByteArray =
        withContext(Dispatchers.Default) { builder.fromPng(png, printer, copies) }

    private fun setStatus(role: PrinterRole, state: PrinterState) {
        _status.update { it + (role to state) }
    }

    private fun stateFor(e: PrintException): PrinterState = when (e.failure) {
        PrintFailure.NOT_CONFIGURED -> PrinterState.NOT_CONFIGURED
        PrintFailure.BLUETOOTH_OFF, PrintFailure.BLUETOOTH_UNAVAILABLE -> PrinterState.BLUETOOTH_OFF
        PrintFailure.NO_PERMISSION -> PrinterState.NO_PERMISSION
        else -> PrinterState.DISCONNECTED
    }

    private fun backoffMs(attempt: Int) = 1_500L * attempt

    private companion object {
        const val TAG = "PrinterManager"
    }
}
