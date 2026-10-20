package com.sopifo.printagent.print

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A hard time limit for a blocking call that may never return. Bluetooth connect() and write()
 * ignore interrupts; closing the socket is meant to unblock them, but on some phones (seen on a
 * HONOR X6c) neither the call nor close() ever returns. Waiting for the call then held the
 * printer's lock forever, so every later print queued behind one stuck print.
 *
 * The call runs on its own daemon thread. On time, its result is returned as usual; past the
 * limit, [onTimeout] runs on yet another thread (it may block too) and the caller gets
 * TIMEOUT straight away. A stuck call is left to finish or fail on its own thread.
 */
object HardTimeout {
    suspend fun <T : Any> run(
        timeoutMs: Long,
        what: String,
        onTimeout: () -> Unit,
        block: () -> T,
    ): T {
        val result = CompletableDeferred<T>()
        daemon("bt-$what") {
            try {
                result.complete(block())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        val value = withTimeoutOrNull(timeoutMs) { result.await() }
        if (value != null) return value
        daemon("bt-$what-abort") { runCatching(onTimeout) }
        throw PrintException(PrintFailure.TIMEOUT, "Printer $what timed out")
    }

    /** Runs [block] on a daemon thread nobody waits for. */
    fun daemon(name: String, block: () -> Unit) {
        Thread(block, name).apply { isDaemon = true }.start()
    }
}
