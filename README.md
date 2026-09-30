# Sopifo Print Agent (Android)

Android companion app for Sopifo Cloud Print. The phone is a **print agent only**: the backend
owns every job, and the phone keeps no job queue. It wakes on FCM, fetches the job,
downloads the backend-rendered PNG, prints it over Bluetooth, reports the result and goes back
to sleep.

Kotlin · Jetpack Compose · Coroutines · Room · WorkManager · FCM · Bluetooth SPP (ESC/POS + TSPL)
· minSdk 26 · targetSdk 36

---

## Build

Requirements: Android SDK (platform 37), JDK 17+ (Gradle provisions its own daemon JDK).

```bash
./gradlew assembleDebug            # debug APK (can talk to http://localhost for tests)
./gradlew assembleRelease          # release APK, R8-minified, HTTPS only
```

| Setting | How |
|---|---|
| API base URL | `sopifoApiBaseUrl` in `gradle.properties` or `-PsopifoApiBaseUrl=https://…`. Must be HTTPS; the build fails otherwise. |
| Firebase / FCM | Put `app/google-services.json` in place. Without it the app still builds and runs; Diagnostics shows "FCM: Not configured", and jobs are only picked up by pending-job recovery. Full guide: [docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md). |
| Release signing | Env vars `SOPIFO_KEYSTORE`, `SOPIFO_KEYSTORE_PASSWORD`, `SOPIFO_KEY_ALIAS`, `SOPIFO_KEY_PASSWORD`. |

Release builds always use the compiled-in API URL. A registration QR code cannot point a
production device at another server; only debug builds honour the `api` hint.

---

## Backend contract

The spec defines `GET /api/print-jobs/pending` and `POST /api/print-jobs/{id}/cancel`. The
other endpoints below are what this app implements. **Backend: please confirm or adjust them.**
They are all in one place: [ApiClient.kt](app/src/main/java/com/sopifo/printagent/data/api/ApiClient.kt).

Every call except `register` sends `Authorization: Bearer <device_jwt>`. Responses may be bare
objects or wrapped in `{"data": …}`, and unknown fields are ignored.

| Method & path | Body → Response |
|---|---|
| `POST /api/devices/register` | `{token, device_name, platform, app_version, fcm_token?}` → `{device_id, device_jwt, store_name, device_name?}` |
| `POST /api/devices/heartbeat` | `{battery_percent, printer_status:{receipt,label}, app_version, last_seen}` every 5 min. Printer values: `connected`, `disconnected`, `bluetooth_off`, `no_permission`, `not_configured` |
| `POST /api/devices/fcm-token` | `{fcm_token}`, sent on registration and whenever Firebase rotates the token |
| `GET /api/print-jobs/{id}` | → `{id, type, status, image_url, created_at, copies?}` |
| `GET /api/print-jobs/pending` | → `[job…]` or `{jobs:[…]}`: jobs assigned to this device, `pending`, < 120 s old |
| `POST /api/print-jobs/{id}/complete` | `{printed_at}` |
| `POST /api/print-jobs/{id}/fail` | `{error}` (human-readable reason) |
| `POST /api/print-jobs/{id}/cancel` | `{}`, from the Pending Jobs screen (single job only) |

Notes for the backend:
- `created_at` must be present (ISO-8601 in UTC, or with an offset). The agent judges the
  2-minute rule on **server time**, taken from the HTTP `Date` header, so a phone with a wrong
  clock still behaves correctly.
- A job is only printed when its `status` is `pending`.
- `image_url` must be HTTPS. The JWT is attached only when the image is served from the API host
  itself, never to a CDN or storage host. Images are capped at 5 MB and 4096 px wide.
- Report calls are retried with backoff for hours. `404/409/410/422` on a report is treated as
  "already final" and not retried.

### FCM contract

Send a **high-priority data message** containing only the job ID:

```json
{ "job_id": "job_123" }
```

Anything else in the payload is ignored. A message without a valid `job_id` triggers a pending
sync. Use high priority, otherwise Android may delay the message in Doze.

### Job routing

| Type | Printer |
|---|---|
| `receipt`, `scratchpad` | Receipt printer |
| `label`, `barcode_label`, `qr_label` | Label printer |

The spec does not route `scratchpad`; it goes to the receipt (roll) printer. The app never
renders receipts, labels, barcodes or QR codes. It only scales the PNG to the printable width and
converts it to 1-bit.

---

## How it works

**Job flow** ([JobProcessor.kt](app/src/main/java/com/sopifo/printagent/jobs/JobProcessor.kt)):
FCM → expedited WorkManager job → `GET job` → checks (not already printed, `pending`, ≤ 120 s
server time, valid type/image) → download PNG → encode → Bluetooth → report.

**No duplicate prints.** Job IDs go into the `printed_jobs` table, and a job is claimed atomically
*once the printer link is up*, just before the first byte is sent:
- A connection failure leaves the job unclaimed, so a retry is safe.
- A failure after bytes were sent is never retried automatically, because part of the job may
  already be on paper. It is reported as failed, and a reprint arrives as a new job ID.
- If the app dies mid-print, the job is reported as `interrupted` on next start and not reprinted.

**Pending-job recovery** (`GET /pending`) runs on app start, service start, boot, app update,
network reconnect, screen-on / Doze exit, and Bluetooth turning on. Triggers that fire together
are collapsed into one request.

**Background reliability**
- The foreground service (`connectedDevice` type) only keeps the process alive and listens for
  system events. It runs no loops, holds no wake locks and keeps no Bluetooth socket open.
- The heartbeat is a self-rescheduling 5-minute WorkManager chain.
- A 15-minute periodic watchdog restarts the service and the heartbeat chain if an OEM task
  killer stopped them.
- The boot receiver restores the session, service, FCM token, printers and pending jobs without
  user action.
- Uncaught exceptions are written to the log and schedule a recovery job. The service is sticky.

**Crash resistance.** Printer, network, image, storage and malformed-job failures are all handled
where they occur and end up as a recorded outcome. Every Bluetooth connect and write has a hard
timeout, enforced by closing the socket from a watchdog, because `connect()` ignores thread
interrupts. The log is a rotating JSON-lines file (`files/logs/agent.log`), also viewable in
Diagnostics.

**Battery.** Push-only; no polling loops. Bluetooth connects per job and disconnects after. HTTP
keep-alive is capped at 15 s so the radio can idle.

**Security**
- The JWT is encrypted with an AES-256-GCM key held in Android Keystore and is excluded from
  backups.
- Release builds allow HTTPS only (network security config plus a code-level URL policy).
- Job IDs are validated before they are used in URL paths.
- Device identifiers are never used for authentication; the backend identifies the device by its
  JWT.

---

## Printers

Bluetooth Classic (SPP) only. Pair the printer in Android Bluetooth settings, then choose it on
the **Printers** tab. The app stores the name and MAC address.

- Receipt printer: ESC/POS raster (`GS v 0`), 58 mm (384 dots) or 80 mm (576 dots), with an
  optional paper cut.
- Label printer: TSPL (`BITMAP`), label size selectable. ESC/POS is also available for label
  printers that use it.

A printer counts as **Connected** when the last connection to it succeeded. Status is refreshed by
every job, by probes (service start, Bluetooth on, each heartbeat) and by system link events.

---

## Deploying a phone for unattended use

1. Install, open, grant **Nearby devices** and **Notifications**.
2. Register: **Scan QR** or enter the token.
3. **Allow background running** (Home or Diagnostics). This asks to exempt the app from battery
   optimisation.
4. OEM settings. Honor/Huawei, Xiaomi, Oppo, Vivo and Samsung kill background apps aggressively:
   - Honor/Huawei: *Settings → Apps → App launch → Sopifo Print Agent → Manage manually*, and
     enable Auto-launch, Secondary launch and Run in background.
   - Samsung: *Settings → Battery → Background usage limits*, add to *Never sleeping apps*.
5. Pair printers, choose them on the Printers tab, and use **Test Receipt Print** / **Test Label
   Print**.
6. Keep the phone on a charger.

**Battery exemption.** Requesting `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is intentional: a
dedicated print agent must not have jobs deferred by Doze. Google Play only allows this for
certain app categories. For Play distribution, review the policy or distribute through MDM or
direct install.

---

## Testing

Three layers, all runnable against a USB-connected phone:

```bash
scripts/run_all_tests.sh                 # everything below
scripts/run_all_tests.sh --network       # + airplane-mode reconnect test (mode is restored)
scripts/run_all_tests.sh --reboot        # + reboots the phone to verify auto-start

./gradlew testDebugUnitTest              # 1. JVM unit tests
./gradlew connectedDebugAndroidTest      # 2. instrumented tests on the phone
python3 scripts/device_e2e.py            # 3. device end-to-end
python3 scripts/mock_backend.py          # the mock backend on its own, for manual testing
```

1. **Unit tests (42).** ESC/POS and TSPL encoding, 1-bit conversion, routing, the 2-minute rule
   and timestamp parsing, server-clock skew, URL and job-ID validation, QR/token parsing, and the
   API client against MockWebServer (envelopes, malformed JSON, 401/5xx, JWT not sent to foreign
   hosts, image size cap).
2. **Instrumented tests (31).**
   - Room: 20-row history, atomic claim, pruning.
   - Keystore JWT round-trip, and confirmation that no plaintext is on disk.
   - PNG → printer bytes.
   - `JobProcessor` against a mock backend with a fake printer: duplicates, concurrent FCM plus
     sync, stale jobs, clock skew, routing, malformed jobs, image failures, printer retry,
     Bluetooth off, broken writes, interrupted prints.
   - Compose UI: registration validation; Home, Pending cancel and Diagnostics against a mock
     backend.
3. **Device E2E (46 checks + 1 opt-in).** Drives the real installed app through a debug-only
   adb receiver (guarded by the `DUMP` permission, and absent from release builds) against
   `mock_backend.py` over `adb reverse`, printing to a debug-only virtual printer:
   - registration and encrypted JWT
   - foreground service type
   - heartbeat payload
   - FCM job flow, idempotency, routing, the 2-minute rule
   - malformed jobs and image failures without crashing
   - cancellation
   - recovery of jobs missed while the app was stopped
   - 401 handling
   - recovery from a real process crash
   - idle battery hygiene (no wake locks, no repeating alarms, sockets released)
   - optional network-reconnect and reboot scenarios

   Results go to `scripts/out/e2e-report.json`.

The UI tests work on a phone with a secure lock screen. Debug builds honour a test-only
"show when locked" launch flag.

### Not covered by automated tests
- **Real FCM delivery.** Needs `google-services.json` and a server key. The E2E suite enters the
  same code path through the debug receiver.
- **A physical Bluetooth printer.** The transport is exercised with the virtual printer. Check a
  real printer with the Test Print buttons.
- **Auto-start after reboot.** Implemented, but only exercised with `--reboot`.
