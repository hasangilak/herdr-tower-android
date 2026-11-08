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
import argparse, hashlib, json, math, os, queue, re, secrets, shutil, ssl, struct, subprocess, sys, tempfile, threading, time, zlib
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
    "screenshots": [], "work": None, "questions": [], "question_policy": "normal", "behaviors": None,
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
ROUTE = {}  # device id -> "phone" | "mac"
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


def speech(text, fallback_hz):
    """A sentence as 24 kHz mono WAV: spoken with macOS `say` when FAKE_SAY is set (real speech, like Kokoro's), else a tone.
    Returns (wav, ms)."""
    if os.environ.get("FAKE_SAY") and shutil.which("say"):
        with tempfile.NamedTemporaryFile(suffix=".wav") as f:
            subprocess.run(["say", "-o", f.name, "--data-format=LEI16@24000", text], check=True)
            wav = open(f.name, "rb").read()
        # `say` writes extra chunks (FLLR); the phone walks chunks, so it is passed on as is.
        data = wav.find(b"data")
        n = struct.unpack("<I", wav[data + 4:data + 8])[0] if data > 0 else 0
        return wav, n * 1000 // 48000
    ms = 400 + 40 * len(text)
    return tone(min(ms, 2500), fallback_hz), min(ms, 2500)


# Throttling, to reproduce a slow tower or Wi-Fi: FAKE_LATE_MS makes every other sentence arrive that long
# after the previous one has finished playing (the tower still synthesizing); FAKE_AUDIO_DELAY_MS delays
# serving each clip.
LATE_MS = int(os.environ.get("FAKE_LATE_MS", "0"))
AUDIO_DELAY_MS = int(os.environ.get("FAKE_AUDIO_DELAY_MS", "0"))


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


def whisper(wav):
    """Real words for a take, when FAKE_WHISPER_MODEL names a whisper.cpp model and whisper-cli is installed; else None."""
    model, cli = os.environ.get("FAKE_WHISPER_MODEL"), shutil.which("whisper-cli")
    if not model or not cli:
        return None
    with tempfile.NamedTemporaryFile(suffix=".wav") as f:
        f.write(wav)
        f.flush()
        r = subprocess.run([cli, "-m", model, "-f", f.name, "-nt", "-np"], capture_output=True, text=True, timeout=30)
    text = " ".join(r.stdout.split()).strip()
    print(f"transcribed: {text!r}", flush=True)
    return text


def chat_message(e, i):
    """A history entry as a message of the main chat thread (GET /v1/chat, event chat), as docs/mobile-api.md."""
    kind = e["kind"]
    role = {"request": "user", "answer": "tower", "screenshot": "screenshots"}.get(kind, "event")
    atts = []
    for n, path in enumerate(e.get("attachments", []), 1):
        name = path.rsplit("/", 1)[-1]
        k = "image" if name.lower().endswith((".png", ".jpg", ".jpeg", ".webp", ".gif")) else "video" if name.lower().endswith((".mp4", ".mov")) else "file"
        atts.append({"n": n, "kind": k, "name": name, "path": path, "url": "/v1/attachments/" + "/".join(path.split("/")[-2:]) if k == "image" else None})
    m = {"id": f"h{i}", "at": e["at"], "updated_at": e["at"], "role": role, "kind": kind, "text": e.get("text", "") if kind != "screenshot" else "",
         "source": e.get("source", "phone:d_fake" if role == "user" else "tower"), "attachments": atts, "screenshots": []}
    for k in ("session", "name", "account", "status"):
        if k in e:
            m[k] = e[k]
    if kind == "behavior":
        m["diff"] = e.get("diff", "")
    if kind == "screenshot":
        s = next((x for x in SHOTS if x["id"] == e["id"]), {"id": e["id"]})
        m["screenshots"] = [{k: s.get(k) for k in ("id", "caption", "session", "name", "account", "at")} | {"url": "/v1/screenshots/" + e["id"]}]
    return m


def thread():
    return [chat_message(e, i + 1) for i, e in enumerate(HISTORY) if e["kind"] in ("request", "answer", "watch", "alert", "summary", "screenshot", "answered", "behavior") or (e["kind"] == "job" and e.get("status") in ("done", "blocked"))]


def add_history(e):
    HISTORY.append(e)
    publish("history", e)
    publish("chat", {"op": "add", "message": chat_message(e, len(HISTORY))})


HUSHED = [False]


def speak(sentences):
    """Speaks sentence by sentence. A hush stops `speaking`, but the turn's later clips are still announced
    (as a slow brain might), so the phone's own "ignore the rest of this turn" is exercised."""
    voice = any(v for _, v in SUBS)
    HUSHED[0] = False
    set_state(phase="speaking", speaking=True)
    for i, s in enumerate(sentences):
        ms = 1200
        if voice:
            cid = f"a{secrets.token_hex(3)}"
            CLIPS[cid], ms = speech(s, 440 + 60 * i)
            publish("audio", {"id": cid, "kind": "reply", "text": s, "ms": ms, "url": f"/v1/audio/{cid}", "at_ms": now()}, voice_only=True)
            print(f"clip {cid}: {ms} ms", flush=True)
        set_state(said=" ".join(sentences[: i + 1]), said_ms=now())
        # Like the real tower, the next sentence is usually ready before this one ends; throttled, every other one is late.
        time.sleep((ms + LATE_MS) / 1000 if LATE_MS and i % 2 == 0 else min(1.2, ms / 1000 * 0.7))
    set_state(phase="idle", speaking=False, said=" ".join(sentences), said_ms=now())
    HUSHED[0] = False


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
    said = brain_behavior(text)
    answer = [said] if said else ["You said: " + text + ".", "This is the fake tower, so nothing really happened.", "Web is still on the checkout page.", "Api is waiting for your answer."]
    # While it speaks, what it says is the state's `said`; the answer lands on the thread after.
    speak(answer)
    add_history({"at": now(), "kind": "answer", "text": " ".join(answer)})
    set_state(work=None)


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


# Questions (docs/questions.md in herder-tower): sets that blocked sessions ask, recommendations, the 90/10 decision.
QUESTIONS = {}
QPOLICY = ["normal"]


def q_publish(op, qs):
    STATE["questions"] = [x for x in QUESTIONS.values()]
    STATE["question_policy"] = QPOLICY[0]
    publish("question", {"op": op, "id": qs["id"], "question": qs if op != "closed" else None})


def q_open(qs):
    QUESTIONS[qs["id"]] = qs
    q_publish("open", qs)


def q_update(qid, **kw):
    qs = QUESTIONS.get(qid)
    if qs:
        qs.update(kw)
        q_publish("update", qs)


def q_close(qid):
    qs = QUESTIONS.pop(qid, None)
    if qs:
        q_publish("closed", qs)


def opt(label, description=""):
    return {"label": label, "description": description, "key": ""}


def questions_life():
    """An escalated three-question set whose recommendations take a few seconds, a permission prompt, and a
    routine question the tower answers by itself (a quiet 'answered' line in the chat)."""
    q1 = {"id": "q3f1a9c", "session": "t3", "name": "api", "kind": "claude-ask", "title": "3 questions: Database, Features, Deploy",
          "detail": "", "context": "Set up the orders service",
          "tabs": [
              {"title": "Database", "question": "Which database should the orders service use?", "options": [opt("Postgres", "the shared cluster, with the other services"), opt("SQLite", "one file next to the service"), opt("DynamoDB", "managed, pay per request")], "multi": False, "allows_other": True, "answered": False},
              {"title": "Features", "question": "Which features should the first version have?", "options": [opt("Refunds"), opt("Partial shipments"), opt("Invoices as PDF"), opt("Webhooks")], "multi": True, "allows_other": True, "answered": False},
              {"title": "Deploy", "question": "Should I deploy it to production once the tests pass?", "options": [opt("Yes, deploy"), opt("Staging only"), opt("Don't deploy yet")], "multi": False, "allows_other": True, "answered": False}],
          "current_tab": 0, "submit_state": "answering", "recommendations": None, "decision": "escalate",
          "reason": "deploying to production is yours to decide", "status": "open", "error": None, "answered_with": None, "at_ms": now() - 60_000}
    q2 = {"id": "q7c20e1", "session": "t5", "name": "web", "kind": "claude-permission", "title": "Run a command",
          "detail": "rm -rf build/ && npm run build:prod", "context": "Fix the checkout page",
          "tabs": [{"title": "", "question": "Do you want to proceed?", "options": [opt("Yes"), opt("Yes, and don't ask again for rm in this project"), opt("No, and tell Claude what to do differently")], "multi": False, "allows_other": False, "answered": False}],
          "current_tab": 0, "submit_state": "single", "recommendations": [{"tab": 0, "options": [
              {"label": "No", "why": "it deletes the build folder; ask first what it needs", "choice": 2},
              {"label": "Yes", "why": "the build folder is generated anyway", "choice": 0},
              {"label": "Yes, don't ask again", "why": "if it keeps rebuilding", "choice": 1}]}],
          "decision": "escalate", "reason": "deleting files", "status": "open", "error": None, "answered_with": None, "at_ms": now() - 20_000}
    q_open(q1)
    q_open(q2)
    while not SUBS:  # the rest happens once a phone watches
        time.sleep(0.5)
    time.sleep(6)
    q_update("q3f1a9c", recommendations=[
        {"tab": 0, "options": [{"label": "Postgres", "why": "the other services already use it", "choice": 0},
                               {"label": "SQLite", "why": "simplest, if it stays small", "choice": 1},
                               {"label": "Postgres, own schema", "why": "shared cluster, kept apart", "choice": {"other": "Postgres, in its own schema"}}]},
        {"tab": 1, "options": [{"label": "Refunds and webhooks", "why": "what the shop asks for most", "choice": {"options": [0, 3]}},
                               {"label": "Refunds only", "why": "smallest first version", "choice": {"options": [0]}},
                               {"label": "All four", "why": "if there's time", "choice": {"options": [0, 1, 2, 3]}}]},
        {"tab": 2, "options": [{"label": "Staging only", "why": "production is yours to decide", "choice": 1},
                               {"label": "Don't deploy yet", "why": "nothing to show yet", "choice": 2},
                               {"label": "Yes, deploy", "why": "if the tests are green", "choice": 0}]}])
    time.sleep(8)
    q3 = {"id": "q90bb12", "session": "t1", "name": "docs", "kind": "claude-ask", "title": "Which test runner?", "detail": "", "context": "Add tests to the docs site",
          "tabs": [{"title": "Runner", "question": "Which test runner should I use?", "options": [opt("Vitest"), opt("Jest")], "multi": False, "allows_other": True, "answered": False}],
          "current_tab": 0, "submit_state": "single", "recommendations": None, "decision": None, "reason": None, "status": "open", "error": None, "answered_with": None, "at_ms": now()}
    q_open(q3)
    time.sleep(2)
    q_update("q90bb12", recommendations=[{"tab": 0, "options": [{"label": "Vitest", "why": "the project already uses Vite", "choice": 0}]}],
             decision="auto", reason="routine: which of two reasonable tools", status="answering")
    time.sleep(1.5)
    q_close("q90bb12")
    add_history({"at": now(), "kind": "answered", "session": "t1", "name": "docs", "text": "answered for you: Which test runner should I use? -> Vitest, because the project already uses Vite"})
    time.sleep(int(os.environ.get("FAKE_TEXT_QUESTION_S", "20")))
    # A session finished its turn with a question in plain text; the tower leaves it to the user.
    q_open({"id": "qtx51d0", "session": "t5", "name": "web", "kind": "text", "title": "Open the PR now?", "detail": "", "context": "Fix the checkout page",
            "tabs": [{"title": "", "question": "The checkout fix is done and tested. Want me to open the PR now, or wait for the design review?", "options": [], "multi": False, "allows_other": True, "answered": False}],
            "current_tab": 0, "submit_state": "single", "recommendations": [{"tab": 0, "options": [
                {"label": "Wait for the design review", "why": "the design team asked to see it first", "choice": {"other": "Wait for the design review, then open the PR."}},
                {"label": "Open it as a draft", "why": "reviewers can look early", "choice": {"other": "Open it as a draft PR now."}},
                {"label": "Open the PR now", "why": "it's tested", "choice": {"other": "Yes, open the PR now."}}]}],
            "decision": "escalate", "reason": "pushing to a shared branch", "status": "open", "error": None, "answered_with": None, "at_ms": now()})


def interpret(qs, tab, text):
    t = (text or "").lower().strip(" .!?")
    options = qs["tabs"][tab]["options"]
    words = {"first": 0, "one": 0, "1": 0, "erste": 0, "second": 1, "two": 1, "2": 1, "zweite": 1, "third": 2, "three": 2, "3": 2, "dritte": 2}
    picked = sorted({i for w, i in words.items() if re.search(rf"\b{w}\b", t) and i < len(options)})
    if "last" in t:
        picked = [len(options) - 1]
    picked += [i for i, o in enumerate(options) if o["label"].lower() in t and i not in picked]
    if picked:
        return {"options": picked[: (len(options) if qs["tabs"][tab]["multi"] else 1)], "other": None}
    return {"options": [], "other": text}


def questions_route(h, method, seg, q, b):
    """The question endpoints; True when handled."""
    if seg == ["questions-policy"]:
        if method == "PUT":
            p = json.loads(b or b"{}").get("policy")
            if p not in ("normal", "keep-moving", "ask-everything"):
                h.err(400, "bad_policy", "normal, keep-moving or ask-everything")
                return True
            QPOLICY[0] = p
            set_state(question_policy=p)
        h.send(200, {"policy": QPOLICY[0]})
        return True
    if not seg or seg[0] != "questions":
        return False
    if seg == ["questions"] and method == "GET":
        h.send(200, {"questions": list(QUESTIONS.values())})
        return True
    qs = QUESTIONS.get(seg[1]) if len(seg) > 1 else None
    if qs is None:
        h.err(404, "no_question", "no such question")
        return True
    if len(seg) == 2 and method == "GET":
        h.send(200, qs)
        return True
    if len(seg) == 2 and method == "DELETE":
        q_close(qs["id"])
        h.send(200, {"ok": True})
        return True
    if seg[2:] == ["answer"] and method == "POST":
        ctype = h.headers.get("Content-Type", "")
        files = []
        if ctype.startswith("multipart/"):
            boundary = ctype.split("boundary=")[-1].strip('"').encode()
            body = {}
            for part in b.split(b"--" + boundary):
                head, _, data = part.partition(b"\r\n\r\n")
                data = data.rsplit(b"\r\n", 1)[0]
                hd = head.decode("utf-8", "replace")
                if 'name="json"' in hd:
                    body = json.loads(data)
                elif 'name="tab' in hd:
                    files.append(hd.split('name="')[1].split('"')[0])
        else:
            body = json.loads(b or b"{}")
        answers = body.get("answers", [])
        if FAIL_NEXT[0]:
            FAIL_NEXT[0] = False
            q_update(qs["id"], status="failed", error="the dialog moved to another question")
            h.err(409, "changed", "the dialog moved to another question before the answer went in")
            return True
        print(f"answer {qs['id']}: {json.dumps(answers)} files={files}", flush=True)
        q_update(qs["id"], status="answering", answered_with=answers)

        def finish():
            time.sleep(2)
            q_update(qs["id"], status="answered")
            time.sleep(0.5)
            q_close(qs["id"])
        threading.Thread(target=finish, daemon=True).start()
        h.send(200, {"ok": True, "id": qs["id"], "submitted": True})
        return True
    if seg[2:] == ["interpret"] and method == "POST":
        d = json.loads(b or b"{}")
        tab = int(d.get("tab", 0))
        h.send(200, {"tab": tab, "choice": interpret(qs, tab, d.get("text", ""))})
        return True
    if seg[2:] == ["voice"] and method == "POST":
        tab = int(q.get("tab", 0))
        heard = whisper(b) or "the second one"
        h.send(200, {"tab": tab, "heard": heard, "choice": interpret(qs, tab, heard)})
        return True
    h.err(404, "not_found", "no such path")
    return True


# FAKE_FAIL_FIRST_ANSWER=1: the first answer stops with 409 changed (to see the failed state).
FAIL_NEXT = [bool(os.environ.get("FAKE_FAIL_FIRST_ANSWER"))]


# Behaviors (docs/behaviors.md in herder-tower): the rules the tower follows, changed from a screen or by the brain.
BCATS = ["Talking", "Sessions", "Spaces and focus", "Accounts", "Releases and safety", "Questions 90/10", "Screenshots", "Browsers", "Misc"]
BCORE = {"title": "The tower's core", "read_only": True, "text": "You are the tower: the voice and hands of the user's terminal sessions.\nMessages reach you as [voice] (the user), [watch] (a session finished), [question] (a session asks).\nYour tools: tower ask, tower answer, tower recommend, tower behavior.\nBehaviors below are the user's rules; change them only with tower behavior."}


def _b(id, title, cat, source, summary, text, enabled=True):
    return {"id": id, "title": title, "category": cat, "enabled": enabled, "source": source, "summary": summary,
            "created_ms": T0 - 30 * 86400_000, "updated_ms": T0 - 2 * 86400_000, "text": text}


BEHAVIORS = {b["id"]: b for b in [
    _b("heard-not-read", "You are heard, not read", "Talking", "builtin", "Answers are spoken, so they are short and plain",
       "Everything you say is spoken aloud.\nKeep answers to one or two sentences.\nNo lists, no markdown, no code unless asked."),
    _b("be-brief", "Be brief", "Talking", "learned", "One sentence when one will do", "Say the result first, then stop.\nDon't repeat the question back."),
    _b("leave-sessions-running", "Leave sessions running", "Sessions", "builtin", "Never stop or close a session that's working",
       "Don't stop, restart or close a session that is working.\nAsk the user first if a session seems stuck."),
    _b("look-then-hand-off", "Look before you act, then hand off", "Sessions", "builtin", "Read a session's screen before sending it work",
       "Before sending work to a session, read its screen.\nThen hand the work off in one clear message."),
    _b("focus-holds-news", "Focus holds other news", "Spaces and focus", "builtin", "While a space is in focus, news from others waits",
       "When a space is in focus, only its news is spoken.\nOther spaces' news is held until focus clears."),
    _b("browser-per-account", "The browser per account", "Browsers", "learned", "Each Claude account opens links in its own browser",
       "Open links for orca sessions in Brave.\nOpen links for main sessions in Chrome."),
    _b("release-dev-then-prod", "Release dev then prod", "Releases and safety", "learned", "Releases always go to dev first, then dev to prod",
       "Always release via dev first (PR merged into dev), then promote dev to prod.\nNever push straight to prod."),
    _b("questions-90-10", "Questions: answer routine ones", "Questions 90/10", "builtin", "Answer about 90% of questions, leave the rest to the user",
       "Answer routine questions yourself: naming, tests, the safe default.\nLeave deploys, pushes, deleting and money to the user."),
    _b("screenshots-with-replies", "Show screenshots with replies", "Screenshots", "builtin", "When a session sends a screenshot, show it with the answer",
       "When you talk about a session that sent screenshots, add [shot:<id>] to your reply."),
    _b("dev-ports", "Dev ports 3000-3050", "Misc", "user", "Dev servers use ports 3000 to 3050", "Start dev servers on a free port between 3000 and 3050.", enabled=False),
]}
BHIST = []  # {at, op, id, title, by, undid, before, after}


def bdiff(before, after):
    import difflib
    a, b = (before or "").splitlines(), (after or "").splitlines()
    out = []
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b).get_opcodes():
        if tag == "equal":
            out += ["  " + l for l in a[i1:i2]]
        else:
            out += ["- " + l for l in a[i1:i2]] + ["+ " + l for l in b[j1:j2]]
    return "\n".join(out)


def bview(c):
    return {"at": c["at"], "op": c["op"], "id": c["id"], "title": c["title"], "by": c["by"], "undid": c.get("undid"),
            "diff": bdiff((c["before"] or {}).get("text", ""), (c["after"] or {}).get("text", ""))}


def bchange(op, id, before, after, by, undid=None):
    title = (after or before or {}).get("title", id)
    if after is None:
        BEHAVIORS.pop(id, None)
    else:
        BEHAVIORS[id] = after
    c = {"at": now(), "op": op, "id": id, "title": title, "by": by, "undid": undid, "before": before, "after": after}
    BHIST.append(c)
    v = bview(c)
    set_state(behaviors=bsummary())
    publish("behaviors", v)
    verb = {"add": "Added", "edit": "Changed", "enable": "Turned on", "disable": "Turned off", "remove": "Removed", "undo": "Undid the last change to"}.get(op, op)
    add_history({"at": now(), "kind": "behavior", "text": f"{verb} a behavior: {title}", "diff": v["diff"]})
    return v


def bsummary():
    by = {}
    for b in BEHAVIORS.values():
        by[b["category"]] = by.get(b["category"], 0) + 1
    return {"count": len(BEHAVIORS), "enabled": sum(1 for b in BEHAVIORS.values() if b["enabled"]), "by_category": by, "updated_ms": max([c["at"] for c in BHIST] or [T0])}


def slug(title):
    base = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:40] or "behavior"
    id, k = base, 2
    while id in BEHAVIORS:
        id, k = f"{base}-{k}", k + 1
    return id


def behaviors_route(h, method, seg, q, b):
    if seg == ["behaviors"] and method == "GET":
        h.send(200, {"categories": BCATS, "behaviors": sorted(BEHAVIORS.values(), key=lambda x: (BCATS.index(x["category"]) if x["category"] in BCATS else 99, x["title"])),
                     "core": BCORE, "summary": bsummary()})
        return True
    if seg == ["behaviors"] and method == "POST":
        d = json.loads(b or b"{}")
        if not d.get("title") or not d.get("text"):
            h.err(400, "behavior", "a behavior needs a title and its text")
            return True
        nb = {"id": slug(d["title"]), "title": d["title"], "category": d.get("category") if d.get("category") in BCATS else "Misc", "enabled": True,
              "source": "user", "summary": d.get("summary", ""), "created_ms": now(), "updated_ms": now(), "text": d["text"]}
        v = bchange("add", nb["id"], None, nb, "user")
        h.send(201, {"behavior": nb, "change": v})
        return True
    if seg == ["behaviors", "history"]:
        lim = int(q.get("limit", 50))
        h.send(200, {"changes": [bview(c) for c in reversed(BHIST)][:lim]})
        return True
    if seg == ["behaviors", "undo"] and method == "POST":
        last = next((c for c in reversed(BHIST) if c["op"] != "undo" and not c.get("undone")), None)
        if not last:
            h.err(400, "behavior", "nothing to undo")
            return True
        last["undone"] = True
        v = bchange("undo", last["id"], last["after"], last["before"], "user", undid=last["at"])
        h.send(200, {"behavior": last["before"], "change": v})
        return True
    if len(seg) >= 2 and seg[0] == "behaviors":
        cur = BEHAVIORS.get(seg[1])
        if cur is None:
            h.err(404, "behavior", "no behavior " + seg[1])
            return True
        if len(seg) == 2 and method == "GET":
            h.send(200, cur)
            return True
        if seg[2:] == ["preview"] and method == "POST":
            h.send(200, {"id": cur["id"], "diff": bdiff(cur["text"], json.loads(b or b"{}").get("text", cur["text"]))})
            return True
        if len(seg) == 2 and method == "PUT":
            d = json.loads(b or b"{}")
            nb = dict(cur)
            for k in ("title", "category", "summary", "text", "enabled"):
                if k in d:
                    nb[k] = d[k]
            nb["updated_ms"] = now()
            op = ("enable" if d["enabled"] else "disable") if set(d) == {"enabled"} else "edit"
            v = bchange(op, cur["id"], cur, nb, "user")
            h.send(200, {"behavior": nb, "change": v})
            return True
        if len(seg) == 2 and method == "DELETE":
            v = bchange("remove", cur["id"], cur, None, "user")
            h.send(200, {"change": v})
            return True
    h.err(404, "not_found", "no such path")
    return True


def brain_behavior(text):
    """The brain turning 'add a behavior: …' / 'turn off …' into a change; its one-line answer, or None."""
    t = text.strip()
    low = t.lower()
    m = re.match(r"(?:add a behaviou?r|from now on|always|never)[:,]?\s*(.+)", t, re.I)
    if m:
        rule = m.group(1).strip().rstrip(".") + "."
        title = rule.split(",")[0].split(".")[0][:48].strip().capitalize()
        nb = {"id": slug(title), "title": title, "category": "Misc", "enabled": True, "source": "learned", "summary": rule,
              "created_ms": now(), "updated_ms": now(), "text": rule}
        bchange("add", nb["id"], None, nb, "brain")
        return "Added to your behaviors."
    m = re.match(r"turn off (?:the )?(.+?)(?: behaviou?r)?\.?$", low)
    if m:
        hit = next((b for b in BEHAVIORS.values() if m.group(1) in b["title"].lower()), None)
        if hit and hit["enabled"]:
            bchange("disable", hit["id"], hit, dict(hit, enabled=False, updated_ms=now()), "brain")
            return f"Turned off {hit['title']}."
    return None


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
        if seg[:1] == ["behaviors"]:
            if behaviors_route(self, method, seg, q, self.body() if method in ("POST", "PUT") else b""):
                return
        if seg[:1] in (["questions"], ["questions-policy"]):
            if questions_route(self, method, seg, q, self.body() if method in ("POST", "PUT") else b""):
                return
        if method == "GET":
            if seg == ["hello"]:
                return self.send(200, {"api": 1, "tower": "fake", "device": dev, "voice": "af_bella", "phone_is_voice": any(v for _, v in SUBS), "audio_route": ROUTE.get(dev["id"], "phone")})
            if seg == ["device", "audio"]:
                return self.send(200, {"route": ROUTE.get(dev["id"], "phone")})
            if seg == ["audio"]:
                return self.send(200, {"clips": []})
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
            if seg == ["chat"]:
                t = thread()
                b = q.get("before")
                if b:
                    at = next((m["at"] for m in t if m["id"] == b), None)
                    t = [m for m in t if at is not None and m["at"] < at]
                lim = int(q.get("limit", 50))
                return self.send(200, {"messages": t[-lim:], "more": len(t) > lim})
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
                time.sleep(AUDIO_DELAY_MS / 1000)
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
                HUSHED[0] = True
                publish("hush", {}, voice_only=True)
                set_state(phase="idle", speaking=False)
                return self.send(200, {"ok": True})
            if seg == ["mode"]:
                set_state(mode=json.loads(b)["mode"])
                return self.send(200, {"mode": STATE["mode"]})
            if seg in (["voice"], ["transcribe"]):
                if b[:4] != b"RIFF" and not self.headers.get("Content-Type", "").startswith("multipart/"):
                    return self.err(415, "bad_audio", "send audio/wav")
                secs = max(0.0, (len(b) - 44) / 32000)
                heard = whisper(b) if b[:4] == b"RIFF" else None
                if heard is None:
                    heard = f"a {secs:.1f} second take"
                if seg == ["transcribe"] or q.get("send") == "0":
                    return self.send(200, {"heard": heard, "sent": False})
                set_state(heard=heard, heard_ms=now())
                threading.Thread(target=reply_to, args=(heard,), daemon=True).start()
                return self.send(200, {"heard": heard, "sent": True})
        if (method, seg) == ("PUT", ["device", "audio"]):
            r = json.loads(self.body() or b"{}").get("route")
            if r not in ("phone", "mac"):
                return self.err(400, "bad_route", "route is phone or mac")
            ROUTE[dev["id"]] = r
            return self.send(200, {"route": r})
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

    def do_PUT(self):
        self.route("PUT")


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
    threading.Thread(target=questions_life, daemon=True).start()
    print(f"fake tower on https://{a.host}:{a.port}\ncode {CODE}\nfingerprint {' '.join(FP[i:i+4] for i in range(0, 64, 4))}\nLINK {link}", flush=True)
    srv.serve_forever()
