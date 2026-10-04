#!/usr/bin/env bash
#
# build-standalone.sh — zero-Gradle build chain for adb-wifi-restore.
#
# Produces the same installable APK as the Gradle build using nothing but:
#   * a JDK (11+; javac --release 8)
#   * an Android SDK checkout containing at least one platform (android.jar)
#     and build-tools (aapt / d8 / zipalign / apksigner)
#
# Works on macOS and Linux. The SDK is located in this order:
#   1. $ANDROID_SDK (or $ANDROID_SDK_ROOT / $ANDROID_HOME)
#   2. ~/Library/Android/sdk   (macOS default)
#   3. ~/Android/Sdk           (Linux default)
#
# Usage:
#   ./scripts/build-standalone.sh
#   ANDROID_SDK=/path/to/sdk ./scripts/build-standalone.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG_ID="dev.adbwifi.restore"
VERSION_CODE="1"
VERSION_NAME="1.0.0"
MIN_SDK="30"
TARGET_SDK="31"
OUT="$ROOT/build"
APK_NAME="adb-wifi-restore"

# ------------------------------------------------------------------- JDK
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JBIN="$JAVA_HOME/bin"
else
  JBIN="$(dirname "$(command -v javac)")"
fi
JAVAC="$JBIN/javac"
KEYTOOL="$JBIN/keytool"
command -v "$JAVAC" >/dev/null || { echo "ERROR: javac not found (set JAVA_HOME)"; exit 1; }

# ------------------------------------------------------------------- SDK
SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}}"
if [ -z "$SDK" ]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$ROOT/sdk"; do
    if [ -d "$candidate" ]; then SDK="$candidate"; break; fi
  done
fi
[ -n "$SDK" ] || { echo "ERROR: Android SDK not found; set ANDROID_SDK=/path/to/sdk"; exit 1; }

PLATFORM_JAR="$(find "$SDK/platforms" -name android.jar 2>/dev/null | sort -V | tail -1)"
AAPT="$(find "$SDK/build-tools" -type f -name aapt 2>/dev/null | sort -V | tail -1)"
D8="$(find "$SDK/build-tools" -type f -name d8 2>/dev/null | sort -V | tail -1)"
ZIPALIGN="$(find "$SDK/build-tools" -type f -name zipalign 2>/dev/null | sort -V | tail -1)"
APKSIGNER="$(find "$SDK/build-tools" -type f -name apksigner 2>/dev/null | sort -V | tail -1)"

for tool in "$PLATFORM_JAR" "$AAPT" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
  [ -n "$tool" ] && [ -e "$tool" ] || {
    echo "ERROR: incomplete SDK at $SDK"
    echo "  need: platforms/android-*/android.jar and build-tools/*/{aapt,d8,zipalign,apksigner}"
    exit 1
  }
done

echo "=== adb-wifi-restore standalone build ==="
echo "JAVA   : $JAVAC"
echo "SDK    : $SDK"
echo "platform: $PLATFORM_JAR"

# ------------------------------------------------------------------- build
echo "=== 1. clean build dir ==="
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

# The repo manifest carries no package attribute (AGP 8 takes it from the
# namespace in build.gradle); inject it here for the aapt-only chain.
TMP_MANIFEST="$OUT/AndroidManifest.xml"
sed "s|<manifest |<manifest package=\"$PKG_ID\" |" "$ROOT/app/src/main/AndroidManifest.xml" \
  > "$TMP_MANIFEST"

echo "=== 2. generate R.java + package resources ==="
mkdir -p "$OUT/gen"
"$AAPT" package -f -m \
  -J "$OUT/gen" \
  -M "$TMP_MANIFEST" \
  -S "$ROOT/app/src/main/res" \
  -I "$PLATFORM_JAR" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -F "$OUT/app-unaligned.apk"

echo "=== 3. compile java (sources + R.java) ==="
SOURCES="$(find "$ROOT/app/src/main/java" "$OUT/gen" -name '*.java')"
# shellcheck disable=SC2086
"$JAVAC" -encoding UTF-8 --release 8 -cp "$PLATFORM_JAR" -d "$OUT/classes" $SOURCES

echo "=== 4. dex ==="
# shellcheck disable=SC2086
"$D8" --min-api "$MIN_SDK" --lib "$PLATFORM_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "=== 5. inject classes.dex ==="
(cd "$OUT/dex" && "$AAPT" add "$OUT/app-unaligned.apk" classes.dex)

echo "=== 6. zipalign ==="
"$ZIPALIGN" -p -f 4 "$OUT/app-unaligned.apk" "$OUT/app-aligned.apk"

echo "=== 7. signing key ==="
KEYS_DIR="$ROOT/keys"
mkdir -p "$KEYS_DIR"
# Prefer a stable release key so standalone-built APKs can upgrade in place
# over CI-signed GitHub Release APKs (same signature). Set
# RELEASE_STORE_PASSWORD (+ optional RELEASE_STORE_FILE / RELEASE_KEY_ALIAS),
# or keep keys/release.keystore next to the script. Falls back to a local
# debug key otherwise.
if [ -n "${RELEASE_STORE_FILE:-}" ] && [ -f "$RELEASE_STORE_FILE" ] && [ -n "${RELEASE_STORE_PASSWORD:-}" ]; then
  KEYSTORE="$RELEASE_STORE_FILE"
  KS_PASS="$RELEASE_STORE_PASSWORD"
  KEY_PASS="${RELEASE_KEY_PASSWORD:-$RELEASE_STORE_PASSWORD}"
  ALIAS="${RELEASE_KEY_ALIAS:-adb-wifi-restore}"
  echo "    signing with release key: $KEYSTORE (alias $ALIAS)"
elif [ -f "$KEYS_DIR/release.keystore" ] && [ -n "${RELEASE_STORE_PASSWORD:-}" ]; then
  KEYSTORE="$KEYS_DIR/release.keystore"
  KS_PASS="$RELEASE_STORE_PASSWORD"
  KEY_PASS="${RELEASE_KEY_PASSWORD:-$RELEASE_STORE_PASSWORD}"
  ALIAS="${RELEASE_KEY_ALIAS:-adb-wifi-restore}"
  echo "    signing with release key: $KEYSTORE (alias $ALIAS)"
else
  KEYSTORE="$KEYS_DIR/debug.keystore"
  KS_PASS="android"
  KEY_PASS="android"
  ALIAS="androiddebugkey"
  if [ ! -f "$KEYSTORE" ]; then
    echo "    no release key configured - generating local debug keystore (gitignored)..."
    "$KEYTOOL" -genkeypair -validity 10000 \
      -dname "CN=adb-wifi-restore,O=adb-wifi-restore,C=US" \
      -keystore "$KEYSTORE" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048
  fi
fi

echo "=== 8. sign (v1+v2+v3) ==="
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$ALIAS" \
  --out "$OUT/$APK_NAME.apk" "$OUT/app-aligned.apk"

echo "=== 9. verify ==="
"$APKSIGNER" verify "$OUT/$APK_NAME.apk"
"$AAPT" dump badging "$OUT/$APK_NAME.apk" | grep -E '^package|uses-permission:|application-label:|launchable-activity' || true

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$OUT/$APK_NAME.apk" | tee "$OUT/$APK_NAME.apk.sha256"
else
  shasum -a 256 "$OUT/$APK_NAME.apk" | tee "$OUT/$APK_NAME.apk.sha256"
fi

rm -f "$OUT/app-unaligned.apk" "$OUT/app-aligned.apk" "$OUT/AndroidManifest.xml"
rm -rf "$OUT/classes"
ls -lh "$OUT/$APK_NAME.apk"
echo "=== done: $OUT/$APK_NAME.apk ==="
