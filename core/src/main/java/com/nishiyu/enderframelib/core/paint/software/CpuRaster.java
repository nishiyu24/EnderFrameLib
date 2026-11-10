package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.BlendMode;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Group;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.raster.Pixmap;

import java.util.ArrayList;

/**
 * CPU ラスタの「描画先」まわりの状態を束ねる: 根の {@link Pixmap}（実際に塗るまで確保しない）・いま描いている先・
 * グループの層のスタック・CPU が塗った範囲（{@link DirtyRect}）・層用 Pixmap のプール。
 *
 * <p>{@link SoftwareRenderBackend} は「記録（姿勢・クリップ・範囲）」と {@link GpuSink} への問い合わせだけを行い、
 * 画素の置き場と層の合成（フィルター → クリップ → 背景 → マスク → 不透明度・混合モード）はここが受け持つ。
 * {@link GpuSink} を知るのは、GPU の描画先を読み戻して CPU で混ぜるとき（{@link #closeCpuLayer}）だけ。
 * スレッドセーフではない。
 */
final class CpuRaster {

    /** 開いている層。{@code parentDirty} は層を開く前の（親の）描いた範囲。GPU の層は {@code pixmap} が null。 */
    static final class Layer {
        final Group group;
        final Pixmap pixmap;
        final int[] parentDirty;
        Pixmap maskPixmap;
        int[] maskRegion;

        Layer(Group group, Pixmap pixmap, int[] parentDirty) {
            this.group = group;
            this.pixmap = pixmap;
            this.parentDirty = parentDirty;
        }

        boolean isMask() {
            return group.maskOf() != null;
        }
    }

    private final int w, h;
    private final PixmapPool pool;
    private final DirtyRect dirty = new DirtyRect();
    private final ArrayList<Layer> layers = new ArrayList<>();
    /** 根の描画先。CPU が実際に画素を塗るまで確保しない。 */
    private Pixmap root;
    /** いま描いている先（グループの層の中なら、その層）。根を指すときは {@link #root}（未確保なら null）。 */
    private Pixmap target;

    CpuRaster(int w, int h) {
        this.w = w;
        this.h = h;
        this.pool = new PixmapPool(w, h);
    }

    int width() {
        return w;
    }

    int height() {
        return h;
    }

    // ------------------------------------------------------------------ 描画先

    /** 根の描画先。初めて必要になったときに確保する。 */
    Pixmap root() {
        Pixmap r = root;
        if (r == null) root = r = new Pixmap(w, h);
        return r;
    }

    boolean isRootAllocated() {
        return root != null;
    }

    /** いまの描画先。根を指していて未確保なら、ここで確保する（画素を書く直前に呼ぶ）。 */
    Pixmap target() {
        Pixmap t = target;
        return t != null ? t : (target = root());
    }

    int targetWidth() {
        return target != null ? target.width() : w;
    }

    int targetHeight() {
        return target != null ? target.height() : h;
    }

    // ------------------------------------------------------------------ 汚れ範囲

    DirtyRect dirty() {
        return dirty;
    }

    /** CPU が描いた範囲 {@code {x0, y0, x1, y1}}。何も描いていなければ null。 */
    int[] dirtyBounds() {
        return dirty.bounds();
    }

    /** 根の描画先を {@code color}（事前乗算）で全面塗り直す。 */
    void clearAll(int color) {
        java.util.Arrays.fill(root().pixels(), color);
    }

    /** 根の描画先の {@code [x0,x1) × [y0,y1)} を {@code color}（事前乗算）で塗り直す。 */
    void clearRect(int x0, int y0, int x1, int y1, int color) {
        Pixmap r = root();
        int[] px = r.pixels();
        int w = r.width();
        for (int y = y0; y < y1; y++) java.util.Arrays.fill(px, y * w + x0, y * w + x1, color);
    }

    /** 汚れているタイルだけを {@code color}（事前乗算）で塗り直す（差分描画）。 */
    void clearTiles(DamageRegion damage, int color) {
        Pixmap r = root();
        int[] px = r.pixels();
        int w = r.width(), h = r.height();
        for (int ty = 0, ny = damage.tilesY(); ty < ny; ty++) {
            for (int tx = 0, nx = damage.tilesX(); tx < nx; tx++) {
                if (!damage.isTileDirty(tx, ty)) continue;
                int ax0 = tx * DamageRegion.TILE, ay0 = ty * DamageRegion.TILE;
                int ax1 = Math.min(w, ax0 + DamageRegion.TILE), ay1 = Math.min(h, ay0 + DamageRegion.TILE);
                for (int y = ay0; y < ay1; y++) java.util.Arrays.fill(px, y * w + ax0, y * w + ax1, color);
            }
        }
    }

    /**
     * 根の描画先の指定矩形 {@code [rx0, rx1) × [ry0, ry1)} 内の画素を {@code (dx, dy)} だけシフトし、
     * シフトによって露出した空き領域を {@code color}（事前乗算）で塗りつぶす（スクロール Blit 再利用）。
     */
    void scrollBlit(int rx0, int ry0, int rx1, int ry1, int dx, int dy, int color) {
        if (dx == 0 && dy == 0) return;
        Pixmap r = root();
        int[] px = r.pixels();
        int w = r.width(), h = r.height();
        rx0 = Math.max(0, rx0);
        ry0 = Math.max(0, ry0);
        rx1 = Math.min(w, rx1);
        ry1 = Math.min(h, ry1);
        int rectW = rx1 - rx0, rectH = ry1 - ry0;
        if (rectW <= 0 || rectH <= 0) return;

        if (Math.abs(dx) >= rectW || Math.abs(dy) >= rectH) {
            // シフト量が矩形以上: 全体が流れるため、矩形全体をクリア
            for (int y = ry0; y < ry1; y++) java.util.Arrays.fill(px, y * w + rx0, y * w + rx1, color);
            return;
        }

        int srcX0 = Math.max(rx0, rx0 - dx), srcX1 = Math.min(rx1, rx1 - dx);
        int srcY0 = Math.max(ry0, ry0 - dy), srcY1 = Math.min(ry1, ry1 - dy);
        int dstX0 = srcX0 + dx;
        int dstY0 = srcY0 + dy;
        int copyW = srcX1 - srcX0;
        int copyH = srcY1 - srcY0;

        if (copyW > 0 && copyH > 0) {
            if (dy > 0) {
                // 下方向へのシフト: 下の行から順にコピー（上書き防止）
                for (int row = copyH - 1; row >= 0; row--) {
                    int sy = srcY0 + row;
                    int dyRow = dstY0 + row;
                    System.arraycopy(px, sy * w + srcX0, px, dyRow * w + dstX0, copyW);
                }
            } else {
                // 上方向または水平のみ: 上の行から順にコピー
                for (int row = 0; row < copyH; row++) {
                    int sy = srcY0 + row;
                    int dyRow = dstY0 + row;
                    System.arraycopy(px, sy * w + srcX0, px, dyRow * w + dstX0, copyW);
                }
            }
        }

        // 露出領域のクリア
        // 垂直方向の露出
        if (dy > 0) {
            int expY1 = Math.min(ry1, ry0 + dy);
            for (int y = ry0; y < expY1; y++) java.util.Arrays.fill(px, y * w + rx0, y * w + rx1, color);
        } else if (dy < 0) {
            int expY0 = Math.max(ry0, ry1 + dy);
            for (int y = expY0; y < ry1; y++) java.util.Arrays.fill(px, y * w + rx0, y * w + rx1, color);
        }

        // 水平方向の露出
        if (dx > 0) {
            int expX1 = Math.min(rx1, rx0 + dx);
            for (int y = ry0; y < ry1; y++) java.util.Arrays.fill(px, y * w + rx0, y * w + expX1, color);
        } else if (dx < 0) {
            int expX0 = Math.max(rx0, rx1 + dx);
            for (int y = ry0; y < ry1; y++) java.util.Arrays.fill(px, y * w + expX0, y * w + rx1, color);
        }
    }

    /** 描いた範囲を透明に戻し、汚れ範囲を空にする。 */
    void clearDirty() {
        int[] b = dirty.bounds();
        if (b != null) {
            int[] px = root().pixels();
            for (int y = b[1]; y < b[3]; y++) java.util.Arrays.fill(px, y * w + b[0], y * w + b[2], 0);
        }
        dirty.reset();
    }

    // ------------------------------------------------------------------ フレーム

    void beginFrame() {
        target = root;
    }

    /** 開いたままの層を全部返し、描画先を根へ戻す。 */
    void endFrame() {
        while (!layers.isEmpty()) {
            Layer l = layers.remove(layers.size() - 1);
            if (l.pixmap != null) pool.give(l.pixmap);
            if (l.maskPixmap != null) pool.give(l.maskPixmap);
        }
        target = root;
    }

    // ------------------------------------------------------------------ 層のスタック

    int depth() {
        return layers.size();
    }

    Layer layerAt(int i) {
        return layers.get(i);
    }

    Layer top() {
        return layers.isEmpty() ? null : layers.get(layers.size() - 1);
    }

    /** 画素を持つ（CPU の）層が 1 つでも開いているか。 */
    boolean hasCpuLayer() {
        for (Layer o : layers) if (o.pixmap != null) return true;
        return false;
    }

    /** GPU が持つ層（画素は持たない）を開いたことにする。 */
    void pushGpuLayer(Group g) {
        layers.add(new Layer(g, null, null));
    }

    /** CPU の層を開く。描画先を層へ切り替え、汚れ範囲は層の中のぶんだけを追う（親のぶんは層に預ける）。 */
    void pushCpuLayer(Group g) {
        Pixmap pm = pool.take();
        layers.add(new Layer(g, pm, dirty.toArray()));
        dirty.reset();
        target = pm;
    }

    /** 一番内側の層を外す（合成は {@link #closeCpuLayer}）。 */
    Layer popLayer() {
        return layers.remove(layers.size() - 1);
    }

    // ------------------------------------------------------------------ 層の合成

    /**
     * {@link #popLayer} で外した CPU の層 {@code l} を閉じる。マスクの層は合成せず、中身の層に預ける。中身の層は
     * フィルター → クリップ → backdrop → マスク → 不透明度・混合モードの順に親へ重ねる。
     *
     * @param rootSink 根の GPU 描画先（なければ null）。GPU の層が足りず CPU の層に落ちたとき、背景（混合・
     *                 backdrop-filter が読む絵）は GPU 側にあるので、これを読み戻して CPU で混ぜ、結果を書き戻す
     * @param cpuLayer 外した後にも CPU の層が残っているか（{@link #hasCpuLayer}）
     */
    void closeCpuLayer(Layer l, GpuSink rootSink, boolean cpuLayer) {
        Layer owner = l.isMask() ? layers.get(layers.size() - 1) : null;
        Pixmap parent = root();
        for (int i = layers.size() - 1; i >= 0; i--) {
            if (layers.get(i).pixmap != null) {
                parent = layers.get(i).pixmap;
                break;
            }
        }
        int[] region = dirty.toArray();
        boolean any = !dirty.isEmpty();
        dirty.set(l.parentDirty);
        target = parent;
        if (l.isMask()) {
            // マスクは合成せず、中身の層に預ける（描かれていなければ、マスクなしと同じ）
            if (any) {
                owner.maskPixmap = l.pixmap;
                owner.maskRegion = region;
            } else {
                pool.give(l.pixmap);
            }
            return;
        }
        LayerSpec spec = l.group.spec();
        boolean viaGpu = false;
        if (rootSink != null && !cpuLayer && spec != null && parent == root
                && (spec.blend() != BlendMode.NORMAL || spec.hasBackdrop())) {
            // blend だけなら層の描いた範囲しか背景を読まない。backdrop-filter はぼかしが範囲外を読むので全面
            Pixmap snap = !spec.hasBackdrop() && any ? rootSink.snapshotTarget(region) : rootSink.snapshotTarget();
            if (snap != null) {
                parent = snap;
                viaGpu = true;
            }
        }
        int[] gpuArea = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        if (viaGpu) {
            // 読み戻しで親の溜めた分が GPU へ流れた。以降の dirty は親の描いた範囲ではなく書き戻しの範囲だけを追う
            dirty.reset();
        }
        if (any && spec != null && !spec.filters().isEmpty()) {
            region = LayerFx.filter(l.pixmap, region, spec.filters(), l.group.map());
        }
        if (any && spec != null && spec.clip() != null) {
            LayerFx.clip(l.pixmap, region, spec.clip(), l.group.pose());
        }
        if (spec != null && spec.hasBackdrop()) {
            Pixmap scratch = pool.take();
            int[] area = LayerFx.backdrop(parent, scratch, spec.backdrop(), l.group.map(), l.group.pose(), spec.backdropShape(),
                    spec.clip(), l.group.opacity());
            pool.give(scratch);
            if (area != null) grow(viaGpu ? gpuArea : null, area);
        }
        if (any && l.maskPixmap != null) {
            LayerFx.mask(l.pixmap, region, l.maskPixmap, l.maskRegion, spec.maskLuminance());
        }
        if (l.maskPixmap != null) {
            int[] mr = l.maskRegion;
            int[] mp = l.maskPixmap.pixels();
            for (int y = mr[1]; y < mr[3]; y++) java.util.Arrays.fill(mp, y * w + mr[0], y * w + mr[2], 0);
            pool.give(l.maskPixmap);
        }
        int[] src = l.pixmap.pixels(), dst = parent.pixels();
        BlendMode blend = spec != null ? spec.blend() : BlendMode.NORMAL;
        if (any) {
            final float groupOpacity = l.group.opacity();
            final int cx0 = region[0], cx1 = region[2];
            final int width = w;
            Rows.forEach(region[1], region[3], (long) (cx1 - cx0) * (region[3] - region[1]), (ya, yb) -> {
                for (int y = ya; y < yb; y++) {
                    for (int x = cx0; x < cx1; x++) {
                        int c = src[y * width + x];
                        if (c == 0) continue;
                        if (groupOpacity < 1f) c = SoftShapes.scalePremul(c, groupOpacity);
                        dst[y * width + x] = blend == BlendMode.NORMAL ? SoftShapes.over(c, dst[y * width + x])
                                : blendPixel(blend, c, dst[y * width + x]);
                        src[y * width + x] = 0;
                    }
                }
            });
            grow(viaGpu ? gpuArea : null, region);
        }
        if (viaGpu && gpuArea[2] > gpuArea[0] && gpuArea[3] > gpuArea[1]) rootSink.overwriteTarget(parent, gpuArea);
        pool.give(l.pixmap);
    }

    /** {@code into} （null なら CPU の汚れ範囲）を {@code r} の分だけ広げる。 */
    private void grow(int[] into, int[] r) {
        if (into != null) {
            into[0] = Math.min(into[0], r[0]);
            into[1] = Math.min(into[1], r[1]);
            into[2] = Math.max(into[2], r[2]);
            into[3] = Math.max(into[3], r[3]);
            return;
        }
        dirty.add(r[0], r[1], r[2], r[3]);
    }

    /** 一時の Pixmap を借りる／返す（backdrop 用など）。 */
    Pixmap takeScratch() {
        return pool.take();
    }

    void giveScratch(Pixmap p) {
        pool.give(p);
    }

    /** {@code mix-blend-mode} の合成。{@code src}・{@code dst} は事前乗算の ARGB で、結果も事前乗算。 */
    static int blendPixel(BlendMode mode, int src, int dst) {
        float as = (src >>> 24) / 255f, ab = (dst >>> 24) / 255f;
        if (as <= 0f) return dst;
        float[] cs = unpremul(src, as), cb = ab > 0f ? unpremul(dst, ab) : new float[3];
        float[] o = BlendMode.composite(mode, cb, ab, cs, as);
        int a = Math.round(Math.min(1f, o[3]) * 255f);
        int r = Math.round(Math.min(1f, Math.max(0f, o[0])) * 255f);
        int g = Math.round(Math.min(1f, Math.max(0f, o[1])) * 255f);
        int b = Math.round(Math.min(1f, Math.max(0f, o[2])) * 255f);
        return (a << 24) | (Math.min(r, a) << 16) | (Math.min(g, a) << 8) | Math.min(b, a);
    }

    private static float[] unpremul(int pm, float a) {
        return new float[]{((pm >> 16) & 0xFF) / 255f / a, ((pm >> 8) & 0xFF) / 255f / a, (pm & 0xFF) / 255f / a};
    }
}
