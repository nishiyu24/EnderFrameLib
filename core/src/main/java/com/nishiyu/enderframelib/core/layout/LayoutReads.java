package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.model.PositionTryRule;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;

import java.util.Arrays;
import java.util.Map;

/**
 * 配置・レイアウトが外の状態（原点・親の外寸・スクロール・包含ブロック・ビューポート・アンカー・float・sticky）を
 * 読むための唯一の入口（読み取りの台帳: 設計書 {@code DESIGN_INCREMENTAL_KERNEL.md} §2 K2・§3.1・K1）。
 *
 * <p>入口から読むだけで現在の配置フレームへ {@link ReadKind} のビットを自動記録する（「読んだのに記録し忘れた」を
 * 構造上起こさない）。スレッドごとの作業配列（{@link MeasureReads.ThreadState}）を使い、主パスでヒープ確保を出さない。
 * K1 の段階では記録するだけで、再利用の挙動（{@link PlacementMemo}・{@link MeasureMemo}）は変えない。</p>
 */
final class LayoutReads {
    private LayoutReads() {}

    /** テスト用: 1 箱の配置が終わるたびに、記録された {@link ReadKind#stageBit()} の集合を受け取る口。 */
    @FunctionalInterface
    interface PlaceRecorder {
        void onPlaced(LayoutBox box, int placeReadMask);
    }

    // ------------------------------------------------------------------ 配置スタック管理

    static MeasureReads.ThreadState state() {
        return MeasureReads.state();
    }

    static int pushPlacing(MeasureReads.ThreadState st, LayoutBox box, float parentX, float parentY, float parentOuterW, float parentOuterH) {
        int idx = st.placeDepth;
        if (idx == st.placeBoxStack.length) {
            int next = idx << 1;
            st.placeBoxStack = Arrays.copyOf(st.placeBoxStack, next);
            st.placeReadStack = Arrays.copyOf(st.placeReadStack, next);
            st.placeOriginX = Arrays.copyOf(st.placeOriginX, next);
            st.placeOriginY = Arrays.copyOf(st.placeOriginY, next);
            st.placeParentW = Arrays.copyOf(st.placeParentW, next);
            st.placeParentH = Arrays.copyOf(st.placeParentH, next);
            st.placeRefStart = Arrays.copyOf(st.placeRefStart, next);
        }
        st.placeBoxStack[idx] = box;
        st.placeReadStack[idx] = 0;
        st.placeOriginX[idx] = parentX;
        st.placeOriginY[idx] = parentY;
        st.placeParentW[idx] = parentOuterW;
        st.placeParentH[idx] = parentOuterH;
        st.placeRefStart[idx] = st.placeRefCount;
        st.placeDepth = idx + 1;
        return idx;
    }

    static int pushPlacing(LayoutBox box, float parentX, float parentY, float parentOuterW, float parentOuterH) {
        return pushPlacing(MeasureReads.state(), box, parentX, parentY, parentOuterW, parentOuterH);
    }

    static int popPlacing(MeasureReads.ThreadState st, int slot) {
        LayoutBox box = st.placeBoxStack[slot];
        int bits = st.placeReadStack[slot];
        int start = st.placeRefStart[slot];
        int end = st.placeRefCount;
        st.placeBoxStack[slot] = null;
        st.placeReadStack[slot] = 0;
        st.placeDepth = slot;
        if (start == end) {
            if (slot > 0 && bits != 0 && st.placeBoxStack[slot - 1] != null) {
                st.placeReadStack[slot - 1] |= ReadKind.propagatePlacementToParent(bits);
            }
        } else if (slot > 0 && box != null && st.placeBoxStack[slot - 1] != null) {
            LayoutBox parent = st.placeBoxStack[slot - 1];
            int write = start;
            int keptRefBits = 0;
            for (int i = start; i < end; i++) {
                LayoutBox target = st.placeRefTarget[i];
                if (target != null && ReadKind.isInsideSubtree(parent, target)) {
                    continue;
                }
                if (write != i) {
                    st.placeRefKind[write] = st.placeRefKind[i];
                    st.placeRefReader[write] = st.placeRefReader[i];
                    st.placeRefTarget[write] = target;
                    st.placeRefX[write] = st.placeRefX[i];
                    st.placeRefY[write] = st.placeRefY[i];
                    st.placeRefW[write] = st.placeRefW[i];
                    st.placeRefH[write] = st.placeRefH[i];
                    st.placeRefScrollX[write] = st.placeRefScrollX[i];
                    st.placeRefScrollY[write] = st.placeRefScrollY[i];
                }
                keptRefBits |= PlacementDeps.readKindBit(st.placeRefKind[write]);
                write++;
            }
            for (int i = write; i < end; i++) {
                st.placeRefReader[i] = null;
                st.placeRefTarget[i] = null;
            }
            st.placeRefCount = write;
            int prop = (ReadKind.propagatePlacementToParent(bits) & ~PlacementDeps.ABSORBABLE_MASK) | keptRefBits;
            st.placeReadStack[slot - 1] |= prop;
        } else {
            for (int i = start; i < end; i++) {
                st.placeRefReader[i] = null;
                st.placeRefTarget[i] = null;
            }
            st.placeRefCount = start;
        }
        if (st.placeRecorder != null && box != null) {
            st.placeRecorder.onPlaced(box, bits);
        }
        return bits;
    }

    private static void pushPlaceRef(MeasureReads.ThreadState st, byte kind, LayoutBox reader, LayoutBox target,
                                     float x, float y, float w, float h, float sx, float sy) {
        if (st.placeDepth <= 0 || st.placeBoxStack[st.placeDepth - 1] == null) return;
        int start = st.placeRefStart[st.placeDepth - 1];
        for (int i = start; i < st.placeRefCount; i++) {
            if (st.placeRefKind[i] == kind && st.placeRefReader[i] == reader && st.placeRefTarget[i] == target) {
                st.placeRefX[i] = x;
                st.placeRefY[i] = y;
                st.placeRefW[i] = w;
                st.placeRefH[i] = h;
                st.placeRefScrollX[i] = sx;
                st.placeRefScrollY[i] = sy;
                return;
            }
        }
        int idx = st.placeRefCount;
        if (idx == st.placeRefKind.length) {
            int next = idx << 1;
            st.placeRefKind = Arrays.copyOf(st.placeRefKind, next);
            st.placeRefReader = Arrays.copyOf(st.placeRefReader, next);
            st.placeRefTarget = Arrays.copyOf(st.placeRefTarget, next);
            st.placeRefX = Arrays.copyOf(st.placeRefX, next);
            st.placeRefY = Arrays.copyOf(st.placeRefY, next);
            st.placeRefW = Arrays.copyOf(st.placeRefW, next);
            st.placeRefH = Arrays.copyOf(st.placeRefH, next);
            st.placeRefScrollX = Arrays.copyOf(st.placeRefScrollX, next);
            st.placeRefScrollY = Arrays.copyOf(st.placeRefScrollY, next);
        }
        st.placeRefKind[idx] = kind;
        st.placeRefReader[idx] = reader;
        st.placeRefTarget[idx] = target;
        st.placeRefX[idx] = x;
        st.placeRefY[idx] = y;
        st.placeRefW[idx] = w;
        st.placeRefH[idx] = h;
        st.placeRefScrollX[idx] = sx;
        st.placeRefScrollY[idx] = sy;
        st.placeRefCount = idx + 1;
    }

    /** 配置を再利用したとき、飛ばした子部分木の外向きの読み取りを親の配置フレームへ引き継ぐ（K2）。 */
    static void propagateReused(int childMask, PlacementDeps childDeps) {
        MeasureReads.ThreadState st = MeasureReads.state();
        if (st.placeDepth <= 0 || st.placeBoxStack[st.placeDepth - 1] == null) return;
        LayoutBox parent = st.placeBoxStack[st.placeDepth - 1];
        int prop = ReadKind.propagatePlacementToParent(childMask) & ~PlacementDeps.ABSORBABLE_MASK;
        if (childDeps != null && childDeps.refKinds != null) {
            for (int i = 0; i < childDeps.refKinds.length; i++) {
                LayoutBox target = childDeps.refTargets[i];
                if (target != null && ReadKind.isInsideSubtree(parent, target)) continue;
                byte kind = childDeps.refKinds[i];
                int b = i * 6;
                pushPlaceRef(st, kind, childDeps.refReaders[i], target,
                        childDeps.refCoords[b], childDeps.refCoords[b + 1],
                        childDeps.refCoords[b + 2], childDeps.refCoords[b + 3],
                        childDeps.refCoords[b + 4], childDeps.refCoords[b + 5]);
                prop |= PlacementDeps.readKindBit(kind);
            }
        }
        st.placeReadStack[st.placeDepth - 1] |= prop;
    }

    /** {@code box} の配置フレーム終端で、自身に吸収される外部参照を取り除いた正味の外向きマスクを返す（K2）。 */
    static int absorbSelfAndMask(LayoutBox box, MeasureReads.ThreadState st, int slot) {
        int bits = st.placeReadStack[slot];
        int start = st.placeRefStart[slot];
        int end = st.placeRefCount;
        if (start == end) return bits;
        int write = start;
        int keptRefBits = 0;
        for (int i = start; i < end; i++) {
            LayoutBox target = st.placeRefTarget[i];
            if (target != null && ReadKind.isInsideSubtree(box, target)) continue;
            if (write != i) {
                st.placeRefKind[write] = st.placeRefKind[i];
                st.placeRefReader[write] = st.placeRefReader[i];
                st.placeRefTarget[write] = target;
                st.placeRefX[write] = st.placeRefX[i];
                st.placeRefY[write] = st.placeRefY[i];
                st.placeRefW[write] = st.placeRefW[i];
                st.placeRefH[write] = st.placeRefH[i];
                st.placeRefScrollX[write] = st.placeRefScrollX[i];
                st.placeRefScrollY[write] = st.placeRefScrollY[i];
            }
            keptRefBits |= PlacementDeps.readKindBit(st.placeRefKind[write]);
            write++;
        }
        for (int i = write; i < end; i++) {
            st.placeRefReader[i] = null;
            st.placeRefTarget[i] = null;
        }
        st.placeRefCount = write;
        return (bits & ~PlacementDeps.ABSORBABLE_MASK) | keptRefBits;
    }

    static int popPlacing(int slot) {
        return popPlacing(MeasureReads.state(), slot);
    }

    static void recordPlace(ReadKind kind) {
        MeasureReads.ThreadState st = MeasureReads.state();
        if (st.placeDepth > 0 && st.placeBoxStack[st.placeDepth - 1] != null) {
            st.placeReadStack[st.placeDepth - 1] |= kind.stageBit();
        }
    }

    static void recordPlace(MeasureReads.ThreadState st, int slot, ReadKind kind) {
        st.placeReadStack[slot] |= kind.stageBit();
    }

    static void withPlaceRecorder(PlaceRecorder recorder, Runnable body) {
        MeasureReads.ThreadState st = MeasureReads.state();
        PlaceRecorder prev = st.placeRecorder;
        st.placeRecorder = recorder;
        try {
            body.run();
        } finally {
            st.placeRecorder = prev;
        }
    }

    // ------------------------------------------------------------------ 原点・親の外寸

    static float originX(MeasureReads.ThreadState st, int slot) {
        st.placeReadStack[slot] |= ReadKind.PLACE_ORIGIN.stageBit();
        return st.placeOriginX[slot];
    }

    static float originY(MeasureReads.ThreadState st, int slot) {
        st.placeReadStack[slot] |= ReadKind.PLACE_ORIGIN.stageBit();
        return st.placeOriginY[slot];
    }

    static float parentOuterW() {
        MeasureReads.ThreadState st = MeasureReads.state();
        int top = st.placeDepth - 1;
        st.placeReadStack[top] |= ReadKind.PLACE_PARENT_OUTER.stageBit();
        return st.placeParentW[top];
    }

    static float parentOuterH() {
        MeasureReads.ThreadState st = MeasureReads.state();
        int top = st.placeDepth - 1;
        st.placeReadStack[top] |= ReadKind.PLACE_PARENT_OUTER.stageBit();
        return st.placeParentH[top];
    }

    static float peekOriginX(MeasureReads.ThreadState st, int slot) {
        return st.placeOriginX[slot];
    }

    static float peekOriginY(MeasureReads.ThreadState st, int slot) {
        return st.placeOriginY[slot];
    }

    static float peekParentOuterW(MeasureReads.ThreadState st, int slot) {
        return st.placeParentW[slot];
    }

    static float peekParentOuterH(MeasureReads.ThreadState st, int slot) {
        return st.placeParentH[slot];
    }

    static float parentInnerW(LayoutBox parent) {
        recordPlace(ReadKind.PLACE_PARENT_OUTER);
        return Math.max(0, parent.w - parent.horizontalDecoration());
    }

    static float parentInnerH(LayoutBox parent) {
        recordPlace(ReadKind.PLACE_PARENT_OUTER);
        return Math.max(0, parent.h - parent.verticalDecoration());
    }

    static float parentContentTop(LayoutBox box) {
        recordPlace(ReadKind.PLACE_PARENT_OUTER);
        LayoutBox p = box.parent;
        return p.y() + p.bT + p.pT;
    }

    static float parentContentBottom(LayoutBox box) {
        recordPlace(ReadKind.PLACE_PARENT_OUTER);
        LayoutBox p = box.parent;
        return p.y() + p.h - p.bB - p.pB;
    }

    static float parentContentLeft(LayoutBox box) {
        recordPlace(ReadKind.PLACE_PARENT_OUTER);
        LayoutBox p = box.parent;
        return p.x() + p.bL + p.pL;
    }

    static float parentContentRight(LayoutBox box) {
        recordPlace(ReadKind.PLACE_PARENT_OUTER);
        LayoutBox p = box.parent;
        return p.x() + p.w - p.bR - p.pR;
    }

    static float peekParentContentTop(LayoutBox box) {
        LayoutBox p = box.parent;
        return p.y() + p.bT + p.pT;
    }

    static float peekParentContentLeft(LayoutBox box) {
        LayoutBox p = box.parent;
        return p.x() + p.bL + p.pL;
    }

    static float peekParentContentRight(LayoutBox box) {
        LayoutBox p = box.parent;
        return p.x() + p.w - p.bR - p.pR;
    }

    static float peekParentContentBottom(LayoutBox box) {
        LayoutBox p = box.parent;
        return p.y() + p.h - p.bB - p.pB;
    }

    static float peekViewportW(LayoutContext ctx) {
        return ctx.viewport().rootW();
    }

    static float peekViewportH(LayoutContext ctx) {
        return ctx.viewport().rootH();
    }

    static float peekAnchorX(LayoutBox anchor) { return anchor.x(); }
    static float peekAnchorY(LayoutBox anchor) { return anchor.y(); }
    static float peekAnchorW(LayoutBox anchor) { return anchor.w; }
    static float peekAnchorH(LayoutBox anchor) { return anchor.h; }

    // ------------------------------------------------------------------ スクロール

    static float selfScrollX(LayoutBox box, LayoutContext ctx, MeasureReads.ThreadState st, int slot) {
        if (box.isScrollContainer()) st.placeReadStack[slot] |= ReadKind.PLACE_SELF_SCROLL.stageBit();
        return ctx.scrollState().getScrollX(box.node);
    }

    static float selfScrollY(LayoutBox box, LayoutContext ctx, MeasureReads.ThreadState st, int slot) {
        if (box.isScrollContainer()) st.placeReadStack[slot] |= ReadKind.PLACE_SELF_SCROLL.stageBit();
        return ctx.scrollState().getScrollY(box.node);
    }

    static float containingBlockScrollX(LayoutBox cb, LayoutContext ctx) {
        recordPlace(ReadKind.PLACE_CONTAINING_BLOCK);
        if (cb.isScrollContainer()) recordPlace(ReadKind.PLACE_DESCENDANT_SCROLL);
        return ctx.scrollState().getScrollX(cb.node);
    }

    static float containingBlockScrollY(LayoutBox cb, LayoutContext ctx) {
        recordPlace(ReadKind.PLACE_CONTAINING_BLOCK);
        if (cb.isScrollContainer()) recordPlace(ReadKind.PLACE_DESCENDANT_SCROLL);
        return ctx.scrollState().getScrollY(cb.node);
    }

    static float peekScrollX(LayoutBox box, LayoutContext ctx) {
        return ctx.scrollState().getScrollX(box.node);
    }

    static float peekScrollY(LayoutBox box, LayoutContext ctx) {
        return ctx.scrollState().getScrollY(box.node);
    }

    static int scrollVersion() {
        return ScrollState.version();
    }

    static LayoutContext freshContext(LayoutContext ctx, boolean reuseMeasurements) {
        return new LayoutContext(ctx.viewport(), ctx.intrinsicResolver(), ctx.scrollState(), ctx.fonts(),
                reuseMeasurements, ctx.layoutRun(), ctx.firstPass(), ctx.autoVisibility());
    }

    // ------------------------------------------------------------------ ビューポート

    static float placeViewportW(LayoutContext ctx) {
        recordPlace(ReadKind.PLACE_VIEWPORT);
        return ctx.viewport().rootW();
    }

    static float placeViewportH(LayoutContext ctx) {
        recordPlace(ReadKind.PLACE_VIEWPORT);
        return ctx.viewport().rootH();
    }

    // ------------------------------------------------------------------ 包含ブロック

    /**
     * 絶対配置・固定配置の包含ブロックを作る祖先（無ければ null = 初期包含ブロック）。
     * {@code LayoutBox.findContainingBlock()} の実装本体。記録なし版（{@code core.paint} 等から呼ばれる）。
     */
    static LayoutBox findContainingBlockRaw(LayoutBox box) {
        LayoutBox cur = box.parent;
        while (cur != null) {
            boolean transformLike = hasTransformOrFilter(cur)
                    || (cur.style.containment() & (ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT)) != 0;
            if (transformLike || (!box.isFixed && (cur.isRelative || cur.isAbsolute || cur.isSticky))) {
                return cur;
            }
            cur = cur.parent;
        }
        return null;
    }

    static LayoutBox containingBlockForMeasure(LayoutBox box) {
        return findContainingBlockRaw(box);
    }

    static LayoutBox.ContainingBlock resolveContainingBlock(LayoutBox box, LayoutContext ctx) {
        recordPlace(ReadKind.PLACE_CONTAINING_BLOCK);
        LayoutBox cb = findContainingBlockRaw(box);
        LayoutBox.ContainingBlock res;
        float cbScrollX = 0f, cbScrollY = 0f;
        if (cb == null) {
            res = new LayoutBox.ContainingBlock(0, 0, placeViewportW(ctx), placeViewportH(ctx));
        } else {
            cbScrollX = containingBlockScrollX(cb, ctx);
            cbScrollY = containingBlockScrollY(cb, ctx);
            float cbX = cb.x() + cb.bL - cbScrollX;
            float cbY = cb.y() + cb.bT - cbScrollY;
            float cbW = Math.max(0, cb.w - cb.bL - cb.bR);
            float cbH = Math.max(0, cb.h - cb.bT - cb.bB);
            if (cb.display != null && cb.display.inner() == LayoutBox.Display.Inner.GRID) {
                float[] area = GridLayoutEngine.absoluteArea(cb, box);
                if (area != null) {
                    res = new LayoutBox.ContainingBlock(cbX + area[0], cbY + area[1], area[2], area[3]);
                } else {
                    res = new LayoutBox.ContainingBlock(cbX, cbY, cbW, cbH);
                }
            } else {
                res = new LayoutBox.ContainingBlock(cbX, cbY, cbW, cbH);
            }
        }
        pushPlaceRef(MeasureReads.state(), PlacementDeps.REF_CB, box, cb,
                res.x(), res.y(), res.w(), res.h(), cbScrollX, cbScrollY);
        return res;
    }

    static boolean peekContainingBlockMatches(LayoutBox box, LayoutBox expectedCb, LayoutContext ctx,
                                              float refBaseX, float refBaseY,
                                              float ex, float ey, float ew, float eh,
                                              float esx, float esy) {
        LayoutBox cb = findContainingBlockRaw(box);
        if (cb != expectedCb) return false;
        if (cb == null) {
            return (0f - refBaseX) == ex && (0f - refBaseY) == ey
                    && ew == ctx.viewport().rootW() && eh == ctx.viewport().rootH();
        }
        float sx = peekScrollX(cb, ctx);
        float sy = peekScrollY(cb, ctx);
        if (sx != esx || sy != esy) return false;
        float cx = cb.x() + cb.bL - sx;
        float cy = cb.y() + cb.bT - sy;
        float cw = Math.max(0, cb.w - cb.bL - cb.bR);
        float ch = Math.max(0, cb.h - cb.bT - cb.bB);
        if (cb.display != null && cb.display.inner() == LayoutBox.Display.Inner.GRID) {
            float[] area = GridLayoutEngine.absoluteArea(cb, box);
            if (area != null) {
                cx += area[0];
                cy += area[1];
                cw = area[2];
                ch = area[3];
            }
        }
        return (cx - refBaseX) == ex && (cy - refBaseY) == ey && cw == ew && ch == eh;
    }

    private static boolean hasTransformOrFilter(LayoutBox b) {
        return isSet(b.style.typed().visual.transform) || isSet(b.style.typed().visual.filter);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank() && !"none".equalsIgnoreCase(value.trim());
    }

    // ------------------------------------------------------------------ アンカー

    static LayoutBox findAnchor(LayoutBox box, String name, String overridePositionAnchor) {
        recordPlace(ReadKind.PLACE_ANCHOR);
        LayoutBox root = rootBox(box);
        if (name == null) {
            name = overridePositionAnchor != null ? overridePositionAnchor : box.style.typed().rare.positionAnchor;
        }
        LayoutBox anchor;
        if (name != null && !name.isBlank() && !"auto".equalsIgnoreCase(name.trim())) {
            anchor = findAnchorNamed(root, box, name.trim());
        } else {
            String id = box.node != null && box.node.attrs != null ? box.node.attrs.get("id") : null;
            anchor = id == null ? null : findInvoker(root, id);
        }
        pushPlaceRef(MeasureReads.state(), PlacementDeps.REF_ANCHOR, box, anchor,
                anchor != null ? anchor.x() : 0f, anchor != null ? anchor.y() : 0f,
                anchor != null ? anchor.w : 0f, anchor != null ? anchor.h : 0f, 0f, 0f);
        return anchor;
    }

    static float anchorX(LayoutBox anchor) {
        recordPlace(ReadKind.PLACE_ANCHOR);
        return anchor.x();
    }

    static float anchorY(LayoutBox anchor) {
        recordPlace(ReadKind.PLACE_ANCHOR);
        return anchor.y();
    }

    static float anchorW(LayoutBox anchor) {
        recordPlace(ReadKind.PLACE_ANCHOR);
        return anchor.w;
    }

    static float anchorH(LayoutBox anchor) {
        recordPlace(ReadKind.PLACE_ANCHOR);
        return anchor.h;
    }

    static Map<String, PositionTryRule> positionTryRules(LayoutBox box) {
        recordPlace(ReadKind.PLACE_ANCHOR);
        return rootBox(box).positionTryRules;
    }

    private static LayoutBox rootBox(LayoutBox box) {
        LayoutBox root = box;
        while (root.parent != null) root = root.parent;
        return root;
    }

    private static LayoutBox findAnchorNamed(LayoutBox cur, LayoutBox self, String name) {
        if (cur != self && cur.style != null) {
            String n = cur.style.typed().rare.anchorName;
            if (n != null && Arrays.asList(n.trim().split("[\\s,]+")).contains(name)) return cur;
        }
        for (int i = 0, n = cur.elementChildren.size(); i < n; i++) {
            LayoutBox hit = findAnchorNamed(cur.elementChildren.get(i), self, name);
            if (hit != null) return hit;
        }
        return null;
    }

    private static LayoutBox findInvoker(LayoutBox cur, String id) {
        if (cur.node != null && cur.node.attrs != null && id.equals(cur.node.attrs.get("popovertarget"))) return cur;
        for (int i = 0, n = cur.elementChildren.size(); i < n; i++) {
            LayoutBox hit = findInvoker(cur.elementChildren.get(i), id);
            if (hit != null) return hit;
        }
        return null;
    }

    // ------------------------------------------------------------------ sticky / float

    static LayoutBox stickyScrollContainer(LayoutBox box) {
        recordPlace(ReadKind.PLACE_STICKY);
        LayoutBox port = null;
        for (LayoutBox cur = box.parent; cur != null; cur = cur.parent) {
            if (cur.isScrollContainer()) {
                port = cur;
                break;
            }
        }
        pushPlaceRef(MeasureReads.state(), PlacementDeps.REF_STICKY, box, port,
                port != null ? port.x() + port.bL : 0f,
                port != null ? port.y() + port.bT : 0f,
                port != null ? Math.max(0, port.w - port.bL - port.bR) : 0f,
                port != null ? Math.max(0, port.h - port.bT - port.bB) : 0f,
                0f, 0f);
        return port;
    }

    static boolean peekStickyMatches(LayoutBox box, LayoutBox expectedPort, LayoutContext ctx,
                                     float refBaseX, float refBaseY,
                                     float ex, float ey, float ew, float eh,
                                     float esx, float esy) {
        LayoutBox port = peekStickyScrollContainer(box);
        if (port != expectedPort) return false;
        if (port == null) return (0f - refBaseX) == ex && (0f - refBaseY) == ey;
        return ((port.x() + port.bL) - refBaseX) == ex
                && ((port.y() + port.bT) - refBaseY) == ey
                && ew == Math.max(0, port.w - port.bL - port.bR)
                && eh == Math.max(0, port.h - port.bT - port.bB);
    }

    static LayoutBox peekStickyScrollContainer(LayoutBox box) {
        for (LayoutBox cur = box.parent; cur != null; cur = cur.parent) {
            if (cur.isScrollContainer()) return cur;
        }
        return null;
    }

    static float stickyPortX(LayoutBox port) {
        recordPlace(ReadKind.PLACE_STICKY);
        return port.x() + port.bL;
    }

    static float stickyPortY(LayoutBox port) {
        recordPlace(ReadKind.PLACE_STICKY);
        return port.y() + port.bT;
    }

    static float stickyPortW(LayoutBox port) {
        recordPlace(ReadKind.PLACE_STICKY);
        return Math.max(0, port.w - port.bL - port.bR);
    }

    static float stickyPortH(LayoutBox port) {
        recordPlace(ReadKind.PLACE_STICKY);
        return Math.max(0, port.h - port.bT - port.bB);
    }

    static void noteFloatPlacement() {
        recordPlace(ReadKind.PLACE_FLOAT);
    }
}
