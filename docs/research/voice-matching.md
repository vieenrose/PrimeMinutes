# Voice matching (比對聲音): evaluation 2026-10-29

Model and rules unchanged: CAM++ zh-en LiteRT fp32, 192-dim; per speaker up to 60 s of clean solo lines (≥10 s or no vector); person voice = seconds-weighted centroid of confirmed meetings; "possible" ≥ 0.50, "likely" ≥ 0.70 with 0.05 margin.

## Measurements (faithful Python port of the app's scoring, app diarizer via the host driver)
Data: 4 podcast series (gooaye, 忽左忽右, KK show/百靈果, Lex Fridman), 3 episodes each (20-min excerpts); AMI far-field (10-min windows). Enrol the host from episode 1, score episodes 2-3; other series serve as distractors.

| set | enrol | P | R | F1 | EER | top-1 | false suggestion on guests |
|---|---|---|---|---|---|---|---|
| podcasts, app diar | 1 ep | 0.91 | 1.00 | 0.95 | 0.000 | 1.00 | 1/14 (7 %) |
| podcasts, app diar, "likely" only | 1 ep | 1.00 | 0.90 | 0.95 | | | 0 |
| podcasts, app diar | 2 ep | 0.90 | 1.00 | 0.95 | | | |
| AMI, app diar | 1 mtg | 1.00 | 0.92 | 0.96 | 0.001 | 1.00 | 0 |
| AMI, app diar | 2 mtg | 1.00 | 0.93 | 0.96 | | | |
| AMI, oracle segments | 1 mtg | 1.00 | 0.64 | | 0.000 | | 0 |

- Cross-episode host cosine: gooaye 0.89, 忽左忽右 0.82-0.83, Lex 0.84-0.85, 百靈果 Ken 0.76, Kylie 0.68-0.71.
- The only false suggestion is an ad-read cluster suggested as Kylie at 0.60 (likely a labelling artefact).
- Threshold sweep: 0.65 would give P 1.00 R 1.00 on podcasts. One error is not enough evidence, so the threshold is unchanged.
- Labelled speakers under 10 s: none. 11 % of all clusters fall under 10 s, all of them short or unlabelled.
- AMI oracle recall is low because reference overlaps remove clean lines. This is a property of the data, not of the app.

## On device (Note10+, androidtest 0.54.0)
- Tagging the host in Lex ep N and opening ep N+1 suggests the host as "likely" (cos 0.92 host vs 0.28 guest). Device vectors vs the port: cos 0.95-0.97.
- Matching itself works.
- Naming a speaker without the Contacts permission creates a label, not a person, so no voice is learned.

## Why 「正在比對聲音」 stayed up
- The note meant "no sidecar yet", not "a job is running".
- The lane waited for recording, transcription and reading to be idle, and then decoded the whole file. On the Note10+, a 20-min import took 75 s to decode, plus 8 s to embed, and only after the batch finished. Sidecars were written about 20 min after the sheet opened.
- If the model was missing or the job failed, the lane cleared the queue or only logged, and the note stayed forever.
- iOS has the same design. It also has no background task, so the lane only advances in the foreground.

## Fix (branches voice-match-android / voice-match-ios)
- Per-meeting status: queued (with the reason), computing, failed with Retry, model missing with Download.
- The meeting open in the picker jumps the queue and runs even while other work is busy.
- Only the selected speech lines are decoded. On the Note10+ this is 6.0 s instead of 75 s for a 20-min episode. The vectors have cos 0.94 vs the full decode, and the suggestion is unchanged.

## Limits
- The diarizer merging two speakers is the main risk to purity (see diarization-precision.md).
- Hosts with less than 10 s of solo speech get no voice.
