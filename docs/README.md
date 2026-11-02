# docs 目次

| 文書 | 言語 | 内容 |
|:---|:---:|:---|
| [getting-started.md](getting-started.md) | EN | 導入（Maven・最初の UI） |
| [client-and-hybrid.md](client-and-hybrid.md) | EN | クライアント駆動 UI・HUD・ハイブリッド実行モデル |
| [headless-state.md](headless-state.md) | EN | 機械 UI・ワールド内の面を Headless State で作る |
| [commands-and-devkit.md](commands-and-devkit.md) | EN | `/enderframe` コマンドの要点とブラウザプレビュー（DevKit。全コマンドは [COMMAND_GUIDE.md](../COMMAND_GUIDE.md)） |
| [migration.md](migration.md) | EN | 移行メモ・破壊的変更 |
| [standalone-viewer.md](standalone-viewer.md) | JA | Minecraft なしで HTML を窓／PNG に出す（`:core`） |
| [build-and-run-commands.md](build-and-run-commands.md) | JA | ビルド・テスト・ビューア起動の Gradle コマンド集 |

## 読む順の目安

- **Mod から使う**: getting-started → headless-state（固定画面）／ client-and-hybrid（クライアント駆動）→ commands-and-devkit
- **バージョンを上げる**: migration
- **単体の Java アプリで使う（Minecraft 無し）**: standalone-viewer → build-and-run-commands

## ルートの文書

| 文書 | 内容 |
|:---|:---|
| [../README.md](../README.md) | 全体の説明・API |
| [../ARCHITECTURE.md](../ARCHITECTURE.md) | 構造・層の責務・`ArchitectureTest` の上限 |
| [../STANDALONE_RENDERING.md](../STANDALONE_RENDERING.md) | スタンドアロン描画化の設計と進捗（Step 1〜5） |
| [../FIDELITY.md](../FIDELITY.md) | ブラウザとの差異の台帳 |
| [../BENCHMARK.md](../BENCHMARK.md) | 性能の基準値と実測 |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | 規約（言語・コメント・テスト） |
| [../REFACTORING_PLAN.md](../REFACTORING_PLAN.md) | リファクタリング計画 |
| [../COMMAND_GUIDE.md](../COMMAND_GUIDE.md) | `/enderframe`（`/ef`）コマンドとデータパック連携の全ガイド |
| [../TEST_SPEC_BLOCKS_ITEMS.md](../TEST_SPEC_BLOCKS_ITEMS.md) | ブロック・アイテム描画の実機テスト仕様 |
| [../TEST_SPEC_MEDIA.md](../TEST_SPEC_MEDIA.md) | 動画・音声・画像の実機テスト仕様 |
| [../TEST_SPEC_SURFACE.md](../TEST_SPEC_SURFACE.md) | ブロック／アイテム表面への Web 画面の実機確認手順 |
| [../REVIEW.md](../REVIEW.md) | コードレビュー（現状評価と課題） |
