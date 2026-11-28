# EnderFrameLib 根本再設計プラン（Engine v3: 破壊的アーキテクチャ刷新）

> **更新日**: 2026-11-29（M3b-4.24 を着手前の点検で 4.24a・4.24b に分け、4.24a〔走査器は列を前から 1 回だけ読む〕を完了した〔§3.4〕。2026-11-28: M3b を 5 段に分け、M3b-1〜M3b-3 と M3b-4.1〜4.23 を完了した〔§3.4〕。M3b-4 の中の段の番号を `M3b-4.N` の通し番号に付け直した〔§3.4〕。M3b と並行して DOM の側の M5-1〜M5-3a と文字の測定の記憶の M5-5 を完了した〔§3.7〕。方針の追加は §1.4〔構造点検。決定 I〜L・Y〕・§1.5〔エンジンを越える方針。決定 M〜Q〕・§1.6〔設計判断の点検。決定 R〜W〕・§1.7〔最適化の候補。決定 X〕。2026-11-26 の軌道修正以降は §1.3・§3.2・§3.3、独立トラック P4-1 は §3.9）
> **対象**: `:core`（622 ファイル・約 11.1 万行。`com.nishiyu.enderframelib.core.*` / `common.*` / `api.*`）と組み込みサンプル MOD（`:mod`。`mod/src/main/java/...`）の境界・描画・ホスト層を含む全体構造。
> **旧計画の全面撤回宣言（2026-11-24）**: 従来の `C 系`・`K 系`・`K5b`・`L5 (W0〜W3)`・`K4h`・`B14` と `core/DESIGN_INCREMENTAL_KERNEL.md` の「既存キャッシュ温存＋増分カーネル延命」方針は撤回・凍結した（§1.2）。本計画は**破壊的変更を全面的に許可**し、データ表現・中間表現・描画契約・ホスト統合の土台を作り直す。
> **この文書の書き方（運用ルール 8）**: 本書は**状態表であり作業ログではない**。段ごとの経緯・途中で分かった点・変異検証の記録はコミットの本文と [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) に書く。2026-11-26 以前の本文（M1 の段の記録・M2a の設計とスパイクの実測・M2b〜M2c の作業記録）は、原文のまま ARCHIVE の索引から辿れる `archive/07` へ移した。

---

## 0. 現在地（2026-11-29。作業のたびに上書きする）

| 項目 | 状態 |
| :--- | :--- |
| 完了 | M0・M1・M2a・M2b（WPT 170/260）・M2c（経路を 1 本化。§3.2）・M3a（MOD の旧レンダラの撤去。§3.3）・**M3b-1〜M3b-3**（描画は DOM を読まない・`DisplayScene`・問い合わせを scene から）・**M3b-4.1〜4.24a**（層ごとの詰めた描画命令 `DrawOps`・走査器 `SceneWalker` が全バックエンドの唯一の描画の経路・描き直す範囲をシーンの違いから・描画器の口を 1 つのフレームの値と層の文脈に・ビューは列を持たず、描くフレームは見えるチャンクだけを組む・本体は表示項目の列を組む口を持たない・走査器は列を前から 1 回だけ読む〔描く前に列を先にたどらない〕。1 行ずつ §3.4 の段の表）・**M5-1〜M5-3a**（DOM の分割。§3.7）・**M5-5**（文字の測定の記憶を §2.3 の L1 の形に。旧 `TextShaper` を削除。§3.7）。独立トラック P1a・P4-1（§3.9） |
| 進行中 | **M3b**（M3b-4.24b〜4.30 と完了ゲート M3b-5。§3.4）・**M5**（M5-4 と完了ゲート M5-3b〔M3b・M4 の後〕を残す。§3.7） |
| 次の一手 | **M3b-4.24b** 走査器がシーンを直接たどり、一時アダプタ（`SceneDisplayListAdapter`・`SceneAdapterBox`・`OpComposer`・`ItemSink`）・節の錨だけの命令 `NODE`・`FrameStructure` を削除する（4.24a の点検で残した、シーンから答える 3 つ〔ホストの描く中身・保持層の中身が層に向くかとクリップ・`::backdrop` の親〕は §3.4 の行）→ **4.25** 保持層の候補を実際にスクロールするコンテナだけに → **4.26** カリングと当たり判定の絞り込みを、宣言でなく動いている節で決める → **4.27** デモの `CatalogApp` で残りを測って段へ振り分ける → **4.28** GPU と CPU の呼び合いを解く → **4.29** 命令のメタ・ID を語から直接読む → **4.30** `DisplayItem` の削除と緩和の戻し → **M3b-5** 完了ゲート。`child` の `≤ 1.0 ms` に残る分（§4.1）: 箱の木の `chunkIntact`・`Scene3DDomSync` の DOM の全走査は M5-4、スクロールバーを持つコンテナの枠ごとの描き直しは 4.29 でつまみの矩形に絞る。残る穴は G3 multicol（M3c） |
| 止まっている条件 | なし |
| 性能の現在地（2026-11-29。M3b-4.24a の後） | 4.24a で `card-grid-3000:scroll` が **533.14 → 103.35 ms（0.194x。p99 567.96 → 141.21 ms）**（HEAD〔4.23〕との交互 A/B 3 回の中央値。走査器の位置ごとの保持層の照合 `retainAt` を消した）。ほかは横ばい（同じ A/B: `showcase` を開く 12.50 → 12.61・`resizeexact` 5.89 → 5.79・`voidwave.html` を開く 34.24 → 34.19・`fontsize` 69.18 → 70.74・`child` 2.08 → 2.07 ms・連続スクロール 内側 1.47 → 1.45 / 文書全体 1.13 → 1.15 ms。絶対値はセッションで揺れる）。回収した段: 4.16（`varied-list-2000:hover` 36.41 → 1.00 ms・連続スクロール 27.37 → 1.44 / 42.88 → 1.12 ms）・4.17（`large-list-8000:child` 7.17 → 2.10 ms）・4.24a（上）。目標まで: 1 件挿入 `≤ 1.0 ms` は残り約 0.5〜1.1 ms（§4.1）、`fontsize` の 1 スレッド `≤ 46.6 ms` は未達、`card-grid-3000:scroll` 約 103 ms（4.25・4.26）。段ごとの A/B の記録はコミットの本文。M5-5（M3b と並行）は 交互 A/B（`memoryAndLatencyGate -Pgate.base=HEAD`）で横ばい（`fontsize` 78.00 → 72.50 ms・`showcase` を開く 14.76 → 14.02 ms・`voidwave.html` を開く 38.01 → 37.11 ms・`child` 2.48 → 2.49 ms・`resizeexact` 6.06 → 6.46 ms〔前の回は 6.53 → 6.11 ms で、揺れの幅の中〕。ビューのヒープ 27.63 / 8.95 MB） |
| 方針の追加（2026-11-28） | Chrome ほかのエンジンを越える方針（§1.5 の決定 M〜Q）。本流には M5-4（DOM・断片・行の詰めた表現。§3.7）を足し、描画命令は M3b-4.10 で詰めた形にした。物差し・並列と合成・起動は機能拡張の P7・P2・P1 |
| 計画の点検（2026-11-28） | §1.6 の表（決定 R〜W）。目標は内訳から導ける値だけを掲げる／latency の判定を同じ実行の基準の仕事との比で正規化する（M3b-5）／§2.3 の表の `SceneEmitter` の使い回しの条件は 4.11 で鍵のほか 2 つにした（残りは M3b-5）／描画器の口を M3d・M3c で変えずに済む形にする（決定 V）／行数のゲートは 1,000 行超 0 をハードに、600 行は新規・刷新だけに |
| 最適化の候補（2026-11-28） | §1.7 の表（決定 X）。どれも未計測で、採否は各段の最初のスパイクで決める。本流: 整形の結果を断片に詰め文字の命令を行単位に・折り返しを送り幅の累積和で（M5-4 ④）／遮蔽の間引き・チャンクごとの GPU のバッファ（M3d）／`TextShaper` の記憶を規則に入れる（M5-5。2026-11-29 完了）。機能拡張: 一致した規則の組でスタイルを引く記憶と群の同一化・セレクタのコンパイル（P2 段 0）ほか（同書 §1.5） |
| 期限付きの緩和 | ① 描くフレームはどのスクロールでも見えるチャンクの列を組み直し（4.16）、組み立てが作る ID の文字列・メタの確保を 4.29 まで受け入れる ② ヒープの上限（`varied-list-2000` 68.5 MB・`large-list-2000` 19.3 MB）と latency の 2 行（`fontsize`・`child`。基準の表の上限は実測の 2 倍＋3 ms。時間の上限は `-Dgate.enforceTiming` のときだけ見る）は新経路の実測上限で、M3b-5 で 28.0 / 9.0 MB（決定 J）・46.6 / 16.5 ms へ戻す ③ `P8AllocationReductionTest` の 52 KB/行（実測 49,183 B/行）は 4.30 で 48 KB/行へ戻す ④ `costGate` の列を組み直す行の確保（4.10 で +5〜8%、4.11 で +9〜24%）は列とともに消える。`P8bRetentionTest` の天井は 30 MB |
| 二重に持っているもの | 表示項目の列だけ: 描画器の準備（`FrameComposer`）が一時アダプタ `SceneDisplayListAdapter`（組み立ての `OpComposer`・親つきの箱 `SceneAdapterBox`）で見えるチャンクの列と列の階層 `FrameStructure` を組み、走査器がたどる。本体に列を組む口は無く、列の外の読み手はテストの道具（`TestItems`・`TestFlatItems`）だけ。アダプタ・`FrameStructure`・`NODE` は 4.24b、`DisplayItem` とテストの道具は 4.30 で消す。他の一時アダプタ・旧経路は 0 |
| 凍結中の例外 | **なし**（M2c で凍結した `TreePainter` の例外 4 つは全て M3b-2 で削除済み） |
| 点検で見つけた歪み | §1.4 の表（2026-11-28）。13 項目を M3b-4.8〜4.30・M3b-5・M5-3b に振り分けた（済んだものは表の各行）。表の外に足すときは §1.4 に行を足してから |
| 見つけた退行（2026-11-28。4.24a で一部を直した） | ゲートに足した `latency:card-grid-3000:scroll`（デモの `CatalogApp` の形: 3,000 枚のカードがどれも `transition` と `overflow: hidden` を持つ）が **575.9 ms**（同じゲートの `large-list-8000:scroll` は 1.41 ms）。`overflow: hidden` を外すと 94.5 ms、`transition` を外すと 30.2 ms。原因は §1.4 の 13、方針は決定 Y。**4.24a で走査器の `retainAt`（§1.4 の 13 ②の照合）を消して約 103 ms**（`overflow: hidden` を外した変種と同じ桁）。残りは宣言だけの補間でカリングが止まる分（4.26）と保持層の候補の数（4.25）で、実際のデモで残りを測って振り分けるのは 4.27。`transition` を外した 30.2 ms の残りの内訳は未計測 |
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

### 1.4 M3b-4.7 の後の構造点検（2026-11-28）

M3b-4.7 の時点のコードを 2 つの独立した点検（Claude・Gemini）で読み、結果を突き合わせて実コードで確かめた。L2・L3（`BoxTreeCompiler`・`LayoutCache`・`LayoutFragment`）・M3b-3 の問い合わせ・凍結例外 0・旧型の再登場を止める `ArchitectureHealthTest`・画素で確かめる番人は健全である。**性能の目標はまだ回収していない**（§4: ヒープ 68.2 / 16.2 MB・8,000 行の 1 件挿入 19.1 ms。どれも M0 前より重い）。M3b-4.2〜4.7 は経路を入れ替える段で、性能は横ばいか少し悪い（costGate +3〜7%）。回収は M3b-4.8〜4.30 にかかる。歪みのほとんどは「`DisplayItem` の列を残したまま段階的に移した仮の足場」に集まっている。

| # | 歪み（場所） | 消す段 |
| :---: | :--- | :---: |
| 1 | **走査器が平らな列から階層を毎フレーム戻す**: `SceneWalker#index` が全項目の文字列 ID を `AtomTable.shared().intern`（プロセス共有の表）で番号にし、親を鍵の表で引き直す。`ScrollContentRanges` は親を最大 128 段たどる。§1.1-3 の「潰した階層の逆算」と同じ形 | M3b-4.18・4.24a・4.24b |
| 2 | **`DrawOp.Item` が `DisplayItem` とほぼ同じ 24 欄**（`String id`・`baseNodeId`・`Map events`・`RenderMeta`・`AnimationSpec`）。§2.1-3 に反し、シーンのヒープが 12.13 → 35.29 MB（`varied-list-2000`） | M3b-4.10（**済**。層ごとの `int[]`＋参照の表 `DrawOps` に詰め、命令ごとのオブジェクト・描画 ID の文字列を持たない。シーンは 15.80 MB。節の錨だけの命令〔`NODE`〕は表示項目の列のために残り、M3b-4.24b で消える） |
| 3 | **`SceneLayer` が参照と写しを両方持つ約 48 欄**（`box`・`frag`・`node`・`style`・`firstLine` と、辺 12 個・`runs` などの写し）。`ops` は後付け（`attachOps`。final でない）。層ごとの `new ComputedStyle` は薄い包みで寄与は小さい | M3b-4.9〜4.11（**済**: `runs` は M3b-4.10 で `ops` へ、M3b-4.11 で `ops` を final に・読まれない `scrollClipW`/`scrollClipH`・legend の印を削除。変形・クリップ・効果・sticky の欄は M3d の property tree へ移す。辺の写しは読む箇所が多く残す） |
| 4 | **残す部品が一時アダプタに乗る**: `SceneAdapterBox` を 16 ファイルが参照し、シーンを作る `LayerOps`・`SceneDamage`・生成器（`BoxItemPainter` ほか）を含む。`SceneDamage#children` はチャンクを `ArrayList` に広げ、子の照合ごとに `IdentityHashMap` を 3 つ作る | M3b-4.9（**済**。生成器・`LayerOps`・`SceneDamage` は `SceneLayer` と明示した置き場所を読み、`SceneAdapterBox` を読むのはアダプタの 4 ファイルだけ。`SceneDamage` は同じ位置の同じチャンクを飛ばし、表は違うチャンクがあるときだけ作る） |
| 5 | **GPU と CPU の呼び合い**（§1.1-4）: `GpuRenderBackend` が `cpu.renderOnto(…, this)` で自分を `GpuSink` として渡し、M3b-4.7 で `needsRepaint` も CPU に任せた。§3.4 の削除対象なのに段が決まっていなかった | M3b-4.28 |
| 6 | **移行のためだけの接着**: `RenderBackend#render` の重ね定義・`needsRepaint`、`FrameDamage` の plan → take（同じ入力の実体かで 2 つの呼び出しをつなぐ）、`SceneSource#baseSizeIn`（列の先頭と末尾の `==` でシーンの部分を推定）、再生を消した後の `GpuReplaySink` | M3b-4.12〜4.29（**M3b-4.12 で済**: 重ね定義・`needsRepaint`・plan → take・`baseSizeIn`。`GpuReplaySink` は M3b-4.28） |
| 7 | **機械検査の穴**: `NEW_PATH_SOURCES` に M3b-4.2〜4.7 で足したクラス（`SceneWalker`・`SceneDamage`・`FrameDamage`・`LayerOps` ほか）が入らず、`SceneWalker.java:309`（`RETAIN_SCROLL_LAYERS`）・`FrameDamage.java:67`（`SOFT_DAMAGE`）が `Shortcut` を直接読む | M3b-4.8（**済**。2 つの切り替えを描画器ごとの設定にして `Shortcut` から削除し、M3b-3〜4.7・M5 で足したクラスを一覧へ入れた） |
| 8 | **変化の検出が入口と出口の 2 か所**: `ChangeLedger` に加え、`SceneDamage` が作り直した層の命令を全欄で深く比べる（`Arrays.equals(ops)`）。出力の差分で記憶ではないが、費用は命令の大きさに比例し、多欄の照合に近い | M3b-4.10（**済**。比べるのは詰めた語の `Arrays.equals` と参照の表〔層の中で共有した欄〕の値だけ。行の断片も同じ列で比べる） |
| 9 | **描画が DOM の節を読む**: 生成器が `box.node().tag`・`key()`（文字列）・`paintHandle()` を読む。`SceneEmitter#nodeOf` は生成した箱に `new HtmlNode` を作って `setId` し、匿名の箱を文字列の ID で照合する。使い回しの条件は §2.3 の鍵より 5 つ多い（親の文脈という構造の入力。これ以上増やさない） | M3b-4.9〜4.11（**条件は 2 つに**: 根・legend・親の文脈を外した）・M3b-5（ブロックの文字の層と表の匿名の箱の番号を層・箱の値に） |
| 10 | **行数を合わせる継承の鎖**（M5-3a）: `NodeState` ← `HtmlNode` ← `ElementCssomView` ← `HtmlElement`。上位が `self()` で下へキャストし、コンストラクタで `self()` を渡す。実害は小さいが、§5 ルール 5 の分け方に合わない | M5-3b |
| 11 | **目標と KPI**: ヒープ 14.0 / 5.5 MB に対し、シーン以外の段だけで 13.46 / 4.42 MB（§4.1）。`fontsize` の 4 スレッド ≤ 5.0 ms は並列の再スタイル（P2）が前提。§4 の「現在」は M3b-4.1 のままで測った条件が混ざる。増分のコードの KPI に `SceneDamage` ほかが入っていない | M3b-4.8（**済**。測り直すと、シーン以外の段は 14.02 / 5.28 MB で目標を超える。M3b のヒープの目標を決定 J で置き直した〔§3.4 の完了ゲート・§4.1〕） |
| 12 | **小さな残り物**: 空のディレクトリ `core/paint/tree`、`V3Frame.java:248` の `indexOf`（O(n)。CSSOM のときだけ） | M3b-4.8（**済**） |
| 13 | **宣言された CSS で 1 フレームの費用が文書の大きさに比例する**（2026-11-28。デモの `CatalogApp` で見つけた）: ① `transition`・`animation` を宣言しただけで層に `FLAG_DYNAMIC_BOUNDS` が付き（`SceneLayerProps.java:141`。静的な `transform` も同じ）、親を通って根まで伝わるので、描くフレームのカリング（M3b-4.16）と当たり判定のチャンクの絞り込みが文書全体で止まる ② 走査器の `retainAt`（`SceneWalker.java:311`）が列の位置ごとに保持層の範囲を全部なめ、範囲は `overflow: hidden` のたびにできる（`Overflow#scrolls`）ので、費用が項目の数 × 範囲の数になる。ゲートの文書に補間も切り抜きも無く、見えていなかった | M3b-4.24a（**②の照合は済**。保持層は範囲の始まりで 1 度だけ問う）・M3b-4.25〜4.26 |

**決定（2026-11-28。§0・§3.4・§4・§5 に反映済み）**

| # | 決定 | 上の番号 |
| :---: | :--- | :---: |
| I | **M3b-4.8〜4.30 を 4 段に分ける**（M3b-4.8 点検の直しと測り直し／M3b-4.9〜4.11 型付きの命令と `SceneLayer` を直接読む生成器・`SceneDamage`／M3b-4.12〜4.29 走査器がシーンを直接たどり、GPU と CPU の呼び合いを解いて列と一時アダプタを削除／M3b-4.30 `DisplayItem` の削除と緩和の戻し）。M3b-4.8〜4.30 は後片付けではなく M3b で最大の段なので、二重持ちを長引かせないよう段ごとに置き換えと削除を閉じる | 1〜9 |
| J | **ヒープの目標は測ってから決める**: M3b-4.8 で「共有・GC 差」の中身と命令を型付きにした見込みを測る。届かないなら、`SceneLayer` の欄を減らす仕事を M3b-4.9〜4.11 に入れるか、目標を根拠つきで置き直す（どちらも本書を先に書き換える）。**M3b-4.8 で置き直した**: シーン以外の段だけで 14.02 / 5.28 MB あり（§4.1）、シーンを 0 にしても 14.0 / 5.5 MB に届かない。M3b の目標は型付きの命令にしたシーンの見込み（約 13 / 3.4 MB）を足した `≤ 28.0 / ≤ 9.0 MB`。元の値は DOM・箱の木を減らす M5 で見直す | 3・11 |
| K | **`fontsize` の 4 スレッド ≤ 5.0 ms は P2 の目標にする**。M3b のゲートは 1 スレッドの latency の上限（≤ 46.6 ms）で判定する（`≤ 5.0 ms` の値そのものは §1.6 決定 R で取り下げ、P2 のスパイクで置き直す） | 11 |
| L | **行数のゲートは役割の型で満たす**: 1 つの子しか継がない上位クラスで行数を合わせない（§5 ルール 5）。M5-3a の 2 つは M5-3b で委譲へ直す | 10 |
| Y | **描くフレームの費用は見える量と動いている量に比例させ、宣言された CSS と文書の大きさに比例させない**（2026-11-28 に追加）: カリングと当たり判定の絞り込みから外すのは、いま補間・アニメーションが動いている節を含むチャンクだけ（宣言だけの補間・静的な変形では外さない。動いている節から根までの層は 1 フレームに 1 度シーンの索引で引き、子から親へ伝える印は足さない）。保持層は走査器が層ごとに決めて O(1) で引き、候補は実際にスクロールするコンテナだけ。ゲートの文書は描画の費用を変える CSS（補間・切り抜き・影）を含む形を 1 つ持つ（`card-grid-3000`） | 13 |

### 1.5 Chrome ほかのエンジンを越える方針（2026-11-28）

旧経路の時点（2026-11-17。`docs/BENCHMARK.md`。両方 Arial）で、Chrome との 1 フレームの CPU 時間の比は hover `0.08x`・文字の変更 `0.16x`・スクロール `0.18x`・全面の font-size `0.21x`（既定のワーカー数）・showcase を開く `0.79x` で上回り、1 行の挿入は互角（`1.08x`）、幅変更（約 `1.3x`）・コールド起動（約 `1.8x`）・保持ヒープ（`1.55x` / `2.0x`）で負けていた。新経路は M3b-4.8〜4.30 まで出力を二重に持ち、Chrome とは測り直していない（ヒープは約 `2.6x` / `3.2x` の推定）。勝ち筋は、Chrome が汎用ブラウザとして払う費用（プロセス間通信・JS の同期レイアウト・互換の分岐）を構造の上で持たず、Chrome・Firefox の速さの仕組み（詰めた描画命令・property tree と合成スレッド・並列の再スタイル）を取り入れることにある。段の順序と比の目安は機能拡張の計画書 §1.3・§4。

| # | 決定 |
| :---: | :--- |
| M | **範囲**: 「軽さ」（1 フレームの CPU 時間・開く時間・コールド起動・プロセスのメモリ・フレームの安定）と「UI に要る仕様の範囲の正しさ」で上回る。JS・任意の Web サイトの互換・マルチプロセスとサンドボックス・quirks モード・Web 全体の WPT の合格数は目標にしない |
| N | **他エンジンとの比は記録だけ**: Chrome・Firefox・JavaFX・Compose ほかとの比は §4 に記録の行として載せ、合否には使わない（§5 ルール 6 の旧経路との比と同じ扱い）。保持ヒープに加えてプロセスのメモリ（RSS・`-XX:NativeMemoryTracking`）を測る。測り方と相手は機能拡張の P7a |
| O | **量の多い出力は詰めて持つ**: 1 件を 1 オブジェクトにせず、層・塊ごとの不変のプリミティブ配列（`int[]` と参照の表 `Object[]`、または `byte[]` と `VarHandle`）に詰め、使い回すカーソルで読む。層ごとの ByteBuffer は使わない（ラッパーが 1 つ約 48 B）。描画命令は M3b-4.10（§3.4）、DOM・断片・行は M5-4（§3.7） |
| P | **オフヒープは大きく寿命の長いものに限る**: direct ByteBuffer・FFM は書体（mmap）・画像・GPU へ写すデータだけに使う。シーン・DOM・断片はヒープに置く（詰め方が同じなら密度は同じで、解放の誤りは JVM ごと落ち、ヒープのゲートの数字だけが減る）。`sun.misc.Unsafe` は使わない |
| Q | **速さは表現と並列で得る**: この方針のためでも、§2.3 の表の外の記憶・新しい `Shortcut` は足さない。property tree（表現）は本流の M3d、論理と描画のスレッドの境界は M4、並列の再スタイルとレイアウト・合成スレッドでの実行は機能拡張の P2 で行う。property tree を足すときは先に §2.3 の表を書き換える |

### 1.6 計画書の設計判断の点検（2026-11-28）

本書と機能拡張の計画書の決定を、本書の内訳（§4.1）と実コードに照らして点検した。決定 A〜Q の向き（旧経路を物差しから外す・中間表現 3 段・`==` の 1 層の記憶・スクロール量を鍵に入れない・他エンジンの比は記録だけ・出力を詰める・シーンはヒープ）は保つ。直すのは次の 6 点（機能拡張の側の点検は同書 §1.4）。

| # | 見つけたこと | 根拠 |
| :---: | :--- | :--- |
| 1 | **`fontsize` の 4 スレッド ≤ 5.0 ms は内訳から導けない** | §4.1 の 1 スレッドは再スタイル 26.3 ms＋レイアウト 10.2 ms で、描画の前だけで 36.5 ms。4 スレッドが完全に効いても約 9.1 ms。要素あたりの再スタイルの費用を下げる段が無い |
| 2 | **ヒープの Chrome 比 `≤ 0.5x` / `0.9x`（M5-4 の後）に届く段が無い** | M5-4 で DOM・ビュー・箱・断片を半分（5.57 / 2.03 MB）にしても、共有の表 2.89 / 1.22 MB とシーンの見込み 13.1 / 3.4 MB を足して約 21.5 / 6.7 MB（Chrome の逆算値 約 26 / 5 MB の約 0.83x / 1.3x）。M5 の 12.0 / 4.5 MB も同じ |
| 3 | **M3b のヒープ 28.0 MB は余裕が約 1 MB で、見込みに未特定の分がある** | シーンの段 35.23 MB のうち §4.1 が挙げたクラスの合計は約 27.8 MB で、約 7.5 MB の中身を特定していない。届いても M0 前（35.96 MB）から 22% 減で、§1.1-1 が主因とした `LayoutBox` より DOM と文字の命令の数が効いている |
| 4 | **絶対の上限の latency が測定の揺れに負ける** | §4 の同じ表に `fontsize` 141.6 ms と 83.1 ms、`child` 21.99 ms と 19.7 ms が載る。セッションの差が上限 46.6 ms の幅より大きい |
| 5 | **§2.3 の表が実装と合っていない** | `SceneEmitter#buildBlock` の使い回しの条件は `(断片 ==, 最新の箱 ==)` のほかに 5 つ（legend か・文字の箱でない・根か・匿名の箱の照合・親の文脈〔`matchesParentContext`〕）。§1.4 の 9 に書いたが表は直しておらず、運用ルール 3 を本書が破っていた |
| 6 | **M3b-4.12〜4.29 で固める口を M3d・M3c がもう一度変える／行数のゲートが代理指標になっている** | M3d は `DynamicSceneState` を消して走査器・問い合わせ・`SceneDamage` を直す。multicol は 1 つの節が複数の層になり、1 節 1 層の `DisplayScene#byNodeId` が崩れる。行数は §1.4 の 10（行数を合わせる継承の鎖）が歪みの実例で、`InputController ≤ 500 行` は当たり判定・IME・編集・選択・ポップアップを収める想定だった |

**決定（2026-11-28。§0・§2.3・§3・§4・§5 と機能拡張の計画書に反映済み）**

| # | 決定 | 上の番号 |
| :---: | :--- | :---: |
| R | **目標は内訳から導ける値だけを掲げる**: `fontsize` の 4 スレッドの絶対値（≤ 5.0 ms）は取り下げ、P2 の最初の段（要素あたりの再スタイルの費用を測って構造で下げるスパイク）で根拠のある値に置き直す。それまでの判定は同じ実行の 1 スレッドとの比。ヒープの Chrome 比の目安は M5-4 の後 約 `0.85x` / `1.3x` にし、`≤ 0.5x` / `0.9x` と M5 の 12.0 / 4.5 MB は、シーンを約 4.5 / 1.3 MB にする段を決めてから掲げる | 1・2 |
| S | **M3b-4.10 の前にシーンの段の未特定の分を特定する**（`heapBreakdown -Pheap.detail=true`）。特定した中身で §4.1 の見込みを書き直し、28.0 / 9.0 MB に届かないと分かったら M3b-4.10・M3b-4.11 の範囲を先に広げる（目標を動かすのは最後）。**M3b-4.10 で特定した**: 未特定の分は命令ごとの描画 ID の文字列（9.8 万個）と語の文字列（`[B`＋`String` 9.34 MB）。M3b-4.10 の後の見込みは 28 / 9 MB にわずかに届かないので、M3b-4.11 に箱ごとのメタ・付帯情報と命令の語を足した（§4.1） | 3 |
| T | **latency の判定は同じ実行の基準の仕事との比で正規化する**: `memoryAndLatencyGate` の中で決まった基準の仕事を一緒に測り、上限は「基準との比」で持つ（絶対値は記録）。M3b-5 で入れ、期限付きで置き直した 2 行の戻しもこの形で判定する。旧経路・他エンジンとの比ではない（運用ルール 6 はそのまま） | 4 |
| U | **§2.3 の表に今の使い回しの条件を書き、M3b-4.11 で鍵どおりに戻す**: 親の文脈で決まる値（重なりの種類 `stackingKind`・legend の扱い）を層の値から外し、親が子を並べる側（`SceneChunk` の並べ方）に置く。条件はこれ以上増やさない。**M3b-4.11 の結果**: legend の扱いは親の値（`SceneChildren#legend`）にした。親の文脈（flex・grid の子か・浮動を止める親か）は親の `display` だけで決まり、子を引く表は親の `display` が前回と同じときだけ渡すので、鍵が当たった層の `stackingKind` は親の文脈と合う（層の値に残す。`SceneReuseKeyTest`。表を常に渡す変異で落ちる）。根は根の断片でしか当たらない。残る 2 つ（ブロックの文字の層は断片の座標に層の位置を含む・表の匿名の箱の ID は走査の順の番号）は M3b-5 | 5 |
| V | **M3b-4.12〜4.29 の口は M3d・M3c で変えずに済む形にする**: 描画器の入力を 1 つのフレームの値（シーン・動的な値・重ね描き・時刻）にまとめ、動的な値は「節の番号 → 変形・クリップ・効果・スクロール」の読み口で読む（M3d は中身を property tree に替えるだけ）。節 → 層の索引は 1 対多を返せる形にする（multicol・行に割れたインライン） | 6 |
| W | **行数のゲートは 1,000 行超 0 をハードに、600 行は新規・刷新だけにする**: M6 の「全クラス ≤ 600 行」は取り下げ、600 行超の既存のクラスは理由つきの一覧で持つ（増やさない）。L5 は行数の合計でなく、依存の向きと状態の置き場で判定する（行数は目安） | 6 |

### 1.7 計画の外にあった最適化の候補（2026-11-28）

他のエンジンと他の分野の技術から、本書と機能拡張の計画書のどの段にも無かった候補を集めて段へ振り分けた。**どれも未計測**で、効果の大きさは保証しない。採否は各段の最初のスパイクで、既存のゲート（`memoryAndLatencyGate`・`heapBreakdown`・`costGate`）の値で決める。本流の内部構造に関わるものを下に、機能・ホスト・物差しに関わるものを機能拡張の計画書 §1.5 に置く。

| # | 候補 | 現状（実コード） | 段 | 判定に使う値 |
| :---: | :--- | :--- | :---: | :--- |
| 1 | **整形の結果を断片に詰めて持ち、文字の命令を行単位にする**: レイアウトで整形した glyph の番号と送り幅を行ごとの `int[]` に詰めて断片が持ち、描画は文字列から整形し直さない。命令の数は語の数から行の数になる | `SceneRun` は `String text` を持つ。`varied-list-2000` の命令 97,879 のうち `TEXT` が 83,916（86%） | M5-4 ④（M3b-4.10 は `TEXT` の命令を、後で行単位の参照に替えられる形にしておく） | `heapBreakdown` のシーンと断片木の段・`costGate` の描画の行 |
| 2 | **折り返しを送り幅の累積和で解く**: 段落ごとに折り返せる位置と送り幅の累積和を詰めた配列で持ち、幅が変わったら二分探索で行を切り直す（測り直さない） | M2a から引き継いだ課題「幅の変更で全ての行を測り直す」（§3.1） | M5-4 ④（1 と同じ表現の上） | `showcase` の幅の変更・`ab-inc-vs-full` |
| 3 | **遮蔽の間引き**: 不透明な矩形を手前から集め、完全に隠れた層とチャンクを走査で飛ばす。GPU は不透明なものを手前から深度つきで描く形を、費用を測ってから決める | 遮蔽を扱うコードは無い（不透明の判定は当たり判定だけ） | M3d | モーダル・全面の背景のある実文書の 1 フレーム（描画まで） |
| 4 | **チャンクごとの GPU のバッファ**: `SceneChunk ==` を鍵に頂点のバッファを 1 回だけ作り、フレームは描画の呼び出しと property tree の値だけにする（§1.5 決定 P の「GPU へ写すデータ」） | 毎フレーム組み立てて送る | M3d（§2.3 の表に行を足した） | GL のスクロール・補間の 1 フレームと 1 フレームの確保 |
| 5 | **`TextShaper` の記憶を規則に入れる**: 整形・折り返し・区切りの記憶を `EngineContext` に閉じた 1 層の記憶にし、表に載せる | static の表 `SHARED_SEGMENTS`・`ThreadLocal` 3 つ（`SHAPE_L1`・`WRAP_L1`・`SEG_L1`）・2 世代の `ConcurrentHashMap`。運用ルール 4 と §2.3 の表の外にあり、どの段も扱っていなかった。**M5-5 で分かった**: この記憶は新経路から呼ばれない旧経路の残骸で、新経路の記憶は書体の送り位置・送り幅の表（static の照会用の鍵・`ThreadLocal` の表）だった | **M5-5（2026-11-29 完了）**: 旧 `TextShaper` を削除し、書体の記憶を §2.3 の L1 の形（`TextMemo`）に | `ThreadLocal`・static の表 `0`・`fontsize` と開く時間が上限内 |

**決定（2026-11-28）**

| # | 決定 |
| :---: | :--- |
| X | **候補は段に入れ、採否はスパイクで決める**: 上の 5 つと機能拡張の計画書 §1.5 の候補を、各段の内容に足した。記憶を足す候補（4・5 と、P2 段 0 の「一致した規則の組でスタイルを引く記憶」）は、運用ルール 3 に従って §2.3 の表に未実装の行として先に載せた（鍵は `==` だけ。スパイクで採らないと決めたら行を消す）。スパイクで効果が出ない候補は、段から消して ARCHIVE の付録 5.A に 1 行残す |

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
| L4a | `DisplayScene`（`SceneLayer` / `SceneChunk`。層は自分の描画の中身〔行の断片と描画命令〕を詰めた列 `DrawOps` を持つ。M3b-4.10） | `(LayoutFragment ==, 最新の BoxNode ==)`・`FlowChunk ==`（描画命令は層の値の一部で、層を作るときだけ作る。別の鍵を持たない）。**今の実装はこれに 2 つ多い**（M3b-4.11 で 5 つから減らした: legend は親の値 `SceneChildren#legend` に、親の文脈は子を引く表を親の `display` が同じときだけ渡す形に、根は根の断片でしか当たらない。残りは、ブロックの文字の層〔断片の座標が層の位置を含む〕と表の匿名の箱の通し番号。M3b-5 で層・箱の値にする。§1.6 決定 U。これ以上増やさない） | **実装済み（M3b-2。描画命令は M3b-4.1）** |
| L4c（M3b-4.15。M3b-4.30 まで） | 表示項目の列（描画器の準備が組む。`FrameComposer`。描画器が 1 つ持ち、ビューは持たない） | `(シーンの根 ==, キーフレーム ==, 装置の画素比・ビューポートの値)` と、中身を 1 つの節にまとめていないコンテナのスクロール量が組んだときのまま（まとめたコンテナのスクロールは列を組み直さず平行移動で足す）。ビューが列を持っていたときの列の寿命と同じで、M3b-4.30 で列ごと消える。**M3b-4.16 で変えた**: 見える範囲で間引くので、カリングするフレームはまとめたコンテナのスクロールでも組み直す（スクロールの条件を全てのコンテナのスクロール量にし、鍵にカリングの有無〔利用者の平行移動が無いか〕を足した。見える範囲の外の余白で使い回す条件は足していない）。**M3b-4.18**: 列の階層（項目の親の位置・保持層の範囲。`FrameStructure`）は列と同じ値の一部で、同じ鍵で使い回す（別の鍵を持たない。列を読むだけの組み手には作らない） | 実装済み |
| L4a（M3d で追加） | property tree（transform・clip・effect・scroll。`DisplayScene` と並ぶ値で、段は増やさない） | 記憶を持たない（毎フレームの不変の値。変わらない節は `==` で共有する） | **未実装**（M3d。足すときに本行を確定する） |
| L4b（M3d の候補） | GPU の頂点のバッファ（チャンクごと。GPU へ写した値で、中間表現の段は増やさない） | `SceneChunk ==`（チャンクが参照されなくなったら解放する） | **未実装**（§1.7 の 4。スパイクで採否を決める） |
| L2（P2 段 0 の候補） | —（`Style` を使い回すだけ。段は増やさない） | `(一致した宣言の組 ==, 親の継承群 ==)` → `Style`。群は値で同一化して `==` を当てる | **未実装**（機能拡張の計画書 §1.5 の 1。スパイクで採否を決める） |
| L1（M5-5） | —（文字の測定の結果: アウトライン書体の字ごとの送り位置・ビットマップ書体の素の送り幅） | `(書体 ==, 文字列の値等価, 整形の指定の値等価)`。置き場は鍵の `書体 ==` そのもの（書体の実例が 1 つ持つ `TextMemo`。2 列の組の直接配置の表で、上限は欄の数〔アウトライン 4,096・ビットマップ 8,192〕。欄は不変の値だけを指し、static・`ThreadLocal` を持たない。2026-11-29 に置き場を `EngineContext` から改めた: 鍵が書体の同一性を含むので書体の実例が持てば同じ鍵になり、測るたびに文脈を引く手間〔`EngineContext.current()` の `ThreadLocal`〕を熱い経路に足さずに済む。書体は不変で、ビューをまたいで共有してよい） | **実装済み（M5-5）**。整形・折り返し・区切りの結果は覚えない（行は `InlineFlow` が組み、断片木の記憶〔L3〕が持つ） |

**期限付きの例外**（この表の外にあるもの。消す段を決めてある）: `PaintNode`/`PaintView`/`TreePainter` の記憶と `Fragment`/`FragSeq` は **M3b-2 で削除済み**。問い合わせの口だった `SceneBox`/`PaintBox` は **M3b-3 で削除済み**（当たり判定・CSSOM は `SceneHitTest`/`SceneQuery` が scene を直接たどる。記憶は持たず、1 回の問い合わせの作業用の値 `HitScratch` をビューの文脈が使い回すだけ）。残る一時アダプタは、記憶を持たない `SceneDisplayListAdapter`（`SceneAdapterSupport`・`SceneAdapterBox`・命令を組み立てる `OpComposer` を含み、M3b-4.12〜4.29 で `DisplayItem` とともに削除。M3b-4.9 から残す部品〔生成器・`LayerOps`・`SceneDamage`〕はこれを読まない）の 1 つだけ。その出力の表示項目の列は描画器の準備が組んで持つ（`FrameComposer`。M3b-4.15 からビューは持たない。使い回しの鍵は上の表の L4c）、走査器（M3b-4.6 から全バックエンドの描画の経路）はフレームをまたいで姿勢・索引を持たない。描画命令は層の位置・祖先のクリップ・親の錨・祖先の文字色のアニメーションを持たず、それらは組み立てが毎フレーム足す（命令の座標は層自身か原点の節からの float）。
**記憶ではない引き継ぎ**: 全体の作り直しの要求（`RELAYOUT`）は前回の木と記憶を捨てるが、作り直す前の要素の算出スタイルだけを、そのフレームのトランジションの始まりの入力として `StyleSource.history` に渡す（照合の鍵ではなく、フレームの後で捨てる）。アンカーの**覚えたスクロール量**（G8。§3.2）もビューの履歴で、アンカーの再計算点でだけ書き換え、台帳の `ANCHOR` で `BoxNode` へ入る（鍵は `BoxNode ==` のまま。`RELAYOUT` をまたいで渡し、文書の入れ替えで捨てる）。描き直す範囲を求める `SceneDamage`（M3b-4.5）が覚える前のフレームのシーンとスクロール量も比べる相手で、使い回す出力は無い（記憶の鍵ではない）。描く前の準備（`RenderBackend#prepare`。M3b-4.12）で求めた範囲も、準備した値 `PreparedFrame` で同じフレームの描画へ渡して捨てる。走査器が前の走査で描いた、補間・外の事情で変わりうる項目の範囲と補間の状態（`OutsideSceneRecord`。M3b-4.24a）も、前のフレームの重ね描きの範囲と同じ比べる相手で、描くのを省く出力ではない（次のフレームの描き直す範囲と描く前の判定にだけ使い、走査のたびに書き直す）。

### 2.4 今の実装の形（2026-11-28）

コードを触る前に読む、今の経路・型・番人の要点。段の状態は §0・§3、記憶の鍵は §2.3。形が変わった段は、その段のうちにここを書き直す（消えた型・口を残さない）。

- **新経路の形**: 凍結した台帳 → `BoxNode`（入力の木と最新の木）→ `LayoutFragment` → `DisplayScene`（`SceneEmitter`。親相対・`int nodeId`・記憶は `(断片 ==, 最新の箱 ==)` と `FlowChunk ==` だけ。層は自分の描画の中身〔行の断片と描画命令〕を詰めた列 `DrawOps` を持ち、命令は層を作るときだけ `LayerOps` が作る〔M3b-4.1・M3b-4.10〕）→ 記憶なしの一時アダプタ `SceneDisplayListAdapter`（命令に位置・クリップ・親の錨・祖先の文字色を足して組み立てる `OpComposer`。M3b-4.24b で削除）→ 描画器の準備が組む `DisplayItem` の列（`FrameComposer`。M3b-4.15 からビューは列を持たない）→ 走査器 `SceneWalker`（全バックエンドの描画の経路。M3b-4.6）。旧 `PaintTreeBuilder`/`PaintNode`/`PaintView`/`TreePainter` の再登場は `ArchitectureHealthTest#legacyPaintTreeTypesStayGone` が落とす。1 回だけのフレームは `FrameRender`、テストで箱を引くのは `TestBoxes`。
- **描画は DOM を読まない（M3b-1・M3b-2）**: 描画が読む DOM の値（属性・textarea の中身・状態の規則が掛かるか）は `BoxTreeCompiler` が `BoxNode.values`（`DomValues`）に写し、描画（`core.paint`）は `SceneLayer.values()` だけを読む（`ArchitectureHealthTest#paintDoesNotReadDomValues`）。スクロール・sticky・アンカー追従は `DynamicSceneState` / `SceneAnchorFollow` が担い、凍結例外は全て削除済み。
- **新経路の穴（M2c-3 の移植で見えた。計画書 §3.2 の G1〜G9）**: G1（インラインの中のブロック）・G2（subgrid）・G4〜G9 は直した（G8 のスクロールは計画書 §3.2「G8 の後半の設計」: スクロール量は鍵に入れず描画の平行移動、アンカーの覚えたスクロール量だけをビューの履歴として台帳の `ANCHOR` で入れる）。残りは multicol（G3。M3c）。見つけたテストは `@Disabled("G<n> …")` で残してあり、直したら外す。ヒープと latency 2 行の上限は M3b まで新経路の実測（計画書 §0）。
- **MOD の描画（M3a）**: 面は `MinecraftHostSurface`（共有コンテキストの `LwjglGlRenderBackend`）だけが描く。旧レンダラ（`DisplayItemDrawer` ほか）・`useCoreBackend`/`useSdfShader`・フォールバックは削除した（再登場は `ArchitectureHealthTest#legacyModRendererStaysGone` が落とす）。Minecraft だけが描ける中身（アイテム・エンティティ・プレイヤーの頭・`<mc-scene>`・ビットマップ書体）は `MinecraftHostTextures` が先にテクスチャへ描き、コアの `HostTextures` で項目の中身として重なりの順どおりに貼る。キャレットは `TextFieldOverlay`（スタンドアロンと共有）。実機の確かめ方は計画書 §3.3。
- **DOM の木の形は子の並びだけ（M5-1）**: 番号の空間 `NodeIds` は番号と世代だけを持つ（旧 `DocumentArena` の木の形・タグ・Bloom の写しは削除。再登場は `ArchitectureHealthTest#domArenaCopyStaysGone` が落とす）。前後の兄弟は子の並びが保つ位置（`indexInParent()`。O(1)）、セレクタの足切り（class の atom・Bloom）は `SelectorIndex` が節の欄で保ち、番号の有無に依らない。子の並びを直接触る経路（パーサ・バインディング）は `parent` を先に結ぶ。`HtmlNode` は API の入口で、中身は `NodeNumbering`・`TreeOps`・`NodeAttributes`・`NodeText`・`NodeEvents`。M5-2 でパーサを `TreeBuilder`・`ActiveFormatting`・`TableModes` ほか、`Bindings` から `ReactiveDomBinder`・`TemplateDeps`、`HtmlElement` から `HtmlDOMTokenList`・`HtmlCSSStyleDeclaration`・`TopLayerOps`、`InteractionState` から `StateFlags`・`StyleInvalidation` を出した（`core.dom` に 1,000 行超は無い）。M5-3a で分けたクラスを各 500 行以下にした（節の欄と手入れは上位の `NodeState`、CSSOM View は上位の `ElementCssomView`、節の鎖と Top Layer は `InteractionChains`、宣言的バインディングの走査は `DeclarativeBindings`、in body は `BodyMode`）。子の並びは、既に別の親へ結ばれた子を外すときは抜くだけにする。
- **問い合わせ（M3b-3）**: 当たり判定は `SceneHitTest`（手前から奥へ scene をたどり、チャンクの包含矩形で `O(log(N/32))` に絞る。外に出た sticky は `stickyDescendants` から拾う。結果は `SceneHit`）、位置・寸法・索引は `SceneQuery`（結果は値の `SceneLocation`）。ビューの側の文脈（変形の上書き・`inert`・α 判定）は `UiHitSupport`。一時アダプタ `SceneDisplayListAdapter` が読む親つきの箱はパッケージ内の `SceneAdapterBox`（M3b-4.24b で一緒に削除。読むのはアダプタ自身と組み立ての `OpComposer` だけで、項目の生成器は `SceneLayer` と置き場所 `PaintBounds#place`、`LayerOps` は位置 0 の層、`SceneDamage` は `PlacedLayer` を読む〔M3b-4.9。`ArchitectureHealthTest#sceneAdapterBoxStaysInsideTheAdapter`〕。消す予定の型に残す部品を新しく乗せない）。旧型の再登場は `ArchitectureHealthTest#legacyQueryTypesStayGone` が落とす。
- **描画命令（M3b-4.1・M3b-4.10）**: 層の描画の中身は詰めた不変の列 `DrawOps`（`core.paint.scene`。先に行に置かれた断片を 1 つ 7 語、続けて命令を頭 2 語・座標 4 語・既定値でない欄だけの語で `int[]` に、文字・属性の表・メタ・アニメーションなどの参照は層ごとの参照の表 `Object[]` に。同じ参照・同じ値の文字列は 1 欄を共有）。項目の生成器（`BoxItemPainter` ほか）は `OpList`（中身は `DrawOpsBuilder`）へ `PaintSupport#addElement`・`addLineElement` で書き、読む側（`OpComposer`・`SceneDamage`）は使い回す `DrawOps.Cursor` で読む。命令は描画 ID の文字列を持たず、持ち主の節の ID と接尾辞・通し番号から組み立て直す（`Cursor#id()`）。文字の命令は箱で 1 つのメタを共有して行の値（ベースライン・行の高さ）を語に持ち、断片と同じ文字の命令は断片の番号だけを持つ。メタ（`RenderMeta`・`RenderHints`）はオブジェクトで持たず、列の末尾の表に層の中で別のものを 1 つずつ語で持つ（`MetaWords`。付帯情報が持ち主の ID・鍵・書体・`relativeOrigin` だけならオブジェクトを作らない。読むと値の等しいメタに戻り、カーソルは 1 つの列で 1 度だけ作る）。層の持ち主と同じ持ち主の命令は持ち主の語を、鍵が持ち主の節の番号から組める命令は部品の番号の 1 語だけを持つ（M3b-4.11）。文字列の鍵（`DisplayItem.keyOf(String)`）はプロセス共有の `AtomTable` に文字列を足すので、詰める・戻す側で呼ばない（呼ぶと以後の番号がずれ、項目の鍵が変わる）。座標は層自身（`DrawOps.BASE_ORIGIN_PARENT` なら文字・インラインの断片の基準の原点の節）からの float で、層の位置・祖先のクリップ（生成器には null を渡す）・親の錨・祖先の文字色のアニメーションを持たない。祖先を読む箱（背景をキャンバスに譲った html/body・縦書きの包み箱）・マスク・`::backdrop`・スクロールバーは毎フレーム作る。行の断片は `SceneLayer.ops()` の `runCount()`・`runX(i)` ほかで読み、値の `SceneRun` は問い合わせの結果とテスト（`TestBoxes#runs`）のときだけ作る。M3b-4.24b で表示項目の列を消すまで、組み立てが毎回 ID の文字列と項目を作る（全部を作り直す割り当ての上限は期限付きで緩めてある。M3b-4.30 で戻す。計画書 §0）。
- **走査器と補間の状態（M3b-4.3・M3b-4.6）**: 補間の状態は `AnimationStates`（節の番号 → 部品の ID ごと。部品の鍵は一意でないので鍵で引かない）。後片付けは走査器の入力の列が別の実体になったときの印（`retainNodesOf`）で、実時間を見ない。走査器 `SceneWalker` が CPU・GPU・Vulkan・MOD の面の唯一の描画の経路（旧 `PaintSceneDriver`・`RetainRanges`・`RangeBounds`・`DamagePlanner`・`ReplayKeys`・GPU の項目の再生は削除。再登場は `ArchitectureHealthTest#legacyPaintDriverTypesStayGone`）。入力は 1 つのフレームの値 `RenderFrame`（`SceneSource`〔`UiView#paintSource`。シーン・動的な値・キーフレーム・画素比〕・重ね描き・利用者の平行移動・除く節・時刻。重ね描きは列に足さず別に渡す。シーンのフレームは列を持たず、列は準備 `PreparedFrame` が持つ）で、`RenderBackend#prepare` が描き直す範囲を求めた `PreparedFrame` を `render` へ渡す（M3b-4.12。`render` の重ね定義・`PaintFrame`・`SceneSource#baseSizeIn`・`FrameDamage` の plan → take は削除）。ホストはビューの列を読まず `UiView#renderFrame(重ね描き, 除く節, 時刻)` を渡し、一部を省くときは列を絞らず除く節 `ExcludedNodes`（属性名の値。シーンの層の `DomValues` で決め、項目の持ち主の節の番号で引く）で省く。空かの判定は `UiView#hasFrame`、要素の位置は `UiView#locate`（列から ID を引く口と入力の値の列への書き戻しは削除。M3b-4.13。本体で列を読む所は `ArchitectureHealthTest#hostsDrawTheViewFrameNotTheViewList` が数える）。列の外の読み手（テスト・ベンチ）の入口はテストの道具 `TestItems`（`core/src/testFixtures`。`of(UiView)`・`of(FrameRender.Frame)`・`displayList(html…)`・`offsets(UiView)`。本体は列を組む口〔`relativeElements`・`FrameRender.Frame#items`・`SoftwareRendering.displayList`・`renderDocument`〕を持たない。M3b-4.22。IME の位置は重ね描き、MOD の面のキャレット・ツールチップは `UiView#locate` と層の描画命令から引く。M3b-4.20。MOD は準備した列を読まない。Minecraft が描く中身は、描画器の準備が組みながらホストに聞いて `HostContent` で集め〔`HostContent.Filter#wants`。`HostTextures` が実装〕、`PreparedFrame#hostContents` から用意し、描くときは鍵と `HostContent#matches` で引く。M3b-4.21）（`renderCache()`・`StandaloneView#items()` は削除。M3b-4.14）。MOD のメディアはシーンの命令から読む（`UiView#videos`）。`memoryAndLatencyGate` の latency と交互 A/B は CPU の描画器で描くところまでを測る（M3b-4.14 から）。走査器はフレームをまたいで姿勢・索引を持たず、毎フレーム作る。**列は前から 1 回だけ読む**（M3b-4.24a。4.24b で列をシーンのたどりに替える前提）: グループを層にするか（部品が 2 つ以上か）は、部品が 1 つのうちは項目を 1 つだけ待たせて次の項目で決め（グループの部品は描画順で続く。待たせた項目は保持層の範囲の前後と走査の終わりで描く）、補間・外の事情で変わる項目の描き直す範囲と描く前の判定（`changesOutsideScene`）は前の走査で描いた項目の記録 `OutsideSceneRecord` から求め、保持層は範囲の始まりで 1 度だけ問い、平行移動は節の姿勢を作るときに引く（描く前に列を先にたどる処理の再登場は `ArchitectureHealthTest#walkerReadsTheListOnceForward` が落とす）。描画器（`Painter`）は項目・保持層の中身を層の文脈 `LayerContext`（項目: 姿勢・補間値・祖先の箱の姿勢をクリップの持ち主の ID で引く。中身: 中身の節とコンテナの ID・印・姿勢・コンテナの切り取りを持つクリップ・層に向くか）で受け取り、列の位置で走査器に問わない（走査器の `items()`・`ancestorById`・`parentIndex`・公開の `resolveAt` は削除。M3b-4.23。`ArchitectureHealthTest#paintersReadTheLayerContextNotListPositions`）。描画器のクリップの覚えは項目の実体で引き、持ち主は ID と姿勢の同一性で確かめる。描画器の準備（`FrameDamage` の `FrameComposer`）が列を組み（描くフレームはビューポートにかかるチャンクだけ。M3b-4.16。層の包含矩形は塗る範囲〔命令の余白は `DrawOps#inkPad`。描き直す範囲と同じ規則〕、変形・補間・フィルター・sticky・アンカー追従の下と利用者の平行移動があるフレームはカリングせず、どのスクロールでも組み直す。正しさはカリングしない列で描いた画素との一致〔`FrameCullingTest`〕）、中身を 1 つの節にまとめるスクロールコンテナはスクロール 0 で組み（`SceneDisplayListAdapter#emitUnscrolled`）、スクロール量は描画器が動的な値から中身の節の平行移動として足す（`ScrollTranslation`。前の列はシーンの根が同じでまとめていないコンテナが動いていなければ使い回す。§2.3 L4c）。ビューは描く絵の版 `UiView#paintVersion` で描くかを知らせる。列の項目から画面の位置を読むときは平行移動も足す（`DisplayItem#resolveAbsolute(…, PaintOffsets)`）。錨を出さない箱（匿名・大きさ 0）は、子の親の錨と座標の原点で素通しにする（`ZeroSizeAncestorTest`）。項目の親（変形・不透明度・グループを引き継ぐ錨）は列を組む側がシーンの層をたどって書いた列の階層 `FrameStructure` から読み、走査器は項目の鍵・ID・文字列の鍵（`DisplayItem.keyOf`）から親を引き直さない。クリップの持ち主は描く項目から親をたどる（M3b-4.18。`ArchitectureHealthTest#walkerReadsTheFrameStructureNotKeyIndexes`。列だけのフレームは `FrameStructure#fromKeys`）。効果の層（フィルター・マスク・混合・`clip-path`）の中身は錨の子孫で、マスクの画像を描く項目は階層の印 `FrameStructure#isMask`（`LayerSpec` は列の範囲を持たない。M3b-4.19）。列だけのフレームはマスクの印を持たないので、画素を見るテストの道具（`SoftwareRendering.renderHtml`）はシーンのフレームを描く。
- **描き直す範囲と保持層（M3b-4.5）**: 描き直す範囲は `SceneDamage` が前後のシーンを使い回しの同一性で対にした違いとスクロール量から求め（作り直した層は写し方が同じなら描画命令だけを比べて子へ下りる。スクロールしたコンテナは枠ごと）、補間・外の事情で変わる項目とシーンの外の重ね描きの前後は走査器が足す（補間・外の事情の分は前の走査の記録 `OutsideSceneRecord` から。M3b-4.24a）。描画器はそれを 64×64 タイルに広げて消し、塗りを外接矩形に収める。正しさは `SceneDamageTest`（前後の全面描画の画素の違いが範囲に入る）と `PartialRepaintRealDocumentsTest`（実文書の全フレームで、差分で描いた画素が新しい描画器の全面と一致）。保持層は、中身を 1 つの節にまとめたコンテナの範囲と印（コンテナの層の子の並び）を列を組む側が列の階層 `FrameStructure` に書き、走査器がそれを `Painter#planRetained` へ渡す。
- **描かないフレームの判定（M3b-4.7）**: 前の画素を持ち続ける描画先（MOD の面・GPU）は、描く前に `RenderBackend#prepare(フレーム)` の `PreparedFrame#needsRepaint` を見て、false なら描かない。答えは描き直す範囲が空か（シーンの違い・塗りの色・利用者の平行移動は `FrameDamage`、補間・外の事情〔画像・動画・キャンバス・3D〕・重ね描きは `SceneWalker#changesOutsideScene`）。true なら準備した値を `render` へ渡す（求めた範囲を描画がそのまま使う。最後に準備した値でない値・2 度目の描画は全面）。シーンの外で絵が変わる事情（書体の登録）はホストが `invalidate`。部品の値は DOM の写しでシーンに入るので、毎フレーム描き直す種類ではない。項目の同一性で静止を見る型（旧 `StaticFrameGate`・`ItemDiff`）と、列全体を絶対座標へ直す本番の型（旧 `AbsoluteItems`・`UiView#elements()`）の再登場は `ArchitectureHealthTest#legacyStaticFrameTypesStayGone` が落とす。要素の位置は `UiView#locate`、平坦な列が要るテストの道具は `TestFlatItems`（テストの補助。M3b-4.30 で消す）。正しさは `RepaintSkipTest`・`GpuRepaintSkipTest`（描かないフレームも含めた画素 = 新しい描画器の全面）。
- **点検の直し（M3b-4.8）**: 差分描画・スクロール層の保持の切り替えは描画器ごとの設定（`SoftwareRenderBackend#partialRepaint`・`GpuRenderBackend#retainScrollLayers`。`Shortcut.SOFT_DAMAGE`・`RETAIN_SCROLL_LAYERS` は削除）。全面で描く比較の基準は `partialRepaint(false)` の描画器で作る。`NEW_PATH_SOURCES` に M3b-3〜4.7・M5 で足したクラスを入れた。M3b のヒープの目標は測り直して `≤ 28.0 / 9.0 MB` に置き直した（シーン以外の段だけで 14.02 / 5.28 MB。計画書 §1.4 決定 J・§4.1）。`:core:heapBreakdown` はビュー・窓が出力を持つ欄を名前で外すので、シーン・列・箱を持つ欄を足したら `V3HeapBreakdownMain#releaseStages` にも足す（足し忘れると段が 0 になり全部が「測りきれない差」へ落ちる。`-Pheap.detail=true` で段ごとのクラスと命令の調べ）。
- **層の使い回し（M3b-4.11）**: `SceneEmitter#buildBlock` は前回の層を鍵 `(断片 ==, 最新の箱 ==)` で使い回す。親の文脈（flex・grid の子か・浮動を止める親か）は親の `display` だけで決まり、子を引く表は親の `display` が前回と同じときだけ渡す（`SceneReuseKeyTest`）。描画される `<legend>` は親の値 `SceneChildren#legend`（子の層の印にしない）。`ops` は final で、命令は断片だけの層から作って `SceneLayer#withOps` で載せる。鍵のほかに残る条件は 2 つ（ブロックの文字の層・表の匿名の箱の通し番号。M3b-5 で層・箱の値にする）。前回の子を引く表は作り直すチャンクの子だけで作り、流れの到達範囲はチャンクの値 `SceneChunk#overflow`（`ScrollableOverflow#chunk`）を使い回したチャンクから読む。`backdrop-filter` の有無は層の印 `SceneLayer#backdropBelow`（M3b-4.17。8,000 子の流れでもカリングの外を子の数に比例してたどらない）。
- **文字の測定と記憶（M5-5）**: 行は `InlineFlow`（折り返しの機会は `LineBreaker`、`white-space` の空白の処理は文字の節の中身を作るときの `WhiteSpaceProcessing`、half-leading は `InlineFlow#leadingAbove`）が組み、送り幅は書体（`FontFace#advance`）が答える。記憶は書体の実例が持つ `TextMemo`（§2.3 の L1。アウトライン書体の送り位置・ビットマップ書体の素の送り幅）だけで、`core.text` に static の記憶・`ThreadLocal` は無い（`core/text/model` と `OutlineFontFace` ほかは `NEW_PATH_SOURCES`）。旧 `TextShaper`・`ParagraphSegments`・`TextStyle` の再登場は `ArchitectureHealthTest#legacyTextShaperMemoStaysGone` が落とす。`ShapedLine` は一時アダプタ `SceneAdapterBox` の読まれない口だけが参照し、M3b-4.24b でアダプタと一緒に消す。

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
| 7 | **M3b（改訂）** | **`DisplayScene` と描画の作り直し** | 平坦な `DisplayItem`・旧描画の層・描画用の木・`TreePainter` の記憶・`PaintSceneDriver`・`HitTestIndex`・`Shortcut` を全廃。描画命令を層ごとに詰める（M3b-4.10。§1.5 決定 O） | §3.4 の完了ゲート（中間表現 3 段・ヒープ ≤ 28.0 / 9.0 MB〔M3b-4.8 で置き直した。§1.4 決定 J〕・`Shortcut` 0） | **進行中**（M3b-1〜M3b-3・M3b-4.1〜4.24a 完了。次は M3b-4.24b〔走査器がシーンを直接たどり、一時アダプタを削除〕。§3.4） |
| 8 | **M3c** | multicol（ブロックの断片化） | `LayoutFragment` の断片化と、列ごとの断片の scene | WPT の css-multicol を足し、新経路のラチェットで固定 | 未着手 |
| 9 | **M3d** | property tree と合成の口 | 変形・クリップ・効果・スクロールを `SceneLayer` の値から独立した property tree にし、`DynamicSceneState` を吸収する。描画器の入力を `DisplayScene` と property tree の 2 つにし、スクロールと transform・opacity の変化は木の値だけを替える（§1.5 決定 Q） | §3.5a の完了ゲート（§2.3 の表を書き換える・スクロールとアニメーションの 1 フレームの latency の上限・WPT と 3 者画素一致を保つ） | 未着手（前提: M3b 完了） |
| 10 | **M4** | L5 ホスト層の純化と MOD の循環の解消 | `UiView`/`StandaloneView` → `EngineView` + `InputController` + `StandaloneHost`。論理スレッドと描画スレッドの境界を `EngineView` の口にする（機能拡張の P2 の前提） | L5 の依存が一方向（行数 ≤ 1,200 は目安。§1.6 決定 W）・MOD の循環 0・描画の側が論理の側の可変の状態を読まない | 未着手 |
| 11 | **M5** | DOM の整理と 1,000 行超のクラス 0 | `DocumentArena` の二重同期の解消・`HtmlNode` の分割・`SelectorIndex` の独立・DOM・断片・行の詰めた表現（M5-4。§1.5 決定 O）・文字の測定の記憶を §2.3 の L1 の形に（M5-5。§1.7） | 1,000 行超 0・ヒープ ≤ 12.0 / 4.5 MB（M5-4 の実測で置き直す） | **進行中**（M5-1・M5-2・M5-3a・M5-5 完了。DOM の側は M3b と並行。§3.7） |
| 12 | **M6** | 品質ゲートの最終固定と配布（v2.0.0 GA） | 行数のゲートの固定（1,000 行超 0・600 行超は理由つきの一覧。§1.6 決定 W）・Linux CI・API 署名の確定・Maven Central | 1,000 行超 0・600 行超の一覧が増えない・グローバルな可変の static 0 | 未着手 |

### 3.0b 独立トラック（Track P）→ 機能拡張プランへ分離（2026-11-28）

本書（本流 M0〜M6）はエンジン内部のデータ構造の刷新（リファクタリング）だけを扱う。新しい機能・ホスト・配布の課題（Track P1〜P6。位置づけ〔JavaFX と並ぶ汎用 UI 基盤〕・純 Java の定義と Java の版の線引き・優先順位表・詳細・KPI）は [`ENGINE_FEATURE_PLAN.md`](ENGINE_FEATURE_PLAN.md) に分けた。Track P の段の前提になる本流の段（M3b・M4・M6）は本書の §3.0a で管理する。

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

### 3.4 Phase M3b（改訂）: `DisplayScene` と描画の作り直し〔進行中。M3b-1〜M3b-3・M3b-4.1〜4.24a 完了〕

平坦な `DisplayItem` に潰してから `PaintSceneDriver` で階層を復元する往復をやめる。同時に、M2c で凍結した描画の例外を全て消す。

**段（2026-11-27 に分けた）**: `DisplayItem` の参照は `:core` 本体 59・テスト 94・MOD 50 ファイルあり、1 回では置き換えられない。入力側（DOM の写し）→ scene の生成 → 問い合わせ → バックエンドの順に、各段で置き換えた型を同じ段で消す。scene からバックエンドへの一時アダプタだけは M3b-4 の中で消すまで残す（§2.3 の期限付きの例外に載せてから置く）。

**段の番号（2026-11-28 に付け直した）**: M3b は 5 段（M3b-1〜M3b-5）。M3b-4 の中の小さな段は通し番号 `M3b-4.N` で並べる（本書では「4.N」と略す）。段を分けるときは枝番を重ねず、まだ始めていない段なら `4.Na`・`4.Nb` の 1 段だけにする。それより細かく分けるなら、未着手の段の番号を振り直す（完了した段の番号は変えない）。段ごとの作業記録はコミットの本文に書く。

| 段 | 内容 | 状態 |
| :--- | :--- | :--- |
| **M3b-1** | **描画は DOM を読まない（決定 F）**: 描画が読む DOM の値（属性・textarea の中身・状態の規則が掛かるか）を `BoxTreeCompiler` が `BoxNode.values`（`DomValues`）に写す | **完了**（2026-11-27）。`ArchitectureHealthTest#paintDoesNotReadDomValues` |
| **M3b-2** | **`DisplayScene` と `SceneEmitter`**: 親相対・`int nodeId` の `SceneLayer` の木。記憶は `(断片 ==, 最新の箱 ==)`・`FlowChunk ==` だけ。スクロール・sticky・アンカーの追従は `DynamicSceneState` | **完了**（2026-11-27）。描画用の木・`TreePainter`・`Fragment`/`FragSeq`・凍結例外 4 つを削除（`:core` −2,168 行） |
| **M3b-3** | **問い合わせ**: 当たり判定・CSSOM・フォーカス・インスペクタを scene から答える | **完了**（2026-11-27）。`SceneHitTest`・`SceneQuery`。`HitTestIndex`・`UIHitResult`・`SceneBox`・`PaintBox`・`PaintRun` を削除 |
| **M3b-4** | **描画命令と走査器**: 層ごとの描画命令・全バックエンドの描画を走査器 1 本に・表示項目の列と一時アダプタの削除（下の 4.1〜4.30） | **進行中**（4.1〜4.24a 完了） |
| 4.1 | **描画命令**: 項目の生成器が層の局所座標の命令を作り、`SceneEmitter` が層を作るときだけ作る | **完了**（2026-11-27） |
| 4.2 | **鍵の範囲**: 文字列から作る鍵と節の鍵の値の範囲を分ける | **完了**（2026-11-27） |
| 4.3 | **補間の状態を節ごとに**: `AnimationStates`。後片付けは実時間でなく列の構造の変化の印。走査器 `SceneWalker` を影で動かす | **完了**（2026-11-27）。実文書 9 本の全フレームで旧駆動器と一致 |
| 4.4 | **スクロール量を焼き込まない**: 中身をスクロール 0 で組み、平行移動で足す | **完了**（2026-11-27）。大きさ 0 の要素の子孫が祖先の変形から外れる欠陥を直した |
| 4.5 | **描き直す範囲をシーンの違いから**（`SceneDamage`）。保持層を中身の層の子の並びに結ぶ | **完了**（2026-11-27） |
| 4.6 | **走査器を唯一の描画の経路に**（CPU・GPU・Vulkan・MOD の面） | **完了**（2026-11-28）。`PaintSceneDriver`・`DamagePlanner`・`RetainRanges`・項目の再生・`Shortcut` 4・`EngineFlags` 5 を削除（`:core` −2,259 行） |
| 4.7 | **描かないフレームの判定を描画器へ**（`needsRepaint`） | **完了**（2026-11-28）。`StaticFrameGate`・`ItemDiff`・`AbsoluteItems` を削除 |
| 4.8 | **点検の直しと測り直し**（§1.4 の 7・11・12） | **完了**（2026-11-28）。切り替え 3 つを描画器の設定へ。ヒープの目標を 28.0 / 9.0 MB に（決定 J） |
| 4.9 | **残す部品が `SceneLayer` を直接読む**（§1.4 の 4） | **完了**（2026-11-28）。`PlacedLayer`・`PaintBounds#place` |
| 4.10 | **詰めた命令 `DrawOps`**（`int[]`＋参照の表。§1.4 の 2・8、決定 O） | **完了**（2026-11-28）。シーンの段 35.30 → 15.80 / 8.08 → 5.67 MB |
| 4.11 | **メタを語の表に・`ops` を final に・使い回しの条件を鍵と 2 つに**（§1.4 の 3・9） | **完了**（2026-11-28）。シーンの段 13.77 / 4.72 MB |
| 4.12 | **描画器の口を 1 つのフレームの値に**（`RenderFrame` → `prepare` → `render(PreparedFrame)`。決定 V） | **完了**（2026-11-28） |
| 4.13 | **ホストはビューのフレームの値を渡す**（`UiView#renderFrame`・除く節 `ExcludedNodes`） | **完了**（2026-11-28）。HUD の絞り込みが当たっていなかった欠陥を直した |
| 4.14 | **列の外の読み手を 1 つの入口に寄せ、ゲートを描画込みに** | **完了**（2026-11-28） |
| 4.15 | **列を描画器の準備で組む**（`FrameComposer`。ビューの列を削除） | **完了**（2026-11-28）。ビューの保持ヒープ 47.4 → 27.5 / 13.6 → 8.8 MB |
| 4.16 | **見えるチャンクだけを組む**（層の包含矩形を塗る範囲に） | **完了**（2026-11-28）。hover 36.41 → 1.00 ms・連続スクロール 27.37 → 1.44 / 42.88 → 1.12 ms |
| 4.17 | **カリングの外で子の数に比例する処理を消す** | **完了**（2026-11-28）。`large-list-8000:child` 7.17 → 2.10 ms |
| 4.18 | **列の階層 `FrameStructure` を組む側が書く**（走査器の鍵の索引・`ScrollContentRanges` を削除） | **完了**（2026-11-28） |
| 4.19 | **効果の層の中身を階層から引く**（`LayerSpec#span`・`maskFrom` を削除） | **完了**（2026-11-28） |
| 4.20 | **本体と MOD の読み手をシーンの問い合わせへ**（IME の位置・面のキャレット・ツールチップ） | **完了**（2026-11-28） |
| 4.21 | **MOD の中身を準備が集める値から用意する**（`HostContent`。MOD は準備した列を読まない） | **完了**（2026-11-28） |
| 4.22 | **列を組む口を本体から消す**（列はテストの道具 `TestItems` だけが組む） | **完了**（2026-11-28） |
| 4.23 | **描画器の口を層の文脈に**（`LayerContext`。列の位置で走査器に問わない） | **完了**（2026-11-28） |
| **4.24a** | **走査器は列を前から 1 回だけ読む**（4.24 の着手前の点検で分けた。§1.4 の 1・13 ②）: 描く前に列を先にたどっていた処理を、項目の位置で決まる形か前の走査の記録に替える。グループを層にするかは部品が 1 つのうち項目を 1 つ待たせて次の項目で決める（列の全部の部品の数え `members` を削除）・補間と外の事情の描き直す範囲と描く前の判定は前の走査で描いた項目の記録 `OutsideSceneRecord` から（描く前の列の全部の補間の状態の引き直しを削除）・保持層は範囲の始まりで 1 度だけ問う（位置ごとに範囲を全部なめる `retainAt` と前もって問う輪を削除）・平行移動は節の姿勢を作るときに引く | **完了**（2026-11-29）。`walkerReadsTheListOnceForward`。`SceneWalkerTest`・`SceneWalkerRetainTest` に 4 件。変異 7 つが全部落ちる |
| **4.24b** | **走査器がシーンを直接たどる**（§1.4 の 1）: 組み立てを走査器へ移し、層の文脈（姿勢・グループ・クリップの持ち主・保持層）を層の階層のたどりで持つ。一時アダプタ（`SceneDisplayListAdapter`・`SceneAdapterBox`・`OpComposer`・`ItemSink`）・節の錨だけの命令 `NODE`・`FrameStructure` を削除。列を読むテストは `TestItems` を走査器の組み立てに合わせて書き直す。節 → 層の索引は 1 対多を返せる形に（決定 V）。**4.24a の点検で残したシーンから答えるもの**: ①準備で集めるホストの描く中身（`HostContent`。今は列を組みながら集める。見えるチャンクの命令をカーソルで読んで集める）②保持層の中身が層に向くか・中身のクリップ（今は範囲の項目を先に読む。中身の見える層の命令から・コンテナの子のクリップから）③`::backdrop` の親の錨（祖先の錨より先に描くので、今は組み終えてから位置を引く。箱の姿勢を先に作る）。列の後片付けの印（`AnimationStates#retainNodesOf(列)`）はたどった節に印を付ける形に | 未着手 |
| **4.25** | **保持層の候補を実際にスクロールするコンテナだけに**（§1.4 の 13、決定 Y）: 切り抜くだけの `overflow: hidden` を、保持層の候補と中身を 1 つの節にまとめる対象から外す（列の位置ごとに範囲を全部なめる照合 `retainAt` は 4.24a で消し、保持層は範囲の始まりで 1 度だけ問う。層ごとに決めるのは 4.24b） | 未着手 |
| **4.26** | **カリングを動いている節で決める**（§1.4 の 13、決定 Y）: 描くフレームのカリングと当たり判定の絞り込みから外すのは、宣言（`FLAG_DYNAMIC_BOUNDS`）でなく、いま補間・アニメーションが動いている節（`AnimationStates`。M3d からは property tree）を含むチャンクだけ。静的な `transform` は包含矩形に変形後の範囲を入れる。ゲート: `card-grid-3000:scroll`（575.9 ms）を `large-list-8000:scroll` と同じ桁へ（上限は直した後の実測。決定 R）、フレーム数を 40 + 200 に戻す。正しさ: `FrameCullingTest` にカードの格子（補間の途中のフレームを含む） | 未着手 |
| **4.27** | **CatalogApp で確かめ、残りを段へ振り分ける**（決定 Y）: 4.25・4.26 の後、デモの `CatalogApp`（3,000 枚・1280×800。GL の窓と CPU の描画器）でスクロール（ポインタをカードの上に置いたまま・毎秒の一部書き換えを含む）と開く時間を段ごとに測り（`StageProfiler`・JFR）、残る重さを担う段を本表と §4 に書いてから直す（見込みの値は掲げない。決定 R）。分かっている残り: ① `transition` があるとスクロールのフレームのたびに箱の木を作り直す（120 フレーム中 120 回。無いと毎秒の更新の 4〜5 回。原因は未特定） ② `transition` を外しても 1 フレーム約 33〜45 ms（列の組み直し約 7.6 ms。残りは未計測） ③ 開く時間 約 0.4〜2.0 秒。測った場面はゲートの文書（`card-grid-3000` にホバーを動かすスクロールと毎秒の更新の場面を足す）にして固定する | 未着手 |
| **4.28** | **GPU と CPU の呼び合いを解く**（§1.4 の 5）: `GpuRenderBackend` が `cpu.renderOnto(…, this)` で自分を `GpuSink` として渡す形・`GpuReplaySink` をやめ、GPU が描けない項目だけを CPU の描画器へ渡す一方向にする | 未着手 |
| **4.29** | **命令を語から直接読む**: 描画側は命令のメタ・ID を詰めた語からカーソルで読み、フレームごとにメタ・ID のオブジェクトを作らない（4.10・4.11 で組み立てに移った割り当てを消し、期限付きで更新した `costGate` の基準を戻す）。スクロールバーを持つコンテナの描き直す範囲をつまみの矩形に絞る | 未着手 |
| **4.30** | **`DisplayItem` の削除と緩和の戻し**: MOD とテスト（`TestItems`・`TestFlatItems` ほか）から `DisplayItem`・`PaintCell`・`RenderHints`・`RenderMeta` を消す。命令の描画 ID の綴り（持ち主の ID・接尾辞・通し番号の語）も消す。4.1 からの期限付きの緩和を戻す | 未着手 |
| **M3b-5** | **完了ゲート**: 下の完了ゲート（ヒープ・latency の上限を戻す・`Shortcut`/`EngineFlags` 0）と §4。先に `memoryAndLatencyGate` の latency を同じ実行の基準の仕事との比で判定する形にする（絶対値は記録。§1.6 決定 T） | 未着手 |

- **完了した段の設計と結果**: 各段のコミットの本文。
- **データモデル（`core/paint/scene`）**
  ```java
  public final class DisplayScene {            // 旧 DisplayItem[] + Fragment + PaintSceneDriver を置き換える
      public final SceneLayer root;
      public final Int2ObjectMap<SceneLayer> byNodeId; // 動的オフセット・当たり判定・スクロール用の O(1) 索引
  }
  public final class SceneLayer {
      public final int nodeId;                 // 文字列 ID なし
      public final float x, y, w, h;           // 親相対座標（unstuck / スクロール 0 基準）
      public final Transform2D transform;      // 固定の変形（アニメーション中は外の表で上書き）
      public final ClipShape clip;             // 角丸クリップ・clip-path
      public final LayerEffects effects;       // opacity / filter / backdrop-filter / mix-blend-mode / mask
      public final StickyConstraint sticky;    // position: sticky のときだけ非 null
      public final SceneLayer[] stickyDescendants; // スクロールコンテナだけ（カリングの外の sticky の回収）
      public final DrawOps ops;                // この節自身の描画の中身（行の断片・描画命令・メタの表を詰めた int[]＋参照の表。M3b-4.10・4.11）
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
  - ヒープ: `varied-list-2000 ≤ 28.0 MB`・`large-list-2000 ≤ 9.0 MB`（M3b-4.8 で置き直した。§1.4 決定 J）。根拠は §4.1 の内訳: シーン以外の段だけで 14.02 / 5.28 MB あり、以前の目標 14.0 / 5.5 MB（M2b の中間目標）はシーンを 0 にしても届かない。M3b-4.11 の後のシーンは 13.77 / 4.72 MB（メタを語の表に、持ち主と鍵を層に 1 つにした）。表示項目の列（18.92 / 4.49 MB。ID の文字列と組み立てるたびに戻すメタを含む）は M3b-4.24b で、節の錨の命令は M3b-4.24b・ID の語は M3b-4.30 で消え、見込みは約 26.2 / 8.7 MB（§4.1）。以前の目標は M5 で DOM・箱の木を減らしてから見直す。
  - 性能（描画を含む 1 フレーム）:
    - 8,000 行リストの 1 件挿入 `≤ 1.0 ms`
    - `showcase` の幅の変更で scene を作る時間 `0.273 → ≤ 0.08 ms`
    - `varied-list-2000 fontsize` は下の 1 スレッドの上限で判定する（4 スレッド・実書体の `10.76 → ≤ 5.0 ms`〔144Hz の予算 `6.94 ms`〕は並列の再スタイルが前提なので、2026-11-28 に P2 の目標へ移した。§1.4 決定 K。値は P2 のスパイクで置き直す。§1.6 決定 R）
    - hover・文字の変更は `ab-inc-vs-full` の上限を締め直す
    - M2c-2 で期限付きで置き直した latency の上限を戻す（`varied-list-2000:fontsize ≤ 46.6 ms`・`large-list-8000:child ≤ 16.5 ms`。1 スレッド・FALLBACK）。判定は同じ実行の基準の仕事との比に直した上限で行う（§1.6 決定 T。セッションの差が上限の幅より大きいため）
  - 正しさ: 3 者画素一致 100%・`FrameOracle`・新経路の WPT `≥ 170/260`。8,000 行のスクロールで見えないチャンクを飛ばすこと・sticky が `FrameOracle` と 100% 一致すること。

---

### 3.5 Phase M3c: multicol（ブロックの断片化）〔未着手。前提: M3b 完了〕

M2c-3 で旧経路と一緒に一時的に失う multicol を、新経路で作り直す。`LayoutFragment` に「1 つの要素の箱を列ごとの断片に分ける」断片化を足し、列ごとの断片を scene の `SceneLayer` として出す。ゲート: WPT の css-multicol の文書を参照画像の集合に足し、`WptV3BaselineTest` のラチェットで固定する。§2.3 の段は増やさない。1 つの節が複数の層になるので、節 → 層の索引（`DisplayScene#byNodeId`）と `SceneQuery` は 1 対多で答える（口は M3b-4.12〜4.29 で先に用意する。§1.6 決定 V）。

---

### 3.5a Phase M3d: property tree と合成の口〔未着手。前提: M3b 完了〕

今は、変形・不透明度・クリップが層の値（`SceneLayer` の欄と描画命令）に混ざり、スクロール・sticky・アンカー追従だけが `DynamicSceneState` の平行移動として分かれている（G8）。Chrome の property tree と同じく、これらを独立した値にして、変化したときに層を作り直さずに描けるようにする。§1.5 決定 Q で、表現（本段）・スレッドの境界（M4）・並列に動かすこと（機能拡張の P2）に分けた。

- **内容**: ①transform・clip・effect（不透明度・フィルタ・合成）・scroll の 4 本の木を、`DisplayScene` と並ぶ不変の値として持ち、層は木の節の番号だけを持つ。②`DynamicSceneState` のスクロール・sticky・アンカー追従を scroll の木へ吸収する。③描画器・`SceneHitTest`・`SceneQuery`・`SceneDamage` は木を読んで位置とクリップを解く。④CSS のアニメーションとトランジションのうち transform・opacity は、木の値だけを替える（層と描画命令を作り直さない）。⑤§2.2 の図と §2.3 の表を先に書き換える。木は毎フレームの値で、使い回しは変わらない節の `==` だけ。記憶の鍵は増やさない。描画器の口と動的な値の読み口は M3b-4.12〜4.29 で作った形のまま使い、中身だけを property tree に替える（§1.6 決定 V）。
- **候補（§1.7 の 3・4。最初にスパイクで採否を決める）**: ⑥遮蔽の間引き（不透明な矩形を手前から集め、隠れた層とチャンクを走査で飛ばす。GPU の深度つきの手前からの描画は費用を測ってから）。⑦チャンクごとの GPU の頂点のバッファ（鍵は `SceneChunk ==`。§2.3 の表の L4b の行）。採るものは同じ段で毎フレームの組み立ての経路を消す（両立させない）。
- **完了ゲート**: 運用ルール 7 の 3 点（`DynamicSceneState` と層の欄に残る変形・クリップの写しを削除・テスト緑・§4 の更新）。スクロールと transform・opacity のアニメーションの 1 フレーム（描画まで）の latency の上限を `memoryAndLatencyGate` に新設する（値はスパイクで決める）。WPT `≥ 170 / 260`・3 者画素一致・`FrameOracle`・差分描画の画素の番人が緑。ヒープの増分の上限を §4 に置く。

---

### 3.6 Phase M4: L5 ホスト層の純化と MOD の循環の解消

- `UiView`（1,308 行。2026-11-28）・`StandaloneView`（937 行）を次の 3 つに分ける。
  - **`EngineView`**（≤ 400 行）: DOM・スタイルシート・`ChangeLedger`・`BoxTreeCompiler`・`LayoutCache`・`DisplayScene`・`ScrollState` を持つ 1 画面の純 Java の核。
  - **`InputController`**（≤ 500 行）: 当たり判定・ポインタ・フォーカス移動・IME/テキスト編集（`TextEditing` と MOD `TextareaEditor` を統合）・範囲選択・`<select>` のポップアップ。
  - **`StandaloneHost`**（≤ 250 行）: 窓の座標とクリップボードの接続だけ。
- MOD の `SessionContext` / `EnderScreenDelegate`（1,298 行 → ≤ 450 行）から Minecraft のサーバー同期の残骸（`serverManaged`・`headlessState`・`lastSyncRequestTime`・`shouldRequestSync()`・`explicitSize`・`applyOverrides()`）を剥がす。
- MOD の 5 組のパッケージの循環（`client ↔ client.gui ↔ client.renderer ↔ client.renderer.surface`）を一方向にする。
- **論理スレッドと描画スレッドの境界**（2026-11-28。§1.5 決定 Q。機能拡張の P2 の前提）: `EngineView` は、レイアウトまでを解く論理の側（ワーカーで動かせる）と、確定した `DisplayScene` と property tree（M3d）を受け取って合成する描画の側の 2 つの口を持つ。受け渡しは不変のシーンの参照を渡すだけにし、描画の側はレイアウトの途中の状態を読まない。MOD は Minecraft の描画スレッドで描画の側だけを呼ぶ。この段では口を分けて 1 スレッドで動かすまでとし、並列に動かすのは P2。
- **行数の扱い**（§1.6 決定 W）: 上の `≤ 400 / 500 / 250 行` は目安で、合否には使わない。`InputController` は役割（ポインタ・フォーカス・テキスト編集と IME・選択・ポップアップ）ごとの部品に分け、各クラスは新規・刷新の `≤ 600 行` に従う。判定は、ホストの状態が `EngineView` と `InputController` の系だけにあること・MOD とスタンドアロンが同じ部品を使うこと（編集・ポップアップ・入力の重複 `0`）。
- **完了ゲート**: L5 の依存が一方向で、Minecraft 由来の状態が `:core` に `0`（行数の合計 `≤ 1,200 行` は目安）・MOD の循環 `0`・描画の側が論理の側の可変の状態を読まない（`ArchitectureHealthTest`）・`runGameTestServer` と実機スクリーンショットの回帰が全て合格。

### 3.7 Phase M5: DOM の整理と 1,000 行超のクラス 0〔進行中。M5-1・M5-2・M5-3a・M5-5 完了〕

- `DocumentArena` が `HtmlNode`（1,398 行）を写して持つ二重構造を解く。`HtmlNode`/`HtmlElement` を、節の基本構造・属性・DOM API・イベントの配送に分ける。セレクタの高速化に要るもの（atom 化したタグ / id / class・Bloom のビット）だけを `SelectorIndex` として独立させる。
- **段（2026-11-27 に分けた）**: DOM の側（`core.dom`・`core.css` の照合）は描画に触れないので、M3b と並行して先に進める。ヒープと 1,000 行超 0 の判定は描画の側（M3b）とホスト層（M4）の後にまとめる。

| 段 | 内容 | 状態 |
| :--- | :--- | :--- |
| **M5-1** | **番号の表の二重同期の解消と `SelectorIndex`**: `DocumentArena`（オフヒープの列 11 本に親・子・兄弟・タグ・id・Bloom を写し、子の並びと二重に同期）を番号と世代だけの `NodeIds`（`core.dom.node`。ヒープの配列）に置き換える。木の形の正本は子の並びだけにし、前の兄弟は子の並びが保つ位置（`indexInParent()`。O(1)）で引く。class の atom・自分 / 子孫の Bloom は `SelectorIndex` が節の欄で保ち、番号の有無に依らない。`HtmlNode` を番号と鍵（`NodeNumbering`）・木の変更と文書順と複製（`TreeOps`）・属性（`NodeAttributes`）・文字の内容（`NodeText`）・イベント（`NodeEvents`）に分ける | **完了**（2026-11-27）。`DocumentArena` と `OffHeapColumns` の `int`/`long` の列を削除（再登場は `ArchitectureHealthTest#domArenaCopyStaysGone`）。`HtmlNode` 1,398 → 739 行（残りは Node/Element の API の入口）。番号は振らずに照合する（`hasNodeId()` の照会が照合の熱い経路から消えた）。子の並びへ直接まとめて足す経路（`addAll`）が祖先の子孫 Bloom へ広げず `:has()` が当たりを落としていた欠陥と、`remove(Object)`・`removeIf` が節を 2 回外していたのを直した。検証は `SelectorIndexOracleTest`（300 手の変更の後も作り直した木と照合が一致）・`ChildListIndexTest`・`NodeIdentityTest`・`NodeIdsTest`。各修正を戻す変異で落ちる |
| **M5-2** | `HtmlElement`（980）・`HtmlParser`（1,214）・`Bindings`（1,175）・`InteractionState`（1,039）の分割（描画に触れない DOM の側） | **完了**（2026-11-27）。パーサを公開の入口 `HtmlParser`（165）・1 回の解析の状態と挿入モード `TreeBuilder`（564）・書式要素の一覧と adoption agency `ActiveFormatting`・表の挿入モード `TableModes`・`ParserText`・`TagSets` に、`Bindings`（584）から `ReactiveDomBinder`・`TemplateDeps` を、`HtmlElement`（634）から `HtmlDOMTokenList`・`HtmlCSSStyleDeclaration`・`TopLayerOps` を、`InteractionState`（602）から `StateFlags`・`StyleInvalidation` を出した（入れ子の型は最上位へ。`Bindings.DEP_ALWAYS` は `TemplateDeps.ALWAYS` へ。MOD の `ArchitectureTest` の「バインディングの解釈は 1 か所」は、この 3 ファイルを 1 か所と数える）。M3b-3 が触る当たり判定・フォーカス・CSSOM の部分（hover / active / focus の鎖・`LayoutQuery`）は動かしていない。前後の要素の兄弟は子の並びの位置から引く。新しい `TreeBuilderFuzzTest`（600 件の乱数のタグの並びを文書と 8 種の文脈の断片で読み、木の形の不変条件を確かめる）が、子を新しい親へ足してから古い並びを空にする順で移った子が仮想の節にされ id 索引と番号を失う欠陥（M5-1 より前から）を見つけ、子の並びの側で直した。分ける前のコミットと同じ 600 件の木を書き出して突き合わせ、全件一致（404 KB） |
| **M5-3a** | 分けたクラスを各 ≤ 500 行に（`HtmlNode` 739・`HtmlElement` 634・`InteractionState` 602・`Bindings` 584・`TreeBuilder` 564）。描画に触れない DOM の側なので M3b と並行 | **完了**（2026-11-27）。公開の API は変えずに役割で分けた: `HtmlNode` 447（DOM の API の入口）と上位の `NodeState` 320（木の形・番号と鍵・セレクタの足切りの欄、文書への知らせ・子の付け外し・属性の版。継ぐのは `HtmlNode` だけ）、`HtmlElement` 480 と上位の `ElementCssomView` 171（CSSOM View の位置・大きさ・スクロール）、`InteractionState` 425 と `InteractionChains` 233（hover・active・focus-within の鎖・Top Layer の積み順・不活性）、`Bindings` 486 と `DeclarativeBindings` 114（`data-bind`・`data-list`・`data-hud`・`data-tracker` の走査。MOD の `ArchitectureTest` は 4 つで解釈の 1 か所と数える）、`TreeBuilder` 358 と `BodyMode` 234（in body）。分ける前のコミットと `TreeBuilderFuzzTest` の 600 件の木（文書と 8 種の文脈の断片）を書き出して突き合わせ、全件一致（404 KB） |
| **M5-4** | **DOM・断片・行の詰めた表現**（§1.5 決定 O・P。M5-3b の完了ゲートの前）: ①`NodeState` の public の欄（`attrs`・`children`・`text`・`parent`・`ownerDocument` ほか）を隠し、読み書きを API に集める（MOD・テストの直接の読み書きも置き換える。表現を替えられるようにするのが目的）②属性を atom の番号の配列にする ③節の欄を、番号と世代（`NodeIds`）で引く構造体の配列を正本にする形を、先にスパイクで費用と API への影響を測ってから決める（旧 `DocumentArena` のような写しは作らない）④断片木の行と文字の断片を `ChunkedFlow` の塊ごとの配列に詰める（候補: 整形した glyph の番号と送り幅を行ごとに詰めて文字の命令を行単位にする・折り返しを送り幅の累積和の二分探索で解く。§1.7 の 1・2。先にスパイク）。①②は M3b と並行でき、③④は M3b の後。ゲート: §4.1 の DOM・ビュー・箱の木・断片木の段の合計（今 11.13 / 4.06 MB）を半分以下・`SelectorIndexOracleTest`・`TreeBuilderFuzzTest` 緑 | 未着手 |
| **M5-5** | **`TextShaper` の記憶を `EngineContext` に閉じる**（2026-11-28 新設。§1.7 の 5。描画に触れないので M3b と並行でき、機能拡張の P2 の前提）: static の表 `SHARED_SEGMENTS`・`ThreadLocal` 3 つ・2 世代の `ConcurrentHashMap` を、§2.3 の表の L1 の行の 1 層の記憶（大きさに上限）に置き換え、同じ段で旧い表を消す。`TextShaper`（1,000 行超）を役割で分ける。ゲート: `TextShaper` の static の可変の表と `ThreadLocal` `0`・`fontsize` と開く時間が上限内・WPT `≥ 170/260` | **完了**（2026-11-29）。着手の点検で、旧 `TextShaper` の整形・折り返し・区切りとその記憶（1,065 行）・`ParagraphSegments`・`TextStyle`・算出スタイルの `TextStyle` の覚え・`EngineFlags.TEXT_CACHE` は新経路から呼ばれない残骸と分かり、削除した（新経路は `InlineFlow` が行を組む）。残る 2 つの役割は空白の処理 `WhiteSpaceProcessing` と `InlineFlow#leadingAbove` へ移した。新経路が実際に使う記憶（アウトライン書体の送り位置〔static の照会用の鍵 `ThreadLocal`〕・ビットマップ書体の素の送り幅〔書体ごとの `ThreadLocal` の表〕）を、書体の実例が持つ上限つきの表 `TextMemo` にした（§2.3 の L1。置き場を `EngineContext` から書体へ改めた理由は同表）。`BidiText` の作業用の `ThreadLocal` も外し、全ての字で `Bidi#requiresBidi` と突き合わせて、速い読み飛ばしが U+FEFD・U+FEFE を飛ばしていた欠陥を直した。`:core` の static の `ThreadLocal` 13 → 5（`ArchitectureHealthTest` の上限 14 → 5）、`EngineFlags` 18 → 17、`:core` −1,029 行。検証は `TextMemoTest`（鍵・上限・8 スレッドで共有した記憶が測り直した値と一致）・`OutlinePositionMemoTest`（6 スレッド・欄より多い語）・`BidiTextTest`（全ての字）・`WhiteSpaceProcessingTest`。鍵から指定を外す変異・`core/text/model` に `ThreadLocal` を戻す変異で落ちる。交互 A/B は §0 の性能の行（`fontsize` −7.1%・ほかは揺れの幅の中）。共有した表の組の中で 2 列目の当たりを 1 列目へ上げない形では `child` が +4〜7% 遅く（2 回）、上げる形に直して 2.48 → 2.49 ms |
| **M5-3b** | 完了ゲート（M3b・M4・M5-4・M5-5 の後）: 下の完了ゲート（1,000 行超 0・`DocumentArena` の二重同期 0・保持ヒープ）。M5-3a で行数を合わせるために挟んだ上位クラス（`NodeState`・`ElementCssomView`）を委譲の部品に直す（§1.4 の 10・決定 L） | 未着手 |

- **完了ゲート**: `:core` と MOD の全体で 1,000 行超のクラス `0`（`ArchitectureHealthTest` で強制）・`DocumentArena` の二重同期 `0`・分けたクラスは各 `≤ 500 行`・全体の保持ヒープ `varied-list-2000 ≤ 12.0 MB`・`large-list-2000 ≤ 4.5 MB`（2026-11-28 の測定ではシーン以外の段〔DOM・ビュー 5.81 / 1.54・箱の木 3.93 / 2.03・断片木 1.39 / 0.49・プロセス共有の表 2.89 / 1.22 MB〕だけで 14.02 / 5.28 MB ある。M5-4 の後に、DOM・箱の木の内訳〔§4.1〕から届く値を測り直して置き直す）。

### 3.8 Phase M6: 品質ゲートの最終固定と配布（v2.0.0 GA）

- `ArchitectureHealthTest` の行数のゲートを固定する（§1.6 決定 W）: **1,000 行超 `0`** はハード、新規・刷新のクラスは `≤ 600 行`。600 行超の既存のクラスは理由つきの一覧で持ち、一覧は増やさない（「全クラス ≤ 600 行」は取り下げた。行数を合わせるための分割が §1.4 の 10 の歪みを生んだため）。グローバルな可変の static を `0` にする。
- Linux の全件（`scripts/ci-linux.ps1 -Full`）を通し、公開 API の署名（`ApiSignatureTest`）を確定し、`:core` を単体のライブラリとして Maven Central / GitHub Packages に公開する。

---

### 3.9 独立トラックの詳細 → [`ENGINE_FEATURE_PLAN.md`](ENGINE_FEATURE_PLAN.md) §3

2026-11-28 に機能拡張プランへ移した（P1a・P4-1 の結果と、P4-2 へ持ち越す結合も同書にある）。

---

## 4. KPI ダッシュボード

時間の主張は交互 A/B の中央値だけ（絶対値はセッションで ±40% ずれる）。「現在」の列は作業のたびに上書きする。**2026-11-28（M3b-4.8）に M3b-4.2〜4.7 の後のコードを同じ条件で測り直し、以後は段ごとに上書きしている（最新は M3b-4.24a の後）**: ゲートの latency・ヒープは `memoryAndLatencyGate`（FALLBACK 書体・1 スレッド。M3b-4.14 から CPU の描画器で差分描画するところまでを測る。それまではシーンと表示項目の列までで、ラスタを含まない。ヒープはビューの保持で、M3b-4.15 から描画器が組む列を含まない）、ヒープの内訳は直した `heapBreakdown`（§4.1）。M0 着手前の値は条件が違う行がある（各セルに書く）。

| 軸 | 指標 / シナリオ | M0 着手前（2026-11-24） | 現在（2026-11-29。M3b-4.24a・M5-5 の後） | 目標（達成フェーズ） |
| :--- | :--- | :---: | :---: | :---: |
| **構造** | `:core` 本体の行数・ファイル数 | `113,195 行`・515 | **`110,845 行`・622**（`core/src/main/java` の `.java` を `wc -l`。M5-5 で旧 `TextShaper`〔1,065 行〕と `ParagraphSegments`・`TextStyle` を消し、`WhiteSpaceProcessing`・`TextMemo` を足して −1,029 行。M3b-4.24a で前の走査の記録 `OutsideSceneRecord` を足し、走査器の先読みの輪を消して +153 行〔`OutsideSceneRecord` +141・走査器 +12〕。M3b-4.23 で層の文脈 `LayerContext` を足し、描画器の位置で引く覚えを消して +57 行〔`SoftwareRenderBackend` −81・走査器 +43・`FrameStructure` +37〕。M3b-4.22 で列を組む口とビューの一時アダプタを消した。M3b-4.21 でホストの描く中身の値 `HostContent` を足した。M3b-4.19 でグループの範囲を消してマスクの印を足した。M3b-4.18 で列の階層 `FrameStructure` を足し、`ScrollContentRanges`・走査器の鍵の表を消して +233 行（M3b-4.24b で一時アダプタごと消える）。M3b-4.17 でチャンクの到達範囲・印を足して +155 行。M3b-4.16 で塗る範囲・カリングを足して +230 行。M3b-4.13〜4.15 で列から ID を引く口・ビューの列・`ImmutableItemList`・`FrozenItemList` を消し、`FrameComposer`・`ScrollTranslation`・`SceneMedia` ほかを足して +97 行。M3b-4.12 でフレームの値 `RenderFrame`・`PreparedFrame` を足し、`PaintFrame`・`render` の重ね定義・plan → take を消して +47 行。M3b-4.11 でメタの語の表 `MetaWords` を足し、使い回しの条件・`attachOps`・読まれない欄を消して +268 行。M3b-4.10 で詰めた列 `DrawOps`・`DrawOpsBuilder` を足して `DrawOp` を消し +638 行〔表示項目の列を消す M3b-4.24b で組み立てごと減る〕。M3b-4.9 で `PlacedLayer` を足し、読まれない口を消して +152 行。M3b-4.8 で `Shortcut`・`EngineFlags` の切り替え 3 つを消し −6 行。M3b-4.2〜4.7 全体は M3b-4.2 前の約 109,600 行から、走査器・`SceneDamage` を足して旧描画の駆動器・項目の再生・静止判定を消し、ほぼ横ばい） | `≤ 120,000 行`（M2c。**達成**）→ M3b 以降は各フェーズで減らす |
| **構造** | 経路の数 | 1 | **1**（M2c-3 で `EnginePath` と旧経路を削除） | **1**（M2c。**達成**） |
| **構造** | 中間表現の段数（DOM の後〜バックエンドの前） | 6〜7 | **3 段 + 一時アダプタ**（箱の木 → 断片木 → `DisplayScene`〔層が行の断片と描画命令を詰めた列 `DrawOps` を持つ〕。一時 `SceneDisplayListAdapter` が命令を `DisplayItem` に組み立て、項目の親の位置と保持層の範囲を列の階層 `FrameStructure` に書き〔M3b-4.18〕、走査器がその列と階層をたどる。M3b-4.24b で列とアダプタを消す） | **3**（M3b-4） |
| **構造** | 増分・キャッシュ専用のコードの規模 | 42 ファイル・`6,360 行` 相当 | **`2,301 行`**（`ChangeLedger` 156 ＋ `BoxTreeCompiler` 560 ＋ `LayoutCache` 187 ＋ `SceneEmitter` 576（M3b-4.11 で使い回しの条件を減らし、legend を親の値にした。M3b-4.16 で塗る範囲の読み手 +8。M3b-4.17 で作り直すチャンクの分だけ前回の子を引き、チャンクの到達範囲を受け渡して +35） ＋ `SceneDamage` 536（M3b-4.9 でチャンクを飛ばす照合を足して +66、M3b-4.10 で命令をカーソルで読んで +2、M3b-4.16 で余白とフィルターの規則を `DrawOps`・`LayerEffects` と共有して −9） ＋ `FrameDamage` 80 ＋ `ScrollContentRanges` 100 ＋ `FrameComposer` 106〔M3b-4.15 でビューから描画器へ移した列の使い回し。M3b-4.16 でカリングの有無とスクロールの条件 +13。§2.3 L4c〕。`ScrollContentRanges` は M3b-4.18 で削除済み〔合計は次に測るときに直す〕、`FrameComposer` は列とともに M3b-4.30 までに消す） | **`≤ 1,000 行`**（M3b。M3b-4.12〜4.29 の後に見直す） |
| **構造** | `LayoutBox` / `ComputedStyle` / `UiView` の行数 | `1,785` / `2,095` / `2,005` | **削除** / `451` / `1,308`（`UiView` は M3b-4.13・M3b-4.15 で列から ID を引く口・入力の値の書き戻し・列とスクロールの平行移動の表を消して 1,456 → 1,308） | 削除（M2c。**達成**）/ `≤ 450`（M5）/ 解体（M4） |
| **構造** | 1,000 行超 / 600 行超のクラス（`:core` 本体） | `19` / `55` | **`7` / `28`**（`wc -l`。1,000 行超は `UiView` 1,308・`ColorParser`・`SoftwareRenderBackend` 1,097・`CanvasRenderingContext2D`・`GpuRenderBackend` 1,138・`StyleResolver`・`ShaderSources`。`TextShaper` は M5-5 で削除。`core.dom` には無い） | `0`（M5）/ `0`（M6） |
| **構造** | `Shortcut` / `EngineFlags` | `35` / `45`（M0 で凍結） | **`10` / `17`**（M5-5 で `TEXT_CACHE` を消し、上限を 17 に。M3b-4.8 で `SOFT_DAMAGE`・`RETAIN_SCROLL_LAYERS` を描画器ごとの設定にして削除し、`ArchitectureHealthTest` の上限を 10 / 18 に下げた。残りは GL・ソフトウェア描画・シートの雛形の切り替え） | レイアウト系 0（M2c。**達成**）→ `0` / `0`（M3b） |
| **構造** | `static volatile` / `ThreadLocal` | 約 `50` / `27` | `40` / **`5`**（`ThreadLocal` は M5-5 で旧 `TextShaper` の 6 つ・書体の照会用の鍵・`BidiText` の作業用の配列を消して 13 → 5、上限も 5 に。残りは `StyleResolver` 2・`StageProfiler`・`EngineContext`・`LayoutTaskPool`。`Atomic`/`LongAdder` 18・共有エポック 0。当たり判定の作業用の値はビューの文脈が持つ） | グローバルな可変の static `0`（M6） |
| **構造** | MOD の旧レンダラ（`client/renderer`） | `9,369 行` | **旧レンダラ 0**。面を描く経路は `1,277 行`（§3.3） | `≤ 1,500 行`（M3a。**達成**） |
| **性能** | `large-list-8000 child`（1 件挿入） | `7.89 ms` | ゲートの latency **`2.63 ms`**（FALLBACK・1 スレッド・CPU の描画器で差分描画するところまで。M3b-4.17 でカリングの外の比例する処理を消した〔交互 A/B で HEAD〔M3b-4.16〕7.17 → 2.10 ms・−70.7%〕。M3b-4.16 で見えるチャンクだけを組む。交互 A/B で HEAD〔M3b-4.15〕59.31 → 5.64 ms・−90.5%。M3b-4.15 の後は 55.58 ms〔列までは 20.35 ms〕。M0 前の列までの 7.89 ms は条件が違う） | 描画を含めて **`≤ 1.0 ms`**（M3b） |
| **性能** | hover / 文字の変更の 1 フレーム（showcase） | — | `ab-inc-vs-full`（描画を含む）は `0.508x` / `0.195x`（上限 `0.900x` / `0.980x`）。`varied-list-2000:hover` `0.023x`・`large-list-2000:child` `0.123x`（M3b-4.15 の後: 0.300x・0.407x）。交互 A/B で `varied-list-2000:hover` HEAD 36.41 → 1.00 ms（−97.3%） | `ab-inc-vs-full` の上限内（M2c。**達成**）→ 締め直す（M3b） |
| **性能** | `varied-list-2000 fontsize`（4 スレッド・実書体） | `10.76 ms` | ゲートの latency（1 スレッド・FALLBACK）**`72.54 ms`**（CPU の描画器で描くところまで。交互 A/B で HEAD〔M3b-4.15〕120.93 → 68.69 ms・−43.2%。絶対値はセッションで揺れるので比較には使わない） | 1 スレッド **`≤ 46.6 ms`**（M3b。判定は基準の仕事との比。§1.6 決定 T）／4 スレッド・実書体は P2 のスパイクで置き直す（以前の `≤ 5.0 ms` は取り下げ。§1.6 決定 R） |
| **性能** | `showcase` 幅の変更（`resizeexact`・GL） | `0.427 ms`（表示リストが 66%） | ゲートの latency（FALLBACK）`6.05 ms`（M3b-4.15 から描画を含む。それまでは `recomputeLayout` の後のフレームが作り直しを見ず描いていなかった〔1.85 ms〕）。GL の実測は未計測 | **`≤ 0.20 ms`**（M3b） |
| **性能** | `voidwave.html open` | `9.15 ms` | ゲートの latency（FALLBACK）`34.79 ms`（M3b-4.14 から描画を含む〔列までは 15.90 ms〕。M3b-4.16 の後）。`costGate` の確保量は下の正しさの行 | **`≤ 5.5 ms`**（M3b〜M5 + P2） |
| **性能** | スクロールの 1 フレーム（文書全体・内側のコンテナ） | —（条件が違う。旧経路の GL の実フレームは showcase `0.007 ms`・2,000 行 `0.09 ms`。`core/README.md`） | ゲートの連続スクロール（M3b-4.16 で新設。1 段 30px・200 フレーム・CPU の描画器で描くまで）: 内側のコンテナ `large-list-8000` **`1.41 ms`**（p99 2.70 ms）・文書全体 `page-list-8000` **`1.22 ms`**（p99 1.52 ms）。同じ場面の HEAD〔M3b-4.15〕との交互 A/B（平均 / p99）: 内側 27.37 / 31.72 → 1.44 / 2.75 ms、文書全体 42.88 / 50.48 → 1.12 / 1.79 ms。`voidwave.html` のスクロール（`ab-gate.ps1`）は 20.53 → 21.38 ms（+4.1%）。`costGate` のスクロールの 1 フレームの確保は変わらない（`large-list` 1,400 B・`voidwave` 1,464 B・`surface_test` 16,392 B〔cpu 159,869 B〕。組み直しの起きない場面） | 上限は M3b-4.16 の実測で置く（決定 R）。スクロールと補間の 1 フレームの上限は M3d で新設（§3.5a） |
| **性能** | スクロールの 1 フレーム（補間と切り抜きを持つカードの格子） | — | ゲート `card-grid-3000:scroll`（2026-11-28 に新設。800×600・CPU の描画器で描くまで・1 スレッド・10 + 40 フレーム）: **`102.1〜116.1 ms`**（M3b-4.24a の後。HEAD〔4.23〕との交互 A/B 3 回の中央値 533.14 → 103.35 ms・0.194x。p99 567.96 → 141.21 ms）。新設したときは `575.9 ms`（最後の回の p99 568.7 ms）。内訳を探る変種（同じゲート・同じ日）: `overflow: hidden` なし 94.5 ms・`transition` なし 30.2 ms。デモの `CatalogApp`（1280×800）は約 557〜795 ms で、JFR の標本の 78% が走査器の `retainAt`（M3b-4.24a で削除。デモは 4.27 で測り直す） | `large-list-8000:scroll` と同じ桁（M3b-4.25〜4.26。上限は直した後の実測で置く。決定 R） |
| **メモリ** | 保持ヒープ `varied-list-2000` / `large-list-2000` | `35.96` / `9.63 MB` | **`27.49` / `8.80 MB`**（ゲート。ビューの保持。M3b-4.15 でビューが列を持たなくなった〔M3b-4.13 47.44 / 13.63〕。描くホストでは描画器が組んだ列〔約 19 / 4.5 MB〕を持つ。M3b-4.10 で命令を詰めて 68.16 / 16.21 → 48.87 / 14.13、M3b-4.11 でメタを語の表にして減った。表示項目の列〔18.92 / 4.49 MB〕は M3b-4.24b で消える。内訳は §4.1） | **`≤ 28.0` / `≤ 9.0 MB`**（M3b。M3b-4.8 で置き直した。§1.4 決定 J）→ M5 で見直す（以前の目標 14.0 / 5.5・M5 の 12.0 / 4.5 はシーン以外の段だけで超える） |
| **比較（記録）** | Chrome との比（同じ書体・1 フレームの CPU 時間。§1.5 決定 N） | 旧経路（2026-11-17）: hover `0.08x`・文字 `0.16x`・スクロール `0.18x`・全面の font-size `0.21x`・1 行の挿入 `1.08x`・開く `0.79x`・幅変更 約 `1.3x` | 新経路では未計測（機能拡張の P7a。本番の値は M3b-4.12〜4.29 の後） | 記録だけ（目安は機能拡張の §4） |
| **比較（記録）** | Chrome との比（保持ヒープ・プロセスのメモリ） | ヒープ `1.55x` / `2.0x`（旧経路。Chrome は逆算で約 26 / 5 MB） | ヒープ約 `2.6x` / `3.2x`（推定）。RSS は未計測 | 記録だけ。目安: M3b の後 約 `1.1x` / `1.8x` → M5-4 の後 約 `0.85x` / `1.3x`（内訳からの計算。`≤ 0.5x` / `0.9x` はシーンを約 4.5 / 1.3 MB にする段を決めてから。§1.6 決定 R） |
| **正しさ** | WPT 参照画像比較 | `160 / 260` | **`170 / 260`**（一覧は `reftest-passing-v3.txt` の 1 つ。旧経路の一覧は削除） | `≥ 170 / 260`（M2c 以降）→ 段ごとの合格率（機能拡張の P7b・P7c） |
| **正しさ** | 3 者画素一致・`FrameOracle` | 緑 | 緑（2026-11-29 M5-5 の後〔4.24a の上〕: `:core:test` 2,858 件〔失敗 0・飛ばし 21。消した旧 `TextShaper` の検証の分だけ減った〕・`:mod:test` 455 件・`costGate` 56 本 OK・`memoryAndLatencyGate` OK〔ビューのヒープ 27.63 / 8.95 MB〕。M3b-4.24a の後: `:core:test` 2,875 件〔失敗 0・飛ばし 21〕・`:mod:test` 455 件・`costGate` 56 本 OK〔cpu の行は基準の ±2%〕・`memoryAndLatencyGate` OK〔交互 A/B の全行 ±2% 以内。`showcase:open`・`resizeexact` は 1 回ずつ 1.10x を越えた回があり、単独で 8 回・6 回測り直して中央値 1.01x・0.97x〕。M3b-4.23 の後: `:core:test` 2,870 件〔失敗 0・飛ばし 21〕・`:mod:test` 455 件・`:mod:runGameTestServer` 5 件・`costGate` 56 本 OK〔`surface_test.html scroll cpu` 158,742 B。HEAD 159,396 B〕・`memoryAndLatencyGate` OK。M3b-4.22 の後: `:core:test` 2,868 件〔失敗 0・飛ばし 21〕・`:mod:test` 455 件・`:mod:runGameTestServer` 5 件・`costGate` 56 本 OK・`memoryAndLatencyGate` OK。M3b-4.21 の後: `:core:test` 2,868 件〔失敗 0・飛ばし 21〕・`:mod:test` 455 件・`:mod:runGameTestServer` 5 件・`costGate` 56 本 OK・`memoryAndLatencyGate` OK。M3b-4.19 の後: `:core:test` 2,864 件〔失敗 0・飛ばし 21〕・`costGate` 56 本 OK・`memoryAndLatencyGate` OK。M3b-4.18 の後: `:core:test` 2,864 件〔失敗 0・飛ばし 21〕・`costGate` 56 本 OK〔確保量は横ばい。2 つの値を行き来する `animation_test.html hover` の行の許容を 10% に〕・`memoryAndLatencyGate` OK。M3b-4.17 の後: `:core:test` 2,857 件〔失敗 0・飛ばし 21〕・`costGate` 56 本 OK〔M3b-4.17 で確保が減った 7 行〔`large-list` の hover −50.8%・text −74.4%・style −60.2%・child −73.1%・`varied-list-500 child` −72.2%・`large-list hover cpu` −20.7%・`animation_test.html hover` −7.8%〕だけ基準を手で更新〕・`memoryAndLatencyGate` OK〔ビューのヒープ 27.50 / 8.89 MB・`child` 2.63 ms〕。M3b-4.16 の後: `:core:test` 2,854 件〔失敗 0・飛ばし 21〕・`costGate` 56 本 OK〔M3b-4.16 で描く cpu の行の確保 −15〜85% を基準に〕・`memoryAndLatencyGate` OK〔ビューのヒープ 27.46 / 8.79 MB・描画込みの `fontsize` 72.54 ms・`child` 6.61 ms・連続スクロール 1.41 / 1.22 ms〕・`:mod:test` 454 件。カリングの正しさは `FrameCullingTest`〔カリングしない列で描いた画素と一致〕・`PartialRepaintRealDocumentsTest`・`RepaintSkipTest`・`GpuFrameSequencePixelTest`・`V3ViewTest`〔sticky がゼロから描いた面と一致〕。`ci-linux.ps1 -Full` は push の前に回す） | 緑を保つ |

### 4.1 新経路の保持ヒープと時間の段ごとの内訳（M3b-4.8。M3b-4.10・4.11 で測り直した。2026-11-28）

ヒープは `:core:heapBreakdown`（`V3HeapBreakdownMain`。ゲートと同じ文書・フレームを回し、出力に近い段から参照を外して GC 後の減りを測る。共有分は先に外した段に入る）。時間は M2c-1/M2c-2 のシナリオ別内訳（`StageProfiler`・FALLBACK・1 スレッド）。

| 文書 | 全体 | 表示リスト・一時アダプタ（M3b-4.24b で消える） | `DisplayScene`（`SceneLayer`/`SceneChunk`・描画命令） | 断片木とレイアウトの記憶 | 箱の木と `Style` | DOM・ビュー | 測りきれない差（プロセス共有の表） |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `varied-list-2000`（M3b-4.8） | 67.91 MB | `18.43 + 0.25` | `35.23` | `1.39` | `3.93` | `5.81` | `2.89` |
| `varied-list-2000`（**M3b-4.10**） | **48.46 MB** | `18.35 + 0.25` | **`15.80`** | `1.41` | `3.90` | `5.78` | `3.00` |
| `varied-list-2000`（**M3b-4.11**） | **46.94 MB** | `18.92 + 0.25` | **`13.77`** | `1.34` | `3.91` | `5.76` | `3.00` |
| `large-list-2000`（M3b-4.8） | 16.69 MB | `3.24 + 0.12` | `8.08` | `0.49` | `2.03` | `1.54` | `1.22` |
| `large-list-2000`（**M3b-4.10**） | **14.29 MB** | `4.04 + 0.12` | **`5.67`** | `0.45` | `1.98` | `1.71` | `0.32` |
| `large-list-2000`（**M3b-4.11**） | **13.80 MB** | `4.49 + 0.12` | **`4.72`** | `0.46` | `1.98` | `1.67` | `0.36` |

| シナリオ（M2c-2 参考） | 1 フレーム | 箱の木 | レイアウト | 描画用の木 | 表示リスト | 表示項目の数（旧経路） |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `varied-list-2000:fontsize` | 79 ms | 26.3 | 10.2 | 17.6 | 22.6 | 97,886（652） |
| `large-list-8000:child` | 31 ms | 0.26 | 0.06 | 5.4 | 22.6 | 80,012（462） |

**`large-list-8000:child` の 1 フレームの内訳（M3b-4.17 の着手前に測った。2026-11-28）**: 400 フレームの中央値（`StageProfiler`・FALLBACK・1 スレッド・CPU の描画器）と JFR の標本の内訳。挿入するのは 250 行目（画面の外）で、見える分は変わらない。

| 時点 | 1 フレーム | DOM の変更〜シーン（`flushInvalidation`） | うち箱の木 / レイアウト / シーン | 描画器の準備（列・描き直す範囲） | 描画（走査・ラスタ） |
| :--- | :---: | :---: | :---: | :---: | :---: |
| M3b-4.16 の後 | 5.92 ms | 3.88 | 0.23 / 0.05 / **3.45** | 0.42 | 1.46 |
| **M3b-4.17 の後** | **1.47 ms** | 0.74 | 0.23 / 0.05 / 0.33 | 0.11（うち列 0.09） | 0.46 |

M3b-4.16 の後の比例する処理（JFR）: シーンの前回の子を引く表 35%（子 1 つに断片・箱・節の 3 件）・`ScrollableOverflow` 30%（流れの全部の子の到達範囲）・走査器の `ScrollContentRanges` の節 → 層の索引 15%・`hasBackdropFilter` の全走査 5%。M3b-4.17 で 4 つとも消した。**残り**（M3b-4.17 の後の JFR）: 描画のラスタ（スクロールバーを持つコンテナは、中身が伸びてつまみが変わると枠ごと描き直す〔`SceneDamage#own` の `wantsScrollbars`〕。見える範囲に比例。M3b-4.29 で範囲をつまみに絞る）・箱の木の `BoxTreeCompiler#chunkIntact`（DOM の子の並びと前回の箱のチャンクを毎回 8,000 個照合。M5-4）・`Scene3DDomSync#find`（DOM の変わったフレームごとに DOM を全部たどる。M5-4）・DOM の子の並びの番号の振り直し。見込みは掲げない（決定 R）。

**測り方を直した（M3b-4.8）**: 道具が M3b-4.6 で足した `UiView.cachedSource`・`cachedDynRoot` を外さず、局所変数がビューを生かしていたので、全段 0・差 48.7 MB と出ていた。以前の「共有・GC 差」8.24 / 1.89 MB の大半は DOM・ビュー（5.81 / 1.54）で、残りはビューを閉じても残るプロセス共有の表（シートの雛形・書体の送り幅）。**シーンの段の中身**（`-Pheap.detail=true`）: `varied-list-2000` は層 7,050・命令 97,879（`TEXT` 83,916・`RECT` 7,056・節の錨だけの `NODE` 6,907）。`DrawOp$Item` 10.45・`RenderMeta` 5.97・`RenderHints` 0.94・文字列 ID 約 4.7 MB（命令ごとに 1 つ、計 19.6 万字）・`SceneRun` 3.89 + 0.41・`SceneLayer` 1.40 MB。`events` を持つ命令 84,416、角丸 10,363、`ClipRect`・影・グラデーションは 0。`large-list-2000` は層 4,005・命令 20,010（`NODE` 4,005）で同じ形。**型付きの命令の見込み**: 文字列 ID・`events`・`RenderMeta`・`RenderHints`・`NODE` を持たない小さい命令（矩形 40 B・文字 48 B）にすると −22.1 / −4.9 MB・+4.3 / +0.7 MB、文字の命令と `SceneRun` の写しを片方にすると −4.3 / −0.6 MB で、シーンは約 13.1 / 3.4 MB。列が消えると全体は約 27 / 8.7 MB（決定 J の 28.0 / 9.0 MB の根拠）。
**M3b-4.10 で特定し測り直した（決定 S）**: M3b-4.8 の内訳で名前の付かなかった約 7.5 MB は、命令ごとの描画 ID の文字列（9.8 万個・計 120 万字）と語の文字列（`[B` 5.04＋`String` 4.30 MB。語は `SceneRun` と命令で共有）だった。M3b-4.10 の後のシーン 15.80 MB の中身は、詰めた語 `[I` 6.56 MB（169 万語。断片 7 語×8.5 万＋命令 平均 10.2 語）・語の文字列 2.9 MB・`SceneLayer` 1.34・箱ごとの `RenderMeta` 1.06（1.7 万）＋`RenderHints` 0.94 MB（1.2 万）・参照の表 0.78 MB。`large-list-2000` は 5.67 MB のうち `[I` 1.25・`SceneLayer` 0.76・`RenderMeta`＋`RenderHints` 1.22 MB。**見込み**: M3b-4.24b で列（18.35 / 4.04 MB）と節の錨の命令（約 0.4 / 0.2 MB）、M3b-4.30 で ID の語（約 1.2 / 0.24 MB）が消えて約 28.3 / 9.7 MB。28.0 / 9.0 MB に届かせる残りは M3b-4.11 の箱ごとのメタ・付帯情報（2.00 / 1.22 MB）と `SceneLayer` の欄（1.34 / 0.76 MB）。
**M3b-4.11 で測り直した**: メタを層の列の語の表に（メタの表の欄 1.7 万 / 1.0 万。付帯情報のオブジェクトは 1.9 万 → 4）、持ち主の語と鍵の上位を層に 1 つにして、シーンは 13.77 / 4.72 MB（詰めた語 `[I` 6.56 / 1.53 MB・`SceneLayer` 1.29 / 0.73 MB）。表示項目の列は組み立てるたびにメタを戻すので 18.92 / 4.49 MB に増えたが、全体は 46.94 / 13.80 MB に減った。**見込み**: M3b-4.24b で列と節の錨の命令、M3b-4.30 で ID の語が消えて約 26.2 / 8.7 MB（28.0 / 9.0 MB に届く）。
**M3b-4.15 の後**: ビューは列を持たず、ゲートのビューの保持は 27.49 / 8.80 MB（表示項目の列は描画器が組んで持つので、描くホストの全体は M3b-4.11 と同じ範囲）。段ごとの内訳は M3b-4.16 の後に測り直す。
`fontsize` の箱の木（全要素の再スタイル 26 ms）は新経路の欠陥ではない: 旧経路も 1 スレッドで毎回違う font-size に変えると再スタイル 26.0 ms・測定 27.7 ms になる（ゲートの旧経路 21.8 ms は 16px / 15px の交互で旧経路の記憶が当たる値。旧経路の再スタイルを 4 スレッドで並列に解くのは P2 の範囲）。

---

## 5. 運用ルール（構造の崩壊とパッチ地獄に戻らないための 8 原則）

1. **優先順位表は、リファクタリング（本流 M0〜M6）は本書の §3、新しい機能（Track P）は [`ENGINE_FEATURE_PLAN.md`](ENGINE_FEATURE_PLAN.md) の §2 の 1 つずつだけとする。** 新しい課題はどちらかの表に統合し、成果と数値は各書の §0 と §4 を上書きする（Track P のコールド起動・リポジトリの構成の KPI も同書へ移した）。
2. **壊れた中間表現や可変オブジェクトの上に、対症療法のキャッシュを足さない。** 速度・メモリの問題が出たら、まずデータ構造（入力と出力の分離・参照の共有・階層の表現）を疑い、破壊的変更で根を絶つ。
3. **変更検知の入口は `ChangeLedger` だけ。下流の記憶は §2.3 の表の鍵だけとする。**
   - 禁止するもの: DOM への `DIRTY_*` の欄・多欄の後追い照合（`PaintStamps` 型）・読み取り台帳（`LayoutReads` 型）・純粋性の述語（`F_*`・子から親へ伝える「使い回せるか」）・出力のその場の書き換え・退避と復元（`TentativeSlots`）・使い回さない部品の列挙。
   - やむを得ない一時の例外は、§3 の「凍結した例外」の表に場所と消す段を書いてから置く。表の外に足さない。
4. **プロセスグローバルな可変状態を禁止する。** 新しい `Shortcut`・`static volatile` のフラグ・プロセス共通のエポック・業務ロジックの `ThreadLocal` を作らない。状態は全て 1 ビュー（`EngineView`）に閉じる。
5. **クラスの規模をゲートで強制する。** 新規・刷新するクラスは `≤ 600 行` とし、M5 の完了で `1,000 行超 = 0` を `ArchitectureHealthTest` で強制する。600 行超の既存のクラスは理由つきの一覧で持ち、M6 で一覧を固定して増やさない（「全クラス ≤ 600 行」は 2026-11-28 に取り下げた。§1.6 決定 W）。
   - 行数は役割の型（委譲・部品）で満たす。1 つの子しか継がない上位クラスを挟んで行数を合わせない（2026-11-28 の点検。§1.4 決定 L）。
6. **検証は外から見た正しさと実際の費用で行う。**〔2026-11-26 改訂〕
   - **正しさ**: 仕様（WPT）・自分自身の全再計算との一致（`FrameOracle`・`IncrementalOracleTest`）・CPU / GL / Vulkan の 3 者画素一致・人が違いを確かめてから取り直したゴールデンで固定する。
   - **旧経路との比較**: 移行の間の道具にとどめる。新しいコードに旧経路の構造（項目の ID・層の分け方）まで一致させる番人は作らない。
   - **性能**: 絶対の上限と、同じ経路の中の比（増分 / 全再計算）で判定する。旧経路との比は記録だけにする。時間の上限は、同じ実行の中で測る基準の仕事との比で持つ（M3b-5 から。§1.6 決定 T）。
   - **目標の値**: 本書の内訳（§4.1）から導ける値だけを掲げる。導けない値は「スパイクで決める」と書き、数字を置かない（§1.6 決定 R）。
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
   - **段の番号は枝番を重ねない**（枝番に枝番を重ねた番号を作らない。2026-11-28）。Phase の中の小さな段は通し番号（`M3b-4.N`）で並べる。段を増やす・分けるときは、未着手の段の番号を振り直して表を整理する（分けるのは未着手の段の `4.Na`・`4.Nb` の 1 段まで。完了した段の番号は変えない）。旧番号との対応表は残さない（§3.4）。

---

## 6. アーカイブおよび他文書との関係

- 旧計画（C 系・K 系・K5b・W0〜W3。2026-11-24 までの設計の記録と知見 `§6-1〜205`）の全文は [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) に退避してある。`RegressionCatalogTest` が参照する `§6-N` は、同じアーカイブの付録 3 に全て保全されている。
- **2026-11-26 の軌道修正の前の本文**（M1 の段の記録・M2a の設計とスパイクの実測・M2b-0〜M2b-5 と M2c ①〜④-3 前半の作業記録・P1 の計測の詳細）は、原文のまま `archive/07_plan_m0_to_m2c_before_course_correction.md` へ移した（ARCHIVE の索引から辿る）。コードの注釈にある「計画書 §3.3 C」「§3.4.1」「§3.4.2-N」は、その文書の同じ番号の節を指す。
- `core/DESIGN_INCREMENTAL_KERNEL.md` は廃止し、全文と持ち越す知見を ARCHIVE の付録 5 へ移した。以後の知見は付録 5.A に積む。
- `docs/ARCHITECTURE.md`・`docs/BENCHMARK.md` は本計画書（§0・§2・§3・§5）に従い、各フェーズの完了に合わせて本計画書の内容へ順に同期する。MOD 時代の旧計画 `REFACTORING_PLAN.md` は閉じ、`archive/00_refactoring_plan_mod_era.md` に原文のまま置いた。
- 新しい機能・ホスト・配布（Track P1〜P8）は [`ENGINE_FEATURE_PLAN.md`](ENGINE_FEATURE_PLAN.md) に分けた（2026-11-28）。本書の §5 の運用ルールは同書にもそのまま適用する。
- 文書の置き場（P4-1）: エンジンの文書は `docs/`（計画書・ARCHIVE は `docs/plan/`、過去の原文は `docs/plan/archive/`）、MOD の文書は `mod/docs/`。目次は `docs/README.md` と `mod/README.md`。