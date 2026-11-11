package com.sopifo.printagent.bt

import com.sopifo.printagent.data.db.PrinterRole

/**
 * Two printers with the same name (2.1.15): the app always tells them apart by Bluetooth address;
 * this shows it to the seller too. "Find printer" rows and the saved printers show the end of the
 * address ("…A3:F1"), and the found printer that is already saved says what it's used for.
 */
object PrinterTags {
    /** The last two parts of the address, e.g. "…A3:F1" ("" when there's none). */
    fun addressTail(address: String): String =
        address.trim().uppercase().takeIf { it.length >= 5 }?.let { "…" + it.takeLast(5) } ?: ""

    /** The jobs (label / receipt) a found printer already does: matched by address, never by name. */
    fun rolesOf(address: String, saved: List<Pair<PrinterRole, String>>): List<PrinterRole> =
        listOf(PrinterRole.LABEL, PrinterRole.RECEIPT).filter { role ->
            saved.any { it.first == role && it.second.equals(address, ignoreCase = true) }
        }
}
