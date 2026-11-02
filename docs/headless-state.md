# Machine UIs & In-World Surfaces with Headless State

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

## In-world screens driven by state

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

---

## Holograms and items driven by state

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
