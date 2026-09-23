# FS Calling Line Android 0.6.0-dev

This revision continues the Android feature-parity work while keeping FS Calling Line separate from D.W, security-alert, and AMN projects.

## Added in 0.6

- Recent-call filters: All, Incoming, Outgoing, Missed.
- Tap any recent call to open per-number analytics: total calls, incoming/outgoing, answered/missed/declined, total/average/longest talk time, and last activity.
- Statistics period selectors: Today, 7 days, 30 days, All.
- Stronger Devices screen showing saved transport/device details.
- Separate reconnect, disconnect, scan-new-PC, and forget-saved-PC actions.
- Dedicated Diagnostics page for control link, services, default Phone role, audio route, permissions, active calls, and live transcription.
- Recovery tools for reconnecting the saved PC, syncing live transcription, opening Bluetooth settings, and opening Android app settings.
- About/version updated to 0.6.0-dev.

## Preserved from 0.5

- Home / Calls / Live / Devices / More navigation.
- Android Telecom call controls.
- Dial pad and in-call DTMF.
- Contacts.
- Local call history and statistics.
- PC-powered live transcription synchronized to Android.
- English + Dari/Farsi and English + Pashto pair control.
- Transcript search, copy, TXT/JSON export, clear and sync.
- Wi-Fi/network control with Bluetooth fallback and Bluetooth/HFP call-audio behavior.

## Explicitly not included

- AMN sessions.
- D.W Main Terminal.
- Security-alert project features.
- Desktop-only layout or billing UI.

## Validation note

Source structure and Java syntax were checked locally. A full Android Gradle build still requires an environment with the Android SDK and Gradle dependencies available.
