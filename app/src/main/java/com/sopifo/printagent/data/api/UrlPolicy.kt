package com.sopifo.printagent.data.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Transport security rules. Production traffic is HTTPS only; debug builds may additionally
 * use plain HTTP to localhost, which is how on-device tests reach a mock backend via `adb reverse`.
 */
class UrlPolicy(private val allowLocalhostHttp: Boolean) {

    fun parseAllowed(url: String?): HttpUrl? {
        val parsed = url?.trim()?.toHttpUrlOrNull() ?: return null
        return if (isAllowed(parsed)) parsed else null
    }

    fun isAllowed(url: HttpUrl): Boolean = when (url.scheme) {
        "https" -> true
        "http" -> allowLocalhostHttp && url.host in LOCAL_HOSTS
        else -> false
    }

    companion object {
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")
        private val JOB_ID = Regex("^[A-Za-z0-9_\\-:.]{1,128}$")

        /** Job IDs end up in URL paths; only allow a conservative character set. */
        fun isValidJobId(id: String?): Boolean = id != null && JOB_ID.matches(id) && id != "." && id != ".."
    }
}
