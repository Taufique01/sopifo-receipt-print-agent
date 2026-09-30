package com.sopifo.printagent.fcm

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.sopifo.printagent.BuildConfig
import com.sopifo.printagent.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

enum class FcmState { NOT_CONFIGURED, NO_TOKEN, TOKEN_READY, REGISTERED, ERROR }

data class FcmStatus(val state: FcmState, val lastMessageAt: Long? = null, val detail: String? = null)

class FcmManager(private val context: Context) {
    private val _status = MutableStateFlow(FcmStatus(if (isConfigured()) FcmState.NO_TOKEN else FcmState.NOT_CONFIGURED))
    val status: StateFlow<FcmStatus> = _status.asStateFlow()

    fun isConfigured(): Boolean = BuildConfig.FIREBASE_CONFIGURED && try {
        FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null
    } catch (_: Exception) {
        false
    }

    /** Returns the current FCM token, or null when Firebase is unavailable (never throws). */
    suspend fun fetchToken(): String? {
        if (!isConfigured()) {
            _status.update { it.copy(state = FcmState.NOT_CONFIGURED) }
            return null
        }
        return try {
            // getToken() is deprecated in favour of register()+onRegistered(), but registration needs
            // the token synchronously; onRegistered() handles later rotations.
            @Suppress("DEPRECATION")
            val token = withTimeoutOrNull(15_000) { FirebaseMessaging.getInstance().token.await() }
            if (token.isNullOrBlank()) {
                _status.update { it.copy(state = FcmState.NO_TOKEN, detail = "Token request timed out") }
                null
            } else {
                _status.update { if (it.state == FcmState.REGISTERED) it else it.copy(state = FcmState.TOKEN_READY, detail = null) }
                token
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "FCM token unavailable", e)
            _status.update { it.copy(state = FcmState.ERROR, detail = e.message) }
            null
        }
    }

    fun markRegisteredWithBackend() = _status.update { it.copy(state = FcmState.REGISTERED, detail = null) }

    fun markBackendSyncFailed(detail: String?) =
        _status.update { if (it.state == FcmState.REGISTERED) it else it.copy(state = FcmState.ERROR, detail = detail) }

    fun onMessageReceived() = _status.update { it.copy(lastMessageAt = System.currentTimeMillis()) }

    private companion object {
        const val TAG = "Fcm"
    }
}
