#!/usr/bin/env python3
"""A fake tower for trying the app without the Mac's daemon.

Speaks the phone API (v1) from herder-tower's docs/mobile-api.md closely
enough to pair, browse, ask, answer and hear audio: HTTPS with a
self-signed ECDSA P-256 certificate, a pairing code, bearer tokens, SSE
with pings, and 24 kHz WAV clips (a short tone per sentence).

    python3 tools/fake_tower.py [--host 127.0.0.1] [--port 7899]

It prints the pairing link. From the emulator, the Mac is 10.0.2.2:

    adb shell am start -d "herdertower://pair?h=10.0.2.2&p=7899&c=...&fp=..."
"""
import argparse, hashlib, io, json, math, os, queue, secrets, ssl, struct, subprocess, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

HERE = os.path.dirname(os.path.abspath(__file__))
CERT_DIR = os.path.join(HERE, ".fake_tower")
now = lambda: int(time.time() * 1000)

CODE = f"{secrets.randbelow(10**8):08d}"
TOKENS = {}  # token -> device
SUBS = []  # (queue, voice)
CLIPS = {}
LOCK = threading.Lock()
SEQ = [0]

STATE = {
    "version": 1, "updated_ms": now(), "pid": os.getpid(), "running": True, "phase": "idle",
    "mode": "ptt", "mic": "closed", "armed": False, "transcribing": False, "speaking": False, "announcing": False,
    "brain": "done", "brain_app": "claude-code", "hearing": "", "heard": "", "heard_ms": 0,
    "said": "", "said_ms": 0, "alerts": [],
    "agents": [
        {"address": "t3", "name": "api", "status": "blocked", "app": "claude-code", "account": "eyz"},
        {"address": "t5", "name": "web", "status": "working", "app": "claude-code", "account": "main"},
        {"address": "vps/t2", "name": "deploy", "status": "idle", "app": "codex"},
    ],
    "error": "", "talk_key": "ctrl", "voice": "af_bella", "stt_model": "fake", "lan": "", "phone": None,
}
HISTORY = [
    {"at": now() - 3_600_000, "kind": "request", "text": "[voice] what's everyone doing?"},
    {"at": now() - 3_590_000, "kind": "answer", "text": "api needs you, web is building the landing page, deploy is idle."},
    {"at": now() - 3_000_000, "kind": "job", "session": "t5", "name": "web", "space": "B2C support", "status": "working", "text": "build the landing page", "account": "main"},
    {"at": now() - 600_000, "kind": "job", "session": "t3", "name": "api", "space": "B2C support", "status": "blocked", "text": "wants to run the migration", "account": "eyz"},
    {"at": now() - 300_000, "kind": "summary", "session": "vps/t2", "name": "deploy", "space": "Infra", "text": "Deployed v2.3 to staging."},
    {"at": now() - 60_000, "kind": "alert", "text": "api is blocked: it asks to run the migration"},
]
JOBS = [
    {"job": "j2", "session": "t3", "name": "api", "space": "B2C support", "account": "eyz", "status": "blocked", "asked": "Fix the failing migration", "summary": "", "started_ms": now() - 900_000, "updated_ms": now() - 600_000},
    {"job": "j1", "session": "t5", "name": "web", "space": "B2C support", "account": "main", "status": "working", "asked": "Build the landing page", "summary": "", "started_ms": now() - 3_000_000, "updated_ms": now() - 100_000},
    {"job": "j0", "session": "vps/t2", "name": "deploy", "space": "Infra", "account": None, "status": "done", "asked": "Deploy v2.3 to staging", "summary": "Deployed; health checks green.", "started_ms": now() - 7_200_000, "updated_ms": now() - 6_000_000},
]
FOCUS = [None]
SCREEN = """╭─ Claude Code ─────────────────────────╮
│ Bash command                                   │
│   npm run migrate -- --env staging             │
│ Do you want to proceed?                        │
│ ❯ 1. Yes                                       │
│   2. No, and tell Claude what to do differently │
╰────────────────────────────────────────────────╯"""


def spaces():
    rows = []
    for name, sess in [("B2C support", ["t3", "t5"]), ("Infra", ["vps/t2"])]:
        ss = [{"address": a["address"], "name": a["name"], "status": a["status"], "account": a.get("account")} for a in STATE["agents"] if a["address"] in sess]
        rows.append({"space": name, "focus": FOCUS[0] == name, "agenda": "Keep the support site and API healthy." if name == "B2C support" else None,
                     "unread": 2 if name == "Infra" else 0, "running_jobs": sum(1 for j in JOBS if j["space"] == name and j["status"] in ("working", "blocked")),
                     "sessions": ss, "last_activity_ms": now() - (60_000 if name == "B2C support" else 300_000)})
    return {"focus": FOCUS[0], "spaces": rows}


def tone(ms, hz):
    rate = 24000
    n = rate * ms // 1000
    pcm = b"".join(struct.pack("<h", int(6000 * math.sin(2 * math.pi * hz * i / rate) * min(1, i / 600, (n - i) / 600))) for i in range(n))
    return b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16) + b"data" + struct.pack("<I", len(pcm)) + pcm


def publish(event, data, voice_only=False):
    with LOCK:
        for q, voice in list(SUBS):
            if voice or not voice_only:
                q.put((event, data))


def set_state(**kw):
    STATE.update(kw)
    STATE["version"] += 1
    STATE["updated_ms"] = now()
    publish("state", dict(STATE))


def add_history(e):
    HISTORY.append(e)
    publish("history", e)


def speak(sentences, kind="reply"):
    voice = any(v for _, v in SUBS)
    set_state(phase="speaking", speaking=True)
    for i, s in enumerate(sentences):
        if voice:
            cid = f"a{secrets.token_hex(3)}"
            ms = 400 + 40 * len(s)
            CLIPS[cid] = tone(min(ms, 2500), 440 + 60 * i)
            publish("audio", {"id": cid, "kind": kind, "text": s, "ms": ms, "url": f"/v1/audio/{cid}"}, voice_only=True)
        time.sleep(0.3)
    set_state(phase="idle", speaking=False, said=" ".join(sentences), said_ms=now())


def reply_to(text):
    add_history({"at": now(), "kind": "request", "text": "[voice] " + text})
    if any(v for _, v in SUBS):
        cid = f"a{secrets.token_hex(3)}"
        CLIPS[cid] = tone(300, 660)
        publish("audio", {"id": cid, "kind": "ack", "text": "Okay.", "ms": 300, "url": f"/v1/audio/{cid}"}, voice_only=True)
    set_state(phase="thinking")
    time.sleep(1.5)
    answer = ["You said: " + text + ".", "This is the fake tower, so nothing really happened."]
    add_history({"at": now(), "kind": "answer", "text": " ".join(answer)})
    speak(answer)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        sys.stderr.write("%s %s\n" % (self.command, self.path))

    def send(self, code, obj=None, ctype="application/json", body=None):
        body = body if body is not None else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def err(self, code, c, msg, **extra):
        self.send(code, {"error": {"code": c, "message": msg, **extra}})

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def device(self):
        auth = self.headers.get("Authorization", "")
        return TOKENS.get(auth[7:]) if auth.startswith("Bearer ") else None

    def route(self, method):
        u = urlparse(self.path)
        seg = [unquote(s) for s in u.path.split("/")[1:]]
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if seg[:1] != ["v1"]:
            return self.err(404, "not_found", "no such path")
        seg = seg[1:]
        if (method, seg) == ("POST", ["pair"]):
            b = json.loads(self.body() or b"{}")
            if b.get("code") != CODE:
                return self.err(403, "wrong_code", "that isn't the code on the Mac", tries_left=4)
            tok = "htk_" + secrets.token_urlsafe(32)
            dev = {"id": "d_" + secrets.token_hex(4), "name": b.get("name", "phone")}
            TOKENS[tok] = dev
            print(f"paired {dev['name']} ({dev['id']})", flush=True)
            return self.send(200, {"token": tok, "device": dev, "fingerprint": FP, "api": 1})
        dev = self.device()
        if dev is None:
            return self.err(401, "unauthorized", "unknown token")
        if method == "GET":
            if seg == ["hello"]:
                return self.send(200, {"api": 1, "tower": "fake", "device": dev, "voice": "af_bella", "phone_is_voice": any(v for _, v in SUBS)})
            if seg == ["state"]:
                return self.send(200, STATE)
            if seg == ["agents"]:
                ag = [{"address": a["address"], "account": a.get("account"), "machine": "vps" if "/" in a["address"] else "this mac", "name": a["name"],
                       "spoken": a["name"], "agent": a["app"], "status": a["status"], "doing": "Waiting for approval" if a["status"] == "blocked" else None,
                       "title": a["name"], "folder": "/work/" + a["name"], "where": {"space": "x"}} for a in STATE["agents"]]
                return self.send(200, {"agents": ag, "unreachable": []})
            if seg == ["spaces"]:
                return self.send(200, spaces())
            if seg == ["jobs"]:
                return self.send(200, {"jobs": JOBS})
            if seg == ["history"]:
                before = int(q.get("before_ms", 2**62))
                lim = int(q.get("limit", 100))
                return self.send(200, {"entries": [e for e in HISTORY if e["at"] < before][-lim:]})
            if seg == ["accounts"]:
                return self.send(200, {"default": "main", "accounts": [{"name": "main", "email": "me@example.com"}, {"name": "eyz", "email": "work@example.com"}]})
            if len(seg) == 3 and seg[0] == "sessions" and seg[2] == "screen":
                return self.send(200, {"address": seg[1], "screen": SCREEN if seg[1] == "t3" else f"$ # {seg[1]} at {time.strftime('%H:%M:%S')}\n"})
            if len(seg) == 2 and seg[0] == "audio":
                wav = CLIPS.get(seg[1])
                return self.send(200, ctype="audio/wav", body=wav) if wav else self.err(404, "no_clip", "gone")
            if seg == ["events"]:
                return self.events(dev, q.get("voice") == "1")
        if method == "POST":
            b = self.body()
            if seg == ["ask"]:
                t = json.loads(b)["text"]
                threading.Thread(target=reply_to, args=(t,), daemon=True).start()
                return self.send(202, {"sent": t})
            if len(seg) == 3 and seg[0] == "agents" and seg[2] == "answer":
                d = json.loads(b)
                for a in STATE["agents"]:
                    if a["address"] == seg[1]:
                        a["status"] = "working"
                set_state()
                add_history({"at": now(), "kind": "request", "text": f"[voice] {'Approve' if d['decision'] == 'approve' else 'Refuse'} what {seg[1]} is asking for. {d.get('note', '')}".strip()})
                return self.send(202, {})
            if seg == ["focus"]:
                FOCUS[0] = json.loads(b).get("space")
                return self.send(200, {"focus": FOCUS[0]})
            if seg == ["hush"]:
                publish("hush", {}, voice_only=True)
                return self.send(200, {"ok": True})
            if seg == ["mode"]:
                set_state(mode=json.loads(b)["mode"])
                return self.send(200, {"mode": STATE["mode"]})
            if seg == ["voice"]:
                if b[:4] != b"RIFF":
                    return self.err(415, "bad_audio", "send audio/wav")
                secs = (len(b) - 44) / 32000
                heard = f"a {secs:.1f} second take"
                set_state(heard=heard, heard_ms=now())
                threading.Thread(target=reply_to, args=(heard,), daemon=True).start()
                return self.send(200, {"heard": heard, "sent": True})
        if (method, seg) == ("DELETE", ["device"]):
            for t, d in list(TOKENS.items()):
                if d is dev:
                    del TOKENS[t]
            return self.send(200, {"revoked": dev["id"]})
        return self.err(404, "not_found", f"no {method} {u.path}")

    def events(self, dev, voice):
        q = queue.Queue()
        with LOCK:
            SUBS.append((q, voice))
        if voice:
            STATE["phone"] = dev["name"]
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        n = 0

        def out(ev, data):
            nonlocal n
            n += 1
            self.wfile.write(f"id: {n}\nevent: {ev}\ndata: {json.dumps(data)}\n\n".encode())
            self.wfile.flush()

        try:
            out("hello", {"api": 1, "device": dev["id"], "voice": voice})
            out("state", STATE)
            last = time.time()
            while True:
                try:
                    out(*q.get(timeout=1))
                except queue.Empty:
                    pass
                if time.time() - last > 10:
                    self.wfile.write(b": ping\n\n")
                    self.wfile.flush()
                    last = time.time()
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError, OSError):
            pass
        finally:
            with LOCK:
                SUBS[:] = [s for s in SUBS if s[0] is not q]
            print(f"stream closed ({'voice' if voice else 'panel'})", flush=True)
        self.close_connection = True

    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def do_DELETE(self):
        self.route("DELETE")


def cert():
    os.makedirs(CERT_DIR, exist_ok=True)
    crt, key = os.path.join(CERT_DIR, "cert.pem"), os.path.join(CERT_DIR, "key.pem")
    if not os.path.exists(crt):
        subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
                        "-keyout", key, "-out", crt, "-days", "3650", "-subj", "/CN=herder-tower"], check=True, capture_output=True)
    der = ssl.PEM_cert_to_DER_cert(open(crt).read())
    return crt, key, hashlib.sha256(der).hexdigest()


def ticker():
    """Something happening now and then: web finishes a step, an alert."""
    i = 0
    while True:
        time.sleep(25)
        i += 1
        add_history({"at": now(), "kind": "watch", "session": "t5", "name": "web", "space": "B2C support", "account": "main", "text": f"web: built section {i} of the landing page"})


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=7899)
    ap.add_argument("--link-host", default="10.0.2.2", help="the address the phone should use (10.0.2.2 = this Mac, from the emulator)")
    a = ap.parse_args()
    crt, key, FP = cert()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(crt, key)
    srv = ThreadingHTTPServer((a.host, a.port), H)
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    threading.Thread(target=ticker, daemon=True).start()
    link = f"herdertower://pair?h={a.link_host}&p={a.port}&c={CODE}&fp={FP}"
    print(f"fake tower on https://{a.host}:{a.port}\ncode {CODE}\nfingerprint {' '.join(FP[i:i+4] for i in range(0, 64, 4))}\nLINK {link}", flush=True)
    srv.serve_forever()
