package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Public entry point for Client-Driven UI.
 *
 * <p>Opens screens and HUD overlays entirely on the client and hands back a
 * {@link ClientSession}, whose DOM you change directly. No network communication is involved,
 * which suits high-frequency updates such as HUDs and animations. It takes the same {@link Ui}
 * description as {@link EnderFrameAPI#open}; only {@link Ui.Html} is supported here.</p>
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

    /** Returns whether the client API is available. */
    static boolean isAvailable() {
        return Holder.INSTANCE != null;
    }

    /** Installs the client API implementation. */
    @ApiStatus.Internal
    static void setInstance(EnderFrameClientAPI instance) {
        Holder.INSTANCE = instance;
    }

    /**
     * Opens a local screen. Without a session id one is generated. The default size is 854x480.
     */
    ClientSession open(@NotNull Ui.Html ui);

    /**
     * Installs a persistent HUD overlay. Without a session id one is generated. The default size is 1280x720.
     */
    ClientSession hud(@NotNull Ui.Html ui);

    /** Returns the session with this id, or {@code null} when there is none. */
    @Nullable
    ClientSession find(String sessionId);

    @ApiStatus.Internal
    final class Holder {
        private static volatile EnderFrameClientAPI INSTANCE;
        private Holder() {}
    }
}
