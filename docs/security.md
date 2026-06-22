# Security Notes

DeadDrop is built to reduce network exposure and avoid storing audio, but it is still an unaudited prototype.

## Android permissions

The Android app does not request `android.permission.INTERNET`.

Requested permissions:

- `RECORD_AUDIO` for live receive/listen, including Android's user-approved playback-capture path.
- `FOREGROUND_SERVICE` for user-started background listening.
- `FOREGROUND_SERVICE_MICROPHONE` for Android foreground microphone service support.
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` for Android foreground device-playback capture support on Android versions that require a typed foreground service.

Not requested:

- Internet
- contacts
- SMS
- phone state
- location
- Bluetooth
- advertising ID

## Local storage

Android:

- group keys and message logs are stored through Android Keystore-backed encrypted preferences;
- the signing identity is a non-exportable Android Keystore ECDSA P-256 key;
- panic wipe removes local groups, logs, replay state, and Keystore entries;
- replay cache is separate from the visible message log;
- audio samples are held only in short in-memory buffers;
- device-playback capture uses Android's `MediaProjection` consent prompt and only receives playback that Android/source apps allow.

Desktop companion:

- local state is stored in a passphrase-protected vault;
- a blank passphrase starts a temporary session that is not saved;
- the desktop vault stores groups, signing identity, known-sender fingerprints, and replay-cache state;
- Windows uninstall removes app files/shortcuts only and leaves the vault unless the user deletes it manually.

## Cryptography

Current prototype primitives:

- AES-GCM for encrypted group messages;
- PBKDF2-HMAC-SHA256 plus AES-GCM for invite wrapping and the desktop vault;
- ECDSA P-256 with SHA-256 for signed modes;
- truncated SHA-256 for group IDs and fingerprints;
- CRC32 for audio frame error detection before packet handling.

## Known gaps

- No external security review yet.
- Robust and Ultra/Noisy modes use simple repetition FEC, not stronger interleaving/error correction.
- Safety fingerprints and key-change warnings help users notice identity changes, but they are not a full PKI or trust-on-first-use security audit.
- A compromised or unlocked device is out of scope.
- A group member can copy messages, invites, or second factors outside the app.
