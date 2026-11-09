# PR chips: what the tower's API doesn't give the app yet

Against herder-tower branch `tower-pr-chips` (docs/mobile-api.md, "PRs").
The app builds on what is there and works around these gaps. It doesn't
invent fields.

1. **A PR doesn't name its chat message.** `GET /v1/prs` and the `pr` event
   give the PR but not the id of its chip (`h1840`). Tapping a PR's
   notification has to find the chip by its `pr.key` in the loaded thread.
   If the chip is older than the loaded thread, the app pages back (up to
   6 pages of 60). If it still isn't found, the app says so.
   *Ask:* a `message` field (the chip's chat id) in the PR view.

2. **The token's expiry uses the Mac's clock.** `expires_ms` is on the
   Mac's clock, and the phone's clock can differ, so the app keeps its own:
   110 s from when the confirmation arrived. After that, it looks again
   before merging. A late merge still fails safely with `403 bad_token`.
   *Ask:* `expires_in_ms` (or the Mac's `now_ms`) beside `expires_ms`.

3. **Notifications need the stream open.** "Waiting for you" is only heard
   over `GET /v1/events`. The stream is open while the app is on screen,
   for a while after sending, and always while the phone is the voice. A
   PR that starts waiting while the app is closed (and not the voice) is
   notified when the app next connects, if it's in the background then.
   Questions work the same way. There is no push channel.
