package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.kernel.style.KeywordMatch;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.model.WhiteSpace;
import com.nishiyu.enderframelib.core.text.model.WordBreak;
import java.util.Locale;

/**
 * 型付きスタイル（{@link Style}）から決まる、レイアウトが繰り返し読む導出値。{@link Style} の構築時に 1 回だけ作り、以後は不変
 * （文字列を読み直さない。旧版は算出スタイルの文字列から怠惰に導出していた）。
 */
public final class StyleFacts {
    public final Overflow overflowX, overflowY;
    public final int fontWeight;
    /** {@code line-height} の種類（{@code ComputedStyle.LH_*}）と数値。解釈できない値は normal。 */
    public final byte lineHeightKind;
    public final float lineHeightValue;
    /** size containment（{@code contain} / {@code container-type}）が横・縦の中身の寄与を断つか。 */
    public final boolean containsInlineSize, containsBlockSize;
    /** {@code float} が none 以外・{@code display: none}・{@code overflow} がスクロールを作る。 */
    public final boolean floating, displayNone, scrollContainer;
    /** {@code width} / {@code height} が確定した長さ（auto・%・内在サイズのキーワードでない）。 */
    public final boolean definiteWidth, definiteHeight;
    /** {@code content-visibility: auto} / {@code hidden}。 */
    public final boolean contentVisibilityAuto, contentVisibilityHidden;
    /** 有効な containment のビット（{@code ComputedStyle.CONTAIN_*}。{@code contain} と {@code content-visibility} の和）。 */
    public final int containment;

    /** {@code width} / {@code height} が {@code auto}（宣言なしを含む）か、{@code %} を含むか。 */
    public final boolean widthAuto, heightAuto, widthPercent, heightPercent;
    /**
     * 横方向の寸法に効く割合（{@code %}）がある（width / min-width / max-width / 余白 / padding / inset のどれか）。割合は親の幅を基準に
     * 解くので、親が渡す幅が変わると箱の寸法が変わる。
     */
    public final boolean percentGeometry;
    /**
     * 高さ方向の割合・式（{@code %} / {@code calc()} など）を、height / min-height / max-height / top / bottom / flex-basis /
     * row-gap / grid-template-rows / grid-auto-rows のどれにも持たない。持たなければ、親が渡す「高さの % の基準」は
     * この箱自身の寸法の決まり方に効かない（子孫が持たなければ、子孫の寸法にも効かない）。
     */
    public final boolean blockPercentFree;
    /** {@code width} の内在サイズのキーワードの種類（{@code IW_*}）。キーワードでなければ {@link #IW_NONE}。 */
    public final byte intrinsicWidthKind;
    /** {@code width: fit-content(<長さ>)} の引数（前後の空白を除く小文字）。その形でなければ null。 */
    public final String fitContentArg;

    public static final byte IW_NONE = 0, IW_MIN_CONTENT = 1, IW_MAX_CONTENT = 2, IW_FIT_CONTENT = 3, IW_FIT_CONTENT_FN = 4;

    /** {@code width} が内容から決まるキーワード（{@code min-content} / {@code max-content} / {@code fit-content}）か。 */
    public boolean hasIntrinsicWidth() {
        return intrinsicWidthKind != IW_NONE;
    }

    /** {@code margin-left} / {@code margin-right} が {@code auto}。 */
    public final boolean marginLeftAuto, marginRightAuto;
    /** 四辺の余白が {@code auto}（flex が読む）。 */
    public final boolean autoMarginT, autoMarginR, autoMarginB, autoMarginL;
    /** {@code direction: rtl}。 */
    public final boolean rtl;
    /** {@code box-sizing: border-box}。 */
    public final boolean borderBox;
    /**
     * flex の揃え（{@code align-items} / {@code align-self}）。normal・auto・legacy は {@link CssKeywords.Alignment#STRETCH}、
     * first/last baseline は {@link CssKeywords.Alignment#BASELINE} に寄せてある。{@code align-self: auto} のときは {@link #alignSelfAuto}。
     */
    public final CssKeywords.Alignment alignItems, alignSelf;
    public final boolean alignSelfAuto;
    /** 独立した整形コンテキストを、スタイルだけで作るか（float・layout/paint containment・スクロールコンテナ）。 */
    public final boolean bfcByStyle;
    /** 値のどれかがビューポート／コンテナ基準の単位を使う。 */
    public final boolean usesViewportUnits;
    /**
     * {@code position} に依らず、{@code absolute} と {@code fixed} の両方の子孫の包含ブロックになる（{@code transform}・{@code filter}・
     * layout / paint containment）。{@code transform} と {@code filter} は描画だけの群にあるが、この事実はレイアウトが読むので
     * {@link Style#sameLayout} が照合する。
     */
    public final boolean containsFixed;
    /**
     * 箱自身の横方向の寸法が、親の幅・ビューポートの幅に依らない: {@code width} が % でない確定した長さ、{@code min/max-width}・
     * 余白・パディング・オフセットに % がなく、横の余白も {@code auto} でない。
     */
    public final boolean horizontalFixed;
    /** {@code width: auto} で横の寸法に % / auto の余白 / ビューポート単位が無い（内容で決まり得る箱）。 */
    public final boolean fitCandidate;
    /** 余白・パディング・オフセットに % / auto / ビューポート単位が無い（親が幅を決めて渡すなら、使える幅を読まない）。 */
    public final boolean edgesAvailableFree;

    /** 整形に使う、スタイルだけで決まる値。 */
    public final WhiteSpace whiteSpace;
    public final WordBreak wordBreak;
    public final boolean overflowWrapBreaks, ellipsis;
    /** 文字の測定に使う書体の指定（同じスタイルなら同じ不変の値）。 */
    public final FontStyle fontStyle;

    StyleFacts(Style s, boolean usesViewportUnits) {
        InheritedStyle in = s.inherited;
        BoxGeomStyle b = s.box;
        FlexGridStyle fg = s.flexGrid;
        RareStyle r = s.rare;

        int bits = 0;
        if (r.contain != null && !r.contain.isBlank()) {
            for (String token : r.contain.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                switch (token) {
                    case "strict" -> bits |= ComputedStyle.CONTAIN_SIZE | ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT
                            | ComputedStyle.CONTAIN_STYLE;
                    case "content" -> bits |= ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT | ComputedStyle.CONTAIN_STYLE;
                    case "size" -> bits |= ComputedStyle.CONTAIN_SIZE;
                    case "inline-size" -> bits |= ComputedStyle.CONTAIN_INLINE_SIZE;
                    case "layout" -> bits |= ComputedStyle.CONTAIN_LAYOUT;
                    case "paint" -> bits |= ComputedStyle.CONTAIN_PAINT;
                    case "style" -> bits |= ComputedStyle.CONTAIN_STYLE;
                    default -> { }
                }
            }
        }
        CssKeywords.ContentVisibility cv = r.contentVisibility;
        switch (cv) {
            case HIDDEN -> bits |= ComputedStyle.CONTAIN_SIZE | ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT
                    | ComputedStyle.CONTAIN_STYLE;
            case AUTO -> bits |= ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT | ComputedStyle.CONTAIN_STYLE;
            default -> { }
        }
        containment = bits;
        contentVisibilityAuto = cv == CssKeywords.ContentVisibility.AUTO;
        contentVisibilityHidden = cv == CssKeywords.ContentVisibility.HIDDEN;

        // paint containment は中身を padding box で切る（overflow: clip と同じ。スクロールコンテナにはならない）
        boolean paintContained = (bits & ComputedStyle.CONTAIN_PAINT) != 0;
        overflowX = b.overflowX == Overflow.VISIBLE && paintContained ? Overflow.CLIP : b.overflowX;
        overflowY = b.overflowY == Overflow.VISIBLE && paintContained ? Overflow.CLIP : b.overflowY;
        scrollContainer = overflowX.scrolls() || overflowY.scrolls();
        fontWeight = in.fontWeight;
        lineHeightKind = in.lineHeightKind;
        lineHeightValue = in.lineHeightValue;
        containsInlineSize = (bits & (ComputedStyle.CONTAIN_SIZE | ComputedStyle.CONTAIN_INLINE_SIZE)) != 0
                || r.containerType.containsInlineSize();
        containsBlockSize = (bits & ComputedStyle.CONTAIN_SIZE) != 0 || r.containerType.containsBlockSize();
        floating = b.floatValue != CssKeywords.FloatSide.NONE;
        displayNone = "none".equals(b.display);
        this.usesViewportUnits = usesViewportUnits;
        containsFixed = isSet(s.visual.transform) || isSet(s.visual.filter) || (bits & (ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT)) != 0;

        long w = b.width, h = b.height;
        definiteWidth = definite(w, b.exprs);
        definiteHeight = definite(h, b.exprs);
        widthAuto = CssLength.isAutoOrUndeclared(w);
        heightAuto = CssLength.isAutoOrUndeclared(h);
        widthPercent = hasPercent(w, b.exprs);
        heightPercent = hasPercent(h, b.exprs);
        byte iw = IW_NONE;
        String fitArg = null;
        if (CssLength.isKeyword(w, CssLength.KW_MIN_CONTENT)) iw = IW_MIN_CONTENT;
        else if (CssLength.isKeyword(w, CssLength.KW_MAX_CONTENT)) iw = IW_MAX_CONTENT;
        else if (CssLength.isKeyword(w, CssLength.KW_FIT_CONTENT)) iw = IW_FIT_CONTENT;
        else if (CssLength.isCalc(w)) {
            String kw = b.exprs.raw(w).toLowerCase(Locale.ROOT);
            if (kw.startsWith("fit-content(")) {
                iw = IW_FIT_CONTENT_FN;
                // 閉じ括弧が無い形は引数を持たない
                if (kw.endsWith(")")) fitArg = kw.substring("fit-content(".length(), kw.length() - 1).trim();
            }
        }
        intrinsicWidthKind = iw;
        fitContentArg = fitArg;

        autoMarginT = CssLength.isAuto(b.marginTop);
        autoMarginR = CssLength.isAuto(b.marginRight);
        autoMarginB = CssLength.isAuto(b.marginBottom);
        autoMarginL = CssLength.isAuto(b.marginLeft);
        marginLeftAuto = autoMarginL;
        marginRightAuto = autoMarginR;
        borderBox = b.boxSizing == CssKeywords.BoxSizing.BORDER_BOX;
        alignItems = flexAlignment(fg.alignItems);
        alignSelfAuto = fg.alignSelf == CssKeywords.Alignment.AUTO;
        alignSelf = flexAlignment(fg.alignSelf);

        boolean horizontalWriting = in.writingMode == null || KeywordMatch.trimmedStartsWith(in.writingMode, "horizontal");
        BoxGeomStyle bx = b;
        boolean edgePercent = hasPercent(bx.minWidth, bx.exprs) || hasPercent(bx.maxWidth, bx.exprs)
                || hasPercent(bx.marginLeft, bx.exprs) || hasPercent(bx.marginRight, bx.exprs)
                || hasPercent(bx.marginTop, bx.exprs) || hasPercent(bx.marginBottom, bx.exprs)
                || hasPercent(bx.paddingLeft, bx.exprs) || hasPercent(bx.paddingRight, bx.exprs)
                || hasPercent(bx.paddingTop, bx.exprs) || hasPercent(bx.paddingBottom, bx.exprs)
                || hasPercent(bx.left, bx.exprs) || hasPercent(bx.right, bx.exprs)
                || hasPercent(bx.top, bx.exprs) || hasPercent(bx.bottom, bx.exprs);
        percentGeometry = widthPercent || edgePercent;
        boolean anyAutoMargin = autoMarginT || autoMarginR || autoMarginB || autoMarginL;
        horizontalFixed = definiteWidth && !widthAuto && !widthPercent && intrinsicWidthKind == IW_NONE && !usesViewportUnits
                && !anyAutoMargin && horizontalWriting && !edgePercent;
        long basis = fg.flexBasis;
        boolean basisPercentOrCalc = CssLength.isPercent(basis)
                || (CssLength.isCalc(basis) && (fg.exprs.raw(basis).indexOf('%') >= 0 || fg.exprs.raw(basis).contains("calc")));
        edgesAvailableFree = !usesViewportUnits && !percentGeometry && !basisPercentOrCalc
                && noBlockPercent(fg.columnGap, fg.exprs) && !anyAutoMargin && horizontalWriting;
        fitCandidate = edgesAvailableFree && widthAuto && intrinsicWidthKind == IW_NONE;
        blockPercentFree = noBlockPercent(h, b.exprs) && noBlockPercent(b.minHeight, b.exprs) && noBlockPercent(b.maxHeight, b.exprs)
                && noBlockPercent(b.top, b.exprs) && noBlockPercent(b.bottom, b.exprs) && noBlockPercent(basis, fg.exprs)
                && noBlockPercent(fg.rowGap, fg.exprs) && noBlockText(fg.gridTemplateRows) && noBlockText(fg.gridAutoRows);
        rtl = KeywordMatch.is(in.direction, "rtl");
        bfcByStyle = floating || (bits & (ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT)) != 0 || scrollContainer;
        whiteSpace = WhiteSpace.of(in.whiteSpace);
        wordBreak = WordBreak.of(in.wordBreak);
        overflowWrapBreaks = in.overflowWrap != CssKeywords.OverflowWrap.NORMAL;
        ellipsis = b.textOverflow == CssKeywords.TextOverflow.ELLIPSIS;
        fontStyle = in.fontStyleSpec;
    }

    /** % を含む長さ（単位が %、または式の原文に % を含む）か。 */
    private static boolean hasPercent(long v, CssExprs exprs) {
        return CssLength.hasPercent(v, exprs);
    }

    /** 高さ方向の割合・式を持たない（% の長さでも式でもない）。 */
    private static boolean noBlockPercent(long v, CssExprs exprs) {
        return !CssLength.isPercent(v) && !CssLength.isCalc(v) && !CssLength.isJunkPercent(v);
    }

    private static boolean noBlockText(String v) {
        return v == null || (v.indexOf('%') < 0 && v.indexOf('(') < 0);
    }

    /** {@code width} / {@code height} が確定した長さか（auto・% を含む・内在サイズのキーワードは確定でない）。 */
    private static boolean definite(long v, CssExprs exprs) {
        if (!CssLength.isDeclared(v)) return false;
        if (CssLength.isKeyword(v)) {
            return !CssLength.isAuto(v) && !CssLength.isKeyword(v, CssLength.KW_EMPTY) && !CssLength.isJunkPercent(v)
                    && !CssLength.isKeyword(v, CssLength.KW_MIN_CONTENT)
                    && !CssLength.isKeyword(v, CssLength.KW_MAX_CONTENT) && !CssLength.isKeyword(v, CssLength.KW_FIT_CONTENT);
        }
        if (CssLength.isCalc(v)) {
            String raw = exprs.raw(v).trim().toLowerCase(Locale.ROOT);
            return !raw.isEmpty() && !raw.contains("%") && !raw.startsWith("fit-content(");
        }
        return !CssLength.isPercent(v);
    }

    /** 揃えの値を flex の配置に寄せる（{@code normal} は stretch、{@code first/last baseline} は baseline）。 */
    static CssKeywords.Alignment flexAlignment(CssKeywords.Alignment v) {
        return switch (v) {
            case NORMAL, AUTO, LEGACY -> CssKeywords.Alignment.STRETCH;
            case FIRST_BASELINE, LAST_BASELINE -> CssKeywords.Alignment.BASELINE;
            default -> v;
        };
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank() && !"none".equalsIgnoreCase(value.trim());
    }
}
