package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.kernel.style.KeywordMatch;
import com.nishiyu.enderframelib.core.css.model.Overflow;

/** 算出スタイルから決まる、レイアウトが繰り返し読む値。 */
public final class StyleFacts {
    public final Overflow overflowX, overflowY;
    public final int fontWeight;
    /** {@code line-height} の種類（{@code LH_*}）と数値。解釈できない値は normal。 */
    public final byte lineHeightKind;
    public final float lineHeightValue;
    /** size containment（{@code contain} / {@code container-type}）が横・縦の中身の寄与を断つか。 */
    public final boolean containsInlineSize, containsBlockSize;
    /** {@code float} が none 以外・{@code display: none}・{@code overflow} がスクロールを作る。 */
    public final boolean floating, displayNone, scrollContainer;
    /** {@code width} / {@code height} が確定した長さ（auto・%・内在サイズのキーワードでない）。 */
    public final boolean definiteWidth, definiteHeight;

    StyleFacts(Overflow overflowX, Overflow overflowY, int fontWeight, byte lineHeightKind, float lineHeightValue,
          boolean containsInlineSize, boolean containsBlockSize, boolean floating, boolean displayNone,
          boolean definiteWidth, boolean definiteHeight) {
        this.overflowX = overflowX;
        this.overflowY = overflowY;
        this.fontWeight = fontWeight;
        this.lineHeightKind = lineHeightKind;
        this.lineHeightValue = lineHeightValue;
        this.containsInlineSize = containsInlineSize;
        this.containsBlockSize = containsBlockSize;
        this.floating = floating;
        this.displayNone = displayNone;
        this.definiteWidth = definiteWidth;
        this.definiteHeight = definiteHeight;
        this.scrollContainer = overflowX.scrolls() || overflowY.scrolls();
    }

    // ---- 測定・整形が箱ごとに読む宣言の、前もって解いた値（文字列を読み直さない）----

    /** {@code width} / {@code height} の宣言（前後の空白を除く。無ければ {@code auto}）の性質。宣言の文字列自体は持たない。 */
    public boolean widthAuto, heightAuto, widthPercent, heightPercent;
    /**
     * 横方向の寸法に効く割合（{@code %}）がある（width / min-width / max-width / 余白 / padding / inset のどれか）。割合は親の幅を基準に
     * 解くので、親が渡す幅が変わると箱の寸法が変わる。
     */
    public boolean percentGeometry;
    /**
     * 高さ方向の割合・式（{@code %} / {@code calc()} など）を、height / min-height / max-height / top / bottom / flex-basis /
     * row-gap / grid-template-rows / grid-auto-rows のどれにも持たない。持たなければ、親が渡す「高さの % の基準」は
     * この箱自身の寸法の決まり方に効かない（子孫が持たなければ、子孫の寸法にも効かない）。
     */
    public boolean blockPercentFree;
    /** {@code height} の宣言があるか（{@code auto} の明示も含む）。 */
    public boolean hasHeightDeclaration;
    /** {@code width} の内在サイズのキーワードの種類（{@code IW_*}）。キーワードでなければ {@link #IW_NONE}。 */
    public byte intrinsicWidthKind;
    /** {@code width: fit-content(<長さ>)} の引数（前後の空白を除く小文字）。その形でなければ null。 */
    public String fitContentArg;

    private static boolean noBlockPercent(String v) {
        return v == null || (v.indexOf('%') < 0 && v.indexOf('(') < 0);
    }

    public static final byte IW_NONE = 0, IW_MIN_CONTENT = 1, IW_MAX_CONTENT = 2, IW_FIT_CONTENT = 3, IW_FIT_CONTENT_FN = 4;

    /** {@code width} が内容から決まるキーワード（{@code min-content} / {@code max-content} / {@code fit-content}）か。 */
    public boolean hasIntrinsicWidth() {
        return intrinsicWidthKind != IW_NONE;
    }
    /** {@code margin-left} / {@code margin-right} が {@code auto}。 */
    public boolean marginLeftAuto, marginRightAuto;
    /** 四辺の余白が {@code auto}（前後の空白と大小文字を無視。flex が読む）。 */
    public boolean autoMarginT, autoMarginR, autoMarginB, autoMarginL;
    /** {@code direction: rtl}。 */
    public boolean rtl;
    /** {@code box-sizing: border-box}。 */
    public boolean borderBox;
    /**
     * flex の揃え（{@code align-items} / {@code align-self}）。normal・auto・legacy は {@link CssKeywords.Alignment#STRETCH}、
     * first/last baseline は {@link CssKeywords.Alignment#BASELINE} に寄せてある。{@code align-self: auto} のときは {@link #alignSelfAuto}。
     */
    public CssKeywords.Alignment alignItems, alignSelf;
    public boolean alignSelfAuto;
    /** 独立した整形コンテキストを、スタイルだけで作るか（float・layout/paint containment・スクロールコンテナ）。 */
    public boolean bfcByStyle;
    /** 値のどれかがビューポート／コンテナ基準の単位を使う。 */
    public boolean usesViewportUnits;
    /**
     * 箱自身の横方向の寸法が、親の幅・ビューポートの幅に依らない: {@code width} が % でない確定した長さ、{@code min/max-width}・
     * 余白・パディング・オフセットに % がなく、横の余白も {@code auto} でない。
     */
    public boolean horizontalFixed;

    /** 整形に使う、スタイルだけで決まる値。 */
    public com.nishiyu.enderframelib.core.text.model.WhiteSpace whiteSpace;
    public com.nishiyu.enderframelib.core.text.model.WordBreak wordBreak;
    public boolean overflowWrapBreaks, ellipsis;
    /** 文字の測定に使う書体の指定（同じスタイルなら同じ不変の値）。 */
    public com.nishiyu.enderframelib.core.text.model.FontStyle fontStyle;

    /** 上の値を算出スタイルから決める。{@link ComputedStyle#facts()} が公開する前に 1 回だけ呼ぶ。 */
    volatile boolean extrasReady;

    void fillExtras(ComputedStyle s) {
        long w = s.packedEdge(ComputedStyle.DIM_WIDTH), h = s.packedEdge(ComputedStyle.DIM_HEIGHT);
        // 詰めた値で決まる（文字列を読まない）のは、auto・宣言なし・0 px 以外の単位つき長さ。0 px に潰れた値（0・解釈不能）と式（fit-content(..) を含む）だけ原文を読む
        widthAuto = w == com.nishiyu.enderframelib.core.kernel.style.PackedCssValue.NONE || PackedLengths.isAuto(w);
        heightAuto = h == com.nishiyu.enderframelib.core.kernel.style.PackedCssValue.NONE || PackedLengths.isAuto(h);
        widthPercent = PackedLengths.hasPercent(w);
        heightPercent = PackedLengths.hasPercent(h);
        hasHeightDeclaration = h != com.nishiyu.enderframelib.core.kernel.style.PackedCssValue.NONE;
        intrinsicWidthKind = IW_NONE;
        fitContentArg = null;
        if (PackedLengths.isZeroPx(w) || PackedLengths.isExpression(w)) {
            String widthStr = s.getOrDefault(CssPropertyIndex.WIDTH, "auto").trim();
            widthPercent = widthStr.contains("%");
            if (ValueKeywords.isIntrinsicSizeKeyword(widthStr)) {
                String kw = widthStr.toLowerCase();
                if (kw.equals("min-content")) intrinsicWidthKind = IW_MIN_CONTENT;
                else if (kw.equals("max-content")) intrinsicWidthKind = IW_MAX_CONTENT;
                else if (kw.startsWith("fit-content(")) {
                    intrinsicWidthKind = IW_FIT_CONTENT_FN;
                    // 閉じ括弧が無い形は引数を持たない（以前の resolveIntrinsicWidth と同じく何もしない）
                    if (kw.endsWith(")")) fitContentArg = kw.substring("fit-content(".length(), kw.length() - 1).trim();
                } else intrinsicWidthKind = IW_FIT_CONTENT;
            }
        }
        if (PackedLengths.isZeroPx(h) || PackedLengths.isExpression(h)) heightPercent = s.getOrDefault(CssPropertyIndex.HEIGHT, "auto").trim().contains("%");
        autoMarginT = PackedLengths.isAuto(s.packedEdge(ComputedStyle.EDGE_MARGIN_T));
        autoMarginR = PackedLengths.isAuto(s.packedEdge(ComputedStyle.EDGE_MARGIN_R));
        autoMarginB = PackedLengths.isAuto(s.packedEdge(ComputedStyle.EDGE_MARGIN_B));
        autoMarginL = PackedLengths.isAuto(s.packedEdge(ComputedStyle.EDGE_MARGIN_L));
        // 左右の auto だけは旧来どおり大小文字・空白を区別する（auto と判った時だけ原文を見る）
        marginLeftAuto = autoMarginL && "auto".equals(s.get(CssPropertyIndex.MARGIN_LEFT));
        marginRightAuto = autoMarginR && "auto".equals(s.get(CssPropertyIndex.MARGIN_RIGHT));
        borderBox = s.keywordFresh(CssPropertyIndex.BOX_SIZING, CssKeywords.BoxSizing.class) == CssKeywords.BoxSizing.BORDER_BOX;
        alignItems = flexAlignment(s.keywordFresh(CssPropertyIndex.ALIGN_ITEMS, CssKeywords.Alignment.class));
        CssKeywords.Alignment self = s.keywordFresh(CssPropertyIndex.ALIGN_SELF, CssKeywords.Alignment.class);
        alignSelfAuto = self == CssKeywords.Alignment.AUTO;
        alignSelf = flexAlignment(self);
        usesViewportUnits = s.usesViewportUnits();
        String wm = s.get(CssPropertyIndex.WRITING_MODE);
        horizontalFixed = definiteWidth && !widthAuto && !widthPercent && intrinsicWidthKind == IW_NONE &&!usesViewportUnits
                && !marginLeftAuto && !marginRightAuto && !autoMarginT && !autoMarginR && !autoMarginB && !autoMarginL
                && (wm == null || KeywordMatch.trimmedStartsWith(wm, "horizontal"))
                && !s.edgeHasPercent(ComputedStyle.DIM_MIN_WIDTH) && !s.edgeHasPercent(ComputedStyle.DIM_MAX_WIDTH)
                && !s.edgeHasPercent(ComputedStyle.EDGE_MARGIN_L) && !s.edgeHasPercent(ComputedStyle.EDGE_MARGIN_R)
                && !s.edgeHasPercent(ComputedStyle.EDGE_MARGIN_T) && !s.edgeHasPercent(ComputedStyle.EDGE_MARGIN_B)
                && !s.edgeHasPercent(ComputedStyle.EDGE_PADDING_L) && !s.edgeHasPercent(ComputedStyle.EDGE_PADDING_R)
                && !s.edgeHasPercent(ComputedStyle.EDGE_PADDING_T) && !s.edgeHasPercent(ComputedStyle.EDGE_PADDING_B)
                && !s.edgeHasPercent(ComputedStyle.DIM_LEFT) && !s.edgeHasPercent(ComputedStyle.DIM_RIGHT)
                && !s.edgeHasPercent(ComputedStyle.DIM_TOP) && !s.edgeHasPercent(ComputedStyle.DIM_BOTTOM);
        percentGeometry = widthPercent
                || s.edgeHasPercent(ComputedStyle.DIM_MIN_WIDTH) || s.edgeHasPercent(ComputedStyle.DIM_MAX_WIDTH)
                || s.edgeHasPercent(ComputedStyle.EDGE_MARGIN_L) || s.edgeHasPercent(ComputedStyle.EDGE_MARGIN_R)
                || s.edgeHasPercent(ComputedStyle.EDGE_MARGIN_T) || s.edgeHasPercent(ComputedStyle.EDGE_MARGIN_B)
                || s.edgeHasPercent(ComputedStyle.EDGE_PADDING_L) || s.edgeHasPercent(ComputedStyle.EDGE_PADDING_R)
                || s.edgeHasPercent(ComputedStyle.EDGE_PADDING_T) || s.edgeHasPercent(ComputedStyle.EDGE_PADDING_B)
                || s.edgeHasPercent(ComputedStyle.DIM_LEFT) || s.edgeHasPercent(ComputedStyle.DIM_RIGHT)
                || s.edgeHasPercent(ComputedStyle.DIM_TOP) || s.edgeHasPercent(ComputedStyle.DIM_BOTTOM);
        blockPercentFree = noBlockPercent(s.get(CssPropertyIndex.HEIGHT)) && noBlockPercent(s.get(CssPropertyIndex.MIN_HEIGHT))
                && noBlockPercent(s.get(CssPropertyIndex.MAX_HEIGHT)) && noBlockPercent(s.get(CssPropertyIndex.TOP))
                && noBlockPercent(s.get(CssPropertyIndex.BOTTOM)) && noBlockPercent(s.get(CssPropertyIndex.FLEX_BASIS))
                && noBlockPercent(s.get(CssPropertyIndex.ROW_GAP)) && noBlockPercent(s.get(CssPropertyIndex.GRID_TEMPLATE_ROWS))
                && noBlockPercent(s.get(CssPropertyIndex.GRID_AUTO_ROWS));
        rtl = KeywordMatch.is(s.get(CssPropertyIndex.DIRECTION), "rtl");
        bfcByStyle = floating || (s.containment() & (ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT)) != 0
                || overflowX.scrolls() || overflowY.scrolls();
        whiteSpace = com.nishiyu.enderframelib.core.text.model.WhiteSpace.of(s.get(CssPropertyIndex.WHITE_SPACE));
        wordBreak = com.nishiyu.enderframelib.core.text.model.WordBreak.of(s.get(CssPropertyIndex.WORD_BREAK));
        overflowWrapBreaks = s.keywordFresh(CssPropertyIndex.OVERFLOW_WRAP, CssKeywords.OverflowWrap.class) != CssKeywords.OverflowWrap.NORMAL;
        ellipsis = s.keywordFresh(CssPropertyIndex.TEXT_OVERFLOW, CssKeywords.TextOverflow.class) == CssKeywords.TextOverflow.ELLIPSIS;
        String kerning = s.get(CssPropertyIndex.FONT_KERNING);
        fontStyle = new com.nishiyu.enderframelib.core.text.model.FontStyle(
                s.fontSizePx(),
                fontWeight >= 600,
                s.keywordFresh(CssPropertyIndex.FONT_STYLE, CssKeywords.FontStyle.class) != CssKeywords.FontStyle.NORMAL,
                s.length(CssPropertyIndex.LETTER_SPACING, 0, 0, 0),
                fontWeight,
                !KeywordMatch.is(kerning, "none"));
    }

    /** 揃えの値を flex の配置に寄せる（{@code normal} は stretch、{@code first/last baseline} は baseline）。 */
    static CssKeywords.Alignment flexAlignment(CssKeywords.Alignment v) {
        return switch (v) {
            case NORMAL, AUTO, LEGACY -> CssKeywords.Alignment.STRETCH;
            case FIRST_BASELINE, LAST_BASELINE -> CssKeywords.Alignment.BASELINE;
            default -> v;
        };
    }
}
