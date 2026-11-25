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
    /** 2 つの {@link Style} の差分を 1 つずつ受け取るコールバック。 */
    public interface DiffVisitor {
        void changed(String name, boolean paintOnly, boolean inherited);
    }

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

    /** 既存の算出スタイルから親との継承群共有だけを適用した {@link Style} を得る。 */
    public static Style of(ComputedStyle cs, Style parent) {
        if (cs == null) return DEFAULT;
        Style s = cs.typed();
        if (parent != null && s.inherited != parent.inherited && s.inherited.equals(parent.inherited)) {
            CustomProperties c = s.custom != null && s.custom.equals(parent.custom) ? parent.custom : s.custom;
            return new Style(parent.inherited, s.box, s.visual, s.flexGrid, s.table, s.rare, c, s.facts.usesViewportUnits);
        }
        return s;
    }

    /** 型付き入力（{@link StyleTable} やマップ経由の宣言）から {@link Style} を直接構築する。 */
    static Style fromSource(StyleReader.Source src, Map<String, String> extras, boolean usesViewportUnits, Style parent) {
        return fromSource(src, extras, usesViewportUnits, parent, 63);
    }

    /** {@code groupMask} で宣言の有る群（bit 0..5）だけを構築し、未宣言の群は {@code DEFAULT} / 親を無確保で共有する。 */
    static Style fromSource(StyleReader.Source src, Map<String, String> extras, boolean usesViewportUnits, Style parent, int groupMask) {
        InheritedStyle inh;
        if ((groupMask & 1) == 0) {
            float elFont = src.fontSizePx(), rFont = src.rootFontSizePx();
            if (parent != null && Float.floatToIntBits(elFont) == Float.floatToIntBits(parent.inherited.fontSize)
                    && Float.floatToIntBits(rFont) == Float.floatToIntBits(parent.inherited.rootFontSizePx)) {
                inh = parent.inherited;
            } else if (parent == null && Float.floatToIntBits(elFont) == Float.floatToIntBits(ComputedStyle.DEFAULT_FONT_SIZE)
                    && Float.floatToIntBits(rFont) == Float.floatToIntBits(ComputedStyle.DEFAULT_FONT_SIZE)) {
                inh = InheritedStyle.DEFAULT;
            } else {
                inh = InheritedStyle.of(src);
            }
        } else {
            inh = InheritedStyle.of(src);
            if (parent != null && inh.equals(parent.inherited)) inh = parent.inherited;
            else if (inh.equals(InheritedStyle.DEFAULT)) inh = InheritedStyle.DEFAULT;
        }
        BoxGeomStyle box = BoxGeomStyle.DEFAULT;
        if ((groupMask & 2) != 0) {
            BoxGeomStyle b = BoxGeomStyle.of(src);
            if (!b.equals(BoxGeomStyle.DEFAULT)) box = b;
        }
        VisualStyle visual = VisualStyle.DEFAULT;
        if ((groupMask & 4) != 0) {
            VisualStyle v = VisualStyle.of(src);
            if (!v.equals(VisualStyle.DEFAULT)) visual = v;
        }
        FlexGridStyle fg = FlexGridStyle.DEFAULT;
        if ((groupMask & 8) != 0) {
            FlexGridStyle f = FlexGridStyle.of(src);
            if (!f.equals(FlexGridStyle.DEFAULT)) fg = f;
        }
        TableStyle table = TableStyle.DEFAULT;
        if ((groupMask & 16) != 0) {
            TableStyle t = TableStyle.of(src);
            if (!t.equals(TableStyle.DEFAULT)) table = t;
        }
        RareStyle rare = RareStyle.DEFAULT;
        if ((groupMask & 32) != 0) {
            RareStyle r = RareStyle.of(src);
            if (!r.equals(RareStyle.DEFAULT)) rare = r;
        }
        CustomProperties custom = null;
        if (extras != null && !extras.isEmpty()) {
            custom = new CustomProperties(new java.util.LinkedHashMap<>(extras));
            if (parent != null && custom.equals(parent.custom)) custom = parent.custom;
        }
        return new Style(inh, box, visual, fg, table, rare, custom, usesViewportUnits);
    }

    /** テキストの節用: 親の継承群と {@code --*} をそのまま共有し、他の 5 群は {@code DEFAULT} を指す。 */
    /** 問い合わせコンテナの種類（{@code container-type}。M2b-5 の新経路の入力側が読む）。 */
    public com.nishiyu.enderframelib.core.css.model.ContainerType containerType() {
        var t = rare.containerType;
        return t == com.nishiyu.enderframelib.core.css.model.CssKeywords.ContainerType.SIZE ? com.nishiyu.enderframelib.core.css.model.ContainerType.SIZE
                : t == com.nishiyu.enderframelib.core.css.model.CssKeywords.ContainerType.INLINE_SIZE
                ? com.nishiyu.enderframelib.core.css.model.ContainerType.INLINE_SIZE
                : com.nishiyu.enderframelib.core.css.model.ContainerType.NORMAL;
    }

    public static Style forTextChild(Style parent) {
        if (parent == null || parent == DEFAULT) return DEFAULT;
        CustomProperties custom = parent.custom != null ? parent.custom.onlyCustom() : null;
        return new Style(parent.inherited, BoxGeomStyle.DEFAULT, VisualStyle.DEFAULT, FlexGridStyle.DEFAULT,
                TableStyle.DEFAULT, RareStyle.DEFAULT, custom, inheritedUsesViewport(parent.inherited, custom));
    }

    /** 匿名生成箱用: 親の継承群と {@code --*} を共有し、{@code display} だけを指定値に差し替える。 */
    static Style forAnonymous(Style parent, BoxGeomStyle anonBox) {
        Style p = parent != null ? parent : DEFAULT;
        CustomProperties custom = p.custom != null ? p.custom.onlyCustom() : null;
        return new Style(p.inherited, anonBox, VisualStyle.DEFAULT, FlexGridStyle.DEFAULT,
                TableStyle.DEFAULT, RareStyle.DEFAULT, custom, inheritedUsesViewport(p.inherited, custom));
    }

    /**
     * 値が同じ群を {@code prev} のインスタンスへ寄せた {@link Style}（Engine v3 M2a の {@code BoxTreeCompiler} が使う）。
     * 全体が同値なら {@code prev} そのもの。解き直した要素の継承群が前回と同値なら、子は前回と同じ継承群の参照を受け取り、
     * 子孫の作り直しが参照比較（{@code ==}）で打ち切られる。
     */
    public Style shareEqualGroups(Style prev) {
        if (prev == null || prev == this) return this;
        if (equals(prev)) return prev;
        InheritedStyle inh = inherited.equals(prev.inherited) ? prev.inherited : inherited;
        BoxGeomStyle b = box.equals(prev.box) ? prev.box : box;
        VisualStyle v = visual.equals(prev.visual) ? prev.visual : visual;
        FlexGridStyle fg = flexGrid.equals(prev.flexGrid) ? prev.flexGrid : flexGrid;
        TableStyle t = table.equals(prev.table) ? prev.table : table;
        RareStyle r = rare.equals(prev.rare) ? prev.rare : rare;
        CustomProperties c = java.util.Objects.equals(custom, prev.custom) ? prev.custom : custom;
        if (inh == inherited && b == box && v == visual && fg == flexGrid && t == table && r == rare && c == custom) return this;
        return new Style(inh, b, v, fg, t, r, c, facts.usesViewportUnits);
    }

    /** 非継承プロパティ 5 群と非継承カスタムプロパティが同じか（{@code PaintOrder.StyleFacts} 引き継ぎ用）。 */
    boolean sameNonInherited(Style o) {
        if (this == o) return true;
        return o != null && box.equals(o.box) && visual.equals(o.visual) && flexGrid.equals(o.flexGrid)
                && table.equals(o.table) && rare.equals(o.rare)
                && CustomProperties.sameNonInherited(custom, o.custom);
    }

    /** 継承プロパティと {@code --*} のどれかがビューポート／コンテナ単位・{@code var()}/{@code env()} を使うか。 */
    static boolean inheritedUsesViewport(InheritedStyle inh, CustomProperties custom) {
        if (CssLength.usesViewport(inh.letterSpacing, inh.exprs)
                || CssLength.usesViewport(inh.wordSpacing, inh.exprs)
                || CssLength.usesViewport(inh.textIndent, inh.exprs)) return true;
        for (String s : new String[]{inh.fontFamily, inh.fontVariant, inh.fontKerning, inh.textAlignLast,
                inh.textJustify, inh.tabSize, inh.textWrap, inh.textWrapStyle, inh.whiteSpace, inh.wordBreak,
                inh.direction, inh.accentColor, inh.textDecoration, inh.textShadow, inh.webkitTextStrokeWidth,
                inh.webkitTextStrokeColor, inh.writingMode, inh.textOrientation, inh.cursor}) {
            if (s != null && StyleResolver.valueDependsOnViewport(s)) return true;
        }
        if (custom != null) {
            for (String v : custom.asMap().values()) {
                if (v != null && StyleResolver.valueDependsOnViewport(v)) return true;
            }
        }
        return false;
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

    /** {@link VisualStyle} の長さ（輪郭線の幅・オフセット）を px へ解く。 */
    public float visPx(long len, float percentBase, float rootW, float rootH) {
        return CssLength.resolve(len, visual.exprs, percentBase, inherited.fontSize, inherited.rootFontSizePx, rootW, rootH);
    }

    /** 枠線の色（{@code 0=top, 1=right, 2=bottom, 3=left}）。{@code currentcolor} / 未宣言は {@code fallbackTextColor}。 */
    public int borderColor(int side, int fallbackTextColor) {
        if ((visual.currentColorMask & (1 << side)) != 0) return fallbackTextColor;
        return switch (side) {
            case 0 -> visual.borderTopColor;
            case 1 -> visual.borderRightColor;
            case 2 -> visual.borderBottomColor;
            default -> visual.borderLeftColor;
        };
    }

    /** {@code outline-color}（{@code currentcolor} / {@code invert} / 未宣言は {@code fallbackTextColor}）。 */
    public int outlineColor(int fallbackTextColor) {
        return (visual.currentColorMask & 16) != 0 ? fallbackTextColor : visual.outlineColor;
    }

    /** {@code column-rule-color}（{@code currentcolor} / 未宣言は {@code fallbackTextColor}）。 */
    public int columnRuleColor(int fallbackTextColor) {
        return rare.columnRuleColorCurrent ? fallbackTextColor : rare.columnRuleColor;
    }

    /** テストや埋め込み境界からの {@code Map<String, String>} を {@link Style} へ寄せる。 */
    public static Style ofMap(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) return DEFAULT;
        return ComputedStyle.of(properties, ComputedStyle.DEFAULT_FONT_SIZE, ComputedStyle.DEFAULT_FONT_SIZE).typed();
    }

    /** 名前で CSS 文字列表現を取り出す（内部・インスペクタ・アニメーション用）。 */
    public String cssProperty(String name) {
        return StyleProperties.get(this, name);
    }

    /** CSSOM {@code getComputedStyle} 形式の文字列表現（色は {@code rgb(...)}）。 */
    public String cssomProperty(String name) {
        return StyleProperties.getCssom(this, name);
    }

    /** 宣言・設定された CSS プロパティの読み取り専用の表（遅延構築・テスト・CSSOM 全件列挙用）。 */
    public Map<String, String> toCssMap() {
        return StyleProperties.toMap(this);
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
