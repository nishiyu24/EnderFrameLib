# EnderFrameLib `:core` — High-Performance Pure-Java HTML/CSS Engine

[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![License: LGPL-3.0](https://img.shields.io/badge/License-LGPL_3.0-green.svg)](https://www.gnu.org/licenses/lgpl-3.0.html)

EnderFrameLib `:core` is an independent, lightweight HTML/CSS UI rendering engine written in pure Java 21, built for the per-frame budgets of in-process UIs (game HUDs, tool panels).

It combines off-heap columns on direct `ByteBuffer` + `VarHandle` (standard JDK 21 only: no preview APIs, no JVM flags, no `java.lang.foreign`), Bloom-filter selector rejection, retained incremental patching, an instanced SDF GPU shader behind a thin API-neutral device layer (RHI), and a 64×64 tile-parallel CPU software rasterizer. It has **zero dependencies on Minecraft or game frameworks** and can be embedded into desktop apps, game engines, tools, or run headlessly for automated image generation.

---

## ⚡ Highlights

- **Built for a 144 Hz frame budget (6.94 ms)**. These are measured values on one machine, not guarantees (see *Performance notes* below):
  - Idle frames are skipped entirely: nothing is restyled, laid out, or drawn.
  - `scroll` (OpenGL, real frame): `0.007 ms` on the showcase page, `0.09 ms` on a 2,000-row list (headless Chrome 157: `0.50 ms`)
  - `:hover` toggle (OpenGL, real frame, showcase page): `0.10 ms` (Chrome: `1.24 ms`)
  - Text update (showcase page): `0.17 ms` (Chrome: `1.07 ms`)
  - Opening the showcase page in a warm JVM: `4.19 ms` (Chrome: `5.30 ms`)
- **Three interchangeable backends with pixel parity**:
  - **CPU** (in `:core`): 64×64 multithreaded tile rasterizer. No GPU and no window needed; works in headless, CI, and server environments.
  - **OpenGL 3.2+ Core** (`:core-gl`) and **Vulkan 1.1** (`:core-vulkan`): an SDF shader batches rounded rectangles, borders, shadows, gradients, and glyphs into instanced draws. Shapes the GPU path refuses fall back to a CPU layer.
  - `:core` itself has no compile or runtime dependency on OpenGL, GLFW, or Vulkan. GPU backends and the GLFW window (`:core-glfw`) are discovered through `ServiceLoader`.
- **A subset of web standards** (this is not a browser; see [FIDELITY.md](../FIDELITY.md) for known divergences):
  - Layout: Block, Inline, Flexbox, Grid, Table, Multicolumn, and Container Queries (`@container`). Coverage is partial: on a 260-pair subset of WPT reftests, **136 pass (52%)**: flexbox 94/120, grid 21/80, text 21/60 (tracked by `WptReftestRatchetTest`).
  - Colors: RGB, HSL, `oklch`, `display-p3`, and gradients.
  - CSS `@keyframes` and `transition` animations.
  - WHATWG DOM interfaces and UI Events (Mouse, Pointer, Keyboard, Wheel, Focus, and IME Composition) for Java callers. There is no JavaScript engine.
- **Architectural Rigor**:
  - 7-layer unidirectional architecture (L1–L7) mechanically enforced by `LayerDependencyRatchetTest` (0 upward dependencies).
  - The public API (`com.nishiyu.enderframelib.api.*`) does not reference implementation packages (`ApiBoundaryTest`).

### Performance notes

All numbers come from [BENCHMARK.md](../BENCHMARK.md) (section "`:core` の実フレーム計測"): one development machine (Windows, JDK 21, 24 hardware threads, 800×600, warm JVM), median of 3 runs, ±10–15% run-to-run variation. Both engines were forced to the same font (Arial). Chrome figures are per-frame CPU time from traces of headless Chrome 157 on the same documents. The comparison covers this engine's scope only (no JavaScript, single process, a subset of CSS), so it says how long the same change to the same document takes, not which engine is better overall.

Where `:core` is not ahead:

- **Row insertion is on par with Chrome**: inserting and removing one row in a 2,000-row list takes `1.44 ms` (Chrome: `1.34 ms`).
- **Large relayouts depend on core count.** A font-size change across 2,000 rows that each have a different design takes `8.20 ms` with a real font, over the 144 Hz frame (Chrome: `39.35 ms`). With the built-in test font it takes `6.04 ms` on 23 worker threads, `8.03 ms` on 4, and `17.45 ms` on 1. Hosts that share cores with a game should expect the lower rows; set the worker count with `EnderEngine.workerThreads(n)` or `-Denderframe.threads=N`.
- **Cold start** (process start → first frame): `510–594 ms`, against about `300 ms` for Chrome (see the AOT cache section below).
- **Retained heap**: about 1.35–1.57x Chrome's on 2,000-row documents.
- **Documents with constant animation** fall off the incremental paths and rebuild every frame; the bundled `voidwave.html` demo runs at 0.57–0.79x of Chrome's frame time instead of the 0.04–0.17x seen on the showcase page.

---

## 📦 Installation

> **Not yet on Maven Central.** Until it is published, build from source. Only `:core` has a Maven publication today; `:core-gl`, `:core-vulkan`, and `:core-glfw` are consumed as Gradle projects (composite build or source dependency).

```
./gradlew :core:publishMavenJavaPublicationToLocalRepoRepository   # writes to <repo root>/repo
```

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("path/to/EnderFrameLib/repo") }
}

val lwjglVersion = "3.3.3"
val lwjglNatives = "natives-windows" // or natives-linux, natives-macos, natives-macos-arm64, ...

dependencies {
    // CPU rendering only: this is all you need
    implementation("com.nishiyu.enderframelib:enderframelib-core:2.0.0")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-freetype::$lwjglNatives")

    // GPU rendering or a window: add the modules you use (Gradle projects for now, see above)
    // implementation(project(":core-gl"))     + runtimeOnly lwjgl-opengl and lwjgl-glfw natives
    // implementation(project(":core-vulkan")) + runtimeOnly lwjgl-vulkan natives on macOS
    // implementation(project(":core-glfw"))   window + StandaloneViewer (brings :core-gl)
}
```

Without a GPU module on the classpath, `Target.SOFTWARE` works and `Target.GPU` fails at `build()` with a message naming the module to add.

---

## 🚀 Quick Start

### 1. Headless Rendering to PNG (No GPU required)

```java
import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.WebViewSurface;
import java.nio.file.Path;

public class HeadlessExample {
    public static void main(String[] args) throws Exception {
        String html = """
            <style>
              body { margin: 0; padding: 24px; font-family: sans-serif; background: #0f172a; color: white; }
              .card { background: #1e293b; border-radius: 12px; padding: 20px; box-shadow: 0 8px 16px rgba(0,0,0,0.3); }
              h1 { margin: 0 0 8px; color: #38bdf8; }
            </style>
            <div class="card">
              <h1>Hello EnderFrameLib!</h1>
              <p>Pure Java HTML/CSS rendering in a few lines of code.</p>
            </div>
            """;

        try (WebViewSurface surface = EnderEngine.html(html).size(600, 300).build()) {
            surface.frame(0, true);
            surface.writePng(Path.of("output.png"));
        }
    }
}
```

### 2. Custom Host Embedding (Swing / JavaFX / Game Engines)

```java
EnderEngine.html(html)
    .size(800, 600)
    .sink((int[] pixels, int width, int height) -> {
        // Upload pixels (ARGB) to your game engine texture, Swing BufferedImage, etc.
    })
    .build();
```

### 3. GPU Offscreen (requires `:core-gl` or `:core-vulkan`)

```java
import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.GpuApi;

try (WebViewSurface surface = EnderEngine.html(html).size(800, 600)
        .target(EnderEngine.Target.GPU).gpu(GpuApi.AUTO) // GL, VULKAN, or AUTO
        .build()) {
    surface.frame(0, true);
    surface.texture().ifPresent(tex -> { /* native texture handle, no pixel copy */ });
}
```

### 4. Standalone Desktop Window (requires `:core-glfw`)

```java
// Blocks until the window is closed. Input and the frame loop are built in.
EnderEngine.html(html).size(1024, 768).openWindow();
```

To load a file and attach Java callbacks, use `StandaloneViewer` from `:core-glfw`:

```java
import com.nishiyu.enderframelib.common.StandaloneViewer;

StandaloneViewer.launch(new String[]{"path/to/page.html", "1024", "768"}, view -> {
    // Optional: attach Java callbacks or inspect the live DOM
});
```

---

## ⚛️ Declarative UI DSL in Pure Java

`com.nishiyu.enderframelib.api.ui.UI` is a small hyperscript-style builder with reactive `State` bindings. It writes to the live DOM directly: there is no virtual-DOM diffing and no hook lifecycle.

```java
import static com.nishiyu.enderframelib.api.ui.UI.*;
import com.nishiyu.enderframelib.api.ui.State;

// 1. Reactive state
State<Integer> count = State.of(0);

// 2. Declarative element tree
var app = div(cls("counter-card"),
    h1("EnderFrameLib UI DSL"),
    p("Count: ", count), // the text node follows the state
    button(cls("btn primary"), onClick(e -> count.update(c -> c + 1)), "Increment"),
    button(cls("btn"), onClick(e -> count.set(0)), "Reset")
);

// 3. Mount into a DOM node
mount(rootElement, app);
```

### Highlights:
- **Element builders**: `div(...)`, `span(...)`, `h1(...)`, `p(...)`, `button(...)`, and more, with props `cls(...)`, `style(...)`, `sx(...)`, `attr(...)`, `on(...)`.
- **Reactive bindings**: `State.of(initial)`, `State.derive(...)`, `state.onChange(...)`, `bindText`, `bindValue`, and the reactive slots `watch(state, renderer)` / `each(listState, itemRenderer)`. `Ref` with `ref(...)` gives typed element references.
- **DOM helpers**: `$(selector, root)`, `$$(selector, root)`, `byId(id, root)`, and `classList(el)`.
- **Batched insertion**: `mount` builds into a `DocumentFragment` and inserts once.

---

## ⌨️ Input & IME (Multi-language / Japanese Composition)

`WebViewSurface` takes pointer, keyboard, and IME composition input:

```java
// Focus & standard inputs
surface.mouseMove(x, y);
surface.mouseButton(MouseButton.LEFT, true);
surface.charTyped('A');

// IME Composition (Japanese / Asian text input)
surface.compositionStart();
surface.compositionUpdate("にほんご", 2);

// Position OS IME candidate window right below the caret
float[] cursorRect = surface.imeCursorRect(); // [x, y, width, height] in CSS pixels

surface.compositionEnd("日本語");
```

---

## 🎯 Scope and Design Boundaries

EnderFrameLib `:core` is purpose-built as an **in-process embedded UI engine** (e.g., game HUDs, desktop tool panels, diagnostics dashboards, reactive forms), NOT a full-featured web browser.

| Supported | Deliberately Excluded / Out of Scope |
| :--- | :--- |
| ✅ Flexbox, Grid, Blocks, Inline formatting (subset; see the WPT numbers above) | ❌ JavaScript engines (V8 / SpiderMonkey) |
| ✅ WHATWG DOM mutation & UI Events from Java (subset) | ❌ Bundled video codecs & WebRTC |
| ✅ CSS animations, transforms, opacity, shadows | ❌ Cross-process IPC / Chromium multi-process overhead |
| ✅ System outline fonts & TTF/OTF `@font-face` | ❌ Full Web browser navigation / arbitrary web browsing |
| ✅ GPU (OpenGL / Vulkan) & CPU software rasterizer with pixel parity | ❌ Heavy browser sandbox isolation |

---

## ⏱️ Faster Cold Start (optional, JDK 24+)

The engine itself targets Java 21, but on JDK 24+ you can ship an AOT cache (JEP 483) to cut process-to-first-frame time by roughly 2x — no code change:

```
java -XX:AOTCacheOutput=app.aot -cp <your classpath as jars> YourMain     # training run (once, per build)
java -XX:AOTCache=app.aot        -cp <same jars>             YourMain     # normal runs
```

Measured on Windows / JDK 27 (`ColdStartMain`, median of 7 fresh JVMs, 800x600): the showcase page went from 482 ms to `220 ms`, and a 2,000-row list from 575 ms to `249 ms`. The cache must be built from the same jars (directories are rejected) and the same JDK.

---

## 📖 Further Reading

- [BENCHMARK.md](../BENCHMARK.md): Measured numbers, conditions, and how to reproduce them (Japanese).
- [ARCHITECTURE.md](../ARCHITECTURE.md): Module layout, layers, and the mechanical checks that guard them (Japanese).
- [FIDELITY.md](../FIDELITY.md): Ledger of known differences from browsers, with measured cases.
