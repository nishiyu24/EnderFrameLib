# EnderFrameLib

An HTML/CSS UI library for Minecraft 1.21.1 (NeoForge).

EnderFrameLib ships a small browser engine — HTML tokenizer, CSS cascade, block /
flex / grid layout, and a Minecraft renderer — so a mod can describe its screens
and HUDs as HTML and CSS instead of hand-placing widgets and pixel offsets.

- **Screens, HUDs and in-world panels** are all the same document, driven from the
  server, from a datapack, or entirely on the client.
- **Headless server state for machine UIs.** The server keeps only a key/value
  `UIState` — no HTML, no DOM — and sends just the keys that changed. The
  template lives on the client (`assets/<namespace>/enderframe_ui/`).
- **Hybrid execution model.** Clients can drive UI independently without network
  overhead via `EnderFrameClientAPI`. Server-driven HTML sessions synchronize via
  fine-grained DOM mutation records applied directly to the live DOM tree.
- **Single DOM, no diff copies.** Client code, bindings and server packets all
  mutate the same live DOM through the standard DOM API.
- **No browser is embedded.** The engine is plain Java in `core/`, with no
  dependency on Minecraft, so it is unit-testable headlessly. Text is laid out
  with the fonts installed on the system, as a browser does.

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
import com.nishiyu.enderframelib.api.Ui;

// Open a screen for one player from an HTML string.
EnderFrameAPI.get().open(player, Ui.html("""
        <style>
          body { display: flex; flex-direction: column; gap: 8px; padding: 12px; }
          button:hover { background-color: #e5e5e5; }
        </style>
        <h2>Hello</h2>
        <p>Diamonds: <span id="count">0</span></p>
        <button onclick="server:collect">Collect</button>
        """).size(320, 200));
```

Reacting to a click, and updating the open document (the state-driven form below sends only what changed):

```java
// onclick="server:<name>[:<value>]" arrives here on the menu or block entity
// (a WebActionReceiver) that owns the session. The server only accepts action
// names that the document it sent declared.
@Override
public void onWebServerAction(ServerPlayer player, String action, String value, CompoundTag payload) {
    if (action.equals("collect")) {
        EnderFrameAPI.get().findSession(sessionId).loadHtml(updatedHtml);
    }
}
```

A UI shipped in a datapack at `data/mymod/enderframe_ui/menu.html`:

```java
EnderFrameAPI.get().open(player, Ui.datapack("mymod:menu").size(320, 200));
```

A persistent HUD overlay:

```java
EnderFrameAPI.get().hud(player, Ui.html(hudHtml));
EnderFrameAPI.get().clearHud(player);
```

Pushing live values to every subscriber of a channel (only changed keys go on
the wire):

```java
EnderFrameAPI.get().publish("mymod:stats", Map.of("kills", 12, "coins", 300));
// In the document: <span data-channel="mymod:stats" data-bind="kills"></span>

EnderFrameAPI.get().addSubscriptionCheck((player, channel) -> player.hasPermissions(2));
```

---

## Machine UIs with headless state (recommended)

For block entities and other fixed screens, ship the HTML with the client and keep
only the values on the server. The server never parses HTML or holds a DOM; it
tracks which keys changed and sends just those to the viewers at the end of each
server tick.

The template is a client resource, `assets/mymod/enderframe_ui/crusher.html`:

```html
<div class="machine">
  <span data-bind="status">{{status}}</span>
  <progress max="1" data-bind="value:progress"></progress>
  <div data-if="running">Running</div>
  <button onclick="server:toggle_power">Power</button>
</div>
```

`data-bind="key"` sets the text, `data-bind="attr:key"` an attribute and
`data-bind="style.width:key"` one style property; `{{key}}` works in text and
attribute values.

On the server:

```java
import com.nishiyu.enderframelib.api.state.UIState;

// One state per block entity; its instance id is "block:x,y,z".
UIState state = EnderFrameAPI.get().stateOf(blockEntity, "mymod:crusher");
state.onAction("toggle_power", (player, payload) ->
        state.set("running", !state.getBoolean("running", false)));

// Opening adds the player as a viewer and sends the template id plus the current state.
EnderFrameAPI.get().open(player, Ui.template("mymod:crusher").at(blockEntity));

// From the block entity's tick: unchanged values are not sent.
state.set("progress", 0.42).set("status", "Crushing");
```

Inside a headless session every action that client code did not handle is sent
to the server, and only players who are viewing the state can trigger it. A state
from `stateOf` is closed on the tick after its block entity is
removed (broken or unloaded), and the viewers' screens close with it; call
`state.close()` yourself for any other owner.

---

### In-world screens driven by state

A block screen can be fed by the same template and `UIState` instead of a server-held HTML session. Extend
`EnderScreenBlockEntity` (see `api/template`) and return the template id; register the renderer on the client with
`Surfaces.block(modEventBus, () -> TYPE)`.

```java
public class PanelEntity extends EnderScreenBlockEntity {
    public PanelEntity(BlockPos pos, BlockState state) { super(TYPE.get(), pos, state); }

    @Override public String getTemplateId() { return "mymod:panel"; }   // assets/mymod/enderframe_ui/panel.html

    @Override protected void onScreenFormed(int width, int height) {
        UIState state = screenState();                    // shared by every block of the screen
        state.set("status", "idle");
        state.onAction("toggle", (player, payload) -> state.set("status", "running"));
    }
}
```

A block shows nothing until it is told to draw: `EnderScreenBlockEntity.draw(level, pos)` turns the connected blocks
into a screen. When any block of a multiblock screen is broken, the whole screen is discarded (its session is dropped,
the state's values are forgotten, viewers drop their session) and the remaining blocks go back to showing nothing until
`draw` is called again.

Players who track the chunk see the screen; there is no "open" call. `state.set(...)` sends only the changed keys, and a
click on the surface reaches `onAction` like it does in a screen. The layout resolution is `getPixelsPerBlock()` (64) times
the screen size in blocks. On an in-world surface, both the attack (left) and use (right) buttons produce `click`, so
players can press buttons without breaking the block. Inside a screen the mouse follows the browser: only the left button
produces `click`, the right button produces `contextmenu`.

The state of a template screen is saved in the master block entity's NBT and restored when the chunk loads. Override
`onScreenState(UIState)` to set defaults for missing keys and to register `onAction` handlers; it runs every time the
state is created, after the saved values are back:

```java
@Override protected void onScreenState(UIState state) {
    if (state.get("status") == null) state.set("status", "idle");
    state.onAction("toggle", (p, payload) -> state.set("status", "running"));
}
```

## Client-driven UI & Hybrid Execution Model

EnderFrameLib 1.0+ supports a **hybrid execution model** where documents can be opened
and mutated entirely on the client side without network communication, while seamlessly
interoperating with server-driven sessions.

### Client-driven screens & HUDs

```java
import com.nishiyu.enderframelib.api.ClientSession;
import com.nishiyu.enderframelib.api.EnderFrameClientAPI;
import com.nishiyu.enderframelib.api.Ui;
import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;

// Open a client-only screen; the session gives the live Document and its timers
ClientSession session = EnderFrameClientAPI.get().open(Ui.html("""
    <div id="container">
      <h1 id="title">Client Panel</h1>
      <button id="actionBtn">Click</button>
    </div>
    """).size(400, 300).session("mymod:client_menu"));
Document doc = session.document();

// Mutate the live DOM directly via standard DOM API
Element title = doc.getElementById("title");
title.setTextContent("Updated by Client!");

// Client listeners run first; calling preventDefault() prevents server transfer
Element btn = doc.getElementById("actionBtn");
btn.addEventListener("click", evt -> {
    System.out.println("Handled on client!");
    evt.preventDefault(); // Do not transfer to server even if onclick="server:..." or data-forward is present
});
```

### Client scheduler

Client-driven documents can schedule rendering and game-tick tasks. All tasks are
automatically cancelled when the document session is closed.

```java
// Run on the next render frame (equivalent to requestAnimationFrame)
int frameTaskId = session.requestAnimationFrame(timestamp -> {
    // High-frequency animation or rendering update
});

// Run after a delay in milliseconds, measured in client ticks
int timerId = session.setTimeout(() -> {
    doc.getElementById("title").setTextContent("Delayed update");
}, 1000);

// setInterval works the same way; session.cancel(handle) cancels any of the three.
```

### Server-sent HTML: ownership model & mutation sync

This applies to documents sent from the server as HTML (`Ui.html`,
`Ui.datapack`, `hud`, `WebSessionHandle`). Headless-state UIs above
are client-owned templates and need none of it.

- **Single DOM**: Both client scripts and server packets operate on the single live DOM tree; there are no template/virtual copies and no diff engine.
- **Subtree Ownership**: every subtree is either server-owned or client-owned. Declare it with `data-owner="server|client"` (inherited by descendants); without it the document default applies — **server-owned** for a document synced from the server, **client-owned** for one opened on the client only (`EnderFrameClientAPI.open` / `hud` / `loadLocalHtml`).
  - Client code *can* write into a server-owned subtree, but the next server mutation (or a full re-sync) wins and overwrites it. This is the contract, not a limitation.
  - Server mutation records aimed at a client-owned subtree are dropped, and a re-sync keeps client-owned islands (moved to the same `id` in the new tree; they vanish only if the server removed their host).
  - A client-opened document that the server also patches must mark those subtrees `data-owner="server"`.
- **Server Sync** (internal, also behind `/enderframe patch`): `WebSession.patchText / patchAttribute / patchStyle / patchClass / patchInnerHtml` generate fine-grained `DomMutationRecord` packets that apply directly to target nodes without tree-wide diffing.
- **Declared event forwarding**: an element forwards events to the server with `data-forward="<event>[:<payload>] …"` and `data-forward-action="<name[:arg]>"`; payload is one of `value`, `key`, `point`, `target`, `none` (default depends on the event). Client listeners run first; the event is sent only if nothing called `preventDefault()` and it actually reached the declaring element (bubbling and `stopPropagation()` apply).
  `onclick="server:name"` is sugar for the same declaration, and inputs/textareas/selects inside server-owned subtrees implicitly forward `input`/`change` values for write-back. The server only accepts action names its own DOM declared (`data-forward-action` or `server:`), and the usual `canAccess` / rate-limit checks still apply.
  The old `setForwardEventsToServer(...)` switch is gone; an unhandled action name without `server:` stays on the client.
- **List Reuse**: `data-for` reuses existing node instances by key (`data-row-key` or index) to preserve node identity and internal state.


### Commands

Useful without writing any Java:

```
/enderframe open <players> <ui_id> [<session_id> [<width> <height>]]
/enderframe open_inline <players> <html…>
/enderframe hud set <players> <ui_id>
/enderframe hud clear <players>
/enderframe session load <session_id> <ui_id> [<width> <height>]
/enderframe session clear <session_id>
/enderframe patch text|html <session_id> <element_id> <value…>
/enderframe patch style <session_id> <element_id> <property> <value…>
/enderframe patch attr <session_id> <element_id> <name> <value…>
/enderframe patch class <session_id> <element_id> add|remove|toggle <class>
/enderframe publish <channel> <snbt>
/enderframe desktop [<players>]    # open the bundled desktop demo
/enderframe devkit                 # export the browser preview kit
```

Everything except `desktop` without a target needs permission level 2.

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
| `<mc-energy value max label>` | an energy bar; the label defaults to `value / max FE` (`label="none"` hides it) |
| `<mc-tank value max fluid label>` | a fluid tank bar tinted by `fluid` (`water`, `lava`, `milk`, `honey`); the label defaults to `value / max mB` |
| `<mc-entity>` / `<mc-scene>` | a live entity / block scene |
| `<mc-fluid>` | a fluid texture |
| `<img src="item:minecraft:diamond">` | an item stack as an image |

**Translations.** `{{i18n:key}}` and `data-i18n="key"` use the game's language files (`assets/<modid>/lang/*.json`).
Arguments fill `%s` in the translation: `{{i18n:mymod.progress, percent, 'Furnace'}}` takes `percent` from the state
(a quoted argument is a literal) and updates when it changes. `{{statusKey|i18n}}` translates a state value.

**Reactive attributes** (no scripting)

`data-model` `data-bind` `data-if` `data-show` `data-for` `data-key`
`data-row-key` `data-class` `data-channel` `data-list` `data-i18n`
`data-item` `data-tooltip` `data-sound` `data-markdown` `data-syntax`
`data-hud` `data-tracker` `data-dropzone` `data-slot` `data-zoom`
`data-pan-zoom`, and for server-sent HTML `data-owner` `data-forward`
`data-forward-action`

- `data-if` (and alias `mc-if`): Toggles the HTML `hidden` boolean attribute on the element based on the condition expression.
- `data-show`: Toggles inline `display: none` style on the element, restoring the original display property when condition is true.
- `data-list`: Evaluates a list model or inline JSON array/object to expand children/templates.

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
(`[a]` `[a=v]` `[a^=v]` `[a$=v]` `[a*=v]` `[a~=v]` `[a|=v]`, with the `i` / `s`
flags), selector lists, CSS escapes in names (`.sm\:hidden`), and CSS nesting
(`&`, nested rules and their specificity). Specificity and cascade origin are
tracked properly, and `!important` wins. A selector the engine does not
understand is invalid, as in a browser: it matches nothing and drops the whole
rule it is listed in.

**Pseudo-classes** `:hover` `:active` `:focus` `:focus-visible` `:focus-within`
`:checked` `:disabled` `:enabled` `:indeterminate` `:open` `:required`
`:optional` `:read-only` `:read-write` `:placeholder-shown` `:valid` `:invalid`
`:in-range` `:out-of-range` `:link` `:any-link` `:visited` (never matches)
`:target` `:defined` `:lang()` `:dir()` `:first-child` `:last-child`
`:only-child` `:first-of-type` `:last-of-type` `:only-of-type` `:nth-child()`
`:nth-last-child()` `:nth-of-type()` `:nth-last-of-type()` (any `An+B`, `odd`,
`even`, and `of S`) `:empty` `:root` `:scope` `:not()` `:is()` `:where()`
`:has()` (selector lists, including complex and relative selectors)

**Pseudo-elements** `::before` `::after` (`content` with strings, `attr()`,
`counter()` and `counters()`; they follow the cascade like elements and update
on state changes such as `:hover::after`); `::marker` on list items,
`::placeholder` on text inputs, `::selection`

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
`ridge` `inset` `outset` `none` `hidden`) `border-radius` and its per-corner
longhands (`border-top-left-radius` …) `outline`
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

**Text** `color` `font-size` `font-family` (fonts installed on the system,
matched as in a browser, plus Minecraft's fonts and `@font-face`) `font-weight`
`font-style` `font-kerning`
`line-height` `letter-spacing` `text-align` `text-indent` `vertical-align`
(`baseline` `top` `bottom` `middle` `sub` `super` `text-top` `text-bottom`,
lengths, `%`) `text-decoration` `text-shadow`
`text-transform` `white-space` (`normal` `nowrap` `pre` `pre-wrap` `pre-line`)
`word-break` `overflow-wrap` `text-overflow` `direction`

**Paint / effects** stacking contexts (positioned boxes with a `z-index`,
flex and grid items with a `z-index`, `opacity` below 1, `transform`, `filter`,
`isolation: isolate`, `mix-blend-mode`, and fixed / sticky boxes) painted in
CSS 2.2 Appendix E order; `background` with multiple layers,
`background-color` `background-image` (`url()`, `linear-gradient()`,
`radial-gradient()`, `conic-gradient()` and their `repeating-` forms, with
colour hints and `in <colorspace>`)
`background-size` `background-position` `background-repeat` (including
`space` and `round`) `background-clip` `background-origin`; `box-shadow` `transform`
(`translate` `scale` `rotate`) `transform-origin` `transition`
`transition-delay` `transition-timing-function` `animation` with `@keyframes`
(lengths, numbers and colours of any animatable property, including
layout-affecting ones such as `width` and `margin`; no `steps()`,
`transition-behavior` or `@starting-style` yet)
`object-fit` `cursor` `content` `src`

**Units & functions** `px` `%` `em` `rem` `ch` `ex` `cap` `ic` `lh` `rlh`
`vw` `vh` `vmin` `vmax` `vi` `vb` and their `sv*` / `lv*` / `dv*` forms,
container units (`cqw` `cqh` `cqi` `cqb` `cqmin` `cqmax`), `pt` `pc` `in` `cm`
`mm` `Q` `fr`; `calc()` `min()` `max()` `clamp()` `round()` `mod()` `rem()`
`abs()` `sign()` `pow()` `sqrt()` `hypot()` `log()` `exp()` and the
trigonometric functions (nested in any combination, typed: an expression whose
units do not fit is invalid); `var()` with custom properties (nested, with
fallbacks; cycles make the declaration invalid).

**Colours** `#rgb` `#rgba` `#rrggbb` `#rrggbbaa`, the 148 CSS named colors,
`transparent`, `currentcolor`, `rgb()` `hsl()` `hwb()` `lab()` `lch()`
`oklab()` `oklch()` `color()` (comma and space syntax, `/ alpha`, `none`),
`color-mix()` and relative colours (`rgb(from red r g b)`). Colours outside sRGB
are gamut-mapped as CSS Color 4 describes. Minecraft's 16 chat colours are
`var(--mc-red)`, `var(--mc-gold)`, `var(--mc-dark-aqua)` and so on.

**Cascade** origins, `!important`, cascade layers (`@layer`, nested and
anonymous layers, `@import … layer()`), `inherit` `initial` `unset` `revert`
`revert-layer` and `all`. Children inherit computed values, so
`font-size: 2em` does not compound down the tree.

**At-rules** `@media` with media types (`all`, `screen`, `print`), `width` /
`height` / `aspect-ratio` (with `min-` / `max-` and the range syntax),
`orientation`, `resolution`, `prefers-color-scheme`, `prefers-reduced-motion`,
`hover` / `any-hover`, `pointer` / `any-pointer`; `@supports` (declarations,
`selector()`, `and` / `or` / `not`); `@container` (`container-type`,
`container-name`, size and `style()` queries); `@layer`; `@property`
(registered custom properties are typed and animatable); `@import` (with
`layer()`, `supports()` and a media list); `@keyframes`; `@font-face`

**Built-in stylesheets** `enderframelib:ua.css` is the user-agent sheet, with
the HTML standard's rendering defaults (`body { margin: 8px }`, `em`-based
headings, `dl` / `dd`, `border-spacing: 2px` tables, `#0000ee` links, and so
on); themes are `enderframelib:dark.css`, `enderframelib:vanilla.css`,
`enderframelib:brass.css`, and carry all the decoration.

> **Breaking change — system fonts.** Text is now laid out and drawn with the
> fonts installed on the system, chosen from `font-family` the way a browser
> chooses them (an unstyled document gets the standard font Chrome on Windows
> would use, form controls Arial, `code` / `pre` a monospace font), and rasterised at the
> device pixel size. Line breaks, widths and line heights therefore follow
> Chrome rather than Minecraft's font. To keep Minecraft's look, set
> `font-family: minecraft:default` (or `minecraft:uniform` / `minecraft:alt`);
> the metrics note below then applies.

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

> **Breaking change — clipping, visibility and decorations.** `overflow: hidden`
> and the `overflow-x` / `overflow-y` longhands now clip children (only the
> shorthand's literal value used to be read, so most clipping silently did
> nothing), and a scroll container now cuts off its content. An absolutely
> positioned descendant is clipped only by boxes that contain it, so a
> dropdown inside a non-positioned `overflow: hidden` box is no longer cut off,
> and `position: fixed` is not clipped by ancestors. `visibility: hidden` hides
> a box (keeping its space) and stops it from receiving clicks, while a
> `visibility: visible` descendant still shows. `pointer-events: none` now lets
> clicks through. A rounded border paints only the border area, so a
> transparent box with `border-radius` and a border is no longer filled with the
> border colour. `box-shadow` follows its offsets, blur, spread, `inset` and
> lists of shadows (it used to be a fixed small drop shadow), and
> `linear-gradient()` uses its angle and every colour stop.

> **Breaking change — dynamic restyling and pseudo-elements.** `::before` and
> `::after` now go through the normal cascade (a later shorthand overrides an
> earlier longhand, `!important` and CSS-wide keywords behave as on elements),
> and element rules such as `span { ... }` no longer leak into them. They follow
> state changes, so `:hover::before { content: "→" }` appears and disappears
> and `:checked::after` changes colour; hovering a pseudo-element hovers its
> element. A state change on one element now restyles its following siblings
> when a rule needs it (`input:checked + label`, `.tab:hover ~ .panel`).
> `<style>` and `<link rel="stylesheet">` are read from the parsed document in
> document order (links used to come before every `<style>`), so markup inside
> comments, `<textarea>` and `<template>` is no longer treated as CSS, and the
> `<style>` / `<script>` elements stay in the DOM (hidden by the user-agent
> sheet), which counts them for `:first-child`, `+` and the like as a browser
> does.

> **Breaking change — colours, selectors and values.** Named colours are the CSS
> ones: `red` is `#ff0000` (it was Minecraft's `#ff5555`), `green` is `#008000`,
> `gray` is `#808080`, `yellow` is `#ffff00`; use `var(--mc-red)` and friends for
> Minecraft's palette. `#rrggbbaa` is read as red, green, blue, alpha (it used
> to be taken as ARGB, so `#ff000080` came out dark blue), `#rgba` works, an
> alpha of `0.5` rounds to 128 rather than 127, and `color: transparent` text is
> invisible instead of off-white. A colour the engine cannot read makes the
> declaration invalid (it used to paint white). An unknown pseudo-class
> (`:has()`, a typo) or an `An+B` it cannot read now makes the selector match
> nothing and drops the rule's whole selector list; previously the condition
> was skipped and the rule applied to every element (`li:nth-child(3n)` styled
> all items). `::placeholder`, `::marker` and other unrendered pseudo-elements
> no longer style the element itself. Two conditions on the same attribute both
> apply. `border-radius` is a shorthand for the four corner longhands, which
> now take effect. A shorthand resets the longhands it does not mention (for
> example `flex-flow: wrap` resets `flex-direction` to `row`), and `flex`
> rejects values it cannot read instead of using them as `flex-basis`.

> **Breaking change — inline boxes and layout edge cases.** An inline box such
> as `<span>`, `<code>` or `<kbd>` now gets its left padding, border and margin
> once, at its start, and its right ones once, at its end, with one background
> per line: a multi-word `<span style="padding: 0 4px">` is no longer padded
> around every word, and a box that wraps has its left edge only on the first
> line and its right edge only on the last. Inline boxes now draw their borders
> and rounded corners. Their vertical padding and borders no longer make the
> line taller (they overlap the lines above and below, as in a browser); add
> `line-height` or use `inline-block` if you relied on the extra space. Centred
> and right-aligned lines ignore their trailing space. A percentage height now
> resolves inside a stretched flex or grid item and inside an absolutely
> positioned box sized by `top` and `bottom`. A shrink-to-fit box (inline-block,
> float, flex item) containing `<br>` is as wide as its widest line rather than
> all lines put together. In `flex-direction: column; flex-wrap: wrap` the
> container is as wide as all its columns, and `justify-content: left / right /
> start / end` keep their direction in `row-reverse` and `column-reverse`
> (`row-reverse; justify-content: left` packs items to the left).

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

- **Scripting & MutationObserver timing.** No JavaScript, anywhere. Behaviour is
  server-side; the reactive `data-*` attributes above are the substitute. `onclick` is read only
  as an action name, never evaluated. A Java-side `MutationObserver` API is available, but
  notifications are batched and dispatched at client tick (before restyle) or manual flush rather
  than via JavaScript microtasks.
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
  borders. No `empty-cells`, and `visibility: collapse` hides a row or column
  like `hidden` instead of removing its space.
- **Floats in full; multi-column layout, writing modes.** A line next to a
  float takes its width from the float area at the top of the line, and a
  float inside an inline box that does not fit is moved to the next line
  rather than back to an earlier one. No multi-column layout or writing modes.
- **Box-tree generation in full.** A block inside an inline box does not split
  the inline box, and text directly inside a flex or grid container is not
  wrapped in an anonymous item. `<ol reversed>` does not count down.
- **List marker images.** `list-style-image` is ignored; markers are always
  text (styled by `::marker`).
- **Intrinsic sizing keywords as heights.** `min-content`, `max-content` and
  `fit-content` work as widths and grid track sizes, but not as a `height`.
- **Flexbox in full.** `align-items: baseline` uses the first baseline of row
  items only. In a column, an item's automatic minimum height is its specified
  height if it has one, otherwise its content height. Lines are broken by the
  items' hypothetical sizes. In a flex container whose height is not definite,
  an item's percentage height resolves against the container's own percentage
  basis instead of behaving as `auto`.
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
  drawn solid whatever its `border-style`, and where two sides of different
  colours meet in a rounded corner the colour changes at the side's width
  rather than along the diagonal. Outlines do not
  follow `border-radius` and are not drawn per line fragment on inline boxes.
  The shading of `inset` / `outset` / `groove` / `ridge` is an approximation
  (the spec leaves it to the user agent).
- **Backgrounds in full.** Multiple layers, `url()` images and
  `background-size / position / repeat / clip / origin` work on block-level
  boxes; inline boxes paint their background colour and borders on each line
  but no background images. Radial, conic and diagonal multi-stop gradients
  are drawn as a mesh of rectangles whose corner colours are interpolated
  linearly, so a hard colour edge is only as sharp as the mesh.
  `background-attachment` is always `scroll`.
- **Box shadows in full.** A blurred `box-shadow` is a Gaussian-like falloff
  drawn by the SDF shader, a blurred outer shadow is cut out of the border box
  (so it does not show through a translucent background), and an `inset` shadow
  has the rounded hole and the same falloff. An outer shadow with no blur is
  still painted under the whole box, so it shows through a transparent or
  translucent background. Corners are circular (no elliptical `border-radius`).
- **Native form controls.** `ua.css` follows the HTML standard's rendering
  defaults, but form controls are drawn by EnderFrameLib (sized after
  Chromium's light theme) rather than by the operating system.
- **Glyph-level fidelity.** Text uses the fonts installed on the system
  (matched as CSS Fonts 4 §5.2 describes), but there are no ligatures,
  `line-height: normal` ignores the metrics of fallback fonts, and
  `font-weight: 500` on a family without a 500 face picks Regular as the
  standard says (Chrome on Windows picks Semibold). Generic families
  (`sans-serif` and so on) map to the fonts Chrome uses on Windows; on other
  systems the installed fallbacks are used, so text can measure differently.
  Fonts registered with `@font-face` and the `minecraft:*` families are drawn
  with Minecraft's bitmap font system instead. See [FIDELITY.md](FIDELITY.md)
  for what is measured.
- **Media features that need a display.** `color-gamut` is unknown (and
  therefore false). `prefers-color-scheme` is always `light` and
  `prefers-reduced-motion` is always `no-preference`; `hover` / `pointer` report
  a mouse, and `prefers-contrast` / `forced-colors` report no preference.
- **Pseudo-elements beyond the supported ones.** `::first-line`,
  `::first-letter`, `::backdrop`, `::file-selector-button` and friends parse
  (and count toward specificity) but never match.
- **Selectors in full.** Pseudo-classes not listed under *Supported CSS*
  (`:user-valid`, `:autofill`, `:fullscreen` and so on) make the selector
  invalid, as an unknown pseudo-class does in a browser. There are no
  namespaces (`svg|rect`).
- **Glyph-relative units and elliptical corners.** `ch` and `ex` are taken as
  0.5em instead of being measured from the font, `ic` as 1em and `cap` as a
  fixed fraction of the font size. Corners are drawn as circular arcs, so an
  elliptical radius (`10px / 20px`, or a percentage on a box that is not
  square) uses the smaller of its two radii.
- **Transforms in full.** `transform`, `translate`, `rotate` and `scale` are
  composed in written order into a 2D matrix, but a box carries only a
  translation, a rotation and a scale, so the shear of `skew*()` and of a
  `matrix()` is dropped, and the depth part of 3D functions
  (`translateZ()`, `rotateX()` / `rotateY()`, `perspective()`) is not drawn.
- **Video** needs the bundled native decoder, which is Windows-only today, and
  YouTube sources need a `yt-dlp` binary you place in `<game dir>/enderframelib/` yourself
  and the opt-in `allowYtDlp` setting; it is never downloaded or self-updated (see [NOTICE](NOTICE)).

---

## Project layout

```
core/     HTML, CSS, layout, markdown, text — plain Java, no Minecraft. Headlessly testable.
api/      The supported surface: EnderFrameAPI, EnderFrameClientAPI, WebSessionHandle,
          UIState (api/state), the DOM and event interfaces (api/dom, api/event), config.
common/   Shared between sides: resource loading, custom-element expansion, the render
          pipeline, DevKit export.
server/   Headless UI state, HTML sessions, viewers, pub/sub, commands, datapack loading,
          access control.
client/   Rendering, input, screens, templates, media, reactivity.
network/  The payloads: headless open / state delta / action, DOM sync and mutations,
          HTML-session action, pub/sub update and subscribe.
```

`core/` must not import Minecraft, NeoForge, or the packages above it, and
`api/` must not import the implementation packages. This is enforced by
`ArchitectureTest` and `ApiBoundaryTest`, which run in CI. The internal
structure is described in [ARCHITECTURE.md](ARCHITECTURE.md) (Japanese).

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
user-supplied `yt-dlp`, JNA, and the NeoForge MDK template files — are
attributed in [NOTICE](NOTICE). The bundled native binary is **not** covered by
the LGPL grant.

### Migration notes

**Headless state**

- Machine UIs that sent their HTML from the server with `Ui.html` and
  updated it with `WebSessionHandle.patch*` can move to `UIState`: ship the
  HTML as `assets/<namespace>/enderframe_ui/<name>.html`, replace each patched
  element with a `data-bind` / `{{key}}` binding, and call `UIState.set`
  instead of `patch*`. `Ui.html` and `WebSessionHandle` remain for ad-hoc HTML.

**Live DOM and hybrid execution**

- The old diff-apply engine, `SessionContext.virtualDom()/templateDom()` and `ClientWebManager.getVirtualRoot()/getTemplateRoot()` are removed; use `SessionContext.rootNode()` / `domDocument()` from `ClientWebManager.getSession(sessionId)`.
- Inline actions (`on*` attributes) now run *after* propagation and only if the event was not cancelled. A `preventDefault()` in any listener suppresses both the local action and the server forward.
- `ClientScreenInteractionManager.setForwardEventsToServer` is removed: use `data-forward` or the `server:` prefix.
- A re-sync recreates server-owned nodes (hover/focus/scroll state tied to the old node objects is reset); fine-grained updates should use mutation records, which keep node identity.
- Common screen helper operations (`setTextareaValue`, `setInputValue`, `toScreenRect`, etc.) on `AbstractEnderScreen` and `AbstractEnderContainerScreen` are extracted into the `EnderScreenHost` interface; downstream code keeps full binary and source compatibility.
- Input event synthesis (pointer, mouse, click synthesis, scroll, keys, and text editing) is centralized into `SessionInput` (`session.input()`); GUI screens and in-world screens (blocks, items, HUD) share the exact same event synthesis pipeline.
### Holograms and items driven by state

Holograms and items show a template the same way a block does: the server keeps a `UIState` and the client renders.

```java
// server: a state without a block entity, shown to a player on a 640x360 surface
UIState board = EnderFrameAPI.get().stateOf("mymod:board", "lobby");
board.set("title", "Welcome");
EnderFrameAPI.get().showSurface(player, Ui.template("mymod:board").instance("lobby"), 640, 360);
// EnderFrameAPI.get().hideSurface(player, ...) stops it

// client: the session is named by the instance id
Surfaces.hologram("lobby", Hologram.at("lobby", new Vec3(x, y, z), 2f, 1.125f).asBillboard());
// an item: ((EnderScreenItem) TABLET.get()).createStack("lobby")

// make the hologram a menu you aim at and click (hover, buttons, drags; a text field opens the same invisible input as on a block screen)
Surfaces.hologram("lobby", Hologram.at("lobby", pos, 2f, 1.125f).asBillboard().asInteractive());
```

`showSurface` is for players you choose (who is near, who holds the item). Chunk-tracking players are handled
automatically only for block screens.
