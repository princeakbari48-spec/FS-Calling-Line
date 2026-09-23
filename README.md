# FS Calling Line

### Android calling companion with PC-powered live transcription

FS Calling Line connects Android call controls with a companion Windows application over Wi-Fi/network or Bluetooth. Manage calls, review call activity, and follow live transcripts from the Android app.

**Development preview · Android 10+ · Version 0.7.1-dev**

## Features

- **Call controls:** Android Telecom integration, dial pad, and in-call DTMF.
- **Call activity:** contacts, recent-call filters, per-number analytics, and date-range statistics.
- **Live transcription:** view captions from the Windows companion, choose English + Dari/Farsi or English + Pashto, and use start/stop and pause/resume controls.
- **Transcript tools:** search, copy, and export transcripts as TXT or JSON.
- **Device pairing:** QR-based pairing, saved-PC reconnect, disconnect, and forget-device controls.
- **Diagnostics:** inspect connection, permissions, call audio, and transcription status.

## How it works

The Android app handles phone integration and displays transcript updates. The paired Windows application runs speech recognition and sends transcription state and captions to Android.

**This repository contains the Android application only.** Live transcription requires the matching updated Windows application, a configured transcription provider or local model, a saved caller playback endpoint, and active Phone Link/Bluetooth HFP call audio on the PC. Updating only the Android APK is not sufficient.

## Build and run

### Requirements

| Component | Project setting |
| --- | --- |
| Java | 17 |
| Android SDK | 35 |
| Minimum device version | Android 10 / API 29 |
| Gradle wrapper | 8.9 |
| Android Gradle plugin | 8.7.3 |

1. Open the project folder containing `settings.gradle.kts` in Android Studio.
2. Install Android SDK 35 and configure a Java 17 Gradle JDK.
3. Let Gradle sync download the required dependencies.
4. Connect a compatible Android phone and run the `app` configuration.
5. Grant the permissions and default Phone role needed by the features you use.
6. Pair the phone with the compatible Windows companion to use PC-connected features.

To build a debug APK from the project root:

```powershell
# Windows PowerShell
.\gradlew.bat assembleDebug
```

```sh
# macOS / Linux
sh ./gradlew assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Project layout

```text
app/
  src/main/
    java/com/fscallingline/  App, call, connection, and transcript logic
    res/                    Icons, branding, and Android resources
    AndroidManifest.xml     Components and permissions
  build.gradle.kts          Android app build configuration
gradle/wrapper/              Reproducible Gradle launcher configuration
build.gradle.kts             Shared plugin configuration
settings.gradle.kts          Project and repository configuration
```

## Configuration and privacy

Pairing credentials are supplied at runtime and stored on the device; they are not intended to be committed to Git. The root `.gitignore` excludes local SDK paths, IDE state, generated build files, environment files, and signing material.

Call history, contacts, and transcripts can contain personal information. Remove personal details and credentials before sharing logs or screenshots in an issue.

## Development status

The included Android build declares version **0.7.1-dev** (version code **9**). It is a development preview; this repository preparation did not include an Android build or device test.

The 0.7.1 update improves transcription startup and diagnostics, including handling the handoff of call audio to the PC and detecting an older Windows transcription bridge.

See the project notes for the changes across versions:

- [0.7.1 transcription startup fix](ANDROID_0.7.1_TRANSCRIPTION_FIX.md)
- [0.7 interface and transcription update](ANDROID_0.7_UPGRADE_NOTES.md)
- [0.6 calling and diagnostics update](ANDROID_0.6_UPGRADE_NOTES.md)

## Reporting an issue

Include the Android/device version, app version, connection method, matching Windows companion version, steps to reproduce, and expected versus actual behavior. Share only sanitized diagnostics.