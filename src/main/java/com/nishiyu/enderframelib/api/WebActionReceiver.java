package com.nishiyu.enderframelib.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Interface implemented by an {@code AbstractContainerMenu} or {@code BlockEntity}
 * to directly receive client-side server actions (such as {@code <button onclick="server:action_name">}
 * or {@code sendServerAction(...)}) without requiring custom network packet definitions.
 */
@ApiStatus.AvailableSince("1.1")
public interface WebActionReceiver {

    /**
     * Invoked on the server main thread when a server action is sent from a client.
     *
     * @param player  the sending player
     * @param action  the action name (excluding the {@code "server:"} prefix)
     * @param value   string payload (e.g. from {@code "server:action:value"} or input values)
     * @param payload additional CompoundTag payload data
     */
    void onWebServerAction(ServerPlayer player, String action, String value, CompoundTag payload);
}
