# YueCap 管線

YueCap 是會議字幕 App，包名 `com.csc.yuecap`。講者主要說粵語，句中偶爾有英文詞。Pad 只負責收音和顯示。辨識在 Spark A，翻譯在 Spark B，兩者都在同一區網，不經公開雲端。

```text
Pad 麥克風
  → 16 kHz 單聲道 PCM
  → Spark A  SenseVoice（language=yue）
  → 粵語原文（保留英文詞）
  → Spark B  OpenAI 相容 chat（自架，不是 OpenAI 雲）
  → 大字英文字幕
```

Android 程式在 `VoicePingIOSAndroidOfflineSpeechTranslationAndroid/`。根目錄的 `LICENSE` 與 `NOTICE` 保留上游聲明；YueCap 的版權行加在 `NOTICE` 開頭。

## Pad

收音鎖在麥克風。系統播放音源不會出現在畫面上，程式也會忽略切換。

預設語言是粵語 `yue` 譯成英文 `en`。目標語鎖定英文。辨識到句中的英文不會把方向對調成英譯粵。

`TranscriptionCoordinator` 把麥克風緩衝切成片段。`asr_mode` 預設 `REMOTE_SPARK_A`：

| 模式 | 行為 |
| --- | --- |
| `REMOTE_SPARK_A` | `RemoteAsrClient` 把片段編成 WAV，送到 Spark A。不下載本機模型。 |
| `LOCAL_SHERPA` | 本機 sherpa-onnx SenseVoice，模型語言固定 `yue`。 |

一段確定文字寫入之後，`scheduleTranslationUpdate()` 只把新增的原文送去翻譯，英文接在已顯示的字幕後面。翻譯提供者預設 `REMOTE_SPARK_B`。逾時、HTTP 錯誤或空白回覆會跳過該段並顯示警告，下一句仍會再送。可改回 ML Kit 或 Android 系統翻譯；這兩條離線路徑沒有粵語模型，`yue` 會改用中文 `zh` 模型，拉丁字母仍留在原文裡。

主畫面是深藍底、64sp 粗體英文。粵語原文預設收起。狀態列為 Ready、Listening、Recognizing、Translating，失敗為紅色 Error。

## Spark A

SenseVoice HTTP。Pad 接受 `http://<ip>:<port>` 或已經帶 `/v1/asr` 的位址，只允許 `http` 與 `https`。

```http
POST /v1/asr?language=yue
Content-Type: audio/wav
```

WAV 是 16 kHz、單聲道、16-bit PCM。`language=yue` 表示以粵語為主，不是拿來刪掉英文的過濾器。成功時 HTTP 200：

```json
{"text":"我哋下個 sprint 用 API","lang":"yue"}
```

`text` 裡的英文詞必須原樣留下。空白 `text` 表示這段沒有語音。沒有 `text`，或狀態碼不是 2xx，這一段視為失敗。連線 5 秒、送出 15 秒、等待 20 秒。

## Spark B

區網上的 vLLM、Ollama 或 llama.cpp。`POST /v1/chat/completions` 只用 OpenAI Chat Completions 的請求形狀，不呼叫 OpenAI 雲，也不需要雲端 API key。LAN 上的 key 可留空；有值才送 `Authorization: Bearer`。模型名空白時，請求裡的 `model` 用 `local`。

系統提示要求譯成簡短英文，並保留原文已有的英文詞、縮寫和產品名。`temperature` 為 0，`max_tokens` 為 256。連線 5 秒、送出 10 秒、等待 20 秒、整次呼叫 25 秒，不重試。

## 設定

設定頁寫入 `AppPreferences`：

| 欄位 | 用途 |
| --- | --- |
| `asr_base_url` | Spark A |
| `asr_mode` | `REMOTE_SPARK_A` 或 `LOCAL_SHERPA` |
| `llm_base_url` | Spark B |
| `llm_model` | Spark B 的模型名 |
| `llm_api_key` | 可空白 |

Test Spark A 送 1 秒靜音 WAV。Test Spark B 送「測試 API」做粵譯英。HTTP 成功即視為連上；空白回覆會標成已連上但沒有文字。關掉設定頁時一併儲存。App 允許區網明文 HTTP。

## 還沒做

Pad 與兩台 Spark 的實機端到端還沒跑。本機目前沒有接上的裝置，也還沒用講者的粵英混雜句核對字幕。
