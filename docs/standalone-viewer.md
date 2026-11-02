# スタンドアロン実行（Minecraft なしで HTML を表示する）

`:core` モジュールは Minecraft / NeoForge に依存しません。HTML/CSS を、GLFW の窓（OpenGL）か PNG に出せます。
設計の背景は [STANDALONE_RENDERING.md](../STANDALONE_RENDERING.md)、Gradle コマンドの一覧は [build-and-run-commands.md](build-and-run-commands.md) を参照してください。

## 必要なもの

- JDK 21
- 窓で表示するとき: OpenGL 3.2 core が使える環境（ふつうのデスクトップ）。PNG 出力だけなら GPU も窓も要りません

## 1. Gradle で動かす（いちばん簡単）

リポジトリ直下で実行します。LWJGL のネイティブは `:core:runViewer` が自動で足します。

```bash
# 窓で表示（幅 高さ は省略可。既定 1024×768）
./gradlew :core:runViewer --args="src/main/resources/data/enderframelib/enderframe_ui/voidwave.html 1280 800"

# 自分の HTML / Web 上のページ
./gradlew :core:runViewer --args="C:/path/to/page.html 1280 800"
./gradlew :core:runViewer --args="https://example.com/ 1024 768"

# 窓なし（CPU 描画）で PNG に保存。--after はアニメーションが進んだあとの見た目（ms）
./gradlew :core:runViewer --args="page.html 1280 800 --png out.png --after 5000"
```

オフライン環境では `--offline` を付けます。

### 窓でできること

| 操作 | 動作 |
|:---|:---|
| マウスを乗せる | `:hover` と `transition` |
| 押す／離す | `:active`、離すと `click`（`<details>` の開閉・チェックボックス・`<label>`・`#id` へのアンカー移動） |
| ホイール | カーソルの下のスクロール箱を、減速しながらスクロール |
| クリック／`Tab` | クリックしたフォーカスできる要素（`input` `textarea` `button` `a[href]` `tabindex`）にフォーカス。`Tab` / `Shift+Tab` で文書順に移動し、`:focus` が効く |
| 文字・`Backspace` / `Delete` | フォーカス中のテキスト欄のキャレット位置に入力・削除（`beforeinput` / `input` / `change`）。`Enter` で `change` とフォーム送信 |
| 矢印・`Home` / `End`・`Shift` | キャレット移動・範囲選択。`Ctrl` / `Alt` で単語単位。`Ctrl+A` / `C` / `X` / `V` |
| ダブル／トリプルクリック | テキスト欄で単語／行（1 行欄は全体）を選択 |
| `<select>` | クリック・`Enter` / `Space` / `↑↓` で開き、選ぶと `input` / `change` |
| `Enter` / `Space` | ボタン・リンク（`Enter`）、チェックボックス・ラジオ（`Space`）を押す |
| range をドラッグ | つまみが動き `input` / `change` が飛ぶ |
| 窓を閉じる | 終了 |

`@keyframes` と `transition` は動きます。変化が無いあいだは描き直さないので、止まっている画面は CPU / GPU を使いません。

### まだできないこと

- 長い値でのキャレット追従スクロール、IME の変換中表示
- GL 窓のリサイズへの追従（固定サイズを拡縮して表示。`StandaloneView.resize` はあるが、バックエンドの作り直しは呼び出し側）
- WOFF / WOFF2 の `@font-face`（TTF / OTF は使える）、窓での音声
- `http` 先の外部 CSS の読み込み、`<a href>` による別ページへの遷移（`#id` だけ動く）
- 取得した画像の固有サイズのレイアウト反映（画像には `width` / `height` を書く）
- Minecraft 専用の要素（`<mc-item>` `<mc-entity>` `<mc-scene>` と `item:` `entity:` `player:` の画像）。これらは Mod 側でだけ描かれます

## 2. jar とクラスパスで動かす

```bash
./gradlew :core:jar        # core/build/libs/enderframelib-core-<version>.jar
```

jar には LWJGL のネイティブが含まれません。利用側が追加してください
（`lwjgl` / `lwjgl-glfw` / `lwjgl-opengl` / `lwjgl-freetype` の `3.3.3`、OS に合う `natives-*`）。
そのほかの依存は `slf4j-api`・`fastutil`・`annotations` です。

```bash
java -cp "enderframelib-core.jar:lwjgl-*.jar:..." \
     com.nishiyu.enderframelib.common.StandaloneViewer page.html 1280 800
```

Windows ではクラスパスの区切りは `;` です。

## 3. 自分のプログラムから使う

### 画像にする（窓も GPU も不要）

```java
SoftwareRenderBackend img = SoftwareRendering.renderHtml(html, 800, 600, System.currentTimeMillis());
img.writePng(Path.of("out.png"));
```

### GPU（OpenGL）で描く

```java
try (LwjglGlRenderBackend gl = new LwjglGlRenderBackend(1280, 800, 1f)) {   // 見えないウィンドウ
    SoftwareRendering.renderHtml(html, 1280, 800, System.currentTimeMillis(), gl);
    gl.writePng(Path.of("out.png"));
}
```

見えるウィンドウは `new LwjglGlRenderBackend(w, h, 1f, true, "title")` で作り、描いたあとに `gl.present()` で映します。

### 動くページにする（ホバー・スクロール・クリック・キー・タイマー）

`StandaloneView` が `UiView`（文書・状態・タイマー）と `UiInput`（クリック合成・フォーム）の上でフレームを進めます。入力（GLFW・Swing など）は座標（CSS px）とキーコード（GLFW）を渡すだけです。

```java
StandaloneView view = new StandaloneView(html, 1280, 800);
try (LwjglGlRenderBackend gl = new LwjglGlRenderBackend(1280, 800, 1f, true, "demo")) {
    String session = gl.sessionId();
    // 入力: view.mouseMove(x, y, session) / view.mouseButton(down, session) / view.scroll(dx, dy, session)
    //       view.keyDown(key, mods) / view.keyUp(key, mods) / view.charTyped(codePoint)
    // タイマー: view.timers().setTimeout(...) / requestAnimationFrame(...)（frame のたびに実行）
    while (!gl.shouldClose()) {
        gl.pollEvents();
        if (view.frame(System.currentTimeMillis(), gl, false)) gl.present();   // 変化が無ければ false
    }
}
```

`view.needsFrame()` が false のあいだは描き直さなくてよいので、スリープして構いません。
`ImageProvider.web()` を `gl.images(...)` に渡すと `http(s)` の画像を背景で取得します（LAN・ループバックは拒否、20MB まで）。
取得が終わったら `view.invalidate()` で描き直してください（`StandaloneViewer` は `fetcher().completedCount()` の変化で行っています）。

## 4. Chromium との見た目の比較

```bash
# Chromium（Edge）
msedge --headless --disable-gpu --hide-scrollbars --window-size=1280,800 --virtual-time-budget=5000 --screenshot=edge.png file:///C:/path/page.html
# Core
./gradlew :core:runViewer --args="page.html 1280 800 --png core.png --after 5000"
```

`--virtual-time-budget` と `--after` を同じ時間にそろえると、フェードインなどのアニメーションが終わったあとの絵を比べられます。
無限に回るアニメーションは位相が違うので、その部分の差は無視してください。

## 5. トラブルシュート

| 症状 | 原因と対処 |
|:---|:---|
| 窓が出ずに `GLFW` の例外 | OpenGL 3.2 core が使えない環境（リモートデスクトップ・VM など）。`--png` で CPU 描画を使う |
| 文字が出ない | OS のフォントが見つからない。`font-family` に OS にあるフォントを指定する |
| 画像が出ない | `http(s)` は取得に少し時間がかかる。LAN の URL は拒否される。`width` / `height` を指定する |
| スクロールが重い | 項目が密なページの先頭付近で 1 フレーム 50〜70ms 程度（約 15〜20fps）。画面外の項目は省いているが、画面内の項目が多いほど重い |
