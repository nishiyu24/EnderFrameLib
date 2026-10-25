# 忠実度台帳

ブラウザ（Chrome / Edge / Firefox）の実測値と EnderFrameLib のレイアウト結果が
一致しないと分かっている差異の一覧。

この台帳は 3 点セットの 1 つで、1 件の差異は

1. ここ（ケース単位の実測値つき）
2. 該当コードの `LIMITATION(制約)` コメント
3. README の "Not supported"（利用者から見える差異のみ）

の 3 箇所に同じ内容で載る。規約は [CONTRIBUTING.md](CONTRIBUTING.md) を参照。

## 仕組み

| | |
|---|---|
| コーパス | `src/main/resources/assets/enderframelib/preview/fidelity/cases/*.html`（索引は `cases.txt`。138 ケース） |
| ブラウザ側の期待値 | `src/test/resources/fidelity/expected/*.txt` |
| 台帳（機械可読） | `src/test/resources/fidelity/known-divergences.txt` |
| 比較 | `WebFidelityTest`（幾何は許容誤差 ±0.5px、描画順は完全一致、計算値は文字列の完全一致） |
| ブラウザ側の採取 | `/enderframe devkit` が出す `fidelity-dump.html` |

ダンプ形式は 1 行 1 要素の `<id> <x> <y> <w> <h> <z> sw=… sh=… cw=… ch=… <計算値>…`。
著者が `id` 属性を書いた要素だけを文書順に並べる。ビューポートは全ケース 320×200。
ブラウザ側は各値を `Math.round` した整数、エンジン側は丸める前の float（整数なら小数点なし）を出す。
幾何が同じなら差は 0.5px 以内に収まる。

計算値の列は、ケース全体の `<meta name="fidelity-properties">` / コメントか、要素ごとの
`data-fidelity-properties` で宣言する。`display` / `position` / `box-sizing` / `overflow` は常に出る。
`opacity` / `transform` / `color` のような値は空白を含む（`rgb(10, 20, 30)` /
`matrix(1, 0, 0, 1, 0, 0)`）ため、空白・引用符・バックスラッシュを含む値は二重引用符で包む。
ブラウザ側（`fidelity-dump.html` の `formatValue`）とエンジン側（`FidelityDumper.quoteValue`）は
同じ規則で、`FidelityDumper.splitFields` が引用符の内側の空白を保ったまま列に分ける。
宣言した列が期待値に 1 つも無い場合は、比較が空回りするので `WebFidelityTest` が落とす。

`z` は描画順の列で、要素の矩形の**中心**で、その要素より上に描かれている id 付き要素の数
（0 = 最前面）。中心が自分に当たらない（大きさ 0・ビューポート外・祖先の `overflow` で
切られている）ときは -1。ブラウザ側は同じ点の `elementsFromPoint`、エンジン側は
`DisplayListBuilder.paintOrder`（描画と同じ並べ替え）から数える。大域的な順位にしないのは、
重ならない要素どうしの前後がブラウザから観測できないため。
行に分かれたインラインボックスは、矩形が断片を囲む矩形（`getBoundingClientRect` と同じ）で、点には断片で
当たる（断片の間の隙間には当たらない）。

台帳に無い差異が出ればテストは**回帰**として落ち、台帳にあるのに一致した差異も
**台帳が古い**として落ちる。後者が各フェーズの完了条件になる。

```bash
./gradlew test --tests '*WebFidelityTest'
./gradlew test --tests '*WebFidelityTest' -Dfidelity.dump=true   # 現状の出力を build/fidelity へ
```

ブラウザ側は `fidelity-dump.html` を開いて「測定する」を押すか、ヘッドレスで採る
（`?auto=1` で自動実行し、結果は `<pre id="efl_result">` にも入る）:

```bash
chrome --headless --disable-gpu --hide-scrollbars --virtual-time-budget=8000   --dump-dom "file:///<gameDir>/enderframe_devkit/fidelity-dump.html?auto=1"
```

現在の期待値は Chromium の実測（Edge 155 ヘッドレス。F1 時点で Chrome と Edge が同値であることを確認済み）。
W6 で ua.css を HTML 標準の既定値に置き換えたときに全ケースを採り直した（`body` の margin 8px で 112 ケースが動いた）。

コーパスを書くときの約束:

- **字幅に依存させない。** 寸法は明示し、インラインブロックを並べる行は `font-size: 0` にして
  字幅と strut を消す（`font-size: 0` の行で strut の高さを測るケースだけは例外）。
- **ケースは `body` の中身として書く。** ブラウザ側は `<html><head><style>ua.css</style></head><body>` で包み、
  エンジン側はパーサが html / head / body を暗黙に補う（W7。W6 では `FidelityDumper` が包んでいた）ので、
  両側とも ua.css の `body { margin: 8px }` が効く。ua.css はブラウザ側では作者スタイルとして当たるが、
  W6 以降は HTML 標準の既定値と同じなので、ブラウザ自身の UA と食い違わない。
- **表の行に id を付けない。** Chromium は `table-row` をヒットテストしないので、行の `z` が
  常に -1 になる（行の幾何はセルから分かる）。
- **インラインボックスには縦の padding か border を付ける。** `font-size: 0` の行では内容領域の高さが 0 で、断片が
  すべて高さ 0 だと `getBoundingClientRect` は断片の和ではなく最初の断片を返す（CSSOM View）。
- **インラインボックスの装飾を次のブロックにはみ出させない。** Chromium の `elementsFromPoint` は、後のブロックの
  背景を前のブロックのインライン内容より上として返す（描画の順とは逆）ので、重なると `z` が描画順と食い違う。
  行の間に余白を入れる。中心が行ボックスの境目にちょうど乗るケースも避ける。

## 測れていること / 測れていないもの

F2 で**フォント指標に依存しないテキストの幾何**が入った。
行送り（`line-height` が長さ指定のとき）・強制改行の行数・`white-space: pre` の
行分割は、グリフの送り幅に左右されないのでブラウザと直接突き合わせられる。
コーパスはいまも字幅に依存しない書き方を守る（下の「コーパスを書くときの約束」）。

### コーパスの外でブラウザと突き合わせているもの

コーパス（id 付き要素の矩形・描画順・計算値）とは別に、次のテストが Chrome（Windows）の実測を期待値に持つ。
システムの書体で組むものは Windows でだけ走り、Chrome が使った書体が無い環境では該当の行を飛ばす。

| テスト | 期待値 | 比べるもの |
|---|---|---|
| `ChromeTextMetricsParityTest` | `src/test/resources/fonts/chrome-win-ja-text-metrics*.txt` | システムの書体での文字列の幅と縦の寸法（書体・太さ 100〜900・斜体・16 / 13px・総称ファミリ・字単位の代替、詰めの有無） |
| `ShowcaseChromeParityTest` | `src/test/resources/showcase-chrome/showcase-*.txt` | `showcase.html` の要素の矩形（854×480・1366×768・1920×1080・2560×1440） |
| `ShowcaseWebComparisonTest` | テスト内の実測値 | 854×480 の字幅に依存しない幾何と、字幅の違う書体での重なり・はみ出しの無さ |
| `ToggleBaselineTest` | `src/test/resources/inline-chrome/` | チェックボックス・ラジオ・range を含む行の高さと位置 |
| `FieldsetLegendTest` | `src/test/resources/fieldset-chrome/` | `legend` を枠線に重ねる配置 |
| `HtmlTreeConstructionTest` | テスト内の木 | HTML のツリー構築（下記） |
| WPT の採用ケース | `src/test/resources/wpt/manifest.json` | DOM・UI Events・CSS の振る舞い（38 ケース。dom 22・uievents 4・css 12、台帳 0 行） |

矩形のコーパスの外で、**HTML のツリー構築**もブラウザと突き合わせている（W7）。
`HtmlTreeConstructionTest` の各ケースは、入力を Edge 155 の `DOMParser`（文書）と innerHTML（断片。文脈は
`body` / `tbody` / `tr` / `template`）に通して書き出した木を期待値にしている（暗黙の html / head / body・
暗黙終了・暗黙の `tbody` / `tr` / `colgroup`・表の前に出される要素と文字・文字参照）。
書式要素の再構築（`<p><b>x</p>y` の 2 つ目の `b`）は入れていない（`HtmlParser` の `LIMITATION`）。
属性値の中の `;` の無い文字参照は仕様に従う。Chromium は同じ属性に `&amp;` があると `&copy=` のような
名前も解く（仕様では `=` が続けば解かない）ので、そのケースは期待値にしていない。

測れていないのは次の 2 つ。

- **コーパスでのグリフの送り幅に依存する幾何。** テキストは既定でシステムの書体（`SystemFonts`）で組むので、
  Windows では上の表のテストが Chrome と同じ書体で比べている。ただしコーパスは OS をまたいで回すため
  字幅に依存させず、Minecraft の書体（`minecraft:*` と `@font-face`）で組む文字はブラウザと原理的に一致しない
  （下の「意図的に違うもの」）。
- **操作感。** 状態・入力・アニメーションは別の軸で、ここでは測っていない（`AnimationTestSiteParityTest` は
  幾何・スタイルの読み込み・アニメーションの指定の解決までを見る）。

## 意図的に違うもの（台帳に載せない差）

以下は `font-family` が Minecraft の書体に解決されたとき（`minecraft:default` などを指定したときと、
`@font-face` で登録した書体）だけの話。システムの書体で組む既定の文字には当てはまらない。

- **`font-size` の意味。** Minecraft のフォントは素のサイズで ascent 7px /
  descent 2px（行高 9px）。これを **1em = 9px** と見なしている
  （`BitmapFontFace.MC_UNITS_PER_EM`）。`font-size: 16px` でキャップハイト 12.4px /
  x ハイト 8.9px になり、ブラウザの一般的な UI フォント（16px でそれぞれ約
  11.5px / 8.3px）とほぼ重なる。F2 以前は 1em = 12px 相当（測定の `16/12` と
  描画の `/12`）で、同じ `font-size` でもブラウザより 3 割小さく出ていた。
  グリフの形が違うので文字幅そのものは一致しない。
- **`line-height: normal`。** ブラウザは書体の指標から決める（UI フォントで 1.15 前後）。
  Minecraft のフォントは行間を持たないため、UA 水準の行間 0.15em を
  `BitmapFontFace.MC_LINE_GAP_RATIO` として与え、`normal` = 1.15 にしている。
  F2 以前は 1.375 の固定値だった。

## 既知の差異（24 件）

ほとんどは R0 で測定を拡張し、計算値の列（`display` / `position` / `box-sizing` / `overflow` と、
ケースが宣言した `opacity` / `transform` / `color` など）と
スクロール量（`scrollWidth` / `scrollHeight` / `clientWidth` / `clientHeight`）を
ブラウザ実測と突き合わせるようにしたことで、新たに観測された差異。
計算値はいずれも完全一致しており（`computed-values` が `opacity` / `transform` / `color` を固定する）、
差はスクロールバーの有無によるクライアント幅とリストマーカーの行高に由来する。
相殺されたマージンの scrollHeight への算入は R5（Phase 2）で一致させた（`margin-collapse-bottom`）。
`pseudo-lang-dir-defined#e.x` だけは R4 で足したケースで見えた差（`direction` が未対応）だったが、Phase 4（2026-10-26）で `direction` に対応して解消した。

### R5（CSSOM / CSSOM View・スクロール）
- `flex-column-overflow#scroller.scrollWidth`: ブラウザ 85 / エンジン 100（スクロールバー表示による clientWidth/scrollWidth の縮小）
- `flex-column-overflow#scroller.clientWidth`: ブラウザ 85 / エンジン 100（スクロールバー表示による clientWidth の縮小）
- `flex-column-overflow#content.w`: ブラウザ 85 / エンジン 100（スクロールバーの内側での子の % 幅縮小）
- `flex-column-overflow#content.scrollWidth`: ブラウザ 85 / エンジン 100（スクロールバーの内側での子の % 幅縮小）
- `flex-column-overflow#content.clientWidth`: ブラウザ 85 / エンジン 100（スクロールバーの内側での子の % 幅縮小）
- `overflow-size#scroller.scrollWidth`: ブラウザ 85 / エンジン 100（スクロールバー表示による clientWidth/scrollWidth の縮小）
- `overflow-size#scroller.clientWidth`: ブラウザ 85 / エンジン 100（スクロールバー表示による clientWidth の縮小）
- `overflow-size#content.w`: ブラウザ 85 / エンジン 100（スクロールバーの内側での子の % 幅縮小）
- `overflow-size#content.scrollWidth`: ブラウザ 85 / エンジン 100（スクロールバーの内側での子の % 幅縮小）
- `overflow-size#content.clientWidth`: ブラウザ 85 / エンジン 100（スクロールバーの内側での子の % 幅縮小）

### R4（セレクタ・リストマーカー）
- `list-basic#list.scrollHeight`: ブラウザ 44 / エンジン 40（::marker の配置と行高によるリストの scrollHeight の差）
- `list-basic#a.scrollHeight`: ブラウザ 24 / エンジン 20（::marker の配置と行高による項目の scrollHeight の差）
- `list-basic#b.scrollHeight`: ブラウザ 24 / エンジン 20（::marker の配置と行高による項目の scrollHeight の差）
- `list-nested#outer.scrollHeight`: ブラウザ 44 / エンジン 40（::marker の配置と行高によるリストの scrollHeight の差）
- `list-nested#a.scrollHeight`: ブラウザ 44 / エンジン 40（::marker の配置と行高による項目の scrollHeight の差）
- `list-nested#inner.scrollHeight`: ブラウザ 24 / エンジン 20（::marker の配置と行高によるリストの scrollHeight の差）
- `list-nested#b.scrollHeight`: ブラウザ 24 / エンジン 20（::marker の配置と行高による項目の scrollHeight の差）
- `list-ordered#a.scrollHeight`: ブラウザ 24 / エンジン 20（::marker の配置と行高による項目の scrollHeight の差）

### R7（テキストと国際化）
- `forced-line-breaks#box.scrollHeight`: ブラウザ 62 / エンジン 60（強制改行の末尾行送り・ベースラインの 2px の差。原因は、行の高さ 20px より高い書体の内容領域（Noto Sans JP の ascent + descent = 24px）の下端がスクロール範囲に入ること。エンジンは Phase 4 で内容領域を算入するようにしたが、このコーパスの測定は書体を持たない `FontResolver.FALLBACK` なので 60 のまま。システムの書体では `DirectionLayoutTest` が固定している）

### R9（レイアウトとパーサの残り）
- `table-border-collapse#t.scrollWidth`: ブラウザ 90 / エンジン 88（border-collapse: collapse での表の scrollWidth 算出）
- `table-border-collapse#t.scrollHeight`: ブラウザ 26 / エンジン 24（border-collapse: collapse での表の scrollHeight 算出）
- `table-border-collapse#t.clientWidth`: ブラウザ 92 / エンジン 88（border-collapse: collapse での表の clientWidth 算出）
- `table-border-collapse#t.clientHeight`: ブラウザ 28 / エンジン 24（border-collapse: collapse での表の clientHeight 算出）

## 解消済みの差異

- **R0 / R4（追加 15 ケース、台帳 1 行）**: 計算値の列を比べる `computed-values`（R0）と、現代の CSS の 14 ケース（R4）
  （`layer-order` / `layer-important` / `layer-nested-anonymous` / `nesting-basic` / `nesting-specificity` /
  `container-width` / `container-named-units` / `has-selector` / `nth-child-of` / `pseudo-form-state` /
  `pseudo-lang-dir-defined` / `math-level4` / `units-modern` / `property-registered`）を足した。
  `@layer`・CSS ネスト・`@container`・`:has()`・フォームの状態の擬似クラス・`:lang()` / `:dir()` / `:defined`・
  数学関数 Level 4・新しい単位・`@property` はブラウザと一致し、残ったのは `direction: rtl` の配置の 1 行
  （Phase 4 で解消）だけだった。コーパスは 123 → 138 ケース。
- **W11（0 行）**: 台帳の行は無かった（装飾を持つインラインボックスの矩形・確定した高さの %・強制改行の
  shrink-to-fit・column wrap・`justify-content` の物理的な向きを測るケースが無かった）。8 ケース
  （`inline-box-edges` / `inline-box-wrap` / `inline-box-vertical-align` / `shrink-to-fit-line-breaks` /
  `percent-height-definite` / `position-abs-percent-height` / `flex-column-wrap` / `flex-justify-physical`）を
  Edge 155 ヘッドレスで採って足し、直す前は 8 ケースとも差が出ることを確かめてから直した。123 ケースが一致する。
  断片ごとの枠線と角丸は矩形に出ないので `PaintModelTest` が固定する。
- **W10（0 行）**: 台帳の行は無かった。直したのは色（`#RRGGBBAA` の並び・`#RGBA`・CSS の名前付き色・`color: transparent`）、
  セレクタ（未知の擬似クラスと読めない `An+B` が全要素に一致していたこと、`*-of-type`・`:is()` / `:where()`、
  同じ属性への複数の条件）、角のロングハンド、単位（`vmin` / `vmax` / `ch` / `ex` / `pt` など）と入れ子の数式、
  ショートハンドが書かれなかったロングハンドを初期値に戻すこと。矩形のダンプには出ないので `ValueModelTest`・
  `SelectorMatchingTest`・`ShorthandExpansionTest`・`PaintModelTest` が固定する。115 ケースは変わらず一致する。
- **W9（0 行）**: 台帳の行は無かった。直したのは状態変化の後の再スタイル（兄弟結合子を跨ぐ無効化、
  `::before` / `::after` の正規のカスケードと出現・消滅）と、`<style>` / `<link>` を DOM から文書順に集めること。
  矩形の静的なダンプには出ないので `StateStyleTest` と `BoxTreeTest` が固定する。`<style>` の要素が DOM に残るように
  なっても 115 ケースは変わらず一致する。
- **W8（0 行）**: 台帳の行は無かった。直したのは矩形のダンプに出ない描画とヒットテストの不具合
  （角丸の枠線の塗りつぶし、overflow のロングハンドと包含ブロックに沿ったクリップ、visibility、pointer-events、
  box-shadow、linear-gradient）で、`PaintModelTest` と `HitTestAndOcclusionTest` が固定する。115 ケースは
  変わらず一致する。
- **W7（0 行）**: 台帳の行は無かった。パーサが html / head / body を暗黙に補うようにし、`FidelityDumper` が
  ケースを包むのをやめても全ケースが一致することを確かめた。ツリー構築そのものは `HtmlTreeConstructionTest` が
  ブラウザの木と突き合わせる（上の「測れていること」）。
- **W6（5 行）**: `definition-list`。ua.css を WHATWG HTML の Rendering 節の既定値に置き換え
  （`dl` の `margin: 1em 0`、`dd` の `margin-left: 40px`、`body` の `margin: 8px`、見出しの `em`、表の
  `border-spacing: 2px` など）、装飾は組み込みテーマへ移した。採り直しで `body` の margin が全ケースに
  入ったことで、`transform` が絶対配置の包含ブロックを作らない差（`z-transform-context#inner.x / .y`）が
  新しく見えたので、同じフェーズで直した（`transform` / `filter` を持つ箱は固定配置を含む位置指定の子孫の
  包含ブロックになる）。
- **W5（8 行）**: `z-stacking-context` / `z-negative-in-context` / `z-index-unpositioned` /
  `z-opacity-context` / `z-transform-context`。z-index を親に足し込んで全体を並べ替える方式をやめ、
  ボックスツリーからスタッキングコンテキストの木を組んで CSS 2.2 付録 E の層の順（負の z-index →
  ブロック → float → インライン → z-index: auto / 0 の位置指定 → 正の z-index）に平らにした。
  コンテキストを作るのは z-index を持つ位置指定要素と flex / grid アイテム、`opacity < 1`、`transform`、
  `filter`、`isolation: isolate`、`mix-blend-mode`、fixed / sticky。描画とヒットテスト
  （`HitTestIndex` は描画順の逆を引く）が同じ順序を使う。
- **W4（161 行 + W5 の 1 行）**: block（`float-*` / `margin-collapse-empty` / `z-float-over-block`）、
  inline（`inline-block-margin` / `inline-text-indent` / `vertical-align-*` / `line-height-strut` /
  `inline-nowrap-overflow`）、position（`position-*`）、flex（`flex-*`）、grid（`grid-*`）、
  table（`table-*`）の全行。ブロックフローは位置を高さを決める段で一度だけ決め（float の領域・
  マージンの strut・clear・BFC の回避）、行ボックスに strut・`vertical-align`・`text-indent` を入れた。
  flex は CSS Flexbox §9 の伸縮長の解決（min / max での凍結・`min-width: auto`）と `order` /
  `*-reverse` / `align-content` / auto マージン / `baseline`、grid は §8 の自動配置と暗黙のトラック・
  名前付きの線・areas・内容依存のトラック・揃え、表は格子（`colspan` / `rowspan`）・自動 / 固定の
  列幅・`border-spacing` / `border-collapse`・キャプション・セルの縦揃えを組む。位置指定要素を
  通常フローと float より上の層に描くようにしたので、W5 の `z-positioned-over-flow` も消えた。
- **W3（21 行 + W4 の 54 行）**: `display-flow-root` / `display-contents` / `anonymous-block` /
  `table-anonymous`。要素ツリーからボックスツリーを組む段（`BoxTreeBuilder`）を入れ、
  `display: contents` の子の繰り上げ・匿名ブロック・匿名の表ボックスを作るようにした。
  エンジンは inner display（flow / flow-root / flex / grid / table）で選び、BFC を作る箱
  （`flow-root`・`overflow` が `visible` 以外・flex / grid アイテム・インラインブロック・
  表のセルなど）は子のマージンを貫通させない。匿名表を組むために入れた最小の表レイアウト
  （列幅 = セルの最大、行の高さ = セルの最大、余りは幅の指定が無い列へ列幅の比で配る）と、
  インラインレベルの箱を inner display で不可分に扱うようにした副作用で、W4 に振っていた
  `margin-collapse-bfc` / `inline-flex` / `table-fixed-widths` / `table-auto-width` /
  `table-layout-fixed` / `table-row-height` の全行と、`table-colspan` / `table-rowspan` /
  `table-border-collapse` の一部（合わせて 54 行）も消えた。
- **W2（10 行）**: `border-box-sizing` / `intrinsic-width-keywords`。`border-box` で padding + border が
  指定値を超えるときに内容ボックスを 0 で止め、`width` の `min-content` / `max-content` /
  `fit-content`（と `fit-content(<長さ>)`）を内容から解くようにした。あわせて箱の幾何を
  float にし（`ComputedStyle.length()` / `LayoutBox` / インライン断片）、丸めを描画の 1 箇所に
  寄せたので、許容誤差を ±1px から ±0.5px に縮めた。
- **W1（9 行）**: `vw-font-size` / `css-wide-keywords` / `em-inheritance`。算出値を継承するようにし
  （子孫が `font-size: 2em` を親に掛け直さない）、`font-size` をビューポートで解き、
  CSS 全域キーワードを解くようにした。
- **F4（15 行）**: F1 で起こした 15 行は F4 ですべて解消した（`box-sizing` の既定・隣接と親子のマージン相殺・
  ブロックフローの `row-gap`・`flex-basis`・縦方向の % 余白・% height・絶対配置の包含ブロック・
  `minmax()`）。詳細は git の履歴（旧計画 第 2 期の F4）を参照。

## 一致しているもの（回帰の番人）

次のケースは現状ブラウザと一致しており、台帳に行が無い。崩すとテストが落ちる。

- **block**: 縦積みと `margin-top`（`block-stacking`）、`box-sizing`、隣接兄弟と末子の
  マージン相殺（`margin-collapse` / `margin-collapse-bottom`）、空の箱を貫通する相殺、
  float（左右・折り返し・`clear`・BFC による包含と回避・通常フローより上に描かれること）、BFC（`display: flow-root`・
  `overflow: hidden`）が子のマージン貫通を止めること、インラインとブロックが混ざったときの
  匿名ブロック、負のマージン、
  `margin: auto` の水平中央寄せ、`min-*` / `max-*`、% height・% 余白・% padding、
  stretch された flex / grid アイテムの中の % height、
  `calc()`、`rem`、`vw` / `vh`、`em` の継承（算出値の px を継承する）、`font-size` の `vw`、
  `inherit` / `initial`
- **inline**: インラインブロックの横並びと折り返し・ベースライン揃え、`text-align`、
  長さ指定の `line-height`、`<br>` の強制改行、`white-space: pre`、`inline-flex` が
  前のインライン内容と同じ行に並ぶこと、インラインブロックのマージン、`text-indent`、
  `vertical-align`（top / bottom / middle / 長さ）、行の strut、`nowrap`、インラインボックスの
  開始端・終了端の装飾と行をまたぐ断片、上下の装飾が行を広げないこと、`<br>` で区切る shrink-to-fit の幅
- **flex**: `flex: 1` の等分と `flex-grow` の比、`flex-basis`（固定・%）、
  `justify-content`（5 種）、`align-items` / `align-self`（`baseline` を含む）、`flex-wrap` /
  `wrap-reverse`、`align-content`、`gap`、縦 flex の stretch と `justify-content`、`order`、
  `*-reverse`、`*-reverse` でも反転しない `left` / `right` / `start` / `end`、`column` + `wrap` の列と幅、auto マージン、縮小と `max-width` での停止、`min-width: auto`、スクロールコンテナの
  最小高さ 0、`display: contents` の子が祖父の flex アイテムになること
- **grid**: `fr`、`minmax()`、`gap`、自動配置と暗黙の行・列、`span`、負の線番号、名前付きの線、
  `grid-template-areas`、`repeat(auto-fill)`、`grid-auto-flow: column` / `dense`、`auto` トラック、
  `justify-items` / `align-items` / `*-self`、`justify-content`
- **table**: `display: table-*` の列幅（セルの幅の最大と、表の幅の余りの配分）、行の高さを
  セルの最大に揃えること、`table-cell` だけの並びに補われる匿名の表と行、`table-layout: fixed`、
  `colspan` / `rowspan`、`border-collapse`
- **list**: `ul` / `ol` の字下げと `li` の積み方、入れ子、`dl` / `dt` / `dd`（ua.css の HTML 標準の既定値）
- **position / overflow**: 絶対配置の包含ブロックと shrink-to-fit、静的位置、両側の inset による
  寸法と、それが子の % height の基準になること、padding box 基準の %、`position: relative` が後続を動かさないこと、`position: sticky`、`position: fixed`、
  `overflow: hidden` のクリップ、スクロールコンテナの寸法
- **z-order**: 文書順、位置指定要素どうしの `z-index`、flex アイテムの `z-index`、
  通常フローのブロックより下に描かれる負の `z-index`、後続の通常フローのブロックより上に描かれる位置指定要素、
  スタッキングコンテキストが中の `z-index` を閉じ込めること、コンテキストの中の負の `z-index` が親の背景より
  上に描かれること、位置指定されていないブロックの `z-index` が効かないこと、`opacity` / `transform` が
  コンテキストを作ること、`transform` を持つ箱が絶対配置の包含ブロックになること
- **computed / modern-css**: 計算値の `opacity` / `transform` / `color`、`@layer` の順序と `!important` の反転・
  入れ子と無名の層、CSS ネストとその詳細度、`@container` の幅の問い合わせと名前付きコンテナ・コンテナ単位、
  `:has()`、`:nth-child(An+B of S)`、フォームの状態の擬似クラス、`:lang()` / `:dir()` / `:defined` の照合、
  `round()` / `mod()` / 三角関数、`lh` / `rlh` / `cap` / `svh` などの単位、`@property` で登録した型付きの
  カスタムプロパティ
