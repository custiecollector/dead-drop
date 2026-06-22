#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

scripts/build_desktop.sh

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

PKG_ROOT="build/package"
LINUX_DIR="$PKG_ROOT/deaddrop-${VERSION}-desktop-linux"
WINDOWS_DIR="$PKG_ROOT/deaddrop-${VERSION}-desktop-windows"
LINUX_ZIP="build/DeadDrop-${VERSION}-linux-desktop.zip"
WINDOWS_ZIP="build/DeadDrop-${VERSION}-windows-desktop.zip"
SUMS="build/SHA256SUMS-${VERSION}-desktop.txt"

rm -rf "$LINUX_DIR" "$WINDOWS_DIR" "$LINUX_ZIP" "$WINDOWS_ZIP" "$SUMS"
mkdir -p "$LINUX_DIR/docs" "$LINUX_DIR/scripts" "$WINDOWS_DIR/docs" "$WINDOWS_DIR/scripts"

cp build/deaddrop-desktop.jar "$LINUX_DIR/"
cp build/deaddrop-desktop "$LINUX_DIR/"
cp README.md LICENSE "$LINUX_DIR/"
cp docs/linux-gui.md docs/security.md docs/threat-model.md docs/protocol.md docs/radio-sdr-interfaces.md "$LINUX_DIR/docs/"
cp scripts/decode_sdr_pcm.sh "$LINUX_DIR/scripts/"

cp build/deaddrop-desktop.jar "$WINDOWS_DIR/"
cp packaging/windows/deaddrop-installer.ps1 "$WINDOWS_DIR/"
cp packaging/windows/deaddrop-wasapi-loopback.ps1 "$WINDOWS_DIR/"
cp packaging/windows/build-windows-native.ps1 "$WINDOWS_DIR/"
cp packaging/windows/README-WINDOWS.txt "$WINDOWS_DIR/"
cp packaging/windows/DeadDrop.iss "$WINDOWS_DIR/"
cp packaging/windows/deaddrop.ico "$WINDOWS_DIR/"
cp README.md LICENSE "$WINDOWS_DIR/"
cp docs/windows-desktop.md docs/security.md docs/threat-model.md docs/protocol.md docs/radio-sdr-interfaces.md "$WINDOWS_DIR/docs/"
cp scripts/decode_sdr_pcm.ps1 "$WINDOWS_DIR/scripts/"

python3 - <<PY
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

windows_dir = Path('$WINDOWS_DIR')


def write_crlf(src, dest):
    data = Path(src).read_text(encoding='utf-8')
    data = data.replace('\r\n', '\n').replace('\r', '\n')
    Path(dest).write_bytes(data.replace('\n', '\r\n').encode('utf-8'))

# Emit Windows batch files with CRLF line endings for normal Windows use.
# Source .cmd.in templates stay LF-normalized.
write_crlf('build/deaddrop-windows.cmd', windows_dir / 'deaddrop-windows.cmd')
write_crlf('packaging/windows/install-deaddrop-windows.cmd.in', windows_dir / 'install-deaddrop-windows.cmd')
write_crlf('packaging/windows/uninstall-deaddrop-windows.cmd.in', windows_dir / 'uninstall-deaddrop-windows.cmd')


def make_zip(src, dest):
    src = Path(src)
    dest = Path(dest)
    with ZipFile(dest, 'w', ZIP_DEFLATED) as zf:
        for path in sorted(src.rglob('*')):
            if path.is_file():
                zf.write(path, path.relative_to(src.parent))

make_zip('$LINUX_DIR', '$LINUX_ZIP')
make_zip('$WINDOWS_DIR', '$WINDOWS_ZIP')
PY

sha256sum "$LINUX_ZIP" "$WINDOWS_ZIP" build/deaddrop-desktop.jar > "$SUMS"
printf 'Created %s\nCreated %s\nWrote %s\n' "$LINUX_ZIP" "$WINDOWS_ZIP" "$SUMS"
