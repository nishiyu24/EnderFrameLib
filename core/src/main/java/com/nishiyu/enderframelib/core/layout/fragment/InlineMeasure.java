package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.text.model.WhiteSpace;

/**
 * インラインの内容の内在サイズ（Engine v3 M2a・M2b-1）。{@link InlineLineBuilder} と同じ規則（{@link TextRun} の割り方と
 * {@link InlineFlow} の境目の機会）で測る。
 *
 * <ul>
 *   <li>max-content: 強制改行の間を 1 行に並べた幅の最大（行末の畳まれる空白・ぶら下がる空白は数えない）。</li>
 *   <li>min-content: 折り返しの機会で区切った塊の幅の最大（塊の後ろのぶら下がる空白は数えない）。</li>
 * </ul>
 */
final class InlineMeasure implements TextRun.Sink {
    private final LayoutSession s;
    private float min, max, line, chunk, pending, chunkHang, lineHang;
    private boolean any, pendingWraps, afterWrapSpace;
    private int lastChar = -1;
    private TextRun.Metrics run;

    InlineMeasure(LayoutSession s) {
        this.s = s;
    }

    void add(BoxNode n) {
        add(n, null);
    }

    private void add(BoxNode n, WhiteSpace ws) {
        switch (n.kind) {
            case NONE -> { }
            case TEXT -> text(n);
            default -> {
                if (OutOfFlow.is(n)) return;
                if (InlineFlow.isBreak(n)) {
                    forcedBreak();
                    return;
                }
                if (InlineFlow.isWordBreakOpportunity(n)) {
                    if (ws == null || ws.wraps()) afterWrapSpace = true;
                    return;
                }
                if (n.kind.atomicInline()) { // 割れない塊: 親への寄与（余白込み）
                    BoxGeometry g = new BoxGeometry(n.style, Float.NaN, s);
                    MinMaxSizes c = g.contribution(n.style, s.cache().minMax(n, s), s);
                    float m = BlockFlow.fixedMargin(n.style, n.style.box.marginLeft, s) + BlockFlow.fixedMargin(n.style, n.style.box.marginRight, s);
                    boolean wraps = ws == null || ws.wraps();
                    boolean breakable = any && (pending > 0 ? pendingWraps : afterWrapSpace || wraps);
                    put(c.max + m, c.min + m, 0, breakable);
                    lastChar = InlineFlow.ATOM_CHAR;
                    afterWrapSpace = false;
                    return;
                }
                float[] e = InlineFlow.edges(n, Float.NaN, s);
                line += e[0];
                chunk += e[0];
                WhiteSpace inner = n.style.facts.whiteSpace;
                for (int i = 0, c = n.flow.size(); i < c; i++) add(n.flow.get(i), inner);
                line += e[1];
                chunk += e[1];
            }
        }
    }

    private void text(BoxNode box) {
        String t = box.text;
        if (t.isEmpty()) return;
        run = TextRun.Metrics.of(box, s);
        TextRun.scan(run, this);
        run = null;
    }

    /** 内容を置く（{@code lineW} は 1 行に並べたときの幅、{@code chunkW} は割れない塊への寄与）。 */
    private void put(float lineW, float chunkW, float hang, boolean breakable) {
        float gap = any ? pending : 0;
        if (breakable) {
            closeChunk();
            line += gap + lineW;
            chunk = chunkW;
        } else {
            line += gap + lineW;
            chunk += gap + chunkW;
        }
        chunkHang = hang;
        lineHang = hang;
        pending = 0;
        pendingWraps = false;
        any = true;
    }

    private void closeChunk() {
        min = Math.max(min, chunk - chunkHang);
        chunk = 0;
        chunkHang = 0;
    }

    // ---- TextRun.Sink ----

    @Override
    public boolean triesWhole() {
        return false;
    }

    @Override
    public boolean fits(float w) {
        return false;
    }

    @Override
    public void space(float w, boolean wraps) {
        pending = Math.max(pending, w);
        pendingWraps |= wraps;
    }

    @Override
    public void word(int start, int end, float w, float hang, boolean first) {
        String t = run.box().text;
        boolean breakable;
        if (!first) breakable = run.ws().wraps();
        else if (!any) breakable = false;
        else if (pending > 0) breakable = pendingWraps;
        else breakable = afterWrapSpace || (run.ws().wraps() && InlineFlow.breaksBetween(lastChar, t.charAt(start), run.wb()));
        put(w, w, hang, breakable);
        lastChar = t.charAt(end - 1);
        afterWrapSpace = run.ws().wraps() && lastChar == ' ';
    }

    @Override
    public void tab(int at, boolean first) {
        float tabW = InlineFlow.tabWidth(run.style(), run.face(), run.fs(), s);
        float from = line + (any ? pending : 0);
        float w = InlineFlow.tabStop(from, tabW, run.spaceW()) - line;
        line += w;
        chunk += w;
        pending = 0;
        pendingWraps = false;
        any = true;
        lineHang = chunkHang = 0;
        lastChar = '\t';
        afterWrapSpace = run.ws().wraps();
    }

    @Override
    public void forcedBreak() {
        closeChunk();
        max = Math.max(max, line - lineHang);
        line = 0;
        lineHang = 0;
        pending = 0;
        pendingWraps = false;
        any = false;
        afterWrapSpace = false;
        lastChar = -1;
    }

    MinMaxSizes result() {
        closeChunk();
        max = Math.max(max, line - lineHang);
        return new MinMaxSizes(LayoutUnit.snap(min), LayoutUnit.snap(Math.max(min, max)));
    }
}
