package com.sopifo.printagent.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.core.AppLog

/**
 * FCM is only a wake-up signal: `{"job_id": "job_123"}`. Any other payload keys are ignored —
 * job content, images and printer commands are always fetched from the backend with the device JWT.
 */
class SopifoMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        try {
            val container = (application as SopifoApp).container
            container.fcm.onMessageReceived()
            container.handleWake(message.data["job_id"], source = "fcm")
        } catch (t: Throwable) {
            AppLog.e(TAG, "FCM message handling failed", t)
        }
    }

    /** Firebase Messaging 25.1+ delivers registration tokens here. */
    override fun onRegistered(token: String) = onToken(token)

    @Deprecated("Superseded by onRegistered in Firebase Messaging 25.1; still called by older Play services paths")
    override fun onNewToken(token: String) = onToken(token)

    private fun onToken(token: String) {
        try {
            AppLog.i(TAG, "FCM token rotated")
            (application as SopifoApp).container.scheduler.enqueueFcmTokenSync(token)
        } catch (t: Throwable) {
            AppLog.e(TAG, "FCM token handling failed", t)
        }
    }

    private companion object {
        const val TAG = "FcmService"
    }
}
