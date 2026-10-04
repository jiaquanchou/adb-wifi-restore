# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-10-04

First public release. The self-heal mechanism itself has been running in
production on a headless Redmi K30 5G (Android 12 / MIUI 13) since day one.

### Added

- Core self-heal engine: restores `Settings.Global adb_wifi_enabled = 1`
  after boot (bounded retry loop, waits for Wi-Fi), using the
  `WRITE_SECURE_SETTINGS` development permission granted once via
  `adb shell pm grant`.
- Dynamic port capture: `NsdManager` discovery of the system
  `_adb-tls-connect._tcp` service resolves the random wireless-debugging
  port within milliseconds of it coming up.
- HTTP status reporter on `0.0.0.0:18055`: plain `GET /` returns the full
  status JSON (permission, setting, detected port, device IPv4 list,
  last error/trigger); `GET /?trigger=1` also runs one restore attempt.
- Boot entry points: `LOCKED_BOOT_COMPLETED` (direct-boot aware) +
  `BOOT_COMPLETED` + explicit `dev.adbwifi.restore.TRIGGER` broadcast for
  Termux:Boot relays.
- Privacy-friendly UI lifecycle: the status dashboard never opens by itself
  (the boot self-heal path launches no activity), auto-closes after 30 s of
  inactivity, and is excluded from the recents screen.
- Stable release signing: CI and the standalone build chain share one release
  keystore (via GitHub Secrets / `keys/release.keystore`), so release APKs
  upgrade in place.
- Status dashboard UI (zero-dependency, light/dark, English/Chinese):
  permission check with copyable `pm grant` / deviceidle-whitelist commands,
  wireless-debugging state, copyable `adb connect` command, device IPs,
  reporter state, raw status JSON.
- `scripts/build-standalone.sh`: zero-Gradle build chain
  (javac → d8 → aapt → zipalign → apksigner) producing the same
  installable APK.
- `scripts/adb_connect.sh`: query the reporter and `adb connect` in one
  command (jq optional).
- `scripts/adb_upgrade.sh`: one-command upgrade from a computer — fetch the
  latest release APK (or use a local one), `adb install -r`, re-arm the
  whitelist and trigger a restore.
- Unit tests pinning the HTTP reporter JSON contract (JVM-only; the runtime
  APK keeps its zero-dependency guarantee).
- GitHub Actions: CI (build + lint + unit tests, debug APK artifact) and
  tag-driven release workflow with optional secret-based signing.
- Bilingual documentation (README.md / README_zh.md) with architecture and
  reboot self-heal sequence diagrams.

[1.0.0]: https://github.com/jiaquanchou/adb-wifi-restore/releases/tag/v1.0.0
