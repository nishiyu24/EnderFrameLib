package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.NodeType;

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
    public final ComputedStyle style;
    public final List<LayoutBox> children = new ArrayList<>();

    public int x, y, w, h;
    public String displayText = "";

    /** margin / padding / border の 4 辺（解決済みのピクセル値）。 */
    public int mT, mR, mB, mL, pT, pR, pB, pL, bT, bR, bB, bL;
    /** row-gap: 行（ブロック方向）の間隔 / column-gap: 列（インライン方向）の間隔 */
    public int rowGap, columnGap;

    public boolean isAbsolute = false, isFixed = false, isRelative = false;
    public int zIndex = 0;
    /** 基準フォントサイズ（16px）に対する倍率。 */
    public float fontSize = 1.0f;
    /** テキストノードの折り返し結果。 */
    public List<String> textLines = null;

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
        this.style = style;

        String text = LayoutMetrics.collapseWhitespace(node.text);
        this.displayText = text != null ? text : "";

        String pos = style.getOrDefault(CssProperty.POSITION, "static");
        this.isFixed = "fixed".equals(pos);
        this.isAbsolute = "absolute".equals(pos) || this.isFixed;
        this.isRelative = "relative".equals(pos);
        this.zIndex = (int) style.number(CssProperty.Z_INDEX, 0f);
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

        if (style.containsKey(CssProperty.MIN_WIDTH)) {
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
            int maxWordW = 0;
            boolean bold = LayoutMetrics.isBold(style);
            for (String word : LayoutMetrics.splitWords(this.displayText)) {
                if (!word.trim().isEmpty()) {
                    maxWordW = Math.max(maxWordW, ctx.fontMetrics().getDisplayWidth(word, this.fontSize, bold));
                }
            }
            return maxWordW;
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
