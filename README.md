# Tower for Android

A phone remote for the herder tower: it mirrors the orb's panel (state, spaces and sessions, jobs, history, chat), lets you act on every agent (ask, approve or refuse what a blocked agent asks, focus a space, hush, the Mac microphone's mode) and can be the tower's voice (you talk into the phone; the tower's replies play on the phone and the Mac stays silent).

It speaks the tower's phone API v1, `docs/mobile-api.md` in herder-tower (branch `mobile-api`).

## Build

Needs JDK 17+ (here Homebrew's `openjdk@21`) and the Android SDK with platform 36 (`local.properties` points at it; it's not committed).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew testDebugUnitTest      # unit tests (API client against a pinned HTTPS fake, SSE, pairing links, WAV)
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

## Layout

- `api/`: `PairLink`, `Fingerprint`, `PinnedTrustManager`, `CertificateProbe`, `TowerClient` (every call), `SseParser`, `TowerEvent`, models
- `data/`: `KeystoreCredentialStore`, `TowerSession` (the one stream, backoff, live state and lists, actions)
- `voice/`: `Take` (recorder), `Wav`, `ClipPlayer`, `VoiceController` (hold, tap, barge-in), `VoiceService`
- `ui/`: Pairing, Talk, Chat, Spaces, Activity and Session screens. Colours are the orb panel's, including the per-account badge hue (FNV-1a of the name, the same as on the Mac; the default account is outlined).
