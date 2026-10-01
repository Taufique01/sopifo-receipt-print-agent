package com.sopifo.printagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sopifo.printagent.R
import com.sopifo.printagent.ui.MainActivity

object Notifications {
    const val CHANNEL_SERVICE = "agent_service"
    const val CHANNEL_WORK = "agent_jobs"
    const val SERVICE_NOTIFICATION_ID = 1001
    const val WORK_NOTIFICATION_ID = 1002

    fun createChannels(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.notif_channel_service_desc)
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WORK, context.getString(R.string.notif_channel_jobs), NotificationManager.IMPORTANCE_MIN).apply {
                    description = context.getString(R.string.notif_channel_jobs_desc)
                    setShowBadge(false)
                },
            )
        } catch (_: Exception) {
        }
    }

    fun serviceNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_sopifo)
            .setColor(ContextCompat.getColor(context, R.color.brand_teal))
            .setContentTitle("Sopifo Print")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .build()

    fun workNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat_sopifo)
            .setColor(ContextCompat.getColor(context, R.color.brand_teal))
            .setContentTitle("Printing")
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
