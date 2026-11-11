package com.sopifo.printagent.ui.shell

import android.webkit.CookieManager
import com.sopifo.printagent.AppContainer
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.quick.QuickLabelSize
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One label size for the app and the website (2.1.1). Chosen in the app (even before login) →
 * sent to Cloud Print's label size at the next chance; otherwise the website's size is read, so
 * order labels, smart shrink and Print → Sopifo all print the same size. Uses the seller's
 * session (the dashboard's cookies); needs a shop.
 * 2.1.15: a size picked in the app is sent at once (not only at the setup's Next or the next
 * start), and the website's size is checked again when Orders / Couriers open; when it changed,
 * labels drawn ahead (PhonePrinter) are dropped, so nothing prints at the old size.
 */
object SizeSync {
    private const val TAG = "SizeSync"
    private const val RECHECK_MS = 15_000L
    private val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    private val lock = Mutex()
    @Volatile private var lastAt = 0L
    /** The website's label size at the last read or send (null: not read since the app started). */
    @Volatile private var lastSeenWebId: String? = null

    /** Pages whose labels the website draws at its size: check the size when one opens. */
    fun dueOnPage(path: String, previousPath: String?, pending: Boolean, sinceLastMs: Long): Boolean {
        if (!path.startsWith("/dashboard/orders") && !path.startsWith("/dashboard/couriers")) return false
        // Coming from Cloud Print settings: the size may have just changed there.
        return pending || previousPath?.startsWith("/dashboard/print") == true || sinceLastMs >= RECHECK_MS
    }

    /** The website's size differs from the one labels were drawn at (first read: the app's own size). */
    fun websiteChanged(lastSeen: String?, now: String?, appWebId: String? = null): Boolean =
        now != null && now != (lastSeen ?: appWebId)

    /** A page opened in the app (see [dueOnPage]). */
    fun checkOnPage(c: AppContainer, path: String, previousPath: String?) {
        val since = if (lastAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastAt
        if (!dueOnPage(path, previousPath, AppSettings.get(c.appContext).sizePendingSync, since)) return
        c.scope.launch { sync(c) }
    }

    suspend fun sync(c: AppContainer) = withContext(Dispatchers.IO) { lock.withLock { syncLocked(c) } }

    private fun syncLocked(c: AppContainer) {
        lastAt = SystemClock.elapsedRealtime()
        val settings = AppSettings.get(c.appContext)
        val url = WebSession.url(c, "api/settings")
        val cookie = CookieManager.getInstance().getCookie(WebSession.base(c)).orEmpty()
        if (!cookie.contains("-auth-token")) return
        try {
            val local = settings.labelSize.value
            if (settings.sizePendingSync && local.webId != null) {
                val body = JSONObject().put("label", JSONObject().put("label_size", local.webId)).toString()
                val req = Request.Builder().url(url).header("Cookie", cookie).put(body.toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        settings.sizeSynced()
                        lastSeenWebId = local.webId
                        // Labels drawn ahead while this was on its way are the old size.
                        c.phonePrinter.forgetDrawn()
                    }
                }
                return
            }
            val req = Request.Builder().url(url).header("Cookie", cookie).get().build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return
                val id = JSONObject(res.body?.string().orEmpty()).optJSONObject("label")?.optString("label_size")?.takeIf { it.isNotBlank() }
                if (websiteChanged(lastSeenWebId, id, local.webId)) c.phonePrinter.forgetDrawn()
                if (id != null) lastSeenWebId = id
                val size = QuickLabelSize.fromWebId(id) ?: return
                if (size != local) settings.setLabelSize(size, fromWebsite = true)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Label size sync failed", e)
        }
    }
}
