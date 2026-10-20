package com.sopifo.printagent.print

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HardTimeoutTest {
    @Test
    fun returnsTheResultInTime() = runBlocking {
        assertEquals(42, HardTimeout.run(1_000, "test", onTimeout = {}) { 42 })
    }

    @Test
    fun passesTheCallsOwnFailureThrough() = runBlocking {
        try {
            HardTimeout.run(1_000, "test", onTimeout = {}) { throw IOException("socket closed") }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("socket closed", e.message)
        }
    }

    @Test
    fun givesUpOnTimeEvenWhenTheCallAndTheAbortNeverReturn() = runBlocking {
        val never = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val started = System.currentTimeMillis()
        try {
            HardTimeout.run(
                timeoutMs = 200,
                what = "connect",
                // Like close() on the phone that hung: the abort blocks too.
                onTimeout = { aborted.countDown(); never.await() },
            ) { never.await(); "unreachable" }
            fail("expected TIMEOUT")
        } catch (e: PrintException) {
            assertEquals(PrintFailure.TIMEOUT, e.failure)
        }
        assertTrue("returned after ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 2_000)
        assertTrue("abort was attempted", aborted.await(1, TimeUnit.SECONDS))
    }
}
