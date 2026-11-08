package com.sopifo.printagent.print

/**
 * The pause between prints on a kept-open printer link (2.1.5). A single print starts at once;
 * the next one waits until the printer has had time to print (and cut) the previous one —
 * back-to-back bulk invoices overflowed small receipt printers and printed garbage.
 * [drainMs] is the time a print of that many bytes needs (BluetoothPrinterTransport.drainDelayMs).
 */
class HeldPacer(private val clock: () -> Long, private val drainMs: (Int) -> Long) {
    private var busyUntil = 0L

    /** How long to wait before sending the next print. */
    fun waitMs(): Long = (busyUntil - clock()).coerceAtLeast(0)

    /** A print of [bytes] was just sent. */
    fun sent(bytes: Int) {
        busyUntil = clock() + drainMs(bytes)
    }
}
