package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 型付きの事実（{@link StyleFacts}）が、宣言の文字列を毎回読み直す元の経路と同じ値になること。
 * 値は乱数で組み合わせ、元の経路は「宣言を直接引く」書き方をここに書き写して照らし合わせる。
 */
class StyleFactsTest {

    private static final String[] OVERFLOWS = {"visible", "hidden", "scroll", "auto", "clip", "hidden auto", "scroll hidden", "auto  clip", "bogus", ""};
    private static final String[] LINE_HEIGHTS = {"normal", "20px", "150%", "1.5em", "1.4", " 18px ", "px", "abc", "12.5px", "0", "-3px", "NORMAL"};
    private static final String[] WEIGHTS = {"normal", "bold", "bolder", "lighter", "700", "100", "1500", "x", "400 "};
    private static final String[] WIDTHS = {"auto", "100px", "50%", "min-content", "max-content", "fit-content", "10em", "calc(10px + 5%)", " 30px ", ""};
    private static final String[] CONTAIN = {"none", "size", "inline-size", "layout", "paint", "strict", "content", "layout paint"};
    private static final String[] CONTAINER = {"normal", "size", "inline-size"};
    private static final String[] FLOATS = {"none", "left", "right", "inline-start"};
    private static final String[] DISPLAYS = {"block", "none", "flex", "inline", "grid"};
    private static final String[] AUTOS = {"auto", " auto ", "AUTO", "0", "10px", "auto auto", ""};
    private static final String[] DIRECTIONS = {"ltr", "rtl", " RTL ", "bogus"};
    private static final String[] SPACES = {"normal", "nowrap", "pre", "pre-wrap", "pre-line", "break-spaces", "x"};
    private static final String[] BREAKS = {"normal", "break-all", "keep-all", "break-word", "x"};
    private static final String[] KERNINGS = {"auto", "none", " NONE ", "normal"};
    private static final String[] ALIGNS = {"normal", "stretch", "start", "end", "center", "flex-start", "flex-end", "baseline", "first baseline", "last baseline", "auto", "self-end", "x"};
    private static final String[] BOXES = {"content-box", "border-box", "x"};
    private static final String[] LENGTHS = {"0", "4px", "10%", "2em", "1rem", "3vw", "auto", "calc(2px + 3px)", "-5px", "1.5"};

    private static String pick(Random r, String[] values) {
        return values[r.nextInt(values.length)];
    }

    private static Map<String, String> randomProperties(Random r) {
        Map<String, String> p = new HashMap<>();
        if (r.nextInt(3) > 0) p.put(CssProperty.OVERFLOW, pick(r, OVERFLOWS));
        if (r.nextInt(3) == 0) p.put(CssProperty.OVERFLOW_X, pick(r, OVERFLOWS));
        if (r.nextInt(3) == 0) p.put(CssProperty.OVERFLOW_Y, pick(r, OVERFLOWS));
        if (r.nextBoolean()) p.put(CssProperty.LINE_HEIGHT, pick(r, LINE_HEIGHTS));
        if (r.nextBoolean()) p.put(CssProperty.FONT_WEIGHT, pick(r, WEIGHTS));
        if (r.nextBoolean()) p.put(CssProperty.WIDTH, pick(r, WIDTHS));
        if (r.nextBoolean()) p.put(CssProperty.HEIGHT, pick(r, WIDTHS));
        if (r.nextBoolean()) p.put(CssProperty.CONTAIN, pick(r, CONTAIN));
        if (r.nextBoolean()) p.put(CssProperty.CONTAINER_TYPE, pick(r, CONTAINER));
        if (r.nextBoolean()) p.put(CssProperty.FLOAT, pick(r, FLOATS));
        if (r.nextBoolean()) p.put(CssProperty.DISPLAY, pick(r, DISPLAYS));
        if (r.nextBoolean()) p.put(CssProperty.FONT_SIZE, (8 + r.nextInt(20)) + "px");
        for (String side : List.of(CssProperty.MARGIN_TOP, CssProperty.MARGIN_RIGHT, CssProperty.MARGIN_BOTTOM, CssProperty.MARGIN_LEFT)) {
            if (r.nextInt(3) == 0) p.put(side, pick(r, AUTOS));
        }
        if (r.nextBoolean()) p.put(CssProperty.DIRECTION, pick(r, DIRECTIONS));
        if (r.nextBoolean()) p.put(CssProperty.BOX_SIZING, pick(r, BOXES));
        if (r.nextBoolean()) p.put(CssProperty.ALIGN_ITEMS, pick(r, ALIGNS));
        if (r.nextBoolean()) p.put(CssProperty.ALIGN_SELF, pick(r, ALIGNS));
        if (r.nextBoolean()) p.put(CssProperty.WHITE_SPACE, pick(r, SPACES));
        if (r.nextBoolean()) p.put(CssProperty.WORD_BREAK, pick(r, BREAKS));
        if (r.nextBoolean()) p.put(CssProperty.FONT_KERNING, pick(r, KERNINGS));
        if (r.nextBoolean()) p.put(CssProperty.LETTER_SPACING, pick(r, LENGTHS));
        if (r.nextBoolean()) p.put(CssProperty.FONT_STYLE, r.nextBoolean() ? "italic" : "oblique 10deg");
        if (r.nextBoolean()) p.put(CssProperty.OVERFLOW_WRAP, r.nextBoolean() ? "break-word" : "normal");
        if (r.nextBoolean()) p.put(CssProperty.TEXT_OVERFLOW, r.nextBoolean() ? "ellipsis" : "clip");
        for (String name : List.of(CssProperty.MARGIN_TOP, CssProperty.MARGIN_RIGHT, CssProperty.MARGIN_BOTTOM,
                CssProperty.MARGIN_LEFT, CssProperty.PADDING_TOP, CssProperty.PADDING_RIGHT, CssProperty.PADDING_BOTTOM,
                CssProperty.PADDING_LEFT, CssProperty.BORDER_TOP_WIDTH, CssProperty.BORDER_RIGHT_WIDTH,
                CssProperty.BORDER_BOTTOM_WIDTH, CssProperty.BORDER_LEFT_WIDTH, CssProperty.ROW_GAP, CssProperty.COLUMN_GAP)) {
            if (r.nextInt(3) == 0) p.put(name, pick(r, LENGTHS));
        }
        return p;
    }

    /** 元の経路（宣言を引いて、その場で解く）。 */
    private static Overflow legacyOverflow(ComputedStyle s, String property, int index) {
        Overflow declared;
        if (s.containsKey(property)) {
            declared = s.keywordOf(property, Overflow.class);
        } else {
            String shorthand = s.get(CssProperty.OVERFLOW);
            if (shorthand == null) {
                declared = Overflow.VISIBLE;
            } else {
                String[] parts = shorthand.trim().split("\\s+");
                Overflow parsed = CssKeywords.parse(Overflow.class, parts[Math.min(index, parts.length - 1)]);
                declared = parsed != null ? parsed : Overflow.VISIBLE;
            }
        }
        if (declared == Overflow.VISIBLE && (s.containment() & ComputedStyle.CONTAIN_PAINT) != 0) return Overflow.CLIP;
        return declared;
    }

    private static boolean legacyDefinite(ComputedStyle s, String property) {
        String value = s.get(property);
        if (value == null) return false;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return !v.isEmpty() && !v.equals("auto") && !v.contains("%") && !LayoutMetrics.isIntrinsicSizeKeyword(v);
    }

    @Test
    void factsMatchTheDeclarationsReadTheOldWay() {
        Random r = new Random(20261108);
        for (int i = 0; i < 4000; i++) {
            ComputedStyle s = ComputedStyle.of(randomProperties(r), 16f, 16f);
            StyleFacts f = s.layoutFacts();
            String at = "case " + i + " " + s.asMap();

            assertEquals(legacyOverflow(s, CssProperty.OVERFLOW_X, 0), f.overflowX, at);
            assertEquals(legacyOverflow(s, CssProperty.OVERFLOW_Y, 1), f.overflowY, at);
            assertEquals(f.overflowX.scrolls() || f.overflowY.scrolls(), f.scrollContainer, at);
            assertEquals(LayoutMetrics.numericWeight(s.get(CssProperty.FONT_WEIGHT)), f.fontWeight, at);

            assertEquals((s.containment() & (ComputedStyle.CONTAIN_SIZE | ComputedStyle.CONTAIN_INLINE_SIZE)) != 0
                    || s.keywordOf(CssProperty.CONTAINER_TYPE, CssKeywords.ContainerType.class).containsInlineSize(),
                    f.containsInlineSize, at);
            assertEquals((s.containment() & ComputedStyle.CONTAIN_SIZE) != 0
                    || s.keywordOf(CssProperty.CONTAINER_TYPE, CssKeywords.ContainerType.class).containsBlockSize(),
                    f.containsBlockSize, at);
            assertEquals(s.keywordOf(CssProperty.FLOAT, CssKeywords.FloatSide.class) != CssKeywords.FloatSide.NONE,
                    f.floating, at);
            assertEquals("none".equals(s.getOrDefault(CssProperty.DISPLAY, "block")), f.displayNone, at);
            assertEquals(legacyDefinite(s, CssProperty.WIDTH), f.definiteWidth, at);
            assertEquals(legacyDefinite(s, CssProperty.HEIGHT), f.definiteHeight, at);


            // 測定・整形が読む宣言（元の「宣言を直接引く」書き方と照らし合わせる）
            String widthStr = s.getOrDefault(CssProperty.WIDTH, "auto").trim();
            String heightStr = s.getOrDefault(CssProperty.HEIGHT, "auto").trim();
            assertEquals("auto".equals(widthStr), f.widthAuto, at);
            assertEquals("auto".equals(heightStr), f.heightAuto, at);
            assertEquals(widthStr.contains("%"), f.widthPercent, at);
            assertEquals(heightStr.contains("%"), f.heightPercent, at);
            assertEquals(s.containsKey(CssProperty.HEIGHT), f.hasHeightDeclaration, at);
            String intrinsic = !"auto".equals(widthStr) && LayoutMetrics.isIntrinsicSizeKeyword(widthStr) ? widthStr.toLowerCase() : null;
            assertEquals(legacyIntrinsicKind(intrinsic), f.intrinsicWidthKind, at);
            assertEquals(intrinsic != null, f.hasIntrinsicWidth(), at);
            String fitArg = intrinsic != null && intrinsic.startsWith("fit-content(") && intrinsic.endsWith(")")
                    ? intrinsic.substring("fit-content(".length(), intrinsic.length() - 1).trim() : null;
            assertEquals(fitArg, f.fitContentArg, at);
            assertEquals("auto".equals(s.get(CssProperty.MARGIN_LEFT)), f.marginLeftAuto, at);
            assertEquals("auto".equals(s.get(CssProperty.MARGIN_RIGHT)), f.marginRightAuto, at);
            assertEquals("auto".equalsIgnoreCase(s.getOrDefault(CssProperty.MARGIN_TOP, "0").trim()), f.autoMarginT, at);
            assertEquals("auto".equalsIgnoreCase(s.getOrDefault(CssProperty.MARGIN_RIGHT, "0").trim()), f.autoMarginR, at);
            assertEquals("auto".equalsIgnoreCase(s.getOrDefault(CssProperty.MARGIN_BOTTOM, "0").trim()), f.autoMarginB, at);
            assertEquals("auto".equalsIgnoreCase(s.getOrDefault(CssProperty.MARGIN_LEFT, "0").trim()), f.autoMarginL, at);
            assertEquals("rtl".equalsIgnoreCase(s.getOrDefault(CssProperty.DIRECTION, "ltr").trim()), f.rtl, at);
            assertEquals(s.keywordOf(CssProperty.FLOAT, CssKeywords.FloatSide.class) != CssKeywords.FloatSide.NONE
                    || (s.containment() & (ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT)) != 0
                    || legacyOverflow(s, CssProperty.OVERFLOW_X, 0).scrolls() || legacyOverflow(s, CssProperty.OVERFLOW_Y, 1).scrolls(),
                    f.bfcByStyle, at);
            assertEquals(com.nishiyu.enderframelib.core.text.model.WhiteSpace.of(s.get(CssProperty.WHITE_SPACE)), f.whiteSpace, at);
            assertEquals(com.nishiyu.enderframelib.core.text.model.WordBreak.of(s.get(CssProperty.WORD_BREAK)), f.wordBreak, at);
            assertEquals(s.keywordOf(CssProperty.OVERFLOW_WRAP, CssKeywords.OverflowWrap.class) != CssKeywords.OverflowWrap.NORMAL,
                    f.overflowWrapBreaks, at);
            assertEquals(s.keywordOf(CssProperty.TEXT_OVERFLOW, CssKeywords.TextOverflow.class) == CssKeywords.TextOverflow.ELLIPSIS,
                    f.ellipsis, at);
            String kerning = s.get(CssProperty.FONT_KERNING);
            int weight = LayoutMetrics.numericWeight(s.get(CssProperty.FONT_WEIGHT));
            assertEquals(new com.nishiyu.enderframelib.core.text.model.FontStyle(s.fontSizePx(), weight >= 600,
                    s.keywordOf(CssProperty.FONT_STYLE, CssKeywords.FontStyle.class) != CssKeywords.FontStyle.NORMAL,
                    s.length(CssProperty.LETTER_SPACING, 0, 0, 0), weight,
                    !(kerning != null && "none".equalsIgnoreCase(kerning.trim()))), f.fontStyle, at);

            assertEquals(s.keywordOf(CssProperty.BOX_SIZING, CssKeywords.BoxSizing.class) == CssKeywords.BoxSizing.BORDER_BOX, f.borderBox, at);
            assertEquals(legacyAlignmentWord(s.keywordOf(CssProperty.ALIGN_ITEMS, CssKeywords.Alignment.class)), f.alignItems.cssName(), at);
            CssKeywords.Alignment self = s.keywordOf(CssProperty.ALIGN_SELF, CssKeywords.Alignment.class);
            assertEquals(self == CssKeywords.Alignment.AUTO, f.alignSelfAuto, at);
            assertEquals(legacyAlignmentWord(self), f.alignSelf.cssName(), at);

            String[] edgeNames = {CssProperty.MARGIN_TOP, CssProperty.MARGIN_RIGHT, CssProperty.MARGIN_BOTTOM,
                    CssProperty.MARGIN_LEFT, CssProperty.PADDING_TOP, CssProperty.PADDING_RIGHT, CssProperty.PADDING_BOTTOM,
                    CssProperty.PADDING_LEFT, CssProperty.BORDER_TOP_WIDTH, CssProperty.BORDER_RIGHT_WIDTH,
                    CssProperty.BORDER_BOTTOM_WIDTH, CssProperty.BORDER_LEFT_WIDTH, CssProperty.ROW_GAP,
                    CssProperty.COLUMN_GAP};
            float base = 50f + r.nextInt(400);
            for (int e = 0; e < edgeNames.length; e++) {
                assertEquals(Float.floatToIntBits(s.length(edgeNames[e], base, 800f, 600f)),
                        Float.floatToIntBits(s.edgeLength(e, base, 800f, 600f)), at + " " + edgeNames[e]);
            }
        }
    }

    @Test
    void lineHeightMatchesTheParsingItReplaced() {
        for (String raw : LINE_HEIGHTS) {
            Map<String, String> p = new HashMap<>();
            p.put(CssProperty.LINE_HEIGHT, raw);
            ComputedStyle s = ComputedStyle.of(p, 16f, 16f);
            StyleFacts f = s.layoutFacts();
            Float expected = legacyLineHeight(raw, 20f);
            float font = 20f;
            float actual = switch (f.lineHeightKind) {
                case ComputedStyle.LH_PX -> f.lineHeightValue;
                case ComputedStyle.LH_PERCENT, ComputedStyle.LH_EM, ComputedStyle.LH_NUMBER -> f.lineHeightValue * font;
                default -> Float.NaN;
            };
            if (expected == null) assertEquals(ComputedStyle.LH_NORMAL, f.lineHeightKind, raw);
            else assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual), raw);
        }
    }

    /** 置き換え前の {@code LayoutMetrics.lineHeightPx} の解釈。normal（または解釈できない）なら null。 */
    private static Float legacyLineHeight(String raw, float fontSize) {
        String value = raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("normal")) return null;
        try {
            if (value.endsWith("px")) return Float.parseFloat(value.substring(0, value.length() - 2).trim());
            if (value.endsWith("%")) return Float.parseFloat(value.substring(0, value.length() - 1).trim()) / 100f * fontSize;
            if (value.endsWith("em")) return Float.parseFloat(value.substring(0, value.length() - 2).trim()) * fontSize;
            return Float.parseFloat(value) * fontSize;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Test
    void cssNameIsTheLowercasedHyphenatedConstantName() {
        for (CssKeywords.FlexDirection d : CssKeywords.FlexDirection.values()) {
            assertEquals(d.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), d.cssName());
        }
        for (CssKeywords.Alignment a : CssKeywords.Alignment.values()) {
            assertEquals(a.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), a.cssName());
        }
    }

    /** 置き換え前の文字列（{@code intrinsicWidth}、小文字）から種類を決める。 */
    private static byte legacyIntrinsicKind(String intrinsic) {
        if (intrinsic == null) return StyleFacts.IW_NONE;
        if (intrinsic.equals("min-content")) return StyleFacts.IW_MIN_CONTENT;
        if (intrinsic.equals("max-content")) return StyleFacts.IW_MAX_CONTENT;
        if (intrinsic.startsWith("fit-content(")) return StyleFacts.IW_FIT_CONTENT_FN;
        return StyleFacts.IW_FIT_CONTENT;
    }

    @Test
    void intrinsicWidthKeywordsAndFitContentArgument() {
        Object[][] cases = {
                {"min-content", StyleFacts.IW_MIN_CONTENT, null},
                {"MAX-Content", StyleFacts.IW_MAX_CONTENT, null},
                {"fit-content", StyleFacts.IW_FIT_CONTENT, null},
                {"fit-content( 120PX )", StyleFacts.IW_FIT_CONTENT_FN, "120px"},
                {"fit-content(50%)", StyleFacts.IW_FIT_CONTENT_FN, "50%"},
                {"fit-content(10px", StyleFacts.IW_FIT_CONTENT_FN, null},
                {"auto", StyleFacts.IW_NONE, null},
                {"100px", StyleFacts.IW_NONE, null},
                {"50%", StyleFacts.IW_NONE, null},
        };
        for (Object[] c : cases) {
            Map<String, String> p = new HashMap<>();
            p.put(CssProperty.WIDTH, (String) c[0]);
            StyleFacts f = ComputedStyle.of(p, 16f, 16f).layoutFacts();
            assertEquals((byte) c[1], f.intrinsicWidthKind, (String) c[0]);
            assertEquals(c[2], f.fitContentArg, (String) c[0]);
        }
    }

    @Test
    void flexAlignmentFoldsNormalAndBaselineVariants() {
        for (CssKeywords.Alignment a : CssKeywords.Alignment.values()) {
            CssKeywords.Alignment folded = StyleFacts.flexAlignment(a);
            assertEquals(legacyAlignmentWord(a), folded.cssName(), a.name());
            // 畳んだ値は不動点（二度畳んでも変わらない）で、元の語に normal / auto / legacy / *-baseline は残らない
            assertEquals(folded, StyleFacts.flexAlignment(folded), a.name());
        }
    }

    /** 置き換え前の {@code FlexLayoutEngine.alignmentWord}。 */
    private static String legacyAlignmentWord(CssKeywords.Alignment v) {
        return switch (v) {
            case NORMAL, AUTO, LEGACY -> "stretch";
            case FIRST_BASELINE, LAST_BASELINE -> "baseline";
            default -> v.cssName();
        };
    }
}
