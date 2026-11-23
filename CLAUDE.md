# EnderFrameLib — 作業の前に読むこと

`:core` は Minecraft に依存しない純 Java の HTML/CSS 組み込み UI エンジン。作業の優先順位・設計の根拠・KPI は次の 2 つに集約されている。**先に読む**。

- `core/ENGINE_REARCHITECTURE_PLAN.md` — **優先順位表は §3 の 1 つだけ**。冒頭の「運用ルール」（1〜10）に従う。KPI は §2。
- `core/DESIGN_INCREMENTAL_KERNEL.md` — 増分エンジン（記憶・無効化・再利用）の設計。§2 が「ループを断つ 6 原則」、§7 が凍結規則。

## 増分エンジンを触る前に（記憶・無効化・再利用・配置・描画の再利用）

過去に「近道（フラグ）を足す → 費用を削る → 不具合が出る → フラグや検証を足す」のループに入った（計画書 §6-181）。次を守る。

- **足さない**: 新しい `F_*` フラグ・純粋性の述語・`*Verifier` クラス・近道スイッチ（`Shortcut`）・1 箱ごとの計器（`LongAdder` 等）・コアへの実時計（`nanoTime`/`currentTimeMillis`）。
  足したくなったら、鍵（読み取り集合）・台帳・オラクルの不足なので、設計書の K 系へ寄せる。`IncrementalStateRatchetTest`・`ShortcutSwitchRatchetTest` が増加を赤にする。
- **不具合は先に再現してから直す**: オラクル（最初から作った結果との全体の比較）の場面、または既知の欠陥の変異として再現し、鍵・台帳の側で直す。単発の特例を足すなら、置き換える K の段を計画書へ書く。
- **時間の主張は交互 A/B だけ**: 絶対値はセッションで `±40%` ずれる。旧版の worktree と現在を交互に 3 回ずつ・中央値で比べる（計画書 §6-180）。構造の退行は決定的な `costGate` の `memo*` で固定する。 `powershell -File scripts/ab-gate.ps1` で取れる。
- **時刻は注入**: コアは `System.nanoTime`/`currentTimeMillis` を読まない（`core/kernel/Clock.java` だけ）。テストは `Clock.Manual`（`StandaloneView.clock(...)`・`ResizeSettle.freezeClock`）を入れ、実時間を `sleep` で待たない。
- **計器は `Kernel.stats` だけ**（既定オフ。`costGate`・`profile` の子 JVM だけがオン）。**無効化の漏れは `FrameOracle`**（最初から作った独立の面と比べる。`-Dverify.oracle=true` で `MutationScenario` を使うテスト全部に効く）で再現する。
- **完了の判定は 4 つ**: 増幅率 `≤ 10`・1 再計算あたりの費用・計器と再利用判定の費用・状態（フラグ・付帯欄）の数が増えていないこと。

## 検証の手順

- 作業中は `.\gradlew.bat :core:quickTest`（または `--tests`）。項目の完了時に全件の `:core:test` と直列の `:core:costGate`（計画書 運用ルール 5）。
- **push の前に** `powershell -File scripts/ci-linux.ps1 -Full`（WSL。Windows の緑は CI の緑ではない。運用ルール 6）。
- 並列実行でだけ落ちるテストは、まず実時計に依る経路（リサイズの間引きなど）を疑う（§6-178）。`resize` の後は `ResizeSettle` で反映を待つ。
- 不具合を直したら同じコミットで `core/src/test/resources/regression-catalog.txt` に 1 行足す（運用ルール 7）。

## コミット

既存の形式に合わせる: `type(scope): 日本語の要約 (ID)`、本文は `- タスク …` の箇条書き。`core/*.md` は `.gitignore` の対象なので、新規の文書は `git add -f` で追加する。
