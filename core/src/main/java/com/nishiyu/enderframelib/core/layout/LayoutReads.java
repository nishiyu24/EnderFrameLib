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
        }
        st.placeBoxStack[idx] = box;
        st.placeReadStack[idx] = 0;
        st.placeOriginX[idx] = parentX;
        st.placeOriginY[idx] = parentY;
        st.placeParentW[idx] = parentOuterW;
        st.placeParentH[idx] = parentOuterH;
        st.placeDepth = idx + 1;
        return idx;
    }

    static int pushPlacing(LayoutBox box, float parentX, float parentY, float parentOuterW, float parentOuterH) {
        return pushPlacing(MeasureReads.state(), box, parentX, parentY, parentOuterW, parentOuterH);
    }

    static int popPlacing(MeasureReads.ThreadState st, int slot) {
        LayoutBox box = st.placeBoxStack[slot];
        int bits = st.placeReadStack[slot];
        st.placeBoxStack[slot] = null;
        st.placeReadStack[slot] = 0;
        st.placeDepth = slot;
        if (slot > 0 && box != null && st.placeBoxStack[slot - 1] == box.parent) {
            st.placeReadStack[slot - 1] |= ReadKind.propagatePlacementToParent(bits);
        }
        if (st.placeRecorder != null && box != null) {
            st.placeRecorder.onPlaced(box, bits);
        }
        return bits;
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
        return p.y + p.bT + p.pT;
    }

    static float parentContentBottom(LayoutBox box) {
        recordPlace(ReadKind.PLACE_PARENT_OUTER);
        LayoutBox p = box.parent;
        return p.y + p.h - p.bB - p.pB;
    }

    static float parentContentLeft(LayoutBox box) {
        recordPlace(ReadKind.PLACE_PARENT_OUTER);
        LayoutBox p = box.parent;
        return p.x + p.bL + p.pL;
    }

    static float parentContentRight(LayoutBox box) {
        recordPlace(ReadKind.PLACE_PARENT_OUTER);
        LayoutBox p = box.parent;
        return p.x + p.w - p.bR - p.pR;
    }

    static float peekParentContentBottom(LayoutBox box) {
        LayoutBox p = box.parent;
        return p.y + p.h - p.bB - p.pB;
    }

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

    static int stepsToContainingBlock(LayoutBox box, int cbOutside) {
        LayoutBox cb = findContainingBlockRaw(box);
        if (cb == null) return cbOutside;
        int n = 0;
        for (LayoutBox p = box; p != null && p != cb; p = p.parent) n++;
        return n;
    }

    static LayoutBox.ContainingBlock resolveContainingBlock(LayoutBox box, LayoutContext ctx) {
        recordPlace(ReadKind.PLACE_CONTAINING_BLOCK);
        LayoutBox cb = findContainingBlockRaw(box);
        if (cb == null) {
            return new LayoutBox.ContainingBlock(0, 0, placeViewportW(ctx), placeViewportH(ctx));
        }
        float cbScrollX = containingBlockScrollX(cb, ctx);
        float cbScrollY = containingBlockScrollY(cb, ctx);
        float cbX = cb.x + cb.bL - cbScrollX;
        float cbY = cb.y + cb.bT - cbScrollY;
        float cbW = Math.max(0, cb.w - cb.bL - cb.bR);
        float cbH = Math.max(0, cb.h - cb.bT - cb.bB);
        if (cb.display != null && cb.display.inner() == LayoutBox.Display.Inner.GRID) {
            float[] area = GridLayoutEngine.absoluteArea(cb, box);
            if (area != null) return new LayoutBox.ContainingBlock(cbX + area[0], cbY + area[1], area[2], area[3]);
        }
        return new LayoutBox.ContainingBlock(cbX, cbY, cbW, cbH);
    }

    private static boolean hasTransformOrFilter(LayoutBox b) {
        return isSet(b.style.get(CssPropertyIndex.TRANSFORM)) || isSet(b.style.get(CssPropertyIndex.FILTER));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank() && !"none".equalsIgnoreCase(value.trim());
    }

    // ------------------------------------------------------------------ アンカー

    static LayoutBox findAnchor(LayoutBox box, String name, String overridePositionAnchor) {
        recordPlace(ReadKind.PLACE_ANCHOR);
        LayoutBox root = rootBox(box);
        if (name == null) {
            name = overridePositionAnchor != null ? overridePositionAnchor : box.style.get(CssPropertyIndex.POSITION_ANCHOR);
        }
        if (name != null && !name.isBlank() && !"auto".equalsIgnoreCase(name.trim())) {
            return findAnchorNamed(root, box, name.trim());
        }
        String id = box.node != null && box.node.attrs != null ? box.node.attrs.get("id") : null;
        return id == null ? null : findInvoker(root, id);
    }

    static float anchorX(LayoutBox anchor) {
        recordPlace(ReadKind.PLACE_ANCHOR);
        return anchor.x;
    }

    static float anchorY(LayoutBox anchor) {
        recordPlace(ReadKind.PLACE_ANCHOR);
        return anchor.y;
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
            String n = cur.style.get(CssPropertyIndex.ANCHOR_NAME);
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
        for (LayoutBox cur = box.parent; cur != null; cur = cur.parent) {
            if (cur.isScrollContainer()) return cur;
        }
        return null;
    }

    static LayoutBox peekStickyScrollContainer(LayoutBox box) {
        for (LayoutBox cur = box.parent; cur != null; cur = cur.parent) {
            if (cur.isScrollContainer()) return cur;
        }
        return null;
    }

    static float stickyPortX(LayoutBox port) {
        recordPlace(ReadKind.PLACE_STICKY);
        return port.x + port.bL;
    }

    static float stickyPortY(LayoutBox port) {
        recordPlace(ReadKind.PLACE_STICKY);
        return port.y + port.bT;
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
