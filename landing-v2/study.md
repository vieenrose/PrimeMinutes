# Landing v2: study + proposal

Source read: `index.html` + `site/` at `origin/ios` (3aa6744e; the page is at the repo root, assets in `site/`), README (zh-TW), docs/USER_GUIDE.md, slides/PrimeMinutes-zh-TW.html (v2.62), CLAUDE.md, release notes v0.56.5, collections/scope SPEC. Worktree: /home/luigi/.cache/pm-landing (detached, nothing modified).

## 1. Feature inventory (★ = key, gets its own section)
| # | Feature | Key? | Status |
|---|---|---|---|
| 1 | Live recording: transcript ~2 s after speech | ★ | shipped |
| 2 | Three lanes at once (ASR + diarization + live meeting reader notes); smaller phones read after recording | ★ | shipped |
| 3 | "下一場" back-to-back recording, previous one keeps processing | | shipped |
| 4 | Speaker labels/colours; provisional near the live edge, corrected after stop | ★ | shipped |
| 5 | People = OS contacts (e-mail for sharing); voiceprints opt-in, suggest → you confirm, deletable | ★ | shipped |
| 6 | Fix speakers: long-press reassign, boundary editor (drag), merge speakers, undo/redo | ★ | shipped |
| 7 | Summary (segmented prose for long meetings) + auto title | ★ | shipped |
| 8 | Notes by type: 決議/待辦/提議/未決/數字, 核對 marks | ★ | shipped |
| 9 | Citation pills → jump/play original audio | ★ | shipped |
| 10 | Citation preview panel | | upcoming |
| 11 | Library-wide semantic search (EmbeddingGemma), filters by person/date/source | ★ | shipped |
| 12 | Ask (one meeting / whole library), answers with citations, says "not found" | ★ | shipped |
| 13 | Scoped search/Ask (collection / channel), 合集 (user collections) | | upcoming (SPEC 2026-10-30) |
| 14 | Import: audio/video files, cloud drives, Podcast, PeerTube (YouTube Android) | ★ | shipped |
| 15 | Channels (podcast series, new-episode check) | | shipped |
| 16 | Share sheet: .pmin whole meeting (cross-platform), PDF, Word, txt/md, audio only, mail via own app | ★ | shipped |
| 17 | Merge/split sessions, undo | | shipped |
| 18 | Processing queue (survives kills, LLM loaded once per batch); recovery of interrupted work | | shipped |
| 19 | zh-TW / zh-CN / EN; script of generated text follows UI language | | shipped |
| 20 | Speed/accuracy settings, reprocess with a new model | | shipped |
| 21 | Fully offline, no account; network only for model download (Wi‑Fi option) and online sources | ★ | shipped |
| 22 | Android + iPhone parity | ★ | shipped |
| 23 | P2 redesign (cool slate, segmented tabs, queue bar, one share sheet) | | shipped v0.56.5 — "新版" tag |

## 2. Current page critique
- Strong: clear headline, the demo video, download cards with QR, honest privacy list, good FAQ.
- Sparse: features compressed into 3 cards + 4 stats; search, import/channels, share/export, people/contacts, boundary editor, queue, recovery, languages are missing or only in FAQ.
- Visual mismatch: dark navy + green accent; the app is now P2 cool slate with blue accent (red only for record).
- Stats "0 次 / 2 平台" are filler; "5 類" is better told inline with the note types.
- Only 6 screenshots, 4 in one row, all pre-redesign; feature-to-image mapping is loose.
- Version strings stale (v0.56.4 / iOS 0.56.2).
- Reveal-on-scroll JS hides content until scrolled (fine, but unnecessary; v2 drops it).

## 3. Information architecture (v2, in order)
| # | Section | Headline | 1-line copy | Visual | Why |
|---|---|---|---|---|---|
| 0 | Nav | — | 功能・隱私・支援機型・常見問題・下載 | — | Sticky, one blue CTA |
| 1 | Hero | 開完會，紀錄已經寫好了。 | 邊錄邊出逐字稿、標出誰在說話，AI 同時記下決議和待辦… | demo.mp4 in phone frame (iOS) → **M1 reshoot** | Keeps the brand promise; download cards + QR above the fold |
| 2 | Trust strip | — | 離線不上傳・不用帳號・Android 與 iPhone・繁中/简中/English | icons | Answers the four first objections in one glance |
| 3 | Flow | 按下錄音，其他交給它。 | 錄音 → 邊聽邊記 → 摘要寫好 → 修正分享 | 4 existing site shots (iOS) | The whole story in 5 seconds |
| 4a | Live | 錄的同時，三件事一起做 | three numbered lanes; 下一場; small-RAM fallback | **M2** capture animation (Android) | The differentiator; only place red is used |
| 4b | Speakers | 誰說了什麼，一眼就知道 | contacts, opt-in voice, long-press / boundary editor / undo | people-people-zh + boundary (interim) | Trust in labels |
| 4c | Summary & notes | 每一句重點，都點得回原話 | citation pills, 5 note types, 核對, long meetings, preview (即將推出) | **M3/M4** | Verifiability is the core value of AI notes |
| 4d | Search & Ask | 不記得原話，也找得到 | semantic search, filters, Ask one/all, says "not found", scope (即將推出) | meeting-search-zh + meeting-ask-zh | Library value grows over time |
| 4e | Import | 不只錄音，現成的也能轉 | files, cloud, Podcast, PeerTube, channels, 合集 (即將推出), queue | import-cloud-zh + channel-zh | Second use case (podcasts/lectures) |
| 4f | Share | 先選內容，再選要送去哪 | PDF/Word, .pmin cross-platform, txt/md, mail via own app | share-sheet-zh + share-pdf-zh | Output is what the boss sees |
| 5 | Details grid | 還有這些，讓它更好用 | queue, recovery, merge/split, 3 languages, speed vs accuracy, library by source | icons (2-col on phone) | Completes "ALL key features" without long rows |
| 6 | Privacy (dark band) | 會議內容，不離開你的手機。 | 4 checks from current page | — | Kept verbatim-ish; dark band breaks rhythm |
| 7 | Specs | 我的手機可以用嗎？ | Android / iPhone cards, model sizes | — | Moved out of FAQ: it's a top question |
| 8 | FAQ | 你可能想問 | 7 Q incl. 分錯講者, AI 可信度, 手機放哪 | — | Kept |
| 9 | Closing | 下一場會議，讓手機幫你記。 | same download cards + QR | icon | Kept |

Design: light slate bg #F5F7FA, ink #14202E, mute #556677, blue #2F6BDE, red #D93F3F only for 錄音中. System CJK font stack (no web-font download — faster than the current Google Fonts load). No JS except reduced-motion video handling; all images `loading="lazy"`, video `preload="none"`. Upcoming items carry a grey 「即將推出」 pill — drop it or remove the line before publishing if not shipped.

Copy rules checked: no "free"/subscription claims, no open-source/GPL (the GitHub footer link was dropped; Releases link kept for the APK), no superlatives, fictitious names only (alt text uses none; people screenshot currently shows "Alice" → reshoot).

Measured: 390 px page ≈ 11,200 px tall, 1280 px ≈ 8,450 px; no horizontal overflow, no sibling overlaps (scripted check over media/steps/download/grid containers).
