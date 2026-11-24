# EnderFrameLib 根本再設計プラン（Engine v3: 破壊的アーキテクチャ刷新）

> **更新日**: 2026-11-24
> **対象**: `:core`（515 ファイル・約 11.1 万行。`com.nishiyu.enderframelib.core.*` / `common.*` / `api.*`）および組み込みサンプル MOD（101 ファイル・約 2.6 万行。`src/main/java/...`）の境界・描画・ホスト層を含む全体構造。
> **旧計画の全面撤回宣言**: 従来の `C 系`・`K 系`・`K5b`・`L5 (W0〜W3)`・`K4h`・`B14` および `core/DESIGN_INCREMENTAL_KERNEL.md` に基づく「既存キャッシュ温存＋増分カーネル延命」方針を**本日をもって正式に撤回・凍結**する。過去の経緯・計測ログ・旧知見（§6-1〜205）は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) と git 履歴にのみ残す。本計画は**破壊的変更を全面的に許可**し、データ表現・中間表現（IR）・描画契約・ホスト統合の土台そのものを作り直す。

---

## 1. 根本診断と旧計画の撤回一覧

実コード全体の構造調査により、パフォーマンスと保守性が改善せず「1 つ直すと別の欠陥と費用が出る」ループに陥っていた理由は、アルゴリズムの細部ではなく**土台となる 5 つの構造的崩壊**にあると特定した。

### 1.1 実コードで確認された 5 つの根本欠陥

1. **名ばかりの DoD と、超肥大・可変 OOP ツリー（`LayoutBox`）への不変メモ化の無理な接ぎ木**
   - オフヒープ SoA（`core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/DocumentArena.java`）を参照しているのは `:core` 全 515 ファイル中 **6 ファイル**のみで、DOM 木（`HtmlNode` 1,399 行）とオフヒープ列の二重同期にとどまっている。
   - `ComputedStyle`（`core/src/main/java/com/nishiyu/enderframelib/core/css/ComputedStyle.java`、2,095 行）は `PackedCssValue` を辺の一部キャッシュ（`packedEdges()`）等にしか使わず、本体は **`CssValue[207]` の文字列ラッパー配列**＋弱参照 `ConcurrentHashMap`（`CssValue.TABLE`）＋ `asMap()` 経由の `Map<String, String>` 受け渡しで動いている。
   - 1 つの要素がヒープ上で以下の 10 個超の可変オブジェクトに分散している：
     - `HtmlNode`（1,399 行・17 フィールド宣言：`AttributeMap`, `ChildList`, `Rare`, `classAtoms`）
     - `InteractionState.StateFlags`（`core/src/main/java/com/nishiyu/enderframelib/core/dom/InteractionState.java`、約 25 欄 + `StateStyleMemo`）
     - `ComputedStyle`（2,095 行・24 欄：`CssValue[207]`, `StyleMap`, `StyleSlice`×4, `ObjMemo`/`ConcurrentHashMap`, `CachedFace`/`CachedTextStyle`）
     - `LayoutBox`（`core/src/main/java/com/nishiyu/enderframelib/core/layout/LayoutBox.java`、**1,785 行・81 インスタンスフィールド宣言**：`children` + `elementChildren` 二重木、幾何、`overrideWidth/Height`, `floatSpace`, `inlineFragments`, `spareFragments`, `TextCache`, `PaintStamps` 用 8 欄）
     - `MeasureMemo`（`core/src/main/java/com/nishiyu/enderframelib/core/layout/MeasureMemo.java`、799 行・**41 欄・17 フラグ** + 出力丸コピー `outW..outBL` + `TentativeSlots`）
     - `PlacementMemo`（`core/src/main/java/com/nishiyu/enderframelib/core/layout/PlacementMemo.java`、342 行・**11 欄**）
     - `FragmentPaint.Memo`（`core/src/main/java/com/nishiyu/enderframelib/core/paint/FragmentPaint.java`、**52 欄**を `IdentityHashMap` 管理）
   - 同一の巨大な可変 `LayoutBox` 上に「入力・一時通信欄（`overrideWidth`, `floatSpace`）・最終出力・4 種の記憶・ロックフリーダーティリスト」を同居させてその場破壊（in-place mutation）しているため、他段の記憶が壊れるバグが構造的に止まらない。その防衛として `PaintStamps.shape()`（約 40 欄ハッシュ）や `resetPlacements` が膨張し、保持ヒープは `varied-list-2000` **40.2 MB（Chrome 1.55x）**・`large-list-2000` **10.0 MB（Chrome 2.0x）** に悪化している。

2. **7〜8 系統の独立キャッシュ／無効化言語とプロセスグローバル状態の汚染**
   - 増分・キャッシュ・検証専用コードだけで **43 ファイル・6,527 行** が並立している：
     - DOM（`ChangeLedger` + `StateFlags` + `InvalidationLevel`）→ Style（`StateStyleMemo` + `ShareCache` + `DIFF_MEMO` + `SLICE_POOLS` + `CssValue.TABLE`）→ Box（`ReusableTable` + `PartialBuild` + `ChildWindow`）→ Measure（`MeasureMemo` + `LayoutReads` 619 行・27 種 `ReadKind`）→ Place（`PlacementMemo` + `PlacementDeps` + `VirtualFlowState`）→ Paint（`PaintStamps` + `FragmentPaint.Memo` + `InkState` + `windowShift`）→ Scene（`PaintSceneDriver` `RetainRanges`/`RangeBounds`/`reconcileIndex`）。
   - 各段が前段を信用できず子全員を記憶照会（約 1 µs/子）するため、8,000 行リスト 1 件挿入は超線形（**7.89 ms**）に悪化する。
   - `:core` 全体に **38 個のグローバル `Shortcut` スイッチ**（`Shortcut.java`）・**50 個の `static volatile`**・**27 個の `ThreadLocal`**・**17 個のグローバル `Atomic`/`LongAdder`**（`LayoutBox.{SHAPE_EPOCH, STRUCT_EPOCH, CONTAINER_EPOCH, PAINT_EPOCH, renderBoundary}`, `MeasureMemo.MEASUREMENT_EPOCH` 等）が散在し、複数ビュー間でキャッシュを巻き添え無効化する。`UiView.rebuildWindowAtBoxFrame` では `scrollState` を一時的に過去値へ書き戻す時間巻き戻しハックまで発生している。

3. **6〜7 段の IR 変換と「フラット `DisplayItem` 配列」の構造ボトルネック**
   - 1 フレーム描画までに `HtmlNode` → `LayoutBox.elementChildren` → `LayoutBox.children` → `Fragment`（8 層・永続 B 木チャンク）→ **フラット `DisplayItem[]`**（`core/src/main/java/com/nishiyu/enderframelib/core/paint/model/DisplayItem.java` + `PaintCell` + `ImmutableItemList`：`String id`, `Map<String, String> events`, `RenderHints.parentId`, `int` 丸め座標）→ `PaintSceneDriver`（926 行）へと 6〜7 段の変換が走る。
   - `Fragment` が持っていた階層構造を `DisplayListBuilder`（922 行）の出口でフラット配列へ潰すため、下流の `PaintSceneDriver` が毎フレーム `Long2IntOpenHashMap` / `parentIdx[]` / `RetainRanges` / `RangeBounds` で **「潰された親子関係・レイヤー・スクロール範囲を逆算・再復元」** している。
   - これが `showcase` 幅変更の **66%（0.273 / 0.414 ms）** と `varied-list-2000 fontsize` の 4〜23 スレッド頭打ち（**10.76 ms > 144Hz 予算 6.94 ms**）の主因である。

4. **`:core` とサンプル MOD の二重レンダラ・L5 ピンポン実行・ホスト層の分裂**
   - `:core`（+ `:core-gl` 1,727 行・`:core-vulkan` 3,330 行）に約 1 万行の独立レンダラがあるにもかかわらず、MOD 側は `EnderFrameBuiltInConfig.useCoreBackend = false` が既定で、**9,369 行の別レンダラ**（`src/main/java/com/nishiyu/enderframelib/client/renderer/` 配下の `LayerCompositor` 1,399 行・`DisplayItemDrawer` 1,007 行・`WebSdfShader` 982 行・`TextElementRenderer` 547 行・`OutlineTextRenderer` 392 行等）が実稼働している。`CoreGpuSurface` を有効化しても `<scene-3d>` や一部画像でフォールバックし、`SurfaceRenderer` が毎回 `AbsoluteItems.of()` で全要素変換を行う。
   - `:core` の L5 内部も `GpuRenderBackend`（1,174 行）→ `CpuFallback.renderOnto` → `SoftwareRenderBackend`（1,259 行）→ `GpuSink` という**制御逆転のピンポン往復**のまま残っている。
   - L6 ホスト層は `UiView`（`core/src/main/java/com/nishiyu/enderframelib/common/UiView.java`、2,005 行・41 欄）に Minecraft サーバー同期由来の状態（`serverManaged`, `headlessState`, `lastSyncRequestTime`, `shouldRequestSync()`, `explicitSize`, `applyOverrides()`）が混在し、その上で `StandaloneView`（1,060 行）と MOD 側 `SessionContext` / `EnderScreenDelegate`（1,298 行）/ `TextareaEditor` が編集・ポップアップ・入力処理を重複実装している。また MOD 側 `client.*` に 5 組の直接パッケージ循環がある。

5. **形式ラチェットと既存キャッシュ温存による構造的ロックイン**
   - `LayerDependencyRatchetTest` は層間の上向き参照しか検査しないため、同一層内の巨大化（`core.css` 17,396 行、`core.layout` 16,490 行、`core.paint` 13,350 行、1,000 行超クラス `:core` 単独 19 個）を見逃す。
   - `IncrementalStateRatchetTest` は既に破綻した状態数（`MeasureMemo` 41 欄・17 フラグ、`FragmentPaint.Memo` 52 欄、`Shortcut` 27/38 個等）を上限固定しただけである。
   - `costGate` の内部カウンタ `memo*` 固定が「可変 `LayoutBox` + フラット `DisplayItem` + `CssValue[207]`」という土台の刷新を阻害し、K 系 → L 系 → W 系と増分層を積み増す袋小路を生んでいる。

### 1.2 撤回する旧計画・旧指示一覧

| 撤回対象 | 旧計画での役割 | 撤回理由 |
| :--- | :--- | :--- |
| **K5b（L3 / L4）** | `ChangeLedger` で `DIRTY_*`・`InvalidationLevel`・`domStructureVersion` を置換 | 可変 `LayoutBox` と多段キャッシュを残したまま入口だけを付け替えても下流の照会コスト（1 µs/子）が消えない |
| **L5（W0〜W3: 窓の伝播 + Fenwick）** | `ChildWindow` を `MeasureMemo` / `PlacementMemo` / `FragmentPaint` へ伝播 | 破綻した 3 つのメモ言語を延命した上にさらに特殊な窓境界判定を各段へ埋め込む複雑化ループになる |
| **K4h** | `FragmentPaint.Memo`・`memoFrameKey`・`InkState` の窓シフト延命 | フラット `DisplayItem` と `FragmentPaint.Memo`（52 欄）自体を廃止して階層的 `DisplayScene` へ置き換えるべきため |
| **K6 / K0b** | 3 種の `*Verifier` 統合、`FrameScheduler` のみ分離 | `*Verifier`・可変記憶群を丸ごと削除するため統合自体が不要 |
| **B14 / B5 / A15（旧前提）** | `FragmentPaint` 並列化・`LayoutBox` 疎欄の `LayoutArena` 化・`LayoutBox` 上の SPI | すべて可変 `LayoutBox` とフラット `DisplayItem` を温存する対症療法のため、新モデル（Phase M1〜M3）へ吸収・刷新 |
| **`DESIGN_INCREMENTAL_KERNEL.md` & 旧運用ルール 8〜10** | 読み取り台帳 `LayoutReads`・`IncrementalStateRatchetTest`・`Shortcut` 基準の運用 | 壊れた前提の固定を解き、破壊的単純化を最優先するため廃止 |

---

## 2. 新アーキテクチャ原則（Engine v3: 単純さ・値分離・単方向データフロー）

### 2.1 5 つの絶対原則

1. **入力と出力の完全分離（Immutable `LayoutFragment`）**
   - レイアウト計算は `(BoxNode, Style, ConstraintSpace) → Immutable LayoutFragment` の純粋な変換関数とする。
   - 出力が完全不変であり、一時状態（`overrideWidth`, `floatSpace` 等）は計算中の引数・局所スタックにしか存在しないため、**「入力が同じなら前回の `LayoutFragment` をそのまま返す」** だけで全段の整合性が保証される。可変 `LayoutBox`・`MeasureMemo`・`PlacementMemo`・`PaintStamps`・`resetPlacements` は存在理由ごと消滅させる。

2. **グループ共有・型付き不変スタイル（脱 `CssValue[207]` 文字列配列）**
   - CSS プロパティを継承性・利用箇所に応じた機能グループ別不変構造体（`InheritedStyle`, `BoxGeomStyle`, `VisualStyle`, `FlexGridStyle`, `TableStyle`, `RareStyle`）へ分離する。
   - 初期値グループは静的シングルトンを共有し、親から受け継ぐ `InheritedStyle` が未変更なら子も同一参照を共有する。長さは 64-bit プリミティブ値 `CssLength`、色・キーワードは `int` ARGB / `enum` / bitfield で直接保持し、文字列ラッパー配列と `Map<String, String>` 受け渡しを根絶する。

3. **階層的・数値 ID ベースの `DisplayScene`（フラット `DisplayItem` 完全廃止）**
   - `LayoutFragment` から、階層構造（変換・クリップ・レイヤー・スクロールコンテナ・境界矩形）とコンパクトなプリミティブ描画コマンド列を保持する不変ツリー **`DisplayScene`** を直接構築する。
   - ノード参照はすべて **`int nodeId`** とし、文字列 ID・`Map<String, String> events`・`AbsoluteItems` 再変換・`PaintSceneDriver` による階層再復元を完全に消滅させる。
   - L5a（`SceneEmitter`）→ L5b（`GpuRenderBackend` RHI / `SoftwareRenderBackend`）は完全単方向データフローとし、GPU 未対応要素は L5a 側で事前にテクスチャ化して流す。

4. **単一のメモ化言語・プロセスグローバル状態ゼロ**
   - 38 個の `Shortcut` スイッチ、プロセス共有エポック（`SHAPE_EPOCH` 等）、`ThreadLocal` 隠れ状態、7 系統のキャッシュを全廃する。
   - すべての実行状態・メモテーブルは 1 ビューインスタンス（`EngineView`）の内部に閉じ、キャッシュは **「不変入力の参照同一性（`==`）による 1 層メモ化」** のみを許可する。

5. **単一レンダラ・ホスト層の純化**
   - サンプル MOD 側の 9,369 行レガシーレンダラ（`DisplayItemDrawer` / `LayerCompositor` / `WebSdfShader` 等）を完全に撤去し、`:core` の RHI バックエンド（`MinecraftHostSurface`）に一本化する。
   - `UiView` から Minecraft 固有の同期残骸を剥がして純 Java の薄い `EngineView`（≤ 400 行）に純化し、入力・フォーカス・テキスト編集・選択・`<select>` ポップアップを共通の `InputController` へ統合する。

### 2.2 新 5 層単方向アーキテクチャ（Engine v3）

```text
┌────────────────────────────────────────────────────────────────────────────┐
│ L5: Host & View Orchestration (`common/EngineView`, `InputController`,     │
│     `api/*`, MOD `SessionContext` / `MinecraftHostSurface`)                │
│     所有物: DOM の 1 インスタンス・ScrollState・AnimatorState・入力状態     │
├────────────────────────────────────────────────────────────────────────────┤
│ L4: Unidirectional Scene & Raster (`core/paint/*`)                         │
│     L4a `SceneEmitter`: Immutable `DisplayScene` (`SceneLayer` + `DrawOp`) │
│     L4b `GpuRenderBackend` (RHI `GpuDevice`) / `SoftwareRenderBackend`     │
│     ※ 文字列 ID ゼロ・ピンポン往復ゼロ・完全単方向・`int nodeId` 索引       │
├────────────────────────────────────────────────────────────────────────────┤
│ L3: Pure Layout (`core/layout/*`)                                          │
│     `(BoxNode, Style, ConstraintSpace) -> Immutable LayoutFragment`        │
│     `FormattingEngine` SPI (Block / Flex / Grid / Table / Vertical)        │
│     1 層 `LayoutCache` (参照同一性キー) + チャンク累積和フロー              │
├────────────────────────────────────────────────────────────────────────────┤
│ L2: DOM, Selector & Typed Style (`core/dom/*`, `core/css/*`)               │
│     軽量 `DomNode` + `SelectorIndex` + グループ共有不変 `Style`            │
├────────────────────────────────────────────────────────────────────────────┤
│ L1: Text, Media, 2D/3D Primitives & RHI (`core/text/*`, `core/raster/*`,   │
│     `core/scene3d/*`, `core/kernel/gpu/rhi/*`, `core/kernel/memory/*`)     │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 破壊的再設計ロードマップ（唯一の優先順位表: Phase M0〜M6）

### 3.0 全体優先順位表

| 順位 | Phase | タイトル | 主な破壊・新設対象 | 完了時の定量的成果 |
| :---: | :--- | :--- | :--- | :--- |
| 1 | **M0** | **ロックイン解除と新ゲートの設置** | `Shortcut` 38 個・`*Verifier` 3 種・旧ラチェット 8 本を削除、出力等価性＋構造健全性ゲートへ置換 | 構成爆発 `2^38 → 1`、土台変更が可能に |
| 2 | **M1** | **`ComputedStyle` の完全刷新** | `CssValue[207]` 全廃 → 6 グループ不変構造体 `Style` + `CssLength` 導入 | `ComputedStyle` 2,095 → ≤ 500 行、`varied-list` ヒープ 40.2 → ≤ 18 MB |
| 3 | **M2** | **`LayoutBox` 解体と不変 `LayoutFragment`** | `LayoutBox`・`MeasureMemo`・`PlacementMemo`・`PaintStamps`・`LayoutReads` 等 約 4,500 行以上を削除、`BoxNode` + `LayoutFragment` + `LayoutCache` へ置換 | `varied-list` ≤ 12 MB・`large-list` ≤ 4.5 MB、8,000 行挿入 7.89 → ≤ 1.0 ms |
| 4 | **M3** | **フラット `DisplayItem` 廃止と `DisplayScene`** | `DisplayItem`・`FragmentPaint`・`PaintSceneDriver`・`HitTestIndex` 廃止、`DisplayScene` + L4 単方向化 | `varied-list fontsize` 10.76 → ≤ 5.0 ms（144Hz 達成）、3 者画素一致 100% |
| 5 | **M4** | **MOD レガシーレンダラ撤去とホスト層純化** | MOD `client/renderer` 9,369 行撤去、`UiView` / `StandaloneView` を `EngineView` + `InputController` へ統合 | MOD 描画 ≤ 1,500 行、MOD 循環 5 → 0、L6 ≤ 1,200 行 |
| 6 | **M5** | **DOM 整理・コールド起動短縮・並列/窓統合** | `DocumentArena` 二重同期解消、`SystemFonts` キャッシュ、非同期/並列・AWT 標準窓（旧 G7） | コールド起動 546 → ≤ 200 ms、1,000 行超クラス **0 個** |
| 7 | **M6** | **品質ゲート最終固定と配布（v2.0.0 GA）** | Linux CI 完全通過、API 署名確定、Maven Central / GitHub Packages 公開 | 全クラス ≤ 600 行、グローバル mutable static = 0 |

---

### 3.1 Phase M0: ロックイン解除と新ゲートの設置（破壊的手術の足場）

M1〜M4 でデータ構造を壊して作り直す前に、「旧構造（可変 `LayoutBox`・`Shortcut` 38 個・多段キャッシュの内部カウンタ）を固定して変更を阻んでいる番人」をすべて撤去し、「外から見た正しさ・性能・コード健全性」だけを見張る新ゲートに張り替える。

- **削除・撤去対象**
  1. `core/src/main/java/com/nishiyu/enderframelib/core/kernel/Shortcut.java`（38 スイッチ）および `EngineFlags.java` の近道フラグ群（呼び出し側はすべて単一の正準動作に畳む）。
  2. 個別検証クラス 3 本：`MeasureVerifier.java`・`OwnItemsVerifier.java`・`PoseVerifier.java`。
  3. 旧内部構造を固定しているラチェットテスト 8 本：
     - `IncrementalStateRatchetTest`（`MeasureMemo` 41 欄・`FragmentPaint.Memo` 52 欄の上限固定）
     - `ShortcutSwitchRatchetTest`
     - `LayoutReadLedgerRatchetTest`
     - `PatchSiteRatchetTest`
     - `PaintCellRatchetTest`
     - `SliceWriteSiteRatchetTest`
     - `ItemDiffRatchetTest`
     - `LayoutBoxReadOnlyFromPaintRatchetTest`
  4. `costGate` の旧内部カウンタ `memo*`（`memoHits`/`memoCeil`）による合否判定と `shortcutMatrix` タスク。
- **維持・新設する番人（出力等価性＋構造健全性のみ）**
  - **維持**: `WptReftestRatchetTest`（合格 160/260 以上）・`GpuBaselineTest`・`VkBaselineTest`・`RealDocumentFramesTest`・`FrameOracle`（最初から作った独立ビューとの出力一致）・`ApiBoundaryTest`。
  - **新設 `MemoryAndLatencyGate`**: 全再計算・差分シナリオのヒープ保持量（MB）と交互 A/B 実行時間（ms）のみを監視する。
  - **新設 `ArchitectureHealthTest`**: 1 クラス行数上限・グローバル mutable static 数・パッケージ循環数（SCC）・`:core` 境界純度（Minecraft 非依存）を直接検査する。
- **完了ゲート**:
  - `Shortcut` スイッチ数 `38 → 0`。
  - 全出力等価性テスト（WPT 160/260・3 者画素一致・`RealDocumentFramesTest`・`FrameOracle`）が緑。

---

### 3.2 Phase M1: `ComputedStyle` の完全刷新（型付きグループ不変構造体）

`CssValue[207]` 文字列ラッパー配列・弱参照インターン `ConcurrentHashMap`・`Map<String, String>` 受け渡しを根絶し、ヒープ消費とスタイル解決コストを桁違いに減らす。

- **新データモデル（`core/css/`）**
  ```java
  public final class Style { // 旧 ComputedStyle を置換（≤ 450 行）
      public final InheritedStyle inherited;   // font, color, lineHeight, textAlign, direction, writingMode...
      public final BoxGeomStyle box;           // display, position, width/height, margin/padding/border (64-bit CssLength)
      public final VisualStyle visual;         // background, opacity, transform, clipPath, filter, boxShadow, radii
      public final FlexGridStyle flexGrid;     // flex-*, grid-*, align-*, justify-*, gap（非使用要素は静的 DEFAULT 共有）
      public final TableStyle table;           // border-collapse, table-layout 等（非表要素は静的 DEFAULT 共有）
      public final RareStyle rare;             // anchor, contain, counter, animation, mask 等（非使用要素は静的 DEFAULT 共有）
      public final CustomProperties custom;    // --* 宣言があるサブツリーのみ非 null
  }
  ```
  - **長さと数値の即時解決型化**: 64-bit プリミティブ `long`（`CssLength`: 種別＋単位＋float 値、式 `calc()` のみ小さな不変式表添字）を `BoxGeomStyle` 等の `final` フィールドに直接保持し、`CssValue` ヒープオブジェクトを作らない。
  - **O(1) 継承と共有**: カスケードで継承プロパティの上書きが無ければ、子は親の `InheritedStyle` インスタンスをそのまま参照共有する（`child.inherited == parent.inherited`）。
  - **スタイルのプリミティブ化による `Layout` / `Paint` との契約単純化**: レイアウトと描画は `Style` から文字列を読まず（`get("position")` ゼロ）、型付きフィールド／列挙型のみを参照する。
- **削除対象**
  - `core/src/main/java/com/nishiyu/enderframelib/core/css/CssValue.java`（弱参照 `ConcurrentHashMap` 表）
  - `ComputedStyle` 内の `StyleSlice` / `SLICE_POOLS` / `DIFF_MEMO` / `StyleMap` / `StyleTable` / `ObjMemo` / `CachedFace` / `CachedTextStyle`
  - `core/src/main/java/com/nishiyu/enderframelib/core/css/StyleFacts.java`（209 行。`Style` の型付きグループ自体が facts を兼ねるため不要）
  - `core/src/main/java/com/nishiyu/enderframelib/core/css/PackedLengths.java`
  - `core/src/main/java/com/nishiyu/enderframelib/core/css/TypedValueCache.java`（68 行）
  - `core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/StateStyleMemo.java`（167 行）
  - `RenderTreeBuilder` 等の `Map<String, String> inheritedStyle` 引数受け渡しと `asMap()` 全箇所
- **移行手順**:
  1. `CssLength` 値表現と 6 グループ不変レコード/クラスを新設し、カスケード結果の生成先を `Style` へ切り替える。
  2. `core/layout/` と `core/paint/` のスタイル読み口を `Style` の型付きアクセサへ機械的に付け替える。
  3. `CssValue.java`・`StyleFacts.java`・旧 `ComputedStyle` のキャッシュ群を一括削除する。
- **完了ゲート**:
  - 旧 `ComputedStyle`（2,095 行）→ `Style`（≤ 500 行）。
  - `varied-list-2000` 保持ヒープ：`40.2 MB → ≤ 18.0 MB`。
  - レイアウト／描画側からの `Style` 文字列参照・`asMap()` 呼び出し `0 件`。
  - WPT 160/260 以上維持、3 者画素一致・`RealDocumentFramesTest` 緑。

---

### 3.3 Phase M2: `LayoutBox` 解体と不変 `LayoutFragment` アーキテクチャ（最重要手術）

本エンジンの最大の病巣である `LayoutBox`（1,785 行・81 欄）およびそれに群がる多層メモ・印・読み取り台帳群（約 4,500 行超）を根こそぎ解体し、**純粋関数的レイアウト＋不変出力＋1 層メモ化**へ作り替える。

- **新データモデル（`core/layout/`）**
  ```java
  // 1. 入力ボックス木（可変状態を一切持たない軽量記述ノード、≤ 10 欄）
  public final class BoxNode {
      public final int nodeId;           // DOM ノード番号（匿名箱・疑似要素も専用番号帯）
      public final Style style;          // Phase M1 の不変スタイル
      public final Display display;
      public final String text;          // TEXT ノードのみ非 null
      public final BoxNode[] children;   // 匿名箱・疑似要素展開済みの単一子配列（二重リスト廃止）
      public final PseudoStyles pseudos; // ::marker / ::placeholder / ::first-line（無ければ null）
  }

  // 2. 不変レイアウト出力（完成後は一切書き換えない）
  public final class LayoutFragment {
      public final BoxNode box;
      public final float width, height;
      public final Edges margin, padding, border; // 解決済みピクセル値
      public final float baseline;
      public final float scrollW, scrollH;
      public final PlacedChild[] children;        // 親相対座標 (x, y) と子の LayoutFragment（または ChunkedFlow）
      public final LineFragment[] lines;          // インライン整形結果（無ければ EMPTY）
      public final OutOfFlowData outOfFlow;       // absolute/fixed/float 情報（無ければ null）
  }

  // 3. 整形コンテキスト SPI（完全にステートレス）
  public interface FormattingEngine {
      LayoutFragment layout(BoxNode node, ConstraintSpace space, LayoutSession session);
  }
  ```
  - **逆流書き込みの完全根絶**: `overrideWidth` / `overrideHeight` / `floatSpace` / `collapsedBorders` / `columnShifts` 等を箱へ書き込む設計を全廃し、`ConstraintSpace`（確定寸法・float 除外領域・テーブル列幅・multicol 情報）としてトップダウン引数で明示的に渡す。
  - **単一 1 層メモ化 `LayoutCache`**: 1 ビュー（`EngineView`）に閉じた `(BoxNode, ConstraintSpace) → LayoutFragment` キャッシュのみを持つ。`BoxNode` と `Style` が不変のため、DOM 変更で影響を受けた祖先チェーンの `BoxNode` のみを新しく構築すれば、**未変更部分木は `boxNode == cached.boxNode && space.equals(cached.space)` の O(1) 判定だけでそのまま再利用**できる。
  - **長大フローの O(log N)〜O(√N) 位置解決（全面・増分同一ロジック）**: 子数が閾値（例：128 子）を超える通常ブロックフローでは、`LayoutFragment.children` を固定チャンク（例：32 子ごとの累積高さブロック、または `ChunkedFlow`）として構築する。先頭〜挿入位置前のチャンクは `BoxNode` の参照同一性で再利用し、後続チャンクは累積オフセット加算のみで位置を解決するため、**全面レイアウトと 1 行挿入・削除が完全に同一のコードパスで O(log N)〜O(√N)** になる。
- **削除対象（計 約 4,500 行超を完全削除）**
  - `LayoutBox.java`（1,785 行・81 欄）
  - `MeasureMemo.java`（799 行・41 欄・17 フラグ）
  - `PlacementMemo.java`（342 行・11 欄）
  - `PlacementDeps.java`（252 行）
  - `LayoutReads.java`（619 行）・`MeasureReads.java`（158 行）・`ReadKind.java`（113 行）
  - `PaintStamps.java`（249 行）
  - `PartialBuild.java`（167 行）・`ChildWindow.java`（47 行）
  - `ChangeLedger.java`（152 行）・`ChangeReads.java`（62 行）
  - `RenderPipeline.java` / `RenderTreeBuilder.java` 内の旧多段分岐（`relayoutRootOnly` / `relayoutInPlace` / `repositionLayout` / `LocalPatches` / `InPlaceRelayout`）
  - プロセスグローバルエポック全廃（`SHAPE_EPOCH`, `STRUCT_EPOCH`, `CONTAINER_EPOCH`, `PAINT_EPOCH`, `renderBoundary`, `MEASUREMENT_EPOCH`）
- **移行手順**:
  1. `BoxNode`・`LayoutFragment`・`ConstraintSpace`（拡張版）・`LayoutCache`・`BoxTreeCompiler`（DOM → 増分 `BoxNode` 木構築）を新設する。
  2. `BlockLayoutEngine`（1,372 行）・`FlexLayoutEngine`（898 行）・`GridLayoutEngine`（1,280 行）・`TableLayoutEngine`（835 行）・`VerticalWritingEngine` を順次 `FormattingEngine` SPI へ移植し、入力不変・出力不変で動作させる。
  3. 移行期間中の `DisplayListBuilder` との接続用アダプタを経由して `WptReftestRatchetTest` / `FrameOracle` を確認したうえで、旧 `LayoutBox`・メモ群・台帳群を一括削除する。
- **完了ゲート**:
  - 削除コード：`≥ 4,500 行`、グローバルエポック：`0`。
  - 保持ヒープ：`varied-list-2000 ≤ 12.0 MB`、`large-list-2000 ≤ 4.5 MB`（Chrome `5 MB` 以下）。
  - 8,000 行リスト 1 件挿入：`7.89 ms → ≤ 1.0 ms`（500 行は `≤ 0.15 ms`）。
  - `FrameOracle`（最初から作った結果との全比較）100% 一致、WPT 160/260 以上維持。

---

### 3.4 Phase M3: フラット `DisplayItem` 廃止と階層的 `DisplayScene` + L4 単方向化

`Fragment` の階層を `DisplayItem[]` 平坦配列に潰してから `PaintSceneDriver` で復元するという無駄な往復を完全に廃止し、レイアウト結果から GPU / CPU 描画までを 1 本の軽量ツリーで単方向に繋ぐ。

- **新データモデル（`core/paint/`）**
  ```java
  // 1. 不変階層描画シーン（旧 DisplayItem[] + Fragment + PaintSceneDriver を置換）
  public final class DisplayScene {
      public final SceneLayer root;
      public final Int2ObjectMap<SceneLayer> byNodeId; // 動的オフセット・ヒットテスト・スクロール用 O(1) 索引
  }

  public final class SceneLayer {
      public final int nodeId;                 // 文字列 ID ゼロ
      public final float x, y, w, h;           // 親相対座標
      public final float scrollClipW, scrollClipH;
      public final Transform2D transform;      // 固定変形（アニメ中は外部表で上書き）
      public final ClipShape clip;             // 角丸クリップ・clip-path（親鎖の継承は走査スタックで解決）
      public final LayerEffects effects;       // opacity / filter / backdropFilter / mixBlendMode / mask
      public final DrawOp[] ops;               // このノード自身の描画プリミティブ（背景・枠線・影・文字グリフ列・画像）
      public final SceneLayer[] children;      // スタッキングコンテキスト順（negZ → flow → posZ）に整列済みの子層
      public final boolean hasAnimatedOrScrollDescendant; // 静的サブツリーの FBO/テクスチャ再利用判定
  }
  ```
  - **動的パラメータの完全外部化**: スクロール量（`ScrollState`）および補間アニメーション（`AnimatorState`、`int nodeId` キー）は `DisplayScene` を書き換えず、バックエンド走査時に `nodeId` で O(1) 参照して座標・変形・色を合成する。
  - **L4a `SceneEmitter` → L4b バックエンドの完全単方向化**:
    - `SceneEmitter` が `LayoutFragment → DisplayScene` を生成する（未変更 `LayoutFragment` サブツリーに対応する `SceneLayer` も参照同一性で再利用）。
    - `GpuRenderBackend`（RHI `GpuDevice`）と `SoftwareRenderBackend`（64×64 `TileQueue`）はどちらも `RenderBackend.render(DisplayScene scene, DynamicSceneState state, long nowMs)` を直接受け取る。
    - GPU 未対応の稀なベクターパス・特殊ブレンド等がある場合、**`SceneEmitter` 側が事前にソフトウェアラスタでビットマップ化（`DrawOp.Image`）してから `DisplayScene` に積む**ことで、`GpuRenderBackend ↔ SoftwareRenderBackend` のピンポン呼び出しを構造的に消滅させる。
  - **ヒットテスト統合**: `HitTestIndex`（749 行）を廃止し、`DisplayScene`（または `LayoutFragment`）を逆順（手前から奥）に直接走査してスクロール・変形・クリップを評価する（`O(木深さ + 命中候補)`）。
- **削除対象**
  - `DisplayItem.java`（237 行）・`PaintCell.java`（77 行）・`RenderHints.java`（174 行）・`ImmutableItemList.java`・`AbsoluteItems.java`（118 行）
  - `DisplayListBuilder.java`（922 行）・`FragmentPaint.java`（776 行）・`Fragment.java`（471 行）・`FragSeq.java`（428 行）
  - `PaintSceneDriver.java`（926 行）・`RetainRanges.java`（87 行）・`RangeBounds.java`（40 行）・`InkBounds.java`（72 行）・`InkState.java`（66 行）・`ItemDiff.java`（93 行）
  - `HitTestIndex.java`（749 行）
  - `CpuFallback.renderOnto` 逆転コールバック・`GpuSink.java` ピンポン経路（`core/paint/emit` / `core/paint/port` は `SceneEmitter` + RHI 契約へ整理）
- **完了ゲート**:
  - `showcase` 幅変更の描画シーン構築時間：`0.273 ms → ≤ 0.08 ms`。
  - `varied-list-2000 fontsize`（4 スレッド・実書体）：`10.76 ms → ≤ 5.0 ms`（**144Hz 予算 `6.94 ms` 達成**）。
  - 3 者画素一致（CPU / OpenGL / Vulkan）100% 緑、`L4` 関連コード規模 30% 以上削減。

---

### 3.5 Phase M4: サンプル MOD レガシーレンダラ（9,369 行）完全撤去と L5 ホスト層純化

二重保守されていた Minecraft 側レンダラを完全に削除して `:core` 単一パイプラインへ統合し、肥大・分裂していたホスト層（`UiView` / `StandaloneView` / MOD `client`）を解体・統合する。

- **サンプル MOD レガシーレンダラ撤去（`src/main/java/com/nishiyu/enderframelib/client/renderer/`）**
  - **完全削除するファイル**:
    - `DisplayItemDrawer.java`（1,007 行）
    - `LayerCompositor.java`（1,399 行）
    - `WebSdfShader.java`（982 行）
    - `TextElementRenderer.java`（547 行）
    - `OutlineTextRenderer.java`（392 行）
    - `BackgroundRenderer.java`（200 行）
    - `ControlElementRenderer.java`（198 行）
    - `MediaElementRenderer.java`（352 行）
    - `RoundedRectRasterizer.java`（243 行）
    - `EnderFrameBuiltInConfig.useCoreBackend` の切り替え・フォールバック分岐
  - **MOD 固有要素の RHI SPI 統合**:
    - アイテム・エンティティ・流体・ブロック・プレイヤー頭・`<mc-scene>`・Minecraft ビットマップ書体・オフスクリーン動画テクスチャを、`:core` の RHI 外部テクスチャ SPI（`GpuDevice.wrapExternalTexture` / `ImageProvider` / `FontResolver`）経由で供給するアダプタ（`MinecraftMediaBridge`, `MinecraftFontAdapter`）のみを MOD 側に残す。
    - `CoreGpuSurface`（`MinecraftHostSurface` へ整理）を全画面（GUI / コンテナ / HUD / ブロック面 / ホログラム / アイテム / 地図）の**唯一の描画経路**とする。
- **L5 ホスト層（`UiView` + `StandaloneView` + `SessionContext`）の純化と重複解消**
  - `UiView.java`（2,005 行）と `StandaloneView.java`（1,060 行）を解体し、以下に分離する：
    1. **`EngineView`**（`common/`、≤ 400 行）：1 画面の純 Java コア状態（`DomDocument`, `StyleSheet`, `LayoutCache`, `DisplayScene`, `ScrollState`, `InteractionState`, `AnimatorState`, `UiTimers`）とフレーム更新 `stepAndPaint(nowMs, backend)` のみを持つ。
    2. **`InputController`**（`common/`、≤ 500 行）：ヒットテスト・ポインタ・フォーカス移動・IME / テキスト編集（旧 `TextEditing` と MOD `TextareaEditor` を統合）・範囲選択・`<select>` ポップアップ（旧 `SelectPopup`）・スムーズスクロール補間を 1 箇所で処理する。
    3. **`StandaloneHost`**（`common/`、≤ 250 行）：ウィンドウ座標・クリップボード接続のみを受け持つ薄いラッパ。
    4. **MOD 側 `SessionContext` / `EnderScreenDelegate`（1,298 行 → ≤ 450 行）**：`serverManaged`, `headlessState`, `lastSyncRequestTime`, `shouldRequestSync()`, `explicitSize`, `applyOverrides()` 等の Minecraft 通信・画面統合責務だけを MOD 側に閉じ、`EnderScreenDelegate` の入力・編集重複コードを削除して `InputController` に委譲する。
  - **MOD パッケージ循環の解消**: `client ↔ client.gui ↔ client.renderer ↔ client.renderer.surface` の 5 組の直接循環依存を「`client.renderer`（低位描画アダプタ）← `client`（セッション管理）← `client.gui`（画面ホスト）」の単方向に整理する。
- **完了ゲート**:
  - `src/main/java/com/nishiyu/enderframelib/client/renderer/`：`9,369 行 → ≤ 1,500 行`。
  - L5 共通ホスト（`EngineView` + `InputController` + `StandaloneHost`）：計 `≤ 1,200 行`（旧 `UiView` + `StandaloneView` + `UiInput` = 3,855 行）。
  - MOD 側パッケージ循環：`5 組 → 0`。
  - `./gradlew runGameTestServer` および実機スクリーンショット回帰（GUI・HUD・ブロック面・ホログラム・アイテム・`<mc-scene>`・メディア）全通過。

---

### 3.6 Phase M5: DOM 整理・コールド起動短縮・並列/窓直交統合

- **DOM とアリーナの二重管理解消（`core/dom/`）**
  - `DocumentArena`（446 行）が `HtmlNode`（1,399 行）をミラー保持している二重構造を整理し、`HtmlNode` / `HtmlElement`（980 行）をノード基本構造・属性・DOM API・イベント配送へ責務分割して各クラス `≤ 500 行` に収める。
  - セレクタ高速化に必要なデータ（atom 化タグ/id/class・Bloom ビット）のみを軽量 `SelectorIndex` として独立させ、トポロジ二重同期を廃止する。
- **コールド起動の劇的短縮（旧 B6）**
  - `SystemFonts` の起動時全フォントフォルダ走査（Windows で約 500 ファイル・150〜300 ms）を撤廃し、既定ファミリの直開きオンデマンド解決（約 2 ms）＋未知ファミリ要求時のみの遅延スキャン＋ディスク目録キャッシュを導入する。
- **不変データモデル上での並列化・描画スレッド分離（旧 B7）および窓×描画直交分離（旧 G7）**
  - 不変 `LayoutFragment` + 不変 `DisplayScene` の恩恵により、データ競合ゼロでサブツリー単位の並列レイアウト（`LayoutTaskPool`）および論理スレッド／描画スレッドの非同期パイプライン分離を実装する。
  - `WindowHost` SPI と標準 Java 2D/AWT 窓ホスト（`JFrame` + `BufferStrategy` + `DataBufferInt` 損傷矩形 Blit + `InputMethodRequests` による OS 標準 IME 連携）を整備し、`:core` 単体（GLFW/GL 不要）での 144Hz 起動と `:core-glfw` 上での `SOFTWARE` / `GL` / `VULKAN` 直交選択を完成させる。
- **完了ゲート**:
  - コールド起動（`ColdStartMain showcase`・JDK 21 素）：`546 ms → ≤ 200 ms`（Chrome `304 ms` 以下）。
  - 全実文書の初回構築（`voidwave.html open` 等）：`9.15 ms → ≤ 5.5 ms`（全シナリオ 144Hz 予算内）。
  - `:core` および MOD 全体で **1,000 行超クラス `0 個`**。

---

### 3.7 Phase M6: 品質ゲート最終固定と配布（v2.0.0 GA）

- `ArchitectureHealthTest` の最終上限を固定する（1 クラス `≤ 600 行`、グローバル mutable static = `0`、パッケージ循環 SCC = `0`、`core/` の Minecraft 非依存 = 100%）。
- Linux 環境での全件テスト・実機検査（`scripts/ci-linux.ps1 -Full`）を通過させ、公開 API 署名（`ApiSignatureTest`）を確定して `:core`（v2.0.0）を単体ライブラリとして Maven Central / GitHub Packages へ公開可能にする。

---

## 4. KPI ダッシュボード（現状 vs 根本治療後の目標）

| 軸 | 指標 / シナリオ | 現状（2026-11-24 実測・確認値） | 根本治療後の目標（達成フェーズ） |
| :--- | :--- | :---: | :---: |
| **性能** | `varied-list-2000 fontsize`（4 スレッド・実書体） | `10.76 ms`（144Hz 予算超） | **`≤ 5.0 ms`**（M3 で 144Hz 達成） |
| **性能** | `large-list-8000 child`（1 件挿入・削除） | `7.89 ms`（超線形） | **`≤ 1.0 ms`**（M2 で達成） |
| **性能** | `showcase` 幅変更（`resizeexact`・GL） | `0.427 ms`（うち表示リスト 66%） | **`≤ 0.20 ms`**（M3 で達成） |
| **性能** | `voidwave.html open`（実文書初回構築） | `9.15 ms`（144Hz 予算超） | **`≤ 5.5 ms`**（M5 で達成） |
| **性能** | コールド起動（JDK 21 素・`showcase`） | `546 ms`（Chrome `304 ms`） | **`≤ 200 ms`**（M5 で達成） |
| **メモリ** | 保持ヒープ `varied-list-2000` / `large-list-2000` | `40.2 MB` / `10.0 MB`（Chrome 1.55x / 2.0x） | **`≤ 12.0 MB` / `≤ 4.5 MB`**（M2 で Chrome 以下） |
| **構造** | 増分・キャッシュ専用コード規模 | 43 ファイル・`6,527 行` | **`≤ 800 行`**（M2〜M3 で 1 層メモ化のみ） |
| **構造** | `LayoutBox` / `ComputedStyle` / `UiView` 行数 | `1,785` / `2,095` / `2,005 行` | **解体・各 `≤ 450 行`**（M1〜M4） |
| **構造** | 1,000 行超クラス数（`:core` / MOD 合計） | `25 個`（`:core` 19 + MOD 6） | **`0 個`**（M5〜M6） |
| **構造** | グローバル `Shortcut` スイッチ / 共有エポック | `38 個` / 複数（`SHAPE_EPOCH` 等） | **`0 個` / `0 個`**（M0〜M2） |
| **構造** | MOD 側重複レンダラ（`client/renderer`） | `9,369 行`（`useCoreBackend = false` 既定） | **`≤ 1,500 行`・コア単一経路化**（M4） |
| **正しさ** | WPT 参照画像比較 / 3 者画素一致 / `FrameOracle` | `160 / 260`・画素一致 | **`≥ 160 / 260` 維持・全一致**（全フェーズ） |

---

## 5. 新・運用ルール（二度と構造崩壊とパッチ地獄に戻らないための 7 原則）

1. **優先順位表は §3 の 1 つだけとする。** 新しい課題は §3 に統合し、完了したフェーズの成果と数値は §4 を上書き更新する。
2. **壊れた IR や可変オブジェクトの上に対症療法的キャッシュを足さない。** 速度・メモリ問題が出たときはまずデータ構造（入力と出力の分離・参照共有・階層表現）を疑い、破壊的変更で根を絶つ。
3. **キャッシュは「不変入力の参照同一性（`==`）による 1 層メモ化」のみ許可する。** 40 欄ハッシュ後追い検証（`PaintStamps` 型）・ビットマスク読み取り台帳（`LayoutReads` 型）・手書き純粋性フラグ（`F_*`）・出力その場書き換えパッチは新設を禁止する。
4. **プロセスグローバルな可変状態を禁止する。** 新しい `Shortcut` スイッチ・`static volatile` フラグ・プロセス共通エポックカウンタ・ビジネスロジック上の `ThreadLocal` を作ってはならない。すべての状態は 1 ビュー（`EngineView`）に閉じる。
5. **クラス規模規律を強制する。** 新規作成および刷新するクラスは **`≤ 600 行`** を原則とし、いかなる理由でも **1,000 行超クラスの再発生を許さない**（`ArchitectureHealthTest` で機械的に阻止）。
6. **検証は「外から見た正しさ」と「実コスト」で行う。** 正しさは WPT・CPU/GL/Vulkan 3 者画素一致・`FrameOracle`（全再計算との一致）で固定し、性能とメモリはヒープ実測と同時交互 A/B 計測で判定する。内部の記憶ヒット率やフィールド数を固定する形式ラチェットは設けない。
7. **完了の定義は「旧コード・旧経路の完全削除」「テスト緑」「§4 KPI 更新」の 3 点セットとする。** 旧経路をフラグやフォールバックで残したまま完了としない。

---

## 6. アーカイブおよび他文書との関係

- 旧計画（C 系・K 系・K5b・W0〜W3・2026-11-24 までの設計記録と知見 `§6-1〜205`）の全文は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) に退避済みである（`RegressionCatalogTest` が参照する `§6-N` は同アーカイブの付録 3 にすべて保全されている）。
- `core/DESIGN_INCREMENTAL_KERNEL.md` は本刷新（§1.2 撤回一覧）により**廃止扱い**とし、Phase M0〜M2 完了に合わせて削除またはアーカイブする。
- `CLAUDE.md`・`ARCHITECTURE.md`・`BENCHMARK.md`・`REFACTORING_PLAN.md` は、Phase M0〜M4 の各段階の実装完了に合わせて本計画書（Engine v3）の内容へ順次同期する。
