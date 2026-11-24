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
   - CSS プロパティを継承性・利用箇所に応じた機能グループ別不変構造体（`InheritedStyle`, `BoxGeomStyle`, `VisualStyle`, `FlexGridStyle`, `TableStyle`, `RareStyle`）へ分離する。
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
│     `BoxTreeCompiler`: `(DOM, ChangeLedger, prev) -> BoxNode / BoxChunk`   │
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
| 4 | **M2b** | **全 `FormattingEngine` 移植と `LayoutBox` 一括解体** | float 非侵入/相対除外・`computeMinMaxSizes`・全エンジン段階移植を経て `LayoutBox`・`MeasureMemo`・`PlacementMemo`・`PaintStamps`・`LayoutReads`・`PartialBuild`・レイアウト系 `Shortcut` 等（約 4,500 行超）を削除 | 全エンジン WPT ≥ 160/260 & `FrameOracle` 一致、旧コード削除 `≥ 4,500 行`、レイアウト増分 `≤ 4.0 MB` / 中間全体 `≤ 14.0 MB` |
| 5 | **M3a** | **`CoreGpuSurface` 品質検証と MOD レガシーレンダラ先行撤去** | `useCoreBackend` の全描画要件（`<mc-scene>`・`<scene-3d>`・画像フォールバック・ビットマップ書体・ツールチップ）の事前品質確認関門を通過した上で、MOD `client/renderer` 9,369 行を M3b より先に撤去し `DisplayItem` 消費者を激減させる | MOD 描画 `≤ 1,500 行`・フォールバック `0 件`・`runGameTestServer` & 実機スクショ回帰全件合格 |
| 6 | **M3b** | **フラット `DisplayItem` 廃止と階層的 `DisplayScene`** | `DisplayItem`・`FragmentPaint`・`PaintSceneDriver`・`HitTestIndex`・残存 `Shortcut.java` を完全廃止し、`SceneChildren`（32 子チャンクカリング）+ `StickyConstraint` 付き `DisplayScene` + L4 単方向化を完了 | `varied-list fontsize` 10.76 → ≤ 5.0 ms（144Hz 達成）、3 者画素一致 100%、`Shortcut` 38 → **0** |
| 7 | **M4** | **L5 ホスト層純化と MOD 循環依存解消** | `UiView` / `StandaloneView` を `EngineView` + `InputController` + `StandaloneHost` へ統合、MOD `EnderScreenDelegate` 縮小・循環依存解消 | L5 ≤ 1,200 行、MOD 循環 5 → 0 |
| 8 | **M5** | **DOM 整理と 1,000 行超クラス 0 の達成** | `DocumentArena` 二重同期解消、`HtmlNode`/`HtmlElement` 責務分割、`SelectorIndex` 独立、`ArchitectureHealthTest` による 1,000 行超強制阻止（※起動/並列/AWT 窓は別トラックへ分離） | 1,000 行超クラス **0 個**、`DocumentArena` 二重同期 `0`、全体保持ヒープ `varied-list ≤ 12.0 MB`・`large-list ≤ 4.5 MB` 達成 |
| 9 | **M6** | **品質ゲート最終固定と配布（v2.0.0 GA）** | `ArchitectureHealthTest` のハードゲート上限を 600 行へ確定、Linux CI 完全通過、API 署名確定、Maven Central / GitHub Packages 公開 | **全クラス ≤ 600 行（ハードゲート強制）**、グローバル mutable static = 0 |

### 3.0b 独立トラック（アーキテクチャ刷新と非依存／後続の別優先順位: Track P1〜P3）

M5 へ混在していた起動高速化・非同期並列化・AWT 窓統合は、データ構造刷新とは独立した課題であるため、本流の膨張を防ぐ目的で別トラックとして管理する（詳細は §3.9）。

| トラック | タイトル | 前提条件 | 主な対象 | 完了時の定量的成果 |
| :--- | :--- | :---: | :--- | :--- |
| **Track P1** | **コールド起動短縮（旧 B6）** | **なし（随時着手可）** | `SystemFonts` の全フォルダ走査撤廃・既定直開きオンデマンド解決・ディスク目録キャッシュ | コールド起動 `546 → ≤ 200 ms`（Chrome `304 ms` 以下） |
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
| **M2b-1** | Block の拡張（float と `ExclusionSpace`〔非侵入は `EMPTY` 共有〕・clearance・positioned〔absolute/fixed/sticky の静的位置〕・multicol）＋ Inline の完全版（`inline-block` の原子・`vertical-align`・双方向・ブロックを含むインライン・`::before/::after`・`::marker`・カウンタ・引用符）＋ スクロールコンテナの `scrollW/H` | 新経路で Block/Inline 系の WPT サブセットが旧経路と同数以上・`IncrementalOracleTest` に float/positioned の変異を足して 100% 一致 | **進行中**: ① 位置指定（`OutOfFlow`・`PlacedFragment`。relative は親が置く位置のずれ、absolute/fixed は静的位置を断片に載せて包含ブロックまで上げ、包含ブロックが自分の大きさを決めた後で整形する LayoutNG 型。`IncrementalOracleTest` に absolute/relative の変異を追加して 100% 一致）② 折り返し候補を UAX #14（`LineBreaker`）へ統一（空白の無い和文）③ flex の基準の大きさの修正（`flex-basis` が `content`・基準の無い % なら `height` を見ない／column の pass 1 は `ConstraintSpace.CONTENT_BLOCK_SIZE` で自分の `height` を掛けずに内容を測る）。新経路 WPT **65 → 74/260**。入れ替わり 4 件: 新経路だけの偶然の合格 3 件（テストと参照が同じく誤っていた）と、旧経路も合格の `flex-align-baseline-column-vert-rl-rtl-wrap-reverse`（absolute の箱を column の項目として積んでいた誤りが偶然正解の絵だった。縦書きは M2b-5）④ インライン水準の原子（`BoxKind.INLINE_BLOCK` / `INLINE_FLEX`。縮小して合わせた幅で独立に整形し、行の中に 1 つの塊として置く。行はベースラインより上の最大 ＋ 下の最大で組み、文字片も原子もベースラインへ揃える〔`inline-block` は最後の行・`inline-flex` は最初の行・`overflow` が visible でなければ下の余白の端〕。`LineFragment` に `baseline` と `atoms`）⑤ `text-indent`。新経路 WPT **74 → 91/260**（flexbox 35 → 52）。入れ替わり 1 件: 旧経路も合格の `flexbox-baseline-multi-line-horiz-004`（flex のベースライン揃え・`wrap-reverse`・`align-content`。原子を平らに流していた頃は偶然一致していた。M2b-2 で取り戻す）⑥ float（`Floats`。同じ容器の中で左右に積み入らなければ下へ・`clear`・同じ容器のインラインの行が避ける・BFC の根は float を含む。float・absolute/fixed はブロック水準へ blockify）と、それで見つかった不具合 3 件の修正（高さ 0 の素通しの箱の位置・column flex の主軸の大きさに `min-height`/`max-height`・column の `justify-content: left/right` は start）・内在サイズの末尾の空白の数え落とし・grid と表を BFC の根に。新経路 WPT **91 → 96/260**（入れ替わり 0）。**範囲外として残したもの**: 兄弟の箱の中の行まで float を避けさせる除外領域の受け渡し・float を避けて縮む BFC の箱 |
| **M2b-2** | Flex の完全版（`*-reverse`・column の折り返し・`align-content`・主軸の auto 余白・ベースライン揃え） | Flex 系 WPT 同数以上・`NestedFlexTest` 緑 | 未着手 |
| **M2b-3** | Grid（`GridLayoutEngine` 1,341 行の移植。トラックの解決を `ConstraintSpace` の入力に明示） | Grid 系 WPT 同数以上 | 未着手 |
| **M2b-4** | Table（列幅・行の高さ・`border-collapse` を親から `ConstraintSpace` で渡す。箱へ直接書かない） | Table 系 WPT 同数以上 | 未着手 |
| **M2b-5** | 縦書き（`VerticalWritingEngine`）・`anchor()` 位置指定・コンテナクエリ | 全 WPT で新経路 ≥ 160/260 | 未着手 |
| **M2b-6** | 新経路を既定にし（ビュー単位の経路の選択を経て）、旧経路（`LayoutBox`・`MeasureMemo`・`PlacementMemo`・`PaintStamps`・`LayoutReads`・`PartialBuild`・レイアウト系 `Shortcut`・グローバルエポック・一時アダプタ）を一括削除。描画は M3b まで射影の代わりに断片木を直接読む薄い層へ | 上の完了ゲート（削除 ≥ 4,500 行・エポック 0・一時アダプタ 0・KPI 更新） | 未着手 |

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
  - **スコープ**: `SystemFonts` の起動時全フォントフォルダ走査（Windows で約 500 ファイル・150〜300 ms）を撤廃し、既定ファミリの直開きオンデマンド解決（約 2 ms）＋未知ファミリ要求時のみの遅延スキャン＋ディスク目録キャッシュを導入する。
  - **完了ゲート**: コールド起動（`ColdStartMain showcase`・JDK 21 素）：`546 ms → ≤ 200 ms`（Chrome `304 ms` 以下）。
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
| **性能** | `varied-list-2000 fontsize`（4 スレッド・実書体） | `10.76 ms`（144Hz 予算超） | **`≤ 5.0 ms`**（M3b で 144Hz 達成） |
| **性能** | `large-list-8000 child`（1 件挿入・削除） | `7.89 ms`（超線形）。**M2a スパイク（新経路・描画なし）: `0.11〜0.16 ms`**（同 JVM 交互 A/B で旧経路 `4.3〜5.9 ms`） | **`≤ 1.0 ms`**（M2a スパイク検証済み → M2b 本実装達成） |
| **性能** | `showcase` 幅変更（`resizeexact`・GL） | `0.427 ms`（うち表示リスト 66%） | **`≤ 0.20 ms`**（M3b で達成） |
| **性能** | `voidwave.html open`（実文書初回構築） | `9.15 ms`（144Hz 予算超） | **`≤ 5.5 ms`**（M3b〜M5 + Track P2 で達成） |
| **性能** | コールド起動（JDK 21 素・`showcase`） | `546 ms`（Chrome `304 ms`） | **`≤ 200 ms`**（Track P1 で達成・本流と独立） |
| **メモリ** | 保持ヒープ `varied-list-2000` / `large-list-2000` | `35.96 MB` / `9.63 MB`（**M1c**: CSS 層は計 ~`3.5 MB`、残り約 32 MB はレイアウト/描画層）。**M2a スパイクの新経路のレイアウト層増分: `1.87 MB` / `0.54 MB`**（DOM＋`Style`＋スタイル解決の副産物＋新レイアウト層の全体 `11.10` / `3.09 MB`） | レイアウト層増分 **`≤ 4.0 MB` / `≤ 1.5 MB`**（M2a 検証 → M2b 達成）<br>全体保持ヒープ **`≤ 12.0 MB` / `≤ 4.5 MB`**（M2b 中間 `≤ 14.0 / ≤ 5.5 MB` → M3b〜M5 で達成） |
| **構造** | 増分・キャッシュ専用コード規模 | 42 ファイル・`6,360 行` 相当（**M1c**: `StateStyleMemo.java` 167 行ほか `DIFF_MEMO`・`SLICE_POOLS` 削除） | **`≤ 1,000 行`**（`ChangeLedger` + `BoxTreeCompiler` 増分判定 + 1 層 `LayoutCache`） |
| **構造** | `LayoutBox` / `ComputedStyle` / `UiView` 行数 | `1,785` / **`398 行`（M1 完了）** / `2,005 行` | **解体・各 `≤ 450 行`**（M1 完了 → M2〜M4） |
| **構造** | 1,000 行超クラス数（`:core` 群 / MOD 合計） | `26 個`（`:core` 19・`:core-gl` 1・`:core-vulkan` 1・MOD 5、`ArchitectureHealthTest` 上限固定。※M1c で `ComputedStyle` をリストから除外） | **`0 個`**（M5 で `ArchitectureHealthTest` 強制達成） |
| **構造** | 600 行超クラス数（`:core` 群 / MOD 合計） | `67 個`（**M1c**: `ComputedStyle` が 600 行超から脱出。新規クラス ≤ 600 行を `ArchitectureHealthTest` で強制） | **`0 個`**（M6 で `ArchitectureHealthTest` ハードゲート強制） |
| **構造** | グローバル `Shortcut` スイッチ / `EngineFlags` | **`33 個` / `43 個`**（**M1c 完了**: `STYLE_SHARE`・`TEXT_STYLE_FROM_PARENT` 削除・上限更新） | **`0 個` / `0 個`**（M2b でレイアウト系・エポック 0 → M3b で `Shortcut` 全廃） |
| **構造** | MOD 側重複レンダラ（`client/renderer`） | `9,369 行`（`useCoreBackend = false` 既定） | **`≤ 1,500 行`・コア単一経路化**（M3a で前倒し達成） |
| **正しさ** | WPT 参照画像比較 / 3 者画素一致 / `FrameOracle` | `160 / 260`・画素一致 | **`≥ 160 / 260` 維持・全一致**（全フェーズ） |

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
