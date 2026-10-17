package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A server-side UI session: one document, its size, and the viewers it is
 * broadcast to. Obtain one through {@link EnderFrameAPI#getOrCreateSession}
 * or {@link EnderFrameAPI#findSession}.
 *
 * <p>All methods are expected to be called from the server thread.</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface WebSessionHandle {

    /** The id this session is addressed by on the network. */
    String getSessionId();

    /** Current layout width in CSS pixels. */
    int getWidth();

    /** Current layout height in CSS pixels. */
    int getHeight();

    /** The loaded document, or {@code null} when nothing has been loaded (or after {@link #clear()}). */
    @Nullable
    WebDocument getDocument();

    /** Resizes the viewport; re-renders and re-broadcasts when the size actually changed. */
    void updateSize(int width, int height);

    /** Parses {@code html} as the new document and broadcasts it to all viewers. */
    void loadHtml(String html);

    /** Parses {@code html} with an additional external stylesheet and broadcasts it to all viewers. */
    void loadWithCss(String html, String css);

    /** Re-parses and re-broadcasts the source last passed to {@code loadHtml}/{@code loadWithCss}. */
    void reload();

    /** Drops the document and tells viewers the session is now empty. */
    void clear();

    /** Re-broadcasts the current document (use after mutating it through a patch method). */
    void requestReRender();

    /** Replaces the text content of one element and sends a single-element patch. */
    void patchText(String elementId, String newText);

    /** Sets or removes one attribute of one element and sends a single-element patch. */
    void patchAttribute(String elementId, String attrName, String attrValue, boolean remove);

    /** Sets one inline style property of one element and sends a single-element patch. */
    void patchStyle(String elementId, String property, String value);

    /** Applies a class change ({@code "add"}, {@code "remove"} or {@code "toggle"}) and sends a patch. */
    void patchClass(String elementId, String className, String mode);

    /** Replaces the children of one element with a parsed HTML fragment and sends a patch. */
    void patchInnerHtml(String elementId, String htmlFragment);
}
