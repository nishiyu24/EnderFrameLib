package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

/**
 * ポップオーバーの暗黙のアンカーの入力の写し（Engine v3 M2b-5。CSS Anchor Positioning 1 §2.4 の implicit anchor element）。
 * 呼び出し元の {@code popovertarget}（{@link #invokes}）と、ポップオーバー自身の {@code id}（{@link #id}）。属性はスタイルに現れないので
 * 入力側が写し（{@link BoxNode#popover}）、レイアウトは生きた DOM を読まない（属性だけの変化も箱の作り直しとして届く）。
 */
public record PopoverLink(String invokes, String id) {

    /** 呼び出し元かポップオーバーなら写し、どちらでもなければ null。 */
    static PopoverLink of(HtmlNode n) {
        if (n.nodeType != NodeType.ELEMENT) return null;
        String target = n.attrs.get("popovertarget");
        String id = n.attrs.containsKey("popover") ? n.attrs.get("id") : null;
        if (target == null && id == null) return null;
        return new PopoverLink(target == null ? null : target.trim(), id == null ? null : id.trim());
    }
}
