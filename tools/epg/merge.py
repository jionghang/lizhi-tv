#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 tools/epg/harvest.mjs 抓到的节目单合并进 assets/channels.json。

用法：
    node tools/epg/harvest.mjs --all --out .tmp/epg --jobs 3
    python tools/epg/merge.py [--src .tmp/epg] [--keep-hours-back 6] [--keep-hours-ahead 96]

规则：
  * 只处理 .tmp/epg/<pk>.json 里存在的频道；没抓到的频道保留原 programs 不动。
  * 抓到了但 items 为空 / 全部过期（绝不覆盖成空，避免把能用的旧数据洗掉）。
  * 裁剪到 [now-回看, now+前瞻]，多余的不进 APK —— 界面只用当前节目 + 下一个。
  * programs 统一成 [beginAt, endAt, name] 三元组，与 ChannelRepo 解析的格式一致。
"""
import argparse
import json
import os
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TARGET = os.path.join(ROOT, 'assets', 'channels.json')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', default=os.path.join(ROOT, '.tmp', 'epg'))
    ap.add_argument('--keep-hours-back', type=float, default=6)
    ap.add_argument('--keep-hours-ahead', type=float, default=96)
    ap.add_argument('--dry', action='store_true')
    a = ap.parse_args()

    now = int(time.time() * 1000)
    lo = now - int(a.keep_hours_back * 3600 * 1000)
    hi = now + int(a.keep_hours_ahead * 3600 * 1000)

    doc = json.load(open(TARGET, encoding='utf-8'))
    old_gen = doc.get('generated')
    before = sum(len(c.get('programs') or []) for k in ('tv', 'radio') for c in doc[k])

    refreshed, kept, empty = [], [], []
    for key in ('tv', 'radio'):
        for c in doc[key]:
            f = os.path.join(a.src, '%d.json' % c['pk'])
            if not os.path.exists(f):
                kept.append((c['pk'], c['name'], '无抓取结果'))
                continue
            d = json.load(open(f, encoding='utf-8'))
            items = [x for x in d.get('items') or []
                     if x.get('name') and x.get('endAt', 0) > lo and x.get('beginAt', 0) < hi]
            items.sort(key=lambda x: x['beginAt'])
            if not items:
                empty.append((c['pk'], c['name'], '抓到了但全过期/为空'))
                continue
            c['programs'] = [[int(x['beginAt']), int(x['endAt']), x['name'].strip()] for x in items]
            refreshed.append((c['pk'], c['name'], len(items),
                              items[0]['beginAt'], items[-1]['endAt']))

    after = sum(len(c.get('programs') or []) for k in ('tv', 'radio') for c in doc[k])
    doc['generated'] = now

    def hm(ms):
        return time.strftime('%m-%d %H:%M', time.localtime(ms / 1000))

    print('刷新 %d 个频道，保留原样 %d 个，无数据 %d 个'
          % (len(refreshed), len(kept), len(empty)))
    for pk, name, n, b, e in refreshed:
        print('  ✓ %-3d %-14s %4d 条  %s ~ %s' % (pk, name, n, hm(b), hm(e)))
    for pk, name, why in kept:
        print('  = %-3d %-14s %s' % (pk, name, why))
    for pk, name, why in empty:
        print('  · %-3d %-14s %s' % (pk, name, why))
    print('节目条数 %d -> %d；generated %s -> %s'
          % (before, after, old_gen, now))

    if a.dry:
        print('（--dry，未写文件）')
        return 0
    with open(TARGET, 'w', encoding='utf-8', newline='') as f:
        json.dump(doc, f, ensure_ascii=False, separators=(',', ':'))
    print('已写入 %s  %.0f KB' % (TARGET, os.path.getsize(TARGET) / 1024))
    return 0


if __name__ == '__main__':
    sys.exit(main())
