#!/usr/bin/env python3
"""A fake tower for trying the app (and taking its screenshots) without the Mac.

Speaks the phone API (v1) from herder-tower's docs/mobile-api.md closely
enough for every screen: HTTPS with a self-signed ECDSA P-256 certificate,
a pairing code, bearer tokens, SSE with pings, made-up sessions, spaces,
jobs, history (markdown answers, a message with a picture), screenshots,
the brain's `work`, transcription, multipart asks with files, and 24 kHz
WAV clips (a short tone per sentence). All data is invented.

    python3 tools/fake_tower.py [--host 127.0.0.1] [--port 7899]

It prints the pairing link. From the emulator, the Mac is 10.0.2.2:

    adb shell am start -a android.intent.action.VIEW -d '"herdertower://pair?h=10.0.2.2&p=7899&c=...&fp=..."' dev.herdertower.phone
"""
import argparse, hashlib, json, math, os, queue, secrets, ssl, struct, subprocess, sys, threading, time, zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

HERE = os.path.dirname(os.path.abspath(__file__))
CERT_DIR = os.path.join(HERE, ".fake_tower")
now = lambda: int(time.time() * 1000)
T0 = now()
MIN = 60_000

CODE = f"{secrets.randbelow(10**8):08d}"
TOKENS = {}  # token -> device
SUBS = []  # (queue, voice)
CLIPS = {}
LOCK = threading.Lock()

# --- Pictures: simple page mock-ups drawn as PNGs (no image files in the repo) ---

def png(w, h, rects, bg):
    """A PNG of size w x h: background bg, then (x, y, w, h, rgb) rectangles."""
    px = [bytearray(bg * w) for _ in range(h)]
    for (x, y, rw, rh, c) in rects:
        for yy in range(max(0, y), min(h, y + rh)):
            row = px[yy]
            for xx in range(max(0, x), min(w, x + rw)):
                row[xx * 3:xx * 3 + 3] = bytes(c)
    raw = b"".join(b"\x00" + bytes(r) for r in px)
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")

def page(accent, dark=False):
    """A web page mock-up: header, a hero, three cards, lines of text."""
    bg = (18, 20, 28) if dark else (246, 244, 240)
    fg = (40, 44, 60) if dark else (225, 222, 216)
    w, h = 360, 460
    r = [(0, 0, w, 44, accent), (16, 14, 80, 16, (255, 255, 255)), (250, 14, 94, 16, (255, 255, 255)),
         (16, 64, 328, 120, fg), (32, 92, 180, 18, accent), (32, 120, 240, 10, (150, 150, 160)), (32, 138, 200, 10, (150, 150, 160)),
         (32, 160, 90, 14, accent)]
    for i in range(3):
        r += [(16 + i * 112, 200, 104, 120, fg), (26 + i * 112, 212, 84, 52, accent), (26 + i * 112, 274, 70, 8, (150, 150, 160)), (26 + i * 112, 290, 50, 8, (150, 150, 160))]
    for i in range(6):
        r += [(16, 340 + i * 18, 300 - (i % 3) * 40, 8, (160, 160, 170))]
    return png(w, h, r, bg)

SHOT_PICS = {
    "1794160000001-checkout.png": page((88, 120, 220)),
    "1794160000002-dashboard.png": page((40, 160, 140), dark=True),
    "1794160000003-onboarding.png": page((210, 110, 60)),
}
ATTACHMENT = page((200, 80, 120))

# --- Made-up tower data ---

STATE = {
    "version": 1, "updated_ms": now(), "pid": os.getpid(), "running": True, "phase": "idle",
    "mode": "ptt", "mic": "closed", "armed": False, "transcribing": False, "speaking": False, "announcing": False,
    "brain": "done", "brain_app": "claude-code", "hearing": "", "heard": "", "heard_ms": 0,
    "said": "Web finished the checkout page. Api is waiting for your answer.", "said_ms": now() - 2 * MIN, "alerts": [],
    "agents": [
        {"address": "t3", "name": "api", "status": "blocked", "app": "claude-code", "account": "studio"},
        {"address": "t5", "name": "web", "status": "working", "app": "claude-code", "account": "main"},
        {"address": "t7", "name": "docs", "status": "working", "app": "claude-code", "account": "main"},
        {"address": "vps/t2", "name": "deploy", "status": "idle", "app": "codex"},
    ],
    "error": "", "talk_key": "ctrl", "voice": "af_bella", "stt_model": "fake", "lan": "", "phone": None,
    "screenshots": [], "work": None,
}
SHOTS = [
    {"id": "1794160000003-onboarding.png", "at": T0 - 4 * MIN, "session": "t5", "name": "web", "space": "Storefront", "account": "main", "job": "j1", "caption": "The new onboarding page, three steps"},
    {"id": "1794160000002-dashboard.png", "at": T0 - 25 * MIN, "session": "t7", "name": "docs", "space": "Storefront", "account": "main", "job": None, "caption": "Dashboard in dark mode"},
    {"id": "1794160000001-checkout.png", "at": T0 - 70 * MIN, "session": "t5", "name": "web", "space": "Storefront", "account": "main", "job": "j1", "caption": "Checkout with the new payment step"},
]
for s in SHOTS:
    s["path"] = "/tower/attachments/screenshots/" + s["id"]
    s["url"] = "/v1/screenshots/" + s["id"]
STATE["screenshots"] = SHOTS
HISTORY = [
    {"at": T0 - 95 * MIN, "kind": "request", "text": "[voice] what's everyone doing?"},
    {"at": T0 - 94 * MIN, "kind": "answer", "text": "Three sessions are busy:\n- **web** builds the checkout page\n- **api** is migrating the orders table\n- **docs** updates the guides\n\nDeploy is idle."},
    {"at": T0 - 80 * MIN, "kind": "job", "session": "t5", "name": "web", "space": "Storefront", "status": "working", "text": "build the checkout page", "account": "main"},
    {"at": T0 - 70 * MIN, "kind": "screenshot", "session": "t5", "name": "web", "space": "Storefront", "account": "main", "id": SHOTS[2]["id"], "caption": SHOTS[2]["caption"], "path": SHOTS[2]["path"], "text": SHOTS[2]["caption"]},
    {"at": T0 - 40 * MIN, "kind": "summary", "session": "vps/t2", "name": "deploy", "space": "Infra", "text": "Deployed v2.3 to staging; health checks green."},
    {"at": T0 - 12 * MIN, "kind": "request", "text": "[Image #1] the spacing on this page looks off, can web fix it?", "attachments": ["/tower/attachments/1794160000000-phone/1-page.png"]},
    {"at": T0 - 11 * MIN, "kind": "answer", "text": "Yes: the cards sit too close to the hero. I've asked **web** to add `24px` above them and to send a screenshot when it's done."},
    {"at": T0 - 4 * MIN, "kind": "screenshot", "session": "t5", "name": "web", "space": "Storefront", "account": "main", "id": SHOTS[0]["id"], "caption": SHOTS[0]["caption"], "path": SHOTS[0]["path"], "text": SHOTS[0]["caption"]},
    {"at": T0 - 3 * MIN, "kind": "job", "session": "t3", "name": "api", "space": "Storefront", "status": "blocked", "text": "wants to run the migration on staging", "account": "studio"},
    {"at": T0 - 2 * MIN, "kind": "answer", "text": "Web finished the onboarding page [shot:1794160000003-onboarding.png]\n\n**api** is waiting for you: it wants to run the migration on staging."},
]
JOBS = [
    {"job": "j2", "session": "t3", "name": "api", "space": "Storefront", "account": "studio", "status": "blocked", "asked": "Migrate the orders table", "summary": "", "started_ms": T0 - 30 * MIN, "updated_ms": T0 - 3 * MIN},
    {"job": "j1", "session": "t5", "name": "web", "space": "Storefront", "account": "main", "status": "working", "asked": "Build the checkout and onboarding pages", "summary": "", "started_ms": T0 - 80 * MIN, "updated_ms": T0 - 4 * MIN},
    {"job": "j3", "session": "t7", "name": "docs", "space": "Storefront", "account": "main", "status": "working", "asked": "Update the setup guide", "summary": "", "started_ms": T0 - 20 * MIN, "updated_ms": T0 - 5 * MIN},
    {"job": "j0", "session": "vps/t2", "name": "deploy", "space": "Infra", "account": None, "status": "done", "asked": "Deploy v2.3 to staging", "summary": "Deployed; health checks green.", "started_ms": T0 - 60 * MIN, "updated_ms": T0 - 40 * MIN},
]
FOCUS = [None]
SPACES = [("Storefront", ["t3", "t5", "t7"], "The shop: checkout, onboarding and the orders API."), ("Infra", ["vps/t2"], None)]
SCREEN = """╭─ Claude Code ────────────────────────╮
│ Bash command                                   │
│   npm run migrate -- --env staging             │
│ Do you want to proceed?                        │
│ ❯ 1. Yes                                       │
│   2. No, and tell Claude what to do differently │
╰────────────────────────────────────────────────╯"""


def spaces():
    rows = []
    for name, sess, agenda in SPACES:
        ss = [{"address": a["address"], "name": a["name"], "status": a["status"], "account": a.get("account")} for a in STATE["agents"] if a["address"] in sess]
        rows.append({"space": name, "focus": FOCUS[0] == name, "agenda": agenda, "unread": 2 if name == "Infra" else 0,
                     "running_jobs": sum(1 for j in JOBS if j["space"] == name and j["status"] in ("working", "blocked")),
                     "sessions": ss, "last_activity_ms": now() - (2 * MIN if name == "Storefront" else 40 * MIN)})
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


def speak(sentences):
    voice = any(v for _, v in SUBS)
    set_state(phase="speaking", speaking=True)
    for i, s in enumerate(sentences):
        if voice:
            cid = f"a{secrets.token_hex(3)}"
            ms = 400 + 40 * len(s)
            CLIPS[cid] = tone(min(ms, 2500), 440 + 60 * i)
            publish("audio", {"id": cid, "kind": "reply", "text": s, "ms": ms, "url": f"/v1/audio/{cid}"}, voice_only=True)
        time.sleep(0.3)
    set_state(phase="idle", speaking=False, said=" ".join(sentences), said_ms=now())


def reply_to(text, attachments=()):
    e = {"at": now(), "kind": "request", "text": "[voice] " + text}
    if attachments:
        e["attachments"] = list(attachments)
    add_history(e)
    if any(v for _, v in SUBS):
        cid = f"a{secrets.token_hex(3)}"
        CLIPS[cid] = tone(300, 660)
        publish("audio", {"id": cid, "kind": "ack", "text": "Okay.", "ms": 300, "url": f"/v1/audio/{cid}"}, voice_only=True)
    t = now()
    set_state(phase="thinking", work={"word": "Thinking", "detail": "", "since_ms": t, "at_ms": t, "done_ms": 0})
    time.sleep(1.5)
    set_state(work={"word": "Reading", "detail": "web", "since_ms": t, "at_ms": now(), "done_ms": 0})
    time.sleep(2.5)
    answer = ["You said: " + text + ".", "This is the fake tower, so nothing really happened."]
    add_history({"at": now(), "kind": "answer", "text": " ".join(answer)})
    set_state(work=None)
    speak(answer)


def multipart(body, ctype):
    """The text field and file parts of a multipart body: (text, [(filename, bytes)])."""
    boundary = ctype.split("boundary=")[-1].strip('"').encode()
    text, files = "", []
    for part in body.split(b"--" + boundary):
        head, _, data = part.partition(b"\r\n\r\n")
        data = data.rsplit(b"\r\n", 1)[0]
        h = head.decode("utf-8", "replace")
        if 'name="text"' in h and "filename=" not in h:
            text = data.decode("utf-8", "replace")
        elif "filename=" in h:
            files.append((h.split('filename="')[1].split('"')[0], data))
    return text, files


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
                return self.send(200, {"default": "main", "accounts": [{"name": "main", "email": "main@example.com"}, {"name": "studio", "email": "studio@example.com"}]})
            if seg == ["screenshots"]:
                s = q.get("session")
                return self.send(200, {"screenshots": [x for x in SHOTS if not s or x["session"] == s]})
            if len(seg) == 2 and seg[0] == "screenshots":
                pic = SHOT_PICS.get(seg[1])
                return self.send(200, ctype="image/png", body=pic) if pic else self.err(404, "no_screenshot", "gone")
            if len(seg) == 3 and seg[0] == "attachments":
                return self.send(200, ctype="image/png", body=ATTACHMENT) if seg[2].endswith(".png") else self.err(415, "not_an_image", "not a picture")
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
                ctype = self.headers.get("Content-Type", "")
                if ctype.startswith("multipart/"):
                    t, files = multipart(b, ctype)
                    paths = [f"/tower/attachments/{now()}-phone/{i + 1}-{n}" for i, (n, _) in enumerate(files)]
                else:
                    t, paths = json.loads(b)["text"], []
                threading.Thread(target=reply_to, args=(t, paths), daemon=True).start()
                return self.send(202, {"sent": t, "attachments": paths})
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
            if seg in (["voice"], ["transcribe"]):
                if b[:4] != b"RIFF" and not self.headers.get("Content-Type", "").startswith("multipart/"):
                    return self.err(415, "bad_audio", "send audio/wav")
                secs = max(0.0, (len(b) - 44) / 32000)
                heard = f"a {secs:.1f} second take"
                if seg == ["transcribe"] or q.get("send") == "0":
                    return self.send(200, {"heard": heard, "sent": False})
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
    link = f"herdertower://pair?h={a.link_host}&p={a.port}&c={CODE}&fp={FP}"
    print(f"fake tower on https://{a.host}:{a.port}\ncode {CODE}\nfingerprint {' '.join(FP[i:i+4] for i in range(0, 64, 4))}\nLINK {link}", flush=True)
    srv.serve_forever()
