NVDA Pocket - Android controller for NVDA Remote, no hardware keyboard needed.

BUILD (pick one)
 1. Windows:  install JDK 17 (winget install EclipseAdoptium.Temurin.17.JDK), then in PowerShell:
              powershell -ExecutionPolicy Bypass -File build.ps1
 2. Linux/macOS/WSL:  ./build.sh
 3. No local setup: upload this folder to a GitHub repo; Actions > Build APK > download artifact.
Result: NvdaPocket.apk (debug-signed). Copy to the phone, allow "install unknown apps", install.

USE
 Enter server host, port (6837 default), and your key, then Connect.
 Modifier toggles stay held until toggled off. "Release all" clears them.
 Typing uses a US keyboard layout mapping.
 Keep the app in the foreground; the connection is not kept alive in the background.
