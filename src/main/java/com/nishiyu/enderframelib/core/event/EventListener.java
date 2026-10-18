package com.nishiyu.enderframelib.core.event;

/**
 * DOM イベントリスナ。
 */
@FunctionalInterface
public interface EventListener {
    void handleEvent(DomEvent event);
}
