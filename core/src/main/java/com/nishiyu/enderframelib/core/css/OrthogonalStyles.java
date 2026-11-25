package com.nishiyu.enderframelib.core.css;

/**
 * 直交フロー（縦書き）のスタイル（Engine v3 M2b-5。旧経路と同じ規則の {@link DerivedStyles} を新経路の不変 {@link Style} で使う入口）。
 *
 * <p>縦書きは「論理（横書き）の軸で組んだ内容を回して置く」方式で実現する（{@link WritingModes}）。縦書きの根の中の箱は、物理の
 * プロパティ（{@code width} ↔ {@code height}・余白／枠線／padding の辺・{@code overflow-x/y}・{@code aspect-ratio}）を論理の軸へ
 * 入れ替えたスタイルで組まれ、根の流れの中身は回転（{@code vertical-lr} は転置）の {@code transform} を持つ包む箱 1 つに入る。
 * 包む箱は描画で物理の向きへ回す。</p>
 */
public final class OrthogonalStyles {
    private OrthogonalStyles() { }

    /**
     * 書字方向 {@code flow}（{@link WritingModes} の定数）の縦書きの文脈の中の箱が読む、軸を入れ替えたスタイル（旧
     * {@code LayoutBox.style()} の {@code derivedVerticalAxisStyle}）。横書きの文脈・入れ替えるプロパティが無いなら同じ参照。
     */
    public static Style axisSwapped(Style computed, int flow) {
        if (flow == WritingModes.HORIZONTAL) return computed;
        ComputedStyle swapped = new ComputedStyle(computed).derivedVerticalAxisStyle(flow);
        Style s = swapped.typed();
        return s == computed ? computed : Style.of(swapped, computed);
    }

    /**
     * 直交フローの根の流れの中身を包む箱のスタイル（旧 {@code BoxTreeBuilder.wrapperStyle}）。継承値は根から受け、{@code display}
     * （{@code block} / {@code inline-block} / {@code flex} / {@code grid} / {@code table}）と回転の {@code transform}、flex・grid・表の
     * 容器のプロパティを持つ。{@code toVertical} は横書きから縦書きの根へ入るとき（false は縦書きの中の横書きの根で、根の
     * {@code width} / {@code height} などの大きさも包む箱が持つ）。{@code flow} は縦書きの側の書字方向。
     */
    public static Style wrapper(Style root, int flow, boolean toVertical, String display) {
        boolean transposed = WritingModes.isTransposed(flow), ccw = WritingModes.isCounterClockwise(flow);
        int transformKind = toVertical ? (transposed ? 3 : ccw ? 2 : 1) : (transposed ? 3 : ccw ? 1 : 2);
        ComputedStyle w = new ComputedStyle(root).derivedOrthogonalWrapper(transformKind, display, !toVertical);
        return Style.of(w, root);
    }
}
