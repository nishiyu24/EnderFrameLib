package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

/**
 * 後方互換のための型だけを残した窓口。
 *
 * <p>入力から DOM イベントを合成する処理は {@link SessionInput} に移した（STRUCTURE_PLAN S4）。
 * {@link TriActionCallback} は {@link com.nishiyu.enderframelib.client.gui.EnderScreenDelegate} の
 * 公開シグネチャが参照しているため、名前と形をそのまま残している。</p>
 *
 * @deprecated 新しいコードは {@link SessionInput.ClickSink} を使う。この型は公開シグネチャの互換のためだけに残る。
 */
@Deprecated
public class ClientDomEventDispatcher {

    /** GUI の {@code onWebAction} フック。 */
    public interface TriActionCallback {
        void onAction(String action, DisplayItem clickedElement, int mouseButton);
    }
}
