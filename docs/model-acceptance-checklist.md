# New model acceptance checklist

Run this before a new AI notes model (new weights, a re-export, a new model card or a new system
prompt) reaches users, on **both** platforms. Every step names the tool or test that already exists;
a step that cannot be run is a reason to hold the release, not to skip it.

The harness is model-agnostic: everything model-specific lives in the registry entry and the
model card. If a step below needs a code change outside those two places, stop and fix the harness
first.

| Where | Android | iOS |
|---|---|---|
| Registry (files, sizes, sha256, revision) | `app/src/main/java/studio/primeminutes/core/models/LlmRegistry.kt` | `ios/App/ModelStore.swift` (`ReaderModel.e2b` / `.e4b`) |
| Model card (template, sampler, budgets, protocol, prompts, guard) | `app/src/main/resources/model_cards/<name>.json` | `ios/App/Resources/model_cards/<name>.json` (byte-identical copy) |

## 1. Artifacts pinned, revision everywhere

- [ ] Every file is fetched from a Hugging Face **commit** URL (`resolve/<40-hex commit>`), never `main`.
- [ ] Every file has its exact byte size **and** sha256 in the registry, on both platforms, and the two
      registries agree (same commit, sizes and hashes).
- [ ] The system prompt (`system_prompt.txt`) is pinned with the weights it was trained with (same commit).
- [ ] A new revision of an existing id changes the pinned revision string: the `.revision` marker
      (`ModelManager.REVISION_MARKER` / `ModelStore.revisionMarker`) then forces a clean re-download and
      drops the stale XNNPACK weight cache. Check on a phone that already had the old files.
- [ ] Sizes shown to users come from the registry through the single formatter (`SizeFormat` /
      `SizeFormat.bytes` on iOS): the first-launch download prompt, Settings and Storage show the new total.
- [ ] The card is copied byte for byte to iOS: `cmp app/src/main/resources/model_cards/<name>.json ios/App/Resources/model_cards/<name>.json`.

## 2. Static tests (host, no model)

- [ ] `./gradlew :app:testDebugUnitTest` green, in particular:
  - `ModelCardTest.budgetsFitTheContextAndTurnsCanStop`: `ctx_budget` ≤ the graph's `maxCtx`, a window
    (2 × `window_tokens` + `read_max` + 600) fits `ctx_budget`, the restart journal fits, prose/title/Ask
    budgets are below the context, `stop_ids` is not empty and the reader has a stop string.
  - `ModelCardTest.shippedCardKeepsTheTrainedValues`, `ChatTemplateTest` (template pieces and the
    **stop ids**: check them against the new tokenizer's ids for end-of-turn / eos).
  - `ReaderGuardTest` (the card's `reader.guard`: `known_tags` = the tags the model was trained to write).
- [ ] iOS: `ios/tests/run.sh` on a macOS host (reader parity + keywords).

## 3. Host protocol replay

- [ ] `ReaderParityTest` (Android) and `ios/tests/ReaderParityTests.swift` replay the golden conversations
      of `tools/reader-parity/make_golden.py` (upstream `eval/phone_live.py` against a fake model) through
      `MeetingReader`: the conversation must match byte for byte with the new card. A protocol change in
      the card (headers, acts, note grammar, budgets) needs a new golden from upstream, never a hand edit.
      Mobile protocol: `make_golden.py --mobile`, fixture `reader_parity_mobile.json`.
- [ ] Optional, real weights on the host: the app's own Kotlin reader and Ask over the host build of
      the engine (`tools/quality-eval/build_host_jni.sh`, local tool) to iterate before using a phone.

## 4. Quality gate, paired against the current model

- [ ] `ReaderRegressionGate` (androidTest, real weights on a phone) for the **current** model and the
      **candidate**, same phone, same build, one after the other:

      adb shell am instrument -w -e class studio.primeminutes.ReaderRegressionGate \
        -e llm <LlmRegistry id> <test pkg>/androidx.test.runner.AndroidJUnitRunner

  Reports: logcat `ReaderGate`, `files/reader_gate/<meeting>-<model>.json`.
- [ ] The candidate states at least the gate's threshold of key facts on every reference meeting
      (`move`, `weiya`) **and** is not worse than the current model meeting by meeting (paired, not
      averaged). Record both reports in the release notes of the change.
- [ ] Guardrail counts (`ReaderGuard`, logcat `primeminutes-reader` "guard:"): the candidate's
      off-protocol rate stays under the card's `max_off_protocol_rate` on the gate meetings, and no
      "guard: off-protocol output above" warning appears. A new tag the model writes on purpose goes
      into `known_tags`, with the minutes section that shows it.

## 5. Ask suite

- [ ] Unit: `AskRetrievalTest`, `AskCitationTest`, `ChineseAmountsTest` green.
- [ ] Device: ask the standard questions on the test library (DEV hook: `--es vox_ask "<question>"` on
      Android, `VOX_ASK` on iOS), in Traditional Chinese, Simplified Chinese and English: every claim
      cites an excerpt it was shown, no invented citation, "not mentioned" when the library does not
      say it. `KeywordBench` (androidTest) for the keyword pass.

## 6. Device: memory, lag, Ask latency

On the reference phones (Android: Note10+ test app; iOS: iPhone 14 Pro Max; plus the smallest
supported RAM class for the model, E4B only on 8 GB phones):

- [ ] Peak RSS of ASR + diarizer + reader during a live recording leaves the phone usable (no
      low-memory kill over a 60-minute meeting). Settings → AI benchmark (debug builds,
      `ComponentBench`), `MfaEngineDeviceTest`, the HW status line; iOS `VOX_MFABENCH`.
- [ ] Live lag after each window (AI notes panel, detailed view) no worse than the current model.
- [ ] Ask answers in **≤ 20 s** from the question to the end of the answer, model already loaded
      (logcat `primeminutes-ask` "answered in … ms"; iOS `status.log`). The Ask sheet shows the stage
      and the elapsed seconds while waiting.

## 7. Rollout

- [ ] Ship the candidate under a **new registry id**; keep the old id in `LlmRegistry.ALL` / iOS
      `ReaderModel` so it stays selectable in Settings → AI notes model, and do not delete its files on
      update (`reclaimRetiredLlm` deletes only the folders listed in `RETIRED_LLM_DIRS`).
- [ ] Change `DEFAULT_ID` (iOS `Prefs.readerId` default) only after the gate and the device checks pass
      on both platforms; meetings record which model wrote their notes (session `llmModelId` / `reader`),
      so an "outdated summary" can be re-summarized with either.
- [ ] Retire the old id one release later, once nobody needs to go back.
