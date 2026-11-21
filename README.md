# EnderFrameLib

[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![License: LGPL-3.0](https://img.shields.io/badge/License-LGPL_3.0-green.svg)](https://www.gnu.org/licenses/lgpl-3.0.html)

**EnderFrameLib** is an **in-process, pure-Java 21 HTML/CSS UI rendering engine** (`:core`, `:core-gl`, `:core-vulkan`, `:core-glfw`), accompanied by an **optional Minecraft 1.21.1 (NeoForge) integration mod** built on top of the engine.

The core engine has **zero dependencies on Minecraft or NeoForge**. It parses HTML5 documents, cascades and animates CSS, runs Flexbox / Grid / Block / Table / Multicolumn / Vertical-writing layout, shapes text using OS or `@font-face` fonts, and renders through interchangeable CPU, OpenGL 3.2+, and Vulkan 1.1 backends — suitable for desktop tools, custom game engines, server-side headless image generation, and embedded UI overlays. The root project adds a full-featured Minecraft mod integration (GUI screens, HUDs, in-world block/hologram/item displays, headless `UIState` networking, and `<mc-*>` elements) as one host implementation of that engine.

> **Status:** Usable, pre-1.0 API evolution in practice (artifact version `2.0.0`). The supported public surface is `com.nishiyu.enderframelib.api.*`; everything outside `api/` is `@ApiStatus.Internal` and subject to change.

---

## Table of Contents

- [What Is EnderFrameLib?](#what-is-enderframelib)
- [Why Not a Browser Wrapper or Hand-Coded Widgets?](#why-not-a-browser-wrapper-or-hand-coded-widgets)
- [Modular Architecture](#modular-architecture)
- [Core Engine Capabilities](#core-engine-capabilities)
- [Optional Minecraft Integration (NeoForge Mod)](#optional-minecraft-integration-neoforge-mod)
- [Scope, Performance, and Fidelity](#scope-performance-and-fidelity)
- [Using the Standalone Engine (`:core`)](#using-the-standalone-engine-core)
- [Using the Minecraft 1.21.1 Integration](#using-the-minecraft-1211-integration)
- [Supported HTML, CSS, and Extensions](#supported-html-css-and-extensions)
- [Migration & Breaking Changes](#migration--breaking-changes)
- [Not Supported](#not-supported)
- [Building from Source & Project Layout](#building-from-source--project-layout)
- [Documentation Map](#documentation-map)
- [Contributing & License](#contributing--license)

---

## What Is EnderFrameLib?

Most Java applications that want modern, flexible interfaces face an awkward choice: either hand-place widgets with ad-hoc coordinate math, or embed an external browser process (CEF / Chromium / WebView2) that brings hundreds of megabytes of native runtime, cross-process IPC, and a JavaScript boundary.

EnderFrameLib takes a third path: **a compact, deterministic HTML/CSS layout and rendering engine written directly in standard Java 21**, paired with a thin, API-neutral GPU abstraction (RHI) and a tile-parallel software rasterizer.

At its heart, EnderFrameLib consists of two layers in one repository:

1. **The Engine (`:core` + `:core-gl` / `:core-vulkan` / `:core-glfw`)** — A standalone Java library for embedding HTML/CSS surfaces into any JVM application, game engine, or headless pipeline. It exposes a clean Java API (`EnderEngine`, `WebViewSurface`, `Document`, `UI` DSL, and `api.spi` host extensions) with no game-specific assumptions.
2. **The Minecraft Integration (Root `:EnderFrameLib` module)** — A NeoForge 1.21.1 mod built on top of `:core` that showcases what a host application can build with the engine: player screens, HUD overlays, interactive in-world multiblock panels, floating holograms, held-item displays, datapack UIs, and tick-synchronized server/client state.

---

## Why Not a Browser Wrapper or Hand-Coded Widgets?

EnderFrameLib is designed specifically for **in-process, frame-budgeted UIs** (144 Hz overlays, game HUDs, interactive inspectors, diagnostic panels, automated PNG rendering) rather than arbitrary web browsing:

- **Zero external browser runtime & zero JVM flags.** Runs on standard JDK 21 (`ByteBuffer` + `VarHandle`) with no preview APIs, no `java.lang.foreign`, no `--add-opens`, and no Chromium subprocesses.
- **Direct Java-to-DOM ownership.** Application code, declarative UI builders (`api.ui.UI`), and data bindings mutate a single live DOM directly in Java — no JSON serialization over IPC and no virtual-DOM tree diffing.
- **Frame-budget engineering.**
  - **Idle-frame skipping:** When nothing changes, frames cost nothing (no restyle, no relayout, no draw).
  - **Off-heap columnar arena & Bloom selector filtering:** Node flags and properties are packed for cache locality, and selector matching skips non-matching subtrees early.
  - **Retained incremental patching:** `:hover`, text edits, child slice insertions/removals, and scroll offsets update only affected layout/display-list slices rather than rebuilding the whole page.
- **Three interchangeable backends with pixel parity.** Run headlessly on CPU (no GPU or window server required in CI/servers), draw via OpenGL 3.2+ Core (`:core-gl`), or draw via Vulkan 1.1 (`:core-vulkan`). Both GPU backends share the same single-source SDF shader pipeline and fall back seamlessly to CPU rasterization for rare shapes.
- **Host GPU & custom element interop.** Share an existing OpenGL or Vulkan device/context (`HostGpu`) without breaking host ownership, and register custom tags/painters via standard `ServiceLoader` SPI (`api.spi`).

---

## Modular Architecture

The engine and its backends are cleanly split into five Gradle modules. `:core` does not depend on OpenGL, GLFW, Vulkan, or Minecraft; backends and window hosts are discovered dynamically via `ServiceLoader`.

| Gradle Module | Artifact (`com.nishiyu.enderframelib`) | Responsibility | Dependencies |
| :--- | :--- | :--- | :--- |
| **`:core`** | `enderframelib-core` | HTML5 tree builder, CSS cascade/animations, Flexbox/Grid/Block/Table/Multicol/Vertical layout, text shaping, `<scene-3d>` core, 64×64 tile-parallel CPU rasterizer, RHI, public API & SPI | Pure Java 21 + LWJGL core/FreeType, JOML, FastUtil, Gson, JNA, SLF4J |
| **`:core-gl`** | `enderframelib-core-gl` | OpenGL 3.2+ Core `GpuBackendProvider` (instanced SDF renderer + shared host GL context support) | `:core`, `lwjgl-opengl`, `lwjgl-glfw` |
| **`:core-vulkan`** | `enderframelib-core-vulkan` | Vulkan 1.1 `GpuBackendProvider` (headless, swapchain, or borrowed host `VkDevice`) | `:core`, `lwjgl-vulkan` |
| **`:core-glfw`** | `enderframelib-core-glfw` | Standalone GLFW `WindowHost` and `StandaloneViewer` desktop launcher | `:core`, `:core-gl`, `lwjgl-glfw` |
| **Root (Mod)** | `enderframelib` | **Optional Minecraft 1.21.1 (NeoForge) host:** GUI screens, HUDs, in-world block/hologram/item surfaces, `UIState` sync, `/enderframe` commands, `<mc-*>` tags, DevKit export | `:core*`, NeoForge 21.1.248+, JNA |

Inside `:core`, dependencies are strictly unidirectional across 7 architectural layers (`L1`–`L7`), mechanically enforced in CI by `LayerDependencyRatchetTest`, `PackageCycleRatchetTest`, `ModulePurityRatchetTest`, and `ApiBoundaryTest` (ensuring `api.*` never leaks internal implementation types).

---

## Core Engine Capabilities

- **Standards-Compliant HTML & Markdown:** WHATWG-style HTML tree construction (implied `html`/`head`/`body`, adoption agency for misnested formatting tags, fragment `innerHTML` context parsing, all 2,231 named character references) plus CommonMark/GFM subset Markdown (`data-markdown`, `<script type="text/markdown">`).
- **Rich CSS Engine:** CSS nesting (`&`), `@layer`, `@container` (size & `style()` queries), `@media`, `@supports`, `@property`, `@scope`, `@counter-style`, `@keyframes`, `transition`, CSS Color 4 (`oklab`, `oklch`, `lab`, `lch`, `color()`, `color-mix()`, relative colors), math/trigonometric functions (`calc()`, `min()`, `max()`, `clamp()`, `round()`, `sin()`, etc.), Flexbox, CSS Grid (including `subgrid`), Tables, Multicolumn, Anchor Positioning (`anchor()`, `position-area`, `position-try`), Top Layer (`<dialog>.showModal()`, `popover`), and Writing Modes (`vertical-rl`, `vertical-lr`, `sideways-*`).
- **Typography & Color Emoji:** OS outline font matching, `@font-face` (TTF/OTF), UAX #14 line breaking, UAX #9 BiDi reordering, and color emoji via `COLR v1`, `COLR v0/CPAL`, and `CBDT`/`CBLC`/`sbix` bitmap strikes.
- **Declarative Pure-Java UI DSL (`api.ui.UI`):** Hyperscript-style element builders (`div`, `button`, `p`, etc.) paired with fine-grained reactive `State<T>` / `RecordState` / `Ref` bindings that mutate live DOM nodes directly.
- **Embedded 3D (`<scene-3d>`):** Declarative and Java-driven 3D scenes (`api.scene3d.Scene3DController`) with GGX PBR lighting, HDR Bloom (RGBA16F + Dual-Kawase + ACES tone mapping), glTF/GLB binary loading, BVH raycasting (`pickScene3d`), and parity across OpenGL, Vulkan, and software 3D rasterization.
- **Input, Selection & IME:** Mouse, pointer, keyboard, wheel, focus management, text selection, `MutationObserver`, `ResizeObserver`, and multi-language IME composition (`compositionStart` / `compositionUpdate` / `compositionEnd` + `imeCursorRect()`).
- **Host Extensibility SPI (`api.spi`):** External hosts can register custom replaced elements (`ElementProvider`, `ElementPainter`, `ReplacedKind`), HTML tag templates (`TagTemplate`), i18n dictionaries, and custom resource resolvers per `WebViewSurface` without modifying `:core`.

---

## Optional Minecraft Integration (NeoForge Mod)

When installed in Minecraft 1.21.1 (NeoForge), the root module wires EnderFrameLib into the game as a complete mod and host showcase:

- **Unified Document Surfaces:** Render the exact same HTML/CSS as player GUI screens, persistent HUD overlays, multiblock in-world block screens (`EnderScreenBlockEntity`), floating holograms (including interactive billboard menus), or held-item displays (`EnderScreenItem`).
- **Headless Server State (`UIState`):** For machine/block UIs, keep HTML templates on the client (`assets/<modid>/enderframe_ui/`) and hold only typed key/value state on the server. Only changed keys are synced per server tick; viewers automatically close when the owner block is removed.
- **Hybrid Client/Server Execution:** Drive documents locally on the client via `EnderFrameClientAPI` with zero network overhead, or mix server-owned and client-owned subtrees (`data-owner="server|client"`) with declared event forwarding (`data-forward`, `onclick="server:..."`) and `data-channel` pub/sub.
- **Minecraft Custom Elements & Data Binding:** Built-in `<mc-slot>`, `<mc-slot-grid>`, `<mc-inventory>`, `<mc-bar>`, `<mc-energy>`, `<mc-tank>`, `<mc-entity>`, `<mc-scene>`, `<mc-fluid>`, `<img src="item:...">`, game `lang/*.json` i18n (`{{i18n:key}}`), and reactive `data-*` attributes.
- **In-Game Tooling:** `/enderframe` commands for live opening/patching/publishing, plus `/enderframe devkit` to export a standalone browser preview kit (sharing the exact `ua.css` and built-in themes) so UI authors can iterate in Chrome/Edge/VS Code without launching the game.

---

## Scope, Performance, and Fidelity

### Scope and Design Boundaries

| Included in Scope | Deliberately Out of Scope |
| :--- | :--- |
| In-process HTML/CSS UI rendering for Java apps, tools, and games | General-purpose web browsing or untrusted web navigation |
| WHATWG DOM & UI Events driven from Java | Embedded JavaScript engines (V8 / SpiderMonkey) |
| Reactive Java UI DSL & host SPI (`api.spi`) | Multi-process sandboxing / cross-process IPC |
| Headless CPU + OpenGL / Vulkan GPU backends with pixel parity | Bundled general media/WebRTC stacks (video in mod is optional/platform-specific) |

### Performance Highlights & Honest Caveats

Numbers below come from [BENCHMARK.md](BENCHMARK.md) (`:core` real-frame measurements on Windows, JDK 21, 24 hardware threads, 800×600, warm JVM, Arial font, median of 3 runs, ±10–15% run-to-run variance; headless Chrome 157 measured on the same documents):

- **Idle frames:** Skipped completely (no restyle, layout, or draw).
- **Incremental updates (OpenGL, real frame):** `scroll` takes `0.007 ms` on the showcase page and `0.09 ms` on a 2,000-row list (Chrome: `0.50 ms`); `:hover` toggle takes `0.10 ms` (Chrome: `1.24 ms`); text update takes `0.17 ms` (Chrome: `1.07 ms`); opening the showcase page in a warm JVM takes `4.19 ms` (Chrome: `5.30 ms`).
- **Where `:core` is not faster than Chrome:**
  - **Row insertion:** Inserting/removing a row in a 2,000-row list takes `1.44 ms` (Chrome: `1.34 ms`).
  - **Core-count sensitivity on huge relayouts:** Restyling font size across 2,000 distinct rows takes `5.72 ms` with default workers and `6.71 ms` on 4 workers (Chrome: `39.35 ms`), rising to `7.32 ms` on 3 workers and `17.20 ms` on 1 worker. Configure workers with `EnderEngine.workerThreads(n)` or `-Denderframe.threads=N`.
  - **Cold start:** `510–594 ms` process-to-first-frame on JDK 21 vs ~`300 ms` for Chrome (on JDK 24+, optional AOT cache `JEP 483` cuts cold start roughly in half; see [core/README.md](core/README.md)).
  - **Retained heap:** Roughly 1.35–1.57× Chrome's on 2,000-row documents.
  - **Continuous animations:** Pages that animate every frame bypass incremental caching (`voidwave.html` runs at 0.57–0.79× of Chrome's frame time).

### Browser Fidelity Status

Layout and rendering differences against real browsers are tracked mechanically by `WebFidelityTest` and `WptReftestRatchetTest` (see [FIDELITY.md](FIDELITY.md)):
- On the tracked 260-pair WPT reftest subset, **160 pass (62%)**: Flexbox 106/120, Grid 23/80, Text 31/60.

---

## Using the Standalone Engine (`:core`)

For the full engine guide, AOT cache instructions, and verified runnable snippets, see [core/README.md](core/README.md) and [docs/standalone-viewer.md](docs/standalone-viewer.md).

### Publishing & Gradle Dependencies

> Artifacts are not yet on Maven Central; publish locally from source first.

```bash
./gradlew publishMavenJavaPublicationToLocalRepoRepository   # publishes :core, :core-gl, :core-vulkan, :core-glfw to ./repo
./gradlew :core:verifyPublishedModules                       # verifies POMs & runs scripts/consumer-check against ./repo
```

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("path/to/EnderFrameLib/repo") }
}

val lwjglVersion = "3.3.3"
val lwjglNatives = "natives-windows" // or natives-linux, natives-macos, natives-macos-arm64

dependencies {
    // CPU software rendering only (headless / PNG / custom PixelSink)
    implementation("com.nishiyu.enderframelib:enderframelib-core:2.0.0")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-freetype::$lwjglNatives")

    // Optional GPU rendering / GLFW desktop window
    // implementation("com.nishiyu.enderframelib:enderframelib-core-gl:2.0.0")     // + lwjgl-opengl & lwjgl-glfw natives
    // implementation("com.nishiyu.enderframelib:enderframelib-core-vulkan:2.0.0") // + lwjgl-vulkan natives on macOS
    // implementation("com.nishiyu.enderframelib:enderframelib-core-glfw:2.0.0")   // GLFW window + StandaloneViewer
}
```

### Quick Examples

**1. Headless rendering to PNG (no GPU or display server required):**

```java
import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.WebViewSurface;
import java.nio.file.Path;

String html = """
    <style>
      body { margin: 0; padding: 24px; font-family: sans-serif; background: #0f172a; color: white; }
      .card { background: #1e293b; border-radius: 12px; padding: 20px; }
      h1 { margin: 0 0 8px; color: #38bdf8; }
    </style>
    <div class="card">
      <h1>Hello EnderFrameLib!</h1>
      <p>Pure Java HTML/CSS rendering.</p>
    </div>
    """;

try (WebViewSurface surface = EnderEngine.html(html).size(600, 300).build()) {
    surface.frame(0, true);
    surface.writePng(Path.of("output.png"));
}
```

**2. Embedding into Swing, JavaFX, or a custom renderer (`PixelSink`) / GPU (`Target.GPU`):**

```java
// Software pixel buffer callback (ARGB int[])
EnderEngine.html(html)
    .size(800, 600)
    .sink((int[] pixels, int width, int height) -> { /* upload to host texture/image */ })
    .build();

// Or GPU offscreen / shared host device (requires :core-gl or :core-vulkan)
try (WebViewSurface gpuSurface = EnderEngine.html(html)
        .size(800, 600)
        .target(EnderEngine.Target.GPU)
        .gpu(GpuApi.AUTO) // GL, VULKAN, or AUTO; or .hostDevice(HostGpu.openGl(...) / HostGpu.vulkan(...))
        .build()) {
    gpuSurface.frame(0, true);
    gpuSurface.texture().ifPresent(tex -> { /* native texture handle without pixel readback */ });
}
```

**3. Standalone desktop window (requires `:core-glfw`) or CLI viewer:**

```java
// Blocks until closed; handles input, timers, and animations
EnderEngine.html(html).size(1024, 768).openWindow();
```

```bash
./gradlew :core:runViewer --args="src/main/resources/data/enderframelib/enderframe_ui/voidwave.html 1280 800"
```

**4. Pure-Java reactive UI DSL (`api.ui.UI`):**

```java
import static com.nishiyu.enderframelib.api.ui.UI.*;
import com.nishiyu.enderframelib.api.ui.State;

State<Integer> count = State.of(0);
var app = div(cls("counter-card"),
    h1("Counter"),
    p("Count: ", count),
    button(cls("btn"), onClick(e -> count.update(c -> c + 1)), "Increment")
);
mount(rootElement, app);
```

---

## Using the Minecraft 1.21.1 Integration

> This section applies only if you are using EnderFrameLib as a **Minecraft 1.21.1 (NeoForge) mod**. Detailed guides live in [docs/getting-started.md](docs/getting-started.md), [docs/headless-state.md](docs/headless-state.md), [docs/client-and-hybrid.md](docs/client-and-hybrid.md), and [docs/commands-and-devkit.md](docs/commands-and-devkit.md).

### Mod Installation

Publish to `./repo` with `./gradlew publish` (requires Minecraft 1.21.1, NeoForge 21.1.248+, Java 21):

```gradle
repositories {
    maven { url = "file:///path/to/enderframelib/repo" }
}

dependencies {
    implementation "com.nishiyu.enderframelib:enderframelib:2.0.0"
}
```

Declare the dependency in `neoforge.mods.toml`:

```toml
[[dependencies.yourmod]]
modId = "enderframelib"
type = "required"
versionRange = "[2.0.0,)"
ordering = "AFTER"
side = "BOTH"
```

### Mod Entry Points Overview

1. **Server-driven HTML & HUDs (`EnderFrameAPI`)** — Open ad-hoc HTML strings (`Ui.html(...)`) or datapack UIs (`Ui.datapack("mymod:menu")`), attach persistent HUDs (`hud(...)`), and push live updates via pub/sub channels (`publish(...)`) with ACL checks (`addSubscriptionCheck(...)`).
2. **Machine UIs & In-World Surfaces with Headless `UIState` (Recommended)** — Ship the HTML template on the client (`assets/<modid>/enderframe_ui/<name>.html`), hold only key/value `UIState` on the server (`EnderFrameAPI.get().stateOf(blockEntity, "mymod:crusher")`), and bind values declaratively with `data-bind="key"` / `{{key}}` / `data-if`. The exact same model powers multiblock `EnderScreenBlockEntity` screens, interactive billboards (`Surfaces.hologram(...)`), and `EnderScreenItem` displays.
3. **Client-Driven UI & Hybrid Ownership (`EnderFrameClientAPI`)** — Open client-only documents with `ClientSession`, mutate the live `Document` directly, schedule `requestAnimationFrame` / `setTimeout` / `setInterval`, and combine client-owned and server-owned subtrees (`data-owner="server|client"`, `data-forward`, `onclick="server:name"`).
4. **In-Game Commands & Browser DevKit** — Use `/enderframe open`, `/enderframe hud`, `/enderframe patch`, `/enderframe publish`, `/enderframe desktop`, and `/enderframe devkit` (exports `<gameDir>/enderframe_devkit/` for live authoring in a regular browser). See [COMMAND_GUIDE.md](COMMAND_GUIDE.md).

---

## Supported HTML, CSS, and Extensions

**HTML Structure & Content**
- **Structure:** `html` `head` `body` `div` `section` `main` `header` `footer` `article` `nav` `aside` `span` `template` (inert `DocumentFragment`)
- **Text:** `h1`–`h6` `p` `br` `hr` `b` `strong` `i` `em` `s` `strike` `del` `u` `ins` `code` `kbd` `pre` `blockquote` `label` `output`
- **Lists & Tables:** `ul` `ol` (`start`) `li` (`value`) `table` `caption` `thead` `tbody` `tfoot` `tr` `th` `td` (`colspan`, `rowspan`) `colgroup` `col` (`span`)
- **Forms:** `form` `fieldset` `legend` `button` `input` (`text`, `number`, `checkbox`, `radio`, `range`, `color`) `select` `option` `textarea` `progress` `meter`
- **Media, Grouping & 3D:** `img` `canvas` `video` `svg` `a` `details` `summary` `dialog` `figure` `figcaption` `<scene-3d>`
- **Markdown:** CommonMark / GFM subset via `data-markdown`, `<script type="text/markdown">`, or `.md` files (headings, emphasis, inline/fenced code, blockquotes, GitHub alerts `> [!NOTE]`, flat lists, task lists, GFM tables, images, links, `<hr>`).

**CSS**
- **Selectors & Nesting:** Type, `.class`, `#id`, `*`, descendant, `>`, `+`, `~`, attribute selectors (`=`, `^=`, `$=`, `*=`, `~=`, `|=`, `i`/`s`), selector lists, CSS escapes, CSS nesting (`&`), and browser-compatible invalid-selector dropping.
- **Pseudo-classes & Pseudo-elements:** `:hover` `:active` `:focus` `:focus-visible` `:focus-within` `:checked` `:disabled` `:enabled` `:indeterminate` `:open` `:required` `:optional` `:read-only` `:read-write` `:placeholder-shown` `:valid` `:invalid` `:in-range` `:out-of-range` `:link` `:any-link` `:visited` (never matches) `:target` `:defined` `:lang()` `:dir()` `:first-child` `:last-child` `:only-child` `:first-of-type` `:last-of-type` `:only-of-type` `:nth-child()` `:nth-last-child()` `:nth-of-type()` `:nth-last-of-type()` (`An+B`, `odd`, `even`, `of S`) `:empty` `:root` `:scope` `:not()` `:is()` `:where()` `:has()`; `::before` `::after` `::first-line` `::marker` `::placeholder` `::selection` `::backdrop`.
- **Layout & Box Model:** `display` (`block`, `inline`, `inline-block`, `flow-root`, `flex`, `inline-flex`, `grid`, `inline-grid`, `table`, `inline-table`, `table-*`, `list-item`, `contents`, `none`, two-keyword forms), `position` (`static`, `relative`, `absolute`, `fixed`, `sticky`), `float`/`clear`, Flexbox, CSS Grid (`subgrid`, `minmax()`, `repeat()`, `auto-fill`/`auto-fit`, named lines/areas), Tables, Multicolumn, Anchor Positioning (`anchor-name`, `position-anchor`, `anchor()`, `anchor-size()`, `position-area`, `position-try`), `content-visibility: auto`, and Writing Modes (`writing-mode: horizontal-tb | vertical-rl | vertical-lr | sideways-rl | sideways-lr`, `text-orientation`, logical properties).
- **Typography, Color & Paint:** System/custom fonts, UAX #14 wrapping, UAX #9 BiDi, `COLR v0/v1` and `CBDT`/`CBLC`/`sbix` color emoji, `filter` / `backdrop-filter`, `mix-blend-mode` (16 modes + `plus-lighter`), `isolation`, `clip-path`, `mask-*`, layered `background` + linear/radial/conic gradients (`in <colorspace>`), `box-shadow`, 2D/projected `transform`, `transition`, `@keyframes`, CSS Color 4 (`rgb`, `hsl`, `hwb`, `lab`, `lch`, `oklab`, `oklch`, `color()`, `color-mix()`, relative colors), math/trig functions, `@media`, `@supports`, `@container`, `@layer`, `@property`, `@scope`, `@counter-style`, `@import`, `@font-face`.
- **Built-in Stylesheets:** `enderframelib:ua.css` (HTML standard defaults) and themes `enderframelib:dark.css`, `enderframelib:vanilla.css`, `enderframelib:brass.css`.

**Minecraft-Only Extensions (Mod Host)**
- **Custom Elements:** `<mc-slot>`, `<mc-slot-grid>`, `<mc-inventory>`, `<mc-bar>`, `<mc-energy>`, `<mc-tank>`, `<mc-entity>`, `<mc-scene>`, `<mc-fluid>`, `<mc-markdown>`, `<img src="item:...">`, plus `var(--mc-*)` chat palette variables and `minecraft:default` / `minecraft:uniform` / `minecraft:alt` font families.
- **Reactive Attributes & i18n:** `data-model`, `data-bind`, `data-if` (`mc-if`), `data-show`, `data-for`, `data-key`, `data-row-key`, `data-class`, `data-channel`, `data-list`, `data-i18n` (`{{i18n:key}}`), `data-item`, `data-tooltip`, `data-sound`, `data-markdown`, `data-syntax`, `data-hud`, `data-tracker`, `data-dropzone`, `data-slot`, `data-zoom`, `data-pan-zoom`, `data-owner`, `data-forward`, `data-forward-action`.

---

## Migration & Breaking Changes

Compared to pre-1.0 / early Minecraft-only builds, the engine now follows standard browser defaults:

- **System fonts & CSS text metrics:** Documents default to system fonts and standard CSS `font-size` / `line-height` metrics (use `font-family: minecraft:default` for Minecraft's bitmap font).
- **Standard UA stylesheet & HTML parsing:** `ua.css` uses browser defaults (`body { margin: 8px }`, transparent root background unless styled, standard `<style>`/`<script>` DOM retention, fragment implied `html`/`head`/`body`). Built-in themes (`dark.css`, `vanilla.css`, `brass.css`) carry the compact dark styling.
- **Standard box tree, formatting contexts & stacking:** Anonymous block wrapping, true CSS tables (`<table>` is no longer a flex column), CSS 2.2 Appendix E paint order, sub-pixel layout snapped at paint time, and standard CSS color names (`red` = `#ff0000`, `#rrggbbaa` RGBA order).
- **Live DOM & Headless State:** Virtual-DOM diffing and `setForwardEventsToServer` were replaced by single-DOM mutation records, declared forwarding (`data-forward` / `server:`), and `UIState` for machine/block UIs.

Full details and upgrade steps are in [docs/migration.md](docs/migration.md).

---

## Not Supported

Known gaps are documented here so you do not have to discover them by debugging; code-level `LIMITATION` markers and browser-measured differences are tracked in [FIDELITY.md](FIDELITY.md).

- **Scripting & `MutationObserver` timing.** No JavaScript anywhere. Behaviour is driven from Java (or server-side / reactive `data-*` attributes in the mod). `onclick` is read only as an action name in the mod host, never evaluated as JS. A Java-side `MutationObserver` API is available, with notifications batched and dispatched on tick/frame or manual flush rather than JS microtasks.
- **HTML parsing in full.** Formatting elements are reconstructed by the adoption agency algorithm, but SVG and MathML elements are parsed without XML namespaces (tag and attribute names stay lower-case, e.g., `viewBox` becomes `viewbox`). Rules from `<style>` and an SVG's own `<style>` apply to presentation properties (`fill`, `stroke`, `opacity`, …), not `:hover`, pseudo-elements, `@container`, or `var()`.
- **Nested lists in Markdown.** List parsing is flat; an indented sub-list becomes a sibling `<li>`. Write nested lists as HTML.
- **Tables in full.** `border-collapse: collapse` merges cell and table borders by width only (not by `border-style` precedence) and ignores row, row-group, and column borders. A collapsed column keeps its `border-spacing`.
- **Floats in full.** A line next to a float takes its available width from the float area at the top of the line, and a float inside an inline box that does not fit moves to the next line rather than an earlier one.
- **Multi-column layout in full.** A block with a border, padding, or background is not split across columns, and neither is an empty block or image, so a tall one overflows its column. No `break-before` / `break-after`, `orphans` / `widows`, `direction: rtl` column order, or floats inside columns.
- **Box-tree generation in full.** A block inside an inline box does not split the inline box, and text directly inside a flex or grid container is not wrapped in an anonymous item.
- **List markers.** `list-style-image: url(...)` shows the image followed by a space; a failed load does not fall back to `list-style-type`. `@counter-style` does not support anonymous `symbols()` or image symbols (`url(...)`); unknown style names fall back to `decimal`.
- **Flexbox in full.** `align-items: baseline` uses the first baseline of row items only. In a column, an item's automatic minimum height is its specified height if present, otherwise content height. Lines break by hypothetical sizes. In a flex container without a definite height, percentage heights resolve against the container's percentage basis instead of behaving as `auto`.
- **Grid in full.** Simplified track sizing: an item spanning multiple content-sized tracks distributes its size evenly across them. `subgrid` is supported, but there are no implicit tracks before the explicit grid and no `baseline` alignment.
- **Compositing on in-world Minecraft surfaces.** Screen-space surfaces (GUI screens, HUDs, standalone windows) composite `opacity < 1`, `filter`, `backdrop-filter`, `mix-blend-mode`, `isolation`, `clip-path`, `mask-image`, and rotated rounded clips via offscreen FBO layers (`url()` SVG references in `filter`/`clip-path` and `rect()`/`xywh()` are not read). On in-world surfaces (blocks, holograms, held items), offscreen FBO compositing is omitted to preserve world depth testing: `opacity` is applied per painted part, `overflow` clipping runs in each element's local transformed space, and flex/grid items paint in document order rather than `order`-modified order.
- **Borders and outlines in full.** On boxes with `border-radius`, `solid`, `dashed`, and `dotted` borders follow the rounded/elliptical path, while `double`/`groove`/`ridge`/`inset`/`outset` draw solid; where adjacent sides of different colors meet at a rounded corner, color transitions at side width rather than along the diagonal. Outlines do not follow `border-radius` or inline fragment bounds. `inset`/`outset`/`groove`/`ridge` shading is approximated.
- **Backgrounds in full.** Multiple layers, `url()` images, and `background-size / position / repeat / clip / origin` work on block boxes; inline boxes paint background colors and borders per line fragment without background images. Gradients in non-sRGB color spaces are approximated with 32 linear sRGB segments per stop pair and require the SDF shader. `background-attachment` is always `scroll`.
- **Box shadows in full.** Blurred `box-shadow` uses a stepped Gaussian-like approximation in the SDF shader (outer blurred shadows cut out the border box; unblurred outer shadows still paint beneath translucent boxes).
- **Native form controls.** Controls are rendered by EnderFrameLib (styled after Chromium's light theme) rather than OS native widgets.
- **Glyph-level fidelity.** Text uses system or `@font-face` fonts without OpenType ligatures; `line-height: normal` ignores fallback font metrics; `font-weight: 500` without a 500 face picks Regular per spec. Line breaking follows UAX #14; BiDi text reorders per fragment (UAX #9) without Arabic contextual shaping or cross-element BiDi reordering. No `contenteditable`; color emoji ignore text `color`.
- **Media features that need a display.** `color-gamut` is unknown (`false`); `prefers-color-scheme` is `light`; `prefers-reduced-motion` is `no-preference`; `hover` / `pointer` report a mouse; `prefers-contrast` / `forced-colors` report no preference.
- **Pseudo-elements & selectors beyond supported set.** Unsupported pseudo-elements (`::first-letter`, `::file-selector-button`, etc.) parse for specificity but do not match; unsupported pseudo-classes (`:user-valid`, `:autofill`, `:fullscreen`, etc.) invalidate the selector list per CSS spec; no XML namespaces (`svg|rect`).
- **Glyph-relative units & elliptical corners.** `ch`/`ex` measure from the element's font (falling back to `0.5em` without a font); `ic` is `1em` and `cap` is a fixed fraction of font size. Elliptical `border-radius` is drawn as true elliptical arcs on SDF backgrounds, borders, shadows, and clips, while images and non-SDF elements approximate with the smaller radius.
- **Transforms in full.** 2D matrix plus `skew*` shear are composed; 3D rotations (`rotateX`/`rotateY`/`rotate3d`) project onto the 2D plane without `perspective()` or `translateZ()` depth.
- **Clipping under rotation or scaling.** Ancestor clips are chained into local space. When an item is rotated or scaled relative to a clipping ancestor, the SDF shader applies one rounded clip exactly and further axis-aligned clips via hardware scissor (skipped if the ancestor is rotated); non-SDF items (text, images) use scissor clipping without rounded corners. In-world surfaces only apply translation-only clips.
- **Video (Minecraft mod host).** Uses the bundled `lunex_video.dll` native decoder (Windows-only today). YouTube URLs require explicit opt-in (`allowExternalMedia` + `allowYtDlp`); when enabled, the official `yt-dlp` release is downloaded from GitHub, verified via `SHA2-256SUMS`, and invoked without self-updating (see [NOTICE](NOTICE)).

---

## Building from Source & Project Layout

```bash
./gradlew build test                                        # compile all modules + run CI test suite
./gradlew :core:quickTest                                   # fast :core check (~2 min, skips slow & timing tags)
./gradlew :core:test                                        # full Minecraft-free :core suite
./gradlew :core:costGate                                    # allocation & fast-path regression gate
./gradlew :core:verifyPublishedModules                      # publish engine modules to ./repo & verify standalone consumer
./gradlew :core:runViewer --args="page.html 1280 800"       # launch standalone GLFW viewer
./gradlew runClient                                         # launch Minecraft 1.21.1 client with the mod
./gradlew runGameTestServer                                 # run server-side lifecycle GameTests
```

```text
core/         (:core) Pure-Java 21 HTML/CSS engine, layout, DOM/events, UI DSL, <scene-3d>, CPU rasterizer, RHI, SPI
core-gl/      (:core-gl) OpenGL 3.2+ Core GPU backend
core-vulkan/  (:core-vulkan) Vulkan 1.1 GPU backend
core-glfw/    (:core-glfw) GLFW WindowHost & StandaloneViewer
src/          (Root NeoForge Mod) Optional Minecraft 1.21.1 integration:
                api/      Mod-facing API (EnderFrameAPI, EnderFrameClientAPI, UIState, EnderScreenBlockEntity)
                common/   Shared resource loading, <mc-*> custom elements, DevKit export
                server/   Headless UIState, HTML sessions, pub/sub, commands, datapacks, ACL
                client/   Minecraft screen/HUD/world rendering, input synthesis, media
                network/  State delta, DOM mutation, action, and pub/sub payloads
docs/         Task-oriented guides for both standalone engine & mod usage
```

---

## Documentation Map

- **Standalone Engine (`:core`):**
  - [core/README.md](core/README.md) — Standalone engine installation, Gradle setup, AOT cache, and compilable Java quick starts (English).
  - [docs/standalone-viewer.md](docs/standalone-viewer.md) — Running HTML in a GLFW window or headless PNG without Minecraft (Japanese).
  - [docs/build-and-run-commands.md](docs/build-and-run-commands.md) — Full Gradle command reference for builds, tests, demos, and viewers (Japanese).
- **Minecraft Integration (`docs/`):**
  - [docs/README.md](docs/README.md) — Documentation index and reading order (Japanese).
  - [docs/getting-started.md](docs/getting-started.md) — Mod installation and first server-driven UI (English).
  - [docs/headless-state.md](docs/headless-state.md) — Machine UIs and in-world screens with `UIState` (English).
  - [docs/client-and-hybrid.md](docs/client-and-hybrid.md) — Client-driven UIs and hybrid server/client DOM ownership (English).
  - [docs/commands-and-devkit.md](docs/commands-and-devkit.md) & [COMMAND_GUIDE.md](COMMAND_GUIDE.md) — `/enderframe` commands and DevKit browser preview.
  - [docs/migration.md](docs/migration.md) — Detailed migration notes and breaking changes (English).
- **Engineering & Quality:**
  - [ARCHITECTURE.md](ARCHITECTURE.md) — Module layout, 7-layer dependency rules, and architectural ratchets (Japanese).
  - [BENCHMARK.md](BENCHMARK.md) — Cross-engine benchmark methodology and measured numbers (Japanese).
  - [FIDELITY.md](FIDELITY.md) — Browser-measured fidelity corpus and divergence ledger (Japanese).
  - [CONTRIBUTING.md](CONTRIBUTING.md) — Language, comment, limitation, and testing conventions (Japanese).

---

## Contributing & License

See [CONTRIBUTING.md](CONTRIBUTING.md) for code conventions, test suites, and how known limitations are recorded across code, `FIDELITY.md`, and this README.

**License:** `LGPL-3.0-only`. See [LICENSE](LICENSE) and [LICENSE.GPL-3.0.txt](LICENSE.GPL-3.0.txt).

Third-party components — the bundled `lunex_video` native decoder (excluded from the LGPL grant), on-demand `yt-dlp`, JNA, and NeoForge MDK template files — are attributed in [NOTICE](NOTICE).
