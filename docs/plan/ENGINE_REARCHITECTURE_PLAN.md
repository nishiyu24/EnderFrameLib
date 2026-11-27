# EnderFrameLib 根本再設計プラン（Engine v3: 破壊的アーキテクチャ刷新）

> **更新日**: 2026-11-28（M3b を 5 段に分け、**M3b-1**〔描画は DOM を読まない〕・**M3b-2**〔`DisplayScene` と `SceneEmitter` を導入し、描画用の木・`TreePainter`・`Fragment`/`FragSeq`・凍結例外 4 つを削除〕・**M3b-3**〔当たり判定・CSSOM・フォーカス・インスペクタを scene から答え、`HitTestIndex`・`PaintBox`・`SceneBox` を削除〕・**M3b-4a**〔描画の項目の生成器を層の描画命令 `DrawOp` に変え、`SceneEmitter` が層とともに記憶する〕・**M3b-4b-1〜2**〔文字列の鍵の範囲・補間の状態を節ごとの表に・走査器 `SceneWalker` を影で動かし `PaintSceneDriver` と一致〕・**M3b-4b-3a**〔走査器はスクロール量を項目に焼き込まない。大きさ 0 の要素の子孫が祖先の変形・不透明度・スクロールから外れる欠陥を直した〕・**M3b-4b-3b**〔描き直す範囲を前後のシーンの違いと動的な値から求める `SceneDamage`・保持層を中身の節に結ぶ〕・**M3b-4b-4a**〔走査器を全バックエンドの描画の経路にし、`PaintSceneDriver`・`DamagePlanner`・項目の再生を削除〕・**M3b-4b-4b**〔面の静止の判定を描画器の `needsRepaint`〔シーンの違いと走査器の動く項目〕へ移し、`StaticFrameGate`・`ItemDiff`・`AbsoluteItems` を削除〕を完了した〔§3.4〕。同日、**M3b-4c-0**〔点検の直しと測り直し。M3b のヒープの目標を置き直した〔§1.4 決定 J〕〕を完了した。同日、M3b-4b の後の構造点検〔§1.4〕で残る歪みを段へ振り分け、M3b-4c を 4 段〔4c-0〜4c-3〕に分け直し、`fontsize` の 4 スレッドの目標を P2 へ移した。同日 M3b と並行して DOM の側だけの **M5-1**〔番号の表の二重同期の解消・`SelectorIndex`・`HtmlNode` の分割〕・**M5-2**〔`HtmlParser`・`Bindings`・`HtmlElement`・`InteractionState` の分割〕・**M5-3a**〔分けた DOM のクラスを各 500 行以下に〕を完了した〔§3.7〕。2026-11-26 の軌道修正以降は §1.3・§3.2・§3.3、独立トラック **P4-1** は §3.9）
> **対象**: `:core`（609 ファイル・約 10.9 万行。`com.nishiyu.enderframelib.core.*` / `common.*` / `api.*`）と組み込みサンプル MOD（`:mod`。`mod/src/main/java/...`）の境界・描画・ホスト層を含む全体構造。
> **旧計画の全面撤回宣言（2026-11-24）**: 従来の `C 系`・`K 系`・`K5b`・`L5 (W0〜W3)`・`K4h`・`B14` と `core/DESIGN_INCREMENTAL_KERNEL.md` の「既存キャッシュ温存＋増分カーネル延命」方針は撤回・凍結した（§1.2）。本計画は**破壊的変更を全面的に許可**し、データ表現・中間表現・描画契約・ホスト統合の土台を作り直す。
> **この文書の書き方（運用ルール 8）**: 本書は**状態表であり作業ログではない**。段ごとの経緯・途中で分かった点・変異検証の記録はコミットの本文と [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) に書く。2026-11-26 以前の本文（M1 の段の記録・M2a の設計とスパイクの実測・M2b〜M2c の作業記録）は、原文のまま ARCHIVE の索引から辿れる `archive/07` へ移した。

---

## 0. 現在地（2026-11-28。作業のたびに上書きする）

| 項目 | 状態 |
| :--- | :--- |
| 完了 | M0・M1・M2a・M2b（WPT 170/260）・M2c（経路を 1 本化、§3.2）・**M3a**（MOD 旧レンダラ撤去、§3.3）・**M3b-1**（描画は DOM を読まない）・**M3b-2**（`DisplayScene` + `SceneEmitter` を導入し、描画用の木・`TreePainter`・`Fragment`/`FragSeq`・凍結例外 4 つ・`SCROLL` 祖先照合を削除）・**M3b-3**（当たり判定・CSSOM・フォーカス・インスペクタ・MOD の入力を `SceneHitTest`/`SceneQuery` で scene から直接答え、`HitTestIndex`・`UIHitResult`・`SceneBox`・`PaintBox`・`PaintRun` を削除。§3.4）・**M3b-4a**（項目の生成器を層の局所座標の描画命令 `DrawOp.Item` を作る形にし、`SceneEmitter` が層を作るときだけ作って層に記憶。一時アダプタは命令に位置・クリップ・親の錨を足して組み立てるだけ〔`OpComposer`〕。§3.4）・**M3b-4b-1**（文字列から作る鍵と節の鍵の値の範囲を分けた）・**M3b-4b-2**（補間の状態を節ごとの表 `AnimationStates` にし、後片付けを実時間〔1 秒ごと〕でなく列の構造の変化の印にした。走査器 `SceneWalker` を影で動かし、実文書 9 本の全フレームで `PaintSceneDriver` と描画側への呼び出しが一致。§3.4）・**M3b-4b-3a**（走査器は中身を 1 つの節にまとめるスクロールコンテナをスクロール 0 で組み、スクロール量を中身の節・つまみ・sticky の平行移動として `DynamicSceneState` から足す。ビューの列に依る差の平行移動を受けない。同時に、大きさ 0 の要素〔錨を出さない〕の子孫が祖先の変形・不透明度・スクロールから外れていた欠陥を直した。§3.4）・**M3b-4b-3b**（描き直す範囲を、前後のシーンを使い回しの同一性で対にした違いとスクロール量から `SceneDamage` が、補間・外の事情で変わる項目から走査器が求める。実文書 9 本の全フレームで前後の画素の違いが全て範囲に入る。保持層の同一性の印を表示項目の列からスクロールコンテナの層の子の並びにした〔`ScrollContentRanges`〕。§3.4）・**M3b-4b-4a**（走査器 `SceneWalker` を CPU・GPU・Vulkan・MOD の面の描画の経路にした。入力はビューの表示項目の列〔スクロールコンテナをスクロール 0 で組む〕と、列を組んだシーン `SceneSource`。部分の描き直しは `SceneDamage`＋走査器の動く項目・重ね描きの前後で求め、`PaintSceneDriver`・`RetainRanges`・`RangeBounds`・`DamagePlanner`・項目の再生〔`ReplayKeys`・GPU の span〕・CPU のスクロールの画素ずらし・`Shortcut` 4 つ・`EngineFlags` 5 つを削除。`:core` −2,259 行。§3.4）・**M3b-4b-4b**（面を描かないフレームの判定を、項目の同一性の後追い照合〔`StaticFrameGate`〕から描画器の `RenderBackend#needsRepaint` へ移した: 描き直す範囲〔シーンの違い・塗りの色・利用者の平行移動〔`FrameDamage`〕と、走査器のシーンの外の変化〔補間・外の事情・重ね描き〕〕が空かを描く前に答え、GPU〔MOD の面〕は前の画素を使い回す。MOD は描くフレームだけ Minecraft の中身を用意し、書体の登録の変化で `invalidate`。部品の値は DOM の写しでシーンに入るので毎フレーム描き直す種類から外した。`StaticFrameGate`・`ItemDiff`・`AbsoluteItems`・ビューの `elements()` を削除。§3.4）・**M3b-4c-0**（点検の直しと測り直し: 差分描画とスクロール層の保持の切り替えを描画器ごとの設定にして `Shortcut` 2 つ・`EngineFlags` 1 つを削除、新経路の機械検査の一覧に M3b-3〜4b・M5 のクラスを入れた。`heapBreakdown` を直して内訳を測り、M3b のヒープの目標を 28.0 / 9.0 MB に置き直した〔決定 J〕。§4 を同じ条件で測り直した）・**M5-1**（DOM の番号の表〔旧 `DocumentArena`〕から木の形・タグ・Bloom の写しを除いて `NodeIds` にし、セレクタの足切りを `SelectorIndex`、前の兄弟を子の並びの位置にした。`HtmlNode` 1,398 → 739 行。§3.7）・**M5-2**（`HtmlParser` 1,214 → 165・`Bindings` 1,175 → 584・`HtmlElement` 980 → 634・`InteractionState` 1,039 → 602 行。`core.dom` に 1,000 行超のクラスは無い。§3.7）・**M5-3a**（分けたクラスを各 500 行以下に: `HtmlNode` 739 → 447〔節の欄と手入れを上位の `NodeState` へ〕・`HtmlElement` 634 → 480〔CSSOM View を上位の `ElementCssomView` へ〕・`InteractionState` 602 → 425〔節の鎖と Top Layer を `InteractionChains` へ〕・`Bindings` 584 → 486〔宣言的バインディングの走査を `DeclarativeBindings` へ〕・`TreeBuilder` 564 → 358〔本文の挿入モードを `BodyMode` へ〕。§3.7）。独立トラック P1a・**P4-1**（§3.9） |
| 進行中 | **M3b**（5 段。M3b-1〜M3b-3・M3b-4a・M3b-4b・M3b-4c-0 完了。§3.4 の段の表）・**M5**（DOM の側だけ M3b と並行。M5-1・M5-2・M5-3a 完了。§3.7 の段の表） |
| 次の一手 | **M3b-4c-1**（型付きの命令: `DrawOp` を背景・枠線・影・文字のグリフ列・画像・部品の小さい命令に分け、文字列 ID・`events`・`RenderMeta`・節の錨だけの命令を持たせない。生成器・`LayerOps`・`SceneDamage` が `SceneLayer` を直接読み、`SceneLayer` の欄と `SceneRun` の写しを減らし `ops` を final に。§3.4）→ **4c-2**（走査器がシーンを直接たどる・GPU と CPU の呼び合いを解く・表示項目の列と一時アダプタを削除）→ **4c-3**（`DisplayItem` の削除と緩和の戻し）。残る穴は G3 multicol（M3c）。M5 の DOM の側は M5-3b（完了ゲート。M3b・M4 の後）を残すのみ（§3.7） |
| 止まっている条件 | なし |
| 期限付きの緩和 | ヒープの上限（`varied-list-2000` 68.5 MB・`large-list-2000` 19.3 MB）と latency の 2 行（`varied-list-2000:fontsize` 157.7 ms・`large-list-8000:child` 57.9 ms）は M3b-4（`DisplayItem` 廃止）まで新経路の実測上限（M3b 完了時に 28.0 / 9.0 MB〔4c-0 で置き直した。§1.4 決定 J〕、46.6 / 16.5 ms へ戻す。§4.1）。`P8bRetentionTest` の天井は 30 MB。**M3b-4a の二重持ち**（層の描画命令と、それを毎フレーム組み立てた表示項目）の分として、ヒープの上限を `varied-list-2000` 74.0 MB・`large-list-2000` 20.5 MB、`P8AllocationReductionTest`（文字の大きさで全部を作り直す割り当て）を 52 KB/行に一時的に上げた（表示項目の列を消す M3b-4c で 68.5 / 19.3 MB・48 KB/行へ戻す。4b-4a は列を残したので保持ヒープは変わらない） |
| 二重に持っているもの | 表示項目の列: ビューが一時アダプタ `SceneDisplayListAdapter`（層の描画命令を表示項目に組み立てる `OpComposer` を含む）で組み、走査器が毎フレームたどる（M3b-4b-4a から描画の経路は走査器 1 本。旧 `PaintSceneDriver`・`DamagePlanner`・`RetainRanges` は削除済み）。列とそれが読むパッケージ内の `SceneAdapterBox` は `DisplayItem` とともに M3b-4c で削除。面の静止判定は M3b-4b-4b で描画器の `needsRepaint`（シーンの違いと走査器の動く項目）へ移した（`StaticFrameGate`・`ItemDiff` は削除済み）。平坦な絶対座標の列は本番から消え、AWT で描くテストの道具だけがテストの補助 `TestFlatItems` で作る（4c で消す）。問い合わせの旧経路は M3b-3 で削除済み。他の一時アダプタ・旧経路は 0 |
| 凍結中の例外 | **なし**（M2c で凍結した `TreePainter` の例外 4 つは全て M3b-2 で削除済み） |
| 点検で見つけた歪み | §1.4 の表（2026-11-28。M3b-4b の後）。12 項目を M3b-4c-0〜4c-3・M3b-5・M5-3b に振り分けた（7・11・12 は 4c-0 で済）。表の外に足すときは §1.4 に行を足してから |
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

### 1.4 M3b-4b の後の構造点検（2026-11-28）

M3b-4b-4b の時点のコードを 2 つの独立した点検（Claude・Gemini）で読み、結果を突き合わせて実コードで確かめた。L2・L3（`BoxTreeCompiler`・`LayoutCache`・`LayoutFragment`）・M3b-3 の問い合わせ・凍結例外 0・旧型の再登場を止める `ArchitectureHealthTest`・画素で確かめる番人は健全である。**性能の目標はまだ回収していない**（§4: ヒープ 68.2 / 16.2 MB・8,000 行の 1 件挿入 19.1 ms。どれも M0 前より重い）。M3b-4b は経路を入れ替える段で、性能は横ばいか少し悪い（costGate +3〜7%）。回収は M3b-4c にかかる。歪みのほとんどは「`DisplayItem` の列を残したまま段階的に移した仮の足場」に集まっている。

| # | 歪み（場所） | 消す段 |
| :---: | :--- | :---: |
| 1 | **走査器が平らな列から階層を毎フレーム戻す**: `SceneWalker#index` が全項目の文字列 ID を `AtomTable.shared().intern`（プロセス共有の表）で番号にし、親を鍵の表で引き直す。`ScrollContentRanges` は親を最大 128 段たどる。§1.1-3 の「潰した階層の逆算」と同じ形 | 4c-2 |
| 2 | **`DrawOp.Item` が `DisplayItem` とほぼ同じ 24 欄**（`String id`・`baseNodeId`・`Map events`・`RenderMeta`・`AnimationSpec`）。§2.1-3 に反し、シーンのヒープが 12.13 → 35.29 MB（`varied-list-2000`） | 4c-1 |
| 3 | **`SceneLayer` が参照と写しを両方持つ約 48 欄**（`box`・`frag`・`node`・`style`・`firstLine` と、辺 12 個・`runs` などの写し）。`ops` は後付け（`attachOps`。final でない）。層ごとの `new ComputedStyle` は薄い包みで寄与は小さい | 4c-1 |
| 4 | **残す部品が一時アダプタに乗る**: `SceneAdapterBox` を 16 ファイルが参照し、シーンを作る `LayerOps`・`SceneDamage`・生成器（`BoxItemPainter` ほか）を含む。`SceneDamage#children` はチャンクを `ArrayList` に広げ、子の照合ごとに `IdentityHashMap` を 3 つ作る | 4c-1 |
| 5 | **GPU と CPU の呼び合い**（§1.1-4）: `GpuRenderBackend` が `cpu.renderOnto(…, this)` で自分を `GpuSink` として渡し、4b-4b で `needsRepaint` も CPU に任せた。§3.4 の削除対象なのに段が決まっていなかった | 4c-2 |
| 6 | **移行のためだけの接着**: `RenderBackend#render` の 4 つの重ね定義＋`needsRepaint`、`FrameDamage` の plan → take（同じ入力の実体かで 2 つの呼び出しをつなぐ）、`SceneSource#baseSizeIn`（列の先頭と末尾の `==` でシーンの部分を推定）、再生を消した後の `GpuReplaySink` | 4c-2 |
| 7 | **機械検査の穴**: `NEW_PATH_SOURCES` に 4b で足したクラス（`SceneWalker`・`SceneDamage`・`FrameDamage`・`LayerOps` ほか）が入らず、`SceneWalker.java:309`（`RETAIN_SCROLL_LAYERS`）・`FrameDamage.java:67`（`SOFT_DAMAGE`）が `Shortcut` を直接読む | 4c-0（**済**。2 つの切り替えを描画器ごとの設定にして `Shortcut` から削除し、M3b-3〜4b・M5 で足したクラスを一覧へ入れた） |
| 8 | **変化の検出が入口と出口の 2 か所**: `ChangeLedger` に加え、`SceneDamage` が作り直した層の命令を全欄で深く比べる（`Arrays.equals(ops)`）。出力の差分で記憶ではないが、費用は命令の大きさに比例し、多欄の照合に近い | 4c-1 |
| 9 | **描画が DOM の節を読む**: 生成器が `box.node().tag`・`key()`（文字列）・`paintHandle()` を読む。`SceneEmitter#nodeOf` は生成した箱に `new HtmlNode` を作って `setId` し、匿名の箱を文字列の ID で照合する。使い回しの条件は §2.3 の鍵より 5 つ多い（親の文脈という構造の入力。これ以上増やさない） | 4c-1・M3b-5 |
| 10 | **行数を合わせる継承の鎖**（M5-3a）: `NodeState` ← `HtmlNode` ← `ElementCssomView` ← `HtmlElement`。上位が `self()` で下へキャストし、コンストラクタで `self()` を渡す。実害は小さいが、§5 ルール 5 の分け方に合わない | M5-3b |
| 11 | **目標と KPI**: ヒープ 14.0 / 5.5 MB に対し、シーン以外の段だけで 13.46 / 4.42 MB（§4.1）。`fontsize` の 4 スレッド ≤ 5.0 ms は並列の再スタイル（P2）が前提。§4 の「現在」は M3b-4a のままで測った条件が混ざる。増分のコードの KPI に `SceneDamage` ほかが入っていない | 4c-0（**済**。測り直すと、シーン以外の段は 14.02 / 5.28 MB で目標を超える。M3b のヒープの目標を決定 J で置き直した〔§3.4 の完了ゲート・§4.1〕） |
| 12 | **小さな残り物**: 空のディレクトリ `core/paint/tree`、`V3Frame.java:248` の `indexOf`（O(n)。CSSOM のときだけ） | 4c-0（**済**） |

**決定（2026-11-28。§0・§3.4・§4・§5 に反映済み）**

| # | 決定 | 上の番号 |
| :---: | :--- | :---: |
| I | **M3b-4c を 4 段に分ける**（4c-0 点検の直しと測り直し／4c-1 型付きの命令と `SceneLayer` を直接読む生成器・`SceneDamage`／4c-2 走査器がシーンを直接たどり、GPU と CPU の呼び合いを解いて列と一時アダプタを削除／4c-3 `DisplayItem` の削除と緩和の戻し）。4c は後片付けではなく M3b で最大の段なので、二重持ちを長引かせないよう段ごとに置き換えと削除を閉じる | 1〜9 |
| J | **ヒープの目標は測ってから決める**: 4c-0 で「共有・GC 差」の中身と命令を型付きにした見込みを測る。届かないなら、`SceneLayer` の欄を減らす仕事を 4c-1 に入れるか、目標を根拠つきで置き直す（どちらも本書を先に書き換える）。**4c-0 で置き直した**: シーン以外の段だけで 14.02 / 5.28 MB あり（§4.1）、シーンを 0 にしても 14.0 / 5.5 MB に届かない。M3b の目標は型付きの命令にしたシーンの見込み（約 13 / 3.4 MB）を足した `≤ 28.0 / ≤ 9.0 MB`。元の値は DOM・箱の木を減らす M5 で見直す | 3・11 |
| K | **`fontsize` の 4 スレッド ≤ 5.0 ms は P2 の目標にする**。M3b のゲートは 1 スレッドの latency の上限（≤ 46.6 ms）で判定する | 11 |
| L | **行数のゲートは役割の型で満たす**: 1 つの子しか継がない上位クラスで行数を合わせない（§5 ルール 5）。M5-3a の 2 つは M5-3b で委譲へ直す | 10 |

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
| L4a | `DisplayScene`（`SceneLayer` / `SceneChunk`。層は自分の描画命令 `DrawOp` を持つ） | `(LayoutFragment ==, 最新の BoxNode ==)`・`FlowChunk ==`（描画命令は層の値の一部で、層を作るときだけ作る。別の鍵を持たない） | **実装済み（M3b-2。描画命令は M3b-4a）** |

**期限付きの例外**（この表の外にあるもの。消す段を決めてある）: `PaintNode`/`PaintView`/`TreePainter` の記憶と `Fragment`/`FragSeq` は **M3b-2 で削除済み**。問い合わせの口だった `SceneBox`/`PaintBox` は **M3b-3 で削除済み**（当たり判定・CSSOM は `SceneHitTest`/`SceneQuery` が scene を直接たどる。記憶は持たず、1 回の問い合わせの作業用の値 `HitScratch` をビューの文脈が使い回すだけ）。残る一時アダプタは、記憶を持たない `SceneDisplayListAdapter`（`SceneAdapterSupport`・`SceneAdapterBox`・命令を組み立てる `OpComposer` を含み、M3b-4c で `DisplayItem` とともに削除）の 1 つだけ。その出力の表示項目の列はビューが持ち（シーンが前回と同じ実体の間は組み直さない。スクロールコンテナはスクロール 0 で組むので、スクロールだけでは組み直さない）、走査器（M3b-4b-4a から全バックエンドの描画の経路）はフレームをまたいで姿勢・索引を持たない。描画命令は層の位置・祖先のクリップ・親の錨・祖先の文字色のアニメーションを持たず、それらは組み立てが毎フレーム足す（命令の座標は層自身か原点の節からの float）。
**記憶ではない引き継ぎ**: 全体の作り直しの要求（`RELAYOUT`）は前回の木と記憶を捨てるが、作り直す前の要素の算出スタイルだけを、そのフレームのトランジションの始まりの入力として `StyleSource.history` に渡す（照合の鍵ではなく、フレームの後で捨てる）。アンカーの**覚えたスクロール量**（G8。§3.2）もビューの履歴で、アンカーの再計算点でだけ書き換え、台帳の `ANCHOR` で `BoxNode` へ入る（鍵は `BoxNode ==` のまま。`RELAYOUT` をまたいで渡し、文書の入れ替えで捨てる）。描き直す範囲を求める `SceneDamage`（M3b-4b-3b）が覚える前のフレームのシーンとスクロール量も比べる相手で、使い回す出力は無い（記憶の鍵ではない）。描く前の判定（`needsRepaint`。M3b-4b-4b）で求めた範囲を同じ入力の描画へ渡す `FrameDamage` の計画も、そのフレームの中で 1 度使って捨てる。

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
| 7 | **M3b（改訂）** | **`DisplayScene` と描画の作り直し** | 平坦な `DisplayItem`・旧描画の層・描画用の木・`TreePainter` の記憶・`PaintSceneDriver`・`HitTestIndex`・`Shortcut` を全廃 | §3.4 の完了ゲート（中間表現 3 段・ヒープ ≤ 28.0 / 9.0 MB〔M3b-4c-0 で置き直した。§1.4 決定 J〕・`Shortcut` 0） | **進行中**（M3b-1〜M3b-3・M3b-4a・M3b-4b・M3b-4c-0 完了。次は M3b-4c-1。§3.4） |
| 8 | **M3c** | multicol（ブロックの断片化） | `LayoutFragment` の断片化と、列ごとの断片の scene | WPT の css-multicol を足し、新経路のラチェットで固定 | 未着手 |
| 9 | **M4** | L5 ホスト層の純化と MOD の循環の解消 | `UiView`/`StandaloneView` → `EngineView` + `InputController` + `StandaloneHost` | L5 ≤ 1,200 行・MOD の循環 0 | 未着手 |
| 10 | **M5** | DOM の整理と 1,000 行超のクラス 0 | `DocumentArena` の二重同期の解消・`HtmlNode` の分割・`SelectorIndex` の独立 | 1,000 行超 0・ヒープ ≤ 12.0 / 4.5 MB | **進行中**（M5-1・M5-2 完了。DOM の側は M3b と並行。§3.7） |
| 11 | **M6** | 品質ゲートの最終固定と配布（v2.0.0 GA） | 全クラス ≤ 600 行のハードゲート・Linux CI・API 署名の確定・Maven Central | 全クラス ≤ 600 行・グローバルな可変の static 0 | 未着手 |

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

### 3.4 Phase M3b（改訂）: `DisplayScene` と描画の作り直し〔進行中。M3b-1〜M3b-3・M3b-4a・M3b-4b・M3b-4c-0 完了〕

平坦な `DisplayItem` に潰してから `PaintSceneDriver` で階層を復元する往復をやめる。同時に、M2c で凍結した描画の例外を全て消す。

**段（2026-11-27 に分けた）**: `DisplayItem` の参照は `:core` 本体 59・テスト 94・MOD 50 ファイルあり、1 回では置き換えられない。入力側（DOM の写し）→ scene の生成 → 問い合わせ → バックエンドの順に、各段で置き換えた型を同じ段で消す。scene からバックエンドへの一時アダプタだけは M3b-4 まで残す（§2.3 の期限付きの例外に載せてから置く）。

| 段 | 内容 | 状態 |
| :--- | :--- | :--- |
| **M3b-1** | **描画は DOM を読まない（決定 F）**: 描画が読む DOM の値（属性・textarea の中身・状態の擬似クラスの規則が掛かるか）を `BoxTreeCompiler` が `BoxNode.values`（`DomValues`）に写す。値が前回と同じなら前回の参照、レイアウトの入力の照合には入れない（属性だけの変化はレイアウトの入力の箱を変えない）。`class`・`id`・`style` は写さない | **完了**（2026-11-27）。`core.paint` の DOM の読み取り 0（`ArchitectureHealthTest#paintDoesNotReadDomValues`）。`TreePainter` は台帳に載った節の祖先を数えず、読むのはスクロールしたコンテナの祖先だけ（凍結した例外を縮めた）。`PaintTreeBuilder` はシートを受け取らない。`PaintDomValuesTest`（属性・入力の値・`checked`・textarea の中身の変化がゼロから組んだ表示リストと一致・変わった枝だけを作り直す）は写しを前回のまま返す変異で落ちる |
| **M3b-2** | **`DisplayScene` と `SceneEmitter`**: `(断片, 最新の箱)` → 親相対・`int nodeId` の `SceneLayer` の木。記憶は `(断片 ==, 最新の箱 ==)`・`FlowChunk ==` だけ。描画用の木（`PaintTreeBuilder`・`PaintNode`・`PaintView`）・`TreePainter`・`TreeStacking`・`Fragment`/`FragSeq` を吸収して消し、凍結した例外を全て消す。スクロール・sticky・アンカーの追従は `DynamicSceneState` | **完了**（2026-11-27）。旧描画用の木・`TreePainter` 等 10 本を削除し、`core.paint.scene`・`SceneDisplayListAdapter` へ移行（`:core` −2,168 行・凍結例外 0・`legacyPaintTreeTypesStayGone`）。保持ヒープ `53.2→50.8 / 13.8→12.6 MB`、`DisplaySceneTest`・`SceneEmitterIncrementalTest` 追加・変異 2 種を確認 |
| **M3b-3** | **問い合わせ**: 当たり判定・CSSOM・フォーカス・インスペクタを断片木と scene から答える。`HitTestIndex`・`PaintBox` を消し、MOD の消費者（イベント・フォーカス・入力欄）を移す | **完了**（2026-11-27）。`SceneHitTest`（付録 E の逆順・チャンクの包含矩形で `O(log(N/32))`・外に出た sticky の回収）・`SceneQuery`/`SceneLocation`（位置・寸法・索引）・`SceneHit` へ移し、`HitTestIndex`・`UIHitResult`・`SceneBox`・`PaintBox`・`PaintRun` を削除（`legacyQueryTypesStayGone`）。`SceneHitTestTest`・`SceneQueryTest` を追加。ホイールの確保は作業用の値をビューの文脈で使い回して `costGate` の基準内（`voidwave` scroll 1,049 B）。`realdocs` は当たりの列だけ更新（float の幾何と旧 `Math.round` の差。項目・画素は一致） |
| **M3b-4a** | **描画命令**: 項目の生成器（`BoxItemPainter` ほか）を、層の局所座標の `DrawOp.Item` を作る形にし、`SceneEmitter` が層を作るときだけ作って層に記憶する（`LayerOps`）。一時アダプタは命令に位置・クリップ・親の錨・祖先の文字色を足して表示項目に組み立てるだけ（`OpComposer`） | **完了**（2026-11-27）。実文書 9 本の全フレームの全項目が以前とメタ・クリップの連鎖まで一致（違いはグラデーションの係数の最後の桁だけ。`surface_test` を開いたフレームの 10 画素が 1/255）。差分のフレームの割り当て −6〜40%・ホバーの増分 1.01 → 0.58 ms。二重持ちの分だけ保持ヒープ +17.5 MB（§0 の期限付きの緩和）。`LayerOpsTest` 追加・変異 2 種を確認 |
| **M3b-4b** | **走査器（全バックエンド）**: scene を描画順にたどる走査器が `PaintSceneDriver` を置き換え、CPU・GPU・Vulkan・MOD の面が走査器で描く。描画の状態は項目の位置でなく項目の整数の鍵と層の同一性で引く。小段 4b-1〜4b-4b（下の「M3b-4b の設計」） | **完了**（2026-11-28。4b-4b で面の静止の判定を描画器の `needsRepaint` へ移し、`StaticFrameGate`・`ItemDiff`・`AbsoluteItems` を削除。`RepaintSkipTest`・`GpuRepaintSkipTest` を追加し変異 8 種を確認。4b-4a で走査器を描画の経路にし、`PaintSceneDriver`・`DamagePlanner`・項目の再生を削除。`PartialRepaintRealDocumentsTest`〔実文書 9 本の全フレームで、差分で描いた画素が新しい描画器の全面と一致〕・`PartialRepaintOutsideSceneTest`・`GpuFrameSequencePixelTest` を追加し変異 7 種を確認。4b-3 まで: `SceneWalker`・`AnimationStates`・`SceneDamage`・`ScrollContentRanges` を追加し、`SceneWalkerShadowTest`〔実文書 9 本の全フレーム〕・`AnimationStatesTest`・`ZeroSizeAncestorTest`・`SceneDamageTest`〔実文書 9 本の全フレームの画素〕・`SceneWalkerRetainTest` で確かめた。変異 18 種を確認） |
| **M3b-4c-0** | **点検の直しと測り直し**（§1.4 の 7・11・12）: `NEW_PATH_SOURCES` に 4b の描画のクラスを入れ、`SceneWalker`・`FrameDamage` の `Shortcut` の読み取りを外す。`heapBreakdown` で「共有・GC 差」の中身と、命令を型付きにしたときの見込みを分けて測り、ヒープの目標を決める（決定 J）。§4 を M3b-4b の後の同じ条件で測り直し、増分のコードの KPI に `SceneDamage`・`FrameDamage`・`ScrollContentRanges` を数える。空のディレクトリ・`V3Frame` の `indexOf` を片付ける | **完了**（2026-11-28）。差分描画の切り替えは `SoftwareRenderBackend#partialRepaint`、スクロール層の保持は `GpuRenderBackend#retainScrollLayers`（描画器ごと。`Shortcut.SOFT_DAMAGE`・`RETAIN_SCROLL_LAYERS`・`EngineFlags.SOFT_DAMAGE` を削除。上限 10 / 18 に下げた）。`NEW_PATH_SOURCES` に描画の 10 クラス・`UiHitSupport`・M5 の DOM の 23 クラスを入れ、`flags()` の誤検出を直した。`heapBreakdown` の外し漏れを直し、段ごとのクラスと命令の調べ（`-Pheap.detail`）を足した。`PartialRepaintPixelTest#partialRepaintSwitchIsPerBackend` を追加。切り替えを無視する変異 2 種で落ちる |
| **M3b-4c-1** | **型付きの命令と、`SceneLayer` を直接読む生成器**（§1.4 の 2・3・4・8・9）: `DrawOp` を型付きの小さい命令（背景・枠線・影・文字のグリフ列・画像・部品）に分け、文字列 ID・`events`・`RenderMeta` を持たせない（鍵は節の番号と部品）。生成器・`LayerOps`・`SceneDamage` が `SceneLayer` を直接読み、`SceneAdapterBox` を使わない。`SceneDamage` は子をチャンクの `==` で飛ばし、命令の比較を軽くする。`SceneLayer` は参照か写しの片方に減らし、`ops` を final にする。ヒープの目標（28.0 / 9.0 MB）の見込みは、節の錨だけの命令（`NODE`。`varied-list-2000` で 6,907 個）を作らないことと、文字の命令と `SceneRun`（85,082 個・3.9 MB）の写しを片方にすることを含む（§4.1） | 未着手 |
| **M3b-4c-2** | **走査器がシーンを直接たどる**（§1.4 の 1・5・6）: チャンクのカリングで `DisplayScene` をたどり、`RenderBackend` の口をシーン・動的な値・重ね描き・時刻にする。GPU と CPU の呼び合い（`CpuFallback.renderOnto`・`GpuSink`）を解く。走査器の文字列の鍵の索引・`ScrollContentRanges`・`render` の重ね定義・`FrameDamage` の plan → take・`SceneSource#baseSizeIn`・`GpuReplaySink`・ビューの表示項目の列・`OpComposer`・`ImmutableItemList`・`SceneDisplayListAdapter`・`SceneAdapterBox` を削除 | 未着手 |
| **M3b-4c-3** | **`DisplayItem` の削除と緩和の戻し**: MOD（Minecraft が描く中身の引き当て〔項目 → テクスチャ〕を命令の鍵へ・ツールチップ・面のキャレット）とテスト（`TestFlatItems` ほか）から `DisplayItem`・`PaintCell`・`RenderHints`・`RenderMeta` を消す。M3b-4a の期限付きの緩和を戻す | 未着手 |
| **M3b-5** | **完了ゲート**: 下の完了ゲート（ヒープ・latency の上限を戻す・`Shortcut`/`EngineFlags` 0）と §4 | 未着手 |

- **M3b-4b の設計（2026-11-27）**
  - **調べた結び付き**: CPU・GPU（`cpu.renderOnto`）・Vulkan・MOD の面は全部 `SoftwareRenderBackend` の `PaintSceneDriver` を通る（CPU だけ先に替えられない）。表示項目の並びの位置に、親の錨の鎖・グループの開閉・保持層の範囲・画面外ブロックの間引き・止まった姿勢の使い回し・アニメーションの状態が結び付く。部分の描き直し（`DamagePlanner`）は項目の差分、MOD の `MinecraftHostTextures`・`MinecraftMediaBridge` は項目の同一性、重ね描き（キャレット・選択の一覧・インスペクタ）は列への追加、公開の口は `UiView#renderCache`・`StandaloneView#items`・`FrameRender.Frame#items`。
  - **方針**: 走査器（`SceneWalker`）が scene を付録 E の順にたどり、層ごとに命令を組み立てた項目を列に溜めずに描画側へ渡す。項目 1 つの塗りは 4b では `DisplayItem` のまま（型付きの命令は 4c）。描画の状態は項目の整数の鍵（節の番号と部品）か層の同一性で引く。部分の描き直しの範囲は、`SceneEmitter` が作り直した層と動的な値（スクロール・アニメーション）から求める（足切りの入口は台帳のまま。項目の後追い照合を足さない）。
  - **鍵の欠陥**: 文字列から作る鍵（`AtomTable` の番号。約 419 万まで）と節から作る鍵（`節の番号 << 16 | 部品`）は値の範囲が重なり得る（文字列の番号が 65,536 を超えると衝突する、以前からの潜在の欠陥）。**4b-1 で範囲を分けた**（`DisplayItem.STRING_KEY_BIT`。`DisplayItemKeyTest#stringKeysNeverCollideWithNodePartKeys`）。また、**項目の鍵は項目ごとに一意でない**: キャンバスの背景（`bg_root`）と body の背景が同じ鍵、背景のタイルが 16 枚を超えると部品番号が折り返す（実文書 6 本で確認）。アニメーションの状態をこの鍵で引くと別の項目が 1 つの状態を取り合う（試して `NoHtmlSiteTest`・`TextStrokeTest` ほか 4 件が落ちたので戻した）。CSS の遷移は要素ごとなので、状態は節（`nodeId`）ごとに持ち、部品はそこから値を引く形にする（4b-2 で走査器と合わせて作る）。
  - **小段**: **4b-1** 文字列の鍵と節の鍵の範囲を分ける〔完了〕／**4b-2** アニメーションの状態を節ごとにし（後片付けは列でなくフレームの印）、走査器を影で動かし、`PaintSceneDriver` と描画側への呼び出し（描く項目・順・グループの鎖・不透明度・姿勢）が実文書の全フレームで一致することを確かめる〔完了〕／**4b-3a** 走査器はスクロール量を項目に焼き込まない〔完了〕／**4b-3b** 部分の描き直しを作り直した層と動的な値から求め、保持層をスクロールコンテナの層に結ぶ〔完了〕／**4b-4a** 全バックエンドを走査器へ切り替え、`PaintSceneDriver`・`DamagePlanner` の項目差分・`RetainRanges`・`RangeBounds` を削除〔完了〕／**4b-4b** MOD の鍵（静止判定・Minecraft が描く中身）と重ね描きをシーンと節の鍵へ移し、`StaticFrameGate`・`AbsoluteItems`・`ItemDiff` を削除〔完了〕。（2026-11-28 に 4b-4 を分けた: 列を消して走査器がシーンを毎フレーム組み立てると、スクロール・補間のフレームで全項目を組み立て直し、costGate のスクロールの行〔1 フレーム約 1 KB〕が桁違いに増える。列の削除・`OpComposer`・`ImmutableItemList`・期限付きの緩和の戻しは、組み立てを要らなくする M3b-4c へ移した）
  - **4b-2 の結果（2026-11-27）**: 補間の状態は `AnimationStates`（節の番号 → 部品の ID ごとの状態。節を持たない項目は番号 0）。置き場の口 `CssAnimator.StateStore` はこの表を返し、MOD のセッションの `cssAnimations()` も同じ表。後片付けは `PaintSceneDriver` が索引を作り直すとき（列の構造が変わったとき）に列にある節だけを残す（以前は実時間で 1 秒に 1 回、項目の ID の集合と照合。消えた部品の状態は節が残る限り残す）。走査器 `SceneWalker` は一時アダプタの受け手（`ItemSink`）として項目を描画順に受け、フレームをまたいで列を持たず、層の範囲を一時アダプタの知らせ（`setLayer`）から取る。位置の番号は表示項目の列と同じなので `Painter` は変えていない。**分かったこと**: ① 層の範囲は実文書では親の鎖と重なる（範囲を無視する変異が実文書 9 本では落ちない）が、高さ 0 の要素（節の錨を出さない）の子は範囲だけが層に入れる（`filterLayerCoversItemsWhoseAnchorIsNotDrawn`）。② ビューが列を使い回すフレーム（スクロール・再スタイルだけ。ホバーも含む）は、列が作った時点のスクロールを焼き込み、平行移動はそこからの差で渡る。今のスクロールで組む走査器とは項目の位置が違い、画面の上の形と中身だけが一致する。4b-3 でスクロール量を焼き込まずスクロールコンテナの層の平行移動にする（そうしないと切り替えた後のスクロールが毎回全部を組み直す）。
  - **4b-3a の結果（2026-11-27）**: 実文書 9 本でスクロールするコンテナは全て「中身を 1 つの節にまとめる」形（`scrollsAsOneNode`。まとめられないのはスクロールしない html/body だけ）。走査器はそのコンテナを `DynamicSceneState#unscrolled` でスクロール 0 として組み（組んだ結果まとめられなかったコンテナは今のスクロール量で組み直す）、中身の節・つまみ・中の sticky の平行移動を今のスクロール量から自分で足す（`UiView` がスクロールだけの変化で列を動かす式と同じ）。走査器はビューの平行移動の表から利用者の移動だけを受ける。一時アダプタはスクロール量を `DynamicSceneState` からだけ読む。結果、スクロールしていない全フレームと、列をスクロール 0 で作ってからスクロールしたフレームは呼び出しまで一致し、列をスクロール中に作り直したフレームは画面の上の形と中身が一致する。**欠陥（直した）**: 大きさ 0 の要素は錨を出さないのに、子は親の錨としてその要素を指し、座標は絶対にしていた。子孫は祖先の変形・不透明度のグループ・スクロールの中身の節の移動から外れていた（スクロールだけの更新でその子孫だけが動かない）。錨を出さない箱を、匿名の箱と同じく親の錨と座標の原点で素通しにした（`ZeroSizeAncestorTest`）。大きさ 0 の要素**自身**の変形・不透明度・フィルターは今も子孫に掛からない（錨・層を作らないため。4b-4 で錨を大きさに依らず出すかを決める）。
  - **4b-3b の結果（2026-11-27）**: **描き直す範囲**は `SceneDamage`（`core.paint`）が前のフレームのシーン・スクロール量と今のものから求める。2 つのシーンを根から対にしてたどり、使い回した層（`==`）で同じ位置のものは下りない。作り直した層は、子孫の写し方（位置・変形・効果・切り抜き・重なり。大きさ・枠・余白は子孫を切る・写す箱でだけ）が同じなら自分の描画命令だけを比べて子へ下り（子は同一性 → レイアウトの箱 → 節で対にし、描く順が入れ替わった子・消えた子・増えた子は部分木）、違えば前後の部分木を足す。範囲は命令の矩形に影・文字の大きさの余白、祖先の変形（回転は外接円）とフィルターの広がり。スクロールしたコンテナは枠を丸ごと足して下りない（そこまでは使い回した層でも下りる）。全面は初回・ビューポート・文書のスクロール・アンカーの追従・`backdrop-filter`・キャンバスの背景・`::backdrop`。補間と外の事情で変わる項目（画像・入力欄など）は走査器が前後の時刻の動きから足し（`SceneWalker#addDynamicDamage`）、姿勢を動かす補間は全面。**保持層**は走査器が中身を 1 つの節にまとめたコンテナの子孫の範囲を自分の項目から求め（`ScrollContentRanges`。子孫が描画順で連続しない＝重なりの文脈を作らないコンテナは外す）、中身の印にコンテナの層の子の並び（`SceneLayer#children`）を渡す（`Painter#planRetained` に印を足した。`PaintSceneDriver` は列を渡す）。印はスクロールだけのフレームで変わらず、中身が変わると変わる。**確かめ**: `SceneDamageTest` は実文書 9 本の全フレームで前後の全面描画の画素の違いが範囲に入ること（部分のフレームの範囲は showcase 19%・hud 2%・machine_ui 9%。全面は voidwave〔`backdrop-filter`〕・surface_test・animation_test〔姿勢の補間〕）、小さな文書で 1 行の色・消えた箱・行より大きい字・重なりの順の入れ替え・回転・内側のスクロールを覆うこと。変異 10 種（順・消えた子・変形・スクロールの道・自分の命令・字の余白・動く項目・前の時刻・印・連続）が全て落ちる。
  - **4b-4a の結果（2026-11-28）**: **描画の経路は走査器 1 本**: ビューは表示項目の列を中身を 1 つの節にまとめるスクロールコンテナをスクロール 0 で組み（`SceneDisplayListAdapter#emitUnscrolled`。4b-3a の走査器の組み方をビューへ移した）、スクロール量は平行移動の表で中身の節へ足す（以前の移動の範囲の上限は要らなくなった）。描画器は `render(列, 平行移動, SceneSource, 時刻)` を受け、走査器 `SceneWalker` が毎フレーム列の索引・親・姿勢を作ってたどる（前のフレームの姿勢・索引の差分・画面外のブロックの覚えを持たない。列が別の実体になったら補間の状態を列にある節だけ残す）。**部分の描き直し**は `SceneDamage`（シーンの違い）＋走査器（補間・外の事情で変わる項目と、シーンの外から足した重ね描きの前後の範囲）で描く前に決め、描画器はそれをデバイス画素の 64×64 タイルに広げて消し、塗りを外接矩形に収める（見積もりの外れの全面やり直しは無くなった。初回・塗りの色・利用者の平行移動の変化は全面）。GPU は同じ走査器で描き、保持層の判定は中身の印（4b-3b）。**削除**: `PaintSceneDriver`・`RetainRanges`・`RangeBounds`・`DamagePlanner`・`ReplayKeys`・GPU の項目の再生（`RectStream` の span と検証の型）・CPU のスクロールの画素ずらし・`Shortcut`（`GL_REPLAY`・`REUSE_IDLE_POSES`・`CULL_RECORDED_RANGES`・`SOFT_GL_REPLAY`）・`EngineFlags` 5 つ（再登場は `ArchitectureHealthTest#legacyPaintDriverTypesStayGone`）。**引き換え**: 前のフレームの姿勢を使い回さないので、項目の再生と止まった姿勢の使い回しが消え、CPU で描く costGate の行は +3〜7%・`surface_test scroll cpu` は 1 フレーム +76 KB（約 560 個の姿勢）。姿勢を動かす補間（変形）のフレームは全面を描き直す（以前は変形の遷移も差分だった）。内側のコンテナのスクロールはコンテナの枠を描き直す。描かない側の時間は交互 A/B を取っていない（`memoryAndLatencyGate` の latency は描画を含まない）。**確かめ**: `PartialRepaintRealDocumentsTest`（実文書 9 本の全フレームで、使い続けた描画器が差分で描いた画素が、新しい描画器の全面の画素と 1 画素も違わない。差分のフレームは 6 本の文書で 22〜24/24〜26）・`PartialRepaintOutsideSceneTest`（キャレット・インスペクタの枠の移動・利用者の平行移動）・`GpuFrameSequencePixelTest`（GPU の描画器を使い続けた画素が新しい GPU の描画器と一致。§6-27 の見張りを付け替え）・`SceneWalkerTest`（旧 `PaintSceneDriverTest` と、影のテストの回帰 2 件を本番の入力で）。変異 8 種のうち 7 種が落ちる（タイルを消さない・動く項目を足さない・前の重ね描きを足さない・利用者の平行移動で全面にしない・補間の状態を掃除しない・列にスクロール量を焼き込む・画面の上の位置に平行移動を足さない）。残る 1 種（デバイス画素の 1 画素の余白を内へ削る）は `SceneDamage` の 2 px の余白とタイルが吸収する同値の変異。`realdocs`・`frames` の項目の要約は、列をスクロール 0 で組むので内側をスクロールしたフレームだけ変わった（画素は同じ）。MOD のツールチップ・面のキャレットは列の位置に平行移動を足す（`DisplayItem#resolveAbsolute(…, PaintOffsets)`）。
  - **4b-4b の結果（2026-11-28）**: **描かないフレームの判定は描画器の側**: `RenderBackend#needsRepaint(列, 平行移動, シーン, 時刻)` が、描き直す範囲が空かを描く前に答える。範囲はシーンから決まる分（`FrameDamage`: `SceneDamage` のシーンの違い＋塗りの色・利用者の平行移動の変化）と、走査器が足すシーンの外の変化（`SceneWalker#changesOutsideScene`: 列がシーンの列で始まらない・重ね描きが今か前のフレームにある・外の事情の項目・前の時刻か今に動く補間。索引・姿勢を作らない）。求めた範囲は同じ入力の次の描画がそのまま使う（比べる相手は 1 フレームに 1 度だけ進める。違う入力が来たら全面）。CPU の描画器と、GPU の描画器の保険の CPU 描画器が持ち、GPU は描くときは全面・描かないフレームは前の画素を持ち続ける（塗りの色の変化と `invalidate` の後は描く）。MOD の面は `StaticFrameGate`（項目の同一性の後追い照合）の代わりにこれを聞き、描くと決めたフレームだけ Minecraft が描く中身を用意する。書体の登録が変わったら全部の面を `invalidate`（字形はシーンに現れない）。Minecraft が描く中身・画像の画素は前から中身の鍵（種類・指定・大きさ・色 / URL）で引き、前のフレームの項目を覚えていない（1 フレームの中の項目 → テクスチャの引き当ては 4c の型付きの命令で鍵へ）。面に渡す列がセッションの列で始まらないとき（HUD で `data-mount-entity` を省いた列）はシーンを渡さず毎回描き、エンティティの上の板は組んだ `FrameRender` のシーンを添える（以前はセッションの別のシーンを添えていた）。**部品の値**（入力欄・textarea・ゲージ・スライダー・チェック）は DOM の写しでシーンに入るので、毎フレーム描き直す種類（旧 `isVolatileForDamage`）から外して外の事情（画像・動画・キャンバス・3D）だけにした（入力欄のある画面も静止すれば描かない。以前の `StaticFrameGate` と同じ）。**削除**: `StaticFrameGate`・`ItemDiff`（項目の `==` はその場に。`hasFx` は `LayerSpec#hasEffects`）・`AbsoluteItems` とビューの `elements()`・`absoluteRenderCache()`（MOD のデスクトップの矩形は `nodeItemAbsolute`、HUD の絞り込みは節を残して親相対のまま。平坦な絶対座標の列を AWT で描くテストの道具だけがテストの補助 `TestFlatItems` を使う。4c で消す）。再登場は `ArchitectureHealthTest#legacyStaticFrameTypesStayGone`。**確かめ**: `RepaintSkipTest`（実文書 9 本の全フレームで、描かないと答えたフレームも含めて持ち続けた画素が新しい描画器の全面の画素と一致。描かなかったフレームは showcase 9・hud_test 19・machine_ui 19。変化の種類ごと: DOM・スクロール・利用者の平行移動・塗りの色・重ね描きの出し入れ・補間の終わり・画像・部品の値）・`GpuRepaintSkipTest`（GPU の描画器で同じ）。変異 8 種が全て落ちる（前のフレームの重ね描き・前の時刻の補間・外の事情の項目・利用者の平行移動・GPU の塗りの色と `invalidate`・GPU 経路で範囲を引き継がない・計画の使い回し・重ね描きの項目）。**計測の穴（直した）**: 計測の包み `ProfilingRenderBackend` はシーンを描画器へ渡さず捨てていたので、包んで描く costGate の cpu の行は部分の描き直しでなく毎フレームの全面描画を測っていた。シーンと判定を渡すようにし（`PipelineProfilingTest#wrapperForwardsTheSceneAndTheRepaintDecision`）、基準を更新した（showcase の文字・スタイル −16〜17%、machine_ui の文字 +8%。転送だけを外す交互 A/B で元の値に戻る）。GPU の描画器で使わなくなった `hasActiveAnimation` は外した（GL・Vulkan の包みは走査器の `anyActiveAt` を直接引く）。
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
  - ヒープ: `varied-list-2000 ≤ 28.0 MB`・`large-list-2000 ≤ 9.0 MB`（M3b-4c-0 で置き直した。§1.4 決定 J）。根拠は §4.1 の内訳: シーン以外の段だけで 14.02 / 5.28 MB あり、以前の目標 14.0 / 5.5 MB（M2b の中間目標）はシーンを 0 にしても届かない。シーンは、型付きの命令で文字列 ID・`events`・`RenderMeta`・`RenderHints`・節の錨の命令（`NODE`）を持たず（−22.1 / −4.9 MB、命令は +4.3 / +0.7 MB）、文字の命令と `SceneRun` の写しを片方にして（−4.3 / −0.6 MB）約 13.1 / 3.4 MB の見込み。表示項目の列（18.7 / 3.4 MB）は 4c-2 で消える。以前の目標は M5 で DOM・箱の木を減らしてから見直す。
  - 性能（描画を含む 1 フレーム）:
    - 8,000 行リストの 1 件挿入 `≤ 1.0 ms`
    - `showcase` の幅の変更で scene を作る時間 `0.273 → ≤ 0.08 ms`
    - `varied-list-2000 fontsize` は下の 1 スレッドの上限で判定する（4 スレッド・実書体の `10.76 → ≤ 5.0 ms`〔144Hz の予算 `6.94 ms`〕は並列の再スタイルが前提なので、2026-11-28 に P2 の目標へ移した。§1.4 決定 K）
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

### 3.7 Phase M5: DOM の整理と 1,000 行超のクラス 0〔進行中。M5-1・M5-2・M5-3a 完了〕

- `DocumentArena` が `HtmlNode`（1,398 行）を写して持つ二重構造を解く。`HtmlNode`/`HtmlElement` を、節の基本構造・属性・DOM API・イベントの配送に分ける。セレクタの高速化に要るもの（atom 化したタグ / id / class・Bloom のビット）だけを `SelectorIndex` として独立させる。
- **段（2026-11-27 に分けた）**: DOM の側（`core.dom`・`core.css` の照合）は描画に触れないので、M3b と並行して先に進める。ヒープと 1,000 行超 0 の判定は描画の側（M3b）とホスト層（M4）の後にまとめる。

| 段 | 内容 | 状態 |
| :--- | :--- | :--- |
| **M5-1** | **番号の表の二重同期の解消と `SelectorIndex`**: `DocumentArena`（オフヒープの列 11 本に親・子・兄弟・タグ・id・Bloom を写し、子の並びと二重に同期）を番号と世代だけの `NodeIds`（`core.dom.node`。ヒープの配列）に置き換える。木の形の正本は子の並びだけにし、前の兄弟は子の並びが保つ位置（`indexInParent()`。O(1)）で引く。class の atom・自分 / 子孫の Bloom は `SelectorIndex` が節の欄で保ち、番号の有無に依らない。`HtmlNode` を番号と鍵（`NodeNumbering`）・木の変更と文書順と複製（`TreeOps`）・属性（`NodeAttributes`）・文字の内容（`NodeText`）・イベント（`NodeEvents`）に分ける | **完了**（2026-11-27）。`DocumentArena` と `OffHeapColumns` の `int`/`long` の列を削除（再登場は `ArchitectureHealthTest#domArenaCopyStaysGone`）。`HtmlNode` 1,398 → 739 行（残りは Node/Element の API の入口）。番号は振らずに照合する（`hasNodeId()` の照会が照合の熱い経路から消えた）。子の並びへ直接まとめて足す経路（`addAll`）が祖先の子孫 Bloom へ広げず `:has()` が当たりを落としていた欠陥と、`remove(Object)`・`removeIf` が節を 2 回外していたのを直した。検証は `SelectorIndexOracleTest`（300 手の変更の後も作り直した木と照合が一致）・`ChildListIndexTest`・`NodeIdentityTest`・`NodeIdsTest`。各修正を戻す変異で落ちる |
| **M5-2** | `HtmlElement`（980）・`HtmlParser`（1,214）・`Bindings`（1,175）・`InteractionState`（1,039）の分割（描画に触れない DOM の側） | **完了**（2026-11-27）。パーサを公開の入口 `HtmlParser`（165）・1 回の解析の状態と挿入モード `TreeBuilder`（564）・書式要素の一覧と adoption agency `ActiveFormatting`・表の挿入モード `TableModes`・`ParserText`・`TagSets` に、`Bindings`（584）から `ReactiveDomBinder`・`TemplateDeps` を、`HtmlElement`（634）から `HtmlDOMTokenList`・`HtmlCSSStyleDeclaration`・`TopLayerOps` を、`InteractionState`（602）から `StateFlags`・`StyleInvalidation` を出した（入れ子の型は最上位へ。`Bindings.DEP_ALWAYS` は `TemplateDeps.ALWAYS` へ。MOD の `ArchitectureTest` の「バインディングの解釈は 1 か所」は、この 3 ファイルを 1 か所と数える）。M3b-3 が触る当たり判定・フォーカス・CSSOM の部分（hover / active / focus の鎖・`LayoutQuery`）は動かしていない。前後の要素の兄弟は子の並びの位置から引く。新しい `TreeBuilderFuzzTest`（600 件の乱数のタグの並びを文書と 8 種の文脈の断片で読み、木の形の不変条件を確かめる）が、子を新しい親へ足してから古い並びを空にする順で移った子が仮想の節にされ id 索引と番号を失う欠陥（M5-1 より前から）を見つけ、子の並びの側で直した。分ける前のコミットと同じ 600 件の木を書き出して突き合わせ、全件一致（404 KB） |
| **M5-3a** | 分けたクラスを各 ≤ 500 行に（`HtmlNode` 739・`HtmlElement` 634・`InteractionState` 602・`Bindings` 584・`TreeBuilder` 564）。描画に触れない DOM の側なので M3b と並行 | **完了**（2026-11-27）。公開の API は変えずに役割で分けた: `HtmlNode` 447（DOM の API の入口）と上位の `NodeState` 320（木の形・番号と鍵・セレクタの足切りの欄、文書への知らせ・子の付け外し・属性の版。継ぐのは `HtmlNode` だけ）、`HtmlElement` 480 と上位の `ElementCssomView` 171（CSSOM View の位置・大きさ・スクロール）、`InteractionState` 425 と `InteractionChains` 233（hover・active・focus-within の鎖・Top Layer の積み順・不活性）、`Bindings` 486 と `DeclarativeBindings` 114（`data-bind`・`data-list`・`data-hud`・`data-tracker` の走査。MOD の `ArchitectureTest` は 4 つで解釈の 1 か所と数える）、`TreeBuilder` 358 と `BodyMode` 234（in body）。分ける前のコミットと `TreeBuilderFuzzTest` の 600 件の木（文書と 8 種の文脈の断片）を書き出して突き合わせ、全件一致（404 KB） |
| **M5-3b** | 完了ゲート（M3b・M4 の後）: 下の完了ゲート（1,000 行超 0・`DocumentArena` の二重同期 0・保持ヒープ）。M5-3a で行数を合わせるために挟んだ上位クラス（`NodeState`・`ElementCssomView`）を委譲の部品に直す（§1.4 の 10・決定 L） | 未着手 |

- **完了ゲート**: `:core` と MOD の全体で 1,000 行超のクラス `0`（`ArchitectureHealthTest` で強制）・`DocumentArena` の二重同期 `0`・分けたクラスは各 `≤ 500 行`・全体の保持ヒープ `varied-list-2000 ≤ 12.0 MB`・`large-list-2000 ≤ 4.5 MB`（2026-11-28 の測定ではシーン以外の段〔DOM・ビュー 5.81 / 1.54・箱の木 3.93 / 2.03・断片木 1.39 / 0.49・プロセス共有の表 2.89 / 1.22 MB〕だけで 14.02 / 5.28 MB ある。M5-3b の前に、DOM・箱の木の内訳〔§4.1〕から届く値を測り直して置き直す）。

### 3.8 Phase M6: 品質ゲートの最終固定と配布（v2.0.0 GA）

- `ArchitectureHealthTest` の行数の上限を **全クラス `≤ 600 行`** のハードゲートにする（例外は生成物・純データ表などの明示した一覧だけ）。グローバルな可変の static を `0` にする。
- Linux の全件（`scripts/ci-linux.ps1 -Full`）を通し、公開 API の署名（`ApiSignatureTest`）を確定し、`:core` を単体のライブラリとして Maven Central / GitHub Packages に公開する。

---

### 3.9 独立トラックの詳細 → [`ENGINE_FEATURE_PLAN.md`](ENGINE_FEATURE_PLAN.md) §3

2026-11-28 に機能拡張プランへ移した（P1a・P4-1 の結果と、P4-2 へ持ち越す結合も同書にある）。

---

## 4. KPI ダッシュボード

時間の主張は交互 A/B の中央値だけ（絶対値はセッションで ±40% ずれる）。「現在」の列は作業のたびに上書きする。**2026-11-28（M3b-4c-0）に M3b-4b の後のコードを同じ条件で測り直した**: ゲートの latency・ヒープは `memoryAndLatencyGate`（FALLBACK 書体・1 スレッド・シーンと表示項目の列まで作り、ラスタは含まない）、ヒープの内訳は直した `heapBreakdown`（§4.1）。M0 着手前の値は条件が違う行がある（各セルに書く）。

| 軸 | 指標 / シナリオ | M0 着手前（2026-11-24） | 現在（2026-11-28。M3b-4c-0 の後） | 目標（達成フェーズ） |
| :--- | :--- | :---: | :---: | :---: |
| **構造** | `:core` 本体の行数・ファイル数 | `113,195 行`・515 | **`109,736 行`・613**（`core/src/main/java` の `.java` を `wc -l`。M3b-4c-0 で `Shortcut`・`EngineFlags` の切り替え 3 つを消し −6 行。M3b-4b 全体は 4b-1 前の約 109,600 行から、走査器・`SceneDamage` を足して旧描画の駆動器・項目の再生・静止判定を消し、ほぼ横ばい） | `≤ 120,000 行`（M2c。**達成**）→ M3b 以降は各フェーズで減らす |
| **構造** | 経路の数 | 1 | **1**（M2c-3 で `EnginePath` と旧経路を削除） | **1**（M2c。**達成**） |
| **構造** | 中間表現の段数（DOM の後〜バックエンドの前） | 6〜7 | **3 段 + 一時アダプタ**（箱の木 → 断片木 → `DisplayScene`〔層が描画命令を持つ〕。一時 `SceneDisplayListAdapter` が命令を `DisplayItem` に組み立て、走査器がその列をたどる。M3b-4c-2 で列とアダプタを消す） | **3**（M3b-4） |
| **構造** | 増分・キャッシュ専用のコードの規模 | 42 ファイル・`6,360 行` 相当 | **`2,093 行`**（`ChangeLedger` 156 ＋ `BoxTreeCompiler` 560 ＋ `LayoutCache` 187 ＋ `SceneEmitter` 532 ＋ `SceneDamage` 477 ＋ `FrameDamage` 77 ＋ `ScrollContentRanges` 104。`ScrollContentRanges` と `FrameDamage` の plan → take は M3b-4c-2 で消す） | **`≤ 1,000 行`**（M3b。4c-2 の後に見直す） |
| **構造** | `LayoutBox` / `ComputedStyle` / `UiView` の行数 | `1,785` / `2,095` / `2,005` | **削除** / `451` / `1,433`（`UiView` は M3b-4b で描画器へ渡すシーンの束・動的状態の覚えを足して +50） | 削除（M2c。**達成**）/ `≤ 450`（M5）/ 解体（M4） |
| **構造** | 1,000 行超 / 600 行超のクラス（`:core` 本体） | `19` / `55` | **`8` / `29`**（`wc -l`。1,000 行超は `UiView` 1,433・`ColorParser`・`SoftwareRenderBackend` 1,178・`CanvasRenderingContext2D`・`GpuRenderBackend` 1,138・`StyleResolver`・`TextShaper`・`ShaderSources`。`core.dom` には無い） | `0`（M5）/ `0`（M6） |
| **構造** | `Shortcut` / `EngineFlags` | `35` / `45`（M0 で凍結） | **`10` / `18`**（M3b-4c-0 で `SOFT_DAMAGE`・`RETAIN_SCROLL_LAYERS` を描画器ごとの設定にして削除し、`ArchitectureHealthTest` の上限を 10 / 18 に下げた。残りは GL・ソフトウェア描画・シートの雛形の切り替え） | レイアウト系 0（M2c。**達成**）→ `0` / `0`（M3b） |
| **構造** | `static volatile` / `ThreadLocal` | 約 `50` / `27` | `40` / `14`（`Atomic`/`LongAdder` 18・共有エポック 0。当たり判定の作業用の値はビューの文脈が持つ） | グローバルな可変の static `0`（M6） |
| **構造** | MOD の旧レンダラ（`client/renderer`） | `9,369 行` | **旧レンダラ 0**。面を描く経路は `1,277 行`（§3.3） | `≤ 1,500 行`（M3a。**達成**） |
| **性能** | `large-list-8000 child`（1 件挿入） | `7.89 ms` | ゲートの latency **`21.99 ms`**（FALLBACK・1 スレッド・表示項目の列まで。ラスタは含まない。上限 57.85 ms） | 描画を含めて **`≤ 1.0 ms`**（M3b） |
| **性能** | hover / 文字の変更の 1 フレーム（showcase） | — | `ab-inc-vs-full` は `0.182x` / `0.118x`（上限 `0.900x` / `0.980x`）。`varied-list-2000:hover` `0.157x`・`large-list-2000:child` `0.199x` | `ab-inc-vs-full` の上限内（M2c。**達成**）→ 締め直す（M3b） |
| **性能** | `varied-list-2000 fontsize`（4 スレッド・実書体） | `10.76 ms` | ゲートの latency（1 スレッド・FALLBACK）**`141.6 ms`**（上限 157.7 ms）。同じセッションで M3b-4a のコミットも `139.4 ms`・4c-0 の前の HEAD も `141.5 ms` なので後退ではない（以前の 80.80 ms は別のセッションの値。絶対値の比較には使わない） | 1 スレッド **`≤ 46.6 ms`**（M3b）／4 スレッド・実書体 **`≤ 5.0 ms`**（P2。§1.4 決定 K） |
| **性能** | `showcase` 幅の変更（`resizeexact`・GL） | `0.427 ms`（表示リストが 66%） | ゲートの latency（FALLBACK）`1.88 ms`。GL の実測は未計測 | **`≤ 0.20 ms`**（M3b） |
| **性能** | `voidwave.html open` | `9.15 ms` | ゲートの latency（FALLBACK）`16.47 ms`。`costGate` の確保量は下の正しさの行 | **`≤ 5.5 ms`**（M3b〜M5 + P2） |
| **メモリ** | 保持ヒープ `varied-list-2000` / `large-list-2000` | `35.96` / `9.63 MB` | **`68.16` / `16.21 MB`**（ゲート。表示項目の列〔18.4 / 3.2 MB〕と層の描画命令の二重持ち。内訳は §4.1） | **`≤ 28.0` / `≤ 9.0 MB`**（M3b。4c-0 で置き直した。§1.4 決定 J）→ M5 で見直す（以前の目標 14.0 / 5.5・M5 の 12.0 / 4.5 はシーン以外の段だけで超える） |
| **正しさ** | WPT 参照画像比較 | `160 / 260` | **`170 / 260`**（一覧は `reftest-passing-v3.txt` の 1 つ。旧経路の一覧は削除） | `≥ 170 / 260`（M2c 以降） |
| **正しさ** | 3 者画素一致・`FrameOracle` | 緑 | 緑（2026-11-28 M3b-4c-0 の後: `:core:test` 2,822 件〔失敗 0・飛ばし 21〕・`:mod:test` 452 件・`costGate` 56 本 OK〔`voidwave` open の確保 13.32 MB〕・`memoryAndLatencyGate` OK。差分描画の画素の番人〔`PartialRepaintPixelTest`・`PartialRepaintRealDocumentsTest`・`RepaintSkipTest`・`IncrementalVsFullTest`〕は全面の基準を描画器ごとの `partialRepaint(false)` で作る。`ci-linux.ps1 -Full` は push の前に回す） | 緑を保つ |

### 4.1 新経路の保持ヒープと時間の段ごとの内訳（M3b-4c-0。2026-11-28）

ヒープは `:core:heapBreakdown`（`V3HeapBreakdownMain`。ゲートと同じ文書・フレームを回し、出力に近い段から参照を外して GC 後の減りを測る。共有分は先に外した段に入る）。時間は M2c-1/M2c-2 のシナリオ別内訳（`StageProfiler`・FALLBACK・1 スレッド）。

| 文書 | 全体 | 表示リスト・一時アダプタ（M3b-4c-2 で消える） | `DisplayScene`（`SceneLayer`/`SceneChunk`・描画命令） | 断片木とレイアウトの記憶 | 箱の木と `Style` | DOM・ビュー | 測りきれない差（プロセス共有の表） |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `varied-list-2000` | **67.91 MB** | `18.43 + 0.25` | `35.23` | `1.39` | `3.93` | `5.81` | `2.89` |
| `large-list-2000` | **16.69 MB** | `3.24 + 0.12` | `8.08` | `0.49` | `2.03` | `1.54` | `1.22` |

| シナリオ（M2c-2 参考） | 1 フレーム | 箱の木 | レイアウト | 描画用の木 | 表示リスト | 表示項目の数（旧経路） |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `varied-list-2000:fontsize` | 79 ms | 26.3 | 10.2 | 17.6 | 22.6 | 97,886（652） |
| `large-list-8000:child` | 31 ms | 0.26 | 0.06 | 5.4 | 22.6 | 80,012（462） |

**測り方を直した（M3b-4c-0）**: 道具が 4b-4a で足した `UiView.cachedSource`・`cachedDynRoot` を外さず、局所変数がビューを生かしていたので、全段 0・差 48.7 MB と出ていた。以前の「共有・GC 差」8.24 / 1.89 MB の大半は DOM・ビュー（5.81 / 1.54）で、残りはビューを閉じても残るプロセス共有の表（シートの雛形・書体の送り幅）。**シーンの段の中身**（`-Pheap.detail=true`）: `varied-list-2000` は層 7,050・命令 97,879（`TEXT` 83,916・`RECT` 7,056・節の錨だけの `NODE` 6,907）。`DrawOp$Item` 10.45・`RenderMeta` 5.97・`RenderHints` 0.94・文字列 ID 約 4.7 MB（命令ごとに 1 つ、計 19.6 万字）・`SceneRun` 3.89 + 0.41・`SceneLayer` 1.40 MB。`events` を持つ命令 84,416、角丸 10,363、`ClipRect`・影・グラデーションは 0。`large-list-2000` は層 4,005・命令 20,010（`NODE` 4,005）で同じ形。**型付きの命令の見込み**: 文字列 ID・`events`・`RenderMeta`・`RenderHints`・`NODE` を持たない小さい命令（矩形 40 B・文字 48 B）にすると −22.1 / −4.9 MB・+4.3 / +0.7 MB、文字の命令と `SceneRun` の写しを片方にすると −4.3 / −0.6 MB で、シーンは約 13.1 / 3.4 MB。列が消えると全体は約 27 / 8.7 MB（決定 J の 28.0 / 9.0 MB の根拠）。
`fontsize` の箱の木（全要素の再スタイル 26 ms）は新経路の欠陥ではない: 旧経路も 1 スレッドで毎回違う font-size に変えると再スタイル 26.0 ms・測定 27.7 ms になる（ゲートの旧経路 21.8 ms は 16px / 15px の交互で旧経路の記憶が当たる値。旧経路の再スタイルを 4 スレッドで並列に解くのは P2 の範囲）。

---

## 5. 運用ルール（構造の崩壊とパッチ地獄に戻らないための 8 原則）

1. **優先順位表は、リファクタリング（本流 M0〜M6）は本書の §3、新しい機能（Track P）は [`ENGINE_FEATURE_PLAN.md`](ENGINE_FEATURE_PLAN.md) の §2 の 1 つずつだけとする。** 新しい課題はどちらかの表に統合し、成果と数値は各書の §0 と §4 を上書きする（Track P のコールド起動・リポジトリの構成の KPI も同書へ移した）。
2. **壊れた中間表現や可変オブジェクトの上に、対症療法のキャッシュを足さない。** 速度・メモリの問題が出たら、まずデータ構造（入力と出力の分離・参照の共有・階層の表現）を疑い、破壊的変更で根を絶つ。
3. **変更検知の入口は `ChangeLedger` だけ。下流の記憶は §2.3 の表の鍵だけとする。**
   - 禁止するもの: DOM への `DIRTY_*` の欄・多欄の後追い照合（`PaintStamps` 型）・読み取り台帳（`LayoutReads` 型）・純粋性の述語（`F_*`・子から親へ伝える「使い回せるか」）・出力のその場の書き換え・退避と復元（`TentativeSlots`）・使い回さない部品の列挙。
   - やむを得ない一時の例外は、§3 の「凍結した例外」の表に場所と消す段を書いてから置く。表の外に足さない。
4. **プロセスグローバルな可変状態を禁止する。** 新しい `Shortcut`・`static volatile` のフラグ・プロセス共通のエポック・業務ロジックの `ThreadLocal` を作らない。状態は全て 1 ビュー（`EngineView`）に閉じる。
5. **クラスの規模を 2 段のハードゲートで強制する。** M0〜M5 の間は新規・刷新するクラスを `≤ 600 行` とし、M5 の完了で `1,000 行超 = 0`、M6 で `全クラス ≤ 600 行`（明示した一覧を除く）を `ArchitectureHealthTest` で強制する。
   - 行数は役割の型（委譲・部品）で満たす。1 つの子しか継がない上位クラスを挟んで行数を合わせない（2026-11-28 の点検。§1.4 決定 L）。
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
- 新しい機能・ホスト・配布（Track P1〜P6）は [`ENGINE_FEATURE_PLAN.md`](ENGINE_FEATURE_PLAN.md) に分けた（2026-11-28）。本書の §5 の運用ルールは同書にもそのまま適用する。
- 文書の置き場（P4-1）: エンジンの文書は `docs/`（計画書・ARCHIVE は `docs/plan/`、過去の原文は `docs/plan/archive/`）、MOD の文書は `mod/docs/`。目次は `docs/README.md` と `mod/README.md`。