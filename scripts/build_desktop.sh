#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

OUT="build/desktop/classes"
DESKTOP_JAR="build/deaddrop-desktop.jar"
LEGACY_JAR="build/deaddrop-linux-gui.jar"
ZXING_JAR="${ZXING_CORE_JAR:-}"
if [ -z "$ZXING_JAR" ]; then
  ZXING_JAR=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/com.google.zxing/core" -name 'core-*.jar' -type f 2>/dev/null | sort | tail -n 1 || true)
fi
if [ -z "$ZXING_JAR" ] || [ ! -f "$ZXING_JAR" ]; then
  echo "ZXing core jar not found. Run the Android Gradle build once, or set ZXING_CORE_JAR=/path/to/core-3.5.3.jar" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT" build

javac -encoding UTF-8 -cp "$ZXING_JAR" -d "$OUT" \
  app/src/main/java/org/deaddrop/app/DeadDropCrypto.java \
  app/src/main/java/org/deaddrop/app/AudioDiagnostics.java \
  app/src/main/java/org/deaddrop/app/AudioModem.java \
  desktop/src/org/fieldpacket/core/*.java \
  desktop/src/org/deaddrop/desktop/FieldPacketToolsPanel.java \
  desktop/src/org/deaddrop/desktop/DeadDropDesktopGui.java

(
  cd "$OUT"
  jar --extract --file "$ZXING_JAR"
)
jar --create --file "$DESKTOP_JAR" --main-class org.deaddrop.desktop.DeadDropDesktopGui -C "$OUT" .
cp "$DESKTOP_JAR" "$LEGACY_JAR"

cat > build/deaddrop-desktop <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="java"
fi
exec "$JAVA_BIN" -jar "$(dirname "$0")/deaddrop-desktop.jar" "$@"
LAUNCHER
chmod +x build/deaddrop-desktop

cat > build/deaddrop-linux-gui <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="java"
fi
exec "$JAVA_BIN" -jar "$(dirname "$0")/deaddrop-linux-gui.jar" "$@"
LAUNCHER
chmod +x build/deaddrop-linux-gui

cat > build/deaddrop-windows.cmd <<'WINLAUNCHER'
@echo off
setlocal
set "DIR=%~dp0"
where java >NUL 2>NUL
if errorlevel 1 (
  echo Java 17 or newer is required. Install Temurin/OpenJDK 17+, then run this again.
  pause
  exit /b 1
)
java -jar "%DIR%deaddrop-desktop.jar" %*
WINLAUNCHER

printf 'Built %s (legacy alias: %s)\n' "$DESKTOP_JAR" "$LEGACY_JAR"
