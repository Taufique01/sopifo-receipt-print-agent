package com.sopifo.printagent.data

import androidx.core.content.edit
import android.content.Context
import com.sopifo.printagent.BuildConfig
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.api.ApiClient
import com.sopifo.printagent.data.api.HeartbeatRequest
import com.sopifo.printagent.data.api.PrinterStatusPayload
import com.sopifo.printagent.data.api.RegisterRequest
import com.sopifo.printagent.data.api.UrlPolicy
import com.sopifo.printagent.data.db.DeviceConfigDao
import com.sopifo.printagent.data.db.DeviceConfigEntity
import com.sopifo.printagent.data.secure.SessionTokenStore
import com.sopifo.printagent.fcm.FcmManager
import kotlinx.coroutines.flow.Flow
import java.time.Instant

class RegistrationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Device session: registration, the stored JWT, heartbeat and FCM-token sync.
 * The API base URL is mirrored in plain prefs (it is not secret) so the HTTP client can
 * read it synchronously on any thread.
 */
class DeviceRepository(
    context: Context,
    private val dao: DeviceConfigDao,
    private val tokenStore: SessionTokenStore,
    private val urlPolicy: UrlPolicy,
    private val systemInfo: SystemInfo,
    private val fcm: FcmManager,
    private val apiProvider: () -> ApiClient,
) {
    private val prefs = context.getSharedPreferences("session_meta", Context.MODE_PRIVATE)
    @Volatile private var sessionValidCache: Boolean? = null

    val baseUrl: String? get() = prefs.getString(KEY_BASE_URL, null)

    fun observeConfig(): Flow<DeviceConfigEntity?> = dao.observe()

    suspend fun config(): DeviceConfigEntity? = dao.get()

    /** True when a device JWT is stored and readable. Cheap; safe on any thread. */
    fun hasSession(): Boolean = baseUrl != null && tokenStore.load() != null

    fun jwt(): String? = tokenStore.load()

    suspend fun register(code: RegistrationCode): DeviceConfigEntity {
        val target = resolveBaseUrl(code.apiUrl)
        val fcmToken = fcm.fetchToken()
        val response = try {
            apiProvider().register(
                target,
                RegisterRequest(
                    token = code.token,
                    deviceName = systemInfo.deviceName(),
                    deviceUuid = systemInfo.deviceUuid(),
                    appVersion = systemInfo.appVersion,
                    fcmToken = fcmToken,
                ),
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "Registration failed", e)
            throw RegistrationException(
                when {
                    (e as? com.sopifo.printagent.data.api.ApiException)?.malformed == true ->
                        "Server response not understood. Update the app or contact support."
                    else -> when ((e as? com.sopifo.printagent.data.api.ApiException)?.httpCode) {
                        400, 401, 403, 404, 422 -> "Registration token was rejected"
                        null -> "Cannot reach Sopifo Cloud. Check the internet connection."
                        else -> "Server error during registration. Try again."
                    }
                },
                e,
            )
        }
        if (response.deviceJwt.isBlank() || response.deviceId.isBlank()) throw RegistrationException("Server returned an incomplete registration")
        if (!tokenStore.save(response.deviceJwt)) throw RegistrationException("Could not store the device session securely")
        prefs.edit(commit = true) { putString(KEY_BASE_URL, target) }
        val config = DeviceConfigEntity(
            deviceId = response.deviceId,
            storeName = response.storeName,
            deviceName = response.deviceName ?: systemInfo.deviceName(),
            apiBaseUrl = target,
            registeredAt = System.currentTimeMillis(),
            lastSyncAt = System.currentTimeMillis(),
            fcmToken = fcmToken,
            fcmTokenSynced = fcmToken != null,
            sessionValid = true,
        )
        dao.upsert(config)
        sessionValidCache = true
        if (fcmToken != null) fcm.markRegisteredWithBackend()
        AppLog.i(TAG, "Device registered", "device_id" to response.deviceId, "store" to response.storeName)
        return config
    }

    suspend fun unregister() {
        tokenStore.clear()
        prefs.edit(commit = true) { remove(KEY_BASE_URL) }
        dao.clear()
        sessionValidCache = null
        AppLog.i(TAG, "Device session cleared")
    }

    suspend fun sendHeartbeat(printerStatus: PrinterStatusPayload) {
        apiProvider().heartbeat(
            HeartbeatRequest(
                batteryPercent = systemInfo.batteryPercent(),
                printerStatus = printerStatus,
                appVersion = systemInfo.appVersion,
                lastSeen = Instant.now().toString(),
            ),
        )
        markSynced()
    }

    /** Pushes the FCM token to the backend when it changed or was never acknowledged. */
    suspend fun syncFcmToken(freshToken: String? = null) {
        val config = dao.get() ?: return
        val token = freshToken ?: fcm.fetchToken() ?: return
        if (token == config.fcmToken && config.fcmTokenSynced) {
            fcm.markRegisteredWithBackend()
            return
        }
        dao.setFcmToken(token, false)
        try {
            apiProvider().updateFcmToken(token)
            dao.setFcmToken(token, true)
            fcm.markRegisteredWithBackend()
            AppLog.i(TAG, "FCM token synced")
        } catch (e: Exception) {
            fcm.markBackendSyncFailed(e.message)
            throw e
        }
    }

    suspend fun markSynced() {
        runCatching { dao.setLastSync(System.currentTimeMillis()) }
        onAuthorized()
    }

    suspend fun onUnauthorized() {
        if (sessionValidCache == false) return
        sessionValidCache = false
        AppLog.w(TAG, "Backend rejected device session (401/403)")
        runCatching { dao.setSessionValid(false) }
    }

    private suspend fun onAuthorized() {
        if (sessionValidCache == true) return
        sessionValidCache = true
        runCatching { dao.setSessionValid(true) }
    }

    /** Release builds always use the compiled-in HTTPS endpoint; a QR code cannot redirect them. */
    private fun resolveBaseUrl(hint: String?): String {
        val candidate = if (BuildConfig.DEBUG && !hint.isNullOrBlank()) hint.trim() else BuildConfig.API_BASE_URL
        val parsed = urlPolicy.parseAllowed(candidate) ?: throw RegistrationException("Server address not allowed: $candidate")
        return parsed.toString().trimEnd('/')
    }

    private companion object {
        const val TAG = "Device"
        const val KEY_BASE_URL = "api_base_url"
    }
}
