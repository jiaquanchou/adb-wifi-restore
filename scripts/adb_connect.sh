#!/usr/bin/env bash
#
# adb_connect.sh — query the on-device status reporter of adb-wifi-restore and
# run `adb connect` against the freshly detected dynamic port.
#
# Works from any Linux/macOS shell, including Termux on another Android device
# (Termux: `pkg install android-tools` provides adb).
#
# Usage:
#   ./adb_connect.sh <device-host> [http-port]
#     <device-host>  phone IP / hostname / Tailscale name, e.g. 192.168.2.5
#     [http-port]    status reporter port, default 18055
#
# The reporter is the plain HTTP endpoint built into the app:
#   GET http://<device>:18055/           -> status JSON
#   GET http://<device>:18055/?trigger=1 -> also run one restore attempt
#
set -euo pipefail

HOST="${1:-${ADB_WIFI_RESTORE_HOST:-}}"
HTTP_PORT="${2:-18055}"

if [ -z "$HOST" ]; then
  echo "usage: $0 <device-host> [http-port=18055]"
  echo "   or: export ADB_WIFI_RESTORE_HOST=<device-host>"
  exit 1
fi

fetch() {
  curl -fsS --max-time 5 "http://$HOST:$HTTP_PORT/$1"
}

port_ok() {
  case "${1:-}" in
    '' | *[!0-9]*) return 1 ;;
  esac
  [ "$1" -gt 0 ]
}

parse_port() {
  local json
  json="$(fetch "" )" || return 1
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$json" | jq -r '.detectedPort // -1'
  else
    printf '%s\n' "$json" | sed -n 's/.*"detectedPort": *\([0-9][0-9]*\).*/\1/p'
  fi
}

PORT="$(parse_port || true)"

if ! port_ok "$PORT"; then
  echo "no port reported yet — asking the app to restore (?trigger=1)..."
  fetch "?trigger=1" >/dev/null || true
  sleep 4
  PORT="$(parse_port || true)"
fi

if ! port_ok "$PORT"; then
  echo "still no port. check on the device:"
  echo "  * WRITE_SECURE_SETTINGS granted?      (permission card in the app)"
  echo "  * Wi-Fi connected?"
  echo "  * unlocked once after reboot?         (Direct Boot: adbd only binds a port after first unlock)"
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb detected port $PORT but 'adb' is not installed here."
  echo "  Termux: pkg install android-tools"
  echo "  then:   adb connect $HOST:$PORT"
  exit 1
fi

echo "adb connect $HOST:$PORT"
exec adb connect "$HOST:$PORT"
