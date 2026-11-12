# EnderFrameLib `:core` — エンジン単体配布ロードマップ & 課題管理台帳

> **策定日**: 2026-11-12  
> **対象モジュール**: `:core` (`com.nishiyu.enderframelib.core.*`, `common.*`, `api.*`)  
> **目的**: `:core` を Minecraft 依存のない「純 Java 汎用 Web/UI レンダリングエンジン」として Maven Central、GitHub Packages、スタンドアロン環境へ配布・公開するための課題整理および実装計画。

---

## 1. 配布レディネス総合判定

| 配布レベル | 判定 | 概要 |
| :--- | :---: | :--- |
| **Developer Preview / Alpha<br/>(v2.0.0-alpha / preview)** | 🟢 **即時配布可能** | ゲーム内 UI、HUD、設定画面、デスクトップツールの組込 HTML/CSS レンダラーとして十分な安定性と Chromium を凌駕する性能を持つ。1,930 件超のテスト、OSI 7 層純度ラチェットが全件通過。 |
| **General Availability (GA)<br/>(v1.0.0 / Production-Ready)** | 🟡 **本ロードマップ施策完了後** | 日本語・多言語入力（IME）、動的ウィンドウリサイズ、Maven Central 公開用メタデータ・POM 整備、および単体利用者向けドキュメントの整備を完了させてから公開。 |

---

## 2. 解決すべき課題一覧（優先度別）

### 🔴 P0: 日本語・多言語入力（IME / Preedit Composition）対応
- **背景**: 現在は GLFW キーコードによる直接文字入力（英数字・記号）のみ。日本語・中国語・韓国語のかな漢字変換やインライン未確定文字列が扱えず、デスクトップアプリや国内向けゲーム UI で利用する際の最大の障壁となっていた。
- **課題詳細**:
  1. `WebViewSurface` に W3C UI Events 準拠の IME ライフサイクル API（`compositionStart`, `compositionUpdate`, `compositionEnd`, `imeCursorRect`）が存在しない。
  2. `TextEditing` が未確定文字列（`compositionText`）および変換中カーソル位置（`compositionCursor`）の状態を持たない。
  3. `StandaloneView` の描画オーバーレイ（`textOverlay`）で、未確定文字列の下線表示（IME Preedit Underline）とキャレット追従が未実装。
  4. ホスト（GLFW、Swing、OS 固有 IME）が変換候補ウィンドウ（Candidate Window）をキャレット真下にポップアップさせるための座標取得 API がない。

### 🟡 P1: 動的ウィンドウリサイズへの追従と描画領域の同期
- **背景**: `StandaloneViewer` において、GLFW ウィンドウをユーザーがマウスドラッグ等でリサイズした際、表示内容が拡大縮小（ストレッチ）されるのみで、レイアウトが新しい幅・高さに動的に追従しなかった。
- **課題詳細**:
  1. `LwjglGlRenderBackend` にコンテキスト破棄なしで内部 FBO / テクスチャをリサイズする `resize(cssW, cssH)` メソッドが未実装。
  2. `StandaloneViewer` に GLFW の `glfwSetFramebufferSizeCallback` / `glfwSetWindowSizeCallback` が登録されておらず、ビューとバックエンドの動的リサイズが連動していなかった。
  3. リサイズ後のマウス座標とビューポートの 1:1 スケーリング追従。

### 🟡 P1: Maven Central / 単体配布パッケージング設定の整備
- **背景**: `core/build.gradle` はローカルリポジトリ（`file://${rootProject.projectDir}/repo`）への出力のみで、Maven Central や JitPack への公開に必要な要件が未充足。
- **課題詳細**:
  1. Maven Central 要求要件: POM メタデータ（名前、詳細説明、URL、ライセンス表記、開発者情報、SCM 情報）。
  2. `javadocJar` の自動生成とコンパイル・Lint の整備。
  3. 環境変数（`OSSRH_USERNAME`, `OSSRH_PASSWORD`, `MAVEN_REPO_URL` 等）によるリモートリポジトリ公開設定。
  4. LWJGL ネイティブバイナリの依存関係ポリシー明記（利用側がプラットフォーム natives を選ぶ `compileOnly` / `runtimeOnly` 分離）。

### 🟢 P2: 単体配布用ドキュメント (`core/README.md`) の作成
- **背景**: ルートの `README.md` は Minecraft Mod (NeoForge) 向けの説明が主体であり、`:core` を単独の UI ライブラリとして使う開発者向けの導入ガイドが存在しない。
- **課題詳細**:
  1. Gradle / Maven 依存関係の記述方法（Java 21、LWJGL natives の指定方法）。
  2. 数行で動く最小クイックスタート（ヘッドレス PNG 保存、カスタムホスト、GLFW 窓表示）。
  3. サポートする HTML / CSS 機能一覧と現在の制限事項（フルブラウザではなく組込 UI エンジンであることのスコープ明示）。

### ⚪ P3: 性能最適化の継続推進（ENGINE_REARCHITECTURE_PLAN 準拠）
- **詳細**:
  1. **P4: スタイル等価判定短絡 & ビューポート依存キャッシュ** (✅ 完了):
     - `StyleTable.java`: `valuesViewport` キャッシュによる 190 プロパティ走査の $O(1)$ 短絡。
     - `ComputedStyle.java`: 64-bit FNV-1a `declHash` による `sameDeclarations` / `StyleMap.equals` の $O(1)$ 足切り、`contentHash` / `sameContent` の高速化。
  2. **P3: 表示項目の相対座標化と平行移動再利用** (✅ 完了):
     - `DisplayListBuilder.java`: `isIntegerTranslation` 許容誤差導入による浮動小数点誤差起因の matchFail(4) 解消、`ShadowShape.offset` / `withShadowShape` による box-shadow 保持項目の平行移動再利用対応。
     - `UiView.java`: ウィンドウリサイズ時の `paintBuilder` 保持（`setViewport`）と `exactTranslation` による不変ボックスの表示リスト再利用。
  3. P5: `varied-list-2000` でのテキスト成形キャッシュ。
  4. P7: CPU バックエンドでの transition 中の部分再描画維持。

---

## 3. 実装計画と進捗ステータス

```text
[Phase 1] P0: IME Composition API & 描画オーバーレイの実装 ── 【完了 ✅】
 ├── 1.1 TextEditing.java: CompositionState（未確定文字列・カーソル）追加 ── ✅ 完了
 ├── 1.2 UIEvents.Composition / CoreEvent: compositionstart/update/end イベント発火 ── ✅ 完了
 ├── 1.3 StandaloneView.java: 未確定文字列の下線描画、キャレット追従、imeCursorRect 計算 ── ✅ 完了
 └── 1.4 WebViewSurface.java & EngineSurface.java: 公開 API の配備と委譲 ── ✅ 完了

[Phase 2] P1: 動的ウィンドウリサイズ追従 ── 【完了 ✅】
 ├── 2.1 LwjglGlRenderBackend.java: resize(cssW, cssH) 実装（FBO / テクスチャ更新） ── ✅ 完了
 └── 2.2 StandaloneViewer.java: glfwSetFramebufferSizeCallback 登録と座標変換同期 ── ✅ 完了

[Phase 3] P1: Maven パッケージング設定の強化 ── 【完了 ✅】
 ├── 3.1 core/build.gradle: javadocJar 追加、POM メタデータ設定 ── ✅ 完了
 └── 3.2 リモート Maven リポジトリ（OSSRH / GitHub Packages）公開設定 ── ✅ 完了

[Phase 4] P2: ドキュメント整備 & テスト検証 ── 【完了 ✅】
 ├── 4.1 core/README.md の作成（単体利用ガイド） ── ✅ 完了
 ├── 4.2 ImeCompositionTest の作成と単体テスト検証 ── ✅ 完了
 └── 4.3 全ラチェットテスト（Layer, Portability, ApiBoundary, Allocation）の再確認 ── ✅ 完了

[Phase 5] P3 & P4: 性能最適化（ENGINE_REARCHITECTURE_PLAN 準拠） ── 【完了 ✅】
 ├── 5.1 P4: StyleTable.valuesViewport キャッシュ & ComputedStyle.declHash 64-bit 高速等価判定 ── ✅ 完了
 └── 5.2 P3: 表示項目平行移動（isIntegerTranslation / ShadowShape 保持）& リサイズ時 DisplayListBuilder 再利用 ── ✅ 完了

[Phase 6] モダン宣言的 UI DSL（React / Hyperscript スタイル Pure Java フレームワーク） ── 【完了 ✅】
 ├── 6.1 com.nishiyu.enderframelib.api.ui: VNode, VElement, VText, VFragment, Prop, Ref, State, UI, React ── ✅ 完了
 ├── 6.2 Hooks & リアクティブ状態管理: useState(初期値) による DOM 自動同期バインド (bindText, bindValue), useRef, useEffect ── ✅ 完了
 ├── 6.3 現代的 DOM 操作: classList(node).toggle/add/remove, $(selector, root), byId(id, root) ── ✅ 完了
 ├── 6.4 CatalogApp.java のリファクタリング: 従来の命令的 createElement / appendChild を完全撤廃し、React 関数型コンポーネントによるエレガントな記述へ刷新 ── ✅ 完了
 └── 6.5 ReactUiTest による単体テスト網羅 & ApiBoundaryTest 準拠検証 ── ✅ 完了
```

---

## 4. 検証基準と品質ゲート（KPI）

1. **API 純度**: `ApiBoundaryTest`, `LayerDependencyRatchetTest`, `PortabilityRatchetTest` がすべてグリーンであること（逆方向依存 0、内部型漏洩 0）。
2. **IME 正確性**: `compositionstart` $\to$ `compositionupdate` $\to$ `compositionend` でテキスト欄が正しく更新され、W3C イベントが順序通りディスパッチされること。未確定時は下線と候補窓用座標が正しく得られること。
3. **リサイズ安定性**: ウィンドウリサイズ時にクラッシュ・GL エラーが発生せず、再レイアウトとマウスポインタのヒットテストが 1:1 で一致すること。
4. **ビルド成果物**: `./gradlew :core:jar :core:sourcesJar :core:javadocJar :core:generatePomFileForMavenJavaPublication` が警告なく成功すること。
