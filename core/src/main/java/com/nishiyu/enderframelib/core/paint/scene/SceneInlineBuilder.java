package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.model.Position;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.layout.fragment.BoxGeometry;
import com.nishiyu.enderframelib.core.layout.fragment.FirstLine;
import com.nishiyu.enderframelib.core.layout.fragment.InlineFlow;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.layout.fragment.LineFragment;
import com.nishiyu.enderframelib.core.layout.fragment.PlacedFragment;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SceneEmitter} のインライン整形出力の組み立て（インライン要素・文字の節・行の断片・ブロックインインライン）。
 */
final class SceneInlineBuilder {
    private SceneInlineBuilder() { }

    record Child(Object item, float x, float y) { }

    static final class Draft {
        BoxNode box;
        HtmlNode node;
        ComputedStyle style;
        Display display;
        boolean anonymous, verticalWrapper, renderedLegend, text, placed, interactive;
        float x, y, w, h, mT, mR, mB, mL, pT, pR, pB, pL, bT, bR, bB, bL;
        float[] collapsedBorders;
        float scrollWidth, scrollHeight, lineHeight;
        ComputedStyle firstLine;
        float[] firstLineBgRect;
        final List<SceneRun> runs = new ArrayList<>(0);
        final List<Child> children = new ArrayList<>(0);

        void add(Object child, float cx, float cy) {
            children.add(new Child(child, cx, cy));
        }
    }

    static void inlineChildren(BoxNode owner, BoxNode latestOwner, Draft parent, Map<BoxNode, Draft> boxes,
                               float cbw, LayoutSession session) {
        for (int i = 0, n = owner.flow.size(); i < n; i++) {
            BoxNode c = owner.flow.get(i), lc = latestOwner.flow.get(i);
            if (c.kind == BoxKind.NONE || c.kind.atomicInline()) continue;
            if (outOfFlow(c)) continue;
            if (c.kind == BoxKind.CONTENTS) {
                inlineChildren(c, lc, parent, boxes, cbw, session);
                continue;
            }
            Draft d = inlineDraft(c, lc, cbw, session);
            parent.add(d, 0f, 0f);
            boxes.put(c, d);
            if (c.kind != BoxKind.TEXT) inlineChildren(c, lc, d, boxes, cbw, session);
        }
    }

    static void collectAncestors(BoxNode owner, BoxNode latestOwner, BoxNode target, Draft ownerDraft,
                                 Map<BoxNode, Draft> boxes, float cbw, LayoutSession session) {
        if (!boxes.containsKey(target)) ancestors(owner, latestOwner, target, ownerDraft, boxes, cbw, true, session);
    }

    static Draft ancestors(BoxNode owner, BoxNode latestOwner, BoxNode target, Draft ownerDraft,
                           Map<BoxNode, Draft> boxes, float cbw, boolean withTarget, LayoutSession session) {
        List<BoxNode> path = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        if (!pathTo(owner, target, -1, path, at)) return null;
        Draft parent = ownerDraft;
        BoxNode cur = latestOwner;
        for (int i = 1, end = withTarget ? path.size() : path.size() - 1; i < end; i++) {
            BoxNode n = path.get(i);
            cur = cur.flow.get(at.get(i));
            if (n.kind == BoxKind.CONTENTS) continue;
            if (outOfFlow(n)) return null;
            Draft d = boxes.get(n);
            if (d == null) {
                d = inlineDraft(n, cur, cbw, session);
                parent.add(d, 0f, 0f);
                boxes.put(n, d);
            }
            parent = d;
        }
        return parent;
    }

    private static boolean pathTo(BoxNode from, BoxNode target, int index, List<BoxNode> path, List<Integer> at) {
        path.add(from);
        at.add(index);
        if (from == target) return true;
        if (from.kind == BoxKind.INLINE || from.kind == BoxKind.CONTENTS || path.size() == 1) {
            for (int i = 0, n = from.flow.size(); i < n; i++) {
                if (pathTo(from.flow.get(i), target, i, path, at)) return true;
            }
        }
        path.remove(path.size() - 1);
        at.remove(at.size() - 1);
        return false;
    }

    private static Draft inlineDraft(BoxNode c, BoxNode lc, float cbw, LayoutSession session) {
        Draft d = new Draft();
        d.box = lc;
        d.node = SceneEmitter.nodeOf(c, null);
        d.style = new ComputedStyle(lc.style);
        d.display = Display.of(d.node, d.style).blockified(d.style);
        d.text = c.kind == BoxKind.TEXT;
        d.interactive = !d.text && d.node != null && !d.node.isPseudoNode && lc.values.interactive();
        d.lineHeight = InlineFlow.lineHeight(c.style, session.face(c.style));
        if (!d.text) {
            BoxGeometry g = new BoxGeometry(c.style, cbw, session);
            d.mT = g.mT; d.mR = g.mR; d.mB = g.mB; d.mL = g.mL;
            d.pT = g.pT; d.pR = g.pR; d.pB = g.pB; d.pL = g.pL;
            d.bT = g.bT; d.bR = g.bR; d.bB = g.bB; d.bL = g.bL;
        }
        return d;
    }

    static float[] fitInline(Draft b) {
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        for (SceneRun f : b.runs) {
            if (!f.isText()) continue;
            x0 = Math.min(x0, f.x()); y0 = Math.min(y0, f.y());
            x1 = Math.max(x1, f.x() + f.w()); y1 = Math.max(y1, f.y() + f.h());
        }
        float[] blocks = null;
        for (Child c : b.children) {
            if (c.item instanceof SceneLayer sl) {
                blocks = union(blocks, new float[]{c.x, c.y, c.x + sl.w, c.y + sl.h});
                continue;
            }
            if (!(c.item instanceof Draft cd)) continue;
            if (cd.placed) {
                blocks = union(blocks, new float[]{cd.x, cd.y, cd.x + cd.w, cd.y + cd.h});
                continue;
            }
            float[] r = fitInline(cd);
            if (r == null) continue;
            x0 = Math.min(x0, r[0]); y0 = Math.min(y0, r[1]); x1 = Math.max(x1, r[2]); y1 = Math.max(y1, r[3]);
        }
        float[] own = blocks == null ? spanBounds(b) : union(spanBounds(b), blocks);
        if (x0 > x1 && own == null) return null;
        if (own != null) {
            b.x = own[0];
            b.y = own[1];
            b.w = own[2] - own[0];
            b.h = own[3] - own[1];
            return x0 > x1 ? own : new float[]{Math.min(x0, own[0]), Math.min(y0, own[1]), Math.max(x1, own[2]), Math.max(y1, own[3])};
        }
        b.x = 0f;
        b.y = 0f;
        b.w = x1 - x0;
        b.h = y1 - y0;
        return new float[]{x0, y0, x1, y1};
    }

    static void lines(Draft block, LineFragment[] lines, float ox, float oy, Map<BoxNode, Draft> boxes,
                      Map<Object, SceneLayer> prev, BoxNode firstLineOwner, SceneEmitter emitter, LayoutSession session,
                      boolean inFlexOrGrid, boolean blocksFloat) {
        if (lines == null) return;
        if (firstLineOwner != null && lines.length > 0) {
            block.firstLine = new ComputedStyle(firstLineOwner.firstLine);
            LineFragment l0 = lines[0];
            if (l0.width() > 0 && l0.height() > 0) {
                block.firstLineBgRect = new float[]{ox + l0.x(), oy + l0.y(), l0.width(), l0.height()};
            }
        }
        for (int li = 0; li < lines.length; li++) {
            LineFragment l = lines[li];
            boolean first = li == 0 && firstLineOwner != null;
            float lineBase = l.baseline();
            for (PlacedFragment a : l.atoms()) {
                float ax = ox + l.x() + a.x(), ay = oy + l.y() + a.y();
                block.add(emitter.buildBlock(a.frag(), ax, ay, prev, false, inFlexOrGrid, blocksFloat, false), ax, ay);
            }
            for (LineFragment.Span sp : l.spans()) {
                Draft eb = boxes.get(sp.box());
                if (eb != null) {
                    eb.runs.add(new SceneRun(ox + l.x() + sp.x(), oy + l.y() + sp.y(), sp.width(), sp.height(),
                            null, sp.baseline(), sp.startEdge(), sp.endEdge()));
                }
            }
            for (LineFragment.Piece p : l.pieces()) {
                Draft tb = boxes.get(p.text());
                if (tb == null) continue;
                Style st = first ? FirstLine.of(tb.style.typed(), firstLineOwner.style, firstLineOwner.firstLine) : p.text().style;
                ComputedStyle fl = first ? new ComputedStyle(st) : null;
                FontFace face = session.face(st);
                FontStyle fs = st.facts.fontStyle;
                float runH = InlineFlow.lineHeight(st, face), runBase = InlineFlow.runBaseline(st, session);
                float fy = LayoutUnit.snap(oy + l.y() + (lineBase - runBase) + p.dy());
                String text = first
                        ? FirstLine.transform(p.text().text, p.text().style, st).substring(p.start(), p.end()) : p.string();
                float x = ox + l.x() + p.x(), end = x + p.width();
                for (int i = 0, n = text.length(); i < n; ) {
                    int stop = InlineFlow.nextBreak(text, i, n, st.facts.wordBreak);
                    String word = text.substring(i, stop);
                    float w = stop == n ? end - x : face.advance(word, fs);
                    tb.runs.add(new SceneRun(x, fy, w, runH, word, runBase, true, true, fl));
                    x += w;
                    i = stop;
                }
            }
        }
    }

    static SceneLayer freezeInline(Draft d, boolean parentInFlexOrGrid, boolean parentBlocksFloat,
                                   int vw, int vh, Map<String, Keyframes> keyframes, SceneEmitter emitter) {
        SceneChildren kids = freezeChildren(d.children, d.display, vw, vh, keyframes, emitter);
        Display.Inner inner = d.display != null ? d.display.inner() : null;
        boolean flexGrid = inner == Display.Inner.FLEX || inner == Display.Inner.GRID;
        boolean blocksFloat = inner == Display.Inner.FLEX || inner == Display.Inner.GRID || inner == Display.Inner.TABLE
                || inner == Display.Inner.TABLE_ROW_GROUP || inner == Display.Inner.TABLE_ROW;
        SceneLayerProps.Computed p = SceneLayerProps.compute(
                d.box, d.node, d.style, d.display, d.w, d.h,
                d.bT, d.bR, d.bB, d.bL, d.pT, d.pR, d.pB, d.pL, d.mT, d.mR, d.mB, d.mL,
                d.anonymous, d.verticalWrapper, d.renderedLegend, d.interactive,
                parentInFlexOrGrid, parentBlocksFloat, kids, false, vw, vh, keyframes);
        int nid = (d.box != null && d.box.generated()) || d.node == null || d.node.isPseudoNode ? 0 : d.node.nodeId();
        SceneRun[] runArr = d.runs.isEmpty() ? SceneLayer.NO_RUNS : d.runs.toArray(SceneLayer.NO_RUNS);
        emitter.countBuilt();
        return emitter.withOps(new SceneLayer(nid, d.x, d.y, d.w, d.h, p.scrollClipW(), p.scrollClipH(), p.transform(), p.clip(),
                p.effects(), p.sticky(), DrawOps.EMPTY, kids, d.box, null, d.node, d.style, d.display,
                d.bT, d.bR, d.bB, d.bL, d.pT, d.pR, d.pB, d.pL, d.mT, d.mR, d.mB, d.mL,
                d.collapsedBorders, d.scrollWidth, d.scrollHeight, d.lineHeight, d.firstLine, d.firstLineBgRect,
                runArr, null, p.kind(), p.zIndex(), p.layerFlags(), p.propagatedLayers()));
    }

    static SceneChildren freezeChildren(List<Child> children, Display parentDisplay, int vw, int vh,
                                        Map<String, Keyframes> keyframes, SceneEmitter emitter) {
        if (children.isEmpty()) return SceneChildren.EMPTY;
        Display.Inner inner = parentDisplay != null ? parentDisplay.inner() : null;
        boolean flexGrid = inner == Display.Inner.FLEX || inner == Display.Inner.GRID;
        boolean blocksFloat = inner == Display.Inner.FLEX || inner == Display.Inner.GRID || inner == Display.Inner.TABLE
                || inner == Display.Inner.TABLE_ROW_GROUP || inner == Display.Inner.TABLE_ROW;
        int n = children.size();
        SceneLayer[] arr = new SceneLayer[n];
        for (int i = 0; i < n; i++) {
            Child c = children.get(i);
            arr[i] = c.item instanceof SceneLayer sl ? sl
                    : freezeInline((Draft) c.item, flexGrid, blocksFloat, vw, vh, keyframes, emitter);
        }
        return chunkSingleArray(arr);
    }

    static SceneChildren chunkSingleArray(SceneLayer[] arr) {
        int n = arr.length;
        if (n == 0) return SceneChildren.EMPTY;
        if (n <= 32) {
            return SceneChildren.of(new SceneChunk[]{SceneChunk.of(null, arr)}, new float[]{0f}, new float[]{0f});
        }
        int groups = (n + 31) / 32;
        SceneChunk[] chunks = new SceneChunk[groups];
        float[] cx = new float[groups];
        float[] cy = new float[groups];
        for (int g = 0; g < groups; g++) {
            int from = g * 32, to = Math.min(n, from + 32);
            SceneLayer[] slice = new SceneLayer[to - from];
            System.arraycopy(arr, from, slice, 0, slice.length);
            chunks[g] = SceneChunk.of(null, slice);
        }
        return SceneChildren.of(chunks, cx, cy);
    }

    static java.util.Set<BoxNode> directChildren(BoxNode owner) {
        java.util.Set<BoxNode> out = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        out.addAll(owner.flow.toList());
        return out;
    }

    private static boolean outOfFlow(BoxNode b) {
        if (b.kind == BoxKind.NONE || b.kind == BoxKind.TEXT) return false;
        Position p = b.style.box.position;
        return p == Position.ABSOLUTE || p == Position.FIXED;
    }

    private static float[] union(float[] a, float[] b) {
        if (a == null) return b;
        return new float[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    private static float[] spanBounds(Draft b) {
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        SceneRun first = null;
        for (SceneRun f : b.runs) {
            if (f.isText()) continue;
            if (first == null) first = f;
            if (f.w() <= 0 || f.h() <= 0) continue;
            x0 = Math.min(x0, f.x()); y0 = Math.min(y0, f.y());
            x1 = Math.max(x1, f.x() + f.w()); y1 = Math.max(y1, f.y() + f.h());
        }
        if (x0 <= x1) return new float[]{x0, y0, x1, y1};
        return first == null ? null : new float[]{first.x(), first.y(), first.x() + first.w(), first.y() + first.h()};
    }
}
