#!/usr/bin/env python3
"""
Mock Sopifo Cloud print backend (stdlib only).

Implements the contract the Android print agent expects, records every call so tests can
assert on it, and exposes /__admin endpoints to create jobs and inject failures.

Run standalone:   python3 scripts/mock_backend.py --port 18080
Phone access:     adb reverse tcp:18080 tcp:18080   (debug builds allow http://localhost only)
"""
import argparse
import json
import secrets
import struct
import threading
import time
import zlib
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

MAX_PENDING_AGE_S = 120


def make_png(width=576, height=160):
    """Black/white test artwork: a border, stripes and a solid block. Pure-python PNG encoder."""
    rows = []
    for y in range(height):
        row = bytearray([0])  # filter: none
        for x in range(width):
            black = (
                x < 4 or x >= width - 4 or y < 4 or y >= height - 4
                or (20 <= y < 60 and (x // 16) % 2 == 0)
                or (80 <= y < 140 and 20 <= x < width // 2)
            )
            row.append(0 if black else 255)
        rows.append(bytes(row))
    raw = zlib.compress(b"".join(rows), 9)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)  # 8-bit greyscale
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", raw) + chunk(b"IEND", b"")


def iso(ts):
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat().replace("+00:00", "Z")


class MockBackend:
    def __init__(self, port=18080):
        self.port = port
        self.lock = threading.Lock()
        self.png = make_png()
        self.label_png = make_png(400, 240)
        self.reset()
        self.httpd = None

    # ----- state -------------------------------------------------------------------------
    def reset(self):
        with getattr(self, "lock", threading.Lock()):
            self.jobs = {}
            self.events = []
            self.device_jwt = None
            self.reject_auth = False
            self.seq = 0

    def record(self, kind, **data):
        with self.lock:
            self.events.append({"kind": kind, "at": time.time(), **data})

    def events_of(self, kind, **match):
        with self.lock:
            return [e for e in self.events if e["kind"] == kind and all(e.get(k) == v for k, v in match.items())]

    def create_job(self, type_="receipt", age_s=0, status="pending", image_fail=False,
                   force_in_pending=False, job_id=None, copies=1):
        with self.lock:
            self.seq += 1
            jid = job_id or f"job_{int(time.time())}_{self.seq}"
            self.jobs[jid] = {
                "id": jid,
                "type": type_,
                "status": status,
                "created_ts": time.time() - age_s,
                "image_fail": image_fail,
                "force_in_pending": force_in_pending,
                "copies": copies,
            }
            return jid

    def job(self, jid):
        with self.lock:
            j = self.jobs.get(jid)
            return dict(j) if j else None

    def job_json(self, j):
        return {
            "id": j["id"],
            "type": j["type"],
            "status": j["status"],
            "image_url": f"http://localhost:{self.port}/images/{j['id']}.png",
            "created_at": iso(j["created_ts"]),
            "copies": j["copies"],
        }

    # ----- server ------------------------------------------------------------------------
    def start(self):
        backend = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def _send(self, code, body=b"", ctype="application/json"):
                if isinstance(body, (dict, list)):
                    body = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _body(self):
                n = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(n) if n else b""
                try:
                    return json.loads(raw or b"{}")
                except ValueError:
                    return {"_raw": raw.decode(errors="replace")}

            def _authorized(self):
                auth = self.headers.get("Authorization", "")
                ok = (not backend.reject_auth) and backend.device_jwt and auth == f"Bearer {backend.device_jwt}"
                if not ok:
                    backend.record("unauthorized", path=self.path)
                    self._send(401, {"error": "unauthorized"})
                return ok

            def do_GET(self):
                path = urlparse(self.path).path
                if path == "/__admin/state":
                    with backend.lock:
                        return self._send(200, {"jobs": list(backend.jobs.values()), "events": backend.events})
                if path.startswith("/images/"):
                    jid = path[len("/images/"):-len(".png")]
                    j = backend.job(jid)
                    backend.record("image", job_id=jid)
                    if not j:
                        return self._send(404, {"error": "no job"})
                    if j["image_fail"]:
                        return self._send(500, {"error": "storage unavailable"})
                    png = backend.label_png if "label" in j["type"] else backend.png
                    return self._send(200, png, "image/png")
                if not self._authorized():
                    return
                if path == "/api/print-jobs/pending":
                    now = time.time()
                    with backend.lock:
                        jobs = [backend.job_json(j) for j in backend.jobs.values()
                                if j["status"] == "pending" and (now - j["created_ts"] <= MAX_PENDING_AGE_S or j["force_in_pending"])]
                    backend.record("pending", count=len(jobs))
                    return self._send(200, {"jobs": jobs})
                if path.startswith("/api/print-jobs/"):
                    jid = path[len("/api/print-jobs/"):]
                    j = backend.job(jid)
                    backend.record("get_job", job_id=jid)
                    if not j:
                        return self._send(404, {"error": "not found"})
                    return self._send(200, {"data": backend.job_json(j)})
                return self._send(404, {"error": "not found"})

            def do_POST(self):
                path = urlparse(self.path).path
                body = self._body()
                if path == "/__admin/jobs":
                    jid = backend.create_job(body.get("type", "receipt"), body.get("age_s", 0), body.get("status", "pending"),
                                             body.get("image_fail", False), body.get("force_in_pending", False))
                    return self._send(200, backend.job_json(backend.job(jid)))
                if path == "/__admin/config":
                    backend.reject_auth = bool(body.get("reject_auth", False))
                    return self._send(200, {"reject_auth": backend.reject_auth})
                if path == "/__admin/reset":
                    backend.reset()
                    return self._send(200, {})
                if path == "/api/devices/register":
                    backend.record("register", body=body)
                    if body.get("token") in (None, "", "INVALID"):
                        return self._send(401, {"error": "invalid token"})
                    backend.device_jwt = "jwt_" + secrets.token_hex(16)
                    return self._send(200, {"device_id": "dev_e2e", "device_jwt": backend.device_jwt,
                                            "store_name": "E2E Test Store", "device_name": body.get("device_name")})
                if not self._authorized():
                    return
                if path == "/api/devices/heartbeat":
                    backend.record("heartbeat", body=body)
                    return self._send(200, {"ok": True})
                if path == "/api/devices/fcm-token":
                    backend.record("fcm_token", body=body)
                    return self._send(200, {"ok": True})
                if path.startswith("/api/print-jobs/"):
                    rest = path[len("/api/print-jobs/"):]
                    jid, _, action = rest.partition("/")
                    with backend.lock:
                        j = backend.jobs.get(jid)
                        if not j:
                            return self._send(404, {"error": "not found"})
                        if action == "complete":
                            j["status"] = "completed"
                        elif action == "fail":
                            j["status"] = "failed"
                        elif action == "cancel":
                            if j["status"] != "pending":
                                return self._send(409, {"error": f"job is {j['status']}"})
                            j["status"] = "cancelled"
                        else:
                            return self._send(404, {"error": "unknown action"})
                    backend.record(action, job_id=jid, body=body)
                    return self._send(200, {"ok": True})
                return self._send(404, {"error": "not found"})

        self.httpd = ThreadingHTTPServer(("127.0.0.1", self.port), Handler)
        self.httpd.daemon_threads = True
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        return self

    def stop(self):
        if self.httpd:
            self.httpd.shutdown()
            self.httpd.server_close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=18080)
    args = ap.parse_args()
    MockBackend(args.port).start()
    print(f"Mock Sopifo backend on http://127.0.0.1:{args.port}  (Ctrl+C to stop)")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
