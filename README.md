<p align="center">
  <img src="docs/screenshots/app-icon.png" width="96" alt="Prime Minutes" />
</p>

<h1 align="center">Prime Minutes</h1>

<p align="center">
  <b>會議錄音 → 標註語者的逐字稿 → AI 筆記與摘要</b><br>
  全程在手機上離線完成，無帳號、不上傳。
</p>

<p align="center">
  <a href="https://github.com/vieenrose/PrimeMinutes/releases/latest"><img alt="Android" src="https://img.shields.io/github/v/release/vieenrose/PrimeMinutes?sort=semver&label=Android&logo=android&logoColor=white&color=3DDC84"></a>
  <img alt="iOS" src="https://img.shields.io/badge/iOS-17.0%2B-000000?logo=apple&logoColor=white">
  <img alt="授權" src="https://img.shields.io/badge/license-GPL--3.0-blue">
  <a href="https://vieenrose.github.io/PrimeMinutes/slides/PrimeMinutes-zh-TW.html"><img alt="簡報" src="https://img.shields.io/badge/簡報-線上預覽-0A7A52"></a>
</p>

<p align="center"><img src="docs/screenshots/demo-studio.gif" width="300" alt="連續錄兩場會議"><br>
<sub>連續錄兩場會議，停止後自動完成筆記、摘要與標題（Galaxy Note10+，E4B；部分片段加速）</sub></p>

## 下載

| 平台 | 取得方式 | 需求 | 掃碼 |
|---|---|---|:---:|
| **Android** | 從 [Releases](https://github.com/vieenrose/PrimeMinutes/releases/latest) 下載 APK；App 每天檢查新版 | Android 8.0+，RAM 8 GB 以上最佳 | <img src="docs/screenshots/qr-android.png" width="110" alt="Android 下載 QR code"> |
| **iOS** | [TestFlight 公開測試](https://testflight.apple.com/join/saH9YxEA)：先安裝 TestFlight App，再點連結加入 | iOS 17.0+，RAM 6 GB 以上最佳 | <img src="docs/screenshots/qr-ios.png" width="110" alt="iOS TestFlight QR code"> |

這個分支是 Android 版（Kotlin、Jetpack Compose）；iOS 版的程式碼與說明在 [ios 分支](https://github.com/vieenrose/PrimeMinutes/blob/ios/README.md)。兩個版本共用同一套語音引擎與 AI 模型，功能一致。

## 特色

- **即時逐字稿**：說話後約兩秒文字就上螢幕，同時標註是誰說的。
- **AI 筆記**：錄音中就整理決議、待辦、提議、未決事項與數字。
- **摘要**：停止後自動完成摘要與標題，點時間點就從原話播放。
- **搜尋與提問**：依語意搜尋整個會議庫；向會議提問，回答附出處。
- **說話者名錄**：人就是手機的聯絡人，名字跨會議共用、電子郵件存在聯絡人中；「辨識聲音」可在之後的會議建議名字（選用）。
- **編輯**：修改文字、改派或合併語者、修正換人位置，可復原。
- **匯入與匯出**：匯入音訊檔、Podcast（單集或訂閱節目）、YouTube、PeerTube；匯出 PDF、Markdown、純文字、字幕，或寄給與會者。

| 錄音 | AI 筆記 | 摘要 | 逐字稿 |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/qs-capture.png" width="200" alt="錄音"> | <img src="docs/screenshots/05-agent.png" width="200" alt="AI 筆記"> | <img src="docs/screenshots/04-summary.png" width="200" alt="摘要"> | <img src="docs/screenshots/03-transcript.png" width="200" alt="逐字稿"> |

## 快速開始

1. 開啟 App，下載模型（可選「僅限 Wi‑Fi」）。
2. 按錄音鈕開始；逐字稿即時出現。
3. 按 **停止並儲存**（或 **下一場** 接著錄），摘要、筆記與標題自動完成。
4. 點時間點核對原話，長按逐字稿修正，從 **⋮** 匯出或寄給與會者。

每個畫面的說明見 [使用說明](docs/USER_GUIDE.md)。

## 隱私

- 音訊、逐字稿、筆記與聲音資料只存在你的手機上，所有模型都在手機 CPU 上執行。
- 只有下載模型、Podcast（含你按下的節目檢查）、YouTube、PeerTube 與每天檢查新版會連網。
- 「辨識聲音」預設關閉，聲音資料不會匯出或備份，可隨時刪除。為他人命名前請先徵得同意。
- 寄給與會者只是開啟你的郵件 App，App 本身不寄信。

## 需求與模型

- Android：8.0 以上，ARMv8.2（dotprod）處理器（約 2019 年後的手機）。RAM 8 GB 以上在錄音中同步整理筆記，並可選用 E4B；較小的手機在錄音結束後整理。
- 模型在第一次使用時下載，先顯示總量：

| 模型 | 用途 | 大小 |
|---|---|---|
| X-ASR + Nemotron-3 語者分離 | 語音辨識、語者 | 約 273 MB |
| Gemma-4-E2B 會議模型 | AI 筆記、摘要、提問（預設） | 約 2.2 GB |
| Gemma-4-E4B 會議模型 | 同上，較完整（RAM 8 GB 以上） | 約 3.3 GB |
| EmbeddingGemma 2 270M（選用） | 語意搜尋、提問 | 約 160 MB |
| CAM++ zh-en（選用） | 辨識聲音 | 約 29 MB |

## 文件

- [使用說明](docs/USER_GUIDE.md)：每個畫面與操作、示範影片、運作方式與已知限制。
- [架構](docs/ARCHITECTURE.md)：模組對應。
- [準確度評測](tools/nemo-eval/README.md)：語音辨識與語者歸屬。
- [產品簡報](https://vieenrose.github.io/PrimeMinutes/slides/PrimeMinutes-zh-TW.html)：在瀏覽器中直接開啟。

## 從原始碼建置

需要 Android Studio、SDK 35、NDK 27.2：

```bash
git clone https://github.com/vieenrose/PrimeMinutes.git && cd PrimeMinutes
git submodule update --init           # 不要加 --recursive
./gradlew :app:assembleDebug          # arm64-v8a；模擬器用 -PprimeminutesAbi=x86_64
./gradlew :app:testDebugUnitTest
scripts/test-on-device.sh             # 裝置上的儀器測試（獨立 app ID，不影響正式版）
```

## 授權與致謝

App 以 [GPL-3.0-or-later](LICENSE) 授權；模型與資料各依其授權：

| 元件 | 授權 | 相關專案 |
|---|---|---|
| X-ASR（語音辨識） | Apache-2.0 | [nemo-x-asr-diarizer.cpp](https://github.com/vieenrose/nemo-x-asr-diarizer.cpp) |
| Nemotron-3 Diarization（語者分離） | OpenMDW-1.1 | [nemo-x-asr-diarizer.cpp](https://github.com/vieenrose/nemo-x-asr-diarizer.cpp) |
| Gemma-4-E2B／E4B 會議模型 | Apache-2.0 | [meeting-summarizer](https://github.com/vieenrose/meeting-summarizer) |
| EmbeddingGemma 2 270M | [Gemma](https://ai.google.dev/gemma/terms) | [tools/embed-export](tools/embed-export) |
| CAM++ zh-en（3D-Speaker） | Apache-2.0 | [Luigi/primeminutes-speaker-litert](https://huggingface.co/Luigi/primeminutes-speaker-litert) |
| 示範會議音訊（VibeVoice-1.5B 合成） | MIT | [docs/demo](docs/demo) |
| AISHELL-4（語者歸屬評測） | CC BY-SA 4.0 | [tools/nemo-eval](tools/nemo-eval/README.md) |
| AMI（語者歸屬評測） | CC BY 4.0 | [tools/nemo-eval](tools/nemo-eval/README.md) |

推論引擎：[LiteRT](https://github.com/google-ai-edge/LiteRT)（[自訂 LiteRT-LM](https://github.com/vieenrose/LiteRT-LM/tree/mobile-fused-attention)）。
