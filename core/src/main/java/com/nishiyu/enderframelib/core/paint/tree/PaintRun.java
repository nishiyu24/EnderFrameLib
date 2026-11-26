package com.nishiyu.enderframelib.core.paint.tree;

import com.nishiyu.enderframelib.core.css.ComputedStyle;

/**
 * 行に置かれた箱の断片 1 つ（文字の語、またはインライン要素の行ごとの箱。M2c ②）。{@link PaintBox#runs} が返す。座標は
 * {@link PaintBox#originParent} の左上が原点。実装は描画用の木の {@code TreeRun}。
 */
public interface PaintRun {
    float x();
    float y();
    float w();
    float h();

    /** 断片の上端からベースラインまで（px）。 */
    float baseline();

    /** 文字の断片なら語、そうでなければ null。 */
    String text();

    default boolean isText() {
        return text() != null;
    }

    /** 箱の開始端（左の border・padding・角丸）を持つか（行をまたぐ箱は最初の断片だけ）。 */
    boolean startEdge();

    /** 箱の終了端（右）を持つか（行をまたぐ箱は最後の断片だけ）。 */
    boolean endEdge();

    /** 1 行目の断片なら、{@code target}（この断片の持ち主の箱）に効く {@code ::first-line} の算出スタイル（無ければ null）。 */
    ComputedStyle firstLineStyleFor(PaintBox target);
}
