package com.sopifo.printagent.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.sopifo.printagent.print.StickerLayout

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

/** ESC/POS paper: a receipt roll, or label stickers on a backing with no gap sensor (StickerLayout). */
enum class PaperType { ROLL, STICKER }

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
    /** Bluetooth send speed, KB/s (see SendSpeed): label printers default to the safe speed. */
    @ColumnInfo(name = "send_speed_kbps", defaultValue = "64") val sendSpeedKbps: Int = 64,
    /** ESC/POS only: receipt roll or label stickers; the sticker_* fields apply to stickers. */
    @ColumnInfo(name = "paper_type", defaultValue = "ROLL") val paperType: PaperType = PaperType.ROLL,
    @ColumnInfo(name = "sticker_height_mm", defaultValue = "75") val stickerHeightMm: Int = 75,
    @ColumnInfo(name = "sticker_gap_mm", defaultValue = "3") val stickerGapMm: Int = 3,
    /** Extra space above the content, 0.1 mm (set in sticker setup). */
    @ColumnInfo(name = "sticker_offset_tenths", defaultValue = "0") val stickerOffsetTenths: Int = 0,
    /** Per-sticker feed correction, 0.01 mm (set in sticker setup). */
    @ColumnInfo(name = "sticker_pitch_adjust", defaultValue = "0") val stickerPitchAdjust: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
) {
    /** Sticker geometry when this printer prints label stickers on ESC/POS, else null. */
    fun stickerLayout(): StickerLayout? =
        if (protocol == PrinterProtocol.ESC_POS && paperType == PaperType.STICKER) {
            StickerLayout(stickerHeightMm, stickerGapMm, stickerOffsetTenths, stickerPitchAdjust)
        } else {
            null
        }
}

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
