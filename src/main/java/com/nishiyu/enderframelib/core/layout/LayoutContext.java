package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.text.FontFace;
import com.nishiyu.enderframelib.core.text.FontResolver;
import com.nishiyu.enderframelib.core.text.FontStyle;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
                "italic".equals(style.keyword(CssProperty.FONT_STYLE, "normal")),
                style.length(CssProperty.LETTER_SPACING, 0, 0, 0));
    }

    /** 整形（折り返し・空白・省略）に必要なスタイルをまとめる。 */
    public TextShaper.TextStyle textStyleFor(ComputedStyle style) {
        FontFace face = faceFor(style);
        FontStyle font = fontStyleFor(style);
        String overflowWrap = style.keyword(CssProperty.OVERFLOW_WRAP, "normal");
        return new TextShaper.TextStyle(
                font,
                TextShaper.WhiteSpace.of(style.get(CssProperty.WHITE_SPACE)),
                TextShaper.WordBreak.of(style.get(CssProperty.WORD_BREAK)),
                overflowWrap.equals("break-word") || overflowWrap.equals("anywhere"),
                "ellipsis".equals(style.keyword(CssProperty.TEXT_OVERFLOW, "clip")),
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
        public boolean isFlexRow() { return parentDisplay.startsWith("row"); }
        public boolean isFlexColumn() { return parentDisplay.startsWith("column"); }
    }

    public static class ScrollState {
        private final Map<String, int[]> scrollOffsets = new ConcurrentHashMap<>();

        public int getScrollX(String nodeId) {
            return scrollOffsets.containsKey(nodeId) ? scrollOffsets.get(nodeId)[0] : 0;
        }
        public int getScrollY(String nodeId) {
            return scrollOffsets.containsKey(nodeId) ? scrollOffsets.get(nodeId)[1] : 0;
        }
        public void setScroll(String nodeId, int x, int y) {
            scrollOffsets.put(nodeId, new int[]{x, y});
        }

        public void clear() {
            scrollOffsets.clear();
        }
    }
}
