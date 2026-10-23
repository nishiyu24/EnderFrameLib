package com.nishiyu.enderframelib.api.state;

import com.nishiyu.enderframelib.api.WebSessionHandle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Headless server state: the values behind one UI instance, kept on the server
 * without any HTML or DOM.
 *
 * <p>The HTML is a client-side template (see {@link #templateId()}); the server only
 * keeps key/value pairs, tracks which keys changed, and sends those keys to the
 * viewers. Changes are sent at the end of each server tick, or immediately with
 * {@link #flush()}. In the template, values are read with {@code {{key}}},
 * {@code data-bind}, {@code data-if} and the other binding attributes.</p>
 *
 * <p>Obtain one through {@code EnderFrameAPI.createBlockEntityState} and show it with
 * {@code EnderFrameAPI.openUi} / {@code openBlockEntityUi}. All methods are expected
 * to be called from the server thread.</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface UIState extends AutoCloseable {

    /**
     * The client-side template this UI uses, e.g. {@code "mymod:crusher"} for
     * {@code assets/mymod/enderframe_ui/crusher.html}.
     */
    @NotNull
    String templateId();

    /**
     * The id of this UI instance, e.g. {@code "block:100,64,200"}.
     */
    @NotNull
    String instanceId();

    /**
     * Sets a value. Only a value that differs from the current one is recorded as a
     * change and sent to the viewers; {@code null} removes the key.
     *
     * <p>Booleans, numbers, strings, arrays of bytes / ints / longs, {@link Map}s and
     * {@link java.util.List}s are sent as their NBT counterparts; any other object is
     * sent as its {@code toString()}.</p>
     *
     * @param key   the key (not null)
     * @param value the new value, or {@code null} to remove the key
     * @return this state, for chaining
     */
    @NotNull
    UIState set(@NotNull String key, @Nullable Object value);

    /**
     * Returns the value of a key.
     *
     * @param key the key
     * @return the value, or {@code null} when the key is not set
     */
    @Nullable
    Object get(@NotNull String key);

    /**
     * Returns the value of a key when it is an instance of {@code type}, otherwise {@code null}.
     */
    @Nullable
    <T> T get(@NotNull String key, @NotNull Class<T> type);

    /**
     * Returns the value of a key as a string, or {@code null} when the key is not set.
     */
    @Nullable
    String getString(@NotNull String key);

    /**
     * Returns the value of a key as an int, or {@code defaultValue} when it is missing
     * or cannot be read as one.
     */
    int getInt(@NotNull String key, int defaultValue);

    /**
     * Returns the value of a key as a long, or {@code defaultValue} when it is missing
     * or cannot be read as one.
     */
    long getLong(@NotNull String key, long defaultValue);

    /**
     * Returns the value of a key as a double, or {@code defaultValue} when it is missing
     * or cannot be read as one.
     */
    double getDouble(@NotNull String key, double defaultValue);

    /**
     * Returns the value of a key as a boolean, or {@code defaultValue} when it is missing
     * or cannot be read as one.
     */
    boolean getBoolean(@NotNull String key, boolean defaultValue);

    /**
     * Returns an immutable snapshot of all values.
     */
    @NotNull
    Map<String, Object> snapshot();

    /**
     * Returns all values as a {@link CompoundTag}.
     */
    @NotNull
    CompoundTag toCompoundTag();

    /**
     * Adds a viewer. The player immediately receives the template id and the current
     * state, and from then on every change.
     *
     * @param player the player who views this UI
     */
    void addViewer(@NotNull ServerPlayer player);

    /**
     * Removes a viewer. Viewers are also removed when they close the screen, log out
     * or change dimension.
     *
     * @param player the player to remove
     */
    void removeViewer(@NotNull ServerPlayer player);

    /**
     * Returns the current viewers.
     */
    @NotNull
    Set<ServerPlayer> viewers();

    /**
     * Returns whether there are changes that have not been sent yet.
     */
    boolean hasChanges();

    /**
     * Sends the pending changes to every viewer now and clears them. Does nothing when
     * there are no changes.
     */
    void flush();

    /**
     * Registers a handler for an action sent from the UI, such as a button with
     * {@code onclick="server:<actionName>"}. Only players who are viewers of this state
     * can trigger it, and the payload comes from the client, so do not trust it.
     *
     * @param actionName the action name
     * @param handler    receives the player and the action's payload
     * @return this state, for chaining
     */
    @NotNull
    UIState onAction(@NotNull String actionName, @NotNull BiConsumer<ServerPlayer, CompoundTag> handler);

    /**
     * Runs the handler registered for an action, as if {@code player} had sent it.
     *
     * @param actionName the action name
     * @param player     the player the action is attributed to
     * @param payload    additional data
     */
    void dispatchAction(@NotNull String actionName, @NotNull ServerPlayer player, @NotNull CompoundTag payload);

    /**
     * Discards this state and removes it from the registry. Viewers that still have the
     * screen open are told to close it. A state from
     * {@code EnderFrameAPI.createBlockEntityState} is closed automatically once its block
     * entity is removed; call this for other owners when they go away.
     */
    @Override
    void close();

    /**
     * Returns an adapter for code written against {@link WebSessionHandle}: calls such
     * as {@code patchText} on it are routed to {@link #set}.
     *
     * @return the adapter, or {@code null} when this implementation has none
     */
    @Nullable
    default WebSessionHandle asSessionHandle() {
        return null;
    }
}
