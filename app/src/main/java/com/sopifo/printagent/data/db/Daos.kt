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
