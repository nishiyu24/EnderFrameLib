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
        BoxGeometry g = new BoxGeometry(st, cbw, space, s); // 枠線を重ねる表のセルは表が決めた枠線（space.borders）
        boolean text = node.kind == BoxKind.TEXT;
        float w, ml = g.mL;
        if (!Float.isNaN(space.fixedWidth)) {
            w = space.fixedWidth;
        } else {
            float spec = g.specifiedWidth(node, cbw, cbw - g.mL - g.mR, s);
            if (Float.isNaN(spec) && !text) { // aspect-ratio: 確定した高さから幅を決める
                float hd = !Float.isNaN(space.fixedHeight) ? space.fixedHeight : g.specifiedHeight(st, space.percentHeightBase, s);
                if (!Float.isNaN(hd)) spec = g.ratioWidth(st, g.clampHeight(st, hd, space.percentHeightBase, s), cbw, s);
            }
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
        // aspect-ratio: 高さが auto なら幅から決めた高さ（中身がそれより高ければ伸びる。自動の最小サイズ）。比で決めた高さは確定
        float ratio = text ? Float.NaN : BoxGeometry.aspectRatio(st);
        float ratioH = heightAuto && ratio > 0 ? g.heightFromWidth(st, ratio, w) : Float.NaN;
        boolean collapseTop = !root && g.pT == 0 && g.bT == 0;
        boolean collapseBottom = !root && g.pB == 0 && g.bB == 0 && heightAuto;
        // 文書の根の子（html）の % の高さの基準は初期包含ブロック（ビューポート）の高さ（CSS 2 §10.5）
        float childPct = node.node.parent == null ? space.percentHeightBase
                : !Float.isNaN(ratioH) ? Math.max(0, ratioH - g.vdec() - spill)
                : heightAuto ? Float.NaN : Math.max(0, fixedH - g.vdec() - spill);
        // 同じ BFC の外の float（親が渡す。独立した整形コンテキストの根は受け取らない）を内容ボックス基準へ
        Floats.Space inherited = root || text || space.exclusions.isEmpty() ? Floats.Space.EMPTY
                : space.exclusions.translate(-(ml + g.bL + g.pL), -(g.bT + g.pT + spill));
        Floats.Space floatsOut = Floats.Space.EMPTY;

        BoxChildren kids = node.flow;
        LineFragment[] lines = null;
        ChunkedFlow flow = null;
        Assembled a;
        List<OutOfFlow.Pending> pend = new ArrayList<>();
        float insetL = g.bL + g.pL, insetT = g.bT + g.pT + spill;
        if (legend == null && (text || (kids.hasInline() && !kids.hasBlock()))) {
            InlineFlow.Result r = InlineFlow.layout(text ? List.of(node) : kids.toList(), st, cw, prior, inherited, 0, childPct, s);
            lines = r.lines().length > 0 ? r.lines() : null;
            a = new Assembled(r.height());
            for (OutOfFlow.Pending p : r.pending()) pend.add(p.shift(insetL, insetT));
        } else {
            ConstraintSpace base = s.space(cw, Float.NaN, Float.NaN, Float.NaN, 0);
            ConstraintSpace pct = Float.isNaN(childPct) ? base : s.space(cw, Float.NaN, Float.NaN, childPct, 0);
            ChunkedFlow priorFlow = prior != null ? prior.flow : null;
            float[] floatBottom = {0};
            Floats.Space[] excOut = {Floats.Space.EMPTY};
            boolean mixed = kids.hasInline() || kids.hasFloat() || kids.hasContents() || legend != null || !inherited.isEmpty();
            FlowChunk[] chunks = mixed ? null : chunks(kids, base, pct, priorFlow, s);
            if (chunks != null && exportsFloats(chunks)) chunks = null; // 子孫の float が後ろの兄弟に掛かる: 混在の流れで並べ直す
            Floats.Space in = inherited;
            boolean mixedFlow = chunks == null;
            if (mixedFlow) chunks = new FlowChunk[]{mixed(st, kids, legend, base, pct, priorFlow, floatBottom, in, 0, excOut, s)};
            a = assemble(chunks, collapseTop, collapseBottom);
            if (mixedFlow && a.chunkY.length == 1 && a.chunkY[0] != 0 && (!inherited.isEmpty() || chunks[0].leadCount > 0)) {
                // 流れの原点（最初の子の相殺しない余白の下）を 0 と見積もって外れた: 受け取った float と、流れの前に置いた float の
                // 除外領域を正しい原点の座標で並べ直す（float 自身の位置は ChunkedFlow.y が内容ボックスの上端から解く）
                in = inherited.translate(0, -a.chunkY[0]);
                chunks = new FlowChunk[]{mixed(st, kids, legend, base, pct, priorFlow, floatBottom, in, a.chunkY[0], excOut, s)};
                a = assemble(chunks, collapseTop, collapseBottom);
            }
            // 独立した整形コンテキストを作らない箱は、自分の部分木が置いた float を親へ返す（後ろの兄弟が避ける。border-box 基準）
            if (!root && chunks.length == 1) floatsOut = excOut[0].suffix(in.size()).translate(insetL, insetT + a.chunkY[0]);
            // 独立した整形コンテキストの根は中の float を含んで高さを決める
            if (root && chunks.length == 1) a.contentH = Math.max(a.contentH, LayoutUnit.snap(a.chunkY[0] + floatBottom[0]));
            flow = chunks.length > 0 ? new ChunkedFlow(chunks, a.chunkY, a.firstStarted, collapseTop) : null;
            for (int k = 0; k < chunks.length; k++) {
                if (chunks[k].pending == null) continue;
                for (OutOfFlow.Pending p : chunks[k].pending) pend.add(p.shift(insetL, insetT + a.chunkY[k]));
            }
        }
        float h = heightAuto ? a.contentH + g.vdec() + spill : fixedH;
        if (!Float.isNaN(ratioH)) h = Math.max(h, ratioH);
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
        LayoutFragment out = new LayoutFragment(node, space, w, h, ml, tp, tn, bp, bn, insetL, insetT, through, flow, lines, up, oof,
                Float.NaN, floatsOut);
        // 外側の ::marker は項目を整形した後で、最初の行のベースラインに揃えて左外に置く（OutsideMarker）
        PlacedFragment marker = text || node.marker == null ? null : OutsideMarker.place(node, out, cw, prior, s);
        if (marker == null) return out;
        return new LayoutFragment(node, space, w, h, ml, tp, tn, bp, bn, insetL, insetT, through, flow, lines, up,
                OutsideMarker.prepend(marker, oof), Float.NaN, floatsOut);
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
                b.add(cache.layout(child, sp, cur.find(child), s), 0);
            }
            out[k] = b.build(c, pct, null);
        }
        return out;
    }

    private static boolean exportsFloats(FlowChunk[] chunks) {
        for (FlowChunk c : chunks) if (c.exportsFloats) return true;
        return false;
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
     * （{@link Floats}。同じ BFC のインラインの行は float を避ける）。{@code inherited} は外の float（チャンク基準）、{@code lead} は
     * チャンクの原点の内容ボックスでの位置（流れの箱が始まる前に置いた float の除外領域をチャンク基準へ移す分）で、
     * {@code floatBottom[0]} に一番下の float の下端、{@code excOut[0]} に最後の除外領域（{@code inherited} が先頭）を返す。
     */
    private static FlowChunk mixed(Style st, BoxChildren kids, BoxNode skip, ConstraintSpace base, ConstraintSpace pct,
                                   ChunkedFlow priorFlow, float[] floatBottom, Floats.Space inherited, float lead,
                                   Floats.Space[] excOut, LayoutSession s) {
        s.cache().chunkComputed();
        Cursor cur = new Cursor(priorFlow, 0, priorFlow != null ? priorFlow.chunks.length - 1 : -1);
        ChunkBuilder b = new ChunkBuilder(8, s);
        List<BoxNode> run = new ArrayList<>();
        Floats.Space exc = inherited;
        for (BoxNode child : kids.toList()) {
            if (child.kind == BoxKind.NONE || child == skip) continue; // skip: fieldset の描画される legend
            if (OutOfFlow.is(child)) {
                flushRun(st, run, base, pct.percentHeightBase, b, exc, s);
                b.addOutOfFlow(child);
                continue;
            }
            if (!child.kind.blockLevel()) {
                run.add(child);
                continue;
            }
            flushRun(st, run, base, pct.percentHeightBase, b, exc, s);
            if (Floats.is(child)) {
                // 流れの箱が始まる前の float は内容ボックスの上端（チャンク基準で -lead）から積み、位置は内容ボックス基準で持つ（ChunkedFlow.y）
                boolean leading = !b.started();
                Floats.Placed p = Floats.place(child, exc, leading ? -lead : b.flowY(), base.availableWidth, pct.percentHeightBase,
                        cur.find(child), s);
                exc = p.space();
                b.addFloat(p.frag(), p.x(), leading ? p.y() + lead : p.y());
                continue;
            }
            var clear = child.style.box.clear;
            if (clear != null && clear != com.nishiyu.enderframelib.core.css.model.CssKeywords.Clear.NONE) b.clearTo(exc.clearance(clear));
            ConstraintSpace sp = BoxGeometry.usesPercentHeight(child.style) ? pct : base;
            LayoutFragment prev = cur.find(child);
            if (FloatAvoid.avoids(child)) {
                if (exc.isEmpty()) b.add(s.cache().layout(child, sp, prev, s), 0);
                else FloatAvoid.place(child, sp, exc, b, prev, s);
            } else { // 外の float を渡し、子が置いた float を受け取る（除外領域が空なら鍵は変わらない）
                exc = FloatAvoid.intrude(child, sp, exc, b, prev, s);
            }
        }
        flushRun(st, run, base, pct.percentHeightBase, b, exc, s);
        floatBottom[0] = exc.bottom();
        excOut[0] = exc;
        return b.build(null, pct, null);
    }

    private static void flushRun(Style st, List<BoxNode> run, ConstraintSpace base, float pctH, ChunkBuilder b, Floats.Space exc,
                                 LayoutSession s) {
        if (run.isEmpty()) return;
        boolean blank = true;
        for (BoxNode n : run) blank &= n.collapsibleWhitespace();
        if (!blank) {
            InlineFlow.Result r = InlineFlow.layout(run, st, base.availableWidth, null, exc, b.flowY(), pctH, s);
            if (r.lines().length > 0 || !r.pending().isEmpty()) {
                b.add(new LayoutFragment(null, base, base.availableWidth, r.height(), 0, 0, 0, 0, 0, 0, 0, r.lines().length == 0,
                        null, r.lines().length > 0 ? r.lines() : null,
                        r.pending().isEmpty() ? null : r.pending().toArray(OutOfFlow.NONE), null), 0);
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
    static final class ChunkBuilder {
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

        /** 流れの箱を次の位置に置く（{@code dx} は外の float を避けて右へ寄せる分）。 */
        void add(LayoutFragment f, float dx) {
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
            float x = f.marginLeft + dx;
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

        boolean started() {
            return started;
        }

        /** 次の流れの箱 {@code f} の border-box の上端を {@code y} に置く（float の下へ下げた箱。前の余白とは相殺しない）。 */
        void placeAt(float y, LayoutFragment f) {
            if (!started) {
                started = true;
                leadCount = n;
            }
            cursor = y - Math.max(0, f.topPos) - Math.min(0, f.topNeg);
            pp = pn = 0;
        }

        /** 上の余白 {@code (tp, tn)} の流れの箱を次に置いたときの border-box の上端（チャンク基準。素通しの箱は除く）。 */
        float peekY(float tp, float tn) {
            return started ? cursor + Math.max(pp, tp) + Math.min(pn, tn) : 0;
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
