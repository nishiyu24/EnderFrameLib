# EnderFrameLib `:core` — エンジン設計・計測の経緯（`ENGINE_REARCHITECTURE_PLAN.md` の補足資料）

> **位置付け**: 本編 [`ENGINE_REARCHITECTURE_PLAN.md`](ENGINE_REARCHITECTURE_PLAN.md)（以下「本編」）の**補足資料**。本編は設計原則・現在地の KPI・優先順位・完了記録だけを持ち、**なぜそうしたか（実測の経緯・棄却した仮説の全文・実装記録・モジュール構成マップ）はこのファイルに置く**。
> **更新方針**: 2026-11-13 に本編を整理した際の退避を起点とする履歴資料。**現行の優先順位・KPI・設計原則は本編だけを正とし、本書の数値は当時の値**（読み取り専用。本編の §6 から `§N.N` で参照される節番号は変えない）。
> **3D** の詳細設計は本編 §3.1、描画 API 中立化（G）と Chrome 超え（B）は本編 §3.2、配布は本編 §1.5。

## 本編との対応（どこを読むか）

| 知りたいこと | 本書の節 | 本編の対応箇所 |
| :-- | :-- | :-- |
| 設計原則・144Hz 予算・7 層構造 | §1（本編 §1 へ統合済み。本書は欠番の見出しだけ） | §1.1〜§1.3 |
| 旧スコアボード（JMH・実フレーム・ラチェット達成状況） | §2 | §2（現行 KPI） |
| モジュール構成マップ（クラス単位。2026-11-10 時点） | §3 | — （本編には置かない） |
| サブシステム別の実装済み設計と知見（Kernel・CSS・レイアウト・ペイント・テキスト・API） | §4 | §4（完了記録は 1 行ずつ） |
| 二重構造（Type A/B/C）の解消記録 | §5 | §1.2 原則 4（ラチェット） |
| 実測プロファイル・Chrome 比較・他エンジン横断ベンチ・棄却した仮説 | §6 | §5（手順）・§6（知見の要約） |
| 当時の総合評価・残存課題・ロードマップ | §7 | §3（現行の優先順位） |
| DoD 再設計・内容アドレス化・反復ブロックのインスタンス化（Wave 1.7、R0〜R7 の実装記録） | §8 | §4（R 系の完了行） |
| 2026-11-12 の再計測・JFR 特定・「なぜ遅いのか」の再考察（C1〜C4・Q1〜Q7） | §9・§10 | §3・§4（B 系・W 系） |

---

## 1. 基本思想と絶対設計原則

→ 本編 §1（パフォーマンス予算・4 つの絶対設計原則・OSI 7 層と階層純度ラチェット）へ統合済み。旧版の重複を避けるため、本書には再掲しない。

---

## 2. マスタースコアボード & 機械的ラチェット達成状況

### 2.1 JMH ベンチマーク推移（[`PipelineBench`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/jmh/java/com/nishiyu/enderframelib/bench/PipelineBench.java) — 300 行ダッシュボード）

> **注**: 本表（および §1.1・§6.1）は 2026-11-10 午前までの旧計測文書「300 行ダッシュボード」の値で、`restyle` 行は JMH の `hoverToggle`（`:hover` 1 要素）を指す。現行の標準文書（`showcase` / `guidebook` / `large-list`）での JMH 全件再計測と、Chrome・Firefox・Swing・JavaFX との比較は **§6.4**、判明した劣後点は **§6.5** を参照。`fullLayoutWarm` `0.986 ms`（旧）は showcase 再計測 `0.989 ms` とほぼ一致しており、退行はない。
>
> **2026-11-11 再計測**: 差分描画・Segmented DisplayList 実装後の `:core` 全件再計測は **§6.7**（GL 実フレーム large-list `text` −80%・`hover` −72%、2,000 行 `hover` `13.05→3.50` ms 等）。

| ベンチマーク項目 | 初期ベースライン | M1〜M5 完了時 (11-09) | **最新計測 (2026-11-10)** | 初期比改善 | 主な寄与 |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **`restyle`** (`:hover` 1 要素切替) | `3.329 ms` / `2.70 MB` | `0.008 ms` / `32 B` | **`0.008 ms` / `32 B`** | **416x 高速化 / 99.999% 削減** | [`StyleStateAutomaton`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/StyleStateAutomaton.java) $O(1)$ 遷移 |
| **`restyleInherited`** (ルート `--accent` 変更) | — | (`2.299 ms`) | **`1.201 ms`** | **−48%** | `CssValue.dependsOnViewport` + 共有 `--*` マップ |
| **`scroll`** (1 step スクロール + HitTest) | `0.421 ms` / `616 KB` | `0.002 ms` / `0 B` | **`0.002 ms` / `0 B`** | **210x 高速化 / 完全 0 B** | [`OffsetItemList`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/OffsetItemList.java) + [`HitTestIndex.patch`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/HitTestIndex.java) |
| **`fullLayoutWarm`** (既存 DOM 暖機後レイアウト) | — | (`1.448 ms`) | **`0.986 ms` / `700 KB`** | **−32%（`< 1.0 ms` 達成）** | `blockPercentFree` 鍵正規化（測定 `1,785 → 860 回`） |
| **`layoutWidthChange`** (1280↔1024px 幅往復) | — | (`1.564 ms`) | **`1.277 ms`** | **−18%** | Flex 同幅再測定スキップ + 同パス測定再利用 |
| **`relayout`** (毎回 `BoxTreeBuilder` から再構築) | `2.761 ms` / `2.38 MB` | `2.411 ms` / `2.16 MB` | **`1.838 ms`** | **−33%** | `CssPropertyIndex` + `CssValue[]` + 測定削減 |
| **`parseHtml`** (300 行 HTML パース + Arena 構築) | `0.629 ms` / `1.00 MB` | `0.680 ms` / `1.04 MB` | **`0.558 ms`** | **−11%** | [`DocumentArena`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/DocumentArena.java) + `HostSyntax` |
| **`displayList`** (フル DisplayList 構築) | `0.818 ms` / `1.29 MB` | `0.713 ms` / `1.23 MB` | **`0.713 ms` / `1.23 MB`** | **−13%** | [`PaintOrder.Chunk`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintOrder.java) |
| **`softwarePaint`** (1280×800 タイル並列 CPU 描画) | `5.887 ms` / `5.57 MB` | `1.106 ms` / `9.8 KB` | **`1.106 ms` / `9.8 KB`** | **5.3x 高速化 / 99.8% 削減** | [`TileQueue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/TileQueue.java) + [`OpPool`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/OpPool.java) |

### 2.2 実フレームシナリオ計測（[`FineProfileMain`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/jmh/java/com/nishiyu/enderframelib/bench/FineProfileMain.java) — `UiView.renderFrame` 全段）

2 種類の実ページ（`showcase`: 366 要素・730 DisplayItem、`large-list`: 1,008 要素・5,012 DisplayItem・約 19,000 字形）における 1 フレーム全段（スタイル・レイアウト・DisplayList・GL バックエンド送出）の平均時間とヒープ確保量：

> **2026-11-11 再計測値**（§6.7 と同条件。括弧内は 2026-11-10 の値）。`gl` 列は GL バックエンド込みの 1 フレーム平均、確保量は 500 行 `gl`。

| シナリオ | `showcase` (`null`) | `showcase` (`gl`) | `large-list` 500 行 (`null`) | `large-list` 500 行 (`gl`) | 500 行 `gl` 確保量 |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`idle`** | `0.0005 ms` | `0.0005 ms` | — | — | `0 KB` |
| **`scroll`** | `0.011 ms` | `0.008 ms` | `0.085 ms` | `0.157 ms`（0.201） | `8 KB` |
| **`hover`** | `0.198 ms` | `0.719 ms` | `0.499 ms` | **`0.721 ms`**（2.619・**−72%**） | `400 KB`（1,342） |
| **`text`** | `0.255 ms` | `0.781 ms` | `0.182 ms` | **`0.431 ms`**（2.180・**−80%**） | `171 KB`（1,110） |
| **`style`** | `0.252 ms` | `0.778 ms` | `0.428 ms` | **`0.653 ms`**（2.570・**−75%**） | `402 KB`（1,350） |
| **`child`** | `0.596 ms` | `1.359 ms` | `0.872 ms` | **`2.184 ms`**（4.956・−56%） | `3,208 KB`（6,598） |
| **`inherited`** | `0.587 ms` | `1.470 ms` | `2.663 ms` | **`3.589 ms`**（7.525・−52%） | `5,417 KB`（13,366） |
| **`fontsize`** | `1.772 ms` | `2.831 ms` | `3.756 ms` | `4.243 ms`（7.636・−44%） | `6,488 KB` |
| **`open`** | `5.507 ms` | `5.106 ms` | `5.838 ms` | `6.171 ms`（9.220） | `9,898 KB` |

CPU バックエンド（500 行）: `scroll 1.392`（3.276）・`hover 2.282`（3.401）・`text 1.289`（3.098）・`style 1.661`（3.624）・`child 5.297`（7.262）・`inherited 7.995`（10.231）ms。showcase の CPU は `hover 4.59`・`inherited 8.25` ms で不変（**showcase より large-list の方が速い逆転が起きており、ダメージ領域が全面化している原因の調査が R4**）。

### 2.3 マイルストーン & 構造ラチェット達成状況

| マイルストーン / ラチェット | 検証テスト | 状態 | 達成内容 |
| :--- | :--- | :---: | :--- |
| **M1: Off-Heap DOM Arena & Flat Bloom** | [`DocumentArenaTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/memory/DocumentArenaTest.java), [`ArenaSlotsLeakTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/memory/ArenaSlotsLeakTest.java) | ✅ **完全達成** | 24B/node 固定列、64-bit Bloom Filter、`InteractionState` 強参照リーク根絶 |
| **M2: Zero-String Style & Reactive Patch** | [`PropertyIdMigrationGuardTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/css/PropertyIdMigrationGuardTest.java), [`CssValueTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/css/CssValueTest.java), [`IdBasedComputeTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/css/IdBasedComputeTest.java) | ✅ **完全達成** | `String[] values` 撤去 → `CssValue[]`、全箇所 `CssPropertyIndex` 添字アクセス、`StyleTable` + `CompiledDeclarations`、`StyleFacts` 25 寸法 `long[]` 化 |
| **M3: Incremental Layout & Shaping** | [`LayoutKeyEquivalenceTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/layout/LayoutKeyEquivalenceTest.java), [`FlexSameWidthRemeasureTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/layout/FlexSameWidthRemeasureTest.java) | ✅ **完全達成** | `TEXT_PATCH` / `CHILD_PATCH` / `LOCAL_SUBTREE`、`ParagraphSegments`、`blockPercentFree` 測定半減 |
| **M4: Retained GPU Property Trees & Uber-Shader** | [`GpuRectShaderTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/gpu/GpuRectShaderTest.java), [`PaintSceneDriverDiffPatchTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriverDiffPatchTest.java) | ✅ **完全達成** | 単一インスタンスシェーダー、Dual Kawase ブラー、`patchIndexByDiff` + `Clip.gpuClips` メモ化 |
| **M5: Zero-Allocation Tile CPU Fallback** | [`AllocationRatchetTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/AllocationRatchetTest.java), [`DiffPatchPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/DiffPatchPixelTest.java) | ✅ **完全達成** | 64×64 タイル並列 `TileQueue`、`OpPool` ゼロ確保、`Fast.div255` |
| **Wave 1: 公開 API・ホスト分離・並行書体・脱 static** | [`ApiBoundaryTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/api/ApiBoundaryTest.java), [`HostElementProviderTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/layout/HostElementProviderTest.java), [`EngineContextTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/EngineContextTest.java), [`LoadedFaceConcurrencyTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/text/font/LoadedFaceConcurrencyTest.java) | ✅ **完全達成** | `EnderEngine` / `WebViewSurface` 公開 API、`core/src/main` から `mc-*` 完全撤去、`EngineContext` スレッドスコープ化、`LoadedFace` 面単位ロック + 専用 `FT_Library` |
| **Portability Ratchet (LWJGL/AWT 隔離)** | [`PortabilityRatchetTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/PortabilityRatchetTest.java) | ✅ **完全達成** | プラットフォーム依存を `gl` 5 + `LoadedFace` + `AwtImageDecoder` の計 7 ファイルのみに固定 |
| **Layer Ratchet (7 層単方向 DAG)** | [`LayerDependencyRatchetTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/LayerDependencyRatchetTest.java) | ✅ **完全達成（逆方向参照 0）** | import＋完全修飾名を走査し下位→上位参照を検出、`KNOWN_UPWARD` は空集合。継ぎ目の挙動は [`LayerInversionSeamsTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/LayerInversionSeamsTest.java) が固定。`core` から `common`/`client`/`server` への外向き参照 0 |

---

## 3. 最新モジュール構成マップ（`:core` 全 335 クラス — 2026-11-10 時点）

```text
com.nishiyu.enderframelib
├── api/                          [公開 API 層 — core/common の内部型を一切漏らさない (ApiBoundaryTest)]
│   ├── EnderEngine.java          統合ビルダー (HTML / サイズ / DPR / フォント / 画像 / Target 指定 / build / openWindow)
│   ├── WebViewSurface.java       統一サーフェス操作 (frame / resize / マウス・キー・文字入力 / state / タイマー / pixels / dom)
│   ├── WebDocument.java          ドキュメント参照 & dom() ブリッジ
│   ├── SurfaceState.java         リアクティブ状態操作 (set / get / setList / watch / bind / forceSync)
│   ├── SurfaceLifecycleListener.java, PixelSink.java, EnginePlatform.java (ServiceLoader SPI)
│   ├── SurfaceFont.java, SurfaceImage.java, ImageSource.java, SurfaceClipboard.java, MouseButton.java, KeyMods.java
│   ├── dom/                      標準 DOM インターフェース (Document, Element, Node, CSSStyleDeclaration, Range, Selection, Observers...)
│   ├── event/                    標準イベント API (Event, EventListener, EventTarget, UIEvents, AbortSignal...)
│   └── config/                   設定 API (EnderConfig, ConfigEntry, EnderConfigManager)
│
├── common/                       [ビューライフサイクル・パイプライン統合層 (26 クラス)]
│   ├── EngineSurface.java        WebViewSurface の標準実装 & EnginePlatform ServiceLoader プロバイダ
│   ├── UiView.java               インタラクティブ画面ライフサイクル・リアクティブ同期・タイマー・スクロール管理
│   ├── StandaloneView.java       ホスト非依存ビュー (マウス 3 ボタン・修飾キー・クリップボード・リソース結線)
│   ├── StandaloneViewer.java     GLFW デスクトップウィンドウランナー
│   ├── RenderPipeline.java       スタイル解決 → レイアウト → DisplayList 構築の統合ファサード
│   ├── UiDocumentCompiler.java   HTML/CSS 解析・カスタムタグ展開・テンプレートコンパイル
│   ├── CustomTagExpander.java    HostElementProvider.tagMacros() に基づく汎用マクロ展開器
│   ├── UiStateStore.java, UiPubSub.java, UiInput.java, UiFocus.java, UiTimers.java, TextEditing.java, SelectPopup.java
│   └── Document.java, DocumentLoader.java, CssResources.java, CanvasSurface.java, BoxLayoutQuery.java, SoftwareRendering.java
│
└── core/                         [レンダリングエンジン中核]
    ├── EngineContext.java        マルチインスタンス・マルチスレッド対応のエンジン設定・リゾルバ・フラグ文脈
    ├── kernel/                   [DoD オフヒープ・コンパイラ・GPU シェーダー基盤]
    │   ├── memory/               OffHeapColumns, DocumentArena (24B/node + FFM), AtomTable
    │   ├── style/                PackedCssValue (64-bit 即値エンコード)
    │   ├── compiler/             StyleStateAutomaton (:hover/:focus O(1) 遷移), StateStyleMemo, BindingPatchTable
    │   └── gpu/                  PropertyTrees, PersistentVramBuffer, GpuRectShader (Uber-Shader), DualKawase, LayerAtlas, GpuAnimation
    ├── dom/                      [DOM 実装・HTML5/Markdown パーサ・ホスト構文 SPI]
    │   ├── node/                 HtmlNode, HtmlElement, HtmlDocument, HtmlText, InlineStyleOverlay, ComputedStyleDeclaration, TagMacro...
    │   ├── parser/               HtmlParser (WHATWG Tree Construction), MarkdownParser, DomUtils, ParseLimits
    │   └── HostSyntax.java, Bindings.java, InteractionState.java, FormController.java, ConstraintValidator.java, MutationObserverManager.java
    ├── css/                      [CSS パーサ・添字カスケード・正規化算出値]
    │   ├── CssPropertyIndex.java 全登録プロパティのコンパイル時整数添字定数
    │   ├── CssValue.java         弱参照正規化された不変算出値 (長さ・色・数値・キーワード・line-height・font-weight・単位フラグを内蔵)
    │   ├── StyleTable.java       固定長 CssValue[] 配列ベースのカスケード作業表
    │   ├── CompiledDeclarations.java, ShorthandPlan.java, PackedLengths.java, KeywordMatch.java
    │   ├── ComputedStyle.java, StyleFacts.java, StyleResolver.java, ValueComputer.java, SelectorMatcher.java, InvalidationSets.java
    │   ├── StyleSheet.java (解析済み雛形キャッシュ内蔵), CssParser.java, CssSyntax.java, AtRuleProcessor.java, ColorParser.java, LengthResolver.java
    │   ├── StyleAnimator.java, ValueInterpolator.java, CompoundInterpolator.java, CounterStyles.java, TypedValueCache.java
    │   └── model/                ElementState, StyleSet, ContainerBox, Keyframes, BorderStyle, Overflow, TextAlign...
    ├── layout/                   [5 大レイアウトエンジン & 置換要素 SPI]
    │   ├── LayoutEngine.java, BoxTreeBuilder.java, BoxMeasurer.java, LayoutBox.java, LayoutContext.java
    │   ├── BlockLayoutEngine.java, FlexLayoutEngine.java, GridLayoutEngine.java, TableLayoutEngine.java, MulticolLayout.java
    │   ├── HostElementProvider.java, ReplacedElements.java, CustomElementPainter.java (ホスト置換要素・空要素・マクロ SPI)
    │   └── model/                ConstraintSpace, FormattingContext, LayoutMetrics, ScrollState, Viewport, IntrinsicSizeResolver
    ├── text/                     [テキスト整形・UAX#14 改行・FreeType / OpenType フォント]
    │   ├── TextShaper.java, ParagraphSegments.java, LineBreaker.java, BidiText.java
    │   ├── font/                 LoadedFace (面単位ロック + 専用 FT_Library), OutlineFontFace (positions 記憶), GsubTable, KerningTable, AtlasCache, SystemFonts, WebFonts...
    │   └── model/                FontFace, BitmapFontFace, ShapedLine, TextStyle, FontStyle, TextSelection, WhiteSpace, WordBreak
    ├── paint/                    [DisplayList・差分シーンドライバ・GL / ソフトウェア描画バックエンド]
    │   ├── DisplayListBuilder.java, PaintOrder.java (Chunk メモ), PaintSceneDriver.java (patchIndexByDiff), HitTestIndex.java (patch)
    │   ├── BoxDecorationPainter.java, ControlPainter.java, CssAnimator.java, RetainPlan.java, StaticFrameGate.java, OffsetItemList.java...
    │   ├── gl/                   LwjglGlRenderBackend, GlRectStream, GlRectRenderer, GlTreeStore, GlVramDevice, GlFenceRing, CpuLayerFallback
    │   ├── gpu/                  GpuSink, GpuClip, GpuScrollLayer, AtlasPacker, ImageTextureCache
    │   ├── software/             SoftwareRenderBackend, TileQueue (64x64 並列), CpuRaster, GpuAttempt, Clip (gpuClips 記憶), Op, OpPool, OverrideImages...
    │   └── model/                DisplayItem, ElementType, ClipRect, Corners, LayerSpec, RoundedBorderSpec, ShaderGradient...
    ├── raster/                   [2D ベクタラスタライザ・HTML5 Canvas・SVG レンダラ]
    │   ├── Pixmap.java, Rasterizer.java, Stroker.java, Path2D.java, Paint.java, Matrix2D.java, CanvasRenderingContext2D.java, PngEncoder.java...
    │   ├── svg/                  SvgRenderer.java, SvgImage.java, SvgSize.java, SvgSources.java
    │   └── awt/                  AwtImageDecoder.java
    ├── media/ & net/             VideoPlayer, NativeVideoApi, SafeHttpClient, HttpPolicy, VideoStreamServer, YtDlpResolver...
    └── diag/                     StageProfiler.java, PipelineStage.java, PipelineStats.java (ThreadLocal 診断統計)
```

---

## 4. サブシステム別・実装済アーキテクチャと設計知見の総括（Milestone 1〜5 ＋ Wave 1）

### 4.1 Kernel & Memory / DOM 層（ホスト完全分離とリーク根絶）

1. **オフヒープ DOM トポロジー（[`DocumentArena`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/DocumentArena.java) + [`OffHeapColumns`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/OffHeapColumns.java)）**:
   - 各 DOM ノードの `parent`, `firstChild`, `nextSibling`, `tagAtom`, `idAtom`, `flags`（24 Bytes/node）と祖先セレクタ照合用の 64-bit Bloom Filter（`ancestorBloom`）を `MemorySegment` 上に連続格納。
   - **2026-11-10 リーク修正**: `DocumentArena` が GC された後も静的テーブル `SLOTS` に `InteractionState`（＝旧 `HtmlDocument` 全体）が強参照で残っていたバグを修正し、[`ArenaSlotsLeakTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/memory/ArenaSlotsLeakTest.java) で 400 回文書を作り直しても古い文書が 100% GC 回収されることを保証。
2. **ホスト固有要素・構文の SPI 分離（`core/src/main` からの `mc-*` 完全撤去）**:
   - [`HostElementProvider`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/HostElementProvider.java)（`ServiceLoader` SPI）を新設し、置換要素台帳 [`ReplacedElements`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/ReplacedElements.java)・カスタム描画 [`CustomElementPainter`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/CustomElementPainter.java)・カスタムタグマクロ [`TagMacro`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/node/TagMacro.java)（[`CustomTagExpander`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/CustomTagExpander.java)）・属性別名と Markdown タグ台帳 [`HostSyntax`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/HostSyntax.java) をすべてプラグイン化。
   - Minecraft 固有の 7 タグ（`mc-fluid`, `mc-entity`, `mc-scene`, `mc-slot`, `mc-slot-grid`, `mc-inventory`, `mc-bar` 等）と別名（`mc-if`, `mc-for`, `mc-markdown`）はすべてルート Mod 側の [`McHostElements`](file:///D:/Dev/Personal/enderframelib-1.21.1/src/main/java/com/nishiyu/enderframelib/content/McHostElements.java) / `McTagMacros` に移設され、`:core` 本番コードから `mc-*` の直書きが完全にゼロとなった（[`HostElementProviderTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/layout/HostElementProviderTest.java), [`CustomTagExpanderSpiTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/common/CustomTagExpanderSpiTest.java), [`HostSyntaxTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/dom/HostSyntaxTest.java) で守護）。

### 4.2 CSS 解決・カスケード・アニメーション層（Milestone 2 完全達成）

2026-11-10 の集中実装により、計画書が掲げていた Milestone 2 の全項目が完遂された。

1. **コンパイル時整数添字 [`CssPropertyIndex`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/CssPropertyIndex.java) への全面移行**:
   - 全登録プロパティ（約 190 個）に `static final int` 添字を割り当て、`:core` 内の約 330 箇所のスタイル読み取りをすべて `style.get(CssPropertyIndex.X)` に統一。
   - `Map.get(Object)` に `int` を誤って渡して `null` になる罠を防ぐため、[`PropertyIdMigrationGuardTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/css/PropertyIdMigrationGuardTest.java)（`com.sun.source` AST 型検査）と [`IdInitOrderTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/css/IdInitOrderTest.java)（10 通りのクラス初期化順序を別 JVM で検証）で常時守護。
2. **`String[] values` の撤去と正規化不変値 [`CssValue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/CssValue.java) 配列化**:
   - **設計判断**: 単純にすべてを `long[]` に潰すと、`getComputedStyle` や既存の読み手が文字列を必要とするたびに逆変換の文字列確保が発生し、`10.0px` → `10px` のような綴り差も生じる。そこで**弱参照で正規化された不変オブジェクト `CssValue`**（生きている間は同じ文字列なら必ず同一インスタンス `==`、不要になれば GC 回収）を導入。
   - `CssValue` は原文文字列に加え、初回アクセス時に 1 回だけ解析した **長さ (`CompiledLength`)・色・数値・全域キーワード・列挙キーワード・`line-height` 種別と数値・`font-weight` 数値・`mayContainDimension`・`usesViewportUnits`・`dependsOnViewport`・`containsVar`・`containsCurrentColor`** をフィールドに保持する（[`CssValueTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/css/CssValueTest.java)）。
3. **固定長作業表 [`StyleTable`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/StyleTable.java)・事前コンパイル宣言 [`CompiledDeclarations`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/CompiledDeclarations.java)・添字展開 [`ShorthandPlan`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/ShorthandPlan.java)**:
   - カスケード時の `LinkedHashMap` 生成を撤廃し、`CssValue[]` を直接持つ `StyleTable` 上で添字アクセスによりカスケード・`ValueComputer.compute`・ショートハンド展開・`var()` 置換を実行（[`IdBasedComputeTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/css/IdBasedComputeTest.java) が乱数 14,000 組で旧実装との完全一致を検証）。
   - 継承可能なカスタムプロパティ `--*` は、要素自身が `--*` を宣言していない限り親の不変マップ参照をそのまま共有。
4. **`StyleFacts` の 25 寸法 `long[]` パック化と無確保キーワード照合**:
   - 縁 14 個（margin/padding/border）に加え、`width`, `height`, `min-*`, `max-*`, `top`, `right`, `bottom`, `left`, `flex-basis` の 11 個（計 25 個）を [`ComputedStyle.packedEdges`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/ComputedStyle.java) (`long[]`) に統合。`noPercent`, `widthAuto`, `definiteWidth` 等のフラグ導出から文字列検索を撤去（[`StyleFactsPercentTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/css/StyleFactsPercentTest.java), [`StyleFactsPackedFlagsTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/css/StyleFactsPackedFlagsTest.java)）。
   - トリムや大小文字無視のキーワード比較は `trim().toLowerCase()` の確保を行わない [`KeywordMatch`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/style/KeywordMatch.java) に統一。
5. **CSS 解析テンプレートの共有キャッシュ（[`StyleSheet`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/StyleSheet.java)）**:
   - 同一 CSS 文字列から解析された不変の規則・索引・`InvalidationSets` を最大 64 件キャッシュし、`StyleSheet.parse` 時は複製を返すことで、画面を開く（`open`）コストを **`5.4 ms → 3.9 ms`（−28%、確保 −1.5 MB）** に短縮（[`StyleSheetTemplateCacheTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/css/StyleSheetTemplateCacheTest.java)）。

### 4.3 レイアウトエンジン層（Milestone 3 ＋ 測定増幅の半減）

1. **5 大レイアウトモード + 3 段階インクリメンタル経路**:
   - Block (BFC / Float / マージン相殺 / Inline IFC)、Flexbox、Grid、Table、Multicol を完備。
   - 変更内容に応じて `TEXT_PATCH`（テキスト葉のインプレース再整形）→ `CHILD_PATCH`（ブロック子要素の増分挿入・削除）→ `LOCAL_SUBTREE`（Dirty 部分木のみの再レイアウト）の 3 段階ファストパスを備える。
2. **2026-11-10 レイアウト測定増幅（Layout Amplification）の半減**:
   - 300 行ダッシュボード（426 ボックス）で 1 回のレイアウトあたり **1,785 回（4.2 回/箱）** 走っていた `BoxMeasurer.measure` を、以下の 3 施策で **860 回（2.0 回/箱、−52%）** に削減し、`fullLayoutWarm` を **`1.448 ms → 0.986 ms`（−32%）** に短縮：
     - **① `%` ブロックサイズ非依存サブツリーのキャッシュ鍵正規化**: 部分木が `%` の高さ・縦余白・縦 inset を持たず `height:100%` チェーンも無い場合（[`LayoutBox.blockPercentFree`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/LayoutBox.java)）、測定キャッシュの鍵から親の `percentageBlockSize` を除外して `Float.NaN` に正規化。Flex アイテムの仮高さとストレッチ後の本高さで測定キャッシュが 100% ミスしていた問題を解消（[`LayoutKeyEquivalenceTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/layout/LayoutKeyEquivalenceTest.java) で 7,096 回の測定が非正規化時と 100% 一致することを検証）。
     - **② Flex shrink-to-fit アイテムの同幅再測定スキップ**: `maxContent` 測定で得た結果が目標幅と同じで、子孫が親幅に依存しない場合は 2 回目の測定をスキップ（[`FlexSameWidthRemeasureTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/layout/FlexSameWidthRemeasureTest.java)）。
     - **③ 新規構築ボックスの同パス測定再利用**: 新規構築されたばかりのボックスでも、同一レイアウトパス内（`lastLayoutRunId == currentRunId`）であれば直前の測定キャッシュをヒットさせる。

### 4.4 ペイント・GPU / CPU ハイブリッド描画層（Milestone 4 & 5 ＋ GL 差分走査）

1. **単一 Uber-Shader インスタンス描画とタイル並列 CPU フォールバック**:
   - [`GlRectStream`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/gl/GlRectStream.java) + [`GpuRectShader`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/gpu/GpuRectShader.java) が矩形・角丸・枠線・影・グラデーション・グリフを 80B/instance で一括描画し、`backdrop-filter: blur()` は [`DualKawase`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/gpu/DualKawase.java) で処理。
   - [`SoftwareRenderBackend`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java) は 64×64 タイル並列 [`TileQueue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/TileQueue.java) と [`OpPool`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/OpPool.java) により定常アロケーション 0 で動作。
2. **2026-11-10 GL 走査の差分更新化（[`PaintSceneDriver.patchIndexByDiff`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriver.java) + [`Clip.gpuClips()`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Clip.java) 記憶）**:
   - **根本原因の発見**: 1 要素の hover や文字変更でも、`PaintSceneDriver.rebuildIndex` が毎回全項目の姿勢 (`Resolved`) とアニメ状態 (`states[]`) の表を作り直していたため、項目ごとのクリップ記憶 (`ClipEntry`) が **99.9% ミス**し、さらに全項目で `CssAnimator.stateFor` の `ConcurrentHashMap` 検索が走っていた。
   - **解決策**:
     - ① `PaintSceneDriver.patchIndexByDiff`: 前回と今回の不変 DisplayList の項目数が同じで差分が 1/4 以下かつ構造（鍵・親・層範囲）が同じなら、**変わった位置だけ**を更新し、未変更項目の姿勢・アニメ状態・クリップ記憶を 100% 維持する（項目ごとの世代番号 `itemVersion` 付き）。
     - ② `Clip.gpuClips()`: 図形・字形ごとに毎回 `GpuClip[]` を生成していた処理を `Clip` インスタンス上にメモ化。
     - ③ `OutlineFontFace.positions`: 書体面ごとに（文字列・スタイル）からグリフ送り位置配列をメモ化。
   - **効果**: `large-list` (5,012 項目) の GL フレーム時間が **hover `4.22 → 2.23 ms` (−47%)、text `3.92 → 1.91 ms` (−51%)、style `4.40 → 2.28 ms` (−48%)** に半減し、フレーム確保量も **75〜78% 削減**（[`PaintSceneDriverDiffPatchTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriverDiffPatchTest.java), [`DiffPatchPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/DiffPatchPixelTest.java) で全画素一致を保証）。

### 4.5 テキスト・フォント層（面単位ロック分離と専用 `FT_Library`）

- **2026-11-10 全域ロック `LoadedFace.LOCK` の分割**:
  - 従来はプロセス単一の `LoadedFace.LOCK` で全書体の測定・カーニング・ラスタライズが直列化されていた。
  - FreeType は `FT_Library` 単位でスレッド安全であるため、[`LoadedFace`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/text/font/LoadedFace.java) ごとに専用の `FT_Library` (`ownLibrary`) と面単位の `lock` を持たせ、複数スレッドからの同時テキスト測定・ラスタライズを完全並列化した（[`LoadedFaceConcurrencyTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/text/font/LoadedFaceConcurrencyTest.java) で 8 スレッド並列実行時のピクセル完全一致とロック非干渉を検証）。

### 4.6 公開 API (`api/*`) とマルチインスタンス文脈 (`EngineContext`)

1. **数行で組み込める統一公開 API（[`EnderEngine`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/api/EnderEngine.java) / [`WebViewSurface`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/api/WebViewSurface.java)）**:
   - `EnderEngine.html(html).size(w, h).devicePixelRatio(dpr).font(...).images(...).target(Target.SOFTWARE | GPU_OFFSCREEN).sink(pixelSink).build()` または `.openWindow()` で起動可能。
   - [`WebViewSurface`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/api/WebViewSurface.java) はフレーム駆動 (`frame`, `needsFrame`, `invalidate`)、標準 DOM 操作 (`dom()`)、リアクティブ状態 ([`SurfaceState`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/api/SurfaceState.java))、タイマー (`setTimeout`, `setInterval`, `requestAnimationFrame`)、マウス 3 ボタン ([`MouseButton`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/api/MouseButton.java))、修飾キー ([`KeyMods`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/api/KeyMods.java))、ホストクリップボード連携 ([`SurfaceClipboard`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/api/SurfaceClipboard.java))、面スコープのカスタムフォント ([`SurfaceFont`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/api/SurfaceFont.java)) と画像プロバイダ ([`ImageSource`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/api/ImageSource.java)) を、`core.*` / `common.*` の内部型を一切露出せずに提供する（[`EnderEngineTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/api/EnderEngineTest.java), [`WebDocumentDomTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/api/WebDocumentDomTest.java), [`SurfaceLifecycleTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/api/SurfaceLifecycleTest.java), [`SurfaceInputTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/api/SurfaceInputTest.java), [`SurfaceResourcesTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/api/SurfaceResourcesTest.java)）。
2. **`static` グローバル状態の排除（[`EngineContext`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/EngineContext.java) & [`PipelineStats`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/diag/PipelineStats.java)）**:
   - `RenderPipeline`, `UiDocumentCompiler`, `DocumentLoader`, `Bindings` に散在していた `static` フィールド（`i18nResolver`, `intrinsicSizeResolver`, `boxRenderer`, `resourceTextResolver`, 各種切り分けフラグ）を [`EngineContext`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/EngineContext.java) に一元化し、`EngineContext.runWith(ctx, ...)` でスレッド単位に独立した文脈を切り替え可能にした。
   - 複数スレッドで競合していた `layoutChangedBoxes` や `lastBuildStats` も `ThreadLocal`（[`PipelineStats`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/diag/PipelineStats.java)）へ分離済み（[`EngineContextTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/EngineContextTest.java), [`EngineContextRendererTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/EngineContextRendererTest.java), [`EngineContextPipelineFlagsTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/EngineContextPipelineFlagsTest.java)）。

---

## 5. 二重構造（Type A / B / C）の解消記録と境界ガード

旧アーキテクチャと新 DoD カーネルの移行期に発生した二重構造は、以下のように整理・解消されている。

### 5.1 解消済みの旧構造（Type A / B）

| 対象領域 | 撤去・統合された旧構造 | 現在の単一正本（Single Source of Truth） |
| :--- | :--- | :--- |
| **DOM ノード ID** | `HtmlNode.nodeId` と `arenaHandle` の二重採番 | [`DocumentArena`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/DocumentArena.java) の `arenaHandle` に一本化 |
| **DOM インライン Style** | `attributes.get("style")` 文字列の毎回再パース | [`InlineStyleOverlay`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/node/InlineStyleOverlay.java)（変更時のみ直列化） |
| **CSS 算出値保持** | `ComputedStyle` の `String[] values` + 外部 `TypedValueCache` 毎回ハッシュ検索 + `StyleFacts.edges` | [`CssValue[]`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/CssValue.java)（型付き解析結果を内蔵）+ [`ComputedStyle.packedEdges`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/ComputedStyle.java) (`long[25]`) |
| **CSS カスケード作業領域** | `LinkedHashMap<String, String>` の生成・走査・文字列名アクセス | [`StyleTable`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/StyleTable.java) + [`CompiledDeclarations`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/CompiledDeclarations.java) + [`CssPropertyIndex`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/CssPropertyIndex.java) |
| **CPU タイルラスタ** | 削除済み旧 `TileRasterizer` + `Op` 毎フレーム `new` | [`TileQueue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/TileQueue.java) + [`OpPool`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/OpPool.java) |
| **GL バックエンドの CPU 依存** | `LwjglGlRenderBackend` が `SoftwareRenderBackend` を直接フィールド保持 | [`CpuLayerFallback`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/gl/CpuLayerFallback.java) インターフェースでパッケージ依存を遮断 |
| **パイプラインの二重 static** | `RenderPipeline` / `UiDocumentCompiler` / `DocumentLoader` の重複 static リゾルバ | [`EngineContext`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/EngineContext.java) に統合 |

### 5.2 意図的な相補構造（Type C — 役割分担と発火条件）

| レイヤ | 段階 1（高速パス / 事前計算） | 段階 2（汎用パス / 動的解決） | 境界ガードテスト |
| :--- | :--- | :--- | :--- |
| **セレクタ照合** | [`DocumentArena.ancestorHas`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/DocumentArena.java) (64-bit Bloom $O(1)$ 棄却) | [`SelectorMatcher`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/SelectorMatcher.java) (Bloom 通過時のみ正確な DOM 照合) | `DocumentArenaTest` |
| **状態変化スタイル** | [`StyleStateAutomaton`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/StyleStateAutomaton.java) ($O(1)$ スロット遷移) | [`StyleResolver`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/StyleResolver.java) (未踏状態ペアの初回のみカスケード実行) | `StyleStateAutomatonTest` |
| **テキスト整形** | [`ParagraphSegments`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/text/ParagraphSegments.java) (幅非依存の書記素・単語幅キャッシュ) | [`TextShaper`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/text/TextShaper.java) (与えられた `maxWidth` への行折り返しパッキング) | `ParagraphSegmentsTest` |
| **描画バックエンド** | [`GlRectStream`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/gl/GlRectStream.java) (GPU Uber-Shader インスタンス描画) | [`CpuLayerFallback`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/gl/CpuLayerFallback.java) / [`TileQueue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/TileQueue.java) (GPU 非対応プリミティブのタイル描画) | [`GpuCpuBoundaryTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/GpuCpuBoundaryTest.java) |

---

## 6. 実測プロファイリング・Chrome 156 比較・棄却された仮説の記録

将来同じ試行錯誤を繰り返さないため、2026-11-10 に実施した Chrome 156 比較および JFR プロファイリングの知見（**何が効き、何が実測で棄却されたか**）をここに記録する。

### 6.1 Chrome 156 Headless との同一 HTML ベンチマーク比較（旧計測。現行文書・Chrome 157 での再計測は §6.4）

同一マシン・同一 300 行ダッシュボード HTML（308 要素）に対する `:core` (JMH `PipelineBench`) と Chrome 156 (`--headless=new`、ページ内 `performance.now()` 200 回平均) の比較：

| 操作 | `:core` (2026-11-10 最新) | Chrome 156 | 倍率（`:core` / Chrome） | 評価と構造的理由 |
| :--- | :---: | :---: | :---: | :--- |
| **HTML 解析 + DOM 構築** | **`0.558 ms`** | `1.132 ms` | **0.49x（`:core` が 2 倍高速）** | オフヒープ `DocumentArena` と軽量パーサにより Blink を上回る |
| **`:hover` 1 要素切替** | **`0.008 ms`** | `0.068 ms` | **0.12x（`:core` が 8.5 倍高速）** | `StyleStateAutomaton` の $O(1)$ 遷移が圧倒 |
| **1 step スクロール** | **`0.002 ms`** | `0.002 ms` | **1.0x（同等）** | 保持型スクロールオフセット更新で双方ゼロコスト |
| **ルート `--accent` 再スタイル** | **`1.201 ms`** | `0.369 ms` | 3.3x（改善前 6.2x から半減） | 全要素の `var(--accent)` 再評価コストの差 |
| **既存 DOM フルレイアウト** | **`0.986 ms`** (`fullLayoutWarm`) | `0.305 ms` | 3.2x（改善前 4.7x から縮小） | 下記 §6.2 の A→B→A→B 交互測定によるキャッシュ上書き（残り 279 回） |
| **幅変更再レイアウト (1280↔1024px)** | **`1.277 ms`** (`layoutWidthChange`) | `0.219 ms` | 5.8x（改善前 7.1x から縮小） | 同上（1 ボックス 1 スロット測定キャッシュの限界） |

### 6.2 レイアウト・再スタイルの JFR 解析と施策 S1〜S5 の検証結果

300 行ダッシュボード（426 ボックス）のレイアウトを計装した結果、**測定増幅（Layout Amplification）の真犯人**は以下の 2 点であることが判明した。
1. **Flex 仮高さ (`NaN`) と確定高さ (`28px`) による測定キャッシュのミス**: すべてのボックスが `%` のブロックサイズを使っていなくても、鍵の `percentageBlockSize` が異なるだけで別物扱いになっていた（→ **S1 先行分 `blockPercentFree` で解決し、測定回数が `1,785 → 860 回` に半減**）。
2. **1 ボックス 1 スロットキャッシュ (`lastMeasureKey`) の A→B→A→B 交互上書き**: Flex が 1 パス内で同じ子を「仮幅 A → 確定幅 B」で測り、次のパスでも「A → B」の順で測るため、1 スロットしか持たないキャッシュが毎回直前の B と A で上書きし合い、279 回の重複測定が残っている（→ **残課題：純粋関数 `computeSize` 化と多枠キャッシュ**）。

| 施策 ID | 内容 | 実測効果 | 採否 |
| :--- | :--- | :--- | :---: |
| **S1 (先行)** | `blockPercentFree` による `percentageBlockSize` 鍵除外 + 同パス測定再利用 | `fullLayoutWarm` **`1.448 → 0.986 ms` (−32%)**、測定 `1,785 → 860 回` | ✅ **採用** |
| **S2 (棄却)** | `flex-basis: 0 / 0px / %` のとき Flex アイテムの `maxContent` 測定を飛ばす | `layoutWidthChange` の経過時間は不変、測定呼び出しが `1,785 → 1,813 回` に**微増**（300 行 HTML は `flex: 1` ではなく `grid` と `flex: none` が主体で、対象は `bar-fill` のみだった） | ❌ **棄却・Revert** |
| **S3** | `CssValue.dependsOnViewport()` メモ化 + 親の `--*` マップ共有 + `hasInteractiveRules` 再利用 | `restyleInherited` **`2.299 → 1.201 ms` (−48%)**、`fullLayoutWarm` −10% | ✅ **採用** |
| **S4** | Flex の shrink-to-fit アイテムで `maxContent` 幅が目標幅と一致し `%` 非依存なら再測定スキップ | 測定 `1,636 → 1,568 回` (−68 回)、`layoutWidthChange` −3% | ✅ **採用** |
| **S5** | `StyleSheet.parse` の解析済みテンプレート共有キャッシュ（最大 64 件） | 画面オープン `open` **`5.4 → 3.9 ms` (−28%)**、確保 `−1.5 MB` | ✅ **採用** |

### 6.3 GL 走査のボトルネック解析と「`GlRectStream` 容量拡大」を棄却した理由

1. **採用して劇的に効いたもの（§4.4）**:
   - `PaintSceneDriver.patchIndexByDiff` + `Clip.gpuClips()` メモ化 + `OutlineFontFace.positions` メモ化により、`large-list` の GL フレーム時間を **−47〜51%**、ヒープ確保を **−75〜78%** 削減。
2. **実測で判明し、採用を見送った仮説（`GlRectStream(4096)` の容量拡大）**:
   - `large-list`（約 19,000 字形）では、1 フレームあたり `GlRectStream` の容量上限 4,096 を超えた約 11,300 個の図形・字形（約 73%）が CPU タイルラスタにフォールバックしている。
   - そこで **`GlRectStream` の容量を 16,384〜65,536 に引き上げて全字形を GPU ストリームに載せる実験**を行ったところ、メインスレッドのフレーム時間は **`2.60 ms → 3.78 ms / 3.44 ms` とかえって悪化**した。
   - **理由**: 現在の `GlRectStream.open`（10%）+ `writeClips`（5.7%）+ `PersistentVramBuffer.touch`（5%）の 1 図形あたりのメインスレッド CPU 積み込みコストが、ワーカースレッドで並列実行される CPU タイルラスタよりも高くつくため。
   - **結論**: 単純にストリーム容量を増やすだけでは得にならない。未変更項目の GPU インスタンスデータを毎フレーム詰め直さない**「項目単位の保持された GPU 記録（Retained GPU Records per DisplayItem）」**が本命である。

### 6.4 他エンジン横断ベンチマーク（2026-11-10 実施・Chrome 157 / Firefox 159 / Swing / JavaFX）

§6.1 の旧比較（300 行ダッシュボード・Chrome 156）を置き換える、現行の標準文書での全面再計測。**再現スクリプトは [`src/bench-crossengine/`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/bench-crossengine/README.md)**（`:core` 側は `PipelineBench` / `IncrementalBench` / `PaintBench` / `FineProfileMain`）。

**環境**: Windows 11・24 論理コア・Temurin JDK 21.0.12・Chrome 157（`--headless=new`。`--disable-gpu` = CPU 合成、既定 = GPU 合成）・Edge（Blink。Chrome と同系統でほぼ同値のため省略）・Firefox 159 headless・Swing（JDK 21）・OpenJFX 21.0.5（非表示 `Scene`）。ビューポート 800×600・DPR 1。
**文書**: `showcase`（23.6 KB・164 要素）／`large-list-N`（N 行の `<li>` を高さ 400px のスクロールコンテナに入れた文書。500 行 = 506 要素・5,012 DisplayItem）。
**方法**: (A) `:core` JMH 全件（fork 1・warmup 3・measure 5）／(B) `FineProfileMain`（`UiView.renderFrame` 全段・1,500 フレーム平均。`null` = 描画なし・`cpu`・`gl`）／(C) ブラウザ内 `performance.now()` + 強制 style（`getComputedStyle`）・layout（`offsetHeight`）の 15 サンプル中央値／(D) Chrome を CDP で駆動し rAF ごとに 1 変更を起こしてトレース集計（main = `BeginMainFrame`、raster = `RasterTask`、draw = `DrawAndSwap`）／(E) Swing・JavaFX の 500 行ラベルリスト。

> **計測上の注意**: ① `:core` は `FontResolver.FALLBACK`、ブラウザはシステムフォントで、文字幅・字形数が完全には一致しない。② Chrome の rAF 駆動には約 `0.14 ms` の計測枠固有コスト（idle 行）が含まれる。③ `hover` は両者とも **画面外の行**（`item-100`/`item-101`）の見た目を変えるため、可視範囲カリングの有無が差として表れる（意図的）。④ Swing は `JLabel`（CSS 無し）、JavaFX は `Label` + `ScrollPane` で意味論が異なる。参考値であって同一機能の比較ではない。⑤ ヒープ確保は JVM 側のみ（ブラウザは計測不可）。⑥ 1 回の計測（各条件 1 実行）で、`FineProfileMain` の平均は p99 が平均の 2〜5 倍とばらつく。差が 1.5 倍未満のものは誤差の範囲として読む。

#### 6.4.1 `:core` JMH 全件（ms / 操作）

| ベンチ | `showcase` | `guidebook` | `large-list`(500) | 備考 |
| :--- | :---: | :---: | :---: | :--- |
| `hoverToggle`（`:hover` 1 要素） | **`0.0069`** | — | — | `button` が無い文書は対象外 |
| `restyleInherited`（body 継承色） | `0.101` | `0.216` | `0.778` | |
| `restyle`（状態を捨てる全カスケード＝最悪ケース） | `1.548` | `0.887` | `1.329` | 旧表の `restyle 0.008` は `hoverToggle` のこと |
| `layoutWidthChange`（幅 ±1px） | `0.931` | `0.477` | `1.019` | |
| `fullLayoutWarm` | `0.989` | `0.455` | `1.048` | |
| `fullLayout`（状態なし） | `2.388` | `1.113` | `1.847` | |
| `relayout`（測定 + 配置のみ） | `1.212` | `0.290` | `0.757` | |
| `displayList` | `0.397` | `0.320` | **`1.450`** | 行数に比例して増える |
| `scrollStep`（旧経路で全段） | `1.836` | `0.719` | `2.551` | 実スクロールは保持レイヤ経由（§6.4.3） |
| `softwarePaint`（800×600 全面 CPU 描画） | `1.970` | `0.552` | `1.955` | |
| `IncrementalBench.textSameSize` | — | — | `0.148` | 1 行の文字差し替え + flush |
| `IncrementalBench.insertAndRemoveRow` | — | — | `2.454`（行高が小数）/ `1.123`（整数） | 挿入 + 削除の 2 回ぶん |

#### 6.4.2 実フレーム（`UiView.renderFrame` 全段）— `showcase`（730 項目）

| シナリオ | `:core` null（描画なし） | `:core` **GL** | `:core` CPU | Chrome main | Chrome 全 CPU（GPU 合成） | Chrome 全 CPU（CPU 合成） |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `idle` | `0.001` | `0.001` | `0.001` | `0.139` | `0.140` | `0.157` |
| `scroll` | `0.010` | `0.008` | `0.008` | `0.150` | `0.151` | `0.173` |
| `hover` | `0.282` | `0.689` | **`4.418`** | `0.719` | `0.940` | `1.550` |
| `text`（本文 1 文字） | `0.268` | `0.769` | **`4.528`** | `0.623` | `0.785` | `1.158` |
| `style`（1 要素 style 属性） | `0.245` | `0.770` | **`4.738`** | `0.531` | `0.679` | `0.759` |
| `inherited`（body 継承色） | `0.595` | `1.394` | **`7.350`** | `0.577` | `0.727` | `0.933` |
| `fontsize`（body の font-size＝全面無効化） | `1.741` | `2.332` | **`8.993`** | `1.571` | `1.963` | `4.063` |
| `child`（子の挿入・削除） | `0.565` | `1.274` | **`6.849`** | `0.726` | `0.925` | `1.789` |
| `open`（解析 + 初回フレーム） | `6.189` | `5.392` | `13.341` | ページ内 `5.0`（Edge `6.4`・Firefox `8.0`） | — | — |

→ **小規模ページでは GL 経路は Chrome とほぼ同等**（hover/text/style で main スレッド 1.0〜1.5 倍・全 CPU 比 0.7〜1.1 倍、`inherited`/`fontsize` は 1.2〜2.4 倍）。一方 **CPU バックエンドは 1 フレーム 4.4〜9.0 ms**で、同条件の Chrome CPU 合成（0.76〜4.1 ms）より遅い。

#### 6.4.3 実フレーム — `large-list`（500 行・5,012 項目）と 2,000 行（20,012 項目）

| 文書 / シナリオ | `:core` null | `:core` **GL** | `:core` CPU | Chrome main | Chrome 全 CPU（GPU 合成） | GL ÷ Chrome 全 CPU |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| 500 / `scroll` | `0.086` | `0.201` | `3.276` | `0.205` | `0.381` | 0.5x |
| 500 / `hover` | `0.488` | **`2.619`** | `3.401` | `0.354` | `0.355` | **7.4x** |
| 500 / `text` | `0.181` | **`2.180`** | `3.098` | `0.488` | `0.488` | **4.5x** |
| 500 / `style` | `0.414` | **`2.570`** | `3.624` | `0.333` | `0.333` | **7.7x** |
| 500 / `inherited` | `2.346` | **`7.525`** | `10.231` | `0.797` | `0.957` | **7.9x** |
| 500 / `fontsize` | `3.416` | `7.636` | `10.425` | `3.274` | `3.450` | 2.2x |
| 500 / `child` | `0.882` | **`4.956`** | `7.262` | `0.541` | `0.542` | **9.1x** |
| 2,000 / `scroll` | `0.331` | `0.634` | — | `0.236` | `0.460` | 1.4x |
| 2,000 / `hover` | `2.952` | **`13.053`** | — | `0.548` | `0.549` | **23.8x** |
| 2,000 / `text` | `0.725` | **`10.254`** | — | `1.017` | `1.017` | **10.1x**（null 単体では Chrome より速い） |
| 2,000 / `child` | **`9.722`** | **`25.585`** | — | `1.090` | `1.090` | **23.5x** |
| 2,000 / `fontsize` | `17.509` | `34.097` | — | `11.164` | `11.346` | 3.0x |

Chrome のみ（`:core` は §6.5 W8 の入力上限で開けない）: **5,000 行** `hover 0.873` / `text 2.151` / `child 2.233` / `fontsize 27.98` ms、**20,000 行** `hover 2.767` / `text 7.282` / `child 7.558` / `fontsize 117.1` ms（`scroll` は全サイズで `0.23` ms 前後）。

#### 6.4.4 ブラウザ内の style / layout 単体（ms）— 強制再計算の中央値

| 文書 / 操作 | Chrome | Edge | Firefox | `:core`（比較対象） | `:core` ÷ Chrome |
| :--- | :---: | :---: | :---: | :---: | :---: |
| showcase / `:hover` style+layout | `0.337` | `0.363` | `0.035` | `0.0069`（JMH `hoverToggle`） | **0.02x（約 49 倍速い）** |
| showcase / body 継承色 | `0.453` | `0.795` | `0.285` | `0.101`（`restyleInherited`） | 0.22x（約 4.5 倍速い） |
| showcase / 幅 800↔799px | `0.0835` | `0.0925` | `0.080` | `0.931`（`layoutWidthChange`） | **11.1x 遅い** |
| showcase / HTML 解析（`DOMParser`） | `0.345` | `0.345` | `0.150` | （本回は未再計測。旧 §6.1: 2 倍速） | — |
| showcase / open（解析→初回レイアウト） | `5.0` | `6.4` | `8.0` | `6.189`（null フレーム） | 1.2x 遅い |
| large-list(500) / 幅 ±1px | `0.762` | `0.746` | `0.490` | `1.019` | 1.3x 遅い |
| large-list(500) / 1 行テキスト | `0.076` | `0.076` | `0.015` | `0.148`（`textSameSize`、DisplayList 込み） | 1.9x |
| large-list(500) / 子の挿入または削除（1 操作） | `0.082` | `0.083` | `0.025` | `0.56〜1.23`（`insertAndRemoveRow` ÷ 2） | **7〜15x 遅い** |
| large-list(500) / body 継承色 | `0.309` | `0.320` | `0.725` | `0.778` | 2.5x 遅い |
| large-list(500) / open | `6.4` | `9.2` | `8.0` | `4.611`（null フレーム）/ `9.220`（GL） | 0.7x（null）〜1.4x（GL） |
| large-list(2,000) / open | `19.8` | — | — | `25.495`（null フレーム） | 1.3x 遅い |

Chrome の全面無効化（`fontsize`）の内訳（main、ms、GPU 合成）: showcase = style `0.26` + layout `0.60` + prepaint `0.19` + paint `0.23`、large-list(500) = style `0.62` + layout `1.63` + prepaint `0.48` + paint `0.25`、2,000 行 = style `2.17` + layout `6.64` + prepaint `1.77` + paint `0.30`。同条件の `:core`（約 2,200 行・`FineProfileMain` 段別）は restyle `2.58` + measure `7.64` + place `1.65` + **display-list `9.93`**（合計 24 ms）で、**style / layout は Chrome の 1.2〜1.4 倍だが、ペイント準備（DisplayList）は 4.7 倍**重い。

#### 6.4.5 Java UI ライブラリとの比較（500 行ラベルリスト・800×600・ms）

| 操作 | Swing (`JLabel`) | JavaFX 21 (`Label`+`ScrollPane`) | Swing `JEditorPane`（HTML 3.2） | Chrome | Firefox | **`:core`** |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| 構築 + 初回レイアウト（open） | `5.05` | `18.53` | `32.53` | `6.4` | `8.0` | **`4.61`**（GL 込み `9.22`） |
| 幅変更の再レイアウト | `0.469` | `1.018` | `2.064` | `0.762` | `0.490` | `1.019` |
| 1 行のテキスト変更 + レイアウト | `0.204` | `0.696` | — | `0.076` | `0.015` | `0.181`（描画なし）/ `2.180`（GL） |
| `:hover` 相当（擬似クラス + CSS） | `≈0`（再描画のみ） | `0.572` | — | `0.005` | `0.015` | `0.488`（GL `2.619`） |
| 子の挿入または削除（1 操作） | `0.314` | `0.674` | — | `0.082` | `0.025` | `0.882`（GL `4.956`） |
| ビューポート全面の描画 | `0.515` | `1.143`（`snapshot`・読み戻し込み） | `0.345` | `≈0.4`（`scroll` 全 CPU） | — | CPU `1.955`（JMH）/ GL スクロール `0.201` |
| 1 フレーム（テキスト変更→レイアウト→描画） | `0.636` | `3.800` | — | `0.488` | — | GL `2.180` / CPU `3.098` |

Dear ImGui 等のネイティブ即時モード GUI は HTML/CSS・レイアウトエンジンを持たず機能軸が異なるため本回は計測対象外。

### 6.5 計測で判明した「現状の劣っている部分」（Chrome / Java UI 比・実測差の大きい順）

> **2026-11-11 注**: 下表の数値は 11-10 計測時点。W1〜W7 の最新状態は **§6.7.4**、再策定後の対応は **§7.3 R0〜R7**。

| # | 弱点 | 実測の根拠（`:core` ÷ Chrome。特記なければ全 CPU） | 構造的な原因 |
| :--- | :--- | :--- | :--- |
| **W1** | 🔧 **一部解消（2026-11-10: ペインタ段カリング実装、§7.3 P0-0）** GL/CPU 描画が可視範囲カリングを持たず、再描画コストが総項目数に比例（`GlRectStream` 4,096 超過ぶんは CPU フォールバック） | 画面外 1 行の `hover`: 500 行 **7.4x**（`2.62` vs `0.36` ms）→ 2,000 行 **23.8x**（`13.05` vs `0.55` ms）。2,000 行の GL 実行で 1 フレームあたり約 `4,100` 図形を GPU、約 `5.9 万` 個を **容量超過で CPU へ棄却**（`rejects` カウンタ）。ただし `scroll` は保持レイヤで `0.2〜0.6 ms` と良好 | スクロールコンテナ外・ビューポート外の項目も毎フレーム GPU ストリームへ詰め直している。§6.3 の「項目単位の保持 GPU 記録」が未実装のため、局所変更でも全項目が対象 |
| **W2** | **子の挿入・削除が O(行数) 以上（`CHILD_PATCH` が成立してもペイント準備が全走査）** | 500 行 **9.1x**（`4.96` ms）→ 2,000 行 **23.5x**（`25.6` ms、null でも `9.72` vs `1.09`）。`CHILD_PATCH` は 1,100 回中 1,100 回ヒットしているのに、段別では **display-list が `9.02` ms 中の `7.06` ms（78%）** | レイアウトは局所化済みだが `DisplayListBuilder` が全ボックスを再走査（§7.3 P0-4 の範囲差し替え未実装）。4 倍の行数で 11 倍の時間＝超線形 |
| **W3** | **CPU バックエンドに差分描画（ダメージ矩形）・スクロールレイヤ再利用が無い** | showcase `hover` `4.42` ms（Chrome CPU 合成 `1.55` ms、**2.9x**）、`inherited` `7.35` ms、large-list `scroll` `3.28` ms（Chrome CPU 合成 `1.20`・GL `0.20`、**2.7x**） | GL 経路は `patchIndexByDiff` と `GpuScrollLayer` で保持するが、`SoftwareRenderBackend` は変更のたびに 800×600 を全面再ラスタ（`softwarePaint` 単体 `1.97` ms + 走査・合成）。ヘッドレス／GL 不可環境の実用上限を決める |
| **W4** | **ペイント準備（DisplayList 生成）が Chrome の prepaint+paint の約 4.8 倍** | `fontsize`（全面無効化・約 2,200 行）: display-list `9.93` ms vs Chrome prepaint+paint `2.07` ms。`displayList` JMH は large-list で `1.45` ms（showcase の 3.7 倍） | 変更の有無に関わらず全ボックスを辿って `DisplayItem` を（メモ済みでも）列挙する。ボックス単位のスライス保持が無い |
| **W5** | **幅変更・全面無効化のレイアウト（測定キャッシュの限界）** | showcase の幅 ±1px: **11.1x**（`0.931` vs `0.0835` ms）。large-list 500 行は 1.3x、`fontsize` の layout は 1.4x（`9.3` vs `6.6` ms）、`inherited`（500 行）は 2.5x（`2.35` vs `0.93` ms）。2,000 行 `fontsize` は **3.0x**（GL 込み） | 親制約が同一の子を丸ごとスキップする Blink の LayoutNG 制約キャッシュ相当が無い。1 ボックス 1 スロットの測定キャッシュ（§6.2） |
| **W6** | **フレームあたりのヒープ確保量が大きい（GC 圧）** | `fontsize`（約 2,200 行）`33 MB`/フレーム、`child`（2,000 行）`16〜32 MB`/フレーム、`hover`（2,000 行・GL）`5.1 MB`/フレーム、`inherited`（500 行）`13 MB`（§1.1 の `0 B` 目標は `idle`/`scroll`/JMH `restyle` のみ達成） | `DisplayItem` 列・`Resolved`・クリップ列の再生成。144 Hz で `fontsize` 級が続けば約 `4.7 GB/s` |
| **W7** | **起動・オープンがやや遅い（JIT 暖機前は未計測）** | showcase `open` `6.19` ms（Chrome `5.0` / Edge `6.4`）、2,000 行 `25.5` vs `19.8` ms（1.3x）。Java UI 比では JavaFX（`18.5` ms）より高速・Swing（`5.05`）と同等 | `StyleSheet` 雛形キャッシュ済みでも解析・`sealNodeIds`・初回フルスタイルが残る。**コールド JVM の最初の 1 フレームは本回未計測**（要追加） |
| **W8** | ✅ **解消済み（2026-11-10）** 入力文書の上限による黙った切り捨て | 5,000 行・20,000 行の文書を開くと **2,224 要素（約 2,200 行）で打ち切られる**（`ITEMS` が 22,192 で頭打ち。5,000/20,000 行の結果が同一になって発覚）。[`DocumentLoader.parseDocument`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/DocumentLoader.java) の `html.length() > 131072`（128 KiB）で切る。`ParseLimits` の「数万行も通す」コメントとも食い違う | 例外・警告なしにタグの途中でも切断。Chrome は 20,000 行（約 1.4 MB）を問題なく処理 |
| **W9** | **機能ギャップ（性能外）** | IME 変換（Preedit）・`<dialog>`/`popover`・`content-visibility`・`subgrid`・縦書き・カラー絵文字が未対応（§7.2 P3）。Swing/JavaFX は IME 対応済み | — |

**優位な点（実測で確認）**: `idle` `0.001` ms／`scroll` `0.008〜0.2` ms（Chrome は rAF 枠込みで `0.14〜0.38`）、`:hover` style+layout `0.0069` ms（Chrome `0.337`・**49 倍速い**）、継承色の再スタイル 4.5 倍速い、500 行リストの `open` が Chrome/Firefox/Edge/JavaFX/JEditorPane より速い（null フレーム）、局所テキスト更新は 2,000 行で null `0.725` ms（Chrome `1.02`）と大規模でも**パイプライン単体は Chrome 以上**（遅いのは GL への送出 §W1）。

### 6.6 本回の検証で得た追加知見（再発防止メモ）

1. **`large-list-5000` で結果が `large-list-20000` と同一になった件**: 文書が 128 KiB で切られていた（W8）。大規模計測では `Workloads.largeList(N)` の出力サイズを必ず確認する（`FineProfileMain` は `DOM elements=` を出力するようになった）。この切り捨てのため `:core` の 5,000 / 20,000 行の値は採用していない（§6.4.3 は 2,000 行まで）。
2. **GL 容量棄却は約 `5.9 万`/フレーム**（2,000 行・20k 項目の実測）。GL の `hover` が 500 行 `2.6 ms`→2,000 行 `13.1 ms` と跳ねるのは、4,096 を超えた図形が CPU タイル描画に回るため（§6.3 の容量拡大実験が逆効果だった理由と一致）。
3. **Chrome の CPU 合成と GPU 合成の差**は raster の `0.6→0.09 ms`（showcase `hover`）で、main スレッド側（style/layout/prepaint/paint）は同一。`:core` の GL 経路が勝てるのは main スレッドの CPU 時間であり、全体を揃えるには W1/W2 の解消が必要。
4. **`hover` 往復の GL 実フレーム（2,000 行）は `restyle-local 0.25 ms` + display-list `3.6 ms` + GL 送出 約 `9 ms`**。局所再スタイルは既に十分速く、残りはすべて下流（W1・W4）。

### 6.7 `:core` 再計測（2026-11-11・Segmented DisplayList / Tile Dirty Bitset / 64-bit 鍵化 反映後）

§6.4 と**同一条件**（JMH `-f 1 -wi 3 -i 5 -prof gc`、`FineProfileMain` 1,500 フレーム平均、同一 JDK・同一マシン、各条件 1 実行）で `:core` のみ再計測。ブラウザ・Swing・JavaFX の値は §6.4 のまま（再計測していない）。2,000 行は `DOM elements=2006` を確認済み。表は「§6.4（11-10）→ **今回（11-11）**」。差が 1.5 倍未満は誤差として読む（§6.4 注⑥）。

#### 6.7.1 JMH（ms / 操作）

| ベンチ | `showcase` | `guidebook` | `large-list`(500) |
| :--- | :---: | :---: | :---: |
| `hoverToggle` | `0.0069 →` **`0.007`** | — | — |
| `restyleInherited` | `0.101 →` **`0.099`** | `0.216 →` **`0.219`** | `0.778 →` **`0.784`** |
| `restyle`（最悪ケース） | `1.548 →` `1.798`（誤差 ±0.87） | `0.887 →` `0.944` | `1.329 →` **`1.329`** |
| `layoutWidthChange` | `0.931 →` `1.039` | `0.477 →` `0.461` | `1.019 →` `1.035` |
| `fullLayoutWarm` | `0.989 →` **`0.971`** | `0.455 →` `0.456` | `1.048 →` **`1.011`** |
| `fullLayout` | `2.388 →` `2.490` | `1.113 →` `1.310` | `1.847 →` `2.162` |
| `relayout` | `1.212 →` `1.229` | `0.290 →` `0.265` | `0.757 →` `0.787` |
| `displayList`（フル構築） | `0.397 →` `0.475` | `0.320 →` `0.352` | `1.450 →` `1.576` |
| `scrollStep`（旧全段経路） | `1.836 →` `1.816` | `0.719 →` `0.741` | `2.551 →` `2.597` |
| `softwarePaint`（全面描画の単体） | `1.970 →` `4.383`（±1.70・要再確認） | `0.552 →` `0.743` | `1.955 →` **`0.321`**（−84%） |
| `textSameSize` | — | — | `0.148 →` `0.165` |
| `insertAndRemoveRow` | — | — | `2.454 →` `2.585`（小数行高）/ `1.123 →` `1.173`（整数） |

JMH はスタイル・レイアウト・フル DisplayList の単体で、今回の変更（差分描画・スライス保持・鍵化）の主対象ではないため**ほぼ不変（退行なし）**。`softwarePaint` は large-list のみ大幅改善。showcase の `1.97 → 4.38 ms` は悪化に見えたが、**R0 の検証（§6.8.3）で退行ではなく 11-10 の値が再現しないことが判明**（同じ 11-10 のコミットでも今の環境では `4.5 ms`）。

#### 6.7.2 実フレーム — `showcase`（730 項目、ms）

| シナリオ | null | **GL** | **CPU** |
| :--- | :---: | :---: | :---: |
| `idle` | `0.001 →` `0.0005` | `0.001 →` `0.0005` | `0.001 →` `0.0007` |
| `scroll` | `0.010 →` `0.0105` | `0.008 →` `0.008` | `0.008 →` `0.008` |
| `hover` | `0.282 →` **`0.198`** | `0.689 →` `0.719` | `4.418 →` `4.593` |
| `text` | `0.268 →` `0.255` | `0.769 →` `0.781` | `4.528 →` `5.211` |
| `style` | `0.245 →` `0.252` | `0.770 →` `0.778` | `4.738 →` `5.032` |
| `inherited` | `0.595 →` `0.587` | `1.394 →` `1.470` | `7.350 →` `8.251` |
| `fontsize` | `1.741 →` `1.772` | `2.332 →` `2.831` | `8.993 →` `9.268` |
| `child` | `0.565 →` `0.596` | `1.274 →` `1.359` | `6.849 →` `7.464` |
| `open` | `6.189 →` **`5.507`** | `5.392 →` `5.106` | `13.341 →` `12.702` |

→ 小規模ページは**ほぼ不変**。CPU バックエンドの showcase は 1 フレームの大半が全面再ラスタ（dirty が画面全体に及ぶ）のため、差分描画の効果は出ていない。

#### 6.7.3 実フレーム — `large-list`（500 行・5,012 項目）と 2,000 行（20,012 項目）

| 文書 / シナリオ | null | **GL** | **CPU** |
| :--- | :---: | :---: | :---: |
| 500 / `scroll` | `0.086 →` `0.085` | `0.201 →` **`0.157`** | `3.276 →` **`1.392`**（−58%） |
| 500 / `hover` | `0.488 →` `0.499` | `2.619 →` **`0.721`**（**−72%**） | `3.401 →` **`2.282`**（−33%） |
| 500 / `text` | `0.181 →` `0.182` | `2.180 →` **`0.431`**（**−80%**） | `3.098 →` **`1.289`**（−58%） |
| 500 / `style` | `0.414 →` `0.428` | `2.570 →` **`0.653`**（**−75%**） | `3.624 →` **`1.661`**（−54%） |
| 500 / `inherited` | `2.346 →` `2.663` | `7.525 →` **`3.589`**（−52%） | `10.231 →` **`7.995`**（−22%） |
| 500 / `fontsize` | `3.416 →` `3.756` | `7.636 →` **`4.243`**（−44%） | `10.425 →` **`7.758`**（−26%） |
| 500 / `child` | `0.882 →` `0.872` | `4.956 →` **`2.184`**（−56%） | `7.262 →` **`5.297`**（−27%） |
| 500 / `open` | `4.611 →` `5.838` | `9.220 →` **`6.171`**（−33%） | — `→` `9.563` |
| 2,000 / `scroll` | `0.331 →` `0.342` | `0.634 →` `0.559` | — |
| 2,000 / `hover` | `2.952 →` `3.384` | `13.053 →` **`3.496`**（**−73%**） | — |
| 2,000 / `text` | `0.725 →` `0.999` | `10.254 →` **`1.613`**（**−84%**） | — |
| 2,000 / `child` | `9.722 →` **`7.571`**（−22%） | `25.585 →` **`13.768`**（−46%） | — |
| 2,000 / `fontsize` | `17.509 →` `14.887` | `34.097 →` **`18.931`**（−44%） | — |

**GL ÷ Chrome 全 CPU（§6.4.3 の Chrome 値で換算）**: 500 行 `hover` 7.4x → **2.0x**、`text` 4.5x → **0.9x（Chrome 並み）**、`style` 7.7x → **2.0x**、`inherited` 7.9x → **3.8x**、`child` 9.1x → **4.0x**。2,000 行 `hover` 23.8x → **6.4x**、`text` 10.1x → **1.6x**、`child` 23.5x → **12.6x**、`fontsize` 3.0x → **1.7x**。

#### 6.7.4 §6.5 弱点の更新

| # | 状態 | 今回の実測 |
| :--- | :--- | :--- |
| W1（GL ストリームの総項目数比例） | ✅ **large は解消（§9）** | large 2,000 行 `hover` GL は窓付き表示リスト後 **`0.30 ms`**（Chrome `0.55` 以下）。GL 送出は `paint 0.167 ms` で律速でない。`text` は §6.7 の `1.61 ms` から未再計測（§9.9 の次回項目）。 |
| W2（子の挿入・削除） | ✅ **large は解消／varied は残存（§9）** | large 2,000 行 `child` GL は窓付き表示リスト後 **`1.44 ms`**（Chrome `child250` `1.98` 以下、確保 `938 KB`）。varied 2,000 行は **`5.15 ms`**（Chrome `3.67`・1.4x）で、`place` が `3.05 ms`（59%）・`ArrayList.add` 15%。 |
| W3（CPU 差分描画・スクロール再利用） | 🔧 **large は改善／showcase は部分改善（§9）** | large-list 500 行 CPU: `scroll 3.28 → 1.39`、`hover 3.40 → 2.28`、`text 3.10 → 1.29` ms。showcase CPU `hover` **`1.77 ms`**（paint `1.64`、`partial=0/full=1801`、理由 1/6 = transition）、`inherited` **`2.62 ms`**（paint `2.19`、`partial=1599/full=2`） |
| W4（DisplayList 全走査） | ✅ **large は解消／varied は残存（§9）** | large 2,000 行 `child` null は `1.13 ms`（窓付き表示リスト＋子パッチ）。JMH `displayList`（フル構築）は不変。varied は `child` の display-list `0.316 ms`、`inherited` `1.311 ms` が残る。 |
| W5（幅変更・全面無効化レイアウト） | 🔧 **改善・未達（§9）** | JMH `layoutWidthChange` `1.039 → 0.777` ms（5 iter、Chrome `0.0835` の 9.3x）。実フレーム showcase resize `1.045 ms`（measure `0.375` + display-list `0.421`）。`MATCHFAIL` reason 4（移動するが平行移動不可）が `111.9/163.9` |
| W6（ヒープ確保量） | 🔧 **large は改善／varied は未解消（§9）** | large `hover` GL `39 KB`、`child` `938 KB`、`fontsize` `7.3 MB`。varied `fontsize` は **`25.8 MB`**/フレーム（`InlineFragment`・`LineItem`・`CssValue[]` コピー）。 |
| W7（open） | 変化小 | showcase null `6.19 → 5.51`、GL `5.39 → 5.11` ms（Chrome `5.0` と同等）。500 行 GL `9.22 → 6.17` ms |

**未解消の主課題（2026-11-12 更新・§9）**: (1) **`varied-list-2000`** の `child`（`5.15` vs Chrome `3.67`）と `inherited`（`10.20` vs `3.55`）。large-list の `child`/`fontsize` は Chrome 以下になった。(2) 幅変更レイアウト（W5: 実フレーム `1.05 ms` / JMH `0.78 ms`、Chrome `0.084`）。(3) showcase CPU の transition 中の全面再ラスタ（`hover` `1.77 ms`、paint `1.64`）。(4) varied `fontsize` の確保量 `25.8 MB`/フレーム。
### 6.8 R0 計測基盤の補強（2026-11-11 完了）

`FineProfileMain` に `PAINT` 段（バックエンドを `ProfilingRenderBackend` で包む）、`ACCOUNTED`（段合計の説明率）、`PATCH` / `CANTPATCH`（スライスパッチ命中・不成立理由）の各行を追加。[`run-fine.sh`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/bench-crossengine/run-fine.sh) / [`summarize-fine.js`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/bench-crossengine/summarize-fine.js) で 3 回実行の中央値・最小・最大を台帳化（`REPS=3 run-fine.sh fine.log` → `node summarize-fine.js fine.log stages`）。ばらつきは多くが ±5% 以内で、§6.7 の 1 回実行値は妥当だった。

#### 6.8.1 段別内訳（2,000 行・GL、ms/フレーム）と判明した事実

| シナリオ | restyle-local | layout | display-list | paint（GL 送出） | 説明率 |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `hover` | `0.10` | — | **`2.49`（70%）** | `0.77` | 95% |
| `style` | `0.14` | — | **`1.95`（64%）** | `0.77` | 94% |
| `text` | — | `0.19` | `0.44` | `0.65` | 84% |
| `child` | — | `1.22` | **`5.78`（41%）** | **`6.24`（44%）** | 94% |
| `inherited` | **`3.40`** | — | **`6.97`（44%）** | `5.16`（32%） | 98% |
| `fontsize` | `1.95` | `3.71` | **`6.41`（33%）** | `5.41`（28%） | 95% |

1. 🔴 **P0-4（スライスパッチ）は標準経路で一度も実行されていなかった**（`StandaloneView.Ui` が独自ビルダーを持ち `UiView.paintBuilder` が常に `null` → `noBuilder` で素通り）。単体テストは `DisplayListBuilder` を直接呼ぶため緑のまま。→ R1-0 で解消。**教訓: 「テストは緑だが実経路では未使用」を防ぐため、パッチ命中率をカウンタでラチェット化する（R7）。**
2. スタイル変更（`hover`/`style`/`inherited`）は当時 100% 全面再構築（`repaintFull`）。→ R1 で解消。
3. `text`・`child` のレイアウト局所パッチは large-list で 100% 命中。showcase の `child` は 0%（全フォールバック、P1-2 の既知事項）。
4. 2,000 行 `inherited` は restyle-local が `3.40` ms（継承値の伝播が全ボックスに及ぶ）。
5. CPU バックエンド: showcase `hover` は `DAMAGE partial=0 full=2101`（常に全面再描画）、large-list 500 行は `partial=2100 full=1`（差分描画が機能）。showcase では差分描画が成立していない（→ R4）。

#### 6.8.2 JMH `softwarePaint` showcase の悪化 — 退行ではなかった

11-10 の `1.970 ms` を出したコミットを同環境で測り直しても `4.477 ms` で、他のコミットも同値。差は当時の計測条件の差であり、コード退行ではない。確定した事実は、同一環境での large-list `3.452 → 0.314 ms`（約 11 倍、差分描画の効果）と、showcase が `4.5 ms` のまま未改善であること。§6.4.1 の該当値は参考値に降格。

---

### 6.9 R1 スタイル変更のスライスパッチ化 & ビルダー一本化（2026-11-11 完了）

- **R1-0**: `StandaloneView.Ui` の独自ビルダーを撤廃し `UiView.paintBuilder` に一本化。`noBuilder` フォールバックを解消。
- **R1**: `RenderPipeline.restyleLayoutTree` が収集した「寸法に影響しないスタイル変更ボックス」を `canPatchBoxes` / `patchBoxes` へ接続（`RESTYLE_REPAINT_PATCH`）。スライス重複（`cant(0,7)`）・寸法変動（`cant(0,8)`）・スタッキングコンテキストの発生/解除（`cant(0,6)`）は理由カウンタ付きで全面描画へフォールバック。
- 検証: `DisplayListSlicePatchTest` に `:hover` 往復・`transition`・スタッキング解除の全再構築一致テストを追加、`:core:test` 全件グリーン。

| 指標 | R0 | R1 後 | 目標 | 判定 |
| :--- | :---: | :---: | :---: | :---: |
| 2,000 行 `hover` GL（mean / p50） | `3.50 / 3.38 ms` | **`1.06 / 0.9995 ms`** | `< 1.0 ms` | ✅ |
| 500 行 `hover` GL | `0.721 ms` | **`0.337 ms`**（Chrome `0.34`） | `< 0.4 ms` | ✅ |
| 2,000 行 `hover` 確保量 | MB 級 | **`198.5 KB/frame`** | `< 300 KB` | ✅ |
| 2,000 行 `hover` null / `display-list` 段 | `3.38 / 2.45 ms` | **`0.249 / 0.018 ms`** | — | 約 15 倍 / 175 倍 |
| スライスパッチ命中率 | 0% | **100%** | 100% | ✅ |

### 6.10 R2: 子要素の挿入・削除（`child`）— 部分達成（2026-11-11）

**実装済み（要約）**: `canPatchChildSlices` / `patchChildSlices`。ブロック層・インライン層の 2 層スライスパッチ、後続ボックスの座標追従、親・祖先の寸法とスクロール範囲・スクロールバーの同期、不成立時の全面描画フォールバック。`ChildPatchTest`・`DisplayListSlicePatchTest`・`:core:test` 全件が全再構築と 100% 一致。

#### 6.10.1 KPI 照合

| 指標 | R0 | R2 実装直後 | 目標 | 判定 |
| :--- | :---: | :---: | :---: | :---: |
| スライスパッチ命中率 | 0% | **100%**（hit=1200 / fallback=0） | 100% | ✅ |
| 500 行 `child` 確保量（GL） | `6,598 KB` | **`1,217 KB`** | `< 2 MB` | ✅ |
| 500 行 `child` GL 時間 | `4.96 ms` | `p50 2.92 ms` | `< 0.9 ms` | ❌ |
| 2,000 行 `child` GL 時間 | `14.08 ms`※ | `p50 17.31 ms`（窓付き表示リスト後 **`2.25 ms`**、§8.6.2） | `< 3 ms` | ✅（窓付きで達成） |
| 2,000 行 `child` null 時間 | `7.57 ms`※ | `p50 13.73 ms` | `< 1.5 ms` | ❌ |
| 2,000 行 `child` 確保量（GL） | `19 MB` | `5.3 MB` | `< 2 MB` | ❌ |

※ R0 の 2,000 行値は §6.8。**時間は R0 より悪化している（確保量は減ったが後続シフトの CPU が増えた）。**

#### 6.8 後の JFR 分解（2,000 行 `child`・null、2026-11-11）と是正

| 区間 | 当初 | 是正後 | 内容 |
| :--- | :---: | :---: | :--- |
| スクロールバー ID の線形走査 | 約 5.6 ms（33%） | ≈ 0 | **存在しない横バーを探して毎回 20,012 件を全走査**していた。整数鍵比較＋「欲しい軸が揃ったら打ち切り」で解消 |
| スクロール可能ボックスの収集 | 約 1.1 ms | ≈ 0 | `renderedBoxes` 全走査 → 描画時に集めた集合の走査 |
| 後続ボックスの座標差分ループ（7a） | 約 1.6 ms | 同左 | 3,500 ボックスの `boxMemo` 引き |
| 後続アイテムの再生成（7b） | 約 8.4 ms | **約 7 ms** | 17,500 項目の再生成。**内部は `withClip`・`equals`・生成そのものではなく散在ヒープの読み出し（メモリ律速）** |
| `display-list` 段 平均 | 16.9 ms | **9.2〜9.5 ms** | フレーム平均 `19.1 → 約 10.9 ms`（null）。`:core:test` 全件グリーン |

下流（JFR 付き・GL）: フレーム `18.5 ms` = display-list `11.3` + `paint(cpu)` `4.9`（**うち索引の全面作り直し `rebuildIndex` が 61%＝約 3 ms**、`AtomTable.intern` が 25%）+ layout `1.4`。CPU バックエンドは `paint` `18.3 ms`・確保 `20 MB/frame`。

#### 6.10.2 結論 — 当初の診断の訂正

- 「平坦リストは O(N) が必然で達成不能」は**半分しか正しくない**。O(N) 自体は 17,500 個の `int` 加算なら数十 µs（D0 ベンチ: SoA 範囲加算 `0.09〜1.6 µs`、現行のレコード再生成 `458 µs`）。実コストは、**散在した 3,500 ボックス×数オブジェクトの読み出し（メモリ律速）**と、**後続の同一性が失われることで下流の増分索引（`patchIndexByShift`）が成立せず全面作り直しに落ちる**ことにある。
- 行の高さが小数のとき、後続項目の座標は「旧座標＋`dy`」にならず、小数のボックス座標から丸め直す必要がある。項目だけをずらす近道は全再構築との一致を壊す。
- よって **ビルダー・索引・バックエンドを貫く契約の再設計（座標を持たない表示表）が必要**であり、現行方式の局所最適化では `< 3 ms` に届かない。方針は §7.3 冒頭の Wave 1.7。

---


## 7. 現状の総合評価と「他 UI ライブラリを超える」ための残存課題・次期ロードマップ

### 7.1 他の UI ライブラリとの現在地比較

定性比較（実測値は §6.4。Chrome = 157 headless、Swing/JavaFX = 500 行ラベルリスト）:

| 比較軸 | JavaFX / Swing | Chromium (Chrome 157 / CEF / JCEF) | Dear ImGui / 独自 GUI | **EnderFrameLib `:core`（現在地・実測）** |
| :--- | :--- | :--- | :--- | :--- |
| **バイナリ規模・依存** | OS 固有ランタイム同梱 | **100MB 超の C++ 別プロセス** | 軽量だがレイアウトが手動座標 | **純 Java + 軽量 FFM/LWJGL（単体 JAR 配布可能、公開 API 完備）** |
| **CSS レイアウト表現力** | 独自 CSS 風サブセット（Swing の `JEditorPane` は HTML 3.2） | Web 標準フル対応 | Flexbox / Grid なし | **Block / Flex / Grid / Table / Multicol / `@container` / `oklch` 対応**（`subgrid`・縦書き・`popover` は未対応） |
| **静止・スクロール** | シーングラフの GC 負荷あり | rAF 枠込みで `0.14〜0.38 ms` | 毎フレーム全 UI 再構築 | **`idle` `0.001 ms`、`scroll` `0.008〜0.2 ms`（Chrome より低い）** |
| **局所更新（小規模ページ）** | `0.2〜0.7 ms`（Swing 1 行テキスト `0.20`、JavaFX `0.70`） | main `0.5〜0.7 ms` | — | **GL 実フレーム `0.7〜0.8 ms`（Chrome とほぼ同等）、パイプライン単体 `0.25〜0.28 ms`** |
| **局所更新（5,000 項目級）** | Swing 1 フレーム `0.64 ms`（ビューポート描画 `0.5 ms`）／JavaFX `3.8 ms`（500 行） | main `0.35〜0.5 ms`（画面外は raster しない） | — | ❌ **GL `2.2〜2.6 ms`（500 行）→ `10〜13 ms`（2,000 行）。Chrome の 4.5〜24 倍（§6.5 W1/W2）** |
| **HTML 解析・オープン** | JavaFX `18.5 ms`・JEditorPane `32.5 ms`（500 行） | showcase `5.0 ms`・500 行 `6.4 ms` | 非対応 | **showcase `6.2 ms`・500 行 `4.6 ms`（Chrome 同等〜やや優位、2,000 行は 1.3 倍遅い）** |
| **`:hover` / 継承再スタイル** | JavaFX `0.57 ms` | `0.34 ms` / `0.45 ms` | — | **`0.007 ms`（49 倍速い）/ `0.10 ms`（4.5 倍速い）** |
| **全面レイアウト（幅変更）** | Swing `0.47`・JavaFX `1.02 ms` | showcase `0.08`・500 行 `0.76 ms` | — | ❌ showcase `0.93 ms`（11 倍遅い）・500 行 `1.02 ms`（1.3 倍遅い） |
| **ホスト組み込み性** | 専用ウィンドウ前提 | オフスクリーン転送が重い | 容易 | **`EnderEngine` で GLFW 窓・GL FBO・CPU `Pixmap`・`PixelSink` に数行で結線** |

> **2026-11-11 再計測による現在地の更新（§6.7）**: 局所更新（5,000 項目級）の行は、GL 実フレームが **500 行 `hover` `0.72` / `text` `0.43` / `style` `0.65` ms**（Chrome `0.34〜0.49`、比 0.9〜2.0 倍）、**2,000 行 `hover` `3.50` / `text` `1.61` ms**（Chrome `0.55` / `1.02`、比 6.4 / 1.6 倍）へ改善し、❌ から「`text` は Chrome 並み、`hover` は 2〜6 倍差」の 🔧 になった。ただし `child` は 500 行 `2.18`（4.0 倍）・2,000 行 `13.8` ms（12.6 倍）、`inherited` 500 行 `3.59` ms（3.8 倍）が残る。**GL の 2,000 行 `hover` `3.50` ms は null `3.38` ms とほぼ同じで、GL 送出はもう律速ではない（`display-list` 段が `2.45 ms` = 70%）**。

> **総合評価（2026-11-10 の横断計測を反映して改訂）**:
> Wave 1 の実装により、**① 公開 API の整備、② `core/src/main` からの Minecraft 固有概念の完全分離、③ `EngineContext` によるマルチインスタンス化、④ `LoadedFace` の並列化、⑤ Milestone 2 の完遂、⑥ GL 走査の差分更新化とレイアウト測定回数の半減** まで完了し、**数百〜千要素規模の UI（ゲーム HUD・設定画面・ダッシュボード）では Chrome と同等以上、Swing / JavaFX を明確に上回る**水準に到達した（`:hover`・継承再スタイル・`idle`・`scroll`・オープン速度は Chrome を上回る）。
> 一方、**「他エンジンを超える」には 2 つの構造的な穴が残る**。(1) **規模耐性**: 描画側（GL 送出・DisplayList 生成・CPU 全面再描画）が可視範囲に関係なく総項目数に比例するため、2,000 行（2 万項目）級で局所更新が Chrome の 10〜24 倍、`child` が 23 倍になる（W1〜W4）。パイプライン単体（`null` バックエンド）の局所テキスト更新は 2,000 行でも Chrome 以上なので、**解くべきは「下流のペイント準備・送出を可視範囲＋変更部分に限定すること」**である。(2) **全面レイアウトの測定キャッシュ**（幅変更で Chrome の 11 倍、W5）。なお文書上限 128 KiB の黙った切り捨て（W8）は 2026-11-10 に修正済み。

---

### 7.2 横断ベンチマークから導かれる「既存ライブラリ・ブラウザを凌駕するための 7 大技術戦略」

§6.4・§6.5 の横断計測が示した本質は極めて明快である。`:core` は**上流のコンパイラ型最適化（[`StyleStateAutomaton`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/StyleStateAutomaton.java) による `:hover` 49 倍高速化、`restyleInherited` 4.5 倍高速化、2,000 行 `TEXT_PATCH` の `0.725 ms`）ではすでに Blink / Gecko を圧倒**している一方、**下流（DisplayList 構築・SceneDriver 走査・GPU/CPU ラスタ送出）および幅変更レイアウトに「$O(N_{\text{全要素}})$ の線形走査」が残っている**ために大規模ページで逆転されている。

したがって、既存のブラウザ（Blink LayoutNG / PaintArtifactCompositor、Firefox WebRender）や UI フレームワーク（Flutter Impeller、Dear ImGui、最新ゲームエンジンの GPU-Driven Rendering）の知見と、Java 21 FFM API・単一プロセス統合の強みを掛け合わせた以下の **7 大技術戦略（T1〜T7）** によって、すべての領域で既存エンジンを上回る。

| 戦略 ID | 対象弱点 | 採用するコア技術（他分野・ブラウザ技術の応用） | 計算量の変化 | 既存エンジンを超える根拠（理論・目標値） |
| :--- | :---: | :--- | :---: | :--- |
| **T1: 階層的空間カリング & スクロール区間インデックス** | **W1** | **WebRender / ゲームエンジン BVH 型**: `SCROLL_NODE` の可視 Y 区間二分探索と [`PaintOrder.Chunk`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintOrder.java) 外接 AABB カリングを [`PaintSceneDriver.run`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriver.java) の上流で行う | $O(N_{\text{total}}) \to O(\log N + N_{\text{visible}})$ | ペインタ段の `outsideClip`（2026-11-10 実装済み・CPU −55%）をさらに上流へ引き上げ、画面外 20,000 項目の `resolveAt`・`buildClip` 自体をゼロにする。2,000 行画面外 `hover` を **`< 0.25 ms`（Chrome `0.55 ms` の 2 倍以上高速）** へ |
| **T2: Segmented DisplayList（CoW スライス木）＋ 64-bit 整数鍵** | **W2, W4, W6** | **Rope / B-Tree スライス構造 & Blink PaintChunk**: 各 [`LayoutBox`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/LayoutBox.java) が自身の `[dlStart, dlEnd)` スライスを保持し、局所変更・`CHILD_PATCH` 時は該当スライスのみを配列シフト／差し替え。`keyOf` は `(arenaHandle << 8) \| partOrdinal` の `long` 化 | $O(N_{\text{boxes}}) \to O(\Delta_{\text{dirty}})$ | `CHILD_PATCH` 成立時に 78%（`7.1 ms`）を占めていた `DisplayListBuilder` 全走査と、全シナリオの 8〜10% を占める `AtomTable.intern` が消滅。2,000 行の行挿入・削除を **`< 0.5 ms`（Chrome `1.09 ms` の 2 倍高速）** へ |
| **T3: GPU-Driven Retained Instance Slices（VRAM 永続スライス保持）** | **W1, W6** | **WebRender / GPU-Driven Rendering**: 未変更 [`DisplayItem`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/model/DisplayItem.java)（特にテキストの字形群）が生成した 80B GPU インスタンス列をキャッシュし、次フレームは字形ループを飛ばして `MemorySegment.copy` 1 回（または SSBO 間接参照）で送出 | $O(N_{\text{glyphs}}) \to O(\Delta_{\text{changed items}})$ | §6.3 で `GlRectStream` 容量拡大が逆効果だった原因（1 字形 `0.13 µs` の CPU 積み込み）を根絶。容量上限 4,096 を撤廃して **5.9 万件の CPU 棄却をゼロ**にしつつ、メインスレッド送出を `< 0.1 ms` に抑える |
| **T4: Tile Dirty Bitset（64×64 タイル差分ラスタ）＋ Scroll Blit** | **W3** | **Skia / Flutter Impeller Dirty Region Tile Binning**: 800×600 画面の 130 タイルを `long[3]` ビットマスクで管理し、`patchIndexByDiff` の変更項目（旧 AABB ∪ 新 AABB）と交わるタイルのみ再ラスタ。スクロールは既存 `Pixmap` の `arraycopy` シフト＋露出帯のみ描画 | $O(W \times H) \to O(\text{Area}_{\text{dirty}})$ | ボタン 1 個の `:hover` で塗るタイルが 130 枚 → 2〜4 枚（面積比 1/40）に激減。CPU バックエンド単体で **`4.42 ms → 0.15〜0.30 ms`** となり、**Chrome CPU 合成 (`1.55 ms`) や Swing (`0.64 ms`) を逆転** |
| **T5: Blink LayoutNG 型「二段制約キャッシュ（Measure 多枠 + Place 入力スキップ）」** | **W5** | **Blink LayoutNG `NGLayoutResult` Caching**: ① [`BoxMeasurer`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/BoxMeasurer.java) を純粋関数化してボックス当たり 4-way キャッシュ（Intrinsic / 仮幅 / 確定幅）を持たせ A→B→A→B 上書きを根絶、② `place` パスでも親から渡る実効制約幅が不変なら非 Dirty 部分木の再配置を丸ごとスキップし座標オフセットのみシフト | $O(N_{\text{subtree}}^2) \to O(N_{\text{width-changed}})$ | ウィンドウ幅 ±1px 変更時に固定幅カードや未変更グリッド内の再測定・再配置がすべてスキップされ、`showcase` 幅変更を **`0.931 ms → < 0.10 ms`（Chrome `0.084 ms` と同等、Swing/JavaFX の 5〜10 倍高速）** へ |
| **T6: オフヒープ DOM 雛形クローン & 文書横断 `ShareCache` & 非同期 JIT 暖機** | **W7** | **AOT Template Cloning & Virtual Thread Prewarm**: 同一 HTML テンプレートの [`DocumentArena`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/DocumentArena.java) オフヒープ列を `MemorySegment.copy` で複製し、UA/共通 CSS の `ComputedStyle` を文書横断で共有。起動時にバックグラウンドで JIT プリウォームを実行 | 初回 Open コスト半減・コールドスパイク 0 | `StyleSheet` 雛形キャッシュ（S5）を HTML DOM と算出スタイルまで拡張し、画面オープン速度で Chrome / Firefox / JavaFX / Swing のすべてを安定して下回る |
| **T7: ブラウザに原理的に不可能な「単一プロセス・ゼロコピー空間 UI & コンパイル済み Signal」** | **W9 + 超差別化** | **① `content-visibility: auto` + [`FenwickVirtualList`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/layout/FenwickVirtualList.java) ($O(\log N)$ 自動仮想化)、② Java 21 `Record` / プリミティブ `Signal` → [`BindingPatchTable`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/BindingPatchTable.java) 直結、③ Phase C ゼロコピー 2D/3D シーングラフ融合** | 10 万行 `< 0.2 ms`・IPC/JS オーバーヘッド 0 | Chromium (CEF) のプロセス間通信・V8 JS 変換・DOM 仮想化ライブラリや、ブラウザ Three.js の `html2canvas` 転送では**構造上絶対に到達できない領域**を確立 |

---

### 7.3 未解決の重要課題（優先度順・整理版）

完了済みの項目を除き、現在残っている真の技術課題を**実測インパクト順（P0〜P4）**に整理する。

---

#### 🧭 再策定（2026-11-11、2026-11-12 更新）：再計測（§6.7・§9）に基づく新ロードマップ R0〜R7

**再計測が示した事実**（以下は 2026-11-11 の §6.7 再計測。最新の 2026-11-12 値は §9.3 と下表を参照）

1. **成果**: ペインタ段・ドライバ段カリング、64-bit 鍵、増分索引、Segmented DisplayList、CPU Tile Dirty で、large-list の局所更新（GL）が **−44〜−84%**。500 行 `text` は Chrome 並み（0.9 倍）。
2. **律速の移動**: 2,000 行 `hover` は GL `3.50` ≒ null `3.38` ms。段別は `restyle-local 0.11` + **`display-list 2.45`**（70%）。`hover` 往復は 500→2,000 行で null が `0.50 → 3.38 ms`（行数 4 倍で 6.8 倍＝超線形）で、**スタイル変更経路が `patchBoxes`（スライスパッチ）に乗らず、`DisplayListBuilder` を全走査している疑いが強い**（`UiView` が `patchBoxes` を呼ぶのは `tryTextPatch` 経路、`patchChildSlices` は `tryChildPatch` 経路のみ。**要コード確認 = R0/R1 の最初の作業**）。同じ画面外 1 行の `text` は display-list `0.47 ms` で済んでおり、パッチ経路に乗れば約 1/5 になる見込み。
3. **`child` が最大の残差**: 2,000 行 `child` GL `13.8` ms（Chrome `1.09`）。`display-list` が `5.6` ms 残っており、`CHILD_PATCH` ヒット時でもスライスパッチ（`canPatchChildSlices`）が成立していない、または成立しても後続シフトが O(N) の可能性（**仮説・未検証**）。残り約 `8 ms` はレイアウト・索引・GL 送出・確保 `19 MB/フレーム`だが内訳は未取得（R0）。
4. **全面無効化**（`fontsize` / `inherited`）: 2,000 行 `fontsize` GL `18.9` ms（Chrome `11.3`・1.7 倍）。display-list `6.2` + restyle `1.9` + 測定・配置。確保 `25 MB/フレーム`（W6）は未解決。
5. **未改善・要調査**: ① W5 幅変更レイアウト（showcase `1.04` ms、Chrome の 12 倍）。② JMH `softwarePaint` showcase `1.97 → 4.38` ms（±1.7。再現確認から）。③ **CPU バックエンドは showcase が large-list より遅い**（`hover` `4.59` vs `2.28` ms）＝ダメージ領域が全面化している疑い。④ 計測が 1 実行のため分散が大きい（p99 は平均の 2〜5 倍）。

**再策定後の優先順位（実測インパクト ÷ 工数の順）**

| ID | 優先 | テーマ | 根拠（実測） | 主な作業 | KPI（合否線） |
| :--- | :---: | :--- | :--- | :--- | :--- |
| **R0** | ✅ **完了（2026-11-11・§6.8）** | **計測基盤の補強**（以降すべての判定に使う） | 段別が `restyle-local` / `display-list` しか出ず、GL 送出・レイアウト・索引・パッチ命中が見えない。分散が大きい | ① `FineProfileMain` に段別（measure/place/index/GL send）と **パッチ命中・不成立理由カウンタ**（text/child/style/slice）を出力。② 各条件 3 回実行して中央値＋最小・最大を台帳化（`summarize-fine.js` 拡張）。③ 2,000 行 `inherited`・`relayout` を追加。④ JMH `softwarePaint` showcase の悪化を再現確認のうえ二分探索（`git bisect`）で原因特定（**実施済み: 退行ではなかった。§6.8.3**） | 2,000 行の全シナリオで段別合計がフレーム平均の 95% 以上を説明 |
| **R1** | ✅ **完了（2026-11-11・§6.9）** | **【R1-0 実装済みスライスパッチの有効化】＋ スタイル変更（`hover`/`style`/`:focus`）の DisplayList スライスパッチ化** | **R0 で判明（§6.8）: `StandaloneView.Ui.paintBoxes` が独自の `DisplayListBuilder` を持ち、`UiView.paintBuilder` が常に `null` のため、P0-4 の `patchBoxes`/`patchChildSlices` は標準経路で一度も実行されていない（スライス命中 0）**。2,000 行 `hover` display-list `2.45 / 3.50` ms（70%）、`style` `1.95`、`text` `0.44` | **✅ 完了**: ① R1-0: `StandaloneView.Ui` のビルダーを `UiView.paintBuilder` に一本化し `noBuilder` フォールバックを完全解消。② `restyle-local` の変更ボックス集合を `canPatchBoxes`/`patchBoxes` へ接続（`RESTYLE_REPAINT_PATCH` 経路確立）。③ 境界不成立（スライス重複 `cant(0, 7)`、スタッキングコンテキスト発生・解除 `cant(0, 6)`、寸法変動 `cant(0, 8)`）を理由カウンタ付きで安全フォールバック。④ `:hover` 往復・`transition`・スタッキングコンテキスト解除の 100% 一致テストを `DisplayListSlicePatchTest` に追加し全件合格 | 2,000 行 GL `hover` **`< 1.0 ms`**（現 `3.50`、Chrome `0.55`、第 2 目標 `< 0.6`）、500 行 `< 0.4 ms`、確保 `< 300 KB` |
| **R2** | 🔧 **部分達成（2026-11-11・§6.10／2026-11-12 再計測 §9）** | **子要素の挿入・削除（`child`）の残差解消（W2）** | **large 2,000 行は `1.44 ms`（Chrome `child250` `1.98` 以下）で KPI 達成**。varied 2,000 行は `5.15 ms`（Chrome `3.67`・1.4x）で未達。`place` が `3.05 ms`（59%）、`ArrayList.add` 15% | スライスパッチ（2 層・座標追従・親寸法同期）・窓付き表示リスト（§8.6.2）・fit/measure 再利用（2026-11-12 コミット）で large は解消。varied の残りは後続 1,750 行の `InlineFragment` 再生成（P2） | large `child` GL `< 3 ms` は達成。varied `child` GL **`< 3.7 ms`**（Chrome 並み）、確保 `< 2 MB` |
| **R3** | 🥇 最優先 | **全面無効化（`fontsize`/`inherited`）＋ 幅変更 ＋ 可視範囲限定のスタイル・レイアウト（W5/W6 / T5・T7-①）** | **large 2,000 行 `fontsize` は `7.63 ms`（Chrome `11.3` 以下）で達成**。varied 2,000 行 `fontsize` は `30.77 ms`（Chrome `42.1` 以下）だが確保 `25.8 MB`。varied `inherited` は `10.20 ms`（Chrome `3.55`・2.9x）で、`canPatchBoxes` 26.4% が主因。showcase 幅変更 `1.05`（Chrome `0.084`） | ① **P1: `canPatchBoxes`/`offscreen` の判定キャッシュ（§9.7）**。② P2: `placeLine`/`InlineFragment` 再利用。③ T5: 測定 4-way キャッシュ＋`place` 制約スキップ。④ `content-visibility: auto` 相当の**ビューポート外スキップ**。⑤ `inherited` は継承値を使うボックスだけへ伝播を限定 | 500 行 `inherited` GL **`< 1.5 ms`**、varied 2,000 行 `inherited` **`< 3.6 ms`**、2,000 行 `fontsize` GL **`< 10 ms`**、showcase 幅変更 **`< 0.2 ms`**、`fontsize` 2,000 行の確保 **`< 5 MB`** |
| **R4** | 🥈 高（一部達成） | **CPU バックエンドのダメージ領域全面化の解消（W3 の残り）** | showcase CPU `hover` **`1.77 ms`**（paint `1.64`、`partial=0/full=1801`、理由 1/6 = transition）、`inherited` **`2.62 ms`**（paint `2.19`、`partial=1599/full=2`）。`inherited` は目標内、`hover` は未達 | ① P7: 項目ごとの「前フレームで動作中だったか」を記録し、遷移中の項目だけを変更扱いにする（§9.7）。② 動作中項目のタイルだけを部分再描画する。③ 影・ブラー・`backdrop-filter`・`mix-blend` の AABB 局所化。④ 差分描画の見積コストが全面描画を超える閾値判断の再調整 | showcase CPU `hover` **`< 1.5 ms`**、`inherited` `< 4 ms`（達成: `2.62`） |
| **R5** | 🥉 中（条件付き） | **項目単位の保持 GPU 記録（T3）** | 2,000 行 `hover` は GL 送出 `0.77` ms（22%）で律速でないが、**`child` `6.2` ms（44%）・`inherited` `5.2` ms（33%）・`fontsize` `5.4` ms（28%）は 30% 前後以上**。R1〜R3 後に再判定（`child` はすでに条件を満たす） | 現行設計（`MemorySegment.copy` 一括転送＋容量 4,096 撤廃）を温存。着手判定は R0 の段別で **GL 送出 > フレームの 30%** | 着手時: 2,000 行 `text` GL `< 0.8 ms` |
| **R6** | 🥉 中 | **オープン・コールド JVM（W7 / T6）** | showcase `open` `5.5` ms（Chrome `5.0`）、500 行 GL `6.2` ms、2,000 行は未再計測 | DOM 雛形クローン・`sealNodeIds` 統合・`EnderEngine.prewarm()`。**コールド JVM 初回フレームを計測に追加** | 初回フレーム p99 を台帳化、2,000 行 `open` `< 20 ms` |
| **R7** | 🔒 横串（一部完了・§8.6.12 / §8.6.14） | **退行防止ラチェットの拡張** | 今回の改善（パッチ命中・確保量）は時間でなくカウンタで固定できる（時間ベンチは全件実行で不安定） | `AllocationRatchetTest` に 500 行 `hover`/`text`/`child` の確保上限、`UiView` の **パッチ命中率ラチェット**（`hover` 往復で slice-patch 100%・`child` 100%）、全再構築との一致ランダム列テスト（`DisplayListSlicePatchTest` を style/hover へ拡張） | 追加テスト全件グリーン、`:core:test` 全件グリーン |

**実施順序**: R0（完了）→ R1（完了）→ R2（部分達成。時間 KPI は Wave 1.7 で再判定）→ R3（Wave 1.7 の施策 2・3 として最優先）→ R4 → R6 → R5（条件付き）。R7 は各段に同伴。各段の完了ごとに §6.7 形式で再計測し台帳を更新する。**P2（3D）・P3（IME 等）・P4（DX）は性能 KPI（R1〜R4）が Chrome 比 2 倍以内に入るまで着手しない**（ただし IME は日本語 UI の必須要件のため R3 と独立に並行着手可）。

**2026-11-12 再計測（§9）による順序の更新**: R3 の前段として、**P1（`canPatchBoxes`/`offscreen` の判定キャッシュ）→ P2（`placeLine`/`InlineFragment` 再利用）** を最優先で実施する。P1 は varied `inherited`、P2 は varied `child` の最大要因であり、どちらも R3/R2 の KPI に直結する。

#### 🔄 Wave 1.7（2026-11-11 方針転換）：DoD 再設計・内容アドレス化・RTI → 設計は §8

R2 の未達（§6.10）を受け、局所最適化では `display-list` 約 9 ms が下限と確定した。律速は構造（項目が絶対座標を焼き込み、全項目を同一性で比較する）にある。方針は ① 構造クラスの計測（M0）→ ② テキスト成形の幅非依存キャッシュ＋改行区間キャッシュ → ③ `ComputedStyle` の共有 → ④ 反復ブロックのインスタンス化（RTI）→ ⑤ 座標を持たない Paint 表・Box 表 → ⑥ 永続データ構造・並列化。**R3（全面無効化・幅変更・可視範囲限定）は ②③ と実質同一の作業として進める。** 詳細・見積もり・限界・リスクは §8 を参照。

**下記 P0〜P4 の記述は、実装済み事項の記録と各設計メモとして残す。優先順位と KPI は上表 R0〜R7 が優先する。**

---

#### 🔥 P0：GL 描画パイプラインと DisplayList のスケール課題（5,000〜20,000 項目での 144Hz 維持 — 戦略 T1〜T4）

§6.5 の横断計測（W1〜W4・W6）で、500 行（5 千項目）で Chrome の 4.5〜9 倍、2,000 行（2 万項目）で 10〜24 倍まで開くことが確定した。以下は実装済みの要約と、残課題である。

**実装済み（詳細はコードとテストを参照）**

| 項目 | 内容 | 効果・備考 |
| :--- | :--- | :--- |
| 可視範囲カリング（T1） | ペインタ段の項目カリング（`outsideClip`）、スクロール層記録時カリング、`PaintSceneDriver` の 64 項目ブロック走査除外（`NONE` 計画の CPU バックエンドにも適用） | CPU `hover` `3.18 → 1.41 ms`、`scrollhover`（GL）フレーム p50 約 −50%。画素一致テスト（`OffscreenItemCullPixelTest`・`PaintSceneDriverRangeCullTest`）で固定。**未着手: `SCROLL_NODE` 区間の二分探索（表示リストが鋸歯状で単調性が無く、設計見直しが必要）** |
| CPU Tile Dirty Bitset ＋ Scroll Blit（T4） | `CpuRaster.scrollBlit`、露出帯のみの再描画、`CHILD_PATCH` 時の 64-bit 鍵による旧新項目対応 | large-list 500 行 CPU `hover` 差分描画が成立。`ScrollBlitTest`・`CpuScrollBlitPixelTest` で全画素一致 |
| 増分索引（T2） | `patchIndexByDiff` の上限撤廃（構造不変なら 25% 超でもインプレース）、`patchIndexByShift`（head/tail 同一性が前提） | **`child` では後続項目が再生成され同一性を失うため `patchIndexByShift` が成立せず、全面作り直しに落ちる（§6.10）** |
| 64-bit 整数鍵化（T2） | `(nodeHandle << 16) \| partOrdinal`。テキスト断片・装飾・主要部品・スクロールバー・親子鍵（`parentKey`）まで | 索引引きの文字列確保を排除。ただし `rebuildIndex` に文字列鍵の補助索引（`AtomTable.intern`）が残り、全面作り直し時に `paint(cpu)` の約 25% を占める |
| Segmented DisplayList（T2） | `LayoutBox` が `[dlStart, dlEnd)` を保持、`patchBoxes` / `patchChildSlices` で局所差し替え | R1 で `hover` 2,000 行 `display-list` `2.45 → 0.018 ms`。`child` は §6.10 のとおり未達 |

**残課題**

2. **項目単位の保持 GPU 記録（T3）**: 1 要素のテキスト変更でも、未変更の字形に毎フレーム `drawGlyphs`（フレームの 39%）が走る。項目ごとの 80B GPU インスタンス列を保持し、同一なら `MemorySegment.copy` で一括転送する。着手判定は GL 送出がフレームの 30% 超（R5）。
5. **GPU/CPU 走査向きの反転と非等方角丸・せん断の GPU シェーダー化**: `GlRectStream.decompose` が毎フレーム `Matrix2D` を `atan2`/`hypot` で逆分解し、せん断・非等方スケール下の角丸を CPU フォールバックへ回している。`PropertyTrees` の Transform ノードに 2D アフィンを保持して `decompose` を廃止し、`GpuRectShader` で直接評価する。

---


#### ⚡ 優先度 P1：レイアウト二段制約キャッシュ（Measure 多枠 + Place スキップ）と局所パッチ拡大（戦略 T5 / T6）

1. **`BoxMeasurer.computeSize` の純粋関数化・4-Way 測定キャッシュ ＋ `place` 制約スキップ（W5 — 戦略 T5）**:
   - **現状**: §6.2 で特定したとおり、Flex レイアウト等で同一ボックスが「仮幅 A → 確定幅 B」の 2 通りで交互に測定されると、1 スロットキャッシュが毎回上書きされて 279 回の重複測定が発生している。さらに `showcase` の幅 ±1px 変更で `0.931 ms`（Chrome `0.0835 ms` の **11.1 倍**）かかるのは、親から渡る実効幅が変わっていない固定幅カードや内部ボックスまで `place` と測定をやり直しているためである。
   - **対策**:
     1. `computeSize` が参照する親依存パラメータ（`parentInnerWidth()` 等）をすべて [`ConstraintSpace`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/model/ConstraintSpace.java)（および測定キャッシュ鍵）に明示化して純粋関数にし、ボックスごとに 2〜4 スロットの測定キャッシュ（Intrinsic / Flex 仮測定 / 確定サイズ）を持たせる。
     2. **配置（`place`）パスにも Blink LayoutNG 型の制約キャッシュ**を導入し、ボックスが `!layoutDirty` かつ親から渡された利用可能幅・高さが前回 `place` 時と同一なら、子孫の `place` 再帰を丸ごとスキップして親の移動量 $(\Delta x, \Delta y)$ のみを反映する。
   - **KPI**: showcase 幅変更 **`< 0.10〜0.20 ms`**（現 `0.931 ms`、Chrome `0.084 ms` に並び Swing `0.47 ms`・JavaFX `1.02 ms` を上回る）、`fullLayoutWarm` **`< 0.50 ms`**。
2. **局所更新ファストパス（`TEXT_PATCH` / `CHILD_PATCH`）の適用範囲拡大**:
   - **現状**:
     - `TEXT_PATCH`: ✅ **`button` / `label` は 2026-11-10 に開放済み**（[`UiView.isPatchableParent`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/UiView.java) の除外から外した）。検証: [`TextPatchTest.buttonAndLabelTextPatchMatchesFullRelayout`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/common/TextPatchTest.java)（Flex 行内の可変幅／固定幅ボタン・インラインラベルの折り返し変化・`updateTextContent`・複数同時を、全体再レイアウトと全項目一致で比較し、局所パッチ経路のヒット ≥ 6 を確認）、`:core:test` 全 1,847 件グリーン。**残り**: `td` / `th`（表レイアウトは `collectTextBoxes` が表内を退かせる）、`summary` / `legend` / `pre` は未開放。
     - `CHILD_PATCH`: `large-list` では 100% ヒットするが、`showcase` では **1,400 回中 0 回ヒット（100% フォールバック）** している。
   - **対策**: 固定幅または親寸法に影響しない `button` / `label` / `td` 内テキスト更新へ `TEXT_PATCH` を開放し、`CHILD_PATCH` の不成立理由カウンタを追加して Flex / Grid コンテナへの子要素追加・削除にも対応させる。
3. ✅ **【完了 2026-11-10・正しさ】文書サイズ上限の黙った切り捨ての解消（W8）**:
   - `DocumentLoader.parseDocument` から 128 KiB（HTML）・64 KiB（外部 CSS）の `substring` を撤去し、上限は `ParseLimits`（トークン数・深さ）に一本化。回帰テスト [`DocumentSizeNoTruncationTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/common/DocumentSizeNoTruncationTest.java)（5,000 行・20,000 行を欠落なく開く／64 KiB 超 CSS の末尾規則が残る）を追加、`:core:test` 全体グリーン。
4. **画面オープン（`open`）のさらなる高速化と JIT プリウォーム（W7 — 戦略 T6）**:
   - **現状**: 実測で showcase `6.2 ms`（Chrome `5.0 ms`）、2,000 行 `25.5 ms`（Chrome `19.8 ms`＝1.3 倍遅い）。コールド JVM の初回フレームは未計測。
   - **対策**: `StyleSheet` 雛形キャッシュ（S5）に続き、① HTML テンプレートの事前解析 `DocumentArena` 雛形クローン化（`MemorySegment.copy`）、② `sealNodeIds`（large-list で 6.5%）のパース時 1 パス統合、③ UA/共通スタイル解決結果の文書横断 `ShareCache` 共有、④ `EnderEngine.prewarm()` によるバックグラウンド非同期 JIT 暖機を導入する。

---

#### 🌌 優先度 P2：Phase C — Three.js 型 3D シーングラフのエンジン統合（戦略 T7-③）

- ブラウザ (Three.js + DOM) でも JavaFX でも不可能な**「同一レンダーパス・ゼロコピーで HTML/CSS 要素と 3D メッシュ・ライト・カメラ・レイキャストが双方向に融合する空間 UI エンジン」**を実現する。
- 詳細設計・メモリレイアウト・5 マイルストーン（`Scene3DArena`, `GlScene3DRenderer`, `SoftwareScene3DRasterizer`, `InstancedBatcher`, `GltfBinaryLoader`, `Raycaster3D`, `HTMLMesh`）は [`ENGINE_3D_ARCHITECTURE_PLAN.md`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/ENGINE_3D_ARCHITECTURE_PLAN.md) に定義済み。

---

#### 🌍 優先度 P3：実践 UI 仕様ギャップの解消（IME / 最新 CSS レイアウト / 自動仮想化 / SVG・Canvas — W9 & 戦略 T7-①）

1. **IME（かな漢字変換・インライン Preedit Composition）対応**:
   - [`TextEditing.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/TextEditing.java) と [`WebViewSurface`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/api/WebViewSurface.java) に IME 未確定文字列（Preedit 文節下線描画、`compositionstart` / `compositionupdate` / `compositionend` イベント、変換候補ウィンドウ座標 `getImeCursorRect()`）を追加し、日本語・多言語デスクトップアプリで JavaFX / Swing を完全に代替可能にする。
2. **CSS Top Layer（`<dialog>`, `popover`）& CSS Anchor Positioning**:
   - 現在 [`SelectPopup`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/SelectPopup.java) 専用になっているポップアップ機構を一般化し、親の `overflow: hidden` を突き抜けてターゲット要素に自動追従・画面端反転する `popover` / `<dialog>` / `anchor()` を実装する。
3. **CSS `content-visibility: auto` + `FenwickVirtualList` による 10 万件自動仮想スクロール（戦略 T7-①）**:
   - テスト層で検証済みの [`FenwickVirtualList`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/layout/FenwickVirtualList.java)（$O(\log N)$ 累積高さ索引）を [`BlockLayoutEngine`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/BlockLayoutEngine.java) の `content-visibility: auto` に接続し、20,000〜100,000 行の DOM でもビューポート内の数十件だけをスタイル・レイアウト・描画する（Chrome が 20,000 行 `fontsize` で `117 ms` かかる処理を **`< 0.5 ms`** に抑える）。
4. **CSS Grid `subgrid`・縦書き（`writing-mode: vertical-rl`）・インライン跨ぎ Bidi・カラー絵文字（`COLR`/`CPAL`）**:
   - [`GridLayoutEngine`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/GridLayoutEngine.java) の `subgrid` 対応、和風 UI・ノベルゲーム向けの縦書き対応、[`GsubTable`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/text/font/GsubTable.java) の文脈依存置換とカラー絵文字対応。
5. **`<svg>` へのページ CSS カスケード接続と `<canvas>` 機能拡充**:
   - [`SvgRenderer`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/raster/svg/SvgRenderer.java) に [`StyleResolver`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/StyleResolver.java) を接続して `var(--color)` や `:hover` を SVG 内部パスに効かせ、[`CanvasRenderingContext2D`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/raster/CanvasRenderingContext2D.java) の変形テキスト・`strokeText` を補完する。

---

#### 🛠️ 優先度 P4：開発体験（DX）の革新（型安全リアクティブ & 組み込み `EnderDevTools` — 戦略 T7-②）

1. **事前コンパイル済み型安全 `Signal<T>` / Java 21 `Record` バインディング（戦略 T7-②）**:
   - [`Bindings.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/Bindings.java) の `{{...}}` 正規表現走査をテンプレート読み込み時の 1 回限りの AST コンパイルに置き換え、プリミティブ `IntSignal` / `FloatSignal` や Java `Record` を文字列変換なしで [`BindingPatchTable`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/BindingPatchTable.java) に直結する。
2. **スコープ付きコンポーネント（`@scope` + `<slot>`）**:
   - [`CustomTagExpander`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/CustomTagExpander.java) を拡張し、名前付き `<slot>` と `@scope` スタイル隔離を持つ再利用可能な Web Components ライクなコンポーネント機構を提供する。
3. **`F12` 組み込みインスペクタ「EnderDevTools」**:
   - [`StageProfiler`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/diag/StageProfiler.java)・[`PipelineStats`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/diag/PipelineStats.java)・[`HitTestIndex`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/HitTestIndex.java) を活用し、エンジン自身の UI オーバーレイとしてボックスモデル可視化・DOM/CSS ライブ編集・フレームタイム内訳グラフ・ホットリロードを標準提供する。

---

### 7.4 統合ロードマップ総覧

| フェーズ | 状態 | テーマ | 到達指標（KPI — Chrome 157 / Firefox 159 / Swing / JavaFX 比較） |
| :--- | :---: | :--- | :--- |
| **Milestone 1〜5** | ✅ **完了** | オフヒープ DOM・状態オートマトン・増分レイアウト・GPU Uber-Shader・タイル並列 CPU ラスタ | `:hover` **`0.0069 ms`（Chrome の 49 倍高速）**、`scroll` `0.002 ms / 0 B` |
| **Wave 0 & Wave 1** | ✅ **完了** | 公開 API (`EnderEngine`/`WebViewSurface`)・脱 `mc-*` SPI・脱 `static` (`EngineContext`)・`CssPropertyIndex` & `CssValue[]`・`LoadedFace` 並列化・GL 差分走査・測定半減 | `fullLayoutWarm` **`0.986 ms`**、`showcase` 規模で Chrome 同等以上、単体汎用 JAR として完全自立 |
| **Wave 1 基盤: 7 層参照モデルラチェット（§1.3）** | ✅ **完了（2026-11-10）** | [`LayerDependencyRatchetTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/LayerDependencyRatchetTest.java) で L1〜L6 の単方向性を固定。逆方向参照 11 系統を全て解消し `KNOWN_UPWARD` を空集合化（SPI 3 本 `BindingHooks`/`ColorSyntax`＋移設 `media.ImageSources`/`paint.DisplayItemFonts`） | 完了。以後は新規逆方向参照がビルドで即失敗するため、Wave 1.5 以降の機能追加はこの層規則の内側で行う（新規 SPI を足す場合も下位層が定義し上位層が実装する） |
| **Wave 1.5 (P0 & P1: 戦略 T1〜T6)** | 🎯 **進行中（①・②a・③・④ 完了。2026-11-11 再計測で効果確認・§6.7。残りは Wave 1.6 の R0〜R7 へ）** | **① ✅ 文書上限 128 KiB の黙った切り捨て解消（W8）<br/>② 🔧 空間カリング（T1: ペインタ段 `outsideClip`・ドライバ段 64 項目ブロック走査除外完了。次は `SCROLL_NODE` 区間カリング）+ 項目単位の保持 GPU 記録（T3）<br/>③ ✅ Segmented DisplayList スライス保持（P0-4）+ 64-bit 整数鍵化（P0-3 段階 1〜4 完遂）+ 増分索引シフト（P0-1）（T2: W2・W4・W6 解消）<br/>④ ✅ CPU Tile Dirty Bitset + Scroll Blit（P0-0b / T4: W3 解消）<br/>⑤ 4-Way 測定キャッシュ + `place` 制約スキップ（T5: W5 解消）<br/>⑥ DOM 雛形クローン + 文書横断 `ShareCache`（T6: W7 解消）** | ・2,000 行画面外 `hover` GL **`< 0.25〜0.7 ms`**（現 `13.1`、Chrome `0.55` を凌駕）<br/>・2,000 行 `child` **`< 0.5〜1.5 ms`**（現 `25.6`、Chrome `1.09` を凌駕）<br/>・CPU `hover` **`< 0.3〜0.8 ms`**（現 `4.4`、Chrome CPU `1.55`・Swing `0.64` を逆転）<br/>・showcase 幅変更 **`< 0.10〜0.20 ms`**（現 `0.93`、Chrome `0.084` 同等）<br/>・フレーム確保量 `fontsize` 級で `< 5 MB`（現 `33 MB`） |
| **Wave 1.6 (R0〜R7: 2026-11-11 再策定)** | 🎯 **R0・R1 完了、R2 部分達成（§6.10）、R3 は Wave 1.7 の施策 2・3 として進行** | ✅ R0 計測基盤補強（§6.8）→ ✅ R1 スタイル変更のスライスパッチ化（§6.9）→ 🔧 R2 `child`（命中 100%・確保削減まで。時間 KPI 未達 → Wave 1.7 の RTI・Paint 表で再判定）→ R3 全面無効化・幅変更・可視範囲限定 → R4 CPU 全面ダメージ解消 → R6 オープン → R5 GPU 記録（条件付き）／R7 ラチェット横串（§7.3 冒頭） | ・2,000 行 GL `hover` **`< 1.0 ms`**（✅ 達成）<br/>・2,000 行 GL `child` **`< 3 ms`**（現 `約 18`。未達）<br/>・500 行 `inherited` GL **`< 1.5 ms`**（現 `3.59`）<br/>・2,000 行 `fontsize` GL **`< 10 ms`**（現 `18.9`、Chrome `11.3`）<br/>・showcase CPU `hover` **`< 1.5 ms`**（現 `4.59`）／幅変更 **`< 0.2 ms`**（現 `1.04`）<br/>・Chrome 比: 全シナリオ 2 倍以内（現 最大約 17 倍） |
| **Wave 1.7 (2026-11-11 方針転換)** | 🎯 **着手前（M0 計測から）** | DoD 再設計（座標を持たない Paint 表）＋内容アドレス化（成形・スタイル・測定・配置の共有）＋反復ブロックのインスタンス化（RTI）。§8 | ・2,000 行 `child` GL **`< 0.5 ms`**（Chrome `1.09` の約 2 倍速）<br/>・2,000 行 `fontsize` **`< 5 ms`**、確保 `< 2 MB`<br/>・10 万行で `hover`/`text`/スクロール `< 0.3 ms` |
| **Wave 2 (P2: 戦略 T7-③)** | 📋 **設計完了** | Three.js 型 3D シーングラフ統合（[`ENGINE_3D_ARCHITECTURE_PLAN.md`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/ENGINE_3D_ARCHITECTURE_PLAN.md)） | HTML/CSS と 3D メッシュ・ライト・レイキャストの単一パス・ゼロコピー融合（ブラウザ Three.js では不可能な領域） |
| **Wave 3 (P3: 戦略 T7-①)** | 📋 **計画中** | IME Composition・Top Layer (`<dialog>`/`popover`) & Anchor Positioning・`content-visibility: auto` + `FenwickVirtualList` 10 万行仮想化・`subgrid`・縦書き | 20,000〜100,000 行リスト **`< 0.2〜0.5 ms`**（Chrome 20,000 行 `117 ms` の **200 倍以上高速**）、日本語デスクトップ UI 完全対応 |
| **Wave 4 (P4: 戦略 T7-②)** | 📋 **計画中** | コンパイル済み型安全 `Signal<T>` / `Record` 直結バインディング・`<slot>` コンポーネント・`F12` 組み込み `EnderDevTools` | 開発生産性と実行性能の双方で Web フロントエンド（React/CEF）および既存 Java UI ライブラリを凌駕 |

---

## 8. Wave 1.7 設計: DoD 再設計・内容アドレス化・反復ブロックのインスタンス化（2026-11-11）

> R2（§6.10）の未達を受けた根本的な作り直しの設計。**数値はすべて見積もり（未実装・未検証）。** 実測済みなのは §8.1 のみ。

### 8.1 診断（実測で確定した事実）

**現行表現の構造的欠陥**

| # | 欠陥 | 帰結 |
| :-- | :-- | :-- |
| F1 | 幾何の二重保持（`LayoutBox.x/y/w/h`＝float、`DisplayItem.x/y/width/height`＝int、`BoxMemo.nums`） | 挿入 1 回で 3 か所を同期。同期コードが `patchChildSlices` の大半 |
| F2 | AoS の太い不変レコード（`DisplayItem` 23 フィールド、`String`×3・`Map`・`RenderMeta` 参照） | 走査はポインタ追い、移動は再生成 |
| F3 | 同一スタイルの重複（行ごとに同じ色・半径・影・クリップを複製） | 2,000 行で同値を 2,000 回持つ |
| F4 | 索引が項目の同一性比較に依存（`patchIndexByDiff` / `patchIndexByShift` / `SoftwareRenderBackend`） | 後続が再生成されると増分索引が成立せず全面作り直し |
| F5 | ホットパスの Map 引き・`equals`・文字列キー（`rebuildIndex` の `AtomTable.intern`） | `paint(cpu)` の約 25% |

**D0 ベンチ**（`DodHypothesisBench`、JMH。後続 17,500 項目相当）

| 項目 | 時間 |
| :-- | :-: |
| 現行: 17,500 項目を `new DisplayItem` で作り直し（確保 2 MB） | 458 µs |
| SoA: Box 表 y 列の範囲加算（3,500 要素）／項目ごと y 列（17,500 要素） | **0.09 µs** ／ 1.6 µs |
| 現行: 20,000 項目の AoS 走査（可視 200 件出力）／SoA 全走査／**SoA 可視のみ（二分探索）** | 58.7 µs ／ 19.8 µs ／ **0.21 µs** |

SoA 全走査は AoS の 3 倍止まり。**可視限定（O(可視)）がなければ SoA 化だけでは大差が出ない。** また孤立ベンチの再生成 0.46 ms に対し本番 `itemShift` は約 7 ms で、差はヒープ上に散在したオブジェクトの読み出し（メモリ律速）である（§6.10）。

**下流の内訳**（2,000 行 `child`・GL・JFR 付き）: `paint(cpu)` 4.9 ms のうち索引の全面作り直し 61%（約 3 ms、文字列キー `intern` 25%）、`isNeutral` / `peekState`（文字列キーの状態引き）20%、保持範囲の走査 8%。CPU バックエンドは `paint` 18.3 ms・確保 20 MB/frame。

### 8.2 設計原則と目標データモデル（DoD 再設計）

原則: ① 幾何は一か所（Box 表）にだけ置き、表示側は添字で引く（遅延束縛）。② 変更は列単位（`float[]` の範囲加算、`int[]` の 1 要素）。③ スタイル由来の値は重複排除した Payload 表。④ 作業量は O(可視＋変更)。⑤ 毎フレーム確保 0（事前確保・世代番号）。⑥ 差分は dirty ビットセット／範囲で伝え、比較走査をしない。

```text
Box 表（SoA, 文書順）    float[] x,y,w,h（小数のまま） / int[] parent,firstChild,nextSibling / int[] styleSlot / long[] dirtyBits
Payload 表              スタイル由来の描画値（bg/border/radius/opacity/shadow/clip…）。ComputedStyle 単位で intern、行は番号だけ共有
Paint 表（SoA, 描画順）  int[] boxIdx / byte[] kind / int[] payloadIdx / long[] key（現行 64-bit 鍵を継承）。Box ごとに [paintStart,paintEnd)
GlyphRun 表             (boxIdx, fontSlot, glyphStart, glyphCount)。同一文字列・フォントなら成形結果を共有、原点は描画時に Box から加算
```

- 文字列・`Map`・`RenderMeta`・`AnimationSpec` は表に入れず、添字参照の別アリーナへ。
- **GPU（GPU-Driven）**: インスタンスを 80 B → 16 B（`boxIdx, kind, payloadIdx, flags`）にし、頂点シェーダが Box/Payload SSBO を引く。行の移動は Box SSBO の連続範囲だけ、`:hover` は Payload 1 行の書き換え。
- **CPU**: Tile Dirty の入力を「項目差分」から「dirty Box の旧・新矩形」へ。タイルの対象 Paint 行は y 範囲の二分探索。
- **座標の丸め**: 行の高さが小数のとき、後続の `y` は「旧＋`dy`」にならず、小数のボックス座標から丸め直す必要がある（項目だけずらす近道は全再構築との一致を壊す）。**Paint 表は小数の相対値を保持し、丸めは描画の最終段 1 か所（現行規則どおり）で行う。**
- 代替案（RTI が使えないブロック向けの保険）: ビルダーが `DisplayListDelta{spliceAt, removedLen, addedLen, shiftFrom, dx, dy}` を発行し、後続は再生成せず `ShiftTable`（Fenwick）で座標を読み出し時に解決、索引は差分駆動にする。**ビルダー・索引・バックエンドを同時に切り替える必要がある**（同一性比較に頼る既存コードが遅延実体化を全面更新と誤判定するため）。

### 8.3 内容アドレス化（Content-Addressed Pipeline）

各段の結果を入力の内容ハッシュで保存し、同じ入力なら再計算せず共有する。**コストは N（ノード数）ではなく D（異なる計算の数）に比例する。** 対応する他分野の技術: 内容アドレス型ビルドキャッシュ（Bazel / Nix）、hash-consing（等価判定を O(1) に）、増分計算（Salsa / Adapton）、永続データ構造（HAMT / rope / B 木）、GPU インスタンス描画、コンパイル型 UI（Svelte / Solid ＝ `BindingPatchTable`）。

| 段 | キー | 共有されるもの | 効く場面 |
| :-- | :-- | :-- | :-- |
| スタイル | (祖先の算出スタイル ID, セレクタ一致集合) | `ComputedStyle`（intern） | 継承変更（`fontsize` / `inherited`）、オープン |
| テキスト成形 | (文字列, フォント, 言語) → グリフ列・前進幅・**改行可能位置** | 成形結果（幅に依存しない） | 幅変更で再成形せず、改行は「幅 → 行分割」の**区間キャッシュ**（行数が同じ幅区間は同じ分割） |
| 測定 (intrinsic) | (部分木の構造キー, 制約) | min/max-content | 同形の部分木を別の場所でも共有（T5 の拡張） |
| 配置 | (部分木の構造キー, 利用可能幅) → **相対座標** | 部分木内レイアウト | 親が動いても子を更新しない |
| ペイント | (部分木の構造キー, 算出値) | テンプレート（相対座標・小数のまま） | カード・ボタン等の部品単位 |
| 字形 | (グリフ ID, フォント, サイズ) | アトラス位置 | 既存 `FontSet` アトラス |

**構造クラス判定**: `shape(node) = H(tag, 算出スタイルの正規化 ID, 描画に効く属性, [shape(child)…])`（文字の内容は含めない＝「穴」）。DOM 変更で変わったノードから祖先へ増分更新（O(深さ)）。`:nth-child` 等で縞模様になる場合は算出スタイルが違うので別クラスになる。

### 8.4 反復ブロックのインスタンス化（RTI）

同形の行（見た目が同じ）は 1 つのテンプレートを共有し、行 = (クラス ID, 位置の序数, 文字の参照) だけを持つ。位置は全行同高さなら `y0 + i×h`（Flutter の固定 extent と同じ O(1)）、高さが違えば Fenwick。描画は可視範囲（y の二分探索）の行だけ、テンプレート＋行の原点＋文字を送出（GPU はテンプレート 1 つ＋インスタンス属性）。1 行だけ `:hover`・インライン style になったら、その行だけ別クラス（変種）へ移す（コピーオンライト）。

| 操作（2,000 行・全行同一クラス） | 現状 | RTI（見込み） |
| :-- | :-: | :-: |
| `child` | 後続の再生成・再配置・索引再構築（GL 約 18 ms） | クラス判定＋配列挿入＋`i×h`。後続の項目・ボックスは存在しない。**0.2〜0.4 ms**（Chrome 1.09） |
| `fontsize` / `inherited` | 全行のスタイル解決・測定・配置（GL 18.9 ms、確保 25 MB） | クラスのスタイル 1 回の再解決＋テンプレートの再測定 1 回。**1〜3 ms**（Chrome 11.3） |
| `hover`（1 行） | 1.0 ms | 該当行をクラスから分離。O(1) |
| 幅変更 | 全行再測定 | 折り返しが起きるかの判定のみ（最大文字幅 ≤ 内容幅なら再測定ゼロ） |

**入れ子・長文**: 構造ハッシュは子の構造を含むので入れ子でも同クラスになる（一部だけ形が違う行は別クラス）。長文が折り返す行は高さが可変（Fenwick・O(log N)）で、折り返し計算は行の文字が変わるたびに必要（RTI では省けない）。効くのは挿入・削除・スタイル変更・全行にまたがる変更（成形結果をキャッシュ）。**適用外**（従来経路にフォールバック）: クラス数/行数が高いブロック、`float`・`position: absolute/sticky` が兄弟と相互作用、margin 相殺が行ごとに変わる場合。イベント・ヒットテスト・IME・選択は、行番号の逆引きと「必要になった行だけ実体化」で扱う。

### 8.5 Chrome 比の到達見込みと限界（正直に）

| シナリオ | 対 Chrome |
| :-- | :-: |
| 同形の行の挿入・削除・`hover`・全行スタイル変更 | **大幅に超える**（要素ごとにスタイル・レイアウト・ペイントを持つ Chrome は構造上届かない） |
| 不均一ページの局所更新（1 要素のテキスト・スタイル） | **超える**（既に `hover` 49 倍・継承 4.5 倍。下流の全走査排除で拡大） |
| 長文の折り返し・幅変更（全行再折り返し） | **超えうる**（成形を幅非依存・改行を区間キャッシュ。Chrome は幅変更で行ごとに再成形・再改行） |
| 全行の文字が毎フレーム変わる（ストリーミング） | 同程度〜わずかに下（成形が支配的。HarfBuzz(C++) 対 純 Java。並列化で緩和） |
| 初回のコールド描画（JIT 前）・全内容がユニーク | **劣る可能性が高い**（共有できる物が無い。`prewarm` / AOT で緩和） |
| 画像・フィルタ・影・ブラー重視 | Skia/GPU が有利（共有では減らせない。T3 の別軸） |

他のネックへの効き方: 後続再生成・索引全面作り直し（W2）、全面無効化（W6）、幅変更（W5）、`intern`（W4）、CPU バックエンド（W3 — 行ラスタ結果をテンプレート＋文字の内容キーでキャッシュして貼り付け）には効く。オープン（W7）は DOM 雛形クローン・テンプレート永続化で 2 回目以降のみ。GL ドライバ送出・画像デコード・フィルタは別手段が必要。

目標は「全面で超える」ではなく、**重複と不変が大半を占める現実の UI のほぼ全域で超え、最悪でも Chrome と同程度**。

### 8.6 実施計画（効果 ÷ 工数・各段で全再構築との一致テストを合否にする）

| 順 | 施策 | 内容 | 出口 KPI |
| :-: | :-- | :-- | :-- |
| 1 | **M0 計測**（読み取り専用） | 構造ハッシュで「クラス数/ノード数」と行高さの一様性を、長文・入れ子・不均一ページ（showcase 等）を含む実ドキュメントで測る | large-list は 1 クラス、showcase でも反復部分の 70% 以上がクラス化できるか |
| 2 | **テキスト成形の幅非依存キャッシュ＋改行区間キャッシュ** | 他と独立に導入でき全ページに効く（W5 の主因） | showcase 幅変更 `< 0.2 ms` |
| 3 | **`ComputedStyle` intern と (親スタイル, 一致集合) 共有** | S5（`StyleSheet` 雛形キャッシュ）の拡張 | 2,000 行 `fontsize` `< 10 ms`、500 行 `inherited` `< 1.5 ms` |
| 4 | **RTI M1**: 反復ブロックの検出と固定 extent レイアウト（`y_i = y0 + i×h`）、可視行のみ実体化 | 全再構築・差分レイアウトとの一致 | 2,000 行 `child` の layout＋display-list `< 1 ms` |
| 5 | **RTI M2/M3 ＋ Paint 表（D1〜D5）**: テンプレート＋インスタンス表、相対座標、Box 表の導入（`DisplayItem` は読み取り専用ビューで互換維持）、Fenwick、GPU インスタンス描画 | 旧経路 oracle との差分テスト（`DisplayListSlicePatchTest` 等を温存） | 2,000 行 `child` **`< 0.5 ms`**、`fontsize` `< 5 ms`、確保 `< 2 MB`、10 万行 `< 0.3 ms` |
| 6 | 永続データ構造化（行配列・表示リスト・索引） | 差分が「ポインタが違う部分木」だけになり、全項目の同一性比較が O(差分) | 不均一ページの構造変更 |
| 7 | クラス単位の並列化 | 成形・測定は独立。`ForkJoinPool` で並列（Chrome のレイアウトは主スレッド 1 本） | ユニーク内容・コールド |


#### 8.6.1 M0 の実測結果（2026-11-11・`ShapeClassMain`、読み取り専用。構造クラス = 算出スタイルと子の構造が同じ部分木、文字は無視）

| 文書 | ノード数 | 構造クラス数 | クラス/ノード | 反復列（同一構造が 3 つ以上連続）に含まれるノード | 備考 |
| :-- | :-: | :-: | :-: | :-: | :-- |
| large-list-2000 | 4,008 | 10 | **0.25%** | **99.8%**（1 列・高さ一様） | RTI が最大に効く典型 |
| nested-list（1,000 カード・入れ子・flex） | 9,007 | 14 | 0.16% | 99.9%（1 列・高さ一様） | 入れ子でも 1 クラスに畳める |
| long-text（1,000 段落・1〜5 倍長の文章） | 2,006 | 7 | 0.35% | 99.7%（1 列・**高さ不均一**） | 構造は同じでも位置は Fenwick が要る |
| striped（`:nth-child(odd)`） | 4,007 | 12 | 0.30% | **0%** | 構造は 2 クラスだが**交互**で、連続列の判定では検出できない。「クラス ID の列」として扱う必要がある |
| showcase（実際の多機能ページ） | 258 | 140 | **54%** | **15.5%**（3 列） | 反復は少ない |
| guidebook（Markdown 由来） | 247 | 70 | 28% | 17%（1 列・高さ不均一） | |

**判定**: リスト・カード一覧・テーブルのような「同形の繰り返し」では 99% 以上が数クラスに畳める（入れ子・長文でも成立）。一方、**実際の多機能ページ（showcase）では反復部分は 15% 程度**で、RTI だけでは効かない。したがって RTI は「リスト系で Chrome を大きく超える」手段であり、不均一ページには別の手段が要る。縞模様は連続列ではなく「クラス ID 列」で扱う設計に直す（§8.4）。

**JFR（2026-11-11・null バックエンド）が示した、施策 2 の前提への修正**:

| シナリオ | フレーム | 段別 |
| :-- | :-: | :-- |
| 2,000 行 `fontsize` | 20.2 ms（確保 25.6 MB） | restyle-local 3.0 ・ **layout 4.8（measure 3.3 + place 1.5）** ・ **display-list 9.6** ・ style+build 0.8 ・ box-tree 0.8 |
| 2,000 行 `inherited` | 9.6 ms（確保 19.8 MB） | restyle-local 3.0 ・ display-list 6.2 |
| showcase 幅変更（`resize`） | 1.41 ms | measure 0.50 ・ place 0.06 ・ display-list 0.59。サンプルは平坦（`ConcurrentHashMap.get`・`declaresContainers` 等）で、**`TextShaper` は支配的でない** |

→ **幅変更（W5）の主因はテキスト成形ではない**（成形は `TextShaper.collapseRuns` が数サンプル）。よって「成形の幅非依存キャッシュ」を W5 の主施策とする根拠は弱く、**施策 2 は長文ページの幅変更限定の効果として格下げ**する。`fontsize` / `inherited` は display-list（全面再構築）が最大で、ここは RTI または Paint 表でなければ半減できない。

#### 8.6.2 実装: 窓付きの表示リスト（Windowed DisplayList）— 2026-11-11

**狙い**: 項目数を可視範囲（画面＋余白 256px）に比例させ、後続再生成・索引全面作り直し・全面再構築のコストを「全体 N」から「窓」へ落とす。RTI（§8.4）の最初の段階として、**同形でないページにも効く**画面外の省略を、スクロール時だけでなく全ての描画に広げた。

**実装**（`DisplayListBuilder`）:
- 描画する箱が `WINDOW_MIN_OWNERS`（既定 800、`-Denderframe.paint.windowmin`）以上の文書は、画面と余白の外の箱の項目を作らない（既存のスクロール用カリング `offscreen()` を流用。`-Denderframe.paint.nowindow=true` で無効化）。省いた箱は `culledBoxes` に覚える。
- 窓付きのときは、部分木の保持（チャンク）を使わない（項目が欠けるため）。
- **画面外の箱への局所パッチ**（`text` / `style` / `hover`）: 項目を持たず今も画面外なら、表示リストに触れず成功扱い（`isCulledNoop`）。
- **子の増減**: `windowIntact` が、パッチ後も「項目を作った箱は全て窓の中、省いた箱は全て窓の外」を満たすかを先に確かめ、窓の出入りがあれば組み直しへ退く（全体を窓付きで組み直した結果と同じ並びを保つため）。追加された部分木も画面外なら項目を作らない。
- **既存の不具合の修正**: `match` が 2（箱が動いただけ）を返した箱は、記憶を動かさず項目だけ平行移動するため、記憶の位置と表示リストの項目の位置がずれる。差分パッチは「箱の今の位置 − 記憶の位置」で動きを見るので、**そのずれを二重に動かしていた**（窓付きの組み直しが頻繁になって初めて表に出た）。記憶に `appliedDx/appliedDy` を持たせ、パッチ側で足して見るようにした。

**検証**: `WindowedDisplayListTest`（① 局所パッチの結果が、毎回全体を窓付きで組み直した結果と 4 seed × 60 手順で完全一致、② 窓付きと窓なしの全画素が、スクロール・ホバー・挿入・削除・文字・スタイル変更 80 手順 × 3 seed で一致。行の高さは整数にしている。小数の高さではスクロール時の平行移動の丸めで ±1px ずれるが、これは窓なしの既存の挙動と同じ性質）、`:core:test` 全件グリーン。

**実測**（`FineProfileMain`、2,000 行 `large-list-2000`、GL・1,500 フレーム、平均 ms ／ Chrome 157）:

| シナリオ | 変更前 | 変更後 | Chrome | 判定 |
| :-- | :-: | :-: | :-: | :-: |
| `hover` | 1.06 | **0.26** | 0.55 | ✅ 超えた |
| `text` | 1.61 | **0.37** | 1.02 | ✅ 超えた |
| `style` | 1.04 | **0.24** | — | |
| `scroll` | 0.51 | **0.08** | — | |
| `fontsize` | 19.6 | **8.8** | 11.3 | ✅ 超えた |
| `inherited` | 15.9 | **4.9** | — | |
| `child` | 約 18 | **1.35**（§8.6.3 の追加後。窓付き直後は 2.25） | 1.09 | ❌ 約 1.2 倍（残りはレイアウト約 0.55 ms ＋ 測定 0.2 ms） |

500 行（`windowmin` 800 以下の閾値で窓付き）: GL `child` `2.99 → 0.61 ms`、`hover` `0.34 → 0.23 ms`。CPU バックエンド 2,000 行: `child` `38 → 3.3 ms`、`hover` `0.12 ms`、`text` `0.22 ms`、`fontsize` `10.5 ms`（確保 20 MB → 0.9 MB）。

**残り**: 2,000 行 `child` はレイアウトの後続 `y` 更新（約 1.4 ms）が律速。次は固定 extent（同形の行）の位置を `y0 + i×h` / Fenwick で持ち、後続を触らない（§8.6 施策 4）。`inherited` 4.9 ms は `restyle-local` 約 3 ms（継承の全面再スタイル）が律速で、スタイル共有（施策 3）の対象。

#### 8.6.3 続き: `child` の残りの削減（2026-11-11）

2,000 行 GL `child` を JFR で再分解（窓付き直後 2.25 ms）して潰した項目と、見送った項目。

| 項目 | 内容 | 効果 |
| :-- | :-- | :-: |
| `windowIntact` の高速化 | 窓の出入りの確認が全体の約 13% を占めていた。位置だけで決まる側（窓に掛かる箱は描く・掛からない箱は省く）を先に見て祖先の走査を避け、省いた箱の集合を `IdentityHashMap` から `ArrayList` に、文字の塊・匿名の箱は持ち主だけを数える | 2.25 → 約 1.75 ms |
| 後続の行の**平行移動**（`LayoutBox.layout`） | 原点が縦に整数ぶんだけ動き、幅が同じで、配置が前回と同じ純粋な通常フローの部分木は、置き直さず箱・断片・行の下端・前回の配置の記録ごと平行移動する（`shiftSubtree`。文字の塊の箱は位置を持たないので動かさない）。`-Denderframe.layout.noshift=true` で無効化 | 行の高さが整数の文書で効く。小数の高さ（既定のベンチ）では適用されない |
| `ScrollState` の未スクロール時の短絡 | レイアウトが箱ごとに 2 回引く同期付き弱参照の表を、一度もスクロールされていなければ見ない | 小（約 0.02 ms） |
| 合計 | | **2.25 → 約 1.35 ms**（Chrome 1.09） |

**平行移動を縦・整数ぶんに限った理由（実測で確定）**: 小数の移動量や横移動を許すと、`WidthOnlyReuseTest`・`TableIncrementalLayoutTest` が置き直した結果と一致しなくなった。置き直しは `((原点 + a) + b) + c` の順に浮動小数を足すが、平行移動は `(原点 + a + b + c) + dy` となり、**結合順が違うので 1 ulp ずれ、丸めの境目（x.5）で 1 画素変わる**ことがある。整数ぶん（かつ横が同じ）に限れば、これまでのテスト（全再構築との完全一致）をすべて満たす。**小数の高さの行（既定のベンチ）では、各行を置き直すコスト（約 0.55 ms）が残る。**

**残り（`child` 1.35 → Chrome 1.09 未満）**: ① 小数の高さの後続行の置き直し（約 0.55 ms）— 窓の外の行のレイアウトを遅延する（`deferScrollLayout` と同じ「読む側が現れたときに済ませる」仕組みの拡張）か、置き直しの浮動小数の順序を保つ高速化が要る。② 測定の `children` 全走査（約 0.2 ms）。③ `inherited` / `fontsize` は `restyle-local` が約 3 ms（`ComputedStyle.diff` と `StyleMap.equals` が支配的）— スタイルの共有（施策 3）。

#### 8.6.4 デザインが行ごとに違う場合の Chrome 比較（`varied-list-2000`、2026-11-11）

**文書**: `Workloads.variedList`。2,000 行（DOM 3,457 要素）。全行がインラインの `style` だけでデザインを決め（クラスで共有しない）、行番号から余白・背景・左右の枠・文字サイズ（% で相対）・文章の長さ（1〜6 倍、折り返す行を含む）・入れ子（バッジ・太字・サブ行）を変える。`:core` と Chrome は**同じ HTML ファイル**を読む（`Workloads` の `main` で書き出し、`trace.js` が `VARIED_FILE` で読む）。`large-list` と同じ #scroller（高さ 400px）内の `ul`。Chrome 157 headless・GPU 合成・全 CPU（main + raster + draw）、`:core` は GL バックエンド・平均 ms。

| シナリオ | `:core` GL | Chrome 全 CPU | 比 |
| :-- | :-: | :-: | :-: |
| `hover` | **0.31** | 0.81 | ✅ 2.6 倍速い |
| `style` | **0.33** | 0.80 | ✅ 2.4 倍速い |
| `text` | **0.68** | 2.16 | ✅ 3.2 倍速い |
| `scroll` | **0.10** | 0.55 | ✅ 5.5 倍速い |
| `child`（250 行目へ挿入・削除） | 5.45 | **3.67** | ❌ 1.5 倍遅い |
| `inherited`（body の color） | 11.8 | **3.55** | ❌ 3.3 倍遅い |
| `fontsize`（body の font-size） | 49.5（確保 70 MB） | **42.1** | ❌ 1.2 倍遅い |

**同形の `large-list-2000` の Chrome 値の訂正**: 従来の Chrome `child` `1.09 ms` は `trace.js` が**末尾へ追加・削除**する方法で、`:core`（250 行目への挿入・削除で後続 1,750 行が動く）と条件が違った。250 行目へ挿入する `child250` で測り直すと Chrome は **`1.98 ms`**。`:core` の `1.35 ms` は**同条件で Chrome を超えている**。varied の `child` は `child250` 同士（Chrome 3.67）で比べた。

**`:core` の段別内訳（varied・GL）と原因**:

| シナリオ | 内訳 | 原因 |
| :-- | :-- | :-- |
| `child` 5.45 | **layout.place 3.69** ・ measure 0.37 ・ display-list 0.28 | 行ごとに高さ・折り返しが違い、後続 1,750 行すべての置き直し（`placeLine`・小数の座標）が要る。平行移動の近道（縦・整数ぶん・同一幅）が効かない |
| `inherited` 11.8 | **restyle-local 7.5** ・ display-list 4.2（`canPatchBoxes` が理由 5 で退く） | 全行のスタイル再解決。変更ボックスにスクロールバーを持つ #scroller が含まれ、パッチが成立せず窓付きの全面再構築に落ちる |
| `fontsize` 49.5 | **layout.measure 26.0** ・ place 5.1 ・ restyle 7.6 ・ style+build 2.2 ・ box-tree 2.2 ・ display-list 3.8 | 全行の文章（折り返す長文を含む）を測り直す。成形・改行が支配的で、同形リストでは隠れていた |

**結論**: ① 局所更新（`hover`・`style`・`text`・`scroll`）は、デザインが違っても Chrome を 2.4〜5.5 倍上回る（窓付き表示リストは行の同形性に依らない）。② **全面に及ぶ変更（`inherited`・`fontsize`）と、後続が動く `child` は、デザインが違うと Chrome に劣る**。これらは共有できる部分が少ないため、RTI（§8.4）の適用外であり、**スタイル再解決そのものの高速化（`ComputedStyle` の共有・差分判定の短絡）と、テキスト成形・改行のキャッシュ（施策 2）、窓の外の行のレイアウトの遅延**が要る。§8.6.2 の「施策 2 は格下げ」は、長文を含むこの文書の `fontsize`（measure 26 ms）で**撤回**する（短い 1 行テキストの同形リストでのみ格下げだった）。

#### 8.6.5 メモリの比較（Chrome 157 レンダラ vs `:core`、2026-11-11）

**測り方**: Chrome は `mem.js`（`SystemInfo.getProcessInfo` でレンダラを列挙し、空ページから文書へ遷移したときに Private バイトが最も増えたプロセスを選び、PowerShell の `Get-Process` で Private / WorkingSet を読む。GC 後・3.5 秒待ち・各 4 回）。`:core` は `MemFootprintMain`（文書を開いて 5 フレーム描いたあと、GC 後のヒープ使用量の差。プロセス全体の Private 差も併記）。同じ HTML。

| 文書 | Chrome レンダラ Private（空ページ 約 28.5 MB からの増分） | Chrome DOM ノード / レイアウトオブジェクト | `:core` ヒープ保持 | `:core` プロセス Private 増分 |
| :-- | :-: | :-: | :-: | :-: |
| large-list-500 | ≈ 0〜+1（ばらつき内） | 1,012 / 1,008 | **2.7 MB** | 約 7（ばらつき大） |
| large-list-2000 | **+5**（約 33.7、外れ値 1 件除く） | 4,012 / 4,008 | **9.3 MB** | 測れず（ヒープの伸縮でばらつく） |
| varied-list-2000 | **+26**（約 55） | 7,080 / 7,361 | **60.2 MB** | 約 77 |

**読み方（注意つき）**:
- `:core` は文書あたり Chrome の **約 2〜2.3 倍**のメモリを持つ（同形 2,000 行で 9.3 vs +5 MB、デザイン違いで 60 vs +26 MB）。Chrome は同じ見た目の要素がスタイルを共有し、レイアウトオブジェクトも軽い。`:core` は全ノードに `ComputedStyle`・箱・記憶（`BoxMemo`）・成形済みの行を持ち、窓付き表示リストで減らしたのは表示項目だけ。
- 1 要素あたり: Chrome 約 6〜7 KB/ノード（デザイン違い。ノードには文字ノードを含む）に対し、`:core` は同形で約 2.4 KB/要素・デザイン違いで約 17 KB/要素。**同形では `:core` の方が小さい要素もあるが、スタイルが行ごとに違うと逆転する**（共有できない `ComputedStyle` が支配的と推定。要確認）。
- 比べられないもの: Chrome の値は**レンダラ 1 プロセスの Private**（ブラウザ・GPU・ユーティリティの各プロセスは含まない。合計では数百 MB）。`:core` はヒープ（JVM 自体のメタスペース・ネイティブは含まない）とプロセス Private の増分で、後者はヒープの伸縮でばらつく。`:core` のオフヒープ DOM（`DocumentArena`）の内訳は未取得。
- Chrome の空ページ基準（約 28.5 MB）は、`:core` では JVM・フォント・アトラスの土台に相当するが、`:core` 側の土台の Private は安定して測れていない。

#### 8.6.6 `ComputedStyle` の共有（hash-consing）と保持量の内訳（2026-11-11）

**実装**（`ComputedStyle.ofOwned` → `share`）: 宣言の値は文字列ごとに 1 つの正規のオブジェクト（`CssValue`）なので、内容が同じ（全プロパティの値の参照・フォントサイズ・根のフォントサイズ・`extras` が同じ）かは参照の比較で決まる。算出した直後に、内容のハッシュで弱参照の表（`ConcurrentHashMap<Interned, Interned>`）を引き、同じ内容の既存インスタンスがあればそれに置き換える。表は弱参照で、どの要素からも使われなくなったスタイルは回収される。`-Denderframe.css.noshare=true` で無効化。`:core:test` 全件グリーン。

**効果**:

| 項目 | 共有前 | 共有後 |
| :-- | :-: | :-: |
| varied-list-2000 のヒープ保持 | 60.2 MB | **51.8 MB**（−14%） |
| large-list-2000 のヒープ保持 | 9.3 MB | 9.3 MB（変化なし） |
| varied `inherited` GL | 11.8 ms | **9.6 ms** |
| varied `fontsize` GL | 49.5 ms | **43.8 ms**（Chrome 42.1） |
| varied `child` GL | 5.45 ms | **4.9 ms**（Chrome 3.67） |
| large `inherited` / `fontsize` / `child` GL | 5.1 / 8.8 / 1.35 ms | **3.8 / 6.6 / 1.21 ms** |

共有の命中（`MemFootprintMain`）: large-list は作成が 20 回しかなく（命中 11・新規 9。同形の行は**もともと別の経路でスタイルを共有していた**ため、効果が出ない）、varied は 5,939 回の作成のうち命中 3,441・新規 2,498（58%）。

**保持量の内訳**（`MemFootprintMain -Dmem.histo=1`、varied-list-2000・共有後、上位）:

| クラス | インスタンス | バイト | 内容 |
| :-- | :-: | :-: | :-- |
| `byte[]` + `String` | 240k + 240k | **10.2 + 5.8 MB** | 単語ごとの部分文字列（断片の文字） |
| `LayoutBox$InlineFragment` | **171,596** | **9.6 MB** | 単語（折り返しの単位）ごとの断片 |
| `CssValue[]` | 9,575 | 8.0 MB | 算出スタイル（約 2,500 に共有済み）・作業表・宣言の表 |
| `CssSyntax$Token` + `Preserved` | 74k + 74k | 3.5 + 1.2 MB | インライン style の解析結果の保持 |
| `LayoutBox` | 7,469 | 3.3 MB | |
| `LinkedHashMap$Entry` | 52k | 2.1 MB | 属性・インライン style の表 |
| `BlockLayoutEngine$LineItem` | 86k | 2.1 MB | 行の項目 |

**結論**: 算出スタイルの共有は、デザインが行ごとに違う文書でも速度（全面変更 −8〜20%）とメモリ（−14%）に効いたが、**メモリの主因ではなかった**。保持量の過半は **テキストの断片（単語ごとの `InlineFragment` と、その部分文字列 `String`）で約 25 MB**、次いで **CSS 解析結果（`Token`）と宣言の表**。次の削減対象は、断片の文字を元の文字列への範囲（開始・長さ）で持つ（部分文字列を作らない）こと、断片を単語でなく行単位にまとめること、インライン style の解析結果をスタイル確定後に捨てることである。

#### 8.6.7 保持量の削減（断片・文字列・キャッシュ、2026-11-11）

§8.6.6 の内訳（varied-list-2000）を、保持量の大きい順に潰した。`MemFootprintMain`（GC 後のヒープ保持）で測り、各段で `:core:test` 全件グリーン。

| 施策 | 内容 | varied-list-2000 | large-list-2000 |
| :-- | :-- | :-: | :-: |
| （開始） | §8.6.5 の値 | 60.2 MB | 9.3 MB |
| `ComputedStyle` の共有 | §8.6.6 | 51.8 MB | 9.3 MB |
| 語の文字列の共有（`TextShaper.sharedSegment`） | 折り返しの単位の語を、同じ綴りなら 1 つの `String` に（直接配置の表・確保なしで引く） | 約 −0（他の項目に隠れて小さい） | — |
| **CSS トークンの文字列の共有**（`CssSyntax.Tokenizer.pooled`） | 解析結果の記憶（`VALUE_CACHE`、最大 8,192 件）が持つ 7.4 万トークンの `value` / `unit` / `raw` を、同じ綴りなら共有。**記憶は 10.5 MB → 約 5 MB** | 43.6 MB | 9.3 MB |
| **未使用の生宣言キャッシュの廃止** | `StateFlags.computedStyleCache`（カスケード後の生宣言の表）は**書かれるだけで読まれていなかった**。要素ごとに約 1 KB 保持していた | 40.7 MB | 9.0 MB |
| **行の項目から断片オブジェクトを外す**（`LineItem`） | 行の記憶（`LineItem`）が、行の相対座標を持つ `InlineFragment` を別に持っていたため、語ごとに同じ大きさのオブジェクトが 2 つ残っていた。`LineItem` が断片の項目（箱・x・幅・高さ・文字・ベースライン・descent・親）を直接持ち、置くときだけ `InlineFragment` を作る | **39.0 MB** | **8.7 MB** |
| **合計** | | **60.2 → 39.0 MB（−35%）** | 9.3 → 8.7 MB（−6%） |

速度は退行なし（GL・平均 ms。large-list-2000 `child` 1.25 / `inherited` 3.6 / `fontsize` 6.5、varied `child` 4.3 / `inherited` 9.8 / `fontsize` 43.5）。

**残る内訳**（varied・39 MB）: `InlineFragment`（置いた結果・語ごと 8.6 万個・約 4.8 MB）、`CssValue[]`（約 5 MB）、`LayoutBox` 3.3 MB、`LinkedHashMap$Entry`（属性・インライン style）2 MB。

**`StyleTable` の保持元（特定済み）**: 状態の記憶 `StateStyleMemo.Entry(raw, computed)` が、要素ごとにカスケード解決後の生宣言の表 `raw`（`StyleTable`・配列 約 840 B）を保持している（約 3,000 件）。`raw` を内容で共有する案（弱参照の hash-consing）を試したが、**保持量は変わらなかった**（異なる内容が 2,196 件あり、行ごとにインライン style が違う varied では `raw` も行ごとに別物。同じ宣言の要素は、既存のスタイル共有 `styleShare` が先に共有している）。効果が無いので戻した。残る手は、**疎な表**（`raw` は 210 スロットのうち数十しか使わない）を ID と値の小さな配列で持つこと（見込み 約 1.5 MB・4%）だが、`raw` を読む経路（`StyleTable.copyOf`）が遅くなるため見送り。Chrome のレンダラの増分は同じ文書で +26 MB なので、**同じ文書で約 1.5 倍まで縮んだ**（開始時は約 2.3 倍）。

#### 8.6.8 再測定と原因の調査し直し（2026-11-11、§8.6.7 の直後）

**再測定**（2,000 行・GL 平均 ms ／ Chrome 157 全 CPU、`child` は Chrome も 250 行目へ挿入の `child250`）:

| シナリオ | large-list `:core` | Chrome | varied `:core` | Chrome |
| :-- | :-: | :-: | :-: | :-: |
| `hover` | **0.24** | 0.61 | **0.30** | 0.79 |
| `style` | **0.24** | 0.62 | **0.31** | 0.71 |
| `text` | **0.37** | 1.26 | **0.62** | 2.15 |
| `scroll` | **0.08** | 0.44 | **0.09** | 0.52 |
| `child` | **1.18** | 2.01 | 4.15 | **3.63** |
| `fontsize` | **6.2** | 11.6 | 43.6 → **28.5**（後述） | 43.6 |
| `inherited` | 3.9 | **2.31** | 9.6 | **3.57** |

**劣る 3 箇所の原因（JFR・段別、今回の調べ直し）**:

1. **varied `fontsize`（43.6 ms）— 原因は記憶の容量の崖だった（修正済み → 28.5 ms）**。`layout.measure` が 26.7 ms で、`FontFace.naturalAdvance`（書体の幅の計算）が全サンプルの 16%、`sharedSegment`・`LineBreaker.classify` が続く。段落の下ごしらえの記憶（`TextShaper.SEGMENTED`）と整形結果の記憶（`SHAPED`）は、**件数が上限 4,096 に達すると全てを消す**作りで、varied は段落 2,300 件 × 書体の大きさ 2 通り（往復する）= 約 4,600 件で上限を超え、**往復のたびに全段落を測り直していた**。2 世代の記憶（新しい世代が 8,192 件に達したら古い世代を捨てる。`Generational`）に変えた結果、`measure` 26.7 → 8.9 ms、確保 64 → 25 MB/フレーム、保持量は +約 4 MB（39 → 43 MB）。Chrome は同条件で 43.6 ms なので **Chrome の約 1.5 倍速い**。
2. **`inherited`（large 3.9 / varied 9.6 ms、Chrome 2.3 / 3.6）— 原因は 2 つ**。(a) `restyle-local` が 2.9 / 6.1 ms: `ComputedStyle.diff`（全スロットの比較）が最大（18%）。共有された算出スタイルは同じ（古い・新しい）の組が行ごとに何度も来るので、**組で結果を覚える**ようにした（`isLayoutAffectingChange` の記憶）。large は 2.9 → 2.4 ms。varied は行ごとに組が違うので効かない（6.1 ms のまま）。残りは `StyleStateAutomaton.compress`・`TextShaper.isAlreadyNormal`（文字の箱ごとに空白の畳み方を毎回確認）・`CssValue.dependsOnViewport`（生宣言ごとに全スロットを走査）で、いずれも要素ごとの定数コスト。(b) `display-list` が 0.84 / 3.3 ms: **`canPatchBoxes` が理由 5（スクロールバーを持つ箱）で毎フレーム退き**、窓付きの全面再構築に落ちている（変更ボックスに `#scroller`（overflow: auto）が含まれるため。`PaintOrder.factsOf`・`classify`・`hasUnscrollableDescendant`・`offscreen` が並ぶ）。スクロールコンテナの項目（`_scrollc` の動く節・スクロールバー）を含めて再生成できるようにすれば、この 0.84 / 3.3 ms は消える。
3. **varied `child`（4.15 ms、Chrome 3.63）— 原因は後続行の置き直し**。`layout.place` が 2.7 ms で、`placeLine`（断片を新しく作る 3 行）・`scanFloats`・`updateScrollExtent`・`arrangeFlow` が並ぶ。行の高さが小数だと §8.6.3 の平行移動の近道（縦・整数ぶん）が使えず、**後続 1,750 行ぶん・約 7.5 万個の断片を毎回作り直している**。large-list も同じ理由で `place` 0.46 ms が残るが、断片が少ない（1 行 6 語）ので目立たない。解決策は、窓の外の行のレイアウトを遅延するか、置き直しで既存の断片オブジェクトを再利用すること。

**副産物**: `TextShaper` の記憶の全消去は、段落 × 書体が 4,096 を超える文書なら、リサイズ・拡大縮小でも同じ崖を踏む（幅変更の `showcase` は小さいので影響なし）。

#### 8.6.9 メモリの再測定（2026-11-11、§8.6.8 の変更後）

Chrome は `mem.js`（空ページからの Private 増分、各 3 回の平均）、`:core` は `MemFootprintMain`（GC 後のヒープ保持。各 3 回で完全に一致）。

| 文書 | Chrome レンダラ Private（空ページ 26.2 MB から） | `:core` ヒープ保持 | 比 | `:core` プロセス Private 増分（Serial GC・ヒープ固定） |
| :-- | :-: | :-: | :-: | :-: |
| large-list-500 | 約 28.7（**+2.5**、ばらつき ±1.5） | **2.5 MB** | 約 1.0 倍 | 約 7〜9 MB |
| large-list-2000 | 約 34.9（**+8.7**、前回 +5。ばらつき ±3） | **8.7 MB** | 約 1.0 倍 | 測れず（-9〜+3 MB のばらつき） |
| varied-list-2000 | 約 54.4（**+28.2**、前回 +26） | **42.7 MB** | **約 1.5 倍**（開始時 2.3 倍） | **約 70〜75 MB** |

**読み方**:
- **ヒープ保持は、同形の文書で Chrome のレンダラの増分とほぼ同じ、デザインが違う文書で約 1.5 倍**。§8.6.7 の削減（60.2 → 39.0 MB）の後、§8.6.8 の整形記憶の 2 世代化（`fontsize` 往復の崖の解消）で **+3.7 MB（39.0 → 42.7 MB）**を払っている（速度 43.6 → 28.5 ms との引き換え）。
- **プロセスの Private 増分は、ヒープ保持よりずっと大きい**（varied で約 72 MB）。読み込み中の一時的なゴミ（レイアウト・成形の確保）がヒープの若い世代のページに触れた分と、オフヒープ（`DocumentArena`）・ネイティブの分を含む。既定の G1 では伸縮で 237 MB まで見える。**Chrome の Private（約 28 MB 増）と同じ尺度で比べると約 2.6 倍**で、ヒープ保持の比（1.5 倍）より悪い。読み込み時の確保の削減（一時的な確保の総量）が別の課題。
- 空ページの土台は比べられない（Chrome レンダラ 約 26 MB に対し、JVM はヒープ 5.4 MB・メタスペースなどのネイティブ領域が別）。

#### 8.6.10 読み込み時の確保（一時的なゴミ）の内訳と削減（2026-11-11）

**背景**: §8.6.9 で、ヒープ保持（42.7 MB）よりプロセス Private の増分（約 72 MB）が大きかった。差は読み込み中の一時的な確保。`FineProfileMain open varied-list-2000` を JFR の `ObjectAllocationSample` で見た（1 回の読み込みで **108 MB** 確保、77 ms。うち `style+build` 41.6 ms・measure 9.3・display-list 4.7・place 4.0・box-tree 3.4）。

**確保の上位（調査時・合計 36 GB 相当のうちの割合）**:

| 場所 | 割合 | 内容 |
| :-- | :-: | :-- |
| `StyleTable` の `CssValue[]`（約 840 B）×4 か所 | 約 10% | 要素ごとにカスケード結果の表を 4 つ作る（`resolveDeclarations`・`cascadeMatched`・`finalizeComputedStyle` の複製 ×2 経路） |
| `CssSyntax.serialize`・`appendSerialized`・`trim`・`splitOnWhitespace`（`ValueComputer.absolutizeUnits` 経由が大半） | **約 13.5%** | 値の中の寸法（`12px` など）を毎回直列化して文字列を作り直し、**置き換えが無ければ捨てていた** |
| `HtmlParser.TreeBuilder.normalizeText` | **約 6.3%** | 文字ノードごとに正規表現（`replaceAll` ×2）で空白を畳んでいた（畳む必要が無い文字列でも） |
| `LineItem` ＋ `InlineFragment` ＋ `ArrayList` | 約 9% | 語ごとの行の項目と置いた断片（必要な確保） |
| インライン style の解析（`parseDeclaration` の `Object[]`・`serialize`） | 約 5% | 行ごとに違うインライン style を宣言に分けて値を直列化 |

**対策**:
1. `ValueComputer.absolutizeUnits`: **置き換える寸法が 1 つも無ければ直列化しない**（先に値の木を走査して判定。反復子も作らない）。
2. `HtmlParser.normalizeText`: **改行・タブも連続する空白も無い文字列は正規表現を通さない**。

**効果**（`open varied-list-2000`、null バックエンド）: 確保 **108 → 約 93 MB（−14%）**、読み込み時間 77 → 約 65〜70 ms。`:core:test` 全件グリーン。

**測定上の注意**: プロセス Private の増分は GC の起きた位置で -20〜+75 MB と大きく振れ、削減の効果を示す尺度にならない（確保量そのものを指標にする）。ヒープ保持（42.7 MB）は変わらない。

**残る大きい確保**: `StyleTable` の表が要素ごとに 4 つ（約 3.3 GB 相当/10%）、語ごとの `LineItem`・断片、インライン style の解析。`StyleTable` は、結果の複製を省く（保持する生宣言と作業表を分ける設計の見直し）か、疎な表にすれば減らせるが、いずれも影響が広い。

#### 8.6.11 スクロール付きの箱を含む局所パッチ（2026-11-11）

**原因（§8.6.8-2b の対処）**: `inherited`（body の `color`）では変更ボックスに `overflow: auto` の `#scroller` が含まれ、`canPatchBoxes` が理由 5（スクロールバーを持つ箱）で退き、窓付きの全面再構築に落ちていた。

**実装**（`DisplayListBuilder.canPatchBoxes` / `scrollTraitsUnchanged`）: スクロールバーのつまみ・溝は箱自身のスライス `[dlStart, dlEnd)` の外（`PaintOrder` の after 層）に出る。そのため、**スクロールの有無・`overflow-x/y`・`scrollbar-width` が前回の記憶と同じ**なら、自身のスライスだけを差し替えればよい。変わるときと、記憶が無いとき、`animation` を持つときは従来どおり退く（理由 5）。

**検証**: `DisplayListSlicePatchTest.inheritedChangeWithScrollContainerPatchesAndMatchesFullRender`（スクロール付きの箱を含む継承色変更を 4 手順往復し、毎回全再構築と全項目一致、かつ `RESTYLE_REPAINT_PATCH` が命中）。`:core:test` 全件グリーン。

**実測**（`FineProfileMain inherited`・GL・1,500 フレーム、平均 ms）:

| 文書 | 変更前 | 変更後 |
| :-- | :-: | :-: |
| varied-list-2000 `inherited` | 9.6（display-list 3.3） | **9.35（display-list 1.18）**、確保 3.2 MB/フレーム |
| large-list-2000 `inherited` | 3.9（display-list 0.84） | **3.54（display-list 0.64）** |

スライスパッチ命中は 100%（`repaintPatch=2100`・`repaintFull=0`・フォールバック 0）。`inherited` の残りは `restyle-local`（varied 6.2 / large 2.2 ms）で、要素ごとの定数コスト（`StyleStateAutomaton.compress`・`TextShaper.isAlreadyNormal`・`CssValue.dependsOnViewport`）が支配的。次はここの短絡（継承値を使わない要素の再解決を省く）と、varied `child` の後続行の置き直し（断片の再利用・窓外のレイアウト遅延）。

R3（§7.3）は施策 2・3 とほぼ同一作業なのでそちらで進める。R2 の時間 KPI（`child` `< 3 ms`）は施策 4・5 の完了で再判定する。

#### 8.6.12 R7 の一部: スタイル・hover・継承・テキスト・子を混ぜた乱択列の一致テスト（2026-11-11）

**追加**: `DisplayListSlicePatchTest.randomMixedStyleHoverTextChildSequenceMatchesFullRender`。60 手順（固定シード）で、class 付け外し（`transition`・`opacity` によるスタッキング含む）・`:hover` の移動／解除・リスト全体の継承色変更（インライン style）・行のインライン背景・ボタンのテキスト更新・行の追加を混ぜ、**毎手順でパッチ経路と全再構築の全項目（key・type・座標・色・テキスト）が一致**することと、スタイル系の手順（10 回超）で `RESTYLE_REPAINT_PATCH` が命中することを確認する。従来の乱択列は子・テキストのみで、スタイル系は個別シナリオだけだった。

**結果**: 初回から全手順一致（不一致の発見なし）。`DisplayListSlicePatchTest` 13 件・`:core:test` 全件グリーン。

**調査して見送った項目**: `inherited` の残り（`restyle-local`、varied 6.2 / large 2.2 ms）の「継承値を使わない要素の再解決を省く」短絡は、コード確認の結果、現行構造では得が小さいと判断した。明示的に値を持つ要素の子孫は既に `propagationOf == NONE` で刈られている（`restyleBoxRecursive`）。残るのは「本当に継承で色が変わる全要素」の算出で、1 要素約 130 ns と下限に近い。これを下回るには継承値の遅延評価（Paint 表・§8.2 の DoD 再設計）が必要で、局所的な短絡では届かない。**数値による検証はしていない（コード上の判断）**。

次の候補: varied `child` の後続行の置き直し（断片の再利用・窓外のレイアウト遅延）、R4（CPU バックエンドの全面ダメージ化）。

#### 8.6.13 R4: CPU の差分描画が全面に戻る理由の計測と、volatile 項目の是正（2026-11-11）

**計測**: `SoftwareRenderBackend` に差分描画を諦めた理由の件数（`fullReasons()`、`FineProfileMain` の `DAMAGE-WHY` 行）を追加。showcase の CPU `hover`（1,500 フレーム）は `partial=0 / full=2101`、つまり**毎フレーム全面描画**だった。理由の内訳は、画像・入力欄・進捗バー等の volatile 項目（`volatileItem`）が約 100%。

**是正**: 同サイズの項目列では volatile 項目で全面に戻さず、毎フレーム「変わった」として扱う（その範囲だけ塗り直す）。項目数が変わるフレームの volatile 判定は従来どおり全面（未着手）。検証: `VolatileItemPartialRepaintTest`（入力欄・進捗バーを含むページでの hover・テキスト・値の乱択 50 手順×3 シードが、毎フレーム全面描画と全画素一致し、`partial > full`・`aborts == 0`。修正を外すと失敗することを確認）。`PartialRepaintPixelTest`・`DiffPatchPixelTest` 含む `:core:test` 全件グリーン。

**実測（showcase・CPU）**: `inherited` は `full=2101 → partial=2099` に変わったが、継承色で文字がほぼ全面で変わるため時間は `2.14 → 2.21 ms`（差なし）。`hover` は理由が **1=前フレームが動作中 / 6=動作中のアニメ項目**（各 1,050）に移り、`partial=0` のまま、`1.77 → 1.50 ms`（ばらつきの範囲）。**時間 KPI（`< 1.5 ms`）への寄与は未確認**。

**残り**: hover 先の要素が `transition` を持つため、遷移中は全面描画に戻る（現行設計では、動作中の項目の同一性が変わらないので差分判定できない）。対応には項目ごとの「前フレームで動作中だったか」の記録と、動作中項目を変更扱いにする設計が必要（次の候補）。不透明度・フィルターの層（`groupItem`）も、同サイズのフレームでは全面に戻したまま。

#### 8.6.14 R7: パッチ命中率と 1 操作あたり確保量のラチェット（2026-11-11）

**追加**: `PatchHitRateRatchetTest`（500 行リスト）。時間ではなくカウンタで退行を固定する。

| テスト | 要求 | 実測 |
| :-- | :-- | :-- |
| `hoverRoundTripAlwaysPatchesWithoutFullRebuild` | hover 付与・解除 80 操作で `RESTYLE_REPAINT_PATCH` が 80（100%）、`RESTYLE_REPAINT_FULL` が 0、確保 `< 50 KB`/操作 | patch=80・full=0・**約 6 KB/操作** |
| `classToggleAlwaysPatchesWithoutFullRebuild` | クラス付け外し 80 操作で同上（命中 100%・全面 0） | 合格 |
| `childAppendAlwaysHitsChildPatch` | 行の追加 20 操作で `CHILD_PATCH_HITS` が 20、フォールバック 0、確保 `< 2.5 MB`/操作 | hits=20・fallbacks=0・**約 740 KB/操作** |

上限は実測の約 3〜8 倍。全面再構築へ落ちる、またはパッチ未使用になる退行は、命中数・フォールバック数で即失敗する。`:core:test` 全件グリーン。

**R7 の残り**: 継承色変更（`inherited`）・テキスト更新のカウンタラチェット、`AllocationRatchetTest` 系の 2,000 行版。**未実施**。

### 8.7 約束事とリスク

1. キーは「入力の正規化ハッシュ」。衝突は `equals` で必ず確認する（見た目が変わる誤共有を許さない）。
2. すべての中間結果は不変（共有できる前提）。座標は小数の相対値で保持し、丸めは描画の最終段 1 か所。
3. 「共有なし・全再計算」と同じ結果になることを、ランダム変異列＋実ドキュメントで毎段検証する。クラス数が多い／小さな部分木は自動で共有を止める（ハッシュ計算が計算を上回る場合は共有しない。閾値は M0 で決める）。

| リスク | 対策 |
| :-- | :-- |
| 影響範囲が広い（GL・CPU・HitTest・PaintOrder・テスト） | `DisplayItem` を読み取り専用ビュー（フライウェイト）として互換維持。段ごとに旧経路 oracle と差分テスト |
| `jdk.incubator.vector` が incubator | 17,500 要素ならスカラ版でも数十 µs の見込み。必須にしない（D0 で実測） |
| `transition`・アニメ中の補間値（Payload の付け替えで表現不可） | アニメ中ノードは専用のローカル Payload スロットを動的確保（少数） |
| スタッキングコンテキスト・`transform` と描画順 | Paint 表の順序は現行 `PaintOrder` の出力を採用。`transform` / スタッキング / sticky / アニメ中の範囲は従来経路へフォールバック |
| 時間ベンチが全件実行で不安定（既知） | 時間でなく確保量・パッチ命中・走査項目数のカウンタをラチェット化（R7） |

---

## 9. 2026-11-12 再計測: 現行コードのボトルネック特定と改善点

### 9.1 結論

現行コードは「一律に遅い」状態ではない。2026-11-12 時点の実フレーム計測では、**同形の大規模リスト（`large-list-2000`）は `hover`・`child`・`fontsize` のすべてで Chrome と同等以上**（GL `0.30` / `1.44` / `7.63 ms`、Chrome `0.55` / `1.98` / `11.3`）。一方で、次の 3 群が残存する。

1. **行ごとにデザインが違う `varied-list-2000` の `child` と `inherited`**: GL `5.15 ms`（Chrome `3.67`・1.4x）と `10.20 ms`（Chrome `3.55`・2.9x）。`fontsize` は `30.77 ms` で Chrome `42.1` より速いが、確保量が `25.8 MB`/フレームと大きい。
2. **showcase の幅変更**: 実フレーム `1.045 ms`、JMH `layoutWidthChange` `0.777 ms`。Chrome `0.0835 ms` の 9〜12 倍。表示リストの再構築（`0.421 ms`）と測定（`0.375 ms`）がほぼ半々。
3. **CPU バックエンドの transition 中の全面再描画**: showcase `hover` は `1.77 ms` のうち paint が `1.64 ms`（93%）。`partial=0 / full=1801` で、理由は「前フレームが動作中」「動作中のアニメ項目」。`inherited` は `2.62 ms` で部分描画が効いている（`partial=1599 / full=2`）。

ただし、これは「全項目で勝った」という意味ではない。large 500 行の `inherited`/`hover`/`style`、2,000 行 `text`、`open`、メモリ保持量、p99 は依然として負けているか未計測である（§9.8.1）。

JFR の実行サンプルから、残存ボトルネックは次の 4 系統に分かれた。

- **A. パッチ判定の O(変更箱 × 祖先) 走査**（`varied inherited` の `canPatchBoxes` 26.4%）。パッチ自体は命中しているのに、判定が高い。
- **B. 後続行の置き直しと断片の再生成**（`varied child` の `placeLine` 26.3%、`ArrayList.add` 15.0%）。1,750 行の `InlineFragment` を作り直す。
- **C. 要素ごとのスタイル等価判定・ビューポート依存判定・テキスト成形**（`varied fontsize` の `Arrays.equals` 7.9% + `StyleMap.equals` 8.5%、`dependsOnViewport` 3.6%、`LineBuilder.newLine` 4.5%）。
- **D. 絶対座標を焼き込んだ表示項目の再生成**（showcase resize の display-list `0.421 ms`、`MATCHFAIL` reason 4 が `111.9/163.9`）。

以下、計測方法、全数値、JFR のホットフレーム、コードとの対応、優先改善 P1〜P8 を示す。

### 9.2 計測環境と方法

| 項目 | 内容 |
| :-- | :-- |
| 実行環境 | Windows / JDK 21 / `:core` 現行 HEAD（`dbcd523` 以降） |
| 画面 | `800×600`（一部 `700×600`・`600×600`）、`FontResolver.FALLBACK` |
| 実フレーム | `FineProfileMain`（`StandaloneView.renderFrame` 全体）。ウォームアップ 600 フレーム、計測 400〜1,200 フレーム。平均・p50・確保量・段別（style+build / box-tree / restyle-local / measure / place / display-list / paint） |
| バックエンド | `null`（レイアウト＋表示リストのみ）、`gl`（LWJGL）、`cpu`（ソフトウェア） |
| JMH | `PipelineBench.layoutWidthChange`（showcase、1 fork、3 warmup、5 iteration、`-prof gc`） |
| JFR | `jdk.ExecutionSample`、スタック深さ 8。`jfr print` のテキストを集計（実行サンプル数は各表に記載） |

再現コマンド:

```powershell
.\gradlew.bat :core:profile "-Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain" "-Pprofile.args=child varied-list-2000 500 gl"
.\gradlew.bat :core:jmh "-Pjmh.args=PipelineBench.layoutWidthChange -p doc=showcase -f 1 -wi 3 -i 5 -prof gc"
.\gradlew.bat :core:profile "-Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain" "-Pprofile.args=inherited varied-list-2000 500 null" "-Pprofile.jfr=build/prof/varied-inherited.jfr"
```

保存した JFR: `build/prof/varied-fontsize.jfr`、`build/prof/varied-inherited.jfr`、`build/prof/varied-child.jfr`、`build/prof/showcase-resize.jfr`。集計は `jfr print --events jdk.ExecutionSample --stack-depth 8` の出力をスクリプトで exclusive / inclusive に集計した。

**注意**: 実フレームの平均は GC・JIT・OS の影響を受ける。以下の差分は同一環境・同一日に取得したが、±10〜15% のばらつきを含む。JFR の割合は実行サンプルに対するもので、フレーム時間の割合ではない（サンプル数が少ない showcase resize は参考値）。

### 9.3 実フレーム再計測の全体像

| 文書 / 操作 | backend（frames） | mean / p50 (ms) | 確保 (KB/frame) | 支配的な段 (ms) | 比較 | 判定 |
| :-- | :--: | :--: | :--: | :-- | :-- | :--: |
| large-list-2000 `hover` | GL (800) | `0.300` / `0.286` | `39` | restyle `0.113` / display `0.002` / paint `0.167` | Chrome `0.55` | ✅ |
| large-list-2000 `child` | GL (800) | `1.435` / `1.387` | `938` | measure `0.213` / place `0.576` / display `0.136` / paint `0.208` | Chrome `child250` `1.98` | ✅ |
| large-list-2000 `child` | null (800) | `1.126` / `1.048` | `878` | measure `0.209` / place `0.496` / display `0.137` | — | ✅ |
| large-list-2000 `inherited` | GL (700) | `3.497` / `3.426` | `1,621` | restyle `2.106` / display `0.620` / paint `0.334` | 前 `3.54`（§8.6.11） | 🔧 |
| large-list-2000 `fontsize` | GL (800) | `7.634` / `7.616` | `7,331` | style `0.436` / box `0.459` / restyle `1.730` / measure `2.302` / place `0.916` / display `0.883` / paint `0.422` | Chrome `11.3` | ✅ |
| varied-list-2000 `child` | GL (500) | `5.147` / `5.041` | `4,891` | measure `0.451` / place `3.050` / display `0.316` / paint `0.397` / unaccounted `0.934` | Chrome `3.67` | ❌ 1.4x |
| varied-list-2000 `child` | null (600) | `4.789` / `4.613` | `4,818` | measure `0.461` / place `3.198` / display `0.301` / unaccounted `0.829` | — | ❌ |
| varied-list-2000 `inherited` | GL (500) | `10.200` / `10.028` | `3,157` | restyle `6.532` / display `1.311` / paint `0.579` / unaccounted `1.777` | Chrome `3.55` | ❌ 2.9x |
| varied-list-2000 `inherited` | null (600) | `9.691` / `9.506` | `2,456` | restyle `6.616` / display `1.337` / unaccounted `1.738` | — | ❌ |
| varied-list-2000 `fontsize` | GL (400) | `30.768` / `30.411` | `26,072` | style `1.638` / box `2.214` / restyle `6.794` / measure `9.588` / place `4.690` / display `3.116` / paint `0.616` | Chrome `42.1` | ✅ |
| varied-list-2000 `fontsize` | null (600) | `30.848` / `30.207` | `25,826` | style `1.715` / box `2.160` / restyle `7.089` / measure `9.707` / place `4.810` / display `3.214` | — | ✅ |
| showcase `resize` | null (—) | `1.045` / `0.953` | `1,456` | style `0.077` / box `0.060` / measure `0.375` / place `0.043` / display `0.421` | Chrome `0.0835` | ❌ 12.5x |
| showcase `hover` | CPU (1200) | `1.767` / `1.736` | `285` | restyle `0.036` / display `0.056` / paint `1.640` | Chrome CPU `1.55` | ❌ |
| showcase `inherited` | CPU (1000) | `2.621` / `2.559` | `879` | restyle `0.240` / display `0.136` / paint `2.192` | 前 `2.21` | ✅ |

**パッチ命中**: large `hover` は slice-patch `1,400`、large `inherited` は `repaintPatch=1,300 / repaintFull=0`、large `child` は `childPatch=1,400 / fallback=0`。いずれも命中率 100% で、**「パッチが当たらない」ことはもう主要因ではない**。残っているのは判定・レイアウト・表示項目の再生成コストである。

**large `child` の表示項目数**: 窓付き表示リストにより画面内の `322` 項目だけを生成する。Chrome の `child250` と同条件で比較すると、`1.44 ms` は Chrome `1.98 ms` を下回る。

**showcase CPU のばらつき**: `hover` の `1.77 ms` は §8.6.13 の `1.50〜1.77 ms` の上端、`inherited` の `2.62 ms` は前回 `2.21 ms` から +18% だが、目標 `< 4 ms` は満たす。CPU の値はタイルのダーティ状態と transition の位相に依存するため、±20% の幅で読む。

### 9.4 JMH: 幅変更（`layoutWidthChange`）

showcase の幅を 1px 交互に変える JMH の 5 iteration:

| iteration | 1 | 2 | 3 | 4 | 5 | 平均 |
| :-- | :--: | :--: | :--: | :--: | :--: | :--: |
| ms/op | `0.745` | `0.725` | `0.776` | `0.818` | `0.821` | **`0.777`** |

確保量は `1,175,600 B/op`（約 `1.18 MB/op`）。前回の `1.039 ms` から **−25%**。Chrome `0.0835 ms` に対しては **9.3 倍**で、依然 W5 である。

JMH はレイアウトのみ（表示リストを含まない）。実フレームの `1.045 ms` との差 `約 0.27 ms` が表示リスト再構築とフレーム周辺のコストに相当する。内訳では display-list `0.421 ms` が最大で、measure `0.375 ms` が続く。**幅変更の改善は「レイアウトの測定再利用」と「表示項目の平行移動再利用」の 2 本立てで進める**。

### 9.5 JFR ホットフレーム

JFR はすべて `null` バックエンド（レイアウト＋表示リスト）で取得した。GL 送出・CPU ラスタは含まないため、描画段のボトルネックは §9.3 の段別と §9.6.6 で補う。サンプル数は `fontsize` 2,139（十分）、`inherited` 692・`child` 339（傾向を読むには足りるが ±数% の誤差を含む）、showcase resize 77（参考値）である。

#### 9.5.1 `varied-list-2000` `fontsize`（2,139 サンプル）

| 種別 | 上位フレーム | 割合 |
| :-- | :-- | :--: |
| exclusive | `java.util.Arrays.equals(Object[], Object[])` | **7.9%** |
| exclusive | `BlockLayoutEngine$LineBuilder.newLine` | 4.5% |
| exclusive | `BlockLayoutEngine.placeLine` | 4.1% |
| exclusive | `CssValue.dependsOnViewport` | 3.6% |
| exclusive | `BlockLayoutEngine.arrangeFlow` | 3.4% |
| exclusive | `DisplayListBuilder.offscreen` | 3.1% |
| exclusive | `String.hashCode` | 2.5% |
| exclusive | `BoxMeasurer.measure` | 2.5% |
| exclusive | `BoxTreeBuilder.restyleGeneratedBoxes` | 2.3% |
| exclusive | `BlockLayoutEngine.contentBlockSize` | 2.2% |
| inclusive | `BoxMeasurer.measure` | **25.0%** |
| inclusive | `RenderPipeline.restyleBoxRecursive` | **24.2%** |
| inclusive | `LayoutBox.measureCore` | 20.1% |
| inclusive | `BlockLayoutEngine.arrangeFlow` / `arrangeChildren` | 14.2% |
| inclusive | `ComputedStyle$StyleMap.equals` | 8.5% |
| inclusive | `java.util.Arrays.equals` | 7.9% |
| inclusive | `BlockLayoutEngine.placeLine` | 5.8% |
| inclusive | `RenderPipeline.styleEntry` | 5.1% |
| inclusive | `PaintOrder.collectChildren` / `classify` | 5.0% |
| inclusive | `RenderPipeline.layoutDocument` | 4.7% |

**読み方**: 2 つのクラスタがある。① **スタイル等価判定**（`Arrays.equals` 7.9% + `StyleMap.equals` 8.5% + `String.hashCode` 2.5% + `dependsOnViewport` 3.6%）。② **テキスト成形・行組み**（`newLine` 4.5% + `placeLine` 4.1% + `arrangeFlow` 3.4% + `measure` 2.5% + `contentBlockSize` 2.2%）。`BoxMeasurer.measure` の inclusive 25.0% は、測定そのものより下流のテキスト・行組みを含む。`DisplayListBuilder.offscreen` 3.1% は、全面再構築時にも画面外判定が走ることを示す。

#### 9.5.2 `varied-list-2000` `inherited`（692 サンプル）

| 種別 | 上位フレーム | 割合 |
| :-- | :-- | :--: |
| exclusive | `DisplayListBuilder.offscreen` | **20.4%** |
| exclusive | `java.util.Arrays.equals(Object[], Object[])` | **10.5%** |
| exclusive | `CssValue.dependsOnViewport` | 6.4% |
| exclusive | `StateLookup.nearestSizeContainer` | 5.9% |
| exclusive | `DisplayListBuilder.isCulledNoop` | 5.8% |
| exclusive | `BoxTreeBuilder.restyleGeneratedBoxes` | 5.2% |
| exclusive | `ComputedStyle.diff` | 5.1% |
| exclusive | `TextShaper.isAlreadyNormal` | 3.8% |
| exclusive | `StyleAnimator.hasValue` | 3.6% |
| exclusive | `RenderPipeline.restyleBoxRecursive` | 3.3% |
| inclusive | `RenderPipeline.restyleBoxRecursive` | **64.6%** |
| inclusive | `DisplayListBuilder.isCulledNoop` | **26.4%** |
| inclusive | `DisplayListBuilder.canPatchBoxes` | **26.4%** |
| inclusive | `DisplayListBuilder.offscreen` | 20.7% |
| inclusive | `DisplayListBuilder.patchBoxes` | 14.0% |
| inclusive | `java.util.ArrayList.removeIf` | 11.7% |
| inclusive | `ComputedStyle$StyleMap.equals` | 10.5% |
| inclusive | `RenderPipeline.cacheStyle` | 10.1% |
| inclusive | `RenderPipeline.styleEntry` | 10.0% |
| inclusive | `RenderPipeline.dependsOnViewport` | 6.8% |

**読み方**: 最大の発見は、`inherited` の**実行サンプルの 26.4% が `canPatchBoxes` の判定**（`isCulledNoop` 26.4%、`offscreen` 20.7%）にあること。**スタイル再解決そのものより、パッチ可否の判定が重い**。しかも `UiView` が `canPatchBoxes` を呼んだ直後に `patchBoxes` を呼び、その中で `canPatchBoxes` をもう一度呼ぶため、**同じ高コスト判定が 1 パッチにつき 2 回走る**。`patchBoxes` の 14.0% はこの 2 回目の判定と適用を含む。`offscreen` は変更箱ごとに祖先をたどり、8 つの移動プロパティを文字列で照合する。`canPatchBoxes` はさらに `ArrayList`/`LinkedHashSet` を作り、全箱をソートする。`ArrayList.removeIf` の 11.7% も含め、判定と適用の両方が高い。

#### 9.5.3 `varied-list-2000` `child`（339 サンプル）

| 種別 | 上位フレーム | 割合 |
| :-- | :-- | :--: |
| exclusive | `java.util.ArrayList.add(Object, Object[], int)` | **15.0%** |
| exclusive | `BlockLayoutEngine.arrangeFlow` | 12.7% |
| exclusive | `LayoutBox.updateScrollExtent` | 8.8% |
| exclusive | `StringLatin1.indexOfNonWhitespace` | 8.0% |
| exclusive | `BlockLayoutEngine.scanFloats` | 6.8% |
| exclusive | `DisplayListBuilder.windowIntact` | 6.2% |
| exclusive | `BlockLayoutEngine.placeLine` | 5.6% |
| exclusive | `LayoutBox.layout` | 5.0% |
| exclusive | `LayoutBox.resetPlacements` | 2.7% |
| exclusive | `DisplayListBuilder.patchChildSlices` | 1.2% |
| inclusive | `LayoutBox.layout` | **65.8%** |
| inclusive | `BlockLayoutEngine.arrangeFlow` / `arrangeChildren` | **65.5%** |
| inclusive | `BlockLayoutEngine.placeLine` | **26.3%** |
| inclusive | `UiView.tryChildPatchReason` | 18.0% |
| inclusive | `java.util.ArrayList.add` | 15.0% |
| inclusive | `LayoutBox.updateScrollExtent` | 8.8% |
| inclusive | `String.isBlank` | 8.3% |
| inclusive | `DisplayListBuilder.patchChildSlices` | 8.0% |
| inclusive | `DisplayListBuilder.windowIntact` | 7.1% |
| inclusive | `DisplayListBuilder.canPatchChildSlices` | 7.1% |

**読み方**: レイアウトが 65.8% を占め、そのうち `placeLine` が 26.3%。`ArrayList.add` 15.0% は後続行の `InlineFragment` 追加、`updateScrollExtent` 8.8% は行ごとのスクロール範囲更新、`String.isBlank` 8.3% は行の空白判定、`scanFloats` 6.8% は浮動子の有無の走査。**子パッチの判定（`windowIntact` / `canPatchChildSlices`）は 7.1% で、主因ではない**。ボトルネックは「1,750 行を実際に置き直す」ことにある。

#### 9.5.4 showcase `resize`（77 サンプル、参考値）

サンプル数が少ないため結論には使わないが、`TextShaper.collapseRuns` 9.1% exclusive、measure 系 31% inclusive、`drawBoxSelfMemo` 24.7% inclusive、`FlexLayoutEngine.resolveFlexibleLengths` 5.2% が上位。幅変更でテキストのラン統合が再実行され、測定キャッシュのミス（`BoxMemo` exact 62.1 / miss 163.9 / unmemoizable 13）が起きていることと整合する。

### 9.6 コードとの対応（原因の特定）

#### 9.6.1 `canPatchBoxes` / `isCulledNoop` / `offscreen` の判定コスト

[`DisplayListBuilder.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/DisplayListBuilder.java) の `canPatchBoxes`（約 1288 行）は、変更箱のコレクションを受け取り、各箱について `isCulledNoop`（約 1269 行）を呼ぶ。`isCulledNoop` は `offscreen`（約 341 行）を呼び、`offscreen` は箱の祖先をたどりながら `MOVING_PROPS` の 8 プロパティを `ComputedStyle` から文字列で `get` し、`trim` と `equalsIgnoreCase` で比較する。さらに `canPatchBoxes` は `ArrayList` と `LinkedHashSet` を作り、全箱をソートしてから判定する。

これは **O(変更箱 × 祖先数 × 8 プロパティ)** で、変更箱が多い `inherited` では支配的になる。JFR の `offscreen` 20.4% exclusive / `canPatchBoxes` 26.4% inclusive がそれを裏付ける。さらに、呼び出し側の [`UiView.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/UiView.java) は `canPatchBoxes` で判定した直後に `patchBoxes` を呼び、`patchBoxesUnprofiled`（約 1351 行）が先頭で `canPatchBoxes` を再実行する。**同じ判定が 1 パッチにつき 2 回**走る。**「画面外か」「移動する祖先があるか」は箱ごとの静的な性質**なので、フレームごとに祖先をたどり直す必要はない。`StyleFacts` にビットとして持たせるか、変更箱の集合を作る段階で 1 回だけ計算してキャッシュすればよい。まず 2 回目の判定をなくすだけでも半分になる。

#### 9.6.2 `placeLine` と `InlineFragment` の再生成

[`BlockLayoutEngine.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/BlockLayoutEngine.java) の `placeLine`（約 1329 行）は、後続行の `InlineFragment` を新しいオブジェクトとして生成し、`ArrayList.add` で箱の `inlineFragments` に追加する。行ごとに高さ・折り返しが異なる `varied-list-2000` では、小数の行高さのため `shiftSubtree` の平行移動近道が効かず、1,750 行すべてを置き直す。`resetPlacements`（[`LayoutBox.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/LayoutBox.java) 約 1283 行）は断片リストをクリアして全子を歩く。`updateScrollExtent`（約 1907 行）は行ごとに呼ばれ、`scanFloats` は各ブロックで浮動子の有無を走査する。

**改善の方向**: 断片を再利用可能な可変オブジェクト（またはプール）にし、行の位置・幅・テキストだけを更新する。`containsFloats` を箱にキャッシュし、浮動子がない場合は `scanFloats` を省略する。`updateScrollExtent` は部分木の最後に 1 回だけ計算する。`String.isBlank` は先頭・末尾の非空白文字を直接見る高速判定にする。

#### 9.6.3 スタイル等価判定とビューポート依存判定

[`ComputedStyle.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/ComputedStyle.java) の `sameDeclarations`（約 392 行）と `StyleMap.equals`（約 434 行）は、約 190 エントリの `CssValue[]` を `Arrays.equals` で比較する。`sameDeclarations` には同一性の高速路があるが、**内容が同じでインスタンスが違うスタイル**は配列比較に落ちる。`CssValue.dependsOnViewport()`（[`CssValue.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/CssValue.java) 約 111 行）は `CssValue` 単位ではメモ化されているが、要素ごとに何度も呼ばれる。`ComputedStyle.facts()`（約 958 行）と `InteractionState.register` も要素ごとの定数コストとして現れる。

**改善の方向**: 同一性 → 64-bit の宣言ハッシュ → 配列比較の順に短絡する。`facts` と `dependsOnViewport` をスタイル単位で 1 回だけ計算し、要素側では参照を読むだけにする。状態フラグが変わらない要素の `register` を省略する。`varied fontsize` では `Arrays.equals` 7.9% + `StyleMap.equals` 8.5% が直接の対象になる。

#### 9.6.4 テキスト成形・改行の再計算

`varied fontsize` の `LineBuilder.newLine` 4.5%、`placeLine` 4.1%、`FontFace.naturalAdvance` 2.0%、`TextShaper$Generational.get` 1.7%、`isAlreadyNormal` 1.7% は、幅変更で全行のテキストを成形し直していることを示す。`varied fontsize` は行ごとに文字サイズ・文章長・折り返しが異なるため、同形リストのように共有できない。**成形済みランと改行位置を (text, font, size, width) でキャッシュ**し、幅が同じなら再成形を避ける必要がある。`isAlreadyNormal` の結果を文字列またはスタイルに保持するのも有効である。

#### 9.6.5 表示項目の絶対座標と `survivesTranslation`

[`DisplayListBuilder.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/DisplayListBuilder.java) の `survivesTranslation`（約 746 行）は、影・シェーダーグラデーション・レイヤー・`NODE`/`RECT`/`TEXT`/`ROUNDED_BORDER` 以外の項目を「平行移動で再利用できない」と判定する。showcase resize の `MATCHFAIL` は reason 4（移動するが平行移動不可）が `111.9/163.9` を占め、`BoxMemo` も exact `62.1` に対して miss `163.9`、unmemoizable `13` だった。`UiView.recomputeLayout` は `paintBoxes(rootBox, w, h, false)` を呼び、表示リストの全再構築経路に落ちる（ビルダーと箱の記憶は保持していても、項目は作り直す）。

**改善の方向**: 表示項目の座標を箱原点からの相対値で保持し、平行移動だけなら項目を再利用する。影・グラデーション・レイヤーもオフセット付きで再利用できるようにする。`BoxMemo` のキーに幅を含め、内容が同じ箱の測定を幅変更後も再利用する。これが P3 である。

#### 9.6.6 CPU バックエンドの全面再描画

[`SoftwareRenderBackend.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java) は、showcase `hover` で `partial=0 / full=1801`、理由 1（前フレームが動作中）と 6（動作中のアニメ項目）が各 `900`。つまり **transition 中の項目があると毎フレーム全面描画**になる。`inherited` は `partial=1599 / full=2` で部分描画が効いており、paint `2.19 ms` の大半は文字色変更によるタイル更新である。

**改善の方向**: 前フレームで動作中だった項目を記録し、遷移中の項目だけを「変更」として扱う。動作中項目のタイルだけを部分再描画し、全面描画に戻す条件を「項目数が変わった」「新規に動作開始」に限定する。これが P7 である。

#### 9.6.7 全木走査と未計上時間

`varied inherited` の未計上 `1.78 ms`（18%）、`varied child` の未計上 `0.93 ms`（18%）は、`LayoutBox.reusableBoxes` の `IdentityHashMap` 構築（`collectReusableBoxes` 約 1323 行）、`linkParents`（約 173 行）、`resetPlacements`、`clearAllSubtreeMarks`（約 1500 行）、`markLayoutChangesAlong` などの全木走査に対応する。JFR では `IdentityHashMap.resize` 1.4%、`collectReusableBoxes` 2.2% exclusive / 4.8% inclusive、`resetPlacements` 2.7% exclusive が確認できる。**ノード索引の配列・親への逆参照・増分マーク**に置き換えれば、フレーム全体で 5〜10% の短縮が見込める。

### 9.7 改善点（優先順位 P1〜P8）

| ID | 優先 | 対象 | 変更 | 期待効果（見込み） | 検証 |
| :-- | :--: | :-- | :-- | :-- | :-- |
| **P1** | ✅ 完了（11-12） | `DisplayListBuilder.canPatchBoxes` / `isCulledNoop` / `offscreen` | 箱ごとの「画面外」「移動祖先あり」親走査をキャッシュ。`UiView` と `patchBoxes` の二重判定を解消。`MOVING_PROPS` の文字列照合を `StyleFacts.hasMovingProps` に置換。単一パス `preparePatchBoxes` で重複排除・降順ソート・重なり判定を一元化 | varied `inherited` `10.20 → 7.92 ms`（p50 `7.78 ms`、-22.4%）、large `inherited` `3.50 → 3.07 ms`（p50 `2.97 ms`）。確保量 `2455.5 → 1970.2 KB/frame`（-485 KB/frame） | §9.3 の varied/large `inherited`、パッチ命中 100%、`DisplayListSlicePatchOptimizationTest`、`DisplayListSlicePatchTest`、`:core:test` 全件グリーン |
| **P2** | ✅ 完了（11-12） | `BlockLayoutEngine.placeLine`・`InlineFragment`・`updateScrollExtent`・`scanFloats` | 後続行の断片を再利用（可変フィールド更新）。先頭/末尾文字から `isNotBlankFast` で `isBlank` を短絡。`updateScrollExtent` のインデックスループ化と末尾通常フロー（`lastInFlow`）の逆順走査。祖先限定の局所 float 無効化（`invalidateFloats`）。外側マーカー・絶対配置・親同期のフラグ化で不要なループ走査を完全短絡 | PatchHitRateRatchetTest の child アロケーション 421 KB → 306 KB（-27%）。large child 確保 896.9 KB/frame。placeOutsideMarkers 走査の完全消滅 | `BlockLayoutOptimizationTest`、`FloatContainmentMemoTest`、`PatchHitRateRatchetTest`、`:core:test` 1,933 件全件グリーン |
| **P3** | 🥈 高 | `DisplayListBuilder.survivesTranslation` / `match`、`UiView.recomputeLayout` | 表示項目の座標を箱原点からの相対値で保持し、平行移動だけなら項目を再利用。影・グラデーション・レイヤーもオフセット付きで再利用。`BoxMemo` の幅キーを見直し、幅変更で内容が同じ箱の測定を再利用 | showcase `resize` `1.05 → ~0.8 ms`（display `0.42 → <0.2`）、JMH `0.78 → ~0.6`。`MATCHFAIL` reason 4 を `112 → <30` | §9.3 の `resize`、JMH `layoutWidthChange`、表示リスト一致テスト |
| **P4** | 🥈 高 | `ComputedStyle.sameDeclarations` / `StyleMap.equals`、`CssValue.dependsOnViewport`、`ComputedStyle.facts`、`InteractionState.register` | 同一性 → 64-bit 宣言ハッシュ → 配列比較の順に短絡。`facts` と `dependsOnViewport` をスタイル単位で 1 回だけ計算。状態フラグが変わらない要素の `register` を省略 | varied `fontsize` restyle `6.79 → ~5.0`、measure `9.59 → ~8.5`、確保 `-10%`。JFR `Arrays.equals` `<4%`、`StyleMap.equals` `<5%` | §9.3/§9.5 の varied `fontsize`、スタイル一致テスト |
| **P5** | 🥉 中 | `TextShaper`・`LineBuilder`・`FontFace.naturalAdvance` | 成形済みランと改行位置を `(text, font, size, width bucket)` でキャッシュ。幅変更で再成形しない。`isAlreadyNormal` の結果を文字列/スタイルに保持。`naturalAdvance` を語単位でキャッシュ | varied `fontsize` measure `9.59 → ~6.5`、`newLine` `<2%`。large `fontsize` `7.63 → ~6.5` | §9.3/§9.5 の `fontsize`、テキスト描画一致テスト |
| **P6** | 🥉 中 | `LayoutBox.reusableBoxes` / `linkParents` / `resetPlacements` / `clearAllSubtreeMarks` / `markLayoutChangesAlong` | `IdentityHashMap` をノード索引の配列に置換。親への逆参照を保持し、`linkParents` の全走査を廃止。変更マークを増分更新。部分木だけの再配置で `resetPlacements` の全走査を避ける | 未計上 `16〜19% → <10%`、フレーム全体 `-5〜10%`、`IdentityHashMap.resize` `<0.5%` | JFR の `collectReusableBoxes` `<1%`、レイアウト一致テスト |
| **P7** | 🥉 中 | `SoftwareRenderBackend` の全面描画理由 1/6 | 前フレームで動作中だった項目を記録し、遷移中の項目だけを「変更」として扱う。動作中項目のタイルだけを部分再描画。全面描画に戻す条件を「項目数が変わった」「新規に動作開始」に限定 | showcase CPU `hover` `1.77 → <1.5`、遷移中も `partial>0` を維持。`inherited` `2.62` は維持 | `DAMAGE-WHY` の partial/full、`PartialRepaintPixelTest`、`VolatileItemPartialRepaintTest` |
| **P8** | 🔒 横串 | `InlineFragment`・`LineItem`・`CssValue[]` コピー・インライン style の解析 | 断片・行項目を再利用。配列を事前サイズ確保。解析済みインライン style をノードに保持。防御的コピーを廃止 | varied `fontsize` `25.8 → <15 MB/frame`、p99 `-20%`。large `fontsize` `7.3 → <6 MB` | `AllocationRatchetTest`、FineProfile の確保量、GC ログ |

**P1 と P2 を先に行う理由**: どちらも「パッチは命中しているが、その前後の処理が重い」という同じ構造で、R2/R3 の KPI に直結する。P1 は `varied inherited` の 26.4%、P2 は `varied child` の 26.3% を直接狙う。P3 は W5、P4/P5 は `varied fontsize` の確保量と `inherited` の残り、P6〜P8 は横串の底上げである。

### 9.8 KPI 判定（2026-11-12）

| KPI | 目標 | 実測 | 判定 |
| :-- | :--: | :--: | :--: |
| large 2,000 `child` GL | `< 3 ms` | `1.44` | ✅ |
| large 2,000 `hover` GL | `< 1.0 ms` | `0.30` | ✅ |
| large 2,000 `fontsize` GL | `< 10 ms` | `7.63` | ✅ |
| large 2,000 `inherited` GL | 前回 `3.54` 以下 | **`3.07`**（p50 `2.97`） | ✅（P1 完了） |
| varied 2,000 `child` GL | Chrome 並み `~3.67` | `5.15` | ❌ |
| varied 2,000 `inherited` GL | Chrome 並み `~3.55` | **`7.92`**（p50 `7.78`） | 🔧（P1 で 10.20→7.92、-22%） |
| varied 2,000 `fontsize` GL | Chrome 以下 `42.1` | `30.77` | ✅ |
| showcase `resize` | `< 0.2 ms` | `1.05`（JMH `0.78`） | ❌ |
| showcase CPU `hover` | `< 1.5 ms` | `1.77` | ❌ |
| showcase CPU `inherited` | `< 4 ms` | `2.62` | ✅ |
| varied `fontsize` 確保 | `< 5 MB` | `25.8 MB` | ❌ |
| large `child` 確保 | `< 2 MB` | **`896.9 KB`**（前回 `0.94 MB`） | ✅ |
| large `hover` 確保 | `< 300 KB` | `39 KB` | ✅ |

**総合**: 同形リストの時間 KPI はほぼ達成した。残るのは **varied の `child`/`inherited`、showcase の幅変更、CPU transition、varied `fontsize` の確保量**。P1 により varied `inherited` は 10.20 → 7.92 ms へ 2.28 ms 縮小、P2 により断片再利用・空白判定短絡・マーカー走査短絡・局所 float 無効化が完了し、`PatchHitRateRatchetTest` の child アロケーションは 421 KB → 306 KB（-27%）へ削減された。今後の主戦場は `varied-list-2000`（特に child の小数平行移動・fontsize の P4/P5）と showcase（P3 幅変更・P7 CPU）である。

#### 9.8.1 まだ負けている項目・未計測項目

> **2026-11-12 夜の更新（§10.2）**: 下表のうち large 500 の `inherited`（`0.77`）・`hover`（`0.26`）・`style`（`0.33`）と large 2,000 `text`（`0.34`）は Chrome 以下（同等）になり、varied `inherited` は `6.81`、varied `child` は `4.60`、showcase CPU `hover` は `1.66 ms` へ縮んだ。最新の負け項目と原因は §10.1・§10.2 を正とする。

§9.8 の表は今回測った KPI の抜粋であり、「全項目で勝った」という意味ではない。現時点で負けている、または今回測っていない主な項目は次のとおり。

| 項目 | 現状 | 比較 | 状態 |
| :-- | :--: | :--: | :--: |
| large 500 `inherited` GL | `3.59 ms` | Chrome `0.96`（3.8x） | ❌ 前回（§6.7） |
| large 500 `hover` GL | `0.72 ms` | Chrome `0.35`（2.0x） | ❌ 前回（§6.7） |
| large 500 `style` GL | `0.65 ms` | Chrome `0.33`（2.0x） | ❌ 前回（§6.7） |
| large 500 `fontsize` GL | `4.24 ms` | Chrome `3.45`（1.2x） | ❌ 前回（§6.7） |
| large 500 `child` GL | `2.18 ms` | Chrome `child250` `1.98`（1.1x） | 🔧 前回（条件補正後） |
| large 2,000 `text` GL | `1.61 ms` | Chrome `1.02`（1.6x） | ❌ 今回未再計測 |
| large 2,000 `inherited` GL | **`3.07 ms`** | Chrome 2,000 未取得（500 は `0.96`） | ⚠️ 未比較（P1 で 3.50→3.07） |
| large 2,000 `scroll` GL | `0.56 ms` | Chrome `0.46`（1.2x） | ❌ 前回（§6.7） |
| large 2,000 `open` | `25.5 ms` | Chrome `19.8`（1.3x） | ❌ 前回（null） |
| varied 2,000 `child` GL | `5.15 ms` | Chrome `3.67`（1.4x） | ❌ 今回 |
| varied 2,000 `inherited` GL | **`7.92 ms`** | Chrome `3.55`（2.2x） | 🔧 今回（P1 で 10.20→7.92） |
| showcase `resize` | `1.05 ms` | Chrome `0.084`（12.5x。**測る範囲が違う比較**。同じ範囲では約 4〜5x、§10.10） | ❌ 今回 |
| showcase CPU `hover` | `1.77 ms` | Chrome CPU `1.55`（1.1x） | ❌ 今回 |
| showcase `open` | `6.19 ms` | Chrome `5.0`（1.2x） | ❌ 前回 |
| varied ヒープ保持 | `51.8 MB` | Chrome +`26 MB`（約2x） | ❌ 前回（§8.6.5） |
| large 2,000 ヒープ保持 | `9.3 MB` | Chrome +`5 MB`（約1.9x） | ❌ 前回（§8.6.5） |
| p99 / 分散 | 未計測 | — | ⚠️ |
| `softwarePaint` showcase JMH | `4.38 ms` | 旧 `1.97`（退行ではないが高い） | ⚠️ 要調査 |

**CPU バックエンド（参考）**: large 500 行の CPU は `hover 2.28` / `text 1.29` / `style 1.66` / `inherited 8.00` / `fontsize 7.76` / `child 5.30 ms` で、Chrome の GPU 合成込み全 CPU（`0.36` / `0.49` / `0.33` / `0.96` / `3.45` / `0.54`）より遅い。ただし CPU バックエンドはソフトウェアラスタで、Chrome の GPU 合成との単純比較はできない（参考値）。showcase CPU `inherited` は `2.62 ms` で目標 `< 4 ms` は満たすが、Chrome CPU の同操作は未取得で、Chrome 比較は未確定である。

### 9.9 次の作業順序

> **2026-11-12 夜の改訂**: 次の作業順序は §10.5（Q1 座標の固定小数点・親相対化 → Q1a / Q2 / Q3 → Q4 → Q5・Q6）を正とする。**Q1 第 1 段・Q1a は完了（§10.7）。Q2 ②（差分メモ）も完了（§10.8）。Q6 は一部のみ（§10.9）。次は Q3。**P3 は実装済みだが、効果は表示リスト込み・ホストの resize 経路（`resizeexact`）でしか見えず、フレーム時間はほぼ不変だった（§10.3.3）。

1. **P1（パッチ判定キャッシュ）**: ✅ **完了（2026-11-12）**。`preparePatchBoxes` 一元化、`StyleFacts.hasMovingProps`、親走査キャッシュ、`UiView` 二重判定撤廃。varied `inherited` GL `10.20 → 7.92 ms`（-22.4%）、large `inherited` GL `3.50 → 3.07 ms`（p50 `2.97 ms`）、ヒープ確保量 `-485 KB/frame`。
2. **P2（`placeLine`/`InlineFragment` 再利用）**: ✅ **完了（2026-11-12）**。断片インプレース再利用、`isNotBlankFast` 短絡、`placeOutsideMarkers` 短絡、`updateScrollExtent` 高速化、局所 float 無効化。`PatchHitRateRatchetTest` child 確保量 421 KB → 306 KB（-27%）、large child 確保 896.9 KB/frame。
3. **P3（表示項目の相対座標・平行移動再利用）**: W5 の本命。次期最優先として着手。showcase resize `1.05 → ~0.8 ms`、JMH `layoutWidthChange` `0.78 → ~0.6 ms` を狙う。
4. **P4/P5（スタイル等価判定・テキスト成形キャッシュ）**: `varied fontsize` の確保量と `inherited` の残り。
5. **P6/P7/P8（全木走査・CPU ダメージ・確保量）**: 横串。P1〜P5 の後で、フレーム全体の底上げとして実施する。
6. 各施策の完了ごとに §9.3 と §9.5 を再計測し、§9.8 を更新する。時間ベンチは不安定なので、**パッチ命中・走査項目数・確保量のカウンタを R7 のラチェットに追加**して退行を防ぐ。

### 9.10 前回台帳との差分（最近のコミットの効果）

| 項目 | 前回値 | 今回値 | 変化の要因 |
| :-- | :--: | :--: | :-- |
| large 2,000 `child` GL | `13.77`（§6.7.3）→ `1.21`（§8.6.6） | **`1.44`**（確保 896.9 KB） | 窓付き表示リスト（§8.6.2）と fit/measure 再利用、P2 の断片再利用で確保量をさらに抑制 |
| large 2,000 `fontsize` GL | `18.93`（§6.7.3）→ `6.6`（§8.6.6） | **`7.63`** | スタイル共有（§8.6.6）と fit/measure 再利用で Chrome `11.3` 以下を維持。前回 `6.6` からは +16% だが、実行ごとのばらつきと JIT の影響を含む |
| large 2,000 `inherited` GL | `3.50`（§9.8） | **`3.07`**（p50 `2.97`） | **P1 実装**（パッチ判定一元化・`hasMovingProps`・親走査キャッシュ） |
| JMH `layoutWidthChange` | `1.039` | **`0.777`** | `dbcd523`（リサイズで表示リストのビルダーと箱ごとの記憶を保持）＋ fit 再利用 |
| varied `fontsize` GL | `43.8`（§8.6.6） | **`30.77`** | fit/measure 再利用と幅変更経路の修正。Chrome `42.1` を下回る |
| varied `child` GL | `4.9`（§8.6.6） | **`5.15`** | 横ばい（±10%）。P2 で断片確保量 -27%、マーカー走査消滅。小数の平行移動近道（P3/将来拡張）へ |
| varied `inherited` GL | `10.20`（§9.8） | **`7.92`**（p50 `7.78`） | **P1 実装**（二重判定撤廃・`preparePatchBoxes` 単一パス化・`offscreen` 高速化で **-2.28 ms, -22.4%**） |

### 9.11 P1 実装記録（2026-11-12 完了）

#### 9.11.1 実施内容
1. **二重判定の撤廃**:
   - `UiView.restyleAndUpdate` の `canPatchBoxes(changedBoxes)` 事前呼出を撤廃し、`patchBoxes(changedBoxes)` に一本化。
2. **単一パス化（`preparePatchBoxes`）**:
   - `canPatchBoxes` と `patchBoxesUnprofiled` で重複していた `isCulledNoop` 走査・`LinkedHashSet`/`ArrayList` の多重生成・二重ソートを `preparePatchBoxes` に統合。
   - `boxes.size() > 1` 時の降順ソート後の in-place 重複排除とスライス重なり判定（`b.dlEnd > prev.dlStart`）を 1 パスで処理。
3. **`MOVING_PROPS` の高速ビット化と親走査キャッシュ**:
   - `PaintOrder.StyleFacts` に `boolean hasMovingProps` を導入。変形・アニメーション・フィルター等の 8 プロパティ文字列比較をスタイル解決時の 1 回のみに集約。
   - `hasMovingAncestor` に直近親の走査結果キャッシュ（`cachedParentForMovingCheck`）を導入。共通親を持つリスト項目群（2000 行など）の祖先走査を O(1) に短絡。
4. **テスト追加と検証**:
   - `DisplayListSlicePatchOptimizationTest`: 重複要素の重複排除・降順ソート維持・全画面外時の安全な no-op 処理・スライス重なり検出・二重呼び出し防止の単体テストを追加。
   - `:core:test` 1,923 件全件グリーン、`LayerDependencyRatchetTest` 適合、`PatchHitRateRatchetTest` パッチ命中率 100%（fallback 0）を確認。

### 9.12 P2 実装記録（2026-11-12 完了）

#### 9.12.1 実施内容
1. **`InlineFragment` のインプレース再利用とプーリング**:
   - `LayoutBox` に `fragmentCursor`、`startFragmentPass()`、`finishFragmentPass()`、`addFragment(...)` を導入。
   - 既存の `inlineFragments` リストの要素を `set(...)` で再利用し、行配置ごとの `new InlineFragment` インスタンス生成と `ArrayList.add` の再確保を完全に抑止。
   - 余分な断片は `finishFragmentPass()` で末尾からトリム。`PatchHitRateRatchetTest` での child アロケーションが 421,374 B → 306,062 B へ 115 KB/op（-27%）削減。
2. **`isNotBlankFast` による `isBlank` 文字列走査の短絡**:
   - `String.isBlank()` が内部で行う `CharacterData.of(ch).isWhitespace(ch)` 全走査（JFR 8.3% inclusive）を、先頭および末尾文字が ASCII 表示可能文字（0x21〜0x7e）であれば即座に non-blank と判定して短絡。
   - 全角空白（\u3000）やノーブレークスペース（\u00a0）等の Unicode 空白に対しても `!s.isBlank()` フォールバックにより完全な互換性を担保。
3. **`LayoutBox.updateScrollExtent` のインデックスループ化と末尾通常フロー（`lastInFlow`）の逆順走査**:
   - `new ScrollExtent` 2 インスタンスの生成をローカルプリミティブ化。
   - 葉ノード（子なし・エンジン状態なし・インラインなし）の早期 return を導入。
   - マージン相殺貫通時の `lastInFlow` 検索をリスト末尾からの逆順走査にし、最初の通常フロー要素発見時に即 `break`。
   - `children` および `inlineFragments` の走査を `ArrayList` インデックスループ化し、Iterator 生成を完全撤廃。
4. **祖先限定の局所 float 無効化（`invalidateFloats`）**:
   - `BoxTreeBuilder.insertBuiltChild` および `removeBuiltChild` で、全体無効化 `LayoutBox.bumpShapeEpoch()` を呼んでいたのを改め、変更ノードの親からルートへの祖先パスのみ局所無効化する `LayoutBox.invalidateFloats(parent)` を導入。
   - 2,000 行リスト等において、無関係な 1,999 行の既存兄弟要素の `containsFloats` キャッシュが保持されるように改善。
5. **外側マーカー・絶対配置・親同期フラグによる走査短絡**:
   - `LayoutBox` に `hasOutsideMarker`、`hasAbsoluteChild`、`needsAdoption` を導入。
   - `BlockLayoutEngine.placeOutsideMarkers`: `hasOutsideMarker` が false のときは `children` 走査を行わずに即 return（Hotspot プロファイルから `placeOutsideMarkers` が完全に消滅）。
   - `LayoutBox.layout`: 親リンク同期済みのときは `adopt` ループをスキップ、`containsFloats` が false のときは `hasFloatChild` 走査をスキップ、`hasAbsoluteChild` が false のときは静的位置設定および絶対配置レイアウト走査をスキップ。
   - 通常ブロック要素における 4 重の `children` 走査を完全に短絡。
6. **`arrangeFlow` における段組シフト判定の短絡**:
   - `shifts == null` の通常フロー時、`shiftOf` メソッド呼び出しと配列加算を完全スキップ。
7. **テスト追加と検証**:
   - `BlockLayoutOptimizationTest`: `isNotBlankFast` の Unicode 空白を含む網羅的一致テスト、`InlineFragment` の再利用ライフサイクル、葉ノード短絡計算一致、子フラグ管理、局所 float 無効化の単体テストを追加。
   - `:core:test` 1,933 件全件グリーン、`LayerDependencyRatchetTest` 適合、`FloatContainmentMemoTest` 適合、`PatchHitRateRatchetTest` パッチ命中率 100%（fallback 0）を確認。

---

## 10. 2026-11-12（夜）再考察: なぜ遅いのか — 根本原因の再定義と裏付け計測

> §9 の P1〜P4 実装後の HEAD（`cb6a734`）で、§9 の改善点リストを「個々のホットスポット」から「それを生む構造」へ一段掘り下げた。各仮説は **A/B 実験または JFR で裏付けたもの**だけを採用し、外れた仮説も記録する（§10.4）。

### 10.1 結論（要約）

`:core` の残る遅さは、個別の遅い関数ではなく、次の **4 つの構造上の原因**から出ている。§9 で P1〜P8 として並べた症状の大半は、このどれかに帰着する。

| # | 根本原因 | 典型的な症状（§9 の ID） | 裏付け（§10.3） | 効く文書 / 操作 |
| :-- | :-- | :-- | :-- | :-- |
| **C1** | **座標が「絶対値の float（`LayoutBox`）」と「丸めた絶対値の int（`DisplayItem`）」の二重表現**。平行移動の再利用が**整数ぶんの移動**でしか正しくならないため、小数の移動（em・`line-height: normal`・中央寄せ）が 1 つでもあると、後続の全行を置き直し・項目を作り直す | P2（`placeLine`）・P3（`MATCHFAIL 4`）・P8（確保量） | **A/B: 移動量を整数にするだけで varied `child` が 4.17 → 2.16 ms（-48%）、確保 4,896 → 277 KB/frame（-94%）**、`shifted` 0 → 1,750 | varied `child`、幅変更、行の高さが小数の全リスト |
| **C2** | **算出スタイルが約 190 枠の平坦な `CssValue[]`** で、変更の分類（継承へ伝わるか・レイアウトに効くか・幾何だけか・トランジション対象か）のたびに**全枠を比べ直す**。1 要素あたり 2〜3 回、しかも同じ (旧, 新) の組を要素ごとに繰り返す | P4（`Arrays.equals` は宣言ハッシュで解消済み。残りは `diff`） | **JFR: `ComputedStyle.diff` が varied `inherited` で 20.3%、large 500 `inherited` で 33.3%（exclusive・首位）** | すべての `inherited` / `fontsize` / テーマ切替 |
| **C3** | **要素ごとの祖先走査**（`nearestSizeContainer`・`hasMovingAncestor`）が、親から子へ下る再帰の中で毎要素ゼロからやり直される。O(N×深さ) | P1 の残り・P6 | **JFR: varied `inherited` で `nearestSizeContainer` 9.4%・`hasMovingAncestor` 9.9%（P1 の直近親キャッシュは、入れ子のある行では外れる）** | 入れ子の深い不均一な文書 |
| **C4** | **無効化の粒度が粗い**: `font-size` の変化は `isGeometryOnlyChange` が無条件に false を返すため**構造変更（箱の木の作り直し）**に格上げされる | P5 の手前 | **計測: varied `fontsize` で `structural=1000`（毎フレーム）、`box-tree` 2.13 ms/frame（7.8%）** | `fontsize`・ズーム・ルート em 変更 |

加えて、**計測の経路の問題（M1）**が見つかった。§9.7 P3（`af36a06`）が有効にした `exactTranslation` は `UiView.flushInvalidation` の resize 分岐でしか立たない。ところが `FineProfileMain resize` と JMH `layoutWidthChange` はどちらも `recomputeLayout` を直接呼ぶため、**P3 の経路を一度も通っていなかった**（§10.3.3）。

### 10.2 再計測（2026-11-12 夜、HEAD `cb6a734`、§9.2 と同条件）

| 文書 / 操作 | backend（frames） | mean / p50 (ms) | 確保 (KB/frame) | 段別 (ms) | §9 の値 → 今回 | Chrome |
| :-- | :--: | :--: | :--: | :-- | :--: | :--: |
| varied-2000 `inherited` | GL (500) | **`6.81` / `6.71`** | `2,897` | restyle `5.46` / display `0.77` / paint `0.54` | `7.92` → **`6.81`**（-14%、P4 の宣言ハッシュ） | `3.55`（1.9x） |
| varied-2000 `child` | GL (500) | **`4.60` / `4.54`** | `4,969` | measure `0.43` / place `2.76` / display `0.30` / paint `0.36` / 未計上 `0.75` | `5.15` → `4.60` | `3.67`（1.25x） |
| varied-2000 `fontsize` | GL (400) | **`27.36` / `26.98`** | `26,319` | box `2.13` / restyle `5.36` / measure `9.27` / place `4.25` / display `2.46` | `30.77` → `27.36` | `42.1` ✅ |
| large-500 `inherited` | GL (800) | **`0.77` / `0.72`** | `810` | restyle `0.36` / display `0.12` | `3.59`（§6.7）→ `0.77` | `0.96` ✅ |
| large-500 `hover` | GL (800) | **`0.26` / `0.24`** | `42` | restyle `0.03` | `0.72` → `0.26` | `0.35` ✅ |
| large-500 `style` | GL (800) | **`0.33` / `0.31`** | `45` | restyle `0.08` | `0.65` → `0.33` | `0.33` ＝ |
| large-2000 `text` | GL (800) | **`0.34` / `0.30`** | `41` | measure `0.03` / place `0.10` | `1.61` → `0.34` | `1.02` ✅ |
| large-2000 `inherited` | GL (700) | **`2.39` / `2.33`** | `979` | restyle `1.78` / display `0.25` | `3.07` → `2.39` | 未取得 |
| showcase `resize`（`recomputeLayout` 直呼び） | null (2000) | **`0.98` / `0.89`** | `1,471` | measure `0.36` / display `0.39` | `1.05` → `0.98` | `0.084`（12x） |
| showcase `hover` | CPU (1200) | **`1.66` / `1.63`** | `284` | paint `≈1.57`（`partial=0 / full=1801`、理由 1・6 が各 900） | `1.77` → `1.66` | CPU `1.55` |

**§9.8.1 の「負け」表の更新**: large 500 の `inherited` / `hover` / `style`、large 2,000 `text` は **Chrome 以下（または同等）になった**。残る負けは **varied `inherited`（1.9x）・varied `child`（1.25x）・showcase 幅変更（12x）・showcase CPU `hover`（1.07x）**と、メモリ保持量・`open`（今回未計測）である。

### 10.3 裏付け計測

#### 10.3.1 C1: 小数の移動量が平行移動の近道を殺す（A/B）

`LayoutBox.layout` の部分木平行移動（[`LayoutBox.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/LayoutBox.java) 約 1649 行）は `isIntegral(parentY - lastLayoutY)` を条件にしている（float の絶対座標に小数を足すと、置き直した結果と丸めが一致しない恐れがあるため）。表示項目側の `DisplayListBuilder.match` も `isIntegerTranslation(dx/dy)` で同じ制約を持つ。varied の行は `font-size: 80〜130%` と `line-height: normal` で高さが小数になるため、挿入した行の高さも小数になり、後続 1,750 行で近道が一度も効かない、という仮説を立てた。

**実験**: `FineProfileMain` に `childfixed` シナリオを追加した。`child` と同じ位置（item-250 の前）に、高さを `20px` に固定した行（下枠 1px を足して移動量 21px＝整数）を挿入・削除する。それ以外は同一。カウンタ `LAYOUTSKIP`（`LayoutBox.layoutsShifted()` / `layoutsSkipped()`）を新たに出力する。

| varied-list-2000（null・600 frames） | mean (ms) | place (ms) | 確保 (KB/frame) | 平行移動した部分木 / frame |
| :-- | :--: | :--: | :--: | :--: |
| `child`（挿入行の高さが小数） | `4.17` | `2.78` | `4,896` | **`0`** |
| `childfixed`（挿入行の高さが整数） | **`2.16`（-48%）** | **`0.85`（-69%）** | **`277`（-94%）** | **`1,750`** |
| （参考）large-2000 `child` | `1.10` | `0.49` | `837` | `0`（行高は整数でも挿入行が小数） |

**読み方**: 原因は「1,750 行を置き直す」ことそのものではなく、**置き直さずに済ませる近道が、移動量が小数というだけで無効になる**ことにある。§9.6.2 の「断片の再生成」（`ArrayList.add` / `addFragment` / `resetPlacements`）と確保量 4.9 MB/frame は、この近道が外れた後の症状である。`childfixed` の 2.16 ms は null バックエンドの値だが、GL 送出（約 0.36 ms）を足しても Chrome `3.67 ms` を下回る見込み。

**副次的な発見**: 近道が外れたとき、`LayoutBox.layout` は子に `resetPlacements(child, -1)` を呼び、これが `inlineFragments.clear()` する（約 1376 行）。そのため **P2 の断片インプレース再利用（`addFragment` の `fragmentCursor`）は、この経路では毎回空のリストから始まり、再利用が 1 件も効かない**。varied `child` の JFR で `LayoutBox.addFragment` 11.4%・`ArrayList.grow`/`Arrays.copyOf` 6.3〜6.8% が残っているのはこのため。

#### 10.3.2 C2 / C3: 再スタイルの中身（JFR）

varied-2000 `inherited`（null・1,500 frames、808 samples）:

| 種別 | フレーム | 割合 | 対応 |
| :-- | :-- | :--: | :-- |
| exclusive | `ComputedStyle.diff` | **20.3%** | C2（`propagationOf` 13.7% incl. ＋ `computeLayoutAffectingChange` 6.6% incl.） |
| exclusive | `StyleStateAutomaton.compress` | 11.3% | `StateStyleMemo.peek` → `StateTable.attachment`。命令数は数個なので、**要素ごとの `StateTable` を読むキャッシュミスがここに計上されている**と読む（散在オブジェクトのポインタ追い。§8.1 F2 と同型） |
| exclusive | `StateLookup.nearestSizeContainer` | 6.9%（incl. 9.4%） | C3（`containerFor` が要素ごとに祖先をたどる） |
| exclusive | `TextShaper.isAlreadyNormal` | 6.1% | 文字色だけの変化でも、`restyleGeneratedBoxes` が文字列の正規化判定を全文字に対してやり直す |
| exclusive | `DisplayListBuilder.hasMovingAncestor` | 5.9%（incl. 9.9%） | C3（P1 の直近親キャッシュは、バッジ・太字・サブ行を持つ行の中の箱では外れる） |
| exclusive | `CssValue.dependsOnViewport` | 4.7%（incl. 5.0%） | `cacheStyle` → `rawDependsOnViewport`（P4 の `valuesViewport` キャッシュは生宣言の表には効いていない） |
| exclusive | `CharacterDataLatin1.toLowerCase` | 2.6% | `cacheStyle` が毎回 `style` 属性を `toLowerCase().contains("inherit")` する |
| inclusive | `RenderPipeline.restyleBoxRecursive` | 86.8% | — |
| inclusive | `RenderPipeline.styleEntry`（カスケード＋算出の本体） | **16.5%** | **カスケード自体は 1/6 にすぎない** |

large-500 `inherited`（108 samples、参考）: `ComputedStyle.diff` **33.3%** exclusive（首位）、`compress` 9.3%、`hasMovingAncestor` 5.6%。**全行が同じスタイルを共有する文書でも diff が首位**であり、同じ (旧スタイル, 新スタイル) の組の diff を 500 回繰り返していることを示す。

`ComputedStyle.diff`（[`ComputedStyle.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/ComputedStyle.java) 約 358 行）は全枠を走査し、参照が違えば `text.equals` まで比べ、差分ごとにラムダ（捕獲配列つき）を呼ぶ。呼び出し元は `RenderPipeline.propagationOf`・`isPositionOnlyChange`・`isGeometryOnlyChange`、`StyleResolver.computeLayoutAffectingChange`、`StyleAnimator.startTransitions`、`StyleStateAutomaton.classify` の 6 か所で、**同じ 1 要素の再スタイルで最大 3 回**走る。

#### 10.3.3 M1: P3 は計測経路に乗っていなかった

showcase `resize` の `MATCHFAIL 4=112`・`translated=0` が P3 実装前後で**完全に同じ**だったため、原因を調べた。

1. **仮説 1（棄却）**: 1px の幅変更で中央寄せの箱が 0.5px 動くので、整数移動の条件に落ちる → `resize2`（2px 刻み）を追加して計測したが、`MATCHFAIL 4=112`・`translated=0` で**変化なし**。
2. **仮説 2（確認）**: `exactTranslation` は `UiView.flushInvalidation` の resize 分岐（約 1217 行）でしか立たない。ベンチは `recomputeLayout` を直接呼ぶので、フラグが立たない。ホストの経路（`StandaloneView.resize` → `requestResize`）を通す `resizeframe` は、`resizeDue` の壁時計による間引き（`RESIZE_THROTTLE_FACTOR`）で連続フレームでは 1 度もレイアウトされない（mean `0.0016 ms`）。そこで resize 分岐の中身を直接再現する `resizeexact`（リフレクションで `exactTranslation` を立てて `recomputeLayout`）を追加した。

| showcase 幅変更（null・2,000 frames） | mean (ms) | display (ms) | 確保 (KB) | BOXMEMO exact / translated / miss | MATCHFAIL 4 |
| :-- | :--: | :--: | :--: | :--: | :--: |
| `resize`（直呼び・従来の計測） | `0.98` | `0.39` | `1,471` | `62 / 0 / 164` | `112` |
| `resize2`（2px 刻み） | `0.97` | `0.38` | `1,461` | `62 / 0 / 164` | `112` |
| **`resizeexact`（ホストの resize 分岐と同じ）** | `0.95` | **`0.33`（-13%）** | **`1,194`（-18%）** | **`103 / 41 / 82`** | **`54`** |

**読み方**: P3 自体は効いており、ミスが半減し確保量も 18% 減った。しかし**フレーム時間はほぼ変わらない**（0.98 → 0.95）。理由は 2 つある。① 残る `MATCHFAIL 4=54` は小数の移動（C1）。② 幅変更の残り時間の主因は表示リストではなく**測定**（`measure` 0.36〜0.37 ms。JFR では `GridLayoutEngine.measureInArea` → `FlexLayoutEngine.remeasure` の入れ子が 36〜41% inclusive）。Chrome `0.084 ms` との 11 倍の差（**測る範囲が違う。同じ範囲では約 4〜5 倍。§10.10**）は、**内容の変わらない子孫の測定を幅変更で再利用できていない**ことによる（§9.5.4 の `BoxMemo miss` と整合）。

**是正**: 以後、幅変更の KPI は `resizeexact`（またはホスト経路）で取る。JMH `layoutWidthChange` もレイアウト単体の指標であり、P3 の効果は表示リスト込みの計測でしか見えないことを明記する。

#### 10.3.4 C4: `font-size` 変化が構造変更になる

varied `fontsize` の実フレームでは `restyle: structural=1000`（ウォームアップ込み累計 = 毎フレーム）で、`box-tree` 段が 2.13 ms/frame を使う。[`RenderPipeline.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/RenderPipeline.java) の `isGeometryOnlyChange`（約 443 行）は、`font-size` か root の `font-size` が違えば即 false を返す。呼び出し元（約 755 行）はこれを `structural = true` にするので、**`display`・`content`・`float`・`position` が不変でも箱の木を作り直す**。`font-size` は箱の構成を変えない（寸法だけを変える）ので、幾何だけの変化として `layoutChangedBoxes` に入れれば `box-tree` 段は消える。表示リストは `MATCHFAIL 2=31`（スタイルの変化）で作り直しになるが、これは正当。

### 10.4 外れた仮説（記録）

| 仮説 | 結果 |
| :-- | :-- |
| varied `inherited` は全行がインライン `style` を持ち `ShareCache` を使えないので、カスケードと算出が支配的 | ✗ `StateStyleMemo` の表が当たり、カスケード＋算出（`styleEntry`）は 16.5% にとどまる。支配的なのは**算出後の比較・分類と祖先走査**（C2・C3） |
| showcase 幅変更の `MATCHFAIL 4` は、1px 変更で中央寄せが 0.5px 動くことが原因 | ✗ 2px 刻みでも同じ値。原因は計測経路（M1）。経路を合わせた後の残り 54 件は小数移動 |
| P2 の断片再利用で varied `child` の確保量は減っている | ✗ 近道が外れた経路では `resetPlacements` がリストを空にするので、再利用は効いていない（§10.3.1） |

### 10.5 改善項目（C1〜C4 から導く。§9.7 の P5〜P8 は維持し、以下を優先する）

| ID | 優先 | 対象 | 変更 | 見込み（計測からの推定） | 合否 |
| :-- | :--: | :-- | :-- | :-- | :-- |
| **Q1**（C1） | ✅ 第 1 段完了（2026-11-12、§10.7） | `LayoutBox` の座標・`InlineFragment`・`DisplayItem` | **座標を固定小数点（`LayoutUnit` = 1/64 px の int）にする**。加算が結合的になり、小数ぶんの平行移動が置き直しと**ビット単位で一致**するので、`isIntegral` / `isIntegerTranslation` の条件を「1/64 の倍数」へ緩められる（Chrome の `LayoutUnit` と同じ考え方）。中間段として、**部分木の位置を親からの相対値で持ち、絶対座標は描画の最終段で 1 回だけ足して丸める**（§8.2 の Paint 表の前段） | varied `child` `4.60 → ~2.5 ms`（`childfixed` の実測 2.16 ms が上限の目安）、確保 `4.9 MB → <0.5 MB/frame`。showcase 幅変更の `MATCHFAIL 4` `54 → ~0` | 全再構築との一致テスト（`DisplayListSlicePatchTest`・R7 乱択列）、`LAYOUTSKIP shifted` を `PatchHitRateRatchetTest` に追加 |
| **Q1a**（C1 の即効策） | ✅ 完了（2026-11-12、§10.7） | `LayoutBox.resetPlacements` | 近道が外れて置き直すときも `inlineFragments` を `clear()` せず、`fragmentCursor = 0` だけにして `finishFragmentPass` で切り詰める（P2 の再利用を置き直し経路でも効かせる） | varied `child` の確保 `-1〜2 MB/frame`、`addFragment`/`ArrayList.grow` `-10%` | `BlockLayoutOptimizationTest`、確保量ラチェット |
| **Q2**（C2） | ✅ ②のみ完了（2026-11-12、§10.8）。①③④は未着手 | `ComputedStyle.diff` と 6 か所の呼び出し元 | ① **1 回の走査で「継承へ伝わる／レイアウトに効く／幾何だけ／描画だけ」のビット集合を返す `diffSummary`** にし、`propagationOf`・`isLayoutAffectingChange`・`isGeometryOnlyChange` が共有する。② **(旧, 新) の同一性の組でメモ化**（同形リストでは 500 行が同じ組）。③ `declHash` が同じなら走査しない。④ 中期的には、算出スタイルを**継承グループ／非継承グループ（さらに box・visual・text…）の共有部分オブジェクト**に分け、比較をグループの参照比較（十数回）で済ませる（Blink の `ComputedStyleBase` のグループ化と同型） | large 500 `inherited` restyle `-30%`、varied `inherited` `6.81 → ~5.5 ms` | JFR で `ComputedStyle.diff` `<5%`、スタイル一致テスト |
| **Q3**（C3） | 🥈 | `restyleBoxRecursive`・`DisplayListBuilder.offscreen` | 「最寄りのサイズコンテナ」「動く祖先があるか」を**再帰の引数として親から子へ渡す**（O(N)）。祖先を毎要素たどらない | varied `inherited` `-10〜15%`（`nearestSizeContainer` 9.4% ＋ `hasMovingAncestor` 9.9% の大半） | JFR で両者 `<1%` |
| **Q4**（C4） | 🥈 | `RenderPipeline.isGeometryOnlyChange` | `font-size` の変化を、表示型・生成内容・`float`・`position` が不変なら**幾何だけの変化**として扱う（箱の木を作り直さない） | varied `fontsize` `box-tree 2.13 → ~0`（`27.4 → ~25 ms`）、large 2,000 `fontsize` も同様 | `structural` カウンタが `fontsize` で 0、全再構築との一致 |
| **Q5** | 🥉 | `restyleGeneratedBoxes`・`cacheStyle` | 文字に効くプロパティ（`white-space`・`text-transform` 等）が不変なら `TextShaper.isAlreadyNormal` を呼ばない。`inheritsExplicitly` は `style` 属性の版が変わったときだけ計算する。`rawDependsOnViewport` を生宣言表ごとにキャッシュする | varied `inherited` `-10%`（`isAlreadyNormal` 6.7% ＋ `toLowerCase` 2.6% ＋ `dependsOnViewport` 5.0%） | JFR |
| **Q6**（W5） | △ 一部のみ（2026-11-12、§10.9）。幅変更 `12x` は未解消 | `FlexLayoutEngine.remeasure`・`GridLayoutEngine.measureInArea` | 幅変更で、**内容の幅に依存しない子孫（固定幅・内容で決まる幅で、利用可能幅が min/max-content の外側にある箱）の測定を再利用**する。測定キャッシュの鍵を「利用可能幅」から「利用可能幅が属する区間（min-content〜max-content のどこか）」へ一般化する | showcase 幅変更 measure `0.36 → <0.15 ms` | `resizeexact` で計測、`LayoutKeyEquivalenceTest` |
| **M1** | 🔒 計測 | `FineProfileMain`・JMH | 幅変更の KPI を `resizeexact` で取る（追加済み）。ホスト経路の間引きで、`resizeframe` が「何もしないフレーム」を測ってしまう点を注記 | — | — |

**順序の根拠**: Q1 は唯一 **A/B で効果の上限を実測済み**（-48% / -94%）で、`child`・幅変更・確保量・将来の RTI（§8.4 の固定 extent）すべての前提になる。Q2・Q3 は `inherited` の 30% 以上を占め、変更が局所的で一致テストも既存のものが使える。Q4 は 1 関数の条件変更で `fontsize` の 8% を消せる。

### 10.6 「なぜ遅いのか」の総括

1. **「パッチが当たらない」段階はもう過ぎた**。パッチ命中は全シナリオで 100%（§9.3・§10.2）で、同形リストでは Chrome 以下になった。
2. 残る遅さは、**当たったパッチの内側で、計算を省くための前提（同一性・整数性・祖先の性質）が細かすぎる／壊れやすい**ことから来る。小数 1 つで平行移動が無効になり（C1）、スタイルが 1 つ変わると全枠を何度も比べ直し（C2）、要素ごとに祖先をたどり直し（C3）、`font-size` 1 つで箱の木を作り直す（C4）。
3. Chrome との差が残る 3 項目（varied `inherited` 1.9x、varied `child` 1.25x、幅変更 12x）は、それぞれ C2+C3、C1、C1+Q6 で説明できる。Chrome は固定小数点の座標と親相対の断片、グループ化された算出スタイル、レイアウト結果のキャッシュでこれらを構造的に避けている。
4. 計測の側にも穴があった（M1）。**実装の効果を測るベンチが、その実装の経路を通っているか**を、カウンタ（`BOXMEMO translated`・`LAYOUTSKIP shifted`）で毎回確かめる。

**再現コマンド**（§9.2 に追加）:

```powershell
.\gradlew.bat :core:profile "-Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain" "-Pprofile.args=childfixed varied-list-2000 600 null"
.\gradlew.bat :core:profile "-Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain" "-Pprofile.args=resizeexact showcase 2000 null"
.\gradlew.bat :core:profile "-Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain" "-Pprofile.args=inherited large-list 3000 null" "-Pprofile.jfr=build/prof/large500-inherited.jfr"
```

### 10.7 Q1 / Q1a 実装記録（2026-11-12 完了）

#### 10.7.1 方針の変更点（Q1 の「固定小数点化」を 2 段に分けた）

§10.5 の Q1 は、座標型を `LayoutUnit`（1/64 px の int）へ全面置換する案だった。`LayoutBox` / `InlineFragment` / `DisplayItem` / 全エンジンの `float` を書き換える規模で、一度に入れると一致テスト（1,950 件）の切り分けが難しい。そこで **float のまま、縦方向の寸法の源だけを 1/64 px の格子に載せる**第 1 段を先に実装した。float は「格子の倍数かつ絶対値 `< 2^18`（= 2^24 / 64）」なら加減算が厳密になるので、格子に載った部分木の小数ぶんの平行移動は、原点を変えて置き直した結果と**ビット単位で一致**する（結合的な加算の性質だけを借りる）。全面置換（第 2 段）は、下の「残課題」に回す。

#### 10.7.2 実施内容

1. **Q1a（断片の再利用を置き直し経路でも効かせる）**: `resetPlacements` が `inlineFragments.clear()` していた部分を、`discardFragments()`（見える側は空にして描画が古い断片を読まないようにしつつ、オブジェクトを最大 16 個の控え `spareFragments` へ回す）に置換。`addFragment` は `fragmentCursor` の既存要素 → 控え → 新規生成の順に取る。当初案の「`clear()` せず `fragmentCursor = 0` だけにする」は、layout がたどらない箱（行に入らなくなった箱など）で**古い断片が描画から見える**ので採らなかった（`resetPlacements` の本来の目的に反する）。
2. **`LayoutUnit`（`core/kernel`）**: `snap`（1/64 への丸め）、`isOnGrid`（格子の倍数かつ `< 2^18`）、`EXACT_LIMIT = 2^18`、`SHIFT_MARGIN = 2^14`。層依存ラチェット（下位 → 上位禁止）のため `kernel` に置いた（`text` から参照するので `layout.model` では不可）。
3. **縦の寸法の源を格子へ**: `LayoutMetrics.lineHeightPx`（px・倍率・`normal` の 3 種）、`FontFace.normalLineHeight`、`BitmapFontFace.ascent/descent` を `snap` する。これでインライン箱の矩形（断片を囲む）、行の下端（`baseline + descent`）、`leadingAbove`（floor 済み）がすべて格子に載る。表示上の影響は最大 1/128 px。
4. **平行移動の条件を緩和**（`LayoutBox.layout`）: 従来の `isIntegral(parentY - lastLayoutY)` を `isShiftableDelta` に置換。整数ぶんは従来どおり。小数ぶんは、①移動量と両方の原点が格子の倍数、②原点の絶対値が `EXACT_LIMIT - SHIFT_MARGIN` 未満、③**部分木の縦の値（`y`・`staticY`・`inlineContentBottom`・断片の `y`・`lastLayoutY`）がすべて格子に載っている**（`isGridAligned`。`isShiftableSubtree` の再帰に相乗りするので追加の走査はない）、のときだけ平行移動する。格子に載らない部分木は従来どおり置き直す（安全側に倒れる）。
5. **潜在バグの修正（`shiftSubtree`）**: 配置されなかったインライン箱（`br`、文字以外の断片を持たない空の `span`）は位置が `0,0` のままで、置き直しでは動かないのに、平行移動は他の箱と同じく `x / y` を足していた。一から組んだ結果と `y = -342`（`WidthOnlyReuseTest` seed 183）や `-15`（`TableIncrementalLayoutTest` seed 11）のずれが出た。整数ぶんの移動でも起き得たが、小数が除外されていたために顕在化していなかった。`isUnplacedInline()` で判定して動かさない。

#### 10.7.3 計測（2026-11-12、`FineProfileMain`、null バックエンド）

| 項目 | 実装前（§10.2 / §10.3.1） | 実装後 | 変化 |
| :-- | :--: | :--: | :-- |
| varied-2000 `child` mean / p50 | `4.60`（§10.3.1 の同条件 `4.17`）/ `4.54` | **`2.02` / `1.96`** | **-56%（同条件比 -51%）**。`childfixed` の実測 2.16 ms（上限の目安）を下回る |
| varied-2000 `child` place | `2.76` | **`0.93`** | -66% |
| varied-2000 `child` 確保 | `4,969 KB/frame` | **`271 KB/frame`** | **-95%** |
| `LAYOUTSKIP shifted`（/frame） | `0` | **`1,751`**（+ skipped 249 = 全行） | 近道が全行で効く |
| large-2000 `child` | `1.44` ms / `897 KB`（§9.10） | **`0.31` ms / `137 KB`**（large-list 800 frames、shifted 251） | 参考（条件が §9.10 と違う: 行数 500 の large-list） |
| showcase `resizeexact` | `0.95` ms / `MATCHFAIL 4=54` | `0.90` ms / `MATCHFAIL 4=54` | **変化なし**（下記） |

**showcase の幅変更は改善していない**: その箱は flex / grid / 中央寄せで、`isShiftableSubtree`（FLOW のみ）が平行移動を許さず（`LAYOUTSKIP shifted=0`）、`MATCHFAIL 4` は表示項目側（`DisplayListBuilder.isIntegerTranslation`）の条件で、横方向の小数移動を含む。この 2 つは第 2 段（下記）と Q6 の課題として残る。§10.5 の見込み「`MATCHFAIL 4` `54 → ~0`」は**未達**。

#### 10.7.4 テスト

- `BlockLayoutOptimizationTest`: `resetPlacementsKeepsFragmentObjectsForReuse`（置き直し後に見える断片は空・控えから再利用・値は上書き）、`spareFragmentsAreBounded`（控えは 16 個まで）。
- `FractionalShiftEquivalenceTest`（新規）: ①`lineHeightsLieOnTheLayoutUnitGrid`、②`layoutUnitSnapAndGrid`（丸め・格子判定・結合性・`2^17` 超の高い文書・上限 `2^18`）、③`fractionalInsertAndRemoveShiftsAndMatchesFullLayout`（小数の高さの行を挿入・削除し、全ての箱・断片の `x/y/w/h/baseline/descent` を**ビット単位**で新規レイアウトと比較。`layoutsShifted > 100` も要求）、④`randomInsertRemoveSequencesMatchFullLayout`（乱択 25 手。行に空の `span` と `br` を含め、配置されなかった箱の回帰を捕える）。
- 既存の一致テスト（`TableIncrementalLayoutTest`・`WidthOnlyReuseTest`・`InlineLayoutConsistencyTest`・`LayoutGoldenTest` ほか）が、潜在バグ（上記 5）の検出と、格子化後の値の整合（`normalLineHeight` を `BoxMeasurer` が未丸めで使っていた不整合）の確認に効いた。
- `:core:test` **全件グリーン**（1,950 件超、`LayerDependencyRatchetTest`・`PatchHitRateRatchetTest`（child 確保 385 KB/op ＜ 上限 2.5 MB）を含む）。

#### 10.7.5 残課題（Q1 第 2 段）

1. **表示項目の小数移動**: `DisplayItem` は丸めた整数座標なので、小数 `dy` では `isIntegerTranslation` が落ちて項目を作り直す（varied `child` の display `0.22 ms` は子の追加パッチ経由で小さいが、`resizeexact` の `MATCHFAIL 4` はこれ）。親相対の項目を持つか、`dy` が格子の倍数のとき項目ごとに「丸め後の差」が一定になることを確かめて平行移動する。
2. **横方向**: `parentX` の変化（幅変更で中央寄せが動く）は今回の対象外（縦のみ）。`LayoutUnit` を `x` へ広げるには、横の寸法の源（`advance`・`letter-spacing`・em の padding / margin）の格子化が要り、折り返し判定の境目に影響するので、別の検証（行の折り返し位置の一致テスト）とセットで行う。
3. **flex / grid の部分木**の平行移動（`isShiftableSubtree` の FLOW 限定の緩和）。
4. 座標型そのものの置換（`float` → `int` の `LayoutUnit`）と親相対化は、1〜3 の効果を見てから判断する。
5. `EXACT_LIMIT`（2^18 px = 262,144 px）を超える高さの文書では、小数の平行移動は無効（従来どおり置き直す）。

### 10.8 Q2 ②（`ComputedStyle.diff` の (旧, 新) 組メモ）実装記録（2026-11-12 完了）

#### 10.8.1 実施内容
- `ComputedStyle.changedIds(a, b)` を追加し、「値が違う添字（昇順の `int[]`）」を **(旧, 新) の同一性の組**で覚える。256 枠の直接写像（枠は `declHash` から決める。衝突は組の同一性を比べて上書き）。`diff` は添字配列を再生して visitor を呼ぶだけになり、継承側（`extras`）の比較は従来どおり。
- 効くのは、同形のリスト（全行が同じ旧スタイル・同じ新スタイルを共有）と、1 要素の再スタイルで `diff` が 2〜3 回呼ばれる分（`propagationOf`・`isPositionOnlyChange`・`isGeometryOnlyChange`・`computeLayoutAffectingChange`）。算出スタイルは不変なので、メモの無効化は要らない。診断用に `diffMemoHits()` / `diffMemoMisses()`。
- 計画書の ①（1 回の走査でビット集合を返す `diffSummary`）は、メモで repeat が消えたので後回し。③（`declHash` 一致の足切り）は、走査が参照比較のみで十分速く、ハッシュ一致でも衝突の検証が要るため採らない。④（算出スタイルのグループ化）は大きな構造変更なので別途。

#### 10.8.2 計測（`FineProfileMain`、GL バックエンド）
| 項目 | 実装前（§10.2） | 実装後 |
| :-- | :--: | :--: |
| large-500 `inherited` mean（restyle） | `0.77`（`0.36`） | **`0.66`（`0.25`）**（-14%、restyle -31%） |
| varied-2000 `inherited` mean | `6.81` | **`6.37`**（-6.5%） |

varied-2000 は行ごとにスタイルが違うので組は重複せず、メモが効くのは 1 要素内の 2〜3 回の重複分だけ。JFR（null、1,500 frames）で `ComputedStyle.diff` は **inclusive 20.3% → 13.9%**（残りは各組の最初の 1 回の全枠走査 `scanChangedIds` 12.6% exclusive）。**見込み「`diff` `<5%`」は未達**: 組が一意な文書では、全枠走査自体を減らす④（グループ化）か、スタイル共有（`ShareCache`）の拡大が要る。

#### 10.8.3 テスト
`ComputedStyleDiffMemoTest`（新規）: ①乱択 3,000 組で `diff` が `asMap()` の直接比較と一致（メモ命中の 2 回目も同一）、②同じ組の全枠走査は 1 回だけ（ミス 1・ヒット 50）、③600 組で 256 枠を衝突させても結果が混ざらない。`Milestone2GateTest`（`ComputedStyle` で `Arrays.copyOf` 禁止）に合わせて配列は 2 パスで確保。`:core:test` 全件グリーン（1,951 件）。

### 10.9 Q6（幅変更の測定の再利用）実装記録・調査結果（2026-11-12、部分達成）

#### 10.9.1 調査: 幅変更で何が測り直されているか
`resizeexact showcase`（1 フレームごとにビューポート幅を 1px 往復）で、`measureCore` が測り直した箱を、前回の測定との差の理由別に数えた（一時的な診断。コードは残していない）。**1 フレーム約 290 回**測っており（DOM 164 要素、flex の 2 段の測定を含む）、内訳は次のとおり。

| 理由 | 割合（回数） | 内容 |
| :-- | :--: | :-- |
| ビューポートが違うだけで、制約は同じ（`spaceEq=true`） | 約 11%（実装前） | `canReuseMeasurement` が `Viewport.equals`（幅も含む）を要求していた。部分木が箱の外の寸法を読まなくても、幅が 1px 違うだけで測り直し |
| `fit` 再利用の条件のうち、高さの % の基準（`percentageBlockSize`）が違う | 約 6%（`button` ほか） | 高さの % を読まない箱（`measuredBlockPercentFree`）でも、基準が違うと fit 再利用・幅だけの再利用が外れた |
| 使える幅そのものが変わる（flex の fill 幅の項目・段落） | 大半（約 50%） | 正当な測り直し。内容が使える幅を埋める箱は、幅が変われば結果が変わる |
| flex の 2 段の測定（仮 → 確定）が、使える幅の変化で仮の産物に当たらず交互に追い出す | 約 20% | flex 項目の `measuredSpace` が、仮の測定（上書き幅なし）と確定の測定（上書き幅あり）で毎フレーム入れ替わる。項目の内容が段落（fill）だと fit の必要幅が無限大になり、仮の産物（`Tentative.matches`）も当たらない |

#### 10.9.2 実施内容（安全で局所的な 2 点）
1. **ビューポートの幅を無視した再利用**（`LayoutBox.sameViewportForMeasure`）: 部分木が箱の外の寸法（ビューポート単位・絶対配置）を読まない（`measuredOutsideFree`）なら、制約が同じ測定は、ビューポートの高さ・解像度比が同じ限り使い回す。画像の候補選び（`srcset` / `sizes`）がビューポートの幅を読むので、`img` / `picture` は `measuredOutsideFree` から外した。切り分け: `-Denderframe.layout.noviewportwidthfree=true`（`LayoutBox.viewportWidthFreeReuse`）。
2. **高さの % の基準を、fit 再利用・幅だけの再利用でも無視**: `measuredBlockPercentFree` の箱は、`widthOnlyChange` と `fitReuse` で `percentageBlockSize` の違いを理由にしない（制約が同じ場合の `sameIgnoringPercentBlock` と同じ扱いに揃えた）。

#### 10.9.3 計測（showcase `resizeexact`、null、3,000 frames）
| 項目 | 実装前 | 実装後 |
| :-- | :--: | :--: |
| フレーム mean / p50 | `0.869` / `0.800` ms | `0.822` / `0.750` ms（再計測 `0.874` / `0.772`。ばらつきあり） |
| `layout.measure` mean / p50 | `0.347` / `0.314` ms | **`0.302〜0.320` / `0.268` ms**（約 -10〜14%） |
| 確保 | `1,198 KB/frame` | `1,121 KB/frame`（-6%） |
| 測り直し（診断、1,000 + 600 frames の累計） | 約 520,000 | 約 464,000（-11%） |

**結論: 幅変更 `12x`（Chrome `0.084 ms`）は解消していない。** フレーム全体で `約 -5%`、測定段で `約 -10%` にとどまる。§10.5 の見込み「measure `0.36 → <0.15 ms`」は**未達**。残りの測り直しの大半は、使える幅が実際に変わる箱（fill 幅の flex・段落）と flex の 2 段の測定で、測定キャッシュの鍵を「使える幅の区間」へ広げる程度では消えない。

#### 10.9.4 次の一手（未実施。効果の大きい順）
1. **flex の 2 段の測定の追い出し**: 項目の `measuredSpace` が仮（上書きなし）と確定（上書きあり）で毎フレーム入れ替わり、確定の測定が毎回外れる。確定の測定の記録を、仮の測定とは別に持つ（`Tentative` の種類を増やすか、確定用の `measuredSpace` を別枠にする）。測り直し全体の約 20% が対象。
2. **fill 幅の block の「幅が変わっても内容が同じ」判定**: 段落が折り返さず収まる幅の範囲（`fitNeed`）を、block の fill でも求める（現状は shrink-to-fit だけ）。flex の項目の中身が段落のとき、仮の産物が当たる。
3. 表示リスト側（`display-list` `0.30 ms`）の `MATCHFAIL 4`（§10.7.5）。幅変更のフレームの約 37% を占め、測定と同じ大きさの残りの塊。

#### 10.9.5 テスト
- `ViewportWidthFreeReuseTest`（新規）: 固定幅・`vw`・`position:absolute`・`img sizes="100vw"`・flex（`flex:0 0 260px` ＋ `flex:1`）・fill の段落が混在する文書で、ビューポート幅を 10 通り往復させ、①再利用あり・なしの結果が一致、②毎回一から組んだ結果と一致（全箱の `x/y/w/h`）、③測り直しが増えない、を確認する。
- 既存の `WidthOnlyReuseTest`（乱択 60 文書 × 10 幅。`vw`・絶対配置を含む）、`LayoutKeyEquivalenceTest` 等が一致の主な安全網。`:core:test` 全件グリーン。

### 10.10 幅変更の Chrome 比較を同じ範囲で測り直す（2026-11-13）

#### 10.10.1 これまでの「12x」は比較の範囲が違っていた
Chrome 側の幅変更は `bench.html` の `relayout`（`iframe` の幅を `800` / `799` px で往復）に続けて、`getComputedStyle(...).color`（スタイル再計算の強制）と `body.offsetHeight`（レイアウトの強制）を呼ぶ時間（`relayout.styleLayout`）で、**ペイント・ラスタ・合成を含まない**。一方 `FineProfileMain resizeexact` の「フレーム mean」は、**表示リスト生成（`display-list`）まで含む**。表示リストはこちらのエンジンがレイアウトの結果から描画命令を作る段で、Chrome ではペイント段（上の強制呼び出しに含まれない）に当たる。つまり 0.88 ms（こちら全体）と 0.084 ms（Chrome のスタイル＋レイアウト）を比べていた。

#### 10.10.2 同じ範囲での測り直し
- Chrome（157.0.8103.51、`node run.js chrome showcase`、164 要素、30 回の中央値の集計）: `relayout.styleLayout` **`0.082 ms`**（min `0.074`、max `0.118`）、うち `relayout.style` **`0.0035 ms`**（幅変更ではスタイルの再計算がほぼ走らない）。
- `:core`（`FineProfileMain resizeexact showcase 4000 null`、3 回実行、段別 mean の範囲）:

| 範囲 | 含む段 | `:core` | Chrome | 比 |
| :-- | :-- | :--: | :--: | :--: |
| **レイアウトのみ** | measure + place | **`0.32 ms`**（measure `0.29〜0.30` + place `0.03`） | 約 `0.078`（`styleLayout` − `style`） | **約 4x** |
| **スタイル＋レイアウト**（Chrome の強制呼び出しと同じ範囲） | style+build `0.07〜0.08` + box-tree `0.05` + measure + place | **`0.44〜0.45 ms`** | **`0.082`** | **約 5.4x** |
| フレーム全体（表示リスト込み） | 上 + display-list `0.29〜0.30` | `0.80〜0.82 ms` | ペイントを含まないので比較不可 | （従来の 10〜12x はこれとの比較） |

#### 10.10.3 読み方と、残りの差の中身
1. **差は 12x ではなく 約 4〜5x**。§10.9 までに「12x を詰める」と書いていた目標は、範囲を揃えると、レイアウトで `0.32 → 0.08 ms`（約 4x）、スタイル＋レイアウトで `0.45 → 0.08 ms`（約 5.4x）が実際の差になる。
2. **スタイル段の差が目立つ**: Chrome は幅変更で `0.0035 ms`（再計算なし）、こちらは `style+build` `0.075 ms` と `box-tree` `0.047 ms` を毎回払っている（合計 `0.12 ms`。Chrome のスタイル＋レイアウト全体 `0.082` より大きい）。幅変更でスタイルに効くのはメディアクエリ・コンテナクエリ・ビューポート単位だけなので、**それらを使わない（`StyleSheet.updateMediaEnvironment` / `declaresContainers` が false の）文書では、幅変更で再スタイルと箱の木の更新を丸ごと飛ばせる**（新しい着手候補 **Q7**）。
3. 測定 `0.29 ms` は §10.9.1 のとおり flex の 2 段の測定と fill 幅の再測定が大半（Chrome は `LayoutNG` の結果キャッシュで同等の処理を飛ばす）。表示リスト `0.29 ms` は Chrome ではペイント段で、同じ範囲には入らない。ただし**幅変更のユーザー体感（フレーム全体）としては効く**ので、`MATCHFAIL 4`（§10.7.5）の解消は引き続き有効。
4. 以後、幅変更の KPI は**2 本立て**にする: ①Chrome 比較用（スタイル＋レイアウト。目標 `< 0.20 ms`、約 2.4x 以内）、②フレーム全体（表示リスト込み。目標 `< 0.50 ms`）。

#### 10.10.4 再現
```powershell
cd core/src/bench-crossengine
node run.js chrome showcase        # res-chrome-showcase.json の res.relayout.styleLayout / res.relayout.style
cd ../../..
.\gradlew.bat :core:profile "-Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain" "-Pprofile.args=resizeexact showcase 4000 null"
```
Chrome の `res-*.json` は `.gitignore` 対象の出力なので、計測のたびに再生成する（今回の値はこの記録のみ）。

---

## 付録: 本編冒頭の更新履歴（2026-11-17 に本編から移設）

本編の冒頭「最終更新」に積み上がっていた記述をそのまま移したもの。以後は本編に積まない。

> **最終更新**: 2026-11-17（**A 系 15 項目を §3 に追加**（総合評価で分かった弱点への対策。すべて未着手。`core/README.md` のずれは同日に修正）／2026-11-16: **B3 完了・§4 移動**（親相対化まで。確保 `0 B`・`hover 0.10 ms`・MOD 側の仕上げと実機確認は B8〜B10 として §3 に追加）／**X3-2・X3-3 完了・§4 移動**（Claude。B3 は Gemini が並走中で、この更新は B3 に触れない）: ライト列（ポイント/スポット）・ベースカラー画像・glTF の `baseColorTexture` を GL/Vulkan/ソフトウェアの 3 者一致で実装し、`<scene-3d>` の子要素の宣言的同期・公開 API `scene3d(id)`・`data-orbit` を追加。`.\gradlew.bat test :core:check` で私の担当範囲は緑〔`Scene3D*`・`Gltf*`・`Texture3D`・`SoftwareScene3D*`・`GlDeviceDrawTest`・`ApiBoundaryTest`・`ArchitectureTest`〕。同時実行の全件では B3 作業中の 2D 側（表示リスト・`LayerDependencyRatchetTest` の `DisplayListBuilder → EngineContext`・`GpuBaselineTest` の最大差 1 ほか）が赤で、B3 の完了時に解消する前提）／（**W9b 完了・§4 移動**: 新品の箱が持つ `subtreeChanged = true` を「当該レイアウト実行でまだ未測定（`hasUnmeasuredSubtreeChange`）」へ改め、`resolveFlexibleHeights`（`hadIncomingFloats == false` 時 `canPreserveMeasurementForEmptyFloatFlow`）・`computeFitNeed`（`SHRINK_TO_FIT` かつ自己内閉鎖 float）・`computeShrinkIndependent`（`sameParentContext`）および `getMinContentWidth` の仮測定退避解決（`tentativeStashed && measuredMinContentVar` 時の `settleTentative`）を整理し、WPT `flexbox-justify-content-horiz-001b.xhtml` の測定 `66,800 → 210` 回・`measureFuelReused() == 0` を達成。`MeasureFuelGuardTest` に線形スケール・`hadIncomingFloats`・差分更新テストを追加し、`.\gradlew.bat test :core:check "-Dgpu.baseline.exact=true" --rerun` 緑〔`:core` 2,388 件・ルート 501 件〕）／（**B2 完了・§4 移動**: `LayoutTaskPool`〔共通 `ForkJoinPool`・`PARALLEL_THRESHOLD = 32`・`-Denderframe.layout.noparallel=true`・`EngineContext` 伝播〕により `restyle`・`measure`・`place`・`resetChildrenPlacements`・`markLayoutChanges`・`clearAllSubtreeMarks` を並列化し、`TextShaper`/`BitmapFontFace`/`StyleAnimator`/`InteractionState` のスレッド安全化と `DisplayListBuilder.cullableSubtree` インデックス走査化を実施。`varied-2000 fontsize`〔GL 3 回中央値〕が `19.43 → 6.21 ms`〔`p50 6.15`、確保 `5,325 → 1,122 KB`、`null` `5.95 ms` vs `noparallel 17.63 ms`〕となり **144Hz 予算 `<= 6.94 ms` を達成**。`ParallelLayoutEquivalenceTest` 追加、`.\gradlew.bat test :core:check "-Dgpu.baseline.exact=true" --rerun` 緑〔`:core` 2,366 件・ルート 501 件〕）／（**X3-2 の大半が完了**: ソフトウェア 3D ラスタライザ・GGX PBR・glTF ローダー・HDR Bloom〔詳細は §3 の X3-2 行〕。残りはライト列・テクスチャ）／（**G4 完了・§4 移動**: `:core` から OpenGL・GLFW・Vulkan のコンパイル依存と実装を分離し、`:core-gl`〔`core.paint.gl`〕・`:core-vulkan`〔`core.paint.vulkan`〕・`:core-glfw`〔`StandaloneViewer`・`WindowHost`〕の 3 モジュールへ。`GpuBackendProvider`/`WindowHost` を `ServiceLoader` で見つけ、`EnderEngine` に `Target.GPU`・`gpu(GpuApi)`・`hostDevice`・`WebViewSurface.texture()`〔`TextureHandle`〕を追加。`:core` 全件 2,327 件、失敗 1 件〔テストのソースパス参照の直し漏れ〕を直して再実行で緑）／（**G3 完了・§4 移動**: 2 つ目の RHI デバイスとなる Vulkan 1.1 バックエンド〔`VkContext`・`VkDevice`・`VkPipelines`・`VkSurfaceHost`・`VkCpuFallback`・`VkRenderBackend`・`VkSwapchain`〕を `core.paint.vulkan` に実装し、GLFW 非依存・必須拡張なしのヘッドレス描画と `VK_KHR_swapchain` 窓描画の双方を確立。`showcase`・`large-list`・`varied-list` で `SOFTWARE`/`GL`/`Vulkan` 3 者画素一致〔`maxDiff(vk, gl) <= 1`、`maxDiff(vk, cpu) <= 2`〕、X3-1 回転キューブ一致〔`maxDiff = 0`〕、`VK_LAYER_KHRONOS_validation` エラー/警告 `0`、`FineProfileMain` Vulkan `hover`/`scroll`/`child` が全項目 GL 比 `<= 1.00x`、RHI 入れ子型をトップレベル昇格し `.\gradlew.bat test :core:check "-Dgpu.baseline.exact=true" --rerun` 全件緑: `:core` 2,310 件〔失敗 0・スキップ 3〕・ルート 501 件〔失敗 0・スキップ 0〕）／（**G2 完了・§4 移動**: 全 7 シェーダーを `core.kernel.gpu.shader.ShaderSources` + `Glsl`〔`gl150`/`vk450`〕へ単一ソース化＋`std140` UBO 統合＋ビルド時 SPIR-V 検証）／（**G1 完了・§4 移動**: RHI 完結・`LwjglGlRenderBackend` 分解）／（**B1 完了・§4 移動**）／（**X3-1 完了・§4 移動**）／（**G0 完了・§4 移動**）／（**X3-0 完了・§4 移動**）

> **最終更新**: 2026-11-18（**A8 一部実施**: 神クラス 5 つから 12 クラスを切り出し〔`DisplayListBuilder` `4,358 → 2,496` 行ほか〕、T1 のラチェットの上限を下げた。JIT の上限を超えていた子のパッチを分けて large-list `child` `0.33 → 0.28 ms`）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-18（**T2 完了**: 回帰ゲートに同梱の実文書を載せた〔`costGate` `22 → 56` シナリオ・実文書 9 本 × 操作の手順の画素・表示リスト・ヒットテストのゴールデン・差分の経路 = 全体の作り直しの毎フレーム一致〕。この比較で見つけた不具合 3 件〔ヒットテストが壁時計でアニメーションを評価・入れ子のスクロールのクリップ・fieldset の枠線のクリップ〕を直した。GPU と SOFTWARE の差の残りは T2b）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-18（**A1 の残り: CI が 11-17 から赤だったのを直す**: OS の書体に依る画素のゴールデンを書体の環境の指紋で切り替え〔GitHub の ubuntu-latest は Ubuntu 26 で WSL と書体が違う〕、`ColrV1EmojiTest` の書体の選び方・`LayoutParallelProbeTest` の壁時計の割合を直した。§6-100）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-19（**T2b 完了**: 実文書 9 本の GPU（GL / Vulkan）と SOFTWARE の画素差を 6 組すべて許容内へ〔最大 `250 → 8`〕。原因 4 つ: fx・tex シェーダーのサンプラーが Java 側だけ binding 0 始まり〔Vulkan の記述子の更新が不正〕・保持したスクロールの層を Vulkan で上下反転・CPU のボックスぼかしの近似が画面の外を透明で読む・動く節の下の部品が GPU の差分パッチから漏れる。§6-101〜104）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-19（**A1 補足**: 実時間で合否を決めるテスト 6 件に `@Tag("timing")` を付け、共有ランナーの CI〔`build.yml`・`full-tests.yml`〕では `-Ptest.excludeTags=timing` で外す〔開発機では流す〕。付け忘れと CI の渡し忘れは `TimingTagPolicyTest` が見張る。きっかけは T2b の push で 2 コアの CI が `LayoutTaskPoolCallerParticipationTest` を落としたこと〔こちらは時間に頼らない形に直した〕。§6-105。直前は T2b 完了・§4）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-19（**B12 完了**: 残っていた voidwave `scroll` の確保 `130 KB` を `86 KB` へ〔合否線 `100 KB` 以下〕。自分の変形・不透明度が恒等の葉の項目は、親の姿勢〔`Resolved`〕の実体をそのまま使い、項目の色などの補間値は `PaintSceneDriver.animAt` で引く。中身の節が動くフレームで作る姿勢が 1 フレーム `1,997 → 642`。voidwave `hover` `92 → 72 KB`・`text` `78 → 59 KB` も下がった。§6-106）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-19（**A8 完了**: 1,500 行を超えるファイル `13 → 4`〔`LayoutBox`・`DisplayListBuilder`・`UiView`・`ComputedStyle`〕。入れ子の型を最上位へ出し〔`HtmlTokenizer`・`CharacterReferences`・`TrackList`・`LineJustify`・`Painter`・`RangeBounds`・`RenderTask`・`Chunk`・`PaintLayers`・`AttributeMap`・`ChildList` ほか〕、責務で 3 つを分けた〔`RenderTreeBuilder`・`GpuEffects`・`ItemRaster`〕。挙動は変えない移動だけ。残る `LayoutBox`〔3,884 行〕は A7 の合否線〔2,000 行以下〕が受け持つ。§6-107）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-19（**T3 実装**: 回帰カタログ `regression-catalog.txt`〔直した不具合 1 件につき 1 行・`RegressionCatalogTest` が「テストが実在する」「§6 の〔不具合〕の項目は全件載っている」を検査〕・§6 の不具合 31 項目に印・差分ファズ〔夜間の `mutation-fuzz.yml` が日付の種で増分 = 全面を調べ、ずれた種を `mutation-seeds.txt` へ。毎回の CI で固定の種として回す〕。初回のファズで実際のずれを 7 本見つけ、3 本〔子パッチが祖先の幅の変化を追わない〕を直し、4 本は「既知」で残した。§6-108〜110）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-19（**T3 の既知 4 本を直した**: 差分ファズの layouts 17・18・23・29 は 4 つの別々の不具合〔子パッチの挿入位置が float・位置指定の層を数えた・`display` の変わった箱の子が古い配置の記録を引き継いだ・記憶の無い表の移動を子パッチが追わなかった・表の中の寸法の変化を局所の測り直しにした〕。種 1〜100 で見つかった別の 4 本は「既知:」で残した。§6-109）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-19（**T3 の既知を 1 本まで減らした**: 「既知:」で残した 4 本〔layouts 33・38・49、nested 64〕と、種 101〜300 で新たに見つけた 4 本〔layouts 137・145、nested 131・195〕を直した。原因は子パッチの追跡漏れ 5 つ〔float・相対位置の箱の高さの変化・寸法 0 になった祖先・消す区間に別の層の項目が挟まる・描かれなくなった箱の古い番号〕と、レイアウトの 2 つ〔表の min-content をセルを測る前の装飾から行に記憶・列方向の flex の stretch が枠線ボックスを装飾より細くした〕。種 1〜500〔1,500 本〕の残りは nested 339 の 1 本〔0.07%。レイアウトの木そのものが違う〕で「既知:」にした。§6-112・113）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-19（**T4 完了**: 公開 API〔`api.*` の 971 行〕の署名を `api-signatures.txt` に固定した〔`ApiSignatureTest`。型・コンストラクタ・メソッド・欄の総称・throws・修飾子まで比べ、違えば `-`/`+` の差分を出して赤。更新は `-Papi.update`〕。`EnderEngine.workerThreads(int)` を `long` にすると赤になることを確認。`api.event`・`api.dom` に契約のテスト 13 件〔利用者が書く `Event`・`AbortSignal` と面への入力の両方で、登録の重複・once・中止・段階・伝播の停止・passive〕。直前は T3 の既知を 1 本まで減らした・§6-112・113。§6-114）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-19（**S1 ① 実施**: `enderframe.*` のシステムプロパティを読むのを `core/kernel/EngineFlags`〔L1・58 個の値・起動時に 1 回だけ読む〕1 か所にした〔読むファイル `22 → 1`。`SystemPropertyRatchetTest.MAX_ENGINE_READER_FILES = 1`〕。各クラスの切り替えの欄は `EngineFlags` の値を初期値に持つだけで、挙動は変えない。実行中に `System.setProperty` で親相対を切っていたテストは文脈の欄を切り替える形にした。残りは ②〜④。直前は T4 完了・§4）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-19（**S1 ② 実施**: `EngineContext` の public volatile 欄 14 個を、不変の 2 つの値〔`HostServices`: 翻訳・JSON 解析・固有サイズ・描画関数・リソース 8 個／`ContextFlags`: 差分・局所レイアウト・診断・親相対 6 個〕にまとめ、`updateHost` / `updateFlags` で丸ごと差し替える形にした〔public 可変欄 `14 → 0`。`ClassShapeRatchetTest` の表から外れた〕。呼び出し 145 か所〔Minecraft 側 4 か所を含む〕を機械的に置き換え、挙動・`costGate` は不変。直前は S1 ①〔`EngineFlags`〕）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-19（**S1 ③ 実施（まとめる段）**: 各クラスの `static volatile boolean` の切り替え 51 個〔近道・切り分け・検証。`STACKING_PATCH_ENABLED`・`CLIP_SHARE`・`STICKY_IN_SCROLL_NODE` を含む〕を `core/kernel/Shortcut`〔enum・状態は 1 つの不変の配列を丸ごと差し替える・読みは `Shortcut.X.on()`〕にまとめ、テストは `set` か範囲つきの `with` で切り替える。テストのリフレクションでの書き換え 2 か所も API になった。GL / Vulkan のファサードが持っていた写しの欄 8 個は不要になって外した。面ごとにするのは利用者の判断で A13 と一緒に行う。`costGate` は不変。直前は S1 ②）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-19（**T3: 差分ファズの全面を「測定の記憶を捨てて組む」形にした**: 全面の再レイアウトも記憶を使い回して増分と同じ古い値を返すため〔§6-117〕。これで配置の記憶の不具合 2 つが見つかり直した〔種 1〜500 の新しいずれ 17 本 → 2。`costGate` の経路の回数は不変。§6-118〕。残る 2 本と種 501〜1500 の既存の子パッチのずれ 13 本は「既知:」で表へ）

> **最終更新**: 2026-11-20（**A9 完了: ホスト拡張の SPI を `api` へ出した**。①公開 SPI `api.spi`〔`ElementProvider`・`ElementPainter`・`ReplacedKind`・`TagTemplate`〕と `common.ApiSpiBridge` ②`Builder.i18n`・`Builder.resources` ③Minecraft 側の `mc-*` を公開 SPI へ移行〔`McTagMacros` → `McTagTemplates`。`McHostElements` は `api.spi.ElementProvider`〕④`api` だけを参照するサンプル〔`core/src/apiSample`〕⑤§6-114 の決定〔`EnderEngine.createEvent`・`createAbortController`〕。`:core` とルートの全テスト緑・`build` 成功）。直前は S3 完了〔同日〕。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-20（**A13 第 3 段完了**: `GlyphUnits`・`ReplacedElements`・`LocalEnvironment` の static 登録を面ごとの `HostServices` へ移設し、static setter は `5 → 4`。残りは `UI.setStyle`〔DOM 操作の名前〕・`DomUtils`・`DisplayListBuilder`〔層の逆転の結線〕・`LayoutTaskPool`〔プロセス共有の池〕）。直前は A13 第 2 段完了〔同日〕。

---

## 付録 2: 2026-11-20 本編整理時の退避（旧 KPI・完了済み設計詳細・§4 詳細実装記録）

> 2026-11-20 に構造監査（`S4`〜`S8` 追加）と合わせて本編 `ENGINE_REARCHITECTURE_PLAN.md` を運用ルール 1〜3 に沿って整理した際、過去履歴として保全するため退避した記録群。

### A2-1. §1.3 完了済み逆方向参照（2026-11-10 解消 11 系統）

| 逆方向参照の向き | 状態 | 実施内容 / 残件と解消策 |
| :--- | :---: | :--- |
| **L3 (`dom`) $\to$ L4 (`layout`)**（`HostSyntax` → `HostElementProvider`） | ✅ 解消 | SPI [`HostElementProvider`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/HostElementProvider.java) と、それが返す [`CustomElementPainter`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/CustomElementPainter.java)、置換要素の性質 [`ReplacedKind`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/ReplacedKind.java)（旧 `ReplacedElements.Kind`）を `core.dom` へ移設。`META-INF/services` の登録名（`core/src/test/resources`・ルート `src/main/resources`）も新 FQN へ更新（旧名のままだと SPI が黙って無効化される） |
| **L3 (`css`) $\to$ L5 (`paint`)**（`AnimationParser` / `StyleAnimator` / `ShorthandExpanders`） | ✅ 解消 | `AnimationTiming` / `EasingFunction` / `AnimationSpec` を `core.paint.model` → `core.css.model` へ移設（`EasingFunction`・`AnimationSpec` の未使用 `paint.*` import も除去） |
| **L3 (`css`) $\to$ L4 (`layout`)**（`ComputedStyle` / `CssValue` / `StyleFacts`） | ✅ 解消 | 計画書旧版は import のみを数えていたが、実際は**完全修飾名で 3 箇所**残っていた（検査器が FQN も走査して発見）。純粋な文字列判定 `isIntrinsicSizeKeyword` / `numericFontWeight` を新設 [`ValueKeywords`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/ValueKeywords.java)（L3）へ移し、`LayoutMetrics` は委譲 |
| **L2 (`text`) $\to$ L3 (`css`)**（`WhiteSpace` / `WordBreak` → `KeywordMatch`） | ✅ 解消 | `KeywordMatch` を L1 `core.kernel.style` へ移設 |
| **L4 (`layout`) $\to$ L5 (`paint`)**（未使用ワイルドカード） | ✅ 解消 | 8 ファイルの未使用 `import core.paint.*;` を削除 |
| **L1 (`kernel/compiler`) $\to$ L3** | ✅ 解消（定義で） | `kernel/compiler` は CSS/DOM を保持するため L3 と定義（逆参照 0） |
| **L2 (`raster/svg`) $\to$ L3 (`dom`, `css`)** | ✅ 解消（定義で） | SVG は DOM を解析して描くため `raster/svg` を L3 と定義（`raster` 本体からの参照は 0、利用側は layout/paint のみ） |
| **L2 (`raster`) $\to$ L3**: `CanvasRenderingContext2D` → `css.ColorParser` | ✅ 解消 | L2 に SPI [`ColorSyntax`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/raster/ColorSyntax.java)（`ServiceLoader`）を新設し、L3 の `CssColorSyntax` が `ColorParser` へ委譲（`META-INF/services` 登録。`ColorParser` 自体は 20 箇所超から参照されるため移設しない） |
| **L2 (`text/font`) $\to$ L4/L5**: `FontFaceRules` → `LayoutMetrics` / `DisplayItem` | ✅ 解消 | `fontStyleFor(DisplayItem, …)` を L5 の [`DisplayItemFonts`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/DisplayItemFonts.java) へ移し、太さの解析は L3 `ValueKeywords` を使用（`WebFontManager` を新所在へ更新） |
| **L3 (`dom`) $\to$ L6**: `Bindings` → `EngineContext` | ✅ 解消 | L3 に SPI [`BindingHooks`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/BindingHooks.java)（翻訳・JSON 解析）を定義し、L6 の `EngineContext` が実装して静的初期化で供給元を設置。スレッドスコープの文脈切替（`callWith`）はそのまま効く。ホスト向けの公開フィールド（`EngineContext.global().i18nResolver` 等）は不変 |
| **L4 (`layout`) $\to$ L5**: `BoxMeasurer` → `ImageChoice` / `ImageSources` | ✅ 解消 | 依存のない純粋ロジックなので `core.paint` → L2 の `core.media` へ移設（paint 側は import を更新） |

### A2-2. 旧 §2 の 2026-11-15 書体不揃い KPI 行・旧ヒープ保持行・B2 着手条件行

| シナリオ | `:core`（当時） | 前回 | Chrome | 判定 |
| :-- | :--: | :--: | :--: | :--: |
| varied-2000 `inherited`（GL） | `3.59 ms`（`p50 3.53`、確保 `755 KB`） | `3.53` | `3.55` | ✅ `1.01x`（Chrome 並み・±10–15% ばらつき内） |
| varied-2000 `child`（null / GL） | `3.41 ms`（3 回 `2.41–3.56`、`p50 3.01`、確保 `831 KB`） / GL **`3.18 ms`**（`p50 3.11`、確保 `849 KB`） | `2.74` / `3.60` | `3.67`（GL 込み） | ✅ GL 込み `0.87x`（null はばらつきが大きい） |
| varied-2000 `fontsize`（GL、B2、3 回中央値） | **`6.21 ms`**（3 回 `6.17–6.43`、`p50 6.15`、確保 `1,122 KB`；`null` `5.95 ms`〔確保 `744 KB`〕 / `noparallel` `17.63 ms`〔`4,096 KB`〕） | `19.43`（`5,325 KB`） | `42.1` | ✅ **144Hz 予算 ≤ 6.94 ms 達成**・`0.15x`・確保 `< 5 MB` 達成（`-79%`） |
| varied-2000 `scroll`（GL） | `0.079 ms`（`p50 0.056`、確保 `3.0 KB`） | `0.083` | 全 CPU 時間 `0.65`（main `0.31` + raster `0.14` + draw `0.19`、3 回 `0.64–0.65`、2026-11-15 `trace.js` gpu） | ✅ **`0.12x`**（`0 B` 未達） |
| large-500 `hover` / `style`（GL） | `0.022` / `0.048 ms`（`p50 0.016 / 0.033`、確保 `1` / `6 KB`） | `0.027` / `0.058` | `0.35` / `0.33` | ✅ **`0.06x` / `0.15x`**（§1.1 時間 `< 0.15` / `< 0.25 ms` 達成、`hover` 確保 `0 B`・`style` `< 1 KB` 未達） |
| large-2000 `text`（GL） | `0.111 ms`（`p50 0.106`、確保 `3.4 KB`） | `0.113` | `1.02` | ✅ **`0.11x`**（時間 `< 0.25 ms` 達成、確保 `< 1 KB` 未達） |
| large-2000 `hover` / `scroll`（GL） | `0.069` / `0.069 ms`（`p50 0.058` / `0.048`、確保 `1` / `3 KB`） | `0.071` / `0.067` | `0.73` / `0.54`（2026-11-15 `trace.js` gpu の 3 回中央値。`hover` は前回値 `0.55` から再計測。`scroll` は main `0.30` + raster `0.07` + draw `0.17`） | ✅ `hover 0.09x`（`< 0.15 ms` 達成）・`scroll 0.13x`・`p50 0.048 ms`（`< 0.05 ms` 達成、mean・`0 B` 未達） |
| large-2000 `child` / `fontsize`（GL） | `1.16` / `5.77 ms`（確保 `540` / `2,375 KB`） | `1.40` / `7.82` | `1.98` / `11.3` | ✅ `0.58x` / `0.51x` |
| showcase `idle`（null / GL） | `0.001 ms` / `0.001 ms`（`p50 0.0004` / `0.0003`、`drawn=0`、確保 `0 KB`） | `0.0007` / `0.0009` | — | ✅ 完全スキップ |
| showcase `scroll` / `hover` / `text` / `style`（GL） | `0.0068` / `0.225` / `0.258` / `0.241 ms`（`p50 0.0056` / `0.176` / `0.215` / `0.195`、確保 `0.7` / `11.0` / `17.8` / `17.3 KB`。2026-11-15 G1 完了後計測: `scroll`/`hover` 3 回中央値、`text`/`style` 1 回） | `0.0070` / `0.225` / `0.288` / `0.243` | 全 CPU 時間（main＋raster＋draw、GPU 合成）: `hover 1.10` / `text 0.95` / `style 0.94` / `scroll 0.18`（2026-11-14 `trace.js` gpu） | ⚠️ `style` mean `< 0.25 ms` 達成、`scroll` は `0.0068 ms` へ改善（`0.04x`）。`hover` mean `0.225 ms`（3 回 `0.223–0.229`）・`text` `0.258 ms` は直前 `0.200`/`0.215` から小幅増（フレーム状態退避/RHI 分割の影響＋±10% ばらつき範囲）。Chrome 比 `0.04x` / `0.20x` / `0.27x` / `0.26x`。ヒープ確保 (`0 B` / `< 1 KB`) は未達 |
| showcase CPU `hover` | `1.555 ms`（`p50 1.206`、確保 `153 KB`） | `1.168` | CPU `1.55` | ✅ `1.00x`（3 回 `1.45–1.62`・ばらつき大。確保は不変） |
| showcase `open` | `null`: `4.93 ms`（`p50 4.59`、確保 `3,899 KB`） / GL: `5.06 ms`（`p50 4.84`、確保 `4,298 KB`） | `4.41` / `5.29` | `5.0` | ✅ GL `1.01x`（ウォーム JVM。コールドは次行） |
| ヒープ保持（varied-2000 / large-2000 / showcase） | `33.3 MB` / `7.7 MB` / `2.4 MB`（`baseline 5.7 MB`、`MemFootprintMain varied-list-2000 large-list-2000 showcase`） | `33.3` / `7.7` / `2.4` | +`26 MB` / +`5 MB` / — | ✅ varied `1.28x` / large `1.54x`（目標 `1.5x` 以内。large は境界、測定ゆらぎ内） |
| **B2 の着手条件: 並列に配れる割合**（`FineProfileMain … null` に `LayoutParallelProbe` を有効にして計測。兄弟が `8` 以上の親の、float・絶対配置・表を含まないブロックレベルの子を「独立な単位」として、最も外側の単位に入ってから出るまでを数える） | varied-2000 `fontsize`: **測定 `86%`・配置 `70%`**（独立な単位 `2,000` 個 / フレーム。測定 `5.8` + 配置 `5.7 ms`）／ large-2000 `fontsize`: 測定 `82%`・配置 `62%`（`2.7` + `1.6 ms`）／ showcase: `0%`（兄弟が多い親が無い。測定 `0.6 ms`）。同 varied: **restyle `93%`**（`3.7 ms`、独立な単位 `2,000`）・表示リスト `1.2`・paint `0.7 ms`。`FineProfileMain … null` の `PARALLELPROBE restyle` | — | — | ✅ **着手条件成立・B2 完了済み**（上掲 varied-2000 `fontsize` 行へ反映。配置の位置積み上げは直列に残し、測定・配置・再スタイル・印付け・後片付けを並列化。showcase はしきい値未満で直列維持） |

### A2-3. 旧 §3 導入メモと `A1`・`T3`・`B10`・`A11`・`B4` 詳細経緯

**順位の考え方（2026-11-15 付け直し）**: ①計測とラチェットを先に置く（G0・B1。以降の合否線を作る）②X3-1 は既に着手中なので GL のまま完了させる（GL 呼び出しとシェーダーを各 1 ファイルに閉じる制約つき）。**X3-2 以降は** GL 直書きで増やすと後で全部移植になるので、RHI（G1）・シェーダー単一化（G2）・2 つ目の実装（G3）の後ろへ回す ③Chrome 超えの項目は「測って着手条件を満たしたものだけ」実装する（B2 以降はすべて着手条件つき）。ID は **G** = 描画 API 中立化（Generality）、**B** = Chrome 超え（Beyond）、**X3** = 3D。設計は §3.1（X3）・§3.2（G / B）。

**A 系（2026-11-17 追加。総合評価の弱点への対策）**: `:core` をロジック・ベンチマーク・構造・拡張性の 4 観点で点検した結果は B+ / B+ / B / B-。弱点はすべて実測かコードの数で確認したもので、各行の「内容」に根拠を書いた。ID は **A** = 評価を A にする（Assessment）。観点ごとの A の条件は、対応する行の合否線がすべて満たされること: **ロジック** = A11・T3（A5・A6・A6b・A7 は完了・§4）／**ベンチマーク** = A1（A2・A3・A12・T2・T2b と、その計測で出た B11・B12 は完了・§4）／**構造** = A13・G5（S3 は完了・A4・A7・A8・T1・S1 は完了・§4）／**拡張性** = A10・A14・A15（A9・S2・T4 は完了・§4。A15 は着手条件つきで、条件が成立しない間は A の判定から除く）。**A2・A3・A12 の計測で、条件 b（144Hz 全行）が実書体・少ないコア・実文書の `open` では成り立たないことが分かった**ので、B11〜B13 を足した（B13 は着手条件つき）。D1（GA 公開）は A9・A10・A14 の完了を前提にする（公開後は API を変えにくい）。

**T・S 系（2026-11-18 追加。テストと構造の整理）と順位の付け直し**: エンジンを数え直した結果（`core/src/main` 434 ファイル・約 11.3 万行・`:core` のテスト 2,507 件）。①**ラチェットが層の間しか見ていない**: クラスの大きさ・形・static な配線・設定の読み口・握りつぶす例外は固定されておらず、A8 を書いた後も `DisplayListBuilder` が `3,953 → 4,358` 行に増えた ②**後戻りを止めるテストが代表文書に偏る**: `costGate` と画素のゴールデンは showcase・large・varied だけで、B12 で直した実文書は固定されていない。§6 の不具合の項目からテストへの対応も無い ③**神クラス**: メソッド 100 超が 9 クラス（`LayoutBox` 201・`UiView` 174・`GpuRenderBackend` 139・`UI` 132・`CanvasRenderingContext2D` 128・`ComputedStyle` 127・`SoftwareRenderBackend` 119・`HtmlNode` 115・`DisplayListBuilder` 115。合成のメソッドを除く）、public な可変欄が計 358（`LayoutBox` 106。T1 の `ClassShapeRatchetTest` の数え方。A7 完了時点で 100 超は 7 クラス、`pubMut` 合計 `278`、`LayoutBox` `40`・`170` メソッド） ④**管理の散らばり**: システムプロパティの読み 68 か所・static な setter 16 個・`EngineContext` にホストの差し込み口と近道の切り替えが同居 ⑤**拡張性**: タグ名の文字列比較が 15 ファイル、`ElementType` の分岐が `default` 付きで多数のファイルに散り、要素や項目の種類を足すと直し漏れがコンパイルで見つからない。ID は **T** = テスト（ラチェット・回帰）、**S** = 構造（Structure）。**順位の原則**: (1) まず現状を数で凍らせる（T1。2026-11-18 完了・§4）(2) 達成した KPI と絵を固定する（T2。2026-11-18 完了・§4。比較で見つけた GPU と SOFTWARE の差の T2b も 2026-11-19 完了・§4）(3) 不具合の再発を止める仕組みと公開 API の固定（T3・T4）(4) その上で大きな移動を伴う整理（A8・S1・A7・S2・S3 完了）(5) 公開 API を足す作業（A14・A10。A9 は完了）は S2・T4 の後で、D1 より前 (6) 下限に近い速度の改善（B10 の残り）と着手条件が成立していない項目（B4 ほか）は後ろへ下げる。

| 順位 | ID | 項目 | 内容 | 見込み | 状態 | 検証（合否線） |
| :--: | :-- | :-- | :-- | :-- | :-- | :-- |
| 1 | **A1** | 回帰ゲートを毎回の CI へ（環境に依らないテストと確保量） | 実装済み（2026-11-17）: ①`build.yml` に `:core:test` を足した（GPU・OS の書体に依るテストは `assumeTrue` で自分から中断。実時間で合否を決めるテストは `@Tag("timing")` を付けて `-Ptest.excludeTags=timing` で外す・§6-105）②`:core:costGate`（`CostGateMain`）: シナリオごとに新しい JVM・ワーカー 1 本・簡易書体で 22 シナリオ（T2 で実文書を足して 56）（描画なし 17 ＋ **ソフトウェア描画 `cpu` 5**）を回し、1 フレームの確保量（±5%）と経路の回数（完全一致）を `core/src/bench-crossengine/costgate-baseline.txt` と比べる（`build.yml`・`scripts/ci-linux.sh`）③**時間の推移**: `full-tests.yml` の `timing` ジョブ（毎晩・手動）が JMH `PipelineBench`（`-f 5`）の JSON を成果物 `jmh-nightly` に 90 日残す。時間は合否に使わない⑤**描画バックエンド側の確認**: `cpu` の 5 行は確保量が実行ごとに ±0.1% 以内で安定するので入れた。`-Denderframe.paint.nodiffpatch=true` で `cpu` の 3 行（hover・text・style。`+146〜155%`）だけが赤になる（描画なしの行では赤にならなかった損傷範囲・ラスタの退行を拾う）。GPU 側の差分再生は `GlDamageParityTest` が担う（GPU が要るので CI では中断）。**残り**: ④利用者が push した後に GitHub の CI で実際に回ることの確認と、夜間の全テスト 7 日連続の緑 | 退行が push の時点で見つかる。時間の揺れ（±10〜15%）に依らない合否線 | 実装済み・手元で確認済み（Windows は `:core` 全件・ルート全件が緑、`costGate` 22 シナリオ緑。`scripts/ci-linux.ps1`〔WSL・GPU なし〕で `build test`・`costGate`・`runGameTestServer` 成功）。**2026-11-18 に push 後の CI を確認したら、`build.yml` は `:core:test` を足した 11-17 から毎回赤だった**（`Core tests` で止まるので、`costGate` とゲームテストのサーバーは CI で一度も走っていなかった）。原因は 3 つで、どれも手元（Windows・WSL）では緑: ①OS の書体に依る画素のゴールデン（`GoldenFrameTest`・`RealDocumentFramesTest`）。GitHub の ubuntu-latest は 2026-10 から Ubuntu 26 で、WSL の Ubuntu 24 と入っている書体が違う → 画素のファイルに書体の環境の指紋（書体ファイルの相対パスと大きさの CRC。`FontEnvironment`）を書き、違う環境では画素の比較だけを飛ばす（表示リストとヒットテストは比べる）②`ColrV1EmojiTest` が、ビットマップ（CBDT）版の Noto Color Emoji を COLR の書体として選んでいた → v0 のレイヤーを持つ書体だけを選ぶ ③`LayoutParallelProbeTest` が壁時計の割合を 1 回だけ測っていた（2 コアの CI で下回る）→ 5 回の最良。修正の push の後、CI の `Core tests` は緑。初めて走った `costGate` は確保量の 2 行だけが赤（`machine_ui open` `+5.0%`・`guidebook text cpu` `-19.5%`。経路の回数は全行一致）。次の回は別の 2 行（`animation_test hover` `+8.1%`・`hud_test open` `+5.0%`）で、2 コアの CI の機械は実行ごとの揺れが手元より大きいので、CI だけ既定の許容を `±12%` にした（`build.yml` の `-Pcostgate.tolerance=0.12`。行ごとの `allocTol` は既定より広いときだけ効く。手元は `±5%`）。CI では失敗の一覧を注記（`::error`）にも出す（認証なしの annotations API で読める）。**2026-11-18 の `438b63c` で CI の全段（ビルド・`:core` のテスト・`costGate`・ゲームテストのサーバー）が緑**。残りは夜間の全テスト（`full-tests.yml`）7 日連続の緑 | `build.yml` が 10 分以内で `:core` のテストと `costGate` を回し Windows と Linux で同じ合否・近道を 1 つ無効にすると赤（確認済み: `nochildpatch`・`notentative`・`noshare`・`nomemo`・`noshift`）・夜間の全テストが 7 日連続で緑 |
| 2 | **T3** | 不具合を直したら固定テストを残す仕組み（回帰カタログと種の蓄積） | §6 の 122 項目のうちの不具合（測定キャッシュの §6-5・§6-20・§6-38・§6-55、平行移動の §6-3、節の状態の §6-9 ほか）、A6b で直した近道のずれ 20 件、W8・W9・W9b のどれにも、テストの側から元をたどる印が無い（テストの中の `§6-` の参照は 0 件）。直した不具合が別の整理で戻っても、どのテストが守っているかが分からない。①`regression-catalog.txt`（元〔§6-N・計画の ID・コミット〕\| テストのクラス#メソッド \| 1 行の説明）を置き、`RegressionCatalogTest` が「載っているテストが実在する」「§6 で不具合の印を付けた項目はすべて載っている」を検査する ②過去の不具合を洗い出し、欠けているテストを足す（§6 の不具合の項目・`core` の修正コミット 24 件・`shortcut-matrix-known.txt`）③**種の蓄積**: `MutationScenario` を実文書と乱数の種で回す差分ファズ（増分の結果 = 一から作った結果）を夜間（`full-tests.yml`）に新しい種で回し、ずれた種を `mutation-seeds.txt` に足して、以後は毎回の CI で固定の種として回す（見つかった不具合がそのまま回帰テストになる）④運用ルールに「不具合を直したら同じコミットでカタログに 1 行」を足す | 直した不具合が戻らない。整理で戻っても、守っているテストの名前から原因へ直行できる | 実装済み（2026-11-19）: ①`regression-catalog.txt`（50 行。元は §6-N・計画の ID・コミット）と `RegressionCatalogTest`（テストが注釈つきで実在する・`gradle:タスク`が登録されている・§6 の〔不具合〕の項目が全件載っている・カタログの §6-N が実在する）②§6 の不具合 31 項目に〔不具合〕の印を付け、全件に守るテストを対応させた。GPU のテストでしか守られていなかった §6-104 には CPU で回る `MovingChainPatchTest` を足した。§6 の重複した番号 53・55 の 2 つ目は 53b・55b ③差分ファズ `MutationFuzz`（`:core:mutationFuzz`。`IncrementalVsFullTest` と同じ比較）・夜間の `mutation-fuzz.yml`（日付で決まる種。ずれた種は注釈と成果物 `new-seeds.txt`）・固定の種 `mutation-seeds.txt` を毎回の CI で回す `MutationSeedRegressionTest`。**経路の確認**: `LocalPatches` の float・位置指定の判定をわざと外して `-Pfuzz.record` で回すと、layouts の種 6 が見つかって表へ入り、`MutationSeedRegressionTest` が赤になった（戻して緑）④運用ルール 7。**初回のファズ（種 9〜32）で、既定のままの実際のずれが 7 本見つかった**（§6-109）: nested の 3 本は子パッチが祖先の幅を追わない不具合で直した（§6-108）。layouts の 4 本も 2026-11-19 に直した（§6-109。4 つの別々の不具合）。種 1〜100 で見つかった別の 4 本は「既知:」で残した。**既知の 4 本も 2026-11-19 に直した**（§6-112: 子パッチが float・相対位置の箱の高さの変化と寸法 0 になった祖先を追わない・表の min-content をセルを測る前の装飾から行に記憶）。続けて種 101〜300 で見つけた 4 本（layouts 137・145、nested 131・195）も直した（§6-113）。**種 1〜500（1,500 本）のずれは nested 339 の 1 本だけ**（0.07%。初回の種 9〜32 は 10%、種 1〜100 は 1.3%）。**nested 339 も 2026-11-19 に直した**（§6-117: 内在幅で寸法が決まる祖先を越えた cutoff。全面の再レイアウトも同じ古い値になる形）。続けて、ファズの全面を「測定の記憶を捨てて組む」形にして、全面も古い値を返す形を見つけられるようにした（§6-118: 配置の記憶の不具合 2 つを直した）。**「既知:」15 本も 2026-11-19 に直した**（§6-119: 配置の記憶の 1 つと子パッチの 6 つ。7 つの直しごとに固定のテストを足し、直しを外すと赤になることを確認。表の「既知:」は 0、種 1〜1500〔4,500 本〕のずれも 0）。**残り**: 夜間のファズ（`mutation-fuzz.yml`。2026-11-19 に追加、毎日 18:30 UTC）の 7 日の稼働 | カタログの検査が緑・§6 の不具合の項目の全件にテストがある・夜間のファズが 7 日動き、わざと壊した近道で見つかった種が固定の種へ入る経路を 1 回確認 |
| 6 | **B10** | 短いフレームの GPU 時間を下げる（損傷範囲の描画） | B3 の標本採取で、GL の `hover`（壁時計 `0.18 ms`）の約 6 割は GPU の完了待ち（`glClientWaitSync`＝GPU が 1 フレーム約 `0.15 ms`）で、CPU 側は `0.022 ms`（§6-62）。毎フレーム 1,238 図形をすべて再生して全面を描いている。変わった項目の損傷範囲（`planDamage`）だけをシザーして描く（GL/Vulkan）か、変化が無い領域は前のフレームの結果を使う。ソフトウェアの `hover` も、ラスタ（`TileQueue.flush`）が 6 割なので同じ損傷範囲で縮める | GL `hover` mean `0.18 → 0.10 ms`・p50 `0.15 → 0.08 ms`。ソフトウェア `hover` のラスタ時間 半減 | **GL/Vulkan は一部達成（2026-11-16）**: 差分パッチで書き換えたスパンの旧・新の範囲（積んだ図形のデバイス画素の外接矩形を実測して記録）の和だけを、シザー付きの `CLEAR` で消して再生する（範囲外は前のフレームの結果）。showcase の GL: `hover` mean `0.173 → 0.105 ms`（p50 `0.150 → 0.092`）・`text` `0.174 → 0.081`・`style` `0.170 → 0.073`。`GlDamageParityTest`（リスト・showcase・影多めの文書・Vulkan）で部分描画あり/なしが毎フレーム全画素一致。`-Denderframe.gpu.nodamage=true` で無効。**再計測（標本採取）**: `hover` mean `0.104–0.109`・p50 `0.091–0.094`。残りの内訳は、GL のフェンス待ち `34%`（GPU/ドライバの往復。CPU では縮まらない）・再スタイル `15%`・差分の表示リスト更新 `9%`・`Scene3DDomSync.find` `3%`（シーンの無い文書では走査を省いた）。**ソフトウェアの損傷範囲は既にある**（`planDamage`。`hover` は `partial=6600 / full=1`）が、showcase の `hover` はボタンの光（ぼかし影）で損傷が広く、ラスタが `0.83 ms` のまま（損傷なしは `1.05 ms`）。**残り**: ①`hover` mean `0.10` 未満・p50 `0.08` 未満は、フェンス待ちと再スタイルの下限に近く、CPU 側の小さな最適化の積み重ね（誤差内）か、GPU の往復を減らす設計（1 フレームに複数の更新をまとめる）が要る ②層・CPU オーバーレイ・差分パッチに入らない文書（影・グラデーションが多い／常時アニメーションがある）は全面のまま（`GlDamageParityTest` はそれらでも全画素一致を確認） | `FineProfileMain hover showcase gl/cpu`（`-Pprofile.sample=1` で内訳）。損傷範囲あり/なしで全シナリオ画素一致（`GpuBaselineTest` の `-Dgpu.baseline.exact`、`ParentRelativeParityTest`）|
| 7 | **A11** | WPT 参照比較の合格を 31% → 60% へ | 部分集合 260 組の合格は 81（flexbox 55 / 120・grid 12 / 80・text 14 / 60）。速度の比較は「同じ絵が出る」ことが前提なので、grid 15% のままでは「Chrome 超え」を機能の部分集合に限定して言うしかない。①不一致 179 組を原因で分類する（未対応のプロパティ・値／配置のずれ／書体・行の高さ／参照側が描けていない）。分類は `WptReftestRatchetTest` の出力に差分画素数と最初に食い違う箱を足して機械的に取る ②件数の多い原因から直す（grid を先に）③合格した組は従来どおりラチェットに載せる | 条件 d（§1.4）の値が上がり、README の対応範囲の記述に根拠が付く | 合否線（156）を達成（2026-11-18: `81 → 112 → 136 → 149 → 160`。第 4 段: flex の rtl を縦書きの局所座標でも、要素の間の改行入りの空白を節として残す（子パッチの挿入位置は箱のある兄弟まで遡る）、テキストの節にセレクタを当てない、表の中の空白は white-space に依らず捨てる、縮小幅の箱の min-width / max-width、flex 容器のベースライン・匿名の文字の項目のベースライン・fieldset の legend のはみ出し、内在幅で run の境目の空白を数える、空白の側の white-space で折り返す、`text-wrap: balance`、capitalize が要素の境目をまたぐ語、要素の境目をまたぐ字の詰め。第 3 段: 表の height と caption、不定の列での flex-basis の %、DOM のテキストを原文で持つ（CSS の `white-space: pre` / `pre-wrap` で空白と tab が残る。畳むのはレイアウト）、tab 位置と `tab-size`、`white-space: break-spaces`、`text-align: match-parent`、`width: 0` の箱での折り返し、要素の境目の `letter-spacing`、flex の `direction: rtl`、flex の `visibility: collapse`、grid の静的位置の領域を内容ボックスに。第 2 段で直した原因（既知の機能だけ。grid-lanes は対象外）: 表が内容より狭くなる（flex 項目・`width` 指定）、揃えの既定が実質 safe だった（`center` / `end` がはみ出しても始点詰め）と `safe` の無視、空の箱のクリアランス（clearfix の `::after`）、先頭の BOM、grid の絶対配置の包含ブロック（`grid-area` の領域）と静的位置の揃え（grid / flex）、grid の `direction: rtl`、祖父母以上が包含ブロックの絶対配置で `top`+`bottom` の高さが 0、`gap` の % の基準、論理プロパティ（`inline-size`・`margin-inline-*` など）、`text-align: justify` / `text-align-last` / `text-justify`、双方向の制御文字が豆腐で描かれる、折り返した行の頭の空白。ハーネスは測定用の概算書体（`FontResolver.FALLBACK`）をやめてシステム書体で比べる（描画は字形で描くので、文字列の区切り方が違うテストと参照で字の位置が食い違っていた）。①分類は `-Dwpt.reftest.diag=true` で `build/wpt-diag/summary.tsv` と画像、`-Dwpt.reftest.dump=<名前>` で箱の木。直した原因: 参照の XHTML（`<style>` の CDATA・`<div/>`）を HTMLとして読んでいた `+26`、比較を透明な地で取っていた（WPT は白いキャンバス）`+3`、flex / grid 容器の空白テキストが項目になっていた・行の中の float の中身の断片を行の配置が消していた・空白の後の空白が畳まれていなかった `+2`。grid 40 は `grid-lanes` 未実装（43 組）を入れないと届かない。合否線は分類の結果を見て 1 度だけ見直してよい） | 合格 156 / 260 以上・grid 40 / 80 以上・flexbox 90 / 120 以上・「原因別の件数」が §2 の WPT 行に載る・時間 KPI が悪化しない |
| 12 | **B4** | GPU 被覆率 100% | G0 のヒストグラムで多い順に、CPU 層へ落ちる図形（回転した文字・縮小画像の多点標本・破線以外の複雑な枠・パス）を GPU の図形にする。パスは曲線を 2 次ベジェの区間レコードにして被覆率をシェーダーで解析的に出す（頂点分割なし）。`GpuCaps.compute` があるデバイスでの計算シェーダー版は効果を測ってから。**着手条件: showcase 以外の実文書で被覆率 95% 未満** | CPU 層の転送と `flushOverlay` が消え、GPU 経路のフレームが項目数だけで決まる | **計測済み・着手条件は未成立（2026-11-16）**: 同梱の実文書 9 本の GL 被覆率は、`surface_test` だけ `98.1%`（落ちた `GRADIENT` は全部「クリップが 5 段で GPU の上限 4 を超える」ため。同じ空間で別の部品を含む直角のクリップを `finishClip` で除いて `100%` に）。残りは `animation_test` の縦横比が違う変形（`scale(x, y)` のアニメーション）の下の角丸枠 `1` 個/フレーム（`99.9%`。円の角は一様な縮尺が前提で、楕円の角を GPU の式へ足すか CPU に任せるかが論点）。`FineProfileMain` は `.html` のファイル名を受け取る（`idle voidwave.html 300 gl`）。着手条件（`95%` 未満）は成立しないので、本体（パス・回転した文字ほか）は着手しない | §2 の被覆率が全文書 100%・`CpuFallbackOfRareShapesTest` の対象が空・`SOFTWARE` と画素一致 |

### A2-4. 旧 §3.1 / §3.2 設計詳細（X3-0〜X3-3 / G0〜G4 実装前設計・2026-11-16 分担）

### 3.1 X3 設計詳細（3D シーングラフ統合）

> **G 系との関係（2026-11-15）**: 以下は G 系の追加前に書いた設計で、GPU 側を OpenGL 3.2 前提で記述している。X3-1 はこの設計のまま GL で完了させ、G1・G2 で RHI（§3.2）へ移す。X3-2 以降は最初から RHI の上に実装するので、`GlScene3DRenderer` は `core.paint.gpu` の `Scene3DRenderer` に、「TBO 3 面リング」は RHI の「レコード列」に、テクスチャユニットの割り当てや GL 状態の退避・復元は `GlDevice` の内部事項に読み替える。シーングラフ・メモリ配置・4 つの統合モード・ソフトウェア描画・宣言的 HTML の設計は変わらない。クリップ空間の深度（GL は −1〜1、Vulkan は 0〜1）は `GpuCaps` の補正行列を射影に掛けて吸収する。

各マイルストーン完了時はラチェット 4 種（`LayerDependencyRatchetTest`・`PortabilityRatchetTest`・`ApiBoundaryTest`・`ArchitectureTest`）の全合格を必須ゲートとする。追加の外部ライブラリは入れない（JOML 1.10.5・fastutil・gson・LWJGL 3.3.3 は同梱済み）。

**3 本柱**

1. **ラチェット準拠の GL 非依存コア＋OpenGL 3.2 Core / GLSL `#version 150`**: 数学・SOA シーングラフ・BVH/レイキャスト・glTF ローダ・ソフトウェアラスタライザは GL/AWT 非依存の純 Java 層に閉じ、GL 呼び出しは `core.paint.gl` のみ。GL 3.3+ 固有の `glVertexAttribDivisor` や SSBO/Compute は使わず、`GlRectRenderer` と同じ `GL31.glDrawElementsInstanced`＋`gl_InstanceID`＋`samplerBuffer`/`isamplerBuffer` TBO（`GlVramDevice` の 3 面リング）でインスタンシングする。
2. **HTML/CSS と 3D の 4 モード融合**（下記）。
3. **Host SPI とヘッドレス描画の両立**: ビルトイン `<scene-3d>` と Minecraft 用 SPI `<mc-scene>`（`WebSceneContext`/`CoreGpuSurface`）を整理し、`SOFTWARE` ターゲットでも同一シーンモデルを 64×64 タイル並列で描く。

**制約とラチェット**

- **層配置**: `core.scene3d` は `dom`/`layout`/`css`/`paint` に依存しない独立モデルとして **L2 相当**に置き（§1.3）、`LayerDependencyRatchetTest.LAYERS` に登録する。L4（`layout`）・L5（`paint`）・L6 が一方向に依存できる。`scene3d` から上位層への参照が要る場合は、L2 に小さなイベント/HUD 出力インターフェースを置き L5/L6 が実装する（依存性逆転）。
- **移植性**: `core.scene3d` は OpenGL/GLFW/AWT を一切 import しない。GPU 状態は `PersistentVramBuffer` ベースの論理記述子（GL 非依存 ID＋`ByteBuffer` 影データ）で持ち、FBO・TBO バインド・シェーダー実行は `core.paint.gl` に集約する。新規 GL クラスは `core.paint.gl` に置き、`PortabilityRatchetTest.ALLOWED`（現 7 ファイル）の拡張は最小限で監査下に置く。
- **公開 API**: `api.scene3d.*`（`Scene3DController`・`Hit3DResult`・`Scene3DViewportSpec`）は純粋なインターフェース/レコードで、`WebViewSurface`（実装は `EngineSurface`）経由でハンドル取得・カメラ操作・ピック結果取得を提供。`core.*` 内部型は公開シグネチャに出さない。
- **設計品質不変条件**: 属性（`data-scene-*`・`data-bind-position` 等）の解釈は `Bindings` 1 箇所のみ（M4）／跨ぎ参照されるデータ型（`MeshDesc`・`Hit3D`・`CameraSpec` 等）はトップレベル化（M7）／3D→DOM 属性同期は `DomUtils.updateAttribute` 経由（`node.attrs.put` 直書き禁止）。
- **GL 状態**: 2D の `GlRectRenderer` は `uAtlas=1`・`uImgTex=2`・TBO `16..27` を使うため、3D は FBO 描画内で自己完結にユニットを割り当て（PBR 素材 `0..3`・IBL/影 `4..6`・3D TBO `8..11`）、描画後に DepthTest/CullFace 無効・Blend 復元・ActiveTexture0 へ戻す。ホストの GL コンテキスト共有時（将来の `CoreGpuSurface` 直描画）は `GL_DEPTH_TEST`・`GL_DEPTH_WRITEMASK`・`GL_CULL_FACE`・`GL_FRAMEBUFFER_BINDING`・`GL_VIEWPORT` を退避・復元する。

**4 つの統合モード**

| モード | 内容 | 接続点 |
| :-- | :-- | :-- |
| **A** `<scene-3d>` ビューポート | CSS レイアウトで決まる矩形に、`GlScene3DRenderer` がオフスクリーン FBO（MSAA またはカラー＋深度、サイズ変更時のみ再確保）へ描き、`GpuSink.drawTexture`（`PropertyTrees.OP_IMAGE`）で 2D と合成。FBO は下端原点なので `compositeRetained` と同じ `sy < 0` で正立。SDF 角丸・`ClipRect`・`opacity`・`Matrix2D` は追加実装なしで効く。テクスチャは軽量レジストリで `long handle` へ写してコピーゼロ | `LwjglGlRenderBackend.drawTexture` |
| **B** 空間パネル（`HTMLMesh`） | DOM サブツリー（`<ui-panel-3d selector=…>`）を `LayerAtlas`（または専用 FBO）へ焼き込み（汚れた部分のみ再描画）、3D の板ポリゴンへ貼付。`Raycaster3D` の交点 `(u,v)` → `(u·W, v·H)` を対象サブツリーの `HitTestIndex` へ委譲し、傾いた HTML のホバー・クリック・入力・スクロールを成立させる | `LayerAtlas`（現在未接続）・`HitTestIndex` |
| **C** 3D→2D 投影 HUD | アンカーのワールド座標をフレーム末に `M_VP` でバッチ投影し、背面（`ndcZ > 1` またはカメラ後方）を除いて紐付く 2D 要素の位置/可視性だけを更新。DOM 書き換え・再レイアウトなしで O(連携数) | `PaintSceneDriver`/`OffsetItemList` |
| **D** CSS 3D Transforms | 現状の `Transforms.java` は `rotateX/Y/3d`・`translate3d`・`matrix3d` を 2D 正射影近似し `perspective`/`translateZ` を捨てている。`perspective`/`perspective-origin`/`transform-style: preserve-3d` の 4×4 射影を追加し、`preserve-3d` 配下は共通深度または Z ソート付き 3D クアッドで描いて遠近と `backface-visibility: hidden` を実行 | `Transforms.java`・`GlRectStream` |

**パッケージ構成**（公開データ型はすべてトップレベル、M7）

```text
api/scene3d/        Scene3DController / Hit3DResult / Scene3DViewportSpec   （公開 API）
core/scene3d/       Scene3DArena（OffHeapColumns の SOA）・Scene3DWorld・GeometryPool・MeshHandle・
                    MaterialPool/MaterialDesc・InstancedBatcher・Scene3DUploadPlan・GltfBinaryLoader・
                    Raycaster3D（フラスタム＋BVH）・Transform3DSystem・Animation3DPlayer   （L2 相当・GL/AWT/paint 非依存）
core/paint/gl/      GlScene3DRenderer（FBO/MSAA・TBO・DualKawase Bloom）・Scene3DShaders（GLSL 150）
core/paint/software/ SoftwareScene3DRasterizer（TileQueue/Op と同一パッケージ）
```

**メモリレイアウト（`Scene3DArena`、64B 整列の連続ダイレクトメモリ）**: `localTrs` 10 float（Pos3・Quat4・Scale3）／`worldMat` 16 float／`bounds` 6 float（AABB）／`topology` 4 int（parent・firstChild・nextSibling・flags）／`renderRef` 4 int（meshId・materialId・entityTag・layerMask）。`Transform3DSystem` はフラットなトポロジカル配列を `VarHandle` で走査し、フレームループ内の `Vector3f`/`Matrix4f` 確保を 0 にする。

**ジオメトリとインスタンシング**: 単一の巨大 VBO/IBO（`GeometryPool`、頂点 `Pos3+Norm3+UV2+Tangent4`＝48B、スキニング時はボーン Index/Weight 追加。メッシュは `(baseVertex, indexOffset, indexCount, localBounds)`）。`InstancedBatcher` がフラスタムカリング後に `(meshId, materialId, blendMode)` でソートし、128B/instance（Model 64B＋Normal/パッキング 36B＋MaterialID/EntityID/Flags 28B）を永続バッファ影領域へ書く。頂点シェーダーは `texelFetch(uInstanceTboF, base + (gl_InstanceID + uBaseInstance) * 8 + k)` で引く。

**マテリアル・ライティング・ポスト**: Cook-Torrance（GGX・Smith・Schlick）。(1) 環境光＋指向性（影マップ任意）、(2) 動的ポイント/スポット（ライト TBO、最大 64 灯程度）。アルファテストと半透明（後→前ソート）のパスを分離。FBO は `RGBA16F`（`RGBA8` 切替可）で発光を抽出し、`DualKawase`（`passes(sigma)`/`offset(sigma)`、現在 GL 未接続）に従う Down/Upsample を実行、ACES Filmic で `RGBA8` へ解決してから `drawTexture`。

**アセット**: `GltfBinaryLoader` は gson＋`ByteBuffer` のみで glTF 2.0/`.glb` と `data:` URI 埋め込みテクスチャを読み、`GeometryPool`/`MaterialPool` へ登録。画像のデコードは既存の `SurfaceImage`/`Media` を再利用し重複実装しない。

**レイキャストと `HitTestIndex`**: `ElementType.SCENE` へのヒットは既に `UIHitResult(el, localX, localY)` を返す。`(localX/w, localY/h)` → NDC → 逆 VP からワールドレイ → `Scene3DArena` の AABB/BVH と O(log N) で交差 → ノード ID（モード B は UV も）を `Hit3DResult` で返し、`click`/`pointermove`/サブツリー転送へ繋ぐ。

**ソフトウェアフォールバック**: `SoftwareRenderBackend` の `case SCENE -> { }`（3D 未描画）を置き換える。`TileQueue`/`Op`/`Scratch` はパッケージプライベートなので `SoftwareScene3DRasterizer` は同パッケージに置き、タイル単位 Z バッファ＋重心座標ラスタライズ（フラット/簡易 Phong＋テクスチャ補間）。静止カメラ・静止シーンでは `Pixmap` キャッシュを再利用し、`StaticFrameGate`/タイルダーティ範囲と協調する。ヘッドレス/CI で視覚回帰を回せる。

**宣言的 HTML**: 現在 `ReplacedElements.isHostScene` は `HostSyntax` 登録タグ（`<mc-scene>`）のみを `ReplacedKind.SCENE` にする。ビルトインの `<scene-3d>`（固有サイズ 300×150）を `ReplacedElements`/`BoxMeasurer` に追加し、内部の `<camera-3d>`・`<light-3d>`・`<mesh-3d>`・`<group-3d>`・`<ui-panel-3d>` は 2D ボックスを作らず `Bindings`/`Scene3DDomSync` で `Scene3DWorld` へ同期する。`UI.h("scene-3d", props, …)` でも構築でき、`WebViewSurface.scene3d(id)` が `Optional<Scene3DController>`（ノード追加・glTF ロード・カメラアニメ・`raycast(localX, localY)`）を返す。

```html
<scene-3d id="preview" style="width:420px;height:280px;border-radius:12px" data-orbit="true" data-bloom="true" data-fov="45">
  <camera-3d position="0 2 5" look-at="0 0.8 0"></camera-3d>
  <light-3d type="directional" direction="-1 -2 -1" color="#fff8e8" intensity="2.4"></light-3d>
  <mesh-3d id="hero" src="assets/models/crystal.glb" position="0 0 0" data-bind-rotation="heroRot"></mesh-3d>
</scene-3d>
```

**表示リストと静止最適化**: 現在 `DisplayListBuilder` → `ControlPainter.drawSceneElement` は空枠と Minecraft 用 `data-blocks` メタしか出さない。`RenderMeta`（または専用キー）へ `sceneWorldId` と更新版を載せ、両バックエンドが走査中に `Scene3DWorld` を引けるようにする。また `SCENE` は現在無条件に `timeDependent = true`・`isNeutral = false`・`rangeIsRetainable = false`（`StaticFrameGate`・`SoftwareRenderBackend`）なので、`Scene3DWorld.needsRender(frameNs)` を設け、自動回転・進行中アニメ・カメラ変更が無い静止ビューポートは前回の FBO/Pixmap を再利用して再描画を飛ばす。

**Minecraft ホストとの共存**: `<mc-scene>` は `WebSceneContext`（`EnderScreenDelegate` がドラッグ/ホイールを送り、`DisplayItemDrawer` が描画）で稼働中で、`CoreGpuSurface.drawable` は `case SCENE -> return false`（画面全体を 2D フォールバックへ落とす暫定、`enabled` も既定 `false`）。①ビルトイン `<scene-3d>` は Minecraft に依存せず `EnderEngine` 単体で完結させる ②`<mc-scene>` は `CustomElementPainter`/`HostSyntax` 経由で後方互換を保ち、将来 `CoreGpuSurface` を有効化するときは FBO 描画または `drawTexture` 外部合成へ切り替えて全画面フォールバックを撤廃 ③`EnderScreenDelegate` の入力は要素タグ/`RenderHints` で `<mc-scene>`（`WebSceneContext`）と `<scene-3d>`（`Raycaster3D`）を振り分ける。

### 3.2 G / B 設計詳細（描画 API 中立化と Chrome 超え）

**現状の診断（2026-11-15 の実測、§6-31・32）**

- OpenGL / GLFW への依存は `core.paint.gl` の 5 ファイルと `StandaloneViewer` に閉じている（`PortabilityRatchetTest`）。ただし GL 呼び出し 684 箇所（72 種類の関数）のうち 524 箇所が `LwjglGlRenderBackend` 1 ファイルにあり、そこに**窓とコンテキスト・GL 呼び出し・API に関係ない描画ロジック**（層の積み上げ、フィルタ段、字形アトラス、スクロール保持層、項目再生）が混ざっている。自由度が低い原因は GL そのものではなく、この混在。
- **データ面は既に API 中立**: 図形は `PropertyTrees` の固定長レコードとして `PersistentVramBuffer`（GL 非依存、`Device` 越し）へ書かれ、シェーダーが添字で引いて 1 ドローコールで描く。図形ごとの GL 呼び出しは無い。だから抽象化するのは 1 フレーム数十回の制御面だけで、仮想呼び出しのコストは KPI に出ない見込み（G1 の合否線で確かめる）。
- 中立でないのは 4 点: ①`LwjglGlRenderBackend` の混在 ②GLSL 150 のシェーダー 7 本が 4 ファイルに散在（`core.paint.AlphaMask` にもある）③公開 API（`Target` は `SOFTWARE` / `GPU_OFFSCREEN` のみ、`openWindow` は GLFW 固定、`EngineSurface` が GL 実装を直接 `new`）④ビルド（`lwjgl-opengl`・`lwjgl-glfw` が `api` 依存で全利用者へ伝わる）。

**採らない案**

| 案 | 採らない理由 |
| :-- | :-- |
| `bgfx`（LWJGL に束縛あり。GL / Vulkan / D3D / Metal を一括） | 永続マップしたレコード列をシェーダーが添字で引く設計（原則 1・2）と合わない。ホストの既存 GL コンテキストへの相乗りが難しい。独自のシェーダーコンパイラとネイティブが増える |
| `wgpu-native` を唯一の GPU 層にする | LWJGL 3.3.3 に束縛が無い。FFM は JDK 21 ではプレビューで「起動フラグを要求しない」（`KernelBaselineTest`）に反する。Minecraft 1.21.1 の GL 3.2 コンテキストへ相乗りできない。→ RHI の形を WebGPU に寄せておき、条件つきの G6 へ |
| GL を捨てて Vulkan だけにする | 現行ホスト（Minecraft 1.21.1）が GL 3.2。GL 実装は RHI の 1 実装として残す |
| 抽象を作らず Vulkan 版バックエンドをもう 1 本書く | 層・フィルタ・アトラス・再生のロジック（`LwjglGlRenderBackend` 2,352 行の大半）が二重になり、X3 でさらに倍になる |

**RHI の形（`core.kernel.gpu.rhi`、L1。OpenGL・Vulkan の型を一切出さない）**

```java
public interface GpuDevice extends AutoCloseable {
    GpuCaps caps();                               // 永続マップ・storage buffer・計算シェーダー・最大テクスチャ寸法・時間計測・クリップ空間の補正
    GpuBuffer createBuffer(BufferDesc d);         // 用途: RECORD（シェーダーが添字で引く列）/ UNIFORM / STAGING
    GpuTexture createTexture(TextureDesc d);      // RGBA8・R8・RGBA16F・深度、描画先にできるか、MSAA
    GpuTexture wrapExternal(long nativeHandle, int w, int h);  // ホストのテクスチャ（コピーなし）
    GpuPipeline createPipeline(PipelineDesc d);   // シェーダー ID・ブレンド・深度・描画先の形式を前もって固定
    GpuFrame beginFrame();                        // リングの面を進める。フェンス待ちはここだけ
    long nativeHandle(GpuTexture t);              // 結果をホストへ渡す（TextureHandle の中身）
}
public interface GpuFrame {
    GpuPass beginPass(GpuTexture target, LoadOp load, int clearArgb, int[] scissor);
    void upload(GpuTexture dst, int x, int y, int w, int h, ByteBuffer pixels);   // 字形アトラス・CPU 層の差分
    void copy(GpuTexture src, GpuTexture dst, int[] region);                      // backdrop・層の解決
    void readback(GpuTexture src, int[] region, ByteBuffer dst);                  // snapshotTarget・pixmap()
    void end();                                                                   // 提出＋フェンス
}
public interface GpuPass {
    void bind(GpuPipeline p, GpuBindings b);      // レコード列・テクスチャ・パスごとの uniform ブロック
    void drawInstanced(int vertexCount, int instanceCount, int baseInstance);
    void end();
}
```

設計規則:

1. **粒度は「パス」と「バッチ」**。図形ごとに RHI を呼ぶ口は作らない（レコード書き込みは `GpuBuffer` の `ByteBuffer` へ直接）。1 フレームの RHI 呼び出し数は層の数に比例し、図形の数に比例しない（`RecordingGpuDevice` で固定）。
2. **状態は明示**。ブレンド・シザー・描画先は `PipelineDesc` と `beginPass` の引数で渡し、「現在の GL 状態」に依存しない。ホストのコンテキストに相乗りするときの退避・復元は `GlDevice` の `beginFrame` / `end` の内側に閉じる。
3. **座標規約は RHI 側で 1 つ**: 左上原点・事前乗算アルファ。GL の下端原点（今の `uYSign`・`uFlip`・`sy < 0`）と `gl_FragCoord` の原点の違いはデバイス実装とシェーダーの前置きが吸収し、`GpuRenderBackend` には出さない。
4. **レコード列の引き方は能力で選ぶ**: GL 3.2 と Vulkan は texel buffer（今の `samplerBuffer`＋`texelFetch` のまま）、storage buffer しか無い API（WebGPU）は G6 でマクロを差し替える。半精度の列（`uPrH`）は storage buffer に対応する型が無いので、その場合は `unpackHalf2x16` で読む。
5. **同期**: `GlFenceRing` の 3 面リングを「飛行中のフレーム数」として一般化する（Vulkan でも同じ 3 面）。
6. **デバイスの作り方は 3 通り**: 自前の窓（`:core-glfw`）／窓なし（GL は見えない窓、Vulkan は窓も GLFW も不要）／ホストの採用（GL は共有コンテキスト = 今の `sharedWith`、Vulkan はホストの instance / device / queue を受け取る）。
7. **スレッド**: デバイスの呼び出しは 1 本の描画スレッドから。B7 まではホストの呼び出しスレッドと同じ。

**`LwjglGlRenderBackend` の分け方（G1）**

| 今の中身 | 行き先 |
| :-- | :-- |
| 層の積み上げ（`beginGroup` / `endGroup` / マスク）、フィルタ段の計画（`stages`・`blurDownsampled`）、backdrop、字形アトラスの割り当て、スクロール保持層の方針、項目再生（`spanReplay`）、CPU 層の重ね順（`flushOverlay`）、統計 | `core.paint.gpu.GpuRenderBackend`（API 中立。`RenderBackend`＋`GpuSink` を実装） |
| `GlRectRenderer`・`GlRectStream` の描画順・クリップ表・再生の記録 | `core.paint.gpu`（中立）。GL 呼び出しだけ `GlDevice` へ |
| FBO・テクスチャ・シェーダーのコンパイル・uniform・ブレンド・シザー・`glReadPixels`・時間計測、`GlVramDevice`・`GlFenceRing` | `core.paint.gl.GlDevice`（`GpuDevice` の GL 実装） |
| GLFW の初期化・見えない窓・共有コンテキスト・`present` / `pollEvents` | `core.paint.gl.GlContext`（G4 で `:core-gl` と `:core-glfw` へ） |

進め方は「動く状態を保ったまま 1 種類ずつ移す」: ①`RecordingGpuDevice` と `GlDevice` の骨組み ②テクスチャと層 ③パイプラインと uniform ④レコード列 ⑤窓。各段で G0 の基準画像と全画素一致を確認し、`PortabilityRatchetTest.ALLOWED` を縮める。既存の GL テスト（`ItemReplayPixelTest`・`RetainedScrollTest`・`SharedContextBackendTest` ほか）はそのまま合否線に使う。

**シェーダー（G2）**: 源は GLSL のまま 1 か所（`core.kernel.gpu.shader`、L1 の文字列）。前置き（プリアンブル）で次を切り替える: 版（GL `#version 150` / Vulkan `#version 450`）、`EF_VERTEX_ID`・`EF_INSTANCE_ID`（`gl_VertexID` / `gl_VertexIndex`）、`EF_FRAG_Y(viewH)`（`gl_FragCoord` の原点）、`EF_REC_F(buf, i)`（レコード列の読み方）、束縛の `layout` 修飾。バラの `uniform`（`glUniform*` 約 60 箇所）はパスごとの 1 ブロックにまとめる（Vulkan にバラの uniform は無い）。Vulkan 用の SPIR-V はビルド時に生成してリソースへ入れ、実行時にコンパイラを持たない。Metal / D3D 向けの変換（SPIRV-Cross）は G6 まで入れない。

**モジュールと公開 API（G4）**

```text
:core          api / common / core.*   OpenGL・GLFW・Vulkan へのコンパイル依存 0（ネイティブは FreeType のみ）
:core-gl       GlDevice・GlContext     OpenGL 3.2 Core。ホストの共有コンテキスト採用を含む
:core-vulkan   VkDevice                Vulkan 1.1。窓なしのオフスクリーン GPU
:core-glfw     WindowHost の GLFW 実装・StandaloneViewer
```

`EngineSurface` は GL 実装を直接 `new` せず、`GpuBackendProvider`（`ServiceLoader`）から描画先を得る（G4 で実装済み。provider は `RenderBackend` を返す）。`LayerDependencyRatchetTest` には `core.kernel.gpu.rhi`・`core.kernel.gpu.shader` を L1 として登録する。ルートの `CoreGpuSurface` は `GlDevice` の共有コンテキスト採用へ乗り換える（G5 の前段）。

**Chrome 超え（B 系）の考え方**

- 時間 KPI の大半は既に Chrome の `0.05x`〜`0.6x`（§2）。残る差は 3 種類で、対策が別: **負けている行**（幅変更・保持ヒープ → B3・B5）、**Chrome も落とす行**（varied `fontsize` → B2）、**測っていない行**（起動・遅延・正しさ → B1・B6）。
- Chrome の強みは合成スレッド（主スレッドが詰まっても滑らか）と GPU ラスタの網羅性。`:core` はスクロールと変形/不透明度アニメの CPU コストがほぼ 0（`scroll` `0.008 ms`）なので、同じ効果を「スレッドを分けずに」得ている。スレッド分離（B7）は論理側が重いホストが実際に出てから。網羅性は被覆率（G0 → B4）で数える。
- Chrome に構造上できないこと（§1.4 条件 c）は G 系の完了がそのまま成果になる: 任意 API のホストへのコピーなし埋め込み（G3〜G5）、GPU の無いサーバでの GPU 相当の決定的描画と Vulkan の窓なし描画（G3）、2D と 3D の単一パイプライン（X3）。
- B2 以降は Y1〜Y6・RTI と同じく**着手条件つき**。条件を満たさない計測結果が出たら、行を消して §4 に「検証済・見送り」で 1 行残す（§6-24 の運用）。

分担（2026-11-16）: B2 = Gemini、G4 = Claude（完了）、**X3-2 = Claude（着手。`core.scene3d`・`paint.software`・`paint.gpu` の 3D 部分。レイアウト／restyle には触れない）**。同じファイルを触らないよう、実装は Claude 側が別 worktree（`../efl-claude-b2`）で行い、通ったものを master へ fast-forward で取り込む。次に着手する行は、着手前にこの欄へ担当を書く。


### A2-5. 旧 §4 詳細実装記録（2026-11-20 整理前の原文）

## 4. 完了済み

| ID | 内容 | 効果・成果 | 主な検証テスト |
| :-- | :-- | :-- | :-- |
| A9 | ホスト拡張の SPI を `api` へ出す: 公開 SPI `api.spi`（`ElementProvider`・`ElementPainter`・`ReplacedKind`・`TagTemplate`。署名は JDK と `api.*` だけで、独自タグの展開は HTML 断片の文字列）・`common.ApiSpiBridge`（内部の SPI へ橋渡し。`core` は `api.spi` を知らない）・`Builder.i18n`／`resources`・Minecraft の `mc-*` の移行・`api` だけを参照するサンプル（`core/src/apiSample`）・`EnderEngine.createEvent`／`createAbortController` | 外部の利用者が内部パッケージに触れずに独自タグ・翻訳・リソースを差し込める。内部の整理が互換性を壊さない。`McTagMacros` が消え、Mc のタグ展開は DOM を触らない（`ArchitectureTest` の例外が 1 つ減った） | `ApiSpiProviderTest`・`ApiSampleTest`（サンプルのソースが `api.*` と JDK だけを参照・SOFTWARE で描ける）・`ApiEventFactoryTest`・`McTagMacrosTest`（11 件。展開結果の構造が従来と同じ）・`McHostElementsTest`・`ApiBoundaryTest`・`ApiSignatureTest`（`api-signatures.txt` に差分）・ルートの `verifyModJarServices` |
| S3 | 表示項目の種類の分岐の網羅: `ElementType` に `default` の無い switch 式の分類（`rasterRoute`〔ソフトウェア描画の振り分け〕・`isExternallyDriven`・`hasLiveWidgetState`・`isVolatileForDamage`・`drawsToBuffer`・`capturesPress`）を置き、`core` とルートの種類の switch 6 か所を呼び出しへ置換 | 種類を足すと分類の switch がコンパイルで直し漏れを知らせる。`default` のある種類の switch `0`。描画の分岐は `RasterRoute` の網羅形 | `ElementTypeClassificationTest`（分類の網羅・HTML から作れる全部品のソフトウェア描画・`case` を enum の外に書かないラチェット〔`core` とルート〕）・`:core` とルートの全件。GPU 側の種類ごとの確認は既存の `CpuFallbackOfRareShapesTest`（GPU が要るので GPU 無しの環境では中断）のまま |
| S2 | 要素の性質の表: `ElementTraits`（フォーム部品・値を持つ部品・文字入力・チェック可能・ラジオ・置換画像／メディア・既定のインライン・フォーカス可否・子の省略・クリック既定・役割ごとの名前つき判定）と `ElementSizing`（input / select / progress の最小内容幅と高さ・canvas / svg の固有寸法）。各段のタグ名比較を表の呼び出しへ置換 | 部品のタグ名を文字列で比べるファイル `15 → 1`（`HtmlParser` のみ）。部品の追加が「表の 1 行＋描き方」で済む。A9 の SPI の差し込み口が整った | `ElementTraitsTest`（表の振る舞い・列挙を表の外に書かない・タグ名を比べるファイル数のラチェット）・`ElementSizingTest`・`:core` 全件・`ArchitectureTest` |
| A2 | Chrome 比較を同じ書体（Arial）で測り直す（`-Pprofile.font` / `BENCH_FONT`。`run-matrix.sh`・`summarize-matrix.js`） | 負ける行は無し・`child` は互角（`1.03x` / `1.08x`）・varied-2000 `fontsize` は実書体で `8.20 ms`（予算超え → B11）。§2 の先頭行 | `run-matrix.sh … font chrome`（計測。テストではない） |
| A3 | ワーカー数の設定口（`-Denderframe.threads`・`EnderEngine.workerThreads`）と、ソフトウェア描画のタイル実行の同じプールへの統合。1 / 2 / 4 / 8 / 既定での計測 | varied-2000 `fontsize` は 8 本以下で予算超え（→ B11）。条件 b の表記を条件つきに訂正 | `ParallelLayoutEquivalenceTest`（ワーカー 2 / 4 本で箱の木・表示リスト一致、1 / 2 / 4 本で画素一致、1 本は配らない） |
| A5 | 並列実行の決定性: ①`core.*` の static 欄を分類（型で安全 158 件・分類つき 58 件〔不変の表 / ロックあり / 1 スロットの記憶 / 診断〕・要対処 0）②`StaticStateRatchetTest`（分類の無い欄・消えた欄・型で安全な欄の数 158 超で赤。検査器自身 1 件）と `static-state-classification.txt` ③`ParallelMutationStressTest`（60〜140 兄弟の文書へ乱数の変更列 1,000 回 × ワーカー 2 / 4 / 既定を直列と同じ順に当て、毎ステップ表示リスト一致）④全件 20 回連続で 1 回赤 → 原因は `StaleTextPatchTest`（§6-74）で修正。`CssParser.fontFaceCallback`・`DomUtils.innerHtmlParser` を `volatile` に | 並列あり/なしの不一致なし・順序・時間に依るテストの不安定さを 1 件解消 | `StaticStateRatchetTest`・`ParallelMutationStressTest`・`StaleTextPatchTest`（修正後に 15 回連続緑）・全件 20 回（修正前 19 / 20）→ 修正後 `build` 緑 |
| A6 | 近道の組み合わせ検証 `:core:shortcutMatrix`（`ShortcutMatrixMain`）: 近道スイッチ 37 個（`enderframe.*.no*` / `.off`。GPU 面を除く）を、既定・各単独オフ・ペアワイズ（どの 2 つの on/off 4 通りも通る 13 構成）の 53 構成で、別 JVM に同じ乱数の変更列（クラス・style・文字・子・マウス・リサイズ・ホイール・時間。cards / layouts / nested〔flex・grid の入れ子〕/ scrolllist / catalog の 5 文書 × 種 8・計 68 シナリオ）を走らせ、毎手の署名と画素を既定と比べる。既定の再実行と `-Xint` で壁時計に依らないことも確認。既知の不一致は `shortcut-matrix-known.txt` で固定（増えたら赤・解消したら表から外させる）。`ShortcutSwitchRatchetTest`（スイッチ数の上限 37・ペアワイズの被覆・検出器）。夜間 `full-tests.yml` の `shortcut-matrix` と `scripts/ci-linux.sh` | 近道を足すたびの検証が組み合わせまで自動で届く（約 1〜2 分）。過去の不具合（§6-20 のエイリアス `measuredOutputs = o`）を再注入すると赤になる（確認済み。最初の 3 文書・種 4 では赤にならず、nested 文書と種の追加で捕まった）。20 件の不一致の解消は A6b | `:core:shortcutMatrix`・`ShortcutSwitchRatchetTest`（4 件） |
| A6b | 近道の増分が全面の結果とずれる不具合の解消（A6 で見つかった 20 件）: ①子パッチから float・位置指定の子の増減を外す（layouts 4 件）②挿入位置の探索で flex / grid アイテム・インラインレベルの箱をブロック層に数えない（nested の並び順）③子パッチ後の上下の枠線の高さを負にしない・絶対座標の後続の箱の高さの二重引き④背景を譲った要素の `bg_root` を局所パッチで合わせる・描いたあとに動きの記録を取り直す（差分描画の遷移の取り残し）・記憶を切ったら子パッチを使わない⑤**枠線を重ねる表の外周の枠線を測定の前に決める**（新しい箱の最初の測定だけ利用可能幅を 2px 超えていた。正しかったのは増分の側）⑥**記憶の無い箱を描き直す clip を、祖先の記憶ではなく親の中身の clip から取る**（スクロールする箱の中の表のセルなど、既定でも起きていた）⑦**スクロール量（減速の途中・行き先）を整数 px に揃え、つまみは作ったときの丸めの端数で動かす**（平行移動と作り直しで全画素一致。つまみの高さも整数）⑧**窓付きの組み立てで、断片を持つ箱は断片の範囲で窓の判定をする**（インラインの中の span の箱の x / y が (0, 0) で、親の無い項目が画面に出ていた）⑨挿入位置の探索の確保を除く（前段の②で `child` の確保が `+31%`。基準より `-44%` に） | `shortcut-matrix-known.txt` が空・53 構成すべて一致（Windows / Linux）・`-Pmatrix.selfcheck` が全文書で一致・`costGate` 緑（`child` の基準値を下げた）・時間は HEAD 比 ±5% 以内（`scroll`・`child`・`fontsize`・`hover`・`resizeexact`）。スクロールのゴールデン 12 フレームを取り直し（意図した変化） | `IncrementalVsFullTest`（11 件。追加: `layoutsDocumentWithoutCutoffMatchesFullRelayout`・`repaintedBoxWithoutMemoKeepsScrollContainerClip`・`scrollListPixelsMatchWithAndWithoutScrollContentNode`・`smoothScrollStaysOnWholePixels`。いずれも修正前は赤を確認）・`:core:shortcutMatrix`・`GoldenFrameTest` |
| A4 | 文書と実装のずれの機械検出: 追跡している `.md` のリンク・`core/README.md` のコード例のコンパイル・README の性能値の出所。リンク切れ 6 件・`ARCHITECTURE.md` のモジュール構成（5 つ）・本書冒頭の圧縮を修正。**性能値の出所は `BENCHMARK.md`**（本書は追跡対象外で、README からリンクできないため。当初の「§2 を出所にする」から変更） | 存在しない API（`api.ui.React`・`useState`）やファイルを例示・リンクするとテストが赤になる | `DocConsistencyTest`（5 件。検査器自身の検証 2 件を含む） |
| A12 | 実文書 5 本 × `hover` / `scroll` / `text` / `open` の計測（`FineProfileMain` と `trace.js` が同じ要素を触る） | 比較できる全行で Chrome より速いが、常時アニメーションの文書は差が小さく確保が大きい（→ B12）・`open` の予算超え 2 本（→ B13）。§2 の先頭行 | `run-matrix.sh … real chrome`（計測） |
| A16 | 要素の間の空白の節（A11 第 4 段で DOM に残した）のコストを戻す: ①直列化で空のキーを書かない ②文字・コメントの節は子と属性に共有の空の表を使い、属性は最初に書くときに作る（`attrsForWrite()`）。短い空白の文字列を共有し、まれにしか使わない欄を別のオブジェクトへ移す（節 `112 → 80 B`） ③文字の節の算出スタイルを親から写し（`ComputedStyle.derivedTextChild`）、アニメーションの確認を飛ばす | 空白の節を残したまま、初回パケット `6,183 → 4,451 B`（元の `4,500` 以下）・1 セッションのメモリ `+4.7% → +0.8%`・showcase を開く確保 `+10% → +2.5%`。WPT `160 / 260` を維持 | `PacketBandwidthTest`・`StateMemoryBenchmarkTest`・`TextNodeStyleEquivalenceTest`・`TextNodeFootprintTest`・`:core:costGate` |
| A8 | 巨大ファイルを責務で分ける（1,500 行を超えるファイルが 13 個。行数の上限は T1 の `FileSizeRatchetTest` が固定済み）。2026-11-18: `DisplayListBuilder` `4,358 → 2,496`（`ChildSlicePatch`・`BoxMemo`・`StackingSubtreePatch`）・`SoftwareRenderBackend` `2,961 → 1,973`（`TextRaster`・`DamagePlanner`）・`BlockLayoutEngine` `2,865 → 1,504`・`UiView` `2,669 → 2,272`（`LocalPatches`）・`LayoutBox` `4,048 → 3,885`。**2026-11-19**: ①入れ子の型を最上位へ: `HtmlParser` `1,594 → 1,256`（`HtmlTokenizer`・`CharacterReferences`）・`GridLayoutEngine` `1,532 → 1,346`（`TrackList`）・`BlockLayoutEngine` `→ 1,464`（`LineJustify`）・`PaintSceneDriver` `1,589 → 1,486`（`Painter`・`RangeBounds`・`IntArrayListLite`）・`PaintOrder` `1,712 → 1,467`（`RenderTask`・`Chunk`・`PaintLayers`）・`HtmlNode` `1,883 → 1,404`（内部クラスだった `AttributeMap`・`ChildList` を、持ち主の欄つきの最上位へ。暗黙の外側参照と同じ 1 欄なので実体の大きさは同じ）②責務で分ける: `RenderPipeline` `1,859 → 1,437`（箱の木の組み立て・疑似要素・`content-visibility` の具現化 → `RenderTreeBuilder`。公開の `buildLayoutTree` は残す）・`GpuRenderBackend` `1,870 → 1,391`（フィルタの効果の段・ぼかし・`backdrop-filter` → `GpuEffects`。効果だけが使う uniform の欄〔`fx*` 45 個〕も一緒に移し、本体の欄を広げない）・`SoftwareRenderBackend` `1,971 → 1,499`（項目の種類ごとの塗り〔背景・影・枠線・画像・3D・入力部品〕→ `ItemRaster`） | 1,500 行超 `13 → 4`（`LayoutBox` 3,884・`DisplayListBuilder` 2,496・`UiView` 2,272・`ComputedStyle` 2,121）。3,000 行超は `LayoutBox` の 1 つで、A7 の合否線（`LayoutBox.java` 2,000 行以下）が受け持つ。T1 の行数の上限を 9 ファイル分下げた（`file-size-ceilings.txt`）。クラスの形の表は `HtmlParser` の public な可変欄 6 が `HtmlTokenizer` へ移っただけ（合計は不変）・`GpuRenderBackend` のメソッド `139 → 121` | 挙動は変えない移動だけ。`:core` 全件・ルート全件・全モジュールのビルド・ラチェット全件（行数・クラスの形・static・システムプロパティ・層）・`costGate`（確保量と経路の回数）・`scripts/ci-linux.ps1` |
| B11 | 少ないコアでも全行の測り直しを 144Hz に収める: ①並列区間を始めた呼び出し側（描画スレッド）も仕事を先着で取る（`LayoutTaskPool.runShared`。以前は `ForkJoinPool.invoke` で区間の間ずっと待ち、ワーカー 4 本で main の標本の 7 割が待ち）。区間は加わる本数 × 4 に割る（`grainFor` / `chunksFor`）②書体の解決を算出スタイルに覚えさせる（`FontResolver.resolveGeneration`・`ComputedStyle.peekFace`。実書体で直列の 7.6%）③形の世代（float メモ）を display / position / float の変わり目だけで進める（以前は要素の再スタイルのたびに進み、2,000 行の親で子を毎フレーム走査し直していた）④`canVirtualize` は `content-visibility: auto` の子孫が無ければ子を走査しない・`isSpanner` は `column-span` の宣言が無ければ文字列を見ない⑤画面外の行を部分木ごと省く判定（`cullableSubtree`）と箱ごとの記録を、表示リストの組み立ての前に並列で済ませる（`SubtreePruner.prepare`。64 子以上） | varied-2000 `fontsize`（実書体・GL）: ワーカー 4 本 `8.85 → 6.71 ms`（合否線 `6.94` を達成）・既定 `8.20 → 5.72`・簡易書体 4 本 `8.03 → 5.91`。直列 1 本 `18.4 → 17.2`。表示リスト `0.93 → 0.58 ms`。他の KPI は ±15% 以内か改善（large-2000 `fontsize` `3.07 → 2.64`・`child` `1.38 → 1.17`）。`costGate` 22 シナリオ緑。⚠️ ワーカー 3 本は `7.32 ms`（§2 A3 行） | `LayoutTaskPoolCallerParticipationTest`（全番号 1 回・呼び出し側が加わる・入れ子は配らない・例外の伝播・区間の式）・`ParallelPrunePrepareEquivalenceTest`（900 行で `font-size`・スクロール・文字・ホバーを挟み、直列と表示リスト・省いた記録が一致）・`FontSizeRestyleMemoTest`（形の世代と float メモが `font-size` だけでは外れない・float / position / display で進む・書体の覚えの鍵）・既存の `ParallelLayoutEquivalenceTest`・`WindowPruneEquivalenceTest`・`verifyFloatMemo` |
| B12 | 常時アニメーションのある文書を差分の経路に乗せる（A12 の実測: voidwave の `hover`・`scroll`・`text` が 1 フレーム `0.7〜2.1 MB` を確保して `1.4〜1.9 ms`。surface_test・animation_test の `hover` も `640 KB` 前後で、毎フレーム全部を組み直していた）。2026-11-18: ①差分に乗らない理由の採取（3 文書の `hover` はホバーでスタッキングコンテキストが変わる `CANTPATCH boxes[6]`・voidwave の `scroll` は粘着の見出しのあるスクロールコンテナ。§6-90・92）②(a) 前後とも変わらないいちばん近いスタッキングコンテキストの部分木だけを組み直す（`DisplayListBuilder.patchViaStackingSubtrees`）(b) 描画側の索引の項目の状態を新しい位置へ写す（`patchIndexByMove`）(c) クリップを同じフレームの中で共有 (d) 上の inset だけの sticky を含むスクロールコンテナも節にまとめ、スクロールだけの更新では sticky の箱の節に「貼り付きのずれの増減」を足す（`StickyShift`）(e) 毎フレームの小さな確保を除く。**2026-11-19（残りの voidwave `scroll` の確保）**: (f) 自分の変形・不透明度が恒等の葉の項目は、親の姿勢（`Resolved`）の実体をそのまま使う（`build` の結果は親と同じ値になる。項目ごとの色などの補間値は `PaintSceneDriver.animAt`。切り分けは既存の `reuseIdlePoses` で、切ると項目ごとに毎フレーム作り直す）。中身の節が動くフレームで作る姿勢が 1 フレーム `1,997 → 642` | GL・実書体: voidwave `hover` `1.88 → 0.67 ms`・`2,144 → 72 KB`、`text` `1.37 → 0.60 ms`・`715 → 59 KB`、`scroll` `1.62 → 0.43 ms`・`1,758 → 86 KB`、surface `hover` `0.94 → 0.66 ms`・`642 → 97 KB`、animation `hover` `1.11 → 0.77 ms`・`643 → 57 KB`（合否線: 実文書の `hover` / `scroll` / `text` の確保が `100 KB` 以下・時間が半分以下） | `StackingSubtreePatchTest`・`StickyScrollNodeTest`・`FrameClipShareTest`・`UnreadableImageSourceCacheTest`・`ScrollTranslateTest`・`ParentPoseShareTest`（共有あり／なしで、スクロールと色・不透明度・変形・回転のアニメーションの毎フレームの画素が一致。変形・不透明度の動く節は共有しない。voidwave のスクロールで作る姿勢が毎回作るときの 4 割未満〔確保量の代わりに数で見る。共有を外すと赤〕）・`GlDamageParityTest`・`RealDocumentFramesTest`（GPU と SOFTWARE の画素一致・Vulkan の検証エラー 0） |
| T2 | 達成した KPI と実文書の絵を回帰ゲートに載せる: ①`costGate` に同梱の実文書 8 本（voidwave・surface_test・animation_test・media_test・hud_test・item_block_test・machine_ui・guidebook）× `hover` / `scroll` / `text`（変化の起きない組み合わせは除く）を描画なしと `cpu` で、`open` を描画なしで足した（`22 → 56` シナリオ。手元 `203 s`）。`COST` 行に `stackSubtrees`・`indexMoves` を足し、`-Dprofile.disable=Class.FIELD` でテストだけの static な近道の印を子 JVM で切れるようにした（エンジンに起動時のスイッチは足さない）。Windows と Linux（JDK 21 の版の違いの JIT）で確保量だけが揺れる行 7 つ（GitHub の ubuntu-latest で見つけた 2 つを含む）には、基準値ファイルに行ごとの許容 `allocTol`（`7〜25%`。更新しても保つ）を付けた（経路の回数は完全一致のまま。media_test `text` は Linux で `-18%`、guidebook `text` は Linux の実行ごとに `6.4〜6.8 KB`）②`RealDocumentFramesTest`: 実文書 9 本（showcase を含む）× 操作の手順（開く → `button`・`a`・`.btn` の 3 か所へホバー〔遷移の途中・確定〕→ 外す → 文書と最初のスクロールコンテナのスクロール → 文字の変更 3 回 → 幅 700）の毎フレームの SOFTWARE の画素（OS ごと）・表示リストの要約・ヒットテスト（8 × 6 の格子）をゴールデンに（`golden/realdocs*.txt`。146 フレーム）③同じ手順で、差分の経路と「近道を切って毎フレーム全体を作り直す」（スクロールの節あり／なしの 2 通り）の画素・見えている位置の表示リストの値・ヒットテストが毎フレーム一致 ④GL / Vulkan と SOFTWARE の毎フレームの画素の比較（既知の差は上限つきで T2b へ）。**比較で見つけて直した不具合 3 件**: (a) ヒットテストがアニメーションを壁時計で評価していた（描画はフレームの時刻。`HitTestIndex.animationClock`。§6-95）(b) 動いた外側のスクロールの中の入れ子のクリップを、作ったときの位置の合成矩形で比べていた（部品ごとに持ち主の今の姿勢で当てる。§6-96）(c) 凡例つきの fieldset の枠線のクリップが連鎖を持たず、スクロールの節が動くと上の枠線が消えた（§6-97） | 実文書の確保量・経路の回数・絵・ヒットテストが push の時点で守られる。近道を 1 つ切ると実文書の行が赤（`STACKING_PATCH_ENABLED`・`STICKY_IN_SCROLL_NODE` で確認。代表文書の 22 行では赤にならなかった。§6-98） | `RealDocumentFramesTest`（5 件）・`HitTestFrameClockTest`・`ScrollNodeClipRegressionTest`（2 件。どれも修正を戻すと赤になることを確認）・`:core:costGate` 56 シナリオ（Windows `203 s`・Linux `189 s` で緑）。Windows のルート＋`:core` 全 3,119 件・`scripts/ci-linux.ps1`（WSL）の `build test` 3,119 件が緑 |
| T2b | 実文書の GPU と SOFTWARE の画素差を無くす: T2 の比較で許容（1 チャンネル `8`）を超えていた 6 組（GL / Vulkan × voidwave・surface_test、Vulkan × showcase・animation_test）の原因 4 つを直した。①fx（ぼかし・backdrop-filter・影・マスク）と tex のシェーダーのサンプラーを、GLSL と同じ binding 1 始まりにする（Java 側だけ 0 始まりで、Vulkan の記述子セットのレイアウトで定数ブロックと 1 番目のテクスチャが同じ binding 0 になり、記述子の更新が不正だった。検証レイヤーと WSL の lavapipe はここで落ち、実機の Vulkan は voidwave の backdrop-filter・animation_test を違う絵で描いていた）②保持したスクロールの層の合成を、デバイスの行の向き（`bottomUpFramebuffer`）で分ける（Vulkan では内側のスクロールの中身が上下反転した）③CPU のボックスぼかしの近似（σ ≥ 12）も、描画先の外を端の画素として読む（ガウスの経路・GPU の clamp-to-edge と同じ。画面の端に接する `backdrop-filter: blur(16px)` にかすみが出ていた）④動く節（`animation: rotate` など）の下の部品も「動いている項目」として GPU の差分パッチに含める（自分は止まっているので漏れ、表示リストの変わらないフレームで GPU が前の姿勢の図形を使い続けた） | GL / Vulkan とも実文書 9 本の全フレームで SOFTWARE との最大差 `8` 以下（最大だった `250` から）。`realdocs-gpu-known.txt` は空。検証レイヤーつきの Vulkan で実文書 9 本の検証エラー 0 | `ShaderBindingConsistencyTest`（全シェーダーの GLSL の binding と Java 側の宣言の一致・重なり無し）・`BoxBlurEdgeTest`（修正を戻すと赤）・`RealDocumentFramesTest.gpuMatchesSoftwareEveryFrame` / `vulkanValidationIsCleanOnRealDocuments`（GPU のある機械）・実文書の画素ゴールデンを取り直し（voidwave の 17 フレーム。意図した変化） |
| T1 | 構造のラチェット: ①ファイルの行数（800 行超を表で固定・表に無いファイルは 1,500 行まで）②クラスの形（リフレクションで、public な可変欄・メソッド数〔100 超〕・`public static void set*`・public static の可変欄）③システムプロパティ・環境変数の読み口（全キーを種類つきで表に。種類 `SHORTCUT` は `shortcutMatrix` のスイッチと一致・キーを文字列リテラルで書かない読みは不可・`enderframe.*` を読むファイル数の上限）④本体が空の広い `catch`。表は `core/src/test/resources` の `file-size-ceilings.txt`・`class-shape-ceilings.txt`・`system-properties.txt`・`swallowed-exceptions.txt`。増えると赤、減ったら表を下げるまで赤（書き直しは `-Dratchet.update=true`） | 固定した値（2026-11-18）: 800 行超 37 ファイル（1,500 行超 13・3,000 行超 2）・public な可変欄 358（`LayoutBox` 106・`CssAnimator` 44・`Selector` 32・`InteractionState` 31・`StyleFacts` 30）・メソッド 100 超 9 クラス（`LayoutBox` 201・`UiView` 174・`GpuRenderBackend` 139・`UI` 132・`CanvasRenderingContext2D` 128・`ComputedStyle` 127・`SoftwareRenderBackend` 119・`HtmlNode` 115・`DisplayListBuilder` 115）・static な setter 16・public static の可変欄 66・キー 63（`enderframe.*` 58。読むファイル 22）・本体が空の広い `catch` 18（`Bindings` 8）。4 クラスで 1 秒未満 | `FileSizeRatchetTest`・`ClassShapeRatchetTest`・`SystemPropertyRatchetTest`・`SwallowedExceptionRatchetTest`（それぞれ合成のソースで検査器自身を検証。本物のソースへ違反を 1 つ入れて赤を確認） |
| T4 | 公開 API の署名を固定する: `api.*` の公開・protected の型（入れ子を含む）と、そのコンストラクタ・メソッド・欄の署名（総称・throws・修飾子）を `api-signatures.txt`（971 行。名前の `com.nishiyu.enderframelib.` は省く）に書き出し、違えば `-`/`+` の行で赤（更新は `-Papi.update`。差分を目で見てからコミット）。`api.event`・`api.dom` の契約のテスト（`EventContractTest` 9 件・`DomValueContractTest` 4 件） | D1 の前後で公開 API の変化が全部「表の差分」としてレビューに出る。`EnderEngine.workerThreads(int)` → `long` で赤になることを確認。内部の型を参照する行は 0 | `ApiSignatureTest`（検査そのものの検査: 引数の型を 1 つ変えた fixture で差分が 2 行）・`EventContractTest`・`DomValueContractTest` |
| S1 | 設定と切り分けスイッチを 1 か所にする: ①`enderframe.*` のシステムプロパティは `core/kernel/EngineFlags`（L1・58 個・起動時に 1 回）だけが読む ②`EngineContext` は不変の `HostServices`（ホストの差し込み口 8 個）と `ContextFlags`（文脈の切り替え 6 個）だけを持ち、`updateHost` / `updateFlags` が丸ごと差し替える（呼び出し 145 か所。Minecraft 側を含む）③各クラスの `static volatile boolean` 51 個を `core/kernel/Shortcut`（enum・状態は 1 つの不変の配列・`set` / 範囲つきの `with`）へ。GL / Vulkan のファサードの写しの欄 8 個とテストのリフレクションの書き換えも無くなった ④`Shortcut` を包むだけの static な setter 2 個を外した。**面ごとの切り替え（③の後段）と、ホストの差し込み口の static な setter 13 個（④の残り）は A13 で行う**（2026-11-19 に利用者が決定。ワーカーへ文脈を渡すのが A13 の仕事） | システムプロパティを読むファイル `22 → 1`・`EngineContext` の public 可変欄 `14 → 0`・テスト用の static な印 51 → 1 か所。設定の一覧が `EngineFlags` と `Shortcut` の 2 ファイルで読める。時間は前後で `-4.1〜+8.3%`（`FineProfileMain` の中央値、text / hover cpu / resizeexact / scroll large-list-2000 / child varied-list-500）| `SystemPropertyRatchetTest`（`MAX_ENGINE_READER_FILES = 1`）・`EngineContextTest`（public 可変欄 0・差し替えが失われない）・`ShortcutTest`・`ClassShapeRatchetTest`・`:core:shortcutMatrix`（53 構成）・`:core:costGate`（56 シナリオ。`-Denderframe.paint.nodiffpatch` で赤） |
| M1〜M5 | オフヒープ DOM・状態オートマトン・増分レイアウト・GPU Uber-Shader・タイル並列 CPU ラスタ | `:hover` `0.0069 ms`、`scroll` `0.002 ms / 0 B` | `DocumentArenaTest`・`PropertyIdMigrationGuardTest`・`LayoutKeyEquivalenceTest`・`Milestone4GateTest`・`Milestone4KernelTest`・`StyleBuildAllocationTest`・`FlowChildrenAllocationGateTest` |
| Wave 0/1 | 公開 API・脱 `mc-*` SPI・脱 `static`（`EngineContext`）・`LoadedFace` 並列化・GL 差分走査・測定半減 | `fullLayoutWarm` `0.97 ms` | `ApiBoundaryTest`・`HostElementProviderTest` |
| 構造ラチェット | 7 層単方向 DAG（逆方向参照 0）・プラットフォーム依存の隔離・ファイル数 | 機能追加による構造崩壊の防止 | `LayerDependencyRatchetTest`・`PortabilityRatchetTest` |
| W8 | 文書サイズ上限の黙った切り捨ての解消 | 正しさ | `DocumentSizeNoTruncationTest` |
| D0 | 単体配布の前提整備（旧 `DISTRIBUTION_ROADMAP.md` の Phase 1〜6）: ①IME 変換（`TextEditing` の未確定文字列とカーソル・`compositionstart/update/end` の発火・`StandaloneView` の未確定下線とキャレット追従・`imeCursorRect`・`WebViewSurface` / `EngineSurface` の公開 API）②動的リサイズ（`LwjglGlRenderBackend.resize` と `StandaloneViewer` の GLFW コールバック、リサイズ後の座標 1:1）③Maven 配布設定（POM メタデータ・`javadocJar`・OSSRH / GitHub Packages の環境変数設定・LWJGL natives を利用側が選ぶ方針）④`core/README.md`（依存の書き方・最小クイックスタート・対応機能と制限）⑤宣言的 UI DSL（`api.ui` の `VNode`/`VElement`/`VText`/`VFragment`/`Prop`/`Ref`/`State`/`UI`/`React`・`useState`/`useRef`/`useEffect`・`classList`/`$`/`byId`・`CatalogApp` を命令的 DOM 操作から刷新）⑥性能の P3（表示項目の相対座標化・平行移動再利用）・P4（スタイル等価判定の短絡）は R 系・B3 に吸収 | 日本語・多言語入力と窓のリサイズが使え、単体利用者向けの導入手順が揃う（配布レベル Alpha） | `ImeCompositionTest`・`ReactUiTest`・`ApiBoundaryTest`・`LayerDependencyRatchetTest`・`PortabilityRatchetTest` |
| R0 | 計測基盤（段別・パッチ命中・不成立理由カウンタ） | 以降の判定の土台 | `FineProfileMain` |
| R1 | スタイル変更（`hover` / `style`）のスライスパッチ化 | 2,000 行 `hover` display `2.45 → 0.018 ms` | `DisplayListSlicePatchTest` ほか |
| R2（部分） | 子の挿入・削除のパッチ命中 100%・窓付き表示リスト | large 2,000 `child` `1.44 ms`（Chrome 以下） | `PatchHitRateRatchetTest` |
| P1 | パッチ判定の一元化・`hasMovingProps`・親走査キャッシュ | varied `inherited` `10.2 → 7.9 ms` | `DisplayListSlicePatchOptimizationTest` |
| P2 | `InlineFragment` 再利用・`isNotBlankFast`・`updateScrollExtent` 高速化・局所 float 無効化 | child 確保 `-27%` | `BlockLayoutOptimizationTest` |
| P3 | 表示項目の平行移動再利用（整数ぶん） | `MATCHFAIL 4` 半減・確保 `-18%` | `DisplayListSlicePatchTest` |
| P4 | スタイル等価判定の短絡（宣言ハッシュ） | varied `inherited` `-14%` | スタイル一致テスト |
| Q1（第 1 段）・Q1a | 縦寸法の 1/64 px 格子化（`LayoutUnit`）と小数ぶんの部分木平行移動、置き直し時の断片オブジェクト再利用、配置されない `br`・空 `span` を動かさない修正 | varied `child` `4.60 → 2.02 ms`、確保 `4,969 → 271 KB` | `FractionalShiftEquivalenceTest`・`BlockLayoutOptimizationTest` |
| Q2（差分メモ） | `ComputedStyle.diff` を (旧, 新) の組でメモ化 | large-500 `inherited` `0.77 → 0.66 ms`、varied `-6.5%` | `ComputedStyleDiffMemoTest` |
| Q6（一部） | ビューポート幅だけの違いで測定を捨てない（外の寸法を読まない部分木）、高さの % 基準を fit / 幅だけの再利用で無視 | `resizeexact` measure `-10%`、測り直し `-11%` | `ViewportWidthFreeReuseTest`・`WidthOnlyReuseTest` |
| Q7 | 幅変更でルート以外の再スタイルと箱の木の組み直しを飛ばす（メディア結果不変・コンテナなし・ビューポート依存の値なし・汚れなし・アニメーションなし・表なし）。ルートだけ再スタイルして測り直す。切り分け `-Denderframe.layout.noresizeskip=true` | showcase `resizeexact` restyle＋box-tree `0.12 → 0.005 ms`、フレーム全体 `0.80 → 0.64 ms` | `ResizeSkipRebuildTest`（一から組んだ結果と一致・依存する文書では飛ばさない） |
| Q6b | `Tentative` に確定記録の種別・`outsideFree`/`widthFree` を追加（仮配列を 4 枠＋確定葉 2 枠へ拡張）し、`FlexLayoutEngine` の `settleTentative` を `stretchItems` 後に移動（baseline 項目のみ先行） | showcase `resizeexact` measure `0.287 → 0.120 ms`、フレーム全体 `0.68 → 0.48 ms`、確保 `983 → 714 KB`、`FITREUSE` `203 → 43` | `TentativeMeasureReuseTest`・`FlexSameWidthRemeasureTest`・`WidthOnlyReuseTest`・`LayoutKeyEquivalenceTest` |
| Q6c | fill 幅ブロック（block / 非 wrap 行 flex）・段落の `fitNeed` 計算と `fillFitReuse` / `Tentative.fillFit` 実装（`FlowBuilder` 実送り幅・折り返し検知つき） | `FITREUSE` 443 → 3 回（fill 幅 block / 段落の再測定省略）、showcase `resizeexact` 同等維持（measure `0.123 ms`） | `FlexSameWidthRemeasureTest`・`WidthOnlyReuseTest`・`LayoutKeyEquivalenceTest`・`TentativeMeasureReuseTest` |
| Q1b | `DisplayItem` の小数移動＋`parentX` 横移動、`BoxMeasurer` で `em`/`rem` 辺縁を `LayoutUnit.snap` 化、`isShiftableSubtree` の `FLEX`/`GRID` 緩和、flex auto-margin 記録・`stampClean` の `attrVersion` 照合・非インライン配下未配置抑止 | `MATCHFAIL 4` `54 → 8`、`BOXMEMO translated` `57.5`、showcase `resizeexact` 確保 `715 → 605 KB`（`int` 化・親相対化は残り `MATCHFAIL 6 = 38` が寸法変化のため見送り） | `DisplayListSlicePatchTest`・`FractionalShiftEquivalenceTest`・`WidthOnlyReuseTest`（全テスト `--rerun` 緑） |
| Q3 | `RenderPipeline`（build/restyle）から `ContainerBox` と `ancestorMoving`/`selfOrAncestorMoving` を再帰引数で伝播、`PaintOrder`・`DisplayListBuilder` の枝刈り／スライスパッチで参照（`movingStamped` O(1) 短絡＋`"none"` 高速判定） | varied `inherited`（GL）`7.36 → 6.23 ms`（`-15%`、`display-list` `0.738 → 0.312 ms`、確保 `3.1 → 2.7 MB`）、JFR の `nearestSizeContainer` / `hasMovingAncestor` ともに `0 / 447`（`0%`） | `ModernCascadeTest`・`DisplayListSlicePatchOptimizationTest`（全テスト `--rerun` 緑） |
| Q4 | `RenderPipeline.isGeometryOnlyChange` を構造変更プロパティ除外方式（`display`/`position`/`float`/`white-space`/`content`/`content-visibility`/`list-style-*`/`counter-*`、および `touchedByTableEngine()` の表のみ構造扱い）へ変更し、`restylePseudoElement`・`markerStyle`・`invalidateMinContentCache` を揃えた | varied `fontsize`（GL）で `structural=0` / `relayoutInplace=1000`・`box-tree` 完全消滅（`24.0 ms`、確保 `21.3 MB`） | `LocalRelayoutTest`（`fontSizeChangeUsesInPlaceRelayoutAndMatchesFreshRebuild`）・`TableIncrementalLayoutTest`（全テスト `--rerun` 緑） |
| Q5 | `LayoutBox.updateStyle` で原文・`white-space`・`text-transform` 不変時に正規化省略、`InteractionState.containsInheritIgnoreCase`＋`inheritsAttrVersion` による `style` 版キャッシュ、`StyleTable`/`ComputedStyle` の `valuesViewport`・`extrasViewport`・`allExtrasCustom` 増分維持 | JFR 上 `isAlreadyNormal`+`toLowerCase`+`dependsOnViewport` が `30/447 (6.7%) → 0/320 (0%)`（JFR 計測 `4.97 ms`、クリーン `6.37 ms`） | `StyleTableHotPathTest`・`RestyleTextAndInheritCacheTest`（全テスト `--rerun` 緑） |
| Q2b | `CssPropertyId` 4 バンド整列＋`StyleSlice` 遅延プール共有（`ComputedStyle`）、非確保差分問い合わせ（`hasLayoutAffectingDiffFrom`・`classifyPropagationFrom`・`hasStructureAffectingDiff`・`classifyPositionOnlyDiff`）、`derivedAnonymous` / `derivedMarker` メモ化、`restyleBoxRecursive` の `ThreadLocal.get` 排除 | varied `inherited`（GL）`6.37 → 3.80 ms`（`p50 3.71`・Chrome 比 `1.07x`、`restyle-local` `5.47 → 2.90 ms`、確保 `2.8 → 1.1 MB`）、JFR `diff` `0/256 (0%)` | `ComputedStyleDiffMemoTest`・`ComputedStyleFootprintTest`・`Milestone2GateTest`（全テスト `--rerun` 緑） |
| P5 | `BitmapFontFace.cachedNaturalAdvance`＋`NaturalSegments`（ビット一致 `scale`）・2-way L1 キャッシュ（`SHAPE_L1`/`SEG_L1`/`WRAP_L1`）＋`ShapedInterval`/`WrappedParagraph` 幅区間キャッシュ＋`InlineLine` スライス高速配置＋`LayoutBox.TextCache` 2 枠＋`ComputedStyle` `TextStyle` メモ＋`SPARE_FRAGMENT_LIMIT=128` | varied `fontsize`（GL）`22.04 → 18.08 ms`（`measure 9.09 → 5.08 ms` `-44%`、確保 `19.35 → 8.92 MB/frame` `-54%`）、showcase `resizeexact` `0.488 ms`（`measure 0.122 ms`・確保 `595 KB`） | `TextShaperIntervalAndFontCacheTest`・`WidthIndependentShapingTest`・`ParagraphSegmentsTest`・`BlockLayoutOptimizationTest`（全テスト `--rerun` 緑） |
| P6 | `ReusableTable`（`nodeId` 索引配列＋同ノード照合）と `tryChildPatch` 増分登録、`linkParents` 全走査廃止、`resetPlacementsFast`＋遅延 `discardFragments`、`markRootRestyleChanges`（`relayoutRootOnly` 引継）＋`clearAllSubtreeMarks` 枝刈り、`subtreeHasGenerated` 伝播 | varied `relayout` `0.68 → 0.28 ms`（未計上 `60.9% → 7.0%`）・`resize` `9.99 → 8.37 ms`・`inherited` `3.80 → 3.64 ms`、JFR `collectReusableBoxes`/`linkParents`/`clearAllSubtreeMarks` `0%`（`relayout`・`child showcase`） | `P6IncrementalTraversalTest`・`TableIncrementalLayoutTest`・`LocalRelayoutTest`（全テスト `--rerun` 緑） |
| P8 | `ConstraintSpace.reuseOrCreate`（`LayoutBox.contentSpaceCache`/`childSpaceCache`）で測定ごとの制約オブジェクトを値が同じ間は再利用、折り返し行 `InlineLine` のスライス情報 7 フィールドを段落単位の `SliceStyle` に共有（行あたりの確保を縮小）。確保元を JFR `ObjectAllocationSample` で特定した結果、`InlineLine` `18.8%`・`LineItem` `14.0%`・`ConstraintSpace` `12.9%` が上位で、当初案の `CssValue[]` コピー・インライン style 解析は上位に出ず対象外 | varied `fontsize`（GL）確保 `3.87 → 3.54 MB/frame`（目標 `<5 MB` 達成）、時間 `15.4 ms` | `P8AllocationReductionTest`（制約再利用の値判定・折り返し段落が一から組んだ結果と一致・再利用の同一性・1 行あたり確保の上限）（全テスト `--rerun` 緑） |
| P7 | CPU バックエンドの全面描画理由 1（前フレームが動作中）・6（今のフレームが動作中）を項目単位へ。`PaintSceneDriver.fillActivity`/`activityAt`＋`CssAnimator.AnimState.motionKindAt`（開始値と目標値が実際に違うチャンネルだけで 塗り=1・変形=2・不透明度=3 に分類）で動いている項目と前フレームで動いていた項目（終端の最終色確定）を dirty にし、変形は動いた節の子孫の塗り直しに乗せる。不透明度（層が必要）と項目数が変わる動きだけ全面に残す | showcase CPU `hover` `1.59 → 1.17 ms`（`DAMAGE-WHY` 理由 1・6 が各 `1050 → 0`、`partial 0 → 2100`） | `PartialRepaintPixelTest`（色・変形の遷移が差分描画かつ全画素一致・不透明度は全面のまま一致・遷移終端で最終色・キーフレーム一致）・`VolatileItemPartialRepaintTest`（全テスト `--rerun` 緑） |
| P8b | 保持ヒープ削減。①`TextShaper` の `flush` が 1 行に収まる段落では行の文字列に段落の `String` を使い回す（以前は `StringBuilder.toString()` で行ごとに同じ中身の `String`＋`byte[]` が残り、`AdvanceEntry` の鍵にもなっていた）②`HtmlParser` のタグ名・属性名を `intern`（`li`/`id` が要素数ぶん別実体だった） | large-2000 `8.0 → 7.3 MB`（Chrome 比 `1.46x`）、varied `33.5 → 33.1 MB` | `P8bRetentionTest`（同一実体・行末空白・折り返し不変・名前共有・保持量の粗い天井）、`:core` 2,007 件・ルート 501 件緑 |
| R6 | `common.EnginePrewarm.start()`（デーモンスレッドで代表文書を数フレーム描く。冪等・失敗は握りつぶす）と `runNow()`。`ColdStartMain` に `-Dcold.prewarm=join/bg` と `-Dcold.sample=1`（スタック採取）を追加 | コールド `open` showcase `446 → 137 ms`、large-2000 `504 → 187 ms`（温め完了後） | `EnginePrewarmTest`（冪等・結果不変・並走で同一表示リスト）、`:core` 2,015 件・ルート 501 件緑 |
| P9 | GL 送出の書き込み削減。①`GlRectStream.open` がクリップ欄を `NONE` → 個数と二度書き、図形ごと・毎フレーム値が往復して全図形が「変更」扱い（showcase hover で GPU レコードの変更 `2,607 件/フレーム`）だったのを、最初から個数で書くよう修正（`143 件`）②`decompose` に回転・傾き無しの近道（`hypot`/`atan2`/`cos`/`sin` を図形ごとに 2 回走らせていた）③同じクリップ配列を共有する文字・図形の slot 引き直しを省略 | showcase GL `hover` `0.487 → 0.43 ms`（同一条件の再計測。表の旧値 `0.565` から `-24%`）、`text` `0.597 → 0.51`、`style` `0.536 → 0.47` | `GlStaticFrameWritesTest`（同一フレームの描き直しで GPU レコードの変更 0。修正を外すと赤になることを確認）、`ChangedWritesTest`、`:core` 2,021 件・ルート 501 件緑 |
| P9b | `SoftwareRenderBackend.drawText` の「項目だけで決まる」下ごしらえ（書体・スタイル解釈・BiDi・送り位置・基線・影・下線・縁取り）を `TextSetup` として項目の実体で保持（書体が変われば作り直し。色・姿勢・クリップ・不透明度は毎回渡す）。切り分け `-Denderframe.paint.notextsetup=true` | showcase GL `hover` 確保 `125 → 96 KB`（`-23%`）、時間 `0.456 → 0.43 ms`（`-5%`、ばらつき内に近い） | `TextSetupReuseTest`（同じ画面の反復・色と文字の変更後が、保持なしと全画素一致）、`:core` 2,025 件・ルート 501 件緑 |
| X2 | 開発体験: `@scope` ＋ `<template component>` / `<slot>`、`State.combine/lens` ＋ 型安全 `RecordState` バインディング、`F12` 組み込み要素インスペクタ（箱モデルの色分けと算出スタイルのパネル） | 開発生産性（制約: 影の木なし・近さによるカスケード順なし・インスペクタはクリック固定/木表示/編集なし） | `ScopeRuleTest`・`ComponentSlotTest`・`RecordStateTest`・`ElementInspectorTest` |
| R5 | **項目単位の保持 GPU 記録（再生）**。`GlRectStream` に項目ごとの範囲記録（開始 slot・描画順・クリップ個数・参照クリップ slot の内容・atlas 世代・字形数）を持たせ、`SoftwareRenderBackend.drawItem` が止まっている項目（`PaintSceneDriver.idleAt`）の入力（項目・`Resolved`・`Clip` の同一性＋`flat`）が前フレームと同じなら `GpuSink.spanReplay` で範囲ごと前進。前提が崩れれば計算に落ちる（直前フレームに同じ項目が出ていない／開始位置・クリップ表の状態が違う／参照クリップの中身が違う／atlas が作り直された／記録中に CPU 描画・画像・容量超過）。CPU が描いた層が残っているときは再生の前に順序づけ（`flushOverlay`）。切り分け `-Denderframe.paint.noreplay=true`（描画側）/`-Denderframe.gl.noreplay=true`（GL 側）。`AtlasPacker.resets()`、`PaintSceneDriver.idleAt` を追加 | showcase GL `hover` `0.416 → 0.31 ms`（`-26%`）、`text` `0.52 → 0.36`、`style` `0.465 → 0.32`、確保 `96 → 42 KB`、large-2000 `hover` `0.20 → 0.15 ms`（再生 `455 項目 / 1,182 図形 / フレーム`、GPU レコードの変更 `143 → 61`）。large-2000 `child` は再生が効かず（行が全部ずれる）同等 | `ItemReplayPixelTest`（ホバー移動・遷移・スクロール・せん断の CPU 描画・クリップ・グラデーション・不透明度・入力欄を含む操作列で、再生あり/なしの毎フレームの画素が完全一致。順序づけの検査を外すと赤になることを確認）、`:core` 2,028 件・ルート 501 件緑 |
| RTI | **見送り（着手条件未達）**。条件は「上位施策でも Chrome 比 2x 超の KPI が残る場合」。§2 の時間 KPI は large-2000 `child` `0.49x`・`hover`・`scroll`・`fontsize` が Chrome 比 `0.5x` 前後、showcase 幅変更が最大 `1.9x`（2x 以内）で、該当なし | — | 再判定の条件: 2x 超の KPI が出たとき |
| R7（今回分） | `PatchHitRateRatchetTest` に `LayoutBox.layoutsShifted/Skipped` のラチェット 3 件を追加: 末尾追加は既存行が省略（`skipped 500/op`）、先頭挿入は平行移動（`shifted 500/op`）で済む、hover 往復は配置のやり直し 0。以降の各項目は完了時にカウンタを足す運用を継続 | 構造の退行（全行の置き直し）を時間ではなくカウンタで検出 | `:core` 2,018 件・ルート 501 件緑 |
| Y1（検証済・見送り） | 区間まとめ再生（showcase `hover`/`text`/`style` を Chrome 比で勝たせる案） | 検証: showcase GL `hover` は 1 フレーム約 `474` 項目のうち `455` が再生済み（再生に乗らないのは入力欄・チェックボックス・ラジオ・セレクト・レンジ約 `11` と遷移中約 `2`）。JFR 上位は `resolveAt` 約 20%・`spanCanReplay`（クリップ鍵比較）約 14%。**Chrome 比は既に `0.28x`（Y2）**。区間まとめの見込みは `0.31 → 0.2 ms` 前後で絶対目標 `0.15 ms` には届かない公算。再判定条件: 絶対目標を満たす必要が出たとき（着手するなら先にクリップ表の版番号の O(1) 判定を試作） | `FineProfileMain hover showcase 4000 gl`・JFR |
| Y2 | Chrome 比の計測欠落の解消 | 既存の `trace.js` で showcase `hover`/`text`/`style`/`scroll` の Chrome 全 CPU 時間（main＋raster＋draw）を計測し §2 に記載: `1.10 / 0.95 / 0.94 / 0.18 ms`（`idle 0.17`）。`:core` GL は Chrome の `0.28x / 0.38x / 0.34x / 0.05x` | `node trace.js showcase <scen> gpu` |
| Y3（検証済・実質達成） | 幅変更 `1.9x` の構造短縮案 | 検証: Chrome 値は「200 回平均 15 組の中央値」、`FineProfileMain` は単純平均で条件が違った。`FineProfileMain` に `CHROMESTAT`（同統計）を追加。`resizeexact` は段別 p50 合計 約 `0.10 ms` ＝ **Chrome 比 約 `1.2x`**（±10–15% のばらつき域）。確保の `layout` 系は約 `10%`（約 `55 KB`/フレーム）で揺れの主因ではなく、結果キャッシュ・親相対化・スタイルのグループ化は費用対効果なしで見送り。再判定条件: p50 比が `1.3x` 超 | `FineProfileMain resizeexact showcase 3000 null`（`CHROMESTAT` 行） |
| Y4（検証済・実質達成） | 保持ヒープ削減案 | 検証（`-Dmem.histo=1`）: large-2000 の `byte[]` `3.0 MB` のうち約 `1.8 MB` は JAR 中央ディレクトリでエンジンの保持ではない。それを除く実保持は約 `6 MB` ＝ Chrome 比 約 `1.2x`。`LayoutBox`（`4,116` 個 × 約 `544 B` ＝ `2.2 MB`）の欄位整理は削減が large で約 `0.4 MB`（5%）・varied で `0.7 MB`（2%）止まり。`InlineFragment`（varied `85,548` 個 × `56 B`）は `descent` を削れば `48 B` だがスクロール範囲に必要。見送り。再判定条件: 保持量の天井テストの悪化 | `MemFootprintMain varied-list-2000 large-list-2000 showcase`（`mem.histo`） |
| Y5（検証済・見送り） | 温め無しコールド `open` の短縮案 | 再計測（新 JVM 3 回）: showcase `open` `448 / 410 / 437 ms`（前回 `446`）。§6-23 のとおり支配は一度きりの処理のインタープリタ実行で、CDS でも縮まない（`-Xint 695` / C1 `406` / 既定 `478` / AppCDS `450–470`）。UA シート・静的表のビルド時直列化は解析 26%＋スタイルシート構築 16% の一部にしか効かず、見込みは `-60 ms` 前後で目標 `< 300 ms` に届かない。温め後は `137 ms`（R6）で解決済みの使い方。再判定条件: 温めを使えない起動経路が要件になったとき | `ColdStartMain showcase`（新 JVM 5〜7 回） |
| Y6（一部実施） | 表示リスト段の確保削減 | 実施: ①`PaintOrder.PaintLayers` の 7 本の一覧を最初の追加まで作らない ②`Gradients.resolveStops` の結果を (停止点・寸法) で再利用 ③`ColorParser.parseHex` を `substring` なしに ④近道に乗らない色の解析結果を小さな表に記憶。効果: showcase `resizeexact`（null）確保 `556 → 482 KB/フレーム`（`-13%`）、時間は変化なし（ばらつき内）。残りは幅変更で寸法が変わる箱の作り直し（`miss 49`・`MATCHFAIL 6 = 38`）と `appendTranslated` の項目作り直し（`DisplayItem` 不変・整数座標）で、親相対座標への設計変更が要る。見送り（目標 `< 300 KB` は未達）。再判定条件: 幅変更フレームの確保天井を課すとき | `:core` 全件緑（`--rerun`）、`FineProfileMain resizeexact showcase 3000 null` |
| P10（KPI 改善バッチ 1〜7） | `GlRectRenderer`/`GlRectStream`（フレーム単位プリ乗算・uniform 分割・`spRefVers` O(1) クリップ版・一括再生・回転矩形）＋`ImmutableItemList`/`OffsetItemList`/`StandaloneView` スクロール基底短絡＋`PaintSceneDriver` チャンクスキップ/`Int2ObjectOpenHashMap`/平行移動短絡＋`SoftwareRenderBackend`（`dprFrame` 再利用・`retainOwner`/`Clip` 配列化・`drawRange`/`drawProgress`/`drawToggle` 非確保）＋`StyleSheet`（`declaresContainers`/`usesContainerQueries` 版数キャッシュ）＋`InvalidationSets`/`InteractionState`/`ScrollState`/`HitTestIndex`/`EventDispatcher`/`Corners`/`LengthResolver`/`ItemProps`/`DisplayListBuilder`/`FlexLayoutEngine` の非確保高速経路 | showcase GL `hover` `0.31 → 0.203 ms` (`p50 0.148 ms`)・確保 `42 → 9.9 KB`（`-76%`）、`text` `0.36 → 0.215 ms` (`16.9 KB`)、`style` `0.32 → 0.207 ms` (`16.6 KB`)、large-2000 `scroll` `0.122 → 0.064 ms` (`p50 0.0456 ms`・確保 `2.7 KB`)・`hover` `0.26 → 0.067 ms`・`text` `0.30 → 0.103 ms`、CPU `hover` `1.22 → 0.917 ms` | 全モジュール `--rerun` 緑（`:core` 2,032 件〔スキップ 3〕・ルート 501 件） |
| X1 | Top Layer（`<dialog>`・`popover`・`::backdrop`・`inert`）・Anchor Positioning（`@position-try`・`anchor()`/`position-area`）・`content-visibility: auto`＋`FenwickVirtualList` 仮想化・`subgrid`・SVG CSS カスケード・Canvas 拡充（輪郭/メトリクス/絵文字）・GPU 行列直送・カラー絵文字（COLR v0/v1・`0xFFFF` 前景色・`CBDT`/`CBLC`/`sbix`）・縦書き（`vertical-rl`/`vertical-lr`/`sideways-*`・直交 `flex`/`grid`/表・縦中横/戻し根・子孫物理プロパティ軸変換＋BFC 境界）・`::first-line` 疑似要素（水平/縦書き・子孫継承・`:hover::first-line` 再描画）・IME（先行実装済み） | 日本語・近代 Web UI 仕様の実用網羅（X1 全課題完了、M7 型抽出ラチェットも解消） | `TopLayerTest`・`AnchorPositioningTest`・`InertTest`・`HitTestIndexTest`・`ContentVisibilityVirtualListTest`・`SubgridTest`・`ColorEmojiTest`・`ColrV1EmojiTest`・`BitmapEmojiTest`・`SvgStyleCascadeTest`・`InlineSvgDocumentStyleTest`・`MatrixTransformModeTest`・`CanvasTextOutlineTest`・`VerticalWritingTest`・`FirstLinePseudoElementTest`・`ArchitectureTest`・全テスト `--rerun` 緑（`:core` 2,194 件〔失敗 0・スキップ 3〕・ルート 501 件〔失敗 0・スキップ 0〕） |
| X3-0 | 3D 接続準備: `core.scene3d`（L2）を `LayerDependencyRatchetTest` に登録、`Scene3DTags`（タグ・固有サイズ・識別属性）、ビルトイン `<scene-3d>` を `ReplacedElements` の既定（SPI 無しでも存在）に追加し `BoxMeasurer` へ「幅 auto は固有幅」（`CustomElementPainter.sceneFillsWidth`）、記述子要素 `camera-3d` 等を UA で `display:none`、`ControlPainter` が表示項目へ `data-scene-world`（nodeId）・`data-scene-kind` を付与 | 3D の足場。ホストの `<mc-scene>`（幅は親いっぱい・識別なし）は不変 | `Scene3DConnectionTest`（10 件）・`LayerDependencyRatchetTest`・`ReplacedElementsTest`・`CustomElementPainterTest`・`PortabilityRatchetTest` |
| G0 | GPU 経路の計測とラチェット: ①`GpuCoverage`（`core.paint.gpu`。`GpuAttempt` の全 12 種の図形試行を受理/断りで数える。既定は無効でフラグ 1 つの読みだけ）と `LwjglGlRenderBackend.cpuOverlayFlushCount()` を足し、`FineProfileMain` の GL 実行が `GPUCOVERAGE`（被覆率＋CPU 層へ落ちた種別ヒストグラム）と `GLFRAME`（1 フレームの draw 数・CPU 層転送数）を出す ②`PortabilityRatchetTest` に「GLSL を持つファイルと本数」（5 ファイル 9 本で固定）と「`core.paint.gl` を外から参照するファイル」（`EngineSurface`・`StandaloneViewer`・ルートの `CoreGpuSurface` の 3 件で固定）を追加 ③`GpuBaselineTest`: showcase・large-list・varied-list で GL の出力を `core/baseline/gpu/*.png`（git 管理外・マシンごと）に保存し、毎回 `SOFTWARE` と最大差 8 以内・基準画像と一致（既定は差 2 以内、`-Dgpu.baseline.exact=true` で全画素一致、`-Dgpu.baseline.update=true` で作り直し）を確認 | 以降の G / B の合否線。被覆率は全シナリオ `1.0000`（B4 の着手条件を現状は満たさない）。未達: 1 フレームの GL **生**呼び出し数（LWJGL に数える口が無い → G1 の `RecordingGpuDevice` で RHI 呼び出し数として測る） | `PortabilityRatchetTest`（3 件）・`GpuBaselineTest`（6 件。計数の単体・せん断図形が「断り」で数えられ CPU 層転送が起きること・GL 出力の決定性・`-Dgpu.baseline.exact=true` で基準と一致） |
| B1 | Chrome 比スコアボードの穴埋め: ①`cold.js`（Chrome のプロセス起動 → 初回描画）と `ColdStartMain` の `COLDPROC`（プロセス → 初回フレーム）②`trace.js` で large / varied の `scroll` と large `hover` を再計測 ③`latency.js`（Chrome の入力 → 表示）と `InputToDisplayLatencyTest`（`:core` の 0 フレーム）④`MemFootprintMain` が JAR を全部開いて土台へ寄せ、`histogramGrowth` を出す ⑤`WptReftestRatchetTest`（reftest 260 組の部分集合。`select_reftests.py` で WPT 固定版から選ぶ。合格 `81` 組を固定、時間切れ `1` 組は一覧で区別、空白どうしの一致は合格にしない） | §1.4 の全行が実測に。判明: コールド起動 `1.7x` / `2.0x` の負け（B6）、保持ヒープは `1.35x` / `1.57x`（B5 の着手条件が成立）、スクロールは `0.12–0.13x`、入力 → 表示は 0 フレーム、WPT 合格 `81 / 260`、flex のレイアウトが終わらない文書を 1 件発見（W9） | `InputToDisplayLatencyTest`（2 件）・`WptReftestRatchetTest`（6 件） |
| W9 | flex レイアウトが終わらない文書の解消（WPT `flexbox-justify-content-horiz-001b.xhtml`）: 測定の「燃料」を足した。1 回のレイアウトで、前回の測定がある箱の測り直しが `50,000 + 200 ×（初めて測った箱の数）` を超えたら、前回の測定を使う（測ったことのない箱は必ず 1 回測るので、全体は箱の数で終わる）。`LayoutBox.measureFuelReused()` で働いた回数を読める。根本原因（float を含む部分木の測定が使い回せず 2^深さ）は W9b | 19 段で終わらなかった文書が `約 60 ms`（測定 `約 6 万回`）で終わる。普通の文書では働かない（2,000 行の flex・浅い入れ子で 0） | `MeasureFuelGuardTest`（2 件。実物の WPT 文書が 30 秒以内に終わること・普通の文書で燃料が働かないこと）、`:core` 2,229 件緑 |
| X3-1 | GL 非依存 3D コア＋GL 3.2 TBO パイプライン（モード A）: `core.scene3d` に `Scene3DArena`（64B 整列 `OffHeapFloatColumn`/`OffHeapIntColumn`＋フリーリスト＋トポロジカル走査順）・`Transform3DSystem`・`GeometryPool`/`MeshHandle`・`MaterialPool`/`MaterialDesc`/`BlendMode3D`・`CameraSpec`/`LightDesc`・`InstancedBatcher`/`Scene3DUploadPlan`/`DrawBatch3D`（フラスタムカリング・128B/インスタンス TBO パック）・`Scene3DWorld` を実装。`GlVramDevice` へ `RGBA32F`/`RGBA32I` ビューを追加し、`Scene3DShaders`（GLSL 150 のみ）と `GlScene3DRenderer`（`glDrawElementsInstanced`＋TBO 3 面リング＋FBO 再利用/MSAA）へ GL 呼び出しを集約。`PaintResources`／`GpuAttempt.scene3D`／`GpuSink.drawScene3D`／`LwjglGlRenderBackend` で `drawTexture(sy < 0)` ゼロコピー合成＋`>4` クリップ等用フォールバックを接続し、`UiView`/`StandaloneView`/`EngineSurface` に `data-scene-world`（nodeId）→ `Scene3DWorld` 参照を実装（`SOFTWARE` 時は安全に解決後無描画） | モード A 成立（回転・角丸・クリップ付き `<scene-3d>` 合成、1,000+ インスタンス 1 ドローコール） | `Scene3DCoreTest`（3 件）・`GlScene3DRendererTest`（2 件）・`Scene3DModeAIntegrationTest`（2 件）・`PortabilityRatchetTest`・`LayerDependencyRatchetTest`・`ArchitectureTest`・`ApiBoundaryTest`。全テスト `--rerun` 緑（`:core` 2,219 件・ルート 501 件） |
| X3-2 | PBR・Bloom・glTF・ソフトウェア 3D: ①`SoftwareScene3DRasterizer`（64×64 タイル並列・近面クリップ・深度・半透明は後ろ→前・スレッド数に依らず決定的）を `<scene-3d>` に接続 ②GGX PBR（Cook-Torrance・Smith 相関・Schlick）＋追加ライト列（ポイント/スポット最大 8 灯。`uExtra` を UBO の配列で渡し、`efDirect` の 1 灯ぶんを主ライトと共用。逆二乗減衰に範囲の窓・スポットは smoothstep） ③ベースカラー画像（`Texture3D`・材質 `baseColorMap`/`mapRepeat`。画像ごとにバッチを分け、RHI が初回に RGBA8 で上げて 300 フレーム未使用で解放。`EF_IMG_V` で GL の上下反転を吸収） ④`GltfBinaryLoader`（`.glb`・`data:` URI の `.gltf`・`baseColorTexture`/sampler の wrap・同じ画像は 1 回だけ復号） ⑤HDR Bloom（RGBA16F・`DualKawase`・ACES。ソフトウェアは `Bloom3D`）。`ImageDecoder.Bitmap` は複数層から使うのでトップレベル `DecodedBitmap` へ昇格（M7） | `SOFTWARE`・GL・Vulkan の 3 者が同一シーンで一致（ライト付き・画像付き・Bloom 付きとも差 `>4` の画素は各 1 画素以下 / 25,600）、`.glb` 読込/解放でダイレクトメモリ増 `< 8 MB` | `SoftwareScene3DRasterizerTest`（追加ライト 5 件・画像 5 件を含む）・`Scene3DSoftwareParityTest`（`extraLights…`・`texturedMaterials…`・`bloom…`）・`Texture3DTest`・`GltfBinaryLoaderTest`・`GltfTextureTest`・`Scene3DBloomTest`・`GlDeviceDrawTest` |
| X3-3 | 入力・宣言的 HTML・`api.scene3d`: ①`Raycaster3D`＋BVH（10,000 メッシュで `0.0055 ms / 本`）と `UiView.pickScene3d`（2D が上に重なれば 2D が先に当たる） ②`Scene3DDomSync`（`core.dom`）が `<camera-3d>`・`<light-3d>`・`<mesh-3d>`・`<group-3d>` の属性を世界へ差分で写す（署名が同じなら世界を触らない・消えた要素のノード解放・形/材質/画像は記述ごとに共有・不正な属性は既定へ。`data-bind="rotation:foo"` は属性を書き換えるだけなので追加実装なしで効く）。`UiView` は文書の取り込み時と再スタイルを伴うフレームだけ同期（静止フレームの費用は 0） ③公開 API `api.scene3d.Scene3DController`/`Hit3DResult` と `WebViewSurface.scene3d(id)`（レイキャスト・ピック・ノード追加・glTF・カメラ・ライト・背景・Bloom・オービット/ズーム）④`data-orbit`: 左ドラッグでカメラを注視点まわりに回し、ホイールで寄せる（`CameraOrbit`、仰角 ±89°）⑤静止した 3D は描き直さない（世界の変更回数の署名。描画自身が起こすアスペクト更新で 1 回余計に描く不具合を直した） | 宣言した 3D がヘッドレス描画で出る・API/入力で操作できる | `Scene3DDeclarativeTest`（10 件）・`Scene3DControllerTest`（8 件）・`Scene3DOrbitTest`（10 件）・`Raycaster3DTest`・`Scene3DPickTest`・`Scene3DRedrawTest`・`ApiBoundaryTest`・`ArchitectureTest`・`LayerDependencyRatchetTest` |
| B3 | 表示項目の親相対化: ①`DisplayItem` の座標を親相対にし、`relativeOrigin` を持つ NODE の位置から測る（`EngineContext.parentRelativeDisplayItems`、既定 ON。`-Denderframe.paint.noparentrelative=true` で従来の絶対座標）。位置が変わっても子の項目を作り直さない ②絶対座標を前提にする消費者との境目は `AbsoluteItems.of`（位置・影の形・グラデーション・`relativeOrigin` を戻す）: `UiView.elements()` / `absoluteRenderCache()` / `elementById` / `hitTestAbsolute` / `UiInput.PressResult`。`client/` の面の描画は `UiView.relativeElements()` を GPU 経路へ親相対のまま渡し、従来の経路へ落ちたときだけ絶対化 ③窓で省いた親の下に残る動く子は絶対座標で持つ（`LayoutBox.paintCulled`。showcase の左上崩れの原因）④文書に繋がらない節（疑似要素の文字）は `HtmlNode.paintHandle()`＝0 で鍵を文字列から導く（鍵の衝突で文字が消えた）⑤差分再生の不具合（`itemsMutable`・箱ノードのクリップ・親原点の移動）と `tryPatchItems` の `GpuSink` 直呼び（`GpuRoute` 経由）を修正 ⑥独占コンテキストでは GL の状態退避 `glGet*` を 1 回だけ（`GlDevice.setExclusiveContext`）⑦計測: `FineProfileMain` の標本採取（`-Pprofile.sample=1`）・段別確保（`-Pprofile.stagealloc=1`）、`ShowcaseApp --hoverbench / --parity | 親相対あり/なしで画素一致（ソフトウェアは全シナリオ・疑似要素・窓付きの showcase で一致、GL は showcase のみグラデーションの丸めで差 ≤1・30 画素以内）、`:core` と ルートの全件が緑。計測（GL showcase）: `hover` mean `0.181 ms`（CPU 側 `0.022 ms`・確保 `4.1 KB`）・`scroll` `0.0089 ms`（`0.6 KB`）・幅変更 全体 `0.46 ms`（`display-list` `0.30 ms`・`118 KB`）。**未達で別項目へ**: 確保 `0 B`（B9）・`hover 0.10 ms`（B10。下限は GPU 待ち）・`client/` の残りと実機確認（B8）。`PaintSceneDriver.run` の変更集合駆動は、走査の自前の分が約 2〜6% で効果が小さく見送り（§6-63） | `ParentRelativeParityTest`（`pseudoElementTextKeysDoNotCollide`・`culledAncestorOfAnimatedChildKeepsParity` ほか）・`RelativeElementsTest`・`ChildPatchTest`・`ViewportCullingTest`・`GpuRouteTest`・`LayerDependencyRatchetTest`・`WptReftestRatchetTest`。実機は未確認 |
| B8 | MOD 側の親相対対応の仕上げと実機確認: ①画面のオーバーレイ・スロットのノード・`WebRecipeViewerBridge`・入力結果（`PressResult`）・`hitTestAbsolute`・`elementById` は、一覧全体を絶対化せず、必要な NODE だけを公開ごとの id 索引で解く（`UiView.toAbsolute` / `nodeItemAbsolute`。開発者ツールが要るときだけ `elements()`）。HUD・メディア・ブロック・ホログラム・アイテムの面は `relativeElements()` を GPU 経路へ親相対のまま渡す ②`AbsoluteItems.CONVERSIONS` で一覧全体の絶対化の回数を計り、通常の面の描画では 0 | テスト: `RelativeElementsTest`（NODE を 1 つずつ解いても変換回数が増えない・`elements()` は 1 回だけ直す）。実機（Minecraft 1.21.1、1600×900、ウィンドウ単位の取得）: ①`enderframelib:showcase` の画面を親相対 ON/OFF で撮影 → 全画素一致 ②ブロック画面（`surface_test` 5×3）・ホログラム（`animation_test`）・HUD（`hud_test`）の同一場面 → 見た目一致（差はアニメーションの位相と FPS 表示だけ）③`enderframe item give` の地図を手に持った描画（`hud_test`）と、ブロック画面の傾いた視点での描画が崩れない。**未確認（既知）**: ツールチップとホバー外枠・実機のクリックは、照準を要素に合わせられず画面では示せていない（入力経路は `WebControlInteractionTest` / `LinuxDesktopScreenInputTest` で検証）。従来の経路へ落ちる面は G5 で無くす | `RelativeElementsTest`・`WebControlInteractionTest`・`LinuxDesktopScreenInputTest`・`ParentRelativeParityTest` |
| G1 | RHI（`GpuDevice`）導入と GL バックエンドの分解: L1 `core.kernel.gpu.rhi`（`GpuDevice`・`GpuFrame`・`GpuPass`・`GpuBuffer`・`GpuTexture`・`GpuPipeline`・`GpuBindings`・`GpuCaps`・`RecordingGpuDevice`）を新設し、`PersistentVramBuffer.Device` のマップ／フェンスを RHI へ吸収（3 面永続マップ・`BUFFER_SUB_DATA` 共用）。描画ロジックを API 中立の `core.paint.gpu`（`GpuRenderBackend`・`RectStream`・`RectRenderer`・`GpuTreeStore`・`Scene3DRenderer`・`GpuSurfaceHost`・`CpuLayerFallback`）へ分離し、`core.paint.gl` を `GlDevice`（FBO/MSAA/Depth24 パス・インデックス/インスタンス描画・TBO/UBO/サンプラー）・`GlContext`（GLFW 共有コンテキスト）および LWJGL import 0 の薄い委譲ファサード `LwjglGlRenderBackend` に整理。`PortabilityRatchetTest.ALLOWED` は 4 件（うち GL 実装は `GlDevice`・`GlContext` の 2 件）へ縮小 | 描画制御面が API 中立化。GL なしで `RecordingGpuDevice` による命令列・O(1) ドロー数検証が可能に | `GpuDeviceContract`・`RecordingGpuDeviceTest`・`GpuRenderBackendRecordingTest`（500/2,000 行とも `BeginPass=1, Bind=1, Draw=1, EndPass=1`＋`filter`/`mix-blend`/`clip-path`/`<scene-3d>`）・`GlDeviceDrawTest`（MSAA/Depth24 含む）・`GlRectRendererTest`・`GlVramDeviceTest`・`PortabilityRatchetTest`・`ArchitectureTest`・`GpuBaselineTest -Dgpu.baseline.exact=true`（全画素一致）。`.\gradlew.bat test :core:check --rerun` 緑（`:core` 2,279 件・ルート 501 件） |
| G2 | シェーダーの単一ソース化・`std140` UBO 化・ビルド時 SPIR-V 検証: ①7 本の描画シェーダー＋アニメーション断片を `core.kernel.gpu.shader.ShaderSources` へ集約（`fxVertex()` を含め頂点/断片で同一 `PassParams` ブロックを共有）し、API 差分語（`EF_VERTEX_ID`・`EF_INSTANCE_ID`・`EF_FRAG_Y`・`EF_CLIP_Y`・`EF_ATLAS_Y`・`EF_UBO`・`EF_TEX2D`・`EF_TBO_*`・`EF_REC_*`・`EF_LOC`）を `Glsl`（`gl150`/`vk450`）の前置きへ隔離 ②バラの `uniform`（`glUniform*`）を廃止し `ShaderProgram.std140Offsets()`／`PassParams` UBO（binding `0`）へ統合（`SCENE3D` の `vec3` は `vec4` で 16B 整列明示、`FX` はパディング 0 順へ再配置、`GlDevice` の毎フレーム `glUniform*` を 0 化） ③`core/build.gradle` に独立 `shaderCompiler` ソースセット＋`compileShadersSpirv` タスク（`SpirvCompilerMain`＋`lwjgl-shaderc:3.3.3` Vulkan 1.1・warnings-as-errors）を追加し、ビルド時に全 8 ステージの `.spv` を検証・生成しつつ `:core` の `compileClasspath`/`runtimeClasspath` への `shaderc` 混入を 0 に固定 | 全シェーダーの源が `core.kernel.gpu.shader` 1 か所になり、GL と Vulkan 1.1 SPIR-V 双方を同一ソースからビルド保証。post-G2 の showcase GL `hover 0.230 ms` / `scroll 0.0076 ms`（`GPUCOVERAGE 1.0000`）で退行なし | `ShaderSourcesTest`（`std140` 整列・`GL`/`VK` 双方の `PassParams`・クラスパス上全 8 `.spv` のマジック `0x07230203` 検証・実行時クラスパスに `Shaderc` 不在）・`:core:compileShadersSpirv`・`:core:dependencies`（`runtimeClasspath`/`compileClasspath` に `lwjgl-shaderc` 0 件）・`PortabilityRatchetTest`・`GpuBaselineTest -Dgpu.baseline.exact=true`（全画素一致）・`GlDeviceDrawTest`・`GlRectRendererTest`・`GlVramDeviceTest`・`GlScene3DRendererTest`・`Scene3DModeAIntegrationTest`・`.\gradlew.bat test :core:check --rerun` 緑（`:core` 2,280 件〔失敗 0・スキップ 3〕・ルート 501 件〔失敗 0・スキップ 0〕） |
| G3 | Vulkan 実装（2 つ目のデバイス）＋RHI/シェーダー型改善: ①`core.paint.vulkan` に Vulkan 1.1 バックエンド（`VkContext`・`VkPipelines`・`VkDevice`・`VkSurfaceHost`・`VkCpuFallback`・`VkRenderBackend`）を GLFW 非依存・必須デバイス拡張なしのヘッドレス構成で実装し、`VkSwapchain`（`VK_KHR_swapchain` 任意有効化）で窓表示（作成・描画・リサイズ・提示・破棄）にも対応 ②テクセルバッファ（`samplerBuffer` ビュー）・3 面ホスト可視永続マップ＋セマフォ/フェンス・MSAA/Depth24（`DEPTH\|STENCIL` アスペクト）・`PassParams` 動的 UBO＋プッシュ定数・`VK_EXT_debug_utils`（`VALIDATION \| PERFORMANCE`）による検証エラー計数を統合 ③2 つ目の実装で判明した RHI/シェーダーの不整合（`VertexLayout.SCENE3D` / `SCENE3D_VERTEX` の未使用 `aTangent` 削除・`GpuDevice` / `ShaderProgram` 入れ子 12 型のトップレベル昇格による M7 解消・`VkDevice.waitIdle` でのタイムスタンプ回収・非所有 `wrapExternal`）を G1 側へ戻して是正 | 条件「汎用 1」成立。showcase / large / varied で 3 者画素一致（`maxDiff(vk, gl) <= 1`, `maxDiff(vk, cpu) <= 2`）・X3-1 回転キューブ一致（`maxDiff = 0`）・検証エラー `0`・`FineProfileMain` Vulkan が GL 比 `0.64x–1.00x`（`<= 1.0x`） | `VkDeviceContractTest`（13 件）・`VkDeviceAndRendererTest`（`RectRenderer` + X3-1 回転キューブ `maxDiff=0`）・`VkBaselineTest`（large `maxDiff(vk,cpu)=1, (vk,gl)=0` / varied `2, 0` / showcase `2, 1`・`cov=1.0000`・決定性・`valErrs=0`）・`VkSwapchainTest`（GLFW_NO_API 窓生成・描画・リサイズ・提示 `valErrs=0`）・`PortabilityRatchetTest`・`LayerDependencyRatchetTest`・`ArchitectureTest`・`.\gradlew.bat test :core:check "-Dgpu.baseline.exact=true" --rerun` 緑（`:core` 2,310 件〔失敗 0・スキップ 3〕・ルート 501 件〔失敗 0・スキップ 0〕） |
| G4 | モジュール分割と公開 API の中立化: ①`:core-gl`（`core.paint.gl`）・`:core-vulkan`（`core.paint.vulkan`）・`:core-glfw`（`StandaloneViewer`・`GlfwWindowHost`）を新設し、`:core` の `api` 依存から `lwjgl-opengl`・`lwjgl-glfw`・`lwjgl-vulkan` を外した（`:core` に残る LWJGL は本体と FreeType のみ）②`core.paint.gpu.GpuBackendProvider`（`GpuApiKind`・優先度・`available()`・`create`・`colorTexture`）と選択器 `GpuBackends`（AUTO は優先度順に作れた最初・API 指定は他 API へ逃げない・失敗は cause/suppressed に積み、足すモジュール名を文言に出す）、窓は `api.WindowHost`（`ServiceLoader`）③公開 API: `EnderEngine.Target.GPU`（`GPU_OFFSCREEN` は GL の別名で残す）・`Builder.gpu(GpuApi.AUTO/GL/VULKAN)`・`Builder.hostDevice(glfwWindow)`・`WebViewSurface.texture()`（`TextureHandle(TextureKind, handle, 幅, 高さ)`。リサイズ後も同じ API を使い続ける）④`EngineSurface` から GL の直 `new` を除去 | 条件「汎用 1・2」成立。`:core` だけのクラスパスで `SOFTWARE` が動き、GPU / 窓の指定は足すべきモジュール名つきで失敗する（実 JVM で確認） | `GpuBackendsTest` 7 件・`GpuModulesTest` 9 件（ServiceLoader の発見順・GL/Vulkan のテクスチャ種別と画素寸法・リサイズ・`GPU_OFFSCREEN` 別名）・`PortabilityRatchetTest`（`:core` の GL/GLFW/Vulkan import 0・ALLOWED は AWT のみ）・Gradle `verifyCoreDependencyGraph`（`:core` の compile/runtime 依存に GPU 系 jar が無いことを検査。freetype を一時的に禁止して失敗することも確認）・Gradle `runCoreOnlySmoke`（別 JVM）・既存の GL/Vulkan/GPU 基準テストが無修正で緑（基準画像は `-Dgpu.baseline.exact=true` で全画素一致） |
| **B2** | 144Hz 予算超過の解消（並列再スタイル・測定・配置・印付け・後片付け）: ①`LayoutTaskPool`（共通 `ForkJoinPool`・`PARALLEL_THRESHOLD = 32`・`-Denderframe.layout.noparallel=true` 切替・`EngineContext` 伝播）を導入 ②`RenderPipeline` 再スタイルの並列化（`StyleSheet.warmCaches()`＋ワーカー専用 `StyleResolver` 共有表〔`resetWorkerShareCache` / `resolveWithWorkerShare`〕＋チャンク順マージ、大規模 `markLayoutChangesAlong` 省略）③`LayoutBox`（`measureParallel`・`applyFitToParent`・`resetPlacementsFast` / `resetChildrenPlacements`・`markLayoutChanges` 強制葉早期復帰・`clearAllSubtreeMarks`）と `BlockLayoutEngine`（`measureContent` / `arrangeFlow`＋`LayoutContext.withoutAutoVisibility()`）の並列化 ④`TextShaper`（2-way L1 の `ThreadLocal` 化）・`BitmapFontFace`（`asciiCharCache` の先行確保＋`advanceCache` の `ThreadLocal` 化）・`StyleAnimator`・`InteractionState` のスレッド安全化と `DisplayListBuilder.cullableSubtree` のインデックス走査化 | **144Hz 予算 `<= 6.94 ms` 達成**: varied-2000 `fontsize`（GL 3 回中央値）`19.43 → 6.21 ms`（`6.17–6.43 ms`、`p50 6.15`、確保 `5,325 → 1,122 KB`、Chrome 比 `0.15x`）、`null` `5.95 ms`（`noparallel` `17.63 ms` 比 `2.96x`）。小規模フレーム（`showcase scroll 0.0072 ms`・`varied scroll 0.149 ms`）は `PARALLEL_THRESHOLD = 32` で直列維持 | `ParallelLayoutEquivalenceTest`（varied 180 行＋`fontsize`＋リサイズ＋`hover`/`scroll` 直列一致、ランダム 20 シード兄弟木、小木/showcase 直列、`EngineContext` 伝播、マルチスレッドキャッシュ安全性）・`ArchitectureTest`・`.\gradlew.bat test :core:check "-Dgpu.baseline.exact=true" --rerun` 緑（`:core` 2,366 件〔失敗 0・スキップ 3〕・ルート 501 件〔失敗 0・スキップ 0〕） |
| **W9b** | float を含む入れ子での `2^深さ` 再測定の根本解消: ①`LayoutBox.hasUnmeasuredSubtreeChange(ctx)`（`subtreeChanged && measuredRun != ctx.layoutRun()`）を導入し、初回レイアウトで全箱が `subtreeChanged = true` の間でも同一 `layoutRun` 内の `measureTentatively`・`fillFitReuse`・`reuseDefiniteTentative`・`canPreserveMeasurementForEmptyFloatFlow` を有効化（かつ `measureCore` で未測定部分木変更時の古い `tentatives` を破棄） ②`BlockLayoutEngine` から `BoxMeasurer.resolveFlexibleHeights(..., hadIncomingFloats)` へ先行 float の有無を渡し、`!hadIncomingFloats` かつ外寸不変なら祖先/自身の `measurementValid` を維持 ③`computeFitNeed`（`SHRINK_TO_FIT` で自BFC・自フロー閉鎖 float を許容）・`computeShrinkIndependent`（`sameParentContext`）・`measuredMinContentVar`（`tentativeStashed` 中に `getMinContentWidth` が子の `%` パディング等を読む場合は `settleTentative()` で同期）を整備 | WPT `flexbox-justify-content-horiz-001b.xhtml` の測定 `66,800 → 210` 回・`LayoutBox.measureFuelReused() == 0`（従来 `31`）。深さ `12`/`24` 入れ子で線形スケールかつ再利用 OFF との表示リスト完全一致を確認 | `MeasureFuelGuardTest`（4 件: WPT 文書で `fuel == 0` かつ `measured < 1000`・深さ `12`/`24` 線形性・`hadIncomingFloats` あり/なしと差分更新の再利用 OFF 完全一致）・`FlexSameWidthRemeasureTest`・`LayoutKeyEquivalenceTest`・`TentativeMeasureReuseTest`・`WidthOnlyReuseTest`・`FloatContainmentMemoTest`・`FlexItemCutoffTest`・`P6IncrementalTraversalTest`。`.\gradlew.bat test :core:check "-Dgpu.baseline.exact=true" --rerun` 緑（`:core` 2,388 件〔失敗 0・スキップ 3〕・ルート 501 件〔失敗 0・スキップ 0〕） |
| B9 | 表示項目の変わりやすい欄（塗り・移動量）を項目ごとの可変セル `PaintCell` へ置き、変化は通し番号と時計（`ChunkClock`）で検出する（M0 ゴールデン・M1 `ItemDiff`・M2 セル・M3 塗りの差分書き込みと構築側の再利用・M4 移動量・M6 ブロック複製の廃止。`OffsetItemList`・`withTranslation` を削除）。幅変更の再利用（M5）は時間が悪化し見送り | showcase `hover` 確保 `4.1 → 0.5–0.7 KB`（GL `3.8 → 1.0 KB`）・large-2000 `hover` `15.8 → 0.1 KB`・showcase `scroll` `2.6 → 0.6 KB`・時間は不変。`0 B` と幅変更 `118 → 30 KB` は未達 | `GoldenFrameTest`・`ItemDiffRatchetTest`・`PaintCellRatchetTest`・`ChunkClockTest`・`PaintInPlaceTest`・`ItemPropsAttrsReuseTest`・`HoverRestyleAllocationTest`・`ScrollAllocationTest`・`ScrollIndexReuseTest` |
| **A7** | `LayoutBox` から測定・配置の記憶と周辺責務を分離（2026-11-19）: ①測定記憶 `MeasureMemo`（764 行・すべての欄を `private`、22 フラグをビットパック、主スロット自然出力と `TentativeSlots` 一時退避 `stash` をプリミティブ欄に展開しつつ不変レコード `MeasureOutputs`〔48〕/`MeasureKey`〔19〕/`Tentative`〔86〕でエイリアスを型で禁止）、純粋判定 `MeasureAnalysis`（313）、変更印伝播 `MeasureChanges`（196）へ分離 ②配置記憶 `PlacementMemo`（428 行・未配置の文字/インライン箱では遅延生成 `null` で 0 B 維持・添字走査化）と `IntrinsicSizing`（249）・`ScrollExtentCalc`（252）へ分離 ③`LayoutBox` の幾何・レイアウト内部欄 44+ 個をパッケージ閉鎖し、外へは読み取り専用 getter＋`setDevicePixelRatio`＋`withPaintBounds` を公開（`:core`/ルート/`ShapeClassMain` 呼び出し側を更新、`BoxDecorationPainter` `1,016 → 995` 行） | `LayoutBox.java` `3,885 → 1,836` 行（`<= 2,000`）・public 可変欄 `106 → 40`（`<= 53`）・メソッド `201 → 170`（100 超クラス `9 → 7`・全体 `pubMut` `358 → 278`・`StaticStateRatchetTest` `166` 不変・新規の行天井追加 0）。`MemFootprintMain` 保持ヒープ `varied 37.5 → 37.0 MB`（histo `36.4`）・`large 9.1 → 8.8 MB`（histo `8.8`）・`showcase 2.9 → 2.9 MB`。`FineProfileMain`（`resizeexact showcase` `0.5436 ms`・`varied fontsize` `5.9862 ms`・`large child` `0.9532 ms`・`showcase hover` `0.1257 ms`）。`costGate` 56 シナリオ通過・経路不一致 0（`large-list child` alloc `-46.0%`・`varied-list-500 child` `-51.8%`） | `MeasureMemoTest`（8 件）・`ClassShapeRatchetTest`・`FileSizeRatchetTest`・`StaticStateRatchetTest`・`LayerDependencyRatchetTest`・`SystemPropertyRatchetTest`・`SwallowedExceptionRatchetTest`・`RegressionCatalogTest`・WPT (`160/260`)・`GpuBaselineTest`・`VkBaselineTest` (`maxDiff(vk,gl)=0`)・`ParallelLayoutEquivalenceTest`・`IncrementalVsFullTest`・`MutationSeedRegressionTest`・`costGate`（直列 56 シナリオ）・`:core:test test --rerun` 全件緑 |
