package com.sopifo.printagent.print

import com.sopifo.printagent.data.db.PrinterRole

/** Job types the backend may send. Android never renders content; every job is a backend-made PNG. */
enum class JobType(val wireName: String, val printer: PrinterRole) {
    RECEIPT("receipt", PrinterRole.RECEIPT),
    // Free-form notes; printed on the receipt (roll) printer.
    SCRATCHPAD("scratchpad", PrinterRole.RECEIPT),
    LABEL("label", PrinterRole.LABEL),
    BARCODE_LABEL("barcode_label", PrinterRole.LABEL),
    QR_LABEL("qr_label", PrinterRole.LABEL);

    companion object {
        fun fromWire(value: String?): JobType? = entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}
