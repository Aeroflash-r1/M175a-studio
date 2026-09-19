#!/usr/bin/env bash
# M175a Studio — Codespace bootstrap.
# 1. Writes local.properties so Gradle/AGP finds the SDK
# 2. Accepts all Android SDK licenses
# 3. Pre-downloads Gradle + dependencies so builds are instant later
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"

# Recovery shells may start without the Java feature applied. Restore the
# documented JDK before invoking sdkmanager or Gradle.
if ! command -v java >/dev/null 2>&1; then
  if command -v apk >/dev/null 2>&1; then
    sudo apk add --no-cache gcompat openjdk17-jdk
  elif command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y openjdk-17-jdk
  else
    echo "[setup] ERROR: Java 17 is required but no supported package manager was found" >&2
    exit 1
  fi
fi

if command -v apk >/dev/null 2>&1 && [ ! -e /lib64/ld-linux-x86-64.so.2 ]; then
  sudo apk add --no-cache gcompat
fi

JAVA_BIN="$(readlink -f "$(command -v java)")"
export JAVA_HOME="${JAVA_HOME:-${JAVA_BIN%/bin/java}}"
echo "[setup] using Java: $JAVA_HOME"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  SDK="$HOME/.android-sdk"
  TOOLS_VERSION="13114758"
  TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${TOOLS_VERSION}_latest.zip"
  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT
  mkdir -p "$SDK/cmdline-tools"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$TOOLS_URL" -o "$TMP_DIR/tools.zip"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$TOOLS_URL" -O "$TMP_DIR/tools.zip"
  else
    echo "[setup] ERROR: curl or wget is required to install Android command-line tools" >&2
    exit 1
  fi
  unzip -q "$TMP_DIR/tools.zip" -d "$TMP_DIR"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$TMP_DIR/cmdline-tools" "$SDK/cmdline-tools/latest"
  echo "[setup] installed Android command-line tools -> $SDK"
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

# local.properties with the container's SDK path
if [ ! -f local.properties ] || ! grep -qx "sdk.dir=$SDK" local.properties; then
  echo "sdk.dir=$SDK" > local.properties
  echo "[setup] wrote local.properties -> $SDK"
fi

# Android SDK: platform 34 + build-tools 34 (AGP 8.5.2 requirements)
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" "build-tools;34.0.0" \
  "platform-tools" > /dev/null
echo "[setup] SDK platform 34 + build-tools 34.0.0 ready"

# Warm the Gradle cache (first build is slow otherwise)
./gradlew --version > /dev/null
echo "[setup] gradle wrapper ready"
./gradlew assembleDebug testDebugUnitTest --no-daemon -q \
  && echo "[setup] BUILD OK — codespace is ready" \
  || echo "[setup] WARN: first build had issues — run ./gradlew assembleDebug to see errors"
