# Windows Desktop Companion

DeadDrop Desktop can be packaged on Windows as a native app launcher with a bundled Java runtime and a per-user `.exe` installer.

## Status

Implemented:

- native Windows app image created with `jpackage`;
- per-user installer `.exe` created with Inno Setup;
- bundled Java runtime in the native package, so users do not need to install Java separately;
- Start menu and optional desktop shortcuts;
- create and join groups;
- export invites as text and QR;
- import invite QR images from screenshots/photos;
- transmit and listen using Java Sound devices;
- select microphone/line-in and output audio devices when Java exposes them;
- choose **System output** as the listen source to capture what Windows is playing through the default speakers/headphones using the bundled WASAPI loopback helper;
- run an in-memory device test with test tone plus short recorded diagnostic sample;
- use Fast/Nearby, Normal, Robust+FEC, Voice Bridge/Narrowband, and Ultra/Noisy audio profiles;
- use radio presets for direct audio, acoustic/no-cable, cabled, USB-interface, narrowband bridge, and SDR receive workflows;
- view Manual Hop Assist as a shared operator channel schedule; it does not retune radios or SDRs;
- open desktop-only FieldPacket tools for FP1 compose/decode, APRS/AX.25 preview, and KISS/TNC hex helpers;
- send anonymous encrypted, signed encrypted, and signed plaintext packets;
- local passphrase-protected desktop vault;
- known-sender safety fingerprints and key-change warnings;
- panic wipe for local desktop vault state.

Current limitations:

- installer is not code-signed yet;
- no Windows background listener service;
- Windows system-output capture uses the bundled PowerShell/C# WASAPI helper against the default render endpoint; exclusive-mode audio or unusual drivers can still prevent capture.

## Build the native Windows package

From Windows PowerShell in the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File packaging\windows\build-windows-native.ps1 -InstallInno
```

The script requires a JDK 17+ and ZXing core jar. If Inno Setup Compiler is not found, `-InstallInno` downloads and installs Inno Setup for the current user.

Outputs:

```text
build\deaddrop-desktop.jar
build\windows-native\app-image\DeadDrop Desktop\DeadDrop Desktop.exe
build\windows-native\installer\DeadDrop-Desktop-<version>-Setup.exe
build\deaddrop-<version>-desktop-windows-native.zip
build\SHA256SUMS-<version>-windows-native.txt
```

## Install on Windows

Run:

```text
DeadDrop-Desktop-<version>-Setup.exe
```

The installer does not require administrator rights. It installs app files under:

```text
%LOCALAPPDATA%\Programs\DeadDrop Desktop
```

It creates a Start menu shortcut and can optionally create a desktop shortcut.

## Portable app image

The app image can run without installation:

```text
DeadDrop Desktop.exe
```

## Vault behavior

The app asks for a local vault passphrase:

- enter a passphrase to save groups/signing identity locally;
- leave it blank for a temporary session that is not saved.

Windows stores the encrypted vault under:

```text
%USERPROFILE%\.config\deaddrop-desktop\vault.ddv
```

Uninstalling the app leaves this vault file alone.

## Audio notes

Use **Audio check** and **Device test** first. **Device test** plays a short tone, records a short in-memory sample from the selected listen source, and reports peak/RMS/clipping. If Java exposes compatible mixers, choose the desired microphone/line-in and output device from the dropdowns. For browser/app audio already playing on the machine, choose **System output / what this computer is playing**; the Windows package includes `deaddrop-wasapi-loopback.ps1` for WASAPI loopback capture.

Field tuning guidance:

- aim for peak roughly 10–80%;
- avoid clipping;
- use Normal for ordinary use;
- use Robust+FEC or Ultra/Noisy when packets are heard but do not decode;
- keep in mind the app processes audio in memory and does not store audio files.
