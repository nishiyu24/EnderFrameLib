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

For how the single live DOM, `data-owner="server|client"` subtree ownership, fine-grained `DomMutationRecord` patching, and `data-forward` event forwarding work in hybrid sessions, see the [README](../README.md#server-sent-html-ownership-model--mutation-sync).
