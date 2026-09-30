package com.sopifo.printagent.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
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
    /** Printable width in dots (e.g. 384 for 58 mm, 576 for 80 mm at 203 dpi). */
    @ColumnInfo(name = "width_dots") val widthDots: Int,
    /** Label stock size, used by TSPL printers. */
    @ColumnInfo(name = "label_width_mm") val labelWidthMm: Int = 50,
    @ColumnInfo(name = "label_height_mm") val labelHeightMm: Int = 30,
    @ColumnInfo(name = "label_gap_mm") val labelGapMm: Int = 2,
    /** ESC/POS only: send a paper cut after each job. */
    @ColumnInfo(name = "cut_paper") val cutPaper: Boolean = true,
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
