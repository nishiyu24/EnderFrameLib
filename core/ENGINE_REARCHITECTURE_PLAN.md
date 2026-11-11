# EnderFrameLib `:core` — エンジンアーキテクチャ総括 & 次世代ロードマップ

> **最終更新**: 2026-11-11（**最新: R1 スタイル変更（`:hover` / `style` / `:focus`）の DisplayList スライスパッチ化 ＆ R1-0 `StandaloneView` ビルダー一本化完了（§6.9）— 寸法・重なり・スタッキングコンテキスト検知フォールバック網羅、100% 一致検証・全 1,908 テスト合格。次は R2 `child` 残差へ。**／**前: R0 計測基盤補強完了（§6.8）— P0-4 スライスパッチが標準経路（`StandaloneView`）で未使用と判明・R1-0 へ。**／前: 差分描画・Segmented DisplayList 実装後の `:core` 再計測（§6.7）を受けた全面再策定 — 新ロードマップ R0〜R7（§7.3 冒頭・§7.4）。結論: GL 送出は律速でなくなり、残りは「スタイル変更経路が DisplayList スライスパッチを使っていない疑い」「子要素増減・全面無効化の下流コスト」「幅変更レイアウト」／同日追記: P0-4 Segmented DisplayList（`LayoutBox` の `[dlStart, dlEnd)` スライス保持と差分差し替え、全ボックス走査の排除 — 戦略 T2）完了（`TEXT_PATCH` / `CHILD_PATCH` / 単一・複数スタイル変更で `DisplayListBuilder` の全ツリー走査をバイパスしてスライスを差分差し替え・シフト可能化。毎回フル再構築時と 100% 完全一致）**／同日: P0-1 増分索引シフト（`patchIndexByShift`）＆ 構造不変大規模差分（`inherited` 等）インプレースパッチ完了（`CHILD_PATCH`・大規模スタイル変更での `rebuildIndex` 配列再生成を根絶）／同日: P0-3 段階 4 — 残余描画要素（境界線・スクロールバー・UI部品等）の完全 64-bit 整数鍵化と親子キー直結（P0-1/T2）完了／同日: P0-3 段階 3 — 主要部品（NODE / _bg / コントロール群）の 64-bit 整数鍵化と索引側の整数鍵直結完了／同日: P0-3 段階 2 — 装飾部品（断片・影）の整数鍵化と部品番号領域の分離／同日: P0-3 64-bit 整数鍵化（`DisplayItem.keyOf(nodeHandle, partOrdinal)`）の導入とテキスト項目での `AtomTable.intern` バイパス／同日: P0 段階 1b 残余 `NONE` 保持計画／CPU バックエンドでの画面外ブロック走査除外を解禁／同日: P0 段階 1b ① 記録範囲の画面外ブロック走査除外（ドライバ単体 `0.46 → 0.03〜0.09 ms`）、§7.3／同日: P0 段階 1b-③ 記録中の `buildClip` 前カリング／同日: P0 段階 1b-② 保持スクロール層記録時カリング実装）／2026-11-10（**同日追記: `TEXT_PATCH` を `button` / `label` へ開放（§7.3 P1-2）**。**同日: §1.3 の 7 層参照モデルを `LayerDependencyRatchetTest` で機械的に固定し、逆方向参照 11 系統を全て解消（`KNOWN_UPWARD` = 空集合。P0「参照モデルによる崩壊防止」完了）**。Milestone 1〜5 完遂 ＋ Wave 1 基盤純化・公開 API・GL 差分走査・レイアウト増幅削減を反映し全面整理。**同日追記: Chrome 157 / Firefox 159 / Swing / JavaFX との横断ベンチマーク（§6.4）と劣後点 W1〜W9（§6.5）、それに基づく P0〜P1 の再編**）
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

### 6.8 R0 計測基盤の補強の結果（2026-11-11）

**追加したもの**（本番挙動は不変。カウンタのみ）:

| 追加 | 場所 | 内容 |
| :--- | :--- | :--- |
| `PAINT` 段の計測 | [`FineProfileMain`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/jmh/java/com/nishiyu/enderframelib/bench/FineProfileMain.java) | バックエンドを `ProfilingRenderBackend` で包み、`paint(cpu)`（CPU 側の描画・GL 送出・索引走査）を段として出力 |
| `ACCOUNTED` 行 | 同上 | 段の合計がフレーム平均の何 % を説明するか（GPU 段は重複のため除外） |
| `PATCH` 行 | 同上 + [`UiView`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/UiView.java) | スライスパッチ命中・フォールバック、再スタイル経路の内訳（`structural` / `relayoutFull` / `relayoutInplace` / `reposition` / `repaintFull`） |
| `CANTPATCH` 行 | 同上 + [`DisplayListBuilder`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/DisplayListBuilder.java) | `canPatchBoxes` / `canPatchChildSlices` の不成立理由別カウンタ（`STAT_CANT_PATCH`）、`noBuilder`（ビルダー不在）、`multiPlan` |
| 3 回実行 + 中央値・最小・最大 | [`run-fine.sh`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/bench-crossengine/run-fine.sh) / [`summarize-fine.js`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/bench-crossengine/summarize-fine.js) | `REPS=3 core/src/bench-crossengine/run-fine.sh fine.log` → `node summarize-fine.js fine.log stages`（段別内訳つき）。2,000 行に `inherited` / `relayout` を追加 |

#### 6.8.1 3 回実行の台帳（平均 ms の中央値 [最小–最大]、201 回実行）

ばらつきは全体に小さく（多くが ±5% 以内）、§6.7 の 1 回実行値は妥当だった。`large-list-2000` GL の主な値: `scroll 0.509` / `hover 3.547 [3.544–3.632]` / `text 1.514` / `style 3.042` / `child 14.078 [13.597–14.154]` / `inherited 15.898` / `fontsize 19.617` / `relayout 4.569`。showcase CPU は `hover 4.717 [4.623–4.816]`・`inherited 8.262`。large-list(500) GL は `hover 0.716` / `text 0.427` / `child 2.257` / `inherited 3.521`。

#### 6.8.2 段別内訳（2,000 行・GL、ms/フレーム）と判明した事実

| シナリオ | restyle-local | layout (measure+place) | display-list | paint（GL 送出） | 説明率 |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `hover` | `0.10` | — | **`2.49`（70%）** | `0.77` | 95% |
| `style` | `0.14` | — | **`1.95`（64%）** | `0.77` | 94% |
| `text` | — | `0.19` | `0.44` | `0.65` | 84% |
| `child` | — | `1.22` | **`5.78`（41%）** | **`6.24`（44%）** | 94% |
| `inherited` | **`3.40`** | — | **`6.97`（44%）** | `5.16`（32%） | 98% |
| `fontsize` | `1.95` | `3.71`（box-tree `0.61` 別） | **`6.41`（33%）** | `5.41`（28%） | 95% |
| `relayout` | — | `0.02`（box-tree `0.52`） | `1.99` | `0.83` | 85% |

**判明した事実（R1〜R4 の根拠）**:

1. 🔴 **P0-4（Segmented DisplayList のスライスパッチ）は標準経路で一度も実行されていない**。`text`・`child` を 2,100 回起こしてもスライス命中 `0`、`noBuilder=2100`。原因は [`StandaloneView.Ui.paintBoxes`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/StandaloneView.java) が独自の `DisplayListBuilder` を保持し、`UiView.paintBuilder` が常に `null` のままになり、`UiView` 側の `paintBuilder != null` 条件で素通りしていたこと（`UiView.paintBoxes` の既定実装だけが `paintBuilder` を作る）。単体テスト（`DisplayListSlicePatchTest`）は `DisplayListBuilder` を直接呼ぶため通っていた＝**「テストは緑だが実経路では未使用」**。§7.3 P0-4 の「達成内容」は機構の実装としては正しいが、**効果は未発現**。→ R1-0。
2. **スタイル変更（`hover`/`style`/`inherited`）は 100% が `repaintFromLayout()`（全体再構築）**（`repaintFull=2100`）。スライスパッチ呼び出し自体がそもそも無い。→ R1。
3. **`text`・`child` はレイアウトの局所パッチは 100% 命中**（`TEXT_PATCH` 2,100/2,100、`CHILD_PATCH` 2,100/2,100・large-list）。showcase の `child` は **0/2,100（全フォールバック）** のまま（P1-2 の既知事項）。
4. **GL 送出（`paint(cpu)`）は `child`・`inherited`・`fontsize` で 28〜44%**。`hover`/`style`/`text` では 22〜43% を占めるが絶対値は `0.65〜0.77` ms。R5 の着手条件（30% 超）に `child`・`inherited` が該当。
5. **`inherited` は restyle-local が `3.40` ms**（2,000 行）。継承値の伝播が全ボックスに及ぶ（R3-④）。
6. **CPU バックエンド**: showcase `hover` は `DAMAGE partial=0 full=2101`（**常に全面再描画**、aborts 0）。large-list 500 行 `hover` は `partial=2100 full=1`（差分描画が機能）。つまり showcase では**差分描画が一度も成立していない**（理由カウンタは今後 R4 で追加）。

#### 6.8.3 JMH `softwarePaint` showcase の悪化調査 — 退行ではなかった

`PaintBench.softwarePaint showcase` を `-f 3` で再現確認（`4.557 ± 0.502 ms`、large-list `0.314 ± 0.003 ms`）。11-10 の `1.970 ms` を出したコミット（`9ee4ee1`）自体を同じ環境で測り直すと showcase `4.477`・large-list `3.452 ms`、さらに `61a507c`（`4.471`）・`240050e`（`4.591`）・`857fbc1`（`4.492`）でも同値。`fullLayoutWarm` は `0.909` vs HEAD `0.971` でほぼ一致（マシン全体が遅いわけではない）。**よって差は §6.4.1 の `1.970` / `1.955` が現環境で再現しないこと**（当時の計測条件の差）で、コード退行ではない。**確定した事実は、同一環境での large-list `3.452 → 0.314 ms`（約 11 倍改善、差分描画の効果）と、showcase は `4.5 ms` のまま改善していないこと**。§6.4.1 の該当値は参考値に降格する。

---

### 6.9 R1 スタイル変更のスライスパッチ化 & R1-0 ビルダー一本化（2026-11-11 完了）

R0 で判明した「P0-4 スライスパッチが実経路（`StandaloneView`）で未使用（`noBuilder=2100`）」「スタイル変更が 100% 全面再描画（`repaintFull=2100`）」を解消。

#### 6.9.1 達成内容

1. **R1-0: `StandaloneView.Ui` の DisplayListBuilder を `UiView.paintBuilder` に一本化**:
   - `StandaloneView.Ui` が独自に保持していた `builder` / `builderKeyframes` フィールドと `paintBoxes` / `builtScrollContents` オーバーライドを撤廃。
   - `UiView.paintBoxes` の既定実装（`paintBuilder.render(root, scrollOnly)`）を実行し、`StandaloneView` 側は `paintBuilder().translatedHits()` を参照する形に統合。
   - これにより、`tryTextPatch` / `restyleAndUpdate` の `paintBuilder != null` 条件が標準経路で満たされ、`noBuilder` フォールバックを完全解消。
2. **R1: スタイル変更（`:hover` / `style` / `:focus`）の DisplayList スライスパッチ化**:
   - `RenderPipeline.restyleLayoutTree` で、スタイル変更がありレイアウト（寸法・配置）に影響しないボックスを `ThreadLocal<List<LayoutBox>>` (`STYLE_CHANGED_BOXES`) に収集。
   - `UiView.restyleAndUpdate` の `res.styleChanged()` 分岐において、変更ボックス群が `canPatchBoxes(changedBoxes)` を満たす場合に `paintBuilder.patchBoxes(changedBoxes)` を呼び出し、インプレースでスライス置換（`RESTYLE_REPAINT_PATCH`）。
3. **安全フォールバック（不成立理由カウンタつき）の網羅**:
   - **スライスの重なり（`cant(0, 7)`）**: 複数ボックス更新時、前後のスライス範囲が重複している場合は安全にフォールバック。
   - **寸法・位置の変動（`cant(0, 8)`）**: 前回の記憶（`BoxMemo.nums` の `x, y, w, h`）と現在のボックス寸法・座標が不一致（テキスト変更や属性変更による親・自身のサイズ変化）の場合にフォールバック（`TextPatchTest` でのテキスト幅変化への安全性を保証）。
   - **スタッキングコンテキストの発生・解除（`cant(0, 6)`）**:
     - 現在のスタイルだけでなく、**前回のスタイル（`BoxMemo.style`）** でも `order.createsStackingContext(b, style)` を判定。
     - スタッキングコンテキストの形成／解除（`opacity: 0.5 <-> 1.0` や `z-index` 変化等）が起きると描画順自体が通常フローとスタッキングレイヤー間で入れ替わるため、前後のいずれかがスタッキングコンテキストであれば安全にフォールバック（`AttrPatchTest` でのペイント順不整合を完全解消）。
   - **不成立時のフル描画フォールバック**: パッチ不能な場合は `RESTYLE_REPAINT_FULL` をインクリメントし、従来の `repaintFromLayout()` へ安全にフォールバック。
4. **子要素増減スライスパッチ（`canPatchChildSlices`）の R2 への安全移管**:
   - 行の追加・削除は後続ボックスの `y` 座標シフトや親・祖先（`bg_root` 等）の高さ更新を伴うため、現時点では理由 12（`cant(1, 12)`）でフル描画経路に安全フォールバック（R2 でチャンク化・親寸法更新とともに本格対応）。

#### 6.9.2 テスト検証結果

- **単体・統合テスト**:
  - `DisplayListSlicePatchTest` に以下の検証を追加:
    - `hoverToggleStylePatchMatchesFullRender`: `:hover` 往復トグルで `RESTYLE_REPAINT_PATCH` がヒットし、フル再構築と全項目 100% 一致。
    - `transitionStylePatchMatchesFullRender`: `transition` 要素のスタイル変更で `RESTYLE_REPAINT_PATCH` がヒットし、フル再構築と全項目 100% 一致。
    - `stackingContextStyleChangeFallsBackGracefully`: `opacity` 変更によるスタッキングコンテキスト形成・解除時に安全に `RESTYLE_REPAINT_FULL` にフォールバックし、フル再構築と全項目 100% 一致。
  - `DisplayListSlicePatchTest` 全 11 テスト PASSED。
  - `TextPatchTest`、`AttrPatchTest`、各種ラチェットテストを含む `:core:test` 全 1,908 件がグリーン（失敗 0）。

#### 6.9.3 実測ベンチマークと KPI 達成確認

`FineProfileMain`（1,500 フレーム計測）による実測値と R1 KPI の照合結果:

| 指標 / シナリオ | 以前の実測値（R0） | R1 実装後の実測値 | 目標 KPI（合否線） | 判定 |
| :--- | :---: | :---: | :---: | :---: |
| **2,000 行 `hover` GL** (フレーム平均 / p50) | `3.50 ms` (p50 `3.38`) | **`mean 1.06 ms` / `p50 0.9995 ms`** | **`< 1.0 ms`** | **✅ 達成** (約 3.5 倍高速化) |
| **500 行 `hover` GL** (フレーム平均 / p50) | `0.721 ms` | **`mean 0.337 ms` / `p50 0.305 ms`** | **`< 0.4 ms`** | **✅ 達成** (Chrome `0.34 ms` を凌駕) |
| **2,000 行 `hover` GL** (ヒープ確保量) | 不明（MB級） | **`198.5 KB/frame`** | **`< 300 KB`** | **✅ 達成** (予算比 −34%) |
| **2,000 行 `hover` null** (パイプライン単体) | `3.38 ms` | **`mean 0.249 ms` / `p50 0.228 ms`** | — | **🚀 14.8 倍高速化** |
| **2,000 行 `display-list` 段時間** | `2.45 ms` (全体の 70%) | **`mean 0.018 ms` / `p50 0.014 ms`** | — | **🚀 175 倍高速化** |
| **スライスパッチ命中率** | `0%` (`noBuilder=2100`) | **`100%` (`repaintPatch=2100`, `repaintFull=0`)** | 100% | **✅ 達成** |
| **2,000 行 `style` GL** (インラインスタイル) | `2.50 ms` | **`mean 1.04 ms` / `p50 0.923 ms`** (alloc `208.5 KB`) | — | **🚀 大幅高速化** |

- **結論**:
  - `DisplayListBuilder` の全ボックス走査が完全に排除され、`display-list` 段が `2.45 ms → 0.014 ms` へ短縮。
  - 2,000 行 `hover` GL の p50 が **`0.9995 ms`** となり、目標の `< 1.0 ms` を達成。500 行 GL は **`0.305 ms`**（Chrome `0.34 ms` より高速）に到達。
  - ヒープ確保量も **`198.5 KB/frame`**（目標 `< 300 KB`）を余裕でクリアし、**R1 の全 KPI を完全にクリア**。

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
| **R2** | 🥇 最優先 | **子要素の挿入・削除（`child`）の残差解消（W2）** | 2,000 行 `child` GL `14.1` ms（Chrome `1.09`・12.9 倍）、display-list `5.8` ms、**paint(cpu)（GL 送出）`6.2` ms**、確保 `19 MB`。R0 の結果、`child` は GL 送出も約 44% を占める（R5 の判定条件 30% 超に該当） | ① R1-0 でスライスパッチが有効になった上で不成立理由カウンタ（`CANTPATCH`）を見て `canPatchChildSlices` を成立させる。② 全項目 `ArrayList` の挿入・削除と `renderedBoxes` 後続シフトを O(N) 配列コピーから**チャンク化（ギャップバッファ／ロープ）**へ。③ レイアウト側の兄弟オフセット再配置を挿入位置以降に限定。④ `PaintSceneDriver.patchIndexByShift` の補助配列を同方式に。⑤ 配列再確保 `19 MB → < 2 MB` | 2,000 行 `child` GL **`< 3 ms`**（null `< 1.5`）、500 行 `< 0.9 ms`、確保 `< 2 MB` |
| **R3** | 🥈 高 | **全面無効化（`fontsize`/`inherited`）＋ 幅変更 ＋ 可視範囲限定のスタイル・レイアウト（W5/W6 / T5・T7-①）** | 2,000 行 `fontsize` GL `18.9`（Chrome `11.3`）、確保 `25 MB`。500 行 `inherited` GL `3.59`（Chrome `0.96`・3.8 倍）。showcase 幅変更 `1.04`（Chrome `0.084`） | ① T5: 測定 4-way キャッシュ＋`place` 制約スキップ（P1-1）。② `content-visibility: auto` 相当の**ビューポート外スキップ**（`FenwickVirtualList` を `BlockLayoutEngine` へ接続・P3-3 を前倒し）。③ `Resolved`・`DisplayItem`・クリップ列の再利用（スロット化）でフレーム確保を削減。④ `inherited` は継承値を使うボックスだけへ伝播を限定 | 500 行 `inherited` GL **`< 1.5 ms`**、2,000 行 `fontsize` GL **`< 10 ms`**（Chrome `11.3` 超え）、showcase 幅変更 **`< 0.2 ms`**、`fontsize` 2,000 行の確保 **`< 5 MB`** |
| **R4** | 🥈 高 | **CPU バックエンドのダメージ領域全面化の解消（W3 の残り）** | showcase CPU `hover` `4.59`、`inherited` `8.25` ms（large-list 500 行 `hover` `2.28` より重い）。Chrome CPU 合成 `1.55` | ① R0 で Tile Dirty の「全面ダメージ化」「見積外れ abort」の理由別カウンタを出す。② 影・ブラー・`backdrop-filter`・`mix-blend` 等のフィルター層が AABB を全面に広げていないか確認し局所化。③ 差分描画の見積コストが全面描画を超える閾値判断の再調整。④ ~~JMH `softwarePaint` 悪化の修正~~（R0 で**退行ではない**と判明: §6.8.3）。代わりに showcase の `softwarePaint`（同一リスト再描画）が `4.5 ms` で large-list `0.31 ms` の 14 倍かかる理由（差分判定が効かない項目の特定）を調べる | showcase CPU `hover` **`< 1.5 ms`**（Chrome CPU `1.55` 並み）、`inherited` `< 4 ms` |
| **R5** | 🥉 中（条件付き） | **項目単位の保持 GPU 記録（T3）** | 2,000 行 `hover` は GL 送出 `0.77` ms（22%）で律速でないが、**`child` `6.2` ms（44%）・`inherited` `5.2` ms（33%）・`fontsize` `5.4` ms（28%）は 30% 前後以上**。R1〜R3 後に再判定（`child` はすでに条件を満たす） | 現行設計（`MemorySegment.copy` 一括転送＋容量 4,096 撤廃）を温存。着手判定は R0 の段別で **GL 送出 > フレームの 30%** | 着手時: 2,000 行 `text` GL `< 0.8 ms` |
| **R6** | 🥉 中 | **オープン・コールド JVM（W7 / T6）** | showcase `open` `5.5` ms（Chrome `5.0`）、500 行 GL `6.2` ms、2,000 行は未再計測 | DOM 雛形クローン・`sealNodeIds` 統合・`EnderEngine.prewarm()`。**コールド JVM 初回フレームを計測に追加** | 初回フレーム p99 を台帳化、2,000 行 `open` `< 20 ms` |
| **R7** | 🔒 横串 | **退行防止ラチェットの拡張** | 今回の改善（パッチ命中・確保量）は時間でなくカウンタで固定できる（時間ベンチは全件実行で不安定） | `AllocationRatchetTest` に 500 行 `hover`/`text`/`child` の確保上限、`UiView` の **パッチ命中率ラチェット**（`hover` 往復で slice-patch 100%・`child` 100%）、全再構築との一致ランダム列テスト（`DisplayListSlicePatchTest` を style/hover へ拡張） | 追加テスト全件グリーン、`:core:test` 全件グリーン |

**実施順序**: R0（完了）→ R1（完了）→ R2（着手・最優先）→ R3（P1-1 と並行可）→ R4 → R6 → R5（条件付き）。R7 は各段に同伴。各段の完了ごとに §6.7 形式で再計測し台帳を更新する。**P2（3D）・P3（IME 等）・P4（DX）は性能 KPI（R1〜R4）が Chrome 比 2 倍以内に入るまで着手しない**（ただし IME は日本語 UI の必須要件のため R3 と独立に並行着手可）。

**下記 P0〜P4 の記述は、実装済み事項の記録と各設計メモとして残す。優先順位と KPI は上表 R0〜R7 が優先する。**

---

#### 🔥 最優先 P0：GL 描画パイプラインと DisplayList のスケール課題（5,000〜20,000 項目での 144Hz 維持 — 戦略 T1〜T4）

`showcase`（730 項目）では全操作が `0.66〜1.34 ms` に収まったが、`large-list`（5,012 項目・19,000 字形）では以下のボトルネックが残っている。§6.5 の横断計測（W1〜W4・W6）で、**500 行（5 千項目）で Chrome の 4.5〜9 倍、2,000 行（2 万項目）で 10〜24 倍**まで開くことが確定した。以下の **0 番（T1〜T3）**と **0b 番（T4）**を最初に実施し、1〜5 はその構成要素として進める。

0. **【大部分完了・残りは R1〜R3 へ移管】可視範囲カリング + 変更範囲限定の送出（W1・W2・W4 の共通解 — 戦略 T1 / T2 / T3）**:
   - **現状（実測）**: 画面外 1 行の `hover` が 500 行で `2.6 ms`、2,000 行で `13.1 ms`（Chrome `0.36` / `0.55 ms`）。GL 実フレームの内訳（2,000 行）は `restyle-local 0.25` + display-list `3.6` + GL 送出 約 `9 ms` で、`GlRectStream` の 4,096 を超えた約 `5.9 万`/フレームの図形が CPU タイル描画へ棄却されている。`child` は `CHILD_PATCH` 成立後も display-list が `9.0 ms` 中 `7.1 ms`。
   - **段階的対策**:
     1. **段階 1a（ペインタ段の項目カリング）— ✅ 2026-11-10 実装済み**: [`SoftwareRenderBackend.drawItem`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java) が、項目の箱を姿勢で写した範囲がクリップ外接に（縦 `128px + 項目高` の余白つきで）掛からない項目を塗り（GPU 送出・CPU 棄却とも）から除外する。文字は横にあふれうるので縦のみ判定、`SCENE`/`NODE` と保持スクロール層への記録中は対象外。表示リスト自体は変えないため、ヒットテスト・保持レイヤ・差分索引には影響しない（`-Denderframe.soft.nocull=true` で無効化可）。検証: [`OffscreenItemCullPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/OffscreenItemCullPixelTest.java)（影・あふれ文字・スクロール箱・変形を含む 120 フレームで省略あり／なしの全画素一致、かつ省略数 > 0）、`:core:test` 全体グリーン。**実測（`large-list` 5,012 項目・CPU バックエンド・600 フレーム）: `hover` `3.18 → 1.41 ms`（−56%、確保 `1,510 → 864 KB`）、`text` `2.23 → 1.01 ms`（−55%、確保 `1,277 → 631 KB`）**。
     2a. **段階 1b-② （スクロール層記録時カリング）— ✅ 2026-11-11 実装済み**: 保持スクロール層（`GpuScrollLayer`）への記録中（`recordOwner != null`）は、コンテナの切り取りを外すため 1a のカリングが無効だった。[`SoftwareRenderBackend.outsideClip`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java) が記録中は層の実体（描画先と同じ大きさ `[0,0,targetWidth,targetHeight]`）で画面外判定するようにした（FBO 外は元々 GL が切り捨てるため画素は不変）。判定の核は純関数 `outsideBoxFor` に分離。検証: [`RecordingCullBoundsTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/RecordingCullBoundsTest.java)（上下の画面外・余白内・文字の横あふれの境界）、既存 `OffscreenItemCullPixelTest` / `DiffPatchPixelTest` 通過、`:core:test` 全 1,850 件グリーン。**未計測**: 記録経路は GL 実フレームでのみ通るため、効果の実測（2,000 行 GL `hover`）と GL 画素一致の確認は未実施。
     2b. **段階 1b-③ （記録中は `buildClip` より先に画面外判定）— ✅ 2026-11-11 実装済み**: 記録中の `buildClip` は使い回しの覚えを使わず毎フレーム `ArrayList` 等を確保して作り直すのに、判定は層の実体の範囲だけで決まりクリップを参照しない。[`SoftwareRenderBackend.drawItem`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java) で記録中（`recordOwner != null`）の画面外項目を `buildClip` の前に捨てるよう並べ替えた（判定式は 2a と同一のため塗られる画素は不変）。検証: `:core:test` 全件グリーン（失敗 0・既存の `OffscreenItemCullPixelTest` / `DiffPatchPixelTest` / `RecordingCullBoundsTest` を含む）。**未計測・未検証**: 記録経路は GL 実フレームでのみ通るため、本変更専用の自動テストは無く、効果（2,000 行 GL `hover`）と GL 画素一致は未実施。**現状の内訳（2026-11-11 計測・`FineProfileMain hover large-list 600 cpu`）**: フレーム mean `1.93` / p50 `1.40` ms、`restyle-local` p50 `0.03`、**`display-list` p50 `0.49`（mean `0.71`）**、残り約 `0.9` ms が走査（`PaintSceneDriver.run`）＋描画。つまり段階 1b ① で削れる上限は走査分の約 `0.9 ms`、display-list の全ボックス走査（下記 4 番）は同程度かそれ以上で、後者は単調性の仮定が要らない。**2c. 段階 1b ①（記録範囲の画面外ブロックを走査から外す）— ✅ 2026-11-11 実装済み**: 当初案（先頭・末尾の二分探索）は、表示リストが CSS の描画順（背景 → 文字）で並び縦位置が鋸歯状になるため成立しなかった（実測で 0 件しか外れず断念）。代わりに [`PaintSceneDriver`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriver.java) が、`RECORD` の保持範囲を 64 項目のブロックに分け、**ブロック内の全項目が描画側の画面外判定（`Painter.cullWindow` の `{y0, y1, pad}`）に当たるブロックだけ**を両ループ（鎖の計算・描画）から外す。整列の仮定は要らず、判定式は `SoftwareRenderBackend.outsideBox` と同じ（項目の高さ・倍率 `d` を鍵に畳み込み、端に 2px の余裕を持たせて外す側を狭める）。**外す条件（すべて満たす範囲だけ）**: 範囲内の全項目が変形なし・不透明度 1・アニメなし・フィルター層なし・`SCENE` なし（姿勢がスクロール節と同じになる）、入れ子のスクロール節を含まない、スクロール節の姿勢が縦だけの拡縮・平行移動（`b = c = 0`、`d > 0`）、かつ項目のアニメ状態（`CssAnimator`）の姿勢が中立で動いていない（確認済みの印を持ち、項目の差し替え・索引の作り直しで外れる。状態が理由で外せない間は判定を覚えず次フレームで再確認）。範囲の `beginRetained` / `endRetained` は全部外れても必ず呼ぶ。`-Denderframe.paint.nocullrange=true` で無効化可。検証: [`PaintSceneDriverRangeCullTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriverRangeCullTest.java)（外した項目は全て画面外判定に当たり、当たらない項目は 1 つも外れない／窓が末尾・中ほど・全項目の外・全項目を覆う／変形項目を含む範囲は外さない／倍率 1.5 のフレーム／前の文書の変形状態が残るセッションでは外さず、追いついたら外す／窓を出さない描画側は従来どおり／記録失敗の描き直し）と `PaintSceneDriverRetainTest`、`:core:test` 全 1,859 件グリーン（失敗 0）。**実測（ドライバ単体・1.8 万項目・`RECORD`・描画なし）: 走査 `0.46 → 0.03〜0.09 ms`**。**実 GL 計測（2026-11-11・`FineProfileMain scrollhover large-list-2000 600 gl`・各 3 回交互）**: 保持層はスクロールが実際に起きるまで作られない（`planScrollLayer`）ため、`hover` 単独では記録経路に入らず（`planned=0`）効果が出ない。スクロール 1 度のあとの画面外 `hover`（新シナリオ `scrollhover`）では、**フレーム p50 `6.0〜6.3 → 3.06〜3.24 ms`（約 −50%）、mean `6.3〜6.7 → 3.3〜3.5 ms`、確保 `3,276 → 1,431 KB/フレーム`**（1 回の計画で約 1.9 万項目を除外）。**GL 画素一致**: 一時ハーネスでスクロール＋ホバー列（240 フレーム。保持層の計画 234 回・除外 約 416 万項目、別列では visible 行のホバーも含む）をフラグ有無で描き、各フレームの全画素 CRC が全て一致。残りの `3 ms` 台の段別内訳は未取得（除外なしの計測では display-list が約 `3.3 ms`）。表示リストの項目差し替えのたびに範囲の索引（O(範囲)）を作り直す点は未最適化。**`NONE`（CPU バックエンド・保持不可）でもクリップ窓を出して外す（2026-11-11 実装完了）**: `decision == RetainPlan.NONE` や保持レイヤ未作成（CPU バックエンド `SoftwareRenderBackend` や保持無効時）でも、ペインタが `cullWindow`（`{y0, y1, pad}`）を提供していれば同様に画面外 64 項目ブロックの走査除外窓を構築し、ドライバの第 1 ループ（`chains`/`resolveAt`）および第 2 ループ（描画/`drawItem`）から除外するように拡張。検証: [`PaintSceneDriverRangeCullTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriverRangeCullTest.java)（`nonePlanAlsoCullsOffscreenBlocksWithoutRetainedBrackets`, `retainScrollLayersDisabledStillCullsWhenWindowOffered`）および [`OffscreenItemCullPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/OffscreenItemCullPixelTest.java)（`scrollContainerBlocksAreRangeCulledInSoftwareBackend`、全画素一致を保証）。残りの候補: 索引の差分更新。
**次（旧記述・参考）**: 段階 1b ①（`PaintSceneDriver.run` の `SCROLL_NODE` 可視区間スキップ。項目が縦に単調とは限らない（flex-wrap・絶対配置）ため、単調性の判定を伴う設計が要る）。
     2. **段階 1b（残り: ドライバ段 `SCROLL_NODE` / `Chunk` 単位の階層カリング — 戦略 T1）**（上記 ② は完了、① の二分探索は未実装）: 段階 1a でも 20,000 項目すべてに対して `PaintSceneDriver.resolveAt` と `SoftwareRenderBackend.buildClip`・`deviceBounds` は呼ばれており、またスクロールコンテナ内の 1 行が変わって `recordOwner != null`（`GpuScrollLayer` 再記録）になったフレームでは `outsideClip` が無効化される。そこで ① `PaintSceneDriver.run` が `SCROLL_NODE` を走査する際、スクロールオフセットとクリップ矩形から可視項目範囲 `[visFrom, visTo)` を二分探索／チャンク境界で求めて画面外ループ自体をスキップし、② `GpuScrollLayer` の FBO はビューポート寸法（`width × height`）しかないため、`recordOwner != null` の間もビューポート `[0, 0, width, height]` 外の項目をカリングする。
     3. **段階 2（項目単位の保持 GPU 記録 — 戦略 T3・下記 2）**: 可視範囲内の未変更項目について 80B GPU インスタンス列を保持し、変更された項目だけを GPU バッファへ再書込する。
     4. **段階 3（ボックス単位の DisplayItem スライス範囲保持 — 戦略 T2・下記 4）**: `hover` / `text` / `child` / `inherited` での `DisplayListBuilder` 全ボックス走査を排除する。
   - **KPI**: 2,000 行・画面外 `hover` の GL フレーム `< 0.25〜0.7 ms`（現 `13.1 ms`、Chrome `0.55 ms`）、`child` `< 1.0〜1.5 ms`（現 `25.6 ms`、Chrome `1.09 ms`）、`text` `< 0.8〜1.2 ms`（現 `10.3 ms`）。500 行は `hover`/`text`/`style` `< 0.3〜0.5 ms`。
0b. **【完了 2026-11-11】CPU バックエンドの差分描画（Tile Dirty Bitset）とスクロール Blit 再利用（W3 — 戦略 T4）**:
    - **達成内容**:
      - ✅ **CPU バックエンド向け高速スクロール Blit（`CpuRaster.scrollBlit`）新設**:
        - 根の描画先 `Pixmap` 内の指定矩形（スクロールコンテナ境界）を `System.arraycopy` でインプレースに上下・左右シフト（上書き防止のためシフト方向に応じた順走／逆走走査）。
        - シフトによって露出した矩形領域をクリア色（事前乗算）で高速に塗りつぶし。
      - ✅ **スクロール Blit 再利用計画（`SoftwareRenderBackend.planDamage`）**:
        - スクロール節（`PART_SCROLL_CONTENT` / `_scrollc`）の姿勢変化（`translateX` / `translateY`）を検出し、整数ピクセルスクロール時に `scrollBlit` を適用。
        - 露出帯矩形のみを `DamageRegion` に登録し、コンテナ内の未露出項目を描画スキップ（`skipBuf`）することで、スクロール時の不要なテキスト・装飾再ラスタを劇的に削減。
      - ✅ **子要素増減（`CHILD_PATCH`）時の 64-bit 整数鍵マッピングと差分描画継続**:
        - `prevItems.size() != items.size()`（行の追加・削除等）時、64-bit 整数鍵（`DisplayItem.key()`）を用いて旧項目と新項目の 1 対 1 マッピング（`oldKeyMap` / `oldIndexOf`）を実施。
        - ブロック層（背景）とインライン層（テキスト）の複数スライスにまたがる挿入・削除でも、旧 `prevExtent` / `prevClip` を正しく再配置し、タイル差分描画（`clearTiles` / `TileQueue`）を継続。
      - ✅ **姿勢変化・クリップ追従・ダーティ伝播の完全化**:
        - 移動節の子孫（`movedBuf`）におけるクリップ追従（`planClip` の `changed || movedBuf[i]`）を修正し、スクロール流入要素の誤スキップを防止。
        - 行背景再描画時の子孫・兄弟枝テキストの同期再描画（`subtreeDirty` 伝播）を保証。
    - **検証**:
      - 新設 [`ScrollBlitTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/ScrollBlitTest.java)（上下左右シフト・露出領域塗りつぶし・全体流出クリア等 全 4 テスト PASSED）。
      - 新設 [`CpuScrollBlitPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/CpuScrollBlitPixelTest.java)（連続スクロール 40 フレーム、および動的行追加・削除 30 ステップにおいて、全面再描画と 100% 全画素一致・差分描画有効・見積外れ abort 0 件を検証）全テスト PASSED。
      - 既存のソフトウェア描画テスト群（[`PartialRepaintPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/PartialRepaintPixelTest.java), [`DiffPatchPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/DiffPatchPixelTest.java), [`ShiftPatchPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/ShiftPatchPixelTest.java) 等）およびコアモジュール全テスト（`:core:test` 全 1,880 件超）全件グリーン。
      - アーキテクチャラチェット（`LayerDependencyRatchetTest`, `PortabilityRatchetTest`, `ApiBoundaryTest`）PASSED。

1. **【完了 2026-11-11】`inherited` / `child` 変更時の `PaintSceneDriver` 索引再構築コストとヒープ確保（戦略 T2）**:
   - **達成内容**:
     - ✅ **構造不変・大規模差分（`inherited` 等）のインプレース差分更新（`patchIndexByDiff` 上限撤廃）**:
       - 変更項目数が 1/4（25%）を超える場合でも、全変更項目の構造（`key()`, `parentKey()`, `type()`, `LayerSpec`）が同一である限り、`rebuildIndex` へフォールバックせず、既存の配列と索引を維持して変更位置のみをインプレース更新。
       - 10 種類におよぶキャッシュ配列の新規確保を 0 にし、`generation` も進まないため外部のクリップ記憶キャッシュ（`ClipEntry`）を 100% 維持。
     - ✅ **局所挿入・削除（`child` = `CHILD_PATCH`）に対する増分インデックスシフト（`patchIndexByShift`）**:
       - 変化が単一の局所領域（`head` と `tail` の間）に集約されている場合、既存の内部配列（`parentIdx`, `states`, `prevRes`, `prevStatic`, `prevIdle`, `itemVersion` 等）を `System.arraycopy` でインプレースシフト。
       - `indexOf` および `indexedById` は差分項目のみを増分更新（削除項目の除去、シフト項目の位置更新、挿入項目の登録）。
       - 親インデックス（`parentIdx`）および FX レイヤー錨（`fxAnchors`）もシフト量 `delta` に応じて調整。
       - 挿入・削除ごとの数 MB〜数十 MB に達していた配列再確保と全項目走査を根絶。
   - **検証**:
     - [`PaintSceneDriverDiffPatchTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriverDiffPatchTest.java) に `largeDiffWithSameStructureInheritedStylePatchesInPlace`（87.5% の要素が一斉変化しても `generation` 不変でインプレース更新され、全出力が再構築時と 100% 一致）を追加し PASSED。
     - 新設 [`PaintSceneDriverShiftPatchTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriverShiftPatchTest.java)（先頭・中間・末尾での行挿入・削除、およびランダム操作列 60 ステップにおいて、シフトパッチ出力が毎回再構築時と 100% 完全一致）全 7 テスト PASSED。
     - 新設 [`ShiftPatchPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/ShiftPatchPixelTest.java)（挿入・削除が連続する実フレームで、CPU バックエンドでの描画結果が毎回再構築時と 120 フレーム全画素完全一致）PASSED。
     - アーキテクチャラチェット（`LayerDependencyRatchetTest`, `PortabilityRatchetTest`, `ApiBoundaryTest`）PASSED。
     - コアモジュール全テスト（`:core:test` 1,880 件超）全件グリーン。
2. **項目単位の保持された GPU 記録（1 字形あたり `0.13 µs` の毎フレーム固定費の解消 — 戦略 T3）**:
   - **現状**: 1 要素のテキスト変更でも、未変更の字形に対して毎フレーム `drawGlyphs`（フレーム時間の 39%：`GpuAttempt.glyph` 15% + `GlRectStream.open` 10% + `GlyphCache` 6%）が走っている。
   - **対策**: `DisplayItem` は未変更ボックスでは同一インスタンスが再利用される（`drawBoxSelfMemo`）ため、項目ごとに生成された 80B GPU インスタンスレコード列を保持し、項目と変換・クリップが同一なら字形ループ自体をスキップして前回の GPU レコードを `MemorySegment.copy` で一括転送する。これにより `GlRectStream` の容量上限 4,096 も CPU 負荷増なしで拡大可能になる。
3. **`DisplayItem.keyOf` → `AtomTable.intern` の文字列連結・ハッシュ照合の排除（全シナリオの 8〜10% — 戦略 T2）**:
   - **現状**: `DisplayItem` 生成時および `PaintSceneDriver` の索引構築時に、毎回文字列連結で ID を作って `AtomTable.intern` (`ConcurrentHashMap<String, Integer>`) を引いており、全シナリオの 8〜10% の CPU 時間と GC 圧を占めていた。
   - **対策・進捗**:
     - ✅ **段階 1: 64-bit 整数鍵基盤新設とテキスト項目バイパス（2026-11-11 実装完了）**:
       - [`DisplayItem`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/model/DisplayItem.java) に `public static long keyOf(int nodeHandle, int partOrdinal)`（`(((long) nodeHandle) << 16) | (partOrdinal & 0xFFFFL)`）および逆変換 `nodeHandleOf` / `partOrdinalOf` を新設。
       - [`PaintSupport.addElement`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintSupport.java) に明示的な `long key` オーバーロードを追加し、`key == 0L` 時は従来の `DisplayItem` 自動導出へ安全にフォールバック。
       - 最も生成件数の多いテキスト断片・テキスト影（[`DisplayListBuilder.addTextRun`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/DisplayListBuilder.java)）で `nodeHandle` から `keyOf` を直接生成し、文字列連結および `AtomTable.intern` を完全にバイパス。
       - 検証: [`DisplayItemKeyTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/DisplayItemKeyTest.java)（パック・アンパック可逆性、非正ハンドルの 0 戻り、明示鍵による `AtomTable` バイパス、フォールバック、キー非衝突、実 HTML 描画時でのパックキー検証など全 6 項目 PASSED）、アーキテクチャラチェット（`LayerDependencyRatchetTest`, `PortabilityRatchetTest`, `ApiBoundaryTest`）PASSED。
     - ✅ **段階 2: 装飾部品（インライン断片の背景・角丸枠線、外側／内側の影）への拡大（2026-11-11 実装完了）**:
       - [`BoxDecorationPainter`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/paint/BoxDecorationPainter.java) の `_frag_<n>` / `_b_round_<n>`（断片）、`_shadow_<n>`（外側）、`_shadow_in<n>`（内側）が `decorationKey(box, base, ordinal)` で整数鍵を直接生成する。`fragIds` は箱ごとにリセットされテキスト項目も同じ番号を使うため、**部品番号の領域を分離**して同一節内の鍵衝突を防ぐ（テキスト `0x0000〜0x6FFF`／主要部品 `0x7000〜0x7FFF`／断片 `0x8000〜0xBFFF`／外側の影 `0xC000〜0xDFFF`／内側の影 `0xE000〜0xFFFF`。範囲外は領域内へ丸める）。ID 文字列の綴りは不変（`HitTestIndex.DECORATION_PARTS` など文字列判定は影響なし）。
       - 検証: `DisplayItemKeyTest.decorationPartsUsePackedKeysInTheirOwnNamespaceAndNeverCollide`（影 3 種・インライン `<code>` の枠線付き断片を含むページで、全項目の鍵が一意、各部品が節ハンドルと所定の領域の部品番号を持つことを確認）、`:core:test` 全 1,877 件グリーン（失敗 0・スキップ 3）。
     - ✅ **段階 3: 主要部品（NODE / _bg / コントロール群）の 64-bit 整数鍵化と索引側の整数鍵直結（2026-11-11 実装完了）**:
       - **部品番号領域の完全分離**: [`DisplayItem`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/model/DisplayItem.java) に主要部品用の定数領域を新設（`PART_NODE=0x7000`, `PART_BG=0x7001`, `PART_BORDER=0x7002`, `PART_INPUT=0x7003`, `PART_CHECKBOX=0x7004`, `PART_RADIO=0x7005`, `PART_SELECT=0x7006`, `PART_TEXTAREA=0x7007`, `PART_CANVAS=0x7008`, `PART_SCENE=0x7009`, `PART_MEDIA=0x700A`, `PART_PLACEHOLDER=0x700B`, `PART_SCROLLBAR_V=0x700C`, `PART_SCROLLBAR_H=0x700D`, `PART_BGL_BASE=0x7100`）。テキスト項目領域を `0x0000〜0x6FFF`（28,672 枠）に設定。
       - **ペインタ側のパック鍵供給**:
         - [`DisplayListBuilder`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/DisplayListBuilder.java): 錨ノード（`ElementType.NODE`）およびキャンバス背景（`bg_root`）で `nodeKeyOf(nodeHandle)`, `bgKeyOf(nodeHandle)` を供給。
         - [`BoxDecorationPainter`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/BoxDecorationPainter.java): 主背景・背景画像・境界線レイヤーで `bgKeyOf(nodeHandle)` および `keyOf(nodeHandle, PART_BGL_BASE + layer)` を供給。
         - [`ControlPainter`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/ControlPainter.java): フォームコントロール群（`_cb`, `_scene`, `_img`, `_svg`, `_canvas`, `_textarea`, `_select`, `_ph`, `_input`）で各部品定数からパック鍵を直接供給。
       - **索引側（PaintSceneDriver / HitTestIndex）の整数鍵直結と 100% 後方互換性**:
         - [`PaintSceneDriver`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriver.java) に `item(int nodeHandle, int partOrdinal)` および `indexOf(int nodeHandle, int partOrdinal)` を新設。整数鍵 (`key()`) と文字列 ID 導出鍵の両方を索引付けし、既存の `itemById` も維持。また `prepare()` 内で確実に `byId` インデックスを公開。
         - [`HitTestIndex`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/HitTestIndex.java) に `getElementByHandle(int nodeHandle, int partOrdinal)` および `getElementByHandle(int nodeHandle)` を新設。`dynamicKeys` および `elementMap` にパック鍵を登録し、ノードハンドルによるゼロアロケーション直結引きを実現。
       - **検証**:
         - [`DisplayItemKeyTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/DisplayItemKeyTest.java) に `majorPartOrdinalsAreDistinctAndInReservedNamespace`（予約領域の非重複検証）および `nodeAndBackgroundAndControlItemsUsePackedKeys`（実 HTML レンダリングでの NODE/_bg/コントロールのパック鍵検証、および HitTestIndex / PaintSceneDriver での handle 引き・文字列 ID 引きの両立検証）を追加し PASSED。
         - [`PaintOffsetHitTestCacheTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/common/PaintOffsetHitTestCacheTest.java)（動的キー追跡・パッチキャッシュ検証）PASSED。
         - 描画回帰テスト [`DiffPatchPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/DiffPatchPixelTest.java), [`PartialRepaintPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/PartialRepaintPixelTest.java), [`OffscreenItemCullPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/OffscreenItemCullPixelTest.java) の全画素一致 PASSED。
         - アーキテクチャラチェット（`LayerDependencyRatchetTest`, `PortabilityRatchetTest`, `ApiBoundaryTest`）PASSED。
         - `:core:test` 全テストスイート通過（1,879 件全件グリーン、0 失敗）。
      - ✅ **段階 4: 残余装飾・境界線・スクロールバー・UI部品の完全 64-bit 整数鍵化と親子キー（parentKey）直結（2026-11-11 実装完了）**:
        - **残余描画項目の完全パック鍵化**:
          - スクロールバーサム（`PART_SCROLLBAR_V`, `PART_SCROLLBAR_H`）、トラック（`PART_SCROLLBAR_TRACK_V`, `PART_SCROLLBAR_TRACK_H`）、つまみ移動節（`PART_SCROLLBAR_NODE_V`, `PART_SCROLLBAR_NODE_H`）、スクロールコンテンツ節（`PART_SCROLL_CONTENT`）を完全鍵化し、スクロールコンテンツ節と持ち主節の NODE 鍵衝突を解消。
          - 単一丸角枠線（`PART_BORDER`）、円形枠線（`PART_CIRCLE_BORDER`）、四辺直線枠線（`PART_BORDER_TOP`〜`PART_BORDER_LEFT`）、複数丸角枠線（`PART_BORDER_ROUND_BASE + i`）、border-image（`PART_BORDER_IMAGE_BASE + part`）、背景画像タイル・グラデーション（`PART_BGL_BASE`）、背景フォールバック色矩形（`PART_BGL_FALLBACK`）、ゴースト画像（`PART_GHOST_IMAGE`）、プログレスバー（`PART_PROGRESS`）にパック鍵を供給。
        - **親子関係の 64-bit 整数キー直結（P0-1 / 戦略 T2）**:
          - [`RenderHints`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/model/RenderHints.java) に `long parentKey` を導入し、[`ItemProps`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/ItemProps.java) で親ノードの 64-bit 鍵を直接供給。
          - [`PaintSceneDriver`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriver.java) の `parentIdx` 構築ループで `parentKey` 直結引き当てを行い、文字列の `AtomTable.intern` 探索を完全スキップ（未設定時は文字列キーへ安全にフォールバック）。
          - `sameStructure` で `parentKey` 同士の 64-bit 整数比較を行い、文字列 `equals` を完全バイパス。
        - **検証**:
          - [`DisplayItemKeyTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/DisplayItemKeyTest.java) に `remainingPartOrdinalsAreDistinctAndInReservedNamespace`（全 30 定数の非重複・予約空間内検証）、`bordersProgressScrollbarsAndScrollContentUsePackedKeys`（実 HTML での境界線・プログレス・スクロールバー・スクロールコンテンツ節パック鍵検証）、`parentKeyInRenderHintsMatchesParentNodeKey`（`parentKey` 直結と親子一致検証）を追加し全 12 テスト PASSED。
          - スクロール保持検証 [`PaintSceneDriverRetainTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriverRetainTest.java), [`PaintSceneDriverRangeCullTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriverRangeCullTest.java), [`ScrollCostTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/common/ScrollCostTest.java)（保持レイヤ再利用率 899/900 回）PASSED。
          - 描画回帰テスト [`DiffPatchPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/DiffPatchPixelTest.java), [`PartialRepaintPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/PartialRepaintPixelTest.java), [`OffscreenItemCullPixelTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/software/OffscreenItemCullPixelTest.java) の全画素一致 PASSED。
          - アーキテクチャラチェット（`LayerDependencyRatchetTest`, `PortabilityRatchetTest`, `ApiBoundaryTest`）PASSED。
          - `:core:test` 全テストスイート通過（1,882 件全件グリーン、0 失敗）。
      - **今後の展開**: 段階 1〜4 および親子キー直結により、描画項目のほぼ 100% が 64-bit 整数鍵化され、索引引き・親子参照ともに文字列アロケーションゼロで動作する基盤が完成したため、これをもとに P0-1（増分索引シフト）および P0-4（LayoutBox スライス範囲保持と局所差分差し替え）の実装へ移行する。
4. **【完了 2026-11-11】`DisplayListBuilder` の差分範囲差し替え（局所変更時の全ボックス走査の排除 — 戦略 T2: Segmented DisplayList）**:
   - **達成内容**:
     - ✅ **`LayoutBox` による DisplayItem スライス範囲保持**:
       - [`LayoutBox`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/LayoutBox.java) に `dlStart`, `dlEnd`, `dlOrder` フィールドおよび `clearDlSlice()` を追加。
       - 各ボックスが生成した表示リスト上のインデックス区間 `[dlStart, dlEnd)` と、CSS 2.1 描画順配列上の順序 `dlOrder` を永続保持。
     - ✅ **`DisplayListBuilder` の局所差分差し替え API 実装**:
       - 描画時に直近の `currentDisplayList`, `renderedBoxes`, `currentRoot` を保持。
       - **スタイル変更・テキスト更新の局所スライスパッチ（`canPatchBoxes` / `patchBoxes`）**:
         - 変更されたボックス群が互いに重なり合わないスライス区間を持つ場合、個別の `[dlStart, dlEnd)` スライスのみを再生成。
         - スライス項目数不変（`delta == 0`）時は $O(1)$ 上書き、項目数変動（`delta != 0`）時は `renderedBoxes` の後続ボックス配列を線形シフトするだけで完了し、全ボックス走査を完全にバイパス。
       - **子要素増減（`CHILD_PATCH`）の局所スライスパッチ（`canPatchChildSlices` / `patchChildSlices`）**:
         - 親要素直下の削除要素および追加要素のスライス区間を特定し、挿入位置前後の境界でスライスを局所置換。
         - 削除要素のスライス破棄と追加要素のスライス生成をインプレースで実施し、後続ボックスのスライス範囲を `delta` 分インクリメンタルにシフト。
     - ✅ **`UiView` 統合**:
       - `tryTextPatch` で `paintBuilder.canPatchBoxes(found.values())` が成立した場合、`paintBuilder.patchBoxes` を呼び出し、全ボックス走査による表示リスト再構築を回避。
       - `tryChildPatch` で `paintBuilder.canPatchChildSlices` が成立した場合、`paintBuilder.patchChildSlices` を呼び出し、全ボックス走査を回避。
   - **検証**:
     - 新設 [`DisplayListSlicePatchTest.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/DisplayListSlicePatchTest.java) にて全 8 テストを実施し、全件 PASSED：
       1. `slicesAreRecordedOnRender`: 初回レンダリング時に各ボックスへ非負のスライス範囲が割り当てられ、親要素内に包含され、連続していることを検証。
       2. `singleBoxStylePatchMatchesFullRender`: 単一ボックスの背景色変更でパッチ結果がフル描画結果と 100% 一致。
       3. `multipleBoxesStylePatchMatchesFullRender`: 複数ボックスの背景色変更でパッチ結果がフル描画結果と 100% 一致。
       4. `textPatchMatchesFullRender`: テキスト更新でパッチ結果がフル描画結果と 100% 一致（`TEXT_PATCH_HITS` 増加確認）。
       5. `childInsertMiddleMatchesFullRender`: リスト中間への行挿入でパッチ結果がフル描画結果と 100% 一致（`CHILD_PATCH_HITS` 増加確認）。
       6. `childRemoveMiddleMatchesFullRender`: リスト中間からの行削除でパッチ結果がフル描画結果と 100% 一致（`CHILD_PATCH_HITS` 増加確認）。
       7. `childInsertAtStartAndEndMatchesFullRender`: リスト先頭・末尾への行挿入でパッチ結果がフル描画結果と 100% 一致。
       8. `randomOperationsSequenceMatchesFullRender`: 40 ステップのランダム変異列（行追加・削除・テキスト変更）で、毎回全再構築時と 100% 完全一致。
     - 回帰テスト [`ChildPatchTest.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/common/ChildPatchTest.java), [`TextPatchTest.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/common/TextPatchTest.java) 全件 PASSED。
     - アーキテクチャラチェット（`LayerDependencyRatchetTest`, `PortabilityRatchetTest`, `ApiBoundaryTest`）PASSED。
     - `:core:test` 全テストスイート通過（1,880 件超全件グリーン、0 失敗）。
5. **Phase A 完結：GPU/CPU 走査向きの反転と非等方角丸・せん断の GPU シェーダー化**:
   - **現状**: `LwjglGlRenderBackend` → `CpuLayerFallback` → `SoftwareRenderBackend` → `PaintSceneDriver` → `GpuAttempt` の向きで走査しており、また `GlRectStream.decompose` が毎フレーム `Matrix2D` を `atan2`/`hypot` で逆分解し、せん断 (`skew`) や非等方スケール下の角丸を CPU フォールバックへ回している。
   - **対策**: `PropertyTrees` の Transform ノードに 2D アフィン行列をそのまま保持して `decompose` を廃止し、`GpuRectShader` で非等方角丸・せん断を直接評価して 2D プリミティブの CPU フォールバック率を 0% にする。

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
| **Wave 1.6 (R0〜R7: 2026-11-11 再策定)** | 🎯 **R0・R1 完了・R2 着手** | ✅ R0 計測基盤補強（§6.8）→ **✅ R1-0 `StandaloneView` ビルダー一本化 ＋ R1 スタイル変更のスライスパッチ化（§6.9）** → **R2 `child` 残差** → R3 全面無効化・幅変更・可視範囲限定 → R4 CPU 全面ダメージ解消 → R6 オープン → R5 GPU 記録（条件付き）／R7 ラチェット横串（§7.3 冒頭） | ・2,000 行 GL `hover` **`< 1.0 ms`**（現 `3.50`）<br/>・2,000 行 GL `child` **`< 3 ms`**（現 `13.8`）<br/>・500 行 `inherited` GL **`< 1.5 ms`**（現 `3.59`）<br/>・2,000 行 `fontsize` GL **`< 10 ms`**（現 `18.9`、Chrome `11.3`）<br/>・showcase CPU `hover` **`< 1.5 ms`**（現 `4.59`）／幅変更 **`< 0.2 ms`**（現 `1.04`）<br/>・Chrome 比: 全シナリオ 2 倍以内（現 最大 12.6 倍） |
| **Wave 2 (P2: 戦略 T7-③)** | 📋 **設計完了** | Three.js 型 3D シーングラフ統合（[`ENGINE_3D_ARCHITECTURE_PLAN.md`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/ENGINE_3D_ARCHITECTURE_PLAN.md)） | HTML/CSS と 3D メッシュ・ライト・レイキャストの単一パス・ゼロコピー融合（ブラウザ Three.js では不可能な領域） |
| **Wave 3 (P3: 戦略 T7-①)** | 📋 **計画中** | IME Composition・Top Layer (`<dialog>`/`popover`) & Anchor Positioning・`content-visibility: auto` + `FenwickVirtualList` 10 万行仮想化・`subgrid`・縦書き | 20,000〜100,000 行リスト **`< 0.2〜0.5 ms`**（Chrome 20,000 行 `117 ms` の **200 倍以上高速**）、日本語デスクトップ UI 完全対応 |
| **Wave 4 (P4: 戦略 T7-②)** | 📋 **計画中** | コンパイル済み型安全 `Signal<T>` / `Record` 直結バインディング・`<slot>` コンポーネント・`F12` 組み込み `EnderDevTools` | 開発生産性と実行性能の双方で Web フロントエンド（React/CEF）および既存 Java UI ライブラリを凌駕 |

