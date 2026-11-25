package com.nishiyu.enderframelib.core.css;

/**
 * 生成物（{@code scripts/GenStyleGroups.java}。手で直さない）: 子孫へ継承される、描画だけのプロパティの型付き不変構造体（新経路のレイアウトは読まない。M2c）。
 * 値は構築時に 1 回だけ解いた型付きのフィールドで、文字列を読み直さない。{@link #equals} は全フィールドの値等価。
 */
public final class InheritedPaintStyle {
    static final int ID_emptyCells = ComputedStyle.idOf("empty-cells");
    static final int ID_color = ComputedStyle.idOf("color");
    static final int ID_accentColor = ComputedStyle.idOf("accent-color");
    static final int ID_textDecoration = ComputedStyle.idOf("text-decoration");
    static final int ID_textShadow = ComputedStyle.idOf("text-shadow");
    static final int ID_webkitTextStrokeWidth = ComputedStyle.idOf("-webkit-text-stroke-width");
    static final int ID_webkitTextStrokeColor = ComputedStyle.idOf("-webkit-text-stroke-color");
    static final int ID_cursor = ComputedStyle.idOf("cursor");
    static final int ID_pointerEvents = ComputedStyle.idOf("pointer-events");

    /** 宣言が 1 つも無いときの共有インスタンス（初期値だけ）。ID_* の初期化より後に置く。 */
    public static final InheritedPaintStyle DEFAULT = new InheritedPaintStyle(null);

    /** {@code empty-cells}。 */
    public final String emptyCells;
    /** {@code color}。 */
    public final int color;
    /** {@code accent-color}。 */
    public final String accentColor;
    /** {@code text-decoration}。 */
    public final String textDecoration;
    /** {@code text-shadow}。 */
    public final String textShadow;
    /** {@code -webkit-text-stroke-width}。 */
    public final String webkitTextStrokeWidth;
    /** {@code -webkit-text-stroke-color}。 */
    public final String webkitTextStrokeColor;
    /** {@code cursor}。 */
    public final String cursor;
    /** {@code pointer-events}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.PointerEvents pointerEvents;
    /** {@code color} が明示宣言（または継承）されているか（{@code ::placeholder}/{@code ::first-line} 用）。 */
    public final boolean hasColor;

    private InheritedPaintStyle(StyleReader.Source cs) {
        this.emptyCells = StyleReader.text(cs, ID_emptyCells);
        this.color = StyleReader.color(cs, ID_color);
        this.accentColor = StyleReader.text(cs, ID_accentColor);
        this.textDecoration = StyleReader.text(cs, ID_textDecoration);
        this.textShadow = StyleReader.text(cs, ID_textShadow);
        this.webkitTextStrokeWidth = StyleReader.text(cs, ID_webkitTextStrokeWidth);
        this.webkitTextStrokeColor = StyleReader.text(cs, ID_webkitTextStrokeColor);
        this.cursor = StyleReader.text(cs, ID_cursor);
        this.pointerEvents = StyleReader.keyword(cs, ID_pointerEvents, com.nishiyu.enderframelib.core.css.model.CssKeywords.PointerEvents.class);
        this.hasColor = StyleReader.isDeclared(cs, ID_color);
    }

    /** 宣言入力から、この群の値を取り出す。 */
    public static InheritedPaintStyle of(StyleReader.Source cs) {
        return new InheritedPaintStyle(cs);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof InheritedPaintStyle o)) return false;
        return java.util.Objects.equals(emptyCells, o.emptyCells)
                && color == o.color
                && java.util.Objects.equals(accentColor, o.accentColor)
                && java.util.Objects.equals(textDecoration, o.textDecoration)
                && java.util.Objects.equals(textShadow, o.textShadow)
                && java.util.Objects.equals(webkitTextStrokeWidth, o.webkitTextStrokeWidth)
                && java.util.Objects.equals(webkitTextStrokeColor, o.webkitTextStrokeColor)
                && java.util.Objects.equals(cursor, o.cursor)
                && pointerEvents == o.pointerEvents
                && hasColor == o.hasColor;
    }

    @Override
    public int hashCode() {
        int h = 9;
        h = h * 31 + java.util.Objects.hashCode(emptyCells);
        h = h * 31 + color;
        h = h * 31 + java.util.Objects.hashCode(accentColor);
        h = h * 31 + java.util.Objects.hashCode(textDecoration);
        h = h * 31 + java.util.Objects.hashCode(textShadow);
        h = h * 31 + java.util.Objects.hashCode(webkitTextStrokeWidth);
        h = h * 31 + java.util.Objects.hashCode(webkitTextStrokeColor);
        h = h * 31 + java.util.Objects.hashCode(cursor);
        h = h * 31 + System.identityHashCode(pointerEvents);
        return h;
    }
}
