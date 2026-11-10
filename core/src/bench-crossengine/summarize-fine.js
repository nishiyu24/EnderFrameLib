// FineProfileMain の RESULT 行（fine.log）を、doc × シナリオ × バックエンド（null / cpu / gl）の表にする
// 使い方: node summarize-fine.js fine.log
const L = require("fs").readFileSync(process.argv[2] || "fine.log", "utf8").split("\n").filter(l => l.startsWith("RESULT"));
const T = {}, docs = new Set(), scs = [];
for (const l of L) {
  const g = k => (l.match(new RegExp(k + "=([^ ]+)")) || [])[1];
  T[`${g("doc")}|${g("scenario")}|${g("backend")}`] = `${(+g("mean")).toFixed(3)} p99 ${(+g("p99")).toFixed(2)} ${(+g("alloc")).toFixed(0)}KB`;
  docs.add(g("doc")); if (!scs.includes(g("scenario"))) scs.push(g("scenario"));
}
for (const d of docs) {
  console.log("##", d, "(mean ms / p99 ms / alloc per frame;  columns: null, cpu, gl)");
  for (const s of scs) console.log(s.padEnd(10), ["null", "cpu", "gl"].map(b => (T[`${d}|${s}|${b}`] || "-").padEnd(28)).join(""));
}
