package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.List;

/**
 * 1 つの描画先で、前に描いたフレームから描き直す範囲のうちシーンから決まる分（M3b-4b-4b）。シーンの違い（{@link SceneDamage}）に、
 * 塗りの色・利用者の平行移動（{@link SceneSource#userOffsetsVersion}）の変化と差分描画の無効化（{@code SOFT_DAMAGE}）を全面として足す。
 * 補間・外の事情で変わる項目と重ね描きの分は走査器が足す（{@link SceneWalker}）。
 *
 * <p>描く前の判定（{@link #plan}。{@code RenderBackend#needsRepaint}）で求めた範囲は、同じ入力の次の {@link #take} がそのまま使う。
 * シーンの比べる相手は 1 フレームに 1 度だけ進める（2 度進めると 2 度目は違いが空になり、描き直す範囲を失う）。描画器が 1 つ持つ。
 * スレッドセーフではない。
 */
public final class FrameDamage {
    private final SceneDamage scene = new SceneDamage();
    private int prevClear;
    private int prevUserOffsets = -1;
    private SceneDamage.Rects planned;
    private SceneSource plannedSource;
    private List<DisplayItem> plannedItems;

    /** 描く前に範囲を求める（描くなら続けて同じ入力で {@link #take} を呼ぶ。描かないなら {@link #dropPlan}）。 */
    public SceneDamage.Rects plan(List<DisplayItem> items, SceneSource source, int clearColor) {
        planned = compute(source, clearColor);
        plannedSource = source;
        plannedItems = items;
        return planned;
    }

    /**
     * 描くフレームの範囲。直前の {@link #plan} と同じ入力（同じ列・同じシーンの束）ならその結果。違う入力なら今の入力で比べる相手を進めて全面
     * （計画で進めた比べる相手は描かなかった入力のものなので、違いを信じない）。シーンが無ければ null（全面）で、比べる相手を捨てる。
     */
    public SceneDamage.Rects take(List<DisplayItem> items, SceneSource source, int clearColor) {
        SceneDamage.Rects p = planned;
        boolean same = p != null && plannedSource == source && plannedItems == items;
        dropPlan();
        if (source == null) {
            reset();
            return null;
        }
        if (same) return p;
        SceneDamage.Rects r = compute(source, clearColor);
        if (p != null) r.setFull("plan-mismatch");
        return r;
    }

    /** 計画を捨てる（描かないと決めたとき）。比べる相手は計画した入力のまま（描いた絵と同じだと分かったので）。 */
    public void dropPlan() {
        planned = null;
        plannedSource = null;
        plannedItems = null;
    }

    /** 比べる相手を捨てる（次は全面）。描画先が不定になったとき（例外・別の描画先・シーンの無いフレーム）。 */
    public void reset() {
        scene.reset();
        prevUserOffsets = -1;
        dropPlan();
    }

    private SceneDamage.Rects compute(SceneSource source, int clearColor) {
        SceneDamage.Rects rects = scene.next(source.scene(), source.dynamic());
        if (clearColor != prevClear) rects.setFull("clear-color");
        if (!com.nishiyu.enderframelib.core.kernel.Shortcut.SOFT_DAMAGE.on()) rects.setFull("damage-off");
        if (source.userOffsetsVersion() != prevUserOffsets) rects.setFull("user-offsets");
        prevClear = clearColor;
        prevUserOffsets = source.userOffsetsVersion();
        return rects;
    }
}
