# ビルド・テスト・実行コマンド集

> 目次: [README.md](README.md)

リポジトリ直下（`enderframelib-1.21.1/`）で実行します。Windows では `./gradlew` を `gradlew.bat` に読み替えても構いません。
オフラインでは各コマンドに `--offline` を付けます。

## テスト

```bash
# 全テスト（core + ルート。約 7 分）
./gradlew test

# core だけ（Minecraft 非依存の単体テスト）
./gradlew :core:test

# クラス名・パターンで絞る
./gradlew :core:test --tests '*StandaloneMultiClickTest'
./gradlew :core:test --tests '*StandaloneEditingTest' --tests '*StandaloneInteractionTest'

# 1 メソッドだけ
./gradlew :core:test --tests 'com.nishiyu.enderframelib.core.paint.software.StandaloneMultiClickTest.wordAndLineRanges'
```

テスト結果の XML は `core/build/test-results/test/` と `build/test-results/test/`、HTML レポートは `build/reports/tests/` に出ます。
GL を作れない環境では `LwjglGlRenderBackendTest` などはスキップされます。

## スタンドアロンビューア（Minecraft なしで HTML を表示）

```bash
# GLFW の窓で表示（幅 高さ は省略可。既定 1024×768）
./gradlew :core:runViewer --args="path/to/page.html 1280 800"

# Web 上のページ
./gradlew :core:runViewer --args="https://example.com/ 1024 768"

# 窓なし（CPU 描画）で PNG に保存。--after はアニメーションが進んだあとの見た目（ms）
./gradlew :core:runViewer --args="page.html 1280 800 --png out.png --after 5000"

# リポジトリ同梱のサンプル
./gradlew :core:runViewer --args="src/main/resources/data/enderframelib/enderframe_ui/voidwave.html 1280 800"
```

## テストサイト（ライブラリだけで動くデモ）

表は多彩な HTML / CSS アニメーション、裏は Java が DOM を操作します（`core/demo/`）。
Java 側は時計・カウンタ・進捗バー・ログの追加と削除・表の書き換え・フォームのイベント・1 593 セルのグリッドの波を動かします。

```bash
# 窓で表示（ホイールでスクロール。全面が白・黒・赤に変わる区画あり）
./gradlew :core:runShowcase

# 窓なしで PNG（先頭に 幅 高さ を付けると縦長に全体を撮れる）
./gradlew :core:runShowcase --args="1280 7600 --png showcase.png --after 3000"
```

窓での操作の全体は [standalone-viewer.md](standalone-viewer.md) を参照。主な操作:

| 操作 | 動作 |
|:---|:---|
| ダブルクリック（テキスト欄） | 単語を選択 |
| トリプルクリック | 1 行欄は全体、textarea は行を選択 |
| `Tab` / `Shift+Tab` | フォーカス移動 |
| `Ctrl+A` / `C` / `X` / `V` | 全選択・コピー・切り取り・貼り付け |
| ホイール | スクロール |
| 窓を閉じる | 終了 |

## ビルド・配布

```bash
# core 単体ライブラリの jar（core/build/libs/enderframelib-core-<version>.jar）
./gradlew :core:jar

# core を repo/ へ公開（ローカル Maven 形式）
./gradlew :core:publish

# Mod 本体（core のクラスを同梱した jar）
./gradlew build
```

## Minecraft（NeoForge）

```bash
# 開発クライアントを起動
./gradlew runClient

# 開発サーバーを起動
./gradlew runServer
```

## 便利なもの

```bash
# タスク一覧
./gradlew tasks --all

# キャッシュを使わず作り直す
./gradlew clean test --rerun-tasks

# 変更の確認（git）
git status
git diff --stat
```
