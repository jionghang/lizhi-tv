#!/usr/bin/env node
/*
 * gdtv 节目单抓取（PC 侧）
 * -----------------------------------------------------------------------------
 * 为什么要跑浏览器：
 *   gdtv-api 的请求头签名（X-ITOUCHTV-Ca-Signature）由页面里的 WASM 现算，
 *   裸请求一律 401。所以这里起一个无头 Edge，让官网页面自己去签名，
 *   我们只在 CDP 网络层把 /tvMenu 的响应体抄下来。
 *
 * 用法：
 *   node tools/epg/harvest.mjs --pk 43,44,45 --out out/epg
 *   node tools/epg/harvest.mjs --all --out out/epg        （频道 pk 从 channels.json 读）
 *
 * 产物：<out>/<pk>.json  ——  {"pk":43,"kind":"tv","url":"...","days":[{date,items:[{beginAt,endAt,name}]}]}
 */

import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');

const EDGE_CANDIDATES = [
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
];

const args = process.argv.slice(2);
function arg(name, def) {
  const i = args.indexOf('--' + name);
  return i >= 0 && args[i + 1] ? args[i + 1] : def;
}
const OUT = resolve(ROOT, arg('out', 'out/epg'));
const PORT = Number(arg('port', '9223'));
const NAV_TIMEOUT = Number(arg('nav-timeout', '20000'));
const IDLE_WAIT = Number(arg('idle', '6000'));
const CONCURRENCY = Number(arg('jobs', '1'));
const DEBUG = args.includes('--debug');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
  + '(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36';

/* ---------------------------------------------------------------- 频道清单 */

function loadChannels() {
  const p = join(ROOT, 'assets/channels.json');
  const d = JSON.parse(readFileSync(p, 'utf8'));
  const out = [];
  for (const c of d.tv || []) out.push({ pk: c.pk, kind: 'tv', name: c.name });
  for (const c of d.radio || []) out.push({ pk: c.pk, kind: 'radio', name: c.name });
  return out;
}

function pageUrl(pk, kind) {
  return 'https://www.gdtv.cn/'
    + (kind === 'tv' ? 'tvChannelDetail/' : 'audioChannelDetail/') + pk;
}

/* ------------------------------------------------------------------- CDP */

class Cdp {
  constructor(wsUrl) {
    this.id = 0;
    this.pending = new Map();
    this.handlers = new Set();
    this.ws = new WebSocket(wsUrl);
    this.ready = new Promise((res, rej) => {
      this.ws.addEventListener('open', () => res());
      this.ws.addEventListener('error', (e) => rej(new Error('ws ' + (e.message || 'error'))));
    });
    this.ws.addEventListener('message', (ev) => {
      let m;
      try { m = JSON.parse(ev.data); } catch { return; }
      if (m.id && this.pending.has(m.id)) {
        const { res, rej } = this.pending.get(m.id);
        this.pending.delete(m.id);
        if (m.error) rej(new Error(m.error.message || JSON.stringify(m.error)));
        else res(m.result);
        return;
      }
      for (const h of this.handlers) h(m);
    });
  }
  send(method, params = {}, sessionId) {
    const id = ++this.id;
    const msg = { id, method, params };
    if (sessionId) msg.sessionId = sessionId;
    this.ws.send(JSON.stringify(msg));
    return new Promise((res, rej) => {
      this.pending.set(id, { res, rej });
      setTimeout(() => {
        if (this.pending.has(id)) { this.pending.delete(id); rej(new Error('timeout ' + method)); }
      }, 30000);
    });
  }
  on(fn) { this.handlers.add(fn); return () => this.handlers.delete(fn); }
  close() { try { this.ws.close(); } catch { } }
}

async function findBrowser() {
  for (const p of EDGE_CANDIDATES) if (existsSync(p)) return p;
  throw new Error('找不到 Edge/Chrome 可执行文件');
}

async function waitJson(url, tries = 100) {
  for (let i = 0; i < tries; i++) {
    try {
      const r = await fetch(url);
      if (r.ok) return await r.json();
    } catch { }
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error('CDP 端口没起来: ' + url);
}

async function launch() {
  const exe = await findBrowser();
  const profile = join(tmpdir(), 'lz-epg-profile-' + Date.now());
  mkdirSync(profile, { recursive: true });
  const proc = spawn(exe, [
    '--headless=new',
    '--remote-debugging-port=' + PORT,
    '--user-data-dir=' + profile,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-gpu',
    '--mute-audio',
    '--disable-extensions',
    '--disable-background-networking',
    '--window-size=1280,800',
    'about:blank',
  ], { stdio: 'ignore', detached: false });

  const ver = await waitJson(`http://127.0.0.1:${PORT}/json/version`);
  return { proc, profile, wsUrl: ver.webSocketDebuggerUrl, version: ver.Browser };
}

/* --------------------------------------------------------- 单频道抓取 */

const isMenu = (u) => /\/tvMenu|\/audioMenu|\/tvMenu\/|menu\?/i.test(u);

async function harvestOne(browser, job) {
  const url = pageUrl(job.pk, job.kind);
  const { targetId } = await browser.send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await browser.send('Target.attachToTarget', { targetId, flatten: true });

  const seen = new Map();      // requestId -> url
  const bodies = new Map();    // requestId -> {url, json}   （loadingFinished 就地取，避免被回收）
  const bodyErr = [];
  const grab = (requestId, u) => {
    if (bodies.has(requestId)) return;
    bodies.set(requestId, null);
    browser.send('Network.getResponseBody', { requestId }, sessionId)
      .then((b) => {
        const text = b.base64Encoded
          ? Buffer.from(b.body, 'base64').toString('utf8')
          : b.body;
        bodies.set(requestId, { url: u, json: JSON.parse(text) });
      })
      .catch((e) => {
        bodies.delete(requestId);
        bodyErr.push(u.slice(0, 80) + ': ' + e.message);
      });
  };

  const off = browser.on((m) => {
    if (m.sessionId !== sessionId) return;
    if (m.method === 'Network.responseReceived') {
      const r = m.params.response;
      if (isMenu(r.url)) { seen.set(m.params.requestId, r.url); grab(m.params.requestId, r.url); }
      else if (DEBUG && /gdtv-api|tcdn-api/.test(r.url)) {
        console.log(`    · ${r.status} ${r.url.slice(0, 140)}`);
      }
    } else if (m.method === 'Network.loadingFinished' && seen.has(m.params.requestId)) {
      grab(m.params.requestId, seen.get(m.params.requestId));
    }
  });

  await browser.send('Network.enable', {}, sessionId);
  // 命中缓存的响应拿不到 body（CDP 报 "No resource with given identifier"），一律走网络
  await browser.send('Network.setCacheDisabled', { cacheDisabled: true }, sessionId);
  await browser.send('Page.enable', {}, sessionId);
  await browser.send('Network.setUserAgentOverride',
    { userAgent: UA, acceptLanguage: 'zh-CN,zh;q=0.9', platform: 'Win32' }, sessionId);

  let done;
  const idle = new Promise((res) => { done = res; });
  await browser.send('Page.navigate', { url }, sessionId);

  // 等到「出现 menu 响应后再静默 IDLE_WAIT」，或硬超时
  const t0 = Date.now();
  let lastCount = -1;
  let lastChange = Date.now();
  while (Date.now() - t0 < NAV_TIMEOUT) {
    await new Promise((r) => setTimeout(r, 500));
    if (seen.size !== lastCount) { lastCount = seen.size; lastChange = Date.now(); }
    if (seen.size > 0 && Date.now() - lastChange > IDLE_WAIT) break;
    if (seen.size === 0 && Date.now() - lastChange > NAV_TIMEOUT - 2000) break;
  }

  // 等已抓到的 body 落地（就地取是异步的）
  const t0b = Date.now();
  while (Date.now() - t0b < 4000) {
    const pending = [...bodies.values()].some((v) => v === null);
    if (!pending) break;
    await new Promise((r) => setTimeout(r, 100));
  }
  const out = [...bodies.values()].filter(Boolean);

  off();
  try { await browser.send('Target.closeTarget', { targetId }); } catch { }

  return { job, url, bodies: out, errors: bodyErr, elapsed: Date.now() - t0 };
}

/* ------------------------------------------------------------ 解析节目单 */

function parseMenu(json, pk) {
  // 结构：{ resultList: [ { date/day, tvMenus: [ {beginAt,endAt,name,...} ] } ] }
  const roots = [];
  const push = (o) => { if (o && typeof o === 'object') roots.push(o); };
  push(json);
  if (json && json.data) push(json.data);
  if (json && json.result) push(json.result);

  let list = null;
  for (const r of roots) {
    const l = r.resultList || r.menus || r.list;
    if (Array.isArray(l) && l.length) { list = l; break; }
  }
  if (!list) {
    // 有些接口直接给一层数组
    for (const r of roots) if (Array.isArray(r)) { list = r; break; }
  }
  if (!list) return null;

  const days = [];
  for (const d of list) {
    const items = [];
    const arr = d.tvMenus || d.menus || d.list || (Array.isArray(d) ? d : null);
    if (Array.isArray(arr)) {
      for (const m of arr) {
        const name = String(m.name || m.title || '').trim();
        if (!name) continue;
        const b = Number(m.beginAt ?? m.beginTime ?? m.startTime ?? 0);
        const e = Number(m.endAt ?? m.endTime ?? m.stopTime ?? 0);
        if (!b || !e) continue;
        items.push({ beginAt: b, endAt: e, name });
      }
    }
    if (items.length) {
      items.sort((a, b) => a.beginAt - b.beginAt);
      days.push({ date: d.date || d.day || d.showDate || (items[0].beginAt), items });
    }
  }
  if (!days.length) return null;
  // 跨天去重（同一节目可能出现在相邻两天的返回里）
  const seen = new Set();
  const merged = [];
  for (const d of days) {
    for (const it of d.items) {
      const k = it.beginAt + '|' + it.endAt + '|' + it.name;
      if (seen.has(k)) continue;
      seen.add(k);
      merged.push(it);
    }
  }
  merged.sort((a, b) => a.beginAt - b.beginAt);
  return { pk, days, merged };
}

/* ------------------------------------------------------------------ main */

async function main() {
  const all = loadChannels();
  let list = all;
  const pkArg = arg('pk', '');
  const namesArg = arg('name', '');
  if (pkArg) {
    const pks = pkArg.split(',').map((s) => Number(s.trim())).filter(Boolean);
    list = pks.map((pk) => all.find((c) => c.pk === pk) || { pk, kind: 'tv', name: 'pk' + pk });
  } else if (namesArg) {
    list = all.filter((c) => namesArg.split(',').some((n) => c.name.includes(n.trim())));
  }

  mkdirSync(OUT, { recursive: true });
  console.log(`[epg] ${list.length} 个频道 -> ${OUT}`);

  const b = await launch();
  console.log('[epg] browser:', b.version);

  const browser = new Cdp(b.wsUrl);
  await browser.ready;

  const results = [];
  const queue = list.slice();
  const workers = Array.from({ length: Math.max(1, CONCURRENCY) }, async () => {
    while (queue.length) {
      const job = queue.shift();
      const tag = `#${job.pk} ${job.name}`;
      try {
        const r = await harvestOne(browser, job);
        const parsed = [];
        for (const body of r.bodies) {
          const p = parseMenu(body.json, job.pk);
          if (p) parsed.push({ url: body.url, ...p });
        }
        const best = parsed.sort((a, x) => x.merged.length - a.merged.length)[0];
        if (!best) {
          console.log(`[epg] ${tag} ✗ 没有可解析的节目单 response=${r.bodies.length} err=${r.errors.join(';')}`);
          results.push({ pk: job.pk, kind: job.kind, name: job.name, ok: false });
          continue;
        }
        const file = join(OUT, `${job.pk}.json`);
        writeFileSync(file, JSON.stringify({
          pk: job.pk, kind: job.kind, name: job.name, page: r.url,
          fetchedAt: Date.now(), source: best.url,
          items: best.merged,
        }, null, 1));
        const first = best.merged[0], last = best.merged[best.merged.length - 1];
        console.log(`[epg] ${tag} ✓ ${best.merged.length} 条  `
          + `${fmt(first.beginAt)} ~ ${fmt(last.endAt)}  ${r.elapsed}ms`);
        results.push({ pk: job.pk, kind: job.kind, name: job.name, ok: true, n: best.merged.length });
      } catch (e) {
        console.log(`[epg] ${tag} ✗ ${e.message}`);
        results.push({ pk: job.pk, kind: job.kind, name: job.name, ok: false });
      }
    }
  });
  await Promise.all(workers);

  browser.close();
  try { b.proc.kill(); } catch { }
  setTimeout(() => { try { rmSync(b.profile, { recursive: true, force: true }); } catch { } }, 1500);

  const ok = results.filter((r) => r.ok).length;
  console.log(`\n[epg] 完成 ${ok}/${results.length}`);
  writeFileSync(join(OUT, '_summary.json'), JSON.stringify(results, null, 1));
  if (ok === 0) process.exitCode = 1;
}

function fmt(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

main().catch((e) => { console.error('[epg] fatal', e); process.exit(1); });
