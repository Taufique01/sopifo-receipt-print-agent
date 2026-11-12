package com.sopifo.printagent.ui.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.BuildConfig
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.ui.theme.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What the website says (GET /api/health, set in Admin → Print devices → App versions). */
data class AppUpdateInfo(val latest: String?, val required: String?)

/**
 * Update rules. Required: an older app must update to continue (block screen). Latest: an older
 * app shows a thin "Update available" bar; ✕ hides it for 3 days for that version. No answer
 * (offline) never blocks.
 */
object AppUpdateRules {
    const val SNOOZE_MS = 3L * 24 * 60 * 60 * 1000
    /** Not blocked: check again at most this often when the app comes back. */
    const val RECHECK_MS = 10L * 60 * 1000

    fun mustUpdate(info: AppUpdateInfo?, current: String): Boolean = AppVersion.isNewer(info?.required, current)

    fun showBar(info: AppUpdateInfo?, current: String, dismissedVersion: String?, dismissedAt: Long, now: Long): Boolean {
        val latest = info?.latest ?: return false
        if (!AppVersion.isNewer(latest, current)) return false
        return !(dismissedVersion == latest && now - dismissedAt in 0 until SNOOZE_MS)
    }
}

/** The app's update check, shared by the block screen and the bar. */
object AppUpdates {
    private const val TAG = "AppUpdates"
    private const val PREFS = "app_updates"
    private val http by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build() }
    private val _info = MutableStateFlow<AppUpdateInfo?>(null)
    val info: StateFlow<AppUpdateInfo?> = _info.asStateFlow()
    @Volatile private var checkedAt = 0L

    /** On start and each time the app comes back; while blocked, every time (so lowering it frees the app). */
    suspend fun check(context: Context) {
        val now = System.currentTimeMillis()
        val blocked = AppUpdateRules.mustUpdate(_info.value, BuildConfig.VERSION_NAME)
        if (!blocked && now - checkedAt < AppUpdateRules.RECHECK_MS) return
        checkedAt = now
        val c = (context.applicationContext as SopifoApp).container
        val got = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url("${WebSession.base(c)}/api/health").build()).execute().use { res ->
                    val json = JSONObject(res.body?.string().orEmpty())
                    AppUpdateInfo(
                        latest = json.optJSONObject("apps")?.optString("android")?.takeIf { it.isNotBlank() },
                        required = json.optJSONObject("apps_required")?.optString("android")?.takeIf { it.isNotBlank() },
                    )
                }
            }.onFailure { AppLog.w(TAG, "Update check failed", it) }.getOrNull()
        } ?: return
        if (got != _info.value) AppLog.i(TAG, "Versions", "latest" to got.latest, "required" to got.required, "this" to BuildConfig.VERSION_NAME)
        _info.value = got
    }

    fun dismissed(context: Context): Pair<String?, Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { it.getString("dismissed_version", null) to it.getLong("dismissed_at", 0L) }

    fun dismiss(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("dismissed_version", version).putLong("dismissed_at", System.currentTimeMillis()).apply()
    }

    /** Google Play only: no APK download inside the app. */
    fun openPlay(context: Context) {
        val id = context.packageName
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }
}

@Composable
private fun rememberTr(): (String, String) -> String {
    val context = LocalContext.current
    val lang by AppSettings.get(context).lang.collectAsStateWithLifecycle()
    return { en, bn -> if (lang == "en") en else bn }
}

/**
 * Wraps the whole app: below the required version only the "Update Sopifo to continue" screen
 * shows. Printing in the background (the print service) keeps working.
 */
@Composable
fun UpdateGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LifecycleStartEffect(Unit) {
        val job = scope.launch { AppUpdates.check(context) }
        onStopOrDispose { job.cancel() }
    }
    val info by AppUpdates.info.collectAsStateWithLifecycle()
    if (AppUpdateRules.mustUpdate(info, BuildConfig.VERSION_NAME)) UpdateRequiredScreen(info?.required.orEmpty()) else content()
}

@Composable
private fun UpdateRequiredScreen(required: String) {
    val context = LocalContext.current
    val tr = rememberTr()
    Surface(Modifier.fillMaxSize().testTag("update_required")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(AppIcons.Station, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                tr("Update Sopifo to continue", "চালিয়ে যেতে Sopifo আপডেট করুন"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                tr(
                    "This version (${BuildConfig.VERSION_NAME}) is too old. Update from Google Play — your login and printers stay as they are.",
                    "এই ভার্সন (${BuildConfig.VERSION_NAME}) অনেক পুরোনো। Google Play থেকে আপডেট করুন — লগইন আর প্রিন্টার যেমন আছে তেমনই থাকবে।",
                ),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                tr("Needed: $required or newer", "দরকার: $required বা নতুন"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { AppUpdates.openPlay(context) },
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("update_required_play"),
            ) { Text(tr("Update on Google Play", "Google Play-তে আপডেট করুন"), fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = { SupportWhatsApp.open(context, "Update required") }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(tr("💬 Help on WhatsApp", "💬 WhatsApp-এ সাহায্য"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The thin "New version … Update ✕" line under the top bar (not on Add order / POS). */
@Composable
fun UpdateBar() {
    val context = LocalContext.current
    val tr = rememberTr()
    val info by AppUpdates.info.collectAsStateWithLifecycle()
    var dismissedVersion by remember { mutableStateOf(AppUpdates.dismissed(context).first) }
    var dismissedAt by remember { mutableLongStateOf(AppUpdates.dismissed(context).second) }
    if (!AppUpdateRules.showBar(info, BuildConfig.VERSION_NAME, dismissedVersion, dismissedAt, System.currentTimeMillis())) return
    val latest = info?.latest.orEmpty()
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().testTag("update_bar")) {
        Row(Modifier.padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Station, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                tr("New version $latest", "নতুন ভার্সন $latest"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = { AppUpdates.openPlay(context) }, modifier = Modifier.testTag("update_bar_play")) {
                Text(tr("Update", "আপডেট"), fontWeight = FontWeight.Bold)
            }
            IconButton(
                onClick = {
                    AppUpdates.dismiss(context, latest)
                    dismissedVersion = latest
                    dismissedAt = System.currentTimeMillis()
                },
                modifier = Modifier.size(40.dp).testTag("update_bar_close"),
            ) { Icon(Icons.Filled.Close, contentDescription = tr("Later", "পরে")) }
        }
    }
}
