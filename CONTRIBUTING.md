# EnderFrameLib への貢献

## 開発環境

- JDK 21（`java.toolchain` で固定。別バージョンが入っていても Gradle が取得する）
- Minecraft 1.21.1 / NeoForge 21.1.248 / ModDevGradle 2.0.148

```bash
./gradlew build test          # CI と同じ経路
./gradlew runClient
./gradlew runServer
./gradlew runGameTestServer   # サーバー側ライフサイクルのゲームテスト
```

`run/`・`.idea/`・`repo/`・`src/generated/` は追跡しない。
生成物やワールドデータをコミットに混ぜないこと。

---

## 言語の規約

| 対象 | 言語 |
|---|---|
| 識別子（クラス・メソッド・フィールド・定数） | **英語** |
| 公開 API（`api/` パッケージ）の Javadoc | **英語** |
| 内部実装のコメント・Javadoc | **日本語**（既存コードに合わせる） |
| `README.md`・`NOTICE`・公開されるドキュメント | **英語** |
| `CONTRIBUTING.md`・`REFACTORING_PLAN.md` などの開発ドキュメント | **日本語** |
| コミットメッセージ | 件名は英語（Conventional Commits）、本文は日本語でよい |

`api/` を英語にしているのは、このライブラリに対してコンパイルする他 Mod の
作者が最初に読む場所だから。内部実装は日本語のままで構わないが、
**同じファイルの中で言語を混ぜない**こと。

既存ファイルを触るときは、そのファイルの言語に合わせる。
言語の置き換えだけを目的とした差分は作らない（レビューの邪魔になる）。

---

## コメントの書き方

実装を言い直すコメントは書かない。書くのは次の 3 種類だけ。

1. **なぜそうなっているか** — 自明でない判断の理由。
2. **何が壊れていたか** — 修正したバグと、素直な書き方に戻すと再発する理由。
   既存コードにこの形のコメントが多いのは意図的で、リファクタリングで
   取り除いた落とし穴の記録になっている。
3. **既知の制約** — 下記の `LIMITATION` 規約。

---

## 既知の制約の記録（`TODO` / `FIXME` / `LIMITATION`）

リファクタリング前のこのリポジトリには `TODO` も `FIXME` も 1 件も無かった。
バグが無かったからではなく、**既知の課題がコードに書かれていなかった**だけで、
「この CSS が効かないのは未実装なのかバグなのか」が読んでも分からなかった。

以後は次の 3 つのマーカーを使う。

```java
// TODO(課題): これから実装する予定のもの。何をするかを書く。
// FIXME(不具合): 既知の壊れている挙動。直し方が分かっているか、せめて再現条件を書く。
// LIMITATION(制約): 直す予定のない仕様上の制限。README の「Not supported」と対応させる。
```

規約:

- **ユーザーから見える制約は `README.md` の "Not supported" に必ず載せる。**
  コードの `LIMITATION` だけで済ませない。README はライブラリの利用者が
  見る唯一の場所である。
- `LIMITATION` を新しく書いたら、同じコミットで README を更新する。
- `TODO` / `FIXME` には必ず内容を書く。裸の `// TODO` は置かない。
- 制約を解消したら、コードのマーカーと README の記述を同じコミットで消す。

現在 README に記録されている主な制約（コード側と対応させること）:
JavaScript 非対応、Markdown のネストリスト非対応、表レイアウトが最小限（ua.css の表は flex 行）、
`float` / 段組み非対応、ボックスツリーの未対応部分（インライン中のブロックで分割しない・
flex / grid 内のテキストを匿名アイテムで包まない）、`::marker` を照合しない、
flex / grid / 位置指定の未実装機能、スタッキングコンテキストが無い、
`vertical-align` / `text-indent` 非対応、UA が HTML 標準の既定値でない、
`@media` の `prefers-*` は固定値（light / 動きの抑制なし）、
`::before` / `::after` 以外の疑似要素は照合しない、動画は Windows 専用。
忠実度コーパスで測れる差は FIDELITY.md の「既知の差異」がフェーズ別に持つ。

---

## 層の規約

```
core/  →  （何にも依存しない）
api/   →  Minecraft のみ
common/, server/, client/, network/  →  core/, api/ とその層
```

**`core/` は純 Java のリーフである。** `net.minecraft` / `net.neoforged` /
`com.mojang` / 上位パッケージを import してはならない（完全修飾名での参照も禁止）。
これは `ArchitectureTest` が CI で強制する。逃げ道を作るのではなく、
依存を反転させるか、値型を `core/model` に置いて境界で変換する。

`api/` パッケージは公開面である。ここに内部型を漏らさない。
`api/` 以外の public 型には `@ApiStatus.Internal` を付ける。
`api/` の破壊的変更は、`@ApiStatus.AvailableSince` の表記と合わせて扱う。

### ファイルの分け方

**1 つのクラスからしか使われない小さな型は、新しいファイルにせずそのクラスの入れ子にする。**
record・enum・コールバック用インターフェース・数十行の補助クラスは、持ち主の末尾に
`public static` の入れ子型として置く（例: `LayoutContext.Viewport`、`StyleSheet.StyleRule`、
`UIElement.ElementType`、`HtmlParser.HtmlTokenizer`）。参照側は入れ子型を直接 import する。
新しいトップレベルのファイルを作るのは、複数の層から使われる中心的な型か、
数百行規模で独立した責務を持つものに限る。

ただし `AbstractWebScreen` / `AbstractWebContainerScreen`（と、それらが継承する
`WebScreenDelegate`）の public シグネチャに現れる型は下流 Mod から参照されるので、
入れ子に移したりパッケージを変えたりしないこと。

---

## テスト

```
src/test/java/.../core/        core/ の単体テスト・ゴールデンテスト
src/test/java/.../api/         API 境界テスト（内部型が漏れていないか）
src/test/resources/golden/     期待出力
src/test/resources/fidelity/   ブラウザ実測の期待値と既知差分の台帳
```

- `core/` に手を入れる変更には、素の JUnit テストを付ける。
  Minecraft の起動が要らないので、ここが唯一安く速く検証できる層。
- パーサ・レイアウトの挙動はゴールデンファイルで固定している。
  意図して出力を変えたときは:

  ```bash
  ./gradlew test -Dgolden.update=true
  ```

  **更新後は必ず `git diff` を目視し、変わるべき差分だけが出ていることを
  確認してからコミットする。** 更新モードで通しただけの差分は受け取らない。
- **Web 挙動との差はゴールデンではなくブラウザ実測で固定する。**
  `WebFidelityTest` が忠実度コーパス
  （`src/main/resources/assets/enderframelib/preview/fidelity/cases/`）を
  エンジンに通し、ブラウザで測った期待値
  （`src/test/resources/fidelity/expected/`）と突き合わせる。
  幾何（x / y / w / h）は ±0.5px、描画順の列（z）は完全一致。列の定義と
  コーパスを書くときの約束は [FIDELITY.md](FIDELITY.md) の「仕組み」にある。

  ```bash
  ./gradlew test --tests '*WebFidelityTest'
  ./gradlew test --tests '*WebFidelityTest' -Dfidelity.dump=true   # 現状の出力を build/fidelity へ
  ```

  ブラウザ側の期待値は `/enderframe devkit` が出す `fidelity-dump.html` で採る。
  ヘッドレスでも採れる（`?auto=1` で自動実行し、結果は `<pre id="efl_result">` に入る）:

  ```bash
  chrome --headless --disable-gpu --hide-scrollbars --virtual-time-budget=8000     --dump-dom "file:///<gameDir>/enderframe_devkit/fidelity-dump.html?auto=1"
  ```

  Chrome の代わりに Edge（`msedge.exe`）でも同じ引数で採れる。
  ケースを足すときは `cases.txt` の該当領域の節に名前を書き、期待値ファイルを
  ブラウザの出力から作る（エンジンの出力 `build/fidelity` を期待値にしてはいけない）。

  一致しない差異は `src/test/resources/fidelity/known-divergences.txt` と
  [FIDELITY.md](FIDELITY.md) の**両方**に同じキー（`case#id.field`）で書く
  （前者に書いて後者に書き忘れるとテストが落ちる）。
  **台帳に無い差異は回帰として落ち、台帳にあるのに一致した差異も「台帳が古い」として
  落ちる。** 差異を 1 つ埋めたら、同じコミットで台帳の行・コードの `LIMITATION`・
  README の "Not supported" を揃えて消す。
- **UA の既定値は `ua.css` が唯一の出所。** ブラウザプレビューは
  `ua.css` と組み込みテーマをそのまま同梱して読む。
  `enderframe-devkit.css` に要素セレクタを書かないこと（`CssResourcesTest` が検査する）。
- **テキストの測定は `core/text` が唯一の出所。** 文字幅・ascent・descent・行間は
  `FontFace`（`font-family` の引き当ては `FontFace.FontResolver` / `WebFontManager.McFontResolver`）、
  折り返し・空白の畳み方・省略は `TextShaper` だけが決める。
  行送りは `LayoutMetrics.lineHeightPx`、`line-height` の解析はそこだけ。
  **描画側（`WebRenderCore`）でテキストを測り直さないこと。**
  `font.width` / `plainSubstrByWidth` でレイアウトの決定をやり直すと、
  予約した行数と見える行数が食い違う（F2 以前がその状態だった）。
  フォントの倍率は `fontSizePx / FontFace.BitmapFontFace.MC_UNITS_PER_EM` で、
  描画時に丸めてはいけない（送り幅が変わって測定と合わなくなる）。
- サーバー側のライフサイクル（ログアウト・ディメンション移動・サーバー停止で
  static マップが空に戻るか）は `gametest/WebScreenGameTest` が
  `./gradlew runGameTestServer` で検証する。
  サーバー側に状態を持つマップを追加したら、`EnderFrameServerState.isEmpty()`
  と `describeState()` にも足すこと。破棄の経路は
  `EnderFrameServerState` の 1 箇所に集める。

---

## セキュリティ

このライブラリはクライアントから文字列を受け取ってサーバー側で解釈する。
次の経路を触るときは特に注意する。

- **クライアント指定のパス・ID を、そのままファイルやリソースの参照に使わない。**
  NBT パスは `NbtPathFilter`、セッション ID とアクション名は
  `WebSessionManager` の正規表現で検証している。検証を迂回する経路を作らない。
- **セッションのアクセス制御は `WebSessionManager.canAccess` に集約されている。**
  新しいパケットやコマンドを足すときは必ずここを通す。
- **PubSub のチャンネルは ACL を通る。** `EnderFrameAPI.addSubscriptionCheck`
  で登録された検査はすべて通過しなければならない。
- 外部プロセスの起動とダウンロード（`yt-dlp`）は `WebMediaManager` の 1 箇所だけ。
  増やす場合は `NOTICE` の第三者表記も更新する。

---

## コミットとプルリクエスト

- 件名は Conventional Commits（`fix:` / `feat:` / `refactor:` / `docs:` /
  `chore:`）。リファクタリング計画の項目に対応するものは
  `refactor(phase5-1): …` のようにフェーズ番号を入れる。
- 1 コミット 1 関心事。整形だけの変更を実装の変更と混ぜない。
- `./gradlew build test` が通らないものは出さない。CI も同じコマンドを走らせる。
- ライセンスは LGPL-3.0-only。貢献はこのライセンスで配布されることに
  同意したものとして扱う。
