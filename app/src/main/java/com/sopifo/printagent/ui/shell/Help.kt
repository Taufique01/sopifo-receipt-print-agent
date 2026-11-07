package com.sopifo.printagent.ui.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.BuildConfig
import com.sopifo.printagent.ui.theme.AppIcons

/** The one 💬 Help on WhatsApp (support), never about paying. Names the page, shop, phone and app. */
object SupportWhatsApp {
    private const val NUMBER = "8801340404043"

    fun open(context: Context, page: String) {
        val shop = AppSettings.get(context).shop.value.slug ?: "-"
        val bn = AppSettings.get(context).lang.value != "en"
        val head = if (bn) "আসসালামু আলাইকুম, সাহায্য দরকার।" else "Assalamu alaikum, I need help."
        val text = "$head ${if (bn) "পেজ" else "Page"}: $page · ${if (bn) "দোকান" else "Shop"}: $shop · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · Sopifo ${BuildConfig.VERSION_NAME}"
        val uri = Uri.parse("https://wa.me/$NUMBER?text=${Uri.encode(text)}")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

val WhatsAppGreen = Color(0xFF1FA855)

/** Round 💬 icon (top bars). */
@Composable
fun HelpIcon(page: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    IconButton(onClick = { SupportWhatsApp.open(context, page) }, modifier = modifier.testTag("help_whatsapp")) {
        Icon(AppIcons.Chat, contentDescription = strings().help, tint = WhatsAppGreen)
    }
}

/** 💬 Help pill (setup screens). */
@Composable
fun HelpButton(page: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val t = strings()
    OutlinedButton(onClick = { SupportWhatsApp.open(context, page) }, modifier = modifier.testTag("help_whatsapp")) {
        Icon(AppIcons.Chat, contentDescription = null, tint = WhatsAppGreen)
        Spacer(Modifier.size(6.dp))
        Text(t.help)
    }
}
