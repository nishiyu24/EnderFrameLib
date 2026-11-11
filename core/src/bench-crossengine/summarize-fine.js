// FineProfileMain のログ（run-fine.sh が作る fine.log。1 行目が "[rep=N doc scenario backend] ..." 形式）を表にする。
// 複数回(rep)あれば「中央値 [最小–最大]」を出す。旧形式（"RESULT ..." のみ）の行も読める。
// 使い方: node summarize-fine.js fine.log [stages]
//   stages を付けると、各条件の段別内訳（中央値の run）と ACCOUNTED / PATCH 行も出す。
const fs = require("fs");
const lines = fs.readFileSync(process.argv[2] || "fine.log", "utf8").split("\n");
const showStages = process.argv[3] === "stages";
const runs = {}; // key -> [{mean,p99,alloc,extra:[lines]}]
const order = { docs: [], scs: [] };
let cur = null;
for (const raw of lines) {
  const m = raw.match(/^\[rep=(\d+) (\S+) (\S+) (\S+)\] (.*)$/);
  const body = m ? m[5] : raw;
  if (body.startsWith("RESULT")) {
    const g = k => (body.match(new RegExp(k + "=([^ ]+)")) || [])[1];
    const doc = m ? m[2] : g("doc"), sc = m ? m[3] : g("scenario"), be = m ? m[4] : g("backend");
    const key = `${doc}|${sc}|${be}`;
    cur = { mean: +g("mean"), p99: +g("p99"), alloc: +g("alloc"), extra: [] };
    (runs[key] = runs[key] || []).push(cur);
    if (!order.docs.includes(doc)) order.docs.push(doc);
    if (!order.scs.includes(sc)) order.scs.push(sc);
  } else if (cur && body.trim()) {
    cur.extra.push(body);
  }
}
const med = a => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
const fmt = (a, f = 3) => a.length === 1 ? a[0].toFixed(f) : `${med(a).toFixed(f)} [${Math.min(...a).toFixed(f)}–${Math.max(...a).toFixed(f)}]`;
for (const d of order.docs) {
  console.log(`## ${d}  (mean ms 中央値 [最小–最大] / alloc KB/frame 中央値;  columns: null, cpu, gl)`);
  for (const s of order.scs) {
    const cells = ["null", "cpu", "gl"].map(b => {
      const r = runs[`${d}|${s}|${b}`];
      return (r ? `${fmt(r.map(x => x.mean))} ${med(r.map(x => x.alloc)).toFixed(0)}KB` : "-").padEnd(34);
    });
    if (cells.every(c => c.trim() === "-")) continue;
    console.log(s.padEnd(10), cells.join(""));
  }
}
if (showStages) {
  console.log("\n## 段別内訳（各条件の中央値 run の stage / ACCOUNTED / PATCH 行）");
  for (const [key, r] of Object.entries(runs)) {
    const sorted = [...r].sort((a, b) => a.mean - b.mean);
    const mid = sorted[Math.floor(sorted.length / 2)];
    console.log(`\n### ${key}  mean=${mid.mean.toFixed(3)} ms`);
    for (const e of mid.extra) if (/^(stage|[a-z.()-]+ +\d+ +[\d.]+ |ACCOUNTED|PATCH|DAMAGE)/.test(e)) console.log("  " + e.replace(/\s+/g, " "));
  }
}
