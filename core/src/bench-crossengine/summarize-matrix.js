// run-matrix.sh の出力ディレクトリを読み、反復の中央値を Markdown の表にする。
//   node summarize-matrix.js <出力ディレクトリ>
// :core は RESULT 行（mean / p50 / alloc）、Chrome は trace.js の 1 フレームの CPU 時間（main + raster + draw。tr.js と同じ合計）。
const fs = require('fs'), path = require('path');
const dir = process.argv[2];
if (!dir) { console.error('usage: node summarize-matrix.js <dir>'); process.exit(2); }
const med = a => { const s = [...a].sort((x, y) => x - y); return s.length ? s[s.length >> 1] : NaN; };
const range = a => a.length ? `${Math.min(...a).toFixed(a[0] < 1 ? 3 : 2)}–${Math.max(...a).toFixed(a[0] < 1 ? 3 : 2)}` : '';
const f = v => !isFinite(v) ? '—' : v < 0.01 ? v.toFixed(4) : v < 1 ? v.toFixed(3) : v.toFixed(2);

function readFine(file) {
  const rows = new Map();
  if (!fs.existsSync(file)) return rows;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const m = line.match(/^\[(.*?)\] RESULT scenario=(\S+) doc=(\S+) backend=(\S+) frames=(\d+) drawn=(\d+) mean=([\d.]+) p50=([\d.]+) .* alloc=([\d.]+) KB/);
    if (!m) continue;
    const tag = m[1].replace(/rep=\d+\s*/, '');
    const key = [m[3], m[2], m[4], tag].join('|');
    if (!rows.has(key)) rows.set(key, { doc: m[3], scen: m[2], be: m[4], tag, mean: [], p50: [], alloc: [], drawn: [] });
    const r = rows.get(key);
    r.mean.push(+m[7]); r.p50.push(+m[8]); r.alloc.push(+m[9]); r.drawn.push(+m[6]);
  }
  return rows;
}

function readChrome(file) {
  const rows = new Map();
  if (!fs.existsSync(file)) return rows;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    if (!line.startsWith('{')) continue;
    const o = JSON.parse(line), g = k => o.perFrameMs[k] ? o.perFrameMs[k].ms : 0;
    const total = g('ProxyMain::BeginMainFrame') + g('RasterTask') + g('Display::DrawAndSwap');
    const key = [o.doc, o.scen, o.gpuMode, o.font || 'default'].join('|');
    if (!rows.has(key)) rows.set(key, { total: [], main: [] });
    rows.get(key).total.push(total); rows.get(key).main.push(g('ProxyMain::BeginMainFrame'));
  }
  return rows;
}

const chrome = readChrome(path.join(dir, 'chrome-trace.log'));
const chromeOf = (doc, scen, be, font) => { const r = chrome.get([doc, scen, be === 'cpu' ? 'soft' : 'gpu', font].join('|')); return r ? r.total : []; };

// ---- A3 ----
const th = readFine(path.join(dir, 'threads.log'));
if (th.size) {
  console.log('## A3: ワーカー数ごとの fontsize（GL・内蔵の簡易書体・' + [...th.values()][0].mean.length + ' 回の中央値）\n');
  console.log('| 文書 | ワーカー | mean ms（範囲） | p50 ms | 確保 KB/フレーム | 144Hz（6.94 ms） |');
  console.log('| :-- | :--: | :--: | :--: | :--: | :--: |');
  for (const r of th.values()) {
    const m = med(r.mean);
    console.log(`| ${r.doc} | ${r.tag.replace('threads=', '')} | ${f(m)}（${range(r.mean)}） | ${f(med(r.p50))} | ${med(r.alloc).toFixed(0)} | ${m <= 6.94 ? '✅' : '❌'} |`);
  }
  console.log();
}

// ---- A2 ----
const fo = readFine(path.join(dir, 'font.log'));
if (fo.size) {
  console.log('## A2: 同じ書体での Chrome 比較（:core は ' + [...fo.values()][0].mean.length + ' 回、Chrome は trace.js の各反復の中央値）\n');
  console.log('| 文書 | シナリオ | :core 簡易書体 mean | :core 実書体 mean（p50・確保 KB） | Chrome 既定書体 | Chrome 実書体 | 比（実書体どうし） | 旧条件の比（簡易 ÷ 既定） |');
  console.log('| :-- | :-- | :--: | :--: | :--: | :--: | :--: | :--: |');
  const keys = new Set([...fo.values()].map(r => [r.doc, r.scen, r.be].join('|')));
  for (const k of keys) {
    const [doc, scen, be] = k.split('|');
    const fb = [...fo.values()].find(r => r.doc === doc && r.scen === scen && r.be === be && r.tag === 'font=fallback');
    const rf = [...fo.values()].find(r => r.doc === doc && r.scen === scen && r.be === be && r.tag !== 'font=fallback');
    const font = rf ? rf.tag.replace('font=', '') : '';
    const cd = med(chromeOf(doc, scen, be, 'default')), cf = med(chromeOf(doc, scen, be, font));
    const a = fb ? med(fb.mean) : NaN, b = rf ? med(rf.mean) : NaN;
    const noop = rf && med(rf.drawn) === 0 ? '（描画なし）' : '';
    console.log(`| ${doc} | ${scen}（${be}） | ${f(a)} | ${f(b)}（${rf ? f(med(rf.p50)) : '—'}・${rf ? med(rf.alloc).toFixed(1) : '—'}）${noop} | ${f(cd)} | ${f(cf)} | ${isFinite(b / cf) ? (b / cf).toFixed(2) + 'x' : '—'} | ${isFinite(a / cd) ? (a / cd).toFixed(2) + 'x' : '—'} |`);
  }
  console.log();
}

// ---- A12 ----
const re = readFine(path.join(dir, 'real.log'));
if (re.size) {
  console.log('## A12: 実文書（GL・実書体・' + [...re.values()][0].mean.length + ' 回の中央値）\n');
  console.log('| 文書 | シナリオ | :core mean ms（範囲） | p50 ms | 確保 KB/フレーム | 描いたフレーム | Chrome ms | 比 | 144Hz |');
  console.log('| :-- | :-- | :--: | :--: | :--: | :--: | :--: | :--: | :--: |');
  for (const r of re.values()) {
    const m = med(r.mean), font = r.tag.replace('font=', '');
    const c = med(chromeOf(r.doc, r.scen, r.be, font));
    const drawn = med(r.drawn);
    console.log(`| ${r.doc} | ${r.scen} | ${f(m)}（${range(r.mean)}） | ${f(med(r.p50))} | ${med(r.alloc).toFixed(1)} | ${drawn === 0 ? '0（変化なし）' : drawn} | ${f(c)} | ${drawn === 0 || !isFinite(m / c) ? '—' : (m / c).toFixed(2) + 'x'} | ${m <= 6.94 ? '✅' : '❌'} |`);
  }
  console.log();
}

// ---- run.js（幅変更・open）----
const res = fs.readdirSync(dir).filter(n => /^res-chrome-.*\.json$/.test(n));
if (res.length) {
  console.log('## Chrome の幅変更（relayout.styleLayout）と open（run.js。ページ内の計測の中央値 ms）\n');
  console.log('| ファイル | 文書 | 書体 | 幅変更 style+layout | open |');
  console.log('| :-- | :-- | :-- | :--: | :--: |');
  for (const n of res.sort()) {
    const o = JSON.parse(fs.readFileSync(path.join(dir, n), 'utf8'));
    const rl = o.res['relayout.styleLayout'], op = o.res.open;
    console.log(`| ${n} | ${o.doc} | ${o.font || '既定'} | ${rl ? f(rl.median) : '—'} | ${op ? f(op.median) : '—'} |`);
  }
}
