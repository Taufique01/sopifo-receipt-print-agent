package com.sopifo.printagent.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.sopifo.printagent.core.AppLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A Bluetooth device the "Find my printer" list shows. */
data class FoundDevice(
    val name: String,
    val address: String,
    val paired: Boolean,
    /** Looks like a printer (imaging class or a printer-like name): listed first. */
    val printerLike: Boolean,
)

/**
 * App 2.0 printer setup inside the app: scans nearby Bluetooth devices and pairs one, so the
 * seller never has to go to Android's Bluetooth settings. Classic Bluetooth (the cheap thermal
 * printers are SPP), discovery runs about 12 s.
 */
class BluetoothFinder(private val context: Context) {
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isEnabled: Boolean get() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
    val isSupported: Boolean get() = adapter != null

    /** Runtime permissions discovery needs on this Android version. */
    fun neededPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun hasPermissions() = neededPermissions().isEmpty()

    /** Paired devices plus what discovery finds, updated as they appear; ends when discovery ends. */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<List<FoundDevice>> = callbackFlow {
        val bt = adapter
        if (bt == null || !hasPermissions()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val found = linkedMapOf<String, FoundDevice>()
        runCatching { bt.bondedDevices }.getOrNull().orEmpty().forEach { d -> found[d.address] = describe(d, paired = true) }
        trySend(sorted(found.values))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = deviceExtra(intent) ?: return
                        val known = found[device.address]
                        found[device.address] = describe(device, paired = known?.paired ?: (device.bondState == BluetoothDevice.BOND_BONDED))
                        trySend(sorted(found.values))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        runCatching {
            if (bt.isDiscovering) bt.cancelDiscovery()
            if (!bt.startDiscovery()) close()
        }.onFailure { e ->
            AppLog.w(TAG, "Discovery failed to start", e)
            close()
        }
        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { if (bt.isDiscovering) bt.cancelDiscovery() }
        }
    }

    /** Pairs [address] (Android shows the PIN prompt if the printer asks). True once bonded. */
    @SuppressLint("MissingPermission")
    suspend fun pair(address: String): Boolean {
        val bt = adapter ?: return false
        val device = runCatching { bt.getRemoteDevice(address) }.getOrNull() ?: return false
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        runCatching { if (bt.isDiscovering) bt.cancelDiscovery() }
        val bonded = callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val d = deviceExtra(intent) ?: return
                    if (d.address != address) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_BONDED -> trySend(true)
                        BluetoothDevice.BOND_NONE -> trySend(false)
                    }
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            if (!runCatching { device.createBond() }.getOrDefault(false)) trySend(device.bondState == BluetoothDevice.BOND_BONDED)
            awaitClose { runCatching { context.unregisterReceiver(receiver) } }
        }
        return withTimeoutOrNull(PAIR_TIMEOUT_MS) { bonded.first() } ?: false
    }

    @SuppressLint("MissingPermission")
    private fun describe(d: BluetoothDevice, paired: Boolean): FoundDevice {
        val name = runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.address
        val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()
        val printerLike = major == BluetoothClass.Device.Major.IMAGING || looksLikePrinter(name)
        return FoundDevice(name, d.address, paired, printerLike)
    }

    private fun sorted(list: Collection<FoundDevice>) =
        list.sortedWith(compareByDescending<FoundDevice> { it.printerLike }.thenByDescending { it.paired }.thenBy { it.name.lowercase() })

    @Suppress("DEPRECATION")
    private fun deviceExtra(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "BluetoothFinder"
        private const val PAIR_TIMEOUT_MS = 60_000L
        private val PRINTER_NAME = Regex(
            "(?i)(print|xp-|xprinter|pos|thermal|label|mtp|mpt|rpp|hprt|tsc|gprinter|gp-|zj|pt-?2|p58|p80|58mm|80mm|bt-?58|qr-?|sunmi|rongta|munbyn|niimbot|phomemo)",
        )

        /** Name-based guess, also used to pick Labels vs Receipts. */
        fun looksLikePrinter(name: String) = PRINTER_NAME.containsMatchIn(name)

        /** XP-365B / XP-420B / TSC / "label" printers print labels; the rest are receipt rolls. */
        fun guessLabelPrinter(name: String) = Regex("(?i)(365|420|470|460|label|tsc|tspl|niimbot|phomemo|d11|m110|xp-?(3|4)\\d\\d)").containsMatchIn(name)

        /** 80 mm when the name says so, else 58 mm (the common cheap roll). */
        fun guessPaperMm(name: String) = if (Regex("(?i)80").containsMatchIn(name)) 80 else 58
    }
}
