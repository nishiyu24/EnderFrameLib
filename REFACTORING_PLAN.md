# EnderFrameLib リファクタリング計画

## この文書について

EnderFrameLib は NeoForge 1.21.1 向けに、HTML トークナイズ → DOM → CSS カスケード →
レイアウト → ディスプレイリスト → Minecraft 描画という本物のブラウザに近い
パイプラインを自前実装したゲーム内 UI フレームワーク。

この文書は**現在地と残りの作業**を記録する。
完了したフェーズは「何を達成したか」だけを残し、作業手順は git の履歴に譲る。

- 差異の台帳は [FIDELITY.md](FIDELITY.md)
- 規約（コメントの種類、ゴールデン更新、3 点セット）は [CONTRIBUTING.md](CONTRIBUTING.md)
- 利用者から見える制約は [README.md](README.md) の "Not supported"

---

## 現在地

| 期 | フェーズ | 内容 | 状態 |
|---|---|---|---|
| 第 1 期 | 1〜7 | 内部保守性とテスト基盤 | **完了** |
| 第 2 期 | F0 | 切れている配線を繋ぐ | **完了** |
| | F1 | 差分を測る土台 + UA の単一化 | **完了** |
| | F2 | テキスト測定とシェイピングの単一化 | **完了** |
| | F3 | 状態をパイプラインの入力にする | **完了** |
| | F4 | フラグメントツリーとインライン整形 | **完了** |
| | F5 | 入力・イベント・スクロール・テキスト編集 | **完了** |
| 第 3 期 | W0 | 現状を正しく測る（コーパス拡張・文書の整合） | **完了** |
| | W1 | CSS 値モデルとプロパティレジストリ | **完了** |
| | W2 | float 幾何と使用値 | **完了** |
| | W3 | ボックスツリー構築 | **完了** |
| | W4 | 整形コンテキストの網羅（float / table / flex / grid / sticky） | **完了** |
| | W5 | 描画モデル（スタッキングコンテキスト・border-style・背景） | **完了** |
| | W6 | UA スタイルシートを HTML 標準に | **完了** |
| | W7 | HTML ツリー構築 | 未着手 |

テスト 254 件 / 失敗 0。`./gradlew build test` が CI と同じ経路。
忠実度コーパス 115 ケース（9 領域）がすべてブラウザと一致（幾何は ±0.5px、描画順は完全一致）。
忠実度台帳は **0 行**（W0 で起こした 269 行は W1〜W6 ですべて解消）。
各フェーズの完了条件は、FIDELITY.md の該当フェーズの節の行が台帳から消えること。

### 第 3 期の方針

第 2 期の方針（Web 準拠優先・破壊的変更可・ブラウザ実測で検証）をそのまま引き継ぐ。
第 2 期が「既にある仕組みの食い違いを直す」作業だったのに対し、第 3 期は
**ブラウザにあってこのエンジンに無い構造**（値モデル・ボックスツリー・スタッキング
コンテキスト・表レイアウト・HTML 標準の UA）を入れる。

### 第 2 期の方針（ユーザー確認済み）

1. **Web 準拠を優先し、破壊的変更を取る。** 既存 UI は作り直し前提。
2. **表示（レイアウト・テキスト）と操作感（状態・入力・アニメ）の両方**を対象にする。
3. **ブラウザ実測との差分テストで検証する。** 忠実度を目視判断から外す。

---

## 設計の軸

残りのフェーズはこの 7 つの不変条件に収束させる。各フェーズはこのどれかを立てる作業。

**軸 1 — 測定の出所は 1 つ。**
テキストの幅・高さ・ベースライン・折り返し位置・省略位置は `core/text` が決め、
描画はその結果を**そのまま描く**。→ **F2 で達成**。

**軸 2 — 状態はパイプラインの入力。**
`ElementState` が変わったら再スタイル →（必要なら）再レイアウト → ディスプレイリスト再生成。
状態専用の描画チャネルを持たない。→ **F3**。

**軸 3 — 幾何は float、丸めは描画の 1 箇所だけ。**
テキスト層は F2、箱の寸法（`ComputedStyle.length()` と `LayoutBox` の座標）は **W2 で達成**。
丸めは `UIRenderer.addElement` が辺を丸める 1 箇所だけ。

**軸 4 — 差は台帳に書く。**
実装しないと決めた差異は `FIDELITY.md` + コードの `LIMITATION` + README の
"Not supported" の 3 点セットで記録する。台帳に無い差はテストが落とす。
→ **F1 で仕組みが入り、以降は運用**。W0 で README / `LIMITATION` の陳腐化を正す。

**軸 5 — プロパティの知識は 1 箇所。**
初期値・継承の有無・値の型・アニメーション可否・無効化種別（paint / layout）・
ショートハンド展開を、プロパティごとにレジストリへ 1 回だけ宣言する。
→ **W1 で達成**（`PropertyRegistry`）。各エンジンのキーワードの文字列比較は残っている。

**軸 6 — DOM と箱は別物。**
レイアウトは DOM ではなく**ボックスツリー**を入力にする。匿名ブロック・匿名表ボックス・
`display: contents`・`::marker` は DOM に無い箱で、DOM → `LayoutBox` 1:1 では作れない。
→ **W3 で達成**（`BoxTreeBuilder`）。

**軸 7 — 描画順は木で決める。**
描画順はボックスツリーから組んだスタッキングコンテキストの木を CSS 2.2 付録 E の層の順に平らにしたもの。
描画・ヒットテスト・忠実度ダンプの z 列が同じ並びを読む。→ **W5 で達成**（`UIRenderer.paintTasks`）。

---

## 完了したフェーズ

### 第 1 期（フェーズ 1〜7）

`core/` を Minecraft 非依存の純 Java リーフにし、JUnit とゴールデンテストを通した。
スタイルを `Map<String,String>` から `ComputedStyle` に型化し、
`UIElement` 境界の文字列平坦化（`formatRadii` / `@@HOVER@@` センチネル）を
`RenderMeta` / `ClipRect` / `Corners` / `PseudoStates` に置き換え、
1,000 行超の god クラスを責務ごとに分割。
サーバー側のアクセス制御・ライフサイクル破棄・`api/` の境界も整えた。
層の規則（`core/` のリーフ性、`client/renderer` → `client/gui` の禁止）は
`ArchitectureTest` が守る。

### F0 — 切れている配線を繋ぐ

二重実装のうち「片方が死んでいた」ものを繋いだ。

- **`:hover` / `:active` / `:focus` の復活。** `UIRenderer` が読んでいた `hover-*` の
  キープレフィックス方式は書き手が `StyleSet` に移った時点で消えており、
  `ua.css` の `button:hover` / `input:focus` / `button:active` が全部無効だった。
  `LayoutBox.hoverStyle` / `activeStyle` / `focusStyle` が状態スタイルの受け渡し口。
- グラデーション背景がホバーで消える逆向きの挙動を止めた。
- textarea のキャレット状態を `TextEditState` の 1 箇所に集約（描画側の static 3 本を削除）。
- ホバーイベントの二重発火、スクロール ID の不一致（書き手と読み手で id が違う）を解消。
- `line-height` の描画側コピーを削除し、`text-transform` を測定にも適用。
- `<progress>` / `<meter>` を死にコードだった描画経路に繋いだ。
- `flex` / `overflow` / `inset` ショートハンドを展開（`th, td { flex: 1 }` が no-op で
  表の列幅が決まらなかった）。
- `min-width: min-content` が 0 になる問題（キーワードが長さとして解けず flex 内で
  ボタンが潰れていた）を解消。
- `FormattingContext.isFlexRow()` / `isFlexColumn()` が常に false だった死にコードを修正。
  これで `flex: 1` の等分が効くようになった。

### F1 — 差分を測る土台と UA の単一化

- **UA スタイルシートを `ua.css` の 1 本にした。** プレビュー用の
  `enderframe-devkit.css` が UA 相当の宣言を独自の値で持っていたため
  （`* { box-sizing: border-box }`、`:root { font-size: 14px }`、button / input / table）、
  ブラウザプレビューで作った見た目がゲーム内で再現しなかった。
  DevKit は `ua.css` と組み込みテーマ 3 本を**そのまま同梱**し、
  プレビューページはゲーム内と同じ `<link>` 順（UA → テーマ → 文書）で読む。
  テーマ切り替えもゲーム内と同じ「テーマ CSS の差し替え」。
  `CssResourcesTest` が「プレビュー CSS に要素セレクタが無い」ことを検査する。
- **ブラウザ実測との差分テスト（`WebFidelityTest`）。**
  コーパスは `preview/fidelity/cases/*.html`（索引は `cases.txt`）で、
  テストとエクスポーターが同じ一覧を読む。
  ダンプ形式は 1 行 1 要素の `<id> <x> <y> <w> <h>`。
  期待値は Chrome と Edge のヘッドレスでの実測で、`fidelity-dump.html` が採取する。
  台帳に無い差は**回帰**、台帳にあるのに一致した差は**台帳が古い**として落ちる。
- 付随: `-D` フラグが Gradle デーモンのプロパティになるだけでテスト JVM に
  届いていなかった（CONTRIBUTING に書かれた `-Dgolden.update=true` が無効だった）のを修正。

### F2 — テキスト測定とシェイピングの単一化（軸 1）

`core/text` に測定と整形の出所を作り、描画側から測定を全部取り除いた。

- **`FontFace` / `FontStyle` / `FontResolver` / `BitmapFontFace`。**
  旧 `FontMetrics`（`int getDisplayWidth(String, float scale, boolean)` 1 本）は削除。
  float の送り幅と ascent / descent / lineGap を持つので、ベースライン揃えが可能になった。
  太字を `§l` の前置で測る Minecraft 固有の作法は `McFontResolver` の内側に閉じた。
  `font-family` が**測定側にも通る**（以前はレイアウトが常に既定フォントで測り、
  描画だけが別フォントを選んでいたため `@font-face` のテキストが全部ずれていた）。
- **`TextShaper` が折り返し・空白・省略の唯一の実装。**
  `LayoutMetrics.wrapText`（余裕なし）と `BlockLayoutEngine.breakIntoLines`（4px の余裕つき）を
  統合。`white-space`（`normal` / `nowrap` / `pre` / `pre-wrap` / `pre-line`）、
  `word-break`、`overflow-wrap`、`text-overflow: ellipsis` を実装し、
  CJK を表意文字の間で折れるようにした（行頭・行末禁則つき）。
  `INLINE_WRAP_SLOP = 4` と描画側の「幅の 1.3 倍 + 4px」は削除。
- **描画は結果をそのまま描く。** `WebRenderCore` のテキスト経路から
  `font.width` / `plainSubstrByWidth` による測り直し・省略判定・中央寄せを削除し、
  レイアウトが決めたベースラインと行送りを `RenderMeta` で受け取る。
  `text-align` の offset は `UIRenderer` に移動。
  `snapTextScaleFor2D`（倍率を物理ピクセル 0.5 刻みに丸める）は削除した
  —— 倍率を変えると送り幅が変わり、測定と食い違う。スナップするのは位置だけ。
- **`font-size` の意味を Web に合わせた（破壊的）。**
  マジックナンバー 3 つ（`BASE_FONT_SIZE = 16` / 測定の `16/12` / 描画の `/12.0f`）を消し、
  **1em = Minecraft フォントの素の行箱 9px** に統一。
  同じ `font-size` が従来より約 1.3 倍大きく出る。
  `line-height: normal` は `ascent + descent + lineGap` = 1.15（旧: 固定値 1.375）。
  `line-height: <length>` がフォントサイズに比例するバグも修正。
- 副産物: `LayoutBox.fontSize`（16 相対の倍率）を廃して `style.fontSizePx()` にしたことで、
  `font-size: 150%` が包含ブロックの**幅**の 1.5 倍になるバグ（F4-1 の項目）が消えた。
- インライン断片の進み位置を float で持ち、丸めは座標に落とす 1 箇所だけにした
  （以前は断片ごとに切り上げていたので 1 行 n 語で最大 n px 累積していた）。
- ベースライン揃えを実装（以前は「高さ 8px の箱」を仮定した行内センタリング）。
- `InlineLayoutConsistencyTest` を**各断片の座標とベースラインまで**検証するよう拡張した。
  F4 がこの経路を大きく触るための安全網。

### F3 — 状態をパイプラインの入力にする（軸 2）

状態（`:hover` / `:active` / `:focus` 等）を独立した別チャネル（`LayoutBox.hoverStyle` 等）ではなく、スタイル解決とレイアウトパイプラインの正規の入力にした。

- **`ElementState` ビット集合とセレクタ照合の厳密化。**
  `HOVER`, `ACTIVE`, `FOCUS`, `FOCUS_VISIBLE`, `FOCUS_WITHIN`, `CHECKED`, `DISABLED`, `INDETERMINATE`, `OPEN` を型で表現。
  `:focus` / `:focus-visible` / `:focus-within` を個別のフラグに分離し、マウスクリック由来のフォーカス（`focusVisible = false`）とキーボード Tab 由来のフォーカス（`focusVisible = true`）を明確に区別。
  `:focus-within` は祖先チェーンへの伝搬および子孫探索で照合。
  `:checked` / `:disabled` / `:indeterminate` / `:open` を静的属性だけでなく実行時状態（`InteractionState`）から動的に照合。
  カスケード解決における `base → focus → hover → active` の `putAll` 重ね合わせを廃止し、詳細度＋宣言順の単一カスケードに統合。
  `Specificity` において CSS カスケード仕様どおり UA `!important` が最優先となる順序を確立。
- **状態変化を 3 段階の無効化（restyle / relayout / repaint）として処理。**
  `InteractionState` により状態変化が発生した部分木のみを再スタイルし、親のスタイル変更（color や font-size 等）が子の継承値に正しく伝搬。
  プロパティの変更種別を判定し、色や影などのペイント専用プロパティの変化であれば再レイアウトをスキップして再描画のみで処理（`:hover { padding }` のような幾何変更は確実に再レイアウト）。
  入力ハンドラ内での `processRenderQueue()` 強制フラッシュを撤去し、フレーム描画境界で集約。
  `renderCache` の in-place 書換を廃止し、差分更新時の不必要な `session.close()` を防止。
- **トランジションとアニメーションの一般化。**
  `PseudoTarget` を完全に廃止。再スタイルによる目標値の変化を直接検知して補間。
  `transition-property` による補間対象チャネルのフィルタリングを実装。
  `EasingFunction` を新設し、`cubic-bezier()` 任意制御点評価と CSS 仕様どおりの `ease` 既定値を導入。
  `@keyframes` における `animation-delay`, `iteration-count`, `direction`（`reverse` / `alternate` / `alternate-reverse`）, `fill-mode`（`forwards` / `backwards` / `both`）を実装。
  キーフレームで宣言されたチャネルのみを上書きし、他チャネルのトランジションを破壊しない合成を実現。

---

### F4 — フラグメントツリーとインライン整形（Web 忠実度向上）

表示差の構造的な残りを解消し、**忠実度台帳の 15 行すべてを完全に解消**した（差異 0 件、回帰 0 件）。

- **箱モデルとマージン相殺の Web 仕様準拠。**
  `box-sizing` の既定値を CSS 仕様どおり `content-box` に修正（`box-sizing#outer.w` 解消）。
  隣接兄弟の縦マージン相殺（`collapseMargins`）を実装（`margin-collapse#b.y` 解消）。
  親の上パディング・ボーダーが 0 のときの子の上マージン貫通相殺を実装（`percent-margin#parent.y`, `percent-margin-narrow#parent.y` 解消）。
  ブロックフローでの誤った `row-gap` 加算を撤去（`block-gap#wrap.h`, `block-gap#b.y` 解消）。
- **寸法・パーセント解決と数学関数の厳密化。**
  縦方向の `margin` / `padding` のパーセント基準を親の高さではなく包含ブロック幅（インラインサイズ）に統一（`percent-margin-narrow#child.y` 解消）。
  親が明示的な `height` を持つ場合にその内容高さを子へ伝達してパーセント解決（`percent-height#child.h` 解消）。
  `LengthResolver` に `min()` / `max()` 数学関数の評価ロジックと、括弧ネストを考慮したトークン分割を実装。
- **flex / grid の仕様準拠。**
  `flex-basis` をアイテムの基本サイズ（主軸寸法）として正しく解決（`flex-basis-fixed#a.w`, `flex-basis-fixed#b.x`, `flex-basis-fixed#b.w` 解消）。
  grid のトラックリスト分割時に括弧深度を追跡し、`minmax(min, max)` の列幅解決を実装（`grid-minmax#a.w`, `grid-minmax#b.x`, `grid-minmax#b.w` 解消）。
- **包含ブロック（Containing Block）と絶対配置。**
  `position: static` の親を包含ブロックとせず、直近の非 static 祖先（または初期包含ブロック）を包含ブロックとして絶対配置ボックスの位置・サイズを解決（`abs-containing-block#abs.y` 解消）。
- **インライン整形と空白ノードの維持。**
  `UIParser` で改行を含まないインライン空白テキストノード（`<span>a</span> <span>b</span>` 間等）を維持し、インライン単語間スペースとして正確に配置。
  `ua.css` において `a`, `code`, `span`, `b`, `strong`, `i`, `em` の既定表示を Web 仕様の `inline` に、`li` を `list-item` に統一。
  `inline-flex` / `inline-grid` をインライン整形コンテキストの対象としてサポート。

---

### F5 — 入力・イベント・スクロール・テキスト編集

- **W3C DOM 準拠のイベントモデル（`core/event/`）。**
  Minecraft 非依存の純 Java で `DomEvent`, `EventPhase`, `EventDispatcher`, `StandardDefaultActions` を導入。
  capture → target → bubble のイベント伝播モデルを確立し、コンテナの `onclick` が子のクリックからバブリングで発火。
  `details/summary`, `label[for]`, `checkbox/radio`, `submit/reset`, `a[href]` の既定動作を一元化。
- **ヒットテストとオクルージョン遮蔽。**
  `button`, `a`, `checkbox`, `radio` の `ElementType` を正確に出力。
  不透明な背景色や置換要素を遮蔽物として判定し、下層ボタンへのクリック貫通を防止。
  `pointer-events: none` による透過オプトアウト、`ElementType.TEXT` およびスクロールバー要素（`_sb_`）のヒットテストを実装。
- **スクロールとスクロールバー操作。**
  スクロールバートラックのクリックによる位置ジャンプおよびサムのドラッグ移動によるスクロール追従を実装。
- **フォーカス 1 系統化とテキスト選択モデル。**
  `SessionContext.focusedElementBaseId` と `WebFocusNavigator` を統合し、`:focus` / `:focus-visible` スタイルとフォーカスリングを連動。
  Enter / Space での activate を幾何中心クリックからフォーカスノードへの直接 `click` イベントディスパッチに変更し、重なりによる誤爆を解消。
  純 Java の `TextSelection` モデルを導入し、マウスドラッグによる選択範囲更新と `Ctrl+C` によるクリップボードコピーを実装。

---

### W0 — 現状を正しく測る

- **コーパスを 15 → 115 ケースに広げた。** 領域は block / inline / flex / grid / table /
  list / position / overflow / z-order（索引 `cases.txt` を領域別の節に分けた）。
  字幅に依存しないよう寸法を明示し、インラインブロックの行は `font-size: 0` で字幅と
  strut を消している。期待値は Edge 155 ヘッドレスの実測。
- **ダンプに描画順の列 `z` を足した。** 要素の矩形の中心で上に重なっている id 付き要素の数
  （当たらなければ -1）。ブラウザ側は `elementsFromPoint`、エンジン側は新設の
  `UIRenderer.paintOrder`（描画と同じ並べ替えを通す）から数える。幾何は ±1px、`z` は完全一致。
- 付随: パーサが id の無い要素にも `gen_N` を id 属性として振るため、ダンプが著者の書いていない
  要素まで拾っていた。原文に書かれた id だけを拾うようにした。
- **出た差 269 行をすべて台帳に記録し、担当フェーズを振った**（直してはいない）。
  フェーズの見立てと違ったものを含め、内訳は FIDELITY.md の「既知の差異」。
  見立てになかった不具合として、`position: relative` のずれが後続の兄弟を動かす・
  インラインブロックの左 margin が二重に足される・`width` を持つ flex アイテムが縮まない・
  伸長が `max-width` で止まらない、が出た（いずれも W4）。
- **README "Not supported" と `LIMITATION` を台帳に揃えた。** 解消済みの 8 項目
  （`box-sizing` 既定・マージン相殺・ブロックフローの `gap`・`flex-basis`・% height・
  縦方向の % 余白・包含ブロック・`minmax()`）と、実装済みの `@font-face` を README から消し、
  `StyleResolver` の古い `flex-basis` の `LIMITATION` を削除。新しく測れた差は、
  エンジンごとに 1 つの `LIMITATION`（`LayoutEngine.getEngine` / `BlockLayoutEngine` /
  `FlexLayoutEngine` / `GridLayoutEngine` / `LayoutBox` の位置指定 / `UIRenderer` の z-index /
  `StyleResolver` / `ua.css`）と README の項目にした。
- **見立ての確認。** 「`em` の継承が複利で増える」は再現した（`em-inheritance`）。
  子は親の**カスケード直後の文字列**（`font-size: 2em`）を継承し、自分の親の 32px に掛け直す。
  子孫が自分で `font-size` を指定すると止まるので、測るケースは指定しない子孫を持たせてある。

### W1 — CSS 値モデルとプロパティレジストリ（軸 5）

- **`PropertyRegistry` を新設。** 約 150 のプロパティについて初期値・継承・値の型・
  アニメーション可否・無効化種別・ショートハンドのロングハンドと展開関数を 1 回だけ宣言する。
  `StyleResolver` の `INHERITABLE_PROPS` / `PAINT_ONLY_PROPS` / `expand*` と、
  「ショートハンドを宣言したら先行するロングハンドを消す」手書きの一覧を置き換えた。
  展開関数は `ShorthandExpanders` に移し、`font` / `outline` / `list-style` / `flex-flow` /
  `border-style` / `place-*` を足した。後続フェーズが読むプロパティ（`float` / `clear` /
  `vertical-align` / `order` / `align-content` / `grid-auto-*` / `grid-template-areas` /
  `table-layout` / `border-collapse` / `list-style-*` / `background-*` / `outline-*` など）も登録済み。
- 付随して直ったもの: 後に書いた `flex` が先に書いた `flex-grow` に負けていた（`flex` /
  `gap` / `overflow` / `inset` は先行するロングハンドを消していなかった）。
  解釈できないショートハンドが先行するロングハンドを消していた。
  `border: solid 1px red` の幅が "solid" に、`border: 1px red` の色が無しになっていた。
- **継承するのは算出値。** 子に渡す継承元をカスケード直後の文字列から算出スタイルに替えた
  （`UIParser`）。算出値の段（`ValueComputer`）で `font-size` を親とビューポートで px に、
  他の値の `em` / `rem` を要素自身のフォントサイズで px に、`line-height` の `%` を px に解く。
  `em` の複利（`em-inheritance`）と `font-size: 2vw`（`vw-font-size`）が直った。
  `ComputedStyle.of` が `font-size` を int に切り捨て、0 を無効としていたのも直した。
  `rem` の基準は `<html>` の算出フォントサイズ（以前は常に 16px）。
- **CSS 全域キーワード。** `inherit` / `initial` / `unset` は算出値の段で、`revert` は
  カスケードの中で UA オリジンだけの結果に巻き戻して解く。`all` も扱う。
  `currentcolor` は `color` の算出値に置き換える（`css-wide-keywords` が直った）。
- **`var()` を作り直した**（`VarResolver`）。括弧の深さを数えて引数を切り出すので、
  括弧を含むフォールバックと入れ子の `var()` が効く。循環と未定義（フォールバック無し）は
  算出時に無効として `unset` になる（以前は空文字列に置き換わっていた）。
- **アットルール。** `@media` は条件式を構文として読み、メディアタイプ・`height` /
  `aspect-ratio`・範囲構文・`orientation`・`prefers-color-scheme` / `prefers-reduced-motion`・
  `hover` / `pointer` を評価する。未知の特性は偽（以前は真）。`@supports` は宣言・`selector()`・
  `and` / `or` / `not` を評価する（以前は常に真）。`@import` をシートの先頭でだけ読み、
  メディアリストを評価する（解決は `<link>` と同じ `resourceTextResolver`）。
  高さに依存する条件のため、ビューポートの高さが変わってもシートを組み直す。
- **見送ったもの。** 算出値を値オブジェクト（長さ・色・キーワード・リストの型）にするのは
  やめ、「正規化した文字列 + `ComputedStyle` の型付きアクセサ」に留めた。読み手が
  文字列 API で 170 箇所あり、W2 で `length()` を float にするときに長さの型へ寄せる方が
  書き換えが 1 回で済むため。値の型はレジストリに宣言してある。
- 検証: `ValueModelTest`（22 件）。矩形に出ない振る舞い（色・`revert`・`all`・
  条件付き規則・`@import`）はここで固定している。

### W2 — float 幾何と使用値（軸 3）

- **幾何を float にした。** `LengthResolver` / `ComputedStyle.length()` が端数を落とさず、
  `LayoutBox` の座標・寸法・余白・ボーダー・gap・スクロール範囲、インライン断片、
  エンジン SPI（`LayoutEngine` の利用可能幅・% 基準・内容の原点）、`ScrollExtent` が float。
  `distributeMainAxis` の整数除算、flex の grow / shrink の `(int)` 切り捨て、grid のトラック幅の
  切り捨て、テキスト箱の幅と高さの切り上げ、インライン断片の座標と行高の `Math.round` を除いた。
- **丸めは描画の 1 箇所。** `UIRenderer.addElement` が `UIElement` を作るときに、幅ではなく
  **左右・上下の辺をそれぞれ丸めて**差を取る。端数の幅が並んでも隣と接する
  （`flex: 1` の 3 等分が 33 + 33 + 33 にならず 33 + 34 + 33 になる）。角丸の半径と
  HUD のキャンバス寸法も描画側で丸める。スクロールバーの操作側（`ClientWebManager`）は
  描画と同じ float の式でつまみの位置を求める。
- **使用値。** `box-sizing: border-box` で padding + border が指定値を超えるとき内容ボックスを
  0 で止める（`width` / `height` / `min-*` / `max-*` / `fit-content()`）。`width` の
  `min-content` / `max-content` / `fit-content` / `fit-content(<長さ>)` を内容から解く
  （以前は長さとして解けず 0 px）。台帳の W2 の 10 行（`border-box-sizing` /
  `intrinsic-width-keywords`）が消えた。
- **許容誤差を ±1px → ±0.5px に縮めた。** エンジン側のダンプは丸める前の float を出し、
  ブラウザ側の `Math.round` した整数と比べる。縮めても新しい差は出なかった。
  ゴールデン（`LayoutGoldenTest` / `InlineLayoutConsistencyTest`）は描画結果も含めて差分なし。
  `InlineLayoutConsistencyTest` の行高は書体の 18.4px を丸めずに比べるようにした。
- **見送ったもの。** W1 が「W2 で長さの型へ寄せる」としていた長さの値オブジェクトは入れず、
  `length()` の戻り値を float にするだけに留めた。読み手の書き換えは型の変更だけで済み、
  値オブジェクトにする利点（単位を保ったまま運ぶ）を使う読み手がまだ無いため。
  ブラウザの使用値が行う枠線の幅のデバイスピクセルへのスナップも入れていない（台帳に差が無い）。
- 検証: `SubpixelGeometryTest`（端数が残ること、丸めた矩形が隙間なく並ぶこと）。
- 破壊的変更: `line-height: normal` の行が 18px ではなく 18.4px で進むので、長い文章は
  わずかに高くなる。README に移行メモを書いた。

### W3 — ボックスツリー構築（軸 6）

- **要素ツリーとボックスツリーを分けた。** `LayoutBox.elementChildren` が DOM と 1:1 の要素ツリーで、
  `UIParser` はこちらを作り、再スタイルと継承もこちらを辿る。`LayoutBox.children` はボックスツリーで、
  `BoxTreeBuilder` が要素ツリーから組み、レイアウトと描画はこちらだけを読む。
  `display: contents` の要素は要素ツリーにだけ残り、子が親の箱に繰り上がる。
  ブロックコンテナの中でブロックとインラインが混ざるとインラインの並びを匿名ブロックで包み
  （畳まれる空白だけの並びは捨てる）、表の構造が欠けていると匿名の table / table-row / table-cell を補う
  （CSS 2.2 §9.2.1.1 / §17.2.1）。匿名ボックスは持ち主の継承値だけを受け継ぎ、id は持ち主ごとの通し番号。
- **`display` を outer / inner に分けた**（`LayoutBox.Display`）。2 キーワード構文と `list-item` も読む。
  `LayoutEngine.getEngine` は inner display（flow / flow-root / flex / grid / table）で選び、
  インラインレベルかどうか・不可分なインラインかどうかも display から決める。これで
  `inline-flex` の子がインライン内容として平坦化されていたのが直った（W4 の `inline-flex` が消えた）。
- **BFC。** `BoxMeasurer.establishesBfc` が `flow-root`・`overflow` が `visible` / `clip` 以外・
  flex / grid アイテム・インラインブロック・表のセル・絶対配置・ルートを BFC とし、
  BFC は子のマージンを上下とも貫通させない（W4 の `margin-collapse-bfc` も消えた）。
- **最小の表レイアウト**（`TableLayoutEngine`）。匿名表を組むために入れた。列幅はセルの幅の最大、
  行の高さはセルの最大でセルは行の高さまで伸び、表に指定幅があれば余りを幅の指定が無い列へ
  列幅の比で配る。W4 の表のケースのうち `colspan` / `rowspan` / `border-collapse` に依らない行が消えた。
  HTML の `<table>` は ua.css が flex の行として組んだまま（W4 で切り替える）。
- **`::marker` と CSS カウンタ。** `list-item` に匿名のマーカー箱を作る。`outside` は通常フローの外で、
  `BlockLayoutEngine` がリスト項目の左外に最初の行のベースラインで揃えて置く。`inside` は先頭の
  インライン内容。`list-style-type`（記号・decimal 系・alpha / latin / roman / greek・文字列）、
  `counter-reset` / `counter-increment` / `counter-set`（レジストリに登録）と暗黙の `list-item`、
  `<ol start>` / `<li value>` を数える。ua.css に `ol { decimal }`・入れ子の `ul` の circle / square・
  `counter-reset: list-item` を足した。
- **生成内容の評価を `BoxTreeBuilder` に移した。** 以前はパーサが `content` の前後の引用符を外すか
  `attr()` 1 つだけを読んでいたので、連結が文字列のまま出ていた。文字列・`attr()`・`counter()`・
  `counters()` の並びを評価する。
- **エンジン SPI を「制約を受け取り、内容の寸法を返す」形にした。** 入力は
  `LayoutContext.ConstraintSpace`（利用可能幅・% の基準・親の整形コンテキスト）、BFC かどうかは
  `BoxMeasurer` が決めて箱に持たせる。エンジンは `measureContent`（内容の幅）・`contentBlockSize`
  （内容の高さ）・`finishLayout`（伸長・stretch・絶対配置の子）を実装し、箱自身の幅と高さ
  （shrink-to-fit なら内容幅 + 装飾、auto なら内容高 + 装飾）は `BoxMeasurer` だけが決める。
  旧 SPI への橋渡しを置いて Table → Block → Flex → Grid の順に 1 つずつ移し、各段で回帰 0・
  ゴールデン差分なしを確かめてから橋渡しを消した。
- 付随: 描画だけの再スタイルは再レイアウトを通らないため、匿名ボックスとマーカーのスタイルを
  `BoxTreeBuilder.restyleGeneratedBoxes` が持ち主から作り直す（`li:hover { color }` でマーカーの色が変わる）。
  `UIRenderer` は匿名ボックスを描かず、子の持ち主の id を匿名ボックスの外の要素のままにする。
  `FidelityDumper` は要素ツリーを辿る（`display: contents` の要素もブラウザ同様 0 矩形で出す）。
- **見送ったもの。** インラインボックスの中のブロックでインラインを分割しない・flex / grid 内の
  テキストを匿名アイテムで包まない・`::marker` のセレクタ照合と `list-style-image`・
  `<ol reversed>`（いずれも README の "Not supported" と `BoxTreeBuilder` の `LIMITATION`）。
  エンジンが返すのは内容の寸法までで、位置は従来どおり `arrangeChildren` が `LayoutBox` に書く
  （独立したフラグメントの木にはしていない。読み手のレンダラ・ヒットテストが `LayoutBox` を読むため）。
- 検証: `BoxTreeTest`（12 件。箱の組み方、マーカーの文字列とカウンタ、生成内容、描画だけの再スタイル）。
  `LayoutGoldenTest` / `InlineLayoutConsistencyTest` は差分なし。
- 破壊的変更: リスト項目にマーカーが出る。インラインとブロックが混ざった内容は重ならずに積まれ、
  BFC の箱では子のマージンが外へ抜けなくなる。README に移行メモを書いた。

### W4 — 整形コンテキストの網羅

- **block**（`BlockLayoutEngine` を作り直した）。子の位置を高さを決める段（`contentBlockSize`）で
  内容ボックス相対に一度だけ決め（`FlowState`）、`arrangeChildren` は平行移動するだけにした。
  以前は配置の段で「直前の子の下端」から積み直していて、`position: relative` のずれが後続の兄弟を
  動かしていた。
  - **float / clear。** BFC ごとに float の領域（`FloatSpace`）を持ち、BFC を作らない子孫は
    親の領域を共有する（子の高さを領域の中で解き直す）。float は CSS 2.2 §9.5.1 の規則で置き、
    行は float の横の残り幅に入り、BFC の箱は float を避けて残り幅に縮むか下へ下がる。BFC は自分の
    float を高さに含める。float と絶対配置は blockify する（`Display.blockified`）。
  - **マージン相殺を strut にした**（正の最大 + 負の最小）。空の箱は上下のマージンが自分を貫通して
    前後と畳まれる。親子の相殺は解き直すたびに相殺前のマージン（`ownMarginTop` / `-Bottom`）から始める。
  - **行ボックス。** strut（ブロックの書体と `line-height`）、`vertical-align`（baseline / top / bottom /
    middle / sub / super / text-top / text-bottom / 長さ / %）、`text-indent`、不可分なインラインの
    ベースライン（最後の行、無ければ下マージンの端）、`nowrap` の行でインラインブロックを折り返さない。
    インラインブロックの左マージンが二重に足されていたのも直した。
- **position。** inset が両方 auto の軸は静的位置（ブロックフローが記録する）に置き、両側の inset で
  `width` / `height: auto` を決め、包含ブロックは padding box。過剰制約では left / top が勝つ。
  `position: sticky` は最も近いスクロールコンテナのスクロールポートと親の内容ボックスの間でずらす。
  インラインボックスの中の絶対配置も置かれるようになった（以前は平坦化されて位置が決まらなかった）。
- **flex**（`FlexLayoutEngine` を作り直した）。CSS Flexbox §9: `order` で並べ、仮の主軸の寸法で行に分け、
  伸縮長を min / max での凍結つきで解き、`min-width: auto`（指定値と内容の最小幅の小さい方、
  スクロールコンテナは 0）を入れた。`*-reverse` / `wrap-reverse`、`align-content`（normal は stretch）、
  auto マージン、`baseline`。親が決めた寸法で子を測り直すため `LayoutBox.overrideWidth` /
  `overrideHeight` を足した（BoxMeasurer は指定値と内容よりこちらを使う）。
- **grid**（`GridLayoutEngine` を作り直した）。CSS Grid §8 の自動配置（`grid-auto-flow` の row / column /
  dense、行・列が固定されたアイテムの先置き）、暗黙のトラック（`grid-auto-rows` / `-columns`）、線の解決
  （番号・負の番号・名前・`span`・areas の `-start` / `-end`）、トラックの定義（`min-content` /
  `max-content` / `fit-content()` / `minmax()` / `repeat()` と `auto-fill`・線の名前）、§11 の簡略版の
  寸法（固定 → 内容依存 → 上限まで → fr → auto の stretch）、トラックの `*-content` とアイテムの
  `*-items` / `*-self`・auto マージン。
- **table**（`TableLayoutEngine` を作り直した）。セルの格子（`colspan` / `rowspan`、`rowspan="0"`、
  thead を先頭・tfoot を末尾）、自動レイアウトの列幅（min / max-content、またがるセルの配分、
  表の幅による補間と余りの配分）、`table-layout: fixed`（列要素と 1 行目）、`border-spacing`、
  `border-collapse`（隣り合う枠線を太い方に重ね、両側が半分ずつ持つ。`LayoutBox.collapsedBorders` を
  描画も読む）、キャプション、セルの `vertical-align`。行グループと行は測らず、表が寸法と子の位置を渡す。
  **ua.css の表を flex の行から `display: table` に切り替えた**（装飾は W6 まで残す）。
- **描画の層。** 同じ z-index の中で float を通常フローより上、位置指定要素をさらに上に描く
  （CSS 2.2 付録 E の層 5 / 6）。W5 の `z-positioned-over-flow` も消えた。スタッキングコンテキストは W5。
- 付随: `BoxMeasurer.measure` が前回の高さから測り始めていた（高さは「今の値と内容の高さの大きい方」で
  決まるので、表の行や stretch で伸ばした高さが次の測定に残っていた）。0 から始めるようにした。
  flex / grid のアイテムに block の auto マージンの中央寄せが掛かっていたのも外した。
- **見送ったもの。** float の横の行の幅は行の上端で決める（行が後から高くなっても測り直さない）。
  列方向の flex アイテムの自動最小高さは、指定された高さか内容の高さで近似。grid のトラックの寸法は
  §11 の簡略版（またがるアイテムは等分）で、subgrid・`grid-template` / `grid` ショートハンド・負の方向の
  暗黙のトラック・`baseline` 揃え・`auto-fit` の空トラックの畳み込みは無い。表の列幅の % と、
  行・行グループ・列の枠線の重ね合わせは無い。いずれも README の "Not supported" とコードの `LIMITATION`。
- 検証: 台帳の W4 の 161 行がすべて消え、回帰 0。ゴールデン（`LayoutGoldenTest` /
  `InlineLayoutConsistencyTest`）は差分なし。`FormattingContextTest`（4 件: ua.css の HTML の表と
  `colspan`、セルの `vertical-align: middle`、float の横を流れる行、インラインの中の絶対配置）。
- 破壊的変更: HTML の表の列が内容から決まる（以前は等分）、float が浮く、relative が後続を動かさない、
  `width` を持つ flex アイテムが縮む、インラインブロックのベースライン、行の strut。README に移行メモを書いた。

### W5 — 描画モデル（CSS 2.2 付録 E）

- **スタッキングコンテキストと描画の層。** z-index を親に足し込んで全体を 1 回並べ替える方式をやめ、
  `UIRenderer.paintTasks` がボックスツリーからスタッキングコンテキストの木を組んで付録 E の順に平らにする:
  負の z-index → 通常フローのブロック → float → インラインレベル → z-index: auto / 0 の位置指定要素と
  コンテキスト → 正の z-index。float・z-index: auto の位置指定要素・不可分なインライン・flex / grid アイテムは
  中身ごと描く疑似コンテキストで、中の位置指定要素と本物のコンテキストは親のコンテキストに属する。
  コンテキストを作るのは z-index を持つ位置指定要素と flex / grid アイテム、`opacity < 1`、`transform`、
  `filter`、`isolation: isolate`、`mix-blend-mode`、fixed / sticky。`HitTestIndex` は描画リストの逆順を
  引くので同じ順序になる。台帳の W5 の 8 行が消えた。
- **キャンバスの背景**（Backgrounds 3 §2.11.2）。`html` の背景、それが透明なら `body` の背景がキャンバスを塗り、
  譲った要素は自分の箱に色を描かない（以前は body の色を 2 回塗り、負の z-index が body の背景の下に潜っていた）。
  どちらも背景を持たなければ透明（ゲームの上に重ねる文書は iframe と同じ扱い。以前は #111111）。
- **枠線の算出値と線種。** `border-*-width` / `outline-width` は線種が none / hidden なら 0、線種だけなら
  `medium` = 3px、`thin` / `thick` は 1px / 5px（`ValueComputer`）。描画は幅をレイアウトの値（`box.bT` など）から
  取り、色が無ければ `currentcolor`（以前は透明）。`dashed` / `dotted`（両端が線で終わるよう間隔を均す。
  2px 以上の点は丸）/ `double` / `groove` / `ridge` / `inset` / `outset` を `BoxDecorations` が矩形の片に分ける。
- **輪郭線。** `outline` / `outline-offset` を枠線の外に描く（レイアウトに影響しない）。`outline: auto` を読む。
- **背景のレイヤー。** `background` ショートハンドをロングハンドのリストに展開し（W1 で残していた分）、
  `background-image`（`url()` / `linear-gradient()`）の各レイヤーを `background-size / position / repeat`
  （`space` / `round` を含む）/ `origin` に従ってタイルに並べ、`background-clip` の箱で切る。背景色は最後の
  レイヤーの `background-clip` で塗る。画像の固有サイズは `UIParser.intrinsicSizeResolver` から引く。
- 付随: W3 の `::marker` の箱と別に `li` が描いていた丸（`_dot`）が残っていて、マーカーが 2 重になっていた。消した。
  線種で分けた片と背景のタイルは id に番号を持つので、`ClientWebManager.getBaseNodeId` が持ち主の id に戻す。
- **見送ったもの。** opacity のグループ合成と、変形した子孫を祖先の空間で切るクリップ。どちらも
  `WebRenderCore` に別のフレームバッファかステンシルが要り、ゲームを起動しないと確かめられない。角丸の枠線の
  線種、輪郭線の角丸、インラインの断片の背景画像、グラデーションの途中の色、`background-attachment`、
  flex / grid アイテムの `order` 順の描画も入れていない。いずれも README の "Not supported" とコードの `LIMITATION`。
- 検証: `PaintModelTest`（11 件。付録 E の層、キャンバスへの伝播、枠線の幅の算出値と線種、輪郭線、背景の
  ショートハンドとタイル、`background-clip`）。台帳の W5 の 8 行が消え、回帰 0、ゴールデンは差分なし。
- 破壊的変更: z-index が外へ漏れない、静的なブロックの z-index が効かない、`border-width` だけでは枠が出ない、
  色の無い枠が `currentcolor` で出る、背景の無い文書が透明。README に移行メモを書いた。

### W6 — UA スタイルシートを HTML 標準に

- **ua.css を WHATWG HTML の Rendering 節（§15.3）の既定値に置き換えた。** `body { margin: 8px }`、見出しは `em`、
  `p` / リスト / `blockquote` / `figure` / `pre` の 1em の上下 margin、リストと `dd` の 40px、表の
  `border-spacing: 2px` とセルの `padding: 1px`、`a[href]` の `#0000ee`、`pre` / `code` は装飾なしの monospace、
  `hr` の inset、`fieldset` の groove、`summary` の開閉の三角（`list-style: disclosure-*`）、
  `:focus-visible { outline: auto }`。論理プロパティは物理プロパティに、`:is()` は子孫セレクタの列挙に、
  `:link` は `a[href]` に置き換えてある。標準に無いものはインライン要素の `display: inline`（エンジンが
  display の宣言の無い要素の多くをブロックと推測するため）・フォームコントロールの見た目・`mc-*` の 3 つだけ。
- **装飾を組み込みテーマ 3 本へ移した。** 詰めた余白（`p` / リスト / `li`）、リンク色、`pre` / `code` / `kbd` /
  `blockquote` / 表 / `fieldset` / `dialog` / `hr` の見た目、GitHub Alerts とタスクリスト（Markdown）、
  フォーカスの輪郭線の色、`body { margin: 0 }`。dark は以前の ua.css の値、vanilla と brass はそれぞれの配色。
  Markdown のページは従来どおり dark.css を読む。`config-form.css` は独自テーマでも崩れないよう body の
  margin を自分で 0 にする。
- **フォームコントロールの既定の見た目**（`appearance: auto` 相当）を ua.css の 1 節にまとめ、寸法を Chromium の
  ライトテーマに揃えた（button `padding: 1px 6px`、テキスト入力 `1px 2px`、チェックボックス 13×13 と margin、
  range 129×16、progress 160×16 / meter 80×16）。`transform: scale(0.95)` の押下表現・トランジション・
  `min-width: min-content`（W4 の flex の自動最小幅で不要になった）は外した。
- **忠実度コーパスの土台。** `FidelityDumper` がケースをブラウザ側と同じ `<html><body>` で包むようにした
  （パーサは html / body を暗黙に補わないので、包まないと body の margin がブラウザ側にしか付かない）。
  新しい ua.css で全 115 ケースを Edge 155 ヘッドレスで採り直した（112 ケースが body の margin で動いた）。
  台帳の W6 の 5 行が消えた。
- 付随: 採り直しで `transform` を持つ箱が絶対配置の包含ブロックにならない差が見えた（以前は body が原点に
  あったので座標が偶然一致していた）。`LayoutBox.findContainingBlock` が `transform` / `filter` を持つ祖先を、
  固定配置も含めて包含ブロックにする（CSS Transforms 1 §2）。
- **見送ったもの。** legend を fieldset の枠に重ねる配置、OS の部品としてのフォームコントロール、
  `::marker` の照合と `list-style-image` / `<ol reversed>`（W3 から残っているもの）。README の "Not supported"。
- 検証: 台帳は 0 行、回帰 0。`FormattingContextTest` / `StateStyleTest` の UA の値（表の `border-spacing`、
  button の背景色）を標準の値に更新した。
- 破壊的変更: テーマを読まない文書の見た目が変わる（body の margin、黒い文字、青いリンク、装飾の無い
  `pre` / 表、Chromium の寸法のフォームコントロール）。README に移行メモを書いた。

---

# 残りのフェーズ（第 3 期）

W0〜W6 は完了。残りは W7。
各フェーズの完了条件は原則として「W0 で台帳に記録した行が消えること」で表す。

## 第 3 期の出発点（調査で分かったこと）

- **値モデルが文字列マップ**（W1 で解消）。算出値は `Map<String,String>` で、`inherit` / `initial` /
  `unset` / `revert` / `currentcolor` が無く（`width: inherit` は 0）、`em` の継承は複利で増え、
  `font-size: 2vw` は既定のサイズのままだった。
- **幾何が `int`**（W2 で解消）。`ComputedStyle.length()`、`LayoutBox.x/y/w/h`・余白・ボーダー、
  `LayoutEngine.distributeMainAxis` の整数除算。
- **ボックスツリーが無い**（W3 で解消）。`LayoutEngine.getEngine` は flex / grid 以外を全部
  `BlockLayoutEngine` に落としていた（table / list-item / inline-block も）。
- **整形コンテキストの欠落**（W4 で解消）。 flex: `order` / `*-reverse` / `align-content` / auto margin /
  baseline 揃え。grid: 自動配置・暗黙トラック・areas・名前付き線。block: float / clear / BFC。
  inline: `vertical-align` / `text-indent`。`position: sticky`。表は `ua.css` で flex 行に化けていた。
- **描画モデル**（W5 で解消。opacity のグループ合成と transform 後のクリップは見送り）。z-index は親に足し込む
  だけでスタッキングコンテキストが無かった。`border-style`（dashed / dotted / double）、複数背景と
  `background-size / position / repeat`、transform 後のクリップが無かった。
- **UA が UA ではない**（W6 で解消）。`ua.css` がテーマ的な装飾を持っていた（`h6` の色、`pre` の暗背景、
  リンク色 `#89b4fa`、見出しが `rem` 基準、`table` が flex）。素の HTML がブラウザと違って見えた。
- **HTML パーサ。** 暗黙終了は `p` / `li` 程度。暗黙の `tbody`、`dt` / `dd` / `option` /
  `tr` / `td` の暗黙終了など WHATWG のツリー構築規則が足りない。


## W7 — HTML ツリー構築

- WHATWG のツリー構築の主要規則: 暗黙終了（`p` / `li` / `dt` / `dd` / `option` / `tr` / `td` / `th`）、
  暗黙の `tbody`、表の中に誤って置かれた要素の扱い、文字参照の完全な名前表。
- 暗黙の `html` / `head` / `body`。今は断片の文書に body が無く、ua.css の `body { margin: 8px }` が
  効かない（忠実度コーパスは `FidelityDumper` が包んで揃えている。W6）。

---

# 検証

## 各フェーズ共通

```bash
./gradlew build test          # CI と同じ経路。ArchitectureTest で core のリーフ性を維持
./gradlew runClient           # 実機確認
./gradlew runGameTestServer   # サーバー側ライフサイクルの退行確認
```

## 忠実度

```bash
./gradlew test --tests '*WebFidelityTest'
./gradlew test --tests '*WebFidelityTest' -Dfidelity.dump=true   # 現状の出力を build/fidelity へ
```

ブラウザ側の期待値を採り直す手順は [FIDELITY.md](FIDELITY.md)。
**台帳の該当行が消えることが、第 3 期の各フェーズの完了条件**（W0 で行を起こす）。

## ゴールデン

`LayoutGoldenTest` / `InlineLayoutConsistencyTest` は**意図した差分だけ**が出ること。
`-Dgolden.update=true` で更新したら `git diff` を目視（CONTRIBUTING の規約どおり）。

## 台帳と README の同期

差異を 1 つ埋めるごとに、同じコミットで
`FIDELITY.md` の行・コードの `LIMITATION`・README の "Not supported" を更新する。
逆に新しく「やらない」と決めた差異は 3 点セットで記録する。
`WebFidelityTest.testEveryLedgerRowIsDocumented` が台帳と FIDELITY.md のずれを検出する。

---

# 順序とリスク

## 第 3 期

- **W0 がすべての前提（完了）。** 各フェーズの完了条件は台帳の行で表してある。
- **W1 → W2 → W3 は直列。** 値の型 → 幾何の型 → 箱の構造の順。逆にすると、
  後のフェーズが古いシグネチャ（文字列の値・`int` の幾何）の上に積まれる。
  W4 は W3 のボックスツリーの上に乗る。
- **W5 と W6 は完了。** W6 は W5 の `border-style`（hr の inset・fieldset の groove）の上に乗った。
  W6 で期待値を採り直すと body の margin で全ケースの座標が動くので、採り直しの前後で台帳の行の出入りを
  確かめた（W6 の 5 行が消え、`transform` の包含ブロックの 2 行が新しく見えて同じフェーズで直した）。
- **W7 は独立**（`core/dom` だけ）。いつ入れてもよい。
- **W3 の SPI 移行が最大のリスクだった（完了）。** 旧 SPI への橋渡しを置き、エンジンを 1 つずつ移して
  各段で回帰 0 とゴールデン差分なしを確かめた。
- **破壊的変更の通知**: UA の刷新（W6）・float 幾何（W2）・表レイアウト（W4）は既存 UI の
  見た目を変える。フェーズごとに README に移行メモを書き、`showcase.html` / `guidebook.md` /
  `config-form.css` / テーマ 3 本を同じコミットで追従させる。

## 第 2 期（完了済み・記録のみ）

F3 と F4 は並行、F4 は幾何 → SPI → エンジン移行の順、F5 はイベントモデルを先に入れた。
`box-sizing` 既定・`display: inline` 化・空白保持・`font-size` 換算の移行メモは README にある。

## 未着手のまま残している既知の差（台帳に行が無いもの）

台帳は「コーパスで測れた差」だけを持つ。W0 で大半はコーパスに入り台帳の行になった。
次はコーパスの形式（矩形と描画順）では測れないが分かっている差で、該当フェーズで扱う。

- `::marker` のセレクタを照合しない・`list-style-image` と `<ol reversed>` が無い（マーカーは
  id を持てないので矩形に出ない。マーカーと CSS カウンタ自体は W3 で入った。フェーズ未定）
- transform 後の空間でクリップしていない・opacity をグループで合成しない（W5 で見送り。`WebRenderCore` に
  別のフレームバッファかステンシルが要る）
- 角丸の枠線の線種・輪郭線の角丸・インラインの断片の背景画像（W5 で見送り。描画の見た目で、矩形に出ない）
- 断片の文書に暗黙の body が無い（W7）
- グリフの送り幅に由来する差（折り返し位置・省略位置・中央寄せの offset）は
  **原理的に一致しないので台帳に載せない**。根拠は FIDELITY.md の「意図的に違うもの」。
