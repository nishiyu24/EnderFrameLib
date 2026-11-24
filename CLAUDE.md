# EnderFrameLib — 作業の前に読むこと

`:core` は Minecraft に依存しない純 Java の HTML/CSS 組み込み UI エンジン。現在は **Engine v3 への破壊的再設計**の最中。次を**先に読む**。

- `core/ENGINE_REARCHITECTURE_PLAN.md` — **優先順位表は §3 の 1 つだけ**（本流 Phase M0〜M6〔M2 は M2a/M2b〕＋独立トラック P1〜P3）。M2 の入力側（DOM → `BoxNode`）の増分設計と M2b 着手前の縦割りスパイク関門（M2a）は §3.3、KPI は §4、運用ルール（7 原則）は §5。
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

### 移行状態（Phase M0 完了）

M0 は完了済み。旧ラチェット 8 本・`*Verifier` 3 種・`VERIFY_MEMO` / `VERIFY_BOX_MEMO`・`:core:shortcutMatrix`・`costGate` の `memo*` 判定は撤去され、代わりに **`ArchitectureHealthTest`**（1 クラス行数上限・mutable static 上限・SCC・`:core` 純度・既存 `Shortcut` / `EngineFlags` 上限凍結〔新規追加禁止・単調減少のみ〕）と **`:core:memoryAndLatencyGate`**（保持ヒープ MB と交互 A/B 遅延 ms）が稼働している。

### Phase M1 の進行（M1a 完了／M1b・M1c 未着手。分割と設計要件は計画書 §3.2.1）

M1a で型付きスタイル（`core/css/` の `Style`・`CssLength`・6 群）を**旧 `ComputedStyle` と併存で新設**し、旧読み口との全フィールド等価を検証済み。群のクラス（`InheritedStyle` ほか 5 つ）は `scripts/GenStyleGroups.java` の**生成物なので手で直さない**（`PropertyRegistry` を変えたら再実行。`StyleGroupCoverageTest` が足し忘れを検出）。M1b の最重要要件は「カスケードが一致規則集合の `==` をキーに `Style` を 1 層メモ化して返す」こと（群を要素ごとに作ると旧表現よりヒープが大きい）。旧型の削除は M1c。

## 検証の手順

- 作業中は `.\gradlew.bat :core:quickTest`（または `--tests`）。項目の完了時に全件の `:core:test`（`ArchitectureHealthTest`・`MemoryAndLatencyGateTest` 等を含む）・直列の `:core:costGate`・`:core:memoryAndLatencyGate`。
- **正しさの番人（外から見た出力）**: `WptReftestRatchetTest`（160/260 以上）・`GpuBaselineTest`・`VkBaselineTest`・`RealDocumentFramesTest`・`FrameOracle`（`-Dverify.oracle=true`）・`ApiBoundaryTest`。M1 以降は各フェーズの完了ゲート（計画書 §3）の数値も確認する。
- **push の前に** `powershell -File scripts/ci-linux.ps1 -Full`（WSL。Windows の緑は CI の緑ではない）。
- 並列実行でだけ落ちるテストは、まず実時計に依る経路（リサイズの間引きなど）を疑う。`resize` の後は `ResizeSettle` で反映を待つ。

## コミット

既存の形式に合わせる: `type(scope): 日本語の要約 (ID)`、本文は `- タスク …` の箇条書き。ID は計画書の Phase（例 `M2`）。`core/*.md` は `.gitignore` の対象なので、新規の文書は `git add -f` で追加する。
