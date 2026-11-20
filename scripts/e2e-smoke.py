#!/usr/bin/env python3
"""
End-to-end API smoke test against a RUNNING backend (e.g. the docker compose stack).
Uses only the Python standard library. Creates two throwaway accounts.

    python3 scripts/e2e-smoke.py http://127.0.0.1:8080      # REQUIRE_HTTPS=false (dev)
    python3 scripts/e2e-smoke.py https://api.example.com

Covers: parent registration, child registration, pairing, usage sync, parent dashboard data,
screen-sharing request/approve/status/stop (REST state machine; media needs real devices),
safety alerts, and device revocation. WebSocket signaling is covered by the backend test suite.
"""
import json
import sys
import urllib.error
import urllib.request
import uuid
from datetime import date, timezone, datetime

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
# --forwarded-https: act like a TLS-terminating proxy (adds X-Forwarded-Proto: https) when calling the backend directly.
FORWARDED_HTTPS = "--forwarded-https" in sys.argv
BASE = (ARGS[0] if ARGS else "http://127.0.0.1:8080").rstrip("/")
PASSWORD = "smoke test password " + uuid.uuid4().hex[:8]


def call(method, path, body=None, token=None, expect=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if FORWARDED_HTTPS:
        req.add_header("X-Forwarded-Proto", "https")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    payload = json.loads(raw) if raw else None
    if expect is not None and status != expect:
        raise SystemExit(f"FAIL {method} {path}: expected {expect}, got {status}: {payload}")
    return payload


def device():
    return {"installationId": str(uuid.uuid4()), "deviceName": "Smoke test device", "osVersion": "smoke"}


def register(role, name):
    email = f"smoke-{role.lower()}-{uuid.uuid4().hex[:10]}@example.test"
    r = call("POST", "/api/v1/auth/register",
             {"email": email, "password": PASSWORD, "displayName": name, "role": role, "device": device()}, expect=201)
    return r


def step(n, text):
    print(f"[{n:>2}] {text}")


def main():
    step(1, "parent registration")
    parent = register("PARENT", "Smoke Parent")
    pt = parent["accessToken"]
    step(2, "child registration")
    child = register("CHILD", "Smoke Child")
    ct, child_device = child["accessToken"], child["deviceId"]

    step(3, "pairing (parent creates code, child accepts)")
    code = call("POST", "/api/v1/pairing/create", {"childLabel": "Smoke"}, pt, expect=201)["code"]
    call("POST", "/api/v1/pairing/accept", {"code": code}, ct, expect=200)
    assert call("POST", "/api/v1/pairing/accept", {"code": code}, ct)["code"] in ("INVALID_PAIRING_CODE", "DEVICE_ALREADY_PAIRED")

    step(4, "usage-data synchronisation")
    today = date.today().isoformat()
    now = datetime.now(timezone.utc).isoformat()
    call("POST", "/api/v1/usage/sync", {"timezone": "UTC", "days": [{"date": today, "apps": [
        {"packageName": "org.example.reader", "appLabel": "Reader", "foregroundMs": 600000, "launchCount": 2, "lastUsedAt": now}]}]},
         ct, expect=200)

    step(5, "parent dashboard data")
    devices = call("GET", "/api/v1/devices", token=pt, expect=200)
    assert devices and devices[0]["id"] == child_device, devices
    daily = call("GET", f"/api/v1/usage/daily?deviceId={child_device}&date={today}", token=pt, expect=200)
    assert daily["totalForegroundMs"] >= 600000, daily
    call("GET", f"/api/v1/usage/weekly?deviceId={child_device}&endDate={today}", token=pt, expect=200)

    step(6, "screen-sharing request")
    session = call("POST", "/api/v1/screen-sharing/request", {"deviceId": child_device}, pt, expect=201)
    step(7, "child approval")
    approved = call("POST", "/api/v1/screen-sharing/approve", {"sessionId": session["id"]}, ct, expect=200)
    assert approved["status"] == "APPROVED" and approved.get("iceServers"), approved
    step(8, "live transmission: signaling/media require two devices (see TESTING.md); checking status only")
    status = call("GET", f"/api/v1/screen-sharing/status?sessionId={session['id']}", token=pt, expect=200)
    assert status["active"]["status"] in ("APPROVED", "ACTIVE"), status
    step(9, "session termination")
    stopped = call("POST", "/api/v1/screen-sharing/stop", {"sessionId": session["id"]}, ct, expect=200)
    assert stopped["status"] == "STOPPED", stopped

    print("     safety: child check-in reaches parent")
    call("POST", "/api/v1/safety/alerts", {"type": "CHECK_IN", "message": "smoke"}, ct, expect=201)
    alerts = call("GET", "/api/v1/safety/alerts", token=pt, expect=200)["alerts"]
    assert any(a["type"] == "CHECK_IN" for a in alerts), alerts

    step(10, "device revocation")
    call("DELETE", f"/api/v1/devices/{child_device}", {"reason": "smoke"}, pt, expect=204)
    call("GET", "/api/v1/auth/me", token=ct, expect=401)
    print("PASS: all smoke steps succeeded against", BASE)


if __name__ == "__main__":
    main()
