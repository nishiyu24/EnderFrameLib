package com.nishiyu.enderframelib.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;

/**
 * ワールド内に配置されるマルチブロックスクリーン用の共通インターフェース
 */
public interface IWebScreenBlockEntity {
    boolean isMaster();
    BlockPos getScreenMasterPos();
    Direction getFacing();
    int getScreenWidth();
    int getScreenHeight();
    String getSessionId();
    default int getLodColor() { return 0x00000000; }
    default List<BlockPos> getLinkedSpeakers() { return List.of(); }
}