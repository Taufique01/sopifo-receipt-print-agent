package com.sopifo.printagent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sopifo.printagent.R
import com.sopifo.printagent.data.db.PrintRunEntity
import com.sopifo.printagent.data.db.RunState
import com.sopifo.printagent.runs.RunNotifier
import com.sopifo.printagent.ui.MainActivity

/**
 * The seller's feedback while the phone is locked: an ongoing "Printing 12 of 40…" while a run
 * prints, then one result notification ("40 printed", "38 printed · 2 failed", or why it stopped).
 * Tapping either opens the app on Activity.
 */
class RunNotifications(private val context: Context) : RunNotifier {
    private val nm = NotificationManagerCompat.from(context)

    fun createChannels() {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.notif_channel_run_progress), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.notif_channel_run_progress_desc)
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.notif_channel_run_results), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.notif_channel_run_results_desc)
                },
            )
        } catch (_: Exception) {
        }
    }

    override fun progress(run: PrintRunEntity, done: Int) {
        notify(
            PROGRESS_ID,
            NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_sopifo)
                .setColor(ContextCompat.getColor(context, R.color.brand_teal))
                .setContentTitle("Printing ${run.title.lowercase()}")
                .setContentText("$done of ${run.total}" + if (run.failed > 0) " · ${run.failed} failed" else "")
                .setProgress(run.total, done, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openActivity())
                .build(),
        )
    }

    override fun finished(run: PrintRunEntity) {
        runCatching { nm.cancel(PROGRESS_ID) }
        val title = when {
            run.state == RunState.STOPPED && run.printed == 0 -> "${run.title}: nothing printed"
            run.failed == 0 -> "${run.title}: ${run.printed} printed"
            else -> "${run.title}: ${run.printed} printed · ${run.failed} not printed"
        }
        val text = run.message ?: if (run.failed > 0) "Open Activity to see which ones and retry" else "All done"
        notify(
            RESULT_ID,
            NotificationCompat.Builder(context, CHANNEL_RESULTS)
                .setSmallIcon(R.drawable.ic_stat_sopifo)
                .setColor(ContextCompat.getColor(context, R.color.brand_teal))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openActivity())
                .build(),
        )
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        // Without the notification permission the in-app Activity still shows everything.
        runCatching { nm.notify(id, notification) }
    }

    private fun openActivity(): PendingIntent = PendingIntent.getActivity(
        context,
        2,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_PROGRESS = "print_runs_progress"
        const val CHANNEL_RESULTS = "print_runs_results"
        const val PROGRESS_ID = 1003
        const val RESULT_ID = 1004
    }
}
