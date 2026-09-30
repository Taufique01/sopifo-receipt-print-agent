package com.sopifo.printagent.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.sopifo.printagent.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServiceController {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile var lastStartError: String? = null
        private set

    internal fun setRunning(value: Boolean) {
        _running.value = value
    }

    /**
     * Starts the foreground service if it is not already running. Never throws: Android 12+
     * refuses background FGS starts in many situations; the WorkManager watchdog retries later,
     * and printing itself does not depend on the service.
     */
    fun start(context: Context, reason: String) {
        if (_running.value) return
        if (!canUseConnectedDeviceType(context)) {
            lastStartError = "Bluetooth permission not granted"
            AppLog.w(TAG, "Not starting service: Bluetooth permission missing", null, "reason" to reason)
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, PrintAgentService::class.java).putExtra(PrintAgentService.EXTRA_REASON, reason))
            lastStartError = null
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) or SecurityException.
            lastStartError = e.javaClass.simpleName
            AppLog.w(TAG, "Foreground service start refused", e, "reason" to reason)
        }
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, PrintAgentService::class.java)) }
    }

    /** Android 14+ requires BLUETOOTH_CONNECT for a connectedDevice foreground service. */
    fun canUseConnectedDeviceType(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private const val TAG = "ServiceController"
}
