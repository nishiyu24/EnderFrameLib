package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A server-side UI session: one document, its size, and the viewers it is
 * broadcast to. Obtain one through {@link EnderFrameAPI#getOrCreateSession}
 * or {@link EnderFrameAPI#findSession}.
 *
 * <p>All methods are expected to be called from the server thread.</p>
 *
 * <p>This is for documents you build yourself. For machine UIs and high-frequency updates keep a
 * {@link com.nishiyu.enderframelib.api.state.UIState} and a client template instead:
 * {@code EnderFrameAPI.open(player, Ui.template(id).at(blockEntity))}.</p>
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

    /** Re-broadcasts the current document. */
    void requestReRender();
}
