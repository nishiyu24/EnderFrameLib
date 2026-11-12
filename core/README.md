# EnderFrameLib `:core` — High-Performance Pure-Java HTML/CSS Engine

[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![License: LGPL-3.0](https://img.shields.io/badge/License-LGPL_3.0-green.svg)](https://www.gnu.org/licenses/lgpl-3.0.html)

EnderFrameLib `:core` is an independent, lightweight, ultra-high-performance Web / UI rendering engine written in pure Java 21.

It combines the Java 21 Foreign Function & Memory (FFM) API (off-heap memory), bit-parallel selector matching, reactive slice patching, a single-draw-call GPU Uber-Shader, and a 64×64 tile-parallel CPU software rasterizer. It has **zero dependencies on Minecraft or game frameworks** and can be embedded into desktop apps, game engines, tools, or run headlessly for automated image generation.

---

## ⚡ Highlights

- **144Hz Frame Budget Optimized**:
  - `idle`: **`0.000 ms / 0 B`** (completely skips no-op frames)
  - `scroll`: **`0.002 ms / 0 B`** (instant bitset patch, 0 GC allocations)
  - `:hover` & local restyle: **`0.008 ms / 32 B`** (up to 49× faster than Chromium Blink via `StyleStateAutomaton`)
  - 2,000-item lists: GL frame times of `hover` (0.30 ms) and `child` insertion (1.44 ms) beat headless Chrome 157.
- **Dual Hybrid Backends**:
  - **GPU (OpenGL 3.2+ Core)**: SDF Uber-Shader batching rounded rectangles, borders, shadows, and glyphs into 1 draw call.
  - **CPU (Tile-Parallel Software Rasterizer)**: 64×64 multithreaded tile rasterizer with zero native dependencies, ensuring pixel parity in headless, CI, or server environments.
- **W3C Standards Subset**:
  - Full support for Block, Flexbox, Grid, Table, Multicolumn, and Container Queries (`@container`).
  - Colors: RGB, HSL, `oklch`, `display-p3`, and gradient shaders.
  - CSS `@keyframes` and `transition` animations.
  - Full W3C DOM and UI Events (Mouse, Pointer, Keyboard, Wheel, Focus, and IME Composition).
- **Architectural Rigor**:
  - Strict 7-layer OSI-style unidirectional architecture (L1–L7) mechanically enforced by `LayerDependencyRatchetTest` (0 upward dependencies).
  - Clean public API (`com.nishiyu.enderframelib.api.*`) with zero internal types leaked.

---

## 📦 Installation

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
}

val lwjglVersion = "3.3.3"
val lwjglNatives = "natives-windows" // or natives-linux, natives-macos, etc.

dependencies {
    implementation("com.nishiyu.enderframelib:enderframelib-core:2.0.0")

    // LWJGL native binaries for your target platform(s)
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-freetype::$lwjglNatives")
}
```

### Maven

```xml
<dependency>
    <groupId>com.nishiyu.enderframelib</groupId>
    <artifactId>enderframelib-core</artifactId>
    <version>2.0.0</version>
</dependency>
```

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

### 3. Standalone Desktop Window (GLFW + OpenGL)

```java
import com.nishiyu.enderframelib.common.StandaloneViewer;

public class DesktopExample {
    public static void main(String[] args) throws Exception {
        StandaloneViewer.launch(new String[]{"path/to/page.html", "1024", "768"}, view -> {
            // Optional: attach Java callbacks or inspect live DOM
        });
    }
}
```

---

## ⌨️ Input & IME (Multi-language / Japanese Composition)

`WebViewSurface` supports full pointer, keyboard, and W3C UI Events Composition:

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
| ✅ Flexbox, Grid, Blocks, Inline formatting | ❌ Heavy JavaScript engines (V8 / SpiderMonkey) |
| ✅ Full WHATWG DOM mutation & W3C UI Events | ❌ Video decoders & WebRTC inside core |
| ✅ CSS animations, transforms, opacity, shadows | ❌ Cross-process IPC / Chromium multi-process overhead |
| ✅ System outline fonts & TTF/OTF `@font-face` | ❌ Full Web browser navigation / arbitrary web browsing |
| ✅ Hybrid GPU Uber-Shader & CPU software rasterizer | ❌ Heavy browser sandbox isolation |

---

## 📖 Further Reading

- [DISTRIBUTION_ROADMAP.md](DISTRIBUTION_ROADMAP.md): Detailed distribution roadmap and readiness tracker.
- [ENGINE_REARCHITECTURE_PLAN.md](ENGINE_REARCHITECTURE_PLAN.md): Master architectural specification, JFR profiling data, and benchmarks.
- [ENGINE_3D_ARCHITECTURE_PLAN.md](ENGINE_3D_ARCHITECTURE_PLAN.md): 3D scene graph integration specification.
