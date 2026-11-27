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
