# EnderFrameLib `:core` 抜本改造設計書：Zero-GC / GPU-Driven UI & Rendering Engine

**対象モジュール**: `D:\Dev\Personal\enderframelib-1.21.1\core`  
**関連設計書**: [`ENGINE_3D_ARCHITECTURE_PLAN.md`（Three.js 型 3D シーングラフ統合設計書）](file:///D:/Dev/Personal/enderframelib-1.21.1/core/ENGINE_3D_ARCHITECTURE_PLAN.md)  
**最終更新**: 2026-11-09（Milestone 0〜5 実装 ＋ 二重構造解消 Step 1〜5 反映・`SimdHitTester` 撤去・Phase B-1 実測訂正と文字箱の平行移動反映・Phase A-2 GL→CPU ラスタ具体型依存の窓口化・Phase A-1 画素命令の `PixelOps`/`OpPool` 分離・Phase A-2 全図形（単色角丸矩形・影・枠線・字形・画像・グラデーション）の GPU 試行を `GpuAttempt` へ分離・Phase A-1 描画先状態と層合成の `CpuRaster` 集約・Phase A-2 層（グループ・マスク・スクロール層）の GPU 問い合わせを `GpuLayerAttempt` へ集約し `SoftwareRenderBackend` の `GpuSink` 直接呼び出しをゼロ化・Phase A-3 旧 `GpuBatchPacker` 経路の撤去（GPU 経路を 1 系統に統一）・Phase A-1 字形キャッシュと被覆率部品の `GlyphCache` 分離・Phase A 試行の向きの反転ステップ ① 角丸枠線・外側影・円枠線・画像・字形の置き方の幾何を `ShapeGeometry` へ分離（完了）・Phase B-2 静止フレームの描き直し省略判定を `StaticFrameGate` として core へ切り出し・二重構造の撤去 `LayoutResultCache`（`LayoutBox.Tentative` を正本化）・`PackedCssValue` を縁の長さへ接続し `CalcProgram` を撤去・`GpuSceneSync` をテスト側へ移動（PropertyTrees の書き手を `GlRectStream` の 1 系統へ）・SIMD 島（`kernel/simd`・`FenwickVirtualList`・`SimdLayoutKernel`）をテスト側へ移動・`GpuAnimation` の Java 参照実装をテスト側へ分離（本番は GLSL のみ）・数値バインディングの往復を実測し見送り（二重構造の解消は完了）・全章統合整理版）

---

## 1. 基本思想と絶対設計原則

### 1.1 なぜ「ブラウザの模倣」ではゲームで使えないのか

Chrome (Blink) や Firefox (Gecko) は「未知の Web ページを CPU のポインタ木で逐次解釈する」ための汎用文書ビューアであり、その延長線上では **ゲームのフレームバジェット（144fps = 6.9ms, 240fps = 4.1ms のうち UI に許される CPU 時間は `< 0.2ms`・GC 確保 `0 Byte`）** には到達できません。

| 観点 | 着手前の `:core` 実装 | 一般的なブラウザ (Blink/Gecko) | **本エンジン（EnderKernel）の設計原則** |
| :--- | :--- | :--- | :--- |
| **1. メモリ管理と GC** | 163要素のレイアウトで **14.6 MB/回**、DisplayList で **5.3 MB/回** のヒープを確保 ([`jmh-baseline.log`](file:///D:/Dev/Personal/enderframelib-1.21.1/build/jmh-baseline.log)) | C++ ヒープ (Oilpan GC / malloc) 上に数万個のポインタノードを分散配置 | **オフヒープ連続列（[`OffHeapColumns`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/OffHeapColumns.java)）＋オブジェクトプールによる Zero-GC 志向**。 |
| **2. 状態とスタイルの解決** | 変化のたびに文字列分割・セレクタ探索 ($O(N \cdot R)$)・`Map<String, String>` カスケードを実行 | ランタイムでセレクタ照合・カスケード・木走査を実行 | **整数 Atom 比較 ＋ 状態遷移テーブル（[`StateStyleMemo`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/StateStyleMemo.java)）による $O(1)$ スワップ ＋ 全要素共有 [`TypedValueCache`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/TypedValueCache.java)**。 |
| **3. レイアウトの評価** | 2 パス再帰 `measure`（`LAYOUT_PASSES = 2`）＋単一スロット上書きによる Flex/Grid の毎回全再測定 | レイアウト木を毎回構築・走査 | **単一パス（`LAYOUT_PASSES = 1`）＋ 3 制約仮測定スロット（`Tentative`）＋ 幅非依存再利用（`measuredWidthFree`）＋ 局所パッチ（`CHILD_PATCH`）**。 |
| **4. CPU-GPU 境界とラスタ** | 毎フレーム全アイテムを行列合成・バッチ構築し、CPU も画素ごとに SDF を直列評価（43.3 ms） | コンポジタスレッドがタイルラスタライズや DrawCall 構築を実行 | **GPU: 3 面永続マップ VRAM リング（[`PersistentVramBuffer`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/gpu/PersistentVramBuffer.java)）＋ スクロール層 FBO 保持**。<br/>**CPU: 解析的 [`Fast`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Fast.java) 領域 ＋ 64×64 タイル並列 [`TileQueue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/TileQueue.java)（1.1 ms）**。 |

### 1.2 ライブラリ／Minecraft Mod としての 3 大制約（Milestone 0 原則）

> [!IMPORTANT]
> **利用側に特別な JVM 起動引数も追加の外部依存も一切要求しない（[`KernelBaselineTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/KernelBaselineTest.java) / [`PortabilityRatchetTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/PortabilityRatchetTest.java) が機械的に監視）**
> 1. **JVM フラグ不要**: JDK 21 で Preview 機能だった `java.lang.foreign` (`--enable-preview`) や Incubator の `jdk.incubator.vector` (`--add-modules`) は使用しない。オフヒープは標準の **Direct `ByteBuffer` + `VarHandle`（64B アラインメント）** で実装し、SIMD は **HotSpot C2 SuperWord 自動ベクトル化が効く分岐なしプリミティブループ（[`ScalarSimd`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/simd/ScalarSimd.java)）** で記述する。
> 2. **追加ネイティブ DLL 不要**: `lwjgl-harfbuzz` 等の未同梱ネイティブは追加せず、既存の `lwjgl-freetype` / `lwjgl-opengl` と純 Java 実装のみで完結させる。
> 3. **無破壊・等価性証明駆動**: すべての高速化・構造変更には「乱数入力による旧実装とのビット／画素単位の等価テスト」と「削除した旧構造が復活しないことを検査する番人（Ratchet）テスト」を必ず付随させる。

---

## 2. マスタースコアボード：初期ベースライン vs. 現在の到達値（2026-11-09） vs. 最終目標

### 2.1 パフォーマンス実測値一覧（`showcase` 163要素 / `large-list` 500行）

| 指標 | 初期ベースライン | **現在の到達値 (2026-11-09)** | **最終ゲート目標** | 達成状況・主なブレイクスルー |
| :--- | :---: | :---: | :---: | :--- |
| **CPU ソフトウェア描画 (`softwarePaint`)** | `43.30 ms` | **`1.11〜1.18 ms`**<br/>(**約 37 倍高速化**) | `< 3.50 ms` | **✅ 大幅達成**（解析的 [`Fast`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Fast.java) 領域 + 64×64 [`TileQueue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/TileQueue.java) 並列 + [`Op`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Op.java) 再利用） |
| **GL スクロール 1 フレーム（ヒープ確保）** | `2,000 KB/frame` | **`5.8 KB/frame`**<br/>(**−99.7% 削減**) | `0 B/frame` | **◎ 劇的削減**（[`OffsetItemList`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/OffsetItemList.java) + `HitTestIndex.patch` $O(1)$ + 作業表再利用） |
| **GL スクロール 1 フレーム（CPU 時間）** | 新経路初期 `9.97 ms`<br/>従来 `1.41 ms` | **連続: `0.130 ms`**<br/>**4ms ペース中央値: `0.48 ms`** | `< 0.01 ms` | **◎ 大幅短縮**（VRAM CPU Read 罠解消 + 3面リング + [`GlFenceRing`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/gl/GlFenceRing.java) + スクロール層 FBO 保持 + 配置遅延） |
| **静止フレーム (Idle Frame)** | 毎フレーム走査・確保 | **無効化 Skip (`isIdleFrame`) / アニメ確保 `0 B`**<br/>(GL 固定費込み `~0.24 ms`) | `0.00 ms / 0 B` | **◎ CPU 無効化・アニメ確保ゼロ達成**。GL 側の前フレーム出力再利用はホストで稼働済みで、判定を [`StaticFrameGate`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/StaticFrameGate.java)（core・テスト済み）に一本化 |
| **`:hover` 状態往復 1 回** | `0.097〜4.25 ms`<br/>`217 KB/op` | **`0.006〜0.009 ms`** (`StateTable` 単体 `~10 ns`)<br/>**`0.9 KB/op`** | `< 0.005 ms`<br/>`0 B/op` | **◎ 25〜240倍削減**（[`StateStyleMemo`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/StateStyleMemo.java) 4枠保持 + `peek` ゼロ確保） |
| **Flex / Grid 部分文字変更の再測定箱数** | Flex: `361〜386 箱`<br/>Grid: `421 箱` | **Flex 行: `13 箱` / 列: `29 箱`**<br/>**Grid: `15 箱`** | `< 15〜40 箱` | **✅ 完全達成（約 28 倍削減）**（`LayoutBox.Tentative` 3制約スロット再利用） |
| **幅のみリサイズ時の再測定回数** | 固定幅カード 200枚:<br/>`1,004 回` (`1.50 ms`) | **`4 回` (`0.82 ms`)** | 幅非依存部分木 `0 回` | **✅ 完全達成（測定回数 99.6% 削減）**（`measuredWidthFree` 部分木再利用） |
| **全測定レイアウト (`relayout` / `fullLayout`)** | `relayout`: `6.39 ms`<br/>`fullLayout`: `12.97 ms` | **`relayout`: `2.41 ms` (−62%)**<br/>**定常 `fullLayoutWarm`: `2.1〜3.0 ms`**<br/>(初回コールド `8.76 ms`) | `< 0.30 ms` | **🔶 半分以上短縮**（`LAYOUT_PASSES=1` + [`StyleFacts`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/StyleFacts.java) 数値化 + [`ParagraphSegments`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/text/ParagraphSegments.java) + `getFlowChildren` 確保撤去） |
| **リスト行挿入＋削除 (500行・2回更新)** | `5.75 ms` (`6.9 MB`) | **整数行: `0.76 ms` (`1.3 MB`)**<br/>(Phase B-1 前 `1.42 ms` / `3.5 MB`)<br/>小数行: `2.09 ms` (`5.6 MB`・未改善) | `< 0.05 ms` | **🔶 整数行は半減**（`CHILD_PATCH` 局所更新＋文字箱の断片平行移動。実測で支配項は `DisplayItem` 複製ではなく文字箱の描き直しだったと判明。残りは §6 Phase B-1 参照） |
| **DisplayList 構築 (`displayList`)** | `5.33 MB/op` | **`0.71 ms/op`・`1.23 MB/op`** | `0 B/op` | **🔶 約 1/4 に削減**（完全 0B 化には `DisplayItem` を作らない直接送出が必要） |

---

### 2.2 機械的ラチェット（GateTest / ArchitectureTest）達成状況

| マイルストーン | 機械的ラチェット条件（復活防止の番人テスト） | 状態 |
| :--- | :--- | :---: |
| **Milestone 0** | ・JVM 起動フラグ（`--enable-preview`, `--add-modules`）なしでのビルド・全テスト通過（[`KernelBaselineTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/KernelBaselineTest.java)）<br/>・パッケージ間循環および入れ子型の跨ぎ参照 = `0`（`ArchitectureTest` M7） | **✅ 達成** |
| **Milestone 1** | ・`HtmlParser` 内の `"gen_"` / `"txt_"` 合成 ID 生成 = `0`（[`Milestone1GateTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/Milestone1GateTest.java)）<br/>・`InteractionState` 内の `WeakHashMap` = `0`<br/>・`DocumentArena` の未読列（`stateBits`, `dirtyBits`, `subtreeEnd`）= `0`（[`DocumentArenaTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/memory/DocumentArenaTest.java)） | **✅ 達成** |
| **Milestone 2** | ・`core/css` 内の `String.format` = `0`（[`Milestone2GateTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/Milestone2GateTest.java)）<br/>・`ComputedStyle` 内の `Arrays.copyOf` および要素別 `Memo` = `0`（[`TypedValueCacheTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/css/TypedValueCacheTest.java)）<br/>・重複層 `StyleStructs` の完全撤去（`styleStructsDuplicateLayerStaysRemoved`）<br/>・`ComputedStyle` 内の正本 `String[]` = `0`（※現在は第1層として残存） | **🔶 3/4 達成** |
| **Milestone 3** | ・`RenderPipeline.LAYOUT_PASSES` = `1`（[`Milestone3GateTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/Milestone3GateTest.java)）<br/>・`LayoutBox.isReusableShape` の Table 除外分岐 = `0`<br/>・測定経路における `getFlowChildren()` リスト生成呼び出し = `0`（[`FlowChildrenAllocationGateTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/FlowChildrenAllocationGateTest.java)） | **✅ 達成** |
| **Milestone 4** | ・`ItemKeys` クラスの完全削除（[`Milestone4GateTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/Milestone4GateTest.java)）<br/>・`kernel/gpu` の GL 非依存、および本番コードからの `SimdTileSoftwareRenderer` 撤去（`simdTileSoftwareRendererStaysTestOnly`）<br/>・`LwjglGlRenderBackend` の CPU レイヤー遅延確保＆ `cpu.sessionId/hasActiveAnimation/fonts/images` 転送 = `0`（[`LazyCpuLayerTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/paint/gl/LazyCpuLayerTest.java)）<br/>・`LwjglGlRenderBackend` から `SoftwareRenderBackend` への参照 = `0`（`glBackendDoesNotKnowTheCpuRasterType`。窓口 `CpuLayerFallback` の 5 操作に集約。※CPU 保険層という機能自体は残る） | **✅ 4/4 達成** |
| **Milestone 5** | ・`PaintBench.softwarePaint` < `3.5 ms`（実測 **`1.11〜1.18 ms`**）<br/>・`SoftwareRenderBackend` からの `DirtyRect` / `PixmapPool` / `TileQueue` / `Fast` / `Clip` 分離（各番人テスト）<br/>・`TextShaper.ShapeKey` の `availableWidth` = `0`（※下ごしらえ [`ParagraphSegments`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/text/ParagraphSegments.java) を幅非依存で分離し、`ShapeKey` は同一幅即命中用に保持） | **✅ 描画達成** |

---

## 3. 現在のモジュール構成マップ（2026-11-09 時点）

不要・重複クラスの撤去（`StyleStructs` 削除、`SimdTileSoftwareRenderer` のテスト移行、`DocumentArena` 未読列削除）と、描画・CSS 部品の分割を経た最新のクラス構成です。

```text
core/src/main/java/com/nishiyu/enderframelib/core/
├── kernel/
│   ├── memory/                       [本流稼働中]
│   │   ├── OffHeapColumns.java       // Direct ByteBuffer + VarHandle による 64B 整列オフヒープ列（Ints / Longs / Floats）
│   │   ├── DocumentArena.java        // 静的領域 + 動的フリーリスト + 世代管理つき SoA アリーナ（トポロジー・識別・Bloom・NodeSlots）
│   │   └── AtomTable.java            // 文字列 ↔ 32-bit 整数 Atom ID + 64-bit Bloom ハッシュ辞書
│   (simd/ の SimdBackend・ScalarSimd・SimdBackends は SimdLayoutKernel 専用で本番から未参照だったため src/test へ移動。M0 の「JVM フラグ不要」は移動後もテストで監視)
│   ├── style/                        [本流稼働中（箱の縁 14 個）]
│   │   └── PackedCssValue.java       // 64-bit long タグ付き CSS 即値（長さ 8 単位・K_CALC は css.PackedLengths の式表の添字）。css を知らない
│   ├── compiler/                     [本流稼働中]
│   │   ├── StyleStateAutomaton.java  // 要素別対話状態マスク & O(1) 状態遷移テーブル (StateTable)
│   │   ├── StateStyleMemo.java       // 4枠フレーム保持・peek ゼロ確保の要素別状態スタイルメモ
│   │   └── BindingPatchTable.java    // {{key}} 数値 style バインディングのオフヒープ列保持 & InlineStyleOverlay 実装
│   (layout/ の FenwickVirtualList・SimdLayoutKernel は本番から未参照・3D 計画書も不使用のため src/test へ移動。LayoutResultCache は Tentative と同役の重複のため撤去、SimdHitTester は1行ラッパーのため撤去)
│   └── gpu/                          [GL 本流稼働中（※一部未接続クラスあり）]
│       ├── PersistentVramBuffer.java // CPU シャドウつき Write-Only 永続マップ / 3面リング対応 VRAM バッファ [稼働中]
│       ├── PropertyTrees.java        // Transform(64B) / Clip(64B) / Effect(32B) / Primitive(128B) ツリー [稼働中]
│       ├── GpuPrimitivePacker.java   // プリミティブバッファへのパッキング [稼働中]
│       ├── GpuRectShader.java        // GLSL 1.50 Uber-Shader (角丸矩形・枠線・影・グラデ・字形・クリップ評価) [稼働中]
│       ├── GpuAnimation.java         // GPU 側アニメーション・スクロール評価の GLSL のみ（GpuRectShader が使用中）。同じ数学の Java 参照実装は src/test の GpuAnimationReference [稼働中]
│       (GpuSceneSync は矩形だけを書く第二の書き手だったため src/test へ移動。本番の書き手は paint/gl/GlRectStream の 1 系統)
│       ├── LayerAtlas.java           // 2048×2048 共有レンダーターゲットアトラス管理 [未接続: Type C]
│       └── DualKawase.java           // Dual-Kawase 2パス高速ブラーシェーダー定義 [未接続: Type C]
│
├── dom/                              [Step 1 解消済]
│   ├── node/HtmlNode.java            // アリーナ番号 nodeId、int[] classAtoms (Atom整数比較)、InlineStyleOverlay 保持
│   ├── node/InlineStyleOverlay.java  // style 属性文字列を書き換えずにインライン宣言へ重ねるインターフェース
│   ├── InteractionState.java         // StateFlags: 対話状態を int state + overridden に、汚れ印を int dirty に1語集約
│   └── Bindings.java                 // 数値 style バインディングを BindingPatchTable へ直書き
│
├── css/                              [Step 2 解消済]
│   ├── ComputedStyle.java            // 第1層 String[] values + 全要素共有 TypedValueCache + 遅延 StyleFacts
│   ├── TypedValueCache.java          // 全スタイルで共有する文字列→CompiledLength/色/数値の不変キャッシュ（旧 Memo を置換）
│   ├── StyleFacts.java               // レイアウト用型付き事実（文字列フィールド全廃・IntrinsicWidthKind/Alignment列挙化済・縁 14 個は long[] で保持）
│   ├── PackedLengths.java            // CompiledLength ⇔ PackedCssValue の窓口（式だけ式表経由で本流の LengthResolver が評価）
│   └── CssNumbers.java               // String.format を全廃した整数ベース高速数値フォーマッタ
│
├── layout/                           [Step 5 前半反映済]
│   ├── LayoutBox.java                // participatesInFlow/hasFlowChildren（確保ゼロ）、Tentative（3制約仮測定退避）、measuredWidthFree
│   └── model/FormattingContext.java  // Display 列挙 + boolean stretch + boolean isShrink（文字列フィールド全廃）
│
├── paint/
│   ├── OffsetItemList.java           // スクロール時の疎なオフセット差し替えリスト（全項目複製を解消）
│   ├── PaintSceneDriver.java         // 親子姿勢解決・OffsetItemList 索引パッチ・スクロール保持層プロトコル・フレーム印再利用
│   ├── StaticFrameGate.java          // 静止フレームで面の再描画を飛ばす判定（同一項目・フォント世代・アニメ停止・時間依存項目なし）。控え配列を使い回しゼロ確保。ホスト CoreGpuSurface が面ごとに保持
│   ├── HitTestIndex.java             // 動的節の live() 参照による O(変更節) patch & スレッド局所 PrefixCache
│   ├── gl/                           [3面リング + スクロール層保持 + 遅延 CPU 層。図形は GlRectStream の 1 系統（旧バッチ経路は撤去）]
│   │   ├── GlVramDevice.java         // 3面永続マップリングバッファ管理
│   │   ├── GlFenceRing.java          // 7本のバッファでフレームあたり1個のフェンスを共有
│   │   ├── GlTreeStore.java / GlRectStream.java / GlRectRenderer.java
│   │   ├── CpuLayerFallback.java     // GL が CPU 保険層を使う唯一の窓口（描画・汚れ範囲・画素・汚れ消去・確保済みか の 5 操作）
│   │   └── LwjglGlRenderBackend.java // PaintResources 共有、CPU Pixmap & overlayTex は実際に塗るまで未確保
│   └── software/                     [Step 3 分割整理済・1.1ms タイルエンジン]
│       ├── PaintResources.java       // GL と CPU で共有する FontResolver / ImageProvider ホルダー
│       ├── TileQueue.java / Op.java  // 64×64 タイル計数ソート振り分け & 並列実行エンジン
│       ├── Fast.java / Clip.java / ClipPart.java / Shader.java / Scratch.java / SoftShapes.java
│       ├── PixelOps.java             // 画素を書く命令 PaintOp / GradientOp / GlyphOp の本体と行ごとの塗り部品（旧 SoftwareRenderBackend 内）
│       ├── GpuAttempt.java           // 図形ごとの「GPU へ渡せるか」の試行窓口（現在は単色角丸矩形・外側／内側の影・実線／破線／円の枠線・字形（通常／縁取り／変形）・画像・外部テクスチャ・グラデーション + クリップ変換）。渡せたら true
│       ├── GpuLayerAttempt.java      // 層（グループ・マスク・スクロール保持層）の「GPU に持たせられるか」の問い合わせ窓口。GpuAttempt の層版
│       ├── CpuRaster.java            // 描画先の状態（遅延確保の根 Pixmap・いまの描画先・層スタック・DirtyRect・層用 PixmapPool）と層の合成（フィルター→クリップ→backdrop→マスク→不透明度・混合）。GpuSink を知るのは GPU 背景の読み戻し時だけ
│       ├── GlyphCache.java           // 字形（通常・縁取り用 SDF）キャッシュ（上限 4096 で全消去）と量子化・明暗判定・縁取り被覆率・双一次補間の純粋部品（旧 SoftwareRenderBackend 内）
│       ├── ShapeGeometry.java        // 図形の幾何（角丸枠線の内側の箱・内側の角半径・辺ごとの色と太さ・破線、外側の影の形、円枠線、画像の置き方と描画範囲・標本数、字形の格子位置・サブピクセル・回転時の局所範囲）を GPU 試行にも CPU 記録にも依らない純粋関数で求める（反転ステップ ①）
│       ├── OpPool.java               // 実行済み命令の置き場（参照を手放して再利用）
│       ├── DirtyRect.java            // 汚れ外接矩形の集約・保存・復元
│       ├── PixmapPool.java           // 層用 Pixmap のサイズ検証付き LIFO プール
│       └── SoftwareRenderBackend.java
│
└── text/
    ├── ParagraphSegments.java        // 幅非依存の段落断片・fullW/strippedTrailingW/strippedW/joinW キャッシュ
    └── TextShaper.java               // 正規表現なし normalize + ParagraphSegments 共有
```

---

## 4. サブシステム別・実装済アーキテクチャと設計知見の総括

これまで時系列で追記されてきた各マイルストーンの実装・計測・修正の記録を、サブシステムごとの現在の確定仕様として整理します。

---

### 4.1 【Milestone 1】DOM 識別・`AtomTable`・`DocumentArena`・`InteractionState`

1. **合成 ID (`"gen_N"`, `"txt_N"`) の全廃と `DocumentArena` の世代管理**
   - `HtmlNode` はアリーナの整数番号 `nodeId` を持ち、`HtmlDocument.nodesById` には著者が明示した `id` のみを登録する。内部・ネットワーク用の識別キーは `HtmlNode.key()`（明示 id、無ければ `~N` / `~tN`）で統一。
   - [`DocumentArena`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/DocumentArena.java) は初期パース時の **静的領域**（`sealStatic`、番号不変）と DOM 変更用の **動的領域**（フリーリスト再利用＋世代カウンタ `generation`）に分かれ、[`NodeSlots`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/DocumentArena.java) で世代不一致の古い状態を自動無効化する。
2. **セレクタ照合の Bloom Filter 棄却と `classAtoms` 整数比較**
   - [`SelectorMatcher`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/SelectorMatcher.java) は `+` / `~` 結合子をアリーナの `prevSibling` 列で $O(1)$ 参照し、先祖探索は `selfBloom`、`:has()` の子孫探索は `subtreeBloom` の 64-bit AND 1 回で早期棄却する。
   - クラス照合（[`HtmlNode.hasClass`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/node/HtmlNode.java) および [`SelectorToken.classAtoms()`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/Selector.java)）は、[`AtomTable`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/AtomTable.java) の `int` 配列同士の整数比較で完結し、文字列分割や `regionMatches` 走査を行わない（未登録クラス名は `lookup` が負数を返し即 `false`）。
3. **対話状態と汚れ印の `StateFlags` 1 語ビット集約（二重書き込みの撤去）**
   - [`InteractionState.StateFlags`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/InteractionState.java) は、9 個の対話状態 boolean を `int state` + `int overridden`（属性由来ビットを 3 値で上書き可能なマスク）に集約し、`stateBitsOf()` を `(attrBits & ~overridden) | (state & overridden)` の 1 式で算出する。
   - 4 個の汚れ印 boolean も `int dirty`（`DIRTY_STYLE | DIRTY_SUBTREE | DIRTY_BELOW | DIRTY_UNSCOPED`）の 1 語に集約し、読み手がいなかった `DocumentArena` 側の `stateBits` / `dirtyBits` / `subtreeEnd` 列とミラー書き込みを完全撤去した。
   - **設計知見（なぜアリーナ列ではなく `StateFlags` 側を正本にしたか）**: ① ノードを DOM から一時的に取り外して付け直す（移動）際、アリーナ動的番号は `free` で 0 にクリアされるが、ノードに付随する `StateFlags` なら状態と汚れ印が失われないため、② 疑似要素や匿名ボックスなどの仮想ノード（`isVirtualNode()`）はアリーナ番号を持たないため。

---

### 4.2 【Milestone 2】状態遷移オートマトン・`BindingPatchTable`・`StyleFacts`・`TypedValueCache`

1. **`:hover` / `:active` / `:focus` を $O(1)$ にする [`StyleStateAutomaton`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/StyleStateAutomaton.java) & [`StateStyleMemo`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/StateStyleMemo.java)**
   - 要素ごとに影響し得る状態ビットマスクを算出し、最大 4 枠（親の `:hover` による継承元 2 通りの往復などを吸収）の `StateTable` に算出スタイルと遷移種別（`NONE` / `PAINT_ONLY` / `LAYOUT`）を記憶する。
   - `StateStyleMemo.peek` により解決済み状態はクロージャ生成すら行わず `~10 ns` で返し、`:hover` 往復 1 回を **`0.006〜0.009 ms`・`0.9 KB`** まで圧縮した。
2. **パスを跨ぐ [`ShareCache`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/RenderPipeline.java) とショートハンド展開キャッシュ**
   - 親の算出スタイル同一性・マッチ規則列・フォントサイズ・ビューポート・コンテナ寸法を鍵として `ShareCache` を再スタイル間で保持。定常状態（`fullLayoutWarm`）でのカスケード再解決は **`0 回`** になった。
3. **数値 `style` バインディングの [`BindingPatchTable`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/BindingPatchTable.java) 直結**
   - `style="width: {{hp}}%"` のような数値バインディングは、DOM の `style` 属性文字列を書き換えず、`BindingPatchTable.set(nodeId, field, value)` でオフヒープ float 列に直接書き込む。スタイル解決時は [`InlineStyleOverlay`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/node/InlineStyleOverlay.java) 経由でインライン宣言へ重ねるため、正規表現置換と二重書き込みが消滅した。
4. **`ComputedStyle` の圧縮：要素別 `Memo` の廃止と [`TypedValueCache`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/TypedValueCache.java) / [`StyleFacts`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/StyleFacts.java)**
   - 以前は `ComputedStyle` インスタンスごとに `Memo`（CAS 配列コピー）を持っていたが、同じ `"16px"` の解析結果は不変であるため、全スタイルで共有するロックフリー [`TypedValueCache`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/TypedValueCache.java) に統合した。
   - [`StyleFacts`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/StyleFacts.java)（および [`FormattingContext`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/model/FormattingContext.java)）から残存していた文字列フィールド（`widthStr`, `heightStr`, `intrinsicWidth`, `alignItemsWord`, `parentDisplay` 等）をすべて列挙・ビット・`CompiledLength` に数値化し、本流から未参照だった重複層 `StyleStructs` を完全撤去した。

---

### 4.3 【Milestone 3】単一パスレイアウト・仮測定再利用 (`Tentative`)・幅独立再利用・`CHILD_PATCH`

1. **`LAYOUT_PASSES = 1` と `StyleFacts` / `ParagraphSegments` による全測定 62% 短縮**
   - 2 パス測定を 1 パス化し、箱の縁 14 個やフォント・整列プロパティを `StyleFacts` から、テキスト断片幅とカーニング補正 `joinW` を幅非依存の [`ParagraphSegments`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/text/ParagraphSegments.java) から引くことで、`relayout`（showcase）を **`6.39 ms → 2.41 ms`** に短縮した。
   - さらに [`LayoutBox.hasFlowChildren()`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/LayoutBox.java) と `participatesInFlow()` により、Flex / Grid / Table / min-content 測定経路での一時リスト（`getFlowChildren()`）生成をゼロにした。
2. **Flex / Grid 仮測定の 3 スロット再利用 (`LayoutBox.Tentative`)**
   - Flex や Grid の子要素は「仮測定 → 確定幅での再測定」と複数の制約で測られるため、単一スロットの `measuredSpace` では前回の確定結果が仮測定で追い出され、無変更の項目まで毎回全再測定されていた。
   - 仮測定が読む出力群（`BoxOutputs`）を最大 3 制約の `Tentative` スロットに記憶し、確定測定が来なかった項目のみ `settleTentative` で本測定する仕組みにより、30 項目中 1 項目の文字変更時の再測定箱数を **Flex 行 `361 → 13 箱`、Flex 列 `386 → 29 箱`、Grid `421 → 15 箱`** へ劇的に削減した。
3. **ウィンドウ幅のみ変更時の部分木再利用 (`measuredWidthFree`)**
   - 自身が確定幅で `%` やビューポート単位（`vw/vh/cq*`）を使わず、子孫も箱の外を参照しない部分木に `measuredWidthFree` を記録し、ウィンドウ幅だけが変わったリサイズで測定をそのまま再利用する（固定幅カード 200 枚の測定回数が **`1,004 → 4 回`** に減少）。
4. **子の増減の局所パッチ (`CHILD_PATCH`)**
   - `appendChild` / `removeChild` 時に全箱の `buildLayoutTree` を走らせず、増減した節だけを `buildDetachedBox` + `insertBuiltChild` / `removeBuiltChild` で既存の箱木へ差し込み、`relayoutInPlace` で親だけを浅く再測定する。500 行リストの挿入配置自体は **`0.25〜0.40 ms`** で完了する。
5. **計測手法に関する重要な教訓（セーフポイントバイアス）**
   - `Thread.getStackTrace()` によるサンプリングは `ThreadLocal` や `ConcurrentHashMap.get` 等のセーフポイント通過箇所に自己時間が偏って見える。A/B 実測で差が出ない変更は取り下げ、必ず JFR（`-XX:+DebugNonSafepoints`）または壁時計の A/B 比較（中央値・p90）を根拠とする。

---

### 4.4 【Milestone 4】3 面 VRAM リング・スクロール層保持・`OffsetItemList`・GL/CPU 分離

1. **Write-Combined VRAM の CPU Read 罠の解消と 3 面リング（[`PersistentVramBuffer`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/gpu/PersistentVramBuffer.java) + [`GlFenceRing`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/gl/GlFenceRing.java)）**
   - 永続マップされた GPU メモリ（Write-Combined）から変更検出のために CPU 読み出しを行うと 1 フレーム `9.97 ms` かかる問題を発見し、CPU シャドウ配列で差分検出・Write-Only 転送に修正（`9.97 → 1.25 ms`）。
   - さらに [`GlVramDevice`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/gl/GlVramDevice.java) を 3 面リング化し、7 本のバッファのフェンスを [`GlFenceRing`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/gl/GlFenceRing.java) でフレームあたり 1 回に共有化した。
2. **スクロール更新の $O(\text{動く節})$ 化と GPU スクロール層保持（`RetainPlan.SKIP`）**
   - **表示リスト**: [`OffsetItemList`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/OffsetItemList.java) により基底リストの複製をゼロにし、[`PaintSceneDriver`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriver.java) も `patchIndex` とフレーム印（`frameStamp`）で配列再確保と `Arrays.fill` を廃止。
   - **ヒットテスト**: [`HitTestIndex.patch`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/HitTestIndex.java) は連鎖配列を書き換えず、動きうる節だけ `live()` で最新項目を引く方式にして $O(\text{変更節})$ 化。
   - **配置の後回し**: スクロールのみの更新では `repositionLayout` を遅延（`UiView.layoutStale`）させ、レイアウト参照が起きたときだけ `ensureLayoutFresh()` を走らせる。
   - **GPU スクロール層 FBO 保持**: スクロールコンテナの中身を FBO に記録し、スクロール中は [`PaintSceneDriver`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriver.java) の範囲走査ごと `SKIP` して FBO テクスチャの貼り付け位置だけを動かす。
   - **総合成果**: GL スクロール 1 フレームのヒープ確保は **`2,000 KB → 5.8 KB`（−99.7%）**、連続実行 CPU 時間は **`0.130 ms`** に到達。
3. **`LwjglGlRenderBackend` の CPU レイヤー遅延確保と [`PaintResources`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/PaintResources.java) の独立**
   - 実測により、傾き・縁取り文字・破線・`clip-path`・`filter: blur`・`mask-image`・`mix-blend-mode` はすべて GPU 側で処理されており、CPU フォールバックは「GPU レイヤー深さ／VRAM 予算超過時のみの保険」であることを確認。
   - CPU の画面サイズ `Pixmap` と `overlayTex` を「実際に CPU が画素を塗る最初の瞬間まで確保しない」遅延確保に変更し、1920×1080 で CPU 8 MB + VRAM 8 MB の常駐を削減した。
   - セッション名・アニメーション判定・[`PaintResources`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/PaintResources.java)（フォント・画像供給元）を `SoftwareRenderBackend` から切り離し、GL 側の `cpu` 依存を 7 経路から 3 経路へ半減させた。

---

### 4.5 【Milestone 5】幅非依存 [`ParagraphSegments`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/text/ParagraphSegments.java)・ローカル `@font-face` パス直登録・64×64 タイル並列 CPU ラスタ

1. **幅非依存の段落前処理とローカルフォントのゼロコピー登録**
   - UAX #14 改行候補の断片・各幅（末尾空白あり／なし／strip）・断片間のカーニング補正 `joinW` を幅非依存の [`ParagraphSegments`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/text/ParagraphSegments.java) に記憶し、幅が変わるたびの `advance` 再測定と文字列生成を排除。`TextShaper.normalize` の正規表現も 1 パス手書き走査に置換。
   - ローカルの `@font-face` ファイルは `byte[]` や `memAlloc` にコピーせず、ファイルパスのまま FreeType の OS メモリマップ（`FT_New_Face`）へ直渡しする。
2. **CPU ソフトウェア描画の 37 倍高速化（`43.3 ms → 1.11 ms`）と部品分離**
   - **[`Fast`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Fast.java) 解析領域**: 角丸矩形の塗り（円の角の行ごとスパン計算）と枠線の内側の「穴」および 4 辺の直線帯を解析的に求め、内側では画素ごとの SDF 評価（`sdRoundRect`）を完全にスキップして `Arrays.fill` / 定数ブレンドで塗る。
   - **グラデーション・字形の専用経路**: 軸平行グラデーションの列／行座標事前計算（縦・横グラデは 1 回計算）、整数グリッド上の字形 LUT 貼り付け（`blitGridAligned`）、および縁取り・回転文字の純粋シェーダー印（`Fast.PURE`）によるタイル並列化。
   - **[`TileQueue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/TileQueue.java) と [`Op`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Op.java) プール**: 描画命令を 64×64 ピクセルタイル（L1 キャッシュ 16 KB）へ計数ソートで振り分けて `IntStream.parallel` で並列実行し、実行後の命令オブジェクトをフリーリストで再利用する。
   - **責務分離**: `SoftwareRenderBackend` から [`Clip`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Clip.java)・[`ClipPart`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/ClipPart.java)・[`Fast`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Fast.java)・[`Shader`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Shader.java)・[`Scratch`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Scratch.java)・[`DirtyRect`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/DirtyRect.java)・[`PixmapPool`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/PixmapPool.java)・[`Op`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Op.java)・[`TileQueue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/TileQueue.java) を独立ファイルへ分離し、孤立していた `SimdTileSoftwareRenderer` を `src/test` の基準実装へ移動した。

---

## 5. 二重構造（Type A / B / C）の解消状況と残存課題の完全整理

監査で特定された「旧オブジェクト層と新カーネル層の二重構造」について、**2026-11-09 時点での解消済み項目**と**残る構造的課題**を整理します。

### 5.1 解消完了した二重構造（一覧）

| 領域 | 解消前の二重構造 | 実施した解決策（Single Source of Truth 化） | 守護テスト（復活防止ラチェット） |
| :--- | :--- | :--- | :--- |
| **DOM 対話状態・汚れ印**<br/>(Step 1-①) | `StateFlags` の 13 個の boolean と `DocumentArena.stateBits / dirtyBits` への二重書き込み | `StateFlags` 内の `int state + overridden` と `int dirty` に 1 語集約。読み手ゼロだったアリーナの `stateBits` / `dirtyBits` / `subtreeEnd` 列を完全撤去 | `NodeArenaIdentityTest`<br/>`DocumentArenaTest.dirtyColumnStaysRemoved`<br/>`unreadDerivedColumnsStayRemoved` |
| **`class` 照合**<br/>(Step 1-②) | `int[] classAtoms` を持ちながら `hasClass()` が生 `String` を走査 | 番号付き節の `hasClass` と `SelectorMatcher`（`SelectorToken.classAtoms()`）を `AtomTable` の整数比較に統一 | `HasClassTest.numberedNodesUseAtomsAndStayInSyncWithTheAttribute` |
| **数値 style バインディング**<br/>(Step 1-③) | 正規表現による `style` 属性書き換えと `BindingPatchTable` ミラーの二重実行 | `mirrorToPatchTable()` を削除。数値バインディングは `style` 属性を書かず `BindingPatchTable.set()` へ直書きし、`StyleResolver` が `InlineStyleOverlay` で重ねる | `BindingPatchTableTest`<br/>`ReactiveStateStoreTest` |
| **スタイルの 4 重表現**<br/>(Step 2-①②③) | ① `String[]` ② 要素別 `Memo` ③ 文字列入り `StyleFacts` ④ 未接続 `StyleStructs` | 要素別 `Memo` を全要素共有 [`TypedValueCache`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/TypedValueCache.java) へ置換。`StyleFacts` と `FormattingContext` の文字列を全廃（数値・列挙化）。未参照の `StyleStructs` を完全撤去 | `TypedValueCacheTest`<br/>`StyleFactsTest`<br/>`FormattingContextKindTest`<br/>`Milestone2GateTest.styleStructsDuplicateLayerStaysRemoved` |
| **GL 内の CPU 常駐と転送**<br/>(Step 3 前半) | GL 描画でも画面サイズの CPU `Pixmap` と `overlayTex` を常時確保し、7 経路で `cpu` に依存 | CPU `Pixmap` と `overlayTex` を遅延確保化（通常 GL 描画では確保ゼロ）。セッション名・アニメ判定・[`PaintResources`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/PaintResources.java) を切り離し `cpu` 依存を 3 経路へ削減 | `LazyCpuLayerTest`<br/>`PaintResourcesTest` |
| **CPU ラスタの重複と肥大化**<br/>(Step 3 中盤) | `SoftwareRenderBackend` に全部品が密結合し、未参照の `SimdTileSoftwareRenderer` が本番に並存 | [`Clip`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Clip.java), [`Fast`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Fast.java), [`DirtyRect`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/DirtyRect.java), [`PixmapPool`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/PixmapPool.java), [`Op`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/Op.java), [`TileQueue`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/TileQueue.java) を独立分離。`SimdTileSoftwareRenderer` を `src/test` へ移動 | `CpuRasterPartsTest`, `DirtyRectTest`, `PixmapPoolTest`, `TileQueueTest`, `Milestone4GateTest` |
| **後続行の文字箱の描き直し**<br/>(Phase B-1) | 行挿入で後続の行が整数ぶんずれるとき、文字箱（`#text`）は自位置を持たず断片だけが動くため `match` が「箱は不動なのに断片が動いた」と不一致にし、1更新あたり約 253 箱（実測）を毎回 `drawBoxSelfUncached` で描き直していた | 箱が不動で断片が全て同量動いたとき、断片の移動量を箱の移動量とみなして前回の項目を平行移動（NODE は箱位置のまま・TEXT のみ移動。項目が NODE/TEXT 以外を含む箱は従来どおり描き直し）。不一致 253→約 6 箱/更新、DisplayList 構築 `0.60→0.33 ms`・`1.8→0.6 MB` | `ChildPatchTest.textBoxesOfFollowingRowsAreTranslatedNotRedrawn`<br/>`ChildPatchTest`（乱数50手順×全再構築との項目完全一致）|
| **画素命令の `SoftwareRenderBackend` 内同居**<br/>(Phase A-1) | `PaintOp` / `GradientOp` / `GlyphOp`（画素を書く命令）と `paintPixels` / `roundRow` / `paintGradientPixels` / `spanColor`、実行済み命令の 3 本のフリーリストが 2091 行の `SoftwareRenderBackend` に同居し、「記録（姿勢・クリップ・範囲）」と「画素の書き込み」が同じ型に混在 | 命令本体と部品を `PixelOps`、フリーリストと参照の手放しを `OpPool` へ移動（コード不変の移動・可視性のみ package-private 化）。`SoftwareRenderBackend` は 2091→1858 行で記録だけを行い、`pool.acquire*` / `TileQueue(pool::release)` 経由で使う | `PixelOpsTest`（`PaintOp` と画素ごとの参照実装の乱数 40 回一致・`spanColor` の式一致・`OpPool` の再利用と参照解放・`opBodiesStayOutOfTheBackend`）<br/>全 1592 テスト緑 |
| **単色角丸矩形の GPU 試行と CPU 命令生成の混在**<br/>(Phase A-2 第1弾) | `fillRoundRect` が「`sink` に聞く」「クリップを `GpuClip[]` へ変換」「CPU 命令を記録」を 1 メソッドで行っていた | 試行と変換を `GpuAttempt.fillRoundRect` / `gpuClips` へ分離（渡せたら true）。バックエンド側は `recordFillRoundRect` が CPU 命令の記録だけを担当。挙動不変 | `GpuAttemptTest`（`sink` なし／断る／受ける／クリップ過多で聞かない・GPU が受けた図形は CPU が塗らず層も未確保・断られたときの画素が `sink` なしと完全一致・番人）<br/>全 1596 テスト緑 |
| **影の GPU 試行と CPU 命令生成の混在**<br/>(Phase A-2 第2弾) | `drawShadow` の内側・外側それぞれが、`sink` への問い合わせ・クリップ変換・穴の形の配列組み立て（`float[4]` ×3）を CPU のシェーダー記録と同じメソッドに持っていた | `GpuAttempt.insetShadow` / `outerShadow`（穴の形 `ShadowShape` → 矩形・角半径の変換もここ）へ分離。`drawShadow` は試行が false のときだけ CPU のシェーダーを記録。挙動不変 | `ShadowAttemptTest`（穴の形の受け渡し・穴なしは null・`sink` なし／断る／クリップ過多は false で GPU に聞かない・断られた外側／内側影の画素が `sink` なしと完全一致・受け取った場合の sigma＝ぼかし/2・番人）<br/>全 1602 テスト緑 |
| **枠線の GPU 試行と CPU 命令生成の混在**<br/>(Phase A-2 第3弾) | `drawRoundedBorder`（実線／破線）と `drawCircleBorder` が、`sink` への問い合わせ・非乗算色配列の組み立て・クリップ変換を CPU のシェーダー記録と同居させていた | `GpuAttempt.roundBorder` / `dashedBorder` / `circleBorder` へ分離（色配列の組み立ても試行側＝`sink` があるときだけ）。バックエンドは試行が false のときだけ CPU 記録。挙動不変 | `BorderAttemptTest`（円の色・半径の受け渡し・`sink` なし／断る／クリップ過多は false で聞かない・実線→実線経路／破線→破線経路で非乗算色と太さが渡る・実線／破線／円の断られた画素が `sink` なしと完全一致・受け取れば CPU は塗らない・番人）<br/>全 1608 テスト緑 |
| **字形の GPU 試行と CPU 貼り付けの混在**<br/>(Phase A-2 第4弾) | バックエンド内の private メソッド `glyphOnGpu` / `strokeOnGpu` / `glyphTransformedOnGpu` が、`sink` 問い合わせ・原点の四捨五入・変形時の描画範囲計算・クリップ変換を持ち、CPU の `blit*` と同じ型に同居 | 3 メソッドを `GpuAttempt.glyph` / `glyphStroke` / `glyphTransformed` へ移動（不透明度 0 は「描くものなし＝処理済み」として true、`sink` なしは常に false という意味も維持）。バックエンドは false のときだけ CPU の貼り付けへ進む。挙動不変 | `GlyphAttemptTest`（原点の四捨五入・変形範囲＝局所外接矩形+1・不透明度 0 は GPU に聞かず true・`sink` なし／断る／クリップ過多は false・通常／縁取り／回転の字が対応する GPU 経路へ届き受け取れば CPU は塗らない・断られた 3 種の画素が `sink` なしと完全一致（システムフォント無しの環境では skip）・番人）<br/>全 1615 テスト緑 |
| **画像・外部テクスチャの GPU 試行と CPU 標本化の混在**<br/>(Phase A-2 第5弾) | `drawImage` が描画範囲の計算・`sink.drawImage` 呼び出し・CPU の標本化シェーダーを、`drawExternalTexture` が uv／上下反転の写像計算と `sink.drawTexture` 呼び出しを、それぞれバックエンド内に持っていた | `GpuAttempt.image`（箱＋余白と画像範囲の交わり）と `GpuAttempt.externalTexture`（範囲 uv・flipY の写像。貼る大きさ 0 は処理済みで true）へ移動。バックエンドは false のときだけ CPU の標本化（`bilinear` / `boxSample`）へ進み、外部テクスチャを断られたら従来どおり画像として解決。挙動不変 | `ImageAttemptTest`（描画範囲の算出・`sink` なし／断る／クリップ過多は false・デコードした画像が GPU に届き受け取れば CPU は塗らない・断られた画素が `sink` なしと完全一致・範囲 uv の写像と flipY の符号・断られたテクスチャも最初に聞く・番人）<br/>全 1621 テスト緑 |
| **グラデーションの GPU 試行と CPU 命令記録の混在**<br/>(Phase A-2 第6弾・図形の分離は完了) | `drawGradient` が `sink.fillGradient` の問い合わせと CPU の列・行事前計算付き命令記録を 1 メソッドに持っていた | `GpuAttempt.gradient` へ試行を分離、バックエンドは `recordGradient` が CPU 命令の記録だけを担当。これで図形の GPU 試行は全て `GpuAttempt` に集約され、バックエンドに残る `sink.` 呼び出しは層（グループ・マスク・スクロール層）の制御だけになった | `GradientAttemptTest`（`sink` なし／断る／クリップ過多は false・線形（2色・3色）・円形が GPU に届き受け取れば CPU は塗らず層も未確保・断られた画素が `sink` なしと完全一致・番人: バックエンド内の `sink.` 呼び出しが層制御以外に戻ると失敗）<br/>全 1625 テスト緑 |
| **字形キャッシュと被覆率部品の `SoftwareRenderBackend` 内同居**<br/>(Phase A-1 第3弾) | `GlyphKey`・字形／SDF 字形の 2 つの `HashMap`・`quantize` / `isLight` / `strokeCoverage` / `sampleRaw` / `sampleCoverage` / `cov` が、記録を行うバックエンドに同居していた | 新設 `GlyphCache` へコード不変で移動（引くときは作業用の鍵 1 個を書き換えて使い回す方式も維持）。バックエンドは `glyphCache.glyph(...)` / `sdfGlyph(...)` と静的部品の呼び出しだけを行う | `GlyphCacheTest`（量子化・明暗の閾値＝参照式と乱数一致・縁取り被覆率の範囲と内外対称・範囲外 0 と格子点／中点の補間・同じ鍵は同一の字形で明暗・SDF は別表・上限で全消去（システムフォント無しの環境では skip）・番人: 移した部品がバックエンドに戻ると失敗）<br/>`GlyphAttemptTest` / `PixelOpsTest` ほか既存テストで画素一致を維持 |
| **円枠線・画像・字形の幾何と試行・記録の混在**<br/>(Phase A 反転ステップ ① 第2弾・完了) | `drawCircleBorder`（色の取り出し・事前乗算・半径・範囲）、`drawImage`（`object-fit` の置き方・標本数・描画範囲）、`drawGlyphs` / `drawGlyphStrokes`（格子位置とサブピクセルの繰り上がり・行の四捨五入・軸平行の判定）と回転時の局所範囲（バックエンド 2 か所＋`GpuAttempt` に重複）が、試行・記録と同じメソッドにあった | `ShapeGeometry.circle` / `image` / `glyphAxisAligned` / `glyphOriginX` / `glyphSubpixel` / `glyphOriginY` / `glyphLocalBounds` へコード不変で移動し、重複していた局所範囲の式を 1 か所に統一。内側影は幾何の算出が無く（形の配列化は `GpuAttempt.shapeRx/Ry` が既に担当）追加の分離は不要だった | `ShapeGeometryTest`（字形の格子位置・サブピクセルを旧式の参照実装と 5000 乱数（繰り上がり境目含む）で一致・軸平行の判定・局所範囲＝箱＋1・画像の範囲が箱の外 1 画素まで・標本数 1〜4・円枠線の色／半径／範囲・番人: 幾何の式がバックエンドに戻ると失敗）<br/>`ImageAttemptTest` / `GlyphAttemptTest` / `BorderAttemptTest`（画素不変）。全 1653 テスト緑 |
| **角丸枠線・外側影の幾何と試行・記録の混在**<br/>(Phase A 反転ステップ ① 第1弾) | `drawRoundedBorder` / `drawShadow` が、幾何の算出（内側の箱・角半径・辺色・太さ・破線パラメータ、影の形と角半径の切り詰め）・GPU 試行・CPU 記録を 1 メソッドで行っていた | 幾何だけを `ShapeGeometry.border` / `outerShadow`（record を返す純粋関数）へコード不変で移動。バックエンドは値を受けて試行と記録へ渡すだけ。残りの図形（内側影・円枠線・画像・字形配置）も同じ形で順に分ける | `ShapeGeometryTest`（枠線の内側の箱・内側半径・事前乗算色・太さを独立導出と乱数 300 回で一致・破線は 4 辺同太さのときだけ・外側影の縮み量と半径の切り詰め・狭すぎる箱は null・番人: 幾何の計算がバックエンドに戻ると失敗）<br/>`BorderAttemptTest` / `ShadowAttemptTest`（断られた画素が `sink` なしと完全一致）が画素の不変を保証。全 1647 テスト緑 |
| **描画先状態と層合成の `SoftwareRenderBackend` 内同居**<br/>(Phase A-1 第2弾) | `rootPixmap` / `target` / `layers`（`Layer`）/ `DirtyRect` / `layerPool` と、層を閉じるときの合成（フィルター・クリップ・backdrop・マスク・不透明度・`blendPixel`）が、記録を行うバックエンドに同居していた | 新設 `CpuRaster` に集約（根の遅延確保・描画先切替・層の開閉スタック・汚れ範囲・合成を移動。コードは不変の移動）。バックエンドは `sink` への問い合わせ（GPU の層を開けるか）と `raster.pushCpuLayer` / `closeCpuLayer` の呼び出しだけを行う。`SoftwareRenderBackend` 1858→1595 行 | `CpuRasterTest`（根の遅延確保・CPU 層の描画先切替と汚れ範囲の親への加算・乱数 20 回の層合成が画素ごとの参照実装（不透明度＋`over`）と一致し層が 0 に戻る・空の層は親に触れない・マスクは描かれたときだけ中身の層へ預ける・GPU の層は画素を確保させない・`endFrame` が層を返す・`clearDirty` は汚れ行のみ・`blendPixel` の恒等性・番人: 描画先状態がバックエンドに戻ると失敗）<br/>全 1635 テスト緑 |
| **層制御の GPU 問い合わせと CPU 層の混在**<br/>(Phase A-2 最終) | `openLayer` / `closeLayer` / スクロール保持の各フックが `sink.beginGroup`（`GroupExtras` の組み立て込み）・`beginMask`・`endGroup` / `endMask`・`planScrollLayer` / `drawScrollLayer` / `beginScrollLayer` / `endScrollLayer` を直接呼び、CPU 層の開閉と同居していた | 新設 `GpuLayerAttempt`（`beginGroup` / `beginMask` / `end` / `planScroll` / `drawScroll` / `beginScroll` / `endScroll`）へ集約。`sink` が null なら常に「GPU は持たない」。バックエンドの `GpuSink` 直接呼び出しは **0** になり、`GpuSink` を知るのは `GpuAttempt` / `GpuLayerAttempt` と `CpuRaster.closeCpuLayer`（読み戻し）だけ。挙動不変 | `GpuLayerAttemptTest`（`sink` なしは全て false/NONE・グループの不透明度／混合／extras の受け渡しと答えの返却・`end` のグループ／マスク振り分け・スクロール保持の 5 操作の転送と戻り値・受け取られた層は開閉が釣り合う・断られた層の画素が `sink` なしと完全一致で GPU に閉じさせない・番人: バックエンドに `sink.` / `rootSink.` の直接呼び出しが戻ると失敗）<br/>全 1642 テスト緑 |
| **GPU 経路の二重化（旧 `GpuBatchPacker` と新 `GlRectStream`）**<br/>(Phase A-3) | 図形の GPU 描画が 2 系統あり、`LwjglGlRenderBackend` が新経路（Property Tree・永続マップ）で受けない図形を旧経路（64 テクセルのインスタンスを `dataTex`/`extTex` へ差分転送する約 520 行の GLSL ＋ `GpuBatchPacker` ＋ `BatchDevice`）へ流していた。計測では showcase で旧経路の描画 0 回・新経路の拒否 0 件で、旧経路が担うのはせん断の姿勢・縦横比の違う拡縮の角丸・容量／クリップ超過の稀な場合だけ | 旧経路を撤去（`GpuBatchPacker`・`BatchDevice`・旧 SHAPE シェーダー・`dataTex`/`extTex`・差分転送・`shapeProgram` と関連テスト）。新経路が受けない図形は GL が `false` を返し、既存の CPU 保険層（`CpuLayerFallback`）が描画順を保って重ねる。`LwjglGlRenderBackend` 2737→2229 行。**副産物の修正**: GPU の図形を積む途中で CPU 層を重ねるとき、CPU のタイルキューに溜めた命令が未実行のまま範囲だけ参照されうる潜在バグを、`SoftwareRenderBackend.dirtyBounds()` がキューを先に書き切ることで解消 | `CpuFallbackOfRareShapesTest`（せん断の姿勢・縦横比の違う拡縮の角丸は新経路が断り（拒否数で確認）CPU 描画との差 ≤ 4・GPU→CPU に回った図形→GPU の描画順・番人: 旧クラスがロード不能で、バックエンドに旧部品の語が戻ると失敗）<br/>`RectTreesBackendTest`（旧経路との比較を CPU 描画との比較へ置換。showcase 全種類 GPU で最大差 4・2 超 483 画素＝約 0.1%、図形種別ごとに GPU 化した混在でも最大差 4）<br/>全 1632 テスト緑 |
| **GL → `SoftwareRenderBackend` 具体型依存**<br/>(Phase A-2) | `LwjglGlRenderBackend` が `SoftwareRenderBackend` を直接保持し、`renderOnto` / `dirtyBounds`+`pixmap`+`clearDirty` / `isRootAllocated` の 3 経路で CPU ラスタ内部（`Pixmap` 配置）を知っていた | 窓口 `CpuLayerFallback`（package-private）を新設し、GL 側が使う 5 操作だけを公開。`pixmap().pixels()` は `pixels()` に畳み、GL 側の import・javadoc 参照も撤去。挙動は変更なし（CPU 保険層の画素一致は既存テストが保証） | `Milestone4GateTest.glBackendDoesNotKnowTheCpuRasterType`<br/>`LazyCpuLayerTest`（保険層の画素一致・遅延確保）<br/>全 1587 テスト緑 |
| **静止フレーム判定のホスト内同居**<br/>(Phase B-2) | 「同じ項目が同じ順で、アニメ停止・フォント世代同じなら前回のテクスチャを貼る」判定が、Minecraft 依存の `CoreGpuSurface` 内（`Painted` / `sameItems` / `timeDependent`）にあり、単体テストできず、描くたびに `items.toArray()` を確保していた | core に `StaticFrameGate`（`canSkip` / `painted` / `invalidate` / `timeDependent`）を新設して判定を移し、控えは使い回す配列に書き込む（成長時のみ確保・短くなったぶんの参照は手放す）。ホストは `gate.canSkip(...)` と `gate.painted(...)` を呼ぶだけ。挙動不変 | `StaticFrameGateTest`（未描画は描く・同一項目＋同フォント世代は何度でも飛ばす・別インスタンス／フォント世代違い／項目数違い／順序違いは描く・アニメ中と無効化時は飛ばさない・画像は時間依存で落ち着かない・`invalidate` 後は描く・乱数 400 手順が素朴なモデルと一致・番人: ホストに `sameItems(` / `items.toArray()` / `timeDependent(` が戻ると失敗）<br/>全 1661 テスト緑（スキップ 3 は環境依存） |
| **測定時の `getFlowChildren()` 確保**<br/>(Step 5 前半) | 空判定 4 箇所と測定走査 7 箇所で毎回 `new ArrayList<>()` を生成 | `hasFlowChildren()` と `participatesInFlow()` による `children` 直接走査に置換し、測定経路のリスト生成を全廃 | `BoxTreeTest`<br/>`FlowChildrenAllocationGateTest` |
| **GPU アニメ評価の GLSL と Java の二重実装**<br/>(Type C 整理・`GpuAnimation`) | `GpuAnimation` が、シェーダーの GLSL と、同じ数学（進行度・イージング・TRS 行列・親鎖の合成・不透明度）の Java 版を同居させていた。本番で評価するのは GLSL だけで、Java 版は本番から未参照（計画書は「Java 側は未接続」と記していた） | Java 版を `src/test` の `GpuAnimationReference`（照合の正解）へ移し、本番の `GpuAnimation` は GLSL のみにした。接続の実体は「GPU が評価する」で完了しており、CPU に評価を戻さない | `Milestone4GateTest.gpuAnimationJavaReferenceStaysTestOnly`（本番に Java 参照メソッドが戻ると失敗・本番がテスト専用クラスを参照すると失敗）<br/>`Milestone4KernelTest`（進行度・イージング全種・TRS・親鎖行列・不透明度を `EasingFunction` や素朴な行列積と照合）<br/>`GlVramDeviceTest`（GL 環境で GPU の結果を Java 参照と一致）<br/>全 1666 テスト緑（スキップ 3 は環境依存） |
| **未接続の SIMD レイアウト島と本流レイアウトの並存**<br/>(Type C 整理・`kernel/simd` ＋ `kernel/layout`) | 本流の `LayoutBox` 木を走る `BlockLayoutEngine` / `BoxMeasurer` とは別に、深さ順 SoA 前提の `SimdLayoutKernel`（縁合算・クランプ・座標スイープ）・`FenwickVirtualList`（仮想リスト）と、その専用の `SimdBackend` / `ScalarSimd` / `SimdBackends` が本番から未参照のまま並存。3D 計画書（`Scene3DArena` は別実装）も使わない | 5 クラスを `src/test`（同パッケージ）へ移動し、乱数照合の基準実装・単体テストの対象に限定（既存の `Milestone3KernelTest` / `SimdBackendTest` は無改造）。本流へ接続するなら、箱木の深さ順平坦化ステージ（§5.2）を作ってから本番へ戻す | `Milestone4GateTest.unconnectedSimdLayoutIslandStaysTestOnly`（本番に不在・参照なし・テスト側に存在・`java.lang.foreign` / `jdk.incubator` を使わない）<br/>`Milestone3GateTest.movedKernelLayoutIslandDoesNotDependOnLayoutBoxOrDom`<br/>`KernelBaselineTest`（`ScalarSimd` のクラスファイルがプレビュー印でない）<br/>全 1665 テスト緑（スキップ 3 は環境依存） |
| **`PropertyTrees` の書き手の二重化**<br/>(Type C 整理・`GpuSceneSync`) | 表示項目 → `PropertyTrees` の書き手が、本流の `GlRectStream`（`GpuSink` 経由・全図形・クリップ・字形・画像）と、`kernel/gpu/GpuSceneSync`（`DisplayItem` の矩形だけ・本番から未参照）の 2 系統あった。後者は `PaintSceneDriver` の姿勢合成を経ない独自の変換解釈を持ち、本流とずれる恐れがあった | `GpuSceneSync` を `src/test`（同パッケージ）へ移動し、`PropertyTrees` のカーネル単体テスト・GL 描画器テストの道具に限定（テスト 8 本は無改造で継続）。本番の書き手は `GlRectStream` だけ | `Milestone4GateTest.gpuSceneSyncStaysTestOnly`（本番に不在・参照なし・テスト側に存在）<br/>`Milestone4KernelTest`（スロット再利用・転送最小・順序・乱数 300 手順・確保ゼロ）<br/>`GlRectRendererTest`（GL がある環境で CPU 描画と一致）<br/>全 1664 テスト緑（スキップ 3 は環境依存） |
| **長さの二重表現（`CompiledLength[]` と未接続 `PackedCssValue`＋`CalcProgram`）**<br/>(Type C 接続) | 箱の縁 14 個は `StyleFacts.edges` が `CompiledLength[]`（オブジェクト＋生文字列＋式の構文木）で持ち、別に 64-bit 即値 `PackedCssValue` と、`LengthResolver`（CSS Values 4・型検査つき）より機能の少ない数式評価器 `CalcProgram` が未接続で並存。`CalcProgram` は `round/mod/sin/pow`・`vmin/vmax`・型の検査を持たず、そのまま接続すると結果が変わる | `StyleFacts.edges` を `long[]` に置換（`ComputedStyle.compileEdges` が `PackedLengths.pack`、`edgeLength` が `PackedLengths.resolve`）。単位つきの長さは `long` 1 個（`vmin/vmax` 単位を追加）。**式だけは数式評価器を二つ持たない**ため `K_CALC` を式表（`PackedLengths`）の添字とし、評価は本流の `LengthResolver` 1 つに集約。弱い複製 `CalcProgram` と、本流から使われない `PackedCssValue.parse`（`kernel.style→css` の循環の元）を撤去。結果は旧経路とビット同一 | `PackedLengthsTest`（35 種の文字列（全単位・`calc/min/max/clamp/round`・0 除算・不正値）×200 乱数で `CompiledLength.resolve` とビット一致・宣言なし＝NONE＝0・同一式は同一スロット・式表の増設・縁 14 個が汎用 `length` 経路と一致・解決の確保ゼロ・番人: `CompiledLength[]` / `CalcProgram` / `kernel.style→css` 参照が戻ると失敗）<br/>`StyleFactsTest`（縁 14 個を乱数スタイルで旧経路と一致）<br/>`PackedCssValueTest`<br/>全 1663 テスト緑（スキップ 3 は環境依存） |
| **仮測定結果キャッシュの二重化**<br/>(Type C 整理・`LayoutResultCache`) | 本流の `LayoutBox.Tentative`（3 制約スロット・14 箱寸法＋子寸法＋制約／ビューポート／フォント署名）と別に、`kernel/layout/LayoutResultCache`（制約→5 値の 4 スロット）が本番から未参照のまま並存。カーネル側は 5 値しか持てず、実際の再利用に必要な署名を表せないため接続不能だった | `LayoutResultCache` を撤去し、`LayoutBox.Tentative` を仮測定再利用の唯一の正本にした（破壊的変更・本番参照ゼロのため挙動不変）。同クラス専用の単体テストも削除 | `Milestone4GateTest.layoutResultCacheDuplicateStaysRemoved`（ファイル不在＋main 全体に語が戻ると失敗）<br/>`Milestone3KernelTest`（Fenwick・SIMD 演算は維持）<br/>`LayoutBox.Tentative` の既存テスト（仮測定箱数 Flex13／Grid15 など）<br/>全 1661 テスト緑（スキップ 3 は環境依存） |
| **`SimdHitTester` 1行ラッパー**<br/>(Type C 整理) | `SimdBackend.hitTestLast` を呼ぶだけの22行クラスが本番から未参照のまま並存（`HitTestIndex` は2D逆変換・角丸SDF・クリップ鎖・動的節を持ち矩形包含だけでは代替不能） | クラスを撤去。矩形列の包含判定が必要な箇所は `SimdBackends.current().hitTestLast` を直接呼ぶ。乱数1000矩形×2000点の素朴実装との一致テストは `Milestone3KernelTest` が `SimdBackend` 直で維持 | `Milestone4GateTest.simdHitTesterWrapperStaysRemoved`<br/>`Milestone3KernelTest.hitTesterReturnsTopmostAndMatchesNaive`<br/>`SimdBackendTest` |

---

### 5.2 本流未接続のカーネル（Type C）の接続条件と設計判断マトリクス（`LayoutResultCache` 撤去・`PackedCssValue` 接続・`CalcProgram` 撤去済み）

調査の結果、残る `core/kernel` 内の未接続クラス（3D 計画向けの `DualKawase` / `LayerAtlas` が本番側に残る。それ以外はテスト側へ移動済み）はいずれも「既存クラスの単純な 1 対 1 差し替え」では接続できず、以下の **前提条件または設計判断** とセットで進める必要があります。

| 未接続クラス | 本流の対応箇所 | 単純差し替えができない理由（調査結果） | 接続するための前提条件・設計判断 |
| :--- | :--- | :--- | :--- |
| ~~`PackedCssValue` / `CalcProgram`~~（接続済み・2026-11-09） | `StyleFacts.edges` | `CompiledLength` と式評価器の二重だった。 | 縁 14 個を `long[]`（`PackedCssValue`）へ接続し、式は `LengthResolver` に一本化して `CalcProgram` を撤去（§5.1 参照）。**実測で見送り（2026-11-10）**: `BindingPatchTable` の数値が「数値→文字列→再解析」を経る往復は、バインディング値が毎回変わる場合と一定の場合の差が **0.04 µs/op・55 B/op**（バインディング付き要素のスタイル解決 1 回 約 1.3 µs・4.7 KB の約 3%）で支配項ではない（`TypedValueCache` は 4096 件で全消去されるため増え続けもしない）。型付き入口は `width`/`height`/`opacity`/`flex-grow`/`font-size` の読み手すべてに及ぶ大きな変更に見合わないため実装しない（§4.3 5. の実測優先方針）。縁以外の長さ（`width`/`height`/`min-*`/`max-*`）の詰め替えも、必要になるまで `CompiledLength` のまま。 |
| ~~`FenwickVirtualList`~~（テスト側へ移動済み・2026-11-10） | — | 本流の箱木レイアウトと並存する未接続の複製だった。 | 本流へ接続するなら、先に `LayoutBox` 木を深さ順配列へ平坦化するステージ（``content-visibility: auto` 行の仮想化と組み合わせる`）を作り、その上で本番へ戻す。 |
| ~~`LayoutResultCache`~~（撤去済み・2026-11-09） | `LayoutBox.Tentative` (`BoxOutputs`) | カーネル側は 5 値のみで、仮測定再利用に必要な署名を表せなかった。 | 本流の `LayoutBox.Tentative` が再測定を 1/28 に削減済みのため **`Tentative` を正本として `LayoutResultCache` を撤去**した（§5.1 参照）。 |
| ~~`SimdLayoutKernel`~~（テスト側へ移動済み・2026-11-10） | — | 本流の箱木レイアウトと並存する未接続の複製だった。 | 本流へ接続するなら、先に `LayoutBox` 木を深さ順配列へ平坦化するステージ（`SimdLayoutKernel の入力`）を作り、その上で本番へ戻す。 |
| `GpuAnimation`（GLSL は稼働中・Java 参照実装はテスト側へ分離済み）<br/>~~`GpuSceneSync`~~（テスト側へ移動済み） | [`CssAnimator`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/CssAnimator.java) / [`PaintSceneDriver`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriver.java) / [`GlRectStream`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/gl/GlRectStream.java) | 現在は `PaintSceneDriver` が CPU で合成行列 `Matrix2D` を計算し、`GlRectStream.decompose(pose)` で逆分解して `PropertyTrees` にフラット格納している。 | **Step 4**: `PaintSceneDriver` の親子・スクロール節をそのまま `PropertyTrees` の階層ノードとして保持させ、`decompose` を廃止した上で変形・不透明度アニメを GPU 補間に載せる。 |
| **[`DualKawase`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/gpu/DualKawase.java)**<br/>**[`LayerAtlas`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/gpu/LayerAtlas.java)** | `LwjglGlRenderBackend` の `blur` / `layerPool` | 現行の `LwjglGlRenderBackend.blur` は厳密な 2 パス分離ガウスブラーであり、CPU 側ガウスブラーとの画素一致テスト（`LayerFxBlurTest`）の基準になっている。`DualKawase` は近似ブラーのため、そのまま差し替えると CPU 基準と画素が食い違う。 | 2D CSS の `filter: blur()` は既存の分離ガウスブラーを維持して CPU/GL の画素一致を守り、`DualKawase` と `LayerAtlas` は **3D シーンのポストプロセス（Bloom 発光）および 2D-in-3D の `HTMLMesh` アトラス（[`ENGINE_3D_ARCHITECTURE_PLAN.md`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/ENGINE_3D_ARCHITECTURE_PLAN.md)）** に接続する。 |

---

## 6. 次フェーズの具体的なアクションプラン（Step 3 完結 〜 3D エンジン統合へ）

> **二重構造の解消は完了（2026-11-10）**: 本番コードに残る二重は、実測で支配項でないと確かめた数値バインディングの往復（§5.2）だけで、意図的に残す。以降は Phase C（3D）、およびレイアウト `< 0.30 ms`（§2.1）側へ進む。

現在のコードベースは、不要な写しや重複クラスが掃除され、モジュール境界が非常にクリーンな状態にあります。次に進めるべき作業は以下の順序で直結しています。

### Phase A：`SoftwareRenderBackend` と `LwjglGlRenderBackend` の完全分離（Step 3 の完結 = Milestone 3D-0）
1. **残る内部状態の `CpuRaster` への集約**（✅ 完了・2026-11-09）:
   - ✅ `PaintOp` / `GradientOp` / `GlyphOp` と画素塗り部品を `PixelOps`、フリーリストを `OpPool` へ分離済み（§5.1 参照）。
   - ✅ `rootPixmap` / `target` / `Layer` スタック / `DirtyRect` / `PixmapPool` と層の合成を `CpuRaster` へ集約（§5.1 参照）。層の開閉は「`sink` に GPU の層を開けるか聞く」部分だけをバックエンドに残し、CPU 層の積み・合成は `CpuRaster` に委ねる形に分けた。GPU 背景の読み戻し（blend / backdrop-filter が GPU 層から落ちたとき）のみ `closeCpuLayer` が `GpuSink` を引数で受ける。
2. **図形ごとの「GPU 送出試行 (`GpuSink`)」と「CPU 命令生成」の分離**（✅ 依存の窓口化は 2026-11-09 完了）:
   - [`LwjglGlRenderBackend`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/gl/LwjglGlRenderBackend.java) から `SoftwareRenderBackend` への直接依存（残り 3 経路）は `CpuLayerFallback` 窓口に集約してゼロにした（`Milestone4GateTest` の最後のラチェット達成）。
   - ✅ **単色角丸矩形**は試行を `GpuAttempt`、CPU 命令の記録を `recordFillRoundRect` に分離済み（2026-11-09）。影・枠線・字形・画像・グラデーションも完了。残りの図形も同じ形（`GpuAttempt.xxx` が boolean を返し、false なら `recordXxx`）で順に移す。
   - ✅ 層（グループ・マスク・スクロール保持層）の GPU 問い合わせも `GpuLayerAttempt` へ集約し、`SoftwareRenderBackend` の `GpuSink` 直接呼び出しをゼロにした（2026-11-09）。
   - ✅ `sink` / `rootSink` フィールドを `GpuRoute`（根の先・現在の先・CPU 層の出入り）へ移し、バックエンドは `GpuSink` を保持しなくなった（`GpuRouteTest`＋番人、全 1635 テスト緑）。
   - **反転の設計メモ（2026-11-09 調査）**: 現状の呼び出しの向きは `LwjglGlRenderBackend.renderLocked` → `CpuLayerFallback.renderOnto` → `SoftwareRenderBackend.drawFrame` → `PaintSceneDriver.run`（走査）→ `drawItem`。つまり**走査と図形の幾何（クリップ・角半径・影・枠線・字形配置・画像範囲）は CPU 側が持ち、GL は `GpuSink` として呼ばれる側**。真の反転は GL 側が走査を持つことを意味し、`drawBackground` / `drawRoundedBorder` / `drawText` / `drawImage` などが「幾何の算出 → GPU 試行 → CPU 命令記録」を 1 メソッドで行っている現状を、「幾何の算出（共有）」と「試行」と「記録」へ 3 分割する必要がある。したがって次の順で段階的に行う: ① 図形ごとの幾何を値（`ShapeSpec` 相当）として返す純粋関数へ分け、既存テスト（`*AttemptTest`・CPU/GL 画素比較）で等価性を保つ → ② `drawItem` を「幾何 → `GpuAttempt` → 失敗時 `recordXxx`」の薄いディスパッチへ → ③ ディスパッチと `PaintSceneDriver` の保持を GL 側が持ち、`SoftwareRenderBackend` は `recordXxx` と層の合成だけの CPU 記録器にする（`CpuLayerFallback` の 5 操作が窓口のまま）。挙動を変えず境界だけ動かすため画素・描画順の一致テストで検証できるが、`SoftwareRenderBackend`（約 1470 行）と GL の層制御の双方に触れる大きな変更であり、機能追加を伴わないため、**① から独立した小さな変更として積む**（一括の書き換えはしない。① の第1弾＝角丸枠線・外側影は 2026-11-09 完了、§5.1 参照）。
   - **未了（任意）**: 試行の向きそのものの反転（GL 側が先に試行し、失敗時だけ CPU 記録を呼ぶ）と、せん断・非等方角丸の GPU 描画。`CpuLayerFallback` の 5 操作が、反転後に CPU 側へ残す最小インターフェースになる。
3. **旧 `GpuBatchPacker`（第 2 世代）の撤去**（✅ 完了・2026-11-09）:
   - 当初案は「`GlRectStream.accepts()` を拡張して非等方スケール角丸・せん断も新経路で受ける」だったが、変換レコードが TRS（平行移動・拡縮・回転）で GPU アニメと一体、角丸の距離が局所単位の単一スケールで測られるため、せん断・非等方の対応は変換構造とシェーダー、`GpuAnimation` の GLSL／Java 参照の変更を伴い大きい。showcase の旧経路描画が 0 回・新経路の拒否が 0 件という計測から、**稀な図形は CPU に回す**方針を採った（ユーザー判断）。
   - 旧経路（`GpuBatchPacker`・`BatchDevice`・旧 SHAPE シェーダー・データテクスチャ・差分転送）を撤去し、`GlRectStream` の 1 系統に統一。画像・字形・影・グラデーション・枠線は新経路が全て受け、受けない場合のみ CPU へ回る（§5.1 参照）。
   - **残課題（任意）**: せん断・非等方スケールの角丸を GPU で描けるようにする（変換レコードに傾きを足す＋角丸の距離を画素空間で評価）。必要になるまで CPU 描画のままでよい。

### Phase B：2D 残存ボトルネックの突破（任意・高レバレッジ項目）
1. **リスト行挿入 `< 0.05 ms`**（✅ 一部実施・2026-11-09）: 当初想定の「`DisplayItem.translated` の `1.4 ms`」は **誤り**。整数行 500 行の実測では `translated` は 1 更新 `18 µs`・`146 KB`（375 項目）にすぎず、支配項は **後続行の文字箱が `match` で不一致になり毎回描き直されていたこと**（不一致 253 箱/更新、DisplayList 構築 `0.60 ms`・`1.8 MB`）だった。文字箱の断片平行移動（§5.1 参照）で `1.42→0.76 ms`、`3.5→1.3 MB`。**追加実施（2026-11-09）**: `DisplayListBuilder.match` が箱ごとに `numsOf` で 13 要素の `float[]` を確保していたのを、`numAt(box, i)` による直接比較に置換（一致判定の結果は不変・判定ごとの確保ゼロ。記憶時のみ `numsOf`）。守護: `DisplayListBuilderNumsTest`（乱数 50 回で `numAt`＝`numsOf` をビット単位で全 13 要素検証）＋既存 `ChildPatchTest`（全再構築との項目完全一致）。**残課題**: ① 小数行（`fractional`）は箱が整数ずれにならず平行移動できないため `2.09 ms` のまま ② 残りの DisplayList 構築（`0.33 ms`）は `appendItems` による 625 箱ぶんの記憶項目の再収集と `remember` 確保（`match` 側の `numsOf` 確保は撤去済み） ③ `LAYOUT_MEASURE 0.12 ms + LAYOUT_PLACE 0.07 ms`。`OffsetItemList` の範囲一括シフト化は、`translated` が `18 µs` と判明したため **優先度を下げる**（やるなら ② の再収集を範囲参照にする形）。
2. **静止フレーム `< 0.01 ms`**（✅ 判定の整理・検証は 2026-11-09 完了）: 調査の結果、前フレームの出力テクスチャを貼って GL の描画を飛ばす仕組み自体は、ホスト（`CoreGpuSurface`）に既にあった（`-Denderframe.core.noskip=true` で切り分け可）。判定が Minecraft 依存クラスに埋まりテストが無く、描画のたびに `items.toArray()` を確保していたため、`StaticFrameGate` として core へ切り出した（§5.1 参照）。**残課題**: ① GL 固定費 `~0.24 ms` の実測値は、ホスト内の飛ばしを含まない単体バックエンド計測の値で、実ゲーム内の飛ばし後のコスト（`SURFACE_CORE_SKIPPED` / `SURFACE_CORE_BLIT`）は未計測（Minecraft 実機での計測が必要）。

3. **Phase B 残項目の再検討結果（2026-11-09・コード調査。実装は見送り）**:
   - **B-1② 記憶項目の再収集**: 行挿入の局所パッチ（`UiView` の `CHILD_PATCH` 経路）は既に変更印（`useChangeStamps`）と厳密平行移動（`exactTranslation`）を有効にしており、変更のない箱は `match` を通らず `appendItems` だけで済む。残るのは `remember` 時の `List.copyOf`（1 回コピー）程度で、削れる確保は小さく、A/B で差が出る見込みが薄いため見送り（§4.3 5. の「差が出ない変更は取り下げる」方針）。
   - **B-1① 小数行**: 項目の座標は整数に丸められるため、小数ずれの箱を整数の平行移動で使い回すと全再構築と丸めが食い違いうる（`ChildPatchTest` の完全一致を満たせない）。厳密に行うには箱の小数位置から項目座標を再導出する必要があり、`match` の設計変更になる。必要になるまで `2.09 ms` のまま。
   - **B-1③ レイアウト** `LAYOUT_MEASURE 0.12 ms + LAYOUT_PLACE 0.07 ms` は合計 `0.19 ms` で、`relayout` 全体の目標（`< 0.30 ms`）側の課題として §2.1 に残す。
   - **B-2② `StandaloneView` の飛ばし判定の `StaticFrameGate` への統合**: `StandaloneView` は `rebuilt = before != after` の O(1) 参照比較で飛ばしており、`StaticFrameGate.canSkip` は項目を走査する O(N) のため、統合すると退行する。窓の再描画要求・アニメ窓（`compositorUntil`）も持つので別実装のままにする。
   - したがって **Phase B は、実装可能で効果が確かめられる項目は完了**。以降の短縮は Phase B ではなく、レイアウト（`< 0.30 ms`）側と Phase C（3D）へ進む。

### Phase C：Three.js 型 3D シーングラフの実装（[`ENGINE_3D_ARCHITECTURE_PLAN.md`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/ENGINE_3D_ARCHITECTURE_PLAN.md)）
- Phase A で独立したディスパッチャの `case SCENE ->` フックに [`ENGINE_3D_ARCHITECTURE_PLAN.md`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/ENGINE_3D_ARCHITECTURE_PLAN.md) の **`Scene3DArena` / `GlScene3DRenderer` / `SoftwareScene3DRasterizer`（Milestone 3D-1〜3D-5）** を接続し、2D HTML/CSS と 3D 空間が双方向に融合したフルゲームエンジンへと進化させる。
