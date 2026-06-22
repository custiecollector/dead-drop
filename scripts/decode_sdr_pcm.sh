#!/usr/bin/env bash
set -euo pipefail

# Feed demodulated SDR/audio PCM into DeadDrop's desktop decoder.
#
# The command after "--" must write mono signed 16-bit little-endian PCM to stdout.
# DeadDrop currently expects 44.1 kHz input; resample before piping if your
# demodulator produces another rate.
#
# Example shape:
#   scripts/decode_sdr_pcm.sh -- your_sdr_fm_demodulator -f <freq> -r 44100 -

if [[ "${1:-}" != "--" || $# -lt 2 ]]; then
  cat >&2 <<'USAGE'
Usage:
  scripts/decode_sdr_pcm.sh -- <pcm-producing command> [args...]

The command must write mono s16le PCM at 44100 Hz to stdout.
Set DEADDROP_DESKTOP_JAR to override the JAR path; default is build/deaddrop-desktop.jar.
Set DEADDROP_VAULT_PASSPHRASE and the decoder will open the local desktop vault for encrypted group packets.
USAGE
  exit 2
fi
shift

jar="${DEADDROP_DESKTOP_JAR:-build/deaddrop-desktop.jar}"
if [[ ! -f "$jar" ]]; then
  echo "DeadDrop desktop JAR not found: $jar" >&2
  echo "Run ./scripts/build_desktop.sh first or set DEADDROP_DESKTOP_JAR." >&2
  exit 1
fi

vault_args=()
if [[ -n "${DEADDROP_VAULT_PASSPHRASE:-}" ]]; then
  vault_args=(--vault-passphrase-env DEADDROP_VAULT_PASSPHRASE)
fi

"$@" | java -jar "$jar" --decode-pcm-stdin --rate 44100 --format s16le --channels 1 "${vault_args[@]}"
