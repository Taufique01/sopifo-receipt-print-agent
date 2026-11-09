package com.sopifo.printagent.printservice

import android.webkit.CookieManager
import com.sopifo.printagent.AppContainer
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.ui.shell.WebSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The shop's plan for prints from other apps (website /api/app/service-print): Free has 30 a
 * month, paid plans unlimited. Asked once per print, logged in. Never blocks printing because of
 * the network: no answer → the print goes ahead (counted locally before login as before).
 */
object PlanQuota {
    sealed interface Answer {
        data object Allowed : Answer
        data class Refused(val message: String) : Answer
    }

    /** The "error" text of the website's JSON answer (plain regex: works in JVM unit tests too). */
    private val ERROR = Regex(""""error"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    private val http by lazy { OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build() }

    /** Pure: what the website's answer means (for tests). */
    fun parse(code: Int, body: String?): Answer =
        if (code == 402) {
            Answer.Refused(ERROR.find(body.orEmpty())?.groupValues?.get(1)?.replace("\\\"", "\"").orEmpty())
        } else {
            Answer.Allowed
        }

    suspend fun take(c: AppContainer): Answer = withContext(Dispatchers.IO) {
        try {
            val base = WebSession.base(c)
            val cookie = CookieManager.getInstance().getCookie(base).orEmpty()
            if (!cookie.contains("-auth-token")) return@withContext Answer.Allowed
            val req = Request.Builder()
                .url("$base/api/app/service-print")
                .header("Cookie", cookie)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { res -> parse(res.code, res.body?.string()) }
        } catch (e: Exception) {
            AppLog.w("PlanQuota", "Plan check failed; printing anyway", e)
            Answer.Allowed
        }
    }
}
