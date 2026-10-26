# YueCap Android

## Spark A：最小 HTTP ASR

Pad 上的 `RemoteAsrClient` 把麥克風切成 16 kHz 單聲道 WAV，送到區網上的 Spark A。Spark A 跑 SenseVoice，主語言是粵語（`yue`）。句中的英文詞要原樣留在 `text` 裡，不要翻譯、不要刪掉拉丁字母。

App 把位址存在 `asr_base_url`（`AppPreferences.asrBaseUrl`）。兩種寫法都可以：

- `http://192.168.1.20:8000`
- `http://192.168.1.20:8000/v1/asr`

Client 只接受 `http` 或 `https`。路徑若不是以 `/v1/asr` 結尾，會自動補上，並加上 `language=yue`。空白網址視為未設定。`asr_mode` 預設是 `REMOTE_SPARK_A`：協調器把麥克風分段送到這個端點。設成 `LOCAL_SHERPA` 才改走本機 SenseVoice。

### 請求

```http
POST /v1/asr?language=yue
Content-Type: audio/wav
```

Body 是一整段 WAV，不是 multipart：

| 欄位 | 值 |
| --- | --- |
| 取樣率 | 16000 Hz |
| 聲道 | 單聲道 |
| 取樣格式 | 16-bit PCM，little-endian |
| 標頭 | 標準 44-byte `RIFF`/`WAVE` |
| 振幅 | float 已夾在 `[-1, 1]` 再轉成 int16 |

`language=yue` 表示這段話以粵語為主。它不是過濾器：聽到的英文單字、縮寫、產品名仍要寫進 `text`。

Client 逾時：連線 5 秒、送出 15 秒、等待回應 20 秒。服務請聽 `0.0.0.0`，並在防火牆開放同一個 LAN port。

### 回應

成功時 HTTP 200，JSON 物件至少要有字串欄位 `text`。`lang` 可有可無，有的話用 `yue`。

```json
{"text":"我哋下個 sprint 用 API","lang":"yue"}
```

`text` 空白表示這段沒有語音，App 把它當成空結果。沒有 `text` 欄位，或 HTTP 不是 2xx，App 把這一段當成失敗。不要在伺服器端刪掉 `[A-Za-z0-9]`。

### 用 curl 檢查

把下面的位址換成 Spark A 的 IP。`sample.wav` 必須是 16 kHz 單聲道 PCM WAV。

```bash
curl -sS -D- \
  -H 'Content-Type: audio/wav' \
  --data-binary @sample.wav \
  'http://192.168.1.20:8000/v1/asr?language=yue'
```

期望看到 HTTP 200，以及含英文詞的 `text`，例如 `我哋下個 sprint 用 API`。
