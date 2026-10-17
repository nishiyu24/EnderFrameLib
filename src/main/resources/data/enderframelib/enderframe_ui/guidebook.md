# EnderFrameLib Web標準ガイドブック

> [!NOTE]
> このページは `data/enderframelib/enderframe_ui/guidebook.md` に配置された **標準Markdown (CommonMark / GFM)** ファイルから自動的にセマンティックHTML5へ変換・描画されています。

## 1. Web標準 (HTML5 / CSS3) 準拠機能一覧

| フェーズ | カテゴリ | 準拠するWeb標準仕様 | 主な要素・属性・構文 |
| :--- | :--- | :---: | :--- |
| **Phase 1** | セマンティックフォーム | W3C HTML5 Forms | `<form>`, `<fieldset>`, `<legend>`, `<details>`, `<summary>`, `<label for>`, `<output>` |
| **Phase 1** | レシピビューア連携 | W3C Landmarks / `data-*` | `<aside>`, `<dialog open>`, `data-dropzone="item"`, `data-item` |
| **Phase 2** | カスタムフォント | CSS Fonts Level 3 | `@font-face`, `font-family: 'minecraft:alt', 'minecraft:uniform'` |
| **Phase 2** | 拡張セレクタ＆色空間 | Selectors Level 3 / Color 3 | `hsl()`, `hsla()`, `:nth-child(even)`, `[attr^="val"]`, `@media` |
| **Phase 2** | 宣言的リアクティブDOM | HTML5 Custom `data-*` | `data-model`, `data-bind`, `data-if`, `data-for`, `data-i18n` |
| **Phase 3** | Markdown & 空間ナビ | GFM / W3C `tabindex` | `<table>`, `<blockquote>`, `tabindex`, `Tab` / 矢印キー移動 |

---

## 2. インラインMinecraftアイテム＆タスクリストの検証

Markdownの標準画像構文 `![alt](item:...)` を記述するだけで、MinecraftのアイテムとJEI/REIツールチップがインライン描画されます：

- [x] ![Diamond](item:minecraft:diamond) **ダイヤモンド (`item:minecraft:diamond`)** のインライン描画とJEI連携
- [x] ![Netherite Ingot](item:minecraft:netherite_ingot) **ネザライトインゴット (`item:minecraft:netherite_ingot`)** の表示
- [x] ![Golden Apple](item:minecraft:golden_apple) **金のリンゴ (`item:minecraft:golden_apple`)** の表示
- [ ] カスタムTTFフォント (`@font-face`) のリソースパック追加テスト

> [!TIP]
> ゲーム内で <kbd>F12</kbd> キーを押すと **DevTools インスペクタ** が起動し、マージン・パディング・計算済みスタイルをリアルタイムに確認できます。<kbd>F5</kbd> キーでホットリロード、<kbd>Tab</kbd> キーまたは矢印キーでフォーカス移動が可能です。

## 3. サンプルHTML5コード

```html
<form class="mc-panel">
  <fieldset>
    <legend>リアクティブ制御設定</legend>
    <label for="boost_chk">オーバークロック有効化</label>
    <input id="boost_chk" type="checkbox" data-model="boost" checked />
  </fieldset>
</form>
```