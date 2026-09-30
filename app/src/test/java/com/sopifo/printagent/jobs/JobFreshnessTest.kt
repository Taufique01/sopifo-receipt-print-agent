package com.sopifo.printagent.jobs

import com.sopifo.printagent.data.api.ServerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class JobFreshnessTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")

    @Test fun acceptsJobsUpToTwoMinutesOld() {
        assertTrue(JobFreshness.isFresh(now.minusSeconds(0), now))
        assertTrue(JobFreshness.isFresh(now.minusSeconds(119), now))
        assertTrue(JobFreshness.isFresh(now.minusSeconds(120), now))
    }

    @Test fun rejectsAnythingOlderThanTwoMinutes() {
        assertFalse(JobFreshness.isFresh(now.minusSeconds(121), now))
        assertFalse(JobFreshness.isFresh(now.minusSeconds(3600), now))
    }

    @Test fun toleratesSmallFutureSkewButNotAbsurdTimestamps() {
        assertTrue(JobFreshness.isFresh(now.plusSeconds(30), now))
        assertFalse(JobFreshness.isFresh(now.plusSeconds(3600), now))
    }

    @Test fun parsesCommonBackendTimestampFormats() {
        val expected = Instant.parse("2026-09-30T11:59:30Z")
        assertEquals(expected, JobFreshness.parseTimestamp("2026-09-30T11:59:30Z"))
        assertEquals(expected, JobFreshness.parseTimestamp("2026-09-30T11:59:30.000000Z"))
        assertEquals(expected, JobFreshness.parseTimestamp("2026-09-30T17:59:30+06:00"))
        assertEquals(expected, JobFreshness.parseTimestamp("2026-09-30 11:59:30"))
        assertEquals(expected, JobFreshness.parseTimestamp(expected.epochSecond.toString()))
        assertEquals(expected, JobFreshness.parseTimestamp(expected.toEpochMilli().toString()))
    }

    @Test fun rejectsGarbageTimestamps() {
        assertNull(JobFreshness.parseTimestamp(null))
        assertNull(JobFreshness.parseTimestamp(""))
        assertNull(JobFreshness.parseTimestamp("yesterday"))
    }
}

class ServerClockTest {
    @Test fun usesServerDateHeaderToCorrectDeviceClock() {
        // Device clock is one hour fast.
        val realNow = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli()
        val clock = ServerClock(deviceNow = { realNow + 3_600_000 })
        clock.onServerDate("Wed, 30 Sep 2026 12:00:00 GMT")
        assertTrue(clock.hasSample)
        assertEquals(Instant.ofEpochMilli(realNow), clock.now())
        // A job created 30 s ago on the server is fresh even though the device clock says 1 h.
        assertTrue(JobFreshness.isFresh(Instant.ofEpochMilli(realNow - 30_000), clock.now()))
    }

    @Test fun ignoresMalformedDateHeaders() {
        val clock = ServerClock(deviceNow = { 1_000L })
        clock.onServerDate("not a date")
        clock.onServerDate(null)
        assertFalse(clock.hasSample)
        assertNotNull(clock.now())
        assertEquals(0L, clock.offsetMillis())
    }
}
