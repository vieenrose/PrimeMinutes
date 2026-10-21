<p align="center">
  <img src="docs/screenshots/app-icon.png" width="96" alt="Prime Minutes" />
</p>

<h1 align="center">Prime Minutes for iOS</h1>

<p align="center">
  <b>會議錄音 → 標註語者的逐字稿 → AI 筆記與摘要。<br>全程在 iPhone 上完成，完全離線。</b>
</p>

<p align="center">
  <img alt="平台" src="https://img.shields.io/badge/iOS-17.0%2B-000000?logo=apple&logoColor=white">
  <img alt="授權" src="https://img.shields.io/badge/license-GPL--3.0-blue">
  <a href="https://github.com/vieenrose/PrimeMinutes/releases/download/v0.53.15/PrimeMinutes-slides-v2.1.html"><img alt="簡報" src="https://img.shields.io/badge/簡報-自包含HTML-0A7A52"></a>
</p>

<p align="center"><img src="docs/screenshots/demo-studio.gif" width="300" alt="錄音室：連續錄兩場會議"><br><sub>連續錄兩場會議：錄完第一場按「下一場」立即錄第二場，第一場的 AI 筆記在背景接續整理；停止後兩場都自動完成筆記、摘要與標題；接著瀏覽場次、匯出、在會議內搜尋與提問、寄給與會者，以及設定。錄音 ×24、整理 ×16、導覽 ×2、提問 ×5 加速（畫面右側標示）（iPhone 14 Pro Max，E4B 行動版 v2）。</sub></p>

Prime Minutes for iOS 是 [Android 版](https://github.com/vieenrose/PrimeMinutes/blob/android/README.md) 的原生 SwiftUI 版本：同一套語音引擎、同一個 AI 筆記模型與閱讀協定，功能與介面對齊 Android。

## 特色

- **即時逐字稿與語者**：說話後約兩秒內文字就上螢幕（轉寫延遲可在設定調整，越短越快、越長越準），同時標註是誰說的。
- **AI 筆記與會議紀要**：錄音中就閱讀逐字稿，寫下決議、待辦、提議、未決事項與數字。
- **摘要**：停止後自動完成段落式摘要與標題；每個時間點一下就從原話播放。
- **搜尋與提問**：依語意搜尋整個會議庫（「誰提到預算」）；向會議提問，回答附出處；也可只在這場會議內搜尋與提問。
- **說話者名錄**：名字跨會議共用；可選的「辨識聲音」在之後的會議中建議名字（只是建議，由你確認）。
- **聯絡人與寄給與會者**：從系統聯絡人加入電子郵件，以你自己的郵件 App 寄出摘要與紀要。
- **編輯逐字稿**：修改文字、改派或合併語者，用分界編輯器修正換人的位置，可復原、重做。
- **匯入、匯出與分享**：匯入「檔案」App 的音訊、Podcast 或 PeerTube 影片連結；匯出 Prime Minutes 場次 `.m4a`（與 Android 互通）、PDF、Markdown、純文字、SRT／VTT／LRC 字幕。
- **完全在裝置上**：所有模型都在 iPhone CPU 上執行，無帳號、不上傳。

| 錄音 | AI 筆記 | 摘要 | 逐字稿 |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/qs-capture.png" width="200" alt="錄音"> | <img src="docs/screenshots/17-process.png" width="200" alt="AI 筆記"> | <img src="docs/screenshots/10-summary.png" width="200" alt="摘要"> | <img src="docs/screenshots/11-transcript.png" width="200" alt="逐字稿"> |

## 需求

- iOS 17.0 以上的 arm64 iPhone；驗證機型為 iPhone 14 Pro Max（6 GB）。
- RAM 4.5 GB 以上：AI 筆記在錄音中同步閱讀；RAM 6 GB 以上可選用 E4B；較小的 iPhone 在錄音結束後閱讀，3 GB 機型（如 iPhone XR）無法可靠執行 E2B。
- 模型在第一次使用時下載（先顯示總量，可選「僅限 Wi‑Fi」）：

| 模型 | 用途 | 下載大小 |
|---|---|---|
| X-ASR（含標點）+ Nemotron-3 語者分離 | 語音辨識、語者；設定可選 4 種轉寫延遲（預設 1.92 秒，最準確），每種約 155 MB | 約 273 MB |
| Gemma-4-E2B 會議模型（行動版 v1） | AI 筆記、摘要、提問（預設） | 約 2.2 GB |
| Gemma-4-E4B 會議模型（行動版 v2） | 同上，較完整；限 RAM 6 GB 以上的 iPhone，載入時記憶體不足則該次改用 E2B | 約 3.3 GB |
| EmbeddingGemma 2 270M（選用） | 語意搜尋、提問 | 約 160 MB |
| CAM++ zh-en（選用） | 辨識聲音 | 約 29 MB |

## 安裝

iOS 版目前以 TestFlight 發佈給受邀的測試者，沒有公開連結；版本更新由 TestFlight 負責。也可以從原始碼建置（見下方）。

## 快速開始

1. 開啟 App，選擇「立即下載」或「僅限 Wi‑Fi」下載模型。
2. 按首頁的錄音鈕開始錄音；逐字稿即時出現，AI 筆記卡片顯示閱讀進度。
3. 按 **停止並儲存**（或 **下一場** 接著錄下一場）；摘要、筆記與標題自動完成。
4. 在場次中點時間點核對原話，在逐字稿長按修正，從 **⋯** 匯出或寄給與會者。
5. 也可用首頁 **＋** 匯入「檔案」App 的音訊、Podcast、PeerTube 影片連結或 `.m4a` 場次，或從其他 App 分享音訊過來。

每個畫面與操作的說明見 [**使用說明**](docs/USER_GUIDE.md)。

## 隱私

- 音訊、逐字稿、筆記與聲音資料都只存在這支 iPhone 上；所有推論都在裝置的 CPU 上執行。
- 唯一的網路連線：下載模型、Podcast 與 PeerTube。
- 「辨識聲音」預設關閉；聲音資料不含於匯出與 iCloud／裝置備份，可隨時刪除。為他人命名前請先徵得同意。
- 寄給與會者只是開啟你的郵件 App，App 本身不寄信。

## 文件

- [使用說明](docs/USER_GUIDE.md)：每個畫面、操作、示範影片、與 Android 版的差異、運作方式與已知限制。
- [開發說明](docs/DEVELOPMENT.md)：引擎移植、模擬器與實機驗證。

## 從原始碼建置

需要 macOS 與 Xcode（iOS 26 SDK）、cmake。在 Mac 上：

```bash
bash ios/native/build_app.sh iphoneos arm64        # 原生引擎 + SwiftUI App bundle
# 簽署並安裝：開啟 ios/Xcode/Prime Minutes.xcodeproj 選 Team 與 iPhone 後 Run，或
xcodebuild -project "ios/Xcode/Prime Minutes.xcodeproj" -scheme "Prime Minutes" -configuration Release \
  -destination "id=<裝置>" DEVELOPMENT_TEAM=<ID> -allowProvisioningUpdates SKIP_NATIVE_BUILD=1 build
bash ios/tests/run.sh                               # 閱讀器與 Android 黃金資料比對
python3 tools/uiparity/check.py                     # 介面字串與 Android 的對齊程度
```

`DEV=1` 開啟開發用的 `VOX_*` 環境變數（自動匯入、開啟場次、下載模型等）。

## 授權與致謝

應用程式以 [GPL-3.0-or-later](https://github.com/vieenrose/PrimeMinutes/blob/android/LICENSE) 授權；模型與資料各依其授權：

<table>
  <tr><th>元件</th><th>授權</th><th>相關子專案</th></tr>
  <tr><td>X-ASR（語音辨識）</td><td>Apache-2.0</td><td rowspan="2"><a href="https://github.com/vieenrose/nemo-x-asr-diarizer.cpp">nemo-x-asr-diarizer.cpp</a></td></tr>
  <tr><td>Nemotron-3 Diarization（語者分離）</td><td>OpenMDW-1.1</td></tr>
  <tr><td>Gemma-4-E2B／E4B 會議模型（AI 筆記、摘要）</td><td>Apache-2.0</td><td><a href="https://github.com/vieenrose/meeting-summarizer">meeting-summarizer</a></td></tr>
  <tr><td>EmbeddingGemma 2 270M（語意搜尋）</td><td><a href="https://ai.google.dev/gemma/terms">Gemma</a></td><td><a href="https://huggingface.co/Luigi/primeminutes-embed-litert">Luigi/primeminutes-embed-litert</a></td></tr>
  <tr><td>CAM++ zh-en（3D-Speaker，辨識聲音）</td><td>Apache-2.0</td><td><a href="https://huggingface.co/Luigi/primeminutes-speaker-litert">Luigi/primeminutes-speaker-litert</a></td></tr>
  <tr><td>示範會議音訊（以 VibeVoice-1.5B 合成）</td><td>MIT</td><td><a href="https://github.com/vieenrose/PrimeMinutes/tree/android/docs/demo">docs/demo（Android 分支）</a></td></tr>
</table>

推論引擎：[LiteRT](https://github.com/google-ai-edge/LiteRT)（[自訂 LiteRT-LM](https://github.com/vieenrose/LiteRT-LM/tree/mobile-fused-attention)，`CLiteRTLM.xcframework`）。評測資料集（AISHELL-4、AMI）見 [Android 版 README](https://github.com/vieenrose/PrimeMinutes/blob/android/README.md#授權與致謝)。
