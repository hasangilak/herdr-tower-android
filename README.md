# Tower for Android

A phone remote for the herder tower: it mirrors the orb's panel (state, spaces and sessions, jobs, history, chat, screenshots), lets you act on every agent (ask, approve or refuse what a blocked agent asks, focus a space, hush, the Mac microphone's mode) and can be the tower's voice (you talk into the phone; the tower's replies play on the phone and the Mac stays silent).

It speaks the tower's phone API v1, `docs/mobile-api.md` in herder-tower (branch `mobile-api`).

## Build

Needs JDK 17+ (here Homebrew's `openjdk@21`) and the Android SDK with platform 36 (`local.properties` points at it; it's not committed).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew testDebugUnitTest      # 68 unit tests: API client against a pinned HTTPS fake, SSE, pairing links and QR, WAV, orb port, suggestions, markdown
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Pairing

On the Mac: `tower phone enable`, then `tower phone pair`. Then either:

- scan the QR in the app (Pair → Scan QR), or with the phone's camera app (the `herdertower://pair` link opens the app);
- or type the address, port and code (Pair → Enter by hand). The app shows the certificate fingerprint it got, grouped by four, to compare with what the Mac printed;
- or, on the emulator, open the link the Mac printed:
  `adb shell am start -a android.intent.action.VIEW -d '"herdertower://pair?h=…&p=7843&c=…&fp=…"' dev.herdertower.phone`

The emulator reaches the tower at the Mac's LAN address (the QR's `h`), not at `10.0.2.2`: the tower listens only on that address.

## Security

- HTTPS only. The tower's self-signed certificate is pinned by its SHA-256 fingerprint (from the QR, or compared by eye). Nothing else is trusted, and hostnames aren't checked, because the pin already identifies the server.
- The bearer token comes back once, at pairing. It's stored encrypted with an AES-GCM key held in the Android Keystore. It's never logged, excluded from backups, and `Credentials.toString()` hides it.
- Unpair (menu) calls `DELETE /v1/device` and forgets everything. A revoked token (401) shows "Pair again".

## Voice

- Turn on **Phone is the voice** (Talk tab). The app keeps `GET /v1/events?voice=1` open in a foreground service (microphone + media playback, Wi-Fi lock, partial wake lock), so it keeps working with the screen off. The notification has Talk, Hush and Stop.
- **Hold** the orb to talk and let go to send, or **tap** it once: it listens until you pause (1.2 s), or until you tap again. Starting a take hushes the tower (here and `POST /v1/hush`). Takes are 16 kHz mono 16-bit WAV, posted to `/v1/voice`. What the Mac heard is shown.
- Reply clips (`audio` events) are fetched with the token as soon as they're announced. They play in event order, gapless, through one streaming `AudioTrack`. `hush` drops the queue; `pause`/`resume` hold playback.
- The stream reconnects after 1, 2, 5, then every 10 s. The banner says why and has "Retry now".

## Trying it without the tower

`tools/fake_tower.py` speaks enough of the API to pair, browse, ask, approve, talk and hear tones as replies:

```sh
python3 tools/fake_tower.py          # prints a pairing link for the emulator (10.0.2.2:7899)
adb shell am start -a android.intent.action.VIEW -d '"<the link>"' dev.herdertower.phone
```

## Typing

Writing is as good as talking. Home and Chat share one draft. It is saved on every change, so it survives rotation, switching apps, the process being killed, and going to the voice screen and back.
- On a hardware keyboard, Enter sends and Shift+Enter starts a new line. The keyboard's Send action sends too.
- Files come from ＋: Photos and videos (the photo picker, several at once), Files (anything: PDF, text, …), or Camera. They can also be pasted or inserted from the keyboard. Each becomes the panel's token where you were typing (`[Image #N]`, `[Video #N]`, `[File #N: name]`), with a thumbnail or file card above. Remove one and its token goes; delete a token and the file goes. They go with the text as multipart `POST /v1/ask`, streamed from disk: six at most, pictures 15 MB (HEIC or very large photos are re-encoded as JPEG), videos and files 50 MB.
- Dictate (the small mic) puts what you say into the draft as you say it, after what's there, to edit before sending. While connected it uses the tower's Whisper: the take so far goes to `POST /v1/transcribe` about once a second (one request at a time, only once speech is heard), and the whole take once more at the end. Offline it falls back to the phone's own recognizer.
- Sent pictures show in your bubbles (`GET /v1/attachments/…`) and open in the zoomable viewer; videos and files show by name. While the brain works, the chat's working line shows its step and the turn's time (`work` in the state).
- The round button sends when there's a draft and talks to the tower when there isn't.

## The look

Home, Voice and Chat are the main flow. Home has a greeting, suggestions from live data (a blocked session, status, catch up, news in a space, focus), and a roomy ask box with the voice toggle, the space focus and a mic. Spaces, Activity, Screenshots and settings open from the menu sheet. The colours are warm copper falling into black (light: peach into cream); every text pair was checked for at least 4.5:1 contrast. Motion is slow and switches off when the system asks for no animation.

The orb is the Mac's own, ported from herder-tower's `src/orb`:
- the liquid physics (`liquid.rs`), the sim (`sim.rs`) and the phase choreography with its cues (`render.rs`'s `Drive`: calm, listening cyan, replying periwinkle, alerts amber, blocked swells and the red dot), line for line in `orb/`;
- the raymarched glass cup of `orb.metal` as an AGSL shader (Android 13+; a gradient stand-in before).

AGSL limits a program's size, and the 45-mode surface plus the boiling noise in every march step exceed it. So `liquid()` and `boil()` are evaluated on the CPU each frame into two direction maps the shader samples. The march (160 steps), colour ramps, tint, halo and transparency are the Mac's. The Mac's motion trail (a feedback texture) is left out.

## Layout

- `api/`: `PairLink`, `Fingerprint`, `PinnedTrustManager`, `CertificateProbe`, `TowerClient` (every call), `SseParser`, `TowerEvent`, models
- `data/`: `KeystoreCredentialStore`, `TowerSession` (the one stream, backoff, live state and lists, actions)
- `voice/`: `Take` (recorder), `Wav`, `ClipPlayer`, `VoiceController` (hold, tap, barge-in), `VoiceService`
- `orb/`: `Liquid`, `Sim`, `Drive`/`Cue`, `OrbShader` (AGSL), `OrbMaps`, `TowerOrb`
- `ui/`: `Shell` (routes, menu sheet), Home, Voice, Chat (markdown replies), Spaces, Activity, Session, Screenshots (gallery and viewer), Pairing; `HomeModel` and `Markdown` hold the logic, tested on the JVM; `Previews.kt` has Home and Chat in both themes. Account badges use the Mac's per-name hue (FNV-1a; the default account is outlined).
