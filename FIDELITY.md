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
| コーパス | `src/main/resources/assets/enderframelib/preview/fidelity/cases/*.html`（索引は `cases.txt`） |
| ブラウザ側の期待値 | `src/test/resources/fidelity/expected/*.txt` |
| 台帳（機械可読） | `src/test/resources/fidelity/known-divergences.txt` |
| 比較 | `WebFidelityTest`（幾何は許容誤差 ±0.5px、描画順は完全一致） |
| ブラウザ側の採取 | `/enderframe devkit` が出す `fidelity-dump.html` |

ダンプ形式は 1 行 1 要素の `<id> <x> <y> <w> <h> <z>`。著者が `id` 属性を書いた要素だけを
文書順に並べる。ビューポートは全ケース 320×200。ブラウザ側は各値を `Math.round` した整数、
エンジン側は丸める前の float（整数なら小数点なし）を出す。幾何が同じなら差は 0.5px 以内に収まる。

`z` は描画順の列で、要素の矩形の**中心**で、その要素より上に描かれている id 付き要素の数
（0 = 最前面）。中心が自分に当たらない（大きさ 0・ビューポート外・祖先の `overflow` で
切られている）ときは -1。ブラウザ側は同じ点の `elementsFromPoint`、エンジン側は
`UIRenderer.paintOrder`（描画と同じ並べ替え）から数える。大域的な順位にしないのは、
重ならない要素どうしの前後がブラウザから観測できないため。

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

コーパスを書くときの約束:

- **字幅に依存させない。** 寸法は明示し、インラインブロックを並べる行は `font-size: 0` にして
  字幅と strut を消す（`font-size: 0` の行で strut の高さを測るケースだけは例外）。
- **ua.css の副作用を避ける。** ua.css はブラウザ側では作者スタイルとして当たる。
  `body { overflow: auto }` は `body` をスクロールコンテナにするので、位置指定された祖先の
  外へはみ出す要素はブラウザでだけ切られる。はみ出させたいときは祖先に高さを与える。
- **表の行に id を付けない。** Chromium は `table-row` をヒットテストしないので、行の `z` が
  常に -1 になる（行の幾何はセルから分かる）。

## 測れていること / 測れていないもの

F2 で**フォント指標に依存しないテキストの幾何**が入った。
行送り（`line-height` が長さ指定のとき）・強制改行の行数・`white-space: pre` の
行分割は、グリフの送り幅に左右されないのでブラウザと直接突き合わせられる。

測れていないのは次の 2 つ。

- **グリフの送り幅に依存する幾何。** 折り返し位置・省略位置・中央寄せの offset・
  `line-height: normal` の値は、エンジンが Minecraft のビットマップフォント、
  ブラウザが OS のフォントで測るため原理的に一致しない。
  同じ土台に乗せる手段が無いので、コーパスには入れない
  （`font-size` の換算根拠は下の「意図的に違うもの」を参照）。
- **操作感。** 状態・入力・アニメーションは別の軸で、ここでは測っていない。

## 意図的に違うもの（台帳に載せない差）

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

## 既知の差異（175 件・52 ケース）

W0 でコーパスを領域別に広げて起こした行。各フェーズの完了条件は、そのフェーズの節の行が台帳から消えること。
値は「ブラウザ / エンジン」。`z` は描画順の列（矩形の中心で上に重なっている id 付き要素の数）。

### W4 — 整形コンテキスト（161 件）

- **`margin-collapse-empty`** — 高さ 0 の空ブロックの上下マージンが自分を貫通して前後と相殺しない。
  `margin-collapse-empty#b.y` 30 / 45
- **`float-left`** — `float` が無く、ブロックとして縦に積まれる。
  `float-left#wrap.h` 10 / 60、`float-left#wrap.z` 2 / 0、`float-left#b.x` 100 / 0、`float-left#b.y` 0 / 30、`float-left#after.y` 0 / 50、`float-left#after.z` 1 / 0
- **`float-right`** — `float` が無く、ブロックとして縦に積まれる。
  `float-right#a.x` 200 / 0、`float-right#b.x` 150 / 0、`float-right#b.y` 0 / 30
- **`float-clear`** — `float` / `clear` が無く、ブロックとして縦に積まれる。
  `float-clear#wrap.h` 60 / 90、`float-clear#b.x` 200 / 0、`float-clear#b.y` 0 / 30、`float-clear#cleared.y` 50 / 80
- **`float-containment`** — `float` が無いため、通常フローの親が float を高さに含めてしまう。
  `float-containment#plain.h` 0 / 40、`float-containment#plain.z` -1 / 0
- **`float-wrap`** — `float` が無く、段の折り返しが起きない。
  `float-wrap#wrap.h` 0 / 70、`float-wrap#wrap.z` -1 / 0、`float-wrap#b.x` 100 / 0、`float-wrap#b.y` 0 / 20、`float-wrap#c.y` 30 / 50
- **`float-bfc-avoid`** — `float` が無く、BFC が float の横の残り幅に収まらない。
  `float-bfc-avoid#wrap.h` 20 / 60、`float-bfc-avoid#wrap.z` 1 / 0、`float-bfc-avoid#bfc.x` 100 / 0、`float-bfc-avoid#bfc.y` 0 / 40、`float-bfc-avoid#bfc.w` 200 / 300
- **`inline-block-margin`** — インラインブロックの左 margin が二重に足され、縦 margin がベースライン揃えに入らない。
  `inline-block-margin#a.x` 10 / 20、`inline-block-margin#b.y` 10 / 5
- **`inline-text-indent`** — `text-indent` を読まない。
  `inline-text-indent#a.x` 30 / 0
- **`vertical-align-top-bottom`** — `vertical-align` を読まず、常にベースライン揃え。
  `vertical-align-top-bottom#top.y` 0 / 30
- **`vertical-align-middle`** — `vertical-align` を読まず、常にベースライン揃え。
  `vertical-align-middle#line.h` 45 / 40、`vertical-align-middle#mid.y` 35 / 30
- **`vertical-align-length`** — `vertical-align` を読まず、常にベースライン揃え。
  `vertical-align-length#line.h` 30 / 20、`vertical-align-length#base.y` 10 / 0
- **`line-height-strut`** — 行ボックスに strut が無く、`line-height` が内容の無い行の高さにならない。
  `line-height-strut#line.h` 40 / 20
- **`inline-nowrap-overflow`** — `white-space: nowrap` がインラインブロックの折り返しを止めない。
  `inline-nowrap-overflow#line.h` 10 / 20、`inline-nowrap-overflow#b.x` 60 / 0、`inline-nowrap-overflow#b.y` 0 / 10
- **`flex-align-baseline`** — `align-items: baseline` を読まない。
  `flex-align-baseline#a.y` 20 / 0
- **`flex-wrap-reverse`** — `flex-wrap: wrap-reverse` を読まない。
  `flex-wrap-reverse#row.z` 1 / 0、`flex-wrap-reverse#a.y` 40 / 0、`flex-wrap-reverse#b.x` 0 / 200、`flex-wrap-reverse#b.y` 20 / 0
- **`flex-align-content`** — `align-content` を読まない。
  `flex-align-content#b.y` 60 / 20、`flex-align-content#c.y` 100 / 80、`flex-align-content#d.y` 120 / 100
- **`flex-row-reverse`** — `flex-direction: row-reverse` を読まない。
  `flex-row-reverse#a.x` 250 / 0、`flex-row-reverse#b.x` 180 / 50
- **`flex-column-reverse`** — `flex-direction: column-reverse` を読まない。
  `flex-column-reverse#col.z` 1 / 0、`flex-column-reverse#a.y` 80 / 0、`flex-column-reverse#b.y` 50 / 20
- **`flex-order`** — `order` を読まない。
  `flex-order#a.x` 130 / 0、`flex-order#b.x` 70 / 50、`flex-order#c.x` 0 / 110
- **`flex-auto-margin`** — flex アイテムの auto margin が余りを吸わない。
  `flex-auto-margin#pushed.x` 250 / 300、`flex-auto-margin#pushed.z` 0 / -1、`flex-auto-margin#centered.y` 35 / 20
- **`flex-shrink`** — `width` を持つアイテムが `flex-shrink` で縮まない。
  `flex-shrink#a.w` 175 / 200、`flex-shrink#b.x` 175 / 200、`flex-shrink#b.w` 125 / 200
- **`flex-max-clamp`** — 伸長が `max-width` で止まらず、止まった分を他のアイテムに回さない。
  `flex-max-clamp#a.w` 50 / 150、`flex-max-clamp#b.x` 50 / 150、`flex-max-clamp#b.w` 250 / 150
- **`flex-min-auto`** — `min-width: auto` の下限が内容の最小幅ではなく基本サイズになり、縮むべきアイテムが縮まない。
  `flex-min-auto#b.w` 50 / 150
- **`flex-column-overflow`** — スクロールコンテナの `min-height: auto` が 0 にならず、アイテムが内容の高さまで伸びる。
  `flex-column-overflow#scroller.h` 70 / 300、`flex-column-overflow#content.z` -1 / 0
- **`grid-auto-placement`** — 暗黙の行（`grid-auto-rows`）を作らず、溢れたアイテムの高さが 0 になる。
  `grid-auto-placement#grid.h` 60 / 0、`grid-auto-placement#grid.z` 1 / -1、`grid-auto-placement#a.h` 30 / 0、`grid-auto-placement#a.z` 0 / -1、`grid-auto-placement#b.h` 30 / 0、`grid-auto-placement#b.z` 0 / -1、`grid-auto-placement#c.h` 30 / 0、`grid-auto-placement#c.z` 0 / -1、`grid-auto-placement#d.y` 30 / 0、`grid-auto-placement#d.h` 30 / 0、`grid-auto-placement#d.z` 0 / -1、`grid-auto-placement#e.y` 30 / 0、`grid-auto-placement#e.h` 30 / 0、`grid-auto-placement#e.z` 0 / -1
- **`grid-span`** — `grid-column: span N` と暗黙の行を読まない。
  `grid-span#wide.h` 20 / 0、`grid-span#wide.z` 0 / -1、`grid-span#a.h` 20 / 0、`grid-span#a.z` 0 / -1、`grid-span#b.y` 20 / 0、`grid-span#b.h` 20 / 0、`grid-span#b.z` 0 / -1
- **`grid-line-placement`** — 負の線番号（`1 / -1`）を末尾から数えない。
  `grid-line-placement#b.w` 300 / 100
- **`grid-areas`** — `grid-template-areas` / `grid-area` を読まない。
  `grid-areas#main.x` 100 / 0、`grid-areas#main.y` 20 / 0、`grid-areas#main.w` 200 / 100、`grid-areas#main.h` 40 / 20、`grid-areas#head.x` 0 / 100、`grid-areas#head.w` 300 / 200
- **`grid-named-lines`** — 名前付きの線を読まない。
  `grid-named-lines#a.x` 100 / 0、`grid-named-lines#a.w` 200 / 0、`grid-named-lines#a.z` 0 / -1
- **`grid-repeat-auto-fill`** — `repeat(auto-fill, ...)` を展開しない。
  `grid-repeat-auto-fill#a.w` 75 / 0、`grid-repeat-auto-fill#a.h` 20 / 0、`grid-repeat-auto-fill#a.z` 0 / -1、`grid-repeat-auto-fill#b.x` 75 / 0、`grid-repeat-auto-fill#b.w` 75 / 0、`grid-repeat-auto-fill#b.h` 20 / 0、`grid-repeat-auto-fill#b.z` 0 / -1、`grid-repeat-auto-fill#c.x` 150 / 0、`grid-repeat-auto-fill#c.w` 75 / 0、`grid-repeat-auto-fill#c.h` 20 / 0、`grid-repeat-auto-fill#c.z` 0 / -1、`grid-repeat-auto-fill#d.x` 225 / 0、`grid-repeat-auto-fill#d.w` 75 / 0、`grid-repeat-auto-fill#d.h` 20 / 0、`grid-repeat-auto-fill#d.z` 0 / -1、`grid-repeat-auto-fill#e.y` 20 / 0、`grid-repeat-auto-fill#e.w` 75 / 0、`grid-repeat-auto-fill#e.h` 20 / 0、`grid-repeat-auto-fill#e.z` 0 / -1
- **`grid-item-alignment`** — `justify-items` / `align-items` / `*-self` がセル内の位置に効かない。
  `grid-item-alignment#a.x` 40 / 0、`grid-item-alignment#a.y` 30 / 0
- **`grid-justify-content`** — `justify-content` がトラック全体を寄せない。
  `grid-justify-content#a.x` 100 / 0、`grid-justify-content#b.x` 150 / 50
- **`grid-auto-flow-column`** — `grid-auto-flow: column` と `grid-auto-columns` を読まない。
  `grid-auto-flow-column#a.w` 50 / 300、`grid-auto-flow-column#b.w` 50 / 300、`grid-auto-flow-column#c.x` 50 / 0、`grid-auto-flow-column#c.y` 0 / 40、`grid-auto-flow-column#c.w` 50 / 300、`grid-auto-flow-column#c.h` 20 / 0、`grid-auto-flow-column#c.z` 0 / -1
- **`grid-dense`** — `grid-auto-flow: dense` と暗黙の行を読まない。
  `grid-dense#a.h` 20 / 0、`grid-dense#a.z` 0 / -1、`grid-dense#wide.y` 20 / 0、`grid-dense#wide.h` 20 / 0、`grid-dense#wide.z` 0 / -1、`grid-dense#b.x` 100 / 0、`grid-dense#b.h` 20 / 0、`grid-dense#b.z` 0 / -1
- **`grid-auto-tracks`** — `auto` トラックを内容の寸法から決めない。
  `grid-auto-tracks#a.w` 200 / 0、`grid-auto-tracks#a.z` 0 / -1、`grid-auto-tracks#b.x` 200 / 0
- **`table-colspan`** — 表レイアウトはあるが、`colspan` を読まない（暗黙の tbody は W7）。
  `table-colspan#t.z` 1 / 0、`table-colspan#span.w` 150 / 60
- **`table-rowspan`** — 表レイアウトはあるが、`rowspan` を読まない。
  `table-rowspan#t.z` 1 / 0、`table-rowspan#tall.h` 40 / 20、`table-rowspan#b.x` 50 / 0
- **`table-border-collapse`** — 表レイアウトはあるが、`border-collapse` と `border-spacing` を読まない。
  `table-border-collapse#t.w` 92 / 96、`table-border-collapse#a.x` 2 / 0、`table-border-collapse#a.y` 2 / 0、`table-border-collapse#a.w` 44 / 48、`table-border-collapse#a.h` 24 / 28、`table-border-collapse#b.x` 46 / 48、`table-border-collapse#b.y` 2 / 0、`table-border-collapse#b.w` 44 / 48、`table-border-collapse#b.h` 24 / 28
- **`position-relative`** — `position: relative` のずれが後続の兄弟の配置まで動かす。
  `position-relative#b.y` 20 / 30
- **`position-abs-insets`** — `left` と `right` の両方を持つ `width: auto` の絶対配置が間の幅に広がらない。
  `position-abs-insets#stretch.x` 10 / 270、`position-abs-insets#stretch.w` 260 / 0、`position-abs-insets#stretch.z` 0 / -1
- **`position-abs-percent`** — 絶対配置の `width` / `height` の % が包含ブロックの padding box ではなく内容ボックス基準。
  `position-abs-percent#abs.w` 22 / 20、`position-abs-percent#abs.h` 12 / 10
- **`position-abs-static`** — inset が全部 auto の絶対配置を静的位置ではなく包含ブロックの原点に置く。
  `position-abs-static#abs.y` 40 / 0
- **`position-sticky`** — `position: sticky` を読まない。
  `position-sticky#sticky.y` 10 / 0
- **`z-float-over-block`** — `float` が無く、後続のブロックが float の下へ押し下げられる。
  `z-float-over-block#block.y` 0 / 50

### W5 — 描画モデル（9 件）

- **`z-stacking-context`** — z-index を親に足し込むだけで、スタッキングコンテキストが中の z-index を閉じ込めない。
  `z-stacking-context#inner.z` 1 / 0
- **`z-negative-in-context`** — スタッキングコンテキストの中の負の z-index が親の背景より下に描かれる。
  `z-negative-in-context#ctx.z` 1 / 0、`z-negative-in-context#neg.z` 0 / 1
- **`z-positioned-over-flow`** — 位置指定要素（z-index: auto）が後続の通常フローのブロックより上の層に描かれない。
  `z-positioned-over-flow#pos.z` 0 / 1
- **`z-index-unpositioned`** — 位置指定されていないブロックの z-index が効いてしまう。
  `z-index-unpositioned#a.z` 1 / 0
- **`z-opacity-context`** — `opacity < 1` がスタッキングコンテキストを作らない。
  `z-opacity-context#inner.z` 1 / 0、`z-opacity-context#over.z` 0 / 1
- **`z-transform-context`** — `transform` がスタッキングコンテキストを作らない。
  `z-transform-context#inner.z` 1 / 0、`z-transform-context#over.z` 0 / 1

### W6 — UA スタイルシート（5 件）

- **`definition-list`** — ua.css に `dl` / `dd` の既定値（`margin: 1em 0` / `margin-left: 40px`）が無い。
  `definition-list#dl.y` 16 / 0、`definition-list#term.y` 16 / 0、`definition-list#desc.x` 40 / 0、`definition-list#desc.y` 36 / 20、`definition-list#desc.w` 280 / 320

## 解消済みの差異

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
  `minmax()`）。詳細は git の履歴と REFACTORING_PLAN.md の F4 を参照。

## 一致しているもの（回帰の番人）

次のケースは現状ブラウザと一致しており、台帳に行が無い。崩すとテストが落ちる。

- **block**: 縦積みと `margin-top`（`block-stacking`）、`box-sizing`、隣接兄弟と末子の
  マージン相殺（`margin-collapse` / `margin-collapse-bottom`）、BFC（`display: flow-root`・
  `overflow: hidden`）が子のマージン貫通を止めること、インラインとブロックが混ざったときの
  匿名ブロック、負のマージン、
  `margin: auto` の水平中央寄せ、`min-*` / `max-*`、% height・% 余白・% padding、
  `calc()`、`rem`、`vw` / `vh`、`em` の継承（算出値の px を継承する）、`font-size` の `vw`、
  `inherit` / `initial`
- **inline**: インラインブロックの横並びと折り返し・ベースライン揃え、`text-align`、
  長さ指定の `line-height`、`<br>` の強制改行、`white-space: pre`、`inline-flex` が
  前のインライン内容と同じ行に並ぶこと
- **flex**: `flex: 1` の等分と `flex-grow` の比、`flex-basis`（固定・%）、
  `justify-content`（5 種）、`align-items` / `align-self`、`flex-wrap`、`gap`、縦 flex の
  stretch と `justify-content`、`display: contents` の子が祖父の flex アイテムになること
- **grid**: `fr`、`minmax()`、`gap`
- **table**: `display: table-*` の列幅（セルの幅の最大と、表の幅の余りの配分）、行の高さを
  セルの最大に揃えること、`table-cell` だけの並びに補われる匿名の表と行
- **list**: `ul` / `ol` の字下げと `li` の積み方、入れ子
- **position / overflow**: 絶対配置の包含ブロックと shrink-to-fit、`position: fixed`、
  `overflow: hidden` のクリップ、スクロールコンテナの寸法
- **z-order**: 文書順、位置指定要素どうしの `z-index`、flex アイテムの `z-index`、
  通常フローのブロックより下に描かれる負の `z-index`
