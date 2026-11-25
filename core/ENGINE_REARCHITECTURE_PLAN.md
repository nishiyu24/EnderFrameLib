# EnderFrameLib 根本再設計プラン（Engine v3: 破壊的アーキテクチャ刷新）

> **更新日**: 2026-11-25（M2a 完了・Go）
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
| **K5b（L3 / L4: 可変 `LayoutBox` + `PartialBuild` + 多段メモ群延命）** | `ChangeLedger` の下流に `PartialBuild` を置き、可変 `LayoutBox` / `MeasureMemo` / `PlacementMemo` を温存したまま入口を付け替え | 可変 `LayoutBox` と多段キャッシュを残したまま入口だけを付け替えても下流の照会コスト（1 µs/子）が消えない（※追記専用台帳 `ChangeLedger` 159 行自体は撤回せず、M2a の `BoxTreeCompiler` の唯一の入力側変更通知へ引き継ぐ） |
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
   - CSS プロパティを継承性・利用箇所に応じた機能グループ別不変構造体（`InheritedStyle`, `BoxGeomStyle`, `VisualStyle`, `FlexGridStyle`, `TableStyle`, `RareStyle`）へ分離する。M2c で「レイアウトに効く群」と「描画だけの群」の軸を加え、継承群も 2 つに分ける（§3.4.2-3。描画だけの変化がレイアウトの入力の参照を変えないため）。
   - 初期値グループは静的シングルトンを共有し、親から受け継ぐ `InheritedStyle` が未変更なら子も同一参照を共有する。長さは 64-bit プリミティブ値 `CssLength`、色・キーワードは `int` ARGB / `enum` / bitfield で直接保持し、文字列ラッパー配列と `Map<String, String>` 受け渡しを根絶する。

3. **階層的・数値 ID ベースの `DisplayScene`（フラット `DisplayItem` 完全廃止）**
   - `LayoutFragment` から、階層構造（変換・クリップ・レイヤー・スクロールコンテナ・境界矩形）とコンパクトなプリミティブ描画コマンド列を保持する不変ツリー **`DisplayScene`** を直接構築する。
   - `SceneLayer` の子列は 32 子単位の不変チャンク階層（`SceneChildren` / `SceneChunk`）とし、各チャンクの包含境界矩形（および長大リストでの単調 Y 順序）を用いた**可視範囲カリング**と、外部 `ScrollState` を参照して走査時に評価する **`position: sticky` の動的オフセット解決（`StickyConstraint`）** を備える。
   - ノード参照はすべて **`int nodeId`** とし、文字列 ID・`Map<String, String> events`・`AbsoluteItems` 再変換・`PaintSceneDriver` による階層再復元を完全に消滅させる。
   - L4a（`SceneEmitter`）→ L4b（`GpuRenderBackend` RHI / `SoftwareRenderBackend`）は完全単方向データフローとし、GPU 未対応要素は L4a 側で事前にテクスチャ化して流す。

4. **単一のメモ化言語・プロセスグローバル状態ゼロ**
   - 38 個の `Shortcut` スイッチ（M0 で新規追加禁止・凍結し、M1〜M3b で所有する旧経路の廃止と同時に順次削除して `0` にする）、プロセス共有エポック（`SHAPE_EPOCH` 等）、`ThreadLocal` 隠れ状態、7 系統のキャッシュを全廃する。
   - 入力側の変更検知（dirty 足切り）の入口は **`ChangeLedger`（1 ビュー閉鎖・追記専用台帳）1 本のみ**とし、DOM ノードへの `DIRTY_*` フラグ欄追加は禁止する。
   - すべての実行状態・メモテーブルは 1 ビューインスタンス（`EngineView`）の内部に閉じ、下流キャッシュは **「1 層メモ化言語」** のみを許可する。ただし呼び出しごとに生成され得る値オブジェクトの扱いを明確に分ける：
     - **構造・データ木入力（`BoxNode` / `BoxChunk`・`Style`・`LayoutFragment`）**: **参照同一性（`==`）のみ**で照合する。
     - **値オブジェクト入力（`ConstraintSpace`：確定寸法・利用可能幅・`ExclusionSpace`・`MarginStrut` 等、および `MinMaxContext`）**: 呼び出しのたびに新しく生成され得る値オブジェクトであるため、素の `==` 比較のみではキャッシュが当たらない。そのため **「`==` 高速パス付きの値等価（`equals` / `hashCode`）」** または **「1 ビュー内インターン（同値なら同一参照を返し `==` で照合）」** のいずれかを許可し、M2a スパイクの実測で採用方式を確定する（詳細は §3.3・§3.4）。**→ M2a で前者（`==` 高速パス付きの値等価）に確定（§3.3 C 判定基準 3）。**
     - **intrinsic size と多段測定**: `MinMaxSizes`（`min-content` / `max-content`）の 1 層メモ化、および Flex/Grid の仮測定・確定測定（2 パス）を相互駆逐させないための固定少数スロット（2〜4 スロット）は、この 1 層メモ化言語の正規要素として位置づける。

5. **単一レンダラ・ホスト層の純化**
   - サンプル MOD 側の 9,369 行レガシーレンダラ（`DisplayItemDrawer` / `LayerCompositor` / `WebSdfShader` 等）を前提検証のうえで M3a に前倒し撤去し、`:core` の RHI バックエンド（`MinecraftHostSurface`）に一本化する。
   - M4 で `UiView` から Minecraft 固有の同期残骸を剥がして純 Java の薄い `EngineView`（≤ 400 行）に純化し、入力・フォーカス・テキスト編集・選択・`<select>` ポップアップを共通の `InputController` へ統合する。

### 2.2 新 5 層単方向アーキテクチャ（Engine v3）

```text
┌────────────────────────────────────────────────────────────────────────────┐
│ L5: Host & View Orchestration (`common/EngineView`, `InputController`,     │
│     `api/*`, MOD `SessionContext` / `MinecraftHostSurface`)                │
│     所有物: DOM の 1 インスタンス・ScrollState・AnimatorState・入力状態     │
├────────────────────────────────────────────────────────────────────────────┤
│ L4: Unidirectional Scene & Raster (`core/paint/*`)                         │
│     L4a `SceneEmitter`: Immutable `DisplayScene`                           │
│         (`SceneLayer` + `SceneChildren` チャンクカリング + `StickyConstraint`) │
│     L4b `GpuRenderBackend` (RHI `GpuDevice`) / `SoftwareRenderBackend`     │
│     ※ 文字列 ID ゼロ・ピンポン往復ゼロ・完全単方向・`int nodeId` 索引       │
├────────────────────────────────────────────────────────────────────────────┤
│ L3: Pure Layout (`core/layout/*`)                                          │
│     `(BoxNode / BoxChunk, Style, ConstraintSpace)                          │
│         -> Immutable LayoutFragment / ChunkedFlow`                         │
│     `FormattingEngine` SPI (`layout` + `computeMinMaxSizes`)               │
│     1 層 `LayoutCache`（構造入力 `==` + `ConstraintSpace` 値等価/インターン │
│         + 固定少数スロット）+ チャンク累積和フロー                           │
├────────────────────────────────────────────────────────────────────────────┤
│ L2: DOM, ChangeLedger, Style & Incremental BoxTreeCompiler                 │
│     (`core/dom/*`, `core/css/*`)                                           │
│     軽量 `DomNode` + `ChangeLedger` + `SelectorIndex` + 不変 `Style`        │
│     `BoxTreeCompiler`（`core/box/*`）: `(DOM, ChangeLedger, prev) -> BoxNode / BoxChunk` │
├────────────────────────────────────────────────────────────────────────────┤
│ L1: Text, Media, 2D/3D Primitives & RHI (`core/text/*`, `core/raster/*`,   │
│     `core/scene3d/*`, `core/kernel/gpu/rhi/*`, `core/kernel/memory/*`)     │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 破壊的再設計ロードマップ（唯一の優先順位表: 本流 Phase M0〜M6 ＋ 独立トラック P1〜P3）

### 3.0a 本流ロードマップ（Engine v3 アーキテクチャ刷新: Phase M0〜M6）

| 順位 | Phase | タイトル | 主な破壊・新設対象 | 完了時の定量的成果 |
| :---: | :--- | :--- | :--- | :--- |
| 1 | **M0** | **新ゲートの先行設置とロックイン解除〔完了〕** | **順序厳守**: 新ゲート（`MemoryAndLatencyGate`・`ArchitectureHealthTest`）先行設置・緑確認 → 旧ラチェット 8 本・`memo*` 判定・`shortcutMatrix`・`*Verifier` 3 種を削除、`Shortcut` 新規追加禁止・上限凍結（`37 → 35` へ減少、M1〜M3b で旧経路ごと削除） | 保護空白ゼロでゲート置換完了、`Shortcut` 上限 `35` 凍結（新規追加 0） |
| 2 | **M1** | **`ComputedStyle` の完全刷新〔完了〕**（M1a・M1b・M1c 完了） | `CssValue[207]`・`StyleSlice`・`DIFF_MEMO`・`StyleMap`・`SHARED`・`PackedLengths`・`TypedValueCache`・`StateStyleMemo` 全廃 → 6 グループ不変構造体 `Style`（`InheritedStyle` 参照共有＋等価判定含む）+ 64-bit `CssLength` + 不変 `StyleFacts` 導入、`layout`/`paint`/`common`/`dom` の読み口を型付きへ完全切替、スタイル系 `Shortcut`/`EngineFlags`（`STYLE_SHARE`・`TEXT_STYLE_FROM_PARENT`）削除 | `ComputedStyle` 2,095 → **398 行**（`Style` 229 行）、`varied-list` Style 層 **3.5 MB**・`large-list` **9.63 MB**（全体 `varied-list` 35.96 MB の残り約 32 MB は M2b/M3b/M5 対象の `LayoutBox`/`InlineFragment`/`DisplayItem`/`TextShaper`）、読み口文字列参照 `0` |
| 3 | **M2a** | **入力側増分 `BoxTreeCompiler` 設計と縦割りスパイク関門〔完了・Go〕** | `ChangeLedger` 接続・継承打ち切り・`BoxChildren`（32 子チャンク）・`ConstraintSpace` 比較方式確定（**値等価＝案 A を採用**）・`computeMinMaxSizes` + 固定少数スロット設計。**実 DOM + M1 実 `Style` 上で Block + Text + 入れ子 Flex（2 パス）の独立スパイク**を実測し Go/No-Go 判定（新設 `core/box/`・`core/layout/fragment/`） | **全 5 基準合格**: 8,000 行挿入 `0.11〜0.16 ms`（同 JVM 交互 A/B で旧経路 `4.3〜5.9 ms`）、入れ子 Flex 指数爆発 0（深さ 8 で箱あたり ≤ 2 回・追い出し 0）、レイアウト層ヒープ増分 `large-list 0.54 MB`・`varied-list 1.87 MB`、全再計算と 100% 一致 |
| 4 | **M2b** | **全 `FormattingEngine` 移植と `LayoutBox` 一括解体**（進行中。段分けは §3.4.1、方針は §3.4.2: M2b-1〜5 でエンジンを新経路へ → **M2c で描画の入力を断片木へ** → M2b-6 で旧経路を削除） | float 非侵入/相対除外・`computeMinMaxSizes`・全エンジン段階移植を経て `LayoutBox`・`MeasureMemo`・`PlacementMemo`・`PaintStamps`・`LayoutReads`・`PartialBuild`・レイアウト系 `Shortcut` 等（約 4,500 行超）を削除 | 全エンジン WPT ≥ 160/260 & `FrameOracle` 一致、旧コード削除 `≥ 4,500 行`、レイアウト増分 `≤ 4.0 MB` / 中間全体 `≤ 14.0 MB` |
| 5 | **M3a** | **`CoreGpuSurface` 品質検証と MOD レガシーレンダラ先行撤去** | `useCoreBackend` の全描画要件（`<mc-scene>`・`<scene-3d>`・画像フォールバック・ビットマップ書体・ツールチップ）の事前品質確認関門を通過した上で、MOD `client/renderer` 9,369 行を M3b より先に撤去し `DisplayItem` 消費者を激減させる | MOD 描画 `≤ 1,500 行`・フォールバック `0 件`・`runGameTestServer` & 実機スクショ回帰全件合格 |
| 6 | **M3b** | **フラット `DisplayItem` 廃止と階層的 `DisplayScene`** | `DisplayItem`・`FragmentPaint`・`PaintSceneDriver`・`HitTestIndex`・残存 `Shortcut.java` を完全廃止し、`SceneChildren`（32 子チャンクカリング）+ `StickyConstraint` 付き `DisplayScene` + L4 単方向化を完了 | `varied-list fontsize` 10.76 → ≤ 5.0 ms（144Hz 達成）、3 者画素一致 100%、`Shortcut` 38 → **0** |
| 7 | **M4** | **L5 ホスト層純化と MOD 循環依存解消** | `UiView` / `StandaloneView` を `EngineView` + `InputController` + `StandaloneHost` へ統合、MOD `EnderScreenDelegate` 縮小・循環依存解消 | L5 ≤ 1,200 行、MOD 循環 5 → 0 |
| 8 | **M5** | **DOM 整理と 1,000 行超クラス 0 の達成** | `DocumentArena` 二重同期解消、`HtmlNode`/`HtmlElement` 責務分割、`SelectorIndex` 独立、`ArchitectureHealthTest` による 1,000 行超強制阻止（※起動/並列/AWT 窓は別トラックへ分離） | 1,000 行超クラス **0 個**、`DocumentArena` 二重同期 `0`、全体保持ヒープ `varied-list ≤ 12.0 MB`・`large-list ≤ 4.5 MB` 達成 |
| 9 | **M6** | **品質ゲート最終固定と配布（v2.0.0 GA）** | `ArchitectureHealthTest` のハードゲート上限を 600 行へ確定、Linux CI 完全通過、API 署名確定、Maven Central / GitHub Packages 公開 | **全クラス ≤ 600 行（ハードゲート強制）**、グローバル mutable static = 0 |

### 3.0b 独立トラック（アーキテクチャ刷新と非依存／後続の別優先順位: Track P1〜P3）

M5 へ混在していた起動高速化・非同期並列化・AWT 窓統合は、データ構造刷新とは独立した課題であるため、本流の膨張を防ぐ目的で別トラックとして管理する（詳細は §3.9）。

| トラック | タイトル | 前提条件 | 主な対象 | 完了時の定量的成果 |
| :--- | :--- | :---: | :--- | :--- |
| **Track P1** | **コールド起動短縮（旧 B6）**（**P1a 完了**・P1b 未着手） | **なし（随時着手可）** | P1a: `SystemFonts` の起動ごとの全フォルダ走査をディスクの書体の目録（`FontCatalog`）で撤廃〔完了 2026-11-25〕。P1b: JVM の冷えた起動（クラスのロード・インタープリタ実行）を AOT キャッシュ（JEP 483）等で縮める | P1a: 書体の読み込み `243 → 24 ms`（実書体の起動 `786 → 718 ms`）。P1b: コールド起動 `546 → ≤ 200 ms`（Chrome `304 ms` 以下） |
| **Track P2** | **並列レイアウト・描画スレッド分離（旧 B7）** | **M3b 完了** | 不変 `LayoutFragment` / `DisplayScene` 上での `LayoutTaskPool` と論理/描画非同期パイプライン | `voidwave.html open` 等の初回構築 `9.15 → ≤ 5.5 ms` |
| **Track P3** | **窓×描画直交分離・標準 AWT 窓ホスト（旧 G7）** | **M4 完了推奨** | `WindowHost` SPI・AWT `JFrame`（`BufferStrategy`・損傷矩形 Blit・OS IME）と `:core-glfw` 直交選択 | `:core` 単体 144Hz 起動＋`SOFTWARE`/`GL`/`VULKAN` 直交選択 |

---

### 3.1 Phase M0: 新ゲートの先行設置とロックイン解除（破壊的手術の足場）〔完了〕

M1〜M4 でデータ構造を壊して作り直す前に、「外から見た正しさ・性能・コード健全性」を見張る新ゲートを**先に設置して緑にしたうえで**、「旧構造（可変 `LayoutBox`・多段キャッシュの内部カウンタ）を固定して変更を阻んでいる番人」を撤去した。

> **順序厳守ルール（遵守済み）**: **新ゲート（`MemoryAndLatencyGate`・`ArchitectureHealthTest`）が稼働し緑になってから、旧ラチェット 8 本と `costGate` の `memo*` 判定を削除した。**

- **Step 1: 新ゲートの先行設置と既存出力ゲートの緑確認（削除前に実施・完了）**
  1. **維持する出力等価性ゲートの緑確認**: `WptReftestRatchetTest`（合格 160/260）・`GpuBaselineTest`・`VkBaselineTest`・`RealDocumentFramesTest`・`FrameOracleTest`・`ApiBoundaryTest` が全緑であることを確認。
  2. **新設 `MemoryAndLatencyGate`**（`core/src/test/java/com/nishiyu/enderframelib/core/MemoryAndLatencyGate.java` + `:core:memoryAndLatencyGate` + `MemoryAndLatencyGateTest`）: 全再計算・差分シナリオのヒープ保持量（MB）と交互 A/B 実行時間（ms・増分/全面比）のみを監視し、現状ベースライン（`core/src/test/resources/memory-latency-baseline.txt`）で緑化。
  3. **新設 `ArchitectureHealthTest`**（`core/src/test/java/com/nishiyu/enderframelib/core/ArchitectureHealthTest.java` + `architecture-health-ceilings.txt`）: 1 クラス行数上限（新規 ≤ 600 行、既存 1,000 行超の上限 26 クラス固定）・グローバル mutable static 数（`volatile` / `ThreadLocal` / `Atomic*` / `*_EPOCH`）・パッケージ循環数（SCC）・`:core` 境界純度（Minecraft 非依存）・**既存 `Shortcut` / `EngineFlags` 近道数の上限凍結（新規追加を禁止し単調減少のみ許可）** を直接検査するゲートを新設し、既存の構造健全性テスト（`FileSizeRatchetTest`・`StaticStateRatchetTest`・`PackageCycleRatchetTest`・`ModulePurityRatchetTest`・`LayerDependencyRatchetTest` 等）と共に緑確認。
- **Step 2: 新ゲート稼働・緑確認後の旧番人撤去（完了）**
  1. `CostGateMain` と `costgate-baseline.txt` から旧内部カウンタ `memo*`（`memoHits`/`memoCeil`/`AMP_LIMIT`）による合否判定を撤去し、`:core:shortcutMatrix` タスク・`ShortcutMatrixMain.java`・`.github/workflows/full-tests.yml` の `shortcut-matrix` ジョブを撤去。
  2. 旧内部構造を固定していたラチェットテスト 8 本・個別検証クラス 3 本（＋単体テスト 3 本・`MutationFuzzKeyLeakTest`）・`VERIFY_MEMO` / `VERIFY_BOX_MEMO`・不要テーブル（`patch-sites.txt`・`shortcut-matrix-known.txt`）を削除：
     - ラチェット 8 本：`IncrementalStateRatchetTest`・`ShortcutSwitchRatchetTest`・`LayoutReadLedgerRatchetTest`・`PatchSiteRatchetTest`・`PaintCellRatchetTest`・`SliceWriteSiteRatchetTest`・`ItemDiffRatchetTest`・`LayoutBoxReadOnlyFromPaintRatchetTest`
     - 個別検証 3 本：`MeasureVerifier.java`・`OwnItemsVerifier.java`・`PoseVerifier.java`（テスト 3 本および呼び出し元含む）
  3. **旧ランタイム `Shortcut` 群**: 新規追加禁止・上限凍結（`VERIFY_MEMO` / `VERIFY_BOX_MEMO` 削除により `37 → 35`、`EngineFlags` `47 → 45` へ減少、以後は M1〜M3b で各旧経路を消滅させるのと同時に順次削除して `0` にする）。
- **完了ゲート（全達成）**:
  - `MemoryAndLatencyGate`・`ArchitectureHealthTest` が先に緑になった後に、旧ラチェット 8 本・`memo*` 判定・`shortcutMatrix`・`*Verifier` 3 本がすべて撤去済み。
  - `Shortcut` の新規追加 `0 件`・上限 `35` 凍結が `ArchitectureHealthTest` で強制済み。
  - 全出力等価性テスト（WPT 160/260・3 者画素一致・`RealDocumentFramesTest`・`FrameOracleTest`・`ApiBoundaryTest`）が緑。

---

### 3.2 Phase M1: `ComputedStyle` の完全刷新（型付きグループ不変構造体）〔完了〕

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
  - **O(1) 継承共有と M2a 向け等価契約**: カスケードで継承プロパティの上書きが無ければ、子は親の `InheritedStyle` インスタンスをそのまま参照共有する（`child.inherited == parent.inherited`）。さらに M2a の `BoxTreeCompiler` が継承変化の伝播打ち切りを行えるよう、**`InheritedStyle` および `CustomProperties` は参照同一性（`==`）に加え高速な構造等価判定（`equals`）を提供する**。
  - **スタイルのプリミティブ化による `Layout` / `Paint` との契約単純化**: レイアウトと描画は `Style` から文字列を読まず（`get("position")` ゼロ）、型付きフィールド／列挙型のみを参照する。
- **削除対象**
  - `core/src/main/java/com/nishiyu/enderframelib/core/css/CssValue.java`（弱参照 `ConcurrentHashMap` 表）
  - `ComputedStyle` 内の `StyleSlice` / `SLICE_POOLS` / `DIFF_MEMO` / `StyleMap` / `StyleTable` / `ObjMemo` / `CachedFace` / `CachedTextStyle`
  - `core/src/main/java/com/nishiyu/enderframelib/core/css/StyleFacts.java`（209 行。`Style` の型付きグループ自体が facts を兼ねるため不要）
  - `core/src/main/java/com/nishiyu/enderframelib/core/css/PackedLengths.java`
  - `core/src/main/java/com/nishiyu/enderframelib/core/css/TypedValueCache.java`（68 行）
  - `core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/StateStyleMemo.java`（167 行）
  - `RenderTreeBuilder` 等の `Map<String, String> inheritedStyle` 引数受け渡しと `asMap()` 全箇所、およびスタイル系 `Shortcut` スイッチ（`STYLE_SHARE` / `TEXT_STYLE_FROM_PARENT` 等）
- **移行手順**:
  1. `CssLength` 値表現と 6 グループ不変レコード/クラスを新設し、カスケード結果の生成先を `Style` へ切り替える。
  2. `core/layout/` と `core/paint/` のスタイル読み口を `Style` の型付きアクセサへ機械的に付け替える。
  3. `CssValue.java`・`StyleFacts.java`・旧 `ComputedStyle` のキャッシュ群とスタイル系 `Shortcut` を一括削除する。
- **完了実績（M1c 完了 2026-11-25）**:
  - 旧 `ComputedStyle`（2,095 行）→ 薄い不変ラッパー `ComputedStyle`（`398 行`）＋ `Style`（`229 行`）＋ `StyleFacts`（`215 行`・`Style` 構築時 1 回算出の導出値として統合）＋ `DerivedStyles`（`166 行`）。`StyleTable.groupMask` により未宣言グループは `DEFAULT` を無確保で共有し、`ComputedStyleFootprintTest` は **`2,192 B/style`**（上限 `< 4,500 B`）、`StyleFootprintTest` は 10,005 要素で **`4 B/element`**（旧 1,541 B）。
  - `varied-list-2000` 保持ヒープの実測分析: `MemoryAndLatencyGate` で `35.96 MB`（`large-list-2000` `9.63 MB`、`showcase` `2.05 MB`、`voidwave.html` `5.08 MB`）。`gcClassHistogram`（`MemFootprintMain`）で内訳を実測したところ、CSS 層合計（`BoxGeomStyle` 0.91 MB + `StyleFacts` 0.69 MB + `VisualStyle` 0.58 MB + `InheritedStyle` 0.47 MB + `Style` 0.35 MB + `ComputedStyle` 0.29 MB + `FontStyle` 0.23 MB）は約 **3.5 MB** まで削減済みであり、残り約 32 MB は `InlineFragment`（5.48 MB）・`LayoutBox`（3.23 MB）・`MeasureMemo`（1.37 MB）・`FragmentPaint$Memo`（1.37 MB）・テキスト整形/表示リスト（M2b・M3b・M5 の対象）である。
  - `Shortcut` `35 → 33`、`EngineFlags` `45 → 43`（`STYLE_SHARE`・`TEXT_STYLE_FROM_PARENT` 削除、`StateStyleMemo.java` 撤去に伴う往復キャッシュ効果は M2a `BoxTreeCompiler` の `CompiledEntry` で再構築予定。`costgate-baseline.txt` 更新済み）。
  - レイアウト／描画側からの `Style` 文字列参照・`asMap()` 呼び出し `0 件`、WPT・3 者画素一致・`RealDocumentFramesTest`・`FrameOracle` 緑。

#### 3.2.1 M1 の分割と進捗（2026-11-24 着手時の実測で分割）

着手時の実測規模は、`ComputedStyle` 2,095 行・参照 66 ファイル／約 800 箇所（テスト 51 ファイル／約 385 箇所）・文字列キー読み約 430 箇所・プロパティ ID 参照約 380 箇所。一度に旧経路の完全削除まで進めると全ゲートが赤のまま長く残るため、**同一フェーズ（M1）内の 3 段**に分け、段ごとに「テスト緑」を保つ。旧型の削除は M1c に集約し、M1 の完了判定（旧コード完全削除・KPI 更新）は M1c で行う。M1a〜M1b の間だけ旧 `ComputedStyle` と新 `Style` が併存する（運用ルール 7 の「同一フェーズ内で削除」は M1c で満たす）。

| 段 | 内容 | 完了条件 | 状態 |
| :--- | :--- | :--- | :--- |
| **M1a** | `CssLength`（64-bit。`calc()` は群ごとの不変表 `CssExprs` の添字で、プロセス共有の式表を持たない）・`CssExprs`・`CustomProperties`・型付き 6 群（`InheritedStyle` / `BoxGeomStyle` / `VisualStyle` / `FlexGridStyle` / `TableStyle` / `RareStyle`。`scripts/GenStyleGroups.java` が `PropertyRegistry` から生成）・`Style.of(ComputedStyle, 親 Style)` を新設。旧 `ComputedStyle` は温存し、**旧読み口との全フィールド等価**を検証する | ① 全レジストリプロパティがちょうど 1 群に所属（`StyleGroupCoverageTest`）② 合成入力＋同梱実文書の全要素で、全群の全フィールドが旧 `keywordOf`/`length`/`color`/`number`/`get`/`StyleFacts` と一致（`StyleTypedParityTest`）③ 継承群の親子参照共有・DEFAULT 共有・値等価・カスタムプロパティ表の共有（`StyleSharingTest`）④ `CssLength` の px 解決が旧 `CompiledLength#resolve` と一致（`CssLengthTest`）⑤ 生成クラス ≤ 600 行・`ArchitectureHealthTest` 緑・`ComputedStyle` 行数不増・変異（色の読みを壊す）で等価テストが落ちることを確認 | **完了** |
| **M1b** | カスケード（`StyleResolver` / `ValueComputer`）の生成先を `Style` へ切り替え、`core/layout`・`core/paint`・`common`・`core/dom` のスタイル読み口を型付きアクセサへ完全付け替え、`Map<String,String> inheritedStyle` 引数・`CssPropertyIndex`・`asMap()` 読みを除去 | 読み口の文字列参照 `0 件`、WPT 160/260 以上・3 者画素一致・`RealDocumentFramesTest`・`FrameOracle` 緑 | **完了** |
| **M1c** | `CssValue.java`・`PackedLengths.java`・`TypedValueCache.java`・`StateStyleMemo.java`・旧 `ComputedStyle` の `CssValue[207]`/`StyleSlice`/`SLICE_POOLS`/`DIFF_MEMO`/`StyleMap`/`SHARED`・スタイル系 `Shortcut`/`EngineFlags`（`STYLE_SHARE`・`TEXT_STYLE_FROM_PARENT`）を一括削除し、`ComputedStyle`（`398 行`）・`DerivedStyles`（`166 行`）・`StyleDiff`（`517 行`）・`StyleProperties`（`533 行`）へ刷新、`Style` を `StyleTable.groupMask` で未宣言群無確保化 | `ComputedStyle` **398 行**（≤ 500 行）、`ComputedStyleFootprintTest` **2,192 B/style**（`< 4,500 B`）、読み口文字列参照 0、`Shortcut` `35 → 33` / `EngineFlags` `45 → 43`、全テスト・ゲート緑・KPI 更新 | **完了** |

**M1a で得た設計上の知見（M1b の入力）**

- 群の値は**実際にはごく少数**: `varied-list` 型の文書（2,000 項目・要素 10,005）で、`BoxGeomStyle` は 2,005 インスタンス中**値の種類は 31**、`VisualStyle` 668 中 2、`FlexGridStyle` 401 中 2、`RareStyle` 402 中 3。一方 `InheritedStyle` は項目ごとに色・サイズが違い 4,002 種で、親子の参照共有（`child.inherited == parent.inherited`）により要素数 10,005 に対して 4,002 インスタンス（約 40 %）に畳まれる。
- **群を要素ごとに作ったままでは旧表現より大きい**（同文書の算出スタイル保持ヒープ: 旧 `ComputedStyle` 1,602 KiB／新 `Style` 2,233 KiB、要素あたり 164 B／228 B。旧は弱参照インターンで丸ごと 1 個に畳んでいた）。M1 の目標ヒープ（`varied-list` 40.2 → ≤ 18 MB）は、型付き化そのものではなく**同じ規則の一致結果が同じ `Style`（群）を参照する**ことで得る。→ **M1b の設計要件**: カスケードが一致規則集合の同一性（`==`）をキーに `Style` を 1 層メモ化して返す。プロセス共有のインターン表（弱参照 `ConcurrentHashMap`）は復活させず、状態は 1 ビューに閉じる。値等価のハッシュコンシングで済ませる案は §5-3 に反するため採らない。
- `calc()` 等の式は群ごとの `CssExprs`（式を持たない大半は共有の `EMPTY`）に持つため、旧 `PackedLengths` のプロセス共有・弱参照の式表は M1c で不要になる。
- 旧読み口は「宣言が無ければ呼び出し側の既定値」だが、`Style` は**宣言が無ければレジストリの初期値**を持つ。M1b の付け替えで呼び出し側の既定値が初期値と異なる箇所は、個別に洗って `Style` 側へ寄せる。
**M1b の棚卸しと作業順（2026-11-24 実測）**

スタイル読み口（文字列キー読み・`CssProperty`/`CssPropertyIndex` 参照・`asMap()`・`facts()`/`layoutFacts()`・`edgeLength`/`packedEdge`）の数は、`core/layout` 522・`core/paint` 227・`common` 124・`core/dom` 114（`core/css` 内 563 は M1c で本体ごと消える）。多い順は `BoxMeasurer` 63・`AnchorPositioning` 59・`Bindings` 59・`FlexLayoutEngine` 48・`GridLayoutEngine` 46・`Scene3DDomSync` 37・`ControlPainter` 33・`BoxAnimation` 31。`facts()`/`layoutFacts()` は 17 ファイル 63 箇所で、`StyleFacts.fillExtras` は旧文字列の細かい意味（`margin-left: auto` の大小文字区別、`%`・`calc` の部分文字列判定など）に依存するため、**意味を保ったまま型付きの導出値へ移す**必要がある。

作業順（各段で `WptReftestRatchetTest`・`GpuBaselineTest`・`RealDocumentFramesTest`・`FrameOracle` を緑に保つ）:

1. **b1 導出事実を `Style` へ〔完了 2026-11-24〕**: `StyleFacts` を `Style` の構築時に 1 回だけ作る不変の導出値へ置換（`fillExtras` と `ComputedStyle` 内の導出コード約 83 行を削除。`hasHeightDeclaration` は `!heightAuto` と常に併用され冗長なので削除）。旧実装で記録した基準 `golden/style-facts.txt`（合成 23 件の全フィールド＋実文書 8 本の全要素の CRC）と一致を確認してから旧実装を削除した（`StyleFactsGoldenTest`）。意図した挙動変更は 2 点だけ: ① `margin-left/right: auto` を上下と同じく大小文字・空白を区別しない（旧は完全一致のみ）② 算出スタイルに残らないショートハンド `overflow` を `ComputedStyle` が直接読む旧経路を廃止（カスケードは常に展開する。これに依存していた `MeasureReadSetTest` の入力を longhand へ修正）。`facts()`/`layoutFacts()` は `facts()` へ統合（63 箇所）。**橋渡しのコスト（b2 で解消）**: `ComputedStyle.typed()` が最初の参照で `Style` を 1 度作るため、`memoryAndLatencyGate` の `varied-list-2000:open` ヒープは基準 35.7 → 39.6 MB（上限 46.7 内）、`ComputedStyleFootprintTest` の 1 スタイルあたり割り当ては 4,500 → 8,176 B（上限を暫定 9,000 へ。b2 で 4,500 へ戻す）。
2. **b2 カスケードの生成先と 1 層メモ化**（**b2-1 完了 2026-11-24**／b2-2 未着手）: **b2-1** カスケード（`StyleResolver` → `ComputedStyle.ofOwned`）と文字の節（`buildTextChild`）が、親の `Style` を受け取って `Style.of(cs, 親)` を作り付ける（`ComputedStyle.typed()` は直接 new した場合だけの遅延フォールバック）。継承群は親と値が同じなら参照共有される。`varied-list-2000:open` ヒープ 39.6 → 37.3 MB（b1 の橋渡し直後比。基準 35.7）。**実測の内訳（`gcClassHistogram`、`varied-list-2000` 要素 10,005）**: 旧 `CssValue[]` 14,406 個 **11.9 MB**（M1c で消える本命）、`LinkedHashMap$Entry` 195,242 個 **7.8 MB**（`DeclMap` 等の宣言マップ。`Map<String,String>` の受け渡しの撤去＝b3〜b5 の対象。保持元は未特定で、b2-2 の最初に特定する）、`LayoutBox` 6.1 MB・`MeasureMemo` 2.6 MB（M2〜M3 の対象）。新側（橋渡し中）: `InheritedStyle` 2.0 MB（10,001 個）・`StyleFacts` 1.2 MB・`BoxGeomStyle` 1.1 MB（4,005 個で値の種類は 31）・`Style` 0.6 MB。したがって **M1 の 40 → 18 MB 目標は「CssValue[] の 11.9 MB ＋ 宣言マップの 7.8 MB を落とし、型付き群を ≈ 3 MB に収める」ことで届く見込み**で、型付き群自体のハッシュコンシングは必須ではない（群の値の種類が少ないのでインスタンス共有は増分で足りる）。**b2-2**: (1) `LinkedHashMap$Entry` の保持元を特定 (2) `Map<String,String> inheritedStyle` を `Style` に置換（`StateStyleMemo`・`StyleShare`・`InteractionState` の鍵も `Style` の同一性へ）(3) `StyleFacts` を要素でなく `Style` ごとに 1 つにする（現状 `Style` と 1:1 で、`Style` が共有されれば共有される）(4) `ComputedStyleFootprintTest` の暫定上限 9,000 B を 4,500 B へ戻す。
3. **b3 `core/layout` の付け替え**（着手: **b3-1 完了 2026-11-24** 縁の長さ〔margin・padding・border 幅・gap〕の `edgeLength` 19 箇所を `Style.boxPx`/`gridPx` へ付け替え、`BoxMeasurer` の添字定数 14 個を削除。WPT・画素一致・`RealDocumentFramesTest` 緑）。**b3-2〜b3-6 完了 2026-11-24**（`core/layout` の固定プロパティの読みを型付きへ）: ① `keywordOf` 21 箇所 → 列挙フィールド ② `length(…)` 44 箇所 → `Style.px` ③ 文字列のまま持つ値（`display`・`direction`・`white-space`・`grid-template-*`・`content`・`counter-*` ほか）72 箇所 → `typed().<群>.<フィールド>`（宣言が無ければ null。旧 `get()` と同じ。既定値付きは `Style.or`）④ 長さの宣言有無・auto・% ・内在サイズ・確定した長さの判定 約 40 箇所 → `CssLength.isDeclared/isAuto/hasPercent/isIntrinsicSize/isDefinite`（**長さの「宣言なし」は `CssLength.UNDECLARED`＝0 px とし、旧 `get()==null` / `containsKey` を保つ**。これを入れずに初期値で埋めると `max-width: none` が 0 に潰れる）⑤ `order`/`flex-grow`/`flex-shrink`/`z-index`/`aspect-ratio`/`column-*`/`caption-side` ほか。**検証で見つけた回帰 1 件**: `flex-wrap: balance` を旧文字列版は別の語として区別しており（固有サイズの判定）、列挙へ寄せて `nowrap` 扱いにすると WPT `balance-line-count-intrinsic-002` が落ちた → `FlexWrap.BALANCE` を値として持ち、折り返しの有無は従来どおり `NOWRAP` と同じに扱う。意図した挙動変更: `auto` 等の語を CSS どおり大小文字・空白を区別せず扱う。全件 `:core:test` 445 クラス緑、`memoryAndLatencyGate` OK（`varied-list-2000:open` 36.7 MB。基準 35.7）。**b3 の残り（`core/layout`、約 110 箇所）**: (1) `AnchorPositioning` 52 箇所（`anchor()` 関数を含む生の宣言文字列・`position-area`・インセットの上書き `spec.*Decl`。`CssExprs` が式の原文を持つので型付きで読める見込みだが、`left/right/top/bottom` の `auto` 以外の語の原文が要る箇所は群に生文字列の欄を足す） (2) 添字を引数で渡す補助（`FlexLayoutEngine.hasSize/isAuto(style, prop)`・`GridLayoutEngine.selfAlignment/contentDistribution/hasSize`・`TableLayoutEngine.border/joint`・`BoxMeasurer.hasBothInsets/isAutoInset`）→ 辺・軸の列挙か長さ `long` を渡す形へ変える (3) `justify-content`/`align-content` の `safe` 修飾の検出（原文が要る。列挙に生文字列の欄を併記する） (4) `vertical-align`（キーワードと長さの両方。語の atom を `CssLength` に足す）・`MulticolLayout.visible(background-color)`・`BlockLayoutEngine`/`AnchorPositioning` の `asMap()` 2 箇所（`StyleResolver.isNativeToggle` は属性とスタイルの対応を読むので、必要な値だけ型付きで渡す形へ）。 **b2-2 の見直し**: 実測で `LinkedHashMap$Entry` は 195k → 35k 個に減った（b1 の `Style.of` が要素ごとに `--*` 外の宣言表を複製していたのを、b2-1 の親共有で畳めた）ため、独立した課題ではなくなった。`Map<String,String> inheritedStyle` の置換（旧 b2-2）は、消費側の付け替え（b3〜b5）が終わり `CssValue[]` を読む者がいなくなってから行うほうが安全なので、b5 の後（M1c の直前）へ移す。 **b3 `core/layout` の付け替え**（多い順）→ **b4 `core/paint`** → **b5 `common`・`core/dom`**。付け替えごとに旧読み口の呼び出しを 0 にし、`asMap()` を使う場所は型付き走査へ。
4. 全読み口の旧参照が 0 になった時点で M1c（旧型の一括削除）。

- 群の追加・プロパティ追加は `PropertyRegistry` へ登録して `java -cp core/build/classes/java/main scripts/GenStyleGroups.java core/src/main/java/com/nishiyu/enderframelib/core/css` を再実行する（生成物は手で直さない。足し忘れは `StyleGroupCoverageTest` が検出する）。

---

### 3.3 Phase M2a: 入力側増分 `BoxTreeCompiler` 設計と縦割りスパイク関門〔完了・Go 2026-11-25〕

> **判定: Go**（判定基準 1〜5 を全て満たした。実績・設計上の判断・M2b への引き継ぎは本節末尾の「M2a 完了実績」）。以下 A・B は着手時の設計と関門の仕様で、実装で変えた点は末尾に明記した。

M2 で「未変更部分木を `boxNode == cached.boxNode` で O(1) 再利用する」ためには、DOM 変更のたびに未変更部分木の `BoxNode` を同一参照で返す**入力側増分機構（`BoxTreeCompiler`）**、長大配列のコピーを避ける**永続チャンク表現（`BoxChildren`）**、さらに **`ConstraintSpace` の比較契約**と **intrinsic size・入れ子 Flex の 2 パス測定を破綻なく収容する 1 層メモ化設計**が不可欠である。全面移植（M2b）へ踏み切る前に、これらを設計し、実 DOM + M1 実 `Style` 上での縦割りスパイク（Block + Text + 入れ子 Flex）で実測検証する。

#### A. 入力側増分 `BoxTreeCompiler` と 1 層 `LayoutCache` の設計

1. **どの DOM ノードが汚れたかをどう知るか（`ChangeLedger` を唯一の入口として維持）**
   - 既存の追記専用台帳 **`ChangeLedger`**（`core/dom/node/ChangeLedger.java`・159 行）は削除せず維持し、フレーム先頭で `freeze()` する。DOM ノードへの `DIRTY_*` 欄追加や祖先への持ち越しフラグは作らない。
   - 凍結世代で `ledger.touches(node)`（変更ノード群から祖先へ辿った `IdentityHashMap` 集合、構築コスト `O(変更数 × 深さ)`）と親ごとの変更範囲を用い、変更の無い部分木は走査を打ち切る。
   - `frozenOverflow()` または `frozenNodeless()`（`ENV`・スタイルシート変更・全体再スタイル）時は、全木再コンパイルへフォールバックする。
   - **非局所依存の閉じ込め**: 兄弟セレクタ（`:nth-child` / `+` / `~`）・番号付き `list-item`・`counter` / `quotes`・コンテナクエリを使う親または文書では、影響する後続兄弟または親部分木を dirty 範囲へ広げる単一規則を `BoxTreeCompiler` 内に閉じる。

2. **継承スタイル変更時の再構築数と伝播打ち切り**
   - 1 ビュー（`EngineView`）に前回ビルドの対応表 `IdentityHashMap<HtmlNode, CompiledEntry>`（または `nodeId` 索引表。`CompiledEntry` は `{BoxNode box, InheritedStyle parentInherited, CustomProperties parentCustom}` のみを持つ軽量レコード）を 1 本だけ保持し、DOM から外れたノードは再コンパイル時に回収する。
   - ノード `n` のコンパイル時は次の 3 分岐で処理する：
     - **O(1) 完全スキップ**: `!ledger.touches(n)` かつ `parentInherited == entry.parentInherited` かつ `parentCustom == entry.parentCustom` ならば、部分木を一切辿らず **`entry.box` をそのまま同一参照（`==`）で返す**。
     - **継承不変による伝播打ち切り**: `n` 自体は dirty でスタイルを再解決したが、結果の `newStyle.inherited.equals(entry.box.style.inherited)` かつ `newCustom` も等価である場合（非継承プロパティ変更、または `n` が継承値を上書きして下流変化を遮断した場合）は、子へ前回と同じ `InheritedStyle` 参照を渡す。その結果、**未変更の子孫ノードは O(1) 完全スキップに落ち、1 個も作り直さない**。
     - **実際に継承値が子孫へ変化する場合**: 変化した継承値を受け取る子孫の `BoxNode` だけを再構築する（CSS 仕様上必然のコストであり、M3b の `DisplayScene` 化と Track P2 の並列化で吸収する）。1 件挿入・削除や非継承変更では、祖先チェーン（深さ 5〜15 ノード）のみが再構築される。

3. **8,000 子の `children[]` コピーコストの解消（不変チャンク列 `BoxChildren`）**
   - `BoxNode` の子列を単一のフラット配列 `BoxNode[]` ではなく、不変の **`BoxChildren`（分岐数 32 の不変 `BoxChunk` による浅いチャンク列）**として表現する。
     - 子数 ≤ 32 の通常要素は単一配列のまま保持し、メモリ・間接参照の追加コストをゼロにする。
     - 8,000 子の親へ 1 件挿入・削除した場合：変更位置を含む 1〜2 個の `BoxChunk`（最大 32〜64 参照）と、上位の `BoxChunk[]` 配列（`8,000 / 32 = 250` 要素、約 1 KB の `System.arraycopy`・数 µs 未満）のみを作り直し、未変更の 249 個の **`BoxChunk` は参照同一性（`==`）ごとそのまま共有**する。
   - **下流 `LayoutEngine` / `LayoutCache` とのチャンク境界一致**:
     - レイアウト出力側の `ChunkedFlow`（32 子ごとの不変 `FlowChunk`、チャンク累積高さを保持）とチャンク境界を揃えることで、`(BoxChunk, ConstraintSpace) → FlowChunk` をチャンク単位で `==`（`BoxChunk`）＋ `ConstraintSpace` 照合により O(1) キャッシュヒットさせる。これにより 8,000 子でも子 1 個ずつの照会（旧 1 µs/子）をゼロにし、**`O(N / 32 + 変更チャンク内 32)`** で位置・高さを確定する。
     - チャンク境界を跨ぐ margin collapsing は、各 `FlowChunk` の入力鍵（`incomingMarginStrut`）と出力（`outgoingMarginStrut`）に margin strut 情報を含めて正確に解決する。
     - 匿名ボックス（インライン/ブロック混在・テーブル補完等）を必要とする親は、対象親の dirty チャンク範囲または親単位で再生成する。

4. **`ConstraintSpace` の比較方式と intrinsic size・Flex/Grid 多段測定（2 パス）のメモ化設計**
   - **`ConstraintSpace` に素の `==` が使えない理由と 2 つの対策**:
     - `ConstraintSpace`（確定寸法・利用可能幅・`ExclusionSpace`・`MarginStrut`・% 解決基準等）はレイアウト呼び出しのたびに新しく組み立てられる値オブジェクトである。`BoxNode` のように前回参照を引き継ぐ木ではないため、素の参照同一性（`==`）だけで比較するとキャッシュが 1 度もヒットしない。
     - 本設計では次の 2 案のいずれかを採り、**M2a スパイクでヒット率・アロケーション・実行時間を実測比較して確定**する：
       - **案 A（構造入力 `==` + `ConstraintSpace` 値等価 `equals`）**: まず `BoxNode` / `BoxChunk` を `IdentityHashMap`（または `nodeId` 索引配列）で O(1) 引きし、ヒットした少数スロットに対してのみ `cachedSpace == space || cachedSpace.equals(space)` で照合する（`ExclusionSpace.EMPTY` 等を静的共有し、`equals` は高速なフィールド比較）。
       - **案 B（ビュー内インターン `ConstraintSpacePool`）**: 1 ビュー内の小さなリング/ハッシュ表（または共通値定数の静的シングルトン）で `ConstraintSpace` をインターンし、同じ値なら同一参照を返すことで下流の比較を完全な `==` に統一する。ただしインターン表の探索・保持コストが案 A のフィールド `equals` を上回るかをスパイクで検証する。
   - **intrinsic size（`min-content` / `max-content`）の独立キャッシュ**:
     - 旧 `MeasureMemo` が複雑化した主因は、レイアウト結果と intrinsic size 測定・仮測定が 1 つの可変構造体に混在した点にある。新設計では `FormattingEngine` に純粋関数 `MinMaxSizes computeMinMaxSizes(BoxNode node, MinMaxContext ctx, LayoutSession session)` を分離し、`LayoutCache` 内に **`(BoxNode[==], MinMaxContext) -> MinMaxSizes`** の 1 層メモを独立して持つ。`MinMaxContext` は利用可能幅（非依存時は `NONE`）とルート書字方向・親フォント解像基準のみから成り、Flex の柔軟長決定や親の縮小幅変更で再計算が走らない。
   - **Flex / Grid の 2 パス・多段測定向け固定少数スロット（`TentativeSlots` の単純置換）**:
     - Flex や Grid が同一の子を「仮測定（pass 1: shrink-to-fit / intrinsic 寄与）」と「確定測定（pass 2: stretch / 割り当て確定幅）」の異なる `ConstraintSpace` で複数回測る場合、1 要素 1 エントリしか持たないと pass 1 と pass 2 が互いにキャッシュを追い出し、入れ子 Flex（深さ `d`）で **`O(2^d)` の指数的再測定**が起きる。
     - これを防ぐため、`LayoutCache` の各 `BoxNode` エントリ（`IdentityHashMap` / `nodeId` 配列の 1 セル）は単一の `(ConstraintSpace, LayoutFragment)` ではなく、**固定少数スロット（初期仮説として 2〜4 スロット、LRU またはリング置換。各スロットは不変 `(ConstraintSpace, LayoutFragment)` ペアのみ）** を保持する。旧 `MeasureMemo.TentativeSlots` のような「可変箱フィールドの退避・復元・settle」は不要であり、出力の不変性と 1 層メモ化の原則を崩さずに 2 パス測定を吸収する。
     - **スロット数に関する仮定と逃げ道**: 親が 2 通りの制約で測られ、その各回で子を 2 パス測ると、子が見る制約は最大 4 通りになり、入れ子が深くなると 2〜4 スロットでは実効的に不足して相互駆逐が起きる可能性がある。そのため M2a スパイクの判定基準 2 で再測定の増加・スロット相互駆逐が検出された場合は、**① スロット上限の拡大（例：4〜8 スロット、または 1 フレーム限りのパス内一時キャッシュ併用）** または **② 非依存フィールドを落とした `ConstraintSpace` キーの粗粒化（確定寸法一致時の不要制約除外・fit-content 充足時の利用可能幅正規化等）** のいずれかを選んで設計を修正する。

#### B. 縦割りスパイク仕様（M2b 着手前の Go / No-Go 関門）

- **スコープ**: `BoxNode`・`BoxChildren`（32 子チャンク）・`BoxTreeCompiler`・`LayoutFragment`・`ChunkedFlow`・`LayoutCache`（固定少数スロット＋`MinMaxSizes`）を用い、以下を対象とする：
  1. **Block フロー＋テキスト整形＋margin collapsing**（8,000 行リスト挿入・削除）
  2. **入れ子 Flex（row / `flex-wrap` / `flex-grow` / `min-content` 問い合わせを伴う 2 パス測定、深さ 3〜4 段の Flex-in-Flex）**
  （※Grid / Table / float / 縦書きは M2b 対象）
- **入力条件と実装形態**: 既存の旧 `LayoutBox` 経路を壊さない独立ハーネスとして実装するが、入力 DOM・スタイルにはダミー型ではなく **実 DOM（`HtmlNode` + 現行 `DocumentArena`）と M1 完了済みの実 `Style` を直接接続**して計測する（DOM 側ヒープの実態を含めて評価するため）。
- **実測と Go / No-Go 判定基準**:
  1. **`large-list-8000` 1 件挿入・削除**（先頭・中央・末尾）：交互 A/B 中央値 **`≤ 1.0 ms`**（500 行は **`≤ 0.15 ms`**）。
  2. **入れ子 Flex 2 パス測定の計算量**: 深さ 3〜4 段の Flex-in-Flex における幅変更・末端変更で `O(2^d)` の指数的再測定が発生しないこと（変更ノードあたり `computeMinMaxSizes` 呼び出し `≤ 1 回`、固定スロット内で pass 1 と pass 2 が相互駆逐されないこと）。スロット不足による再測定を検出した場合は No-Go とし、スロット数拡大または `ConstraintSpace` キー粗粒化へ切り替える。
  3. **`ConstraintSpace` 比較方式の実測確定**: 案 A（構造 `==` + `ConstraintSpace` 値等価 `equals`）と案 B（1 ビュー内インターン）のキャッシュヒット率・アロケーション・実行時間を比較測定し、M2b の採用方式を確定すること。
  4. **保持ヒープ（DOM ベースラインとレイアウト層増分の分離計測）**: 実 DOM + M1 `Style` 上での **レイアウト層増分（`BoxNode` + `BoxChildren` + `LayoutFragment` + `ChunkedFlow` + `LayoutCache`）** が **`large-list-2000 ≤ 1.5 MB`**・**`varied-list-2000 ≤ 4.0 MB`** であること。あわせて実 DOM + M1 `Style` 込みの全体保持ヒープを実測記録し、M5（`DocumentArena` 二重同期解消・`HtmlNode` 整理）までに削るべき DOM 側占有量を可視化する。
  5. **再生成の局所性と出力等価性**: 8,000 子への 1 件挿入で作り直す `BoxChunk` `≤ 2 個`、再構築 `BoxNode` 数 = 祖先深さ + 1、未変更 `FlowChunk` ヒット率 `≥ 99%`。ゼロから全構築した結果（`FrameOracle` 相当）と座標・寸法・折り返しが 100% 一致すること。
- **No-Go 時の規則**: 上記の 1 つでも基準未達の場合は **M2b（全面移植）へ着手しない**。チャンク粒度・`ChangeLedger.touches` 構築・`ConstraintSpace` キー粗粒化／スロット上限拡大・`MinMaxSizes` キャッシュ・margin strut 処理等のボトルネックを実測で特定し、全面着手の前に設計を修正する。

#### C. M2a 完了実績（2026-11-25・判定 Go）

**実装（旧 `LayoutBox` 経路には一切触れない独立の新経路。全クラス ≤ 600 行、計 約 2,360 行）**

| 層 | パッケージ | クラス |
| :--- | :--- | :--- |
| L2 入力 | `core/box/` | `BoxNode`（5 欄・不変）・`BoxKind`・`BoxChildren`／`BoxChunk`（可変長 1〜64 のチャンク列。≤ 32 子は `BoxChunk` そのもの）・`BoxTreeCompiler`（284 行）・`StyleSource`（M1 のカスケードをそのまま呼ぶ） |
| L3 整形 | `core/layout/fragment/` | `ConstraintSpace`（値等価）・`LayoutFragment`（不変）・`LineFragment`・`FlowChunk`／`ChunkedFlow`・`MinMaxSizes`／`MinMaxContext`・`LayoutCache`（1 層メモ＋固定 4 スロット）・`LayoutSession`・`FormattingEngine` SPI・`BlockFlow`・`InlineFlow`・`FlexFlow`・`BoxGeometry`・`FragmentLayout`（入口） |
| 検証 | テスト | `BoxTreeCompilerTest`（8）・`IncrementalOracleTest`（4 文書 × 80 変異）・`NestedFlexTest`（2）・`LegacyParityTest`（5）・`M2aSpikeGateTest`（ヒープ＝`slow`・時間＝`timing`）・`StyleSharingTest#shareEqualGroupsReusesPreviousInstances`、実測 `./gradlew :core:m2aSpike`（`M2aSpikeMain`） |

**判定基準ごとの実測**

1. **1 件挿入・削除**（`:core:m2aSpike`。同じ JVM で旧経路と v3 を交互に 3 回ずつ、回ごとの中央値の中央値。旧経路は `StandaloneView` の 1 フレーム〔表示リストの構築まで〕、v3 は DOM 変更＋台帳の凍結＋コンパイル＋整形〔描画は M3b で載る〕）:

   | 文書 | 先頭 | 中央 | 末尾 | 基準 |
   | :--- | :---: | :---: | :---: | :---: |
   | `large-list-8000` 旧経路 | 5.91 ms | 4.71 ms | 4.29 ms | — |
   | `large-list-8000` v3 | **0.161 ms**（compile 0.067 + layout 0.023） | **0.122 ms** | **0.106 ms** | ≤ 1.0 ms ✔ |
   | （再実行） 旧経路 / v3 | 5.98 / 0.159 ms | 4.67 / 0.120 ms | 4.30 / 0.110 ms | — |
   | `large-list-500` 旧経路 / v3 | 0.295 / **0.020 ms** | 0.162 / **0.020 ms** | 0.146 / **0.017 ms** | ≤ 0.15 ms ✔ |

   v3 の残り（約 0.07 ms）は DOM 側の `insertBefore`（`ChildList.indexOf` の O(n)）で、M5 の DOM 整理の対象。
2. **入れ子 Flex の 2 パス**（`NestedFlexTest`。row と column を交互に深さ 3〜8、葉の高さを経路ごとに不ぞろいにして各段で stretch・`flex-grow` の pass 2 を連鎖させた文書）: 初回・幅の変更ともに整形は箱あたり **≤ 2 回**（深さ 8 で 578 回 / 514 箱 ≈ 1.12）・スロットの追い出し **0**・幅の変更で `computeMinMaxSizes` の再計算 **0**・葉の変更で内在サイズの再計算は祖先の数まで（変わった箱あたり ≤ 1 回）・変更のない次フレームは根で当たり整形 0 回。**変異検証**: スロット数を 1 にすると pass 1 と pass 2 が追い出し合い、テストが落ちることを確認した。スロット数 4（初期仮説）で不足は検出されず、拡大は不要。
3. **`ConstraintSpace` の比較方式**（ABBA 順で 4 回。案 B は `LayoutSession` に同値の制約を同じ参照へそろえる表を足して計測した）:

   | 文書 | 案 A 挿入 / 幅の往復 | 案 B 挿入 / 幅の往復 | ヒット率・確保量 |
   | :--- | :---: | :---: | :--- |
   | `varied-list-2000` | **0.063 ms** / 3.62 ms | 0.070 ms / 3.61 ms | 同一（0.8995・32.8 KB / 6.8 MB） |
   | 入れ子 Flex 深さ 7 | **0.0229 ms** / 0.157 ms | 0.0241 ms / 0.160 ms | 同一 |

   → **案 A（`==` の高速路つき値等価）を採用**。案 B はヒット率も確保量も同じ（照合の意味が同じで、表を引く前に制約を作る）で、表の探索の分だけ遅い。案 B の経路は運用ルール 7 に従い M2a 内で削除した。
4. **保持ヒープ**（`M2aSpikeMain.heapOf`。DOM のみ → M1 の `Style` だけを全要素ぶん作って保持 → それを捨てて `BoxNode` 木 → 断片木、の順に GC 後の差分を取る）: 下表（最終の実行。実行ごとの揺れは ±0.1〜0.2 MB で、初回の実行は 0.64 / 2.11 MB）。**レイアウト層増分 `large-list-2000` 0.54 MB（≤ 1.5 ✔）・`varied-list-2000` 1.87 MB（≤ 4.0 ✔）**。同じ文書の旧経路の全体保持は 9.63 / 35.96 MB（`memoryAndLatencyGate`）。

   | 文書 | DOM | `Style` | スタイル解決の副産物 | `BoxNode` 木 | 断片木 | レイアウト層増分 | 全体 |
   | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
   | `large-list-2000` | 1.05 | 1.48 | 0.01 | 0.21 | 0.33 | **0.54** | 3.09 |
   | `varied-list-2000` | 2.79 | 2.97 | 3.47 | 0.45 | 1.42 | **1.87** | 11.10 |

   「スタイル解決の副産物」は M1 のカスケードが節に残す覚え（`style` 属性の宣言の解析結果など。`varied-list` は全 `li` が `style` 属性を持つので 3.47 MB）で、M5（DOM 整理）で削る DOM 側の占有として可視化した。
5. **局所性と出力等価性**: 8,000 子への 1 件挿入で作り直す `BoxNode` は **7 個**（根・`html`・`body`・`div`・`ul` の 5 段 ＋ 新しい `li` と文字）、削除は **5 個**、作り直す `BoxChunk` は **1 個**（≤ 2 ✔）・前回と同じ参照のチャンク **249 / 250**・一覧の `FlowChunk` は変わった 1 個を除く **249 / 250 が `(BoxChunk ==, 制約)` で当たる（未変更チャンクのヒット率 100%）**。`IncrementalOracleTest`（`large-list`・`varied-list`・入れ子 Flex 深さ 3/4 に種固定のランダム変異〔挿入・削除・文字・非継承／継承のスタイル・幅・`display`〕を 80 回ずつ）で、ゼロから作った結果と全断片の座標・寸法・行の折り返しが **100% 一致**。**変異検証**: ① チャンクの照合で「触れられた子」を見ない ② 断片の照合で制約を見ない、のどちらも検出して落ちることを確認した。

**旧経路との一致（新経路が「本物」の結果を出すことの確認。`LegacyParityTest`）**: 同じ実 DOM・実 `Style` を旧経路（`LayoutBox`）と v3 に通し、`id` を持つ全ての箱の位置・寸法が **1/64 px 以内で一致**（`large-list`・余白の相殺／`box-sizing`／auto 余白／%・min/max の各ブロック・`white-space: pre / nowrap`・`line-height`・`text-align` を含む折り返し・flex の row／wrap／column／`align-items`／縮小と自動最小サイズ／`gap`＋`space-between`）。意図した差は 2 つ: ① **負の余白が親の下端を素通しして次の兄弟と相殺される場面**は、v3 が CSS の規則どおり「正の最大 ＋ 負の最小」（例: −3・10・30 → 27 px）で、旧経路は負の余白を落として 30 px（旧経路の逸脱。M2b で WPT と照合する） ② インライン要素は行の断片へ平らに入るので箱の断片を持たない。

**実装で設計（A・B）から変えた点と、その理由**

- **`BoxNode` は `int nodeId` ではなく DOM の節への参照を持つ**: `DocumentArena` の番号は節の削除で解放・再利用されるため、番号で「前回と同じ節か」を照合すると、同じ位置に入った新しい節へ古い箱を取り違える（`BoxTreeCompilerTest#replacedNodeAtSamePositionGetsFreshBox`）。M3b の `DisplayScene` が要る番号は `node.nodeId()` で引ける。
- **`IdentityHashMap<HtmlNode, CompiledEntry>` を持たない**: 前回の `BoxNode` 木と DOM を並走させて照合する（前回の箱が親の箱の子として見つかる）ので、対応表の分のヒープはゼロ。O(1) 完全スキップの条件（`parentInherited ==` 等）は「親の継承群・カスタムプロパティの参照が前回と同じ」として親ごとに 1 回だけ判定する。
- **チャンクは可変長（1〜64。挿入で伸び、64 を超えたら 32 ずつに割る）**: 境界を 32 ごとに固定すると、挿入位置より後ろの全チャンクの境界がずれて作り直しになる。チャンクの中身の事実（インライン水準・ブロック水準の子を含むか）は構築時に 1 回だけ求めて持つ（8,000 子の親で子を毎回走査しない）。
- **レイアウトのキャッシュは「前回の断片木そのもの」**（`IdentityHashMap<BoxNode, スロット>` を持たない）: 断片は自分の `BoxNode` を参照するので、`BoxNode` を鍵にした表では値が鍵を生かし続け、弱参照の表でも回収されない（掃除の仕組みが要る）。親の前回の断片が子の断片を持つので、親が子を測るときはそこから候補を引いて `box == 子 && space.equals(制約)` で照合する。木から外れた結果は木と一緒に回収される。
- **2 パスの固定少数スロットは親の出力側（`FlowChunk.slots`）に持つ**: 1 回の整形の間に項目を測った断片（最大 4・LRU）だけを残す。断片自身にスロットを持たせると「前回の断片 → その前回…」の鎖になって保持が際限なく伸びる。
- **`MinMaxSizes` は `BoxNode` を弱参照の鍵にした表**（値が鍵を参照しないので回収される。`BoxNode` は `equals` を持たないので同一性で引ける）。`computeMinMaxSizes` は**内容に基づく内在サイズ**（自分の `width`・min/max を掛けない）を返し、指定の幅を掛けた「親への寄与」は呼び出し側（`BoxGeometry.contribution`）が作る（flex の自動最小サイズは内容の `min-content` が要るため。混ぜると `width:80px` の項目の自動最小が 80 px になる不具合を `LegacyParityTest` で見つけて直した）。
- **キーの粗粒化を最初から入れた**: 子の制約の `% の高さの基準` は、子自身が % の高さを持つときだけ載せる。載せないと、親が pass 1（高さ未確定）と pass 2（stretch で確定）で測られるたびに全ての子の制約が別になり、入れ子で倍々に増える。
- **非局所依存の規則（`BoxTreeCompiler` に閉じる）**: 兄弟の位置を読むセレクタ（`+ ~ :nth-* :empty`）を使う文書は、子の増減があった親の子を全員解き直す／`:has()`・コンテナクエリ・台帳あふれ・節を絞れない変更（`ENV`・全体の `STYLE`）は全体を作り直す（`SCROLL` は箱の木に効かないので無視）／`inherit` キーワードを使う文書は、親の算出スタイルが変わったら子の**要素**を解き直す（文字の節のスタイルは親の継承群だけで決まるので解き直さない）。旧経路の無効化（`InteractionState.markDirty`）が文字の節にも `STYLE` を追記するが、文字の節への `STYLE` は解き直しの理由にしない。
- **`Style.shareEqualGroups(prev)` を `core/css/Style` に追加**: 解き直した結果の群が前回と同値なら前回のインスタンスへ寄せる（全体が同値なら前回そのもの）。`color` を上書きする要素の祖先で `color` が変わっても、その要素の継承群は前回と同じ参照のままになり、子孫は 1 個も作り直さない（`BoxTreeCompilerTest#inheritedOverrideCutsPropagation`）。

**スパイクの範囲外（M2b で `FormattingEngine` へ移すときに足す）**: float・clearance・positioned・multicol・grid・table・縦書き・`inline-block` の原子化・`vertical-align`・双方向・ブロックを含むインライン・`::before/::after`／`::marker`（番号つきリスト・カウンタ・引用符）・匿名の表の箱・`content-visibility`・flex の `*-reverse`／column の折り返し／`align-content`／主軸の auto 余白／ベースライン揃え・スクロールコンテナの `scrollW/H`。混在（インラインとブロック）の子を持つ親はチャンク単位では使い回さない（子ごとには照合する）。

**M2b への引き継ぎ（実測で見えた課題）**

- **幅の変更は全ての行を測り直す**: `varied-list-2000` の幅の往復は 1 フレーム 3.6 ms・確保 6.8 MB（語ごとの `substring` と `advance`、`Piece` の確保）。ブロックの子は 1 スロットなので、幅を往復すると毎回外れる。`InlineFlow` の語の幅の覚え（`(文字の BoxNode ==) → 語の幅の列`。幅に依らない）を 1 層メモとして足すのが第一候補（KPI の `showcase` 幅変更 ≤ 0.20 ms に効く）。
- **8,000 子の親の DOM 側**: `ChildList.indexOf` の O(n) が v3 の 1 件挿入の約半分を占める（M5）。
- **`StyleSource` のルート要素の基準**（`rem` / `rlh`）は 1 ビューの実例に閉じて持つが、`html` 要素だけが解き直されないフレームでも正しいのは「`html` の継承群が変われば全子孫が解き直される」ことに依る。M2b で `CompileCtx` 相当に明示するか検討する。

---

### 3.4 Phase M2b: `LayoutBox` 解体と全 `FormattingEngine` 移植・不変 `LayoutFragment` 本実装

M2a の縦割りスパイク合格（**2026-11-25 Go。§3.3 C**）を前提として、`LayoutBox`（1,785 行・81 欄）およびそれに群がる多層メモ・印・読み取り台帳群（約 4,500 行超）を解体し、全フォーマッティングエンジンを `FormattingEngine` SPI へ段階移植して旧経路を完全削除する。

- **新データモデル（`core/layout/`）**（M2a で `core/box/`・`core/layout/fragment/` に実装済みの型を土台にする。下のコードは着手時の構想で、実装との差 ―― `BoxNode` は `nodeId` でなく DOM の節の参照を持つ・キャッシュは前回の断片木と `FlowChunk.slots`・`MinMaxSizes` は弱参照の表 ―― は §3.3 C のとおり）
  ```java
  // 1. 入力ボックス木（可変状態を一切持たない軽量記述ノード、≤ 10 欄）
  public final class BoxNode {
      public final int nodeId;           // DOM ノード番号（匿名箱・疑似要素も専用番号帯）
      public final Style style;          // Phase M1 の不変スタイル（InheritedStyle 参照共有済み）
      public final Display display;
      public final String text;          // TEXT ノードのみ非 null
      public final BoxChildren children; // 32 子チャンクの不変列（≤ 32 子は単一配列、二重リスト廃止）
      public final PseudoStyles pseudos; // ::marker / ::placeholder / ::first-line（無ければ null）
  }

  // 2. 不変レイアウト出力（完成後は一切書き換えない）
  public final class LayoutFragment {
      public final BoxNode box;
      public final float width, height;
      public final Edges margin, padding, border; // 解決済みピクセル値
      public final float baseline;
      public final float scrollW, scrollH;
      public final ChunkedFlow children;          // 親相対座標 (x, y) と子の LayoutFragment（32 子チャンク累積和フロー）
      public final LineFragment[] lines;          // インライン整形結果（無ければ EMPTY）
      public final OutOfFlowData outOfFlow;       // absolute/fixed/float 情報（無ければ null）
  }

  // 3. 整形コンテキスト SPI（完全にステートレス）
  public interface FormattingEngine {
      LayoutFragment layout(BoxNode node, ConstraintSpace space, LayoutSession session);
      MinMaxSizes computeMinMaxSizes(BoxNode node, MinMaxContext ctx, LayoutSession session);
  }
  ```
  - **逆流書き込みの完全根絶**: `overrideWidth` / `overrideHeight` / `floatSpace` / `collapsedBorders` / `columnShifts` 等を箱へ書き込む設計を全廃し、`ConstraintSpace`（確定寸法・float 除外領域・テーブル列幅・multicol 情報・margin strut）としてトップダウン引数で明示的に渡す。
  - **float 除外領域によるキャッシュヒット率低下リスクへの対策**:
    1. **非侵入の高速パス（`ExclusionSpace.EMPTY` 共有）**: float が存在しない BFC（大半のノード）、全 float の下端を通過した後の子、および新しい BFC を確立する子（`overflow != visible`・`flow-root`・Flex・Grid・Table 等）には、常に静的シングルトン `ExclusionSpace.EMPTY`（親の BFC 内 Y オフセットを載せない）を渡す。これにより親の Y 変化や上方の無関係な float で通常兄弟のキャッシュが巻き添えミスするのを防ぐ。
    2. **float 侵入帯域の子のみ相対座標化（または限定キャッシュ）**: 実際に float が子の Y 帯域へ交差する子に限り、その子の上端を基準とした相対座標の `RelativeExclusions` を `ConstraintSpace` に載せる（連続侵入が複雑な要素は侵入子のみ非キャッシュに倒してもよく、非侵入兄弟 95%以上のキャッシュヒット率を優先して守る）。
  - **単一 1 層メモ化 `LayoutCache`**: 1 ビュー（`EngineView`）に閉じた `(BoxNode / BoxChunk, ConstraintSpace) → LayoutFragment / FlowChunk` および `(BoxNode, MinMaxContext) → MinMaxSizes` キャッシュのみを持つ。構造入力（`BoxNode` / `BoxChunk`）は **`==` 参照同一性**、`ConstraintSpace` は M2a スパイクで確定した **`==` 高速パス付き値等価 `equals`**（§3.3 C）で照合し、Flex/Grid の 2 パス測定用の固定少数スロット（4 スロット。M2a 実測で不足なし）は親の出力（`FlowChunk.slots`）に持つ。
- **段階的移植と途中検証（ビッグバン回避）**
  - 全エンジンが揃うまで一度に旧経路を消すのではなく、M2a で確立した基盤の上に以下の順でエンジン単位に `FormattingEngine` SPI へ移植し、移行期間中のみ使う薄い一時アダプタ（`LayoutFragment` → 既存 `DisplayListBuilder`、M2b 完了時に必ず削除）経由で、各エンジンごとに WPT サブセット・`FrameOracle` を緑にしながら進める：
    1. Block 拡張（float・positioned・multicol）＋ Inline 整形（`LineFragment`）
    2. Flex（`FlexLayoutEngine`、898 行）
    3. Grid（`GridLayoutEngine`、1,280 行）
    4. Table（`TableLayoutEngine`、835 行）
    5. Vertical（`VerticalWritingEngine`）
  - 全エンジンが WPT 160/260 以上・`FrameOracle` 100% 一致に達した時点で、旧コード群と一時アダプタを一括削除する。
- **削除対象（計 約 4,500 行超を完全削除 ※`ChangeLedger.java` は維持）**
  - `LayoutBox.java`（1,785 行・81 欄）
  - `MeasureMemo.java`（799 行・41 欄・17 フラグ）
  - `PlacementMemo.java`（342 行・11 欄）
  - `PlacementDeps.java`（252 行）
  - `LayoutReads.java`（619 行）・`MeasureReads.java`（158 行）・`ReadKind.java`（113 行）
  - `PaintStamps.java`（249 行）
  - `PartialBuild.java`（167 行）・`ChildWindow.java`（47 行）
  - `ChangeReads.java`（62 行）
  - `RenderPipeline.java` / `RenderTreeBuilder.java` 内の旧多段分岐（`relayoutRootOnly` / `relayoutInPlace` / `repositionLayout` / `LocalPatches` / `InPlaceRelayout`）と移行用一時アダプタ
  - レイアウト系 `Shortcut` スイッチ群（`MeasureMemo` / `LayoutBox` / `Flex` / `Block` / `Intrinsic` 関連）およびプロセスグローバルエポック全廃（`SHAPE_EPOCH`, `STRUCT_EPOCH`, `CONTAINER_EPOCH`, `PAINT_EPOCH`, `renderBoundary`, `MEASUREMENT_EPOCH`）
- **完了ゲート**:
  - 削除コード：`≥ 4,500 行`、グローバルエポック：`0`、一時アダプタ残存：`0`、レイアウト系 `Shortcut`：`0`。
  - **保持ヒープ（レイアウト層増分と中間全体）**: レイアウト層増分（`BoxNode` + `LayoutFragment` + `LayoutCache`）は `varied-list-2000 ≤ 4.0 MB`・`large-list-2000 ≤ 1.5 MB`。全体保持ヒープ（M3b 未撤去の旧描画層・M5 未整理の DOM 二重構造を含む中間値）は `varied-list-2000 ≤ 14.0 MB`・`large-list-2000 ≤ 5.5 MB`（※最終全体目標 `≤ 12.0 MB` / `≤ 4.5 MB` は M3b〜M5 で達成）。
  - 8,000 行リスト 1 件挿入：`7.89 ms → ≤ 1.0 ms`（500 行は `≤ 0.15 ms`）。
  - `FrameOracle`（最初から作った結果との全比較）100% 一致、WPT 160/260 以上維持。

#### 3.4.1 M2b の分割と進捗（2026-11-25 着手時の棚卸しで分割）

着手時の実測規模: `LayoutBox` を参照する本体は **77 ファイル・約 3.2 万行**（`core/layout` 39・`core/paint` 17・`common` 13・`core/kernel` 4・`core/css` 2・その他 2）、テスト **100 ファイル**、MOD 3 ファイル。描画（`core/paint`）が `LayoutBox` から読むのは主に幾何（`x()/y()/w()/h()`・枠線・パディング・`scrollWidth/Height`）・`node`・`style`・`inlineFragments`・`children`/`parent`・`display()`・位置指定と sticky の判定・`firstLineStyle`/`placeholderStyle`・描画の印（`ownPaintStamp`）。M1 と同じく、一度に旧経路の削除まで進めると全ゲートが長く赤になるので、**同一フェーズ（M2b）内の段**に分け、段ごとに「テスト緑」を保つ。旧型の削除は最後の段に集約する（運用ルール 7 の「同一フェーズ内で削除」はそこで満たす）。

| 段 | 内容 | 完了条件 | 状態 |
| :--- | :--- | :--- | :--- |
| **M2b-0** | **一時アダプタと新経路の検証の足場**: 断片木 → 描画用の `LayoutBox` 木への射影（`core/layout/FragmentProjection`。幾何・縁・行の文字片〔語ごと〕を写すだけで、旧エンジンの測定は呼ばない）と、WPT を新経路で回す `WptV3BaselineTest`（合格一覧 `reftest-passing-v3.txt` をラチェットで固定。`-Dwpt.v3.update=true` で締める）。ビュー単位の経路の選択は、ハーネスが新経路を直接組めば足りたので M2b-6 へ回した | 旧経路の WPT ≥ 160/260 不変・新経路の合格数を記録・射影の往復（M2a の文書で旧経路と画素一致） | **完了 2026-11-25**: 新経路 **65/260**（flexbox 29・grid 23・text 13。旧経路 160）、旧経路だけ合格 118・新経路だけ合格 11。`ProjectionPixelParityTest`（ブロック・折り返し・flex の 3 文書で旧経路と**画素一致**）。射影で見つけた新経路の不具合 1 件（ルート要素〔`html`〕を独立した整形コンテキストにしておらず、子の余白と相殺していた）を直した。最初の計数 72 は文字片が描かれず、テストと参照が同じく文字を欠いて一致した水増しだった（`BLANK` 29 → 0） |
| **M2b-1** | Block・インラインと入力側の完成: 位置指定・float（除外領域を兄弟の箱へ渡す〔`ConstraintSpace` に相対座標で載せ、侵入しない子は `EMPTY` 共有〕・float を避けて縮む BFC の箱）・インライン水準の原子・置換要素とフォーム部品（内在の大きさ）・`::before/::after/::marker`・カウンタ・引用符・`<br>`・`vertical-align`・`text-align: justify`・スクロールコンテナ（`scrollW/H`・クリップ）・sticky の静的位置・multicol・レイアウトに効くアニメーション（§3.4.2-5） | 新経路で Block/Inline 系の WPT サブセットが旧経路と同数以上・`IncrementalOracleTest` に float/positioned の変異を足して 100% 一致 | **完了 2026-11-25**（multicol だけはブロックの断片化〔1 つの要素の箱を列ごとの断片に分ける〕が要る独立した設計の仕事なので、ユーザーの判断で **M2c の後へ回した**。実文書と対象の WPT は multicol を使わない）: ① 位置指定（`OutOfFlow`・`PlacedFragment`。relative は親が置く位置のずれ、absolute/fixed は静的位置を断片に載せて包含ブロックまで上げ、包含ブロックが自分の大きさを決めた後で整形する LayoutNG 型。`IncrementalOracleTest` に absolute/relative の変異を追加して 100% 一致）② 折り返し候補を UAX #14（`LineBreaker`）へ統一（空白の無い和文）③ flex の基準の大きさの修正（`flex-basis` が `content`・基準の無い % なら `height` を見ない／column の pass 1 は `ConstraintSpace.CONTENT_BLOCK_SIZE` で自分の `height` を掛けずに内容を測る）。新経路 WPT **65 → 74/260**。入れ替わり 4 件: 新経路だけの偶然の合格 3 件（テストと参照が同じく誤っていた）と、旧経路も合格の `flex-align-baseline-column-vert-rl-rtl-wrap-reverse`（absolute の箱を column の項目として積んでいた誤りが偶然正解の絵だった。縦書きは M2b-5）④ インライン水準の原子（`BoxKind.INLINE_BLOCK` / `INLINE_FLEX`。縮小して合わせた幅で独立に整形し、行の中に 1 つの塊として置く。行はベースラインより上の最大 ＋ 下の最大で組み、文字片も原子もベースラインへ揃える〔`inline-block` は最後の行・`inline-flex` は最初の行・`overflow` が visible でなければ下の余白の端〕。`LineFragment` に `baseline` と `atoms`）⑤ `text-indent`。新経路 WPT **74 → 91/260**（flexbox 35 → 52）。入れ替わり 1 件: 旧経路も合格の `flexbox-baseline-multi-line-horiz-004`（flex のベースライン揃え・`wrap-reverse`・`align-content`。原子を平らに流していた頃は偶然一致していた。M2b-2 で取り戻す）⑥ float（`Floats`。同じ容器の中で左右に積み入らなければ下へ・`clear`・同じ容器のインラインの行が避ける・BFC の根は float を含む。float・absolute/fixed はブロック水準へ blockify）と、それで見つかった不具合 3 件の修正（高さ 0 の素通しの箱の位置・column flex の主軸の大きさに `min-height`/`max-height`・column の `justify-content: left/right` は start）・内在サイズの末尾の空白の数え落とし・grid と表を BFC の根に。新経路 WPT **91 → 96/260**（入れ替わり 0）。⑦ **インライン・文字の完成**（2026-11-25）: `InlineFlow` を入口 `InlineFlow`・行組み `InlineLineBuilder`・内在サイズ `InlineMeasure`・揃え `LineAlign`・文字の走査 `TextRun` に分け（全て ≤ 600 行）、行組みと内在サイズが同じ走査で文字を割る（`letter-spacing` の差し引きも走査の幅に入れ、縮小して合わせた箱の中で折り返さない）。足したもの: タブと `tab-size`・節ごとの `white-space`（折り返しの機会は畳まれた空白ならその空白の `white-space`、要素の境目は 2 文字を UAX #14 で判定）・`pre-wrap`（後ろの空白は行末にぶら下がる）・`break-spaces`・`<wbr>`・行末の「その他の空白」（U+1680 ほか）のぶら下がり・`letter-spacing`（要素の最後の文字の後には足さない）・`text-align` の `start`/`end` と `direction`・両端揃え（語の後ろの空白と CJK の字の間、`text-justify`。片を機会ごとに割ってずらした新しい片を作り、断片は不変のまま）と `text-align-last`（強制改行の前の行を含む）・`text-transform` と描かない書式文字（ゼロ幅の空白・双方向の制御文字）の除去（入力側 `core/box/TextContent`。`BoxNode.text` に入る）・`width` の `min-content`/`max-content`/`fit-content`（`BoxGeometry.specifiedWidth(box, …)`）。直した不具合 3 件: ① 一時アダプタが中に文字を持つ `inline-block` を親の原点へ戻していた（行の先頭の原子だけ偶然正しかった。直しただけで flexbox が 10 件増えた）② 流れの箱より前の高さ 0 の素通しの箱の位置（親が余白を相殺するなら親の上端。チャンクは親に依らず使い回すので、位置は `FlowChunk.leadCount` と親の相殺の有無から `ChunkedFlow.y` が解く）③ `LineBreaker` の `keep-all` が小書きの仮名の前で割れた（旧経路と共有の規則）。新経路 WPT **96 → 120/260**（flexbox 66・grid 28・text 26。旧経路だけ合格 85 → 61）。**入れ替わり 5 件**（どれもテストと参照が同じく誤っていた偶然の合格で、今回参照の側が正しくなった）: `gap-004-rtl`・`gap-007-rtl`（rtl の flex。M2b-5）・`bidi-lines-001`（`unicode-bidi: plaintext`。M2b-5）・`grid-lanes/grid-placement/row-explicit-placement-001`（参照が `fit-content` を使う。grid-lanes は対象外）・`word-break-auto-phrase-001`（語句の区切り `auto-phrase` は新旧とも未対応で、旧経路は参照の `keep-all` も要素の境目で無視していた。M2b-5 の文字の残りへ）。検証: `InlineTextTest`（19）・`IncrementalOracleTest#inlineText`（文字の規則と文字の差し替えの変異で全再計算と 100% 一致。変異検証: 前回の文字列を使い回す誤りを入れると落ちる）・`ProjectionPixelParityTest` に原子の文書を追加（変異検証: ①の誤りを戻すと落ちる）。費用（`:core:m2aSpike`）: 8,000 行の 1 件挿入 v3 `0.12〜0.16 ms`（同 JVM の旧経路 `5.2〜6.4 ms`）・500 行 `0.017〜0.024 ms`、レイアウト層増分 `large-list-2000 0.62 MB`・`varied-list-2000 2.15 MB`（M2a の 0.54 / 1.87 MB から、断片に載る行と片が増えた分。基準 ≤ 1.5 / ≤ 4.0 は満たす）。`:core:test`（旧経路 WPT 160/260 不変）・`costGate`・`memoryAndLatencyGate` 緑。⑧ **疑似要素 `::before` / `::after`**（2026-11-25）: DOM に疑似の節を作らず `BoxNode.pseudo` の種類と持ち主の節の組で表し、`BoxNode.children` は DOM の子と 1 対 1 のまま（増分の照合を崩さない）、レイアウトが並べる `BoxNode.flow` の前後に 1 個ずつのチャンクで足す（疑似要素が無ければ `children` と同じ参照で確保 0）。持ち主を解き直したときだけ作り直し、スタイル・種類・文字が前回と同じなら前回の参照のまま（`PseudoBoxes`）。`content` の文字列・`attr()`・`counter()` / `counters()`（旧経路のアルゴリズムの移植。カウンタは文書順に積み上がる非局所の状態なので、使う文書〔UA の暗黙の `list-item` を除く〕は台帳に変更があれば全体を作り直し、`Counters` で文書順に数える）。一時アダプタの不具合 2 件を修正: flex の項目になった文字の節の文字片が容器の原点に置かれていた・blockify したインライン要素（span・疑似要素）の項目がインラインの外接矩形合わせで原点へ戻されていた（どちらも先頭の項目だけ偶然正しい）。新経路 WPT **120 → 122/260**（`flexbox-with-pseudo-elements-001`・`flexbox_item-vertical-align`）。検証: `PseudoElementTest`（10）・`IncrementalOracleTest#pseudoElements`（クラスの付け外し・`attr()` の値の変異。変異検証: 持ち主を解き直しても前回の疑似要素の箱を使い回す誤りで落ちる）・`ProjectionPixelParityTest` に flex の文字と span の項目の文書。費用: 8,000 行の 1 件挿入 `0.12〜0.20 ms`、レイアウト層増分 `large-list-2000 0.68 MB`。⑨ **`vertical-align`**（2026-11-25。`VerticalAlign` に旧経路の持ち上げ量のアルゴリズムを移植）: `sub` / `super`・長さ・%・`middle`・`text-top` / `text-bottom` は持ち上げ量（入れ子のインライン要素の分を足し合わせる）、`top` / `bottom` は行の高さが決まった後で行の上端・下端へ置く。文字片は `LineFragment.Piece.dy`（並びのベースラインの行のベースラインからのずれ）を持つ。新経路 WPT **122 → 123/260**（`align-content-wrap-004`）。検証: `LegacyParityTest#verticalAlignMatchesLegacy`（原子の位置と行の高さが旧経路と 1/64 px 以内。変異検証: 揃えを無効にすると 10 箱がずれて落ちる）・`IncrementalOracleTest` の文字の変異に `vertical-align` を追加。⑩ **`display: contents`**（2026-11-25）: `BoxKind.CONTENTS` は箱を作らず、レイアウトは `BoxChildren.toList()` で中身（疑似要素を含む `flow`）へ展開して読む。チャンクの事実（インライン・ブロック・float を含むか）は中身から引き継ぎ、含むチャンクは混在の流れで並べる（`BoxChildren` と DOM の子の 1 対 1 は保つ）。子のスタイルの継承元ではある。新経路 WPT **123 → 124/260**（`white-space-applies-to-text-001`）。検証: `DisplayContentsTest`（6。包んだ文書と包まない文書で位置が同じ・flex の項目・継承・疑似要素・付け外しの増分が全再計算と一致。変異検証: 展開をやめると落ちる）・`IncrementalOracleTest` の変異に追加。⑪ **置換要素とフォーム部品**（2026-11-25。M2b-2・M2b-3 の後に、実文書の幾何の差の主因だったので行った）: `ReplacedFlow`（`img` / `canvas` / `svg` / ホストの画像と 3D の場面 / `input` / `select` / `progress`。内在の大きさと比から箱の大きさ・幅だけ・高さだけの指定は比で他方・大きさの分からない画像は既定の正方形〔読み込み中のリモート画像は大きめ〕・部品は旧 `ElementSizing` の値。行の中では原子、`button` は中身を持つ原子）。大きさの入力（属性・`select` の選択肢の文字・`svg` の `viewBox`・`img` の候補）はスタイルに現れないので、**入力側が不変の写し `ReplacedInput` を `BoxNode.replaced` に作り**（値が前回と同じなら前回の参照）、レイアウトは生きた DOM を読まない（属性だけの変化も箱の作り直しとして届く。変異検証: 写しを前回のまま使うと `ReplacedTest`・`IncrementalOracleTest#replacedElements` が落ちる）。置換要素の台帳 `ReplacedElements` は旧 `core/layout` から新しい `core/replaced` へ移した（新経路から引くと `core/layout` との循環になり、`core/dom` へ置くと DOM が `scene3d` に依る〔`ModulePurityRatchetTest`〕ため。ホストの登録は M2b-6 の後も残る）。実文書の幾何のずれた箱: `showcase` 53 → 22・`surface_test` 36 → 6・`media_test` 86 → 10（掃引の全体 3,152 → 3,015）。新経路 WPT 152/260 は不変（WPT の文書は置換要素をほぼ使わない）。検証: `ReplacedTest`（5）・`IncrementalOracleTest#replacedElements`（属性だけ・スタイル・選択肢の文字・親の表示型の変異）。⑫ **fieldset の描画される legend**（2026-11-25。HTML §15.3.13。旧 `BoxMeasurer.legendSpill`・`ScrollExtentCalc.placeRenderedLegend` の移植 `FieldsetLegend`）: 最初の legend の子は流れ（flex・グリッドなら項目）に入らず、縮小して合わせた幅で上の枠線にまたがって置き（左右の auto 余白は余りを配る）、中身は枠線からはみ出した分だけ下がる。fieldset の表示型（ブロック・`inline-block`・flex・グリッド）に依らない。legend は置いた断片（`LayoutFragment.oof`）として持ち、一時アダプタが旧描画の枠線の切り欠きの印（`isRenderedLegend`）を付ける。あわせて行の原子の x を `LayoutUnit` の格子へ丸めた（`inline-block` と flex の項目で反エイリアスが 1 段ずれていた）。掃引の一致 331 → 333 文書（`fieldset-baseline-alignment` の組）、`showcase` の fieldset と legend は旧経路と一致（残る縦のずれは上の `summary` の高さ。旧経路は 1 行の summary を 2 行の高さにしており、仕様の根拠が無いので写さない）。検証: `FieldsetLegendTest`（3。変異検証: はみ出しを 0 にすると落ちる）。⑬ **インライン要素の行ごとの箱**（2026-11-25。旧 `LineBuilder` の開いた箱〔`OpenBox`・`BoxSpan`〕と `placeSpan` の移植）: 行組み（`InlineLineBuilder`）が開いているインライン要素の箱を積み、行ごとの分を `LineFragment.Span`（行の左端・上端基準の border-box、開始端・終了端の有無）として行に持つ。横は開始端（左の margin・border・padding）から終了端まで、縦は自分の書体の内容領域に上下の padding と border を足した範囲（CSS 2.2 §10.6.1。`vertical-align` の持ち上げと `top` / `bottom` を含む）。行をまたぐ箱は最初の行だけが開始端・最後の行だけが終了端を持ち（`box-decoration-break: slice`）、中身を置く前に折り返した箱は開始端ごと次の行へ移る。直後に閉じる箱の終了端は最後の語と一緒に行に入る。待っている畳まれる空白は箱の開始端より前へ出し（空白は箱の外の文字に属する）、続く空白は要素の境目をまたいで 1 つに畳む。余白・枠線・padding を持つ箱だけの行も行を作る（CSS 2.2 §9.4.2）。両端揃えは機会の位置を「前の空白の始まり」で持ち、箱の終了端はそれより厳密に前の機会の分だけずらす（旧経路は `</span> ` の後ろの空白の分まで箱を広げていた）。一時アダプタは行ごとの箱を要素の箱の文字でない断片にし、要素の矩形をそれらを囲む矩形にする（旧 `fitToFragments` と同じ）。**直した不具合 1 件**: 一時アダプタがインライン要素の箱に枠線・padding・余白の幅を写しておらず、新経路ではインライン要素の枠線が一切描かれなかった（背景だけ描かれた）。新経路 WPT **152 → 153/260**（`line-edge-white-space-collapse-002`）。幾何の一致の掃引はインライン要素の矩形も比べるようにし、一致は 333 → **328** 文書: 外したのは旧経路の逸脱 3 件（行末の畳まれる空白を箱に含める `line-edge-white-space-collapse-002`・`white-space-wrap-after-nowrap-001`〔CSS Text 3 §4.1.3 は行末の畳まれる空白を取り除く〕、`pre-wrap` の行末にぶら下がる保った空白を箱から外す `white-space-pre-032`）と対象外の `grid-lanes` 2 件。検証: `InlineBoxSpanTest`（8。手で求めた位置・幅・端。変異検証: 境目をまたぐ空白の畳み・中身の無い箱の持ち越しをそれぞれ外すと落ちる）・`IncrementalOracleTest` の文字の変異にインライン要素の余白・枠線・padding を追加・`ProjectionPixelParityTest` に行をまたぐ角丸の枠線と中身の無い枠線つきの箱の文書（旧経路と画素一致。変異検証: 幅を写さないと落ちる）。⑭ **`text-wrap: balance`**（2026-11-25。CSS Text 4 §6.1。旧 `FlowBuilder.balanceLines` の移植）: 2〜10 行で、float・流れの外の箱を含まない行の並びは、行数を変えずに折り返しの判定の幅をできるだけ狭めて組み直す（1px 単位まで二分探索。行の幅と揃えは元のまま。`InlineFlow.layout`）。新経路 WPT **153 → 154/260**（`text-wrap-balance-overflow-002`）。検証: `InlineTextTest#textWrapBalanceKeepsTheLineCountAndEvensTheLines`（変異検証: 組み直しを外すと落ちる）・`IncrementalOracleTest` の文字の変異に `text-wrap: balance`。⑮ **`::marker` と `details` / `summary`**（2026-11-25。旧 `BoxTreeBuilder.createMarker`・`BlockLayoutEngine.placeOutsideMarkers`・`RenderTreeBuilder` の閉じた details の移植）: リスト項目（`display: list-item`）の `::marker` を入力側 `MarkerBoxes` が作る（`BoxNode.marker`。UA の上書き `inline-block`・`white-space: pre`・`text-transform: none` は `ComputedStyle.derivedMarker`、`::marker` の規則と `content` は `GeneratedContentStyles.markerDeclared`）。`inside` は `flow` の先頭の原子、`outside` は流れに入らず `OutsideMarker` が項目の border-box の左外（右の余白の端が項目の左端）に最初の行のベースラインで揃えて置く（`LayoutFragment.oof`。項目の高さ・内在サイズ・ベースラインに寄与しない）。**番号は兄弟の順に依る非局所の値**なので、マーカーの箱に組んだ番号（`BoxNode.ordinal`）を持ち、親の子の並びを組み直したとき（子の増減・子に触れた・親を解き直した〔`start`・`reversed`〕）に `MarkerBoxes.renumber` が暗黙の `list-item` カウンタで文書順に数え直し、番号の違う項目だけ作り直す（番号に依らない `disc` などは数え直さない。チャンクの事実 `BoxChunk.hasOrdinalMarkers` で数え直しの要否を O(チャンク数) で判定）。CSS のカウンタを使う文書は従来どおり全体を作り直し `Counters` の値。閉じた `details` の `summary` 以外の子は箱を作らない（`open` の付け外しは details の解き直しとして届き、子を解き直す）。**直した不具合 1 件**: 新経路で閉じた details の中身が並んでいた（実文書 `voidwave` の掃引で検出）。実文書の幾何のずれた箱: `showcase` 22 → 2・`voidwave` 78 → 48。新経路 WPT 154/260 は不変（WPT の文書はリストをほぼ使わない）。検証: `ListMarkerTest`（5。外側・内側の位置、`start`・`reversed`・`value`、80 項目への挿入と削除・`start` の変更で最初から作った結果と一致、details の開閉。変異検証: 数え直しを外す・閉じた details を隠さないとそれぞれ落ちる）・`IncrementalOracleTest#listMarkers`（項目の挿入・削除・属性・型・位置・details の開閉の変異で全再計算と 100% 一致。最初の版の「li の `value` だけの変化は箱を変えないので数え直さない」誤りをここで検出）・`ProjectionPixelParityTest` にリストと details の文書（旧経路と画素一致）。範囲外: `list-style-image`（画像の読み込みは M2c。今は記号の型で描く）・`::marker` の外側の位置の `direction: rtl`（M2b-5）・入れ子の要素をまたぐ暗黙の `list-item` の数え方（li が ol の孫のとき。CSS のカウンタを使う文書は `Counters` で正しい）。⑯ **同じ BFC の float と兄弟の箱**（2026-11-25。CSS 2.2 §9.5。旧 `FlowBuilder` の `floatSpace` の受け渡しと BFC の箱の回避の移植。`FloatAvoid`）: 除外領域 `Floats.Space` は不変のまま、独立した整形コンテキストを作らないブロックの子へ、その子の上端より下に掛かる矩形を`ConstraintSpace.exclusions`（値等価で鍵に入る。掛からない子は `EMPTY` のままで鍵は変わらない）で相対座標で渡す。子の上端は余白の相殺で決まるので、自分の上の余白で見積もって整形し、子孫の余白が抜けて上端が違えば正しい上端で 1 回だけ整形し直す。子が置いた float は`LayoutFragment.floatsOut`（border-box 基準）で親へ返り、後ろの兄弟が避ける（ブロックだけのチャンクの流れは、子が float を返すとき（`FlowChunk.exportsFloats`）だけ混在の流れで並べ直す）。独立した整形コンテキストの根・置換要素・flex・grid は float と重ならず、幅が auto なら残り幅に縮み、入らなければ次に float が終わる所まで下がる。BFC の根は子孫の float も含んで高さを決める。幾何の一致の掃引 328 → **347** 文書（float を含む WPT の文書が旧経路と一致）。新経路 WPT 154/260 は不変。検証: `FloatIntrusionTest`（6。手で求めた行の位置・縮む幅・下がる位置。変異検証: 除外領域を渡さない・相殺後の上端で整形し直さない・子の float を受け取らない、のそれぞれで落ちる）・`IncrementalOracleTest` の文字の変異に float・`overflow: hidden`・`clear`・`flow-root`・上の余白を追加して全再計算と100% 一致。範囲外: 流れの最初の箱より前に置いた float の除外領域は、最初の箱の相殺しない上の余白の分だけ下にずれる（チャンクの原点が余白の下にあるため。float 自身の位置は `ChunkedFlow.y` で正しい）→ ㉓ で解消。行の押し下げは ㉑。⑰ **到達範囲（スクロールの範囲）**（2026-11-25。旧 `ScrollExtentCalc.updateScrollExtent` の移植 `ScrollableOverflow`）: 断片木から`{scrollWidth, scrollHeight, 子孫のはみ出し}` を求める純粋な関数（流れの子・行の原子とインライン要素の箱の margin-box、文字片の右端とベースライン ＋ descent、突き抜ける最後の子の下の余白は除く、絶対配置の子は除く、clip しない子のはみ出しは伝わる）。描画（M2c）の入力になる値で、今は一時アダプタが旧描画の `scrollWidth` などへ写す。**直した不具合 1 件**: 一時アダプタが到達範囲を箱の大きさのまま写しており、新経路のスクロールコンテナのつまみが中身の量に依らなかった。旧経路は行末の畳まれる空白（後ろに空白を含む語の断片・空白だけの文字の断片の ascent ＋ descent）も到達範囲に数える逸脱を持つ（CSS Text 3 §4.1.3）ので写さない。新経路 WPT **154 → 153/260**: **入れ替わり 1 件**（`grid-lanes/overflow/grid-lanes-overflowing-container-010`。対象外の `display: grid-lanes` のテストと、参照のスクロールバーが同じく描かれていなかった偶然の合格で、参照の側が正しくなった）。検証: `ScrollableOverflowTest`（4。手で求めた値。変異検証: clip する子のはみ出しを伝えると落ちる）・`ScrollExtentParityTest`（幾何が旧経路と一致した文書のブロックの要素 4,145 個で、新経路の到達範囲が旧経路を超えない。差は全て旧経路の行末の空白の分。縦書きは M2b-5）・`ProjectionPixelParityTest` にスクロールコンテナの文書（旧経路と画素一致。変異検証: 箱の大きさを写すと落ちる）。⑱ **`aspect-ratio` と `video` / `iframe`**（2026-11-25。CSS Sizing 4 §5.1）: auto の側の大きさを確定した側から比で移す（`BoxGeometry.aspectRatio` / `widthFromHeight` / `heightFromWidth`。`box-sizing: border-box` なら比は border-box に掛かる）。高さ auto は幅から（中身が高ければ伸びる。比で決めた高さは確定なので子の % の高さの基準になる）、幅 auto は確定した高さから、内在サイズでは確定した高さ・`min-height` を幅へ移す（`BoxGeometry.contribution`）。flex の row の項目は確定した交差軸の大きさ（指定の高さか、1 行の容器の確定した高さへの stretch）から基準の大きさを決め（§9.2 3B）、容器の内在サイズにも同じ値で寄与する。旧経路は高さを幅から移すだけで、幅への転写を持たない（旧経路の逸脱）。`video` / `iframe` は中身を描かない置換要素で既定の物体の大きさ 300 x 150（旧経路は置換要素として扱っていない）。新経路 WPT **153 → 155/260**（`aspect-ratio-intrinsic-size-010`・`flex-item-transferred-sizes-padding-content-sizing`。旧経路はどちらも不合格。幾何の一致の掃引はこの 2 文書を外し 1 文書が加わって 347 → 346）。検証: `AspectRatioTest`（5。変異検証: 比の高さ・flex の比の基準の大きさをそれぞれ外すと落ちる）・`ReplacedTest` に `video` / `iframe`。範囲外: グリッドの項目の比（列の大きさを行の大きさから解き直す §12.1 の 2 回目の解決が要る。WPT `grid-items/aspect-ratio-002`）。⑲ **引用符と sticky の静的位置**（2026-11-25）: `quotes` プロパティを登録し（`PropertyRegistry`。群と型付き API は生成物の再生成）、`open-quote` / `close-quote` / `no-open-quote` / `no-close-quote` を入れ子の深さの組で出す（`Quotes`。旧経路は何も出さない）。深さは文書順に積み上がる非局所の状態なので、CSS のカウンタを使う文書（全体を作り直す）では `Counters.quoteDepth` で文書順に数え、それ以外は祖先の `q` の数で近似する（要素ごとに開いて閉じる使い方では一致し、祖先の並びだけに依るので増分の結果は全体と同じ）。**UA の `q::before` / `q::after` は置かない**: 置くと全ての文書で疑似要素のカスケードを解くことになり、旧経路・新経路とも確保が +17〜390% に増え（`costGate` の 30 行）、8,000 行の 1 件挿入の交互 A/B も 1.31 倍になった（`memoryAndLatencyGate`）。`q` を囲むのは文書の規則（旧経路と同じく UA では囲まない）。sticky はレイアウトでは静的位置だけを出し（ずれは描画の側。§3.4.2-5）、絶対配置の子孫の包含ブロックになる（実装済みを確認）。検証: `QuotesTest`（4。入れ子の組・`quotes` の値・`none`・カウンタの文書の文書順の深さ・挿入の増分が全体と一致。変異検証: 祖先の数え上げを外すと落ちる）・`StickyPositionTest`（2）。⑳ **レイアウトに効くアニメーション**（2026-11-25。§3.4.2-5 の設計どおり）: `StyleSource.element(node, parent, before)` が前回の算出スタイル（アニメーションの値を含む。`BoxTreeCompiler` が前回の箱から渡す）と目標値で `StyleAnimator.observe` を呼び、`sample` の値（トランジションと `@keyframes`。opacity・transform・色は描画の側）を目標値の宣言に重ねて解き直す。時刻は `InteractionState.animationTimeMs`（`Clock` を注入できる）。動いている節は駆動側がフレームの始めに `StyleSource.appendAnimating` で台帳へ `STYLE` を足す（入口は台帳 1 本のまま。新しい足切りや側表は作らない）。今の駆動側はテストの `SpikeView.frame` で、ビューの経路の選択（M2c）で本番の駆動に繋ぐ。検証: `LayoutAnimationTest`（2。時刻を止めて進めた幅のトランジションの補間と終了・`@keyframes` の幅。変異検証: 駆動側の台帳への追記を外すと落ちる）。㉑ **float の下への行の押し下げと `word-spacing`**（2026-11-25）: 空の行の最初の中身（語・原子）が float の横の残り幅に入らなければ、入るか float が無くなるまで次に float が終わる所へ行を下げる（CSS 2.2 §9.5.1。旧 `LineBuilder.dropBelowFloats`）。`word-spacing` は語の区切りの文字（空白・改行しない空白）ごとに足す（CSS Text 3 §8.1。% は自分の書体の大きさが基準で % のまま継承する〔CSS Text 4〕。旧経路は持たない）。行組みと内在サイズは同じ `TextRun.Metrics` の幅を使い、`word-spacing` のある節は語ごとの片にする（一時アダプタは片を語ごとに割って書体の幅で並べ直すので）。WPT `word-spacing-percent-001` は合格のまま（以前は test と参照の両方で `word-spacing` を無視して偶然一致していたのが、両方に効いて一致するようになった）。幾何の一致の掃引はこの組 2 文書を外した（旧経路が `word-spacing` を持たない逸脱。346 → 344）。検証: `FloatIntrusionTest#lineThatDoesNotFitBesideFloatsDropsBelowThem`（変異検証: 押し下げを外すと落ちる）・`InlineTextTest#wordSpacingWidensEachWordSeparator`・`IncrementalOracleTest` の文字の変異に `word-spacing`。`memoryAndLatencyGate` の `large-list-2000:child` は 1 回だけ 1.36 倍で落ち、単独で 3 回流し直して 1.016 / 0.983 / 0.996 倍で通った（旧経路の A/B の既知の揺れ。この段は旧経路を変えていない）。㉒ **要素の境目をまたぐ `capitalize`**（2026-11-25。旧 `IntrinsicSizing.endsWithWordChar` と同じ規則）: 前の文字が語の途中（英数字）で終わっていれば、文字の節の最初の語の頭を大文字にしない（`p<b>ass</b>`）。前の兄弟を後ろから辿り、無ければ語の中で開く書式の要素（`span`・`b`・`i`・`a`・`em`）の外へ出て続ける（`TextContent.continuesWord`）。前の節の文字に依る非局所の依存なので、文書の規則が `capitalize` を使えば台帳に変更があれば全体を作り直す（カウンタと同じ扱い。`GeneratedContentStyles.usesCapitalize`）。検証: `InlineTextTest#capitalizeDoesNotStartAWordThatContinuesAcrossAnElement`（前の節の文字を変えた増分が全体と一致。変異検証: 続きの判定・全体の作り直しをそれぞれ外すと落ちる）。範囲外: `style` 属性だけで `capitalize` を指定した文書の増分（規則に現れない）。㉓ **流れの前の float の除外領域**（2026-11-25。⑯ の範囲外の解消）: 混在の流れのチャンクの原点は最初の流れの箱の相殺しない上の余白の下にあるので、流れの箱が始まる前の float はチャンク基準で `-lead`（内容ボックスの上端）から積み、断片の位置は内容ボックス基準で持つ（`ChunkedFlow.y` が読む形）。原点を 0 と見積もって外れたら、受け取った float と同じく 1 回だけ正しい原点で並べ直す。あわせて `Floats.Space.lastTop` は float が無ければ -∞（チャンク基準の負の位置へ積めるように）。検証: `FloatIntrusionTest#floatBeforeTheFirstChildWithAMarginStartsAtTheContentTop`（変異検証: 並べ直しで原点を渡さないと落ちる）。**範囲外として残したもの**: 外部の画像の大きさの解決（読み込みの完了を台帳へ流す形で M2c）・`object-fit`（描画の側）・スクロールコンテナのスクロール量（描画の側。M2c）・multicol（M2c の後）・`match-parent`（親の向きが要る。M2b-5） |
| **M2b-2** | Flex の完全版: 折り返し＋`align-content`・column の折り返し・`*-reverse`・`order` との組み合わせ・ベースライン揃え（`align-items/self: baseline`、容器のベースライン）・主軸の auto 余白・absolute の静的位置の揃え（`justify-content`/`align-self`）・`min/max/fit-content` の内在サイズの語・`aspect-ratio` | 旧経路だけ合格の flex 系（調査時 37 件）が 0・`NestedFlexTest` 緑・入れ替わりで落とした `flexbox-baseline-multi-line-horiz-004` を取り戻す | **完了 2026-11-25**（M2b-1 の範囲外の残りより先に行った。残りは M2b-1 の行のとおり）: `FlexFlow` を入口 `FlexFlow`（容器の大きさ・項目・内在サイズ）・行の解決 `FlexLines`（論理の主軸・交差軸で row / column・`*-reverse`・`wrap-reverse` を 1 つの手順に）・項目の作業域 `FlexItem` に分けた（全て ≤ 600 行）。足したもの: column の折り返し（高さ、不定なら `max-height` で列に分ける）・`align-content`（`normal`/`stretch` は余りを行へ等分。分配と safe・`wrap-reverse` の裏返し）・`*-reverse`・ベースライン揃え（上と下を別に数える行の高さ）と容器のベースライン（`LayoutFragment.baseline`。最初の行のベースライン揃えの項目、無ければ最初の項目。`inline-flex` の原子・入れ子の flex の項目が読む）・主軸と交差軸の auto 余白（`justify-content`・揃えより先に余りを吸う）・`justify-content` の safe・`visibility: collapse`・`direction: rtl` の左右の裏返し・abspos の子の静的位置の揃え（`OutOfFlow.Pending` に領域と揃えの割合を持たせ、大きさが決まった後で揃える。M2b-3 の grid も使う）・`flex-basis` の `min-content`/`fit-content`・内在サイズ（縮まない項目は確定した flex base size より小さく寄与しない §9.9.1・折り返す column は容器の高さで列に分けた幅の和〔`order` の順〕・`flex-wrap: balance` は内在サイズでは複数行）・fieldset の legend をベースラインから外す（HTML §15.3.13）。新経路 WPT **124 → 150/260**（flexbox 69 → 95）。**入れ替わり 0**、`flexbox-baseline-multi-line-horiz-004` を取り戻した。flex の旧経路だけ合格は 38 → **13** で、残りは全て縦書き 7 件（M2b-5）と表 6 件（M2b-4）。`NestedFlexTest` の上限を箱あたり 2 → 3 回・平均 1.5 → 1.75 回に改めた: `align-content: normal` を入れたので、折り返す row が親の column に伸ばされると行が伸び、短い項目が「内容の行」と「伸びた行」の 2 つの stretch の制約で測られる（仕様どおりの 3 つの異なる制約。実測は深さ 3〜8 で平均 1.33〜1.59 回・最大 3 回で深さに依らず一定、追い出し 0）。検証: `FlexFeaturesTest`（10。仕様から手で求めた位置）・`IncrementalOracleTest#flexFeatures`（25 種の flex のスタイルの変異で全再計算と 100% 一致。変異検証: スロットを箱の同一性でなく節で照合する誤りで落ちる）・`LegacyParityTest#flexExtendedMatchesLegacy`（変異検証: `align-content` の分配を外すと落ちる）・`SpikeView.dump` に断片のベースラインを足した |
| **M2b-3** | Grid: `GridLayoutEngine` のアルゴリズム（配置・トラックのサイズ解決・揃え。純粋な static 関数）と `TrackList` を移植し、子を測るつなぎ目（`minContentContribution(LayoutBox, ctx)` など）だけを `LayoutCache` へ差し替える（§3.4.2-2） | 旧経路だけ合格の grid 系（14 件）が 0・同梱の実文書 7 本中 6 本が grid を使うので `RealDocumentFramesTest` の文書で幾何一致（§3.4.2-4） | **完了 2026-11-25**（グリッド本体と実文書の幾何）: `GridLayoutEngine` の配置（`GridPlacement`: 線の番号・名前・`span`・`grid-area`・`grid-template-areas` の暗黙の線・自動配置の row / column / dense・空の `auto-fit` を潰す）とトラック（`GridTracks`: `TrackList` の解析・§11 の簡略版の寸法の解決・`*-content` の分配と safe）を移植し、子を測るつなぎ目だけを `LayoutCache`（`minMax`・`layout`）へ差し替えた `GridFlow`（全て ≤ 600 行。旧経路の文字列の解析の `ThreadLocal` の記憶は持ち込まない）。`BoxKind.GRID` / `INLINE_GRID`・項目の揃え（`*-self`・auto 余白・stretch は確定した高さで測り直す §6.2）・高さが決まった後の行の確定し直し・abspos の子の包含ブロックはグリッドの線の領域（§10.1。auto の線は padding の箱の端）・静的位置の揃え・内在サイズ（列を不定の空間と min-content の制約で解いた和）・容器のベースライン・`direction: rtl`。新経路 WPT **150 → 152/260**、grid の旧経路だけ合格 **16 → 0**。**入れ替わり 15 件**（どれも旧経路も不合格の、テストと参照が同じく誤っていた偶然の合格）: `grid-lanes` 12 件（`display: grid-lanes` は対象外で、参照がグリッドで描くようになった）・`subgrid` 3 件（`grid-gap-larger-001`・`line-names-009`・`repeat-auto-fill-002`。新経路は `subgrid` を none として扱う。旧経路の部分的なサブグリッドも合格していない）。直した不具合 2 件: ① 文書の根が初期包含ブロックの高さを子の % の高さの基準に渡していなかった（`html, body { height: 100% }` の実文書で html が内容の高さに。幾何の一致の掃引で検出）② `position: relative` の % の `top` / `bottom` を % の高さの基準を載せる判定に含めた（WPT `relative-grandchild`）。検証: `GridFeaturesTest`（9。仕様から手で求めた位置）・`IncrementalOracleTest#gridFeatures`（18 種のグリッドの変異で全再計算と 100% 一致）・`LegacyParityTest#gridMatchesLegacy`。変異検証: `auto-fit` を潰さない・伸ばした項目を確定した高さで測り直さない・文書の根の基準を渡さない、のそれぞれで落ちる。**幾何の一致の掃引 `GeometrySweepTest` を新設**（§3.4.2-6。WPT のテストと参照 458 文書と実文書 9 本の全ての要素の箱を DOM の道筋ごとに旧経路と 1/64 px で比べ、全て一致した文書の一覧 `geometry-sweep-matching.txt` をラチェットで固定。`-Dgeom.sweep.diag=<文書>` でずれた箱を出す）: **332/467 文書が一致**。実文書は `hud_test`・`machine_ui` が一致、残りのずれた箱は `showcase` 53・`voidwave` 77・`surface_test` 36・`animation_test` 11・`media_test` 86・`item_block_test` 291・`guidebook` 60 で、グリッドの外の M2b-1 の残り（フォーム部品と置換要素の内在の大きさ・`details` / `summary`・`fieldset` / `legend` の描き方・スクロールコンテナ・旧経路の 9999 px の幅の縮み）と、文字の幅の 0.3〜1 px の差が主。**実文書の幾何一致（完了 2026-11-25）**: 実文書 9 本のずれた箱を全て再現の文書で切り分け、新経路の誤りは直し、残りは旧経路の逸脱と丸めに分類して記録した（**`GeometryDeviationsTest`**: ずれた箱の道筋の集合と両方の経路の値を `geometry-sweep-deviations.txt` で固定し、どの行も分類を持つことを確かめる。新経路の変更で実文書の箱が 1 つでも動けば落ちる）。**直した新経路の誤り 3 件**（回帰カタログに追加）: ① 行の中の原子（`img`・`inline-block`）の % の高さを容器の確定した高さに対して解いていなかった（`InlineLineBuilder` へ基準を渡す。`item_block_test` の `height: 100%` の流体の `img` が 88 → 16）② 部品の見た目のチェックボックス・ラジオボタン・range のベースラインを枠線の箱の下端にしていなかった（WebKit / Blink の `RenderTheme::baselinePosition` と旧経路と同じ。`guidebook` のタスクリストの行が 3 px 高い）③ 幅 % の置換要素の min-content の寄与（M2b-4 の別の作業で見つけて直したものを統合で移した。`media_test`）。**記録した旧経路の逸脱 8 種類と丸め**（どれも再現の文書で原因を確かめ、仕様または旧経路自身の行の組み方と照らして新経路を正とした）: L1 内在サイズが最後の文字の後の `letter-spacing` を含める（行を組むときは含めない。`voidwave`・`showcase` ほか）・L2 max-content が閉じる要素の後ろの空白を二重に数える・L3 flex の基準の大きさを空きの幅で頭打ちにする（§9.2 3E は max-content）・L4 交差軸の fit-content が min-content を下限にしない・L5 最初のフレームが CSS アニメーションを当てない・L6 置換要素の flex 項目を指定の大きさより縮める・L7 min-content が要素の境目を折り返しの機会にする（読点の前でも）・L8 グリッドの項目の寄与が % の幅を 9999 px に対して解く（`item_block_test` の 3 列のグリッドが 9999 px になり、後ろの箱が全て下へずれる）・U1 新経路の `LayoutUnit` の段ごとの丸め（0.02 px ほど）。実文書のずれた箱 `showcase` 2（L1）・`voidwave` 48（L1）・`surface_test` 7・`animation_test` 11・`media_test` 14・`item_block_test` 323（L8 の波及が 280）・`guidebook` 90 → 57（L7）、`hud_test`・`machine_ui` は 0。変異検証: % の高さの基準・チェックボックスのベースラインをそれぞれ外すと `ReplacedTest` と `GeometryDeviationsTest` が落ちる。`IncrementalOracleTest#replacedElements` に容器の高さと % の高さの変異を追加。調べる用に `GeometrySweepTest#diagnose` へ HTML ファイルの指定・要素の印・一致した箱の出力・両方の経路へ足す CSS（`-Dgeom.sweep.inject`）を足した。**範囲外で見つけた新経路の差**（実文書の幾何には出ない）: 閉じる要素の直前の畳まれる空白を要素の外（後ろの親の側）に置く（CSS Text 3 §4.1.1 では先の空白が残るので要素の中。`x <code> loop </code> x` の `code` の箱が空白 1 つ分狭く、背景のある `code` で見える）。行末の空白を消す規則（§4.1.2）と一緒に直す必要があり、M2c の前に行の組み方の残りとして直す |
| **M2b-4** | Table: `TableLayoutEngine` の列幅・行の高さ・`border-collapse` の計算を同じ形で移植（親が決めた列幅・行の高さは `ConstraintSpace` で子へ渡し、箱へ書かない）・匿名の表の箱は `BoxTreeCompiler` で作る | 旧経路だけ合格の表系（8 件）が 0 | **完了 2026-11-25**（M2b-1 の残りと並行し、別の作業ツリーで行った）: **入力側**: `BoxKind` に表の 7 種（`TABLE`・`INLINE_TABLE`・行グループ・行・セル・キャプション・列。表の内部の箱の float・absolute はブロック、`inline-table` は表へ blockify）。匿名の表の箱 `TableBoxes`（CSS 2.2 §17.2.1。旧 `BoxTreeBuilder.fixupTable` の移植）は `BoxNode.flow`（レイアウトが並べる子）だけを組み直し、`children` は DOM の子と 1 対 1 のまま（増分の照合を崩さない）。匿名の箱は `BoxNode.Pseudo.ANONYMOUS`（持ち主の節を持つ）で、前回の `flow` の中の種類・スタイル・包んだ子（`==`）が同じ匿名の箱を前回の参照のまま使う（下流のキャッシュが当たる）。表の構造を持たない親（大半）はチャンクの事実 `BoxChunk.hasTableInternal` の O(チャンク数) で抜けて確保 0。`display: contents` の子は先に展開して判定し、表・行グループ・行の子の空白だけの文字は箱を作らない。匿名の箱のスタイルは `css/AnonymousStyles`（親の継承群を共有し `display` だけを持つ。不変の定数）。セルの `colspan` / `rowspan`・列の `span` はスタイルに現れないので入力側が `BoxNode.span` に写す（属性だけの変化も箱の作り直しとして届く。`BoxNode` は 56 バイトのまま）。**レイアウト**: `TableFlow`（入口・行の高さ・縦揃え・断片の組み立て）・`TableStructure`（格子・`thead` / `tfoot` の並べ替え・`rowspan=0`・`visibility: collapse`）・`TableColumns`（自動・割合・固定レイアウトの列幅。配列だけの純粋な関数）・`TableBorders`（枠線の重ね）・`TablePriors`（前回の断片の候補）。全て ≤ 600 行。旧エンジンの計算を移植し、セルを測るつなぎ目だけを `LayoutCache`（`minMax`・`layout`）へ差し替えた（§3.4.2-2）。旧経路が行・セルの箱へ書き込んだ値は値で渡す: 列幅は `ConstraintSpace.fixedWidth`、重ねた枠線は新設の `ConstraintSpace.borders`（`TableBorders.Edges`。値等価で鍵に入り、`BlockFlow` は `BoxGeometry` へ渡すだけ）、行の高さと縦揃えのずれは表が作る伸ばした断片（高さと内容の原点 `insetTop` だけが違い、中身の断片は同じ参照。中身の高さで整形した元の断片は `FlowChunk.slots` に残り、次のフレームで当たる。制約に伸ばした高さを載せて取り違えない）。行グループと行は自分では整形されず、表が大きさと子の位置を決めた断片になる。表は列の最小幅の和より狭くならず、表の `height` は表の格子の箱の下限でキャプションはその外に足す。表の内在サイズは列の最小・最大の和とキャプションの最小幅。flex: column flex の項目の表は `height`（格子の箱）にキャプションを足した外枠を基準の大きさにし（`FlexLines.columnBase` ・`TableFlow.captionExtent`。親が決めた高さはキャプションを含む外枠）、`CONTENT_BLOCK_SIZE` では自分の `height` を掛けない（幾何の一致の掃引の `table-as-item-specified-height` で検出）。一時アダプタ: 匿名の箱の使い捨ての節（`持ち主の鍵_anonN`）・重ねた枠線（使う枠線と `collapsedBorders`）・重ねる表の padding 0・行と行グループは縁を持たない（旧経路と同じ）。**旧経路と意図して変えた点**（旧経路の逸脱を写さない）: ① 表の高さの指定の余りを行へ高さの比で配る（CSS Tables 3 §3.10.3。旧経路は行の下に空きを残した）② 枠線を重ねる表のセルの min-content は使う枠線（半分ずつ）で数える（旧経路は重ねる前の全幅を足し、内容で幅が決まるセルを広く取った）③ 表の内在サイズを表の計算で求める（旧経路は汎用のブロックの内在サイズ）④ 匿名の表はインライン要素の下でだけ `inline-table`（旧経路は `inline-block` の下でも）。新経路 WPT **155 → 162/260**（旧経路の 160 を超えた）。表の旧経路だけ合格 **7 → 0**（`flexbox_rowspan`・`flexbox_stf-table`・`item-with-table-with-infinite-max-intrinsic-width`・`table-as-item-fixed-min-width`・`table-as-item-min-content-height-1`・`table-as-item-stretch-cross-size-5`・`ws-break-spaces-applies-to-011`）、入れ替わり 0。幾何の一致の掃引 **346 → 355/467 文書**（ずれた箱 3,502 → 3,338。`guidebook` 111 → 90）。検証: `TableLayoutTest`（13。仕様から手で求めた位置）・`IncrementalOracleTest#tables`（行の挿入・削除・`colspan` / `rowspan` の属性だけの変化・20 種のスタイルで匿名の箱が増減する変異で全再計算と 100% 一致）・`LegacyParityTest#tableMatchesLegacy`・`ProjectionPixelParityTest` に分離・重ね・匿名の表の 3 文書（旧経路と画素一致）。変異検証 6 件（セルへ重ねた枠線を渡さない・匿名の箱を使い回さない・span の写しを比べない・縦揃えを外す・元の断片を残さない・匿名の箱を作らない、のそれぞれで落ちる。span の誤りは `IncrementalOracleTest#tables` でも落ちる）。**範囲外**: 行・行グループ・列の枠線の重ねと `border-style` の優先順位（旧経路と同じ制約）・行・列の背景の描き方と `empty-cells`（描画。M2c）・縦書きの表（M2b-5）・行・行グループが包含ブロックになること |
| **M2b-5** | 縦書き（`writing-mode`・`text-orientation`）・双方向（`direction: rtl`・`unicode-bidi`）・`anchor()` 位置指定・コンテナクエリ（容器を整形 → 子孫を解き直す 2 段。現在は全体の作り直し） | 旧経路だけ合格の縦書き・双方向系（13 件）が 0・**新経路の WPT ≥ 旧経路の合格数（160）** | **完了 2026-11-25**: ① `text-align: match-parent` を算出値の段階で親の算出値と `direction` から `left` / `right` に解く（`ValueComputer`。CSS Text 3 §7.1。新経路の行組みは親を辿らない。旧経路も同じ算出値を読む）。② 縦書き: 旧経路の「論理（横書き）の軸で組んだ中身を回して置く」方式を移植した。入力側（`BoxTreeCompiler`）が縦書きの文脈を子へ渡し、文脈の中の箱は物理のプロパティを論理の軸へ入れ替えたスタイル（`OrthogonalStyles.axisSwapped`。旧 `DerivedStyles.verticalAxisStyle` を呼ぶ）を `BoxNode.style` に持つ（カスケードの継承元・前回との照合には入れ替える前の `BoxNode.computed`）。直交フローの根（横書きの中の縦書き・縦書きの中の`horizontal-tb`）は流れの中身を回転の `transform` を持つ包む箱（`BoxNode.Pseudo.ORTHOGONAL`、`OrthogonalBoxes`。`children` は DOM と 1 対 1 のまま `flow` だけを包む）へ入れ、位置指定の子は根の直下に残して軸を入れ替えない（旧 `clearAxisFlowOutsideWrapper`）。新エンジン `OrthogonalFlow`（旧 `VerticalWritingEngine` / `HorizontalReturnEngine` の移植）: 行の長さ＝根の内容の高さ（指定・包含ブロックの確定した高さ・ビューポートの高さ。§7.3。直交の根には % の高さの基準を必ず渡す）、根の幅（auto）＝論理のブロック寸法、90° は右へ・-90° は下へずらして置き、転置はずらさない。スクロールの範囲（`ScrollableOverflow`）は包む箱を回した物理の矩形で数える。一時アダプタは包む箱を `isVerticalWrapper` の箱として写し、旧描画が `transform` で回す。**直した新経路の誤り 1 件**: column の flex の `align-items: baseline` を `wrap-reverse` で裏返していた（交差軸にベースラインが無いので safe self-start。CSS Box Alignment 3 §9.3・旧 `FlexLayoutEngine` と同じ。M2b-2 の移植の誤り）。新経路 WPT **162 → 170/260**、**旧経路だけ合格 8 → 0**（縦書き 7・`match-parent` 1）、入れ替わり 0。幾何の一致の掃引 353 → **372** 文書（実文書のずれは変わらず）。検証: `WritingModeTest`（7。手で求めた根の大きさ・包む箱の置き場所・行の長さの決まり方・位置指定の子・横書きへ戻す根・flex の項目の内在の幅・文脈の変化と無関係な変化での参照の再利用）・`FlexFeaturesTest#columnBaselineFallsBackToSelfStart`・`IncrementalOracleTest#writingModes`（書字方向の付け替え・横書きへ戻す根・位置指定・縦書きの flex と grid の変異で全再計算と 100% 一致）・`ProjectionPixelParityTest` に縦書きの文書（文字・枠線・横書きへ戻す根・縦書きの flex。旧経路と画素一致）・`InlineTextTest` と`TypedCssCoverageTest` に `match-parent`。変異検証: 包む箱のずらし・直交の根への % の基準・column の baseline の扱い・`match-parent` の解決をそれぞれ外すと落ちる（子の文脈の変化での解き直しは、UA の CSS が `inherit` を使うため要素のスタイルの変化で子が必ず解き直され〔`elemRestyle`〕、外しても今の構成では表に出ない。`inherit` を使わない構成の正しさの条件として残す）。**旧経路と同じ制約**: 包む箱の中の位置指定の子孫で包含ブロックが根の外にあるものは論理の座標のまま上へ渡る・疑似要素とマーカーの箱は軸を入れ替えない。③ コンテナクエリの 2 段: 新しい入口 `FragmentPipeline`（箱の木を作る → 整形 → 問い合わせコンテナの内容ボックスの寸法を測る → 前と違う容器の部分木だけを `BoxTreeCompiler.restyleContainers` で解き直して整形し直す。旧 `RenderPipeline` と同じく最大 4 回。寸法は 1 ビューの状態として旧経路と同じ `InteractionState` に記録）。カスケードの問い合わせ先は `ContainerScope`（要素の状態は `InteractionState`、`containerOf` はコンパイル中の祖先の列〔根から下へ辿るので祖先は必ず載る〕の算出スタイルと測った寸法から答える。節 → スタイルの表は作らない）で、`@container`・名前付きの問い合わせ・`style()`・`cqw` などの単位・疑似要素（持ち主も容器）に効く。容器の `container-type` / `container-name` が変わったときも部分木を解き直す（非継承なので子の解き直しとして届かない）。コンテナを使う文書で台帳の変更のたびに全体を作り直していた扱いはやめた。検証: `ContainerQueryTest`（5。手で求めた幅・名前・cqw・疑似要素・`style()`・親の幅の変化と名前だけの変化・容器をやめたときの増分と全再計算の一致）・`IncrementalOracleTest#containerQueries`（容器の付け外し・名前・幅の変異）。変異検証: 名前と種類の変化での解き直し・容器の疑似要素の作り直しを外すと落ちる。④ 双方向: 旧経路も行の中の並べ替えは描画（`TextRaster` の `BidiText`）だけで、レイアウトに移植するものは無い（`direction: rtl` の揃え・flex / grid の向きは M2b-1〜M2b-3 で移植済み）。⑤ `anchor()` 位置指定: 旧 `AnchorPositioning.placeAbsolute` の規則（`anchor()`・`anchor-size()`・`position-area`・`position-try-fallbacks` の `@position-try` と flip-block / flip-inline / flip-start・`position-try-order`）を `AnchorPlacement`（配置）と `AnchorTry`（候補と反転）へ移植した。アンカーは、包含ブロックが流れの外の子を置く時点で自分の中身（流れの塊・行・同じ回に先に置いた流れの外の断片）から探す（`Anchors`。不変の断片は絶対座標を持たないため。包含ブロックの外のアンカーは見つからないが、仕様の「受け入れられるアンカー」は包含ブロックの中の要素が主で、文書の根が包含ブロックなら文書全体）。ポップオーバーの暗黙のアンカー（呼び出し元の `popovertarget`）は入力側が属性を写す（`BoxNode.popover`・`PopoverLink`）。`@position-try` の規則は`LayoutSession` で渡す。中身が変われば包含ブロックを整形し直すので、アンカーが動けば配置した箱も追従する。検証: `AnchorPlacementTest`（17。旧 `AnchorPositioningTest` と同じ文書・同じ期待値の 16 と、アンカーが動いたときの増分と全再計算の一致）。変異検証: flip-block・`position-area`・暗黙のアンカーをそれぞれ外すと落ちる。**旧経路と違う点**: スクロールに追従する `position: fixed` の候補の選び直し（旧 `repositionLayout`）は、スクロールの量をレイアウトの入力にしないので M2c の描画の側で行う・反転で入れ替えた余白は位置にだけ効く（一時アダプタは箱の余白をスタイルのまま写す）|
| **M2c** | **描画の入力を断片木へ（新経路を既定にする前提）**: ① `Style` の群を生成器で「レイアウトに効く群」と「描画だけの群」に分け、描画だけの変化では `BoxNode`・断片を作り直さない（§3.4.2-3）② 断片木（と最新の群の木）から描画項目を作る新しいクラスを書き、描画の記憶は断片の同一性（`==`）の 1 層だけにする。旧 `DisplayListBuilder`・`FragmentPaint`・`PaintOrder`・`InkState`・`PaintStamps` ほか（描画の 17 本のうち入力側）は付け替えずに M2c の中で削除する③ 問い合わせ（CSSOM の `BoxLayoutQuery`・当たり判定・スクロール・`UiFocus`・`ElementInspector`・MOD 3 本）を断片木から答える ④ ビュー単位の経路の選択（構築時の引数。`Shortcut`・`EngineFlags` にしない）を経て新経路を既定に。出力（`DisplayItem`）は変えない（`DisplayScene` 化は M3b） | `RealDocumentFramesTest`・`GpuBaselineTest`・`VkBaselineTest`・`costGate`・`memoryAndLatencyGate` が新経路で緑・hover / 文字の変更の 1 フレームが旧経路の交互 A/B 比 ≤ 1.0 | **進行中（① 完了 2026-11-25）**: ① 群を生成器で分けた。描画だけのプロパティ（`PropertyRegistry` の `PAINT`。ただし `visibility` は `collapse` が表の行・列と flex の項目を畳むのでレイアウトの群に残す）を、継承するものは新しい `InheritedPaintStyle`（`Style.inheritedPaint`。`color` ほか 9）へ、しないものは `VisualStyle`（`z-index`・`transition-*` / `animation-*`・`user-select`・`column-rule-color` を移した）へ集めた。2 つの木は `BoxNode.layout`（レイアウトの入力の箱）で表す: 普段は自分自身で 2 つの木は全ての節を共有し、描画だけの変化（`Style.sameLayout`）では前回の箱のまま、最新の群を持つ箱は変わった節とその祖先だけ別に作る。2 つの木は同じ形（`children`・`flow` の位置が対応する）で、描画は位置で並走して読む（② で使う）。`FragmentLayout` はレイアウトの入力の木を読むので、hover の色だけの変化は整形 0（`varied-list` の `li:hover` で `layoutComputes == 0`、根の断片が同じ参照）。`ArchitectureHealthTest` に `core/layout/fragment` が描画だけの群を読まない規則を足した。検証: `LayoutInputTreeTest`（5。描画だけの変化と寸法の変化を混ぜた乱択 120 手で、断片が全再計算と、最新の木がゼロから作った木と一致し、2 つの木が同じ形）。変異検証: 継承する描画だけの群の変化で子を解き直す・前回のレイアウトの入力の箱を使う・チャンクの切れ目を問わない照合をそれぞれ外すと落ちる。途中で分かった点: 隣り合う 2 つのチャンクを作り直すと最新の木の側では 1 つにまとまるので、レイアウトの入力の並びは子ごとに前回と照合する。② は 4 段: ②-1 箱 1 つを描く処理を取り出す・②-2 断片木と最新の木から不変の描画用の木を作る・②-3 新しい描画で旧経路と同じ順に項目を出す（箱ごとの項目は描画用の木の節に持つ）・②-4 旧描画と項目を 1 つずつ比べ、新経路の WPT をこの描画で回す。**②-1 完了（2026-11-26）**: 箱 1 つを描く道具（`BoxDecorationPainter`・`ControlPainter`・`BoxAnimation`・`PaintSupport`・`ItemProps`・`PaintBounds`・`BoxDecorations`）が読む箱を `PaintBox`（行の断片は `PaintRun`）にし、`DisplayListBuilder` の箱ごとの描画（節の錨・背景・枠線・部品）を `BoxItemPainter` へ、文字の行を `TextRunPainter` へ取り出した（`DisplayListBuilder` 922 → 374 行。描く順・クリップ・記憶は呼び出し側に残し、旧経路は今までどおり動く）。`PaintBox` は移行の間だけ`core.layout` に置く抽象クラスで、`LayoutBox` の不変の素性（`node`・`isAnonymous`）を移して持つ（パッケージの循環と、クラスの形のラチェット〔`LayoutBox` のメソッド数・新しい型の public な可変の欄〕を守るため。`LayoutBox` の部分構築の索引表は `ReusableTable` へ出した）。M2b-6 で `core.paint` へ移す。**残り**: ②-2〜②-4・③ 問い合わせ・④ 経路の選択と既定化 |
| **M2b-6** | 旧経路（`LayoutBox`・`MeasureMemo`・`PlacementMemo`・`PaintStamps`・`LayoutReads`・`PartialBuild`・`RenderTreeBuilder`/`RenderPipeline` の旧多段分岐・旧エンジン本体・レイアウト系 `Shortcut`・グローバルエポック・一時アダプタ `FragmentProjection`・`LegacyParityTest` の旧経路側）を一括削除 | 上の完了ゲート（削除 ≥ 4,500 行・エポック 0・一時アダプタ 0・KPI 更新） | 未着手 |


#### 3.4.2 M2b の実装方針（2026-11-25 の調査で確定）

**調査の結果**（旧経路だけ合格の WPT 85 件・新経路だけ合格 21 件・両方不合格 79 件、M2b-1 の途中 96/260 の時点）:

| 分類（先に当たった順） | 旧経路だけ合格 | 担う段 |
| :--- | :---: | :--- |
| flex の拡張（折り返し＋`align-content` 12・column の折り返し 9・absolute の静的位置の揃え 8・`*-reverse` 8・内在サイズの語 6・ベースライン 5） | 37 | M2b-2 |
| grid | 14 | M2b-3 |
| 縦書き・双方向 | 13 | M2b-5 |
| 表 | 8 | M2b-4 |
| インライン・文字（`<br>`・`vertical-align`・justify ほか） | 7 | M2b-1 |
| 疑似要素 | 4 | M2b-1 |
| float の残り・基礎 | 各 1 | M2b-1 |

**M2b-1 ⑦ の後（120/260）の旧経路だけ合格 61 件**: flex 41・grid 16・css-text 5（`match-parent`〔M2b-5〕・文字を持たない span の枠線・`text-wrap: balance`・`display: contents`・表の行〔M2b-4〕）。

**M2b-3（グリッド本体）の後（152/260）の旧経路だけ合格 17 件**: flex 13（縦書き 7・表 6）・css-text 4。grid は 0。

**M2b-5 ② の後（170/260）の旧経路だけ合格 0 件**（縦書き 7・`match-parent` 1 を移植した）。

**M2b-4 の後（162/260）の旧経路だけ合格 8 件**: flex 7（縦書き 7〔M2b-5〕）・css-text 1（`match-parent`〔M2b-5〕）。表は 0。

**M2b-1 ⑭ の後（154/260）の旧経路だけ合格 15 件**: flex 13（縦書き 7〔M2b-5〕・表 6〔M2b-4〕）・css-text 2（`match-parent`〔M2b-5〕・表の行の `break-spaces`〔M2b-4〕）。M2b-1 の分類の旧経路だけ合格は 0。

**M2b-2 の後（150/260）の旧経路だけ合格 33 件**: flex 13（縦書き 7〔M2b-5〕・表 6〔M2b-4〕）・grid 16（M2b-3）・css-text 4（`match-parent`〔M2b-5〕・行端の空白の畳み・`text-wrap: balance`・`break-spaces`）。

同梱の実文書 7 本の使用機能: flex 7・grid 6・スクロールコンテナ 6・アニメーション/トランジション 5・transform 5・フォーム部品 4・画像/SVG/canvas 4・absolute 3。WPT の件数では flex、実文書では grid・スクロール・アニメーション・置換要素が要になる。

**確定した方針**

1. **段の順は M2b-1 → M2b-2 → M2b-3 → M2b-4 → M2b-5 → M2c → M2b-6。** 各段は「その分類の旧経路だけ合格を 0 にする」を完了条件にし、新経路の合格一覧（`reftest-passing-v3.txt`）のラチェットで進捗を固定する。旧経路で合格している項目を新経路で落としてよいのは、原因が後の段の未実装の機能で、計画書に記録した場合だけ（入れ替わり）。入れ替わりは M2b-6 までに全て取り戻す。

2. **旧エンジンは「アルゴリズムは移植・つなぎ目は差し替え」。** grid・表・縦書きの旧エンジンは、`LayoutBox` の欄への書き込みが少なく（grid 8・表 12 か所）、記憶の仕組み（`MeasureMemo` ほか）への参照は 0 で、トラックの配置・サイズ解決・列幅などの計算は純粋な static 関数（`TrackList` は完全に独立）。書き直さずに計算を `core/layout/fragment` の新しいクラス（≤ 600 行）へ移し、子を測る関数だけを `LayoutCache`（`minMax`・`layout`）を呼ぶ形に替え、結果は断片で返す。旧クラスは旧経路が動いている間は残し、M2b-6 で消す。

3. **描画だけの変化はレイアウトを作り直さない（構造で）。** 実測: `varied-list-2000` の hover（色だけが変わる）で、新経路はレイアウトに 0.44 ms を使っていた。色は継承群（`InheritedStyle`）にあり、`li` の `BoxNode` が変わって子を解き直しチャンクが外れるため。旧経路は `PropertyRegistry` の `Invalidation.PAINT` でプロパティごとに判定している。新経路では述語を足さず、**生成器（`scripts/GenStyleGroups.java`）で群を「レイアウトに効く群」と「描画だけの群」に分ける**（継承群も 2 つに分ける）。`BoxTreeCompiler` は**2 つの不変の木**を出す: レイアウトの入力の木（レイアウト群・種類・文字・子だけを持つ。解き直した結果のレイアウト群が前回と同じ参照〔`shareEqualGroups`〕なら前回の参照のまま）と、最新の全ての群を持つ木。`LayoutCache` はレイアウトの入力の木の参照（`==`）で照合し、描画は 2 つの木と断片木を並走して読む。**節 → 最新スタイルのような可変の側表は作らない**（2 つ目のスタイルの置き場になり、原則 4 の「単一のメモ化言語・状態の系統を増やさない」と原則 1 の「入力は不変」に反するため）。レイアウトは描画だけの群を読まない（`ArchitectureHealthTest` で `core/layout/fragment` からの参照を禁止する）。M2c ①で行う。**実装（M2c ①）**: 2 つの木は別の型を作らず `BoxNode.layout` で表した。レイアウトの入力の箱は描画だけの群を古いまま持つ（読まないことを `ArchitectureHealthTest` で守る）。描画だけの群を空にした別の型にすると、変化の無い木でも 2 つ目の木の全ての節を持つことになり、メモリが倍になるため。

4. **新経路を既定にする前に、描画の入力を断片木へ替える（M2c を新設）。** 実測: 一時アダプタ（`FragmentProjection`）経由で hover の 1 フレームを描くと、射影 16.7 ms ＋ 描き直し 10.4 ms（旧経路の 1 フレームは 0.39 ms。調査時にその場で交互 3 回測った参考値で、正式な時間の主張は M2c で足す交互 A/B のゲートで行う）。旧描画の記憶（`FragmentPaint` の `IdentityHashMap<LayoutBox, Memo>`・`PaintStamps`）は箱のインスタンスの使い回しを前提にしており、毎フレーム新しい箱では一度も当たらないうえ、古い箱の記憶が溜まり続けてメモリが尽きる（`OutOfMemoryError` を確認）。したがって**一時アダプタは 1 フレームの検証（WPT・画素一致）専用**とし、対話のフレームには使わない。M2c では、断片木（と方針 3 の最新の群の木）から描画項目を作る**新しいクラスを書き**、描画の記憶は断片の同一性（`==`）をキーとする 1 層だけにする。旧 `DisplayListBuilder`・`FragmentPaint`（52 欄の `Memo`）・`PaintOrder`・`InkState`・窓のずらし・`PaintStamps` は**鍵を付け替えて延命せず、M2c の中で削除する**（§1.1 の根本欠陥 2・3 そのものなので、付け替えは運用ルール 2 の「壊れた土台の上の対症療法」になる。運用ルール 7 の「置き換えたら同じ段で削除」）。新経路の既定化に使うビュー単位の経路の選択は、ビューの構築時の引数として持ち、プロセス共有のスイッチ（`Shortcut`・`EngineFlags`）にはしない。M2b-6 で引数ごと消す。出力（`DisplayItem`）は変えないので、並行している M3a（MOD のレンダラの撤去。`DisplayItem` の消費側）と衝突しない。`DisplayScene` 化（出力側）は M3b に残す。

5. **入力側の未実装は `BoxTreeCompiler` に閉じて作る（M2b-1）。**
   - 疑似要素: `BoxNode` に疑似の種類（`::before` / `::after` / `::marker`）を持たせ、持ち主の節と種類の組で照合する（DOM に疑似の節を作らない）。カウンタ・引用符は文書順に依る非局所の依存なので、使う文書では変更のあった節より後ろの兄弟と子孫を解き直す規則を足す。
   - 置換要素とフォーム部品: `BoxKind.REPLACED` を足し、内在の大きさは旧経路と同じ供給元（`ReplacedElements`・画像の大きさの `HostServices`）から引く。
   - アニメーション・トランジション: レイアウトに効くプロパティの補間値は `StyleSource` が `StyleAnimator` で解き（時刻は `Clock` 注入）、動いている節はアニメーションの駆動側が毎フレーム `ChangeLedger` へ `STYLE` を追記する（入口は台帳 1 本のまま）。描画だけのプロパティ（opacity・transform・色）の補間は今までどおり描画の側。
   - スクロールコンテナ: 断片はスクロールで溢れた範囲（`scrollW/H`）を持ち、スクロール量はレイアウトの入力にしない（描画・ビューの側で足す）。sticky はレイアウトでは静的位置だけを出し、ずれは描画の側で解く。

6. **検証の物差しを段ごとに足す。**
   - WPT: `WptV3BaselineTest` のラチェット（段ごとに分類の旧経路だけ合格を 0 へ）。
   - 増分の正しさ: `IncrementalOracleTest` に段ごとの機能の変異を足す（全再計算と全断片の一致）。
   - 幾何の一致の掃引（M2b-1 で新設）: WPT の 260 文書と同梱の実文書 7 本の全ての箱の位置と大きさを、DOM の節ごとに旧経路と比べ（許容 1/64 px）、完全に一致した文書の数をラチェットにする。画素の合否より細かく、M2c の前に実文書の差を潰す物差しになる。**ただし旧経路は正解の基準ではない**（負の余白の相殺など、仕様からの逸脱を持つ）。差は旧経路の逸脱として記録した除外の一覧で扱い、旧経路の不具合を新経路へ写さない（正しさは運用ルール 6 のとおり WPT・画素一致・`FrameOracle` で決める）。実文書の除外の一覧は `geometry-sweep-deviations.txt`（`GeometryDeviationsTest`。ずれた箱ごとに分類と両方の経路の値を持つ。M2b-3 で新設）。
   - 費用: `:core:m2aSpike` の数値（8,000 行の 1 件挿入 ≤ 1.0 ms ほか）を各段で守り、M2c で hover・文字の変更・幅の変更の交互 A/B を足す（幅の変更は §3.3 C の引き継ぎどおり、文字の節ごとの語の幅を 1 層メモにする）。

7. **並行作業との関係。** M3a（MOD のレンダラの撤去・`CoreGpuSurface`）は別に進んでいる。M2b〜M2c は `core/layout`・`core/box`・描画の入力側を変え、`DisplayItem` とその消費者には触れない。コミットは自分の変更したファイルだけをステージし、プッシュ前の Linux CI（作業ツリーを丸ごと写す）は並行作業の未コミットの変更が無いときに回す。

**v3 の規範との照合（2026-11-25）**: 上の方針を §2.1 の 5 原則・§5 の 7 原則・`CLAUDE.md` の作業規則と照らし、反する読み方ができた 3 点を修正した（方針 3 の可変の側表 → 2 つの不変の木／方針 4 の旧描画の記憶の付け替え → 新しいクラスで置き換えて同じ段で削除／ビュー単位の経路の選択 → 構築時の引数で `Shortcut`・`EngineFlags` にしない）。明確化した 3 点: 幾何の一致の掃引は正解の基準ではない／調査時の時間は参考値で、正式な主張は交互 A/B のゲート／原則 2 の群の列挙と §2.2 の図を更新した。新経路のコード（`core/box`・`core/layout/fragment`）は、プロセス共有の可変状態・`ThreadLocal`・`Atomic`・実時計・`Shortcut`/`EngineFlags`・旧来の記憶（`MeasureMemo` ほか）への参照が 0 であることを確認した（一時アダプタ `FragmentProjection` だけは旧 `LayoutBox.addChild` を通じて旧経路のエポックに触れるが、1 フレームの検証専用で M2b-6 で消える）。

---

### 3.5a Phase M3a: `CoreGpuSurface` 品質検証と MOD レガシーレンダラ（9,369 行）先行撤去

`EnderFrameBuiltInConfig.useCoreBackend`（`CoreGpuSurface`）の基盤が既に存在するため、フラット `DisplayItem` を廃止する **M3b より前に MOD 側のレガシーレンダラ（9,369 行）を撤去する**。`DisplayItem` を消費する側が `:core` と薄い `MinecraftHostSurface` だけになれば、M3b の移行対象と二重保守が一気に減るためである。ただし既定の `useCoreBackend` が無効のまま切り替えると品質欠落が起きるため、**前提品質の確認関門を先に通過してから撤去**する。

- **Step 1（必須の事前確認関門 — Step 2 撤去着手の前提条件）: `CoreGpuSurface` による MOD 全描画カバレッジの検証と不足補完**
  - 現行の `CoreGpuSurface.drawable()` がフォールバックしている要素を含め、MOD 内の全画面・全要素（GUI / コンテナ / HUD / ブロック面 / ホログラム / アイテム / 地図）が `:core` バックエンド単体で品質・性能劣化なく描けることを確認・補完する：
    1. `<mc-scene>` および `<scene-3d>`（`ElementType.SCENE`）をコア経路で完結描画できるようにする。
    2. `McMediaImages.supports()` が現在フォールバックさせている要素（アイテム・エンティティ・流体・ブロック・プレイヤー頭・`<canvas>`・オフスクリーン動画テクスチャ等）を、`:core` の RHI 外部テクスチャ SPI（`GpuDevice.wrapExternalTexture` / `ImageProvider`）経由の `MinecraftMediaBridge` へ統合する。
    3. Minecraft ビットマップ書体（`MinecraftFontAdapter` 経由の `FontResolver`）、LOD 描画、およびホバーツールチップ描画（`DisplayItemDrawer.renderTooltipOverlay` 依存の分離）がレガシー経路なしで完結することを確認する。
  - **Step 1 定量合格基準（すべて満たすまで Step 2 の削除へ進まない）**:
    - `useCoreBackend = true` での実行時、レガシー経路へのフォールバック発火件数（`CoreGpuSurface.drawable() == false`・`BROKEN` 例外フォールバック・`<scene-3d>` / `<mc-scene>` / 画像 / 書体等のフォールバック総計）：**`0 件`**。
    - 対象全種別（GUI・コンテナ・HUD・ブロック面・ホログラム・アイテム・地図・`<mc-scene>`・`<scene-3d>`・メディア・ビットマップ書体）の実機スクリーンショット回帰：既存レガシー基準画像との比較で**全件合格**（画素差分が規定許容閾値 `≤ 0.1%` 以内かつ描画欠落 `0 件`）。
    - `./gradlew runGameTestServer`：**全件緑（失敗 `0`）**。
- **Step 2: MOD レガシーレンダラ（`src/main/java/com/nishiyu/enderframelib/client/renderer/`）の一括撤去**
  - Step 1 の定量合格後にのみ以下を実施し、撤去後に再度 `./gradlew runGameTestServer` と実機スクリーンショット回帰を実行して単一経路での動作を確定する：
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
    - `EnderFrameBuiltInConfig.useCoreBackend` の切り替え・フォールバック分岐、`CoreGpuSurface.BROKEN` によるレガシーフォールバック
  - **MOD 側に残すアダプタ**: `MinecraftMediaBridge`・`MinecraftFontAdapter`・`MinecraftHostSurface`（旧 `CoreGpuSurface` を整理。GUI の最小ヒット情報参照のみ M3b で `DisplayScene` へ付け替える）。
- **完了ゲート（必須検証）**:
  - `src/main/java/com/nishiyu/enderframelib/client/renderer/`：`9,369 行 → ≤ 1,500 行`、`useCoreBackend` 切替分岐 `0`、レガシーフォールバック経路 `0 件`。
  - **レガシー経路完全削除後の必須検証**: `./gradlew runGameTestServer` 全件通過（失敗 `0`）、および対象全種別（GUI・コンテナ・HUD・ブロック面・ホログラム・アイテム・地図・`<mc-scene>`・`<scene-3d>`・メディア・ビットマップ書体）の実機スクリーンショット回帰全件合格（画素差分 `≤ 0.1%`）。

---

### 3.5b Phase M3b: フラット `DisplayItem` 廃止と階層的 `DisplayScene` + L4 単方向化

`Fragment` の階層を `DisplayItem[]` 平坦配列に潰してから `PaintSceneDriver` で復元するという無駄な往復を完全に廃止し、レイアウト結果から GPU / CPU 描画までを 1 本の軽量ツリーで単方向に繋ぐ。M3a により MOD レガシーレンダラが既に撤去されているため、ここでの移行対象は `:core` 内部と薄い `MinecraftHostSurface`・GUI 最小参照のみとなる。

- **新データモデル（`core/paint/`）**
  ```java
  // 1. 不変階層描画シーン（旧 DisplayItem[] + Fragment + PaintSceneDriver を置換）
  public final class DisplayScene {
      public final SceneLayer root;
      public final Int2ObjectMap<SceneLayer> byNodeId; // 動的オフセット・ヒットテスト・スクロール用 O(1) 索引
  }

  public final class SceneLayer {
      public final int nodeId;                 // 文字列 ID ゼロ
      public final float x, y, w, h;           // 親相対座標（unstuck / scroll 0 基準）
      public final float scrollClipW, scrollClipH; // 非スクロール要素は NaN
      public final Transform2D transform;      // 固定変形（アニメ中は外部表で上書き）
      public final ClipShape clip;             // 角丸クリップ・clip-path（親鎖の継承は走査スタックで解決）
      public final LayerEffects effects;       // opacity / filter / backdropFilter / mixBlendMode / mask
      public final StickyConstraint sticky;    // position: sticky のみ非 null（無ければ null）
      public final SceneLayer[] stickyDescendants; // スクロールコンテナのみ保持（通常空、カリング外 sticky 回収用）
      public final DrawOp[] ops;               // このノード自身の描画プリミティブ（背景・枠線・影・文字グリフ列・画像）
      public final SceneChildren children;     // 32 子チャンク階層（≤ 32 子は単一配列、スタッキング順・境界矩形付き）
      public final boolean hasAnimatedOrScrollDescendant; // 静的サブツリーの FBO/テクスチャ再利用判定
  }

  // 2. 32 子単位の不変チャンク列（可視範囲カリングと増分シーン構築を両立）
  public final class SceneChildren {
      public final SceneChunk[] chunks;        // 各チャンク ≤ 32 子。子数 ≤ 32 の要素はチャンク 1 個（または単一配列高速パス）
      public final boolean monotonicY;         // Y 方向単調増加リストなら二分探索カリングを有効化
  }
  public final class SceneChunk {
      public final float minX, minY, maxX, maxY; // このチャンク内全子孫を包むローカル境界矩形（含 overflow/sticky 可動域）
      public final SceneLayer[] layers;        // スタッキングコンテキスト順（negZ → flow → posZ）に整列済みの子層
  }
  ```
  - **動的パラメータの完全外部化**: スクロール量（`ScrollState`）および補間アニメーション（`AnimatorState`、`int nodeId` キー）は `DisplayScene` を書き換えず、バックエンド走査時に `nodeId` で O(1) 参照して座標・変形・色を合成する。
  - **可視範囲カリング（旧 `ChildWindow` / `VirtualFlowState` の代替）**:
    - スクロール状態を外部化したことで 8,000 行分の `SceneLayer` はシーンツリー内に残るが、`SceneChildren`（32 子単位の `SceneChunk`、各チャンクが包含境界矩形 `minX..maxY` を保持）により描画・ヒットテスト走査時にスタック上のローカル可視矩形 `visibleRect` でカリングする。
    - `monotonicY == true` の長大フローリストでは `SceneChildren.chunks` に対して `visibleRect` の上端・下端を**二分探索（`O(log(N / 32))`）**して交差するチャンク範囲だけを走査し、一般ツリーでは各 `SceneChunk` の包含矩形と `visibleRect` の交差判定で不可視サブツリーを一括スキップする。結果として 8,000 行リストでも毎フレーム可視範囲（数十行・数チャンク）しか走査しない。
    - さらに `SceneEmitter` も `ChunkedFlow` の未変更 `FlowChunk` に対応する `SceneChunk` を参照同一性（`==`）で再利用するため、8,000 行への 1 件挿入時のシーン再構築コストも `O(N / 32 + 32)` に抑えられる。
  - **スクロール依存配置（`position: sticky`）の外部評価とカリング整合**:
    - `LayoutFragment` と `SceneLayer` は常に unstuck（スクロール 0）座標で純粋構築し、スクロール発生時にレイアウトも `DisplayScene` も作り直さない。
    - `sticky` 要素は `StickyConstraint`（`scrollContainerNodeId`・`top/bottom/left/right` inset・包含ブロック可動範囲 `[cbMinX..cbMaxX, cbMinY..cbMaxY]`・unstuck 矩形）を保持し、バックエンド走査およびヒットテスト時に `ScrollState.get(scrollContainerNodeId)` から O(1) でクランプ式により動的オフセット `(stickyDx, stickyDy)` を算出して加算する。
    - unstuck 座標がビューポート外へ流れた `sticky` 要素がチャンクカリングで誤ってスキップされないよう、スクロールコンテナ自身が `stickyDescendants` 一覧を保持し、親チャンクがカリングされていても包含ブロックが可視域に掛かる sticky 要素を必ず走査対象へ残す。
  - **L4a `SceneEmitter` → L4b バックエンドの完全単方向化**:
    - `SceneEmitter` が `LayoutFragment → DisplayScene` を生成する（未変更 `LayoutFragment` サブツリーに対応する `SceneLayer` / `SceneChunk` も参照同一性で再利用）。
    - `GpuRenderBackend`（RHI `GpuDevice`）と `SoftwareRenderBackend`（64×64 `TileQueue`）はどちらも `RenderBackend.render(DisplayScene scene, DynamicSceneState state, long nowMs)` を直接受け取る。
    - GPU 未対応の稀なベクターパス・特殊ブレンド等がある場合、**`SceneEmitter` 側が事前にソフトウェアラスタでビットマップ化（`DrawOp.Image`）してから `DisplayScene` に積む**ことで、`GpuRenderBackend ↔ SoftwareRenderBackend` のピンポン呼び出しを構造的に消滅させる。
  - **ヒットテスト統合**: `HitTestIndex`（749 行）を廃止し、`DisplayScene`（または `LayoutFragment`）を逆順（手前から奥）に直接走査してチャンクカリング・スクロール・sticky・変形・クリップを評価する（`O(log N + 木深さ + 命中候補)`）。
- **削除対象**
  - `DisplayItem.java`（237 行）・`PaintCell.java`（77 行）・`RenderHints.java`（174 行）・`ImmutableItemList.java`・`AbsoluteItems.java`（118 行）
  - `DisplayListBuilder.java`（922 行）・`FragmentPaint.java`（776 行）・`Fragment.java`（471 行）・`FragSeq.java`（428 行）
  - `PaintSceneDriver.java`（926 行）・`RetainRanges.java`（87 行）・`RangeBounds.java`（40 行）・`InkBounds.java`（72 行）・`InkState.java`（66 行）・`ItemDiff.java`（93 行）
  - `StickyShift.java`（34 行）・`ScrollContentInfo.java`・`HitTestIndex.java`（749 行）
  - `CpuFallback.renderOnto` 逆転コールバック・`GpuSink.java` ピンポン経路（`core/paint/emit` / `core/paint/port` は `SceneEmitter` + RHI 契約へ整理）、および MOD GUI に残る最小 `DisplayItem` 参照の `DisplayScene` / `HitResult` 置換
  - 残存する描画系スイッチおよび **`Shortcut.java`・`EngineFlags.java` 近道フラグ群の完全削除（`38 → 0`）**
- **完了ゲート**:
  - `showcase` 幅変更の描画シーン構築時間：`0.273 ms → ≤ 0.08 ms`。
  - `varied-list-2000 fontsize`（4 スレッド・実書体）：`10.76 ms → ≤ 5.0 ms`（**144Hz 予算 `6.94 ms` 達成**）。
  - 8,000 行スクロール時に不可視チャンクがスキップされ可視チャンクのみ走査されること、`position: sticky` 要素が `FrameOracle` と 100% 一致すること。
  - `Shortcut` スイッチ数 **`0 個`**、3 者画素一致（CPU / OpenGL / Vulkan）100% 緑、`L4` 関連コード規模 30% 以上削減。

---

### 3.6 Phase M4: L5 ホスト層純化と MOD パッケージ循環解消

M3a により MOD レガシーレンダラは既に撤去されているため、本フェーズでは肥大・分裂していたホスト層（`UiView` / `StandaloneView` / MOD `client`）の解体・統合と循環依存解消に専念する。

- **L5 ホスト層（`UiView` + `StandaloneView` + `SessionContext`）の純化と重複解消**
  - `UiView.java`（2,005 行）と `StandaloneView.java`（1,060 行）を解体し、以下に分離する：
    1. **`EngineView`**（`common/`、≤ 400 行）：1 画面の純 Java コア状態（`DomDocument`, `StyleSheet`, `ChangeLedger`, `BoxTreeCompiler` キャッシュ, `LayoutCache`, `DisplayScene`, `ScrollState`, `InteractionState`, `AnimatorState`, `UiTimers`）とフレーム更新 `stepAndPaint(nowMs, backend)` のみを持つ。
    2. **`InputController`**（`common/`、≤ 500 行）：ヒットテスト・ポインタ・フォーカス移動・IME / テキスト編集（旧 `TextEditing` と MOD `TextareaEditor` を統合）・範囲選択・`<select>` ポップアップ（旧 `SelectPopup`）・スムーズスクロール補間を 1 箇所で処理する。
    3. **`StandaloneHost`**（`common/`、≤ 250 行）：ウィンドウ座標・クリップボード接続のみを受け持つ薄いラッパ。
    4. **MOD 側 `SessionContext` / `EnderScreenDelegate`（1,298 行 → ≤ 450 行）**：`serverManaged`, `headlessState`, `lastSyncRequestTime`, `shouldRequestSync()`, `explicitSize`, `applyOverrides()` 等の Minecraft 通信・画面統合責務だけを MOD 側に閉じ、`EnderScreenDelegate` の入力・編集重複コードを削除して `InputController` に委譲する。
- **MOD パッケージ循環の解消**: `client ↔ client.gui ↔ client.renderer ↔ client.renderer.surface` の 5 組の直接循環依存を「`client.renderer`（低位描画アダプタ）← `client`（セッション管理）← `client.gui`（画面ホスト）」の単方向に整理する。
- **完了ゲート**:
  - L5 共通ホスト（`EngineView` + `InputController` + `StandaloneHost`）：計 `≤ 1,200 行`（旧 `UiView` + `StandaloneView` + `UiInput` = 3,855 行）。
  - MOD 側パッケージ循環：`5 組 → 0`。
  - `./gradlew runGameTestServer` および実機スクリーンショット回帰全通過。

---

### 3.7 Phase M5: DOM 整理・1,000 行超クラス 0 の達成（スコープ純化）

本フェーズはアーキテクチャ刷新の本流（L2 DOM 構造の純化と巨大クラス根絶）のみに責務を限定する。起動高速化・非同期並列化・AWT 窓統合は、アーキテクチャ刷新とは独立した別優先順位（§3.9 Track P1〜P3）へ切り出した。

- **DOM とアリーナの二重管理解消（`core/dom/`）**
  - `DocumentArena`（446 行）が `HtmlNode`（1,399 行）をミラー保持している二重構造を整理し、`HtmlNode` / `HtmlElement`（980 行）をノード基本構造・属性・DOM API・イベント配送へ責務分割して各クラス `≤ 500 行` に収める。
  - セレクタ高速化に必要なデータ（atom 化タグ/id/class・Bloom ビット）のみを軽量 `SelectorIndex` として独立させ、トポロジ二重同期を廃止する。
- **完了ゲート**:
  - `:core` および MOD 全体で **1,000 行超クラス `0 個`**（`ArchitectureHealthTest` のハードゲートで強制）。
  - `DocumentArena` トポロジ二重同期 `0`、`HtmlNode` / `HtmlElement` 各分割クラス `≤ 500 行`。
  - **全体保持ヒープ最終目標の達成**：`varied-list-2000 ≤ 12.0 MB`、`large-list-2000 ≤ 4.5 MB`（Chrome `5 MB` 以下）。
  - WPT 160/260 以上維持、3 者画素一致・`RealDocumentFramesTest`・`FrameOracle` 全通過。

---

### 3.8 Phase M6: 品質ゲート最終固定と配布（v2.0.0 GA）

- `ArchitectureHealthTest` のクラス行数上限を **全クラス `≤ 600 行` のハードゲート** へ引き上げて固定する（例外は自動生成・純データ表等の明示ホワイトリストのみで原則 `0 個`、グローバル mutable static = `0`、パッケージ循環 SCC = `0`、`core/` の Minecraft 非依存 = 100%）。
- Linux 環境での全件テスト・実機検査（`scripts/ci-linux.ps1 -Full`）を通過させ、公開 API 署名（`ApiSignatureTest`）を確定して `:core`（v2.0.0）を単体ライブラリとして Maven Central / GitHub Packages へ公開可能にする。

---

### 3.9 独立トラック詳細（Track P1〜P3: 本流と分離された最適化・ホスト拡張）

- **Track P1: コールド起動短縮（旧 B6）**
  - **前提条件**: なし（M0〜M6 と独立にいつでも着手・単独出荷可能）。
  - **着手時の実測で分かったこと（2026-11-25）**: 旧ゲートの `ColdStartMain showcase`（`546 ms`）は `FontResolver.FALLBACK`（書体なし）で開いており、**書体の走査は最初から含まれていなかった**（スタックの採取で `SystemFonts` 0 件）。実書体の既定の経路（`new StandaloneView(html, w, h)`）では走査が別に `250〜400 ms`（680 面）かかる。したがって P1 を 2 つに分けた。`ColdStartMain` に `-Dcold.fonts=system`（OS の書体で開く。目録があれば使う）と `=scan`（目録を消して全走査）を足し、書体の経路も同じ計測器で測れるようにした。
  - **P1a 書体の目録〔完了 2026-11-25〕**: 走査（FreeType で全ファイルを開く）を `core/text/font/FontScanner` へ切り出し、結果を利用者のキャッシュのフォルダの目録 `FontCatalog`（`%LOCALAPPDATA%\EnderFrame`・`~/Library/Caches/EnderFrame`・`$XDG_CACHE_HOME/enderframe` の `font-catalog-v1.txt`）へ書く。次の起動では、目録の形式の版・走査の規則の版（`FontScanner.RULES_VERSION`）・根のフォルダの並びが一致し、**走査で中身を列挙した全フォルダの更新時刻が記録と同じ**とき（書体の追加・削除・改名はそのフォルダの時刻を変える。fontconfig と同じ判定）だけ目録を読み、それ以外は走査して書き直す。書き込みは一時ファイルからの置き換えで、壊れた・途中で切れた目録は使わない。目録の書き込みの直前 2 秒以内に変わったフォルダがあれば、その目録は使わない（時刻の分解能〔切り捨て・HFS+ 1 秒・FAT 2 秒〕の中で起きた追加・削除を見逃さないため。Linux の CI で時刻の一致による見逃しとして検出し、`regression-catalog.txt` に記録）。目録は走査の結果を順序ごと写すので、**解決の結果は目録の有無で変わらない**（決定性を保つ）。`SystemFonts` から走査の旧コード（`scan`・`defaultFontDirectories`・面の読み取り）を削除し `609 → 443 行`（600 行超の上限表から外した）。目録の場所は `SystemFonts.load(roots, catalog, locale)` で注入でき（`null` で目録なし）、プロセス共有の状態・新しいシステムプロパティは足していない。
    - **採らなかった案**: 計画時の「既定ファミリの直開き＋未知ファミリだけ遅延走査」は、全走査なしでは「その書体が入っていない」を判定できず（総称ファミリと代替書体の候補の多くは入っていない書体）、解決の結果が走査済みかどうかで変わる（同じ文書が起動の経緯で違う書体になる）ため採らなかった。目録で 2 回目以降の起動から同じ効果を得る。初回の起動（と書体を足した直後）は従来どおり全走査を 1 回払う。
    - **検証**: `FontCatalogTest`（11 件: 書き込みの直前に変わったフォルダを信用しない・2 回目は走査せず同じ面を同じ順で返す・書体の追加/削除/下位フォルダの変化/後から作られた根で捨てる・版/根の違い/壊れた行/途中で切れた目録を使わない・タブ/改行/`\` の往復・書けない場所でも走査の結果を返し一時ファイルを残さない・実際の OS の書体を写したフォルダで走査と目録の `SystemFonts` の照合結果が一致）。変異検証: フォルダの時刻の照合を外すと 2 件落ちる。
    - **実測**（同じセッションの交互 A/B、旧 `HEAD` の worktree と新を 11 回ずつ交互・中央値）: 書体の読み込み（`SystemFonts.system()`）`243 → 24 ms`、プロセス起動 → 実書体で文書を開き終わるまで `786 → 718 ms`（対の差の中央値 `-58 ms`、11 回とも新が速い）。**走査で浮いた約 220 ms の大半は全体の短縮にならない**: 走査は 1 本のスレッドで動く間に JDK のクラスのロード・JIT の翻訳・LWJGL のネイティブの読み込み（SHA-1 の照合を含め約 35 ms。今は最初の字形を開くときに払う）を済ませており、それが `open` の側へ移る（`open` 単体は `455 → 619 ms`）。冷えた JVM の費用は足し算にならない。
  - **P1b JVM の冷えた起動（進行中。計測の道具は完了 2026-11-25）**: 書体なしの `open` も冷えた JVM では `≥ 450 ms` で、支配はクラスのロード・リンクとインタープリタ実行（ARCHIVE §6-23・§6-47）。`:core:coldStartAb` を新設した: JDK 25 のツールチェーン（このタスクの子 JVM だけ。エンジン・MOD のビルドと実行は Java 21 のまま）で AOT キャッシュ（JEP 483 / 514 の `-XX:AOTCacheOutput`）を訓練し、JDK 21 / JDK 25 / JDK 25 + キャッシュを新しい JVM で交互に起動して中央値を出す（`ColdStartAbMain`。キャッシュが使えないと黙って素の起動になるので `-XX:AOTMode=on`。AOT はクラスパスが jar だけのときしか使えないので計測用のクラスも jar にする）。**実測**（showcase・実書体・目録あり・7 巡の中央値。2 回）: 開く＋初回フレーム JDK 21 `540〜643 ms` → JDK 25 `468〜550 ms`（`0.86〜0.88x`）→ JDK 25 + キャッシュ **`228〜264 ms`（`0.41〜0.45x`）**、プロセス起動 → 初回フレーム `615〜732 → 304〜355 ms`。訓練を厚くしても（代表 4 文書を 20 回。`-Pab.train`）速くならなかった（`0.42x → 0.45x`、揺れの範囲）。**キャッシュ下の残り約 230 ms の内訳**（スタックの採取）: 旧レイアウト経路（`MeasureMemo`・`BoxMeasurer`・旧エンジン）約 55%・HTML/CSS の解析約 20%・FreeType のネイティブの読み込み（LWJGL の SHA-1 の照合を含む）約 15%。最大の塊は M2b で消える旧経路なので、≤ 200 ms の判定は **M2b-6 の後に `:core:coldStartAb` で測り直す**。それまでに P1b で行う候補: 起動経路の FreeType の読み込みを最初の字形まで遅らせる、UA シートの解析の結果の再利用。MOD は Minecraft 1.21.1 の Java 21 で動くので AOT キャッシュの恩恵は受けない（`:core` を JDK 24 以上で単体で使う利用者向け）。
  - **完了ゲート**: P1a: 目録のある起動で書体の読み込み `≤ 30 ms`（達成 `24 ms`）・解決の結果が走査と同一（`FontCatalogTest`）。P1b: コールド起動（`:core:coldStartAb`・showcase・実書体・目録あり）の JDK 25 + AOT キャッシュの中央値 `≤ 200 ms`（Chrome `304 ms` 以下。JDK 21 素では届かないことを P1b の実測で確認。現在 `228〜264 ms`）。
- **Track P2: 並列レイアウト・描画スレッド非同期パイプライン（旧 B7）**
  - **前提条件**: **Phase M3b 完了**（不変 `LayoutFragment` + 不変 `DisplayScene` の確立後）。
  - **スコープ**: データ競合ゼロの不変ツリー上でサブツリー単位の並列レイアウト（`LayoutTaskPool`）および論理スレッド／描画スレッドの非同期パイプライン分離を実装する。
  - **完了ゲート**: 全実文書の初回構築（`voidwave.html open` 等）：`9.15 ms → ≤ 5.5 ms`（全シナリオ 144Hz 予算 `6.94 ms` 以内）。
- **Track P3: 窓×描画直交分離と標準 Java 2D/AWT 窓ホスト（旧 G7）**
  - **前提条件**: **Phase M4 完了推奨**（L5 `EngineView` / `InputController` 分離後。必要時は独立先行も可）。
  - **スコープ**: `WindowHost` SPI と標準 Java 2D/AWT 窓ホスト（`JFrame` + `BufferStrategy` + `DataBufferInt` 損傷矩形 Blit + `InputMethodRequests` による OS 標準 IME 連携）を整備し、`:core` 単体（GLFW/GL 不要）での 144Hz 起動と `:core-glfw` 上での `SOFTWARE` / `GL` / `VULKAN` 直交選択を完成させる。
  - **完了ゲート**: `:core` 単体（AWT 窓・GLFW/GL 非依存）での 144Hz 起動確認、および `:core-glfw` 上での 3 バックエンド直交切替テスト全通過。

---

## 4. KPI ダッシュボード（現状 vs 根本治療後の目標）

| 軸 | 指標 / シナリオ | 現状（2026-11-24 実測・確認値） | 根本治療後の目標（達成フェーズ） |
| :--- | :--- | :---: | :---: |
| **性能** | `varied-list-2000 fontsize`（4 スレッド・実書体） | `10.76 ms`（144Hz 予算超）。**2026-11-25（M2b-4 後）**: M1c 完了（`957c483d`）との交互 A/B（`ab-gate.ps1`・3 回・中央値）で `-3.6%` / `+20.2%`（2 回。並行の作業の負荷 2〜66% で揺れた）＝変化なし（M2b は旧経路を変えない。新経路の既定化は M2c） | **`≤ 5.0 ms`**（M3b で 144Hz 達成） |
| **性能** | `large-list-8000 child`（1 件挿入・削除） | `7.89 ms`（超線形）。**M2a スパイク（新経路・描画なし）: `0.11〜0.16 ms`**（同 JVM 交互 A/B で旧経路 `4.3〜5.9 ms`）。**2026-11-25（M2b-4 後）**: 新経路 `0.12〜0.18 ms`（`:core:m2aSpike`・同 JVM で旧経路 `5.5〜7.5 ms`、比 `0.02x`）・500 行 `0.018〜0.023 ms`。旧経路は M1c 完了（`957c483d`）との交互 A/B（`ab-gate.ps1`・3 回・中央値）で `+3.0%` / `-21.7%`＝変化なし | **`≤ 1.0 ms`**（M2a スパイク検証済み → M2b 本実装達成） |
| **性能** | `showcase` 幅変更（`resizeexact`・GL） | `0.427 ms`（うち表示リスト 66%）。**2026-11-25（M2b-4 後）**: M1c 完了（`957c483d`）との交互 A/B（`ab-gate.ps1`・3 回・中央値）で `-0.1%` / `+6.0%`＝変化なし | **`≤ 0.20 ms`**（M3b で達成） |
| **性能** | `voidwave.html open`（実文書初回構築） | `9.15 ms`（144Hz 予算超）。**2026-11-25（M2b-4 後）**: M1c 完了（`957c483d`）との交互 A/B（`ab-gate.ps1`・3 回・中央値）（5 回・無負荷）で `-1.5%`＝変化なし | **`≤ 5.5 ms`**（M3b〜M5 + Track P2 で達成） |
| **性能** | コールド起動（JDK 21 素・`showcase`） | `546 ms`（Chrome `304 ms`。書体なしの値）。**P1a（2026-11-25）**: 実書体の経路の書体の読み込み `243 → 24 ms`（書体の目録）、プロセス起動 → 実書体で開き終わるまで `786 → 718 ms`（交互 A/B・中央値）。**P1b（2026-11-25）**: JDK 25 + AOT キャッシュで `540 → 228 ms`（`0.42x`、`:core:coldStartAb`） | **`≤ 200 ms`**（JDK 25 + AOT キャッシュ。Track P1b・M2b-6 の後に判定） |
| **メモリ** | 保持ヒープ `varied-list-2000` / `large-list-2000` | `35.96 MB` / `9.63 MB`（**M1c**: CSS 層は計 ~`3.5 MB`、残り約 32 MB はレイアウト/描画層）。**M2a スパイクの新経路のレイアウト層増分: `1.87 MB` / `0.54 MB`**（DOM＋`Style`＋スタイル解決の副産物＋新レイアウト層の全体 `11.10` / `3.09 MB`）。**2026-11-25（M2b-4 後）**: 旧経路（既定）の保持ヒープ `35.84 MB` / `9.57 MB`（`memoryAndLatencyGate`）。新経路のレイアウト層増分 `2.52 MB` / `0.74 MB`・全体 `11.75` / `3.29 MB`（`:core:m2aSpike`。M2a から行・文字片・表の断片が載った分の増加）＝ M2b のレイアウト層増分の目標と中間の全体の目標を新経路は満たす | レイアウト層増分 **`≤ 4.0 MB` / `≤ 1.5 MB`**（M2a 検証 → M2b 達成）<br>全体保持ヒープ **`≤ 12.0 MB` / `≤ 4.5 MB`**（M2b 中間 `≤ 14.0 / ≤ 5.5 MB` → M3b〜M5 で達成） |
| **構造** | 増分・キャッシュ専用コード規模 | 42 ファイル・`6,360 行` 相当（**M1c**: `StateStyleMemo.java` 167 行ほか `DIFF_MEMO`・`SLICE_POOLS` 削除）。**2026-11-25（M2b-4 後）**: 新経路の 1 層の仕組みは `ChangeLedger` 156 ＋ `BoxTreeCompiler` 368 ＋ `LayoutCache` 187 ＝ `711 行`（旧経路の増分・記憶の仕組みは M2b-6 の削除まで残る） | **`≤ 1,000 行`**（`ChangeLedger` + `BoxTreeCompiler` 増分判定 + 1 層 `LayoutCache`） |
| **構造** | `LayoutBox` / `ComputedStyle` / `UiView` 行数 | `1,785` / **`398 行`（M1 完了）** / `2,005 行`。**2026-11-25（M2b-4 後）**: `1,785` / `450` / `2,005 行`（`LayoutBox`・`UiView` は M2b-6・M4 まで不変） | **解体・各 `≤ 450 行`**（M1 完了 → M2〜M4） |
| **構造** | 1,000 行超クラス数（`:core` 群 / MOD 合計） | `26 個`（`:core` 19・`:core-gl` 1・`:core-vulkan` 1・MOD 5、`ArchitectureHealthTest` 上限固定。※M1c で `ComputedStyle` をリストから除外）。**2026-11-25（M2b-4 後）**: `25 個`（`:core` 18・`:core-gl` 1・`:core-vulkan` 1・MOD 5。main のソースの物理行で数えた） | **`0 個`**（M5 で `ArchitectureHealthTest` 強制達成） |
| **構造** | 600 行超クラス数（`:core` 群 / MOD 合計） | `67 個`（**M1c**: `ComputedStyle` が 600 行超から脱出。新規クラス ≤ 600 行を `ArchitectureHealthTest` で強制）。**2026-11-25（M2b-4 後）**: `66 個`（`:core` 53・`:core-gl` 1・`:core-vulkan` 2・MOD 10。新経路の `core/box`・`core/layout/fragment` の 52 クラスは全て 600 行以下） | **`0 個`**（M6 で `ArchitectureHealthTest` ハードゲート強制） |
| **構造** | グローバル `Shortcut` スイッチ / `EngineFlags` | **`33 個` / `43 個`**（**M1c 完了**: `STYLE_SHARE`・`TEXT_STYLE_FROM_PARENT` 削除・上限更新）。**2026-11-25（M2b-4 後）**: `33 個` / `43 個`（変化なし。新経路は参照 0、レイアウト系は M2b-6 で削除） | **`0 個` / `0 個`**（M2b でレイアウト系・エポック 0 → M3b で `Shortcut` 全廃） |
| **構造** | MOD 側重複レンダラ（`client/renderer`） | `9,369 行`（`useCoreBackend = false` 既定）。**2026-11-25（M2b-4 後）**: `client/renderer` 以下 27 ファイル `9,711 行`（物理行。M3a 未着手） | **`≤ 1,500 行`・コア単一経路化**（M3a で前倒し達成） |
| **正しさ** | WPT 参照画像比較 / 3 者画素一致 / `FrameOracle` | `160 / 260`・画素一致（**2026-11-25（M2b-4 後）**: 旧経路 `160 / 260` 維持）。**新経路（M2b、`WptV3BaselineTest`）: `170 / 260`**（M2b-0 `65` → M2b-1 ⑦ `120` → ⑧ `122` → ⑨ `123` → ⑩ `124` → M2b-2 `150` → M2b-3 `152` → M2b-1 ⑬ `153` → ⑭ `154` → ⑰ `153`〔入れ替わり 1〕 → ⑱ `155` → M2b-4 `162`〔旧経路の 160 を超えた〕 → M2b-5 ① `163` → ② `170`〔旧経路だけ合格 0〕） | **`≥ 160 / 260` 維持・全一致**（全フェーズ） |

---

## 5. 新・運用ルール（二度と構造崩壊とパッチ地獄に戻らないための 7 原則）

1. **優先順位表は §3（本流 Phase M0〜M6 と独立トラック P1〜P3）の 1 つだけとする。** 新しい課題は §3 に統合し、完了したフェーズの成果と数値は §4 を上書き更新する。
2. **壊れた IR や可変オブジェクトの上に対症療法的キャッシュを足さない。** 速度・メモリ問題が出たときはまずデータ構造（入力と出力の分離・参照共有・階層表現）を疑い、破壊的変更で根を絶つ。
3. **入力側の変更検知入口は `ChangeLedger` のみ、下流キャッシュは「1 層メモ化」のみ許可する。** 構造入力（`BoxNode` / `BoxChunk` / `Style` / `LayoutFragment`）は参照同一性（`==`）、`ConstraintSpace` / `MinMaxContext` は値等価（`==` 高速パス付き）またはビュー内インターンで照合し、併せて `MinMaxSizes` の 1 層メモ化と 2 パス測定向け固定少数スロットのみを許可する。DOM への `DIRTY_*` フラグ追加・40 欄ハッシュ後追い検証（`PaintStamps` 型）・ビットマスク読み取り台帳（`LayoutReads` 型）・手書き純粋性フラグ（`F_*`）・出力その場書き換えパッチ・退避復元 `TentativeSlots` は新設を禁止する。
4. **プロセスグローバルな可変状態を禁止する。** 新しい `Shortcut` スイッチ・`static volatile` フラグ・プロセス共通エポックカウンタ・ビジネスロジック上の `ThreadLocal` を作ってはならない。すべての状態は 1 ビュー（`EngineView`）に閉じる。
5. **クラス規模規律を二段階のハードゲートで強制する。** M0〜M5 の間は新規作成・刷新クラス `≤ 600 行` を原則とし M5 完了時点で **`1,000 行超クラス = 0 個`** を `ArchitectureHealthTest` で強制阻止する。M6（v2.0.0 GA）では同ゲートの上限を **`全クラス ≤ 600 行`**（明示ホワイトリストを除く）へ引き上げて強制する。
6. **検証は「外から見た正しさ」と「実コスト」で行い、ゲートの置換順序と縦割りスパイク関門を厳守する。** 正しさは WPT・CPU/GL/Vulkan 3 者画素一致・`FrameOracle`（全再計算との一致）で固定し、性能とメモリはヒープ実測と同時交互 A/B 計測で判定する。新ゲートが緑になる前に旧ゲートを削除してはならず、大規模刷新（M2b）の前には縦割りスパイク（M2a）の実測合格を必須とする。
7. **完了の定義は「旧コード・旧経路の完全削除」「テスト緑」「§4 KPI 更新」の 3 点セットとする。** 旧経路をフラグやフォールバックで残したまま完了としない。

---

## 6. アーカイブおよび他文書との関係

- 旧計画（C 系・K 系・K5b・W0〜W3・2026-11-24 までの設計記録と知見 `§6-1〜205`）の全文は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) に退避済みである（`RegressionCatalogTest` が参照する `§6-N` は同アーカイブの付録 3 にすべて保全されている）。
- `core/DESIGN_INCREMENTAL_KERNEL.md` は本刷新（§1.2 撤回一覧）により**廃止**し、全文と持ち越す知見を ARCHIVE の付録 5 へ移設して削除した。以後の知見は同付録 5.A に積む。
- `CLAUDE.md` は `core/ENGINE_REARCHITECTURE_PLAN.md`（§3〜§5）および M2a の入力側増分設計（§3.3）に準拠する運用であり、`CLAUDE.md`・`ARCHITECTURE.md`・`BENCHMARK.md`・`REFACTORING_PLAN.md` は Phase M0〜M6 の各段階の実装完了に合わせて本計画書（Engine v3）の内容へ順次同期する。
