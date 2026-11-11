# EnderFrameLib `:core` — エンジンアーキテクチャ総括 & 次世代ロードマップ

> **最終更新**: 2026-11-11（**最新: **窓付き表示リストを実装（§8.6.2）: 大きい文書で画面外の箱の項目を作らず、2,000 行 GL の `hover` 1.06→0.26・`text` 1.61→0.37・`fontsize` 19.6→8.8 ms で Chrome を超え、`child` は約 18→2.25 ms（Chrome 1.09）。**その前提として、R2（`child`）は「スライスパッチ命中 100%・確保量削減」まで達成し時間 KPI は未達と確定（§6.10）していた。JFR 分解で、原因は平坦リストの O(N) そのものではなく「後続項目の再生成（散在ヒープのポインタ追い）」と「それに巻き込まれる下流索引の全面作り直し」と判明。現行方式の延長では `< 3 ms` に届かないため、DoD 再設計（座標を持たない Paint 表）と内容アドレス化・反復ブロックのインスタンス化（RTI）へ方針転換（§8）。**／前: R1 完了（§6.9）／前: R0 計測基盤補強完了（§6.8））
> **対象モジュール**: `:core` (`com.nishiyu.enderframelib.core.*`, `common.*`, `api.*` — 本番 335 クラス)
> **位置付け**: 本書は `:core` エンジンの**マスターアーキテクチャ仕様書・実測ベンチマーク台帳・残存課題ロードマップ**である。3D シーングラフ統合の詳細設計は [`ENGINE_3D_ARCHITECTURE_PLAN.md`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/ENGINE_3D_ARCHITECTURE_PLAN.md) を参照。

---

## 1. 基本思想と絶対設計原則

EnderFrameLib `:core` は、Java 21 FFM API（オフヒープメモリ）・ビット並列アルゴリズム・GPU インスタンスシェーダー・タイル並列ソフトウェアラスタライザを組み合わせた**純 Java 汎用 Web/UI レンダリングエンジン**である。

### 1.1 パフォーマンス予算（144Hz フレームバジェット: `6.94 ms`）

ゲームループや高リフレッシュレートデスクトップアプリと同一プロセスで共存するため、UI エンジンは以下の CPU / メモリ予算を厳守する。

| 操作カテゴリ | 代表シナリオ | 目標時間 | 目標ヒープ確保 | 2026-11-10 実測値（300 行ダッシュボード / 実フレーム） |
| :--- | :--- | :---: | :---: | :--- |
| **静止フレーム** | 変化なし (`idle`) | `0.00 ms` | `0 B` | **`0.000 ms` / `0 B`**（完全スキップ） |
| **スクロール** | 1 step スクロール (`scroll`) | `< 0.05 ms` | `0 B` | JMH: **`0.002 ms` / `0 B`**、GL 実フレーム (5,012 項目): **`0.157 ms` / `8 KB`**（2,000 行 `0.559 ms`） |
| **インタラクション** | `:hover` / `:focus` 切替 | `< 0.15 ms` | `0 B` | JMH (`restyle`): **`0.008 ms` / `32 B`**、GL 実フレーム: showcase **`0.719 ms`**、large-list 500 行 **`0.337 ms` (p50 `0.305 ms`)**、2,000 行 **`1.06 ms` (p50 `0.999 ms` / null `0.228 ms`)**（**R1 KPI 達成・§6.9**） |
| **局所データ更新** | テキスト・インライン style 変更 | `< 0.25 ms` | `< 1 KB` | `TEXT_PATCH` (`null`): **`0.248 ms`**、GL 実フレーム: showcase **`0.781 ms`**、large-list 500 行 **`0.431 ms`**、2,000 行 **`1.61 ms`** |
| **フル再レイアウト** | ウィンドウ幅変更・全体再計算 | `< 1.00 ms` | `< 1 MB` | JMH (`fullLayoutWarm`): **`0.971 ms` / `1.1 MB`**（2026-11-11 再計測。showcase `layoutWidthChange` `1.04 ms` は目標内だが Chrome `0.084 ms` に劣る＝W5） |

### 1.2 4 つの絶対設計原則

1. **Data-Oriented Design (DoD) と正規化不変値の組み合わせ**:
   - DOM トポロジー・セレクタ Bloom Filter・GPU インスタンスバッファは `MemorySegment` オフヒープ列（[`OffHeapColumns`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/OffHeapColumns.java), [`DocumentArena`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/DocumentArena.java), [`PersistentVramBuffer`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/gpu/PersistentVramBuffer.java)）に配置する。
   - CSS プロパティアクセスはすべてコンパイル時整数添字（[`CssPropertyIndex`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/CssPropertyIndex.java)）で行い、算出値は弱参照正規化された不変オブジェクト [`CssValue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/CssValue.java) と 64-bit パック値 [`PackedCssValue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/style/PackedCssValue.java) (`long[]`) に保持する。
2. **Retain & Patch（保持と局所差分更新）**:
   - 毎フレームのツリー全体走査を避け、変更の種類に応じて最短経路（`BindingPatchTable` → `StyleStateAutomaton` → `TEXT_PATCH` / `CHILD_PATCH` / `LOCAL_SUBTREE` → `HitTestIndex.patch` → `PaintSceneDriver.patchIndexByDiff` → `GpuScrollLayer`）を選択する。
3. **GPU ファースト + タイル並列 CPU フォールバックのハイブリッド構成**:
   - 矩形・角丸・枠線・影・グラデーション・グリフは単一 Uber-Shader（[`GpuRectShader`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/gpu/GpuRectShader.java) + [`GlRectStream`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/gl/GlRectStream.java)）で 1 ドローコール描画し、複雑なパス・フィルタやヘッドレス環境では 64×64 タイル並列ラスタライザ（[`TileQueue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/TileQueue.java)）がピクセル等価性を保証する。
4. **機械的ラチェット（Mechanical Ratchets）による退行防止**:
   - 達成した構造純化（`mc-*` 直書き禁止、公開 API への内部型漏洩禁止、`ComputedStyle` の文字列名アクセス禁止、プラットフォーム依存クラス数上限、テスト当たりヒープ確保量上限）はすべて自動テストで固定し、逆行をコンパイル／テスト段階で阻止する。

### 1.3 OSI 参照モデル型・7 層単方向アーキテクチャ（機能追加による構造崩壊の防止）

今後の大規模な機能追加（Wave 1.5〜Wave 4）によってパッケージ間の責務や依存方向が崩れることを防ぐため、エンジン全体を **OSI 参照モデルに倣った 7 層の単方向階層（L1〜L7）** として定義する。**「第 $N$ 層は第 $N-1$ 層以下の型しか参照してはならず、各層間は明確な不変データ単位（PDU: Protocol Data Unit）のみで通信する」**ことを原則とする。

```text
┌────────────────────────────────────────────────────────────────────────────┐
│ L7: Public API & Host Surface Layer (`api/*` + `common/EngineSurface`)     │
│   PDU: `WebViewSurface`, `SurfaceState`, `PixelSink` (内部型露出 0)         │
├────────────────────────────────────────────────────────────────────────────┤
│ L6: Session & Pipeline Orchestration Layer (`common/*`, `core/EngineContext`)│
│   PDU: フレーム駆動・入力イベント合成 (`UiView`, `RenderPipeline`, `UiInput`)│
├────────────────────────────────────────────────────────────────────────────┤
│ L5: Paint, Composition & Backend Layer (`core/paint/*`)                    │
│   PDU: `DisplayItem` スライス → 80B GPU インスタンス / 64×64 タイル `Op`   │
├────────────────────────────────────────────────────────────────────────────┤
│ L4: Box Tree & Layout Formatting Layer (`core/layout/*`)                   │
│   PDU: `ConstraintSpace` → `LayoutBox` (解決済み座標 `x, y, w, h`, `ShapedLine`)│
├────────────────────────────────────────────────────────────────────────────┤
│ L3: Document, Style & Event Semantics Layer (`core/dom/*`, `core/css/*`,   │
│     `core/event/*`, `core/kernel/compiler/*`)                              │
│   PDU: `HtmlNode`, `ComputedStyle` (`CssValue[]`, `StyleFacts`), `CoreEvent`│
├────────────────────────────────────────────────────────────────────────────┤
│ L2: Text Shaping, Font & 2D Raster Primitives Layer (`core/text/*`,        │
│     `core/raster/*`, `core/net/*`, `core/media/*`)                         │
│   PDU: `FontFace`, `ShapedLine`, `GlyphBitmap`, `Pixmap`, `Path2D`         │
├────────────────────────────────────────────────────────────────────────────┤
│ L1: Physical Memory, Math & GPU Kernel Layer (`core/kernel/memory/*`,      │
│     `core/kernel/style/*`, `core/kernel/gpu/*`, `core/diag/*`, `api/dom`)  │
│   PDU: `MemorySegment` (`DocumentArena`, `AtomTable`), `PackedCssValue`    │
└────────────────────────────────────────────────────────────────────────────┘
```

#### 階層純度の現状と機械的固定（2026-11-10 実装: [`LayerDependencyRatchetTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/LayerDependencyRatchetTest.java)）

マクロ層（`api` $\leftarrow$ `core` $\leftarrow$ `common` $\leftarrow$ `client`/`server`）の単方向性は [`ApiBoundaryTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/src/test/java/com/nishiyu/enderframelib/api/ApiBoundaryTest.java) と [`ArchitectureTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/src/test/java/com/nishiyu/enderframelib/core/ArchitectureTest.java) で固定済み。`core.*` 内部（L1〜L6）の単方向性は **`LayerDependencyRatchetTest`** が固定する。

- **検査対象**: `core/src/main` の全 `.java` について、`import`（ワイルドカード含む）と**完全修飾名の直接参照**（コメントは除外）を走査し、「参照元の層 < 参照先の層」を逆方向参照として検出する。
- **層の割り当て**: `kernel/memory|style|gpu`・`diag` = L1、`text`・`raster`・`net`・`media` = L2、`dom`・`css`・`event`・`kernel/compiler`・`raster/svg` = L3（`kernel/compiler` は CSS/DOM を保持し、`raster/svg` は DOM を解析するため L3）、`layout` = L4、`paint` = L5、`core` 直下（`EngineContext`）= L6。
- **ラチェット**: 既知の逆方向参照 `KNOWN_UPWARD` は**増やせず、解消したら一覧から外さないと失敗**する（`PortabilityRatchetTest` と同方式）。目標は空集合。
- **外向き禁止**: `core.*` は `common` / `client` / `server` を参照できず、`api` は DOM・イベントの契約型（`api.dom` / `api.event`）のみ参照可（`coreNeverDependsOutward`）。
- **検査器自身の検証**: 上向きの `import`・ワイルドカード・FQN の検出、下向き/同層/コメントの非検出、層割り当て表の 5 テストで偽陰性を防ぐ。

**実装前の実測は 29 件の `import` ＋ 完全修飾名 3 箇所（計 11 系統）。9 系統を実際に解消し、2 系統は層の定義で解消した。`KNOWN_UPWARD` は空集合で、以後の新規逆方向参照はビルドで即失敗する。**

| 逆方向参照の向き | 状態 | 実施内容 / 残件と解消策 |
| :--- | :---: | :--- |
| **L3 (`dom`) $	o$ L4 (`layout`)**（`HostSyntax` → `HostElementProvider`） | ✅ 解消 | SPI [`HostElementProvider`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/HostElementProvider.java) と、それが返す [`CustomElementPainter`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/CustomElementPainter.java)、置換要素の性質 [`ReplacedKind`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/ReplacedKind.java)（旧 `ReplacedElements.Kind`）を `core.dom` へ移設。`META-INF/services` の登録名（`core/src/test/resources`・ルート `src/main/resources`）も新 FQN へ更新（旧名のままだと SPI が黙って無効化される） |
| **L3 (`css`) $	o$ L5 (`paint`)**（`AnimationParser` / `StyleAnimator` / `ShorthandExpanders`） | ✅ 解消 | `AnimationTiming` / `EasingFunction` / `AnimationSpec` を `core.paint.model` → `core.css.model` へ移設（`EasingFunction`・`AnimationSpec` の未使用 `paint.*` import も除去） |
| **L3 (`css`) $	o$ L4 (`layout`)**（`ComputedStyle` / `CssValue` / `StyleFacts`） | ✅ 解消 | 計画書旧版は import のみを数えていたが、実際は**完全修飾名で 3 箇所**残っていた（検査器が FQN も走査して発見）。純粋な文字列判定 `isIntrinsicSizeKeyword` / `numericFontWeight` を新設 [`ValueKeywords`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/ValueKeywords.java)（L3）へ移し、`LayoutMetrics` は委譲 |
| **L2 (`text`) $	o$ L3 (`css`)**（`WhiteSpace` / `WordBreak` → `KeywordMatch`） | ✅ 解消 | `KeywordMatch` を L1 `core.kernel.style` へ移設 |
| **L4 (`layout`) $	o$ L5 (`paint`)**（未使用ワイルドカード） | ✅ 解消 | 8 ファイルの未使用 `import core.paint.*;` を削除 |
| **L1 (`kernel/compiler`) $	o$ L3** | ✅ 解消（定義で） | `kernel/compiler` は CSS/DOM を保持するため L3 と定義（逆参照 0） |
| **L2 (`raster/svg`) $	o$ L3 (`dom`, `css`)** | ✅ 解消（定義で） | SVG は DOM を解析して描くため `raster/svg` を L3 と定義（`raster` 本体からの参照は 0、利用側は layout/paint のみ） |
| **L2 (`raster`) $	o$ L3**: `CanvasRenderingContext2D` → `css.ColorParser` | ✅ 解消 | L2 に SPI [`ColorSyntax`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/raster/ColorSyntax.java)（`ServiceLoader`）を新設し、L3 の `CssColorSyntax` が `ColorParser` へ委譲（`META-INF/services` 登録。`ColorParser` 自体は 20 箇所超から参照されるため移設しない） |
| **L2 (`text/font`) $	o$ L4/L5**: `FontFaceRules` → `LayoutMetrics` / `DisplayItem` | ✅ 解消 | `fontStyleFor(DisplayItem, …)` を L5 の [`DisplayItemFonts`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/DisplayItemFonts.java) へ移し、太さの解析は L3 `ValueKeywords` を使用（`WebFontManager` を新所在へ更新） |
| **L3 (`dom`) $	o$ L6**: `Bindings` → `EngineContext` | ✅ 解消 | L3 に SPI [`BindingHooks`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/BindingHooks.java)（翻訳・JSON 解析）を定義し、L6 の `EngineContext` が実装して静的初期化で供給元を設置。スレッドスコープの文脈切替（`callWith`）はそのまま効く。ホスト向けの公開フィールド（`EngineContext.global().i18nResolver` 等）は不変 |
| **L4 (`layout`) $	o$ L5**: `BoxMeasurer` → `ImageChoice` / `ImageSources` | ✅ 解消 | 依存のない純粋ロジックなので `core.paint` → L2 の `core.media` へ移設（paint 側は import を更新） |

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
| W1（GL ストリームの総項目数比例） | 🔧 **大幅改善** | 2,000 行 `hover` `13.05 → 3.50` ms、`text` `10.25 → 1.61` ms。Chrome 比は 24x → 6.4x（`hover`）、10x → 1.6x（`text`）。残りは Chrome の 0.55 ms に対する差 |
| W2（子の挿入・削除） | 🔧 **改善・未解消** | 2,000 行 `child` GL `25.6 → 13.8` ms、null `9.72 → 7.57`。500 行 GL `4.96 → 2.18`。Chrome（`1.09` ms）比は 23x → 12.6x。DisplayList 以外（レイアウト・確保量 `17 MB`/フレーム）が残る |
| W3（CPU 差分描画・スクロール再利用） | 🔧 **大幅改善（large-list）／ showcase は不変** | large-list 500 行 CPU: `scroll 3.28 → 1.39`、`hover 3.40 → 2.28`、`text 3.10 → 1.29` ms。showcase CPU は全面ダメージのため `4.4〜9.3 ms` のまま |
| W4（DisplayList 全走査） | 🔧 **改善** | 上記 GL/null の大規模値が低下（2,000 行 `child` null `9.72 → 7.57`）。ただし JMH `displayList`（フル構築）は不変で、効果は差分経路のみ |
| W5（幅変更・全面無効化レイアウト） | ❌ **未改善** | showcase `layoutWidthChange` `0.931 → 1.039` ms（Chrome 0.0835 の 12x）。`restyle`/`fullLayout` も不変 |
| W6（ヒープ確保量） | 🔧 **一部改善** | 2,000 行 `hover` GL は `5.1 MB → 1.3 MB`/フレーム。`fontsize` 2,000 行 `25 MB`、`child` 2,000 行 `19 MB` は依然大きい |
| W7（open） | 変化小 | showcase null `6.19 → 5.51`、GL `5.39 → 5.11` ms（Chrome `5.0` と同等）。500 行 GL `9.22 → 6.17` ms |

**未解消の主課題**: (1) `child`・`fontsize`・`inherited` のような**構造/全面変更系**が 2,000 行で Chrome の 1.7〜12.6 倍、(2) 幅変更レイアウト（W5）、(3) showcase 規模の CPU バックエンド全面再ラスタ、(4) `softwarePaint` showcase の JMH 悪化の原因調査。
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

#### 🧭 再策定（2026-11-11）：再計測（§6.7）に基づく新ロードマップ R0〜R7

**再計測が示した事実**

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
| **R2** | 🔧 **部分達成（2026-11-11・§6.10）** | **子要素の挿入・削除（`child`）の残差解消（W2）** | 2,000 行 `child` GL `14.1` ms（Chrome `1.09`・12.9 倍） | スライスパッチ（2 層・座標追従・親寸法同期）を実装し命中 100%・確保削減（500 行 `1.2 MB`）は達成。時間は実装直後は未達（2,000 行 `約 18 ms`）だったが、窓付き表示リスト（§8.6.2）で `2.25 ms` に短縮。原因は後続項目の再生成と下流索引の全面作り直し（§6.10.2）で、現行方式の延長では届かない | 2,000 行 `child` GL **`< 3 ms`**（Wave 1.7 の RTI・Paint 表で再判定）、500 行 `< 0.9 ms`、確保 `< 2 MB` |
| **R3** | 🥇 最優先 | **全面無効化（`fontsize`/`inherited`）＋ 幅変更 ＋ 可視範囲限定のスタイル・レイアウト（W5/W6 / T5・T7-①）** | 2,000 行 `fontsize` GL `18.9`（Chrome `11.3`）、確保 `25 MB`。500 行 `inherited` GL `3.59`（Chrome `0.96`・3.8 倍）。showcase 幅変更 `1.04`（Chrome `0.084`） | ① T5: 測定 4-way キャッシュ＋`place` 制約スキップ（P1-1）。② `content-visibility: auto` 相当の**ビューポート外スキップ**（`FenwickVirtualList` を `BlockLayoutEngine` へ接続・P3-3 を前倒し）。③ `Resolved`・`DisplayItem`・クリップ列の再利用（スロット化）でフレーム確保を削減。④ `inherited` は継承値を使うボックスだけへ伝播を限定 | 500 行 `inherited` GL **`< 1.5 ms`**、2,000 行 `fontsize` GL **`< 10 ms`**（Chrome `11.3` 超え）、showcase 幅変更 **`< 0.2 ms`**、`fontsize` 2,000 行の確保 **`< 5 MB`** |
| **R4** | 🥈 高 | **CPU バックエンドのダメージ領域全面化の解消（W3 の残り）** | showcase CPU `hover` `4.59`、`inherited` `8.25` ms（large-list 500 行 `hover` `2.28` より重い）。Chrome CPU 合成 `1.55` | ① R0 で Tile Dirty の「全面ダメージ化」「見積外れ abort」の理由別カウンタを出す。② 影・ブラー・`backdrop-filter`・`mix-blend` 等のフィルター層が AABB を全面に広げていないか確認し局所化。③ 差分描画の見積コストが全面描画を超える閾値判断の再調整。④ ~~JMH `softwarePaint` 悪化の修正~~（R0 で**退行ではない**と判明: §6.8.3）。代わりに showcase の `softwarePaint`（同一リスト再描画）が `4.5 ms` で large-list `0.31 ms` の 14 倍かかる理由（差分判定が効かない項目の特定）を調べる | showcase CPU `hover` **`< 1.5 ms`**（Chrome CPU `1.55` 並み）、`inherited` `< 4 ms` |
| **R5** | 🥉 中（条件付き） | **項目単位の保持 GPU 記録（T3）** | 2,000 行 `hover` は GL 送出 `0.77` ms（22%）で律速でないが、**`child` `6.2` ms（44%）・`inherited` `5.2` ms（33%）・`fontsize` `5.4` ms（28%）は 30% 前後以上**。R1〜R3 後に再判定（`child` はすでに条件を満たす） | 現行設計（`MemorySegment.copy` 一括転送＋容量 4,096 撤廃）を温存。着手判定は R0 の段別で **GL 送出 > フレームの 30%** | 着手時: 2,000 行 `text` GL `< 0.8 ms` |
| **R6** | 🥉 中 | **オープン・コールド JVM（W7 / T6）** | showcase `open` `5.5` ms（Chrome `5.0`）、500 行 GL `6.2` ms、2,000 行は未再計測 | DOM 雛形クローン・`sealNodeIds` 統合・`EnderEngine.prewarm()`。**コールド JVM 初回フレームを計測に追加** | 初回フレーム p99 を台帳化、2,000 行 `open` `< 20 ms` |
| **R7** | 🔒 横串 | **退行防止ラチェットの拡張** | 今回の改善（パッチ命中・確保量）は時間でなくカウンタで固定できる（時間ベンチは全件実行で不安定） | `AllocationRatchetTest` に 500 行 `hover`/`text`/`child` の確保上限、`UiView` の **パッチ命中率ラチェット**（`hover` 往復で slice-patch 100%・`child` 100%）、全再構築との一致ランダム列テスト（`DisplayListSlicePatchTest` を style/hover へ拡張） | 追加テスト全件グリーン、`:core:test` 全件グリーン |

**実施順序**: R0（完了）→ R1（完了）→ R2（部分達成。時間 KPI は Wave 1.7 で再判定）→ R3（Wave 1.7 の施策 2・3 として最優先）→ R4 → R6 → R5（条件付き）。R7 は各段に同伴。各段の完了ごとに §6.7 形式で再計測し台帳を更新する。**P2（3D）・P3（IME 等）・P4（DX）は性能 KPI（R1〜R4）が Chrome 比 2 倍以内に入るまで着手しない**（ただし IME は日本語 UI の必須要件のため R3 と独立に並行着手可）。

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

R3（§7.3）は施策 2・3 とほぼ同一作業なのでそちらで進める。R2 の時間 KPI（`child` `< 3 ms`）は施策 4・5 の完了で再判定する。

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
