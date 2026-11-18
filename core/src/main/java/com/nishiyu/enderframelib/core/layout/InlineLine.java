package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.kernel.CompactList;
import com.nishiyu.enderframelib.core.kernel.layout.FenwickVirtualList;
import com.nishiyu.enderframelib.core.text.ParagraphSegments;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssKeywords;
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

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * 行分割の結果 1 行。座標は内容ボックス相対、{@code baseline} は行の上端から。
 * {@code used} は行の内容の幅（行の左端から、行末の空白を除く）で、{@code text-align} が読む。
 *
 * <p>A8 で {@link BlockLayoutEngine} の入れ子のクラスから最上位のクラスへ移した。</p>
 */
final class InlineLine {
    List<BlockLayoutEngine.LineItem> items;
    final List<BlockLayoutEngine.BoxSpan> spans;
    final float top, left, width, height, baseline, used;
    final TextShaper.WrappedParagraph sliceWp;
    final int sliceStart, sliceEnd;
    /** 自動の折り返しで終わった行（両端揃えの対象。強制改行・段落の最後の行は false で text-align-last に従う）。 */
    boolean softWrapped;
    /** スライス行が段落ごとに共有する、ランの寸法と揃え（行ごとに確保しない）。 */
    final BlockLayoutEngine.SliceStyle slice;

    InlineLine(List<BlockLayoutEngine.LineItem> items, List<BlockLayoutEngine.BoxSpan> spans, float top, float left, float width,
               float height, float baseline, float used) {
        this.items = items;
        this.spans = spans;
        this.top = top;
        this.left = left;
        this.width = width;
        this.height = height;
        this.baseline = baseline;
        this.used = used;
        this.sliceWp = null;
        this.sliceStart = 0;
        this.sliceEnd = 0;
        this.slice = null;
    }

    InlineLine(TextShaper.WrappedParagraph sliceWp, int sliceStart, int sliceEnd,
                       BlockLayoutEngine.SliceStyle slice,
                       float top, float left, float width, float height, float baseline, float used) {
        this.items = null;
        this.spans = List.of();
        this.top = top;
        this.left = left;
        this.width = width;
        this.height = height;
        this.baseline = baseline;
        this.used = used;
        this.sliceWp = sliceWp;
        this.sliceStart = sliceStart;
        this.sliceEnd = sliceEnd;
        this.slice = slice;
    }

    static InlineLine ofWrappedSlice(TextShaper.WrappedParagraph wp, int lineIdx, BlockLayoutEngine.SliceStyle slice,
                                     float top, float left, float width, float height, float baseline) {
        return new InlineLine(wp, wp.lineStart(lineIdx), wp.lineStart(lineIdx + 1), slice,
                top, left, width, height, baseline, wp.lineUsed(lineIdx));
    }

    List<BlockLayoutEngine.LineItem> items() {
        List<BlockLayoutEngine.LineItem> list = items;
        if (list == null && sliceWp != null) {
            int start = sliceStart, end = sliceEnd;
            list = new ArrayList<>(end - start);
            String[] segments = sliceWp.prepared().segs();
            BlockLayoutEngine.SliceStyle st = slice;
            for (int k = start; k < end; k++) {
                list.add(new BlockLayoutEngine.LineItem(st.box(), sliceWp.itemX(k), sliceWp.advance(k), st.runH(),
                        segments[sliceWp.segIndex(k)], st.runBaseline(), st.descent(),
                        st.parents(), st.align(), st.raise(), null));
            }
            items = list;
        }
        return list;
    }

    List<BlockLayoutEngine.BoxSpan> spans() { return spans; }
    float top() { return top; }
    float left() { return left; }
    float width() { return width; }
    float height() { return height; }
    float baseline() { return baseline; }
    float used() { return used; }
}
