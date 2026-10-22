# Prime Minutes iOS：啟用 TestFlight

## 一、在 App Store Connect 建立 App（只需做一次）

1. 以團隊帳號（Team ID `QGKL6RDGM3`）登入 <https://appstoreconnect.apple.com>。
2. 進入「我的 App」，點左上角「＋」，選「新增 App」。
3. 依下表填寫：

   | 欄位 | 填寫 |
   |---|---|
   | 平台 | iOS |
   | 名稱 | Prime Minutes（名稱已被佔用時，可改用「Prime Minutes 會議記錄」） |
   | 主要語言 | 繁體中文 |
   | 套件識別碼（Bundle ID） | `tw.com.pesi.primeminutes` |
   | SKU | 自訂，例如 `primeminutes-ios` |
   | 使用者存取權限 | 完整存取權限 |

4. 按「建立」。

## 二、上傳建置版本（在 Mac 上操作）

1. 打開 Xcode，選選單 **Window → Organizer**。
2. 在「Archives」分頁選最新的 **PrimeMinutes** 封存。可能有兩筆同名的封存（例如兩筆都叫 0.53.0 (11)），這時以日期時間為準，選最新的那筆。
3. 點右側「**Distribute App**」，選「**App Store Connect**」，再選「**Upload**」。
4. 簽署選「**Automatically manage signing**」。Xcode 會用雲端憑證自動完成簽署。
5. 確認摘要後按「Upload」，等待「Upload Successful」。

## 三、在 TestFlight 啟用測試

1. 回到 App Store Connect，進入 Prime Minutes 的「**TestFlight**」分頁。
2. 建置版本通常 5–30 分鐘後出現，狀態為「處理中」，處理完成後才能使用。
3. 不需要回答「出口合規」（加密）問題：Info.plist 已聲明只使用 HTTPS 標準加密。
4. 填寫「測試資訊」：
   - 意見回饋電子郵件
   - 測試說明，例如：「請錄製或匯入一場會議，確認逐字稿、說話者與摘要；iOS 26 以上可離開 App 讓其在背景處理。」
5. 加入測試人員：
   - **內部測試**（最快，不需審查）：在「內部測試」建立群組，加入團隊成員（須先在「使用者與存取權限」中有帳號，最多 100 人）。建置版本處理完成後，成員就會收到通知。
   - **外部測試**（給團隊以外的人）：在「外部測試」建立群組並加入建置版本。首次需要通過「Beta 版 App 審查」，通常 1 天內完成。通過後可用電子郵件邀請，或開啟「公開連結」分享。
6. 測試人員在 iPhone 安裝「**TestFlight**」App，接受邀請後即可安裝 Prime Minutes。

## 四、開啟公開連結（讓任何 iPhone 用戶掃碼加入）

公開連結不需要事先知道對方的電子郵件：對方掃 QR code 或點連結，就能在 TestFlight 裡加入測試。

1. 在「TestFlight」分頁的「外部測試」旁點「＋」，建立群組，例如「公開測試」。
2. 在群組裡點「建置版本」，加入最新版（例如 0.53.0 (11)）。
3. 依頁面要求填寫測試資訊（測試說明、意見回饋電子郵件等），送出「Beta 版 App 審查」。
   - 第一次通常 1 天內審完。之後同一群組加入新版本，多半不用再等。
4. 審查通過後，在群組頁找到「公開連結」，按「啟用公開連結」。
   - 可以設定人數上限（最多 10,000 人），也可以隨時停用。
5. 複製連結，格式是 `https://testflight.apple.com/join/` 加一串代碼，交給 luigi 做成 QR code。
   - 這個連結會一直有效，之後上傳新版本也不用換 QR code。
6. 目前的公開連結：<https://testflight.apple.com/join/saH9YxEA>（2026-10-22 開通，QR code 在 README 與簡報封面）。

## 五、注意事項

- 每次上傳新版本都必須提高建置號碼（`ios/App/Info.plist` 的 `CFBundleVersion`），例如 `11` 的下一版是 `12`。同一個號碼上傳過一次，就不能再用。
- TestFlight 建置版本有效期為 **90 天**。
- 裝置需求：
  - 建議使用 6 GB 以上記憶體的 iPhone，例如 iPhone 14 Pro Max；iPhone XR（3 GB）無法執行摘要模型。
  - 背景處理需要 iOS 26 以上。
- 選用（讓之後可以自動上傳）：在「使用者與存取權限 → 整合 → App Store Connect API」建立金鑰，角色選「App 管理」。
  - 下載 `.p8` 檔並放到 Mac 上。
  - 提供「金鑰 ID」與「Issuer ID」，之後就能用 `~/work/testflight.sh` 一個指令完成上傳，不必打開 Xcode。
