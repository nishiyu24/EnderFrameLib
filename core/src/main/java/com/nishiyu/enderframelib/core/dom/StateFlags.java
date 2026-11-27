package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.ContainerBox;
import com.nishiyu.enderframelib.core.css.model.ContainerType;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1 つのビュー（{@link InteractionState}）が 1 つの節について持つ状態: 明示された対話状態のビット・算出スタイルの記憶・
 * 再スタイルの汚れの印・問い合わせコンテナの寸法（M5-2 で {@code InteractionState} の入れ子から出した）。
 */
public final class StateFlags {
    /**
     * 明示された対話状態。{@link ElementState} のビット（HOVER / ACTIVE / FOCUS / FOCUS_VISIBLE / FOCUS_WITHIN /
     * CHECKED / DISABLED / INDETERMINATE / OPEN）が唯一の記憶。
     */
    public int state;
    /** {@link #state} のうち CHECKED / DISABLED / INDETERMINATE / OPEN は 3 値（未指定・真・偽）。指定済みのビット。 */
    public int overridden;

    /** 確定済みの算出スタイルキャッシュ。 */
    public ComputedStyle computedStyle;
    /** 算出時の親フォントサイズ（px）。 */
    public float cachedParentFontSize = -1f;
    /** 算出時のルート要素のフォントサイズ（px、rem の基準）。 */
    public float cachedRootFontSize = -1f;
    /** 算出時のルート要素の行の高さ（px、rlh の基準）。 */
    public float cachedRootLineHeight = -1f;
    /** 算出時のビューポート。font-size の vw / vh が算出値に入るので、変わったら再スタイルする。 */
    public int cachedViewportW = -1;
    public int cachedViewportH = -1;
    /**
     * 宣言値がビューポートの大きさに依存し得るか（{@code vw} / {@code cqw} などの単位、{@code var()}）。
     * 依存しない要素は、ビューポートが変わっても算出スタイルのキャッシュをそのまま使う。
     */
    public boolean viewportDependent = true;
    /** {@link #viewportDependent} を一度でも確かめたか（作りたては未確認で、依存するとみなす）。 */
    private boolean viewportDependenceKnown;

    /**
     * 要素のビューポート依存を記録する。ルート以外で依存が増えた・初めて確かめたときは、持ち主の世代
     * （{@link InteractionState#viewportDependenceEpoch}）を進めて「依存する要素は無い」の記憶を捨てさせる。
     */
    public void noteViewportDependence(boolean root, boolean dependent) {
        if (!root && (!viewportDependenceKnown || viewportDependent != dependent) && owner != null) owner.viewportDependenceEpoch.incrementAndGet();
        viewportDependenceKnown = true;
        viewportDependent = dependent;
    }

    public boolean viewportDependenceKnown() { return viewportDependenceKnown; }
    /** {@code content-visibility: auto} で直近の配置時に表示域内（非スキップ）だったか、最後に記録された内容寸法。 */
    public boolean contentAutoActive;
    public float lastRememberedWidth = Float.NaN;
    public float lastRememberedHeight = Float.NaN;
    /** この要素に対話状態（:hover 等）のルールが存在するか。 */
    public boolean hasInteractiveRules;
    /**
     * {@link #hasInteractiveRules} を求めたときの入力（シート・規則の版・節の属性の版・親）。入力が同じなら、親の算出スタイルだけが
     * 変わった再スタイル（継承の変化）で求め直さなくてよい。祖先・兄弟・構造の変化は、汚れの印（FORCE_SUBTREE / DIRTY_STYLE）で別に伝わる。
     */
    public Object interactiveSheet;
    public int interactiveVersion;
    public int interactiveAttrVersion;
    public HtmlNode interactiveParent;

    public boolean isHovered() { return (state & ElementState.HOVER) != 0; }
    public boolean isActive() { return (state & ElementState.ACTIVE) != 0; }
    public boolean isFocused() { return (state & ElementState.FOCUS) != 0; }
    public boolean isFocusVisible() { return (state & ElementState.FOCUS_VISIBLE) != 0; }
    public boolean isFocusWithin() { return (state & ElementState.FOCUS_WITHIN) != 0; }

    /** 2 値のビットを書く。変わったら true。 */
    boolean setBit(int bit, boolean on) {
        int next = on ? (state | bit) : (state & ~bit);
        if (next == state) return false;
        state = next;
        return true;
    }

    /** 3 値のビット（未指定は null）。 */
    public Boolean tri(int bit) {
        return (overridden & bit) == 0 ? null : (state & bit) != 0;
    }

    /** 3 値のビットを書く。変わったら true。 */
    boolean setTri(int bit, Boolean value) {
        if (java.util.Objects.equals(tri(bit), value)) return false;
        if (value == null) {
            overridden &= ~bit;
            state &= ~bit;
        } else {
            overridden |= bit;
            state = value ? (state | bit) : (state & ~bit);
        }
        return true;
    }

    /** 持ち主。汚れた節の個数（{@link StyleInvalidation#dirtyCount}）を O(1) で保つ。持ち主の無い使い捨てなら null。 */
    InteractionState owner;

    /** 自身の再スタイルが必要。 */
    public static final int DIRTY_STYLE = 1;
    /**
     * この要素の子孫も、継承元が変わったかどうかによらず再スタイルが要るか
     * （{@code .a:hover .b} のように、祖先の状態・class・属性を読む規則がある）。
     */
    public static final int DIRTY_SUBTREE = 2;
    /**
     * 子孫のどこかが再スタイルの対象（{@link #DIRTY_STYLE} / {@link #DIRTY_SUBTREE}）になっている。汚す側が祖先へ印を付け、
     * 再スタイルの走査はこれが無く自分も汚れていない部分木を丸ごと飛ばす（ブラウザの dirty-bit と同じ）。
     */
    public static final int DIRTY_BELOW = 4;
    /** 影響範囲を絞らずに汚した（{@link InteractionState#markDirty}）。祖先も汚し直す必要がある。 */
    public static final int DIRTY_UNSCOPED = 8;

    /** 汚れ印（{@code DIRTY_*} のビット）の唯一の記憶。作りたては安全側にすべて立つ。 */
    private int dirty = DIRTY_STYLE | DIRTY_SUBTREE | DIRTY_BELOW | DIRTY_UNSCOPED;

    /** 汚れ印の生のビット（検査・テスト用）。 */
    public int dirtyBits() { return dirty; }

    public boolean isStyleDirty() { return (dirty & DIRTY_STYLE) != 0; }
    public boolean subtreeDirty() { return (dirty & DIRTY_SUBTREE) != 0; }
    public boolean dirtyBelow() { return (dirty & DIRTY_BELOW) != 0; }
    public boolean unscoped() { return (dirty & DIRTY_UNSCOPED) != 0; }

    public void markSubtreeDirty() { dirty |= DIRTY_SUBTREE; }
    public void clearSubtreeDirty() { dirty &= ~DIRTY_SUBTREE; }
    public void markDirtyBelow() { dirty |= DIRTY_BELOW; }
    public void clearDirtyBelow() { dirty &= ~DIRTY_BELOW; }
    void markUnscoped() { dirty |= DIRTY_UNSCOPED; }
    public void clearUnscoped() { dirty &= ~DIRTY_UNSCOPED; }

    /** {@link #DIRTY_STYLE} を立てる。書き込みは必ずここを通す（個数の帳簿が合わなくなる）。 */
    public void markStyleDirty() {
        if ((dirty & DIRTY_STYLE) == 0) {
            dirty |= DIRTY_STYLE;
            if (owner != null) owner.invalidation.dirtyCount.incrementAndGet();
        }
    }

    /** {@link #DIRTY_STYLE} を下ろす。 */
    public void clearStyleDirtyFlag() {
        if ((dirty & DIRTY_STYLE) != 0) {
            dirty &= ~DIRTY_STYLE;
            if (owner != null) owner.invalidation.dirtyCount.updateAndGet(v -> v > 0 ? v - 1 : 0);
        }
    }
    /** style 属性が {@code inherit} を含む。親の非継承プロパティの変化が子へ届く。 */
    public boolean inheritsExplicitly;
    /** {@link #inheritsExplicitly} を評価したときの {@code node.attrVersion()}。未評価は -1。 */
    public int inheritsAttrVersion = -1;
    /** 問い合わせコンテナとして測った内容ボックスの寸法（px）。測っていなければ NaN。 */
    public float containerWidth = Float.NaN;
    public float containerHeight = Float.NaN;
    /** {@link #containerBox} の結果の使い回し（算出スタイルと寸法が同じ間だけ有効）。 */
    ComputedStyle containerStyle;
    float containerBoxW, containerBoxH;
    ContainerBox containerBox;

    /**
     * 問い合わせコンテナとしての箱（{@link InteractionState#containerOf}）。算出スタイルがまだ無ければ null。
     * 名前の分割・カスタムプロパティの表は、算出スタイルと寸法が同じ間は使い回す。
     */
    ContainerBox containerBox() {
        if (computedStyle == null) return null;
        ComputedStyle style = computedStyle;
        // 祖先をたどる問い合わせのたびに作り直さない（名前の分割・カスタムプロパティの表は、算出スタイルが変わるまで同じ）
        ContainerBox hit = containerBox;
        if (hit != null && containerStyle == style
                && Float.floatToIntBits(containerBoxW) == Float.floatToIntBits(containerWidth)
                && Float.floatToIntBits(containerBoxH) == Float.floatToIntBits(containerHeight)) {
            return hit;
        }
        Style st = style.typed();
        ContainerType type = switch (st.rare.containerType) {
            case SIZE -> ContainerType.SIZE; case INLINE_SIZE -> ContainerType.INLINE_SIZE; default -> ContainerType.NORMAL;
        };
        String nameList = st.rare.containerName;
        List<String> names;
        if (nameList == null || nameList.isBlank() || "none".equalsIgnoreCase(nameList.trim())) names = List.of();
        else {
            names = new ArrayList<>();
            for (String name : nameList.trim().toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
                if (!name.isEmpty() && !name.equals("none")) names.add(name);
            }
        }
        ContainerBox box = new ContainerBox(containerWidth, containerHeight, type, names,
                st.custom != null ? st.custom.asMap() : Map.of());
        containerStyle = style;
        containerBoxW = containerWidth;
        containerBoxH = containerHeight;
        containerBox = box;
        return box;
    }
}
