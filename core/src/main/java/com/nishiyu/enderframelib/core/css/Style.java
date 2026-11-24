package com.nishiyu.enderframelib.core.css;

import java.util.Map;

/**
 * 算出スタイル（Phase M1）。6 つの型付き不変群と任意のカスタムプロパティ表の束。
 *
 * <ul>
 *   <li>{@link #inherited}: 継承するプロパティ全部。子の継承プロパティが親と同じ値なら、子は親のインスタンスを
 *       そのまま参照する（{@code child.inherited == parent.inherited}）。</li>
 *   <li>それ以外の群は、宣言が無ければ静的な {@code DEFAULT} を共有する。</li>
 *   <li>{@link #custom}: 登録外の宣言（{@code --*} など）がある要素だけ非 null。</li>
 * </ul>
 *
 * <p>どの群も不変で、{@code ==} と値等価（{@code equals}）の両方で照合できる。レイアウト・描画は文字列を読まず、
 * この型の final フィールドだけを引く。</p>
 */
public final class Style {
    public final InheritedStyle inherited;
    public final BoxGeomStyle box;
    public final VisualStyle visual;
    public final FlexGridStyle flexGrid;
    public final TableStyle table;
    public final RareStyle rare;
    public final CustomProperties custom;
    /** 導出値（構築時に 1 回だけ作る不変の値）。 */
    public final StyleFacts facts;

    private Style(InheritedStyle inherited, BoxGeomStyle box, VisualStyle visual, FlexGridStyle flexGrid, TableStyle table,
                  RareStyle rare, CustomProperties custom, boolean usesViewportUnits) {
        this.inherited = inherited;
        this.box = box;
        this.visual = visual;
        this.flexGrid = flexGrid;
        this.table = table;
        this.rare = rare;
        this.custom = custom;
        this.facts = new StyleFacts(this, usesViewportUnits);
    }

    /** 宣言が 1 つも無い、初期値だけのスタイル。 */
    public static final Style DEFAULT = new Style(InheritedStyle.DEFAULT, BoxGeomStyle.DEFAULT, VisualStyle.DEFAULT,
            FlexGridStyle.DEFAULT, TableStyle.DEFAULT, RareStyle.DEFAULT, null, false);

    /**
     * 算出スタイルから型付きスタイルを作る。{@code parent} が非 null で継承群が同じ値なら親の群を共有する。
     * 継承する登録外の宣言（{@code --*}）も、親と同じ中身なら親の表を共有する。
     */
    public static Style of(ComputedStyle cs, Style parent) {
        InheritedStyle inh = InheritedStyle.of(cs);
        if (parent != null && inh.equals(parent.inherited)) inh = parent.inherited;
        else if (inh.equals(InheritedStyle.DEFAULT)) inh = InheritedStyle.DEFAULT;
        BoxGeomStyle box = BoxGeomStyle.of(cs);
        if (box.equals(BoxGeomStyle.DEFAULT)) box = BoxGeomStyle.DEFAULT;
        VisualStyle visual = VisualStyle.of(cs);
        if (visual.equals(VisualStyle.DEFAULT)) visual = VisualStyle.DEFAULT;
        FlexGridStyle fg = FlexGridStyle.of(cs);
        if (fg.equals(FlexGridStyle.DEFAULT)) fg = FlexGridStyle.DEFAULT;
        TableStyle table = TableStyle.of(cs);
        if (table.equals(TableStyle.DEFAULT)) table = TableStyle.DEFAULT;
        RareStyle rare = RareStyle.of(cs);
        if (rare.equals(RareStyle.DEFAULT)) rare = RareStyle.DEFAULT;
        // 添字を持たない宣言（登録外・--*）は、登録済みの宣言のあとに並ぶ
        Map<String, String> extras = null;
        for (Map.Entry<String, String> e : cs.asMap().entrySet()) {
            if (ComputedStyle.idOf(e.getKey()) != -1) continue;
            if (extras == null) extras = new java.util.LinkedHashMap<>();
            extras.put(e.getKey(), e.getValue());
        }
        CustomProperties custom = null;
        if (extras != null) {
            custom = new CustomProperties(extras);
            if (parent != null && custom.equals(parent.custom)) custom = parent.custom;
        }
        return new Style(inh, box, visual, fg, table, rare, custom, cs.usesViewportUnits());
    }

    /** この要素のフォントサイズ（px）。 */
    public float fontSizePx() {
        return inherited.fontSize;
    }

    /** {@code len} を px へ解く。{@code exprs} は {@code len} を持つ群の {@code exprs}。{@code em} はこの要素のフォントサイズ基準。 */
    public float resolve(long len, CssExprs exprs, float percentBase, float vw, float vh) {
        return CssLength.resolve(len, exprs, percentBase, inherited.fontSize, inherited.rootFontSizePx, vw, vh);
    }

    /** 宣言が無い（null）ときだけ既定値を返す（文字列のまま持つ値の読み手が使う）。 */
    public static String or(String declared, String fallback) {
        return declared != null ? declared : fallback;
    }

    /** 単位なしの数値。解釈できない値（NaN）は {@code fallback}。 */
    public static float num(float value, float fallback) {
        return Float.isNaN(value) ? fallback : value;
    }

    /** 任意の群の長さを px へ解く（{@code exprs} は、その長さを持つ群の式表）。 */
    public float px(long len, CssExprs exprs, float percentBase, float rootW, float rootH) {
        return CssLength.resolve(len, exprs, percentBase, inherited.fontSize, inherited.rootFontSizePx, rootW, rootH);
    }

    /** {@link BoxGeomStyle} の長さ（余白・パディング・枠線の幅・寸法）を px へ解く。{@code percentBase} は % の基準。 */
    public float boxPx(long len, float percentBase, float rootW, float rootH) {
        return CssLength.resolve(len, box.exprs, percentBase, inherited.fontSize, inherited.rootFontSizePx, rootW, rootH);
    }

    /** {@link FlexGridStyle} の長さ（gap・flex-basis）を px へ解く。 */
    public float gridPx(long len, float percentBase, float rootW, float rootH) {
        return CssLength.resolve(len, flexGrid.exprs, percentBase, inherited.fontSize, inherited.rootFontSizePx, rootW, rootH);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Style o)) return false;
        return inherited.equals(o.inherited) && box.equals(o.box) && visual.equals(o.visual) && flexGrid.equals(o.flexGrid)
                && table.equals(o.table) && rare.equals(o.rare) && java.util.Objects.equals(custom, o.custom);
    }

    @Override
    public int hashCode() {
        int h = inherited.hashCode();
        h = h * 31 + box.hashCode();
        h = h * 31 + visual.hashCode();
        h = h * 31 + flexGrid.hashCode();
        h = h * 31 + table.hashCode();
        h = h * 31 + rare.hashCode();
        return h * 31 + java.util.Objects.hashCode(custom);
    }
}
