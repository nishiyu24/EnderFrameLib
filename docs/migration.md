# Migration & Breaking Changes

## Migration notes

### Headless state

- Machine UIs that sent their HTML from the server with `Ui.html` and
  updated it with `WebSessionHandle.patch*` can move to `UIState`: ship the
  HTML as `assets/<namespace>/enderframe_ui/<name>.html`, replace each patched
  element with a `data-bind` / `{{key}}` binding, and call `UIState.set`
  instead of `patch*`. `Ui.html` and `WebSessionHandle` remain for ad-hoc HTML.

### Live DOM and hybrid execution

- The old diff-apply engine, `SessionContext.virtualDom()/templateDom()` and `ClientWebManager.getVirtualRoot()/getTemplateRoot()` are removed; use `SessionContext.rootNode()` / `domDocument()` from `ClientWebManager.getSession(sessionId)`.
- Inline actions (`on*` attributes) now run *after* propagation and only if the event was not cancelled. A `preventDefault()` in any listener suppresses both the local action and the server forward.
- `ClientScreenInteractionManager.setForwardEventsToServer` is removed: use `data-forward` or the `server:` prefix.
- A re-sync recreates server-owned nodes (hover/focus/scroll state tied to the old node objects is reset); fine-grained updates should use mutation records, which keep node identity.
- Common screen helper operations (`setTextareaValue`, `setInputValue`, `toScreenRect`, etc.) on `AbstractEnderScreen` and `AbstractEnderContainerScreen` are extracted into the `EnderScreenHost` interface; downstream code keeps full binary and source compatibility.
- Input event synthesis (pointer, mouse, click synthesis, scroll, keys, and text editing) is centralized into `SessionInput` (`session.input()`); GUI screens and in-world screens (blocks, items, HUD) share the exact same event synthesis pipeline.

---

## Breaking changes (Rendering & CSS engine)

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
> font sizes revisited. The reasoning is in [FIDELITY.md](../FIDELITY.md).

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
