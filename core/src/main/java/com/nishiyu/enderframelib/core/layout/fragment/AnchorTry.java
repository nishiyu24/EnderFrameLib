package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.PositionTryRule;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * アンカー配置の候補（Engine v3 M2b-5。旧 {@code AnchorPositioning} の {@code CandidateSpec}・{@code position-try-fallbacks} の解釈・
 * 反転の移植。CSS Anchor Positioning 1 §5）。候補は inset・余白・大きさの宣言を文字列のまま持ち、{@code @position-try} の規則と
 * {@code flip-block} / {@code flip-inline} / {@code flip-start} で書き換える。
 */
final class AnchorTry {
    private static final int FLIP_BLOCK = 0, FLIP_INLINE = 1, FLIP_START = 2;

    private AnchorTry() { }

    /** 1 つの候補（基本の位置か、{@code position-try-fallbacks} の 1 項）。 */
    static final class Spec {
        String left, right, top, bottom;
        String positionAnchor;
        String[] area;
        float mT, mR, mB, mL;
        boolean autoMarginT, autoMarginR, autoMarginB, autoMarginL;
        String widthDecl, heightDecl, minWidthDecl, maxWidthDecl, minHeightDecl, maxHeightDecl;
        boolean insetsOverridden, widthOverridden, heightOverridden;

        Spec copy() {
            Spec c = new Spec();
            c.left = left; c.right = right; c.top = top; c.bottom = bottom;
            c.positionAnchor = positionAnchor;
            c.area = area != null ? new String[]{area[0], area[1]} : null;
            c.mT = mT; c.mR = mR; c.mB = mB; c.mL = mL;
            c.autoMarginT = autoMarginT; c.autoMarginR = autoMarginR; c.autoMarginB = autoMarginB; c.autoMarginL = autoMarginL;
            c.widthDecl = widthDecl; c.heightDecl = heightDecl;
            c.minWidthDecl = minWidthDecl; c.maxWidthDecl = maxWidthDecl; c.minHeightDecl = minHeightDecl; c.maxHeightDecl = maxHeightDecl;
            c.insetsOverridden = insetsOverridden; c.widthOverridden = widthOverridden; c.heightOverridden = heightOverridden;
            return c;
        }
    }

    /** スタイルどおりの基本の候補（余白は解いた値）。 */
    static Spec base(Style st, BoxGeometry g) {
        Spec s = new Spec();
        s.left = rawOrAuto(st, st.box.left);
        s.right = rawOrAuto(st, st.box.right);
        s.top = rawOrAuto(st, st.box.top);
        s.bottom = rawOrAuto(st, st.box.bottom);
        s.positionAnchor = st.rare.positionAnchor;
        s.area = parseArea(st.rare.positionArea);
        s.mT = g.mT; s.mR = g.mR; s.mB = g.mB; s.mL = g.mL;
        s.autoMarginT = st.facts.autoMarginT; s.autoMarginR = st.facts.autoMarginR;
        s.autoMarginB = st.facts.autoMarginB; s.autoMarginL = st.facts.autoMarginL;
        return s;
    }

    /** {@code position-try-fallbacks} の候補（未定義の {@code @position-try} を名指す項は飛ばす）。 */
    static List<Spec> fallbacks(String value, Spec base, Map<String, PositionTryRule> rules, Style st, float cbW, LayoutSession s) {
        List<Spec> out = new ArrayList<>();
        for (String entry : splitCommaTopLevel(value)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            Spec spec = option(trimmed.split("\\s+"), base, rules, st, cbW, s);
            if (spec != null) out.add(spec);
        }
        return out;
    }

    private static Spec option(String[] tokens, Spec base, Map<String, PositionTryRule> rules, Style st, float cbW, LayoutSession s) {
        String ruleName = null;
        int tactics = 0;
        boolean allTryOrIdent = true;
        for (String tok : tokens) {
            if (tok.startsWith("--")) {
                if (ruleName != null) { allTryOrIdent = false; break; }
                ruleName = tok;
            } else if (isTactic(tok)) {
                tactics++;
            } else {
                allTryOrIdent = false;
                break;
            }
        }
        if (allTryOrIdent && (ruleName != null || tactics > 0)) {
            Spec spec = base.copy();
            if (ruleName != null) {
                PositionTryRule rule = rules != null ? rules.get(ruleName) : null;
                if (rule == null) return null;
                applyRule(spec, rule, st, cbW, s);
            }
            for (String tok : tokens) {
                switch (tok.toLowerCase(Locale.ROOT)) {
                    case "flip-block" -> flipBlock(spec);
                    case "flip-inline" -> flipInline(spec);
                    case "flip-start" -> flipStart(spec, st);
                    default -> { }
                }
            }
            return spec;
        }
        if (tokens.length <= 2) { // <position-area> 単体
            String[] area = areaTokens(tokens);
            if (area != null) {
                Spec spec = base.copy();
                spec.area = area;
                return spec;
            }
        }
        return null;
    }

    private static boolean isTactic(String tok) {
        return switch (tok.toLowerCase(Locale.ROOT)) {
            case "flip-block", "flip-inline", "flip-start" -> true;
            default -> false;
        };
    }

    private static void applyRule(Spec spec, PositionTryRule rule, Style st, float cbW, LayoutSession s) {
        float fs = st.inherited.fontSize, rfs = st.inherited.rootFontSizePx;
        for (Map.Entry<String, String> e : rule.declarations().entrySet()) {
            String val = e.getValue();
            switch (e.getKey()) {
                case "left" -> { spec.left = val; spec.insetsOverridden = true; }
                case "right" -> { spec.right = val; spec.insetsOverridden = true; }
                case "top" -> { spec.top = val; spec.insetsOverridden = true; }
                case "bottom" -> { spec.bottom = val; spec.insetsOverridden = true; }
                case "margin-left" -> { spec.autoMarginL = auto(val); spec.mL = margin(val, cbW, fs, rfs, s); }
                case "margin-right" -> { spec.autoMarginR = auto(val); spec.mR = margin(val, cbW, fs, rfs, s); }
                case "margin-top" -> { spec.autoMarginT = auto(val); spec.mT = margin(val, cbW, fs, rfs, s); }
                case "margin-bottom" -> { spec.autoMarginB = auto(val); spec.mB = margin(val, cbW, fs, rfs, s); }
                case "width" -> { spec.widthDecl = val; spec.widthOverridden = true; }
                case "height" -> { spec.heightDecl = val; spec.heightOverridden = true; }
                case "min-width" -> spec.minWidthDecl = val;
                case "max-width" -> spec.maxWidthDecl = val;
                case "min-height" -> spec.minHeightDecl = val;
                case "max-height" -> spec.maxHeightDecl = val;
                case "position-anchor" -> spec.positionAnchor = val;
                case "position-area" -> spec.area = parseArea(val);
                default -> { }
            }
        }
    }

    private static float margin(String val, float cbW, float fs, float rfs, LayoutSession s) {
        return auto(val) ? 0 : LayoutUnit.snap(LengthResolver.parseDim(val, cbW, s.viewportW(), s.viewportH(), fs, rfs));
    }

    // ---- 反転（try tactic）

    private static void flipBlock(Spec spec) {
        String top = flipAnchor(spec.bottom, FLIP_BLOCK), bottom = flipAnchor(spec.top, FLIP_BLOCK);
        spec.top = top;
        spec.bottom = bottom;
        spec.insetsOverridden = true;
        float m = spec.mT; spec.mT = spec.mB; spec.mB = m;
        boolean a = spec.autoMarginT; spec.autoMarginT = spec.autoMarginB; spec.autoMarginB = a;
        if (spec.area != null) spec.area[1] = flipArea(spec.area[1], FLIP_BLOCK);
    }

    private static void flipInline(Spec spec) {
        String left = flipAnchor(spec.right, FLIP_INLINE), right = flipAnchor(spec.left, FLIP_INLINE);
        spec.left = left;
        spec.right = right;
        spec.insetsOverridden = true;
        float m = spec.mL; spec.mL = spec.mR; spec.mR = m;
        boolean a = spec.autoMarginL; spec.autoMarginL = spec.autoMarginR; spec.autoMarginR = a;
        if (spec.area != null) spec.area[0] = flipArea(spec.area[0], FLIP_INLINE);
    }

    /** 対角線で鏡映: 上 ↔ 左・下 ↔ 右、幅 ↔ 高さ（{@code anchor-size()} の向きも）。 */
    private static void flipStart(Spec spec, Style st) {
        String t = spec.top, r = spec.right, b = spec.bottom, l = spec.left;
        spec.top = flipAnchor(l, FLIP_START);
        spec.left = flipAnchor(t, FLIP_START);
        spec.bottom = flipAnchor(r, FLIP_START);
        spec.right = flipAnchor(b, FLIP_START);
        spec.insetsOverridden = true;
        float mt = spec.mT; spec.mT = spec.mL; spec.mL = mt;
        float mb = spec.mB; spec.mB = spec.mR; spec.mR = mb;
        boolean at = spec.autoMarginT; spec.autoMarginT = spec.autoMarginL; spec.autoMarginL = at;
        boolean ab = spec.autoMarginB; spec.autoMarginB = spec.autoMarginR; spec.autoMarginR = ab;
        String w = spec.widthDecl != null ? spec.widthDecl : rawOrAuto(st, st.box.width);
        String h = spec.heightDecl != null ? spec.heightDecl : rawOrAuto(st, st.box.height);
        spec.widthDecl = flipAnchorSize(h);
        spec.heightDecl = flipAnchorSize(w);
        spec.widthOverridden = true;
        spec.heightOverridden = true;
        String minW = spec.minWidthDecl != null ? spec.minWidthDecl : CssLength.toCssString(st.box.minWidth, st.box.exprs);
        String minH = spec.minHeightDecl != null ? spec.minHeightDecl : CssLength.toCssString(st.box.minHeight, st.box.exprs);
        spec.minWidthDecl = minH;
        spec.minHeightDecl = minW;
        String maxW = spec.maxWidthDecl != null ? spec.maxWidthDecl : CssLength.toCssString(st.box.maxWidth, st.box.exprs);
        String maxH = spec.maxHeightDecl != null ? spec.maxHeightDecl : CssLength.toCssString(st.box.maxHeight, st.box.exprs);
        spec.maxWidthDecl = maxH;
        spec.maxHeightDecl = maxW;
        if (spec.area != null) {
            String x = flipArea(spec.area[1], FLIP_START), y = flipArea(spec.area[0], FLIP_START);
            spec.area[0] = x;
            spec.area[1] = y;
        }
    }

    private static String flipAnchor(String raw, int kind) {
        if (!isAnchorFn(raw)) return raw;
        String[] ns = nameAndArg(raw, 7);
        if (ns == null || ns[1] == null) return raw;
        String side = ns[1];
        String flipped = switch (kind) {
            case FLIP_BLOCK -> switch (side) {
                case "top" -> "bottom";
                case "bottom" -> "top";
                case "start" -> "end";
                case "end" -> "start";
                case "self-start" -> "self-end";
                case "self-end" -> "self-start";
                default -> flipPercent(side);
            };
            case FLIP_INLINE -> switch (side) {
                case "left" -> "right";
                case "right" -> "left";
                case "start" -> "end";
                case "end" -> "start";
                case "self-start" -> "self-end";
                case "self-end" -> "self-start";
                default -> flipPercent(side);
            };
            default -> switch (side) {
                case "top" -> "left";
                case "left" -> "top";
                case "bottom" -> "right";
                case "right" -> "bottom";
                default -> side;
            };
        };
        return "anchor(" + (ns[0] != null ? ns[0] + " " : "") + flipped + ")";
    }

    private static String flipPercent(String side) {
        if (!side.endsWith("%")) return side;
        try {
            float p = 100f - Float.parseFloat(side.substring(0, side.length() - 1));
            return (p == Math.rint(p) ? Integer.toString((int) p) : Float.toString(p)) + "%";
        } catch (NumberFormatException e) {
            return side;
        }
    }

    private static String flipAnchorSize(String raw) {
        if (!isAnchorSizeFn(raw)) return raw;
        String[] ns = nameAndArg(raw, 12);
        if (ns == null || ns[1] == null) return raw;
        String flipped = switch (ns[1]) {
            case "width" -> "height";
            case "height" -> "width";
            case "inline" -> "block";
            case "block" -> "inline";
            default -> ns[1];
        };
        return "anchor-size(" + (ns[0] != null ? ns[0] + " " : "") + flipped + ")";
    }

    private static String flipArea(String tok, int kind) {
        if (tok == null) return null;
        if (kind == FLIP_START) {
            return swap(tok, "left", "top", "right", "bottom", "span-left", "span-top", "span-right", "span-bottom",
                    "x-start", "y-start", "x-end", "y-end", "span-x-start", "span-y-start", "span-x-end", "span-y-end",
                    "x-self-start", "y-self-start", "x-self-end", "y-self-end");
        }
        String r = kind == FLIP_BLOCK
                ? swap(tok, "top", "bottom", "span-top", "span-bottom", "y-start", "y-end", "span-y-start", "span-y-end", "y-self-start", "y-self-end")
                : swap(tok, "left", "right", "span-left", "span-right", "x-start", "x-end", "span-x-start", "span-x-end", "x-self-start", "x-self-end");
        return !r.equals(tok) ? r : swap(tok, "start", "end", "span-start", "span-end", "self-start", "self-end", "span-self-start", "span-self-end");
    }

    private static String swap(String tok, String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            if (tok.equals(pairs[i])) return pairs[i + 1];
            if (tok.equals(pairs[i + 1])) return pairs[i];
        }
        return tok;
    }

    // ---- 値の読み取り

    /** {@code position-area} を {x の語, y の語} に（none・解けなければ null）。 */
    static String[] parseArea(String area) {
        if (area == null || area.isBlank() || "none".equalsIgnoreCase(area.trim())) return null;
        return areaTokens(area.trim().toLowerCase(Locale.ROOT).split("\\s+"));
    }

    private static String[] areaTokens(String[] parts) {
        if (parts.length == 0 || parts.length > 2) return null;
        if (parts.length == 1) {
            String t = parts[0].toLowerCase(Locale.ROOT);
            if (isAreaX(t)) return new String[]{t, "span-all"};
            if (isAreaY(t)) return new String[]{"span-all", t};
            if (isAreaNeutral(t)) return new String[]{t, t};
            return null;
        }
        String t0 = parts[0].toLowerCase(Locale.ROOT), t1 = parts[1].toLowerCase(Locale.ROOT);
        if (isAreaX(t0) && (isAreaY(t1) || isAreaNeutral(t1))) return new String[]{t0, t1};
        if (isAreaY(t0) && (isAreaX(t1) || isAreaNeutral(t1))) return new String[]{t1, t0};
        if (isAreaNeutral(t0) && isAreaX(t1)) return new String[]{t1, t0};
        if (isAreaNeutral(t0) && isAreaY(t1)) return new String[]{t0, t1};
        if (isAreaNeutral(t0) && isAreaNeutral(t1)) return new String[]{t1, t0};
        return null;
    }

    private static boolean isAreaX(String t) {
        return switch (t) {
            case "left", "right", "span-left", "span-right", "x-start", "x-end", "x-self-start", "x-self-end", "span-x-start", "span-x-end" -> true;
            default -> false;
        };
    }

    private static boolean isAreaY(String t) {
        return switch (t) {
            case "top", "bottom", "span-top", "span-bottom", "y-start", "y-end", "y-self-start", "y-self-end", "span-y-start", "span-y-end" -> true;
            default -> false;
        };
    }

    private static boolean isAreaNeutral(String t) {
        return switch (t) {
            case "center", "span-all", "start", "end", "self-start", "self-end", "span-start", "span-end", "span-self-start", "span-self-end" -> true;
            default -> false;
        };
    }

    /** {@code fn(--name arg)} の {名前, 引数}（{@code prefix} は関数名と括弧の長さ）。 */
    static String[] nameAndArg(String raw, int prefix) {
        int close = raw.lastIndexOf(')');
        if (close < prefix) return null;
        String name = null, arg = null;
        for (String part : raw.substring(prefix, close).trim().split("[\\s,]+")) {
            if (part.startsWith("--")) name = part;
            else if (!part.isEmpty() && arg == null) arg = part.toLowerCase(Locale.ROOT);
        }
        return new String[]{name, arg};
    }

    static String rawOrAuto(Style st, long len) {
        String v = CssLength.toCssString(len, st.box.exprs);
        return v == null || v.isBlank() ? "auto" : v.trim();
    }

    static boolean auto(String v) {
        return v == null || v.isBlank() || "auto".equalsIgnoreCase(v.trim());
    }

    static boolean isAnchorFn(String v) {
        return v != null && v.regionMatches(true, 0, "anchor(", 0, 7);
    }

    static boolean isAnchorSizeFn(String v) {
        return v != null && v.regionMatches(true, 0, "anchor-size(", 0, 12);
    }

    private static List<String> splitCommaTopLevel(String s) {
        List<String> out = new ArrayList<>(4);
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && depth > 0) depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }
}
