package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.text.model.ShapedLine;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 測定の再利用・構造判定に関する純粋な補助関数群（A7・C3）。状態や記憶の書き換えは持たない。
 */
final class MeasureAnalysis {
    private MeasureAnalysis() {}

    static boolean sameLength(float a, float b) {
        return Float.isNaN(a) ? Float.isNaN(b) : a == b;
    }

    static boolean sameFixedSizes(ConstraintSpace a, ConstraintSpace b) {
        return sameLength(a.fixedInlineSize(), b.fixedInlineSize())
                && sameLength(a.fixedBlockSize(), b.fixedBlockSize());
    }

    static boolean allChildrenWidthInvariantForFill(LayoutBox box, LayoutBox.Display.Inner inner) {
        if (inner == LayoutBox.Display.Inner.FLEX) return true; // FlexLayoutEngine が grow=0・wrap なし・auto 余白なしの行方向だけ naturalFitInnerWidth を入れる
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox c = kids.get(i);
            if (c.style.facts().displayNone) continue;
            if (!(c.node.nodeType == NodeType.TEXT
                    || (c.measureMemo.isWidthFree() && Shortcut.WIDTH_INDEPENDENT_REUSE.on())
                    || c.sizingMode == LayoutBox.SizingMode.SHRINK_TO_FIT)) {
                return false;
            }
        }
        return true;
    }

    static boolean hasActiveMaxWidth(ComputedStyle s) {
        long v = s.typed().box.maxWidth;
        return CssLength.isDeclared(v) && !CssLength.isKeyword(v, CssLength.KW_NONE);
    }

    static boolean hasNonZeroMinWidth(ComputedStyle s) {
        long v = s.typed().box.minWidth;
        return CssLength.isDeclared(v) && !CssLength.isAuto(v) && !(CssLength.isPixels(v) && CssLength.pixels(v) == 0f);
    }

    static boolean isFillWidthExcludedTag(String tag) {
        return tag == null || com.nishiyu.enderframelib.core.dom.node.ElementTraits.isBuiltinReplacedImage(tag)
                || com.nishiyu.enderframelib.core.dom.node.ElementTraits.isControlSizedByContent(tag)
                || ReplacedElements.isHostImageLike(tag) || ReplacedElements.isHostScene(tag);
    }

    static boolean isFallbackReplacedForMinContent(LayoutBox c) {
        String t = c.node.tag;
        return ReplacedElements.isReplacedTag(t) || BoxMeasurer.isReplacedImage(t)
                || com.nishiyu.enderframelib.core.dom.node.ElementTraits.isFallbackSizedMedia(t);
    }

    /** 仮の測定を使う親（flex / grid）の子か。それ以外の箱は産物を記録しない（割り当てを増やさない）。 */
    static boolean parentUsesTentatives(LayoutBox box) {
        if (box.parent == null || box.parent.display == null) return false;
        LayoutBox.Display.Inner inner = box.parent.display.inner();
        return inner == LayoutBox.Display.Inner.FLEX || inner == LayoutBox.Display.Inner.GRID;
    }

    /** 子を持たない要素だけ、MeasureOutputs が測定状態のすべてなので確定測定を別枠で使い回せる。 */
    static boolean isDefiniteCacheableLeaf(LayoutBox box) {
        return Shortcut.DEFINITE_LEAF_REUSE.on() && box.children.isEmpty()
                && box.node.nodeType != NodeType.TEXT
                && box.floatSpace == null && box.collapsedBorders == null && !box.measureMemo.hasTable()
                && !LayoutBox.isTableBox(box) && box.renderedLegend() == null;
    }

    static boolean sameChildren(LayoutBox[] recorded, List<LayoutBox> children) {
        if (recorded == null || recorded.length != children.size()) return false;
        for (int i = 0; i < recorded.length; i++) if (recorded[i] != children.get(i)) return false;
        return true;
    }

    static boolean hasFloatingChild(LayoutBox box) {
        List<LayoutBox> ch = box.children;
        for (int i = 0, n = ch.size(); i < n; i++) {
            if (ch.get(i).style.facts().floating) return true;
        }
        return false;
    }

    static int computeAttrHash(HtmlNode node) {
        int hash = 1;
        for (Map.Entry<String, String> attr : node.attrs.entrySet()) {
            String key = attr.getKey();
            if ("style".equals(key) || "class".equals(key)) continue;
            hash = 31 * hash + key.hashCode();
            hash = 31 * hash + Objects.hashCode(attr.getValue());
        }
        return hash;
    }

    static float computeFitNeed(LayoutBox box, boolean outsideFree, boolean hasTable,
                                boolean flowFloatFree, boolean childFitBlocked, float childFitNeedMax) {
        final float none = Float.POSITIVE_INFINITY;
        if (!Shortcut.FIT_REUSE.on() || box.parent == null || !outsideFree || hasTable) return none;
        if (box.floatSpace != null || box.collapsedBorders != null
                || box.isAbsolute || box.isFixed || box.isRenderedLegend || box.isOutsideMarker
                || box.display == null || box.display.isNone()) {
            return none;
        }
        ComputedStyle ax = box.axisStyle();
        StyleFacts f = ax.facts();
        boolean textNode = box.node.nodeType == NodeType.TEXT;
        if (!Float.isNaN(box.overrideWidth)) {
            return (!textNode && !f.floating && f.edgesAvailableFree && (flowFloatFree || box.establishesBfc)) ? 0f : none;
        }
        if (!f.fitCandidate) return none;
        if (textNode) {
            List<ShapedLine> lines = box.shapedLines;
            if (lines == null || lines.size() != 1 || hasActiveMaxWidth(ax)) return none;
            return box.w + box.mL + box.mR;
        }
        LayoutBox.Display.Inner inner = box.display.inner();
        if (inner != LayoutBox.Display.Inner.FLOW && inner != LayoutBox.Display.Inner.FLOW_ROOT
                && inner != LayoutBox.Display.Inner.FLEX) {
            return none;
        }
        if (box.measuredClamped || box.renderedLegend() != null || MulticolLayout.isMulticol(box)
                || box.effectiveContainsInlineSize() || childFitBlocked) {
            return none;
        }
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox c = kids.get(i);
            if (c.style.facts().displayNone) continue;
            if (c.isOutsideMarker || c.isAbsolute) return none;
        }
        if (box.sizingMode == LayoutBox.SizingMode.SHRINK_TO_FIT) {
            if (Float.isNaN(box.naturalInlineSize)) return none;
            return Math.max(Math.max(box.w, box.naturalInlineSize) + box.mL + box.mR,
                    childFitNeedMax + box.horizontalDecoration() + box.mL + box.mR);
        }
        if (f.floating || !flowFloatFree) return none;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox c = kids.get(i);
            if (c.style.facts().displayNone) continue;
            if (BlockLayoutEngine.isFloating(c)) return none;
        }
        if (box.sizingMode != LayoutBox.SizingMode.DEFINITE || isFillWidthExcludedTag(box.node.tag)
                || Float.isNaN(box.naturalFitInnerWidth)) {
            return none;
        }
        if (hasActiveMaxWidth(ax) || hasNonZeroMinWidth(ax) || ax.typed().box.aspectRatio != null) {
            return none;
        }
        float innerNeed = Math.max(box.naturalFitInnerWidth, childFitNeedMax);
        return innerNeed + box.horizontalDecoration() + box.mL + box.mR + (innerNeed > 0 ? 0.02f : 0f);
    }

    static boolean isFitFixedW(LayoutBox box, float fitNeed) {
        if (fitNeed == Float.POSITIVE_INFINITY) return false;
        return !Float.isNaN(box.overrideWidth)
                || box.node.nodeType == NodeType.TEXT
                || box.sizingMode == LayoutBox.SizingMode.SHRINK_TO_FIT;
    }

    static boolean isFillFitReusable(LayoutBox box, float fitNeed) {
        if (fitNeed == Float.POSITIVE_INFINITY || !Float.isNaN(box.overrideWidth)
                || box.node.nodeType == NodeType.TEXT || box.sizingMode != LayoutBox.SizingMode.DEFINITE) {
            return false;
        }
        return Float.isNaN(box.overrideHeight) && allChildrenWidthInvariantForFill(box, box.display.inner());
    }
}
