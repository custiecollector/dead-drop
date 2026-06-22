# Changelog

## 0.1.12

- Added a listening-source selector for microphone/acoustic vs local device playback audio.
- Android can use Android 10+ AudioPlaybackCapture/MediaProjection for app/browser playback that the source app allows, including foreground-service playback capture for background listening.
- Windows desktop packages include a WASAPI loopback helper for capturing the default render endpoint (what the machine is playing through speakers/headphones) without requiring Stereo Mix or virtual cable software.
- Linux desktop can listen to PulseAudio/PipeWire monitor sources through `parec @DEFAULT_MONITOR@` or `pw-record` when those tools are available.
- Audio remains live/in-memory only; no Internet permission was added.

- Android offline audio messenger with no Internet permission.
- Encrypted group messages, signed message modes, second-factor group invites, QR invite support, local safety fingerprints, replay suppression, expiring logs, and panic wipe.
- Audio profiles for nearby, normal, noisy, and narrowband bridge conditions.
- Radio presets for acoustic/no-cable, cabled, USB-interface, narrowband bridge, and SDR receive workflows.
- Manual Hop Assist for a shared operator-visible channel schedule; it does not control or retune external radios.
- Linux/Windows desktop companion with local vault, audio diagnostics, device test, SDR PCM-stdin decode helper, and QR import/export.
- Desktop-only FieldPacket tools for FP1 compose/decode, APRS/AX.25 preview, and KISS/TNC hex helper workflows.
- Windows desktop ZIP and unsigned Windows installer path. Windows builds are not code-signed yet.
