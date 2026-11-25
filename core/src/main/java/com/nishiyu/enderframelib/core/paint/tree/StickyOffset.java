package com.nishiyu.enderframelib.core.paint.tree;

import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.Position;

/**
 * {@code position: sticky} の貼り付きのずれ（M2c ④。旧 {@code ScrollExtentCalc.applySticky} と同じ規則）。スクロール量はレイアウトの入力に
 * しないので、貼り付きもスクロールの位置を足した見え方（{@link PaintView}）の側で決める: いちばん近いスクロールコンテナ（無ければ
 * ビューポート）の内側の縁に {@code top}・{@code bottom}・{@code left}・{@code right} だけ近づいたら止め、親の内容の箱の外へは出さない。
 */
final class StickyOffset {
    private StickyOffset() { }

    static boolean isSticky(PaintNode n) {
        return n.style != null && n.style.typed().box.position == Position.STICKY;
    }

    /**
     * 親 {@code parent} の子 {@code n} を、スクロールの位置を足した {@code (bx, by)} に置いたときの貼り付きのずれ {dx, dy}。
     * {@code vw}, {@code vh} はビューポートの大きさ。
     */
    static float[] of(PaintNode n, PaintView parent, float bx, float by, float vw, float vh) {
        PaintView port = parent;
        while (port != null && !port.isScrollContainer()) port = port.paintParent();
        float portX = port != null ? port.x() + port.bL() : 0f;
        float portY = port != null ? port.y() + port.bT() : 0f;
        float portW = port != null ? Math.max(0, port.w() - port.bL() - port.bR()) : vw;
        float portH = port != null ? Math.max(0, port.h() - port.bT() - port.bB()) : vh;
        float cbTop = parent.y() + parent.bT() + parent.pT();
        float cbBottom = parent.y() + parent.h() - parent.bB() - parent.pB();
        float cbLeft = parent.x() + parent.bL() + parent.pL();
        float cbRight = parent.x() + parent.w() - parent.bR() - parent.pR();
        Style t = n.style.typed();
        float dx = 0, dy = 0;
        if (!CssLength.isAutoOrUndeclared(t.box.top)) {
            float limit = portY + t.boxPx(t.box.top, portH, vw, vh);
            if (by < limit) dy = Math.max(0, Math.min(limit - by, cbBottom - (by + n.h + n.mB)));
        } else if (!CssLength.isAutoOrUndeclared(t.box.bottom)) {
            float limit = portY + portH - t.boxPx(t.box.bottom, portH, vw, vh);
            if (by + n.h > limit) dy = -Math.max(0, Math.min(by + n.h - limit, by - n.mT - cbTop));
        }
        if (!CssLength.isAutoOrUndeclared(t.box.left)) {
            float limit = portX + t.boxPx(t.box.left, portW, vw, vh);
            if (bx < limit) dx = Math.max(0, Math.min(limit - bx, cbRight - (bx + n.w + n.mR)));
        } else if (!CssLength.isAutoOrUndeclared(t.box.right)) {
            float limit = portX + portW - t.boxPx(t.box.right, portW, vw, vh);
            if (bx + n.w > limit) dx = -Math.max(0, Math.min(bx + n.w - limit, bx - n.mL - cbLeft));
        }
        return dx == 0 && dy == 0 ? null : new float[]{dx, dy};
    }
}
