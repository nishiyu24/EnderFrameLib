# EnderFrameLib リファクタリング計画

## Context

EnderFrameLib は NeoForge 1.21.1 向けに「Web ブラウザエンジン」を模倣した
ゲーム内 UI フレームワーク。HTML トークナイズ → DOM → CSS → レイアウト →
ディスプレイリスト → 描画という本物のブラウザに近いパイプラインを自前実装している。

**今の状態**: 11 コミット / 4 日間（2026-10-14〜10-17）で 17,878 行・62 ファイルまで
一気に成長した。機能は既に広い（flex/grid、CSS 変数、`calc()`、`@media`、
SDF 角丸、canvas、動画、Markdown、PubSub、HUD、マルチブロックスクリーン）。
**構造が機能の成長速度に追いついていない**のが問題。

- テスト 0 件（`src/test` が存在しない）、CI 0 件（`.github` が存在しない）
- 100 行超メソッドが 19 個、合計 3,262 行 = 全体の **18%** が 19 メソッドに集中
- 最大クラス 1,508 行（`AbstractWebScreen`）、最大メソッド 461 行（`CssParser.processAtRules`）
- パッケージ間の**相互依存（循環）が 11 組**
- スタイルが `Map<String, String>` のまま 152 箇所で生文字列参照される

**パイプラインの段階分け（`HtmlNode` → `LayoutBox` → `UIElement`）と、
Minecraft 依存を注入フックで切り離す設計方針そのものは正しい。**
壊れているのは型表現とクラス分割であって、全体設計ではない。

**今回の方針（ユーザー確認済み）**:
1. 優先軸は **内部保守性＋テスト基盤**（拡張 API 公開・Web 標準忠実度・描画性能は副産物）
2. **破壊的変更 OK** — v1.0.3 の API シグネチャは自由に再設計してよい
3. ネイティブ DLL は**同梱のまま**、ロード失敗・非 Windows の抽象化だけ整える
4. **基盤を一括で作り直す** — `core/` を再設計し、`client/` をそれに追従させる

**到達したい状態**: `core/` が Minecraft 非依存の純 Java ライブラリとして
JUnit で検証でき、スタイルとレイアウトが型で表現され、1,000 行超クラスが
責務ごとに分割され、以後の機能追加が構造を壊さずに積める土台になること。

---

# 第 1 部: 評価

## 最重要の判断

結論は、パイプラインの前半と後半でそれぞれ 1 つずつ。
**どちらも「境界を文字列で渡している」ことが根本原因**で、症状の大半はその派生。

> **(1) `core/` 側 — 算出スタイルが「共有された可変 `Map<String,String>`」であり、
> それが同時にキャッシュ・HTML 属性の入れ物・レイアウトエンジンの作業領域を
> 兼ねている。**

これを直すと、キャッシュ破損・カスケード順序の誤り・
エンジン間の重複が同時に解消される。

> **(2) `client/` 側 — `UIElement` 境界が構造化データを文字列に平坦化し、
> 下流が parse し直している。**

`formatRadii`/`formatClipRect`、`@@HOVER@@` センチネル、
`String` の `el.type()`、`id + "_bg"` のサフィックス連結がすべてこれ。
**しかも `RenderMeta` レコードへの移行は既に半分終わっている**ので、
完了させるだけで符号化/復号のペアが丸ごと消える。

基盤一括再構築の中心はこの 2 点に置くべき。
god クラスの分割は、型が入った後なら機械的な作業になる。

---

## A. スタイルシステム（最重要）

### A-1. HTML 属性が算出スタイルに丸ごと流し込まれている

`core/css/CssParser.java:309`、全 CSS ルール適用の**後**:

```java
for (StyleRule rule : sheet.rules) { ... style.putAll(rule.properties); }
style.putAll(node.attrs);                 // ← ここ
if (node.attrs.containsKey("style")) { ...インラインスタイル... }
```

結果として算出スタイルマップに `id` / `class` / `onclick` / `src` / `href` /
`data-*` / さらに `style` という名前のキーまで同居する。影響:

- `<img width="32">` が `img { width: 100% }` に**勝つ**（本来 HTML では
  presentational 属性はあらゆるルールに負ける）→ カスケード順序のバグ
- レイアウトエンジンが `style.get("data-list")` のように属性を読む（責務の混線）
- ゴミキーが `var()` 解決ループ（`CssParser.java:326-355`）と
  全 `style.getOrDefault(...)` に巻き込まれる
- 「このキーは CSS プロパティか属性か」をコンパイラが区別できない

実測: スタイルキーとして使われる生文字列リテラルは `"mc-parent"` 14回、
`"data-model"` 8回、`"data-bind"` 6回…。スタイル参照は全体で 152 箇所、
`LayoutBox` 56 / `UIRenderer` 46 / `FlexLayoutEngine` 33 / `AbstractWebScreen` 22。

この属性流し込みに実際に依存している消費者は `UIRenderer.java:645`（`src` を
スタイルマップから読む）**1 箇所だけ**。残りは純粋な汚染。

### A-2. 疑似クラスが「キー名のプレフィックス」と「通常の値」の二重機構

`CssParser.java:748-758` が、セレクタに結合子があるかどうかで扱いを切り替える:

- `.btn:hover`（結合子なし）→ `rule.isHover`。ルールは**ホバー状態に関係なく
  マッチ**し、宣言は `"hover-" + プロパティ名` として保存される
- `.card .btn:hover`（結合子あり）→ `token.isHover`。実際の `node.isHovered` と
  照合され、宣言は**プレフィックスなし**で基底スタイルに入る（＝キャッシュを汚す）

さらにプレフィックス側の消費者 `UIRenderer.buildPseudoStateDef`
（`client/renderer/UIRenderer.java:291-318`）が読むキーは**たった 5 つ**:
`{prefix}-opacity` / `-transform` / `-color` / `-background` / `-background-color`。
`:hover` / `:focus` / `:active` ブロック内のそれ以外の宣言は**黙って捨てられる**
— 同梱 UA スタイルシート自身の `.mc-slot:hover { border-color }`
（`UIParser.java:188`）や `button:focus-visible { box-shadow }`（`:177`）も含めて。

最後にその状態はマジック文字列に平坦化される（`UIRenderer.java:318`）:

```java
return has ? (marker + op + ":" + tx + ":" + ty + ":" + sx + ":" + sy + ":" + rot + ":" + col + ":" + bgCol) : "";
```

### A-3. カスケードと詳細度が文字数カウント

`CssParser.java:768-781` はセレクタの**構造**ではなく**文字**を数える:

```java
if (c == '#') spec += 100;
else if (c == '.' || c == '[' || c == ':') spec += 10;
```

- `::before` はコロン 2 個で 20 点 → **疑似要素がクラスに勝つ**
- `:not(.a)` も 20 点
- 加算 10 進なので**桁溢れする**（クラス 11 個が id 1 個に勝つ）
- `!important` は**未実装**
- 作者スタイルシートは `rule.specificity += 10000`（`common/UIParser.java:199-207`）
  でUAシートを上回る ← カスケード origin の代用マジック定数

ソート自体（`CssParser.java:505`、昇順の安定ソート）は正しい。

### A-4. 算出スタイルのキャッシュが不正、かつ read-only ではない

```java
// CssParser.java:275-278
if (!node.isDirty && node.computedStyle != null) {
    return node.computedStyle;     // inheritedStyle / sheet / env を見ていない
}
```

キャッシュキーが `isDirty` のみ。引数（継承スタイル・スタイルシート・env）が
変わってもヒットする。`UIParser.buildLayoutTree`（`:420-422`）が
`forceDirty` を祖先から伝播させて埋め合わせているが、不変条件は暗黙で脆い。

**さらに悪いのは、返されるマップが生のキャッシュ本体で、レイアウトエンジンが
それを書き換えること**（A-5）。

加えて `HtmlNode.markDirty()`（`core/dom/HtmlNode.java:260-272`）は
**全兄弟＋祖先チェーン全体**を dirty にする。`buildLayoutTree` が
`wasDirty` を `forceDirty` として下に流すため、ホバー 1 回で
「自分の部分木＋全兄弟の部分木＋全祖先」のスタイルが無効化される。
キャッシュは見かけよりずっと効いていない。

### A-5. レイアウトエンジンが共有キャッシュを書き換える（save/force/recurse/restore）

`core/layout/FlexLayoutEngine.java:124-132`:

```java
if (child.style.containsKey("flex-grow")) {
    float grow = Float.parseFloat(child.style.get("flex-grow"));
    child.w += (int) (extraW * (grow / totalGrowW));
    String oldW = child.style.get("width");
    int forcedW = child.w - (...);
    child.style.put("width", Math.max(0, forcedW) + "px");   // ← キャッシュを改変
    child.computeSize(child.w, child.h, false, false, "stretch", false, rootW, rootH);
    if (oldW != null) child.style.put("width", oldW); else child.style.remove("width");
}
```

`LayoutBox.style` は `node.computedStyle` と**同一インスタンス**
（`UIParser.buildLayoutTree:424-428`）。この save/force/recurse/restore が
**8 箇所**で繰り返される（Flex `:83,:92,:128,:169,:182` / Grid `:178,:229`）。

- restore が `finally` に入っていない → **例外が出ると強制 `width:NNNpx` が
  算出スタイルキャッシュに永久に焼き付く**
- `LayoutBox.computeSize` も `flex` ショートハンド展開時に
  キャッシュを恒久的に書き換える（`:117-137`）
- `Float.parseFloat(child.style.get("flex-grow"))` は**ガード無し**
  （Flex `:110,:113,:125,:143,:151,:179`、Grid `:81,:90` の `fr`）→
  `flex-grow: abc` がレイアウトから `NumberFormatException` を投げる

### A-6. CSS「パーサ」が正規表現

```java
private static final Pattern CSS_PATTERN = Pattern.compile("([^{]+)\\{([^}]+)}");
```

`[^}]+` なのでネストした `{}`、文字列内・`url()` 内の波括弧を扱えない。
回避策として `processAtRules()` が**461 行**（本リポジトリ最大のメソッド）の
文字列前処理で `@media` を手で平坦化している。トークナイザが無いため、
`@supports`・ネスト・複雑なセレクタを足すたびにこの 461 行が膨らむ構造。

セレクタ解析も**破壊的な文字列手術**で、トークナイズではない:

```java
// CssParser.java:541-548
if (sel.contains(":hover"))  { parsedHover = true;  sel = sel.replace(":hover", ""); }
if (sel.contains(":active")) { parsedActive = true; sel = sel.replace(":active", ""); }
```

### A-7. 継承プロパティが 7 個の許可リスト — 実害が出ている

```java
// CssParser.java:31-33
private static final Set<String> INHERITABLE_PROPS = Set.of(
        "color", "text-align", "font-family", "font-size", "line-height",
        "text-transform", "font-weight");
```

欠落: `font-style`, `text-decoration`, `text-shadow`, `white-space`,
`letter-spacing`, `visibility`, `cursor`, `direction`。

これは単なる未実装ではなく**可視のバグ**。グリフは `#text` 子ボックスから
描かれ、レンダラはそのボックスからプロパティを読む
（`client/renderer/UIRenderer.java:679-680`）。UA シートの
`a { text-decoration: underline }`（`UIParser.java:143`）は `<a>` ボックスに
付くだけで `#text` 子に伝播しないため、**リンクに下線が出ず、
`<s>`/`<del>` に打ち消し線が出ない**。

なお `font-weight` は継承リストにあるのに、`BlockLayoutEngine.isBoldBox()`
（`:71-81`）が**祖先ボックスを別途歩いて**太字判定している — 継承リストを
直す代わりに bold 専用の第 2 機構が作られた。

### A-8. 確認済みの個別バグ

| バグ | 場所 | 内容 |
|---|---|---|
| `@media` が AND でなく **OR** | `CssParser.java:408-419` | `conditionMet` をどちらの分岐でも `true` にする。`@media (min-width:400px) and (max-width:800px)` が**全幅でマッチ** |
| `:root` 宣言が捨てられる | `CssParser.java:491-498` | `--var` 以外は破棄。`:root { color: red }` が消える |
| `border-width` ショートハンド未展開 | `CssParser.java:357-371` | `border` → `border-{dir}` → `border-{dir}-width` は展開するが、素の `border-width: 2px` は無視される |
| `min-height` が**水平**の装飾幅を足す | `LayoutBox.java:318-322` | 6 行上の `max-height`（`:313`）は正しく `verticalDecoration()`。コピペミス |
| `em` が要素のフォントサイズを見ない | `CssParser.java:166` | `em` = `16 * value` 固定。`rem` も `16` 固定 |
| `parseDim` が全失敗で `0` | `CssParser.java:168` | 単位のタイポと `0px` が区別できない |
| `catch` 内で未ガードの `parseFloat` | `LayoutBox.java:259`, `BlockLayoutEngine.java:97,236` | `line-height: 2em` が catch ブロックから例外を投げる |
| `aspect-ratio` 解析失敗で正方形 | `LayoutBox.java:279` | `catch` の中で `this.h = this.w` |
| `script`/`style` 内で実体参照が復号される | `HtmlTokenizer.java:121` | HTML 仕様違反。`&amp;lt;` を含むスクリプトが壊れる |
| `MAX_NODES` がノードでなくトークン数 | `HtmlParser.java:28` | 超過時に**無警告で文書を切り捨てる** |
| NBT 逆シリアライズに深さ制限なし | `HtmlNodeSerializer` | 不正ペイロードでクライアントがスタックオーバーフロー |

---

## B. レイアウト層

### B-1. `LayoutEngine` インターフェースに親の都合が漏れている

```java
public interface LayoutEngine {
    void computeSize(LayoutBox box, int tentativeInnerW, int parentH,
                     boolean isParentFlexRow, boolean isParentFlexColumn,
                     String parentAlignItems, boolean isParentShrink,
                     int rootW, int rootH);
    void computeHeightAndFinalizeChildren(LayoutBox box, int parentH,
                     boolean isParentFlexRow, boolean isParentFlexColumn,
                     int rootW, int rootH);
    void layoutChildren(LayoutBox box, int innerX, int innerY, int rootW, int rootH);
}
```

引数 9 個、うち 4 個が**親のフォーマッティングコンテキストを表すフラグ**。
`rootW`/`rootH` はパッケージ内の全呼び出しを貫通している（＝アンビエントに
すべき viewport）。メソッド名も実態と合っていない
（`computeHeightAndFinalizeChildren` は flex grow/shrink 解決と絶対配置子の
サイズ決定もしている）。`display: inline` や絶対配置を正式に足すには
**インターフェース自体の変更**が必要。

`LayoutEngineFactory` は `flex`/`grid` 以外を全て block に落とすので、
`inline` / `inline-block` / `inline-flex` / `table` が黙って block になる。

### B-2. `LayoutBox` がデータ・スタイル・アルゴリズム・エンジン内部を兼任

```java
public HtmlNode node;
public Map<String, String> style;      // = node.computedStyle（共有・可変）
public List<LayoutBox> children = new ArrayList<>();
public int x, y, w, h;
public int mT, mR, mB, mL, pT, pR, pB, pL, bT, bR, bB, bL, gap;
public Object engineState;              // 型無し
```

- 全フィールド `public`、不変条件を守る術が無い。`computeSize` は **218 行**
- `w == -1` が「shrink-to-fit」の**マジックセンチネル**として 3 エンジンを貫通
- `engineState` は `Object`。しかも `LayoutBox` が**特定エンジンの内部型を
  知っている**（循環依存）:

```java
// LayoutBox.java:378-384
if (this.engineState instanceof GridLayoutEngine.GridState gs) {
    for (GridLayoutEngine.GridItem item : gs.items) { ... }
```

### B-3. レイアウトが DOM に書き戻し、それが diff を暴走させている

これが一番面白い構造バグ。`LayoutBox.layout()` がレイアウト結果を
DOM 属性として書き込む:

```java
// LayoutBox.java:389-390
this.node.attrs.put("data-scroll-w", String.valueOf(scrollWidth));
this.node.attrs.put("data-scroll-h", String.valueOf(scrollHeight));
```

これらのキーは実ツリー（`doc.root`）にしか存在せず、仮想ツリーには無い。
`DomDiffEngine` は仮想側に無い実属性を削除する:

```java
// DomDiffEngine.java:31-40
if (!virtualNode.attrs.containsKey(key)) { it.remove(); changed = true; }
```

→ **スクロール可能ボックスがあると `diffAndPatch` が毎回 `changed == true` を
返し、`ClientWebManager.processRenderQueue()` が何も変わっていなくても
毎回フルリレイアウトを走らせる**。同時にそのノードの算出スタイルキャッシュも
無効化される。「レイアウトが DOM に書く」結合と「属性を刈る diff」が噛み合って
イベント駆動のゲートが漏れている。

スクロール位置も逆方向に DOM 属性経由で渡っている
（`LayoutBox.java:345-348` が `data-scroll-x/y` を `Float.parseFloat` で読み、
失敗を握り潰す）。

### B-4. レイアウト結果のキャッシュが無く、`computeSize` が二度呼ばれる

```java
// common/UIParser.java:411-417
LayoutBox rootBox = buildLayoutTree(doc.root, rootStyle, doc.sheet, env, false);
rootBox.computeSize(rootW, rootH, false, false, "stretch", false, rootW, rootH);
rootBox.computeSize(rootW, rootH, false, false, "stretch", false, rootW, rootH);  // ← 二重、コメント無し
rootBox.layout(0, 0, rootW, rootH, rootW, rootH);
```

毎回 `LayoutBox` ツリーを全再構築し、dirty 部分木だけの再レイアウトも
測定のメモ化も無い。救いは**毎フレームではない**こと（`requestRender()` →
`DIRTY_SESSIONS` → `processRenderQueue()` のイベント駆動、
`client/ClientWebManager.java:274-293`）— ただし B-3 によりそのゲートが漏れている。

`LayoutBox.getMinContentWidth`（`:60-94`）は部分木全体を再帰し、
flex shrink ループ（`FlexLayoutEngine.java:154`、最大 10 回）の中から
呼ばれるためメモ化無しで最悪 O(n²·10)。

### B-5. 3 エンジン間に共通基底が無く、約 400 行が重複

| 重複ブロック | 場所 |
|---|---|
| 絶対配置子の確定（5 行同一） | `Block:64-68`, `Flex:190-194`, `Grid:250-254` |
| `line-height` 解析（px フォールバック付き） | `LayoutBox:256-262`, `Block:93-102`, `Block:232-239`, `UIRenderer:698-703` — **4 コピー** |
| 強制幅 save/force/recurse/restore | Flex ×5, Grid ×2 |
| `justify-content` の space 配分（約 15 行、`float`/`int` 違いだけ） | `Flex:232-246` vs `:286-300` |
| インライン**測定** vs インライン**配置** | `Block.measureInlineRunHeight(83-154)` vs `layoutInlineContext(222-341)` — 約 70 行の再実装。折返しを直すときに両方直さないと測定高と配置高がずれる |
| `display:none` 判定 | 同一式が **10 箇所** |

### B-6. Minecraft 固有表現が core に漏れている

- `§l`（MC の太字書式コード）を測定文字列に直接埋め込む:
  `LayoutBox.java:81,187`, `BlockLayoutEngine.java:125,258`。
  測定済み文字列は `textLines`/`InlineFragment.text` に入ってそのまま
  レンダラに渡るので、core の出力が MC レンダラ専用になっている
- 置換要素の固有サイズが core にタグ名チェーンでハードコード:
  `mc-scene` 64×120、`img` 既定 16×16 / 32×32、`input` 90px、
  checkbox/radio 16px、`progress` 12px（`LayoutBox.java:63-72,192-204,283-303`）
- `LayoutBox.computeSize` のタグ名分岐は **13 箇所**、`BlockLayoutEngine` に **12 箇所**

---

## C. DOM 層

### C-1. 非正規化された状態が 4 箇所で同期されている

`HtmlNode` は `id` と `attrs["id"]`、`classes` と `attrs["class"]`、
`isHovered`/`isChecked` と `attrs["hover"]`/`attrs["checked"]` を二重に持つ。
その同期が 4 箇所に再実装されている:
`HtmlNode.syncClassAttribute()` (`:131-138`)、
`DomUtils.updateAttribute()` (`:38-59`)、
`DomDiffEngine.diffAndPatch()` (`:36-56`)、
`DomUtils.applyTemplateData()` (`:186-195`)。
新しい書き込み経路が同期を忘れるとセレクタエンジンが黙ってずれる。

### C-2. `HtmlNode` が 3 つの関心事を混載

文書構造（`tag`/`attrs`/`children`/`parent`/`text`）、
対話状態（`isHovered`/`isChecked`/`isActive`/`isFocused`）、
スタイルキャッシュ（`computedStyle`/`isDirty`）。
対話状態が文書に乗っているため、**同一ドキュメントを 2 つのビューで共有できない**。

専用のテキストノード型が無く、`"#text".equals(node.tag)` の文字列比較が
`DomUtils` 6箇所 / `CssParser` 3箇所 / `LayoutBox` 3箇所 /
`BlockLayoutEngine` 2箇所に散在。

`attrs` が `HashMap` なのにトークナイザは `LinkedHashMap` を作っている
（`HtmlTokenizer.java:189`）→ 属性順が失われ、`getOuterHTML()` の出力が
非決定的。これはシリアライズとして使われているので実害がある。

### C-3. ノード ID が不安定なプロセスグローバルカウンタ

```java
// HtmlParser.java:17, :50-53
private static final AtomicInteger globalAutoId = new AtomicInteger(0);
...
if (node.id.isEmpty()) {
    node.id = "html_gen_" + globalAutoId.incrementAndGet();
    node.attrs.put("id", node.id);
}
```

同じ HTML を再パースすると ID が変わるので、ID をキーにしたクライアント状態
（フォーカス・スクロール・アニメーション）がリロードごとに無効化される。
生成 ID は `attrs` に書かれるため `getOuterHTML()` と NBT 同期にも漏れる。
カウンタはプロセス寿命の間、無制限に増える。

その上でレンダラは `id + "_txt_" + n` / `_bg` / `_b_top` / `_sb_v` … と
**文字列連結で派生 ID を作り**、クライアントが 21 要素のサフィックス表で
剥がし直している（`client/ClientWebManager.java:49-52` の `RENDER_SUFFIXES`
と `getBaseNodeId()`）。ハンドルシステムが文字列操作で実装されている。

### C-4. `DomDiffEngine` が位置ベース（キー無し）

`HtmlNode` は既に `id` を持っているのに、diff はそれを**照合に使わない**
（`DomDiffEngine.java:66-87`）。append/truncate/in-place 編集には正しいが、
リストの先頭挿入や中間削除で**後続の全兄弟が再パッチされ**、
位置でタグが食い違うと部分木ごとクローンされてノード同一性が失われる
（＝`isFocused`/`isActive`/`computedStyle` とクライアント側の
ID キー状態が消える）。

付随して: 余剰子の削除時に `child.parent` をクリアしない（`:92`）ので
`CssParser` の結合子照合が辿る親ポインタが腐る。
`isFocused`/`isActive` は一切同期されない。

### C-5. HTML が 3 つの文法で 3 回処理されている

1. `UIParser.expandCustomTags`（`:238-351`、**114 行**）— 正規表現で
   `<mc-slot>`/`<mc-slot-grid>`/`<mc-inventory>`/`<mc-bar>` を `<div>` の塊に
   書き換え、`resolveIncludes`（`:211-227`）と
   `<script>`/`<style>`/`<link>` の正規表現抽出（`:369-391`）
2. `MarkdownParser.processDocumentOrBlocks`（`:54-104`）— **第 3 の**正規表現
   HTML マッチャ（`MD_CONTAINER_PATTERN`）。しかも `expandCustomTags:240` から
   **全ドキュメントに無条件で**呼ばれるので、Markdown 正規表現が
   すべての HTML を毎回走る
3. `HtmlParser`/`HtmlTokenizer` — 本来のパーサ

正規表現層がトークナイザの**前**に走るため、生テキストを見て
`<script>` 内容やコメント/CDATA を壊しうる。

`HtmlParser` の void 要素集合（`:12-15`）は HTML と `mc-*` を混ぜており、
`DomUtils.java:16` には**異なる第 2 の `VOID_TAGS`**（HTML のみ）がある。
2 つの真実の源。

暗黙クローズが一切無い（`<p>`/`<li>`/`<td>` の自動閉じ、table foster parenting なし）。
`<p>a<p>b` が兄弟でなくネストする。

---

## D. クライアント層（約 10,650 行 / 23 ファイル）

パイプラインは次の形。`UIElement`（不変レコードのフラットリスト）が
レイアウトと描画の唯一の受け渡し形式になっており、
**6 つの描画ホスト（画面・コンテナ画面・HUD・トラッカー・ブロック面・手持ちアイテム）
を 1 つのペインタで共有できている**。これはこの層で最も正しい設計判断。

```
LayoutBox tree → UIRenderer → List<UIElement> → WebRenderCore.renderElements(...) → 頂点
```

以下の問題のほとんどは、この `UIElement` が**構造体ではなく文字列**で
情報を運んでいることに起因する。

### D-1. `UIElement` 境界が文字列で情報を失っている（この層の根本原因）

`UIRenderer` が構造化データを文字列に平坦化し、下流が parse し直している:

```java
// UIRenderer.java:908-914
private static String formatRadii(int[] r)      { return r[0]+","+r[1]+","+r[2]+","+r[3]; }
private static String formatClipRect(float[] c) { return c[0]+","+c[1]+ ... +","+c[7]; }

// UIRenderer.java:318 — 疑似クラス状態を区切り文字列に
return has ? (marker + op+":"+tx+":"+ty+":"+sx+":"+sy+":"+rot+":"+col+":"+bgCol) : "";
// :484 — それを 3 つ連結して 1 フィールドに詰める
String animDef = parseAnimationDef(...) + hoverStr + activeStr + focusStr;
```

`WebAnimator.extractMarker`（`:218-237`）が `indexOf("@@HOVER@@")` + `split(":")` で
読み戻し、**`ClientWebManager.getHitElement:543-544` は
`animDef().contains("@@HOVER@@")` をヒットテストの判定条件に使っている**。

**これは半分終わった移行**: `RenderMeta` レコードは既に
`clipRect`/`borderRadii`/`gradient`/`circleBorder` を構造的に持っており、
`WebAnimator:255-292` は `meta` を先に見て文字列解析にフォールバックする。
→ 移行を完了させれば `buildPseudoStateDef`・`extractMarker`・
`parsePseudoDefs`・`contains()` ヒットテストがすべて消える。

同様に `el.type()` が `String` で、約 45 箇所で `.equals` 比較される
（`"node"`, `"rect"`, `"text"`, `"input"`, …）。画像のフィットモードは
**型文字列に埋め込まれている**（`UIRenderer:652` で `"img:contain"`、
`WebRenderCore:661` で `split(":")[1]` し直す）。

### D-2. レンダー ID のサフィックスが事実上のプロトコルで、3 箇所に重複

レンダラが `id + "_bg"` / `_bbg"` / `_ph"` / `_txt_N"` / `_frag_N"` / `_b_top"` /
`_sb_v"` … を**文字列連結で**生成し（約 25 箇所）、消費側が `endsWith` で剥がす。
その一覧が 3 箇所にある:

- `ClientWebManager:49-52` — `RENDER_SUFFIXES[21]`（`getBaseNodeId` 用、正典）
- `ClientWebManager:522-523` — ヒットテスト除外リスト
- `ClientWebManager:372` — `getElementById` フォールバック

**既にドリフトしている**: `"_shadow"` は後ろ 2 つにあるが
`RENDER_SUFFIXES` に**無い**ので、`getBaseNodeId("foo_shadow_1")` は
`"foo"` ではなく `"foo_shadow_1"` を返す。

`getBaseNodeId` は `renderElements` 内で要素あたり 3〜4 回
（`:195,285,287,288`）＋ヒットテスト全体から呼ばれ、
毎回 21 回の `endsWith` を走らせる。基底 ID は
**それを知っている `UIRenderer` で 1 回計算して `UIElement` のフィールドに持てばよい**。

### D-3. `AbstractWebScreen` 1,508 行の実体は入れ子クラス

ファイルはきれいに 2 つに割れている:

| 範囲 | 行数 | 正体 |
|---|---|---|
| `AbstractWebScreen`（`Screen` 本体） | 43–338 | **296 行** — 薄い委譲シェル |
| `AbstractWebScreen.WebUiScreenSupport`（static 入れ子） | 344–1508 | **1,165 行** — 本当の god クラス |

つまり**抽出作業は既に一度行われているが、すべてを 1 つの袋に入れてしまった**。
`WebUiScreenSupport` のフィールドは 8 つの無関係な関心事が並ぶ:
IME ブリッジ / ビューポート / イベントルータ 2 マップ / textarea エディタ /
フォーカス / `<select>` ポップアップ / 3D シーンカメラ / HTML5 DnD。

抽出価値の高い継ぎ目:

| 継ぎ目 | 範囲 | 行数 | 備考 |
|---|---|---|---|
| `WebFocusNavigator` | 994–1213 | 220 | 既に自己完結。`FocusCandidate` レコード・tabindex ソート・空間ナビ採点。依存は `sessionId` + `ClientWebManager` のみ |
| `WebTextareaEditor` | 1227–1354 | 128 | 完全な複数行テキストエディタ |
| `WebDevTools` | 598–680 | 83 | F12 押下中、**毎フレーム** CSS ボックス辺を再パース（`parseBoxEdgesStr` + `parseDim` 8 回、`:624-634`） |
| `SelectDropdownWidget` | 557-586, 687-706, 843-865, 1502-1503 | ~70 | 1 ウィジェットが 4 箇所に分散 |
| `WebViewport` | 392–447 | 56 | 依存ゼロ。両画面のフィールドミラーリングも解消できる |
| `WebActionRouter` | 464–509 | ~45 | 2 マップ + `dispatchRoutedAction` |
| 相互作用ハンドラ連鎖 | 682–832 | 151 | `handleMouseClicked` の 9 段 if/else を `InteractionHandler` の連鎖に |

**最悪の層違反**: textarea のキャレット/スクロール状態が**描画層に住んでいる**:

```java
// WebRenderCore.java:64-66 — エディタ状態がレンダラ所有
public static final Map<String,Integer> inputScrollOffsets  = new ConcurrentHashMap<>();
public static final Map<String,Integer> textareaCursorIndex = new ConcurrentHashMap<>();
public static final Map<String,Integer> textareaScrollRow   = new ConcurrentHashMap<>();
```

入力層（`AbstractWebScreen:922-924,1236,1254,1268-1350`）が書き、
描画層（`WebRenderCore:808,1315`）も書き戻す。

現実的な目標: `WebUiScreenSupport` 1,165 行 → 約 250 行の純ディスパッチ ＋ 7 協力クラス。

### D-4. 2 つの画面クラスが 38% 同一で、既に 5 つのバグに分岐している

空白正規化して差分を取ると、**`AbstractWebContainerScreen` の 486 行中
187 行（38%）が `AbstractWebScreen` とバイト単位で同一**。
最大の連続一致は 29 行（`renderElements(...)` の **22 引数呼び出し** +
`renderOverlays` + `popPose`、AWS 223-252 / AWCS 343-372）と
22 行（1 行委譲アクセサ 13 個、AWS 305-326 / AWCS 457-478）。

コピーされた結果、**黙ってドリフトしたバグが 5 つ**:

1. **コンテナ画面で `onmouseenter`/`onmouseleave` が発火しない** —
   `AbstractWebScreen:215` は `ClientScreenInteractionManager.handleHover(...)`
   （enter/leave を配送する）を呼ぶが、`AbstractWebContainerScreen:335-336` は
   生の `getHitElement` をインライン化して `hoveredId` を代入するだけ
2. **サーバー管理のコンテナセッションが再同期を要求しない** —
   `AbstractWebScreen:208-210` の `ensureSynced(sessionId)` が
   コンテナ側の `render`/`renderBg` に無い
3. **`initOrBindSession` の戻り値が捨てられている**（AWCS:137）ので
   `isLocalHtmlSession` が無く、`onClose`（`:479`）が無条件にセッションを消す
   （AWS:327 は条件付き）
4. **入力コールバックの配線が違う** — AWS:118 は `this::notifyInputChanged`
   （`onInputChanged` と `onWebInputChanged` の両方に配る）、
   AWCS:121 は `this::onWebInputChanged` のみ。
   **コンテナ画面のサブクラスは `onInputChanged` を受け取れない**
5. `renderElements` の第 19 引数が `true`（AWS:237）vs `false`（AWCS:357）—
   `playAudio` フラグ。**22 個の位置引数の中に埋まっており、
   名前付き引数が無いので取り違えが起きた**。これはまさに巨大位置引数が
   生むバグの典型

### D-5. 引数の数が制御不能 — レコード 2 つで約 12 個減る

| メソッド | 引数数 |
|---|---|
| `WebRenderCore.drawRoundedTexturedRect`（`:1065`） | **29** |
| `WebRenderCore.renderImageOrMediaElement`（`:577`） | **28** |
| `WebRenderCore.drawClippedTexturedQuad`（`:1100`） | 25 |
| `WebRenderCore.drawRoundedRect`（`:997`）/ `drawGradientRoundedRect`（`:955`） | 23 |
| `WebRenderCore.renderElements`（`:239`） | 22 |
| `WebRenderCore.renderElementBackground`（`:454`） | 21 / `UIRenderer.addElement`（`:238`） 21 |

このうち 12 個は常に同じ 2 つのタプル:
`(clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL)` と
`(rTL, rTR, rBR, rBL)`。**`ClipRect` と `Corners` の 2 レコードで
9 つのシグネチャから約 12 引数が消え、D-4 の `playAudio` 取り違えが
構造的に起こり得なくなる。**

**死んだ引数**: `renderElements` の `int renderMode`（`:245` 宣言）は
**本体で一度も参照されず**、6 つの呼び出し元すべてが `0` を渡す。
`ICustomRenderer customRenderer` は `:343` で 1 回参照されるが
**6 呼び出し元すべてが `null`** を渡す。
`private static boolean currentPassIs2D`（`:56`）は `:247` で書かれて**一度も読まれない**。

幾何ライブラリにも 4 つのほぼ同一な角丸ラスタライザがあり、
UV を出すかどうかだけが違う（`drawRoundedRect`/`drawRoundedTexturedRect`、
`drawClippedQuad`/`drawClippedTexturedQuad`）。角の内側オフセット式
`sqrt(r²−(r−i−0.5)²)` が**16 箇所に逐語的に出現**。

### D-6. SDF シェーダが要素ごとにバッチをフラッシュしている（最大のフレーム時間問題）

`useSdfShader` は既定 `true`（`WebRenderCore:55`）で、角丸を描く 3 経路すべてが
ここを通る。**角丸要素 1 つ・1 フレームあたり**:

```java
// WebSdfShader.java:199-220（確認済み）
Minecraft.getInstance().renderBuffers().bufferSource().endBatch();   // バッチ全フラッシュ
int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);        // GL クエリ → パイプラインストール
RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.enableDepthTest();
try (MemoryStack stack = MemoryStack.stackPush()) {
    GL20.glUseProgram(programId);
    ... glUniformMatrix4fv ×3（modelView / proj / pose）...
    ... glUniform4f ×4 ...
    ... BufferBuilder で 4 頂点 → BufferUploader.draw(mesh);   // 1 ドローコール
}
```

→ **角丸 200 要素の UI = 毎フレーム 200 回のバッチフラッシュ + 200 ドローコール +
200 回の GL ステートクエリ + 600 回の行列アップロード**。
渡された `VertexConsumer builder` を完全に無視しており、Mojang のバッチングを迂回する。
`renderElementBackground:472` は、直後にフラッシュされる gui バッファを
その直前に取得している。

副作用: `RenderSystem` の blend/depth を変更して**復元しない**。
プログラムの復元は `RenderSystem` 経由でなく生の `glUseProgram` なので
Mojang の追跡ステートがずれる。

行列ユニフォームはパス全体で定数なので、**最低でも巻き上げるだけで効く**。
本来はインスタンシング / UBO でバッチ化すべき。

### D-7. セッション状態が 13 本の並列マップに散っている

`ClientWebManager:31-47` — 同一の `String sessionId` をキーにした
`ConcurrentHashMap` が **13 本**:
`RENDER_CACHE`, `DOM_CACHE`, `VIRTUAL_DOM_CACHE`, `TEMPLATE_DOM_CACHE`,
`SERVER_MANAGED_SESSIONS`, `DIRTY_SESSIONS`, `REQUESTED_SESSIONS`,
`SYNC_REQUESTS`, `DOM_HASH_CACHE`, `LAST_ROOT_W`, `LAST_ROOT_H`,
`SESSION_ENV`, `SESSION_CLEARED_LISTENERS`
＋ public 可変コールバック 3 本。

`clearSession`（`:636-654`）はこの 13 本＋外部マネージャ 5 つを
**全部覚えていなければならない**（ショットガン解体）。
`Map<String, SessionContext>` 1 本にすれば
`SESSIONS.remove(id).close()` で済む。

クライアント層の static 可変フィールドは全体で **44 個**。
`WebHudRenderer` のトラッカーは `currentTrackerSessionId` が単一スロットなので
**セッションごとでなくクライアントごとの singleton** ——
2 つ目の `data-tracker` セッションが 1 つ目を黙って追い出す。

**`WebRenderCore.activeInputId` / `activePressedId` は
`public static String`（`:68-69`、確認済み）でセッション別でない。**
GUI 画面とブロック画面、あるいはブロック画面 2 枚が同時に見えていると
フォーカスを共有してしまう。

### D-8. リソースリーク（確認済み）

| リーク | 場所 | 内容 |
|---|---|---|
| **`ClientLevel` 全体を保持** | `WebRenderCore:67,100` | `ENTITY_PREVIEW_CACHE` が `type.create(finalLevel)` で作った**生きた `Entity`** を保持し、**一度もクリアされない**。`Entity` は `Level` 参照を持つので、ワールドアンロード後も `ClientLevel` のオブジェクトグラフ全体が残る |
| GPU テクスチャ | `WebMediaManager:195-203` | `IMG_CACHE` に上限も追い出しも無く、各エントリが登録済み `DynamicTexture`。`release` も `TextureManager.release` も無い。さらにテクスチャ ID が `url.hashCode()` の 32bit — **衝突した 2 URL が同じテクスチャを上書きし合う** |
| textarea 編集状態 | `WebRenderCore:64-66` | セッション接頭辞が無いキー（`"foo_textarea"`）で、どの `clearSession` も触らない。ゲームセッション中ずっと増える |
| OpenAL バッファ | `WebMediaManager:853-869` | `alDeleteBuffers` が `if (!alSources.isEmpty())` の内側。音声を持つが一度も「可聴」にならなかった動画は閉じるときに 4 バッファを漏らす。無条件にすべき |
| フォント | `WebFontManager:213,227` | `FONT_CACHE` が `FontSet`（グリフアトラステクスチャを所有、`AutoCloseable`）を解放しない。`clearCache()` は存在するが**どこからも呼ばれていない** |
| SDF プログラム | `WebSdfShader` | `glDeleteProgram` されず、`initialized` もリセットされないのでシェーダリロード後に古い program id を使い続ける |
| `yt-dlp` キャッシュ | `WebMediaManager:351-352` | TTL *チェック*はあるがエントリが削除されない |
| トラッカーキャッシュ | `WebHudRenderer:310-312` | `if (size > activeUuids.size() + 10) retainAll(...)` — 常に最大 10 個を恒久的に漏らす |

### D-9. ヒットテストが毎イベント O(n) で、クリップを無視する

```java
// ClientWebManager.java:515-557
List<UIElement> elements = getElements(sessionId);
Map<String, UIElement> elementMap = new HashMap<>(elements.size());
for (UIElement el : elements) elementMap.put(el.id(), el);   // ★ 呼び出しごとに再構築
for (int i = elements.size() - 1; i >= 0; i--) {              // 逆ペイント順
    UIElement el = elements.get(i);
    if ("node".equals(el.type()) || ... || el.id().contains("_b_")) continue;  // ID サフィックス文字列フィルタ
```

- **O(n) / イベント**、しかも `elementMap` を**呼び出しごとに**作り直す。
  `handleHover`（ホストごと毎フレーム）・`handleClick`・`handleScrolled`・
  `handleMouseReleased`・`renderBg`・`WebRecipeViewerBridge` から呼ばれる
  ＝ 毎フレーム数回のフル再構築
- **相互作用可能かを文字列接頭辞で推測**（`:532-544`）:
  `key.startsWith("on")`, `"mc-title".equals(key)`, `"draggable".equals(key)`,
  `text.startsWith("item:")`, `parentAnchor.animDef().contains("@@HOVER@@")`
- **ヒットテストにクリップ矩形の判定が無い。** `RenderMeta.clipRect` は
  ペインタは尊重するが `getHitElement` は無視する →
  **`overflow:hidden` でスクロールアウトしたコンテンツが
  クリック可能なまま**（実バグ）
- `handleHover(session, -1, -1)` が「マウスが出た」のマジック値（`:159`）

レイアウトごとに 1 回だけ z ソート済みの
`(rect, clipRect, transformChain, nodeId)` 配列を作れば
O(log n) になり、クリップのバグも直る。

### D-10. 入力系が 2 本立てで、世界側は GLFW を直接ポーリングしている

```java
// ClientScreenInteractionManager.java:87-92 — tick レートでのエッジ検出
boolean glfwLeft  = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT)  == GLFW.GLFW_PRESS;
boolean isMouseDown = glfwLeft || glfwRight || mc.options.keyUse.isDown() || mc.options.keyAttack.isDown();
boolean isMouseJustPressed  = isMouseDown && !wasMouseDown;
```

20 Hz のエッジ検出なので **50 ms 未満のクリックが落ちる**し、
左右ボタンの区別が `isMouseDown` に潰れ、
生 GLFW 側はキーバインド再割り当てを無視する。

### D-11. イベントモデルが無い — バブリングもキャプチャも `preventDefault` も無い

イベントオブジェクトも伝播フェーズも無く、代わりに:

1. **祖先*探索*がバブリングの代用**を、イベント種別ごとに手書きで:
   `findAncestorByTag`（`AbstractWebScreen:834-841`）を
   `summary`（`:749`）・`label`（`:757`）・`form`（`:812`）に対して呼ぶ。
   `WebRecipeViewerBridge:148-162` が `data-item`/`data-fluid` 用に
   同じ上方歩行を独立再実装。つまり「バブリング」は
   **誰かが思い出した 4 ケースにだけ**存在する
2. **ハンドラ解決が文字列ルータ**（`dispatchRoutedAction:486-509`）。
   `"server:"` のマジック接頭辞と `substring(7)` のマジック長、
   `containsKey` + `get` の二重ルックアップ。
   ディスパッチ層が 4 段（exact マップ → prefix マップ →
   `onWebAction` テンプレートメソッド → `triggerLocalEvent` が C2S パケット化）
3. **同じ操作で 5 本の通知経路が走る。**この 4 行の呪文が
   `handleMouseClicked` だけで **9 回**コピペされている
   （`:696-699, 766-770, 787-798, 884-885`）＋
   `handleTextareaKeyPressed`・`insertIntoTextarea`・`adjustFocusedRangeSlider`:
   ```java
   ClientWebManager.updateNodeAttributeLocally(sessionId, id, "value", v, false);
   ClientWebManager.processRenderQueue();
   if (this.state != null) this.state.notifyElementModelChanged(id, v);
   if (onInputChanged != null) onInputChanged.accept(id, v);
   ```
   `commitValue(id, v)` 1 メソッドで 9 箇所が消える

キーボード処理は `isEditingTextarea` / `activeInputId.isEmpty()` /
`focusedElementBaseId.isEmpty()` の**3 つの boolean でモードを表現する**
手書きステートマシン（`:939-992`）。enum 1 つにすべき。

### D-12. `InvisibleInputScreen` — IME ハックが描画層を GUI 層に依存させている

Minecraft には `Screen` の外にテキスト入力 API が無い。そこで
**完全透明な `Screen` を開き、画面外の `EditBox` を載せるだけ**という手を使う
（`InvisibleInputScreen:41-50`、`render` は意図的に何も描かない `:61-63`）。

問題は、**ペインタがその `Screen` の中身を取りに行くこと**:

```java
// WebRenderCore.java:215-237
public static EditBox findActiveEditBox() {
    Screen screen = Minecraft.getInstance().screen;
    if (screen instanceof InvisibleInputScreen iis) return iis.getActiveEditBox();
    if (screen.getFocused() instanceof EditBox eb) return eb;
    EditBox fallback = null;
    for (var child : screen.children()) {        // ★ 任意の画面の全ウィジェットを走査
        if (child instanceof EditBox eb) { if (eb.isFocused()) return eb; if (fallback == null) fallback = eb; }
    }
```

- **レンダラが GUI パッケージに依存**（`renderer` → `gui` の逆向き依存）
- 「任意の画面の子から `EditBox` を探す」フォールバックは、
  **他 Mod やバニラのテキストフィールド**を掴んで
  その内容を Web UI の入力描画に流し込みうる
- GUI 画面側は `(-2000,-2000)` / `setMaxLength(2048)`（`AbstractWebScreen:450-451`）、
  こちらは `(-1000,-1000)` / `8192` — **同じトリックの実装が 2 つ、制限値が違う**
- Enter/ESC/textarea 改行のロジックも両者が独立に実装
- `distSqr > 64.0`（`InvisibleInputScreen:70`）は**二乗距離**を
  半径に見える値と比べている。実際の閾値は 8 ブロックで、64 ではない

### D-13. リアクティビティが 4 系統あり、相互運用しない

| 系統 | 真実の源 | 契機 | 粒度 |
|---|---|---|---|
| `WebStateStore`（501 行） | 画面インスタンスごとの `Map<String,String>` | `set()` 即時 / 毎フレーム `syncToDom()` | 木全体の再帰歩行 |
| `ClientPubSubManager`（227 行） | static な `clientCache` | S2C パケット | バインディング単位 ✅ |
| `ClientLocalEnvProvider`（242 行） | static な `CACHED_SNAPSHOT` | 100 ms ポーリング | 木全体の再帰歩行 |
| `WebHudRenderer.applyTemplateData`（`:501-569`） | `HitResult` データ | HUD フレーム | **同じ置換処理の 4 つ目の再実装** |

4 つ目は `WebStateStore` の static（`evaluateCondition`・
`extractTemplateChildren`・`replacePlaceholdersWithData`）を呼び戻しており、
**半分を借用して半分を重複実装**している。

`DeclarativeBindingManager`（119 行）はリアクティブエンジンではなく
**一回限りの DOM スキャナ**（`initializeBindings:16-78`）。
`ClientWebManager:171`（設置時）・`:250`（`inner_html` パッチ）・`:423` から呼ばれる。

> **リーク**: `initializeBindings` は `dataBindings`/`virtualListBindings` に
> **重複排除なしで追加**する（`ClientPubSubManager:38,42`）。
> `inner_html` パッチごとに再実行されるので、
> `setInnerHtml` を繰り返すと同じ `targetId` のバインディングが
> 永久に積み上がり、チャンネル更新ごとに全部が再適用される。

**毎フレームのコスト**: `syncToDom()`（`AbstractWebScreen:206`・
`AbstractWebContainerScreen:377` から毎フレーム）が `pollBoundSuppliers`
（`:162-182`）で全 Supplier を呼び、**バインド済みリストを丸ごとディープコピー**
（`copyListItems:146-153`、新 `ArrayList` + 新 `HashMap` 群）して
`equals` 比較するだけ。60 fps・100 行のリストで
**「何も変わっていない」ことを確かめるために毎秒 6,000 個の `HashMap`**。

`dirty` は**ストア全体で 1 つの boolean**（`:37`）なので、
1 キーの変更が全体を無効化する。`applyRecursive` は **207 行**（`:197-403`）で、
毎回全ノードについて i18n・`{{}}`・全属性プレースホルダ・`data-model`・
`data-bind`・`data-if`・`data-show`・`data-class`・`data-list` を再評価する。
**キー単位の依存追跡が無いので、どのノードがどのキーを読むか分からない。**

リストの ID は `id + "_row_" + index`（`uniquifyIds:467-473`）＝**位置依存**。
先頭に 1 行挿入すると以降の全行の同一性が壊れ、
`WebAnimator` の状態とフォーカスが消える。

### D-14. 毎フレームのレイアウト再計算が簡単に起きる

`ClientWebManager.getElements`（`:327-337`）は**毎回**
`ClientLocalEnvProvider.tickSessionLocalVariables` を呼ぶ。
100 ms スロットル（`ClientLocalEnvProvider:166`）はあるが、
発火して `{{@…}}` が 1 つでも変わると
`requestRender` → `processRenderQueue` → `DomDiffEngine.diffAndPatch` →
**`recomputeLayout` ＝ 木全体の `renderDocument`**。

→ **`{{@fps}}` / `{{@time}}` / `{{@hp}}` / 座標を含む UI は
文書全体を毎秒 10 回レイアウトし直す。**dirty 部分木の経路が無い。

`WebHudRenderer` だけが正しくやっている —
`processHudTemplates:220-223` が `lastHudDataCache` との
深い等価比較でショートサーキットする。このガードが
`WebStateStore` / env 層にも必要。

その他の毎フレーム確保・毎フレーム文字列処理:

| 箇所 | 内容 |
|---|---|
| `WebAnimator.cleanup`（`:96-106`、`WebRenderCore:269` から毎フレーム） | O(状態数 × 要素数)。500 要素で**毎フレーム約 25 万回の文字列比較 + 500 回の `substring`** |
| `WebRenderCore:1281` | `fullText.replace("\r\n","\n").split("\n",-1)` — textarea 全文を毎フレーム再分割 |
| `WebRenderCore:511-520` | range 要素ごとに `split(":")` + `Float.parseFloat` ×3 — 値は既に `anim.rangeVal/Min/Max` にある |
| `WebRenderCore:182-184` | 可視 textarea 行ごとに正規表現 `replaceAll` ×3 |
| `WebRenderCore:403-425` | ホバー要素に `ResourceLocation.parse` + レジストリ検索 + `new ItemStack` |
| `WebRenderCore:272-273` / `ClientWebManager:517-518` | 要素 ID マップを毎フレーム・毎ヒットテストで再構築 |
| `UIRenderer:61-62,145,160` | ボックスごとに `RenderTask` レコード + ラムダ |
| `WebHudRenderer:247` | HUD フレームごとに要素リスト全体をコピー |

### D-15. スレッド処理 — 1 クラスに閉じていて、概ね正しい

レンダースレッド外で動くのは **`WebMediaManager` だけ**
（grep 確認: 他に `CompletableFuture`・生 `new Thread`・executor は無い）。
固定 4・デーモン・命名付きの `MEDIA_IO_EXECUTOR`（`:56-61`）は良い。
`Minecraft.getInstance().execute(...)` と `RenderSystem.recordRenderCall(...)` の
使い分けも正しく、**レンダースレッドで `.get()`/`.join()`/`waitFor()` を
待つ箇所は無い**（volatile フラグ方式）。

残る危険:

1. **`close()` が `initVideoWorker` と競合**: `initVideoWorker` は
   `isClosed` を `:546` と `:549` で見て `:555` で `nativeHandle = handle` を代入。
   `close()`（`:846`）はその間に走ると `0` を見て `closeVideo` を飛ばし、
   ワーカーが**解放されないハンドルを公開する**（ネイティブリーク）。
   二重チェックは緩和策で、CAS かロックが必要
2. `ProcessBuilder(...).waitFor()` が**タイムアウト無し**で worker 上
   （`:385` の `yt-dlp -U` と `:404`）。4 スレッドプールなので
   遅い yt-dlp が 4 本走ると**画像ダウンロードを含む全メディア I/O が飢餓**
3. `ClientPubSubManager.activeSessions` が `ConcurrentHashMap` の中に
   素の `HashSet`（`:25`）。今はパケットハンドラが
   メインスレッドに enqueue されるから安全なだけ。
   `ConcurrentHashMap.newKeySet()` にすべき
4. `ClientLocalEnvProvider.CACHED_SNAPSHOT` を
   `clear()` → `putAll()` と**非原子的に**更新し（`:123-124`）、
   **生の可変マップを返す**（`:126`）。
   並行読み出しが空または半分埋まった env を観測しうる
5. `alBuffers`（`int[4]`、`:494`）の公開がモニタで順序付けられていない

### D-16. ログが 2 行、例外の握り潰しが 61 箇所

**クライアント層 10,656 行に対して LOGGER 呼び出しは 2 箇所だけ**
（`WebFontManager:192,222`）。それ以外は全部無言。
`printStackTrace` / `System.out` は **0 件**（そこは良い）。

危険な握り潰し:

- `WebMediaManager:466` — `catch (Throwable t) { return null; }` が
  ネイティブロード失敗の**全原因**（未対応プラットフォーム・
  ランタイム欠落・展開失敗・ウイルス対策の隔離）を `null` に潰す
- **3 つの `static {}` ブロックが NeoForge イベントリスナ登録を
  `catch (Throwable ignored)` の中で行っている** —
  `WebHudRenderer:91-96`, `WebMediaManager:219-224`,
  `ClientScreenInteractionManager:61-66`。登録が失敗すると
  サブシステムが**無言で不活性**になり、しかも
  登録がクラスロード順に依存する
- `AbstractWebContainerScreen:76` / `:304` — `Slot.x`/`Slot.y` の
  リフレクション失敗を握り潰す。MC 更新でフィールド名が変わると
  **全スロットが黙って誤った位置に描かれる**
- `AbstractWebScreen:106,111` — リソース読み込み失敗を握り潰し、
  HTML エラー*文字列*に変換（`:112`）。
  `EnderFrameClientInit:62` がそれを部分文字列一致で検出する
  ＝ 2 ファイルにまたがる文字列型エラーチャネル
- `WebStateStore:56,70,80,171,180` — **ユーザー提供の
  `Supplier`/`Consumer` コールバック**を `catch (Exception ignored) {}` で包む。
  バグったバインディングが毎フレーム不可視に失敗する

### D-17. パッケージ宣言とディレクトリの不一致（確認済み）

```
renderer/WebStateStore.java -> package com.nishiyu.enderframelib.client.binding;
```

14 ファイル中これ 1 つだけが不一致。Gradle は通るが、
IDE ナビゲーション・`javac -sourcepath`・多くの静的解析が壊れる。
参照側は正しく `client.binding.WebStateStore` と書いている
（`AbstractWebScreen:7` 他）。`binding/` に移すだけ。

### D-18. その他

**死んだコード**: `WebRenderCore.currentPassIs2D`、`renderElements` の
`renderMode` 引数、`WebFontManager.clearCache()`（呼び出し元ゼロ）、
`WebAnimator.getValues` 3 引数オーバーロード・`isElementAnimating`・
`isAnimating`（いずれも呼び出し元ゼロ）、`ICustomRenderer`（全呼び出し元が `null`）、
`FontFaceSpec.shiftX/shiftY`（常に `0.0f`）。

**層違反**: `renderer/WebRenderCore` → `client.gui.InvisibleInputScreen`、
`renderer/UIRenderer` → `net.minecraft.client.gui.components.EditBox` と
`WebRenderCore.findActiveEditBox()`（`:249`、**レイアウトコンパイラが
生きたウィジェット状態を覗く**）、`renderer/WebHudRenderer` →
`network.c2s.SubscribeC2SPacket`（パケット送信、`:170`）。

**リフレクション 2 箇所**（どちらもキャッシュ済み・失敗無言）:
`AbstractWebContainerScreen:63-77` が `Slot.x`/`Slot.y` を名前で探す
（**Mojmap 1.21.1 では public 非 final なので、おそらく不要**）、
`WebFontManager:141-152` が `Font#getFontSet` を**名前でなく形**
（戻り型 + 引数数）で照合する — 将来 MC に
同形のメソッドが増えると `getDeclaredMethods()` の順序が未規定なので誤バインドしうる。

**マジック数**: Z レイヤの float オフセット（`280.0f`/`300.0f`/`320.0f`/
`350.0f`/`35.0f`/`20.0f`/`0.002f`…、定数クラス無し）、
**OpenAL の enum を `AL10` 定数でなく数値リテラルで書いている**
（`4353`/`4355`/`4106`/`4099`/`4112`/`4114`/`4105`/`4100`/`4118`/
`4117`/`4128`/`514`/`53248`/`53252`）、生 GL（`3553`/`35052`/`3314-3317`/
`6408`/`5121`）、Catppuccin パレットの ARGB 直書き（`0xFF89B4FA` が 5 箇所ほか）、
`itemH = 18` が `:561` と `:688` に重複、`1920`/`1080` が 5 箇所・
`854`/`480` が 4 箇所（同じ概念に 2 組の既定値）。

**`toLowerCase()` に `Locale` 指定なし**（`UIRenderer:117,119,492,579,584` 他）—
CSS キーワード比較でトルコ語ロケールの `I`/`ı` 問題。

**コメントの嘘**: `WebHudRenderer:169` の
`// 最新の8引数コンストラクタを使用`（引数数は腐る）、
`WebMediaManager:17` と `build.gradle:78` が
**存在しないクラス `ClientMediaManager`** を参照。

---

## E. 層構造 — パッケージ循環 11 組

import 解析の結果、双方向依存が 11 組:

```
server         <->  api            (2 / 5)     ← API が自分の実装に依存
server         <->  common         (3 / 1)
api/config     <->  common         (1 / 2)
common         <->  core/dom       (2 / 1)     ← core が上位層に依存
common         <->  core/layout    (1 / 1)     ← 同上
core/dom       <->  core/css       (1 / 1)
client         <->  client/renderer (8 / 12)
client         <->  client/gui     (3 / 5)
client         <->  client/binding (4 / 1)
client/renderer<->  client/gui     (1 / 7)
client/gui     <->  client/compat  (1 / 1)
```

### E-1. 良い知らせ: `core/` は**たった 3 箇所**でリーフになる

`core/` から `core/` 外への依存は **2 import / 3 呼び出し箇所だけ**:

- `core/dom/DomUtils.java:3` → `common.UIParser`
  （使用は `:127` の `UIParser.expandCustomTags(html)` 1 箇所）
- `core/layout/LayoutBox.java:3` → `common.ServerImageRegistry`
  （使用は `:197`, `:288` の `getSize(src)` 2 箇所）

`core/` を参照する側は 62 import（`client/renderer` 18 / `client` 15 /
`client/gui` 10 / `server` 6 / `common` 6 / `client/binding` 4 / `client/compat` 3）
= 一括再構築の影響範囲。

### E-2. ただし現状 `core/` は**ヘッドレステスト不可能**（当初の見立ての訂正）

直接 import で `net.minecraft` を使うのは `core/dom/HtmlNodeSerializer.java`
（NBT）の 1 ファイルだけ。`net.minecraft.client` は core に 0 件。
**しかし推移的な鎖がある**:

```
core.dom.DomUtils
  → common.UIParser                        (UIParser.java:9)
      → server.EnderFrameDataPackManager   (SimplePreparableReloadListener を継承)
          → net.minecraft.server.packs.resources.*
```

`HtmlNode.setInnerHTML` → `DomUtils.setInnerHTML` → `UIParser.expandCustomTags`
なので、**`HtmlNode` の変更 API を Minecraft クラスパス無しに動かせない**。
層の向きも `core → common → server` と逆流している。

`LayoutBox` → `ServerImageRegistry` は純 Java だが、**プロセスグローバルな
可変レジストリ**を固有サイズ計算の中から静的に呼ぶため、
レイアウト結果が画像ダウンロード状態に依存し、テストで再現できない。

→ **この 3 箇所を直すだけで `core/` は素の Java として動く。** 今回の最大のレバレッジ。

---

## F. god クラス / god メソッド

100 行超メソッド 19 個、合計 3,262 行（全体の 18%）:

| 行数 | 場所 | メソッド |
|---|---|---|
| 461 | `core/css/CssParser.java:379-839` | `processAtRules()` |
| 350 | `client/renderer/WebHudRenderer.java:344-693` | `processAndRenderSingleTracker()` |
| 307 | `client/renderer/WebStateStore.java:197-503` | `applyRecursive()` |
| 218 | `core/layout/LayoutBox.java:106-323` | `computeSize()` |
| 176 | `common/WebDevKitExporter.java:37-212` | `buildDevKitCss()` |
| 165 | `core/layout/FlexLayoutEngine.java:31-195` | `computeHeightAndFinalizeChildren()` |
| 155 | `core/markdown/MarkdownParser.java:122-276` | `toHtml()` |
| 141 | `server/EnderFrameCommands.java:42-182` | `register()` |
| 127 | `common/WebDevKitExporter.java:214-340` | `buildDevKitJs()` |
| 126 | `core/layout/FlexLayoutEngine.java:198-323` | `layoutChildren()` |
| 125 | `core/layout/GridLayoutEngine.java:69-193` | `computeSize()` |
| 123 | `core/dom/HtmlTokenizer.java:13-135` | `tokenize()` |
| 120 | `core/layout/BlockLayoutEngine.java:222-341` | `layoutInlineContext()` |
| 119 | `client/renderer/WebMediaManager.java:725-843` | `updateFrame()` |
| 114 | `common/UIParser.java:238-351` | `expandCustomTags()` |
| 114 | `client/renderer/WebSceneContext.java:213-326` | `renderScene()` |
| 110 | `client/renderer/WebRenderCore.java:279-388` | （無名 for ブロック） |
| 108 | `api/config/EnderConfigManager.java:315-422` | `generateStandardHtml5Config()` |
| 103 | `core/css/CssParser.java:275-377` | `computeNodeStyle()` |

クラス上位: `AbstractWebScreen` 1,508 / `WebRenderCore` 1,390 /
`UIRenderer` 939 / `WebMediaManager` 881 / `CssParser` 837 /
`WebHudRenderer` 691 / `ClientWebManager` 662 /
`ClientScreenInteractionManager` 588 / `EnderConfigManager` 527 /
`WebAnimator` 513 / `UIParser` 509 / `WebStateStore` 501。

`CssParser` 単体で 5 以上の責務（色解析・単位解析・`calc()` 再帰下降評価器・
セレクタエンジン・カスケード・at-rule 前処理）。`calc()` 評価器（`:173-242`）は
**可変 `pos`/`ch` を持つ匿名クラスをメソッドから返す**実装で、単体テスト不能。

`common/UIParser.java` 509 行のうち約 250 行が**ハードコードされた CSS 文字列**
（3 テーマ ≈1,500 文字 + 64 行の UA スタイルシート）。これは純粋なデータで
`resources/` に属する。しかも `common/WebDevKitExporter.java:185` に
focus-visible CSS の**第 4 コピー**がある。

---

## G. グローバル可変状態と「public static mutable な拡張口」

static 可変フィールドが 20 ファイル・約 79 個。多い順:
`WebHudRenderer` 13 / `WebSdfShader` 10 / `ClientScreenInteractionManager` 10 /
`WebMediaManager` 7 / `EnderFrameBuiltInConfig` 6。

拡張ポイントが**公開 static 可変フィールド**として露出:

```java
public static BiFunction<String,Float,Integer> TextMetrics.clientWidthFunction = null;
public static Consumer<String>                 CssParser.fontFaceCallback      = null;
public static Function<String,String>          DomUtils.i18nResolver           = ...;
public static Function<String,String>          UIParser.clientResourceFallback = null;
public static Function<String,String>          UIParser.resourceTextResolver   = ...;
public static ILayoutBoxRenderer               UIParser.clientBoxRenderer      = ...;
public static Function<Minecraft,String>       WebHudRenderer.activeHudSessionResolver = ...;
```

**注意: この「MC 依存を関数で注入する」パターン自体は正しい設計判断**で、
純 Java フォールバックまで用意されている（`TextMetrics.getCharWidth`）。
問題は*型*ではなく*置き場所*: グローバル static なので
テストごとの差し替え・並行実行・複数フォント共存ができず、
`CssParser.fontFaceCallback` は**パース処理に副作用**を持たせている（`:441-443`）。

危険なもの:
- `HtmlParser.globalAutoId` — 単調増加、リセット無し（C-3）
- `GridLayoutEngine.REPEAT_CACHE`（`:26`）— **信頼できない作者 CSS 文字列を
  キーにした無制限 static キャッシュ**。追い出し無し
- `ServerImageRegistry.SIZES` — 画像 URL キー、無制限。URL は
  サーバー供給の DOM（`dom_sync` の `src` 属性）から来るので、
  **サーバーがクライアントのマップを無制限に成長させられる**

どれも同期されておらず、`HtmlNode.computedStyle` はメモリバリア無しで
書かれる一方、サーバー側 `WebSession` とクライアント側 `ClientWebManager` の
両方が文書をパース/レイアウトする。

---

## H. 公開 API の安定性

### I-1. API が内部を漏らしている

`IWebSessionProvider` は `com.nishiyu.enderframelib.server.WebSession`
（`api` ではなく `server` パッケージの具象 161 行クラス）を返す。
`WebSession.patchElement(String, Consumer<HtmlNode>)` は
**全フィールド public な可変 DOM クラス `core.dom.HtmlNode`** を
そのまま下流の契約に露出する。`WebSession.getDocument()` が返す
`UIParser.Document` も `root`/`authorCss`/`clientScript` が public。

→ 実質の API 表面は `WebSession` + `HtmlNode` + `UIParser.Document`。
どれを変えてもバイナリ互換が壊れる。

`api` 自身が実装に依存している（`api → server` 5 import、`api → network/s2c` 1、
`api/config → common` 1）ので、**`api` を別アーティファクトとして出荷できない**。

### I-2. 安定性マーカーが 1 つも無い

`@ApiStatus` / `@Deprecated` / `@since` / `@Nullable` が全 62 ファイルで **0 件**。
`package-info.java` も無い。明らかに内部のもの
（`WebSessionManager`・`UIParser`・`HtmlNode`）に `@ApiStatus.Internal` が無い。
Javadoc は 62 ファイルで 74 ブロック。`WebSessionManager`（実質のサーバー入口、
public static メソッド 11 個）には**メソッド Javadoc が 1 つも無い**。
既存 Javadoc は全て日本語 — 公開 API の doc は英語（または併記）が望ましい。

### I-3. 足りない入口

- **画面を開く** API が `EnderFrameScriptingBridge`（KubeJS 向けと銘打たれた
  static ファサード）しか無い。`EnderFrameAPI.open(player, ...)` 相当が無い
- **UI の登録**が無い。データパック探索が暗黙
  （`data/<ns>/enderframe_ui/*.html`）で、検証も、プログラム的な UI ファクトリ
  登録手段も無い
- **カスタム要素/コンポーネントの拡張点が無い**。`<mc-slot>`・`<mc-inventory>`・
  `<mc-bar>` などはパーサ・レンダラ・`WebDevKitExporter` の文字列リテラルに
  ハードコード。下流 Mod が新しいタグ・CSS プロパティ・レイアウト挙動を
  登録する手段が**存在しない**。「ブラウザエンジン」を売りにするライブラリとして
  最大の API 欠落（※今回の優先軸ではないが、基盤再設計時に席を用意しておくべき）
- `AbstractWebScreen` / `AbstractWebContainerScreen` は `client.gui` にあり
  `api` ではないが、`buildInitialHtml()` / `onWebAction(...)` /
  `sendServerAction(...)` が `protected` ＝ 下流の継承前提。実質の主要 API が
  API パッケージ外・無 Javadoc
- **後片付け API が機能していない**: `WebSessionManager.removeViewer` と
  `removePlayerAll` は**どこからも呼ばれていない**（grep 済み）

---

## I. サーバー/ネットワークの重大な欠陥

> 今回の優先軸は保守性だが、これらは**リファクタリングで引き継いではいけない
> 正当性・セキュリティの欠陥**なので、評価として明記する。

全て該当コードを直接確認済み。

### I-1. クライアント指定パスによる任意 NBT 読み出し（最重大）

`server/ServerTargetSyncHandler.java:82-95`:

```java
AABB box = player.getBoundingBox().inflate(sub.radius());         // radius ≤ 64
Class<? extends LivingEntity> targetClass = getTargetClass(sub.targetType());  // "player" 可
for (LivingEntity le : player.level().getEntitiesOfClass(targetClass, box)) {
    CompoundTag nbt = new CompoundTag();
    le.saveWithoutId(nbt);                                        // エンティティ全 NBT
    Map<String, Object> entityData = extractPaths(nbt, sub.trackerPaths());  // パスもクライアント指定
```

`extractPaths` は `current.getAsString()` を返す（`:167`）ので、
`CompoundTag`/`ListTag` に当たると**SNBT 部分木を丸ごと**返す。
つまりクライアントが `trackerNbtPaths=["Inventory"]`, `targetType="player"`,
`radius=64` を送ると、**周囲の全プレイヤーの完全なインベントリが
5 tick ごと（毎秒 4 回）に届く**。

同じく `extractLookTargetData`（`:139-147`）は視線先 20 ブロック以内の
BlockEntity に `saveWithFullMetadata(...)` をかけ、クライアント指定パスを返す
→ **見えるだけのチェスト/樽/シュルカー/Mod 機械の中身**が読める。
バニラが意図的にクライアントへ送らない BlockEntity データを迂回している。
プライバシー漏洩かつチート基盤。さらに 64 ブロック立方の全 LivingEntity に
`saveWithoutId()` を毎秒 4 回 → サーバー CPU の DoS でもある。

### I-2. 任意セッション ID の読み出し

`server/WebSessionManager.java:106-122` の `forceSyncToPlayer` は
**最初の行で無検証に `addViewer(sessionId, player)` を実行**し、
そのセッションの完全な DOM を送る。画面を開いているか、近くにいるか、
所有者かのチェックが一切無い。セッション ID は完全に予測可能
（`"script_" + UUID`、`"custom_hud"`、`"dp_ui_<ns>_<path>"`、
`"inline_ui_" + hashCode`）。

→ プレイヤー B がプレイヤー A の開いている UI（Mod が描いた残高・
プライベートメッセージ・管理パネルを含む）をダンプでき、
**自分を恒久的に viewer として登録**して以後の `dom_sync`/`dom_patch` を
受け取り続けられる。

### I-3. 検証されないアクション名のディスパッチ

`WebSessionManager.java:158-191`。`player.containerMenu` への経路は
`stillValid(player)`（バニラの距離チェック）だけを見て、
**そのアクション名がサーバーが送った DOM に存在したか、要素があるか、
有効/可視だったかを一切検証しない**。クライアントは開いているメニューに
**任意のアクション文字列**を投げられる。

`block:x,y,z` 経路は `isLoaded` + `distSqr(pos) <= 4096.0`（**= 64 ブロック**、
通常の操作距離を大きく超える）のみで、**その `sessionId` が本当にその
BlockEntity のものかを検証しない**。視線判定もクレーム/権限フックも無い。
→ 64 ブロック立方を走査して全 `IWebActionReceiver` BlockEntity に
アクションを発火できる。下流 Mod は「ライブラリが検証済み」と仮定するはず。

同じ関数は `oninput`/`onchange` の **クライアント値を無検証でサーバー DOM に
書き込む**（`:139-155`、長さ制限・input か否か・viewer 正当性のチェック無し）。
その値は次の `dom_sync` で他の全 viewer に再配信される。

### I-4. PubSub に ACL が無く、購読時に全状態を再生する

`server/ServerPubSubManager.java:19-25`。チェックは「空でないか」だけ。
ACL・名前空間・プレイヤースコープ・権限フックが無く、`channelData` は
単一のグローバルマップ。購読した瞬間に**チャンネルの蓄積状態全部**が
`isFull=true` で送られる。チャンネル名は短く推測可能
（同梱 `hud_test.html` が `"test_channel"` を使っている）。
`subscribe` にレート制限も購読数上限も無いので、名前空間を総当たりできる。

### I-5. `yt-dlp` のダウンロードと実行

`client/renderer/WebMediaManager.java:342-412`。GitHub releases から
`yt-dlp` を `<gameDir>/enderframelib` に落とし、`setExecutable(true)` し、
`-U` で自己更新させ、**DOM 経由でサーバーから来た URL**を引数に
`ProcessBuilder` で実行する。

直前の `resolveDirectStreamUrl`（`:421`）には `isUrlSafe()` があるのに、
**YouTube 分岐（`:412`）は `isUrlSafe` を通らない**。
引数配列渡しなのでシェルインジェクションではなく引数インジェクションだが、
ゲートは `targetUrl.contains("youtube.com/watch") || contains("youtu.be/")` の
部分文字列一致のみで、`yt-dlp` のオプション面と対応サイトは広い。

### I-6. リーク: 後片付けが一切されていない

```java
// EnderFrameLib.java:55-60 — 実際に呼ばれるのは PubSub と TargetSync だけ
ServerPubSubManager.removePlayerAll(sp);
ServerTargetSyncHandler.removePlayer(sp.getUUID());
```

grep で確認: `WebSessionManager.removePlayerAll`（`:65`）、
`WebSessionManager.removeViewer`（`:56`）、
`WebActionC2SPacket.clearRateLimitsForPlayer`（`:43`）は
**宣言されているがどこからも呼ばれていない**。
`ServerStopping`/`ServerStopped` リスナーも **0 件**。

結果:
- `SESSION_VIEWERS` が、セッションを開いた（または探った）全プレイヤーの
  `ServerPlayer` 強参照を JVM 寿命の間保持 → プレイヤーエンティティ・
  その `Level`・インベントリのリーク。`broadcastDom` に
  `hasDisconnected()` ガードも無い（`ServerPubSubManager.publish:71` にはある）
- サーバー側の全 static 状態（`WebSessionManager` 3 マップ、
  `ServerPubSubManager` 2 マップ、`ServerTargetSyncHandler` 2 マップ、
  `RATE_LIMIT_MAP`、`LOADED_UIS`、`GLOBAL_PROVIDERS`）が
  **ワールド再読込をまたいで生き残る** → 統合サーバーでワールドを
  切り替えると前の `ServerLevel` のオブジェクトグラフ全体が残る
- ディメンション変更・ブロック破壊時の後片付けも無い

### I-7. 無制限マップとレート制限の迂回

`WebActionC2SPacket` のレート制限キーは `ui_event` で
`sessionId + elementId` を含む（`:87`）→ **`ElementId` を毎パケット変えれば
制限を完全に回避**でき、各新キーが 10 秒保持されてマップが成長する。
`SubscribeC2SPacket` にはレート制限が**無い**。
`forceSyncToPlayer`/`handleFetchRequest`/`handleClientEvent` の 3 経路すべてが
解決前に `addViewer(sessionId, player)` を呼ぶので、
`SESSION_VIEWERS` はクライアント供給文字列ごとに 1 エントリ増える
（＝自明なサーバーメモリ DoS）。

### I-8. `PubSubUpdateS2CPacket` の無制限再帰デコード

`readMap` が `buf.readVarInt()` 個のエントリを**上限無し**で読み、
ネストしたマップ/リストに**深さ制限無し**で再帰する（`:51-113`）。
S2C なので悪意あるサーバーがクライアントを OOM/スタックオーバーフローさせられる。

### I-9. 潜在的: クラスパス traversal

`common/UIParser.java:50-75` の `resourceTextResolver`
（`<include src>` / `<link href>` が使う）:

```java
String[] sp = clean.split(":", 2);
try (InputStream is = UIParser.class.getResourceAsStream("/assets/" + sp[0] + "/" + sp[1])) {
```

正規化無しの文字列連結なので `ns:../../..//path` が `assets/<ns>` を脱出できる。
**現状クライアント供給文字列はここに到達しない**（データパック作者・Mod 作者・
ローカルリソース由来のみ）ので生きた穴ではないが、`fetch`/`ui_event` の
ペイロードで HTML を組む日が来た瞬間に穴になる。正規化すべき。

---

## J. 設定システム

`api/config/EnderConfigManager.java`（527 行）は NeoForge の `ModConfigSpec` を
**再発明している**（`grep ModConfigSpec` → 0 件）。アノテーション駆動
（`@EnderConfig` / `@ConfigEntry`）＋リフレクションで、Gson による JSON を
`FMLPaths.CONFIGDIR` に自前 IO。

見返りは `generateStandardHtml5Config`（`:315-422`）で設定を HTML として
レンダリングできること — これは本当に良い機能で、動機として理解できる。
代償: NeoForge 設定 GUI と非連携、再起動/リロードのセマンティクス無し
（`requiresRestart` は表示専用、`:394-396`）、バリデータ API 無し、
他 Mod の設定との相互運用無し。

**スレッド安全性が不完全**:
- `CONFIGS` は `ConcurrentHashMap`、`changeListeners` は `CopyOnWriteArrayList`（OK）
- しかし**設定値そのものは非 volatile な static フィールド**で、
  リフレクション（`FieldSpec.setFromString`）で書き換えられ、
  レンダー/tick スレッドから直接読まれる → happens-before 保証が無い
- `register()` の `CONFIGS.put` → `load` → `save`（`:231-233`）が非同期化。
  NeoForge の並列 Mod ロードで競合しうる
- `field.setAccessible(true)` が呼び出しごと（`:51,:70,:129`）。`:208` で既に済み

**クライアント/サーバー同期が全く無い**。`ModConfig.Type` の区別も同期パケットも
`@ConfigEntry(side=...)` も無い。`EnderFrameBuiltInConfig` は偶然
クライアント描画専用なので実害が出ていないだけで、下流 Mod が
ゲームプレイに関わる設定をこの API で登録すると、
**サーバー権威が無いことを知らされずに**クライアントローカル値になる。
`load()` は型エラーをフィールド単位で握り潰し（`:122`）警告も出さない。

---

## K. 死んだコード / 重複

- **`ClientScript` 経路が完全に死んでいる**: `packDom` が
  `getDocument().clientScript` を送り（`WebSessionManager.java:76`）、
  `ClientWebManager.java:181-182` が `scriptLoaderCallback` に渡すが、
  **`scriptLoaderCallback` はどこにも代入されていない**。
  つまり `<script>` 本体はパースされ、NBT にされ、**毎回フル同期で
  ネットワークを流れて捨てられている**。帯域の無駄であり、
  いつか誰かが `scriptLoaderCallback` を繋いだ日に、
  サンドボックスの議論がどこにも無い状態で任意クライアントスクリプト実行になる
- **DevKit が二重化**: `src/main/resources/assets/enderframelib/preview/`
  の `enderframe-devkit.js`（19,127 bytes、Markdown レンダラ・`data-for`・
  DevTools インスペクタ・RPC ログ入り）と `.css`（6,707 bytes）は
  **どこからも読まれていない**（`grep "preview/"` → 0 件）。
  代わりに `WebDevKitExporter` が機能の少ない別版を文字列から生成している
  （`buildDevKitJs:214-340` — `data-for` なし、Markdown なし、インスペクタなし）。
  コミット `aed2386 拡張機能のSSOT化` が届いていない箇所
- `HtmlTokenizer.flushText(tokens, textBuffer, isRaw)` の `isRaw` 引数が死んでいる
  （全 3 呼び出しが `false`）
- `ServerImageRegistry` は `common/` にあるが実質クライアント専用
  （唯一のリスナーが `ClientWebManager::recomputeAllLayouts`）。名前が誤り
- `mod_group_id=com.nishiyu.enderframe` に対しパッケージは
  `com.nishiyu.enderframelib` — Maven 公開で問題になる
- `mod_version=1.0.3` に対しネットワーク版は `.versioned("1.0.0")` ハードコード
- `EnderFrameNetwork.registerPayloads` が `@SubscribeEvent` 付き**かつ**
  手動登録（`EnderFrameLib.java:32`）。アノテーションは死んでいる

---

## L. 例外処理

`catch (Exception|Throwable|Error)` が **102 箇所**。
`printStackTrace` / `System.out` は **0 件**（SLF4J 使用は良い）。
`TODO`/`FIXME` も 0 件 — 既知の課題がコード上に記録されていない。

空ボディ（`catch (Exception ignored) {}`）が不正入力の診断を隠している例:
`LayoutBox:44`（z-index）、`:69`（input size）、`:279`（aspect-ratio →
失敗が黙って正方形になる）、`:348`（data-scroll → 失敗が黙って 0 に）、
`CssParser:112`（hex 色 → 白にフォールバック）、`:168`（`parseDim` 全失敗 → 0）、
`:586`（nth-child）、`UIElement.RenderMeta.fromEvents` に 6 個、
`GridLayoutEngine:41,51`（`parseGridLine` は失敗時 1 を返す = 黙った誤配置）。

逆にガードすべき数値パースが**ガードされていない**:
`FlexLayoutEngine:110,113,125,143,151,179`、`GridLayoutEngine:81,90`、
`CssParser:412,417`。

---

## M. プロジェクト衛生

| 項目 | 現状 |
|---|---|
| テスト | **0 件**。`src/` 配下は `main` のみ。アサーション 0 |
| ゲームテスト | **0 件**。`build.gradle` は 3 つの run 設定で `neoforge.enabledGameTestNamespaces` を有効化済み＝**ハーネスは整っているのに中身が無い** |
| CI | **0 件**。`.github/` が存在しない |
| README | **NeoForge MDK テンプレートそのまま**（25 行）。「This template repository can be directly cloned…」で始まり NeoForged Discord リンクで終わる。プロジェクト固有の記述・導入手順・API 使用例・対応 HTML/CSS 一覧・ライセンス表記が**ゼロ**。下流 API が価値の全てである*ライブラリ*としては最大の衛生欠陥 |
| LICENSE | **存在しない**。`gradle.properties` は `mod_license=LGPL-3.0` と宣言しているが本文が無い（LGPL-3.0 は告知を要求）。あるのは `TEMPLATE_LICENSE.txt`（MIT, NeoForged project, MDK テンプレート用）のみ。ソースにライセンスヘッダ 0 件 |
| `.gitignore` | 4 行（`/.gradle/`, `/build/`, `/build/classes/java/main/` ＝冗長）。`run/`, `.idea/`, `repo/`（maven publish 先）, `src/generated/`, `*.log` が**全て漏れている** |
| 誤コミット | `run/logs/debug.log`（96,872 B）, `run/logs/latest.log`, `run/config/*.toml` 3件, `run/downloads/log.json`, `.idea/` 6 ファイル。ログには開発機の絶対パス `D:/Dev/Personal/enderframelib-1.21.1/` が含まれる |
| ネイティブ DLL | `lunex_video.dll` **23,473,450 B（22.4 MiB）** = 作業ツリーの約 97%。`WebMediaManager.loadLibrary`（`:452-458`）は `liblunex_video.so` / `.dylib` も探すが**どちらもリポジトリに無い**ので Linux/macOS では動画が黙って無効化される。ソース・ビルドスクリプト・出自・ライセンス・チェックサムが無く、毎回クライアント起動時に `<gameDir>` へ `REPLACE_EXISTING` で展開して `Native.load` される |
| 言語の混在 | 49/62 ファイルに日本語（計 232 行）。**Javadoc は全て日本語**（公開 API 含む）。一方ユーザー向け文字列は英語。識別子は英語。コミットメッセージも日本語。規約が文書化されていない |
| その他 | `build.gradle` の `repositories { }` が空（moddev 注入依存＝脆い）。`org.gradle.configuration-cache=true` は ModDevGradle と相性が悪いことで知られる |

---

# 第 2 部: リファクタリング計画

方針は「**基盤を一括で作り直す**」。ただし一括と無計画は違う。
`core/` を純 Java リーフに切り出し、**先に特性テストで現在の挙動を固定**してから
型を入れ替える。これがテスト基盤優先という選択に最も効く順序。

フェーズ 1〜2 は順序必須（テストが無いと一括再設計の回帰を検出できない）。
フェーズ 3〜5 がリファクタリング本体。フェーズ 6〜7 は独立に進められる。

---

# 修正クラス一覧

全体像を先に示す。記号の意味:
**新** = 新規作成 / **改** = 既存を変更 / **解** = 解体して複数クラスに分割 /
**移** = 移動・改名 / **削** = 削除 / **波** = 波及変更のみ（import・呼び出し側の追従）

規模: 既存 62 ファイルのうち **55 ファイルに手が入る**。
うち **解体が 8 クラス**（`CssParser` 837 / `AbstractWebScreen` 1,508 /
`WebRenderCore` 1,390 / `WebStateStore` 501 / `WebHudRenderer` 691 /
`EnderConfigManager` 527 / `EnderFrameCommands` 344）、
移動が 2、**波及変更のみが 11**。
新規は 66 項目（Java クラス・テスト・リソース・CI 定義を含む）。

**手を入れない 7 ファイル**（現状で問題が無いか、今回の範囲外）:
`api/IWebActionReceiver`・`api/IWebScreenBlockEntity`（インターフェースとして妥当）、
`api/config/ConfigEntry`・`api/config/EnderConfig`（アノテーション定義）、
`client/gui/EnderConfigScreen`・`common/EnderFrameBuiltInConfig`
（フェーズ 6-4 の設定方針が決まってから）、
`network/s2c/WebSyncS2CPacket`。

---

### フェーズ 1: `core/` のリーフ化とテスト基盤（11 ファイル + テスト）

| 記号 | クラス / ファイル | 作業 |
|---|---|---|
| **移** | `core/dom/HtmlNodeSerializer.java` → `network/dom/HtmlNodeSerializer.java` | `core/` で唯一 `net.minecraft`（NBT）を import するファイル。移動 ＋ 再帰 `deserialize` に深さ制限 |
| **改** | `core/dom/DomUtils.java` | `:3` の `common.UIParser` import と `:127` の直呼びを削除し、注入された `innerHtmlParser` フックに。**`core → common → server → net.minecraft` の鎖を断つ** |
| **改** | `core/layout/LayoutBox.java` | `:3` の `common.ServerImageRegistry` import と `:197,:288` の static 呼びを削除し、`IntrinsicSizeResolver` 経由に |
| **新** | `core/layout/IntrinsicSizeResolver.java` | 置換要素の固有サイズ解決インターフェース。フェーズ 3-2 でレジストリに育てる |
| **改** | `common/UIParser.java` | `DomUtils` に渡す `innerHtmlParser` 実装を提供 |
| **改** | `common/ServerImageRegistry.java` | `IntrinsicSizeResolver` を実装（または薄いアダプタを追加） |
| **波** | `server/WebSessionManager.java` | `HtmlNodeSerializer` の import パス追従 |
| **波** | `client/ClientWebManager.java` | 同上 ＋ `innerHtmlParser` / `IntrinsicSizeResolver` の配線 |
| **改** | `client/EnderFrameClientInit.java` | 上記フックの注入を追加（既に 4 箇所で同じことをしている箇所） |
| **改** | `build.gradle` | `src/test/java` ソースセット ＋ JUnit 5 ＋ `useJUnitPlatform()` |
| **新** | `.github/workflows/build.yml` | `gradlew build test` |

テスト（新規）:

| 記号 | ファイル | 内容 |
|---|---|---|
| **新** | `test/.../ArchitectureTest.java` | **`core/**` に `net.minecraft`・`net.neoforged`・`client`・`common`・`server` の import が無いことを検証**。これが無いと次の MC import がすぐ戻る |
| **新** | `test/.../HtmlParserGoldenTest.java` | void 要素・生テキスト（`script`/`style`/`textarea`）・実体参照・不正ネスト・`showcase.html` の往復 |
| **新** | `test/.../CssParserGoldenTest.java` | 全疑似クラス・結合子・属性セレクタの照合、`parseColor` 全形式、`parseDim` 全単位、`calc()`/`clamp()`、カスケード勝敗、`@media`、`var()` |
| **新** | `test/.../LayoutGoldenTest.java` | block/flex/grid の `(x,y,w,h)` ツリー比較。`TextMetrics` の純 Java フォールバックで決定的になる |
| **新** | `test/.../MarkdownParserGoldenTest.java` | `guidebook.md` → HTML |
| **新** | `test/.../DomDiffEngineTest.java` | append / truncate / 中間挿入 / 中間削除 / タグ変更 |
| **新** | `test/resources/golden/*` | ゴールデン出力 |

---

### フェーズ 2: スタイル表現の再設計（14 ファイル）

`core/css/CssParser.java`（837 行）の解体が中心。

| 記号 | クラス | 作業 |
|---|---|---|
| **解** | `core/css/CssParser.java` | 以下に分割。薄いファサードとして残すか、破壊的変更 OK の方針なら削除 |
| **新** | `core/css/CssTokenizer.java` | `CSS_PATTERN` 正規表現（`:15`）を置換。ネスト波括弧・文字列・`url()` を正しく扱う |
| **新** | `core/css/CssRuleParser.java` | ルールブロックの構文解析 |
| **新** | `core/css/AtRuleProcessor.java` | **`processAtRules()` 461 行（`:379-839`）を置換**。トークナイザがあれば `@media` の文字列平坦化そのものが不要。`@media` の OR→AND バグ（`:408-419`）と `:root` 破棄（`:491-498`）もここで直す |
| **新** | `core/css/Selector.java` / `SelectorParser.java` / `SelectorMatcher.java` | `StyleRule`(`:703-833`)・`SelectorToken`(`:509-701`) をトップレベル化。`contains`+`replace` の破壊的文字列手術（`:541-548`）を実トークナイズに |
| **新** | `core/css/SelectorEngine.java` | `querySelector`/`querySelectorAll` の実体。**セレクタをキャッシュ**（現在は呼び出しごとに `new StyleRule`）。`core/dom ↔ core/css` の循環を切る |
| **新** | `core/css/Specificity.java` | 文字数カウント（`:768-781`）を (id, class, type) タプルに。桁溢れと `::before` > class の逆転を解消 |
| **新** | `core/css/CascadeOrigin.java` | `UIParser:199-207` の `+= 10000` マジック定数を置換。`!important` も導入 |
| **新** | `core/css/StyleResolver.java` | `computeNodeStyle()`（`:275-377`）を置換。**`style.putAll(node.attrs)`（`:309`）を削除** |
| **新** | `core/css/ComputedStyle.java` | **`Map<String,String>` を置換する不変値型**。型付きアクセサ（`length`/`color`/`keyword`/`number`）。パースは算出時 1 回 |
| **新** | `core/css/CssProperty.java` | プロパティ名の定数／enum。現在 152 箇所に散る生文字列を 1 箇所に |
| **新** | `core/css/StyleSet.java` / `ElementState.java` | **疑似クラス状態を型で表す**。`"hover-"` キープレフィックス機構（`:299-302`）を廃止 |
| **新** | `core/css/StyleSheet.java` / `StyleRule.java` | `:833-837` / `:703-833` のネストクラスをトップレベル化 |
| **新** | `core/css/ColorParser.java` | `parseColor`(`:38-119`) ＋ `hslToArgb` |
| **新** | `core/css/LengthResolver.java` | `parseDim`・`parseBoxEdgesStr`・`parseBorderRadius`。`em` を要素フォントサイズ基準、`rem` をルート基準に正す。`border-width` ショートハンド展開を追加 |
| **新** | `core/css/CalcEvaluator.java` | `:173-242`。**可変 `pos`/`ch` を持つ匿名クラスを返す**実装を通常クラスにして単体テスト可能に |
| **改** | `core/dom/HtmlNode.java` | `computedStyle`/`isDirty` フィールドを削除（ビュー側のキャッシュへ）。`querySelector`(`:54,:70`) を `SelectorEngine` 委譲に。`markDirty()`(`:260-272`) の範囲を適正化 |
| **改** | `core/layout/LayoutBox.java` | `Map<String,String> style` → `ComputedStyle`（56 箇所） |
| **改** | `core/layout/BlockLayoutEngine.java` | 14 箇所。`isBoldBox()`(`:71-81`) の bold 専用第 2 機構を**削除**（継承修正で不要になる） |
| **改** | `core/layout/FlexLayoutEngine.java` | 33 箇所。save/force/recurse/restore 5 箇所の除去はフェーズ 3-2 |
| **改** | `core/layout/GridLayoutEngine.java` | 11 箇所 |
| **改** | `common/UIParser.java` | `+= 10000` 廃止。**UA スタイルシートと 3 テーマ（約 250 行の CSS 文字列、`:81-192`）を `resources/` に追い出す** |
| **改** | `common/WebDevKitExporter.java` | focus-visible CSS の**第 4 コピー**（`:185`）を削除し共通リソースを参照 |
| **改** | `client/renderer/UIRenderer.java` | 46 箇所。`buildPseudoStateDef`(`:291-318`) と `src` のスタイルマップ読み（`:645` → `node.attrs` 直読み）を修正 |
| **改** | `client/renderer/WebHudRenderer.java` | スタイル参照の追従 |
| **改** | `client/gui/AbstractWebScreen.java` | 22 箇所 |
| **改** | `client/gui/AbstractWebContainerScreen.java` | 2 箇所 |
| **波** | `client/ClientWebManager.java` / `client/EnderFrameClientInit.java` | `fontFaceCallback` ほかフックの配線追従 |
| **新** | `resources/assets/enderframelib/css/ua.css` ＋ `theme/*.css` | Java 文字列リテラルから移した UA シートとテーマ |

---

### フェーズ 3: レイアウト層の再設計（11 ファイル）

| 記号 | クラス | 作業 |
|---|---|---|
| **改** | `core/layout/LayoutEngine.java` | **9 引数 → 2〜3 引数**。`LayoutContext`/`FormattingContext` を受け取る形に。メソッド名を実態に合わせる |
| **新** | `core/layout/LayoutContext.java` | `Viewport` ＋ `IntrinsicSizeResolver` ＋ `ScrollState` ＋ `FontMetrics` を束ねる |
| **新** | `core/layout/FormattingContext.java` | 親の display モード・`align-items`・shrink 可否。**`isParentFlexRow`/`isParentFlexColumn`/`parentAlignItems`/`isParentShrink` の 4 フラグを置換** |
| **新** | `core/layout/Viewport.java` | 全呼び出しを貫通している `rootW`/`rootH` をアンビエント化 |
| **新** | `core/layout/SizingMode.java` | `w == -1` のマジックセンチネル（3 エンジン貫通）を enum に |
| **新** | `core/layout/ScrollState.java` | **ノード ID キーのスクロール状態**。`data-scroll-x/y` の DOM 属性往復（`LayoutBox:345-348`）を置換 |
| **新** | `core/layout/AbstractLayoutEngine.java` | **3 エンジンの約 400 行の重複を集約**: 絶対配置子確定（3 コピー）・`line-height` 解析（4 コピー）・`justify-content` space 配分（2 コピー）・`display:none` フィルタ（10 箇所）・日英分割正規表現（4 コピー）・bold 判定（4 コピー）・マジック数の定数化 |
| **新** | `core/layout/GridState.java` / `GridItem.java` | `GridLayoutEngine` のネスト型をトップレベル化。`LayoutBox:378-384` の `instanceof GridLayoutEngine.GridState` 循環を解消 |
| **改** | `core/layout/LayoutBox.java` | **`computeSize()` 218 行（`:106-323`）をエンジンへ移し、ジオメトリ結果の保持に限定**。`public` フィールドを適切な可視性に。`Object engineState` を型付きに。**`:389-390` の `data-scroll-w/h` DOM 書き込みを削除（B-3 の diff 暴走修正）**。`min-height` の `horizontalDecoration()` バグ（`:318-322`）を修正。`§l` 埋め込み（`:81,:187`）を削除 |
| **改** | `core/layout/LayoutEngineFactory.java` | `inline`/`inline-block`/`inline-flex`/`table` が黙って block になる問題に対応 |
| **改** | `core/layout/BlockLayoutEngine.java` | **`measureInlineRunHeight`(83-154) と `layoutInlineContext`(222-341) の約 70 行重複を統合**（折返しを片方だけ直すと測定高と配置高がずれる）。`§l` 埋め込み（`:125,:258`）削除 |
| **改** | `core/layout/FlexLayoutEngine.java` | **save/force/recurse/restore 5 箇所を制約引数の再測定 API に置換**（`finally` 漏れによるキャッシュ焼き付きが消える）。未ガード `parseFloat` 6 箇所（`:110,113,125,143,151,179`）をガード |
| **改** | `core/layout/GridLayoutEngine.java` | save/force/restore 2 箇所。未ガード `parseFloat`（`:81,:90` の `fr`）。**`REPEAT_CACHE`（`:26`）に上限と追い出し**（信頼できない作者 CSS がキー） |
| **改** | `core/text/TextMetrics.java` | **`public static BiFunction clientWidthFunction`（`:8`）を `FontMetrics` インターフェースに**し、`LayoutContext` 経由で渡す。テストごとの差し替えと複数フォント共存が可能に |
| **新** | `core/text/FontMetrics.java` | 上記インターフェース（純 Java フォールバック実装も同梱） |
| **改** | `common/UIParser.java` | `renderDocument`(`:411-417`) の **`computeSize` 二重呼び出し**を意図明示の収束ループに。`LayoutContext` の組み立て |
| **改** | `client/renderer/UIRenderer.java` | `line-height` 解析の 4 つ目のコピー（`:698-703`）を削除。`LayoutBox` API 追従 |
| **改** | `client/ClientWebManager.java` | `TextMetrics.clientWidthFunction`（`:55`）→ `FontMetrics` 実装の提供に変更 |

---

### フェーズ 4: DOM 層の整理（21 ファイル）

| 記号 | クラス | 作業 |
|---|---|---|
| **改** | `core/dom/HtmlNode.java` | **3 つの関心事を分離**。対話状態（`isHovered`/`isChecked`/`isActive`/`isFocused`）を `InteractionState` へ移す。`id`/`classes` の非正規化を解消（同期経路 4 → 1）。`attrs` を `LinkedHashMap` に（属性順保持）。`querySelector` の `StyleRule` 毎回生成を解消 |
| **新** | `core/dom/InteractionState.java` | **ビュー単位の対話状態**。1 文書を複数ビューで共有できるようになり、ホバーがスタイルキャッシュを無効化しなくなる |
| **新** | `core/dom/NodeType.java`（または `TextNode`） | `"#text".equals(node.tag)` の文字列比較 14 箇所を解消 |
| **新** | `core/dom/VoidElements.java` | `HtmlParser:12-15` と `DomUtils:16` の**2 つの異なる定義**を 1 本化 |
| **新** | `core/dom/NodeId.java` | **再パースで安定な ID 導出**。`HtmlParser.globalAutoId`（`:17`、プロセスグローバル単調増加）を置換 |
| **改** | `core/dom/DomDiffEngine.java` | **位置ベース → キーベース照合**（既にある `id` / `key` 属性を使う）。削除時の `child.parent` クリア（`:92`）。`isFocused`/`isActive` の同期漏れ |
| **改** | `core/dom/DomUtils.java` | 同期ロジックの重複（`:38-59`・`:186-195`）を削除。`VoidElements` 参照に |
| **改** | `core/dom/HtmlParser.java` | `globalAutoId` 削除。`MAX_NODES`(`:28`) をノード数に直して**超過時に警告**（現在は無警告切り捨て）。`<p>`/`<li>` の最低限の暗黙クローズ。空白正規化ヒューリスティック（`:76-99`）を `white-space` プロパティ参照に |
| **改** | `core/dom/HtmlTokenizer.java` | **`script`/`style` 内の実体参照復号（`:121`）をやめる**（HTML 仕様違反）。実体参照を数値参照対応に拡張。死んだ `isRaw` 引数を削除 |
| **改** | `core/markdown/MarkdownParser.java` | `processDocumentOrBlocks`(`:54-104`) の**第 3 の正規表現 HTML マッチャ**をトークナイズ後の DOM 変換に。**全 HTML に無条件で走る**（`UIParser:240`）のをやめる |
| **改** | `common/UIParser.java` | `expandCustomTags`(`:238-351`、114 行) の正規表現マクロ展開を DOM 変換に移す |
| **改** | `client/ClientWebManager.java` | **`RENDER_SUFFIXES[21]`（`:49-52`）と `getBaseNodeId`（`:351-360`）を削除**（フェーズ 5-1 で `UIElement` のフィールドに）。3 箇所に重複しドリフトしたサフィックス表を 1 本化 |
| **波** | `server/WebSession.java` / `server/WebSessionManager.java` | `HtmlNode` API 追従 |
| **波** | `client/binding/ClientPubSubManager.java` / `DeclarativeBindingManager.java` | 同上 |
| **波** | `client/renderer/WebStateStore.java` / `WebRenderCore.java` / `WebHudRenderer.java` | 同上 |
| **波** | `client/gui/AbstractWebScreen.java` / `AbstractWebContainerScreen.java` / `InvisibleInputScreen.java` | 同上 |
| **波** | `client/ClientScreenInteractionManager.java` / `ClientLocalEnvProvider.java` / `client/compat/WebRecipeViewerBridge.java` | 同上 |

---

### フェーズ 5: `client/` の分割

#### 5-1. `UIElement` 境界の型化（17 ファイル — 他の前提）

| 記号 | クラス | 作業 |
|---|---|---|
| **改** | `core/model/UIElement.java` | `type()` を `String` → enum。画像フィットモードを型文字列から独立フィールドに。**基底ノード ID をフィールドに追加**。19 引数の後方互換コンストラクタ（`:32-44`）を削除（破壊的変更 OK） |
| **新** | `core/model/ClipRect.java` | 8 値タプル。**9 シグネチャから約 8 引数が消える** |
| **新** | `core/model/Corners.java` | 4 値タプル。**同様に約 4 引数** |
| **新** | `core/model/PseudoStates.java` | hover/active/focus を構造体に。フェーズ 2-2 の `StyleSet` と繋がる |
| **新** | `core/model/ElementType.java` | `"node"`/`"rect"`/`"text"`/`"input"`… 約 45 箇所の `.equals` 比較を置換 |
| **新** | `core/model/RenderKeys.java` | `events` マップのプロトコルキー 11 個（`mc-parent`・`mc-clip`・`mc-radius`・`mc-gradient`・`mc-font-h`・`mc-title`・`mc-origin`・`mc-delay`・`mc-easing`・`mc-scrollable`・`mc-scroll-current`）を定数に。現在はタイポが無言の no-op |
| **改** | `client/renderer/UIRenderer.java` | **`formatRadii`/`formatClipRect`（`:908-914`）と `buildPseudoStateDef`（`:291-318`）を削除**。`animDef` の 3 連結（`:484`）を構造体に。派生 ID 生成時に基底 ID を `UIElement` に入れる |
| **改** | `client/renderer/WebAnimator.java` | **`extractMarker`（`:218-237`）・`parsePseudoDefs`・`@@HOVER@@` センチネルを削除**。`updateStyleCache`(`:245-329`、85 行) の 4 つの文字列解析ブロックを除去 |
| **改** | `client/ClientWebManager.java` | `getHitElement:543-544` の **`animDef().contains("@@HOVER@@")` ヒットテストを削除**。`RENDER_SUFFIXES` 一式を削除 |
| **波** | `client/renderer/WebRenderCore.java` ほか | `el.type()` 比較・`split(":")`（`:661`）の追従 |
| **波** | `WebBlockRenderer` / `WebItemRendererHelper` / `WebHudRenderer` / `WebCanvasContext` / `WebSceneContext` / `WebMediaManager` / `ClientScreenInteractionManager` / `WebRecipeViewerBridge` / `AbstractWebScreen` / `AbstractWebContainerScreen` / `InvisibleInputScreen` / `common/UIParser` | `UIElement` API 追従（計 17 ファイルが `UIElement`/`RenderMeta` を参照） |

#### 5-2. god クラスの分割（4 ファイル → ＋13 新クラス）

| 記号 | クラス | 作業 |
|---|---|---|
| **解** | `client/gui/AbstractWebScreen.java` | 実体は入れ子 `WebUiScreenSupport`（344-1508、**1,165 行**）。以下に分割し、約 250 行の純ディスパッチに |
| **新** | `client/gui/WebViewport.java` | `:392-447`（56 行、依存ゼロ）。両画面のフィールドミラーリング（`:186-193`・`AWCS:224-231`）も解消 |
| **新** | `client/gui/WebActionRouter.java` | `:464-509`（2 マップ ＋ `dispatchRoutedAction`）。`containsKey`+`get` の二重ルックアップも修正 |
| **新** | `client/gui/WebDevTools.java` | `:598-680`（83 行）。F12 押下中**毎フレーム**の CSS 再パース（`:624-634`）を解消 |
| **新** | `client/gui/SelectDropdownWidget.java` | `:557-586, 687-706, 843-865, 1502-1503`（4 箇所に分散した 1 ウィジェット） |
| **新** | `client/gui/WebFocusNavigator.java` | `:994-1213`（**220 行**、既に自己完結）。`FocusCandidate` レコード・tabindex ソート・空間ナビ採点 |
| **新** | `client/gui/WebTextareaEditor.java` | `:1227-1354`（128 行、完全な複数行エディタ） |
| **新** | `client/gui/TextEditState.java` | **`WebRenderCore` の編集状態 3 マップ（`:64-66`）をエディタ所有に移す**。client 層で最悪の層違反（キャレット状態が描画層所有で入力層と描画層の双方が書く）を解消 |
| **新** | `client/gui/InteractionHandler.java` ＋ 実装群 | `handleMouseClicked`(`:682-832`、**151 行**) の 9 段 if/else を連鎖に（scene → textarea → input → summary/details → label[for] → select → range/toggle/checkbox → draggable → onclick） |
| **新** | `client/gui/DragDropController.java` | `:803-806, 893-910, 588-596`（約 30 行、2 フィールド） |
| **解** | `client/renderer/WebRenderCore.java` | 4 つのほぼ同一な角丸ラスタライザをテクスチャ有無の 2 つに畳む。角の内側オフセット式の**16 箇所の逐語重複**を 1 関数に。**死んだ `renderMode` 引数・`ICustomRenderer`・`currentPassIs2D` を削除** |
| **新** | `client/renderer/RoundedRectRasterizer.java` | 上記幾何ライブラリの切り出し（`:916-1176`） |
| **解** | `client/renderer/WebStateStore.java` | `applyRecursive()`（**207 行**、`:197-403`）を、コメント `// 1.` 〜 `// 7.` が示す境界でそのまま 7 分割 |
| **解** | `client/renderer/WebHudRenderer.java` | `processAndRenderSingleTracker()`（**350 行**、`:344-693`）を NBT 平坦化 / JSON 変換 / キャッシュ判定 / レイアウト木構築 / ビルボード変換 / 描画に分割 |

#### 5-3. 6 描画ホストの一本化（7 ファイル）

| 記号 | クラス | 作業 |
|---|---|---|
| **新** | `client/renderer/WebPaintPass.java` | `.forGui(...)` / `.forBlockFace(...)` / `.forBillboard(...)` ビルダ。**22 個の位置引数の取り違えが起こり得なくなる** |
| **改** | `client/gui/AbstractWebScreen.java` | `:219-244` の前置処理を `WebPaintPass` に |
| **改** | `client/gui/AbstractWebContainerScreen.java` | `:340-364`（上とほぼ同一） |
| **改** | `client/renderer/WebHudRenderer.java` | `:254-275`（画面空間 HUD）と `:410-429`（ビルボード） |
| **改** | `client/renderer/WebBlockRenderer.java` | `:69-89`（ブロック面・LOD 付き） |
| **改** | `client/renderer/WebItemRendererHelper.java` | `:73-102`（手持ちアイテム） |
| **改** | `client/renderer/WebRenderCore.java` | `renderElements`(`:239`) の 22 引数を `WebPaintPass` ＋ `ClipRect`/`Corners` で削減 |

#### 5-4. 2 画面クラスの重複解消（3 ファイル）

| 記号 | クラス | 作業 |
|---|---|---|
| **新** | `client/gui/WebScreenDelegate.java` | **187/486 行（38%）の同一コード**を集約。最大の連続一致は 29 行（`renderElements` 22 引数呼び出し）と 22 行（1 行委譲アクセサ 13 個） |
| **改** | `client/gui/AbstractWebScreen.java` | 委譲に置換 |
| **改** | `client/gui/AbstractWebContainerScreen.java` | 委譲に置換 ＋ **抽出と同時に 5 つの分岐バグを修正**（`onmouseenter`/`onmouseleave` 不発火、`ensureSynced` 欠落、`initOrBindSession` 戻り値破棄、`onInputChanged` がサブクラスに届かない、`playAudio` の `true`/`false` 食い違い）。`handleMouseClicked` に 9 回コピペされた値コミット 4 行を `commitValue(id, v)` 1 つに |

#### 5-5. セッション状態の集約（9 ファイル）

| 記号 | クラス | 作業 |
|---|---|---|
| **新** | `client/SessionContext.java` | **`ClientWebManager` の 13 本の並列マップ（`:31-47`）＋ public 可変コールバック 3 本を 1 オブジェクトに**。`clearSession`（`:636-654`）のショットガン解体が `SESSIONS.remove(id).close()` になる |
| **改** | `client/ClientWebManager.java` | 上記。`Map<String, SessionContext>` 1 本に |
| **改** | `client/renderer/WebRenderCore.java` | **`public static String activeInputId` / `activePressedId`（`:68-69`）をセッション別に**。現在 GUI 画面とブロック画面でフォーカスを共有してしまう |
| **改** | `client/renderer/WebHudRenderer.java` | `currentTrackerSessionId` が単一スロット → セッション別に（2 つ目の `data-tracker` が 1 つ目を黙って追い出す） |
| **改** | `client/renderer/WebAnimator.java` | `STATES` の `sessionId + "_" + id` 文字列連結複合キーを構造化（`cleanup` の `startsWith`/`substring` 走査の原因） |
| **改** | `client/renderer/WebCanvasContext.java` / `WebSceneContext.java` | `CONTEXTS`/`SCENES` の `sessionId + "::" + id` 同様 |
| **改** | `client/ClientScreenInteractionManager.java` | `states` / `ROUTER_DISTANCE_CACHE` / 6 つの public 可変設定スロット（`:46-52`）を整理 |
| **改** | `client/binding/ClientPubSubManager.java` | `activeSessions` の素の `HashSet` → `ConcurrentHashMap.newKeySet()` |

#### 5-6〜5-14（残りのクライアント作業）

| 記号 | クラス | 作業 |
|---|---|---|
| **改** | `client/renderer/WebRenderCore.java` | **5-6**: `ENTITY_PREVIEW_CACHE`（`:67`）をレベルアンロードでクリア（生きた `Entity` が `ClientLevel` 全体を保持＝実メモリリーク） |
| **改** | `client/renderer/WebMediaManager.java` | **5-6**: `IMG_CACHE` に上限・追い出し・`DynamicTexture` 解放。テクスチャ ID を `url.hashCode()`（32bit 衝突）から変更。`alDeleteBuffers`（`:853-869`）を無条件に。`YT_DLP_CACHE` の TTL 切れ削除。**5-13**: `NativeVideoAPI.INSTANCE` の static 初期化（`:445`）を明示的初期化に、`catch (Throwable)`（`:466`）で一度だけ `warn`、非 Windows の明示的無効化、`REPLACE_EXISTING`（`:461`）をハッシュ比較に、**`yt-dlp` 自動 DL＋実行（`:342-412`）を既定無効化または同意ゲート**。**5-15**: `close()` × `initVideoWorker` 競合（`:546-555` vs `:846`）を CAS/ロックに、`ProcessBuilder.waitFor()`（`:385,:404`）にタイムアウト |
| **改** | `client/renderer/WebFontManager.java` | **5-6**: `clearCache()`（`:227`、**呼び出し元ゼロ**）をリソースリロードに繋ぎ `FontSet` を `close()`。**5-12**: `:141-152` の「形で照合する」リフレクションを名前指定に |
| **改** | `client/renderer/WebSdfShader.java` | **5-6**: `glDeleteProgram` とリロードフック。**5-10**: 行列ユニフォーム 3 本をパス先頭に巻き上げ、`glGetInteger` ステートクエリを削除、角丸クアッドをインスタンシング/UBO でバッチ化（要素ごとの `endBatch()` とドローコールをやめる）、`RenderSystem` の blend/depth を復元 |
| **改** | `common/ServerImageRegistry.java` | **5-6**: `SIZES` の無制限成長に上限（キーはサーバー供給の画像 URL）。**5-12**: `client/` に移動して改名（実質クライアント専用） |
| **改** | `client/binding/DeclarativeBindingManager.java` | **5-6**: バインディング重複登録リーク（`ClientPubSubManager:38,42` に重複排除なし追加）。**5-9**: `serializeToSimpleHtml`（`:99-119`）の DOM→文字列→DOM 往復をやめる（**属性エスケープが無い**） |
| **新** | `client/HitTestIndex.java` | **5-7**: レイアウトごとに 1 回作る z ソート済み `(rect, clipRect, transformChain, nodeId)` 配列。**`clipRect` をヒット判定に使う**（現在 `overflow:hidden` でスクロールアウトしたコンテンツがクリック可能＝実バグ）。毎イベントの `HashMap` 再構築（`ClientWebManager:517-518`）が消える |
| **新** | `client/gui/WebTextInputHost.java` | **5-8**: 隠し `EditBox` 実装 1 本。現在 GUI 画面（`(-2000,-2000)`/`2048`）と `InvisibleInputScreen`（`(-1000,-1000)`/`8192`）に**同じトリックが 2 実装・制限値違い**で存在 |
| **改** | `client/gui/InvisibleInputScreen.java` | **5-8**: `WebTextInputHost` 利用に。`distSqr > 64.0`（`:70`、実際は 8 ブロック）を意図が読める形に |
| **削** | `WebRenderCore.findActiveEditBox()`（`:215-237`） | **5-8**: **レンダラ → GUI パッケージの逆依存**を解消。「任意の画面の子から `EditBox` を探す」フォールバックが**他 Mod やバニラのテキストフィールドを掴みうる** |
| **改** | `client/ClientScreenInteractionManager.java` | **5-8**: 世界側の GLFW 直接ポーリング（`:87-92`、20 Hz エッジ検出で 50 ms 未満のクリックが落ち左右ボタンが潰れる）。キーボードのモード 3 boolean → enum |
| **改** | `client/renderer/WebStateStore.java` | **5-9**: **キー単位の依存追跡**を導入（現在 `dirty` はストア全体で 1 boolean、`set()` が 207 行の全木歩行を起こす）。`pollBoundSuppliers` の毎フレームディープコピー比較（`copyListItems:146-153`、60 fps・100 行で**毎秒 6,000 個の `HashMap`**）を世代カウンタに。リスト行 ID の `id + "_row_" + index`（`uniquifyIds:467-473`、位置依存）をキー付き照合に。**5-12**: **`binding/` ディレクトリへ移動**（パッケージ宣言が `client.binding` なのにディレクトリが `renderer/` — 14 ファイル中これ 1 つだけ） |
| **改** | `client/ClientLocalEnvProvider.java` | **5-9**: 深い等価比較ガードを導入（`{{@fps}}` を含む UI が**毎秒 10 回全文書をレイアウト**するのを止める）。**5-12**: `CACHED_SNAPSHOT` の非原子的 `clear()`+`putAll()`（`:123-124`）と**生の可変マップ返却**（`:126`）を修正 |
| **改** | `client/renderer/WebHudRenderer.java` | **5-9**: `applyTemplateData`（`:501-569`、**同じ置換処理の 4 つ目の実装**）を共通化。**5-12**: `network.c2s.SubscribeC2SPacket` 直送（`:170`）をコントローラ層へ |
| **改** | `client/binding/ClientPubSubManager.java` | **5-9**: `changedAny = true` の無条件設定（`:93,:156`）を値比較に。仮想リストのテンプレート再パース（`:114`）とマジック数（`estimatedContainerW = 400`・`visibleRows = 15`、`:98-104`）を修正 |
| **改** | `client/gui/AbstractWebContainerScreen.java` | **5-12**: `Slot.x`/`Slot.y` リフレクション（`:63-77`）を直接代入に（**Mojmap 1.21.1 で public 非 final なので、おそらく不要**）。失敗の無言握り潰し（`:76,:304`）を解消 |
| **新** | `client/renderer/ZLayers.java` | **5-12**: `280.0f`/`300.0f`/`320.0f`/`350.0f`/`35.0f`/`20.0f`/`0.002f`… の散在した Z オフセットを定数化 |
| **改** | `client/renderer/UIRenderer.java` | **5-12**: `EditBox`/`findActiveEditBox` 依存（`:249`、**レイアウトコンパイラが生きたウィジェット状態を覗く**）を削除。`toLowerCase()` に `Locale.ROOT`（`:117,119,492,579,584`） |
| **削** | `WebSessionManager` 経由の `ClientScript` 経路 | **5-14**: `scriptLoaderCallback` が未代入のまま `<script>` 本体が毎回ネットワークを流れて捨てられている。`common/UIParser`（`:381-385` の抽出）・`server/WebSessionManager`（`:76` の送信）・`client/ClientWebManager`（`:181-182` の受信）から経路ごと削除 |
| **改** | `common/WebDevKitExporter.java` | **K**: `buildDevKitCss`(176 行)・`buildDevKitJs`(127 行) の Java 文字列生成をやめ、`assets/enderframelib/preview/` の**機能の多い既存リソース**（`.js` 19 KB・`.css` 6.7 KB、現在**どこからも読まれていない**）を読む形に |

---

### フェーズ 6: サーバー / API / 設定（13 ファイル）

| 記号 | クラス | 作業 |
|---|---|---|
| **改** | `server/ServerTargetSyncHandler.java` | **I-1（最重大）**: クライアント指定 NBT パスの許可リスト。`extractPaths:167` の `getAsString()` 部分木ダンプをやめる。`targetType="player"`（`:174`）と `saveWithFullMetadata`（`:145`）の扱い見直し。tick リスナーの static イニシャライザ登録（`:46-48`）を明示的初期化に |
| **改** | `server/WebSessionManager.java` | **I-2**: `forceSyncToPlayer`（`:106-122`）に所有者/近接/画面オープン検証。**I-3**: `handleClientEvent`（`:158-191`）でアクション名が送出 DOM に存在したか検証、`block:` 経路の 64 ブロック（`distSqr <= 4096.0`）を操作距離に縮め `sessionId` と BlockEntity の対応を検証、クライアント値 DOM 書き込み（`:139-155`）に長さ制限と要素種別チェック。**I-7**: `addViewer` を解決後に移す。**I-6**: `broadcastDom` に `hasDisconnected()` ガード、`STANDALONE_SESSIONS` の追い出し方針 |
| **改** | `server/ServerPubSubManager.java` | **I-4**: `subscribe`（`:19-25`）に ACL・名前空間・プレイヤースコープ・権限フック。購読時の全状態再生（`isFull=true`）の見直し。購読数上限。`channelData` の追い出し |
| **改** | `network/c2s/WebActionC2SPacket.java` | **I-7**: レート制限キー（`:87`）から `ElementId` を外す（**現在 `ElementId` を変えるだけで制限を完全迂回できる**） |
| **改** | `network/c2s/SubscribeC2SPacket.java` | **I-7**: レート制限を追加（**現在ゼロ**） |
| **改** | `network/s2c/PubSubUpdateS2CPacket.java` | **I-8**: `readMap`/`readValue`（`:51-113`）にエントリ数上限と再帰深さ制限（悪意あるサーバーがクライアントを OOM/スタックオーバーフローさせられる） |
| **改** | `common/UIParser.java` | **I-9**: `resourceTextResolver`（`:50-75`）のクラスパスパス正規化 |
| **改** | `EnderFrameLib.java` | **I-6**: `PlayerLoggedOutEvent`（`:55-60`）に **`WebSessionManager.removePlayerAll` と `WebActionC2SPacket.clearRateLimitsForPlayer` を実際に繋ぐ**（宣言済みで呼び出し元ゼロ）。**`ServerStoppingEvent`/`ServerStoppedEvent` リスナーを追加**（現在 0 件、統合サーバーのワールド再読込で全 static 状態が残る）。`PlayerChangedDimensionEvent` も |
| **新** | `server/EnderFrameServerState.java` | **I-6**: サーバー側の全 static 状態（`WebSessionManager` 3 マップ・`ServerPubSubManager` 2・`ServerTargetSyncHandler` 2・`RATE_LIMIT_MAP`・`LOADED_UIS`・`GLOBAL_PROVIDERS`）を 1 箇所から破棄できるように |
| **改** | `api/IWebSessionProvider.java` | **H-1**: 具象 `server.WebSession` を返すのをやめ `api` 側インターフェースに（`HtmlNode` と `UIParser.Document` の露出も切る） |
| **新** | `api/WebDocument.java` / `api/WebSessionHandle.java` | 上記の `api` 側インターフェース |
| **新** | `api/EnderFrameAPI.java` | **H-3**: 画面を開く正式な入口。現在 `EnderFrameScriptingBridge`（KubeJS 向け static ファサード）しかない |
| **改** | `api/EnderFrameScriptingBridge.java` | `api → server`（5 import）・`api → network/s2c`（1）を切る。`onGlobalServerAction`（`:108`）が**クライアント要求でセッションを生成する**点を見直す |
| **新** | `api/package-info.java` | `@ApiStatus` ＋ 英語 Javadoc の方針。現在マーカーは全 62 ファイルで 0 件 |
| **改** | `server/WebSessionManager.java` / `common/UIParser.java` / `core/dom/HtmlNode.java` | `@ApiStatus.Internal` を付与 |
| **解** | `api/config/EnderConfigManager.java` | **J**: `generateStandardHtml5Config`（108 行、`:315-422`）を分割。NeoForge `ModConfigSpec` を裏に置き HTML 生成だけ自前にする（または自前維持なら: 設定値フィールドを `volatile`/アクセサ経由に、`register()`（`:231-233`）の read-modify-write を同期化、`setAccessible` 重複呼び出し（`:51,:70,:129`）削除、リスナー 2 重発火（`:269`/`:306`）修正、`load()` の型エラー握り潰し（`:122`）に警告） |
| **解** | `server/EnderFrameCommands.java` | `register()`（141 行、`:42-182`）を分割 |
| **改** | `server/EnderFrameDataPackManager.java` | 二重キー登録による件数誤表示（`:81-83`）、`.css` の扱い |
| **改** | `network/EnderFrameNetwork.java` | 死んだ `@SubscribeEvent`（`:13`）を削除。`.versioned("1.0.0")` ハードコードを `mod_version` 連動に |

---

### フェーズ 7: プロジェクト衛生（コード外中心）

| 記号 | ファイル | 作業 |
|---|---|---|
| **改** | `.gitignore` | `run/`・`.idea/`・`repo/`・`src/generated/`・`*.log` を追加（現在 4 行、`/build/classes/java/main/` は冗長） |
| **削** | `run/logs/*`・`run/config/*`・`run/downloads/*`・`.idea/*` | `git rm --cached`。`debug.log` 96 KB、**開発機の絶対パス `D:/Dev/Personal/...` を含む** |
| **改** | `README.md` | **NeoForge MDK テンプレートそのまま**（25 行）。概要・導入（Maven 座標）・最小使用例・対応 HTML/CSS 一覧・非対応一覧・ライセンス |
| **新** | `LICENSE` | `mod_license=LGPL-3.0` と宣言しているのに**本文が無い**（LGPL-3.0 は告知を要求）。`lunex_video.dll` と `yt-dlp`（Unlicense）の帰属表記も |
| **新** | `CONTRIBUTING.md` | コメント言語の規約（現在 49/62 ファイルに日本語、**公開 API の Javadoc も全て日本語**、識別子は英語）。既知の制約を記録する方針（`TODO`/`FIXME` が 0 件） |
| **新** | `test/.../gametest/WebScreenGameTest.java` | `build.gradle` が 3 run 設定で `enabledGameTestNamespaces` を有効化済みなのに**中身が 0**。画面オープン→描画→ログアウト→マップ空を確認 |
| **改** | `gradle.properties` | `mod_group_id=com.nishiyu.enderframe` とパッケージ `com.nishiyu.enderframelib` の不一致（Maven 公開で問題）。`org.gradle.configuration-cache=true`（ModDevGradle と相性が悪い）の確認 |
| **改** | `build.gradle` | 空 `repositories { }` の確認。`:78` の**存在しないクラス `ClientMediaManager`** を参照するコメント修正 |

---

# 各フェーズの詳細

以下は上の一覧の各項目について「なぜそれをするのか」を述べたもの。
クラス単位の作業内容は上の表、設計上の理由はこちらを参照。

---

## フェーズ 1: `core/` をリーフ化し、テストを走らせられるようにする

これが**全体の前提条件**。影響する呼び出し箇所はたった 3 つ。

### 1-1. `core/` から `core/` 外への依存を 3 箇所切る

| 変更 | 対象 | 方法 |
|---|---|---|
| `HtmlNodeSerializer` を `core/dom` から移す | `core/dom/HtmlNodeSerializer.java` | `network/` 配下へ移動。`core/` で唯一 `net.minecraft`（NBT）を import するファイル。あわせて再帰 `deserialize` に深さ制限を入れる（I-8 系の防御） |
| `DomUtils` → `common.UIParser` を反転 | `core/dom/DomUtils.java:3,:127` | `UIParser.expandCustomTags(html)` の直呼びを、注入された `Function<String, HtmlNode> innerHtmlParser` に置換。**`core → common → server → net.minecraft` の推移鎖を断つ最重要の 1 行**（既に 4 箇所で使われている注入パターンと同形） |
| `LayoutBox` → `common.ServerImageRegistry` を反転 | `core/layout/LayoutBox.java:3,:197,:288` | `getSize(src)` の static 呼びを、レイアウトコンテキストに載せた `IntrinsicSizeResolver` インターフェースに置換。副産物としてレイアウトがプロセスグローバル状態に依存しなくなり、テストで再現可能になる |

### 1-2. ビルドにテスト基盤を入れる

- `build.gradle` に `src/test/java` ソースセットと JUnit 5（`junit-jupiter`）を追加し、
  `test { useJUnitPlatform() }` を設定
- **`core/` の純粋性を守るテスト**を追加: `core/**` のソースを走査して
  `net.minecraft` / `net.neoforged` / `com.nishiyu.enderframelib.client` /
  `...common` / `...server` の import が現れたら失敗させる。
  これが無いと次の MC import がすぐ戻ってくる（ArchUnit を入れるか、
  依存を増やしたくなければ素の `Files.walk` + 正規表現で十分）
- `.github/workflows/build.yml` を追加: `gradlew build test`

> 注: この環境では Maven Central / `maven.neoforged.net` への到達が
> プロキシに阻まれている（`429` / `CONNECT tunnel failed 403`）ため、
> **依存解決を伴うビルド検証はこのコンテナ内では実行できない**。
> ローカルまたは CI での `gradlew build test` 実行を前提とする。

### 1-3. 特性テスト（characterization test）で現在の挙動を固定

**型を変える前に**書く。意図は「正しさの検証」ではなく
「一括再設計で何が変わったかを検出すること」。

- `HtmlTokenizer`/`HtmlParser`: 代表的な HTML（`showcase.html` を含む）→
  `getOuterHTML()` のゴールデン比較。void 要素、生テキスト（`script`/`style`/
  `textarea`）、実体参照、不正ネストを網羅
- `CssParser`: セレクタ照合（対応している全疑似クラス・結合子・属性セレクタ）、
  `parseColor` の全形式、`parseDim` の全単位、`calc()`/`clamp()`、
  カスケードの勝敗、`@media`、`var()` 解決
- レイアウト: block/flex/grid の代表ケースについて
  `(x, y, w, h)` ツリーのゴールデン比較。`TextMetrics` は組み込みの
  純 Java フォールバックを使えば決定的になる
- `MarkdownParser`: `guidebook.md` → HTML のゴールデン比較
- `DomDiffEngine`: append / truncate / 中間挿入 / 中間削除 / タグ変更

**A-8 と I の既知バグは、この時点では「現状の挙動」としてそのまま固定し、
修正はフェーズ 3 以降で意図的に行ってテストを更新する。**
バグ修正とリファクタリングを同じコミットに混ぜない。

---

## フェーズ 2: スタイル表現の再設計（基盤の中心）

評価の結論どおり、ここが一括再構築の核。

### 2-1. `ComputedStyle` 値型を導入し、`Map<String,String>` を置換

- CSS プロパティ名を**定数または enum**（`CssProperty`）に集約。
  現在 152 箇所に散在する生文字列参照と、`"display"` 25回・`"width"` 25回・
  `"height"` 29回…のリテラルを 1 箇所に集める
- **型付きアクセサ**（`length(CssProperty)`, `color(...)`, `keyword(...)`,
  `number(...)`）。パースは算出時に 1 回だけ行い、結果を保持する。
  これで毎参照の文字列再パースが消える
- **不変（またはコピーオンライト）にする**。フェーズ 2-3 でエンジンの
  書き換えを排除するための前提
- **HTML 属性を算出スタイルから分離**: `CssParser.java:309` の
  `style.putAll(node.attrs)` を削除する。唯一の依存者
  `UIRenderer.java:645`（`src` 読み）を `node.attrs` 直読みに直す。
  レイアウト/レンダラが `style.get("data-*")` / `style.get("mc-*")` で
  読んでいる箇所をすべて `node.attrs` 経由に移す
- **presentational 属性**（`<img width>` など）は、必要なら
  カスケードの最下位 origin として明示的に扱う（現状は最上位＝バグ）

### 2-2. 疑似クラス状態を型で表す

`"hover-"` / `"focus-"` / `"active-"` のキープレフィックス機構を廃止し、
状態ごとの宣言集合を持つ構造に変える（例: `StyleSet { base, hover, focus, active }`、
または `ElementState` を引数に取る解決関数）。これにより:

- `:hover` ブロック内の**全プロパティ**が効くようになる
  （現在は 5 キーだけ。A-2 の「黙って捨てられる」が解消）
- 結合子有無で挙動が変わる二重機構（`CssParser.java:748-758`）が 1 本化
- インラインスタイルが hover を消す副作用がなくなる
- `UIRenderer.buildPseudoStateDef` のマジック連結文字列（`:318`）を
  構造体に置換でき、`UIElement.animDef` のマーカー文字列も解消

### 2-3. 算出スタイルのキャッシュを正す

- キャッシュを `HtmlNode` から外し、**ビュー単位の解決コンテキスト**に移す。
  これで対話状態（C-2）も文書から外せる＝1 文書を複数ビューで共有可能になる
- キャッシュキーに継承スタイル・スタイルシート世代・env 世代を含める
- **エンジンによる書き換えを排除**: フェーズ 3-2 で
  save/force/recurse/restore（8 箇所）を、制約を引数で渡す
  再測定 API に置き換える。`finally` 漏れによるキャッシュ焼き付きも消える
- `HtmlNode.markDirty()`（`:260-272`）の範囲を適正化。
  全兄弟＋全祖先を dirty にする現在の実装をやめ、
  実際に影響を受ける範囲（自身＋継承プロパティが変わった場合の子孫）に絞る

### 2-4. `CssParser`（837 行）を責務ごとに分割

| 新クラス | 現在の所在 |
|---|---|
| `CssTokenizer` | 新規。`CSS_PATTERN` 正規表現（`:15`）を置換し、ネスト波括弧・文字列・`url()` を正しく扱う |
| `CssRuleParser` / `AtRuleParser` | `processAtRules()` **461 行**（`:379-839`）を置換。トークナイザができれば `@media` の文字列平坦化そのものが不要になる |
| `Selector` / `SelectorParser` / `SelectorMatcher` | `StyleRule`(`:703-833`)・`SelectorToken`(`:509-701`) を**トップレベル型**に昇格。`contains`+`replace` の破壊的文字列手術（`:541-548`）を実トークナイズに置換 |
| `Specificity`（タプル型） | `:768-781` の文字数カウントを (id, class, type) タプルに。桁溢れと `::before` > class の逆転を解消。`!important` とカスケード origin を導入し、`UIParser.java:199-207` の `+= 10000` マジックを廃止 |
| `StyleResolver` / `Cascade` | `computeNodeStyle()`（`:275-377`）を置換 |
| `ColorParser` | `parseColor`(`:38-119`)・`hslToArgb` |
| `LengthResolver` | `parseDim`(`~:150-172`)・`parseBoxEdgesStr`・`parseBorderRadius`。`em` を要素フォントサイズ基準に、`rem` をルートフォントサイズ基準に正す（A-8） |
| `CalcEvaluator` | `:173-242`。**可変 `pos`/`ch` を持つ匿名クラスを返す**実装を通常のクラスにして単体テスト可能にする |
| `StyleSheet` | `:833-837` をトップレベル化 |

- `core/dom ↔ core/css` の循環（E）を切る: `HtmlNode.querySelector` が
  `CssParser.StyleRule` を直接 `new` している（`HtmlNode.java:54,:70`）のを、
  `css` 側に置いた `SelectorEngine.query(node, selector)` に移す。
  あわせてセレクタをキャッシュする（現在は呼び出しごとに再トークナイズ）
- 継承プロパティ（A-7）を正す: `font-style`, `text-decoration`, `text-shadow`,
  `white-space`, `letter-spacing`, `visibility`, `cursor`, `direction` を追加。
  これでリンクの下線・打ち消し線が直り、
  `BlockLayoutEngine.isBoldBox()` の bold 専用第 2 機構（`:71-81`）を削除できる
- **UA スタイルシートと 3 テーマを `resources/` に出す**:
  `common/UIParser.java:81-192` の約 250 行の CSS 文字列リテラルと、
  `WebDevKitExporter.java:185` の第 4 コピーを 1 つのリソースファイルに統合
- バグ修正（テスト更新込み）: `@media` の OR → AND（`:408-419`）、
  `:root` 宣言の破棄（`:491-498`）、`border-width` ショートハンド未展開（`:357-371`）

---

## フェーズ 3: レイアウト層の再設計

### 3-1. コンテキストオブジェクトを導入し、インターフェースを作り直す

- `FormattingContext`（親の display モード・`align-items`・shrink 可否）と
  `Viewport`（`rootW`/`rootH`）を値型にする。
  これで `LayoutEngine` の 9 引数が 2〜3 引数になり、
  `display: inline` / `inline-block` / 絶対配置を**インターフェース変更なしで**
  追加できるようになる
- `w == -1` の shrink-to-fit センチネルを明示的な `SizingMode` enum に
- `Object engineState` を型付きの per-engine 状態に。
  `LayoutBox.java:378-384` が `GridLayoutEngine.GridState` を
  `instanceof` で覗く循環を解消する（スクロール範囲算出を
  エンジン側の責務として interface に出す）
- メソッド名を実態に合わせる（`computeHeightAndFinalizeChildren` は
  flex grow/shrink 解決と絶対配置子サイズ決定もしている）

### 3-2. `LayoutBox` からアルゴリズムを剥がす

- `LayoutBox` は**ジオメトリ結果の保持**に限定する。
  `computeSize()` の **218 行**（`:106-323`）をエンジン側へ移す
  （現在エンジンと `LayoutBox` に二重のレイアウトロジックがある）
- フィールドを `public` から適切な可視性へ（破壊的変更 OK の方針を活かす）
- 置換要素の固有サイズ（`:63-72,192-204,283-303` のタグ名チェーン 13 箇所）を
  フェーズ 1-1 で導入した `IntrinsicSizeResolver` のレジストリに移す。
  **ここが将来のカスタム要素拡張点の土台になる**（H-3、今回は席を用意するだけ）
- `§l`（MC 太字コード）の埋め込み（`:81,:187`,
  `BlockLayoutEngine:125,258`）を core から追い出し、
  太字は構造化された情報として渡してレンダラ側で書式化する

### 3-3. レイアウト ↔ DOM の書き戻しを断つ（B-3 のバグ修正）

- `LayoutBox.java:389-390` の `data-scroll-w`/`data-scroll-h` の
  DOM 属性書き込みをやめ、スクロール範囲は `LayoutBox` のフィールドに持つ
- スクロール**入力**（`:345-348` の `data-scroll-x/y` 読み）も
  DOM 属性経由をやめ、ノード ID をキーにした `ScrollState` を
  レイアウトコンテキストで渡す
- これで `DomDiffEngine` が毎回 `changed == true` を返す原因が消え、
  **「何も変わっていないのに毎回フルリレイアウト」が止まる**。
  イベント駆動ゲート（`ClientWebManager:274-293`）が本来の効きを取り戻す

### 3-4. 共通基底を作り、重複約 400 行を畳む

`AbstractLayoutEngine`（または共有ヘルパー）に集約:
- 絶対配置子の確定（現在 `Block:64-68`/`Flex:190-194`/`Grid:250-254` に同一 5 行）
- `line-height` 解析（**4 コピー**: `LayoutBox:256-262`, `Block:93-102`,
  `Block:232-239`, `UIRenderer:698-703`）。`%` と `normal` に対応し、
  マジック既定値 `1.375f`（4 箇所）を名前付き定数に
- `justify-content` の space 配分（`Flex:232-246` と `:286-300` が
  `float`/`int` 違いだけの同一ロジック）
- `display:none` / flow 子フィルタ（同一式 10 箇所）
- 日本語/英語の分割正規表現（`TextMetrics:35`, `LayoutBox:78`,
  `Block:120,252` の 4 コピー）
- bold 判定正規表現（4 コピー）
- マジック数の定数化: 基準フォントサイズ `16`（11 箇所）、
  `maxW + 4` の説明なき 4px スロップ（`Block:127,261,283`）、
  「無限幅」`9999`（`LayoutBox:61,181`）、`1920`/`1080` フォールバック

**`BlockLayoutEngine.measureInlineRunHeight`(83-154) と
`layoutInlineContext`(222-341) の統合は優先度が高い**: 約 70 行の
再実装で、折返しロジックを片方だけ直すと測定高と配置高がずれる。

### 3-5. レイアウトの正当性修正とガード

- `min-height` が `horizontalDecoration()` を足すコピペバグ（`LayoutBox:318-322`）
- 未ガードの `Float.parseFloat`:
  `Flex:110,113,125,143,151,179`（`flex-grow`/`flex-shrink`）、
  `Grid:81,90`（`fr`）、`CssParser:412,417`（media query px）
- `catch` ブロック内で未ガードの `parseFloat` を呼ぶ
  `LayoutBox:259`, `Block:97,236`
- `GridLayoutEngine.REPEAT_CACHE`（`:26`）に上限と追い出しを入れる
  （信頼できない作者 CSS がキー）
- `LayoutBox.getMinContentWidth`（`:60-94`）に測定メモ化を入れる
  （flex shrink ループから最大 10 回呼ばれ、現在 O(n²·10)）
- `UIParser.java:411-417` の `computeSize` 二重呼び出しを、
  意図を明示した収束ループかコメント付きの 2 パスに置き換える

---

## フェーズ 4: DOM 層の整理

- **非正規化の解消**（C-1）: `id`/`classes`/`isHovered`/`isChecked` と
  `attrs` の二重持ちをやめる。`attrs` を単一の真実の源にして
  派生ビューをキャッシュするか、逆に属性を導出にする。
  いずれにせよ**同期経路を 4 → 1 にする**
  （`HtmlNode:131-138`, `DomUtils:38-59`, `DomDiffEngine:36-56`,
  `DomUtils:186-195` のうち 3 つを削除）
- **対話状態を文書から外す**（C-2）: `isHovered`/`isChecked`/`isActive`/
  `isFocused` をビュー単位の `InteractionState` に移す。
  これで 1 文書を複数ビューで共有でき、ホバーがスタイルキャッシュを
  無効化しなくなる
- **テキストノードを型にする**: `"#text".equals(node.tag)` の文字列比較
  14 箇所を解消
- `attrs` を `LinkedHashMap` にして属性順を保つ（`getOuterHTML()` が
  シリアライズとして使われているため実害がある）
- **void 要素集合の二重定義**（`HtmlParser:12-15` と `DomUtils:16`）を 1 本化
- **ノード ID を安定化**（C-3）: `HtmlParser.globalAutoId` の
  プロセスグローバルカウンタをやめ、ツリー内パスなど再パースで安定な
  導出に変える。レンダラの `id + "_txt_" + n` 文字列派生と
  `ClientWebManager` の 21 要素サフィックス表（`:49-52`）を
  構造化されたハンドルに置換
- **`DomDiffEngine` をキーベースに**（C-4）: 既にある `id`（または `key` 属性）で
  照合する。あわせて削除時の `child.parent` クリア（`:92`）と
  `isFocused`/`isActive` の同期漏れを直す
- **HTML 処理を 3 層 → 1 層に寄せる**（C-5）:
  `UIParser.expandCustomTags` の正規表現マクロ展開（114 行）と
  `MarkdownParser.processDocumentOrBlocks` の第 3 の正規表現 HTML マッチャを、
  トークナイズ後の DOM 変換に移す。少なくとも
  **Markdown 正規表現が全 HTML に無条件で走る**（`expandCustomTags:240`）のは止める
- `HtmlTokenizer`: `script`/`style` 内の実体参照復号（`:121`）をやめる、
  実体参照を数値参照対応に広げる、`MAX_NODES`（`:28`）をノード数に直して
  超過時に警告を出す（現在は無警告切り捨て）、死んだ `isRaw` 引数を削除
- `HtmlParser` に最低限の暗黙クローズ（`<p>`/`<li>`）を入れる

---

## フェーズ 5: `client/` の分割（約 10,650 行）

`UIElement` を境界とする構造自体は正しいので、作り直すのではなく
**境界を型にして、god クラスを既にある継ぎ目で割る**のが基本方針。
以下は 5-1 → 5-2 の順に依存する（5-1 が他の前提）。

### 5-1. `UIElement` 境界を型にする（D-1 / D-2 の解消）

ここが client 側の起点。**`RenderMeta` への移行は半分終わっている**ので、
完了させれば文字列の符号化/復号ペアが全部消える。

- `ClipRect`（8 値）と `Corners`（4 値）をレコードにする。
  **9 つのシグネチャから約 12 引数が機械的に消え、
  D-4 の `playAudio` 取り違えバグが構造的に起こり得なくなる**
- `formatRadii`/`formatClipRect`（`UIRenderer:908-914`）と
  その読み戻しを削除
- `PseudoStates` レコード（hover/active/focus）を `RenderMeta` に載せ、
  `buildPseudoStateDef`（`UIRenderer:291-318`）・
  `WebAnimator.extractMarker`（`:218-237`）・`parsePseudoDefs` と
  `@@HOVER@@` センチネルを削除。
  フェーズ 2-2 の疑似クラス状態の型化とここで繋がる
- `el.type()` を `String` から enum に。画像フィットモードを
  型文字列から独立フィールドに出す（`UIRenderer:652` / `WebRenderCore:661`）
- **基底ノード ID を `UIElement` のフィールドにする**。
  `UIRenderer` は派生 ID を作る時点で基底を知っているので、
  21 サフィックスの `endsWith` ループ（`ClientWebManager:351-360`、
  要素あたり毎フレーム 3〜4 回）が消え、
  3 箇所に重複してドリフトしているサフィックス表
  （`:49-52` / `:522-523` / `:372`、`"_shadow"` が既に不一致）が 1 本化する
- `events` マップのプロトコルキー（`mc-parent`・`mc-clip`・`mc-radius`・
  `mc-gradient`・`mc-font-h`・`mc-title`・`mc-origin`・`mc-delay`・
  `mc-easing`・`mc-scrollable`・`mc-scroll-current`）を定数に集約。
  現在はタイポが無言の no-op になる

### 5-2. god クラスを継ぎ目で割る

**`AbstractWebScreen`**: 実体は入れ子の `WebUiScreenSupport`（344-1508、1,165 行）。
D-3 の表の継ぎ目をそのまま抽出する。依存の少ない順に:
`WebViewport`(56) → `WebActionRouter`(~45) → `WebDevTools`(83) →
`SelectDropdownWidget`(~70) → `WebFocusNavigator`(220) →
`WebTextareaEditor`(128) → `InteractionHandler` 連鎖(151)。
目標は 1,165 → 約 250 行の純ディスパッチ。

**`WebTextareaEditor` 抽出時に併せて**、
`WebRenderCore` が持っている編集状態 3 マップ
（`inputScrollOffsets`/`textareaCursorIndex`/`textareaScrollRow`、`:64-66`）を
`TextEditState` としてエディタ所有に移す。レンダラは読むだけにする。
**これが client 層で最悪の層違反**（キャレット状態が描画層所有で、
入力層と描画層の双方が書く）。

**`WebRenderCore` の幾何ライブラリ**: 4 つのほぼ同一な角丸ラスタライザを
テクスチャ有無で分岐する 2 つに畳む。角の内側オフセット式
`sqrt(r²−(r−i−0.5)²)` の 16 箇所の逐語重複を 1 関数に。
死んだ引数（`renderMode`・`ICustomRenderer`）と
死んだフィールド（`currentPassIs2D`）を削除。

**`WebStateStore.applyRecursive()`（207 行、`:197-403`）**: コメント
`// 1.` 〜 `// 7.` が既に extract-method の境界を示しているので、そのまま 7 分割。

**`WebHudRenderer.processAndRenderSingleTracker()`（350 行）**:
NBT 平坦化 / JSON 強制変換 / キャッシュ判定 / レイアウト木構築 /
ビルボード変換 / 描画に分割。

### 5-3. 6 つの描画ホストの前置処理を 1 本化

`renderElements` 自体は正しく共有されている。重複しているのは
約 15 行のホスト前置処理（論理 W/H 計算 → pushPose → scale →
bufferSource 取得 → 呼び出し → endBatch → popPose）で、6 箇所にある:
`AbstractWebScreen:219-244`, `AbstractWebContainerScreen:340-364`,
`WebHudRenderer:254-275`（画面空間 HUD）, `WebHudRenderer:410-429`（ビルボード）,
`WebBlockRenderer:69-89`（ブロック面・LOD 付き）,
`WebItemRendererHelper:73-102`（手持ち）。

→ `WebPaintPass` ビルダ（`.forGui(...)` / `.forBlockFace(...)` /
`.forBillboard(...)`）に集約する。5-1 の `ClipRect`/`Corners` と併せて
**22 個の位置引数の取り違えが起こり得なくなる**。

### 5-4. 2 つの画面クラスの重複（38%）とそこに潜むバグ

187/486 行が同一なので、`WebScreenDelegate`（または default メソッドを持つ
共有インターフェース）に抽出して両者で合成する。
**抽出と同時に D-4 の 5 つの分岐バグを直す**:

1. コンテナ画面で `onmouseenter`/`onmouseleave` が発火しない
2. サーバー管理コンテナセッションが `ensureSynced` を呼ばない
3. `initOrBindSession` の戻り値が捨てられ `onClose` が無条件にセッションを消す
4. コンテナ画面のサブクラスが `onInputChanged` を受け取れない
5. `playAudio` 引数が `true`/`false` で食い違っている

あわせて `handleMouseClicked` に 9 回コピペされている
「値コミット 4 行の呪文」（D-11）を `commitValue(id, v)` 1 メソッドに。

### 5-5. セッション状態を 1 つのオブジェクトに（D-7）

`ClientWebManager` の 13 本の並列マップ ＋ public 可変コールバック 3 本を
`Map<String, SessionContext>` 1 本にする。
`clearSession`（`:636-654`）のショットガン解体が
`SESSIONS.remove(id).close()` になる。

同時にセッション別でないものをセッション別にする:
- **`WebRenderCore.activeInputId` / `activePressedId`**（`public static String`、
  `:68-69`）— 現在は GUI 画面とブロック画面でフォーカスを共有してしまう
- `WebHudRenderer.currentTrackerSessionId`（単一スロット）—
  2 つ目の `data-tracker` セッションが 1 つ目を黙って追い出す
- `WebRenderCore` の編集状態 3 マップ（5-2 で移動）
- `WebAnimator.STATES` / `WebCanvasContext.CONTEXTS` / `WebSceneContext.SCENES` の
  `sessionId + "_" + id` 文字列連結複合キー（`cleanup` が
  `startsWith`/`substring` で走査する原因）

### 5-6. リソースリークを塞ぐ（D-8）

優先順:
1. **`ENTITY_PREVIEW_CACHE`（`WebRenderCore:67`）をレベルアンロードでクリア** —
   生きた `Entity` を保持しており `ClientLevel` 全体が残る。実メモリリーク
2. **`IMG_CACHE` に上限と追い出しを入れ、`DynamicTexture` を解放する**。
   テクスチャ ID を `url.hashCode()`（32bit）から衝突しない導出に変える
3. `VideoTexture.close` の `alDeleteBuffers` を無条件にする（`:853-869`）
4. `WebFontManager.clearCache()`（`:227`、**呼び出し元ゼロ**）を
   リソースリロードに繋ぐ。`FontSet` を `close()` する
5. `WebSdfShader` に `glDeleteProgram` とリロードフックを入れる
6. `YT_DLP_CACHE` の TTL 切れエントリを実際に削除する
7. `WebHudRenderer.trackerCaches` の `+10` ヒューリスティック（`:310-312`）
8. `ClientPubSubManager` のバインディング重複登録（D-13 のリーク）に
   重複排除を入れる
9. `ServerImageRegistry.SIZES` の無制限成長（キーはサーバー供給の画像 URL）

### 5-7. ヒットテストをキャッシュし、クリップを尊重する（D-9）

- レイアウトごとに 1 回、z ソート済みの
  `(rect, clipRect, transformChain, nodeId)` 配列を作る。
  毎イベントの `HashMap` 再構築（`ClientWebManager:517-518`、
  毎フレーム数回）が消える
- **`clipRect` をヒット判定に使う** — 現在
  `overflow:hidden` でスクロールアウトしたコンテンツがクリック可能（実バグ）
- 相互作用可能性の文字列接頭辞推測（`startsWith("on")`・
  `animDef().contains("@@HOVER@@")` など）を、5-1 の型に基づく判定に置換
- `handleHover(session, -1, -1)` のマジック値を `clearHover()` に

### 5-8. 入力層の整理

- **`WebTextInputHost` 抽象を 1 つ作る**（隠し `EditBox` 実装 1 本）。
  現在 GUI 画面（`(-2000,-2000)`/`maxLength 2048`）と
  `InvisibleInputScreen`（`(-1000,-1000)`/`8192`）に
  **同じトリックが 2 実装・制限値違いで**存在し、
  Enter/ESC/改行ロジックも二重化している
- これにより `WebRenderCore.findActiveEditBox()`（`:215-237`）を削除できる。
  現状は**レンダラが GUI パッケージに依存**し、
  「任意の画面の子から `EditBox` を探す」フォールバックが
  **他 Mod やバニラのテキストフィールドを掴みうる**
- キーボードのモード 3 boolean（`isEditingTextarea` /
  `activeInputId.isEmpty()` / `focusedElementBaseId.isEmpty()`、`:939-992`）を enum に
- `InvisibleInputScreen:70` の `distSqr > 64.0`（実際は 8 ブロック）を
  意図が読める形に直す
- 世界側の GLFW 直接ポーリング（`ClientScreenInteractionManager:87-92`）—
  20 Hz エッジ検出で 50 ms 未満のクリックが落ち、左右ボタンが潰れる。
  可能なら入力イベントに寄せる

### 5-9. リアクティビティを 1 系統に寄せる（D-13 / D-14）

- **キー単位の依存追跡**を `WebStateStore` に入れる。
  `set("hp", x)` が 207 行の全木歩行を起こすのをやめる。
  現在 `dirty` はストア全体で 1 boolean
- `pollBoundSuppliers` の毎フレームディープコピー比較
  （`copyListItems:146-153`、60 fps・100 行で**毎秒 6,000 個の `HashMap`**）を
  世代カウンタか変更通知に置換
- **`WebHudRenderer.processHudTemplates:220-223` の深い等価比較ガードを
  `WebStateStore` と env 層にも入れる** — ここだけが正しくやっている。
  これで `{{@fps}}` を含む UI が**毎秒 10 回全文書をレイアウトし直す**のが止まる
  （フェーズ 3-3 の diff 暴走修正と合わせて効く）
- `WebHudRenderer.applyTemplateData`（`:501-569`、同じ置換処理の 4 つ目の実装）を
  共通化する
- リスト行 ID の `id + "_row_" + index`（位置依存、`uniquifyIds:467-473`）を
  キー付き照合に。フェーズ 4 の `DomDiffEngine` キー化と揃える
- `DeclarativeBindingManager.serializeToSimpleHtml`（`:99-119`）の
  DOM → 文字列 → DOM 往復をやめる（**属性エスケープが無い**ので
  `"` を含む属性値がマークアップを壊す）
- `ClientPubSubManager` の仮想リストのマジック数
  （`estimatedContainerW = 400`、`visibleRows = 15`、`:98-104`）を
  実際のレイアウト値に

### 5-10. SDF シェーダのバッチ化（D-6）

**単独で最大のフレーム時間改善**。優先軸は保守性だが、
これは局所的で効果が大きいので独立に着手できる。

- 最低限: 行列ユニフォーム 3 本をパス先頭に巻き上げる（パス全体で定数）、
  `glGetInteger(GL_CURRENT_PROGRAM)` のステートクエリを削除
- 本来: 角丸クアッドをインスタンシング / UBO でバッチ化し、
  要素ごとの `endBatch()` とドローコールをやめる。
  渡された `VertexConsumer` を使う
- `RenderSystem` の blend/depth を復元する。
  プログラム復元を生 `glUseProgram` でなく `RenderSystem` 経由にする

### 5-11. ログを入れる（D-16）

**10,656 行に LOGGER 2 行**が、今後のデバッグ難易度の根本原因。
最低限ここから:
- `WebMediaManager:466` のネイティブロード `Throwable`（一度だけ `warn`）
- `AbstractWebContainerScreen:76`/`:304` の `Slot` リフレクション失敗
  （失敗すると全スロットが黙って誤位置に描かれる）
- **3 つの `static {}` 内のイベント登録**
  （`WebHudRenderer:91-96`・`WebMediaManager:219-224`・
  `ClientScreenInteractionManager:61-66`）— 失敗するとサブシステムが無言で不活性
- `WebStateStore:56,70,80,171,180` の**ユーザー提供コールバック**の握り潰し
- `AbstractWebScreen:106-112` → `EnderFrameClientInit:62` の
  文字列型エラーチャネル（HTML エラー文字列を部分文字列一致で検出）を
  例外か `Optional` に

### 5-12. 層違反・死んだコード・その他

- **`renderer/WebStateStore.java` を `binding/` に移動**（D-17）。
  パッケージ宣言が `client.binding` なのにディレクトリが `renderer/`。
  14 ファイル中これ 1 つだけ。IDE ナビゲーションと静的解析が壊れる
- 層違反の解消: `renderer` → `gui`（5-8 で解決）、
  `UIRenderer` → `EditBox`/`findActiveEditBox`（同）、
  `WebHudRenderer` → `network.c2s.SubscribeC2SPacket`（パケット送信を
  コントローラ層に出す）
- `client/` 内のパッケージ循環 5 組
  （`client ↔ renderer` 8/12、`client ↔ gui` 3/5、`client ↔ binding` 4/1、
  `renderer ↔ gui` 1/7、`gui ↔ compat`）を解消
- 死んだコードを削除: `currentPassIs2D`、`renderElements` の `renderMode`、
  `ICustomRenderer`（全呼び出し元が `null`）、
  `WebFontManager.clearCache()`（5-6 で繋ぐなら残す）、
  `WebAnimator.getValues` 3 引数版・`isElementAnimating`・`isAnimating`、
  `FontFaceSpec.shiftX/shiftY`
- `AbstractWebContainerScreen` の `Slot.x`/`Slot.y` リフレクションは
  **Mojmap 1.21.1 で public 非 final なので、おそらく不要**。直接代入に置換
- `WebFontManager:141-152` の「形で照合する」リフレクションを
  名前指定に（将来 MC に同形メソッドが増えると誤バインドしうる）
- マジック数の定数化: Z レイヤ（`ZLayers` クラス）、
  **OpenAL/GL の数値リテラルを `AL10`/`GL11` 定数に**、
  Catppuccin パレットをテーマ定義に、
  `1920`/`1080` と `854`/`480` の二重既定値を 1 本化
- `toLowerCase()` に `Locale.ROOT` を付ける（CSS キーワード比較、
  トルコ語ロケールの `I`/`ı` 問題）
- `ClientPubSubManager.activeSessions` の素の `HashSet` を
  `ConcurrentHashMap.newKeySet()` に
- `ClientLocalEnvProvider` の `CACHED_SNAPSHOT` を
  非原子的 `clear()`+`putAll()` から差し替えに変え、
  **生の可変マップを返すのをやめる**（`:123-126`）
- `WebMediaManager` の `close()` × `initVideoWorker` 競合（`:546-555` vs `:846`）—
  二重チェックを CAS かロックにする。現状はネイティブハンドルを漏らしうる
- `ProcessBuilder(...).waitFor()` にタイムアウトを付ける
  （`:385`/`:404`、4 スレッドプールが飢餓する）
- 嘘コメントの修正: `WebHudRenderer:169` の「8 引数コンストラクタ」、
  `WebMediaManager:17` と `build.gradle:78` の**存在しない `ClientMediaManager`**

### 5-13. 動画/ネイティブの抽象化（方針 3: 同梱のまま整える）

DLL はリソースに残す。整えるのは境界と失敗時の挙動:

- `IExternalVideoContext` 境界を整理し、`NativeVideoAPI` の
  `INSTANCE` static 初期化（`:445`、クラス初期化時に
  `Minecraft.getInstance().gameDirectory` を触る）を明示的な初期化に移す
- **`catch (Throwable t) { return null; }`（`:466`）で一度だけ `warn` を出す** —
  未対応プラットフォーム・ランタイム欠落・展開失敗・ウイルス対策の隔離が
  現在すべて無言で `null` になる
- **非 Windows で明示的に無効化**する。`liblunex_video.so`/`.dylib` は
  リポジトリに存在しないので、Linux/macOS では
  `getResourceAsStream` が `null` → `Native.load` が
  `UnsatisfiedLinkError` → 握り潰し → 動画要素が無言で空白になる。
  「このプラットフォームでは動画非対応」と 1 回ログに出す
  （描画側のフォールバック `WebRenderCore:588-597` は既に正しい）
- `Files.copy(..., REPLACE_EXISTING)`（`:461`）を
  ハッシュ比較してスキップする形に。現状は毎起動で上書きし、
  Windows でファイルがロックされていると例外 → 内側の
  `catch (Exception ignored)` に飲まれて**古いバイナリで続行**する
- アーキテクチャ判定が無い（`os.name` の部分文字列のみ）ので、
  Apple Silicon / Windows-on-ARM で誤ったバイナリを読もうとする
- **`yt-dlp` の自動ダウンロード＋実行（`:342-412`）を既定で無効にするか
  同意ゲートを置く**（I-5）。署名検証もハッシュ固定も無い実行可能ファイルを
  インターネットから落として、**サーバー供給の URL に対して実行**している。
  YouTube 分岐が `isUrlSafe` を通らない点も直す

### 5-14. 死んだ `ClientScript` 経路を削除（K）

`scriptLoaderCallback` が未代入のまま、`<script>` 本体がパースされ・
NBT 化され・**毎回フル同期でネットワークを流れて捨てられている**。
経路ごと削除する。将来スクリプトを入れるなら
サンドボックス設計から始めるべきで、今の形のまま誰かが
`scriptLoaderCallback` を繋ぐと、議論なしに
任意クライアントスクリプト実行になる。

---

## フェーズ 6: サーバー / API / 設定

### 6-1. 正当性・セキュリティ欠陥の修正（I）

リファクタリングで引き継いではいけない。**フェーズ 2〜5 と独立に着手できる**ので、
基盤再構築と並行して進めるのが望ましい。優先順:

1. **`ServerTargetSyncHandler` の任意 NBT 読み出し**（I-1、最重大）—
   サーバー側のパス許可リストを導入し、`CompoundTag.getAsString()` による
   部分木ダンプをやめる。`targetType="player"` と
   `saveWithFullMetadata` の扱いを見直す
2. **`forceSyncToPlayer` の無検証 viewer 追加**（I-2）—
   所有者/近接/画面を開いているかの検証を入れる。
   予測可能なセッション ID 生成も見直す
3. **`handleClientEvent` の無検証ディスパッチ**（I-3）—
   アクション名がサーバー送出 DOM に存在したかを検証する。
   `block:` 経路の 64 ブロック（`distSqr <= 4096.0`）を操作距離まで縮め、
   `sessionId` と BlockEntity の対応を検証する。
   クライアント値の DOM 書き込みに長さ制限と要素種別チェックを入れる
4. **PubSub の ACL**（I-4）— 名前空間/プレイヤースコープ/権限フックを入れ、
   購読時の全状態再生を見直す。購読数上限とレート制限を入れる
5. **`yt-dlp` 経路**（I-5）— YouTube 分岐に `isUrlSafe` を通す。
   そもそも実行時ダウンロード＋外部プロセス実行を既定で有効にするか再検討
6. **無制限マップとレート制限迂回**（I-7）— `addViewer` を解決後に移し、
   レート制限キーから `ElementId` を外し、`SubscribeC2SPacket` にも制限を入れる
7. **`PubSubUpdateS2CPacket` のデコード制限**（I-8）— エントリ数上限と再帰深さ制限
8. **`resourceTextResolver` のパス正規化**（I-9、潜在的）

### 6-2. ライフサイクルとリークの修正（I-6）

- `PlayerLoggedOutEvent` に `WebSessionManager.removePlayerAll` と
  `WebActionC2SPacket.clearRateLimitsForPlayer` を**実際に繋ぐ**
  （宣言済みで未使用）
- `ServerStoppingEvent`/`ServerStoppedEvent` リスナーを追加し、
  サーバー側の全 static 状態（`WebSessionManager` 3 マップ、
  `ServerPubSubManager` 2、`ServerTargetSyncHandler` 2、
  `RATE_LIMIT_MAP`、`LOADED_UIS`、`GLOBAL_PROVIDERS`）をクリアする。
  **統合サーバーのワールド再読込リークがこれで消える**
- `broadcastDom` に `hasDisconnected()` ガードを入れる
  （`ServerPubSubManager.publish:71` には既にある）
- ディメンション変更・ブロック破壊時の後片付け経路を追加
- `STANDALONE_SESSIONS` と `channelData` の追い出し方針を決める
- `ServerTargetSyncHandler` の tick リスナー登録を static イニシャライザ
  （`:46-48`）から明示的な初期化に移す

### 6-3. API 境界を立てる（破壊的変更 OK を活かす）

- `api` から `server`/`common`/`network` への依存（5+1+1 import）を切り、
  **`api` が実装に依存しない**ようにする。`IWebSessionProvider` が
  具象 `WebSession` を返すのをやめ、`api` 側のインターフェースに変える
  （`HtmlNode` と `UIParser.Document` の露出もここで切る）
- `@ApiStatus.Internal` を内部クラス（`WebSessionManager`・`UIParser`・
  `HtmlNode` など）に付ける。現状マーカーは全 62 ファイルで 0 件
- 画面を開く正式な入口（`EnderFrameAPI` 相当）を用意する。
  現状 `EnderFrameScriptingBridge`（KubeJS 向け static ファサード）しかない
- `AbstractWebScreen`/`AbstractWebContainerScreen` が実質の主要下流 API
  である事実を整理する（`api` へ移すか、少なくとも Javadoc を付ける）
- `onGlobalServerAction` が `getOrCreateStandaloneSession` を呼んで
  **クライアント要求でセッションを生成する**（`:108`）点を見直す
- 公開 API の Javadoc を英語（または日英併記）にする。
  コメント言語の規約を `CONTRIBUTING` に明文化する

### 6-4. 設定システムの決着（J）

- **方針を決める**: NeoForge `ModConfigSpec` に寄せるか、自前を維持するか。
  自前の動機（HTML として設定 UI を生成できる `generateStandardHtml5Config`）は
  正当なので、**`ModConfigSpec` を裏に置いて HTML 生成だけ自前**にするのが
  落としどころ
- 自前を維持するなら最低限: 設定値フィールドを `volatile` にするか
  アクセサ経由にする（現在リフレクション書き込み + 別スレッド直読みで
  happens-before 保証が無い）、`register()` の read-modify-write を同期化、
  `setAccessible` の重複呼び出し（`:51,:70,:129`）を削除、
  登録時にリスナーが 2 回発火する問題（`:269`/`:306`）を直す、
  `load()` の型エラー握り潰し（`:122`）に警告を出す
- **クライアント/サーバーの区別**が無いことを、せめて Javadoc で明示する
  （下流 Mod がゲームプレイ設定を登録すると黙ってクライアントローカルになる）
- `EnderConfigManager.generateStandardHtml5Config()`（108 行）を分割

---

## フェーズ 7: プロジェクト衛生（独立に即着手可）

- **`.gitignore` を直し、誤コミットを追跡から外す**:
  `run/`, `.idea/`, `repo/`, `src/generated/`, `*.log` を追加。
  `git rm --cached` で `run/logs/*`（`debug.log` 96 KB 含む）、
  `run/config/*`, `run/downloads/*`, `.idea/*` 6 ファイルを外す。
  コミット済みログに開発機の絶対パスが入っている
- **README を書く**: 現在 NeoForge MDK テンプレートそのまま。
  概要・導入（Maven 座標）・最小の使用例・対応 HTML/CSS 機能一覧・
  対応していないもの・ライセンス
- **LICENSE を置く**: `gradle.properties` は LGPL-3.0 を宣言しているのに
  本文が無い（LGPL-3.0 は告知を要求）。`TEMPLATE_LICENSE.txt` は
  MDK テンプレート用の MIG で別物。`lunex_video.dll` と
  実行時取得する `yt-dlp` の帰属表記も必要
- **CI を入れる**: `.github/workflows/build.yml` で `gradlew build test`。
  フェーズ 1-2 の「`core/` 純粋性テスト」をここで強制する
- **ゲームテストを使う**: `build.gradle` は 3 run 設定で
  `enabledGameTestNamespaces` を有効化済みなのに中身が 0。
  画面を開いて描画が通ることを確認する最小のゲームテストを 1 本置く
- **DevKit の二重化を解消**（K）: `assets/enderframelib/preview/` の
  `enderframe-devkit.js`（19 KB、機能が多い方）と `.css` が
  **どこからも読まれていない**一方、`WebDevKitExporter` が
  機能の少ない別版を Java 文字列から生成している。
  エクスポータがリソースを読むようにする（コミット `aed2386 拡張機能のSSOT化` の続き）
- **バージョン/座標の不整合**: `mod_group_id=com.nishiyu.enderframe` と
  パッケージ `com.nishiyu.enderframelib`、
  `mod_version=1.0.3` と `.versioned("1.0.0")` ハードコード
- `EnderFrameNetwork.registerPayloads` の死んだ `@SubscribeEvent` を削除
- `ServerImageRegistry` を `client/` に移して改名（実質クライアント専用）
- `build.gradle` の空 `repositories { }` と
  `org.gradle.configuration-cache=true`（ModDevGradle と相性が悪い）を確認
- `TODO`/`FIXME` が 0 件 ＝ 既知の課題がコードに記録されていない。
  既知の制約（Markdown のネストリスト未対応、`!important` 未対応など）を
  コードかドキュメントに残す規約にする

---

# 検証方法

### フェーズ 1 の検証（ここが最重要）

```bash
./gradlew build test
```

1. **`core/` 純粋性テストが通ること** — `core/**` に
   `net.minecraft` / `net.neoforged` / `client` / `common` / `server` の
   import が無い
2. **特性テストが通ること** — フェーズ 1-3 で固定したゴールデン出力が
   フェーズ 2〜4 の型入れ替え後も一致する。
   意図的に直したバグ（A-8 の `@media` AND 化など）だけが差分として現れ、
   その差分ごとにテストを更新したコミットが対応している
3. JUnit のみで `core/` のテストが走る（Minecraft クラスパス不要）

> この環境では Maven Central / `maven.neoforged.net` がプロキシに
> 阻まれている（`429` / `CONNECT tunnel failed 403`）ため、
> **ビルド検証はローカルまたは CI で行う必要がある。**

### フェーズ 3〜5 の検証（実機）

同梱のテストページがそのまま回帰テストとして使える:

- `src/main/resources/data/enderframelib/enderframe_ui/showcase.html`（162 行）—
  flex・grid・`linear-gradient`・CSS 変数・`mc-entity`・`mc-bar`・
  `{{@fps}}` などの env 変数・PubSub・SDF 角丸クリップ・`overflow:auto` を網羅
- `hud_test.html`（64 行）— HUD 経路と `test_channel` の PubSub
- `guidebook.md`（40 行）— Markdown 経路

```
# クライアント起動
./gradlew runClient
# ゲーム内で
/enderframe ...   （EnderFrameCommands:42-182 の register() で定義）
```

確認項目:
1. `showcase.html` がフェーズ前後で**視覚的に同一**であること
   （スクリーンショット比較）
2. **B-3 の修正確認**: `overflow:auto` のボックスがある画面で、
   何も操作していないときに毎フレームのフルリレイアウトが走らないこと。
   リレイアウト回数をログに出して before/after を比較する
3. **A-2 の修正確認**: `:hover` ブロックに `border-color` など
   「5 キー以外」のプロパティを書いて効くこと
   （UA シート自身の `.mc-slot:hover` が効くようになる）
4. **A-7 の修正確認**: `<a>` に下線が出ること、`<s>`/`<del>` に
   打ち消し線が出ること
5. **A-8 の修正確認**: `@media (min-width:400px) and (max-width:800px)` が
   範囲外でマッチしないこと（画面幅を変えて確認）
6. HUD・canvas・動画（Windows）・`mc-slot` のドラッグ＆ドロップが退行しないこと
7. 非 Windows で動画が**明示的に**無効化され、クラッシュも無言の no-op も
   起こさないこと

フェーズ 5 固有の確認項目:

8. **D-4 の 5 バグ修正確認**（コンテナ画面）: `AbstractWebContainerScreen` を
   継承した画面で、`onmouseenter`/`onmouseleave` が発火すること、
   `onInputChanged` がサブクラスに届くこと、`playAudio` の挙動が
   通常画面と一致すること
9. **D-9 のクリップバグ修正確認**: `overflow:hidden` のコンテナで
   スクロールアウトしたボタンが**クリックできなくなっている**こと
   （現在はできてしまう）
10. **D-7 の修正確認**: GUI 画面とブロック画面を同時に見える状態にして、
    一方の `<input>` にフォーカスしても他方のフォーカス表示が
    変わらないこと（現在は `activeInputId` が global なので共有される）
11. **D-14 の修正確認**: `{{@fps}}` を含む UI を開き、
    レイアウト回数が毎秒 10 回でなくなっていること
12. **D-8 のリーク修正確認**: `mc-entity` を含む画面を開く →
    ワールドを抜ける → ヒープダンプで `ClientLevel` が解放されていること。
    リモート画像を多数読む画面で `IMG_CACHE` のテクスチャ数が
    上限を超えないこと
13. **D-6（SDF）の効果測定**: 角丸要素が多い画面（`showcase.html`）で
    フレーム時間とドローコール数を before/after で比較する
14. `/enderframe devkit` で出力される DevKit が、
    `assets/enderframelib/preview/` の機能の多い版と一致すること（K）

### フェーズ 6 の検証

- **I-6 のリーク修正**: 統合サーバーでワールドに入る → メインメニューに戻る →
  別ワールドに入る、を数回繰り返してヒープダンプを取り、
  `ServerPlayer` / `ServerLevel` / `WebSession` が残っていないこと
- **I-1〜I-4 のセキュリティ修正**: 不正なパケット
  （他プレイヤーのセッション ID、`trackerNbtPaths=["Inventory"]`、
  範囲外の `block:x,y,z`、推測したチャンネル名）を送るテストクライアントか
  ゲームテストを書き、**拒否されることを検証**する。
  修正だけ入れて検証を書かないと再発する
- ゲームテスト（`gameTestServer` run 設定が既にある）で
  セッション生成 → 同期 → ログアウト → マップが空になることを確認

### フェーズ 7 の検証

```bash
git ls-files run/ .idea/        # 空になること
du -sh .git                    # DLL 分は履歴に残る点は認識しておく
./gradlew build                 # CI と同じ経路
```

---

# 進め方の提案

フェーズ 1 は**他のすべての前提**なので最初に完了させる。
対象はわずか 3 箇所の依存反転＋1 ファイル移動＋テスト基盤で、
ここを越えると `core/`（約 4,400 行）が素の JUnit で検証可能になり、
一括再設計の安全網が手に入る。

フェーズ 2〜4（`core/` の一括再構築）は 1 本の作業として連続して進める。
フェーズ 2-1 の `ComputedStyle` 導入がフェーズ 3 と 4 の前提になるため、
この 3 つは分割しても同じブランチ上で続ける。

フェーズ 5 は 5-1（`UIElement` 境界の型化）が他の前提。
`ClipRect`/`Corners`/`PseudoStates` が入ると
約 12 引数が 9 シグネチャから機械的に消え、
god クラスの分割（5-2）と 6 ホストの一本化（5-3）が安全にできるようになる。
フェーズ 2-2 の疑似クラス状態の型化と 5-1 の `PseudoStates` は
同じ変更の両端なので、続けて実施するのが効率的。

一方、**5-6（リソースリーク）・5-10（SDF バッチ化）・5-11（ログ追加）・
5-12 の一部（ファイル移動、死んだコード削除）は前後関係が無い**ので、
いつでも単独で入れられる。特に 5-11 のログ追加は
以後のすべての作業のデバッグ効率に効くので、早めに入れる価値がある。

**フェーズ 6-1（セキュリティ）と 6-2（リーク）、フェーズ 7（衛生）は
基盤再構築と独立しているので、並行して、あるいは先に着手できる。**
特に I-1（任意 NBT 読み出し）と I-6（`ServerPlayer` リーク）は
基盤再構築を待つ理由がない。
