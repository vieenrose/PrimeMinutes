# Podcast 系列訂閱（節目訂閱）

Status: 定案計畫 2026-10-20；**2026-10-29 依 origin/android（52292c6b，v0.53.18 / versionCode 199）與 origin/ios（5cde2550，build 13）重新核對**（行號、UI 模式、背景下載前提）。採納評審勝出方案 **C（v1 手動檢查）**，並移植 A/B 的可用構想。
範圍：Android（v0.53.18 → 目標 v0.54.0，versionCode 200+）+ iOS（build 13 → build 14+）。
核心原則：**訂閱＝儲存節目連結**；取得新集數後走的還是既有單集匯入管線（下載 → 佇列 → 轉錄 → 筆記/摘要），管線一支指標碼都不動。

用語沿用 README/使用者指南詞彙：**節目**（series）、**單集**（episode）、**集**（計數）、
**場次**（session）、**匯入**、**轉錄**、**僅限 Wi‑Fi**；Podcast 維持拉丁字。
指南需加註「**訂閱＝儲存節目連結，不付費、無背景連線**」，避免與行銷語「無需訂閱」互相解讀。

## 目標與非目標

**目標（v1）**

1. 一次訂閱、永久保存：儲存節目連結（RSS `feedUrl` 即身分），之後随时可檢查新集數；feed **永久搬家（301/308）自動追隨並換鍵**（見取得策略），訂閱不會卡死在舊位址。
2. 新集數差比：以 `<guid>` 為主的身分鏈，比出「新集」清單；一集tap即入庫，行為與今日「單集 → 轉錄」完全相同。
3. 首页/場次/佇列畫面零改動：沿用全部 `dl_*` 字串（下載中… 45 % · 12 MB / 30 MB、下載已暫停 · 會自動繼續、繼續、下載失敗：原因、重試、刪除、已下載 · 點按開始處理）。
4. 修掉已驗證的潜在臭蟲：Android 匯入下載遇磁碟全滿目前會被 `ImportDownloads.transient()`（ImportDownloads.kt:115，`IOException && !FileNotFoundException`）歸類為暫時錯誤 → 無限暫停重試（TranscriptionService.kt:771）。本計畫把它改為明確的「**空間不足 · 等待空間**」暫停狀態（見匯入管線整合）。
5. 順手收攏既有 parity 缺口：`country=us` 寫死（Podcast.kt:44）改為記錄找到節目時的國家；iOS `Podcast.swift` 已解析 `episodeCount` 卻从不顯示 → 在節目頁顯示；補上 iOS 缺的 24 小時 podcast `.part` 清理（Android 已有 `ResumableDownload.sweepStaleParts`，ResumableDownload.kt:60；iOS 2026-10-29 仍只有模型端的 `PartPlan`，audio 目錄的 `podcast_*.part` 無人清理）。
6. 訂閱管理基本盤：每訂閱**暫停／繼續**開關；節目清單與節目頁可**分享節目連結**；Podcast 搜尋面板提供「**貼上 RSS 連結**」直接訂閱（iTunes 搜尋不再是唯一門）。**接收其他 App 分享的 feed 連結延到 M2**（2026-10-29 核對：Android manifest 只收 `audio/*`/`video/*` 的 SEND/VIEW，iOS 沒有 Share Extension、`onOpenURL` 只收檔案——兩平台都要新增入口，iOS 還要新 target，不屬 v1）。

**非目標（v1 明確不做）**

* **背景自動下載、背景排程**：不引入 WorkManager、不註冊 BGAppRefreshTask、不設計時器。2026-10-29 更新：iOS **已有** background URLSession（ec45b1ae，`BackgroundDownloader`，ios/App/BackgroundDownload.swift），但**只用於模型**（ModelStore.swift:205-214）；podcast 單集仍走前景 `Chunked`（ModelStore.swift:252，Podcast.swift:49），且 `BackgroundWork.schedule()` 仍刻意略過只有下載項目的佇列（BackgroundWork.swift:71）。v1 **不把單集搬到 background session**（會改動既有匯入管線，違反「管線不動」原則）；這個非目標的理由由「沒有 background session」改為「單集刻意不上 background session」。把 enclosure 接上 `BackgroundDownloader` 列為 M3 的前置選項（屆時 `PartPlan.merge` 可直接沿用）。
* **自動刪除場次**：保留集數預設 **全部**；今日兩平台的庫刪除都是使用者明確動作，v1 不改。
* **配額／上限強制**：只做事前空間檢查與暫停，不做 LRU  eviction。
* **iTunes lookup endpoint（directory-first）**：Apple 節目目錄對自架 feed 有延遲、且無 API key 的 endpoint 會成批 403；免費的 RSS GET + ETag 已夠便宜，不養第二套探索引擎。
* **OPML 訂閱清單匯出／匯入**：v1 不做。匯出容易，匯入要與既有訂閱去重合併（`feedUrl` 換鍵後以 fnv1a 雜湊合併、套「首次訂閱基準」），不在 M1 時間盒裡；列為 M2（格式採 OPML 2.0 `<outline type="rss" xmlUrl>` 子集）。v1 的對等能力由「分享節目連結／貼上 RSS 連結」提供（見 UI）。
* **通知**：iOS `Notifier` 維持僅「場次已準備好／佇列項目失敗」；Android 下載通知沿用既有。

`auto` 旗標在資料模型中**預留但不顯示**，供 v2（里程碑 M3）以「搭現成唤醒來源」的方式啟用。每訂閱的**暫停**（`paused`）則相反：它是 v1 就顯示的開關，管「要不要檢查新集數」；`auto` 管「要不要自動下載」——兩者互不關聯，v1 的 `auto` 恆為 false 且不顯示。

## 使用者流程（訂閱→取得→入庫，含「僅限 Wi‑Fi」與儲存上限）

```
＋ → Podcast → 搜尋 → 節目結果（artwork + 節目名稱 + 出演者 · N 集）
      搜尋面板「貼上 RSS 連結」→ 節目預覽 → 「訂閱此節目」（接收他 App 分享：M2）
      ├─ 點列身   → 節目頁（同今日單集清單，加「訂閱此節目」勾選）
      └─ 「訂閱此節目」→ 寫入訂閱記錄（離線、不抓任何位元組）；「轉錄」照舊

＋ → 節目（新列）→ 節目清單
      「全部檢查」（序列化、可取消；暫停中的訂閱跳過）
      點節目 → 節目頁
          標頭：artwork、節目名稱、出演者、N 集
          「檢查新集數」→ 檢查中…
          單集清單（最新在前）：標題 · 長度 · 發表日 + 狀態 chip
              新集 → 「下載並轉錄」
              下載中… 45 % · 12 MB / 30 MB
              下載已暫停 · 會自動繼續（繼續）
              空間不足 · 等待空間
              下載失敗：原因（重試／刪除）
              已加入佇列 · 處理中
              已匯入 → 開場次
          頂欄選單（Android TopAppBar overflow／iOS toolbar Menu）：暫停訂閱／繼續訂閱 · 分享節目連結 · 刪除訂閱
          副標：上次檢查：10月20日上午 8:30
                （刪除＝可復原：Android snackbar「已刪除訂閱 · 復原」、iOS 確認 alert，訊息「只移除訂閱，已下載的場次保留。」——沿用兩平台 Lot 1–3 的刪除模式）

新集「下載並轉錄」＝ ImportDownloads.submit(...)（Android）/ Imports.addEpisode(...)（iOS）
  → 首頁立刻出現這一列（標題即單集名稱，釘住不讓摘要改寫）
  → 單一直線佇列、下載完成自動轉錄，全部與今日單集匯入相同
```

* **僅限 Wi‑Fi**：v1 的手動「下載並轉錄」與今日單集匯入相同——媒體下載從來不受 Wi‑Fi 閘門限制（僅限 Wi‑Fi 同意只約束模型下載）。訂閱清單提供**每集「立即下載」**覆寫按鈕（移植 B），v2 自動路徑則沿用既有 `dlConsent`（`DownloadPlan`/`ModelPrefetch.unmetered`、iOS `ModelDownloads.unmetered`），且**不弱化模型閘門的語意**。
* **儲存上限（事前檢查）**：自動下載啟動前先估容积：enclosure 的 length 屬性（RSS `<enclosure length=>`；Android 今日無 HEAD，改用此屬性）× 3（來源檔 + 16 kHz 單聲道 WAV 約 115 MB/小時）+ 200 MB 底（移植 B 的 HEAD×3+200 MB 公式）；iOS 已做 HEAD（Podcast.swift:40-41），沿用。可用空間不足 → **不下載**，該集留在「新集」並顯示「空間不足 · 等待空間」；500 MB 上限（Podcast.kt:39）照樣生效。
* **首次訂閱基準**（移植 A）：訂閱成功即把目前 feed 的 guid 全數記入 `seenGuids`，**不下載任何舊集**——不產生回填洪水；舊集要就在單集清單裡手動「匯入」，提供「取得最近幾集」由使用者自決。
* **暫停訂閱（v1 顯示的每訂閱開關）**：清單列選單／節目頁頁尾「暫停訂閱」→ `paused=true`：「全部檢查」與 opportunistic 自動檢查跳過該訂閱，清單副標改「已暫停 · 不會檢查新集數」、新集徽章凍結；節目頁的手動「檢查新集數」是使用者明確動作，仍可用。**暫停 ≠ 取消**：已排入佇列／進行中的下載照常完成，移除訂閱的規則不受影響。「繼續訂閱」還原，並照常參與下次 opportunistic 檢查。移除是刪除記錄，暫停是可逆狀態——兩者都在 v1，`auto` 則留給 v2。
* **佇列公平性**：自動/手動排入的單集走同一條 oldest-first FIFO（TranscriptionService.kt:708 每圈重點 `minByOrNull { createdAt }`），使用者的會議工作永遠不被單集淹沒。

## 資料模型（新增欄位/檔案格式，與既有 podcast 匯入共用的管線）

**訂閱記錄**——Android `filesDir/subscriptions.json`（單一群組，`writeTextAtomic` + 程序級 `subLock`，仿 `SessionLibrary.metaLock`）；iOS `Application Support/subscriptions.json`（`Codable`，`jobs.json` 同級）。欄位兩平台同名：

```
{
  "id": "podcast-<fnv1a(feedUrl) hex>",     // feedUrl 即身分；與 iOS Podcast.stableHash 位元一致（無前導零補齊，故不一定 16 位）；301/308 搬家時 feedUrl 與 id 一併換鍵
  "feedUrl": "https://…/feed.xml",          // 檢查追隨 301/308 後寫入最終位址（見取得策略「feed 搬家」）
  "country": "tw",                          // 修 Podcast.kt:44：記錄搜尋命中時的國家
  "title": "…", "artist": "…", "artworkUrl": "…",  // artworkUrl100（搜尋結果已有欄位）
  "addedAt": 1760000000,
  "lastChecked": 1761000000,                // 0 = 從未；只在成功時更新
  "checkError": null,                       // 檢查失敗原因（保留上次清單）
  "auto": false,                            // v2 預留，v1 不顯示
  "paused": false,                          // v1 顯示：暫停＝跳過檢查與自動檢查；進行中的下載不受影響
  "seenGuids": ["guid:…", …],               // 上限 2000；看過即永不視為新集（擠出規則見下）
  "episodes": [                             // 最新在前，上限 60
    { "guid": "…", "title": "…", "audioUrl": "…", "published": 1760000000,
      "durationSec": 3100, "lengthBytes": 73400000, "queuedEntryId": "20261020-083011" }
  ]
}
```

**`seenGuids` 上限語意（上限＝檔案尺寸保護，不是滑動視窗）**：「看過即永不視為新集」是不可動語意，舊稿的 500 上限暗示擠出舊 guid、與之直接衝突，定案為：

* 上限調為 **2000**（guid 通常 ≤64 bytes，檔案上限約 130 KB），並定出**擠出策略**：超過上限時只擠出「見過、但從未排入佇列」的最舊 guid（依 `published` FIFO）；凡仍列在 `episodes` 的 guid 永不擠出。
* 檢查 diff 只比對本次解析視窗（60 集），被擠出的 guid 只在「同一集重新出現在視窗內」時才會被重新標新，此時由三道鏈兜住：`seenGuids`（2000）→ `episodes`（60）→ 庫 `meta.json.sourceUrl` 掃描——凡已入庫的集一律標「已匯入」、提交去重擋下，不會產生第二列。殘餘面僅剩「在 feed 見過、從未匯入、guid 已被擠出、又重回視窗」的集被重新標新——那只是多一枚「新集」chip，可手動忽略；寫入文件。
* 語意一句話：**上限保護檔案尺寸，永不把「已看過」的既有入庫集重新變成可下載的新集。**

**集數身分鏈**（去重根基）：`<guid>`／`<atom:id>` → `<link>` → enclosure URL → `sha1(title + pubDate)`；`Podcast.fetchEpisodes`（Android XmlPullParser，`FEATURE_PROCESS_NAMESPACES=false`）與 iOS 解析器**加讀** `guid`、`link`、`<enclosure length>`、`atom:link rel="enclosure"`、channel 的 title/author/`itunes:image`——純 RSS 節目行為不變。上限：feed 本體 2 MB、解析 60 集。

**来源標記（provenance）**——v2 去重與「已匯入」判定的持久鍵（移植 A 的 source.json 構想，落地為 meta.json 欄位）：

* Android：`SessionLibrary.PendingDownload` 新增 `seriesId`/`guid`（寫入 `download.json`，`toJson` 選讀）；**實作改為建立項目時寫 `source.json`（kind/sourceUrl/seriesId/guid，永久保留）**，不動 `meta.json` 格式（今日 `download.json` 在下载完成即刪除，單集的 enclosure URL 就此失落，去重必須活得比它久）。`createDownloadEntry` 增加選填參數，舊 `meta.json` 照常解析。
* iOS：**實作改為 `Application Support/episode_sources.json`**（jobId/sourceUrl/guid/seriesId；場次 id＝job id，故作業結束後仍可對應），`Job` 與場次格式都不動。既有以 URL 雜湊命名的 `.part`（`podcast_<fnv1a(url)>.part`）天然續傳同一集。

**共用管線**：訂閱只產生 `(title, audioUrl, durationSec, seriesId, guid)` 四元組，交給今日同一個提交點：

```
Android  ImportDownloads.submit(ctx, ep.title, PODCAST, ep.audioUrl, durationSec, seriesId, guid)
iOS      Imports.addEpisode(ep, seriesId: guid:)
```

其後 `.part` 續傳、暫停/退避（2 s → 60 s）、`ready → withModels → processDownloaded`、`ImportPipeline.promoteInto`（寫回同一個項目、不新增第二列）全部原樣。

## 新集數取得策略（輪詢節奏、觸發時機、iOS 後台限制的務實做法、手動「檢查新集數」保底）

v1 的檢查只有兩種觸發，全部在使用者眼前：

1. **「檢查新集數」／「全部檢查」**按鈕（序列化、可取消、每次一個 GET）。
2. **單次/session opportunistic 整理**：開啟節目頁且 `lastChecked` 超過 24 小時時自動檢查一次（一個節目一個 GET，數 KB、亞秒級）。

**暫停訂閱（`paused`）跳過 1 的「全部檢查」與 2 的自動檢查**；節目頁手動「檢查新集數」是明確動作，不受暫停影響（語意見使用者流程）。

檢查請求沿用既有 `Podcast.open` 設定（15 s timeouts、UA `PrimeMinutes/1.0`），加 `If-None-Match`/`If-Modified-Since`：304 = **零成本重查**（移植 A），記 `lastChecked`、清單不動。

**feed 搬家（3xx 手動追隨＋換鍵）**：今日兩平台自動追隨 redirects——Android `openStream` 的 `instanceFollowRedirects = true`（Podcast.kt:119）、iOS `URLSession.shared.data` 預設跟隨且丟棄 response（Podcast.swift:32）——3xx 對 App 完全不可見，feed **永久搬家（301/308）時檢查永遠用舊 URL、`feedUrl` 永不重鍵**，訂閱最終卡死在舊位址（404/410 只是搬家後的症狀，會被誤歸類為「來源已移除」）。定案：

* **檢查請求改手動逐跳**：Android 檢查用 `instanceFollowRedirects = false` 自行讀 `Location`；iOS 實作 `urlSession(_:task:willPerformHTTPRedirection:completionHandler:)` 攔截後手動跟跳。最多 **5 跳**，重訪已見過位址即中斷（防環）；追蹤終點：Android 用 connect 後的 `conn.url`，iOS 用 `HTTPURLResponse.url`。
* **301/308（永久搬家）**：於最終位址完成檢查，並將訂閱記錄**換鍵**——`feedUrl` 寫入新位址、`id` 重算 `fnv1a(新 feedUrl)`，`subLock` 下一次原子重寫 `subscriptions.json`，顯示「訂閱連結已搬家 · 已追隨新連結」一次。`seenGuids`、`episodes`、來源標記、已入庫場次**全數不動**：集數身分在 guid，不在 URL。
* **302/303/307（暫時）**：僅在本次檢查內追隨取回 feed，**不換鍵**（避免被地區鏡像／CDN 黏住），`feedUrl` 與 `id` 維持原值。
* 音訊 enclosure 下載**維持既有自動追隨**：`.part` 以 enclosure URL 雜湊為鍵（iOS `podcast_<fnv1a(url)>.part`），單集搬家不換鍵、續傳不受影響，本節不適用。

失敗分類（每節目獨立，單個死 feed 不得拖垮其他訂閱；清單保留、可再檢）：

| 狀況 | 狀態字串 | 行為 |
|---|---|---|
| 404/410/DNS/TLS | 訂閱已失效 · 來源已移除 | 停止檢查建議（移除訂閱）；已下載單集不受影響 |
| 401/403 | 此節目需要訂閱服務，無法在 App 內取得 | 不重試 |
| 解析失敗（HTML 頁/壞 XML） | 無法解析 RSS | 保留上次清單 |
| 全 feed 無音訊 enclosure | 此節目沒有音訊單集 | 建議移除 |
| 429/403 於 serial 檢查 | 上次檢查失敗 · 稍後自動重試 | 檢查節流退避 |
| 301/308 永久搬家 | 訂閱連結已搬家 · 已追隨新連結 | 於終點完成檢查；`feedUrl`/`id` 換鍵，guid 鏈與已看集數不動（見上） |
| 302/303/307 暫時跳轉 | —（檢查照常成功） | 僅當次追隨，不換鍵 |
| >5 跳／Location 成環／非 http scheme | 上次檢查失敗 · 稍後自動重試 | 中斷追隨；清單與鍵不動 |
| pubDate 在未來／時鐘偏移 | —（不標新集） | 視為非新集 |

**iOS 後台現實（務實做法）**：BGTaskScheduler 對一週開幾次的小工具 App 一天可能只給幾次短暫窗口，而單集下載走前景 `Chunked`（background session 目前只服務模型）、`BackgroundWork.schedule()` 對純下載佇列直接略過——**排程帶得動檢查、帶不動 30–100 MB 的單集**。因此 v1 不註冊任何 background task，也不寫「會自動下載」文案；「檢查新集數」按钮就是保底，永遠可用。

**v2（M3）自動檢查規格**（寫入規格、不在 v1 實作）：不新增排程器，只搭 App 已存在的唤醒來源——Android：回前台（MainActivity.kt:529 既有 `ImportDownloads.resumeNow()` 旁）＋已在運行的下載迴圈結尾順道；iOS：`DownloadResumer` 既有 wake 來源（`didBecomeActiveNotification`、`NWPathMonitor` satisfied）。24 小時節流（暫停訂閱一律跳過）、`dlConsent` 僅限 Wi‑Fi 閘門、每節目每次最多取最新 1 集、每次唤醒全 App 最多 3 集、預設 **off**。發現 ≠ 下載：iOS 發現的新集在 App 開啟前不會下載，文案如實寫「新集數已發現 · 開 App 繼續」。

## UI（Android+iOS 對齊：系列頁、集數清單狀態、訂閱管理）

**＋ 選單（兩平台同步同一版本出貨）**：新增「**節目**」列，副標「訂閱的節目 · 檢查新集數」，位於「開啟場次」之前——Android 五列：音訊檔案 / Podcast / YouTube / 節目 / 開啟場次（AddSourceSheet.kt:67-70 現有四列的順序保持，節目為第 4 列，沿用 `SourceRow`）；iOS 五列：音訊檔案 / Podcast / PeerTube / 節目 / 開啟場次（LibraryView.swift:224-252 `AddSourceSheet`；已改 `.presentationDetents([.medium, .large])`，五列在 medium 不夠時可拉到 large，原「450 → 520」不再適用）。App Store 5.2.3 使 iOS 無 YouTube 列，節目列不受影響（RSS 匯入合規）。

**單集清單顶「訂閱此節目」**：現有 PodcastSheet/PodcastPanel（Android）、PodcastView（iOS）流程不動，清單頂部加勾選（已訂閱則顯示「已訂閱」並直接連節目頁）；勾選純離線寫檔，「轉錄」照常。

**貼上 RSS 連結（搜尋面板一列）**：手動貼上（預填剪貼簿中的 http(s) 網址）：直接走 M1 擴充後的 RSS 解析讀 channel `<title>`/`itunes:author` 開節目預覽 →「訂閱此節目」，跳過 iTunes 目錄（原未決 1 的逃生口，v1 落地；訂閱寫檔路徑與搜尋訂閱完全相同）。

**畫面模式（2026-10-29，依 Lot 1–3）**：Android 節目清單與節目頁為**全螢幕**畫面，仿 Settings 全螢幕（ConfigSheet.kt:78 `LargeTopAppBar`、edge-to-edge、`AppSnackbar` 承載提示與「復原」），不用 bottom sheet；iOS 用 `NavigationStack` + `List`，列刪除與暫停用 `.swipeActions`，刪除確認用 `.alert`，清單 >8 筆時加 `.searchable`。兩平台都不新增自訂元件。

**節目清單**（訂閱首頁）：每列 = artwork + 節目名稱 + 出演者；副標「N 集 · 上次檢查：10月18日」／「尚未檢查」／暫停中「已暫停 · 不會檢查新集數」；藍色徽章「2 集新」（暫停中凍結）；工具列「全部檢查」（跳過暫停中的訂閱）；列操作（Android 長按選單，刪除附 snackbar「復原」〔v1 實作未加左滑〕；iOS swipeActions：刪除〔alert 確認〕、暫停／繼續；context menu：分享節目連結）「暫停訂閱／繼續訂閱」「分享節目連結」「刪除訂閱」（訊息：「只移除訂閱，已下載的場次保留。」）；空狀態「尚未訂閱節目，到 ＋ → Podcast 搜尋訂閱」。「分享節目連結」＝ 系統分享面板（Android `ACTION_SEND` 文字、iOS `ShareLink`），內容「節目名稱 — feedUrl」，接收端貼上即訂閱（見上）。

**節目頁**（採用 B 的版面作為本計畫的節目頁）：標頭 artwork（本機快取、納入 `isExcludedFromBackup`）、節目名稱、出演者、N 集（補上 iOS 已解析未顯示的 `episodeCount`，Android 用既有 plurals `podcast_episodes`）；檢查失敗時紅色「訂閱來源失效：%1$s」；主按鈕「檢查新集數」（進行中「檢查中…」）；單集清單最新在前（標題 / 長度 `formatDuration` / 發表日——Android 補顯示發表日與 iOS 對齊，iOS 補顯示長度與 Android 對齊）；每列狀態 chip 全部沿用既有 `dl_*` 文案；頁尾「上次檢查：%1$s · 檢查新集數 · 暫停訂閱／繼續訂閱 · 分享節目連結 · 移除訂閱」；搬家換鍵後標頭顯示一次「訂閱連結已搬家 · 已追隨新連結」。

**首頁 / 場次畫面 / 佇列：不改**。「我的訂閱」徽章列（首頁與場次庫頂部的 artwork 磚 + 新集數徽章）列為 M2 fast-follow，不擋 M1。

**設定 → 儲存空間**（M2）：現有只列 AI 模型的 StoragePanel（Android SettingsContent.kt:375）/ Storage（iOS `Settings.swift:141`）新增「**匯入音訊**」行（Android `filesDir/library` dirSize；iOS audio+sessions），附提示「在首頁刪除場次以釋放空間」，並按訂閱顯示小計。

**新增字串**（en / zh-TW；zh-CN 由 zh-TW 以既有 OpenCC 流程產生，`ResourceLanguageTest` 守鍵、placeholder 與字形純度；iOS 三個 `.lproj` 同表。feed 來的節目/單集標題是**外部內容**，不套 `AppLanguage` 的繁簡轉換，與 OpenCC span-gating 只轉生成文字的規則一致）：`節目`、`訂閱的節目 · 檢查新集數`、`訂閱此節目`、`已訂閱`、`檢查新集數`、`全部檢查`、`檢查中…`、`上次檢查：%1$s`、`尚未檢查`、`%d 集新`、`新集`、`下載並轉錄`、`已匯入`、`已加入佇列`、`開場次`、`訂閱來源失效：%1$s`、`訂閱已失效 · 來源已移除`、`此節目需要訂閱服務，無法在 App 內取得`、`無法解析 RSS`、`此節目沒有音訊單集`、`上次檢查失敗 · 稍後自動重試`、`空間不足 · 等待空間`、`立即下載`、`刪除訂閱`、`已刪除訂閱`（snackbar，「復原」沿用既有字串）、`只移除訂閱，已下載的場次保留。`、`已在場次庫 · 開啟該場次`、`暫停訂閱`、`繼續訂閱`、`已暫停 · 不會檢查新集數`、`訂閱連結已搬家 · 已追隨新連結`、`分享節目連結`、`貼上 RSS 連結`。

## 匯入管線整合（去重、已匯入集數、暫停續傳、失敗處理）

**提交去重**：`submit` 前查 `seenGuids`/佇列/庫：同一 guid 已在佇列或已下載 → 不建立第二筆項目，提示「已在場次庫 · 開啟該場次」並開啟該列（今日同一集連點兩次會產生兩筆項目，訂閱不可容許）。`subLock` 序列化檢查與提交，消除「檢查與手動匯入競態 → 兩列」問題。

**已匯入判定**：開啟節目頁時背景執行緒掃一次庫的 `sourceUrl`（M1 起新匯入都有；`download.json` 存在中的項目直接比 URL）：enclosure URL 命中 → 該集標「已匯入」並可開場次。**本功能之前的舊匯入沒有存 URL**（Android `downloadDone` 刪除 `download.json`；iOS 完成檔改名 `<job-uuid>.<ext>`）→ 舊集可能被標為新集，**採納並寫入文件，不做 title 比對回填**（易誤配，且刪除/合併都不是 v1 該做的事）。

**暫停續傳**：完全不動。Android `.part` 在項目自己的目錄（`download.<ext>.part`，被殺後由 `ImportDownloads.kick` 續傳）；iOS 以 URL 雜湊續傳（重新選同一集即接續）。檢查本身不抓音訊位元組。

**磁碟全滿（本計畫的既修項目）**：

* Android：寫入 ENOSPC（訊息比對「No space left」，**比訊息不比例外型別**，避免誤歸網路 IOException）目前落入 `transient` → 無限退避。改為新暫停狀態「**空間不足 · 等待空間**」：略過該項目（不標失敗、保留 `.part`），每圈與開機重試，回到「繼續」語意。
* iOS：`DownloadResumer.transient` 現把 ENOSPC 當永久錯誤（ModelStore.swift:381）→ 匯入下載同樣改走「空間不足 · 等待空間」暫停。
* **模型下載維持 ENOSPC 永久**（ModelManager.kt:525 / iOS ENOSPC 非暫時）——兩類下載分開，模型端不受本變更影響。

**失敗處理**：單集 enclosure 404/410 → 既有永久分類「下載失敗：原因」+「重試」；集數從 feed 消失 → 清單移除該集，已入庫場次不動；guid 被重新產生（republish）→ 依賴「看過即不再新」+ URL 比對，文件記載此侷限。

**iOS 順手補齊（M1 同週）**：新增 24 小時 `.part` 清理（**只掃 audio 目錄的 `podcast_*.part`**、跳過仍被 `jobs.json` 下載項目引用者、僅在無下載運行時；**不碰模型目錄**——模型 `.part` 可能由 `BackgroundDownloader` 在 App 暫停時寫完，交給 `PartPlan.verify`）對齊 Android `sweepStaleParts`——訂閱會讓棄置 `.part` 變多，這個既有缺口必须同版補。

## 測試計畫（DEV hooks：vox_podcast 等既有 hook 的延伸；自動化缺口 vox_micfile 無關，不需）

**DEV hooks**（沿用 `vox_podcast` 模式，MainActivity.kt:667-677 / iOS `VOX_*` 於 `-DVOX_DEV`，devhooks.zsh 以 devicectl 環境變數遞送）：

* Android：`--es vox_sub "<query>"`（訂閱第一個搜尋命中）`[--ei vox_sub_ep n]`（檢查 + 排入第 n 集）；`--es vox_sub_check`（強制檢查並記錄）；`--ez vox_sub_lowspace`（假造空間不足路徑）；`--es vox_sub_feed "<feedUrl>"`（直接以 RSS 連結訂閱，測貼上路徑）；`--ez vox_sub_pause`（暫停後觸發全部檢查，驗證跳過）。
* iOS：`VOX_SUB=<query>`、`VOX_SUBEP=<n>`、`VOX_SUBCHECK`、`VOX_SUBLOWSPACE`、`VOX_SUBFEED=<feedUrl>`、`VOX_SUBPAUSE`；沿用 `VOX_DLSLOW` 測空間/退避、`VOX_IDS/VOX_DEL` 檢視含 `.part` 的佇列。

**自動化**（fixture 導向，iTunes/RSS 為即時服務不做即時断言）：

* JVM/Robolectric：身分辨識鏈（guid → link → URL → title+pubDate）、`seenGuids` 語意（消失又出現不再標新）、`seenGuids` 上限 2000 擠出（僅擠「見過未排入」的最舊 guid；列在 `episodes` 的 guid 不被擠出）、2 MB/60 集上限、304 短路、404 分類、**301/308 換鍵（`feedUrl`/`id` 更新，`seenGuids`/`episodes`/來源標記不動）**、302/303/307 不換鍵、>5 跳／成環中斷分類、`paused` 跳過全部檢查與自動檢查（手動檢查可用）、ENOSPC **以訊息**分類（含「網路 IOException 不得誤歸類」反例）、`sub.json` 新欄位對舊檔寬容。
* Android instrumentation：`SourceSheetsTest` 加「訂閱此節目」勾選與「貼上 RSS 連結」入口；`AddSourceSheetTest` 加第 5 列回調；新增 `SubscriptionTest`（guid diff、重複提交只得一個項目、已匯入掃描、貼上 RSS 網址 → 訂閱預覽、暫停訂閱後全部檢查跳過、分享面板內容含 feedUrl、左滑刪除 → 復原）。
* iOS UI 測試：以 `VOX_SUB` 路徑走「訂閱 → 檢查 → 排入 → 首頁出現列」（目前 Podcast/PeerTube 皆手動測試，本功能補一條自動化）；import 路徑既有 `devhooks.zsh` 模式延伸。
* 實機：Note 10+（training-machine，獨立 worktree 建置，`studio.primeminutes.androidtest`，`flock /tmp/n10.lock`；完整路徑：訂閱 → 檢查 → 下載 → 斷網暫停 → 飛航模式 → 場次 → 空間不足 → 刪除訂閱保留場次）；iPhone 14 Pro Max（共用裝置，`~/work/.maclock`，保持短跑；UI Automation 不可用時改模擬器並註明：訂閱 → 檢查 → 前台下載 → 場次；背景僅驗證「開 App 繼續」文案與 `.part` 清理）。

## 里程碑 M1/M2/M3 與優先序

**M1 — 可出貨切片（v0.54.x 一次出貨，Android → iOS，每平台約 1 週）**
1. 解析擴充：guid/link/enclosure length/atom/media enclosure/channel 中繼資料 + `If-None-Match`/`If-Modified-Since`（304 短路）+ 2 MB/60 集上限；修 `country=us`（記錄命中國家）。
2. 訂閱存放：`subscriptions.json`（雙平台）+ `seenGuids`（上限 2000＋擠出規則：只擠見過未排入者）+ `paused` 旗標 + 首次基準規則 + `subLock`。
3. 來源標記：`PendingDownload.seriesId/guid`、`meta.json.sourceUrl`（Android `downloadDone`）、iOS `Job.seriesId/guid` + 完成記錄 `sourceUrl`；全部選填欄位、舊檔照常解析。
4. 檢查：單檔 GET + guid diff + 失敗分類表（**含 3xx 手動追隨：301/308 搬家換鍵、302 系不換鍵、5 跳上限**）+ 「已匯入」掃描 + 提交去重。
5. 磁碟全滿：Android/iOS 匯入 ENOSPC → 「空間不足 · 等待空間」暫停（略過、保留 `.part`、每圈與開機重試）；模型端不變；pre-download 空間檢查（length×3+200 MB，iOS 用既有 HEAD）。
6. UI：＋ 第 5 列（兩平台同版）、「訂閱此節目」、節目清單、節目頁（Android 全螢幕 LargeTopAppBar＋snackbar 復原；iOS NavigationStack/List/swipeActions/alert）、暫停訂閱／繼續訂閱、分享節目連結、貼上 RSS 連結訂閱、zh-TW/zh-CN/en 字串。
7. iOS podcast `.part` 24 小時清理（僅 audio 目錄）；iOS `episodeCount` 顯示、Android 發表日顯示（parity 對齊）。
8. DEV hooks + 測試（上表）+ 指南/README/媒體更新（見下）。

**M2 — fast-follow（下一版，獨立可拆）**：首頁與場次庫「我的訂閱」徽章列；設定 → 儲存空間「匯入音訊」+ 每訂閱小計；artwork 快取精修；檢查退避與多訂閱序列化調校；**接收其他 App 分享的 feed 連結**（Android `SEND text/plain` 篩 http(s)、iOS Share Extension 新 target）；**訂閱清單 OPML 匯出／匯入**（v1 非目標的歸屬地：匯出＝每訂閱一筆 `<outline type="rss" xmlUrl>`；匯入以換鍵後 fnv1a 雜湊合併去重，新訂閱套首次基準、不回填）。

**M3 — v2 自動（獨立評估後）**：`auto` 旗標上線（預設 off）——只搭既有唤醒來源（Android 前景/運行中的下載迴圈；iOS `didBecomeActive`/`NWPathMonitor`），24 h 節流、`dlConsent` 僅限 Wi‑Fi、每節目最新 1 集、每唤醒 ≤3 集、「立即下載」覆寫；屆時才評估配額設定。v2 不動 M1 的任何管線。

**M4 — 真正的背景檢查（v0.54 之後，2026-10-29 使用者核可加入計畫）**：M3 只搭既有唤醒來源，App 沒被打開就不會檢查；M4 讓 App 未啟動時也能發現新集數。
1. **階段一：每日背景檢查＋通知**（預設 off，設定「訂閱 → 背景檢查新集數」開關）
   * Android：WorkManager `PeriodicWorkRequest`（24 h，約束 `UNMETERED` 網路 + 電量不低），重開機後自動恢復；只做 RSS 條件式 GET（ETag/Last-Modified）與 guid 差比，**不下載音訊**；有新集時發本機通知「《節目》2 集新」，點按開節目頁。需 `POST_NOTIFICATIONS` 執行期權限（Android 13+），拒絕時開關說明「只更新徽章」。
   * iOS：`BGAppRefreshTask`（`earliestBeginDate` 24 h，Info.plist `BGTaskSchedulerPermittedIdentifiers`），每次執行後重排；時機由系統決定、**不保證**、使用者滑掉 App 後不會喚醒——文案如實寫「iOS 會在適當時機檢查，不保證準時」。同樣只檢查、發 `UNUserNotificationCenter` 本機通知。
   * 兩平台共用：檢查邏輯沿用 M1 的 `Subscriptions` 檢查（暫停訂閱跳過、301/308 換鍵、失敗分類退避），每次全 App 時間盒 ≤ 30 s、訂閱序列化；無網路費用承諾（僅限 Wi‑Fi）。
2. **階段二（選用）：自動下載新集**＝啟用資料模型已預留的 `auto` 旗標（每訂閱，預設 off）
   * Android：由背景工作把新集交給既有 `ImportDownloads`（前景服務），沿用空間預檢、`空間不足 · 等待空間`、去重；每節目最新 1 集、每次 ≤ 3 集。
   * iOS：單集改走 `BackgroundDownloader`（ec45b1ae 的 background URLSession，`PartPlan.merge` 沿用），下載可在 App 暫停時完成；**轉錄仍要 App 開啟**（引擎不在背景跑），通知文案寫「已下載 · 開 App 轉錄」。
   * 這一步會碰匯入管線，需另行評審後才動工。
* 驗收：Android 用 `adb shell cmd jobscheduler run` / WorkManager 測試驅動強制執行；iOS 用 `e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:]` 於 Xcode 除錯器觸發；兩機各驗「新集→通知→點按開節目頁」與「暫停訂閱不通知」。
* 隱私／電量：不上傳任何資料，只對使用者已訂閱的 feed 發 GET；README/指南的「訂閱＝儲存節目連結，不付費、無背景連線」在開啟此開關時改寫為「開啟背景檢查後，每天在 Wi‑Fi 下讀取已訂閱節目的 RSS」。

**優先序**：M1 內若時間吃緊，依序保 1–5（解析/存放/來源標記/檢查/空間）與 6 的最小 UI（＋列、訂閱勾選、節目清單＋節目頁）；7 的 `.part` 清理屬同版安全項，不拆。6 之中：**暫停訂閱與 3xx 搬家換鍵屬資料正確性，不拆**；分享節目連結／貼上 RSS 連結是最低優先尾巴，時間吃緊可整項延到 M2（純增益功能，不影響 1–5 出貨，OPML 已另列 M2）。

**出貨**：Android v0.54.0 APK（versionCode 200+）；iOS TestFlight build 14+（`~/work/testflight.sh`，Organizer 上傳）。指南更新：§4 新增「Podcast 訂閱」節點（加入音訊 → Podcast → 節目 → 訂閱此節目 → 節目頁 → 新集下載並轉錄）、mermaid 畫面結構補節點、驗證矩陣重新對日期（現行最後全面驗證為 v0.47.0，落後 PeerTube），並加入「訂閱＝儲存節目連結，不付費、無背景連線」註解；README 特色行 → 「匯入音訊檔、Podcast（單集或訂閱節目）、YouTube、PeerTube」。媒體（emulator、淺色主題、EN 程式+EN 會議 / ZH 程式+ZH 會議，依既有規則）：`43-subs-list.png`（節目清單）、`44-subs-show.png`（節目頁）、`45-subs-new.png`（新集清單）、重拍 `07-add-source.png`（五列）與 `08-podcast.png`（訂閱此節目）。

## 未決問題

1. **訂閱创建只能經 iTunes 搜尋**（已決定，v1 解決）：M1 落地「貼上 RSS 連結」入口（接收分享 M2）（見 UI），搜尋不再是唯一門，`country` 修好與否只影響搜尋覆蓋。剩餘小題：貼上非 feed 網頁網址＝解析失敗分類已擋，要不要在預覽前先探一次 HEAD？留實作期決定。
2. **舊匯入的去重**（已決定，見匯入管線整合）：接受並寫入文件，不做指紋遷移。實際影響小：首次訂閱基準把當下所有 guid 記為已看，舊集不會被標新；只在使用者手動點舊集「下載並轉錄」時才可能與舊匯入重複。
3. **guid 卫生**：無 guid 或每次建置重產 guid 的 feed 會把舊集標新（`seenGuids` 2000 上限＋「見過未排入才擠出」規則 + URL 鏈已緩解，見資料模型）；renamed 集以 title+pubDate 兜底仍可能再標新——接受並記錄。
4. **republish 同一 guid**（整集重發）會被當同一集而不重下載——符合預期？
5. **通知**：v1 全無新通知。v2 自動上線後，Android 要不要「《節目名》新增 N 集」單發（每訂閱開關、預設 off）？iOS 維持 App 內徽章。
6. **保留集數**：v1 固定「全部」；若要「保留最近 N 集」自動清理，門檻＝僅 DONE、`edit_rev==0` 無編輯、未釘選、非佇列/執行中/播放中，且需「將移除」預覽——v2 再議。
7. ~~iOS 五列選單高度~~（已解：sheet 改 `.medium/.large` detents）；e-ink 調色盤下的進度條規則沿用。
8. **v2 配額**：`podcastQuotaMB`（1/3/5 GB/不限）與「清理最舊的自動單集」是否綁定 v2 自動下載一起出，避免只有清理沒有洪水來源。
9. **釘選**：場次選單加「釘選」（排除未來任何自動清理）是否提前到 M2。
10. **PeerTube/YouTube 系列化**（頻道訂閱）明確不在本計畫；`subscriptions.json` 的 `kind` 是否預留欄位。
