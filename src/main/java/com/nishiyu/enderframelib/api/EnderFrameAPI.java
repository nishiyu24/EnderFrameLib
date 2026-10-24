package com.nishiyu.enderframelib.api;

import com.nishiyu.enderframelib.api.state.UIState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
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
 * <p>Sessions opened through {@link #open} and {@link #hud} without a session id are
 * <em>owned</em> by the player they were opened for: no other player can request
 * a sync of them or dispatch actions into them. Sessions created with
 * {@link #getOrCreateSession} have no owner and are reachable by anyone who can
 * legitimately see them (a world screen, or a session a registered
 * {@link WebSessionProvider} vouches for).</p>
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

    // --------------------------------------------------------------------- ui

    /**
     * Opens a UI for one player. {@link Ui} says what to show: a client template driven by a
     * {@link UIState} ({@link Ui#template}), a datapack UI ({@link Ui#datapack}) or an HTML
     * string ({@link Ui#html}).
     *
     * <p>HTML and datapack UIs create a session if needed. When no session id is given the session
     * is owned by {@code player}: no other player can request a sync of it or dispatch actions into it.</p>
     */
    void open(@NotNull ServerPlayer player, @NotNull Ui ui);

    /**
     * Installs a persistent HUD overlay for one player from an HTML UI. Without a session id the
     * session is owned by {@code player}. Replaces the previous HUD.
     */
    void hud(@NotNull ServerPlayer player, @NotNull Ui.Html ui);

    /** Removes the HUD overlay installed with {@link #hud}. */
    void clearHud(@NotNull ServerPlayer player);

    /**
     * Returns the {@link UIState} of a block entity, creating it on first use.
     * Its instance id is {@code "block:x,y,z"}, from the block entity's position.
     *
     * <p>The state is closed automatically on the server tick after the block entity is
     * removed (broken or unloaded), which also closes the screen of every viewer. A block
     * placed again at the same position gets a fresh state.</p>
     *
     * @param be         the block entity that owns the UI
     * @param templateId the client-side template, e.g. {@code "mymod:crusher"} for
     *                   {@code assets/mymod/enderframe_ui/crusher.html}
     */
    @NotNull
    UIState stateOf(@NotNull BlockEntity be, @NotNull String templateId);

    /**
     * Returns the state of a template instance that has no block entity (a hologram, an item, a shared menu),
     * creating it when missing. It lives until {@link UIState#close()} or the server stops.
     */
    @NotNull
    UIState stateOf(@NotNull String templateId, @NotNull String instanceId);

    /**
     * Shows a template on an in-world surface that is not a block: a hologram
     * ({@code Surfaces.hologram}) or an item ({@code EnderScreenItem}). The client builds a session of
     * {@code width} x {@code height} pixels named by the instance id and opens no screen; point the hologram
     * or the item's {@code SessionId} at that id. The state is the one {@link Ui.Template#state}, or the one
     * for the instance id (created if missing). Values you {@code set} reach every player shown the surface.
     */
    void showSurface(@NotNull ServerPlayer player, @NotNull Ui.Template ui, int width, int height);

    /** Stops showing a surface started with {@link #showSurface}; the client drops its session. */
    void hideSurface(@NotNull ServerPlayer player, @NotNull Ui.Template ui);

    /**
     * Discards the state of a block entity's template screen: viewers are told to drop their session and the
     * values are forgotten. The next {@code stateOf} starts empty.
     */
    void discardSurface(@NotNull BlockEntity be);

    /**
     * Shows the template screen of a {@link WebScreenBlockEntity} (one whose
     * {@link WebScreenBlockEntity#getTemplateId()} is not {@code null}) to every player tracking its
     * chunk. Players who start tracking the chunk later see it without another call, so you only need
     * this when the screen's shape changes. Does nothing for other block entities.
     */
    void refreshSurface(@NotNull BlockEntity be);

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
    void registerProvider(WebSessionProvider provider);

    /** Unregisters a host registered with {@link #registerProvider(WebSessionProvider)}. */
    void unregisterProvider(WebSessionProvider provider);

    /** Registers a prefix-keyed provider. Internal bridge implementation. */
    @ApiStatus.Internal
    default void registerPrefixProvider(String prefix, WebSessionProvider provider) {}

    /** Unregisters a prefix-keyed provider. Internal bridge implementation. */
    @ApiStatus.Internal
    default void unregisterPrefixProvider(String prefix) {}


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
