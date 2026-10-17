package com.nishiyu.enderframelib.api;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The supported entry point into EnderFrameLib from server-side code.
 *
 * <p>Obtain the instance with {@link #get()} once the mod has been constructed.
 * The implementation lives outside this package on purpose: nothing in
 * {@code api} depends on the server, network or parser implementation, so
 * downstream code that compiles against {@code api} keeps compiling when those
 * change.</p>
 *
 * <p>Sessions opened through {@link #openScreen} and {@link #setHud} are
 * <em>owned</em> by the player they were opened for: no other player can request
 * a sync of them or dispatch actions into them. Sessions created with
 * {@link #getOrCreateSession} have no owner and are reachable by anyone who can
 * legitimately see them (a world screen, or a session a registered
 * {@link IWebSessionProvider} vouches for).</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface EnderFrameAPI {

    /**
     * Returns the active implementation.
     *
     * @throws IllegalStateException when called before EnderFrameLib has been constructed
     */
    static EnderFrameAPI get() {
        EnderFrameAPI api = Holder.INSTANCE;
        if (api == null) {
            throw new IllegalStateException(
                    "EnderFrameAPI is not available yet. Call it from mod setup or later, not from a static initializer.");
        }
        return api;
    }

    /** Whether {@link #get()} will succeed. */
    static boolean isAvailable() {
        return Holder.INSTANCE != null;
    }

    /** Installs the implementation. Called by EnderFrameLib during mod construction. */
    @ApiStatus.Internal
    static void setInstance(EnderFrameAPI implementation) {
        Holder.INSTANCE = implementation;
    }

    // ---------------------------------------------------------------- sessions

    /** Returns the session with this id, creating an unowned one at the given size when it does not exist. */
    WebSessionHandle getOrCreateSession(String sessionId, int width, int height);

    /** Returns the session with this id, or {@code null} when it does not exist. */
    @Nullable
    WebSessionHandle findSession(String sessionId);

    /** Drops a session, its viewer list and its owner. */
    void closeSession(String sessionId);

    // ----------------------------------------------------------------- screens

    /**
     * Opens a full-screen UI for one player from an HTML string.
     * The session is created if needed and is owned by {@code player}.
     *
     * @param sessionId session id, or {@code null}/empty to derive one from the player
     */
    void openScreen(ServerPlayer player, @Nullable String sessionId, String html, int width, int height);

    /**
     * Opens a UI declared in a datapack ({@code data/<namespace>/enderframe_ui/<path>.html}).
     *
     * @param uiId resource id in {@code "namespace:path"} form
     */
    void openDatapackScreen(ServerPlayer player, String uiId, int width, int height);

    /** Installs a persistent HUD overlay for one player. The session is owned by {@code player}. */
    void setHud(ServerPlayer player, @Nullable String sessionId, String html);

    /** Removes the HUD overlay previously installed with {@link #setHud}. */
    void clearHud(ServerPlayer player);

    // ------------------------------------------------------------------ pubsub

    /**
     * Publishes a diff onto a pub/sub channel. Only keys whose value changed are sent.
     *
     * @param channel channel name; must match {@code [a-z0-9_.:/-]{1,128}}
     */
    void publish(String channel, Map<String, Object> data);

    /**
     * Installs a check that decides whether a player may subscribe to a channel.
     * Checks are consulted in registration order and all of them must accept.
     */
    void addSubscriptionCheck(SubscriptionCheck check);

    // --------------------------------------------------------------- providers

    /** Registers a host that owns sessions (a block entity, a script runtime, …). */
    void registerProvider(IWebSessionProvider provider);

    /** Unregisters a host registered with {@link #registerProvider}. */
    void unregisterProvider(IWebSessionProvider provider);

    /** Decides whether a player may subscribe to a pub/sub channel. */
    @FunctionalInterface
    interface SubscriptionCheck {
        boolean allows(ServerPlayer player, String channel);
    }

    /** Holder for the single implementation instance. */
    @ApiStatus.Internal
    final class Holder {
        private static volatile EnderFrameAPI INSTANCE;

        private Holder() {}
    }
}
