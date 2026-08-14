#!/usr/bin/env bash
# Render + mux the brand opener into APK/webapp assets.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
FRAMES=/tmp/vf_intro_frames

python3 "$HERE/make_sting.py"
python3 "$HERE/render_intro.py"

OUT_RAW="$ROOT/app/src/main/res/raw/brand_intro.mp4"
OUT_WEB="$ROOT/webapp/renderer/brand_intro.mp4"
mkdir -p "$(dirname "$OUT_RAW")" "$(dirname "$OUT_WEB")"

ffmpeg -y -loglevel error \
  -framerate 30 -i "$FRAMES/f%04d.png" \
  -i /tmp/splash_tudum.wav \
  -map 0:v -map 1:a \
  -c:v libx264 -profile:v high -level 4.0 -pix_fmt yuv420p \
  -preset veryslow -crf 24 -movflags +faststart \
  -c:a aac -b:a 128k -ac 2 -ar 48000 \
  -shortest "$OUT_RAW"

cp -f "$OUT_RAW" "$OUT_WEB"
ls -lh "$OUT_RAW" "$OUT_WEB"
ffprobe -v error -show_entries format=duration:stream=codec_name,width,height,r_frame_rate \
  -of default=noprint_wrappers=1 "$OUT_RAW"
