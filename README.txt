Pocket Link v3.3 (project NvdaPocket) - Android controller for NVDA Remote.
The name shown on the phone is app_name in app/src/main/res/values/strings.xml. The package id stays com.example.nvdapocket so updates install over older builds.
Build: push to GitHub (Actions builds it) or run build.ps1 / build.sh.
The APK is signed with the key in app/nvdapocket.jks so later builds install over earlier ones.
Each GitHub build also creates a Release you can download on the phone.

New in 3.2:
- Gesture mode (Control tab): the whole screen is the gesture pad. Press Back twice to leave. Practice mode says what each gesture is.
- Connection: backoff reconnect, reconnect when the network returns, keep-alive, certificate remembered on first use,
  encrypted saved keys, connection history.
- Macro editor: edit steps, several phrases, wait steps, convert recordings, import and export.
- Recorded voice cues (app/src/main/res/raw) replace spoken status messages.
- Voice commands listen continuously, also in the background.
- Fixed: tabs can no longer scroll out of view; on-screen keys (F3 etc.) work with a tap or a screen reader double tap.

New in 3.3:
- Speech engine and voice can be chosen inside the app (Settings) and apply at once.
- Sound effects with the voice cues, with a cue style setting (sound and voice / voice only / sound only).
- Support tab: WhatsApp and email, with a ready-made message that can include app and phone details.
- New app name.

New in 3.4 (connection fixes):
- Fixed the repeated "Connection lost": the watchdog cut quiet but healthy connections after about 90 s of silence from the relay.
  It now only reconnects when the relay is known to answer pings (3 answered in a row) and the keep-alive setting is on.
- A network change no longer drops a working connection at once: the link is checked with a ping first.
- Failed sends are noticed straight away and trigger a reconnect.
- The TLS handshake has a 15 s timeout.
- Connection history now says how long the connection lasted before it was lost.

New in 3.5:
- All installed speech engines are now listed and the system default engine is used (the manifest was missing the TTS_SERVICE query,
  so Android 11+ hid every engine except Google's).
- Speech from the PC is spoken on its own thread, so a slow speech engine can no longer stall the connection. If the engine falls
  behind, old queued speech is dropped so you always hear the latest text.
- One bad message or an error while speaking can no longer drop the connection.
- The first reconnect after a drop happens after 1 second instead of 2.
