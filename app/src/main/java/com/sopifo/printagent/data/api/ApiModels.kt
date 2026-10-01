package com.sopifo.printagent.data.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class RegisterRequest(
    val token: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_uuid") val deviceUuid: String? = null,
    val platform: String = "android",
    @SerialName("app_version") val appVersion: String,
    @SerialName("fcm_token") val fcmToken: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RegisterResponse(
    @SerialName("device_id") @JsonNames("id") val deviceId: String,
    @SerialName("device_jwt") @JsonNames("jwt", "access_token", "token") val deviceJwt: String,
    @SerialName("store_name") val storeName: String = "",
    @SerialName("device_name") val deviceName: String? = null,
)

@Serializable
data class PrinterStatusPayload(
    val receipt: String,
    val label: String,
)

@Serializable
data class HeartbeatRequest(
    @SerialName("battery_percent") val batteryPercent: Int?,
    @SerialName("printer_status") val printerStatus: PrinterStatusPayload,
    @SerialName("app_version") val appVersion: String,
    @SerialName("last_seen") val lastSeen: String,
)

@Serializable
data class FcmTokenRequest(@SerialName("fcm_token") val fcmToken: String)

@Serializable
data class CompleteJobRequest(@SerialName("printed_at") val printedAt: String)

@Serializable
data class FailJobRequest(val error: String)

/** A print job as returned by the backend. Everything here is untrusted and validated before use. */
@Serializable
data class PrintJobDto(
    val id: String,
    val type: String,
    val status: String = "pending",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val copies: Int = 1,
)
