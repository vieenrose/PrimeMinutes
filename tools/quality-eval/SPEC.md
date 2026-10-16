# Reference spec (quality eval 2026-10, Prime Minutes)

One JSON file per meeting: /home/luigi/voxsum-models/eval-corpora/work/refs/<NAME>.json

```json
{
 "name": "<NAME>", "lang": "zh" | "en",
 "method": "<one line: how the key points were derived>",
 "keypoints": [
   {"id": "<short_snake_id>", "kind": "decision|action|figure|topic|problem",
    "text": "<the key point, one sentence, meeting language>",
    "span": [start_sec, end_sec],
    "all": [["alt1","alt2"], ["alt3"]]}
 ],
 "questions": [
   {"id": "<NAME>-q1", "lang": "zh"|"en", "q": "<question>", "answer": "<expected short answer>",
    "span": [start_sec, end_sec], "all": [["alt1","alt2"], ["alt3"]]}
 ]
}
```

`all` = keyword groups used for automatic matching against the app's output: the item counts as present
when EVERY group has at least one alternative contained (substring, case-insensitive) in the output.
- Keep groups few (1-3) and alternatives short and distinctive (zh: 2-4 characters; en: 1-2 words, stems ok).
- zh alternatives in SIMPLIFIED Chinese only (outputs are converted to Simplified before matching). Give
  number variants: "三十"/"30", "两万"/"2万"/"20000"/"二万", dates "三月"/"3月".
- The app's transcript comes from a small streaming ASR: avoid rare proper nouns or words a recognizer is
  likely to garble as the ONLY alternative; prefer the common words that carry the fact.
- `span` = where in the meeting (seconds from the start of the audio, as in the gold transcript's
  [mm:ss-mm:ss] stamps) the fact is stated; keep it tight (<= 90 s) and around the clearest statement.

Questions: answerable from one short passage (<= 90 s) of ONE meeting, factual (numbers, decisions, who
does what, reasons, plans, choices made), worded naturally as a user would ask over a library of
meetings — include enough context words to identify the meeting topic (e.g. "In the remote control
design meeting, ..."/"在商场经理的会议里，..."), but do not quote the transcript verbatim. zh questions
are written in TRADITIONAL Chinese (the app's users are in Taiwan); `answer` and `all` stay Simplified.
No yes/no questions. Avoid questions whose answer needs the whole meeting.
