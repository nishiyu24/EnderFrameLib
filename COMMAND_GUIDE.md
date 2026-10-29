# EnderFrameLib コマンド＆データパック連携ガイド

EnderFrameLib の全機能をゲーム内コマンドやデータパック（`.mcfunction`）・コマンドブロックから操作するためのリファレンスです。
ルートコマンドは **`/enderframe`** または短縮エイリアスの **`/ef`** が使用できます。

---

## 1. 権限とサバイバルモード制限

セキュリティおよびマルチサーバーでの不正利用防止のため、以下の制限が適用されています。

| 項目 | 仕様 |
| :--- | :--- |
| **必要な権限レベル** | **OP レベル 2 以上**（マルチサーバーでは管理者権限、シングルではチート許可、またはデータパック関数・コマンドブロック実行） |
| **ゲームモード** | ゲームモードによる制限はありません。OP レベル 2 以上であれば、サバイバルでも `/ef`（`/enderframe`）を実行できます |
| **スクリーンブロック保護** | `enderframelib:surface_test_block` は岩盤同等の強度（サバイバルで破壊不可・ドロップなし）を持ち、サバイバルモードでは手持ちからの直接設置も拒否されます |

---

## 2. データパック内の HTML ファイル配置場所

データパックで独自の HTML UI を追加する場合、以下のパスに `.html` ファイルを配置します。

```text
<ワールドまたはデータパック>/
└── data/
    └── <namespace>/
        └── enderframe_ui/
            ├── menu.html          -> UI ID は "<namespace>:menu"
            └── hud/status.html    -> UI ID は "<namespace>:hud/status"
```

- `/reload` を実行すると即座に再読み込みされ、コマンドの `<ui_id>` 引数で自動 **Tab 補完**されます。

---

## 3. コマンド一覧・構文リファレンス

### 3.1 GUI 画面を開く（`open` / `open_inline`）

プレイヤーにマウス操作可能なフルスクリーン GUI として HTML を開きます。

```mcfunction
# 1. 省略形（実行者 @s に対してデフォルトサイズ 854x480 で開く）
/ef open <ui_id>

# 2. 対象プレイヤー指定
/ef open <targets> <ui_id>

# 3. 解像度指定（例: 800x500 px）＋任意のセッションID
/ef open <targets> <ui_id> <width> <height> [<session_id>]

# 4. セッションID先行指定（従来構文との互換用）
/ef open <targets> <ui_id> <session_id> [<width> <height>]

# 5. HTML文字列を直接インライン指定して開く
/ef open_inline <targets> <html...>
```

**使用例:**
```mcfunction
/ef open mypack:shop_menu
/ef open @a mypack:event_notice 640 400 shared_notice
/ef open_inline @s <div style="padding:16px;background:#222;color:#fff;"><h1>Hello!</h1></div>
```

---

### 3.2 HUD オーバーレイ（`hud`）

画面上に常駐する HUD（ヘッドアップディスプレイ）を表示・消去します（基準解像度 `1280x720`）。

```mcfunction
# 1. 省略形（実行者 @s に HUD をセット）
/ef hud set <ui_id>

# 2. 対象プレイヤー・カスタムセッションIDを指定してセット
/ef hud set <targets> <ui_id> [<session_id>]

# 3. HUD を消去（省略時は @s）
/ef hud clear [<targets>]
```

**使用例:**
```mcfunction
/ef hud set @a mypack:boss_hud boss_hud_session
/ef hud clear @a
```

---

### 3.3 ブロックスクリーン（`screen` または別名 `block`）

ワールド内のブロック表面に HTML を投影し、クロスヘア（照準）で直接ホバー・クリック・スクロール・文字入力できるマルチブロックスクリーンを操作します。

> **座標の基準**: `<pos>` にはマルチブロックスクリーンの**左下ブロック（master）**または構成ブロックの座標を指定します。`<facing>` は画面の正面が向く方角（`north` / `south` / `east` / `west`）です。

```mcfunction
# 1. スクリーンブロックを w×h 個一括配置し、結合して UI を表示
/ef screen place <pos> <facing> <blocks_w> <blocks_h> [<ui_id> [<px_w> <px_h> [<session_id>]]]

# 2. 既存のスクリーンブロックにデータパック UI を読み込む（未結合なら自動結合）
/ef screen load <pos> <ui_id> [<px_w> <px_h> [<session_id>]]

# 3. 並べたスクリーンブロックを1枚の画面として結合（手動配置後など）
/ef screen draw <pos> [<facing>]

# 4. スクリーン内の DOM 要素を座標指定で直接書き換え（element_id も Tab 補完対応）
/ef screen patch <pos> text <element_id> <text...>
/ef screen patch <pos> html <element_id> <raw_html...>
/ef screen patch <pos> style <element_id> <property> <value...>
/ef screen patch <pos> attr <element_id> <attr_name> <attr_value...>
/ef screen patch <pos> class <element_id> add|remove|toggle <class_name>

# 5. クライアントテンプレート（UIState 駆動）を割り当て
/ef screen template <pos> <template_id>

# 6. スクリーンの状態確認（サイズ・master座標・セッションID・UI ID を表示）
/ef screen info <pos>

# 7. スクリーンの表示をクリア（destroy_blocks=true でブロック自体も撤去）
/ef screen clear <pos> [<destroy_blocks>]
```

**使用例:**
```mcfunction
# 座標 (100, 65, 200) を左下に、南向きで横3×縦2ブロックのモニターを設置
/ef screen place 100 65 200 south 3 2 mypack:lobby_board 768 512 main_board

# モニター内の id="headline" のテキストを書き換え
/ef screen patch 100 65 200 text headline "第2ウェーブ開始！"

# モニターをブロックごと撤去
/ef screen clear 100 65 200 true
```

---

### 3.4 空間ホログラム（`hologram`）

ブロックのない空中（小数座標対応）に、半透明・両面・ビルボード（視点追従）または角度固定の Web UI ホログラムを浮かべます。
ログイン時やディメンション移動時にもサーバーから自動同期されます。

```mcfunction
# 1. データパック UI から新しいホログラムを生成
/ef hologram spawn <id> <pos> <ui_id> [<width_blocks> <height_blocks> [<orientation> [<interactive> [<px_w> <px_h>]]]]

# 2. 既存のセッションID（または UIState インスタンスID）をホログラムとして空間にバインド
/ef hologram bind <id> <pos> <session_id> [<width_blocks> <height_blocks> [<orientation> [<interactive>]]]

# 3. 個別削除・全消去・一覧表示
/ef hologram remove <id>
/ef hologram clear
/ef hologram list
```

- `<orientation>`:
  - `billboard`（デフォルト：常にプレイヤーの正面を向く）
  - `north` / `south` / `east` / `west`（方角固定）
  - 数値（`0`, `90`, `180`, `270`, `45.0` などの Yaw 角度）
- `<interactive>`: `true`（デフォルト：照準クリック操作可能）/ `false`（表示のみ）

**使用例:**
```mcfunction
# 自分の目の前にビルボード（視点追従）ホログラムを生成（2.4×1.5ブロック）
/ef hologram spawn guide_1 ~ ~1.6 ~ mypack:guide_card 2.4 1.5 billboard true

# 北向き固定の案内板ホログラムを設置
/ef hologram spawn sign_north 100.5 66.5 198.0 mypack:map_sign 3.0 2.0 north true

# ホログラムの文字を更新（spawn 時は "holo_<id>" というセッションIDが自動作成されます）
/ef patch text holo_guide_1 message "次の目的地へ進んでください"

# 削除
/ef hologram remove guide_1
```

---

### 3.5 手持ちアイテムスクリーン（`item`）

手持ちのタブレットアイテム（`enderframelib:surface_test_item`）やバニラの地図（`minecraft:filled_map`）の表面にリアルタイム UI を描画します。

```mcfunction
# 1. UI がバインドされたスクリーンタブレットを対象に付与（すべて省略時は @s にデフォルトUIを付与）
/ef item give [<targets> [<ui_id> [<px_w> <px_h> [<session_id>]]]]

# 2. 対象プレイヤーがメインハンドに持っているアイテム（または地図）に UI をバインド
/ef item bind <ui_id> [<targets> [<px_w> <px_h> [<session_id>]]]

# 3. メインハンドのアイテムに既存セッションIDをバインド
/ef item session <session_id> [<targets>]
```

**使用例:**
```mcfunction
# プレイヤーにレーダーUI付きのタブレットを配布
/ef item give @a mypack:radar 512 384 shared_radar

# 手に持っている地図にセッションをバインド
/ef item session shared_radar @s
```

---

### 3.6 DOM の部分更新（`patch`）

開いている画面・HUD・ブロックスクリーン・ホログラム・手持ちアイテムのどれであっても、同じ `<session_id>` に対してサーバーから差分更新（DOM ミューテーション）を送ることができます。
`<session_id>` と `<element_id>` はどちらも **Tab 補完**されます。

```mcfunction
# テキスト内容の更新
/ef patch text <session_id> <element_id> <text...>

# 内部 HTML の更新
/ef patch html <session_id> <element_id> <raw_html...>

# インライン CSS スタイルの更新
/ef patch style <session_id> <element_id> <property> <value...>

# HTML 属性の更新
/ef patch attr <session_id> <element_id> <attr_name> <attr_value...>

# CSS クラスの追加・削除・トグル
/ef patch class <session_id> add|remove|toggle <class_name>
```

**使用例:**
```mcfunction
/ef patch text boss_hud_session hp_label "HP: 450 / 1000"
/ef patch style boss_hud_session hp_bar width 45%
/ef patch class boss_hud_session phase2_badge add visible
```

---

### 3.7 テンプレート駆動 `UIState` 操作（`state`）

クライアントテンプレート（`{{key}}`, `data-bind="key"`, `data-if="key"`）と連動する軽量 Key-Value ステート（`UIState`）を操作します。

```mcfunction
# 1. インスタンスIDを指定して値をセット（true/false/数値/文字列を自動判別）
/ef state set <instance_id> <key> <value>

# 2. ブロック座標から対応する UIState ("block:x,y,z") の値をセット
/ef state set_block <pos> <key> <value>

# 3. 指定座標のブロック UIState をプレイヤーに GUI として開く
/ef state open <pos> [<targets> [<template_id>]]

# 4. 状態の確認・削除・一覧
/ef state info <instance_id>
/ef state info_block <pos>
/ef state clear <instance_id>
/ef state list
```

---

### 3.8 セッション管理・Pub/Sub・開発ツール（`session` / `publish` / `dev`）

```mcfunction
# セッション単体の事前読み込み・破棄・一覧・詳細（要素ID一覧の確認）
/ef session load <session_id> <ui_id> [<width> <height>]
/ef session clear <session_id>
/ef session clear_all
/ef session list
/ef session info <session_id>

# Pub/Sub チャンネルへの SNBT データ配信（HTML 側 <span data-channel="ch" data-bind="key"> と連動）
/ef publish <channel> <snbt>

# 組み込みデスクトップ画面を開く / ブラウザプレビュー用 DevKit を書き出す
/ef desktop [<targets>]
/ef devkit
```

---

## 4. データパック実践レシピ（`.mcfunction` 例）

### レシピ A: ロビーに巨大モニターと空中ホログラムを設置してスコアボード連動する

**1. `data/mypack/enderframe_ui/scoreboard.html` を作成:**
```html
<style>
  body {
    background-color: #111827;
    color: #f9fafb;
    padding: 24px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .title { font-size: 28px; color: #38bdf8; font-weight: bold; }
  .timer { font-size: 48px; color: #facc15; }
  .status.alert { color: #ef4444; }
</style>
<div>
  <div id="title" class="title">ARENA MATCH</div>
  <div id="timer" class="timer">03:00</div>
  <div id="status" class="status">待機中...</div>
</div>
```

**2. `data/mypack/function/setup_stage.mcfunction`（初期設置）:**
```mcfunction
# ロビー壁面に 4x3 ブロックのスクリーンを設置（セッションID: arena_board）
ef screen place 0 70 10 south 4 3 mypack:scoreboard 640 480 arena_board

# 中央空中に同じセッション (arena_board) を共有するビルボードホログラムも浮かべる
ef hologram bind arena_holo 0.5 72.5 0.5 arena_board 2.5 1.8 billboard false
```

**3. `data/mypack/function/start_wave.mcfunction`（進行時の更新）:**
```mcfunction
# セッション arena_board を書き換えると、壁面スクリーンと空中ホログラムの両方が同時に更新される
ef patch text arena_board title "WAVE 1 - START!"
ef patch text arena_board status "敵が出現しました！"
ef patch class arena_board status add alert
```
