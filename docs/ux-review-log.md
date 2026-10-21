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

## 2026-10-21 (12) — Android pass 7 (branch `android`, commit `4c14e49b`)

Carries the follow-up iOS pass 6 logged: the Ask sheet now focuses its own question field.

| Severity | Site | Finding | Change |
|---|---|---|---|
| Med | `AskSheet.kt:248` composer | the sheet opens on the answer list and the question field — the only thing to do there — needed a tap before the keyboard appeared; from the library Ask capsule the whole visit is "type a question" | `FocusRequester` + `.focusRequester(askFocus)` on the `BasicTextField`, requested 250 ms after composition so the bottom sheet finishes presenting (`LaunchedEffect(Unit) { delay(250); runCatching { askFocus.requestFocus() } }`, the codebase's existing pattern in `TranscriptSearch.kt:89`); the column already carries `.imePadding()` so the raised keyboard lifts the field |

**Scan, nothing else to change:** every `ModalBottomSheet` already sets `skipPartiallyExpanded = true`
(10 sites) — the iOS pass-6 detent/grabber case has no Android counterpart; `values-zh-rTW` re-checked
after the media/brand merges — no simplified glyphs, the 30 Latin runs are product names (Prime Minutes,
YouTube, Podcast, Markdown, Nemotron, token), 601 keys in all three catalogs with identical key sets;
brand `Image`s (`StudioScreen.kt:347,496`, `EmptyState.kt:151`) are all `contentDescription = null`
inside labelled rows.

**Verification:** `./gradlew :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx3g` (laptop) → first run
failed on a duplicated import pair (fixed), second run `BUILD SUCCESSFUL in 44s`, warnings unchanged
(the pre-existing `Icons.Filled.Send` deprecation). Worktree `/tmp/pm-ux7` from `origin/android`
(`96aeaa15`), rebased (already current), pushed `4c14e49b`, removed. No emulator run: the auto-keyboard
and the 250 ms delay are owed a device pass.

---

## 2026-10-21 (11) — iOS pass 6 (branch `ios`, commit `fc2f9c65`)

Design sweep on the suggested axes beyond what the earlier passes covered: sheet affordances, focus
order, `.contextMenu` on key lists, empty-state copy, progress affordances, Dynamic Type at AX sizes,
dark-mode colour tokens.

**Implemented (2):**

| Severity | Site | Finding | Change |
|---|---|---|---|
| Med | 9 sheets (`LibraryView.swift:385`, `EmailParticipants.swift:229`, `SessionView.swift:417/463/479/495/509`, `VoiceViews.swift:74`, `PeopleViews.swift:391`) | every sheet in the app declares `.presentationDetents([.medium, .large])` and none shows a grabber — a half-height sheet (export, mail, Ask, people picker, enrol) gave no hint it can be dragged to full height, and the text list inside it is clipped at the fold | `.presentationDragIndicator(.visible)` on all nine (deployment target is iOS 17, so the modifier is available everywhere detents are used) |
| Med | `Ask.swift:405` composer | the Ask sheet opens on the answer list; the question field — the reason the sheet exists — needed a tap before the keyboard appeared, including from the library capsule where typing is the whole intent | `@FocusState` + `.focused($composer)`, focused 0.4 s after the sheet settles (the composer sits in a `safeAreaInset`, so the keyboard lifts it; the 0.4 s matches the sheet-transition waits already used in this file) |

**Swept, nothing to change:**
- *Fixed font sizes*: only two in `ios/App` — the 76 pt capture timer (intentional, carries
  `a11y_elapsed` since pass 4) and the 11 pt seam grip glyph.
- *Fixed frame heights*: every `.frame(height:)` is a bar, rule or spacer (agent strip 6 pt, meter 3 pt,
  talk-time bar 8 pt, boundary rule 1.5 pt) — none clips text. `SessionView.swift:193` already carries
  the `fixedSize()` fix for a clipped time.
- *Progress affordances*: import / job / summary / index paths pair a `ProgressView` with text
  (`LibraryView.swift:189,274,307,481-483`, `PodcastView.swift:34,68`, `VoiceViews.swift:55,94`,
  `SessionView.swift:575` exporting sheet); decorative spinners are `accessibilityHidden`.
- *Empty states*: library (`empty_headline` / `empty_subtitle` / `library_empty`), people picker
  (`people_picker_empty`), a person with no meetings (`people_no_meetings`), live capture (聆聽中…).
- *Colour tokens*: `Color.white` appears only as ink on the saturated system accent (library filter chip
  `LibraryView.swift:128`, people contact chip `PeopleViews.swift:424`) — the exemption logged in iOS
  pass 1 (system accent is saturated, Android's `OnSky` case does not apply); the one RGB literal
  (`LibraryView.swift:575`) is the brand mark's navy fallback under the bundle icon, hidden from
  VoiceOver — not text ink.
- *`.contextMenu`*: the transcript rows carry one (`SessionView.swift:359`). Library rows were left
  alone — their long-press is selection mode (`action_select` in pass 3), and a context menu would
  steal that gesture.

**Verification:** tree-sitter Swift parse — `EmailParticipants.swift`, `SessionView.swift`,
`VoiceViews.swift`, `PeopleViews.swift` clean; `Ask.swift` reports 3 of its 6 baseline grammar
false positives, `LibraryView.swift` its 4 baseline nodes (positions unchanged: the sheet edits are
inline) — no new parse errors. No simulator, no Mac build (no `~/work/.maclock`): the grabbers, the
auto-keyboard, and the Ask focus timing are owed a device pass.

**Open item raised:** Android's Ask sheet has no `FocusRequester` either — the same auto-focus is worth
doing on the Compose side (Android open list is otherwise closed). **Done 2026-10-21 Android pass 7**
(`4c14e49b`).

---

## 2026-10-21 (10) — Android pass 6 (branch `android`, commit `9c4df28a`)

Closes the Android open item 11 (raised by iOS pass 5) and one parity gap the iOS pass 2 log left.

| Severity | Site | Finding | Change |
|---|---|---|---|
| Med | `SpeakerStatsPanel.kt:66` | the tap that opens the per-speaker legend carried no state: the chevron is `contentDescription = null` (correct) and the count carries no state, so TalkBack said「3 位語者 … 雙擊即可」with no hint of what the tap does | `.semantics { stateDescription = ... }` on the clickable Column, reading `show_more` / `show_less` (existing keys, all three catalogs) — the iOS `edc14bce` equivalent |
| Med | `MainActivity.kt` summary / action-items / transcript copy | the four clipboard paths on iOS got a success haptic in iOS pass 2; Android's three in-meeting copies had only the snackbar, which is easy to miss on a long transcript | `HapticFeedbackType.ContextClick` on each copy, right after `setPrimaryClip` |

**Scan, nothing else small:** icon-only controls all labelled (M3 `Icon` makes `contentDescription`
mandatory, and every decorative glyph is already `null` inside a labelled row), no `IconButton` under
40 dp left after passes 1–4, no simplified glyphs in `values-zh-rTW`.

**Skipped:** the mail sheet's address-copy fallback (`EmailParticipantsFlow.kt:215 copyAddresses`) —
it is a plain function on a `scope.launch` path (no composable scope for `LocalHapticFeedback`), and it
already fires a Toast; adding haptics there means threading a `HapticFeedback` through the sheet.

**Verification:** `./gradlew :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx3g` (laptop) → `BUILD
SUCCESSFUL in 40s`, warnings unchanged (pre-existing icon deprecations only). Worktree `/tmp/pm-ux6`
rebased onto `origin/android` (already current), pushed `9c4df28a`, removed. No emulator run: the
TalkBack phrase and the copy haptic are owed a device pass.

---

## 2026-10-21 (9) — iOS pass 5 (branch `ios`, commit `edc14bce`)

Lean scan of the README-facing chain — capture → summary → export — plus Ask and People, looking for
label noise, glyph-only state and copy nits. Two found, both in the same species as pass 2's SF
Symbol noise (SF Symbols ship their own VoiceOver names, so a decorative glyph is announced out loud).

| Severity | Site | Finding | Change |
|---|---|---|---|
| Med | `SessionView.swift` `SpeakerStats` | the talk-time row is a tap target for the per-speaker legend, and VoiceOver read「3 位語者 … Chevron Up」— the glyph's name was pure noise, and the chevron's real message (a tap does something, and what) had no audio equivalent | glyph `.accessibilityHidden(true)`; the element gained `.accessibilityValue(L(legend ? "show_less" : "show_more"))`, so it reads「3 位語者，顯示更多，按鈕」(existing keys, no new copy) |
| Low | `CaptureView.swift:31` | the live screen's empty state announced「Microphone 聆聽中…」— the mic glyph is decoration under the「聆聽中…」line | `.accessibilityHidden(true)` |

**Verification:** tree-sitter Swift parse — `SessionView.swift` and `CaptureView.swift` both clean (no
ERROR nodes; the repo-wide baseline of 14 false positives sits in `LibraryView.swift`, untouched). No
new strings, so no catalog change (589 keys per catalog unchanged). No simulator, no Mac build: the
two VoiceOver phrasings are owed a device pass.

**Scanned, nothing to change:** `Ask.swift` (send / cancel / clear / reset all labelled), `Export.swift`
and `ExportSheet` (text-labelled format buttons, copy path already haptic + toast), the session toolbar
(search, More menu, ShareLink), `PeopleViews.swift` (play-sample, clear ✕, picker, merge/delete alerts,
row glyph already hidden), `CaptureView`'s two control buttons (both `Label` + `cd_next_talk`).

**Open Android finding (new, 1):** `SpeakerStatsPanel.kt` has the same row — its chevron is already
`contentDescription = null`, but the `clickable { showLegend = !showLegend }` Column carries no state,
so TalkBack cannot say what the tap does. Candidate fix mirrors iOS: `.semantics` value naming
show-more / show-less (or `onLongClickLabel`-style labelling), next Android round.

---

## 2026-10-21 (8) — Android pass 5, final (branch `android`, commit `7b5a9393`)

All open lists were closed, so this pass was a targeted polish scan only: `contentDescription`
duplication, sub-44 dp targets, simplified characters in `values-zh-rTW/strings.xml`.

**Scan results:** every `IconButton` is ≥40 dp (3 × 18/20 dp hits were the inner glyphs); clickable
rows all sit at `heightIn(min = 44.dp)`; every icon-only control has a label and every icon inside a
labelled row has `contentDescription = null`; the zh-TW catalog contains no simplified-only glyphs
(strict scan over 录/语/义/声/图/书/写/筛/选/关/开/传/转/识/证/议/机/纸/线/专/业/实/现/权/产/观/点/与…)
and no English runs; SessionRow's status glyph is already `null` with the text in `rowDesc`.

Two nits found and fixed:

| Severity | Site | Finding | Change |
|---|---|---|---|
| Low | `PersonContact.kt:192` | the default-address ★ carried `contentDescription = email_default` while the line above it renders the same string — TalkBack announced 寄信時預設使用 twice | star is decoration now (`contentDescription = null`) |
| Low | `EmailParticipantsSheet.kt` one-off recipient | the Remove button announced only「移除」— no indication of what is removed | label is「移除 · <name or address>」 (no new key) |

**Verification:** `./gradlew :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx3g` (laptop) → `BUILD
SUCCESSFUL in 40s`. Worktree `/tmp/pm-ux5` rebased onto `origin/android` (already current), pushed
`7b5a9393`, removed. No emulator run: the two TalkBack phrases are owed a device pass.

---

## 2026-10-21 (7) — iOS pass 4 (branch `ios`, commit `e6824fa3`)

Closes iOS open items 2 and 3.

| Severity | Site | Finding | Change |
|---|---|---|---|
| Med | `EmailParticipants.swift` recipient row | the checkbox for a person with no address is `disabled(r.address == nil)` — the fact that this recipient cannot be picked was carried by the glyph going grey only | `accessibilityValue` now covers it: `a11y_unavailable`「無法選用」when there is no address, `a11y_checked` / `a11y_unchecked` otherwise |
| Med | `CaptureView.swift:17` | the 76 pt recording timer is a fixed display face (intentional: read from across a table) and VoiceOver read it digit by digit ("oh oh colon one two colon …") | `accessibilityLabel(L("a11y_elapsed", mmss))` —「已錄 12:34」; the 76 pt face stays, this row is meant to be seen |

**New copy:** `a11y_unavailable`, `a11y_elapsed` in en / zh-Hant / zh-Hans — 589 keys per catalog,
identical key sets. zh-Hant traditional-only.

**Verification:** tree-sitter Swift parse — `EmailParticipants.swift` and `CaptureView.swift` both
clean (no ERROR nodes). No simulator, no Mac build: the two VoiceOver phrases are owed a device pass.

**Scanned, nothing small left:** the only sub-44 frames on iOS are decorative glyphs inside
controls that are already 44 pt (the seam grip inside the 44 pt seam button, the job-status icon
inside its row, the agent log's 20 pt glyph).

---

## 2026-10-21 (6) — Android pass 4 (branch `android`, commit `a88539a1`)

Fresh review pass over Studio / Session / People / Settings / Import after the audit list closed.
Every finding this tick was the same species: **a control in a dense row under the 48 dp touch
minimum** — the first three passes fixed the `IconButton` boxes, these are the hand-sized rows.

| Severity | Site | Finding | Change |
|---|---|---|---|
| High | `BoundaryEditor.kt:88` `SpeakerSeam` | the seam is the only entry to the boundary editor and sat at `height(28.dp)` in a scrolling transcript — a 28 dp band a thumb lands on by luck | `height(44.dp)` (iOS `SpeakerSeam` is 44 pt) |
| High | `TranscriptSearch.kt` ×6 | the in-meeting search bar's ≈, prev, next, results-toggle, Ask and close were all `size(32.dp)` | `size(40.dp)`; the text field is `weight(1f)`, so the extra 40 dp is absorbed by the field — no overflow, the row has no fixed height |
| Med | `StudioScreen.kt:409` Ask capsule | the library's Ask capsule (primary action under the search field) `heightIn(min = 32.dp)` | `min = 44.dp` — matches the iOS Ask capsule's 44 pt |
| Med | `StudioScreen.kt:980` `FilterChip` | the status and person filter chips `heightIn(min = 32.dp)`, the main filter affordance of the library | `min = 44.dp` (48 dp Material minimum; 44 chosen so the two chip rows keep their shape) |
| Med | `UpdateBanner.kt:74` | banner dismiss ✕ at `size(28.dp)` | size override dropped → `IconButton`'s 48 dp box |

**Verification:** `./gradlew :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx3g` (laptop) → `BUILD
SUCCESSFUL in 39s`, no new warnings. Worktree `/tmp/pm-ux4` rebased onto `origin/android` (already
current), pushed `a88539a1`, removed. No emulator run: the seam's density in a long transcript, the
search bar's fit at 360 dp width, and the two chip rows' height are owed a device pass.

**Scanned, nothing to fix:** hardcoded UI literals (only `name@example.com` placeholders),
`contentDescription = null` on icons (every hit is an icon inside a labelled row — the label
carries it; the icon-only controls all have labels), zh-TW catalogs (no English runs, no
license wording), `.imePadding()` (all text-field sheets padded), destructive confirms (all covered
in pass 2).

**Noted, not changed:** `GradientButton` disabled = Slate400 on Slate700 (≈ 3.7:1 with bold text,
disabled states exempt); e-ink speaker-hue separation; `AiBenchmark`'s English output lines
(`BuildConfig.DEBUG` only, not shipped copy).

---

## 2026-10-21 (5) — iOS pass 3 (branch `ios`, commit `91653aac`)

Closes iOS open items 1, 4 and 5 — the parity items the Android passes left behind.

### 1. Unconfirmed destructive merge (HIGH)

Both merge paths ran on the first tap (`PersonContact.swift:93` via `PeopleViews.swift:325`, and the
"Merge into…" sheet's row). They now stop for a confirm built from the already-localized
`people_merge_pick` + `people_merge_text` + `contact_merge`, mirroring Android `253cb2c9`.

The confirm lives in two states, not one (`mergeConfirm` on the page, `pickConfirm` inside the
sheet's `NavigationStack`): an alert attached to the page behind a sheet that is dismissing gets
swallowed by UIKit, so the picker's confirmation is attached inside the sheet and closes the sheet
only on the destructive tap.

### 2. Clear buttons labelled "Cancel"/"Close search" (MED)

| Severity | Finding | Change |
|---|---|---|
| Med | `PeopleViews.swift:83` — the people-search clear ✕ was labelled `L("cancel")` (取消) | `L("cd_clear_search")`「清除搜尋」(key added; Android's `cd_clear_search` is the same copy) |
| Med | `LibraryView.swift:247` — the library search's clear ✕ (clears the field, keeps the search row open) was labelled `L("search_close")`「關閉搜尋」 | same key; the transcript search's ✕ keeps `search_close`, because it really does close the search |

### 3. Selection mode was long-press only (MED)

The library row's Manage menu now leads with「選取」(`action_select`), the same entry Android shipped
in `034ce829`; long-press and the `accessibilityAction(named: select)` stay as they were.

**New copy:** `cd_clear_search`, `action_select` in en / zh-Hant / zh-Hans — 587 keys per catalog,
identical key sets. zh-Hant stays traditional-only; no license/open-source wording.

**Verification:** tree-sitter Swift parse — `PeopleViews.swift` clean; `LibraryView.swift` reports
the same 4 ERROR nodes as its HEAD baseline (grammar false positives, shifted +2 lines by the
2-line menu insert), i.e. no new parse errors. No simulator, no Mac build (no `~/work/.maclock`):
the two alerts, the sheet-internal confirm, and the menu item are owed a device pass.

**Skipped this tick:** iOS 76 pt fixed-size timer (`CaptureView.swift:17`); the mail rows' disabled
checkbox state (colour only); the library filter chips' `Color.white` on system blue (≈ 4.3:1,
system accent is saturated — the Android `OnSky` case does not apply).

---

## 2026-10-21 (4) — Android pass 3 (branch `android`, commit `034ce829`)

Closes open item 5 (the library no-match dead end) and the two affordance items the earlier passes
listed as skipped.

### 1. No-match was a dead end (HIGH)

A search plus filters that matches nothing showed only「沒有符合搜尋的會議。」— the way out was
deleting the query by hand and hunting the filter chips behind an empty list. The empty state now
offers「清除搜尋與篩選」(`studio_clear_filters`, `testTag("studio-clear-filters")`) whenever a query,
the status filter, or a person filter is active; it resets all three. (The indexing-in-progress and
searching states keep their own copy and show no button.)

### 2. Selection mode was long-press only (MED)

`onLongClickLabel` named the gesture for TalkBack, but nothing visible advertises a long-press, and
a mouse/stylus has no long-press path at all. The row's action sheet (Manage) now starts with
「選取」(`action_select`), which enters selection mode with that row picked.

### 3. A clear button labelled "Cancel" (MED)

`PersonPicker.kt:153`'s trailing ✕ in the people search field was announced as 「取消」; it is now
`cd_clear_search`「清除搜尋」. (iOS `PeopleViews.swift:83` has the identical label bug — logged as
open on the iOS side.)

**New copy:** `studio_clear_filters`, `action_select`, `cd_clear_search` added to en / zh-TW / zh-CN
in one pass — 590 keys per catalog, XML-valid, identical key sets. zh-TW stays traditional-only; no
license/open-source wording touched.

**Verification:** `./gradlew :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx3g` (laptop) → `BUILD
SUCCESSFUL in 47s`, no new warnings on the touched files (only the pre-existing `PlaylistPlay`
deprecations). Worktree `/tmp/pm-ux3` rebased onto `origin/android` (already current), pushed
`034ce829`, removed. No emulator run: the new button, the action-sheet entry, and the TalkBack
label are owed a device pass.

**Skipped this tick:** `AiBenchmark`'s English output lines (debug-only panel, `BuildConfig.DEBUG`,
not shipped copy); disabled ink on the accent (`GradientButton` disabled = Slate400 on Slate700
≈ 3.7:1 with bold text — exempt, not worth a token); e-ink speaker-hue legibility; iOS merge-confirm
parity (iOS round).

---

## 2026-10-21 (3) — iOS pass 2 (branch `ios`, commit `37920031`)

Audited `ios/App` for what the Android passes exposed: the copy-to-clipboard paths, glyph-only
state, and SF Symbols' built-in VoiceOver names.

### 1. Copy actions had no feedback beyond a toast (MED)

Export fires a completion haptic (pass 1), the four clipboard paths did not: copy summary, copy
action items, copy transcript, and the mail sheet's "addresses copied" fallback. All four now call
`Haptics.success()` — the moment the text is provably in the pasteboard, matching the export haptic.

### 2. Glyph-only state, invisible to VoiceOver (HIGH)

| Severity | Finding | Change |
|---|---|---|
| High | Mail recipient checkbox: `checkmark.square.fill` / `square` carries who is included, and the button had no `accessibilityLabel` at all — VoiceOver announced "Button" | `.accessibilityLabel(r.person.name)` + `.accessibilityValue(checked ? a11y_checked : a11y_unchecked)` (2 keys added to en / zh-Hant / zh-Hans) |
| Med | The mail sheet's one-off recipient used a **checked box** as its Remove button: pressing the box deletes the address, and the box read as a selection (the Android side had the same bug in a checkbox's `onCheckedChange`, fixed today) | glyph → `xmark.circle`, label `email_remove` unchanged; 44 pt hit box kept |
| Med | SF Symbols ship their own accessibility labels, so decorative glyphs are announced as noise: the paused-download row reads "Pause circle" before its text, the job header row "Exclamation mark"/"Clock", the Add-audio rows their icon names, the People search field and person rows "Magnifyingglass"/"Person" | `.accessibilityHidden(true)` on those decoration images (job status line, Add-audio rows, transcript-search + People search glasses, person row glyph, the small progress spinner) |

**Verification:** tree-sitter Swift parse (`/home/luigi/tmp/parsecheck/check.js`): `SessionView.swift`,
`EmailParticipants.swift`, `PeopleViews.swift` parse clean; `LibraryView.swift` reports the same 4
ERROR nodes as its HEAD baseline (grammar false positives, same lines, positions unchanged) — no
new parse errors. The three catalogs parse with 585 keys and identical key sets. zh-Hant scan
finds no English fragments and no GPL/open-source copy. No simulator run and no Mac build (no
`~/work/.maclock` taken): the VoiceOver announcements and the haptics are owed a device pass.

**Skipped this tick:** the `Color.white` on `Color.accentColor` in the library filter chips
(system blue ≈ 4.3:1 — the Android `OnSky` fix is about a light accent, not applicable here);
`CaptureView`'s 76 pt timer stays a fixed display face (Dynamic Type would break the row);
merging on iOS still has no confirm dialog — parity item, logged as open.

---

## 2026-10-21 (2) — Android pass 2 (branch `android`, commit `253cb2c9`)

The previous entry's skipped list, minus item 5 (library no-match dead end). Audit items 7, 8, 9
and the copy half of 10. Worktree `/tmp/pm-ux2`; no `MainActivity.kt` / `TranscriptSearch.kt`
touch, so nothing collides with the search work.

### 1. Destructive actions firing on the first tap (HIGH)

| Severity | Finding | Change |
|---|---|---|
| High | Two merge paths ran with no confirm — the contact card's `contact_merge` button (`PersonContact.kt:112` via `PeopleScreen.kt:203`) and picking a target in the "Merge into…" dialog: a person record loses its name, note, contact link and voice samples and cannot be un-merged | both paths now set `mergeTarget` and open a confirm dialog (`people_merge_pick` + `people_merge_text`, both already localized); the merge runs from its confirm button only |
| High | Download item's screen: Delete → `vm.deleteEntries(...)` on the first tap, while the library row's delete of the same entry confirms first (`delete_confirm`) | `ItemDownloadCard` now opens the same dialog (`action_delete` / `delete_confirm` / `cancel`) |
| High | Mail sheet's one-off recipient: its `Checkbox(checked = true, onCheckedChange = { extras.remove(...) })` deleted the recipient when un-ticked — a control that reads as a selection | row ends with a labelled `Remove` icon button (`email_remove`, 44 dp box); the address is only dropped by pressing it |

### 2. Long-press with no screen-reader label (MED)

Long-press on a library row is the only entry to selection mode, and `combinedClickable` was
called without `onLongClickLabel`, so TalkBack announced the row and nothing about the second
gesture. `SessionRow` now passes `onLongClickLabel = cd_start_selection` ("Select this meeting" /
「選取此會議」/「选取此会议」) — one place covers both call sites.

### 3. Keyboard over the input field (MED)

`YouTubeSheet` and `PodcastSheet` (its field lives in `PodcastPanel`) built their sheet `Column`
without `.imePadding()`; Ask / Mail / PersonPicker already had it. Both now pad for the IME.

### 4. Copy (MED)

zh-TW (and zh-CN) `voice_me_script` ended with a full English sentence after the Chinese one;
`about_license` read 「自由開放原始碼 · GPL-3.0」 on the settings page and `about_components`
「開放原始碼元件」. Both now match the wording iOS shipped the same day: 「錄音與文字只留在這支手機」
/「授權元件」 (en: "Your recordings and text stay on this device" / "Licensed components").
A scan of `values-zh-rTW/strings.xml` for runs of English words is now clean.

**Verification:** `./gradlew :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx3g` → `BUILD
SUCCESSFUL in 24s`; no new warnings on the 6 touched Kotlin files. All three string catalogs
parse as XML with 587 keys and identical key sets (new key added to all three). Pushed
`253cb2c9` onto `origin/android` after rebase (already up to date). No on-device pass: the
confirm dialogs, the long-press label under TalkBack, and the IME insets in both sheets are owed
a device check.

**Skipped this tick:** item 5 (no-match dead end — the other agent's `search_no_matches` area),
disabled-state ink on `pal.Sky`, e-ink speaker hues, `AiBenchmark` English literals,
`PersonPicker.kt:153` clear-search icon labelled 「取消」, and the library-row long-press
*discovery* problem behind item 8 (nothing visible hints that long-press selects).

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

## Open Android findings (audited 2026-10-20; all eleven implemented 2026-10-21 — item 11 raised by iOS pass 5, closed by Android pass 6)

Highest-value, from the Compose audit (files on branch `android`). Struck items are fixed,
numbering kept so the entries refer to them.

1. ~~High — `SessionComponents.kt:545,424,469` `IconButton(Modifier.size(28.dp))` edit/copy
   icons.~~ **Done 2026-10-21** (`12563607`), with the transport buttons raised too.
2. ~~High — `AskSheet.kt:238` send button 32 dp.~~ **Done 2026-10-21** (`12563607`).
3. ~~High — `SessionComponents.kt:796-803` active-segment ring drawn `Color.White` at 35 % alpha on
   the white light/e-ink card ground → invisible.~~ **Done 2026-10-21** (`12563607`).
4. ~~High — `SessionComponents.kt:984-989` speaker chip `lineHeight = 12.sp` frozen while glyph size
   scales → CJK clipping at large text sizes.~~ **Done 2026-10-21** (`12563607`).
5. ~~High — library no-match state is a dead end (no "clear filters / clear search" affordance).~~
   **Done 2026-10-21 pass 3** (`034ce829`, `studio_clear_filters`).
6. ~~High — `Color.White` ink on `pal.Sky` (filter chip, export format, selection tick, mail chip)
   needs an `OnSky` token (dark theme accent ≈ 2.6:1).~~ **Done 2026-10-21** (`12563607`,
   `OnSky = Slate900`).
7. ~~High — destructive without confirm: delete downloaded model (`ItemDownload.kt:83`), people
   merge (`PeopleScreen.kt:203,261`); un-ticking a mail recipient deletes it silently
   (`EmailParticipantsSheet.kt:192`).~~ **Done 2026-10-21 pass 2** (`253cb2c9`) — confirm dialogs
   on both merge paths and the download delete, labelled Remove button for the mail recipient.
8. ~~Med — library row long-press (the only entry to selection mode) has no `onLongClickLabel`.~~
   **Done 2026-10-21 pass 2** (`253cb2c9`, `cd_start_selection`).
9. ~~Med — `YouTubeSheet.kt:94`, `PodcastSheet.kt:26` missing `.imePadding()` (keyboard covers the
   field).~~ **Done 2026-10-21 pass 2** (`253cb2c9`).
10. ~~Med — `values-zh-rTW/strings.xml:530` `voice_me_script` mixes English; `about_license` GPL line
    on the settings page; `PersonPicker.kt:153` clear-search icon labelled "取消".~~ **Done
    2026-10-21 passes 2–3** (`253cb2c9` copy, `034ce829` `cd_clear_search`).
11. ~~Med — `SpeakerStatsPanel.kt:66` the tap that opens the per-speaker legend carries no state: the
    chevron is `contentDescription = null` (correct) and the count is read, but nothing names what the
    double-tap does. iOS shipped the equivalent in `edc14bce` — a `semantics` value naming show-more /
    show-less.~~ **Done 2026-10-21 Android pass 6** (`9c4df28a`, `stateDescription` from `show_more` /
    `show_less`, all three catalogs).

---

## Open iOS findings (audited 2026-10-21; all five implemented 2026-10-21)

1. ~~High — unconfirmed destructive merge ...~~ **Done 2026-10-21 iOS pass 3** (`91653aac`):
   confirm alerts on both paths (`mergeConfirm` on the page, `pickConfirm` inside the picker sheet).
2. ~~Med — the mail sheet's person rows toggle through a checkbox whose `disabled(r.address == nil)`
   state is carried by colour only.~~ **Done 2026-10-21 iOS pass 4** (`e6824fa3`, `a11y_unavailable`).
3. ~~Med — `CaptureView.swift:17`'s elapsed timer is a 76 pt fixed face.~~ **Done 2026-10-21 iOS
   pass 4** (`e6824fa3`): the face stays (it is meant to be seen from across a table), the element now
   carries `a11y_elapsed` so VoiceOver says 已錄 12:34 instead of the digits.
4. ~~Med — `PeopleViews.swift:83` clear labelled `L("cancel")`.~~ **Done 2026-10-21 iOS pass 3**
   (`91653aac`, `cd_clear_search`; also `LibraryView.swift:247`, whose label said 關閉搜尋).
5. ~~Med — the iOS Manage menu has no visible select entry.~~ **Done 2026-10-21 iOS pass 3**
   (`91653aac`, `action_select` leads the row's Manage menu).

---

## Device validation 2026-10-21 — semantic-index degrade fix (`8f8d4e3d`)

Validated on the Note10+ (isolated debug app `studio.primeminutes.androidtest`, build from
`origin/android` `c352ee68`): 5 cycles on session `20261021-024959` — degrade logs
`primeminutes-search: index missing, keyword-only for now, index requested`, the 「索引建立中…」
bar and 「語意索引建立中，稍後即可搜尋重點」 row appear, then `primeminutes-index: indexed …
10 vectors` → `index ready … semantic search re-run`: semantic hits (≈ 1:00/1:10) appear **by
themselves**, no re-typing. Main app `studio.primeminutes` untouched. Screenshots
`/tmp/fixchk/{t1,t3,u2}.png`, logs `/tmp/fixchk-shots/`.
