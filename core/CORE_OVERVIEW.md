# `:core` 調査メモ

`core/` は Minecraft / NeoForge に依存しない、単体で動く HTML/CSS レンダリングコア。
このメモは 2026-11-07 時点のソース（`core/src/main` 約 69,000 行・Java、テスト 151 ファイル）を読んで整理したもの。
数値は `BENCHMARK.md`（開発機・JUnit 上の測定）からの引用で、ここでは再測定していない。

---

## 1. 位置づけ

| 項目 | 内容 |
|:---|:---|
| Gradle | `java-library`、Java 21、成果物名 `enderframelib-core` |
| 依存 | fastutil / JOML / Gson / JNA / slf4j、LWJGL 3.3.3（core・glfw・opengl・freetype）。ネイティブはテスト時のみ |
| 禁止 | `net.minecraft` / `net.neoforged` / `com.mojang` の参照（`ArchitectureTest` が検査） |
| 入口 | `common/RenderPipeline`（レイアウト〜ディスプレイリスト）、`common/StandaloneView(er)`（単体表示）、`RenderBackend`（描画） |

## 2. パイプライン全体

```
HTML/Markdown ─▶ HtmlParser / MarkdownParser ─▶ ライブ DOM (HtmlDocument)
                                                   │
CSS ─▶ CssParser / AtRuleProcessor ─▶ StyleSheet ──┤
                                                   ▼
        StyleResolver (カスケード) ─▶ ComputedStyle ─▶ LayoutBox (要素ツリー)
                                                   ▼
                              BoxTreeBuilder (匿名ボックス・display:contents)
                                                   ▼
        BoxMeasurer + Block/Flex/Grid/Table/Multicol LayoutEngine (measure → layout)
                                                   ▼
                      DisplayListBuilder ─▶ List<DisplayItem>（不変レコードの平坦な列）
                                                   ▼
                       PaintSceneDriver（補間・変形・グループ解決）
                          ├─ SoftwareRenderBackend ─▶ Pixmap (ARGB int[])
                          └─ LwjglGlRenderBackend  ─▶ FBO（SDF シェーダー 1 本 + インスタンス描画）
```

`RenderPipeline.layoutDocument` が中核。`buildLayoutTree`（再スタイル＋箱の生成/再利用）→ `BoxTreeBuilder.build` →
`markLayoutChanges` → `runLayoutPasses`（`LAYOUT_PASSES = 2`）を回し、`@container` が絡む文書は最大
`MAX_CONTAINER_PASSES = 4` まで収束させる。

## 3. パッケージ別

| パッケージ | 規模 | 役割 |
|:---|:---:|:---|
| `api/dom`, `api/event` | 23 files | Web IDL 形の純 Java インターフェース（`core/dom` が実装） |
| `api/config` | 3 | 設定（`EnderConfigManager`） |
| `common/` | 23 | 文書読み込み（`DocumentLoader`/`UiDocumentCompiler`/`CustomTagExpander`）、`RenderPipeline`、`UiView`(1.8k 行)・`UiInput` 等のビュー層、`StandaloneView(er)` |
| `core/css` (+`model`) | 35 | CSS Syntax 3 トークナイザ（`CssSyntax`）、セレクタ（`SelectorParser/Matcher`）、`InvalidationSets`、カスケード（`StyleResolver`）、`ComputedStyle`、色（`ColorParser` 1.3k 行）、`calc`/長さ（`LengthResolver`）、`@property`/`@counter-style`/`@layer`/`@container`、アニメ補間（`StyleAnimator`/`CompoundInterpolator`） |
| `core/dom` (+`node`,`parser`) | 21 | ライブ DOM（`HtmlNode` 1.2k 行、`HtmlElement`、`HtmlRange/Selection`）、`HtmlParser`（1.5k 行・adoption agency 対応）、`MarkdownParser`、`Bindings`（`{{key}}`/`data-*` の宣言的束縛）、`InteractionState`（:hover/:focus を弱参照で保持＋無効化セット連携）、フォーム検証、`MutationObserver` |
| `core/event` | 5 | イベントのディスパッチ（捕捉/バブル/既定動作） |
| `core/layout` (+`model`) | 16 | `LayoutBox`（1.6k 行、差分レイアウトの署名/キャッシュ）、`BoxMeasurer`、Block(1.5k)/Flex/Grid(1.1k)/Table/Multicol |
| `core/text` (+`font`,`model`) | 24 | `TextShaper`（折り返し・省略の唯一の実装、結果キャッシュあり）、`LineBreaker`(UAX #14)、`BidiText`、システム書体（`SystemFonts`）、`LoadedFace`(FreeType)、`KerningTable`、グリフアトラス（`AtlasCache`/`AtlasPacker`） |
| `core/paint` (+`model`) | 56 | `DisplayListBuilder`、`PaintOrder`（積層順）、`BoxDecorations`/`Gradients`/`ClipShape`/`FilterOp`/`BlendMode`/`Transforms`、`CssAnimator`、`HitTestIndex`、`LayerPool`、`VramBudget` |
| `core/paint/software` | 9 | `SoftwareRenderBackend`（1.6k 行）、`LayerFx`（フィルタ等）、`ImageProvider` |
| `core/paint/gpu` | 5 | API 非依存の `GpuBatchPacker`（1 インスタンス＝64 テクセル）、`AtlasPacker`、`GpuSink`、`BatchDevice` |
| `core/paint/gl` | 1 | `LwjglGlRenderBackend`（2.2k 行、GLSL 150） |
| `core/raster` (+`svg`,`awt`) | 23 | Canvas2D 相当（`CanvasRenderingContext2D`）、`Path2D`、`Stroker`、`Rasterizer`（4 サブスキャンライン）、`Pixmap`、`PngEncoder`、SVG |
| `core/diag` | 2 | `StageProfiler` / `PipelineStage`: 段別の所要時間・割り当ての計測器（既定は無効。[MEASUREMENT.md](MEASUREMENT.md)） |
| `core/net`, `core/media` | 8 | `SafeHttpClient`（SSRF 対策を持つ HTTP）、yt-dlp 連携、`VideoPlayer`（JNA ネイティブ） |
| `resources` | — | UA スタイル、テーマ 3 種、138 件の忠実度ケース（`preview/fidelity/cases`）と devkit |

## 4. 各段の設計ポイント

### 4.1 DOM / スタイル
- **単一ライブ DOM**。状態（:hover 等）とスクロール量はノード参照キーの弱参照表。
- **カスケード**は Cascade 5 の順（重要度→オリジン→レイヤー→詳細度→出現順）。規則は右端の複合セレクタの id/class/tag で振り分け。
- **無効化**: `InvalidationSets` で、状態・属性・子の増減から再スタイル対象の要素だけを導く。`pruneCleanSubtrees` で汚れていない部分木を飛ばす。
- **`ComputedStyle`** は不変。登録済みプロパティは `String[]`（`CssPropertyId` 添字）で保持し、長さ/色/数値は初回参照時に 1 回だけパースして不変配列を CAS で差し替えるキャッシュに載せる。
  ただし**宣言の値自体は依然として文字列**であり、`StyleResolver` / `StyleSet` / `RenderPipeline` の境界は `Map<String,String>` を渡している。
- 同じ親・同じ規則の兄弟でスタイルを共有する `styleShare`（レイアウト 1 回ごとにクリア）。

### 4.2 レイアウト
- 差分レイアウト: 箱を DOM 節ごとに使い回し、「制約・親が決めた寸法・ビューポート・書体」＋「算出スタイル・文字・属性・子の並び」の**署名**が同じなら `measure` を飛ばす。表と `display:none` 部分木は毎回作り直し。
- 再レイアウト境界（size containment / 確定サイズのスクロールコンテナ）、`opacity`/`transform`/色/影/角丸だけの変化は再レイアウトを要求しない。
- 動的操作の近道: スクロール＝位置決めのみ（`repositionLayout`）、`left/top` ドラッグ＝再スタイル後に layout のみ、リサイズ＝ビューポート単位を使う要素のみ再スタイル。
- 忠実度: 138 ケースをブラウザ実測値と比較（幾何 ±0.5px）。差異は `FIDELITY.md` の台帳＋`LIMITATION` コメント＋README の 3 点で管理。

### 4.3 テキスト
- 測定の出所は `TextShaper` 1 つ（描画側は測り直さない）。折り返しは 1/64px の許容（Chrome の LayoutUnit 相当）。
- 書体は既定でシステム書体→FreeType（`LoadedFace`）。`@font-face` の TTF/OTF も同経路。`minecraft:*` 等だけビットマップ書体。
- グリフ: `GlyphBitmap` を `AtlasPacker`（シェルフ詰め、倍々で拡張→上限で全リセット）に載せ、`AtlasCache` が LRU＋期限で VRAM から捨てる。`scale` アニメ時はサイズを量子化して置き場の乱立を抑制（667 種→約 90 種）。

### 4.4 描画
- **ディスプレイリスト**は `DisplayItem`（不変レコード、`String id`、`Map<String,String> events`、`RenderMeta/RenderHints` 付き）の平坦な列。`long key`（`ItemKeys`）で id を数値化。
- **`PaintSceneDriver`**: アニメ補間・親子変形/不透明度の積・グループ（opacity/filter/mask/blend/clip-path/backdrop）の層の鎖を解決し、`Painter` に「層を合わせる／項目を描く」の 2 呼び出しで渡す。動いていない項目の姿勢は前フレームから再利用（`reuseIdlePoses`）。
- **ソフトウェア**: `Pixmap`(ARGB int[]) に直接描画。dirty 矩形を追跡し、グループは層（`Layer`）に描いて合成。シェーダーと同じ式を `ShaderGradientEmulator`/`SoftShapes` が CPU で再現し、**CPU/GPU で画素を揃える**方針。
- **GPU（`LwjglGlRenderBackend`）**: 図形 1 つ＝データテクスチャ 1 行（`STRIDE=64` テクセル）。`MAX_BATCH=512` 件ずつ `glDrawArraysInstanced`。頂点シェーダーは `gl_InstanceID` で行を `texelFetch`、フラグメントは**角丸矩形 SDF＋ガウス影(erfc 近似)＋枠線＋グラデ＋字形/画像サンプル**を 1 本で処理。クリップ 4 段・姿勢・不透明度も GPU 側。回転文字・縮小画像・破線などは CPU 層をテクスチャとして描画順を保って重ねる。
- **層（FBO）**: `LayerPool` が大きさ（粗いバケット）ごとに再利用。`VramBudget`（総量 192MB、字形置き場 32〜64MB、FBO は画面サイズ 4〜16 枚分）。効果が無い文書は FBO を一切確保しない。
- **ヒットテスト**: レイアウト更新ごとに `HitTestIndex` を生成（対話要素のみ、Z 逆順、親変形鎖と clip を事前解決）。

### 4.5 周辺
- `core/net`: `SafeHttpClient`（836 行）がネットワーク入口。`ParseLimits` で HTML パーサの上限を設定可能。
- `core/raster`: ブラウザ非依存の Canvas2D 実装（Path2D→`Stroker`→`Rasterizer`→`Pixmap`）。SVG も自前（`SvgRenderer`）。
- `core/media`: JNA 経由のネイティブ動画デコード、`VideoStreamServer` で配信。

## 5. 性能の現状（`BENCHMARK.md` より）

| 文書 | Restyle | Relayout | DisplayList | ScrollStep |
|:---|:---:|:---:|:---:|:---:|
| showcase.html（163 要素） | 9.8 ms | 9.7 ms | 2.6 ms | 9.9 ms |
| guidebook.md | 4.5 ms | 2.3 ms | 1.9 ms | 3.2 ms |
| large-list（500 行） | 19.0 ms | 8.0 ms | 8.0 ms | 10.7 ms |

近道の効果: スクロール 14→3.4 ms、ドラッグ 14→3.7 ms、リサイズ 48→15 ms、500 行リストの 1 要素変更 17.8→3.2 ms、1 行ホバー 0.3〜0.5 ms。
サーバー帯域（Headless State）: 初回 4,548→101 bytes、単一更新 166→20 bytes。

## 6. 観察した構造上の特徴・課題

1. **スタイルが文字列主体**: 値は `String`。型付きキャッシュで緩和しているが、`Map<String,String>` が段の境界に残る。
2. **`DisplayItem` が重い**: 不変レコードで `String id`/`Map events`/`RenderMeta` を持つ。フレームごとに大量のオブジェクトが生まれ、GC とキャッシュ効率に響く。DisplayList 生成は差分化されていない（BENCHMARK 自身が未達と明記）。
3. **完全にシングルスレッド**: パイプライン内に並列化は無い（Executor 系は画像取得・動画・パーサの一部のみ）。GL は作成スレッド限定。
4. **GPU は「図形 SDF」中心**: 文字は CPU ラスタ（FreeType）→アトラス、フィルタ/一部の変形/破線は CPU 層を経由する。
5. **テスト基盤は充実**: 忠実度 138 ケース、差分 vs 全再構築のファジング、ゴールデン、`ArchitectureTest`。最適化を入れる際の安全網が既にある。
6. 作業ツリーに未コミットの変更（`RenderPipeline`、`InteractionState`、`IdleCostScalingTest`、`ScalingBenchmarkTest`）があり、アイドル時コストのスケーリング計測に着手している様子。
7. 既知の赤: `PerformanceBenchmarkTest` は全件実行でのみ不安定、M9 のファイル数ラチェットは HEAD で超過（直近コミットで削除済みの可能性あり）。

計測の手順と実測は [`MEASUREMENT.md`](MEASUREMENT.md)、改善案は [`PERFORMANCE_PROPOSALS.md`](PERFORMANCE_PROPOSALS.md) を参照。
計測基盤で取った段別の実測では、ソフトウェア描画の `paint(cpu)` と再レイアウトの `measure` が大きく、
静止時のフレームコストは 3 µs と既に小さい。上の 1〜4 の課題の優先度は PERFORMANCE_PROPOSALS §9 で見直した。
