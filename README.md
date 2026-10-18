# EnderFrameLib

An HTML/CSS UI library for Minecraft 1.21.1 (NeoForge).

EnderFrameLib ships a small browser engine — HTML tokenizer, CSS cascade, block /
flex / grid layout, and a Minecraft renderer — so a mod can describe its screens
and HUDs as HTML and CSS instead of hand-placing widgets and pixel offsets.

- **Screens, HUDs and in-world panels** are all the same document, driven from the
  server or from a datapack.
- **The server owns the DOM.** Clients receive the tree and diff patches; they
  never evaluate scripts.
- **No browser is embedded.** The engine is ~4,400 lines of plain Java in
  `core/`, with no dependency on Minecraft, so it is unit-testable headlessly.

> Status: usable, pre-1.0 in practice. The supported surface is `api/`;
> everything else is `@ApiStatus.Internal` and will move.

---

## Installation

Published to a local Maven repository (`./repo`) by `./gradlew publish`.

```gradle
repositories {
    maven { url = "file:///path/to/enderframelib/repo" }
}

dependencies {
    implementation "com.nishiyu.enderframelib:enderframelib:1.0.3"
}
```

Declare the dependency in `neoforge.mods.toml` as well:

```toml
[[dependencies.yourmod]]
modId = "enderframelib"
type = "required"
versionRange = "[1.0.3,)"
ordering = "AFTER"
side = "BOTH"
```

Requires Minecraft 1.21.1, NeoForge 21.1.248+, Java 21.

---

## Minimal example

Everything server-side goes through `EnderFrameAPI`. Call it from mod setup or
later — never from a static initializer.

```java
import com.nishiyu.enderframelib.api.EnderFrameAPI;

// Open a screen for one player from an HTML string.
EnderFrameAPI.get().openScreen(player, null, """
        <style>
          body { display: flex; flex-direction: column; gap: 8px; padding: 12px; }
          button:hover { background-color: #e5e5e5; }
        </style>
        <h2>Hello</h2>
        <p>Diamonds: <span id="count">0</span></p>
        <button data-action="mymod:collect">Collect</button>
        """, 320, 200);
```

Reacting to a click, and patching the open document without resending it:

```java
// The button's data-action arrives here on the block entity or provider that
// owns the session.
@Override
public void onWebServerAction(ServerPlayer player, String action, String value, CompoundTag payload) {
    if (action.equals("mymod:collect")) {
        EnderFrameAPI.get().findSession(sessionId).patchText("count", "42");
    }
}
```

A UI shipped in a datapack at `data/mymod/enderframe_ui/menu.html`:

```java
EnderFrameAPI.get().openDatapackScreen(player, "mymod:menu", 320, 200);
```

A persistent HUD overlay:

```java
EnderFrameAPI.get().setHud(player, null, hudHtml);
EnderFrameAPI.get().clearHud(player);
```

Pushing live values to every subscriber of a channel (only changed keys go on
the wire):

```java
EnderFrameAPI.get().publish("mymod:stats", Map.of("kills", 12, "coins", 300));
// In the document: <span data-channel="mymod:stats" data-bind="kills"></span>

EnderFrameAPI.get().addSubscriptionCheck((player, channel) -> player.hasPermissions(2));
```

### Commands

Useful without writing any Java:

```
/enderframe open <players> <ui_id> [<session_id> [<width> <height>]]
/enderframe open_inline <players> <html…>
/enderframe hud set <players> <ui_id>
/enderframe hud clear <players>
/enderframe session load <session_id> <ui_id> [<width> <height>]
/enderframe session clear <session_id>
/enderframe publish <channel> <snbt>
/enderframe devkit                 # export the browser preview kit
```

### Browser preview

`/enderframe devkit` writes `<gameDir>/enderframe_devkit/` with a standalone
HTML/CSS/JS kit. Open `preview-playground.html` in Chrome, Edge, Firefox or VS
Code Live Server and author your UI without launching Minecraft — the custom
elements and the reactive attributes below are reimplemented there.

The kit ships the engine's own user-agent sheet (`enderframe-ua.css`) and the
built-in themes verbatim, and links them in the same order the game does, so the
preview is not styled by a parallel set of defaults. It also contains
`fidelity-dump.html`, which measures the fidelity corpus in your browser; the
measured values are what [FIDELITY.md](FIDELITY.md) and `WebFidelityTest` compare
the engine against.

---

## Supported HTML

**Structure** `html` `head` `body` `div` `section` `main` `header` `footer`
`article` `nav` `aside` `span` `template`

**Text** `h1`–`h6` `p` `br` `hr` `b` `strong` `i` `em` `s` `strike` `del` `u`
`ins` `code` `kbd` `pre` `blockquote` `label` `output`

**Lists / tables** `ul` `ol` `li` `table` `caption` `thead` `tbody` `tfoot`
`tr` `th` `td` (`colspan`, `rowspan`) `colgroup` `col` (`span`)

**Forms** `form` `fieldset` `legend` `button` `input`
(`text` `number` `checkbox` `radio` `range` `color`) `select` `option`
`textarea` `progress` `meter`

**Media / grouping** `img` `canvas` `video` `a` `details` `summary` `dialog`
`figure` `figcaption`

**Minecraft custom elements**

| Element | What it renders |
|---|---|
| `<mc-slot index="3">` | one inventory slot |
| `<mc-slot-grid cols rows gap start>` | a slot grid |
| `<mc-inventory>` | the 3×9 main inventory plus a 9-slot hotbar |
| `<mc-bar value min max color bg label>` | a labelled progress bar |
| `<mc-entity>` / `<mc-scene>` | a live entity / block scene |
| `<mc-fluid>` | a fluid texture |
| `<img src="item:minecraft:diamond">` | an item stack as an image |

**Reactive attributes** (server-driven, no scripting)

`data-model` `data-bind` `data-if` `data-show` `data-for` `data-key`
`data-class` `data-channel` `data-list` `data-item` `data-tooltip`
`data-sound` `data-markdown` `data-syntax` `data-theme` `data-hud`
`data-tracker` `data-dropzone` `data-slot` `data-zoom` `data-pan-zoom`

**Parsing** Documents are built with the HTML standard's tree-construction
rules, as a browser's `DOMParser` would: `html`, `head` and `body` are implied
when you write only a fragment (leading `<link>`, `<meta>` and `<title>` go
into `head`); end tags may be omitted for `p`, `li`, `dt`, `dd`, `option`,
`optgroup`, `tr`, `td`, `th`, the table sections and `colgroup`; a table gets
its implied `tbody`, `tr` and `colgroup`; content misplaced inside a `<table>`
is moved in front of it; and all 2,231 named character references are known.
`innerHTML` patches are parsed in the context of their target element, so a
`<tr>` patched into a `<tbody>` stays a row.

**Markdown** CommonMark / GFM subset, via `data-markdown` on
`<article>`/`<section>`, `<script type="text/markdown">`, `<mc-markdown>`, or a
`.md` file in `enderframe_ui/`: headings, paragraphs, emphasis, inline code,
fenced code blocks with a language class, blockquotes, GitHub alerts
(`> [!NOTE]`), flat bullet/ordered/task lists, GFM tables, images, links,
horizontal rules.

## Supported CSS

**Selectors** type, `.class`, `#id`, `*`, descendant, `>`, `+`, `~`, attribute
(`[a]` `[a=v]` `[a^=v]` `[a$=v]` `[a*=v]` `[a~=v]` `[a|=v]`), selector lists.
Specificity and cascade origin are tracked properly, and `!important` wins.

**Pseudo-classes** `:hover` `:active` `:focus` `:focus-visible` `:focus-within`
`:checked` `:disabled` `:first-child` `:last-child` `:nth-child()` (`odd`,
`even`, integer) `:empty` `:root` `:not()`

**Pseudo-elements** `::before` `::after` (`content` with strings, `attr()`,
`counter()` and `counters()`); list items get a `::marker` box

**Lists and counters** `list-style-type` (`disc` `circle` `square` `decimal`
`decimal-leading-zero` `lower-` / `upper-alpha` / `latin` / `roman`
`lower-greek` `none`, or a string), `list-style-position`, `counter-reset`
`counter-increment` `counter-set`, `<ol start>` and `<li value>`

**Box model** `display` (`block` `inline` `inline-block` `flow-root` `flex`
`inline-flex` `grid` `inline-grid` `table` `inline-table` `table-*`
`list-item` `contents` `none`, and the two-keyword forms such as
`inline flow-root`)
`position` (`static` `relative` `absolute` `fixed` `sticky`) `top` `right`
`bottom` `left` `z-index` `float` `clear` `width` `height` `min-*` `max-*` `margin` `padding` `border`
`border-*-width` `border-style` (`solid` `dashed` `dotted` `double` `groove`
`ridge` `inset` `outset` `none` `hidden`) `border-radius` `outline`
`outline-offset` `box-sizing` `aspect-ratio` `overflow`
`overflow-x` `overflow-y` `scrollbar-width` `visibility` `opacity`

**Flexbox** `flex` `flex-grow` `flex-shrink` `flex-basis` `flex-direction`
(including `*-reverse`) `flex-wrap` (including `wrap-reverse`) `flex-flow`
`order` `justify-content` `align-items` `align-self` (including `baseline`)
`align-content` `gap` `row-gap` `column-gap`, auto margins

**Grid** `grid-template-columns` `grid-template-rows` (lengths, `%`, `fr`,
`auto`, `min-content`, `max-content`, `fit-content()`, `minmax()`, `repeat()`
including `auto-fill`, named lines) `grid-template-areas` `grid-auto-rows`
`grid-auto-columns` `grid-auto-flow` (`row` `column` `dense`) `grid-row`
`grid-column` `grid-area` and their `-start` / `-end` longhands (line numbers,
negative numbers, names, `span`) `justify-items` `align-items` `justify-self`
`align-self` `justify-content` `align-content` `place-*` `gap`

**Tables** `table-layout` `border-collapse` `border-spacing` `caption-side`,
`vertical-align` on cells

**Text** `color` `font-size` `font-family` `font-weight` `font-style`
`line-height` `letter-spacing` `text-align` `text-indent` `vertical-align`
(`baseline` `top` `bottom` `middle` `sub` `super` `text-top` `text-bottom`,
lengths, `%`) `text-decoration` `text-shadow`
`text-transform` `white-space` (`normal` `nowrap` `pre` `pre-wrap` `pre-line`)
`word-break` `overflow-wrap` `text-overflow` `direction`

**Paint / effects** stacking contexts (positioned boxes with a `z-index`,
flex and grid items with a `z-index`, `opacity` below 1, `transform`, `filter`,
`isolation: isolate`, `mix-blend-mode`, and fixed / sticky boxes) painted in
CSS 2.2 Appendix E order; `background` with multiple layers,
`background-color` `background-image` (`url()`, `linear-gradient()`)
`background-size` `background-position` `background-repeat` (including
`space` and `round`) `background-clip` `background-origin`; `box-shadow` `transform`
(`translate` `scale` `rotate`) `transform-origin` `transition`
`transition-delay` `transition-timing-function` `animation` with `@keyframes`
`object-fit` `cursor` `content` `src`

**Units & functions** `px` `%` `em` `rem` `vw` `vh` `fr`, `calc()`, `min()`
`max()` `clamp()`, `var()` with custom properties (nested, with fallbacks;
cycles make the declaration invalid), `rgb()` `rgba()` `hsl()` `hsla()`, `#rgb`
`#rrggbb` `#rrggbbaa`, named colors, `currentcolor`

**Cascade** `inherit` `initial` `unset` `revert` and `all`. Children inherit
computed values, so `font-size: 2em` does not compound down the tree.

**At-rules** `@media` with media types (`all`, `screen`, `print`), `width` /
`height` / `aspect-ratio` (with `min-` / `max-` and the range syntax),
`orientation`, `prefers-color-scheme`, `prefers-reduced-motion`, `hover`,
`pointer`; `@supports` (declarations, `selector()`, `and` / `or` / `not`);
`@import` (with a media list); `@keyframes`; `@font-face`

**Built-in stylesheets** `enderframelib:ua.css` is the user-agent sheet, with
the HTML standard's rendering defaults (`body { margin: 8px }`, `em`-based
headings, `dl` / `dd`, `border-spacing: 2px` tables, `#0000ee` links, and so
on); themes are `enderframelib:dark.css`, `enderframelib:vanilla.css`,
`enderframelib:brass.css`, and carry all the decoration.

> **Breaking change — text metrics.** `font-size` and `line-height` now mean what
> they mean in a browser. A given `font-size` renders about 1.3× larger than
> before (1em is the font's natural 9px line box, not 12px), `line-height: normal`
> reserves 1.15em instead of 1.375em, and `line-height: <length>` no longer scales
> with `font-size`. Documents that were tuned against the old numbers need their
> font sizes revisited. The reasoning is in [FIDELITY.md](FIDELITY.md).

> **Breaking change — box tree.** Layout now runs on a box tree built from the
> DOM, as in a browser. Inline content that sits next to a block is wrapped in an
> anonymous block and stacks below or above it instead of overlapping it;
> `display: contents` removes the element's own box; stray `table-cell` /
> `table-row` boxes get anonymous table parents; and `display: flow-root`,
> `overflow` other than `visible`, flex and grid items, inline-blocks and table
> cells stop their children's margins from collapsing through them. List items
> now draw a bullet or number (`ol` counts in `decimal`, nested `ul` use
> `circle` then `square`); set `list-style: none` to remove it.

> **Breaking change — formatting contexts.** `<table>` is now laid out as a CSS
> table (it used to be a column of flex rows): columns are sized from their
> cells' content instead of sharing the width equally, and `colspan` /
> `rowspan` work. If you relied on equal columns, set `table-layout: fixed`
> with a `width` on the table. `float` floats instead of stacking, `position:
> relative` no longer pushes the following siblings, absolutely positioned
> boxes with `auto` insets sit at their static position, flex items with a
> `width` now shrink, and an inline-block's baseline is its last line of text
> (or its bottom margin edge), so buttons on a line of text sit on its
> baseline. Line boxes are at least as tall as their block's `line-height`
> (the strut).

> **Breaking change — painting.** Elements are painted in the browser's order.
> A `z-index` no longer escapes the stacking context it is in (a child with
> `z-index: 100` stays below its parent's `z-index: 2` sibling), `z-index` on a
> non-positioned block has no effect (use `position: relative`), and `opacity`
> below 1 or a `transform` makes a box a stacking context. In-flow block
> backgrounds paint below all inline content of their stacking context. The
> root element's background (or `<body>`'s, if the root has none) fills the
> whole canvas; with neither, the canvas is transparent. Border widths follow
> `border-style`: `border-width: 2px` alone draws nothing (add a style, e.g.
> `border: 2px solid`), and `border-style: solid` alone is 3px wide. A border
> without a colour uses `currentcolor` (it used to be invisible).

> **Breaking change — user-agent stylesheet.** `ua.css` now holds the HTML
> standard's defaults and nothing else, so unstyled HTML looks the way it does
> in a browser: `body` has an 8px margin and no background, text is black,
> links are `#0000ee`, `p` / lists / `blockquote` / `figure` / `pre` have 1em
> vertical margins, lists are indented 40px, `dd` 40px, tables have
> `border-spacing: 2px` and 1px cell padding (no border, no 100% width),
> `pre` / `code` / `kbd` are plain monospace, `hr` is a 2px inset line,
> `fieldset` has a grooved border, `summary` shows a disclosure triangle, and
> focus is shown with an outline. Form controls take Chromium's sizes (button
> padding `1px 6px`, text inputs `1px 2px`, checkboxes 13×13). The previous
> look — compact margins, dark `pre` / `code`, styled tables, blockquotes,
> GitHub alerts and task lists — moved to the built-in themes, which also reset
> `body { margin: 0 }`. Link a theme (Markdown pages get `dark.css`
> automatically) or restyle the elements yourself. Without a background on
> `html` or `body`, a document is now transparent over the game instead of
> white.

> **Breaking change — HTML parsing.** Every document now has `html`, `head` and
> `body` elements, even when you write only a fragment, so the user-agent
> `body { margin: 8px }` applies (the built-in themes reset it to 0), and a
> top-level `height: 100%` now resolves against `body`, whose height is `auto`:
> add `html, body { height: 100%; }` (as in a browser) or use `100vh`.
> `:root` matches only the `html` element. A trailing `/>` is ignored on
> non-void elements, so `<div/>` opens a `div` that runs to its parent's end
> tag. `<tr>` / `<td>` outside a table are dropped (their content is kept),
> and text or elements written directly inside `<table>` move in front of the
> table. `&nbsp;` is now U+00A0 (it does not collapse or break), named and
> numeric references follow the standard (`&#65` without `;` is `A`), and when
> an attribute is repeated the first one wins.

> **Breaking change — sub-pixel geometry.** Lengths are no longer truncated to
> whole pixels during layout: `33.33%`, `1.5em` and `line-height: normal`
> (18.4px at 16px) keep their fractions, and positions are rounded once, when
> drawn, by snapping each box's edges. Boxes that used to come out a pixel short
> (or a few pixels short after several nested percentages) now line up with a
> browser, and lines of text advance by their exact line height, so tall blocks
> of text grow slightly. With `box-sizing: border-box`, padding and border wider
> than `width` now widen the box instead of being squeezed into it.

---

## Not supported

Known gaps, listed so you do not have to discover them by debugging.
Layout differences from a real browser are measured, case by case, in
[FIDELITY.md](FIDELITY.md).

- **Scripting.** No JavaScript, anywhere. Behaviour is server-side; the
  reactive `data-*` attributes above are the substitute. `onclick` is read only
  as an action name, never evaluated.
- **HTML parsing in full.** Formatting elements are not reconstructed and the
  adoption agency algorithm is not run: in `<p><b>x<p>y` the second paragraph
  is not bold, and misnested formatting tags such as `<b><i></b></i>` close by
  the generic end-tag rule. SVG and MathML are parsed as ordinary HTML
  elements, and `<template>` content is part of the document tree (hidden by
  `display: none`) rather than an inert fragment.
- **Nested lists in Markdown.** List parsing is flat; an indented sub-list
  becomes a sibling `<li>`. Write nested lists as HTML.
- **Tables in full.** Percentage column widths are treated as `auto`.
  `border-collapse: collapse` merges cell and table borders by width only
  (not by `border-style` precedence) and ignores row, row-group and column
  borders. No `empty-cells` and no `visibility: collapse`.
- **Floats in full; multi-column layout, writing modes.** A line next to a
  float takes its width from the float area at the top of the line, and a
  float inside an inline box that does not fit is moved to the next line
  rather than back to an earlier one. No multi-column layout or writing modes.
- **Box-tree generation in full.** A block inside an inline box does not split
  the inline box, and text directly inside a flex or grid container is not
  wrapped in an anonymous item. `<ol reversed>` does not count down.
- **`::marker` styling.** Markers are drawn with the list item's inherited
  style: `::marker` rules never match and `list-style-image` is ignored.
- **Intrinsic sizing keywords as heights.** `min-content`, `max-content` and
  `fit-content` work as widths and grid track sizes, but not as a `height`.
- **Flexbox in full.** `align-items: baseline` uses the first baseline of row
  items only. In a column, an item's automatic minimum height is its specified
  height if it has one, otherwise its content height. Lines are broken by the
  items' hypothetical sizes.
- **Grid in full.** Track sizing is a simplified version of the spec's: an
  item spanning several content-sized tracks spreads its size evenly over
  them. No subgrid, no `grid-template` / `grid` shorthands, no implicit tracks
  before the explicit grid, no `baseline` alignment, and `auto-fit` keeps its
  empty tracks (it behaves like `auto-fill`).
- **Compositing.** Stacking contexts and the CSS 2.2 painting order are
  implemented, but `opacity` is applied to each painted part separately rather
  than to the group, so overlapping descendants of a translucent box show
  through each other. `overflow` clipping is applied in each element's own
  transformed space, so a transformed descendant of a clipping box is clipped
  by a transformed copy of the clip rather than the ancestor's rectangle.
  Flex and grid items paint in document order, not `order`-modified order.
- **Borders and outlines in full.** On a box with `border-radius` the border is
  drawn solid in a single colour, whatever its `border-style`. Outlines do not
  follow `border-radius` and are not drawn per line fragment on inline boxes.
  The shading of `inset` / `outset` / `groove` / `ridge` is an approximation
  (the spec leaves it to the user agent).
- **Backgrounds in full.** Multiple layers, `url()` images and
  `background-size / position / repeat / clip / origin` work on block-level
  boxes; inline boxes paint only their background colour. Gradients are
  `linear-gradient` with the first and last colour stop and eight directions.
  `background-attachment` is always `scroll`. On a rounded box, tiles smaller
  than the painting area are clipped to its rectangle, not its rounded corners.
- **Native form controls and `legend`.** `ua.css` follows the HTML standard's
  rendering defaults, but form controls are drawn by EnderFrameLib (sized after
  Chromium's light theme) rather than by the operating system, and a `legend`
  is laid out as an ordinary block instead of sitting on its `fieldset`'s
  border.
- **Glyph-level fidelity.** `font-size: Npx` scales Minecraft's bitmap font so
  that its cap height and x-height land where a browser's UI font would
  (1em = the font's natural 9px line box), and `line-height: normal` reserves
  1.15em. Individual glyph widths still differ from any OS font, so wrap
  positions, ellipsis positions and centring offsets cannot match a browser
  exactly. See [FIDELITY.md](FIDELITY.md) for what is measured instead.
- **Media features that need a display.** `resolution` and `color-gamut` are
  unknown (and therefore false). `prefers-color-scheme` is always `light` and
  `prefers-reduced-motion` is always `no-preference`; `hover` / `pointer` report
  a mouse. `@import` with `layer()` or `supports()` is ignored, as there are no
  cascade layers.
- **Pseudo-elements beyond `::before` / `::after`.** `::marker`,
  `::placeholder`, `::selection` and friends parse (and count toward
  specificity) but never match.
- **Custom fonts.** Rendering uses Minecraft's font system; `font-family`
  selects among the fonts Minecraft has and the TrueType / OpenType fonts
  registered with `@font-face`.
- **Video** needs the bundled native decoder, which is Windows-only today, and
  YouTube sources download `yt-dlp` at first use (see [NOTICE](NOTICE)).

---

## Project layout

```
core/     HTML, CSS, layout, markdown, text — plain Java, no Minecraft. Headlessly testable.
api/      The supported surface: EnderFrameAPI, WebSessionHandle, config annotations.
common/   Shared between sides: resource loading, custom-element expansion, DevKit export.
server/   Sessions, viewers, pub/sub, commands, datapack loading, access control.
client/   Rendering, input, screens, media, reactivity.
network/  The four payloads (DOM sync, pub/sub update, action, subscribe).
```

`core/` must not import Minecraft, NeoForge, or the other packages. This is
enforced by `ArchitectureTest`, which runs in CI.

## Building

```bash
./gradlew build test          # what CI runs
./gradlew runClient
./gradlew runGameTestServer   # the server-side lifecycle game test
./gradlew publish             # into ./repo
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the comment-language convention and
for how known limitations are recorded, and [FIDELITY.md](FIDELITY.md) for the
browser-measured divergence ledger.

## License

LGPL-3.0-only. See [LICENSE](LICENSE), and
[LICENSE.GPL-3.0.txt](LICENSE.GPL-3.0.txt) whose terms LGPL-3.0 incorporates.

Third-party components — the bundled `lunex_video` native decoder, the
runtime-downloaded `yt-dlp`, JNA, and the NeoForge MDK template files — are
attributed in [NOTICE](NOTICE). The bundled native binary is **not** covered by
the LGPL grant.
