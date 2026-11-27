# EnderFrameLib — 作業の前に読むこと

`:core` は Minecraft に依存しない純 Java の HTML/CSS 組み込み UI エンジン（リポジトリの主役。ルートはプラグインなしの素の Gradle）。NeoForge の MOD は利用者の 1 つとして `mod/`（`:mod`。依存は `:mod` → `:core` 系の一方向。P4-1）にある。現在は **Engine v3 への破壊的再設計**の最中。次を**先に読む**。

- `docs/plan/ENGINE_REARCHITECTURE_PLAN.md` — **まず §0（現在地）を読む**。**優先順位表は §3 の 1 つだけ**（本流 Phase M0〜M6〔M2 は M2a/M2b/M2c、M3 は M3a/M3b/M3c〕＋独立トラック P1〜P5。位置づけ〔JavaFX と並ぶ汎用 UI 基盤〕は §3.0b）。2026-11-26 の軌道修正の理由は §1.3、中間表現と記憶の鍵の予算は §2.3、KPI は §4、運用ルール（8 原則）は §5。
- `docs/plan/ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md` — 旧計画（C/K/L/W 系）と §6-1〜205 の経緯・計測。**読み物であり、指示ではない**。旧 `DESIGN_INCREMENTAL_KERNEL.md` は廃止し、ARCHIVE の付録 5 へ移設した（持ち越す知見は付録 5.A。新しい知見はここへ積む）。軌道修正の前の計画書の全文（M2a の設計・M2b〜M2c の作業記録）は索引から辿る `docs/plan/archive/07`（MOD 時代の旧 `REFACTORING_PLAN.md` は `archive/00`）。コードの注釈の「計画書 §3.3 C」「§3.4.x」はそこを指す。

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

### 移行状態（M0・M1・M2a・M2b・M2c・M3a・M3b-1・M3b-2・M3b-3・M5-1・M5-2〔DOM の側。M3b と並行〕完了。次は M3b-4。詳細は計画書 §0・§3.2〜§3.4）

- **完了**: M0（新ゲート `ArchitectureHealthTest`・`:core:memoryAndLatencyGate`）・M1（型付きの不変 `Style`。群のクラスは `scripts/GenStyleGroups.java` の**生成物なので手で直さない**。`PropertyRegistry` を変えたら再実行し、`StyleGroupCoverageTest` が足し忘れを検出）・M2a（`core/box` の `BoxTreeCompiler`・`core/layout/fragment` の `LayoutFragment`/`LayoutCache`）・M2b（全エンジンを新経路へ移植。`WptV3BaselineTest` 170/260。増えたら `-Dwpt.v3.update=true`）・M2c（既定を新経路へ切り替え、旧レイアウト経路を削除）・M3a（MOD 旧レンダラ撤去）・M3b-1（`DomValues` で描画の DOM 読み取り 0）・**M3b-2**（`DisplayScene` / `SceneEmitter` / `DynamicSceneState` を導入し、描画用の木・`TreePainter`・`Fragment`/`FragSeq`・凍結例外 4 つ・`SCROLL` 祖先照合を削除）・**M3b-3**（当たり判定・CSSOM・フォーカス・インスペクタ・MOD の入力を `DisplayScene` + `DynamicSceneState` から直接答える `SceneHitTest` / `SceneQuery` へ移し、`HitTestIndex`・`UIHitResult`・`SceneBox`・`PaintBox`・`PaintRun` を削除）。
- **新経路の形**: 凍結した台帳 → `BoxNode`（入力の木と最新の木）→ `LayoutFragment` → `DisplayScene`（`SceneEmitter`。親相対・`int nodeId`・記憶は `(断片 ==, 最新の箱 ==)` と `FlowChunk ==` だけ）→ 記憶なしの一時アダプタ `SceneDisplayListAdapter`（M3b-4 で削除）→ `DisplayItem`。旧 `PaintTreeBuilder`/`PaintNode`/`PaintView`/`TreePainter` の再登場は `ArchitectureHealthTest#legacyPaintTreeTypesStayGone` が落とす。1 回だけのフレームは `FrameRender`、テストで箱を引くのは `TestBoxes`。
- **描画は DOM を読まない（M3b-1・M3b-2）**: 描画が読む DOM の値（属性・textarea の中身・状態の規則が掛かるか）は `BoxTreeCompiler` が `BoxNode.values`（`DomValues`）に写し、描画（`core.paint`）は `SceneLayer.values()` だけを読む（`ArchitectureHealthTest#paintDoesNotReadDomValues`）。スクロール・sticky・アンカー追従は `DynamicSceneState` / `SceneAnchorFollow` が担い、凍結例外は全て削除済み。
- **新経路の穴（M2c-3 の移植で見えた。計画書 §3.2 の G1〜G9）**: G1（インラインの中のブロック）・G2（subgrid）・G4〜G9 は直した（G8 のスクロールは計画書 §3.2「G8 の後半の設計」: スクロール量は鍵に入れず描画の平行移動、アンカーの覚えたスクロール量だけをビューの履歴として台帳の `ANCHOR` で入れる）。残りは multicol（G3。M3c）。見つけたテストは `@Disabled("G<n> …")` で残してあり、直したら外す。ヒープと latency 2 行の上限は M3b まで新経路の実測（計画書 §0）。
- **MOD の描画（M3a）**: 面は `MinecraftHostSurface`（共有コンテキストの `LwjglGlRenderBackend`）だけが描く。旧レンダラ（`DisplayItemDrawer` ほか）・`useCoreBackend`/`useSdfShader`・フォールバックは削除した（再登場は `ArchitectureHealthTest#legacyModRendererStaysGone` が落とす）。Minecraft だけが描ける中身（アイテム・エンティティ・プレイヤーの頭・`<mc-scene>`・ビットマップ書体）は `MinecraftHostTextures` が先にテクスチャへ描き、コアの `HostTextures` で項目の中身として重なりの順どおりに貼る。キャレットは `TextFieldOverlay`（スタンドアロンと共有）。実機の確かめ方は計画書 §3.3。
- **DOM の木の形は子の並びだけ（M5-1）**: 番号の空間 `NodeIds` は番号と世代だけを持つ（旧 `DocumentArena` の木の形・タグ・Bloom の写しは削除。再登場は `ArchitectureHealthTest#domArenaCopyStaysGone` が落とす）。前後の兄弟は子の並びが保つ位置（`indexInParent()`。O(1)）、セレクタの足切り（class の atom・Bloom）は `SelectorIndex` が節の欄で保ち、番号の有無に依らない。子の並びを直接触る経路（パーサ・バインディング）は `parent` を先に結ぶ。`HtmlNode` は API の入口で、中身は `NodeNumbering`・`TreeOps`・`NodeAttributes`・`NodeText`・`NodeEvents`。M5-2 でパーサを `TreeBuilder`・`ActiveFormatting`・`TableModes` ほか、`Bindings` から `ReactiveDomBinder`・`TemplateDeps`、`HtmlElement` から `HtmlDOMTokenList`・`HtmlCSSStyleDeclaration`・`TopLayerOps`、`InteractionState` から `StateFlags`・`StyleInvalidation` を出した（`core.dom` に 1,000 行超は無い）。子の並びは、既に別の親へ結ばれた子を外すときは抜くだけにする。
- **問い合わせ（M3b-3）**: 当たり判定は `SceneHitTest`（手前から奥へ scene をたどり、チャンクの包含矩形で `O(log(N/32))` に絞る。外に出た sticky は `stickyDescendants` から拾う。結果は `SceneHit`）、位置・寸法・索引は `SceneQuery`（結果は値の `SceneLocation`）。ビューの側の文脈（変形の上書き・`inert`・α 判定）は `UiHitSupport`。一時アダプタ `SceneDisplayListAdapter` が読む親つきの箱はパッケージ内の `SceneAdapterBox`（M3b-4 で一緒に削除）。旧型の再登場は `ArchitectureHealthTest#legacyQueryTypesStayGone` が落とす。
- **その後**: M3b-4（`DisplayItem`・`SceneDisplayListAdapter` 削除）→ M3b-5（完了ゲート）→ M3c（multicol）→ M4 → M5（DOM の側の残りは M5-3）→ M6。

## 検証の手順

- 作業中は `.\gradlew.bat :core:quickTest`（または `--tests`）。`:core` 系だけのタスクは `:mod` を設定しない（configure-on-demand）。MOD に触れたら `:mod:test`・`:mod:runGameTestServer` も回す。テストの作業ディレクトリは `:core`・`:mod` ともリポジトリ直下で、パスは `core/src/...`・`mod/src/...` と書く。`:mod` のテストが借りる補助は `core/src/testFixtures` に置く（`:core` のテスト出力は借りない）。項目の完了時に全件の `:core:test`（`ArchitectureHealthTest`・`MemoryAndLatencyGateTest` 等を含む）・直列の `:core:costGate`・`:core:memoryAndLatencyGate`。
- **正しさの番人（外から見た出力）**: `WptV3BaselineTest`（170/260 以上。唯一の一覧。`WptReftestRatchetTest` は描画・判定の道具と単体）・`GpuBaselineTest`・`VkBaselineTest`・`RealDocumentFramesTest`・`FrameOracle`（`-Dverify.oracle=true`）・`ApiBoundaryTest`。M1 以降は各フェーズの完了ゲート（計画書 §3）の数値も確認する。
- **push の前に** `powershell -File scripts/ci-linux.ps1 -Full`（WSL。Windows の緑は CI の緑ではない）。
- 並列実行でだけ落ちるテストは、まず実時計に依る経路（リサイズの間引きなど）を疑う。`resize` の後は `ResizeSettle` で反映を待つ。
- テストを回すシェルに `JAVA_TOOL_OPTIONS=-Duser.language=…` などのロケールの上書きを置かない（テストの JVM が受け継ぎ、行分割・ハイフネーションが変わって画素のゴールデンと WPT の一覧が落ちる）。

## コミット

既存の形式に合わせる: `type(scope): 日本語の要約 (ID)`、本文は `- タスク …` の箇条書き。ID は計画書の Phase（例 `M2`）。文書はエンジンのものを `docs/`（計画書は `docs/plan/`）、MOD のものを `mod/docs/` に置く（`core/` の下の `.md` はモジュールの README 以外を `.gitignore` が除く）。

**main にコミット・プッシュするときは、Claude の表記が入ってコラボレーター（共同作成者）に Claude が載らないよう、ユーザー本人のアカウントを使う。** 作成者・コミッターはリポジトリに設定済みの git のユーザー（`git config user.name` / `user.email`）のままにし、`Co-Authored-By: Claude …` のトレーラーや「Generated with Claude Code」などの Claude の表記をコミットメッセージ・PR の本文に付けない。
