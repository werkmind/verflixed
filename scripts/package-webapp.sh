#!/usr/bin/env bash
# Package desktop webapp zip for GitHub Releases / distribution.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_RAW="${1:-$ROOT/dist/Verflixed-Webapp.zip}"
if [[ "$OUT_RAW" = /* ]]; then OUT="$OUT_RAW"; else OUT="$ROOT/$OUT_RAW"; fi
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/Verflixed-Webapp"
cp -a "$ROOT/webapp/." "$STAGE/Verflixed-Webapp/"
# Drop heavy / local-only bits from the starter zip
rm -rf "$STAGE/Verflixed-Webapp/node_modules" \
  "$STAGE/Verflixed-Webapp/dist" \
  "$STAGE/Verflixed-Webapp/.cache" 2>/dev/null || true

cat > "$STAGE/Verflixed-Webapp/Start-macOS.command" <<'EOF'
#!/bin/bash
cd "$(dirname "$0")"
if ! command -v npm >/dev/null 2>&1; then
  echo "Node.js/npm fehlt. Bitte https://nodejs.org installieren."
  read -r _
  exit 1
fi
npm install
npm start
EOF
chmod +x "$STAGE/Verflixed-Webapp/Start-macOS.command"

cat > "$STAGE/Verflixed-Webapp/Start-Windows.bat" <<'EOF'
@echo off
cd /d "%~dp0"
where npm >nul 2>&1 || (
  echo Node.js/npm fehlt. Bitte https://nodejs.org installieren.
  pause
  exit /b 1
)
call npm install
call npm start
EOF

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
(cd "$STAGE" && zip -qr "$OUT" Verflixed-Webapp)
echo "Wrote $OUT"
ls -lh "$OUT"
