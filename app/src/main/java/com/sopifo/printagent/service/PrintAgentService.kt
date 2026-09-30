package com.sopifo.printagent.service

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.print.PrinterState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Keeps the agent process alive and reacts to system events. It deliberately does no periodic
 * work of its own — no loops, no polling, no held wake locks, no open Bluetooth sockets. It
 * only listens for events that should trigger pending-job recovery:
 *  network reconnect, device wake (screen on / Doze exit), Bluetooth on, printer link up.
 */
class PrintAgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
        AppLog.e(TAG, "Service coroutine failed", t)
    })
    private val container get() = (application as SopifoApp).container
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var receiverRegistered = false
    private var started = false
    @Volatile private var networkLost = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceController.setRunning(true)
        AppLog.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!container.device.hasSession()) {
            AppLog.i(TAG, "No device session; stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            val reason = intent?.getStringExtra(EXTRA_REASON) ?: "restart"
            AppLog.i(TAG, "Service started", "reason" to reason)
            registerListeners()
            observeStatus()
            container.scheduler.enqueuePendingSync("service_start")
            container.scheduler.ensureHeartbeat()
            scope.launch { container.printers.probeAll() }
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        val notification = Notifications.serviceNotification(this, "Ready")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, Notifications.SERVICE_NOTIFICATION_ID, notification, type)
        true
    } catch (e: Exception) {
        AppLog.e(TAG, "startForeground failed", e)
        false
    }

    private fun registerListeners() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (networkLost) {
                        networkLost = false
                        AppLog.i(TAG, "Network reconnected")
                        container.scheduler.enqueuePendingSync("network_reconnect")
                    }
                }

                override fun onLost(network: Network) {
                    networkLost = true
                    container.onNetworkLost()
                }
            }
            cm?.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            AppLog.e(TAG, "Network callback registration failed", e)
        }

        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
            ContextCompat.registerReceiver(this, eventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (e: Exception) {
            AppLog.e(TAG, "Receiver registration failed", e)
        }
    }

    private val eventReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        AppLog.i(TAG, "Bluetooth state changed", "state" to state)
                        scope.launch {
                            container.printers.refreshPassive()
                            if (state == BluetoothAdapter.STATE_ON) {
                                container.printers.probeAll()
                                container.scheduler.enqueuePendingSync("bluetooth_on")
                            }
                        }
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                        val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
                        scope.launch { container.printers.onLinkEvent(device.address, connected) }
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> container.scheduler.enqueuePendingSync("device_wake")
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        val pm = getSystemService(PowerManager::class.java)
                        if (pm?.isDeviceIdleMode == false) container.scheduler.enqueuePendingSync("doze_exit")
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Event handling failed", e, "action" to intent.action)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeStatus() {
        combine(container.printers.status, container.cloudOnline) { printers, online ->
            val cloud = if (online == true) "Online" else "No server connection"
            "Cloud: $cloud · Receipt: ${label(printers[PrinterRole.RECEIPT])} · Label: ${label(printers[PrinterRole.LABEL])}"
        }
            .distinctUntilChanged()
            .debounce(1_000)
            .onEach { text ->
                try {
                    if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                        NotificationManagerCompat.from(this).notify(Notifications.SERVICE_NOTIFICATION_ID, Notifications.serviceNotification(this, text))
                    }
                } catch (_: SecurityException) {
                }
            }
            .launchIn(scope)
    }

    private fun label(state: PrinterState?) = when (state) {
        PrinterState.CONNECTED -> "Connected"
        PrinterState.NOT_CONFIGURED, null -> "Not set"
        else -> "Disconnected"
    }

    override fun onDestroy() {
        AppLog.i(TAG, "Service destroyed")
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        if (receiverRegistered) runCatching { unregisterReceiver(eventReceiver) }
        scope.cancel()
        ServiceController.setRunning(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AgentService"
        const val EXTRA_REASON = "reason"
    }
}
