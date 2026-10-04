#!/usr/bin/env bash
#
# adb_upgrade.sh — one-command upgrade of adb-wifi-restore on a headless
# device, from your computer.
#
# Why computer-side? An Android app cannot silently reinstall itself
# (PackageInstaller needs on-screen confirmation; Termux `pm install` is
# blocked without root — field-tested), so the "automation" lives here,
# where adb already has uid 2000.
#
# Usage:
#   ./adb_upgrade.sh <device-host>                 # fetch the latest GitHub
#                                                  # release APK and install it
#   ./adb_upgrade.sh <device-host> ./local.apk     # install a local APK
#
# The device must already be reachable over adb — run ./adb_connect.sh
# <device-host> first if it is not.
#
set -euo pipefail

REPO="jiaquanchou/adb-wifi-restore"
PACKAGE="dev.adbwifi.restore"

HOST="${1:-}"
LOCAL_APK="${2:-}"

if [ -z "$HOST" ]; then
  echo "usage: $0 <device-host> [local-apk]"
  echo "  <device-host>  phone IP / Tailscale name used for adb"
  exit 1
fi

if ! adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  echo "no adb device connected - run: ./adb_connect.sh $HOST"
  exit 1
fi

if [ -z "$LOCAL_APK" ]; then
  echo "==> looking up the latest GitHub release"
  APK_URL="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
    | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | cut -d'"' -f4)"
  [ -n "$APK_URL" ] || { echo "ERROR: no .apk asset found in the latest release"; exit 1; }
  echo "==> downloading $APK_URL"
  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT
  curl -fsSL -o "$TMP_DIR/adb-wifi-restore.apk" "$APK_URL"
  LOCAL_APK="$TMP_DIR/adb-wifi-restore.apk"
fi

echo "==> adb install -r $(basename "$LOCAL_APK")"
adb install -r "$LOCAL_APK"

# Upgrades keep existing grants, but re-arm the whitelist entry and run one
# restore attempt so the reporter is back up immediately. A fresh install or
# update leaves the app in "stopped" state, where manifest receivers ignore
# implicit broadcasts — hence the explicit component + FLAG_INCLUDE_STOPPED_
# PACKAGES (0x20).
echo "==> re-arming deviceidle whitelist + trigger"
adb shell dumpsys deviceidle whitelist +"$PACKAGE" >/dev/null
adb shell am broadcast -a "$PACKAGE.TRIGGER" -f 0x20 -n "$PACKAGE/.BootReceiver" >/dev/null

echo "==> done. verify: curl http://$HOST:18055/"
