#!/usr/bin/env python3
"""Reference renderer for the proposed participants e-mail (docs/design/speaker-contacts.md §6).

Design aid, not product code: it pins down the template semantics (a logic-less Mustache subset),
the mail model built from a meeting (title, prose, minutes, speaker names) and the truncation rule,
so the Kotlin and Swift ports can be tested against the same golden files. Inputs are real reader
outputs (fixtures/*.json); outputs go to out/ (.txt, .html, and .png through headless Chrome).

  python3 -I render_samples.py            # all samples
  python3 -I render_samples.py --no-png   # text and HTML only
"""
import html, json, os, re, subprocess, sys, datetime

HERE = os.path.dirname(os.path.abspath(__file__))
MAIL = os.path.join(HERE, '..', 'mail')
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..', '..'))
CARD = os.path.join(ROOT, 'app/src/main/resources/model_cards/gemma4-meeting-agent-zh.json')
OPENCC = os.path.join(ROOT, 'ios/App/Resources/opencc')   # only on the ios branch; zh-Hans sample skipped without it
OPENCC_ALT = '/home/luigi/VoxSumDroid/ios/App/Resources/opencc'
OUT = os.path.join(HERE, 'out')

# ------------------------------------------------------------------------------------ template

TAG = re.compile(r'\{\{(\{?)([#^/]?)\s*([\w.]+)\s*\}?\}\}')
STANDALONE = re.compile(r'^[ \t]*(\{\{[#^/][^}]*\}\})[ \t]*\n', re.M)


def render(tpl, ctx, escape):
    """{{x}} (escaped when [escape]), {{{x}}} raw, {{#x}}..{{/x}} list/truthy, {{^x}}..{{/x}} falsy.
    A line holding only a section tag disappears with its newline (Mustache's standalone rule)."""
    tpl = STANDALONE.sub(r'\1', tpl)
    out, pos, stack = [], 0, [ctx]

    def lookup(name):
        if name == '.':
            return stack[-1]
        for c in reversed(stack):
            if isinstance(c, dict) and name in c:
                return c[name]
        return None

    def section(start, name):
        depth, i = 1, start
        while True:
            m = TAG.search(tpl, i)
            if m is None:
                raise ValueError('unclosed ' + name)
            if m.group(3) == name and m.group(2) in ('#', '^'):
                depth += 1
            elif m.group(3) == name and m.group(2) == '/':
                depth -= 1
                if depth == 0:
                    return tpl[start:m.start()], m.end()
            i = m.end()

    while True:
        m = TAG.search(tpl, pos)
        if m is None:
            out.append(tpl[pos:])
            break
        out.append(tpl[pos:m.start()])
        raw, kind, name = m.group(1), m.group(2), m.group(3)
        if kind in ('#', '^'):
            body, pos = section(m.end(), name)
            v = lookup(name)
            if kind == '^':
                if not v:
                    out.append(render(body, stack[-1], escape))
            elif isinstance(v, list):
                for x in v:
                    out.append(render(body, {**(stack[-1] if isinstance(stack[-1], dict) else {}), **(x if isinstance(x, dict) else {'.': x})}, escape))
            elif v:
                out.append(render(body, {**stack[-1], **v} if isinstance(v, dict) else stack[-1], escape))
            continue
        v = lookup(name)
        v = '' if v is None else str(v)
        out.append(html.escape(v, quote=False) if escape and not raw else v)
        pos = m.end()
    return ''.join(out)

# ------------------------------------------------------------------------------------ OpenCC t2s


def t2s_converter():
    d = OPENCC if os.path.isdir(OPENCC) else OPENCC_ALT
    if not os.path.isdir(d):
        return None
    table = {}
    for f in ('TSCharacters.txt', 'TSPhrases.txt'):
        for line in open(os.path.join(d, f), encoding='utf-8'):
            if '\t' in line:
                k, v = line.rstrip('\n').split('\t', 1)
                table.setdefault(k, v.split(' ')[0])
    longest = max(len(k) for k in table)

    def conv(s):
        out, i = [], 0
        while i < len(s):
            for n in range(min(longest, len(s) - i), 0, -1):
                if s[i:i + n] in table:
                    out.append(table[s[i:i + n]]); i += n; break
            else:
                out.append(s[i]); i += 1
        return ''.join(out)
    return conv

# ------------------------------------------------------------------------------------ mail model

REF = re.compile(r'(?<![A-Za-z0-9_])S(\d{1,2})(?![A-Za-z0-9_])')
TS = re.compile(r'\[(\d+:\d{2}(?::\d{2})?(?:\s*[,，、]\s*\d+:\d{2}(?::\d{2})?)*)\]')
ITEM = re.compile(r'^-\s*(.*?)\s*(?:\[(\d+:\d{2}(?::\d{2})?)\])?\s*$')
PRIORITY = {'DECISION': 1, 'ACTION': 1, 'OPEN-ISSUE': 2, 'NUMBER': 3, 'PROPOSAL': 4}


def is_han(c):
    return '㐀' <= c <= '鿿'


def resolve_refs(text, names):
    """SpeakerRefs.resolve: S2 -> the speaker's name; no space between a Han name and Han text."""
    def rep(m):
        n = names.get(int(m.group(1)) - 1)
        return m.group(0) if n is None else '\0' + n + '\0'
    t = REF.sub(rep, text)
    t = re.sub(r'(?<=[㐀-鿿]) \0', '\0', t)
    t = re.sub(r'\0 (?=[㐀-鿿])', '\0', t)
    return t.replace('\0', '')


def sections_of(card, lang):
    if lang == 'en':
        return card['languages']['en']['sections']
    return card['reader']['sections']


def parse_minutes(raw, card, lang):
    """The minutes as the reader assembles them: 【heading】 then '- text [ts]' lines."""
    heads = {h: tag for h, tag in sections_of(card, lang)}
    out, cur = [], None
    for line in raw.splitlines():
        line = line.strip()
        m = re.match(r'^【(.+)】$', line)
        if m:
            cur = {'heading': m.group(1), 'tag': heads.get(m.group(1), ''), 'items': []}
            out.append(cur)
        elif line.startswith('-') and cur is not None:
            im = ITEM.match(line)
            text, ts = im.group(1), im.group(2)
            if text in ('無', '无', 'None', '-', ''):
                continue
            cur['items'].append({'text': text, 'ts': ts})
    return [s for s in out if s['items']]


def fmt_duration(sec, s):
    m = round(sec / 60)
    return s['duration_hm'].format(h=m // 60, m=m % 60) if m >= 60 else s['duration_m'].format(m=m)


def build(fx, lang_ui, opts, card, strings, conv=None):
    s = strings[lang_ui]
    c = conv or (lambda x: x)
    lang = fx['lang']
    people = {int(k): v for k, v in fx['people'].items()}
    names = {k: c(v['name']) for k, v in people.items()}
    known = {u['speaker'] for u in fx['utterances']}
    start = datetime.datetime.fromisoformat(fx['started'])
    dur = max(u['end'] for u in fx['utterances'])
    end = start + datetime.timedelta(seconds=dur)
    title = c(fx['title'])
    wd = s['weekdays'][start.weekday()]
    mon = s.get('months', [''] * 12)[start.month - 1]
    date_long = s['date_long'].format(y=start.year, mo=start.month, d=start.day, wd=wd, mon=mon)
    date_short = s['date_short'].format(mo=start.month, d=start.day, mon=mon)
    sub = lambda t: t.format(title=title, date_long=date_long, date_short=date_short)
    unnamed = sorted(k for k in known if k not in names)
    parts = [names[k] for k in sorted(names)] + ([s['unnamed'].format(n=len(unnamed))] if unnamed else [])
    facts = [
        {'label': s['label_date'], 'value': date_long},
        {'label': s['label_time'], 'value': f"{start:%H:%M}–{end:%H:%M}"},
        {'label': s['label_duration'], 'value': fmt_duration(dur, s)},
        {'label': s['label_participants'], 'value': s['list_sep'].join(parts)},
    ]
    refs = lambda t: resolve_refs(c(t), names)

    def html_ts(t):
        e = html.escape(t, quote=False)
        return TS.sub(lambda m: f'<span style="color:#5A6982;font-size:13px;white-space:nowrap;">[{m.group(1)}]</span>', e)

    model = {'subject': sub(s['subject']), 'greeting': s['greeting'], 'intro': sub(s['intro']),
             'chat_intro': sub(s['chat_intro']), 'facts_heading': s['facts_heading'] if opts.get('html_headings') else s['plain_heading'].format(h=s['facts_heading'], H=s['facts_heading'].upper()), 'facts': facts, 'colon': s['colon'],
             'disclaimer': s['disclaimer'], 'footer': s['footer'], 'bullet': s['bullet'],
             'html_lang': {'zh-Hant': 'zh-Hant-TW', 'zh-Hans': 'zh-Hans-CN'}.get(lang_ui, 'en')}
    if opts.get('lead'):
        model['lead'] = refs(opts['lead'])
        model['lead_html'] = html_ts(model['lead'])
    blocks = []   # (priority, kind, payload) — what truncation can trim
    if opts.get('summary', True) and fx.get('summary'):
        paras = [p.strip() for p in refs(fx['summary']).split('\n\n') if p.strip()]
        blocks.append({'kind': 'summary', 'prio': 3, 'heading': s['summary_heading'], 'paragraphs': paras})
    if opts.get('minutes', True):
        for sec in parse_minutes(fx['minutes_raw'], card, lang):
            if opts.get('only') and sec['tag'] not in opts['only']:
                continue
            blocks.append({'kind': 'section', 'prio': PRIORITY.get(sec['tag'], 3), 'tag': sec['tag'],
                           'heading': c(sec['heading']), 'items': sec['items'], 'dropped': 0})
    if opts.get('attachments'):
        model['attachments'] = s['attachments'].format(files=s['list_sep'].join(opts['attachments']))

    def assemble():
        model['summary'] = None
        model['sections'] = []
        for b in blocks:
            if b['kind'] == 'summary':
                if b['paragraphs']:
                    model['summary'] = {'heading': plain_heading(b['heading']), 'paragraphs':
                                        [{'text': p, 'html': html_ts(p)} for p in b['paragraphs']]}
                continue
            if not b['items']:
                continue
            items = [{'text': refs(i['text']), 'ts': i['ts'], 'owner': owner_of(i['text'], names) if b['tag'] == 'ACTION' else None}
                     for i in b['items']]
            groups = group_owners(items, s) if any(i['owner'] for i in items) else [{'owner': None, 'items': items}]
            model['sections'].append({'heading': plain_heading(b['heading']), 'groups': groups,
                                      'more': s['more'].format(n=b['dropped']) if b['dropped'] else None})

    def plain_heading(h):
        return h if opts.get('html_headings') else s['plain_heading'].format(h=h, H=h.upper())

    budget = opts.get('budget')
    tpl_txt = open(os.path.join(MAIL, opts.get('template', 'meeting_mail.txt')), encoding='utf-8').read()
    assemble()
    if budget:
        # Trim the least important block first: proposals, then figures, then the prose, then open issues.
        steps = [('PROPOSAL', 5), ('summary', 1), ('NUMBER', 5), ('PROPOSAL', 0), ('summary', 0), ('NUMBER', 0), ('OPEN-ISSUE', 3)]
        for key, keep in steps:
            if len(render(tpl_txt, model, False)) <= budget:
                break
            for b in blocks:
                if key == 'summary' and b['kind'] == 'summary':
                    b['paragraphs'] = b['paragraphs'][:keep]
                elif b.get('tag') == key and len(b['items']) > keep:
                    b['dropped'] += len(b['items']) - keep
                    b['items'] = b['items'][:keep]
            assemble()
    return model


def owner_of(text, names):
    m = re.match(r'^S(\d{1,2})(?![A-Za-z0-9_])', text)
    return names.get(int(m.group(1)) - 1) if m else None


def group_owners(items, s):
    order, groups = [], {}
    for i in items:
        o = i['owner'] or s['owner_none']
        if o not in groups:
            groups[o] = []; order.append(o)
        t = i['text']
        if i['owner'] and t.startswith(i['owner']):
            t = t[len(i['owner']):].lstrip(' ')
        groups[o].append({'text': t, 'ts': i['ts']})
    none = s['owner_none']
    order = [o for o in order if o != none] + ([none] if none in groups else [])
    return [{'owner': o, 'items': groups[o]} for o in order]

# ------------------------------------------------------------------------------------ output

FRAME = '''<!DOCTYPE html><html><head><meta charset="utf-8"><style>
body{{margin:0;background:#E3E7EE;font-family:Roboto,'Noto Sans CJK TC',sans-serif}}
.w{{width:{w}px;margin:24px auto;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 1px 4px rgba(0,0,0,.15)}}
.h{{padding:14px 18px;background:#F7F8FA;border-bottom:1px solid #E1E5EC;font-size:13px;color:#5A6982;line-height:1.7}}
.h b{{color:#17263F;font-weight:600}} .s{{font-size:16px;color:#17263F;font-weight:600;margin-top:4px}}
pre{{margin:0;padding:16px 18px;white-space:pre-wrap;font:14px/1.55 'Noto Sans Mono CJK TC','Noto Sans CJK TC',monospace;color:#17263F}}
.tag{{display:inline-block;background:#2F6BFF;color:#fff;border-radius:6px;padding:1px 8px;font-size:12px;margin-left:6px}}
</style></head><body><div class="w"><div class="h">{hdr}</div>{body}</div></body></html>'''


def header(to, subject, kind):
    return (f'<div><b>To</b>&nbsp; {html.escape(", ".join(to))}</div>'
            f'<div class="s">{html.escape(subject)}<span class="tag">{kind}</span></div>')


def shot(src, png, width):
    subprocess.run(['google-chrome', '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1.5',
                    f'--window-size={width + 48},5000', f'--screenshot={png}', 'file://' + src],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(['convert', png, '-fuzz', '1%', '-trim', '+repage', '-bordercolor', '#E3E7EE', '-border', '24', '-dither', 'None', '-colors', '48', 'PNG8:' + png], check=True)


def main():
    png = '--no-png' not in sys.argv
    os.makedirs(OUT, exist_ok=True)
    card = json.load(open(CARD, encoding='utf-8'))
    strings = json.load(open(os.path.join(MAIL, 'strings.json'), encoding='utf-8'))
    t2s = t2s_converter()
    move = json.load(open(os.path.join(HERE, 'fixtures/move.json'), encoding='utf-8'))
    ami = json.load(open(os.path.join(HERE, 'fixtures/is1009c.json'), encoding='utf-8'))
    lead = {}
    lp = os.path.join(HERE, 'fixtures/leads.json')   # model-written leads (tools/email-eval), when present
    if os.path.exists(lp):
        lead = json.load(open(lp, encoding='utf-8'))
    samples = [
        ('move.zh-TW', move, 'zh-Hant', {}, None),
        ('move.zh-TW.hybrid', move, 'zh-Hant', {'lead': lead.get('move'), 'summary': False}, None),
        ('move.zh-TW.actions-only', move, 'zh-Hant', {'summary': False, 'only': ['DECISION', 'ACTION']}, None),
        ('move.zh-CN', move, 'zh-Hans', {}, t2s),
        ('is1009c.en', ami, 'en', {'attachments': ['Remote-control-concept.pdf']}, None),
        ('is1009c.en.mailto', ami, 'en', {'budget': 2000}, None),
        ('move.zh-TW.chat', move, 'zh-Hant', {'template': 'chat.txt', 'summary': False}, None),
    ]
    for name, fx, lang, opts, conv in samples:
        if lang == 'zh-Hans' and conv is None:
            print('skip', name, '(no OpenCC tables)'); continue
        if 'hybrid' in name and not opts.get('lead'):
            print('skip', name, '(no fixtures/leads.json yet)'); continue
        m = build(fx, lang, opts, card, strings, conv)
        to = [f"{v['name']} <{v['emails'][0]}>" for k, v in sorted(fx['people'].items()) if not v.get('me') and v.get('emails')]
        txt = render(open(os.path.join(MAIL, opts.get('template', 'meeting_mail.txt')), encoding='utf-8').read(), m, False)
        txt = re.sub(r'\n{3,}', '\n\n', txt).strip() + '\n'
        open(os.path.join(OUT, name + '.txt'), 'w', encoding='utf-8').write(
            ('' if 'chat' in name else f"To: {', '.join(to)}\nSubject: {m['subject']}\n\n") + txt)
        print(name, len(txt), 'chars')
        if png:
            body = '<pre>' + html.escape(txt) + '</pre>'
            f = os.path.join(OUT, name + '.txt.html')
            open(f, 'w', encoding='utf-8').write(FRAME.format(w=600, hdr=header(to, m['subject'], 'chat' if 'chat' in name else 'plain text'), body=body))
            shot(f, os.path.join(OUT, name + '.txt.png'), 600)
            os.remove(f)
        if 'chat' in name or 'mailto' in name:
            continue
        hm = build(fx, lang, {**opts, 'html_headings': True}, card, strings, conv)
        h = render(open(os.path.join(MAIL, 'meeting_mail.html'), encoding='utf-8').read(), hm, True)
        h = re.sub(r'\n\s*\n', '\n', h)
        open(os.path.join(OUT, name + '.html'), 'w', encoding='utf-8').write(h)
        if png:
            inner = re.search(r'<body[^>]*>(.*)</body>', h, re.S).group(1)
            f = os.path.join(OUT, name + '.frame.html')
            open(f, 'w', encoding='utf-8').write(FRAME.format(w=640, hdr=header(to, hm['subject'], 'HTML'), body=inner))
            shot(f, os.path.join(OUT, name + '.png'), 640)
            os.remove(f)


if __name__ == '__main__':
    main()
