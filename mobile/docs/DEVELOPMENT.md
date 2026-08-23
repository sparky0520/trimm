# Android development

## Scope

Work here for Android UI, conversation state, selective tool-artifact media
preparation, Media3 preview/export, Android persistence, device capability
reporting, and Google Play client flows.

Cloud agent/storage behavior belongs in `trimm-cloud`. Desktop and Remotion
behavior belongs in `trimm-desktop`.

## Start

1. Install JDK 17 and Android SDK 37.
2. Copy `.env.local.example` to `.env.local`.
3. Use the deployed cloud origin or run `trimm-cloud` separately.
4. Run `.\gradlew.bat testDebugUnitTest`.
5. Run `.\gradlew.bat assembleDebug`.

The debug APK is under `app/build/outputs/apk/debug`.

## Contracts

The accepted cloud versions are pinned in
`app/src/test/resources/contracts/cloud-contract.lock.json`. Do not add a
handwritten cloud field without a matching canonical cloud contract change.

The active cloud-agent path uses `/v1/conversations`: the full transcript and
the initial authorized video window go to Gemini, later media is prepared only
for an `inspect_video_range` tool call, and ordinary chat messages resume the
same conversation. Legacy Layer 1 data remains decodable for existing projects
but is not generated for this path.

## Device capability tiers

At import time, the app measures API level, 64-bit ABI support, total RAM,
free storage, H.264/HEVC decode and encode workloads, current thermal state,
and any recorded Layer 1 model latency. It reports one provisional tier:
minimum supported, standard editing, advanced local perception, or
high-resolution export. Each downgraded feature says whether it will use
Layer 2, reduce resolution, or remain unavailable. An NPU is not required.

The thresholds and explanations are unit tested in `DeviceCapabilityTest`.
They remain provisional until issue #7 records sustained physical-device
thermal and latency evidence; that evidence is required before publishing
final store requirements.

## Definition of done

- process recreation and offline/error states are considered;
- the UI shows safe operational activity, not hidden chain-of-thought;
- preview and export share canonical operation semantics;
- source orientation is preserved and output never exceeds source quality;
- unsupported device capabilities degrade honestly;
- unit tests and `assembleDebug` pass;
- physical-device evidence is attached when hardware behavior changes.
