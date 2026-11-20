package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.text.model.ShapedLine;

import java.util.List;

/**
 * 測定の再利用・性質判定に関する純粋な読み取り判定群（A7）。状態や記憶の書き換えは持たない。
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

    static boolean sameParentContext(FormattingContext a, FormattingContext b, boolean shrinkFree) {
        return a.equals(b) || (shrinkFree && a.parentDisplay() == b.parentDisplay() && a.stretch() == b.stretch());
    }

    static boolean sameBlockPercentAndParent(ConstraintSpace a, ConstraintSpace b, boolean percentFree, boolean shrinkFree) {
        return (percentFree || Float.floatToIntBits(a.percentageBlockSize()) == Float.floatToIntBits(b.percentageBlockSize()))
                && sameParentContext(a.parent(), b.parent(), shrinkFree);
    }

    /** 効かない高さの % の基準や親の shrink 指示を除いて、制約が同じ。 */
    static boolean sameIgnoringPercentAndShrink(ConstraintSpace a, ConstraintSpace b, boolean percentFree, boolean shrinkFree) {
        if (!percentFree && !shrinkFree) return false;
        return Float.floatToIntBits(a.availableInlineSize()) == Float.floatToIntBits(b.availableInlineSize())
                && sameBlockPercentAndParent(a, b, percentFree, shrinkFree);
    }

    static boolean computeBlockPercentFree(LayoutBox box) {
        StyleFacts f = box.axisStyle().layoutFacts();
        // 横方向の割合・内在サイズのキーワードは、親の現在の幅（子の割合 padding の基準・最小内容幅のキャッシュ）を通して
        // 測定時の状態に隠れて依存する。高さの基準を無視して測り直しを省くと、その状態の更新まで省いてしまうので、含まない
        if (!Shortcut.BLOCK_PERCENT_FREE.on() || !f.blockPercentFree || f.percentGeometry
                || f.intrinsicWidthKind != StyleFacts.IW_NONE || box.renderedLegend() != null) return false;
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox c = kids.get(i);
            if (c.style.facts().displayNone) continue;
            if (!c.measureMemo.isBlockPercentFree()) return false;
        }
        return true;
    }

    static boolean computeOutsideFree(LayoutBox box) {
        StyleFacts f = box.axisStyle().layoutFacts();
        // 画像の候補選び（srcset / sizes）はビューポートの幅を読む
        boolean outside = !box.isAbsolute && !f.usesViewportUnits
                && !(box.node != null && ("img".equals(box.node.tag) || "picture".equals(box.node.tag)));
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); outside && i < n; i++) {
            LayoutBox c = kids.get(i);
            outside = c.style.facts().displayNone || c.measureMemo.isOutsideFree();
        }
        return outside;
    }

    static boolean computeWidthFree(LayoutBox box, boolean outsideFree, boolean hasTable) {
        StyleFacts f = box.axisStyle().layoutFacts();
        return outsideFree && f.horizontalFixed && !box.isFixed
                && box.node.nodeType != NodeType.TEXT
                && !hasTable && !LayoutBox.isTableBox(box)
                && (!box.display.isInlineLevel() || BlockLayoutEngine.isAtomicInline(box));
    }

    static boolean computeShrinkIndependent(LayoutBox box, FormattingContext parentFmt) {
        if (box.node == null || box.display == null) return false;
        StyleFacts f = box.axisStyle().layoutFacts();
        if (!Float.isNaN(box.overrideWidth)) return true;
        if (f.widthPercent) return false;
        if (!f.widthAuto || f.hasIntrinsicWidth()) return true;
        if (box.isAbsolute || box.isRenderedLegend || box.isHorizontalReturnRoot || box.isVerticalRoot
                || BlockLayoutEngine.isFloating(box) || box.display.isInlineLevel()
                || box.display.inner() == LayoutBox.Display.Inner.TABLE) {
            return true;
        }
        if (parentFmt.isFlexRow()) return true;
        String tag = box.node.tag;
        return box.node.nodeType == NodeType.TEXT
                || BoxMeasurer.isReplacedImage(tag) || ReplacedElements.isHostScene(tag)
                || com.nishiyu.enderframelib.core.dom.ElementTraits.isControlSizedByContent(tag);
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
        if (!s.containsKey(CssPropertyIndex.MAX_WIDTH)) return false;
        String v = s.get(CssPropertyIndex.MAX_WIDTH);
        return v != null && !"none".equalsIgnoreCase(v.trim());
    }

    static boolean hasNonZeroMinWidth(ComputedStyle s) {
        if (!s.containsKey(CssPropertyIndex.MIN_WIDTH)) return false;
        String v = s.get(CssPropertyIndex.MIN_WIDTH);
        if (v == null) return false;
        String t = v.trim();
        return !t.equalsIgnoreCase("auto") && !t.equals("0") && !t.equals("0px");
    }

    static boolean isFillWidthExcludedTag(String tag) {
        return tag == null || com.nishiyu.enderframelib.core.dom.ElementTraits.isBuiltinReplacedImage(tag) || com.nishiyu.enderframelib.core.dom.ElementTraits.isControlSizedByContent(tag)
                || ReplacedElements.isHostImageLike(tag) || ReplacedElements.isHostScene(tag);
    }

    static boolean isFallbackReplacedForMinContent(LayoutBox c) {
        String t = c.node.tag;
        return ReplacedElements.isReplacedTag(t) || BoxMeasurer.isReplacedImage(t)
                || "video".equals(t) || "iframe".equals(t);
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

    /**
     * 子の中身が変わって測り直しても外寸（幅・高さ・マージン）が前回と同じなら、親の測定はそのまま使える箱か。
     * 親が子から読むのは外寸だけ（配置・ベースラインは layout で組み直す）という前提が成り立つ場合に限る。
     */
    static boolean isCutoffEligible(LayoutBox box) {
        LayoutBox p = box.parent;
        if (!Shortcut.CUTOFF.on() || p == null || box.display == null || p.display == null) return false;
        if (box.node.nodeType == NodeType.TEXT || box.display.isInlineLevel() || box.display.isNone()
                || box.isAbsolute || box.isRelative || box.isSticky || box.isOutsideMarker || box.isRenderedLegend
                || LayoutBox.isTableBox(box) || box.collapsedBorders != null
                || box.measureMemo.everLaidOutAsTable() || box.floatSpace != null
                || !Float.isNaN(box.overrideWidth) || !Float.isNaN(box.overrideHeight)) {
            return false;
        }
        LayoutBox.Display.Inner pi = p.display.inner();
        if (pi == LayoutBox.Display.Inner.FLEX) {
            if (!FlexLayoutEngine.cutoffSafeItem(p, box)) return false;
        } else if (pi != LayoutBox.Display.Inner.FLOW && pi != LayoutBox.Display.Inner.FLOW_ROOT) {
            return false;
        }
        if (LayoutBox.isTableBox(p) || MulticolLayout.isMulticol(p) || BlockLayoutEngine.isFloating(box)) return false;
        for (LayoutBox a = p; a != null; a = a.parent) {
            if (!a.measureMemo.isFlowFloatFree()) return false;
        }
        if (LayoutEngine.contentSizedAncestor(p)) return false;
        return !BlockLayoutEngine.containsFloats(box);
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

    static boolean sameViewportForMeasure(Viewport vp, Viewport old, boolean outsideFree) {
        if (old == null) return false;
        if (vp.equals(old)) return true;
        return Shortcut.VIEWPORT_WIDTH_FREE_REUSE.on() && outsideFree
                && vp.rootH() == old.rootH() && vp.devicePixelRatio() == old.devicePixelRatio();
    }

    static boolean widthOnlyChange(ConstraintSpace space, LayoutContext ctx, boolean widthFree,
                                   boolean blockPercentFree, boolean shrinkIndependent,
                                   ConstraintSpace measuredSpace, Viewport measuredViewport) {
        if (!Shortcut.WIDTH_INDEPENDENT_REUSE.on() || !widthFree || measuredSpace == null || measuredViewport == null) {
            return false;
        }
        Viewport vp = ctx.viewport();
        return (blockPercentFree || Float.floatToIntBits(space.percentageBlockSize()) == Float.floatToIntBits(measuredSpace.percentageBlockSize()))
                && sameParentContext(space.parent(), measuredSpace.parent(), shrinkIndependent)
                && vp.rootH() == measuredViewport.rootH() && vp.devicePixelRatio() == measuredViewport.devicePixelRatio();
    }

    static boolean matchesFitConstraint(LayoutBox box, ConstraintSpace space, LayoutContext ctx,
                                        float need, boolean blockPercentFree, boolean shrinkIndependent,
                                        ConstraintSpace measuredSpace, Viewport measuredViewport) {
        if (!Shortcut.FIT_REUSE.on() || need == Float.POSITIVE_INFINITY || measuredSpace == null || measuredViewport == null) {
            return false;
        }
        if (!(space.availableInlineSize() >= need) || !(measuredSpace.availableInlineSize() >= need)) return false;
        Viewport vp = ctx.viewport();
        boolean sameContext = box.node.nodeType == NodeType.TEXT
                || ((blockPercentFree || Float.floatToIntBits(space.percentageBlockSize()) == Float.floatToIntBits(measuredSpace.percentageBlockSize()))
                        && sameParentContext(space.parent(), measuredSpace.parent(), shrinkIndependent));
        return sameContext && vp.rootH() == measuredViewport.rootH() && vp.devicePixelRatio() == measuredViewport.devicePixelRatio();
    }

    static boolean sameConstraintForReuse(ConstraintSpace a, ConstraintSpace b,
                                          boolean blockPercentFree, boolean shrinkIndependent) {
        if (b == null) return false;
        if (a.equals(b)) return true;
        if (!sameFixedSizes(a, b)) return false;
        if (Float.floatToIntBits(a.availableInlineSize()) != Float.floatToIntBits(b.availableInlineSize())) return false;
        if (!sameParentContext(a.parent(), b.parent(), shrinkIndependent)) return false;
        return blockPercentFree || Float.floatToIntBits(a.percentageBlockSize()) == Float.floatToIntBits(b.percentageBlockSize());
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
        StyleFacts f = ax.layoutFacts();
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
        if (hasActiveMaxWidth(ax) || hasNonZeroMinWidth(ax) || ax.containsKey(CssPropertyIndex.ASPECT_RATIO)) {
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
