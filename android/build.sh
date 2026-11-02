#!/usr/bin/env bash
# Builds ZENKTX TV without Android Studio.
#
#   ./build.sh              debug APK -> app/build/outputs/apk/debug/
#   ./build.sh installDebug build + push to a TV reachable over adb
#   ./build.sh clean
set -euo pipefail
cd "$(dirname "$0")"

# --- 1. JDK 17 -----------------------------------------------------------------
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in "$HOME/.jdks"/jdk-17* "/c/Program Files/Eclipse Adoptium"/jdk-17*; do
    if [[ -x "$candidate/bin/java" || -x "$candidate/bin/java.exe" ]]; then
      # Gradle needs a Windows-style path on Windows; MSYS reports it with -W.
      JAVA_HOME="$(cd "$candidate" && { pwd -W 2>/dev/null || pwd; })"
      break
    fi
  done
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "error: JDK 17 not found. Install Temurin 17 or export JAVA_HOME." >&2
  exit 1
fi
export JAVA_HOME
echo "JAVA_HOME=$JAVA_HOME"

# --- 2. Android SDK ------------------------------------------------------------
SDK_DEFAULT="$HOME/AppData/Local/Android/Sdk"
if [[ ! -f local.properties ]]; then
  if [[ -d "$SDK_DEFAULT" ]]; then
    # AGP needs a native path (C:/...), not the MSYS /c/... form.
    SDK_PATH="$(cd "$SDK_DEFAULT" && { pwd -W 2>/dev/null || pwd; })"
    printf 'sdk.dir=%s\n' "$SDK_PATH" > local.properties
    echo "wrote local.properties -> $SDK_PATH"
  else
    echo "warning: no local.properties and no SDK at $SDK_DEFAULT" >&2
  fi
fi

# --- 3. Build ------------------------------------------------------------------
# Default to assembleDebug when no task is named (flags alone are not a task).
has_task=false
for arg in "$@"; do [[ "$arg" == -* ]] || has_task=true; done
if [[ "$has_task" == false ]]; then
  set -- assembleDebug "$@"
fi

exec ./gradlew "$@"
