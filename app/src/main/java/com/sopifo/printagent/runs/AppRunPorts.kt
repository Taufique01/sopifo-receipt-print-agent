package com.sopifo.printagent.runs

import com.sopifo.printagent.data.api.ApiClient
import com.sopifo.printagent.data.db.PrintRunItemEntity
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.print.PrinterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The phone's Bluetooth printers, through the printer manager (its lock, retries and time limits). */
class PrinterManagerPort(private val printers: PrinterManager) : RunPrinterPort {
    override suspend fun config(role: PrinterRole): PrinterConfigEntity? = printers.config(role)

    override suspend fun <T> session(
        printer: PrinterConfigEntity,
        block: suspend (write: suspend (ByteArray) -> Unit) -> T,
    ): T = printers.session(printer) { connection -> block { bytes -> connection.write(bytes) } }
}

/**
 * Order and parcel images come from the server, drawn on request with the dashboard's own
 * layout; quick prints are image files the app made itself. Either way the printer manager
 * turns them into bytes for this printer.
 */
class ApiRunContent(
    private val api: ApiClient,
    private val printers: PrinterManager,
) : RunContent {
    override suspend fun prepare(item: PrintRunItemEntity, printer: PrinterConfigEntity): ByteArray {
        val png = when (item.kind) {
            KIND_ORDER -> api.renderOrder(item.refId, item.doc).png
            KIND_PARCEL -> api.renderParcel(item.refId).png
            KIND_QUICK -> withContext(Dispatchers.IO) {
                val file = File(item.refId)
                if (!file.exists()) throw IllegalStateException("This quick print is no longer on the phone")
                file.readBytes()
            }
            else -> throw IllegalStateException("Unknown print item")
        }
        return printers.buildFromPng(png, printer, 1).bytes
    }

    companion object {
        const val KIND_ORDER = "order"
        const val KIND_PARCEL = "parcel"
        const val KIND_QUICK = "quick"
    }
}
