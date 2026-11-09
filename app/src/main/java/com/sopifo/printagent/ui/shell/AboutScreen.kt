package com.sopifo.printagent.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.BuildConfig
import com.sopifo.printagent.R
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.core.LogShare
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.ui.pages.WebLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** "2.1.10" vs "2.1.9": numbers compared part by part (a "-beta" tail is ignored). */
object AppVersion {
    fun isNewer(latest: String?, current: String): Boolean {
        val a = parts(latest ?: return false)
        val b = parts(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int> =
        v.substringBefore('-').trim().split('.').mapNotNull { it.toIntOrNull() }
}

private val http by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build() }

/**
 * About Sopifo (ⓘ beside Store, or on the start screen): version, what Sopifo is, this phone,
 * updates (Google Play only: no APK in the app), logs to support, help and the policies.
 * No payment or plan links (Google Play policy).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AboutScreen(onClose: () -> Unit, connected: Boolean? = null) {
    val context = LocalContext.current
    val c = (context.applicationContext as SopifoApp).container
    val settings = AppSettings.get(context)
    val lang by settings.lang.collectAsStateWithLifecycle()
    val shop by settings.shop.collectAsStateWithLifecycle()
    val bn = lang != "en"
    fun tr(en: String, b: String) = if (bn) b else en
    val scope = rememberCoroutineScope()
    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    var labelPrinter by remember { mutableStateOf<String?>(null) }
    var receiptPrinter by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        labelPrinter = c.printers.config(PrinterRole.LABEL)?.name
        receiptPrinter = c.printers.config(PrinterRole.RECEIPT)?.name
    }
    /** null = checking / unknown; "" = up to date; else the newer version. */
    var latest by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        latest = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url("${WebSession.base(c)}/api/health").build()).execute().use { res ->
                    JSONObject(res.body?.string().orEmpty()).optJSONObject("apps")?.optString("android")
                }
            }.getOrNull()
        }
        checked = true
    }
    var sending by remember { mutableStateOf(false) }

    fun openPlay() {
        val id = context.packageName
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { WebLinks.open(context, "https://play.google.com/store/apps/details?id=$id") }
    }
    fun openSite(path: String) = WebLinks.open(context, "${WebSession.base(c)}/$path")

    Column(Modifier.fillMaxSize().testTag("about_screen")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("about_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "ফিরে যান"))
            }
            Text(tr("About Sopifo", "Sopifo সম্পর্কে"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Logo and version (long-press copies it, for support).
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val dark = isSystemInDarkTheme()
                Surface(shape = RoundedCornerShape(16.dp), color = if (dark) Color.White else Color.Transparent) {
                    Image(
                        painterResource(R.drawable.sopifo_logo),
                        contentDescription = "Sopifo",
                        modifier = Modifier.padding(horizontal = if (dark) 12.dp else 0.dp, vertical = if (dark) 6.dp else 0.dp).height(46.dp),
                    )
                }
                Text(
                    tr("Version $version", "ভার্সন $version"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp).testTag("about_version").combinedClickable(
                        onClick = {},
                        onLongClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Sopifo version", version))
                            Toast.makeText(context, tr("Version copied", "ভার্সন কপি হয়েছে"), Toast.LENGTH_SHORT).show()
                        },
                    ),
                )
            }

            Text(
                tr(
                    "Sopifo runs your shop from your phone: orders, courier booking and labels, POS, your online shop, and printing to your Bluetooth label and receipt printers.",
                    "Sopifo দিয়ে ফোন থেকেই দোকান চালান: অর্ডার, কুরিয়ার বুকিং ও লেবেল, POS, অনলাইন শপ, আর ব্লুটুথ লেবেল ও রসিদ প্রিন্টারে প্রিন্ট।",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Updates: only through Google Play.
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val newer = latest?.takeIf { AppVersion.isNewer(it, BuildConfig.VERSION_NAME) }
                    Text(
                        when {
                            !checked -> tr("Checking for updates…", "আপডেট দেখা হচ্ছে…")
                            newer != null -> tr("New version $newer is available", "নতুন ভার্সন $newer এসেছে")
                            latest.isNullOrBlank() -> tr("Couldn't check for updates", "আপডেট দেখা যায়নি")
                            else -> tr("You have the latest version ✓", "আপনার অ্যাপ সবচেয়ে নতুন ✓")
                        },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("about_update"),
                    )
                    if (newer != null) {
                        Button(onClick = ::openPlay, modifier = Modifier.fillMaxWidth()) { Text(tr("Update on Google Play", "Google Play-তে আপডেট করুন"), fontWeight = FontWeight.Bold) }
                    }
                }
            }

            // This phone, for support.
            Section(tr("This phone", "এই ফোন")) {
                Fact(tr("Shop", "দোকান"), shop.name ?: "—")
                Fact(tr("Gets the shop's prints", "দোকানের প্রিন্ট আসে"), when (connected) { true -> tr("Yes", "হ্যাঁ"); false -> tr("No", "না"); null -> "—" })
                Fact(tr("Label printer", "লেবেল প্রিন্টার"), labelPrinter ?: "—")
                Fact(tr("Receipt printer", "রসিদ প্রিন্টার"), receiptPrinter ?: "—")
                Fact(tr("Label size", "লেবেলের সাইজ"), settings.labelSize.value.title)
                Fact("Android", "${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
            }

            // Help and logs.
            FilledTonalButton(onClick = { SupportWhatsApp.open(context, "About") }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(tr("💬 Help on WhatsApp", "💬 WhatsApp-এ সাহায্য"), fontWeight = FontWeight.Bold)
            }
            FilledTonalButton(
                onClick = {
                    sending = true
                    scope.launch {
                        runCatching { LogShare.send(context, c.logUploader.buildBundle("about")) }
                            .onFailure { AppLog.w("About", "Log share failed", it) }
                        sending = false
                    }
                },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("about_logs"),
            ) { Text(tr("📤 Send logs to support", "📤 সাপোর্টে লগ পাঠান"), fontWeight = FontWeight.Bold) }

            Section(tr("More", "আরও")) {
                LinkRow(tr("Visit sopifo.com", "sopifo.com দেখুন")) { openSite("") }
                LinkRow(tr("Privacy policy", "প্রাইভেসি পলিসি")) { openSite("privacy") }
                LinkRow(tr("Terms", "শর্তাবলি")) { openSite("terms") }
                LinkRow(tr("Refund policy", "রিফান্ড পলিসি")) { openSite("refund-policy") }
                LinkRow(tr("Rate Sopifo on Google Play", "Google Play-তে রেটিং দিন")) { openPlay() }
                // Google Play: the in-app way to ask for the account and its data to be deleted.
                LinkRow(tr("Delete account & data", "অ্যাকাউন্ট ও ডেটা মুছুন")) { openSite("data-deletion${if (bn) "" else "?lang=en"}") }
            }

            Section(tr("Open-source software", "ওপেন-সোর্স সফটওয়্যার")) {
                Text(
                    "AndroidX & Jetpack Compose, Kotlin coroutines, OkHttp, Room, WorkManager (Apache 2.0) · " +
                        "Google ML Kit barcode scanning (Google APIs Terms) · ZXing (Apache 2.0) · Firebase Cloud Messaging",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Text(
                tr("© 2026 Sopifo · Made in Bangladesh", "© ২০২৬ Sopifo · বাংলাদেশে তৈরি"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
        content()
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.4f))
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(46.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
