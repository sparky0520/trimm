# Android development

## Scope

Work here for Android UI, chat state, local Layer 1 indexing, selective media
preparation, Media3 preview/export, Android persistence, device capability
reporting, and Google Play client flows.

Cloud agent/storage behavior belongs in `ctrlb-cloud`. Desktop and Remotion
behavior belongs in `ctrlb-desktop`.

## Start

1. Install JDK 17 and Android SDK 37.
2. Copy `.env.local.example` to `.env.local`.
3. Use the deployed cloud origin or run `ctrlb-cloud` separately.
4. Run `.\gradlew.bat testDebugUnitTest`.
5. Run `.\gradlew.bat assembleDebug`.

The debug APK is under `app/build/outputs/apk/debug`.

## Contracts

The accepted cloud versions are pinned in
`app/src/test/resources/contracts/cloud-contract.lock.json`. Do not add a
handwritten cloud field without a matching canonical cloud contract change.

## Definition of done

- process recreation and offline/error states are considered;
- the UI shows safe operational activity, not hidden chain-of-thought;
- preview and export share canonical operation semantics;
- source orientation is preserved and output never exceeds source quality;
- unsupported device capabilities degrade honestly;
- unit tests and `assembleDebug` pass;
- physical-device evidence is attached when hardware behavior changes.
