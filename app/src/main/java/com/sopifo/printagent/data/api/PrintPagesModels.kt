package com.sopifo.printagent.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The app's own pages (Orders, Shipping): lists from /api/device/orders and /api/device/parcels,
 * and the results the app reports to /api/device/print-results after printing them itself.
 */

@Serializable
data class OrderCourierDto(
    val provider: String,
    @SerialName("consignment_id") val consignmentId: String? = null,
)

/** One open order: just what printing needs. */
@Serializable
data class DeviceOrderDto(
    val id: String,
    val number: String,
    val status: String,
    val customer: String? = null,
    val phone: String? = null,
    @SerialName("invoice_printed") val invoicePrinted: Boolean = false,
    @SerialName("label_printed") val labelPrinted: Boolean = false,
    val courier: OrderCourierDto? = null,
)

@Serializable
data class OrdersPageDto(
    val orders: List<DeviceOrderDto> = emptyList(),
    /** Open orders per status (PENDING, CONFIRMED, PROCESSING, PACKED, SHIPPED). */
    val counts: Map<String, Int> = emptyMap(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_count") val pageCount: Int = 1,
)

/** One courier parcel on its way: the same card as the dashboard's Couriers page. */
@Serializable
data class DeviceParcelDto(
    val id: String,
    val provider: String,
    val status: String,
    @SerialName("provider_status") val providerStatus: String? = null,
    @SerialName("consignment_id") val consignmentId: String? = null,
    @SerialName("tracking_code") val trackingCode: String? = null,
    val invoice: String? = null,
    @SerialName("cod_amount") val codAmount: Double? = null,
    @SerialName("tracking_message") val trackingMessage: String? = null,
    @SerialName("from_courier_panel") val fromCourierPanel: Boolean = false,
    @SerialName("booked_at") val bookedAt: String? = null,
    @SerialName("order_number") val orderNumber: String? = null,
    @SerialName("label_printed") val labelPrinted: Boolean = false,
    val customer: String? = null,
    val phone: String? = null,
    val address: String? = null,
) {
    /** What the seller recognises the parcel by: its order, else invoice, else consignment. */
    val title: String get() = orderNumber ?: invoice ?: consignmentId ?: id
}

@Serializable
data class ParcelCountsDto(val awaiting: Int = 0, val all: Int = 0)

@Serializable
data class NewLabelDto(val id: String, val number: String)

@Serializable
data class ParcelsPageDto(
    val parcels: List<DeviceParcelDto> = emptyList(),
    val counts: ParcelCountsDto = ParcelCountsDto(),
    /** Parcels awaiting pickup whose label isn't printed yet, across all pages. */
    @SerialName("new_labels") val newLabels: List<NewLabelDto> = emptyList(),
    val page: Int = 1,
    @SerialName("page_count") val pageCount: Int = 1,
)

@Serializable
data class SyncParcelsRequest(val courier: String)

@Serializable
data class SyncParcelsResponse(val synced: Int = 0, val failed: Int = 0, val message: String = "")

@Serializable
data class PrintResultItemDto(
    /** "order" or "parcel". */
    val kind: String,
    val id: String,
    /** "invoice" or "label". */
    val doc: String,
    /** "printed", "failed", "incomplete" or "not_printed". */
    val status: String,
    val reason: String? = null,
    val attempt: Int = 1,
)

@Serializable
data class PrintResultsRequest(val run: String, val items: List<PrintResultItemDto>)

/** A print image drawn on request, and which of the phone's printers it is for. */
class RenderedImage(val png: ByteArray, val printer: String)
