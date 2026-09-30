package com.sopifo.printagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.core.AppLog

/**
 * After reboot or an app update: restore the session and bring the agent back without any
 * user interaction (service, heartbeat, FCM token, printers, pending jobs).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return
        try {
            AppLog.i("Boot", "System event", "action" to action)
            (context.applicationContext as SopifoApp).container.startAgent(reason = if (action == Intent.ACTION_MY_PACKAGE_REPLACED) "app_updated" else "boot")
        } catch (t: Throwable) {
            AppLog.e("Boot", "Boot recovery failed", t)
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
