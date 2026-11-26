# Client-Driven UI & Hybrid Execution Model

EnderFrameLib 1.0+ supports a **hybrid execution model** where documents can be opened
and mutated entirely on the client side without network communication, while seamlessly
interoperating with server-driven sessions.

## Client-driven screens & HUDs

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

## Client scheduler

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

## Server-sent HTML: ownership model & mutation sync

This applies to documents sent from the server as HTML (`Ui.html`, `Ui.datapack`, `hud`, `WebSessionHandle`). Headless-state UIs in [headless-state.md](headless-state.md) are client-owned templates and need none of it.

- **Single DOM**: Both client scripts and server packets operate on the single live DOM tree; there are no template/virtual copies and no diff engine.
- **Subtree Ownership**: Every subtree is either server-owned or client-owned. Declare it with `data-owner="server|client"` (inherited by descendants); without it the document default applies — **server-owned** for a document synced from the server, **client-owned** for one opened on the client only (`EnderFrameClientAPI.open` / `hud` / `loadLocalHtml`).
  - Client code *can* write into a server-owned subtree, but the next server mutation (or a full re-sync) wins and overwrites it.
  - Server mutation records aimed at a client-owned subtree are dropped, and a re-sync keeps client-owned islands (moved to the same `id` in the new tree; they vanish only if the server removed their host).
  - A client-opened document that the server also patches must mark those subtrees `data-owner="server"`.
- **Server Sync** (also behind `/enderframe patch`): `WebSession.patchText / patchAttribute / patchStyle / patchClass / patchInnerHtml` generate fine-grained `DomMutationRecord` packets that apply directly to target nodes without tree-wide diffing.
- **Declared event forwarding**: An element forwards events to the server with `data-forward="<event>[:<payload>] …"` and `data-forward-action="<name[:arg]>"` (payload: `value`, `key`, `point`, `target`, `none`). Client listeners run first; the event is sent only if nothing called `preventDefault()` and it reached the declaring element. `onclick="server:name"` is sugar for the same declaration, and inputs/textareas/selects inside server-owned subtrees implicitly forward `input`/`change` values for write-back.
- **List Reuse**: `data-for` reuses existing node instances by key (`data-row-key` or index) to preserve node identity and internal state.

See also [ARCHITECTURE.md](../../docs/ARCHITECTURE.md) for the internal synchronization design.
