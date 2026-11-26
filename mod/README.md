# EnderFrameLib — Minecraft integration (`:mod`)

This directory is the **optional NeoForge 1.21.1 mod** built on the EnderFrameLib engine. The engine itself
(`:core`, `:core-gl`, `:core-vulkan`, `:core-glfw`) lives at the repository root and has no dependency on
Minecraft; this mod is one host of it. Dependencies point one way: `:mod` → `:core*`.

For what the mod offers (GUI screens, HUDs, in-world block / hologram / item surfaces, `UIState` sync,
`/enderframe` commands, `<mc-*>` elements), see the [repository README](../README.md#optional-minecraft-integration-neoforge-mod)
and the documents below. Engine documents (architecture, benchmarks, fidelity, the plan) are in [../docs/](../docs/README.md).

## Layout

```text
mod/
  build.gradle         NeoForge (ModDevGradle) build; bundles the engine modules into the mod JAR
  gradle.properties    Minecraft / NeoForge / mod metadata (Gradle settings stay in the root gradle.properties)
  src/main/java        api/ client/ server/ network/ content/ gametest/ mixin/
  src/main/resources   assets, datapack UIs (data/enderframelib/enderframe_ui/*.html), mixins
  src/main/templates   neoforge.mods.toml template
  src/test/java        headless tests (no Minecraft client is started)
  src/test/resources   mod-only test data (display-list goldens, Chrome-measured rectangles, server baselines)
  docs/                mod guides, command reference, in-game test specifications (listed below)
  run/                 development game directory (not tracked)
```

## Tasks

Run from the repository root:

```bash
./gradlew :mod:test                # headless mod tests (working directory = repository root)
./gradlew :mod:build               # mod JAR with the engine classes bundled (mod/build/libs)
./gradlew :mod:runClient           # development client
./gradlew :mod:runServer           # development server
./gradlew :mod:runGameTestServer   # server-side lifecycle GameTests
```

Engine-only tasks (`:core:test` and the like) do not configure this project, so they need no
NeoForge or Minecraft setup.

## Tests and shared helpers

The mod tests borrow a few helpers from the engine through Gradle test fixtures
(`testImplementation testFixtures(project(':core'))`, sources in `core/src/testFixtures`): `GoldenFiles`,
`TestBoxes`, `WptHarness` / `WptBehaviorTest` and the WPT divergence ledger. They do not depend on the engine's
test output itself. Like the engine tests, they run with the repository root as the working directory, so
paths in tests are written from the root (`mod/src/...`, `core/src/...`).

## Documents

Guides (English):

- [docs/getting-started.md](docs/getting-started.md) — installing the mod and a first server-driven UI
- [docs/headless-state.md](docs/headless-state.md) — machine UIs and in-world screens with `UIState`
- [docs/client-and-hybrid.md](docs/client-and-hybrid.md) — client-driven UIs, HUDs, and hybrid server/client DOM ownership
- [docs/commands-and-devkit.md](docs/commands-and-devkit.md) — `/enderframe` essentials and the DevKit browser preview

Reference and in-game test specifications (Japanese):

- [docs/COMMAND_GUIDE.md](docs/COMMAND_GUIDE.md) — all `/enderframe` (`/ef`) commands and datapack integration
- [docs/TEST_SPEC_SURFACE.md](docs/TEST_SPEC_SURFACE.md) — web screens on block / item surfaces
- [docs/TEST_SPEC_BLOCKS_ITEMS.md](docs/TEST_SPEC_BLOCKS_ITEMS.md) — block and item rendering
- [docs/TEST_SPEC_MEDIA.md](docs/TEST_SPEC_MEDIA.md) — video, audio and images
