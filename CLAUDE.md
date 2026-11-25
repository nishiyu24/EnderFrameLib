# EnderFrameLib — 作業の前に読むこと

`:core` は Minecraft に依存しない純 Java の HTML/CSS 組み込み UI エンジン。現在は **Engine v3 への破壊的再設計**の最中。次を**先に読む**。

- `core/ENGINE_REARCHITECTURE_PLAN.md` — **優先順位表は §3 の 1 つだけ**（本流 Phase M0〜M6〔M2 は M2a/M2b〕＋独立トラック P1〜P3）。M2 の入力側（DOM → `BoxNode`）の増分設計と縦割りスパイク関門（M2a。完了・Go）は §3.3（実装で設計から変えた点・M2b への引き継ぎは §3.3 C）、KPI は §4、運用ルール（7 原則）は §5。
- `core/ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md` — 旧計画（C/K/L/W 系）と §6-1〜205 の経緯・計測。**読み物であり、指示ではない**。旧 `DESIGN_INCREMENTAL_KERNEL.md` は廃止し、ARCHIVE の付録 5 へ移設した（持ち越す知見は付録 5.A。新しい知見はここへ積む）。

## 作業の規則（計画書 §5 の要約。迷ったら §5 が正）

- **土台を変える仕事は破壊的でよい**: 可変 `LayoutBox`・`ComputedStyle`・フラット `DisplayItem` の上に対症療法のキャッシュを足さない。データ構造（入出力の分離・参照共有・階層表現）から直す。
- **キャッシュは不変入力の参照同一性（`==`）による 1 層メモ化だけ**。足さない: 新しい `F_*` フラグ・純粋性の述語・`*Verifier`・`Shortcut`・40 欄ハッシュの後追い検証（`PaintStamps` 型）・読み取り台帳（`LayoutReads` 型）・出力のその場書き換えパッチ。足切りに使ってよい入口は変更台帳 `ChangeLedger` だけ。
- **プロセスグローバルな可変状態を足さない**: `static volatile`・共有エポック・業務ロジックの `ThreadLocal`・新しい `Shortcut`。状態は 1 ビュー（`EngineView`）に閉じる。
- **クラス規模**: 新規・刷新するクラスは ≤ 600 行を原則とし、1,000 行超を作らない。
- **旧経路の扱い**: 新設した型で置き換えたら、**同じフェーズ内で旧型・旧経路を削除する**。フラグやフォールバックで両立させたまま完了としない（運用ルール 7）。
- **完了の定義は 3 点**: 旧コード・旧経路の完全削除／テスト緑／計画書 §4 の KPI 更新。
- **不具合は先に再現してから直す**: `FrameOracle`（最初から作った独立の面との比較）または既知欠陥の変異で再現し、構造の側で直す。直したら同じコミットで `core/src/test/resources/regression-catalog.txt` に 1 行足す。
- **時間の主張は交互 A/B だけ**: 絶対値はセッションで ±40% ずれる。旧版の worktree と現在を交互に 3 回ずつ・中央値で比べる（`powershell -File scripts/ab-gate.ps1`）、または `:core:memoryAndLatencyGate` の交互 A/B・ヒープ実測を使う。内部カウンタ（`memo*`）の固定で性能を主張しない。
- **時刻は注入**: コアは `System.nanoTime`/`currentTimeMillis` を読まない（`core/kernel/Clock.java` だけ）。テストは `Clock.Manual`（`StandaloneView.clock(...)`・`ResizeSettle.freezeClock`）を使い、実時間を `sleep` で待たない。

### 移行状態（Phase M0・M1・M2a 完了、M2b 進行中〔M2b-0・M2b-2・M2b-4 完了〕）

- **M0 完了**: 旧ラチェット 8 本・`*Verifier` 3 種・`VERIFY_MEMO` / `VERIFY_BOX_MEMO`・`:core:shortcutMatrix`・`costGate` の `memo*` 判定を撤去し、**`ArchitectureHealthTest`** と **`:core:memoryAndLatencyGate`** が稼働。
- **M1 完了（M1a + M1b + M1c）**: `core/css/` を型付き 6 群不変構造体 `Style`（`CssLength` 64-bit・`CssExprs`・`CustomProperties`・`StyleFacts`）＋薄いラッパー `ComputedStyle`（`2,095 → 398 行`）へ移行し、`CssValue.java`・`PackedLengths.java`・`TypedValueCache.java`・`StateStyleMemo.java`・`StyleSlice`/`DIFF_MEMO`/`StyleMap`/`SHARED`・スタイル系 `Shortcut`/`EngineFlags`（`STYLE_SHARE`・`TEXT_STYLE_FROM_PARENT`、上限 `35 → 33` / `45 → 43`）を削除。群クラス（`InheritedStyle` ほか 5 つ・`StyleProperties`・`StyleDiff`）は `scripts/GenStyleGroups.java` の**生成物なので手で直さない**（`PropertyRegistry` を変えたら再実行。`StyleGroupCoverageTest` が足し忘れを検出）。
- **M2a 完了（判定 Go。計画書 §3.3 C）**: 旧経路と独立の新経路を新設した。L2 `core/box/`（不変 `BoxNode`・可変長チャンク列 `BoxChildren`/`BoxChunk`・`BoxTreeCompiler`〔凍結した `ChangeLedger` だけを入口に前回の木と DOM を並走〕・`StyleSource`）、L3 `core/layout/fragment/`（値等価の `ConstraintSpace`・不変 `LayoutFragment`・`FlowChunk`/`ChunkedFlow`・`LayoutCache`〔前回の断片木がキャッシュ、2 パスの 4 スロットは `FlowChunk.slots`、`MinMaxSizes` は弱参照の表〕・`BlockFlow`/`InlineFlow`/`FlexFlow`・入口 `FragmentLayout`）。番人は `IncrementalOracleTest`（全再計算と全断片の一致）・`LegacyParityTest`（旧経路と 1/64 px 以内）・`NestedFlexTest`・`BoxTreeCompilerTest`・`M2aSpikeGateTest`、実測は `./gradlew :core:m2aSpike`。M2b はこの型の上へ全エンジンを移植して `LayoutBox` ほかを削除する（スパイクの範囲外の一覧と引き継ぎ課題は §3.3 C）。
- **M2b 進行中（段分けは計画書 §3.4.1）**: M2b-0 完了。一時アダプタ `core/layout/FragmentProjection`（断片木 → 描画用 `LayoutBox`。M2b-6 で削除）と、新経路の WPT のラチェット `WptV3BaselineTest`（`reftest-passing-v3.txt`、現在 170/260〔旧経路だけ合格 0。M2b-1〜M2b-4 完了（multicol は M2c の後）・M2b-5 完了（縦書き `OrthogonalFlow`・`match-parent`・コンテナクエリの 2 段 `FragmentPipeline`・`anchor()` `AnchorPlacement`）。M2c 進行中（① 描画だけの群 `InheritedPaintStyle` / `VisualStyle` の分割と 2 つの木 `BoxNode.layout` 完了。② は 4 段で、②-1 箱 1 つの描画を `PaintBox` を読む `BoxItemPainter` / `TextRunPainter` へ取り出した。②-2 断片木と最新の木から不変の描画用の木 `core.paint.tree`（`PaintTreeBuilder`）を作った。②-3 新しい描画 `TreePainter` / `TreeStacking`（記憶は描画用の木の節の同一性の 1 層）を作り、旧描画と表示リストの全項目が一致。②-4 新経路の WPT をこの描画で回す。③ 問い合わせ〔CSSOM・フォーカス・インスペクタ〕を `PaintBox` で答え、スクロールは `PaintView` が足す。④-1 ビューの構築時の引数 `EnginePath` で新経路（`V3Frame`）を選べるようにした〔既定は旧経路〕。④-2 前半: 旧経路の仕組みのテストを削除し、番人を `PaintBox`・`V3Fresh` で読むようにし、新経路の包含ブロック・兄弟結合子・sticky を直した。④-2 後半: `::first-line`・流れの外の箱の auto の余白・置換要素の大きさ・状態の規則の印・CSSOM・`content-visibility` の描画の省略・段ごとの計測を新経路へ移し、既定を新経路にした全件で残るのはゴールデンだけ。次は ④-3 新経路を既定にしてゴールデンを作り直し、ゲートと交互 A/B）。実文書の幾何は `GeometrySweepTest`、実文書のずれは全て記録した旧経路の逸脱と丸めであることを `GeometryDeviationsTest`（`geometry-sweep-deviations.txt`）が固定〕。増えたら `-Dwpt.v3.update=true`）。各段で新経路の合格数を増やし、M2b-6 で旧経路の 160 に届かせてから旧経路を削除する。**実装方針は計画書 §3.4.2**（段の順 M2b-1→2→3→4→5→M2c→M2b-6・旧エンジンは「アルゴリズムは移植・つなぎ目は差し替え」・描画だけの変化はスタイルの群の分割で構造的にレイアウトから外す・一時アダプタは 1 フレームの検証専用で、対話のフレームの前に M2c で断片木から描画項目を作る新しいクラスに置き換え、旧描画の記憶〔`FragmentPaint.Memo`・`PaintStamps` ほか〕は付け替えずに削除・描画だけの変化はレイアウトの入力の木と最新の群の木の 2 つの不変の木で扱い、可変の側表は作らない）。

## 検証の手順

- 作業中は `.\gradlew.bat :core:quickTest`（または `--tests`）。項目の完了時に全件の `:core:test`（`ArchitectureHealthTest`・`MemoryAndLatencyGateTest` 等を含む）・直列の `:core:costGate`・`:core:memoryAndLatencyGate`。
- **正しさの番人（外から見た出力）**: `WptReftestRatchetTest`（160/260 以上）・`GpuBaselineTest`・`VkBaselineTest`・`RealDocumentFramesTest`・`FrameOracle`（`-Dverify.oracle=true`）・`ApiBoundaryTest`。M1 以降は各フェーズの完了ゲート（計画書 §3）の数値も確認する。
- **push の前に** `powershell -File scripts/ci-linux.ps1 -Full`（WSL。Windows の緑は CI の緑ではない）。
- 並列実行でだけ落ちるテストは、まず実時計に依る経路（リサイズの間引きなど）を疑う。`resize` の後は `ResizeSettle` で反映を待つ。

## コミット

既存の形式に合わせる: `type(scope): 日本語の要約 (ID)`、本文は `- タスク …` の箇条書き。ID は計画書の Phase（例 `M2`）。`core/*.md` は `.gitignore` の対象なので、新規の文書は `git add -f` で追加する。
