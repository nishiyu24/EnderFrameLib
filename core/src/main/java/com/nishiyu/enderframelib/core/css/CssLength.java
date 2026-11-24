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
            KW_EMPTY = 10, KW_JUNK_PERCENT = 11;

    /** 文字列から値を作る。式は {@code exprs} に積んで添字を持つ。 */
    static long parse(String raw, CssExprs.Builder exprs) {
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
            default -> raw.isBlank() ? KW_EMPTY : raw.indexOf((char) 37) >= 0 ? KW_JUNK_PERCENT : KW_OTHER;
        };
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

    public static long px(float value) { return PackedCssValue.length(value, PackedCssValue.U_PX); }

    /**
     * px へ解く。{@code percentBase} は % の基準、{@code fontPx} は {@code em} の基準（その要素のフォントサイズ）。
     * キーワードは 0（旧 {@code CompiledLength.ZERO} と同じ）。
     */
    public static float resolve(long v, CssExprs exprs, float percentBase, float fontPx, float rootFontPx, float vw, float vh) {
        return switch (PackedCssValue.kind(v)) {
            case PackedCssValue.K_LENGTH -> PackedCssValue.resolveLength(v, percentBase, fontPx, rootFontPx, vw, vh);
            case PackedCssValue.K_CALC -> exprs.get(PackedCssValue.payload(v)).resolve(percentBase, vw, vh, fontPx, rootFontPx);
            default -> 0f;
        };
    }
}
