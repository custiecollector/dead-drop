#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(python3 - <<'PY'
import re
from pathlib import Path
text = Path('app/build.gradle').read_text(encoding='utf-8')
match = re.search(r"versionName\s+'([^']+)'", text)
if not match:
    raise SystemExit('versionName not found in app/build.gradle')
print(match.group(1))
PY
)

RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$RELEASE_APK" ]; then
  echo "Release APK output is missing. Run: gradle --no-daemon assembleRelease" >&2
  exit 1
fi

OUT_RELEASE="build/DeadDrop-${VERSION}-android.apk"
SUMS="build/SHA256SUMS-${VERSION}-android.txt"

rm -f "$OUT_RELEASE" "$SUMS"
mkdir -p build
cp "$RELEASE_APK" "$OUT_RELEASE"

sha256sum "$OUT_RELEASE" > "$SUMS"
printf 'Created %s\nWrote %s\n' "$OUT_RELEASE" "$SUMS"
