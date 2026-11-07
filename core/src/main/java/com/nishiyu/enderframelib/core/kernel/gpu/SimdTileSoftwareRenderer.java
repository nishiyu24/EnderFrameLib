package com.nishiyu.enderframelib.core.kernel.gpu;

import java.util.Arrays;

/**
 * 64×64 画素のタイル単位で塗る、CPU のソフトウェア合成器（角丸矩形の塗り）。
 * 命令は先にタイルへ振り分け（計数ソート）、タイルごとに「命令の順」で塗る。1 タイル = 16 KB（64×64×4 B）で L1 に収まり、
 * 行内の塗りは分岐の無い数え上げループなので C2 の SuperWord が自動ベクトル化できる（JVM フラグ不要）。
 * タイル同士は独立（{@link #renderTile}）なので、呼び出し側が並列に回してもよい。
 *
 * <p>描画先は <b>プリマルチプライド ARGB</b> の {@code int[]}。入力の色はストレート ARGB。
 * 合成は {@code out = src*a + dst*(1-a)}（a = 色の不透明度 × 不透明度 × 角の被覆率）。</p>
 * 事前確保の配列だけを使い、描画中の割り当ては 0（命令数が容量を超えたときの拡張を除く）。
 */
public final class SimdTileSoftwareRenderer {
    public static final int TILE = 64;

    private final int width, height, tilesX, tilesY;
    private final int[] pixels;
    private int cap, count;
    private int[] cx, cy, cw, ch, ccolor;
    private float[] cradius, copacity;
    private int[] clipX0, clipY0, clipX1, clipY1;
    private final int[] tileStart;
    private final int[] tileFill;
    private int[] tileList = new int[0];

    public SimdTileSoftwareRenderer(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("size");
        this.width = width;
        this.height = height;
        this.tilesX = (width + TILE - 1) / TILE;
        this.tilesY = (height + TILE - 1) / TILE;
        this.pixels = new int[width * height];
        this.tileStart = new int[tilesX * tilesY + 1];
        this.tileFill = new int[tilesX * tilesY];
        cx = new int[0]; cy = new int[0]; cw = new int[0]; ch = new int[0]; ccolor = new int[0];
        cradius = new float[0]; copacity = new float[0];
        clipX0 = new int[0]; clipY0 = new int[0]; clipX1 = new int[0]; clipY1 = new int[0];
        grow(256);
    }

    public int width() { return width; }
    public int height() { return height; }
    public int[] pixels() { return pixels; }
    public int tileCount() { return tilesX * tilesY; }

    private void grow(int newCap) {
        cx = Arrays.copyOf(cx, newCap);
        cy = Arrays.copyOf(cy, newCap);
        cw = Arrays.copyOf(cw, newCap);
        ch = Arrays.copyOf(ch, newCap);
        ccolor = Arrays.copyOf(ccolor, newCap);
        cradius = Arrays.copyOf(cradius, newCap);
        copacity = Arrays.copyOf(copacity, newCap);
        clipX0 = Arrays.copyOf(clipX0, newCap);
        clipY0 = Arrays.copyOf(clipY0, newCap);
        clipX1 = Arrays.copyOf(clipX1, newCap);
        clipY1 = Arrays.copyOf(clipY1, newCap);
        cap = newCap;
    }

    /** 透明に戻し、命令も捨てる。 */
    public void clear() {
        Arrays.fill(pixels, 0);
        count = 0;
    }

    /** 角丸矩形を塗る命令を積む。{@code clip*} は画素の半開区間 {@code [x0,x1) × [y0,y1)}。 */
    public void fillRoundRect(int x, int y, int w, int h, float radius, int argb, float opacity,
                              int cx0, int cy0, int cx1, int cy1) {
        if (w <= 0 || h <= 0 || opacity <= 0f) return;
        if (count == cap) grow(cap * 2);
        int i = count++;
        cx[i] = x; cy[i] = y; cw[i] = w; ch[i] = h; ccolor[i] = argb;
        cradius[i] = Math.max(0f, Math.min(radius, Math.min(w, h) * 0.5f));
        copacity[i] = Math.min(1f, opacity);
        clipX0[i] = cx0; clipY0[i] = cy0; clipX1[i] = cx1; clipY1[i] = cy1;
    }

    public void fillRoundRect(int x, int y, int w, int h, float radius, int argb, float opacity) {
        fillRoundRect(x, y, w, h, radius, argb, opacity, 0, 0, width, height);
    }

    /** 積んだ命令を全部描く。 */
    public void render() {
        bin();
        for (int t = 0, n = tilesX * tilesY; t < n; t++) renderTile(t);
        count = 0;
    }

    private void bin() {
        int nt = tilesX * tilesY;
        Arrays.fill(tileFill, 0);
        int total = 0;
        for (int i = 0; i < count; i++) {
            int x0 = Math.max(0, Math.max(cx[i], clipX0[i])), y0 = Math.max(0, Math.max(cy[i], clipY0[i]));
            int x1 = Math.min(width, Math.min(cx[i] + cw[i], clipX1[i])), y1 = Math.min(height, Math.min(cy[i] + ch[i], clipY1[i]));
            if (x1 <= x0 || y1 <= y0) continue;
            for (int ty = y0 / TILE, tyE = (y1 - 1) / TILE; ty <= tyE; ty++)
                for (int tx = x0 / TILE, txE = (x1 - 1) / TILE; tx <= txE; tx++) { tileFill[ty * tilesX + tx]++; total++; }
        }
        if (tileList.length < total) tileList = new int[Math.max(total, tileList.length * 2)];
        int acc = 0;
        for (int t = 0; t < nt; t++) { tileStart[t] = acc; acc += tileFill[t]; tileFill[t] = 0; }
        tileStart[nt] = acc;
        for (int i = 0; i < count; i++) { // 命令の順に入れるので、タイル内の順序が保たれる
            int x0 = Math.max(0, Math.max(cx[i], clipX0[i])), y0 = Math.max(0, Math.max(cy[i], clipY0[i]));
            int x1 = Math.min(width, Math.min(cx[i] + cw[i], clipX1[i])), y1 = Math.min(height, Math.min(cy[i] + ch[i], clipY1[i]));
            if (x1 <= x0 || y1 <= y0) continue;
            for (int ty = y0 / TILE, tyE = (y1 - 1) / TILE; ty <= tyE; ty++)
                for (int tx = x0 / TILE, txE = (x1 - 1) / TILE; tx <= txE; tx++) {
                    int t = ty * tilesX + tx;
                    tileList[tileStart[t] + tileFill[t]++] = i;
                }
        }
    }

    /** タイル {@code t} だけを塗る（振り分けの後。他のタイルとは独立）。 */
    public void renderTile(int t) {
        int tx0 = (t % tilesX) * TILE, ty0 = (t / tilesX) * TILE;
        int tx1 = Math.min(width, tx0 + TILE), ty1 = Math.min(height, ty0 + TILE);
        for (int k = tileStart[t], e = tileStart[t + 1]; k < e; k++) {
            int i = tileList[k];
            int x0 = Math.max(tx0, Math.max(cx[i], clipX0[i])), y0 = Math.max(ty0, Math.max(cy[i], clipY0[i]));
            int x1 = Math.min(tx1, Math.min(cx[i] + cw[i], clipX1[i])), y1 = Math.min(ty1, Math.min(cy[i] + ch[i], clipY1[i]));
            if (x1 <= x0 || y1 <= y0) continue;
            drawClipped(i, x0, y0, x1, y1);
        }
    }

    private void drawClipped(int i, int x0, int y0, int x1, int y1) {
        int argb = ccolor[i];
        float base = ((argb >>> 24) / 255f) * copacity[i];
        if (base <= 0f) return;
        float r = cradius[i];
        int rc = (int) Math.ceil(r);
        int left = cx[i], top = cy[i], right = cx[i] + cw[i], bottom = cy[i] + ch[i];
        for (int y = y0; y < y1; y++) {
            int xi0 = x0, xi1 = x1;
            if (r > 0f && (y < top + rc || y >= bottom - rc)) { // 角の正方形に掛かる行: 左右の端だけ被覆率つき
                int lEnd = Math.min(x1, left + rc), rBeg = Math.max(Math.max(x0, lEnd), right - rc);
                for (int x = x0; x < lEnd; x++) spanFill(y * width, x, x + 1, argb, base * coverage(i, x, y));
                for (int x = rBeg; x < x1; x++) spanFill(y * width, x, x + 1, argb, base * coverage(i, x, y));
                xi0 = Math.max(x0, left + rc);
                xi1 = Math.min(x1, right - rc);
                if (xi1 <= xi0) continue;
            }
            spanFill(y * width, xi0, xi1, argb, base);
        }
    }

    /** 連続区間を同じ不透明度 {@code a} で塗る。分岐の無い数え上げループ（自動ベクトル化の対象）。 */
    private void spanFill(int row, int x0, int x1, int argb, float a) {
        if (a <= 0f) return;
        int[] px = pixels;
        float inv = 1f - a;
        float sr = ((argb >> 16) & 0xFF) * a, sg = ((argb >> 8) & 0xFF) * a, sb = (argb & 0xFF) * a;
        float sA = 255f * a;
        for (int x = row + x0, e = row + x1; x < e; x++) {
            int d = px[x];
            int oa = (int) (sA + ((d >>> 24) & 0xFF) * inv + 0.5f);
            int or = (int) (sr + ((d >> 16) & 0xFF) * inv + 0.5f);
            int og = (int) (sg + ((d >> 8) & 0xFF) * inv + 0.5f);
            int ob = (int) (sb + (d & 0xFF) * inv + 0.5f);
            px[x] = (oa << 24) | (or << 16) | (og << 8) | ob;
        }
    }

    /** 角丸矩形の画素中心での被覆率（角の正方形の外は 1）。 */
    private float coverage(int i, int x, int y) {
        float r = cradius[i];
        float px = x + 0.5f, py = y + 0.5f;
        float left = cx[i], top = cy[i], right = cx[i] + cw[i], bottom = cy[i] + ch[i];
        float ccx, ccy;
        if (px < left + r) ccx = left + r; else if (px > right - r) ccx = right - r; else return 1f;
        if (py < top + r) ccy = top + r; else if (py > bottom - r) ccy = bottom - r; else return 1f;
        float dx = px - ccx, dy = py - ccy;
        float d = (float) Math.sqrt(dx * dx + dy * dy) - r;
        return Math.max(0f, Math.min(1f, 0.5f - d));
    }
}
