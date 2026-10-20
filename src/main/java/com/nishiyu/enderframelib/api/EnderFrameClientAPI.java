package com.nishiyu.enderframelib.api;

import com.nishiyu.enderframelib.api.dom.Document;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Public entry point for Client-Driven UI.
 *
 * <p>Allows opening screens, HUD overlays, in-world block faces, and billboard UIs entirely
 * on the client, manipulating them directly via the DOM API through the returned
 * {@link Document} handle. No network communication is involved, making this suitable for
 * high-frequency updates such as HUDs and animations.</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface EnderFrameClientAPI {

    /**
     * Returns the active client API implementation.
     *
     * @throws IllegalStateException when called before the client API is available
     */
    static EnderFrameClientAPI get() {
        EnderFrameClientAPI api = Holder.INSTANCE;
        if (api == null) {
            throw new IllegalStateException("EnderFrameClientAPI is not available yet.");
        }
        return api;
    }

    /**
     * Returns whether the client API is available.
     */
    static boolean isAvailable() {
        return Holder.INSTANCE != null;
    }

    /** Installs the client API implementation. */
    @ApiStatus.Internal
    static void setInstance(EnderFrameClientAPI instance) {
        Holder.INSTANCE = instance;
    }

    /**
     * Opens a local UI screen on the client and returns its {@link Document} handle.
     *
     * @param sessionId session id, or {@code null} to generate one automatically
     * @param html      initial HTML content string
     * @param width     screen width in CSS pixels
     * @param height    screen height in CSS pixels
     * @return the constructed DOM Document
     */
    Document openScreen(@Nullable String sessionId, String html, int width, int height);

    /**
     * Installs a persistent HUD overlay on the client and returns its {@link Document} handle.
     *
     * @param sessionId session id, or {@code null} to generate one automatically
     * @param html      initial HTML content string
     * @return the constructed DOM Document
     */
    Document setHud(@Nullable String sessionId, String html);

    /**
     * Returns the {@link Document} handle of a client session, or {@code null} if not found.
     *
     * @param sessionId session id
     * @return the document, or {@code null} when no such session exists
     */
    @Nullable
    Document getDocument(String sessionId);

    /**
     * Closes a client session and cleans up its DOM and scheduled tasks.
     *
     * @param sessionId session id
     */
    void closeSession(String sessionId);

    // --- Scheduler (rAF / setTimeout / setInterval) ---

    /**
     * Schedules a callback to be run before the next paint frame (equivalent to {@code window.requestAnimationFrame}).
     *
     * @param sessionId session id
     * @param callback  callback receiving the current timestamp in milliseconds
     * @return request ID handle
     */
    int requestAnimationFrame(String sessionId, Consumer<Double> callback);

    /**
     * Cancels an animation frame callback previously scheduled with {@link #requestAnimationFrame}.
     *
     * @param id request ID handle
     * @return {@code true} if successfully cancelled
     */
    boolean cancelAnimationFrame(int id);

    /**
     * Schedules a one-off task to execute after the specified delay in milliseconds.
     *
     * @param sessionId session id
     * @param task      task to execute
     * @param delayMs   delay in milliseconds
     * @return timeout ID handle
     */
    int setTimeout(String sessionId, Runnable task, long delayMs);

    /**
     * Cancels a timeout task previously scheduled with {@link #setTimeout}.
     *
     * @param id timeout ID handle
     * @return {@code true} if successfully cancelled
     */
    boolean clearTimeout(int id);

    /**
     * Schedules a task to execute repeatedly at the specified interval in milliseconds.
     *
     * @param sessionId  session id
     * @param task       task to execute
     * @param intervalMs interval in milliseconds
     * @return interval ID handle
     */
    int setInterval(String sessionId, Runnable task, long intervalMs);

    /**
     * Cancels an interval task previously scheduled with {@link #setInterval}.
     *
     * @param id interval ID handle
     * @return {@code true} if successfully cancelled
     */
    boolean clearInterval(int id);

    @ApiStatus.Internal
    final class Holder {
        private static volatile EnderFrameClientAPI INSTANCE;
        private Holder() {}
    }
}