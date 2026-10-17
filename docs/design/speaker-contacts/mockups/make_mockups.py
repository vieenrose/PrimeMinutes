#!/usr/bin/env python3
"""Static mockups for docs/design/speaker-contacts.md (HTML/CSS, rendered with headless Chrome).

Android light theme of the app (ui/theme/Theme.kt light palette: ground #F2F4F1, cards #FFFFFF,
accent #2F6BFF, ink #17263F, muted #5A6982). 412 x 915 dp phone, PNG at 2x.
  python3 -I make_mockups.py
"""
import os, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))

ICONS = {
    'back': 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
    'search': 'M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
    'more': 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
    'mail': 'M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z',
    'person': 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
    'contacts': 'M20 0H4v2h16V0zM4 24h16v-2H4v2zM20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 2.75c1.24 0 2.25 1.01 2.25 2.25s-1.01 2.25-2.25 2.25S9.75 10.24 9.75 9 10.76 6.75 12 6.75zM17 17H7v-1.5c0-1.67 3.33-2.5 5-2.5s5 .83 5 2.5V17z',
    'play': 'M8 5v14l11-7z',
    'add': 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
    'close': 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
    'link': 'M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z',
    'sync': 'M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
    'share': 'M16 5l-1.42 1.42-1.59-1.59V16h-1.98V4.83L9.42 6.42 8 5l4-4 4 4zm4 5v11c0 1.1-.9 2-2 2H6a2 2 0 0 1-2-2V10c0-1.11.89-2 2-2h3v2H6v11h12V10h-3V8h3a2 2 0 0 1 2 2z',
    'tune': 'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z',
    'shield': 'M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z',
    'history': 'M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z',
    'star': 'M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z',
    'attach': 'M16.5 6v11.5c0 2.21-1.79 4-4 4s-4-1.79-4-4V5a2.5 2.5 0 0 1 5 0v10.5c0 .55-.45 1-1 1s-1-.45-1-1V6H10v9.5a2.5 2.5 0 0 0 5 0V5c0-2.21-1.79-4-4-4S7 2.79 7 5v12.5c0 3.04 2.46 5.5 5.5 5.5s5.5-2.46 5.5-5.5V6h-1.5z',
    'refresh': 'M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
    'summ': 'M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zM6 20V4h7v5h5v11H6zm2-6h8v2H8v-2zm0-3h8v2H8v-2zm0 6h5v2H8v-2z',
    'expand': 'M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z',
    'lock': 'M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zM9 6c0-1.66 1.34-3 3-3s3 1.34 3 3v2H9V6zm9 14H6V10h12v10zm-6-3c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2z',
}


def ic(name, color='currentColor', size=24):
    return f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="{color}" style="flex:none"><path d="{ICONS[name]}"/></svg>'


CSS = '''
*{box-sizing:border-box}
:root{--ground:#F2F4F1;--card:#FFFFFF;--accent:#2F6BFF;--accent2:#1E4FD6;--ink:#17263F;--muted:#5A6982;--hair:rgba(23,38,63,.10);
--tint:rgba(47,107,255,.12);--warn:#9A5B00;--ok:#2E9E63;--track:#DFE4DD}
html,body{margin:0;padding:0}
body{width:412px;height:915px;overflow:hidden;background:var(--ground);color:var(--ink);
font-family:Roboto,'Noto Sans CJK TC','Noto Sans TC',sans-serif;font-size:15px;position:relative}
.status{height:32px;display:flex;align-items:center;justify-content:space-between;padding:0 20px;font-size:13px;color:var(--ink)}
.top{height:56px;display:flex;align-items:center;gap:6px;padding:0 6px}
.top .t{flex:1;font-size:19px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ib{width:44px;height:44px;display:flex;align-items:center;justify-content:center;color:var(--ink)}
.pad{padding:0 16px}
.card{background:var(--card);border:1px solid var(--hair);border-radius:14px;padding:14px;margin:8px 16px}
.label{font-size:12.5px;font-weight:700;color:var(--muted);margin:14px 0 4px;letter-spacing:.2px}
.row{display:flex;align-items:center;gap:12px;min-height:52px;padding:6px 0}
.row .main{flex:1;min-width:0}
.row .n{font-size:15.5px;font-weight:500}
.row .s{font-size:13px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.hr{height:1px;background:var(--hair)}
.btn{white-space:nowrap;display:inline-flex;align-items:center;gap:6px;border:1px solid #9AA7B8;border-radius:20px;padding:8px 16px;font-size:14px;font-weight:500;color:var(--accent);background:transparent}
.btn.fill{background:var(--accent);border-color:var(--accent);color:#fff}
.btn.warn{color:var(--warn)}
.tb{color:var(--accent);font-weight:600;font-size:14px;padding:8px 10px}
.chip{display:inline-flex;align-items:center;gap:4px;border:1px solid var(--hair);border-radius:9px;padding:5px 10px;font-size:13px;font-weight:600;color:var(--muted);background:#fff}
.chip.on{background:var(--tint);border-color:transparent;color:var(--accent2)}
.pill{display:inline-block;font-size:11.5px;font-weight:700;border-radius:6px;padding:1px 7px}
.pill.ok{background:rgba(46,158,99,.12);color:#1F7A4A}
.pill.blue{background:var(--tint);color:var(--accent2)}
.pill.grey{background:#ECEFEA;color:var(--muted)}
.avatar{width:36px;height:36px;border-radius:18px;background:#E6ECFF;color:var(--accent2);display:flex;align-items:center;justify-content:center;font-weight:700;font-size:15px;flex:none}
.scrim{position:absolute;inset:0;background:rgba(15,20,30,.38)}
.sheet{position:absolute;left:0;right:0;bottom:0;background:#fff;border-radius:28px 28px 0 0;padding:10px 16px 22px}
.handle{width:36px;height:4px;border-radius:2px;background:#9AA7B8;margin:4px auto 12px}
.field{display:flex;align-items:center;gap:8px;border:1px solid #9AA7B8;border-radius:10px;padding:0 12px;height:48px;font-size:15px;color:var(--muted);background:#fff}
.field.focus{border:2px solid var(--accent);color:var(--ink)}
.cb{width:20px;height:20px;border-radius:4px;border:2px solid #5A6982;flex:none;display:flex;align-items:center;justify-content:center}
.cb.on{background:var(--accent);border-color:var(--accent)}
.cb.on::after{content:'';width:5px;height:10px;border:solid #fff;border-width:0 2.5px 2.5px 0;transform:rotate(45deg) translate(-1px,-1px)}
.note{font-size:12.5px;color:var(--muted);line-height:1.5}
.menu{position:absolute;right:8px;top:84px;width:236px;background:#fff;border-radius:6px;box-shadow:0 6px 24px rgba(0,0,0,.22);padding:8px 0}
.mi{display:flex;align-items:center;gap:14px;height:48px;padding:0 16px;font-size:15px}
.mi svg{color:var(--muted)}
.mi.new{background:var(--tint)}
.badge{font-size:10.5px;font-weight:800;color:#fff;background:var(--accent);border-radius:5px;padding:1px 5px;margin-left:auto}
.cap{position:absolute;left:0;right:0;bottom:-1px}
.tabs{display:flex;margin:8px 16px;background:#fff;border:1px solid var(--hair);border-radius:14px;padding:4px}
.tabs div{flex:1;text-align:center;padding:9px;border-radius:10px;font-weight:700;color:var(--muted)}
.tabs .on{background:#E6ECFF;color:var(--accent2)}
p.body{margin:0;line-height:1.75;font-size:15px}
sup{font-size:9px;color:var(--muted)}
.seg{display:flex;border:1px solid #9AA7B8;border-radius:20px;overflow:hidden;font-size:13px;font-weight:600}
.seg div{flex:1;text-align:center;padding:7px 4px;color:var(--ink);border-right:1px solid #9AA7B8}
.seg div:last-child{border-right:none}
.seg .on{background:#E6ECFF;color:var(--accent2)}
'''


def page(body, lang='zh-Hant-TW', time='18:24'):
    return f'''<!DOCTYPE html><html lang="{lang}"><head><meta charset="utf-8"><style>{CSS}</style></head><body>
<div class="status"><span>{time}</span><span style="display:flex;gap:6px;align-items:center">
<svg width="16" height="16" viewBox="0 0 24 24" fill="#17263F"><path d="M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3a4.237 4.237 0 0 0-6 0zm-4-4l2 2a7.074 7.074 0 0 1 10 0l2-2C15.14 9.14 8.87 9.14 5 13z"/></svg>
<svg width="16" height="16" viewBox="0 0 24 24" fill="#17263F"><path d="M2 22h20V2z"/></svg>
<svg width="16" height="16" viewBox="0 0 24 24" fill="#17263F"><path d="M15.67 4H14V2h-4v2H8.33C7.6 4 7 4.6 7 5.33v15.33C7 21.4 7.6 22 8.33 22h7.33c.74 0 1.34-.6 1.34-1.33V5.33C17 4.6 16.4 4 15.67 4z"/></svg></span></div>
{body}</body></html>'''


# ------------------------------------------------------------------------------------ screens

def person_page():
    meetings = [('新辦公室搬遷規劃與預算決議會議', '2026年10月14日'), ('公司尾牙籌備會議：流程、預算與節目', '2026年10月14日'), ('第三季行政預算檢討', '2026年9月30日')]
    ms = ''.join(f'<div class="row"><div class="main"><div class="n" style="font-weight:400">{t}</div><div class="s">{d}</div></div>{ic("more", "#5A6982")}</div><div class="hr"></div>' for t, d in meetings)
    return page(f'''
<div class="top"><div class="ib">{ic('back')}</div><div class="t">林雅婷</div></div>
<div class="pad note" style="margin-top:-6px">總務部 · 3 場會議</div>
<div class="pad" style="display:flex;gap:8px;margin:12px 0 4px"><span class="btn">全部改名</span><span class="btn">備註</span><span class="btn">合併至…</span><span class="btn warn">刪除</span></div>
<div class="card">
  <div style="display:flex;align-items:center;gap:10px">{ic('contacts', '#2F6BFF')}<div style="flex:1"><div style="font-weight:700">聯絡人</div>
  <div class="note">已連結：林雅婷（總務部）· 10/14 同步</div></div><span class="pill ok">已連結</span></div>
  <div style="display:flex;gap:4px;justify-content:flex-end;margin-top:6px"><span class="tb">從聯絡人更新</span><span class="tb" style="color:#5A6982">取消連結</span></div>
</div>
<div class="card">
  <div style="display:flex;align-items:center;gap:10px;margin-bottom:4px">{ic('mail', '#2F6BFF')}<div style="font-weight:700;flex:1">電子郵件</div></div>
  <div class="row"><div class="main"><div class="n">yating.lin@example.com</div><div class="s">來自聯絡人 · 寄信時預設使用</div></div>{ic('star', '#2F6BFF', 20)}</div>
  <div class="hr"></div>
  <div class="row"><div class="main"><div class="n">yating.lin@home.example</div><div class="s">來自聯絡人</div></div>{ic('more', '#5A6982')}</div>
  <div class="hr"></div>
  <div class="row" style="color:var(--accent);font-weight:600">{ic('add', '#2F6BFF')}新增電子郵件</div>
</div>
<div class="pad"><div class="label">有 林雅婷 的會議</div>{ms}</div>
''')


def rename_sheet(permission):
    if permission:
        query = '<div class="field focus">' + ic('search', '#5A6982') + '<span style="flex:1;color:#17263F">王建</span>' + ic('close', '#5A6982') + '</div>'
        body = f'''
<div class="label">名錄中符合</div>
<div class="note" style="padding:6px 0 4px">沒有符合的人</div>
<div class="label" style="display:flex;align-items:center;gap:6px">{ic('contacts', '#5A6982', 16)}聯絡人</div>
<div class="row"><div class="avatar">王</div><div class="main"><div class="n">王建宏</div><div class="s">chienhung.wang@example.com · 資訊部</div></div></div>
<div class="row"><div class="avatar">王</div><div class="main"><div class="n">王建民</div><div class="s">沒有電子郵件 · 業務部</div></div></div>
<div class="row" style="color:var(--accent);font-weight:600;gap:8px">{ic('add', '#2F6BFF')}新增「王建」為新的人</div>
<div class="hr"></div>
<div class="note" style="padding:10px 0 0">聯絡人只在這支手機上比對，不會上傳。可在「設定 › 說話者名錄」關閉。</div>'''
        title_note = '已允許讀取聯絡人：輸入時一併列出符合的聯絡人'
    else:
        query = '<div class="field">' + ic('search', '#5A6982') + '<span style="flex:1">輸入名字…</span></div>'
        body = f'''
<div class="row" style="gap:12px"><div class="avatar" style="background:#E6ECFF">{ic('contacts', '#1E4FD6', 20)}</div><div class="main"><div class="n" style="color:var(--accent2);font-weight:700">從聯絡人選擇…</div><div class="s">名字與電子郵件一起帶入，不需要權限</div></div></div>
<div class="hr"></div>
<div class="label">這場會議中</div>
<div class="row"><div class="main"><div class="n" style="color:#5A6982">林雅婷</div></div><div class="s note">已是 語者 2</div></div>
<div class="row"><div class="main"><div class="n" style="color:#5A6982">陳志明</div></div><div class="s note">我 · 已是 語者 1</div></div>
<div class="label">最近</div>
<div class="row"><div class="main"><div class="n">Mark</div></div><div class="note">業務部 · 5 場會議</div></div>
<div class="row"><div class="main"><div class="n">黃淑芬</div></div><div class="note">2 場會議</div></div>
<div class="label">所有人</div>
<div class="row"><div class="main"><div class="n">李俊傑</div></div><div class="note">1 場會議</div></div>
<div class="hr"></div>
<div class="row note" style="font-size:15px">清除名字（改回 語者 3）</div>'''
        title_note = None
    head = f'''<div style="display:flex;align-items:center"><div style="flex:1;font-size:19px;font-weight:700">語者 3 是誰？</div>
<div class="ib" style="color:var(--accent)">{ic('play', '#2F6BFF')}</div></div>'''
    sub = f'<div class="note" style="margin:-4px 0 8px">{title_note}</div>' if title_note else '<div style="height:6px"></div>'
    return page(f'''
<div class="top"><div class="ib">{ic('back')}</div><div class="t">新辦公室搬遷規劃與預算決議會議</div><div class="ib">{ic('search')}</div><div class="ib">{ic('more')}</div></div>
<div class="tabs"><div>摘要</div><div class="on">逐字稿</div></div>
<div class="scrim"></div>
<div class="sheet" style="top:150px"><div class="handle"></div>{head}{sub}{query}<div style="margin-top:8px">{body}</div></div>
''')


def meeting_menu():
    return page(f'''
<div class="top"><div class="ib">{ic('back')}</div><div class="t">新辦公室搬遷規劃與預算決議會議</div><div class="ib">{ic('search')}</div><div class="ib">{ic('more')}</div></div>
<div class="tabs"><div class="on">摘要</div><div>逐字稿</div></div>
<div class="card"><div style="display:flex;align-items:center;gap:10px"><span style="width:10px;height:10px;border-radius:5px;background:#2E9E63"></span><div style="flex:1"><div style="font-weight:700;font-size:17px">AI 筆記</div><div class="note">17 則筆記</div></div><span class="pill ok" style="font-size:13px;padding:4px 10px">完成</span></div></div>
<div class="card"><p class="body">本次會議主要討論了新辦公室的搬遷事宜，新辦公室預計於二月二十號交屋，預計三月七號搬家，並於三月十號正式上班<sup>0:00</sup>。在搬家公司報價範圍為八萬二、十一萬及十四萬五三種選項中，會議決定採用中間的十一萬報價<sup>0:35</sup>。為確保搬遷流程順利，林雅婷需請法務審閱合約<sup>1:12</sup>…</p>
<div class="note" style="margin-top:8px">AI 摘要，可能有誤，請對照逐字稿確認。</div></div>
<div class="card" style="display:flex;align-items:center;gap:12px"><div style="font-weight:700;font-size:16px;color:#5A6982">4 位語者</div>
<div style="flex:1;height:8px;border-radius:4px;background:linear-gradient(90deg,#D64545 0 44%,#E8730C 44% 78%,#1E6FD9 78% 96%,#0F9D8A 96%)"></div>
<span class="chip on" style="font-size:12.5px">{ic('mail', '#1E4FD6', 16)}寄給與會者</span></div>
<div class="scrim" style="background:rgba(15,20,30,.10)"></div>
<div class="menu">
<div class="mi">{ic('refresh')}重新轉錄</div>
<div class="mi">{ic('summ')}重新摘要</div>
<div class="hr" style="margin:6px 0"></div>
<div class="mi new">{ic('mail', '#2F6BFF')}<span style="color:#1E4FD6;font-weight:600">寄給與會者…</span><span class="badge">新</span></div>
<div class="mi">{ic('share')}匯出與分享…</div>
<div class="hr" style="margin:6px 0"></div>
<div class="mi">{ic('tune')}設定</div>
</div>
''')


def participants_sheet(en=False):
    if en:
        L = dict(title='Email participants', sub='Remote Control Product Concept… · Oct 13', rec='Recipients', missing='No e-mail address',
                 addr='name@example.com', pick='Contacts', me='Me', notme='not sent to yourself', unnamed='Speaker 5 has no name ·',
                 name='Name', add='Add another recipient', content='Content', c1='Summary', c2='Minutes', c3='Decisions & actions only',
                 att='Attach', none='None', lang='Language', langv='English (meeting language)',
                 last='Last shared Oct 13, 11:02 · Jana, Francine', prev='Preview', go='Open in mail app (2)', other='Share to another app…',
                 foot='Prime Minutes never sends mail itself: your mail app opens with everything filled in, and you send it there.')
        people = [('Jana', 'jana@example.com', True, None), ('Francine', 'francine@example.com', True, None),
                  ('Marco', None, False, 'marco'), ('Ben', 'ben@example.com', False, 'me')]
        langcode, time = 'en', '11:31'
    else:
        L = dict(title='寄給與會者', sub='新辦公室搬遷規劃與預算決議會議 · 10/14', rec='收件人', missing='沒有電子郵件',
                 addr='輸入電子郵件', pick='聯絡人', me='我', notme='不寄給自己', unnamed='語者 4 尚未命名 ·', name='命名',
                 add='新增其他收件人', content='內容', c1='摘要', c2='會議紀要', c3='只寄決議與待辦', att='附件', none='無',
                 lang='郵件語言', langv='繁體中文（會議語言）', last='上次分享：10/14 18:20 · 林雅婷', prev='預覽郵件',
                 go='在郵件 App 中開啟（2 位）', other='分享到其他 App…',
                 foot='Prime Minutes 不會替你寄信：郵件 App 會開啟並填好收件人與內容，由你確認後送出。')
        people = [('林雅婷', 'yating.lin@example.com', True, None), ('王建宏', None, True, 'typing'),
                  ('陳志明', 'chihming.chen@example.com', False, 'me')]
        langcode, time = 'zh-Hant-TW', '18:31'
    rows = ''
    for name, mail, on, kind in people:
        av = f'<div class="avatar">{name[0]}</div>'
        if kind == 'typing':
            rows += f'''<div class="row" style="align-items:flex-start;padding-top:10px">{f'<div class="cb on" style="margin-top:8px"></div>'}{av}<div class="main"><div class="n">{name}</div>
<div class="s" style="color:var(--warn)">{L['missing']}</div>
<div style="display:flex;gap:8px;margin-top:6px"><div class="field focus" style="height:40px;flex:1;font-size:14px"><span style="color:#17263F">chienhung.wang@example.com</span><span style="width:1.5px;height:18px;background:#2F6BFF"></span></div>
<span class="btn" style="padding:6px 10px;font-size:13px">{ic('contacts', '#2F6BFF', 16)}{L['pick']}</span></div>
<div class="note" style="margin-top:4px">也會存到 王建宏 的名錄資料</div></div></div>'''
        elif kind == 'marco':
            rows += f'''<div class="row"><div class="cb"></div>{av}<div class="main"><div class="n">{name}</div><div class="s" style="color:var(--warn)">{L['missing']}</div></div>
<span class="btn" style="padding:6px 10px;font-size:13px">{ic('add', '#2F6BFF', 16)}Add</span></div>'''
        elif kind == 'me':
            rows += f'''<div class="row"><div class="cb"></div>{av}<div class="main"><div class="n">{name} <span class="pill grey">{L['me']}</span></div><div class="s">{mail} · {L['notme']}</div></div></div>'''
        else:
            rows += f'''<div class="row"><div class="cb on"></div>{av}<div class="main"><div class="n">{name}</div><div class="s">{mail}</div></div></div>'''
        rows += '<div class="hr" style="margin-left:32px"></div>'
    att_chips = f'<span class="chip on">{L["none"]}</span><span class="chip">PDF</span><span class="chip">TXT</span><span class="chip">.m4a · {"14 MB" if en else "4.0 MB"}</span>'
    return page(f'''
<div class="top"><div class="ib">{ic('back')}</div><div class="t">{'Remote Control Product Concept and Feature Discussion' if en else '新辦公室搬遷規劃與預算決議會議'}</div><div class="ib">{ic('search')}</div><div class="ib">{ic('more')}</div></div>
<div class="tabs"><div class="on">{'Summary' if en else '摘要'}</div><div>{'Transcript' if en else '逐字稿'}</div></div>
<div class="scrim"></div>
<div class="sheet" style="top:96px;overflow:hidden"><div class="handle"></div>
<div style="font-size:20px;font-weight:700">{L['title']}</div><div class="note" style="margin-bottom:2px">{L['sub']}</div>
<div class="label">{L['rec']}</div>
{rows}
{'' if en else f'<div class="row note" style="min-height:40px;font-size:13.5px;gap:8px">{ic("person", "#5A6982", 18)}<span>{L["unnamed"]} <span style="color:var(--accent);font-weight:600">{L["name"]}</span></span></div>'}
<div class="row" style="min-height:40px;color:var(--accent);font-weight:600;gap:8px;font-size:14.5px">{ic('add', '#2F6BFF', 20)}{L['add']}</div>
<div class="label" style="display:flex;align-items:center"><span style="flex:1">{L['content']}</span><span style="color:var(--accent);font-size:13.5px">{L['prev']}</span></div>
<div style="display:flex;gap:6px;flex-wrap:wrap"><span class="chip on">✓ {L['c1']}</span><span class="chip on">✓ {L['c2']}</span><span class="chip">{L['c3']}</span></div>
<div style="display:flex;align-items:center;gap:8px;margin-top:10px;flex-wrap:wrap"><span class="note" style="display:flex;align-items:center;gap:4px">{ic('attach', '#5A6982', 16)}{L['att']}</span>{att_chips}</div>
<div style="display:flex;align-items:center;gap:8px;margin-top:10px"><span class="note">{L['lang']}</span><span class="chip">{L['langv']} {ic('expand', '#5A6982', 16)}</span></div>
<div class="note" style="display:flex;align-items:center;gap:6px;margin-top:12px">{ic('history', '#5A6982', 16)}{L['last']}</div>
<div style="display:flex;flex-direction:column;gap:8px;margin-top:12px">
<span class="btn fill" style="justify-content:center;padding:12px;font-size:15px">{ic('mail', '#fff', 20)}{L['go']}</span>
<span class="btn" style="justify-content:center;padding:10px">{ic('share', '#2F6BFF', 18)}{L['other']}</span></div>
<div class="note" style="margin-top:10px;text-align:center">{L['foot']}</div>
</div>''', lang=langcode, time=time)


def permission_screen():
    bullets = [('contacts', '讀取什麼', '聯絡人的姓名、電子郵件與公司。不讀電話紀錄、相片或其他 App 的資料。'),
               ('lock', '存在哪裡', '只在這支手機上比對。名錄只保存你選用的人，不上傳、不備份、不放進匯出檔。'),
               ('person', '做什麼用', '命名語者時列出符合的聯絡人；聯絡人改了電子郵件時，名錄跟著更新。'),
               ('tune', '隨時可關閉', '「設定 › 說話者名錄 › 從聯絡人建議」。關閉後已存的名字與電子郵件不受影響。')]
    b = ''.join(f'<div class="row" style="align-items:flex-start;gap:14px;padding:10px 0"><div style="margin-top:2px">{ic(i, "#2F6BFF")}</div><div class="main"><div class="n" style="font-weight:700">{t}</div><div class="note" style="font-size:14px">{d}</div></div></div>' for i, t, d in bullets)
    return page(f'''
<div class="top"><div class="ib">{ic('close')}</div><div class="t"></div></div>
<div class="pad">
<div style="width:64px;height:64px;border-radius:20px;background:#E6ECFF;display:flex;align-items:center;justify-content:center;margin:8px 0 18px">{ic('contacts', '#1E4FD6', 34)}</div>
<div style="font-size:23px;font-weight:700;line-height:1.35">命名語者時，從聯絡人建議名字？</div>
<div class="note" style="font-size:14.5px;margin:10px 0 8px">不開啟也能用：「從聯絡人選擇…」每次只帶入你挑的那一位，不需要權限。開啟後，輸入名字時就會直接列出符合的聯絡人。</div>
{b}
</div>
<div class="cap" style="padding:16px;background:var(--ground)">
<span class="btn fill" style="display:flex;justify-content:center;padding:13px;font-size:15.5px">繼續，允許讀取聯絡人</span>
<div style="text-align:center;margin-top:10px" class="tb">不用，我用挑選的就好</div>
<div class="note" style="text-align:center;margin-top:4px">按「繼續」後，系統會再詢問一次。</div>
</div>''')


SCREENS = [
    ('01-person-contact.zh-TW', person_page),
    ('02a-rename-sheet-picker.zh-TW', lambda: rename_sheet(False)),
    ('02b-rename-sheet-autocomplete.zh-TW', lambda: rename_sheet(True)),
    ('03-meeting-menu.zh-TW', meeting_menu),
    ('04-participants-sheet.zh-TW', participants_sheet),
    ('05-contacts-permission.zh-TW', permission_screen),
    ('06-participants-sheet.en', lambda: participants_sheet(True)),
]

if __name__ == '__main__':
    for name, f in SCREENS:
        src = os.path.join(HERE, name + '.html')
        open(src, 'w', encoding='utf-8').write(f())
        subprocess.run(['google-chrome', '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=2',
                        '--window-size=412,915', f'--screenshot={os.path.join(HERE, name + ".png")}', 'file://' + src],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print(name)
