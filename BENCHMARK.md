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
（`SessionContext.RESIZE_THROTTLE_FACTOR`）。毎フレームの描画（`DisplayItemDrawer.renderElements`）は、祖先の変形を
フレーム内で覚えて使い回すので、要素数 × 木の深さだった祖先の評価が要素数に比例するだけになった（ゲーム内での計測は未実施）。

---

## 5. 差分レイアウト（measure の再利用）

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

字形の置き場（VRAM）: 13px の文字を `scale(1 → 3)` で動かしたときの置き場の種類は、量子化の前 667 フレームで 667 種、後は約 90 種（`AtlasCacheTest`）。同じサイズの `subpixel` × `lightText` は 8 枚のテクスチャから 1 つの `FontSet` になった。ゲーム内の VRAM の実測（Phase 3b の FBO の予算を決める材料）は未実施。
