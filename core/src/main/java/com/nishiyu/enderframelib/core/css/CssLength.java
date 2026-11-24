package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.kernel.style.PackedCssValue;
import java.util.Locale;

/**
 * 長さ・サイズ指定を 64-bit の {@code long} 1 個で表す値（{@link PackedCssValue} の配置を使う）。オブジェクトも参照も持たない。
 *
 * <ul>
 *   <li>数値＋単位（{@code px % em rem vw vh vmin vmax}）: 値そのものを持つ。</li>
 *   <li>キーワード（{@code auto none normal min-content …}）: 種別 {@code K_KEYWORD}、ペイロードが {@code KW_*}。</li>
 *   <li>{@code calc()} 等の式: 種別 {@code K_CALC}、ペイロードが {@link CssExprs} の添字。</li>
 * </ul>
 *
 * <p>px への解決は旧 {@code LengthResolver.CompiledLength#resolve} と同じ結果（キーワードは 0）。</p>
 */
public final class CssLength {
    private CssLength() {}

    public static final int KW_OTHER = 0, KW_AUTO = 1, KW_NONE = 2, KW_NORMAL = 3, KW_MIN_CONTENT = 4, KW_MAX_CONTENT = 5,
            KW_FIT_CONTENT = 6, KW_MEDIUM = 7, KW_THIN = 8, KW_THICK = 9,
            /** 解釈できない値のうち、空文字（確定した長さではない）と、{@code %} を含む文字列（% を含む扱い）。旧文字列版の判定を保つ。 */
            KW_EMPTY = 10, KW_JUNK_PERCENT = 11, KW_CONTENT = 12,
            KW_BASELINE = 13, KW_MIDDLE = 14, KW_SUB = 15, KW_SUPER = 16,
            KW_TEXT_TOP = 17, KW_TEXT_BOTTOM = 18, KW_TOP = 19, KW_BOTTOM = 20;

    /** 文字列から値を作る。式は {@code exprs} に積んで添字を持つ。 */
    static long parse(String raw, CssExprs.Builder exprs) {
        if (raw == null) return PackedCssValue.keyword(KW_OTHER);
        long fast = parseFast(raw);
        if (fast != -1L) return fast;
        LengthResolver.CompiledLength c = LengthResolver.compile(raw);
        return switch (c.unit()) {
            case PIXELS -> PackedCssValue.length(c.value(), PackedCssValue.U_PX);
            case PERCENT -> PackedCssValue.length(c.value(), PackedCssValue.U_PERCENT);
            case VW -> PackedCssValue.length(c.value(), PackedCssValue.U_VW);
            case VH -> PackedCssValue.length(c.value(), PackedCssValue.U_VH);
            case VMIN -> PackedCssValue.length(c.value(), PackedCssValue.U_VMIN);
            case VMAX -> PackedCssValue.length(c.value(), PackedCssValue.U_VMAX);
            case EM -> PackedCssValue.length(c.value(), PackedCssValue.U_EM);
            case REM -> PackedCssValue.length(c.value(), PackedCssValue.U_REM);
            case EXPRESSION -> PackedCssValue.calc(exprs.add(c));
            default -> PackedCssValue.keyword(keywordAtom(raw));
        };
    }

    private static long parseFast(String raw) {
        int s = 0, e = raw.length();
        while (s < e && raw.charAt(s) <= ' ') s++;
        while (e > s && raw.charAt(e - 1) <= ' ') e--;
        if (s == e) return PackedCssValue.keyword(KW_EMPTY);
        char first = raw.charAt(s);
        if ((first < '0' || first > '9') && first != '-' && first != '+' && first != '.') {
            if (e - s == 4 && raw.regionMatches(true, s, "auto", 0, 4)) return PackedCssValue.keyword(KW_AUTO);
            return -1L;
        }
        int p = s;
        if (first == '-' || first == '+') p++;
        if (p >= e) return -1L;
        boolean sawDigit = false, sawDot = false;
        while (p < e) {
            char c = raw.charAt(p);
            if (c >= '0' && c <= '9') { sawDigit = true; p++; }
            else if (c == '.' && !sawDot) { sawDot = true; p++; }
            else break;
        }
        if (!sawDigit) return -1L;
        int unitLen = e - p;
        int unit;
        if (unitLen == 0) {
            unit = PackedCssValue.U_PX;
        } else if (unitLen == 1 && raw.charAt(p) == '%') {
            unit = PackedCssValue.U_PERCENT;
        } else if (unitLen == 2) {
            char u0 = Character.toLowerCase(raw.charAt(p)), u1 = Character.toLowerCase(raw.charAt(p + 1));
            if (u0 == 'p' && u1 == 'x') unit = PackedCssValue.U_PX;
            else if (u0 == 'e' && u1 == 'm') unit = PackedCssValue.U_EM;
            else if (u0 == 'v' && u1 == 'w') unit = PackedCssValue.U_VW;
            else if (u0 == 'v' && u1 == 'h') unit = PackedCssValue.U_VH;
            else return -1L;
        } else if (unitLen == 3 && raw.regionMatches(true, p, "rem", 0, 3)) {
            unit = PackedCssValue.U_REM;
        } else {
            return -1L;
        }
        try {
            float val = Float.parseFloat(raw.substring(s, p));
            return Float.isFinite(val) ? PackedCssValue.length(val, unit) : -1L;
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static int keywordAtom(String raw) {
        if (raw == null) return KW_OTHER;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> KW_AUTO;
            case "none" -> KW_NONE;
            case "normal" -> KW_NORMAL;
            case "min-content" -> KW_MIN_CONTENT;
            case "max-content" -> KW_MAX_CONTENT;
            case "fit-content" -> KW_FIT_CONTENT;
            case "medium" -> KW_MEDIUM;
            case "thin" -> KW_THIN;
            case "thick" -> KW_THICK;
            case "content" -> KW_CONTENT;
            case "baseline" -> KW_BASELINE;
            case "middle" -> KW_MIDDLE;
            case "sub" -> KW_SUB;
            case "super" -> KW_SUPER;
            case "text-top" -> KW_TEXT_TOP;
            case "text-bottom" -> KW_TEXT_BOTTOM;
            case "top" -> KW_TOP;
            case "bottom" -> KW_BOTTOM;
            default -> raw.isBlank() ? KW_EMPTY : raw.indexOf((char) 37) >= 0 ? KW_JUNK_PERCENT : KW_OTHER;
        };
    }

    /** 宣言が無い（{@code PackedCssValue.NONE}）。0 px に解ける。 */
    public static final long UNDECLARED = PackedCssValue.NONE;

    public static boolean isDeclared(long v) { return v != UNDECLARED; }

    /** 宣言が無い、または {@code auto}。 */
    public static boolean isAutoOrUndeclared(long v) { return v == UNDECLARED || isAuto(v); }

    /** % を含む長さ（単位が %、% を含む解釈不能な値、% を含む式）か。 */
    public static boolean hasPercent(long v, CssExprs exprs) {
        return isPercent(v) || isJunkPercent(v) || (isCalc(v) && exprs.raw(v).indexOf((char) 37) >= 0);
    }

    /** 確定した長さ（宣言があり、auto・% を含む値・内容から決まるサイズのキーワードのどれでもない）。 */
    public static boolean isDefinite(long v, CssExprs exprs) {
        return isDeclared(v) && !isAuto(v) && !hasPercent(v, exprs) && !isIntrinsicSize(v, exprs);
    }

    /** 内容から決まるサイズのキーワード（{@code auto min-content max-content fit-content fit-content(..)}）か。旧 {@code isIntrinsicSizeKeyword} と同じ。 */
    public static boolean isIntrinsicSize(long v, CssExprs exprs) {
        return isAuto(v) || isKeyword(v, KW_MIN_CONTENT) || isKeyword(v, KW_MAX_CONTENT) || isKeyword(v, KW_FIT_CONTENT)
                || (isCalc(v) && exprs.raw(v).trim().toLowerCase(java.util.Locale.ROOT).startsWith("fit-content("));
    }

    public static boolean isKeyword(long v) { return PackedCssValue.kind(v) == PackedCssValue.K_KEYWORD; }

    public static boolean isKeyword(long v, int atom) { return isKeyword(v) && PackedCssValue.payload(v) == atom; }

    public static boolean isAuto(long v) { return isKeyword(v, KW_AUTO); }

    public static boolean isPercent(long v) {
        return PackedCssValue.kind(v) == PackedCssValue.K_LENGTH && PackedCssValue.unit(v) == PackedCssValue.U_PERCENT;
    }

    /** 解釈できない値で、% を含む文字列か（旧文字列版は % を含むとみなした）。 */
    public static boolean isJunkPercent(long v) { return isKeyword(v, KW_JUNK_PERCENT); }

    public static boolean isCalc(long v) { return PackedCssValue.kind(v) == PackedCssValue.K_CALC; }

    /** 数値＋単位の長さ（% を含む。キーワード・式は含まない）。 */
    public static boolean isLength(long v) { return PackedCssValue.kind(v) == PackedCssValue.K_LENGTH; }

    /** 単位が px の長さか。 */
    public static boolean isPixels(long v) {
        return PackedCssValue.kind(v) == PackedCssValue.K_LENGTH && PackedCssValue.unit(v) == PackedCssValue.U_PX;
    }

    /** px の長さの値（{@link #isPixels} が真のとき）。 */
    public static float pixels(long v) { return PackedCssValue.floatPayload(v); }

    /** % の長さの数値（{@link #isPercent} が真のとき。{@code 50%} なら 50）。 */
    public static float percentValue(long v) { return PackedCssValue.floatPayload(v); }

    public static long px(float value) { return PackedCssValue.length(value, PackedCssValue.U_PX); }

    /**
     * px への解決（キーワードは 0）。
     */
    public static float resolve(long v, CssExprs exprs, float percentBase, float fontPx, float rootFontPx, float vw, float vh) {
        return switch (PackedCssValue.kind(v)) {
            case PackedCssValue.K_LENGTH -> PackedCssValue.resolveLength(v, percentBase, fontPx, rootFontPx, vw, vh);
            case PackedCssValue.K_CALC -> exprs.get(PackedCssValue.payload(v)).resolve(percentBase, vw, vh, fontPx, rootFontPx);
            default -> 0f;
        };
    }

    /** 64-bit 長さ値を CSS の文字列表現へ戻す（宣言なし・不明語は null）。{@code anchor-positioning} の上書き・反転用。 */
    public static String toCssString(long v, CssExprs exprs) {
        if (v == UNDECLARED) return null;
        return switch (PackedCssValue.kind(v)) {
            case PackedCssValue.K_CALC -> exprs.raw(v);
            case PackedCssValue.K_KEYWORD -> switch (PackedCssValue.payload(v)) {
                case KW_AUTO -> "auto";
                case KW_NONE -> "none";
                case KW_NORMAL -> "normal";
                case KW_MIN_CONTENT -> "min-content";
                case KW_MAX_CONTENT -> "max-content";
                case KW_FIT_CONTENT -> "fit-content";
                case KW_MEDIUM -> "medium";
                case KW_THIN -> "thin";
                case KW_THICK -> "thick";
                case KW_CONTENT -> "content";
                case KW_BASELINE -> "baseline";
                case KW_MIDDLE -> "middle";
                case KW_SUB -> "sub";
                case KW_SUPER -> "super";
                case KW_TEXT_TOP -> "text-top";
                case KW_TEXT_BOTTOM -> "text-bottom";
                case KW_TOP -> "top";
                case KW_BOTTOM -> "bottom";
                case KW_EMPTY -> "";
                default -> null;
            };
            case PackedCssValue.K_LENGTH -> {
                float f = PackedCssValue.floatPayload(v);
                String num = (f == (long) f) ? Long.toString((long) f) : Float.toString(f);
                String unit = switch (PackedCssValue.unit(v)) {
                    case PackedCssValue.U_PX -> "px";
                    case PackedCssValue.U_PERCENT -> "%";
                    case PackedCssValue.U_EM -> "em";
                    case PackedCssValue.U_REM -> "rem";
                    case PackedCssValue.U_VW -> "vw";
                    case PackedCssValue.U_VH -> "vh";
                    case PackedCssValue.U_VMIN -> "vmin";
                    case PackedCssValue.U_VMAX -> "vmax";
                    default -> "px";
                };
                yield num + unit;
            }
            default -> null;
        };
    }

    /** 2 つの長さが同じ値（式の場合は式の原文一致）か。 */
    public static boolean same(long a, CssExprs ae, long b, CssExprs be) {
        if (isCalc(a) && isCalc(b)) return ae.raw(a).equals(be.raw(b));
        return a == b;
    }

    /** ビューポート基準の単位または式を含むか（テキスト子の判定用）。 */
    public static boolean usesViewport(long v, CssExprs ex) {
        if (isCalc(v)) return StyleResolver.valueDependsOnViewport(ex.raw(v));
        if (!isLength(v)) return false;
        int u = PackedCssValue.unit(v);
        return u == PackedCssValue.U_VW || u == PackedCssValue.U_VH
                || u == PackedCssValue.U_VMIN || u == PackedCssValue.U_VMAX;
    }
}
