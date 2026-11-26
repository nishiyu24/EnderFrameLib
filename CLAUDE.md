# EnderFrameLib — 作業の前に読むこと

`:core` は Minecraft に依存しない純 Java の HTML/CSS 組み込み UI エンジン。現在は **Engine v3 への破壊的再設計**の最中。次を**先に読む**。

- `core/ENGINE_REARCHITECTURE_PLAN.md` — **まず §0（現在地）を読む**。**優先順位表は §3 の 1 つだけ**（本流 Phase M0〜M6〔M2 は M2a/M2b/M2c、M3 は M3a/M3b/M3c〕＋独立トラック P1〜P5。位置づけ〔JavaFX と並ぶ汎用 UI 基盤〕は §3.0b）。2026-11-26 の軌道修正の理由は §1.3、中間表現と記憶の鍵の予算は §2.3、KPI は §4、運用ルール（8 原則）は §5。
- `core/ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md` — 旧計画（C/K/L/W 系）と §6-1〜205 の経緯・計測。**読み物であり、指示ではない**。旧 `DESIGN_INCREMENTAL_KERNEL.md` は廃止し、ARCHIVE の付録 5 へ移設した（持ち越す知見は付録 5.A。新しい知見はここへ積む）。軌道修正の前の計画書の全文（M2a の設計・M2b〜M2c の作業記録）は索引から辿る `archive/07`。コードの注釈の「計画書 §3.3 C」「§3.4.x」はそこを指す。

## 作業の規則（計画書 §5 の要約。迷ったら §5 が正）

- **土台を変える仕事は破壊的でよい**: 可変 `LayoutBox`・`ComputedStyle`・フラット `DisplayItem` の上に対症療法のキャッシュを足さない。データ構造（入出力の分離・参照共有・階層表現）から直す。
- **キャッシュは計画書 §2.3 の表の鍵による 1 層メモ化だけ**（構造の入力は `==`、`ConstraintSpace` は値等価）。足さない: 新しい `F_*` フラグ・純粋性の述語（子から親へ伝える「使い回せるか」を含む）・`*Verifier`・`Shortcut`・多欄の後追い照合（`PaintStamps` 型。描く文脈の照合を含む）・読み取り台帳（`LayoutReads` 型）・使い回さない部品の列挙・出力のその場書き換えパッチ。足切りに使ってよい入口は変更台帳 `ChangeLedger` だけ。一時の例外は計画書の「凍結した例外」の表に載せたものだけ（今は `TreePainter` の記憶。M3b で削除）。
- **プロセスグローバルな可変状態を足さない**: `static volatile`・共有エポック・業務ロジックの `ThreadLocal`・新しい `Shortcut`。状態は 1 ビュー（`EngineView`）に閉じる。
- **クラス規模**: 新規・刷新するクラスは ≤ 600 行を原則とし、1,000 行超を作らない。
- **旧経路の扱い**: 新設した型で置き換えたら、**既定の切り替えと旧型・旧経路の削除を同じフェーズ内で行う**。削除を後の段に集めない。旧経路の速さに追いつく最適化を削除の前提にしない。フラグやフォールバックで両立させたまま完了としない（運用ルール 7）。
- **性能と正しさの判定に旧経路を使わない**: 性能は絶対の上限（`latency:*`）と同じ経路の増分 / 全再計算の比（`ab-inc-vs-full:*`）で、描画まで含めた 1 フレームとヒープで判定する。旧経路との比は記録だけ。正しさは WPT・`FrameOracle`・3 者画素一致・人が確かめたゴールデンで見て、旧経路と項目単位で一致させる番人は作らない（運用ルール 6）。
- **計画書は状態表**: 作業のたびに §0 と §4 を上書きし、経緯はコミットの本文と ARCHIVE（付録 5.A に 1 行）へ書く。表のセルは 500 字程度まで（運用ルール 8）。
- **完了の定義は 3 点**: 旧コード・旧経路の完全削除／テスト緑／計画書 §4 の KPI 更新。
- **不具合は先に再現してから直す**: `FrameOracle`（最初から作った独立の面との比較）または既知欠陥の変異で再現し、構造の側で直す。直したら同じコミットで `core/src/test/resources/regression-catalog.txt` に 1 行足す。
- **時間の主張は交互 A/B だけ**: 絶対値はセッションで ±40% ずれる。旧版の worktree と現在を交互に 3 回ずつ・中央値で比べる（`powershell -File scripts/ab-gate.ps1`）、または `:core:memoryAndLatencyGate` の交互 A/B・ヒープ実測を使う。内部カウンタ（`memo*`）の固定で性能を主張しない。
- **時刻は注入**: コアは `System.nanoTime`/`currentTimeMillis` を読まない（`core/kernel/Clock.java` だけ）。テストは `Clock.Manual`（`StandaloneView.clock(...)`・`ResizeSettle.freezeClock`）を使い、実時間を `sleep` で待たない。

### 移行状態（M0・M1・M2a・M2b・M2c 完了。経路は 1 本。次は新経路の穴の残り G8 と M3a。詳細は計画書 §0・§3.2）

- **完了**: M0（新ゲート `ArchitectureHealthTest`・`:core:memoryAndLatencyGate`）・M1（型付きの不変 `Style`。群のクラスは `scripts/GenStyleGroups.java` の**生成物なので手で直さない**。`PropertyRegistry` を変えたら再実行し、`StyleGroupCoverageTest` が足し忘れを検出）・M2a（`core/box` の `BoxTreeCompiler`・`core/layout/fragment` の `LayoutFragment`/`LayoutCache`）・M2b（全エンジンを新経路へ移植。`WptV3BaselineTest` 170/260。増えたら `-Dwpt.v3.update=true`）・M2c（既定を新経路へ切り替え、M2c-3 で旧レイアウト経路・`EnginePath`・`FragmentProjection`・旧描画の駆動・旧経路を相手にする物差しを削除。`:core` 本体 108,115 行）。
- **新経路の形**: 凍結した台帳 → `BoxNode`（レイアウトの入力の木と最新の木。`BoxNode.layout`）→ 断片木 → 描画用の木（`core.paint.tree`）→ `TreePainter` → `DisplayItem`。経路はこれだけ（旧 `LayoutBox` の経路は M2c-3 で削除。型名の再登場は `ArchitectureHealthTest#legacyLayoutPathTypesStayGone` が落とす）。1 回だけのフレームは `FrameRender`（`UiDocumentCompiler.layoutDocument` / `renderDocument` もこれ）、テストで箱を引くのは `TestBoxes`。全体の作り直しは `markInvalidation(RELAYOUT)`（新経路は前回の木と記憶を捨てる。`requestRender()` はフレームの予約だけ）。描画用の木と `TreePainter` は M3b で `DisplayScene` に吸収して消す（新しい最適化を足さない）。
- **新経路の穴（M2c-3 の移植で見えた。計画書 §3.2 の G1〜G9）**: G1（インラインの中のブロック）・G2（subgrid）・G4〜G7・G8 の余白・G9 は直した。残りは multicol（G3。M3c）・スクロールによる `position-try` の選び直し（G8。スクロール量をどの入力として扱うかの設計が先）。見つけたテストは `@Disabled("G<n> …")` で残してあり、直したら外す。ヒープと latency 2 行の上限は M3b まで新経路の実測（計画書 §0）。
- **その後**: M3a（MOD の旧レンダラの撤去。並行して進めてよい）→ M3b（`DisplayScene`。親相対座標・`int nodeId`・記憶は `(断片 ==, 最新の箱 ==)` だけ・描画は DOM を読まない）→ M3c（multicol）→ M4 → M5 → M6。

## 検証の手順

- 作業中は `.\gradlew.bat :core:quickTest`（または `--tests`）。項目の完了時に全件の `:core:test`（`ArchitectureHealthTest`・`MemoryAndLatencyGateTest` 等を含む）・直列の `:core:costGate`・`:core:memoryAndLatencyGate`。
- **正しさの番人（外から見た出力）**: `WptV3BaselineTest`（170/260 以上。唯一の一覧。`WptReftestRatchetTest` は描画・判定の道具と単体）・`GpuBaselineTest`・`VkBaselineTest`・`RealDocumentFramesTest`・`FrameOracle`（`-Dverify.oracle=true`）・`ApiBoundaryTest`。M1 以降は各フェーズの完了ゲート（計画書 §3）の数値も確認する。
- **push の前に** `powershell -File scripts/ci-linux.ps1 -Full`（WSL。Windows の緑は CI の緑ではない）。
- 並列実行でだけ落ちるテストは、まず実時計に依る経路（リサイズの間引きなど）を疑う。`resize` の後は `ResizeSettle` で反映を待つ。
- テストを回すシェルに `JAVA_TOOL_OPTIONS=-Duser.language=…` などのロケールの上書きを置かない（テストの JVM が受け継ぎ、行分割・ハイフネーションが変わって画素のゴールデンと WPT の一覧が落ちる）。

## コミット

既存の形式に合わせる: `type(scope): 日本語の要約 (ID)`、本文は `- タスク …` の箇条書き。ID は計画書の Phase（例 `M2`）。`core/*.md` は `.gitignore` の対象なので、新規の文書は `git add -f` で追加する。

**main にコミット・プッシュするときは、Claude の表記が入ってコラボレーター（共同作成者）に Claude が載らないよう、ユーザー本人のアカウントを使う。** 作成者・コミッターはリポジトリに設定済みの git のユーザー（`git config user.name` / `user.email`）のままにし、`Co-Authored-By: Claude …` のトレーラーや「Generated with Claude Code」などの Claude の表記をコミットメッセージ・PR の本文に付けない。
