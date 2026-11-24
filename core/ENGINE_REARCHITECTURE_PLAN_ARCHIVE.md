# EnderFrameLib `:core` — エンジン設計・計測の経緯（`ENGINE_REARCHITECTURE_PLAN.md` の補足資料）

> **位置付け**: 本編 [`ENGINE_REARCHITECTURE_PLAN.md`](ENGINE_REARCHITECTURE_PLAN.md)（以下「本編」）の**補足資料**。本編は設計原則・現在地の KPI・優先順位・完了記録だけを持ち、**なぜそうしたか（実測の経緯・棄却した仮説の全文・実装記録・モジュール構成マップ）はこのファイルに置く**。
> **削除済みの文書**: 本書に出てくる `DESIGN_FRAGMENT_TREE.md`（C 系初期の設計書）は 2026-11-23 に削除した。今も効く設計は [`DESIGN_INCREMENTAL_KERNEL.md`](DESIGN_INCREMENTAL_KERNEL.md) 付録 A、試作の経緯は git の履歴にある。
>
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

> **最終更新**: 2026-11-22〔本編から 2026-11-23 に移設〕（**C3 完了（§3 → §4）**〔①レイアウト測定キーを不変レコード `MeasureKey`（`ExternalEpochs`: 測定世代・shape 世代・書体解決器＋世代・固有サイズ解決器＋世代）・`MeasureReads`（入力 `READ_*`＋親読み取り `PARENT_READ_*`）・`TentativeSlots` へ統合 ②一般化子読み cutoff（`parentReadMask`: 外寸・`contentBlockExtent`・`min-content`・上下 baseline・stretch・`PARENT_CUTOFF_BLOCKERS` で全 `LayoutEngine` の読みを記録＋再レイアウト境界も同条件で判定、旧 `contentSizedAncestor`/`cutoffSafeItem` を撤去） ③`FIT_REUSE`/`WIDTH_INDEPENDENT_REUSE`/`VIEWPORT_WIDTH_FREE_REUSE`/`BLOCK_PERCENT_FREE` を `readMask` 導出へ一本化 ④C7 ③引継の描画入力統合宣言表 `PaintBoxInputs`（`PaintStamps`・`FragmentPaint.Memo` と `PaintReadSetTest` で全単射照合）。検証: `VERIFY_MEMO`＋`MeasureReadSetTest`・全ラチェット緑（`MeasureMemo` 790 行・`ClassShapeRatchetTest` `pubMut=268/setters=3/pubStatic=25/methodsOver100=7`）・差分ファズ種 1〜1500（4,500 回）＋`VERIFY_BOX_MEMO` 種 1〜100（300 回）ずれ 0・`:core:shortcutMatrix` レイアウト 17 構成×68 件不一致 0・全モジュール `:core:test`/`:test`/`:core-gl`/`:core-vulkan`/`:core-glfw:test` 緑、直列 `:core:costGate` 全 56 シナリオ `COSTGATE OK`（`item_block_test.html text` `569,208→9,888 B` `-98.3%`/`layoutsSkipped 15,600→36,000`・`text cpu` `678,466→119,466 B` `-82.4%`・`showcase text` `10,815→10,000 B` `-7.5%`・`animation_test.html text` `10,576→9,675 B` `-8.5%`）、`FineProfileMain` A/B は C3 前 `HEAD` 比 ±4% 内。§6-167〕。直前の **S7 完了（§3 → §4）**〔①L5→`LayoutBox` 書換を完全撤去（`PaintOrder` の走査中 `culledStack`・L5 局所 `PaintBounds`・死んでいた `movingMemo*`/`dl*` 削除。`LayoutBox.pubMut` `40→34`・`1,870→1,809` 行、`DisplayListBuilder` `979→926` 行、`LayoutBoxReadOnlyFromPaintRatchetTest` 0・`SliceWriteSiteRatchetTest` 0・`PaintBoundsTest`） ②〔済〕`css.TextStroke` ③`HtmlNode.{interactionOwner,interactionFlags}` 撤去（`HtmlNode.pubMut` `8→6`・`DocumentArena.NodeSlots` ロックフリー読取＋仮想節子孫対応＋`VirtualNodeSlots`・`InteractionStateOwnershipTest`・`Milestone1GateTest`）とフレーム境界不変 PDU（`PaintOffsets.snapshot()`・`setIfActive()`・`FrozenItemList`・`PaintFrame`・`StandaloneView.frameSnapshot()`・`FrameBoundarySnapshotTest`）。全モジュール `:core:test`/`:test`/`:core-gl`/`:core-vulkan`/`:core-glfw:test` 緑、直列 `:core:costGate` 全 56 シナリオ `COSTGATE OK`。§6-166〕。直前の **C6 完了（§3 → §4）**〔`DisplayItem`・`PaintCell` 全非静的欄 `final` 化・平行移動を外部 `PaintOffsets` へ分離・`ItemDiff.unchanged` を `prev == cur` の同一性 1 本へ縮退。`ChunkClock`・`ImmutableItemList.touched`・`contentToken`・`PaintSceneDriver.itemVersion`・4 か所の「前に見た番号」を削除し、`ClipEntry` を項目参照比較へ置換。`UiView` `2,087` 行。`costGate` `voidwave.html scroll` `2,352 → 901 B` (`-61.7%`)・`machine_ui.html scroll` `-6.2%`・`inherited` `-5.2〜-6.9%`・全 56 シナリオ `COSTGATE OK`・`mutationFuzz` 450 回ずれ 0。設計書 §21.5・§6-165〕。直前の **C1p 完了（§3 → §4）**〔断片の記憶の 3 本柱＋ホットパス確保削減: 祖先 `ownReused`・永続木 `insert`/`remove`・`DirtyKids` 多兄弟スキップ・部分木ビューポート感度・直接展開・`dlStart`/`dlEnd`/`dlOrder` 書込削除〔`SliceWriteSiteRatchetTest` `8 → 2`〕・`flexOrGridItem` 鍵漏れ修正。`costGate` 確保 `hover` `-72〜-76%`・`child` `-74%`・`resizeexact` `-48%`、`PathPredictionTest` 取りこぼし `1,104 → 266`〔不健全 0〕。§6-163・164〕。直前の **C7 を §3 から §4 へ移動**〔①〜⑤完了。残りは C3〜C5 へ引き継ぎ〕。直前の **C7 ⑤ 完了**〔先行例の調査: Blink PaintController・Salsa・Stylo・Flutter・React との対応表と、新しいと言えそうな部分・限界を設計書 §22.8 に記録。C7 は ①〜⑤ 完了。§6-162〕。直前の **C7 ④ 完了**〔有界の全列挙: 12 手・長さ 4 以下の全 22,620 列で、記憶あり描画 = 記憶なし描画。実際の不具合 2 件を検出して修正。`BoundedModelCheckTest`。§6-161〕。直前の **C7 ③ 完了**〔描画が読む箱の欄を実測で洗い出し、署名・別の経路の宣言と機械的に照合。`PaintReadSetTest`。§6-160〕。直前の **C7 ② 完了**〔書き込みを値の比較つきに。経路の予測テストの取りこぼし `17,102 → 1,049`〔−94%〕・不健全 0・全件 `:core:test`・ファズ 900 回・costGate〔基準を取り直し〕緑。§6-159〕。直前の **C7 を起票し、経路の予測テストを試作**〔§3。変更の前後の木の差から理想の再利用の段を記憶と独立に予測し、実際の経路と突き合わせる。不健全 0 件・取りこぼし 17,102 件で、原因はほぼ全て「入力が変わっていないのに印が進む」。§6-158〕。直前の **C6 を起票**〔§3。描画側の 4 つの印の設計見直し。塗りの書き込みは死んだ経路、生きているのは平行移動だけ。§6-157・設計書 §21〕。直前の **C1m 完了**〔§4。箱ごとの記憶 `BoxMemo`〔791 行〕と平行移動の再利用を削除し、再利用の仕組みを断片の記憶 1 つに統合。`DisplayListBuilder` `1,581 → 958` 行。全件 `:core:test`・`:core-gl`/`:core-vulkan:test`・ファズ 450 回緑。既存の文字の幅のずれもこれで消えた。§6-156〕。直前の **C2 完了**〔§4。描画側の項目単位の差分パッチ〔`tryPatchItems`・GPU の上書き・GPU の部分描画〕を全削除。索引は状態の引き継ぎ＋作り直しの 2 つ。再生の「補間の最終フレームを覚えて古くなる」不具合を修正。全件 `:core:test`・`:core-gl`/`:core-vulkan:test` 緑。§6-155〕。直前の **C2 の第 2 弾**〔項目の再生の鍵を `ReplayKeys` に集約: `SoftwareRenderBackend` に散っていた 6 本の配列と 20 か所の操作を 照合 1 つ・記録 1 つ・破棄 1 つへ。全件 `:core:test`・`:core-gl`/`:core-vulkan:test`・ファズ 300 回緑。§6-153〕。直前の **C2 の第 1 弾**〔描画側の索引の更新を 1 本化: 並びの入れ替え・挿入削除の差分更新 `patchIndexByShift`・`patchIndexByMove` と `PATCH_SHIFT_INDEX`・統計・テストを削除し、同じ並びの差分 `patchIndexByDiff` と全面と同じ作り直し `rebuildIndex` の 2 つだけに。`PaintSceneDriver` `1,467 → 1,095` 行。全件 `:core:test`・`:core-gl:test`・ファズ 450 回緑。§6-152〕。直前の **C1p の第 1 弾**〔記憶の掃除の不具合の修正・箱の一覧のコピー廃止・子の配列と文脈の使い回し。large-2000 `child` `3.71 → 1.65 ms`・p99 `72 → 3 ms`、`hover` `0.174 → 0.15 ms`・確保 `55 → 16 KB`。全件 `:core:test`・ファズ 600 回緑。§6-151〕。直前の **C1 完了**〔§4。表示リストは `FragmentPaint` の 1 経路：箱ごとの記憶＋部分木の断片の記憶＋永続ベクタの連結。パッチ全削除。ホバー等の時間は許容して悪化し、回収は新設の C1p〕: showcase `hover` フレーム `0.211 → 0.073 ms`〔記憶なしの 1 本化直後→記憶つき〕・large-2000 `hover` `0.277 → 0.174 ms`。全件 `:core:test`・ファズ 600 回緑〔§6-150〕。直前の **表示リストの組み立てを 1 本化**〔C1 の方針どおり: パッチをやめて全面と同じ関数だけにし、保守性を先に上げる〕: 断片の経路をマスク・仮想化・Top Layer・根でない起点まで完全にし、旧全面〔`paintTasks`・`executeTasks`・チャンク・prune〕・旧パッチ 3 クラス・`FragmentPatch`・影モードを削除。`DisplayListBuilder` `2,473 → 1,568` 行・`PaintOrder` `1,487 → 935` 行、`dl*` の書き込みは `FragmentPaint` だけ〔S7 ① 実質完了〕。全件 `:core:test` 緑・ファズ緑。性能はホバー等が全面の組み立てになり悪化〔後から改善〕。未解決だった `StickyScrollNodeTest` は解決〔原因: `BoxMemo` の平行移動の再利用が、sticky の貼り付きで動いた箱を古い位置から単純移動していた既存の欠陥。パッチが隠していた。暫定対処: スクロールだけの更新でない再描画は平行移動の再利用を使わない。恒久対処は C4〕。全件 `:core:test` 緑〔`DESIGN_FRAGMENT_TREE.md`〔削除済み〕 §18.6〕。直前の **方針の訂正〔C1・C2〕**: 直前に作った `FragmentPatch`〔パッチの代替〕は、パッチをやめてメモ化へ移行し経路を 1 本にするという目的に逆行するため**撤回して削除**した。以後はパッチを新設せず、記憶〔メモ化〕だけで増分を実現し、旧経路・各パッチを削除して 1 本にする〔`DESIGN_FRAGMENT_TREE.md`〔削除済み〕 §17〕。撤回した試行: **C1 の F2: スタイル変更のパッチを断片の記録から行う `FragmentPatch`〔`Shortcut.FRAGMENT_BUILD` の下。旧 `patchBoxes`・`StackingSubtreePatch` の置き換え〕**: 項目だけが変わる箱の差し替え・種類が変わる箱を含むスタッキングコンテキストの部分木の組み直し・項目数が変わる差し替えを実装し、全件 `:core:test` を両モードで、ファズ 450 回を断片の経路で緑。ほぼ全行が旧と ±10% 前後〔showcase `hover` `0.0319 → 0.0354 ms`〕だが、voidwave `hover` は `0.0881 → 0.1225 ms`〔+39%〕・確保 2.1 倍が残るため旧経路は削除しない〔§6-146・`DESIGN_FRAGMENT_TREE.md`〔削除済み〕 §16〕。直前の **C1 の F2 の記憶の実験は効果が示せず撤回〔旧経路は残す〕**: 箱ごとの記憶と `LayoutBox` の部分木の印で子へ降りるのを飛ばしても、全面の描画は旧経路〔部分木の保持＋省略〕を上回れなかった〔showcase `resizeexact` `0.328 → 0.473 ms`・`LayoutBox` 全箱の印の伝播で large-2000 `child` の `layout.place` `+67%`〕。次は記憶の鎖でなく変わった箱の道を直接組み直す形〔§6-145・`DESIGN_FRAGMENT_TREE.md`〔削除済み〕 §15〕。直前の **C1 の B 案〔断片の経路が全面の描画そのものになる〕完了**: `Shortcut.FRAGMENT_BUILD`〔`-Denderframe.paint.fragments=true`、既定は旧経路〕の `FragmentPaint` が、箱の再帰で旧経路と同じ項目・箱の記録を出す。実文書 9 本・差分テスト・全件 `:core:test`〔`-Pfragment.build=true`〕・ファズ 600 回が旧経路と一致〔§6-144・`DESIGN_FRAGMENT_TREE.md`〔削除済み〕 §14〕。直前の **C1 の F1b の計測部分**: 断片の構造共有〔1 箱の変更で作り直す節は道の上だけ。615 節中 4〕・変更の伝播を含めた更新＋展開 `0.0026 ms`〔兄弟 2,000〕を実測して U9 を解決し、パッチ後の影は旧経路の `dl*` 記録の食い違いで撤回〔§6-143・`DESIGN_FRAGMENT_TREE.md`〔削除済み〕 §13〕。直前の **C1 の F1a〔影モードの足場〕完了**: 断片の型を本体へ移し、全面の描画のたびに断片の木を組み直して旧経路の表示リストと項目の実体まで比べる検証モード `VERIFY_FRAGMENT` を `:core:test`・`mutationFuzz` で常時有効にした。全件 `:core:test`・ファズ 150 種で食い違い 0〔§6-142・`DESIGN_FRAGMENT_TREE.md`〔削除済み〕 §12〕。直前の **C1 の F0 試作が合格**〔付録 E を 7 層の列として写した断片の木が、実文書 9 本で実ビルダーの出力と項目の実体まで一致し、large-2000 の更新＋展開が `0.0026 ms`〔合否線 `0.02`〕。本体へは未配線。§6-141・`DESIGN_FRAGMENT_TREE.md`〔削除済み〕 §11〕。直前の **C1・C2 の設計書** [`DESIGN_FRAGMENT_TREE.md`〔削除済み〕](DESIGN_FRAGMENT_TREE.md) を起こした〔断片の木。保守性の原則 M1〜M8〔純粋な組み立て・不変・位置を数えない・影モードで併走検証ほか〕を拘束条件に追加。段は F0 試作〜F5、消費側 F4・F5 は条件つき、F0 で時間の合否線に届かなければ見送り〕。直前の **C1 の第 1 段**〔足場〕: `LayoutBox.dl*` の書き込み箇所を `SliceWriteSiteRatchetTest` で固定し、C1 を 4 段に分けて §3.3 に記した。直前の **S7 ②**: L5 に残っていた CSS 値の文字列パース〔`-webkit-text-stroke-width`〕を L3 の `css.TextStroke` へ移し、`paint` は数値だけを受け取る形にして `TextStrokeTest` で固定した。直前の **S6 の一部**: GPU へ渡す口の翻訳層〔`GpuAttempt`・`GpuRoute`・`GpuLayerAttempt`・`Clip`・`ClipPart`・`ShapeGeometry`・`SoftShapes`〕を CPU ラスタから切り離して中立パッケージ `paint.emit` へ移し、`software` は画素を塗るだけにして `PaintPipelineDirectionRatchetTest` で固定した。`GpuSink` を図形の口〔`GpuSink`〕・層の口〔`GpuLayerSink`〕・再生の口〔`GpuReplaySink`〕へ分割し `GpuSinkSplitTest` で固定した〔312 → 158 行〕。直前に共有の供給元を `paint.resource` へ移し、`paint.gpu`↔`paint.software` の参照 0 を `PaintPipelineDirectionRatchetTest` で固定。残りの往復は設計判断を §6-137 に記録し、B7 の着手に合わせる〔§3 の S6〕）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-23〔本編から 2026-11-23 に移設〕（**KPI 再計測**〔§2 全行。Windows / JDK 21 / i9-12900K・同日の同一機で測定。Chrome 列は 2026-11-17〜19 の実測を再利用〕。C1 の断片経路 1 本化〔`602b0d5f`〕以降に時間が悪化していることを二分探索で特定し、計画外のボトルネックとして §3 に **C8** を起票。直前は C3 完了〔§4〕）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-23〔本編から移設〕（**KPI の 2 回目の再測定〔C9a〜c・配置の照合モード後〕**: メモの当たり以外では、`text` 変更のパイプライン時間〔配置＋表示リスト〕が旧 `ea4dc204` との交互 A/B で**全 7 文書 `0.55〜0.83x` に改善**。一方 `large-list-2000` の GL 込み `hover`・`text` が旧比 `+7〜15%` の小さな退行〔`hover` は記憶のカウンタが約 `8%`〕を見つけて §3 に **C10** を起票。**セッションをまたいだ絶対値の比較は機械の状態で `±40%` ずれる**ので時間は同時の A/B だけを使う〔§6-180〕。直前は C9c〔表〕完了）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

> **最終更新**: 2026-11-23（**計画の練り直し: 「フラグ追加 → 費用削減 → 不具合 → 検証追加」のループを構造で断つ**。診断〔記憶の仕組み約 7・無効化の言語 3・回帰カタログの 26/69 が「古い結果が残る」系・検証クラス 4・実時計を読むファイル 17〕から、配置・描画・スタイルを測定と同じ**読み取り集合の鍵**へ寄せる **K 系（K0〜K6）**を設計し〔[`DESIGN_INCREMENTAL_KERNEL.md`](DESIGN_INCREMENTAL_KERNEL.md)〕、§3 の C4・C5・C8・C9・C10 を置き換えた。**C9 は凍結**〔C9a〜c は §4〕。直前は KPI の 2 回目の再測定〔§6-180〕）。これより前の更新の経緯は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) の末尾「本編冒頭の更新履歴」。この行は日付と直近 1 件だけにする（運用ルール 3）。

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

---

## 付録 3: 旧 §6 の知見 1〜205（2026-11-23・2026-11-24 に本編から移設）

> 本編 §6 から移した原文。番号は本編の参照（`§6-N`）と同じ。回帰カタログのテスト（`RegressionCatalogTest`）は本編とここの両方を読む。

§6-1. **「パッチが当たらない」段階は過ぎた**。パッチ命中は全シナリオで 100%。残る遅さは、当たったパッチの内側で計算を省くための前提（同一性・整数性・祖先の性質）が壊れやすいことから来る。

§6-2. **小数 1 つで平行移動の近道が全部外れる**（移動量が整数でないと丸めが置き直しと一致しない）。float は「1/64 の倍数かつ `< 2^18`」なら加算が厳密で、これを借りて縦方向を格子化できた。`DisplayItem` が整数座標の限り、表示リスト側には効かない（Q1b）。

§6-3. 〔不具合〕**平行移動は「配置されなかった箱」を動かしてはならない**（`br`・断片のない空の `span` は位置 0,0 のまま）。小数の移動を許すと顕在化する潜在バグで、一致テストが捕えた。

§6-4. **計測の範囲を揃える**。Chrome の幅変更値はペイントを含まない。`:core` のフレーム全体と比べると 12x に見えるが、同じ範囲では 4〜5x。**実装の効果を測るベンチが、その実装の経路を通っているか**をカウンタで毎回確かめる（`resizeexact`・`BOXMEMO translated`・`LAYOUTSKIP shifted`）。

§6-5. 〔不具合〕**flex の項目は 2 つの制約（仮 → 確定）で測られ、1 スロットの測定キャッシュが交互に追い出される**。`Tentative` 側の `outsideFree`/`widthFree` 漏れ・確定別枠に加えて、`stretchItems` 前の `settleTentative` が stretch 予定の仮ヒット項目まで仮寸法で測り直して確定記録を壊していた（Q6b で解消）。

§6-6. 幅変更の測り直しの約半分は、使える幅が実際に変わる箱（fill 幅の flex・段落）で、測定キャッシュの鍵を広げるだけでは消えない。Chrome は結果キャッシュ・親相対の座標・グループ化したスタイルで構造的に避けている。

§6-7. 差分メモ（`ComputedStyle.diff`）は、同形のリストでは効くが、行ごとにスタイルが違う文書では各組の最初の走査が残る。残りはグループ化（Q2b）でしか減らせない。

§6-8. **外れた仮説**: varied `inherited` はカスケード＋算出が支配的（→ 実際は算出後の比較・分類と祖先走査）／showcase の `MATCHFAIL 4` は中央寄せの 0.5px が原因（→ 計測経路の問題）／P2 の断片再利用が `child` の確保を減らしている（→ 近道が外れた経路では `clear()` で効いていなかった。Q1a で解消）／`GlRectStream` 容量拡大は GL 走査を速くする（→ 棄却。アーカイブ §6.3）。

§6-9. **節の状態（`InteractionState` のフラグ）は節自身が持つ**。同じ文書に別の `InteractionState` で一から組むと、元の状態のフラグが奪われる。「一から組んだ結果との比較」は、連鎖を測り終えてから行う（Q7 のテストで判明）。

§6-10. 幅変更の近道は「ルート以外にビューポートへ依存する要素が無い」を世代つきで覚えて O(1) にした（依存が増えたときだけ世代を進める）。表は箱へ直接書き込むので対象外。

§6-11. 〔不具合〕**fill 幅ブロックの `fitNeed` は `FlowBuilder` と同じ幅基準で数える必要がある**。独立テキスト計測 `b.w`（末尾空白トリム）だけを使うとインライン要素間の空白幅が抜けて `93.3 < 99.3 px` のように境界を下回り、折り返した測定結果を誤再利用する（`ParagraphSegments.fullW`＋正カーニング加算＋`FlowState.wrapped` で `fitReuse` `443 → 3` を達成。一方 `showcase` 自体は固定幅カード＋% 項目主体で Q6b 時点の `0.12 ms` 付近が限界のため、次は `display-list` の `Q1b` が本命）。

§6-12. 〔不具合〕**Q1b で分かった複合前提の境界条件**: `ValueComputer` がカスケード時に `em`/`rem` を `"4.81px"` 文字列へ展開するため辺縁の `LayoutUnit` 格子化は `BoxMeasurer.resolveBoxEdges` に必須、Flex の `placeLineItems` は測定後に `auto` margin を書き換えるため配置鍵に `lastLayoutML..MB` が必要、`fitToFragments` は非インライン子（インライン内の `display:flex` 等）で再帰を打ち切るため `shiftSubtree` も到達可能性を引き継がないと子孫のインライン箱を誤移動する。`MATCHFAIL 4` が `54 → 8` に下がった残りは主に `MATCHFAIL 6 = 38`（実際の寸法変化）なので、座標 `int` 化・親相対化の追加実装は費用対効果から見送った。

§6-13. **Q3 の `isCulledNoop` による祖先走査増幅**: バッチ単位のエポックメモ（`movingEpoch`）だけでは、`STYLE_CHANGED_BOXES` の ~2,000 個の間引き対象要素すべてで `isCulledNoop` → `hasMovingAncestor` が呼ばれ、`PaintOrder.hasMovingProps` ＋祖先走査が JFR の `2.7%` を占め続けた。再スタイル／構築の再帰で `ancestorMoving`・`selfOrAncestorMoving` を O(1) 刻印（`movingStamped`）すると JFR は `0 / 447`（`0%`）へ消滅し、`display-list` 段階も `0.738 → 0.312 ms` へ半減した（`canSkipRestyle` 側は親の `selfOrAncestorMoving != child.ancestorMoving` のときは飛ばさず継承を更新する必要がある）。

§6-14. 〔不具合〕**Q4 の表セルと擬似要素の扱い**: `font-size` 等を構造変更から外した際、`TableIncrementalLayoutTest` が `1px` ずれ（`230` vs `231`）を検出した。`TableLayoutEngine` が箱に直接 `collapsedBorders` / `contentOffsetY` / `engineState` を書き込むため `touchedByTableEngine()` な表要素だけは従来どおり再構築する必要がある。また `restylePseudoElement` も構造・非構造の切り分けと `layoutChangedBoxes` 登録を揃えないと、擬似要素を持つ行で `structural=0` が崩れる。

§6-15. **Q5 のプロファイル比較**: `LayoutBox.updateStyle`・`inheritsExplicitly`・`StyleTable` ビューポート依存判定の 3 箇所は JFR 上 `0 / 320`（`0%`）へ完全に消えた（JFR 付きは `4.97 ms` だがクリーン計測は `6.37 ms` 付近でばらつき内）。現在の支配要因は `ComputedStyle.diff` / `scanChangedIds`（17.5%）＋`BoxTreeBuilder.restyleGeneratedBoxes`（15%）＋`ThreadLocal.get`（15%）のため、本格的な短縮は **Q2b** へ引き継ぐ。

§6-16. **Q2b のバンド分割とゲート規約**: `CssPropertyId` を `(inherited, paintOnly)` の 4 連続バンドへ並べて `StyleSlice` を限定プールで遅延共有し、`RenderPipeline` の再スタイル呼び出しから `diff()` ラムダ・配列確保・`ThreadLocal.get()`・`BoxTreeBuilder.restyleGeneratedBoxes` の `LinkedHashMap` 再構築を外したところ、varied `inherited` は `6.37 → 3.80 ms`（Chrome 比 `1.07x`）・確保 `2.8 → 1.1 MB` へ到達した。なお `Milestone2GateTest` が `ComputedStyle` 内の `Arrays.copyOf` 呼び出し（`copyOfRange` 含む）を 0 件に固定しているため、スライス切り出しには `System.arraycopy` を使う必要がある。

§6-17. **P5 のテキスト成形区間キャッシュと語単位キャッシュ**: `varied-list-2000 fontsize` の `layout.measure` を支えていたのは成形失敗ではなく、`ConcurrentHashMap.get`・`FontFace.naturalAdvance` の毎回計測・`LineBuilder` の `LineItem`/`ArrayList` 割り当て・`SPARE_FRAGMENT_LIMIT=16` による断片再確保だった。`NaturalSegments` でビット一致のままフォントサイズ倍率スケーリング（カーニングは 3 乗算 `pair*s - prev*s - curr*s` で `floatToIntBits` 一致）を行い、`ShapedInterval` / `WrappedParagraph`＋`InlineLine` スライス直接配置＋2 枠 `LayoutBox.TextCache` を入れた結果、`TextShaper` 関連は JFR 上位から消失し、`measure 9.09 → 5.08 ms`（`-44%`）・確保 `19.35 → 8.92 MB/frame`（`-54%`）へ削減できた（長文断片に合わせ `SPARE_FRAGMENT_LIMIT` は `16 → 128` へ拡張）。

§6-18. 〔不具合〕**P6 の増分走査と `ReusableTable` の境界条件**: `DocumentArena.free` が `id >= staticCount` の動的 `nodeId` をフリーリストへ即時再利用するため、ノード索引配列の `ReusableTable.take` は取得後の `b.node == node` 照合が必須（`z2.setTextContent` の置換で判明）。また、表組み遷移（`TABLE -> TABLE_ROW` 等）での再利用抑止と `restyleLayoutTree` 先行時のフラグ消去は、それぞれ `buildLayoutTree` 内の `isInternalTableBox` 判定と `hasUnmarkedChanges`/`hasStructuralPending` 引継で解消した。

§6-19. **P8 の確保元は計画の想定と違った**。JFR の `jdk.ObjectAllocationSample`（後半半分を集計）では varied `fontsize` の確保は折り返し行の `InlineLine`・`LineItem`・測定ごとの `ConstraintSpace` が支配的で、`CssValue[]` のコピーやインライン style の解析は上位に出ない。`FineProfileMain` の `alloc` は main スレッドだけを数え、着手時点で既に `3.87 MB/frame`（以前の `8.91 MB` は計測条件が違う）。

§6-20. 〔不具合〕**`DEFINITE`（確定測定の葉再利用）の不具合はエイリアスだった**。`reuseDefiniteTentative` が `measuredOutputs = o` で記録（`Tentative.outputs`）と同じ実体を持ち、後続の `captureOrUpdate` が記録をその場で書き換えて古い値（`w=90`）を別の上書き幅（`138.5`）の記録として残していた（`TentativeMeasureReuseTest`・`WidthOnlyReuseTest` のファジングが赤）。写し取る形に直し、上書き寸法（`overrideWidth/Height`）も記録の鍵に加えて解消。以前の「不健全なので既定オフ」「フォーム部品を除外」は誤診で撤回済み（`LayoutBox.definiteLeafReuse` は既定オン、切り分け `-Denderframe.layout.nodefiniteleaf=true`）。

§6-21. **P7: 遷移の「許可チャンネル」と「実際に動くチャンネル」は別**。`AnimState.transitionMask` は既定で透明度・変形・色をすべて含むため、`geometryStaticAt` は色だけの遷移でも false（動く）を返していた。開始値と目標値を比べる `motionKindAt` に替えると hover の遷移は塗り・変形に正しく分類され、全面描画が消えた。変形は動いた節の子孫を旧範囲＋新範囲で塗り直す既存のスクロール系の仕組みで画素一致する。不透明度は描画側が層（グループ）を作るので全面のまま。

§6-22. **P8b: 保持量の内訳は推測せず、ヒープダンプで引く**。`MemFootprintMain` に `-Dmem.dump=<file>`（`JAVA_TOOL_OPTIONS` 経由）を足し、HPROF を `build/prof/hprof.py` で集計した（フィールド別の一意な `String` 実体数）。`class_histogram` だけでは「語の `String` が共有されていない」と誤読しかけたが、実際は語は共有済みで、重複していたのは行全文（`StringBuilder` から作り直した `ShapedLine.text`）とタグ・属性名だった。`byte[]` 3.2 MB のうち 1.8 MB は JAR の中央ディレクトリ（`ZipFile$Source.cen`）で、エンジンの保持ではない。`LayoutBox` は 1 つ約 544 B（4,116 個で 2.2 MB）が最大項目で、残る削減は `LayoutBox`/`StateFlags` のフィールド整理（疎なフィールドの副オブジェクト化）。テスト JVM は検証モードで保持量が約 2 倍（14〜16 MB）になるため、ラチェットは本番設定の値でなく粗い天井にした。

§6-23. **R6: コールド `open` の大半は「一度きりの処理のインタープリタ実行」で、CDS（AppCDS）ではほぼ縮まない**。`-Xint` `695 ms` / C1 のみ `406 ms` / 既定 `478 ms` / AppCDS `450–470 ms`。つまりクラスの読み込みよりも、UA シート解析・静的表・初回の各段のコードを温めていないまま走らせる時間が支配的。採取（`-Dcold.sample=1`）の内訳は `layoutDocument` 37%・`parseDocument` 26%・`paintBoxes` 17%・`buildStyleSheet` 16% で、特定の 1 箇所ではなく広く散る。よって「速くする」より「画面を開く前に別スレッドで済ませる」が有効で、`EnginePrewarm` 完了後は `137 ms`。**温めと `open` を同時に走らせる（`bg`）と短縮は出ない**（同じクラスの初期化ロックを取り合う）ので、ロード画面など `open` の前に走らせる使い方になる。残る約 `137 ms` は showcase 固有の機能（温め文書に無い）の初回実行で、温め文書を増やすほど減るが、本番の文書に依存する。

§6-24. **R5/RTI は「着手条件つき項目」を再計測で閉じた**。GL 送出の割合は過去値（`child` 44%・`inherited` 33%）から P6〜P8 で `20% / 26% / 16.5%` に下がっていた（上位施策で表示リストと配置が縮んだぶん送出の比率は変わらず絶対値が減った）。条件を決めて保留にしておいたことで、コストの高い再設計（保持 GPU 記録・インスタンス化）を、測定だけで見送れた。

§6-25. **P9: 「毎フレーム全部書き直している」は、書き込みの“変更数”を数えて初めて見えた**。永続バッファは「同じ値の書き込みは汚れにしない」設計なのに、クリップ欄が 2 回書きで値が往復し、その差分検出が全図形で空振りしていた。`PersistentVramBuffer.changedWrites()` を足して `FineProfileMain` の GL 行に `changedWrites/frame` を出すと `2,607 → 143`（`-95%`）。ただし**時間は 11% しか縮まなかった**（書き込み自体は安く、本体は 1,232 図形・約 1,070 字ぶんの CPU 側の組み立て＝`drawItem`/`drawGlyphs`/字形キャッシュ引き/atlas slot/クリップ構築）。JFR のサンプル帰属は `touch`・`rasterize` のような関数に偏って見えたが、字形の miss は 0 だった（偏りで、改善の根拠にしない）。残りを大きく減らすには、項目単位で GPU 記録（字形の atlas slot・位置）を保持して変更の無い項目を丸ごと飛ばす R5 が必要。showcase のような小さい文書では GL 送出が `88%` を占めるので、R5 の着手条件（30% 超）は large-2000 の割合（17〜26%）ではなく showcase では満たす。

§6-26. **P9b/R5: 計測の道具が結果を歪めた（2 件）**。①切り分けのため `drawGlyphs` 先頭に `Boolean.getBoolean("x.skip...")` を入れて「字形の処理を飛ばしても速くならない」と結論しかけたが、`getBoolean` 自体が毎回 `System.getProperty`（同期）で、省いた分を足していた。切り分けのフラグは `static final` に読み込んでから使う。②JFR の自己時間は `touch`・`rasterize` を上位に出したが、字形の miss は 0、書き込みも 143 件/フレームで、サンプルの帰属の偏りだった。確かな測り方は「項目種別ごとの `nanoTime` 計測」で、showcase GL `hover` の `drawItem` は TEXT `1.4 µs × 約 200 項目 = 0.28 ms`（フレーム `0.43` の約 65%）、RECT/枠/フォームは合計 `0.08 ms`、項目を全部飛ばした床が `0.13 ms`（走査・同期・`dirtyBounds`）。TEXT のうち、`events` の解釈・スタイル・BiDi の下ごしらえは P9b で保持して軽くなった（確保 `-23%`）が時間は `-5%` で、**残りは図形の送出（`GpuAttempt.glyph` → atlas slot 引き・`accepts`・`open`・書き込み）約 `0.95 µs/項目`**。字形の並び（字形・原点）だけの保持（`GlyphRunCache`）は効果が無く（`0.45 → 0.45`）取り下げた。ここを大きく減らすのは項目単位の GPU 記録の再生（R5）で、クリップ表の番号付け・atlas の追い出し・不透明度の層の整合を壊さずに再生する設計が要る（本セッションでは未実装）。

§6-27. 〔不具合〕**R5: 再生の安全条件は「同じ入力」だけでは足りなかった（3 つの落とし穴）**。①`Resolved` の同一性は色の補間値の同一性を保証しない（姿勢が止まっていると `AnimatedValues` を同じ実体のまま書き換える）。遷移中は再生しない（`idleAt`）。②クリップ slot の番号が同じでも中身は他の項目に上書きされうる。参照するクリップ slot の鍵（20 float）を記録して照合し、新規登録分は再生時にクリップ表へ入れ直す。③再生は「CPU が描いた層を、その上に積む図形の前に重ねる」順序づけを飛ばす。再生の直前に `flushOverlay` を 1 回行う（CPU 描画を含む画面で、項目が重なる検査を入れて初めて、この検査を外すと赤になった: せん断した箱が CPU に回り、その下に重なる単色の箱が再生されるケース）。**テストが「効いている」ことの確認**（条件を外して赤になるか）は、最初の文書では通ってしまい、重なる要素を足して初めて有効になった。クリップ内容の照合を外しても現行のテストは赤にならない（その状況は文書に再現できていない）ので、このガードは設計上の根拠のみ。**注意**: 全テスト実行中に並行ビルドがクラスを差し替えると `NoClassDefFoundError` で 92 件落ちる（再実行で緑）。

§6-28. **P10（バッチ 1〜7）で判明した残差の内訳**: showcase GL の時間 KPI について、`text` (`0.215 ms`) と `style` (`0.207 ms`) は目標 `< 0.25 ms` を達成、`hover` も `p50 0.148 ms` まで短縮したが、mean (`0.203 ms`) の約 76% (`0.154 ms`) は `paint(cpu)` の固定走査（`PaintSceneDriver.run` / `resolveAt`＋`GlRectStream.spanCanReplay` で全 730 項目中 455 項目を毎フレーム検査）が占め、`0 B` / `< 1 KB` 確保制約には `DisplayItem` 再生成・`OffsetItemList`・`CoreWheelEvent`・`PaintSceneDriver.Resolved` が残る。

§6-29. **`StyleSheet.declaresContainers()` / `usesContainerQueries()` の版数キャッシュ漏れ**: `StyleSheet` の他の走査メソッド（`usesHasSelectors`・`declaresViewportDependentValues` 等）は `rules.version()` でキャッシュされていたが上記 2 件だけ未キャッシュで、`text` シナリオの JFR CPU 最上位（`8.7%`）で毎回全ルール全プロパティを走査していた。また `Corners.ZERO` / `LengthResolver.cornerRadiiXYShared` / `ItemProps` 遅延マップにより無角丸・無属性ノードの中間配列確保を一括排除した。

§6-30. **縦書きは「軸を入れ替える」より「論理の軸で組んで回す」方が安全**: 物理/論理の対応を全エンジン（ブロック・インライン・float・測定キャッシュ）へ通すと差分・再利用の経路が壊れやすい。根の内容を回転付きの生成箱 1 つに包み、既存の変形（`PropertyTrees`・ヒットテスト・スクロール範囲以外）をそのまま使うと、ホバー・文字更新・差分描画は無改造で新規描画と全画素一致した。拾った穴は 2 つ: 回転前の箱で数えるスクロール範囲（横スクロールが出る）と、1 字ずつ正立させる字の幅（描画側に測定器が無いので `PaintServices.fontResolver()` の送り幅を行の幅へ等倍配分）。

§6-31. **GL 依存の棚卸し（2026-11-15、G 系の根拠）**: GL / GLFW の呼び出しは 684 箇所・72 種類の関数で、内訳は `LwjglGlRenderBackend` 524・`GlVramDevice` 78・`GlRectRenderer` 58・`StandaloneViewer` 14・`GlFenceRing` 8・`GlRectStream` 2。最多は `glBindFramebuffer` 30・`glActiveTexture` 25・`glUniform*` 約 60 で、図形ごとの呼び出しは無い（図形はレコード書き込み）。つまり**データ面は既に API 中立で、縛っているのは 1 ファイルに混ざった制御面**。拡張への依存は `GL_ARB_buffer_storage`（永続マップ。無ければ `glBufferSubData` へ退く）と `GL_ARB_timer_query`（計測のみ）の 2 つだけ。GLSL 150 のシェーダーは 4 ファイル 7 本で、1 本は GL 実装の外（`core.paint.AlphaMask`）にある（`PortabilityRatchetTest` は import しか見ないので検出されない）。

§6-32. **レンダラが 2 本ある**: ルートの `client/renderer` に Blaze3D 向けの別実装が約 9,400 行あり、コアの GPU 経路を Minecraft 内で使う `CoreGpuSurface`（共有コンテキスト）は試験的・既定オフで、`<canvas>`・3D・ホストの画像・ビットマップ書体を含む面は全画面で従来経路へ落ちる。「汎用性が低い」の実害は GL か否かより、ホストごとに描画実装を持つこと。ホストのテクスチャとデバイスを受ける口（`wrapExternal`・デバイスの採用）が RHI に要る理由。

§6-33. **文書の記述と実装のずれを訂正**: §1 は「Java 21 FFM API」「`MemorySegment`」と書いていたが、`:core` の `java.lang.foreign` 参照は 0 件で、オフヒープはダイレクト `ByteBuffer`＋`VarHandle`（FFM は JDK 21 ではプレビューで、起動フラグを要求しない方針と両立しない）。ネイティブ束縛を FFM 前提で計画できないので、追加デバイスは LWJGL の束縛がある Vulkan を先にし、`wgpu-native` は最低 Java の引き上げと組にした（G6）。

§6-34. **G0 の被覆率は「受理 + 再生」で数える**: 止まっている項目は `spanReplay` で過去の受理を再利用して `GpuAttempt` を通らないので、試行の数だけで割ると定常（`hover`）では分母がほぼ 0 になる（showcase `hover` は受理 `56` に対し再生 `1,182` / フレーム）。断りは毎フレーム試行されるので試行側に残る。LWJGL の静的 GL 呼び出しは数える口が無く、生の GL 呼び出し数は G1 の RHI で数える。基準画像はシステムフォント依存でマシンごとに作る（git 管理外）。

§6-35. **WPT の reftest は「ブラウザとの比較」ではなく「参照との一致」で測れる**: 部分集合 `260` 組（機械的な選別: スクリプト・外部リソース・Ahem 無し、等間隔抽出）の合格は `81`（flexbox `55 / 120`・grid `12 / 80`・text `14 / 60`）。空白どうしの一致を合格にすると、何も描けていないのが見逃される（空白判定を入れた）。`flexbox-justify-content-horiz-001b.xhtml` はレイアウトが終わらなかった（W9。解消済み）。Java は走っているスレッドを止められないので、時間切れの組は `reftest-hangs.txt` に載せて通常の実行では走らせる仕組みは残してある（今は空）。

§6-36. **保持ヒープの比較で JAR の中央ディレクトリは差分に入らない**（B1④）: クラスパスの JAR を全部開いたまま土台を測り直しても `heapRetained` は同じ（large `9.2 → 9.2 MB`）で、`histogramGrowth` の上位は `LayoutBox`・`InlineFragment`・`CssValue[]`。Y4 の「`byte[]` `3.0 MB` のうち `1.8 MB` が中央ディレクトリ」は絶対量（土台に載っている分）の話で、Chrome との差分比較には当たらない。測定順にも依存する（large は varied の後だと `8.0`、前だと `9.2–10.0 MB`）ので、計画の手順どおり varied → large → showcase の順で測る。

§6-37. **コールド起動は Chrome に負けている**（B1①）: Chrome は新しいプロファイルでも `chrome.exe` 起動 → 初回描画が約 `300 ms` で文書の大きさにほぼ依らない（showcase `304`・large-2000 `302`）。`:core` は JVM 起動を含めて `510` / `594 ms`。ウォーム時の `open`（`5 ms`）で Chrome と並ぶのと対照的に、起動は一度きりの処理（クラスロードとインタープリタ実行。§6-23）が支配する。`EnginePrewarm` を裏で済ませるホストなら `137 / 187 ms`（2026-11-14 値、JVM 起動は含まない）。

§6-38. 〔不具合〕**レイアウトの暴走は「測定の数が箱の数の何倍か」で捕まえられる**（W9）: 入れ子の float flex を 1 段ずつ増やすと、測定の回数が `161`（12 段）→ `4,828`（13 段）→ `3,029` → `302,106`（16 段）→ 終わらない（19 段）と段ごとに跳ねる。原因は、各箱が 2 つの制約を交互に受けて 1 スロットの測定キャッシュが毎回外れること（`space:diff`）で、float を含む部分木は仮の測定・fit 再利用が使えない。キャッシュの条件を緩める案（上書き幅が自然な幅と同じなら使い回す）は、上書きすると `sizingMode` が `DEFINITE` になり子へ渡す整形コンテキストが変わるので等価ではなく、採らなかった。

§6-39. **RHI の座標規約は「左上原点」で固定し、GL の下端原点はデバイス実装の中だけで吸収する**（G1 段 1）: `GlDevice` はアップロード・読み出しで行を反転し、クリップ・コピーでは領域の y を `高さ - (y + h)` に写す。`GpuDeviceContract`（9 項目）は非対称な領域で上下を見分けるので、`RecordingGpuDevice`（CPU の画像）と本物の GL が同じ答えを返すことが、規約を守っている証拠になる。`RecordingGpuDevice` は画素を持つので、GL なしの CI でも「何を描いたか」を値で検証できる。

§6-40. **GLSL を「版の行だけ前置きが付ける」形に直しても出力は変わらない**（G2 段 a）: 源 8 本をそのまま 1 か所へ移し、組み立てた文字列のハッシュが移動前と一致することで、描画が同じであることを保証した（画素の確認は基準画像が補う）。計画の「GLSL 7 本・4 ファイル」の数え方は、Javadoc の `Pixmap#version()` を `#version` と数え違えていた（`AlphaMask` は GLSL を持たない）。ラチェットは行頭または文字列の先頭の `#version N` だけを数えるよう直した。

§6-41. **RHI の「フレーム」は 1 回の描画全体を包む**（G1 段 2）: `GlDevice.beginFrame` はホストの GL 状態（FBO・ビューポート・シザー・テクスチャユニット・プログラム・VAO・ブレンドなど約 20 項目）を問い合わせて退避し、`end()` で戻す。問い合わせは図形ごと・層ごとではなく 1 フレームに 1 回にしないと、描画 1 回が `0.2 ms` の KPI に効く。したがって `LwjglGlRenderBackend` を RHI へ移すとき（段 4）は、`render()` 全体を 1 つの `GpuFrame` で包み、層の切り替えは `beginPass` で表す。個々の `drawPixels` だけを RHI へ置き換えても、呼ぶたびに退避が走るので意味がない。

§6-42. **描画の RHI 化は「橋」を挟むと 1 描画ずつ移せる**（G1 段 3a）: バックエンドは描画先（FBO）・アトラス・画像のテクスチャを自分で管理しているので、いきなり `GpuTexture` の描画先へ切り替えられない。そこで `GlDevice` に GL 固有の 2 つの橋（現在の FBO へ描くパス・呼び出し側が結んだテクスチャユニットを指すテクスチャ）を置き、矩形の描画だけを先に RHI の `bind` / `drawInstanced` へ移した。橋は段 4 でバックエンドの描画先を `GpuTexture` にしたときに消す。サンプラーの値はプログラムに残るので、パイプラインごとに最初の `bind` で 1 度だけ設定する（`GlRectRendererTest` の「サンプラーは最初の描画で 14 個」を保つ）。

§6-43. **並列に配れる割合は高いが、`7 ms` 未満には測定・配置だけでは届かない**（B2 の計測、24 スレッド）: varied-2000 `fontsize` の `17.8 ms`（計測器なしの `null`）は restyle `3.3`・測定 `5.8`・配置 `5.7`・表示リスト `1.2` ほか。測定の `86%`・配置の `70%` が独立な行の中なので、測定・配置だけを `8` 並列にしても `17.8 → 約 9.9 ms`。restyle（行どうしは独立）も配ると概算 `約 7 ms`。残りの直列（表示リストの組み立て・描画・位置の積み上げ）が下限を決める。計測の落とし穴: 「最も外側の独立な単位」を親の兄弟数の条件なしで数えると、html → body → 唯一の子の鎖が全体を 1 単位として `99.9%` になってしまう（配り先が無い）。兄弟が `8` 以上の所だけを単位にした。

§6-44. **G1 完了時の設計上の落とし穴とヘッドレス RHI 検証**（2026-11-15）: ①`GpuPass.Bindings` のような `public` 入れ子型を別パッケージから参照すると `ArchitectureTest`（M7 パッケージ横断入れ子型ラチェット）に掛かるため、`GpuBindings` へトップレベル抽出した ②`LazyCpuLayerTest` がフレーム外から `snapshotTarget()` を呼ぶ経路では `activeFrame == null` になるため、`host.enter()/exit()` 内で一時 `GpuFrame` を張って読み戻す必要があった ③`GpuRenderBackendRecordingTest` により、GL/GLFW を一切起動せず `RecordingGpuDevice` 上で 500 行・2,000 行の同一 RHI 命令数（`BeginPass=1, Bind=1, Draw=1, EndPass=1`）と `filter` / `mix-blend-mode` / `clip-path` / `<scene-3d>` のパス分離を直接検証できるようになった。

§6-45. **G2 完了時の GLSL 前置き・Vulkan SPIR-V・Gradle 循環回避の知見**（2026-11-16）: ①OpenGL の GLSL プリプロセッサは `#define M(y) ...` の仮引数 `y` をメンバー参照 `.y`（`textureSize(uAtlas, 0).y` → `.p.y`）まで置換するため、`Glsl` のマクロ仮引数は `ef` 接頭辞（`efViewH` 等）が必須 ②Vulkan GLSL 450 では頂点・断片間で同一の `PassParams` UBO 宣言（`ShaderSources.fxVertex()`）と非衝突の `layout(set=0, binding=...)`（UBO `0`・2D サンプラー `1..3`・3D TBO `8..9`・2D 矩形 TBO `16..27`）、さらに `std140` 16B 整列が明確な `vec4` 配置（`SCENE3D`）が要る ③Gradle で `shaderCompiler` ソースセットのクラスパスへ `sourceSets.main.output` を渡したまま生成 `.spv` 出力先を `sourceSets.main.output.dir(..., builtBy: 'compileShadersSpirv')` へ登録すると循環依存になるため、`shaderCompiler` は `compileJava.destinationDirectory`＋`compileClasspath` のみを参照し、`lwjgl-shaderc` を `shaderCompilerImplementation`/`shaderCompilerRuntimeOnly` に閉じて `:core` 実行時依存への混入 0 を保った。

§6-46. **B2 は 4 段が配れても `7 ms` に僅差で届かず、共有可変状態が実装の本体**（B2 事前調査、24 スレッド）: varied-2000 `fontsize` の段は restyle `3.27`（`93%` が行の中）・測定 `5.33`（`85%`）・配置 `5.74`（`71%`）・表示リスト `1.13`・計上外 `1.9 ms`（フレーム `17.4`）。`8` 並列・同期費用ゼロの上限は `0.61 + 1.37 + 2.17 + 1.13 + 1.93 ≒ 7.2 ms`（表示リストも配れば `約 6.3`）で、目標 `6.94` は表示リストと計上外に掛かる。実装の障害（コードを読んで確認）: (1) restyle は `layoutChangedList` / `styleChangedList`（スレッドローカル）と `InteractionState.register`、算出スタイルの共有キャッシュへ書く。ワーカごとの一覧を行の順に併合し、登録を安全にする必要がある。(2) `EngineContext` はスレッドローカル（`callWith`）で、ワーカへ引き継ぐ必要がある。(3) 表示リストの `fragIds`（`AtomicInteger`）は木の順の通し番号で、並列にすると id が非決定的になる。行ごとに範囲を事前割り当てする必要がある。(4) 位置の積み上げ（兄弟の高さの和）は直列。「並列あり/なしで箱の木が完全一致」の検証を先に作る。結論: 全段の並列化は大工事のわりに目標へ僅差。先に計上外 `1.9 ms` を減らせるかを調べる。

§6-47. **コールド起動の遅さは JVM のウォームアップで、AOT キャッシュ（JEP 483）で `2x` 短縮できる**（B6）: 温め無しの `open` を支配する一度きりの処理は、クラスのロード・リンクとインタープリタ実行。JDK 27 の AOT キャッシュ（訓練 1 回・約 `23 MB`）が同じ範囲を `482 → 220 ms` に縮めた。AppCDS が効かなかった（§6-23）のはクラスだけを保存するからで、AOT キャッシュはロード済み・リンク済みの状態まで保存する。落とし穴: キャッシュはディレクトリのクラスパスを拒否する（`Cannot have non-empty directory in paths`）ので、jar にまとめて訓練する。共有の `core/build` を別の作業が同時に再ビルドすると途中でクラスが消えるため、計測は別 worktree で行う。

§6-48. **`LayoutBox` の欄を整理しても保持ヒープは `1.0x` に届かない**（B5 の事前見積もり。large-2000、`MemFootprintMain`）: 保持 `8.1 MB`（Chrome `+5.1 MB`）のうち `LayoutBox` `4,116` 個が `2.23 MB`（1 個 `584 B`）、`InlineFragment` `12,300` 個が `0.73 MB`、`StateFlags` `0.52 MB`、`CompactList` `0.49 MB`。`LayoutBox` の欄は `179` 個で、4 B の数値 `72`・参照 `41`・boolean/byte `57`（各 1 B）・8 B `6`。測定キャッシュ（`measured*`）の `35` 個前後は全ての箱が使う。めったに使わない欄（tentative 系・`reusableTable`・`textCache` など）を移しても `約 40 B`、boolean 57 個を bit 欄へまとめても `約 50 B`、合計 `約 100–150 B / 箱 ≒ 0.4–0.6 MB`（`large 8.1 → 約 7.5 MB`、`1.45x`）。`1.0x`（`-3 MB`）には `x/y/w/h`・余白など全員が使う欄と測定キャッシュを列（`LayoutArena`）へ移す全面改修が要り、`~190` 欄の全読み書きが対象。得られるのは large 1 件の比率で、時間 KPI（§1.4）は既に Chrome 比 `0.46x`。費用に見合わないので、B5 は他の項目が片付いた後に再判定する。

§6-49. **レイキャストは BVH の枝刈りが正しいことを「1 ノードずつ」で確かめる**（X3-3）: BVH の結果の検証に、全ノードを順に「そのノードだけ可視」にして同じレイを撃ち、最短を取った結果と比べる（150 本・80 ノード、回転・拡大縮小つき）。枝刈りの誤りは当たりを取りこぼす形で出るので、別実装の総当たりが要らない。10,000 個の立方体（400³ に散布）で `0.0055 ms / 本` は、BVH が候補を数個に絞るため。作り直しは `transformEpoch` で判定し、動かさない間は 0 回（`rebuilds()`）。画面ピックのレイの始点は近クリップ面なので、`t` は近クリップ面からの距離になる。

§6-50. **3D の選択は 2D のヒットテストの「後ろ」に置くと前後関係が自然に揃う**（X3-3）: `pickScene3d` は先に既存の `hitTest` を呼び、当たった項目が `<scene-3d>`（`data-scene-kind=3d`）のときだけ、その局所座標と項目の幅・高さで `Raycaster3D.pickScreen` を撃つ。上に重なった 2D の要素（`position:absolute` の div など）は `hitTest` の段階で先に当たるので、3D は選ばれない（`Scene3DPickTest`）。モーダル・inert の絞り込みも `hitTest` がそのまま効く。レイキャストはワールドごとに 1 つ使い回す（BVH を持つため）。

§6-51. **G3 完了時の Vulkan 実装と RHI フィードバックの知見**（2026-11-16）: ①LWJGL の `MemoryStack` は既定 64 KB（スレッド初期化時に固定）で、拡張数が多い GPU（RTX 3070 Ti で 250 件超 × 約 260 B）では `new VkInstance(...)` や拡張列挙で溢れるため、`VkContext.withLargeStack` で一時的に 512 KB スタックへ差し替えることで JVM フラグ不要のまま解消できた ②Windows Vulkan ローダは他社製壊れマニフェスト（`EOSOverlayVkLayer` 等）の `GENERAL` 警告を流すため、`VK_EXT_debug_utils` は `VALIDATION | PERFORMANCE` に絞らないとアプリの検証エラー計数を汚染する ③`VK_FORMAT_D24_UNORM_S8_UINT` は `depthAspectMask` で `DEPTH \| STENCIL` 両ビット（イメージビューとバリア）を揃える必要があり、`waitIdle()` 時点の保留 GPU タイムスタンプ回収・非所有 `wrapExternal` も契約テストで明文化された ④2 つ目の実装で `SCENE3D` の未使用 `aTangent` 属性（パイプライン検証警告）と `GpuDevice.*` / `ShaderProgram.{ViewFormat,RecordView}` 入れ子型の `ArchitectureTest` M7 違反が露見し、それぞれ除去とトップレベル昇格で RHI 側へ戻して完治した。

§6-52. **G4 の分離は `:core` 本体の変更がほぼ要らなかった。落とし穴はテストのソースパス参照**（G4、2026-11-16）: `:core` 本体から GL/GLFW/Vulkan を参照していたのは `EngineSurface`・`StandaloneViewer`・`GpuSurfaceHost`（コメントのみ）の 3 ファイルだけで、`core.paint.gl` / `vulkan` は同一パッケージ名のまま別モジュールへ移せた（クラスパス上のパッケージ分割は問題なし）。ソースを文字列で読むテスト（`Milestone4GateTest`・`LazyCpuLayerTest`・`CpuFallbackOfRareShapesTest`・`PortabilityRatchetTest`）は移動で壊れるため全件実行で拾った。`:core` のテストは `testImplementation project(':core-gl')` などで GPU 実装を含めて回す（循環にならない）。検査は「依存グラフ」（Gradle `verifyCoreDependencyGraph`）と「実 JVM」（`runCoreOnlySmoke`）の 2 段にし、前者は禁止リストに freetype を足して実際に落ちることを確かめた。**B2 の計上外**: `layout.marks`（`markLayoutChanges`・`clearAllSubtreeMarks`）を段として足して測ると `0.24 ms`、GC 停止は `-Xlog:gc` で約 `0.1 ms`/フレーム。varied-2000 `fontsize` の計上外 `1.7–2.5 ms` の大半は計測の外側（ハーネスの `setAttribute` と無効化）で、並列化の対象外。

§6-53. **B2 は `restyle`/`measure`/`place` に加え `resetChildrenPlacements`・`markLayoutChanges`・`cullableSubtree` を詰めて `144Hz` 予算内へ到達**（B2、2026-11-16）: 兄弟の独立部分木だけ並列化した初期版では `varied-2000 fontsize` が `~7.9 ms (GL)` に留まった。JFR で主スレッドを確認すると `LayoutBox.resetPlacements -> discardFragments`（主スレッドサンプルの `122/341 = 35.8%`）と `DisplayListBuilder.cullableSubtree` の拡張 for 文による `AbstractList$Itr` 生成が直列で残っていたため、`resetChildrenPlacements` / `markLayoutChanges`（強制葉の早期復帰付き） / `clearAllSubtreeMarks` も `LayoutTaskPool` で並列化し、`cullableSubtree` をインデックス走査へ改めた結果、GL 3 回中央値 `6.21 ms`（`p50 6.15 ms`・確保 `1,122 KB`）・`null` `5.95 ms`（`noparallel 17.63 ms`）まで低下した。落とし穴: ①`ArchitectureTest` M7 はソース文字列上の `Outer.Nested` 参照をパッケージ単位で数えるため、`common.RenderPipeline` から `StyleResolver.ShareCache` を直接参照すると失敗する。ワーカー共有キャッシュは `StyleResolver.resetWorkerShareCache()` / `resolveWithWorkerShare(...)` として閉じた ②`GpuBaselineTest`・`ArchitectureTest` を含むルート＋`:core` 全体で `StyleResolver` 変更後の回帰なしを確認済み。

§6-53b. 〔不具合〕**ソフトウェア版を作ると GL 側の既存バグが見える**（X3-2）: 3 者の画素比較は「どちらが正しいか」を決める手が無いと意味がないので、ソフトウェア版は数学的に正しい式（逆行列の転置・前面は反時計回り）で独立に書き、食い違いを GL の不具合として調べた。結果 2 件: ①`InstancedBatcher.writeInstances` の法線行列が「逆行列」を書いていた（回転した物体の陰影が逆回転）うえ要素 1 つ（`nm5`）が別の式だった ②`GeometryPool.createSphere` の三角形の巻きが逆で、背面カリングのもとで球の手前が消え奥の内側が描かれていた（法線が外向きなので真っ黒になる）。どちらも GL/Vulkan で同じ結果になるため、GL≡Vulkan の比較では見えない。直した後は GL とソフトウェアが各チャンネル最大 `3` 以内。8 bit クリップのせいで鏡面の頂点（GGX の D は粗さ `0.2` で約 `150`）は白飛びしやすく、色味の検証は光量を `0.005` まで落とす必要があった（トーンマップ未導入。Bloom の HDR 化で解消する見込み）。

§6-54. 〔不具合〕**Bloom で見つかった 3 つの落とし穴**（X3-2、2026-11-16）: ①Vulkan のディスクリプタ配置は `firstTextureUnit + i` なので、サンプラーだけのプログラムを `firstTextureUnit` 未指定（0）で宣言すると UBO（binding 0）と重なり、検証レイヤーごと JVM が落ちる（`RECTS` は 1 を明示している。`TEX`・`FX` も 0 のままで、Vulkan で実際に使うと同じ問題が出る疑い〔未検証〕）。②`RGBA16F` は upload・readback の対象外だが、コピー・MSAA の解決は形式を問わない移動なので通す必要があった（GL の blit・Vulkan の resolve はそのまま動く）。③ソフトウェア側の発光は 8 bit クリップで隠れていた誤り（`emissiveStrength` に不要な ×4）が、HDR にした途端に 4 倍の光として表に出た。HDR の比較は 8 bit の比較が見逃す差を拾う。そのほか `StaleTextPatchTest` が全件実行のときだけ失敗し単独では通る（B2 の並列化との相互作用か不安定さか未調査。Gemini の領域）。

§6-55. 〔不具合〕**新品 `LayoutBox` の `subtreeChanged = true` が初回パス内再利用をすべて塞いでいた**（W9b、2026-11-16）: 初回レイアウトでは全箱が `subtreeChanged = true`（かつ `clearAllSubtreeMarks` は `layout()` 終了後にしか呼ばれない）ため、同一パス内の 2 回目以降の測定でも `measureTentatively`・`fillFitReuse`・`reuseDefiniteTentative`・`canPreserveMeasurementForEmptyFloatFlow` が全滅し、`resolveFlexibleHeights` が毎回 `invalidateMeasurement()` で祖先の `measurementValid` を消して `2^深さ` に膨らんでいた。`subtreeChanged && measuredRun != ctx.layoutRun()`（`hasUnmeasuredSubtreeChange`）へ切り替えたところ `66,800 → 210` 回・`fuel=0` まで落ちた一方、初回レイアウトで `measureTentatively` が有効になったことで既存不具合（`GridLayoutEngine.measureTracks` が `measureTentatively` 直後に `getMinContentWidth` を呼び、子孫の `%` パディング `pL`/`pR` が別幅のまま残った状態を読んでしまう、`FlexSameWidthRemeasureTest` `seed 5`）が顕在化したため、`tentativeStashed && measuredMinContentVar` 時の `settleTentative()` で整合性を保った。

§6-55b. **CI が 50 コミット連続で赤かった原因は 2 系統**（2026-11-16）: ①ルート JAR が `:core-gl`・`:core-vulkan` の同名 `META-INF/services/…GpuBackendProvider` を重ねて `jar` が落ちる（G4 の取りこぼし。Windows では `test :core:check` しか回しておらず `build` を回していなかった）②書体・OS に依るテスト（`InertTest` の撃つ座標が文字に当たる・`SurfaceResourcesTest` が「OS の書体 ≠ 内蔵書体」を前提・WPT の合格一覧が Windows 基準）。ログは認証が要るが、失敗の注釈は `check-runs/<job id>/annotations` から誰でも読める（今回の調査はここと WSL の再現で行った）。対策: サービスファイルの結合と JAR の検査・`scripts/ci-linux.ps1`（git の blob＝LF のスナップショットで、DISPLAY / XDG_RUNTIME_DIR を外して実行。作業ツリーの CRLF は `gradlew` を壊すので使わない）・pre-push フック・CI のテスト失敗サマリー。WSLg 上では `WAYLAND_DISPLAY` を外すだけでは GL のテストが走る（`XDG_RUNTIME_DIR` の既定ソケットに繋がる）。

§6-56. 〔不具合〕**テクスチャの向きは API ごとに違う**（X3-2、2026-11-16）: RHI の GL は行を上下反転して持つ（上げた先頭行が画面の上になる向き）一方、Vulkan は先頭行が `v = 0`。同じ `texture(uv)` では 2 者が上下逆になるので、`Glsl` に `EF_IMG_V` を足して吸収した（GL は `1 - v`）。3 者の画素比較で初めて分かった（GL だけ `10,745` 画素ずれ）。

§6-57. 〔不具合〕**描画自身が世界を書き換えると「静止判定」が 1 フレーム遅れる**（X3-3）: 初回描画がカメラのアスペクトを更新して世界の署名を進めるため、静止した 3D が 2 フレーム目にもう一度描かれた。描画後に署名を取り直して解消。

§6-58. **複数の層から使う入れ子型は昇格が要る**（X3-2）: 画像の復号結果 `ImageDecoder.Bitmap` を `scene3d`・`dom` からも使い始めると M7 が落ちる。トップレベルの `DecodedBitmap` へ昇格した。シェーダーの uniform ブロックを伸ばしたら、直接 `GpuBindings` を組むテスト（`GlDeviceDrawTest`）も新しい大きさ・サンプラー数に合わせる。

§6-59. 〔不具合〕**文書に繋がらない節の `nodeId()` は鍵にできない**（B3、2026-11-16）: 疑似要素の文字の節は文書に繋がらず、節ごとに別の木のアリーナで同じ番号（1）が振られる。`DisplayItem.keyOf(nodeId, 部品)` が複数の疑似要素で衝突し、2 つ目以降の文字が消えた（`::before` を持つ兄弟 2 つで再現）。`HtmlNode.paintHandle()`（文書に繋がらなければ 0 ＝文字列の ID から導く）を描画の鍵に使う。

§6-60. 〔不具合〕**差分再生で使い回す箱の記憶は、可変かを実体で見る**（B3）: `itemsMutable` が真のまま `List.copyOf` の不変リストへ差し替わると `UnsupportedOperationException`。箱自身のノードが持つクリップが前と違うのに「外側だけの差」と見て使い回すと、ノードのクリップが古いまま子へ伝わる（`ChildPatchTest`）。親のノードの原点が動いた箱の部分クリップも組み直す。

§6-61. 〔不具合〕**`GpuSink` の呼び出しは経路（`GpuRoute`）を通す**（B3）: 項目単位の差分再生（`tryPatchItems`）が `GpuSink` を直接呼んでいて `GpuRouteTest` などの検査が落ちた。`GpuRoute` に `beginDiffPatch` / `patchSpanBegin` などを足して通した。

§6-62. **短いフレームの時間は「GPU の待ち」で決まる**（B3、2026-11-16）: GL の `hover` は CPU 側が `0.022 ms` なのに壁時計は `0.18 ms`。JFR は短いフレームを取りこぼすので、`FineProfileMain` に別スレッドの標本採取（`-Pprofile.sample=1`）を足した。状態退避の `glGet*` を省くと、待ちが `glClientWaitSync`（フェンス）へ移るだけで合計はほぼ動かない。CPU の最適化の効果は、GPU が空いているフレーム（実機の 16 ms 周期）で見る。

§6-63. **`PaintSceneDriver.run` の走査そのものは小さい**（B3 ②、2026-11-16）: ソフトウェア描画の `hover`（標本 1,717）で `run` の包含は 24% だが、その中身は項目の描画（`drawItem` 368）で、走査・索引の自前の分は約 2%（GL では約 6%）。「`run` の全項目走査が `hover` の 76%」は、描画（ラスタ）を含む数字だった。変更集合だけを触る形にしても縮むのは数 % で、描画そのものを減らす（損傷範囲＝`planDamage` を広げる）ほうが効く。

§6-64. **GPU が受け取れるクリップは 1 つの図形に 4 つまで**（B4、2026-11-16）: 入れ子の箱が同じ範囲で切る（親が直角・子が丸角）と、実質 1 つのクリップが 2 部品として積まれ、5 段で上限を超えて図形ごと CPU 層へ落ちた（`surface_test` の 18 個/フレーム）。同じ空間・同じ倍率で別の部品をすっかり含む直角の部品は、重ねても結果が変わらないので `finishClip` で除く。CPU の被覆率も部品の積なので、同じ縁を二重に掛けていた分（`c²`）が `c` になるだけ。

§6-65. **「全部再生して範囲だけ描く」は、範囲の外が前のフレームの結果のまま残るので安全に近い**（B10、2026-11-16）: 保持した記録を全部再生しても、シザー付きの `CLEAR` で範囲を消してから描けば、範囲の中は全面描画と同じ列で描き直され、外は触らない。範囲の見積もり（項目の矩形に余白）ではなく、各スパンが積んだ図形のデバイス画素の外接矩形を記録して旧範囲に使うので、影のぼかしや文字の張り出しで外れない。条件（前のフレームが主ターゲットへ全部描いた・層なし・大きさ/アトラスが同じ）を外れたら従来の全面。部分描画あり/なしの全画素比較を毎フレーム回すテストが要る。

§6-66. 〔不具合〕**表示項目を「変わったら別の実体」から「セルへ書く」に替えると、実体の同一性で変化を知る消費者が黙って壊れる**（B9、2026-11-17）: 壊れたのは項目単位の再生（`spanEl`）・静止フレームの飛ばし（`StaticFrameGate`）・MOD のレイヤー再利用・`CssAnimator` で、`==` の検索では配列要素との比較を見落とした（ラチェットに配列形を足した）。スナップショットは書き込みのたびに新しいままにし、変化は通し番号（消費者が自分で持つ）と `ChunkClock` で知る。値の控えが要るテストは `DisplayItem.frozen()`。

§6-67. **`hover` の確保の本当の原因は「項目が可変でない」ことではなく、差分パッチが構築側へ前の項目を渡していなかったこと**（B9 M3c）と、兄弟の多い親の再スタイルが子の数ぶんの表を毎フレーム確保していたこと（`15.8 KB`）だった。設計を変える前に JFR の確保の標本で出所を見る。

§6-68. **幅が変わる箱の項目をその場で書き換えても得にならない**（B9 M5、外れた仮説）: リサイズでは項目の `meta`（クリップ・影の形）が箱の大きさで変わり、使い回せる項目は 8% だけ。メタを書き換え可にして使い回すと確保 `-9%`・時間 `+10〜15%`。幅変更の確保の支配項は `RenderMeta`・勾配・移動複製（`appendNodePatched`）で、箱の記憶が「項目は記憶の時点の値のまま」を前提にしている限り削れない。

§6-69. **書体を揃えても Chrome 比はほとんど変わらなかった。変わったのは「全行の文字を測り直す」1 行だけ**（A2、2026-11-17）: `:core` を内蔵の簡易書体から Arial（`OutlineFontFace`）に替えても、`hover`・`scroll`・`text`・`style`・`child` は ±10% 以内。文字の測定は、行の中身が変わらない操作では再利用されるため。varied-2000 `fontsize` だけ `6.28 → 8.20 ms`。「簡易書体だから速く見える」という疑いは、この 1 行にしか当たらない。逆に Chrome は書体を Arial に強制すると全行で数〜30% 速くなった（既定の書体のフォールバック探索が減るためと見られる。未確認）。

§6-70. **並列化の効果は 8 本で頭打ちに近い**（A3）: varied-2000 `fontsize` は 1 → 2 → 4 → 8 → 23 本で `17.45 → 10.94 → 8.03 → 7.12 → 6.04 ms`。2 本目で `-37%`、8 → 23 本は `-15%`。確保量は 1 本 `3,460 KB`、2 本以上 `941 KB`（計測は main スレッドの分だけを数えるので、ワーカーへ移った分が見えなくなる。確保が減ったのではない）。

§6-71. **代表 3 文書に無い経路が実文書にあった**（A12）: 常時アニメーションのある文書は、どの操作でも毎フレーム全部を組み直す（`GPUCOVERAGE` の再生が 0）。showcase・リストでは差分パッチの命中が 100% なので見えなかった。計測の文書を増やすと、最適化が効いていない場所が先に見つかる。

§6-72. **平行移動の近道（`shiftSubtree`）は確保量を増やしている**（A1 のゲートの確認中に判明）: `-Denderframe.layout.noshift=true` にすると、`child` の確保量が large-500 で `187 → 139 KB`（`-26%`）、varied-500 で `267 → 179 KB`（`-33%`）に減る（`layoutsShifted` は 301,200 → 0）。時間は測っていない。近道が時間を縮める代わりに確保を増やしている可能性があり、`child` が Chrome と互角（§2）であることと合わせて調べる価値がある。

§6-73. **確保量は「新しい JVM・ワーカー 1 本」なら回帰の判定に使える**（A1）: 同じ JVM で多くのテストの後に測ると JIT の状態で数倍に揺れる（`HoverToggleCostTest` の注記）が、シナリオごとに JVM を立て直すと 2 回続けて全 17 シナリオが ±5% に収まった。経路の回数（パッチ命中・`layoutsSkipped` ほか）は完全一致で比べられる。

§6-74. **`StaleTextPatchTest` の不安定さの原因はエンジンではなくテスト**（A5④、§6-54 の「B2 との相互作用か不明」に決着）: `resize` 後に 1 フレームだけ回して比べていたが、`UiView.resizeDue` は直前のレイアウト時間の 2 倍（壁時計）が経つまでリサイズを保留する。保留された回だけ、(a) 比べる時点でリサイズが未適用、(b) 追加のフレームで仮想時間が進み `StandaloneView.stepScroll`（スムーズスクロール）が余計に進んで位置が `-78` / `-89` とずれる。並列レイアウトを切っても再現（`noparallel` で 3 / 12）し、並列テストの後ろに置くと頻度が上がる（レイアウトが重くなり保留に当たりやすい）。時刻を進めずにリサイズの適用を待つ形に直して 0 / 15。壁時計に依るテストは「条件が成立するまで時刻を進めずに待つ」と書く。

§6-75. **`cleanTest` + `test` はビルドキャッシュで 1 秒に終わる**（A5④の連続実行の準備中）: 反復実行は `--rerun --no-build-cache` を付ける。

§6-76. **近道を 1 つ切るだけで出力が変わるものが 20 件あった**（A6）: 各スイッチ単独オフの 37 構成のうち 8 スイッチが、乱数の変更列で既定と違う署名・画素を出す（A6b の表）。既存のテストは「その近道の on/off」を決まった場面で比べていて、ホイール・hover のトランジション・入れ子の flex/grid・DOM の増減を混ぜた列では見ていなかった。

§6-77. **`showcase.html` は JVM ごとに最初の手から結果が違う**（A6）: 同じ設定の既定どうしでも手 0 の署名が違う（時刻や乱数に依る内容）。差分テストの文書には使えない。決定的な `catalog.html` を使い、既定の再実行と `-Xint` との一致を毎回確認する。

§6-78. **検査の感度は再注入で測る**（A6）: 最初の 3 文書・種 4 のマトリクスは、§6-20 のエイリアスを戻しても緑だった。flex/grid の入れ子の文書（上書き幅で測り直しが起きる）と種を足して初めて `layout.nocutoff` の構成で赤になった。新しい検査は、過去の不具合を戻して赤になることまで確かめて完成とする。

§6-79. **増分と全面の自己比較は、時刻を同じにしないとノイズだらけになる**（A6b）: 比べる側のフレームで仮想時間を進めると、トランジションとスムーズスクロールが進んで近道と無関係な差が山ほど出る。`StandaloneView.lastNow` と同じ時刻で回す（`dt = 0` なら進まない）。DOM を直列化して作り直す方法は、`>` が `&gt;` になるなど直列化の欠損でノイズが大きく使えなかった。

§6-80. 〔不具合〕**子パッチは「兄弟に影響しない子」だけが対象**（A6b）: float を消す・位置指定の子を足すと、兄弟の行の折り返しや描画の層が変わる。差分の経路は、変わった箱の外寸が同じだと中身（行の位置）の変化を見落とす（段落の箱の大きさは同じで、行だけが動いた）。局所パッチへ新しい種類の子を通すときは、`IncrementalVsFullTest` の文書にその形の変更を足す。

§6-81. 〔不具合〕**挿入位置の「ブロック層の末尾」は、flex / grid アイテムとインラインレベルの箱の中を数えない**（A6b）: 表示リストは CSS の描画順（ブロックの背景 → …→ インラインレベル・アトミックな箱）で並ぶので、`display` がブロックでも flex アイテムは後ろの層にいる。部分木の「最後のブロックの箱」を単純に拾うと、リストの末尾を指す。新しい種類の兄弟を局所パッチへ通すときは `IncrementalVsFullTest` の nested の列で厳密な並びを確かめる。

§6-82. 〔不具合〕**「全面の結果が正しい」は仮説で、外れることがある**（A6b ②）: `layout.nocutoff` の表の幅 262 / 260 は、増分（260）が正しく全面（262）が誤りだった。枠線を重ねる表の外周の枠線は `measureContent` の中で決まるので、新しく作った箱の最初の測定だけが枠線 0 で内側の予算を取って利用可能幅を超え、前回の値が残る測り直しは正しかった。食い違ったら、どちらが CSS どおりかを最小の文書で先に決める（同じ形の「最初の測定だけ前回の出力を読めない」値が無いかも疑う）。

§6-83. 〔不具合〕**表示リストの署名に clip が入っていないので、clip の食い違いは画素でしか見えない**（A6b ④）: `nomemo` × scrolllist は署名が同じで画素だけ違った。`-Pmatrix.dump` は clip も出す（`matrix.sigclip`）。`ClipRect` の文字列は `ClipChainPart` のハッシュを含み JVM ごとに変わるので、署名そのものには足せない。

§6-84. 〔不具合〕**小数のスクロール量は「表し方の違い」ではなく直せる差だった**（A6b ⑤）: 既定の平行移動は小数のまま描き、作り直しは項目の整数座標へ丸める。スクロール量（減速の途中・行き先）を整数 px に揃え、つまみは作ったときの丸めの端数を持って `round(端数 + k·Δ)` で動かすと、両経路が全画素一致した（Chrome も画素へ揃えて描く）。同じ列で、インラインの中の span の箱は x / y が (0, 0) のまま（位置は断片だけ）で、窓の判定が箱の座標を見ていたので親の無い項目が画面に出る不具合も見つかった。窓・省略の判定に箱の座標を使うときは、断片を持つ箱かを確かめる。

§6-85. 〔不具合〕**パーサは改行を含む空白だけのテキストを捨てていた**（A11、2026-11-18。第 4 段で解消: 要素の間の空白は原文で残し、表示用テキストで空白 1 つに。増分の子パッチは、直前の兄弟が箱を作らない節なら箱のある兄弟まで遡る。代償として Dual DOM の初回パケットが `4,500 → 6,183` バイト（+37%）、100 セッションの DOM ノードが `3,000 → 4,900`、showcase を開く確保が +10% になった（正確さを優先すると判断。パケットは A16① で 4,451 バイトに、開く確保は A16③ で +2.5% に、セッションのメモリは A16② で +0.8% に戻した。`-Denderframe.parser.dropnewlinews=true` で従来の挙動に戻せる。Headless State の比較元 `server_baseline.txt` は H0 の値のまま）。以下は当時の記録）: `HtmlParser.normalizeText` は改行入りの空白だけの節を作らず、テキスト先頭・末尾の改行も空白として残さない。CSS では改行もスペースと同じく 1 つに畳まれるので、`<span>a</span>⏎<span>b</span>` は Chrome で「a b」、ここでは「ab」になる（inline-block の並びの隙間も消える）。改行も空白として残す変更を試すと、WPT 部分集合の合格数は変わらず（`112`）、増分と全面の一致テスト（`ChildPatchTest`・`IncrementalVsFullTest`・`DisplayListSlicePatchTest`。節の番号がずれ、表示項目の鍵が食い違う）と showcase のゴールデンが落ちた。直すなら、増分の経路が空白の節を全面と同じに番号付けするところから。

§6-86. **空白の節の確保の主因はスタイルの共有の外れだった**（A16、2026-11-18）: 「同じ親の文字の子で算出スタイルを共有する」案は `ShareCache` で既に実現済みで、主因は親ごとの 1 回目の外れ（親の表を継承するカスタムプロパティごと写してカスケードと算出をやり直す。約 9 KB）だった。「アニメーションの確認が 1 節 0.8 KB」の見立ては外れ。JFR の確保の標本は揺れるので、疑わしい箇所は一時的な数え上げで確かめる（標本の推定 290 KB に対し、外れは 32 回 × 約 9 KB）。

§6-87. **呼び出し側が並列区間で待つだけだと、ワーカー N 本は N 本ぶんしか働かない**（B11、2026-11-18）: `ForkJoinPool.invoke` を外部のスレッドから呼ぶと、そのスレッドは `park` で待つ（varied-2000 `fontsize`・4 本で main の標本の 71%）。既定のワーカー数は「コア数 − 1」で、描画スレッドの 1 コアを残す設計なのに、その 1 コアが区間の間は遊んでいた。番号を先着で配り、呼び出し側も取ると 4 本で `8.56 → 7.70 ms`。区間を 1 本 1 個に割ると、起きるのが遅いワーカーの分を誰も拾えないので、本数 × 4 に割る。

§6-88. **全体の世代でメモを守るなら、世代を進める条件を絞らないと「毎フレーム全部外れる」**（B11）: 形の世代（`SHAPE_EPOCH`）を `updateStyle` が要素ごとに無条件に進めていたので、`font-size` だけの再スタイルで float メモが全部外れ、2,000 行の親の直列の走査（`scanFloats`）が直列部分の 8% を占めた。メモの正しさは検証モード（`verifyFloatMemo`、テストで常時有効）が見張るので、条件を絞っても安全に確かめられる。

§6-89. **並列化のあとに残る直列の時間は、2,000 個の子を 1 回なめるループの数で決まる**（B11）: 1 つ 1 つの処理は軽く、子のデータに最初に触るキャッシュミスが支配する（`addFragment`・`initialKeyword` のような軽い関数が標本の上位に出る）。関数を速くするより、ループそのものを消す（メモ・印で早く抜ける）か並列の前処理へ移す方が効く。落とし穴: 直列の記録で `addAll(Arrays.asList(配列))` にすると包みと配列の写しを 1 行ごとに確保し、`costGate` の `fontsize` が `+12%` になった。

§6-90. **実文書が差分に乗らない理由は「常時アニメーション」ではなく「ホバーでスタッキングコンテキストが変わる」だった**（B12、2026-11-18。外れた仮説）: voidwave・surface_test・animation_test の `hover` は 3 本とも `CANTPATCH boxes[6]`。ボタンの `:hover { transform: … }` で箱がスタッキングコンテキストになり、項目が付録 E の層 8（位置指定と同じ層）へ移るので、箱のスライスの差し替えでは表せない。スタッキングコンテキストの項目は表示リストで連続するので、前後とも変わらない祖先のコンテキストの部分木だけを全体と同じ手順で組み直せば済む（voidwave は `.hero-actions` の `animation-fill-mode: both` が祖先のコンテキストになる）。祖先が文書の根しか無い surface_test・animation_test は全体の作り直しのまま。

§6-91. **差分パッチが当たっても、描画側の索引の作り直しが同じだけ重い**（B12）: 部分木の組み直しで表示リストの時間は `0.50 → 0.09 ms` になったが、描画（`paint(cpu)`）は変わらなかった。項目が離れた 2 か所で入れ替わる（長さは同じ）と、`patchIndexByDiff`（鍵の一致が前提）も `patchIndexByShift`（前後の一致が半分以上）も外れ、`rebuildIndex` が全項目の補間の状態を引き直し（`CssAnimator.stateFor` の鍵の intern）、姿勢と再生の記録も捨てていた。同じ実体の項目の状態を新しい位置へ写す（`patchIndexByMove`）と再生が戻り、`hover` `0.93 → 0.69 ms`。全体の作り直しでも項目の大半は記憶の同じ実体なので、surface_test・animation_test の `hover` も `-20%`・確保 `-83%`。

§6-92. **voidwave の `scroll` は「根のスクロール」ではなく「粘着の見出しのあるスクロールコンテナ」だった**（B12、2026-11-18。最初の見立ては外れ）: ページ全体は `.scroller`（`position: absolute; overflow-y: auto`）の中でスクロールし、その子の `.nav` が `position: sticky; top: 0`。`PaintOrder.scrollsAsOneNode` は粘着・固定の子孫があると断るので、1 段ごとに位置を決め直して表示リストを組み直していた（`BOXMEMO translated 360`）。sticky のずれはレイアウトの式（`applyStickyOffset`: 貼り付く前の y・上の限界・包含ブロックの下端）だけで決まり、スクロールで中身が `Δ` 動くと 貼り付く前の y と〔親が中身の中なら〕下端が `Δ` 動くだけなので、作ったときの値を持てばスクロール量から計算できる。中身の節の移動に、sticky の箱の節へ「ずれの増減」を足すと、作り直しと画素・ヒットテストまで一致した（`1.62 → 0.43 ms`）。浮動小数の往復の誤差を避けるため、レイアウトは足す前と足した後の y をそのまま覚え（`stickyUnstuckY` / `stickyPlacedY`）、式が作ったときの値をビット単位で再現しなければ従来の作り直しにする。JFR の確保の標本は `HitTestIndex` の大きな表（1 フレーム 1 回・約 `48 KB`）を約 `760 KB` と見積もった（大きい配列ほど重みが偏る。数え上げで確かめる）。

§6-93. **実文書の確保の残りは「動く節の下の項目ごとの姿勢」で、小さな確保を削るより桁が大きい**（B12）: voidwave の `text` は 1 フレームに約 `940` 項目の姿勢（`Resolved`）を作り直す（見えている項目が大半で、画面外の省略を足しても約 1 割しか減らない）。`hover` / `text` は、姿勢の行列を途中の行列なしで掛ける・クリップの部品と角の半径を同じフレームで共有する・層の記録・作業用の一覧・`computeRetainRanges` の表を使い回すことで `100 KB` を切った。`scroll` は中身の節が動くので見えている全項目の姿勢が変わり、`Resolved` と平行移動の行列だけで約 `87 KB` 残る。`Resolved` の実体の同一性は「変わっていない」の印（子・クリップの覚え・GPU の再生）なので、使い回すには値が同じであることが要り、値の同じ姿勢を前回の実体で返す試みは `-1 KB` にとどまった（祖先の不透明度・変形が動くと値も変わる）。

§6-94. **HotSpot は 8,000 バイトを超えるメソッドを JIT しない**（`DontCompileHugeMethods`。A8、2026-11-18）: `DisplayListBuilder.patchChildSlicesUnprofiled` は 1 メソッド約 1,000 行・バイトコード `11,940` B で、子の挿入・削除のパッチは常にインタプリタで動いていた。手順ごとに分けると（最大 `1,594` B）large-list `child` mean `0.33 → 0.28 ms`・p99 `0.89 → 0.64 ms`（確保は不変）。他の神クラスで 2,500 B を超えるメソッドは `planDamage` `3,674`・`drawBoxSelfUncached` `2,847`・`match` `2,632` で、上限内。

§6-95. 〔不具合〕**ヒットテストは描画と別の時計でアニメーションを評価していた**（T2、2026-11-18）: 描画は `frame(now)` の時刻、`HitTestIndex` は `System.currentTimeMillis()`。ホストのフレームの時刻が壁時計と違う（ゲームの tick・テストの固定の時刻）と、動いている要素の見えている位置とクリックの当たる位置がずれ、同じ入力でも実行ごとに結果が変わった（voidwave で、表示リストと画素は同じなのにヒットテストだけが実行ごとに違った）。索引に対話状態のアニメーション時刻を渡す（`HitTestFrameClockTest`）。

§6-96. 〔不具合〕**合成済みのクリップの矩形は「表示リストを作ったときの位置」**（T2）: 動く節（スクロールの中身）の下で入れ子のクリップを持つ項目は、合成矩形を画面の点と比べると見えている位置からずれ、文字ではなく親の箱に当たった。描画側（`ClipResolver`）と同じく、部品ごとに持ち主の今の姿勢へ戻して比べる。確保を増やさないため、動かない持ち主の部品は戻さず、動く持ち主は 1 回の問い合わせの中で祖先の計算を共有する（最初の版はスクロール 1 フレーム `10.5 KB`。`ScrollAllocationTest` の上限 `1.8 KB` に掛かった）。

§6-97. 〔不具合〕**連鎖の無い `ClipRect` はフレームの空間に固定される**（T2）: `ClipRect.fromArray` で作った矩形は持ち主を持たないので、スクロールの節が動いても付いていかない。部品のクリップは `PaintSupport.intersectClip`（連鎖に持ち主つきの矩形を足す）で作る。凡例つきの fieldset の枠線（凡例を避ける 3 つのクリップ）がこれで消えていた。代表文書の画素ゴールデンは入れ子のスクロールをしておらず、全体の作り直しとの比較で初めて見えた。

§6-98. **代表文書の回帰ゲートは B12 の近道を見ていなかった**（T2）: `STACKING_PATCH_ENABLED`・`STICKY_IN_SCROLL_NODE` を切っても 22 行はどれも赤にならない（スタッキングコンテキストが変わるホバー・sticky を含むスクロールは実文書にしか無い）。近道を足したら、それが効く文書をゲートに載せる。また、表示項目の座標は親相対なので画面の点には `DisplayItem.resolveAbsolute` が要り、`interactiveState` の項目の先頭は `html`・`body`（`:hover` の規則を持つ祖先）なので、ホバーの手順の対象には `button`・`a`・`.btn` を先に選ぶ（最初の版はホバーが何も変えず、空の検証になっていた）。

§6-99. **`shortcutMatrix` の `default (slow: -Xint)` の不一致（cards の種 3・手 39）は負荷に依る**（T2、2026-11-18）: `scripts/ci-linux.ps1` の全構成（子 JVM を 3 本並列）では 3 回続けて出たが、`-Pmatrix.only=slow`（3 構成だけ）では HEAD・作業ツリーとも 2 回ずつ一致した。今回の変更（ヒットテストの時計をフレームの時刻へ）では消えなかったので、ヒットテスト以外にまだ壁時計に依る経路がある。`build.yml` には入っていない。

§6-100. **「手元の Linux で緑」は「CI で緑」ではない: 書体が違う**（A1、2026-11-18）: WSL の Ubuntu 24 と GitHub の ubuntu-latest（Ubuntu 26。絵文字書体あり）で、OS の書体を使う SOFTWARE の画素が全フレーム違い、`build.yml` は 1 日以上赤のままだった（`ci-linux.ps1` は緑）。書体に依る画素の比較は、記録した書体の環境でだけ行う。push の後は CI の結果を必ず見る（認証なしでも `check-runs/<job>/annotations` で失敗したテストが読める）。

§6-101. 〔不具合〕**Vulkan の記述子の番号は Java 側の宣言から作るので、GLSL の binding と食い違うと黙って壊れる**（T2b、2026-11-19）: fx と tex は GLSL ではサンプラーが binding 1〜、Java 側（`ShaderProgram.firstTextureUnit`）は 0〜で、定数ブロックと同じ binding 0 になっていた。GL は `glUniform1i` で結び直すので動き、実機の Vulkan も落ちずに違う絵を出すだけだった（BLOOM だけは以前に同じ理由で 1 始まりに直してあった）。検証レイヤーを有効にして初めて見えた → 全シェーダーの一致をテストで固定（`ShaderBindingConsistencyTest`）。

§6-102. 〔不具合〕**GPU の層の行の向きは API ごとに違う**（T2b）: GL のフレームバッファは下から上、Vulkan は上から下。テクスチャを貼る所は `bottomUpFramebuffer` で分けるのが約束で（`drawImage`・`drawScene3D`）、保持したスクロールの層の合成だけが GL の向きを決め打ちしていた。

§6-103. 〔不具合〕**近似の経路は、正確な経路と端の扱いまで揃える**（T2b）: CPU のぼかしはガウス（σ < 12）が「描画先の外は端の画素」、ボックス 3 回の近似（σ ≥ 12）が「外は透明」で、大きなぼかしだけ画面の端が薄くなっていた。走る和でも、寄せた位置の値を足し引きすれば窓の和はそのまま正しい。

§6-104. 〔不具合〕**「動いている」は項目自身ではなく姿勢の鎖で決まる**（T2b）: 差分パッチの「前フレームで動いていた項目」は自分の補間が動いている項目だけで、回る節の下の背景の部品は自分では止まっているので漏れた。CPU は差分パッチを使わないので正しく、GPU だけが止まって見えた（表示リストの変わるフレームでは全体を走査するので直り、気付きにくい）。

§6-105. **共有ランナーの CI の合否は、時間に頼らない確認だけにする**（A1、2026-11-19）: T2b の push で、変更と関係の無い `LayoutTaskPoolCallerParticipationTest` が 2 コアの CI で落ちた（「ワーカーも仕事を取る」を、たまたまワーカーが動けたかで見ていた。呼び出し側がワーカーの加わるまで待つ形に直した）。同じ型の、実時間で合否を決める assert が毎回の CI に 6 件残っていた（`ScrollCostTest` 3・`ContentVisibilityVirtualListTest`・`LayerFxBlurTest`・`Raycaster3DTest`）→ `@Tag("timing")` を付けて `build.yml`・`full-tests.yml` では `-Ptest.excludeTags=timing` で外し、開発機（`scripts/ci-linux.ps1` を含む）では流す。正しさの確認が同じメソッドにあるもの（10 万行の仮想化）は、時間の assert だけを別のメソッドに分けて CI に残した。`TimingTagPolicyTest` が「経過時間の変数を大小比較する assert は `timing` のテストの中だけ」「CI でテストを流す行はどれも除外を渡す」を見張る。速さの回帰の合否は確保量と経路の回数の `costGate`、時間の推移は夜間の JMH（合否に使わない）。

§6-106. **値が同じ姿勢は、実体も親と同じでよい**（B12、2026-11-19）: §6-93 で「`Resolved` の実体の同一性は『変わっていない』の印なので、使い回すには値が同じであることが要る」とした。自分の変形・不透明度が恒等の葉の項目は、`build` の結果が親の姿勢・不透明度・グループと同じ値になる（違うのは補間値 `anim` だけ）。補間値を項目ごとに引けば（`animAt`。補間値は状態ごとの 1 つの実体を書き換えるので、以前から姿勢の同一性は色の変化を表していなかった）、親の実体を返しても「同一なら値も同じ」が保たれ、中身の節が動くと葉の姿勢も一緒に変わる。同一性を比べる側（`spanRes`・クリップの持ち主・差分パッチの節）はどれも項目ごとに前回と比べるだけで、実体が項目の間で共有されても困らない。実文書の葉の大半は恒等なので、作る姿勢は 1 フレーム `1,997 → 642`（残りは節と、変形・不透明度の動く項目）。確保量は機械と JIT で揺れるので、テストは作った姿勢の数（`PaintSceneDriver.posesBuilt`）で見る。新しい切り替えを足すとスイッチ・static 欄・public static 欄の 3 つのラチェットに掛かるので、同じ系統の既存の `reuseIdlePoses` に入れた。

§6-107. **巨大ファイルを分けるときは、欄を広げずに欄ごと移す**（A8、2026-11-19）: インスタンスのメソッドを協力クラスへ出すと、本体の private な欄への参照が大量に出る（`GpuRenderBackend` の効果の段では約 60 個）。全部を package-private に広げると本体の閉じ方が崩れるので、「本体には宣言しか残らず、協力クラスだけが使う欄・メソッド」は協力クラスへ移した（効果の uniform の欄 45 個・画面への切り詰め・`clampTo` ほか）。内部クラス（非 static）は暗黙の外側参照を持つので、最上位へ出すときは持ち主の欄を 1 つ足す（大きさは同じ）。公開 API の入れ子の型を最上位へ出すと利用側の `Outer.Name` が全部変わるので、行数の稼ぎが同じなら、公開されていない型・静的な走査を先に動かす。A8 の「3,000 行超 0」は `LayoutBox` だけが残り、その中身（測定の記憶・仮の測定・配置の記憶）が A7 の切り出しの対象そのものなので、A7 の合否線（2,000 行以下）へ寄せた。

§6-108. 〔不具合〕**子パッチは後続の箱の位置と祖先の高さしか追わない。幅や兄弟の寸法が変わる子の増減は、古い寸法の項目を残す**（T3、2026-11-19）: 中身で幅の決まる flex アイテムの子を消すと、アイテムの幅が `40 → 10` になるのに、背景・枠線は 40 のままだった（差分ファズ nested の種 17・14・15）。レイアウトは済んでいるので、表示リストに触る前に、祖先の幅と、ブロックの流れ以外（flex / grid / 表）の中の兄弟の寸法を記憶と比べ、変わっていれば全体の作り直しへ退く（`BoxMemo.sizesFollowable`。`ChildSlicePatch` の行数のラチェットを増やさないため記憶の側に置いた）。A6b の種 1〜8 では起きない形だった。

§6-109. 〔不具合〕**差分ファズの初回（種 9〜32）で残した既知の 4 本は、4 つの別々の不具合だった**（T3、2026-11-19 に直した）: ①子パッチの挿入位置（ブロックの層の末尾・インラインの層の末尾）が、前の兄弟の中の float・位置指定・スタッキングコンテキストの箱を数えていた。それらは後ろの層（float の層・位置指定の層）で描くので、足したブロックの項目が float の後ろに入った（種 17。`collectBlockPhaseBoxes` / `collectInlinePhaseBoxes` と兄弟そのものの判定で除く。§6-81 の flex / grid と同じ形）②`display: flex` の箱を普通のインラインへ変えても、中の文字の箱が flex の項目だったころの配置の記録（`lastLayoutX` など）を持ち続け、相対配置の祖先の下で全体を置き直すと「layout で置かれた箱」と見なされて断片が作られなかった（種 23・29。全面の方が誤りで、文字が画面外に出た。`display` が変わったら子の配置の記録を捨てる）③子パッチの後続の平行移動（⑦）は記憶の原点との差で動かすので、記憶を持たない箱（表）が動くと古い位置に残った（種 23 の手 19。表示リストに触る前に、変更点より後ろで項目を持つ記憶の無い箱があれば全体の作り直しへ退く）④表の中（`display: flex` の要素を包む匿名のセルの下など）の寸法だけの変化を、局所の測り直し（in-place relayout）で済ませ、表の幅が `79` / 全面 `75` になった（種 18。表の箱そのものだけでなく、表の中の箱の寸法の変化も構造の変化として扱う〔`isInTableScope`〕）。直した後に種 1〜100（300 本）を回すと、別の 4 本（layouts 33・38・49、nested 64。1.3%）が見つかり、未調査のまま「既知:」で残した（初回の種 9〜32 は 10%）。

§6-110. **回帰カタログを作ると「GPU のテストだけが守っている不具合」が見える**（T3）: §6-104（回る節の下の部品が差分パッチへ渡らない）・§6-101・§6-102 は守るテストが GPU を要し、CI（GPU なし）では中断する。§6-104 は走査の入力（`tryPatchItems` へ渡る添字）を CPU で調べられたので `MovingChainPatchTest` を足した（修正を戻すと赤）。§6-101 は `ShaderBindingConsistencyTest` が CPU で回る。§6-102 は今も GPU のテストだけ。

§6-111. **`costGate` の `machine_ui scroll cpu` は WSL で実行ごとに ±10% 揺れる**（T3 の確認中、2026-11-19）: 変更前の `HEAD` だけを回しても `36,876 → 33,272`（-9.8%）で赤、別の回は緑。行ごとの許容 `allocTol` を `7 → 12`（CI の既定と同じ）にした。経路の回数は完全一致のまま。

§6-112. 〔不具合〕**「既知:」で残した差分ファズの 4 本は、子パッチの追跡漏れ 3 つとレイアウトの記憶 1 つだった**（T3、2026-11-19）: ①子パッチが寸法を合わせる祖先が float か相対位置（流れの中にいて、後ろの兄弟より後の層で描く箱）で、その高さが変わると、⑦は描画順で後ろの箱しか動かさないので先の層で描いた後ろの兄弟が古い位置に残り（layouts の種 38）、float なら後ろの段落の折り返しも変わる（種 33）→ 記憶の高さと違えば全体の作り直しへ退く（`BoxMemo.paintedAfterItsFlow`。絶対位置・固定は流れの外なので退かない）②body を消した html の最後の子を消して html が高さ 0 になると、全体の作り直しは html を描かないのに、⑧は寸法を合わせるだけで項目を残した（nested の種 64）③表の min-content は幅を決める段（`BoxMeasurer`）でセルを測る前の装飾から求めて行・行グループに記憶され、行は自分では測られないので記憶が残った。一から組むと装飾 0 の新しい箱から、増分では前回の装飾から求め、匿名のセルの中の匿名の表の幅の下限が食い違った（種 49。**全面の方が誤り**で、中の表がセルからはみ出していた）→ 表がセルを測った後に表・行・行グループの記憶を捨てる。

§6-113. 〔不具合〕**種 101〜300 で見つかった 4 本は、子パッチの 2 つとレイアウトの 1 つ**（T3、2026-11-19）: ①消す部分木の子孫に位置指定の箱があると、その項目は後ろの層にあり、消す区間（最小の開始〜最大の終わり）の間に別の位置指定の箱の項目が挟まって一緒に消えた（layouts の種 145）→ 描画順の番号が連続していなければ退く ②描かれなくなった箱（tbody を消して 0×0 になった表）は前回の `dlEnd` / `dlOrder` を持ち続け、子パッチがそれを挿入位置にした（種 137、nested の種 131）→ 番号が今の描画順の一覧でその箱を指すことまで確かめる（`ChildSlicePatch.drawn`）③列方向の flex の stretch が、伸ばす幅をそのまま枠線ボックスにしていたので、容器が狭いと padding と border より細い幅 0 になり、測り直す箱の組み合わせで 0 / 10 が入れ替わった（nested の種 195。内容の寸法は負にならないので装飾の幅で止める）。種 1〜500 の残りは nested 339 の 1 本で、縮小幅の body の中で flex-basis の % を変えた後の内在幅がレイアウトの段で食い違う（表示リストの不具合ではない）。

§6-114. **公開 API だけではイベントを作れず、利用者が書いた `Event` は伝播を止められない**（T4 の契約のテストで分かった、2026-11-19）: `api.event` は `Event`・`AbortSignal.Controller` を interface で出すだけで、作る口（`new Event(type, init)`・`new AbortController()` に当たるもの）が無い。利用者が `Event` を自分で実装して `dispatchEvent` に渡すと配送はされる（capture → 対象 → bubble・once・中止・`preventDefault` の戻り値は効く）が、`stopPropagation` / `stopImmediatePropagation` と `getEventPhase` / `getCurrentTarget` / `composedPath` はエンジン内部の `CoreEvent` にしか効かない。リスナーの種類の照合は大文字小文字を区別しない（WHATWG DOM は区別する）。どれも公開 API を足す・変える話なので、A9（SPI を `api` へ）と同時に決める（D1 の前）。契約のテストは仕様どおりのふるまいだけを固定し、この 3 点は固定していない。 **決定（A9、2026-11-20）**: 作る口は `EnderEngine.createEvent(type, bubbles, cancelable)`・`createAbortController()`（`EnginePlatform` の SPI 経由）で足した。自前の `Event` 実装の `stopPropagation` などは効かないまま（Javadoc に明記し、エンジンが作ったイベントを使うよう案内）。種類の大文字小文字は区別しないまま（この版の仕様として Javadoc に明記）。

§6-115. **`costGate` は CI で 1 回だけ、確保が基準の `-77%` に落ちた**（2026-11-19、`c330053`）: `varied-list-500 child` が `162,940 → 37,049 B`（経路の回数は全部一致）。同じコードの次の push（`f7c17a6`）では緑で、Windows・WSL・`-XX:ActiveProcessorCount=2`・`-XX:+UseSerialGC` のどれでも `162.9 KB ±0.1%` で再現しない。減る向きの外れなので、JIT の脱出解析が計測の窓の中で効いた揺れと見る（根拠は無い）。退行の検出には増える向きだけが要るので、同じ行で再び出たら、確保の下限を緩める（片側の許容）を検討する。

§6-116. **テストの所要時間は 3 つのクラスと直列の実行で決まっていた。`costGate` を並列にすると確保量だけずれる**（テストの体制の整理、2026-11-19）: `:core:test` は 1 本の JVM で 406 クラスを直列に流して 314 秒、上位 3 クラス（`WidthOnlyReuseTest`・`SoftwarePaintCostTest`・`RealDocumentFramesTest`）で 53%。クラスを 4 本の JVM へ振り分けて 161 秒（6 本でも同じ。各テストが内部でワーカーを立てるので CPU を取り合う）、種を多数回す 2 メソッドに `@Tag("slow")` を付けて外した `quickTest` は 119 秒（ルートの `test` は 67 → 43 秒）。`costGate` を子 JVM 4 本で並列にすると 205 → 78 秒で経路の回数は全行一致するが、確保量は小さい行で最大 `-20%` ずれて 1 回赤になった（同時に走る JVM で JIT の進みが変わると見る）ので、既定は直列のまま、並列は `-Pcostgate.parallel=4` の途中確認だけに使う。

§6-117. 〔不具合〕**差分ファズの「既知:」nested 339 は、内在幅で寸法が決まる祖先を越えた cutoff だった**（T3、2026-11-19）: flex の html の中の縮小幅の body で、孫の flex-basis を % にすると（不定の基準なので内容の幅に落ちる）body の内在幅が 212 → 224 に変わるが、子の行 n1 は body の幅で測り直しても外寸が同じなので `isCutoffEligible` が吸収し、body の寸法が古いまま残った。**全面の再レイアウト（`RELAYOUT`）も同じ測定の記憶を使い回して古い値になる**ので、増分と全面を比べるだけでは見つからない形があった（339 は全面の側だけが別の経路で測り直されて見つかった）。直し方: 親から上へ再レイアウト境界・幅が px の箱まで、内在幅で寸法が決まる箱（中身に依る flex アイテム・grid / 表の中・上書きの無い縮小幅）があれば cutoff しない（`LayoutBox.contentSizedAncestor`）。`costGate` 56 シナリオの経路の回数は不変。固定のテストは最初から作った面と比べる。

§6-118. 〔不具合〕**差分ファズの比べる相手を「測定の記憶を捨てた全面」にしたら、配置の記憶の不具合が 2 つ見つかった**（T3、2026-11-19）: §6-117 で、全面の再レイアウトも記憶を使い回して増分と同じ古い値を返す形が分かったので、`MutationFuzz` の全面は `LayoutBox.invalidateAllMeasurements()` で測定の世代を進めてから組む。種 1〜500 で新たに 17 本（1.1%）ずれ、全部が `KEEP_PLACEMENTS` を切ると一致した: ①`resetPlacements` / `resetPlacementsFast` は配置を捨てる箱の位置を 0 にするが、`keepRun >= 0` の経路では配置を有効のまま残し、その時点で使い回せなかった箱が後の layout で「同じ原点」と見なされて 0 のまま残った（13 本。兄弟の float が消えるなどで使い回しの条件が後から揃う）→ 捨てた箱の `placementValid` を下ろす。原点の記録（`lastLayoutX`）を消す直し方は、その箱が自分の原点が変わったと見て子の配置を全部捨てるので、`costGate` の `large-list text` の `layoutsSkipped` が `598,800 → 0` になった（外れた直し方） ②相対位置のずらし（`applyRelativeOffset`）は子孫の位置だけを動かして記録を動かさず、祖先が相対位置でなくなった次の layout で平行移動（`shiftSubtree`）が古いずらしを保った（2 本）→ ずらした子孫の記録を消す。`costGate` 56 シナリオの経路の回数は不変。残る 2 本（nested 324・481）は、インラインの中のブロックの匿名の箱が記憶を捨てた全面の側で 0,0 に残る形で、「既知:」にした。種 501〜1500 では別の形が 13 本（0.43%。表示リストの項目の並び・種類の食い違い＝子パッチの形）出て、これは旧い比較でも同じく出るので、範囲の外にあっただけの既存の不具合。「既知:」で表に載せた。

§6-119. 〔不具合〕**「既知:」の 15 本は、配置の記憶 1 つと子パッチ 6 つだった。子パッチの挿入位置は「木の順」ではなく「描画の層と描画順」で決める**（T3、2026-11-19）: 近道を 1 つずつ切ると、6 本は `KEEP_PLACEMENTS`、9 本は `CHILD_PATCH` で一致した。①float の span の中の flex の子の文字は flex の項目として layout で置かれ「配置を使い回せる」印が残る。span がインラインに戻ると文字は行の流れの中身になるのに、断片を消す走査（`clearFragmentsRecursively`）がその印を見て飛ばし、遅延消去の印が解けずに行が足した断片が `finishResetPlacements` で捨てられ 0,0 に描かれた（layouts の種 1149 ほか 6 本。**全面の側が誤り**）→ 行の流れの中の子は印に依らず消す（印で飛ばすのはアトミックな箱の中身だけ）②インラインの層の手がかりが親の子に無いとき、ブロックの層の直後に入れていたが、描画は「ブロック → float → インライン」なので float の前に入った（種 1048 ほか 4 本）→ 祖先の兄弟まで手がかりをたどり、閉じた層（float・位置指定・スタッキングコンテキスト・アトミック）に当たったら全体を作り直す。ブロックの層も同じ形で、描かれない親（`height:0`）ではリストの先頭 0 に入っていた ③子パッチの平行移動は「箱が動いていない」と飛ばすが、絶対位置の箱は親の節が動いても動かず、親相対の座標だけが古くなる（種 1005）④インラインの箱（span）の中のブロックはブロックの層で描かれる（`PaintOrder` は箱ごとに自分の display で層を決める）のに、挿入位置の探索が span の中へ降りなかった（nested の種 1295）⑤アトミックな箱の中は「ブロック → インライン」の順に描くので、木の順の最後（中のブロック）は描画順の最後ではない（nested の種 811 ほか 3 本）→ 手がかりは描画順（`dlOrder`）の最大・最小で選ぶ ⑥描かれていなかった祖先（float しか持たず高さ 0）が子を足して寸法を持つと全体の作り直しは祖先も描く（§6-112 の「消える祖先」の逆）→ 退く。①〜⑥と②のブロックの層の 7 つの直しは、どれも外すと対応する固定のテスト（`IncrementalVsFullTest`）が赤になる。①は断片を消す側だけを流れの中へ広げ、切り詰める側（`finishFragmentsRecursively`）は従来どおり印の箱を飛ばす（流れの中まで切り詰めると、配置を残された箱の断片を毎フレーム作り直し、`costGate` の `voidwave.html text` の確保が `+12%` になった。経路の回数は変わらず、確保の行だけが見つけた）。挿入位置の決め方は `ChildSlicePatch` から `ChildPatchInsertion` へ切り出した（行数のラチェット 941 行を超えたため。`ChildSlicePatch` は 763 行で表から外れた）。

§6-120. **記憶オブジェクトを切り出しても、ホットパスの一時退避（stash）や全箱常時確保まで不変レコード・別オブジェクトにすると確保量・保持ヒープが跳ねる**（A7、2026-11-19）: 仮測定の一時退避 `TentativeSlots.stash` を都度 `new MeasureOutputs` にしたところ、`item_block_test.html text` の 1 フレーム確保が `+8.9%`（JFR `ObjectAllocationSample` 上で `82.03 MB / 84 MB`）増え `costGate` が赤になった。記録済みスロット（`Tentative`/`MeasureOutputs`/`MeasureKey`）は不変レコードで §6-20 のエイリアスを型で防ぎつつ、主スロット自然出力と非公開の作業用一時退避はプリミティブ欄へ展開・インライン化し、さらに `PlacementMemo` を未配置テキスト・インライン要素では生成しない遅延確保へすることで、ヒープ確保増ゼロ・保持ヒープ微減（`varied 37.5 → 37.0 MB`・`large 9.1 → 8.8 MB`）と `PlacementMemo` 側の添字走査化による `child` シナリオ確保 `-46.0%/-51.8%` を両立した。また描画中の一時矩形差し替え（`BoxDecorationPainter`・`ControlPainter`）は `LayoutBox.withPaintBounds(..., Runnable)` に閉じ、`LayoutBox` の幾何欄のパッケージ閉鎖を崩さない形とした。

§6-121. **標準の面（`StandaloneView`）は `data-i18n` を解決していなかった**（A9、2026-11-20）: 解決は Minecraft クライアントの `ClientLocalEnvProvider` だけが呼ぶ。`Builder.i18n` を公開する際、既定の翻訳（キーを返す）で全面に適用すると本文がキーで潰れるので、翻訳を差し込んだ面だけが `data-i18n*` を解決する形にした。公開 SPI の橋渡しは `core` から `api.spi` を参照できない（層の向き）ため、橋渡しの実装を `common` に置いて `core` の SPI 名で登録した。

§6-122. **層間（L1〜L6）逆参照 0 の裏に、層内 SCC 3 系統・GPU↔CPU ピンポン・上流モデルへの状態書き戻し・静的テーブル無制限蓄積が残っていた**（2026-11-20 構造監査）: 全 481 ファイルを実シンボル参照で SCC 解析した結果、L2（`raster`↔`raster.awt`↔`text.font`）・L3（`dom`↔`css`↔`event`↔`kernel.compiler` 7 パッケージ）・L5（`paint`↔`paint.model`↔`paint.gpu`↔`paint.software` 4 パッケージ）の循環、未使用ワイルドカード `import` 122 件、`DisplayListBuilder`→`LayoutBox.{dlStart,dlEnd,dlOrder,paintCulled}` の逆流書き込み、`ValueInterpolator` の異単位 `calc()` 生成による `PackedLengths` の無制限登録、`:core` 内の Minecraft 専用 `client.template` 残置が判明。§3 に S4〜S8 を追加して段階解体する。

§6-123. **増分の不具合の多くは「パッチの退く条件の漏れ」で、条件は不具合が出るたびに 1 つずつ増えていた**（2026-11-20、C 系の根拠）: 回帰カタログ 76 件のうち増分と全面の食い違いは約 26 件で、前回の出力を書き換えるパッチ（子パッチ・平行移動・配置の記憶・局所パッチ）由来が約 15 件、記憶の鍵の漏れ（エイリアス・免除の判定・再生の条件）由来が約 11 件（件数は本文の分類による概数）。前者は `BoxMemo.sizesFollowable`・`paintedAfterItsFlow`・`ChildPatchInsertion.drawn`・`contentSizedAncestor` のように差分ファズが見つけるたびに退く条件を足す形で、上限が無い（§6-108〜119）。後者は記憶が当たった時点で計算し直して比べれば漏れた鍵がその場で分かる（`VERIFY_FLOATS` で実証済み、§6-88）。そこで増分を「全面と同じ関数＋記憶」に揃え、正しさの論点を鍵の網羅 1 つに絞る（§1.2 原則 2・§3.3）。`StackingSubtreePatch`（全面と同じ手順で部分木を組み直す）は回帰カタログに不具合の行が無く、この方針の小さな先例。

§6-124. **検証モードで「記憶の出力」と「作り直し」を比べるには、比べる側の近似を先に正規化する**（2026-11-20 C0 ②）。測定の記憶は、部分木の箱の出力と測定の記憶を退避して測り直し、戻せば誤検出なく全件緑〔許容は 1 ULP の揺れ用に 0.01px。`sizingMode` は親が決める入力なので比べない〕。姿勢は `Group.anchor`（リスト位置）を比べない。外れた仮説: `BoxMemo` の項目をそのまま比べる検証は、`PaintCell`（スクロール・補間でその場で書き換わる値）・`RenderMeta` のクリップ／原点（親相対で意図的に近似される）・合成 ID の違いで 19 件が誤検出になり外した。BoxMemo・項目の再生の検証は「パラメータ化された値を除いた正規形」を先に定義してから入れる。

§6-125. **〔不具合〕箱の項目の検証（`VERIFY_BOX_MEMO`）が実不具合を 2 つ見つけた**（2026-11-21 C0 ②）。①差分パッチ（`patchSortedSlices`）が、箱の記憶として保存した項目リストへスクロール内容の節を後から足し、次の通常の組み立てで同じ id が 2 つ並んでいた（画素が同じなので画素比較のテストでは見えなかった。§6-20 型のエイリアス）。②ノードの鍵が変わった箱で、古い id・鍵の記憶が当たっていた（`BoxMemo.nodeKey` で照合）。検証の正規形は `BoxMemoVerifier` に書いた〔`cell`・`hints.layer`・クリップの位置を除き、浮動小数は 0.001 以内〕。実文書（`RealDocumentFramesTest`）で出た失敗は、検証の再描画の副作用ではなく、テストの実時間依存だった（§6-126）

§6-126. **実文書の `resize700` の比較は、実時間の間引きに依って偶然に通っていた**（2026-11-21 C0 ②）。幅の変更はホストの経路で「直前のレイアウトにかかった時間の 2 倍」が過ぎるまで反映されない（`UiView.resizeDue`）。`RealDocumentFramesTest` は 6 フレーム（各 40ms）だけ描いて記録していたが、full 側は毎フレーム再レイアウトを強制するので間引きが解けず、9 本中 7 本でリサイズが一度も反映されていなかった（`rootW` が 800 のまま。反映された incr 側と食い違うかは、レイアウトの速さ次第）。検証モードで描画が遅くなると食い違いが表に出て、「検証が状態を乱す」ように見えた（外れた仮説。描画を一切せずに待つだけでも再現した）。待つ間は強制の再レイアウトをせず、`rootW` が反映されるまで描き（最大 200 回）、仮想時刻は進めない形に直した（時刻を進めるとアニメーションの位相が実行の速さで変わる）。

§6-127. **検証モードの費用の大半は「記憶で飛ばせた箱の描き直し」で、比べる処理の作りで 3 倍変わる**（2026-11-21 C0 ②）。`VERIFY_BOX_MEMO` を入れた全件 `:core:test` は 14 分 46 秒（検証なしは約 3 分 50 秒）。ランダム変更を大量に回す `ParallelMutationStressTest`（3 件で 589 秒）・`TentativeMeasureReuseTest`（217 秒）が支配的で、2 件に絞った切り分けでは `VERIFY_BOX_MEMO` だけで 12 秒 → 189 秒、`VERIFY_MEMO`（測定・姿勢・再生）は約 1 秒だった（`-Pverify.memo=false` / `-Pverify.boxmemo=false` で個別に切れる）。比較が項目ごとに反射でパス文字列を作っていたので、同じ場合は文字列を作らない比較だけで済ませる形（記録クラスごとの読み口を覚える）にして 189 秒 → 62 秒、全件 5 分 29 秒。残る費用は描き直し自体。さらに下げるなら、検証する箱を間引く（N 回に 1 回）が次の手だが、間引くと `NoHtmlSiteTest` のような「検証が当たることに頼る」テストが運に左右されるので、今は全件の検証を保っている。

§6-128. **鍵漏れの再注入で見えた、検証モードが捕まえる型・捕まえない型**（2026-11-21 C0 ②。修正を一時的に外し、同じテストを検証オン・オフで回して差を見た）。①**捕まえる**: 測定の記憶の鍵（`canReuseMeasurement` の上書き寸法）を外すと、検証オフで赤 14 件・オンで 21 件（検証だけが捕まえたのが `FlexItemCutoffTest`・`IncrementalVsFullTest`・`WidthOnlyReuseTest` など 7 件）。箱の項目の鍵（`match` のスタイル比較）を外すと、オフ 4 件・オン 8 件（オンだけ 4 件）。②**検証が無くても既存の比較テストが捕まえる**: §6-27 ①（`idleAt` を外す → `RealDocumentFramesTest.gpuMatchesSoftwareEveryFrame` が赤。検証のメッセージも 1 件出る）・§6-27 ③（再生前の `flushOverlay` を外す → `ItemReplayPixelTest`。再生の順序づけは検証が再生を止めて描き直すので見えない。これらのテストは検証を切って再生そのものを回している）。③**検証では捕まえない**: §6-117（内在幅の祖先を越える cutoff を許す → 種 339 が赤になるが、検証オンでも比較テストの食い違いとして出る。原因は無効化の取りこぼしで、古い値を持つ箱が測り直しの入口に来ないので、「記憶が当たった所を測り直す」検証は発火しない。全面との比較かファズ〔③〕でしか見えない）。④**再現できない**: §6-20 のエイリアスは記録を不変の `record` にしたので型で起きない（`MeasureMemoTest`）。§6-27 ②（クリップ slot の中身の照合を外す）は、検証でも既存テストでも赤にならない（文書に状況を再現できていない。再生の検証〔`spanVerifyArm`〕は出力の影を比べるので、状況さえ作れれば捕まえる設計）。

§6-129. **差分ファズに検証を併走させると、鍵の漏れと食い違いを分けて報告できる。その代わり、検証の修正自体の確保量を `costGate` で確かめる**（2026-11-21 C0 ③）。`mutationFuzz`（`JavaExec`）は検証のシステムプロパティが付かず検証なしで回っていたので、既定で有効にした（`-Pverify.memo=false` / `-Pverify.boxmemo=false` で切れる）。上書き寸法の鍵を外した状態で種 1〜30（90 回）を回すと、検証オンは 60 件を全部 `KEYLEAK 鍵の漏れ[測定の記憶]`（箱 `f1a`・手 0）で報告し、オフは 58 件を食い違いとしてだけ報告した。§6-117 の型は検証オンでも `MISMATCH nested/339`（比較側）で、報告の分かれ方は §6-128 のとおり。**注意**: §6-125 ①の直し（記憶のリストを複製して節と分ける）は `costGate` の `showcase inherited` を `+7.5%`（許容 ±5%）にした（差分パッチのたびにリストを作り直し、`CaptureList` の再利用も止めたため）。節を足した後にリストの末尾から取り除く形にして `+2.5%` に戻した（確保なし）。

§6-130. **`RenderPipeline` の static な覚えは、面の間で漏れない純粋な覚えだった**（2026-11-21 A13 第 4 段の調査）。`rootStyleCache`（ビューポートの大きさ → 読み取り専用の写し）と `StyleScope.lastInitial`/`lastEntered`（ビューポート・ルートの文字の大きさ → 不変の値）は、鍵が値そのもので結果は鍵だけで決まるので、別の面が同じ鍵で当てても同じ値を返す（1 スロットの競合は最悪でも作り直しで、取り違えない）。面ごとの文脈へ移す対象ではない（計画の A13 の記述から外した）。**面ごとの文脈が要るのは「ホストが差し込む値」だけ**で、構文の追加・タグマクロは差し込みなので対象だった。テストは「前提（2 面の絵が違う）」を置く: 面の差し込みを global から引くように壊すと、2 面が同じ絵になって前提のテストが赤になる（交互・同時の比較は、単独の描画も同じ壊れ方をするので一致してしまう）。

§6-131. **〔不具合〕並列のワーカーは、呼び出したスレッドの面の文脈を引き継いでいなかった**（A13、2026-11-21）: `LayoutTaskPool` の呼び出しのうち、文脈を包む `scopeRunner` を渡していたのは再スタイルだけで、ブロックの測定・配置の並列・描画の準備・ソフトウェア描画の行/タイルなどは `null` を渡していた。ワーカーは global の文脈を見るので、面にだけ登録した置換要素・書体・解析の上限を取り違え、32 件以上の兄弟を持つ文書で並列と直列の結果がずれた（`WorkerContextPropagationTest`。面ごとの置換要素を登録した 96 行の文書）。直し方は呼び出し側に包みを足すのではなく、`LayoutTaskPool.runShared` が呼び出したスレッドの文脈を捕まえる（`EngineContext` が `bindScopeCapture` で結ぶ。L4 の `layout` は L6 の `EngineContext` を知らないので、口を結ぶ形）。これで呼び出し側が包みを渡し忘れる型が構造上起きない。調べた範囲の他の非同期スレッド（HTTP 画像・書体の取得・プリウォーム・動画の初期化）は文脈の値を読まない。

§6-132. **`Shortcut` は面ごとにしない**（A13、2026-11-21 の設計判断）: `Shortcut` は「結果を変えてはならない」近道・切り分け・検証の切り替え（`shortcutMatrix`・差分ファズが見張る）で、面ごとに値を変える動機が無い（面ごとに変えて結果が違うなら、それは近道ではなく不具合）。計画に書いていた動機の「JUnit のスレッド並列化」は、今はテストがフォーク単位（`maxParallelForks`）で並列に回っていて足りている。面ごと化は `.on()` のたびにスレッドローカルを引く費用（ホットパスの読み）を払うので、割に合わない。**見直す条件**: JUnit のスレッド並列（同一 JVM）が要るとき（その場合は `Shortcut` を触るテストへ資源の排他を付ける方が安い）。振る舞いを面ごとに変える設定は、すでに `ContextFlags` が持つ。

§6-133. **未使用ワイルドカード import が、パッケージ循環の見かけの原因の半分だった**（S5、2026-11-21）: パッケージ単位の SCC を測ると、計画に無かった小さな循環が 2 つ出た（`text`↔`text.model`・`layout`↔`layout.model`）。原因は生成されたままの自パッケージ・親パッケージへのワイルドカード import（`core.text.*` を `core.text.model` が持つ）で、型は 1 つも使っていなかった。171 件を消すだけで両方が解消した（計画は 122 件と数えていたが、同じパッケージの import を数えていなかった）。**循環の測り方は「import のパッケージ」ではなく「使っている型のパッケージ」でなければ、このように見かけの循環が混ざる**（ここでは import を見る検査器のまま、未使用の import を先に消した）。残る 3 つの塊は、`api.dom`↔`api.event`（`EventTarget`↔`Event`。DOM 仕様どおりの相互参照で、公開 API の形なので凍結）・L3 の 7 パッケージ（S8）・L5 の 4 パッケージ（S6）。**`raster`↔`text.font` の切り方**: 向きは両方向にあった（`raster` の `CanvasRenderingContext2D` が書体の型を使い、`text.font` の COLR v1 描画が `CanvasRenderingContext2D` を使う）。ラスタの基本部品〔`Pixmap`・`Path2D`・`Matrix2D`・`Paint`〕を下、書体を中、キャンバスを上にして、キャンバスを `core.raster.canvas` へ移し、書体側が使う描画は口（`ColrV1Renderer`）を書体に置いて実装をキャンバス側から `ServiceLoader` で結んだ。可視性の変更は `Pixmap.div255`・`CanvasPattern` の一部・`Paint.shader` を public にしただけ。**`common.three` の扱い**（公開 API へ統合かデモへ降格か）は、D1 の直前に公開面を広げないため、デモへ降格した（本体からは使われておらず、デモとそのテストだけが使っていた。昇格は `api.scene3d` を決めるときに戻せる）。

§6-134. **層内の循環は、型を正しい下の階へ置くだけで大半が消え、残りは「ノードが上を呼ぶ口」の SPI で切れた**（S5 の続き、2026-11-21）。測り方: パッケージごとに、型の使用（import・完全修飾名）の数を数え、全順列で「上向きの参照」が最小になる並びを求めてから、上向きの参照だけを切った（L3 は 36 件 → 25 → 13 → 7 → 1 → 0）。**効いた順**: ①依存が下位の型だけの型を下へ移す〔`ElementTraits`・`ConstraintValidator`・`EventForwardDeclaration`・`CssKeywords`・`StyleStateAutomaton`・`HostSyntax`・`HostElementProvider`・`DomUtils` など〕②相互に参照し合う小さな部品を同じパッケージへ統合する〔`event` の 5 クラスは `api.*` と `dom.node` にしか依存せず、ノードがイベントを送る DOM の一体なので `dom.node` へ〕③上を呼ぶ口を、下に狭い SPI として置き、実装を上の層に置いて ServiceLoader で結ぶ〔ノード → セレクタ・HTML の断片解析・文書の対話状態は `NodeServices`/`DocumentState`、実装は `core.dom`。`ImageDecoder`・`ColrV1Renderer` も同じ形〕。④対話状態の算出スタイルに依る部分は、既定メソッドを持つ小さなインターフェース `StyledDocumentState` に置く〔`InteractionState` の行数が増えない〕。**ServiceLoader の設定ファイルは、インターフェースの完全修飾名で決まる**ので、SPI の型を移したらファイル名も変える（`HostElementProvider` を移して、設定ファイルの名前を直し忘れると、実装が見つからず多数のテストが落ちる）。**ルート側のラチェットは配置の前提を持つ**: `ArchitectureTest` の `core.event.StandardDefaultActions` の文字列・フォーカス状態の持ち場の検出（インターフェースのメソッド宣言を持ち場と数えない）・入れ子型の共有（同じ部品のサブパッケージ〔`paint.model` など〕を 1 つに数える）を、移動に合わせて直した。**残った `api.dom`↔`api.event`**: `EventTarget` が `Event`・リスナーの型を使い、`Event` が `EventTarget` を使う DOM 仕様どおりの相互参照で、`UIEvents` も `Element` を使う。解くには `EventTarget` を `api.event` へ・`UIEvents` を別の置き場へ動かす公開 API の署名変更が要るので、凍結して判断を待つ。。

§6-135. **ホストの Vulkan デバイスは「借りるだけ」にし、拡張・機能を要求しない**（A14、2026-11-21）: LWJGL の `VkInstance`/`VkDevice` ラッパーは有効な拡張の一覧から関数表を作るが、ホストの一覧は渡されないので、1.1 の中核関数だけを使う前提でラッパーを作った（エンジンのヘッドレス描画は元から拡張なし・機能なしで足りる）。借りたコンテキストの `close` はホストのデバイス・インスタンスを壊さず、確保したメモリ特性の解放だけを行う。`gpu(GL)` とホストの Vulkan の食い違いは、別 API へ黙って逃がすと「ホストのデバイスに描いたつもり」が崩れるので、`build` で失敗にした。キューはホストと共有なので外部同期（エンジンの描画中にホストが同じキューへ送らない）を `HostGpu` の文書に明記した。

§6-136. 〔不具合〕**公開の合否は「別プロジェクトが公開物だけで動くか」で見る**（A10、2026-11-21）: POM の検査だけでは LWJGL のネイティブの足りなさやモジュール間の依存の抜けを見落とすので、project 依存を持たない `scripts/consumer-check` を `repo/` だけを見るリポジトリで組み、クイックスタートの 4 例（窓は開かず提供者の発見まで）を実行する。`artifactId` は `base.archivesName` の後で決まるので、公開設定のスクリプトは各 `build.gradle` の末尾で読む（先に読むと `core-gl` のような名前で出る）。同時に直した不具合: `:core` の公開先 URL を `"file://${projectDir}/repo"` と書いていたため Windows のパス（`D:\…`）で「URI をファイルへ変換できない」と失敗していた（`uri(file)` へ）。

§6-137. **L5 の「GPU 試行 → 拒否分だけ CPU」の往復は、構造の循環ではなく `GpuSink` 越しの Strategy で、いま解体する費用に見合わない**（S6、2026-11-21）: 項目の走査は中立な `PaintSceneDriver`/`Painter` が既に持ち、`GpuRenderBackend`→`CpuFallback`→`GpuSink` は port の口越しの一方向の呼び出しで、パッケージの循環・実装同士の参照は 0（`PaintPipelineDirectionRatchetTest`）。残る分岐は ItemRaster/TextRaster の約 20 か所の「`GpuAttempt.x(route.current(), …)` が true なら塗らない、false なら CPU が塗る」で、CPU の命令の順序づけ（`cpuOpsOrdered`）と交互に進む。これを「中立 emitter が図形の列を作り、GPU が受けた残りを CPU へ」に替えると、図形ごとの記録（確保）と描画順の再構成が増え、`costGate`・時間 KPI・3 者画素一致の三方で退行の危険がある一方、得るのは B7（描画スレッド分離）の前提だけ。B7 は着手条件つき（§3）なので、反転は B7 に着手するとき、フレーム PDU の設計と一緒に行う。それまでは emit/port の向きのラチェットで劣化を止める。

§6-138. **C1 の目標値（2026-11-21 の計測、large-2000 `child`・null）**: パッチあり `0.924 ms`（layout.measure `0.328`・place `0.270`・display-list `0.086`・確保 `121 KB`）。`CHILD_PATCH` を切った全面経路は `1.67 ms`（display-list `0.290`・確保 `1.45 MB`）。ただし切ると子の増減がレイアウトでも全面（`BoxTreeBuilder` の作り直し）になり、確保の大半はそちら（JFR の 66%）で、表示リスト側の確保は `PaintOrder.collectChildren`/`classify` の 9% 程度。つまり C1 が満たす線は display-list を `0.29 → 0.09 ms` 付近へ戻すことで、C1 自体で `child` が速くなる見込みは無い（display-list は全体の 9%）。保守性のための置き換えで、時間は ±10% 以内を守る。`hover`・`style` は display-list がほぼ 0 で、時間の大半は `restyle-local`（C5 の領域）。

§6-139. **C1c の前提（2026-11-21 の計測）: スタイル変更のパッチ（`patchSortedSlices`）は断念されない**。showcase の `hover`・`style`・`text` は箱の差し替えが 2,100 回中 100% 命中でフォールバック 0（`cant` は `text` の理由 8 が 1 回だけ）、large-2000 でも `cant` 0。display-list は showcase `hover` `0.014 ms`・`style` `0.019 ms`。一方、パッチの無い経路（showcase `child`）の display-list は `0.170 ms` で、箱ごとの記憶に当たっても「全面と同じ組み立て」は約 10 倍かかる。つまり `patchBoxes` を全面と同じ手順の部分木の組み直しへ置き換えると、断片の連結が `0.01 ms` 台でできない限り `hover` の display-list が約 10 倍（フレーム全体 `0.04 → 0.17 ms`）になり、時間 ±10% を守れない。`cant` を消す目的なら、断念が起きない以上ほぼ効果が無い。C1c は断片の記憶（部分木の項目列の不変の断片と連結の作業域）の試作を、配線せず `paint` の中で全面とのずれ 0 を示す形で先に作り、時間を測ってから置き換える。試作が `0.02 ms` 台に届かなければ C1 は見送り（検証済・見送り、§4）にする。

§6-140. **C1 の試作の前提を計測で確認（2026-11-21、既存の `Chunk` による部分木の保持だけの全面経路で、スタイル変更のパッチを一時的に切って比較。null）**: showcase `hover` フレーム全体 `0.039 → 0.135 ms`・`style` `0.063 → 0.170 ms`、large-2000 `hover` `0.062 → 0.274 ms`・`style` `0.101 → 0.289 ms`（確保も `0.2〜9 KB → 13〜60 KB`）。つまり現状の「箱ごとの記憶＋部分木の保持」では 3〜4 倍遅く、合否線（±10%）に届かない。large-2000 は 1 つの親の下に 2,000 の子が並び、全面と同じ組み立てを走らせる限り、1 か所の変更でも兄弟 2,000 個ぶんの走査が要るため（表示リストの断片を連結するだけでも O(兄弟数)）。`0.02 ms` 台に届くには、断片を木（または縄）で持ち、変わった葉から根までの O(深さ) だけを組み直し、消費者（`PaintSceneDriver` の索引・GPU の送出）が断片の木を直接たどる必要がある。これは C2（索引・再生・損傷範囲）と同時の設計になり、`DisplayItem` の同一性に依る B9 の差分検出（`PaintCell`・`ItemDiff`）との整合も要る。C1 単独では時間 KPI を守れないので、C1 は C2 と一体の設計（断片の木）が決まるまで着手しない。それまでは `SliceWriteSiteRatchetTest` と `PatchSiteRatchetTest` でパッチの増加だけを止める。

§6-141. **C1 の F0 試作（2026-11-21）: 付録 E の描画順は「箱が親へ渡す 7 層の列〔blocks・floats・inlines・負の z・位置指定・正の z・after〕の連結」で正確に表せる**。同梱の実文書 9 本（float・不可分インライン・位置指定・z-index 付きスタッキング・レイヤー・スクロールバーを含む）で、断片の平らな列が実ビルダーの表示リストと項目の実体（`==`）まで一致。当初の想定〔外へ出る子孫を `memoizable=false` に倒す〕は誤りで、不可分インライン（flex/grid アイテム・inline-block）が箱の 45〜68% を占めるため、一般形（特別扱いなし）にした。大きな親（兄弟 2,000）の 1 つの子の差し替えは、32 分岐の永続ベクタ（2 段のブロック配列。B 木は不要）で根まで作り直して `0.0009 ms`、平らな列への展開まで `0.0026 ms`・確保 `1.4 KB`（子の配列を複製すると 9 KB）。未証明: 葉の作り方〔`drawBoxSelf` の文脈〕・変更の上向き伝播と鍵の一致検査の時間・レイヤーの錨の span・子の増減・消費側（`DESIGN_FRAGMENT_TREE.md`〔削除済み〕 §11.4）。

§6-142. **C1 の F1a（2026-11-21）: 影モードで、断片の木（付録 E の 7 層の写し）が旧経路の全面の表示リストと全テスト・ファズで一致した**。`VERIFY_FRAGMENT`（`:core:test`・`mutationFuzz` で既定オン、本番は無効）が全面の描画のたびに、旧経路の表示リストを箱ごとの切り出しから断片の木へ組み直して項目の実体（`==`）まで比べる。全件 `:core:test`（約 6 分）・`:core:quickTest` 2,786 件・ファズ 150 種 450 回で食い違い 0、実文書 9 本で見送り 0。見送るのは仮想化した並び・Top Layer・マスクだけ。未検証: パッチ後の表示リスト・断片が自分で項目を作ること・変更の伝播と鍵の一致検査の時間・レイヤーの錨の span（`DESIGN_FRAGMENT_TREE.md`〔削除済み〕 §12.1）。

§6-143. **C1 の F1b（2026-11-21）**: ①断片は前フレームと同じ実体になる〔箱ごとの記憶が項目の実体を保つので、変更なしなら新しく作る節 0・1 箱の変更で道の上だけ〕＝C2 が区間ごとの変化なしを言える前提が成り立つ ②変更の上向き伝播（親をたどり兄弟の中の位置を線形に引く）を含めても大きな親（兄弟 2,000）で更新＋展開 `0.0026 ms`、U9 は解決 ③〔知見〕パッチ後の表示リストを断片の木と比べると 3 テスト〔`FloatContainmentMemoTest`・`LocalUpdateTest$ChunkRetention`・`ParallelMutationStressTest`〕で食い違う。表示リストは全面と一致しているが、パッチ後の `dl*`〔範囲・描画順の記録〕が実際の並びと一致しない状態があり、旧経路のパッチが内部の記録を厳密には保っていない〔C1 の動機の実例〕。パッチ後の検証は葉を断片が自分で作る段で独立に行う。

§6-144. **C1 の B 案（2026-11-21）**: 全面の描画を断片の木で行う経路 `FragmentPaint`〔`Shortcut.FRAGMENT_BUILD`、既定は旧経路〕を実装し、旧経路と項目・箱の記録〔`dl*`・描画順・窓の外〕まで一致することを、実文書 9 本・並走の差分テスト〔12 種×40 手〕・全件 `:core:test -Pfragment.build=true`〔slow・timing を含む〕・差分ファズ 600 回で確かめた。〔不具合ではなく知見〕**旧経路には暗黙の状態への依存があった**: 子の親相対の原点は親の `paintCulled` を読んで決まり、旧経路は親を先に実行して印を書いてから子を実行するので成り立っていた〔`PaintOrder.effectiveParentNodeBox`〕。断片の経路で印を展開時に書くと、前フレームに省かれた親の子が原点 0〔絶対座標〕で作られて食い違った。親を描く直前に書いて解決、F2 で親を明示の引数にする〔M1〕。記憶でスキップする前の全面描画は旧経路より重い〔showcase `child` の display-list `0.154 → 0.183 ms`・確保 `197 → 306 KB/frame`〕ので、既定の切り替えは F2 の断片の記憶の後〔全面 ±10%〕。`PaintOrder.java` の行数上限を共有化の増分だけ `1463 → 1487` にした〔旧経路の削除で下げる〕。

§6-145. **C1 の F2 の実験（2026-11-21）: 断片の記憶〔子へ降りるのを飛ばす〕を足しても、全面の描画は旧経路を上回れなかった**。記憶の鍵〔親・位置・クリップ・親 id・動く祖先・親相対の原点・子の並び・スクロール量・描画の枠組み・部分木の印〕と副作用の再登録〔スクロールの節・窓の外・`BoxMemo` の世代〕・絶対配置の箱の外側クリップ依存の印付けまで入れて正しさは保てた〔差分テスト・等価テスト・既存の関連テストが緑〕が、showcase `child` の display-list は旧 `0.154` に対し `0.141〜0.219 ms`・`resizeexact` は旧 `0.328` に対し `0.473 ms`。JFR では断片の経路の 62% のうち 50% が箱ごとの描画〔`drawBoxSelfMemo` → `BoxMemo`〕で、旧経路と同じ処理。旧経路が速いのは部分木の保持・省略が箱の描画自体を飛ばすから。さらに `LayoutBox.touchPaint` が全箱で祖先へ部分木の印を伝えると、large-2000 `child` の `layout.place` が `0.270 → 0.450 ms`〔+67%〕。伝播を「前回の描画のあとに印がある祖先で止める」にして戻せたが、ラチェット〔`LayoutBox` の行数・メソッド数〕も増えるので、効果が示せない実験として撤回した。**教訓**: 断片の木の利得は「兄弟の多い親で 1 つの子だけが変わる」場面〔旧経路ではパッチが担当〕にあり、全面の描画の記憶では出ない。1 本化の前提は、記憶の当たりでなく変わった箱の道の直接の組み直しがスタイル変更・子の増減のパッチと同等以上になること〔`DESIGN_FRAGMENT_TREE.md`〔削除済み〕 §15.3〕。

§6-146. **C1 の F2（2026-11-21）: スタイル変更のパッチを断片の記録から行う `FragmentPatch` は、ほぼ旧パッチと同等にできたが、1 行〔voidwave `hover`〕が未達**。①旧パッチの `cant` 22 か所と `patchSortedSlices`・`StackingSubtreePatch` の判定を、箱ごとの記録〔`FragmentPaint.Rec`: 文脈・位置・大きさ・種類・z・レイヤー・性質〕との比較〔`sameGeometry`・`sameFacts`・`sameKind`・`sameMoving`・`animated`〕に置き換えた〔約 230 行〕。②**記憶の鎖・`LayoutBox` の印の伝播は不要**〔変更の箱は呼び出し側が渡す。§6-145 の `layout.place +67%` を避けた〕。③**消費者が平らな `List` と `dl*` で読む間、パッチごとの木の更新〔道の節の複製〕は純粋な追加費用**〔showcase `hover` 確保 `8 KB`・時間 `+13%`〕。項目だけが変わる箱では木を更新せず〔構造は変わらない〕、`+11%`・`2.6 KB` に収まった。木が利得を生むのはリストが木から導かれる形〔F3・C2〕になってから。④旧実装との差で直した食い違い: `:hover` の `transform`〔動く性質の変化〕・根の祖先のアニメーション・項目数が変わる差し替え〔全面に落とすと画面外の箱を省いてスクロールの節が減った〕。⑤未達: voidwave `hover` `0.0881 → 0.1225 ms`〔+39%〕・確保 `19.9 → 42.4 KB`〔根の部分木を `Fragment` 経由で組む確保が、旧 `executeTasks` の直接リスト出力より重い〕。全面の描画も旧より `+19〜45%`。よって旧経路は削除しない。残り: 組み直しの確保削減・子の増減の断片版・全面の描画の同等化〔`DESIGN_FRAGMENT_TREE.md`〔削除済み〕 §16.4〕。

§6-147. **方針の訂正（2026-11-21）**: §6-146 の `FragmentPatch` は撤回した。C1 の目的は「パッチ〔前回の出力の書き換え。範囲・順序の数え違いが不具合の源〕をメモ化〔全面と同じ関数＋記憶〕へ置き換え、経路を 1 本にして保守とバグを改善する」ことで、パッチの代替を作ると経路が増える〔旧パッチ・断片のパッチ・旧全面・断片の全面〕。性能が同等でも目的に逆行する。以後の原則: ①パッチを新設しない ②経路は最終的に 1 本〔旧経路・各パッチ・チャンク・prune・`executeTasks` は断片の経路が同等になった時点で削除〕 ③性能の差は保守性のための許容として扱い、許容の上限をユーザーと決める。§6-145 のメモ化の実験が「効果が示せない」とされたのは、旧パッチ・チャンク・prune と同じ時間を目標にしたため。

§6-148. **表示リストの組み立ての 1 本化（2026-11-21）**: ユーザーの方針〔パッチはバグの温床。メモ化へ移行して経路を 1 本にし、保守性を先に上げる。性能は後から〕に従い、①断片の経路を完全にした〔旧経路へ落ちていたのはマスクと仮想化だけ。厳格モードで全件テストを回して洗い出し、Top Layer・根でない起点も実装〕②旧全面・旧パッチ・チャンク・prune・`FragmentPatch`・影モードを削除。**パッチを無くすと、旧実装が隠していた差が出る**: スクロールの途中で `:hover` が変わると全面の描画になり、`StickyScrollNodeTest`〔node と rebuild〕が 52 フレーム目で 1 画素ずれる〔未解決・決定的〕。また全面描画は項目を窓付きで作るので、パッチで保たれていた窓外の項目〔large-list のスクロール 3 フレーム 1212 件〕が無くなり、ゴールデンの項目数が 472 になった〔画素・ヒットテストは同じ〕。**要素ごとの最適な経路について**: 経路を分けず、同じ契約〔箱と文脈 → 断片〕の下で種類ごとの戦略〔`Fragment.Kind`〕だけを分けるのが安全。契約が 1 つなら、後から種類別の最適化を足しても同じ検証が見張れる。

§6-149. **`StickyScrollNodeTest` の原因（2026-11-21。§6-148 の未解決の解決）**: パッチの削除で露出した既存の欠陥。スクロールの途中で `:hover` が変わると、全面の再描画が `repaintFromLayout(true)`〔平行移動の再利用つき〕で走り、`BoxMemo` の「箱が動いただけ」の経路が、sticky の貼り付きで動いた箱を古い位置の記憶から単純な平行移動として使って、節の移動の描画より sticky ヘッダが貼り付きのずれの増分〔146 px〕上に出た。旧実装はホバーをパッチで反映していたので通らなかった。暫定対処: スクロールだけの更新でない再描画は `repaintFromLayout(scrollOnly)`〔平行移動の再利用なし〕。ゴールデンは項目の内容のハッシュのみ 4 フレーム分変更〔数・画素・ヒットテストは同じ〕。恒久対処は C4〔配置を親相対の不変の結果へ。sticky を鍵に入れるか平行移動の再利用から外す〕。**教訓**: パッチは「前回の出力を使い回す経路」を増やし、全面の経路の欠陥を隠す。経路を 1 本にすると、欠陥が早く見つかる。

§6-150. **C1 の記憶（2026-11-21）**: 1 本の経路の上に断片の記憶を載せた。①鍵は書き込みの時点で必ず付く印〔`LayoutBox.touchPaint`〕＋位置・クリップ・文脈の比較で作り、呼び出し側の入力〔変わった箱の一覧〕を要らない形にした。`LayoutBox` の祖先への伝播は「前回の描画〔`noteRendered`〕以降にすでに印がある祖先で止める」ので layout は `+5%`〔全箱の伝播は `+67%`〕 ②子の確認を O(1) にする: 親の文脈が前回と同じなら、子はクリップ・位置・大きさ・親 id・動く祖先・部分木の印だけを見て、記憶は親の記憶の配列から引く〔ハッシュ引きなし。配列の位置の記憶が別の箱のものでないかは確かめる〕 ③親の組み立ては、子の断片が前回と同じ実体の位置が大半なら、層の列を前回のものから変わった子だけ `set` で差し替える〔`Fragment.aggregateFrom`。結果は全面の組み立てと同じ。これはパッチでなく組み立ての段の最適化〕。④記憶が部分木ごと飛ばすので、箱の記憶の統計〔`STAT_STAMPED`・`STAT_EXACT`〕を見るテスト 3 件は断片の記憶の当たり〔`fragmentMemoHits`〕も数える形にした。⑤副作用の再登録が要る: スクロールの節・窓の外の箱・箱の記憶の世代。絶対配置の箱が外側の包含ブロックのクリップに依るので、その間の箱の断片は記憶しない〔`tainted`〕。⑥残る悪化: 構造の変更〔子の増減〕・兄弟の多い親の O(兄弟数)・全面の再レイアウト〔C1p〕。

§6-151. **C1p の第 1 弾（2026-11-21）**: ①〔不具合〕記憶の掃除〔64 回に 1 度、古い記憶を捨てる `removeIf`〕が、そのフレームの `store` のたびに全記憶を走査していた〔1 フレームに 1 回の意図〕。large-2000 `child` の p99 が `72 ms`・最大 `98 ms` の停止になっていた。フレームの頭で 1 回に修正〔`child` `3.71 → 1.79 ms`〕 ②`culledBoxes`〔読み手がパッチ削除で無くなっていた〕と、記憶ごとの「描いた箱の一覧」のコピーを廃止。箱の記憶の掃除は断片の記憶が生きている箱の項目を残す形にした ③子の断片の配列は深さごとの作業域に組み、前回と同じなら前回の配列を共有。箱の中身の文脈〔クリップ・親 id〕は深さごとに使い回し ④結果〔null。mean〕: large-2000 `hover` `0.174 → 0.15 ms`〔表示リスト `0.098 → 0.073`・確保 `55 → 16 KB`〕、`child` `3.71 → 1.65 ms`〔確保 `1.0 MB → 0.74 MB`〕、showcase `hover` `0.073 ms`。**構造の変更の下限**: 子の挿入で後続の兄弟が全て動く〔`layoutsShifted` `1,751`/フレーム〕と、親相対の座標が変わるので、動いた箱ごとに項目を作り直す〔箱ごとの記憶の平行移動 + 断片〕。これは断片の記憶では消えず、**配置を親相対の不変の結果にする C4**〔項目の座標が親の原点を含まない形〕で消える。

§6-152. **C2 の第 1 弾（2026-11-21）**: 描画側の索引〔`PaintSceneDriver`〕の更新は 3 経路〔同じ並びの差分・挿入削除のシフト・並びの入れ替え〕あり、合計 480 行だった。シフト・入れ替えの 2 経路と `PATCH_SHIFT_INDEX`・`STAT_MOVE_PATCHES`・作業表・テスト〔`MovingChainPatchTest`・`PaintSceneDriverShiftPatchTest`・`ShiftPatchPixelTest`〕を削除し、**同じ並びの項目ごとの照合〔`patchIndexByDiff`: 不変リストの同一性と塗りの時計で差を取り、差のある位置だけ更新〕と、それで済まなければ全面と同じ作り直し〔`rebuildIndex`〕**の 2 つにした。構造が変わるフレーム〔子の増減・並びの入れ替え〕は索引を作り直す〔O(項目数)。パッチ時代の O(変わった所)より遅いが保守しやすい〕。`PATCH_DIFF_INDEX` は、結果を変えない検証用の切り替え〔差分更新と作り直しの等価性テストが使う〕として 1 つだけ残した。`PaintSceneDriver` `1,467 → 1,095` 行。§6-104 の守るテストは `RealDocumentFramesTest#gpuMatchesSoftwareEveryFrame` だけにした。未了: 項目の再生〔`GL_REPLAY`・`SOFT_GL_REPLAY`〕の安全条件を明示の鍵に集める・損傷範囲〔`GPU_DAMAGE`・`SOFT_DAMAGE`・`DamagePlanner`〕を断片の同一性の差から出す〔いまは項目の同一性と塗りの時計から出している〕。

§6-153. **C2 の第 2 弾（2026-11-21）**: 項目の再生〔止まった項目が前フレームと同じ入力なら GPU 出力を計算せず前進〕の入力の組〔項目の実体と塗りの時計・解決結果・クリップ・不透明度・省いた記録か〕を `paint.software.ReplayKeys` に集めた。照合は `replayable`・`sameItemAndPose`、記録は `remember`、破棄は `forget` の 1 組で、描画本体・差分更新・省いた項目の 3 か所の同じ配列操作を置き換えた〔挙動は不変〕。**未了〔C2 に残す〕**: 損傷範囲〔`DamagePlanner` 594 行〕を断片の同一性の差から導く件は、項目の同一性と塗りの時計から出す現行方式が 3 者画素一致のテストで守られており、置き換えは GPU の表示結果に直結する大きな設計変更のため、別の第 3 弾として単独で行う〔先に損傷範囲の入力を列挙し、現行との同値を差分テストで確かめる〕。

§6-154. **C2 の完了と損傷範囲の判断（2026-11-22）**: 損傷範囲を「断片の同一性の差」から導く案を検討し、**行わない**と決めた。理由: ①バックエンドが受け取るのは項目の列で、断片の木を渡すと描画側が組み立て側の型に依存する〔`PaintPipelineDirectionRatchetTest` の方向に反する〕 ②断片の葉の項目は断片の記憶で同じ実体が保たれる〔§6-150〕ので、項目の同一性の比較は断片の同一性の比較と同値 ③比較は 3 消費者とも `ItemDiff` の述語〔`unchanged`・`changed`・`sameKeyAndType`〕に限られ〔`ItemDiffRatchetTest`〕、判定は 1 か所。置き換えは経路を増やすだけで、GPU の表示結果へ回帰を入れる危険に見合う利点が無い。

§6-155. **C2 の完了の訂正（2026-11-22）**: §6-154 の「完了」は誤りで、描画側の差分パッチ〔`tryPatchItems`・`patchSpan*`・GPU の部分描画〕が残っていた。**測定**〔CPU・`cpu` バックエンド・mean〕: 差分パッチの経路を切ると showcase `hover` `1.48 → 1.67 ms`〔`+13%`〕・`style` `1.55 → 1.78`・確保 `212 → 276 KB`、large-list-2000 `hover` `0.364 → 0.407`・確保 `23 → 61 KB`・`text` `0.472 → 0.529`。**保守性優先で全削除した**。①索引の差分更新〔`reconcileIndex`〕は残す: 作り直しは項目ごとの状態を全消去し、再生が必要とする解決結果の同一性が毎フレーム失われる〔削除すると `SpanReplayVerificationTest` の検証が 0 件〕。②**不具合を 1 件修正**: 再生は動いている最中の出力も覚えていたため、補間〔色の遷移〕の最終フレームの出力を次の止まったフレームに再生して古くなる〔Vulkan の検証層が `BUTTON` の塗り色の違いで検出〕。止まっている項目だけ覚える。差分パッチが出力を上書きして隠していた。③GPU の部分描画も差分パッチ専用だったので削除。GPU の損傷の性能への影響は GPU 実機で未測定〔CPU の測定のみ〕。④ファズが `nested/21779068` で text の幅 `10 ≠ 11` を検出したが、HEAD でも同じ種で再現する既存の不具合〔文字パッチ。C5〕で、表には足していない。

§6-156. **C1m の記録（2026-11-22）**: 箱ごとの記憶〔`BoxMemo`〕は、断片の記憶と別に「箱自身の項目」を覚え、署名の比較・平行移動の再利用〔`appendTranslated`・`appendNodePatched`・`computeItemShifts`〕・変更印を持っていた。断片の記憶が同じ問い〔前回と同じか〕に答えるので、**箱自身の項目は断片〔`Fragment.own`〕に持たせ、文脈が前回と同じなら使い回す**形にした。①使い回しの条件は、断片の記憶の鍵〔位置・大きさ・クリップ・親 id・動く祖先・親相対の原点・属性の版・自分と祖先のスタイルの印〕に、箱の外の入力〔中身の大きさ・先頭の断片の位置・キャンバス背景か・対話規則・文字の影の抑制・placeholder〕を足したもの。**不具合を 1 件作って直した**: 子の確認は親の文脈が同じなら位置等だけで済ませるので、箱自身のスタイルの印は使い回しの側で見る必要がある〔検証モードが検出した〕。②平行移動の再利用を削除した結果、子の増減で動く後続の兄弟は箱ごとに作り直す〔large-2000 `child` `1.65 → 2.14 ms`・確保は同等〕。C4〔親相対の不変の配置〕で戻る。③リサイズでは断片の記憶を全て捨てる〔ビューポートが枠組みの鍵〕。ビューポートに依らない箱の再利用〔旧 `viewportSensitive`〕は無くなった。④`resize700` の画素ゴールデン〔Windows〕を取り直した。**Linux の画素ゴールデンは WSL でしか取れないので未更新**〔CI の Linux では `resize700` が赤くなりうる〕。⑤測定〔null・mean〕: showcase `hover` `0.095 ms`・`style` `0.083`・large-list-2000 `hover` `0.155`・`text` `0.444`・`child` `2.14`。無効化の鍵の整理は設計書 §20。

§6-157. **描画側の変化の印の調査（2026-11-22）**: ①`PaintCell` は項目の塗り〔`color`・`bgColor`・`opacity`〕と描画の上だけの平行移動〔`offsetX/Y`〕を**可変**で持ち、書くたびに `epoch`〔通し番号〕・`geomEpoch`〔移動のみ〕を進め、入っているスナップショットの `ChunkClock` に印を付ける〔B9 の M3・M4・M6〕。消費者は「前に見た番号」より新しければ変わったと見る。②塗りの書き込み〔`repaint`・`repaintFrom`〕の呼び出しは `main` に 0 件〔差分パッチの削除で消えた。`ChunkClockTest` と `PaintCellRatchetTest` だけが使う〕。移動の書き込み〔`UiView.setPaintOffset` ほか 9 か所〕だけが生きている。③「前に見た番号」を `PaintSceneDriver`〔`indexedEpoch`〕・`DamagePlanner`〔`prevEpoch`〕・`ReplayKeys`〔項目ごと〕・`CssAnimator`〔`seenEpoch`〕が別々に持ち、`ItemDiff.unchanged` が 3 つの消費者で使う。④`contentToken` は「移動の書き込みで別スナップショットになってもリストの中身は同じ」を表す印で、保持層の適格性の記憶〔`SoftwareRenderBackend.eligibleToken`〕と索引の基準〔`indexedBase`〕が使う。⑤`itemVersion` は索引の引き継ぎが項目の差し替え・クリップの変化で進める版で、クリップの記憶〔`ClipEntry.version`〕の有効性だけが読む。**結論**: 項目を不変にして平行移動を別の表へ出せば、①〜⑤の印は全て「項目の同一性＋表の版 1 つ」へ畳める。C6 として §3 に起票した。

§6-158. **経路の予測テストの結果（2026-11-22）**: `PathPredictionTest`〔40 種 × 40 手・約 27,000 箱。リサイズと時間だけの手は除く。1 手 1 回の描画を観測〕。予測は箱の入力の前後比較だけで決め〔自分が変わった → 作り直す、部分木だけが変わった → 自身の項目を再利用して子を組み直す、何も → 部分木ごと再利用〕、記憶の内部を見ない。①**不健全 0 件**: 変わった箱を再利用して古い項目を出す経路は無い。②**取りこぼし 17,102 件**〔予測 0 → 実際 1: 10,000 超・0 → 2: 約 5,000〕。内訳の追跡〔部分木の印が前回の描画後に進んだか〕で、ほぼ全て `touched`〔印は進んだが入力は同じ〕。残り約 330 件〔`untouched`〕は印以外の文脈〔クリップの実体・親 id 等〕の違いで、クリップは予測の入力に含めていないので予測側の限界の可能性がある。③原因の仮説: レイアウトの配置・再スタイルが、結果が同じでも `touchPaint` する〔`layoutsSkipped` は入力が同じなら配置を飛ばすが、飛ばさない箱の書き込みは値を比べない〕。④次: 書き込みを値の比較つきにして取りこぼしを減らす〔C7 ②〕。上限は `MISSED_CEILING` で見張り、減らすたびに下げる。

§6-159. **C7 ②（2026-11-22）**: ①**仮の印→確定**: レイアウトの書き込み口〔`MeasureMemo`・`PlacementMemo`・`LayoutBox` の配置〕は、結果が同じでも「書きうる地点」を通るたびに `touchPaint` していた〔測定の前後で 2 回・リセットでも〕。これを仮の印〔`PaintStamps.touchLayout`〕にし、描画の直前〔`DisplayListBuilder`〕の `PaintStamps.settle` が、**描画が読む出力の署名**〔寸法・余白・行・断片・文字・旗。上位集合〕または**子の並び**が前回の確定と違う箱だけ本当の印にする。待ち行列は**根の箱ごと**〔別スレッドのビューと干渉せず、文書が消えれば行列も消える。全体で 1 つだと描画しない文書の箱を保持して OOM になった〕。②**不具合 2 件を作って直した**〔予測テスト・検証モード・既存テストが検出〕: 〔a〕簡易確認で通した親が、自分のスタイルの印を見ないまま子へ「文脈が同じ」と伝えていた〔`ctxOk` に印の確認を足した〕 〔b〕箱自身の項目が子孫の状態〔固定・粘着・絶対配置〕で決まる「スクロールの節」を含むのに、鍵に入っていなかった〔`oneNode` を鍵に足した〕。③**祖先のスタイルの印を外した**: 子の項目が祖先から読むものは鍵〔動く祖先・位置・クリップ・親 id〕に入っており、継承は子自身の更新で印が付く。祖先の印を見ると、アニメーションする祖先〔showcase〕で子孫の全部が毎フレーム外れた〔ヒット 0〕。**唯一の例外**は `::first-line`〔子の文字の断片が持ち主〔祖先〕のスタイルを描画時に読む〕なので、持ち主のスタイルと ::first-line のスタイルを記憶の鍵へ明示した〔`FirstLinePseudoElementTest` が検出〕。④疑似要素のスタイル〔`::marker`・`::placeholder`・`::backdrop`・`::first-line`〕は `updateStyle` の外で代入されるので、`resolveHighlightPseudos` が値として変わったときに印を進める。⑤`updateStyle` は、宣言が値として同じ〔フォントの大きさ・ルートの大きさも同じ〕なら印を進めない。⑥結果: 経路の予測テスト 取りこぼし `17,102 → 1,049`・ヒット `659 → 4,740`。性能〔null・mean〕: showcase `hover` `0.091 ms`〔=前〕・`style` `0.083 → 0.091`・large-list-2000 `hover` `0.155 → 0.170`・`text` `0.444 → 0.318`〔−28%〕・`child` `2.14 → 2.45`〔+14%。署名と根までの探索のコスト〕。⑦**costGate の基準を取り直した**〔基準値はパッチ時代のまま C1 以降ずっと赤だった。`hover` の確保 `1,217 B → 32,559 B` など、C1 で受け入れた悪化が基準になる。`FineProfileMain` の `resizeexact` は `exactTranslation` の削除で動かなかったので `recomputeLayout` へ直した〕。⑧残る取りこぼし 1,049 件は、位置・大きさの変化の伝わり方・クリップの実体〔予測は見ていない〕。

§6-160. **C7 ③（2026-11-22）**: **描画が読む箱の欄を実測で洗い出し、署名・別の経路の宣言と機械的に照合する**〔`PaintReadSetTest`〕。①箱の欄を 1 つずつ摂動し、記憶なしの新しい描画器で描き直して、項目が変わった欄を「描画が読む欄」と決める〔洗い出し: `x y w h bT bR bB bL lineHeightPx scrollWidth/Height mB contentAutoSkipped isRenderedLegend` ほか〕。②その全てが `PaintStamps.SIGNATURE_FIELDS`〔署名の欄〕か `PaintStamps.COVERED_ELSEWHERE`〔別の経路で見張る欄と理由: position 由来の 4 旗・書字方向の 2 旗・`hasInteractiveRules`・`subtreeHasContentAuto`〕に入っていなければ赤。③署名の各欄が、本当に署名を変えることも確かめる〔挙げただけで効かない欄を検出〕。④検出力の確認: 署名から `bB` を外すと赤になる〔ミューテーション〕。⑤限界: 箱の**欄**にない依存〔`::first-line` の持ち主のスタイル・`oneNode`・クリップの実体・位置の親〕は洗い出せない。それらは記憶の鍵 `Memo` の欄で、予測テスト・`OwnItemsVerifier`・ファズが見張る。鍵そのものを宣言から生成する〔署名と `Memo` の手書きの対応を 1 つの表にする〕のは、C3 の「箱の入力を 1 つの値にまとめる」と一緒にやる。

§6-161. **C7 ④（2026-11-22）**: **有界の全列挙による安全性の確認**〔`BoundedModelCheckTest`〕。小さな文書と有限の操作〔クラス・インラインスタイル・文字・子の増減・表示切替・位置指定・`::first-line` のクラス・絶対配置・属性。12 手〕について、**長さ 4 以下の全ての操作の列**〔22,620 列・88,428 手〕を新しい画面で数え上げ、各手の後で記憶あり描画が記憶なし描画と項目ごとに同じことを確かめる。範囲の中では「見つからなかった」でなく「ない」と言える。**実際の不具合を 2 件検出し修正した**: 〔a〕**`title`・`data-*` など属性だけの変更で再描画されない**〔C7 ② の退行。属性だけの変更は再スタイルを通らず再レイアウトの経路で届くが、署名が属性の版を含まず、仮の印が確定しなかった。署名に `node.attrVersion()` を足した〕。〔b〕**`::first-line` のクラスを外しても色が戻らない**〔描画は持ち主が無いとき 1 行目の断片自身の `firstLineStyle` を読む。記憶の鍵には持ち主のスタイルしかなく、署名にも断片の指定がなかった。断片の `firstLineStyle`・`firstLineOwner` を署名へ、断片の指定を記憶の鍵 `flFrag` へ足した〕。〔c〕範囲外: スクロールは実際のホイール経路〔バックエンドの作業単位〕でないと状態が揃わないので含めず、ファズの scroll モードが見張る。〔d〕経路の予測テストの取りこぼしは `1,049 → 1,104`〔属性の版を署名に足した分。上限 1,200 の内〕。全件 `:core:test`・ファズ 900 回緑。

§6-162. **C7 ⑤（2026-11-22）**: **先行例の調査**〔検索で確認できた範囲。網羅ではない。Blink の記述は 2016〜17 年の資料で、LayoutNG 後は未確認〕。対応: Blink `PaintController` の cached subsequence・per-item cache = `Memo` の HIT・OWN／Blink の under-invalidation 検査 = `VERIFY_BOX_MEMO`・`OwnItemsVerifier`／Salsa の red-green・early cutoff・backdating = `PaintStamps`／Stylo の `RestyleHint`・`RestyleDamage` = 仮の印と 3 段／Flutter の `RepaintBoundary` = 鍵の比較で境界を毎回決める／React `memo` の bailout = 鍵の比較。**新しいと言えそうな部分**〔同じ組合せを見つけられなかった、の意味〕: 独立の予測器〔経路の効率まで見張る〕・読み取りの実測・有界の全列挙・経路を 1 つの規則から決めること。**限界**: 予測器は記憶と同じ人が書く・全列挙は小さな範囲の仮説の下・読み取りの実測は箱の欄だけ。設計書 §22.8。

§6-163. **C1p の完了（2026-11-22）**: パッチ・近道スイッチを増やさずに 3 本柱〔祖先 `ownReused` 分離＋`FragSeq.Vec` 永続木 `insert`/`remove`＋錨キャッシュ／`DirtyKids`〔`DIRTY_MIN_KIDS=64`〕多兄弟スキップ／部分木単位ビューポート感度〕とホットパス削減〔`flatBuf` 直接展開・`dlStart`/`dlEnd`/`dlOrder` 削除で `SliceWriteSiteRatchetTest` `8 → 2`・`updateStyle` `newStyle==before` 短絡〕を実装し、`:core:costGate` の確保を大幅に回収した〔showcase `hover` `32,559 → 9,046 B`・`resizeexact` `474 → 248 KB`・large-list `hover` `10,260 → 2,457 B`・`child` `163 → 43.1 KB`・voidwave `hover` `96.7 → 26.0 KB`〕。`FineProfileMain`（null）は showcase `hover` `0.0612 ms`・large-2000 `hover` dl `0.031 ms`・`child` `2.11 ms`・`resizeexact` `0.555 ms`、`PathPredictionTest` の取りこぼしは `1,104 → 266`〔不健全 0〕。構造変更時に後続兄弟の親相対座標が動いて作り直す下限は C4 で回収する（設計書 §19.5）。

§6-164. 〔不具合〕**親の flex/grid↔block 切替で位置・スタイル不変の子の `Fragment.Kind` が古いまま記憶に当たった**（C1p、2026-11-22。差分ファズ `nested/39` 手 33）: `LayoutBox.updateStyle` に `newStyle == before` の短絡を足した結果、親が `flex→block` に変わっても位置・スタイル不変の子が `ownPaintStamp` 不変のまま `PATH_HIT` し、旧 `ATOMIC`（`INLINES` 層）の断片を返して `BLOCK` 層の兄弟と描画順がずれた。`PaintSupport.isFlexOrGridItem` が親の `display.inner()` を読む依存が `FragmentPaint.Memo` の鍵に漏れていたため、`flexOrGridItem`（`sameContextCheap`）と `flexOrGridContainer`（`kidsCtxOk`）を鍵に追加し、`FragmentPaintC1pTest#flexToBlockParentSwitchReclassifiesUnmovedChildKind` と種 `nested/39` を固定した。

§6-165. **C6 の完了（2026-11-22）**: ①`PaintCell`〔13 非静的欄すべて `final`〕と `DisplayItem` を完全不変にし、死んだ塗りの書き込み〔`writePaint`・`WRITES`・`epoch`・`geomEpoch`・`clock`・`offsetX/Y`・`DisplayItem.{repaint, repaintFrom, sameExceptPaint, attach, setPaintOffset, frozen}`〕を削除した〔`PaintCellRatchetTest` が全非静的欄 `final` を機械固定〕。②生きているスクロール・ドラッグ等の平行移動は外部表 `PaintOffsets`〔要素 ID 文字列とキャッシュ済み `DisplayItem.keyOf(id)`、`version`・`keysVersion`・`EMPTY` 不変番兵〕へ移し、`UiView` は `baseRenderCache`・`publishRenderCache`・`baseIndexById` 等を廃して `2,087` 行へ縮小。`PaintOffsets` 書き換えは `UiView` 内に限定した。③`ItemDiff.unchanged(prev, cur)` は `prev == cur` 1 本になり、`ChunkClock`〔`ChunkClockTest` 含む〕・`ImmutableItemList.{touched, attach, epoch, token, clock}`・`contentToken`・`PaintSceneDriver.{itemVersion, indexedBase, snapScrollContent}` を削除。`SoftwareRenderBackend.ClipEntry` は `DisplayItem item`＋`NO_CLIP_SOURCE` 番兵の参照一致へ置換した。④4 か所の「前に見た番号」〔`CssAnimator.seenEpoch`・`ReplayKeys.epoch`・`PaintSceneDriver.indexedEpoch`・`DamagePlanner.prevEpoch`〕を廃し、`DamagePlanner` は `PaintOffsets` の版と差分座標配列、また `StaticFrameGate` は同一インスタンスを `clear` して空になった場合を取りこぼさないよう `paintedOffsetsEmpty` も併用して静止フレーム判定する。⑤結果: `costGate` で `voidwave.html scroll` `-61.7%`（`2,352 → 901 B`）・`machine_ui.html scroll` `-6.2%`（`47,695 → 44,759 B`）・`varied-list-500 inherited` `-6.9%`（`195,473 → 181,897 B`）・`showcase inherited` `-5.2%`（`55,365 → 52,474 B`）・退行 0〔基準更新後 56 シナリオ `COSTGATE OK`〕、`mutationFuzz` 450 回ずれ 0（設計書 §21.5）。

§6-166. **S7 の完了（2026-11-22）**: ①L5→`LayoutBox` 書き戻しの完全撤去: `LayoutBox` から `movingMemoEpoch`/`movingMemoResult`/`paintCulled`/`dlStart`/`dlEnd`/`dlOrder`/`clearDlSlice()`/`withPaintBounds()` を削り、`PaintOrder` の走査中 `culledStack` と L5 局所 `PaintBounds` へ置換した〔`LayoutBox` `pubMut 40→34`・`methods 174→171`・`1,870→1,809` 行、`DisplayListBuilder` `979→926` 行、`LayoutBoxReadOnlyFromPaintRatchetTest` 0、`SliceWriteSiteRatchetTest` 0〕。②〔仮想節とビュー別状態の分離〕`HtmlNode.{interactionOwner, interactionFlags}` を撤去し〔`HtmlNode.pubMut 8→6`〕、`DocumentArena.NodeSlots` のロックフリー読取と仮想節用 `VirtualNodeSlots`〔`Milestone1GateTest` の `WeakHashMap` 禁止に適合しつつ `ReferenceQueue` で回収〕へ移行。`::before` のテキスト節のような仮想節の子孫でも余分なアリーナを作らないよう `HtmlNode.isVirtualNode()` が仮想祖先を辿るようにし、`InteractionState.tabFor` で `lastTab` の追い出しを防いだ〔`ResizeSkipRebuildTest` の全 12 件を回復〕。③〔フレーム境界スナップショットと `voidwave.html scroll` の確保削減〕`UiView.paintOffsets()` と `PaintRequest` がフレーム境界で `paintOffsets.snapshot()` を返し、新規不変レコード `PaintFrame`＋`FrozenItemList` で B7 の不変契約を確立した。当初 `UiView.tryScrollTranslate()` が未スクロール領域にも `setPaintOffset(id, 0, 0)` を呼んで約 40 件の `(0, 0)` 項目を積み、`voidwave.html scroll` の `snapshot()` が `1,335 B`〔基準 `921 B` から `+414 B`〕へ跳ねた〔JFR で特定〕が、`PaintOffsets.setIfActive()` で未登録 `(0, 0)` の登録を省き `1,017 B`〔全 56 シナリオ `COSTGATE OK`〕へ収束した。全モジュールテストおよび直列 `:core:costGate` 緑。

§6-167. **C3 の完了（2026-11-22）**: ①不変鍵 `MeasureKey`（`ExternalEpochs` で測定/shape 世代・書体解決器＋世代・固有サイズ解決器＋世代）・`MeasureReads`（入力読み取り `READ_*`＋親読み取り `PARENT_READ_*`＋スレッド状態統一）・`TentativeSlots` 分離を導入し、`GridLayoutEngine`・`FlexLayoutEngine`・`BlockLayoutEngine`・`TableLayoutEngine`・`IntrinsicSizing`・`BoxMeasurer` から実際の読み取りを自動記録する構造へ移行した。②親読み取り集合による一般化 cutoff を導入し（`parentReadStillMatches`/`extraParentReadsMatch` では `MeasureReads.pushMeasuring(null)` 退避で先祖スタックへの baseline 上書きを抑止、再レイアウト境界吸収時も同照合を要求）、旧ヒューリスティック `LayoutEngine.contentSizedAncestor`・`FlexLayoutEngine.cutoffSafeItem` を削除した。③`costGate` の途中検出・是正: `MeasureMemo.invalidateParentOnly` が下降再測定中の無条件ループでアクティブな測定スタック上の祖先（`.surface-workspace` 格子・カード群）の `tentativeSlots.entries` まで消去し、`surface_test.html text` が `12,155→431,248 B`・`open` が `3.46→3.70 MB` へ悪化していたが、`!MeasureReads.isOnStack(p)` ガードで解消して `12,123 B`・`3.49 MB` へ復帰させた〔`MeasureReadSetTest#tentativeSlotsSurviveDescentWhenAncestorRemeasures` を追加〕。また行フレックス子孫の `% column-gap`/`flex-basis`（`StyleFacts.edgesAvailableFree`）とグリッド items の非透過読取・新規書体インスタンス世代も実測で塞いだ。④C7 ③引き継ぎの描画入力統合宣言表 `PaintBoxInputs.ALL` を新設して `PaintStamps`/`FragmentPaint.Memo` と `PaintReadSetTest` で全単射照合を固定。結果: `costGate` 全 56 シナリオ `COSTGATE OK`（`item_block_test.html text` `-98.3%`／`layoutsSkipped 15,600→36,000`・`text cpu` `-82.4%`・`showcase text` `-7.5%`・`animation_test.html text` `-8.5%`）、`FineProfileMain` A/B は C3 前 `HEAD` `454f884d` 比 ±4% 内（`varied-list-2000 fontsize` `11.35→11.43 ms`、§2 の 2026-11-17〜19 値 `6.71 ms` との差は C1 以降の `display-list` `4.69 ms` 由来で C3 退行ではない）、`mutationFuzz` 種 1〜1500（4,500 回）＋`VERIFY_BOX_MEMO` 種 1〜100（300 回）ずれ 0・`:core:shortcutMatrix` レイアウト 17 構成×68 件不一致 0・全モジュールテスト緑。


§6-168. **KPI 再計測で時間の退行を発見（2026-11-23）**: `costGate`（確保・描画なし）は全 56 シナリオ緑だったが、GL 込みの実フレームは C1 の一本化〔`602b0d5f`〕で一段悪化していた〔large-2000 `hover` `0.064 → 0.19 ms`・varied `inherited` `1.33 → 3.18`・large `child` `0.96 → 2.2`。`9aa8d9c7` を境にした二分探索で、後続の C7・C1p・C6・S7・C3 は hover・inherited をほぼ動かさず、large `child` だけ C1p で `1.8 → 2.2` と微悪化〕。C1 の「時間は許容して悪化し、回収は C1p」は確保しか回収していなかった。**確保の回帰ゲートは時間の退行を捕まえない**ので、`costGate` に時間の粗い天井（±2 倍）を足すかは C8 で判断する。§3 C8。
§6-169. **C8 の着手前調査（2026-11-23）**: ①**`hover`（large-2000）の退行はほぼ描画側**: ベンチの対象 `item-100/101` は画面外（窓の中）で、変わる項目は 2 つだけ〔表示リスト `#459`・`#461`、`sameList=false`〕。新経路は項目の差し替えでリストの同一性が変わるため `StandaloneView.frame` が毎フレーム `backend.render` を呼び、GL が 162 項目・589 図形を再生する〔`paint(cpu)` `0.062 ms`＋display-list `0.021`。旧は `drawn=0`〕。可視の `hover`（showcase）も同じ型: restyle `0.020`・display-list `0.041`・`paint(cpu)` **`0.152 ms`**（68%・456 項目 / 1,194 図形を再生。旧の全体は `0.097`）。→ 断片の組み立てではなく GL の損傷範囲の問題。②**`child`・`inherited` は組み立て側**: large-2000 `child` の display-list `0.075 → 1.13 ms`〔1 フレーム約 250 の子の断片を通り、1 つ `4.4 µs`〕、varied `inherited` `0.28 → 3.7 ms`〔651 項目で `5.7 µs`/項目。旧は `0.43 µs`〕。JFR の `FragmentPaint.build` 内の自己時間: `child` は `LongAdder.add` 16%・`HtmlNode.treeTop` 10%・`selfViewportSensitive` 8%・`IdentityHashMap` get/put 12%、`inherited` は `build` 本体 14%・`PaintOrder.factsOf` 13%〔継承の変更で全 `ComputedStyle` が新しくなり `derivedFacts` の保存が外れ、`z-index` などを `equalsIgnoreCase`/`toLowerCase` で毎回解く〕・`sameKids` 12%・`kid` 13%。③**外れた仮説**: 統計の `LongAdder`〔`MEMO_HITS`/`MEMO_MISSES`/`OWN_REUSED`〕を外しても display-list は有意に下がらなかった〔同じコードの 2 回で `1.78`/`2.01 ms`、外して `1.71 ms`。この機械は同一コードで ±50% ばらつく日があるので、A/B は交互・カウンタ併用で取る〕。④負けの起点は C1 の一本化〔`602b0d5f`〕の 1 点で、後続の C7・C1p・C6・S7・C3 は ほぼ動かしていない〔§6-168〕。→ §3 C8 の順序（①C8a → ②C8b → ③）の根拠。
§6-170. **C8a の着手前の追加調査（2026-11-23）で、C8a の前提が崩れた**: ①可視 `hover`（showcase）の変更は 9 項目〔`BUTTON`・`NODE`・`RECT`・`ROUNDED_BORDER`〕だけで、描画側の再生の鍵は 474 項目中 約 16 項目しか外れない〔`notSameItemAndPose` 約 16/フレーム・`resDiffer` 0・`notFast` 約 2〕。再生の鍵の同一性（`C6` の `prev == cur`）が原因という仮説は外れ。②`paint(cpu)` `0.122 ms`〔旧 `0.068`〕の内訳は、`-Pprofile.sample` で GPU フェンス待ち `GlDevice$FenceRing.await` が 47%〔`beginFrame`〕・ドライバの走査 `PaintSceneDriver.run` が 30%・表示リストの構築 10%・restyle 16%。増分 `+0.054 ms` のうちどれが新経路由来かは未特定〔旧に戻した A/B はサンプル数が足りず断定できない〕。③`GpuRenderBackend` の `glyphs` 統計は `replayedGlyphs`〔最後の項目の値〕を足しているだけで意味が無く、「旧 1,066・新 4.9M」の差は経路の差を表さない〔計測の外れ。統計の修正は別件〕。④large-2000 `hover` は画面外の 2 項目〔窓の中。画面外の判定は `RangeBounds` の窓と `outsideClip` の 2 系統〕だけが変わり、`frame()` が `before != after` で毎フレーム描く。ここだけは「見えない変更は描かない」で `0.062 ms` を省ける。→ §3 C8 の順序を ①C8b ②C8a に改めた。
§6-171. **C8b の着手前の追加調査（2026-11-23）— 作り直しは定数倍でなく個数の問題**: `FragmentPaint.MEMO_MISSES` を `FRAGMENT` 診断行へ足して 1 フレームあたりで数えた〔`FragmentPaint.build` の記憶の当たり／外れ〕。large-2000 `child` は **hit `249` に対し miss `約 3,509` /フレーム**、varied-2000 `inherited` は **miss `約 4,916`**〔箱 3,457＋無名箱・文字〕で、窓〔画面＋余白〕の外の箱まで毎フレーム作り直している〔表示リストの項目は窓の中の 462 / 651 だけ〕。`child` は挿入で後続の箱の `y` がずれるため `sameContextCheap`〔`m.y != box.y()`〕で外れ、`inherited` は全箱の値が変わるため外れる。`FragmentPaint.buildFresh` は窓外の箱でも自分の項目を省くだけで〔`cull && !inWindowGeometry` → `pushCulled`〕、子の走査と断片・記憶の作り直しは続ける。1 箱 `約 0.3 µs` × 約 3,500 箱 ≒ display-list `1.1 ms`。→ 旧経路〔`9aa8d9c7`〕は窓外の部分木を打ち切る・位置のずれを平行移動で済ませる〔`BoxMemo` の平行移動の再利用。C1m で削除〕ことでこれを避けていた。**統計の `LongAdder`・`treeTop`・`factsOf` などの個別の固定費は副次**〔個数を減らさない限り `child` は `1 ms` 前後のまま〕。進め方の候補: ①窓外の箱を部分木ごと打ち切る〔箱の見た目が箱の外へはみ出す上限＝部分木の外接が要る。`LayoutBox` には `scrollWidth`/`scrollHeight` しか無く、はみ出し〔影・`outline`・位置指定・変形〕の上限は無い〕②位置のずれだけの箱を記憶の当たりにする〔親相対の配置 = C4〕。①は独立に入れられるが、はみ出しの上限の作りが画素一致の要点。
§6-172. **memo hit/miss の一覧（2026-11-23）**: 各段のカウンタを `FineProfileMain` の `MEMO` 行に統一した〔style: 訪問・再計算・変化／測定: 呼び出し・計算／配置: 呼び出し・skip・shift・placed／断片: hit・miss〕。①**増幅率が 1 に近い所は問題が別**: `inherited`・`fontsize` は変わった箱 ≒ 再計算した箱〔large `4,003`→`4,007`〕で、遅さは 1 miss あたりの時間〔display-list `0.43 → 0.75 µs`〕と窓外の箱まで作る個数〔§6-171〕。②**増幅率が桁違いなのは 2 か所**: `child`〔変更元 2 に対し配置 shift `1,751`・断片 miss `3,510`。varied は `6,451`。C4・C8b の対象〕と、**実文書の `text`〔配置 skip `0`。C9〕**。large-list の `text` は skip `1,999` と当たるので、実文書だけ外れる理由がある。③`hover`・`scroll`・`idle`・large の `text`/`style` は健全〔≤ `9`〕。④測定の記憶は `child` で `2,010` 呼び出し中 計算 `5.9` と良く当たる。⑤カウンタはウォームアップ込みの累計をフレーム数で割る〔開く 1 回ぶんが混ざるので `idle` でも `0.1〜0.5` が出る〕。

§6-173. **`costGate` に記憶の増幅率を組み込んだ（2026-11-23）**: `FineProfileMain` の `COST` 行の末尾に `memoStyleRecomputed`・`memoMeasureComputed`・`memoPlaced`・`memoFragMisses`〔開いた後の 1,200 フレームの再計算数。開く分は計測前の値を引く〕を足し、完全一致で比べる〔直列 2 回とも `COSTGATE OK`＝決定的〕。`CostGateMain` はこれを変更元で割った最悪値が `10` を超えたら赤にし、既知の 17 行は `memoCeil=N` で上限を固定する。**初回の更新で 17 行が違反として出た**: `text` の配置が実文書の全てと showcase、`child` 2 行〔cpu 行なし〕、machine_ui の `scroll`〔配置 `22`/フレーム・断片 `28`〕。C9 は voidwave 単独でなく実文書全般の問題。Linux での決定性は未確認〔push 前に `scripts/ci-linux.ps1 -Full`。運用ルール 6〕。
§6-174. **C9 の原因調査（2026-11-23）— 配置が当たらないのは「純粋性」が祖先を巻き込むため**: `tryReuseOrShift` の外れ理由を調べると、入力は同じ〔`sameInputs`〕なのに `canKeepPlacement`〔`KEPT_PURE`・`VALID`・祖先の relative/sticky/float〕で外れる。`resetPlacements` が上から「変更の鎖」〔`measureMemo` が無効・layoutChanged・subtreeChanged の箱〕を作り直し、鎖の直下の兄弟を保持できるか見るが、**純粋でない兄弟は部分木ごと配置し直す**。鎖の直下で外れた箱を理由別・部分木の大きさ別〔箱/フレーム、部分木の箱の延べ数/フレーム〕に数えた: surface `!keptPure`〔子孫に relative〕の GRID `1`・部分木 `134`／voidwave `relative<div>` 自身 `10`・部分木 `663`／animation・media・showcase は**子孫のスクロールコンテナ**で GRID/FLEX `1`・部分木 `295 / 493 / 214`／item_block・guidebook は**表**〔`td`/`th` 各 8 万・2 万回 /900 フレーム〕。理想〔鎖だけ＝ `G changed chain` 5〜7 箱〕との差がこれ。**外れ理由の `floatsAffectPlacement`〔祖先が relative/sticky/float を持つ〕を外しても配置数は変わらない**〔実験: `-Dc9.exp` で 0 オフセットの relative を通しても voidwave 496・surface 121 のまま。次の `!keptPure` で止まる〕ので、単独の修正では足りず純粋性の定義が本体。なぜ relative が止めるか: `applyRelativeOffset` が**子孫の座標へオフセットを破壊的に加算**し〔`lastLayoutX` も消す。§6-118〕、保持した子孫へ再度加算すると二重にずれるため。0 オフセットなら加算が起きないので保持してよい。machine_ui `scroll` は別系統〔`!reuseMeasurements`＝スクロールのフレームは測定の再利用が無効のまま全箱を配置し直す〕。**この調査は一時の計器〔`PlacementMemo` の外れ理由の集計〕で行い、コードには残していない**。
§6-175. **C9a（スクロールコンテナ）の実装と、relative 単独が効かなかった実測（2026-11-23）**: 着手前に、鎖の直下で純粋でない箱が「インセット無しの relative を純粋とみなしたら純粋になるか」を数えた〔一時の計器〕。voidwave: 純粋になるのは `25,200 / 640,000` 余りの約 4% で、残りは子孫のスクロールコンテナ約 61%・abs 約 34%／surface: **0%**〔`via scrollContainer` が `120,600` 全部〕／animation・media・showcase: スクロールコンテナが 100%。→ 計画書の順序〔relative が先〕を改め、**スクロールコンテナを C9a** にした。実装: `ScrollState` に全面共通の版 `AtomicInteger`〔位置が実際に変わったときだけ加算。`StaticStateRatchetTest` は `Atomic*` を許す〕、`PlacementMemo` の純粋性を 3 値〔`PURE`/`SCROLL_DEP`/`IMPURE`〕にし、`KEPT_PURE` は「自分のスクロールを除いた子孫が `IMPURE` でない」、`F_SCROLL_DEP` は子孫にスクロールコンテナがあるとき〔`canKeepPlacement` が `scrollVersion` == 現在の版を要求〕。`holdsPlacement`〔`F_PURE`〕は従来のまま厳密。検証: `PlacementKeepModuloScrollTest`〔2 本。文字の変更・スクロール・入れ子のスクロールを増分で動かした箱の木が、同じ状態を最初から作った箱の木と一致〕、**版の比較を外した変異版でこのテストが赤になる**ことを確認、差分ファズ種 1〜1500〔4,500 回〕ずれ 0〔ファズ自体にはスクロール操作が無いので専用テストで補う〕、全件 `:core:test` は既存の `TextShaperIntervalAndFontCacheTest` 1 件のみ失敗〔変更を外した HEAD でも同じ。Windows のみ。別件〕、直列 `costGate` 56 通過〔基準値を更新: showcase `text` の `memoCeil` `54 → 14`・media `124 → 108`・item_block `168 → 159`〕。**教訓**: テストの比較に表示項目を使うと、増分〔スクロールは `PaintOffsets` に持つ〕と最初から作った結果〔座標に焼き込み〕で表現が違い、HEAD でも赤になる。箱の木の座標で比べる。
§6-176. **C9b の実装と、途中で見つけた既存の不具合（2026-11-23）**: 着手前の見積もり〔abs・インセット無しの relative・スクロールを純粋とみなしたら何割が純粋になるか〕で voidwave・surface・animation・media・showcase は**ほぼ 100%**、残るのは表だけと確認してから実装した。①**`RealDocumentFramesTest`〔実文書の増分 = 全面再構築〕とスクロールの専用テストが赤になった**〔子のスクロールコンテナ自身が親から見て純粋に見えていた。`levelNoCb` に子自身のスクロールを足して解消〕。安全網が効いた例。②**`PlacementKeepAbsoluteTest` が既存の不具合を発見**: `transform`・`filter`・containment の「包含ブロックを作るか」が切り替わっても、描画だけの変更として扱われ、子孫の絶対配置が古い基準のまま残る〔**変更を外した HEAD でも再現**。増分 ≠ 最初から作った結果〕。全箱に適用すると hover の `transform` でレイアウトが走り `costGate` が voidwave `hover` の増幅率 `17` で更新を拒否した〔ゲートが想定どおり退行を止めた〕ので、**絶対配置・固定配置の子孫を持つ箱の切り替えだけ**を再レイアウトにした〔`ContainingBlockFlip`〕。③**変異の確認**: 外の包含ブロックの検査・子のスクロール・包含ブロックの切り替え判定は外すとテストが赤になる。一方、インセット付き relative の純粋扱い・埋め込みオフセットの記憶・アンカー判定・ビューポート単位判定は外しても赤にならない〔エンジンに別の防御がある: `applyRelativeOffset` が子孫の記録を消す〔§6-118〕・アンカーは後処理で解決・リサイズは全面の配置し直し・`top:10%` は親の高さに依らない〕。保守的な二重の防御として残したが、必要性は示せていない。④確保: 記録のたびの小さな配列が item_block `text` で `+35%` を出したので、作業欄をメモの欄へ移して確保をなくした〔voidwave `text` `18,576 → 15,374 B` ほか改善のみ〕。⑤検証: 差分ファズ種 1〜1500〔4,500 回〕ずれ 0・直列 `costGate` 56 通過〔基準値を更新〕。⑥`MeasureReadSetTest` の不安定さは §6-178 で原因を特定〔テストが実時間の間引きを待たない。エンジンの不具合ではない〕。
§6-177. **配置の記憶の照合モード `VERIFY_PLACEMENT`（2026-11-23）**: 測定の記憶の `MeasureVerifier` の配置版として `PlacementVerifier` を作った。`tryReuseOrShift` が省略・平行移動で受理した部分木を、記憶なしで最初から置き直して〔`resetPlacements(box, -1)` ＋ 再利用を切った文脈での `layoutImpl`〕、位置・大きさ・静的位置を比べ、食い違えば `AssertionError`〔箱・欄・値・`debugInfo`〕。`LayoutBox.layoutImpl` の分岐に 1 行足しただけで、新しい経路は無い。`:core:test`・`mutationFuzz` で常時有効〔`-Pverify.placement=false` で切れる〕。**所要時間は増えていない**〔`quickTest` 約 2.5〜3.7 分で従来と同程度〕。①**検出力**: オラクル〔最初から作った結果との比較〕を持たない `PlacementVerifierTest` だけで、外の包含ブロックの検査・子のスクロール・スクロール量の版の比較・純粋性の素通し、の変異を全て検出できる。②**比べない欄を実験で決めた**: 最初は断片・スクロール範囲も比べて `IncrementalVsFullTest` 2 件・`RestylePruneTest` 1 件が赤になった。通常に置いた直後の全ブロック箱を同じ手順で検証する実験をすると、**位置・大きさ・静的位置は食い違い 0、スクロール範囲と行の断片は食い違いが出る**〔断片の開始・終了・遅延消去は祖先のエンジンが受け持つので、部分木だけの置き直しでは個数が変わる〕＝検証側の人工物。偽陽性を避けるため比べる欄から外した。③**限界**: 「レイアウトが走るべきなのに走らなかった」種類の不具合〔包含ブロックの切り替え `ContainingBlockFlip` の判定を外す変異〕は、保持の検証では見つからない〔検証は保持が起きたときだけ走る〕。スクロール範囲・行の断片は照合していない。④差分ファズ種 1〜1500〔4,500 回〕を検証モード有効で流してずれ 0〔保持の誤りは出ていない〕。この検証はファズにも実文書テストにも効くので、C9c 以降（表・余り）の変更の安全網になる。
§6-178. **`MeasureReadSetTest` の不安定さの原因は、リサイズの実時間の間引き（2026-11-23）**: 全件の並列実行でだけ時々落ち〔Linux で 2 回・Windows で 1 回・直近は 5 回中 3 回〕、`fv.resize(60, 120)` の後に古い幅 `400` が残る〔期待 `60`〕。①**原因**: `UiView.resizeDue` は連続したリサイズを「直前のレイアウトの所要時間の 2 倍が過ぎるまで」次のフレームへ持ち越す〔実時計。ホストの描画を重いレイアウトで詰まらせないための意図した動き〕。テストは `resize` の直後に比べるので、直前のレイアウトが重く測られた回〔GC・スレッドの割り込み・並列の他テストとの競合〕だけ間引きが効いて反映されず落ちる。**C3・C9 の変更とは無関係で、保持・無効化の不具合ではない**。②**確定の根拠**: 同じ手順を 1 つの JVM で回すと**待たない形で 3,000 回中 2,671 回〔約 90%〕失敗**〔速い繰り返しは間引き内に入る〕、**反映されるまで回す形では 7,800 回〔直列 3,000＋8 スレッド 4,800〕全部成功**。③**直し**: テスト用の `ResizeSettle`〔反映されるまで無効化処理／フレームを回す〕を作り、`MeasureReadSetTest`・`IncrementalVsFullTest` の補助関数・`GoldenFrameTest`〔固定 6 フレームだった〕に適用。前例は `a450765d`〔`RealDocumentFramesTest` の resize700〕・`MutationScenario`・`ScrollTranslateTest` の spin。④**教訓**: 並列実行でだけ落ちるテストは、まず実時計に依る箇所〔`System.nanoTime`・`currentTimeMillis` を読むエンジンの経路〕を疑う。単独実行では負荷をかけても再現しない〔直す前 `12 回中 0 回` 失敗。並列の GC や競合が要る〕ので、A/B は「同じ手順で待つ／待たない」だけを変えて速い繰り返しで比べるのが確実。⑤同じ書き方の残り: `resize` を呼ぶテストのうち待たないものは上の 3 件で全部〔他は spin で待つ〕。再発防止の機械的な検査〔テストの `resize` の直後に待つ呼び出しがあるか〕は未実装。
§6-179. **C9c（表）の実装（2026-11-23）**: 着手前の理由別集計〔鎖の直下で保持されなかった箱〕で、guidebook は**表自体が不純**〔`isTableBox`〕、item_block は**枠線を統合する表**〔表・セルが `collapsedBorders` を持つ。表を含む祖先 1 フレーム約 `738` 箱とセル `89`＋`11` が毎フレーム置き直し〕と特定した。実装: `selfContained` から `isTableBox` と `collapsedBorders` を不純の条件から外し、表は `strictBroken`〔`F_PURE` には入れない＝`holdsPlacement` は従来どおり厳密〕。行・セル・キャプションが記録の根のときだけ `IMPURE`〔表ごとにしか保持できない。`canKeepPlacement` の親の整形の検査とも二重〕。`collapsedBorders` は測定の段階で組む出力で、配置だけ飛ばす保持とは整合する〔測り直した表は測定側が作り直す〕。検証: `PlacementKeepTableTest`〔統合の切り替え・列幅の変化・兄弟の表が保持される場面・relative の中の表と絶対配置。毎手、最初から作った結果と比べ、**表を含む部分木の保持が検証された回数**〔`PlacementVerifier.verifiedTables`〕も確認〕。表を不純に戻す変異でこのテストが赤になる。表の内部を単独の根にする変異は生き残る〔親の整形の検査が同じ防御を担うので同等〕。全件の照合モード有効の `quickTest`〔既存の `TextShaper` 1 件のみ失敗〕・差分ファズ種 1〜1500〔4,500 回〕ずれ 0・直列 `costGate` 56 通過〔基準値を更新〕。**C9a〜c を通して、鎖の直下で保持できない原因は「部分木の純粋性」を壊す構造〔スクロール・abs・relative・表〕の順に潰した。残りの `showcase`/`voidwave` `14` は構造ではなく余りの 4 箱で、別の見方が要る**。
§6-180. **KPI の 2 回目の再測定（C9a〜c の後。2026-11-23）— メモの当たり以外の改善と、見つかった退行**: ①**改善**: `text` 変更のパイプライン時間〔描画なし〕が旧 `ea4dc204` との交互 A/B〔各 3 回・中央値〕で全 7 文書 `0.55〜0.83x`〔voidwave `0.276 → 0.151 ms`・item_block `0.193 → 0.114`・media `0.154 → 0.100`・guidebook `0.092 → 0.061`・surface `0.131 → 0.098`・animation `0.101 → 0.077`・showcase `0.107 → 0.088`〕。確保も voidwave `18,507 → 15,280 B`・guidebook `8,194 → 7,214 B` と減った〔costGate〕。GL 込みの 1 回測定でも `text` が voidwave `0.72 → 0.59 ms`・surface `-13%`・media `-14%`。JMH `fullLayoutWarm` も `-8%`。②**不変**: コールド起動〔`531〜549 ms`〕・保持ヒープ〔`40.2 / 10.0 / 2.8 MB`〕・WPT〔`160 / 260`〕・`scroll`・`child`・`inherited`・`fontsize`・`open`・幅変更〔memo の個数も不変〕。C9 は配置の保持だけを変えたので当然。③**退行**: large-list-2000 の GL 込み `text` `+15%`・`hover` `+7%`〔旧比。3 回とも新が遅い〕・JMH `restyleInherited` `+6%`。カウンタ〔`MemoKpi`〕を無効にした版との A/B で `hover` は `-8.4%`〔3 回とも〕、`text` は差なし〔`-1.7%`〕＝カウンタが `hover` の退行の大半。`text` の残りは未特定。→ §3 C10。④**教訓（測り方）**: 同じコードでも**セッションが変わると絶対値が `±40%` ずれる**〔午前 `fontsize` 4 本 `10.76 ms` が午後 `14.3 ms`。旧版も同様〕。JMH も同じ。したがって時間の比較は**同時の交互 A/B**〔旧版の worktree と現在を 3 回ずつ交互。中央値〕だけを使い、絶対値は同じセッションの中だけで比べる。1 回ずつの測定の `±10〜15%` は揺れで、改善・悪化の判断に使わない〔今回も large-2000 の `text` が 1 回測定で `+26%` と出たが、A/B では `+15%`〕。⑤**計器を足すときの注意**: `LongAdder` の加算でも 1 箱ごとに入れると 2,000 箱の文書で `0.02 ms` 単位になる〔`hover` で `8%`〕。診断用の計器は既定オフ〔`static final` の分岐〕にして、必要な計測だけ有効にする。
§6-181. **計画の練り直し: ループは構造が原因（2026-11-23）**: C9a〜c と周辺の作業が「近道を足す → 費用を削る → 不具合 → フラグ・検証を足す」に入りかけた〔§6-174〜180〕。実数で裏づけた: ①記憶・無効化の仕組みが約 7、**無効化の言語が 3**〔測定＝読み取り集合の鍵・配置＝構造の純粋性フラグ・描画／スタイル＝印と世代〕。配置だけが手書きの述語で、C9 で状態を 5 つ足した。②回帰カタログの **26 / 69（38%）** が「古い結果が残る・記憶・増分・無効化」系。③検証クラスが 4、実時計を読むコア／共通のファイルが 17。④既存の 旧 `DESIGN_FRAGMENT_TREE.md`〔削除済み。要旨は DESIGN_INCREMENTAL_KERNEL.md 付録 A〕 §0.5 に**同じ結論〔手書きの退く条件の漏れ・M4 増分専用のコードを持たない・M6 まず影〕が既に書かれていた**のに、配置の記憶で同じ過ちを繰り返した。→ 運用の注意ではなく**構造と機械的な固定**で断つ。**決定**: 全段を測定と同じ**読み取り集合の鍵**へ寄せる K 系〔K0 足場・K1 台帳・K2 配置の鍵〔先にスパイク。届かなければ C9 の現状で凍結〕・K3 親相対・K4 窓＋インク境界・K5 パッチ撤去・K6 検証の統合〕。全面書き換えはしない〔K0 だけでも価値がある〕。**C9 は凍結**し、フラグ・検証クラス・近道スイッチ・1 箱ごとの計器の新規追加を禁じる〔K0 のラチェット〕。根拠の要: 配置が外から読むものは数えられる〔スクロール 14・包含ブロック 6・親の外寸 21・アンカー 13・float 22 の箇所〕うえ、測定の台帳 `MeasureReads` が同種の読み取りを既に 18 種追跡している＝配置を同じ方式に載せる土台がある。
§6-182. **〔不具合〕K0 の実装で分かったこと（2026-11-23）**: ①**フレームのオラクルが、表示に出ない増分の欠陥を見つけた**: `display` の型を変えた箱〔`layouts` 種 1 手 19 の `gr`〕のスクロール範囲〔`scrollHeight`〕が、最初から作った面より `2px` 大きいまま残る〔位置・大きさは一致。同じくスクロール範囲だけの差が種 3・8・14・16・20・21・23・29 にも〕。比較の既定からスクロール範囲を外し、既知として固定した〔`FrameOracleTest`・回帰カタログ「既知:」。直るとテストが赤になる〕。②**外れた仮説**: DOM を HTML に書き出して別の面で読み直す方式は、半数の手で偽陽性だった〔パーサが `table > div` などの不正な入れ子を直すので、DOM の API で作った木と別の木になる〕。構造のまま複製〔`cloneNode(true)` と `installDocument`〕へ改め、`layouts` 種 1〜1500 で偽陽性 0 になった。③**オラクルの使える場面は、結果が時間と対話の履歴に依らない場面だけ**: `transition: width` は途中の幅をレイアウトに使う〔履歴のある面と食い違う。`cards`・`nested` は対象外〕・ホバーとスクロール量は新しい面が持たない・`display:none` の部分木は古い値と未配置で食い違う〔観測できないので種類だけ比べる〕。表示項目は比べない〔途中の色・半径が時間で動く〕。箱の幾何が合えば項目の位置・大きさは合う。④**変異の作り方**: 保持を戻して欠陥を再現するにはコードへスイッチを足すことになり〔運用ルール 8 で禁止〕、代わりに**通知なしの変更**〔`attrsForWrite()` への直書き〕で「レイアウトが走るべきなのに走らなかった」状態を作る。5 形の場面で、通知つきなら一致・通知なしなら差が出る。⑤**時計の注入で `ResizeSettle` の `sleep` が消えた**: 止めた時計は所要時間 `0` と測られ、間引きが起きない〔反映は 1 フレーム〕。別の時計へ差し替えるとき、直前のレイアウトの記録〔別の時計の時刻〕を捨てないと間引きが固まる。⑥**計器**: `MemoKpi` の `LongAdder` は常時オンだった。`Kernel.stats` は既定オフ〔`static final`〕で、HEAD との A/B で `hover` `-5.6%`。ファイルの行数・メソッド数のラチェットは、`import` 1 行でも上限を超える〔更新モードで上げた: UiView `+3`・StandaloneView `+8`・RenderPipeline `+1`〕。⑦**層のラチェット**: `Clock` を最上位の `core` パッケージへ置くと、下の層〔`core.layout` など〕からの上向き依存とパッケージ循環になる。`core.kernel` に置いた。⑧**K0 と無関係の既存の失敗**: `TextShaperIntervalAndFontCacheTest#shapeIntervalCache…` は未改変の HEAD でも落ちる〔テストが `System.nanoTime()` の桁数で文字列を作り、折り返しの境界が変わる〕。直していない。
§6-183. **K2（配置を読み取り集合の鍵へ）の実装で分かったこと（2026-11-23）**: ①**既存の C9 でも起きていた `applyRelativeOffset` と `shiftSubtree` の不整合**: `applyRelativeOffset` が `clearLastLayoutX()` で `lastLayoutX = NaN` にしていたため、親 `#shell` が `shiftSubtree` で動いたときに相対子孫 `#in2` の座標移動が抜けた〔`invalidateValid()` で `F_VALID` だけを落とす形へ変更〕。②**未配置へ戻った子孫を `shiftSubtree` が動かす罠**: `MutationFuzz` の `layouts` 種 294 手 11 で、`#w1` が `fx→fl` に変わって `span#w2` がインラインへ戻り未配置になった `div#z7` を、後の `#w1` の平行移動で `0.0 → 14.953125` へ動かした。原因は `onReset(false)` が `lastLayoutX = NaN` に戻さず `isLaidOut()` が true のままだったことと、`isAllowedParent` が `outer=INLINE, inner=FLOW` の親を許していたこと。旧 C9 は `tryReuseOrShift` 末尾の `resetChildrenPlacements` 条件が `!sameInputs`（`lastLayoutX == parentX`）に頼っていたため `onReset(false)` で `lastLayoutX` を消すと `large-list:text` が `skipped 499 → 0` に壊れたが、K2 は `canKeepAtReset` を先に見るため `onReset` で常に `lastLayoutX = NaN` にして両立できる。③**原子的インラインと `voidwave text` `≤ 10`**: `voidwave.html` で `a#~39`（`inline-flex`）が `isInlineLevel()` で弾かれていたのを `BlockLayoutEngine.isAtomicInline` まで許可したことで `voidwave text` が `11 → 9`、`showcase text` が `14 → 8` になり、K2 の合否線 `≤ 10` を両文書で達成した〔`:core:costGate` 56 シナリオ全行で増加 0、`memoCeil=14` が消滅〕。④**原子的インライン保持時の断片・丸め整合**: `isAtomicInline` を保持対象へ入れたところ `BlockLayoutEngine.clearFragmentsRecursively`/`finishFragmentsRecursively` が内部の子（テキスト座標・断片）を消す問題と `shiftSubtree` が直前の `addFragment` を二重移動する問題（`RealDocumentFramesTest` `voidwave/resize700`・`guidebook/resize700`）、さらに `isShiftableDelta` で `84.00001f - 333.0f` が `-249.0f` に丸まり `lastCoord + d != parentCoord` になる 1 ULP ずれ（`WidthOnlyReuseTest` 種 101 `w=150`）を検出・修正した。⑤**`PlacementVerifier` の削除とオラクル統一**: `PlacementVerifier` が `staticX/Y` を親を走らせず消すため abs 直接保持と相容れず、設計方針どおり削除して `FrameOracle`＋`K2PlacementKeyTest` へ一本化し、`LayoutReadLedgerTest` も `ABSORB_IF_INSIDE_SUBTREE` 後の `sc`/`wrapScroll` の `PLACE_STICKY` 吸収へ更新した。
§6-184. **K3（親相対の配置と位置の解決の分離）の実装で分かったこと（2026-11-23）**: ①**座標解決の親（`originParent`）は flex/grid の inline 項目と `display: contents/none` を区別する**: `display: flex/grid` 内の `<span>` や `::before` は `display.isInlineLevel()` のまま自身の `layout` を通って子の原点になるため、`PlacementMemo.isInlineFlowForOrigin` が親の `FLEX`/`GRID` を除外しないと内部の `#text` 断片が外へ飛び出し（`WptReftestRatchetTest` 2 件と `GoldenFrameTest` 画素）、また `display: contents/none` は未配置のまま親の原点を足してはならない（`WebFidelityTest` `display-contents#contents`）。②**`#text` の `NODE` 座標の正規化とゴールデン**: 以前は `#text.x/y = 0`（絶対）のまま `DisplayListBuilder` が親の節の原点 `ox/oy` を引いていたため `#text` の `NODE` が `(-parentX, -parentY)` に出ていた。親相対の解決後は親の原点上の `(0, 0)`（絶対座標では親の左上。`DisplayListGoldenTest` の `showcase.txt`・`decorations.txt`・`flex-grid.txt` も同期更新）へ移り、可視画素・`GpuBaselineTest`・`WebFidelityTest`・`RealDocumentFramesTest` ヒットテストは全て不変。③**遅延消去待ちの `inlineFragments` と §6-182 の完全解消**: `inline` 要素が `flex`/`grid` 等へ変わった直後、`finishResetPlacements` の前に走る `ScrollExtentCalc.updateScrollExtent` が `child.fragmentsStale == true` の旧 `inlineFragments` を読むため `scrollWidth`/`scrollHeight` が壊れていた（`ServerClientLayoutParityTest` `b#bold` と `FrameOracleTest` §6-182）。加えて原子インライン内に `flex` へ変わった子孫があると `BlockLayoutEngine.clearFragmentsRecursively` が `fragmentsStale` を消した後に `finishFragmentsRecursively` が `holdsPlacement()` でスキップしていた（`IncrementalLayoutFuzzTest` `s2_1`）。両者を修正したことで `layouts` 種 1 手 19・種 3 手 4 の `scrollHeight` 差も消滅し、`FrameOracle.Scope` の既定を `GEOMETRY_AND_SCROLL_EXTENTS` へ引き上げた（`layouts` 種 1〜200 で差 0）。④**位置解決の費用**: 親鎖走査で `showcase:resizeexact:null` の A/B（3 回中央値）は `+7.0%`（`0.4781 → 0.5114 ms`、撤退条件 `+10%` 内。`large-list:child:null` は `-9.8%` で `0.3971 → 0.3580 ms`）に収まり、`LayoutBox` への遅延評価フィールド追加なしで完了、`ClassShapeRatchetTest`（`LayoutBox` 171 メソッド・1,805 行以下）も維持した。
§6-185. **〔不具合〕K4（窓の外の部分木の O(1) の代理）の実装で分かったこと（2026-11-23）**: ①**平行移動の費用は、記憶の鍵ではなく署名にあった**: K3 で箱は親相対になったが、署名〔`PaintStamps.signature`〕が親相対の `x`・`y` を含むので、挿入で後続の兄弟が動くたびに兄弟ごと印が進み、窓の外で空の断片まで作り直していた〔`child` の断片 miss `507`/f〕。署名を形と位置に分け、位置だけの変化は自身の印を進めず部分木の印だけ進める〔親は下りて確認する〕。窓の外で空の部分木は、箱の印と子の部分木の印が古く子の並びが同じで、移動後も外接矩形が窓の外なら再利用して正しい。②**空の断片は位置に依らないとは限らない**: 窓なしの文書で「断片が空＝平行移動で変わらない」と仮定すると `PathPredictionTest` が不健全 25 件〔予測は自分の入力が変わったと見る〕を出した。代理は窓付きの組み立て〔`cull`〕で、境界を持てる〔窓の判定が読む矩形だけに依る〕空の部分木に限定する。③**〔不具合〕K3 の穴（`FragmentPaint` の `kidsCtxOk`）**: 親 id が無い枠〔`cc.parentId == null`。子の項目は絶対座標〕で親〔例: `ul`〕が平行移動しても、K3 以降は子の印が進まないので `tryPillar2` が「変わった子 0」で子の断片を全て再利用し、窓付き＋位置指定の子孫を持つ行で、窓へ入った行の項目が欠け古い位置の項目が残った。既存のテストでは見つからず、K4 の「記憶あり＝最初から作った」の比較〔乱数の手順〕で再現した〔`HEAD` でも同じ失敗を確認〕。`kidsCtxOk` に親の位置の一致を足して直した〔鍵の側の直し。新しいフラグ・特例は足していない〕。④**確保の落とし穴**: 窓の外の境界を組み立てごとに `float[4]` で作ると `inherited` の確保が `+14%`、`for (c : children)` の反復子で `child` の確保が `+15%` になった。境界は平行移動が起きたときに初めて作り〔`PENDING` の印〕、子の走査は添字で行う。⑤**`InkBounds` が作れない子孫**: 位置指定・sticky の子孫は平行移動で動かない〔親の外の包含ブロックを持ちうる〕。行に分かれたインラインの箱自身は原点の親が箱の外にある。どちらも `null` を返し、代理を使わず従来どおり作り直す〔正しさを保つ〕。
§6-186. **K4b の着手前調査: 実文書 `hover` の確保 `250 KB` は表示リストの外（2026-11-23）**: `FineProfileMain hover voidwave.html 4000 gl` の JFR〔`-Pprofile.jfr`〕で確保を呼び出し元別に集計すると、`FragmentPaint` は約 6%、残りは描画側の `PaintSceneDriver`〔`rebuildIndex` 約 39%・`boundsFor` 約 30%・`build` 約 13–19%〕。`reconcileIndex` が毎フレーム〔ウォームアップ込み 1,600 回すべて〕失敗しており、原因は 2 要素 `~79`〔節＋背景〕と `~82`〔節＋背景＋丸い枠〕のホバーで項目が出入りして、項目数は同じまま間の項目が 1〜数個ずれること〔同じ位置で節と非節が入れ替わる〕。**同じ位置の鍵だけが違う「入れ替え」を許す試作は効かなかった**〔節と非節の不一致が毎フレーム。差分は位置ずれを含む複数区間の編集〕ので撤回し、**索引の作り直し自体を、前回の索引を鍵で引いて姿勢・補間状態を運ぶ形**にした〔`reconcileIndex` を一般化せず、`rebuildIndex` を 1 本の基準にした〕。運ぶ条件は構造〔種類・親・層〕と中身が同じ、または姿勢だけが同じ静的な項目。**描画上の平行移動〔`PaintOffsets`〕を持つ項目の姿勢を運ぶと古い姿勢が残った**〔`~67_scrollv` の `f=141` と `110`。平行移動の同期が差を見る基準が 0 になるため〕。`RealDocumentFramesTest`・`HoverPatchKeepsScrollNodeTest` が検出し、持つ項目は運ばないで解決。可変のリスト・同じリストの書き換えは前回の中身が当てにならないので運ばない。確保の内訳は表の毎回の作成〔`Long2ObjectOpenHashMap`〕と範囲の表〔`boundsFor` の `double[]`〕。残りは動く項目の姿勢〔voidwave は常時アニメーション〕で、`inherited` の 1 miss の費用と合わせて K4b の残り。
§6-187. **K4b `inherited` の調査と位置解決の持ち回し（2026-11-23）**: ①`varied-list-2000 inherited` の表示リスト CPU〔JFR〕は `LayoutBox.x()/y()` の解決〔`PlacementMemo.resolveX/Y`＋`isInlineFlowForOrigin`。祖先の連鎖を箱ごとに足し直す〕が約 35%で最大だった〔K3 の位置解決の費用〕。箱ごとに x・y を 8 回ほど解決していた。②`FragmentPaint.build` が親の原点〔`LayoutOrigin.originX`〕を深さごとの表で受け、自分の絶対位置を 1 回だけ作って記憶の鍵・窓の判定・`kidsCtxOk` へ渡す〔解決は子へ渡す原点＝親の原点＋親の寄与の再帰で、規則は `resolveX` と同じ。足す順が逆なので浮動小数の最後の桁だけ違いうる。記憶の鍵と窓の判定にしか使わない。親の枠の原点の丸め `pox`・`poy` は元の `x()` のまま〕。JFR の自己時間で `resolveX` が `24% → 3%` に下がり、A/B で `inherited` varied-2000 `-20.9%`。③**JFR の割合は A/B より大きく出る**: 最初の試作〔親の原点の持ち回しのみ〕は JFR で `x()` が `35% → 6%` に見えたが A/B は `-2.6%`〔7 回〕。時間は交互 A/B でだけ判断する〔運用ルール 9〕。④ラチェット: 行数・メソッド数の上限を超えたので `LayoutOrigin` を別の型にし、`LayoutBox` に足さなかった。⑤残りの 1 miss の費用は一つの山が無い〔`StyleFacts` の再計算 約 10%・`selfViewportSensitive` 約 8%・子の並びの照合 約 7%〕。`StyleFacts` は再スタイルのたびに新しい `ComputedStyle` で作り直される。次は、箱の前回のスタイルと facts の入力の宣言が同じなら引き継ぐ〔宣言表で照合。鍵の方式〕。
§6-188. **K4b の撤退（2026-11-23）**: 計画書の撤退条件〔1 miss の費用が構造で下がらなければ A/B `±3%` の保守だけ〕を適用した。構造で下がった山は 2 つ〔位置解決 `-20.9%`・索引の運び〕。残りは 1 箱あたりの小さな費用の分散で、`varied-list-2000 inherited` の表示リスト CPU の自己時間は `StyleFacts` の計算〔再スタイルで新しい `ComputedStyle` になるたび。宣言が同じスタイルは共有されるので行ごとに違う文書でだけ効く〕・`selfViewportSensitive`・`sameKids`・`treeTop`〔`store` ごとに根まで辿る〕・`isValueControl` の `toLowerCase`。**`isValueControl` の `toLowerCase` を大文字が無ければ避ける直し〔JFR 自己時間 3.4%〕を入れて A/B `+1.0%`〔varied-2000・5 回〕・`+10.8%`〔varied-500・揺れ〕で効果が見えず、撤回した**〔JFR の自己時間は A/B の時間に出ない。§6-187③〕。`StyleFacts` を前回のスタイルから引き継ぐ案は、宣言のバンドの照合〔`ensureBandSlice` の比較〕が計算より重く、ハッシュだけの鍵は衝突の安全性を保証できないので見送った。`treeTop` の持ち回し〔`LayoutOrigin` と同じ形〕は約 `3%` で `FragmentPaint` の行数の上限〔800 行〕に当たるため、型を切り出す必要があり費用に見合わない。
§6-189. **K4・K4b が守れたことと残った 1 本化の綻び（2026-11-23）**: 守れた: `F_*` フラグ・`Shortcut`・`*Verifier`・1 箱ごとの計器・実時計の読みは足していない〔ラチェット緑〕。再利用は「鍵が同じなら再利用」の規則の中に入れた。**綻び**: ①表示リストの再利用に 2 つの条件が並ぶ〔`subtreePaintStamp() <= epoch` と、窓の外の平行移動だけを許す `onlyPlacementChanged`。後者は実質第 2 の近道〕②索引の更新が 2 経路〔`rebuildIndex` と、差分が小さいときの `reconcileIndex`〕③`Memo.ink` の状態が番兵の配列で暗黙〔遷移が `store` と `translatedOutsideWindow` に分散〕④`FragmentPaint.Memo`〔`ink` 1 欄増〕・`LayoutBox`〔`layoutPosSig`〕の欄数がラチェットの対象外。→ §3 K4g〔状態機械の明示・判定の 1 本化・欄数のラチェット。索引の統合は K6〕。**GPU への移植の判断**: 描画は GL/Vulkan で GPU 済み。残る CPU の計算は分岐とポインタ参照が主で、GPU の計算シェーダーに向かない〔`hover` 0.1 ms 前後では転送・起動の費用が勝つ〕。窓外の判定・姿勢の合成・ヒットテストは、項目が数万・数千を超える用途が出たら候補〔要 GL 4.3 以上か Vulkan の計算キュー〕。今は CPU のメモリ読み出しの削減〔K4f〕が先。
§6-190. **K4g の実装で分かったこと（2026-11-23）**: ①番兵の配列〔`UNCONSTRAINED`・`PENDING`・`NONE`〕の暗黙の状態を、型〔`InkState`〕にすると、遷移が「記憶を作る時」と「平行移動が起きた時」の 2 つに閉じ、`outside` が真になる状態が FREE と BOUNDED だけだと網羅テストで言える。②**再利用の 2 条件は、位置と部分木の 2 つの鍵に分けられる**: 位置〔`sameContextCheap` の `x`・`y`〕と部分木〔`subtreeKeySame`〕。窓の外の平行移動はどちらにも同じ状態 `InkState` が効く。判定は 1 つの言語になったが、位置の鍵と部分木の鍵が別の関数なのは残る〔K6 で記憶の鍵に統合〕。③ラチェットは正規表現の検出器なので、テストのソースの Java のエスケープ〔`\w`〕を書き込む作業で 2 回壊した〔書き込みは heredoc とファイルの経由でなく、直接の編集で行う〕。
§6-191. **K4d の実装で分かったこと（2026-11-23）**: ①**往復の `inherited`〔color を 2 値で交互〕では派生値は再計算されない**: 新しい `ComputedStyle` が 2 フレーム前のスタイルの共有に当たり、`derivedFacts` ごと戻ってくる〔`buildFacts`・`factsOf` は全 300 フレームで 6k 回〕。引き継ぎが効くのは値が毎回新しい場合〔`color` の遷移など。新設の `inheriteduniq`〕で、そこでは `factsOf` が `1.6M` 回あった。時間の A/B は `inheriteduniq` で取る。②**「`StyleFacts` と `selfViewportSensitive` の約 18%」は同じ物**: `selfViewportSensitive` の費用は `PaintOrder.factsOf(...).hasAnimation()` の再計算で、`usesViewportUnits` は再スタイルが `byte` で持つので統合の必要がなかった。③**残り**: `css.StyleFacts`〔`ComputedStyle.facts()`〕は `inheriteduniq` で 1 フレーム約 5.4k 回作り直される。継承する値〔フォント・`line-height`・`direction`・`white-space`〕と `elementFontSize` も読むので、引き継ぎの鍵は非継承バンドだけでは足りない〔バンド 2 の参照・`extras`・フォントサイズも要る〕。費用は未測定〔JFR の割合は A/B に出ない。§6-187③〕。④ラチェット〔`ComputedStyle` の行数・メソッド数〕に当たったので、相対単位の走査 3 メソッドを `RelativeUnitScan` へ切り出して先に縮め、その分で足した。
§6-192. **K4e の実装で分かったこと（2026-11-23）**: ①**GL で `SoftwareRenderBackend.buildClip`〔確保の約 16〜24%〕が出るのは、CPU への落ち先〔フォールバック〕の描画ではなく、GL の描画も `CpuLayerFallback.renderOnto` 経由で `SoftwareRenderBackend` が項目を走査するから**〔`GPUCOVERAGE declined=0`・`declinedByKind: (none)`。voidwave `hover` で GPU が断った図形は 0〕。クリップを作る費用は、再生〔`ReplayKeys.sameItemAndPose` は `Resolved` の参照で比べる〕に当たらない項目の数で決まる。②**voidwave の姿勢の作り直し `674`/f の約 45% は、前回と値が全く同じ**〔姿勢・不透明度・残り・グループが同じで、補間値〔`AnimatedValues`〕の参照だけが違う〕。原因は、補間値の参照だけが毎フレーム変わる節〔`hue` のような色だけ・同じ値の変形が動く節〕が毎回新しい `Resolved` を作り、子孫の「前回と同じ親か」が参照の比較だったため連鎖していたこと。値で比べると連鎖が切れ、確保 `-31%`・再生が `4,000 → 489k`/2,000f へ。③残りの約 `350`/f は本当に動く節〔`drift`・`pulse`・`bars` など〕の子孫で、値が違うので作り直しが要る。窓の外の項目の姿勢を作らない案は、窓の外かどうかの判定自体が姿勢を要する〔`outsideClip`〕ので、動く祖先の下では使えない〔合否線を満たしたので見送り。姿勢の連続配列化〔`float[]`〕も同じく見送り〕。④診断は `JFR` の確保サンプルを工程ごとに集計し、一時的な計数〔作った節・値が同じだった節〕で確かめた〔計数は戻した〕。
§6-193. **K4f の実装で分かったこと（2026-11-23）**: ①`treeTop`〔`store` ごとに根まで辿る〕と `sameKids`〔`List.get` の添字検査＋型変換〕を型と配列照合へ寄せても、varied-list-2000 `inherited` の 1 フレーム全体は `-0.5%`〔交互 5 回・3,000 フレーム。1 回ずつは `±2%` 揺れる〕。JFR の自己時間の 4%・7% は呼び出し側の分岐・メモリ待ちに吸収され、全体の時間には出なかった。**K4b と同じく「1 箱あたりの小さな費用の分散」は時間では見えない**ので、型の切り出し〔行数の上限の余裕・下降状態の 1 か所化〕の価値だけを残した。②`-Dverify.oracle=true` では `PathPredictionTest`・`FloatContainmentMemoTest` が HEAD でもスクロール範囲の差で赤い〔レイアウト側。§3 K0b ②の対象〕。③`HtmlNode` のメソッド数・行数の上限が詰まっていたので、`treeTopBelow` は `AttributeMap` の静的関数にした〔`listed` を同パッケージ可視へ〕。
§6-194. **`CatalogApp`（3,000 カード・36,063 箱）の実機スクロールカクツキ検証（2026-11-24）**: 実機 GL で ①純スクロール ②1 秒タイマー併用 ③マウスホバー併用の 3 条件を計測し、カクツキ（`80〜707 ms`/f）の 5 原因を特定した。①**窓シフト時の全記憶消去**: `SCROLL_TRANSLATE_LIMIT`（`576px`）を超えるたび `UiView.rebuildWindowAtBoxFrame` が `windowShift` を立て、`DisplayListBuilder.memoFrameKey` が `Integer.MIN_VALUE` を返して `FragmentPaint.memos` を全消去するため、約 15 フレームごとに `fMiss=36,044`・`81〜275 ms` のスパイクが発生する。②**スクロール中の再配置・再描画で窓外 `36,008` 箱の `InkState` 代理が 100% 不発**: `.scroller` のスクロール後に `repositionLayout` 等が走ると `<main>` と `#list` の絶対 Y が `-scrollY` だけ動くため、`#list` の `buildFresh` で `kidsCtxOk`（`m.y == stack.ay`）が偽になり、子の各 `.card` でも `sameContextHeader` の `Math.round(p.y()) != m.poy` で即座に棄却されて `translatedOutsideWindow`（`InkState`）へ到達しない（`fHit=12, fMiss=36,008`、毎フレーム `85〜184 ms`）。③**`repositionLayout` の全箱リセットと `tryTextPatch` のデッドコード**: `RenderPipeline.repositionLayout` が `reuseMeasurements=false, keepRun=-1` で全 `36,063` 箱をリセット・再配置（`lSkip=0`、`~19.5 ms`）し、さらに `LocalPatches.tryTextPatch` は `List<DisplayItem> elements = null` が `try` 内で一度も代入されず `if (stale && elements != null)` が不発で毎回 `ensureLayoutFresh`＋`paintBoxes`（`28.3 ms`）＋`publishBuilt`（`36.7 ms`）へ落ちる。④**`.card:hover` の `box-shadow .15s` 遷移**: 非コンポジタ属性の `box-shadow` 補間で `StyleAnimator` が 150ms 間毎フレーム `requestRestyle` を発火し、②③と連鎖してスクロール中のホバー通過で毎フレーム `90〜140 ms` 化する。⑤**初回無窓フレーム**: `new StandaloneView` 時の空 `#list` で `lastOwnerCount ~ 25` が記録されるため、`wire()` で 3,000 枚追加した直後のフレーム 0 が `windowed=false`（`138,081` 項目・`964 ms`）で組まれ、次フレームで `windowed=true` へ反転して `memoFrameKey` 不一致で `707 ms` 化する（→ §3 **K4h**・**K5**）。
§6-195. **〔不具合〕オラクルで落ちていた 2 件（`FloatContainmentMemoTest`・`PathPredictionTest`）の原因と直し（2026-11-24）**: ①**内在の幅〔min/max-content〕が `box.horizontalDecoration()`〔前回の測定が置いた `pL`・`bL` など〕を読んでいた**。測っていない箱は 0、使い回した箱は前の値で、shrink-to-fit の親〔float になった body〕の中の fieldset の幅が最初から作った面と違った〔62 と 56〕。全面の再レイアウトでも直らない〔箱の値が残る〕ので無効化の漏れではなく、入力の読み違い。`intrinsicInlineMargins` と同じく**スタイルだけから**求める `IntrinsicSizing.inlineDecoration`〔% は 0。表は重ねた枠線のため箱の値〕へ。②`ScrollExtentCalc` が、断片を持たず幅 0 の文字〔空白だけで畳まれた文字〕の箱の高さ 1 行ぶんをスクロール範囲へ足していた〔最初から作った面だけ。増分では最後のブロックを消すとその箱が残らない〕。③箱の木の作り方の差〔最初から作ると子が空白だけのブロックに箱が残る〕は観測できないので、`FrameOracle` の箱の比較から数えない〔`invisibleText`〕。固定: `IntrinsicAndScrollOracleTest`・`-Dverify.oracle=true` の全件緑。フラグ・状態欄の追加 0。
§6-196. **K5 第 1 段で分かったこと（2026-11-24）**: ①`repositionLayout` は `reuseMeasurements=false`・`keepRun=-1` で毎回全箱の配置を捨てていた〔K2 の鍵・K3 の親相対座標が効かない唯一の入口〕。`keepRun` を今回の run にし `reuseMeasurements=true` にするだけで、スクロールの量に依らない箱の配置が `PlacementMemo.tryReuse` の鍵〔`sameScroll`・`deps`〕で使い回され、`machine_ui scroll` が `-91%`。既存の全テストは無改造で緑で、新しい検証 `RepositionReuseTest` も旧実装で緑〔旧実装との同値を確かめた上での変更〕。②**スクロール中の表示リストの座標は、最初から作ったものと項目の座標では比べられない**〔スクロール内容の節の移動量が遅延して適用されるため、`renderCache` の NODE の座標が違う〕。比べるなら最終の画素。`FrameOracle` へスクロール量の複製を入れる〔K0b ②〕ときも、箱の座標でなく画素の比較にする。③**最初の版は `scrollOnly` を区別せず全再配置を使い回しにして、根の `:test` の 4 件〔`IncrementalPipelineTest`・`ServerClientLayoutParityTest`・`LinuxDesktopScreenInputTest`・`ReactiveStateStoreTest`。ドラッグ＝`left`・`top` のスタイル変更〕を壊した**。配置の鍵は箱自身の inset を読まないので、位置の指定が変わる再配置は同じ入口でも使い回せない。入口に `scrollOnly` を渡し〔`UiView.repositionFromLayout(scrollOnly)`・遅延したスクロールの決め直し〕、公開の 8 引数版は従来どおり全箱を捨てる。`core` だけの `:core:test` では見つからず、**Linux の `ci-linux.ps1`〔ルートの `:test` を含む〕で見つかった**ので、配置・位置に触れる変更は `:test`〔ルート〕も回す。④`STALE_TEXT_PATCH` は `elements != null` が一度も代入されず、実質 `ensureLayoutFresh` の後ろへ回るだけだった〔§6-194 ③の指摘どおり〕。撤去しても結果は旧実装と同じ〔`StaleTextPatchTest` は旧・新どちらでも緑〕。⑤`LocalPatches` の残りの 4 スイッチは、文字・属性・子の増減の入口を `restyleAndUpdate`〔汚れの印＋`tryRelayoutInPlace`〕へ寄せる設計〔文字の節の変更を「変わった箱」として `layoutChangedBoxes` に載せ、`CHILD_PATCH` は構造の変更として再構築〕で消す。性能の退行が出やすいので、`textPatchHits` の `costGate` 行〔`text` 系 14 行〕を A/B で守りながら 1 つずつ行う。
§6-197. **K5 第 2 段で分かったこと（2026-11-24）**: ①**文字の変更は、旧「文字パッチ」と「再スタイルの変わった箱の測り直し」が同じ処理**〔`refreshStyle` → `relayoutInPlace(変わった箱)` → 表示リストの作り直し〕で、違いは変わった箱の集め方だけだった。集め方を `TextChanges` に出し、測り直しを 1 つの入口にした。②**統合の最初の版は、文字と位置の指定〔`left`・`top`〕の同フレームの重なりで位置の再配置を飛ばした**〔`PlacementKeepAbsoluteTest` の fresh 側が重なりを作るので検出〕。文字の経路の後ろに、位置・属性の分岐を続ける。③**確保の退行は 2 つ**: 文字の集めに `IdentityHashMap` の集合を作っていた〔約 500 B。`changed` は集合から出すので重複しない〕、`InteractionState.hasDirtyNodes`・`styleDirtyOnlyIn` が汚れ印のある文書で全節を走査して `Integer` を作る〔約 3 KB。`surface_test` のように毎フレーム汚れた節がある文書で出る〕。判定は個数の帳簿だけの O(1)〔`mayHaveDirtyNodes`〕にし、残っていれば再スタイルを走らせる〔旧の重なりと同じ〕。④**`CHILD_PATCH` を通常経路へ寄せると `large-list child` が `0.29 → 0.50 ms`・確保 `33 → 317 KB`/f**〔`-Denderframe.ui.nochildpatch=true` との比較。記憶の回数は同じ: 配置 5.5・断片 miss 5〕。全再計算の箱の木の作り直しが、変わっていない 500 行の子リストも組み直すため。撤去には、きれいな部分木をそのまま返す再利用が要る → §3 K5b。⑤`-Dverify.oracle=true` の全件・ルート `:test`・`costGate` を通した。
§6-198. **K5b 第 1 段で分かったこと（2026-11-24）**: ①`CHILD_PATCH` を切った `large-list child` の確保の出どころ〔JFR〕は `BoxTreeBuilder.fixupTable`〔`items.stream().noneMatch`〕`40%`・`wrapInlineRuns` `24%`・`collectItems`〔子の反復子と `ArrayList` の伸長〕`13%`。500 行の親の子リストごとに `stream` のパイプラインと反復子を作っていた。除去で `317 → 151 KB`、先頭確保・添字ループで `→ 90 KB`。②**それでも時間は `0.43 ms`〔patch `0.30`〕**: CPU の差分は全木の走査の分〔`UiView.recomputeLayout` 内の `RenderTreeBuilder.buildLayoutTree` `21%`・`BoxTreeBuilder.buildBox` `17%`・`LayoutBox.measure` の照合・`markLayoutChanges`〕で、記憶の回数は patch と同じ〔配置 5.5・断片 miss 5・測定 計算 3〜6〕。つまり**費用は「変わらない箱を訪れる」こと自体**で、定数倍では詰まらない。③`ComputedStyle.keywordOf` の名前引き〔`CssPropertyId.of`〕を添字へ替える試作は効かず戻した〔CPU の `18/200` は呼び出し側の全箱走査の一部〕。④部分木を飛ばすには「触れられていない部分木」を知る必要があるが、**入口別の集合〔`pendingChildParents` と同種〕や `CHILD_PATCH` の成立条件の共有では、パッチの別名になる**〔最初の案。却下〕。現状は変更の入口が 6 つ〔文字・子・属性の集合・汚れの 3 ビット・水準・構造の版〕で、「下に変更がある」を段ごとに別の仕組みで持つ〔`DIRTY_BELOW` は祖先へ伝播するフラグ〕。→ 変更を追記するだけの台帳 1 本と、全段共通の「触れられていない部分木は辿らない」の 1 規則へ一本化する〔設計書 §3.6。L0〜L4〕。⑤性能の合否線 ±10% を満たさないまま `CHILD_PATCH` を消さない〔運用ルール 10〕。**⑥フラグ・集合・パッチ条件の「共有」で近道を作らない**〔ユーザーの指摘。目標は 1 本化と状態機械〕。
§6-199. **K5b L0 で分かったこと（2026-11-24）**: ①**`DocumentState`〔`dom.node`〕を通す**: `HtmlDocument` は `InteractionState`〔上の層〕を直接参照できず、口 `DocumentState` 経由。台帳の型 `ChangeLedger` も `dom.node` に置き、口に `changeLedger()` を足した〔層の向きのラチェットに従う〕。②**入口の分類は 1 か所で決める**: `nodeChanged(節, null)` は文字の節なら `TEXT`、**要素の内容の差し替えは箱の木には子の増減として効く**ので `CHILDREN`〔最初は `ATTR` にして、観測テストが `pendingChildParents` との食い違いを見つけた〕。属性名のある変更は `ATTR`。描画だけの属性かどうかは**読み手の導出**〔L1〕で、台帳には事実だけを載せる。③**既存の入口には残りかすがある**: `<style>` の変更で全体の再レイアウトへ入る経路は `pendingChildParents` をフレーム後に取り除かない〔台帳は凍結で空になる〕。観測は「フレーム後に残った分は今の変更ではない」として除く。L1 以降でその入口を消せば自然に無くなる。④**凍結は配列の入れ替え**: 毎フレーム `Arrays.copyOf` で凍結すると A/B `+2〜3%` だった〔確保〕。入れ替えにして `+1〜2%`。残りは A/A の揺れ〔`±1%`〕と区別がつかない水準で、見積り〔5 件 + 凍結 1 回 ≒ `0.2%`〕と合わせて許容とした。⑤`static final Kind[] KINDS` は `StaticStateRatchetTest` が分類を求めるので持たない。
§6-200. **K5b L1 で分かったこと（2026-11-24）**: ①**台帳を落としても表示は壊れない**: 文字の変更の台帳の追記を落とす変異〔`freeze` を 2 回〕でも、結果は最初から作った結果と同じだった。`markDirty` の汚れ印〔`StateFlags`〕が別経路で文字の箱を更新するため。つまり**入口はまだ 2 つ**〔台帳と汚れ印〕で、台帳が文字の「測り直しの経路」に乗せる唯一の入口になっただけ。汚れ印の撤去は L3。変異の殺しは表示の一致でなく `TEXT_PATCH_HITS` の増減〔経路に乗ったか〕で固定した。②**あふれ・節を絞れない要素の分離**: L0 は「節の無い追記」で全体を `whole` にし、以降の個々の追記を捨てていた。L1 の読み手は同じフレームの文字・属性の変更を読む必要があるので、節を絞れない要素〔`ENV`・`SCROLL`・`appendWhole`〕は 1 要素として個々と並べ、**捨てるのは 4,096 件を超えたあふれだけ**にした。`touches` は節を絞れない要素があれば全体に効く。③**`markAllDirty` はフラッシュの中でも呼ばれる**〔`layoutDocument` のメディア変更〕ので、台帳へ「全体」を足すと次フレームの凍結に載る。個々を捨てる「あふれ」と混ぜると次フレームの文字の測り直しが全体の再レイアウトへ退く〔無駄〕ので、`appendWhole` は節の無い `STYLE` 要素にした。④**属性の分類の移動**: 「描画だけの属性か」の判定はリスナー〔書き手〕から読み手〔`flushInvalidation` の `paintAttrs` 呼び出し〕へ移した。台帳に載るのは事実〔節・種別・属性名〕だけ。
§6-201. **〔不具合〕混在の内容で、インラインの途中へブロックを差し込むと項目が二重に出た（K5b L2 の調査。K4 以前から存在・2026-11-24）**: `<div>lead <b>bold</b> <div>r0</div>tail</div>` の `<b>` の前へブロックを `insertBefore` すると、表示リストに `b` の項目が 2 組出た〔`mixed`〔匿名ボックス〕の古い断片が当たった〕。**差分でない基準〔`incrementalLayout(false)` の面・記憶の無い `DisplayListBuilder`〕と比べて初めて見えた**〔旧 `ChildPatchTest` は基準も差分だったので同じ不具合が両方に出て一致していた〕。原因: 子を足した**作りたての匿名ボックス**は、親へ付く前に `addChild` が `touchSubtree()` → `PaintStamps.mark` を呼び、`push` が箱から親を辿った根〔親が無いので自分自身〕の待ちリストへ積む。文書の根の待ちリストしか `settle` が確定しないので、`layoutDirty` が立ったまま取り残され〔`mark` は立っているビットを見ると積まない〕、その匿名ボックスが次に再利用されて子が減っても構造の印〔部分木の印〕が付かず、`FragmentPaint` の記憶が古いまま当たった。直し: 親にぶら下がっていない箱〔`parent == null` かつ節に親がある＝文書の根でない〕には印を付けない〔作りたての箱に描画の記憶は無く、最初の配置で付く〕。再現: `AnonymousBoxPaintMemoTest`〔記憶の無いビルダーとの比較〕・`ChildPatchTest`。**教訓**: 差分の検証は、基準も差分だと同じ不具合を隠す。基準は差分を切った面か記憶の無い面にする。
§6-202. **〔不具合〕隣接・一般兄弟結合子（`+`・`~`）の規則が、先行する兄弟の増減で後続の兄弟を再スタイルしなかった（K5b L2 の調査。K5 以前から存在・2026-11-24）**: `.sib > .row + .row { margin-top: 9px }` で先頭の子を `removeChild` すると、新しく先頭になった子が前の余白 `9px` のまま残った〔算出スタイルそのものが古い。レイアウト・描画の記憶ではない〕。原因: `InvalidationSets` の「構造の変化の範囲」〔`structure`。子の挿入・削除で汚す範囲〕に入るのが `:empty`・`:nth-*` だけで、`+`・`~` が無かった。クラスの変化〔`byClass`〕は `compose` で後続の兄弟へ広がるが、**要素そのものが増減する変化は別の入口**。直し: セレクタに `+`・`~` が 1 つでもあれば、構造の変化の範囲へ `FOLLOWING_SIBLINGS_SUBTREE`〔後続の兄弟と子孫〕を足す。再現: `SiblingCombinatorInvalidationTest`〔差分でない基準 `FrameOracle` との比較〕。もう 1 つの原因: 要素ごとの算出スタイルの記憶〔`StateStyleMemo`〕の鍵が自分の属性・子の数・状態だけで、先行する兄弟・位置に依る規則〔`+`・`~`・`:nth-*`・`:empty`〕の結果が変わっても当たり続けた〔1 度子の増減を反映した後の 2 度目の削除で再現〕。直し: 兄弟・位置を読む規則があるシート〔`InvalidationSets.structureSensitive()`〕では記憶を使わない。**旧 `ChildPatchTest` が見逃した理由**: 基準も差分レイアウトだったので同じ古さが両方に出て一致した〔§6-201 と同じ教訓〕。
§6-203. **K5b L2 で分かったこと（2026-11-24）**: ①**通常経路の費用は「触れられた親の子を 1 つずつ確かめる」定数倍**: 子の増減を台帳から部分構築にすると、変わった親の子 `500` 件を `RenderTreeBuilder`〔`keepFast`〕・`BoxTreeBuilder`〔`collectItems`・`fixup`・`replaceChildren`〕が 1 回ずつ辿る。`large-list child` の内訳〔ms〕: 再スタイル側 `0.057`・箱の木 `0.049`・測り直し/配置 `0.21`〔旧 patch は全体 `0.30`〕。②**`<li>` の子を飛ばせない版が最初にあり、辿った箱が `~508` になって全く速くならなかった**: 番号を使わない `list-style-type`〔`none`・`disc`・`circle`・`square`〕の項目は兄弟に依らないので飛ばしてよい。③**`replaceChildren` の落とし穴 3 つ**〔どれも全体の経路で見つかった〕: 並びが同じでも子自身の `isAbsolute` 等は変わる〔`recomputeChildFlags` は常に〕・別の親から移ってきた箱は残した子でも親の指しが古い〔全部付け直す〕・構造の版・描画の記憶の更新は組み直した箱で毎回進める。④**匿名ボックスは辿った箱に入らない**: 親の子の付け替えで匿名ボックスの中身が変わるのに測定の印が付かなかった〔`K2PlacementKeyTest` の `nested/27` で発見〕。`collectChanged` が辿った箱の下の匿名ボックスも見る。⑤**測り直しの調査〔旧 patch と同じ 3 段で比較〕**: 旧は組み立て `0.023`・測り直し `0.140`・描画 `0.115`、新は `0.112`・`0.164`・`0.115`〔`large-list`〕。測り直しの差の正体は 2 つで、**表示なしの `head`/`style` を毎回「辿る」〔`intact` が `display:none` を拒んでいた〕→ 測られない箱が変更扱いで毎フレーム測り直される**ことと、**新しい未測定の箱を個別に変更扱いして部分木の全体印付けをする**ことだった。どちらも直し、測定回数は旧と同数〔`3600`〕。残りは子 500 件を辿る定数倍。⑥**次の手**: 触れられた親の子を O(件数) で辿る部分を、台帳の `touchedChildren` と前回の並びから「前後を飛ばして間だけ」〔`replaceChildren` の前後比較を `RenderTreeBuilder`・`BoxTreeBuilder` の前へ〕にする〔設計書 §3.6 L3 の汚れビットと合わせる〕。
§6-204. **K5b L2b の調査〔定常状態〕（2026-11-24）**: ①**`+10%` ゲートの 3000 フレームは判定に使えない**: 30000 フレームでは旧パッチ `0.170` → 現 `0.217 ms`〔`+27%`〕・`varied-list-500` `0.220` → `0.307`〔`+40%`〕。旧は温まるほど速くなる〔ウォームアップではなく構造の差〕。`text` は一致〔`±0`〕。②**差は箱の木の組み立て段に集中**〔段ごとの突き合わせは設計書 L2b〕。測り直し・描画は旧と同じ仕事。③**子の一括の再利用の実験は不発**〔並びを空にして作り直す構造のまま中で飛ばしても `+3.5%` 悪化。`touchedKids` が使われるようになっても速くならなかった〕→ 費用は「作り直す」構造そのもの。破棄。④**案**は設計書 L2b〔親ごとの子の差分を 1 つ導き全段が読む。守る条件・撤退条件つき〕。 ⑤**実装結果**: `RenderTreeBuilder` が親の子の差分 `{前, 後ろ}` を導いて前後の箱を並べ直さず〔`splitKids`〕、`BoxTreeBuilder` が同じ差分を `ChildWindow` で前回の組み立て済みの並びと確かめて間だけ組む。3000 フレーム `+6.3%`・`+5.0%`、30000 フレーム `+8.9%`〔`large-list`〕・`+13.9%`〔`varied-list-500`〕、`costGate` 基準は不変。不具合 1 件〔先頭・末尾の空白の文字の扱いを見落とし、`p` に block を足すと食い違った。`FrameOracleTest` の乱数列で発見〕。残る `varied-list-500` の差は測り直し・描画側〔組み立てではない〕。
§6-205. **K5b L5 の調査: 子の数に対する傾き〔根本の設計の見直し〕（2026-11-24）**: ①`child` を行数 `500`・`2000`・`8000` で測ると、**旧パッチも現在も同じ超線形の曲線**〔旧 `0.211`・`1.15`・`7.53 ms`／現 `0.230`・`1.38`・`7.89`〕。旧パッチに追いつく作業〔L2・L2b〕は合格線として不十分で、本体は**下流の段が親の子を全員辿る**こと。②8000 行の内訳: 測り直し `30%`・配置 `16%`・描画 `35%`・組み立て `6%`〔JFR。子 1 件あたり約 `1 µs`〕。③**設計**は設計書 §3.7〔窓の伝播: 差分の窓を測り直し・配置・描画へ。前は辿らない・後ろは平行移動のみ・座標は当面絶対〕。⑤**既存設計との比較**〔設計書 §3.7〕: アーカイブ §7.3・§8.4・§8.6 が同じ結論〔後続の `y` 更新が律速・Fenwick の累積和〕を既に出していた〔未実装〕。初版の「後続の平行移動」「座標は絶対」は誤りで撤回〔`shiftSubtree` は K3 で削除済み・座標は親相対・丸めの一致〕。`VirtualFlowState`〔Fenwick〕は本体にあるが `content-visibility: auto` 限定。方針は〔窓の伝播〕＋〔後続の位置を累積和で解く〕。④合格線は「旧パッチを超える」〔500 行が旧以下・8000 行が旧の `1/3` 以下〕。段取りは W0 計測の固定→W1 測り直し→W2 配置→W3 描画。

---

## 付録 4: 旧 §4 の古い完了行（2026-11-23 に本編から移設）

| ID | 内容 | 効果・成果 | 主な検証テスト |
| :-- | :-- | :-- | :-- |
| S7 | 下流→上流モデルへの逆流書き込み撤去と PDU 不変契約の回復: ①`LayoutBox` の描画側書き戻し〔`movingMemoEpoch`・`movingMemoResult`・`paintCulled`・`dlStart`・`dlEnd`・`dlOrder`・`clearDlSlice()`・`withPaintBounds()`〕を全削除し、`PaintOrder` の走査中 `culledStack` と L5 局所 `PaintBounds`〔`BoxDecorationPainter`・`BoxDecorations`・`ControlPainter`〕へ置換 ②〔既完了〕`css.TextStroke` ③`HtmlNode.{interactionOwner, interactionFlags}` を削除し、`DocumentArena`〔静的 ID の世代保持・`hasSlots`・`migrateSlots`・`NodeSlots` の `VarHandle`＋`Slot` によるロックフリー読取〕と仮想節専用 `VirtualNodeSlots`〔`WeakReference`＋`ReferenceQueue`、および `HtmlNode.isVirtualNode()` の仮想節子孫対応〕へビュー別所有権を分離。さらに `PaintOffsets.snapshot()`〔`coords` 統合・`keysVersion` 単位の `snapIds`/`snapKeys` 再利用・`setIfActive()`〕・`FrozenItemList`・`PaintFrame`・`RenderBackend`/`StaticFrameGate` の `PaintFrame` 口・`StandaloneView.frameSnapshot()`・`UiView.paintOffsets()`・`PaintRequest` でフレーム境界の不変 PDU を確立 | `LayoutBox.pubMut` `40→34`・`methods` `174→171`・`1,870→1,809` 行、`HtmlNode.pubMut` `8→6`・`1,404→1,402` 行、`DisplayListBuilder` `979→926` 行、`PaintOrder` `944` 行〔`ClassShapeRatchetTest` `pubMut=268`〕。`core.paint`→`LayoutBox` 書込 0・`dl*` 書込部位 0。`voidwave.html scroll` は `setIfActive()` で `1,017 B` に抑え全 56 シナリオ `COSTGATE OK` | `LayoutBoxReadOnlyFromPaintRatchetTest`・`SliceWriteSiteRatchetTest`・`PaintBoundsTest`・`InteractionStateOwnershipTest`・`FrameBoundarySnapshotTest`・`PaintOffsetsTest`・`LayerCacheAndDirectOpacityTest`・`Milestone1GateTest`・`ResizeSkipRebuildTest`・`PaintCellRatchetTest`・`PaintReadSetTest`・`ClassShapeRatchetTest`・`FileSizeRatchetTest`・`DocConsistencyTest`・`:core:costGate`・全件 `:core:test`/`:test`/`:core-gl`/`:core-vulkan`/`:core-glfw:test` |
| C6 | 描画側の変化の印の一本化: ①`PaintCell` の死んだ塗りの書き込み〔`writePaint`・`WRITES`・`epoch` 等〕と `DisplayItem.{repaint, repaintFrom, sameExceptPaint, attach, setPaintOffset, frozen}` を削除し、`DisplayItem`・`PaintCell` の全非静的欄を `final` に固定 ②平行移動〔スクロール・窓のドラッグ〕を外部表 `PaintOffsets`（要素 ID 鍵・キャッシュ済み長整数鍵・`version`/`keysVersion`・`EMPTY` 番兵）へ移し、`UiView.renderCache()` は不変スナップショットをそのまま返す（`baseRenderCache`・`publishRenderCache`・`applyPendingPaintOffsets`・`baseIndexById` 等を削除、`UiView` `2,087` 行へ縮小） ③`ItemDiff.unchanged(prev, cur)` を `prev == cur` の同一性 1 本へ縮退し、`ChunkClock`〔`ChunkClockTest` 含む〕・`ImmutableItemList.{touched, attach, epoch, token, clock}`・`contentToken`・`PaintSceneDriver.itemVersion`・`indexedBase` を削除、`SoftwareRenderBackend.ClipEntry` は `DisplayItem item` 参照比較へ置換 ④`CssAnimator.seenEpoch`・`ReplayKeys.epoch`・`PaintSceneDriver.indexedEpoch`・`DamagePlanner.prevEpoch` の 4 記録を廃止し、`DamagePlanner` と `StaticFrameGate`〔`paintedOffsetsEmpty` 含む〕で `PaintOffsets` の版・差分を直接判定 | `costGate` 確保: `voidwave.html scroll` `2,352 → 901 B` (`-61.7%`)・`machine_ui.html scroll` `47,695 → 44,759 B` (`-6.2%`)・`varied-list-500 inherited` `195,473 → 181,897 B` (`-6.9%`)・`showcase inherited` `55,365 → 52,474 B` (`-5.2%`)・退行 0〔基準更新後 56 シナリオ `COSTGATE OK`〕。`mutationFuzz` 450 回ずれ 0。書換口は `PaintOffsets`（`UiView` 所有）のみ | `GpuBaselineTest`・`VkBaselineTest`・`PartialRepaintPixelTest`・`CpuScrollBlitPixelTest`・`ScrollTranslateTest`・`StickyScrollNodeTest`・`SpanReplayVerificationTest`・`RealDocumentFramesTest`・`PaintOffsetsTest`・`StaticFrameGateTest`・`PaintOffsetHitTestCacheTest`・`ScrollIndexReuseTest`・`PaintSceneDriverPatchTest`・`ItemDiffRatchetTest`・`PaintCellRatchetTest`・`FileSizeRatchetTest`・`ClassShapeRatchetTest`・`StaticStateRatchetTest`・`DocConsistencyTest`・`:core:costGate`・`:core:mutationFuzz`・全件 `:core:test`/`:test`/`:core-gl`/`:core-vulkan`/`:core-glfw` |
| C1p | 断片の記憶の性能回復（パッチ・近道スイッチ新設なし）: ①祖先 `ownReused` を親文脈と分離・`FragSeq.Vec` 永続木 `insert`/`remove`＋`Fragment.aggregateDiff` 差分構築・`FragSeq.Layered` 錨キャッシュ・`kidMemos` 前後整列 ②`DirtyKids`（`DIRTY_MIN_KIDS=64`＋`LayoutBox` Treiber スタック＋`Fragment.bigKids` チャンク＋`treeNonStyleAttrVersion` 安全弁＋`refreshKidPasses` GC 更新）による O(dirtyCount) 多兄弟スキップ ③部分木単位のビューポート感度（`selfVpSensitive`/`subtreeVpSensitive`） ④`flatBuf` 直接展開・`ArrayView`・`FrameScratch`・`Fragment.EMPTY`・死んでいた `dlStart`/`dlEnd`/`dlOrder` 書込削除・`updateStyle` `newStyle==before` 短絡・`Memo.flexOrGridItem`/`flexOrGridContainer` 鍵追加。なお構造変更時の `layoutsShifted` 兄弟再構築の下限は C4 の対象 | `costGate` 確保: showcase `hover` `32,559→9,046 B` (`-72%`)・`resizeexact` `474→248 KB`・large-list `hover` `10,260→2,457 B` (`-76%`)・`child` `163→43.1 KB` (`-74%`)・voidwave `hover` `96.7→26.0 KB` (`-73%`)。`FineProfileMain`（null）: showcase `hover` `0.0612 ms`・large-2000 `hover` dl `0.031 ms`・`child` `2.11 ms`・`resizeexact` `0.555 ms`。`PathPredictionTest` 取りこぼし `1,104→266`（不健全 `0`）・`SliceWriteSiteRatchetTest` `8→2` | `FragmentPaintC1pTest`・`FragSeqPropertyTest`・`PathPredictionTest`・`PaintReadSetTest`・`BoundedModelCheckTest`・`MutationSeedRegressionTest`・`SliceWriteSiteRatchetTest`・`PatchSiteRatchetTest`・`:core:costGate`・`:core:mutationFuzz`・全件 `:core:test`/`:core-gl`/`:core-vulkan`/`:core-glfw` |
| C7 | 変更の計算と経路の予測〔描画側。経路をシステムが決め、機械的に予測・検証する〕: ①独立の予測器 `PathPredictionTest`〔記憶の内部を見ず、入力の前後比較だけから理想の再利用の段を予測し、実際の段と突き合わせる〕 ②書き込みを値の比較つきに〔`PaintStamps`: レイアウトは仮の印を付け、描画の直前に出力の署名・子の並びが変わった箱だけ確定〕 ③描画が読む箱の欄を摂動で実測し、署名の宣言と照合〔`PaintReadSetTest`〕 ④12 手・長さ 4 以下の全列挙〔`BoundedModelCheckTest`。属性だけの変更・`::first-line` の再描画漏れを検出して修正〕 ⑤先行例の調査〔設計書 §22.8〕。**残り**: 鍵の宣言からの生成は C3、レイアウト側の経路を同じ遷移表へ載せるのは C3〜C5 | 取りこぼし `17,102 → 1,049`〔−94%。④後 1,104〕・不健全 0・全 22,620 列で記憶あり=なし・`LargeList` `text` −28%〔`child` +14%〕 | `PathPredictionTest`・`PaintReadSetTest`・`BoundedModelCheckTest`・`OwnItemsVerifier`・ファズ・`costGate` |
| C1m | 再利用の仕組みを断片の記憶 1 つへ統合: 箱自身の項目の再利用〔文脈と箱の外の入力が前回と同じなら `Fragment.own` をそのまま使う〕を `FragmentPaint` の記憶に持たせ、**`BoxMemo`・変更印・厳密な平行移動・スクロール後の再描画待ち・`BoxMemoVerifier` の箱ごとの経路を削除**〔`BoxMemo` 791 行・`DisplayListBuilder` `−623` 行・`UiView`/`LocalPatches`/`StandaloneView` の受け渡し・スイッチ 4 個〕。検証は `OwnItemsVerifier`〔`VERIFY_BOX_MEMO` で、使い回した項目を作り直して比べる〕 | 「前回と同じか」の答え方が 5 種類 → 3 種類〔断片の記憶・索引の引き継ぎ・再生の鍵〕。既存の不具合〔ファズ `nested/21779068` の文字の幅 `10 ≠ 11`〕が平行移動の再利用の近似に由来しており、これで解消。`resize700` の画素ゴールデン 2 件が変わった〔平行移動の近似が無くなり、描き直しと同じになった〕 | `OwnItemsVerifierTest`・`RealDocumentFramesTest`・`IncrementalVsFullTest`・`RestylePruneTest`・全件 `:core:test`・ファズ 450 回 |
| C2 | 描画側の項目単位の差分パッチを全削除し、変化の扱いを「再生か全面の描き直し」に 1 本化: ①`SoftwareRenderBackend.tryPatchItems`・`Painter.tryPatchItems`・GPU の `beginDiffPatch`/`patchSpan*`/`endDiffPatch`/`abortDiffPatch`・`RectStream` の上書きモード・GPU の部分描画〔損傷のシザー・`GPU_DAMAGE`〕・`PATCH_DIFF_INDEX`・`PATCH_SHIFT_INDEX` を削除〔`PaintSceneDriver` `1,467 → 1,008`・`GpuRenderBackend` `1,379 → 1,184`・`SoftwareRenderBackend` `1,485 → 1,354` 行〕 ②索引の更新は、挿入削除のシフト・入れ替えの 2 経路を削除し、**状態の引き継ぎ〔`reconcileIndex`: 同じ並びなら差のある項目だけ更新し、姿勢・補間・検証印を使い回す〕と作り直し**の 2 つへ。これは描画のパッチでなく再生〔`ReplayKeys`〕の前提〔解決結果の同一性〕を保つ仕組みなので残した〔消すと GPU の項目の再生が成立しない：検証が 0 件になった〕 ③再生の鍵を `ReplayKeys` に 1 か所へ ④再生は止まっている項目の出力だけ覚える〔補間の最終フレームの出力を次に再生して古くなる不具合を修正〕 | 描画側の差分パッチ経路が無い〔CPU の `DamagePlanner` の部分再描画は別の仕組みで残る〕。損傷範囲・再生・索引の変化の判定は `ItemDiff` の述語 1 つ | `RealDocumentFramesTest`〔Vulkan の検証層・再生の検証〕・`SpanReplayVerificationTest`・`PaintSceneDriverPatchTest`・`GpuSinkSplitTest`・全件 `:core:test`・`:core-gl`/`:core-vulkan:test`・`PatchSiteRatchetTest` |
| C1 | 表示リストを部分木の記憶の連結で作る。**パッチを全て削除して経路を 1 本にした**: `FragmentPaint`〔箱を再帰し、種類〔付録 E の 7 層＋Top Layer の持ち上げ〕ごとに層の列を組み立てて 1 本に展開。マスク・仮想化・Top Layer・根でない起点まで対応〕＋箱ごとの記憶〔`BoxMemo`〕＋部分木の断片の記憶〔鍵: 親・位置・大きさ・クリップ・親 id・動く祖先・親相対の原点・子の並び・スクロール量・部分木の印〕＋永続ベクタによる層の連結〔変わった子だけ `set`〕。旧全面〔`paintTasks`・`executeTasks`・チャンク・prune〕・旧パッチ〔`patchBoxes`・`ChildSlicePatch`・`ChildPatchInsertion`・`StackingSubtreePatch`〕・`FragmentPatch`・影モード・`cant` 22 か所を削除。`LayoutBox` に部分木の印〔`touchPaint` の祖先への伝播。前回の描画以降に印のある祖先で止める〕 | 経路が 1 本〔`DisplayListBuilder` `2,473 → 1,581` 行・`PaintOrder` `1,487 → 945` 行・`dl*` の書き込みは `FragmentPaint` の展開だけ〕。パッチの退く条件・範囲や順序の数え違い型の不具合が構造上起きない。パッチが隠していた既存の欠陥を 1 件発見・暫定解決〔sticky の平行移動の再利用。恒久対処は C4〕。**時間 KPI は許容して悪化**〔ホバー等が全面の組み立てになった。回収は C1p〕 | `FragmentPaint`・`Fragment`・`FragSeqPropertyTest`・全件 `:core:test`〔両モードの旧経路との同一性は 1 本化の直前に実文書 9 本・並走の差分テスト・全件・ファズ 600 回で確認〕・差分ファズ 600 回・`StickyScrollNodeTest`・`GoldenFrameTest`・`RealDocumentFramesTest`・`PatchSiteRatchetTest`・`SliceWriteSiteRatchetTest` |
| C1a | C1 の足場: `LayoutBox.{dlStart, dlEnd, dlOrder, paintCulled}` の書き込み箇所（`DisplayListBuilder` 22・`ChildSlicePatch` 9・`StackingSubtreePatch` 6・`LayoutBox` 7）を棚卸しし、上限をテストで固定。C1 を C1a〜C1d の 4 段に分けた（§3.3） | 逆流書き込み（S7 ①）が増えない。以後 C1 の各段で上限を下げ最後に 0 にする | `SliceWriteSiteRatchetTest`・`PatchSiteRatchetTest` |
| S7 ②（一部） | `DisplayListBuilder.textStrokeWidth`（`-webkit-text-stroke-width` の文字列パース）を L3 の `css.TextStroke` へ移設。`paint` は CSS px の数値だけを受け取る | L5 から CSS 値の文字列解釈が 1 つ減った（キーワード thin/medium/thick・px/em/rem・不正値 0 の挙動は不変） | `TextStrokeTest`（値の解釈・`paint` が該当プロパティを直接読まない）・`LayerDependencyRatchetTest`・`ClassShapeRatchetTest`・`FileSizeRatchetTest`・`GoldenFrameTest`・`IncrementalVsFullTest`・`:core:quickTest` |
| S6（一部） | GPU 実装と CPU 実装の共有の供給元〔`PaintResources`・`ImageProvider`・`HttpImageFetcher`・`CropImages`〕を `paint.software` から中立の `paint.resource` へ移した。これで `paint.gpu` は `paint.software` を参照せず（つなぎは `paint.port` の口と `paint.resource`）、共有パッケージ〔`model`・`port`・`resource`〕は実装を参照しない | GPU 実装と CPU 実装が互いの具象を知らない（ビルドで固定）。`ImageProvider.Web(boolean)` は別パッケージのテストのため public に | `PaintPipelineDirectionRatchetTest`・`PackageCycleRatchetTest`・`StaticStateRatchetTest`・`UnreadableImageSourceCacheTest`・`VideoWindowTest`・`PaintResourcesTest`・`:core:test`/`:core-gl:test`/`:core-vulkan:test`/`:core-glfw:test` 全件 |
| A10 | GPU・窓モジュールを公開物にする: `:core-gl`・`:core-vulkan`・`:core-glfw` に `maven-publish`（共通設定 `gradle/publish-core-module.gradle`。sources/javadoc jar・POM のライセンス・scm・`:core` 依存・LWJGL 版）。`:core` の `repo/` URL が Windows で解決できない不具合も `uri(file)` で直した | 依存追加だけで GPU 描画・窓が使える（`enderframelib-core-gl` など 4 成果物） | Gradle `:core:verifyPublishedModules`（4 モジュールを公開 → POM の依存検査〔`:core` に GPU・窓が混ざらない〕→ 別プロジェクト `scripts/consumer-check` が `repo/` だけでクイックスタート 4 例を実行）・`verifyCoreDependencyGraph` |
| A14 | ホストの GPU デバイスを API 中立に渡す: `api.HostGpu`（`openGl(glfwWindow)`・`vulkan(instance, physicalDevice, device, queueFamily, queue)`）と `Builder.hostDevice(HostGpu)`〔`hostDevice(long)` は非推奨で残す〕。core 側は中立の `HostDevice` を `GpuBackendProvider.Request` で運び、`VkContext.adopt` がホストのハンドルを借りる（`close` はホストのデバイスを壊さない・1.1 未満／グラフィックス非対応のキューファミリは拒否）。`gpu()` とホストの API の食い違いは失敗にする | 条件 c-① が Vulkan でも成立（コピーなしの `VULKAN_IMAGE` を返す・AUTO は GL を試さず Vulkan へ）。ホストのデバイス上の画素は自前のデバイスと一致 | `HostGpuTest`（値の検証・食い違いの拒否）・`VkHostDeviceTest`（ホストデバイス上の描画・画素一致・面を閉じてもホストが生きる・2 面共有・検証レイヤ 0 件）・`ApiSignatureTest`（表更新）・`GpuBackendsTest`・`GpuModulesTest` |
| C0 | ①`patch-sites.txt`（パッチ系スイッチ 11・`cant` 22 か所・パッチ専用 3 クラスの行数）と `PatchSiteRatchetTest` で、パッチの追加を機械的に止めた ②検証モードを `VERIFY_FLOATS` から一般化: `VERIFY_MEMO`（測定の記憶〔`MeasureVerifier`〕・姿勢の使い回し〔`PoseVerifier`〕・項目の再生〔`RectStream.spanVerifyArm`〕・float メモ）と `VERIFY_BOX_MEMO`（箱の項目〔`BoxMemoVerifier`〕）。`:core:test` で常時有効 ③`mutationFuzz` にも併走させ、検証が赤にした手を「鍵の漏れ[記憶の種類]」で報告（増分と全面の食い違いと区別） | 記憶の鍵の漏れが、画素の差ではなく当たった箇所で見つかる（鍵を外す再注入で、検証オンだけ赤が測定 7 件・箱の項目 4 件増え、ファズは 60 件を箱名・手つきで報告。§6-128）。実不具合 2 件を発見・修正（スクロール節の二重出力・ノードの鍵が変わった箱の古い記憶。§6-125）。全件 `:core:test` 5〜8 分（検証なし 3 分 50 秒）・`costGate` 56 シナリオ OK | `PatchSiteRatchetTest`・`MeasureVerifierTest`・`PoseVerifierTest`・`BoxMemoVerifierTest`・`SpanReplayVerificationTest`・`PatchedMemoNoDuplicateItemsTest`・`MutationFuzzKeyLeakTest`・`ItemReplayPixelTest` |
| S5 | ①`PackageCycleRatchetTest`（同一層のパッケージ循環の塊を `package-cycles.txt` に凍結し、広がる・新しい塊を失敗にする。Tarjan の SCC）と `ModulePurityRatchetTest`（`:core` に `client`/`server`/`content` が無い・`core.dom` が `core.scene3d`/`core.raster` を参照しない・使われていないワイルドカード import が無い）②L2 の循環を解体: `ImageDecoder` を `ServiceLoader` 化〔`raster` が `raster.awt` を new しない〕、`CanvasRenderingContext2D` を `core.raster.canvas` へ移し、COLR v1 の描画は `text.font` の口 `ColrV1Renderer` ＋ `raster.canvas.ColrV1Painter`（ServiceLoader）へ分離〔`raster`↔`text.font` を切る〕③未使用ワイルドカード import 171 件を一掃（これだけで `text`↔`text.model`・`layout`↔`layout.model` も解消）、`client.template` をルートの Mod へ（パッケージ名は据え置き）、`Scene3DDomSync` を `core.dom` から `common` へ、`common.three`（Three.js 風 API）をデモへ降格〔`:core` の配布物から外す〕 | 層内循環の再発防止（残る塊は凍結の 3 つだけで、増えると赤）、`:core` の配布 JAR から Minecraft 側の型と孤立した API を外した、L2 基盤の完全 DAG 化 | `PackageCycleRatchetTest`・`ModulePurityRatchetTest`・`LayerDependencyRatchetTest`・`ImageDecoderTest`・`CanvasRenderingContext2DTest`・`Scene3DApiTest`・`ClassShapeRatchetTest`・`FileSizeRatchetTest`・`verifyCoreDependencyGraph`・全件・ルート `test` |
| A13 第 5 段（完了） | ①`LayoutTaskPool.runShared` が、明示の `scopeRunner` が無ければ呼び出したスレッドの面の文脈を捕まえてワーカーへ引き継ぐ〔`bindScopeCapture`。`EngineContext` が結ぶ。global の呼び出しは包まない〕。再スタイルだけが持っていた明示の包みを廃止 ②`ImageDecoder.use`（プロセス全体の復号器の差し替え）を `HostServices.imageDecoder`〔`withImageDecoder`。null なら JDK 版〕へ。`bindSource` で文脈を引く ③`Shortcut` は面ごと化しない設計判断（§6-132） | 並列のレイアウト・描画の準備・ソフトウェア描画の行/タイルでも、ワーカーが面の置換要素・書体・解析の上限を見る（以前は global を見て、面に登録した置換要素の並列レイアウトが直列とずれた）。復号器は面ごとに独立。static の差し込み口は 0（`Shortcut` を除く） | `WorkerContextPropagationTest`（並列＝直列・前提）・`ImageDecoderTest`・`TwoSurfacesIsolationTest`・`EngineContextHostSeamsTest`・`ClassShapeRatchetTest`・`LayerDependencyRatchetTest`・全件 |
| A13 第 4 段 | `HostSyntax`（属性の別名・Markdown ブロックのタグ）と `CustomTagExpander`（タグマクロ）の static 登録表を、SPI の宣言＋面ごとの追加〔`HostServices.syntaxExtras`/`tagMacros`。`withAttributeAlias`/`withMarkdownTag`/`withTagMacro`〕へ移した（`register`/`registerAttributeAlias`/`registerMarkdownTag`/`resetToDefaults` を廃し、追加は構築時に SPI と合成して読みは参照 1 回）。`DisplayListBuilder.setParentRelativeSource` を `bindParentRelativeSource` へ | 面ごとにテンプレート構文・タグマクロが独立（テストの後始末も不要）、static setter `1 → 0`。タグマクロ・別名・解析の上限が違う 2 面を同一スレッド交互・2 スレッド同時に描いて、毎フレームの全画素が単独の描画と一致 | `TwoSurfacesIsolationTest`（交互・同時・前提）・`EngineContextHostSeamsTest`（構文・タグマクロの 2 スレッド・番人）・`HostSyntaxTest`・`CustomTagExpanderSpiTest`・`ClassShapeRatchetTest`・全件 |
| A13 第 3 段 | `GlyphUnits`（`ch`/`ex` の書体解決）・`ReplacedElements`（ホスト置換要素の登録。SPI 宣言分は静的のまま）・`LocalEnvironment`（基本の供給元・自前変数。写しの覚えは文脈ごと）を `HostServices` へ移した（`register`/`install`/`resetToDefaults` を廃し `withReplacedElement` 等へ、`setBaseSource` は `useBaseSource`） | 面ごとに `ch`/`ex` の測定・置換要素・ローカル環境変数が独立、static setter `5 → 4`（残りは setter 名の別物） | `EngineContextHostSeamsTest`（置換要素・ローカル環境・番人）・`GlyphUnitsContextTest`・`LocalEnvironmentTest`・`ReplacedElementsTest`・`StaticStateRatchetTest`・`ClassShapeRatchetTest`・全件 |
| A13 第 2 段 | `SvgSources`（読み手・HTTP 取得元。読み込み結果の覚えは読み手ごと）・`FontFaceRules`（書体の読み手）・`CssAnimator`（補間状態の置き場）・`HtmlParser`（既定の解析上限）の static 差し込み口を `HostServices` へ移し、各クラスは `Source` 読み口＋`EngineContext` からの `bindSource` だけにした。ホスト（`EnderFrameClientInit`・`WebFontManager`・`StandaloneViewer`）は `updateHost` へ | 面ごとに SVG・書体・アニメ状態・解析上限が独立、static setter `10 → 5`。別の面の SVG 読み込み結果を覚えから返さない | `EngineContextHostSeamsTest`（4 窓口の面別・2 スレッド同時・番人）・`SvgSourcesTest`・`FontFaceRulesTest`・`ClassShapeRatchetTest`・`FileSizeRatchetTest`・`:core:test`/`:core-glfw:test`/ルート `test` 全件 |
| A13 第 1 段 | `PaintServices` の static 差し込み口（SVG・書体・固有サイズ・画素数の既定）を `HostServices` へ移し、`PaintServices` は文脈を引く読み口だけにした（`bindSource`）。`ImageSizes.installDefault` を廃し `withPixelSizeFallback` へ | 面ごとに SVG・書体・固有サイズが独立（同一 JVM・スレッド別）、static setter `14 → 10`。ワーカーは従来どおり global を見る（layout の `scopeRunner` 経由で伝播するのは layout 側のみ） | `EngineContextPaintServicesTest`（面別・2 スレッド同時・番人）・`ClassShapeRatchetTest`・`LayerDependencyRatchetTest`・`:core:test`/ルート `test` 全件 |
| S4（`AtomTable`） | `AtomTable` に上限指定（`AtomTable(int)`）・残量（`remaining`）・90% 到達の通知（`onNearLimit`）・超過時の専用例外（`ExhaustedException`）を追加。動的 id・class の際限ない投入は解放機構が無いため例外で早く検出する方針 | 枯渇前に運用側が警告でき、失敗の原因が型で分かる | `AtomTableTest`（上限・通知・同名反復で不増） |
| S4（`PackedLengths`） | 式表を弱参照スロット＋空きリストの有界表へ変更（持ち主の `CssValue`→`CompiledLength` 強参照が消えれば添字を再利用。別実体の同一式は共有しない） | 補間 `calc()` が毎フレーム生まれても式表は生存数でしか増えない（10 万フレームで増加 `< 20,000` 添字をテストで固定） | `PackedLengthsTest`（有界性・再利用）・`StaticStateRatchetTest`・`StyleAnimatorTest`・`:core:test` 全件 |
| A9 | ホスト拡張の公開 SPI（`api.spi.ElementProvider` 等）と `common.ApiSpiBridge`、`Builder.i18n`/`resources`、`EnderEngine.createEvent`/`createAbortController` を追加し Minecraft `mc-*` を移行 | 内部型非依存の拡張口と `core/src/apiSample` を確立、`McTagMacros` を廃止 | `ApiSpiProviderTest`・`ApiSampleTest`・`ApiEventFactoryTest`・`McTagMacrosTest`・`ApiBoundaryTest`・`ApiSignatureTest` |
| S3 | `ElementType` に `default` なしの網羅的 switch 分類（`rasterRoute`・`drawsToBuffer` 等）を集約し `core`/ルートの switch 6 箇所を置換 | 種類追加漏れをコンパイル検出、`default` 付き分岐 0 | `ElementTypeClassificationTest`・`:core` / ルート全件 |
| S2 | 要素性質表 `ElementTraits` と採寸表 `ElementSizing` を導入し各段のタグ文字列比較を集約 | タグ文字列比較ファイル `15 → 1`（`HtmlParser` のみ） | `ElementTraitsTest`・`ElementSizingTest`・`ArchitectureTest` |
| A2 | Chrome 比較を Arial 同一書体条件で再計測（`run-matrix.sh … font chrome`） | 負ける比較行 0・`varied fontsize` 予算超過（→ B11）を発見 | `run-matrix.sh` 計測 |
| A3 | `-Denderframe.threads` / `EnderEngine.workerThreads` と CPU タイル描画のワーカープール統合 | コア数別の 144Hz 達成境界（4 本以上）を特定 | `ParallelLayoutEquivalenceTest` |
| A5 | `core.*` の static 状態を棚卸し分類（`static-state-classification.txt`）・ラチェット化、並列変異ストレス検証、`StaleTextPatchTest` 修正 | 並列実行の決定性確立、要対処 static 状態 0 | `StaticStateRatchetTest`・`ParallelMutationStressTest`・`StaleTextPatchTest` |
| A6 | 近道スイッチ 37 個の単独オフ＋ペアワイズ 53 構成を乱数変更列で自動検証する `:core:shortcutMatrix` を構築 | 近道組み合わせの退行を自動検出 | `:core:shortcutMatrix`・`ShortcutSwitchRatchetTest` |
| A6b | A6 で発覚した増分≠全面の 20 件（子パッチ・表枠線・スクロール整数揃え・窓判定等）を修正 | `shortcut-matrix-known.txt` 空・53 構成全一致 | `IncrementalVsFullTest`・`:core:shortcutMatrix`・`GoldenFrameTest` |
| A4 | `.md` 相対リンク・`core/README.md` コード例のコンパイル・`BENCHMARK.md` との数値整合を自動検査化 | 文書と実装の乖離をビルドで阻止 | `DocConsistencyTest` |
| A12 | 実文書 5 本 × `hover`/`scroll`/`text`/`open` を Chrome と実書体比較計測 | 常時アニメ確保問題（→ B12）と `open` 予算超過（→ B13）を特定 | `run-matrix.sh … real chrome` 計測 |
| A16 | A11 の空白テキスト保持による増分コストを、直列化最適化・共有空マップ/`Rare` 分離（節 `112→80 B`）・親スタイル写しで回復 | パケット `6,183→4,451 B`・メモリ `+0.8%` へ復帰、WPT `160/260` 維持 | `PacketBandwidthTest`・`StateMemoryBenchmarkTest`・`TextNodeStyleEquivalenceTest` |
| A8 | 1,500 行超の神クラス 13 個のうち 9 個から入れ子型・責務クラス（`RenderTreeBuilder`・`GpuEffects`・`ItemRaster` 等）を切り出し | 1,500 行超 `13 → 4`、HotSpot JIT 制限超メソッド解消 | `FileSizeRatchetTest`・`ClassShapeRatchetTest`・`costGate` |
| B11 | 呼び出しスレッドのタスク参加（`LayoutTaskPool.runShared`）・書体メモ・`SHAPE_EPOCH` 発火限定・`SubtreePruner` 並列化を実施 | `varied-2000 fontsize` 4 本 `8.85→6.71 ms`（144Hz 予算内） | `LayoutTaskPoolCallerParticipationTest`・`ParallelPrunePrepareEquivalenceTest`・`FontSizeRestyleMemoTest` |
| B12 | スタッキングコンテキスト部分木再構築・`patchIndexByMove`・`StickyShift`・親姿勢 `Resolved` 共有により常時アニメ文書を差分経路へ乗せた | 実文書 `hover`/`scroll`/`text` が全件 `<= 100 KB`・時間半減以下 | `StackingSubtreePatchTest`・`StickyScrollNodeTest`・`ParentPoseShareTest`・`RealDocumentFramesTest` |
| T2 | `costGate` を実文書込み 56 シナリオへ拡張し、実文書 9 本の操作手順ゴールデン＋ヒットテスト時計・クリップ連鎖の不具合 3 件を修正 | 実文書の確保量・経路・画素・ヒットテストを push 時に固定 | `RealDocumentFramesTest`・`HitTestFrameClockTest`・`ScrollNodeClipRegressionTest`・`:core:costGate` |
| T2b | 実文書の GPU/SOFTWARE 画素差原因 4 件（サンプラー binding・Vulkan 反転・ボックスぼかし端・動く節の子孫パッチ漏れ）を解消 | GL/Vulkan とも全 9 実文書で最大差 `<= 8`・Vulkan 検証エラー 0 | `ShaderBindingConsistencyTest`・`BoxBlurEdgeTest`・`RealDocumentFramesTest` |
| T1 | ファイル行数・クラス形・システムプロパティ・空 `catch` の 4 ラチェット表を制定（`*-ceilings.txt` 他） | 構造指標の退行を自動阻止 | `FileSizeRatchetTest`・`ClassShapeRatchetTest`・`SystemPropertyRatchetTest`・`SwallowedExceptionRatchetTest` |
| T4 | 公開 `api.*` 全署名を `api-signatures.txt`（971 行）へ固定し、`api.event`/`api.dom` 契約テストを追加 | 公開 API の無意識な破壊的変更を阻止 | `ApiSignatureTest`・`EventContractTest`・`DomValueContractTest` |
| S1 | システムプロパティ読込を `EngineFlags` 1 箇所へ集約し、`EngineContext` を不変 `HostServices`/`ContextFlags` 化、51 個のフラグを `Shortcut` へ統合 | プロパティ読込ファイル `22→1`・`EngineContext` 可変欄 `14→0` | `SystemPropertyRatchetTest`・`EngineContextTest`・`ShortcutTest`・`:core:shortcutMatrix` |
| B4 | **検証済・見送り（着手条件未成立）**: 同梱実文書 9 本の GL 被覆率実測が `98.1%〜100%`（`finishClip` 後 `surface_test` も `100%`）で、条件 `< 95%` に非該当 | パス/回転文字等の GPU 実装コストを回避 | `FineProfileMain` 実測・`CpuFallbackOfRareShapesTest` |
| M1〜M5 | オフヒープ DOM（`DocumentArena`）・`StyleStateAutomaton`・増分レイアウト・Uber-Shader・タイル並列 CPU ラスタ基盤 | `:hover` `0.0069 ms`、`scroll` `0.002 ms / 0 B` | `DocumentArenaTest`・`PropertyIdMigrationGuardTest`・`Milestone4GateTest` |
| Wave 0/1 | 公開 API・脱 `mc-*` SPI・`EngineContext` 導入・`LoadedFace` 並列化・GL 差分走査 | `fullLayoutWarm` `0.97 ms` | `ApiBoundaryTest`・`HostElementProviderTest` |
| 構造ラチェット | 7 層単方向 DAG（層間逆方向参照 0）・プラットフォーム依存隔離 | 層間の逆依存をビルドで阻止 | `LayerDependencyRatchetTest`・`PortabilityRatchetTest` |
| W8 | 文書サイズ上限の黙った切り捨てを解消 | 正しさの保証 | `DocumentSizeNoTruncationTest` |
| D0 | IME 変換・動的リサイズ・Maven POM 設定・`core/README.md`・宣言的 UI DSL（`api.ui`）を整備 | 単体配布 Alpha レベル達成 | `ImeCompositionTest`・`ReactUiTest`・`ApiBoundaryTest` |
| R0 | 段別プロファイル・パッチ命中・不成立理由カウンタの計測基盤を整備 | 以降の最適化判定の土台 | `FineProfileMain` |
| R1・R2（部分） | スタイル変更のスライスパッチ化・子増減パッチ・窓付き表示リスト | 2,000 行 `hover` `2.45→0.018 ms`・`child` `1.44 ms` | `DisplayListSlicePatchTest`・`PatchHitRateRatchetTest` |
| P1〜P4 | パッチ判定一元化・`InlineFragment` 再利用・整数平行移動・宣言ハッシュ等価短絡 | `child` 確保 `-27%`・`inherited` `10.2→7.9 ms` | `DisplayListSlicePatchOptimizationTest`・`BlockLayoutOptimizationTest` |
| Q1〜Q7・Q1b〜Q6c | 縦寸法 1/64 px 格子化（`LayoutUnit`）・差分メモ・リサイズ再構築スキップ・仮測定 4 枠＋`fillFitReuse`・CSS 4 バンド整列（Q2b）・構造変化除外（Q4）等 | `varied inherited` `6.37→3.80 ms`・`resize` 全体 `0.80→0.48 ms` | `FractionalShiftEquivalenceTest`・`ResizeSkipRebuildTest`・`TentativeMeasureReuseTest`・`ComputedStyleDiffMemoTest` |
| P5〜P9b | `NaturalSegments`＋2-way L1 文字キャッシュ・`ReusableTable`・`ConstraintSpace` 再利用・項目単位 CPU 部分塗り（P7）・GL 二度書き解消（P9）・`TextSetup` 保持（P9b） | `measure` 半減・`varied fontsize` 確保 `< 5 MB`・GL 変更書込 `-95%` | `TextShaperIntervalAndFontCacheTest`・`P6IncrementalTraversalTest`・`P8AllocationReductionTest`・`PartialRepaintPixelTest`・`GlStaticFrameWritesTest` |
| R6 | `EnginePrewarm` によるバックグラウンド事前ウォームアップ | コールド `open` `446 → 137 ms` | `EnginePrewarmTest` |
| X2 | `@scope`・`<template component>`/`<slot>`・`RecordState`・`F12` 要素インスペクタ | 開発体験・型安全バインディング向上 | `ScopeRuleTest`・`ComponentSlotTest`・`RecordStateTest`・`ElementInspectorTest` |
| R5・R7 | 項目単位の保持 GPU 記録再生（`spanReplay`）と `layoutsShifted/Skipped` カウンタ固定 | showcase GL `hover` `0.416→0.31 ms`・配置退行の機械検出 | `ItemReplayPixelTest`・`PatchHitRateRatchetTest` |
| RTI | **検証済・見送り（着手条件未達）**: 上位施策で Chrome 比 2x 超の KPI が消滅 | 不要な保持 GPU 大改修を回避 | `FineProfileMain` 計測 |
| Y1〜Y6 | 計測精度統一（Y2/Y3）・不要案の事前見送り（Y1 区間再生・Y4 ヒープ・Y5 CDS/静的表直列化）と表示リスト一部確保削減（Y6 `-13%`） | 費用対効果の低い施策を見送り・誤計測を是正 | `FineProfileMain`・`MemFootprintMain`・`ColdStartMain` |
| P10 | GPU/Software/Driver 非確保高速経路バッチ 1〜7（`OffsetItemList`・`StyleSheet` 版キャッシュ・クリップ/行列共有等） | showcase GL `hover` `0.31→0.203 ms`・確保 `42→9.9 KB` | `:core` / ルート全件緑 |
| X1 | Top Layer・Anchor Positioning・`content-visibility: auto` 仮想化・`subgrid`・SVG カスケード・カラー絵文字・縦書き・`::first-line`・IME | 近代 Web UI 仕様を実用網羅 | `TopLayerTest`・`AnchorPositioningTest`・`SubgridTest`・`ColorEmojiTest`・`VerticalWritingTest` |
| X3-0〜X3-3 | L2 `core.scene3d`・GL 3.2 TBO 描画（モード A）・PBR/Bloom/glTF・64×64 CPU 3D ラスタ・`Raycaster3D` BVH・`<scene-3d>` 宣言同期・`api.scene3d`・`data-orbit` | 3 者画素一致の 2D+3D 統合・10,000 メッシュ `0.0055 ms/本` | `Scene3DCoreTest`・`Scene3DSoftwareParityTest`・`GltfBinaryLoaderTest`・`Scene3DDeclarativeTest`・`Raycaster3DTest` |
| B3・B8 | 表示項目の親相対化（`AbsoluteItems` 境界分離）と Minecraft 側各 Surface への親相対適用・実機検証 | 親相対 ON/OFF 画素一致・移動時子項目再構築を削減 | `ParentRelativeParityTest`・`RelativeElementsTest`・`WebControlInteractionTest` |
| G0〜G4 | GPU 被覆率計測・RHI `GpuDevice` 導入・シェーダー単一ソース＋ビルド時 SPIR-V 検証・Vulkan 1.1 バックエンド・`:core-gl`/`vulkan`/`glfw` モジュール分離 | `:core` の GPU/窓依存 0・GL/Vulkan/CPU 3 者画素一致 | `GpuDeviceContract`・`ShaderSourcesTest`・`VkBaselineTest`・`GpuModulesTest`・`verifyCoreDependencyGraph`・`runCoreOnlySmoke` |
| B1 | `cold.js`/`trace.js`/`latency.js` による Chrome 比計測・WPT 260 組選定 | 勝敗・改善ターゲット（B2/B5/B6/W9）を明確化 | `InputToDisplayLatencyTest`・`WptReftestRatchetTest` |
| W9・W9b | 測定燃料上限と float 入れ子再測定（`2^深さ`）の根本解消（`hasUnmeasuredSubtreeChange` 等） | 19 段未了文書が `66,800→210` 回・`fuel=0` で線形終了 | `MeasureFuelGuardTest`・`FlexSameWidthRemeasureTest`・`TentativeMeasureReuseTest` |
| B2 | `LayoutTaskPool` による並列 restyle・測定・配置・印付け・後片付け | `varied-2000 fontsize` `19.43→6.21 ms`（144Hz 予算達成） | `ParallelLayoutEquivalenceTest` |
| B9 | `DisplayItem` の可変欄を `PaintCell` へ分離し `ChunkClock` で差分検出 | showcase `hover` 確保 `4.1→0.5–0.7 KB` | `ItemDiffRatchetTest`・`PaintCellRatchetTest`・`ChunkClockTest`・`PaintInPlaceTest` |
| A7 | `LayoutBox` から `MeasureMemo`・`PlacementMemo`・`IntrinsicSizing`・`ScrollExtentCalc` 等を切り出し幾何欄をパッケージ閉鎖 | `LayoutBox` `3,885→1,836` 行・`pubMut` `106→40`・`child` 確保半減 | `MeasureMemoTest`・`ClassShapeRatchetTest`・`FileSizeRatchetTest`・`:core:costGate` |
