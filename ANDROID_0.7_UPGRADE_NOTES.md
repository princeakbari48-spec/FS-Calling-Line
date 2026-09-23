# FS Calling Line Android 0.7.0-dev

## Home redesign
- Removed the large FS Calling Line hero/branding card from Home.
- Kept a compact Home heading and PC connection status.
- Today's call history is now calculated strictly from calls recorded today.
- Added Today metrics for calls, answered, missed, incoming, outgoing, and talk time.
- Added Home date-range analytics with From and To date pickers.
- Date-range analytics show calls, incoming, outgoing, answered, missed, and answered talk time.
- Added a shortcut from Home date analytics to the full Statistics page.
- Active calls on Home now expose both Call controls and Transcribe actions.

## Call-connected transcription prompt
- When an Android cellular call reaches CONNECTED and the FS PC control link is available, Android prompts once for that call to start live transcription.
- The prompt offers:
  - English + Dari / Farsi · دری / فارسی
  - English + Pashto · پښتو
- Tapping Start transcription applies the selected interpretation pair, starts the existing PC transcription engine, syncs state, and opens the Android Live screen.
- Choosing Not now suppresses repeat prompts for the same call.
- Manual Start Live Transcription remains available from the active-call screen and Home.
- While transcription is already live, Languages opens the same selector in change-language mode without issuing a redundant Start command.

## Clearer Live Transcription UX
- Redesigned Live to prioritize readable captions over settings.
- Added compact live state, language pair, detected language, and audio status.
- Main actions are Start/Stop, Pause/Resume, and Languages.
- Conversation is displayed as clean full-width transcript rows similar to the PC presentation, rather than chat bubbles.
- Caption text is larger and supports RTL for Dari/Farsi/Pashto entries.
- Search/copy/export/clear are moved into a separate Transcript tools card below the conversation.
- Live transcript updates are refreshed in place instead of rebuilding the complete Activity on every new caption.

## Version
- Android versionCode: 8
- Android versionName: 0.7.0-dev

## Validation performed here
- AndroidManifest.xml parsed successfully.
- Java delimiter/string/comment structural validation passed.
- javac parsing did not report Java syntax errors before expected Android SDK / org.json missing-class errors.
- A full Gradle Android compile could not run in this environment because Gradle 8.9 is not cached and external downloads are unavailable.
