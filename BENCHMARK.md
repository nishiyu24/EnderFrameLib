# EnderFrameLib 性能ベンチマーク

描画パイプラインとサーバー状態の性能の基準値と、フェーズごとの比較記録。
計画は [REFACTORING_PLAN.md](REFACTORING_PLAN.md)、仕組みは [ARCHITECTURE.md](ARCHITECTURE.md) を参照。

数値はすべて開発機（Windows / JDK 21）での JUnit 上の測定で、ゲーム内のフレーム時間ではない。
マシンが変われば絶対値は変わるので、比べるのは同じ表の中の前後だけにする。

---

## 1. 測定の対象

### パイプラインの段

1. **Restyle（再スタイル）**: カスケードを解いて算出スタイル（`ComputedStyle`）を確定する時間。
2. **Relayout（再レイアウト）**: ボックスツリーの `measure` と `layout` の時間。
3. **DisplayList（ディスプレイリスト生成）**: レイアウト結果から描画要素の列（`List<DisplayItem>`）を作る時間。
4. **ScrollStep（スクロール 1 段）**: スクロールコンテナを 20px 動かしたときの再レイアウトとディスプレイリスト再生成の合計。

### 代表文書

- **`showcase.html`**: グリッド・フレックス・装飾・入れ子の箱など多くの CSS 機能を使う複合文書。
- **`guidebook.md`**: Markdown から変換した、段落・見出し・インライン修飾・リスト・表を含む文書。
- **`large-list (500 items)`**: 500 行のリストを `overflow-y: auto` のスクロールコンテナに入れた高密度文書。

---

## 2. 回帰の基準値（固定）

- **ファイル**: `src/test/resources/benchmark/baseline.txt`（2026-10-24 に更新、3 回測った中央値。初回は 2026-10-20）
- **テスト**: `PerformanceBenchmarkTest`（厳密モード時: ウォームアップ 5 回、測定 15 回の平均 / 通常 `test` 時: ウォームアップ 1 回、測定 2 回のスモーク実行）
- **通常テストとの分離**: `./gradlew test`（全件実行）では `IncrementalLayoutFuzzTest` 等の先行テストによる GC・CPU 負荷で実時間が揺れるため、決定論的な検証（描画結果の生成・基準値ファイルの整合性・保持 DOM ノード数）のみを強制し、実時間（ms）と GC ヒープ差分の超過は警告（`[WARN]`）出力に留める。厳密な回帰判定（「実測 ≤ 基準値 × 2.0 + 3ms」を超えたら落とす）は単独 JVM で走る `./gradlew benchmark`（または `-Dbenchmark.enforce=true`）で行う。レポートは `build/reports/benchmark-results.txt`。

```bash
./gradlew benchmark                                                          # 単独 JVM で厳密な回帰判定（-Dbenchmark.enforce=true）
./gradlew test --tests '*PerformanceBenchmarkTest' -Dbenchmark.enforce=true  # PerformanceBenchmarkTest のみ厳密実行
./gradlew test --tests '*PerformanceBenchmarkTest' -Dbenchmark.update=true   # 基準値の更新（git diff で確認してからコミット）
```

| Document | Restyle | Relayout | DisplayList | ScrollStep |
| :--- | :---: | :---: | :---: | :---: |
| `showcase.html` | 16.3 ms | 15.2 ms | 4.8 ms | 17.5 ms |
| `guidebook.md` | 6.7 ms | 3.5 ms | 2.7 ms | 4.9 ms |
| `large-list (500 items)` | 19.9 ms | 8.9 ms | 10.1 ms | 13.7 ms |

2026-10-20 の値は showcase.html が 9.1 / 10.6 / 3.3 / 12.4 ms で、他の 2 つは今とほぼ同じだった。
変わったのが showcase.html だけなのは、2026-10-24 のコミット `f34cc9b` で文書自体が拡張されたため（要素 99 → 163、+65%。本文 14.3KB → 23.6KB、ディスプレイリストのゴールデン 556 → 714 行）。処理系の退行ではなく文書規模に概ね比例した増加（restyle は +79%）で、以後は 163 要素版を基準とする。

### 最新の実測（2026-10-24）

| Document | Restyle | Relayout | DisplayList | ScrollStep |
| :--- | :---: | :---: | :---: | :---: |
| `showcase.html` | 9.832 ms | 9.668 ms | 2.582 ms | 9.927 ms |
| `guidebook.md` | 4.471 ms | 2.296 ms | 1.900 ms | 3.208 ms |
| `large-list (500 items)` | 18.988 ms | 7.998 ms | 8.022 ms | 10.691 ms |

この表の ScrollStep は、ベンチが毎回パイプラインを全部通す測り方の値。実際のスクロールは §4 の近道を通る。

---

## 3. 経緯: R0 → R1

R1（ライブ DOM）で初回構築と再スタイルが悪化していないことを確かめるために採った値（2026-10-19、測定方法は §2 と同じ）。

| Document | 段 | R0 | R1 |
| :--- | :--- | :---: | :---: |
| `showcase.html` | Restyle / Relayout / DisplayList / ScrollStep | 11.294 / 8.555 / 2.161 / 10.486 ms | 15.274 / 9.707 / 2.218 / 11.252 ms |
| `guidebook.md` | 同上 | 14.015 / 2.416 / 1.787 / 3.858 ms | 16.663 / 3.032 / 1.778 / 4.066 ms |
| `large-list (500 items)` | 同上 | 18.034 / 5.472 / 5.646 / 9.958 ms | 31.122 / 8.448 / 9.795 / 17.692 ms |

R1 の時点では対話状態とスクロール量をノードの参照をキーにした弱参照の表へ移し、id 索引と live collection を入れた
（いまの `InteractionState` と `core/layout/model/ScrollState`）。`large-list` の再スタイルが一時的に悪化したが、
§2 の最新の実測では R0 の水準に戻っている。

---

## 4. 動的操作の近道（リサイズ・スクロール・ドラッグ）

- **測定日**: 2026-10-23
- **測定方法**: `showcase.html` の末尾に絶対配置のウィンドウ（`#win`, `width: 240px`）と 200 行のスクロール領域を足した文書で、
  1 回の更新（描画要素の生成まで）の平均時間（ウォームアップ 10 回、測定 30 回、3 回測った値の範囲）。
- **正しさ**: `IncrementalPipelineTest` が、どの近道も全部作り直したときと同じディスプレイリストを出すことを確かめる。

| 操作 | 以前の経路 | 以前 | 現在の経路 | 現在 |
| :--- | :--- | :---: | :--- | :---: |
| ウィンドウのリサイズ 1 段 | `init()` のたびに HTML を解析し直して全要素を再スタイル | 47〜49 ms | 文書を保ち、ビューポート単位を使う要素だけ再スタイル | 15 ms |
| スクロール 1 段 | 箱の木を作り直して measure × 2 + layout | 14〜15 ms | layout（位置決め）だけ | 3.4 ms |
| `left` / `top` のドラッグ 1 段 | 再スタイル後に箱の木を作り直して measure × 2 + layout | 14 ms | 再スタイル後に layout だけ | 3.7〜4.0 ms |

リサイズは、さらに描画キューで 1 フレーム 1 回にまとめ、直前のレイアウトにかかった時間の 2 倍が過ぎるまで次の反映を待つ
（`SessionContext.RESIZE_THROTTLE_FACTOR`）。（当時の MOD の旧レンダラ `DisplayItemDrawer` の祖先の変形の使い回しは、M3a で旧レンダラごと撤去した。今の面はコアの描画が描く。）

---

## 5. 差分レイアウト（measure の再利用）

> **2026-11-26（M2c-3）**: この節は旧経路（`LayoutBox`）の記録。旧経路は削除し、差分は変更台帳 `ChangeLedger` → 箱の木
> `BoxTreeCompiler` → 断片の記憶 `LayoutCache`（鍵は `(BoxNode ==, ConstraintSpace の値等価)`）の 1 本になった。今の数値は
> `:core:memoryAndLatencyGate`（`ab-inc-vs-full:*`・`latency:*`）と計画書 §4。正しさは `IncrementalOracleTest`・`IncrementalVsFullTest`・
> `ServerClientLayoutParityTest`。

- **測定日**: 2026-10-23
- **仕組み**: 再レイアウトのたびに箱の木を作り直さず、DOM の節ごとに前回の `LayoutBox` を使い回す。箱は前回の measure の
  入力（制約・親が決めた寸法・ビューポート・書体）と中身の署名（算出スタイル・表示する文字・属性・子の並び）を覚えていて、
  どちらも変わらず子孫も変わっていなければ measure を飛ばす（`LayoutBox.measure`）。汚れの判定は今の状態と署名の比較で決めるので、
  サーバーの変異・クライアントのローカル API・DOM API のどれで変えても同じ結果になる。表とその中、`display: none` の部分木は作り直す。
- **正しさ**: `IncrementalLayoutTest`（乱数の変異列で、差分・全部作り直し・スタイルのキャッシュも無しの 3 つが一致すること）と
  `ServerClientLayoutParityTest`（同じ変化をサーバーの変異とクライアントの操作で起こし、全部作り直しと一致すること）。

| 文書 / 変化（1 要素） | 全部作り直し | 差分レイアウト |
| :--- | :---: | :---: |
| 500 行のリスト / 文字の変更 | 17.8 ms | 3.2 ms |
| 500 行のリスト / 幅の変更 | 17.5 ms | 3.4 ms |
| `showcase.html` / 文字の変更 | 10.2 ms | 6.2 ms |
| `showcase.html` / 幅の変更 | 8.4 ms | 5.9 ms |

`showcase.html` で伸びが小さいのは、表（毎回作り直す）と stretch される flex アイテム（親が measure の後に高さを書き換えるので
使い回せない）が多いため。ディスプレイリストの生成（約 2.4 ms）は差分化していない。

### R5 の残りの目標（2026-10-25 時点）

- 達成: 要素ごとの無効化（`InvalidationSets` の接続）で hover の色変化・class のトグルが再スタイルの全体を回さない（1 行ホバー 0.3〜0.5 ms）。
  `opacity` / `transform` / 色 / 影 / 角丸だけの変化は再レイアウトを要求しない。再レイアウト境界（size containment・確定サイズのスクロールコンテナ）
  の中だけの変化は、境界の外の測定も配置も飛ばす（`RelayoutBoundaryTest`）。配置の使い回しで、リストの途中への 1 行追加は挿入位置より前の行を置き直さない。
- 未達: 1 行追加が 1 ms 未満（後ろの行は位置がずれるので置き直す。相対的な位置の持ち方にしないと下がらない）。ディスプレイリスト生成の差分化。

---

## 6. サーバー状態とパケット帯域（H0 → H1）

- **測定日**: 2026-10-23（H0）/ 2026-10-24（H1・番号化）
- **測定対象**: 代表的な機械 UI `Electric Crusher`（`src/test/resources/benchmark/machine_ui.html`。スロット 2 個、進捗バー、
  エネルギーバー、状態表示、電源ボタン）
- **H0（互換モード）**: サーバーの `WebSession` が DOM を持ち、初回に DOM を直列化して送り（`dom_sync`）、以後は
  `DomMutationRecord` を送る。値は `src/test/resources/benchmark/server_baseline.txt` に固定し、
  `PacketBandwidthTest` / `StateMemoryBenchmarkTest` が完全一致で確かめる。
- **H1（Headless State）**: サーバーは `UIState` の Key-Value だけを持ち、初回はテンプレート ID と初期状態
  （`OpenUiS2CPacket`）、以後は変わったキーだけ（`StateDeltaS2CPacket`）を送る。`HeadlessStateBandwidthAndMemoryTest` が
  H0 との比と当初の目標（初回 400 bytes 未満・単一更新 30 bytes 未満・100 回の累計 3,000 bytes 未満）、保持 DOM ノード 0、
  100 セッションで 10MB 未満を確かめる。パケットは手で組まず、`UIStateImpl` が実際に送るものを測る。
  結果は `build/reports/headless-state-bandwidth-result.txt`。

### パケット帯域

| 項目 | H0（互換モード） | H1（Headless State） | 削減 | 当初の目標 |
| :--- | :---: | :---: | :---: | :---: |
| 初回オープン | 4,548 bytes | 101 bytes | 97.8% | < 400 bytes（達成） |
| 単一更新（2 項目） | 166 bytes | 20 bytes | 88.0% | < 30 bytes（達成） |
| 100 回の進捗更新の累計 | 16,584 bytes | 1,992 bytes | 88.0% | < 3,000 bytes（達成） |

番号化の前は単一更新 59 bytes・累計 5,892 bytes だった。インスタンス ID（`block:x,y,z`）とキー名を毎回文字列で送り、
値を `CompoundTag` で包んでいた（キーごとに名前と型、末尾に終端が付く）ため。いまは:

- インスタンスは状態ごとの番号（`wireId`、VarInt）。キーは状態ごとの辞書（`StateNbtConverter.KeyTable`、追記だけで番号を使い回さない）の番号。
  開くパケットが辞書の全体を 1 回送り、差分は前回の差分以降に増えたキーの名前だけを添える。
- 値は `VarInt 番号 + NBT の型 1 byte + 中身` を並べる（Compound で包まない）。削除は番号の一覧。
- 受信側はデコード時に番号を名前に戻すので、クライアントのハンドラとテストはキー名の `CompoundTag` のまま扱う
  （統合サーバーのメモリ接続はエンコードを通らず、送信側の値がそのまま渡る）。

単一更新 20 bytes の内訳: wireId 1 + flags 1 + 件数 1 + `progress`（番号 1 + 型 1 + double 8）+ `progress_text`（番号 1 + 型 1 + 文字列 2 + 3）。
数字はペイロードだけで、カスタムパケットの ID（`enderframelib:state_delta`）は H0 と同じく含めていない。

### サーバーのヒープと保持オブジェクト（100 セッション）

| 項目 | H0（互換モード） | H1（Headless State） |
| :--- | :---: | :---: |
| ヒープ消費量 | 約 35.6 MB（1 セッションあたり約 350 KB） | 約 0.1 MB（102,400 bytes） |
| 保持 DOM ノード数 | 3,000（1 セッション 30） | 0 |

ヒープは GC の前後の使用量の差で測るので、実行ごとに数十 KB 揺れる（H0 は 35.63〜35.66 MB を観測）。

### インクリメンタル計測（R0+、2026-10-25）

500 行のリストで局所変更 1 回あたりの時間。基準値は `src/test/resources/benchmark/incremental-baseline.txt`。

| 操作 | 時間 |
| :--- | :---: |
| 1 行ホバー | 0.3〜0.5 ms |
| 無関係な class トグル | 0.15〜0.25 ms |
| 子孫セレクタに効く class トグル | 17〜31 ms（500 行が実際に変わる） |
| 1 行追加（差分レイアウト。Phase 2 の前は 6.6 ms） | 3.2〜4.4 ms |
| スクロール 1 段の `repositionLayout` | 0.3〜0.8 ms |

この機械は負荷で 3 割ほど揺れる（基準値の記録時より遅い）。コミット済み（HEAD）の状態でも `showcase.html` の再レイアウトは 25〜28 ms で、`baseline.txt` の許容幅（基準値 × 2 + 3 ms = 33.4 ms）に近い。

単一要素のホバーは dirty 要素だけ再計算するので Phase 2 の目標 `< 1.0 ms` を既に満たす。子孫セレクタに効く class トグルの時間は、500 行の見た目が実際に変わる仕事の量（行の再スタイル）で、Phase 2 の無効化セットの接続後も変わらない。

### Phase 5 R9（表の `%` 列幅・multicol・grid の畳み・adoption agency）の前後（2026-10-26）

`./gradlew benchmark`（単独 JVM・厳密）は許容幅内（基準値 × 2 + 3 ms）。R9 の追加は既存の経路に分岐を足すだけ
（`MulticolLayout.isMulticol` は `column-count` / `column-width` が `auto` の箱で即 false）なので、段組を使わない文書の経路は変わらない。

| Document | Restyle | Relayout | DisplayList | ScrollStep |
| :--- | :---: | :---: | :---: | :---: |
| `showcase.html` | 11.814 ms | 15.236 ms | 4.598 ms | 17.823 ms |
| `guidebook.md` | 4.660 ms | 3.608 ms | 2.801 ms | 5.422 ms |
| `large-list (500 items)` | 17.337 ms | 9.892 ms | 9.375 ms | 12.768 ms |

段組の組版（400 段落・約 1,200 行・幅 380px）は `layoutDocument` 1 回で、段組なし 26.4 ms（best。平均 37.4）、
`column-count: 4` 29.2 ms（best。平均 32.0）。段組は組版を列の幅で 1 回行い、単位の列への詰めは二分探索（24 回 × 単位数）なので、
段組なしと同じ桁に収まる。

`@counter-style` を入れた最終状態でも `./gradlew benchmark`（厳密）は許容幅内（showcase 12.4 / 17.0 / 4.7 / 21.4 ms、
guidebook 7.9 / 5.7 / 3.4 / 8.0 ms、large-list 19.8 / 9.4 / 9.2 / 13.7 ms。Restyle / Relayout / DisplayList / ScrollStep）。

### Phase 4（フォントアトラス・UAX #14・direction・Range）の前後（2026-10-26）

同じマシン・同じ条件（`./gradlew test --tests '*PerformanceBenchmarkTest' --rerun-tasks`）で、HEAD（`02f7730`）の worktree と作業ツリーを交互に 3 往復した値（ms。Restyle / Relayout / DisplayList / ScrollStep。マシンが負荷で 3 割ほど揺れるので、往復の間にもばらつく）。

| 文書 | 指標 | HEAD（3 回） | Phase 4（3 回） |
| :--- | :--- | :---: | :---: |
| showcase.html | Restyle | 25 / 23 / 25 | 22 / 27 / 20 |
| showcase.html | Relayout | 35 / 28 / 35 | 31 / 38 / 35 |
| guidebook.md | Relayout | 7.5 / 4.8 / 5.9 | 8.5 / 5.8 / 9.0 |
| large-list | Relayout | 17.7 / 18.8 / 18.1 | 16.0 / 16.0 / 18.7 |

揺れの範囲で差は見えない。厳密な `./gradlew benchmark` も通る（`baseline.txt` の許容幅 = 基準値 × 2 + 3 ms の内側）。

途中で見つけて直した退行: 行の断片を置くたびに `fontStyleFor`（太さの解析を含む）で書体の descent を引いていて、showcase の Relayout が HEAD の 3 往復すべてより約 40% 遅かった（60 / 37 / 37 ms）。断片を作る時点（書体が手元にある）で descent を持たせる（`InlineFragment.descent`）と HEAD と同じ範囲に戻った。UAX #14 の `LineBreaker` は英字どうし（語の内側）を最初に判定して返すので、文字ごとの分類は語の境目でしか走らない。

字形の置き場（VRAM）: 13px の文字を `scale(1 → 3)` で動かしたときの置き場の種類は、量子化の前 667 フレームで 667 種、後は約 90 種（`AtlasCacheTest`）。同じサイズの `subpixel` × `lightText` は 8 枚のテクスチャから 1 つの `FontSet` になった。置き場ごとの字形面積から VRAM を見積もる `AtlasFootprint` と、文字の置き場（最低 32 MiB 予約・64 MiB 上限）と FBO プール（画面サイズのレイヤー 4〜16 枚分）を全体 192 MiB の枠で配分する `VramBudget` を入れた（`AtlasFootprintTest`：Arial の ASCII + 漢字 400 字 × 3 サイズ × 端数 4 段 = 1,128 字形で 3 枚・192 KiB）。

---

## `:core` の実フレーム計測（2026-11-17）

ここが `:core`（Minecraft に依存しない描画コア）の性能値の出所。`core/README.md` に書く性能値は、この節にある値だけにする
（`DocConsistencyTest` が検査する）。上の節までの JUnit の値は JIT のウォームアップが短く、ここの定常値より大きく出る。

- **道具**: `FineProfileMain`（実フレームの経路 `StandaloneView.frame` を、ウォームアップ 600 フレームの後に 400〜1,500 フレーム回す）。
  Chrome 側は `core/src/bench-crossengine/trace.js`（CDP のトレースから 1 フレームの CPU 時間 = main + raster + draw）。
  手順は [core/src/bench-crossengine/README.md](core/src/bench-crossengine/README.md)。
- **条件**: 開発機 1 台（Windows・JDK 21・論理 24 スレッド・800×600・ウォーム JVM）。`:core` は 3 回の中央値、Chrome 157（headless・GPU）は 3 回の中央値。
  時間は実行ごとに ±10〜15% 揺れる。**比が 0.85x〜1.15x の行は「互角」と読む。**
- **比較の範囲**: Chrome の値はこのエンジンが扱う範囲（JavaScript なし・単一文書）での 1 フレームの CPU 時間。Chrome は別プロセスの合成・
  サンドボックスを含む構成で、仕様の網羅率も違う（`:core` の WPT 参照比較は部分集合 260 組中 81 組）。「同じ文書の同じ変更にかかる時間」の比であって、
  エンジン全体の優劣ではない。
- **書体**: 以前の比較は `:core` が内蔵の簡易書体（文字幅の表だけ）、Chrome が OS の書体で、文字の測定の仕事量が揃っていなかった。
  下の「実書体」は両方に `* { font-family: Arial !important; }` を足して測った値。

### README に引用している値

`core/README.md` はここから引く（測り直したら両方を同じ値に直す）。

- スクロール 1 段（GL・実書体）: showcase `0.007 ms`、2,000 行のリスト `0.09 ms`（Chrome `0.50 ms`）
- `:hover` の切り替え（GL・実書体・showcase）: `0.10 ms`（Chrome `1.24 ms`）
- 文字の書き換え（GL・実書体・showcase）: `0.17 ms`（Chrome `1.07 ms`）
- 2,000 行のリストへの 1 行の挿入と削除（GL・実書体）: `1.44 ms`（Chrome `1.34 ms`。互角）
- 文書を開く（GL・実書体・showcase・ウォーム JVM）: `4.19 ms`（Chrome `5.30 ms`）
- 行ごとにデザインが違う 2,000 行の `font-size` 変更（GL・実書体。2026-11-18 B11 後、3 回の中央値）: 既定のワーカー数で `5.72 ms`、4 本で `6.71 ms`（ここまで 144Hz の 1 フレーム `6.94 ms` 以内。Chrome `39.35 ms`）、
  3 本で `7.32 ms`、1 本で `17.20 ms`（B11 前は既定で `8.20 ms`）
- コールド起動（プロセスの起動 → 最初のフレーム。2026-11-14 の値で、今回は測り直していない）: `510–594 ms`（Chrome は約 `300 ms`）。
  JDK 27 の AOT キャッシュを使うと showcase `220 ms`・2,000 行のリスト `249 ms`。この値は書体なし（`FontResolver.FALLBACK`）で、OS の書体を使う既定の経路では
  書体の走査が別に `250〜400 ms` 掛かっていた。Track P1a（2026-11-25）で書体の目録（`FontCatalog`）を入れ、2 回目以降の起動の
  書体の読み込みは `243 → 24 ms`、プロセス起動 → 実書体で開き終わるまで `786 → 718 ms`（交互 A/B・11 回の中央値）。
  `ColdStartMain` の `-Dcold.fonts=system`（目録あり）・`=scan`（目録を消して全走査）で書体の経路を測る

### ワーカー数への依存（`font-size` の変更・GL・内蔵の簡易書体）

レイアウトの並列実行は論理コア数 − 1 本のワーカーを使う（`-Denderframe.threads=N` / `EnderEngine.workerThreads(n)` で変えられる）。
2026-11-18（B11）から、並列区間を始めた描画スレッドも仕事を取るので、ワーカー N 本で N+1 本が働く。varied-list-2000 の行は B11 後の 3 回の中央値（範囲）、large-list-2000 の行は B11 前の値。
ゲームなど他の処理とコアを分け合うホストでは、下の表の小さいワーカー数の行が実際の値に近い。

| 文書 | ワーカー | mean ms（範囲） | p50 ms | 確保 KB/フレーム | 144Hz（6.94 ms） |
| :-- | :--: | :--: | :--: | :--: | :--: |
| varied-list-2000 | 1 | 16.65（16.61–16.93） | 16.49 | — | ❌ |
| varied-list-2000 | 2 | 7.96（7.90–7.98） | 7.90 | — | ❌ |
| varied-list-2000 | 3 | 6.70（6.62–6.73） | 6.66 | — | ✅ |
| varied-list-2000 | 4 | 5.91（5.90–5.96） | 5.89 | — | ✅ |
| varied-list-2000 | 8 | 5.15（5.11–5.27） | 5.11 | — | ✅ |
| varied-list-2000 | default | 5.61（5.56–5.76） | 5.61 | — | ✅ |
| large-list-2000 | 1 | 4.91（4.91–4.93） | 4.83 | 1303 | ✅ |
| large-list-2000 | 2 | 3.86（3.79–4.50） | 3.82 | 752 | ✅ |
| large-list-2000 | 4 | 3.11（3.00–3.24） | 3.08 | 752 | ✅ |
| large-list-2000 | 8 | 3.14（3.03–3.14） | 3.08 | 752 | ✅ |
| large-list-2000 | default | 2.70（2.68–2.72） | 2.66 | 753 | ✅ |

行ごとにデザインが違う 2,000 行（varied-list-2000）は、**ワーカーが 8 本以下だと 144Hz の予算に入らない**。同形の行の 2,000 行（large-list-2000）は 1 本でも入る。

### 同じ書体での Chrome との比較

| 文書 | シナリオ | :core 簡易書体 mean | :core 実書体 mean（p50・確保 KB） | Chrome 既定書体 | Chrome 実書体 | 比（実書体どうし） | 旧条件の比（簡易 ÷ 既定） |
| :-- | :-- | :--: | :--: | :--: | :--: | :--: | :--: |
| varied-list-2000 | inherited（gl） | 1.44 | 1.50（1.47・322.4） | 4.90 | 4.09 | 0.37x | 0.29x |
| varied-list-2000 | child（gl） | 2.55 | 2.65（2.62・257.5） | 2.83 | 2.58 | 1.03x | 0.90x |
| varied-list-2000 | fontsize（gl） | 6.28 | 8.20（8.09・1139.9） | 58.42 | 39.35 | 0.21x | 0.11x |
| varied-list-2000 | scroll（gl） | 0.100 | 0.087（0.061・2.3） | 0.654 | 0.615 | 0.14x | 0.15x |
| large-list | hover（gl） | 0.025 | 0.024（0.019・0.3）（描画なし） | 0.517 | 0.486 | 0.05x | 0.05x |
| large-list | style（gl） | 0.060 | 0.058（0.043・5.5）（描画なし） | 0.480 | 0.453 | 0.13x | 0.13x |
| large-list-2000 | text（gl） | 0.249 | 0.252（0.239・3.7）（描画なし） | 1.46 | 1.29 | 0.19x | 0.17x |
| large-list-2000 | hover（gl） | 0.076 | 0.070（0.060・0.3）（描画なし） | 0.758 | 0.738 | 0.09x | 0.10x |
| large-list-2000 | scroll（gl） | 0.092 | 0.090（0.066・2.3） | 0.627 | 0.502 | 0.18x | 0.15x |
| large-list-2000 | child（gl） | 1.38 | 1.44（1.40・223.9） | 1.63 | 1.34 | 1.08x | 0.84x |
| large-list-2000 | fontsize（gl） | 3.07 | 3.13（3.06・760.3） | 16.54 | 11.96 | 0.26x | 0.19x |
| showcase | scroll（gl） | 0.0084 | 0.0070（0.0064・0.6）（描画なし） | 0.226 | 0.186 | 0.04x | 0.04x |
| showcase | hover（gl） | 0.125 | 0.101（0.074・1.3） | 1.54 | 1.24 | 0.08x | 0.08x |
| showcase | text（gl） | 0.171 | 0.167（0.139・9.6） | 1.22 | 1.07 | 0.16x | 0.14x |
| showcase | style（gl） | 0.142 | 0.146（0.120・9.7） | 1.04 | 0.856 | 0.17x | 0.14x |
| showcase | open（gl） | 4.25 | 4.19（4.13・3442.0） | — | — | — | — |
| showcase | resizeexact（gl） | 0.571 | 0.539（0.513・160.9）（描画なし） | — | — | — | — |
| showcase | hover（cpu） | 1.05 | 1.02（0.963・152.0） | 1.87 | 1.64 | 0.62x | 0.56x |

- 「描画なし」は、変えた要素が画面の外（スクロール領域の外）にあって描き直しが起きなかった行。`:core` は再スタイルと差分の判定まで、
  Chrome はスタイルの再計算とペイントの無効化まで走る。
- 書体を揃えても比はほとんど変わらなかった（文字の測定は、行の中身が変わらない操作では再利用される）。変わったのは varied-list-2000 の
  `fontsize`（全行の文字を測り直す）で、`:core` は簡易書体 `6.28 ms` → 実書体 `8.20 ms`。
- `child`（1 行の挿入と削除）は Chrome と互角（`1.03x`・`1.08x`）。以前の「`0.58x`」は書体の条件が違う比較だった。
- 幅変更と open の Chrome 側は下の run.js の表。showcase の `open` は `:core` `4.19 ms` に対し Chrome `5.30 ms`。幅変更のスタイル＋レイアウトは
  Chrome `0.082 ms`（書体を揃えても同じ）。`:core` の幅変更は今回フレーム全体（`0.539 ms`。表示リストの組み直しを含む）しか測っておらず、
  スタイル＋レイアウトだけの値（2026-11-15 に簡易書体で p50 `0.106 ms`。Chrome の約 1.3 倍）は測り直していない。

### 実文書（同梱のデモ・テスト用の文書 5 本）

代表文書 3 種（showcase・large-list・varied-list）以外で同じ傾向になるかを見る。文書は `src/main/resources/data/enderframelib/enderframe_ui/` の
4 本と `src/test/resources/benchmark/machine_ui.html`。

| 文書 | シナリオ | :core mean ms（範囲） | p50 ms | 確保 KB/フレーム | 描いたフレーム | Chrome ms | 比 | 144Hz |
| :-- | :-- | :--: | :--: | :--: | :--: | :--: | :--: | :--: |
| surface_test.html | hover | 0.936（0.890–1.012） | 0.867 | 641.5 | 1500 | 3.46 | 0.27x | ✅ |
| surface_test.html | scroll | 0.063（0.061–0.066） | 0.042 | 2.7 | 1500 | 0.852 | 0.07x | ✅ |
| surface_test.html | text | 0.191（0.185–0.197） | 0.164 | 11.1 | 1500 | 0.927 | 0.21x | ✅ |
| surface_test.html | open | 5.47（5.18–5.53） | 5.33 | 3587.1 | 400 | — | — | ✅ |
| voidwave.html | hover | 1.88（1.66–1.89） | 1.82 | 2144.3 | 1500 | 2.76 | 0.68x | ✅ |
| voidwave.html | scroll | 1.62（1.61–1.66） | 1.56 | 1757.9 | 1500 | 2.06 | 0.79x | ✅ |
| voidwave.html | text | 1.37（1.30–1.53） | 1.32 | 715.0 | 1500 | 2.42 | 0.57x | ✅ |
| voidwave.html | open | 9.45（9.27–10.47） | 9.17 | 10130.7 | 400 | — | — | ❌ |
| animation_test.html | hover | 1.11（1.08–1.14） | 1.06 | 642.6 | 1500 | 2.56 | 0.43x | ✅ |
| animation_test.html | scroll | 0.0090（0.009–0.010） | 0.0075 | 0.6 | 0（変化なし） | 1.13 | — | ✅ |
| animation_test.html | text | 0.398（0.395–0.427） | 0.341 | 58.8 | 1500 | 1.38 | 0.29x | ✅ |
| animation_test.html | open | 5.58（5.57–5.90） | 5.41 | 4969.9 | 400 | — | — | ✅ |
| media_test.html | hover | 0.0016（0.002–0.002） | 0.0014 | 0.1 | 0（変化なし） | 0.205 | — | ✅ |
| media_test.html | scroll | 0.013（0.012–0.014） | 0.0072 | 0.6 | 0（変化なし） | 0.188 | — | ✅ |
| media_test.html | text | 0.213（0.203–0.228） | 0.185 | 10.2 | 1500 | 0.972 | 0.22x | ✅ |
| media_test.html | open | 7.25（6.86–7.33） | 6.89 | 6331.1 | 400 | — | — | ❌ |
| machine_ui.html | hover | 0.0020（0.002–0.002） | 0.0015 | 0.1 | 0（変化なし） | 0.186 | — | ✅ |
| machine_ui.html | scroll | 0.0045（0.004–0.005） | 0.0034 | 0.6 | 0（変化なし） | 0.172 | — | ✅ |
| machine_ui.html | text | 0.176（0.173–0.195） | 0.149 | 10.2 | 1500 | 0.577 | 0.31x | ✅ |
| machine_ui.html | open | 1.71（1.69–1.72） | 1.66 | 639.3 | 400 | — | — | ✅ |

- 「変化なし」は、その文書ではシナリオが何も起こさなかった行（スクロールできない文書の `scroll`、`:hover` の規則が無い要素の `hover`）。比較から外す。
- **`open` が 144Hz の 1 フレームを超える文書がある**（`voidwave.html` `9.45 ms`・`media_test.html` `7.25 ms`）。開くのは 1 回だけの処理だが、
  1 フレームには収まらない。
- `voidwave.html` は常時アニメーションのある文書で、`hover`・`scroll`・`text` のどれも 1 フレームに 0.7〜2.1 MB を確保する（showcase の `hover` は 1.3 KB）。
  差分の経路に乗らず、毎フレーム組み直している。Chrome 比は `0.57x`〜`0.79x` で、代表文書（`0.04x`〜`0.17x`）より差が小さい。
- `surface_test.html`・`animation_test.html` の `hover` も 1 フレームに約 640 KB を確保する。

Chrome の `open`（`srcdoc` の設定 → load → レイアウトの強制）と幅変更（run.js。ページ内の計測）:

| ファイル | 文書 | 書体 | 幅変更 style+layout | open |
| :-- | :-- | :-- | :--: | :--: |
| res-chrome-animation_test.html-Arial.json | animation_test.html | Arial | 0.530 | 6.30 |
| res-chrome-machine_ui.html-Arial.json | machine_ui.html | Arial | 0.016 | 3.50 |
| res-chrome-media_test.html-Arial.json | media_test.html | Arial | 0.250 | 1583.60 |
| res-chrome-showcase-Arial.json | showcase | Arial | 0.082 | 5.30 |
| res-chrome-showcase-committed.json | showcase | 既定 | 0.083 | 5.50 |
| res-chrome-showcase-default.json | showcase | 既定 | 0.082 | 6.00 |
| res-chrome-surface_test.html-Arial.json | surface_test.html | Arial | 0.017 | 5.90 |
| res-chrome-voidwave.html-Arial.json | voidwave.html | Arial | 0.337 | 13.70 |

`media_test.html` の `open` は、解決できない画像・動画の読み込み待ちが入った値で、比較に使えない。

### 回帰の判定（内部カウンタを使わずヒープ・確保量・交互 A/B で見る）

時間は機械と負荷で揺れるため、`:core` の回帰は以下の 2 つの独立ゲートで判定する（どちらも毎回の CI で回る）。

1. **`./gradlew :core:costGate`**: 新しい JVM・ワーカー 1 本・内蔵の簡易書体・描画なし（`cpu` の行のみソフトウェア描画）で代表シナリオを測り、基準値（`core/src/bench-crossengine/costgate-baseline.txt`）と 1 フレームの確保量（±5%）と描画経路の回数（完全一致）を比べる（旧 `memo*` / `memoCeil` 判定は Phase M0 で撤去）。
   - 近道を 1 つ無効にすると赤になる: `-Pcostgate.jvmArgs=-Denderframe.ui.nochildpatch=true` で `child` の確保量が +141%・+178%、`-Denderframe.layout.notentative=true` で showcase の `text` が +1,753%、`-Denderframe.css.noshare=true` で `style` が +46%。
   - **見ていないもの**: 描画バックエンドの中（GPU の差分再生・ソフトウェアの損傷範囲）。そこは画素の一致テスト（`GlDamageParityTest` ほか）が番をする。
2. **`./gradlew :core:memoryAndLatencyGate`**: Phase M0 で新設。全再計算・差分シナリオの保持ヒープ量（MB）と同時交互 A/B 実行時間（ms および増分/全面比）のみを監視し、基準値（`core/src/test/resources/memory-latency-baseline.txt`、更新は `-Pgate.update`）の上限を超えていないかを判定する。
   - **単体検査**: `./gradlew :core:test --tests '*ArchitectureHealthTest'` がクラス行数上限（新規 ≤ 600 行、1,000 行超クラスの増加禁止）・mutable static 上限・SCC・`:core` 純度・既存 `Shortcut`（35 個凍結）/`EngineFlags`（45 個凍結）の新規追加禁止を監視する。
