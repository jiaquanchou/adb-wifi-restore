# Contributing to adb-wifi-restore

*(中文速览：提交 PR 前请跑通两条构建链并尽量在真机验证；本项目铁律是零运行时依赖与 APK 极小化，请勿引入 androidx / material / 第三方 HTTP 库。)*

Thanks for helping make headless Android boxes more reliable!

## Project principles

These two rules are the reason the project exists as it is — please respect
them in every PR:

1. **Zero runtime dependencies.** No androidx, no material, no http client
   libraries. This is what allows `scripts/build-standalone.sh` to build the
   exact same app with nothing but a JDK and the Android build-tools, and what
   keeps the security surface auditable in one sitting.
2. **Keep the APK tiny.** If your change pushes the APK towards megabytes,
   it probably belongs in a separate tool.

Also: the self-heal core (setting write → NSD port discovery → HTTP report)
is field-proven; refactor freely, but keep the runtime behaviour stable unless
an issue documents otherwise.

## Development setup

Any of:

- **Android Studio**: open the repo root, press Run (JDK 17+ required).
- **Gradle CLI**: `./gradlew assembleDebug lintDebug`
- **No Gradle / no Studio**: `ANDROID_SDK=/path/to/sdk ./scripts/build-standalone.sh`

The standalone script must always stay green — it is the project's second
official build chain.

## Testing on a device

Real-device verification is what this project lives on. A useful loop:

```bash
adb install -r build/adb-wifi-restore.apk
adb shell pm grant dev.adbwifi.restore android.permission.WRITE_SECURE_SETTINGS
# reboot, then from another network:
curl http://<phone>:18055/
adb logcat -s AdbRestoreEngine AdbRestoreHttp AdbWifiRestore AdbRestoreService
```

If you cannot test on a device, say so in the PR — honest "untested" beats
silent hope.

## Submitting

1. Fork / branch, keep commits single-purpose.
2. CI must pass (`assembleDebug lintDebug`).
3. Fill the PR checklist (build chains + device matrix).
4. For behaviour changes, describe the ROM(s) you verified against.

## Reporting bugs

Use the issue template and always include the reporter JSON
(`curl http://<phone>:18055/` — masking the port is fine). The JSON carries
almost everything needed for diagnosis.
