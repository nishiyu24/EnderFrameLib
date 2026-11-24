package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.kernel.CompactList;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutOrigin;
import com.nishiyu.enderframelib.core.layout.PaintStamps;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.RandomAccess;

/**
 * 表示リストの組み立て（C1・C1p。設計 {@code core/DESIGN_INCREMENTAL_KERNEL.md} 付録 A）。箱を再帰してその場で箱の項目を描き、
 * 種類（{@link Fragment.Kind}）ごとに層（付録 E）へ組み立てて 1 本の列に展開する。経路はこの 1 本で、増分の更新（パッチ）は持たない。
 */
final class FragmentPaint {
    /**
     * 断片と表示項目の再利用キーおよび帳簿（キー欄は {@link com.nishiyu.enderframelib.core.layout.PaintBoxInputs#ALL} を
     * 唯一の宣言源とし、{@code PaintReadSetTest} が全欄を機械照合する）。
     */
    private static final class Memo {
        Fragment frag;
        LayoutBox box, parent;
        float x, y, w, h, scrollW, scrollH, f0x, f0y;
        ClipRect clip, childClip;
        String parentId, childParentId;
        int pox, poy, pass, owners, kidCount, childPox, childPoy, kidsOwners, treeAttrVer, vpW, vpH, attrVersion;
        long epoch;
        boolean moving, childContextSet, selfVpSensitive, subtreeVpSensitive;
        boolean allKidsMemoized, hasFirstLine, kidsHaveScrolls, kidsSkipped;
        boolean canvasSource, interactive, suppressShadow, oneNode, flexOrGridItem, flexOrGridContainer;
        LayoutBox[] kids;
        Memo[] kidMemos;
        int[] kidIndex;
        PaintOrder.ScrollContent[] scrolls, ownScrolls;
        Object flStyle, flPseudo, flFrag, placeholder;
        /** K4g: 窓の外の代理の状態（{@link InkState}。状態機械）。 */
        InkState ink = InkState.NOT_EMPTY;
    }

    private static final class DirtyBuf {
        final LayoutBox[] boxes = new LayoutBox[PaintStamps.DIRTY_CAP];
        final int[] pos = new int[PaintStamps.DIRTY_CAP], fragIdx = new int[PaintStamps.DIRTY_CAP];
        final Fragment[] oldF = new Fragment[PaintStamps.DIRTY_CAP], newF = new Fragment[PaintStamps.DIRTY_CAP];
        final Memo[] newM = new Memo[PaintStamps.DIRTY_CAP];
    }

    private static final class FrameScratch {
        final PaintOrder.ChildContext ctx = new PaintOrder.ChildContext();
        Fragment[] kids;
        Memo[] memos;
        int[] idx;
        DirtyBuf dirty;

        Fragment[] kids(int n) {
            if (kids == null || kids.length < n) kids = new Fragment[Math.max(n, 16)];
            return kids;
        }
        Memo[] memos(int n) {
            if (memos == null || memos.length < n) memos = new Memo[Math.max(n, 16)];
            return memos;
        }
        int[] idx(int n) {
            if (idx == null || idx.length < n) idx = new int[Math.max(n, 64)];
            return idx;
        }
        DirtyBuf dirty() { return dirty != null ? dirty : (dirty = new DirtyBuf()); }
    }

    private static final class ArrayView extends AbstractList<DisplayItem> implements RandomAccess {
        DisplayItem[] a;
        @Override public DisplayItem get(int i) { return a[i]; }
        @Override public int size() { return a.length; }
    }

    private static final LayoutBox[] NO_BOXES = new LayoutBox[0];
    private static final Memo[] NO_MEMOS = new Memo[0];
    private static final PaintOrder.ScrollContent[] NO_SCROLLS = new PaintOrder.ScrollContent[0];
    private static final Fragment[] NO_FRAGMENTS = new Fragment[0];

    static final java.util.concurrent.atomic.LongAdder MEMO_HITS = new java.util.concurrent.atomic.LongAdder(),
            OWN_REUSED = new java.util.concurrent.atomic.LongAdder(),
            MEMO_MISSES = new java.util.concurrent.atomic.LongAdder();

    private final java.util.IdentityHashMap<LayoutBox, Memo> memos = new java.util.IdentityHashMap<>();
    private final PaintDescent stack = new PaintDescent();
    private final java.util.Set<LayoutBox> tainted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final DisplayListBuilder b;
    private final PaintOrder order;
    private final PaintSupport.CaptureList scratch = new PaintSupport.CaptureList(64);
    private final ArrayView prevOwnView = new ArrayView();
    private FrameScratch[] frames = new FrameScratch[16];
    private java.util.function.ObjIntConsumer<LayoutBox> pathRecorder;
    static final int PATH_HIT = 0, PATH_OWN = 1, PATH_REBUILD = 2;

    private int frameKey, passNo, owners;
    private boolean cull;
    private Memo lastBuiltMemo;
    private PaintOrder.ScrollContent[] lastOwnScrolls = NO_SCROLLS;
    private ClipRect lastChildClip;
    private String lastChildParentId;
    private int lastChildPox, lastChildPoy, lastKidListSize, lastKidsOwners, lastDirtyCount, lastDepth;
    private boolean lastOwnKept, lastSelfVp, lastSubtreeVp, lastChildContextSet;
    private boolean lastAllKidsMemo, lastHasFirstLine, lastKidsHaveScrolls, lastKidsSkipped;
    private boolean lastWasPillar2, lastHasBuiltMemos;
    private int[] lastKidIndex;

    FragmentPaint(DisplayListBuilder b, PaintOrder order) { this.b = b; this.order = order; }
    void setPathRecorder(java.util.function.ObjIntConsumer<LayoutBox> r) { this.pathRecorder = r; }
    int lastOwners() { return owners; }

    private FrameScratch frameAt(int depth) {
        if (depth >= frames.length) frames = Arrays.copyOf(frames, Math.max(depth + 1, frames.length * 2));
        FrameScratch fs = frames[depth];
        return fs != null ? fs : (frames[depth] = new FrameScratch());
    }

    int paintInto(LayoutBox root, int base, boolean cull, boolean windowed) {
        if (pathRecorder != null) pathRecorder.accept(null, -1);
        Fragment top = buildTop(root, cull, windowed);
        int count = top.size(), total = base + count;
        DisplayItem[] buf = b.ensureFlatBuf(total);
        if (count > 0) top.emitted.copyTo(buf, base);
        return total;
    }

    /** 箱を描く順（付録 E）に並べた持ち主。 */
    List<LayoutBox> paintOrder(LayoutBox root) {
        List<LayoutBox> out = new ArrayList<>();
        Fragment top = buildTop(root, false, false);
        top.emitted.copyTo(new DisplayItem[top.size()], 0, (box, s, e) -> out.add(box));
        return out;
    }

    private Fragment buildTop(LayoutBox root, boolean cull, boolean windowed) {
        this.cull = cull;
        this.owners = 0;
        passNo++;
        LayoutBox.noteRendered(b.buildEpoch);
        if (passNo % 64 == 0) {
            int cutoff = passNo - 64;
            for (Memo pm : memos.values()) {
                if (pm.kidsSkipped && pm.pass >= cutoff) refreshKidPasses(pm, pm.pass);
            }
            memos.values().removeIf(x -> x.pass < cutoff);
        }
        int key = b.memoFrameKey(cull, windowed);
        if (key != frameKey || key == Integer.MIN_VALUE) memos.clear();
        frameKey = key;
        stack.clear();
        tainted.clear();
        order.beginPass();
        boolean rootMoving = root.style != null && PaintOrder.factsOf(root.style).hasMovingProps();
        if ("root".equals(root.node.tag)) {
            stack.pushRoot(root);
            try {
                FrameScratch fs = frameAt(0);
                PaintOrder.ChildContext cc = order.enterChildren(root, null, null, fs.ctx);
                int n = root.children.size();
                Fragment[] tmp = fs.kids(n);
                int c = 0;
                for (int i = 0; i < n; i++) {
                    Fragment f = kid(root.children.get(i), cc, rootMoving, null, false);
                    if (f != null) tmp[c++] = f;
                }
                Fragment[] small = c < 64 ? (c == 0 ? NO_FRAGMENTS : Arrays.copyOf(tmp, c)) : null;
                Fragment[][] big = c >= 64 ? Fragment.chunkifyFrom(null, tmp, c) : null;
                Arrays.fill(tmp, 0, c, null);
                FragSeq[] agg = small != null ? Fragment.aggregate(small, c) : Fragment.aggregateDiff(null, null, big, c);
                return new Fragment(root, Fragment.NONE, Fragment.NONE, false, Fragment.Kind.ROOT, 0, null, null, null, c, small, big, agg);
            } finally {
                stack.clear();
            }
        }
        stack.ax = root.x(); stack.ay = root.y();
        Fragment f = buildFresh(root, null, null, rootMoving, null, false);
        return new Fragment(root, f.own, f.ownAfter, f.drawn, Fragment.Kind.ROOT, 0, null, null, null, f.kidCount, f.smallKids, f.bigKids, f.agg);
    }

    private static void refreshKidPasses(Memo parent, int p) {
        Memo[] km = parent.kidMemos;
        if (km == null) return;
        for (int i = 0, n = parent.kidCount; i < n; i++) {
            Memo cm = km[i];
            if (cm != null && cm.pass < p) {
                cm.pass = p;
                if (cm.kidCount > 0) refreshKidPasses(cm, p);
            }
        }
    }

    private Fragment.Extras extras(LayoutBox box, Fragment.Kind kind, LayerSpec layer) {
        DisplayItem[] mask = null, backdrop = null;
        boolean hoist = kind == Fragment.Kind.STACKING && order.isTopLayerBox(box);
        if (layer != null && layer.wantsMask()) {
            scratch.resetForBuild();
            b.drawMask(scratch, box);
            mask = scratch.toArray(new DisplayItem[0]);
        }
        if (hoist && box.backdropStyle != null) {
            scratch.resetForBuild();
            order.drawBackdrop(scratch, box);
            backdrop = scratch.toArray(new DisplayItem[0]);
        }
        return mask == null && !hoist ? null : new Fragment.Extras(mask, hoist, backdrop);
    }

    private Fragment kid(LayoutBox child, PaintOrder.ChildContext cc, boolean moving, Memo hint, boolean parentCtxOk) {
        if (child.isAbsolute()) taintAboveContainingBlock(child);
        return build(child, order.clipForChild(child, cc.clip), cc.parentId, moving, hint, parentCtxOk);
    }

    private Fragment build(LayoutBox box, ClipRect clip, String parentId, boolean ancestorMoving, Memo hint, boolean parentCtxOk) {
        if (box.display().isNone() || PaintOrder.factsOf(box.style).displayNone() || ElementTraits.isOption(box.node.tag)) {
            lastBuiltMemo = null;
            return null;
        }
        boolean moving = ancestorMoving || (box.style != null && PaintOrder.factsOf(box.style).hasMovingProps());
        Memo m = hint != null && hint.box == box ? hint : memos.get(box);
        boolean sameKidList = false;
        float ox = stack.originX(box), oy = stack.originY(box);
        float ax = LayoutOrigin.resolvesLocally(box) ? LayoutOrigin.relX(box) : ox + LayoutOrigin.relX(box), ay = LayoutOrigin.resolvesLocally(box) ? LayoutOrigin.relY(box) : oy + LayoutOrigin.relY(box);
        if (m != null && (parentCtxOk ? sameContextCheap(m, box, clip, parentId, moving, ax, ay) : sameContextHeader(m, box, clip, parentId, moving, ax, ay))) {
            boolean vpOk = !m.subtreeVpSensitive || (m.vpW == b.rootW && m.vpH == b.rootH);
            if (subtreeKeySame(m, box) && vpOk && (parentCtxOk || sameKids(m, box.children))) {
                if (pathRecorder != null) pathRecorder.accept(box, PATH_HIT);
                lastBuiltMemo = m;
                return reuse(m);
            }
            boolean ownCtxOk = !parentCtxOk || box.ownPaintStamp() <= m.epoch;
            sameKidList = ownCtxOk && sameKids(m, box.children);
        }
        MEMO_MISSES.increment();
        int owners0 = owners, scroll0 = order.scrollContentCount();
        stack.push(box, ax, ay, ox, oy);
        try {
            Fragment f = buildFresh(box, clip, parentId, moving, m, sameKidList);
            if (pathRecorder != null) pathRecorder.accept(box, lastOwnKept ? PATH_OWN : PATH_REBUILD);
            if (!tainted.contains(box)) {
                lastBuiltMemo = store(box, m, f, clip, parentId, moving, owners - owners0, scroll0, ax, ay);
            } else {
                memos.remove(box);
                lastBuiltMemo = null;
            }
            return f;
        } finally {
            FrameScratch fs = lastDepth < frames.length ? frames[lastDepth] : null;
            if (fs != null) {
                if (lastHasBuiltMemos && fs.memos != null) {
                    Arrays.fill(fs.memos, 0, Math.min(lastKidListSize, fs.memos.length), null);
                }
                if (lastWasPillar2 && fs.dirty != null) {
                    int end = Math.min(lastDirtyCount, fs.dirty.boxes.length);
                    Arrays.fill(fs.dirty.boxes, 0, end, null);
                    Arrays.fill(fs.dirty.oldF, 0, end, null);
                    Arrays.fill(fs.dirty.newF, 0, end, null);
                    Arrays.fill(fs.dirty.newM, 0, end, null);
                }
            }
            lastHasBuiltMemos = false;
            lastWasPillar2 = false;
            stack.pop();
        }
    }

    /** 部分木の鍵が記憶と同じ（K4g: 部分木の印が古い、または新しいのが窓の外で空の部分木の自身の平行移動だけ）。再利用の判定の 1 つの言語。 */
    private static boolean subtreeKeySame(Memo m, LayoutBox box) {
        return box.subtreePaintStamp() <= m.epoch || m.ink.canTranslate() && InkState.quiet(box, m.epoch) && sameKids(m, box.children);
    }

    private boolean sameContextCheap(Memo m, LayoutBox box, ClipRect clip, String parentId, boolean moving, float ax, float ay) {
        if (m.parent != box.parent || m.clip != clip || m.moving != moving
                || m.flexOrGridItem != PaintSupport.isFlexOrGridItem(box)
                || !java.util.Objects.equals(m.parentId, parentId)) return false;
        if (m.attrVersion != box.node.attrVersion() || !sameFirstLine(m, box)) return false;
        if (Float.compare(m.w, box.w()) != 0 || Float.compare(m.h, box.h()) != 0) return false;
        if ((Float.compare(m.x, ax) != 0 || Float.compare(m.y, ay) != 0) && !translatedOutsideWindow(m, box, ax, ay)) return false;
        return scrollSame(m);
    }

    /** K4: 断片が空の部分木が平行移動しただけで、移動後も全ての箱が窓の外（空のまま）。窓の外の部分木の O(1) の代理（状態は {@link InkState}）。 */
    private boolean translatedOutsideWindow(Memo m, LayoutBox box, float ax, float ay) {
        if (m.scrolls.length != 0 || !m.ink.canTranslate() || !InkState.quiet(box, m.epoch) || !sameKids(m, box.children)) return false;
        m.ink = m.ink.resolved(box);
        return m.ink.outside(ax, ay, b.rootW, b.rootH, DisplayListBuilder.CULL_MARGIN);
    }

    private static boolean sameFirstLine(Memo m, LayoutBox box) {
        LayoutBox owner = box.inlineFragments.isEmpty() ? null : box.inlineFragments.get(0).firstLineOwner;
        Object own = box.inlineFragments.isEmpty() ? null : box.inlineFragments.get(0).firstLineStyle;
        if (m.flFrag != own) return false;
        return owner == null ? m.flStyle == null : m.flStyle == owner.style && m.flPseudo == owner.firstLineStyle;
    }

    private boolean sameContextHeader(Memo m, LayoutBox box, ClipRect clip, String parentId, boolean moving, float ax, float ay) {
        if (!sameContextCheap(m, box, clip, parentId, moving, ax, ay) || box.ownPaintStamp() > m.epoch) return false;
        if (b.parentRelative) {
            LayoutBox p = order.effectiveParentNodeBox(box, parentId);
            if ((p != null ? Math.round(p.x()) : 0) != m.pox || (p != null ? Math.round(p.y()) : 0) != m.poy) return false;
        }
        return true;
    }

    private boolean scrollSame(Memo m) {
        for (PaintOrder.ScrollContent c : m.scrolls) {
            if (Float.compare(c.sx0, b.scrollState.getScrollX(c.box.node)) != 0
                    || Float.compare(c.sy0, b.scrollState.getScrollY(c.box.node)) != 0) return false;
        }
        return true;
    }

    private Fragment reuse(Memo m) {
        MEMO_HITS.increment();
        m.pass = passNo;
        m.kidsSkipped = true;
        owners += m.owners;
        for (PaintOrder.ScrollContent c : m.scrolls) order.reregister(c);
        return m.frag;
    }

    private Memo store(LayoutBox box, Memo existing, Fragment f, ClipRect clip, String parentId, boolean moving, int ownersHere, int scroll0, float ax, float ay) {
        Memo m = existing != null ? existing : memos.get(box);
        if (m == null) memos.put(box, m = new Memo());
        m.frag = f; m.box = box; m.parent = box.parent;
        m.x = ax; m.y = ay; m.w = box.w(); m.h = box.h();
        m.clip = clip; m.parentId = parentId; m.moving = moving;
        LayoutBox p = b.parentRelative ? order.effectiveParentNodeBox(box, parentId) : null;
        m.pox = p != null ? Math.round(p.x()) : 0; m.poy = p != null ? Math.round(p.y()) : 0;
        m.pass = passNo; m.epoch = b.buildEpoch;
        m.childContextSet = lastChildContextSet; m.childClip = lastChildClip; m.childParentId = lastChildParentId;
        m.childPox = lastChildPox; m.childPoy = lastChildPoy; m.kidsOwners = lastKidsOwners;
        m.treeAttrVer = stack.treeAttrVer(box);
        m.vpW = b.rootW; m.vpH = b.rootH;
        m.selfVpSensitive = lastSelfVp; m.subtreeVpSensitive = lastSubtreeVp;
        m.allKidsMemoized = lastAllKidsMemo; m.hasFirstLine = lastHasFirstLine;
        m.kidsHaveScrolls = lastKidsHaveScrolls; m.kidsSkipped = lastKidsSkipped;

        int n = box.children.size();
        if (lastWasPillar2) {
            DirtyBuf db = frames[lastDepth].dirty;
            int dc = lastDirtyCount;
            if (dc > 0) {
                for (int k = 0; k < dc; k++) m.kidMemos[db.pos[k]] = db.newM[k];
                Arrays.fill(db.newM, 0, dc, null);
                Arrays.fill(db.oldF, 0, dc, null);
                Arrays.fill(db.newF, 0, dc, null);
            }
            Arrays.fill(db.boxes, 0, dc, null);
            lastWasPillar2 = false;
        } else {
            updateKidArrays(m, box.children, n);
        }

        m.owners = ownersHere; m.ownScrolls = lastOwnScrolls; m.attrVersion = box.node.attrVersion();
        m.scrollW = box.scrollWidth(); m.scrollH = box.scrollHeight();
        float fx = 0f, fy = 0f;
        if (!box.inlineFragments.isEmpty()) {
            LayoutBox.InlineFragment f0 = box.inlineFragments.get(0);
            fx = f0.x; fy = f0.y;
        }
        m.f0x = fx; m.f0y = fy;
        m.canvasSource = box == b.canvasBackgroundSource; m.interactive = box.hasInteractiveRules;
        m.suppressShadow = b.suppressTextShadow; m.oneNode = order.scrollContentOf(box) != null;
        m.flexOrGridItem = PaintSupport.isFlexOrGridItem(box);
        m.flexOrGridContainer = isFlexOrGridContainer(box);
        LayoutBox flOwner = box.inlineFragments.isEmpty() ? null : box.inlineFragments.get(0).firstLineOwner;
        m.flStyle = flOwner == null ? null : flOwner.style;
        m.flPseudo = flOwner == null ? null : flOwner.firstLineStyle;
        m.flFrag = box.inlineFragments.isEmpty() ? null : box.inlineFragments.get(0).firstLineStyle;
        m.placeholder = box.placeholderStyle;
        m.scrolls = order.scrollContentCount() == scroll0 ? NO_SCROLLS : order.scrollContentsSince(scroll0).toArray(NO_SCROLLS);
        m.ink = InkState.onStore(f == Fragment.EMPTY, cull, ownersHere);
        return m;
    }

    private void updateKidArrays(Memo m, List<LayoutBox> ch, int n) {
        if (n == 0) {
            if (m.kidCount > 0 && m.kids != null) {
                Arrays.fill(m.kids, 0, m.kidCount, null);
                Arrays.fill(m.kidMemos, 0, m.kidCount, null);
            }
            if (m.kids == null) { m.kids = NO_BOXES; m.kidMemos = NO_MEMOS; }
            m.kidCount = 0; m.kidIndex = null;
            return;
        }
        Memo[] builtMemos = lastHasBuiltMemos ? frames[lastDepth].memos : null;
        if (m.kids != null && m.kidCount == n && sameKids(m, ch)) {
            if (builtMemos != null) System.arraycopy(builtMemos, 0, m.kidMemos, 0, n);
            else Arrays.fill(m.kidMemos, 0, n, null);
        } else if (m.kids != null && m.kids.length >= n) {
            for (int i = 0; i < n; i++) {
                m.kids[i] = ch.get(i);
                m.kidMemos[i] = builtMemos != null ? builtMemos[i] : null;
            }
            if (m.kidCount > n) {
                Arrays.fill(m.kids, n, m.kidCount, null);
                Arrays.fill(m.kidMemos, n, m.kidCount, null);
            }
            m.kidCount = n;
        } else {
            int cap = m.kids == null && n < PaintStamps.DIRTY_MIN_KIDS ? n : n + Math.min(128, Math.max(8, n >> 4));
            LayoutBox[] nb = new LayoutBox[cap];
            Memo[] nm = new Memo[cap];
            for (int i = 0; i < n; i++) {
                nb[i] = ch.get(i);
                nm[i] = builtMemos != null ? builtMemos[i] : null;
            }
            m.kids = nb; m.kidMemos = nm; m.kidCount = n;
        }
        if (builtMemos != null) Arrays.fill(builtMemos, 0, n, null);
        lastHasBuiltMemos = false;
        if (lastKidIndex != null) {
            if (m.kidIndex == null || m.kidIndex.length < n) m.kidIndex = new int[m.kids.length];
            System.arraycopy(lastKidIndex, 0, m.kidIndex, 0, n);
        } else {
            m.kidIndex = null;
        }
    }

    private static boolean sameKids(Memo m, List<LayoutBox> ch) {
        return CompactList.sameElements(ch, m.kids, m.kidCount);
    }

    private void taintAboveContainingBlock(LayoutBox abs) {
        LayoutBox cb = abs.findContainingBlock();
        if (cb == null) return;
        for (int i = stack.depth() - 1; i >= 0; i--) {
            LayoutBox s = stack.at(i);
            if (s == cb) break;
            tainted.add(s);
        }
    }

    private boolean ownReusable(Memo m, LayoutBox box) {
        if (box.ownPaintStamp() > m.epoch) return false;
        if (m.selfVpSensitive && (m.vpW != b.rootW || m.vpH != b.rootH)) return false;
        if (!DisplayListBuilder.memoizable(box) || m.attrVersion != box.node.attrVersion()) return false;
        if (Float.compare(m.scrollW, box.scrollWidth()) != 0 || Float.compare(m.scrollH, box.scrollHeight()) != 0) return false;
        float fx = 0f, fy = 0f;
        if (!box.inlineFragments.isEmpty()) { LayoutBox.InlineFragment f0 = box.inlineFragments.get(0); fx = f0.x; fy = f0.y; }
        return Float.compare(m.f0x, fx) == 0 && Float.compare(m.f0y, fy) == 0
                && m.canvasSource == (box == b.canvasBackgroundSource) && m.interactive == box.hasInteractiveRules
                && m.suppressShadow == b.suppressTextShadow && m.placeholder == box.placeholderStyle
                && m.oneNode == (order.scrollContentOf(box) != null);
    }

    private LayoutBox childParentNodeBox(LayoutBox box, String childParentId) {
        if (childParentId == null || "root".equals(box.node.tag)) return null;
        if (!box.isAnonymous) return PaintOrder.boxHasOwnNode(box) && !order.isCulled(box) ? box : null;
        for (LayoutBox a = box.parent; a != null; a = a.parent) {
            if (a.node != null && "root".equals(a.node.tag)) return null;
            if (a.isAnonymous) continue;
            return PaintOrder.boxHasOwnNode(a) && !order.isCulled(a) ? a : null;
        }
        return null;
    }

    private boolean selfViewportSensitive(LayoutBox box, Fragment.Extras ex, PaintOrder.ScrollContent ownScroll) {
        if (box.style != null && (box.style.usesViewportUnits() || PaintOrder.factsOf(box.style).hasAnimation())) return true;
        if (box.axisStyle() != null && box.axisStyle() != box.style && box.axisStyle().usesViewportUnits()) return true;
        if (box.backdropStyle != null || (ex != null && ex.backdrop() != null)) return true;
        if (box.placeholderStyle != null && box.placeholderStyle.usesViewportUnits()) return true;
        if (box.firstLineStyle != null && box.firstLineStyle.usesViewportUnits()) return true;
        if (!box.inlineFragments.isEmpty()) {
            var fl = box.inlineFragments.get(0).effectiveFirstLineStyle(box);
            if (fl != null && fl.usesViewportUnits()) return true;
        }
        if (box.isSticky() || (ownScroll != null && !ownScroll.stickies.isEmpty())) return true;
        return ElementTraits.isImage(box.node.tag) && !DisplayListBuilder.memoizable(box);
    }

    private static boolean isFlexOrGridContainer(LayoutBox box) {
        LayoutBox.Display d = box.display();
        return d != null && (d.inner() == LayoutBox.Display.Inner.FLEX || d.inner() == LayoutBox.Display.Inner.GRID);
    }

    private Fragment buildFresh(LayoutBox box, ClipRect clip, String parentId, boolean moving, Memo m, boolean sameKidList) {
        Fragment.Kind kind = Fragment.kindOf(box, order);
        boolean hasSelf = PaintOrder.hasSelf(box);
        LayerSpec layer = kind == Fragment.Kind.STACKING && hasSelf ? order.layerSpecFor(box) : null;
        boolean valueControl = ElementTraits.isValueControl(box.node.tag);
        int depth = stack.depth();
        FrameScratch fs = frameAt(depth);
        PaintOrder.ChildContext cc = valueControl ? null : order.enterChildren(box, clip, parentId, fs.ctx);
        PaintOrder.ScrollContent ownScroll = valueControl ? null : order.scrollContentOf(box);

        DisplayItem[] own = Fragment.NONE;
        boolean drawn = false;
        boolean culledSelf = false;
        PaintOrder.ScrollContent[] ownScrolls = NO_SCROLLS;
        boolean ownKept = sameKidList && !hasSelf;
        if (hasSelf) {
            owners++;
            if (cull && layer == null && !moving && !b.inWindowGeometry(box, stack.ax, stack.ay)) {
                order.pushCulled(box);
                culledSelf = true;
                ownKept = sameKidList;
            } else {
                if (sameKidList && m != null && m.frag.drawn && ownReusable(m, box)) {
                    OWN_REUSED.increment();
                    ownKept = true;
                    own = m.frag.own; ownScrolls = m.ownScrolls;
                    for (PaintOrder.ScrollContent c : ownScrolls) order.reregister(c);
                } else {
                    int scroll0 = order.scrollContentCount();
                    scratch.resetForBuild();
                    List<DisplayItem> prevOwn = null;
                    if (m != null && m.frag != null && m.frag.drawn && m.frag.own.length > 0) {
                        prevOwnView.a = m.frag.own;
                        prevOwn = prevOwnView;
                    }
                    try { order.drawSelf(scratch, box, clip, parentId, prevOwn); } finally { prevOwnView.a = null; }
                    own = scratch.toArray(new DisplayItem[0]);
                    ownScrolls = order.scrollContentCount() == scroll0 ? NO_SCROLLS : order.scrollContentsSince(scroll0).toArray(NO_SCROLLS);
                }
                drawn = true;
            }
        }

        try {
            int childPox = 0, childPoy = 0;
            boolean kidsCtxOk = false;
            if (!valueControl) {
                if (b.parentRelative) {
                    LayoutBox cp = childParentNodeBox(box, cc.parentId);
                    if (cp != null) { childPox = Math.round(cp.x()); childPoy = Math.round(cp.y()); }
                }
                kidsCtxOk = m != null && m.frag != null && m.childContextSet && m.moving == moving
                        && m.flexOrGridContainer == isFlexOrGridContainer(box)
                        && m.childClip == cc.clip && java.util.Objects.equals(m.childParentId, cc.parentId)
                        && (!b.parentRelative || (m.childPox == childPox && m.childPoy == childPoy))
                        && Float.compare(m.x, stack.ax) == 0 && Float.compare(m.y, stack.ay) == 0;
            }

            Fragment.Extras ex = extras(box, kind, layer);
            boolean selfVp = selfViewportSensitive(box, ex, ownScroll);
            DisplayItem[] after = Fragment.NONE;
            if (!valueControl && PaintOrder.wantsScrollbars(box)) {
                scratch.resetForBuild();
                order.drawScrollbarsIfNeeded(scratch, box, clip);
                after = scratch.toArray(new DisplayItem[0]);
            }
            int z = kind == Fragment.Kind.STACKING ? (PaintOrder.isZIndexAuto(box) ? 0 : box.zIndex()) : 0;
            Fragment result = buildKidsAndFinish(box, cc, moving, m, sameKidList, kidsCtxOk, valueControl,
                    own, after, drawn, kind, z, layer, ex, selfVp, fs);

            lastOwnScrolls = ownScrolls; lastOwnKept = ownKept; lastSelfVp = selfVp;
            lastChildContextSet = !valueControl;
            lastChildClip = valueControl ? null : cc.clip; lastChildParentId = valueControl ? null : cc.parentId;
            lastChildPox = childPox; lastChildPoy = childPoy; lastDepth = depth;
            return result;
        } finally {
            if (culledSelf) order.popCulled();
        }
    }

    private Fragment buildKidsAndFinish(LayoutBox box, PaintOrder.ChildContext cc, boolean moving, Memo m,
                                        boolean sameKidList, boolean kidsCtxOk, boolean valueControl,
                                        DisplayItem[] own, DisplayItem[] after, boolean drawn, Fragment.Kind kind,
                                        int z, LayerSpec layer, Fragment.Extras ex, boolean selfVp, FrameScratch fs) {
        if (valueControl || box.contentAutoSkipped) {
            lastSubtreeVp = selfVp; lastKidListSize = 0; lastKidsOwners = 0; lastAllKidsMemo = true;
            lastHasFirstLine = box.firstLineStyle != null; lastKidsHaveScrolls = false;
            lastKidsSkipped = false; lastKidIndex = null; lastWasPillar2 = false; lastHasBuiltMemos = false;
            if (!drawn && after.length == 0 && ex == null) return Fragment.EMPTY;
            return new Fragment(box, own, after, drawn, kind, z, layer, DisplayListBuilder::withLayer, ex, 0, NO_FRAGMENTS, null, Fragment.NO_KIDS);
        }

        List<LayoutBox> ch = box.children;
        int n = ch.size();
        var vf = box.virtualFlowState();

        if (n >= PaintStamps.DIRTY_MIN_KIDS && vf == null && m != null && m.frag != null && m.frag.kidCount >= 64
                && kidsCtxOk && m.allKidsMemoized && !m.hasFirstLine && box.firstLineStyle == null
                && !m.kidsHaveScrolls && m.kidIndex != null
                && !(m.subtreeVpSensitive && (m.vpW != b.rootW || m.vpH != b.rootH))
                && stack.treeAttrVer(box) == m.treeAttrVer
                && sameKids(m, ch)) {
            DirtyBuf db = fs.dirty();
            int dc = PaintStamps.copyDirtyKidsSince(box, m.epoch, db.boxes);
            Fragment p2 = dc >= 0 ? tryPillar2(box, cc, moving, m, n, db, dc, fs, own, after, drawn, kind, z, layer, ex, selfVp) : null;
            if (p2 != null) return p2;
        }

        Fragment[] tmp = fs.kids(n);
        int[] kIdx = vf == null && n >= PaintStamps.DIRTY_MIN_KIDS ? fs.idx(n) : null;
        int c = 0, kidsOwnersSum = 0;
        boolean allMemo = true, hasFl = box.firstLineStyle != null, kidsScrolls = false, subVp = selfVp, hasBuiltMemos = false;

        if (vf == null) {
            Memo[] builtMemos = n > 0 ? fs.memos(n) : null;
            hasBuiltMemos = n > 0;
            int pre = 0, suf = 0, oldN = 0;
            Memo[] oldKm = null;
            if (m != null && m.kids != null) {
                oldN = m.kidCount; oldKm = m.kidMemos;
                if (sameKidList || (m.kidCount == n && sameKids(m, ch))) pre = n;
                else {
                    int minN = Math.min(oldN, n);
                    while (pre < minN && m.kids[pre] == ch.get(pre)) pre++;
                    while (suf < minN - pre && m.kids[oldN - 1 - suf] == ch.get(n - 1 - suf)) suf++;
                }
            }
            for (int i = 0; i < n; i++) {
                Memo hint = oldKm == null ? null : (i < pre ? oldKm[i] : (i >= n - suf ? oldKm[oldN - (n - i)] : null));
                int oBefore = owners;
                Fragment f = kid(ch.get(i), cc, moving, hint, kidsCtxOk);
                Memo cm = f != null ? lastBuiltMemo : null;
                builtMemos[i] = cm;
                if (f != null) {
                    if (kIdx != null) kIdx[i] = c;
                    tmp[c++] = f;
                    if (cm == null) { allMemo = false; kidsOwnersSum += owners - oBefore; }
                    else {
                        kidsOwnersSum += cm.owners;
                        if (cm.scrolls.length > 0) kidsScrolls = true;
                        if (cm.subtreeVpSensitive) subVp = true;
                        if (cm.flStyle != null || cm.flFrag != null) hasFl = true;
                    }
                } else if (kIdx != null) kIdx[i] = -1;
            }
        } else {
            allMemo = false;
            int start = vf.placedRangeStart(), end = vf.placedRangeEnd();
            if (start >= 0 && end >= start) {
                for (int i = start, limit = Math.min(end, n - 1); i <= limit; i++) {
                    int oBefore = owners;
                    Fragment f = kid(ch.get(i), cc, moving, null, false);
                    if (f != null) {
                        tmp[c++] = f;
                        kidsOwnersSum += lastBuiltMemo != null ? lastBuiltMemo.owners : owners - oBefore;
                        if (lastBuiltMemo != null && lastBuiltMemo.subtreeVpSensitive) subVp = true;
                        if (lastBuiltMemo != null && lastBuiltMemo.scrolls.length > 0) kidsScrolls = true;
                    }
                }
            }
            for (int k = 0, vm = vf.extraPlacedCount(); k < vm; k++) {
                int idx = vf.extraPlacedAt(k);
                if (idx < 0 || idx >= n || (idx >= start && idx <= end)) continue;
                int oBefore = owners;
                Fragment f = kid(ch.get(idx), cc, moving, null, false);
                if (f != null) {
                    tmp[c++] = f;
                    kidsOwnersSum += lastBuiltMemo != null ? lastBuiltMemo.owners : owners - oBefore;
                    if (lastBuiltMemo != null && lastBuiltMemo.subtreeVpSensitive) subVp = true;
                    if (lastBuiltMemo != null && lastBuiltMemo.scrolls.length > 0) kidsScrolls = true;
                }
            }
        }
        return finishKids(m != null ? m.frag : null, own, after, drawn, box, kind, z, layer, ex,
                tmp, c, n, kidsOwnersSum, allMemo, hasFl, kidsScrolls, false, subVp, kIdx, false, 0, hasBuiltMemos);
    }

    private Fragment tryPillar2(LayoutBox box, PaintOrder.ChildContext cc, boolean moving, Memo m, int n, DirtyBuf db, int dc,
                                FrameScratch fs, DisplayItem[] own, DisplayItem[] after, boolean drawn, Fragment.Kind kind,
                                int z, LayerSpec layer, Fragment.Extras ex, boolean selfVp) {
        if (dc == 0) {
            owners += m.kidsOwners; MEMO_HITS.add(n);
            lastSubtreeVp = selfVp || m.subtreeVpSensitive; lastKidListSize = n; lastKidsOwners = m.kidsOwners;
            lastAllKidsMemo = true; lastHasFirstLine = false; lastKidsHaveScrolls = false;
            lastKidsSkipped = true; lastKidIndex = m.kidIndex; lastWasPillar2 = true; lastDirtyCount = 0; lastHasBuiltMemos = false;
            if (!drawn && after.length == 0 && ex == null && m.frag.agg == Fragment.NO_KIDS) return Fragment.EMPTY;
            return new Fragment(box, own, after, drawn, kind, z, layer, DisplayListBuilder::withLayer, ex,
                    m.frag.kidCount, m.frag.smallKids, m.frag.bigKids, m.frag.agg);
        }
        for (int k = 0; k < dc; k++) if (db.boxes[k].isAbsolute()) return null;
        int found = 0;
        LayoutBox[] mk = m.kids;
        for (int i = 0; i < n && found < dc; i++) {
            LayoutBox kb = mk[i];
            for (int k = 0; k < dc; k++) if (db.boxes[k] == kb) { db.pos[k] = i; found++; break; }
        }
        if (found != dc) return null;

        int oldDirtyOwners = 0, newDirtyOwners = 0;
        boolean allMemo = true, hasFl = false, kidsScrolls = false, subVp = selfVp || m.subtreeVpSensitive, nullFlip = false;
        Fragment prevFrag = m.frag;
        for (int k = 0; k < dc; k++) {
            int bi = db.pos[k], fi = m.kidIndex[bi];
            Memo oldKm = m.kidMemos[bi];
            oldDirtyOwners += oldKm != null ? oldKm.owners : 0;
            db.fragIdx[k] = fi;
            db.oldF[k] = fi >= 0 ? prevFrag.kidAt(fi) : null;
            int oBefore = owners;
            Fragment nf = kid(db.boxes[k], cc, moving, oldKm, true);
            newDirtyOwners += owners - oBefore;
            db.newF[k] = nf;
            Memo nm = nf != null ? lastBuiltMemo : null;
            db.newM[k] = nm;
            if ((fi >= 0) != (nf != null)) nullFlip = true;
            if (nf != null) {
                if (nm == null) allMemo = false;
                else {
                    if (nm.scrolls.length > 0) kidsScrolls = true;
                    if (nm.subtreeVpSensitive) subVp = true;
                    if (nm.flStyle != null || nm.flFrag != null) hasFl = true;
                }
            }
        }
        owners += m.kidsOwners - oldDirtyOwners;
        MEMO_HITS.add(n - dc);
        int totalKidsOwners = m.kidsOwners - oldDirtyOwners + newDirtyOwners;

        if (nullFlip) {
            Fragment[] tmp = fs.kids(n);
            Memo[] builtMemos = fs.memos(n);
            int[] kIdx = fs.idx(n);
            System.arraycopy(m.kidMemos, 0, builtMemos, 0, n);
            for (int k = 0; k < dc; k++) builtMemos[db.pos[k]] = db.newM[k];
            int c = 0;
            for (int i = 0; i < n; i++) {
                Fragment f = null;
                boolean dirty = false;
                for (int k = 0; k < dc; k++) if (db.pos[k] == i) { f = db.newF[k]; dirty = true; break; }
                if (!dirty) { int fi = m.kidIndex[i]; f = fi >= 0 ? prevFrag.kidAt(fi) : null; }
                if (f != null) { kIdx[i] = c; tmp[c++] = f; } else kIdx[i] = -1;
            }
            Arrays.fill(db.oldF, 0, dc, null); Arrays.fill(db.newF, 0, dc, null);
            Arrays.fill(db.newM, 0, dc, null); Arrays.fill(db.boxes, 0, dc, null);
            return finishKids(prevFrag, own, after, drawn, box, kind, z, layer, ex,
                    tmp, c, n, totalKidsOwners, allMemo, hasFl, kidsScrolls, true, subVp, kIdx, false, 0, n > 0);
        }

        int visibleDirty = 0;
        for (int k = 0; k < dc; k++) {
            if (db.fragIdx[k] >= 0) {
                if (visibleDirty != k) { db.fragIdx[visibleDirty] = db.fragIdx[k]; db.oldF[visibleDirty] = db.oldF[k]; db.newF[visibleDirty] = db.newF[k]; }
                visibleDirty++;
            }
        }
        Fragment[][] nextBig = Fragment.replaceChunks(prevFrag.bigKids, db.fragIdx, db.newF, visibleDirty);
        FragSeq[] nextAgg = Fragment.aggregateReplaced(prevFrag.agg, prevFrag.kidCount, db.fragIdx, db.oldF, db.newF, visibleDirty);
        lastSubtreeVp = subVp; lastKidListSize = n; lastKidsOwners = totalKidsOwners;
        lastAllKidsMemo = allMemo; lastHasFirstLine = hasFl; lastKidsHaveScrolls = kidsScrolls;
        lastKidsSkipped = true; lastKidIndex = m.kidIndex; lastWasPillar2 = true; lastDirtyCount = dc; lastHasBuiltMemos = false;
        if (!drawn && after.length == 0 && ex == null && nextAgg == Fragment.NO_KIDS) return Fragment.EMPTY;
        return new Fragment(box, own, after, drawn, kind, z, layer, DisplayListBuilder::withLayer, ex,
                prevFrag.kidCount, null, nextBig, nextAgg);
    }

    private Fragment finishKids(Fragment prev, DisplayItem[] own, DisplayItem[] after, boolean drawn, LayoutBox box,
                                Fragment.Kind kind, int z, LayerSpec layer, Fragment.Extras ex,
                                Fragment[] tmp, int c, int n, int kidsOwnersSum, boolean allMemo, boolean hasFl,
                                boolean kidsScrolls, boolean skipped, boolean subVp, int[] kIdx,
                                boolean wasPillar2, int dc, boolean hasBuiltMemos) {
        Fragment[] small = c == 0 ? NO_FRAGMENTS : c < 64
                ? (prev != null && prev.smallKids != null && prev.smallKids.length == c && Fragment.sameArray(prev.smallKids, tmp, c) ? prev.smallKids : Arrays.copyOf(tmp, c))
                : null;
        Fragment[][] big = c >= 64 ? Fragment.chunkifyFrom(prev, tmp, c) : null;
        Arrays.fill(tmp, 0, c, null);
        FragSeq[] agg = Fragment.aggregateDiff(prev, small, big, c);
        lastSubtreeVp = subVp; lastKidListSize = n; lastKidsOwners = kidsOwnersSum;
        lastAllKidsMemo = allMemo; lastHasFirstLine = hasFl; lastKidsHaveScrolls = kidsScrolls;
        lastKidsSkipped = skipped; lastKidIndex = kIdx; lastWasPillar2 = wasPillar2; lastDirtyCount = dc; lastHasBuiltMemos = hasBuiltMemos;
        if (!drawn && after.length == 0 && ex == null && agg == Fragment.NO_KIDS) return Fragment.EMPTY;
        return new Fragment(box, own, after, drawn, kind, z, layer, DisplayListBuilder::withLayer, ex, c, small, big, agg);
    }
}
