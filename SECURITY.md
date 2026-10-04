# Security Policy

## Trust model — read before granting

`adb-wifi-restore` asks for **`WRITE_SECURE_SETTINGS`**, a development
permission granted once via adb. With it, an app can change global system
settings (including — in this app's case, deliberately — the wireless
debugging switch). Treat that grant like handing over a system key:

- Only grant it to apps whose source you accept. This one is a few hundred
  lines of Java with **zero third-party dependencies**; building it yourself
  (`./gradlew assembleDebug` or `scripts/build-standalone.sh`) and comparing
  against the release APK is always an option.
- Revoke by uninstalling the app (`adb uninstall dev.adbwifi.restore`).

## Network exposure

The app runs an unauthenticated HTTP reporter on `0.0.0.0:18055` whose whole
purpose is to tell clients the current wireless-debugging port. Anyone who can
open a TCP connection to the phone on that port learns the adb port. Wireless
adbd itself still requires a paired computer (cryptographic pairing), so the
reporter is an *information* leak, not a shell — but keep the phone on a
trusted LAN or behind Tailscale, where reaching the network is the
authentication boundary.

## APK signing

Release APKs are built by GitHub Actions. If the repository's signing secrets
(`RELEASE_KEYSTORE_B64` …) are configured, releases carry a stable key;
otherwise they fall back to a debug-style signature. Download releases only
from the GitHub Releases page, verify the published `.sha256`, and prefer
in-place upgrades to keep signatures consistent.

## Supported versions

Only the latest release receives security fixes.

## Reporting a vulnerability

Please use GitHub's **private vulnerability reporting** (Security tab →
Report a vulnerability) rather than a public issue. Include the reporter JSON
and, if possible, a `logcat` snippet. You can expect a first response within a
week.
