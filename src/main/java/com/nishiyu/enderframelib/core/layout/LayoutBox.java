package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlNode.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutEngineState;
import com.nishiyu.enderframelib.core.text.FontFace;
import com.nishiyu.enderframelib.core.text.FontFace.FontStyle;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    /** {@code display} を outer / inner に分けたもの。{@link #updateStyle} が算出スタイルから決める。 */
    public Display display;
    public LayoutBox parent = null;
    /**
     * ボックスツリーの子。レイアウトと描画はこちらを読む。
     *
     * パーサが作るツリーでは {@link BoxTreeBuilder} が {@link #elementChildren} から組み立てるので、
     * 匿名ボックスが挟まり、{@code display: contents} の要素は抜けて子が繰り上がる。
     */
    public final List<LayoutBox> children = new ArrayList<>();
    /**
     * 要素ツリー（DOM と 1:1）の子。スタイルの継承と再スタイルはこちらを辿る。
     *
     * 何が壊れていたか: 以前は DOM と箱が 1:1 で、この 2 つが同じリストだった。そのため
     * 匿名ブロック・匿名表ボックス・{@code display: contents} が作れなかった（軸 6）。
     */
    public final List<LayoutBox> elementChildren = new ArrayList<>();
    /** DOM に無い箱（匿名ブロック・匿名表ボックス・{@code ::marker}）。{@link #node} は持ち主に紐づけた合成ノード。 */
    public final boolean isAnonymous;
    /**
     * {@code list-style-position: outside} の {@code ::marker}。通常フローに入らず、
     * リスト項目の最初の行の左に置かれる。
     */
    public boolean isOutsideMarker = false;
    /**
     * この箱がブロック整形コンテキスト（BFC）を作るか。測定のたびに {@link BoxMeasurer} が決める。
     * BFC を作る箱は子のマージンを自分の外へ貫通させない。
     */
    public boolean establishesBfc = false;

    public void addChild(LayoutBox child) {
        if (child != null) {
            child.parent = this;
            this.children.add(child);
        }
    }

    /** 要素ツリーに子を足す。ボックスツリーは {@link BoxTreeBuilder} がここから組み立てる。 */
    public void addElementChild(LayoutBox child) {
        if (child != null) {
            child.parent = this;
            this.elementChildren.add(child);
        }
    }

    public void linkParents() {
        for (LayoutBox child : children) {
            child.parent = this;
            child.linkParents();
        }
    }

    public LayoutBox findByNodeId(String id) {
        if (id == null || id.isEmpty()) return null;
        if (node != null && id.equals(node.getId())) return this;
        for (LayoutBox child : children) {
            LayoutBox found = child.findByNodeId(id);
            if (found != null) return found;
        }
        return null;
    }

    public LayoutBox findContainingBlock() {
        LayoutBox cur = this.parent;
        while (cur != null) {
            if (cur.isRelative || cur.isAbsolute || cur.isFixed) {
                return cur;
            }
            cur = cur.parent;
        }
        return null;
    }

    public record ContainingBlock(float x, float y, float w, float h) {}

    public ContainingBlock resolveContainingBlock(LayoutContext ctx) {
        if (this.isFixed) {
            return new ContainingBlock(0, 0, ctx.viewport().rootW(), ctx.viewport().rootH());
        }
        LayoutBox cb = findContainingBlock();
        if (cb == null) {
            return new ContainingBlock(0, 0, ctx.viewport().rootW(), ctx.viewport().rootH());
        }
        float cbScrollX = ctx.scrollState().getScrollX(cb.node.getId());
        float cbScrollY = ctx.scrollState().getScrollY(cb.node.getId());
        float cbX = cb.x + cb.bL - cbScrollX;
        float cbY = cb.y + cb.bT - cbScrollY;
        float cbW = Math.max(0, cb.w - cb.bL - cb.bR);
        float cbH = Math.max(0, cb.h - cb.bT - cb.bB);
        return new ContainingBlock(cbX, cbY, cbW, cbH);
    }

    /** この要素に対話状態（:hover / :active / :focus 等）のセレクタが関与するか。 */
    public boolean hasInteractiveRules = false;

    public float x, y, w, h;
    public String displayText = "";

    /** margin / padding / border の 4 辺（解決済みのピクセル値）。 */
    public float mT, mR, mB, mL, pT, pR, pB, pL, bT, bR, bB, bL;
    /** row-gap: 行（ブロック方向）の間隔 / column-gap: 列（インライン方向）の間隔 */
    public float rowGap, columnGap;

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

    public float scrollWidth = 0;
    public float scrollHeight = 0;

    /** min-content 幅のメモ化。flex の shrink ループから何度も問われるため。 */
    private float cachedMinContentWidth = -1;
    private long cachedMinContentKey = Long.MIN_VALUE;

    public LayoutBox(HtmlNode node, ComputedStyle style) {
        this(node, style, false);
    }

    private LayoutBox(HtmlNode node, ComputedStyle style, boolean anonymous) {
        this.node = node;
        this.isAnonymous = anonymous;
        updateStyle(style);
    }

    /** 匿名ボックスを作る。 */
    static LayoutBox anonymous(HtmlNode node, ComputedStyle style) {
        return new LayoutBox(node, style, true);
    }

    /**
     * 算出スタイルを更新する（再スタイル時にジオメトリを保ったまま描画属性だけ差し替える用途を含む）。
     */
    public void updateStyle(ComputedStyle newStyle) {
        this.style = newStyle;
        this.display = Display.of(node, newStyle);

        // 空白の畳み方は white-space で決まる。無条件に畳んでいたため <pre> が効かなかった。
        String text = TextShaper.normalize(node.text, TextShaper.WhiteSpace.of(newStyle.get(CssProperty.WHITE_SPACE)));
        this.displayText = LayoutMetrics.applyTextTransform(text, newStyle);

        String pos = newStyle.getOrDefault(CssProperty.POSITION, "static");
        this.isFixed = "fixed".equals(pos);
        this.isAbsolute = "absolute".equals(pos) || this.isFixed;
        this.isRelative = "relative".equals(pos);
        this.zIndex = (int) newStyle.number(CssProperty.Z_INDEX, 0f);
    }

    public float horizontalDecoration() { return pL + pR + bL + bR; }
    public float verticalDecoration() { return pT + pB + bT + bB; }

    /** 通常フローに参加する子（絶対配置・display:none・外側のマーカーを除く）。 */
    public List<LayoutBox> getFlowChildren() {
        List<LayoutBox> list = new ArrayList<>();
        for (LayoutBox c : children) {
            if (!c.isAbsolute && !c.isOutsideMarker && !"none".equals(c.style.getOrDefault(CssProperty.DISPLAY, "block"))) {
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
    public float getMinContentWidth(LayoutContext ctx) {
        long key = ((long) ctx.viewport().rootW() << 32) | (ctx.viewport().rootH() & 0xFFFFFFFFL);
        if (cachedMinContentKey == key && cachedMinContentWidth >= 0) return cachedMinContentWidth;

        float result = computeMinContentWidth(ctx);
        cachedMinContentKey = key;
        cachedMinContentWidth = result;
        return result;
    }

    private float computeMinContentWidth(LayoutContext ctx) {
        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        // min-content / auto などのキーワードは長さとして解けないので、内容から計算する経路に落とす
        String minWidthDecl = style.get(CssProperty.MIN_WIDTH);
        if (minWidthDecl != null && !LayoutMetrics.isIntrinsicSizeKeyword(minWidthDecl)) {
            return style.length(CssProperty.MIN_WIDTH, LayoutMetrics.UNBOUNDED_WIDTH, rootW, rootH);
        }

        String tag = node.tag;
        if (tag.equals("input") || tag.equals("select") || tag.equals("button") || tag.equals("progress")) {
            float inputW = 90;
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
            return maxWordW;
        }

        if (children.isEmpty()) {
            return this.w > 0 ? this.w : LayoutMetrics.FALLBACK_REPLACED_SIZE;
        }

        float max = 0;
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

    public void applyRelativeOffset(float dx, float dy) {
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
    public void measure(ConstraintSpace space, LayoutContext ctx) {
        for (LayoutBox child : children) child.parent = this;
        BoxMeasurer.measure(this, space, ctx);
    }

    /** {@link #measure(ConstraintSpace, LayoutContext)} の制約を個別に渡す形（テストと HUD の入口）。 */
    public void measure(float tentativeInnerW, float parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        measure(new ConstraintSpace(tentativeInnerW, parentH, fmtCtx), ctx);
    }

    /** 高さだけ再解決する（flex grow/shrink、grid stretch の後処理用）。 */
    public void resolveFlexibleHeights(float parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        BoxMeasurer.resolveFlexibleHeights(this,
                new ConstraintSpace(Math.max(0, w - horizontalDecoration()), parentH, fmtCtx), ctx);
    }

    /** 決まった寸法をもとに自分と子の位置を確定する。 */
    public void layout(float parentX, float parentY, float parentOuterW, float parentOuterH, LayoutContext ctx) {
        if ("none".equals(style.getOrDefault(CssProperty.DISPLAY, "block"))) return;
        for (LayoutBox child : children) child.parent = this;

        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        // LIMITATION(制約): inset が全部 auto の絶対配置を静的位置ではなく包含ブロックの原点に置き、
        // left と right の両方を持つ width: auto を間の幅に広げない。width / height の % は
        // padding box ではなく内容ボックス基準。position: relative のずれは後続の兄弟まで動かし、
        // position: sticky は読まない。W4 で直す。FIDELITY.md の「W4 — 整形コンテキスト」と
        // README の "Not supported" に対応。
        if (this.isAbsolute) {
            ContainingBlock cb = resolveContainingBlock(ctx);
            float refX = cb.x();
            float refY = cb.y();
            float refW = cb.w();
            float refH = cb.h();

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
            float refX = this.isFixed ? 0 : parentX;
            float refY = this.isFixed ? 0 : parentY;
            this.x = refX + mL;
            this.y = refY + mT;
        }

        float scrollX = ctx.scrollState().getScrollX(node.getId());
        float scrollY = ctx.scrollState().getScrollY(node.getId());

        float innerX = this.x + pL + bL - scrollX;
        float innerY = this.y + pT + bT - scrollY;

        LayoutEngine engine = LayoutEngine.getEngine(display);
        engine.arrangeChildren(this, innerX, innerY, ctx);

        for (LayoutBox child : children) {
            if ("none".equals(child.style.getOrDefault(CssProperty.DISPLAY, "block"))) continue;
            if (child.isAbsolute) child.layout(this.x - scrollX, this.y - scrollY, this.w, this.h, ctx);
        }

        applyRelativePositioning(parentOuterW, parentOuterH, rootW, rootH);
        updateScrollExtent(scrollX, scrollY);
    }

    private void applyRelativePositioning(float parentOuterW, float parentOuterH, float rootW, float rootH) {
        if (!this.isRelative) return;

        float offX = 0, offY = 0;
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
    private void updateScrollExtent(float scrollX, float scrollY) {
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
        public float x, y, w, h;
        /**
         * 断片の上端からベースラインまでの距離（px）。
         * これが無かったため描画側は「高さ 8px の箱」を仮定した行内センタリングで
         * 位置を推測していて、フォントサイズが混ざった行で文字が揃わなかった。
         */
        public float baseline;
        public String text;
        public List<LayoutBox> parents;

        public InlineFragment(LayoutBox box, float x, float y, float w, float h, String text) {
            this.box = box;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.text = text;
        }

        public boolean isText() { return text != null; }
    }

    /**
     * {@code display} の値を outer（周りに対してどう振る舞うか）と inner（子をどう整形するか）に
     * 分けたもの（CSS Display 3）。{@code list-item} は別のフラグで持つ。
     *
     * 以前は各所が {@code display} の文字列を個別に比べていて、{@code flow-root} や
     * {@code table-cell} のように一覧に無い値はタグ名からの推測に落ちていた。
     */
    public record Display(Outer outer, Inner inner, boolean listItem) {

        public enum Outer { BLOCK, INLINE, NONE, CONTENTS }

        public enum Inner {
            FLOW, FLOW_ROOT, FLEX, GRID, TABLE,
            TABLE_ROW_GROUP, TABLE_ROW, TABLE_CELL, TABLE_CAPTION, TABLE_COLUMN, NONE
        }

        public static final Display BLOCK = new Display(Outer.BLOCK, Inner.FLOW, false);
        public static final Display INLINE = new Display(Outer.INLINE, Inner.FLOW, false);
        public static final Display HIDDEN = new Display(Outer.NONE, Inner.NONE, false);
        public static final Display CONTENTS = new Display(Outer.CONTENTS, Inner.NONE, false);

        /** {@code display} の宣言が無いときにインラインとして扱うタグ（従来の推測と同じ一覧）。 */
        private static final Set<String> INLINE_TAGS = Set.of(
                "br", "span", "a", "b", "i", "strong", "em", "img", "input", "button", "select", "progress",
                "mc-fluid", "mc-entity", "mc-scene");

        /** 箱の display。テキストは常にインライン、宣言が無い・解釈できない値はタグから推測する。 */
        public static Display of(HtmlNode node, ComputedStyle style) {
            if (node.nodeType == NodeType.TEXT) return INLINE;
            Display parsed = parse(style.get(CssProperty.DISPLAY));
            if (parsed != null) return parsed;
            return INLINE_TAGS.contains(node.tag) ? INLINE : BLOCK;
        }

        /** 1 キーワードの旧構文と、{@code block flow-root} / {@code inline flex} などの複数キーワード構文を読む。 */
        public static Display parse(String value) {
            if (value == null) return null;
            String v = value.trim().toLowerCase(Locale.ROOT);
            Display legacy = switch (v) {
                case "none" -> HIDDEN;
                case "contents" -> CONTENTS;
                case "block" -> BLOCK;
                case "inline" -> INLINE;
                case "flow-root" -> new Display(Outer.BLOCK, Inner.FLOW_ROOT, false);
                case "inline-block" -> new Display(Outer.INLINE, Inner.FLOW_ROOT, false);
                case "flex" -> new Display(Outer.BLOCK, Inner.FLEX, false);
                case "inline-flex" -> new Display(Outer.INLINE, Inner.FLEX, false);
                case "grid" -> new Display(Outer.BLOCK, Inner.GRID, false);
                case "inline-grid" -> new Display(Outer.INLINE, Inner.GRID, false);
                case "table" -> new Display(Outer.BLOCK, Inner.TABLE, false);
                case "inline-table" -> new Display(Outer.INLINE, Inner.TABLE, false);
                case "list-item" -> new Display(Outer.BLOCK, Inner.FLOW, true);
                case "table-row-group", "table-header-group", "table-footer-group" ->
                        new Display(Outer.BLOCK, Inner.TABLE_ROW_GROUP, false);
                case "table-row" -> new Display(Outer.BLOCK, Inner.TABLE_ROW, false);
                case "table-cell" -> new Display(Outer.BLOCK, Inner.TABLE_CELL, false);
                case "table-caption" -> new Display(Outer.BLOCK, Inner.TABLE_CAPTION, false);
                case "table-column", "table-column-group" -> new Display(Outer.BLOCK, Inner.TABLE_COLUMN, false);
                default -> null;
            };
            if (legacy != null) return legacy;

            Outer outer = null;
            Inner inner = null;
            boolean listItem = false;
            for (String token : v.split("\\s+")) {
                switch (token) {
                    case "block" -> outer = Outer.BLOCK;
                    case "inline" -> outer = Outer.INLINE;
                    case "flow" -> inner = Inner.FLOW;
                    case "flow-root" -> inner = Inner.FLOW_ROOT;
                    case "flex" -> inner = Inner.FLEX;
                    case "grid" -> inner = Inner.GRID;
                    case "table" -> inner = Inner.TABLE;
                    case "list-item" -> listItem = true;
                    default -> {
                        return null;
                    }
                }
            }
            // list-item は flow / flow-root とだけ組める（CSS Display 3）
            if (listItem && inner != null && inner != Inner.FLOW && inner != Inner.FLOW_ROOT) return null;
            return new Display(outer != null ? outer : Outer.BLOCK, inner != null ? inner : Inner.FLOW, listItem);
        }

        public boolean isNone() { return outer == Outer.NONE; }

        public boolean isContents() { return outer == Outer.CONTENTS; }

        public boolean isInlineLevel() { return outer == Outer.INLINE; }

        /** ブロックとインラインの両方を子に持てる箱（ブロックコンテナ）。匿名ブロックはこの中にだけ作る。 */
        public boolean isBlockContainer() {
            // inline flow はインラインボックスで、ブロックコンテナではない
            return (inner == Inner.FLOW && outer == Outer.BLOCK) || inner == Inner.FLOW_ROOT
                    || inner == Inner.TABLE_CELL || inner == Inner.TABLE_CAPTION;
        }

        /** 表の内部の箱（行グループ・行・セル・キャプション・列）。 */
        public boolean isTableInternal() {
            return inner == Inner.TABLE_ROW_GROUP || inner == Inner.TABLE_ROW || inner == Inner.TABLE_CELL
                    || inner == Inner.TABLE_CAPTION || inner == Inner.TABLE_COLUMN;
        }
    }

    public enum SizingMode {
        DEFINITE,       // 幅がピクセルやパーセントで確定している
        SHRINK_TO_FIT   // 子要素のサイズに合わせて縮む (従来の w == -1)
    }

    /**
     * スクロール範囲（内容がどこまで広がっているか）の集計。
     * エンジン固有の子要素も自分で寄与できるようにするための受け皿。
     */
    public static final class ScrollExtent {

        private float right;
        private float bottom;

        public ScrollExtent(float initialRight, float initialBottom) {
            this.right = initialRight;
            this.bottom = initialBottom;
        }

        /** 1 つのボックスの右下端を取り込む。 */
        public void include(LayoutBox box, float scrollX, float scrollY) {
            include(box.x + box.w + box.mR + scrollX, box.y + box.h + box.mB + scrollY);
        }

        public void include(float rightEdge, float bottomEdge) {
            if (rightEdge > right) right = rightEdge;
            if (bottomEdge > bottom) bottom = bottomEdge;
        }

        public float right() {
            return right;
        }

        public float bottom() {
            return bottom;
        }
    }
}
