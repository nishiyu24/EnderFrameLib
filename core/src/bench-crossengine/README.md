# 他エンジン横断ベンチマーク（ENGINE_REARCHITECTURE_PLAN.md §6.4）

同一 HTML（`showcase.html` と `large-list-N`）・ビューポート 800×600 で、`:core` と Chrome / Edge / Firefox / Swing / JavaFX を比べる。

| ファイル | 内容 |
| :--- | :--- |
| `bench.html` + `run.js` | ブラウザ内計測。`node run.js <chrome\|edge\|firefox> <showcase\|large-list\|large-list-N>` → `res-<browser>-<doc>.json`（ページ内 `performance.now()` + 強制 style/layout の中央値） |
| `trace.js` + `tr.js` | Chrome を CDP で駆動し、rAF ごとに 1 変更を起こして 1 フレームの CPU 時間（main / raster / draw）をトレース集計。`node trace.js <doc> <idle\|scroll\|hover\|text\|style\|inherited\|fontsize\|child> <soft\|gpu>` を `>> trace.log` に溜め、`node tr.js trace.log` で表にする |
| `SwingBench.java` | `java SwingBench.java`（JDK 21）。500 行の JLabel リストと JEditorPane |
| `FxBench.java` | OpenJFX 21 の jar（javafx-base/graphics/controls の win クラシファイア）を `--module-path` に置いて `java --module-path . --add-modules javafx.controls FxBench.java` |
| `summarize-fine.js` | `FineProfileMain` の `RESULT` 行を溜めたログ（`fine.log`）を表にする |

`:core` 側は `./gradlew :core:jmh -Pjmh.args="-f 1 -wi 3 -i 5 -prof gc"` と
`./gradlew :core:profile -Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain -Pprofile.args="<scenario> <doc> <frames> <null|cpu|gl>"`
（doc は `showcase` / `large-list` / `large-list-N`。シナリオに `fontsize` を追加）。
