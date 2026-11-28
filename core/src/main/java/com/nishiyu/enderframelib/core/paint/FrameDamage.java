package com.nishiyu.enderframelib.core.paint;



/**
 * 1 つの描画先で、前に描いたフレームから描き直す範囲のうちシーンから決まる分（M3b-4b-4b）。シーンの違い（{@link SceneDamage}）に、
 * 塗りの色・利用者の平行移動（{@link SceneSource#userOffsetsVersion}）・描かない節（{@link RenderFrame#exclude}）の変化と、描画器が差分描画を
 * 切ったこと（{@link #partialRepaint}）を全面として足す。
 * 補間・外の事情で変わる項目と重ね描きの分は走査器が足す（{@link SceneWalker}）。
 *
 * <p>描画器の準備（{@link RenderBackend#prepare}）が 1 フレームに 1 度だけ {@link #next} で比べる相手を進め、範囲を準備した値
 * （{@link PreparedFrame}）に持たせる（M3b-4c-2a で plan → take を削除）。描画器が 1 つ持つ。スレッドセーフではない。</p>
 */
public final class FrameDamage {
    private final SceneDamage scene = new SceneDamage();
    /** シーンから表示項目の列を組む（M3b-4c-2b-2b。準備で 1 フレームに 1 度）。 */
    private final FrameComposer composer = new FrameComposer();
    private int prevClear;
    private int prevUserOffsets = -1;
    /** 前のフレームの描かない節（{@link RenderFrame#exclude}。違えば全面）。 */
    private ExcludedNodes prevExclude;
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
        if (!java.util.Objects.equals(frame.exclude(), prevExclude)) rects.setFull("exclude");
        prevClear = clearColor;
        prevUserOffsets = source.userOffsetsVersion();
        prevExclude = frame.exclude();
        return rects;
    }

    /**
     * 描画器の準備（{@link RenderBackend#prepare}）: シーンから列を組み（{@link FrameComposer}）、シーンの違いの範囲を求め、描き直しが要るかを
     * その範囲と {@code outside}（走査器のシーンの外の変化。{@link SceneWalker#changesOutsideScene}）で答える。シーンが無ければ全面。
     */
    public PreparedFrame prepare(RenderFrame f, int clearColor, java.util.function.Predicate<PreparedFrame> outside) {
        FrameComposer.Composed c = composer.compose(f);
        SceneDamage.Rects rects = f.isEmpty() ? null : next(f, clearColor);
        PreparedFrame p = new PreparedFrame(f, c, rects, true, null);
        return last = rects == null || !rects.isEmpty() || outside.test(p) ? p : new PreparedFrame(f, c, rects, false, null);
    }

    /**
     * 描く準備した値: 最後に準備した値ならそのまま、そうでなければ列を組み直し、比べる相手を今のフレームへ進めて全面にした値（1 度使ったら捨てる）。
     */
    public PreparedFrame take(PreparedFrame p, int clearColor) {
        boolean mine = p == last;
        last = null;
        if (mine) return p;
        RenderFrame f = p.frame();
        FrameComposer.Composed c = composer.compose(f);
        SceneDamage.Rects r = f.isEmpty() ? null : next(f, clearColor);
        if (r != null) r.setFull("not-prepared");
        return new PreparedFrame(f, c, r, true, null);
    }

    /** 比べる相手を捨てる（次は全面）。描画先が不定になったとき（例外・別の描画先・シーンの無いフレーム）。 */
    public void reset() {
        scene.reset();
        composer.reset();
        prevUserOffsets = -1;
    }
}
