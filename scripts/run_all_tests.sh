#!/usr/bin/env bash
# Runs every test layer against the USB-connected phone:
#   1. JVM unit tests            (encoders, routing, freshness, validation, API client)
#   2. Instrumented tests        (Room, Keystore, PNG pipeline, JobProcessor, Compose UI) on the phone
#   3. Device end-to-end tests   (real app + mock backend over adb reverse + virtual printer)
#
# Usage: scripts/run_all_tests.sh [--network] [--reboot]     (extra flags go to device_e2e.py)
set -euo pipefail
cd "$(dirname "$0")/.."

ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
[[ -x "$ADB" ]] || ADB="$(command -v adb)"
export ADB

if [[ "$("$ADB" get-state 2>/dev/null)" != "device" ]]; then
  echo "No authorised Android device connected. Enable USB debugging and accept the prompt." >&2
  exit 1
fi
echo "Device: $("$ADB" shell getprop ro.product.model) (API $("$ADB" shell getprop ro.build.version.sdk))"
# Keep the screen awake while tests run (the UI tests also work on a locked phone).
"$ADB" shell input keyevent KEYCODE_WAKEUP || true

echo; echo "== 1/3 JVM unit tests"
./gradlew testDebugUnitTest --console=plain -q

echo; echo "== 2/3 Instrumented tests on device"
./gradlew connectedDebugAndroidTest --console=plain -q

python3 - <<'EOF'
import glob, re
t = f = 0
for p in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    m = re.search(r'tests="(\d+)".*?failures="(\d+)".*?errors="(\d+)"', open(p).read())
    t += int(m[1]); f += int(m[2]) + int(m[3])
print(f"Unit tests: {t} run, {f} failed")
for p in glob.glob("app/build/outputs/androidTest-results/connected/debug/**/*.xml", recursive=True):
    m = re.search(r'tests="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"', open(p).read())
    print(f"Instrumented tests: {m[1]} run, {int(m[2]) + int(m[3])} failed")
EOF

echo; echo "== 3/3 Device end-to-end tests"
python3 scripts/device_e2e.py "$@"
