package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import java.util.List;

/**
 * 断片の記憶の「窓の外の代理」の状態機械（K4g。{@link InkBounds} が境界の計算、これが状態と遷移）。状態は 5 つで、記憶 1 つにつき 1 つだけ持つ。
 *
 * <pre>
 *   組み立てて記憶を作る（{@link #onStore}）:
 *     断片が空でない／窓付きでない                        → NOT_EMPTY   平行移動は代理にならない（位置が変われば作り直す）
 *     空で、項目を持つ箱が部分木に無い                     → FREE        位置に依らない（常に窓の外と同じ）
 *     空で、窓の外で省かれた箱を含む                       → PENDING     境界はまだ作っていない（組み立てごとの確保を避ける）
 *   平行移動が起きて部分木の中が変わっていないとき（{@link #resolved}）:
 *     PENDING                                              → BOUNDED     境界を作れた
 *                                                          → UNBOUNDED   作れなかった（位置指定・sticky の子孫など。代理を使わない）
 *     それ以外                                             → そのまま
 *   窓の外のままか（{@link #outside}）: FREE は常に真、BOUNDED は外接矩形で判定、ほかは偽（保守的）
 * </pre>
 * 状態は記憶を作り直すたびに {@link #onStore} で決め直す。境界を持つのは BOUNDED だけで、ほかの状態は共有の定数。
 */
final class InkState {
    enum Kind { NOT_EMPTY, FREE, PENDING, UNBOUNDED, BOUNDED }

    static final InkState NOT_EMPTY = new InkState(Kind.NOT_EMPTY, null);
    static final InkState FREE = new InkState(Kind.FREE, null);
    static final InkState PENDING = new InkState(Kind.PENDING, null);
    static final InkState UNBOUNDED = new InkState(Kind.UNBOUNDED, null);

    final Kind kind;
    private final float[] rect;

    private InkState(Kind kind, float[] rect) {
        this.kind = kind;
        this.rect = rect;
    }

    /** 記憶を作るときの状態。{@code emptyFragment}: 断片が空、{@code cull}: 窓付きの組み立て、{@code owners}: 部分木の項目を持ちうる箱の数。 */
    static InkState onStore(boolean emptyFragment, boolean cull, int owners) {
        if (!emptyFragment || !cull) return NOT_EMPTY;
        return owners == 0 ? FREE : PENDING;
    }

    /** 平行移動が窓の外で起きても、再利用の候補になりうる状態か。 */
    boolean canTranslate() {
        return kind == Kind.FREE || kind == Kind.PENDING || kind == Kind.BOUNDED;
    }

    /** 境界をまだ作っていなければ今の幾何から作って返す（部分木の中が記憶の時点と同じときだけ呼ぶ）。 */
    InkState resolved(LayoutBox box) {
        if (kind != Kind.PENDING) return this;
        float[] r = InkBounds.compute(box);
        return r != null ? new InkState(Kind.BOUNDED, r) : UNBOUNDED;
    }

    /** 箱の左上が {@code (ax, ay)} に移ったとき、部分木の全ての箱がまだ窓の外か（窓に掛かりうる・不明なら偽）。 */
    boolean outside(float ax, float ay, float rootW, float rootH, float margin) {
        return switch (kind) {
            case FREE -> true;
            case BOUNDED -> InkBounds.stillOutside(rect, ax, ay, rootW, rootH, margin);
            default -> false;
        };
    }

    /** 箱自身の印も子の部分木の印も {@code epoch} 以下（部分木の印が新しいのは、箱自身の平行移動の印だけ）か。 */
    static boolean quiet(LayoutBox box, long epoch) {
        if (box.ownPaintStamp() > epoch) return false;
        List<LayoutBox> ch = box.children;
        for (int i = 0, n = ch.size(); i < n; i++) if (ch.get(i).subtreePaintStamp() > epoch) return false;
        return true;
    }

    @Override public String toString() { return kind.toString(); }
}
