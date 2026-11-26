NVDA Pocket v3.2 - Android controller for NVDA Remote.
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
