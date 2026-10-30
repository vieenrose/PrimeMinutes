# Media needed for landing v2 (reshoot after the P2 redesign)
Format: webp 480 px wide, 9:19.5 portrait unless noted; zh-TW UI, light theme; demo people 王主任 / 陳經理 / 林雅婷, e-mail @example.com.

| ID | Section | What | Platform | Replaces |
|---|---|---|---|---|
| M1 | Hero | Demo video in P2 UI: two meetings back to back, stop → notes/summary/title (mp4 ≤ 3 MB + poster webp) | iPhone 17e | assets/demo.mp4 (pre-redesign) |
| M2 | 錄的同時 | 6–8 s loop (webm/mp4, not gif): red record button, transcript growing, speaker chips, notes card updating | Android Note10+ | dashed placeholder |
| M3 | 摘要與筆記 | Summary tab with segmented tabs + blue citation pills | Android or iOS | placeholder |
| M4 | 摘要與筆記 | Notes tab: five types + 核對 marks | same | placeholder |
| M5 | 講者 | People list with 王主任/陳經理/林雅婷 (current shot shows "Alice", older UI) | Android | assets/people-people-zh.webp |
| M6 | 講者 | Boundary editor in P2 UI (still or short loop) | Android | assets/boundary.webp |
| M7 | 搜尋與提問 | Library search results in P2 UI (pill search, scope pill once shipped) | either | assets/meeting-search-zh.webp (v0.53.14 build) |
| M8 | 搜尋與提問 | Ask answer with citation pills, bubble layout | either | assets/meeting-ask-zh.webp |
| M9 | 匯入 | Channel page in P2 UI (Android page is new per SPEC) | Android | assets/channel-zh.webp |
| M10 | Flow row | Re-export the 4 flow shots from the P2 build if they predate v0.56.5 | iPhone | qs-capture / 17-process / 10-summary / 11-transcript |
| M11 | Upcoming | Citation preview panel; 合集 page — only when shipped | both | (text-only today) |
| M12 | og:image | 1200×630 in slate/blue palette | — | site/og.png (dark/green) |

Already P2 and reusable as-is: share-sheet-zh, share-pdf-zh, import-cloud-zh (iOS reshoot 2026-10-30).

Status 2026-10-31 (Android remainder pass): M12 done as `assets/og.png` (1200×630, slate/blue, from the P2 summary and notes shots; `og:image` now points there instead of the old root `site/og.png`). M9 `channel-zh.webp` reshot with the last-check time pinned to 10:00. M10 is iPhone-only (not in this pass). M11 left as text: citation preview and collections are merged on Android but not released on either platform yet.
