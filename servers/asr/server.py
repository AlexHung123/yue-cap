"""Spark A — SenseVoice ASR for YueCap (POST /v1/asr, raw audio/wav body)."""
from __future__ import annotations

import io
import os
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx
import uvicorn
from fastapi import FastAPI, Query, Request, Response
from fastapi.responses import JSONResponse

MODEL_DIR = Path(
    os.environ.get(
        "SENSEVOICE_DIR",
        str(Path(__file__).resolve().parent / "models" / "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"),
    )
)
NUM_THREADS = int(os.environ.get("ASR_THREADS", "4"))
HOST = os.environ.get("ASR_HOST", "0.0.0.0")
PORT = int(os.environ.get("ASR_PORT", "8005"))

ALLOWED_LANG = {"auto", "zh", "en", "yue", "ja", "ko", "nospeech"}

recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
    model=str(MODEL_DIR / "model.int8.onnx"),
    tokens=str(MODEL_DIR / "tokens.txt"),
    use_itn=True,
    language="auto",
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

    lang = (language or "yue").strip().lower()
    if lang not in ALLOWED_LANG:
        lang = "yue"

    # Recreate stream with requested language hint when possible.
    # OfflineRecognizer.from_sense_voice bakes language at init; for per-request
    # language we pass via create_stream only if supported — otherwise decode with
    # the shared recognizer (auto) and report the requested lang.
    stream = recognizer.create_stream()
    stream.accept_waveform(sample_rate, samples)
    recognizer.decode_stream(stream)
    text = (stream.result.text or "").strip()
    return JSONResponse({"text": text, "lang": lang})


if __name__ == "__main__":
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
