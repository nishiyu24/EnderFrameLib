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
| | F3 | 状態をパイプラインの入力にする | 未着手 |
| | F4 | フラグメントツリーとインライン整形 | 未着手 |
| | F5 | 入力・イベント・スクロール・テキスト編集 | 未着手 |

テスト 173 件 / 失敗 0。`./gradlew build test` が CI と同じ経路。
忠実度台帳の残り **15 行**（内訳: F4-4 が 10、F4-2 が 3、F4-1 が 2）。
忠実度コーパス 15 ケース。

### 第 2 期の方針（ユーザー確認済み）

1. **Web 準拠を優先し、破壊的変更を取る。** 既存 UI は作り直し前提。
2. **表示（レイアウト・テキスト）と操作感（状態・入力・アニメ）の両方**を対象にする。
3. **ブラウザ実測との差分テストで検証する。** 忠実度を目視判断から外す。

---

## 設計の軸

残りのフェーズはこの 4 つの不変条件に収束させる。各フェーズはこのどれかを立てる作業。

**軸 1 — 測定の出所は 1 つ。**
テキストの幅・高さ・ベースライン・折り返し位置・省略位置は `core/text` が決め、
描画はその結果を**そのまま描く**。→ **F2 で達成**。

**軸 2 — 状態はパイプラインの入力。**
`ElementState` が変わったら再スタイル →（必要なら）再レイアウト → ディスプレイリスト再生成。
状態専用の描画チャネルを持たない。→ **F3**。

**軸 3 — 幾何は float、丸めは描画の 1 箇所だけ。**
テキスト層は F2 で達成。箱の寸法（`ComputedStyle.length()` と `LayoutBox` の座標）は
**F4-1 でまとめて float にする**。

**軸 4 — 差は台帳に書く。**
実装しないと決めた差異は `FIDELITY.md` + コードの `LIMITATION` + README の
"Not supported" の 3 点セットで記録する。台帳に無い差はテストが落とす。
→ **F1 で仕組みが入り、以降は運用**。

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

---

# 残りのフェーズ

## フェーズ F3: 状態をパイプラインの入力にする（軸 2）

F0 で状態スタイルは「届く」ようになったが、届き方が
`LayoutBox.hoverStyle` / `activeStyle` / `focusStyle` という 3 本の別チャネルで、
**レイアウトには一切影響しない**。ここを畳む。

### F3-1. 状態を型で表し、照合を直す

現状 `ElementState` は `record(boolean hover, boolean active, boolean focus)` の 3 値。

```java
public record ElementState(int flags) {   // ビット集合
    // HOVER, ACTIVE, FOCUS, FOCUS_VISIBLE, FOCUS_WITHIN, CHECKED, DISABLED, INDETERMINATE, OPEN
}
```

- `SelectorParser.java:173` が `focus` / `focus-visible` / `focus-within` を
  **同一フラグに潰している**（`case "focus", "focus-visible", "focus-within" -> out.parsedFocus = true`）。
  分ける。`:focus-within` は祖先方向の照合、`:focus-visible` はキーボード由来のみ。
  現状は `:focus-visible` がマウスクリックでも成立し、`:focus-within` が自分のフォーカスで誤 match する。
- `SelectorMatcher` の `:checked` / `:disabled` が静的属性しか見ないのを実行時状態参照にする
  （ゲーム内でチェックを切り替えても `:checked` が付かない）。
- `StyleSet.resolve` の `base → focus → hover → active` の `putAll` を捨て、
  **状態込みで通常のカスケード（詳細度 + 出現順）を回す**。
  現状は詳細度に関係なく hover が base に勝ち、active が focus に勝つ。
- `Specificity.compareTo` が `important` を `origin` より先に比較しているため
  （`compareTo` の 1 行目が `this.important != other.important`）、
  作者の `!important` が UA の `!important` に勝つ。順序を入れ替える。

### F3-2. 状態変化を無効化として扱う

`UIParser.java:302` が常に `styleSet.resolve(ElementState.NONE)` で解決しているため、
`:hover { padding }` も `:checked { display }` も効かない。

- `buildLayoutTree` に状態を渡し、状態が変わった要素の**サブツリーだけ**再スタイルする。
  親の状態が子の継承値に伝わる（`a:hover` で中のテキスト色が変わる）ようになる。
- 無効化を 3 段に分ける: **restyle / relayout / repaint**。
  現状 `ClientWebManager.recomputeLayout` は全部やり直す一択で、
  `color` が変わっただけで文書全体を再レイアウトする。
  `buildLayoutTree` に渡されていて読まれていない `forceDirty` 引数はここで意味を持つ。
- 入力ハンドラ内での `processRenderQueue()` 強制フラッシュ（`client/` に 10 箇所）を撤去し、
  **フレーム境界で 1 回**流す。`requestRender` による集約が本来の仕組み。
- `renderCache` の in-place 書き換え（`updateElementTextLocally`）をやめる。
  描画スレッドと `HitTestIndex` が同じ `ArrayList` を見ている。
- `dom_sync` が 1 バイト違うだけで `session.close()` を呼び、
  アニメーションと canvas を全部消すのをやめる。

### F3-3. トランジションとアニメーションを一般化

`PseudoTarget`（opacity / tx / ty / sx / sy / rot / color / bgColor の 8 値）を**廃止**する。
状態変化は再スタイルで表現されるので、アニメータの仕事は
「前の算出値 → 次の算出値」の補間に変わる。

- 補間可能プロパティを型で持つ（色・長さ・`transform` リスト・`opacity`）。
  現状 `:hover` の `border-color` / `box-shadow` / `padding` / `text-decoration` は
  境界で捨てられ、`@keyframes` の `background-color` も無言で無視される。
- `transition-property` を実装する（現状は最初の duration と delay と
  部分一致の easing しか見ない）。
- `cubic-bezier()` を実装し、既定を CSS の `ease` にする（現状は 4 本の固定カーブ、既定 `ease-out`）。
- `animation-iteration-count` / `-direction` / `-fill-mode` / `-delay` を実装する。
- キーフレームがトランジションを無条件に上書きするのを直す。
- 時間基準は現状どおり実時間でよい（ここはフレームレート非依存に実装できている）。

**主な対象**: `core/css/SelectorParser.java`, `SelectorMatcher.java`, `StyleSet.java`,
`Specificity.java`, `ElementState.java`, `common/UIParser.java`,
`client/ClientWebManager.java`, `client/renderer/WebAnimator.java`, `UIRenderer.java`,
`core/model/PseudoTarget.java`（削除）

**完了条件**: `ua.css` の状態セレクタが詳細度どおりに効き、
`:hover { padding }` がレイアウトに反映され、`PseudoTarget` が無くなること。

---

## フェーズ F4: フラグメントツリーとインライン整形

表示差の構造的な残り。**台帳 15 行のすべてがこのフェーズで消える。**

### F4-1. 算出値と使用値を分ける（軸 3 を含む）

`ComputedStyle` は不変で `CompiledLength` を memo 化済み（良い継ぎ目）だが、
中身は**指定文字列**で、パーセント基準が `length(prop, maxPixels, rootW, rootH)` として
呼び出しを貫通している。これが以下を同時に引き起こしている。

- **縦方向の margin / padding のパーセントが幅基準でない。**
  `BoxMeasurer.resolveBoxEdges` は `margin-top` / `padding-top` を親の高さ側の引数で解き、
  その値には祖先から降りてきたビューポート高さが混ざる。
  CSS は常にインラインサイズ基準。→ 台帳 `percent-margin-narrow#child.y`
- **`height: 50%` の基準**が親ではなく祖先から降りてきた値になる。
  → 台帳 `percent-height#child.h`
- `font-size: 2vw` が 0 になる（`ComputedStyle.of` が `rootW` / `rootH` に 0 を渡す）。
- 継承が**指定文字列**で行われるため `em` が階層ごとに複利で増える。
- `LengthResolver` の `min()` / `max()` が未実装（式が 0 になる）。

**対応**: `ComputedStyle` を本当の算出値（長さは px に絶対化、継承は算出値で）にし、
パーセント基準は `Constraints`（使用値側）が持つ。
これで `(parentW, parentH, rootW, rootH)` の 4 つ組が全シグネチャから落ちる。

**同じ作業で `int` → `float` 化をやる。**
`ComputedStyle.length()` の戻りと `LayoutBox` の座標・寸法を `float` にし、
丸めは `UIElement` を作る最終段（`UIRenderer`）と `snapCoordFor2D` の 2 箇所だけにする。
テキスト層は F2 で float 化済みなので、残っているのは箱の寸法だけ。
**F2 で先に float 化しなかったのは意図的**で、
この 173 箇所の `length()` 呼び出しをどちらにしても書き換えるため、
2 回触るのを避けた。

### F4-2. エンジン SPI をフラグメントを返す形に畳む

`LayoutEngine` の 3 メソッド（`measure` / `resolveFlexibleHeights` / `arrangeChildren`）を
`Fragment layout(Box, Constraints)` 1 本にする。付随して消えるもの:

- `LAYOUT_PASSES = 2`（`UIParser.java:55`）—— 2 パス必要なのは子の結果が上に返らないから
- `SizingMode`（`DEFINITE` / `SHRINK_TO_FIT`）——「幅がまだ分からない」の回避策
- `box.inlineFragments` の in-place クリア＆再追加 → 本物の `LineBox` ツリー

`flex-basis` をレイアウトが基本サイズとして読むのもここ。
→ 台帳 `flex-basis-fixed#a.w` / `#b.x` / `#b.w`

**着手順の注意**: `BlockLayoutEngine` のインライン部はこの計画中で最も密度が高い。
守っているテストは `InlineLayoutConsistencyTest` だけだが、
**F2 でこれを各断片の座標とベースラインまで検証するよう拡張済み**なので、
フラグメント型の導入 → エンジンを 1 つずつ移行の順で進められる。
3 エンジン同時に書き換えない。

### F4-3. インライン整形を本物にする

- `vertical-align`（`CssProperty` に定数すら無い）。
  ベースライン揃えそのものは F2 で入ったが、揃え方は固定で、
  置換要素とインラインブロックのベースラインは下端で近似している。
- **空白のみのテキストノードを捨てるのをやめる**（`UIParser.java:327`）。
  現状 `<span>a</span> <span>b</span>` が "ab" になる。
- `ua.css` を Web の既定に合わせる: `a`（`ua.css:16`）と `code`（`:22`）を
  `inline-block` から `inline` に、`span` / `b` / `strong` / `i` / `em` に `display: inline`、
  `li`（`:14`）を `list-item` にしてマーカーを出す。
- `inline-flex` が `isInlineBox` の判定に無く block になる。
- インラインボックスの padding / border 断片。

### F4-4. ブロックの箱モデルを Web に合わせる（破壊的）

**台帳 10 行がここで消える。**

- **`box-sizing` 既定を `content-box` に戻す。** → `box-sizing#outer.w`
  `LayoutGoldenTest.testBorderBoxIsTheDefault` を書き換える。
- **マージン相殺**を実装する（現状は単純加算、親の上辺の貫通も無し）。
  → `margin-collapse#b.y`, `percent-margin#parent.y`, `percent-margin-narrow#parent.y`
  `LayoutGoldenTest.testBlockChildrenStackVertically` が現状を固定している。
- **ブロックフローで `gap` を効かせない**（CSS では block コンテナに `row-gap` は効かない）。
  → `block-gap#wrap.h`, `block-gap#b.y`
  `LayoutGoldenTest.testRowGapSeparatesBlockChildren` を書き換える。
- 絶対配置: `position: static` の親を包含ブロックにしない → `abs-containing-block#abs.y`。
  `left` / `right` 未指定時の静的位置フォールバック（現状 `left: 0` 相当）、`position: sticky`。
- 縦方向の `margin: auto`、`align-content`、`flex-direction: *-reverse`
  （現状 `dir.startsWith("row")` が `row-reverse` に match して前向きに並べる）、`order`。
- grid: トラックリストを空白で split しているため `minmax(100px, 1fr)` が 2 列になる。
  `CssTokenizer` でトークン分割する。 → `grid-minmax#a.w` / `#b.x` / `#b.w`
  行の `fr` が `auto` 扱い、行スパンが高さに寄与しない問題も同時に直す。

### F4-5. 重ね順とクリップ

- **スタッキングコンテキスト**を実装する。現状は `currentZ = parentZ + box.zIndex` の
  加算を全文書で 1 回ソートで、`position: static` も `z-index` に参加する。
  継ぎ目は `buildRenderQueue` の中なので局所的に入る。
- **クリップを transform 後の空間で行う。** 現状クリップ矩形は変換前のレイアウト空間で
  計算・比較されるので、`overflow: hidden` の中で `transform` した子の切り取りが逆になる。
- `is2DAudio` を「2D ホストか」の判定に流用しているのをやめ、
  `WebPaintPass` に `isScreenSpace` を持たせる。これが原因で
  **ワールド内スクリーンのテキストがクリップされず**、
  ビルボードでは world 行列から GUI scissor を計算して壊れている。
- `opacity` をグループ（サブツリー）単位の合成にする。現状は quad ごとの alpha 乗算なので
  半透明な親の中で子同士の重なりが濃くなる。
- `box-shadow` が宣言値を見ずに固定 3 枚、`linear-gradient` が 2 stop・5 方向のみ。

**主な対象**: `core/css/ComputedStyle.java`, `LengthResolver.java`,
`core/layout/*`（全エンジン）, `core/model/UIElement.java`,
`client/renderer/UIRenderer.java`, `WebRenderCore.java`, `WebSdfShader.java`,
`src/main/resources/assets/enderframelib/css/ua.css`

**完了条件**: 台帳 15 行がすべて消え、
`LayoutGoldenTest` の差分が**意図したものだけ**であること。

---

## フェーズ F5: 入力・イベント・スクロール・テキスト編集

### F5-1. イベントモデルを入れる

現状 `triggerLocalEvent` が唯一の経路で、
**バブリングもキャプチャも `Event` も `preventDefault` も `stopPropagation` も無い**。
コンテナの `onclick` は子をクリックしても発火せず、
`summary` と `label[for]` だけがクリック時の祖先探索で特別扱いされている。

- `core/event` に `DomEvent`（type / target / currentTarget / phase / defaultPrevented）と
  ディスパッチャを置く（`core/` は Minecraft 非依存のまま）。capture → target → bubble。
- **既定動作を 1 箇所に集める**: `a`（href）、`label`（for と包含の両方）、
  `summary` / `details`、`checkbox` / `radio`、`select`、`button[type=submit]`。
  現状 `label` は GUI 画面とワールド内で別経路
  （`UIRenderer` が `onclick="label_for:…"` を注入）になっている。
- `ElementType.BUTTON` / `A` / `CHECKBOX` / `RADIO` が**一度も生成されていない**ので、
  `HitTestIndex` の分岐と既定サウンドの判定が死んでいる。型を実際に出す。

### F5-2. ヒットテスト

`HitTestIndex.isInteractive` が「フォーム要素 / `on*` 属性 / `title` / `draggable` /
アイテム画像 / 親が疑似状態を持つ」だけをヒット対象にしているため、

- 不透明な `<div>` がボタンの上にあってもクリックが**貫通する**（ブラウザと逆）
- `TEXT` 要素は常に除外なのでテキストをクリックできない
- スクロールバーのつまみも除外なので**ドラッグできない**

**対応**: 「背景を塗る／置換要素である」を遮蔽の条件に加え、
`pointer-events: none` を明示的なオプトアウトとして実装する。
テキストとスクロールバーを対象に含める。

### F5-3. スクロール

- `ScrollState` を `float` にし（現状 `Map<String, int[]>`）、
  **スクロールをレイアウト入力から外す**（現状 `LayoutBox.layout` が
  `innerX` / `innerY` から引いているので、ホイール 1 刻みごとに全文書再レイアウト）。
  描画時の合成トランスレートに移す。
- スムーズスクロール・慣性・端での親へのチェーン・`scroll-behavior`。
- スクロールバーのドラッグとクリック（F5-2 の後）。
- `overflow: hidden` でもスクロール範囲が作られる（`updateScrollExtent` が無条件）のを直す。
  `overflow` の読み方が 3 箇所で不統一（短縮形のみ / `overflow-x`・`-y` /
  flex shrink のオプトアウト）なのを揃える。

### F5-4. フォーカスとテキスト編集

- フォーカスの 2 系統（`SessionContext.activeInputId` と
  `WebFocusNavigator.focusedElementBaseId`）を 1 つにする。現状 `WebRenderCore` は
  前者しか見ないため、**Tab で移動したボタンにはフォーカスリングが出るのに
  `:focus` の CSS が当たらない**。リングは `ZLayers.FOCUS_INDICATOR_OVERLAY_Z` で
  クリップ外に描かれ、スクロールコンテナから飛び出す。
- tab order: `onclick` を持つだけでフォーカス可能になる、
  インライン `style` 属性の部分一致でしか `display: none` を見ない
  （クラス由来の非表示は依然フォーカス可能）、`visibility: hidden` を無視、
  radio グループを 1 つにまとめない。
- **選択モデルを入れる**: anchor / focus offset、shift + 矢印、ダブルクリックで単語、
  トリプルクリックで行、ドラッグ選択、選択範囲のハイライト、コピー / カット。
  現状は選択が存在せず、単一行入力は画面外 (−2000, −2000) の隠し `EditBox` に
  委譲しているため選択描画がどこにも出ない。
  隠し `EditBox` は **IME 入力の取り込みだけ**に役割を縮小する。
  文字位置の決定には F2 で入った `FontFace.fitChars` / `glyphCount` を使う。
- `WebTextInputHost.activeHost` が**グローバル static** なので、
  2 画面開くと片方の入力が他方のプレースホルダを消す。
  プレースホルダの可否を描画側で id のサフィックス剥がしから推測するのもやめる。
- Enter / Space での activate が**要素中心への合成クリック**になっているため、
  上に別要素があると別の要素が押される。イベントモデル（F5-1）で直接ディスパッチする。
- `cursor` プロパティがどこからも読まれていない。OS カーソルに反映する。
- ホバーのレイテンシを揃える: GUI は毎フレーム、
  ワールド内は 20Hz tick + `onmousemove` 50ms スロットル。
- `<select>` の挙動がホストで違う（GUI ではドロップダウン、
  ワールド内では次の選択肢に循環）。1 つにする。
  `SelectDropdownWidget` が `HitTestIndex` を通らない独自ヒットテストを持っているのも解消。

**主な対象**: `core/event/`（新規）, `client/ClientScreenInteractionManager.java`,
`client/gui/InteractionHandler.java`, `WebScreenDelegate.java`, `WebFocusNavigator.java`,
`WebTextInputHost.java`, `WebTextareaEditor.java`, `SelectDropdownWidget.java`,
`core/layout/ScrollState.java`, `core/model/HitTestIndex.java`

**完了条件**: コンテナの `onclick` が子のクリックで発火し、
不透明な要素がクリックを遮り、テキスト選択ができ、
フォーカスが 1 系統になること。

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
**台帳の該当行が消えることが、F4 の各項目の完了条件。**

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

- **F3 と F4 は独立して進められる。** F3 は `client/` と `core/css` 中心、
  F4 は `core/layout` 中心。並行させてよい。
- **F4-1 を F4 の最初に入れる。** パーセント基準の引き回しと `int` 幾何を先に畳まないと、
  F4-2 以降が古いシグネチャの上に積まれる。
- **F4-2（エンジン SPI の畳み込み）が最大のリスク。**
  テスト拡張（F2 で済）→ フラグメント型の導入 → エンジンを 1 つずつ移行の順で、
  3 エンジン同時に書き換えない。
- **F5-1（イベントモデル）が F5 の他の項目の前提。**
  F5-2 のヒットテストと F5-4 の activate はイベントモデルの上に乗る。
- **破壊的変更の通知**: `box-sizing` 既定・`display: inline` 化・空白保持は
  既存 UI の見た目を変える。F4-4 のコミットで README に移行メモを書き、
  `showcase.html` / `guidebook.md` / `config-form.css` / テーマ 3 本を同時に追従させる。
  `font-size` の換算は F2 で済んでおり、移行メモは README の
  "Breaking change — text metrics" に入っている。

## 未着手のまま残している既知の差（台帳に行が無いもの）

台帳は「コーパスで測れた差」だけを持つ。次は測れていないが分かっている差で、
該当フェーズで扱う。

- `font-size: 2vw` が 0 になる（F4-1）
- `em` の継承が複利で増える（F4-1）
- `LengthResolver` の `min()` / `max()` が未実装（F4-1）
- 空白のみのテキストノードが捨てられる（F4-3）
- `vertical-align` 未実装（F4-3）
- スタッキングコンテキストが無い（F4-5）
- transform 後の空間でクリップしていない（F4-5）
- グリフの送り幅に由来する差（折り返し位置・省略位置・中央寄せの offset）は
  **原理的に一致しないので台帳に載せない**。根拠は FIDELITY.md の「意図的に違うもの」。
