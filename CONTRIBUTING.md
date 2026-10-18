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
JavaScript 非対応、Markdown のネストリスト非対応、表レイアウトが flex 行、
`float` / 段組み非対応、`@media` は幅のみ、`grid-template-areas` 非対応、
`::before` / `::after` 以外の疑似要素は照合しない、動画は Windows 専用。

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

---

## テスト

```
src/test/java/.../core/        core/ の単体テスト・ゴールデンテスト
src/test/java/.../api/         API 境界テスト（内部型が漏れていないか）
src/test/resources/golden/     期待出力
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
