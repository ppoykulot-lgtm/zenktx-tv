#!/usr/bin/env bash
# Boots an Android TV emulator, installs the debug APK and opens the app.
#
#   ./run-tv-emulator.sh          boot windowed, install, launch
#   ./run-tv-emulator.sh --headless
#
# The AVD (ZenktxTV, 1080p, API 36, android-tv image) is created on first run.
set -euo pipefail
cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$(cd "$HOME/AppData/Local/Android/Sdk" 2>/dev/null && pwd -W || echo "")}"
[[ -n "$ANDROID_HOME" ]] || { echo "error: Android SDK not found" >&2; exit 1; }
export ANDROID_SDK_ROOT="$ANDROID_HOME"

SDK_WIN="$ANDROID_HOME"
ADB="$SDK_WIN/platform-tools/adb.exe"
EMU="$SDK_WIN/emulator/emulator.exe"
AVDMANAGER="$SDK_WIN/cmdline-tools/latest/bin/avdmanager.bat"
AVD=ZenktxTV
IMAGE="system-images;android-36;android-tv;x86"

if [[ ! -x "$EMU" ]]; then echo "error: emulator missing at $EMU" >&2; exit 1; fi

# --- AVD -----------------------------------------------------------------------
if ! "$EMU" -list-avds | grep -qx "$AVD"; then
  [[ -f "$AVDMANAGER" ]] || { echo "error: cmdline-tools/avdmanager missing" >&2; exit 1; }
  echo "creating AVD $AVD ..."
  echo "no" | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" -d tv_1080p --force
fi

# --- boot ----------------------------------------------------------------------
if ! "$ADB" devices | grep -q "emulator-.*device$"; then
  echo "booting $AVD ..."
  if [[ "${1:-}" == "--headless" ]]; then
    "$EMU" -avd "$AVD" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect -no-snapshot >/tmp/zenktx-emulator.log 2>&1 &
  else
    "$EMU" -avd "$AVD" -no-audio -no-boot-anim -no-snapshot >/tmp/zenktx-emulator.log 2>&1 &
  fi
  "$ADB" wait-for-device
  printf "waiting for boot"
  for _ in $(seq 1 60); do
    if [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      echo " ok"; break
    fi
    printf "."; sleep 5
  done
fi

# --- install + launch ----------------------------------------------------------
./build.sh assembleDebug >/dev/null
APK="app/build/outputs/apk/debug/app-debug.apk"
"$ADB" install -r "$APK"
"$ADB" shell am start -n ph.zenktx.tv.debug/ph.zenktx.tv.MainActivity
echo "launched on $AVD"
