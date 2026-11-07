# `:core` 計測ガイド

最適化の前に「どの段がいくらか」を同じ手順で測り直せるようにするための道具立て。
提案は [PERFORMANCE_PROPOSALS.md](PERFORMANCE_PROPOSALS.md)、構造は [CORE_OVERVIEW.md](CORE_OVERVIEW.md)、
回帰判定の基準値（JUnit）は `../BENCHMARK.md`。

## 1. 道具の使い分け

| 知りたいこと | 道具 | 実行 |
|:---|:---|:---|
| ある 1 段を、JIT が温まった状態で孤立させて測る／割り当て量を見る | **JMH**（`PipelineBench` / `PaintBench`） | `./gradlew :core:jmh` |
| 本番の経路（`StandaloneView.frame`）で、段ごとの内訳・外れ値・割り当てを見る | **段別プロファイル**（`PipelineProfileMain` + `StageProfiler`） | `./gradlew :core:profile` |
| 時間がどのメソッドに溶けているか | **JFR**（上の `profile` に付ける） | `-Pprofile.jfr=build/profile.jfr` |
| GPU 側の所要 | **タイマークエリ**（`LwjglGlRenderBackend.enableGpuTiming`） | コードから有効化 |
| 回帰の自動判定（基準値の ×2＋3ms） | JUnit（`PerformanceBenchmarkTest`） | `./gradlew benchmark` |

JUnit の数値は JIT のウォームアップが短く（5＋15 回）、JMH の定常値より最大で数倍大きく出る
（§4）。**絶対値の比較は同じ道具の中で**行い、回帰判定は従来どおり JUnit に任せる。

## 2. 段別プロファイル

`core/diag/StageProfiler`（計測器）と `PipelineStage`（段の一覧）。既定は無効で、無効のとき `begin` は volatile の読み 1 回で 0 を返し、
`end` はそれを見て戻る。有効にしても描画結果は変わらない（`PipelineProfilingTest` がピクセルとディスプレイリストの一致を確かめる）。

| 段 | 計測箇所 |
|:---|:---|
| `style+build` | `RenderPipeline.layoutDocument` の `buildLayoutTree`（再スタイル＋箱の生成/再利用） |
| `box-tree` | `BoxTreeBuilder.build`（匿名ボックスの整形） |
| `restyle-local` | `RenderPipeline.restyleLayoutTree`（ホバー・class トグルなどの局所再スタイル） |
| `layout.measure` / `layout.place` | `runLayoutPasses` の measure（2 パス分）と配置 |
| `reposition` | `repositionLayout`（measure を飛ばしたスクロール・ドラッグ） |
| `display-list` | `DisplayListBuilder.render` |
| `paint(cpu)` | `ProfilingRenderBackend` で包んだバックエンドの `render`（GPU 版では命令発行まで） |
| `gpu` | GL のタイマークエリ（数フレーム遅れて届く） |

```text
./gradlew :core:profile -Pprofile.args="<文書> [フレーム数] [--alloc] [--mode=idle|text|restyle|relayout]"
  文書: showcase | guidebook | large-list
  --alloc : 段ごとの割り当て量（KB/回）も記録（呼び出しスレッドの割り当てカウンタ。やや費用がある）
  --mode  : 毎フレーム起こす変化。idle=何もしない / text=文字ノード 1 つ / restyle=全体再スタイル要求 / relayout=全体再レイアウト要求
```

出力は mean / p50 / p95 / max（直近 1024 回の輪から）と合計。平均だけでは見えない GC・JIT の外れ値が p95・max に出る。
コードに仕込む場合:

```java
long t0 = StageProfiler.begin(PipelineStage.DISPLAY_LIST);
try { ... } finally { StageProfiler.end(PipelineStage.DISPLAY_LIST, t0); }
```
同じ段を同じスレッドで入れ子にしない。計測器を有効にするには `-Denderframe.profile=true`（割り当ては `-Denderframe.profile.alloc=true`）か `StageProfiler.enable(true)`。

### GPU の所要
```java
LwjglGlRenderBackend gl = new LwjglGlRenderBackend(w, h);
if (gl.enableGpuTiming()) { /* GL 3.3 以上か ARB_timer_query がある */ }
... view.frame(now, gl, false) ...
gl.lastGpuMillis();               // 直近に届いた値（なければ -1）
StageProfiler.report();           // gpu 行
```
読み戻しで CPU を止めない（4 本のクエリを輪にして、終わったものだけ拾う）。ホストが `GL_TIME_ELAPSED` を張っているコンテキストでは使わない。

### ゲーム内（Minecraft）で取る
root モジュールの描画経路にも同じ計測器を差し込んである。クライアントで:

```text
/enderframe_profile start [true]   # 集計を消して開始（true で割り当ても）。起動時から取るなら -Denderframe.profile=true
/enderframe_profile report         # 表をチャットとログへ
/enderframe_profile stop           # 止めて表を出す
```
計測中は約 30 秒ごとにログへ出る。ゲーム内で増える段:

| 段 | 内容 |
|:---|:---|
| `surface` | 面 1 枚の描画全体（`SurfaceRenderer.paint`。コア経路・従来経路のどちらも含む） |
| `surface.core-render` | コア GPU バックエンドへの命令発行（`useCoreBackend` 有効時） |
| `surface.core-blit` | コアの結果テクスチャを板として貼る処理 |
| `surface.legacy` | 従来の経路（`PaintRequest.render`）で描いた面 |
| `gpu` | コア経路の GL タイマークエリ（面ごとに有効化。数フレーム遅れて届く） |

スタイル/レイアウト/ディスプレイリストの段（`style+build` など）は `RenderPipeline` に入っているので、ゲーム内でもそのまま記録される。
`gpu` はコア経路（`useCoreBackend`）のときだけ。従来経路の GPU 時間は取れない（`surface.legacy` は CPU 側の発行まで）。
**実測はまだ**（この手順を整えた段階）。

## 3. JMH

```text
./gradlew :core:jmh                                                         # 全部（数分）
./gradlew :core:jmh -Pjmh.args="PipelineBench.restyle -p doc=showcase -f 1 -wi 2 -i 3 -prof gc"
./gradlew :core:jmh -Pjmh.args="-f 1 -wi 3 -w 1 -i 5 -r 1 -prof gc -rf json -rff build/jmh.json"
```
- `PipelineBench`: `fullLayout`（一から）/ `restyle` / `relayout` / `displayList` / `scrollStep`。段の切り方は `PerformanceBenchmarkTest` と同じ。
- `PaintBench`: `softwarePaint`（CPU ラスタ。GL 不要）。
- 文書は `Workloads`（showcase / guidebook / large-list(500 行)）。`-p doc=` で絞る。
- `-prof gc` で 1 操作あたりの割り当て（`gc.alloc.rate.norm`）が出る。ヒープ割り当ての削減（データ指向化）の効果はこれで見る。
- `check` は `compileJmhJava` に依存し、計測コードが腐らないようにしている（実行はしない）。

**注意**: `restyle` ベンチは毎回 `new InteractionState()` を渡すので「全部が汚れた状態からの再スタイル」（冷たい経路）。
本番のフレームで起きる局所の再スタイルは `restyle-local`（段別プロファイル）で見る。

## 4. 実測（2026-11-07 / 開発機 Windows 11・JDK 21・ソフトウェア描画・800×600）

> 条件: JMH は `-f 1 -wi 3 -i 5`（短縮）、誤差は ±99.9%。機械が負荷で揺れるので、**同じ手順での前後比較にだけ使う**。
> ゲーム内（Minecraft の描画経路）の値ではない。

### 4.1 JMH（ms/op、割り当て KB/op）

| 文書 | fullLayout | restyle（冷） | relayout | displayList | scrollStep | softwarePaint |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| showcase | 12.5 ±2.0（14,308 KB） | 4.3 ±0.8（5,592） | 8.2 ±1.5（8,172） | 0.58 ±0.16（1,214） | 8.6 ±3.1（9,417） | **43.3 ±11.7**（1,741） |
| guidebook | 3.1 ±0.5（4,276） | 1.5 ±0.4（2,057） | 1.3 ±0.3（1,903） | 0.49 ±0.12（1,009） | 1.8 ±1.2（2,937） | 1.9 ±0.2（935） |
| large-list | 5.2 ±1.0（7,585） | 2.4 ±0.4（2,587） | 3.0 ±0.2（4,479） | 2.1 ±0.3（5,207） | 5.2 ±1.7（9,280） | 4.3 ±1.0（9,661） |

`BENCHMARK.md`（JUnit）の同じ段は showcase の restyle 9.8・large-list の displayList 8.0 ms などで、JMH の定常値の 2〜4 倍になる段がある。
JUnit は短いウォームアップの JIT 前の値を含むため。

### 4.2 本番経路の段別プロファイル（ms/フレーム、平均）

| 状況 | style+build | box-tree | measure | place | display-list | paint(cpu) | フレーム全体 |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| showcase / idle | — | — | — | — | — | — | **0.003**（描かない） |
| showcase / 全再レイアウト要求 | 0.29 | 0.17 | 7.9 | 0.10 | 0.49 | **43.8** | 53.3 |
| large-list / 文字ノード 1 つ変更 | — | — | 0.06 | 0.07 | 0.23 | **3.5** | 4.2 |
| large-list / 全再レイアウト要求 | 0.16 | 0.16 | 0.03 | 0.02 | 0.48 | **3.3** | 4.5 |

割り当て（KB/回）: large-list の `paint(cpu)` は 1 フレーム約 **8,960 KB**（800×600 の `int[]` 1 枚は約 1,875 KB）、`display-list` は 58〜346 KB。

### 4.3 読み取れること（以降の優先度に反映）

1. **静止した UI のフレームコストは既にほぼ 0**（3 µs/フレーム）。`needsFrame()` の早期抜けが効いている。
2. **ソフトウェア描画では `paint(cpu)` が支配的**（showcase 43.8 ms、局所更新でも large-list で 3.3〜3.5 ms と全体の約 80%）。
   しかも large-list は 1 フレームで約 9 MB を割り当てており、**GC 圧の源が CPU ラスタ側にある**。
   ただし Minecraft 内の描画は別経路（root モジュールのレンダラ）なので、**ゲーム内でも同じとは限らない**（未計測）。
3. **再レイアウトでは `measure` が最大の段**（showcase 7.9 ms）。`style+build` と `display-list` は 1 ms 未満。
   `display-list` の時間より割り当て（large-list で 5.2 MB/op）のほうが目立つ。
4. 冷たい再スタイルの JMH 値（4.3 ms）は「全要素が汚れた状態」の値。実フレームの再スタイルは差分化されていて
   （汚れが無いとき `restyle-local` は 0.007 ms、`relayout` 要求でも `style+build` は 0.3 ms）、定常運用の支配項には見えない。
   冷たい経路が効くのは文書を開いた直後と、全体が汚れる変化（ビューポート変更・テーマ切替など。**未計測**）。

### 4.4 ゲーム内の実測（2026-11-07 / Minecraft 1.21.1・1600×900・showcase を全画面で開いた静止状態）

`/enderframe_profile` 相当（`-Denderframe.profile=true` の 30 秒ログ）。約 2,300 フレーム、ms/フレーム平均（p95）。

| 経路 | surface（CPU 側） | 内訳 | GPU |
|:---|:---:|:---|:---:|
| 従来（`useCoreBackend=false`） | **2.49**（2.64） | `surface.legacy` 2.49 | 計測不可 |
| コア（`useCoreBackend=true`） | **0.84**（0.99） | `core-render` 0.76 / `core-blit` 0.04 | **1.68**（1.69） |

文書を開いた最初の 1 回だけ `style+build` 30〜42 ms・`layout.measure` 61〜64 ms・`display-list` 28〜31 ms（冷たい経路。2 回の平均で、1 回目が 60〜100 ms 級）。
その後は再レイアウトが起きず、毎フレームは `surface` だけ。

読み取れること:
1. **静止した全画面 UI でも毎フレーム描き直している**（surface が 2,300 回）。コア経路は CPU 0.84 ms だが GPU が 1.68 ms で、**GPU 側が CPU の約 2 倍**。
   スタンドアロンの CPU ラスタ基準（§4.2）とは支配項が違い、ゲーム内では GPU・描画命令側が重い。
2. コア経路は従来経路より CPU 側が約 3 倍速い（2.49 → 0.84 ms）。ただし GPU 時間を足すと差は縮む（従来経路の GPU 時間は取れていない）。
3. 静止していれば前フレームのテクスチャを貼るだけで済むはずで、**ダメージ追跡（§3.5）で毎フレームの再描画をやめる**余地が大きい。
4. 文書を開く瞬間の 100 ms 級の山は冷たい経路（AOT・型付きスタイルの対象）。

制約: 静止状態のみ（スクロール・ホバー・アニメ中は未計測）。1 回の起動ずつ。GPU は共有コンテキストのクエリで、描画完了待ち（`waitForRender`）は含まない。

## 5. 未実施（今後）

- **ゲーム内のフレーム時間**: 静止状態は §4.4 で実測済み。スクロール・ホバー・アニメ中、HUD・ブロック面での値は未計測。
- **GPU 版の段別プロファイル**: `enableGpuTiming` を使った実測（`PipelineProfileMain` に `--gpu` を足す。GL が要る）。
- **ブラウザ比較**: 同じ文書を Chrome ヘッドレスで測る手順の整備（JS・セキュリティを含むので参考値）。
- **基準値の更新**: JMH の結果を CI に載せるかは、機械の揺れを見てから決める（現状は手動）。

## 6. 検証したこと（この道具自体の正しさ）

| テスト | 内容 |
|:---|:---|
| `StageProfilerTest`（9） | 無効時に何も記録しない／途中で有効にしても壊れた値を入れない／中央値・95%・最大・平均／輪の入れ替わり／割り当て計測／並行記録 |
| `PipelineProfilingTest`（4） | 有効でもピクセル・ディスプレイリストが同一／全段が実際に記録される／`ProfilingRenderBackend` が全て委譲 |
| `GpuTimingTest`（2） | 既定で GL を呼ばない／有効にしても絵が同一で、結果が届き `gpu` 段に入る（GL 無し環境はスキップ） |
| `ArchitectureTest` / `PortabilityRatchetTest` | `core` から上位への依存なし、GL 依存は `LwjglGlRenderBackend` に閉じたまま |
