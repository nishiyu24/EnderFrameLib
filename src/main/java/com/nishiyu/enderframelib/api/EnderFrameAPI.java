package com.nishiyu.enderframelib.api;

import com.nishiyu.enderframelib.api.state.UIState;
import net.minecraft.core.BlockPos;
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
 * <p>Sessions opened through {@link #openScreen} and {@link #setHud} are
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

    // ----------------------------------------------------------------- screens

    /**
     * Opens a full-screen UI for one player from an HTML string (Ad-hoc / Compatibility Mode).
     * The session is created if needed and is owned by {@code player}.
     *
     * <p>For standard machine UIs and reusable interfaces, prefer using {@link #openUi} or
     * {@link #openBlockEntityUi} with client templates and {@link com.nishiyu.enderframelib.api.state.UIState}.</p>
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

    /**
     * Installs a persistent HUD overlay for one player (Ad-hoc Session). The session is owned by {@code player}.
     */
    void setHud(ServerPlayer player, @Nullable String sessionId, String html);

    /** Removes the HUD overlay previously installed with {@link #setHud}. */
    void clearHud(ServerPlayer player);

    // ----------------------------------------------------------- headless state

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
     * @return the state
     */
    @NotNull
    UIState createBlockEntityState(@NotNull BlockEntity be, @NotNull String templateId);

    /**
     * Opens a headless-state UI for a player: adds the player as a viewer of the
     * {@link UIState} and sends the template id with the current state to the client.
     *
     * @param player     the player
     * @param templateId the client-side template
     * @param instanceId the id of this UI instance
     * @param state      the state, or {@code null} to look it up (or create it) by
     *                   {@code templateId} and {@code instanceId}
     */
    void openUi(@NotNull ServerPlayer player, @NotNull String templateId, @NotNull String instanceId, @Nullable UIState state);

    /**
     * Opens the headless-state UI of a block entity for a player. The instance id is
     * derived from the block entity's position, as in {@link #createBlockEntityState}.
     *
     * @param player     the player
     * @param be         the block entity that owns the UI
     * @param templateId the client-side template
     * @param state      the state, or {@code null} to look it up (or create it) with
     *                   {@link #createBlockEntityState}
     */
    default void openBlockEntityUi(@NotNull ServerPlayer player, @NotNull BlockEntity be, @NotNull String templateId, @Nullable UIState state) {
        if (player == null || be == null || templateId == null) return;
        BlockPos pos = be.getBlockPos();
        String instanceId = "block:" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        openUi(player, templateId, instanceId, state != null ? state : createBlockEntityState(be, templateId));
    }

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
