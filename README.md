# ctrlB Mobile

Android-first conversational video editor. Import clips, describe the edit in chat, review the evidence-backed proposal, and preview or export the result locally.

## Build

Requirements:

- JDK 17
- Android SDK 37

Open this repository in Android Studio and run the `app` configuration, or run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Runtime endpoints and public client configuration belong in user Gradle properties or CI secrets. Set `CTRLB_BACKEND_URL` to the deployed `ctrlb-cloud` HTTPS origin.

## Ownership

This repository owns Android UI, on-device evidence, Layer 1 execution, Media3 preview/export, project persistence, and Google Play client flows. Cloud planning, transcription, derivatives, accounts, and billing live in `ctrlb-cloud`.
