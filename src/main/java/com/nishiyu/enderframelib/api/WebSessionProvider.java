package com.nishiyu.enderframelib.api;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A host that owns server-side sessions — a block entity, a scripting runtime,
 * a menu — and resolves them by id for the client.
 *
 * <p>Register with {@link EnderFrameAPI#registerProvider}. A provider is trusted:
 * returning a session from {@link #getSessionForClient} tells the library that
 * the requesting player is allowed to see it, so implementations must perform
 * their own access check (ownership, distance, permissions) rather than
 * creating a session for whatever id arrives.</p>
 */
@ApiStatus.AvailableSince("1.1")
public interface WebSessionProvider {

    /** Whether this provider is responsible for the given session id. */
    boolean matchesSession(String sessionId);

    /**
     * Resolves the session for a client request.
     *
     * @return the session, or {@code null} to deny the request
     */
    @Nullable
    WebSessionHandle getSessionForClient(String sessionId);

    /**
     * Whether {@code player} may view and interact with this session.
     * Defaults to allowing it, which is correct for providers whose session ids
     * are not guessable or whose content is not player-specific.
     */
    default boolean allowsPlayer(ServerPlayer player, String sessionId) {
        return true;
    }

    /** Called for {@code fetch(...)} requests issued by the client document. */
    default void onFetchRequest(ServerPlayer player, String sessionId, String requestId, String endpoint, String jsonPayload) {}

    /** Called for DOM events the client forwarded to the server. */
    default void onClientEvent(ServerPlayer player, String sessionId, String elementId, String eventName, String actionName, String payload) {}
}
