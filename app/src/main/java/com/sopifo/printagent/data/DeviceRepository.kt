package com.sopifo.printagent.data

import androidx.core.content.edit
import android.content.Context
import com.sopifo.printagent.BuildConfig
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.data.api.ApiClient
import com.sopifo.printagent.data.api.ApiException
import com.sopifo.printagent.data.api.ConnectClaim
import com.sopifo.printagent.data.api.ConnectClaimRequest
import com.sopifo.printagent.data.api.ConnectStartRequest
import com.sopifo.printagent.data.api.HeartbeatRequest
import com.sopifo.printagent.data.api.PrinterStatusPayload
import com.sopifo.printagent.data.api.RegisterRequest
import com.sopifo.printagent.data.api.RegisterResponse
import com.sopifo.printagent.data.api.UrlPolicy
import com.sopifo.printagent.data.db.DeviceConfigDao
import com.sopifo.printagent.data.db.DeviceConfigEntity
import com.sopifo.printagent.data.secure.SessionTokenStore
import com.sopifo.printagent.fcm.FcmManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

class RegistrationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A "Connect to my shop" code this phone shows until it is claimed, replaced or expires. */
data class ConnectCode(
    val requestId: String,
    val code: String,
    val secret: String,
    val connectUrl: String,
    /** When this phone got it (wall clock), to drop codes older than a day without asking. */
    val issuedAt: Long,
)

enum class ConnectPoll { WAITING, EXPIRED, CONNECTED }

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
    /** The Connect code in progress. App-private and excluded from backups, like the session prefs. */
    private val connectPrefs = context.getSharedPreferences("connect_request", Context.MODE_PRIVATE)
    @Volatile private var sessionValidCache: Boolean? = null
    /** Fetched once per Connect screen visit, not on every poll. */
    @Volatile private var connectFcmToken: String? = null

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
        return saveSession(response, target, fcmToken)
    }

    /** Stores the credentials a pairing code or a Connect claim returned. */
    private suspend fun saveSession(response: RegisterResponse, target: String, fcmToken: String?): DeviceConfigEntity {
        if (response.deviceJwt.isBlank() || response.deviceId.isBlank()) throw RegistrationException("Server returned an incomplete registration")
        if (!tokenStore.save(response.deviceJwt)) throw RegistrationException("Could not store the device session securely")
        prefs.edit(commit = true) {
            putString(KEY_BASE_URL, target)
            remove(KEY_DISCONNECTED_FROM)
        }
        clearConnectCode()
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

    /** The Connect code this phone is showing, if it has one younger than a day. */
    fun savedConnectCode(): ConnectCode? {
        val code = ConnectCode(
            requestId = connectPrefs.getString(KEY_CONNECT_ID, null) ?: return null,
            code = connectPrefs.getString(KEY_CONNECT_CODE, null) ?: return null,
            secret = connectPrefs.getString(KEY_CONNECT_SECRET, null) ?: return null,
            connectUrl = connectPrefs.getString(KEY_CONNECT_URL, null) ?: return null,
            issuedAt = connectPrefs.getLong(KEY_CONNECT_ISSUED, 0),
        )
        // The server decides expiry (claim answers 410); this only skips a pointless round trip.
        return code.takeIf { System.currentTimeMillis() - it.issuedAt in 0 until CONNECT_CODE_MAX_AGE_MS }
    }

    /** Asks for a new Connect code; the server drops this phone's previous one. */
    suspend fun newConnectCode(): ConnectCode {
        val target = resolveBaseUrl(null)
        val response = try {
            apiProvider().connectStart(
                target,
                ConnectStartRequest(
                    deviceUuid = systemInfo.deviceUuid() ?: installUuid(),
                    deviceName = systemInfo.deviceName(),
                    appVersion = systemInfo.appVersion,
                ),
            )
        } catch (e: ApiException) {
            AppLog.w(TAG, "Connect code request failed", e)
            throw RegistrationException(connectFailure(e), e)
        }
        val url = urlPolicy.parseAllowed(response.connectUrl)?.toString()
            ?: throw RegistrationException("Server sent an unusable connect link")
        val code = ConnectCode(response.requestId, response.code, response.pollSecret, url, System.currentTimeMillis())
        connectPrefs.edit(commit = true) {
            putString(KEY_CONNECT_ID, code.requestId)
            putString(KEY_CONNECT_CODE, code.code)
            putString(KEY_CONNECT_SECRET, code.secret)
            putString(KEY_CONNECT_URL, code.connectUrl)
            putLong(KEY_CONNECT_ISSUED, code.issuedAt)
        }
        AppLog.i(TAG, "Connect code issued", "request" to code.requestId)
        return code
    }

    /** One check: is [code] approved yet? Saves the session when it is. */
    suspend fun claimConnect(code: ConnectCode): ConnectPoll {
        val target = resolveBaseUrl(null)
        val fcmToken = connectFcmToken ?: fcm.fetchToken().also { connectFcmToken = it }
        val claim = try {
            apiProvider().connectClaim(target, ConnectClaimRequest(code.requestId, code.secret, fcmToken))
        } catch (e: ApiException) {
            AppLog.w(TAG, "Connect claim failed", e)
            throw RegistrationException(connectFailure(e), e)
        }
        return when (claim) {
            ConnectClaim.Pending -> ConnectPoll.WAITING
            ConnectClaim.Expired -> {
                if (connectPrefs.getString(KEY_CONNECT_ID, null) == code.requestId) clearConnectCode()
                ConnectPoll.EXPIRED
            }
            is ConnectClaim.Connected -> {
                saveSession(claim.response, target, fcmToken)
                connectFcmToken = null
                ConnectPoll.CONNECTED
            }
        }
    }

    /** For the rare phone without an ANDROID_ID: a random ID kept for this install. */
    private fun installUuid(): String =
        prefs.getString(KEY_INSTALL_UUID, null) ?: "install-${java.util.UUID.randomUUID()}".also { id ->
            prefs.edit(commit = true) { putString(KEY_INSTALL_UUID, id) }
        }

    private fun clearConnectCode() {
        connectPrefs.edit(commit = true) { clear() }
    }

    private fun connectFailure(e: ApiException): String = when {
        e.httpCode == null -> "No internet — retrying…"
        e.httpCode == 429 -> "Too many tries. Waiting a moment…"
        e.httpCode in 400..499 -> e.message ?: "Could not connect this phone"
        else -> "Sopifo Cloud is busy — retrying…"
    }

    /**
     * Tells the server this phone is leaving (More → Disconnect this phone). False when it
     * couldn't be told (offline): the phone then still shows on the dashboard until removed there.
     */
    suspend fun tellServerUnregister(): Boolean = try {
        withTimeoutOrNull(UNREGISTER_TIMEOUT_MS) { apiProvider().unregister(); true } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        // Already removed on the server: nothing left to tell.
        e.unauthorized
    } catch (e: Exception) {
        AppLog.w(TAG, "Unregister request failed", e)
        false
    }

    /** The shop the server removed this phone from, for the Connect screen; null otherwise. */
    fun disconnectedFrom(): String? = prefs.getString(KEY_DISCONNECTED_FROM, null)

    fun setDisconnectedFrom(storeName: String) {
        prefs.edit(commit = true) { putString(KEY_DISCONNECTED_FROM, storeName) }
    }

    suspend fun unregister() {
        tokenStore.clear()
        prefs.edit(commit = true) { remove(KEY_BASE_URL) }
        dao.clear()
        sessionValidCache = null
        AppLog.i(TAG, "Device session cleared")
    }

    /** Returns true when support asked for this phone's logs. */
    suspend fun sendHeartbeat(printerStatus: PrinterStatusPayload): Boolean {
        val logsRequested = apiProvider().heartbeat(
            HeartbeatRequest(
                batteryPercent = systemInfo.batteryPercent(),
                printerStatus = printerStatus,
                appVersion = systemInfo.appVersion,
                lastSeen = Instant.now().toString(),
            ),
        )
        markSynced()
        return logsRequested
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

    /** Debug builds only: the server on-device tests run against (the Connect screen calls it on its own). */
    @Volatile var debugApiBaseUrl: String? = null

    /** Release builds always use the compiled-in HTTPS endpoint; a QR code cannot redirect them. */
    private fun resolveBaseUrl(hint: String?): String {
        val debugTarget = if (BuildConfig.DEBUG) hint?.takeIf { it.isNotBlank() }?.trim() ?: debugApiBaseUrl else null
        val candidate = debugTarget ?: BuildConfig.API_BASE_URL
        val parsed = urlPolicy.parseAllowed(candidate) ?: throw RegistrationException("Server address not allowed: $candidate")
        return parsed.toString().trimEnd('/')
    }

    private companion object {
        const val TAG = "Device"
        const val KEY_BASE_URL = "api_base_url"
        const val KEY_DISCONNECTED_FROM = "disconnected_from"
        const val KEY_INSTALL_UUID = "install_uuid"
        const val KEY_CONNECT_ID = "request_id"
        const val KEY_CONNECT_CODE = "code"
        const val KEY_CONNECT_SECRET = "secret"
        const val KEY_CONNECT_URL = "connect_url"
        const val KEY_CONNECT_ISSUED = "issued_at"
        const val CONNECT_CODE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        const val UNREGISTER_TIMEOUT_MS = 8_000L
    }
}
