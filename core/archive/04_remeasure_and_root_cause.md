# 旧 §9〜§10: 2026-11-12 の再計測・「なぜ遅いのか」の再考察

> 読み取り専用の履歴。[索引](../ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md)へ戻る。本文中の `§6-N` は索引ファイルの「付録 3」の項番。数値は当時の値。現行の指示は計画書（`../ENGINE_REARCHITECTURE_PLAN.md`）だけを正とする。

---

## 9. 2026-11-12 再計測: 現行コードのボトルネック特定と改善点

### 9.1 結論

現行コードは「一律に遅い」状態ではない。2026-11-12 時点の実フレーム計測では、**同形の大規模リスト（`large-list-2000`）は `hover`・`child`・`fontsize` のすべてで Chrome と同等以上**（GL `0.30` / `1.44` / `7.63 ms`、Chrome `0.55` / `1.98` / `11.3`）。一方で、次の 3 群が残存する。

1. **行ごとにデザインが違う `varied-list-2000` の `child` と `inherited`**: GL `5.15 ms`（Chrome `3.67`・1.4x）と `10.20 ms`（Chrome `3.55`・2.9x）。`fontsize` は `30.77 ms` で Chrome `42.1` より速いが、確保量が `25.8 MB`/フレームと大きい。
2. **showcase の幅変更**: 実フレーム `1.045 ms`、JMH `layoutWidthChange` `0.777 ms`。Chrome `0.0835 ms` の 9〜12 倍。表示リストの再構築（`0.421 ms`）と測定（`0.375 ms`）がほぼ半々。
3. **CPU バックエンドの transition 中の全面再描画**: showcase `hover` は `1.77 ms` のうち paint が `1.64 ms`（93%）。`partial=0 / full=1801` で、理由は「前フレームが動作中」「動作中のアニメ項目」。`inherited` は `2.62 ms` で部分描画が効いている（`partial=1599 / full=2`）。

ただし、これは「全項目で勝った」という意味ではない。large 500 行の `inherited`/`hover`/`style`、2,000 行 `text`、`open`、メモリ保持量、p99 は依然として負けているか未計測である（§9.8.1）。

JFR の実行サンプルから、残存ボトルネックは次の 4 系統に分かれた。

- **A. パッチ判定の O(変更箱 × 祖先) 走査**（`varied inherited` の `canPatchBoxes` 26.4%）。パッチ自体は命中しているのに、判定が高い。
- **B. 後続行の置き直しと断片の再生成**（`varied child` の `placeLine` 26.3%、`ArrayList.add` 15.0%）。1,750 行の `InlineFragment` を作り直す。
- **C. 要素ごとのスタイル等価判定・ビューポート依存判定・テキスト成形**（`varied fontsize` の `Arrays.equals` 7.9% + `StyleMap.equals` 8.5%、`dependsOnViewport` 3.6%、`LineBuilder.newLine` 4.5%）。
- **D. 絶対座標を焼き込んだ表示項目の再生成**（showcase resize の display-list `0.421 ms`、`MATCHFAIL` reason 4 が `111.9/163.9`）。

以下、計測方法、全数値、JFR のホットフレーム、コードとの対応、優先改善 P1〜P8 を示す。

### 9.2 計測環境と方法

| 項目 | 内容 |
| :-- | :-- |
| 実行環境 | Windows / JDK 21 / `:core` 現行 HEAD（`dbcd523` 以降） |
| 画面 | `800×600`（一部 `700×600`・`600×600`）、`FontResolver.FALLBACK` |
| 実フレーム | `FineProfileMain`（`StandaloneView.renderFrame` 全体）。ウォームアップ 600 フレーム、計測 400〜1,200 フレーム。平均・p50・確保量・段別（style+build / box-tree / restyle-local / measure / place / display-list / paint） |
| バックエンド | `null`（レイアウト＋表示リストのみ）、`gl`（LWJGL）、`cpu`（ソフトウェア） |
| JMH | `PipelineBench.layoutWidthChange`（showcase、1 fork、3 warmup、5 iteration、`-prof gc`） |
| JFR | `jdk.ExecutionSample`、スタック深さ 8。`jfr print` のテキストを集計（実行サンプル数は各表に記載） |

再現コマンド:

```powershell
.\gradlew.bat :core:profile "-Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain" "-Pprofile.args=child varied-list-2000 500 gl"
.\gradlew.bat :core:jmh "-Pjmh.args=PipelineBench.layoutWidthChange -p doc=showcase -f 1 -wi 3 -i 5 -prof gc"
.\gradlew.bat :core:profile "-Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain" "-Pprofile.args=inherited varied-list-2000 500 null" "-Pprofile.jfr=build/prof/varied-inherited.jfr"
```

保存した JFR: `build/prof/varied-fontsize.jfr`、`build/prof/varied-inherited.jfr`、`build/prof/varied-child.jfr`、`build/prof/showcase-resize.jfr`。集計は `jfr print --events jdk.ExecutionSample --stack-depth 8` の出力をスクリプトで exclusive / inclusive に集計した。

**注意**: 実フレームの平均は GC・JIT・OS の影響を受ける。以下の差分は同一環境・同一日に取得したが、±10〜15% のばらつきを含む。JFR の割合は実行サンプルに対するもので、フレーム時間の割合ではない（サンプル数が少ない showcase resize は参考値）。

### 9.3 実フレーム再計測の全体像

| 文書 / 操作 | backend（frames） | mean / p50 (ms) | 確保 (KB/frame) | 支配的な段 (ms) | 比較 | 判定 |
| :-- | :--: | :--: | :--: | :-- | :-- | :--: |
| large-list-2000 `hover` | GL (800) | `0.300` / `0.286` | `39` | restyle `0.113` / display `0.002` / paint `0.167` | Chrome `0.55` | ✅ |
| large-list-2000 `child` | GL (800) | `1.435` / `1.387` | `938` | measure `0.213` / place `0.576` / display `0.136` / paint `0.208` | Chrome `child250` `1.98` | ✅ |
| large-list-2000 `child` | null (800) | `1.126` / `1.048` | `878` | measure `0.209` / place `0.496` / display `0.137` | — | ✅ |
| large-list-2000 `inherited` | GL (700) | `3.497` / `3.426` | `1,621` | restyle `2.106` / display `0.620` / paint `0.334` | 前 `3.54`（§8.6.11） | 🔧 |
| large-list-2000 `fontsize` | GL (800) | `7.634` / `7.616` | `7,331` | style `0.436` / box `0.459` / restyle `1.730` / measure `2.302` / place `0.916` / display `0.883` / paint `0.422` | Chrome `11.3` | ✅ |
| varied-list-2000 `child` | GL (500) | `5.147` / `5.041` | `4,891` | measure `0.451` / place `3.050` / display `0.316` / paint `0.397` / unaccounted `0.934` | Chrome `3.67` | ❌ 1.4x |
| varied-list-2000 `child` | null (600) | `4.789` / `4.613` | `4,818` | measure `0.461` / place `3.198` / display `0.301` / unaccounted `0.829` | — | ❌ |
| varied-list-2000 `inherited` | GL (500) | `10.200` / `10.028` | `3,157` | restyle `6.532` / display `1.311` / paint `0.579` / unaccounted `1.777` | Chrome `3.55` | ❌ 2.9x |
| varied-list-2000 `inherited` | null (600) | `9.691` / `9.506` | `2,456` | restyle `6.616` / display `1.337` / unaccounted `1.738` | — | ❌ |
| varied-list-2000 `fontsize` | GL (400) | `30.768` / `30.411` | `26,072` | style `1.638` / box `2.214` / restyle `6.794` / measure `9.588` / place `4.690` / display `3.116` / paint `0.616` | Chrome `42.1` | ✅ |
| varied-list-2000 `fontsize` | null (600) | `30.848` / `30.207` | `25,826` | style `1.715` / box `2.160` / restyle `7.089` / measure `9.707` / place `4.810` / display `3.214` | — | ✅ |
| showcase `resize` | null (—) | `1.045` / `0.953` | `1,456` | style `0.077` / box `0.060` / measure `0.375` / place `0.043` / display `0.421` | Chrome `0.0835` | ❌ 12.5x |
| showcase `hover` | CPU (1200) | `1.767` / `1.736` | `285` | restyle `0.036` / display `0.056` / paint `1.640` | Chrome CPU `1.55` | ❌ |
| showcase `inherited` | CPU (1000) | `2.621` / `2.559` | `879` | restyle `0.240` / display `0.136` / paint `2.192` | 前 `2.21` | ✅ |

**パッチ命中**: large `hover` は slice-patch `1,400`、large `inherited` は `repaintPatch=1,300 / repaintFull=0`、large `child` は `childPatch=1,400 / fallback=0`。いずれも命中率 100% で、**「パッチが当たらない」ことはもう主要因ではない**。残っているのは判定・レイアウト・表示項目の再生成コストである。

**large `child` の表示項目数**: 窓付き表示リストにより画面内の `322` 項目だけを生成する。Chrome の `child250` と同条件で比較すると、`1.44 ms` は Chrome `1.98 ms` を下回る。

**showcase CPU のばらつき**: `hover` の `1.77 ms` は §8.6.13 の `1.50〜1.77 ms` の上端、`inherited` の `2.62 ms` は前回 `2.21 ms` から +18% だが、目標 `< 4 ms` は満たす。CPU の値はタイルのダーティ状態と transition の位相に依存するため、±20% の幅で読む。

### 9.4 JMH: 幅変更（`layoutWidthChange`）

showcase の幅を 1px 交互に変える JMH の 5 iteration:

| iteration | 1 | 2 | 3 | 4 | 5 | 平均 |
| :-- | :--: | :--: | :--: | :--: | :--: | :--: |
| ms/op | `0.745` | `0.725` | `0.776` | `0.818` | `0.821` | **`0.777`** |

確保量は `1,175,600 B/op`（約 `1.18 MB/op`）。前回の `1.039 ms` から **−25%**。Chrome `0.0835 ms` に対しては **9.3 倍**で、依然 W5 である。

JMH はレイアウトのみ（表示リストを含まない）。実フレームの `1.045 ms` との差 `約 0.27 ms` が表示リスト再構築とフレーム周辺のコストに相当する。内訳では display-list `0.421 ms` が最大で、measure `0.375 ms` が続く。**幅変更の改善は「レイアウトの測定再利用」と「表示項目の平行移動再利用」の 2 本立てで進める**。

### 9.5 JFR ホットフレーム

JFR はすべて `null` バックエンド（レイアウト＋表示リスト）で取得した。GL 送出・CPU ラスタは含まないため、描画段のボトルネックは §9.3 の段別と §9.6.6 で補う。サンプル数は `fontsize` 2,139（十分）、`inherited` 692・`child` 339（傾向を読むには足りるが ±数% の誤差を含む）、showcase resize 77（参考値）である。

#### 9.5.1 `varied-list-2000` `fontsize`（2,139 サンプル）

| 種別 | 上位フレーム | 割合 |
| :-- | :-- | :--: |
| exclusive | `java.util.Arrays.equals(Object[], Object[])` | **7.9%** |
| exclusive | `BlockLayoutEngine$LineBuilder.newLine` | 4.5% |
| exclusive | `BlockLayoutEngine.placeLine` | 4.1% |
| exclusive | `CssValue.dependsOnViewport` | 3.6% |
| exclusive | `BlockLayoutEngine.arrangeFlow` | 3.4% |
| exclusive | `DisplayListBuilder.offscreen` | 3.1% |
| exclusive | `String.hashCode` | 2.5% |
| exclusive | `BoxMeasurer.measure` | 2.5% |
| exclusive | `BoxTreeBuilder.restyleGeneratedBoxes` | 2.3% |
| exclusive | `BlockLayoutEngine.contentBlockSize` | 2.2% |
| inclusive | `BoxMeasurer.measure` | **25.0%** |
| inclusive | `RenderPipeline.restyleBoxRecursive` | **24.2%** |
| inclusive | `LayoutBox.measureCore` | 20.1% |
| inclusive | `BlockLayoutEngine.arrangeFlow` / `arrangeChildren` | 14.2% |
| inclusive | `ComputedStyle$StyleMap.equals` | 8.5% |
| inclusive | `java.util.Arrays.equals` | 7.9% |
| inclusive | `BlockLayoutEngine.placeLine` | 5.8% |
| inclusive | `RenderPipeline.styleEntry` | 5.1% |
| inclusive | `PaintOrder.collectChildren` / `classify` | 5.0% |
| inclusive | `RenderPipeline.layoutDocument` | 4.7% |

**読み方**: 2 つのクラスタがある。① **スタイル等価判定**（`Arrays.equals` 7.9% + `StyleMap.equals` 8.5% + `String.hashCode` 2.5% + `dependsOnViewport` 3.6%）。② **テキスト成形・行組み**（`newLine` 4.5% + `placeLine` 4.1% + `arrangeFlow` 3.4% + `measure` 2.5% + `contentBlockSize` 2.2%）。`BoxMeasurer.measure` の inclusive 25.0% は、測定そのものより下流のテキスト・行組みを含む。`DisplayListBuilder.offscreen` 3.1% は、全面再構築時にも画面外判定が走ることを示す。

#### 9.5.2 `varied-list-2000` `inherited`（692 サンプル）

| 種別 | 上位フレーム | 割合 |
| :-- | :-- | :--: |
| exclusive | `DisplayListBuilder.offscreen` | **20.4%** |
| exclusive | `java.util.Arrays.equals(Object[], Object[])` | **10.5%** |
| exclusive | `CssValue.dependsOnViewport` | 6.4% |
| exclusive | `StateLookup.nearestSizeContainer` | 5.9% |
| exclusive | `DisplayListBuilder.isCulledNoop` | 5.8% |
| exclusive | `BoxTreeBuilder.restyleGeneratedBoxes` | 5.2% |
| exclusive | `ComputedStyle.diff` | 5.1% |
| exclusive | `TextShaper.isAlreadyNormal` | 3.8% |
| exclusive | `StyleAnimator.hasValue` | 3.6% |
| exclusive | `RenderPipeline.restyleBoxRecursive` | 3.3% |
| inclusive | `RenderPipeline.restyleBoxRecursive` | **64.6%** |
| inclusive | `DisplayListBuilder.isCulledNoop` | **26.4%** |
| inclusive | `DisplayListBuilder.canPatchBoxes` | **26.4%** |
| inclusive | `DisplayListBuilder.offscreen` | 20.7% |
| inclusive | `DisplayListBuilder.patchBoxes` | 14.0% |
| inclusive | `java.util.ArrayList.removeIf` | 11.7% |
| inclusive | `ComputedStyle$StyleMap.equals` | 10.5% |
| inclusive | `RenderPipeline.cacheStyle` | 10.1% |
| inclusive | `RenderPipeline.styleEntry` | 10.0% |
| inclusive | `RenderPipeline.dependsOnViewport` | 6.8% |

**読み方**: 最大の発見は、`inherited` の**実行サンプルの 26.4% が `canPatchBoxes` の判定**（`isCulledNoop` 26.4%、`offscreen` 20.7%）にあること。**スタイル再解決そのものより、パッチ可否の判定が重い**。しかも `UiView` が `canPatchBoxes` を呼んだ直後に `patchBoxes` を呼び、その中で `canPatchBoxes` をもう一度呼ぶため、**同じ高コスト判定が 1 パッチにつき 2 回走る**。`patchBoxes` の 14.0% はこの 2 回目の判定と適用を含む。`offscreen` は変更箱ごとに祖先をたどり、8 つの移動プロパティを文字列で照合する。`canPatchBoxes` はさらに `ArrayList`/`LinkedHashSet` を作り、全箱をソートする。`ArrayList.removeIf` の 11.7% も含め、判定と適用の両方が高い。

#### 9.5.3 `varied-list-2000` `child`（339 サンプル）

| 種別 | 上位フレーム | 割合 |
| :-- | :-- | :--: |
| exclusive | `java.util.ArrayList.add(Object, Object[], int)` | **15.0%** |
| exclusive | `BlockLayoutEngine.arrangeFlow` | 12.7% |
| exclusive | `LayoutBox.updateScrollExtent` | 8.8% |
| exclusive | `StringLatin1.indexOfNonWhitespace` | 8.0% |
| exclusive | `BlockLayoutEngine.scanFloats` | 6.8% |
| exclusive | `DisplayListBuilder.windowIntact` | 6.2% |
| exclusive | `BlockLayoutEngine.placeLine` | 5.6% |
| exclusive | `LayoutBox.layout` | 5.0% |
| exclusive | `LayoutBox.resetPlacements` | 2.7% |
| exclusive | `DisplayListBuilder.patchChildSlices` | 1.2% |
| inclusive | `LayoutBox.layout` | **65.8%** |
| inclusive | `BlockLayoutEngine.arrangeFlow` / `arrangeChildren` | **65.5%** |
| inclusive | `BlockLayoutEngine.placeLine` | **26.3%** |
| inclusive | `UiView.tryChildPatchReason` | 18.0% |
| inclusive | `java.util.ArrayList.add` | 15.0% |
| inclusive | `LayoutBox.updateScrollExtent` | 8.8% |
| inclusive | `String.isBlank` | 8.3% |
| inclusive | `DisplayListBuilder.patchChildSlices` | 8.0% |
| inclusive | `DisplayListBuilder.windowIntact` | 7.1% |
| inclusive | `DisplayListBuilder.canPatchChildSlices` | 7.1% |

**読み方**: レイアウトが 65.8% を占め、そのうち `placeLine` が 26.3%。`ArrayList.add` 15.0% は後続行の `InlineFragment` 追加、`updateScrollExtent` 8.8% は行ごとのスクロール範囲更新、`String.isBlank` 8.3% は行の空白判定、`scanFloats` 6.8% は浮動子の有無の走査。**子パッチの判定（`windowIntact` / `canPatchChildSlices`）は 7.1% で、主因ではない**。ボトルネックは「1,750 行を実際に置き直す」ことにある。

#### 9.5.4 showcase `resize`（77 サンプル、参考値）

サンプル数が少ないため結論には使わないが、`TextShaper.collapseRuns` 9.1% exclusive、measure 系 31% inclusive、`drawBoxSelfMemo` 24.7% inclusive、`FlexLayoutEngine.resolveFlexibleLengths` 5.2% が上位。幅変更でテキストのラン統合が再実行され、測定キャッシュのミス（`BoxMemo` exact 62.1 / miss 163.9 / unmemoizable 13）が起きていることと整合する。

### 9.6 コードとの対応（原因の特定）

#### 9.6.1 `canPatchBoxes` / `isCulledNoop` / `offscreen` の判定コスト

[`DisplayListBuilder.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/DisplayListBuilder.java) の `canPatchBoxes`（約 1288 行）は、変更箱のコレクションを受け取り、各箱について `isCulledNoop`（約 1269 行）を呼ぶ。`isCulledNoop` は `offscreen`（約 341 行）を呼び、`offscreen` は箱の祖先をたどりながら `MOVING_PROPS` の 8 プロパティを `ComputedStyle` から文字列で `get` し、`trim` と `equalsIgnoreCase` で比較する。さらに `canPatchBoxes` は `ArrayList` と `LinkedHashSet` を作り、全箱をソートしてから判定する。

これは **O(変更箱 × 祖先数 × 8 プロパティ)** で、変更箱が多い `inherited` では支配的になる。JFR の `offscreen` 20.4% exclusive / `canPatchBoxes` 26.4% inclusive がそれを裏付ける。さらに、呼び出し側の [`UiView.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/UiView.java) は `canPatchBoxes` で判定した直後に `patchBoxes` を呼び、`patchBoxesUnprofiled`（約 1351 行）が先頭で `canPatchBoxes` を再実行する。**同じ判定が 1 パッチにつき 2 回**走る。**「画面外か」「移動する祖先があるか」は箱ごとの静的な性質**なので、フレームごとに祖先をたどり直す必要はない。`StyleFacts` にビットとして持たせるか、変更箱の集合を作る段階で 1 回だけ計算してキャッシュすればよい。まず 2 回目の判定をなくすだけでも半分になる。

#### 9.6.2 `placeLine` と `InlineFragment` の再生成

[`BlockLayoutEngine.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/BlockLayoutEngine.java) の `placeLine`（約 1329 行）は、後続行の `InlineFragment` を新しいオブジェクトとして生成し、`ArrayList.add` で箱の `inlineFragments` に追加する。行ごとに高さ・折り返しが異なる `varied-list-2000` では、小数の行高さのため `shiftSubtree` の平行移動近道が効かず、1,750 行すべてを置き直す。`resetPlacements`（[`LayoutBox.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/LayoutBox.java) 約 1283 行）は断片リストをクリアして全子を歩く。`updateScrollExtent`（約 1907 行）は行ごとに呼ばれ、`scanFloats` は各ブロックで浮動子の有無を走査する。

**改善の方向**: 断片を再利用可能な可変オブジェクト（またはプール）にし、行の位置・幅・テキストだけを更新する。`containsFloats` を箱にキャッシュし、浮動子がない場合は `scanFloats` を省略する。`updateScrollExtent` は部分木の最後に 1 回だけ計算する。`String.isBlank` は先頭・末尾の非空白文字を直接見る高速判定にする。

#### 9.6.3 スタイル等価判定とビューポート依存判定

[`ComputedStyle.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/ComputedStyle.java) の `sameDeclarations`（約 392 行）と `StyleMap.equals`（約 434 行）は、約 190 エントリの `CssValue[]` を `Arrays.equals` で比較する。`sameDeclarations` には同一性の高速路があるが、**内容が同じでインスタンスが違うスタイル**は配列比較に落ちる。`CssValue.dependsOnViewport()`（[`CssValue.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/CssValue.java) 約 111 行）は `CssValue` 単位ではメモ化されているが、要素ごとに何度も呼ばれる。`ComputedStyle.facts()`（約 958 行）と `InteractionState.register` も要素ごとの定数コストとして現れる。

**改善の方向**: 同一性 → 64-bit の宣言ハッシュ → 配列比較の順に短絡する。`facts` と `dependsOnViewport` をスタイル単位で 1 回だけ計算し、要素側では参照を読むだけにする。状態フラグが変わらない要素の `register` を省略する。`varied fontsize` では `Arrays.equals` 7.9% + `StyleMap.equals` 8.5% が直接の対象になる。

#### 9.6.4 テキスト成形・改行の再計算

`varied fontsize` の `LineBuilder.newLine` 4.5%、`placeLine` 4.1%、`FontFace.naturalAdvance` 2.0%、`TextShaper$Generational.get` 1.7%、`isAlreadyNormal` 1.7% は、幅変更で全行のテキストを成形し直していることを示す。`varied fontsize` は行ごとに文字サイズ・文章長・折り返しが異なるため、同形リストのように共有できない。**成形済みランと改行位置を (text, font, size, width) でキャッシュ**し、幅が同じなら再成形を避ける必要がある。`isAlreadyNormal` の結果を文字列またはスタイルに保持するのも有効である。

#### 9.6.5 表示項目の絶対座標と `survivesTranslation`

[`DisplayListBuilder.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/DisplayListBuilder.java) の `survivesTranslation`（約 746 行）は、影・シェーダーグラデーション・レイヤー・`NODE`/`RECT`/`TEXT`/`ROUNDED_BORDER` 以外の項目を「平行移動で再利用できない」と判定する。showcase resize の `MATCHFAIL` は reason 4（移動するが平行移動不可）が `111.9/163.9` を占め、`BoxMemo` も exact `62.1` に対して miss `163.9`、unmemoizable `13` だった。`UiView.recomputeLayout` は `paintBoxes(rootBox, w, h, false)` を呼び、表示リストの全再構築経路に落ちる（ビルダーと箱の記憶は保持していても、項目は作り直す）。

**改善の方向**: 表示項目の座標を箱原点からの相対値で保持し、平行移動だけなら項目を再利用する。影・グラデーション・レイヤーもオフセット付きで再利用できるようにする。`BoxMemo` のキーに幅を含め、内容が同じ箱の測定を幅変更後も再利用する。これが P3 である。

#### 9.6.6 CPU バックエンドの全面再描画

[`SoftwareRenderBackend.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java) は、showcase `hover` で `partial=0 / full=1801`、理由 1（前フレームが動作中）と 6（動作中のアニメ項目）が各 `900`。つまり **transition 中の項目があると毎フレーム全面描画**になる。`inherited` は `partial=1599 / full=2` で部分描画が効いており、paint `2.19 ms` の大半は文字色変更によるタイル更新である。

**改善の方向**: 前フレームで動作中だった項目を記録し、遷移中の項目だけを「変更」として扱う。動作中項目のタイルだけを部分再描画し、全面描画に戻す条件を「項目数が変わった」「新規に動作開始」に限定する。これが P7 である。

#### 9.6.7 全木走査と未計上時間

`varied inherited` の未計上 `1.78 ms`（18%）、`varied child` の未計上 `0.93 ms`（18%）は、`LayoutBox.reusableBoxes` の `IdentityHashMap` 構築（`collectReusableBoxes` 約 1323 行）、`linkParents`（約 173 行）、`resetPlacements`、`clearAllSubtreeMarks`（約 1500 行）、`markLayoutChangesAlong` などの全木走査に対応する。JFR では `IdentityHashMap.resize` 1.4%、`collectReusableBoxes` 2.2% exclusive / 4.8% inclusive、`resetPlacements` 2.7% exclusive が確認できる。**ノード索引の配列・親への逆参照・増分マーク**に置き換えれば、フレーム全体で 5〜10% の短縮が見込める。

### 9.7 改善点（優先順位 P1〜P8）

| ID | 優先 | 対象 | 変更 | 期待効果（見込み） | 検証 |
| :-- | :--: | :-- | :-- | :-- | :-- |
| **P1** | ✅ 完了（11-12） | `DisplayListBuilder.canPatchBoxes` / `isCulledNoop` / `offscreen` | 箱ごとの「画面外」「移動祖先あり」親走査をキャッシュ。`UiView` と `patchBoxes` の二重判定を解消。`MOVING_PROPS` の文字列照合を `StyleFacts.hasMovingProps` に置換。単一パス `preparePatchBoxes` で重複排除・降順ソート・重なり判定を一元化 | varied `inherited` `10.20 → 7.92 ms`（p50 `7.78 ms`、-22.4%）、large `inherited` `3.50 → 3.07 ms`（p50 `2.97 ms`）。確保量 `2455.5 → 1970.2 KB/frame`（-485 KB/frame） | §9.3 の varied/large `inherited`、パッチ命中 100%、`DisplayListSlicePatchOptimizationTest`、`DisplayListSlicePatchTest`、`:core:test` 全件グリーン |
| **P2** | ✅ 完了（11-12） | `BlockLayoutEngine.placeLine`・`InlineFragment`・`updateScrollExtent`・`scanFloats` | 後続行の断片を再利用（可変フィールド更新）。先頭/末尾文字から `isNotBlankFast` で `isBlank` を短絡。`updateScrollExtent` のインデックスループ化と末尾通常フロー（`lastInFlow`）の逆順走査。祖先限定の局所 float 無効化（`invalidateFloats`）。外側マーカー・絶対配置・親同期のフラグ化で不要なループ走査を完全短絡 | PatchHitRateRatchetTest の child アロケーション 421 KB → 306 KB（-27%）。large child 確保 896.9 KB/frame。placeOutsideMarkers 走査の完全消滅 | `BlockLayoutOptimizationTest`、`FloatContainmentMemoTest`、`PatchHitRateRatchetTest`、`:core:test` 1,933 件全件グリーン |
| **P3** | 🥈 高 | `DisplayListBuilder.survivesTranslation` / `match`、`UiView.recomputeLayout` | 表示項目の座標を箱原点からの相対値で保持し、平行移動だけなら項目を再利用。影・グラデーション・レイヤーもオフセット付きで再利用。`BoxMemo` の幅キーを見直し、幅変更で内容が同じ箱の測定を再利用 | showcase `resize` `1.05 → ~0.8 ms`（display `0.42 → <0.2`）、JMH `0.78 → ~0.6`。`MATCHFAIL` reason 4 を `112 → <30` | §9.3 の `resize`、JMH `layoutWidthChange`、表示リスト一致テスト |
| **P4** | 🥈 高 | `ComputedStyle.sameDeclarations` / `StyleMap.equals`、`CssValue.dependsOnViewport`、`ComputedStyle.facts`、`InteractionState.register` | 同一性 → 64-bit 宣言ハッシュ → 配列比較の順に短絡。`facts` と `dependsOnViewport` をスタイル単位で 1 回だけ計算。状態フラグが変わらない要素の `register` を省略 | varied `fontsize` restyle `6.79 → ~5.0`、measure `9.59 → ~8.5`、確保 `-10%`。JFR `Arrays.equals` `<4%`、`StyleMap.equals` `<5%` | §9.3/§9.5 の varied `fontsize`、スタイル一致テスト |
| **P5** | 🥉 中 | `TextShaper`・`LineBuilder`・`FontFace.naturalAdvance` | 成形済みランと改行位置を `(text, font, size, width bucket)` でキャッシュ。幅変更で再成形しない。`isAlreadyNormal` の結果を文字列/スタイルに保持。`naturalAdvance` を語単位でキャッシュ | varied `fontsize` measure `9.59 → ~6.5`、`newLine` `<2%`。large `fontsize` `7.63 → ~6.5` | §9.3/§9.5 の `fontsize`、テキスト描画一致テスト |
| **P6** | 🥉 中 | `LayoutBox.reusableBoxes` / `linkParents` / `resetPlacements` / `clearAllSubtreeMarks` / `markLayoutChangesAlong` | `IdentityHashMap` をノード索引の配列に置換。親への逆参照を保持し、`linkParents` の全走査を廃止。変更マークを増分更新。部分木だけの再配置で `resetPlacements` の全走査を避ける | 未計上 `16〜19% → <10%`、フレーム全体 `-5〜10%`、`IdentityHashMap.resize` `<0.5%` | JFR の `collectReusableBoxes` `<1%`、レイアウト一致テスト |
| **P7** | 🥉 中 | `SoftwareRenderBackend` の全面描画理由 1/6 | 前フレームで動作中だった項目を記録し、遷移中の項目だけを「変更」として扱う。動作中項目のタイルだけを部分再描画。全面描画に戻す条件を「項目数が変わった」「新規に動作開始」に限定 | showcase CPU `hover` `1.77 → <1.5`、遷移中も `partial>0` を維持。`inherited` `2.62` は維持 | `DAMAGE-WHY` の partial/full、`PartialRepaintPixelTest`、`VolatileItemPartialRepaintTest` |
| **P8** | 🔒 横串 | `InlineFragment`・`LineItem`・`CssValue[]` コピー・インライン style の解析 | 断片・行項目を再利用。配列を事前サイズ確保。解析済みインライン style をノードに保持。防御的コピーを廃止 | varied `fontsize` `25.8 → <15 MB/frame`、p99 `-20%`。large `fontsize` `7.3 → <6 MB` | `AllocationRatchetTest`、FineProfile の確保量、GC ログ |

**P1 と P2 を先に行う理由**: どちらも「パッチは命中しているが、その前後の処理が重い」という同じ構造で、R2/R3 の KPI に直結する。P1 は `varied inherited` の 26.4%、P2 は `varied child` の 26.3% を直接狙う。P3 は W5、P4/P5 は `varied fontsize` の確保量と `inherited` の残り、P6〜P8 は横串の底上げである。

### 9.8 KPI 判定（2026-11-12）

| KPI | 目標 | 実測 | 判定 |
| :-- | :--: | :--: | :--: |
| large 2,000 `child` GL | `< 3 ms` | `1.44` | ✅ |
| large 2,000 `hover` GL | `< 1.0 ms` | `0.30` | ✅ |
| large 2,000 `fontsize` GL | `< 10 ms` | `7.63` | ✅ |
| large 2,000 `inherited` GL | 前回 `3.54` 以下 | **`3.07`**（p50 `2.97`） | ✅（P1 完了） |
| varied 2,000 `child` GL | Chrome 並み `~3.67` | `5.15` | ❌ |
| varied 2,000 `inherited` GL | Chrome 並み `~3.55` | **`7.92`**（p50 `7.78`） | 🔧（P1 で 10.20→7.92、-22%） |
| varied 2,000 `fontsize` GL | Chrome 以下 `42.1` | `30.77` | ✅ |
| showcase `resize` | `< 0.2 ms` | `1.05`（JMH `0.78`） | ❌ |
| showcase CPU `hover` | `< 1.5 ms` | `1.77` | ❌ |
| showcase CPU `inherited` | `< 4 ms` | `2.62` | ✅ |
| varied `fontsize` 確保 | `< 5 MB` | `25.8 MB` | ❌ |
| large `child` 確保 | `< 2 MB` | **`896.9 KB`**（前回 `0.94 MB`） | ✅ |
| large `hover` 確保 | `< 300 KB` | `39 KB` | ✅ |

**総合**: 同形リストの時間 KPI はほぼ達成した。残るのは **varied の `child`/`inherited`、showcase の幅変更、CPU transition、varied `fontsize` の確保量**。P1 により varied `inherited` は 10.20 → 7.92 ms へ 2.28 ms 縮小、P2 により断片再利用・空白判定短絡・マーカー走査短絡・局所 float 無効化が完了し、`PatchHitRateRatchetTest` の child アロケーションは 421 KB → 306 KB（-27%）へ削減された。今後の主戦場は `varied-list-2000`（特に child の小数平行移動・fontsize の P4/P5）と showcase（P3 幅変更・P7 CPU）である。

#### 9.8.1 まだ負けている項目・未計測項目

> **2026-11-12 夜の更新（§10.2）**: 下表のうち large 500 の `inherited`（`0.77`）・`hover`（`0.26`）・`style`（`0.33`）と large 2,000 `text`（`0.34`）は Chrome 以下（同等）になり、varied `inherited` は `6.81`、varied `child` は `4.60`、showcase CPU `hover` は `1.66 ms` へ縮んだ。最新の負け項目と原因は §10.1・§10.2 を正とする。

§9.8 の表は今回測った KPI の抜粋であり、「全項目で勝った」という意味ではない。現時点で負けている、または今回測っていない主な項目は次のとおり。

| 項目 | 現状 | 比較 | 状態 |
| :-- | :--: | :--: | :--: |
| large 500 `inherited` GL | `3.59 ms` | Chrome `0.96`（3.8x） | ❌ 前回（§6.7） |
| large 500 `hover` GL | `0.72 ms` | Chrome `0.35`（2.0x） | ❌ 前回（§6.7） |
| large 500 `style` GL | `0.65 ms` | Chrome `0.33`（2.0x） | ❌ 前回（§6.7） |
| large 500 `fontsize` GL | `4.24 ms` | Chrome `3.45`（1.2x） | ❌ 前回（§6.7） |
| large 500 `child` GL | `2.18 ms` | Chrome `child250` `1.98`（1.1x） | 🔧 前回（条件補正後） |
| large 2,000 `text` GL | `1.61 ms` | Chrome `1.02`（1.6x） | ❌ 今回未再計測 |
| large 2,000 `inherited` GL | **`3.07 ms`** | Chrome 2,000 未取得（500 は `0.96`） | ⚠️ 未比較（P1 で 3.50→3.07） |
| large 2,000 `scroll` GL | `0.56 ms` | Chrome `0.46`（1.2x） | ❌ 前回（§6.7） |
| large 2,000 `open` | `25.5 ms` | Chrome `19.8`（1.3x） | ❌ 前回（null） |
| varied 2,000 `child` GL | `5.15 ms` | Chrome `3.67`（1.4x） | ❌ 今回 |
| varied 2,000 `inherited` GL | **`7.92 ms`** | Chrome `3.55`（2.2x） | 🔧 今回（P1 で 10.20→7.92） |
| showcase `resize` | `1.05 ms` | Chrome `0.084`（12.5x。**測る範囲が違う比較**。同じ範囲では約 4〜5x、§10.10） | ❌ 今回 |
| showcase CPU `hover` | `1.77 ms` | Chrome CPU `1.55`（1.1x） | ❌ 今回 |
| showcase `open` | `6.19 ms` | Chrome `5.0`（1.2x） | ❌ 前回 |
| varied ヒープ保持 | `51.8 MB` | Chrome +`26 MB`（約2x） | ❌ 前回（§8.6.5） |
| large 2,000 ヒープ保持 | `9.3 MB` | Chrome +`5 MB`（約1.9x） | ❌ 前回（§8.6.5） |
| p99 / 分散 | 未計測 | — | ⚠️ |
| `softwarePaint` showcase JMH | `4.38 ms` | 旧 `1.97`（退行ではないが高い） | ⚠️ 要調査 |

**CPU バックエンド（参考）**: large 500 行の CPU は `hover 2.28` / `text 1.29` / `style 1.66` / `inherited 8.00` / `fontsize 7.76` / `child 5.30 ms` で、Chrome の GPU 合成込み全 CPU（`0.36` / `0.49` / `0.33` / `0.96` / `3.45` / `0.54`）より遅い。ただし CPU バックエンドはソフトウェアラスタで、Chrome の GPU 合成との単純比較はできない（参考値）。showcase CPU `inherited` は `2.62 ms` で目標 `< 4 ms` は満たすが、Chrome CPU の同操作は未取得で、Chrome 比較は未確定である。

### 9.9 次の作業順序

> **2026-11-12 夜の改訂**: 次の作業順序は §10.5（Q1 座標の固定小数点・親相対化 → Q1a / Q2 / Q3 → Q4 → Q5・Q6）を正とする。**Q1 第 1 段・Q1a は完了（§10.7）。Q2 ②（差分メモ）も完了（§10.8）。Q6 は一部のみ（§10.9）。次は Q3。**P3 は実装済みだが、効果は表示リスト込み・ホストの resize 経路（`resizeexact`）でしか見えず、フレーム時間はほぼ不変だった（§10.3.3）。

1. **P1（パッチ判定キャッシュ）**: ✅ **完了（2026-11-12）**。`preparePatchBoxes` 一元化、`StyleFacts.hasMovingProps`、親走査キャッシュ、`UiView` 二重判定撤廃。varied `inherited` GL `10.20 → 7.92 ms`（-22.4%）、large `inherited` GL `3.50 → 3.07 ms`（p50 `2.97 ms`）、ヒープ確保量 `-485 KB/frame`。
2. **P2（`placeLine`/`InlineFragment` 再利用）**: ✅ **完了（2026-11-12）**。断片インプレース再利用、`isNotBlankFast` 短絡、`placeOutsideMarkers` 短絡、`updateScrollExtent` 高速化、局所 float 無効化。`PatchHitRateRatchetTest` child 確保量 421 KB → 306 KB（-27%）、large child 確保 896.9 KB/frame。
3. **P3（表示項目の相対座標・平行移動再利用）**: W5 の本命。次期最優先として着手。showcase resize `1.05 → ~0.8 ms`、JMH `layoutWidthChange` `0.78 → ~0.6 ms` を狙う。
4. **P4/P5（スタイル等価判定・テキスト成形キャッシュ）**: `varied fontsize` の確保量と `inherited` の残り。
5. **P6/P7/P8（全木走査・CPU ダメージ・確保量）**: 横串。P1〜P5 の後で、フレーム全体の底上げとして実施する。
6. 各施策の完了ごとに §9.3 と §9.5 を再計測し、§9.8 を更新する。時間ベンチは不安定なので、**パッチ命中・走査項目数・確保量のカウンタを R7 のラチェットに追加**して退行を防ぐ。

### 9.10 前回台帳との差分（最近のコミットの効果）

| 項目 | 前回値 | 今回値 | 変化の要因 |
| :-- | :--: | :--: | :-- |
| large 2,000 `child` GL | `13.77`（§6.7.3）→ `1.21`（§8.6.6） | **`1.44`**（確保 896.9 KB） | 窓付き表示リスト（§8.6.2）と fit/measure 再利用、P2 の断片再利用で確保量をさらに抑制 |
| large 2,000 `fontsize` GL | `18.93`（§6.7.3）→ `6.6`（§8.6.6） | **`7.63`** | スタイル共有（§8.6.6）と fit/measure 再利用で Chrome `11.3` 以下を維持。前回 `6.6` からは +16% だが、実行ごとのばらつきと JIT の影響を含む |
| large 2,000 `inherited` GL | `3.50`（§9.8） | **`3.07`**（p50 `2.97`） | **P1 実装**（パッチ判定一元化・`hasMovingProps`・親走査キャッシュ） |
| JMH `layoutWidthChange` | `1.039` | **`0.777`** | `dbcd523`（リサイズで表示リストのビルダーと箱ごとの記憶を保持）＋ fit 再利用 |
| varied `fontsize` GL | `43.8`（§8.6.6） | **`30.77`** | fit/measure 再利用と幅変更経路の修正。Chrome `42.1` を下回る |
| varied `child` GL | `4.9`（§8.6.6） | **`5.15`** | 横ばい（±10%）。P2 で断片確保量 -27%、マーカー走査消滅。小数の平行移動近道（P3/将来拡張）へ |
| varied `inherited` GL | `10.20`（§9.8） | **`7.92`**（p50 `7.78`） | **P1 実装**（二重判定撤廃・`preparePatchBoxes` 単一パス化・`offscreen` 高速化で **-2.28 ms, -22.4%**） |

### 9.11 P1 実装記録（2026-11-12 完了）

#### 9.11.1 実施内容
1. **二重判定の撤廃**:
   - `UiView.restyleAndUpdate` の `canPatchBoxes(changedBoxes)` 事前呼出を撤廃し、`patchBoxes(changedBoxes)` に一本化。
2. **単一パス化（`preparePatchBoxes`）**:
   - `canPatchBoxes` と `patchBoxesUnprofiled` で重複していた `isCulledNoop` 走査・`LinkedHashSet`/`ArrayList` の多重生成・二重ソートを `preparePatchBoxes` に統合。
   - `boxes.size() > 1` 時の降順ソート後の in-place 重複排除とスライス重なり判定（`b.dlEnd > prev.dlStart`）を 1 パスで処理。
3. **`MOVING_PROPS` の高速ビット化と親走査キャッシュ**:
   - `PaintOrder.StyleFacts` に `boolean hasMovingProps` を導入。変形・アニメーション・フィルター等の 8 プロパティ文字列比較をスタイル解決時の 1 回のみに集約。
   - `hasMovingAncestor` に直近親の走査結果キャッシュ（`cachedParentForMovingCheck`）を導入。共通親を持つリスト項目群（2000 行など）の祖先走査を O(1) に短絡。
4. **テスト追加と検証**:
   - `DisplayListSlicePatchOptimizationTest`: 重複要素の重複排除・降順ソート維持・全画面外時の安全な no-op 処理・スライス重なり検出・二重呼び出し防止の単体テストを追加。
   - `:core:test` 1,923 件全件グリーン、`LayerDependencyRatchetTest` 適合、`PatchHitRateRatchetTest` パッチ命中率 100%（fallback 0）を確認。

### 9.12 P2 実装記録（2026-11-12 完了）

#### 9.12.1 実施内容
1. **`InlineFragment` のインプレース再利用とプーリング**:
   - `LayoutBox` に `fragmentCursor`、`startFragmentPass()`、`finishFragmentPass()`、`addFragment(...)` を導入。
   - 既存の `inlineFragments` リストの要素を `set(...)` で再利用し、行配置ごとの `new InlineFragment` インスタンス生成と `ArrayList.add` の再確保を完全に抑止。
   - 余分な断片は `finishFragmentPass()` で末尾からトリム。`PatchHitRateRatchetTest` での child アロケーションが 421,374 B → 306,062 B へ 115 KB/op（-27%）削減。
2. **`isNotBlankFast` による `isBlank` 文字列走査の短絡**:
   - `String.isBlank()` が内部で行う `CharacterData.of(ch).isWhitespace(ch)` 全走査（JFR 8.3% inclusive）を、先頭および末尾文字が ASCII 表示可能文字（0x21〜0x7e）であれば即座に non-blank と判定して短絡。
   - 全角空白（\u3000）やノーブレークスペース（\u00a0）等の Unicode 空白に対しても `!s.isBlank()` フォールバックにより完全な互換性を担保。
3. **`LayoutBox.updateScrollExtent` のインデックスループ化と末尾通常フロー（`lastInFlow`）の逆順走査**:
   - `new ScrollExtent` 2 インスタンスの生成をローカルプリミティブ化。
   - 葉ノード（子なし・エンジン状態なし・インラインなし）の早期 return を導入。
   - マージン相殺貫通時の `lastInFlow` 検索をリスト末尾からの逆順走査にし、最初の通常フロー要素発見時に即 `break`。
   - `children` および `inlineFragments` の走査を `ArrayList` インデックスループ化し、Iterator 生成を完全撤廃。
4. **祖先限定の局所 float 無効化（`invalidateFloats`）**:
   - `BoxTreeBuilder.insertBuiltChild` および `removeBuiltChild` で、全体無効化 `LayoutBox.bumpShapeEpoch()` を呼んでいたのを改め、変更ノードの親からルートへの祖先パスのみ局所無効化する `LayoutBox.invalidateFloats(parent)` を導入。
   - 2,000 行リスト等において、無関係な 1,999 行の既存兄弟要素の `containsFloats` キャッシュが保持されるように改善。
5. **外側マーカー・絶対配置・親同期フラグによる走査短絡**:
   - `LayoutBox` に `hasOutsideMarker`、`hasAbsoluteChild`、`needsAdoption` を導入。
   - `BlockLayoutEngine.placeOutsideMarkers`: `hasOutsideMarker` が false のときは `children` 走査を行わずに即 return（Hotspot プロファイルから `placeOutsideMarkers` が完全に消滅）。
   - `LayoutBox.layout`: 親リンク同期済みのときは `adopt` ループをスキップ、`containsFloats` が false のときは `hasFloatChild` 走査をスキップ、`hasAbsoluteChild` が false のときは静的位置設定および絶対配置レイアウト走査をスキップ。
   - 通常ブロック要素における 4 重の `children` 走査を完全に短絡。
6. **`arrangeFlow` における段組シフト判定の短絡**:
   - `shifts == null` の通常フロー時、`shiftOf` メソッド呼び出しと配列加算を完全スキップ。
7. **テスト追加と検証**:
   - `BlockLayoutOptimizationTest`: `isNotBlankFast` の Unicode 空白を含む網羅的一致テスト、`InlineFragment` の再利用ライフサイクル、葉ノード短絡計算一致、子フラグ管理、局所 float 無効化の単体テストを追加。
   - `:core:test` 1,933 件全件グリーン、`LayerDependencyRatchetTest` 適合、`FloatContainmentMemoTest` 適合、`PatchHitRateRatchetTest` パッチ命中率 100%（fallback 0）を確認。

---

## 10. 2026-11-12（夜）再考察: なぜ遅いのか — 根本原因の再定義と裏付け計測

> §9 の P1〜P4 実装後の HEAD（`cb6a734`）で、§9 の改善点リストを「個々のホットスポット」から「それを生む構造」へ一段掘り下げた。各仮説は **A/B 実験または JFR で裏付けたもの**だけを採用し、外れた仮説も記録する（§10.4）。

### 10.1 結論（要約）

`:core` の残る遅さは、個別の遅い関数ではなく、次の **4 つの構造上の原因**から出ている。§9 で P1〜P8 として並べた症状の大半は、このどれかに帰着する。

| # | 根本原因 | 典型的な症状（§9 の ID） | 裏付け（§10.3） | 効く文書 / 操作 |
| :-- | :-- | :-- | :-- | :-- |
| **C1** | **座標が「絶対値の float（`LayoutBox`）」と「丸めた絶対値の int（`DisplayItem`）」の二重表現**。平行移動の再利用が**整数ぶんの移動**でしか正しくならないため、小数の移動（em・`line-height: normal`・中央寄せ）が 1 つでもあると、後続の全行を置き直し・項目を作り直す | P2（`placeLine`）・P3（`MATCHFAIL 4`）・P8（確保量） | **A/B: 移動量を整数にするだけで varied `child` が 4.17 → 2.16 ms（-48%）、確保 4,896 → 277 KB/frame（-94%）**、`shifted` 0 → 1,750 | varied `child`、幅変更、行の高さが小数の全リスト |
| **C2** | **算出スタイルが約 190 枠の平坦な `CssValue[]`** で、変更の分類（継承へ伝わるか・レイアウトに効くか・幾何だけか・トランジション対象か）のたびに**全枠を比べ直す**。1 要素あたり 2〜3 回、しかも同じ (旧, 新) の組を要素ごとに繰り返す | P4（`Arrays.equals` は宣言ハッシュで解消済み。残りは `diff`） | **JFR: `ComputedStyle.diff` が varied `inherited` で 20.3%、large 500 `inherited` で 33.3%（exclusive・首位）** | すべての `inherited` / `fontsize` / テーマ切替 |
| **C3** | **要素ごとの祖先走査**（`nearestSizeContainer`・`hasMovingAncestor`）が、親から子へ下る再帰の中で毎要素ゼロからやり直される。O(N×深さ) | P1 の残り・P6 | **JFR: varied `inherited` で `nearestSizeContainer` 9.4%・`hasMovingAncestor` 9.9%（P1 の直近親キャッシュは、入れ子のある行では外れる）** | 入れ子の深い不均一な文書 |
| **C4** | **無効化の粒度が粗い**: `font-size` の変化は `isGeometryOnlyChange` が無条件に false を返すため**構造変更（箱の木の作り直し）**に格上げされる | P5 の手前 | **計測: varied `fontsize` で `structural=1000`（毎フレーム）、`box-tree` 2.13 ms/frame（7.8%）** | `fontsize`・ズーム・ルート em 変更 |

加えて、**計測の経路の問題（M1）**が見つかった。§9.7 P3（`af36a06`）が有効にした `exactTranslation` は `UiView.flushInvalidation` の resize 分岐でしか立たない。ところが `FineProfileMain resize` と JMH `layoutWidthChange` はどちらも `recomputeLayout` を直接呼ぶため、**P3 の経路を一度も通っていなかった**（§10.3.3）。

### 10.2 再計測（2026-11-12 夜、HEAD `cb6a734`、§9.2 と同条件）

| 文書 / 操作 | backend（frames） | mean / p50 (ms) | 確保 (KB/frame) | 段別 (ms) | §9 の値 → 今回 | Chrome |
| :-- | :--: | :--: | :--: | :-- | :--: | :--: |
| varied-2000 `inherited` | GL (500) | **`6.81` / `6.71`** | `2,897` | restyle `5.46` / display `0.77` / paint `0.54` | `7.92` → **`6.81`**（-14%、P4 の宣言ハッシュ） | `3.55`（1.9x） |
| varied-2000 `child` | GL (500) | **`4.60` / `4.54`** | `4,969` | measure `0.43` / place `2.76` / display `0.30` / paint `0.36` / 未計上 `0.75` | `5.15` → `4.60` | `3.67`（1.25x） |
| varied-2000 `fontsize` | GL (400) | **`27.36` / `26.98`** | `26,319` | box `2.13` / restyle `5.36` / measure `9.27` / place `4.25` / display `2.46` | `30.77` → `27.36` | `42.1` ✅ |
| large-500 `inherited` | GL (800) | **`0.77` / `0.72`** | `810` | restyle `0.36` / display `0.12` | `3.59`（§6.7）→ `0.77` | `0.96` ✅ |
| large-500 `hover` | GL (800) | **`0.26` / `0.24`** | `42` | restyle `0.03` | `0.72` → `0.26` | `0.35` ✅ |
| large-500 `style` | GL (800) | **`0.33` / `0.31`** | `45` | restyle `0.08` | `0.65` → `0.33` | `0.33` ＝ |
| large-2000 `text` | GL (800) | **`0.34` / `0.30`** | `41` | measure `0.03` / place `0.10` | `1.61` → `0.34` | `1.02` ✅ |
| large-2000 `inherited` | GL (700) | **`2.39` / `2.33`** | `979` | restyle `1.78` / display `0.25` | `3.07` → `2.39` | 未取得 |
| showcase `resize`（`recomputeLayout` 直呼び） | null (2000) | **`0.98` / `0.89`** | `1,471` | measure `0.36` / display `0.39` | `1.05` → `0.98` | `0.084`（12x） |
| showcase `hover` | CPU (1200) | **`1.66` / `1.63`** | `284` | paint `≈1.57`（`partial=0 / full=1801`、理由 1・6 が各 900） | `1.77` → `1.66` | CPU `1.55` |

**§9.8.1 の「負け」表の更新**: large 500 の `inherited` / `hover` / `style`、large 2,000 `text` は **Chrome 以下（または同等）になった**。残る負けは **varied `inherited`（1.9x）・varied `child`（1.25x）・showcase 幅変更（12x）・showcase CPU `hover`（1.07x）**と、メモリ保持量・`open`（今回未計測）である。

### 10.3 裏付け計測

#### 10.3.1 C1: 小数の移動量が平行移動の近道を殺す（A/B）

`LayoutBox.layout` の部分木平行移動（[`LayoutBox.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/layout/LayoutBox.java) 約 1649 行）は `isIntegral(parentY - lastLayoutY)` を条件にしている（float の絶対座標に小数を足すと、置き直した結果と丸めが一致しない恐れがあるため）。表示項目側の `DisplayListBuilder.match` も `isIntegerTranslation(dx/dy)` で同じ制約を持つ。varied の行は `font-size: 80〜130%` と `line-height: normal` で高さが小数になるため、挿入した行の高さも小数になり、後続 1,750 行で近道が一度も効かない、という仮説を立てた。

**実験**: `FineProfileMain` に `childfixed` シナリオを追加した。`child` と同じ位置（item-250 の前）に、高さを `20px` に固定した行（下枠 1px を足して移動量 21px＝整数）を挿入・削除する。それ以外は同一。カウンタ `LAYOUTSKIP`（`LayoutBox.layoutsShifted()` / `layoutsSkipped()`）を新たに出力する。

| varied-list-2000（null・600 frames） | mean (ms) | place (ms) | 確保 (KB/frame) | 平行移動した部分木 / frame |
| :-- | :--: | :--: | :--: | :--: |
| `child`（挿入行の高さが小数） | `4.17` | `2.78` | `4,896` | **`0`** |
| `childfixed`（挿入行の高さが整数） | **`2.16`（-48%）** | **`0.85`（-69%）** | **`277`（-94%）** | **`1,750`** |
| （参考）large-2000 `child` | `1.10` | `0.49` | `837` | `0`（行高は整数でも挿入行が小数） |

**読み方**: 原因は「1,750 行を置き直す」ことそのものではなく、**置き直さずに済ませる近道が、移動量が小数というだけで無効になる**ことにある。§9.6.2 の「断片の再生成」（`ArrayList.add` / `addFragment` / `resetPlacements`）と確保量 4.9 MB/frame は、この近道が外れた後の症状である。`childfixed` の 2.16 ms は null バックエンドの値だが、GL 送出（約 0.36 ms）を足しても Chrome `3.67 ms` を下回る見込み。

**副次的な発見**: 近道が外れたとき、`LayoutBox.layout` は子に `resetPlacements(child, -1)` を呼び、これが `inlineFragments.clear()` する（約 1376 行）。そのため **P2 の断片インプレース再利用（`addFragment` の `fragmentCursor`）は、この経路では毎回空のリストから始まり、再利用が 1 件も効かない**。varied `child` の JFR で `LayoutBox.addFragment` 11.4%・`ArrayList.grow`/`Arrays.copyOf` 6.3〜6.8% が残っているのはこのため。

#### 10.3.2 C2 / C3: 再スタイルの中身（JFR）

varied-2000 `inherited`（null・1,500 frames、808 samples）:

| 種別 | フレーム | 割合 | 対応 |
| :-- | :-- | :--: | :-- |
| exclusive | `ComputedStyle.diff` | **20.3%** | C2（`propagationOf` 13.7% incl. ＋ `computeLayoutAffectingChange` 6.6% incl.） |
| exclusive | `StyleStateAutomaton.compress` | 11.3% | `StateStyleMemo.peek` → `StateTable.attachment`。命令数は数個なので、**要素ごとの `StateTable` を読むキャッシュミスがここに計上されている**と読む（散在オブジェクトのポインタ追い。§8.1 F2 と同型） |
| exclusive | `StateLookup.nearestSizeContainer` | 6.9%（incl. 9.4%） | C3（`containerFor` が要素ごとに祖先をたどる） |
| exclusive | `TextShaper.isAlreadyNormal` | 6.1% | 文字色だけの変化でも、`restyleGeneratedBoxes` が文字列の正規化判定を全文字に対してやり直す |
| exclusive | `DisplayListBuilder.hasMovingAncestor` | 5.9%（incl. 9.9%） | C3（P1 の直近親キャッシュは、バッジ・太字・サブ行を持つ行の中の箱では外れる） |
| exclusive | `CssValue.dependsOnViewport` | 4.7%（incl. 5.0%） | `cacheStyle` → `rawDependsOnViewport`（P4 の `valuesViewport` キャッシュは生宣言の表には効いていない） |
| exclusive | `CharacterDataLatin1.toLowerCase` | 2.6% | `cacheStyle` が毎回 `style` 属性を `toLowerCase().contains("inherit")` する |
| inclusive | `RenderPipeline.restyleBoxRecursive` | 86.8% | — |
| inclusive | `RenderPipeline.styleEntry`（カスケード＋算出の本体） | **16.5%** | **カスケード自体は 1/6 にすぎない** |

large-500 `inherited`（108 samples、参考）: `ComputedStyle.diff` **33.3%** exclusive（首位）、`compress` 9.3%、`hasMovingAncestor` 5.6%。**全行が同じスタイルを共有する文書でも diff が首位**であり、同じ (旧スタイル, 新スタイル) の組の diff を 500 回繰り返していることを示す。

`ComputedStyle.diff`（[`ComputedStyle.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/core/css/ComputedStyle.java) 約 358 行）は全枠を走査し、参照が違えば `text.equals` まで比べ、差分ごとにラムダ（捕獲配列つき）を呼ぶ。呼び出し元は `RenderPipeline.propagationOf`・`isPositionOnlyChange`・`isGeometryOnlyChange`、`StyleResolver.computeLayoutAffectingChange`、`StyleAnimator.startTransitions`、`StyleStateAutomaton.classify` の 6 か所で、**同じ 1 要素の再スタイルで最大 3 回**走る。

#### 10.3.3 M1: P3 は計測経路に乗っていなかった

showcase `resize` の `MATCHFAIL 4=112`・`translated=0` が P3 実装前後で**完全に同じ**だったため、原因を調べた。

1. **仮説 1（棄却）**: 1px の幅変更で中央寄せの箱が 0.5px 動くので、整数移動の条件に落ちる → `resize2`（2px 刻み）を追加して計測したが、`MATCHFAIL 4=112`・`translated=0` で**変化なし**。
2. **仮説 2（確認）**: `exactTranslation` は `UiView.flushInvalidation` の resize 分岐（約 1217 行）でしか立たない。ベンチは `recomputeLayout` を直接呼ぶので、フラグが立たない。ホストの経路（`StandaloneView.resize` → `requestResize`）を通す `resizeframe` は、`resizeDue` の壁時計による間引き（`RESIZE_THROTTLE_FACTOR`）で連続フレームでは 1 度もレイアウトされない（mean `0.0016 ms`）。そこで resize 分岐の中身を直接再現する `resizeexact`（リフレクションで `exactTranslation` を立てて `recomputeLayout`）を追加した。

| showcase 幅変更（null・2,000 frames） | mean (ms) | display (ms) | 確保 (KB) | BOXMEMO exact / translated / miss | MATCHFAIL 4 |
| :-- | :--: | :--: | :--: | :--: | :--: |
| `resize`（直呼び・従来の計測） | `0.98` | `0.39` | `1,471` | `62 / 0 / 164` | `112` |
| `resize2`（2px 刻み） | `0.97` | `0.38` | `1,461` | `62 / 0 / 164` | `112` |
| **`resizeexact`（ホストの resize 分岐と同じ）** | `0.95` | **`0.33`（-13%）** | **`1,194`（-18%）** | **`103 / 41 / 82`** | **`54`** |

**読み方**: P3 自体は効いており、ミスが半減し確保量も 18% 減った。しかし**フレーム時間はほぼ変わらない**（0.98 → 0.95）。理由は 2 つある。① 残る `MATCHFAIL 4=54` は小数の移動（C1）。② 幅変更の残り時間の主因は表示リストではなく**測定**（`measure` 0.36〜0.37 ms。JFR では `GridLayoutEngine.measureInArea` → `FlexLayoutEngine.remeasure` の入れ子が 36〜41% inclusive）。Chrome `0.084 ms` との 11 倍の差（**測る範囲が違う。同じ範囲では約 4〜5 倍。§10.10**）は、**内容の変わらない子孫の測定を幅変更で再利用できていない**ことによる（§9.5.4 の `BoxMemo miss` と整合）。

**是正**: 以後、幅変更の KPI は `resizeexact`（またはホスト経路）で取る。JMH `layoutWidthChange` もレイアウト単体の指標であり、P3 の効果は表示リスト込みの計測でしか見えないことを明記する。

#### 10.3.4 C4: `font-size` 変化が構造変更になる

varied `fontsize` の実フレームでは `restyle: structural=1000`（ウォームアップ込み累計 = 毎フレーム）で、`box-tree` 段が 2.13 ms/frame を使う。[`RenderPipeline.java`](file:///D:/Dev/Personal/enderframelib-1.21.1/core/src/main/java/com/nishiyu/enderframelib/common/RenderPipeline.java) の `isGeometryOnlyChange`（約 443 行）は、`font-size` か root の `font-size` が違えば即 false を返す。呼び出し元（約 755 行）はこれを `structural = true` にするので、**`display`・`content`・`float`・`position` が不変でも箱の木を作り直す**。`font-size` は箱の構成を変えない（寸法だけを変える）ので、幾何だけの変化として `layoutChangedBoxes` に入れれば `box-tree` 段は消える。表示リストは `MATCHFAIL 2=31`（スタイルの変化）で作り直しになるが、これは正当。

### 10.4 外れた仮説（記録）

| 仮説 | 結果 |
| :-- | :-- |
| varied `inherited` は全行がインライン `style` を持ち `ShareCache` を使えないので、カスケードと算出が支配的 | ✗ `StateStyleMemo` の表が当たり、カスケード＋算出（`styleEntry`）は 16.5% にとどまる。支配的なのは**算出後の比較・分類と祖先走査**（C2・C3） |
| showcase 幅変更の `MATCHFAIL 4` は、1px 変更で中央寄せが 0.5px 動くことが原因 | ✗ 2px 刻みでも同じ値。原因は計測経路（M1）。経路を合わせた後の残り 54 件は小数移動 |
| P2 の断片再利用で varied `child` の確保量は減っている | ✗ 近道が外れた経路では `resetPlacements` がリストを空にするので、再利用は効いていない（§10.3.1） |

### 10.5 改善項目（C1〜C4 から導く。§9.7 の P5〜P8 は維持し、以下を優先する）

| ID | 優先 | 対象 | 変更 | 見込み（計測からの推定） | 合否 |
| :-- | :--: | :-- | :-- | :-- | :-- |
| **Q1**（C1） | ✅ 第 1 段完了（2026-11-12、§10.7） | `LayoutBox` の座標・`InlineFragment`・`DisplayItem` | **座標を固定小数点（`LayoutUnit` = 1/64 px の int）にする**。加算が結合的になり、小数ぶんの平行移動が置き直しと**ビット単位で一致**するので、`isIntegral` / `isIntegerTranslation` の条件を「1/64 の倍数」へ緩められる（Chrome の `LayoutUnit` と同じ考え方）。中間段として、**部分木の位置を親からの相対値で持ち、絶対座標は描画の最終段で 1 回だけ足して丸める**（§8.2 の Paint 表の前段） | varied `child` `4.60 → ~2.5 ms`（`childfixed` の実測 2.16 ms が上限の目安）、確保 `4.9 MB → <0.5 MB/frame`。showcase 幅変更の `MATCHFAIL 4` `54 → ~0` | 全再構築との一致テスト（`DisplayListSlicePatchTest`・R7 乱択列）、`LAYOUTSKIP shifted` を `PatchHitRateRatchetTest` に追加 |
| **Q1a**（C1 の即効策） | ✅ 完了（2026-11-12、§10.7） | `LayoutBox.resetPlacements` | 近道が外れて置き直すときも `inlineFragments` を `clear()` せず、`fragmentCursor = 0` だけにして `finishFragmentPass` で切り詰める（P2 の再利用を置き直し経路でも効かせる） | varied `child` の確保 `-1〜2 MB/frame`、`addFragment`/`ArrayList.grow` `-10%` | `BlockLayoutOptimizationTest`、確保量ラチェット |
| **Q2**（C2） | ✅ ②のみ完了（2026-11-12、§10.8）。①③④は未着手 | `ComputedStyle.diff` と 6 か所の呼び出し元 | ① **1 回の走査で「継承へ伝わる／レイアウトに効く／幾何だけ／描画だけ」のビット集合を返す `diffSummary`** にし、`propagationOf`・`isLayoutAffectingChange`・`isGeometryOnlyChange` が共有する。② **(旧, 新) の同一性の組でメモ化**（同形リストでは 500 行が同じ組）。③ `declHash` が同じなら走査しない。④ 中期的には、算出スタイルを**継承グループ／非継承グループ（さらに box・visual・text…）の共有部分オブジェクト**に分け、比較をグループの参照比較（十数回）で済ませる（Blink の `ComputedStyleBase` のグループ化と同型） | large 500 `inherited` restyle `-30%`、varied `inherited` `6.81 → ~5.5 ms` | JFR で `ComputedStyle.diff` `<5%`、スタイル一致テスト |
| **Q3**（C3） | 🥈 | `restyleBoxRecursive`・`DisplayListBuilder.offscreen` | 「最寄りのサイズコンテナ」「動く祖先があるか」を**再帰の引数として親から子へ渡す**（O(N)）。祖先を毎要素たどらない | varied `inherited` `-10〜15%`（`nearestSizeContainer` 9.4% ＋ `hasMovingAncestor` 9.9% の大半） | JFR で両者 `<1%` |
| **Q4**（C4） | 🥈 | `RenderPipeline.isGeometryOnlyChange` | `font-size` の変化を、表示型・生成内容・`float`・`position` が不変なら**幾何だけの変化**として扱う（箱の木を作り直さない） | varied `fontsize` `box-tree 2.13 → ~0`（`27.4 → ~25 ms`）、large 2,000 `fontsize` も同様 | `structural` カウンタが `fontsize` で 0、全再構築との一致 |
| **Q5** | 🥉 | `restyleGeneratedBoxes`・`cacheStyle` | 文字に効くプロパティ（`white-space`・`text-transform` 等）が不変なら `TextShaper.isAlreadyNormal` を呼ばない。`inheritsExplicitly` は `style` 属性の版が変わったときだけ計算する。`rawDependsOnViewport` を生宣言表ごとにキャッシュする | varied `inherited` `-10%`（`isAlreadyNormal` 6.7% ＋ `toLowerCase` 2.6% ＋ `dependsOnViewport` 5.0%） | JFR |
| **Q6**（W5） | △ 一部のみ（2026-11-12、§10.9）。幅変更 `12x` は未解消 | `FlexLayoutEngine.remeasure`・`GridLayoutEngine.measureInArea` | 幅変更で、**内容の幅に依存しない子孫（固定幅・内容で決まる幅で、利用可能幅が min/max-content の外側にある箱）の測定を再利用**する。測定キャッシュの鍵を「利用可能幅」から「利用可能幅が属する区間（min-content〜max-content のどこか）」へ一般化する | showcase 幅変更 measure `0.36 → <0.15 ms` | `resizeexact` で計測、`LayoutKeyEquivalenceTest` |
| **M1** | 🔒 計測 | `FineProfileMain`・JMH | 幅変更の KPI を `resizeexact` で取る（追加済み）。ホスト経路の間引きで、`resizeframe` が「何もしないフレーム」を測ってしまう点を注記 | — | — |

**順序の根拠**: Q1 は唯一 **A/B で効果の上限を実測済み**（-48% / -94%）で、`child`・幅変更・確保量・将来の RTI（§8.4 の固定 extent）すべての前提になる。Q2・Q3 は `inherited` の 30% 以上を占め、変更が局所的で一致テストも既存のものが使える。Q4 は 1 関数の条件変更で `fontsize` の 8% を消せる。

### 10.6 「なぜ遅いのか」の総括

1. **「パッチが当たらない」段階はもう過ぎた**。パッチ命中は全シナリオで 100%（§9.3・§10.2）で、同形リストでは Chrome 以下になった。
2. 残る遅さは、**当たったパッチの内側で、計算を省くための前提（同一性・整数性・祖先の性質）が細かすぎる／壊れやすい**ことから来る。小数 1 つで平行移動が無効になり（C1）、スタイルが 1 つ変わると全枠を何度も比べ直し（C2）、要素ごとに祖先をたどり直し（C3）、`font-size` 1 つで箱の木を作り直す（C4）。
3. Chrome との差が残る 3 項目（varied `inherited` 1.9x、varied `child` 1.25x、幅変更 12x）は、それぞれ C2+C3、C1、C1+Q6 で説明できる。Chrome は固定小数点の座標と親相対の断片、グループ化された算出スタイル、レイアウト結果のキャッシュでこれらを構造的に避けている。
4. 計測の側にも穴があった（M1）。**実装の効果を測るベンチが、その実装の経路を通っているか**を、カウンタ（`BOXMEMO translated`・`LAYOUTSKIP shifted`）で毎回確かめる。

**再現コマンド**（§9.2 に追加）:

```powershell
.\gradlew.bat :core:profile "-Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain" "-Pprofile.args=childfixed varied-list-2000 600 null"
.\gradlew.bat :core:profile "-Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain" "-Pprofile.args=resizeexact showcase 2000 null"
.\gradlew.bat :core:profile "-Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain" "-Pprofile.args=inherited large-list 3000 null" "-Pprofile.jfr=build/prof/large500-inherited.jfr"
```

### 10.7 Q1 / Q1a 実装記録（2026-11-12 完了）

#### 10.7.1 方針の変更点（Q1 の「固定小数点化」を 2 段に分けた）

§10.5 の Q1 は、座標型を `LayoutUnit`（1/64 px の int）へ全面置換する案だった。`LayoutBox` / `InlineFragment` / `DisplayItem` / 全エンジンの `float` を書き換える規模で、一度に入れると一致テスト（1,950 件）の切り分けが難しい。そこで **float のまま、縦方向の寸法の源だけを 1/64 px の格子に載せる**第 1 段を先に実装した。float は「格子の倍数かつ絶対値 `< 2^18`（= 2^24 / 64）」なら加減算が厳密になるので、格子に載った部分木の小数ぶんの平行移動は、原点を変えて置き直した結果と**ビット単位で一致**する（結合的な加算の性質だけを借りる）。全面置換（第 2 段）は、下の「残課題」に回す。

#### 10.7.2 実施内容

1. **Q1a（断片の再利用を置き直し経路でも効かせる）**: `resetPlacements` が `inlineFragments.clear()` していた部分を、`discardFragments()`（見える側は空にして描画が古い断片を読まないようにしつつ、オブジェクトを最大 16 個の控え `spareFragments` へ回す）に置換。`addFragment` は `fragmentCursor` の既存要素 → 控え → 新規生成の順に取る。当初案の「`clear()` せず `fragmentCursor = 0` だけにする」は、layout がたどらない箱（行に入らなくなった箱など）で**古い断片が描画から見える**ので採らなかった（`resetPlacements` の本来の目的に反する）。
2. **`LayoutUnit`（`core/kernel`）**: `snap`（1/64 への丸め）、`isOnGrid`（格子の倍数かつ `< 2^18`）、`EXACT_LIMIT = 2^18`、`SHIFT_MARGIN = 2^14`。層依存ラチェット（下位 → 上位禁止）のため `kernel` に置いた（`text` から参照するので `layout.model` では不可）。
3. **縦の寸法の源を格子へ**: `LayoutMetrics.lineHeightPx`（px・倍率・`normal` の 3 種）、`FontFace.normalLineHeight`、`BitmapFontFace.ascent/descent` を `snap` する。これでインライン箱の矩形（断片を囲む）、行の下端（`baseline + descent`）、`leadingAbove`（floor 済み）がすべて格子に載る。表示上の影響は最大 1/128 px。
4. **平行移動の条件を緩和**（`LayoutBox.layout`）: 従来の `isIntegral(parentY - lastLayoutY)` を `isShiftableDelta` に置換。整数ぶんは従来どおり。小数ぶんは、①移動量と両方の原点が格子の倍数、②原点の絶対値が `EXACT_LIMIT - SHIFT_MARGIN` 未満、③**部分木の縦の値（`y`・`staticY`・`inlineContentBottom`・断片の `y`・`lastLayoutY`）がすべて格子に載っている**（`isGridAligned`。`isShiftableSubtree` の再帰に相乗りするので追加の走査はない）、のときだけ平行移動する。格子に載らない部分木は従来どおり置き直す（安全側に倒れる）。
5. **潜在バグの修正（`shiftSubtree`）**: 配置されなかったインライン箱（`br`、文字以外の断片を持たない空の `span`）は位置が `0,0` のままで、置き直しでは動かないのに、平行移動は他の箱と同じく `x / y` を足していた。一から組んだ結果と `y = -342`（`WidthOnlyReuseTest` seed 183）や `-15`（`TableIncrementalLayoutTest` seed 11）のずれが出た。整数ぶんの移動でも起き得たが、小数が除外されていたために顕在化していなかった。`isUnplacedInline()` で判定して動かさない。

#### 10.7.3 計測（2026-11-12、`FineProfileMain`、null バックエンド）

| 項目 | 実装前（§10.2 / §10.3.1） | 実装後 | 変化 |
| :-- | :--: | :--: | :-- |
| varied-2000 `child` mean / p50 | `4.60`（§10.3.1 の同条件 `4.17`）/ `4.54` | **`2.02` / `1.96`** | **-56%（同条件比 -51%）**。`childfixed` の実測 2.16 ms（上限の目安）を下回る |
| varied-2000 `child` place | `2.76` | **`0.93`** | -66% |
| varied-2000 `child` 確保 | `4,969 KB/frame` | **`271 KB/frame`** | **-95%** |
| `LAYOUTSKIP shifted`（/frame） | `0` | **`1,751`**（+ skipped 249 = 全行） | 近道が全行で効く |
| large-2000 `child` | `1.44` ms / `897 KB`（§9.10） | **`0.31` ms / `137 KB`**（large-list 800 frames、shifted 251） | 参考（条件が §9.10 と違う: 行数 500 の large-list） |
| showcase `resizeexact` | `0.95` ms / `MATCHFAIL 4=54` | `0.90` ms / `MATCHFAIL 4=54` | **変化なし**（下記） |

**showcase の幅変更は改善していない**: その箱は flex / grid / 中央寄せで、`isShiftableSubtree`（FLOW のみ）が平行移動を許さず（`LAYOUTSKIP shifted=0`）、`MATCHFAIL 4` は表示項目側（`DisplayListBuilder.isIntegerTranslation`）の条件で、横方向の小数移動を含む。この 2 つは第 2 段（下記）と Q6 の課題として残る。§10.5 の見込み「`MATCHFAIL 4` `54 → ~0`」は**未達**。

#### 10.7.4 テスト

- `BlockLayoutOptimizationTest`: `resetPlacementsKeepsFragmentObjectsForReuse`（置き直し後に見える断片は空・控えから再利用・値は上書き）、`spareFragmentsAreBounded`（控えは 16 個まで）。
- `FractionalShiftEquivalenceTest`（新規）: ①`lineHeightsLieOnTheLayoutUnitGrid`、②`layoutUnitSnapAndGrid`（丸め・格子判定・結合性・`2^17` 超の高い文書・上限 `2^18`）、③`fractionalInsertAndRemoveShiftsAndMatchesFullLayout`（小数の高さの行を挿入・削除し、全ての箱・断片の `x/y/w/h/baseline/descent` を**ビット単位**で新規レイアウトと比較。`layoutsShifted > 100` も要求）、④`randomInsertRemoveSequencesMatchFullLayout`（乱択 25 手。行に空の `span` と `br` を含め、配置されなかった箱の回帰を捕える）。
- 既存の一致テスト（`TableIncrementalLayoutTest`・`WidthOnlyReuseTest`・`InlineLayoutConsistencyTest`・`LayoutGoldenTest` ほか）が、潜在バグ（上記 5）の検出と、格子化後の値の整合（`normalLineHeight` を `BoxMeasurer` が未丸めで使っていた不整合）の確認に効いた。
- `:core:test` **全件グリーン**（1,950 件超、`LayerDependencyRatchetTest`・`PatchHitRateRatchetTest`（child 確保 385 KB/op ＜ 上限 2.5 MB）を含む）。

#### 10.7.5 残課題（Q1 第 2 段）

1. **表示項目の小数移動**: `DisplayItem` は丸めた整数座標なので、小数 `dy` では `isIntegerTranslation` が落ちて項目を作り直す（varied `child` の display `0.22 ms` は子の追加パッチ経由で小さいが、`resizeexact` の `MATCHFAIL 4` はこれ）。親相対の項目を持つか、`dy` が格子の倍数のとき項目ごとに「丸め後の差」が一定になることを確かめて平行移動する。
2. **横方向**: `parentX` の変化（幅変更で中央寄せが動く）は今回の対象外（縦のみ）。`LayoutUnit` を `x` へ広げるには、横の寸法の源（`advance`・`letter-spacing`・em の padding / margin）の格子化が要り、折り返し判定の境目に影響するので、別の検証（行の折り返し位置の一致テスト）とセットで行う。
3. **flex / grid の部分木**の平行移動（`isShiftableSubtree` の FLOW 限定の緩和）。
4. 座標型そのものの置換（`float` → `int` の `LayoutUnit`）と親相対化は、1〜3 の効果を見てから判断する。
5. `EXACT_LIMIT`（2^18 px = 262,144 px）を超える高さの文書では、小数の平行移動は無効（従来どおり置き直す）。

### 10.8 Q2 ②（`ComputedStyle.diff` の (旧, 新) 組メモ）実装記録（2026-11-12 完了）

#### 10.8.1 実施内容
- `ComputedStyle.changedIds(a, b)` を追加し、「値が違う添字（昇順の `int[]`）」を **(旧, 新) の同一性の組**で覚える。256 枠の直接写像（枠は `declHash` から決める。衝突は組の同一性を比べて上書き）。`diff` は添字配列を再生して visitor を呼ぶだけになり、継承側（`extras`）の比較は従来どおり。
- 効くのは、同形のリスト（全行が同じ旧スタイル・同じ新スタイルを共有）と、1 要素の再スタイルで `diff` が 2〜3 回呼ばれる分（`propagationOf`・`isPositionOnlyChange`・`isGeometryOnlyChange`・`computeLayoutAffectingChange`）。算出スタイルは不変なので、メモの無効化は要らない。診断用に `diffMemoHits()` / `diffMemoMisses()`。
- 計画書の ①（1 回の走査でビット集合を返す `diffSummary`）は、メモで repeat が消えたので後回し。③（`declHash` 一致の足切り）は、走査が参照比較のみで十分速く、ハッシュ一致でも衝突の検証が要るため採らない。④（算出スタイルのグループ化）は大きな構造変更なので別途。

#### 10.8.2 計測（`FineProfileMain`、GL バックエンド）
| 項目 | 実装前（§10.2） | 実装後 |
| :-- | :--: | :--: |
| large-500 `inherited` mean（restyle） | `0.77`（`0.36`） | **`0.66`（`0.25`）**（-14%、restyle -31%） |
| varied-2000 `inherited` mean | `6.81` | **`6.37`**（-6.5%） |

varied-2000 は行ごとにスタイルが違うので組は重複せず、メモが効くのは 1 要素内の 2〜3 回の重複分だけ。JFR（null、1,500 frames）で `ComputedStyle.diff` は **inclusive 20.3% → 13.9%**（残りは各組の最初の 1 回の全枠走査 `scanChangedIds` 12.6% exclusive）。**見込み「`diff` `<5%`」は未達**: 組が一意な文書では、全枠走査自体を減らす④（グループ化）か、スタイル共有（`ShareCache`）の拡大が要る。

#### 10.8.3 テスト
`ComputedStyleDiffMemoTest`（新規）: ①乱択 3,000 組で `diff` が `asMap()` の直接比較と一致（メモ命中の 2 回目も同一）、②同じ組の全枠走査は 1 回だけ（ミス 1・ヒット 50）、③600 組で 256 枠を衝突させても結果が混ざらない。`Milestone2GateTest`（`ComputedStyle` で `Arrays.copyOf` 禁止）に合わせて配列は 2 パスで確保。`:core:test` 全件グリーン（1,951 件）。

### 10.9 Q6（幅変更の測定の再利用）実装記録・調査結果（2026-11-12、部分達成）

#### 10.9.1 調査: 幅変更で何が測り直されているか
`resizeexact showcase`（1 フレームごとにビューポート幅を 1px 往復）で、`measureCore` が測り直した箱を、前回の測定との差の理由別に数えた（一時的な診断。コードは残していない）。**1 フレーム約 290 回**測っており（DOM 164 要素、flex の 2 段の測定を含む）、内訳は次のとおり。

| 理由 | 割合（回数） | 内容 |
| :-- | :--: | :-- |
| ビューポートが違うだけで、制約は同じ（`spaceEq=true`） | 約 11%（実装前） | `canReuseMeasurement` が `Viewport.equals`（幅も含む）を要求していた。部分木が箱の外の寸法を読まなくても、幅が 1px 違うだけで測り直し |
| `fit` 再利用の条件のうち、高さの % の基準（`percentageBlockSize`）が違う | 約 6%（`button` ほか） | 高さの % を読まない箱（`measuredBlockPercentFree`）でも、基準が違うと fit 再利用・幅だけの再利用が外れた |
| 使える幅そのものが変わる（flex の fill 幅の項目・段落） | 大半（約 50%） | 正当な測り直し。内容が使える幅を埋める箱は、幅が変われば結果が変わる |
| flex の 2 段の測定（仮 → 確定）が、使える幅の変化で仮の産物に当たらず交互に追い出す | 約 20% | flex 項目の `measuredSpace` が、仮の測定（上書き幅なし）と確定の測定（上書き幅あり）で毎フレーム入れ替わる。項目の内容が段落（fill）だと fit の必要幅が無限大になり、仮の産物（`Tentative.matches`）も当たらない |

#### 10.9.2 実施内容（安全で局所的な 2 点）
1. **ビューポートの幅を無視した再利用**（`LayoutBox.sameViewportForMeasure`）: 部分木が箱の外の寸法（ビューポート単位・絶対配置）を読まない（`measuredOutsideFree`）なら、制約が同じ測定は、ビューポートの高さ・解像度比が同じ限り使い回す。画像の候補選び（`srcset` / `sizes`）がビューポートの幅を読むので、`img` / `picture` は `measuredOutsideFree` から外した。切り分け: `-Denderframe.layout.noviewportwidthfree=true`（`LayoutBox.viewportWidthFreeReuse`）。
2. **高さの % の基準を、fit 再利用・幅だけの再利用でも無視**: `measuredBlockPercentFree` の箱は、`widthOnlyChange` と `fitReuse` で `percentageBlockSize` の違いを理由にしない（制約が同じ場合の `sameIgnoringPercentBlock` と同じ扱いに揃えた）。

#### 10.9.3 計測（showcase `resizeexact`、null、3,000 frames）
| 項目 | 実装前 | 実装後 |
| :-- | :--: | :--: |
| フレーム mean / p50 | `0.869` / `0.800` ms | `0.822` / `0.750` ms（再計測 `0.874` / `0.772`。ばらつきあり） |
| `layout.measure` mean / p50 | `0.347` / `0.314` ms | **`0.302〜0.320` / `0.268` ms**（約 -10〜14%） |
| 確保 | `1,198 KB/frame` | `1,121 KB/frame`（-6%） |
| 測り直し（診断、1,000 + 600 frames の累計） | 約 520,000 | 約 464,000（-11%） |

**結論: 幅変更 `12x`（Chrome `0.084 ms`）は解消していない。** フレーム全体で `約 -5%`、測定段で `約 -10%` にとどまる。§10.5 の見込み「measure `0.36 → <0.15 ms`」は**未達**。残りの測り直しの大半は、使える幅が実際に変わる箱（fill 幅の flex・段落）と flex の 2 段の測定で、測定キャッシュの鍵を「使える幅の区間」へ広げる程度では消えない。

#### 10.9.4 次の一手（未実施。効果の大きい順）
1. **flex の 2 段の測定の追い出し**: 項目の `measuredSpace` が仮（上書きなし）と確定（上書きあり）で毎フレーム入れ替わり、確定の測定が毎回外れる。確定の測定の記録を、仮の測定とは別に持つ（`Tentative` の種類を増やすか、確定用の `measuredSpace` を別枠にする）。測り直し全体の約 20% が対象。
2. **fill 幅の block の「幅が変わっても内容が同じ」判定**: 段落が折り返さず収まる幅の範囲（`fitNeed`）を、block の fill でも求める（現状は shrink-to-fit だけ）。flex の項目の中身が段落のとき、仮の産物が当たる。
3. 表示リスト側（`display-list` `0.30 ms`）の `MATCHFAIL 4`（§10.7.5）。幅変更のフレームの約 37% を占め、測定と同じ大きさの残りの塊。

#### 10.9.5 テスト
- `ViewportWidthFreeReuseTest`（新規）: 固定幅・`vw`・`position:absolute`・`img sizes="100vw"`・flex（`flex:0 0 260px` ＋ `flex:1`）・fill の段落が混在する文書で、ビューポート幅を 10 通り往復させ、①再利用あり・なしの結果が一致、②毎回一から組んだ結果と一致（全箱の `x/y/w/h`）、③測り直しが増えない、を確認する。
- 既存の `WidthOnlyReuseTest`（乱択 60 文書 × 10 幅。`vw`・絶対配置を含む）、`LayoutKeyEquivalenceTest` 等が一致の主な安全網。`:core:test` 全件グリーン。

### 10.10 幅変更の Chrome 比較を同じ範囲で測り直す（2026-11-13）

#### 10.10.1 これまでの「12x」は比較の範囲が違っていた
Chrome 側の幅変更は `bench.html` の `relayout`（`iframe` の幅を `800` / `799` px で往復）に続けて、`getComputedStyle(...).color`（スタイル再計算の強制）と `body.offsetHeight`（レイアウトの強制）を呼ぶ時間（`relayout.styleLayout`）で、**ペイント・ラスタ・合成を含まない**。一方 `FineProfileMain resizeexact` の「フレーム mean」は、**表示リスト生成（`display-list`）まで含む**。表示リストはこちらのエンジンがレイアウトの結果から描画命令を作る段で、Chrome ではペイント段（上の強制呼び出しに含まれない）に当たる。つまり 0.88 ms（こちら全体）と 0.084 ms（Chrome のスタイル＋レイアウト）を比べていた。

#### 10.10.2 同じ範囲での測り直し
- Chrome（157.0.8103.51、`node run.js chrome showcase`、164 要素、30 回の中央値の集計）: `relayout.styleLayout` **`0.082 ms`**（min `0.074`、max `0.118`）、うち `relayout.style` **`0.0035 ms`**（幅変更ではスタイルの再計算がほぼ走らない）。
- `:core`（`FineProfileMain resizeexact showcase 4000 null`、3 回実行、段別 mean の範囲）:

| 範囲 | 含む段 | `:core` | Chrome | 比 |
| :-- | :-- | :--: | :--: | :--: |
| **レイアウトのみ** | measure + place | **`0.32 ms`**（measure `0.29〜0.30` + place `0.03`） | 約 `0.078`（`styleLayout` − `style`） | **約 4x** |
| **スタイル＋レイアウト**（Chrome の強制呼び出しと同じ範囲） | style+build `0.07〜0.08` + box-tree `0.05` + measure + place | **`0.44〜0.45 ms`** | **`0.082`** | **約 5.4x** |
| フレーム全体（表示リスト込み） | 上 + display-list `0.29〜0.30` | `0.80〜0.82 ms` | ペイントを含まないので比較不可 | （従来の 10〜12x はこれとの比較） |

#### 10.10.3 読み方と、残りの差の中身
1. **差は 12x ではなく 約 4〜5x**。§10.9 までに「12x を詰める」と書いていた目標は、範囲を揃えると、レイアウトで `0.32 → 0.08 ms`（約 4x）、スタイル＋レイアウトで `0.45 → 0.08 ms`（約 5.4x）が実際の差になる。
2. **スタイル段の差が目立つ**: Chrome は幅変更で `0.0035 ms`（再計算なし）、こちらは `style+build` `0.075 ms` と `box-tree` `0.047 ms` を毎回払っている（合計 `0.12 ms`。Chrome のスタイル＋レイアウト全体 `0.082` より大きい）。幅変更でスタイルに効くのはメディアクエリ・コンテナクエリ・ビューポート単位だけなので、**それらを使わない（`StyleSheet.updateMediaEnvironment` / `declaresContainers` が false の）文書では、幅変更で再スタイルと箱の木の更新を丸ごと飛ばせる**（新しい着手候補 **Q7**）。
3. 測定 `0.29 ms` は §10.9.1 のとおり flex の 2 段の測定と fill 幅の再測定が大半（Chrome は `LayoutNG` の結果キャッシュで同等の処理を飛ばす）。表示リスト `0.29 ms` は Chrome ではペイント段で、同じ範囲には入らない。ただし**幅変更のユーザー体感（フレーム全体）としては効く**ので、`MATCHFAIL 4`（§10.7.5）の解消は引き続き有効。
4. 以後、幅変更の KPI は**2 本立て**にする: ①Chrome 比較用（スタイル＋レイアウト。目標 `< 0.20 ms`、約 2.4x 以内）、②フレーム全体（表示リスト込み。目標 `< 0.50 ms`）。

#### 10.10.4 再現
```powershell
cd core/src/bench-crossengine
node run.js chrome showcase        # res-chrome-showcase.json の res.relayout.styleLayout / res.relayout.style
cd ../../..
.\gradlew.bat :core:profile "-Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain" "-Pprofile.args=resizeexact showcase 4000 null"
```
Chrome の `res-*.json` は `.gitignore` 対象の出力なので、計測のたびに再生成する（今回の値はこの記録のみ）。

---
