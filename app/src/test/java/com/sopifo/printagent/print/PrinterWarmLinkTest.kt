package com.sopifo.printagent.print

import com.sopifo.printagent.data.db.PrinterConfigDao
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fast printing (2.0.1): the kept-open printer link. */
class PrinterWarmLinkTest {
    private val printer = PrinterConfigEntity(
        role = PrinterRole.RECEIPT,
        name = "Printer",
        macAddress = "AA:BB:CC:DD:EE:01",
        protocol = PrinterProtocol.ESC_POS,
        widthDots = 384,
    )
    private var now = 100_000L
    private var opens = 0
    private var closes = 0
    private val written = mutableListOf<String>()
    /** Next write fails like this (once). */
    private var failNext: PrintFailure? = null

    private val dao = object : PrinterConfigDao {
        override fun observeAll(): Flow<List<PrinterConfigEntity>> = flowOf(listOf(printer))
        override suspend fun getAll() = listOf(printer)
        override suspend fun get(role: PrinterRole) = printer.takeIf { it.role == role }
        override suspend fun upsert(config: PrinterConfigEntity) = Unit
        override suspend fun delete(role: PrinterRole) = Unit
    }
    private val transport = object : PrinterTransport {
        override suspend fun open(printer: PrinterConfigEntity): PrinterConnection {
            opens++
            return object : PrinterConnection {
                override suspend fun write(data: ByteArray) {
                    failNext?.let {
                        failNext = null
                        throw PrintException(it, "boom")
                    }
                    written += String(data)
                }
                override fun close() { closes++ }
            }
        }
    }
    private val manager = PrinterManager(
        dao,
        transport,
        PrintDataBuilder(),
        object : PrinterManager.Environment {
            override fun isBluetoothOn() = true
            override fun hasPermission() = true
        },
        clock = { now },
    )

    @Test fun twoFastPrintsShareOneConnection() = runTest {
        manager.printFast(printer, "a".toByteArray())
        manager.printFast(printer, "b".toByteArray())
        assertEquals(1, opens)
        assertEquals(0, closes)
        assertEquals(listOf("a", "b"), written)
    }

    @Test fun otherPrintsReuseTheKeptOpenLink() = runTest {
        manager.warmUp(PrinterRole.RECEIPT)
        manager.print(printer, "job".toByteArray())
        assertEquals(1, opens)
        assertEquals(0, closes)
        assertEquals(PrinterState.CONNECTED, manager.probe(PrinterRole.RECEIPT))
        assertEquals(1, opens)
    }

    @Test fun staleLinkWithNothingSentReconnectsOnce() = runTest {
        manager.printFast(printer, "a".toByteArray())
        failNext = PrintFailure.CONNECT_FAILED
        manager.printFast(printer, "b".toByteArray())
        assertEquals(2, opens)
        assertEquals(listOf("a", "b"), written)
    }

    // Print → Sopifo (Chrome, other apps) and jobs go through session(): a link that died while
    // the app was in the background used to fail with "I/O error" / "not connected".
    @Test fun sessionOnAStaleLinkReconnectsOnce() = runTest {
        manager.warmUp(PrinterRole.RECEIPT)
        failNext = PrintFailure.CONNECT_FAILED
        manager.session(printer) { connection ->
            connection.write("p1".toByteArray())
            connection.write("p2".toByteArray())
        }
        assertEquals(2, opens)
        assertEquals(listOf("p1", "p2"), written)
        // The new link is kept open for the next print.
        manager.print(printer, "job".toByteArray())
        assertEquals(2, opens)
    }

    @Test fun sessionWriteFailureAfterBytesWentOutIsNotResent() = runTest {
        manager.warmUp(PrinterRole.RECEIPT)
        failNext = PrintFailure.WRITE_FAILED
        try {
            manager.print(printer, "job".toByteArray())
            fail("expected a print failure")
        } catch (e: PrintException) {
            assertEquals(PrintFailure.WRITE_FAILED, e.failure)
        }
        assertEquals(emptyList<String>(), written)
        assertEquals(1, opens)
    }

    @Test fun writeFailureAfterBytesWentOutIsNotResent() = runTest {
        manager.printFast(printer, "a".toByteArray())
        failNext = PrintFailure.WRITE_FAILED
        try {
            manager.printFast(printer, "b".toByteArray())
            fail("expected a print failure")
        } catch (e: PrintException) {
            assertEquals(PrintFailure.WRITE_FAILED, e.failure)
        }
        assertEquals(listOf("a"), written)
        assertTrue(closes >= 1)
        // The next print opens a fresh link.
        manager.printFast(printer, "c".toByteArray())
        assertEquals(2, opens)
    }

    @Test fun coolDownClosesTheLink() = runTest {
        manager.printFast(printer, "a".toByteArray())
        manager.coolDown()
        assertEquals(1, closes)
        manager.printFast(printer, "b".toByteArray())
        assertEquals(2, opens)
    }

    // Battery: the 5-minute heartbeat only reconnects to a printer not checked for 30 minutes.
    @Test fun heartbeatProbesAPrinterAtMostEvery30Minutes() = runTest {
        val every = 30 * 60_000L
        manager.probeStale(every)
        assertEquals(1, opens)
        now += 5 * 60_000L
        manager.probeStale(every)
        assertEquals(1, opens)
        now += 26 * 60_000L
        manager.probeStale(every)
        assertEquals(2, opens)
    }

    @Test fun aPrintCountsAsAFreshCheck() = runTest {
        manager.print(printer, "job".toByteArray())
        assertEquals(1, opens)
        now += 10 * 60_000L
        manager.probeStale(30 * 60_000L)
        assertEquals(1, opens)
    }
}
