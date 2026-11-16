package com.nishiyu.enderframelib.core.paint.model;

/**
 * {@link DisplayItem} の「変わりやすい欄」（位置・寸法・色・不透明度・変形）の置き場（B9 の M2）。
 * すべての項目が必ず 1 つ持つ。読み出しは {@link DisplayItem} のアクセサが {@code cell} 経由で行うので、消費者は変わらない。
 * 寿命は項目と同じ（GC が管理する）ので、派生・一時の項目を作る箇所でも番号の解放や持ち主の受け渡しは要らない。
 *
 * 書き込み口は塗りの 3 欄だけ（{@link #writePaint}。B9 の M3）。書くたびに {@link #epoch} を進め、観測者は
 * {@link com.nishiyu.enderframelib.core.paint.ItemDiff} の版比較で「見たあとに書かれたか」を知る（前の値は持たない）。
 * 等価性は欄の値で決める（{@link DisplayItem} が record として値で比べられる従来の意味を保つ）。
 */
public final class PaintCell {
    /** 書き込みの通し番号（プロセス全体。0 は一度も書かれていない）。 */
    private static final java.util.concurrent.atomic.AtomicInteger WRITES = new java.util.concurrent.atomic.AtomicInteger();

    final int x, y, width, height;
    final int radius;
    /** 塗りの欄。{@link #writePaint} だけが書く（B9 M3）。 */
    int color, bgColor;
    float opacity;
    /** 最後に塗りの欄が書かれたときの通し番号。観測者は「見た時の値より大きければ変わった」と判定する（{@code ItemDiff}）。 */
    volatile int epoch;
    /** この項目が入っているスナップショットの時計と位置（無ければ null）。書くたびに印を付ける。{@link DisplayItem#attach} が設定する。 */
    ChunkClock clock;
    int pos;
    /**
     * 描画の上だけの平行移動（スクロール・ウィンドウのドラッグ。{@code UiView.setPaintOffset}）。{@link #translateX} に足して読む。
     * レイアウトも表示リストの構築も変えないので、構築側は基準の値（{@code baseTranslateX}）だけを見る（B9 の M4）。
     */
    float offsetX, offsetY;
    /** 最後に姿勢に関わる欄（移動量）が書かれたときの通し番号。姿勢の使い回しを捨てる判定に使う。 */
    volatile int geomEpoch;
    final float translateX, translateY, scaleX, scaleY, rotate;

    public PaintCell(int x, int y, int width, int height, int color, int bgColor, int radius, float opacity,
                     float translateX, float translateY, float scaleX, float scaleY, float rotate) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.color = color;
        this.bgColor = bgColor;
        this.radius = radius;
        this.opacity = opacity;
        this.translateX = translateX;
        this.translateY = translateY;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.rotate = rotate;
    }

    /** 同じ値の別のセル（項目ごとに 1 つ持ち、M3 以降の書き込みが別の項目へ漏れないよう、派生の項目はセルを共有しない）。 */
    PaintCell copy() {
        PaintCell c = new PaintCell(x, y, width, height, color, bgColor, radius, opacity, translateX, translateY, scaleX, scaleY, rotate);
        c.epoch = epoch;
        c.offsetX = offsetX;
        c.offsetY = offsetY;
        c.geomEpoch = geomEpoch;
        return c;
    }

    /** 位置だけ違う写し。 */
    PaintCell withPosition(int newX, int newY) {
        PaintCell c = new PaintCell(newX, newY, width, height, color, bgColor, radius, opacity, translateX, translateY, scaleX, scaleY, rotate);
        c.epoch = epoch;
        c.offsetX = offsetX;
        c.offsetY = offsetY;
        c.geomEpoch = geomEpoch;
        return c;
    }

    PaintCell withBounds(int newX, int newY, int newWidth, int newHeight) {
        PaintCell c = new PaintCell(newX, newY, newWidth, newHeight, color, bgColor, radius, opacity, translateX, translateY, scaleX, scaleY, rotate);
        c.epoch = epoch;
        c.offsetX = offsetX;
        c.offsetY = offsetY;
        c.geomEpoch = geomEpoch;
        return c;
    }

    /** 今までに書かれた回数の通し番号（スナップショットがこれを覚え、これより新しい書き込みを「変わった」と見分ける）。 */
    public static int currentEpoch() {
        return WRITES.get();
    }

    /**
     * 塗りの欄を書く。値が変わらなければ何もしない。変わったら通し番号を進めて true。
     * 書けるのは塗りの 3 欄（色・背景色・不透明度）だけで、位置・寸法・変形は項目を作り直す（M4 以降）。
     */
    boolean writePaint(int newColor, int newBgColor, float newOpacity) {
        if (color == newColor && bgColor == newBgColor && Float.compare(opacity, newOpacity) == 0) return false;
        color = newColor;
        bgColor = newBgColor;
        opacity = newOpacity;
        int e = WRITES.incrementAndGet();
        epoch = e;
        if (clock != null) clock.mark(pos, e, true);
        return true;
    }

    /** 描画の上だけの平行移動を書く。値が変わらなければ何もしない。変わったら通し番号を進めて true。 */
    boolean writeOffset(float dx, float dy) {
        if (Float.compare(offsetX, dx) == 0 && Float.compare(offsetY, dy) == 0) return false;
        offsetX = dx;
        offsetY = dy;
        int e = WRITES.incrementAndGet();
        epoch = e;
        geomEpoch = e;
        if (clock != null) clock.mark(pos, e, false);
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaintCell c)) return false;
        return x == c.x && y == c.y && width == c.width && height == c.height
                && color == c.color && bgColor == c.bgColor && radius == c.radius
                && Float.compare(opacity, c.opacity) == 0
                && Float.compare(translateX, c.translateX) == 0 && Float.compare(translateY, c.translateY) == 0
                && Float.compare(scaleX, c.scaleX) == 0 && Float.compare(scaleY, c.scaleY) == 0
                && Float.compare(rotate, c.rotate) == 0
                && Float.compare(offsetX, c.offsetX) == 0 && Float.compare(offsetY, c.offsetY) == 0;
    }

    @Override
    public int hashCode() {
        int h = x;
        h = 31 * h + y;
        h = 31 * h + width;
        h = 31 * h + height;
        h = 31 * h + color;
        h = 31 * h + bgColor;
        h = 31 * h + radius;
        h = 31 * h + Float.hashCode(opacity);
        h = 31 * h + Float.hashCode(translateX);
        h = 31 * h + Float.hashCode(translateY);
        h = 31 * h + Float.hashCode(scaleX);
        h = 31 * h + Float.hashCode(scaleY);
        h = 31 * h + Float.hashCode(rotate);
        h = 31 * h + Float.hashCode(offsetX);
        h = 31 * h + Float.hashCode(offsetY);
        return h;
    }

    @Override
    public String toString() {
        return "PaintCell[x=" + x + ", y=" + y + ", w=" + width + ", h=" + height + ", color=" + color + ", bg=" + bgColor
                + ", radius=" + radius + ", opacity=" + opacity + ", t=(" + translateX + "," + translateY + "), s=("
                + scaleX + "," + scaleY + "), rotate=" + rotate + ", offset=(" + offsetX + "," + offsetY + ")]";
    }
}
