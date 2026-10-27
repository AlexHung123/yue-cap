# Spark A — SenseVoice ASR (Mac / LAN)

HTTP ASR for YueCap: `POST /v1/asr?language=yue` with raw 16-bit PCM WAV body.

SenseVoice language is **forced to `yue`** at recognizer load (not `auto`) so Cantonese is not mis-detected as Japanese.

## One-time setup

```bash
cd servers/asr
uv venv --python 3.11 .venv   # or: python3.11 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt   # or: uv pip install -r requirements.txt

# Model weights are NOT in git. First `python server.py` auto-downloads
# sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17 (~200MB+) into models/.
```

## Run

```bash
source .venv/bin/activate
python server.py
# first run downloads the model if missing, then listens on 0.0.0.0:8005
```

Optional env: `ASR_HOST`, `ASR_PORT` (default 8005), `ASR_THREADS`, `SENSEVOICE_DIR`.

## Smoke test

```bash
curl -s http://127.0.0.1:8005/health
```

YueCap Oppo: `asr_mode=REMOTE_SPARK_A`, `asr_base_url=http://<mac-lan-ip>:8005`.
