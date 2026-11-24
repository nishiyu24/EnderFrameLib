package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.kernel.CompactList;
import com.nishiyu.enderframelib.core.kernel.layout.FenwickVirtualList;
import com.nishiyu.enderframelib.core.text.ParagraphSegments;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display.Inner;
import com.nishiyu.enderframelib.core.layout.LayoutBox.InlineFragment;
import com.nishiyu.enderframelib.core.layout.LayoutBox.SizingMode;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.List;

import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * 1 つの BFC の中の float の配置（BFC の内容ボックスの原点からの座標、マージンボックス）。
 * BFC を作らない子孫のブロックは同じ領域を共有する。
 *
 * <p>A8 で {@link BlockLayoutEngine} の入れ子のクラスから最上位のクラスへ移した。</p>
 */
final class FloatSpace {
    /** {x, y, right, bottom} */
    static final List<float[]> NONE = List.of();
    /** 最初の追加まで共有の空の表（大半の箱は float を持たない）。 */
    List<float[]> lefts = NONE;
    List<float[]> rights = NONE;
    float lastTop = Float.NEGATIVE_INFINITY;

    boolean isEmpty() {
        return lefts.isEmpty() && rights.isEmpty();
    }

    void add(boolean left, float x, float y, float w, float h) {
        if (left) {
            if (lefts == NONE) lefts = new ArrayList<>();
            lefts.add(new float[]{x, y, x + w, y + h});
        } else {
            if (rights == NONE) rights = new ArrayList<>();
            rights.add(new float[]{x, y, x + w, y + h});
        }
        lastTop = Math.max(lastTop, y);
    }

    float lastTop() {
        return lastTop;
    }

    /** 全 float の下端。無ければ負の無限大。 */
    float bottom() {
        float b = Float.NEGATIVE_INFINITY;
        for (int i = 0, n = lefts.size(); i < n; i++) b = Math.max(b, lefts.get(i)[3]);
        for (int i = 0, n = rights.size(); i < n; i++) b = Math.max(b, rights.get(i)[3]);
        return b;
    }

    /** {@code clear} の値に対応する側の float の下端。 */
    float clearance(CssKeywords.Clear clear) {
        boolean clearLeft = clear == CssKeywords.Clear.LEFT || clear == CssKeywords.Clear.INLINE_START;
        boolean clearRight = clear == CssKeywords.Clear.RIGHT || clear == CssKeywords.Clear.INLINE_END;
        float b = Float.NEGATIVE_INFINITY;
        if (!clearRight) for (int i = 0, n = lefts.size(); i < n; i++) b = Math.max(b, lefts.get(i)[3]);
        if (!clearLeft) for (int i = 0, n = rights.size(); i < n; i++) b = Math.max(b, rights.get(i)[3]);
        return b;
    }

    static boolean overlaps(float[] f, float y, float h) {
        return f[1] < y + Math.max(h, 0.01f) && f[3] > y;
    }

    boolean intrudes(float y, float h) {
        for (float[] f : lefts) if (overlaps(f, y, h)) return true;
        for (float[] f : rights) if (overlaps(f, y, h)) return true;
        return false;
    }

    /** 帯 [y, y + h) で左の float を避けた左端。 */
    float leftEdge(float y, float h, float minX) {
        float x = minX;
        for (float[] f : lefts) if (overlaps(f, y, h)) x = Math.max(x, f[2]);
        return x;
    }

    /** 帯 [y, y + h) で右の float を避けた右端。 */
    float rightEdge(float y, float h, float maxX) {
        float x = maxX;
        for (float[] f : rights) if (overlaps(f, y, h)) x = Math.min(x, f[0]);
        return x;
    }

    /** {@code y} より下で、いずれかの float が終わる最も近い位置。無ければ正の無限大。 */
    float nextBottom(float y) {
        float next = Float.POSITIVE_INFINITY;
        for (float[] f : lefts) if (f[3] > y) next = Math.min(next, f[3]);
        for (float[] f : rights) if (f[3] > y) next = Math.min(next, f[3]);
        return next;
    }
}
