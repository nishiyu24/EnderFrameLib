# EnderFrameLib Web標準ガイドブック

> [!NOTE]
> このページは `data/enderframelib/enderframe_ui/guidebook.md` に配置された **標準Markdown (CommonMark / GFM)** ファイルから自動的にセマンティックHTML5へ変換・描画されています。

## 1. Web標準 (HTML / CSS / DOM) 準拠機能一覧

| フェーズ | カテゴリ | 準拠するWeb標準仕様 | 主な要素・属性・構文 |
| :--- | :--- | :---: | :--- |
| **R1 / R2** | ライブDOM＆イベント | DOM Standard / UI Events | `appendChild`, `classList`, `addEventListener`, `click`, `beforeinput` |
| **R2** | セマンティックフォーム | HTML Forms | `<form>`, `<fieldset>`, `<legend>`, `<details>`, `<summary>`, `<label for>`, `<output>` |
| **R3 / H1** | 宣言的リアクティブDOM | HTML `data-*` | `data-model`, `data-bind`, `data-if`, `data-for`, `data-i18n`, `{{key}}` |
| **R4** | モダンCSS | Cascade 5 / Selectors 4 / Color 4 | `@layer`, `@container`, `:has()`, `oklch()`, `color-mix()`, CSS ネスト |
| **R5** | 差分レイアウト | CSS Box Model / Overflow | スクロール・ドラッグ・リサイズの近道、`overflow-y: auto` |
| **R7** | システムフォント | CSS Fonts 4 | `font-family: sans-serif`, `font-weight`, `font-kerning`, `@font-face` |

---

## 2. インラインMinecraftアイテム＆タスクリストの検証

Markdownの標準画像構文 `![alt](item:...)` を記述するだけで、MinecraftのアイテムとJEI/REIツールチップがインライン描画されます：

- [x] ![Diamond](item:minecraft:diamond) **ダイヤモンド (`item:minecraft:diamond`)** のインライン描画とJEI連携
- [x] ![Netherite Ingot](item:minecraft:netherite_ingot) **ネザライトインゴット (`item:minecraft:netherite_ingot`)** の表示
- [x] ![Golden Apple](item:minecraft:golden_apple) **金のリンゴ (`item:minecraft:golden_apple`)** の表示
- [ ] SVG・Canvas 2D の置換要素 (R8)

> [!TIP]
> GUI画面で <kbd>F12</kbd> キーを押すと **簡易インスペクタ** が開き、ポインタの下の要素のタグ・id・class・矩形・バインディング属性を確認できます。<kbd>F5</kbd> キーで文書の再読み込み、<kbd>Tab</kbd> キーまたは矢印キーでフォーカス移動が可能です。

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
