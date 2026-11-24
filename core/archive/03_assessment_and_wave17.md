# 旧 §7〜§8: 当時の総合評価・残存課題、Wave 1.7 設計（DoD 再設計・内容アドレス化・インスタンス化）

> 読み取り専用の履歴。[索引](../ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md)へ戻る。本文中の `§6-N` は索引ファイルの「付録 3」の項番。数値は当時の値。現行の指示は計画書（`../ENGINE_REARCHITECTURE_PLAN.md`）だけを正とする。

---

## 7. 現状の総合評価と「他 UI ライブラリを超える」ための残存課題・次期ロードマップ

### 7.1 他の UI ライブラリとの現在地比較

定性比較（実測値は §6.4。Chrome = 157 headless、Swing/JavaFX = 500 行ラベルリスト）:

| 比較軸 | JavaFX / Swing | Chromium (Chrome 157 / CEF / JCEF) | Dear ImGui / 独自 GUI | **EnderFrameLib `:core`（現在地・実測）** |
| :--- | :--- | :--- | :--- | :--- |
| **バイナリ規模・依存** | OS 固有ランタイム同梱 | **100MB 超の C++ 別プロセス** | 軽量だがレイアウトが手動座標 | **純 Java + 軽量 FFM/LWJGL（単体 JAR 配布可能、公開 API 完備）** |
| **CSS レイアウト表現力** | 独自 CSS 風サブセット（Swing の `JEditorPane` は HTML 3.2） | Web 標準フル対応 | Flexbox / Grid なし | **Block / Flex / Grid / Table / Multicol / `@container` / `oklch` 対応**（`subgrid`・縦書き・`popover` は未対応） |
| **静止・スクロール** | シーングラフの GC 負荷あり | rAF 枠込みで `0.14〜0.38 ms` | 毎フレーム全 UI 再構築 | **`idle` `0.001 ms`、`scroll` `0.008〜0.2 ms`（Chrome より低い）** |
| **局所更新（小規模ページ）** | `0.2〜0.7 ms`（Swing 1 行テキスト `0.20`、JavaFX `0.70`） | main `0.5〜0.7 ms` | — | **GL 実フレーム `0.7〜0.8 ms`（Chrome とほぼ同等）、パイプライン単体 `0.25〜0.28 ms`** |
| **局所更新（5,000 項目級）** | Swing 1 フレーム `0.64 ms`（ビューポート描画 `0.5 ms`）／JavaFX `3.8 ms`（500 行） | main `0.35〜0.5 ms`（画面外は raster しない） | — | ❌ **GL `2.2〜2.6 ms`（500 行）→ `10〜13 ms`（2,000 行）。Chrome の 4.5〜24 倍（§6.5 W1/W2）** |
| **HTML 解析・オープン** | JavaFX `18.5 ms`・JEditorPane `32.5 ms`（500 行） | showcase `5.0 ms`・500 行 `6.4 ms` | 非対応 | **showcase `6.2 ms`・500 行 `4.6 ms`（Chrome 同等〜やや優位、2,000 行は 1.3 倍遅い）** |
| **`:hover` / 継承再スタイル** | JavaFX `0.57 ms` | `0.34 ms` / `0.45 ms` | — | **`0.007 ms`（49 倍速い）/ `0.10 ms`（4.5 倍速い）** |
| **全面レイアウト（幅変更）** | Swing `0.47`・JavaFX `1.02 ms` | showcase `0.08`・500 行 `0.76 ms` | — | ❌ showcase `0.93 ms`（11 倍遅い）・500 行 `1.02 ms`（1.3 倍遅い） |
| **ホスト組み込み性** | 専用ウィンドウ前提 | オフスクリーン転送が重い | 容易 | **`EnderEngine` で GLFW 窓・GL FBO・CPU `Pixmap`・`PixelSink` に数行で結線** |

> **2026-11-11 再計測による現在地の更新（§6.7）**: 局所更新（5,000 項目級）の行は、GL 実フレームが **500 行 `hover` `0.72` / `text` `0.43` / `style` `0.65` ms**（Chrome `0.34〜0.49`、比 0.9〜2.0 倍）、**2,000 行 `hover` `3.50` / `text` `1.61` ms**（Chrome `0.55` / `1.02`、比 6.4 / 1.6 倍）へ改善し、❌ から「`text` は Chrome 並み、`hover` は 2〜6 倍差」の 🔧 になった。ただし `child` は 500 行 `2.18`（4.0 倍）・2,000 行 `13.8` ms（12.6 倍）、`inherited` 500 行 `3.59` ms（3.8 倍）が残る。**GL の 2,000 行 `hover` `3.50` ms は null `3.38` ms とほぼ同じで、GL 送出はもう律速ではない（`display-list` 段が `2.45 ms` = 70%）**。

> **総合評価（2026-11-10 の横断計測を反映して改訂）**:
> Wave 1 の実装により、**① 公開 API の整備、② `core/src/main` からの Minecraft 固有概念の完全分離、③ `EngineContext` によるマルチインスタンス化、④ `LoadedFace` の並列化、⑤ Milestone 2 の完遂、⑥ GL 走査の差分更新化とレイアウト測定回数の半減** まで完了し、**数百〜千要素規模の UI（ゲーム HUD・設定画面・ダッシュボード）では Chrome と同等以上、Swing / JavaFX を明確に上回る**水準に到達した（`:hover`・継承再スタイル・`idle`・`scroll`・オープン速度は Chrome を上回る）。
> 一方、**「他エンジンを超える」には 2 つの構造的な穴が残る**。(1) **規模耐性**: 描画側（GL 送出・DisplayList 生成・CPU 全面再描画）が可視範囲に関係なく総項目数に比例するため、2,000 行（2 万項目）級で局所更新が Chrome の 10〜24 倍、`child` が 23 倍になる（W1〜W4）。パイプライン単体（`null` バックエンド）の局所テキスト更新は 2,000 行でも Chrome 以上なので、**解くべきは「下流のペイント準備・送出を可視範囲＋変更部分に限定すること」**である。(2) **全面レイアウトの測定キャッシュ**（幅変更で Chrome の 11 倍、W5）。なお文書上限 128 KiB の黙った切り捨て（W8）は 2026-11-10 に修正済み。

---

### 7.2 横断ベンチマークから導かれる「既存ライブラリ・ブラウザを凌駕するための 7 大技術戦略」

§6.4・§6.5 の横断計測が示した本質は極めて明快である。`:core` は**上流のコンパイラ型最適化（[`StyleStateAutomaton`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/StyleStateAutomaton.java) による `:hover` 49 倍高速化、`restyleInherited` 4.5 倍高速化、2,000 行 `TEXT_PATCH` の `0.725 ms`）ではすでに Blink / Gecko を圧倒**している一方、**下流（DisplayList 構築・SceneDriver 走査・GPU/CPU ラスタ送出）および幅変更レイアウトに「$O(N_{\text{全要素}})$ の線形走査」が残っている**ために大規模ページで逆転されている。

したがって、既存のブラウザ（Blink LayoutNG / PaintArtifactCompositor、Firefox WebRender）や UI フレームワーク（Flutter Impeller、Dear ImGui、最新ゲームエンジンの GPU-Driven Rendering）の知見と、Java 21 FFM API・単一プロセス統合の強みを掛け合わせた以下の **7 大技術戦略（T1〜T7）** によって、すべての領域で既存エンジンを上回る。

| 戦略 ID | 対象弱点 | 採用するコア技術（他分野・ブラウザ技術の応用） | 計算量の変化 | 既存エンジンを超える根拠（理論・目標値） |
| :--- | :---: | :--- | :---: | :--- |
| **T1: 階層的空間カリング & スクロール区間インデックス** | **W1** | **WebRender / ゲームエンジン BVH 型**: `SCROLL_NODE` の可視 Y 区間二分探索と [`PaintOrder.Chunk`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintOrder.java) 外接 AABB カリングを [`PaintSceneDriver.run`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintSceneDriver.java) の上流で行う | $O(N_{\text{total}}) \to O(\log N + N_{\text{visible}})$ | ペインタ段の `outsideClip`（2026-11-10 実装済み・CPU −55%）をさらに上流へ引き上げ、画面外 20,000 項目の `resolveAt`・`buildClip` 自体をゼロにする。2,000 行画面外 `hover` を **`< 0.25 ms`（Chrome `0.55 ms` の 2 倍以上高速）** へ |
| **T2: Segmented DisplayList（CoW スライス木）＋ 64-bit 整数鍵** | **W2, W4, W6** | **Rope / B-Tree スライス構造 & Blink PaintChunk**: 各 [`LayoutBox`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/LayoutBox.java) が自身の `[dlStart, dlEnd)` スライスを保持し、局所変更・`CHILD_PATCH` 時は該当スライスのみを配列シフト／差し替え。`keyOf` は `(arenaHandle << 8) \| partOrdinal` の `long` 化 | $O(N_{\text{boxes}}) \to O(\Delta_{\text{dirty}})$ | `CHILD_PATCH` 成立時に 78%（`7.1 ms`）を占めていた `DisplayListBuilder` 全走査と、全シナリオの 8〜10% を占める `AtomTable.intern` が消滅。2,000 行の行挿入・削除を **`< 0.5 ms`（Chrome `1.09 ms` の 2 倍高速）** へ |
| **T3: GPU-Driven Retained Instance Slices（VRAM 永続スライス保持）** | **W1, W6** | **WebRender / GPU-Driven Rendering**: 未変更 [`DisplayItem`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/model/DisplayItem.java)（特にテキストの字形群）が生成した 80B GPU インスタンス列をキャッシュし、次フレームは字形ループを飛ばして `MemorySegment.copy` 1 回（または SSBO 間接参照）で送出 | $O(N_{\text{glyphs}}) \to O(\Delta_{\text{changed items}})$ | §6.3 で `GlRectStream` 容量拡大が逆効果だった原因（1 字形 `0.13 µs` の CPU 積み込み）を根絶。容量上限 4,096 を撤廃して **5.9 万件の CPU 棄却をゼロ**にしつつ、メインスレッド送出を `< 0.1 ms` に抑える |
| **T4: Tile Dirty Bitset（64×64 タイル差分ラスタ）＋ Scroll Blit** | **W3** | **Skia / Flutter Impeller Dirty Region Tile Binning**: 800×600 画面の 130 タイルを `long[3]` ビットマスクで管理し、`patchIndexByDiff` の変更項目（旧 AABB ∪ 新 AABB）と交わるタイルのみ再ラスタ。スクロールは既存 `Pixmap` の `arraycopy` シフト＋露出帯のみ描画 | $O(W \times H) \to O(\text{Area}_{\text{dirty}})$ | ボタン 1 個の `:hover` で塗るタイルが 130 枚 → 2〜4 枚（面積比 1/40）に激減。CPU バックエンド単体で **`4.42 ms → 0.15〜0.30 ms`** となり、**Chrome CPU 合成 (`1.55 ms`) や Swing (`0.64 ms`) を逆転** |
| **T5: Blink LayoutNG 型「二段制約キャッシュ（Measure 多枠 + Place 入力スキップ）」** | **W5** | **Blink LayoutNG `NGLayoutResult` Caching**: ① [`BoxMeasurer`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/BoxMeasurer.java) を純粋関数化してボックス当たり 4-way キャッシュ（Intrinsic / 仮幅 / 確定幅）を持たせ A→B→A→B 上書きを根絶、② `place` パスでも親から渡る実効制約幅が不変なら非 Dirty 部分木の再配置を丸ごとスキップし座標オフセットのみシフト | $O(N_{\text{subtree}}^2) \to O(N_{\text{width-changed}})$ | ウィンドウ幅 ±1px 変更時に固定幅カードや未変更グリッド内の再測定・再配置がすべてスキップされ、`showcase` 幅変更を **`0.931 ms → < 0.10 ms`（Chrome `0.084 ms` と同等、Swing/JavaFX の 5〜10 倍高速）** へ |
| **T6: オフヒープ DOM 雛形クローン & 文書横断 `ShareCache` & 非同期 JIT 暖機** | **W7** | **AOT Template Cloning & Virtual Thread Prewarm**: 同一 HTML テンプレートの [`DocumentArena`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/memory/DocumentArena.java) オフヒープ列を `MemorySegment.copy` で複製し、UA/共通 CSS の `ComputedStyle` を文書横断で共有。起動時にバックグラウンドで JIT プリウォームを実行 | 初回 Open コスト半減・コールドスパイク 0 | `StyleSheet` 雛形キャッシュ（S5）を HTML DOM と算出スタイルまで拡張し、画面オープン速度で Chrome / Firefox / JavaFX / Swing のすべてを安定して下回る |
| **T7: ブラウザに原理的に不可能な「単一プロセス・ゼロコピー空間 UI & コンパイル済み Signal」** | **W9 + 超差別化** | **① `content-visibility: auto` + [`FenwickVirtualList`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/layout/FenwickVirtualList.java) ($O(\log N)$ 自動仮想化)、② Java 21 `Record` / プリミティブ `Signal` → [`BindingPatchTable`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/BindingPatchTable.java) 直結、③ Phase C ゼロコピー 2D/3D シーングラフ融合** | 10 万行 `< 0.2 ms`・IPC/JS オーバーヘッド 0 | Chromium (CEF) のプロセス間通信・V8 JS 変換・DOM 仮想化ライブラリや、ブラウザ Three.js の `html2canvas` 転送では**構造上絶対に到達できない領域**を確立 |

---

### 7.3 未解決の重要課題（優先度順・整理版）

完了済みの項目を除き、現在残っている真の技術課題を**実測インパクト順（P0〜P4）**に整理する。

---

#### 🧭 再策定（2026-11-11、2026-11-12 更新）：再計測（§6.7・§9）に基づく新ロードマップ R0〜R7

**再計測が示した事実**（以下は 2026-11-11 の §6.7 再計測。最新の 2026-11-12 値は §9.3 と下表を参照）

1. **成果**: ペインタ段・ドライバ段カリング、64-bit 鍵、増分索引、Segmented DisplayList、CPU Tile Dirty で、large-list の局所更新（GL）が **−44〜−84%**。500 行 `text` は Chrome 並み（0.9 倍）。
2. **律速の移動**: 2,000 行 `hover` は GL `3.50` ≒ null `3.38` ms。段別は `restyle-local 0.11` + **`display-list 2.45`**（70%）。`hover` 往復は 500→2,000 行で null が `0.50 → 3.38 ms`（行数 4 倍で 6.8 倍＝超線形）で、**スタイル変更経路が `patchBoxes`（スライスパッチ）に乗らず、`DisplayListBuilder` を全走査している疑いが強い**（`UiView` が `patchBoxes` を呼ぶのは `tryTextPatch` 経路、`patchChildSlices` は `tryChildPatch` 経路のみ。**要コード確認 = R0/R1 の最初の作業**）。同じ画面外 1 行の `text` は display-list `0.47 ms` で済んでおり、パッチ経路に乗れば約 1/5 になる見込み。
3. **`child` が最大の残差**: 2,000 行 `child` GL `13.8` ms（Chrome `1.09`）。`display-list` が `5.6` ms 残っており、`CHILD_PATCH` ヒット時でもスライスパッチ（`canPatchChildSlices`）が成立していない、または成立しても後続シフトが O(N) の可能性（**仮説・未検証**）。残り約 `8 ms` はレイアウト・索引・GL 送出・確保 `19 MB/フレーム`だが内訳は未取得（R0）。
4. **全面無効化**（`fontsize` / `inherited`）: 2,000 行 `fontsize` GL `18.9` ms（Chrome `11.3`・1.7 倍）。display-list `6.2` + restyle `1.9` + 測定・配置。確保 `25 MB/フレーム`（W6）は未解決。
5. **未改善・要調査**: ① W5 幅変更レイアウト（showcase `1.04` ms、Chrome の 12 倍）。② JMH `softwarePaint` showcase `1.97 → 4.38` ms（±1.7。再現確認から）。③ **CPU バックエンドは showcase が large-list より遅い**（`hover` `4.59` vs `2.28` ms）＝ダメージ領域が全面化している疑い。④ 計測が 1 実行のため分散が大きい（p99 は平均の 2〜5 倍）。

**再策定後の優先順位（実測インパクト ÷ 工数の順）**

| ID | 優先 | テーマ | 根拠（実測） | 主な作業 | KPI（合否線） |
| :--- | :---: | :--- | :--- | :--- | :--- |
| **R0** | ✅ **完了（2026-11-11・§6.8）** | **計測基盤の補強**（以降すべての判定に使う） | 段別が `restyle-local` / `display-list` しか出ず、GL 送出・レイアウト・索引・パッチ命中が見えない。分散が大きい | ① `FineProfileMain` に段別（measure/place/index/GL send）と **パッチ命中・不成立理由カウンタ**（text/child/style/slice）を出力。② 各条件 3 回実行して中央値＋最小・最大を台帳化（`summarize-fine.js` 拡張）。③ 2,000 行 `inherited`・`relayout` を追加。④ JMH `softwarePaint` showcase の悪化を再現確認のうえ二分探索（`git bisect`）で原因特定（**実施済み: 退行ではなかった。§6.8.3**） | 2,000 行の全シナリオで段別合計がフレーム平均の 95% 以上を説明 |
| **R1** | ✅ **完了（2026-11-11・§6.9）** | **【R1-0 実装済みスライスパッチの有効化】＋ スタイル変更（`hover`/`style`/`:focus`）の DisplayList スライスパッチ化** | **R0 で判明（§6.8）: `StandaloneView.Ui.paintBoxes` が独自の `DisplayListBuilder` を持ち、`UiView.paintBuilder` が常に `null` のため、P0-4 の `patchBoxes`/`patchChildSlices` は標準経路で一度も実行されていない（スライス命中 0）**。2,000 行 `hover` display-list `2.45 / 3.50` ms（70%）、`style` `1.95`、`text` `0.44` | **✅ 完了**: ① R1-0: `StandaloneView.Ui` のビルダーを `UiView.paintBuilder` に一本化し `noBuilder` フォールバックを完全解消。② `restyle-local` の変更ボックス集合を `canPatchBoxes`/`patchBoxes` へ接続（`RESTYLE_REPAINT_PATCH` 経路確立）。③ 境界不成立（スライス重複 `cant(0, 7)`、スタッキングコンテキスト発生・解除 `cant(0, 6)`、寸法変動 `cant(0, 8)`）を理由カウンタ付きで安全フォールバック。④ `:hover` 往復・`transition`・スタッキングコンテキスト解除の 100% 一致テストを `DisplayListSlicePatchTest` に追加し全件合格 | 2,000 行 GL `hover` **`< 1.0 ms`**（現 `3.50`、Chrome `0.55`、第 2 目標 `< 0.6`）、500 行 `< 0.4 ms`、確保 `< 300 KB` |
| **R2** | 🔧 **部分達成（2026-11-11・§6.10／2026-11-12 再計測 §9）** | **子要素の挿入・削除（`child`）の残差解消（W2）** | **large 2,000 行は `1.44 ms`（Chrome `child250` `1.98` 以下）で KPI 達成**。varied 2,000 行は `5.15 ms`（Chrome `3.67`・1.4x）で未達。`place` が `3.05 ms`（59%）、`ArrayList.add` 15% | スライスパッチ（2 層・座標追従・親寸法同期）・窓付き表示リスト（§8.6.2）・fit/measure 再利用（2026-11-12 コミット）で large は解消。varied の残りは後続 1,750 行の `InlineFragment` 再生成（P2） | large `child` GL `< 3 ms` は達成。varied `child` GL **`< 3.7 ms`**（Chrome 並み）、確保 `< 2 MB` |
| **R3** | 🥇 最優先 | **全面無効化（`fontsize`/`inherited`）＋ 幅変更 ＋ 可視範囲限定のスタイル・レイアウト（W5/W6 / T5・T7-①）** | **large 2,000 行 `fontsize` は `7.63 ms`（Chrome `11.3` 以下）で達成**。varied 2,000 行 `fontsize` は `30.77 ms`（Chrome `42.1` 以下）だが確保 `25.8 MB`。varied `inherited` は `10.20 ms`（Chrome `3.55`・2.9x）で、`canPatchBoxes` 26.4% が主因。showcase 幅変更 `1.05`（Chrome `0.084`） | ① **P1: `canPatchBoxes`/`offscreen` の判定キャッシュ（§9.7）**。② P2: `placeLine`/`InlineFragment` 再利用。③ T5: 測定 4-way キャッシュ＋`place` 制約スキップ。④ `content-visibility: auto` 相当の**ビューポート外スキップ**。⑤ `inherited` は継承値を使うボックスだけへ伝播を限定 | 500 行 `inherited` GL **`< 1.5 ms`**、varied 2,000 行 `inherited` **`< 3.6 ms`**、2,000 行 `fontsize` GL **`< 10 ms`**、showcase 幅変更 **`< 0.2 ms`**、`fontsize` 2,000 行の確保 **`< 5 MB`** |
| **R4** | 🥈 高（一部達成） | **CPU バックエンドのダメージ領域全面化の解消（W3 の残り）** | showcase CPU `hover` **`1.77 ms`**（paint `1.64`、`partial=0/full=1801`、理由 1/6 = transition）、`inherited` **`2.62 ms`**（paint `2.19`、`partial=1599/full=2`）。`inherited` は目標内、`hover` は未達 | ① P7: 項目ごとの「前フレームで動作中だったか」を記録し、遷移中の項目だけを変更扱いにする（§9.7）。② 動作中項目のタイルだけを部分再描画する。③ 影・ブラー・`backdrop-filter`・`mix-blend` の AABB 局所化。④ 差分描画の見積コストが全面描画を超える閾値判断の再調整 | showcase CPU `hover` **`< 1.5 ms`**、`inherited` `< 4 ms`（達成: `2.62`） |
| **R5** | 🥉 中（条件付き） | **項目単位の保持 GPU 記録（T3）** | 2,000 行 `hover` は GL 送出 `0.77` ms（22%）で律速でないが、**`child` `6.2` ms（44%）・`inherited` `5.2` ms（33%）・`fontsize` `5.4` ms（28%）は 30% 前後以上**。R1〜R3 後に再判定（`child` はすでに条件を満たす） | 現行設計（`MemorySegment.copy` 一括転送＋容量 4,096 撤廃）を温存。着手判定は R0 の段別で **GL 送出 > フレームの 30%** | 着手時: 2,000 行 `text` GL `< 0.8 ms` |
| **R6** | 🥉 中 | **オープン・コールド JVM（W7 / T6）** | showcase `open` `5.5` ms（Chrome `5.0`）、500 行 GL `6.2` ms、2,000 行は未再計測 | DOM 雛形クローン・`sealNodeIds` 統合・`EnderEngine.prewarm()`。**コールド JVM 初回フレームを計測に追加** | 初回フレーム p99 を台帳化、2,000 行 `open` `< 20 ms` |
| **R7** | 🔒 横串（一部完了・§8.6.12 / §8.6.14） | **退行防止ラチェットの拡張** | 今回の改善（パッチ命中・確保量）は時間でなくカウンタで固定できる（時間ベンチは全件実行で不安定） | `AllocationRatchetTest` に 500 行 `hover`/`text`/`child` の確保上限、`UiView` の **パッチ命中率ラチェット**（`hover` 往復で slice-patch 100%・`child` 100%）、全再構築との一致ランダム列テスト（`DisplayListSlicePatchTest` を style/hover へ拡張） | 追加テスト全件グリーン、`:core:test` 全件グリーン |

**実施順序**: R0（完了）→ R1（完了）→ R2（部分達成。時間 KPI は Wave 1.7 で再判定）→ R3（Wave 1.7 の施策 2・3 として最優先）→ R4 → R6 → R5（条件付き）。R7 は各段に同伴。各段の完了ごとに §6.7 形式で再計測し台帳を更新する。**P2（3D）・P3（IME 等）・P4（DX）は性能 KPI（R1〜R4）が Chrome 比 2 倍以内に入るまで着手しない**（ただし IME は日本語 UI の必須要件のため R3 と独立に並行着手可）。

**2026-11-12 再計測（§9）による順序の更新**: R3 の前段として、**P1（`canPatchBoxes`/`offscreen` の判定キャッシュ）→ P2（`placeLine`/`InlineFragment` 再利用）** を最優先で実施する。P1 は varied `inherited`、P2 は varied `child` の最大要因であり、どちらも R3/R2 の KPI に直結する。

#### 🔄 Wave 1.7（2026-11-11 方針転換）：DoD 再設計・内容アドレス化・RTI → 設計は §8

R2 の未達（§6.10）を受け、局所最適化では `display-list` 約 9 ms が下限と確定した。律速は構造（項目が絶対座標を焼き込み、全項目を同一性で比較する）にある。方針は ① 構造クラスの計測（M0）→ ② テキスト成形の幅非依存キャッシュ＋改行区間キャッシュ → ③ `ComputedStyle` の共有 → ④ 反復ブロックのインスタンス化（RTI）→ ⑤ 座標を持たない Paint 表・Box 表 → ⑥ 永続データ構造・並列化。**R3（全面無効化・幅変更・可視範囲限定）は ②③ と実質同一の作業として進める。** 詳細・見積もり・限界・リスクは §8 を参照。

**下記 P0〜P4 の記述は、実装済み事項の記録と各設計メモとして残す。優先順位と KPI は上表 R0〜R7 が優先する。**

---

#### 🔥 P0：GL 描画パイプラインと DisplayList のスケール課題（5,000〜20,000 項目での 144Hz 維持 — 戦略 T1〜T4）

§6.5 の横断計測（W1〜W4・W6）で、500 行（5 千項目）で Chrome の 4.5〜9 倍、2,000 行（2 万項目）で 10〜24 倍まで開くことが確定した。以下は実装済みの要約と、残課題である。

**実装済み（詳細はコードとテストを参照）**

| 項目 | 内容 | 効果・備考 |
| :--- | :--- | :--- |
| 可視範囲カリング（T1） | ペインタ段の項目カリング（`outsideClip`）、スクロール層記録時カリング、`PaintSceneDriver` の 64 項目ブロック走査除外（`NONE` 計画の CPU バックエンドにも適用） | CPU `hover` `3.18 → 1.41 ms`、`scrollhover`（GL）フレーム p50 約 −50%。画素一致テスト（`OffscreenItemCullPixelTest`・`PaintSceneDriverRangeCullTest`）で固定。**未着手: `SCROLL_NODE` 区間の二分探索（表示リストが鋸歯状で単調性が無く、設計見直しが必要）** |
| CPU Tile Dirty Bitset ＋ Scroll Blit（T4） | `CpuRaster.scrollBlit`、露出帯のみの再描画、`CHILD_PATCH` 時の 64-bit 鍵による旧新項目対応 | large-list 500 行 CPU `hover` 差分描画が成立。`ScrollBlitTest`・`CpuScrollBlitPixelTest` で全画素一致 |
| 増分索引（T2） | `patchIndexByDiff` の上限撤廃（構造不変なら 25% 超でもインプレース）、`patchIndexByShift`（head/tail 同一性が前提） | **`child` では後続項目が再生成され同一性を失うため `patchIndexByShift` が成立せず、全面作り直しに落ちる（§6.10）** |
| 64-bit 整数鍵化（T2） | `(nodeHandle << 16) \| partOrdinal`。テキスト断片・装飾・主要部品・スクロールバー・親子鍵（`parentKey`）まで | 索引引きの文字列確保を排除。ただし `rebuildIndex` に文字列鍵の補助索引（`AtomTable.intern`）が残り、全面作り直し時に `paint(cpu)` の約 25% を占める |
| Segmented DisplayList（T2） | `LayoutBox` が `[dlStart, dlEnd)` を保持、`patchBoxes` / `patchChildSlices` で局所差し替え | R1 で `hover` 2,000 行 `display-list` `2.45 → 0.018 ms`。`child` は §6.10 のとおり未達 |

**残課題**

2. **項目単位の保持 GPU 記録（T3）**: 1 要素のテキスト変更でも、未変更の字形に毎フレーム `drawGlyphs`（フレームの 39%）が走る。項目ごとの 80B GPU インスタンス列を保持し、同一なら `MemorySegment.copy` で一括転送する。着手判定は GL 送出がフレームの 30% 超（R5）。
5. **GPU/CPU 走査向きの反転と非等方角丸・せん断の GPU シェーダー化**: `GlRectStream.decompose` が毎フレーム `Matrix2D` を `atan2`/`hypot` で逆分解し、せん断・非等方スケール下の角丸を CPU フォールバックへ回している。`PropertyTrees` の Transform ノードに 2D アフィンを保持して `decompose` を廃止し、`GpuRectShader` で直接評価する。

---


#### ⚡ 優先度 P1：レイアウト二段制約キャッシュ（Measure 多枠 + Place スキップ）と局所パッチ拡大（戦略 T5 / T6）

1. **`BoxMeasurer.computeSize` の純粋関数化・4-Way 測定キャッシュ ＋ `place` 制約スキップ（W5 — 戦略 T5）**:
   - **現状**: §6.2 で特定したとおり、Flex レイアウト等で同一ボックスが「仮幅 A → 確定幅 B」の 2 通りで交互に測定されると、1 スロットキャッシュが毎回上書きされて 279 回の重複測定が発生している。さらに `showcase` の幅 ±1px 変更で `0.931 ms`（Chrome `0.0835 ms` の **11.1 倍**）かかるのは、親から渡る実効幅が変わっていない固定幅カードや内部ボックスまで `place` と測定をやり直しているためである。
   - **対策**:
     1. `computeSize` が参照する親依存パラメータ（`parentInnerWidth()` 等）をすべて [`ConstraintSpace`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/model/ConstraintSpace.java)（および測定キャッシュ鍵）に明示化して純粋関数にし、ボックスごとに 2〜4 スロットの測定キャッシュ（Intrinsic / Flex 仮測定 / 確定サイズ）を持たせる。
     2. **配置（`place`）パスにも Blink LayoutNG 型の制約キャッシュ**を導入し、ボックスが `!layoutDirty` かつ親から渡された利用可能幅・高さが前回 `place` 時と同一なら、子孫の `place` 再帰を丸ごとスキップして親の移動量 $(\Delta x, \Delta y)$ のみを反映する。
   - **KPI**: showcase 幅変更 **`< 0.10〜0.20 ms`**（現 `0.931 ms`、Chrome `0.084 ms` に並び Swing `0.47 ms`・JavaFX `1.02 ms` を上回る）、`fullLayoutWarm` **`< 0.50 ms`**。
2. **局所更新ファストパス（`TEXT_PATCH` / `CHILD_PATCH`）の適用範囲拡大**:
   - **現状**:
     - `TEXT_PATCH`: ✅ **`button` / `label` は 2026-11-10 に開放済み**（[`UiView.isPatchableParent`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/UiView.java) の除外から外した）。検証: [`TextPatchTest.buttonAndLabelTextPatchMatchesFullRelayout`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/common/TextPatchTest.java)（Flex 行内の可変幅／固定幅ボタン・インラインラベルの折り返し変化・`updateTextContent`・複数同時を、全体再レイアウトと全項目一致で比較し、局所パッチ経路のヒット ≥ 6 を確認）、`:core:test` 全 1,847 件グリーン。**残り**: `td` / `th`（表レイアウトは `collectTextBoxes` が表内を退かせる）、`summary` / `legend` / `pre` は未開放。
     - `CHILD_PATCH`: `large-list` では 100% ヒットするが、`showcase` では **1,400 回中 0 回ヒット（100% フォールバック）** している。
   - **対策**: 固定幅または親寸法に影響しない `button` / `label` / `td` 内テキスト更新へ `TEXT_PATCH` を開放し、`CHILD_PATCH` の不成立理由カウンタを追加して Flex / Grid コンテナへの子要素追加・削除にも対応させる。
3. ✅ **【完了 2026-11-10・正しさ】文書サイズ上限の黙った切り捨ての解消（W8）**:
   - `DocumentLoader.parseDocument` から 128 KiB（HTML）・64 KiB（外部 CSS）の `substring` を撤去し、上限は `ParseLimits`（トークン数・深さ）に一本化。回帰テスト [`DocumentSizeNoTruncationTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/common/DocumentSizeNoTruncationTest.java)（5,000 行・20,000 行を欠落なく開く／64 KiB 超 CSS の末尾規則が残る）を追加、`:core:test` 全体グリーン。
4. **画面オープン（`open`）のさらなる高速化と JIT プリウォーム（W7 — 戦略 T6）**:
   - **現状**: 実測で showcase `6.2 ms`（Chrome `5.0 ms`）、2,000 行 `25.5 ms`（Chrome `19.8 ms`＝1.3 倍遅い）。コールド JVM の初回フレームは未計測。
   - **対策**: `StyleSheet` 雛形キャッシュ（S5）に続き、① HTML テンプレートの事前解析 `DocumentArena` 雛形クローン化（`MemorySegment.copy`）、② `sealNodeIds`（large-list で 6.5%）のパース時 1 パス統合、③ UA/共通スタイル解決結果の文書横断 `ShareCache` 共有、④ `EnderEngine.prewarm()` によるバックグラウンド非同期 JIT 暖機を導入する。

---

#### 🌌 優先度 P2：Phase C — Three.js 型 3D シーングラフのエンジン統合（戦略 T7-③）

- ブラウザ (Three.js + DOM) でも JavaFX でも不可能な**「同一レンダーパス・ゼロコピーで HTML/CSS 要素と 3D メッシュ・ライト・カメラ・レイキャストが双方向に融合する空間 UI エンジン」**を実現する。
- 詳細設計・メモリレイアウト・5 マイルストーン（`Scene3DArena`, `GlScene3DRenderer`, `SoftwareScene3DRasterizer`, `InstancedBatcher`, `GltfBinaryLoader`, `Raycaster3D`, `HTMLMesh`）は [`ENGINE_3D_ARCHITECTURE_PLAN.md`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/ENGINE_3D_ARCHITECTURE_PLAN.md) に定義済み。

---

#### 🌍 優先度 P3：実践 UI 仕様ギャップの解消（IME / 最新 CSS レイアウト / 自動仮想化 / SVG・Canvas — W9 & 戦略 T7-①）

1. **IME（かな漢字変換・インライン Preedit Composition）対応**:
   - [`TextEditing.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/TextEditing.java) と [`WebViewSurface`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/api/WebViewSurface.java) に IME 未確定文字列（Preedit 文節下線描画、`compositionstart` / `compositionupdate` / `compositionend` イベント、変換候補ウィンドウ座標 `getImeCursorRect()`）を追加し、日本語・多言語デスクトップアプリで JavaFX / Swing を完全に代替可能にする。
2. **CSS Top Layer（`<dialog>`, `popover`）& CSS Anchor Positioning**:
   - 現在 [`SelectPopup`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/SelectPopup.java) 専用になっているポップアップ機構を一般化し、親の `overflow: hidden` を突き抜けてターゲット要素に自動追従・画面端反転する `popover` / `<dialog>` / `anchor()` を実装する。
3. **CSS `content-visibility: auto` + `FenwickVirtualList` による 10 万件自動仮想スクロール（戦略 T7-①）**:
   - テスト層で検証済みの [`FenwickVirtualList`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/kernel/layout/FenwickVirtualList.java)（$O(\log N)$ 累積高さ索引）を [`BlockLayoutEngine`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/BlockLayoutEngine.java) の `content-visibility: auto` に接続し、20,000〜100,000 行の DOM でもビューポート内の数十件だけをスタイル・レイアウト・描画する（Chrome が 20,000 行 `fontsize` で `117 ms` かかる処理を **`< 0.5 ms`** に抑える）。
4. **CSS Grid `subgrid`・縦書き（`writing-mode: vertical-rl`）・インライン跨ぎ Bidi・カラー絵文字（`COLR`/`CPAL`）**:
   - [`GridLayoutEngine`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/GridLayoutEngine.java) の `subgrid` 対応、和風 UI・ノベルゲーム向けの縦書き対応、[`GsubTable`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/text/font/GsubTable.java) の文脈依存置換とカラー絵文字対応。
5. **`<svg>` へのページ CSS カスケード接続と `<canvas>` 機能拡充**:
   - [`SvgRenderer`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/raster/svg/SvgRenderer.java) に [`StyleResolver`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/StyleResolver.java) を接続して `var(--color)` や `:hover` を SVG 内部パスに効かせ、[`CanvasRenderingContext2D`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/raster/CanvasRenderingContext2D.java) の変形テキスト・`strokeText` を補完する。

---

#### 🛠️ 優先度 P4：開発体験（DX）の革新（型安全リアクティブ & 組み込み `EnderDevTools` — 戦略 T7-②）

1. **事前コンパイル済み型安全 `Signal<T>` / Java 21 `Record` バインディング（戦略 T7-②）**:
   - [`Bindings.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/dom/Bindings.java) の `{{...}}` 正規表現走査をテンプレート読み込み時の 1 回限りの AST コンパイルに置き換え、プリミティブ `IntSignal` / `FloatSignal` や Java `Record` を文字列変換なしで [`BindingPatchTable`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/kernel/compiler/BindingPatchTable.java) に直結する。
2. **スコープ付きコンポーネント（`@scope` + `<slot>`）**:
   - [`CustomTagExpander`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/CustomTagExpander.java) を拡張し、名前付き `<slot>` と `@scope` スタイル隔離を持つ再利用可能な Web Components ライクなコンポーネント機構を提供する。
3. **`F12` 組み込みインスペクタ「EnderDevTools」**:
   - [`StageProfiler`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/diag/StageProfiler.java)・[`PipelineStats`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/diag/PipelineStats.java)・[`HitTestIndex`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/HitTestIndex.java) を活用し、エンジン自身の UI オーバーレイとしてボックスモデル可視化・DOM/CSS ライブ編集・フレームタイム内訳グラフ・ホットリロードを標準提供する。

---

### 7.4 統合ロードマップ総覧

| フェーズ | 状態 | テーマ | 到達指標（KPI — Chrome 157 / Firefox 159 / Swing / JavaFX 比較） |
| :--- | :---: | :--- | :--- |
| **Milestone 1〜5** | ✅ **完了** | オフヒープ DOM・状態オートマトン・増分レイアウト・GPU Uber-Shader・タイル並列 CPU ラスタ | `:hover` **`0.0069 ms`（Chrome の 49 倍高速）**、`scroll` `0.002 ms / 0 B` |
| **Wave 0 & Wave 1** | ✅ **完了** | 公開 API (`EnderEngine`/`WebViewSurface`)・脱 `mc-*` SPI・脱 `static` (`EngineContext`)・`CssPropertyIndex` & `CssValue[]`・`LoadedFace` 並列化・GL 差分走査・測定半減 | `fullLayoutWarm` **`0.986 ms`**、`showcase` 規模で Chrome 同等以上、単体汎用 JAR として完全自立 |
| **Wave 1 基盤: 7 層参照モデルラチェット（§1.3）** | ✅ **完了（2026-11-10）** | [`LayerDependencyRatchetTest`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/test/java/com/nishiyu/enderframelib/core/LayerDependencyRatchetTest.java) で L1〜L6 の単方向性を固定。逆方向参照 11 系統を全て解消し `KNOWN_UPWARD` を空集合化（SPI 3 本 `BindingHooks`/`ColorSyntax`＋移設 `media.ImageSources`/`paint.DisplayItemFonts`） | 完了。以後は新規逆方向参照がビルドで即失敗するため、Wave 1.5 以降の機能追加はこの層規則の内側で行う（新規 SPI を足す場合も下位層が定義し上位層が実装する） |
| **Wave 1.5 (P0 & P1: 戦略 T1〜T6)** | 🎯 **進行中（①・②a・③・④ 完了。2026-11-11 再計測で効果確認・§6.7。残りは Wave 1.6 の R0〜R7 へ）** | **① ✅ 文書上限 128 KiB の黙った切り捨て解消（W8）<br/>② 🔧 空間カリング（T1: ペインタ段 `outsideClip`・ドライバ段 64 項目ブロック走査除外完了。次は `SCROLL_NODE` 区間カリング）+ 項目単位の保持 GPU 記録（T3）<br/>③ ✅ Segmented DisplayList スライス保持（P0-4）+ 64-bit 整数鍵化（P0-3 段階 1〜4 完遂）+ 増分索引シフト（P0-1）（T2: W2・W4・W6 解消）<br/>④ ✅ CPU Tile Dirty Bitset + Scroll Blit（P0-0b / T4: W3 解消）<br/>⑤ 4-Way 測定キャッシュ + `place` 制約スキップ（T5: W5 解消）<br/>⑥ DOM 雛形クローン + 文書横断 `ShareCache`（T6: W7 解消）** | ・2,000 行画面外 `hover` GL **`< 0.25〜0.7 ms`**（現 `13.1`、Chrome `0.55` を凌駕）<br/>・2,000 行 `child` **`< 0.5〜1.5 ms`**（現 `25.6`、Chrome `1.09` を凌駕）<br/>・CPU `hover` **`< 0.3〜0.8 ms`**（現 `4.4`、Chrome CPU `1.55`・Swing `0.64` を逆転）<br/>・showcase 幅変更 **`< 0.10〜0.20 ms`**（現 `0.93`、Chrome `0.084` 同等）<br/>・フレーム確保量 `fontsize` 級で `< 5 MB`（現 `33 MB`） |
| **Wave 1.6 (R0〜R7: 2026-11-11 再策定)** | 🎯 **R0・R1 完了、R2 部分達成（§6.10）、R3 は Wave 1.7 の施策 2・3 として進行** | ✅ R0 計測基盤補強（§6.8）→ ✅ R1 スタイル変更のスライスパッチ化（§6.9）→ 🔧 R2 `child`（命中 100%・確保削減まで。時間 KPI 未達 → Wave 1.7 の RTI・Paint 表で再判定）→ R3 全面無効化・幅変更・可視範囲限定 → R4 CPU 全面ダメージ解消 → R6 オープン → R5 GPU 記録（条件付き）／R7 ラチェット横串（§7.3 冒頭） | ・2,000 行 GL `hover` **`< 1.0 ms`**（✅ 達成）<br/>・2,000 行 GL `child` **`< 3 ms`**（現 `約 18`。未達）<br/>・500 行 `inherited` GL **`< 1.5 ms`**（現 `3.59`）<br/>・2,000 行 `fontsize` GL **`< 10 ms`**（現 `18.9`、Chrome `11.3`）<br/>・showcase CPU `hover` **`< 1.5 ms`**（現 `4.59`）／幅変更 **`< 0.2 ms`**（現 `1.04`）<br/>・Chrome 比: 全シナリオ 2 倍以内（現 最大約 17 倍） |
| **Wave 1.7 (2026-11-11 方針転換)** | 🎯 **着手前（M0 計測から）** | DoD 再設計（座標を持たない Paint 表）＋内容アドレス化（成形・スタイル・測定・配置の共有）＋反復ブロックのインスタンス化（RTI）。§8 | ・2,000 行 `child` GL **`< 0.5 ms`**（Chrome `1.09` の約 2 倍速）<br/>・2,000 行 `fontsize` **`< 5 ms`**、確保 `< 2 MB`<br/>・10 万行で `hover`/`text`/スクロール `< 0.3 ms` |
| **Wave 2 (P2: 戦略 T7-③)** | 📋 **設計完了** | Three.js 型 3D シーングラフ統合（[`ENGINE_3D_ARCHITECTURE_PLAN.md`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/ENGINE_3D_ARCHITECTURE_PLAN.md)） | HTML/CSS と 3D メッシュ・ライト・レイキャストの単一パス・ゼロコピー融合（ブラウザ Three.js では不可能な領域） |
| **Wave 3 (P3: 戦略 T7-①)** | 📋 **計画中** | IME Composition・Top Layer (`<dialog>`/`popover`) & Anchor Positioning・`content-visibility: auto` + `FenwickVirtualList` 10 万行仮想化・`subgrid`・縦書き | 20,000〜100,000 行リスト **`< 0.2〜0.5 ms`**（Chrome 20,000 行 `117 ms` の **200 倍以上高速**）、日本語デスクトップ UI 完全対応 |
| **Wave 4 (P4: 戦略 T7-②)** | 📋 **計画中** | コンパイル済み型安全 `Signal<T>` / `Record` 直結バインディング・`<slot>` コンポーネント・`F12` 組み込み `EnderDevTools` | 開発生産性と実行性能の双方で Web フロントエンド（React/CEF）および既存 Java UI ライブラリを凌駕 |

---

## 8. Wave 1.7 設計: DoD 再設計・内容アドレス化・反復ブロックのインスタンス化（2026-11-11）

> R2（§6.10）の未達を受けた根本的な作り直しの設計。**数値はすべて見積もり（未実装・未検証）。** 実測済みなのは §8.1 のみ。

### 8.1 診断（実測で確定した事実）

**現行表現の構造的欠陥**

| # | 欠陥 | 帰結 |
| :-- | :-- | :-- |
| F1 | 幾何の二重保持（`LayoutBox.x/y/w/h`＝float、`DisplayItem.x/y/width/height`＝int、`BoxMemo.nums`） | 挿入 1 回で 3 か所を同期。同期コードが `patchChildSlices` の大半 |
| F2 | AoS の太い不変レコード（`DisplayItem` 23 フィールド、`String`×3・`Map`・`RenderMeta` 参照） | 走査はポインタ追い、移動は再生成 |
| F3 | 同一スタイルの重複（行ごとに同じ色・半径・影・クリップを複製） | 2,000 行で同値を 2,000 回持つ |
| F4 | 索引が項目の同一性比較に依存（`patchIndexByDiff` / `patchIndexByShift` / `SoftwareRenderBackend`） | 後続が再生成されると増分索引が成立せず全面作り直し |
| F5 | ホットパスの Map 引き・`equals`・文字列キー（`rebuildIndex` の `AtomTable.intern`） | `paint(cpu)` の約 25% |

**D0 ベンチ**（`DodHypothesisBench`、JMH。後続 17,500 項目相当）

| 項目 | 時間 |
| :-- | :-: |
| 現行: 17,500 項目を `new DisplayItem` で作り直し（確保 2 MB） | 458 µs |
| SoA: Box 表 y 列の範囲加算（3,500 要素）／項目ごと y 列（17,500 要素） | **0.09 µs** ／ 1.6 µs |
| 現行: 20,000 項目の AoS 走査（可視 200 件出力）／SoA 全走査／**SoA 可視のみ（二分探索）** | 58.7 µs ／ 19.8 µs ／ **0.21 µs** |

SoA 全走査は AoS の 3 倍止まり。**可視限定（O(可視)）がなければ SoA 化だけでは大差が出ない。** また孤立ベンチの再生成 0.46 ms に対し本番 `itemShift` は約 7 ms で、差はヒープ上に散在したオブジェクトの読み出し（メモリ律速）である（§6.10）。

**下流の内訳**（2,000 行 `child`・GL・JFR 付き）: `paint(cpu)` 4.9 ms のうち索引の全面作り直し 61%（約 3 ms、文字列キー `intern` 25%）、`isNeutral` / `peekState`（文字列キーの状態引き）20%、保持範囲の走査 8%。CPU バックエンドは `paint` 18.3 ms・確保 20 MB/frame。

### 8.2 設計原則と目標データモデル（DoD 再設計）

原則: ① 幾何は一か所（Box 表）にだけ置き、表示側は添字で引く（遅延束縛）。② 変更は列単位（`float[]` の範囲加算、`int[]` の 1 要素）。③ スタイル由来の値は重複排除した Payload 表。④ 作業量は O(可視＋変更)。⑤ 毎フレーム確保 0（事前確保・世代番号）。⑥ 差分は dirty ビットセット／範囲で伝え、比較走査をしない。

```text
Box 表（SoA, 文書順）    float[] x,y,w,h（小数のまま） / int[] parent,firstChild,nextSibling / int[] styleSlot / long[] dirtyBits
Payload 表              スタイル由来の描画値（bg/border/radius/opacity/shadow/clip…）。ComputedStyle 単位で intern、行は番号だけ共有
Paint 表（SoA, 描画順）  int[] boxIdx / byte[] kind / int[] payloadIdx / long[] key（現行 64-bit 鍵を継承）。Box ごとに [paintStart,paintEnd)
GlyphRun 表             (boxIdx, fontSlot, glyphStart, glyphCount)。同一文字列・フォントなら成形結果を共有、原点は描画時に Box から加算
```

- 文字列・`Map`・`RenderMeta`・`AnimationSpec` は表に入れず、添字参照の別アリーナへ。
- **GPU（GPU-Driven）**: インスタンスを 80 B → 16 B（`boxIdx, kind, payloadIdx, flags`）にし、頂点シェーダが Box/Payload SSBO を引く。行の移動は Box SSBO の連続範囲だけ、`:hover` は Payload 1 行の書き換え。
- **CPU**: Tile Dirty の入力を「項目差分」から「dirty Box の旧・新矩形」へ。タイルの対象 Paint 行は y 範囲の二分探索。
- **座標の丸め**: 行の高さが小数のとき、後続の `y` は「旧＋`dy`」にならず、小数のボックス座標から丸め直す必要がある（項目だけずらす近道は全再構築との一致を壊す）。**Paint 表は小数の相対値を保持し、丸めは描画の最終段 1 か所（現行規則どおり）で行う。**
- 代替案（RTI が使えないブロック向けの保険）: ビルダーが `DisplayListDelta{spliceAt, removedLen, addedLen, shiftFrom, dx, dy}` を発行し、後続は再生成せず `ShiftTable`（Fenwick）で座標を読み出し時に解決、索引は差分駆動にする。**ビルダー・索引・バックエンドを同時に切り替える必要がある**（同一性比較に頼る既存コードが遅延実体化を全面更新と誤判定するため）。

### 8.3 内容アドレス化（Content-Addressed Pipeline）

各段の結果を入力の内容ハッシュで保存し、同じ入力なら再計算せず共有する。**コストは N（ノード数）ではなく D（異なる計算の数）に比例する。** 対応する他分野の技術: 内容アドレス型ビルドキャッシュ（Bazel / Nix）、hash-consing（等価判定を O(1) に）、増分計算（Salsa / Adapton）、永続データ構造（HAMT / rope / B 木）、GPU インスタンス描画、コンパイル型 UI（Svelte / Solid ＝ `BindingPatchTable`）。

| 段 | キー | 共有されるもの | 効く場面 |
| :-- | :-- | :-- | :-- |
| スタイル | (祖先の算出スタイル ID, セレクタ一致集合) | `ComputedStyle`（intern） | 継承変更（`fontsize` / `inherited`）、オープン |
| テキスト成形 | (文字列, フォント, 言語) → グリフ列・前進幅・**改行可能位置** | 成形結果（幅に依存しない） | 幅変更で再成形せず、改行は「幅 → 行分割」の**区間キャッシュ**（行数が同じ幅区間は同じ分割） |
| 測定 (intrinsic) | (部分木の構造キー, 制約) | min/max-content | 同形の部分木を別の場所でも共有（T5 の拡張） |
| 配置 | (部分木の構造キー, 利用可能幅) → **相対座標** | 部分木内レイアウト | 親が動いても子を更新しない |
| ペイント | (部分木の構造キー, 算出値) | テンプレート（相対座標・小数のまま） | カード・ボタン等の部品単位 |
| 字形 | (グリフ ID, フォント, サイズ) | アトラス位置 | 既存 `FontSet` アトラス |

**構造クラス判定**: `shape(node) = H(tag, 算出スタイルの正規化 ID, 描画に効く属性, [shape(child)…])`（文字の内容は含めない＝「穴」）。DOM 変更で変わったノードから祖先へ増分更新（O(深さ)）。`:nth-child` 等で縞模様になる場合は算出スタイルが違うので別クラスになる。

### 8.4 反復ブロックのインスタンス化（RTI）

同形の行（見た目が同じ）は 1 つのテンプレートを共有し、行 = (クラス ID, 位置の序数, 文字の参照) だけを持つ。位置は全行同高さなら `y0 + i×h`（Flutter の固定 extent と同じ O(1)）、高さが違えば Fenwick。描画は可視範囲（y の二分探索）の行だけ、テンプレート＋行の原点＋文字を送出（GPU はテンプレート 1 つ＋インスタンス属性）。1 行だけ `:hover`・インライン style になったら、その行だけ別クラス（変種）へ移す（コピーオンライト）。

| 操作（2,000 行・全行同一クラス） | 現状 | RTI（見込み） |
| :-- | :-: | :-: |
| `child` | 後続の再生成・再配置・索引再構築（GL 約 18 ms） | クラス判定＋配列挿入＋`i×h`。後続の項目・ボックスは存在しない。**0.2〜0.4 ms**（Chrome 1.09） |
| `fontsize` / `inherited` | 全行のスタイル解決・測定・配置（GL 18.9 ms、確保 25 MB） | クラスのスタイル 1 回の再解決＋テンプレートの再測定 1 回。**1〜3 ms**（Chrome 11.3） |
| `hover`（1 行） | 1.0 ms | 該当行をクラスから分離。O(1) |
| 幅変更 | 全行再測定 | 折り返しが起きるかの判定のみ（最大文字幅 ≤ 内容幅なら再測定ゼロ） |

**入れ子・長文**: 構造ハッシュは子の構造を含むので入れ子でも同クラスになる（一部だけ形が違う行は別クラス）。長文が折り返す行は高さが可変（Fenwick・O(log N)）で、折り返し計算は行の文字が変わるたびに必要（RTI では省けない）。効くのは挿入・削除・スタイル変更・全行にまたがる変更（成形結果をキャッシュ）。**適用外**（従来経路にフォールバック）: クラス数/行数が高いブロック、`float`・`position: absolute/sticky` が兄弟と相互作用、margin 相殺が行ごとに変わる場合。イベント・ヒットテスト・IME・選択は、行番号の逆引きと「必要になった行だけ実体化」で扱う。

### 8.5 Chrome 比の到達見込みと限界（正直に）

| シナリオ | 対 Chrome |
| :-- | :-: |
| 同形の行の挿入・削除・`hover`・全行スタイル変更 | **大幅に超える**（要素ごとにスタイル・レイアウト・ペイントを持つ Chrome は構造上届かない） |
| 不均一ページの局所更新（1 要素のテキスト・スタイル） | **超える**（既に `hover` 49 倍・継承 4.5 倍。下流の全走査排除で拡大） |
| 長文の折り返し・幅変更（全行再折り返し） | **超えうる**（成形を幅非依存・改行を区間キャッシュ。Chrome は幅変更で行ごとに再成形・再改行） |
| 全行の文字が毎フレーム変わる（ストリーミング） | 同程度〜わずかに下（成形が支配的。HarfBuzz(C++) 対 純 Java。並列化で緩和） |
| 初回のコールド描画（JIT 前）・全内容がユニーク | **劣る可能性が高い**（共有できる物が無い。`prewarm` / AOT で緩和） |
| 画像・フィルタ・影・ブラー重視 | Skia/GPU が有利（共有では減らせない。T3 の別軸） |

他のネックへの効き方: 後続再生成・索引全面作り直し（W2）、全面無効化（W6）、幅変更（W5）、`intern`（W4）、CPU バックエンド（W3 — 行ラスタ結果をテンプレート＋文字の内容キーでキャッシュして貼り付け）には効く。オープン（W7）は DOM 雛形クローン・テンプレート永続化で 2 回目以降のみ。GL ドライバ送出・画像デコード・フィルタは別手段が必要。

目標は「全面で超える」ではなく、**重複と不変が大半を占める現実の UI のほぼ全域で超え、最悪でも Chrome と同程度**。

### 8.6 実施計画（効果 ÷ 工数・各段で全再構築との一致テストを合否にする）

| 順 | 施策 | 内容 | 出口 KPI |
| :-: | :-- | :-- | :-- |
| 1 | **M0 計測**（読み取り専用） | 構造ハッシュで「クラス数/ノード数」と行高さの一様性を、長文・入れ子・不均一ページ（showcase 等）を含む実ドキュメントで測る | large-list は 1 クラス、showcase でも反復部分の 70% 以上がクラス化できるか |
| 2 | **テキスト成形の幅非依存キャッシュ＋改行区間キャッシュ** | 他と独立に導入でき全ページに効く（W5 の主因） | showcase 幅変更 `< 0.2 ms` |
| 3 | **`ComputedStyle` intern と (親スタイル, 一致集合) 共有** | S5（`StyleSheet` 雛形キャッシュ）の拡張 | 2,000 行 `fontsize` `< 10 ms`、500 行 `inherited` `< 1.5 ms` |
| 4 | **RTI M1**: 反復ブロックの検出と固定 extent レイアウト（`y_i = y0 + i×h`）、可視行のみ実体化 | 全再構築・差分レイアウトとの一致 | 2,000 行 `child` の layout＋display-list `< 1 ms` |
| 5 | **RTI M2/M3 ＋ Paint 表（D1〜D5）**: テンプレート＋インスタンス表、相対座標、Box 表の導入（`DisplayItem` は読み取り専用ビューで互換維持）、Fenwick、GPU インスタンス描画 | 旧経路 oracle との差分テスト（`DisplayListSlicePatchTest` 等を温存） | 2,000 行 `child` **`< 0.5 ms`**、`fontsize` `< 5 ms`、確保 `< 2 MB`、10 万行 `< 0.3 ms` |
| 6 | 永続データ構造化（行配列・表示リスト・索引） | 差分が「ポインタが違う部分木」だけになり、全項目の同一性比較が O(差分) | 不均一ページの構造変更 |
| 7 | クラス単位の並列化 | 成形・測定は独立。`ForkJoinPool` で並列（Chrome のレイアウトは主スレッド 1 本） | ユニーク内容・コールド |


#### 8.6.1 M0 の実測結果（2026-11-11・`ShapeClassMain`、読み取り専用。構造クラス = 算出スタイルと子の構造が同じ部分木、文字は無視）

| 文書 | ノード数 | 構造クラス数 | クラス/ノード | 反復列（同一構造が 3 つ以上連続）に含まれるノード | 備考 |
| :-- | :-: | :-: | :-: | :-: | :-- |
| large-list-2000 | 4,008 | 10 | **0.25%** | **99.8%**（1 列・高さ一様） | RTI が最大に効く典型 |
| nested-list（1,000 カード・入れ子・flex） | 9,007 | 14 | 0.16% | 99.9%（1 列・高さ一様） | 入れ子でも 1 クラスに畳める |
| long-text（1,000 段落・1〜5 倍長の文章） | 2,006 | 7 | 0.35% | 99.7%（1 列・**高さ不均一**） | 構造は同じでも位置は Fenwick が要る |
| striped（`:nth-child(odd)`） | 4,007 | 12 | 0.30% | **0%** | 構造は 2 クラスだが**交互**で、連続列の判定では検出できない。「クラス ID の列」として扱う必要がある |
| showcase（実際の多機能ページ） | 258 | 140 | **54%** | **15.5%**（3 列） | 反復は少ない |
| guidebook（Markdown 由来） | 247 | 70 | 28% | 17%（1 列・高さ不均一） | |

**判定**: リスト・カード一覧・テーブルのような「同形の繰り返し」では 99% 以上が数クラスに畳める（入れ子・長文でも成立）。一方、**実際の多機能ページ（showcase）では反復部分は 15% 程度**で、RTI だけでは効かない。したがって RTI は「リスト系で Chrome を大きく超える」手段であり、不均一ページには別の手段が要る。縞模様は連続列ではなく「クラス ID 列」で扱う設計に直す（§8.4）。

**JFR（2026-11-11・null バックエンド）が示した、施策 2 の前提への修正**:

| シナリオ | フレーム | 段別 |
| :-- | :-: | :-- |
| 2,000 行 `fontsize` | 20.2 ms（確保 25.6 MB） | restyle-local 3.0 ・ **layout 4.8（measure 3.3 + place 1.5）** ・ **display-list 9.6** ・ style+build 0.8 ・ box-tree 0.8 |
| 2,000 行 `inherited` | 9.6 ms（確保 19.8 MB） | restyle-local 3.0 ・ display-list 6.2 |
| showcase 幅変更（`resize`） | 1.41 ms | measure 0.50 ・ place 0.06 ・ display-list 0.59。サンプルは平坦（`ConcurrentHashMap.get`・`declaresContainers` 等）で、**`TextShaper` は支配的でない** |

→ **幅変更（W5）の主因はテキスト成形ではない**（成形は `TextShaper.collapseRuns` が数サンプル）。よって「成形の幅非依存キャッシュ」を W5 の主施策とする根拠は弱く、**施策 2 は長文ページの幅変更限定の効果として格下げ**する。`fontsize` / `inherited` は display-list（全面再構築）が最大で、ここは RTI または Paint 表でなければ半減できない。

#### 8.6.2 実装: 窓付きの表示リスト（Windowed DisplayList）— 2026-11-11

**狙い**: 項目数を可視範囲（画面＋余白 256px）に比例させ、後続再生成・索引全面作り直し・全面再構築のコストを「全体 N」から「窓」へ落とす。RTI（§8.4）の最初の段階として、**同形でないページにも効く**画面外の省略を、スクロール時だけでなく全ての描画に広げた。

**実装**（`DisplayListBuilder`）:
- 描画する箱が `WINDOW_MIN_OWNERS`（既定 800、`-Denderframe.paint.windowmin`）以上の文書は、画面と余白の外の箱の項目を作らない（既存のスクロール用カリング `offscreen()` を流用。`-Denderframe.paint.nowindow=true` で無効化）。省いた箱は `culledBoxes` に覚える。
- 窓付きのときは、部分木の保持（チャンク）を使わない（項目が欠けるため）。
- **画面外の箱への局所パッチ**（`text` / `style` / `hover`）: 項目を持たず今も画面外なら、表示リストに触れず成功扱い（`isCulledNoop`）。
- **子の増減**: `windowIntact` が、パッチ後も「項目を作った箱は全て窓の中、省いた箱は全て窓の外」を満たすかを先に確かめ、窓の出入りがあれば組み直しへ退く（全体を窓付きで組み直した結果と同じ並びを保つため）。追加された部分木も画面外なら項目を作らない。
- **既存の不具合の修正**: `match` が 2（箱が動いただけ）を返した箱は、記憶を動かさず項目だけ平行移動するため、記憶の位置と表示リストの項目の位置がずれる。差分パッチは「箱の今の位置 − 記憶の位置」で動きを見るので、**そのずれを二重に動かしていた**（窓付きの組み直しが頻繁になって初めて表に出た）。記憶に `appliedDx/appliedDy` を持たせ、パッチ側で足して見るようにした。

**検証**: `WindowedDisplayListTest`（① 局所パッチの結果が、毎回全体を窓付きで組み直した結果と 4 seed × 60 手順で完全一致、② 窓付きと窓なしの全画素が、スクロール・ホバー・挿入・削除・文字・スタイル変更 80 手順 × 3 seed で一致。行の高さは整数にしている。小数の高さではスクロール時の平行移動の丸めで ±1px ずれるが、これは窓なしの既存の挙動と同じ性質）、`:core:test` 全件グリーン。

**実測**（`FineProfileMain`、2,000 行 `large-list-2000`、GL・1,500 フレーム、平均 ms ／ Chrome 157）:

| シナリオ | 変更前 | 変更後 | Chrome | 判定 |
| :-- | :-: | :-: | :-: | :-: |
| `hover` | 1.06 | **0.26** | 0.55 | ✅ 超えた |
| `text` | 1.61 | **0.37** | 1.02 | ✅ 超えた |
| `style` | 1.04 | **0.24** | — | |
| `scroll` | 0.51 | **0.08** | — | |
| `fontsize` | 19.6 | **8.8** | 11.3 | ✅ 超えた |
| `inherited` | 15.9 | **4.9** | — | |
| `child` | 約 18 | **1.35**（§8.6.3 の追加後。窓付き直後は 2.25） | 1.09 | ❌ 約 1.2 倍（残りはレイアウト約 0.55 ms ＋ 測定 0.2 ms） |

500 行（`windowmin` 800 以下の閾値で窓付き）: GL `child` `2.99 → 0.61 ms`、`hover` `0.34 → 0.23 ms`。CPU バックエンド 2,000 行: `child` `38 → 3.3 ms`、`hover` `0.12 ms`、`text` `0.22 ms`、`fontsize` `10.5 ms`（確保 20 MB → 0.9 MB）。

**残り**: 2,000 行 `child` はレイアウトの後続 `y` 更新（約 1.4 ms）が律速。次は固定 extent（同形の行）の位置を `y0 + i×h` / Fenwick で持ち、後続を触らない（§8.6 施策 4）。`inherited` 4.9 ms は `restyle-local` 約 3 ms（継承の全面再スタイル）が律速で、スタイル共有（施策 3）の対象。

#### 8.6.3 続き: `child` の残りの削減（2026-11-11）

2,000 行 GL `child` を JFR で再分解（窓付き直後 2.25 ms）して潰した項目と、見送った項目。

| 項目 | 内容 | 効果 |
| :-- | :-- | :-: |
| `windowIntact` の高速化 | 窓の出入りの確認が全体の約 13% を占めていた。位置だけで決まる側（窓に掛かる箱は描く・掛からない箱は省く）を先に見て祖先の走査を避け、省いた箱の集合を `IdentityHashMap` から `ArrayList` に、文字の塊・匿名の箱は持ち主だけを数える | 2.25 → 約 1.75 ms |
| 後続の行の**平行移動**（`LayoutBox.layout`） | 原点が縦に整数ぶんだけ動き、幅が同じで、配置が前回と同じ純粋な通常フローの部分木は、置き直さず箱・断片・行の下端・前回の配置の記録ごと平行移動する（`shiftSubtree`。文字の塊の箱は位置を持たないので動かさない）。`-Denderframe.layout.noshift=true` で無効化 | 行の高さが整数の文書で効く。小数の高さ（既定のベンチ）では適用されない |
| `ScrollState` の未スクロール時の短絡 | レイアウトが箱ごとに 2 回引く同期付き弱参照の表を、一度もスクロールされていなければ見ない | 小（約 0.02 ms） |
| 合計 | | **2.25 → 約 1.35 ms**（Chrome 1.09） |

**平行移動を縦・整数ぶんに限った理由（実測で確定）**: 小数の移動量や横移動を許すと、`WidthOnlyReuseTest`・`TableIncrementalLayoutTest` が置き直した結果と一致しなくなった。置き直しは `((原点 + a) + b) + c` の順に浮動小数を足すが、平行移動は `(原点 + a + b + c) + dy` となり、**結合順が違うので 1 ulp ずれ、丸めの境目（x.5）で 1 画素変わる**ことがある。整数ぶん（かつ横が同じ）に限れば、これまでのテスト（全再構築との完全一致）をすべて満たす。**小数の高さの行（既定のベンチ）では、各行を置き直すコスト（約 0.55 ms）が残る。**

**残り（`child` 1.35 → Chrome 1.09 未満）**: ① 小数の高さの後続行の置き直し（約 0.55 ms）— 窓の外の行のレイアウトを遅延する（`deferScrollLayout` と同じ「読む側が現れたときに済ませる」仕組みの拡張）か、置き直しの浮動小数の順序を保つ高速化が要る。② 測定の `children` 全走査（約 0.2 ms）。③ `inherited` / `fontsize` は `restyle-local` が約 3 ms（`ComputedStyle.diff` と `StyleMap.equals` が支配的）— スタイルの共有（施策 3）。

#### 8.6.4 デザインが行ごとに違う場合の Chrome 比較（`varied-list-2000`、2026-11-11）

**文書**: `Workloads.variedList`。2,000 行（DOM 3,457 要素）。全行がインラインの `style` だけでデザインを決め（クラスで共有しない）、行番号から余白・背景・左右の枠・文字サイズ（% で相対）・文章の長さ（1〜6 倍、折り返す行を含む）・入れ子（バッジ・太字・サブ行）を変える。`:core` と Chrome は**同じ HTML ファイル**を読む（`Workloads` の `main` で書き出し、`trace.js` が `VARIED_FILE` で読む）。`large-list` と同じ #scroller（高さ 400px）内の `ul`。Chrome 157 headless・GPU 合成・全 CPU（main + raster + draw）、`:core` は GL バックエンド・平均 ms。

| シナリオ | `:core` GL | Chrome 全 CPU | 比 |
| :-- | :-: | :-: | :-: |
| `hover` | **0.31** | 0.81 | ✅ 2.6 倍速い |
| `style` | **0.33** | 0.80 | ✅ 2.4 倍速い |
| `text` | **0.68** | 2.16 | ✅ 3.2 倍速い |
| `scroll` | **0.10** | 0.55 | ✅ 5.5 倍速い |
| `child`（250 行目へ挿入・削除） | 5.45 | **3.67** | ❌ 1.5 倍遅い |
| `inherited`（body の color） | 11.8 | **3.55** | ❌ 3.3 倍遅い |
| `fontsize`（body の font-size） | 49.5（確保 70 MB） | **42.1** | ❌ 1.2 倍遅い |

**同形の `large-list-2000` の Chrome 値の訂正**: 従来の Chrome `child` `1.09 ms` は `trace.js` が**末尾へ追加・削除**する方法で、`:core`（250 行目への挿入・削除で後続 1,750 行が動く）と条件が違った。250 行目へ挿入する `child250` で測り直すと Chrome は **`1.98 ms`**。`:core` の `1.35 ms` は**同条件で Chrome を超えている**。varied の `child` は `child250` 同士（Chrome 3.67）で比べた。

**`:core` の段別内訳（varied・GL）と原因**:

| シナリオ | 内訳 | 原因 |
| :-- | :-- | :-- |
| `child` 5.45 | **layout.place 3.69** ・ measure 0.37 ・ display-list 0.28 | 行ごとに高さ・折り返しが違い、後続 1,750 行すべての置き直し（`placeLine`・小数の座標）が要る。平行移動の近道（縦・整数ぶん・同一幅）が効かない |
| `inherited` 11.8 | **restyle-local 7.5** ・ display-list 4.2（`canPatchBoxes` が理由 5 で退く） | 全行のスタイル再解決。変更ボックスにスクロールバーを持つ #scroller が含まれ、パッチが成立せず窓付きの全面再構築に落ちる |
| `fontsize` 49.5 | **layout.measure 26.0** ・ place 5.1 ・ restyle 7.6 ・ style+build 2.2 ・ box-tree 2.2 ・ display-list 3.8 | 全行の文章（折り返す長文を含む）を測り直す。成形・改行が支配的で、同形リストでは隠れていた |

**結論**: ① 局所更新（`hover`・`style`・`text`・`scroll`）は、デザインが違っても Chrome を 2.4〜5.5 倍上回る（窓付き表示リストは行の同形性に依らない）。② **全面に及ぶ変更（`inherited`・`fontsize`）と、後続が動く `child` は、デザインが違うと Chrome に劣る**。これらは共有できる部分が少ないため、RTI（§8.4）の適用外であり、**スタイル再解決そのものの高速化（`ComputedStyle` の共有・差分判定の短絡）と、テキスト成形・改行のキャッシュ（施策 2）、窓の外の行のレイアウトの遅延**が要る。§8.6.2 の「施策 2 は格下げ」は、長文を含むこの文書の `fontsize`（measure 26 ms）で**撤回**する（短い 1 行テキストの同形リストでのみ格下げだった）。

#### 8.6.5 メモリの比較（Chrome 157 レンダラ vs `:core`、2026-11-11）

**測り方**: Chrome は `mem.js`（`SystemInfo.getProcessInfo` でレンダラを列挙し、空ページから文書へ遷移したときに Private バイトが最も増えたプロセスを選び、PowerShell の `Get-Process` で Private / WorkingSet を読む。GC 後・3.5 秒待ち・各 4 回）。`:core` は `MemFootprintMain`（文書を開いて 5 フレーム描いたあと、GC 後のヒープ使用量の差。プロセス全体の Private 差も併記）。同じ HTML。

| 文書 | Chrome レンダラ Private（空ページ 約 28.5 MB からの増分） | Chrome DOM ノード / レイアウトオブジェクト | `:core` ヒープ保持 | `:core` プロセス Private 増分 |
| :-- | :-: | :-: | :-: | :-: |
| large-list-500 | ≈ 0〜+1（ばらつき内） | 1,012 / 1,008 | **2.7 MB** | 約 7（ばらつき大） |
| large-list-2000 | **+5**（約 33.7、外れ値 1 件除く） | 4,012 / 4,008 | **9.3 MB** | 測れず（ヒープの伸縮でばらつく） |
| varied-list-2000 | **+26**（約 55） | 7,080 / 7,361 | **60.2 MB** | 約 77 |

**読み方（注意つき）**:
- `:core` は文書あたり Chrome の **約 2〜2.3 倍**のメモリを持つ（同形 2,000 行で 9.3 vs +5 MB、デザイン違いで 60 vs +26 MB）。Chrome は同じ見た目の要素がスタイルを共有し、レイアウトオブジェクトも軽い。`:core` は全ノードに `ComputedStyle`・箱・記憶（`BoxMemo`）・成形済みの行を持ち、窓付き表示リストで減らしたのは表示項目だけ。
- 1 要素あたり: Chrome 約 6〜7 KB/ノード（デザイン違い。ノードには文字ノードを含む）に対し、`:core` は同形で約 2.4 KB/要素・デザイン違いで約 17 KB/要素。**同形では `:core` の方が小さい要素もあるが、スタイルが行ごとに違うと逆転する**（共有できない `ComputedStyle` が支配的と推定。要確認）。
- 比べられないもの: Chrome の値は**レンダラ 1 プロセスの Private**（ブラウザ・GPU・ユーティリティの各プロセスは含まない。合計では数百 MB）。`:core` はヒープ（JVM 自体のメタスペース・ネイティブは含まない）とプロセス Private の増分で、後者はヒープの伸縮でばらつく。`:core` のオフヒープ DOM（`DocumentArena`）の内訳は未取得。
- Chrome の空ページ基準（約 28.5 MB）は、`:core` では JVM・フォント・アトラスの土台に相当するが、`:core` 側の土台の Private は安定して測れていない。

#### 8.6.6 `ComputedStyle` の共有（hash-consing）と保持量の内訳（2026-11-11）

**実装**（`ComputedStyle.ofOwned` → `share`）: 宣言の値は文字列ごとに 1 つの正規のオブジェクト（`CssValue`）なので、内容が同じ（全プロパティの値の参照・フォントサイズ・根のフォントサイズ・`extras` が同じ）かは参照の比較で決まる。算出した直後に、内容のハッシュで弱参照の表（`ConcurrentHashMap<Interned, Interned>`）を引き、同じ内容の既存インスタンスがあればそれに置き換える。表は弱参照で、どの要素からも使われなくなったスタイルは回収される。`-Denderframe.css.noshare=true` で無効化。`:core:test` 全件グリーン。

**効果**:

| 項目 | 共有前 | 共有後 |
| :-- | :-: | :-: |
| varied-list-2000 のヒープ保持 | 60.2 MB | **51.8 MB**（−14%） |
| large-list-2000 のヒープ保持 | 9.3 MB | 9.3 MB（変化なし） |
| varied `inherited` GL | 11.8 ms | **9.6 ms** |
| varied `fontsize` GL | 49.5 ms | **43.8 ms**（Chrome 42.1） |
| varied `child` GL | 5.45 ms | **4.9 ms**（Chrome 3.67） |
| large `inherited` / `fontsize` / `child` GL | 5.1 / 8.8 / 1.35 ms | **3.8 / 6.6 / 1.21 ms** |

共有の命中（`MemFootprintMain`）: large-list は作成が 20 回しかなく（命中 11・新規 9。同形の行は**もともと別の経路でスタイルを共有していた**ため、効果が出ない）、varied は 5,939 回の作成のうち命中 3,441・新規 2,498（58%）。

**保持量の内訳**（`MemFootprintMain -Dmem.histo=1`、varied-list-2000・共有後、上位）:

| クラス | インスタンス | バイト | 内容 |
| :-- | :-: | :-: | :-- |
| `byte[]` + `String` | 240k + 240k | **10.2 + 5.8 MB** | 単語ごとの部分文字列（断片の文字） |
| `LayoutBox$InlineFragment` | **171,596** | **9.6 MB** | 単語（折り返しの単位）ごとの断片 |
| `CssValue[]` | 9,575 | 8.0 MB | 算出スタイル（約 2,500 に共有済み）・作業表・宣言の表 |
| `CssSyntax$Token` + `Preserved` | 74k + 74k | 3.5 + 1.2 MB | インライン style の解析結果の保持 |
| `LayoutBox` | 7,469 | 3.3 MB | |
| `LinkedHashMap$Entry` | 52k | 2.1 MB | 属性・インライン style の表 |
| `BlockLayoutEngine$LineItem` | 86k | 2.1 MB | 行の項目 |

**結論**: 算出スタイルの共有は、デザインが行ごとに違う文書でも速度（全面変更 −8〜20%）とメモリ（−14%）に効いたが、**メモリの主因ではなかった**。保持量の過半は **テキストの断片（単語ごとの `InlineFragment` と、その部分文字列 `String`）で約 25 MB**、次いで **CSS 解析結果（`Token`）と宣言の表**。次の削減対象は、断片の文字を元の文字列への範囲（開始・長さ）で持つ（部分文字列を作らない）こと、断片を単語でなく行単位にまとめること、インライン style の解析結果をスタイル確定後に捨てることである。

#### 8.6.7 保持量の削減（断片・文字列・キャッシュ、2026-11-11）

§8.6.6 の内訳（varied-list-2000）を、保持量の大きい順に潰した。`MemFootprintMain`（GC 後のヒープ保持）で測り、各段で `:core:test` 全件グリーン。

| 施策 | 内容 | varied-list-2000 | large-list-2000 |
| :-- | :-- | :-: | :-: |
| （開始） | §8.6.5 の値 | 60.2 MB | 9.3 MB |
| `ComputedStyle` の共有 | §8.6.6 | 51.8 MB | 9.3 MB |
| 語の文字列の共有（`TextShaper.sharedSegment`） | 折り返しの単位の語を、同じ綴りなら 1 つの `String` に（直接配置の表・確保なしで引く） | 約 −0（他の項目に隠れて小さい） | — |
| **CSS トークンの文字列の共有**（`CssSyntax.Tokenizer.pooled`） | 解析結果の記憶（`VALUE_CACHE`、最大 8,192 件）が持つ 7.4 万トークンの `value` / `unit` / `raw` を、同じ綴りなら共有。**記憶は 10.5 MB → 約 5 MB** | 43.6 MB | 9.3 MB |
| **未使用の生宣言キャッシュの廃止** | `StateFlags.computedStyleCache`（カスケード後の生宣言の表）は**書かれるだけで読まれていなかった**。要素ごとに約 1 KB 保持していた | 40.7 MB | 9.0 MB |
| **行の項目から断片オブジェクトを外す**（`LineItem`） | 行の記憶（`LineItem`）が、行の相対座標を持つ `InlineFragment` を別に持っていたため、語ごとに同じ大きさのオブジェクトが 2 つ残っていた。`LineItem` が断片の項目（箱・x・幅・高さ・文字・ベースライン・descent・親）を直接持ち、置くときだけ `InlineFragment` を作る | **39.0 MB** | **8.7 MB** |
| **合計** | | **60.2 → 39.0 MB（−35%）** | 9.3 → 8.7 MB（−6%） |

速度は退行なし（GL・平均 ms。large-list-2000 `child` 1.25 / `inherited` 3.6 / `fontsize` 6.5、varied `child` 4.3 / `inherited` 9.8 / `fontsize` 43.5）。

**残る内訳**（varied・39 MB）: `InlineFragment`（置いた結果・語ごと 8.6 万個・約 4.8 MB）、`CssValue[]`（約 5 MB）、`LayoutBox` 3.3 MB、`LinkedHashMap$Entry`（属性・インライン style）2 MB。

**`StyleTable` の保持元（特定済み）**: 状態の記憶 `StateStyleMemo.Entry(raw, computed)` が、要素ごとにカスケード解決後の生宣言の表 `raw`（`StyleTable`・配列 約 840 B）を保持している（約 3,000 件）。`raw` を内容で共有する案（弱参照の hash-consing）を試したが、**保持量は変わらなかった**（異なる内容が 2,196 件あり、行ごとにインライン style が違う varied では `raw` も行ごとに別物。同じ宣言の要素は、既存のスタイル共有 `styleShare` が先に共有している）。効果が無いので戻した。残る手は、**疎な表**（`raw` は 210 スロットのうち数十しか使わない）を ID と値の小さな配列で持つこと（見込み 約 1.5 MB・4%）だが、`raw` を読む経路（`StyleTable.copyOf`）が遅くなるため見送り。Chrome のレンダラの増分は同じ文書で +26 MB なので、**同じ文書で約 1.5 倍まで縮んだ**（開始時は約 2.3 倍）。

#### 8.6.8 再測定と原因の調査し直し（2026-11-11、§8.6.7 の直後）

**再測定**（2,000 行・GL 平均 ms ／ Chrome 157 全 CPU、`child` は Chrome も 250 行目へ挿入の `child250`）:

| シナリオ | large-list `:core` | Chrome | varied `:core` | Chrome |
| :-- | :-: | :-: | :-: | :-: |
| `hover` | **0.24** | 0.61 | **0.30** | 0.79 |
| `style` | **0.24** | 0.62 | **0.31** | 0.71 |
| `text` | **0.37** | 1.26 | **0.62** | 2.15 |
| `scroll` | **0.08** | 0.44 | **0.09** | 0.52 |
| `child` | **1.18** | 2.01 | 4.15 | **3.63** |
| `fontsize` | **6.2** | 11.6 | 43.6 → **28.5**（後述） | 43.6 |
| `inherited` | 3.9 | **2.31** | 9.6 | **3.57** |

**劣る 3 箇所の原因（JFR・段別、今回の調べ直し）**:

1. **varied `fontsize`（43.6 ms）— 原因は記憶の容量の崖だった（修正済み → 28.5 ms）**。`layout.measure` が 26.7 ms で、`FontFace.naturalAdvance`（書体の幅の計算）が全サンプルの 16%、`sharedSegment`・`LineBreaker.classify` が続く。段落の下ごしらえの記憶（`TextShaper.SEGMENTED`）と整形結果の記憶（`SHAPED`）は、**件数が上限 4,096 に達すると全てを消す**作りで、varied は段落 2,300 件 × 書体の大きさ 2 通り（往復する）= 約 4,600 件で上限を超え、**往復のたびに全段落を測り直していた**。2 世代の記憶（新しい世代が 8,192 件に達したら古い世代を捨てる。`Generational`）に変えた結果、`measure` 26.7 → 8.9 ms、確保 64 → 25 MB/フレーム、保持量は +約 4 MB（39 → 43 MB）。Chrome は同条件で 43.6 ms なので **Chrome の約 1.5 倍速い**。
2. **`inherited`（large 3.9 / varied 9.6 ms、Chrome 2.3 / 3.6）— 原因は 2 つ**。(a) `restyle-local` が 2.9 / 6.1 ms: `ComputedStyle.diff`（全スロットの比較）が最大（18%）。共有された算出スタイルは同じ（古い・新しい）の組が行ごとに何度も来るので、**組で結果を覚える**ようにした（`isLayoutAffectingChange` の記憶）。large は 2.9 → 2.4 ms。varied は行ごとに組が違うので効かない（6.1 ms のまま）。残りは `StyleStateAutomaton.compress`・`TextShaper.isAlreadyNormal`（文字の箱ごとに空白の畳み方を毎回確認）・`CssValue.dependsOnViewport`（生宣言ごとに全スロットを走査）で、いずれも要素ごとの定数コスト。(b) `display-list` が 0.84 / 3.3 ms: **`canPatchBoxes` が理由 5（スクロールバーを持つ箱）で毎フレーム退き**、窓付きの全面再構築に落ちている（変更ボックスに `#scroller`（overflow: auto）が含まれるため。`PaintOrder.factsOf`・`classify`・`hasUnscrollableDescendant`・`offscreen` が並ぶ）。スクロールコンテナの項目（`_scrollc` の動く節・スクロールバー）を含めて再生成できるようにすれば、この 0.84 / 3.3 ms は消える。
3. **varied `child`（4.15 ms、Chrome 3.63）— 原因は後続行の置き直し**。`layout.place` が 2.7 ms で、`placeLine`（断片を新しく作る 3 行）・`scanFloats`・`updateScrollExtent`・`arrangeFlow` が並ぶ。行の高さが小数だと §8.6.3 の平行移動の近道（縦・整数ぶん）が使えず、**後続 1,750 行ぶん・約 7.5 万個の断片を毎回作り直している**。large-list も同じ理由で `place` 0.46 ms が残るが、断片が少ない（1 行 6 語）ので目立たない。解決策は、窓の外の行のレイアウトを遅延するか、置き直しで既存の断片オブジェクトを再利用すること。

**副産物**: `TextShaper` の記憶の全消去は、段落 × 書体が 4,096 を超える文書なら、リサイズ・拡大縮小でも同じ崖を踏む（幅変更の `showcase` は小さいので影響なし）。

#### 8.6.9 メモリの再測定（2026-11-11、§8.6.8 の変更後）

Chrome は `mem.js`（空ページからの Private 増分、各 3 回の平均）、`:core` は `MemFootprintMain`（GC 後のヒープ保持。各 3 回で完全に一致）。

| 文書 | Chrome レンダラ Private（空ページ 26.2 MB から） | `:core` ヒープ保持 | 比 | `:core` プロセス Private 増分（Serial GC・ヒープ固定） |
| :-- | :-: | :-: | :-: | :-: |
| large-list-500 | 約 28.7（**+2.5**、ばらつき ±1.5） | **2.5 MB** | 約 1.0 倍 | 約 7〜9 MB |
| large-list-2000 | 約 34.9（**+8.7**、前回 +5。ばらつき ±3） | **8.7 MB** | 約 1.0 倍 | 測れず（-9〜+3 MB のばらつき） |
| varied-list-2000 | 約 54.4（**+28.2**、前回 +26） | **42.7 MB** | **約 1.5 倍**（開始時 2.3 倍） | **約 70〜75 MB** |

**読み方**:
- **ヒープ保持は、同形の文書で Chrome のレンダラの増分とほぼ同じ、デザインが違う文書で約 1.5 倍**。§8.6.7 の削減（60.2 → 39.0 MB）の後、§8.6.8 の整形記憶の 2 世代化（`fontsize` 往復の崖の解消）で **+3.7 MB（39.0 → 42.7 MB）**を払っている（速度 43.6 → 28.5 ms との引き換え）。
- **プロセスの Private 増分は、ヒープ保持よりずっと大きい**（varied で約 72 MB）。読み込み中の一時的なゴミ（レイアウト・成形の確保）がヒープの若い世代のページに触れた分と、オフヒープ（`DocumentArena`）・ネイティブの分を含む。既定の G1 では伸縮で 237 MB まで見える。**Chrome の Private（約 28 MB 増）と同じ尺度で比べると約 2.6 倍**で、ヒープ保持の比（1.5 倍）より悪い。読み込み時の確保の削減（一時的な確保の総量）が別の課題。
- 空ページの土台は比べられない（Chrome レンダラ 約 26 MB に対し、JVM はヒープ 5.4 MB・メタスペースなどのネイティブ領域が別）。

#### 8.6.10 読み込み時の確保（一時的なゴミ）の内訳と削減（2026-11-11）

**背景**: §8.6.9 で、ヒープ保持（42.7 MB）よりプロセス Private の増分（約 72 MB）が大きかった。差は読み込み中の一時的な確保。`FineProfileMain open varied-list-2000` を JFR の `ObjectAllocationSample` で見た（1 回の読み込みで **108 MB** 確保、77 ms。うち `style+build` 41.6 ms・measure 9.3・display-list 4.7・place 4.0・box-tree 3.4）。

**確保の上位（調査時・合計 36 GB 相当のうちの割合）**:

| 場所 | 割合 | 内容 |
| :-- | :-: | :-- |
| `StyleTable` の `CssValue[]`（約 840 B）×4 か所 | 約 10% | 要素ごとにカスケード結果の表を 4 つ作る（`resolveDeclarations`・`cascadeMatched`・`finalizeComputedStyle` の複製 ×2 経路） |
| `CssSyntax.serialize`・`appendSerialized`・`trim`・`splitOnWhitespace`（`ValueComputer.absolutizeUnits` 経由が大半） | **約 13.5%** | 値の中の寸法（`12px` など）を毎回直列化して文字列を作り直し、**置き換えが無ければ捨てていた** |
| `HtmlParser.TreeBuilder.normalizeText` | **約 6.3%** | 文字ノードごとに正規表現（`replaceAll` ×2）で空白を畳んでいた（畳む必要が無い文字列でも） |
| `LineItem` ＋ `InlineFragment` ＋ `ArrayList` | 約 9% | 語ごとの行の項目と置いた断片（必要な確保） |
| インライン style の解析（`parseDeclaration` の `Object[]`・`serialize`） | 約 5% | 行ごとに違うインライン style を宣言に分けて値を直列化 |

**対策**:
1. `ValueComputer.absolutizeUnits`: **置き換える寸法が 1 つも無ければ直列化しない**（先に値の木を走査して判定。反復子も作らない）。
2. `HtmlParser.normalizeText`: **改行・タブも連続する空白も無い文字列は正規表現を通さない**。

**効果**（`open varied-list-2000`、null バックエンド）: 確保 **108 → 約 93 MB（−14%）**、読み込み時間 77 → 約 65〜70 ms。`:core:test` 全件グリーン。

**測定上の注意**: プロセス Private の増分は GC の起きた位置で -20〜+75 MB と大きく振れ、削減の効果を示す尺度にならない（確保量そのものを指標にする）。ヒープ保持（42.7 MB）は変わらない。

**残る大きい確保**: `StyleTable` の表が要素ごとに 4 つ（約 3.3 GB 相当/10%）、語ごとの `LineItem`・断片、インライン style の解析。`StyleTable` は、結果の複製を省く（保持する生宣言と作業表を分ける設計の見直し）か、疎な表にすれば減らせるが、いずれも影響が広い。

#### 8.6.11 スクロール付きの箱を含む局所パッチ（2026-11-11）

**原因（§8.6.8-2b の対処）**: `inherited`（body の `color`）では変更ボックスに `overflow: auto` の `#scroller` が含まれ、`canPatchBoxes` が理由 5（スクロールバーを持つ箱）で退き、窓付きの全面再構築に落ちていた。

**実装**（`DisplayListBuilder.canPatchBoxes` / `scrollTraitsUnchanged`）: スクロールバーのつまみ・溝は箱自身のスライス `[dlStart, dlEnd)` の外（`PaintOrder` の after 層）に出る。そのため、**スクロールの有無・`overflow-x/y`・`scrollbar-width` が前回の記憶と同じ**なら、自身のスライスだけを差し替えればよい。変わるときと、記憶が無いとき、`animation` を持つときは従来どおり退く（理由 5）。

**検証**: `DisplayListSlicePatchTest.inheritedChangeWithScrollContainerPatchesAndMatchesFullRender`（スクロール付きの箱を含む継承色変更を 4 手順往復し、毎回全再構築と全項目一致、かつ `RESTYLE_REPAINT_PATCH` が命中）。`:core:test` 全件グリーン。

**実測**（`FineProfileMain inherited`・GL・1,500 フレーム、平均 ms）:

| 文書 | 変更前 | 変更後 |
| :-- | :-: | :-: |
| varied-list-2000 `inherited` | 9.6（display-list 3.3） | **9.35（display-list 1.18）**、確保 3.2 MB/フレーム |
| large-list-2000 `inherited` | 3.9（display-list 0.84） | **3.54（display-list 0.64）** |

スライスパッチ命中は 100%（`repaintPatch=2100`・`repaintFull=0`・フォールバック 0）。`inherited` の残りは `restyle-local`（varied 6.2 / large 2.2 ms）で、要素ごとの定数コスト（`StyleStateAutomaton.compress`・`TextShaper.isAlreadyNormal`・`CssValue.dependsOnViewport`）が支配的。次はここの短絡（継承値を使わない要素の再解決を省く）と、varied `child` の後続行の置き直し（断片の再利用・窓外のレイアウト遅延）。

R3（§7.3）は施策 2・3 とほぼ同一作業なのでそちらで進める。R2 の時間 KPI（`child` `< 3 ms`）は施策 4・5 の完了で再判定する。

#### 8.6.12 R7 の一部: スタイル・hover・継承・テキスト・子を混ぜた乱択列の一致テスト（2026-11-11）

**追加**: `DisplayListSlicePatchTest.randomMixedStyleHoverTextChildSequenceMatchesFullRender`。60 手順（固定シード）で、class 付け外し（`transition`・`opacity` によるスタッキング含む）・`:hover` の移動／解除・リスト全体の継承色変更（インライン style）・行のインライン背景・ボタンのテキスト更新・行の追加を混ぜ、**毎手順でパッチ経路と全再構築の全項目（key・type・座標・色・テキスト）が一致**することと、スタイル系の手順（10 回超）で `RESTYLE_REPAINT_PATCH` が命中することを確認する。従来の乱択列は子・テキストのみで、スタイル系は個別シナリオだけだった。

**結果**: 初回から全手順一致（不一致の発見なし）。`DisplayListSlicePatchTest` 13 件・`:core:test` 全件グリーン。

**調査して見送った項目**: `inherited` の残り（`restyle-local`、varied 6.2 / large 2.2 ms）の「継承値を使わない要素の再解決を省く」短絡は、コード確認の結果、現行構造では得が小さいと判断した。明示的に値を持つ要素の子孫は既に `propagationOf == NONE` で刈られている（`restyleBoxRecursive`）。残るのは「本当に継承で色が変わる全要素」の算出で、1 要素約 130 ns と下限に近い。これを下回るには継承値の遅延評価（Paint 表・§8.2 の DoD 再設計）が必要で、局所的な短絡では届かない。**数値による検証はしていない（コード上の判断）**。

次の候補: varied `child` の後続行の置き直し（断片の再利用・窓外のレイアウト遅延）、R4（CPU バックエンドの全面ダメージ化）。

#### 8.6.13 R4: CPU の差分描画が全面に戻る理由の計測と、volatile 項目の是正（2026-11-11）

**計測**: `SoftwareRenderBackend` に差分描画を諦めた理由の件数（`fullReasons()`、`FineProfileMain` の `DAMAGE-WHY` 行）を追加。showcase の CPU `hover`（1,500 フレーム）は `partial=0 / full=2101`、つまり**毎フレーム全面描画**だった。理由の内訳は、画像・入力欄・進捗バー等の volatile 項目（`volatileItem`）が約 100%。

**是正**: 同サイズの項目列では volatile 項目で全面に戻さず、毎フレーム「変わった」として扱う（その範囲だけ塗り直す）。項目数が変わるフレームの volatile 判定は従来どおり全面（未着手）。検証: `VolatileItemPartialRepaintTest`（入力欄・進捗バーを含むページでの hover・テキスト・値の乱択 50 手順×3 シードが、毎フレーム全面描画と全画素一致し、`partial > full`・`aborts == 0`。修正を外すと失敗することを確認）。`PartialRepaintPixelTest`・`DiffPatchPixelTest` 含む `:core:test` 全件グリーン。

**実測（showcase・CPU）**: `inherited` は `full=2101 → partial=2099` に変わったが、継承色で文字がほぼ全面で変わるため時間は `2.14 → 2.21 ms`（差なし）。`hover` は理由が **1=前フレームが動作中 / 6=動作中のアニメ項目**（各 1,050）に移り、`partial=0` のまま、`1.77 → 1.50 ms`（ばらつきの範囲）。**時間 KPI（`< 1.5 ms`）への寄与は未確認**。

**残り**: hover 先の要素が `transition` を持つため、遷移中は全面描画に戻る（現行設計では、動作中の項目の同一性が変わらないので差分判定できない）。対応には項目ごとの「前フレームで動作中だったか」の記録と、動作中項目を変更扱いにする設計が必要（次の候補）。不透明度・フィルターの層（`groupItem`）も、同サイズのフレームでは全面に戻したまま。

#### 8.6.14 R7: パッチ命中率と 1 操作あたり確保量のラチェット（2026-11-11）

**追加**: `PatchHitRateRatchetTest`（500 行リスト）。時間ではなくカウンタで退行を固定する。

| テスト | 要求 | 実測 |
| :-- | :-- | :-- |
| `hoverRoundTripAlwaysPatchesWithoutFullRebuild` | hover 付与・解除 80 操作で `RESTYLE_REPAINT_PATCH` が 80（100%）、`RESTYLE_REPAINT_FULL` が 0、確保 `< 50 KB`/操作 | patch=80・full=0・**約 6 KB/操作** |
| `classToggleAlwaysPatchesWithoutFullRebuild` | クラス付け外し 80 操作で同上（命中 100%・全面 0） | 合格 |
| `childAppendAlwaysHitsChildPatch` | 行の追加 20 操作で `CHILD_PATCH_HITS` が 20、フォールバック 0、確保 `< 2.5 MB`/操作 | hits=20・fallbacks=0・**約 740 KB/操作** |

上限は実測の約 3〜8 倍。全面再構築へ落ちる、またはパッチ未使用になる退行は、命中数・フォールバック数で即失敗する。`:core:test` 全件グリーン。

**R7 の残り**: 継承色変更（`inherited`）・テキスト更新のカウンタラチェット、`AllocationRatchetTest` 系の 2,000 行版。**未実施**。

### 8.7 約束事とリスク

1. キーは「入力の正規化ハッシュ」。衝突は `equals` で必ず確認する（見た目が変わる誤共有を許さない）。
2. すべての中間結果は不変（共有できる前提）。座標は小数の相対値で保持し、丸めは描画の最終段 1 か所。
3. 「共有なし・全再計算」と同じ結果になることを、ランダム変異列＋実ドキュメントで毎段検証する。クラス数が多い／小さな部分木は自動で共有を止める（ハッシュ計算が計算を上回る場合は共有しない。閾値は M0 で決める）。

| リスク | 対策 |
| :-- | :-- |
| 影響範囲が広い（GL・CPU・HitTest・PaintOrder・テスト） | `DisplayItem` を読み取り専用ビュー（フライウェイト）として互換維持。段ごとに旧経路 oracle と差分テスト |
| `jdk.incubator.vector` が incubator | 17,500 要素ならスカラ版でも数十 µs の見込み。必須にしない（D0 で実測） |
| `transition`・アニメ中の補間値（Payload の付け替えで表現不可） | アニメ中ノードは専用のローカル Payload スロットを動的確保（少数） |
| スタッキングコンテキスト・`transform` と描画順 | Paint 表の順序は現行 `PaintOrder` の出力を採用。`transform` / スタッキング / sticky / アニメ中の範囲は従来経路へフォールバック |
| 時間ベンチが全件実行で不安定（既知） | 時間でなく確保量・パッチ命中・走査項目数のカウンタをラチェット化（R7） |

---
