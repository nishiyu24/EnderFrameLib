package com.nishiyu.enderframelib.core.css;

import java.util.Map;

/**
 * 表の匿名の箱のスタイル（Engine v3 M2b-4。CSS 2.2 §17.2.1）。親の継承群と {@code --*} を共有し、{@code display} だけを持つ
 * （{@link Style#forAnonymous}）。{@code display} ごとの非継承の群は不変の定数で、実行時に増える表は持たない。
 */
public final class AnonymousStyles {
    private static final Map<String, BoxGeomStyle> BOXES = Map.of(
            "table", box("table"), "inline-table", box("inline-table"), "table-row", box("table-row"), "table-cell", box("table-cell"));

    private AnonymousStyles() { }

    /** 親 {@code parent} の下に作る、{@code display} が {@code display}（{@code table} / {@code inline-table} / {@code table-row} / {@code table-cell}）の匿名の箱のスタイル。 */
    public static Style of(Style parent, String display) {
        BoxGeomStyle b = BOXES.get(display);
        if (b == null) throw new IllegalArgumentException(display);
        return Style.forAnonymous(parent, b);
    }

    private static BoxGeomStyle box(String display) {
        return BoxGeomStyle.of(new StyleReader.Source() {
            @Override public String get(int id) { return id == CssPropertyIndex.DISPLAY ? display : null; }
            @Override public float fontSizePx() { return ComputedStyle.DEFAULT_FONT_SIZE; }
            @Override public float rootFontSizePx() { return ComputedStyle.DEFAULT_FONT_SIZE; }
        });
    }
}
