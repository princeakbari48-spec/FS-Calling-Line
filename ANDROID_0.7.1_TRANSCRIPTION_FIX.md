# FS Calling Line Android 0.7.1-dev — Transcription Start Fix

This update fixes Android Live Transcription startup and diagnostics.

- Android now shows the real PC transcription status instead of only READY TO TRANSCRIBE.
- A Start request is queued while Phone Link/Bluetooth HFP call audio is still being transferred to the PC.
- Once PC call audio becomes active, Windows starts transcription automatically.
- Starting from Android explicitly enables transcription approval for the PC session.
- Android detects an older/unpatched Windows FS Calling Line build that does not answer the transcription protocol and shows a clear update/restart message.
- The patch includes BOTH Android and Windows bridge files. The Windows application must be rebuilt/updated; updating only the APK is not enough because speech recognition runs on the PC.

Required Windows transcription setup remains unchanged: a configured provider (or local model), saved caller playback endpoint, and active Phone Link/Bluetooth HFP call audio.
