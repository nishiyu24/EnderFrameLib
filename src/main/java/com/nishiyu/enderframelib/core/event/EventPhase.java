package com.nishiyu.enderframelib.core.event;

/**
 * DOM イベントの伝播フェーズ（W3C DOM Level 2 Event 仕様準拠）。
 */
public enum EventPhase {
    NONE,
    CAPTURING_PHASE,
    AT_TARGET,
    BUBBLING_PHASE
}
