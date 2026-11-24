package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxChunk;

/**
 * 子のチャンク 1 つ分のレイアウト結果（Engine v3 M2a）。不変。
 *
 * <p>ブロックの流れでは {@code (source ==, space.equals) → FlowChunk} を 1 層で使い回す。位置はチャンクの先頭の子の
 * border-box の上端を 0 とした相対値で、チャンクの前後の余白（{@code lead*} / {@code trail*}）は相殺前のまま外へ出す。
 * 親がチャンクを並べるときに前のチャンクの {@code trail} と組み合わせる（チャンク境界を跨ぐ余白の相殺）。
 * こうするとチャンクの中身は前のチャンクに依らないので、前に 1 件足しても後ろのチャンクは同じ参照のまま使える。</p>
 *
 * <p>flex・混在した流れでは {@link #source} は null（チャンク単位では使い回さない）で、位置は内容ボックス基準。
 * {@link #slots} は 2 パス測定で子を測った全ての断片（子ごとに最大 {@link LayoutCache#SLOTS} 個。無ければ null）。</p>
 */
public final class FlowChunk {
    public final BoxChunk source;
    public final ConstraintSpace space;
    public final LayoutFragment[] frags;
    public final float[] x, y;
    public final float height;
    public final float leadPos, leadNeg, trailPos, trailNeg;
    /** 中身が全て高さ 0 で余白が素通しになるチャンクか（{@code lead} に全部を入れてある）。 */
    public final boolean through;
    public final LayoutFragment[][] slots;
    /** 流れの外の子孫の静的位置（x は内容ボックス基準、y はチャンクの上端基準。無ければ null）。 */
    final OutOfFlow.Pending[] pending;

    FlowChunk(BoxChunk source, ConstraintSpace space, LayoutFragment[] frags, float[] x, float[] y, float height,
              float leadPos, float leadNeg, float trailPos, float trailNeg, boolean through, LayoutFragment[][] slots,
              OutOfFlow.Pending[] pending) {
        this.source = source;
        this.space = space;
        this.frags = frags;
        this.x = x;
        this.y = y;
        this.height = height;
        this.leadPos = leadPos;
        this.leadNeg = leadNeg;
        this.trailPos = trailPos;
        this.trailNeg = trailNeg;
        this.through = through;
        this.slots = slots;
        this.pending = pending;
    }

    /** この制約で並べた、このチャンクの結果か。 */
    boolean matches(BoxChunk c, ConstraintSpace s) {
        return source == c && (space == s || space.equals(s));
    }
}
