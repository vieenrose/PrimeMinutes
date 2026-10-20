# UX review log

Hourly UI/UX iteration log for Prime Minutes. Newest entry first. Each entry: findings with
severity, what was changed, commit hashes, what was deliberately skipped.

Android = Kotlin/Compose (`app/`, branch `android`); iOS = SwiftUI (`ios/`, branch `ios`).
Verification on this machine: Android `./gradlew :app:compileDebugKotlin`; iOS has no Swift
toolchain here, so each edited file is syntax-checked with the tree-sitter Swift grammar
(`/home/luigi/tmp/parsecheck/check.js`) and compared against the same file at HEAD — the grammar
reports 14 pre-existing false positives on this codebase, so the test is "no new parse errors".
A full iOS build is not possible on this machine.

---

## 2026-10-21 — Android pass (branch `android`, commit `12563607`)

Implements audit items 1, 2, 3, 4 and 6 from the list below (in a detached worktree at
`/tmp/pm-ux`; `MainActivity.kt:1280–1315`, `TranscriptSearch.kt:127` and the `search_no_matches`
strings were left to the agent working in `/tmp/pm-a`, so no new `strings.xml` keys are added
anywhere in this commit).

### 1. Touch targets below 48 dp (HIGH)

| Severity | Finding | Change |
|---|---|---|
| High | Summary / action-items copy icons and `EditPencil`: `IconButton(Modifier.size(28.dp))` — 28 dp box, 9.6 dp ink (Material minimum is 48 dp) | dropped the size override → `IconButton` default 48 dp box |
| High | Ask sheet send (`AskSheet.kt:243`) — the sheet's primary action, 32 dp inside a 44 dp row | `Modifier.size(44.dp)` (the row's own height) |
| High | Player transport: play `38/44 dp`, step buttons `34/40 dp` | play `44/48 dp`, buttons `40/44 dp` (compact/normal), so the transport cluster clears the minimum |

### 2. Ink on the Sky accent (HIGH)

| Severity | Finding | Change |
|---|---|---|
| High | `Color.White` drawn on `pal.Sky` in the selection tick, filter chips, export-format chips and the mail-participants button — ≈ 2.6:1 on the dark theme's light-blue accent, fails AA for the 12 sp chip labels | new palette token `PrimeMinutesPalette.OnSky` (`= Slate900`, defaulted so the 3 theme constructors are unchanged — Slate900 is each theme's contrasting ground, the same ink `GradientButton` already uses on the accent): near-black on the dark accent, off-white on the light / e-ink accents. All four sites now read `pal.OnSky` |
| High | Active-segment ring in the timeline drawn `Color.White` at 35 % alpha on the white light/e-ink card ground → invisible (the "you are here" marker reads as nothing in 2 of 3 themes) | `pal.Slate200.copy(alpha = 0.55f)` (Slate200 is the primary ink in every theme) |

### 3. Speaker chip line height frozen (HIGH)

`speakerChipTextStyle()` set `lineHeight = 12.sp` while the glyph size follows the user's font
scale, so at large text sizes CJK names clipped against each other in the transcript rows.
`lineHeight` now follows `MaterialTheme.typography.labelMedium.fontSize` (12 sp × the app's
`fontScale`), so the leading scales with the glyphs.

**Verification:** `./gradlew :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx3g` in `/tmp/pm-ux`
→ `BUILD SUCCESSFUL in 34s`, exit 0. No new warnings on the 6 touched files (the only hits are
pre-existing `Icons.Filled.Send` / `VolumeOff` deprecations at untouched lines). Rebased onto
`origin/android` (`8f8d4e3d..12563607`), no force-push. No on-device pass: visual check of the
44 dp boxes, the `OnSky` chips and the timeline ring in all three themes is still owed.

**Skipped this tick:** item 5 (library no-match dead end — overlaps the other agent's
`search_no_matches` work), 7 (unconfirmed destructive deletes / silent mail-recipient delete),
8 (`onLongClickLabel`), 9 (`.imePadding()` on the YouTube / Podcast sheets), 10 (zh-TW strings:
`voice_me_script`, the settings GPL line, `PersonPicker.kt:153`), disabled-state ink contrast on
`pal.Sky`, e-ink speaker-hue legibility, `AiBenchmark` English literals.

---

## 2026-10-20 — iOS pass (branch `ios`, commit `90aff9a9`)

Audited with the `mobile-app-ui-design` skill and the apple-stack design skills
(`ux-writing`, `game-feel` feedback audit, `typography`). 3 areas implemented.

### 1. Touch targets below 44 pt (HIGH)

| Severity | Finding | Change |
|---|---|---|
| High | Library card menus: `Image("ellipsis").padding(10)` ≈ 36×30 pt (5 sites) | `.padding(12)` → 44 pt hit box |
| High | Summary / action-items copy + edit icons: bare glyphs ≈ 18 pt | `.tapTarget()` (44×44) |
| High | Ask sheet send button: bare glyph, primary action of the sheet | `.tapTarget()` |
| High | Speaker seam in the transcript (BoundaryEditor) 28 pt tall | `minHeight: 44` |
| High | Player bar volume menu: glyph only | `.tapTarget()` (siblings already 44×44) |
| Med | Library header `+` / settings, selection bar ✕ / trash, search clear, Ask capsule | `.tapTarget()`; capsule keeps its drawn height, hit box reaches 44 pt |
| Med | Transcript search row: ≈ toggle, prev/next, results toggle, sparkles, close | `.tapHeight()` (44 pt tall, intrinsic width — the row must not overflow at 375 pt) |
| Med | Note-row timestamp pill and "verify" link ≈ 24×17 pt | `.tapHeight()` |
| Med | Mail sheet: remove-one-off-recipient, add-address send | `.tapTarget()` + label |

New helpers in `ios/App/Support.swift`: `View.tapTarget()` (44×44) and `View.tapHeight()`
(44 pt tall, intrinsic width) — dense rows use the height form so a 375 pt row cannot overflow.

### 2. Feedback (HIGH — the app had exactly one haptic)

| Severity | Finding | Change |
|---|---|---|
| High | No tactile confirmation anywhere for record start/stop, a meeting finishing, Ask's answer, export, delete confirm | `Haptics` enum (Support.swift): `success` / `destructive` / `toggle`, main-thread safe; wired at LibraryView (record, saved-meeting count, both delete dialogs), Ask (answer finished), SessionView (export ready) |
| Med | Toasts (copy summary, boundary moved, …) are visual only — VoiceOver hears nothing | `UIAccessibility.post(notification: .announcement, …)` when the toast appears (SessionView overlay) |
| Med | Re-transcribe / re-summarize overwrite the transcript/summary with no confirm | **skipped** — needs a product decision (Android has the same shape); logged |

Completion triggers are guarded so a launch load or opening a sheet with history stays silent
(`countArmed` in LibraryView, `askWasWorking` in AskSheet).

### 3. Accessibility labels / Dynamic Type / contrast (MED)

| Severity | Finding | Change |
|---|---|---|
| High | Batch-delete trash icon had no label → VoiceOver read the English symbol name | `.accessibilityLabel(L("delete"))` |
| Low | Mail sheet send-address button labelled "儲存" (Save) — the action adds the address | label → `mail_add_address` |
| Med | SpeakerStats (talk-time bar) is tap-only: invisible to VoiceOver | `.accessibilityAddTraits(.isButton)` + `.accessibilityAction` |
| Med | Transcript rows: VoiceOver's default action opened the context menu; the play-from-here tap had no action | `.accessibilityAction { player.seekPlay(l.start) }` |
| Med | 9 pt / 10 pt fixed-point fonts ignore the app's own text-size control (HwStatus gauge, AgentPanel log + gauge digits) | `.caption2`, `.system(.caption2, design: .monospaced)` |
| Med | Search-result timestamp `.frame(width: 48)` clipped at large text (same bug the team fixed at the player bar) | `.fixedSize().frame(minWidth: 48)` |
| Med | Add-source sheet `.presentationDetents([.height(450)])` clips rows at AX sizes | `[.medium, .large]` |
| Med | Note tag colours were raw RGB literals; agent "restarting" chip was `.secondary` (gray text on a gray tint) | system `.purple` / `.teal`; `.brown` chip |
| High | `about_license` shipped "自由開放原始碼 · GPL-3.0" (license talk in product copy) | zh-Hant/en/zh-Hans: 「錄音與文字只留在這支手機」+「授權元件」/ "Licensed components" |
| Low | `voice_me_script` (zh-Hant, zh-Hans) appended an English sentence to the Chinese practice script | English tail removed |

Commit: `90aff9a9` (branch `ios`).

**Verification:** tree-sitter Swift parse of all 8 edited files: 14 reported ERROR nodes, identical
in count and position (± inserted lines) to the same files at HEAD — the grammar's known false
positives on this codebase; injected syntax errors are detected, and `SessionView.swift` (largest
edit) parses clean. `.strings`: all three catalogs 583 keys, key sets identical, no malformed
lines. No iOS build run (no Swift toolchain on this machine); no device/emulator touched.

**Skipped this pass (ranked, with reasons):**
- Semantic-search "no matches" shown while the semantic half is still running (SessionView;
  LibraryView's `densePending` pattern) — Med, needs the debounce plumbing mirrored.
- Transcript tab has no empty state for a no-speech session (summary tab has one) — Med.
- Toast/undo overlays stack on magic `padding(.bottom, 110)` above the player bar; move into the
  existing `safeAreaInset` stack — Med, layout restructure.
- Ask surfaces raw English technical errors (`提問失敗：prompt does not fit`) — Med, needs a
  localized error map.
- `zh-Hans.lproj` ships and is selectable (product decision, invasive).
- Library "New" filter is dead (`isNew` returns `false`) — Med, needs wiring or deletion.
- Dead-code cleanup: nested `.accessibilityLabel` on card menus (inner `cd_manage` overridden by
  outer `more`); duplicate `navigationTitle` + `.principal` in the Ask sheet.
- Chip rows (filter/people/speaker chips ~30 pt) — M3 chip spec matches; left as designed.

## Open Android findings (audited 2026-10-20; items 1–4 and 6 implemented 2026-10-21)

Highest-value, from the Compose audit (files on branch `android`). Struck items are fixed,
numbering kept so the entries refer to them.

1. ~~High — `SessionComponents.kt:545,424,469` `IconButton(Modifier.size(28.dp))` edit/copy
   icons.~~ **Done 2026-10-21** (`12563607`), with the transport buttons raised too.
2. ~~High — `AskSheet.kt:238` send button 32 dp.~~ **Done 2026-10-21** (`12563607`).
3. ~~High — `SessionComponents.kt:796-803` active-segment ring drawn `Color.White` at 35 % alpha on
   the white light/e-ink card ground → invisible.~~ **Done 2026-10-21** (`12563607`).
4. ~~High — `SessionComponents.kt:984-989` speaker chip `lineHeight = 12.sp` frozen while glyph size
   scales → CJK clipping at large text sizes.~~ **Done 2026-10-21** (`12563607`).
5. High — library no-match state is a dead end (no "clear filters / clear search" affordance).
6. ~~High — `Color.White` ink on `pal.Sky` (filter chip, export format, selection tick, mail chip)
   needs an `OnSky` token (dark theme accent ≈ 2.6:1).~~ **Done 2026-10-21** (`12563607`,
   `OnSky = Slate900`).
7. High — destructive without confirm: delete downloaded model (`ItemDownload.kt:83`), people merge
   (`PeopleScreen.kt:203,261`); un-ticking a mail recipient deletes it silently
   (`EmailParticipantsSheet.kt:192`).
8. Med — library row long-press (the only entry to selection mode) has no `onLongClickLabel`.
9. Med — `YouTubeSheet.kt:94`, `PodcastSheet.kt:26` missing `.imePadding()` (keyboard covers the field).
10. Med — `values-zh-rTW/strings.xml:530` `voice_me_script` mixes English (iOS side fixed today);
    `about_license` GPL line on the settings page (iOS side fixed today); `PersonPicker.kt:153`
    clear-search icon labelled "取消".
