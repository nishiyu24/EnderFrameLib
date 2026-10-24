package com.nishiyu.enderframelib.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Common interface for in-world multiblock screen block entities.
 */
@ApiStatus.AvailableSince("1.1")
public interface WebScreenBlockEntity {

    /**
     * Returns whether this block entity is the master of the multiblock screen.
     */
    boolean isMaster();

    /**
     * Returns whether this block takes part in a drawn screen. A block that was not told to draw, or was reset
     * when part of its multiblock screen was broken, shows nothing and takes no input.
     */
    default boolean isActive() {
        return true;
    }

    /**
     * Returns the position of the master block entity, or this position if this is master.
     */
    BlockPos getScreenMasterPos();

    /**
     * Returns the facing direction of the screen.
     */
    Direction getFacing();

    /**
     * Returns the width of the screen in blocks.
     */
    int getScreenWidth();

    /**
     * Returns the height of the screen in blocks.
     */
    int getScreenHeight();

    /**
     * Returns the session id associated with this screen.
     */
    String getSessionId();

    /**
     * Returns the client-side template that drives this screen, or {@code null} when the screen shows a
     * server-held session ({@link #getSessionId()}).
     *
     * <p>With a template, the screen is fed by the {@link com.nishiyu.enderframelib.api.state.UIState}
     * of the master block entity ({@link EnderFrameAPI#stateOf}): players who track its chunk see it,
     * clicks reach the state's {@code onAction} handlers, and nothing is held on the server but key-value
     * state. {@link #getSessionId()} must then be {@link Ui.Template#blockInstanceId} of the master position.</p>
     */
    @Nullable
    default String getTemplateId() {
        return null;
    }

    /**
     * Called on the server each time the {@link com.nishiyu.enderframelib.api.state.UIState} of this block
     * entity is created: the first time it is needed after the block is placed or its chunk is loaded.
     * Restore saved values and register {@code onAction} handlers here.
     */
    default void onStateCreated(com.nishiyu.enderframelib.api.state.UIState state) {
    }

    /** Returns the layout resolution of a template screen, in pixels per block of screen size. */
    default int getPixelsPerBlock() {
        return 64;
    }

    /**
     * Returns the fallback LOD tint color (ARGB).
     */
    default int getLodColor() {
        return 0x00000000;
    }

    /**
     * Returns block positions of linked audio speakers, if any.
     */
    default List<BlockPos> getLinkedSpeakers() {
        return List.of();
    }
}
