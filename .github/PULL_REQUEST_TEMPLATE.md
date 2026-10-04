# Pull Request

## What does this PR change?

<!-- one or two sentences -->

## Why?

<!-- link the issue, or describe the pain point -->

## How was it tested?

- [ ] `./gradlew assembleDebug lintDebug` passes
- [ ] `scripts/build-standalone.sh` still produces an installable APK
- [ ] Tested on a real device (model / Android version: ...)

## Zero-dependency check

The project deliberately avoids runtime dependencies (no androidx / material /
http libs) so that the standalone build chain stays dependency-free.

- [ ] This PR adds no new runtime dependencies
