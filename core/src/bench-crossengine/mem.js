// Chrome のレンダラのメモリを、同じ HTML で測る（空ページとの差が文書の分）。
// 使い方: VARIED_FILE=<varied-list-N.html> node mem.js   （large-list-N は内蔵の生成。varied は Workloads の main で書き出したファイル）
// 測るもの: レンダラの Private（コミット）と WorkingSet（PowerShell の Get-Process）、JS ヒープ・DOM ノード数・レイアウトオブジェクト数（CDP）
const http = require('http'), fs = require('fs'), os = require('os'), path = require('path'), cp = require('child_process');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

function largeList(n) {
  let s = '<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; } li { padding: 4px; border-bottom: 1px solid #ccc; }</style></head><body><div id="scroller" style="height: 400px; overflow-y: auto;"><ul>';
  for (let i = 1; i <= n; i++) s += '<li id="item-' + i + '">Row item #' + i + ' with description text</li>';
  return s + '</ul></div></body></html>';
}
const docs = {
  blank: () => '<html><body></body></html>',
  'large-list-500': () => largeList(500),
  'large-list-2000': () => largeList(2000),
  'varied-list-2000': () => fs.readFileSync(process.env.VARIED_FILE, 'utf8'),
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function measure(name) {
  const srv = http.createServer((req, res) => { res.setHeader('Content-Type', 'text/html'); const n = decodeURIComponent(req.url.slice(1)); res.end((docs[n] || docs.blank)()); });
  await new Promise(r => srv.listen(0, r));
  const port = srv.address().port, dbg = 9700 + Math.floor(Math.random() * 200);
  const prof = fs.mkdtempSync(path.join(os.tmpdir(), 'mem-'));
  const child = cp.spawn(CHROME, ['--headless=new', '--no-first-run', '--user-data-dir=' + prof, '--window-size=800,600',
    '--renderer-process-limit=1', '--disable-features=SpareRendererForSitePerProcess,SiteIsolationForCrossOriginOpenerPolicy', '--disable-site-isolation-trials', '--remote-debugging-port=' + dbg, '--remote-allow-origins=*', 'http://127.0.0.1:' + port + '/blank'], { stdio: 'ignore' });
  let tabs;
  for (let i = 0; i < 50; i++) { try { tabs = await (await fetch(`http://127.0.0.1:${dbg}/json`)).json(); if (tabs.find(t => t.type === 'page')) break; } catch (e) { } await sleep(200); }
  const tab = tabs.find(t => t.type === 'page');
  const ws = new WebSocket(tab.webSocketDebuggerUrl);
  await new Promise(r => ws.onopen = r);
  let id = 0; const pend = new Map();
  ws.onmessage = m => { const o = JSON.parse(m.data); if (o.id && pend.has(o.id)) { pend.get(o.id)(o.result); pend.delete(o.id); } };
  const send = (method, params = {}) => new Promise(r => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  await sleep(2500);
  const snap = async () => {
    const ver = await (await fetch(`http://127.0.0.1:${dbg}/json/version`)).json();
    const bws = new WebSocket(ver.webSocketDebuggerUrl);
    await new Promise(r => bws.onopen = r);
    const info = await new Promise(r => { bws.onmessage = m => { const o = JSON.parse(m.data); if (o.id === 1) r(o.result); }; bws.send(JSON.stringify({ id: 1, method: 'SystemInfo.getProcessInfo' })); });
    bws.close();
    const out = {};
    for (const p of (info.processInfo || []).filter(p => p.type === 'renderer')) {
      const t = cp.execSync(`powershell -NoProfile -Command "$p=Get-Process -Id ${p.id}; '{0} {1}' -f $p.PrivateMemorySize64,$p.WorkingSet64"`).toString().trim().split(' ');
      out[p.id] = [+t[0] / 1048576, +t[1] / 1048576];
    }
    return out;
  };
  const base = await snap();
  if (name !== 'blank') { await send('Page.navigate', { url: 'http://127.0.0.1:' + port + '/' + name }); await sleep(3500); }
  await send('HeapProfiler.collectGarbage');
  await sleep(500);
  const metrics = Object.fromEntries(((await send('Performance.getMetrics')) || { metrics: [] }).metrics.map(m => [m.name, m.value]));
  await send('Performance.enable');
  const m2 = Object.fromEntries((await send('Performance.getMetrics')).metrics.map(m => [m.name, m.value]));
  const after = await snap();
  // 文書を読んだレンダラ（導入前後で Private が最も増えたプロセス。blank は最大のもの）
  let pid = null, best = -1e9;
  for (const k of Object.keys(after)) { const d = after[k][0] - (base[k] ? base[k][0] : 0); const score = name === 'blank' ? after[k][0] : d; if (score > best) { best = score; pid = k; } }
  const priv = after[pid][0] * 1048576, wsb = after[pid][1] * 1048576;
  const renderers = Object.keys(after);
  const r = { doc: name, rendererPrivateMB: +(priv / 1048576).toFixed(1), rendererWorkingSetMB: +(wsb / 1048576).toFixed(1),
    jsHeapUsedMB: +((m2.JSHeapUsedSize || 0) / 1048576).toFixed(2), nodes: m2.Nodes, layoutObjects: m2.LayoutObjects, renderers: renderers.length };
  ws.close(); child.kill(); srv.close();
  await sleep(500);
  return r;
}

(async () => {
  const out = [];
  for (const name of Object.keys(docs)) {
    for (let k = 0; k < 3; k++) { const r = await measure(name); r.run = k; out.push(r); console.log(JSON.stringify(r)); }
  }
  process.exit(0);
})();
setTimeout(() => { console.log('TIMEOUT'); process.exit(2); }, 420000);
