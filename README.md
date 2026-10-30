<p align="center">
  <img src="docs/screenshots/app-icon.png" width="96" alt="Prime Minutes" />
</p>

<h1 align="center">Prime Minutes</h1>

<p align="center">
  <b>錄下會議，手機自己整理出誰說了什麼、決定了什麼。</b><br>
  逐字稿、AI 筆記與摘要全部在手機上離線完成，不用帳號，不上傳。
</p>

<p align="center">
  <a href="https://github.com/vieenrose/PrimeMinutes/releases/latest"><img alt="Android" src="https://img.shields.io/github/v/release/vieenrose/PrimeMinutes?sort=semver&label=Android&logo=android&logoColor=white&color=3DDC84"></a>
  <a href="https://testflight.apple.com/join/saH9YxEA"><img alt="iOS" src="https://img.shields.io/badge/iOS-TestFlight-000000?logo=apple&logoColor=white"></a>
  <img alt="授權" src="https://img.shields.io/badge/license-GPL--3.0-blue">
  <a href="https://vieenrose.github.io/PrimeMinutes/slides/PrimeMinutes-zh-TW.html"><img alt="簡報" src="https://img.shields.io/badge/簡報-線上預覽-0A7A52"></a>
</p>

<p align="center"><img src="docs/screenshots/demo-studio.gif" width="300" alt="錄音、佇列、摘要原話預覽、合集提問與搜尋"><br>
<sub>邊錄邊記筆記；佇列可暫停再繼續；點摘要裡的時間就看到原話；在合集裡提問、搜遍所有會議（部分片段加速）</sub></p>

## 下載安裝

| | 怎麼裝 | 需求 | 掃碼 |
|---|---|---|:---:|
| **Android** | 到 [Releases](https://github.com/vieenrose/PrimeMinutes/releases/latest) 下載 APK，App 每天會自動檢查新版 | Android 8.0 以上、約 2019 年後的處理器（ARMv8.2）；RAM 8 GB 以上最順 | <img src="docs/screenshots/qr-android.png" width="100" alt="Android 下載 QR code"> |
| **iPhone** | 先裝 TestFlight，再開啟[公開測試連結](https://testflight.apple.com/join/saH9YxEA) | iOS 17.0 以上；RAM 6 GB 以上最順 | <img src="docs/screenshots/qr-ios.png" width="100" alt="iOS TestFlight QR code"> |

Android 安裝時如果被擋：Play 安全防護跳出警告，點「更多詳細資料 → 仍要安裝」；小米／HyperOS 在安全提示中選擇允許即可（[#6](https://github.com/vieenrose/PrimeMinutes/issues/6)）。

第一次開啟會先下載模型（約 2.5 GB 起，可設定只用 Wi‑Fi）。RAM 8 GB 以上的手機會邊錄邊整理筆記，也能改用較完整的 E4B 模型；較小的手機在錄完後才整理。

## 能做什麼

| 邊錄邊出字 | 自動整理筆記 | 摘要點了就聽原話 |
|:---:|:---:|:---:|
| <img src="docs/screenshots/qs-capture.png" width="220" alt="錄音"> | <img src="docs/screenshots/05-agent.png" width="220" alt="AI 筆記"> | <img src="docs/screenshots/04-summary.png" width="220" alt="摘要"> |
| **搜尋和提問** | **認得說話的人** | **一鍵分享** |
| <img src="docs/screenshots/meeting-search-ask.gif" width="220" alt="搜尋與提問"> | <img src="docs/screenshots/people-voice-suggest-zh.png" width="220" alt="辨識聲音建議名字"> | <img src="docs/screenshots/share.gif" width="220" alt="分享"> |

- **邊錄邊出字**：說完話大約兩秒，文字就上螢幕，還標好是誰說的。
- **自動整理筆記**：錄音時就把決議、待辦、提議、還沒定案的事和數字整理出來；停下後自動寫好摘要和標題。
- **摘要點了就聽原話**：每個時間點都能直接播放，對照原本怎麼說；文字、說話者、換人位置都能修正，也能復原。
- **搜尋和提問**：用意思找遍所有會議，直接問問題，回答會附上出處。
- **認得說話的人**：名字用手機聯絡人，跨會議通用；開啟「辨識聲音」後，下次開會會建議名字（選用）。
- **匯入和分享**：匯入音訊、影片、雲端硬碟的檔案或 Podcast；分享成 PDF、Word，或整場會議 `.pmin`，Android 和 iPhone 互相打得開。

## 隱私與離線

- 錄音、逐字稿、筆記和聲音資料都只存在你的手機，所有模型都在手機上跑。
- 只有下載模型、檢查 Podcast 新集數和每天檢查新版會連網；不登入帳號，也不上傳雲端。
- 「辨識聲音」預設關閉，聲音資料不會匯出或備份，隨時可以刪除。幫別人命名前請先取得同意。
- 匯入或分享的內容由你自己負責；Podcast 等線上內容請確認你有權轉錄，從這類來源分享時預設不附音訊。

## 相關連結

- [使用指南](docs/USER_GUIDE.md)：每個畫面怎麼用、運作方式和已知限制
- [產品簡報](https://vieenrose.github.io/PrimeMinutes/slides/PrimeMinutes-zh-TW.html)
- [官網](https://vieenrose.github.io/PrimeMinutes/)
- [討論區](https://github.com/vieenrose/PrimeMinutes/discussions)
- [回報問題](https://github.com/vieenrose/PrimeMinutes/issues)

## 開發者

這個分支是 Android 版（Kotlin、Jetpack Compose）；iPhone 版在 [ios 分支](https://github.com/vieenrose/PrimeMinutes/blob/ios/README.md)。需要 Android Studio、SDK 37 與 NDK 29.0.14206865：

```bash
git clone https://github.com/vieenrose/PrimeMinutes.git && cd PrimeMinutes
git submodule update --init           # 不要加 --recursive
./gradlew :app:assembleDebug          # arm64-v8a；模擬器用 -PprimeminutesAbi=x86_64
./gradlew :app:testDebugUnitTest
scripts/test-on-device.sh             # 裝置上的儀器測試（獨立 app ID，不影響正式版）
```

架構見 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，準確度評測見 [tools/nemo-eval](tools/nemo-eval/README.md)。

## 授權

App 以 [GPL-3.0-or-later](LICENSE) 授權；模型、資料與第三方元件各依其授權，完整清單見 [NOTICE](NOTICE)，App 內在「設定 › 關於 › 開放原始碼授權」。
