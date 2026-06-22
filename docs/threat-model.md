# Threat Model

DeadDrop sends short messages over audio. It assumes the audio channel is observable, unreliable, and easy to jam.

## Goals

DeadDrop aims to protect:

- encrypted message contents from listeners who do not have the group key;
- invite material by requiring a separate second factor;
- local group keys and text logs at rest;
- local replay state from being reset just by clearing the visible message board;
- packet integrity;
- users from accidentally storing audio samples.

## Message modes

- **Anonymous encrypted**: encrypted to the group key. Receivers know only that someone with the group key sent it.
- **Signed handle encrypted**: encrypted to the group key and signed by a sender identity.
- **Signed plaintext**: signed but not encrypted. Useful for testing and interoperability.

## Signed-sender safety

Signed modes expose a short safety fingerprint. Users should compare fingerprints out-of-band before trusting a handle. DeadDrop remembers signed senders locally and warns if a remembered handle appears with a different signing fingerprint, but this is not a substitute for an external trust process or audit.

## Not covered

DeadDrop does not protect against:

- jamming;
- detection that a transmission happened;
- traffic analysis;
- radio direction finding;
- compromised devices or operating systems;
- malicious group members;
- denial-of-service packet floods;
- legal limits on encrypted or data transmissions.

## Prototype limits

- Robust/Ultra modes use simple bit repetition rather than stronger FEC/interleaving.
- Android background listening depends on foreground service behavior and user approval.
- Desktop live audio depends on Java Sound exposing compatible devices.
- The project has not been externally audited.
