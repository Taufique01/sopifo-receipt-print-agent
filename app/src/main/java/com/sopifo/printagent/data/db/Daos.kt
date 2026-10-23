package com.sopifo.printagent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceConfigDao {
    @Query("SELECT * FROM device_config WHERE id = 1")
    fun observe(): Flow<DeviceConfigEntity?>

    @Query("SELECT * FROM device_config WHERE id = 1")
    suspend fun get(): DeviceConfigEntity?

    @Upsert
    suspend fun upsert(config: DeviceConfigEntity)

    @Query("UPDATE device_config SET last_sync_at = :at WHERE id = 1")
    suspend fun setLastSync(at: Long)

    @Query("UPDATE device_config SET fcm_token = :token, fcm_token_synced = :synced WHERE id = 1")
    suspend fun setFcmToken(token: String?, synced: Boolean)

    @Query("UPDATE device_config SET session_valid = :valid WHERE id = 1")
    suspend fun setSessionValid(valid: Boolean)

    @Query("DELETE FROM device_config")
    suspend fun clear()
}

@Dao
interface PrinterConfigDao {
    @Query("SELECT * FROM printer_config")
    fun observeAll(): Flow<List<PrinterConfigEntity>>

    @Query("SELECT * FROM printer_config")
    suspend fun getAll(): List<PrinterConfigEntity>

    @Query("SELECT * FROM printer_config WHERE role = :role")
    suspend fun get(role: PrinterRole): PrinterConfigEntity?

    @Upsert
    suspend fun upsert(config: PrinterConfigEntity)

    @Query("DELETE FROM printer_config WHERE role = :role")
    suspend fun delete(role: PrinterRole)
}

@Dao
interface PrintedJobDao {
    @Query("SELECT * FROM printed_jobs WHERE job_id = :jobId")
    suspend fun get(jobId: String): PrintedJobEntity?

    /** Returns -1 when the job already has a row, which means another path claimed it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: PrintedJobEntity): Long

    @Query("UPDATE printed_jobs SET state = :state, updated_at = :at WHERE job_id = :jobId")
    suspend fun setState(jobId: String, state: PrintedState, at: Long)

    @Query("DELETE FROM printed_jobs WHERE job_id = :jobId")
    suspend fun delete(jobId: String)

    @Query("SELECT * FROM printed_jobs WHERE state = :state")
    suspend fun withState(state: PrintedState): List<PrintedJobEntity>

    @Query("DELETE FROM printed_jobs WHERE updated_at < :before")
    suspend fun pruneOlderThan(before: Long): Int

    @Query("SELECT COUNT(*) FROM printed_jobs")
    suspend fun count(): Int
}

@Dao
interface RecentJobDao {
    @Query("SELECT * FROM recent_jobs ORDER BY updated_at DESC LIMIT $MAX_RECENT_JOBS")
    fun observe(): Flow<List<RecentJobEntity>>

    @Query("SELECT * FROM recent_jobs ORDER BY updated_at DESC")
    suspend fun getAll(): List<RecentJobEntity>

    @Upsert
    suspend fun upsertRaw(job: RecentJobEntity)

    @Query("DELETE FROM recent_jobs WHERE job_id NOT IN (SELECT job_id FROM recent_jobs ORDER BY updated_at DESC LIMIT $MAX_RECENT_JOBS)")
    suspend fun trim()

    @Transaction
    suspend fun upsert(job: RecentJobEntity) {
        upsertRaw(job)
        trim()
    }

    companion object {
        const val MAX_RECENT_JOBS = 20
    }
}

/** Prints the seller started in the app, and each item's outcome (see PrintRunner). */
@Dao
interface PrintRunDao {
    @Insert
    suspend fun insertRun(run: PrintRunEntity)

    @Insert
    suspend fun insertItems(items: List<PrintRunItemEntity>)

    @Query("SELECT * FROM print_runs WHERE id = :id")
    suspend fun getRun(id: String): PrintRunEntity?

    @Query("SELECT * FROM print_runs WHERE id = :id")
    fun observeRun(id: String): Flow<PrintRunEntity?>

    @Query("SELECT * FROM print_runs ORDER BY created_at DESC LIMIT 50")
    fun observeRuns(): Flow<List<PrintRunEntity>>

    @Query("SELECT * FROM print_runs WHERE state = :state")
    suspend fun runsIn(state: RunState): List<PrintRunEntity>

    @Query("SELECT * FROM print_run_items WHERE run_id = :runId ORDER BY idx")
    suspend fun items(runId: String): List<PrintRunItemEntity>

    @Query("SELECT * FROM print_run_items WHERE run_id = :runId ORDER BY idx")
    fun observeItems(runId: String): Flow<List<PrintRunItemEntity>>

    @Query("UPDATE print_run_items SET status = :status, reason = :reason, updated_at = :at WHERE run_id = :runId AND idx = :idx")
    suspend fun setItem(runId: String, idx: Int, status: RunItemStatus, reason: String?, at: Long)

    /** Retry: the run's unsuccessful items go back in the queue as a new attempt. */
    @Query(
        "UPDATE print_run_items SET status = 'PENDING', reason = NULL, attempt = attempt + 1, reported = 0, updated_at = :at " +
            "WHERE run_id = :runId AND status IN ('FAILED', 'INCOMPLETE', 'NOT_PRINTED')",
    )
    suspend fun requeueUnsuccessful(runId: String, at: Long): Int

    @Query("UPDATE print_runs SET state = :state, printed = :printed, failed = :failed, message = :message, updated_at = :at WHERE id = :id")
    suspend fun setRun(id: String, state: RunState, printed: Int, failed: Int, message: String?, at: Long)

    /** Finished order / parcel items whose result hasn't reached the server yet. */
    @Query(
        "SELECT * FROM print_run_items WHERE reported = 0 AND kind IN ('order', 'parcel') " +
            "AND status IN ('PRINTED', 'FAILED', 'INCOMPLETE', 'NOT_PRINTED') ORDER BY updated_at LIMIT 200",
    )
    suspend fun unreported(): List<PrintRunItemEntity>

    @Query("UPDATE print_run_items SET reported = 1 WHERE run_id = :runId AND idx IN (:idx)")
    suspend fun markReported(runId: String, idx: List<Int>)

    @Query("DELETE FROM print_runs WHERE created_at < :before AND state != 'RUNNING'")
    suspend fun deleteRunsBefore(before: Long)

    @Query("DELETE FROM print_run_items WHERE run_id NOT IN (SELECT id FROM print_runs)")
    suspend fun deleteOrphanItems()
}
