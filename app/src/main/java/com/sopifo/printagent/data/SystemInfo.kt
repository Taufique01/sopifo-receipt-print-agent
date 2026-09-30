package com.sopifo.printagent.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.sopifo.printagent.BuildConfig

/** Read-only device facts. Every accessor is defensive; OEM builds throw in surprising places. */
class SystemInfo(private val context: Context) {

    val appVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    fun batteryPercent(): Int? = try {
        val bm = context.getSystemService(BatteryManager::class.java)
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    } catch (_: Exception) {
        null
    }

    fun isCharging(): Boolean = try {
        context.getSystemService(BatteryManager::class.java)?.isCharging == true
    } catch (_: Exception) {
        false
    }

    fun deviceName(): String = try {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    } catch (_: Exception) {
        null
    }?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    fun isNetworkAvailable(): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) {
        false
    }

    fun isIgnoringBatteryOptimizations(): Boolean = try {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (_: Exception) {
        false
    }
}
