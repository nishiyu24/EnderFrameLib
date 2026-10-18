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

**Lists / tables** `ul` `ol` `li` `table` `thead` `tbody` `tr` `th` `td`
(tables are laid out as flex rows, not as a real table formatting context)

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

**Pseudo-elements** `::before` `::after` (with `content`)

**Box model** `display` (`block` `inline-block` `flex` `grid` `none`)
`position` (`static` `relative` `absolute` `fixed`) `top` `right` `bottom`
`left` `z-index` `width` `height` `min-*` `max-*` `margin` `padding` `border`
`border-*-width` `border-radius` `box-sizing` `aspect-ratio` `overflow`
`overflow-x` `overflow-y` `scrollbar-width` `visibility` `opacity`

**Flexbox** `flex` `flex-grow` `flex-shrink` `flex-basis` `flex-direction`
`flex-wrap` `justify-content` `align-items` `align-self` `gap` `row-gap`
`column-gap`

**Grid** `grid-template-columns` `grid-template-rows` (`px`, `fr`, `auto`,
`repeat()`, `minmax()`) `grid-column` `grid-row` `grid-gap` `grid-row-gap`
`grid-column-gap`

**Text** `color` `font-size` `font-family` `font-weight` `font-style`
`line-height` `letter-spacing` `text-align` `text-decoration` `text-shadow`
`text-transform` `white-space` (`normal` `nowrap` `pre` `pre-wrap` `pre-line`)
`word-break` `overflow-wrap` `text-overflow` `direction`

**Paint / effects** `background` `background-color` `box-shadow` `transform`
(`translate` `scale` `rotate`) `transform-origin` `transition`
`transition-delay` `transition-timing-function` `animation` with `@keyframes`
`object-fit` `cursor` `content` `src`

**Units & functions** `px` `%` `em` `rem` `vw` `vh` `fr`, `calc()`, `var()`
with custom properties, `rgb()` `rgba()` `hsl()` `hsla()`, `#rgb` `#rrggbb`
`#rrggbbaa`, named colors

**At-rules** `@media (min-width: …px)` / `(max-width: …px)` — comma lists are
OR, conditions within one query are AND, `not` is honoured — and `@keyframes`

**Built-in stylesheets** `enderframelib:ua.css` is the user-agent sheet; themes
are `enderframelib:dark.css`, `enderframelib:vanilla.css`,
`enderframelib:brass.css`.

> **Breaking change — text metrics.** `font-size` and `line-height` now mean what
> they mean in a browser. A given `font-size` renders about 1.3× larger than
> before (1em is the font's natural 9px line box, not 12px), `line-height: normal`
> reserves 1.15em instead of 1.375em, and `line-height: <length>` no longer scales
> with `font-size`. Documents that were tuned against the old numbers need their
> font sizes revisited. The reasoning is in [FIDELITY.md](FIDELITY.md).

---

## Not supported

Known gaps, listed so you do not have to discover them by debugging.
Layout differences from a real browser are measured, case by case, in
[FIDELITY.md](FIDELITY.md).

- **Scripting.** No JavaScript, anywhere. Behaviour is server-side; the
  reactive `data-*` attributes above are the substitute. `onclick` is read only
  as an action name, never evaluated.
- **Nested lists in Markdown.** List parsing is flat; an indented sub-list
  becomes a sibling `<li>`. Write nested lists as HTML.
- **Real table layout.** `table`/`tr`/`td` are flex rows: no `colspan`,
  no `rowspan`, no automatic column sizing.
- **`float`, `clear`, multi-column layout, writing modes.**
- **`flex-basis`.** The `flex` shorthand expands to `flex-grow` / `flex-shrink` /
  `flex-basis`, and the first two are honoured, but the basis itself is not read:
  an item's base size comes from its content and its `width`.
- **Intrinsic sizing keywords outside `min-width` / `max-width`.**
  `min-content`, `max-content` and `fit-content` are understood as a `min-width`
  or `max-width` value; as a `width` or in `grid-template-*` they are not.
- **`box-sizing` defaults to `border-box`.** CSS defaults to `content-box`:
  `width: 100px; padding: 10px; border-width: 2px` should occupy 124px, and here
  it occupies 100px.
- **Margin collapsing.** Adjacent vertical margins are added, not collapsed, and a
  child's top margin does not collapse through its parent's top edge.
- **`gap` in block flow.** `row-gap` separates block-level children here; in CSS
  `gap` only applies to flex, grid and multi-column containers.
- **Percentage `height`.** The percentage resolves against a size handed down from
  an ancestor rather than against the containing block.
- **Percentage vertical `margin` / `padding`.** CSS resolves these against the
  containing block's *inline* size; here they resolve against a height-like value
  handed down from an ancestor.
- **Containing blocks for `position: absolute`.** A `position: static` parent is
  treated as the containing block; CSS uses the nearest positioned ancestor, or the
  initial containing block if there is none.
- **`minmax()` in a track list.** Track lists are split on whitespace, so
  `minmax(100px, 1fr)` is read as two tracks.
- **Inline formatting contexts in full.** Inline boxes line-break, measure and
  align on a common baseline, but `vertical-align` and floats around them are
  not implemented.
- **Glyph-level fidelity.** `font-size: Npx` scales Minecraft's bitmap font so
  that its cap height and x-height land where a browser's UI font would
  (1em = the font's natural 9px line box), and `line-height: normal` reserves
  1.15em. Individual glyph widths still differ from any OS font, so wrap
  positions, ellipsis positions and centring offsets cannot match a browser
  exactly. See [FIDELITY.md](FIDELITY.md) for what is measured instead.
- **`@media` beyond width.** No `prefers-*`, orientation or resolution
  features, no `@supports`, no `@import`, no `@font-face`.
- **Named grid lines and areas.** `grid-template-areas` is not implemented.
- **Pseudo-elements beyond `::before` / `::after`.** `::placeholder`,
  `::selection` and friends parse (and count toward specificity) but never
  match.
- **Custom fonts.** Rendering uses Minecraft's font system; `font-family`
  selects among the fonts Minecraft has.
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
