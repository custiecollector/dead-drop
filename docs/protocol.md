# Protocol Notes

This is a description of the current prototype, not a stable specification.

## Transport

DeadDrop treats audio as a one-way, unreliable transport.

Sender:

1. Build a message packet.
2. Encrypt and/or sign it.
3. Wrap it in an audio frame.
4. Encode the frame as tones.
5. Play it through the speaker.

Receiver:

1. Read live audio samples into memory from the selected source (microphone/line-in, supported device playback capture, or PCM pipeline).
2. Search for a frame preamble and sync word.
3. Decode bits into bytes.
4. Check the frame CRC.
5. Open the message packet.
6. Add accepted messages to the local board.

Normal app behavior does not write audio samples to disk.

## Audio profiles

### Fast/Nearby

- Binary FSK/AFSK.
- Tones: 1400 Hz and 2400 Hz.
- Raw rate: about 919 bps.
- Shortest airtime; best for clean device-to-device audio.

### Normal

- Binary FSK/AFSK.
- Tones: 1200 Hz and 2200 Hz.
- Raw rate: about 735 bps.
- Best for direct speaker-to-microphone paths.

### Robust+FEC

- Binary FSK/AFSK.
- Tones: 1000 Hz and 2000 Hz.
- 3x bit repetition with majority voting.
- Effective raw rate: about 122.5 bps.
- Slower, but more tolerant of noise.

### Ultra/Noisy

- Binary FSK/AFSK.
- Tones: 900 Hz and 1900 Hz.
- 5x bit repetition with majority voting.
- Effective raw rate: about 55 bps.
- Very slow emergency/noisy-link profile.

All profiles use a preamble, sync word, payload length, payload bytes, and CRC32.

## Groups and invites

A group contains a name and a symmetric key. Invites are exported as `DDINV1` text and protected with a second factor.

Share the invite and second factor through separate paths when possible.

## Compatibility

The packet format is not stable. Future versions may change framing, FEC, identity handling, or invite format.
