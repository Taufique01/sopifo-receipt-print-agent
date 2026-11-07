package com.sopifo.printagent.ui.shell

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import androidx.core.content.edit
import com.sopifo.printagent.AppContainer
import com.sopifo.printagent.BuildConfig
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.ConnectPoll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * The website session inside the app (app 2.0). The dashboard runs in a WebView marked with the
 * cookie `sopifo_app` (app mode: the site hides its own menus and every pay button). Logging in
 * there connects this phone as the shop's printer: the app asks for a connect request, proves it
 * holds the request's secret with the seller's session (/api/app/link-device), then claims it.
 */
object WebSession {
    private const val TAG = "WebSession"
    private const val PREFS = "web_session"
    private const val KEY_NONCE = "oauth_nonce"
    private val http by lazy { OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build() }

    /** The website the app shows (the paired server, else the built-in one). */
    fun base(c: AppContainer): String = (c.device.baseUrl ?: BuildConfig.API_BASE_URL).trimEnd('/')

    fun url(c: AppContainer, path: String): String = "${base(c)}/${path.trimStart('/')}"

    /** App mode + language (+ this phone's print device) cookies, before any page loads. */
    fun prepareCookies(c: AppContainer, lang: String, deviceId: String?) {
        val base = base(c)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val secure = if (base.startsWith("https://")) "; Secure" else ""
        cm.setCookie(base, "sopifo_app=android-${BuildConfig.VERSION_NAME.substringBefore('-')}; Path=/; SameSite=Lax$secure")
        cm.setCookie(base, "hb_locale=$lang; Path=/; Max-Age=31536000; SameSite=Lax$secure")
        if (deviceId != null) cm.setCookie(base, "sopifo_device=$deviceId; Path=/; SameSite=Lax$secure")
        cm.flush()
    }

    /** Signed in on the website (Supabase's session cookie is there). */
    fun isLoggedIn(c: AppContainer): Boolean =
        CookieManager.getInstance().getCookie(base(c)).orEmpty().contains("-auth-token")

    fun logOut(c: AppContainer) {
        val cm = CookieManager.getInstance()
        val base = base(c)
        cm.getCookie(base).orEmpty().split(';').map { it.substringBefore('=').trim() }
            .filter { it.contains("auth-token") }
            .forEach { name ->
                cm.setCookie(base, "$name=; Path=/; Max-Age=0")
                cm.setCookie(base, "$name=; Path=/; Domain=.sopifo.com; Max-Age=0")
            }
        cm.flush()
    }

    sealed interface LinkResult {
        data object Linked : LinkResult
        data class Failed(val message: String) : LinkResult
    }

    /** Connects this phone to the signed-in seller's shop (no code, no confirm page). */
    suspend fun linkDevice(c: AppContainer): LinkResult = withContext(Dispatchers.IO) {
        try {
            val code = c.device.newConnectCode()
            val body = JSONObject().put("request_id", code.requestId).put("poll_secret", code.secret).toString()
            val request = Request.Builder()
                .url("${base(c)}/api/app/link-device")
                .header("Cookie", CookieManager.getInstance().getCookie(base(c)).orEmpty())
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    val error = runCatching { JSONObject(res.body?.string().orEmpty()).optString("error") }.getOrNull()
                    AppLog.w(TAG, "link-device refused", null, "code" to res.code)
                    return@withContext LinkResult.Failed(error?.takeIf { it.isNotBlank() } ?: "HTTP ${res.code}")
                }
            }
            repeat(6) { attempt ->
                when (c.device.claimConnect(code)) {
                    ConnectPoll.CONNECTED -> {
                        c.startAgent("app_login")
                        AppLog.i(TAG, "Phone linked by login")
                        return@withContext LinkResult.Linked
                    }
                    ConnectPoll.EXPIRED -> return@withContext LinkResult.Failed("expired")
                    ConnectPoll.WAITING -> delay(1_000L * (attempt + 1))
                }
            }
            LinkResult.Failed("timeout")
        } catch (e: Exception) {
            AppLog.w(TAG, "link-device failed", e)
            LinkResult.Failed(e.message ?: "error")
        }
    }

    /** Facebook in a Chrome tab: the URL to open; the nonce stays on the phone for the return. */
    fun facebookStartUrl(context: Context, c: AppContainer): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY_NONCE, nonce) }
        val hash = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return url(c, "app/oauth/start?h=$hash")
    }

    /** sopifo://auth?code=… came back: the page that turns it into this WebView's session. */
    fun facebookFinishUrl(context: Context, c: AppContainer, code: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nonce = prefs.getString(KEY_NONCE, null) ?: return null
        prefs.edit(commit = true) { remove(KEY_NONCE) }
        return url(c, "app/oauth/finish?t=${java.net.URLEncoder.encode(code, "UTF-8")}&n=${java.net.URLEncoder.encode(nonce, "UTF-8")}")
    }
}
