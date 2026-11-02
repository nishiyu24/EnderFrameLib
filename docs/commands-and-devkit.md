# Commands & Browser Preview (DevKit)

## Commands

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

---

## Browser preview

`/enderframe devkit` writes `<gameDir>/enderframe_devkit/` with a standalone
HTML/CSS/JS kit. Open `preview-playground.html` in Chrome, Edge, Firefox or VS
Code Live Server and author your UI without launching Minecraft — the custom
elements and the reactive attributes are reimplemented there.

The kit ships the engine's own user-agent sheet (`enderframe-ua.css`) and the
built-in themes verbatim, and links them in the same order the game does, so the
preview is not styled by a parallel set of defaults. It also contains
`fidelity-dump.html`, which measures the fidelity corpus in your browser; the
measured values are what [FIDELITY.md](../FIDELITY.md) and `WebFidelityTest` compare
the engine against.
