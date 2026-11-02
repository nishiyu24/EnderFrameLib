# Getting Started

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

Reacting to a click, and updating the open document (the state-driven form in [headless-state.md](headless-state.md) sends only what changed):

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
