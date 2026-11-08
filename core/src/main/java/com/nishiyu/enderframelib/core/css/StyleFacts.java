package com.nishiyu.enderframelib.core.css;

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
    /** 箱の縁の長さ（{@code EDGE_*} の順）。宣言が無いものは null（0 px）。 */
    volatile LengthResolver.CompiledLength[] edges;

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

    /** {@code width} / {@code height} の宣言（前後の空白を除く。無ければ {@code auto}）と、その性質。 */
    public String widthStr, heightStr;
    public boolean widthAuto, heightAuto, widthPercent, heightPercent;
    /** {@code height} の宣言があるか（{@code auto} の明示も含む）。 */
    public boolean hasHeightDeclaration;
    /** {@code width} が {@code min-content} などの内在サイズのキーワードなら、その小文字。そうでなければ null。 */
    public String intrinsicWidth;
    /** {@code margin-left} / {@code margin-right} が {@code auto}。 */
    public boolean marginLeftAuto, marginRightAuto;
    /** 四辺の余白が {@code auto}（前後の空白と大小文字を無視。flex が読む）。 */
    public boolean autoMarginT, autoMarginR, autoMarginB, autoMarginL;
    /** {@code direction: rtl}。 */
    public boolean rtl;
    /** {@code box-sizing: border-box}。 */
    public boolean borderBox;
    /** flex の揃えの語（{@code align-items} / {@code align-self}。normal は stretch、baseline 系は baseline）。{@code align-self: auto} のときは {@link #alignSelfAuto}。 */
    public String alignItemsWord, alignSelfWord;
    public boolean alignSelfAuto;
    /** 独立した整形コンテキストを、スタイルだけで作るか（float・layout/paint containment・スクロールコンテナ）。 */
    public boolean bfcByStyle;
    /** 整形に使う、スタイルだけで決まる値。 */
    public com.nishiyu.enderframelib.core.text.model.WhiteSpace whiteSpace;
    public com.nishiyu.enderframelib.core.text.model.WordBreak wordBreak;
    public boolean overflowWrapBreaks, ellipsis;
    /** 文字の測定に使う書体の指定（同じスタイルなら同じ不変の値）。 */
    public com.nishiyu.enderframelib.core.text.model.FontStyle fontStyle;

    /** 上の値を算出スタイルから決める。{@link ComputedStyle#facts()} が公開する前に 1 回だけ呼ぶ。 */
    volatile boolean extrasReady;

    void fillExtras(ComputedStyle s) {
        widthStr = s.getOrDefault(CssProperty.WIDTH, "auto").trim();
        heightStr = s.getOrDefault(CssProperty.HEIGHT, "auto").trim();
        widthAuto = "auto".equals(widthStr);
        heightAuto = "auto".equals(heightStr);
        widthPercent = widthStr.contains("%");
        heightPercent = heightStr.contains("%");
        hasHeightDeclaration = s.containsKey(CssProperty.HEIGHT);
        intrinsicWidth = !widthAuto && com.nishiyu.enderframelib.core.layout.model.LayoutMetrics.isIntrinsicSizeKeyword(widthStr)
                ? widthStr.toLowerCase() : null;
        marginLeftAuto = "auto".equals(s.get(CssProperty.MARGIN_LEFT));
        marginRightAuto = "auto".equals(s.get(CssProperty.MARGIN_RIGHT));
        borderBox = s.keywordFresh(CssProperty.BOX_SIZING, CssKeywords.BoxSizing.class) == CssKeywords.BoxSizing.BORDER_BOX;
        alignItemsWord = alignmentWord(s.keywordFresh(CssProperty.ALIGN_ITEMS, CssKeywords.Alignment.class));
        CssKeywords.Alignment self = s.keywordFresh(CssProperty.ALIGN_SELF, CssKeywords.Alignment.class);
        alignSelfAuto = self == CssKeywords.Alignment.AUTO;
        alignSelfWord = alignmentWord(self);
        autoMarginT = "auto".equalsIgnoreCase(s.getOrDefault(CssProperty.MARGIN_TOP, "0").trim());
        autoMarginR = "auto".equalsIgnoreCase(s.getOrDefault(CssProperty.MARGIN_RIGHT, "0").trim());
        autoMarginB = "auto".equalsIgnoreCase(s.getOrDefault(CssProperty.MARGIN_BOTTOM, "0").trim());
        autoMarginL = "auto".equalsIgnoreCase(s.getOrDefault(CssProperty.MARGIN_LEFT, "0").trim());
        rtl = "rtl".equalsIgnoreCase(s.getOrDefault(CssProperty.DIRECTION, "ltr").trim());
        bfcByStyle = floating || (s.containment() & (ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT)) != 0
                || overflowX.scrolls() || overflowY.scrolls();
        whiteSpace = com.nishiyu.enderframelib.core.text.model.WhiteSpace.of(s.get(CssProperty.WHITE_SPACE));
        wordBreak = com.nishiyu.enderframelib.core.text.model.WordBreak.of(s.get(CssProperty.WORD_BREAK));
        overflowWrapBreaks = s.keywordFresh(CssProperty.OVERFLOW_WRAP, CssKeywords.OverflowWrap.class) != CssKeywords.OverflowWrap.NORMAL;
        ellipsis = s.keywordFresh(CssProperty.TEXT_OVERFLOW, CssKeywords.TextOverflow.class) == CssKeywords.TextOverflow.ELLIPSIS;
        String kerning = s.get(CssProperty.FONT_KERNING);
        fontStyle = new com.nishiyu.enderframelib.core.text.model.FontStyle(
                s.fontSizePx(),
                fontWeight >= 600,
                s.keywordFresh(CssProperty.FONT_STYLE, CssKeywords.FontStyle.class) != CssKeywords.FontStyle.NORMAL,
                s.length(CssProperty.LETTER_SPACING, 0, 0, 0),
                fontWeight,
                !(kerning != null && "none".equalsIgnoreCase(kerning.trim())));
    }

    /** 揃えの値を flex の配置の語にする（{@code normal} は stretch、{@code first/last baseline} は baseline）。 */
    private static String alignmentWord(CssKeywords.Alignment v) {
        return switch (v) {
            case NORMAL, AUTO, LEGACY -> "stretch";
            case FIRST_BASELINE, LAST_BASELINE -> "baseline";
            default -> v.cssName();
        };
    }
}
