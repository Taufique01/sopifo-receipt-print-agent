# Firebase (FCM) setup

The print agent uses Firebase Cloud Messaging (FCM) for one thing only: a push that wakes the phone
up when the backend has a new print job for it. The push carries nothing but the job ID. The phone
then fetches the job itself from the Sopifo API.

FCM is optional at build time. Without it the app still works, but it only picks up jobs through
pending-job recovery (app start, network reconnect, screen on, Bluetooth on, boot), not instantly.

---

## 1. What keys are involved

There are **two different credentials**, and they go to two different places:

| Credential | Where it goes | Secret? | Used for |
|---|---|---|---|
| `google-services.json` | Android app: `app/google-services.json` | Not really. It identifies the Firebase project and app (project ID, app ID, an Android API key restricted to this package). Keep it out of public repos anyway. | Lets the phone register with FCM and get a **device token** |
| Service account key (JSON with a private key) | **Backend only** (server env / secret store) | **Yes, secret.** Never put it in the app. | Lets the backend **send** messages through the FCM HTTP v1 API |

The old "Server key" (legacy FCM API) is shut down. Use the HTTP v1 API with a service account.

The app itself needs no key in code. The Gradle build detects `app/google-services.json` and turns
FCM on ([app/build.gradle.kts](../app/build.gradle.kts)):

```kotlin
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) apply(plugin = "com.google.gms.google-services")
buildConfigField("boolean", "FIREBASE_CONFIGURED", hasFirebaseConfig.toString())
```

---

## 2. Setup steps

### 2.1 Create the Firebase project

1. Go to <https://console.firebase.google.com> and **Add project** (or use the existing Sopifo
   project). Google Analytics is not needed; the app disables it in the manifest.
2. In the project, **Add app → Android**.
   - Package name: `com.sopifo.printagent` (must match `applicationId` exactly).
   - Nickname: anything, e.g. "Sopifo Print Agent".
   - SHA-1: not required for FCM. You can skip it.
3. Download **`google-services.json`**.

### 2.2 Add it to the app

1. Copy the file to `app/google-services.json` (next to `app/build.gradle.kts`, not the project root).
2. Build:

   ```bash
   ./gradlew assembleDebug      # or assembleRelease
   ```

3. Install, open the app, register the device, then open **Diagnostics**. The **FCM status** row
   should read **Registered**.

   | Diagnostics shows | Meaning |
   |---|---|
   | Not configured in this build | `google-services.json` was missing when the APK was built |
   | No token yet | Firebase is configured, but no token yet (no Play services, no network, or timed out after 15 s) |
   | Token ready (not yet sent) | Phone has a token, but the backend hasn't accepted it yet |
   | Registered | Backend has the token. Pushes will work. |
   | Error: … | Token request or `POST /api/devices/fcm-token` failed. The message says which. |

   The phone needs Google Play services. FCM does not work on devices without it (e.g. Huawei
   devices without GMS).

Optional: to keep the file out of git, add `app/google-services.json` to `.gitignore` and supply
it from CI secrets at build time.

### 2.3 Give the backend permission to send

1. Firebase console → **Project settings → Service accounts → Generate new private key**.
   This downloads a JSON file with a private key.
2. Store it as a secret on the backend server (env var / secret manager). Do **not** commit it.
3. Make sure **Firebase Cloud Messaging API (V1)** is enabled
   (Project settings → Cloud Messaging). It is enabled by default for new projects.
4. Use the Firebase Admin SDK (easiest) or call the HTTP v1 endpoint directly with an OAuth token
   minted from that service account. Examples are in section 4.

---

## 3. How it works: how Firebase knows which phone to send to

Firebase doesn't know anything about stores, printers or jobs. It only knows **tokens**. A token
is an address for one app install on one phone. The backend decides *when* to push and *which
token* to push to. Firebase only delivers.

```
 Phone (app)                    Firebase (FCM)                 Sopifo backend
 ───────────                    ──────────────                 ──────────────
 1. App starts, asks FCM  ───►  issues token "fX3k…"
    for a token            ◄───
 2. Register device  ─────────────────────────────────────────► stores fcm_token
    POST /api/devices/register {token, …, fcm_token}           with device_id
                                                               (store → device → token)

        … later, a cashier prints a receipt …

                                                               3. Job created for this device.
                                                                  Look up the device's fcm_token
                                 ◄──────────────────────────────  send {job_id} to that token
 4. FCM delivers  ◄───────────  pushes to that phone
    {"job_id":"job_123"}
 5. App fetches job  ─────────────────────────────────────────► GET /api/print-jobs/job_123
    downloads PNG, prints over Bluetooth
 6. Report  ──────────────────────────────────────────────────► POST /api/print-jobs/job_123/complete
```

### 3.1 Getting the token (phone side)

- At registration, `DeviceRepository` calls `FcmManager.fetchToken()`
  ([FcmManager.kt](../app/src/main/java/com/sopifo/printagent/fcm/FcmManager.kt)) and sends the
  token in `POST /api/devices/register` as `fcm_token`.
- Firebase can **rotate** the token (app reinstall, restoring data, clearing app data, Firebase
  deciding to refresh it). When that happens, `SopifoMessagingService.onRegistered` / `onNewToken`
  fires ([SopifoMessagingService.kt](../app/src/main/java/com/sopifo/printagent/fcm/SopifoMessagingService.kt)),
  and a WorkManager job sends the new one with `POST /api/devices/fcm-token {fcm_token}`.
  It retries until the backend accepts it.
- The token is also re-checked on boot and by the heartbeat worker if it was never acknowledged.

So the backend must **always store the latest token per device**, overwriting the old one.

### 3.2 Deciding when to send (backend side)

The backend sends a push whenever a print job is created for a device:

1. Job is created and assigned to `device_id` with status `pending`.
2. Backend looks up that device's latest `fcm_token`.
3. Backend sends a high-priority data message `{"job_id": "<id>"}` to that token.

That is the whole trigger. The phone never polls in a loop.

### 3.3 Receiving the push (phone side)

`SopifoMessagingService.onMessageReceived` reads `data["job_id"]` and calls
`AppContainer.handleWake`:

- Valid `job_id` → an **expedited WorkManager job** for that ID (unique per job ID, so a duplicate
  push is ignored) → `JobProcessor` fetches, checks and prints.
- Missing or invalid `job_id` → a **pending sync** (`GET /api/print-jobs/pending`), which picks up
  anything waiting.

Everything except `job_id` in the payload is ignored. Job data, the image and printer commands
always come from the API with the device JWT. A forged or replayed push can't make the phone print
anything the backend didn't assign to it.

### 3.4 If a push is lost

FCM is best-effort. If a push is delayed or dropped, pending-job recovery catches the job on the
next app start, network reconnect, screen-on / Doze exit, Bluetooth-on or boot. Jobs older than
120 seconds (server time) are not printed.

---

## 4. Sending a push from the backend

Rules:
- Use a **data message** (`data`), not a `notification` message. A notification message is shown
  by the system tray and does not reach `onMessageReceived` when the app is in the background.
- Set **Android priority HIGH**. Normal priority can be held for minutes while the phone is in Doze.
- All `data` values must be strings.

### Firebase Admin SDK (Node.js)

```js
import admin from "firebase-admin";

admin.initializeApp({
  credential: admin.credential.cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON)),
});

export async function wakeDevice(fcmToken, jobId) {
  await admin.messaging().send({
    token: fcmToken,
    data: { job_id: String(jobId) },
    android: { priority: "high", ttl: 120 * 1000 }, // job is useless after 2 min
  });
}
```

### Firebase Admin SDK (PHP, kreait/firebase-php)

```php
use Kreait\Firebase\Factory;
use Kreait\Firebase\Messaging\CloudMessage;

$messaging = (new Factory)->withServiceAccount(getenv('FIREBASE_SERVICE_ACCOUNT_PATH'))->createMessaging();

$messaging->send(
    CloudMessage::withTarget('token', $fcmToken)
        ->withData(['job_id' => (string) $jobId])
        ->withAndroidConfig(['priority' => 'high', 'ttl' => '120s'])
);
```

### Raw HTTP v1

```http
POST https://fcm.googleapis.com/v1/projects/<PROJECT_ID>/messages:send
Authorization: Bearer <OAuth2 access token from the service account,
                       scope https://www.googleapis.com/auth/firebase.messaging>
Content-Type: application/json

{
  "message": {
    "token": "<device fcm_token>",
    "data": { "job_id": "job_123" },
    "android": { "priority": "HIGH", "ttl": "120s" }
  }
}
```

### Handling send errors

| FCM error | What to do |
|---|---|
| `UNREGISTERED` / `NOT_FOUND` (404) | The token is dead (app uninstalled or data cleared). Clear it for that device. The phone sends a new one when it comes back. |
| `INVALID_ARGUMENT` (400) | Bad token or payload. Log it; don't retry the same request. |
| `UNAVAILABLE` / `INTERNAL` (5xx), `QUOTA_EXCEEDED` (429) | Retry with backoff, but only while the job is still < 120 s old. |
| `SENDER_ID_MISMATCH` / 403 | The token belongs to another Firebase project. The app's `google-services.json` and the backend's service account must come from the **same** project. |

A failed push is not a lost job: the phone still finds it through pending-job recovery.

---

## 5. Testing a push by hand

1. On the phone, register the device, then get its token. The backend has it from
   `POST /api/devices/register` or `/api/devices/fcm-token`.
2. Create a pending job for the device on the backend (it must be < 120 s old when the phone
   fetches it).
3. Send `{"job_id": "<that id>"}` to the token with one of the snippets above.
   The Firebase console's "Send test message" sends *notification* messages, so it won't
   reach `onMessageReceived` when the app is in the background. Use the API instead.
4. Diagnostics → **Last FCM wake-up** updates, and the job appears in history.

Useful log check:

```bash
adb logcat | grep -E "FcmService|Wake|Fcm"
```

Without FCM (or before the backend sends pushes), the debug build and the E2E suite enter the same
code path through the debug adb receiver. See the Testing section of the [README](../README.md).

---

## 6. Checklist

- [ ] Firebase project created, Android app `com.sopifo.printagent` added
- [ ] `app/google-services.json` in place before building
- [ ] Diagnostics shows **FCM status: Registered** after device registration
- [ ] Backend stores `fcm_token` per device from `register` and `fcm-token`, overwriting old values
- [ ] Backend has the service account JSON as a secret (same Firebase project)
- [ ] Backend sends a **data-only, high-priority** `{"job_id": "…"}` when a job is created
- [ ] Backend clears tokens that return `UNREGISTERED`
