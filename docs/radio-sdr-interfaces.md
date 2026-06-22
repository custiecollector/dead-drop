# Radio and SDR interface recommendations

DeadDrop should remain radio-agnostic. These notes describe interface categories and common product families that are likely to work with DeadDrop's audio modem.

## Radio mode presets

DeadDrop radio mode should support these generic presets:

- `No cable / acoustic handset` — hold the device speaker near the radio microphone and the radio speaker near the device microphone.
- `Generic audio cable` — passive speaker/mic cable path with manual PTT or VOX.
- `Handheld accessory cable` — common handheld accessory-port wiring.
- `USB radio interface` — host sees a USB audio device, with hardware PTT support where available.
- `Voice call / narrowband bridge` — slower profile intended for speech-band bridges; it is an experiment-friendly preset, not a carrier or call-control feature.
- `SDR receive` — receive-only path from demodulated SDR or virtual-audio PCM.

## Recommended interface categories

### No cable / acoustic handset

Best for first confirmation that a radio audio channel can carry DeadDrop packets without buying or configuring anything.

Suggested app defaults:

```text
Profile: Robust+FEC
Repeat: 5x
TX lead-in: 1500 ms
TX tail: 500 ms
Warm-up: on
PTT: manual or VOX
```

Tradeoffs: easiest to try, least repeatable. Room noise, handset AGC, speaker distortion, and VOX clipping can all matter.

### USB radio interfaces

Recommended product families to evaluate:

- Digirig Mobile with the appropriate radio cable.
- Digirig Lite with the appropriate radio cable.

Why these are attractive:

- The host sees a standard USB audio interface.
- Radio-side cables are replaceable, so DeadDrop does not become tied to one radio brand.
- Hardware PTT/control paths may be possible later on supported hosts.

### Passive phone/tablet audio cables

Recommended product family to evaluate:

- BTECH APRS-K1 style audio cable, paired with a USB-C audio adapter that explicitly supports microphone input.

Why this is useful:

- Low-cost first wired path.
- Good for Android-first experiments.
- Likely VOX/manual-PTT first, so radio mode lead-in/tail settings matter.

Caution: many USB-C headphone adapters are output-only. For this path, the adapter must expose both headphone output and microphone input.

### Generic USB audio interfaces

A generic USB sound card or isolated audio interface can work if it provides:

- mono or stereo output to radio mic/audio input;
- mono input from radio speaker/data/audio output;
- stable host drivers on the target OS;
- enough level control or attenuation to avoid clipping.

## SDR receive

DeadDrop's software boundary should be demodulated audio or a locally attached USB receiver, not a specific SDR product.

Any SDR path is compatible if the host can provide one of:

- mono signed 16-bit PCM to stdin;
- a virtual audio capture device containing demodulated narrow-FM audio;
- an audio file or named pipe that can be converted to 44.1 kHz mono PCM.

Common compatible SDR families include RTL-compatible USB receivers, SDRplay, Airspy, HackRF, LimeSDR, and any other receiver whose software can produce demodulated audio.

## Android USB SDR receive

Android devices can use USB SDR receivers when the phone/tablet supports USB host/OTG and the receiver has enough power. DeadDrop includes generic Android USB-host discovery and user permission plumbing for locally attached SDR receivers without adding network permissions.

The first Android package layer is intentionally quiet and hardware-generic:

- declares USB host support as optional;
- scans attached USB devices for generic SDR-like receiver interfaces;
- requests Android's user-approved USB permission for the attached receiver;
- keeps receiver names/product IDs out of public documentation;
- does not add `android.permission.INTERNET`.

Direct Android IQ demodulation is planned behind this USB plumbing. Until direct demodulation is available on Android, Android receive still uses DeadDrop's acoustic/audio input path, while Linux/Windows can use PCM-stdin or virtual-audio SDR pipelines.

Initial desktop CLI target:

```bash
java -jar build/deaddrop-desktop.jar \
  --decode-pcm-stdin \
  --rate 44100 \
  --format s16le \
  --channels 1
```

Linux helper shape:

```bash
scripts/decode_sdr_pcm.sh -- <pcm-producing command> [args...]
```

Windows helper shape:

```powershell
powershell -File scripts\decode_sdr_pcm.ps1 -- <pcm-producing command> [args...]
```

The command after `--` should output mono signed 16-bit little-endian PCM at 44.1 kHz. If an SDR demodulator produces another rate, resample before piping it into DeadDrop.
