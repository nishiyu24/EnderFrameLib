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
- **テスト**: `PerformanceBenchmarkTest`（ウォームアップ 5 回、測定 15 回の平均）
- **判定**: 「実測 ≤ 基準値 × 2.0 + 3ms」を超えたら回帰として落ちる。レポートは `build/reports/benchmark-results.txt`。

```bash
./gradlew test --tests '*PerformanceBenchmarkTest'
./gradlew test --tests '*PerformanceBenchmarkTest' -Dbenchmark.update=true   # 基準値の更新（git diff で確認してからコミット）
```

| Document | Restyle | Relayout | DisplayList | ScrollStep |
| :--- | :---: | :---: | :---: | :---: |
| `showcase.html` | 16.3 ms | 15.2 ms | 4.8 ms | 17.5 ms |
| `guidebook.md` | 6.7 ms | 3.5 ms | 2.7 ms | 4.9 ms |
| `large-list (500 items)` | 19.9 ms | 8.9 ms | 10.1 ms | 13.7 ms |

2026-10-20 の値は showcase.html が 9.1 / 10.6 / 3.3 / 12.4 ms で、他の 2 つは今とほぼ同じだった。
変わったのが showcase.html だけなので、マシンの差ではなくその間に showcase.html の処理が重くなったもの（原因は未調査）。

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

### R5 の残りの目標

- 要素ごとの dirty ビット（style / layout / paint / composite）で、hover の色変化やリストの 1 行追加が再スタイル・
  再レイアウトの全体を回さないこと（1ms 未満が目安）。
- transform / opacity だけの変化がレイアウトを通らないこと。
- ディスプレイリスト生成の差分化。

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
