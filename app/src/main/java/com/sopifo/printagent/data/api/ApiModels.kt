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

/** "Connect to my shop": ask the server for a code to show and approve in the browser. */
@Serializable
data class ConnectStartRequest(
    @SerialName("device_uuid") val deviceUuid: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("app_version") val appVersion: String,
)

@Serializable
data class ConnectStartResponse(
    @SerialName("request_id") val requestId: String,
    /** Shown on the Connect screen, e.g. "cow milk" (two words; first 1.4.0 codes looked like K7P4-QXM2). */
    val code: String,
    /** Only this phone knows it: claiming the credentials needs it. */
    @SerialName("poll_secret") val pollSecret: String,
    /** The page where the seller signs in and approves the code. */
    @SerialName("connect_url") val connectUrl: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class ConnectClaimRequest(
    @SerialName("request_id") val requestId: String,
    @SerialName("poll_secret") val pollSecret: String,
    @SerialName("fcm_token") val fcmToken: String? = null,
)

/** The server's answer to a claim: not approved yet, gone (ask for a new code), or connected. */
sealed interface ConnectClaim {
    data object Pending : ConnectClaim
    data object Expired : ConnectClaim
    data class Connected(val response: RegisterResponse) : ConnectClaim
}

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
    /** Bulk print jobs: printable until this (server time) instead of the 2-minute rule. */
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("batch_index") val batchIndex: Int? = null,
)

/**
 * A bulk print as the phone sees it: the jobs waiting to print, in order. Each fetch makes the
 * backend render a few more and keeps the batch alive; `renderedAll` with no jobs means done.
 */
@Serializable
data class PrintBatchDto(
    val id: String,
    /** active | cancelled | expired */
    val status: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    val total: Int = 0,
    @SerialName("rendered_all") val renderedAll: Boolean = false,
    val jobs: List<PrintJobDto> = emptyList(),
)
