package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.kernel.CompactList;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.text.ParagraphSegments;
import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutEngineState;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.PositionTryRule;
import java.util.Map;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * 仮の測定（上書き寸法なし）・確定の測定（上書き寸法あり）の結果の産物と、その入力。
 *
 * <p>A8 で {@link LayoutBox} の入れ子のクラスから最上位のクラスへ移した。</p>
 */
final class Tentative {
    LayoutBox.TentativeKind kind;
    ConstraintSpace space;
    Viewport viewport;
    FontResolver fonts;
    IntrinsicSizeResolver intrinsics;
    int epoch;
    LayoutBox.BoxOutputs outputs;
    boolean percentFree, shrinkFree, outsideFree, widthFree;
    float fitNeed;
    boolean fillFit;
    float overrideW, overrideH;

    void set(LayoutBox.TentativeKind kind, ConstraintSpace space, Viewport viewport, FontResolver fonts,
             IntrinsicSizeResolver intrinsics, int epoch, LayoutBox box, boolean percentFree,
             boolean shrinkFree, boolean outsideFree, boolean widthFree, float fitNeed, boolean fillFit,
             float overrideW, float overrideH) {
        this.kind = kind;
        this.space = space;
        this.viewport = viewport;
        this.fonts = fonts;
        this.intrinsics = intrinsics;
        this.epoch = epoch;
        this.outputs = LayoutBox.BoxOutputs.captureOrUpdate(this.outputs, box);
        this.percentFree = percentFree;
        this.shrinkFree = shrinkFree;
        this.outsideFree = outsideFree;
        this.widthFree = widthFree;
        this.fitNeed = fitNeed;
        this.fillFit = fillFit;
        this.overrideW = overrideW;
        this.overrideH = overrideH;
    }

    LayoutBox.TentativeKind kind() { return kind; }
    ConstraintSpace space() { return space; }
    Viewport viewport() { return viewport; }
    FontResolver fonts() { return fonts; }
    IntrinsicSizeResolver intrinsics() { return intrinsics; }
    int epoch() { return epoch; }
    LayoutBox.BoxOutputs outputs() { return outputs; }
    boolean percentFree() { return percentFree; }
    boolean shrinkFree() { return shrinkFree; }
    boolean outsideFree() { return outsideFree; }
    boolean widthFree() { return widthFree; }
    float fitNeed() { return fitNeed; }
    boolean fillFit() { return fillFit; }
    float overrideW() { return overrideW; }
    float overrideH() { return overrideH; }

    /**
     * 同じ制約か。上書き寸法は必ず一致させる。高さの % の基準が結果に効かない箱（{@link #percentFree}）や
     * 親の shrink 指示に依らない箱（{@link #shrinkFree}）はそれらだけが違っても同じとみなし、確定幅の箱（{@link #widthFree}）や
     * 内容で寸法が決まる箱（{@link #fitNeed}）は使える幅の違いも許す。
     */
    boolean matches(ConstraintSpace s) {
        if (!LayoutBox.sameFixedSizes(space, s)) return false;
        return space.equals(s)
                || LayoutBox.sameIgnoringPercentAndShrink(space, s, percentFree, shrinkFree)
                || widthMatches(s)
                || fitMatches(s);
    }

    boolean widthMatches(ConstraintSpace s) {
        return LayoutBox.widthIndependentReuse && widthFree && LayoutBox.sameBlockPercentAndParent(space, s, percentFree, shrinkFree);
    }

    boolean fitMatches(ConstraintSpace s) {
        return LayoutBox.fitReuseEnabled && fitNeed != Float.POSITIVE_INFINITY
                && s.availableInlineSize() >= fitNeed && space.availableInlineSize() >= fitNeed
                && LayoutBox.sameBlockPercentAndParent(space, s, percentFree, shrinkFree);
    }

    /** ビューポートが同じか。外を読まない箱（{@link #outsideFree}）や内容で寸法が決まる箱は、幅の違いを許す。 */
    boolean sameViewport(Viewport v) {
        return viewport.equals(v)
                || (((LayoutBox.fitReuseEnabled && fitNeed != Float.POSITIVE_INFINITY) || (LayoutBox.viewportWidthFreeReuse && outsideFree))
                        && viewport.rootH() == v.rootH() && viewport.devicePixelRatio() == v.devicePixelRatio());
    }
}
