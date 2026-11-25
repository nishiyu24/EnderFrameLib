package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;

/**
 * 同じ BFC の float と流れの箱（Engine v3 M2b-1。旧 {@code FlowBuilder} の {@code floatSpace} の受け渡しと BFC の箱の回避の移植）。
 * 除外領域 {@link Floats.Space} は不変なので、子へは自分の上端基準へ動かした写しを {@link ConstraintSpace#exclusions} で渡し、
 * 子が置いた float は子の断片の {@link LayoutFragment#floatsOut} から受け取って足す（共有の可変の領域を持たない）。
 *
 * <ul>
 *   <li>独立した整形コンテキストを作らないブロック（{@link #intrude}）: 箱は float に重なって置かれ、中の行が float を避ける
 *       （CSS 2.2 §9.5）。子の上端は余白の相殺で決まるので、自分の上の余白で見積もって整形し、子孫の余白が抜けて上端が違えば
 *       正しい上端の座標で整形し直す（1 回。LayoutNG の BFC の位置が後で決まる場合と同じ）。</li>
 *   <li>独立した整形コンテキストの根・置換要素・flex・grid・表（{@link #place}）: border-box が float の margin-box と重ならない。
 *       幅が auto なら float の間の残り幅に縮み、残り幅に入らなければ次に float が終わる所まで下がる。</li>
 * </ul>
 */
final class FloatAvoid {
    private FloatAvoid() { }

    /** 外の float と重ならずに置く箱か（独立した整形コンテキストの根・置換要素・ブロック以外の表示型）。 */
    static boolean avoids(BoxNode child) {
        return child.replaced != null || child.kind != BoxKind.BLOCK || BoxGeometry.establishesBfc(child.style);
    }

    /**
     * 独立した整形コンテキストを作らないブロックの子を、除外領域 {@code exc}（チャンク基準）を渡して整形し {@code b} に足す。
     * 子が置いた float を足した領域を返す。
     */
    static Floats.Space intrude(BoxNode child, ConstraintSpace sp, Floats.Space exc, BlockFlow.ChunkBuilder b, LayoutFragment prev,
                                LayoutSession s) {
        BoxGeometry cg = new BoxGeometry(child.style, sp.availableWidth, s);
        float y = b.peekY(Math.max(0, cg.mT), Math.min(0, cg.mT));
        LayoutFragment f = s.cache().layout(child, sp.withExclusions(exc.translate(0, -y)), prev, s);
        if (!f.collapseThrough) {
            float actual = b.peekY(f.topPos, f.topNeg);
            if (actual != y) { // 子孫の余白が相殺で抜けて上端が見積もりと違った: 正しい上端の座標で整形し直す
                y = actual;
                f = s.cache().layout(child, sp.withExclusions(exc.translate(0, -y)), f, s);
            }
        }
        b.add(f, 0);
        return f.floatsOut.isEmpty() ? exc : exc.merge(f.floatsOut.translate(f.marginLeft, y));
    }

    /** float と重ならない箱を、残り幅に縮めるか float の下へ下げて {@code b} に足す。 */
    static void place(BoxNode child, ConstraintSpace sp, Floats.Space exc, BlockFlow.ChunkBuilder b, LayoutFragment prev,
                      LayoutSession s) {
        float cw = sp.availableWidth;
        BoxGeometry cg = new BoxGeometry(child.style, cw, s);
        float predicted = b.peekY(Math.max(0, cg.mT), Math.min(0, cg.mT)), y = predicted;
        boolean autoW = child.replaced == null && Float.isNaN(cg.specifiedWidth(child, cw, cw - cg.mL - cg.mR, s));
        LayoutFragment f = s.cache().layout(child, sp, prev, s);
        float x = 0, used = cw;
        for (int guard = 0; guard < 64; guard++) {
            float h = Math.max(f.height, 0.01f);
            if (!exc.intrudes(y, y + h)) {
                if (used != cw) { // 下げた・縮めた後で float に掛からなくなった: 元の幅で整形し直す
                    used = cw;
                    f = s.cache().layout(child, sp, f, s);
                    continue;
                }
                x = 0;
                break;
            }
            float[] av = exc.available(y, y + h, cw);
            float avail = Math.max(0, av[1] - av[0]);
            if (autoW && Math.abs(avail - used) > 0.01f) {
                used = avail;
                f = s.cache().layout(child, s.space(avail, sp.fixedWidth, sp.fixedHeight, sp.percentHeightBase, sp.flags), f, s);
                continue;
            }
            if (cg.mL + f.width + cg.mR <= avail + 0.01f) {
                x = av[0];
                break;
            }
            float next = exc.nextBottom(y); // 入らない: 次に float が終わる所まで下げる
            if (next == Float.POSITIVE_INFINITY) {
                x = av[0];
                break;
            }
            y = next;
        }
        if (y != predicted) b.placeAt(y, f);
        b.add(f, x);
    }
}
