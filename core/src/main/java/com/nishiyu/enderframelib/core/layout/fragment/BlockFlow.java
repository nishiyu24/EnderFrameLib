package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxChildren;
import com.nishiyu.enderframelib.core.box.BoxChunk;
import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * ブロックの流れ（Engine v3 M2a）。{@code (BoxNode, ConstraintSpace) → LayoutFragment} の純粋な変換。
 *
 * <ul>
 *   <li>子がブロック水準だけなら、子のチャンクごとに {@code (BoxChunk ==, 子の制約) → FlowChunk} を使い回し、
 *       チャンクの上端は累積和で決める（{@code O(チャンク数 + 作り直したチャンクの中身)}）。</li>
 *   <li>子がインライン水準だけ（と畳まれる空白）なら {@link InlineFlow} で行に組む。</li>
 *   <li>混在なら、インライン水準の連なりを匿名の塊（{@code box == null}）にして 1 つの {@link FlowChunk} に並べる
 *       （チャンク単位では使い回さない。子は 1 つずつ照合する）。</li>
 * </ul>
 *
 * <p>余白の相殺: 兄弟の間・親と最初/最後の子（親に上/下の枠線・パディングが無く、独立した整形コンテキストでないとき）・
 * 高さ 0 の素通しの箱を、正の最大と負の最小の組で扱う。チャンクは前後の余白を相殺前のまま外へ出すので、
 * チャンクの中身は前のチャンクに依らない。M2a の範囲外: float・clearance・positioned・multicol（M2b）。</p>
 */
final class BlockFlow implements FormattingEngine {
    static final BlockFlow INSTANCE = new BlockFlow();

    private BlockFlow() { }

    @Override
    public LayoutFragment layout(BoxNode node, ConstraintSpace space, LayoutFragment prior, LayoutSession s) {
        Style st = node.style;
        float cbw = space.availableWidth;
        BoxGeometry g = new BoxGeometry(st, cbw, s);
        boolean text = node.kind == BoxKind.TEXT;
        float w, ml = g.mL;
        if (!Float.isNaN(space.fixedWidth)) {
            w = space.fixedWidth;
        } else {
            float spec = g.specifiedWidth(node, cbw, cbw - g.mL - g.mR, s);
            w = g.clampWidth(st, Float.isNaN(spec) ? Math.max(0, cbw - g.mL - g.mR) : spec, cbw, s);
            if (g.autoML && g.autoMR) ml = Math.max(0, (cbw - w) / 2f);
            else if (g.autoML) ml = Math.max(0, cbw - w - g.mR);
        }
        w = LayoutUnit.snap(w);
        ml = LayoutUnit.snap(ml);
        float cw = Math.max(0, w - g.hdec());
        // fieldset の描画される legend は流れに入らず上の枠線にまたがり、中身ははみ出した分だけ下がる（FieldsetLegend）
        BoxNode legend = text ? null : FieldsetLegend.of(node);
        FieldsetLegend.Placed lp = legend == null ? null : FieldsetLegend.place(legend, g, cw, prior, s);
        float spill = lp == null ? 0 : lp.spill();
        // 文書の根とルート要素（html）は独立した整形コンテキスト: 子と余白を相殺しない
        boolean root = space.formattingRoot() || node.node.parent == null || node.node.parent.parent == null
                || BoxGeometry.establishesBfc(st);
        float fixedH = !Float.isNaN(space.fixedHeight) ? space.fixedHeight
                : space.contentBlockSize() ? Float.NaN : g.specifiedHeight(st, space.percentHeightBase, s);
        boolean heightAuto = Float.isNaN(fixedH);
        boolean collapseTop = !root && g.pT == 0 && g.bT == 0;
        boolean collapseBottom = !root && g.pB == 0 && g.bB == 0 && heightAuto;
        // 文書の根の子（html）の % の高さの基準は初期包含ブロック（ビューポート）の高さ（CSS 2 §10.5）
        float childPct = node.node.parent == null ? space.percentHeightBase : heightAuto ? Float.NaN : Math.max(0, fixedH - g.vdec() - spill);

        BoxChildren kids = node.flow;
        LineFragment[] lines = null;
        ChunkedFlow flow = null;
        Assembled a;
        List<OutOfFlow.Pending> pend = new ArrayList<>();
        float insetL = g.bL + g.pL, insetT = g.bT + g.pT + spill;
        if (legend == null && (text || (kids.hasInline() && !kids.hasBlock()))) {
            InlineFlow.Result r = InlineFlow.layout(text ? List.of(node) : kids.toList(), st, cw, prior, s);
            lines = r.lines().length > 0 ? r.lines() : null;
            a = new Assembled(r.height());
            for (OutOfFlow.Pending p : r.pending()) pend.add(p.shift(insetL, insetT));
        } else {
            ConstraintSpace base = s.space(cw, Float.NaN, Float.NaN, Float.NaN, 0);
            ConstraintSpace pct = Float.isNaN(childPct) ? base : s.space(cw, Float.NaN, Float.NaN, childPct, 0);
            ChunkedFlow priorFlow = prior != null ? prior.flow : null;
            float[] floatBottom = {0};
            FlowChunk[] chunks = kids.hasInline() || kids.hasFloat() || kids.hasContents() || legend != null
                    ? new FlowChunk[]{mixed(st, kids, legend, base, pct, priorFlow, floatBottom, s)}
                    : chunks(kids, base, pct, priorFlow, s);
            a = assemble(chunks, collapseTop, collapseBottom);
            // 独立した整形コンテキストの根は中の float を含んで高さを決める
            if (root && chunks.length == 1) a.contentH = Math.max(a.contentH, LayoutUnit.snap(a.chunkY[0] + floatBottom[0]));
            flow = chunks.length > 0 ? new ChunkedFlow(chunks, a.chunkY, a.firstStarted, collapseTop) : null;
            for (int k = 0; k < chunks.length; k++) {
                if (chunks[k].pending == null) continue;
                for (OutOfFlow.Pending p : chunks[k].pending) pend.add(p.shift(insetL, insetT + a.chunkY[k]));
            }
        }
        float h = heightAuto ? a.contentH + g.vdec() + spill : fixedH;
        if (Float.isNaN(space.fixedHeight) && !space.contentBlockSize()) h = g.clampHeight(st, h, space.percentHeightBase, s);
        h = LayoutUnit.snap(h);

        float tp = Math.max(0, g.mT), tn = Math.min(0, g.mT), bp = Math.max(0, g.mB), bn = Math.min(0, g.mB);
        if (collapseTop) { tp = Math.max(tp, a.leadPos); tn = Math.min(tn, a.leadNeg); }
        if (collapseBottom && a.started) { bp = Math.max(bp, a.trailPos); bn = Math.min(bn, a.trailNeg); }
        boolean through = collapseTop && collapseBottom && !a.started && lines == null && h == 0;
        OutOfFlow.Pending[] up = null;
        PlacedFragment[] oof = null;
        if (!pend.isEmpty()) {
            boolean docRoot = node.node.parent == null;
            if (docRoot || OutOfFlow.containsOutOfFlow(st)) { // 包含ブロック: パディングの箱で流れの外の子孫を置く（文書の根は初期包含ブロック）
                float cbW = docRoot ? w : Math.max(0, w - g.bL - g.bR);
                float cbH = docRoot && Float.isFinite(space.percentHeightBase) ? space.percentHeightBase : Math.max(0, h - g.bT - g.bB);
                oof = OutOfFlow.layout(pend, docRoot ? 0 : g.bL, docRoot ? 0 : g.bT, cbW, cbH, prior, s);
            } else {
                up = pend.toArray(OutOfFlow.NONE);
            }
        }
        if (lp != null) oof = OutsideMarker.prepend(lp.frag(), oof); // legend は置いた断片として持つ（流れの外と同じく border-box 基準）
        LayoutFragment out = new LayoutFragment(node, space, w, h, ml, tp, tn, bp, bn, insetL, insetT, through, flow, lines, up, oof);
        // 外側の ::marker は項目を整形した後で、最初の行のベースラインに揃えて左外に置く（OutsideMarker）
        PlacedFragment marker = text || node.marker == null ? null : OutsideMarker.place(node, out, cw, prior, s);
        if (marker == null) return out;
        return new LayoutFragment(node, space, w, h, ml, tp, tn, bp, bn, insetL, insetT, through, flow, lines, up,
                OutsideMarker.prepend(marker, oof));
    }

    /** ブロック水準の子だけの流れ: チャンクごとに前回の {@link FlowChunk} を引き、外れたチャンクだけ並べ直す。 */
    private static FlowChunk[] chunks(BoxChildren kids, ConstraintSpace base, ConstraintSpace pct, ChunkedFlow priorFlow, LayoutSession s) {
        int kc = kids.chunkCount();
        FlowChunk[] out = new FlowChunk[kc];
        int[] match = matchPrior(kids, priorFlow);
        LayoutCache cache = s.cache();
        for (int k = 0; k < kc; k++) {
            BoxChunk c = kids.chunk(k);
            int p = match[k];
            FlowChunk pc = p >= 0 ? priorFlow.chunks[p] : null;
            if (pc != null && pc.matches(c, pct)) {
                cache.chunkHit();
                out[k] = pc;
                continue;
            }
            cache.chunkComputed();
            Cursor cur;
            if (pc != null) {
                cur = new Cursor(priorFlow, p, p);
            } else {
                int lo = -1, hi = priorFlow != null ? priorFlow.chunks.length : 0;
                for (int j = k - 1; j >= 0; j--) if (match[j] >= 0) { lo = match[j]; break; }
                for (int j = k + 1; j < kc; j++) if (match[j] >= 0) { hi = match[j]; break; }
                cur = new Cursor(priorFlow, lo + 1, hi - 1);
            }
            ChunkBuilder b = new ChunkBuilder(c.size(), s);
            for (int i = 0, n = c.size(); i < n; i++) {
                BoxNode child = c.get(i);
                if (OutOfFlow.is(child)) { b.addOutOfFlow(child); continue; }
                if (!child.kind.blockLevel()) continue; // 畳まれる空白・display: none
                ConstraintSpace sp = BoxGeometry.usesPercentHeight(child.style) ? pct : base;
                b.add(cache.layout(child, sp, cur.find(child), s));
            }
            out[k] = b.build(c, pct, null);
        }
        return out;
    }

    /** 今のチャンク {@code k} と同じ参照の前回のチャンクの位置（無ければ -1）。 */
    private static int[] matchPrior(BoxChildren kids, ChunkedFlow priorFlow) {
        int kc = kids.chunkCount();
        int[] match = new int[kc];
        java.util.Arrays.fill(match, -1);
        if (priorFlow == null) return match;
        FlowChunk[] pcs = priorFlow.chunks;
        if (kc == 1 && pcs.length == 1) {
            if (pcs[0].source == kids.chunk(0)) match[0] = 0;
            return match;
        }
        Map<BoxChunk, Integer> index = new IdentityHashMap<>(pcs.length * 2);
        for (int p = 0; p < pcs.length; p++) if (pcs[p].source != null) index.put(pcs[p].source, p);
        for (int k = 0; k < kc; k++) {
            Integer p = index.get(kids.chunk(k));
            if (p != null) match[k] = p;
        }
        return match;
    }

    /**
     * インラインとブロックが混在する・float を含む子: インラインの連なりを匿名の塊にして並べ、float を左右に置く
     * （{@link Floats}。同じ容器のインラインの行は float を避ける）。{@code floatBottom[0]} に一番下の float の下端を返す。
     */
    private static FlowChunk mixed(Style st, BoxChildren kids, BoxNode skip, ConstraintSpace base, ConstraintSpace pct,
                                   ChunkedFlow priorFlow, float[] floatBottom, LayoutSession s) {
        s.cache().chunkComputed();
        Cursor cur = new Cursor(priorFlow, 0, priorFlow != null ? priorFlow.chunks.length - 1 : -1);
        ChunkBuilder b = new ChunkBuilder(8, s);
        List<BoxNode> run = new ArrayList<>();
        Floats.Space exc = Floats.Space.EMPTY;
        for (BoxNode child : kids.toList()) {
            if (child.kind == BoxKind.NONE || child == skip) continue; // skip: fieldset の描画される legend
            if (OutOfFlow.is(child)) {
                flushRun(st, run, base, b, exc, s);
                b.addOutOfFlow(child);
                continue;
            }
            if (!child.kind.blockLevel()) {
                run.add(child);
                continue;
            }
            flushRun(st, run, base, b, exc, s);
            if (Floats.is(child)) {
                Floats.Placed p = Floats.place(child, exc, b.flowY(), base.availableWidth, pct.percentHeightBase, cur.find(child), s);
                exc = p.space();
                b.addFloat(p.frag(), p.x(), p.y());
                continue;
            }
            var clear = child.style.box.clear;
            if (clear != null && clear != com.nishiyu.enderframelib.core.css.model.CssKeywords.Clear.NONE) b.clearTo(exc.clearance(clear));
            ConstraintSpace sp = BoxGeometry.usesPercentHeight(child.style) ? pct : base;
            b.add(s.cache().layout(child, sp, cur.find(child), s));
        }
        flushRun(st, run, base, b, exc, s);
        floatBottom[0] = exc.bottom();
        return b.build(null, pct, null);
    }

    private static void flushRun(Style st, List<BoxNode> run, ConstraintSpace base, ChunkBuilder b, Floats.Space exc, LayoutSession s) {
        if (run.isEmpty()) return;
        boolean blank = true;
        for (BoxNode n : run) blank &= n.collapsibleWhitespace();
        if (!blank) {
            InlineFlow.Result r = InlineFlow.layout(run, st, base.availableWidth, null, exc, b.flowY(), s);
            if (r.lines().length > 0 || !r.pending().isEmpty()) {
                b.add(new LayoutFragment(null, base, base.availableWidth, r.height(), 0, 0, 0, 0, 0, 0, 0, r.lines().length == 0,
                        null, r.lines().length > 0 ? r.lines() : null,
                        r.pending().isEmpty() ? null : r.pending().toArray(OutOfFlow.NONE), null));
            }
        }
        run.clear();
    }

    /** チャンクを縦に並べ、チャンク境界を跨いで余白を相殺した結果。 */
    private static final class Assembled {
        final float[] chunkY;
        float contentH, leadPos, leadNeg, trailPos, trailNeg;
        boolean started;
        /** 最初に流れの箱が始まったチャンク（無ければチャンクの数）。 */
        int firstStarted;

        Assembled(float contentH) {
            this.chunkY = null;
            this.contentH = contentH;
            this.started = contentH > 0;
        }

        Assembled(int n) {
            this.chunkY = new float[n];
        }
    }

    private static Assembled assemble(FlowChunk[] chunks, boolean collapseTop, boolean collapseBottom) {
        Assembled a = new Assembled(chunks.length);
        a.firstStarted = chunks.length;
        float y = 0, pp = 0, pn = 0;
        for (int k = 0; k < chunks.length; k++) {
            FlowChunk fc = chunks[k];
            if (fc.through) {
                if (!a.started) { a.leadPos = Math.max(a.leadPos, fc.leadPos); a.leadNeg = Math.min(a.leadNeg, fc.leadNeg); }
                else { pp = Math.max(pp, fc.leadPos); pn = Math.min(pn, fc.leadNeg); }
                a.chunkY[k] = y;
                continue;
            }
            if (!a.started) {
                a.started = true;
                a.firstStarted = k;
                float lp = Math.max(a.leadPos, fc.leadPos), ln = Math.min(a.leadNeg, fc.leadNeg);
                if (collapseTop) { a.leadPos = lp; a.leadNeg = ln; y = 0; }
                else { y = lp + ln; a.leadPos = a.leadNeg = 0; }
            } else {
                y += Math.max(pp, fc.leadPos) + Math.min(pn, fc.leadNeg);
            }
            a.chunkY[k] = y;
            y += fc.height;
            pp = fc.trailPos;
            pn = fc.trailNeg;
        }
        if (!a.started) {
            a.contentH = collapseTop ? 0 : Math.max(0, a.leadPos + a.leadNeg);
            if (!collapseTop) a.leadPos = a.leadNeg = 0;
        } else if (collapseBottom) {
            a.contentH = y;
            a.trailPos = pp;
            a.trailNeg = pn;
        } else {
            a.contentH = y + pp + pn;
        }
        a.contentH = LayoutUnit.snap(a.contentH);
        return a;
    }

    /** 1 チャンクの子を縦に並べ、兄弟の間の余白を相殺する（1 回の整形の間だけの作業域）。 */
    private static final class ChunkBuilder {
        private LayoutFragment[] frags;
        private float[] xs, ys;
        private int n;
        private boolean started;
        private int leadCount;
        private float cursor, lp, ln, pp, pn;
        private final LayoutSession s;
        private List<OutOfFlow.Pending> pend;

        ChunkBuilder(int cap, LayoutSession s) {
            this.s = s;
            frags = new LayoutFragment[Math.max(1, cap)];
            xs = new float[frags.length];
            ys = new float[frags.length];
        }

        void add(LayoutFragment f) {
            if (n == frags.length) {
                frags = java.util.Arrays.copyOf(frags, n * 2);
                xs = java.util.Arrays.copyOf(xs, n * 2);
                ys = java.util.Arrays.copyOf(ys, n * 2);
            }
            float y;
            if (f.collapseThrough) {
                float sp = Math.max(f.topPos, f.bottomPos), sn = Math.min(f.topNeg, f.bottomNeg);
                // 素通しの箱の上端は、前の余白と自分の上の余白を相殺した位置（CSS 2 §8.3.1）
                if (!started) { y = Math.max(lp, f.topPos) + Math.min(ln, f.topNeg); lp = Math.max(lp, sp); ln = Math.min(ln, sn); }
                else { y = cursor + Math.max(pp, f.topPos) + Math.min(pn, f.topNeg); pp = Math.max(pp, sp); pn = Math.min(pn, sn); }
            } else if (!started) {
                started = true;
                leadCount = n;
                lp = Math.max(lp, f.topPos);
                ln = Math.min(ln, f.topNeg);
                y = 0;
                cursor = f.height;
                pp = f.bottomPos;
                pn = f.bottomNeg;
            } else {
                y = cursor + Math.max(pp, f.topPos) + Math.min(pn, f.topNeg);
                cursor = y + f.height;
                pp = f.bottomPos;
                pn = f.bottomNeg;
            }
            float x = f.marginLeft;
            if (f.box != null) { // position: relative は流れを変えず置く位置だけずらす
                float[] rel = OutOfFlow.relativeOffset(f.box.style, f.space.availableWidth, f.space.percentHeightBase, s);
                if (rel != null) { x += rel[0]; y += rel[1]; }
            }
            frags[n] = f;
            xs[n] = x;
            ys[n++] = LayoutUnit.snap(y);
            if (f.pending != null) {
                if (pend == null) pend = new ArrayList<>();
                for (OutOfFlow.Pending p : f.pending) pend.add(p.shift(x, y));
            }
        }

        /** 次の流れの箱の上端が来るはずの位置（チャンクの上端基準。相殺前の余白は正の最大 ＋ 負の最小で足す）。 */
        float flowY() {
            return started ? cursor + pp + pn : 0;
        }

        /** {@code clear}: 次の流れの箱を {@code y} より下へ（clearance。前の余白とは相殺しない）。 */
        void clearTo(float y) {
            if (y == Float.NEGATIVE_INFINITY || y <= flowY()) return;
            if (!started) {
                started = true;
                leadCount = n;
            }
            cursor = y;
            pp = pn = 0;
        }

        /** float を流れに影響させずに置く（{@code x}, {@code y} はチャンク基準の border-box の左上）。 */
        void addFloat(LayoutFragment f, float x, float y) {
            if (n == frags.length) {
                frags = java.util.Arrays.copyOf(frags, n * 2);
                xs = java.util.Arrays.copyOf(xs, n * 2);
                ys = java.util.Arrays.copyOf(ys, n * 2);
            }
            frags[n] = f;
            xs[n] = x;
            ys[n++] = y;
            if (f.pending != null) {
                if (pend == null) pend = new ArrayList<>();
                for (OutOfFlow.Pending p : f.pending) pend.add(p.shift(x, y));
            }
        }

        /** 流れの外の子: 今の流れの位置（内容ボックスの左・次の子が来るはずの上端）を静的位置として渡す。 */
        void addOutOfFlow(BoxNode child) {
            if (pend == null) pend = new ArrayList<>();
            pend.add(new OutOfFlow.Pending(child, 0, started ? cursor + pp + pn : 0));
        }

        FlowChunk build(BoxChunk source, ConstraintSpace space, LayoutFragment[][] slots) {
            LayoutFragment[] fs = n == frags.length ? frags : java.util.Arrays.copyOf(frags, n);
            float[] x = n == xs.length ? xs : java.util.Arrays.copyOf(xs, n);
            float[] y = n == ys.length ? ys : java.util.Arrays.copyOf(ys, n);
            OutOfFlow.Pending[] up = pend == null ? null : pend.toArray(OutOfFlow.NONE);
            if (!started) return new FlowChunk(source, space, fs, x, y, 0, lp, ln, lp, ln, true, slots, up, n);
            return new FlowChunk(source, space, fs, x, y, LayoutUnit.snap(cursor), lp, ln, pp, pn, false, slots, up, leadCount);
        }
    }

    /**
     * 前回の流れの {@code [from, to]} 番目のチャンクの断片を、DOM の順に先頭から照合する（並びは概ね保たれるので、
     * 位置を覚えて少しずつ進める。足された節は見つからず位置は進まない）。表を作らない。
     */
    static final class Cursor {
        private static final int LOOKAHEAD = 8;
        private final ChunkedFlow flow;
        private final int to;
        private int chunk, idx;

        Cursor(ChunkedFlow flow, int from, int to) {
            this.flow = flow;
            this.to = flow == null ? -1 : Math.min(to, flow.chunks.length - 1);
            this.chunk = Math.max(0, from);
        }

        LayoutFragment find(BoxNode child) {
            if (flow == null) return null;
            int c = chunk, i = idx;
            for (int step = 0; step < LOOKAHEAD && c <= to; step++) {
                LayoutFragment[] fs = flow.chunks[c].frags;
                if (i >= fs.length) { c++; i = 0; step--; continue; }
                LayoutFragment f = fs[i];
                if (f.box != null && f.box.node == child.node) {
                    chunk = c;
                    idx = i + 1;
                    return f;
                }
                i++;
            }
            return null;
        }
    }

    @Override
    public MinMaxSizes computeMinMaxSizes(BoxNode node, MinMaxContext ctx, LayoutSession s) {
        Style st = node.style;
        BoxGeometry g = new BoxGeometry(st, Float.NaN, s);
        float min = 0, max = 0;
        BoxChildren kids = node.flow;
        if (node.kind == BoxKind.TEXT || !kids.hasBlock()) {
            MinMaxSizes m = InlineFlow.minMax(node.kind == BoxKind.TEXT ? List.of(node) : kids.toList(), s);
            min = m.min;
            max = m.max;
        } else {
            List<BoxNode> run = new ArrayList<>();
            for (BoxNode child : kids.toList()) {
                if (child.kind == BoxKind.NONE || OutOfFlow.is(child)) continue;
                if (!child.kind.blockLevel()) { run.add(child); continue; }
                if (!run.isEmpty()) {
                    MinMaxSizes m = InlineFlow.minMax(run, s);
                    min = Math.max(min, m.min);
                    max = Math.max(max, m.max);
                    run.clear();
                }
                MinMaxSizes m = new BoxGeometry(child.style, Float.NaN, s).contribution(child.style, s.cache().minMax(child, s), s);
                float margins = fixedMargin(child.style, child.style.box.marginLeft, s) + fixedMargin(child.style, child.style.box.marginRight, s);
                min = Math.max(min, m.min + margins);
                max = Math.max(max, m.max + margins);
            }
            if (!run.isEmpty()) {
                MinMaxSizes m = InlineFlow.minMax(run, s);
                min = Math.max(min, m.min);
                max = Math.max(max, m.max);
            }
        }
        return new MinMaxSizes(LayoutUnit.snap(min + g.hdec()), LayoutUnit.snap(max + g.hdec()));
    }

    /** 内在サイズへ足す余白（% と auto は 0）。 */
    static float fixedMargin(Style st, long v, LayoutSession s) {
        return CssLength.hasPercent(v, st.box.exprs) ? 0 : BoxGeometry.len(st, v, Float.NaN, s);
    }
}
