package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.ArrayList;

/**
 * 対話状態のうち、節の鎖で決まる部分（M5-3a で {@link InteractionState} から出した）: ポインタの下・押下中・フォーカス中の節と、
 * それぞれの祖先の鎖（{@code :hover}・{@code :active}・{@code :focus-within}）、Top Layer の積み順（{@code :modal}・
 * {@code :popover-open}）と、そこから決まる不活性（{@code inert} 属性・最前面のモーダルの外側）。
 *
 * <p>ビットの読み書きと無効化は持ち主（{@link InteractionState}）の {@code setHovered} などを通す。鎖の更新は作業用の並びを
 * 使い回し、フレームごとに確保しない。</p>
 */
final class InteractionChains {
    private final InteractionState state;

    /** 現在 {@code :hover} な節（ポインタの下の節とその祖先）。 */
    private ArrayList<HtmlNode> hoveredChain = new ArrayList<>(16);
    /** 現在 {@code :active} な節（押下中の節とその祖先）。 */
    private ArrayList<HtmlNode> activeChain = new ArrayList<>(16);
    /** 現在 {@code :focus-within} な節（フォーカス中の節とその祖先）。 */
    private ArrayList<HtmlNode> focusWithinChain = new ArrayList<>(16);
    /** 鎖更新時の作業用リスト（フレームごとの確保を避ける）。 */
    private ArrayList<HtmlNode> scratchChain = new ArrayList<>(16);
    /** Top Layer の所属と表示順（{@code dialog.showModal()}・{@code showPopover()} の順）。 */
    private final ArrayList<HtmlNode> topLayerOrder = new ArrayList<>(4);

    /** ポインタの下の節（{@link #hoveredChain} の末端）。 */
    HtmlNode hoveredNode;
    /** 押下中の節（{@link #activeChain} の末端）。 */
    HtmlNode activeNode;
    /** フォーカス中の節。 */
    HtmlNode focusedNode;

    InteractionChains(InteractionState state) {
        this.state = state;
    }

    /** ポインタの下の節を更新する。{@code target} とその祖先が {@code :hover}、それ以外は外れる。null で全解除。 */
    boolean setHoveredNode(HtmlNode target) {
        ArrayList<HtmlNode> nextChain = fillAncestorsAndSelf(target, scratchChain);
        hoveredNode = target;
        if (sameChain(hoveredChain, nextChain)) return false;

        boolean changed = false;
        for (int i = 0, n = hoveredChain.size(); i < n; i++) {
            HtmlNode left = hoveredChain.get(i);
            if (!containsRef(nextChain, left)) changed |= state.setHovered(left, false);
        }
        for (int i = 0, n = nextChain.size(); i < n; i++) {
            HtmlNode entered = nextChain.get(i);
            if (!containsRef(hoveredChain, entered)) changed |= state.setHovered(entered, true);
        }
        scratchChain = hoveredChain;
        hoveredChain = nextChain;
        return changed;
    }

    /** 押下中の節を更新する。{@code target} とその祖先が {@code :active}、それ以外は外れる。null で全解除。 */
    boolean setActiveNode(HtmlNode target) {
        ArrayList<HtmlNode> nextChain = fillAncestorsAndSelf(target, scratchChain);
        activeNode = target;
        if (sameChain(activeChain, nextChain)) return false;

        boolean changed = false;
        for (int i = 0, n = activeChain.size(); i < n; i++) {
            HtmlNode released = activeChain.get(i);
            if (!containsRef(nextChain, released)) changed |= state.setActive(released, false);
        }
        for (int i = 0, n = nextChain.size(); i < n; i++) {
            HtmlNode pressed = nextChain.get(i);
            if (!containsRef(activeChain, pressed)) changed |= state.setActive(pressed, true);
        }
        scratchChain = activeChain;
        activeChain = nextChain;
        return changed;
    }

    /**
     * フォーカス中の節を更新する。{@code target} が {@code :focus}、{@code target} とその祖先が
     * {@code :focus-within}、前にフォーカスされていた節は外れる。null でフォーカスを外す。
     */
    boolean setFocusedNode(HtmlNode target, boolean focusVisible) {
        boolean changed = false;

        if (focusedNode != target) {
            if (focusedNode != null) {
                StateFlags oldFlags = state.getOrCreate(focusedNode);
                if (oldFlags.setBit(ElementState.FOCUS | ElementState.FOCUS_VISIBLE, false)) {
                    state.invalidateState(focusedNode, ElementState.FOCUS | ElementState.FOCUS_VISIBLE);
                    changed = true;
                }
            }
            focusedNode = target;
        }

        if (target != null) {
            changed |= state.setFocused(target, true, focusVisible);
        }

        ArrayList<HtmlNode> nextChain = fillAncestorsAndSelf(target, scratchChain);
        for (int i = 0, n = focusWithinChain.size(); i < n; i++) {
            HtmlNode left = focusWithinChain.get(i);
            if (containsRef(nextChain, left)) continue;
            StateFlags flags = state.getOrCreate(left);
            if (flags.setBit(ElementState.FOCUS_WITHIN, false)) {
                state.invalidateState(left, ElementState.FOCUS_WITHIN);
                changed = true;
            }
        }
        for (int i = 0, n = nextChain.size(); i < n; i++) {
            HtmlNode entered = nextChain.get(i);
            StateFlags flags = state.getOrCreate(entered);
            if (flags.setBit(ElementState.FOCUS_WITHIN, true)) {
                state.invalidateState(entered, ElementState.FOCUS_WITHIN);
                changed = true;
            }
        }
        scratchChain = focusWithinChain;
        focusWithinChain = nextChain;
        return changed;
    }

    /** 節を Top Layer の最前面へ積む（{@code on}）か、外す。 */
    void stackTopLayer(HtmlNode node, boolean on) {
        removeIdentity(topLayerOrder, node);
        if (on) topLayerOrder.add(node);
    }

    /** 最前面で開いているモーダル {@code <dialog>}。無ければ null。 */
    HtmlNode activeModalDialog() {
        for (int i = topLayerOrder.size() - 1; i >= 0; i--) {
            HtmlNode n = topLayerOrder.get(i);
            if (state.stateOf(n).modal() && rootOf(n) instanceof HtmlDocument) return n;
        }
        return null;
    }

    /**
     * 節が不活性（{@code inert} 属性の配下、または最前面モーダル {@code <dialog>} の外側）か。
     * モーダルより後に Top Layer へ積まれた popover 配下は不活性にしない。
     */
    boolean isInert(HtmlNode node) {
        if (node == null) return false;
        HtmlNode topModal = activeModalDialog();
        HtmlNode cur = node;
        HtmlNode root = null;
        while (cur != null) {
            if (cur.attrs != null && cur.attrs.containsKey("inert")) return true;
            if (cur == topModal) return false;
            root = cur;
            cur = cur.parent;
        }
        if (topModal == null) return false;
        if (root != rootOf(topModal)) return false;
        int modalIdx = indexOfIdentity(topLayerOrder, topModal);
        for (int i = modalIdx + 1, n = topLayerOrder.size(); i < n; i++) {
            HtmlNode upper = topLayerOrder.get(i);
            if (state.stateOf(upper).popoverOpen() && isDescendantOrSelf(node, upper)) return false;
        }
        return true;
    }

    /** 不活性になった節からホバー／押下状態を外す（フォーカス解除は焦点イベントを伴うため呼び出し側で行う）。 */
    void clearInertPointerStates() {
        if (hoveredNode != null && isInert(hoveredNode)) setHoveredNode(null);
        if (activeNode != null && isInert(activeNode)) setActiveNode(null);
    }

    void clear() {
        hoveredChain.clear();
        activeChain.clear();
        focusWithinChain.clear();
        scratchChain.clear();
        hoveredNode = null;
        activeNode = null;
        focusedNode = null;
        topLayerOrder.clear();
    }

    private static HtmlNode rootOf(HtmlNode n) {
        HtmlNode cur = n;
        while (cur != null && cur.parent != null) cur = cur.parent;
        return cur;
    }

    private static boolean isDescendantOrSelf(HtmlNode node, HtmlNode ancestor) {
        for (HtmlNode cur = node; cur != null; cur = cur.parent) {
            if (cur == ancestor) return true;
        }
        return false;
    }

    private static void removeIdentity(ArrayList<HtmlNode> list, HtmlNode target) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) == target) list.remove(i);
        }
    }

    private static int indexOfIdentity(ArrayList<HtmlNode> list, HtmlNode target) {
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i) == target) return i;
        }
        return -1;
    }

    private static ArrayList<HtmlNode> fillAncestorsAndSelf(HtmlNode node, ArrayList<HtmlNode> out) {
        out.clear();
        for (HtmlNode cur = node; cur != null; cur = cur.parent) {
            out.add(cur);
        }
        return out;
    }

    private static boolean sameChain(ArrayList<HtmlNode> a, ArrayList<HtmlNode> b) {
        int n = a.size();
        if (n != b.size()) return false;
        for (int i = 0; i < n; i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    private static boolean containsRef(ArrayList<HtmlNode> list, HtmlNode target) {
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i) == target) return true;
        }
        return false;
    }
}
