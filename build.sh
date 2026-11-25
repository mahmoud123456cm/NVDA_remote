#!/usr/bin/env bash
# Linux / macOS / WSL. Requires JDK 17 and internet.
set -euo pipefail
cd "$(dirname "$0")"
command -v java >/dev/null || { echo "Install JDK 17 first (e.g. sudo apt install openjdk-17-jdk)"; exit 1; }
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
case "$(uname)" in Darwin) OS=mac;; *) OS=linux;; esac
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  curl -L -o /tmp/clt.zip "https://dl.google.com/android/repository/commandlinetools-$OS-11076708_latest.zip"
  unzip -q -o /tmp/clt.zip -d "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"; mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
export ANDROID_HOME="$SDK"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" "build-tools;34.0.0" "platform-tools"
echo "sdk.dir=$SDK" > local.properties
if ! command -v gradle >/dev/null; then
  [ -d "$HOME/gradle-8.7" ] || { curl -L -o /tmp/g.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip; unzip -q /tmp/g.zip -d "$HOME"; }
  export PATH="$HOME/gradle-8.7/bin:$PATH"
fi
gradle --no-daemon assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk NvdaPocket.apk
echo "Done: $(pwd)/NvdaPocket.apk"
