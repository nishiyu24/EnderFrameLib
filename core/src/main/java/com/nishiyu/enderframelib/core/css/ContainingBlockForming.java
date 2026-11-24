package com.nishiyu.enderframelib.core.css;

/**
 * 絶対配置・固定配置の包含ブロックを作るか（transform・filter・layout / paint containment。{@code LayoutBox.findContainingBlock} と同じ見方）。
 * 値の変更は描画だけだが、作るかどうかが切り替わると子孫の絶対配置の基準が変わる。配置を保持した部分木はこれを自分では気づけないので、
 * 切り替わりを絶対配置の子孫を持つ箱ではレイアウトに効く変更として扱う（{@link com.nishiyu.enderframelib.core.layout.ContainingBlockFlip}。C9b）。
 */
public final class ContainingBlockForming {
    private ContainingBlockForming() { }

    public static boolean flips(ComputedStyle a, ComputedStyle b) {
        return forms(a) != forms(b);
    }

    private static boolean forms(ComputedStyle s) {
        Style st = s.typed();
        return isSet(st.visual.transform) || isSet(st.visual.filter)
                || (st.facts.containment & (ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT)) != 0;
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank() && !"none".equalsIgnoreCase(v.trim());
    }
}
