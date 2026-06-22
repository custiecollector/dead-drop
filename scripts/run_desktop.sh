#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ ! -f build/deaddrop-desktop.jar ]; then
  scripts/build_desktop.sh
fi
exec build/deaddrop-desktop "$@"
