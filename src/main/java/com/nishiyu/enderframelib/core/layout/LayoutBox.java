package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.NodeType;
import com.nishiyu.enderframelib.core.text.FontFace;
import com.nishiyu.enderframelib.core.text.FontStyle;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.ArrayList;
import java.util.List;

/**
 * レイアウト結果（ジオメトリ）の保持。
 *
 * 寸法を決めるアルゴリズムは {@link BoxMeasurer} とレイアウトエンジンにあり、
 * このクラスは「どこに・どれだけの大きさで置かれたか」を持つだけ。
 * フィールドが public なのは、レンダラがこの結果を読み取る値オブジェクトだからで、
 * 書き換えてよいのはレイアウト中のエンジンだけ。
 */
public class LayoutBox {

    public final HtmlNode node;
    public ComputedStyle style;
    public final List<LayoutBox> children = new ArrayList<>();

    /** この要素に対話状態（:hover / :active / :focus 等）のセレクタが関与するか。 */
    public boolean hasInteractiveRules = false;

    public int x, y, w, h;
    public String displayText = "";

    /** margin / padding / border の 4 辺（解決済みのピクセル値）。 */
    public int mT, mR, mB, mL, pT, pR, pB, pL, bT, bR, bB, bL;
    /** row-gap: 行（ブロック方向）の間隔 / column-gap: 列（インライン方向）の間隔 */
    public int rowGap, columnGap;

    public boolean isAbsolute = false, isFixed = false, isRelative = false;
    public int zIndex = 0;
    /**
     * 解決済みの 1 行分の高さ（px）。
     * 自分で複数行を並べる要素（textarea）と描画側の行送りがこれを読む。
     * 以前は描画側が line-height を独自に解析していて、% / em / normal で食い違っていた。
     */
    public float lineHeightPx = 0;
    /**
     * テキストノードの整形結果。幅・ベースライン・行高を各行が持つ。
     *
     * 以前は折り返した文字列（{@code List<String>}）だけを持っていたため、
     * 描画側が行の幅と行送りを自前で測り直していた。
     */
    public List<TextShaper.ShapedLine> shapedLines = null;

    public List<InlineFragment> inlineFragments = new ArrayList<>();
    /** エンジン固有の状態（グリッドの行列など）。 */
    public LayoutEngineState engineState;
    public SizingMode sizingMode = SizingMode.SHRINK_TO_FIT;

    public int scrollWidth = 0;
    public int scrollHeight = 0;

    /** min-content 幅のメモ化。flex の shrink ループから何度も問われるため。 */
    private int cachedMinContentWidth = -1;
    private long cachedMinContentKey = Long.MIN_VALUE;

    public LayoutBox(HtmlNode node, ComputedStyle style) {
        this.node = node;
        updateStyle(style);
    }

    /**
     * 算出スタイルを更新する（再スタイル時にジオメトリを保ったまま描画属性だけ差し替える用途を含む）。
     */
    public void updateStyle(ComputedStyle newStyle) {
        this.style = newStyle;

        // 空白の畳み方は white-space で決まる。無条件に畳んでいたため <pre> が効かなかった。
        String text = TextShaper.normalize(node.text, TextShaper.WhiteSpace.of(newStyle.get(CssProperty.WHITE_SPACE)));
        this.displayText = LayoutMetrics.applyTextTransform(text, newStyle);

        String pos = newStyle.getOrDefault(CssProperty.POSITION, "static");
        this.isFixed = "fixed".equals(pos);
        this.isAbsolute = "absolute".equals(pos) || this.isFixed;
        this.isRelative = "relative".equals(pos);
        this.zIndex = (int) newStyle.number(CssProperty.Z_INDEX, 0f);
    }

    public int horizontalDecoration() { return pL + pR + bL + bR; }
    public int verticalDecoration() { return pT + pB + bT + bB; }

    /** 通常フローに参加する子（絶対配置と display:none を除く）。 */
    public List<LayoutBox> getFlowChildren() {
        List<LayoutBox> list = new ArrayList<>();
        for (LayoutBox c : children) {
            if (!c.isAbsolute && !"none".equals(c.style.getOrDefault(CssProperty.DISPLAY, "block"))) {
                list.add(c);
            }
        }
        return list;
    }

    /**
     * これ以上縮められない幅（min-content）。
     *
     * flex の shrink 解決ループから 1 要素あたり最大 10 回問われ、そのたびに
     * 部分木全体を再帰していたため、ビューポートごとに結果をメモ化する。
     */
    public int getMinContentWidth(LayoutContext ctx) {
        long key = ((long) ctx.viewport().rootW() << 32) | (ctx.viewport().rootH() & 0xFFFFFFFFL);
        if (cachedMinContentKey == key && cachedMinContentWidth >= 0) return cachedMinContentWidth;

        int result = computeMinContentWidth(ctx);
        cachedMinContentKey = key;
        cachedMinContentWidth = result;
        return result;
    }

    private int computeMinContentWidth(LayoutContext ctx) {
        int rootW = ctx.viewport().rootW();
        int rootH = ctx.viewport().rootH();

        // min-content / auto などのキーワードは長さとして解けないので、内容から計算する経路に落とす
        String minWidthDecl = style.get(CssProperty.MIN_WIDTH);
        if (minWidthDecl != null && !LayoutMetrics.isIntrinsicSizeKeyword(minWidthDecl)) {
            return style.length(CssProperty.MIN_WIDTH, LayoutMetrics.UNBOUNDED_WIDTH, rootW, rootH);
        }

        String tag = node.tag;
        if (tag.equals("input") || tag.equals("select") || tag.equals("button") || tag.equals("progress")) {
            int inputW = 90;
            String type = node.attrs.getOrDefault("type", "text");
            if (tag.equals("input") && (type.equals("checkbox") || type.equals("radio"))) {
                inputW = LayoutMetrics.FALLBACK_REPLACED_SIZE;
            } else if (node.attrs.containsKey("size")) {
                try {
                    inputW = Integer.parseInt(node.attrs.get("size")) * 8;
                } catch (NumberFormatException ignored) {
                }
            }
            return inputW + horizontalDecoration();
        }

        if (node.nodeType == NodeType.TEXT) {
            // これ以上縮められない幅 = 最も長い「折り返し候補で切れない塊」の幅
            TextShaper.TextStyle textStyle = ctx.textStyleFor(style);
            FontFace face = ctx.faceFor(style);
            FontStyle font = textStyle.font();
            float maxWordW = 0;
            for (String segment : TextShaper.segments(this.displayText, textStyle.wordBreak())) {
                String word = segment.strip();
                if (!word.isEmpty()) maxWordW = Math.max(maxWordW, face.advance(word, font));
            }
            return (int) Math.ceil(maxWordW);
        }

        if (children.isEmpty()) {
            return this.w > 0 ? this.w : LayoutMetrics.FALLBACK_REPLACED_SIZE;
        }

        int max = 0;
        for (LayoutBox c : getFlowChildren()) {
            max = Math.max(max, c.getMinContentWidth(ctx) + c.mL + c.mR);
        }
        return max + horizontalDecoration();
    }

    /** 寸法が変わるため、測定パスの先頭でメモ化を捨てる。 */
    void invalidateMinContentCache() {
        cachedMinContentKey = Long.MIN_VALUE;
        cachedMinContentWidth = -1;
    }

    public void applyRelativeOffset(int dx, int dy) {
        this.x += dx;
        this.y += dy;
        for (LayoutBox child : children) child.applyRelativeOffset(dx, dy);
        for (InlineFragment frag : inlineFragments) {
            frag.x += dx;
            frag.y += dy;
        }
    }

    /**
     * 寸法を決める。実装は {@link BoxMeasurer}。
     * エンジンが子に対して再帰的に呼ぶため、入口はここに残している。
     */
    public void measure(int tentativeInnerW, int parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        BoxMeasurer.measure(this, tentativeInnerW, parentH, fmtCtx, ctx);
    }

    /** 高さだけ再解決する（flex grow/shrink、grid stretch の後処理用）。 */
    public void resolveFlexibleHeights(int parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        BoxMeasurer.resolveFlexibleHeights(this, parentH, fmtCtx, ctx);
    }

    /** 決まった寸法をもとに自分と子の位置を確定する。 */
    public void layout(int parentX, int parentY, int parentOuterW, int parentOuterH, LayoutContext ctx) {
        if ("none".equals(style.getOrDefault(CssProperty.DISPLAY, "block"))) return;

        int rootW = ctx.viewport().rootW();
        int rootH = ctx.viewport().rootH();

        int refX = this.isFixed ? 0 : parentX;
        int refY = this.isFixed ? 0 : parentY;
        int refW = this.isFixed ? rootW : parentOuterW;
        int refH = this.isFixed ? rootH : parentOuterH;

        if (this.isAbsolute) {
            if (style.containsKey(CssProperty.RIGHT)) {
                this.x = refX + refW - this.w - style.length(CssProperty.RIGHT, refW, rootW, rootH) - mR;
            } else {
                this.x = refX + style.length(CssProperty.LEFT, refW, rootW, rootH) + mL;
            }
            if (style.containsKey(CssProperty.BOTTOM)) {
                this.y = refY + refH - this.h - style.length(CssProperty.BOTTOM, refH, rootW, rootH) - mB;
            } else {
                this.y = refY + style.length(CssProperty.TOP, refH, rootW, rootH) + mT;
            }
        } else {
            this.x = refX + mL;
            this.y = refY + mT;
        }

        int scrollX = ctx.scrollState().getScrollX(node.getId());
        int scrollY = ctx.scrollState().getScrollY(node.getId());

        int innerX = this.x + pL + bL - scrollX;
        int innerY = this.y + pT + bT - scrollY;

        LayoutEngine engine = LayoutEngineFactory.getEngine(style.getOrDefault(CssProperty.DISPLAY, "block"));
        engine.arrangeChildren(this, innerX, innerY, ctx);

        for (LayoutBox child : children) {
            if ("none".equals(child.style.getOrDefault(CssProperty.DISPLAY, "block"))) continue;
            if (child.isAbsolute) child.layout(this.x - scrollX, this.y - scrollY, this.w, this.h, ctx);
        }

        applyRelativePositioning(parentOuterW, parentOuterH, rootW, rootH);
        updateScrollExtent(scrollX, scrollY);
    }

    private void applyRelativePositioning(int parentOuterW, int parentOuterH, int rootW, int rootH) {
        if (!this.isRelative) return;

        int offX = 0, offY = 0;
        if (style.containsKey(CssProperty.LEFT)) offX += style.length(CssProperty.LEFT, parentOuterW, rootW, rootH);
        else if (style.containsKey(CssProperty.RIGHT)) offX -= style.length(CssProperty.RIGHT, parentOuterW, rootW, rootH);
        if (style.containsKey(CssProperty.TOP)) offY += style.length(CssProperty.TOP, parentOuterH, rootW, rootH);
        else if (style.containsKey(CssProperty.BOTTOM)) offY -= style.length(CssProperty.BOTTOM, parentOuterH, rootW, rootH);

        if (offX != 0 || offY != 0) applyRelativeOffset(offX, offY);
    }

    /**
     * スクロール範囲を更新する。
     * エンジン固有の子（グリッドアイテムなど）は {@link LayoutEngineState} が自分で足し込む。
     */
    private void updateScrollExtent(int scrollX, int scrollY) {
        ScrollExtent extent = new ScrollExtent(this.x + pL + bL, this.y + pT + bT);
        for (LayoutBox child : children) {
            if ("none".equals(child.style.getOrDefault(CssProperty.DISPLAY, "block")) || child.isAbsolute) continue;
            extent.include(child, scrollX, scrollY);
        }
        if (engineState != null) engineState.contributeScrollExtent(extent, scrollX, scrollY);

        this.scrollWidth = Math.max(this.w, extent.right() - this.x + pR + bR);
        this.scrollHeight = Math.max(this.h, extent.bottom() - this.y + pB + bB);
    }

    /** インライン整形で 1 行に置かれた断片（テキスト 1 語、またはインラインブロック 1 つ）。 */
    public static class InlineFragment {
        public LayoutBox box;
        public int x, y, w, h;
        /**
         * 断片の上端からベースラインまでの距離（px）。
         * これが無かったため描画側は「高さ 8px の箱」を仮定した行内センタリングで
         * 位置を推測していて、フォントサイズが混ざった行で文字が揃わなかった。
         */
        public float baseline;
        public String text;
        public List<LayoutBox> parents;

        public InlineFragment(LayoutBox box, int x, int y, int w, int h, String text) {
            this.box = box;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.text = text;
        }

        public boolean isText() { return text != null; }
    }
}
