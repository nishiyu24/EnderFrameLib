package com.nishiyu.enderframelib.core.paint;

/**
 * 描く前に準備したフレーム（M3b-4c-2a。{@link RenderBackend#prepare} が返し、{@link RenderBackend#render(PreparedFrame)} が受け取る）。
 * 前に描いたフレームから描き直す範囲と、描き直しが要るかを持つ。描き直しが要らなければ描かずに前の画素を使ってよい（前の画素を持ち続ける描画先）。
 *
 * <p>描画器は最後に準備した値だけを信じる: 別の描画器の値・前に準備した値・2 度目の {@code render} は全面を描く（比べる相手は準備で 1 度だけ進める）。
 * 以前の {@code FrameDamage} の plan → take（別々の入力を実体の同一性で結ぶ）の代わりに、準備した値そのものを渡す。</p>
 */
public final class PreparedFrame {
    private final RenderFrame frame;
    private final SceneDamage.Rects damage;
    private final boolean repaint;
    private final PreparedFrame inner;

    /**
     * @param frame   準備したフレーム
     * @param damage  描き直す範囲（null なら全面）
     * @param repaint 描き直しが要るか
     * @param inner   描画器が中で使う別の描画器の準備（GPU の描画器が CPU の描画器に描かせるとき。無ければ null）
     */
    public PreparedFrame(RenderFrame frame, SceneDamage.Rects damage, boolean repaint, PreparedFrame inner) {
        this.frame = frame;
        this.damage = damage;
        this.repaint = repaint;
        this.inner = inner;
    }

    /** 範囲を求めない描画器の準備（いつも全面を描く）。 */
    public static PreparedFrame full(RenderFrame frame) {
        return new PreparedFrame(frame, null, true, null);
    }

    public RenderFrame frame() {
        return frame;
    }

    /** 描き直しが要るか（偽なら前に描いた画素がこのフレームの絵）。 */
    public boolean needsRepaint() {
        return repaint;
    }

    /** 描き直す範囲（null なら全面）。 */
    public SceneDamage.Rects damage() {
        return damage;
    }

    /** 中で使う別の描画器の準備（無ければ null）。 */
    public PreparedFrame inner() {
        return inner;
    }
}
