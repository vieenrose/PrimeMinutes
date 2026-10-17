#!/usr/bin/env python3
"""Writes the EmailEvalHarness config (tools/email-eval/run.sh): 12 meetings, one journal each.

Journals: the shipped E2B card run (small_limits/out_N) for the 6 AISHELL-4 + 4 AMI quality-eval
meetings, the E4B reader-gate run for the two demo meetings (move, weiya). Both models write the
e-mail from the SAME journal, so differences come from the e-mail step only."""
import json, sys, os
W = '/home/luigi/voxsum-models/eval-corpora/work'
GATE = '/home/luigi/VoxSumDroid/.claude/worktrees/quality-eval/app/src/androidTest/assets/reader_gate'
out = sys.argv[1] if len(sys.argv) > 1 else W + '/email_eval'
here = os.path.dirname(os.path.abspath(__file__))
items = []
def dur(sec):
    m = round(sec / 60)
    return (f'{m} 分鐘', f'{m} min')
def add(name, reader, utts, lang, date):
    u = json.load(open(utts))
    us = u['utterances']
    spk = sorted({x['speaker'] for x in us if x.get('speaker') is not None})
    sec = u.get('audio_sec') or us[-1]['end']
    zh, en = dur(sec)
    sep = '、' if lang == 'zh' else ', '
    items.append({'name': name, 'reader': reader, 'lang': lang, 'facts': {
        'date': date, 'duration_zh': zh, 'duration_en': en,
        'participants': sep.join(f'S{i + 1}' for i in spk)}})
for n in ['L_R003S02C02', 'L_R004S06C01', 'M_R003S02C01', 'M_R003S05C01', 'S_R003S03C01', 'S_R004S01C01']:
    add(n, f'{W}/small_limits/out_N/{n}.reader.gemma4-e2b-meeting-agent-zh-mobile-v1.json', f'{W}/out/{n}.asr.json', 'zh', '2026-10-12')
for n in ['ES2004b', 'ES2004d', 'IS1009c', 'TS3003c']:
    add(n, f'{W}/small_limits/out_N/{n}.reader.gemma4-e2b-meeting-agent-zh-mobile-v1.json', f'{W}/out/{n}.asr.json', 'en', '2026-10-13')
for n in ['move', 'weiya']:
    add(n, f'{W}/reader_gate/{n}.reader.gemma4-e4b-meeting-agent-zh-mobile.json', f'{GATE}/{n}.json', 'zh', '2026-10-14')
cfg = {'models_dir': '/home/luigi/voxsum-models/eval-corpora/app-models', 'threads': 6,
       'llm': ['gemma4-e2b-meeting-agent-zh-mobile-v1', 'gemma4-e4b-meeting-agent-zh-mobile'],
       'out': out, 'variants': {k: v for k, v in json.load(open(here + '/prompts.json')).items() if not k.startswith('_')},
       'items': items}
os.makedirs(out, exist_ok=True)
json.dump(cfg, open(out + '/cfg.json', 'w'), ensure_ascii=False, indent=1)
print(out + '/cfg.json', len(items), 'meetings')
