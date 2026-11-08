package com.sopifo.printagent.ui.shell

import android.webkit.CookieManager
import com.sopifo.printagent.AppContainer
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.quick.QuickLabelSize
import kotlinx.coroutines.Dispatchers
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
 */
object SizeSync {
    private const val TAG = "SizeSync"
    private val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }

    suspend fun sync(c: AppContainer) = withContext(Dispatchers.IO) {
        val settings = AppSettings.get(c.appContext)
        val url = WebSession.url(c, "api/settings")
        val cookie = CookieManager.getInstance().getCookie(WebSession.base(c)).orEmpty()
        if (!cookie.contains("-auth-token")) return@withContext
        try {
            val local = settings.labelSize.value
            if (settings.sizePendingSync && local.webId != null) {
                val body = JSONObject().put("label", JSONObject().put("label_size", local.webId)).toString()
                val req = Request.Builder().url(url).header("Cookie", cookie).put(body.toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { res -> if (res.isSuccessful) settings.sizeSynced() }
                return@withContext
            }
            val req = Request.Builder().url(url).header("Cookie", cookie).get().build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext
                val id = JSONObject(res.body?.string().orEmpty()).optJSONObject("label")?.optString("label_size")
                val size = QuickLabelSize.fromWebId(id) ?: return@withContext
                if (size != local) {
                    settings.setLabelSize(size, fromWebsite = true)
                    c.phonePrinter.forgetDrawn()
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Label size sync failed", e)
        }
    }
}
