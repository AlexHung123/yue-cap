#!/usr/bin/env bash

# Start Spark A SenseVoice ASR from any working directory.
# Same as: source servers/asr/.venv/bin/activate && cd servers/asr && python server.py

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VENV="$SCRIPT_DIR/.venv"

if [ ! -f "$VENV/bin/activate" ]; then
  echo "Missing venv: $VENV" >&2
  echo "Create it once from the repo root:" >&2
  echo "  cd \"$SCRIPT_DIR\"" >&2
  echo "  uv venv --python 3.11 .venv" >&2
  echo "  source .venv/bin/activate" >&2
  echo "  pip install -r requirements.txt" >&2
  exit 1
fi

# shellcheck disable=SC1091
source "$VENV/bin/activate"
cd "$SCRIPT_DIR"
exec python server.py
