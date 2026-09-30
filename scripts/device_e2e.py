#!/usr/bin/env python3
"""
End-to-end test of the Sopifo print agent on a real, USB-connected Android phone.

The debug APK is driven through adb against a local mock backend (scripts/mock_backend.py)
reached via `adb reverse`. Printing goes to the debug-only virtual printer (files in app
storage), so no Bluetooth printer is needed. FCM wake-ups are simulated through a debug-only
broadcast that enters the exact code path FirebaseMessagingService uses.

Usage:
  python3 scripts/device_e2e.py                 # build, install, run all scenarios
  python3 scripts/device_e2e.py --skip-build    # reuse the installed APK
  python3 scripts/device_e2e.py --network       # also toggle airplane mode (restored afterwards)
  python3 scripts/device_e2e.py --reboot        # also reboot the phone and verify auto-start
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
from mock_backend import MockBackend  # noqa: E402

PKG = "com.sopifo.printagent"
ACTION = f"{PKG}.debug.COMMAND"
SERVICE = f"{PKG}/.service.PrintAgentService"


def find_adb():
    candidates = [os.environ.get("ADB"),
                  os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb"),
                  os.path.expanduser("~/Android/Sdk/platform-tools/adb"),
                  shutil.which("adb")]
    for c in candidates:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    sys.exit("adb not found; set ADB=/path/to/adb")


class Device:
    def __init__(self, adb, serial=None):
        self.base = [adb] + (["-s", serial] if serial else [])

    def run(self, *args, check=False, timeout=60):
        r = subprocess.run(self.base + list(args), capture_output=True, text=True, timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout.strip()

    def shell(self, cmd, timeout=60):
        return self.run("shell", cmd, timeout=timeout)

    def cmd(self, name, **extras):
        """Debug command into the app; returns the JSON status it reports."""
        parts = ["am", "broadcast", "--include-stopped-packages", "-a", ACTION, "-p", PKG, "--es", "cmd", name]
        for k, v in extras.items():
            parts += ["--es", k, str(v)]
        out = self.shell(" ".join(f"'{p}'" if " " in p else p for p in parts), timeout=30)
        m = re.search(r'data="(.*)"\s*$', out, re.S)
        if not m:
            raise RuntimeError(f"No result from debug command {name}: {out}")
        return json.loads(m.group(1))

    def pid(self):
        return self.shell(f"pidof {PKG}")

    def service_running(self):
        return "PrintAgentService" in self.shell(f"dumpsys activity services {PKG}")

    def virtual_prints(self):
        out = self.shell(f"run-as {PKG} ls files/virtual_prints 2>/dev/null")
        return [l for l in out.splitlines() if l.endswith(".bin")]


class Results:
    def __init__(self):
        self.items = []

    def check(self, name, ok, detail=""):
        self.items.append({"name": name, "ok": bool(ok), "detail": detail})
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail and not ok else ""), flush=True)
        return ok

    def skip(self, name, why):
        self.items.append({"name": name, "ok": None, "detail": why})
        print(f"  [SKIP] {name} — {why}", flush=True)


def wait_for(fn, timeout=30, interval=0.5):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        try:
            last = fn()
            if last:
                return last
        except Exception as e:  # device/app momentarily unavailable
            last = e
        time.sleep(interval)
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial")
    ap.add_argument("--port", type=int, default=18080)
    ap.add_argument("--skip-build", action="store_true")
    ap.add_argument("--network", action="store_true", help="toggle airplane mode to test reconnect recovery")
    ap.add_argument("--reboot", action="store_true", help="reboot the phone to test auto-start")
    ap.add_argument("--keep", action="store_true", help="leave the device registered afterwards")
    args = ap.parse_args()

    adb = find_adb()
    dev = Device(adb, args.serial)
    r = Results()

    state = dev.run("get-state")
    if state != "device":
        sys.exit(f"No authorised device connected (adb get-state: {state or 'none'})")
    model = dev.shell("getprop ro.product.model")
    sdk = dev.shell("getprop ro.build.version.sdk")
    print(f"Device: {model} (API {sdk})")

    # ---- install --------------------------------------------------------------------------
    if not args.skip_build:
        print("Building and installing debug APK…", flush=True)
        env = dict(os.environ, ANDROID_SERIAL=args.serial or os.environ.get("ANDROID_SERIAL", ""))
        b = subprocess.run(["./gradlew", "installDebug", "-q", "--console=plain"], cwd=ROOT, env=env, capture_output=True, text=True)
        if b.returncode != 0:
            print(b.stdout[-3000:], b.stderr[-3000:])
            sys.exit("Build/install failed")
    dev.shell(f"am force-stop {PKG}")
    dev.shell(f"pm clear {PKG}")
    for perm in ("android.permission.BLUETOOTH_CONNECT", "android.permission.POST_NOTIFICATIONS"):
        dev.shell(f"pm grant {PKG} {perm}")
    # Unattended deployments exempt the agent from battery optimisation (the app prompts for it).
    dev.shell(f"dumpsys deviceidle whitelist +{PKG}")
    dev.shell("input keyevent KEYCODE_WAKEUP")

    backend = MockBackend(args.port).start()
    dev.run("reverse", f"tcp:{args.port}", f"tcp:{args.port}", check=True)
    api = f"http://localhost:{args.port}"
    airplane_changed = False

    def launch_app():
        dev.shell(f"am start -n {PKG}/.ui.MainActivity --ez test_show_when_locked true")

    def job_status(jid):
        return (backend.job(jid) or {}).get("status")

    def wait_status(jid, status, timeout=40):
        return wait_for(lambda: job_status(jid) == status, timeout)

    try:
        # ---- 1. registration -----------------------------------------------------------------
        print("\n1. Registration")
        launch_app()
        time.sleep(2)
        bad = dev.cmd("register", token="INVALID", api=api)
        r.check("invalid token is rejected without crashing", not bad["ok"] and dev.pid(), bad.get("error", ""))
        st = dev.cmd("register", token="E2E-TOKEN-123", api=api)
        r.check("device registers with token", st["ok"] and st["registered"], json.dumps(st))
        reg = backend.events_of("register")[-1]["body"]
        r.check("registration sends device_name and app_version", reg.get("device_name") and reg.get("app_version"), json.dumps(reg))
        r.check("store name received from backend", st.get("store") == "E2E Test Store", str(st.get("store")))
        pr = dev.shell(f"run-as {PKG} cat shared_prefs/secure_session.xml")
        r.check("device JWT is stored encrypted (not in plaintext)", backend.device_jwt and backend.device_jwt not in pr and "device_jwt" in pr)

        # ---- 2. printers + service ----------------------------------------------------------
        print("\n2. Printers and foreground service")
        st = dev.cmd("virtual_printers")
        r.check("receipt printer connected", st["receipt"] == "connected", str(st["receipt"]))
        r.check("label printer connected", st["label"] == "connected", str(st["label"]))
        launch_app()
        r.check("foreground service running", wait_for(dev.service_running, 20))
        svc = dev.shell(f"dumpsys activity services {PKG}")
        # FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE = 0x10
        r.check("service is a connectedDevice foreground service",
                "isForeground=true" in svc and re.search(r"types=0x0*10\b", svc), svc[:300])

        # ---- 3. heartbeat -------------------------------------------------------------------
        print("\n3. Heartbeat")
        dev.cmd("heartbeat")
        hb = wait_for(lambda: backend.events_of("heartbeat"), 30)
        body = hb[-1]["body"] if hb else {}
        r.check("heartbeat delivered", bool(hb))
        r.check("heartbeat has battery_percent", isinstance(body.get("battery_percent"), int), json.dumps(body))
        r.check("heartbeat has printer_status", body.get("printer_status") == {"receipt": "connected", "label": "connected"}, json.dumps(body.get("printer_status")))
        r.check("heartbeat has app_version and last_seen", body.get("app_version") and body.get("last_seen"))
        st = dev.cmd("status")
        r.check("last sync updated", st.get("last_sync"))
        r.check("cloud status online", st.get("cloud_online") is True, str(st.get("cloud_online")))

        # ---- 4. FCM job flow ----------------------------------------------------------------
        print("\n4. FCM wake → fetch → download → print → report")
        dev.cmd("clear_prints")
        j1 = backend.create_job("receipt")
        dev.cmd("wake", job_id=j1)
        r.check("receipt job completed and reported", wait_status(j1, "completed"), str(job_status(j1)))
        r.check("job fetched from backend (not from FCM payload)", backend.events_of("get_job", job_id=j1))
        r.check("image downloaded from backend", backend.events_of("image", job_id=j1))
        prints = dev.virtual_prints()
        r.check("receipt printed exactly once", len(prints) == 1 and prints[0].endswith("-receipt.bin"), str(prints))

        # ---- 5. idempotency -----------------------------------------------------------------
        print("\n5. Idempotency")
        dev.cmd("wake", job_id=j1)
        dev.cmd("wake", job_id=j1)
        dev.cmd("sync")
        time.sleep(6)
        r.check("duplicate FCM wake-ups do not reprint", len(dev.virtual_prints()) == 1, str(dev.virtual_prints()))
        r.check("completion reported once", len(backend.events_of("complete", job_id=j1)) == 1)

        # ---- 6. routing ---------------------------------------------------------------------
        print("\n6. Printer routing")
        dev.cmd("clear_prints")
        routed = {t: backend.create_job(t) for t in ("label", "barcode_label", "qr_label", "scratchpad")}
        for jid in routed.values():
            dev.cmd("wake", job_id=jid)
        ok = all(wait_status(j, "completed") for j in routed.values())
        prints = dev.virtual_prints()
        r.check("all job types printed", ok, json.dumps({t: job_status(j) for t, j in routed.items()}))
        r.check("label/barcode_label/qr_label → label printer", sum(p.endswith("-label.bin") for p in prints) == 3, str(prints))
        r.check("scratchpad → receipt printer", sum(p.endswith("-receipt.bin") for p in prints) == 1, str(prints))

        # ---- 7. freshness -------------------------------------------------------------------
        print("\n7. Two-minute rule")
        dev.cmd("clear_prints")
        stale = backend.create_job("receipt", age_s=300, force_in_pending=True)
        stale2 = backend.create_job("label", age_s=130)
        dev.cmd("sync")
        dev.cmd("wake", job_id=stale2)
        time.sleep(8)
        r.check("stale jobs (> 120 s) are never printed", not dev.virtual_prints() and job_status(stale) == "pending" and job_status(stale2) == "pending")
        r.check("stale job listed as expired in history", f"{stale}:expired" in dev.cmd("status")["recent"])

        # ---- 8. failure handling ------------------------------------------------------------
        print("\n8. Crash resistance: malformed jobs, bad images")
        pid_before = dev.pid()
        bogus = backend.create_job("invoice")
        dev.cmd("wake", job_id=bogus)
        r.check("malformed job reported as failed", wait_status(bogus, "failed"), str(job_status(bogus)))
        broken = backend.create_job("receipt", image_fail=True)
        dev.cmd("wake", job_id=broken)
        r.check("image download failure reported as failed", wait_status(broken, "failed", 60), str(job_status(broken)))
        fail_body = (backend.events_of("fail", job_id=broken) or [{}])[-1].get("body", {})
        r.check("failure reason sent to backend", "image" in json.dumps(fail_body), json.dumps(fail_body))
        dev.cmd("wake", job_id="does_not_exist")
        dev.cmd("wake", job_id="../../etc/passwd")
        time.sleep(3)
        r.check("app survived all malformed input (same process)", dev.pid() == pid_before, f"{pid_before} → {dev.pid()}")
        r.check("nothing printed for failed jobs", not dev.virtual_prints(), str(dev.virtual_prints()))

        # ---- 9. cancellation ----------------------------------------------------------------
        print("\n9. Cancellation")
        cj = backend.create_job("receipt")
        backend.jobs[cj]["status"] = "cancelled"  # cancelled from the dashboard / Pending screen
        dev.cmd("wake", job_id=cj)
        time.sleep(5)
        r.check("cancelled job is not printed", not dev.virtual_prints() and job_status(cj) == "cancelled")

        # ---- 10. pending recovery on app start ----------------------------------------------
        print("\n10. Pending-job recovery on app start (no FCM)")
        dev.cmd("clear_prints")
        dev.shell(f"am force-stop {PKG}")
        missed = [backend.create_job("receipt"), backend.create_job("qr_label")]
        launch_app()
        ok = all(wait_status(j, "completed", 60) for j in missed)
        r.check("jobs missed while app was stopped print on start", ok, json.dumps({j: job_status(j) for j in missed}))
        r.check("each printed once", len(dev.virtual_prints()) == 2, str(dev.virtual_prints()))
        r.check("service restarted with app", wait_for(dev.service_running, 20))

        # ---- 11. session rejection ----------------------------------------------------------
        print("\n11. Server rejects session (401)")
        backend.reject_auth = True
        dev.cmd("heartbeat")
        r.check("session marked invalid after 401", wait_for(lambda: dev.cmd("status")["session_valid"] is False, 30))
        backend.reject_auth = False
        dev.cmd("heartbeat")
        r.check("session valid again once backend accepts it", wait_for(lambda: dev.cmd("status")["session_valid"] is True, 30))
        r.check("app still alive after auth failures", dev.pid())

        # ---- 12. crash recovery -------------------------------------------------------------
        print("\n12. Crash recovery")
        dev.cmd("clear_prints")
        pid_before = dev.pid()
        out = dev.shell(f"am crash {PKG}")
        if "Unknown command" in out or "Error" in out:
            r.skip("process crash recovery", f"`am crash` unsupported: {out[:80]}")
        else:
            back = wait_for(lambda: dev.pid() and dev.pid() != pid_before, 45, 1)
            r.check("process restarts by itself after a crash", back, f"pid before {pid_before}, after {dev.pid()}")
            r.check("foreground service restored after crash", wait_for(dev.service_running, 45, 1))
            logs = dev.shell(f"run-as {PKG} cat files/logs/agent.log")
            r.check("crash written to structured log", '"tag":"Crash"' in logs)
            j = backend.create_job("receipt")
            dev.cmd("wake", job_id=j)
            r.check("printing works after crash recovery", wait_status(j, "completed"), str(job_status(j)))

        # ---- 13. battery hygiene ------------------------------------------------------------
        print("\n13. Battery hygiene (idle)")
        time.sleep(20)  # past the HTTP keep-alive window
        power = dev.shell("dumpsys power")
        wl = [l for l in power.splitlines() if PKG in l and ("WAKE_LOCK" in l.upper() or "WakeLock" in l)]
        r.check("no wake locks held while idle", not wl, " | ".join(wl)[:300])
        # WorkManager may hold a one-shot bookkeeping alarm; only repeating alarms would mean polling.
        alarm_lines = dev.shell("dumpsys alarm").splitlines()
        ours = [i for i, l in enumerate(alarm_lines) if PKG in l]
        repeating = [alarm_lines[i] for i in ours
                     if any(re.search(r"repeatInterval=(?!0\b)\d+", l) for l in alarm_lines[i:i + 6])]
        r.check("no repeating AlarmManager polling", not repeating, " | ".join(repeating)[:300])
        m = re.search(r"(?:userId|appId)=(\d+)", dev.shell(f"dumpsys package {PKG}"))
        uid = m.group(1) if m else None
        def open_sockets():
            return dev.shell(f"cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '$8 == {uid} && $4 == \"01\"' | wc -l").strip()
        # Background work (reports, heartbeat) may briefly hold a connection; it must be released once idle.
        released = wait_for(lambda: open_sockets() == "0", 45, 3) if uid else True
        r.check("network connections released when idle", released, f"{open_sockets() if uid else '?'} established sockets")

        # ---- 14. network reconnect (optional) -----------------------------------------------
        print("\n14. Network reconnect")
        if not args.network:
            r.skip("pending sync on network reconnect", "run with --network")
        else:
            dev.cmd("clear_prints")
            out = dev.shell("cmd connectivity airplane-mode enable")
            if "Unknown" in out or "Exception" in out:
                r.skip("pending sync on network reconnect", f"airplane mode not controllable: {out[:80]}")
            else:
                airplane_changed = True
                time.sleep(5)
                jn = backend.create_job("receipt")
                dev.shell("cmd connectivity airplane-mode disable")
                airplane_changed = False
                r.check("job created while offline prints after reconnect", wait_status(jn, "completed", 90), str(job_status(jn)))

        # ---- 15. reboot (optional) ----------------------------------------------------------
        print("\n15. Auto-start after reboot")
        if not args.reboot:
            r.skip("auto-start after reboot", "run with --reboot")
        else:
            dev.cmd("clear_prints")
            dev.run("reboot")
            dev.run("wait-for-device", timeout=300)
            wait_for(lambda: dev.shell("getprop sys.boot_completed") == "1", 180, 2)
            dev.run("reverse", f"tcp:{args.port}", f"tcp:{args.port}")
            jb = backend.create_job("receipt")
            r.check("foreground service auto-started after boot (no user action)", wait_for(dev.service_running, 120, 2))
            r.check("pending job printed after boot", wait_status(jb, "completed", 120), str(job_status(jb)))

    finally:
        if airplane_changed:
            dev.shell("cmd connectivity airplane-mode disable")
        if not args.keep:
            try:
                dev.cmd("unregister")
            except Exception:
                pass
            dev.shell(f"dumpsys deviceidle whitelist -{PKG}")
        dev.run("reverse", "--remove", f"tcp:{args.port}")
        backend.stop()

    passed = sum(1 for i in r.items if i["ok"] is True)
    failed = [i for i in r.items if i["ok"] is False]
    skipped = sum(1 for i in r.items if i["ok"] is None)
    out_dir = ROOT / "scripts" / "out"
    out_dir.mkdir(exist_ok=True)
    report = {"device": model, "api": sdk, "passed": passed, "failed": len(failed), "skipped": skipped, "checks": r.items}
    (out_dir / "e2e-report.json").write_text(json.dumps(report, indent=2))
    print(f"\nE2E: {passed} passed, {len(failed)} failed, {skipped} skipped  →  scripts/out/e2e-report.json")
    for f in failed:
        print(f"  FAILED: {f['name']}  {f['detail']}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
