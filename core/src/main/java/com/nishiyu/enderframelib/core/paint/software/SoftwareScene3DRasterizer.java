package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.kernel.memory.OffHeapFloatColumn;
import com.nishiyu.enderframelib.core.kernel.memory.OffHeapIntColumn;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.scene3d.BlendMode3D;
import com.nishiyu.enderframelib.core.scene3d.Bloom3D;
import com.nishiyu.enderframelib.core.scene3d.CameraSpec;
import com.nishiyu.enderframelib.core.scene3d.GeometryPool;
import com.nishiyu.enderframelib.core.scene3d.LightDesc;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DArena;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * {@code <scene-3d>} を GPU なしで描くソフトウェアラスタライザ（X3-2）。{@link Scene3DWorld} だけを入力にし、
 * GL / Vulkan 版（{@code Scene3DRenderer}）と同じ前面判定・深度比較・ブレンドで {@link Pixmap}（乗算済みα ARGB）を作る。
 *
 * <p>流れ: ①頂点をワールド → クリップ空間へ変換し、近クリップ面で三角形を切る ②画面へ投影した三角形を 64×64 の
 * タイルへ振り分ける ③タイルごとに（互いに独立なので並列に）深度バッファ付きで重心座標ラスタライズする。
 * 不透明・マスクを先に、半透明を後ろから前へ。タイルは自分の画素だけを書くので、結果はスレッド数に依らず決定的。
 * 周辺の状態は持たない（呼び出しごとに作って捨てる）。
 */
public final class SoftwareScene3DRasterizer {
    private SoftwareScene3DRasterizer() {}

    /** タイルの 1 辺。 */
    static final int TILE = 64;
    /** 近クリップ面（{@code z + w >= 0}）の余裕。 */
    private static final float W_EPS = 1e-5f;
    /** 並列にするタイル数の下限（これ未満は呼び出しスレッドで回す）。 */
    private static final int PARALLEL_TILES = 6;

    /** 1 枚描く。{@code width}・{@code height} は画素。背景は {@link Scene3DWorld#clearColorArgb()}（ストレート ARGB）。 */
    public static Pixmap render(Scene3DWorld world, int width, int height) {
        Pixmap out = new Pixmap(width, height);
        int[] px = out.pixels();
        Arrays.fill(px, Pixmap.premultiply(world.clearColorArgb()));
        world.updateAndPrepare(width, height);
        Scene scene = new Scene(world, width, height);
        scene.collect();
        if (world.bloomEnabled()) {
            // HDR（制限なしの float）へ描き、Bloom を足して ACES で 8 bit にする（GPU 版と同じ式: Bloom3D）
            float[] hdr = new float[width * height * 4];
            int clear = Pixmap.premultiply(world.clearColorArgb());
            for (int i = 0; i < width * height; i++) {
                hdr[i * 4] = ((clear >> 16) & 0xFF) / 255f;
                hdr[i * 4 + 1] = ((clear >> 8) & 0xFF) / 255f;
                hdr[i * 4 + 2] = (clear & 0xFF) / 255f;
                hdr[i * 4 + 3] = (clear >>> 24) / 255f;
            }
            scene.raster(px, hdr);
            Bloom3D.Level bloom = Bloom3D.bloom(hdr, width, height, world.bloomThreshold(), world.bloomSigma());
            Bloom3D.composite(hdr, width, height, bloom, world.bloomIntensity(), px);
        } else {
            scene.raster(px, null);
        }
        out.markChanged();
        return out;
    }

    /** 三角形の入れ物（構造体の配列）。半透明は後ろから前の順に並べ替えたうえで並べる。 */
    private static final class Tris {
        int count;
        float[] sx = new float[0], sy = new float[0];     // 3 頂点 × 画面座標（画素）
        float[] sz = new float[0], iw = new float[0];     // 3 頂点 × NDC の z・1/w
        float[] wp = new float[0], wn = new float[0];     // 3 頂点 × ワールド位置・法線（各 3 要素）
        int[] mat = new int[0];                           // 材質の番号
        boolean[] back = new boolean[0];                  // 背面か（両面材質のとき法線を反転する）

        void ensure(int n) {
            if (sx.length >= n * 3) return;
            int cap = Math.max(n, Math.max(16, count * 2));
            sx = Arrays.copyOf(sx, cap * 3);
            sy = Arrays.copyOf(sy, cap * 3);
            sz = Arrays.copyOf(sz, cap * 3);
            iw = Arrays.copyOf(iw, cap * 3);
            wp = Arrays.copyOf(wp, cap * 9);
            wn = Arrays.copyOf(wn, cap * 9);
            mat = Arrays.copyOf(mat, cap);
            back = Arrays.copyOf(back, cap);
        }
    }

    private static final class Scene {
        final Scene3DWorld world;
        final int w, h;
        final float[] vp;
        final List<MaterialDesc> materials = new ArrayList<>();
        final Tris opaque = new Tris(), blend = new Tris();

        Scene(Scene3DWorld world, int w, int h) {
            this.world = world;
            this.w = w;
            this.h = h;
            this.vp = world.camera().viewProjMatrix();
        }

        void collect() {
            Scene3DArena arena = world.arena();
            GeometryPool geo = world.geometry();
            CameraSpec cam = world.camera();
            OffHeapIntColumn render = arena.renderRef();
            OffHeapFloatColumn bounds = arena.bounds();
            ByteBuffer vb = geo.vertexBufferSlice(), ib = geo.indexBufferSlice();

            // 半透明は視点からの距離で後ろから前へ
            record Item(int node, float depth) {}
            List<Item> transparent = new ArrayList<>();
            List<Integer> opaqueNodes = new ArrayList<>();
            OffHeapFloatColumn wm = arena.worldMat();
            for (int id = 0, hi = arena.highWater(); id < hi; id++) {
                int rb = id * Scene3DArena.RENDER_STRIDE;
                int flags = render.get(rb + Scene3DArena.RENDER_FLAGS);
                if ((flags & Scene3DArena.FLAG_ALIVE) == 0 || (flags & Scene3DArena.FLAG_VISIBLE) == 0) continue;
                int meshId = render.get(rb + Scene3DArena.RENDER_MESH_ID);
                if (meshId < 0 || geo.meshOrNull(meshId) == null) continue;
                int bb = id * Scene3DArena.BOUNDS_STRIDE;
                if (!cam.intersectsAabb(bounds.get(bb), bounds.get(bb + 1), bounds.get(bb + 2),
                        bounds.get(bb + 3), bounds.get(bb + 4), bounds.get(bb + 5))) continue;
                MaterialDesc mat = world.materials().get(render.get(rb + Scene3DArena.RENDER_MATERIAL_ID));
                if (mat.blendMode() == BlendMode3D.TRANSPARENT) {
                    int b = id * Scene3DArena.MAT_STRIDE;
                    float dx = wm.get(b + 12) - cam.eyeX(), dy = wm.get(b + 13) - cam.eyeY(), dz = wm.get(b + 14) - cam.eyeZ();
                    transparent.add(new Item(id, dx * dx + dy * dy + dz * dz));
                } else {
                    opaqueNodes.add(id);
                }
            }
            transparent.sort((a, b) -> Float.compare(b.depth, a.depth));
            for (int id : opaqueNodes) addNode(arena, geo, vb, ib, id, opaque);
            for (Item it : transparent) addNode(arena, geo, vb, ib, it.node, blend);
        }

        /** 1 ノードの三角形を、クリップ・投影して {@code dst} へ足す。 */
        void addNode(Scene3DArena arena, GeometryPool geo, ByteBuffer vb, ByteBuffer ib, int node, Tris dst) {
            int rb = node * Scene3DArena.RENDER_STRIDE;
            MeshHandle mesh = geo.mesh(arena.renderRef().get(rb + Scene3DArena.RENDER_MESH_ID));
            MaterialDesc mat = world.materials().get(arena.renderRef().get(rb + Scene3DArena.RENDER_MATERIAL_ID));
            int matIdx = materials.size();
            materials.add(mat);

            OffHeapFloatColumn wmc = arena.worldMat();
            int b = node * Scene3DArena.MAT_STRIDE;
            float[] m = new float[16];
            for (int i = 0; i < 16; i++) m[i] = wmc.get(b + i);
            float[] nm = normalMatrix(m);
            // 反射（行列式が負）のときは前面の向きが反転する
            boolean mirrored = det3(m) < 0f;

            int vBase = mesh.firstVertex() * GeometryPool.VERTEX_STRIDE_BYTES;
            int iBase = mesh.firstIndex() * GeometryPool.INDEX_STRIDE_BYTES;
            float[] v = new float[3 * 12]; // 三角形の頂点（クリップ座標 4 + ワールド位置 3 + ワールド法線 3 + 余白 2）
            for (int t = 0, tris = mesh.indexCount() / 3; t < tris; t++) {
                for (int k = 0; k < 3; k++) {
                    int p = vBase + ib.getInt(iBase + (t * 3 + k) * 4) * GeometryPool.VERTEX_STRIDE_BYTES;
                    float x = vb.getFloat(p), y = vb.getFloat(p + 4), z = vb.getFloat(p + 8);
                    float nx = vb.getFloat(p + 12), ny = vb.getFloat(p + 16), nz = vb.getFloat(p + 20);
                    int o = k * 12;
                    float wx = m[0] * x + m[4] * y + m[8] * z + m[12];
                    float wy = m[1] * x + m[5] * y + m[9] * z + m[13];
                    float wz = m[2] * x + m[6] * y + m[10] * z + m[14];
                    v[o + 4] = wx;
                    v[o + 5] = wy;
                    v[o + 6] = wz;
                    v[o] = vp[0] * wx + vp[4] * wy + vp[8] * wz + vp[12];
                    v[o + 1] = vp[1] * wx + vp[5] * wy + vp[9] * wz + vp[13];
                    v[o + 2] = vp[2] * wx + vp[6] * wy + vp[10] * wz + vp[14];
                    v[o + 3] = vp[3] * wx + vp[7] * wy + vp[11] * wz + vp[15];
                    float tx = nm[0] * nx + nm[3] * ny + nm[6] * nz;
                    float ty = nm[1] * nx + nm[4] * ny + nm[7] * nz;
                    float tz = nm[2] * nx + nm[5] * ny + nm[8] * nz;
                    float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                    if (len > 1e-20f) { tx /= len; ty /= len; tz /= len; }
                    v[o + 7] = tx;
                    v[o + 8] = ty;
                    v[o + 9] = tz;
                }
                emit(v, matIdx, mat.doubleSided(), mirrored, dst);
            }
        }

        /** 近クリップ面で切って（最大 2 枚の）三角形にし、投影して足す。 */
        private void emit(float[] v, int matIdx, boolean doubleSided, boolean mirrored, Tris dst) {
            // 頂点ごとの「近面からの距離」z + w（>= 0 が内側）
            float d0 = v[2] + v[3], d1 = v[14] + v[15], d2 = v[26] + v[27];
            int inside = (d0 >= 0 ? 1 : 0) | (d1 >= 0 ? 2 : 0) | (d2 >= 0 ? 4 : 0);
            if (inside == 0) return;
            if (inside == 7) {
                project(v, 0, 12, 24, matIdx, doubleSided, mirrored, dst);
                return;
            }
            // 切る: 内側の頂点列と、辺と近面の交点から多角形（3〜4 頂点）を作る
            float[] poly = new float[4 * 12];
            int n = 0;
            for (int i = 0; i < 3; i++) {
                int j = (i + 1) % 3;
                float di = v[i * 12 + 2] + v[i * 12 + 3], dj = v[j * 12 + 2] + v[j * 12 + 3];
                if (di >= 0) {
                    System.arraycopy(v, i * 12, poly, n * 12, 12);
                    n++;
                }
                if ((di >= 0) != (dj >= 0)) {
                    float t = di / (di - dj);
                    for (int c = 0; c < 10; c++) poly[n * 12 + c] = v[i * 12 + c] + (v[j * 12 + c] - v[i * 12 + c]) * t;
                    n++;
                }
            }
            if (n < 3) return;
            float[] a = new float[36];
            System.arraycopy(poly, 0, a, 0, 12);
            for (int i = 1; i + 1 < n; i++) {
                System.arraycopy(poly, i * 12, a, 12, 12);
                System.arraycopy(poly, (i + 1) * 12, a, 24, 12);
                project(a, 0, 12, 24, matIdx, doubleSided, mirrored, dst);
            }
        }

        private void project(float[] v, int o0, int o1, int o2, int matIdx, boolean doubleSided, boolean mirrored, Tris dst) {
            int[] os = {o0, o1, o2};
            float[] x = new float[3], y = new float[3], z = new float[3], iw = new float[3];
            for (int k = 0; k < 3; k++) {
                int o = os[k];
                float cw = Math.max(v[o + 3], W_EPS);
                float inv = 1f / cw;
                x[k] = v[o] * inv;
                y[k] = v[o + 1] * inv;
                z[k] = v[o + 2] * inv;
                iw[k] = inv;
            }
            // NDC（y が上）での符号付き面積。正 = 反時計回り = 前面（GL の既定）
            float area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0]);
            if (area == 0f || Float.isNaN(area)) return;
            boolean front = (area > 0f) != mirrored;
            if (!front && !doubleSided) return;

            int i = dst.count;
            dst.ensure(i + 1);
            for (int k = 0; k < 3; k++) {
                int o = os[k];
                dst.sx[i * 3 + k] = (x[k] * 0.5f + 0.5f) * w;
                dst.sy[i * 3 + k] = (1f - (y[k] * 0.5f + 0.5f)) * h;
                dst.sz[i * 3 + k] = z[k];
                dst.iw[i * 3 + k] = iw[k];
                for (int c = 0; c < 3; c++) {
                    dst.wp[i * 9 + k * 3 + c] = v[o + 4 + c];
                    dst.wn[i * 9 + k * 3 + c] = v[o + 7 + c];
                }
            }
            dst.mat[i] = matIdx;
            dst.back[i] = !front;
            dst.count = i + 1;
        }

        private final ThreadLocal<float[]> color = ThreadLocal.withInitial(() -> new float[4]);

        void raster(int[] px, float[] hdr) {
            int tx = (w + TILE - 1) / TILE, ty = (h + TILE - 1) / TILE;
            int tiles = tx * ty;
            IntArrayList[] opaqueBins = bin(opaque, tx, ty), blendBins = bin(blend, tx, ty);
            LightDesc light = world.light();
            float[] eye = {world.camera().eyeX(), world.camera().eyeY(), world.camera().eyeZ()};
            Shader shader = new Shader(light, eye);
            IntStream range = IntStream.range(0, tiles);
            if (tiles >= PARALLEL_TILES) range = range.parallel();
            range.forEach(t -> {
                int x0 = (t % tx) * TILE, y0 = (t / tx) * TILE;
                int x1 = Math.min(w, x0 + TILE), y1 = Math.min(h, y0 + TILE);
                float[] depth = new float[TILE * TILE];
                Arrays.fill(depth, 1f);
                drawBin(opaque, opaqueBins[t], px, hdr, depth, x0, y0, x1, y1, shader, false);
                drawBin(blend, blendBins[t], px, hdr, depth, x0, y0, x1, y1, shader, true);
            });
        }

        private IntArrayList[] bin(Tris tris, int tx, int ty) {
            IntArrayList[] bins = new IntArrayList[tx * ty];
            for (int i = 0; i < bins.length; i++) bins[i] = new IntArrayList();
            for (int i = 0; i < tris.count; i++) {
                int o = i * 3;
                float minX = Math.min(tris.sx[o], Math.min(tris.sx[o + 1], tris.sx[o + 2]));
                float maxX = Math.max(tris.sx[o], Math.max(tris.sx[o + 1], tris.sx[o + 2]));
                float minY = Math.min(tris.sy[o], Math.min(tris.sy[o + 1], tris.sy[o + 2]));
                float maxY = Math.max(tris.sy[o], Math.max(tris.sy[o + 1], tris.sy[o + 2]));
                if (maxX < 0 || maxY < 0 || minX >= w || minY >= h) continue;
                int bx0 = Math.max(0, (int) Math.floor(minX) / TILE), bx1 = Math.min(tx - 1, (int) Math.floor(maxX) / TILE);
                int by0 = Math.max(0, (int) Math.floor(minY) / TILE), by1 = Math.min(ty - 1, (int) Math.floor(maxY) / TILE);
                for (int by = by0; by <= by1; by++) for (int bx = bx0; bx <= bx1; bx++) bins[by * tx + bx].add(i);
            }
            return bins;
        }

        private void drawBin(Tris tris, IntArrayList bin, int[] px, float[] hdr, float[] depth, int x0, int y0, int x1, int y1,
                             Shader shader, boolean blending) {
            for (int bi = 0, bn = bin.size(); bi < bn; bi++) {
                int i = bin.getInt(bi);
                int o = i * 3;
                double ax = tris.sx[o], ay = tris.sy[o], bx = tris.sx[o + 1], by = tris.sy[o + 1], cx = tris.sx[o + 2], cy = tris.sy[o + 2];
                double area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
                if (area == 0) continue;
                double invArea = 1.0 / area;
                int minX = Math.max(x0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
                int maxX = Math.min(x1 - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
                int minY = Math.max(y0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
                int maxY = Math.min(y1 - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
                MaterialDesc mat = materials.get(tris.mat[i]);
                boolean mask = mat.blendMode() == BlendMode3D.MASK;
                float z0 = tris.sz[o], z1 = tris.sz[o + 1], z2 = tris.sz[o + 2];
                float w0 = tris.iw[o], w1 = tris.iw[o + 1], w2 = tris.iw[o + 2];
                // 上辺・左辺の規則（共有辺を二重に塗らない）。面積の符号で向きが変わるので、符号をそろえて判定する
                boolean pos = area > 0;
                for (int py = minY; py <= maxY; py++) {
                    double sy = py + 0.5;
                    for (int pxl = minX; pxl <= maxX; pxl++) {
                        double sx = pxl + 0.5;
                        double e0 = ((cx - bx) * (sy - by) - (cy - by) * (sx - bx)) * invArea; // 頂点 a の重み
                        double e1 = ((ax - cx) * (sy - cy) - (ay - cy) * (sx - cx)) * invArea; // b
                        double e2 = ((bx - ax) * (sy - ay) - (by - ay) * (sx - ax)) * invArea; // c
                        if (e0 < 0 || e1 < 0 || e2 < 0) continue;
                        if ((e0 == 0 && !edgeOwned(bx, by, cx, cy, pos)) || (e1 == 0 && !edgeOwned(cx, cy, ax, ay, pos))
                                || (e2 == 0 && !edgeOwned(ax, ay, bx, by, pos))) continue;
                        float z = (float) (e0 * z0 + e1 * z1 + e2 * z2);
                        if (z > 1f || z < -1f) continue;
                        int di = (py - y0) * TILE + (pxl - x0);
                        if (z >= depth[di]) continue;
                        // 透視補正: 1/w で重みを補正
                        double pw0 = e0 * w0, pw1 = e1 * w1, pw2 = e2 * w2;
                        double ps = pw0 + pw1 + pw2;
                        if (!(ps > 0)) continue;
                        float f0 = (float) (pw0 / ps), f1 = (float) (pw1 / ps), f2 = (float) (pw2 / ps);
                        float[] c = color.get();
                        if (!shader.shade(tris, i, mat, f0, f1, f2, mask, c)) continue; // マスクで捨てた
                        if (hdr != null) {
                            int hi = (pxl + py * w) * 4;
                            float al = c[3];
                            if (!blending) {
                                depth[di] = z;
                                hdr[hi] = c[0] * al;
                                hdr[hi + 1] = c[1] * al;
                                hdr[hi + 2] = c[2] * al;
                                hdr[hi + 3] = al;
                            } else { // 事前乗算の over（HDR は上限なし）
                                float ia = 1f - al;
                                hdr[hi] = c[0] * al + hdr[hi] * ia;
                                hdr[hi + 1] = c[1] * al + hdr[hi + 1] * ia;
                                hdr[hi + 2] = c[2] * al + hdr[hi + 2] * ia;
                                hdr[hi + 3] = al + hdr[hi + 3] * ia;
                            }
                            continue;
                        }
                        int argb = Shader.pack(c);
                        if (!blending) {
                            depth[di] = z;
                            px[pxl + py * w] = argb;
                        } else {
                            px[pxl + py * w] = over(argb, px[pxl + py * w]);
                        }
                    }
                }
            }
        }

        /** 重みがちょうど 0 の辺（頂点 p→q）を、この三角形が所有するか（上辺・左辺の規則）。 */
        private static boolean edgeOwned(double px, double py, double qx, double qy, boolean pos) {
            double dx = qx - px, dy = qy - py;
            if (!pos) { dx = -dx; dy = -dy; }
            return dy > 0 || (dy == 0 && dx < 0);
        }

        private static int div255(int v) {
            return ((v + 128) * 257) >> 16;
        }

        private static int over(int src, int dst) {
            int sa = src >>> 24;
            if (sa == 255) return src;
            int ia = 255 - sa;
            int a = sa + div255((dst >>> 24) * ia);
            int r = ((src >> 16) & 0xFF) + div255(((dst >> 16) & 0xFF) * ia);
            int g = ((src >> 8) & 0xFF) + div255(((dst >> 8) & 0xFF) * ia);
            int b = (src & 0xFF) + div255((dst & 0xFF) * ia);
            return (Math.min(a, 255) << 24) | (Math.min(r, 255) << 16) | (Math.min(g, 255) << 8) | Math.min(b, 255);
        }
    }

    /** 画素の色。GL 版（{@code ShaderSources.SCENE3D_FRAGMENT}）と同じ式。 */
    private static final class Shader {
        final float lr, lg, lb, ar, ag, ab, ldx, ldy, ldz;
        final float[] eye;

        Shader(LightDesc l, float[] eye) {
            float li = l.intensity();
            this.lr = l.lightR() * li;
            this.lg = l.lightG() * li;
            this.lb = l.lightB() * li;
            this.ar = l.ambientR();
            this.ag = l.ambientG();
            this.ab = l.ambientB();
            // 光が進む向きの逆（面から光源へ）
            this.ldx = -l.dirX();
            this.ldy = -l.dirY();
            this.ldz = -l.dirZ();
            this.eye = eye;
        }

        /** 色（事前乗算の前の rgb・α。HDR は上限なし）を {@code out} へ。マスクで捨てるなら false。 */
        boolean shade(Tris t, int i, MaterialDesc m, float f0, float f1, float f2, boolean mask, float[] out) {
            int base = m.baseColorArgb();
            float alpha = ((base >>> 24) & 0xFF) / 255f;
            if (mask && alpha < 0.5f) return false;
            float cr = ((base >> 16) & 0xFF) / 255f, cg = ((base >> 8) & 0xFF) / 255f, cb = (base & 0xFF) / 255f;
            float es = m.emissiveStrength(); // GL 版は強さ/4 を 8 bit にして ×4 で戻す = 強さそのもの
            float er = ((m.emissiveRgb() >> 16) & 0xFF) / 255f * es, eg = ((m.emissiveRgb() >> 8) & 0xFF) / 255f * es,
                    eb = (m.emissiveRgb() & 0xFF) / 255f * es;
            float rr, rg, rb;
            if (m.unlit()) {
                rr = cr + er;
                rg = cg + eg;
                rb = cb + eb;
            } else {
                int o = i * 9;
                float px = f0 * t.wp[o] + f1 * t.wp[o + 3] + f2 * t.wp[o + 6];
                float py = f0 * t.wp[o + 1] + f1 * t.wp[o + 4] + f2 * t.wp[o + 7];
                float pz = f0 * t.wp[o + 2] + f1 * t.wp[o + 5] + f2 * t.wp[o + 8];
                float nx = f0 * t.wn[o] + f1 * t.wn[o + 3] + f2 * t.wn[o + 6];
                float ny = f0 * t.wn[o + 1] + f1 * t.wn[o + 4] + f2 * t.wn[o + 7];
                float nz = f0 * t.wn[o + 2] + f1 * t.wn[o + 5] + f2 * t.wn[o + 8];
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl > 1e-20f) { nx /= nl; ny /= nl; nz /= nl; }
                if (t.back[i]) { nx = -nx; ny = -ny; nz = -nz; }
                float ll = (float) Math.sqrt(ldx * ldx + ldy * ldy + ldz * ldz);
                float lx = ldx / ll, ly = ldy / ll, lz = ldz / ll;
                float ndl = Math.max(nx * lx + ny * ly + nz * lz, 0f);
                float vx = eye[0] - px, vy = eye[1] - py, vz = eye[2] - pz;
                float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                if (vl > 1e-20f) { vx /= vl; vy /= vl; vz /= vl; }
                float hx = lx + vx, hy = ly + vy, hz = lz + vz;
                float hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                if (hl > 1e-20f) { hx /= hl; hy /= hl; hz /= hl; }
                float ndv = Math.max(nx * vx + ny * vy + nz * vz, 1e-4f);
                float ndh = Math.max(nx * hx + ny * hy + nz * hz, 0f);
                float vdh = Math.max(vx * hx + vy * hy + vz * hz, 0f);
                // Cook-Torrance（GGX・Smith 相関・Schlick）。ShaderSources.SCENE3D_FRAGMENT と同じ式
                float metallic = m.metallic();
                float roughness = Math.min(1f, Math.max(m.roughness(), 0.05f));
                float a2 = roughness * roughness * roughness * roughness;
                float dd = ndh * ndh * (a2 - 1f) + 1f;
                float d = a2 / ((float) Math.PI * dd * dd);
                float vis = 0.5f / (ndl * (float) Math.sqrt(ndv * ndv * (1f - a2) + a2)
                        + ndv * (float) Math.sqrt(ndl * ndl * (1f - a2) + a2));
                float fw = (float) Math.pow(1f - vdh, 5.0);
                float fe = (float) Math.pow(1f - ndv, 5.0);
                float spec = d * vis * (float) Math.PI;
                float[] c3 = {cr, cg, cb};
                float[] light = {lr, lg, lb}, amb = {ar, ag, ab};
                float[] lit = new float[3];
                float envK = Math.max(1f - roughness, 0f);
                for (int k = 0; k < 3; k++) {
                    float fz = 0.04f + (c3[k] - 0.04f) * metallic;
                    float f = fz + (1f - fz) * fw;
                    float kd = (1f - f) * (1f - metallic);
                    float direct = (kd * c3[k] + spec * f) * light[k] * ndl;
                    float fEnv = fz + (Math.max(envK, fz) - fz) * fe;
                    float ambient = amb[k] * (c3[k] * (1f - metallic) + fEnv * (1f - roughness * 0.5f));
                    lit[k] = direct + ambient;
                }
                rr = lit[0] + er;
                rg = lit[1] + eg;
                rb = lit[2] + eb;
            }
            out[0] = rr;
            out[1] = rg;
            out[2] = rb;
            out[3] = alpha;
            return true;
        }

        /** 8 bit の事前乗算 ARGB へ（GL 版の {@code clamp} と丸めに合わせる）。 */
        static int pack(float[] c) {
            float alpha = c[3];
            int a = Math.round(alpha * 255f);
            return (a << 24) | (q(c[0] * alpha) << 16) | (q(c[1] * alpha) << 8) | q(c[2] * alpha);
        }

        private static int q(float v) {
            return v <= 0f ? 0 : v >= 1f ? 255 : Math.round(v * 255f);
        }
    }

    private static float det3(float[] m) {
        return m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2]);
    }

    /** 法線行列 = 左上 3×3 の逆行列の転置（列優先で 9 要素: 行 r・列 c は {@code [c*3 + r]}）。 */
    private static float[] normalMatrix(float[] m) {
        float m0 = m[0], m1 = m[1], m2 = m[2], m4 = m[4], m5 = m[5], m6 = m[6], m8 = m[8], m9 = m[9], m10 = m[10];
        float c00 = m5 * m10 - m9 * m6, c01 = m9 * m2 - m1 * m10, c02 = m1 * m6 - m5 * m2;
        float det = m0 * c00 + m4 * c01 + m8 * c02;
        if (Math.abs(det) < 1e-20f) return new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        float id = 1f / det;
        // 逆行列（列優先）
        float i0 = c00 * id, i1 = c01 * id, i2 = c02 * id;
        float i4 = (m8 * m6 - m4 * m10) * id, i5 = (m0 * m10 - m8 * m2) * id, i6 = (m4 * m2 - m0 * m6) * id;
        float i8 = (m4 * m9 - m8 * m5) * id, i9 = (m8 * m1 - m0 * m9) * id, i10 = (m0 * m5 - m4 * m1) * id;
        // 転置して返す: 結果 N = inv^T。N の列 c・行 r の値は inv の列 r・行 c
        return new float[]{i0, i4, i8, i1, i5, i9, i2, i6, i10};
    }
}
