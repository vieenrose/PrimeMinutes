# In-meeting search and Ask

Status: implemented 2026-10-17 (Android + iOS). Scope: search and Ask restricted to **the meeting
on screen** — one library item, its live view included while it is processed.

## 1. What exists and what is reused

| Piece | Global (library) | In-meeting (this doc) |
|---|---|---|
| Keyword match | `LibrarySearch.lexical` (title, summary, notes) | the open meeting's lines and notes, in memory (live view too) |
| Meaning match | `LibrarySearch.dense` over every `index.bin` | the same call on **one** entry |
| Ask retrieval | dense + BM25 → meeting routing → `fuse` → `select` | dense + BM25 on one entry → `fuse` → `select` (**no routing**) |
| Citations | `[[k]]` → `[[id@sec]]`, `cite`, dedupe, collapse | unchanged; the chip shows `m:ss` only (the meeting is known) |
| Answer language | `answerLanguage(question, ui)` | unchanged: the question's language |

Nothing model-specific is added: the prompt, template, sampler, budgets and the
answer-language words all come from the model card as for the global Ask
([harness-model-agnostic]). The in-meeting Ask is the global Ask with a narrower corpus.

## 2. Entry points

```
┌──────────────────────────────────────────────┐
│ ←  Office move kickoff        🔍  ⋮          │  ⋮ → "Ask this meeting"
│ Summary │ Transcript                          │
├──────────────────────────────────────────────┤
│ 🔍 budget            ≈  2 / 7   ˄ ˅  ✦  ✕    │  ✦ = Ask this meeting (the query as question)
│ ┌──────────────────────────────────────────┐ │
│ │ NOTES                                    │ │
│ │ 4:12  Budget for movers capped at 80k    │ │  tap → transcript at 4:12, line highlighted,
│ │ TRANSCRIPT                               │ │        player parked there (paused)
│ │ 4:05  Mark  …so the budget for the mo…   │ │
│ │ 9:40  ≈ Anna  we can't spend more than…  │ │  ≈ = found by meaning only
│ │ ✦ Ask this meeting: "budget"             │ │
│ └──────────────────────────────────────────┘ │
│ 4:05 Mark   …so the **budget** for the movers │
└──────────────────────────────────────────────┘
```

```
┌──────────────────────────────────────────────┐
│ Ask this meeting                Experimental │
│ Office move kickoff                       ↺  │
│ ── Earlier ───────────────────────────────── │
│ Who books the truck?                         │
│ Anna books it by Friday [3:12]               │
│ ──────────────────────────────────────────── │
│ What is the movers' budget?                  │
│ Capped at 80,000 [4:12] [9:40]               │  [m:ss] → closes the sheet, transcript there
│ Sources  4:05 · 4:12 · 9:40                  │
│ [ Follow-up question…                    ➤ ] │
└──────────────────────────────────────────────┘
```

- Search: the existing search icon of the meeting screen. The bar keeps its stepper (˄ ˅) and
  in-line highlight; a **results list** under it shows notes first, then transcript lines, in
  meeting order, each with its time; ≈ marks a passage found by meaning only.
- Ask: ✦ in the search bar (asks the typed query, or opens on an empty field), the last row of the
  results list, and "Ask this meeting" in the ⋮ menu.

## 3. Behaviour and defaults (chosen, not asked)

1. **Results** — at most 50 rows (notes then lines), meeting order; keyword hits first decide the
   stepper, meaning-only hits are added when ≈ is on (the toggle stays as before: off by default).
   The list folds after a tap; the list icon opens it again. Highlight = the query, case-insensitive.
2. **Meaning search on one meeting** — `LibrarySearch.dense([entry])`, cosine ≥ 0.45 (the global
   threshold), 20 passages max; chunks map to their first line, notes to their time.
3. **Ask retrieval** — same `Retrieval` knobs (800 excerpt tokens, 8 excerpts); routing skipped; each
   list (dense, BM25) gives up to 16 candidates (the global `routeDepth`) of this meeting. A named
   person's lines keep the note bonus. The reader model is loaded on demand as for the global Ask.
4. **Not indexed yet** (no search model, or the index is still being built): Ask still works with the
   keyword half only, on chunks cut on the fly with the index's own chunker (`SessionIndex.units`,
   token count estimated); the index is requested in the background. Search by meaning is hidden.
   In-meeting Ask therefore needs only the AI notes model; the search model makes it better.
5. **Still processing** (queued, transcribing, reading): search works on the lines and notes shown so
   far (keyword only — the index is built after the reading). Ask follows the global rule: it waits
   until no job runs (the pipeline always wins on CPU and memory) and says so.
6. **History per meeting** — answered questions are kept in `ask-history.json` next to the meeting
   (last 20; question, answer with its citations, the cited times). The sheet shows them under
   "Earlier"; ↺ clears the conversation and this meeting's history. Not exported, deleted with the
   meeting, never sent anywhere. A follow-up after reopening starts a fresh model conversation.
7. **Citations** open the time in the meeting already on screen (no reload), player parked there.
8. **Languages** — the answer follows the question's language (the global rule); every new string
   exists in English, 繁體中文 and 简体中文 (the app's three languages, both platforms).

## 4. Latency (Note10+, E2B, 8 meetings in the library, 2026-10-17)

| Stage | Library Ask | In-meeting Ask |
|---|---|---|
| Retrieval, cold app (embedder load included) | 3.80–3.89 s (5.7 s on a hot phone) | 3.77–3.80 s (5.7 s hot; 6.0 s once) |
| of which dense + BM25 passes, cold / warm | 113 ms / 11 ms | 34–41 ms / 3–4 ms |
| Retrieval, warm (embedder resident) | 303–306 ms | 352–356 ms |
| Prompt (excerpts) | 701–901 tokens | 671–878 tokens |
| Prefill + answer | 9.6–23.1 s (answer length) | 11.7–18.8 s (answer length) |

The narrower corpus saves ~0.1 s of passes, nothing a user notices: the retrieval is the query
embedding (cold load of the search model: 3.5–5.7 s; warm: 0.26–0.34 s) and the rest is the reader's
prefill of the same 800-token excerpt budget and the answer's length. Hence the one latency change of
this feature: the search model is **preloaded when the meeting's search bar opens** (and when the
in-meeting Ask opens on an empty field), so a question typed there finds it resident (~0.35 s
retrieval instead of ~4 s). The routing gain is in relevance: near-duplicate meetings (three 尾牙
takes in the test library) cannot take the excerpt slots of the meeting on screen.

## 5. Out of scope

- Ask during a run (memory/CPU contention with ASR + reader).
- Cross-meeting follow-ups from the in-meeting sheet (use the library Ask).
- Search inside the summary prose (it is generated from the notes, which are searched).
