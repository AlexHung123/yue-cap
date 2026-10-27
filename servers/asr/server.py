"""Spark A — SenseVoice ASR for YueCap (POST /v1/asr, raw audio/wav body)."""
from __future__ import annotations

import io
import os
import tarfile
import urllib.request
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx
import uvicorn
from fastapi import FastAPI, Query, Request, Response
from fastapi.responses import JSONResponse

MODEL_NAME = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
MODEL_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    f"{MODEL_NAME}.tar.bz2"
)
MODEL_DIR = Path(
    os.environ.get(
        "SENSEVOICE_DIR",
        str(Path(__file__).resolve().parent / "models" / MODEL_NAME),
    )
)
NUM_THREADS = int(os.environ.get("ASR_THREADS", "4"))
HOST = os.environ.get("ASR_HOST", "0.0.0.0")
PORT = int(os.environ.get("ASR_PORT", "8005"))

ALLOWED_LANG = {"auto", "zh", "en", "yue", "ja", "ko", "nospeech"}


def ensure_model(model_dir: Path) -> tuple[Path, Path]:
    """Download and extract SenseVoice weights if model.int8.onnx / tokens.txt are missing."""
    model_path = model_dir / "model.int8.onnx"
    tokens_path = model_dir / "tokens.txt"
    if model_path.is_file() and tokens_path.is_file():
        return model_path, tokens_path

    models_root = model_dir.parent
    models_root.mkdir(parents=True, exist_ok=True)
    archive = models_root / f"{MODEL_NAME}.tar.bz2"

    print(f"[asr] SenseVoice model missing under {model_dir}")
    print(f"[asr] Downloading {MODEL_URL}")
    print("[asr] This is ~200MB+ and may take a few minutes…")

    def _progress(block_num: int, block_size: int, total_size: int) -> None:
        if total_size <= 0:
            return
        done = block_num * block_size
        pct = min(100.0, done * 100.0 / total_size)
        if block_num % 50 == 0 or done >= total_size:
            print(f"\r[asr] download {pct:5.1f}%", end="", flush=True)

    urllib.request.urlretrieve(MODEL_URL, archive, reporthook=_progress)
    print()
    print(f"[asr] Extracting {archive.name} → {models_root}")
    with tarfile.open(archive, "r:bz2") as tar:
        tar.extractall(path=models_root)

    # Keep archive optional; delete to save disk.
    try:
        archive.unlink(missing_ok=True)
    except OSError:
        pass

    if not model_path.is_file() or not tokens_path.is_file():
        raise SystemExit(
            f"Download finished but files still missing:\n"
            f"  {model_path}\n  {tokens_path}"
        )
    print("[asr] Model ready.")
    return model_path, tokens_path


_model, _tokens = ensure_model(MODEL_DIR)
print(f"[asr] Loading {_model}")
# YueCap is Cantonese-first. Never use language="auto" — SenseVoice then
# frequently mis-tags Cantonese as Japanese (ja) or Mandarin (zh).
recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
    model=str(_model),
    tokens=str(_tokens),
    use_itn=True,
    language="yue",
    num_threads=NUM_THREADS,
)

app = FastAPI(title="YueCap Spark A — SenseVoice ASR")


def wav_bytes_to_float32(data: bytes) -> tuple[np.ndarray, int]:
    with wave.open(io.BytesIO(data), "rb") as wf:
        channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        sample_rate = wf.getframerate()
        frames = wf.readframes(wf.getnframes())
    if sample_width != 2:
        raise ValueError(f"expected 16-bit PCM, got sample_width={sample_width}")
    audio = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
    if channels > 1:
        audio = audio.reshape(-1, channels).mean(axis=1)
    return audio, sample_rate


@app.get("/health")
def health() -> dict:
    return {"ok": True, "model": "sensevoice-small-int8"}


@app.post("/v1/asr")
async def asr(
    request: Request,
    language: str = Query(default="yue"),
) -> Response:
    raw = await request.body()
    if not raw:
        return JSONResponse({"text": "", "lang": language}, status_code=200)
    try:
        samples, sample_rate = wav_bytes_to_float32(raw)
    except Exception as exc:  # noqa: BLE001
        return JSONResponse({"error": f"invalid wav: {exc}"}, status_code=400)

    # Product path: always force Cantonese ASR regardless of query / client.
    # Query param is accepted for API compatibility but cannot drift to ja/auto.
    requested = (language or "yue").strip().lower()
    if requested not in ALLOWED_LANG:
        requested = "yue"
    lang = "yue"

    stream = recognizer.create_stream()
    stream.accept_waveform(sample_rate, samples)
    recognizer.decode_stream(stream)
    text = (stream.result.text or "").strip()
    return JSONResponse({"text": text, "lang": lang, "requested_lang": requested})


if __name__ == "__main__":
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
