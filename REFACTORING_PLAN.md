# EnderFrameLib リファクタリング計画

## この文書について

EnderFrameLib は NeoForge 1.21.1 向けに、HTML トークナイズ → DOM → CSS カスケード →
レイアウト → ディスプレイリスト → Minecraft 描画という本物のブラウザに近い
パイプラインを自前実装したゲーム内 UI フレームワーク。

この文書は**これから何をどの順でやるか**を書く。
旧計画（第 1 期〜第 4 期・フェーズ 1〜7 / F0〜F5 / W0〜W12）はここで閉じ、
同じ領域の先行 MOD である **ApricityUI**（`AUI-snow/`）との比較から 1 から練り直した。
旧計画の各フェーズで何をしたかは git の履歴に残っている。この文書では「出発点」として要約するだけにする。

- 差異の台帳は [FIDELITY.md](FIDELITY.md)
- 規約（コメントの種類、ゴールデン更新、3 点セット）は [CONTRIBUTING.md](CONTRIBUTING.md)
- 利用者から見える制約は [README.md](README.md) の "Not supported"

---

## 現在地

| フェーズ | 内容 | 状態 |
|---|---|---|
| R0 | 測定の拡張（計算値・DOM の振る舞い・ディスプレイリスト・性能） | 完了 |
| R1 | ライブ DOM | 完了 |
| R2 | イベントとフォーム | 実装済み（残: `runClient` / `WebScreenGameTest` での実機観測・IME composition） |
| R3 | ハイブリッド実行モデルと宣言的バインディングの載せ替え | 実装済み（残: `runClient` / `WebScreenGameTest` での実機観測） |
| R4 | CSS の構文・カスケード・値の現代化 | 未着手 |
| R5 | インクリメンタル・パイプラインと CSSOM | 未着手 |
| R6 | 描画の合成化（ディスプレイリスト・レイヤー・transform） | 未着手 |
| R7 | テキストと国際化 | 未着手 |
| R8 | 置換要素（SVG・Canvas 2D） | 未着手 |
| R9 | レイアウトとパーサの残り | 未着手 |
| R10 | 開発者ツール | 未着手 |

### R0 の調査結果（2026-10-19 時点）

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

### R1〜R3 の調査結果（2026-10-19 時点）

`./gradlew test` は 437 件・失敗 0。R1〜R3 の変更は未コミット（作業ツリー差分 238 ファイル、直近コミットは W7）。

**R1 — 達成**: `api/dom/` の Node / Element / Text / Comment / Document / DocumentFragment、`DOMTokenList`・`CSSStyleDeclaration`・
`HTMLCollection`・`NodeList`・`MutationObserver`、`getElementById` の `idMap` 索引、WPT `dom/nodes` 19 件。
**R1 — 残なし**: `InteractionState` と `LayoutContext.ScrollState` は節オブジェクトをキー（弱参照）にした。
`getElementById` は索引だけで引き、未登録なら null を返す（`findNodeById` と線形探索のフォールバックは削除）。
文字列 id で節を引くのはネットワークと描画の境界だけで、`getNodeById` が担う。`NodeKeyedStateTest` で固定。

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
- `runClient` での観測と `gametest/WebScreenGameTest` の確認は未実施。

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
**R3 — 残**:
- `runClient` での観測（HUD を毎フレーム更新してパケットが出ないこと、サーバー駆動画面が従来どおり動くこと）と `gametest/WebScreenGameTest` の確認は未実施（ヘッドレスのテストまで）。
- 再同期はサーバー所有のノードを作り直すため、ホバー・フォーカス・スクロールなどノードに紐づく状態は戻る（細かい更新は変異レコードで、ノードの同一性を保つ）。

R3 を「完了」とする条件は `runClient` での観測。R4 以降には、R1〜R3 の残が片付くまで入らない。

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

### R0 — 測定の拡張

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

**参照**: AUI `wpt/tools/browser-adapter.mjs`（プローブが採る値）、`wpt/README.md`（固定 SHA の運用）。

**完了条件と検証**: 既存の全ケース（138 件）の計算値列がブラウザと一致（新しく見えた差は台帳へフェーズを振って載せる）。
DOM 振る舞いテストの枠に最初の数ケースが入って通る。ベンチの基準値を記録する。

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
  `SessionContext.InvalidationLevel` と `markAllDirty()` の全文書再構築を置き換える。
- **再スタイル**: 汚れた要素と、無効化セットが指す兄弟・子孫だけ。`ComputedStyle` の比較は全マップの `equals` ではなく、
  `PropertyRegistry` の無効化種別ごとに「レイアウトに効く値が変わったか」を見る。
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

**完了条件と検証**: R0 のベンチで、スクロール 1 段・hover の色変化・リストの 1 行追加がレイアウト全体を回さないことを数値で確認。
インクリメンタル更新の結果が全再構築と一致することを、ランダムな変異列で比べるテスト。CSSOM View の WPT 採用ケース。

### R6 — 描画の合成化

**目的**: 軸 D。型付きのディスプレイリストとレイヤー合成で、今は描いていない効果を描く。旧 W12 を含む。

**内容**:
- **ディスプレイリスト**: `DisplayItem` の `events` マップで描画メタ（`mc-radius`・`mc-clip`・`mc-gradient` 等の `RenderKeys`）を渡すのをやめ、
  型付きの項目（矩形・角丸・枠・背景層・影・テキスト・画像・置換要素・クリップ・レイヤー開始 / 終了）にする。
  ヒットテスト（`HitTestIndex`）と忠実度ダンプの z 列は同じリストを読む。
- **transform**: `matrix()` / `matrix3d()`・`skew*`・3D 関数・宣言順の合成・個別プロパティ（`translate` / `rotate` / `scale`）・
  `perspective` / `perspective-origin`・`transform-style: preserve-3d`・`backface-visibility`。正規表現での読み取り（`DisplayListBuilder` の `TRANSLATE_PATTERN` 等）をやめる。
- **レイヤー合成**: FBO のプールを持ち、opacity のグループ合成・`filter`（blur / brightness / contrast / grayscale / hue-rotate / invert / saturate / sepia / drop-shadow）・
  `backdrop-filter`・`mix-blend-mode`・`isolation` を描く。`clip-path`（基本図形・`path()`）と `mask-*` はステンシルかマスク用 FBO で。
  transform 後の空間での非矩形クリップもここで解く。
- **装飾**: ガウスの `box-shadow`（今の段階的な層をやめる）・透明背景で外側の影を抜く・inset 影の角丸、楕円の角、
  角丸に沿う dashed / dotted、`outline` の角丸、インライン断片の背景画像、`background-attachment: fixed / local`。
- **旧 W12**: スクロールのヒットテスト（`ClientScreenInteractionManager.handleScrollLocally`）が祖先の変換の逆行列とクリップを通ること、
  `<mc-scene>` / `<mc-entity>` / `<img src="item:...">` のシザー、スクロール端にかかるテキスト行の部分クリップ。

**参照**: AUI `render/FilterRenderer`（FBO プール・backdrop 用プール）、`render/Mask`、`render/ClipPath`、`render/CssBlendMode`、`style/Transform`。

**完了条件と検証**: R0 のディスプレイリストのゴールデンで、効果ごとの項目が出ること。`PaintModelTest`・`HitTestAndOcclusionTest` の拡張。
`runClient` で効果ごとの見本ページを目視し、スクリーンショットを `guidebook.md` の見本と並べる。
コーパスに transform（2D の合成順・3D の矩形）を足して一致。

### R7 — テキストと国際化

**目的**: 字幅に依存する幾何を測れるようにし、多言語の改行と方向を Web と同じ規則にする。

**内容**:
- **TTF のグリフ描画**: `@font-face` の TTF / OTF をグリフアトラスで実寸に描く（今は Minecraft の FontSet に読ませている）。
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

**完了条件と検証**: `TextShaperTest` を UAX #14 / #9 の公式テストデータで拡張。コーパスに TTF を使うテキストの領域を足して一致。
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

**完了条件と検証**: 各項目のコーパスケースと `HtmlTreeConstructionTest` のケース（html5lib の採用ケースを含む）。該当の `LIMITATION` と README の行を消す。

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

- **R0 がすべての前提。** 各フェーズの完了条件は R0 で入れる測定で書く。
- **R1 → R2 → R3 は直列。** 識別子（ノードのオブジェクト化）→ イベント → 実行モデルの順。逆にすると、
  イベントとバインディングが文字列 id の上に積まれて作り直しになる。
- **R3 が最大の破壊的変更。** `SessionContext` の 3 コピーと旧差分適用エンジンを消し、サーバの更新を変異レコードにする。
  先に互換テストを固定し、旧経路と新経路を並べて 1 経路ずつ移す（旧 W3 の SPI 移行と同じやり方）。
- **R4 は R1 と独立に先行できる**（`core/css` の中で閉じる）。ただし `:has()` と `@container` の無効化は R5 の dirty ビットに乗るので、
  照合は R4、無効化は R5 で仕上げる。
- **R5 は R1 と R4 の後。** 変異（R1）と無効化セット（R4）が dirty ビットの入力。
- **R6 は R4 の後**（型付きの値をディスプレイリストが読む）。R5 の速い経路（transform / opacity）は R6 の合成と合わせて完成する。
- **R7 の TTF 描画は R6 と独立。** bidi と `writing-mode` は R9 のレイアウト変更とぶつかるので、同時に進めない。
- **R8 は R6 の後**（Canvas と SVG はレイヤーとして合成する）。R9・R10 は R2 以降いつでも。
- **性能**: R1 の DOM 化で一時的に遅くなりうる。R0 のベンチで各段の前後を比べ、R5 までに今より速くする。
- **破壊的変更の通知**: フェーズごとに README に移行メモを書き、`showcase.html`・`guidebook.md`・`config-form.css`・テーマを同じコミットで追従させる。

---

# 検証

## 各フェーズ共通

- `./gradlew build test`（CI と同じ経路）。
- `WebFidelityTest`: 台帳に無い差は回帰として落ち、台帳にあるのに一致した差は「台帳が古い」として落ちる。
  台帳の行が消えることがフェーズの完了条件。
- R0 で入れる計算値の列・DOM の振る舞いテスト・ディスプレイリストのゴールデン・性能ベンチ。
- `ArchitectureTest`・`ApiBoundaryTest`（`core` が Minecraft に依存しない・公開 API に内部型が漏れない）。
- `./gradlew runClient` の実機確認と `gametest/WebScreenGameTest`。

## 台帳と README の同期

実装しないと決めた差は 3 点セット（FIDELITY.md・`LIMITATION`・README の "Not supported"）で記録する。
フェーズで消した差は 3 箇所から同じコミットで消す。

---

# 残している既知の差（この計画でも見送るもの）

- **JS エンジンと `<script>`**（方針 1）。DOM API は Java と KubeJS から使う。
- **Shadow DOM とカスタム要素。** 部品化は `mc-*` の宣言的な要素（`CustomTagExpander`）と Java 側のコンポーネントで行う。
- **Minecraft の既定フォントのグリフ形状に由来する差**（折り返し位置・省略位置・中央寄せの offset）。
  TTF を使う文書は R7 で測れるようになるが、既定フォントは原理的に一致しないので台帳に載せない。根拠は FIDELITY.md の「意図的に違うもの」。
- **印刷・ページ分割**（`@page`・ページの断片化）。multicol に要る断片化だけ R9 で入れる。
- **ネットワーク上の文書読み込み**（ナビゲーション・`history`・`fetch`）。文書はリソースパック・データパック・サーバのセッションから来る。
