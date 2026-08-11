#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
PORT="${1:-8080}"
IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
[[ -z "${IP}" ]] && IP="DEINE-PC-IP"
echo ""
echo "Verflixed APK-Server"
echo "==================="
echo "Silk Browser URL:"
echo "  http://${IP}:${PORT}/Verflixed-FireTV.apk"
echo ""
cd "$DIR/silk"
python3 -m http.server "$PORT"
