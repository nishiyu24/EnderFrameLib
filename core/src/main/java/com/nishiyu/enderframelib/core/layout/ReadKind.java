package com.nishiyu.enderframelib.core.layout;

/**
 * レイアウト（測定・配置）が外から読む状態の統一台帳（設計書 {@code DESIGN_INCREMENTAL_KERNEL.md} §3.1・K1）。
 *
 * <p>測定の入力（9 種）・親が子から読む出力（9 種）・配置の外部入力（9 種）の計 27 種を 1 か所に登録し、
 * 各入口（{@link MeasureReads}・{@link LayoutReads}）がこの種別で読み取りを自動記録する。
 * 32-bit {@code int} のビット集合（{@link #globalBit()}）に収まる。</p>
 */
enum ReadKind {
    // ---- 測定の入力（MeasureReads.READ_* と 1:1）----
    AVAIL_INLINE(Stage.MEASURE_INPUT, 1 << 0, Source.CONSTRAINT_SPACE, ScopeRule.PARENT_INPUT),
    PERCENT_BLOCK(Stage.MEASURE_INPUT, 1 << 1, Source.CONSTRAINT_SPACE, ScopeRule.SUBTREE_PROPAGATED),
    SHRINK(Stage.MEASURE_INPUT, 1 << 2, Source.CONSTRAINT_SPACE, ScopeRule.PARENT_INPUT),
    STRETCH(Stage.MEASURE_INPUT, 1 << 3, Source.CONSTRAINT_SPACE, ScopeRule.PARENT_INPUT),
    VIEWPORT_W(Stage.MEASURE_INPUT, 1 << 4, Source.VIEWPORT, ScopeRule.SUBTREE_PROPAGATED),
    VIEWPORT_H(Stage.MEASURE_INPUT, 1 << 5, Source.VIEWPORT, ScopeRule.SUBTREE_PROPAGATED),
    SHAPE_EPOCH(Stage.MEASURE_INPUT, 1 << 6, Source.EXTERNAL_EPOCH, ScopeRule.GLOBAL_EPOCH),
    FIT_BOUNDED(Stage.MEASURE_INPUT, 1 << 7, Source.CONSTRAINT_SPACE, ScopeRule.FIT_BOUNDED),
    FILL_FIT(Stage.MEASURE_INPUT, 1 << 8, Source.CONSTRAINT_SPACE, ScopeRule.FILL_FIT),

    // ---- 親が子から読む出力（MeasureReads.PARENT_READ_* と 1:1）----
    PARENT_OUTER(Stage.MEASURE_PARENT, 1 << 0, Source.CHILD_OUTPUT, ScopeRule.PARENT_INPUT),
    PARENT_CONTENT_EXTENT(Stage.MEASURE_PARENT, 1 << 1, Source.CHILD_OUTPUT, ScopeRule.PARENT_INPUT),
    PARENT_MIN_CONTENT(Stage.MEASURE_PARENT, 1 << 2, Source.CHILD_OUTPUT, ScopeRule.PARENT_INPUT),
    PARENT_FIRST_BASELINE(Stage.MEASURE_PARENT, 1 << 3, Source.CHILD_OUTPUT, ScopeRule.PARENT_INPUT),
    PARENT_LAST_BASELINE(Stage.MEASURE_PARENT, 1 << 4, Source.CHILD_OUTPUT, ScopeRule.PARENT_INPUT),
    PARENT_FIT_NEED(Stage.MEASURE_PARENT, 1 << 5, Source.CHILD_OUTPUT, ScopeRule.PARENT_INPUT),
    PARENT_STRETCHED(Stage.MEASURE_PARENT, 1 << 6, Source.CHILD_OUTPUT, ScopeRule.PARENT_INPUT),
    PARENT_MULTI_SPACE(Stage.MEASURE_PARENT, 1 << 7, Source.CHILD_OUTPUT, ScopeRule.PARENT_INPUT),
    PARENT_OPAQUE(Stage.MEASURE_PARENT, 1 << 8, Source.CHILD_OUTPUT, ScopeRule.PARENT_INPUT),

    // ---- 配置の外部入力（K1・K2）----
    PLACE_ORIGIN(Stage.PLACEMENT, 1 << 0, Source.ORIGIN, ScopeRule.PARENT_INPUT),
    PLACE_PARENT_OUTER(Stage.PLACEMENT, 1 << 1, Source.PARENT_BOX, ScopeRule.PARENT_INPUT),
    PLACE_SELF_SCROLL(Stage.PLACEMENT, 1 << 2, Source.SELF_SCROLL, ScopeRule.SELF_LOCAL),
    PLACE_DESCENDANT_SCROLL(Stage.PLACEMENT, 1 << 3, Source.SCROLL_TREE, ScopeRule.SUBTREE_PROPAGATED),
    PLACE_CONTAINING_BLOCK(Stage.PLACEMENT, 1 << 4, Source.CONTAINING_BLOCK, ScopeRule.ABSORB_IF_INSIDE_SUBTREE),
    PLACE_VIEWPORT(Stage.PLACEMENT, 1 << 5, Source.VIEWPORT, ScopeRule.SUBTREE_PROPAGATED),
    PLACE_ANCHOR(Stage.PLACEMENT, 1 << 6, Source.ANCHOR, ScopeRule.ABSORB_IF_INSIDE_SUBTREE),
    PLACE_FLOAT(Stage.PLACEMENT, 1 << 7, Source.FLOAT_SPACE, ScopeRule.PARENT_OR_SIBLING_FLOW),
    PLACE_STICKY(Stage.PLACEMENT, 1 << 8, Source.STICKY_PORT, ScopeRule.ABSORB_IF_INSIDE_SUBTREE);

    enum Stage { MEASURE_INPUT, MEASURE_PARENT, PLACEMENT }

    enum Source {
        CONSTRAINT_SPACE, VIEWPORT, EXTERNAL_EPOCH, CHILD_OUTPUT,
        ORIGIN, PARENT_BOX, SELF_SCROLL, SCROLL_TREE,
        CONTAINING_BLOCK, ANCHOR, FLOAT_SPACE, STICKY_PORT
    }

    enum ScopeRule {
        /** 親が呼び出し時に渡す値（原点・親の外寸・制約空間）。部分木の外向き集約では親の入力として扱う。 */
        PARENT_INPUT,
        /** 箱自身のスクロール量。親へは {@link #PLACE_DESCENDANT_SCROLL} として伝播する。 */
        SELF_LOCAL,
        /** 部分木の中から外へ必ず伝播する依存（ビューポート・% 基準・子孫のスクロール）。 */
        SUBTREE_PROPAGATED,
        /** 読む元（包含ブロック・アンカー・sticky のスクロールコンテナ）が部分木の内側なら外への依存にしない。 */
        ABSORB_IF_INSIDE_SUBTREE,
        /** 全体の形・float の世代。 */
        GLOBAL_EPOCH,
        /** shrink-to-fit / fit-content の上界判定。 */
        FIT_BOUNDED,
        /** fill 幅で再測定なしに追従できる判定。 */
        FILL_FIT,
        /** 親または兄弟の BFC float 空間。 */
        PARENT_OR_SIBLING_FLOW
    }

    private final Stage stage;
    private final int stageBit;
    private final Source source;
    private final ScopeRule scopeRule;

    ReadKind(Stage stage, int stageBit, Source source, ScopeRule scopeRule) {
        this.stage = stage;
        this.stageBit = stageBit;
        this.source = source;
        this.scopeRule = scopeRule;
    }

    Stage stage() { return stage; }

    int stageBit() { return stageBit; }

    int globalBit() { return 1 << ordinal(); }

    Source source() { return source; }

    ScopeRule scopeRule() { return scopeRule; }

    /** 子で起きた配置読み取りのうち、親の部分木へ外向きに伝播させるビット集合を返す。 */
    static int propagatePlacementToParent(int childMask) {
        int out = childMask & (PLACE_DESCENDANT_SCROLL.stageBit
                | PLACE_VIEWPORT.stageBit
                | PLACE_CONTAINING_BLOCK.stageBit
                | PLACE_ANCHOR.stageBit
                | PLACE_STICKY.stageBit);
        if ((childMask & PLACE_SELF_SCROLL.stageBit) != 0) {
            out |= PLACE_DESCENDANT_SCROLL.stageBit;
        }
        return out;
    }

    /**
     * スコープ解決規則 {@link ScopeRule#ABSORB_IF_INSIDE_SUBTREE}:
     * 読む対象 {@code target} が部分木の根 {@code subtreeRoot} の内側（自身または子孫）にあれば true。
     */
    static boolean isInsideSubtree(LayoutBox subtreeRoot, LayoutBox target) {
        if (subtreeRoot == null || target == null) return false;
        for (LayoutBox cur = target; cur != null; cur = cur.parent) {
            if (cur == subtreeRoot) return true;
        }
        return false;
    }
}
