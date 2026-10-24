package com.nishiyu.enderframelib.api;

import com.nishiyu.enderframelib.api.dom.Document;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Consumer;

/**
 * A client-side UI session: the {@link Document} of a screen or HUD opened with
 * {@link EnderFrameClientAPI#open} / {@link EnderFrameClientAPI#hud}, and the timers that
 * belong to it. Timers stop when the session is closed.
 *
 * <pre>{@code
 * ClientSession hud = EnderFrameClientAPI.get().hud(Ui.html("<span id='n'>0</span>"));
 * hud.requestAnimationFrame(t -> hud.document().getElementById("n").setTextContent("tick"));
 * }</pre>
 */
@ApiStatus.AvailableSince("1.2")
public interface ClientSession {

    /** The id this session is addressed by. */
    String id();

    /** The live DOM of the session. Changes show on the next frame; nothing is sent over the network. */
    Document document();

    /** Runs {@code callback} before the next paint frame (like {@code window.requestAnimationFrame}). Returns a handle for {@link #cancel}. */
    int requestAnimationFrame(Consumer<Double> callback);

    /** Runs {@code task} once after {@code delayMs} (like {@code setTimeout}). Returns a handle for {@link #cancel}. */
    int setTimeout(Runnable task, long delayMs);

    /** Runs {@code task} every {@code intervalMs} (like {@code setInterval}). Returns a handle for {@link #cancel}. */
    int setInterval(Runnable task, long intervalMs);

    /** Cancels a frame callback, timeout or interval by the handle its call returned. */
    boolean cancel(int handle);

    /** Closes the session, dropping its DOM and timers. */
    void close();
}
