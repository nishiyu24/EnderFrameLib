package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display;
import com.nishiyu.enderframelib.core.layout.LayoutBox.ScrollExtent;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ConstraintSpace;
import com.nishiyu.enderframelib.core.css.CssProperty.CssKeywords;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.FontResolver;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.FontStyle;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * 整形コンテキストのエンジン（block / flex / grid / table）の基底と、inner display からの選択。
 *
 * 3 つのレイアウトエンジンに共通する処理もここに置く。
 * 以前は「絶対配置子の確定」「justify-content の余白配分」
 * 「display:none のフィルタ」「太字判定」が各エンジンにコピーされていた
 * （合計で約 400 行の重複）。共有できるものはここに集約する。
 */
public abstract class LayoutEngine {

    /** 基底クラスの初期化中にサブクラスを生成しないよう、インスタンスは別ホルダーに置く。 */
    private static final class Engines {
        static final LayoutEngine BLOCK = new BlockLayoutEngine();
        static final LayoutEngine FLEX = new FlexLayoutEngine();
        static final LayoutEngine GRID = new GridLayoutEngine();
        static final LayoutEngine TABLE = new TableLayoutEngine();
    }

    /**
     * inner display（子をどう整形するか）から整形コンテキストのエンジンを選ぶ。
     *
     * 何が壊れていたか: 以前は {@code display} の文字列で選び、flex / grid 以外は全部ブロックに
     * 落としていた。{@code inline-flex} も外側の振る舞い（インライン）と内側の整形（flex）を
     * 区別できず、{@code table} / {@code table-*} はブロックとして縦に積まれていた。
     */
    public static LayoutEngine getEngine(Display display) {
        if (display == null) return Engines.BLOCK;
        return switch (display.inner()) {
            case FLEX -> Engines.FLEX;
            case GRID -> Engines.GRID;
            case TABLE, TABLE_ROW_GROUP, TABLE_ROW -> Engines.TABLE;
            // flow / flow-root / table-cell / table-caption はブロックコンテナ
            default -> Engines.BLOCK;
        };
    }

    // ==========================================
    // エンジン SPI
    // ==========================================
    //
    // 制約（ConstraintSpace）を受け取り、内容の寸法を返す。箱自身の幅・高さ（装飾・指定値・
    // min / max）は BoxMeasurer が決め、エンジンは子の寸法と配置だけを受け持つ。
    // 以前はエンジンが box.w / box.h を自分で書き換えていて、「shrink-to-fit なら内容幅 + 装飾」
    // 「auto なら内容高 + 装飾」が各エンジンにコピーされていた。

    /**
     * 子を測り、内容の幅（装飾を含まない）を返す。箱が shrink-to-fit なら
     * {@link BoxMeasurer} がこの幅を箱の幅にする。
     */
    public abstract float measureContent(LayoutBox box, ConstraintSpace space, LayoutContext ctx);

    /** 幅が確定した後、内容の高さ（装飾を含まない）を返す。箱の高さが auto ならこれが高さになる。 */
    public abstract float contentBlockSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx);

    /** 箱の高さが決まった後の仕上げ（伸長・stretch・絶対配置の子の測定）。 */
    public abstract void finishLayout(LayoutBox box, ConstraintSpace space, LayoutContext ctx);

    public abstract void arrangeChildren(LayoutBox box, float innerX, float innerY, LayoutContext ctx);


    protected boolean isDisplayNone(ComputedStyle style) {
        return "none".equals(style.getOrDefault(CssProperty.DISPLAY, "block"));
    }

    protected boolean isBold(ComputedStyle style) {
        return LayoutMetrics.isBold(style);
    }

    /**
     * 絶対配置の子を、確定した内容領域を基準に測り直す。
     * 3 エンジンに同じ 5 行がコピーされていた部分。
     */
    protected void measureAbsoluteChildren(LayoutBox box, LayoutContext ctx) {
        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        for (LayoutBox child : box.children) {
            if (child.isAbsolute && !isDisplayNone(child.style)) {
                LayoutBox cb = child.findContainingBlock();
                float cbW, cbH;
                if (cb == null) {
                    cbW = rootW;
                    cbH = rootH;
                } else {
                    // 絶対配置の包含ブロックは padding box（CSS 2.2 §10.1）。以前は内容ボックスで、
                    // padding を持つ親の中の width / height の % が小さく出ていた
                    cbW = Math.max(0, cb.w - cb.bL - cb.bR);
                    cbH = Math.max(0, cb.h - cb.bT - cb.bB);
                }
                child.measure(ConstraintSpace.block(cbW, cbH), ctx);
            }
        }
    }

    /**
     * 主軸方向の余白配分（{@code justify-content}）。
     *
     * @param start        配分前の開始座標
     * @param availableMain 利用できる主軸サイズ
     * @param itemsTotal   アイテムの合計サイズ（gap を含まない）
     * @param baseGap      スタイル指定の gap
     * @param itemCount    アイテム数
     */
    protected static MainAxisSpacing distributeMainAxis(String justify, float start, float availableMain,
                                                 float itemsTotal, float baseGap, int itemCount) {
        float totalWithGap = itemsTotal + baseGap * Math.max(0, itemCount - 1);
        float container = Math.max(totalWithGap, availableMain);
        float gap = baseGap;

        switch (justify == null ? "flex-start" : justify) {
            case "space-between" -> {
                if (itemCount > 1) gap = Math.max(0, container - itemsTotal) / (itemCount - 1);
            }
            case "space-around" -> {
                if (itemCount > 0) {
                    gap = Math.max(0, container - itemsTotal) / itemCount;
                    start += gap / 2;
                }
            }
            case "space-evenly" -> {
                if (itemCount > 0) {
                    gap = Math.max(0, container - itemsTotal) / (itemCount + 1);
                    start += gap;
                }
            }
            case "center" -> start += Math.max(0, container - totalWithGap) / 2;
            case "flex-end", "end" -> start += Math.max(0, container - totalWithGap);
            default -> {
                // flex-start / start / 未知の値は先頭詰め
            }
        }
        return new MainAxisSpacing(start, gap);
    }

    /** {@link #distributeMainAxis} の結果（開始座標とアイテム間隔）。 */
    protected record MainAxisSpacing(float start, float gap) {}

    /**
     * レイアウトエンジンがボックスに持たせる、エンジン固有の状態。
     *
     * 以前は {@code LayoutBox.engineState} が {@code Object} で、スクロール範囲を求めるために
     * {@code LayoutBox} が {@code instanceof GridLayoutEngine.GridState} でエンジン内部型を
     * 覗き込んでいた（core/layout の中で循環していた）。
     * 範囲への寄与をこのインターフェースの責務にすることでその参照を断つ。
     */
    public interface LayoutEngineState {

        /**
         * 通常フローの子以外に、このエンジンが配置した要素の範囲を足し込む。
         * 何も追加しないエンジンは実装しなくてよい（既定は何もしない）。
         */
        default void contributeScrollExtent(ScrollExtent extent, float scrollX, float scrollY) {
        }
    }

    /**
     * レイアウト全体で共有する定数と、文字列まわりの小さな共通処理。
     *
     * 以前はこれらが各エンジン・LayoutBox・レンダラにコピーされていて
     * （line-height の解析が 4 コピー、単語分割の正規表現が 4 コピー、
     *  太字判定が 4 コピー、基準フォントサイズ 16 が 11 箇所）、
     * 片方だけ直すと測定と配置がずれる状態だった。ここが唯一の出所。
     *
     * 折り返し・空白・省略そのものは {@link TextShaper} が持つ。
     */
    public static final class LayoutMetrics {

        /** 「幅の制約が無い」ことを表す十分大きな幅。 */
        public static final int UNBOUNDED_WIDTH = 9999;

        /** 置換要素のサイズが取得できなかったときの既定サイズ（px）。 */
        public static final int FALLBACK_REPLACED_SIZE = 16;
        /** リモート画像のサイズが未取得のときの暫定サイズ（px）。 */
        public static final int PENDING_IMAGE_SIZE = 32;

        /** font-weight が太字扱いになる値。 */
        private static final Pattern BOLD_FONT_WEIGHT = Pattern.compile("(?i)bold|bolder|700|800|900");

        private LayoutMetrics() {}

        /**
         * 長さではなく内容から決まるサイズのキーワードか。
         *
         * 何が壊れていたか: これらは {@code LengthResolver} で解析できず 0 px になるため、
         * ua.css の {@code button, input { min-width: min-content }} が
         * 「最小幅 0」として扱われ、flex の shrink でボタンが潰れていた。
         */
        public static boolean isIntrinsicSizeKeyword(String value) {
            if (value == null) return false;
            String v = value.trim().toLowerCase();
            return v.equals("min-content") || v.equals("max-content")
                    || v.equals("fit-content") || v.startsWith("fit-content(") || v.equals("auto");
        }

        public static boolean isBold(ComputedStyle style) {
            return isBoldWeight(style.getOrDefault(CssProperty.FONT_WEIGHT, ""));
        }

        public static boolean isBoldWeight(String weight) {
            return weight != null && BOLD_FONT_WEIGHT.matcher(weight.trim()).matches();
        }

        /**
         * {@code text-transform} を適用する。
         *
         * 何が壊れていたか: 以前は描画側だけが uppercase を適用し、
         * 測定側は変換前の文字列を測っていたため、大文字化したテキストは
         * 幅を狭く見積もられて描画がはみ出していた。しかもインラインフラグメント経路では
         * 変換そのものが抜けていた。変換は表示文字列を作るこの 1 箇所だけで行う。
         */
        public static String applyTextTransform(String text, ComputedStyle style) {
            if (text == null || text.isEmpty()) return text;
            return switch (style.keywordOf(CssProperty.TEXT_TRANSFORM, CssKeywords.TextTransform.class)) {
                case UPPERCASE -> text.toUpperCase(java.util.Locale.ROOT);
                case LOWERCASE -> text.toLowerCase(java.util.Locale.ROOT);
                case CAPITALIZE -> capitalizeWords(text);
                case NONE -> text;
            };
        }

        private static String capitalizeWords(String text) {
            StringBuilder sb = new StringBuilder(text.length());
            boolean atWordStart = true;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                sb.append(atWordStart ? Character.toUpperCase(c) : c);
                atWordStart = Character.isWhitespace(c);
            }
            return sb.toString();
        }

        /**
         * 1 行が占める高さ（px）。
         *
         * 何が壊れていたか: 以前は line-height を「フォントサイズに対する倍率」に
         * 変換して返していたため、{@code line-height: 20px} が {@code 20/16} の倍率になり、
         * 使う側が {@code 16 * fontScale} を掛け戻すことで
         * 実際には {@code 20 * fontScale} px になっていた（font-size に比例してしまう）。
         * 長さ指定は長さのまま、倍率指定だけがフォントサイズに比例する。
         *
         * {@code normal} は書体の ascent + descent + 行間から決まる。
         * 以前は 1.375 という固定値だった。
         */
        public static float lineHeightPx(ComputedStyle style, FontFace face, FontStyle font) {
            String raw = style.get(CssProperty.LINE_HEIGHT);
            if (raw == null) return face.normalLineHeight(font);

            String value = raw.trim();
            if (value.isEmpty() || value.equalsIgnoreCase("normal")) return face.normalLineHeight(font);

            try {
                if (value.endsWith("px")) {
                    return Float.parseFloat(value.substring(0, value.length() - 2).trim());
                }
                if (value.endsWith("%")) {
                    return Float.parseFloat(value.substring(0, value.length() - 1).trim()) / 100f * font.fontSizePx();
                }
                if (value.endsWith("em")) {
                    return Float.parseFloat(value.substring(0, value.length() - 2).trim()) * font.fontSizePx();
                }
                return Float.parseFloat(value) * font.fontSizePx();
            } catch (NumberFormatException e) {
                return face.normalLineHeight(font);
            }
        }
    }

    /**
     * レイアウト 1 回分の外部依存。
     *
     * 書体の引き当てをここに置いているのは、測定と整形が必ず同じ
     * {@link FontFace} を通るようにするため。
     * 以前は測定が常に既定フォントを使い、描画だけが {@code font-family} を見ていた。
     */
    public record LayoutContext(
            Viewport viewport,
            IntrinsicSizeResolver intrinsicResolver,
            ScrollState scrollState,
            FontResolver fonts
    ) {

        /** 算出スタイルの {@code font-family} に対応する書体。 */
        public FontFace faceFor(ComputedStyle style) {
            return fonts.resolve(style.get(CssProperty.FONT_FAMILY));
        }

        /** 算出スタイルから測定に必要な指定だけを取り出す。 */
        public FontStyle fontStyleFor(ComputedStyle style) {
            return new FontStyle(
                    style.fontSizePx(),
                    LayoutMetrics.isBold(style),
                    style.keywordOf(CssProperty.FONT_STYLE, CssKeywords.FontStyle.class) != CssKeywords.FontStyle.NORMAL,
                    style.length(CssProperty.LETTER_SPACING, 0, 0, 0));
        }

        /** 整形（折り返し・空白・省略）に必要なスタイルをまとめる。 */
        public TextShaper.TextStyle textStyleFor(ComputedStyle style) {
            FontFace face = faceFor(style);
            FontStyle font = fontStyleFor(style);
            CssKeywords.OverflowWrap overflowWrap = style.keywordOf(CssProperty.OVERFLOW_WRAP, CssKeywords.OverflowWrap.class);
            return new TextShaper.TextStyle(
                    font,
                    TextShaper.WhiteSpace.of(style.get(CssProperty.WHITE_SPACE)),
                    TextShaper.WordBreak.of(style.get(CssProperty.WORD_BREAK)),
                    overflowWrap != CssKeywords.OverflowWrap.NORMAL,
                    style.keywordOf(CssProperty.TEXT_OVERFLOW, CssKeywords.TextOverflow.class) == CssKeywords.TextOverflow.ELLIPSIS,
                    LayoutMetrics.lineHeightPx(style, face, font));
        }

        public record Viewport(int rootW, int rootH) {}

        /**
         * 置換要素の固有サイズ（画像やエンティティなど）を解決するためのインターフェース。
         * core パッケージが Minecraft やサーバー側のリソース状態に直接依存することを防ぎます。
         */
        public interface IntrinsicSizeResolver {
            int[] getSize(String src);
        }

        /**
         * 子の測定に親から降りてくる整形コンテキスト。
         *
         * {@code parentDisplay} には、ブロックなら {@code "block"}、グリッドなら {@code "grid"}、
         * フレックスなら {@code flex-direction} の値（{@code "row"} / {@code "column"} /
         * {@code "row-reverse"} …）が入る。
         *
         * 何が壊れていたか: 以前この判定は {@code "flex-row"} / {@code "flex-column"} という
         * 前置きを探していたが、その文字列を作る箇所は 1 つも無く、両メソッドは常に false だった。
         * そのため幅指定の無いフレックスアイテムが shrink-to-fit にならず、いったん親幅いっぱいに
         * 広がってから shrink で削られる挙動になり、{@code flex: 1} の等分が効かなかった。
         */
        public record FormattingContext(String parentDisplay, String alignItems, boolean isShrink) {
            /** 通常のブロックフロー（幅いっぱいに広がる）。 */
            public static final FormattingContext BLOCK = new FormattingContext("block", "stretch", false);

            public boolean isFlexRow() { return parentDisplay.startsWith("row"); }
            public boolean isFlexColumn() { return parentDisplay.startsWith("column"); }
        }

        /**
         * 親が子の整形に渡す制約（エンジン SPI の入力）。
         *
         * @param availableInlineSize 内容が使える幅（px）。shrink-to-fit の上限でもある
         * @param percentageBlockSize 高さの % を解く基準（px）
         * @param parent              親の整形コンテキスト（flex の方向・揃え・shrink の有無）
         *
         * 以前はこの 3 つが {@code measure(box, w, h, fmtCtx, ctx)} の別々の引数だった。
         */
        public record ConstraintSpace(float availableInlineSize, float percentageBlockSize, FormattingContext parent) {

            /** 通常のブロックフローの子として、幅と高さの基準だけを与える。 */
            public static ConstraintSpace block(float availableInlineSize, float percentageBlockSize) {
                return new ConstraintSpace(availableInlineSize, percentageBlockSize, FormattingContext.BLOCK);
            }
        }

        /**
         * スクロール位置。節そのものをキーにするので、同じ id を持つ別の節とは混ざらない。
         * 節が文書から外れたら位置も一緒に回収されるよう、キーは弱参照で持つ。
         */
        public static class ScrollState {
            private final Map<HtmlNode, int[]> offsetsByNode = Collections.synchronizedMap(new WeakHashMap<>());

            public int getScrollX(HtmlNode node) {
                int[] offset = node != null ? offsetsByNode.get(node) : null;
                return offset != null ? offset[0] : 0;
            }

            public int getScrollY(HtmlNode node) {
                int[] offset = node != null ? offsetsByNode.get(node) : null;
                return offset != null ? offset[1] : 0;
            }

            public void setScroll(HtmlNode node, int x, int y) {
                if (node == null) return;
                offsetsByNode.put(node, new int[]{x, y});
            }

            public void clear() {
                offsetsByNode.clear();
            }
        }
    }
}
