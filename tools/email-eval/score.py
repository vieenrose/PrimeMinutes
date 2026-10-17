#!/usr/bin/env python3
"""Scores the e-mail prompt variants written by EmailEvalHarness (tools/email-eval/run.sh).

  score.py <out dir> prep [model] [--control]   # automatic metrics -> auto.json; blind judge batches
                                                # -> judge/<model|all>/in_*.json + key.json (only outputs of
                                                # models matching <model>, so a finished model can be judged
                                                # while the next one runs; --control adds the template items)
  score.py <out dir> table    # merges judge/*/out_*.json (written by the judge) -> table.md

The judge sees only: the notes the model was given, and the text it wrote (ids are random, the
variant and the model are hidden, items shuffled across meetings). It labels every statement
(sentence or bullet) against the notes; see JUDGE_RUBRIC.
"""
import glob, json, os, random, re, sys
from collections import defaultdict

TS_ANY = re.compile(r'\[[0-9:：\s,，、;；\-–~～]*\d[0-9:：\s,，、;；\-–~～]*\]')
TS_ONE = re.compile(r'\d+:\d{2}(?::\d{2})?')
CJK = re.compile(r'[㐀-鿿]')
LATIN = re.compile(r'[A-Za-z]')

JUDGE_RUBRIC = """For each item: split TEXT into statements (one sentence of prose, or one bullet line; a greeting,
a heading, a sign-off or the AI-disclaimer line is not a statement). Label each statement against NOTES
only (not world knowledge):
- supported: everything it says is in the notes (paraphrase, merging two notes, or a fair summary is fine);
- contradicted: it changes a fact of the notes (figure, date, name/owner, place, polarity), OR turns a
  PROPOSAL / suggestion into a decision or a firm action, OR an OPEN-ISSUE into something settled, OR a
  DECISION into a mere suggestion;
- unsupported: it adds content the notes do not contain (a new fact, person, date, deadline, promise,
  reason or conclusion), without contradicting them.
Also count, over the whole item: type_flips (proposal<->decision, open<->settled), wrong_owner (a task or
statement given to the wrong S-number or person), wrong_figure (a number/date/amount changed), invented
(names, dates, deadlines or commitments that appear nowhere in the notes; list them)."""


def load(out):
    rows = []
    for f in sorted(glob.glob(os.path.join(out, '*.json'))):
        if os.path.basename(f) in ('cfg.json', 'auto.json'):
            continue
        d = json.load(open(f, encoding='utf-8'))
        if 'variant' in d:
            rows.append(d)
    return rows


def auto(d):
    raw = d['raw'].split('<turn|>')[0]
    text = d['cleaned'] or ''
    known = set(TS_ONE.findall(d['notes_used_text']))
    tags = TS_ANY.findall(raw)
    bad = [t for t in tags if any(x not in known for x in TS_ONE.findall(t)) or not TS_ONE.search(t)]
    cjk, lat = len(CJK.findall(text)), len(LATIN.findall(text))
    lang_ok = (cjk > lat) if d['lang'] == 'zh' else (lat > 5 * max(cjk, 1) or cjk == 0)
    first = text.strip().splitlines()[0] if text.strip() else ''
    greet = bool(re.search(r'各位|大家好|您好|Hi\b|Hello|Dear', first))
    md = len(re.findall(r'\*\*|^\s*[*#]+\s', raw, re.M))
    heads = {'zh': ['【摘要】', '【決議事項】', '【待辦與負責人】', '【保留與未決】'],
             'en': ['Summary', 'Decisions', 'Action items and owners', 'Open issues']}[d['lang']]
    form = all(h in text for h in heads) if d['variant'] == 'sections' else None
    return {'meeting': d['meeting'], 'variant': d['variant'], 'model': d['model'], 'lang': d['lang'],
            'chars': len(text), 'gen_tokens': d['gen_tokens'], 'prompt_tokens': d['prompt_tokens'],
            'truncated': d['gen_tokens'] >= d['max_tokens'] - 1, 'ts_tags': len(tags), 'ts_bad_raw': len(bad),
            'markdown': md, 'lang_ok': lang_ok, 'greeting': greet, 'sections_ok': form,
            'prefill_sec': d['prefill_sec'], 'decode_sec': d['decode_sec'], 'empty': not text.strip()}


def prep(out, model=None, control=False):
    rows = load(out)
    metrics = [auto(d) for d in rows]
    json.dump(metrics, open(os.path.join(out, 'auto.json'), 'w'), ensure_ascii=False, indent=1)
    rnd = random.Random('7' + (model or ''))
    items, key = [], {}
    for d in rows:
        if model and model not in d['model']:
            continue
        if not (d['cleaned'] or '').strip():
            continue
        i = '%08x' % rnd.getrandbits(32)
        key[i] = {'meeting': d['meeting'], 'variant': d['variant'], 'model': d['model']}
        items.append({'id': i, 'lang': d['lang'], 'notes': d['notes_used_text'], 'text': d['cleaned']})
    # Control: the deterministic minutes (the notes regrouped by type), judged like the rest; any
    # "contradiction" found there is judge noise or a proposal-guard reclassification.
    cfg = json.load(open(os.path.join(out, 'cfg.json'), encoding='utf-8'))
    for it in (cfg['items'] if control or not model else []):
        r = json.load(open(it['reader'], encoding='utf-8'))
        i = '%08x' % rnd.getrandbits(32)
        key[i] = {'meeting': it['name'], 'variant': 'template', 'model': 'deterministic'}
        items.append({'id': i, 'lang': it['lang'], 'notes': r['notes'], 'text': r['minutes_raw']})
    rnd.shuffle(items)
    jd = os.path.join(out, 'judge', model or 'all')
    os.makedirs(jd, exist_ok=True)
    json.dump(key, open(os.path.join(jd, 'key.json'), 'w'), indent=1)
    n = int(os.environ.get('BATCH', '12'))
    for k in range(0, len(items), n):
        json.dump({'rubric': JUDGE_RUBRIC, 'items': items[k:k + n]},
                  open(os.path.join(jd, f'in_{k // n:02d}.json'), 'w'), ensure_ascii=False, indent=1)
    print(len(metrics), 'outputs,', len(items), 'to judge in', (len(items) + n - 1) // n, 'batches')


def table(out):
    metrics = json.load(open(os.path.join(out, 'auto.json'), encoding='utf-8'))
    key, verdict = {}, {}
    for jd in glob.glob(os.path.join(out, 'judge', '*', '')):
        key.update(json.load(open(os.path.join(jd, 'key.json'))))
        for f in glob.glob(os.path.join(jd, 'out_*.json')):
            for v in json.load(open(f, encoding='utf-8')):
                verdict[v['id']] = v
    jrows = defaultdict(list)
    for i, v in verdict.items():
        k = key[i]
        jrows[(k['model'], k['variant'])].append(v)
    arows = defaultdict(list)
    for m in metrics:
        arows[(m['model'], m['variant'])].append(m)
    for i, k in key.items():
        if k['variant'] == 'template' and ('deterministic', 'template') not in arows:
            arows[('deterministic', 'template')] = []
    order = ['template', 'typed', 'hybrid', 'prose', 'sections', 'free']
    lines = ['| model | variant | n | statements | contradicted | unsupported | type flips | wrong owner | wrong figure | invented | markdown | truncated | bad ts (raw) | lang ok | form ok | chars (median) | gen tok (median) | host s (median) |',
             '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|']
    med = lambda xs: sorted(xs)[len(xs) // 2] if xs else 0
    for (model, variant) in sorted(arows, key=lambda x: (x[0], order.index(x[1]) if x[1] in order else 9)):
        a = arows[(model, variant)]
        j = jrows.get((model, variant), [])
        st = sum(v['statements'] for v in j)
        c = sum(v['contradicted'] for v in j)
        u = sum(v['unsupported'] for v in j)
        pct = lambda x: f'{x} ({100 * x / st:.0f} %)' if st else '-'
        form = [m['sections_ok'] for m in a if m['sections_ok'] is not None]
        lines.append('| ' + ' | '.join(str(x) for x in [
            {'deterministic': '-'}.get(model, 'E2B' if 'e2b' in model else 'E4B'), variant, len(a) or len(j), st or '-', pct(c), pct(u),
            sum(v.get('type_flips', 0) for v in j), sum(v.get('wrong_owner', 0) for v in j),
            sum(v.get('wrong_figure', 0) for v in j), sum(len(v.get('invented', [])) for v in j),
            *([sum(1 for m in a if m['markdown']), sum(1 for m in a if m['truncated']), sum(m['ts_bad_raw'] for m in a),
               f"{sum(1 for m in a if m['lang_ok'])}/{len(a)}", f"{sum(form)}/{len(form)}" if form else '-',
               med([m['chars'] for m in a]), med([m['gen_tokens'] for m in a]),
               f"{med([m['prefill_sec'] + m['decode_sec'] for m in a]):.0f}"] if a else ['-'] * 8)]) + ' |')
    open(os.path.join(out, 'table.md'), 'w').write('\n'.join(lines) + '\n')
    print('\n'.join(lines))


if __name__ == '__main__':
    a = [x for x in sys.argv[3:] if not x.startswith('--')]
    if sys.argv[2] == 'prep':
        prep(sys.argv[1], a[0] if a else None, '--control' in sys.argv)
    else:
        table(sys.argv[1])
