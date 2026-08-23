# trimm Mobile

Android-first conversational video editor. Import clips, describe the edit in chat, review the evidence-backed proposal, and preview or export the result locally.

See [Android rendering](docs/RENDERING.md), [Perception](docs/PERCEPTION.md), and the [Mobile roadmap](docs/ROADMAP.md).

Start with the [Android development guide](docs/DEVELOPMENT.md) before taking a
ticket.

## Build

Requirements:

- JDK 17
- Android SDK 37

Open this repository in Android Studio and run the `app` configuration, or run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Copy `.env.local.example` to the ignored `.env.local` for endpoint,
authentication, and optional signing configuration.

Runtime endpoints and public client configuration belong in user Gradle properties or CI secrets. Set `TRIMM_BACKEND_URL` to the deployed `trimm-cloud` HTTPS origin.

### Production Android builds

Set `JAVA_HOME` to JDK 17 and fill in every production and release-signing
setting in `.env.local`. In particular, `TRIMM_ANDROID_UPLOAD_STORE_FILE` must
be a path to an existing upload keystore, relative to this repository.

Build a signed production APK for direct distribution:

```powershell
.\scripts\build-production-apk.ps1
```

The APK is written to
`app\build\outputs\apk\release\app-release.apk`.

Build the signed Android App Bundle used for Google Play:

```powershell
.\scripts\build-production-bundle.ps1
```

The bundle is written to
`app\build\outputs\bundle\release\app-release.aab`.

Both scripts verify JDK 17, the required production configuration, and the
upload keystore before invoking Gradle. They explicitly put the JDK from
`JAVA_HOME` first on `PATH`.

#### Create the upload keystore

Create the upload keystore once:

```powershell
.\scripts\create-upload-keystore.ps1
```

The script invokes JDK 17's `keytool` interactively so the password is not
placed in shell history. Choose a strong password, store it in a password
manager, and press Enter at the key-password prompt to reuse the keystore
password. Then set:

```dotenv
TRIMM_ANDROID_UPLOAD_STORE_FILE=keystore/trimm-upload.jks
TRIMM_ANDROID_UPLOAD_STORE_PASSWORD=<the password you entered>
TRIMM_ANDROID_UPLOAD_KEY_ALIAS=trimm-upload
TRIMM_ANDROID_UPLOAD_KEY_PASSWORD=<the same password>
```

The keystore is ignored by Git. Back up both the keystore and its credentials
securely: future updates to the app must be signed consistently. Do not create
a replacement merely because the local copy is lost.

### Removing the obsolete Java 8 installation on Windows

If `java -version` still reports Java 8, uninstall **Java 8 Update 491
(64-bit)** from Windows Settings under **Apps > Installed apps**. Alternatively,
open PowerShell as Administrator and run:

```powershell
Start-Process msiexec.exe -Verb RunAs -Wait -ArgumentList `
  '/x {71024AE4-039E-4CA4-87B4-2F64180491F0}'
```

Open a new terminal afterward and verify:

```powershell
java -version
```

It should report version 17.

## Ownership

This repository owns Android UI, on-device evidence, Layer 1 execution, Media3 preview/export, project persistence, and Google Play client flows. Cloud planning, transcription, derivatives, accounts, and billing live in `trimm-cloud`.

Supported cloud wire versions are pinned in the test resources under `contracts/cloud-contract.lock.json` and checked by the Android test suite.
