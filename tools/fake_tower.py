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


def _tokens_file():
    return os.path.join(CERT_DIR, "tokens.json")


def load_tokens():
    """Paired phones survive a restart (kept beside the certificate), so a phone needn't pair again."""
    try:
        with open(_tokens_file()) as f:
            TOKENS.update(json.load(f))
    except (OSError, ValueError):
        pass


def save_tokens():
    os.makedirs(CERT_DIR, exist_ok=True)
    with open(_tokens_file(), "w") as f:
        json.dump(TOKENS, f)
SUBS = []  # (queue, voice)
LINK_HOST = ["10.0.2.2", 7899]
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
    "mode": "ptt", "mic": "closed", "armed": False, "transcribing": False, "speaking": False, "announcing": False, "muted": False,
    "brain": "done", "brain_app": "claude-code", "hearing": "", "heard": "", "heard_ms": 0,
    "said": "Web finished the checkout page. Api is waiting for your answer.", "said_ms": now() - 2 * MIN, "alerts": [],
    "agents": [
        {"address": "t3", "name": "api", "status": "blocked", "app": "claude-code", "account": "studio"},
        {"address": "t5", "name": "web", "status": "working", "app": "claude-code", "account": "main"},
        {"address": "t7", "name": "docs", "status": "working", "app": "claude-code", "account": "main"},
        {"address": "vps/t2", "name": "deploy", "status": "idle", "app": "codex"},
        {"address": "t9", "name": "infra-bot", "status": "working", "app": "codex", "account": "main"},
        {"address": "t11", "name": "qfix-ask", "status": "working", "app": "claude-code", "account": "main"},
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

# PRs the sessions opened (herder-tower docs/mobile-api.md, "PRs"): one of each state; all invented, nothing reaches GitHub.
def _pr(repo, n, title, base, head, checks, session, name, space, found_min, mergeable="mergeable", waiting=False, state="open", merged_by=None, draft=False):
    failing = [c for c, ok in checks if ok is False]
    pending = [c for c, ok in checks if ok is None]
    cs = "none" if not checks else "failing" if failing else "pending" if pending else "passing"
    return {"repo": repo, "number": n, "url": f"https://github.com/{repo}/pull/{n}", "title": title, "base": base, "head": head,
            "head_sha": hashlib.sha1(f"{repo}{n}".encode()).hexdigest(), "state": state, "draft": draft, "mergeable": mergeable,
            "checks": {"state": cs, "failing": failing, "pending": pending, "passed": len(checks) - len(failing) - len(pending), "total": len(checks)},
            "session": session, "name": name, "space": space, "waiting": waiting, "found_ms": T0 - found_min * MIN, "checked_ms": T0 - found_min * MIN + 30_000,
            "closed_ms": None if state == "open" else T0 - (found_min - 5) * MIN, "merged_by": merged_by}


PRS = {f"{p['repo']}#{p['number']}": p for p in [
    _pr("example/web", 40, "docs: the setup guide for the new checkout", "dev", "docs-checkout", [("CI / build", True)], "t7", "docs", "Storefront", 90, state="merged", merged_by="mac"),
    _pr("example/web", 41, "spike: a second payment provider", "dev", "spike-pay", [("CI / build", True)], "t5", "web", "Storefront", 85, state="closed"),
    _pr("example/web", 12, "chore: bump the UI kit to 4.0", "main", "deps-ui-4", [("CI / build", True), ("CI / test", True)], "t5", "web", "Storefront", 50, mergeable="conflicting"),
    _pr("example/api", 88, "fix: retry uploads that time out", "dev", "retry-uploads", [("CI / build", True), ("CI / test", True), ("CI / lint", None), ("e2e", None), ("deploy preview", None)], "t3", "api", "Storefront", 9),
    _pr("example/api", 91, "feat: export orders as CSV", "dev", "orders-csv", [("CI / build", True), ("CI / test", True), ("CI / lint", True)], "t3", "api", "Storefront", 7, waiting=True),
    _pr("example/mediaroom", 1250, "release: support tickets can open a new request beside an open one", "main", "dev",
        [("CI / build", True), ("CI / test", True), ("CI / lint", False), ("e2e", True), ("deploy preview", True)], "t9", "b2c tickets mediaroom", "Infra", 5, waiting=True),
]}
PROD = {"main", "master", "prod", "production", "release", "live"}


def pr_gate(p):
    blocked = ("It's merged already." if p["state"] == "merged" else "It's closed." if p["state"] == "closed" else
               "It's a draft: mark it ready for review first." if p["draft"] else
               f"It has conflicts with {p['base']}: they need resolving first." if p["mergeable"] == "conflicting" else None)
    c = p["checks"]
    needs = None
    if c["state"] == "failing":
        n = len(c["failing"])
        needs = f"{n} check{'' if n == 1 else 's'} failing: {', '.join(c['failing'])}. Merge anyway only if you know the failure is unrelated."
    warnings = []
    if p["base"].lower() in PROD:
        warnings.append(f"It merges into {p['base']}: that ships to production.")
    if c["state"] == "pending":
        k = len(c["pending"])
        warnings.append(f"{k} check{' is' if k == 1 else 's are'} still running.")
    if c["state"] == "none":
        warnings.append("No checks ran on it.")
    if p["mergeable"] == "unknown" and p["state"] == "open":
        warnings.append("GitHub hasn't worked out yet whether it merges cleanly.")
    if (p.get("score") or {}).get("needs_review"):
        warnings.append(p["score"]["line"])
    return {"can": blocked is None, "blocked": blocked, "needs_override": needs, "warnings": warnings}


def pr_view(p):
    return dict(p, key=f"{p['repo']}#{p['number']}", merge=pr_gate(p))


for k, p in PRS.items():
    HISTORY.append({"at": p["found_ms"], "kind": "pr", "id": k, "session": p["session"], "name": p["name"], "space": p["space"], "text": p["title"]})
HISTORY.sort(key=lambda e: e["at"])
PR_TOKENS = {}  # token -> (device id, key, head sha, expires)
# FAKE_PR_MERGE_FAIL=1: the first merge is refused by "GitHub" (409 merge_failed), to see the retry card.
PR_FAIL_NEXT = [bool(os.environ.get("FAKE_PR_MERGE_FAIL"))]


def pr_changed(key, op="update"):
    """A PR changed: its `pr` event, and its chip's chat message again (the same id, updated)."""
    p = PRS[key]
    p["checked_ms"] = now()
    publish("pr", {"op": op, "pr": pr_view(p)})
    for i, e in enumerate(HISTORY):
        if e["kind"] == "pr" and e.get("id") == key:
            publish("chat", {"op": "update", "message": chat_message(e, i + 1)})


def pr_life():
    """#88's checks finish passing a while after the start, and its session then waits on the merge."""
    time.sleep(float(os.environ.get("FAKE_PR_CHECKS_S", "40")))
    p = PRS["example/api#88"]
    p["checks"] = {"state": "passing", "failing": [], "pending": [], "passed": 5, "total": 5}
    p["waiting"] = True
    print("PR example/api#88: checks passed, waiting", flush=True)
    pr_changed("example/api#88")


def prs_route(h, method, seg, dev, b):
    if seg == ["prs"] and method == "GET":
        day = now() - 86400_000
        ps = [p for p in PRS.values() if p["state"] == "open" or (p["closed_ms"] or 0) >= day]
        ps.sort(key=lambda p: (p["state"] != "open", not p["waiting"], -p["found_ms"]))
        return h.send(200, {"prs": [pr_view(p) for p in ps]})
    if len(seg) < 4:
        return h.err(404, "not_found", "no such path")
    key = f"{seg[1]}/{seg[2]}#{seg[3]}"
    p = PRS.get(key)
    if p is None:
        return h.err(404, "no_pr", "no PR the tower knows by that name")
    if len(seg) == 4 and method == "GET":
        return h.send(200, {"pr": pr_view(p)})
    if seg[4:] == ["confirm"] and method == "POST":
        tok = secrets.token_hex(12)
        PR_TOKENS[tok] = (dev["id"], key, p["head_sha"], now() + 2 * MIN)
        g = pr_gate(p)
        return h.send(200, {"token": tok, "expires_ms": now() + 2 * MIN, "pr": pr_view(p), "method": "squash", **g})
    if seg[4:] == ["merge"] and method == "POST":
        d = json.loads(b or b"{}")
        t = PR_TOKENS.pop(d.get("token", ""), None)
        if not t or t[0] != dev["id"] or t[1] != key or t[3] < now():
            return h.err(403, "bad_token", "confirm first (POST …/confirm): the token is missing, used, for another PR or device, or older than two minutes")
        g = pr_gate(p)
        if g["blocked"]:
            return h.err(409, "blocked", g["blocked"])
        if t[2] != p["head_sha"]:
            return h.err(409, "changed", "New commits since you confirmed: look again.")
        if g["needs_override"] and not d.get("override"):
            return h.err(409, "needs_override", g["needs_override"])
        if PR_FAIL_NEXT[0]:
            PR_FAIL_NEXT[0] = False
            return h.err(409, "merge_failed", "At least 1 approving review is required by reviewers with write access.")
        p.update(state="merged", merged_by=f"phone:{dev['id']}", closed_ms=now(), waiting=False)
        print(f"PR {key} merged from {dev['name']} (override={bool(d.get('override'))})", flush=True)
        pr_changed(key, "closed")
        add_history({"at": now(), "kind": "watch", "session": p["session"], "name": p["name"],
                     "text": f"{p['session']} ({p['name']}): the user merged PR #{p['number']} into {p['base']} ({p['repo']}) from the phone."})
        return h.send(200, {"merged": True, "pr": pr_view(p)})
    return h.err(404, "not_found", "no such path")


FOCUS = [None]
ROUTE = {}  # device id -> "phone" | "mac"
SPACES = [("Storefront", ["t3", "t5", "t7"], "The shop: checkout, onboarding and the orders API."), ("Infra", ["vps/t2", "t9"], None)]
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
    text = re.sub(r"\[[A-Z_ ]+\]|\((?:silence|music|noise)\)", "", " ".join(r.stdout.split()), flags=re.I).strip()
    print(f"transcribed: {text!r}", flush=True)
    return text


def chat_message(e, i):
    """A history entry as a message of the main chat thread (GET /v1/chat, event chat), as docs/mobile-api.md."""
    kind = e["kind"]
    role = {"request": "user", "answer": "tower", "act": "tower", "screenshot": "screenshots", "pr": "pr"}.get(kind, "event")
    atts = []
    for n, path in enumerate(e.get("attachments", []), 1):
        name = path.rsplit("/", 1)[-1]
        k = "image" if name.lower().endswith((".png", ".jpg", ".jpeg", ".webp", ".gif")) else "video" if name.lower().endswith((".mp4", ".mov")) else "file"
        atts.append({"n": n, "kind": k, "name": name, "path": path, "url": "/v1/attachments/" + "/".join(path.split("/")[-2:]) if k == "image" else None})
    m = {"id": f"h{i}", "at": e["at"], "updated_at": e["at"], "role": role, "kind": kind, "text": e.get("text", "") if kind != "screenshot" else "",
         "source": e.get("source", "phone:d_fake" if role == "user" else "tower"), "attachments": atts, "screenshots": []}
    for k in ("session", "name", "account", "status", "space", "from", "from_name", "to_name"):
        if k in e:
            m[k] = e[k]
    if kind == "behavior":
        m["diff"] = e.get("diff", "")
    if kind == "act":
        m["act"] = ACTS.get(e["id"])
        m["text"] = (m["act"] or {}).get("closing") or (m["act"] or {}).get("line", "")
    if kind == "pr":
        p = PRS.get(e["id"])
        m["space"] = e.get("space")
        m["pr"] = pr_view(p) if p else {"key": e["id"], "title": e.get("text", "")}
        if p:
            m["updated_at"] = max(p["checked_ms"], e["at"])
    if kind == "screenshot":
        s = next((x for x in SHOTS if x["id"] == e["id"]), {"id": e["id"]})
        m["screenshots"] = [{k: s.get(k) for k in ("id", "caption", "session", "name", "account", "at")} | {"url": "/v1/screenshots/" + e["id"]}]
    return m


def thread():
    return [chat_message(e, i + 1) for i, e in enumerate(HISTORY) if e["kind"] in ("request", "answer", "act", "watch", "alert", "summary", "screenshot", "answered", "behavior", "pr") or (e["kind"] == "job" and e.get("status") in ("done", "blocked"))]


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
    if not STATE.get("muted"):
        set_state(phase="speaking", speaking=True)
    for i, s in enumerate(sentences):
        ms = 1200
        # Muted (`tower mute`): the words still land in the state and the chat, but nothing is said.
        if voice and not STATE.get("muted"):
            cid = f"a{secrets.token_hex(3)}"
            CLIPS[cid], ms = speech(s, 440 + 60 * i)
            publish("audio", {"id": cid, "kind": "reply", "text": s, "ms": ms, "url": f"/v1/audio/{cid}", "at_ms": now()}, voice_only=True)
            print(f"clip {cid}: {ms} ms", flush=True)
        set_state(said=" ".join(sentences[: i + 1]), said_ms=now())
        # Like the real tower, the next sentence is usually ready before this one ends; throttled, every other one is late.
        time.sleep((ms + LATE_MS) / 1000 if LATE_MS and i % 2 == 0 else min(1.2, ms / 1000 * 0.7))
    set_state(phase="idle", speaking=False, said=" ".join(sentences), said_ms=now())
    HUSHED[0] = False


def reply_to(text, attachments=(), space=None):
    e = {"at": now(), "kind": "request", "text": "[voice] " + text}
    if attachments:
        e["attachments"] = list(attachments)
    if space:
        e["space"] = space
    add_history(e)
    if any(v for _, v in SUBS) and not STATE.get("muted"):
        cid = f"a{secrets.token_hex(3)}"
        CLIPS[cid] = tone(300, 660)
        publish("audio", {"id": cid, "kind": "ack", "text": "Okay.", "ms": 300, "url": f"/v1/audio/{cid}"}, voice_only=True)
    t = now()
    set_state(phase="thinking", work={"word": "Thinking", "detail": "", "since_ms": t, "at_ms": t, "done_ms": 0})
    time.sleep(1.5)
    set_state(work={"word": "Reading", "detail": "web", "since_ms": t, "at_ms": now(), "done_ms": 0})
    time.sleep(2.5)
    said = brain_behavior(text)
    shown = None
    if said:
        answer = [said]
    elif QUIET[0]:
        # A command gets a receipt with what was assumed, a question one reply: never a question back.
        shown, answer = quiet_reply(text, space)
    else:
        answer = ["You said: " + text + ".", "This is the fake tower, so nothing really happened.", "Web is still on the checkout page.", "Api is waiting for your answer."]
    # While it speaks, what it says is the state's `said`; the answer lands on the thread after.
    speak(answer)
    out = {"at": now(), "kind": "answer", "text": shown or " ".join(answer)}
    if space:
        out["space"] = space
    add_history(out)
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
Q_WHO = [("t3", "api"), ("t5", "web")]  # who asks the sets of questions_life
QPOLICY = ["normal"]


def q_publish(op, qs):
    STATE["questions"] = [x for x in QUESTIONS.values()]
    STATE["question_policy"] = QPOLICY[0]
    publish("question", {"op": op, "id": qs["id"], "question": qs if op != "closed" else None})
    if QUIET[0]:
        d_changed()  # an escalated set is a decision in the queue


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
    (a_addr, a_name), (b_addr, b_name) = Q_WHO
    q1 = {"id": "q3f1a9c", "session": a_addr, "name": a_name, "kind": "claude-ask", "title": "3 questions: Database, Features, Deploy",
          "detail": "", "context": "Set up the orders service",
          "tabs": [
              {"title": "Database", "question": "Which database should the orders service use?", "options": [opt("Postgres", "the shared cluster, with the other services"), opt("SQLite", "one file next to the service"), opt("DynamoDB", "managed, pay per request")], "multi": False, "allows_other": True, "answered": False},
              {"title": "Features", "question": "Which features should the first version have?", "options": [opt("Refunds"), opt("Partial shipments"), opt("Invoices as PDF"), opt("Webhooks")], "multi": True, "allows_other": True, "answered": False},
              {"title": "Deploy", "question": "Should I deploy it to production once the tests pass?", "options": [opt("Yes, deploy"), opt("Staging only"), opt("Don't deploy yet")], "multi": False, "allows_other": True, "answered": False}],
          "current_tab": 0, "submit_state": "answering", "recommendations": None, "decision": "escalate",
          "reason": "deploying to production is yours to decide", "status": "open", "error": None, "answered_with": None, "at_ms": now() - 60_000}
    q2 = {"id": "q7c20e1", "session": b_addr, "name": b_name, "kind": "claude-permission", "title": "Run a command",
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
    q_open({"id": "qtx51d0", "session": b_addr, "name": b_name, "kind": "text", "title": "Open the PR now?", "detail": "", "context": "Fix the checkout page",
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



# --- Quiet Tower (herder-tower docs/quiet-tower.md, "Step 11: the phone") ---
# The queue of decisions, spaces as missions, the thread between sessions, setups, launch and the tell level.
# The data is the design's own example (docs/quiet-tower.html). `--legacy` turns all of it off: every new call
# answers 404 and the state has no `decisions`, as a tower from before does, so the app's fallback can be tried.

QUIET = [True]
TELL = ["decisions"]
VOICE = ["replies"]
ACTS = {}            # id -> act: what the tower opened for a command, with what it assumed
ACT_LINE = {}        # act id -> its chat message's history index
DECISIONS = {}       # id -> decision as kept (the queue is made from these and the escalated question sets)
MISSIONS = []
QUIET_NAMES = ["B2C support", "eyz-mediaroom-serverless", "b2c", "b2c-tickets", "B2B ticketing", "eyzflow", "uiux"]
CLOSED_TODAY = []
THREADS = {}
ACTIONS = {}         # id -> a launch or a close a phone started
CLOSED_DECISIONS = []
MODEL = {"program": "claude", "model": "claude-opus-5-5", "effort": "high",
         "choices": {"models": [{"id": "claude-fable-5-2", "label": "Fable 5.2"}, {"id": "claude-opus-5-5", "label": "Opus 5.5"},
                                {"id": "claude-sonnet-5-5", "label": "Sonnet 5.5"}, {"id": "claude-haiku-5-5", "label": "Haiku 5.5"}],
                     "efforts": ["low", "medium", "high", "xhigh", "max"]},
         "running": [{"brain": "tower", "space": None, "address": "t1", "model": "claude-opus-5-5", "effort": "high", "restart": False}],
         "restart_needed": False, "locked_by_args": False, "locked": {"model": None, "effort": None}}
SETUPS = [
    {"name": "eyzflow + mediaroom on orca", "account": "orca", "repos": ["~/Work/eyz/eyzflow", "~/Work/eyz/eyz-mediaroom-serverless"]},
    {"name": "landing page on orca", "account": "orca", "repos": ["~/Work/eyz/industrial-landing-page"]},
    {"name": "herder-tower on default", "account": "default", "repos": ["~/Work/herder-tower"]},
]
PORT = [3018]


def _ms(address, name, status, account, worktree, port, said):
    branch = "/".join(reversed(worktree.split("@", 1))) if worktree and "@" in worktree else None
    return {"address": address, "name": name, "status": status, "account": account, "worktree": worktree, "branch": branch, "port": port, "said": said}


def model_says():
    label = lambda m: next((c["label"] for c in MODEL["choices"]["models"] if c["id"] == m), m)
    run = MODEL["running"][0]
    says = f"The tower's brain runs on {label(run['model'])} at {run['effort']} effort."
    if MODEL["restart_needed"]:
        says += f" {label(MODEL['model'])} at {MODEL['effort']} effort applies when it restarts."
    return dict(MODEL, says=says)


def quiet_world():
    """Replaces the made-up shop with the design's example: five missions, three decisions, a thread, a record."""
    global SPACES
    MISSIONS[:] = [
        {"name": "sandbox", "goal": "A plan for the storefront live editor", "done_when": "the plan is written and all three sessions agree on it",
         "standing": False, "state": "open", "follow": False, "mute": False, "report": None, "thread_count": 33,
         "sessions": [_ms("s1", "eyzflow", "done", "orca", "eyzflow@sandbox", 3010, "Wrote the plan"),
                      _ms("s2", "mediaroom", "done", "orca", "mediaroom@sandbox", 3011, "Answered the planning questions"),
                      _ms("s3", "project", "done", "orca", "sandbox-project@sandbox", 3012, "Checked the estimate")],
         "requests": [{"id": "a31", "title": "Plan the storefront live editor", "state": "done", "done": 3, "total": 3}],
         "tower": {"name": "tower-sandbox", "status": "ready", "address": "t21"}},
        {"name": "isa", "goal": "Voice clips for the Ingest tour, live", "done_when": "the release is out and the tour plays on production",
         "standing": False, "state": "open", "follow": False, "mute": False, "report": None, "thread_count": 1,
         "sessions": [_ms("s4", "eyzflow", "done", "orca", "eyzflow@isa", 3013, "Uploaded 12 clips to dev and production"),
                      _ms("s5", "mediaroom", "working", "orca", "mediaroom@isa", 3014, "Runs the last step after the two merges")],
         "requests": [{"id": "a32", "title": "Voice clips for the Ingest tour", "state": "working", "done": 5, "total": 6}],
         "tower": {"name": "tower-isa", "status": "working", "address": "t22"}},
        {"name": "industrial-landing-page", "goal": "New product drawings on the landing page", "done_when": "every section has its drawing and the page is live",
         "standing": False, "state": "open", "follow": False, "mute": False, "report": None, "thread_count": 0,
         "sessions": [_ms("s6", "landing", "working", "orca", "industrial-landing-page@landing", 3015, "Reworking the product drawings")],
         "requests": [{"id": "a33", "title": "Rework the product drawings", "state": "working", "done": 0, "total": 1}]},
        {"name": "M5 Pro", "goal": None, "done_when": None, "standing": True, "state": "open", "follow": False, "mute": False, "report": None, "thread_count": 0,
         "sessions": [_ms("s7", "herder-tower · default", "done", "default", "herder-tower@m5-pro-1", None, "Merged the fix and restarted the tower"),
                      _ms("s8", "herder-tower · orca", "working", "orca", "herder-tower@m5-pro-2", None, "Rethinking the Mac tower's UX")],
         "requests": [{"id": "a34", "title": "Rethink the Mac tower's UX", "state": "working", "done": 0, "total": 1}]},
        {"name": "Anna", "goal": "Support pictures on cases, in production", "done_when": "the release is live and the case shows its pictures",
         "standing": False, "state": "ready", "follow": False, "mute": False, "thread_count": 1,
         "report": "Support pictures are live on production. Released at 14:55 UTC, health check passing. Both sessions have finished.",
         "sessions": [_ms("s9", "eyzflow", "done", "orca", "eyzflow@anna", 3016, "Checked the case on production"),
                      _ms("s10", "mediaroom", "done", "orca", "mediaroom@anna", 3017, "Release live since 14:55 UTC")],
         "requests": [{"id": "a35", "title": "Support pictures, released", "state": "done", "done": 2, "total": 2}]},
    ]
    STATE["agents"] = [{"address": x["address"], "name": x["name"], "status": "idle" if x["status"] == "done" else x["status"], "app": "claude-code", "account": x["account"]}
                       for m in MISSIONS for x in m["sessions"]]
    SPACES = [(m["name"], [x["address"] for x in m["sessions"]], m["goal"]) for m in MISSIONS]
    DECISIONS.clear()
    for d in [
        {"id": "d-db", "key": "raised:sandbox:production-read", "kind": "permission", "space": "sandbox", "sessions": ["s1", "s2", "s3"],
         "title": "Allow one read of the production database", "why": "Production data",
         "detail": "psql $PROD_READONLY -c \"select id, status, updated_at from storefront_drafts limit 200\"",
         "options": [{"label": "Allow once", "why": "", "pick": True}, {"label": "No", "why": "", "pick": False}], "prs": [], "first_ms": now() - 2 * MIN},
        {"id": "d-pick", "key": "question:industrial-landing-page:which-microscope-drawing", "kind": "choice", "space": "industrial-landing-page", "sessions": ["s6"],
         "title": "Which microscope drawing?", "why": "Design choice", "detail": "",
         "options": [{"label": "B, magnified view", "why": "the tower's pick: it reads at small sizes", "pick": True}, {"label": "A", "why": "", "pick": False}, {"label": "C", "why": "", "pick": False}],
         "prs": [], "first_ms": now() - 4 * MIN},
        {"id": "d-pr", "key": "merge:example/mediaroom", "kind": "merge", "space": "isa", "sessions": [],
         "title": "2 pull requests ready to merge", "why": "Merge", "detail": "",
         "options": [],
         "prs": [{"repo": "example/mediaroom", "number": 1273, "base": "dev"}, {"repo": "example/mediaroom", "number": 1277, "base": "main"}],
         "first_ms": now() - 31 * MIN},
    ]:
        DECISIONS[d["id"]] = dict(d, question_id=None, last_ms=d["first_ms"], count=1, state="open")
    THREADS.clear()
    THREADS["sandbox"] = [
        {"at": T0 - 26 * MIN, "from": "s3", "from_name": "project", "to": "s1", "to_name": "eyzflow", "text": "Four changes to the plan summary."},
        {"at": T0 - 9 * MIN, "from": "s2", "from_name": "mediaroom", "to": "s1", "to_name": "eyzflow", "text": "Answers on stale writes and publish events."},
        {"at": T0 - 6 * MIN, "from": "s2", "from_name": "mediaroom", "to": "s1", "to_name": "eyzflow", "text": "Answer on the read-only key question."},
    ]
    THREADS["isa"] = [{"at": T0 - 50 * MIN, "from": "s5", "from_name": "mediaroom", "to": "s4", "to_name": "eyzflow", "text": "The clips are ready and release #1277 is open."}]
    THREADS["Anna"] = [{"at": T0 - 8 * 60 * MIN, "from": "s9", "from_name": "eyzflow", "to": "s10", "to_name": "mediaroom", "text": "Production check of the case passed."}]
    n = 0
    for sp, msgs in THREADS.items():
        for msg in msgs:
            n += 1
            msg.update(id=f"t{n}", space=sp, delivered=True)
    # The shots and the record of the same day.
    pics = list(SHOT_PICS)
    SHOT_PICS[f"{T0}-quiet-window.png"] = page((90, 110, 160), dark=True)
    SHOT_PICS[f"{T0}-quiet-panel.png"] = page((120, 140, 200), dark=True)
    SHOT_PICS[f"{T0}-anna-case.png"] = page((170, 90, 140))
    shots = [
        (f"{T0}-quiet-window.png", 12, "s8", "herder-tower · orca", "M5 Pro", "Quiet Tower: every state of the orb, the floating panel and the full window"),
        (f"{T0}-quiet-panel.png", 14, "s8", "herder-tower · orca", "M5 Pro", "Quiet Tower: the floating panel above the full window it opens"),
        (pics[0], 95, "s6", "landing", "industrial-landing-page", "The film-frames drawing, variant D2"),
        (pics[1], 120, "s6", "landing", "industrial-landing-page", "The microscope drawing, variant B"),
        (f"{T0}-anna-case.png", 8 * 60, "s9", "eyzflow", "Anna", "A case showing its support pictures on production"),
        (pics[2], 9 * 60, "s4", "eyzflow", "isa", "The Ingest walk on dev with the highlight fixed"),
    ]
    SHOTS[:] = [{"id": i, "at": T0 - m * MIN, "session": a, "name": n, "space": sp, "account": "orca", "job": None, "caption": c,
                 "path": "/tower/attachments/screenshots/" + i, "url": "/v1/screenshots/" + i} for (i, m, a, n, sp, c) in shots]
    STATE["screenshots"] = SHOTS
    STATE["said"] = "The plan for the storefront live editor is finished and checked."
    JOBS[:] = [
        {"job": "j31", "session": "s1", "name": "eyzflow", "space": "sandbox", "account": "orca", "status": "done", "asked": "Plan the storefront live editor", "summary": "Wrote the plan", "started_ms": T0 - 70 * MIN, "updated_ms": T0 - 16 * MIN},
        {"job": "j32", "session": "s5", "name": "mediaroom", "space": "isa", "account": "orca", "status": "working", "asked": "Voice clips for the Ingest tour", "summary": "", "started_ms": T0 - 10 * 60 * MIN, "updated_ms": T0 - 31 * MIN},
        {"job": "j33", "session": "s6", "name": "landing", "space": "industrial-landing-page", "account": "orca", "status": "working", "asked": "Rework the product drawings", "summary": "", "started_ms": T0 - 3 * 60 * MIN, "updated_ms": T0 - 4 * MIN},
    ]
    PRS.clear()
    for pr in [
        _pr("example/mediaroom", 1273, "feat: voice clips for the Ingest tour", "dev", "ingest-clips", [("CI / build", True), ("CI / test", True)], "s5", "mediaroom", "isa", 33, waiting=True),
        _pr("example/mediaroom", 1277, "release: the Ingest tour with its voice clips", "main", "dev", [("CI / build", True), ("CI / test", True), ("e2e", True)], "s5", "mediaroom", "isa", 31, waiting=True),
        _pr("example/landing", 42, "feat: the microscope drawing, variant B", "dev", "drawing-microscope-b", [("CI / build", True), ("CI / test", True)],
            "s6", "landing", "industrial-landing-page", 40, state="merged", merged_by="tower"),
    ]:
        PRS[f"{pr['repo']}#{pr['number']}"] = pr
    # Review scores (feature scores): the release into main needs review, the rest are low risk; the tower merged #42 itself.
    low = {"needs_review": False, "line": "Low risk: no migration, breaking change, secrets, infrastructure or auth", "reasons": [], "flags": []}
    PRS["example/mediaroom#1273"].update(score=dict(low, planned="p3#1", sha=PRS["example/mediaroom#1273"]["head_sha"]))
    PRS["example/mediaroom#1277"].update(score={"needs_review": True, "line": "Needs review: a deploy or release (release: dev into main)",
                                                 "reasons": ["a deploy or release (release: dev into main)"], "flags": ["deploy"], "planned": "p3#2",
                                                 "sha": PRS["example/mediaroom#1277"]["head_sha"]})
    PRS["example/landing#42"].update(score=dict(low, planned="p4#1", sha=PRS["example/landing#42"]["head_sha"]),
                                     auto={"state": "merged", "why": "low risk, into dev; its checks passed and its reviewer agreed", "sha": PRS["example/landing#42"]["head_sha"], "at_ms": T0 - 35 * MIN})
    STATE["auto_merge"] = True
    H_ = lambda mins, kind, **kw: dict({"at": T0 - mins * MIN, "kind": kind}, **kw)
    HISTORY[:] = sorted([
        H_(10 * 60 + 40, "answered", session="s5", name="mediaroom", space="isa", text="answered for you: Where to start? -> Start with the small fix to the analysis description."),
        H_(10 * 60 + 35, "job", session="s4", name="eyzflow", space="isa", status="done", text="Uploaded 12 clips to production and to dev.", account="orca"),
        H_(9 * 60, "screenshot", session="s4", name="eyzflow", space="isa", account="orca", id=SHOTS[5]["id"], caption="3 screenshots of the Ingest walk on dev.", path=SHOTS[5]["path"], text="3 screenshots of the Ingest walk on dev."),
        H_(8 * 60 + 10, "job", session="s10", name="mediaroom", space="Anna", status="done", text="Release live, health check passing.", account="orca"),
        H_(7 * 60 + 20, "job", session="s10", name="mediaroom", space="Anna", status="done", text="The support pictures work is complete.", account="orca"),
        H_(5 * 60, "job", session="s6", name="landing", space="industrial-landing-page", status="done", text="PR #40 is merged.", account="orca"),
        H_(52, "request", text="Open a space named sandbox with three sessions on orca."),
        H_(51, "answer", text="**Opened sandbox.** Three sessions on orca, each in its own worktree, introduced to each other."),
        H_(26, "peer", session="s1", name="eyzflow", space="sandbox", text="Four changes to the plan summary.", **{"from": "s3", "from_name": "project", "to_name": "eyzflow"}),
        H_(24, "job", session="s1", name="eyzflow", space="sandbox", status="done", text="Put all five additions into the plan.", account="orca"),
        H_(9, "peer", session="s1", name="eyzflow", space="sandbox", text="Answers on stale writes and publish events.", **{"from": "s2", "from_name": "mediaroom", "to_name": "eyzflow"}),
        H_(6, "peer", session="s1", name="eyzflow", space="sandbox", text="Answer on the read-only key question.", **{"from": "s2", "from_name": "mediaroom", "to_name": "eyzflow"}),
        H_(3, "note", name="tower", space="sandbox", text="The plan for the storefront live editor is finished and checked."),
    ], key=lambda e: e["at"])
    # The PRs' chips in the conversation.
    for k, pr in PRS.items():
        HISTORY.append({"at": pr["found_ms"], "kind": "pr", "id": k, "session": pr["session"], "name": pr["name"], "space": pr["space"], "text": pr["title"]})
    HISTORY.sort(key=lambda e: e["at"])
    Q_WHO[:] = [("s5", "mediaroom"), ("s6", "landing")]
    d_sync()


def space_of(address):
    return next((m["name"] for m in MISSIONS if any(x["address"] == address for x in m["sessions"])), "")


def name_of(address):
    return next((x["name"] for m in MISSIONS for x in m["sessions"] if x["address"] == address), address)


def d_of_question(q):
    """An escalated question set as a decision: one tab with recommendations gives answers to tap; more tabs go to the stepper."""
    recs = next((r["options"] for r in (q.get("recommendations") or []) if r["tab"] == 0), [])
    one = len(q["tabs"]) == 1
    perm = q["kind"] in ("claude-permission", "codex-menu")
    return {"id": "d-" + q["id"], "key": "question:" + q["id"], "kind": "permission" if perm else "question", "space": space_of(q["session"]),
            "sessions": [q["session"]], "title": q["title"] if perm or not one else q["tabs"][0]["question"],
            "why": "Permission" if perm else (f"{len(q['tabs'])} questions" if not one else "Question"), "detail": q.get("detail", ""),
            "options": [{"label": r["label"], "why": r.get("why", ""), "pick": i == 0} for i, r in enumerate(recs)] if one else [],
            "prs": [], "question_id": q["id"], "first_ms": q["at_ms"], "last_ms": q["at_ms"], "count": 1,
            "state": {"answering": "answering", "queued": "answering", "answered": "answered", "failed": "stopped"}.get(q["status"], "open")}


def muted_spaces():
    return {m["name"] for m in MISSIONS if m.get("mute")}


def d_pr(p, d):
    """A merge's PR as the decision lists it: what the confirm and merge calls need."""
    k = f"{p['repo']}#{p['number']}"
    full = PRS.get(k)
    view = pr_view(full) if full else {}
    checks = {"passing": "pass", "failing": "fail", "pending": "pending"}.get((view.get("checks") or {}).get("state"), "none")
    failing = (view.get("checks") or {}).get("failing") or []
    note = {"pass": "checks passing", "pending": "checks running", "none": "no checks ran"}.get(checks) or f"{len(failing)} check{'s' if len(failing) != 1 else ''} failing: {', '.join(failing)}"
    return {"key": k, "repo": p["repo"], "number": p["number"], "base": p["base"], "title": view.get("title", ""), "url": view.get("url", f"https://github.com/{p['repo']}/pull/{p['number']}"),
            "checks": checks, "note": note, "session": view.get("session"), "merge": view.get("merge") or {"can": True, "blocked": None, "needs_override": None, "warnings": []},
            **({"score": view["score"]} if view.get("score") else {})}


def d_view(d):
    v = dict(d, sessions=[{"address": a, "name": name_of(a), "waiting": d["kind"] != "merge"} for a in d["sessions"]],
             waiting=0 if d["kind"] == "merge" else len(d["sessions"]), muted=d["space"] in muted_spaces(), task=d.get("task"))
    if d["kind"] == "merge":
        v["prs"] = [d_pr(p, d) for p in d["prs"]]
        prod = lambda b: b in ("main", "master", "production", "release")
        v["options"] = [{"label": f"Merge #{p['number']} into {p['base']}", "why": p["note"], "pick": i == 0 and p["checks"] == "pass" and not prod(p["base"])} for i, p in enumerate(v["prs"])]
    picks = [o["label"] for o in v["options"] if o.get("pick")]
    v["pick"] = picks[0] if picks else None
    v.pop("detail", None)
    return v


def d_all():
    asked = [d_of_question(q) for q in QUESTIONS.values() if q.get("decision") == "escalate" and q["status"] != "answered"]
    return [d for d in list(DECISIONS.values()) + asked if d["state"] in ("open", "answering")]


def d_queue(space=None, with_muted=False):
    """In order: sessions waiting x minutes waited, heaviest first, then the oldest. The main queue leaves muted spaces out."""
    muted = muted_spaces()
    ds = [d_view(d) for d in d_all() if (d["space"] == space if space else (with_muted or d["space"] not in muted))]
    mins = lambda d: max(0, (now() - d["first_ms"]) // MIN)
    return sorted(ds, key=lambda d: (-d["waiting"] * mins(d), d["first_ms"]))


def d_count():
    return len(d_queue())


def m_view(m):
    return dict(m, needs=sum(1 for d in d_all() if d["space"] == m["name"]), goal=m.get("goal") or "", done_when=m.get("done_when") or "", report=m.get("report") or "",
                tower=m.get("tower"), quiet_since_ms=None, ready_since_ms=now() - 20 * MIN if m["state"] == "ready" else None,
                auto_close_ms=now() + 4 * 60 * MIN if m["state"] == "ready" else None)


def missions():
    return {"spaces": [m_view(m) for m in MISSIONS], "quiet": QUIET_NAMES, "closed_today": CLOSED_TODAY}


def d_event():
    return {"count": d_count(), "decisions": d_queue(with_muted=True)}


def d_sync():
    STATE.pop("decisions", None)
    STATE["decisions_count"] = d_count()
    STATE["tell_level"] = TELL[0]
    STATE["tell_voice"] = VOICE[0]
    STATE["brain_model"] = model_says()
    STATE["brains"] = [{"name": "tower", "space": None, "address": "t1", "status": "idle", "model": MODEL["running"][0]["model"], "effort": MODEL["running"][0]["effort"]}] + \
        [{"name": m["tower"]["name"], "space": m["name"], "address": m["tower"].get("address"), "status": m["tower"]["status"], "model": MODEL["running"][0]["model"], "effort": MODEL["running"][0]["effort"]}
         for m in MISSIONS if m.get("tower")]


def d_changed():
    """The queue changed: its event, the missions', and the state's count."""
    d_sync()
    publish("decisions", d_event())
    publish("missions", missions())
    set_state()


def d_close(d, state, answer=None):
    d["state"] = state
    DECISIONS.pop(d["id"], None)
    CLOSED_DECISIONS.insert(0, d)
    add_history({"at": now(), "kind": "decision", "id": d["id"], "space": d["space"], "status": state, "why": d.get("why"), "record": True,
                 "text": d["title"] + (f" -> {answer}" if answer else "")})
    d_changed()


def d_answer(d, body):
    """A choice, a raise or a permission: the words go to the sessions that wait on it, and it closes."""
    label = d["options"][body["choice"]]["label"] if "choice" in body else body.get("other", "")
    print(f"decision {d['id']}: {label!r}", flush=True)
    sent = [{"session": a, "name": name_of(a), "queue_id": i + 1, "sent": True} for i, a in enumerate(d["sessions"])]
    # A choice answered in words can become a rule; a permission, production data and the like never.
    can = d["kind"] in ("question", "choice") and not any(w in (d.get("why") or "").lower() for w in ("deploy", "delete", "production", "secret", "money"))
    d["rule"] = f"Make this a rule: when a session in {d['space']} asks \"{d['title']}\", answer \"{label}\" without asking me." if can else None
    d_close(d, "answered", label)
    return {"ok": True, "id": d["id"], "state": "answered", "answer": label, "sent": sent, "errors": [], "rule": {"can": can, "text": d["rule"]}}


def action_publish(a):
    ACTIONS[a["id"]] = a
    publish("action", dict(a))


def new_action(kind, space, dev, plan=None):
    a = {"id": "act_" + secrets.token_hex(4), "kind": kind, "space": space, "state": "running", "plan": plan, "receipt": None, "error": None,
         "by": "phone:" + dev["id"], "started_ms": now(), "finished_ms": None}
    action_publish(a)
    return a


def launch_plan(goal, setup, name, dry_run=True):
    name = name or slug_space(goal)
    made = not any(m["name"] == name for m in MISSIONS)
    sess = []
    for repo in setup["repos"]:
        short = repo.rstrip("/").split("/")[-1].replace("eyz-", "").replace("-serverless", "")
        sess.append({"name": f"{name}-{short}", "repo": repo, "address": None, "account": setup["account"], "worktree": f"{repo.rsplit('/', 1)[0]}/.tower-worktrees/{name}/{short}",
                     "branch": f"{name}/{short}", "port": None, "task": None, "error": None})
    first = SETUPS.index(setup) == 0
    return {"space": name, "goal": goal, "made": made, "dry_run": dry_run, "agenda": None, "sessions": sess,
            "assumed": {"setup": setup["name"], "setup_why": f"the first of {len(SETUPS)}" if first else "the one you picked",
                        "account": setup["account"], "account_why": "the setup's", "name": name, "name_why": "the goal's first two words, small ones left out",
                        "worktrees": True, "worktrees_why": "one per repo, on a branch of its own"}}


def run_launch(a, plan):
    """Opens the sessions one by one, as the real tower does (seconds each), saying each step."""
    receipt = json.loads(json.dumps(plan))
    receipt["dry_run"] = False
    receipt["agenda"] = "a" + secrets.token_hex(2)
    m = None
    for i, x in enumerate(receipt["sessions"]):
        time.sleep(1.4)
        addr = f"s{len(STATE['agents']) + 1}"
        short = x["repo"].rstrip("/").split("/")[-1].replace("eyz-", "").replace("-serverless", "")
        x.update(address=addr, port=PORT[0], task=f"{receipt['agenda']}.{i + 1}")
        PORT[0] += 1
        STATE["agents"].append({"address": addr, "name": short, "status": "working", "app": "claude-code", "account": x["account"]})
        if m is None:
            m = next((y for y in MISSIONS if y["name"] == plan["space"]), None)
            if m is None:
                m = {"name": plan["space"], "goal": plan["goal"], "done_when": "", "standing": False, "state": "open", "follow": False, "mute": False,
                     "report": None, "thread_count": 0, "sessions": [], "tower": {"name": "tower-" + plan["space"], "status": "starting", "address": None},
                     "requests": [{"id": receipt["agenda"], "title": plan["goal"], "state": "open", "done": 0, "total": len(plan["sessions"])}]}
                MISSIONS.append(m)
        m["sessions"].append(_ms(addr, short, "working", x["account"], f"{short}@{plan['space']}", x["port"], "Reading the goal"))
        a["receipt"] = receipt
        action_publish(a)
        d_changed()
    if m and m.get("tower"):
        m["tower"]["status"] = "ready"
    # The launch became an act: it can be taken back.
    asm = plan["assumed"]
    act = {"id": "r" + secrets.token_hex(6), "kind": "launch", "at_ms": now(), "by": a["by"], "space": plan["space"],
           "line": f"Launched {plan['space']}: {len(receipt['sessions'])} sessions, each in its own worktree.",
           "sessions": [{"address": x["address"], "name": x["name"], "label": f"{plan['space']} › {x['repo'].rstrip('/').split('/')[-1]}", "worktree": x["worktree"], "branch": x["branch"]} for x in receipt["sessions"]],
           "assumed": [{"what": k, "value": str(asm[k]) if k != "worktrees" else "one each", "why": asm.get(k + "_why")} for k in ("setup", "account", "name", "worktrees") if asm.get(k) is not None],
           "request": None, "state": "done", "undo": {"possible": True, "why": None}, "closing": None, "undone_ms": None}
    ACTS[act["id"]] = act
    receipt["act"] = act["id"]
    publish("act", act)
    a.update(state="done", finished_ms=now())
    action_publish(a)
    add_history({"at": now(), "kind": "note", "name": "tower", "space": plan["space"], "record": True,
                 "text": f"Launched {plan['space']}: {len(plan['sessions'])} sessions on {plan['sessions'][0]['account'] if plan['sessions'] else ''}, each in its own worktree."})
    d_changed()
    print(f"launched {plan['space']}", flush=True)


def run_close(a, m):
    time.sleep(1.5)
    kept = [{"path": f"~/Work/.tower-worktrees/{m['name']}/{x['name']}", "branch": x.get("branch"), "repo": None, "why": "uncommitted work"} for x in m["sessions"] if x["status"] == "working"]
    removed = [f"~/Work/.tower-worktrees/{m['name']}/{x['name']}" for x in m["sessions"] if x["status"] != "working"]
    if m in MISSIONS:
        MISSIONS.remove(m)
    CLOSED_TODAY.append(m["name"])
    for k in [k for k, d in DECISIONS.items() if d["space"] == m["name"]]:
        d_close(DECISIONS[k], "stopped")
    STATE["agents"] = [x for x in STATE["agents"] if x["address"] not in {y["address"] for y in m["sessions"]}]
    a.update(state="done", finished_ms=now(), receipt={
        "closed": m["name"], "sessions_ended": [{"address": x["address"], "name": x["name"]} for x in m["sessions"]], "sessions_left": [],
        "herdr_space": "closed", "worktrees_removed": removed, "kept_worktrees": kept, "requests_closed": [r["id"] for r in m.get("requests", [])],
        "tower_ended": [m["tower"]["name"]] if m.get("tower") else [], "report": m.get("report") or ""})
    action_publish(a)
    add_history({"at": now(), "kind": "space", "space": m["name"], "status": "closed", "record": True, "text": f"Closed {m['name']}."})
    d_changed()


def quiet_reply(text, space):
    """What the quiet tower says back: (what the chat shows, the sentences it speaks)."""
    low = text.lower()
    if "open" in low and "session" in low:
        where = space or "M5 Pro"
        m = next((x for x in MISSIONS if x["name"] == where), None)
        if m:
            a = f"s{len(STATE['agents']) + 1}"
            label = f"{where} › herder-tower 2"
            wt = f"herder-tower@{slug_space(where)}-{len(m['sessions']) + 1}"
            m["sessions"].append(_ms(a, label, "working", "orca", wt, None, "Reading its introduction"))
            STATE["agents"].append({"address": a, "name": label, "status": "working", "app": "claude-code", "account": "orca"})
            d_changed()
            # The receipt: what was opened and what was assumed, right after the request, with an undo.
            act = {"id": "r" + secrets.token_hex(6), "kind": "start", "at_ms": now(), "by": "tower-" + where if space else "tower", "space": space,
                   "line": f"Opened herder-tower 2 in {where}.", "sessions": [{"address": a, "name": "herder-tower-2", "label": label, "worktree": "~/Work/.tower-worktrees/" + wt, "branch": wt.replace("@", "/")}],
                   "assumed": [{"what": "account", "value": "orca", "why": "the space's sessions run on it"},
                               {"what": "model", "value": "default model", "why": "none was asked for: a session starts on its account's model"},
                               {"what": "folder", "value": "herder-tower", "why": "the best match for what was asked"},
                               {"what": "worktree", "value": "its own", "why": "the space's sessions each have one"}],
                   "request": {"at": now(), "text": text}, "state": "done", "undo": {"possible": True, "why": None}, "closing": None, "undone_ms": None}
            ACTS[act["id"]] = act
            e = {"at": now(), "kind": "act", "id": act["id"]}
            if space:
                e["space"] = space
            add_history(e)
            ACT_LINE[act["id"]] = len(HISTORY)
            publish("act", act)
        tail = ", introduced to the others" if space else ""
        return (f"It's reading its introduction now{tail}." if m else f"**Opened in {where}.** One session in its own worktree{tail}.",
                [f"Opened in {where}.", f"One session in its own worktree{tail}."])
    q = d_queue(space) if space else d_queue()
    openq = [d for d in q if d["state"] in ("open", "stopped")]
    busy = [x["name"] for m in MISSIONS if (not space or m["name"] == space) for x in m["sessions"] if x["status"] == "working"]
    head = (f"{len(openq)} need{'s' if len(openq) == 1 else ''} you" if openq else "Nothing needs you") + (" here" if space else "") + (f", {len(busy)} working" if busy else "") + "."
    lines = [("" if space else d["space"] + ": ") + d["title"] for d in openq]
    return ("**" + head + "**" + "".join("\n- " + l for l in lines), [head] + lines[:2])


def slug_space(goal):
    small = {"a", "an", "the", "of", "for", "to", "in", "on", "and", "with", "as", "at", "by"}
    words = [w for w in re.sub(r"[^a-z0-9 ]", "", goal.lower()).split() if w not in small]
    base = "-".join(words[:2]) or "new-space"
    name, n = base, 2
    while any(m["name"] == name for m in MISSIONS):
        name, n = f"{base}-{n}", n + 1
    return name


def act_changed(act):
    publish("act", act)
    i = ACT_LINE.get(act["id"])
    if i:
        publish("chat", {"op": "update", "message": chat_message(HISTORY[i - 1], i)})


def act_undo(act, force):
    """Ends what the act opened: a session at work would lose it (409 held) unless forced; then its worktree is kept."""
    gone = {x["address"] for x in act["sessions"]}
    working = [x for m in MISSIONS for x in m["sessions"] if x["address"] in gone and x["status"] == "working"]
    if working and not force:
        return None, [{"address": x["address"], "name": x["name"], "why": "has work in its worktree that is nowhere else: 1 uncommitted change (notes.txt)"} for x in working]
    for m in MISSIONS:
        m["sessions"] = [x for x in m["sessions"] if x["address"] not in gone]
    STATE["agents"] = [x for x in STATE["agents"] if x["address"] not in gone]
    kept = [{"path": x["worktree"], "why": "1 uncommitted change (notes.txt)"} for x in act["sessions"] if working]
    closing = "Closed it." + (" Kept its worktree: 1 uncommitted change." if kept else " Nothing else changed.")
    act.update(state="undone", undo={"possible": False, "why": None}, closing=closing, undone_ms=now())
    act_changed(act)
    d_changed()
    return {"act": act, "closing": closing, "sessions_ended": sorted(gone), "worktrees_removed": [] if kept else [x["worktree"] for x in act["sessions"]],
            "kept_worktrees": kept, "space_closed": False}, None


NETWORK = {"range": None, "name": "Office", "lan": None}


def network_view():
    """The Mac's addresses and the network it is on (hello, the hosts event, GET /v1/network)."""
    hs = STATE.get("lan_hosts") or [{"kind": "lan", "host": LINK_HOST[0], "port": LINK_HOST[1]}]
    lan = next((h for h in hs if h["kind"] == "lan"), None)
    r = ".".join(lan["host"].split(".")[:3]) if lan and lan["host"].count(".") == 3 else None
    net = dict(NETWORK, range=NETWORK.get("range") or r, lan={"host": lan["host"], "port": lan["port"]} if lan else None)
    return {"hosts": hs, "network": net, "magic_dns": (STATE.get("tailscale") or {}).get("dns_name"), "tailscale": STATE.get("tailscale") or {"installed": False, "running": False}}


def quiet_route(h, method, seg, q, b):
    """The Quiet Tower calls (docs/mobile-api.md, "The Quiet Tower"); True when handled."""
    body = json.loads(b or b"{}") if method in ("POST", "PUT") and not h.headers.get("Content-Type", "").startswith("multipart/") else {}
    if seg == ["decisions"] and method == "GET":
        space = q.get("space")
        r = {"space": space, "count": len(d_queue(space)) if space else d_count(), "decisions": d_queue(space)}
        if q.get("all") == "1":
            r["closed"] = [d_view(d) for d in CLOSED_DECISIONS[:50]]
        h.send(200, r)
        return True
    if len(seg) >= 2 and seg[0] == "decisions":
        did = seg[1]
        d = DECISIONS.get(did) or next((x for x in CLOSED_DECISIONS if x["id"] == did), None)
        qs = QUESTIONS.get(did[2:]) if did.startswith("d-") else None
        if d is None and qs is None:
            h.err(404, "no_decision", "no decision with that id")
            return True
        if len(seg) == 2 and method == "GET":
            h.send(200, {"decision": d_view(d) if d else d_of_question(qs)})
            return True
        if len(seg) == 2 and method == "DELETE":
            if d and d["state"] not in ("open", "answering"):
                h.send(200, {"dismissed": did, "state": d["state"]})
                return True
            if d:
                d_close(d, "dismissed")
            if qs:
                q_close(qs["id"])
            h.send(200, {"dismissed": did, "state": "dismissed"})
            return True
        if seg[2:] == ["answer"] and method == "POST":
            if qs:  # the question set's own answer, typed into the session
                view = d_of_question(qs)
                if "answers" in body:
                    answers = body["answers"]
                else:
                    recs = next((r["options"] for r in (qs.get("recommendations") or []) if r["tab"] == 0), [])
                    choice = recs[body["choice"]]["choice"] if "choice" in body and body["choice"] < len(recs) else {"other": body.get("other", "")}
                    answers = [{"tab": 0, "choice": choice}]
                print(f"decision {view['id']} (question {qs['id']}): {json.dumps(answers)}", flush=True)
                q_update(qs["id"], status="answering", answered_with=answers)

                def finish():
                    time.sleep(2)
                    q_update(qs["id"], status="answered")
                    time.sleep(0.5)
                    q_close(qs["id"])
                threading.Thread(target=finish, daemon=True).start()
                h.send(200, {"ok": True, "id": did, "state": "answering", "answered": [{"session": qs["session"], "question": qs["id"], "status": "answering"}], "errors": []})
                return True
            if d["state"] not in ("open", "answering"):
                h.err(409, "closed", f"{did} is {d['state']} already", state=d["state"])
                return True
            if d["kind"] == "merge":
                h.err(400, "merge", "a merge is merged through its PRs' confirm and merge calls", prs=[f"{p['repo']}#{p['number']}" for p in d["prs"]])
                return True
            if "choice" in body and not (0 <= body["choice"] < len(d["options"])) or ("choice" not in body and not (body.get("other") or "").strip()):
                h.err(400, "bad_answer", "no such option, or no words")
                return True
            h.send(200, d_answer(d, body))
            return True
        if seg[2:] == ["rule"] and method == "POST":
            if d is None or d["state"] in ("open", "answering"):
                h.err(409, "not_answered", "it is still open")
                return True
            if not d.get("rule"):
                h.err(409, "no_rule", "no rule is offered for it")
                return True
            print(f"rule for {did}: {d['rule']}", flush=True)
            threading.Thread(target=reply_to, args=(d["rule"], (), d["space"] or None), daemon=True).start()
            h.send(202, {"sent": d["rule"], "space": d["space"] or None})
            return True
        if seg[2:] == ["interpret"] and method == "POST":
            words = (body.get("text") or "").lower()
            i = next((i for i, o in enumerate(d["options"]) if o["label"].lower() in words), 0 if "first" in words else 1 if "second" in words else None)
            h.send(200, {"tab": 0, "choice": {"options": [i] if i is not None else [], "other": None if i is not None else body.get("text")},
                         "answer": {"choice": i} if i is not None else ({"other": body.get("text")} if words else None)})
            return True
    if seg == ["missions"] and method == "GET":
        h.send(200, missions())
        return True
    if len(seg) == 3 and seg[0] == "spaces" and method == "POST":
        m = next((x for x in MISSIONS if x["name"].lower() == seg[1].lower()), None)
        if m is None:
            h.err(404, "no_space", f"no space called \"{seg[1]}\"")
            return True
        if seg[2] == "goal":
            for k in ("goal", "done_when", "standing"):
                if k in body and body[k] is not None:
                    m[k] = body[k]
            d_changed()
            v = m_view(m)
            h.send(200, {"space": m["name"], "goal": v["goal"], "done_when": v["done_when"], "standing": m["standing"], "state": m["state"], "report": v["report"], "mission": v})
            return True
        if seg[2] in ("follow", "mute"):
            if not isinstance(body.get("on"), bool):
                h.err(400, "bad_on", "on is true or false")
                return True
            m[seg[2]] = body["on"]
            d_changed()
            h.send(200, {"space": m["name"], seg[2]: m[seg[2]], "mission": m_view(m)})
            return True
        if seg[2] == "close":
            working = [x for x in m["sessions"] if x["status"] in ("working", "blocked")]
            if working and not body.get("force"):
                names = ", ".join(f"{x['name']} ({x['address']})" for x in working)
                h.err(409, "busy", f"\"{m['name']}\" isn't closed: {names} {'is' if len(working) == 1 else 'are'} working. Wait for them, or send {{\"force\": true}} (their work in progress is cut off).",
                      sessions=[{"address": x["address"], "name": x["name"], "status": x["status"]} for x in working])
                return True
            running = next((a for a in ACTIONS.values() if a["kind"] == "close" and a["space"] == m["name"] and a["state"] == "running"), None)
            if running:
                h.err(409, "closing", f"\"{m['name']}\" is being closed", action=running["id"])
                return True
            a = new_action("close", m["name"], h.device())
            threading.Thread(target=run_close, args=(a, m), daemon=True).start()
            h.send(202, {"action": a})
            return True
        return False
    if seg == ["thread"] and method == "GET":
        msgs = THREADS.get(q.get("space", ""), []) if q.get("space") else sorted((x for v in THREADS.values() for x in v), key=lambda x: x["at"])
        before = q.get("before")
        if before:
            i = next((i for i, x in enumerate(msgs) if x["id"] == before), len(msgs))
            msgs = msgs[:i]
        lim = min(200, int(q.get("limit", 50)))
        h.send(200, {"space": q.get("space"), "messages": msgs[-lim:], "more": len(msgs) > lim})
        return True
    if seg == ["setups"] and method == "GET":
        h.send(200, {"setups": SETUPS})
        return True
    if seg == ["launch"] and method == "POST":
        goal = (body.get("goal") or "").strip()
        if not goal:
            h.err(400, "empty", "a space needs a goal")
            return True
        want = (body.get("setup") or "").lower()
        setup = next((x for x in SETUPS if want and want in x["name"].lower()), None) if want else SETUPS[0]
        if setup is None:
            h.err(404, "no_setup", f"no setup called \"{body.get('setup')}\"")
            return True
        plan = launch_plan(goal, setup, body.get("name"), dry_run=bool(body.get("dry_run")))
        if body.get("dry_run"):
            h.send(200, plan)
            return True
        running = next((a for a in ACTIONS.values() if a["kind"] == "launch" and a["state"] == "running"), None)
        if running:
            h.err(409, "launching", f"\"{running['space']}\" is being launched: one at a time", action=running["id"])
            return True
        a = new_action("launch", plan["space"], h.device(), plan=plan)
        threading.Thread(target=run_launch, args=(a, plan), daemon=True).start()
        print(f"launching {plan['space']} with {setup['name']}", flush=True)
        h.send(202, {"action": a})
        return True
    if seg == ["acts"] and method == "GET":
        h.send(200, {"acts": sorted(ACTS.values(), key=lambda a: -a["at_ms"])[:50]})
        return True
    if len(seg) >= 2 and seg[0] == "acts":
        act = ACTS.get(seg[1])
        if act is None:
            h.err(404, "no_act", "no act with that id")
            return True
        if len(seg) == 2 and method == "GET":
            h.send(200, {"act": act})
            return True
        if seg[2:] == ["undo"] and method == "POST":
            if act["state"] == "undone":
                h.err(409, "undone", f"{act['id']} is undone already", state="undone")
                return True
            r, held = act_undo(act, bool(body.get("force")))
            if held:
                names = ", ".join(f"{x['name']} ({x['address']}) {x['why']}" for x in held)
                h.err(409, "held", f"not undone: {names}. Send {{\"force\": true}} to undo it anyway.", sessions=held)
                return True
            print(f"undid {act['id']}", flush=True)
            h.send(200, r)
            return True
    if seg == ["actions"] and method == "GET":
        h.send(200, {"actions": sorted(ACTIONS.values(), key=lambda a: -a["started_ms"])[:50]})
        return True
    if len(seg) == 2 and seg[0] == "actions" and method == "GET":
        a = ACTIONS.get(seg[1])
        h.send(200, {"action": a}) if a else h.err(404, "no_action", "no action with that id")
        return True
    if seg == ["tell"]:
        if method == "POST":
            if "level" in body and body["level"] not in ("decisions", "results", "everything"):
                h.err(400, "bad_level", "level is decisions, results or everything")
                return True
            if "voice" in body and body["voice"] not in ("off", "decisions", "replies"):
                h.err(400, "bad_voice", "voice is off, decisions or replies")
                return True
            r = {}
            if "level" in body:
                r["was"], TELL[0] = TELL[0], body["level"]
            if "voice" in body:
                r["voice_was"], VOICE[0] = VOICE[0], body["voice"]
                STATE["muted"] = VOICE[0] == "off"
            STATE["tell_level"], STATE["tell_voice"] = TELL[0], VOICE[0]
            set_state()
            h.send(200, dict(r, level=TELL[0], voice=VOICE[0]))
            return True
        h.send(200, {"level": TELL[0], "voice": VOICE[0]})
        return True
    if seg == ["model"]:
        if method == "POST":
            ids = {c["id"]: c["id"] for c in MODEL["choices"]["models"]} | {c["label"]: c["id"] for c in MODEL["choices"]["models"]}
            if "model" in body and body["model"] not in ids:
                h.err(400, "bad_model", f"no model called \"{body['model']}\"")
                return True
            if "effort" in body and body["effort"] not in MODEL["choices"]["efforts"]:
                h.err(400, "bad_effort", "effort is " + ", ".join(MODEL["choices"]["efforts"]) + f": not \"{body['effort']}\"")
                return True
            if "model" in body:
                MODEL["model"] = ids[body["model"]]
            if "effort" in body:
                MODEL["effort"] = body["effort"]
            restarted = []
            if body.get("restart"):
                for r in MODEL["running"]:
                    r.update(model=MODEL["model"], effort=MODEL["effort"], restart=False)
                    restarted.append(r["brain"])
                restarted += [m["tower"]["name"] for m in MISSIONS if m.get("tower")]
            for r in MODEL["running"]:
                r["restart"] = (r["model"], r["effort"]) != (MODEL["model"], MODEL["effort"])
            MODEL["restart_needed"] = any(r["restart"] for r in MODEL["running"])
            d_sync()
            set_state()
            print(f"model {MODEL['model']} at {MODEL['effort']}" + (f", restarted {restarted}" if restarted else ""), flush=True)
            h.send(200, dict(model_says(), restarted=restarted))
            return True
        h.send(200, dict(model_says(), restarted=[]))
        return True
    return False


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
        if (method, seg) == ("POST", ["fake", "network"]):
            # The Mac moved (another network, Tailscale on or off): {"hosts": [...], "name": "Home"}; the hosts event says so.
            d = json.loads(self.body() or b"{}")
            if "hosts" in d:
                STATE["lan_hosts"] = d["hosts"]
            NETWORK["name"] = d.get("name", NETWORK["name"])
            NETWORK["range"] = d.get("range")
            publish("hosts", network_view())
            return self.send(200, network_view())
        if (method, seg) == ("POST", ["fake", "pr"]):
            # Drive a PR's state for screenshots: {"key": "example/api#88", "checks": {...}, "waiting": true, ...}.
            d = json.loads(self.body() or b"{}")
            k = d.pop("key", "")
            if k not in PRS:
                return self.err(404, "no_pr", "no such PR")
            PRS[k].update(d)
            pr_changed(k, "update" if PRS[k]["state"] == "open" else "closed")
            return self.send(200, {"pr": pr_view(PRS[k])})
        if (method, seg) == ("POST", ["fake", "decision"]) and QUIET[0]:
            # Raise a decision (to see its card and its notification): {"title": "…", "space": "sandbox", "why": "…", "options": [{"label": "Yes", "pick": true}, {"label": "No"}], "sessions": ["s1"]}.
            d = json.loads(self.body() or b"{}")
            did = d.get("id") or "d-" + secrets.token_hex(3)
            DECISIONS[did] = {"id": did, "key": d.get("key", did), "kind": d.get("kind", "raised"), "space": d.get("space", "sandbox"), "sessions": d.get("sessions", []),
                              "title": d.get("title", "A decision"), "why": d.get("why", ""), "detail": d.get("detail", ""),
                              "options": [{"label": o.get("label", ""), "why": o.get("why", ""), "pick": bool(o.get("pick"))} for o in d.get("options", [])],
                              "prs": d.get("prs", []), "question_id": None, "first_ms": now(), "last_ms": now(), "count": 1, "state": "open"}
            d_changed()
            return self.send(200, {"decision": d_view(DECISIONS[did])})
        if (method, seg) == ("POST", ["pair"]):
            b = json.loads(self.body() or b"{}")
            iid = b.get("install_id")
            # A bad install id is refused before the code is used.
            if iid is not None and not re.fullmatch(r"[A-Za-z0-9_-]{8,64}", str(iid)):
                return self.err(400, "bad_install_id", "install_id is 8 to 64 letters, digits, - and _")
            if b.get("code") != CODE:
                return self.err(403, "wrong_code", "that isn't the code on the Mac", tries_left=4)
            tok = "htk_" + secrets.token_urlsafe(32)
            # The same install paired before: that device, re-paired in place (its old token refused from now on).
            old = next(((t, d) for t, d in TOKENS.items() if iid and d.get("install_id") == iid), None)
            if old:
                del TOKENS[old[0]]
                dev = dict(old[1], name=b.get("name", old[1]["name"]))
            else:
                dev = {"id": "d_" + secrets.token_hex(4), "name": b.get("name", "phone"), **({"install_id": iid} if iid else {})}
            TOKENS[tok] = dev
            save_tokens()
            print(f"{'re-paired' if old else 'paired'} {dev['name']} ({dev['id']})", flush=True)
            nv = network_view() if QUIET[0] else {}
            nv.pop("tailscale", None)
            return self.send(200, {"token": tok, "device": {"id": dev["id"], "name": dev["name"]}, "fingerprint": FP, "api": 1, "repaired": bool(old),
                                   **({"features": ["quiet", "acts", "pairing", "scores"], **nv} if QUIET[0] else {})})
        dev = self.device()
        if dev is None:
            return self.err(401, "unauthorized", "unknown token")
        if seg[:1] == ["behaviors"]:
            if behaviors_route(self, method, seg, q, self.body() if method in ("POST", "PUT") else b""):
                return
        if seg[:1] == ["prs"]:
            return prs_route(self, method, seg, dev, self.body() if method == "POST" else b"")
        if seg[:1] in (["questions"], ["questions-policy"]):
            if questions_route(self, method, seg, q, self.body() if method in ("POST", "PUT") else b""):
                return
        if QUIET[0] and (seg[:1] in (["decisions"], ["missions"], ["thread"], ["setups"], ["launch"], ["tell"], ["actions"], ["model"], ["acts"]) or (len(seg) == 3 and seg[0] == "spaces")):
            if quiet_route(self, method, seg, q, self.body() if method in ("POST", "PUT") else b""):
                return
        if method == "GET":
            if seg == ["network"] and QUIET[0]:
                return self.send(200, network_view())
            if seg == ["hello"]:
                return self.send(200, {"api": 1, "tower": "fake", "device": dev, "voice": "af_bella", "phone_is_voice": any(v for _, v in SUBS), "audio_route": ROUTE.get(dev["id"], "phone"),
                                       **({"features": ["quiet", "acts", "pairing", "scores"]} if QUIET[0] else {})})
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
                sp = q.get("space")
                return self.send(200, {"entries": [e for e in HISTORY if e["at"] < before and (not sp or (e.get("space") or "").lower() == sp.lower())][-lim:]})
            if seg == ["chat"]:
                t = thread()
                if QUIET[0] and q.get("space"):
                    t = [m for m in t if m.get("space") == q["space"] and m["kind"] in ("request", "answer", "receipt")]
                elif QUIET[0] and q.get("main") == "1":
                    t = [m for m in t if not (m.get("space") and m["kind"] in ("request", "answer"))]
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
                space = None
                if ctype.startswith("multipart/"):
                    t, files = multipart(b, ctype)
                    paths = [f"/tower/attachments/{now()}-phone/{i + 1}-{n}" for i, (n, _) in enumerate(files)]
                    m = re.search(rb'name="space"\r\n\r\n([^\r]*)', b)
                    space = m.group(1).decode() if m else None
                else:
                    d = json.loads(b)
                    t, paths, space = d["text"], [], d.get("space")
                if space:
                    print(f"ask for the {space} tower: {t!r}", flush=True)
                threading.Thread(target=reply_to, args=(t, paths, space), daemon=True).start()
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
            if len(seg) == 3 and seg[0] == "sessions" and seg[2] == "show":
                print(f"show {seg[1]} in Herdr", flush=True)
                return self.send(200, {"shown": seg[1]})
            if seg == ["hush"]:
                HUSHED[0] = True
                publish("hush", {}, voice_only=True)
                set_state(phase="idle", speaking=False)
                return self.send(200, {"ok": True})
            if seg == ["mute"]:
                # The tower-wide mute: stops talking now and says nothing, anywhere, until unmuted.
                on = bool(json.loads(b or b"{}").get("muted"))
                if on:
                    HUSHED[0] = True
                    publish("hush", {}, voice_only=True)
                    set_state(muted=True, phase="idle", speaking=False)
                else:
                    set_state(muted=False)
                print(f"muted: {on}", flush=True)
                return self.send(200, {"muted": on})
            if seg == ["mode"]:
                set_state(mode=json.loads(b)["mode"])
                return self.send(200, {"mode": STATE["mode"]})
            if seg == ["voice"] and q.get("space") and QUIET[0] and not any(m["name"].lower() == q["space"].lower() for m in MISSIONS):
                return self.err(404, "no_space", f"no space called \"{q['space']}\"")
            if seg in (["voice"], ["transcribe"]):
                if b[:4] != b"RIFF" and not self.headers.get("Content-Type", "").startswith("multipart/"):
                    return self.err(415, "bad_audio", "send audio/wav")
                secs = max(0.0, (len(b) - 44) / 32000)
                heard = whisper(b) if b[:4] == b"RIFF" else None
                if heard is None:
                    heard = f"a {secs:.1f} second take"
                if seg == ["transcribe"] or q.get("send") == "0":
                    return self.send(200, {"heard": heard, "sent": False})
                sp = q.get("space")
                set_state(heard=heard, heard_ms=now())
                threading.Thread(target=reply_to, args=(heard, (), sp), daemon=True).start()
                return self.send(200, {"heard": heard, "sent": True, **({"space": sp} if sp else {})})
        if (method, seg) == ("PUT", ["device", "audio"]):
            r = json.loads(self.body() or b"{}").get("route")
            if r not in ("phone", "mac"):
                return self.err(400, "bad_route", "route is phone or mac")
            ROUTE[dev["id"]] = r
            return self.send(200, {"route": r})
        if method in ("PATCH", "PUT") and seg == ["device"] and QUIET[0]:
            name = (json.loads(self.body() or b"{}").get("name") or "").strip()
            if not name:
                return self.err(400, "bad_name", "a name is needed")
            dev["name"] = name
            save_tokens()
            print(f"renamed {dev['id']}: {name}", flush=True)
            return self.send(200, {"device": {"id": dev["id"], "name": name}})
        if (method, seg) == ("DELETE", ["device"]):
            for t, d in list(TOKENS.items()):
                if d is dev:
                    del TOKENS[t]
                    save_tokens()
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
            out("hello", {"api": 1, "device": dev["id"], "voice": voice, **({"features": ["quiet", "acts", "pairing", "scores"], **network_view()} if QUIET[0] else {})})
            out("state", STATE)
            if QUIET[0]:
                # Right after the first state: the whole open list (a muted space's marked), then the missions.
                out("decisions", d_event())
                out("missions", missions())
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

    def do_PATCH(self):
        self.route("PATCH")


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
    ap.add_argument("--hosts", default="", help="every address, as the Tailscale-era link: lan:<ip>:<port>,tailscale:<ip>:<port> (e.g. a LAN that isn't there, then 10.0.2.2 as the tailnet)")
    ap.add_argument("--dns", default="", help="the MagicDNS name to put in the link and the state")
    ap.add_argument("--legacy", action="store_true", help="a tower from before the Quiet Tower: no decisions, missions, thread, setups, launch or tell (404), the shop's made-up data")
    a = ap.parse_args()
    QUIET[0] = not a.legacy
    if QUIET[0]:
        quiet_world()
    crt, key, FP = cert()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(crt, key)
    load_tokens()
    # On connect the app asks for a dozen things at once: a backlog of 5 (the default) drops some of them.
    ThreadingHTTPServer.request_queue_size = 128
    srv = ThreadingHTTPServer((a.host, a.port), H)
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    LINK_HOST[:] = [a.link_host, a.port]
    link = f"herdertower://pair?h={a.link_host}&p={a.port}&c={CODE}&fp={FP}"
    if a.hosts:
        # As the tower on its tower-tailscale branch: h/p the first address, then hosts= and dns= (docs/mobile-api.md).
        first = a.hosts.split(",")[0].split(":")
        link = f"herdertower://pair?h={first[1]}&p={first[2]}&c={CODE}&fp={FP}&hosts={a.hosts}" + (f"&dns={a.dns}" if a.dns else "")
        hs = []
        for e in a.hosts.split(","):
            kind, ip, port = e.split(":")
            h = {"kind": kind, "host": ip, "port": int(port)}
            if kind == "tailscale" and a.dns:
                h["dns_name"] = a.dns
            hs.append(h)
        ts = next((h for h in hs if h["kind"] == "tailscale"), None)
        STATE["lan_hosts"] = hs
        STATE["tailscale"] = {"installed": True, "running": ts is not None, "state": "running" if ts else "stopped", "ip": ts and ts["host"], "dns_name": a.dns or None}
    # FAKE_QUESTIONS=0: no question sets come up (the queue stays the design's three decisions).
    if os.environ.get("FAKE_QUESTIONS", "1") != "0":
        threading.Thread(target=questions_life, daemon=True).start()
    if not QUIET[0]:
        threading.Thread(target=pr_life, daemon=True).start()
    print(f"fake tower on https://{a.host}:{a.port}\ncode {CODE}\nfingerprint {' '.join(FP[i:i+4] for i in range(0, 64, 4))}\nLINK {link}", flush=True)
    srv.serve_forever()
