# EnderFrameLib 技術評価・コードレビュー報告書

対象リポジトリ: `enderframelib-1.21.1` (Version `2.0.0` / NeoForge 1.21.1)  
レビュー実施日: 2026-10-29

---

## エグゼクティブサマリー（総合評価）

**EnderFrameLib** は、CEF（Chromium Embedded Framework）等の巨大な外部ブラウザプロセスに依存せず、**純粋な Java + OpenGL シェーダのみで HTML/CSS レンダリングエンジンを実装し、Minecraft の Screen / HUD / 3D ワールド空間（BlockSurface / Hologram）に統合した野心的な UI ライブラリ**です。

### 総合スコア

| 評価軸 | 評価 | 寸評 |
| :--- | :---: | :--- |
| **仕様準拠・描画精度** | **A** | WHATWG HTML Tree Construction、CSS Cascade 5（`@layer`/`@container`/`:has()`）、OKLCH、UAX #14/#9、実ブラウザ比較テスト（`WebFidelityTest` ±0.5px）は非常に高水準。 |
| **ネットワーク・状態同期設計** | **A-** | `UIState`（Headless State + `KeyTable` VarInt 辞書圧縮）による 0 サーバ DOM・20B 差分同期の設計は Minecraft 向けに極めて合理的。ただし権限・距離検証に抜け穴あり。 |
| **コアエンジンの内部実装・性能** | **C+** | トークン化や一部の型付けは進んでいるものの、`ComputedStyle` の内部表現が依然として `Map<String, String>` であり、カスケード時の大量 Map アロケーションや毎回全ツリーを走査する `DisplayList` 再構築がボトルネック。 |
| **マルチセッション隔離・安全性** | **C** | シングルプレイ（統合サーバ＋クライアント同一 JVM）や複数 Surface 同時描画において、`core/`・`common/` 内の static グローバル状態や `MutationObserver` のリーク、文字列 ID サフィックス除去の衝突バグなど重大な欠陥が残存。 |
| **モジュール構造・保守性** | **C+** | `core/` の Minecraft 非依存性は `ArchitectureTest` で厳格に守られているが、ファイル数上限ラチェット（`M9`）の副作用で 1,300〜1,800 行級の God Class が複数発生している。 |
| **実用性・プロダクション完成度** | **B-** | 本番ビルドにテスト用ブロック・アイテムが常時登録される問題、IME（日本語入力）未対応、動画デコーダの Windows 専属など、実運用ライブラリとして解消すべき課題が明確。 |

---

## 1. 高く評価できる点（強み）

改善点の指摘に入る前に、本ライブラリが他の Minecraft UI MOD（LDLib、ApricityUI、MCEF 等）と比較して明確に優れている点を評価します。

1. **Minecraft から完全に独立した `core/` パッケージと機械的ガバナンス**
   - `com.nishiyu.enderframelib.core` 配下が `net.minecraft.*` / `net.neoforged.*` / `com.mojang.*` を一切インポートしない構造が `ArchitectureTest` と `ApiBoundaryTest` で強制されています。
   - これにより、Minecraft を起動せずに JUnit 上でレイアウト・CSS・DOM・ベンチマークを高速実行できており、テスト容易性が極めて高いです。
2. **`UIState`（Headless State モード）のアーキテクチャ**
   - 従来のサーバ主導 DOM 同期が抱えていた「サーバ側での HTML パース・DOM 保持コスト」を廃し、テンプレートはクライアントの ResourcePack から読み込ませ、サーバは KV 差分（`KeyTable` による VarInt インデックス化）のみを送る設計（Open 101B / Delta 20B）は、大規模マルチサーバの TPS 保護の観点から模範的です。
3. **「Three-Piece Ledger（仕様テスト・実ブラウザ比較・JMH/単体ベンチマーク）」の文化**
   - WPT（Web Platform Tests）ハーネス、Playwright 実測値と ±0.5px で突き合わせる `WebFidelityTest`（138 ケース）、そして 900 行超の `WebScreenGameTest` など、検証基盤の充実度は個人・小規模開発の Minecraft MOD の域を大きく超えています。
4. **GPU SDF シェーダと局所 FBO 合成 (`LayerCompositor`)**
   - 角丸・ボーダー・ボックスシャドウを 1 パスで描く `WebSdfShader` や、要素サイズに応じた局所オフスクリーン FBO（`LayerBounds` + `VramBudget` 32MB 上限 + Near-zero 早期スキップ）など、描画パイプラインの近代化が着実に進んでいます。
5. **外部通信のセキュリティ境界 (`SafeHttpClient` / `VideoStreamServer`)**
   - DNS リバインディング対策（解決済み IP への直接接続と SNI 制御）、ループバック・リンクローカル遮断、256 ビットランダムトークンによるローカルストリーム認証など、Web 要素をゲーム内に持ち込む際の SSRF 対策が真剣に実装されています。

---

## 2. 重大な改善点（P0: バグ・リーク・セキュリティ・設計上の欠陥）

以下は、ライブラリとして他 MOD やマルチプレイ環境に組み込んだ際に**実際のバグ、メモリリーク、またはセキュリティホールとして顕在化する問題**です。最優先での修正を推奨します。

### P0-1. 文字列 ID のサフィックス除去（`ClientWebManager.getBaseNodeId`）による要素 ID 衝突バグ

- **該当箇所**:
  - `src/main/java/com/nishiyu/enderframelib/client/ClientWebManager.java:119-160` (`getBaseNodeId`)
  - `src/main/java/com/nishiyu/enderframelib/core/dom/node/HtmlNode.java:217-220` (`getRenderId`)
  - `src/main/java/com/nishiyu/enderframelib/client/SessionContext.java:81-94`
- **問題の詳細**:
  - `InteractionState` と `ScrollState` は `HtmlNode` インスタンス参照に移行されましたが、`DisplayItem`、`HitTestIndex`、`SessionContext.paintOffsets`、`cssAnimations`、`canvases`、`scenes`、`inputScrollOffsets`、`EnderScreenDelegate` は依然として **文字列の `renderId`** をキーに状態を管理しています。
  - 描画プリミティブ（背景、ボーダー、画像、テキスト等）に `<nodeId>_bg` や `<nodeId>_img` といった派生 ID を付与し、ヒットテストやイベント処理時に `ClientWebManager.getBaseNodeId(renderId)` で末尾のサフィックスを文字列操作で剥がして元の DOM ノード ID を推測しています。
  - **致命的な欠陥**: `SIMPLE_SUFFIXES` には `"_img"`, `"_bg"`, `"_input"`, `"_text"`, `"_check"`, `"_box"`, `"_fill"`, `"_track"`, `"_thumb"`, `"_label"`, `"_placeholder"` などの一般的な単語が含まれています。**UI 作成者が `<div id="user_img">` や `<input id="search_input">`、`<span id="price_label">` のような極めて一般的な `id` 属性を書いた瞬間、`getBaseNodeId("user_img")` は `"_img"` を剥がして `"user"` に変換してしまいます。**
  - その結果、`document.getElementById("user")` が探索され、存在しないためクリック・ホバー・フォーカス・スクロールが全て不発になるか、たまたま存在した `<div id="user">` にイベントが誤爆します。
  - さらに `BASE_NODE_ID_CACHE` は `if (size > 100_000) clear()` という粗い全消去を行っており、動的 ID 生成時にスパイクを生みます。
- **改善策**:
  - `DisplayItem` に文字列加工された `id` を持たせるのをやめ、**`HtmlNode ownerNode` への直接参照**と、`DisplayItemPart`（`BACKGROUND`, `BORDER_TOP`, `SCROLLBAR_THUMB_Y` 等の Enum）をフィールドとして持たせてください。これで `getBaseNodeId` の文字列パースと正規表現、および `BASE_NODE_ID_CACHE` を完全に廃止できます。

---

### P0-2. `MutationObserverManager` のグローバル static リストによるメモリリークとセッション間汚染

- **該当箇所**:
  - `src/main/java/com/nishiyu/enderframelib/core/dom/MutationObserverManager.java:18` (`private static final List<MutationObserver> ACTIVE_OBSERVERS = new CopyOnWriteArrayList<>()`)
  - `src/main/java/com/nishiyu/enderframelib/client/SessionContext.java:436-468` (`close()`)
- **問題の詳細**:
  1. **セッション終了時のメモリリーク**: `ACTIVE_OBSERVERS` が JVM 全体で単一の static リストになっています。UI 側で `MutationObserver.observe(target, ...)` を登録したまま画面を閉じた場合、`SessionContext.close()` は `MutationObserverManager` を一切クリーンアップしないため、**閉じた画面の `MutationObserver` とそれが保持する `HtmlNode`（ひいては `Document` ツリー全体）が GC されず永続的にリークします**。
  2. **O(全オブザーバ数) のクロスドキュメント走査**: あるセッション（または統合サーバ側の `WebSession`）で DOM 変更が 1 回起きるたびに、無関係な別 Surface や別セッションのオブザーバまで毎回すべてイテレートして `isTargetOrAncestor` を辿っています。
- **改善策**:
  - `ACTIVE_OBSERVERS` を static から排除し、`Document` インスタンス（または対象 `HtmlNode`）のフィールドに紐付けてください。`Document` が破棄されれば自動的にオブザーバも GC されます。

---

### P0-3. ネットワーク・セッション認可と距離検証のセキュリティホール

- **該当箇所**:
  1. `src/main/java/com/nishiyu/enderframelib/server/state/UIStateManager.java:83-105` (`handleAction`)
  2. `src/main/java/com/nishiyu/enderframelib/server/WebSessionManager.java:300-316` (`canAccess`)
  3. `src/main/java/com/nishiyu/enderframelib/server/EnderFrameCommands.java:98-106` (`canUseCommand`)
- **問題の詳細**:
  1. **`UIState` アクション送信時の距離・生存チェック欠如**:
     - `UIStateManager.handleAction` は `if (!state.isViewer(player)) return;` しかチェックしていません。
     - 改造クライアントがブロック UI（例: 拠点のチェストや機械）を一度開いて `viewers` に登録された後、画面を閉じるパケット（`__close`）の送信をキャンセルして数千ブロック離れた場所や別ディメンションへ移動した場合でも、**そのブロックに対する `onAction`（アイテム引き出しや設定変更など）を無制限に遠隔実行できてしまいます**。
     - また、対象の `BlockEntity` が既に破壊されていても、MOD 開発者側が毎回手動で `state.close()` を呼ばない限りアクションが届き続けます。
  2. **非所有セッションへの無条件アクセス許可 (`WebSessionManager.canAccess`)**:
     - `canAccess` において、`session.getOwner() == null` かつ `sessionId` が `"block:"` で始まらないセッション（例: 特定の場所に設置されたホログラムや共有セッション）は、**ディメンションや距離のチェックが一切なく `return true;` となり、サーバ内のどこにいるプレイヤーからでもクリックや入力パケットを送り込めます**。
  3. **統合サーバ（シングルプレイ / LAN 公開）における static 状態の衝突**:
     - `StateNbtConverter.ReceivedKeyTables`（`StateNbtConverter.java`）や `EnderFrameServerState` などが JVM static であり、シングルプレイでワールドを入り直した際や LAN 公開時にクライアント側とサーバ側が同一クラスローダ上で状態を共有・干渉するリスクがあります（実際に `WebScreenGameTest.java:276` でテストを通すために手動で `clearReceivedKeyTables()` を呼ぶワークアラウンドが入っています）。
  4. **OP 権限者がサバイバルモードだとコマンドを実行できない不整合**:
     - `EnderFrameCommands.canUseCommand` で `if (player.gameMode.isSurvival()) return false;` がハードコードされているため、`source.hasPermission(2)` を持つサーバ管理者であっても、サバイバルモードのままでは `/enderframe` 管理コマンドが一切使えません（`README.md` の「権限レベル 2 が必要」という記述とも矛盾しています）。
- **改善策**:
  - `UIState` に `Predicate<ServerPlayer> accessValidator`（または `BlockPos` + `ResourceKey<Level>` のバインディング）を持たせ、`handleAction` のたびに距離（例: `player.canInteractWithBlock(pos, 4.0)`）と `BlockEntity` の生存をサーバ側で強制検証してください。
  - `canUseCommand` から `isSurvival()` 制限を削除し、純粋に `source.hasPermission(2)` で判定してください。

---

### P0-4. 本番ビルドへのテスト用ブロック・アイテム・GameTest の混入

- **該当箇所**:
  - `src/main/java/com/nishiyu/enderframelib/EnderFrameLib.java:65-67`
  - `src/main/java/com/nishiyu/enderframelib/gametest/WebScreenGameTest.java:710-929` (`SurfaceDevHarness`)
- **問題の詳細**:
  - ライブラリ MOD のメインエントリポイントである `EnderFrameLib` のコンストラクタが、`WebScreenGameTest.SurfaceDevHarness.init(modEventBus)` を**無条件に呼び出しています**。
  - その結果、このライブラリを前提 MOD として導入した全ての一般ユーザーの環境に、`enderframelib:surface_test_block` と `enderframelib:surface_test_item` というデバッグ用ブロック・アイテムが常時レジストリ登録されてしまいます（JEI/EMI 等のアイテム一覧にも露出します）。
  - また、930 行に及ぶ `WebScreenGameTest` が本番 JAR (`src/main`) にそのまま含まれており、`EnderFrameLib` 本体から `gametest` パッケージへの逆方向依存が発生しています。
- **改善策**:
  - GameTest 専用のソースセット（`src/gametest/java`）に分離するか、少なくとも `FMLLoader.isProduction()` 判定（またはシステムプロパティ `-Denderframelib.devHarness=true`）でガードし、通常プレイ環境ではテスト用ブロック・アイテムを一切登録しないようにしてください。

---

## 3. アーキテクチャ・パフォーマンス上の改善点（P1）

`BENCHMARK.md` と `REFACTORING_PLAN.md` で掲げられているパフォーマンス目標に対し、構造的なボトルネックとなっている箇所です。

### P1-1. `ComputedStyle` の `Map<String, String>` 依存とカスケード時の大量アロケーション

- **該当箇所**:
  - `src/main/java/com/nishiyu/enderframelib/core/css/ComputedStyle.java:38, 97-99`
  - `src/main/java/com/nishiyu/enderframelib/core/css/StyleResolver.java:197-255, 310-365`
  - `src/main/java/com/nishiyu/enderframelib/common/RenderPipeline.java:219-240, 337-358, 480`
- **問題の詳細**:
  - ドキュメントでは「Token から Paint まで型付けされている（Typed from token to paint）」と説明されていますが、実際には `ComputedStyle` の本体は依然として `Map<String, String> properties`（`Collections.unmodifiableMap(new LinkedHashMap<>(properties))`）です。
  - `StyleResolver.resolveDeclarations` が 1 要素のスタイルを解決するたびに、以下の **最低 4〜6 個の `LinkedHashMap<String, String>`** が毎回ヒープ上に新規アロケーションされています：
    1. `result` (`LinkedHashMap`)
    2. `uaValues` (`LinkedHashMap`)
    3. `userValues` (`LinkedHashMap`)
    4. `CascadeState.layerBase` (`LinkedHashMap`)
    5. Origin や `@layer` が切り替わるたびの `new LinkedHashMap<>(result)` スナップショットコピー
    6. `ComputedStyle` コンストラクタ内の `new LinkedHashMap<>(properties)`
  - さらに `RenderPipeline.restyleBoxRecursive` では、再スタイル後の差分判定に `prevStyle.asMap().equals(computed.asMap())`（文字列 Map 同士の全キー・全文字列比較）を行い、`propagationOf` でも継承プロパティを文字列 Map からコピーしています。
  - `BENCHMARK.md` で「500 要素リストの親クラス切り替え（子孫セレクタ）」が **17〜31ms（60fps の 1 フレーム 16.6ms を超過）** かかっている最大の原因は、この文字列 Map の生成・コピー・ハッシュ計算の嵐にあります。
- **改善策**:
  - 標準 CSS プロパティを `CssPropertyId`（Enum、約 120〜150 種）に定義し、`ComputedStyle` の内部表現を「**継承プロパティ群（`InheritedStyleGroup`）**」と「**非継承プロパティ群（`NonInheritedStyleGroup`）**」の固定長配列または型付き構造体（Copy-on-Write 共有）に置き換えてください（Firefox の Servo / Stylo と同じ手法）。
  - カスタムプロパティ（`--*`）のみを遅延生成の Map に入れれば、通常の要素では Map アロケーションが **0 回**になり、`propagationOf` も親の `InheritedStyleGroup` 参照をそのまま渡すだけの O(1) になります。

---

### P1-2. インクリメンタル描画パイプラインの未完部分（リスト挿入 O(N) と DisplayList 全再構築）

- **該当箇所**:
  - `src/main/java/com/nishiyu/enderframelib/common/RenderPipeline.java:186-217, 290-335`
  - `src/main/java/com/nishiyu/enderframelib/core/layout/LayoutBox.java`
  - `BENCHMARK.md` §3, §5
- **問題の詳細**:
  1. **リストへの 1 行挿入が目標（< 1.0ms）の 3〜4 倍（3.2〜4.4ms）かかっている**:
     - `LayoutBox` の座標 `x, y` が親からの相対座標ではなく**ビューポート絶対座標**で保持されており、かつ `measure` キャッシュが親の位置や幅のわずかな変化で無効化されやすいため、500 行リストの途中に 1 行挿入すると、後続する数百行の兄弟要素すべてに対して `layout` が再実行されます。
  2. **`DisplayList` が毎回フルビルドされている**:
     - `StyleDirty`（色変更のみ）や `PaintOrderDirty`（スクロール位置変更のみ）であっても、レイアウト計算こそスキップされるものの、`DisplayListBuilder.build(rootBox)` は**ルートから全ボックスツリーを走査して全 `DisplayItem` を一から作り直しています**。
  3. **`<table>` と Flex stretch での計測キャッシュ不発**:
     - `showcase.html` のリサイズベンチマークで `table` と `flex stretch` が毎回再計測（197 layout / 199 measure）を引き起こしており、複合 UI でのリサイズ性能を押し下げています。
- **改善策**:
  - `LayoutBox` の座標を**親ボックス相対（parent-relative `offsetX, offsetY`）**に変更してください。これにより、リストに 1 行挿入した際、後続の兄弟要素は「サイズ不変なら `offsetY += insertedHeight` するだけ」で済み、子孫の再レイアウトが完全に不要になります（`< 0.5ms` 達成が可能）。
  - `StackingContext` または `LayerCompositor` のレイヤー単位で `DisplayList` のフラグメントをキャッシュし、`PaintDirty` でないサブツリーは前回の `DisplayItem` リストを再利用してください。

---

### P1-3. `core/` および `common/` に散在する static グローバル状態（マルチインスタンス・スレッド安全性の欠如）

- **該当箇所**:
  - `src/main/java/com/nishiyu/enderframelib/core/paint/ImageSources.java:46` (`private static volatile float devicePixelRatio = 1.0f`)
  - `src/main/java/com/nishiyu/enderframelib/core/dom/Bindings.java:24-30` (`i18nResolver`, `jsonListParser`)
  - `src/main/java/com/nishiyu/enderframelib/core/dom/DomUtils.java` (`innerHtmlParser`)
  - `src/main/java/com/nishiyu/enderframelib/common/RenderPipeline.java:42` (`clientBoxRenderer`)
  - `src/main/java/com/nishiyu/enderframelib/common/UiDocumentCompiler.java` (`resourceTextResolver`)
  - `src/main/java/com/nishiyu/enderframelib/common/EnderConfigManager.java` (`resourceResolver`)
- **問題の詳細**:
  - `ArchitectureTest` の `M1` ルールは `client/` パッケージ内の `clearSession` 系 static Map のみを検査していますが、実際には `core/` と `common/` に多数のグローバル可変 static フィールドが存在します。
  - 例えば `ImageSources.devicePixelRatio` は static 変数であるため、**GUI スケールに応じた 2D Screen（DPR=2.0）と、ワールド内の BlockSurface（別解像度/DPR=1.0）を同時に描画すると、`srcset` / `image-set()` の選択結果が互いに上書きし合います**。
  - また、シングルプレイ（統合サーバスレッドとクライアントレンダースレッドが同一 JVM 上で並列動作する環境）において、サーバ側の `WebSession` が DOM 操作やパイプラインを実行すると、クライアント側と static フックやキャッシュを共有して競合する危険があります。
- **改善策**:
  - これらの resolver や `devicePixelRatio` を static フィールドから廃止し、`BrowserServices`（または `DocumentContext` / `LayoutContext`）インターフェースにまとめて `Document` 生成時・レイアウト実行時に DI（依存性注入）する設計に統一してください。

---

### P1-4. `M9`（トップレベルファイル数上限ラチェット）の副作用による God Class 化

- **該当箇所**:
  - `src/test/java/com/nishiyu/enderframelib/core/ArchitectureTest.java:105` (`M9_MAX_TOP_LEVEL_FILES = 283`)
  - 肥大化している主要クラス:
    - `EnderFrameCommands.java` (**1,843 行**)
    - `SessionContext.java` (**1,472 行**)
    - `WebMediaManager.java` (**1,420 行**)
    - `LayoutBox.java` (**1,413 行**)
    - `LayerCompositor.java` (**1,396 行**)
    - `EnderScreenDelegate.java` (**1,364 行**)
    - `RenderPipeline.java` (**1,280 行**)
    - `DisplayListBuilder.java` (**1,277 行**)
- **問題の詳細**:
  - 不要なラッパークラスの増殖を防ぐために導入された `M9_MAX_TOP_LEVEL_FILES` ラチェットが、現在は**「新しい責務を追加する際に、テストが落ちるのを避けるため既存ファイルの中に package-private クラスやメソッドを詰め込む」という逆インセンティブ**として働いています。
  - 特に `SessionContext.java`（1,472 行）は、DOM・CSS・レイアウト・アニメーション・Canvas・Scene・メディア・入力・スクロール・パケット送信・ドラッグ＆ドロップの全てを 1 ファイルで抱え込んでおり、認知負荷が限界に達しています。
  - `EnderFrameCommands.java`（1,843 行）も、コマンド定義・構文解析・プリセット生成・デモ UI・管理操作が単一ファイルに密結合しています。
- **改善策**:
  - **「ファイル数上限（M9）」を廃止し、代わりに「1 ファイルあたりの最大行数上限（例: 800 行超のファイル数をラチェット管理）」に置き換える**ことを強く推奨します。
  - `EnderFrameCommands` をサブコマンド単位（`OpenCommand`, `PresetCommand`, `SurfaceCommand`, `DebugCommand`）に分割し、`LayoutBox` から Canvas/SVG ペイント処理や Flex/Grid 補助ロジックを分離してください。

---

## 4. 機能・UX・運用面の改善点（P2）

Minecraft の UI ライブラリとして実用段階（多くの MOD 開発者やプレイヤーが利用する段階）に進む上で障壁となる点です。

### P2-1. 日本語（CJK）等の IME インライン変換（Composition）未対応

- **該当箇所**:
  - `src/main/java/com/nishiyu/enderframelib/client/gui/EnderScreenDelegate.java` (`charTyped`, `keyPressed`)
  - `src/main/java/com/nishiyu/enderframelib/client/SessionInput.java`
- **問題の詳細**:
  - `<input type="text">` や `<textarea>` は実装されていますが、GLFW / Minecraft の `charTyped` と `keyPressed` のみを処理しており、**IME の未確定文字列（プリエディット / `compositionstart`, `compositionupdate`, `compositionend`）や変換候補ウィンドウの座標通知に対応していません**。
  - 日本語・中国語・韓国語環境でチャット検索や数値以外のテキスト入力を行う際、変換中の文字列が見えない、またはフルスクリーン時に変換候補が出ないという Minecraft 特有の UX 問題が発生します。
- **改善策**:
  - キャレットのスクリーン座標を取得できる API を `SessionInput` に用意し、GLFW/OS レベルの IME 位置制御（あるいは CocoaInput / IMBlocker 等との連携フック、または変換中テキストのアンダーライン描画）をサポートしてください。

### P2-2. `<video>` ネイティブデコーダのプラットフォーム制限と `yt-dlp` 管理

- **該当箇所**:
  - `src/main/java/com/nishiyu/enderframelib/client/renderer/WebMediaManager.java`
  - `src/main/java/com/nishiyu/enderframelib/client/renderer/YtDlpProvisioner.java`
- **問題の詳細**:
  - 同梱されているネイティブ動画デコーダ（`natives/win-x64/lunex_video.dll`）が **Windows x64 専用**であり、macOS（Apple Silicon / Intel）や Linux（Steam Deck 含む）のプレイヤー環境では `<video>` タグが機能しません。
  - また、`YtDlpProvisioner` は初回再生時に GitHub から外部実行バイナリ（`yt-dlp.exe`）を自動ダウンロードしてプロセス実行します。SHA-256 検証は行われていますが、CurseForge や Modrinth の審査ガイドラインでは**「ユーザーの明示的な同意なしに外部から実行可能バイナリ（`.exe`）をダウンロード・実行する挙動」はマルウェア対策規約（Remote Executable Download）に抵触しリジェクトされる可能性が極めて高い**です。
- **改善策**:
  - `yt-dlp` の自動ダウンロードは**デフォルトで `false`（オプトイン設定、またはゲーム内確認ダイアログ承認後のみ有効）**に変更してください。これだけで Modrinth / CurseForge の審査落ちリスクを回避できます。
  - Linux / macOS 用のネイティブライブラリ（`.so` / `.dylib`）を同梱するか、非対応 OS ではクラッシュせず分かりやすいフォールバック表示（または JCodec 等の純 Java デコーダへのフォールバック）を行ってください。

### P2-3. ドキュメントの乖離（Documentation Drift）とバージョニングの不整合

- **該当箇所**:
  - `gradle.properties` (`mod_version=2.0.0`) vs `README.md:38, 868` (`1.0.3`, `pre-1.0 in practice`)
  - `CONTRIBUTING.md:76-91` vs `FIDELITY.md`
  - `REFACTORING_PLAN.md:108` vs `CONTRIBUTING.md:280`
  - `ARCHITECTURE.md:187` (`281`) vs `ArchitectureTest.java:105` (`283`)
- **問題の詳細**:
  - `CONTRIBUTING.md` の「割り切っているところ（制限事項）」セクション（76〜91行目）が古く、Phase 3 で既に解決されたはずの項目（「`opacity` をグループで合成しない」「放射/円錐/斜めグラデーションは矩形の網で近似」「`transform` は固定順のみ」「ボーダーは 4 辺同じ色・太さのみ」）が**未対応のまま記載されており、読み手に誤解を与えます**。
  - `REFACTORING_PLAN.md` の 108 行目には「`YtDlpProvisioner` の自動ダウンロードを撤去した」と書かれている一方、実際のコードと `CONTRIBUTING.md` では自動ダウンロードが復活・維持されており、設計文書間で矛盾が生じています。
  - バージョン表記が `2.0.0`（`gradle.properties` / Git tag）、`1.0.3`（`README.md`）、`1.2`（`@ApiStatus.AvailableSince`）に分裂しています。

---

## 5. 改善ロードマップ提言（優先順位付きアクションアイテム）

### Phase A: 即時修正すべき項目（バグ・セキュリティ・配布審査対策）
- [x] **1. 本番環境からの `SurfaceDevHarness` 分離** (`EnderFrameLib.java`, `WebScreenGameTest.java`)
  - 本番ビルドで `surface_test_block` / `surface_test_item` が登録されないよう開発環境判定または別ソースセットへ隔離する。
- [x] **2. `DisplayItem` の `HtmlNode` 直接参照化と `getBaseNodeId` の廃止** (`ClientWebManager.java`, `DisplayItem.java`)
  - `"_img"` や `"_input"` で終わる要素 ID が破壊されるバグを根治するため、文字列サフィックス剥がしを全廃する。
- [x] **3. `MutationObserverManager` の `Document` スコープ化** (`MutationObserverManager.java`, `Document.java`)
  - `ACTIVE_OBSERVERS` static リストを廃止し、セッション終了時の DOM ツリーリークを解消する。
- [x] **4. `UIState` アクションの距離・BlockEntity 生存検証の強制** (`UIStateManager.java`, `WebSessionManager.java`)
  - 遠隔パケット送信による不正操作を防ぐため、`UIState` にアクセス検証述語を組み込む。
- [x] **5. `EnderFrameCommands.canUseCommand` のサバイバル制限撤廃と `YtDlpProvisioner` のデフォルト Opt-in 化**
  - サバイバルの OP がコマンドを使えるようにし、Modrinth/CurseForge 審査基準に合わせて外部 `.exe` 自動取得をデフォルト無効（設定で有効化）にする。

### Phase B: コアエンジンの高速化・メモリ削減
- [~] **6. `ComputedStyle` の型付き構造体化（文字列 Map の廃止）** (`ComputedStyle.java`, `StyleResolver.java`)
  - `CssPropertyId` インデックス配列 + Copy-on-Write 継承グループに移行し、カスケード時の `LinkedHashMap` 大量生成をゼロにする（500 要素子孫セレクタ更新 `< 5ms` を目指す）。
  - **状況（一部のみ実施・目標未達）**: `ComputedStyle` 内部を「登録プロパティの添字配列 + 添字なし宣言（`--*` 等）の遅延 Map」にし、`asMap()` はコピーなしの読み取り専用ビューにした。`resolveDeclarations` の `uaValues` / `userValues` / `layerBase` は `revert` 系の記述か `input` 要素があるときだけ作る。500 行の子孫セレクタ class トグルは約 15ms のままで `< 5ms` は未達（支配的なのはレイアウトの再計測。項目 7 の注記を参照）。未着手: `substituteVariables` / `expandShorthands` / `ValueComputer.compute` の Map 処理、継承プロパティ群の Copy-on-Write 共有。
- [~] **7. `LayoutBox` の親相対座標化と `DisplayList` の部分更新** (`LayoutBox.java`, `RenderPipeline.java`, `DisplayListBuilder.java`)
  - リストへの 1 行挿入時に後続要素の再レイアウトをスキップし、`BENCHMARK.md` の目標値 `< 1.0ms` を達成する。
  - **状況（一部のみ実施・目標未達）**: `ComputedStyle` の差分比較（`isLayoutAffectingChange` / `propagationOf` / `isPositionOnlyChange` / `RenderPipeline` の等価判定）を配列の直接比較に置き換え、1 行追加を約 3.1ms → 約 2.8ms に短縮した。目標の `< 1.0ms` は未達。
  - **構造的な床**: 何も変わっていない 500 行の文書を差分レイアウトし直すだけで 1.6ms かかる（build / `markLayoutChanges` / measure / `resetPlacements` / layout が全ノードをたどるため）。親相対座標化・`DisplayList` の部分更新には、変更のない部分木を飛ばす仕組みが先に要る。
  - **未着手の前提作業**: 部分木スキップは「子孫は clean」と言い切れることが条件だが、dirty フラグを通らない書き込みが残っている（`Bindings` の `node.text` 直接代入、`DomUtils.updateAttribute` の `attrs.put` / `remove` 直接呼び出し。呼び出し元は `SessionContext` / `WebSession` / `WebSessionManager` / `DomMutationRecord`）。`attrs` を書き込み検知付きの Map にし、`text` の書き込みも検知できるようにしてから、dirty 祖先の集合と部分木スキップを入れる必要がある。取りこぼすと画面が古いまま残る。
- [~] **8. Static グローバル状態のコンテキスト集約** (`ImageSources.java`, `Bindings.java`, `DomUtils.java`, `RenderPipeline.java`)
  - `devicePixelRatio` や各種 Resolver を `Document` / `LayoutContext` 単位に隔離し、マルチ Surface・統合サーバでの競合をなくす。
  - **状況（`devicePixelRatio` のみ実施）**: Surface ごとに値が違う `devicePixelRatio` の static（`ImageSources.devicePixelRatio` / `setDevicePixelRatio`）を廃止した。値は文書の `sheet.mediaEnvironment().resolution()` が持ち、レイアウトは `Viewport.devicePixelRatio`、描画はルートの `LayoutBox.devicePixelRatio` 経由で `srcset` / `image-set()` / SVG ラスタライズの密度に使う。`ReplacedElementsTest.devicePixelRatioIsIsolatedPerDocument` が 2 文書の交互描画で干渉しないことを確かめる。
  - **据え置き（判断）**: `Bindings.i18nResolver` / `i18nFormatter` / `jsonListParser`、`DomUtils.innerHtmlParser`、`RenderPipeline` / `UiDocumentCompiler` の `clientBoxRenderer` / `intrinsicSizeResolver` は、起動時に 1 回差すプロセス共通のサービスで Surface ごとに値が変わらない（競合しない）ため、文書単位へは移していない。差し替えるテストが多く、移すなら注入の API 設計が先に要る。

### Phase C: 保守性・開発体験・ドキュメント整備
- [ ] **9. `M9` ラチェットの「ファイル行数上限ラチェット」への転換と God Class 分割** (`ArchitectureTest.java`)
  - 1,300 行を超える 6 クラス（`EnderFrameCommands`, `SessionContext`, `WebMediaManager`, `LayoutBox`, `LayerCompositor`, `EnderScreenDelegate`）を責務ごとに分割する。
- [ ] **10. ドキュメントの同期とバージョニング統一** (`README.md`, `CONTRIBUTING.md`, `REFACTORING_PLAN.md`)
  - バージョン表記を `2.0.0` に統一し、`CONTRIBUTING.md` の古い制限事項記述を現在の実装（Phase 3 完了状態）に合わせて更新する。