# docs 目次

エンジン（`:core` 系）の文書はこのディレクトリ、Minecraft の MOD（`:mod`）の文書は [../mod/docs/](../mod/README.md) にある。

## 使う人向け

| 文書 | 言語 | 内容 |
|:---|:---:|:---|
| [../core/README.md](../core/README.md) | EN | 単体エンジン（`:core` / `:core-gl` / `:core-vulkan` / `:core-glfw`）の導入・Gradle の設定・コンパイルできるコード例 |
| [standalone-viewer.md](standalone-viewer.md) | JA | Minecraft なしで HTML を窓／PNG に出す |
| [build-and-run-commands.md](build-and-run-commands.md) | JA | ビルド・テスト・ビューア・デモ・公開の Gradle コマンド集 |
| [migration.md](migration.md) | EN | 移行メモ・破壊的変更 |

## 開発する人向け

| 文書 | 内容 |
|:---|:---|
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | 規約（言語・コメント・テスト・ゴールデンの更新） |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 構造・層の責務・アーキテクチャのラチェット |
| [BENCHMARK.md](BENCHMARK.md) | 性能の基準値と実測・計り方 |
| [FIDELITY.md](FIDELITY.md) | ブラウザとの差異の台帳（`WebFidelityTest` が読む） |
| [plan/ENGINE_REARCHITECTURE_PLAN.md](plan/ENGINE_REARCHITECTURE_PLAN.md) | **現行の計画書**（Engine v3。§0 現在地・§3 優先順位表・§4 KPI・§5 運用ルール） |
| [plan/FEATURE_PLAN.md](plan/ENGINE_FEATURE_PLAN.md) | **機能拡張の計画**（独立トラック P1〜P6: 起動・並列化・脱 LWJGL と純 Java のホスト・OS のメディア・リポジトリの分割・汎用ツールキット化・Three.js 本家レベルの 3D。§0 現在地・§2 優先順位表・§4 KPI） |
| [plan/ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md](plan/ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md) | 旧計画の経緯と計測・持ち越す知見（付録 5.A）。`plan/archive/` の索引を兼ねる |

`plan/archive/` は過去の計画の原文（読み物であり、指示ではない）。MOD 時代の旧計画 `REFACTORING_PLAN.md` も
`plan/archive/00_refactoring_plan_mod_era.md` として残してある。原文のまま保存しているので、中のリンクは当時のパスを指す。

## 読む順の目安

- **単体の Java アプリ・ゲームエンジンで使う（Minecraft なし）**: [../core/README.md](../core/README.md) → [standalone-viewer.md](standalone-viewer.md) → [build-and-run-commands.md](build-and-run-commands.md)
- **Minecraft の MOD（NeoForge）から使う**: [../mod/README.md](../mod/README.md)（MOD の文書の目次）
- **エンジンに手を入れる**: [../CONTRIBUTING.md](../CONTRIBUTING.md) → [plan/ENGINE_REARCHITECTURE_PLAN.md](plan/ENGINE_REARCHITECTURE_PLAN.md) の §0 → [ARCHITECTURE.md](ARCHITECTURE.md)

## リポジトリの構成

```text
README.md  CONTRIBUTING.md  LICENSE  NOTICE   入口・規約・ライセンス
core/         (:core)        エンジン本体（Minecraft・OpenGL・Vulkan に依存しない）
core-gl/      (:core-gl)     OpenGL 3.2 バックエンド
core-vulkan/  (:core-vulkan) Vulkan 1.1 バックエンド
core-glfw/    (:core-glfw)   GLFW の窓と StandaloneViewer
mod/          (:mod)         NeoForge 1.21.1 の MOD（エンジンの利用者の 1 つ）。文書は mod/docs/
docs/                        エンジンの文書（このディレクトリ）。計画書は docs/plan/
scripts/                     CI・計測・コード生成の補助（ci-linux.ps1・ab-gate.ps1・GenStyleGroups.java ほか）
gradle/                      wrapper と、モジュール間で共有する適用スクリプト
build/repo/                  公開物の出力先（./gradlew :core:verifyPublishedModules ほか。追跡しない）
```