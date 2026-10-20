package com.sopifo.printagent.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.db.PrinterConfigEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** A short-lived printer connection. Opened per job and always closed afterwards. */
interface PrinterConnection {
    suspend fun write(data: ByteArray)
    fun close()
}

interface PrinterTransport {
    /** Throws [PrintException] if the printer cannot be reached. */
    suspend fun open(printer: PrinterConfigEntity): PrinterConnection
}

/**
 * Classic Bluetooth SPP (RFCOMM) transport. No socket is kept open between jobs, which is
 * what keeps the radio — and the battery — idle while the device waits for work.
 */
class BluetoothPrinterTransport(
    private val context: Context,
    private val connectTimeoutMs: Long = 10_000,
) : PrinterTransport {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothOn(): Boolean = try {
        adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    @SuppressLint("MissingPermission")
    override suspend fun open(printer: PrinterConfigEntity): PrinterConnection {
        val bt = adapter ?: throw PrintException(PrintFailure.BLUETOOTH_UNAVAILABLE, "No Bluetooth adapter")
        if (!hasConnectPermission()) throw PrintException(PrintFailure.NO_PERMISSION, "Bluetooth permission not granted")
        if (!isBluetoothOn()) throw PrintException(PrintFailure.BLUETOOTH_OFF, "Bluetooth is off")
        if (!BluetoothAdapter.checkBluetoothAddress(printer.macAddress)) {
            throw PrintException(PrintFailure.NOT_CONFIGURED, "Invalid printer address")
        }
        return withContext(Dispatchers.IO) {
            try {
                // Discovery slows RFCOMM connects dramatically; it is never needed here.
                runCatching { bt.cancelDiscovery() }
                val device = bt.getRemoteDevice(printer.macAddress)
                val socket = try {
                    connect(device.createRfcommSocketToServiceRecord(SPP_UUID))
                } catch (secure: IOException) {
                    AppLog.w(TAG, "Secure RFCOMM failed, trying insecure", secure, "printer" to printer.name)
                    connect(device.createInsecureRfcommSocketToServiceRecord(SPP_UUID))
                }
                BluetoothConnection(socket, printer.sendSpeedKbps)
            } catch (e: PrintException) {
                throw e
            } catch (e: SecurityException) {
                throw PrintException(PrintFailure.NO_PERMISSION, "Bluetooth permission revoked", e)
            } catch (e: IOException) {
                throw PrintException(PrintFailure.CONNECT_FAILED, "Printer not reachable: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw PrintException(PrintFailure.NOT_CONFIGURED, "Invalid printer address", e)
            }
        }
    }

    /**
     * BluetoothSocket.connect() ignores interrupts; closing the socket is meant to unblock it,
     * but on some phones neither returns. HardTimeout gives up on time either way.
     */
    @SuppressLint("MissingPermission")
    private suspend fun connect(socket: BluetoothSocket): BluetoothSocket = try {
        HardTimeout.run(connectTimeoutMs, "connect", onTimeout = { socket.close() }) {
            socket.connect()
            socket
        }
    } catch (e: IOException) {
        closeInBackground(socket)
        throw e
    } catch (e: PrintException) {
        AppLog.w(TAG, "Connect gave up", e)
        throw e
    }

    private class BluetoothConnection(private val socket: BluetoothSocket, private val speedKbps: Int) : PrinterConnection {
        override suspend fun write(data: ByteArray) {
            // The whole send runs on one thread, paced with sleeps, under one hard time limit: a
            // write that never returns fails the print and frees the printer instead of holding it.
            try {
                HardTimeout.run(SendSpeed.writeTimeoutMs(data.size, speedKbps), "write", onTimeout = { socket.close() }) {
                    val out = socket.outputStream
                    var offset = 0
                    val chunk = SendSpeed.chunkSize(speedKbps)
                    val pause = SendSpeed.chunkDelayMs(speedKbps)
                    while (offset < data.size) {
                        val n = minOf(chunk, data.size - offset)
                        out.write(data, offset, n)
                        out.flush()
                        offset += n
                        // Pace writes at the printer's send speed: cheap printers drop bytes if their
                        // small buffer overflows.
                        Thread.sleep(pause)
                    }
                    // Give the printer time to drain before the link closes (closing early truncates output).
                    Thread.sleep(drainDelayMs(data.size))
                }
            } catch (e: IOException) {
                throw PrintException(PrintFailure.WRITE_FAILED, "Write failed: ${e.message}", e)
            }
        }

        // close() can block on the same phones: never on the caller's thread.
        override fun close() = closeInBackground(socket)
    }

    companion object {
        private const val TAG = "BtTransport"

        fun closeInBackground(socket: BluetoothSocket) = HardTimeout.daemon("bt-close") {
            runCatching { socket.outputStream.close() }
            runCatching { socket.close() }
        }
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        fun drainDelayMs(bytes: Int): Long = (300L + bytes / 20).coerceAtMost(4_000L)
    }
}

/**
 * Debug-only virtual printer: writes print data to app storage instead of Bluetooth, so the full
 * job pipeline can be tested on a phone with no printer attached. Never used in release builds.
 */
class FilePrinterTransport(private val dir: File) : PrinterTransport {
    override suspend fun open(printer: PrinterConfigEntity): PrinterConnection {
        dir.mkdirs()
        return object : PrinterConnection {
            override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
                File(dir, "${System.currentTimeMillis()}-${printer.role.name.lowercase()}.bin").writeBytes(data)
            }

            override fun close() {}
        }
    }

    companion object {
        const val VIRTUAL_MAC = "00:00:00:00:00:00"
    }
}

/** Routes to the virtual printer only when a debug build is configured with its sentinel address. */
class RoutingPrinterTransport(
    private val bluetooth: BluetoothPrinterTransport,
    private val virtual: PrinterTransport?,
) : PrinterTransport {
    override suspend fun open(printer: PrinterConfigEntity): PrinterConnection =
        if (virtual != null && printer.macAddress == FilePrinterTransport.VIRTUAL_MAC) virtual.open(printer)
        else bluetooth.open(printer)
}
