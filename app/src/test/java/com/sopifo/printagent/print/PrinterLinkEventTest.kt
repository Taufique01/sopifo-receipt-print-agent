package com.sopifo.printagent.print

import com.sopifo.printagent.data.db.PrinterConfigDao
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The link drop that follows our own close must not show the printer as disconnected. */
class PrinterLinkEventTest {
    private val printer = PrinterConfigEntity(
        role = PrinterRole.RECEIPT,
        name = "Printer",
        macAddress = "AA:BB:CC:DD:EE:FF",
        protocol = PrinterProtocol.entries.first(),
        widthDots = 384,
    )
    private var now = 100_000L

    private val dao = object : PrinterConfigDao {
        override fun observeAll(): Flow<List<PrinterConfigEntity>> = flowOf(listOf(printer))
        override suspend fun getAll() = listOf(printer)
        override suspend fun get(role: PrinterRole) = printer.takeIf { it.role == role }
        override suspend fun upsert(config: PrinterConfigEntity) = Unit
        override suspend fun delete(role: PrinterRole) = Unit
    }
    private val transport = object : PrinterTransport {
        override suspend fun open(printer: PrinterConfigEntity) = object : PrinterConnection {
            override suspend fun write(data: ByteArray) = Unit
            override fun close() = Unit
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

    @Test fun linkDropRightAfterOurOwnCloseKeepsConnected() = runTest {
        assertEquals(PrinterState.CONNECTED, manager.probe(PrinterRole.RECEIPT))
        now += 3_000
        manager.onLinkEvent("aa:bb:cc:dd:ee:ff", connected = false)
        assertEquals(PrinterState.CONNECTED, manager.status.value[PrinterRole.RECEIPT])
    }

    @Test fun linkDropLongAfterOurCloseIsTheRealThing() = runTest {
        manager.probe(PrinterRole.RECEIPT)
        now += 60_000
        manager.onLinkEvent(printer.macAddress, connected = false)
        assertEquals(PrinterState.DISCONNECTED, manager.status.value[PrinterRole.RECEIPT])
    }
}
