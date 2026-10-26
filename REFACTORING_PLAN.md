# EnderFrameLib リファクタリング計画

## この文書について

EnderFrameLib は NeoForge 1.21.1 向けに、HTML トークナイズ → DOM → CSS カスケード →
レイアウト → ディスプレイリスト → Minecraft 描画という本物のブラウザに近い
パイプラインを自前実装したゲーム内 UI フレームワーク。

この文書は**これから何をどの順でやるか**と、その**現在地**を書く。
旧計画（第 1 期〜第 4 期・フェーズ 1〜7 / F0〜F5 / W0〜W12）はここで閉じ、
同じ領域の先行 MOD である **ApricityUI**（外部リファレンス）との比較から 1 から練り直した。
旧計画の各フェーズで何をしたかは git の履歴に残っている。この文書では「出発点」として要約するだけにする。

- システムの全体像・層の規約・管理指標（M1〜M9）: [ARCHITECTURE.md](ARCHITECTURE.md)
- 性能の基準値と実測: [BENCHMARK.md](BENCHMARK.md)
- ブラウザとの差異の台帳: [FIDELITY.md](FIDELITY.md)
- 規約（コメントの種類、ゴールデン更新、3 点セット）: [CONTRIBUTING.md](CONTRIBUTING.md)
- 利用者から見える制約: [README.md](README.md) の "Not supported"
- 実機・機能別の手動/自動テスト仕様書: [TEST_SPEC_SURFACE.md](TEST_SPEC_SURFACE.md)・[TEST_SPEC_BLOCKS_ITEMS.md](TEST_SPEC_BLOCKS_ITEMS.md)・[TEST_SPEC_MEDIA.md](TEST_SPEC_MEDIA.md)

---

## 現在地（2026-10-26 時点）

`./gradlew test` は 1,036 件（2026-10-26 の Phase 3b 完了後。Phase 4 実装後は 973 件、Phase 3a 実装後は 897 件。`PerformanceBenchmarkTest` / `StateMemoryBenchmarkTest` は通常 `test` では決定論的スモーク検証のみとし、厳密な実時間・メモリ閾値判定は単独 JVM の `./gradlew benchmark` に分離）、`runGameTestServer` は 5 件通過。忠実度コーパス 138 ケース（台帳 23 行）、WPT 採用 38 ケース（台帳 0 行）。
直近コミット（`511672e`）以降の作業ツリーに、H1・R4〜R7・Surface 描画の実装と本文書群の整理が載っている。
2026-10-25 にコードベース全体の技術監査を実施し、既存の残課題（R2〜R10・旧 W12）と監査で新たに判明した課題（テスト・計測の空白、ライフサイクル・入力コールバック競合、DOM 属性の直接書き換え、スタイル/描画/フォントのメモリ・GC ボトルネック、メディア取得のセキュリティ）を統合して、**テスト・計測・番人ラチェットを最優先（Phase 0）**に課題全体を再設定した。

| フェーズ | 内容 | 状態 |
|---|---|---|
| **R0+**（最優先） | **テスト・計測・番人ラチェットの拡充**（差分ベンチ・M1/DOM/R6 ラチェット・不具合再現テスト先行） | **完了（2026-10-25、Phase 0）。Phase 1 の無効化テスト 6 件が完了条件** |
| R0 | 測定の拡張（計算値・DOM の振る舞い・ディスプレイリスト・性能） | 完了（差分・マイクロ操作計測は R0+ で拡張） |
| R1 | ライブ DOM | 完了（残: バインディング・入力同期での `attrs.put` 直書き撤去 → T8） |
| R2 | イベントとフォーム | ほぼ完了（残: IME の composition。実機での観測は H の残と一緒に 2026-10-24 に済） |
| R3 | ハイブリッド実行モデルと宣言的バインディングの載せ替え | ほぼ完了（実機・`WebScreenGameTest` での観測は 2026-10-24 に済。残: 互換 `WebSession` の開き直し無しライブ反映確認・再同期時の節状態引き継ぎ） |
| T0〜T7 | 構造整理（セッション集約・入力一本化・M1〜M9 ラチェット） | 完了（[ARCHITECTURE.md](ARCHITECTURE.md) §7） |
| **T8** | **監査指摘のライフサイクル・入力コールバック・サーバー状態・セキュリティ修正** | **R0+ 直後に着手（Phase 1）** |
| H0〜H4 | Headless State（サーバーは状態だけ、テンプレートはクライアント） | 完了（番号化・Surface 連携・実機での観測も 2026-10-24〜25 に済。Surface 既知不具合は Phase 1/3 へ統合） |
| R4 | CSS の構文・カスケード・値の現代化 | ほぼ完了（残: 字形を測った `ch` / `ex`、無効化セットの接続は R5、台帳の `::marker` 8 行） |
| R5 | インクリメンタル・パイプラインと CSSOM | ほぼ完了（差分レイアウトと近道、`InvalidationSets` 接続、`ComputedStyle` の遅延確保、再レイアウト境界と `contain`、配置の使い回し、描画だけの変化の近道、`StyleAnimator`（再スタイルで動かすトランジション・`@keyframes`）、CSSOM View は 2026-10-25 に完了。残: 台帳 R5 のスクロールバー 10 行（設計判断待ち）、`@starting-style`・`transition-behavior`） |
| R6 | 描画の合成化と型付きディスプレイリスト | 完了（SDF の角丸・楕円の角・角丸に沿う破線・線形/放射/円錐グラデーション・角丸クリップ・ガウス影、型付き `RenderHints`、transform の宣言順合成、FBO レイヤー合成（グループ `opacity`・`filter`・`backdrop-filter`・`mix-blend-mode`・`isolation`・`clip-path`・`mask`・回転角丸クリップ）、VRAM 予算 `VramBudget`、旧 W12 のスクロール・シザー・世界内面の描画順と文字行部分クリップまで 2026-10-26 に完了） |
| R7 | テキストと国際化 | ほぼ完了（システムの書体、アトラスの 1 サイズ 1 置き場化と破棄、`@font-face` の統合、`ch` / `ex`、UAX #14、`direction`、UAX #9 の並べ替え、Selection / Range は 2026-10-26 に完了。残: 合字・`line-height: normal` の代替書体の指標・`writing-mode`・`contenteditable` と IME・描画の選択と Selection API のつなぎ） |
| R8 | 置換要素（SVG・Canvas 2D） | ほぼ完了（2026-10-26。ソフトウェア Canvas 2D・インライン SVG・画像としての SVG・`srcset` / `image-set()`・`object-fit` / `object-position`。残: SVG の `mask` / `filter` / `marker` / `pattern` / `tspan`、WPT `html/canvas/` の採用ケース、canvas / SVG のテキストの書体） |
| R9 | レイアウトとパーサの残り | 未着手（fieldset / legend の重ね配置だけ R2 で先行。表の台帳 4 行を含む残りは Phase 5） |
| R10 | 開発者ツール | 一部（F12 の簡易インスペクタ・F5 の再読み込み・devkit。DOM ツリー・CDP・ホットリロードは Phase 5） |

---

## 課題全体の再設定と優先実行計画（2026-10-25 監査統合・テスト最優先）

2026-10-25 の監査で判明した課題を既存の R2〜R10・T0〜T7・H0〜H4 と統合し、以下の **Phase 0（テスト系最優先）〜 Phase 5** の順序で進める。

### Phase 0（最優先）: テスト・計測・番人ラチェットの拡充（R0+ / T-Guard）— 完了（2026-10-25）

**結果**: `./gradlew test` は 820 件（失敗 0・無効 6）、`runGameTestServer` は 5 件すべて通過。実装した内容は次のとおり。

- **ラチェット（`ArchitectureTest`、実測値で固定）**: static コールバックフィールド 1（`ClientScreenInteractionManager`）、コマンドクラス内のサーバー状態 1（`ACTIVE_HOLOGRAMS`）、`attrs.put/remove` の直接書き換え 22 箇所（`SessionInput` 3・`Bindings` 9・`StandardDefaultActions` 7・`EnderScreenDelegate` / `TextareaEditor` / `WebHudRenderer` 各 1。`core/dom/node/`・パーサ・直列化の復元・`CustomTagExpander` は新規ノード構築なので除外）、`RenderKeys` 参照 48 行、`DisplayListBuilder` の `Pattern.compile` 8。いずれも Phase 1 / 3 で 0 にする。
- **インクリメンタル計測（`PerformanceBenchmarkTest`、基準値 `incremental-baseline.txt`）**: 1 行ホバー 0.32ms（Phase 2 の目標 `< 1.0ms` は計測上すでに満たす）、無関係な class トグル 0.15ms、子孫セレクタに効く class トグル 16.2ms、1 行追加（差分レイアウト）6.6ms、スクロール 1 段の `repositionLayout` 0.31ms。重いのは子孫セレクタと挿入で、Phase 2 の対象。
- **`showcase.html` の Restyle 増加の原因**: 処理系の退行ではなく、`f34cc9b`（2026-10-24）で文書自体が拡張されたため（要素 99 → 163、ディスプレイリストのゴールデン 556 → 714 行）。`baseline.txt` と `BENCHMARK.md` に記録。
- **失敗再現テスト（Phase 1 の完了条件。直したら `@Disabled` を外す）**:
  1. `ClientActionRoutingTest.testConcurrentDelegatesKeepOwnCallbacks`（複数画面のコールバック上書き → T8 の 1）
  2. `ReactiveStateStoreTest.testApplyI18nAttributesFiresMutationObserver`（→ T8 の 2）
  3. `ReactiveStateStoreTest.testApplyLocalVariablesFiresMutationObserver`（→ T8 の 2）
  4. `ReactiveStateStoreTest.testInputValueSyncFiresMutationObserver`（→ T8 の 2）
  5. `WebMediaManagerRedirectTest.redirectFollowingIsAlwaysInspected`（2 本目の接続が自動追従のまま・307/308 未対応・相対 `Location` は常に遮断 → T8 の 4）
  6. `WebMediaManagerRedirectTest.uniqueLocalIpv6IsRejected`（`isUrlSafe` が IPv6 ユニークローカル `fc00::/7` を素通りする → T8 の 4）
- 有効なまま通る SSRF テスト: ループバック・プライベート・リンクローカル・IPv4 の別表記（10 進・16 進・8 進整数・省略形）・IPv4 射影 IPv6・非 http スキーム・許可ドメインの回り込み。
- 実機仕様書との結びつけ: [TEST_SPEC_SURFACE.md](TEST_SPEC_SURFACE.md) §3 の C-1（共有セッションのサイズ競合）と B-1 / B-2（一人称のはみ出し）は Phase 1 の 5 番、[TEST_SPEC_BLOCKS_ITEMS.md](TEST_SPEC_BLOCKS_ITEMS.md) §3 の A〜C 表と [TEST_SPEC_MEDIA.md](TEST_SPEC_MEDIA.md) の実機手順は、Phase 1 完了時の再実施項目とする。

機能修正やパイプライン改修に入る前に、現状のテストとベンチマークが見落としていた領域をすべて自動テスト・ラチェットとして先に固定する。

1. **進行中の自動テスト・実機仕様書の検証と統合**
   - 作業ツリーに追加された `SurfaceStateTest`・`HologramPickTest`・`AdHocSessionLifecycleTest`・`UIStateLoadTest` および `gametest/WebScreenGameTest` の全件通過を確認する。
   - [TEST_SPEC_SURFACE.md](TEST_SPEC_SURFACE.md)・[TEST_SPEC_BLOCKS_ITEMS.md](TEST_SPEC_BLOCKS_ITEMS.md)・[TEST_SPEC_MEDIA.md](TEST_SPEC_MEDIA.md) の検証項目と既知不具合（§3）を本計画の完了条件に結びつける。
2. **`ArchitectureTest`（番人ラチェット）の強化**
   - **M1 拡張（静的コールバック・サーバー状態の混入防止）**:
     - `ClientScreenInteractionManager.clientEventCallback` のように、画面やセッションごとのコールバックをシングルトンの static フィールドに持つことを禁止し、`SessionContext` への集約を強制する。
     - `EnderFrameCommands` のようなコマンド定義クラス内にサーバー状態のマップ（`ACTIVE_HOLOGRAMS` 等）を直接持つことを禁止し、`server/` の状態管理クラスへの分離を強制する。
   - **DOM 属性の直接書き換え禁止ラチェット（新規）**:
     - `core/dom/node/` およびパーサの初期構築以外で `.attrs.put(` / `.attrs.remove(` を直接呼ぶことを禁止し、必ず `setAttribute` / `removeAttribute`（`MutationObserver` と無効化フックを通る経路）を使わせる。
   - **R6 向けラチェット（新規）**:
     - `RenderKeys` の参照箇所数を上限ラチェットとして固定し、Phase 3（R6）で 0 にするまで増加を禁止する。
     - `DisplayListBuilder` 内の正規表現（`Pattern.compile`）の数を上限ラチェットとして固定する。
3. **`PerformanceBenchmarkTest` のインクリメンタル・マイクロ操作計測の追加**
   - 現行の `PerformanceBenchmarkTest` は毎回新しい `InteractionState`（全要素 dirty）を作って全文書の再スタイルを測り、スクロールも `runLayoutPasses` を呼んでいるため、R5 の目標である「1 箇所の変更で全文書を回さない」効果が見えない。以下の計測を追加し基準値化する:
     - **単一要素の `:hover` 切替時の再スタイル時間**（500 項目リストの 1 行ホバー。Phase 2 での目標 `< 1.0 ms`）
     - **単一要素の class トグル時の再スタイル時間**（無関係な class と子孫セレクタに効く class の比較）
     - **リスト 1 行追加（DOM 1 ノード挿入）時の差分更新時間**
     - **スクロール 1 段での `RenderPipeline.repositionLayout` 実測時間**
     - **2026-10-20 → 10-24 で `showcase.html` の Restyle 基準値が 9.1ms → 16.3ms に増えた原因の特定と解消**
4. **監査で発見した不具合に対する失敗再現テストの先行作成（Test-First）**
   - **複数画面の同時稼働テスト**（`ClientActionRoutingTest` 拡張）: HUD 用の `EnderScreenDelegate` と GUI 画面用の `EnderScreenDelegate` が同時に存在するとき、後から開いた画面が先の画面の `eventCallback` を上書きせず、片方を閉じても他方のイベント配送が壊れないことを確かめる。
   - **宣言的バインディング・入力同期の `MutationObserver` 発火テスト**（`ReactiveStateStoreTest` / `WptDomBehaviorTest` 拡張）: `Bindings.applyI18nAttributes`・`Bindings.applyLocalVariables`・フォーム入力の DOM 同期が `MutationObserver(attributes)` と再スタイル無効化を発火させることを確かめる。
   - **`WebMediaManager` のリダイレクト・SSRF 防御テスト**: 301 / 302 / 303 / 307 / 308 の多段リダイレクト、相対 `Location`、およびリダイレクト先がループバック・プライベート IP を指すケースを遮断できることを確かめる。

### Phase 1: ライフサイクル・入力経路・セキュリティ・Surface 修正（T8 + R2/R3 残課題）

**進捗（2026-10-25）**: 項目 1〜5 すべて完了。
`./gradlew test` は 820 件・失敗 0・無効 0、`runGameTestServer` は 5 件通過。Phase 0 の無効化テスト 6 件はすべて有効化して通る。
- 1: 画面のコールバックは `sessionId` 引きの登録簿（`ClientScreenInteractionManager`）に移し、static の単一フィールドを廃止（ラチェット 1 → 0）。全体向けの `setClientEventCallback` は追加式・`remove` で外す。
- 2: `Bindings`・`SessionInput`・`EnderScreenDelegate`・`TextareaEditor`・`WebHudRenderer`・`StandardDefaultActions` の属性書き込みを `setAttribute` / `removeAttribute` に統一（ラチェット 22 → 0）。
- 3: ホログラム状態を `server/HologramRegistry` へ切り出し（ラチェット 1 → 0。M9 は +1 で 209）。
- 5（R3 残・Surface）:
  - 再同期（`adoptServerTree`）で木を作り直しても、同じ id の要素のスクロール位置・フォーカス・ホバーを `NodeStateSnapshot` で引き継ぐ（`ServerOwnershipAndForwardingTest`）。
  - 互換 `WebSession` の変異が開き直さずに反映されることは `ServerClientLayoutParityTest`（サーバー変異をクライアントへ適用して描画を比較）が固定している。
  - C-1: 世界内の面（ブロック・ホログラム・アイテム）はセッションを読み込んだ解像度を縦横比を保って収めるだけで、面側からセッションを作り直さない。この設計のまま、縦横比の異なる 4 面で共有しても解像度が変わらないことを `SharedSessionSurfaceFitTest` で固定した。残る競合は GUI 画面が同じ `sessionId` を `requestResize` する場合だけで、これは 1 つの DOM が 1 つのレイアウトを持つ設計上の仕様（面ごとのレイアウト分離は持たない）。
  - B-1/B-2: `surface_test_item.json` の一人称 `display` を調整し、実機（`runClient`・PrintWindow）で右手・左手とも視界に収まり正立することを確認。
- 4: `WebMediaManager` は `openCheckedConnection`（301/302/303/307/308・最大 5 ホップ・相対 `Location` の解決・各ホップで `isUrlSafe`）に統一。IPv6 ユニークローカル・マルチキャストを遮断。`yt-dlp` は利用者が置いた実行ファイルのみを使い、ダウンロードと `-U` を廃止（README・NOTICE・CONTRIBUTING を更新）。

Phase 0 で追加したテストとラチェットを通す形で、状態管理のバグ・セキュリティ懸念・R2/R3/Surface の残課題を片付ける。

1. **イベントコールバックのセッション単位化（T8）**
   - `EnderScreenDelegate` コンストラクタが `ClientScreenInteractionManager.setClientEventCallback` でグローバルな static フィールドを上書きしている構造を廃止し、`SessionContext`（または `sessionId` ごとの登録簿）に持たせる。
2. **DOM 属性変更の正規化（T8 / R1 補完）**
   - `Bindings.applyI18nAttributes`・`Bindings.applyLocalVariables` および `EnderScreenDelegate.syncInputValueToDomNodes` の `vNode.attrs.put(...)` 直書きをやめ、DOM API（または無効化フックと `MutationObserver` を通す内部メソッド）に統一する。
3. **コマンドクラスからのサーバー状態の分離（T8）**
   - 1,899 行に肥大化した `EnderFrameCommands` から `ACTIVE_HOLOGRAMS` とホログラム管理ロジックを `server/` の管理クラスへ切り出す。
4. **`WebMediaManager` のネットワーク・外部プロセス安全化（T8）**
   - リダイレクト処理を 301 / 302 / 303 / 307 / 308 対応・最大 5 ホップ・`URI.resolve` による相対 URL 解決に改め、各ホップで `isUrlSafe` を検査する。
   - 外部バイナリ `yt-dlp` の無検証ダウンロードと毎回の自己更新（`yt-dlp -U`）を廃止し、設定による明示的な有効化と SHA-256 検証（または事前導入バイナリの参照のみ）に限定する。
5. **R3 残課題と Surface 既知不具合（[TEST_SPEC_SURFACE.md](TEST_SPEC_SURFACE.md) §3）の解消**
   - 互換モード `WebSession` の変異が画面を開き直さなくてもライブ反映される経路の確認と、フル再同期フォールバック時の節状態（スクロール・フォーカス・選択範囲）の引き継ぎ。
   - **C-1（共有セッションのサイズ競合）**: 同じ `sessionId` をアスペクト比の異なるブロック面・ホログラム・手持ちアイテムで共有した際、後から描画した面のサイズで先方のレイアウトが潰れないよう、論理ビューポートまたは面ごとのレイアウトキャッシュを分離する。
   - **B-1 / B-2（一人称視点のアイテム Surface のはみ出し）**: `surface_test_item.json` の `firstperson_righthand` / `lefthand` の display transform 調整とクリッピングの確認。

### Phase 2: インクリメンタル・パイプライン完遂とスタイル省メモリ化（R5 + R4 残課題）

**進捗（2026-10-25）**: 項目 1〜3 を実装（台帳の 18 行を除く）。
`./gradlew test` は 866 件・失敗 0（`PerformanceBenchmarkTest` だけは全体実行の CPU 競合で落ちることがあり、単独実行では通る。HEAD でも同じ）、`runGameTestServer` は 5 件通過。
- 1（実装済み）: `InvalidationSets` を `InteractionState` に接続（`bindStyleSheet`）。状態（`:hover` など）・属性（class は変化した class だけ、id、その他）・子の増減（挿入/削除位置）を `invalidateState` / `invalidateAttribute` / `invalidateChildList` が `Relation` に従って汚す（自身・子孫・後続/先行の兄弟・祖先・文書全体）。`HtmlDocument.attributeChanged` / `childListChanged` が DOM の変異から呼ぶ。祖先の状態を読む規則（`.a:hover .b`）は要素の `subtreeDirty` で表し、再スタイルは `FORCE_SUBTREE` として下まで続ける。理由を絞らない `markDirty` は従来どおり安全側（子孫・祖先も汚す）。`:root` は `forRootChange` に分け、文書直下の子の増減でだけ効く（兄弟の増減で兄弟を汚さない）。要素ごとの `LAYOUT_DIRTY` / `PAINT_DIRTY` ビットへの分割は未実施。
- 2（一部）: 継承しないプロパティだけが変わった要素は子孫を再スタイルしない（`propagationOf`。`inherit` を宣言値に使う規則 `StyleSheet.usesInheritKeyword()` か style 属性があるときだけ子孫が追従）。`ComputedStyle` の参照キャッシュ（`ConcurrentHashMap` × 4）を最初の参照まで確保しない。`StyleResolver` のインライン宣言用 `LinkedHashMap` × 2 は style 属性があるときだけ確保。`StyleResolver.resolveDeclarations` の `uaValues` / `userValues` / `layerBase` のコピー（3 個）は未削減。確保量・GC の測定は未実施。
- 検証: `InvalidationPipelineTest`（乱数の変異列 20 系列 × 2 経路を、同じ列を毎回作り直した文書のディスプレイリストと比較。class・hover・style 属性・属性・挿入・削除と、子孫/子/隣接/一般兄弟/`:has`/`:nth-child`/`:first-child` 規則、`inherit` 規則あり/なしの 2 つの CSS）、範囲を絞った汚れ方の単体テスト 5 件。実装中に、強制の伝播が 1 段で止まる誤り（祖先の `:hover` を読む規則で孫が古いまま）をこのテストが捕まえ、`FORCE_NONE/SELF/SUBTREE` に直した。
- 3a 再レイアウト境界と `contain`（実装済み）:
  - `contain: layout | paint` は BFC を作り、絶対配置・固定配置の包含ブロックになる。`contain: size`（`strict` 含む）と `container-type: size` は中身の高さ・幅を箱の寸法に寄与させず、ベースラインも境界ボックスの端から合成する（`BlockLayoutEngine.synthesizesBaseline`）。
  - 再レイアウト境界（`LayoutBox.isRelayoutBoundary()`）: BFC を作るブロックレベルの箱で、size containment を持つか、幅と高さが確定した（`auto`・`%`・内在キーワードでない）スクロールコンテナ。境界の中だけが変わり、境界の寸法・マージンが前回と同じなら、祖先の measure は無効にならない（`invalidateAncestors`）。使い回された祖先から境界まで降りて、中だけを前回と同じ制約で測り直す（`remeasureBoundaries`）。
  - 配置の使い回し: measure を使い回した箱は、親から渡る原点と包含ブロックの寸法が前回の layout と同じなら layout を飛ばす（`LayoutBox.canKeepPlacement`、`layoutsSkipped()` で計測）。外の状態に位置が依る箱（絶対・固定・相対・sticky・スクロールコンテナ・float・表）を含む部分木と、同じ BFC に float がある箱は対象外。入力が同じ（原点が同じで、配置に外寸が効かない部分木では外寸が変わっても同じ扱い）で子孫の変化のために置き直すだけのときは、子孫の配置を捨てず、子がそれぞれ飛ばすかを決める。
  - `content-visibility: hidden` は従来どおり中身を測らない。`auto`（画面外の中身を飛ばす）は未対応。
  - 見つけて直した不具合（`IncrementalLayoutFuzzTest` が捕まえた）: (1) size containment の箱で、中身を測らないのに前回の layout が作った配置の状態（`engineState`）が残り、子の増減が配置に反映されなかった。(2) インラインブロックのベースラインが、測る時点では前回の layout の残りを読んでいて、作り直しと食い違った。(3) 入力が同じでも置き直すたびに子孫の配置を全部捨てていて、配置を飛ばす近道が効かなかった。
- 3b 描画だけの変化の近道: `PropertyRegistry` の無効化種別（`Invalidation.PAINT`）で、`opacity`・`transform`・色・影・角丸・輪郭線だけの変化は再レイアウトも位置の決め直しも要求しない（`StyleAnimatorTest.opacityTransformAndColorChangesNeedNoLayout`）。位置指定の inset だけの変化は `repositionLayout` で足りる。スクロール位置は `ScrollState` が `float`（丸めは描画の 1 箇所）。
- 3c `StyleAnimator`（`core/css`。実装済み）: `PropertyRegistry` で `animatable` と宣言されたプロパティのうち、描画項目の補間（`CssAnimator`）が担う `opacity`・`transform`・`translate`・`rotate`・`scale`・`color`・`background-color` を除く全てを、再スタイルで動かす。
  - トランジション（`transition` の複数指定・各ロングハンドの繰り返し・遅延・イージング）は、再スタイルの前後の目標値の差で始まり、動いている最中に目標が変われば今の値から取り直す。継承しているだけの変化は子の分を始めない。`@keyframes` は `from` / `to` / 途中のストップ・暗黙の端（要素自身の値）・`animation-iteration-count` / `direction` / `fill-mode` / `delay` に対応する。
  - 動く値は「アニメーションの起源」として生の宣言に重ねて算出し直す（`RenderPipeline.animated`）。レイアウトに効くプロパティが動けば再スタイルの比較が再レイアウトを、色や角丸のような描画だけに効くものは描き直しだけを要求する。時刻は `InteractionState.setAnimationTime` で渡す（テストは決定的）。`SessionContext.flushInvalidation` が実時間で進め、動いている間は次のフレームを予約し続ける。
  - 値の補間は `ValueInterpolator`（色は sRGB、長さは同じ単位なら数値のまま、違えば `calc()`、`thin` / `medium` / `thick` は px、補間できない組は離散）。`animation` / `transition` の読み取りは `AnimationParser` に出し、`DisplayListBuilder` も同じものを使う。進行度の計算は `AnimationTiming`（`core/paint/model`）に一本化し、`CssAnimator` もこれを呼ぶ。
  - 未対応: `transition-behavior`（離散的なプロパティのトランジション）・`@starting-style`・`steps()` のイージング・`animation` の複数指定（先頭のみ）・疑似要素のアニメーション。
- 3d CSSOM View（実装済み）: `Element` の `getBoundingClientRect` / `getClientRects` / `offset*` / `client*` / `scroll*` / `scrollTo` / `scrollBy` / `scrollIntoView` / `offsetParent`、`Document.getComputedStyle`（読み取り専用の解決値）・`matchMedia` / `MediaQueryList`・ビューポートの寸法、`ResizeObserver`・`IntersectionObserver`。レイアウトの型は `core/dom/LayoutQuery`（値だけの `BoxMetrics`）の背後に置き、実装は `common/BoxLayoutQuery`。`CssomViewTest` が固定する（WPT の CSSOM View ケースの採用は未実施）。
- 検証（追加分）: `IncrementalLayoutFuzzTest`（ブロック・flex・grid・float・絶対・相対・sticky・スクロールコンテナ・`contain`・`content-visibility`・表・インラインを混ぜた文書に、class・テキスト・style・挿入・削除・スクロールの乱数列 150 系列 × 2 経路 × 2 設定を当て、毎回作り直したディスプレイリストと一致）、`RelayoutBoundaryTest`（境界の外の 200 段落を測り直さず置き直しも飛ばすことを回数で、結果は作り直しと一致で確認）、`StyleAnimatorTest`（補間・指定の読み取り・進行度・各時刻の描画が同じ値を静的に書いた文書と一致・描画だけの補間が再レイアウトを要求しないこと）、`StyleAnimatorFuzzTest`（境界の中の要素や無限の `@keyframes` を含む文書に、class の付け外しと時刻の進みの乱数列 40 系列を当て、各フレームが作り直しと一致）、`SessionAnimationFlowTest`（`flushInvalidation` を通した実時間の進み方と終了）。
- 計測（`incremental-baseline.txt`。この機械では単独実行でも 3 割ほど揺れる）: hover 1 行 0.3〜0.5ms（目標 `< 1.0ms` を満たす）、無関係な class 0.2ms、子孫セレクタに効く class 17〜31ms（500 行が実際に変わる仕事）、**1 行追加 6.3ms → 3.2〜4.4ms**（`RelayoutBoundaryTest.insertingARowSkipsLayoutOfTheRowsBeforeIt` は 300 行のリストの途中への挿入で、挿入位置より前の 250 行の layout を飛ばすことを回数で固定。後ろの行は位置がずれるので置き直す。`< 1.0ms` には届かない）、スクロール 1 段 0.3〜0.8ms。
- 残り（判断待ち）:
  - 台帳 R5 のスクロールバー 10 行（`flex-column-overflow` / `overflow-size`）: ブラウザ（Windows の Chrome）は `overflow: auto | scroll` の箱に 15px の古典的なスクロールバーの溝を確保して `clientWidth` と子の `%` 幅を縮めるが、エンジンのスクロールバーは中身に重ねる 4px の細いバーで、溝を取らない。揃えると既存の画面（`ua.css` の `textarea` / `select`、`config-form.css`、`linux_desktop.html` など）のレイアウトとスクロールバーの見た目・ヒットテストが全て変わるため、方針の決定を待つ。マージンの相殺と `scrollHeight` の 1 行（`margin-collapse-bottom`）は解消済み。
  - 台帳 R4 の `::marker` 8 行（`list-basic` / `list-nested` / `list-ordered`）: ブラウザの項目の `scrollHeight` が項目の高さより 4px 大きい（外側マーカーの箱が行の外に出る）。マーカーの書体の指標とブラウザの実測が要るため、Phase 4（R7 の `ch` / `ex`・書体指標）と一緒に扱う。

1. **`InvalidationSets` の実接続と要素ごとの dirty ビット（R4 残 + R5）**
   - `InteractionState.propagateDirtyToAncestors` が親へ無条件に dirty を伝播している現状を改め、`Document.sheet.invalidation`（`forClass` / `forId` / `forAttribute` / `forState` / `forStructureChange`）から汚すべき要素だけを特定して `STYLE_DIRTY` を立てる。
   - 要素・ボックスごとに `STYLE_DIRTY` / `INTRINSIC_DIRTY` / `LAYOUT_DIRTY` / `PAINT_DIRTY` を分ける。
2. **`StyleResolver` / `ComputedStyle` のアロケーション削減と継承判定の厳密化（R5）**
   - `RenderPipeline.restyleBoxRecursive` が `oldStyle.asMap().equals(newStyle.asMap())` で全マップを比較し、1 つでも違えば `childForceDirty = true` にして子孫をすべて再スタイルしている問題を直す。`background-color` などの**非継承プロパティだけが変わったときは子孫を再スタイルしない**（`PropertyRegistry.isInherited` と `--*` 変数の変化だけを見る）。
   - `ComputedStyle` 1 個あたり `LinkedHashMap` 1 個 + `ConcurrentHashMap` 4 個を即時確保している構造と、`StyleResolver.resolveDeclarations` が要素ごとに 5 個の `LinkedHashMap` を作っている構造を遅延確保・軽量構造に置き換え、GC 負荷を下げる。
3. **レイアウト境界・`transform`/`opacity` 高速経路・`CssAnimator` 汎用化・CSSOM View（R5）**
   - `contain: layout | size | strict | content`・`content-visibility`・確定サイズのスクロールコンテナを再レイアウト境界（relayout boundary）にする。
   - `transform` / `opacity` だけの変化はレイアウトを通さず、スクロール位置を `float` にする。
   - `CssAnimator` を 4 チャネル固定から `PropertyRegistry` 駆動へ広げる。
   - CSSOM / CSSOM View API を公開し、[FIDELITY.md](FIDELITY.md) の R5 の 11 行と R4 の 8 行（`::marker` の `scrollHeight`）を解消する。

### Phase 3: 型付き `DisplayItem`・SDF/FBO 合成・`transform` 厳密化（R6 + 旧 W12）

**進捗（2026-10-26）**: 項目 1・2 を実装し、肥大していた 2 クラスを分けた。項目 3 は影の穴抜き・内側の影のガウス化・W12 のスクロールとシザーまで実装（3a）。FBO レイヤー合成・楕円の角・角丸の破線・W12 の残りは未着手。
`./gradlew test` は 897 件・失敗 0、`runGameTestServer` は 5 件通過。ラチェットは `RenderKeys` 参照 48 → 0、`DisplayListBuilder` の `Pattern.compile` 8 → 0。
- 1（実装済み）: `RenderKeys` を撤去し、描画・ヒットテスト・アニメーションの付帯情報を型付きの `RenderHints`（`core/paint/model`）にして `RenderMeta.hints()` に持たせた（`DisplayItem.hints()` で読む）。持つのは親の錨の id・ツールチップ・スクロール範囲・影のぼかし半径・グラデーションの外縁の辺・シェーダーのグラデーション（`ShaderGradient`）・`pointer-events: none`・トランジションの遅延/イージング/対象。`DisplayItem.events` は DOM 由来の属性（`onclick`・`data-*`・書体）だけになり、描画側が毎フレーム `split(",")` と `parseFloat` で読み戻すことはなくなった。`DisplayListBuilder` は部品の群ごとに `ItemProps`（属性 + `RenderHints`）を持ち、`addElement` で `events` と `meta.hints` に振り分ける。読まれていなかった `mc-origin`・`mc-font-h`・`mc-scroll-current` は撤去。`HitTestIndex` の `title` 判定（旧 `mc-title`）は `hints().title()` に移した。
  - 見つかった不具合: `LinuxDesktopScreenInputTest` が文字列 `"mc-parent"` で親を辿っていて、キー名の取り違えで常に null を読んでいた（型付きにして直った）。
  - 見送り: `DisplayListBuilder` / `DisplayItemDrawer`（各 1,700 行超）の責務ごとの分割は、今回は型の移し替えを優先して未実施。
- 2a（実装済み）: `transform` と個別プロパティ `translate` / `rotate` / `scale` を `core/paint/Transforms` が `CssSyntax` のトークン列から宣言順どおりに 2D の行列へ合成し（`translate` → `rotate` → `scale` → `transform`）、描画項目が運べる平行移動・回転・拡縮に分解する。`matrix()` / `matrix3d()` の 2D 成分・`skew*`・`rotate3d` の z 軸・`em` / `rem` / `calc()` の長さ・`deg` / `rad` / `grad` / `turn` に対応。以前は固定順（translate → rotate → scale）で、`scale(2) translate(10px)` の平行移動が拡大されなかった。せん断と 3D の奥行きは描かない（`README` の "Not supported" を更新）。`transition` / `animation` の時間の読み取りと `transform-origin` の語分けの正規表現も、`CssSyntax` のトークンで読むよう置き換えた。検証は `TransformsTest`・`PaintModelTest`。
- 2b（実装済み）: すべての線形・放射・円錐グラデーション（`repeating-*`・補間のヒント・`in <色空間>` を含む）を `WebSdfShader` で描き、`Gradients.strips` / `GradientStrip` / `GradientSpec` / `RenderHints.edgeMask` / `RoundedRectRasterizer` の帯の経路を撤去した（`Gradients.shaderGradients`）。停止点の間（区間）ごとに同じ矩形へ重ねて描き、各区間は自分の t の範囲の外を捨てる（`ShaderGradient.LOWER_CLOSED` / `UPPER_CLOSED` を `GradA.w` に畳む。境目は 0.001 だけ重ねて抜けを防ぐ）。同じ位置の停止点（色の切り替わり）は区間を描かず、手前と先の端の色を単色の区間で塗る。繰り返しはシェーダーが位置 `u` を周期に畳む（頂点に `GradRepeat` を追加し、`EdgeMask` を外した。頂点は 156 バイト）。ヒントは区間の t の指数（`GradB.w`）、円錐は `atan` で求めた周の角。補間空間の指定は区間を 32 に刻み、その色空間の色を sRGB の補間でつなぐ近似（色が急に曲がる `oklch` の赤→緑は端で最大 20 ほどずれる）。
  - 検証: `ShaderGradientTest`（シェーダーの式を `ShaderGradientEmulator` で CPU に再現し、停止点を直接引く基準の `colorAt` と画素ごとに比較。斜め・位置つき・切り替わり・透明・繰り返し・ヒント・色空間・放射・円錐）、`PaintModelTest`、ゴールデン 2 件（区間の注記 `shader=[…]` が加わった）、実機（`runClient`・PrintWindow）で斜め 3 色・位置つき 4 色・切り替わり・放射・繰り返し（以前は帯の経路で粗かった）・縞・円錐・ヒント・`oklab` / `oklch` を目視（継ぎ目・段なし）。
  - LIMITATION: シェーダーが使えない環境（`WebSdfShader.isAvailable()` が偽）ではグラデーションは描かれない（以前は帯の矩形で近似していた）。
- 責務ごとの分割（実装済み）: `DisplayListBuilder`（1,873 → 279 行）を `PaintOrder`（スタッキングコンテキストの順序・クリップ・スクロールバー）・`BoxAnimation`（transform / transition / animation）・`BoxDecorationPainter`（背景・枠線・影・輪郭線・インライン断片）・`ControlPainter`（フォーム部品・メディア・シーン・フィールドセット）・`PaintSupport`（状態を持たない道具）・`ItemProps`（属性 + `RenderHints`）に、`DisplayItemDrawer`（1,723 → 584 行）を `BackgroundRenderer`・`ControlElementRenderer`・`MediaElementRenderer`・`TextElementRenderer` に分けた。動作は変えていない（全テストと実機の目視で確認）。M9 の上限は 231。`ArchitectureTest` の `Pattern.compile` の上限は `DisplayListBuilder` とその部品の合計。
- 3a（実装済み・2026-10-26）影と W12 の一部:
  - ぼかした外側の影は箱の角丸矩形を「穴」として抜く（`RenderHints.ShadowShape`・シェーダーの `SHADOW_HOLE`）ので、透明・半透明の背景の箱に影が透けない。内側の影は 4 本の帯をやめ、パディングボックスの 1 枚を「穴の外」で塗る（`SHADOW_INSET`）。穴は角丸（内側の角丸から広がりだけ縮める）で、ぼかしは外側と同じガウス近似。ぼかしのない外側の影は従来の 1 層のまま（箱の下全体）。実機（`runClient`・PrintWindow）で、透明な箱の赤い光彩・角丸の内側の影・オフセット/広がり付きの内側の影・回転した親の中の影を目視。
  - W12 のスクロール: ホイールの宛先（`SessionContext.handleScrollLocally`）を `HitTestIndex.scrollContainersAt` に移し、祖先の変換の逆行列を宣言順に当て、祖先のクリップに隠れた箱を除く（以前は自身の変換だけで、クリップも見なかった）。`HitTestAndOcclusionTest` に拡大した親の中の箱と `overflow: hidden` に隠れた箱の 2 件。
  - W12 のシザー: `<mc-scene>` と `item:` / `entity:` の画像は、祖先のクリップが部分的なとき（`enableClipScissorFor2D`）GUI・HUD でシザーを当てる（以前はクリップを無視して全部描いた）。世界内の面ではシザーが使えないので従来どおり。
  - 見つけて直した不具合: `transform-origin` の長さ（`0 0`・`20px 10px`・`em` / `rem`）を読めず中心（50%）になっていた（`parseOriginProp`。`PaintModelTest.testTransformOriginReadsLengthsAndKeywords`）。
  - 検証: `./gradlew test` は 897 件・失敗 0、`runGameTestServer` 5 件通過、`./gradlew benchmark` は許容幅内（hover 0.31ms・1 行追加 3.6ms・スクロール 0.72ms。`showcase.html` Restyle 18.3ms は基準 16.3 の許容幅内）。
- 残り（項目 3）: FBO レイヤー合成（グループ `opacity`・`filter`・`backdrop-filter`・`mix-blend-mode`・`clip-path`・`mask`）、回転した要素の角丸クリップ、楕円の角・角丸の破線、旧 W12 の残り（スクロール端にかかる文字行の世界内面での部分クリップ・複数 Surface の半透明順序 A-14）。ぼかした影のガウス化は 3a で完了。いずれも GL の状態（FBO・クリップ・合成）の設計を伴うので、別の段として扱う。

- **残りをいつやるか（実施時期の方針）**: 順序は「Phase 4 → Phase 3b（FBO 合成の段）→ Phase 5」。Phase 3 の残りは Phase 4 の完了後・Phase 5 の着手前に、独立した段（Phase 3b）として行う。
  - **理由**: (1) 3b は GL の状態設計（FBO プール・ステンシル・合成順）を伴う大きな段で、他の段と混ぜると退行の原因を切り分けられない。(2) Phase 4 の文字アトラス（VRAM 最適化・LRU）が FBO プールと VRAM・テクスチャ管理を取り合うので、先に文字側を固めてから FBO の予算を決める。(3) Phase 5 の SVG / Canvas 2D と DevTools は、`clip-path` / `mask` / `filter` / `mix-blend-mode` を前提にするので、その前に合成基盤が要る。
  - **3b の中の順序と着手条件**: ①楕円の角と角丸に沿う dashed / dotted（`Corners` を `int` から楕円半径へ変える型変更が先。SDF だけで済み、FBO 不要なので最初に片付ける）→ ②FBO プールとグループ `opacity`・`filter`・`mix-blend-mode`・`isolation` → ③`clip-path`・`mask`・回転した角丸クリップ（ステンシル / マスク FBO）→ ④`backdrop-filter`。①は Phase 4 と並行してもよい。②以降の着手条件は、Phase 4 のアトラス改修が済み VRAM の予算が決まっていること。
  - **旧 W12 の残り**: A-14（複数 Surface の半透明順序。世界内の面のバッチ描画で文字・画像が SDF の Quad より後になる問題）は ②の FBO / レイヤー順序の設計と同じ根（描画順の管理）なので 3b ②と一緒にやる。世界内の面での文字行の部分クリップは、シザーが使えないため ③のマスク / ステンシルが入ってから解く。
  - **着手条件の実装（2026-10-26）**: アトラスの VRAM の実測と FBO の予算を入れた。`AtlasFootprint`（置き場ごとの字形数・面積 → テクスチャ枚数と VRAM の見積もり。256px 四方 × 1 バイト、詰め込みの余裕 1.3 倍）を `OutlineTextRenderer` が置き場ごとに持ち（`atlasBytes()` / `glyphCount()`）、`VramBudget`（`core/paint/model`）が配分を決める: 全体 192 MiB のうち文字の置き場に最低 32 MiB を予約し、残りを FBO プールへ（画面サイズの RGBA8 + D24S8 のレイヤー 4〜16 枚分に収める）。置き場が 64 MiB を超えたら保護の外の置き場を捨てる（`AtlasCache.evictUnprotected`）。FBO が先に削られることはない。実測（`AtlasFootprintTest`、Arial の ASCII + 漢字 400 字 × 13/16/26px × 端数 4 段 = 1,128 字形）: 字形 13.6 万 px、見積もり 3 枚（192 KiB）で、高さ順の棚詰めの 3 枚と一致。予算の範囲に十分収まる（日本語を大量に使っても数 MiB）ので、FBO を競合で削る必要は当面ない。
- **3b の実装（2026-10-26）**: ①楕円の角と角丸に沿う dashed / dotted（`Corners` の楕円半径・`BorderPath`・SDF）、②FBO レイヤー合成（`LayerSpec`・`LayerPool`・`LayerCompositor`。グループ `opacity`・`filter`（`FilterOp`）・`mix-blend-mode`（`BlendMode`）・`isolation`）、③`clip-path`・`mask`・回転した角丸クリップ（`ClipShape`・マスク用 FBO）、④`backdrop-filter`。レイヤーは画面の面（GUI・HUD）だけで、世界の中の面は従来どおり部品ごとの不透明度。検証: `EllipticalCornersTest`・`LayerDisplayListTest`・`ClipMaskDisplayListTest`・`FilterAndBlendTest` を追加。`./gradlew test` は 1,029 件・失敗 0（`ArchitectureTest` の M9 は 244 → 251 に引き上げ）、`runGameTestServer` 5 件通過、`./gradlew benchmark`（厳密）は許容幅内（showcase Restyle 10.9 / Relayout 16.7 / DisplayList 4.6 / ScrollStep 19.2 ms、hover 0.26ms・1 行追加 3.0ms・スクロール 0.52ms。FBO を使わない文書は基準どおり）。実機（`runClient`・PrintWindow、1600×900）で効果ごとの見本ページを目視: グループ `opacity`（重なった子が透けず、部品ごとの比較用の箱では透ける）、`filter`（`blur`・`grayscale`・`hue-rotate`・`invert`）、`isolation` + `multiply`、楕円の角、角丸に沿う dashed / dotted、`clip-path`（`circle` は寸法どおり上下が平らに切れる・`polygon`）、`mask-image`、`backdrop-filter: blur`（背後の境目だけがぼける）、回転した角丸の `overflow: hidden` がいずれも期待どおり。不具合なし。未実施: A-14（複数 Surface の半透明順序）、世界内の面での文字行の部分クリップ（→ 下の「3b の残りの実装」で完了）。
- **3b の残りの実装（2026-10-26）**:
  - **A-14（世界内の面の描画順）**: バッチ中に積んだ文字・画像・3D の範囲を `QuadClip.Pending` に覚え（変形のある要素は「どこでも」）、そこに重なる SDF の Quad（影のぼかしの分 48 を広げて判定）が来たら、先に `WebSdfShader.flushPass()` と `endBatch()` で積んだ分を描き切ってから続ける。重ならない要素どうしはこれまでどおり 1 回の draw にまとまる。以前は SDF が先、文字が後に描かれ、半透明の要素の下の文字が奥行きで隠れた。
  - **世界内の面の文字行の部分クリップ**: シザーが使えない面で、祖先のクリップ（`overflow` / スクロール端）にかかる字を `OutlineTextRenderer.setClip` で板ごと切る。字形の板を単位行列で受けて `QuadClip.clip` で矩形に切り、テクスチャ座標を位置に比例して詰め直し、姿勢を掛けて出す（`ClippingConsumer`）。テキストエリアの行も同じ。LIMITATION: ビットマップ書体の経路・下線 / 取り消し線・キャレットは切らない。
  - 検証: `WorldSurfaceOrderTest`（板の切り取りの UV・頂点順・全外・重なりの判定）7 件を追加。`./gradlew test` 1,036 件・失敗 0（M9 を 251 → 252）、`runGameTestServer` 5 件通過、`./gradlew benchmark` は許容幅内（showcase Restyle 11.7 / Relayout 15.8 / DisplayList 5.0 / ScrollStep 19.5 ms、hover 0.27ms・1 行追加 2.8ms・スクロール 0.52ms）。実機（`runClient`・PrintWindow、1600×900）で世界内のブロック面を目視: 半透明の赤・緑の箱の下の文字が隠れず見える、`overflow: hidden` の上端にかかる行が端で切れ、下端の外の行は出ない。
- **完了条件**: 効果ごとの項目がディスプレイリストのゴールデンに出ること、`runClient` の見本ページの目視（`guidebook.md` と並べる）、ベンチで FBO を使わない文書の Restyle・DisplayList が基準の許容幅内であること（FBO は効果があるときだけ確保）。

### Phase 4: フォント VRAM 最適化・`@font-face` 統合・国際化・編集（R7 + R4 残課題）

**進捗（2026-10-26）**: 項目 1・2 と項目 3 の一部（UAX #14・UAX #9 の並べ替え・`direction`・Selection / Range API）を実装した。合字・`line-height: normal` の代替書体の指標・`writing-mode`・`contenteditable`・IME は未着手。
`./gradlew test` は 973 件・失敗 0、`./gradlew benchmark` は通過（HEAD と同じマシン・同じ条件で 3 往復比較し、有意な差なし。下の「検証」）。`::marker` の台帳 8 行は書体の指標が要る残りとして据え置き。

- 1（実装済み）フォントアトラス:
  - `OutlineTextRenderer` の置き場の鍵から `subpixel` と `lightText` を外し、同じサイズの 8 変種を **1 つの `FontSet`** に載せた（`GlyphKey`）。以前は 1 サイズ最大 8 枚の別テクスチャで、どれも少ししか埋まらなかった。Minecraft の `FontSet` が字形を引く表（`CodepointMap`）は 0〜0x10FFFF しか持てないので、字形の番号はコードポイントのビット詰めではなく、置き場ごとの通し番号 × 8 + 変種にした。
  - 焼くピクセルサイズを量子化（`AtlasSizing`。32px まで 1/4px、64px まで 1/2px、それ以上 1px。誤差 1% 未満で、普通の静止した大きさは変わらない）。13px の文字を `scale(1 → 3)` で動かすと、毎フレーム違う大きさの置き場が 667 フレームで 667 枚できていたのが約 90 枚に収まる。
  - 置き場の破棄を `AtlasCache` に分けた: 直近 2 秒以内に使った置き場は捨てない（描画バッファがまだ参照しているかもしれない）、個数の目安 64 を超えたら保護の外の古い順、30 秒使われなければ個数によらず捨てる。以前は個数 256 の LRU だけだった。
  - `LoadedFace` の FreeType ハンドルを閉じて開き直せるようにした（`release()` / `releaseIdle()` / `releaseAll()`。測った寸法・送り幅・字形番号の表は残るので、閉じても何も失わない）。描画側が 60 秒使われない面を閉じる。資源の再読み込みでも閉じる。
  - 書体の一覧の走査を `SystemFonts.warmUpAsync()`（クライアント起動時に裏のスレッドで 1 回）にまとめた（以前は `EnderFrameClientInit` にインラインのスレッドがあった）。
- 2（実装済み）`@font-face` と `ch` / `ex`:
  - `@font-face` の TTF / OTF を `WebFonts` に登録し、OS の書体と同じ `SystemFonts.Face`（メモリから開く `FT_New_Memory_Face`）として照合に載せた。測定（`OutlineFontFace`）も描画（`OutlineTextRenderer`）も OS の書体と同じ経路で、同じ書体ファイルなら OS によらず同じ寸法・同じ字形になる（`font-weight` / `font-style` 記述子も読む）。同名の OS の書体より優先し、資源の再読み込みで読み直す。TTF / OTF でない `src`（フォントの JSON など）と読めなかったファイルは従来のビットマップ経路。
  - `ch`（「0」の送り幅）と `ex`（x ハイト）を要素の書体の指標から決める（`GlyphUnits`）。書体の解決は下の層が持つので `GlyphUnits.install` で渡す（クライアントは起動時に `McFontResolver`）。渡されない文脈（ヘッドレスの単体テスト）だけ 0.5em。書体を引くのは `ch` / `ex` が実際に使われたときだけ。
- 3（一部）国際化:
  - 折り返しを **UAX #14** にした（`LineBreaker`）。LB7〜LB31 のうち LB7 / 8 / 8a / 9 / 11〜19 / 21 / 23〜25 / 28〜30a / 31。以前は空白・ハイフン・和文だけを候補にしていたので、`/` の後ろ・`—` の前後などで折り返せなかった。`(3-5 days)` の `3-5` を割らなくなった（Chrome と同じ。ディスプレイリストのゴールデンの 2 か所が変わった: `"(3-" "5 "` → `"(3-5 "`、`"1-" "4 "` → `"1-4 "`）。`word-break: keep-all` は表意文字・ハングルを英字として扱い、`break-all` は英字・数字も表意文字として扱う。LIMITATION: タイ語などの辞書による分かち書き、ハングル音節の連結（LB26 / 27）、東アジア幅による LB30 の括弧の区別。
  - **`direction`**: `dir` 属性を UA スタイルシートで `direction` に写し、ブロックの子の over-constrained な配置（CSS 2.2 §10.3.3。rtl は左のマージンが余りを受けて右に寄る）と `text-align: start / end`（`TextAlign.physical`）を向きで解く。台帳の `pseudo-lang-dir-defined#e.x` が解消した。
  - **UAX #9**: `BidiText` が 1 つの文字列を視覚順に並べ替える（`OutlineTextRenderer` が描く前に通す。括弧は反転）。LIMITATION: アラビア文字の連結形（GSUB による整形）は選ばない。複数のインライン要素にまたがる行の並べ替えはしない。キャレットと選択の位置は論理順の測定のまま。
  - **Selection / Range API**: `Document.createRange()` / `getSelection()`。`Range`（境界点の比較・`toString`・`cloneContents` / `extractContents` / `deleteContents` / `insertNode` / `surroundContents`・節の挿入・削除・文字の変更に追従する生きた範囲）と `Selection`（範囲は 1 つ。anchor / focus の向き・`extend` / `setBaseAndExtent` / `selectAllChildren` / `deleteFromDocument`・`selectionchange`）。描画の選択（`TextSelection`）とはまだつないでいない。
  - **スクロール範囲**: 行の高さより高い書体の内容領域（ベースライン + descent）がスクロール範囲（`scrollHeight`）に入る。台帳の `forced-line-breaks#box.scrollHeight` の原因はこれ（Chrome 側の書体は日本語環境の Noto Sans JP で内容領域が 24px、行が 20px）。忠実度コーパスの測定は書体を持たない `FontResolver.FALLBACK` なので行は残る。システムの書体で測る `DirectionLayoutTest` で固定した。
- 未着手: 合字（GSUB の `liga`）、`line-height: normal` での代替書体の指標、`writing-mode`、`contenteditable`・IME の composition、`::marker` の台帳 8 行（書体の指標とブラウザの実測が要る）。（アトラスの VRAM 実測と FBO の予算配分 `AtlasFootprint` / `VramBudget` は 2026-10-26 に Phase 3b の着手条件として完了。）
- 検証:
  - 新規テスト（76 件）: `AtlasCacheTest`（置き場の保護・期限・個数・字形番号が `CodepointMap` の範囲に収まること・量子化）、`FontLifecycleTest`（ハンドルの解放と開き直しで同じ字形・`@font-face` が OS の書体と同じ幅・同名の上書きと復元・読めないデータの拒否）、`LineBreakerTest`（UAX #14 の規則と Chrome の挙動、結合・ZWJ・地域指示記号・禁則・`keep-all` / `break-all`）、`BidiTextTest`、`GlyphUnitsTest`、`DirectionLayoutTest`（rtl の配置・`dir` 属性・start / end・行の下にはみ出す文字のスクロール範囲）、`RangeSelectionTest`（範囲の追従・取り出し・Selection）。
  - 実機（`runClient`、showcase を 1600×900）で、共有アトラスの描画を目視した。**単体テストでは捕まらない不具合を 2 つ直した**: (1) 字形の番号を `cp | 変種 << 21` と詰めたら `CodepointMap` の範囲外で描画スレッドが落ちた、(2) `FontSet` は対応字（空白）を引いて null を返す提供元を外すので、番号の割り当て前の問い合わせに null を返したら全字が豆腐になった。どちらも `GlyphKey` と `FaceGlyphProvider` に理由を書いた。
  - ベンチ: `./gradlew benchmark`（厳密）が通る。HEAD の worktree と同じマシン・同じ `test --tests PerformanceBenchmarkTest` を 3 往復して比べた（マシンが負荷で 3 割ほど揺れる）。showcase の Relayout は HEAD 25〜35 ms / 今回 31〜38 ms、Restyle は HEAD 22〜25 ms / 今回 20〜27 ms で、揺れの範囲。途中で、行の断片を置くたびに書体を引いて Relayout が約 40% 遅くなっていたのを、断片を作る時点で descent を持たせて直し（`InlineFragment.descent`）、`LineBreaker` の英字どうしの近道を足した。
  - 忠実度: 台帳は 24 → 23 行（`pseudo-lang-dir-defined#e.x` が解消）。ゴールデン（showcase）は UAX #14 で 2 か所変わった。`ArchitectureTest` の M9（トップレベルのファイル数）は 231 → 242。

1. **フォントアトラスの VRAM 膨張抑止と FreeType ハンドル管理（R7）**
   - `OutlineTextRenderer.AtlasKey` が `subpixel`（4 種）× `lightText`（2 種）を含むため 1 サイズあたり最大 8 倍の `FontSet` テクスチャが作られる問題と、`scale` アニメーション中にサイズごとのアトラスが溜まり続ける問題を、共通アトラス化・サイズ量子化・LRU 破棄で解消する。
   - `LoadedFace.OPEN` の解放ライフサイクルと、`SystemFonts.system()` の初回フォント走査の非同期ウォームアップを入れる。
2. **`@font-face` の `OutlineFontFace` 統合と実測 `ch` / `ex`（R7 + R4 残）**
   - `@font-face` の TTF / OTF を Minecraft の `BitmapFontFace` ではなく `OutlineFontFace`（FreeType + OpenType）で測り描きし、OS に依らず同じメトリクスにする。書体の指標から `ch` / `ex` と代替書体込みの `line-height: normal`、合字を決める。
3. **UAX #14 改行・UAX #9 (Bidi)・`writing-mode`・Selection / Range・`contenteditable`・IME（R7）**
   - [FIDELITY.md](FIDELITY.md) の R7 の 2 行（`direction: rtl` と `forced-line-breaks`）を解消し、選択・編集 API と IME の composition イベントを揃える。

### Phase 5: 置換要素（SVG / Canvas 2D）・レイアウト/パーサ残課題・DevTools 拡張（R8 / R9 / R10）

- **R8**: インライン `<svg>` と `<img src="*.svg">`、標準 `CanvasRenderingContext2D` と `Path2D`、`image-set()` / `srcset`。
- **R9**: 表の `%` 列幅と `border-collapse` 完全対応（[FIDELITY.md](FIDELITY.md) の R9 の 4 行を解消し台帳 0 行へ）、multicol、grid の `subgrid` / `baseline` / `auto-fit`、ブロック内インライン分割、HTML パーサの adoption agency・外来要素・`<template>`。
- **R10**: ゲーム内 DevTools（DOM ツリー・計算値・マッチ規則・ライブ編集）、WebSocket の CDP 部分集合 + MCP ブリッジ、ファイル監視のホットリロード。

---

### H0〜H4 — Headless State（2026-10-23〜24）

**目的**: 機械 UI のような定型画面で、サーバーが HTML を解析して DOM を常駐させ、全木と変異を送るのをやめる。
**達成**:
- **H0**: 互換モード（サーバーが DOM を持つ `WebSession`）のパケット量とヒープを `server_baseline.txt` に固定した
  （`PacketBandwidthTest`・`StateMemoryBenchmarkTest`）。
- **H1**: `api/state/UIState`（Key-Value・dirty 追跡・閲覧者・アクション）とサーバー側の `server/state`
  （`UIStateImpl` / `UIStateManager` が tick で flush し、ログアウト・ディメンション移動・停止で `EnderFrameServerState` から片付く）。
  パケットは `OpenUiS2CPacket`（テンプレート ID + 初期状態）・`StateDeltaS2CPacket`（変わったキーだけ）・
  `UiActionC2SPacket`（閲覧者の検査とレート制限）。値と NBT の変換は両側で使う `network/StateNbtConverter`。
- **H2**: クライアントのテンプレート。`client/template/ClientTemplateManager` が `assets/<ns>/enderframe_ui/*.html` を読んで
  キャッシュし、状態は `ReactiveStateStore` → `Bindings` で DOM に反映する。
- **H3**: 機械向けの高水準 API。`EnderFrameAPI.createBlockEntityState` / `openUi` / `openBlockEntityUi`（インスタンス ID は
  `block:x,y,z`）と、テンプレートの切り出し（`ParsedTemplate`）。
- **H4**: 既存コードの移行用アダプタ `UIState.asSessionHandle()` / `WebSessionHandle.asUIState()`
  （`UIStateSessionAdapter` / `WebSessionUIStateAdapter`）。
- 測定（[BENCHMARK.md](BENCHMARK.md) §6）: 初回 4,548 → 100 bytes、単一更新 166 → 59 bytes、100 セッションのヒープ
  約 35.6MB → 約 0.1MB、保持 DOM ノード 3,000 → 0。
- 検証: `UIStateTest`・`UIStateManagerTest`・`MachineStateApiTest`・`MultiViewerStateSyncTest`・`MachineInstanceIsolationTest`・
  `BackwardCompatibilityAdapterTest`・`HeadlessStateBandwidthAndMemoryTest`・`HeadlessStatePacketTest`・`HeadlessClientStateSyncTest`・
  `ClientTemplateManagerTest`。`client/` が `server/` を参照しないことは `ArchitectureTest` が検査する。

**残項目の解消（2026-10-24）**:
- 番号化: インスタンスは `wireId`、キーは状態ごとの辞書（`StateNbtConverter.KeyTable`）の番号で送る。単一更新 59 → 20 bytes、
  100 回の累計 5,892 → 1,992 bytes（[BENCHMARK.md](BENCHMARK.md) §6）。2 人目の閲覧者が開いたときに辞書へ入ったキーも、
  次の差分で 1 人目に名前が届く（`HeadlessStatePacketTest`）。
- ブロックの破壊での後始末: `createBlockEntityState` の状態は持ち主のブロックエンティティを弱参照で覚え、破壊・アンロードの次の tick で閉じる。
  `close()` は画面を開いている閲覧者に破棄を知らせ（差分パケットの closed フラグ）、クライアントは画面を閉じる。以前は古い値の画面が残り、操作も届かなかった。
  同じ座標に置き直したブロックは前の状態を使い回さない。
- `gametest/WebScreenGameTest` に `headlessStateFollowsBlockEntityLifecycle`（かまどを置いて開く → tick の差分 → 2 人目 → アクションの検査 →
  ログアウト → 破壊で後始末。パケットは本物のコーデックで往復）と `headlessStateIsNotReusedByReplacedBlock` を足した。`runGameTestServer` で 4 件とも通る。
- 実機（専用サーバー + クライアント 2 つ。番号化したパケットが実際にエンコードされる経路）での観測: テンプレートの読み込みと初期値、差分の反映、
  クリック・キー入力・ホイールが DOM のイベントとしてサーバーのアクションまで届くこと、2 人の閲覧者への同時反映、ブロックの破壊で両方の画面が閉じて状態が消えること、
  クライアントだけの HUD を 600 フレーム更新して C2S パケットが 0 件であること。確認用のテンプレートは `assets/enderframelib/enderframe_ui/state_test.html`、
  コマンドは開発用の run でだけ有効な `WebScreenGameTest.DevHarness`（`/efl_dev state|count|hud`）。
- 観測で見つけて直したもの: 画面を開いているとき `server:` アクションが画面のルータに取られ、イベント名と値を捨てて `rpc` / 空で送っていた
  （`oninput="server:…"` でサーバーが入力値を受け取れない。`ClientActionRoutingTest`）。語の境目に詰め（`-` と `v` など）がある文字列が、
  max-content ちょうどの flex アイテムで折り返していた（折り返しの判定が語の幅の和を見ていた。`TextShaperTest`・`HeadlessClientStateSyncTest`）。
- 未確認: 下向きのホイールは、ウィンドウへ送ったメッセージでは Minecraft に 0 として届き、実機で確かめられなかった（上向きはスクロールまで確認。
  下向きはヘッドレスで確認）。IME は従来どおり対象外。

### R4〜R10 の進捗

**R4 — 達成**: CSS Syntax 3 のトークンとコンポーネント値（`CssSyntax`。値の正規表現での再パースを撤去）、CSS ネスト、
`@layer`（入れ子・無名・`@import layer()`・`revert-layer`）、`@import supports()`、`@container`（`container-type` / `container-name`・
`style()`・コンテナ単位）、`@property`（`CustomPropertyRegistration`）、セレクタ（`:has()`・`:nth-child(An+B of S)`・
フォームの状態・`:lang()` / `:dir()` / `:defined` / `:target`・`::marker` / `::placeholder` / `::selection` の照合）、
規則の id / class / tag での振り分け、キーワードの enum（`CssKeywords`）、型付きの `@keyframes`、色 Level 4 / 5
（`ColorParser`）、radial / conic / repeating のグラデーションと補間のヒント（`Gradients`）、数学関数 Level 4・
`lh` / `rlh` / `cap` / `sv*` などの単位、`@media (resolution)`。コーパスに modern-css の 14 ケースを足して一致
（台帳は `direction` の 1 行だけ）。
**R4 — 残**: （`ch` / `ex` は 2026-10-26 に Phase 4 で書体の指標から決めるようにし、`InvalidationSets` は Phase 2 で再スタイルへ接続した。）
台帳の `::marker` の 8 行（書体の指標とブラウザの実測が要る）。

**R5 — 達成**: 差分レイアウト（`LayoutBox.measure` の再利用）、スクロール・ドラッグ・リサイズの近道
（[BENCHMARK.md](BENCHMARK.md) §4・§5）。正しさは `IncrementalLayoutTest`・`IncrementalPipelineTest`・`ServerClientLayoutParityTest`。
**R5 — 達成（2026-10-25、Phase 2）**: `InvalidationSets` の接続、非継承プロパティ変更時の子孫再スタイル抑止、`ComputedStyle` の遅延確保、再レイアウト境界と `contain`、配置の使い回し、描画だけの変化の近道、`StyleAnimator`（再スタイルで動かすトランジション・`@keyframes`）、CSSOM View。詳細は Phase 2 の節。
**R5 — 残**: 台帳 R5 のスクロールバー 10 行（古典的なスクロールバーの溝を取るかの方針待ち）、`transition-behavior`・`@starting-style`・`content-visibility: auto`、CSSOM View の WPT ケースの採用。

**R6 — 達成**: 角丸・グラデーション・角丸の祖先クリップを 1 枚の四角形で描く SDF シェーダー（`WebSdfShader`・
`RoundedRectRasterizer`）。GUI・ブロック面・手持ちアイテムで同じ経路。
**R6 — 達成（2026-10-25〜26、Phase 3a / 3b）**: `DisplayItem` の型付き描画情報（`RenderHints` / `BorderInfo` / `ShadowInfo` / `Gradients` / `Transforms`）への刷新と文字列キー撤去、宣言順の `transform`（`skew*` / `matrix` を含む）、ガウスの `box-shadow`（`blur`・`spread`・`inset`・複数指定）、楕円の角（`Corners`）と角丸に沿う `dashed` / `dotted`（`BorderPath`）、FBO のレイヤー合成（`LayerPool`・`LayerCompositor`。グループ `opacity`・`filter`・`backdrop-filter`・`mix-blend-mode`・`isolation`・`clip-path`・`mask`・回転した角丸クリップ）、旧 W12 の解消（スクロール後のヒットテスト・3D プレビューのシザー・世界内の面の半透明順序と文字行の部分クリップ `QuadClip`）。詳細は Phase 3 の節。
**R6 — 残**: 世界内の面（ブロック面・手持ちアイテム）は FBO を持たないためレイヤー効果を適用しない仕様（LIMITATION）。

**R7 — 達成**: システムの書体（`core/text/font`。OS の書体フォルダの一覧、CSS Fonts 4 §5.2 の照合、Chrome（Windows）の
総称ファミリ、字単位の代替、GPOS / `kern` の詰め、Chrome と同じ縦の寸法）、デバイスピクセルでのラスタライズ
（`OutlineTextRenderer`）、選択範囲のモデル（`TextSelection`）。`ChromeTextMetricsParityTest`・`ShowcaseChromeParityTest`・
`GlyphRasterizationTest` が Chrome の実測と比べる。
**R7 — 残（Phase 4 の続き）**: 合字、`line-height: normal` での代替書体の指標、`writing-mode`、`contenteditable` と IME の composition、描画の選択（`TextSelection`）と Selection API のつなぎ、台帳の R7 の 1 行（`forced-line-breaks`。測定に書体を使うコーパスが要る）。（アトラスの膨張と破棄・`LoadedFace` の解放・`@font-face` の統合・UAX #14・UAX #9 の並べ替え・`direction`・Selection / Range は 2026-10-26 に完了。）

**R10 — 達成**: GUI 画面の F12 で、ホバーした要素のタグ・id・class・矩形・バインディング属性を重ねて出す簡易インスペクタ、
F5 で文書の再読み込み。
**R10 — 残（Phase 5）**: DOM ツリーと計算値・マッチした規則の表示とライブ編集、リモートデバッグプロトコル、ファイル監視のホットリロード。

### R0〜R3 の記録

#### R0 の調査結果（2026-10-19 時点）

**達成**: `FidelityDumper` / `WebFidelityTest` が `display` / `position` / `box-sizing` / `overflow` とスクロール量（`sw` / `sh` / `cw` / `ch`）を比較（期待値 138 件）。
台帳 `known-divergences.txt` は 25 行で、すべて R4 / R5 / R7 / R9 のフェーズ付き。
WPT の枠（`WptHarness` / `WptDomBehaviorTest` / `WptManifestTest`、固定 SHA `a6f29b0b…`、採用 38 ケース）。
ディスプレイリストのゴールデン 3 件（decorations / flex-grid / showcase）。`PerformanceBenchmarkTest`（showcase.html・guidebook.md・500 項目リスト）。

**残項目の解消（2026-10-20）**:
- ベンチ基準値を `src/test/resources/benchmark/baseline.txt` に固定した。`PerformanceBenchmarkTest` は実測が
  「基準値 × 2.0 + 3ms」を超えたら回帰として落ち、`-Dbenchmark.update=true` のときだけ基準値を書き換える。
  レポートは従来どおり `build/reports/benchmark-results.txt` にも出す。
- 計算値の列を実際に比較するケース `computed-values` を足し（`opacity` / `transform` / `color`）、
  `WebFidelityTest.testDeclaredComputedPropertiesAreCompared` が「宣言した列が期待値に無い」状態を落とす。
  あわせて、値に空白を含む計算値（`rgb(10, 20, 30)` / `matrix(1, 0, 0, 1, 0, 0)`）が列区切りで
  切れていたのを、二重引用符で包む形式に直した（ブラウザ側 `fidelity-dump.html` も同じ規則）。
- WPT manifest の各ケースに `browserExpectation` を記録し、サブテスト単位の既知差分を
  `src/test/resources/wpt/known-divergences.txt` で管理する。`WptBehaviorTest` が `@WptCase` のパスを
  `WptHarness.test` に結び、台帳に無い失敗は回帰、載っているのに通ったものは古い台帳として落とす。
- ディスプレイリストのゴールデンは R6 で効果ごとに増やす前提（変更なし）。
- 検証: `./gradlew test` は 553 件・失敗 0（2026-10-20）。

#### R1〜R3 の調査結果（2026-10-19 時点）

`./gradlew test` は 437 件・失敗 0。R1〜R3 の変更は未コミット（作業ツリー差分 238 ファイル、直近コミットは W7）。

**R1 — 達成**: `api/dom/` の Node / Element / Text / Comment / Document / DocumentFragment、`DOMTokenList`・`CSSStyleDeclaration`・
`HTMLCollection`・`NodeList`・`MutationObserver`、`getElementById` の `idMap` 索引、WPT `dom/nodes` 19 件。
**R1 — 残なし（監査での補足は T8 で対応）**: `InteractionState` と `LayoutContext.ScrollState` は節オブジェクトをキー（弱参照）にした。
`getElementById` は索引だけで引き、未登録なら null を返す（`findNodeById` と線形探索のフォールバックは削除）。
文字列 id で節を引くのはネットワークと描画の境界だけで、`getNodeById` が担う。`NodeKeyedStateTest` で固定。
（※ 2026-10-25 監査: `Bindings` の i18n / 局所変数展開と `EnderScreenDelegate` のフォーム値同期に `attrs.put` の直書きが残っていたため、Phase 0 のラチェットと Phase 1 の T8 で DOM API 経由に統一する。）

**R2 — 達成**: `EventDispatcher` / `CoreEvent` / `DomAbortController` / `StandardDefaultActions`、`api/event` の UI Events 型、
`ConstraintValidator` / `FormController` / `FormData` / `ValidityState`、`EventDispatcherTest` / `EventTargetDomStandardTest` / `Form*Test`。
**R2 — 入力経路の DOM イベント化（実装済み）**:
- `HtmlDocument.InlineActionSink` を `SessionContext.setDocument` が結ぶ。`node.dispatchEvent(...)` は伝播経路の `on*` 属性のアクション名を
  `ClientScreenInteractionManager.runInlineAction` へ渡し、ローカルのルータ・サーバー転送まで届く。`triggerLocalEvent` を文字列で直接呼ぶ経路は
  ホバー・クリック・フォーム・ドラッグ・ホイールから消えた（残るのはシンク自身とフォーム値の同期だけ）。
- ホバーは UI Events の遷移（`over`/`out` は浮上、`enter`/`leave` は共通祖先の内側に 1 つずつ）。世界側スクリーンは押下で
  `pointerdown`/`mousedown`、解放で `pointerup`/`mouseup`、押下先と解放先の共通祖先へ `click`（`dblclick`、右は `contextmenu`+`auxclick`、中は `auxclick`）を合成。
- 打鍵は `beforeinput`（キャンセル可）→ 編集 → `input`、確定で `change`。`keyup`、`focus*`/`blur`、`wheel` も DOM イベント。
- `uievents/` の WPT 4 件（`mouse/attributes`・`click-order`・`mouseover-out`・`click-cancel`）を移植（`WptUiEventsBehaviorTest`）。
  `UiEventPipelineTest` が実際のヒットテストを通して順序とアクション名の呼び出し回数を固定。テスト 453 件・失敗 0。
**R2 — 残**:
- GUI とワールド内の入力は `SessionInput` の 1 本に統合し、`click` はどちらも解放時に `pointerup`/`mouseup` の後で配送する
  （`SessionInputPipelineTest` が GUI とワールド内の DOM イベント順を比較。T3）。
- IME の `composition*` は Minecraft の `EditBox` が preedit を渡さないため配送できない（型と `CoreEvent` 以外の経路は無い）。
- `runClient` での観測と `gametest/WebScreenGameTest` の確認は 2026-10-24 に済（専用サーバー + クライアントでクリック・キー入力・ホイールが DOM のイベントとして発火。H0〜H4 の節）。

**R3 — 達成**: クライアント駆動 API（`openClientHtmlScreen` / `setClientCustomHud` / `getClientDocument`）、
`requestAnimationFrame` / `setTimeout` / `setInterval`、`DomMutationRecord` と `WebSession.patch*`、README の所有権の節。
**R3 — 残項目の解消（2026-10-19）**:
- 旧差分適用エンジンと `SessionContext.virtualDom/templateDom`、`ClientWebManager.getVirtualRoot/getTemplateRoot` を削除。そのテストは `DomMutationRecordTest` に置き換えた。
  旧経路が黙って落としていた「サーバーからの全木の再同期」は `SessionContext.adoptServerTree` が受ける（文書のハンドルは変えず、中身を差し替える）。
  あわせて、サーバー駆動の文書がクライアントで `HtmlDocument` にならず（`Document` ハンドルも id 索引もイベントの送り先も無かった）、`HtmlNodeSerializer.deserialize` が最上位の `root` を `HtmlDocument` に戻すよう直した。
- 所有権: `core/dom/Ownership`（`data-owner`、継承、文書の既定）。サーバー同期の文書は既定でサーバー所有、クライアントだけの文書は既定でクライアント所有。
  クライアント所有の部分木へのサーバー変異は適用しない。再同期はクライアント所有の島を同じ id の位置へ移して残す。
- イベント転送の宣言: `core/dom/EventForwardDeclaration`（`data-forward` / `data-forward-action`、`onclick="server:…"` は糖衣、サーバー所有の入力は値の書き戻しを暗黙に宣言）。
  `HtmlNode.dispatchEvent` は `on*` と転送を伝播の後ろへ回し、`preventDefault` されていれば出さない（以前は `preventDefault` しても `on*` が走っていた）。
  `forwardEventsToServer` を撤去。サーバーは `data-forward-action` も送出アクション名として検証する（`WebSession`）。
- 検証: `EventForwardDeclarationTest`・`ServerOwnershipAndForwardingTest`（同期・変異・再同期・転送・クライアントだけの HUD を 240 フレーム更新して送出パケット 0）・`WebSessionForwardDeclarationTest`。テスト 478 件・失敗 0。
**R3 — 残（Phase 1）**:
- HUD を毎フレーム更新してパケットが出ないこと（600 フレームで C2S 0 件）と `gametest/WebScreenGameTest` は 2026-10-24 に実機で確認。
  サーバー駆動画面（互換モードの `WebSession`）を実機で開き直す確認はまだ（ゲームテストでのサーバー側の文書と寿命まで）。
- 再同期はサーバー所有のノードを作り直すため、ホバー・フォーカス・スクロールなどノードに紐づく状態は戻る（細かい更新は変異レコードで、ノードの同一性を保つ）。

R3 を「完了」とする条件は `runClient` での観測としていた。実際には観測を残したまま、ヘッドレスのテストを完了条件に R4〜R7 と H0〜H4 を先に進めた（`runClient` での観測は各フェーズの残に持ち越している）。

---

## 出発点（旧計画で揃ったもの）

旧計画で、**静的な文書をブラウザと同じ形に組む**骨格はできている。

- **HTML**: WHATWG のツリー構築（挿入モード・暗黙の html / head / body・暗黙終了・foster parenting・
  暗黙の `tbody` / `tr`・完全な名前付き文字参照）。43 ケースがブラウザの木と一致（`HtmlTreeConstructionTest`）。
- **CSS**: UA / 作者 / インラインの起源、`!important`、CSS 全域キーワード（`revert` 含む）、`var()`、
  `calc()` / `min()` / `max()` / `clamp()`、148 の名前付き色、`@media`（範囲構文・`prefers-*`）・`@supports`、
  `:is()` / `:where()` / `:not()` と構造擬似クラス。プロパティの知識は `PropertyRegistry` に 1 回だけ宣言。
- **レイアウト**: ボックスツリー（`BoxTreeBuilder`。匿名ブロック・匿名表ボックス・`display: contents`・`::marker`）、
  block / inline / float / table / flex / grid / 絶対・固定・sticky。幾何は float で、丸めは描画の 1 箇所。
- **描画**: CSS 2.2 付録 E の層の順でスタッキングコンテキストの木を平らにする。border-style・多層背景。
  クリップ・`visibility`・`pointer-events`・ヒットテスト（W8）、状態変化の再スタイルと擬似要素（W9）、
  色・セレクタ・単位の厳密化（W10）、インライン断片の結合（W11）まで入っている。
- **測定**: 忠実度コーパス 123 ケース（9 領域）がブラウザと一致（幾何 ±0.5px・描画順完全一致）、台帳 0 行。
  テスト 370 件 / 失敗 0。`./gradlew build test` が CI と同じ経路。

旧 W12（transform 合成・スクロールのヒットテスト・3D 要素のシザー）は未着手のまま R6 に吸収した。

足りないのは、**文書が生きて動くための仕組み**（ライブ DOM・イベント・インクリメンタル更新・合成描画）と、
**CSS の現代的な構文と値**である。次章の比較がその根拠。

---

## ApricityUI との比較

この節は計画を練り直した時点（2026-10-19）の比較で、「EnderFrameLib の現状」の列は当時のもの（いまの到達点は上の「現在地」）。

ApricityUI（以下 AUI）は Forge / NeoForge / Fabric の 8 ターゲットに対応する HTML / CSS / JS の UI MOD。
`common/` 約 8.3 万行、テスト 1,049 件。以下のパスは
`AUI-snow/AUI-snow/common/src/main/java/com/sighs/apricityui/` からの相対。

### EnderFrameLib が進んでいるもの（維持する）

| 領域 | EnderFrameLib | AUI |
|---|---|---|
| HTML パース | WHATWG のトークナイザとツリー構築 | 正規表現 1 本のトークナイザと単純な開いた要素のスタック（`parser/HTML`）。暗黙終了・foster parenting なし、名前付き文字参照は 6 個 |
| カスケード | 起源・`!important`・宣言順を仕様どおりに並べる | 規則を**セレクタ文字列キー**の `LinkedHashMap` に積むので、同じセレクタの 2 回目が 1 回目の位置に並ぶ（`parser/CSS`） |
| `@media` | 範囲構文・`prefers-*`・`hover` / `pointer` | 幅・高さ・向きだけ |
| 整形コンテキスト | float・table・sticky・ボックスツリー | table は block に、float と sticky は無い（`ComputedStyleResolver.normalizeDisplay`） |
| 描画順 | スタッキングコンテキストの木 | 平らなリストを持ち上げる方式（`render/Drawer`）。同等 |
| 測定 | 123 ケースがすべて一致し、台帳の行の出入りがフェーズの完了条件 | WPT 19,775 件を全量で回し pass 5 件（`wpt/progress.md`）。回帰の番人として機能していない |

### AUI が進んでいるもの（取り込む）

| 領域 | AUI（参照先） | EnderFrameLib の現状 |
|---|---|---|
| ライブ DOM | `init/Node`・`init/Element`・`init/Document`。`appendChild` / `insertBefore` / `replaceChildren` / `innerHTML` の setter / `classList` / `dataset` / 双方向の `element.style`。`dom/MutationObserverManager`、`ResizeObserver`・`IntersectionObserver` | `HtmlNode` は公開フィールドの木。識別は文字列 id（`gen_N`）。テンプレート・仮想・実の 3 コピーを差分適用エンジンが突き合わせる。`getElementById` は線形探索 |
| イベント | `event/Event` が捕捉・目標・浮上の 3 相、`stopImmediatePropagation`・`composedPath`・`isTrusted`。mouse / pointer / wheel / keyboard / focus / input / `beforeinput` / composition | `EventDispatcher` の捕捉は空、リスナーの登録口が無い（`EventListener` は未使用）、`preventDefault` を見る既定動作が無い。DOM を通るのは click と contextmenu だけで、他は `DisplayItem` のイベントマップを直接引く |
| フォーム | `form/ConstraintValidator`・`ValidityState`・`FormData`、`requestSubmit`・`formdata` イベント | 既定動作（送信・リセット・ラベル）のみ |
| 無効化 | 要素ごとの dirty ビット（REPAINT / REORDER / RELAYOUT / HITTEST）、transform とスクロールだけの変化は再レイアウトしない（`render/LayoutCommit`）、規則を id / class / tag で振り分ける（`parser/Selector` の `Index`） | DOM 変更・バインディングの変化・**スクロール 1 段ごと**に全文書を再スタイル→ボックスツリー再構築→全レイアウト→ディスプレイリスト再生成。規則照合は全規則の線形走査。リサイズで全シートを再パース |
| 描画効果 | FBO のプールで `filter`・`backdrop-filter`・16 種の `mix-blend-mode`・`clip-path`・`mask` を合成（`render/FilterRenderer`・`render/Mask`・`render/ClipPath`）。ガウスの `box-shadow`、`perspective()` | `filter` と `mix-blend-mode` はスタッキングコンテキストを作るだけで描かない。`clip-path` / `mask` 無し。opacity は部品ごとの乗算。transform は正規表現で読み、宣言順を無視して translate → rotate → scale の固定順 |
| 置換要素 | Canvas 2D API ほぼ全量（Path2D・3 種のグラデーション・合成演算・`isPointInPath`）、インライン SVG と画像としての SVG、TTF のグリフアトラス（`render/GlyphCache`） | `Canvas2DContext` は独自のコマンド列。SVG 無し。Minecraft のビットマップフォントだけ |
| 編集 | Selection / Range、`beforeinput`、undo ログ（`behavior/richtext/*`） | 単一行は隠した `EditBox`（`InvisibleInputScreen.WebTextInputHost`）、複数行は `TextareaEditor`。contenteditable 無し |
| 開発ツール | ゲーム内 DevTools（DOM ツリー・計算値・ライブ編集）、CDP 風の WebSocket デバッグサーバ（`dev/debug/ExternalDebugServer`）と MCP ブリッジ（`tools/apricity-mcp`）、ファイル監視のホットリロード | `/enderframe devkit` の採取ページだけ |
| 測定の幅 | ブラウザ側のプローブが矩形に加えて計算値（display / position / box-sizing / overflow）とスクロール量を採る（`wpt/tools/browser-adapter.mjs`） | 矩形と描画順だけ |

### 両方に無いもの（Web 標準との共通の差）

CSS Syntax Level 3 のトークン列、CSS ネスト、`@layer`、`@container`、`@property`、`:has()`、
色 Level 4（`oklch` / `lab` / `color-mix()`）、`writing-mode`、multicol、UAX #9（bidi）・UAX #14（改行）、Shadow DOM。
EnderFrameLib はさらに radial / conic / repeating グラデーションと 3D transform が無い。

### AUI から真似しないもの

- 正規表現の HTML / CSS パーサ、セレクタ文字列キーのカスケード、パース時にしか評価しない `@media`。
- 3,445 行の `init/Element` のように、DOM・フォーム・スクロール・描画を 1 クラスに抱える形。
- WPT の全量を回して pass 率を見る運用。採用したケースを固定して台帳で管理する今の方式を広げる（R0）。
- 組み込み JS エンジン（Rhino。方針 1）。
- マルチローダーの targets 構成と WebView2 の iframe（Web 標準への接近とは別の話なのでスコープ外）。

---

## 方針（ユーザー確認済み）

1. **組み込み JS エンジンは入れない。** `<script>` も Rhino / GraalJS も持たない。
2. **Web 標準どおりの DOM / Event API を Java と KubeJS に直接公開する。**
   インターフェースの形（名前・引数・戻り値・例外）は DOM Standard / UI Events / HTML の Web IDL に合わせる。
3. **ハイブリッド実行モデル。** DOM ツリーとイベントはクライアントに置き、
   - **クライアント駆動**: クライアントだけで完結する UI 操作（地図 MOD・高頻度の HUD 更新・クライアント側スクリプト）。通信 0。
   - **サーバ駆動**: パケットで同期される状態とアクション。

   の両方から**同じ DOM API** で操作できるようにする。
4. **既存の宣言的バインディング**（`data-model` / `data-for` / `{{}}` / `WebSession.patch*`）は
   新しい DOM API の上に作り直して残す。
5. 旧第 2 期からの継承: **Web 準拠を優先し破壊的変更を取る**・**表示と操作感の両方が対象**・**ブラウザ実測との差分テストで検証する**。

---

## 設計の軸

### 継承する不変条件（旧計画で達成済み。崩さない）

- **測定の出所は 1 つ。** テキストの幅・ベースライン・折り返し位置は `core/layout` の `TextShaper` が決め、描画はそれをそのまま描く。
- **幾何は float、丸めは描画の 1 箇所。**
- **差は台帳に書く。** FIDELITY.md・`LIMITATION`・README の 3 点セット。台帳に無い差はテストが落とす。
- **プロパティの知識は 1 箇所**（`PropertyRegistry`）。
- **DOM と箱は別物**（レイアウトの入力はボックスツリー）。
- **描画順は木で決める**（付録 E）。

### 新しい軸

**軸 A — DOM はクライアントに 1 つ。**
ノードの識別はオブジェクトそのもの。文字列 id はネットワークの直列化の境界でだけ使う。
Java のクライアントコード・KubeJS・サーバからのパケット・宣言的バインディングは、全員同じ DOM API を通って同じ木を変える。
テンプレート・仮想・実の 3 コピーと差分適用は持たない。

**軸 B — 変更は DOM の変異として流れる。**
変異と要素の状態変化が、要素ごとの dirty ビット（style / layout / paint / composite）を立てる。
パイプラインは立ったビットの範囲だけ進める。全文書の再構築は初回と UA・ビューポートの大域的な変化だけ。

**軸 C — 値はトークンから描画まで型付き。**
CSS Syntax Level 3 の component value → 型付きの指定値・計算値 → 使用値。
キーワードは enum、色は色空間つきの値、transform は関数の列。
正規表現での再パースと、`DisplayItem` の文字列キー（`mc-radius` など）で描画メタを渡す経路を無くす。

**軸 D — 描画はディスプレイリストと合成レイヤー。**
レイアウトの結果から型付きのディスプレイリストを作り、opacity・filter・blend・clip-path・mask・3D transform は
レイヤー（FBO かステンシル）として合成する。ヒットテストも同じリストと変換を読む。

**軸 E — 測れる差はすべて測る。**
矩形と描画順に加え、計算値・DOM API の振る舞い・ディスプレイリストをブラウザと突き合わせる。
測れない差は「意図的に違うもの」として根拠を書く。

---

## フェーズ計画

各フェーズは **目的 / 内容 / 参照（AUI）/ 完了条件と検証** で書く。
完了条件は、忠実度台帳か専用テストで表せるものにする（旧計画と同じ）。

### R0 — 測定の拡張（＋ R0+ テスト・計測・ラチェット拡充）

**目的**: R1 以降の変更（DOM・イベント・無効化・合成）が壊したり直したりするものを測れるようにする。
今のコーパスは静的な矩形と描画順しか見ない。

**内容**:
- **計算値のダンプ。** `fidelity-dump.html` と `FidelityDumper` に、id 付き要素の計算値の列
  （`display`・`position`・`box-sizing`・`overflow`・`opacity`・`transform`・`color` など、ケースが宣言したもの）と
  スクロール量（`scrollWidth/Height`・`clientWidth/Height`）を足す。比較は `WebFidelityTest` に足す。
- **DOM の振る舞いテストの枠。** WPT の `dom/` と `uievents/` の testharness ケースを JUnit に移植する土台
  （`assert_equals` 相当のヘルパと、ケースごとに WPT のパスと固定 SHA を書く約束）。R1・R2 の完了条件はこれで書く。
- **ディスプレイリストのゴールデン。** `DisplayListBuilder` の出力を型付きでテキスト化し `GoldenFiles` で固定する。
  R6 の合成化で描画の意味が変わらないこと（と変わったこと）を差分で見る。
- **WPT の採用ケース manifest。** WPT を全量では回さない。固定 SHA の上で採用するケースを 1 件ずつ manifest に載せ、
  ブラウザ側の期待値を今と同じ形で採り、台帳で管理する。採用の基準は「字幅に依存しない」「本エンジンのスコープ内」。
- **性能ベンチ。** 代表文書（`showcase.html`・`guidebook.md`・大きなリスト）で、再スタイル・再レイアウト・
  ディスプレイリスト生成の時間とスクロール 1 段の時間を測る JUnit ベンチ。R1 と R5 の前後比較に使う。
- **R0+（2026-10-25 追加・最優先）**:
  - `PerformanceBenchmarkTest` に単一要素 `:hover`・単一 class トグル・1 行挿入・`repositionLayout` スクロールのマイクロ操作ベンチを追加する。
  - `ArchitectureTest` に static コールバック禁止・サーバー状態のコマンド混入禁止・`attrs.put` 直書き禁止・`RenderKeys` と `DisplayListBuilder` 正規表現の上限ラチェットを追加する。
  - 複数 `EnderScreenDelegate` 同時稼働、バインディング/入力同期の `MutationObserver` 通知、`WebMediaManager` のリダイレクト・SSRF 防御の回帰テストを先行追加する。

**参照**: AUI `wpt/tools/browser-adapter.mjs`（プローブが採る値）、`wpt/README.md`（固定 SHA の運用）。

**完了条件と検証**: 既存の全ケース（138 件）の計算値列がブラウザと一致（新しく見えた差は台帳へフェーズを振って載せる）。
DOM 振る舞いテストの枠に最初の数ケースが入って通る。ベンチの基準値を記録する。R0+ の新規ラチェットと回帰テストがすべて組まれ、Phase 1〜5 の番人として機能する。

### R1 — ライブ DOM

**目的**: 軸 A を立てる。DOM Standard の形の木をクライアントに 1 つ持ち、全員がそれを変える。

**内容**:
- `Node` / `Element` / `Text` / `Comment` / `Document` / `DocumentFragment` を DOM Standard に沿って置く
  （`HtmlNode` の公開フィールドを置き換える）。
  - 木の操作: `appendChild` / `insertBefore` / `removeChild` / `replaceChild` / `append` / `prepend` / `before` / `after` /
    `remove` / `replaceWith` / `replaceChildren` / `cloneNode` / `contains` / `compareDocumentPosition`。前提条件の例外（`HierarchyRequestError` 等）も仕様どおり。
  - 内容: `textContent`、`innerHTML` / `outerHTML` の getter（直列化アルゴリズム）と setter（`HtmlParser.parseFragment` を使う）。
  - 属性: `getAttribute` / `setAttribute` / `removeAttribute` / `toggleAttribute` / `hasAttribute`、`classList`（`DOMTokenList`）、`dataset`。
  - `element.style`（`CSSStyleDeclaration`。`style` 属性と双方向）。
  - 検索: `getElementById`（id 索引）、`querySelector(All)`・`matches`・`closest`（`SelectorMatcher` を使う）、
    ライブな `HTMLCollection` / `NodeList`（`children`・`getElementsByClassName` 等）。
- `MutationObserver`（`childList` / `attributes` / `characterData` / `subtree` / `attributeOldValue` / `attributeFilter`）。
  JS のマイクロタスクが無いので、通知はクライアント tick の決まった点（再スタイルの前）でまとめて配る。この差は `LIMITATION` に書く。
- 文字列 id で引いている状態を**ノードをキー**にする: `InteractionState`、`LayoutContext` のスクロール状態、
  計算値のキャッシュ。文字列 id（`HtmlParser.NodeId`）はネットワーク直列化の境界だけに残す。
- 公開 API は `api/dom/` にまとめ、`ApiBoundaryTest` で `core` の内部型が漏れないことを確かめる。

**参照**: AUI `init/Node`・`dom/MutationObserverManager`・`dom/ElementTree`（id 索引）。
真似しない: `init/Element` のように描画やフォームまで同じクラスに持たせない（DOM は `core/dom`、フォームの振る舞いは R2 の別クラス）。

**完了条件と検証**: R0 の枠に移植した WPT `dom/nodes/` の採用ケースが通る。
既存の 370 件・123 ケースに回帰が無い。R0 のベンチで初回構築の時間が悪化していないこと。

### R2 — イベントとフォーム

**目的**: DOM Standard のイベントディスパッチと UI Events を入れ、既定動作を `preventDefault` で止められるようにする。

**内容**:
- `EventTarget.addEventListener` / `removeEventListener`（`capture` / `once` / `passive` / `signal` の options。`AbortController` / `AbortSignal` を含む）。
- ディスパッチのアルゴリズム（伝播経路の固定・捕捉→目標→浮上・`stopPropagation` / `stopImmediatePropagation`・
  `composedPath`・`isTrusted`・`dispatchEvent` での合成イベント）。`EventDispatcher` の空の捕捉ループと未使用の `EventListener` を置き換える。
- UI Events: `pointerdown/move/up/over/out/enter/leave`・`mouse*`・`click` / `dblclick` / `auxclick` / `contextmenu`
  （押下と解放からの合成）・`wheel`・`keydown` / `keyup`（`key` / `code` / 修飾キー）・`focus` / `blur` / `focusin` / `focusout`・
  `input` / `beforeinput` / `change`・`compositionstart/update/end`。いまの `triggerLocalEvent`（イベントマップの直接参照）は全部これに寄せる。
- 既定動作（`StandardDefaultActions`）はディスパッチ後に `defaultPrevented` を見てから走る。リンク・ラベル・`details` / `summary`・
  チェックボックス・送信・フォーカス移動・スクロール・テキスト入力が対象。
- フォーム: 制約検証（`validity` / `checkValidity` / `reportValidity` / `setCustomValidity`、`required` / `pattern` / `min` / `max` / `step` / ラジオグループ）、
  `submit` / `reset` / `formdata` / `invalid` イベント、`requestSubmit`、`FormData`、`form` 属性での関連付け。
- フォーカス: `WebFocusNavigator` の `tabindex` 順を `focus()` / `blur()` / `document.activeElement` と `:focus-visible` の判定に揃える。

**参照**: AUI `event/Event`（3 相・`composedPath`）、`event/MouseEvent`、`form/ConstraintValidator`・`ValidityState`。
AUI の `addEventListener` は第 3 引数が真偽値だけで `passive` / `signal` が無いので、そこは仕様に合わせる。

**完了条件と検証**: WPT `dom/events/` と `uievents/` の採用ケース、`EventDispatcherTest` と `InteractionPipelineTest` の拡張。
`runClient` でクリック・キー入力・ホイールが DOM のイベントとして観測できること。

### R3 — ハイブリッド実行モデルと宣言的バインディングの載せ替え

**目的**: 方針 3・4。クライアント駆動とサーバ駆動が同じ DOM を同じ API で触る形にし、3 コピーと差分適用をなくす。

**内容**:
- **クライアント駆動の API。**
  - 画面・HUD・ブロック面・ビルボードの文書をクライアントだけで開ける（今はサーバのセッション経由が主）。開いた文書は `Document` のハンドルとして返す。
  - クライアントのスケジューラ: `requestAnimationFrame` 相当（描画フレーム）と `setTimeout` / `setInterval` 相当（クライアント tick）。
    文書が閉じたら登録を全部解く。
  - KubeJS のクライアント側バインディング（`EnderFrameScriptingBridge` をクライアント側にも置き、DOM API をそのまま見せる）。
- **サーバ駆動の同期。**
  - サーバからの更新は **DOM 変異のレコード列**（MutationRecord 相当: 子の挿入・削除、属性、文字データ、style）としてパケットにする。
    `HtmlNodeSerializer` は初回の木の転送と挿入される部分木の直列化に使う。`WebSession.patchText / patchAttribute / patchStyle / patchClass / patchInnerHtml` はこの変異の糖衣にする。
  - イベントのサーバ転送は宣言で行う（要素・イベント種別・送る値を指定。`onclick` のアクション名はこの宣言の糖衣）。
    クライアントのリスナーが先に走り、`preventDefault` されなければ転送する。
  - **所有権**: 部分木ごとにサーバ所有かクライアント所有かを持つ。サーバ所有の部分木をクライアントのコードが変えても、
    次のサーバ変異が正になる（書けるが上書きされる、を `LIMITATION` ではなく仕様として README に書く）。
  - サーバ側の検証は `WebSessionManager`（`canAccess`・id / アクションの検査）と `PlayerRateLimiter` をそのまま通す。クライアントからの値は信用しない。
- **宣言的バインディングの載せ替え。**
  - `ReactiveStateStore`（`data-model` / `data-if` / `data-show` / `data-for` / `{{}}` / i18n）と `ClientPubSubManager` を、
    DOM API で自分の担当ノードを直接変える「バインディング層」にする。`TemplateDeps` の依存追跡はそのまま使う。
  - `SessionContext` のテンプレート・仮想・実の 3 コピーを撤去し、更新は `DomMutationRecord` の変異列だけにする。
    `data-for` の行は鍵（`key` 属性か添字）で既存ノードを再利用する。
- **互換テストを先に固定する。** 載せ替えの前に、今の `WebSession.patch*`・`data-*`・PubSub の挙動を DOM の結果で書いたテストにしておき、
  旧経路と新経路の両方で通してから旧経路を消す。

**参照**: AUI `spi/AuiServices`（クライアント側 API の切り口）、`network/*`（チャンク分割パケット）。
AUI はサーバ→クライアントの同期がコンテナのスロットに限られ、汎用のデータ経路が無いので、同期モデルは独自に設計する。

**完了条件と検証**: 互換テストが新経路で通る。`TemplateDepsTest`・`DomMutationRecordTest`・
`HtmlNodeSerializerTest`・`gametest/WebScreenGameTest`。`runClient` でクライアントだけで開いた HUD を毎フレーム更新しても
パケットが出ないこと、サーバ駆動の画面が今と同じに動くこと。README に移行メモ。

### R4 — CSS の構文・カスケード・値の現代化

**目的**: 軸 C の CSS 側。CSS Syntax Level 3 の上に、現代の CSS の構文と値を載せる。

**内容**:
- **構文**: CSS Syntax Level 3 のトークナイザとパーサ（component value・simple block・function）。
  宣言の値はトークン列のまま `ValueComputer` に渡し、`LengthResolver.NUMBER_WITH_UNIT` などの正規表現での再パースを無くす。
  `@keyframes` も文字列エンコードをやめ、型付きのキーフレーム列にする（`CssAnimator` が読む）。
- **CSS ネスト**（`&`・入れ子の規則とその詳細度）。
- **カスケード**: `@layer`（宣言順・入れ子の層・無名の層・`@import layer()`）、`!important` の層と起源の反転、`revert-layer`、
  ユーザー起源の枠。`@import` の `supports()` 条件。
- **条件付き規則**: `@container`（`container-type` / `container-name` とコンテナ単位 `cqw` 等）、`@media` は再パースせず
  ビューポートが変わったときに条件だけ評価し直す。
- **`@property`**（構文・継承・初期値。登録したカスタムプロパティは型付きで補間できる）。
- **セレクタ**: `:has()`、`:placeholder-shown`・`:read-only` / `:read-write`・`:valid` / `:invalid` / `:in-range` / `:out-of-range`（R2 の制約検証を読む）・
  `:lang()`・`:dir()`・`:target`・`:defined`・`:nth-child(An+B of S)`。`::placeholder`・`::selection`・`::marker` を照合する。
- **規則の振り分け**: 右端の複合セレクタの id / class / tag / 属性で規則をバケットに分け、照合する規則を絞る。
  状態と変異の無効化は、セレクタから作る無効化セット（どの class / 属性 / 状態の変化がどの要素を汚すか）で決める（R5 の dirty ビットに渡す）。
- **値**: キーワードを enum に（`PropertyRegistry` の型宣言を読み手まで通す）。色 Level 4 / 5（`hwb`・`lab`・`lch`・`oklab`・`oklch`・`color()`・`color-mix()`・相対色）。
  `radial-gradient`・`conic-gradient`・`repeating-*`・色の補間ヒント。数学関数 Level 4（`round`・`mod`・`rem`・三角関数・`abs`・`sign`）。
  単位 `lh`・`rlh`・`cap`・`svh` / `lvh` / `dvh` 系。字形を測った `ch` / `ex`（R7 のフォント指標を読む）。

**参照**: AUI `parser/Selector`（`Index` の振り分けと `SELECTOR_CACHE`）、`parser/Color`（`color(srgb-linear …)`）。
AUI の `parser/CSS` は正規表現でネストが原理的に書けないので構文は参考にしない。

**完了条件と検証**: `CssTokenizerTest` を CSS Syntax の WPT（`css/css-syntax/`）採用ケースに、`SelectorMatchingTest`・`ValueModelTest`・
`ShorthandExpansionTest` を拡張。コーパスに `@layer`・ネスト・`@container`・`:has()`・新しい色の領域を足して全件一致。

### R5 — インクリメンタル・パイプラインと CSSOM

**目的**: 軸 B。変異と状態変化が汚した範囲だけを再計算し、スクロールや transform のような頻繁な変化でレイアウトを回さない。
あわせて、レイアウトの結果を DOM API から読めるようにする。

**内容**:
- **dirty ビット**: 要素ごとに style / layout / paint / composite と子孫用のビット。R4 の無効化セットと R1 の変異が立てる。
  `SessionContext.InvalidationLevel` と `markAllDirty()` の全文書再構築、および `InteractionState.propagateDirtyToAncestors` の無条件な親汚しを置き換える。
- **再スタイルと省メモリ化**: 汚れた要素と、無効化セットが指す兄弟・子孫だけ。`ComputedStyle` の比較は全マップの `equals` ではなく、
  `PropertyRegistry` の無効化種別ごとに「継承される値が変わったか」「レイアウトに効く値が変わったか」を見る（非継承プロパティだけの変化では `childForceDirty` を立てない）。
  あわせて `ComputedStyle` の `ConcurrentHashMap` × 4 個即時生成と `StyleResolver.resolveDeclarations` の一時 `LinkedHashMap` × 5 個生成を削減する。
- **ボックスツリーとレイアウト**: 汚れた部分木だけボックスツリーを作り直す。レイアウト境界（確定サイズで `overflow` が `visible` 以外、
  `contain: layout` / `size` 等）を relayout root にして、そこから上へ伝えない。`contain` と `content-visibility` を入れる。
- **速い経路**: スクロール・transform・opacity だけの変化はレイアウトを通さず、ディスプレイリストの変換と合成だけを変える。
  スクロール位置は int から float にする。
- **アニメーション**: `CssAnimator` を `PropertyRegistry` の「アニメーション可能」宣言で駆動し、4 チャネル（opacity / transform / color / 背景・枠の色）の
  制限を外す。レイアウトに効くプロパティの補間は layout ビットを立てる。`transition-behavior`・`@starting-style`。
- **CSSOM / CSSOM View**: `getComputedStyle`（解決値）、`getBoundingClientRect` / `getClientRects`、`offset*` / `client*` / `scroll*`、
  `scrollTo` / `scrollBy` / `scrollIntoView`、`ResizeObserver`、`IntersectionObserver`、`matchMedia`。

**参照**: AUI `render/LayoutCommit`（`commitTransforms` / `commitScrollTranslation`）、`render/Drawer` の dirty ビット、`dom/RenderElement`。
AUI も本物の再レイアウトは全文書なので、レイアウト境界は独自に入れる。

**完了条件と検証**: R0 / R0+ のベンチで、スクロール 1 段・hover の色変化・class トグル・リストの 1 行追加がレイアウト全体を回さず `< 1.0 ms` で完了することを数値で確認。
インクリメンタル更新の結果が全再構築と一致することを、ランダムな変異列で比べるテスト。CSSOM View の WPT 採用ケース。台帳の R5 の 11 行と R4 の 8 行が消えること。

### R6 — 描画の合成化

**目的**: 軸 D。型付きのディスプレイリストとレイヤー合成で、今は描いていない効果を描く。旧 W12 を含む。

**内容**:
- **ディスプレイリスト**: `DisplayItem` の `events` マップで描画メタ（`mc-radius`・`mc-clip`・`mc-gradient` 等の `RenderKeys`）を渡すのをやめ、
  型付きの項目（矩形・角丸・枠・背景層・影・テキスト・画像・置換要素・クリップ・レイヤー開始 / 終了）にする。`DisplayItemDrawer` での毎フレームの CSV `split` / `parseFloat` を根絶する。
  ヒットテスト（`HitTestIndex`）と忠実度ダンプの z 列は同じリストを読む。
- **transform**: `matrix()` / `matrix3d()`・`skew*`・3D 関数・宣言順の合成・個別プロパティ（`translate` / `rotate` / `scale`）・
  `perspective` / `perspective-origin`・`transform-style: preserve-3d`・`backface-visibility`。正規表現での読み取り（`DisplayListBuilder` の `TRANSLATE_PATTERN` 等）をやめ、`CssSyntax` のトークン列から順に評価する。
- **レイヤー合成**: FBO のプールを持ち、opacity のグループ合成・`filter`（blur / brightness / contrast / grayscale / hue-rotate / invert / saturate / sepia / drop-shadow）・
  `backdrop-filter`・`mix-blend-mode`・`isolation` を描く。`clip-path`（基本図形・`path()`）と `mask-*` はステンシルかマスク用 FBO で。
  transform 後の空間での非矩形クリップもここで解く。
- **装飾**: 3 色以上のグラデーションを `WebSdfShader` へ統合（`GradientStrip` の撤廃）。ガウスの `box-shadow`（今の段階的な層をやめる）・透明背景で外側の影を抜く・inset 影の角丸、楕円の角、
  角丸に沿う dashed / dotted、`outline` の角丸、インライン断片の背景画像、`background-attachment: fixed / local`。
- **旧 W12**: スクロールのヒットテスト（`ClientScreenInteractionManager.handleScrollLocally`）が祖先の変換の逆行列とクリップを通ること、
  `<mc-scene>` / `<mc-entity>` / `<img src="item:...">` のシザー、スクロール端にかかるテキスト行の部分クリップ、複数 Surface バッティング時の半透明順序（[TEST_SPEC_SURFACE.md](TEST_SPEC_SURFACE.md) A-14）。

**参照**: AUI `render/FilterRenderer`（FBO プール・backdrop 用プール）、`render/Mask`、`render/ClipPath`、`render/CssBlendMode`、`style/Transform`。

**完了条件と検証**: R0 のディスプレイリストのゴールデンで、効果ごとの項目が出ること。`RenderKeys` の参照数が 0 になること。`PaintModelTest`・`HitTestAndOcclusionTest` の拡張。
`runClient` で効果ごとの見本ページを目視し、スクリーンショットを `guidebook.md` の見本と並べる。
コーパスに transform（2D の合成順・3D の矩形）を足して一致。

### R7 — テキストと国際化

**目的**: 字幅に依存する幾何を測れるようにし、多言語の改行と方向を Web と同じ規則にする。

**内容**:
- **TTF のグリフ描画とアトラス VRAM 最適化**: `@font-face` の TTF / OTF を Minecraft の `BitmapFontFace` ではなく `OutlineFontFace` とグリフアトラスで実寸に描く。
  `OutlineTextRenderer.AtlasKey` の `subpixel` × `lightText` による 8 倍アトラス分割を統合・量子化し、LRU 破棄と `LoadedFace.OPEN` の解放、`SystemFonts.system()` の非同期ウォームアップを入れる。
  フォント指標（ascent / descent / line gap / x-height / cap-height）を `FontFace` に出し、`line-height: normal` と `ch` / `ex` を書体から決める。
- **字幅の測定**: コーパスで**同じフォントファイルをブラウザ側にも `@font-face` で読ませる**と、折り返し位置・中央寄せ・
  `line-height: normal` を比べられる。FIDELITY.md の「意図的に違うもの」のうち TTF を使う場合の差をここで消す。
  Minecraft の既定フォントを使う文書の差は残る。
- **改行と方向**: UAX #14 の改行（今の空白と CJK 範囲の発見的規則を置き換える）、`word-break` / `overflow-wrap` / `line-break` / `hyphens` / `text-wrap`、
  UAX #9 の bidi と `direction` / `unicode-bidi`、`writing-mode`（`vertical-rl` / `vertical-lr`）と論理プロパティの物理への写像。
- **選択と編集**: Selection / Range API、`contenteditable`（`beforeinput` / `input` の `inputType`、undo）、
  `<input>` / `<textarea>` の `selectionStart` / `selectionEnd` / `setSelectionRange`。IME の composition を R2 のイベントで流す。

**参照**: AUI `render/GlyphCache`（アトラスのページ・1 フレームのラスタ予算）、`resource/Font`、`behavior/richtext/*`・`behavior/TextSelection`。
AUI も bidi と UAX #14 は無いので独自に入れる。

**完了条件と検証**: `TextShaperTest` を UAX #14 / #9 の公式テストデータで拡張。コーパスに TTF を使うテキストの領域を足して一致。台帳の R7 の 2 行が消えること。
`TextSelectionTest` の拡張と `runClient` での日本語入力の確認。

### R8 — 置換要素（SVG・Canvas 2D）

**目的**: 独自形式の描画をやめ、Web の API と要素にする。

**内容**:
- **SVG**: インライン `<svg>`（基本図形・path・`viewBox`・`fill` / `stroke`・グラデーション・`transform`）と画像としての SVG（`<img src="*.svg">`・`background-image`）。
  HTML パーサの外来要素（R9）と合わせ、SVG 名前空間の要素として木に入れる。
- **Canvas 2D**: `CanvasRenderingContext2D` の仕様どおりの部分集合（パス・`Path2D`・塗りと線・3 種のグラデーション・パターン・`drawImage`・
  テキスト・線種・影・`globalAlpha` / `globalCompositeOperation`・変換・`save` / `restore`・`getImageData` / `putImageData`・`isPointInPath`）。
  `Canvas2DContext` のコマンド列（矢印・スパークライン等）は Canvas 2D の上のヘルパに移す。
- **画像**: `object-fit` / `object-position` の確認、`image-set()`、`srcset` / `sizes` の選択。

**参照**: AUI `canvas/CanvasRenderingContext2D` と周辺 20 クラス、`element/Svg`（Java2D で 4 倍ラスタ）、`resource/SvgImageDecoder`。

**完了条件と検証**: WPT `html/canvas/` の採用ケース（ピクセルを読むもの）、SVG の矩形をコーパスに足して一致。

**R8 — 達成（2026-10-26。Phase 5 の 1 段目）**:
- **Canvas 2D**: `core/raster`（`CanvasRenderingContext2D`・`Path2D`・`Stroker`・`Rasterizer`・`Pixmap`・`CanvasGradient` / `CanvasPattern`・22 種の合成・影・`ImageData`・`isPointInPath`）。`<canvas>` は `width` / `height` 属性（既定 300×150）のビットマップを `Canvas2DContext` が持ち、1 枚のテクスチャ（`RasterTextures`）にして `object-fit` で内容ボックスへ収める。`drawArrow` / `drawSparkline` / `drawNodeLink` などは標準コンテキストの上の薄い補助関数に移した（座標はビットマップの画素。以前の CSS px の命令列からの変更）。
- **SVG**: `core/raster/svg`（`SvgRenderer`・`SvgImage`・`SvgSize`）。基本図形・path・`viewBox` / `preserveAspectRatio`・`transform`・`fill` / `stroke` 一式・破線・線形 / 放射グラデーション（`href` 継承）・`clipPath`・`use` / `symbol`・グループ `opacity`・`currentColor`・`style` 属性。インライン `<svg>` は置換要素として組み（子は箱にしない）、画像と同じ経路でラスタライズして置く。`<img src="*.svg">`・`background-image: url(*.svg)`（`data:` / リソースパック / http(s)）も同じ。ラスタライズは描く大きさ × デバイスピクセル比で、内容・寸法・色が同じなら `RasterImageStore` から使い回す。HTML パーサは `svg` / `math` の中で自己終了タグを守る（R9 の外来要素の一部）。
- **画像**: `ImageSources`（`srcset` / `sizes` / `image-set()` の選択、密度で固有サイズを割る）、`ObjectFit`（`object-fit` 5 種と `object-position`。`img` / `canvas` / `video` 共通）。
- **検証**: `RasterCoreTest`・`CanvasRenderingContext2DTest`（45 件）、`SvgRendererTest`（28）、`ImageSourcesTest`（9）、`ReplacedElementsTest`（16）、`ForeignContentTest`（5）、`Canvas2DContextTest`（6）、`RasterBenchmarkTest`。`ArchitectureTest` の M9 は 252 → 275。実機（`runClient`・PrintWindow）で、グラデーション・`clip-path`・破線・グループ `opacity`・`currentColor`・リソース SVG の `contain` / `cover` / `none` + `object-position`・canvas（グラデーション・矢印・円・折れ線・`contain`）・背景 SVG のタイル・`image-set()` を目視し、期待どおり。
- **LIMITATION**: SVG の `mask` / `filter` / `marker` / `pattern` / `image` / `tspan` の個別配置 / `textPath`、ページの `<style>` の規則（要素の `style` 属性と提示属性だけ）。canvas / SVG のテキストは `OutlineFontFace` の書体だけ（`McFontResolver` が返す Minecraft の書体では描かれない）。`srcset` の選択に使うデバイスピクセル比は画面共通の 1 値。SVG の再読み込みはリソースの再ロードで破棄しない。

### R9 — レイアウトとパーサの残り

**目的**: 既存のエンジンの `LIMITATION` と、まだ無い整形コンテキストを埋める。R2 以降ならいつでも進められる。

**内容**:
- **multicol**（`columns` / `column-*`・`column-span`）と、それに要る最小限の断片化。
- **grid**: subgrid、`grid` / `grid-template` ショートハンド、明示グリッドの前の暗黙トラック、baseline 揃え、`auto-fit` の空トラックの畳み、
  span するアイテムの仕様どおりのトラック配分、`dense`。
- **flex**: last baseline、column 方向の `min-height: auto`、不定の高さのコンテナ内のアイテムの `%` 高さを `auto` とする扱い。
- **ブロックとインライン**: ブロックを含むインラインボックスの分割、flex / grid 直下のテキストの匿名アイテム、float の横の行幅を行ごとに測り直す、
  `min-content` / `max-content` / `fit-content` を高さにも。
- **表**: `%` の列幅、`border-collapse` の線種と優先順位、`empty-cells`、`visibility: collapse`。
- **リスト**: `<ol reversed>`、`list-style-image`、`@counter-style`。
- **HTML パーサ**: 書式要素の一覧と adoption agency、SVG / MathML の外来要素、`<template>` の中身を別の `DocumentFragment` にする、
  閉じていない `<!--` で残りの文書を捨てるのをやめる（仕様どおりコメントを EOF まで続ける）。

**参照**: AUI `layout/Grid`（`minmax()` / `repeat()` / `auto-fill`）、`layout/Flex`（baseline）。AUI は table / float / multicol が無いので、多くは独自。

**完了条件と検証**: 各項目のコーパスケースと `HtmlTreeConstructionTest` のケース（html5lib の採用ケースを含む）。台帳の R9 の 4 行と該当の `LIMITATION`・README の行を消す。

### R10 — 開発者ツール

**目的**: R1〜R3 で生きた DOM ができると、外から観測して触れるようになる。デバッグと AI 支援の両方に効く。R2 の後なら並行できる。

**内容**:
- **ゲーム内インスペクタ**: DOM ツリー、選んだ要素の計算値・マッチした規則・ボックスモデル、計算値のライブ編集、ヒットテストでの要素選択。
- **リモートデバッグプロトコル**: CDP の部分集合（`DOM.*`・`CSS.getComputedStyleForNode`・`Runtime` は持たない）を WebSocket で出し、
  MCP ブリッジから文書一覧・スナップショット・検索・計算値・クリック・入力を使えるようにする。ローカルホストのみ・トークン認証。
- **ホットリロード**: リソースの変更を監視して文書を開き直す（クライアント駆動の文書は状態を保ったまま CSS だけ差し替える）。

**参照**: AUI `dev/devtools/*`（`DevToolsController`）、`dev/debug/ExternalDebugServer`・`DebugProtocolSession`、`tools/apricity-mcp/server.mjs`。

**完了条件と検証**: プロトコルの往復テスト（JUnit で WebSocket クライアントを立てる）。`runClient` でインスペクタを開いて計算値を変え、即座に反映されること。

---

# 順序とリスク

- **R0 / R0+（テスト・計測・ラチェット）がすべての最優先前提（Phase 0）。** 機能修正やリファクタリングより先に、差分ベンチマーク・`ArchitectureTest` の番人ラチェット・不具合再現テストを揃える。
- **T8 と R2 / R3 / Surface の残課題を Phase 1 で片付ける。** 複数画面のイベントコールバック上書き・`attrs.put` の直書き・`WebMediaManager` のリダイレクト検証・共有 Surface のサイズ競合を先に直し、土台のバグを無くす。
- **Phase 2（R5 完遂 + R4 残）→ Phase 3（R6 完遂 + 旧 W12）→ Phase 4（R7 完遂）→ Phase 5（R8 / R9 / R10）の順に進める。**
  - `InvalidationSets`（R4）と DOM 変異（R1）を `InteractionState` / `RenderPipeline` に結び、`ComputedStyle` の省メモリ化と非継承プロパティ判定を入れることで、Phase 0 で追加した差分ベンチマークを `< 1.0 ms` に落とす（Phase 2）。
  - 型付きの値（R4）と高速経路（R5）の上に、`RenderKeys` 撤去・`CssSyntax` 順列の `transform`・FBO レイヤー合成・多色シェーダーグラデーションを載せる（Phase 3）。
  - フォントアトラスの VRAM 最適化と `@font-face` の `OutlineFontFace` 統合、UAX #14 / #9、選択・編集を仕上げる（Phase 4）。
  - SVG / Canvas 2D（R8）、表・段組・パーサの残り（R9）、DevTools 拡張（R10）で Web 標準との差を閉じ切る（Phase 5）。
- **H（Headless State）は R1〜R3 の上に独立して載る。** サーバーの状態とテンプレートの分離は完了しており、互換モード（`WebSession`）と Headless State（`UIState`）の両方を同じクライアントのパイプラインで描く。
- **性能**: R0+ のベンチで全文書と差分操作の両方の前後を比べ、回帰は CI で即座に落とす。
- **破壊的変更の通知**: フェーズごとに README に移行メモを書き、`showcase.html`・`guidebook.md`・`config-form.css`・テーマを同じコミットで追従させる。

---

# 検証

## 各フェーズ共通

- `./gradlew build test`（CI と同じ経路）。
- `WebFidelityTest`: 台帳に無い差は回帰として落ち、台帳にあるのに一致した差は「台帳が古い」として落ちる。
  台帳の行が消えることがフェーズの完了条件。
- R0 / R0+ で入れる計算値の列・DOM の振る舞いテスト・ディスプレイリストのゴールデン・全文書および差分性能ベンチ。
- `ArchitectureTest`・`ApiBoundaryTest`（`core` が Minecraft に依存しない・公開 API に内部型が漏れない・M1〜M9 および新規ラチェットが後退しない）。
- `./gradlew runClient` の実機確認と `gametest/WebScreenGameTest`、および [TEST_SPEC_SURFACE.md](TEST_SPEC_SURFACE.md)・[TEST_SPEC_BLOCKS_ITEMS.md](TEST_SPEC_BLOCKS_ITEMS.md)・[TEST_SPEC_MEDIA.md](TEST_SPEC_MEDIA.md)。

## 台帳と README の同期

実装しないと決めた差は 3 点セット（FIDELITY.md・`LIMITATION`・README の "Not supported"）で記録する。
フェーズで消した差は 3 箇所から同じコミットで消す。

---

# 残している既知の差（この計画でも見送るもの）

- **JS エンジンと `<script>`**（方針 1）。DOM API は Java と KubeJS から使う。
- **Shadow DOM とカスタム要素。** 部品化は `mc-*` の宣言的な要素（`CustomTagExpander`）と Java 側のコンポーネントで行う。
- **Minecraft の書体（`minecraft:*`）のグリフ形状に由来する差**（折り返し位置・省略位置・中央寄せの offset）。
  システムの書体と `@font-face`（R7）は Chrome と測れるようにするが、Minecraft のビットマップ書体は原理的に一致しないので台帳に載せない。根拠は FIDELITY.md の「意図的に違うもの」。
- **印刷・ページ分割**（`@page`・ページの断片化）。multicol に要る断片化だけ R9 で入れる。
- **ネットワーク上の文書読み込み**（ナビゲーション・`history`・`fetch`）。文書はリソースパック・データパック・サーバのセッションから来る。
