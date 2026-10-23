package com.sopifo.printagent.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Single row (id = 1) describing this registered device. The JWT is NOT stored here. */
@Entity(tableName = "device_config")
data class DeviceConfigEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "store_name") val storeName: String,
    @ColumnInfo(name = "device_name") val deviceName: String,
    @ColumnInfo(name = "api_base_url") val apiBaseUrl: String,
    @ColumnInfo(name = "registered_at") val registeredAt: Long,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "fcm_token") val fcmToken: String? = null,
    @ColumnInfo(name = "fcm_token_synced") val fcmTokenSynced: Boolean = false,
    @ColumnInfo(name = "session_valid") val sessionValid: Boolean = true,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

enum class PrinterRole { RECEIPT, LABEL }

enum class PrinterProtocol { ESC_POS, TSPL }


@Entity(tableName = "printer_config")
data class PrinterConfigEntity(
    @PrimaryKey val role: PrinterRole,
    val name: String,
    @ColumnInfo(name = "mac_address") val macAddress: String,
    val protocol: PrinterProtocol,
    /**
     * Legacy, unused: layout size is a dashboard setting and comes from the job image (see
     * PrintDataBuilder). The paper actually loaded is [paperWidthMm].
     */
    @ColumnInfo(name = "width_dots") val widthDots: Int,
    @ColumnInfo(name = "label_width_mm") val labelWidthMm: Int = 50,
    @ColumnInfo(name = "label_height_mm") val labelHeightMm: Int = 30,
    @ColumnInfo(name = "label_gap_mm") val labelGapMm: Int = 2,
    /** ESC/POS only: send a paper cut after each job. */
    @ColumnInfo(name = "cut_paper") val cutPaper: Boolean = true,
    /**
     * ESC/POS only: receipt paper loaded in this printer, 58 or 80. Wider job images are scaled
     * down to fit; narrower ones are centred by the printer. Null (printers saved before this setting existed):
     * images print exactly as received.
     */
    @ColumnInfo(name = "paper_width_mm") val paperWidthMm: Int? = null,
    /** Bluetooth send speed, KB/s (see SendSpeed): the fastest by default. */
    @ColumnInfo(name = "send_speed_kbps", defaultValue = "64") val sendSpeedKbps: Int = 64,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

enum class PrintedState { PRINTING, PRINTED }

/**
 * Idempotency ledger. A row exists for every job whose bytes were (or were being) sent to a
 * printer; a job with a row is never printed again. Old rows are pruned — reprints arrive
 * with new job IDs and nothing older than 2 minutes is ever printed anyway.
 */
@Entity(tableName = "printed_jobs")
data class PrintedJobEntity(
    @PrimaryKey @ColumnInfo(name = "job_id") val jobId: String,
    val state: PrintedState,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Display-only history, trimmed to the 20 most recent jobs. */
@Entity(tableName = "recent_jobs")
data class RecentJobEntity(
    @PrimaryKey @ColumnInfo(name = "job_id") val jobId: String,
    val type: String,
    val status: String,
    val message: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

object RecentJobStatus {
    const val PRINTING = "printing"
    const val PRINTED = "printed"
    const val FAILED = "failed"
    const val SKIPPED_EXPIRED = "expired"
    const val SKIPPED_NOT_PENDING = "not_pending"
    const val INTERRUPTED = "interrupted"
}

/** A print the seller started in the app (Orders, Shipping, Quick print): one or many items. */
enum class RunState {
    RUNNING,
    DONE,
    /** Stopped by the seller, or because the printer could not be reached. */
    STOPPED,
    /** The app was closed mid-run; the seller can continue it. */
    INTERRUPTED,
}

enum class RunItemStatus {
    PENDING,
    /** Being sent right now; found like this after a crash it becomes INCOMPLETE. */
    SENDING,
    PRINTED,
    /** Its image could not be loaded or prepared: nothing was sent. */
    FAILED,
    /** The link dropped while sending: part of it may have printed. */
    INCOMPLETE,
    /** Never sent (printer unreachable, or the run was stopped). */
    NOT_PRINTED,
}

@Entity(tableName = "print_runs")
data class PrintRunEntity(
    @PrimaryKey val id: String,
    /** What the seller sees, e.g. "Order labels" or "Steadfast labels". */
    val title: String,
    /** orders · shipping · quick */
    val source: String,
    @ColumnInfo(name = "printer_role") val printerRole: PrinterRole,
    val state: RunState,
    val total: Int,
    val printed: Int = 0,
    val failed: Int = 0,
    /** Why the run stopped, when it did. */
    val message: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "print_run_items",
    primaryKeys = ["run_id", "idx"],
    indices = [Index("run_id"), Index("reported")],
)
data class PrintRunItemEntity(
    @ColumnInfo(name = "run_id") val runId: String,
    /** Position in the run: items print in this order. */
    val idx: Int,
    /** order · parcel · quick */
    val kind: String,
    /** The order / parcel id, or the image file for a quick print. */
    @ColumnInfo(name = "ref_id") val refId: String,
    /** invoice · label · quick */
    val doc: String,
    /** What the seller recognises it by, e.g. the order number. */
    val title: String,
    val status: RunItemStatus,
    val reason: String? = null,
    val attempt: Int = 1,
    /** The result reached the server (orders and parcels only). */
    val reported: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
