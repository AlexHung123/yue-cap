"""Spark A — SenseVoice ASR for YueCap (POST /v1/asr, raw audio/wav body).

Loads the FunASR PyTorch checkpoint sensevoice_small_yue from Hugging Face
(ASLP-lab/WSYue-ASR). Weights are never fetched from ModelScope.
"""
from __future__ import annotations

import io
import os
import re
import threading
import wave
from pathlib import Path

import numpy as np
import uvicorn
from fastapi import FastAPI, Query, Request, Response
from fastapi.responses import JSONResponse
from funasr import AutoModel
from opencc import OpenCC

# Cantonese fine-tune of SenseVoice Small (WenetSpeech-Yue, ~21.8k hours).
# This process runs on the Spark A M3 Ultra, not on the dev machine.
# The checkpoint does not emit punctuation; Spark B adds it when polishing.
REPO_ID = "ASLP-lab/WSYue-ASR"
MODEL_SUBDIR = "sensevoice_small_yue"
MODEL_NAME = MODEL_SUBDIR
REQUIRED_FILES = (
    "model.pt",
    "config.yaml",
    "configuration.json",
    "am.mvn",
    "chn_jpn_yue_eng_ko_spectok.bpe.model",
)
MODEL_DIR = Path(
    os.environ.get(
        "SENSEVOICE_DIR",
        str(Path(__file__).resolve().parent / "models" / MODEL_SUBDIR),
    )
)
NUM_THREADS = int(os.environ.get("ASR_THREADS", "4"))
HOST = os.environ.get("ASR_HOST", "0.0.0.0")
PORT = int(os.environ.get("ASR_PORT", "8006"))
# CPU is the reliable default. SANM attention in this checkpoint is not
# dependable on MPS. Set ASR_DEVICE=mps or ASR_DEVICE=cuda to override.
DEVICE = os.environ.get("ASR_DEVICE", "cpu")

ALLOWED_LANG = {"auto", "zh", "en", "yue", "ja", "ko", "nospeech"}
# SenseVoice event tags that are not someone talking. A forced language=yue
# still transcribes these, which shows up as captions during a break or a
# music bed. FunASR puts the event in the text as <|BGM|>, <|Applause|>, and
# so on. An empty event means the result did not report one.
NON_SPEECH_EVENTS = {
    "bgm",
    "music",
    "applause",
    "laughter",
    "laugh",
    "cry",
    "sneeze",
    "breath",
    "cough",
    "nospeech",
}
# SenseVoice emits simplified characters. YueCap captions use Hong Kong
# traditional (s2hk): character conversion plus HK variants, English left as-is.
_TAG = re.compile(r"<\|([^|>]*)\|>")
_to_traditional = OpenCC("s2hk")
_infer_lock = threading.Lock()


def _ready(model_dir: Path) -> bool:
    return all((model_dir / name).is_file() for name in REQUIRED_FILES)


def ensure_model(model_dir: Path) -> Path:
    """Return a directory of Hugging Face sensevoice_small_yue weights.

    Downloads only that subdirectory from ASLP-lab/WSYue-ASR. The sibling
    WenetSpeech-Yue checkpoints in the same repo are not fetched.
    """
    if _ready(model_dir):
        return model_dir
    nested = model_dir / MODEL_SUBDIR
    if _ready(nested):
        return nested

    model_dir.mkdir(parents=True, exist_ok=True)
    # snapshot_download keeps the repo-relative path, so the files land in
    # <local_dir>/sensevoice_small_yue/. Point local_dir at the parent when
    # MODEL_DIR already uses that folder name.
    local_dir = model_dir.parent if model_dir.name == MODEL_SUBDIR else model_dir
    local_dir.mkdir(parents=True, exist_ok=True)

    from huggingface_hub import snapshot_download

    print(f"[asr] SenseVoice weights missing under {model_dir}")
    print(f"[asr] Downloading {MODEL_SUBDIR} from https://huggingface.co/{REPO_ID}")
    print("[asr] model.pt is about 2.8GB and may take a while…")
    snapshot_download(
        repo_id=REPO_ID,
        revision="main",
        allow_patterns=[f"{MODEL_SUBDIR}/{name}" for name in REQUIRED_FILES],
        local_dir=str(local_dir),
    )

    if _ready(model_dir):
        print("[asr] Model ready.")
        return model_dir
    if _ready(nested):
        print("[asr] Model ready.")
        return nested
    missing = [name for name in REQUIRED_FILES if not (model_dir / name).is_file()]
    raise SystemExit(
        "Download finished but sensevoice_small_yue files are still missing:\n  "
        + "\n  ".join(str(model_dir / name) for name in missing)
    )


_model_dir = ensure_model(MODEL_DIR)
print(f"[asr] Loading {_model_dir} on {DEVICE}")
# hub=hf plus a local directory: FunASR reads config.yaml here and does not
# call ModelScope. Language stays yue for every request below.
recognizer = AutoModel(
    model=str(_model_dir),
    hub="hf",
    device=DEVICE,
    ncpu=NUM_THREADS,
    disable_update=True,
    disable_pbar=True,
    check_latest=False,
)

app = FastAPI(title="YueCap Spark A — SenseVoice ASR")


def transcript_from_raw(raw: str) -> tuple[str, str]:
    """Return (traditional text, event). Non-speech events become empty text."""
    tags = [tag.strip().lower() for tag in _TAG.findall(raw)]
    event = next((tag for tag in tags if tag in NON_SPEECH_EVENTS or tag == "speech"), "")
    if event in NON_SPEECH_EVENTS:
        return "", event
    text = _TAG.sub("", raw).strip()
    return _to_traditional.convert(text), event


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
    return np.ascontiguousarray(audio, dtype=np.float32), sample_rate


def transcribe(samples: np.ndarray, sample_rate: int) -> tuple[str, str]:
    if samples.size == 0:
        return "", ""
    with _infer_lock:
        result = recognizer.generate(
            input=samples,
            fs=sample_rate,
            language="yue",
            use_itn=True,
            disable_pbar=True,
        )
    if not result:
        return "", ""
    return transcript_from_raw(str(result[0].get("text") or ""))


@app.get("/health")
def health() -> dict:
    return {
        "ok": True,
        "model": MODEL_NAME,
        "language": "yue",
        "script": "hk-traditional",
        "device": DEVICE,
    }


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

    text, event = transcribe(samples, sample_rate)
    if event and not text:
        print(f"[asr] drop non-speech event={event}")
    return JSONResponse(
        {"text": text, "lang": lang, "event": event, "requested_lang": requested}
    )


if __name__ == "__main__":
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
