package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.box.BoxChildren;
import com.nishiyu.enderframelib.core.box.BoxChunk;
import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.WritingModes;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.model.Position;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.layout.fragment.AnchorScroll;
import com.nishiyu.enderframelib.core.layout.fragment.BoxGeometry;
import com.nishiyu.enderframelib.core.layout.fragment.FlowChunk;
import com.nishiyu.enderframelib.core.layout.fragment.InlineFlow;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.layout.fragment.LineFragment;
import com.nishiyu.enderframelib.core.layout.fragment.PlacedFragment;
import com.nishiyu.enderframelib.core.layout.fragment.ScrollableOverflow;
import com.nishiyu.enderframelib.core.layout.fragment.TableBorders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code (LayoutFragment, 最新の BoxNode)} から不変の {@link DisplayScene} を直接作る（M3b-2。計画書 §2.1-3 / §3.4）。
 *
 * <p><b>記憶は 1 層だけ</b>: 鍵は {@link SceneLayer} の {@code (LayoutFragment ==, 最新の BoxNode ==)} と、
 * {@link SceneChunk} の {@code FlowChunk ==}（配下の {@code BoxNode} が最新の木で分岐していないとき）だけ。
 * 描く文脈（絶対位置・クリップ・親 ID）・純粋性述語・台帳の祖先照合・部品除外一覧は持たない。</p>
 */
public final class SceneEmitter {
    /**
     * 層の描画命令（{@link SceneLayer#ops}）を作る口（M3b-4a）。描画のパッケージが実装し、{@code V3Frame} が渡す
     * （scene のパッケージは描画のパッケージに依存しない）。層を新しく作ったときだけ呼ぶので、命令は層とともに記憶される。
     */
    @FunctionalInterface
    public interface OpsSource {
        /** 層 {@code layer}（作っている途中。{@link SceneLayer#ops()} は読まない）の命令を、層の断片 {@code runs} を写して作る。 */
        DrawOps ops(SceneLayer layer, DrawOps runs, int viewportW, int viewportH);
    }

    private final LayoutSession session;
    private OpsSource opsSource;
    private final Map<String, Keyframes> keyframes;
    private DisplayScene prevScene;
    private int prevVw = -1, prevVh = -1;

    // ---- 1 回の構築の間だけ使う作業領域 ----
    private final Map<BoxNode, BoxNode> latest = new IdentityHashMap<>();
    private final Set<BoxChunk> divergedChunks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<LayoutFragment, float[]> overflow = new IdentityHashMap<>();
    private final Map<HtmlNode, Integer> anonymousCounts = new IdentityHashMap<>();
    private int vw, vh;
    private int builtLayers, reusedLayers, reusedChunks;

    public SceneEmitter(LayoutSession session, Map<String, Keyframes> keyframes) {
        this.session = session;
        this.keyframes = keyframes != null ? keyframes : Map.of();
    }

    public DisplayScene scene() {
        return prevScene;
    }

    /** 層の描画命令を作る口を渡す。前回のシーン（命令の無い層）は捨てる。 */
    public void opsSource(OpsSource source) {
        this.opsSource = source;
        invalidate();
    }

    /** 前回のシーンを捨て、次の {@link #emit} で全部の層を作り直す（描画命令が装置の画素比などに依るとき）。 */
    public void invalidate() {
        prevScene = null;
        prevVw = prevVh = -1;
    }

    /** 作っている層 {@code layer} の描画の中身（自分を描かない層・命令の口が無ければ断片だけの {@code runs}）。{@link SceneLayer} のコンストラクタが呼ぶ。 */
    /** 層の包含矩形に命令の塗る範囲を足す作業用の読み手と矩形（M3b-4c-2b-2c。層を作るたびに作らない。このエミッタの作る層だけが使う）。 */
    private final DrawOps.Cursor inkCursor = new DrawOps.Cursor();
    private final float[] inkBounds = new float[4];

    DrawOps.Cursor inkCursor() { return inkCursor; }

    float[] inkBounds() { return inkBounds; }

    DrawOps opsFor(SceneLayer layer, DrawOps runs) {
        if (opsSource == null || !layer.hasSelf()) return runs;
        DrawOps out = opsSource.ops(layer, runs, vw, vh);
        if (out == null) return runs;
        if (out.runCount() != runs.runCount()) throw new IllegalStateException("命令は層の断片を写して作る");
        return out;
    }

    public int builtLayers() { return builtLayers; }
    public int reusedLayers() { return reusedLayers; }
    public int reusedChunks() { return reusedChunks; }

    void countBuilt() {
        builtLayers++;
    }

    /** 前回のシーンとビューポートから、増分で {@link DisplayScene} を組み立てる。 */
    public DisplayScene emit(LayoutFragment rootFrag, BoxNode latestRoot, int viewportW, int viewportH) {
        builtLayers = reusedLayers = reusedChunks = 0;
        this.vw = viewportW;
        this.vh = viewportH;
        try {
            diverge(latestRoot.layout, latestRoot);
            Map<Object, SceneLayer> prev = new IdentityHashMap<>();
            if (prevScene != null && prevVw == viewportW && prevVh == viewportH) {
                index(prevScene.root, prev);
            }
            SceneLayer root = buildBlock(rootFrag, 0f, 0f, prev, false, false, true);
            if (prevScene != null && root == prevScene.root && prevVw == viewportW && prevVh == viewportH) {
                return prevScene;
            }
            prevScene = new DisplayScene(root);
            prevVw = viewportW;
            prevVh = viewportH;
            return prevScene;
        } finally {
            latest.clear();
            divergedChunks.clear();
            overflow.clear();
            anonymousCounts.clear();
        }
    }

    private void diverge(BoxNode l, BoxNode p) {
        if (l == p) return;
        latest.put(l, p);
        divergeChildren(l.flow, p.flow);
        if (l.marker != null && p.marker != null) diverge(l.marker, p.marker);
    }

    private void divergeChildren(BoxChildren lf, BoxChildren pf) {
        int lc = lf.chunkCount(), pc = pf.chunkCount();
        if (lc == pc) {
            for (int c = 0; c < lc; c++) {
                BoxChunk lch = lf.chunk(c), pch = pf.chunk(c);
                if (lch == pch) continue;
                divergedChunks.add(lch);
                for (int i = 0, n = Math.min(lch.size(), pch.size()); i < n; i++) {
                    diverge(lch.get(i), pch.get(i));
                }
            }
            return;
        }
        for (int c = 0; c < lc; c++) divergedChunks.add(lf.chunk(c));
        for (int i = 0, n = Math.min(lf.size(), pf.size()); i < n; i++) {
            diverge(lf.get(i), pf.get(i));
        }
    }

    private BoxNode latestOf(BoxNode l) {
        BoxNode p = latest.get(l);
        return p != null ? p : l;
    }

    private static void index(SceneLayer l, Map<Object, SceneLayer> out) {
        if (l.frag != null) {
            out.put(l.frag, l);
            BoxNode b = l.frag.box;
            if (b != null) {
                out.putIfAbsent(b, l);
                if (!b.generated()) out.putIfAbsent(b.node, l);
            }
        } else {
            for (SceneChunk c : l.children.chunks) {
                for (SceneLayer k : c.layers) index(k, out);
            }
        }
    }

    /** 前回の層 {@code l} の子を引く表。{@code skip} のチャンク（そのまま使い回すもの）の子は入れない（8,000 子でも作り直す分だけ。M3b-4c-2b-3a）。 */
    private static Map<Object, SceneLayer> childIndex(SceneLayer l, Set<SceneChunk> skip) {
        if (l == null || l.children.size == 0) return null;
        Map<Object, SceneLayer> out = new IdentityHashMap<>();
        for (SceneChunk c : l.children.chunks) {
            if (skip != null && skip.contains(c)) continue;
            for (SceneLayer k : c.layers) index(k, out);
        }
        return out;
    }

    private static Map<FlowChunk, SceneChunk> chunkIndex(SceneLayer l) {
        if (l == null || l.children.chunks.length == 0) return null;
        Map<FlowChunk, SceneChunk> out = null;
        for (SceneChunk c : l.children.chunks) {
            if (c.source != null) {
                if (out == null) out = new IdentityHashMap<>();
                out.put(c.source, c);
            }
        }
        return out;
    }

    /**
     * 断片 {@code f} の層。前回の層は鍵 {@code (断片 ==, 最新の箱 ==)} で使い回す（§2.3 L4a）。親の文脈（flex・grid の子か・浮動を止める親か）は
     * 親の {@code display} だけで決まり、子を引く表 {@code prev} は親の {@code display} が前回と同じときだけ渡すので、鍵が当たった層の文脈は同じ
     * （根の断片は根でしか当たらない。M3b-4c-1c。§1.6 決定 U）。描画される {@code <legend>} は親の値（{@link SceneChildren#legend}）。
     * 鍵のほかに残る条件は 2 つ: ブロックの文字の層は断片の座標が層の位置を含む（原点の箱から）ので作り直す。表の匿名の箱の ID は走査の順の
     * 通し番号なので番号が同じときだけ使い回す（どちらも M3b-5 で層・箱の値にする）。
     */
    SceneLayer buildBlock(LayoutFragment f, float x, float y, Map<Object, SceneLayer> prev,
                          boolean parentInFlexOrGrid, boolean parentBlocksFloat, boolean isRoot) {
        BoxNode b = f.box;
        BoxNode lb = latestOf(b);
        SceneLayer old = prev != null ? prev.get(f) : null;
        if (old != null && old.box == lb && b.kind != BoxKind.TEXT && anonymousMatches(old, b)) {
            reusedLayers++;
            return old.withPosition(x, y);
        }
        SceneLayer before = old != null ? old : prev == null ? null
                : prev.get(b) != null ? prev.get(b) : !b.generated() ? prev.get(b.node) : null;
        HtmlNode node = nodeOf(b, anonymousCounts);
        ComputedStyle style = new ComputedStyle(lb.style);
        Display display = Display.of(node, style).blockified(style);
        boolean anonymous = b.anonymousTableBox();
        boolean verticalWrapper = b.orthogonalWrapper();
        boolean interactive = node != null && !node.isPseudoNode && lb.values.interactive();
        boolean isText = b.kind == BoxKind.TEXT;

        float mT, mR, mB, mL, pT, pR, pB, pL, bT, bR, bB, bL;
        float[] collapsedBorders = null;
        float scrollWidth, scrollHeight, lineHeight;
        if (old != null) {
            mT = old.mT; mR = old.mR; mB = old.mB; mL = old.mL;
            pT = old.pT; pR = old.pR; pB = old.pB; pL = old.pL;
            bT = old.bT; bR = old.bR; bB = old.bB; bL = old.bL;
            collapsedBorders = old.collapsedBorders;
            scrollWidth = old.scrollWidth;
            scrollHeight = old.scrollHeight;
            lineHeight = old.lineHeight;
        } else {
            BoxGeometry g = new BoxGeometry(b.style, f.space.availableWidth, session);
            mT = g.mT; mR = g.mR; mB = g.mB; mL = f.marginLeft;
            pT = g.pT; pR = g.pR; pB = g.pB; pL = g.pL;
            bT = g.bT; bR = g.bR; bB = g.bB; bL = g.bL;
            TableBorders.Edges e = f.space.usedBorders();
            if (TableBorders.collapses(b)) {
                e = TableBorders.ofTable(b, session);
                pT = pR = pB = pL = 0f;
            }
            if (e != null) {
                bT = e.top(); bR = e.right(); bB = e.bottom(); bL = e.left();
                collapsedBorders = new float[]{e.top(), e.right(), e.bottom(), e.left()};
            }
            if (b.kind == BoxKind.TABLE_ROW || b.kind == BoxKind.TABLE_ROW_GROUP) {
                mT = mR = mB = mL = pT = pR = pB = pL = bT = bR = bB = bL = 0f;
            }
            scrollWidth = scrollHeight = Float.NaN; // 子を組んでから求める（使い回したチャンクの到達範囲を読む）
            lineHeight = InlineFlow.lineHeight(b.style, session.face(b.style));
        }

        boolean sameDisplay = before != null && java.util.Objects.equals(before.display, display);
        boolean pureFlow = !isText && f.lines == null && isPureFlow(b, f);
        Map<Object, SceneLayer> kidsIdx = sameDisplay && !pureFlow ? childIndex(before, null) : null;
        Display.Inner inner = display != null ? display.inner() : null;
        boolean childFlexGrid = inner == Display.Inner.FLEX || inner == Display.Inner.GRID;
        boolean childBlocksFloat = inner == Display.Inner.FLEX || inner == Display.Inner.GRID || inner == Display.Inner.TABLE
                || inner == Display.Inner.TABLE_ROW_GROUP || inner == Display.Inner.TABLE_ROW;

        if (isText) {
            if (Float.isNaN(scrollWidth)) {
                float[] ov = ScrollableOverflow.of(f, overflow, session);
                scrollWidth = ov[0];
                scrollHeight = ov[1];
            }
            SceneInlineBuilder.Draft d = new SceneInlineBuilder.Draft();
            Map<BoxNode, SceneInlineBuilder.Draft> boxes = new IdentityHashMap<>();
            boxes.put(b, d);
            SceneInlineBuilder.lines(d, f.lines, x + f.insetLeft, y + f.insetTop, boxes, kidsIdx, null,
                    this, session, childFlexGrid, childBlocksFloat);
            SceneLayerProps.Computed p = SceneLayerProps.compute(lb, node, style, display, f.width, f.height,
                    bT, bR, bB, bL, pT, pR, pB, pL, mT, mR, mB, mL,
                    anonymous, verticalWrapper, interactive, parentInFlexOrGrid, parentBlocksFloat,
                    SceneChildren.EMPTY, isRoot, vw, vh, keyframes);
            builtLayers++;
            int nid = b.generated() || node == null || node.isPseudoNode ? 0 : node.nodeId();
            return new SceneLayer(nid, x, y, f.width, f.height, p.transform(), p.clip(),
                    p.effects(), p.sticky(), DrawOps.EMPTY, SceneChildren.EMPTY, lb, f, node, style, display,
                    bT, bR, bB, bL, pT, pR, pB, pL, mT, mR, mB, mL, collapsedBorders, scrollWidth, scrollHeight, lineHeight,
                    d.firstLine, d.firstLineBgRect, d.runs.isEmpty() ? SceneLayer.NO_RUNS : d.runs.toArray(SceneLayer.NO_RUNS),
                    null, p.kind(), p.zIndex(), p.layerFlags(), p.propagatedLayers(), this);
        }

        SceneChildren sceneChildren;
        ComputedStyle firstLine = null;
        float[] firstLineBgRect = null;
        float[][] chunkOverflow = null;
        if (pureFlow) {
            Flow flow = buildChunkedFlow(f, sameDisplay ? before : null, childFlexGrid, childBlocksFloat, Float.isNaN(scrollWidth));
            sceneChildren = flow.children();
            chunkOverflow = flow.overflow();
        } else {
            SceneInlineBuilder.Draft d = new SceneInlineBuilder.Draft();
            d.w = f.width; d.h = f.height;
            d.bT = bT; d.bR = bR; d.bB = bB; d.bL = bL;
            d.pT = pT; d.pR = pR; d.pB = pB; d.pL = pL;
            float cx = f.insetLeft, cy = f.insetTop;
            Map<BoxNode, SceneInlineBuilder.Draft> textBoxes = new IdentityHashMap<>();
            if (f.lines != null) {
                SceneInlineBuilder.inlineChildren(b, lb, d, textBoxes, f.width - bL - pL - pR - bR, session);
                SceneInlineBuilder.lines(d, f.lines, cx, cy, textBoxes, kidsIdx, lb.firstLine != null ? lb : null,
                        this, session, childFlexGrid, childBlocksFloat);
            }
            if (f.flow != null) {
                Map<BoxNode, SceneInlineBuilder.Draft> anon = new IdentityHashMap<>();
                float cbw = f.width - bL - pL - pR - bR;
                java.util.Set<BoxNode> direct = b.flow.hasBlockInInline() ? SceneInlineBuilder.directChildren(b) : null;
                f.flow.forEach((child, x1, y1) -> {
                    if (child.box != null) {
                        SceneInlineBuilder.Draft parent = direct != null && !direct.contains(child.box)
                                ? SceneInlineBuilder.ancestors(b, lb, child.box, d, anon, cbw, false, session) : null;
                        (parent != null ? parent : d).add(
                                buildBlock(child, cx + x1, cy + y1, kidsIdx, childFlexGrid, childBlocksFloat, false),
                                cx + x1, cy + y1);
                    } else {
                        for (LineFragment l : child.lines) {
                            for (LineFragment.Piece p : l.pieces()) {
                                SceneInlineBuilder.collectAncestors(b, lb, p.text(), d, anon, cbw, session);
                            }
                            for (LineFragment.Span sp : l.spans()) {
                                SceneInlineBuilder.collectAncestors(b, lb, sp.box(), d, anon, cbw, session);
                            }
                        }
                        SceneInlineBuilder.lines(d, child.lines, cx + x1, cy + y1, anon, kidsIdx, null,
                                this, session, childFlexGrid, childBlocksFloat);
                    }
                });
            }
            for (SceneInlineBuilder.Child c : d.children) {
                if (c.item() instanceof SceneInlineBuilder.Draft cd && !cd.placed && (cd.text || inlineFlow(cd.display))) {
                    SceneInlineBuilder.fitInline(cd);
                }
            }
            BoxNode legend = null;
            if (f.oof != null) {
                for (PlacedFragment p : f.oof) {
                    SceneLayer placed = buildOof(b, p, kidsIdx, childFlexGrid, childBlocksFloat);
                    if (renderedLegend(b, p)) legend = placed.box;
                    d.children.add(documentOrderIndex(d.children, placed.node), new SceneInlineBuilder.Child(placed, p.x(), p.y()));
                }
            }
            firstLine = d.firstLine;
            firstLineBgRect = d.firstLineBgRect;
            sceneChildren = SceneInlineBuilder.freezeChildren(d.children, display, vw, vh, keyframes, this).withLegend(legend);
        }

        if (Float.isNaN(scrollWidth)) {
            float[] ov = ScrollableOverflow.of(f, overflow, session, chunkOverflow);
            scrollWidth = ov[0];
            scrollHeight = ov[1];
        }
        SceneLayerProps.Computed p = SceneLayerProps.compute(lb, node, style, display, f.width, f.height,
                bT, bR, bB, bL, pT, pR, pB, pL, mT, mR, mB, mL,
                anonymous, verticalWrapper, interactive, parentInFlexOrGrid, parentBlocksFloat,
                sceneChildren, isRoot, vw, vh, keyframes);
        builtLayers++;
        int nid = b.generated() || node == null || node.isPseudoNode ? 0 : node.nodeId();
        return new SceneLayer(nid, x, y, f.width, f.height, p.transform(), p.clip(),
                p.effects(), p.sticky(), DrawOps.EMPTY, sceneChildren, lb, f, node, style, display,
                bT, bR, bB, bL, pT, pR, pB, pL, mT, mR, mB, mL, collapsedBorders, scrollWidth, scrollHeight, lineHeight,
                firstLine, firstLineBgRect, SceneLayer.NO_RUNS, null, p.kind(), p.zIndex(), p.layerFlags(), p.propagatedLayers(), this);
    }

    private static boolean isPureFlow(BoxNode b, LayoutFragment f) {
        if (f.flow == null) return true;
        if (b.flow.hasBlockInInline()) return false;
        for (FlowChunk fc : f.flow.chunks) {
            for (LayoutFragment cf : fc.frags) {
                if (cf.box == null) return false;
            }
        }
        return true;
    }

    private record ChunkEntry(SceneChunk chunk, float cx, float cy) { }

    /** 流れの子の並びと、流れのチャンクごとの子の到達範囲（{@link ScrollableOverflow#chunk}。使い回したチャンクは持っていた値）。 */
    private record Flow(SceneChildren children, float[][] overflow) { }

    /**
     * 純粋な流れの子を組む。チャンクは {@code FlowChunk ==} で前回のものを使い回し（§2.3）、前回の子を引く表は作り直すチャンクの分だけ作る
     * （使い回すチャンクの子は引かれない。M3b-4c-2b-3a）。{@code before} は親の {@code display} が前回と同じときの前回の層（違えば null）。
     * {@code needOverflow} は親が自分の到達範囲を求めるか（前回の層から写すときは作り直すチャンクの到達範囲を作らない）。
     */
    private Flow buildChunkedFlow(LayoutFragment f, SceneLayer before, boolean childFlexGrid, boolean childBlocksFloat,
                                  boolean needOverflow) {
        int fcCount = f.flow != null ? f.flow.chunks.length : 0;
        List<ChunkEntry> entries = new ArrayList<>(fcCount + (f.oof != null ? f.oof.length : 0));
        float ox = f.insetLeft, oy = f.insetTop;
        Map<FlowChunk, SceneChunk> prevChunks = chunkIndex(before);
        SceneChunk[] hits = prevChunks != null ? new SceneChunk[fcCount] : null;
        Set<SceneChunk> consumed = null; // 使い回すチャンク（作るのは当たったときだけ）
        boolean rebuild = f.oof != null || prevChunks == null;
        for (int k = 0; prevChunks != null && k < fcCount; k++) {
            FlowChunk fc = f.flow.chunks[k];
            boolean canReuseChunk = f.flow.isUniformChunk(k) && fc.source != null && !divergedChunks.contains(fc.source);
            SceneChunk hit = canReuseChunk ? prevChunks.get(fc) : null;
            if (hit != null && hit.anonChain == 0) {
                hits[k] = hit;
                if (consumed == null) consumed = Collections.newSetFromMap(new IdentityHashMap<>());
                consumed.add(hit);
            } else {
                rebuild = true;
            }
        }
        Map<Object, SceneLayer> kidsIdx = rebuild ? childIndex(before, consumed) : null;
        float[][] chunkOverflow = fcCount > 0 ? new float[fcCount][] : null;
        for (int k = 0; k < fcCount; k++) {
            FlowChunk fc = f.flow.chunks[k];
            boolean uniform = f.flow.isUniformChunk(k);
            boolean canReuseChunk = uniform && fc.source != null && !divergedChunks.contains(fc.source);
            if (hits != null && hits[k] != null) {
                reusedChunks++;
                entries.add(new ChunkEntry(hits[k], ox, oy + f.flow.chunkY[k]));
                chunkOverflow[k] = hits[k].overflow;
                continue;
            }
            int len = fc.frags.length;
            SceneLayer[] layers = new SceneLayer[len];
            float chunkOx = uniform ? ox : 0f;
            float chunkOy = uniform ? oy + f.flow.chunkY[k] : 0f;
            for (int i = 0; i < len; i++) {
                float lx = uniform ? fc.x[i] : ox + fc.x[i];
                float ly = uniform ? fc.y[i] : oy + f.flow.y(k, i);
                layers[i] = buildBlock(fc.frags[i], lx, ly, kidsIdx, childFlexGrid, childBlocksFloat, false);
            }
            // 到達範囲は FlowChunk で決まるので、前回の同じ FlowChunk のチャンクが持っていればその値。無ければ親が求めるときだけ作る
            SceneChunk same = uniform && prevChunks != null ? prevChunks.get(fc) : null;
            float[] ov = same != null ? same.overflow : null;
            if (ov == null && uniform && needOverflow) ov = ScrollableOverflow.chunk(f, k, overflow, session);
            chunkOverflow[k] = ov;
            SceneChunk built = SceneChunk.of(canReuseChunk ? fc : null, layers, ScrollableOverflow.reusable(ov) ? ov : null);
            entries.add(new ChunkEntry(built, chunkOx, chunkOy));
        }
        BoxNode legend = null;
        if (f.oof != null) {
            for (PlacedFragment p : f.oof) {
                SceneLayer placed = buildOof(f.box, p, kidsIdx, childFlexGrid, childBlocksFloat);
                if (renderedLegend(f.box, p)) legend = placed.box;
                insertOof(entries, placed);
            }
        }
        if (entries.isEmpty()) return new Flow(SceneChildren.EMPTY, chunkOverflow);
        int n = entries.size();
        SceneChunk[] chunks = new SceneChunk[n];
        float[] cx = new float[n], cy = new float[n];
        for (int i = 0; i < n; i++) {
            ChunkEntry e = entries.get(i);
            chunks[i] = e.chunk;
            cx[i] = e.cx;
            cy[i] = e.cy;
        }
        return new Flow(SceneChildren.of(chunks, cx, cy).withLegend(legend), chunkOverflow);
    }

    /** {@code owner} の流れの外に置いた {@code p} が fieldset の描画される {@code <legend>} か（レイアウトが流れの外に置く。親の値）。 */
    private static boolean renderedLegend(BoxNode owner, PlacedFragment p) {
        BoxNode cb = p.frag().box;
        return cb != null && cb.style.box.position != Position.ABSOLUTE && cb.style.box.position != Position.FIXED
                && ElementTraits.isFieldset(owner.node.tag) && ElementTraits.isLegend(cb.node.tag);
    }

    private SceneLayer buildOof(BoxNode owner, PlacedFragment p, Map<Object, SceneLayer> kidsIdx,
                                boolean childFlexGrid, boolean childBlocksFloat) {
        BoxNode cb = p.frag().box;
        boolean tries = (cb != null && cb.style.rare.positionTryFallbacks != null) || p.follow() != null;
        SceneLayer placed = buildBlock(p.frag(), p.x(), p.y(), tries ? null : kidsIdx, childFlexGrid, childBlocksFloat, false);
        float[] m = p.margins();
        AnchorScroll follow = p.follow();
        if (m == null && follow == null) return placed;
        float nmT = m != null ? m[0] : placed.mT, nmR = m != null ? m[1] : placed.mR;
        float nmB = m != null ? m[2] : placed.mB, nmL = m != null ? m[3] : placed.mL;
        StickyConstraint sticky = StickyConstraint.of(placed.style.typed(), nmT, nmR, nmB, nmL);
        return new SceneLayer(placed.nodeId, placed.x, placed.y, placed.w, placed.h,
                placed.transform, placed.clip, placed.effects, sticky,
                placed.ops(), placed.children, placed.box, placed.frag, placed.node, placed.style, placed.display,
                placed.bT, placed.bR, placed.bB, placed.bL, placed.pT, placed.pR, placed.pB, placed.pL,
                nmT, nmR, nmB, nmL, placed.collapsedBorders, placed.scrollWidth, placed.scrollHeight, placed.lineHeight,
                placed.firstLine, placed.firstLineBgRect, null, follow, placed.stackingKind, placed.zIndex,
                placed.flags, placed.propagatedLayers, null);
    }

    private static void insertOof(List<ChunkEntry> entries, SceneLayer oof) {
        HtmlNode node = oof.node;
        if (node == null || node.isPseudoNode) {
            entries.add(new ChunkEntry(SceneChunk.of(null, new SceneLayer[]{oof}), 0f, 0f));
            return;
        }
        for (int k = 0; k < entries.size(); k++) {
            ChunkEntry e = entries.get(k);
            SceneLayer[] ls = e.chunk.layers;
            int hit = -1;
            for (int i = 0; i < ls.length; i++) {
                if (followsInDocument(node, ls[i].node)) {
                    hit = i;
                    break;
                }
            }
            if (hit == 0) {
                entries.add(k, new ChunkEntry(SceneChunk.of(null, new SceneLayer[]{oof}), 0f, 0f));
                return;
            }
            if (hit > 0) {
                SceneLayer[] merged = new SceneLayer[ls.length + 1];
                System.arraycopy(ls, 0, merged, 0, hit);
                merged[hit] = oof.withPosition(oof.x - e.cx, oof.y - e.cy);
                System.arraycopy(ls, hit, merged, hit + 1, ls.length - hit);
                entries.set(k, new ChunkEntry(SceneChunk.of(null, merged), e.cx, e.cy));
                return;
            }
        }
        entries.add(new ChunkEntry(SceneChunk.of(null, new SceneLayer[]{oof}), 0f, 0f));
    }

    private static boolean followsInDocument(HtmlNode node, HtmlNode candidate) {
        if (candidate == null || candidate == node || candidate.isPseudoNode) return false;
        int pos = node.compareDocumentPosition(candidate);
        if ((pos & com.nishiyu.enderframelib.api.dom.Node.DOCUMENT_POSITION_DISCONNECTED) != 0) return false;
        return (pos & com.nishiyu.enderframelib.api.dom.Node.DOCUMENT_POSITION_FOLLOWING) != 0
                && (pos & com.nishiyu.enderframelib.api.dom.Node.DOCUMENT_POSITION_CONTAINED_BY) == 0;
    }

    private static int documentOrderIndex(List<SceneInlineBuilder.Child> children, HtmlNode node) {
        if (node == null || node.isPseudoNode) return children.size();
        for (int i = 0, n = children.size(); i < n; i++) {
            Object c = children.get(i).item();
            HtmlNode cn = c instanceof SceneInlineBuilder.Draft cd ? cd.node : c instanceof SceneLayer sl ? sl.node : null;
            if (followsInDocument(node, cn)) return i;
        }
        return children.size();
    }

    private boolean anonymousMatches(SceneLayer old, BoxNode b) {
        if (old.anonChain == 0) return true;
        int next = anonymousCounts.getOrDefault(b.node, 0) + 1;
        if (!(b.node.key() + "_anon" + next).equals(old.node.getId())) return false;
        anonymousCounts.put(b.node, next - 1 + old.anonChain);
        return true;
    }

    private static boolean inlineFlow(Display d) {
        return d != null && d.outer() == Display.Outer.INLINE && d.inner() == Display.Inner.FLOW;
    }

    static HtmlNode nodeOf(BoxNode b, Map<HtmlNode, Integer> anonymousCounts) {
        if (!b.generated()) return b.node;
        if (b.orthogonalWrapper()) {
            HtmlNode n = new HtmlNode("div");
            n.isPseudoNode = true;
            n.parent = b.node;
            n.setId(b.node.key() + (b.style.inherited.writingMode != null
                    && WritingModes.flowOf(b.style.inherited.writingMode) == WritingModes.HORIZONTAL ? "_hw" : "_vw"));
            return n;
        }
        if (b.anonymousTableBox()) {
            HtmlNode n = new HtmlNode("div");
            n.parent = b.node;
            int idx = anonymousCounts != null ? anonymousCounts.merge(b.node, 1, Integer::sum) : 1;
            n.setId(b.node.key() + "_anon" + idx);
            return n;
        }
        HtmlNode n = b.kind == BoxKind.TEXT ? new HtmlText(b.text) : new HtmlNode("span");
        n.isPseudoNode = true;
        n.parent = b.node;
        n.setId(b.node.key() + "_" + b.pseudo.name().toLowerCase(Locale.ROOT) + (b.kind == BoxKind.TEXT ? "_txt" : ""));
        return n;
    }
}
