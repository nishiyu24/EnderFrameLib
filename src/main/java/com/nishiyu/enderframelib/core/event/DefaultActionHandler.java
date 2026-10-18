package com.nishiyu.enderframelib.core.event;

/**
 * イベントの既定動作（Default Action）を実行するハンドラ。
 * キャンセル（preventDefault）されていない場合に、イベントディスパッチの最終段で呼び出される。
 */
@FunctionalInterface
public interface DefaultActionHandler {
    void executeDefaultAction(DomEvent event);
}
