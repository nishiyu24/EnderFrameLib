package com.nishiyu.enderframelib.core.paint.model;

/**
 * B12: 中身を 1 つの節にまとめたスクロールコンテナ（{@link ScrollContentInfo}）の中の、{@code position: sticky}（上の inset だけ）の箱。
 * スクロールだけが変わったとき、表示リストを作り直さず、この箱の節に「貼り付きのずれの増減」を移動量として足す。
 * 式はレイアウトの {@code LayoutBox.applyStickyOffset} と同じ（スクロールで中身が {@code Δ} 上へ動くと、貼り付く前の y と、
 * 親が中身の中なら包含ブロックの下端も {@code Δ} だけ上へ動く。上の限界〔スクロールポートの上端 + top〕は動かない）。
 *
 * @param nodeId      sticky の箱の節（{@code NODE}）の項目 id
 * @param limit       上の限界（スクロールポートの上端 + {@code top}）
 * @param cbBottom0   作ったときの包含ブロックの下端
 * @param yUnstuck0   作ったときの、貼り付く前の y
 * @param h           箱の高さ
 * @param marginBottom 下の余白
 * @param parentMoves 親（包含ブロック）がスクロールで動くか（親がスクロールコンテナ自身なら動かない）
 * @param dy0         作ったときのずれ
 */
public record StickyShift(String nodeId, float limit, float cbBottom0, float yUnstuck0, float h, float marginBottom,
                          boolean parentMoves, float dy0) {

    /** スクロールが作ったときから {@code delta}（下へ正）進んだときの、貼り付きのずれ。 */
    public float dyAt(float delta) {
        float y = yUnstuck0 - delta;
        float cbBottom = parentMoves ? cbBottom0 - delta : cbBottom0;
        float dy = 0;
        if (y < limit) dy = Math.max(0, Math.min(limit - y, cbBottom - (y + h + marginBottom)));
        return dy;
    }

    /** 作ったときからの、節に足す移動量（縦）。 */
    public float extraAt(float delta) {
        return dyAt(delta) - dy0;
    }
}
