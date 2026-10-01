package com.sopifo.printagent.print

import android.os.SystemClock
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
import java.util.concurrent.ConcurrentHashMap

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
 *
 * Heartbeat probes back off while a printer is unreachable (phone carried away from it), since
 * each failed probe holds the radio and CPU for up to the connect timeout. Jobs never back off.
 */
class PrinterManager(
    private val printerDao: PrinterConfigDao,
    private val transport: PrinterTransport,
    private val builder: PrintDataBuilder,
    private val environment: Environment,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    interface Environment {
        fun isBluetoothOn(): Boolean
        fun hasPermission(): Boolean
    }

    private val locks = PrinterRole.entries.associateWith { Mutex() }
    private val _status = MutableStateFlow(PrinterRole.entries.associateWith { PrinterState.NOT_CONFIGURED })
    val status: StateFlow<Map<PrinterRole, PrinterState>> = _status.asStateFlow()

    private class ProbeBackoff(val failures: Int, val nextProbeAt: Long)
    private val probeBackoff = ConcurrentHashMap<PrinterRole, ProbeBackoff>()

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
            if (state == PrinterState.DISCONNECTED) recordProbeFailure(role) else probeBackoff.remove(role)
            state
        } finally {
            lock.unlock()
        }
    }

    /** [respectBackoff] is for the periodic heartbeat; user- and event-driven probes always run. */
    suspend fun probeAll(respectBackoff: Boolean = false) {
        for (role in PrinterRole.entries) {
            try {
                if (respectBackoff && !probeDue(role)) {
                    AppLog.d(TAG, "Probe skipped (backoff)", "role" to role)
                    continue
                }
                probe(role)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Probe failed", e, "role" to role)
            }
        }
    }

    /** Next heartbeat probes every printer again (Bluetooth turned on, app opened). */
    fun resetProbeBackoff() {
        probeBackoff.clear()
    }

    private fun probeDue(role: PrinterRole): Boolean {
        val backoff = probeBackoff[role] ?: return true
        // Slack, because heartbeats drift a little late or early relative to the backoff deadline.
        return clock() + PROBE_SLACK_MS >= backoff.nextProbeAt
    }

    private fun recordProbeFailure(role: PrinterRole) {
        val failures = (probeBackoff[role]?.failures ?: 0) + 1
        val delayMs = if (failures == 1) PROBE_BACKOFF_FIRST_MS else PROBE_BACKOFF_MAX_MS
        probeBackoff[role] = ProbeBackoff(failures, clock() + delayMs)
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
    suspend fun buildFromPng(png: ByteArray, printer: PrinterConfigEntity, copies: Int): PrintData =
        withContext(Dispatchers.Default) { builder.fromPng(png, printer, copies) }

    private fun setStatus(role: PrinterRole, state: PrinterState) {
        // Reachable again (job printed, link event, probe): back to probing every heartbeat.
        if (state == PrinterState.CONNECTED) probeBackoff.remove(role)
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
        /** After an unreachable probe: skip one heartbeat, then probe every third (~15 min). */
        const val PROBE_BACKOFF_FIRST_MS = 10 * 60_000L
        const val PROBE_BACKOFF_MAX_MS = 15 * 60_000L
        const val PROBE_SLACK_MS = 60_000L
    }
}
