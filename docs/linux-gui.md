# Desktop Companion

DeadDrop includes a small Swing desktop app for Linux/Windows desktop use. It uses the same packet, crypto, QR, and modem code as the Android app.

## Build

```bash
./scripts/build_desktop.sh
```

Outputs:

```text
build/deaddrop-desktop.jar
build/deaddrop-desktop
```

Compatibility aliases are also created for the older `deaddrop-linux-gui` name:

```text
build/deaddrop-linux-gui.jar
build/deaddrop-linux-gui
```

## Run on Linux

```bash
./build/deaddrop-desktop
```

or:

```bash
java -jar build/deaddrop-desktop.jar
```

The desktop app asks for a local vault passphrase. There is no default passphrase.

- Enter a passphrase to save groups and identity locally.
- Leave it blank for a temporary session.

Default vault path:

```text
~/.config/deaddrop-desktop/vault.ddv
```

If an older `~/.config/deaddrop-linux/vault.ddv` exists and the new path is empty, the desktop app reads the old vault and saves future changes to the new neutral desktop path.

## Features

- create and join groups;
- export invites as text and QR;
- transmit through the selected/default speaker;
- listen through the selected/default microphone or line-in;
- listen to supported Linux system-output audio through PulseAudio/PipeWire monitor capture (`parec @DEFAULT_MONITOR@` or `pw-record`);
- use Fast/Nearby, Normal, Robust+FEC, Voice Bridge/Narrowband, or Ultra/Noisy audio profiles;
- use radio presets for direct audio, acoustic/no-cable, cabled, USB-interface, narrowband bridge, and SDR receive workflows;
- view Manual Hop Assist as a shared operator channel schedule; it does not retune radios or SDRs;
- open desktop-only FieldPacket tools for FP1 compose/decode, APRS/AX.25 preview, and KISS/TNC hex helpers;
- view peak/RMS/clipping audio diagnostics for field tuning;
- run a device test that plays a tone, records a short in-memory sample, and reports peak/RMS/clipping;
- import invite QR images from screenshots/photos;
- send anonymous encrypted, signed encrypted, or signed plaintext messages;
- remember signed-sender safety fingerprints and warn on key changes;
- wipe local desktop state.

## Package

```bash
./scripts/package_desktop.sh
```

Outputs Linux and Windows desktop zips plus SHA-256 sums under `build/`.

## Audio devices

Check Java audio devices:

```bash
java -jar build/deaddrop-desktop.jar --audio-devices
```

## Limitations

- No background listener daemon.
- Live microphone/line-in audio depends on Java audio device and mixer support.
- System-output listening depends on a PulseAudio/PipeWire monitor command (`parec` from pulseaudio-utils/pipewire-pulse, or `pw-record`) being available on PATH.
- Windows live audio depends on hardware and driver support.
