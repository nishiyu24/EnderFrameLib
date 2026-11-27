# EnderFrameLib 根本再設計プラン（Engine v3: 破壊的アーキテクチャ刷新）

> **更新日**: 2026-11-27（M3b を 5 段に分け、**M3b-1**〔描画は DOM を読まない〕・**M3b-2**〔`DisplayScene` と `SceneEmitter` を導入し、描画用の木・`TreePainter`・`Fragment`/`FragSeq`・凍結例外 4 つを削除〕・**M3b-3**〔当たり判定・CSSOM・フォーカス・インスペクタを scene から答え、`HitTestIndex`・`PaintBox`・`SceneBox` を削除〕を完了した〔§3.4〕。2026-11-26 の軌道修正以降は §1.3・§3.2・§3.3、独立トラック **P4-1** は §3.9）
> **対象**: `:core`（588 ファイル・約 10.8 万行。`com.nishiyu.enderframelib.core.*` / `common.*` / `api.*`）と組み込みサンプル MOD（`:mod`。`mod/src/main/java/...`）の境界・描画・ホスト層を含む全体構造。
> **旧計画の全面撤回宣言（2026-11-24）**: 従来の `C 系`・`K 系`・`K5b`・`L5 (W0〜W3)`・`K4h`・`B14` と `core/DESIGN_INCREMENTAL_KERNEL.md` の「既存キャッシュ温存＋増分カーネル延命」方針は撤回・凍結した（§1.2）。本計画は**破壊的変更を全面的に許可**し、データ表現・中間表現・描画契約・ホスト統合の土台を作り直す。
> **この文書の書き方（運用ルール 8）**: 本書は**状態表であり作業ログではない**。段ごとの経緯・途中で分かった点・変異検証の記録はコミットの本文と [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) に書く。2026-11-26 以前の本文（M1 の段の記録・M2a の設計とスパイクの実測・M2b〜M2c の作業記録）は、原文のまま ARCHIVE の索引から辿れる `archive/07` へ移した。

---

## 0. 現在地（2026-11-27。作業のたびに上書きする）

| 項目 | 状態 |
| :--- | :--- |
| 完了 | M0・M1・M2a・M2b（WPT 170/260）・M2c（経路を 1 本化、§3.2）・**M3a**（MOD 旧レンダラ撤去、§3.3）・**M3b-1**（描画は DOM を読まない）・**M3b-2**（`DisplayScene` + `SceneEmitter` を導入し、描画用の木・`TreePainter`・`Fragment`/`FragSeq`・凍結例外 4 つ・`SCROLL` 祖先照合を削除）・**M3b-3**（当たり判定・CSSOM・フォーカス・インスペクタ・MOD の入力を `SceneHitTest`/`SceneQuery` で scene から直接答え、`HitTestIndex`・`UIHitResult`・`SceneBox`・`PaintBox`・`PaintRun` を削除。§3.4）。独立トラック P1a・**P4-1**（§3.9） |
| 進行中 | **M3b**（5 段。M3b-1・M3b-2・M3b-3 完了。§3.4 の段の表） |
| 次の一手 | **M3b-4**（バックエンドが scene を直接描き、`DisplayItem`・`SceneDisplayListAdapter`・`SceneAdapterBox` を削除。§3.4）。残る穴は G3 multicol（M3c） |
| 止まっている条件 | なし |
| 期限付きの緩和 | ヒープの上限（`varied-list-2000` 68.5 MB・`large-list-2000` 19.3 MB）と latency の 2 行（`varied-list-2000:fontsize` 157.7 ms・`large-list-8000:child` 57.9 ms）は M3b-4（`DisplayItem` 廃止）まで新経路の実測上限（M3b 完了時に 14.0 / 5.5 MB、46.6 / 16.5 ms へ戻す。§4.1）。`P8bRetentionTest` の天井は 30 MB |
| 二重に持っているもの | 一時アダプタのみ: 記憶を持たない `SceneDisplayListAdapter` とそれが読むパッケージ内の `SceneAdapterBox`（`DisplayItem` とともに M3b-4 で削除）。問い合わせの旧経路（`HitTestIndex`・`SceneBox`・`PaintBox`）は M3b-3 で削除済み。他の一時アダプタ・旧経路は 0 |
| 凍結中の例外 | **なし**（M2c で凍結した `TreePainter` の例外 4 つは全て M3b-2 で削除済み） |
| 一時的に無い機能 | multicol（G3。M3c で戻す）。見つけたテストは `@Disabled` で理由に G 番号を書いて残す |

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

### 1.3 M2 の中間診断（2026-11-26）

M0〜M2c ④-3 前半（63 コミット）を実際のコードで点検した。M1 と新経路のレイアウトは成功している（WPT 170/260、8,000 行の 1 件挿入 0.12 ms、新経路のクラスは全て 600 行以下）。しかし計画全体では、次の 5 点で目的から外れていた。

1. **削らずに足している**: `:core` 本体は M0 着手前の 113,195 行・515 ファイルから **130,093 行・633 ファイル（+15%）** に増えた。M2b の削除対象（`LayoutBox` 1,663 行・`MeasureMemo` 775・`LayoutReads` 624・`RenderPipeline` 1,324・旧エンジン約 5,000 行ほか）は全て残っている。`Shortcut` 33・`static volatile` 48・`ThreadLocal` 28・1,000 行超のクラス 18 個（M0 前は 19）も、ほとんど動いていない。削除を最後の段（旧 M2b-6）に集め、その前に M2c を挟んだため、運用ルール 7（同じフェーズの中で削除する）が事実上止まっていた。
2. **端から端で測ると新経路のほうが重く遅い**: 新経路を既定にすると、`varied-list-2000` の保持ヒープは **53 MB**（旧経路 35.7 MB・上限 46.7 MB・M2b の中間目標 14 MB）。showcase の hover の 1 フレームは **旧経路の 2.5 倍**、文字の変更は **1.3 倍**。M2a の関門はレイアウトだけ（描画なし）で判定していたので、§1.1-3 で主因とした描画が関門の外にあった。
3. **中間表現の段数が減っていない**: 出力を `DisplayItem` のまま据え置いた（`DisplayScene` 化は M3b）ので、新経路は `DOM → BoxNode（2 つの木）→ LayoutFragment → PaintNode → PaintView → 旧 Fragment/FragSeq → DisplayItem[]（文字列 ID・絶対座標）→ PaintSceneDriver` となり、§1.1-3 で問題にした段数のままだった。新しい描画は旧描画の上に乗っている（`TreeStacking` 541 行は旧 `PaintOrder` を 23 か所で参照し、`TreePainter` は旧 `FragmentPaint` の `Fragment`/`FragSeq` を使う）。このままでは M3b でもう一度書き直すことになる。
4. **禁止した記憶の形が新経路で再発した**: `TreePainter` の記憶は次の 4 つを持つ。描く文脈（絶対位置・クリップ・`parentId` の文字列・キャンバスの役・ビューポート）の多欄照合（`PaintStamps` 型）、子から親へ伝える `reusable`（純粋性の述語）、描画が DOM を直接読むための台帳の祖先の照合、使い回さない部品の列挙。原因は 2 つある。出力の契約が絶対座標・文字列 ID の平坦な列であることと、描画の入力が不変でない（属性・入力の値を DOM から読む）ことである。契約を変えずに速くしようとする限り、照合は増え続ける。
5. **切り替えの条件と番人が旧経路に縛っていた**: 既定を切り替える条件「hover・文字の変更が旧経路比 ≤ 1.0」は、対症療法の記憶で速くした旧経路に勝つまで、新経路に記憶を足させる。番人（`LegacyParityTest`・`PaintTreeParityTest` の表示リストの全項目の一致・`GeometryDeviationsTest`）は、新しいコードに旧経路の構造（`parentId`・`PaintOrder` の層）まで写させた。加えて計画書が作業ログになり（旧 §3.4.1 は約 88 KB、1 つのセルが最大 31,810 字）、状態と止まっている条件が読めなくなっていた。

**軌道修正（2026-11-26 に決定。§2・§3・§5 に反映済み）**

| # | 決定 | 理由（上の番号） |
| :---: | :--- | :---: |
| A | **一本化と削除を先にする**: M2c を「新経路への一本化と旧経路の削除」に作り直し、旧 M2b-6 を吸収する。旧経路の削除の前に、新経路の速さを旧経路の水準まで上げる仕事はしない | 1・5 |
| B | **切り替えの条件を絶対値と自己比較にする**: `memoryAndLatencyGate` の `latency:*`（絶対の上限）と `ab-inc-vs-full:*`（同じ経路の増分と全再計算の比）を新経路で満たすこと。旧経路との比（`ab-v3-vs-legacy`）は記録だけにして、合否には使わない | 5 |
| C | **正しさの物差しから旧経路を外す**: 正しさは、仕様（WPT）・自分自身の全再計算（`FrameOracle`・`IncrementalOracleTest`）・CPU / GL / Vulkan の 3 者画素一致・人が確かめて取り直したゴールデンだけで判定する。旧経路との一致を求めるテストは旧経路と一緒に削除する | 5 |
| D | **出力の作り直し（M3b）を描画の最適化より先にする**: 平坦な `DisplayItem` を出す新しい描画には、正しさの修正以外の最適化を足さない。`TreePainter` の記憶は今の形で凍結し（§3.2）、M3b で `DisplayScene` に置き換えて消す | 3・4 |
| E | **中間表現は 3 段まで**: `BoxNode` → `LayoutFragment` → `DisplayScene`。描画用の木（`PaintNode`/`PaintView`）は M3b で `SceneLayer` に吸収し、別の木として残さない（§2.3） | 3 |
| F | **描画は DOM を読まない**: 描画に要る DOM の値（属性・入力の値・画像の到着など）は、`BoxTreeCompiler` が台帳の下で `BoxNode` に取り込む。これで描画の記憶は `(断片 ==, 最新の箱 ==)` の 1 層だけになる（§2.1-3） | 4 |
| G | **関門は端から端で測る**: 以後の関門・切り替え・完了の性能条件は、描画とバックエンドへの受け渡しまで含めた 1 フレームと保持ヒープで判定する | 2 |
| H | **計画書は状態表にする**（運用ルール 8） | 5 |

---

## 2. 新アーキテクチャ原則（Engine v3: 単純さ・値分離・単方向データフロー）

### 2.1 5 つの絶対原則

1. **入力と出力の完全分離（不変の `LayoutFragment`）**
   - レイアウトは `(BoxNode, Style, ConstraintSpace) → 不変の LayoutFragment` という純粋な変換関数にする。一時状態（`overrideWidth`・`floatSpace` 等）は引数と局所変数にだけ置く。
   - 出力が不変なので「入力が同じなら前回の断片を返す」だけで全段の整合が保たれる。可変 `LayoutBox`・`MeasureMemo`・`PlacementMemo`・`PaintStamps`・`resetPlacements` は存在理由ごと消す。

2. **群で共有する型付き不変スタイル（脱 `CssValue[207]`）**〔M1 で達成〕
   - CSS プロパティを継承性と利用箇所に応じた不変の群（`InheritedStyle`・`InheritedPaintStyle`・`BoxGeomStyle`・`VisualStyle`・`FlexGridStyle`・`TableStyle`・`RareStyle`）に分ける。「レイアウトに効く群」と「描画だけの群」を分けてあるので、描画だけの変化はレイアウトの入力の参照を変えない。
   - 初期値の群は静的シングルトンを共有し、継承群が変わらなければ子は親と同じ参照を持つ。長さは 64-bit の `CssLength`、色・キーワードは `int` ARGB・`enum`・bitfield で持つ。

3. **階層的・数値 ID の `DisplayScene`（フラット `DisplayItem` の完全廃止）**〔2026-11-26 改訂〕
   - **入力**: `SceneEmitter` は `(LayoutFragment, 最新の BoxNode)` から不変の木 `DisplayScene` を直接作る。描画用の別の木は作らない。
   - **座標は親相対**: `SceneLayer` は親相対の座標を持つ。前の兄弟が伸びて動いただけの部分木は、同じ参照のまま使い回せる。
   - **記憶は 1 層だけ**: `SceneLayer` は `(断片 ==, 最新の箱 ==)` で、`SceneChunk` は `FlowChunk ==` で使い回す。描く文脈（絶対位置・クリップ・親の ID）を鍵に入れない。
   - **描画は DOM を読まない**: 描画に要る値（属性・入力の値・画像・状態）は `BoxNode` に取り込む（決定 F）。
   - **動的な値は外に置く**: スクロール量・アニメーションの補間値・`position: sticky` のずれは scene に入れない。走査のときに外の状態（`DynamicSceneState`）から足す。
   - **ID と索引**: 子の列は 32 子単位の不変チャンク（`SceneChildren`/`SceneChunk`）で、包含矩形による可視範囲のカリングを持つ。ノードの参照は `int nodeId` にし、文字列 ID・`Map<String, String> events`・`AbsoluteItems` の再変換・`PaintSceneDriver` による階層の復元をなくす。
   - **単方向**: L4a（`SceneEmitter`）→ L4b（`GpuRenderBackend` / `SoftwareRenderBackend`）は一方向に流す。GPU が対応しない要素は L4a の側でテクスチャにしてから流す。

4. **単一のメモ化言語・プロセスグローバル状態ゼロ**
   - `Shortcut`（新規追加を禁止し、旧経路と一緒に `0` まで削除する）・プロセス共有のエポック・`ThreadLocal` の隠れた状態・7 系統のキャッシュを全廃する。
   - 入力側の変更検知の入口は **`ChangeLedger`（1 ビューに閉じた追記専用の台帳）1 本だけ**にし、DOM の節に `DIRTY_*` の欄を足さない。
   - 状態と記憶は全て 1 ビュー（`EngineView`）に閉じる。記憶は §2.3 の表の鍵だけを使う。構造の入力（`BoxNode`/`BoxChunk`・`Style`・`LayoutFragment`）は参照同一性（`==`）、`ConstraintSpace`/`MinMaxContext` は `==` の高速路つきの値等価で照合する（M2a で確定）。`MinMaxSizes` の記憶と、2 パス測定のための固定少数スロット（4）は正規の要素とする。

5. **単一レンダラ・ホスト層の純化**
   - サンプル MOD の旧レンダラ（`client/renderer` 9,711 行）を M3a で撤去し、`:core` の RHI バックエンド（`MinecraftHostSurface`）に一本化する。
   - M4 で `UiView` から Minecraft 固有の同期の残骸を剥がし、純 Java の薄い `EngineView`（≤ 400 行）にする。入力・フォーカス・テキスト編集・選択・`<select>` のポップアップは共通の `InputController` に統合する。

### 2.2 5 層の単方向アーキテクチャ（Engine v3）

```text
┌────────────────────────────────────────────────────────────────────────────┐
│ L5: Host & View (`common/EngineView`, `InputController`, `api/*`,          │
│     MOD `SessionContext` / `MinecraftHostSurface`)                         │
│     所有物: DOM の 1 インスタンス・ScrollState・AnimatorState・入力状態     │
├────────────────────────────────────────────────────────────────────────────┤
│ L4: Scene & Raster (`core/paint/*`)                                        │
│     L4a `SceneEmitter`: (LayoutFragment, 最新の BoxNode) -> DisplayScene   │
│         親相対座標・int nodeId・SceneChildren チャンクカリング              │
│         記憶は (断片 ==, 最新の箱 ==) の 1 層。DOM を読まない              │
│     L4b `GpuRenderBackend` (RHI) / `SoftwareRenderBackend`                 │
│         render(DisplayScene, DynamicSceneState) を直接受ける               │
├────────────────────────────────────────────────────────────────────────────┤
│ L3: Pure Layout (`core/layout/fragment/*`)                                 │
│     (BoxNode / BoxChunk, ConstraintSpace) -> LayoutFragment / ChunkedFlow  │
│     1 層 `LayoutCache`（前回の断片木・FlowChunk.slots・MinMaxSizes）       │
├────────────────────────────────────────────────────────────────────────────┤
│ L2: DOM, ChangeLedger, Style & BoxTreeCompiler (`core/dom`, `core/css`,    │
│     `core/box`)                                                            │
│     (DOM, 凍結した ChangeLedger, 前回の木) -> BoxNode                      │
│     （レイアウトの入力の木と最新の木。節を共有。描画に要る DOM の値も持つ） │
├────────────────────────────────────────────────────────────────────────────┤
│ L1: Text, Media, 2D/3D Primitives & RHI (`core/text`, `core/raster`,       │
│     `core/scene3d`, `core/kernel/gpu/rhi`, `core/kernel/memory`)           │
└────────────────────────────────────────────────────────────────────────────┘
```

### 2.3 中間表現と記憶の予算（2026-11-26 新設）

DOM の後、バックエンドの前に置く中間表現は次の 3 段だけとする。段を足すとき、または表の外の鍵で記憶するときは、先に本表を書き換える（運用ルール 3）。

| 層 | 中間表現 | 記憶の鍵（これ以外の照合を足さない） | 状態 |
| :--- | :--- | :--- | :--- |
| L2 | `BoxNode`（レイアウトの入力の木と最新の木。節を共有する） | 凍結した `ChangeLedger` の `touches` と、親の継承群の `==` | 実装済み |
| L3 | `LayoutFragment` / `ChunkedFlow` | `(BoxNode ==, ConstraintSpace の値等価)`・`FlowChunk.slots`（4）・`MinMaxSizes`（弱参照の表） | 実装済み |
| L4a | `DisplayScene`（`SceneLayer` / `SceneChunk`） | `(LayoutFragment ==, 最新の BoxNode ==)`・`FlowChunk ==` | **実装済み（M3b-2）** |

**期限付きの例外**（この表の外にあるもの。消す段を決めてある）: `PaintNode`/`PaintView`/`TreePainter` の記憶と `Fragment`/`FragSeq` は **M3b-2 で削除済み**。問い合わせの口だった `SceneBox`/`PaintBox` は **M3b-3 で削除済み**（当たり判定・CSSOM は `SceneHitTest`/`SceneQuery` が scene を直接たどる。記憶は持たず、1 回の問い合わせの作業用の値 `HitScratch` をビューの文脈が使い回すだけ）。残る一時アダプタは、記憶を持たない `SceneDisplayListAdapter`（`SceneAdapterSupport`・`SceneAdapterBox` を含み、M3b-4 で `DisplayItem` とともに削除）の 1 つだけ。
**記憶ではない引き継ぎ**: 全体の作り直しの要求（`RELAYOUT`）は前回の木と記憶を捨てるが、作り直す前の要素の算出スタイルだけを、そのフレームのトランジションの始まりの入力として `StyleSource.history` に渡す（照合の鍵ではなく、フレームの後で捨てる）。アンカーの**覚えたスクロール量**（G8。§3.2）もビューの履歴で、アンカーの再計算点でだけ書き換え、台帳の `ANCHOR` で `BoxNode` へ入る（鍵は `BoxNode ==` のまま。`RELAYOUT` をまたいで渡し、文書の入れ替えで捨てる）。

---

## 3. 破壊的再設計ロードマップ（唯一の優先順位表）

### 3.0a 本流ロードマップ（Phase M0〜M6）

| 順位 | Phase | タイトル | 主な破壊・新設の対象 | 完了の定量的な条件 | 状態 |
| :---: | :--- | :--- | :--- | :--- | :--- |
| 1 | **M0** | 新ゲートの先行設置とロックイン解除 | `MemoryAndLatencyGate`・`ArchitectureHealthTest` を先に置き、旧ラチェット 8 本・`*Verifier` 3 種・`memo*` 判定・`shortcutMatrix` を削除 | 保護の空白ゼロでゲートを置き換え・`Shortcut` 新規 0 | **完了** |
| 2 | **M1** | `ComputedStyle` の刷新 | 型付きの群の不変構造体 `Style`・`CssLength`、`CssValue[207]` ほかスタイル系の記憶を全廃 | `ComputedStyle` 2,095 → 398 行・文字列の読み口 0 | **完了** |
| 3 | **M2a** | 入力側の増分と縦割りスパイクの関門 | `core/box`（`BoxTreeCompiler`）・`core/layout/fragment`（`LayoutFragment`・`LayoutCache`） | 5 基準に合格（ただしレイアウトだけで判定。§1.3-2） | **完了・Go** |
| 4 | **M2b** | 全エンジンのレイアウトを新経路へ移植 | Block・Inline・float・位置指定・Flex・Grid・Table・縦書き・双方向・`anchor()`・コンテナクエリ | 新経路の WPT 170/260（旧経路だけ合格 0） | **完了**（multicol は M3c） |
| 5 | **M2c（改訂）** | **新経路への一本化と旧経路の削除**（旧 M2b-6 を吸収） | 既定を新経路へ切り替え、`LayoutBox` ほか旧レイアウト経路・`EnginePath`・旧経路を相手にする物差しを削除 | §3.2 の完了ゲート | **完了**（2026-11-26。移植で見えた新経路の穴 G1〜G9 は §3.2） |
| 6 | **M3a** | `CoreGpuSurface` の品質検証と MOD の旧レンダラの撤去 | MOD `client/renderer` を撤去して `DisplayItem` の消費者を減らす（M2c と並行してよい。M3b の前提） | MOD の描画 ≤ 1,500 行・フォールバック 0 | **完了**（2026-11-26。§3.3） |
| 7 | **M3b（改訂）** | **`DisplayScene` と描画の作り直し** | 平坦な `DisplayItem`・旧描画の層・描画用の木・`TreePainter` の記憶・`PaintSceneDriver`・`HitTestIndex`・`Shortcut` を全廃 | §3.4 の完了ゲート（中間表現 3 段・ヒープ ≤ 14.0 / 5.5 MB・`Shortcut` 0） | **進行中**（M3b-1・M3b-2・M3b-3 完了。§3.4） |
| 8 | **M3c** | multicol（ブロックの断片化） | `LayoutFragment` の断片化と、列ごとの断片の scene | WPT の css-multicol を足し、新経路のラチェットで固定 | 未着手 |
| 9 | **M4** | L5 ホスト層の純化と MOD の循環の解消 | `UiView`/`StandaloneView` → `EngineView` + `InputController` + `StandaloneHost` | L5 ≤ 1,200 行・MOD の循環 0 | 未着手 |
| 10 | **M5** | DOM の整理と 1,000 行超のクラス 0 | `DocumentArena` の二重同期の解消・`HtmlNode` の分割・`SelectorIndex` の独立 | 1,000 行超 0・ヒープ ≤ 12.0 / 4.5 MB | 未着手 |
| 11 | **M6** | 品質ゲートの最終固定と配布（v2.0.0 GA） | 全クラス ≤ 600 行のハードゲート・Linux CI・API 署名の確定・Maven Central | 全クラス ≤ 600 行・グローバルな可変の static 0 | 未着手 |

### 3.0b 独立トラック（本流と独立／M6 の後: Track P1〜P5）

本流（M0〜M6）はエンジン内部のデータ構造の刷新だけを扱う。それと独立に進められる課題、または M6 の後に続く課題を、目的別の 3 群で管理する（詳細は §3.9）。

**位置づけ（2026-11-25 確定）**: `:core` 系は Minecraft に依存しない汎用の HTML/CSS レイアウト・描画エンジンであり、MOD はその利用者の 1 つにすぎない（依存は MOD → Core の一方向）。目標は **JavaFX・Compose Multiplatform Desktop と並ぶ Java の汎用 UI 基盤**とする。勝ち筋の順序は次のとおり。
1. **先に強い分野を取る**（M6 まで）: 既存の GL/Vulkan の描画ループへの埋め込み（LWJGL・libGDX・jMonkeyEngine・Minecraft 等。JavaFX が苦手とする領域）と、決定的なヘッドレス描画。
2. **M6 の後に汎用ツールキットへ広げる**（群 C・Track P5）: JavaFX に対して欠けているもの（アクセシビリティ・状態バインディング・高機能部品・OS 統合）を埋める。

| 群 | トラック | タイトル | 前提条件 | 完了時の定量的な成果 | 状態 |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **A. 性能** | **P1** | コールド起動の短縮 | なし | P1a: 書体の読み込み `243 → 24 ms`。P1b: コールド起動 `≤ 200 ms` | P1a 完了・P1b 計測の道具まで |
| **A. 性能** | **P2** | 並列レイアウト・描画スレッドの分離 | M3b 完了 | `voidwave.html open` `9.15 → ≤ 5.5 ms` | 未着手 |
| **B. ホストと配布** | **P3** | 窓と描画の直交分離・標準 AWT 窓ホスト | M4 完了を推奨 | `:core` 単体で 144Hz 起動・`SOFTWARE`/`GL`/`VULKAN` の直交選択 | 未着手 |
| **B. ホストと配布** | **P4** | リポジトリの Core 主体化・分割・独立名への改名 | P4-1: M2c の完了の後（旧経路の削除で `:core` の API が落ち着いてから）／P4-2: M4 完了・API 確定（M6 と同時） | P4-1: `:core` 系だけのビルドで NeoForge の設定処理 0。P4-2: エンジンのリポジトリ単体で全ゲート緑・Maven Central 公開 | **P4-1 完了**（2026-11-27）・P4-2 未着手（前提 M4） |
| **C. 汎用ツールキット化** | **P5** | JavaFX と並ぶ汎用 UI 基盤へ（P5a〜P5f） | P5f の測定基盤: なし／他: M6 完了 | 業務アプリ相当の見本がスクリーンリーダーで操作可能・JavaFX / Compose との交互 A/B の比較表を公開 | 未着手 |

---

### 3.1 完了したフェーズ（M0・M1・M2a・M2b）の要約

詳細な設計・段の記録・計測は `archive/07`（ARCHIVE の索引から辿る）にある。コードの注釈にある「計画書 §3.3 C」「§3.4.1」「§3.4.2-N」は、`archive/07` の同じ番号の節を指す。

- **M0〔完了 2026-11-24〕**: 新ゲート（`MemoryAndLatencyGate`・`ArchitectureHealthTest`）を先に緑にしてから、旧ラチェット 8 本・`*Verifier` 3 種・`VERIFY_MEMO`/`VERIFY_BOX_MEMO`・`shortcutMatrix`・`costGate` の `memo*` 判定を削除した。`Shortcut` の新規追加を禁止した。
- **M1〔完了 2026-11-25〕**: `Style`（型付きの群の不変構造体）・`CssLength`・`CssExprs`・`CustomProperties`・`StyleFacts` を導入し、`CssValue`・`PackedLengths`・`TypedValueCache`・`StateStyleMemo`・`StyleSlice`/`DIFF_MEMO`/`StyleMap`/`SHARED`・スタイル系の `Shortcut`/`EngineFlags` を削除した。群のクラスは `scripts/GenStyleGroups.java` の生成物なので手で直さない（`PropertyRegistry` を変えたら再実行。`StyleGroupCoverageTest` が足し忘れを検出する）。
- **M2a〔完了・Go 2026-11-25〕**: 実装で確定した設計（M2b 以降もこのまま使う）:
  - `BoxNode` は `nodeId` でなく DOM の節の参照を持つ。`DocumentArena` の番号は削除で再利用されるため。
  - 照合の表（`IdentityHashMap<HtmlNode, …>`）を持たず、前回の木と DOM を並走させて照合する。
  - 子の列は可変長のチャンク（1〜64。64 を超えたら 32 ずつに割る）。
  - レイアウトのキャッシュは前回の断片木そのもの。2 パスのスロットは親の出力（`FlowChunk.slots`）に持ち、`MinMaxSizes` は `BoxNode` を弱参照の鍵にした表に持つ。
  - `ConstraintSpace` は `==` の高速路つきの値等価で照合し、子の制約に使わない欄は載せない。
  - 非局所依存（兄弟セレクタ・カウンタ・引用符・コンテナクエリ）は `BoxTreeCompiler` に閉じる。
  - 引き継いだ課題（幅の変更で全ての行を測り直す・DOM の `ChildList.indexOf` の O(n)・`StyleSource` のルートの基準）は M2c-1 の内訳と M3b で再評価する。
- **M2b〔完了 2026-11-26〕**: 旧エンジンは「アルゴリズムは移植し、つなぎ目は差し替える」方針で移植した。M2b-0 一時アダプタ（65/260）→ M2b-1 Block・インライン・float・位置指定・置換要素・疑似要素 → M2b-2 Flex（150）→ M2b-3 Grid（152）→ M2b-4 Table（162）→ M2b-5 縦書き・`match-parent`・コンテナクエリの 2 段・`anchor()`（170。旧経路だけ合格 0）。M2c ①〜④-3 前半（描画だけの群の分割・描画用の木・`TreePainter`・問い合わせ・`EnginePath`・新経路の振る舞いの穴埋め）も済んでいる。以降はこの成果の上で §3.2 を行う。

---

### 3.2 Phase M2c（改訂）: 新経路への一本化と旧経路の削除〔完了〕

**目的**: 経路を 1 本にし、旧レイアウト経路を消す。旧 M2b-6 をここへ吸収した。削除の前に、新経路の速さを旧経路の水準まで上げる仕事はしない（決定 A）。

| 段 | 内容 | 完了条件 | 状態 |
| :--- | :--- | :--- | :--- |
| **M2c-1** | **保持ヒープの内訳**: 新経路の `varied-list-2000` / `large-list-2000` の保持ヒープを段ごとに測る。段は DOM・`Style`・2 つの箱の木・断片木・描画用の木・`TreePainter` の記憶・表示リスト・新経路でも作っている旧経路の残骸。**判定規則**: 上限を超える分（53 − 46.7 MB）がレイアウト・箱の木・旧経路の残骸にあれば、切り替えの前に構造で直す。M3b で消える描画の中間（`Fragment`・`DisplayItem`・`TreePainter` の記憶・描画用の木）にあれば、上限を新経路の実測値に期限付きで置き直し（M3b の完了ゲートで 14.0 / 5.5 MB へ戻す）、M2c-2 へ進む | 内訳の表を §4 に記録し、判定を本表に書く | **完了**（2026-11-26。内訳は §4.1）。**判定**: `varied-list-2000` 52.6 MB の 74% は M3b で消える描画の中間（`TreePainter` の記憶 27.7・描画用の木 11.6）で、レイアウトと箱の木は計 5.2、旧経路の残骸は 0。よって上限を新経路の実測へ期限付きで置き直した（M3b で 14.0 / 5.5 MB へ戻す） |
| **M2c-2** | **既定の切り替え**: `UiView`・`StandaloneView` の既定を `V3` にする。MOD のクライアントが読む `layoutRoot()`（ルートの `:test` 3 件）を `boxRoot()` と断片木の問い合わせに移す。ゴールデン（`GoldenFrameTest`・`RealDocumentFramesTest`・`GpuBaselineTest`・`VkBaselineTest`）は新経路の出力から取り直す。取り直す前に、④-3 前半で分けた違いの 3 種（文字の項目の粒度・記録済みの旧経路の逸脱・新経路の欠陥）を人が確かめる。`costGate` の基準も新経路で取り直す | `memoryAndLatencyGate` の `latency:*`・`ab-inc-vs-full:*` を新経路で満たす（上限は現行の値。決定 B）・ヒープは M2c-1 の判定どおり・`:core:test`・ルートの `:test`・`ci-linux.ps1 -Full` が緑 | **完了**（2026-11-26。数値は §4）。既定を `V3` にした。新経路は `RELAYOUT` を読まず全再計算が増分を測っていたのを直し、本当の全再計算と比べて見つかった欠陥 3 件を直した（回帰カタログ M2c の 4 行）。ゴールデンは旧経路との違いが既知の 3 種だけと画像で確かめて Windows・Linux を取り直した。latency の 2 行は上限を超え、描画の中間（M3b で消える）と、旧経路も 1 スレッド・毎回違う値なら同じ 26 ms の再スタイルが原因なので、期限付きで置き直した（§4.1） |
| **M2c-3** | **旧経路の削除**: 下の「削除対象」を消す。新経路が使っている旧エンジンの静的なアルゴリズムと `LayoutBox.Display` は `core/layout/fragment`（または `core/box`）へ移す。`PaintBox` は `PaintView` だけが実装する型になるので `core.paint` へ移す。multicol は旧経路と一緒に一時的に失う（同梱の文書・WPT での使用は 0。M3c で戻す） | 下の「完了ゲート」 | **完了**（2026-11-26）。削除対象を全て消し、`:core` 本体を 130,142 → 108,115 行（−22,027 行・633 → 569 ファイル）にした。`LayoutBox.Display` は `core.box.Display`、`PaintBox`/`PaintRun` は実装と同じ `core.paint.tree`（`core.paint` へ置くと循環になる）、旧 `PaintOrder` の算出スタイルだけの値は `PaintFacts` へ移した。旧経路の 1 回だけの入口（`layoutDocument` など）は `FrameRender` に置き換えた。旧経路を相手にするテストは消し、仕様を確かめるテストは新経路へ移した（移植で見えた穴は下の表）。`ContextFlags`・`ChangeReads`・`Kernel` の計器も消えた |

**削除対象（M2c-3）**
- 型と経路: `EnginePath`・`LayoutBox`・`MeasureMemo`・`PlacementMemo`・`PlacementDeps`・`LayoutReads`・`MeasureReads`・`ReadKind`・`PaintStamps`・`PartialBuild`・`ChildWindow`・`ChangeReads`・`ReusableTable`・一時アダプタ `FragmentProjection`。
- 旧エンジン: `BlockLayoutEngine`・`FlexLayoutEngine`・`GridLayoutEngine`・`TableLayoutEngine`・`VerticalWritingEngine`・`LayoutEngine` ほか。
- 旧描画の駆動: `RenderPipeline`/`RenderTreeBuilder` の旧多段の分かれ道（`relayoutRootOnly`・`relayoutInPlace`・`repositionLayout`・`LocalPatches`・`InPlaceRelayout`）と `DisplayListBuilder`（`BoxItemPainter`/`TextRunPainter` は M3b まで残す）。
- 状態: レイアウト系の `Shortcut`/`EngineFlags` と、グローバルなエポック（`SHAPE_EPOCH`・`STRUCT_EPOCH`・`CONTAINER_EPOCH`・`PAINT_EPOCH`・`renderBoundary`・`MEASUREMENT_EPOCH`）。
- テスト: 旧経路を相手にする物差し（`LegacyParityTest`・`ProjectionPixelParityTest`・`PaintTreeParityTest` の旧描画との比較・`GeometrySweepTest`・`GeometryDeviationsTest` と `geometry-sweep-deviations.txt`・`WptReftestRatchetTest` の旧経路側。WPT は `WptV3BaselineTest` に一本化する）と、旧経路の仕組みだけを確かめるテスト。回帰カタログの該当行は、新経路で同じ種類の不具合を守るテストへ付け替える。

**凍結した例外（M3b-2 で全廃済み。新たな例外は置かない）**

| 例外 | 場所 | 規範との衝突 | 消す段 |
| :--- | :--- | :--- | :--- |
| 描く文脈の多欄照合（絶対位置・クリップ・`parentId`・キャンバスの役・ビューポート） | `TreePainter.Context` | 多欄の後追い照合（`PaintStamps` 型） | **削除済み（M3b-2）** |
| 子から親へ伝える使い回しの可否 | `TreePainter.build` の `reusable` | 純粋性の述語（`F_*` 型） | **削除済み（M3b-2）** |
| 台帳の祖先の照合 | `TreePainter.scrolled` | 文脈の絶対位置が祖先で変わらないまま中身の位置が変わる | **削除済み（M3b-2）** |
| 使い回さない部品の列挙（表・フォームの部品・svg・動画・スクロールに依る部分木） | `TreePainter`・`TreeStacking` | 例外の列挙が増えていく形 | **削除済み（M3b-2）** |

正しさの不具合は、使い回しをやめて作り直す側で直す。照合の欄や例外の種類は増やさない。

**完了ゲート（運用ルール 7 の 3 点）**
- 旧経路の型の参照 0（`ArchitectureHealthTest` に `LayoutBox`・`MeasureMemo`・`EnginePath` 等の型名の禁止を足す）。
- 削除 `≥ 10,000 行`（`:core` 本体 `≤ 120,000 行`）・レイアウト系 `Shortcut` 0・グローバルなエポック 0・一時アダプタ 0。
- 正しさ: 新経路の WPT `≥ 170/260`・`FrameOracle`・`IncrementalOracleTest`・3 者画素一致・`RealDocumentFramesTest` が緑。
- 性能: M2c-2 の条件を保ち、§4 を更新する。

**完了ゲートの結果（M2c-3）**: `memoryAndLatencyGate` OK・`costGate` OK（基準を取り直した）。 旧経路の型の参照 0（`ArchitectureHealthTest#legacyLayoutPathTypesStayGone` を足した）・削除 22,027 行（本体 108,115 行）・レイアウト系 `Shortcut` 0（33 → 16）・`EngineFlags` 43 → 24・グローバルなエポック 0・一時アダプタ 0。WPT は `WptV3BaselineTest` 1 本に一本化して 170/260（旧経路の一覧 `reftest-passing.txt` は削除）。`:core:test`・ルートの `:test` 緑。性能は §4。

**新経路の穴（M2c-3 の移植で見えた）**: 旧経路だけを相手にしていた仕様のテストを新経路へ移したら、M2c-2 の既定の切り替えから既定の経路にある穴が見えた（WPT・ゴールデンの範囲の外）。テストは `@Disabled` に G 番号を書いて残し、直したら外す。

| # | 穴 | 見つけたテスト | 状態 |
| :---: | :--- | :--- | :--- |
| G1 | インラインの中のブロック（CSS 2.1 §9.2.1.1）: 中のブロックが行の中の箱になり、その中身が描かれない（既定でインラインのカスタム要素がブロックを包む `<template component>` などで起きる） | `ComponentSlotTest#componentLaysOutAndScopesItsStyle`・`BlockInInlineTest` | **完了**（インラインの内容をブロックの前後の区間に割り〔`InlineSegment`〕、ブロックは持ち主の流れに置く。描画用の木では割れたインラインの下） |
| G2 | `subgrid` | `SubgridTest`（14 件） | **完了**（親が子の寄与を親のトラックへ回し、共有する軸の線の名前と寸法を `ConstraintSpace` の付加の欄〔表の枠線と同じ欄。値等価で鍵に入る〕で渡す。`GridSubgrid`） |
| G3 | multicol | `MulticolTest`（14 件。うち 12 件が新経路で落ちる） | M3c |
| G4 | `list-style-image` のマーカー | `BoxTreeTest#testListStyleImageReplacesTheMarkerText` | **完了**（画像の置換要素のマーカー〔`MarkerBoxes`〕） |
| G5 | `::backdrop` | `TopLayerTest#modalDialogPaintsBackdropBehindItself` | **完了**（箱の木が描画だけが読むスタイルとして持ち、`TreePainter` が Top Layer の箱の直前に描く） |
| G6 | `::first-line` の `text-transform` | `FirstLinePseudoElementTest`（1 件） | **完了**（1 行目の語を測るときと描くときに `FirstLine.transform`） |
| G7 | `min-width: 0` の入力欄の min-content の寄与（flex の自動の最小寸法） | `FlexMinContentTest#rowFlexItemDoesNotShrinkBelowTheSumOfItsItems` | **完了**（部品の min-content は確定した min-width。`ReplacedFlow`） |
| G8 | `position-try`: `flip-block` で入れ替えた余白を描画用の木が読まない（位置は正しい）・スクロールしたアンカーに追従しない／候補を選び直さない（スクロール量はレイアウトの入力でない） | `AnchorPositioningTest`（2 件）・`AnchorScrollFollowTest` | **完了**（余白は `PlacedFragment.margins`。スクロールは下の「G8 の後半の設計」のとおり: 追従は `PaintView.child` の平行移動〔`AnchorFollow`〕、選び直しは `V3Frame.settleAnchors` が覚えたスクロール量を取り直して台帳の `ANCHOR` で組み直す） |
| G9 | ブラウザとの差 2 件（子に原子を持つインライン要素の前で折り返さない・枠線を重ねる表のスクロール範囲） | `WebFidelityTest`（`known-divergences.txt` の M2c の行） | **完了**（台帳 11 行 → 0 行） |

**G8 の後半の設計（2026-11-26 確定・同日実装。CSS Anchor Positioning 1 §3.3・§6.5 に従う）**

調べて分かった現状: 新経路はアンカーを断片（スクロールの無い位置）から探し、スクロールは `PaintView.child` で原点の親がスクロールコンテナのときだけ引く。そのため、スクロールコンテナの外の流れの外の箱（`position: fixed` など）は、中のアンカーがスクロールしても**追従もしない**（候補の選び直しより前の穴）。さらに、スクロールだけのフレームはコンテナの中身を 1 つの節として動かして済ませるので（`TreeStacking.scrollsAsOneNode`）、外の箱は描き直されない。

仕様の形: `anchor()` は全てのスクロールコンテナが初期位置にあるものとして解き、**覚えたスクロール量**（remembered scroll offset）だけをずらす。覚えた量を取り直すのは**アンカーの再計算点**（箱を作り始めたとき・候補を決めるとき）だけ。その後のスクロールは、レイアウトの後の平行移動（default scroll shift = 今の量 − 覚えた量。既定のアンカーと同じスクロールの文脈の軸だけ）で追う。候補を決め直すのは、平行移動した箱が inset で縮めた包含ブロック（IMCB）からはみ出したときだけで、はみ出さない限り今の候補を保つ。

| # | 決めたこと | 理由 |
| :---: | :--- | :--- |
| 1 | **スクロール量は鍵に入れない**。追従は描画の平行移動（`PaintView.child` で sticky と同じ形で足す）。レイアウトが読むのは「覚えた量」だけ | 毎スクロールで包含ブロックが組み直しになり、スクロールだけのフレームの速い経路が死ぬ。仕様も追従は「レイアウトを要さない位置のずれ」と定める |
| 2 | **覚えた量はビューの履歴**（`V3Frame` が持つ「流れの外の要素 → スクロールコンテナごとの量」）。`StyleSource.history` と同じく照合の鍵ではない入力で、`RELAYOUT` をまたいで渡し、文書の入れ替えで捨てる。レイアウトへは**台帳の新しい種別 `ANCHOR`** で入る: 箱の木はその要素の箱だけを（スタイルは解き直さずに）作り直し、`BoxNode` が覚えた量を持つ | 足切りの入口は台帳だけ（§5）。`BoxNode ==` の鍵がそのまま効くので §2.3 の表の鍵は増えない。読み取り台帳（アンカーから依存する箱への逆引き）を作らない |
| 3 | **再計算点の判定は描画用の木を作った後**に 1 回: (a) 覚えた量が無い箱で、アンカーの上のスクロールコンテナの量が 0 でない、(b) 平行移動した余白の箱が IMCB からはみ出し、候補が 2 つ以上ある。当てはまる箱の覚えた量を今の量に置き、フレームだけの台帳に `ANCHOR` を載せて同じフレームでもう 1 回だけ組む | 取り直した直後は平行移動が 0 なので、同じ箱で判定が続かない（1 回で止まる）。1 フレーム遅れの位置を出さない |
| 4 | **レイアウトの出力**: `PlacedFragment.follow`（`AnchorScroll`。null 可）に、参照したアンカーの上のスクロールコンテナ・既定のアンカーの上のスクロールコンテナ（包含ブロックの下だけ）・追従する軸・使った覚えた量・選んだ候補の余白の箱と包含ブロックを持たせる。`Anchors` はアンカーを集めるときに通ったスクロールコンテナの列を覚える | はみ出しの判定は候補の判定と同じ規則（余白の箱が包含ブロックに収まるか。新経路の `AnchorPlacement` は IMCB でなく包含ブロックで判定する）。包含ブロックの辺はスクロールで動かないので、辺の印は要らない |
| 5 | **スクロールだけのフレーム**: 追従する箱が依るスクロールコンテナは 1 つの節として動かさない（`scrollsAsOneNode` は null）。`V3Frame` はその場合に null を返さず描く | 外の箱を描き直さないと、追従が描画に出ない |
| 6 | **仕様との差として残すもの**: 最後に成功した候補（§6.5.1）は、スクロール以外の変化（大きさの変化など）では持たない（毎回 基本 → 候補の順に選ぶ。今と同じ）。スクロールでは覚えた量が状態の役をするので仕様どおり「はみ出すまで今の候補を保つ」になる | 状態を持つ候補の選択は G8 の範囲の外。必要になったら別の項目にする |

実装で見つけた既存の欠陥: 中身を 1 つの節にまとめないスクロールコンテナ（追従される・下の inset の sticky を含むなど）をスクロールすると、描画の記憶が文脈の変わらない祖先を使い回して中身が古いスクロール量のまま出ていた。前回描いたときから位置が変わったコンテナを台帳に節つきの `SCROLL` で載せ、`TreePainter` がその祖先を使い回さないようにした（足切りの入口は台帳のまま。回帰カタログ M2c）。

テストの扱い: `positionTryFlipsOnScrollReposition` は 1 回だけのフレーム（`layoutDocument`）を 2 回呼ぶので、2 回目は箱を作り始めたとき（再計算点）にあたり、期待値（210 → 180）は仕様と同じ。そのまま有効にする。足すテスト: 候補の無い箱の追従（固定配置の箱がアンカーと一緒に動く）・状態を持つビューでの選び直し（100px のスクロールでは反転した候補を保って 110、220px でそれがはみ出して基本の候補の 60 へ戻る）・スクロールだけのフレームの画素・`RELAYOUT` をまたいだ増分と全再計算の一致・`FrameOracle`。各修正を戻す変異で落ちることを確かめ、回帰カタログへ 1 行足す。

移植の途中で新経路の欠陥を 2 件直した（回帰カタログ M2c の行）: rtl の包含ブロックで幅と余白が決まりすぎたブロックを右に寄せる（§10.3.3。包含ブロックの向きを `ConstraintSpace.CONTAINER_RTL` で渡す）・インライン要素の中の絶対配置の子に位置の無い写しを作っていた（CSSOM の矩形が 0 になる）。M2c-3 の作業で足した欠陥（文書を開いた直後のフレームがもう一度全体を組み直す）も同じ版で直した。

---

### 3.3 Phase M3a: MOD の旧レンダラの撤去と描画の一本化〔完了 2026-11-26〕

平坦な `DisplayItem` を廃止する M3b の前に、MOD の旧レンダラ（`client/renderer` 27 ファイル・9,703 行。`useCoreBackend = false` が既定）を撤去し、MOD の面をコアの描画だけで描くようにした。

| 段 | 内容 | 状態 |
| :--- | :--- | :--- |
| Step 1（コアで描けなかった中身） | ①**ホストの中身の口 `HostTextures`**（`core.paint.resource`。GPU のバックエンドだけが、画像・シーン・文字の項目で聞く）: Minecraft だけが描ける中身（`item:`・`entity:`・`player:`・`<mc-scene>`・ビットマップ書体の文字）を `MinecraftHostTextures` が面を描く前に項目ごとの FBO へ描き、コアが項目の中身として重なりの順どおりに貼る（後から重ねる形と違い、ポップアップがアイテムを隠す）。共有コンテキストの同期は Minecraft 側のフェンスを `LwjglGlRenderBackend.waitForHost` で待つ ②**キャレット**: `StandaloneView` の選択・キャレット・IME の項目を `TextFieldOverlay`（`common`）へ出し、MOD は自分の編集の状態で同じ計算を使う（`SurfaceEditOverlay`。textarea のクリック位置も同じ） ③ツールチップ（`SurfaceTooltip`）・遠距離の 1 色の塗り（`SurfaceRenderer`）は旧レンダラから切り離した | **完了** |
| Step 2（一括撤去） | `DisplayItemDrawer`・`LayerCompositor`・`WebSdfShader`・`TextElementRenderer`・`OutlineTextRenderer`・`BackgroundRenderer`・`ControlElementRenderer`・`MediaElementRenderer`・`RoundedRectRasterizer`・`RasterTextures`、設定 `useCoreBackend`・`useSdfShader`、`CoreGpuSurface` の `enabled`・`BROKEN`・`drawable` のフォールバック、動画のフレームの GPU への転送（コアは CPU の画素を読む）を削除。`CoreGpuSurface` → `MinecraftHostSurface`、`McMediaImages` → `MinecraftMediaBridge`。`SurfaceStyle` から旧レンダラだけの指定（奥行きの積み方・距離の間引き・層の平坦化）を外した | **完了** |

**完了ゲートの結果**: 旧レンダラの型の参照 0（`ArchitectureHealthTest#legacyModRendererStaysGone` を足した）・切り替えの分岐 0・フォールバック 0（描けなかったフレームは何も描かずセッションごとに 1 度記録する）。`client/renderer` は 9,703 → 4,667 行（MOD 全体 26,444 → 21,364 行）。うち**面を描く経路**（`MinecraftHostSurface`・`MinecraftHostTextures`・`MinecraftMediaBridge`・`SurfaceRenderer`・`SurfaceStyle`・`PaintRequest`・`SurfaceTooltip`・`SurfaceEditOverlay`）は計 1,277 行で ≤ 1,500 を満たす。残りの 3,390 行は描画ではない部品（`WebMediaManager` の取得と復号 1,312・HUD の置き場所 618・書体の登録 358・`<mc-scene>` の状態・ホスト〔ブロック・ホログラム・アイテム〕の姿勢・`SafeHttpClient`）で、パッケージの整理は M4（MOD の循環の解消）で行う。実機: 下の手順で旧レンダラと見比べ、全種別が描けることと欠落 0 件を人が確かめた。旧レンダラとの画素差の基準（≤ 0.1%）は運用ルール 6 に従って使わない（コアは別の書体のラスタライズで、旧レンダラ自身の欠陥〔語間の空白が詰まる・ブロック面の `<mc-scene>` が描かれない・`font-family: minecraft` がシステムの書体〕も直った）。

**実機の確かめ方**: 一時的なデータパック（`efl_m3a`。ブロック面・ホログラム・HUD・GUI〔`m3a_check`: アイテム・個数・エンティティ・プレイヤーの頭・流体・効果・`<mc-scene>`・ビットマップ書体・入力欄・textarea・`title`〕・showcase・media_test を順に開いて `say CAPn`）と、Minecraft の窓を `PrintWindow` で撮るスクリプト（入力欄・textarea のクリックとホバーは `PostMessage`）。見つけた欠陥: ①光沢のあるアイテムで頂点の置き場が 1 つだと "Not building!"（描き方ごとの置き場を持つ） ②親相対の項目で textarea の文字が切り落とされる（コアの欠陥。回帰カタログ M3a） ③GUI の面を GUI の倍率で描いて最近傍で縮めていたため、細い線の列が落ちる（面は姿勢の倍率 × GUI の倍率で描く。ビットマップ書体の字形は 1 画素より細い画素を持つので、文字のテクスチャは 2 倍で描いて線形で縮める）。

**残したもの・差**: ツールチップは旧レンダラと同じく Minecraft の書体を面の 1 px = 1 単位で描くので、1 CSS px が画面の 1 画素の GUI では小さい（旧来から。M4 で入力の層と一緒に直す）。textarea の行番号・Lua の色分け（`data-line-numbers`・`data-syntax`。同梱の文書の使用 0）とキャレットの点滅・入力欄の横スクロールは旧レンダラだけの機能で、コアの textarea・入力欄と同じ見た目になった。ワールドの面は旧レンダラの明るさ（`packedLight`）を使わない（コアの経路は以前から発光扱い）。

---

### 3.4 Phase M3b（改訂）: `DisplayScene` と描画の作り直し〔進行中。M3b-1・M3b-2・M3b-3 完了〕

平坦な `DisplayItem` に潰してから `PaintSceneDriver` で階層を復元する往復をやめる。同時に、M2c で凍結した描画の例外を全て消す。

**段（2026-11-27 に分けた）**: `DisplayItem` の参照は `:core` 本体 59・テスト 94・MOD 50 ファイルあり、1 回では置き換えられない。入力側（DOM の写し）→ scene の生成 → 問い合わせ → バックエンドの順に、各段で置き換えた型を同じ段で消す。scene からバックエンドへの一時アダプタだけは M3b-4 まで残す（§2.3 の期限付きの例外に載せてから置く）。

| 段 | 内容 | 状態 |
| :--- | :--- | :--- |
| **M3b-1** | **描画は DOM を読まない（決定 F）**: 描画が読む DOM の値（属性・textarea の中身・状態の擬似クラスの規則が掛かるか）を `BoxTreeCompiler` が `BoxNode.values`（`DomValues`）に写す。値が前回と同じなら前回の参照、レイアウトの入力の照合には入れない（属性だけの変化はレイアウトの入力の箱を変えない）。`class`・`id`・`style` は写さない | **完了**（2026-11-27）。`core.paint` の DOM の読み取り 0（`ArchitectureHealthTest#paintDoesNotReadDomValues`）。`TreePainter` は台帳に載った節の祖先を数えず、読むのはスクロールしたコンテナの祖先だけ（凍結した例外を縮めた）。`PaintTreeBuilder` はシートを受け取らない。`PaintDomValuesTest`（属性・入力の値・`checked`・textarea の中身の変化がゼロから組んだ表示リストと一致・変わった枝だけを作り直す）は写しを前回のまま返す変異で落ちる |
| **M3b-2** | **`DisplayScene` と `SceneEmitter`**: `(断片, 最新の箱)` → 親相対・`int nodeId` の `SceneLayer` の木。記憶は `(断片 ==, 最新の箱 ==)`・`FlowChunk ==` だけ。描画用の木（`PaintTreeBuilder`・`PaintNode`・`PaintView`）・`TreePainter`・`TreeStacking`・`Fragment`/`FragSeq` を吸収して消し、凍結した例外を全て消す。スクロール・sticky・アンカーの追従は `DynamicSceneState` | **完了**（2026-11-27）。旧描画用の木・`TreePainter` 等 10 本を削除し、`core.paint.scene`・`SceneDisplayListAdapter` へ移行（`:core` −2,168 行・凍結例外 0・`legacyPaintTreeTypesStayGone`）。保持ヒープ `53.2→50.8 / 13.8→12.6 MB`、`DisplaySceneTest`・`SceneEmitterIncrementalTest` 追加・変異 2 種を確認 |
| **M3b-3** | **問い合わせ**: 当たり判定・CSSOM・フォーカス・インスペクタを断片木と scene から答える。`HitTestIndex`・`PaintBox` を消し、MOD の消費者（イベント・フォーカス・入力欄）を移す | **完了**（2026-11-27）。`SceneHitTest`（付録 E の逆順・チャンクの包含矩形で `O(log(N/32))`・外に出た sticky の回収）・`SceneQuery`/`SceneLocation`（位置・寸法・索引）・`SceneHit` へ移し、`HitTestIndex`・`UIHitResult`・`SceneBox`・`PaintBox`・`PaintRun` を削除（`legacyQueryTypesStayGone`）。`SceneHitTestTest`・`SceneQueryTest` を追加。ホイールの確保は作業用の値をビューの文脈で使い回して `costGate` の基準内（`voidwave` scroll 1,049 B）。`realdocs` は当たりの列だけ更新（float の幾何と旧 `Math.round` の差。項目・画素は一致） |
| **M3b-4** | **バックエンドが scene を直接描く**: `render(DisplayScene, DynamicSceneState, nowMs)`・`DrawOp`・チャンクのカリング。アニメーションの状態は `int nodeId` を鍵にする。`DisplayItem` ほか下の削除対象と一時アダプタを消す | 未着手 |
| **M3b-5** | **完了ゲート**: 下の完了ゲート（ヒープ・latency の上限を戻す・`Shortcut`/`EngineFlags` 0）と §4 | 未着手 |

- **データモデル（`core/paint/scene`）**
  ```java
  public final class DisplayScene {            // 旧 DisplayItem[] + Fragment + PaintSceneDriver を置き換える
      public final SceneLayer root;
      public final Int2ObjectMap<SceneLayer> byNodeId; // 動的オフセット・当たり判定・スクロール用の O(1) 索引
  }
  public final class SceneLayer {
      public final int nodeId;                 // 文字列 ID なし
      public final float x, y, w, h;           // 親相対座標（unstuck / スクロール 0 基準）
      public final float scrollClipW, scrollClipH; // スクロールしない要素は NaN
      public final Transform2D transform;      // 固定の変形（アニメーション中は外の表で上書き）
      public final ClipShape clip;             // 角丸クリップ・clip-path
      public final LayerEffects effects;       // opacity / filter / backdrop-filter / mix-blend-mode / mask
      public final StickyConstraint sticky;    // position: sticky のときだけ非 null
      public final SceneLayer[] stickyDescendants; // スクロールコンテナだけ（カリングの外の sticky の回収）
      public final DrawOp[] ops;               // この節自身の描画の命令（背景・枠線・影・文字のグリフ列・画像）
      public final SceneChildren children;     // 32 子単位のチャンク（重なりの順・包含矩形つき）
  }
  public final class SceneChildren { public final SceneChunk[] chunks; public final boolean monotonicY; }
  public final class SceneChunk { public final float minX, minY, maxX, maxY; public final SceneLayer[] layers; }
  ```
- **作り方（2026-11-26 改訂）**
  - `SceneEmitter` は `(LayoutFragment, 最新の BoxNode)` から直接 `SceneLayer` を作る。描画用の木（`PaintTreeBuilder`・`PaintNode`・`PaintView`）・`TreePainter`・`TreeStacking` はこれに吸収して消す。
  - 箱 1 つの描画（`BoxItemPainter`・`TextRunPainter`）は `DrawOp` を出す形に変える。重なりの規則（旧 `PaintOrder` の移植）は `SceneChunk` の並べ方として 1 か所に持つ。
  - 記憶は `(断片 ==, 最新の箱 ==)` と `FlowChunk ==` だけ。座標が親相対なので、前の兄弟が伸びただけの部分木も同じ参照で使い回す。
- **描画は DOM を読まない（決定 F）**〔M3b-1 で完了〕: 描画が読む DOM の値（入力の値・`placeholder`・`checked`・画像の参照・textarea の中身・状態の規則が掛かるか）を、`BoxTreeCompiler` が `BoxNode.values` に写す。値の変更（属性・入力）は既存の台帳の `ATTR`・`TEXT` で箱の作り直しとして届く（新しい種別 `VALUE` は要らなかった。入力の値は属性に書かれる）。画像・`<canvas>`/動画の中身は項目の参照（URL・キー）だけを持ち、到着はホストの全体の作り直し（今と同じ）。`ArchitectureHealthTest#paintDoesNotReadDomValues` が `core.paint` の DOM の読み取りを禁じる。
- **動的な値は外に置く**: スクロール量（`ScrollState`）・補間アニメーション（`AnimatorState`。`int nodeId` を鍵にする）・sticky のずれ（`StickyConstraint`）は scene を書き換えず、バックエンドが走査のときに `DynamicSceneState` から足す。
- **可視範囲のカリング**: `SceneChunk` の包含矩形で見えないチャンクを飛ばす。`monotonicY` の長い流れは二分探索する（`O(log(N/32))`）。unstuck の座標で外に出た sticky は、スクロールコンテナの `stickyDescendants` から拾う。
- **単方向化**: `GpuRenderBackend`（RHI）と `SoftwareRenderBackend` は `render(DisplayScene, DynamicSceneState, nowMs)` を直接受ける。GPU が対応しない稀なパスは、`SceneEmitter` の側でビットマップ（`DrawOp.Image`）にしてから流す。
- **問い合わせ**: 当たり判定・CSSOM・フォーカス・インスペクタは、断片木と scene を手前から奥へ直接たどって答える。`HitTestIndex`・`PaintBox` は廃止する。
- **削除対象**:
  - 出力と旧描画の層: `DisplayItem`・`PaintCell`・`RenderHints`・`ImmutableItemList`・`AbsoluteItems`・`FragmentPaint`・`Fragment`・`FragSeq`・`PaintOrder`・`InkState`・`InkBounds`・`ItemDiff`・`StickyShift`・`ScrollContentInfo`・`PaintSceneDriver`・`RetainRanges`・`RangeBounds`・`HitTestIndex`
  - M2c の新しい描画: `core.paint.tree` 一式・`TreePainter`・`TreeStacking`・`PaintBox`
  - バックエンドのつなぎ: `CpuFallback.renderOnto` の逆転したコールバックと `GpuSink` の往復
  - フラグ: `Shortcut.java`・`EngineFlags.java`
- **完了ゲート**:
  - 構造: 中間表現 3 段（§2.3 の期限付きの例外 0）・`Shortcut`/`EngineFlags` `0`・描画の DOM の読み取り `0`・L4 のコードの規模 30% 以上の削減。
  - ヒープ（M2b の中間目標をここへ移した）: `varied-list-2000 ≤ 14.0 MB`・`large-list-2000 ≤ 5.5 MB`。
  - 性能（描画を含む 1 フレーム）:
    - 8,000 行リストの 1 件挿入 `≤ 1.0 ms`
    - `showcase` の幅の変更で scene を作る時間 `0.273 → ≤ 0.08 ms`
    - `varied-list-2000 fontsize`（4 スレッド・実書体）`10.76 → ≤ 5.0 ms`（144Hz の予算 `6.94 ms` を達成）
    - hover・文字の変更は `ab-inc-vs-full` の上限を締め直す
    - M2c-2 で期限付きで置き直した latency の上限を戻す（`varied-list-2000:fontsize ≤ 46.6 ms`・`large-list-8000:child ≤ 16.5 ms`。1 スレッド・FALLBACK）
  - 正しさ: 3 者画素一致 100%・`FrameOracle`・新経路の WPT `≥ 170/260`。8,000 行のスクロールで見えないチャンクを飛ばすこと・sticky が `FrameOracle` と 100% 一致すること。

---

### 3.5 Phase M3c: multicol（ブロックの断片化）〔未着手。前提: M3b 完了〕

M2c-3 で旧経路と一緒に一時的に失う multicol を、新経路で作り直す。`LayoutFragment` に「1 つの要素の箱を列ごとの断片に分ける」断片化を足し、列ごとの断片を scene の `SceneLayer` として出す。ゲート: WPT の css-multicol の文書を参照画像の集合に足し、`WptV3BaselineTest` のラチェットで固定する。§2.3 の段は増やさない。

---

### 3.6 Phase M4: L5 ホスト層の純化と MOD の循環の解消

- `UiView`（1,933 行）・`StandaloneView`（1,101 行）を次の 3 つに分ける。
  - **`EngineView`**（≤ 400 行）: DOM・スタイルシート・`ChangeLedger`・`BoxTreeCompiler`・`LayoutCache`・`DisplayScene`・`ScrollState` を持つ 1 画面の純 Java の核。
  - **`InputController`**（≤ 500 行）: 当たり判定・ポインタ・フォーカス移動・IME/テキスト編集（`TextEditing` と MOD `TextareaEditor` を統合）・範囲選択・`<select>` のポップアップ。
  - **`StandaloneHost`**（≤ 250 行）: 窓の座標とクリップボードの接続だけ。
- MOD の `SessionContext` / `EnderScreenDelegate`（1,298 行 → ≤ 450 行）から Minecraft のサーバー同期の残骸（`serverManaged`・`headlessState`・`lastSyncRequestTime`・`shouldRequestSync()`・`explicitSize`・`applyOverrides()`）を剥がす。
- MOD の 5 組のパッケージの循環（`client ↔ client.gui ↔ client.renderer ↔ client.renderer.surface`）を一方向にする。
- **完了ゲート**: L5 の共通ホストが計 `≤ 1,200 行`・MOD の循環 `0`・`runGameTestServer` と実機スクリーンショットの回帰が全て合格。

### 3.7 Phase M5: DOM の整理と 1,000 行超のクラス 0

- `DocumentArena` が `HtmlNode`（1,398 行）を写して持つ二重構造を解く。`HtmlNode`/`HtmlElement` を、節の基本構造・属性・DOM API・イベントの配送に分ける。セレクタの高速化に要るもの（atom 化したタグ / id / class・Bloom のビット）だけを `SelectorIndex` として独立させる。
- **完了ゲート**: `:core` と MOD の全体で 1,000 行超のクラス `0`（`ArchitectureHealthTest` で強制）・`DocumentArena` の二重同期 `0`・分けたクラスは各 `≤ 500 行`・全体の保持ヒープ `varied-list-2000 ≤ 12.0 MB`・`large-list-2000 ≤ 4.5 MB`。

### 3.8 Phase M6: 品質ゲートの最終固定と配布（v2.0.0 GA）

- `ArchitectureHealthTest` の行数の上限を **全クラス `≤ 600 行`** のハードゲートにする（例外は生成物・純データ表などの明示した一覧だけ）。グローバルな可変の static を `0` にする。
- Linux の全件（`scripts/ci-linux.ps1 -Full`）を通し、公開 API の署名（`ApiSignatureTest`）を確定し、`:core` を単体のライブラリとして Maven Central / GitHub Packages に公開する。

---

### 3.9 独立トラックの詳細（Track P1〜P5）

#### 群 A. 性能

- **Track P1: コールド起動の短縮**（前提なし）
  - **P1a 書体の目録〔完了 2026-11-25〕**: 起動ごとの全フォルダの走査を、ディスクの書体の目録（`FontCatalog`。走査は `core/text/font/FontScanner`）に置き換えた。書体の読み込み `243 → 24 ms`、実書体での起動 `786 → 718 ms`（交互 A/B・中央値）。検証は `FontCatalogTest`。
  - **P1b JVM の冷えた起動**（計測の道具 `:core:coldStartAb` まで完了）: JDK 25 + AOT キャッシュ（JEP 483）で `540 → 228 ms`（`0.42x`）。旧 `ColdStartMain` は書体なし（`FALLBACK`）で測っていた。ゲートはコールド起動 `≤ 200 ms` で、M2c-3 の後に判定する。
- **Track P2: 並列レイアウトと描画スレッドの非同期パイプライン**（前提: M3b 完了）: 不変の断片木と scene の上で、部分木単位の並列レイアウト（`LayoutTaskPool`）と、論理スレッドと描画スレッドの分離を行う。ゲート: 実文書の初回構築（`voidwave.html open` 等）`9.15 → ≤ 5.5 ms`。

#### 群 B. ホストと配布

- **Track P3: 窓と描画の直交分離・標準 Java 2D/AWT 窓ホスト**（前提: M4 完了を推奨）: `WindowHost` SPI と、AWT の窓ホスト（`JFrame` + `BufferStrategy` + 損傷矩形の Blit + `InputMethodRequests` による OS の IME）を整え、`:core-glfw` と直交に選べるようにする。ゲート: `:core` 単体で 144Hz の起動・3 つのバックエンドの直交切り替えのテストが全て合格。
- **Track P4: リポジトリの Core 主体化・分割・独立名への改名**: リポジトリのルートは NeoForge のプロジェクトで、名前も MOD 由来である。
  - **P4-1（M2c の完了の後）**: 同じリポジトリの中で構成を変える。
    1. MOD を `mod/` サブプロジェクトへ移し、ルートを NeoForge 非依存の素の Gradle にする。
    2. MOD のテストが借りる `:core` のテスト出力を `testFixtures` にする。
    3. ルートの文書の主役を Core にする。
    - ゲート: 全ゲート緑・`:core` 系だけのビルドで NeoForge の設定処理 `0`。
    - **結果（2026-11-27 完了）**: ①ルートの `build.gradle` はプラグインなし（`wrapper` の設定だけ）。MOD は `mod/`（`build.gradle`・`gradle.properties`〔Minecraft・NeoForge・MOD の値〕・`src`・`run/`）。エンジンの座標は `engine_group_id`/`engine_version`/`engine_artifact_prefix`（ルートの `gradle.properties`）。`org.gradle.configureondemand=true` で、`:core:test` は root と `:core` 系だけを評価し `:mod` を評価しない（CI の build に検査の段を足した） ②`:mod` は `testFixtures(project(':core'))` だけを借りる（`GoldenFiles`・`TestBoxes`・`WptHarness`/`WptBehaviorTest`・`TransformParseTestBridge` と WPT の既知差分の台帳。公開物には載せない）。テストの資源はコアのもの（WPT・忠実度・書体・パーサのゴールデン・`machine_ui.html`）を `core/src/test/resources`、MOD だけのもの（表示リストのゴールデン・Chrome の矩形・サーバーの基準値）を `mod/src/test/resources` へ分けた。全テストの作業ディレクトリはリポジトリ直下 ③README・ARCHITECTURE・CONTRIBUTING・docs を `:mod` の構成に合わせ、`mod/README.md` を置いた。続けて文書を置き場で分けた: ルートは README・CONTRIBUTING・CLAUDE・ライセンスだけ、エンジンの文書（ARCHITECTURE・BENCHMARK・FIDELITY）は `docs/`、計画書と ARCHIVE は `docs/plan/`（旧 `core/archive/` は `docs/plan/archive/`、MOD 時代の旧 `REFACTORING_PLAN.md` は `archive/00`）、MOD の文書（導入・Headless State・ハイブリッド・コマンド・`COMMAND_GUIDE`・`TEST_SPEC_*`）は `mod/docs/`。公開物の出力先は `build/repo/`。`DocConsistencyTest` は `docs/`・`mod/docs/` の全体（`docs/plan/archive/` は原文の保存なので除く）のリンクを見る
    - **P4-2 へ持ち越す結合**（別リポジトリにすると切れるもの。今は同じリポジトリなので動く）: コアのテスト・計測が MOD の同梱の文書（`mod/src/main/resources/data/enderframelib/enderframe_ui`。showcase・voidwave ほか）を読む（約 20 ファイル。文書をコアの資料へ移す）・`ArchitectureHealthTest`/`PortabilityRatchetTest` などが MOD のソースを走査する（MOD 側の検査へ移す）・`WptManifestTest` が MOD の uievents の移植のソースを読む（M4 で uievents を `:core` へ）・MOD の `PacketBandwidthTest` が `core/src/test/resources/benchmark/machine_ui.html` を読む
  - **P4-2（M4 完了・API 確定の後。M6 と同時）**: エンジンを独立名へ改名する（パッケージ・`groupId`）。
    1. `git filter-repo` で履歴付きの別リポジトリへ切り出す。
    2. MOD は公開版に依存させる（同時に触るときは `includeBuild`）。
    3. CI はエンジン側へ移す。
    - ゲート: エンジン側のソースに `enderframelib` の名前 `0`。
    - 名前の候補（2026-11-25 の Web 検索。商標と GitHub の Organization 名は未確認なので、確定の前に再確認する）: **Weft**（候補）・**Quire**（候補）・**Facet**（次点）。Vellum（Minecraft 用の HTML の GUI ライブラリ「Vellum GUI」と重なる）と Tessera（ConsenSys の Java 製品と重なる）は不可。

#### 群 C. 汎用ツールキット化

- **Track P5: JavaFX と並ぶ汎用 UI 基盤へ**（前提: P5f の測定基盤は随時。それ以外は M6 完了。P5d は P3 も）。順序は P5a → P5b → P5c → P5d で、P5e・P5f は並行して進める。
  - **P5a アクセシビリティ**: DOM と ARIA から、役割・名前・状態のアクセシビリティツリーを不変の木として作る。それを OS の支援技術（Windows UI Automation・macOS NSAccessibility・Linux AT-SPI）へ出す。
  - **P5b Java 側の状態バインディングと部品モデル**: 型付きスタイル API（`01edc0dc`）を延長し、Java の状態と画面を結ぶ。
  - **P5c 高機能部品**: 仮想化した一覧・表・木（M3b のチャンク単位のカリングの上に作る）・日付選択・分割ペイン。ゲート: 10 万行の表のスクロールで 144Hz。
  - **P5d OS 統合**（P3 の AWT 窓ホストの上）: ネイティブのメニュー・ファイルダイアログ・ドラッグ＆ドロップ・クリップボードの形式・複数窓。
  - **P5e 複雑な文字体系**: アラビア文字・インド系文字の字形処理とフォールバックの到達度を棚卸しして埋める。ゲート: 該当する WPT と参照画像テストをラチェットに足す。
  - **P5f 比較測定と公開物**: 同じ画面を JavaFX と Compose Desktop でも作り、起動・保持ヒープ・フレーム時間を交互 A/B で比べる。比較の主張と公開は M6 の後に行う。
  - **完了ゲート**: 業務アプリ相当の見本（フォーム・表・木・メニュー・ダイアログ）が Windows / macOS / Linux でスクリーンリーダーから操作でき、JavaFX・Compose との交互 A/B の比較表を公開している。

---

## 4. KPI ダッシュボード

時間の主張は交互 A/B の中央値だけ（絶対値はセッションで ±40% ずれる）。「現在」の列は作業のたびに上書きする。

| 軸 | 指標 / シナリオ | M0 着手前（2026-11-24） | 現在（2026-11-27。M3b-3 の後） | 目標（達成フェーズ） |
| :--- | :--- | :---: | :---: | :---: |
| **構造** | `:core` 本体の行数・ファイル数 | `113,195 行`・515 | **`107,902 行`・588**（M3b-3 で `HitTestIndex`・`SceneBox`・`PaintBox` ほか 5 本を削除し、`SceneHitTest`・`SceneHitGeometry`・`SceneQuery`・`SceneLocation`・`SceneHit`・`HitScratch`・`UiHitSupport`・一時の `SceneAdapterBox` を足して +604 行。M3b-2 は −2,168 行） | `≤ 120,000 行`（M2c。**達成**）→ M3b 以降は各フェーズで減らす |
| **構造** | 経路の数 | 1 | **1**（M2c-3 で `EnginePath` と旧経路を削除） | **1**（M2c。**達成**） |
| **構造** | 中間表現の段数（DOM の後〜バックエンドの前） | 6〜7 | **3 段 + 一時アダプタ**（箱の木 → 断片木 → `DisplayScene`、M3b-4 まで一時 `SceneDisplayListAdapter` → `DisplayItem`。問い合わせは M3b-3 から scene を直接たどる） | **3**（M3b-4） |
| **構造** | 増分・キャッシュ専用のコードの規模 | 42 ファイル・`6,360 行` 相当 | `ChangeLedger` 149 ＋ `BoxTreeCompiler` 560 ＋ `LayoutCache` 183 ＋ `SceneEmitter` 504 行（凍結例外 `TreePainter` の記憶は削除済み） | **`≤ 1,000 行`**（M3b） |
| **構造** | `LayoutBox` / `ComputedStyle` / `UiView` の行数 | `1,785` / `2,095` / `2,005` | **削除** / `451` / `1,383`（M3b-3 で当たり判定の文脈を `UiHitSupport` へ出した） | 削除（M2c。**達成**）/ `≤ 450`（M5）/ 解体（M4） |
| **構造** | 1,000 行超 / 600 行超のクラス（`:core` 本体） | `19` / `55` | **`11` / `37`** | `0`（M5）/ `0`（M6） |
| **構造** | `Shortcut` / `EngineFlags` | `35` / `45`（M0 で凍結） | `16` / `24`（レイアウト系 0。残りは GL・ソフトウェア描画・シートの雛形の切り替え） | レイアウト系 0（M2c。**達成**）→ `0` / `0`（M3b） |
| **構造** | `static volatile` / `ThreadLocal` | 約 `50` / `27` | `40` / `14`（`Atomic`/`LongAdder` 18・共有エポック 0。当たり判定の作業用の値はビューの文脈が持つ） | グローバルな可変の static `0`（M6） |
| **構造** | MOD の旧レンダラ（`client/renderer`） | `9,369 行` | **旧レンダラ 0**。面を描く経路は `1,277 行`（§3.3） | `≤ 1,500 行`（M3a。**達成**） |
| **構造** | リポジトリの構成（`:core` 系だけのタスクで設定する NeoForge のプロジェクト） | ルートが NeoForge の MOD | **0**（P4-1。`mod/`〔`:mod`〕分離） | `0`（P4-1。**達成**）→ P4-2 |
| **性能** | `large-list-8000 child`（1 件挿入） | `7.89 ms` | 描画を含む **`23.04 ms`**（M3b-3 後のゲート。M3b-4 の前の上限 57.85 ms） | 描画を含めて **`≤ 1.0 ms`**（M3b） |
| **性能** | hover / 文字の変更の 1 フレーム（showcase） | — | `ab-inc-vs-full` は `0.292x` / `0.203x`（上限 `0.900x` / `0.980x`）。一時アダプタ無記憶のため M3b-4 まで毎回 `DisplayItem` を生成 | `ab-inc-vs-full` の上限内（M2c。**達成**）→ 締め直す（M3b） |
| **性能** | `varied-list-2000 fontsize`（4 スレッド・実書体） | `10.76 ms` | ゲートの latency（1 スレッド・FALLBACK）は **`70.21 ms`**（M3b-3 後。上限 157.7 ms） | **`≤ 5.0 ms`**（M3b） |
| **性能** | `showcase` 幅の変更（`resizeexact`・GL） | `0.427 ms`（表示リストが 66%） | ゲートの latency（FALLBACK）`1.70 ms`。GL の実測は未計測 | **`≤ 0.20 ms`**（M3b） |
| **性能** | `voidwave.html open` | `9.15 ms` | ゲートの latency（FALLBACK）`16.41 ms`。`costGate` の確保量は `13.02 MB`（M3b-3 で不変） | **`≤ 5.5 ms`**（M3b〜M5 + P2） |
| **性能** | コールド起動（`showcase`） | `546 ms`（書体なし） | 書体の読み込み `243 → 24 ms`（P1a）・JDK 25 + AOT `540 → 228 ms`（P1b） | **`≤ 200 ms`**（P1b） |
| **メモリ** | 保持ヒープ `varied-list-2000` / `large-list-2000` | `35.96` / `9.63 MB` | **`50.70` / `12.59 MB`**（M3b-3 後のゲート。M3b-2 の `50.75`/`12.57` と同等。内訳は §4.1） | **`≤ 14.0` / `≤ 5.5 MB`**（M3b）→ **`≤ 12.0` / `≤ 4.5 MB`**（M5） |
| **正しさ** | WPT 参照画像比較 | `160 / 260` | **`170 / 260`**（一覧は `reftest-passing-v3.txt` の 1 つ。旧経路の一覧は削除） | `≥ 170 / 260`（M2c 以降） |
| **正しさ** | 3 者画素一致・`FrameOracle` | 緑 | 緑（2026-11-27 M3b-3 の後。`:core:test`〔2,811 件〕・`:core:timingTest`・`:mod:test`〔452 件〕・`memoryAndLatencyGate`・`costGate`〔56 本。全行で `drawn`/`elements`/`items` 一致、確保の減った `machine_ui` scroll 2 行だけ基準を更新〕・`heapBreakdown` OK。`realdocs` のゴールデンは当たりの列だけ更新） | 緑を保つ |

### 4.1 新経路の保持ヒープと時間の段ごとの内訳（M3b-3。2026-11-27）

ヒープは `:core:heapBreakdown`（`V3HeapBreakdownMain`。ゲートと同じ文書・フレームを回し、出力に近い段から参照を外して GC 後の減りを測る。共有分は先に外した段に入る）。時間は M2c-1/M2c-2 のシナリオ別内訳（`StageProfiler`・FALLBACK・1 スレッド）。

| 文書 | 全体 | 表示リスト・一時アダプタ（M3b-4 で消える） | `DisplayScene`（`SceneLayer`/`SceneChunk`） | 断片木とレイアウトの記憶 | 箱の木と `Style` | 共有・GC 差 |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `varied-list-2000`（M3b-3） | **50.20 MB** | `24.53 + 0.25` | `12.13` | `1.35` | `3.94` | `8.00` |
| `large-list-2000`（M3b-3） | **12.74 MB** | `5.31 + 0.13` | `3.01` | `0.49` | `2.03` | `1.78` |

| シナリオ（M2c-2 参考） | 1 フレーム | 箱の木 | レイアウト | 描画用の木 | 表示リスト | 表示項目の数（旧経路） |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `varied-list-2000:fontsize` | 79 ms | 26.3 | 10.2 | 17.6 | 22.6 | 97,886（652） |
| `large-list-8000:child` | 31 ms | 0.26 | 0.06 | 5.4 | 22.6 | 80,012（462） |

M3b-2 で `TreePainter` の記憶と描画用の木を `DisplayScene` に置き換え、一時アダプタ（`SceneDisplayListAdapter`）は記憶を 0（0.25 MB）にした。M3b-3 の問い合わせは記憶を持たないので内訳は M3b-2 と同じ（CSSOM の索引は要求されたときだけ作る）。残る最大成分は M3b-4 で廃止する `DisplayItem` 列・`PaintCell`・`RenderMeta`（`varied-list-2000` 24.47 MB）で、M3b-4 の直接描画とチャンクカリングで目標 14.0 / 5.5 MB 以下へ落とす。
`fontsize` の箱の木（全要素の再スタイル 26 ms）は新経路の欠陥ではない: 旧経路も 1 スレッドで毎回違う font-size に変えると再スタイル 26.0 ms・測定 27.7 ms になる（ゲートの旧経路 21.8 ms は 16px / 15px の交互で旧経路の記憶が当たる値。旧経路の再スタイルを 4 スレッドで並列に解くのは P2 の範囲）。

---

## 5. 運用ルール（構造の崩壊とパッチ地獄に戻らないための 8 原則）

1. **優先順位表は §3（本流 M0〜M6 と独立トラック P1〜P5）の 1 つだけとする。** 新しい課題は §3 に統合し、成果と数値は §0 と §4 を上書きする。
2. **壊れた中間表現や可変オブジェクトの上に、対症療法のキャッシュを足さない。** 速度・メモリの問題が出たら、まずデータ構造（入力と出力の分離・参照の共有・階層の表現）を疑い、破壊的変更で根を絶つ。
3. **変更検知の入口は `ChangeLedger` だけ。下流の記憶は §2.3 の表の鍵だけとする。**
   - 禁止するもの: DOM への `DIRTY_*` の欄・多欄の後追い照合（`PaintStamps` 型）・読み取り台帳（`LayoutReads` 型）・純粋性の述語（`F_*`・子から親へ伝える「使い回せるか」）・出力のその場の書き換え・退避と復元（`TentativeSlots`）・使い回さない部品の列挙。
   - やむを得ない一時の例外は、§3 の「凍結した例外」の表に場所と消す段を書いてから置く。表の外に足さない。
4. **プロセスグローバルな可変状態を禁止する。** 新しい `Shortcut`・`static volatile` のフラグ・プロセス共通のエポック・業務ロジックの `ThreadLocal` を作らない。状態は全て 1 ビュー（`EngineView`）に閉じる。
5. **クラスの規模を 2 段のハードゲートで強制する。** M0〜M5 の間は新規・刷新するクラスを `≤ 600 行` とし、M5 の完了で `1,000 行超 = 0`、M6 で `全クラス ≤ 600 行`（明示した一覧を除く）を `ArchitectureHealthTest` で強制する。
6. **検証は外から見た正しさと実際の費用で行う。**〔2026-11-26 改訂〕
   - **正しさ**: 仕様（WPT）・自分自身の全再計算との一致（`FrameOracle`・`IncrementalOracleTest`）・CPU / GL / Vulkan の 3 者画素一致・人が違いを確かめてから取り直したゴールデンで固定する。
   - **旧経路との比較**: 移行の間の道具にとどめる。新しいコードに旧経路の構造（項目の ID・層の分け方）まで一致させる番人は作らない。
   - **性能**: 絶対の上限と、同じ経路の中の比（増分 / 全再計算）で判定する。旧経路との比は記録だけにする。
   - **関門と完了条件**: 描画とバックエンドへの受け渡しまで含めた 1 フレームと保持ヒープで測る（部分だけで合否を決めない）。
   - **順序**: 新ゲートが緑になる前に旧ゲートを消さない。
7. **完了の定義は「旧コード・旧経路の完全削除」「テスト緑」「§4 の KPI 更新」の 3 点とする。**〔2026-11-26 改訂〕
   - 旧経路をフラグやフォールバックで残したまま完了としない。
   - 新しい型で置き換える経路は、**既定の切り替えと旧経路の削除を同じフェーズで行う**。削除を後の段に集めて、間に別の段を挟まない。
   - **旧経路の水準に追いつくための最適化を、削除の前提にしない**（性能の条件はルール 6 の絶対値で判定する）。
8. **計画書は状態表であり、作業ログではない。**〔2026-11-26 新設〕
   - §0（現在地）と §4 を作業のたびに上書きする。
   - 表の 1 つのセルはおよそ 500 字までとする。経緯・途中で分かった点・変異検証の記録はコミットの本文に書き、持ち越す知見は ARCHIVE の付録 5.A に 1 行で足す。
   - 状態の欄には「完了 / 進行中 / 未着手」と、止まっている条件だけを書く。

---

## 6. アーカイブおよび他文書との関係

- 旧計画（C 系・K 系・K5b・W0〜W3。2026-11-24 までの設計の記録と知見 `§6-1〜205`）の全文は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) に退避してある。`RegressionCatalogTest` が参照する `§6-N` は、同じアーカイブの付録 3 に全て保全されている。
- **2026-11-26 の軌道修正の前の本文**（M1 の段の記録・M2a の設計とスパイクの実測・M2b-0〜M2b-5 と M2c ①〜④-3 前半の作業記録・P1 の計測の詳細）は、原文のまま `archive/07_plan_m0_to_m2c_before_course_correction.md` へ移した（ARCHIVE の索引から辿る）。コードの注釈にある「計画書 §3.3 C」「§3.4.1」「§3.4.2-N」は、その文書の同じ番号の節を指す。
- `core/DESIGN_INCREMENTAL_KERNEL.md` は廃止し、全文と持ち越す知見を ARCHIVE の付録 5 へ移した。以後の知見は付録 5.A に積む。
- `CLAUDE.md` は本計画書（§0・§2.3・§3・§5）に従う。`CLAUDE.md`・`docs/ARCHITECTURE.md`・`docs/BENCHMARK.md` は、各フェーズの完了に合わせて本計画書の内容へ順に同期する。MOD 時代の旧計画 `REFACTORING_PLAN.md` は閉じ、`archive/00_refactoring_plan_mod_era.md` に原文のまま置いた。
- 文書の置き場（P4-1）: エンジンの文書は `docs/`（計画書・ARCHIVE は `docs/plan/`、過去の原文は `docs/plan/archive/`）、MOD の文書は `mod/docs/`。目次は `docs/README.md` と `mod/README.md`。
