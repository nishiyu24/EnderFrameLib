# 他エンジン横断ベンチマーク（ENGINE_REARCHITECTURE_PLAN.md §6.4）

同一 HTML（`showcase.html` と `large-list-N`）・ビューポート 800×600 で、`:core` と Chrome / Edge / Firefox / Swing / JavaFX を比べる。

| ファイル | 内容 |
| :--- | :--- |
| `bench.html` + `run.js` | ブラウザ内計測。`node run.js <chrome\|edge\|firefox> <showcase\|large-list\|large-list-N>` → `res-<browser>-<doc>.json`（ページ内 `performance.now()` + 強制 style/layout の中央値） |
| `trace.js` + `tr.js` | Chrome を CDP で駆動し、rAF ごとに 1 変更を起こして 1 フレームの CPU 時間（main / raster / draw）をトレース集計。`node trace.js <doc> <idle\|scroll\|hover\|text\|style\|inherited\|fontsize\|child> <soft\|gpu>` を `>> trace.log` に溜め、`node tr.js trace.log` で表にする |
| `SwingBench.java` | `java SwingBench.java`（JDK 21）。500 行の JLabel リストと JEditorPane |
| `FxBench.java` | OpenJFX 21 の jar（javafx-base/graphics/controls の win クラシファイア）を `--module-path` に置いて `java --module-path . --add-modules javafx.controls FxBench.java` |
| `summarize-fine.js` | `FineProfileMain` の `RESULT` 行を溜めたログ（`fine.log`）を表にする |
| `run-matrix.sh` + `summarize-matrix.js` | ワーカー数（`threads`）・同じ書体での比較（`font`）・実文書（`real`）・Chrome 側（`chrome`）をまとめて回し、反復の中央値を Markdown の表にする。Gradle を介さず、クラスパスの写しから直接 JVM を立てる（計測中に別のビルドが走っても影響を受けない） |
| `costgate-baseline.txt` | 回帰ゲート `./gradlew :core:costGate` の基準値（1 フレームの確保量と近道の経路の回数。時間は見ない） |

`:core` 側は `./gradlew :core:jmh -Pjmh.args="-f 1 -wi 3 -i 5 -prof gc"` と
`./gradlew :core:profile -Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain -Pprofile.args="<scenario> <doc> <frames> <null|cpu|gl>"`
（doc は `showcase` / `large-list` / `large-list-N`。シナリオに `fontsize` を追加）。

## 条件を揃える

- **書体**: `:core` の `FineProfileMain` は既定で内蔵の簡易書体（`FontResolver.FALLBACK`。文字幅の表だけ）で測る。Chrome は OS の書体で
  整形するので、そのままでは文字の測定のぶん仕事量が違う。エンジン間の比較は両方に同じ書体を強制して行う:
  `:core` は `-Pprofile.font=Arial`（`java` 直呼びなら `-Dprofile.font=Arial`）、Chrome は `BENCH_FONT=Arial node trace.js …`。
  どちらも文書の最初の `<style>` の先頭に `* { font-family: Arial !important; }` を足す（同じ文字列）。
- **ワーカー数**: `-Pthreads=N`（`-Denderframe.threads=N`）。既定は論理コア数 − 1。少ないコアのホストでの値を見るときに使う。
- **実文書**: 文書名に `voidwave.html` のようなファイル名を渡すと、`mod/src/main/resources/data/enderframelib/enderframe_ui/`
  （無ければ `core/src/test/resources/benchmark/`）から読む。`:core` と `trace.js` で同じ要素を触る（最初の 2 つの `button`、無ければ
  `class="btn"` の要素 2 つ。文字は `button` / `label` の外の最初の文字）。変化が起きない組み合わせ（スクロールできない文書の
  `scroll` など）は `drawn=0` になるので、比較から外す。

```bash
./gradlew :core:profileClasspath
REPS=3 core/src/bench-crossengine/run-matrix.sh build/bench threads font real chrome
node core/src/bench-crossengine/summarize-matrix.js build/bench
```
