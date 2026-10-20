package com.nishiyu.enderframelib.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.ApiStatus;

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
