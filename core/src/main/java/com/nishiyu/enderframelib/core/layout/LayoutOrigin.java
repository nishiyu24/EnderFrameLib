package com.nishiyu.enderframelib.core.layout;

/**
 * 箱の絶対位置の解決（{@link LayoutBox#x()} = 祖先の寄与の合計 + 親相対の {@code x}）を、木を下りる側が O(1) で持ち回れるようにする入口（K4b）。
 * 表示リストは箱ごとに祖先の連鎖を足し直さず、親の原点に自分の寄与を足して子へ渡す。解決の規則は {@link PlacementMemo#resolveX} と同じ。
 */
public final class LayoutOrigin {
    private LayoutOrigin() {}

    /** 親相対の座標のまま。 */
    public static float relX(LayoutBox b) { return b.x; }

    public static float relY(LayoutBox b) { return b.y; }

    /** 子の絶対原点へ足す、この箱の寄与（インラインの流れの箱は 0、それ以外は親相対の座標）。 */
    public static float shiftX(LayoutBox b) { return PlacementMemo.isInlineFlowForOrigin(b) ? 0f : b.x; }

    public static float shiftY(LayoutBox b) { return PlacementMemo.isInlineFlowForOrigin(b) ? 0f : b.y; }

    /** 位置を親から足さず自分の値で解決する箱（{@code display: none / contents}）か。 */
    public static boolean resolvesLocally(LayoutBox b) { return b.display != null && (b.display.isNone() || b.display.isContents()); }

    /** 祖先の寄与の合計。{@code b.x() = originX(b) + relX(b)}（{@link #resolvesLocally} の箱を除く）。 */
    public static float originX(LayoutBox b) {
        float r = 0f;
        for (LayoutBox p = b.parent; p != null; p = p.parent) r += shiftX(p);
        return r;
    }

    public static float originY(LayoutBox b) {
        float r = 0f;
        for (LayoutBox p = b.parent; p != null; p = p.parent) r += shiftY(p);
        return r;
    }
}
