#!/bin/bash
cd "$(dirname "$0")"
export PATH="/usr/local/bin:/opt/homebrew/bin:$PATH"
if ! command -v node >/dev/null 2>&1; then
  osascript -e 'display alert "Verflixed" message "Bitte Node.js LTS installieren: https://nodejs.org"' 2>/dev/null || true
  open "https://nodejs.org" 2>/dev/null || true
  exit 1
fi
echo "Starte Verflixed Webapp…"
npx --yes electron@33.2.0 .
