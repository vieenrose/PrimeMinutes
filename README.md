<p align="center">
  <img src="docs/screenshots/app-icon.png" width="96" alt="Prime Minutes" />
</p>

<h1 align="center">Prime Minutes for Android</h1>

<p align="center">
  <b>會議錄音 → 標註語者的逐字稿 → AI 筆記與摘要。<br>全程在手機上完成，完全離線。</b>
</p>

<p align="center">
  <a href="https://github.com/vieenrose/PrimeMinutes/releases/latest"><img alt="版本" src="https://img.shields.io/github/v/release/vieenrose/PrimeMinutes?sort=semver"></a>
  <img alt="平台" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="授權" src="https://img.shields.io/badge/license-GPL--3.0-blue">
</p>

<p align="center"><img src="docs/screenshots/demo-studio.gif" width="300" alt="錄音室：連續錄兩場會議"><br><sub>連續錄兩場會議：錄完第一場按「下一場」立即錄第二場，第一場的 AI 筆記在背景接續整理；停止後兩場都自動完成筆記、摘要與標題；接著瀏覽場次、在會議內搜尋與提問、寄給與會者，以及設定中的轉寫延遲與 E4B。第一次閱讀前，AI 筆記卡片預告還要幾分鐘，提問的回答指出負責的語者，寄給與會者的郵件列出會議時間。錄音段 ×4、整理段 ×16、提問等待段 ×4 加速（畫面右側標示），其餘即時；在 Galaxy Note10+ 上實機錄製（E4B 行動版 v2）。</sub></p>

## 特色

- **即時逐字稿與語者**：說話後約兩秒內文字就上螢幕（轉寫延遲可在設定調整，越短越快、越長越準），同時標註是誰說的。
- **AI 筆記與會議紀要**：錄音中就閱讀逐字稿，寫下決議、待辦、提議、未決事項與數字。
- **摘要**：停止後自動完成段落式摘要與標題；每個時間點一下就從原話播放。
- **搜尋與提問**：依語意搜尋整個會議庫（「誰提到預算」）；向會議提問，回答附出處；也可只在這場會議內搜尋與提問。
- **說話者名錄**：名字跨會議共用；可選的「辨識聲音」在之後的會議中建議名字（只是建議，由你確認）。
- **聯絡人與寄給與會者**：從系統聯絡人加入電子郵件，以你自己的郵件 App 寄出摘要與紀要。
- **編輯逐字稿**：修改文字、改派或合併語者，用分界編輯器修正換人的位置，可復原、重做。
- **匯入、匯出與分享**：匯入音訊檔、Podcast、YouTube、PeerTube；匯出 Prime Minutes 場次 `.m4a`、PDF、Markdown、純文字、SRT／VTT／LRC 字幕。
- **完全在裝置上**：所有模型都在手機 CPU 上執行，無帳號、不上傳。

| 錄音 | AI 筆記 | 摘要 | 逐字稿 |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/qs-capture.png" width="200" alt="錄音"> | <img src="docs/screenshots/05-agent.png" width="200" alt="AI 筆記"> | <img src="docs/screenshots/04-summary.png" width="200" alt="摘要"> | <img src="docs/screenshots/03-transcript.png" width="200" alt="逐字稿"> |

## 需求

- Android 8.0 以上，ARMv8.2（dotprod）處理器（約 2019 年後的手機）。
- RAM 8 GB 以上：AI 筆記在錄音中同步閱讀，並可選用 E4B；較小的手機在錄音結束後閱讀。
- 模型在第一次使用時下載（先顯示總量，可選「僅限 Wi‑Fi」）：

| 模型 | 用途 | 下載大小 |
|---|---|---|
| X-ASR（含標點）+ Nemotron-3 語者分離 | 語音辨識、語者；設定可選 4 種轉寫延遲（預設 1.92 秒，最準確），每種約 155 MB | 約 273 MB |
| Gemma-4-E2B 會議模型（行動版 v1） | AI 筆記、摘要、提問（預設） | 約 2.2 GB |
| Gemma-4-E4B 會議模型（行動版 v2） | 同上，較完整；限 RAM 8 GB 以上 | 約 3.3 GB |
| EmbeddingGemma 2 270M（選用） | 語意搜尋、提問 | 約 160 MB |
| CAM++ zh-en（選用） | 辨識聲音 | 約 29 MB |

## 安裝

從 [**Releases**](https://github.com/vieenrose/PrimeMinutes/releases/latest) 下載最新的 APK 並安裝。App 每天檢查一次新版本，有新版時在首頁提示。

## 快速開始

1. 開啟 App，選擇「立即下載」或「僅限 Wi‑Fi」下載模型。
2. 按首頁的錄音鈕開始錄音；逐字稿即時出現，AI 筆記卡片顯示閱讀進度。
3. 按 **停止並儲存**（或 **下一場** 接著錄下一場）；摘要、筆記與標題自動完成。
4. 在場次中點時間點核對原話，在逐字稿長按修正，從 **⋮** 匯出或寄給與會者。
5. 也可用首頁 **＋** 匯入音訊檔、Podcast、YouTube 或 PeerTube。

每個畫面與操作的說明見 [**使用說明**](docs/USER_GUIDE.md)。

## 隱私

- 音訊、逐字稿、筆記與聲音資料都只存在這支手機上；所有推論都在裝置的 CPU 上執行。
- 唯一的網路連線：下載模型、Podcast／YouTube／PeerTube，以及每天檢查一次新版本。
- 「辨識聲音」預設關閉；聲音資料不含於匯出與 Android 備份，可隨時刪除。為他人命名前請先徵得同意。
- 寄給與會者只是開啟你的郵件 App，App 本身不寄信。

## 文件

- [使用說明](docs/USER_GUIDE.md)：每個畫面、操作、示範影片、運作方式與已知限制。
- [架構](docs/ARCHITECTURE.md)：模組對應。
- [準確度評測](tools/nemo-eval/README.md)。

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

應用程式以 [GPL-3.0-or-later](LICENSE) 授權；模型與資料各依其授權：

<table>
  <tr><th>元件</th><th>授權</th><th>相關子專案</th></tr>
  <tr><td>X-ASR（語音辨識）</td><td>Apache-2.0</td><td rowspan="2"><a href="https://github.com/vieenrose/nemo-x-asr-diarizer.cpp">nemo-x-asr-diarizer.cpp</a><br>（語音辨識與語者分離整合為單一串流引擎）</td></tr>
  <tr><td>Nemotron-3 Diarization（語者分離）</td><td>OpenMDW-1.1</td></tr>
  <tr><td>Gemma-4-E2B／E4B 會議模型（AI 筆記、摘要）</td><td>Apache-2.0</td><td><a href="https://github.com/vieenrose/meeting-summarizer">meeting-summarizer</a><br>（閱讀代理的訓練與協定）</td></tr>
  <tr><td>EmbeddingGemma 2 270M（語意搜尋）</td><td><a href="https://ai.google.dev/gemma/terms">Gemma</a></td><td><a href="tools/embed-export">tools/embed-export</a><br>（LiteRT 重新封裝）</td></tr>
  <tr><td>CAM++ zh-en（3D-Speaker，辨識聲音）</td><td>Apache-2.0</td><td><a href="https://huggingface.co/Luigi/primeminutes-speaker-litert">Luigi/primeminutes-speaker-litert</a></td></tr>
  <tr><td>示範會議音訊（以 VibeVoice-1.5B 合成）</td><td>MIT</td><td><a href="docs/demo">docs/demo</a></td></tr>
  <tr><td>AISHELL-4（語者歸屬正確率評測）</td><td>CC BY-SA 4.0</td><td rowspan="2"><a href="tools/nemo-eval/README.md">tools/nemo-eval</a></td></tr>
  <tr><td>AMI（語者歸屬正確率評測）</td><td>CC BY 4.0</td></tr>
</table>

推論引擎：[LiteRT](https://github.com/google-ai-edge/LiteRT)（[自訂 LiteRT-LM](https://github.com/vieenrose/LiteRT-LM/tree/mobile-fused-attention)）。
