#!/usr/bin/env bash
# Records eval fixture clips on macOS: 16kHz mono PCM16 WAV + ground-truth transcript.
# Usage: ./record.sh   (from backend/scripts/eval; records into fixtures/)
# Needs ffmpeg (brew install ffmpeg). Stop each recording with q.
# sox alternative: rec -r 16000 -c 1 -b 16 fixtures/<name>.wav
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p fixtures

while true; do
  read -r -p "clip name (empty to quit): " name
  [ -z "$name" ] && break
  echo "Recording fixtures/$name.wav — speak now, press q to stop."
  ffmpeg -hide_banner -loglevel error -f avfoundation -i ":0" \
    -ac 1 -ar 16000 -c:a pcm_s16le -y "fixtures/$name.wav"
  read -r -p "exact transcript (numbers as digits, e.g. 42000): " ref
  printf '%s\n' "$ref" > "fixtures/$name.txt"
  echo "saved fixtures/$name.wav + $name.txt"
done
