package com.nishiyu.enderframelib.core.css;

/**
 * 生成物（{@code scripts/GenStyleGroups.java}。手で直さない）: 継承されないプロパティの型付き不変構造体。
 * 値は構築時に 1 回だけ解いた型付きのフィールドで、文字列を読み直さない。{@link #equals} は全フィールドの値等価。
 */
public final class TableStyle {
    static final int ID_tableLayout = ComputedStyle.idOf("table-layout");

    /** 宣言が 1 つも無いときの共有インスタンス（初期値だけ）。ID_* の初期化より後に置く。 */
    public static final TableStyle DEFAULT = new TableStyle(null);

    /** {@code table-layout}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.TableLayout tableLayout;

    private TableStyle(StyleReader.Source cs) {
        this.tableLayout = StyleReader.keyword(cs, ID_tableLayout, com.nishiyu.enderframelib.core.css.model.CssKeywords.TableLayout.class);
    }

    /** 宣言入力から、この群の値を取り出す。 */
    public static TableStyle of(StyleReader.Source cs) {
        return new TableStyle(cs);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TableStyle o)) return false;
        return tableLayout == o.tableLayout;
    }

    @Override
    public int hashCode() {
        int h = 1;
        h = h * 31 + System.identityHashCode(tableLayout);
        return h;
    }
}
