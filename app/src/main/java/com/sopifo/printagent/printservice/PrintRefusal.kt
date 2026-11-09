package com.sopifo.printagent.printservice

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sopifo.printagent.R
import com.sopifo.printagent.service.RunNotifications
import com.sopifo.printagent.ui.MainActivity
import com.sopifo.printagent.ui.print.SetupChecklist

/**
 * Print → Sopifo turned a print down. Android only shows its own "Printer error", so Sopifo also
 * posts the real reason with one button that fixes it (Log in / Open Printers / Open Sopifo).
 */
object PrintRefusal {
    enum class Reason { LOGIN, NO_LABEL_PRINTER, OTHER }

    data class Notice(val title: String, val text: String, val button: String, val open: String?)

    /** The words and button for [reason] ([detail]: the error for OTHER). Pure, for tests. */
    fun notice(reason: Reason, lang: String, detail: String? = null): Notice {
        val en = lang == "en"
        val title = if (en) "Sopifo couldn't print" else "Sopifo প্রিন্ট করতে পারেনি"
        return when (reason) {
            Reason.LOGIN -> Notice(
                title,
                if (en) "${SetupChecklist.FREE_SERVICE_PRINTS} free prints from other apps are used up. Log in to Sopifo to keep printing."
                else "অন্য অ্যাপ থেকে ${SetupChecklist.FREE_SERVICE_PRINTS}টি ফ্রি প্রিন্ট শেষ। প্রিন্ট চালিয়ে যেতে Sopifo-তে লগ ইন করুন।",
                if (en) "Log in" else "লগ ইন",
                MainActivity.OPEN_LOGIN,
            )
            Reason.NO_LABEL_PRINTER -> Notice(
                title,
                if (en) "No label printer is set. Choose your label printer in Sopifo → Printers."
                else "লেবেল প্রিন্টার সেট করা নেই। Sopifo → প্রিন্টার-এ লেবেল প্রিন্টার বেছে নিন।",
                if (en) "Open Printers" else "প্রিন্টার খুলুন",
                MainActivity.OPEN_PRINTERS,
            )
            Reason.OTHER -> Notice(
                title,
                detail?.takeIf { it.isNotBlank() }?.take(200) ?: if (en) "Something went wrong. Try again." else "সমস্যা হয়েছে। আবার চেষ্টা করুন।",
                if (en) "Open Sopifo" else "Sopifo খুলুন",
                null,
            )
        }
    }

    fun show(context: Context, reason: Reason, lang: String, detail: String? = null) {
        val n = notice(reason, lang, detail)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { n.open?.let { putExtra(MainActivity.EXTRA_OPEN, it) } }
        val tap = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, RunNotifications.CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_sopifo)
            .setColor(ContextCompat.getColor(context, R.color.brand_teal))
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .addAction(0, n.button, tap)
            .build()
        // One notice at a time (the same id): several refused pages don't pile up.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private const val NOTIFICATION_ID = 1005
    private const val REQUEST_CODE = 5
}
