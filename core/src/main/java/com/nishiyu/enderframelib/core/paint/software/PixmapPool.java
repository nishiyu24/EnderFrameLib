package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.util.ArrayDeque;

/**
 * 同じ大きさの作業用 {@link Pixmap}（グループの層・マスク・背景の複写）の貸し借り。
 * 返された画素は<b>透明に戻してから</b>返す約束（借りた側は透明な画素を受け取る）。
 * 描画は 1 スレッドなので同期しない。以前は {@code SoftwareRenderBackend} が {@code ArrayDeque} の
 * {@code pollFirst() ?: new Pixmap(w, h)} と {@code addFirst} を 8 箇所に手書きしていた。
 */
final class PixmapPool {
    private final int width, height;
    private final ArrayDeque<Pixmap> idle = new ArrayDeque<>();
    private int created;

    PixmapPool(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** 透明な画素を 1 枚借りる。空きがなければ作る。 */
    Pixmap take() {
        Pixmap p = idle.pollFirst();
        if (p != null) return p;
        created++;
        return new Pixmap(width, height);
    }

    /** 透明に戻した画素を返す。大きさが違うものは受け付けない。 */
    void give(Pixmap p) {
        if (p.width() != width || p.height() != height) throw new IllegalArgumentException("大きさが違う: " + p.width() + "x" + p.height());
        idle.addFirst(p);
    }

    /** 空き（返却済み）の枚数。 */
    int idleCount() { return idle.size(); }

    /** 作った枚数の累計（診断・テスト用）。 */
    int createdCount() { return created; }
}
