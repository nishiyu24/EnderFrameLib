package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.ContainingBlockForming;

/**
 * transform・filter・containment が「包含ブロックを作る」かどうかが切り替わった箱の再レイアウト要否（C9b）。
 * 値の変更は描画だけだが、作るかどうかが変わると子孫の絶対配置・固定配置の基準が変わる。以前は描画だけの変更として扱われ、
 * 子孫の絶対配置が古い基準のまま残っていた（増分と最初から作った結果が食い違う）。影響があるのは絶対配置・固定配置の子孫を持つ箱だけなので、
 * それ以外（hover で transform を付ける葉など）は従来どおりレイアウトを走らせない。
 */
public final class ContainingBlockFlip {
    private ContainingBlockFlip() { }

    public static boolean relayout(LayoutBox box, ComputedStyle prev, ComputedStyle now) {
        return prev != null && now != null && ContainingBlockForming.flips(prev, now) && hasPositionedDescendant(box);
    }

    private static boolean hasPositionedDescendant(LayoutBox box) {
        for (LayoutBox c : box.children) {
            if (c.isAbsolute || c.isFixed || hasPositionedDescendant(c)) return true;
        }
        return false;
    }
}
