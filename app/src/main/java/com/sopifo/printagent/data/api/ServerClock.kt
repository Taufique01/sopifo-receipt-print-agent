package com.sopifo.printagent.data.api

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Tracks the offset between the device clock and the backend clock (from the HTTP `Date`
 * header), so the 120-second freshness rule is judged on server time. A phone with a wrong
 * clock must neither drop fresh jobs nor print stale ones.
 */
class ServerClock(private val deviceNow: () -> Long = System::currentTimeMillis) {
    @Volatile private var offsetMs: Long = 0
    @Volatile var hasSample: Boolean = false
        private set

    fun onServerDate(httpDate: String?) {
        if (httpDate.isNullOrBlank()) return
        try {
            val server = ZonedDateTime.parse(httpDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            offsetMs = server - deviceNow()
            hasSample = true
        } catch (_: Exception) {
            // Ignore malformed headers.
        }
    }

    fun now(): Instant = Instant.ofEpochMilli(deviceNow() + offsetMs)

    fun offsetMillis(): Long = offsetMs
}
