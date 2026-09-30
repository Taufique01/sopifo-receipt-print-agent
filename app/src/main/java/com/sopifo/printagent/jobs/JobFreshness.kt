package com.sopifo.printagent.jobs

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The "nothing older than 2 minutes is printed" rule, evaluated on server time. */
object JobFreshness {
    const val MAX_AGE_SECONDS = 120L
    /** Tolerate timestamps slightly in the future (clock differences between backend nodes). */
    private const val FUTURE_TOLERANCE_SECONDS = 300L

    fun parseTimestamp(value: String?): Instant? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { n -> return if (n > 100_000_000_000L) Instant.ofEpochMilli(n) else Instant.ofEpochSecond(n) }
        return runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw.replace(' ', 'T')).toInstant() }.getOrNull()
            // Timestamps without a zone are taken as UTC.
            ?: runCatching { LocalDateTime.parse(raw.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    fun ageSeconds(createdAt: Instant, now: Instant): Long = now.epochSecond - createdAt.epochSecond

    fun isFresh(createdAt: Instant, now: Instant): Boolean {
        val age = ageSeconds(createdAt, now)
        return age <= MAX_AGE_SECONDS && age >= -FUTURE_TOLERANCE_SECONDS
    }
}
