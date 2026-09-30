package com.sopifo.printagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder

/**
 * What a registration QR code or typed code contains. Accepted forms:
 *  - a bare token:                       `abc123...`
 *  - a URI:                              `sopifo://register?token=abc&api=https://...`
 *  - an https link with a token param:   `https://app.sopifo.com/pair?token=abc`
 *  - JSON:                               `{"token":"abc","api_url":"https://..."}`
 * `apiUrl` is only a hint; whether it may be used is decided by the caller.
 */
data class RegistrationCode(val token: String, val apiUrl: String?) {
    companion object {
        private val TOKEN = Regex("^[A-Za-z0-9._\\-~+/=]{4,4096}$")

        fun parse(raw: String?): RegistrationCode? {
            val input = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (input.startsWith("{")) return fromJson(input)
            if (input.contains("://")) return fromUri(input)
            return input.takeIf { TOKEN.matches(it) }?.let { RegistrationCode(it, null) }
        }

        private fun fromJson(input: String): RegistrationCode? = try {
            val obj = Json.parseToJsonElement(input) as? JsonObject
            val token = obj?.get("token")?.jsonPrimitive?.content
            val api = (obj?.get("api_url") ?: obj?.get("api"))?.jsonPrimitive?.content
            token?.takeIf { TOKEN.matches(it) }?.let { RegistrationCode(it, api) }
        } catch (_: Exception) {
            null
        }

        private fun fromUri(input: String): RegistrationCode? {
            val params: Map<String, String> = try {
                if (input.startsWith("http://") || input.startsWith("https://")) {
                    val url = input.toHttpUrlOrNull() ?: return null
                    url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
                } else {
                    val query = URI(input).rawQuery ?: return null
                    query.split('&').mapNotNull { part ->
                        val i = part.indexOf('=')
                        if (i <= 0) null else URLDecoder.decode(part.substring(0, i), "UTF-8") to URLDecoder.decode(part.substring(i + 1), "UTF-8")
                    }.toMap()
                }
            } catch (_: Exception) {
                return null
            }
            val token = params["token"]?.takeIf { TOKEN.matches(it) } ?: return null
            return RegistrationCode(token, params["api"] ?: params["api_url"])
        }
    }
}
