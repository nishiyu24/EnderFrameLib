package com.nishiyu.enderframelib.core.kernel.style;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.kernel.memory.AtomTable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 算出スタイルの 5 つの不変グループ（型付き構造体）。文字列の宣言マップを毎回読み直す代わりに、
 * レイアウト・描画はここのプリミティブ値を直接読む。
 *
 * <p>値の等しい構造体は {@link #intern} で同じインスタンスに畳むので、継承するグループは参照のコピー
 * （{@code child.font == parent.font}）で済み、似た要素どうしが 1 つの構造体を共有する（Copy-on-Write）。</p>
 *
 * <p>キーワード値（display / white-space など）は {@link AtomTable} の atom で持つ。</p>
 */
public final class StyleStructs {

    public record InheritedFontStruct(float fontSizePx, float lineHeightPx, short fontWeight, int fontStyleAtom,
                                      int fontFamilyAtom) {}

    public record InheritedTextStruct(int colorArgb, float letterSpacingPx, int textAlignAtom, int whiteSpaceAtom,
                                      int wordBreakAtom, int directionAtom) {}

    public record BoxGeometryStruct(long width, long height, long minWidth, long maxWidth, long minHeight,
                                    long maxHeight, float aspectRatio, float flexGrow, float flexShrink,
                                    long flexBasis, int displayAtom, int positionAtom, byte boxSizing,
                                    byte overflowX, byte overflowY) {}

    public record SurroundStruct(long marginT, long marginR, long marginB, long marginL,
                                 long paddingT, long paddingR, long paddingB, long paddingL,
                                 float borderT, float borderR, float borderB, float borderL,
                                 int borderColorT, int borderColorR, int borderColorB, int borderColorL,
                                 int borderStyleT, int borderStyleR, int borderStyleB, int borderStyleL,
                                 long radiusTL, long radiusTR, long radiusBR, long radiusBL) {}

    public record PaintEffectStruct(float opacity, int bgColorArgb, int zIndex, int transformOpId, int filterOpId,
                                    int transitionId, int animationId) {}

    public final InheritedFontStruct font;
    public final InheritedTextStruct text;
    public final BoxGeometryStruct box;
    public final SurroundStruct surround;
    public final PaintEffectStruct paint;

    private StyleStructs(InheritedFontStruct font, InheritedTextStruct text, BoxGeometryStruct box,
                         SurroundStruct surround, PaintEffectStruct paint) {
        this.font = font;
        this.text = text;
        this.box = box;
        this.surround = surround;
        this.paint = paint;
    }

    public static StyleStructs of(InheritedFontStruct font, InheritedTextStruct text, BoxGeometryStruct box,
                                  SurroundStruct surround, PaintEffectStruct paint) {
        return new StyleStructs(font, text, box, surround, paint);
    }

    /** 値の等しい構造体を 1 つに畳む入れ物。 */
    private static final ConcurrentHashMap<Object, Object> POOL = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public static <T extends Record> T intern(T value) {
        Object prev = POOL.putIfAbsent(value, value);
        return prev != null ? (T) prev : value;
    }

    /** 畳まれている構造体の種類数（診断・テスト用）。 */
    public static int pooledCount() { return POOL.size(); }

    /**
     * ロード時のコンパイラ。{@code calc()} の命令表とキーワードの atom 表を持つ（既定では共有の atom 表）。
     */
    public static final class Compiler {
        private final AtomTable atoms;
        private final CalcProgram.Table calcs = new CalcProgram.Table();

        public Compiler(AtomTable atoms) { this.atoms = atoms; }

        public Compiler() { this(AtomTable.shared()); }

        public CalcProgram.Table calcs() { return calcs; }

        private long packed(ComputedStyle s, String prop) {
            String v = s.get(prop);
            if (v == null) return PackedCssValue.NONE;
            synchronized (calcs) {
                return PackedCssValue.parse(v, atoms, calcs);
            }
        }

        private int atomOf(ComputedStyle s, String prop) {
            String v = s.get(prop);
            return v == null || v.isBlank() ? AtomTable.NONE : atoms.intern(v.trim().toLowerCase(java.util.Locale.ROOT));
        }

        /**
         * {@code style} から 5 つのグループを作る。{@code parent} が非 null で継承グループの値が等しければ、
         * 親のインスタンスをそのまま使う。
         */
        public StyleStructs compile(ComputedStyle style, StyleStructs parent) {
            InheritedFontStruct font = intern(new InheritedFontStruct(style.fontSizePx(), style.lineHeightPx(),
                    fontWeight(style.get(CssProperty.FONT_WEIGHT)),
                    atomOf(style, CssProperty.FONT_STYLE),
                    atomOf(style, CssProperty.FONT_FAMILY)));
            InheritedTextStruct text = intern(new InheritedTextStruct(style.color(CssProperty.COLOR, 0xFF000000),
                    style.length(CssProperty.LETTER_SPACING, 0, 0, 0), atomOf(style, CssProperty.TEXT_ALIGN),
                    atomOf(style, CssProperty.WHITE_SPACE), atomOf(style, CssProperty.WORD_BREAK),
                    atomOf(style, CssProperty.DIRECTION)));
            if (parent != null) {
                if (parent.font.equals(font)) font = parent.font;
                if (parent.text.equals(text)) text = parent.text;
            }
            BoxGeometryStruct box = intern(new BoxGeometryStruct(
                    packed(style, CssProperty.WIDTH), packed(style, CssProperty.HEIGHT),
                    packed(style, CssProperty.MIN_WIDTH), packed(style, CssProperty.MAX_WIDTH),
                    packed(style, CssProperty.MIN_HEIGHT), packed(style, CssProperty.MAX_HEIGHT),
                    style.number(CssProperty.ASPECT_RATIO, 0f), style.number(CssProperty.FLEX_GROW, 0f),
                    style.number(CssProperty.FLEX_SHRINK, 1f), packed(style, CssProperty.FLEX_BASIS),
                    atomOf(style, CssProperty.DISPLAY),
                    atomOf(style, CssProperty.POSITION),
                    (byte) (style.isBorderBox() ? 1 : 0),
                    (byte) style.overflowX().ordinal(), (byte) style.overflowY().ordinal()));
            SurroundStruct surround = intern(new SurroundStruct(
                    packed(style, "margin-top"), packed(style, "margin-right"),
                    packed(style, "margin-bottom"), packed(style, "margin-left"),
                    packed(style, "padding-top"), packed(style, "padding-right"),
                    packed(style, "padding-bottom"), packed(style, "padding-left"),
                    style.length("border-top-width", 0, 0, 0), style.length("border-right-width", 0, 0, 0),
                    style.length("border-bottom-width", 0, 0, 0), style.length("border-left-width", 0, 0, 0),
                    style.color("border-top-color", 0xFF000000), style.color("border-right-color", 0xFF000000),
                    style.color("border-bottom-color", 0xFF000000), style.color("border-left-color", 0xFF000000),
                    atomOf(style, "border-top-style"), atomOf(style, "border-right-style"),
                    atomOf(style, "border-bottom-style"), atomOf(style, "border-left-style"),
                    packed(style, CssProperty.BORDER_TOP_LEFT_RADIUS), packed(style, CssProperty.BORDER_TOP_RIGHT_RADIUS),
                    packed(style, CssProperty.BORDER_BOTTOM_RIGHT_RADIUS),
                    packed(style, CssProperty.BORDER_BOTTOM_LEFT_RADIUS)));
            PaintEffectStruct paint = intern(new PaintEffectStruct(style.number(CssProperty.OPACITY, 1f),
                    style.color(CssProperty.BACKGROUND_COLOR, 0), (int) style.number(CssProperty.Z_INDEX, 0f),
                    atomOf(style, "transform"), atomOf(style, "filter"), atomOf(style, "transition"),
                    atomOf(style, "animation")));
            return new StyleStructs(font, text, box, surround, paint);
        }

        private static short fontWeight(String v) {
            if (v == null) return 400;
            String s = v.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (s) {
                case "normal" -> 400;
                case "bold" -> 700;
                default -> {
                    try {
                        yield (short) Math.round(Float.parseFloat(s));
                    } catch (NumberFormatException e) {
                        yield 400;
                    }
                }
            };
        }
    }

    /** 箱の幅を px で返す（{@code auto} / 未指定は NaN）。 */
    public float widthPx(float percentBase, float rootFontSize, float vw, float vh) {
        return resolve(box.width(), percentBase, font.fontSizePx(), rootFontSize, vw, vh, null);
    }

    /**
     * 詰めた長さ値を px にする。K_CALC は {@code calcs}（と評価用スタック）が要る。キーワード・未指定は NaN。
     */
    public static float resolve(long v, float percentBase, float fontSize, float rootFontSize, float vw, float vh,
                                CalcResolver calcs) {
        switch (PackedCssValue.kind(v)) {
            case PackedCssValue.K_LENGTH, PackedCssValue.K_NUMBER:
                return PackedCssValue.resolveLength(v, percentBase, fontSize, rootFontSize, vw, vh);
            case PackedCssValue.K_CALC:
                return calcs == null ? Float.NaN : calcs.eval(PackedCssValue.payload(v), percentBase, fontSize,
                        rootFontSize, vw, vh);
            default:
                return Float.NaN;
        }
    }

    /** K_CALC を解く側。スタック配列を持ち回って割り当てを避ける。 */
    public static final class CalcResolver {
        private final CalcProgram.Table table;
        private float[] stack = new float[16];

        public CalcResolver(CalcProgram.Table table) { this.table = table; }

        public float eval(int index, float percentBase, float fontSize, float rootFontSize, float vw, float vh) {
            CalcProgram p = table.get(index);
            if (stack.length < p.stackSize()) stack = new float[p.stackSize()];
            return p.eval(stack, percentBase, fontSize, rootFontSize, vw, vh);
        }
    }
}
