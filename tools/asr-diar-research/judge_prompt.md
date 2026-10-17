You are a blind judge of meeting notes. Packet directory: {PACKET}
It holds `gold_transcript.txt` (human reference transcript, speakers G1, G2, ...) and several
`output_X.txt` files: notes produced by an on-device model from different (unknown to you) transcripts of
the same meeting. Each output starts with a SPEAKER MAP translating its S-labels to gold speakers.
Some outputs were made from a transcript with recognition errors and/or wrong speaker labels; you must not
guess which, judge only against the gold transcript.

Work in this order and be strict and consistent across outputs.

1. Read the whole gold transcript. Write the reference list (before reading any output):
   - `key_points`: the 8-15 most important points a participant would want in the notes (topics with
     their substance, conclusions, numbers/names that matter). One short sentence each, ids K1..Kn.
   - `decisions`: decisions actually taken (D1..), and `actions`: tasks with an owner or a deadline (A1..).
     Empty lists if the meeting has none; do not invent.
2. For each output (all of TITLE, SUMMARY, MINUTES, NOTES, ACTIONS together):
   - `kp`: for every K id: "covered" (substance right), "partial" (topic present, substance thin or
     partly wrong), "missing".
   - `decisions_found` / `actions_found`: ids of D/A that the output states correctly (an action with a
     wrong owner counts under errors, not here).
   - `errors`: every statement that is false against the gold transcript. Each error: {"quote": short
     quote from the output, "truth": what the gold says, "type": one of
       "asr_term"      (a misheard word, name, number or term carried into the notes),
       "speaker"       (an act or opinion attributed to the wrong person; use the SPEAKER MAP),
       "fabrication"   (content not in the meeting at all, or a decision/action that was not taken),
       "distortion"    (meeting content misrepresented: wrong conclusion, wrong polarity, merged topics),
     "severity": "major" (would mislead a reader acting on the notes) or "minor"}.
     Do not count style, language variant (simplified vs traditional) or missing detail as errors.
   - `speaker_claims`: number of statements that name or label who said/proposed/owns something, and
     `speaker_claims_wrong`: how many of them the SPEAKER MAP + gold transcript contradict.
   - `usefulness`: 1-5 for a participant who missed the meeting (5 = accurate and covers what matters).
3. Write ONLY a JSON file `{OUT}` with:
   {"meeting": ..., "key_points": [...], "decisions": [...], "actions": [...],
    "outputs": {"A": {"kp": {"K1": "covered", ...}, "decisions_found": [...], "actions_found": [...],
                      "errors": [...], "speaker_claims": n, "speaker_claims_wrong": n, "usefulness": n,
                      "comment": "one sentence"}, ...}}
   Validate it with `python3 -c "import json;json.load(open('{OUT}'))"`.
Do not open any other file than the packet directory. Reply with one line: the path written.
