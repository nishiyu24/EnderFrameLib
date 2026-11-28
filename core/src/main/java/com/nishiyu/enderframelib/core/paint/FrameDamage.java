package com.nishiyu.enderframelib.core.paint;



/**
 * 1 つの描画先で、前に描いたフレームから描き直す範囲のうちシーンから決まる分（M3b-4b-4b）。シーンの違い（{@link SceneDamage}）に、
 * 塗りの色・利用者の平行移動（{@link SceneSource#userOffsetsVersion}）の変化と、描画器が差分描画を切ったこと（{@link #partialRepaint}）を全面として足す。
 * 補間・外の事情で変わる項目と重ね描きの分は走査器が足す（{@link SceneWalker}）。
 *
 * <p>描画器の準備（{@link RenderBackend#prepare}）が 1 フレームに 1 度だけ {@link #next} で比べる相手を進め、範囲を準備した値
 * （{@link PreparedFrame}）に持たせる（M3b-4c-2a で plan → take を削除）。描画器が 1 つ持つ。スレッドセーフではない。</p>
 */
public final class FrameDamage {
    private final SceneDamage scene = new SceneDamage();
    private int prevClear;
    private int prevUserOffsets = -1;
    private boolean partial = true;
    /** 最後に準備したフレーム（{@link #take} がこれでなければ全面。1 度使ったら捨てる）。 */
    private PreparedFrame last;

    /** 差分描画を切り替える（描画器ごと。切ると毎フレーム全面。比較の基準・切り分け用。絵は同じでなければならない）。 */
    public void partialRepaint(boolean on) { partial = on; }

    /**
     * フレーム {@code frame} の描き直す範囲を求め、比べる相手をこのフレームへ進める。シーンが無ければ null（全面）で、比べる相手を捨てる。
     */
    public SceneDamage.Rects next(RenderFrame frame, int clearColor) {
        SceneSource source = frame.scene();
        if (source == null) {
            reset();
            return null;
        }
        SceneDamage.Rects rects = scene.next(source.scene(), source.dynamic());
        if (clearColor != prevClear) rects.setFull("clear-color");
        if (!partial) rects.setFull("partial-off");
        if (source.userOffsetsVersion() != prevUserOffsets) rects.setFull("user-offsets");
        prevClear = clearColor;
        prevUserOffsets = source.userOffsetsVersion();
        return rects;
    }

    /**
     * 描画器の準備（{@link RenderBackend#prepare}）: シーンの違いの範囲を求め、描き直しが要るかをその範囲と {@code outside}（走査器のシーンの外の
     * 変化。{@link SceneWalker#changesOutsideScene}）で答える。シーンが無ければ全面。
     */
    public PreparedFrame prepare(RenderFrame f, int clearColor, java.util.function.Predicate<RenderFrame> outside) {
        SceneDamage.Rects rects = f.isEmpty() ? null : next(f, clearColor);
        return last = new PreparedFrame(f, rects, rects == null || !rects.isEmpty() || outside.test(f), null);
    }

    /** 準備した値の範囲（最後に準備した値でなければ比べる相手を今のフレームへ進めて全面。1 度使ったら捨てる）。 */
    public SceneDamage.Rects take(PreparedFrame p, int clearColor) {
        boolean mine = p == last;
        last = null;
        if (mine) return p.damage();
        SceneDamage.Rects r = p.frame().isEmpty() ? null : next(p.frame(), clearColor);
        if (r != null) r.setFull("not-prepared");
        return r;
    }

    /** 比べる相手を捨てる（次は全面）。描画先が不定になったとき（例外・別の描画先・シーンの無いフレーム）。 */
    public void reset() {
        scene.reset();
        prevUserOffsets = -1;
    }
}
