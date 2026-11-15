package com.nishiyu.enderframelib.core.scene3d;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.software.SoftwareScene3DRasterizer;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.scene3d.GltfBinaryLoader.GltfException;
import java.io.ByteArrayOutputStream;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** X3-2: glTF 2.0 / .glb の読み込み。テストの中で .glb を組み立てて、幾何・階層・材質・失敗時の不変性・メモリを確かめる。 */
class GltfBinaryLoaderTest {

    // ------------------------------------------------------------------ .glb を組み立てる小道具

    private static byte[] floats(float... v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) b.putFloat(f);
        return b.array();
    }

    private static byte[] u16(int... v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i : v) b.putShort((short) i);
        return b.array();
    }

    private static byte[] glb(String json, byte[] bin) {
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int jl = (j.length + 3) & ~3, bl = bin == null ? 0 : (bin.length + 3) & ~3;
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + jl + (bin == null ? 0 : 8 + bl)).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546C67).putInt(2).putInt(b.capacity());
        b.putInt(jl).putInt(0x4E4F534A).put(j);
        for (int i = j.length; i < jl; i++) b.put((byte) 0x20);
        if (bin != null) {
            b.putInt(bl).putInt(0x004E4942).put(bin);
            // 残りは 0 で埋まっている
        }
        return b.array();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    /** 原点・+X・+Y の三角形（+Z 向き）。bufferView 0 = 位置 36B、1 = u16 インデックス 6B。 */
    private static final byte[] TRI_BIN = cat(floats(0, 0, 0, 1, 0, 0, 0, 1, 0), u16(0, 1, 2));
    private static final String TRI_BASE = """
            "asset":{"version":"2.0"},
            "buffers":[{"byteLength":42}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],
            "accessors":[
              {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
              {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
            """;

    private static String triJson(String materials, String primExtra, String nodes) {
        return "{" + TRI_BASE + materials
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1" + primExtra + "}]}],"
                + nodes + "}";
    }

    private static String simpleTri() {
        return triJson("", "", "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],\"scene\":0");
    }

    // ------------------------------------------------------------------ 幾何

    @Test
    void loadsATriangleIntoThePools() {
        Scene3DWorld w = new Scene3DWorld();
        GltfBinaryLoader.Result r = GltfBinaryLoader.load(glb(simpleTri(), TRI_BIN), w);
        assertEquals(1, r.rootNodes().length);
        assertEquals(1, r.meshCount());
        assertEquals(1, w.geometry().meshCount());
        MeshHandle m = w.geometry().mesh(0);
        assertEquals(3, m.vertexCount());
        assertEquals(3, m.indexCount());
        assertEquals(1f, m.maxX());
        assertEquals(1f, m.maxY());
        ByteBuffer vb = w.geometry().vertexBufferSlice();
        // 位置
        assertEquals(1f, vb.getFloat(GeometryPool.VERTEX_STRIDE_BYTES));
        assertEquals(1f, vb.getFloat(2 * GeometryPool.VERTEX_STRIDE_BYTES + 4));
        // 法線が無いので面の法線（+Z）が作られる
        for (int v = 0; v < 3; v++) {
            int o = v * GeometryPool.VERTEX_STRIDE_BYTES;
            assertEquals(0f, vb.getFloat(o + 12), 1e-6);
            assertEquals(0f, vb.getFloat(o + 16), 1e-6);
            assertEquals(1f, vb.getFloat(o + 20), 1e-6);
        }
        assertEquals(w.geometry().meshCount() - 1, w.arena().meshId(r.rootNodes()[0]));
    }

    @Test
    void readsInterleavedStridedAttributesAndNormalizedUv() {
        // 1 頂点 = pos3 f32 (12B) + normal3 f32 (12B) + uv2 u8 normalized (2B) + 2B 余白 = 28B
        ByteBuffer b = ByteBuffer.allocate(3 * 28 + 6).order(ByteOrder.LITTLE_ENDIAN);
        float[][] pos = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}};
        for (int i = 0; i < 3; i++) {
            for (float f : pos[i]) b.putFloat(f);
            b.putFloat(0).putFloat(0).putFloat(1);
            b.put((byte) (i == 1 ? 255 : 0)).put((byte) (i == 2 ? 255 : 0)).put((byte) 0).put((byte) 0);
        }
        b.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        String json = """
                {"asset":{"version":"2.0"},"buffers":[{"byteLength":90}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":84,"byteStride":28},{"buffer":0,"byteOffset":84,"byteLength":6}],
                 "accessors":[
                  {"bufferView":0,"byteOffset":0,"componentType":5126,"count":3,"type":"VEC3"},
                  {"bufferView":0,"byteOffset":12,"componentType":5126,"count":3,"type":"VEC3"},
                  {"bufferView":0,"byteOffset":24,"componentType":5121,"normalized":true,"count":3,"type":"VEC2"},
                  {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3}]}],
                 "nodes":[{"mesh":0}]}
                """;
        Scene3DWorld w = new Scene3DWorld();
        GltfBinaryLoader.load(glb(json, b.array()), w);
        ByteBuffer vb = w.geometry().vertexBufferSlice();
        int s = GeometryPool.VERTEX_STRIDE_BYTES;
        assertEquals(1f, vb.getFloat(s), 0);                  // 頂点 1 の x
        assertEquals(1f, vb.getFloat(s + 6 * 4), 1e-6);        // 頂点 1 の u
        assertEquals(0f, vb.getFloat(s + 7 * 4), 1e-6);
        assertEquals(1f, vb.getFloat(2 * s + 7 * 4), 1e-6);    // 頂点 2 の v
        assertEquals(1f, vb.getFloat(s + 5 * 4), 1e-6);        // 法線 z
    }

    @Test
    void convertsStripsAndFansToTriangles() {
        // 4 頂点の四角形。STRIP は「Z 字」順 (0,0)(1,0)(0,1)(1,1)、FAN は周回順 (0,0)(1,0)(1,1)(0,1)
        for (int mode : new int[]{5, 6}) {
            byte[] bin = mode == 5 ? cat(floats(0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0), u16(0, 1, 2, 3))
                    : cat(floats(0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0), u16(0, 1, 2, 3));
            String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":56}],"
                    + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":48},{\"buffer\":0,\"byteOffset\":48,\"byteLength\":8}],"
                    + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":4,\"type\":\"VEC3\"},"
                    + "{\"bufferView\":1,\"componentType\":5123,\"count\":4,\"type\":\"SCALAR\"}],"
                    + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1,\"mode\":" + mode + "}]}],"
                    + "\"nodes\":[{\"mesh\":0}]}";
            Scene3DWorld w = new Scene3DWorld();
            GltfBinaryLoader.load(glb(json, bin), w);
            assertEquals(6, w.geometry().mesh(0).indexCount(), "mode " + mode);
            // どちらの向きも +Z を向く（法線の z が正）
            ByteBuffer ib = w.geometry().indexBufferSlice(), vb = w.geometry().vertexBufferSlice();
            for (int t = 0; t < 2; t++) {
                int a = ib.getInt(t * 12), c = ib.getInt(t * 12 + 4), d = ib.getInt(t * 12 + 8);
                float[] p = new float[9];
                int[] ids = {a, c, d};
                for (int k = 0; k < 3; k++) for (int j = 0; j < 3; j++) p[k * 3 + j] = vb.getFloat(ids[k] * GeometryPool.VERTEX_STRIDE_BYTES + j * 4);
                float nz = (p[3] - p[0]) * (p[7] - p[1]) - (p[4] - p[1]) * (p[6] - p[0]);
                assertTrue(nz > 0, "mode " + mode + " tri " + t + " は +Z 向き: " + nz);
            }
        }
    }

    @Test
    void skipsPointsAndLinesAndCountsThem() {
        String json = triJson("", ",\"mode\":1", "\"nodes\":[{\"mesh\":0}]");
        Scene3DWorld w = new Scene3DWorld();
        GltfBinaryLoader.Result r = GltfBinaryLoader.load(glb(json, TRI_BIN), w);
        assertEquals(1, r.skippedPrimitives());
        assertEquals(0, w.geometry().meshCount());
    }

    // ------------------------------------------------------------------ 階層・姿勢

    @Test
    void appliesNodeTransformsAlongTheHierarchy() {
        String nodes = """
                "nodes":[{"translation":[10,0,0],"children":[1]},
                         {"translation":[0,5,0],"scale":[2,2,2],"mesh":0}],
                "scenes":[{"nodes":[0]}]""";
        Scene3DWorld w = new Scene3DWorld();
        GltfBinaryLoader.Result r = GltfBinaryLoader.load(glb(triJson("", "", nodes), TRI_BIN), w);
        assertEquals(2, r.allNodes().length);
        w.transformSystem().update(w.arena(), w.geometry());
        int child = r.allNodes()[1];
        OffHeapAccess a = new OffHeapAccess(w, child);
        assertEquals(10f, a.m(12), 1e-5);
        assertEquals(5f, a.m(13), 1e-5);
        assertEquals(2f, a.m(0), 1e-5, "親の平行移動の下で子の拡大縮小");
    }

    @Test
    void matrixIsDecomposedIntoTrs() {
        // 回転 +90° about Z（x→y）, 拡大 (2,3,4), 平行移動 (1,2,3) の列優先 4×4
        float[] m = {0, 2, 0, 0, -3, 0, 0, 0, 0, 0, 4, 0, 1, 2, 3, 1};
        float[] trs = new float[10];
        GltfBinaryLoader.decompose(m, trs);
        assertArrayEquals(new float[]{1, 2, 3}, new float[]{trs[0], trs[1], trs[2]}, 1e-6f);
        assertArrayEquals(new float[]{2, 3, 4}, new float[]{trs[7], trs[8], trs[9]}, 1e-5f);
        float s = (float) Math.sin(Math.PI / 4);
        assertArrayEquals(new float[]{0, 0, s, s}, new float[]{trs[3], trs[4], trs[5], trs[6]}, 1e-5f);
    }

    @Test
    void sharedMeshIsRegisteredOnceAndMultiPrimitiveMeshesGetChildNodes() {
        // 2 つのノードが同じ mesh を参照。mesh は 2 つの primitive を持つ
        String json = "{" + TRI_BASE
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1},{\"attributes\":{\"POSITION\":0},\"indices\":1}]}],"
                + "\"nodes\":[{\"mesh\":0},{\"mesh\":0,\"translation\":[3,0,0]}],\"scenes\":[{\"nodes\":[0,1]}]}";
        Scene3DWorld w = new Scene3DWorld();
        GltfBinaryLoader.Result r = GltfBinaryLoader.load(glb(json, TRI_BIN), w);
        assertEquals(2, r.meshCount(), "primitive ごとに 1 回（ノード数ではない）");
        assertEquals(2, w.geometry().meshCount());
        assertEquals(4, r.allNodes().length, "ノード 2 + 2 つ目の primitive の子 2");
    }

    @Test
    void loadsUnderAGivenParent() {
        Scene3DWorld w = new Scene3DWorld();
        int parent = w.arena().allocateNode();
        w.arena().setPosition(parent, 100, 0, 0);
        GltfBinaryLoader.Result r = GltfBinaryLoader.load(glb(simpleTri(), TRI_BIN), w, parent);
        w.transformSystem().update(w.arena(), w.geometry());
        assertEquals(100f, new OffHeapAccess(w, r.rootNodes()[0]).m(12), 1e-5);
    }

    // ------------------------------------------------------------------ 材質

    @Test
    void readsMaterials() {
        String mats = """
                "materials":[
                 {"pbrMetallicRoughness":{"baseColorFactor":[1,0.5,0,0.5],"metallicFactor":0.25,"roughnessFactor":0.75},
                  "alphaMode":"BLEND","doubleSided":true,"emissiveFactor":[2,1,0]},
                 {"alphaMode":"MASK","extensions":{"KHR_materials_unlit":{}}}],""";
        String json = triJson(mats, ",\"material\":0", "\"nodes\":[{\"mesh\":0}]");
        Scene3DWorld w = new Scene3DWorld();
        GltfBinaryLoader.Result r = GltfBinaryLoader.load(glb(json, TRI_BIN), w);
        assertEquals(2, r.materialCount());
        MaterialDesc a = w.materials().get(w.arena().renderRef().get(r.rootNodes()[0] * Scene3DArena.RENDER_STRIDE + Scene3DArena.RENDER_MATERIAL_ID));
        assertEquals(0.25f, a.metallic(), 1e-6);
        assertEquals(0.75f, a.roughness(), 1e-6);
        assertEquals(BlendMode3D.TRANSPARENT, a.blendMode());
        assertTrue(a.doubleSided());
        assertFalse(a.unlit());
        assertEquals(0x80, a.baseColorArgb() >>> 24, "α は線形のまま");
        // 線形 0.5 → sRGB 188（約 0xBC）、1 → 255、0 → 0
        assertEquals(255, (a.baseColorArgb() >> 16) & 0xFF);
        assertEquals(188, (a.baseColorArgb() >> 8) & 0xFF, 1);
        assertEquals(0, a.baseColorArgb() & 0xFF);
        assertEquals(2f, a.emissiveStrength(), 1e-6, "最大成分が強さ");
        assertEquals(0xFF, (a.emissiveRgb() >> 16) & 0xFF);
        // 材質を指定しない primitive は glTF の既定（白・metallic 1・roughness 1）
        Scene3DWorld w2 = new Scene3DWorld();
        GltfBinaryLoader.Result r2 = GltfBinaryLoader.load(glb(simpleTri(), TRI_BIN), w2);
        MaterialDesc d = w2.materials().get(w2.arena().renderRef().get(r2.rootNodes()[0] * Scene3DArena.RENDER_STRIDE + Scene3DArena.RENDER_MATERIAL_ID));
        assertEquals(1f, d.metallic());
        assertEquals(1f, d.roughness());
        assertEquals(0xFFFFFFFF, d.baseColorArgb());
    }

    @Test
    void readsUnlitAndMaskFlags() {
        String mats = "\"materials\":[{\"alphaMode\":\"MASK\",\"extensions\":{\"KHR_materials_unlit\":{}}}],";
        String json = triJson(mats, ",\"material\":0", "\"nodes\":[{\"mesh\":0}]");
        Scene3DWorld w = new Scene3DWorld();
        GltfBinaryLoader.Result r = GltfBinaryLoader.load(glb(json, TRI_BIN), w);
        MaterialDesc m = w.materials().get(w.arena().renderRef().get(r.rootNodes()[0] * Scene3DArena.RENDER_STRIDE + Scene3DArena.RENDER_MATERIAL_ID));
        assertTrue(m.unlit());
        assertEquals(BlendMode3D.MASK, m.blendMode());
    }

    // ------------------------------------------------------------------ .gltf（data: URI）

    @Test
    void loadsAGltfWithEmbeddedDataUri() {
        String uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(TRI_BIN);
        String json = simpleTri().replace("\"buffers\":[{\"byteLength\":42}]", "\"buffers\":[{\"byteLength\":42,\"uri\":\"" + uri + "\"}]");
        Scene3DWorld w = new Scene3DWorld();
        GltfBinaryLoader.Result r = GltfBinaryLoader.load(json.getBytes(StandardCharsets.UTF_8), w);
        assertEquals(1, r.meshCount());
    }

    // ------------------------------------------------------------------ 失敗。world は変わらない

    private static void assertRejected(byte[] data, String messagePart) {
        Scene3DWorld w = new Scene3DWorld();
        int nodes = w.arena().aliveCount(), mats = w.materials().count();
        GltfException e = assertThrows(GltfException.class, () -> GltfBinaryLoader.load(data, w), messagePart);
        assertTrue(e.getMessage().contains(messagePart), "メッセージに「" + messagePart + "」が無い: " + e.getMessage());
        assertEquals(0, w.geometry().meshCount(), "失敗したら mesh を登録しない");
        assertEquals(nodes, w.arena().aliveCount(), "失敗したらノードを作らない");
        assertEquals(mats, w.materials().count(), "失敗したら材質を登録しない");
    }

    @Test
    void rejectsMalformedInputWithoutTouchingTheWorld() {
        byte[] ok = glb(simpleTri(), TRI_BIN);
        assertRejected("not json".getBytes(StandardCharsets.UTF_8), "invalid glTF JSON");
        assertRejected(glb("{\"asset\":{\"version\":\"1.0\"}}", null), "2.0");
        // glb の長さ・チャンクを壊す
        byte[] truncated = java.util.Arrays.copyOf(ok, ok.length - 10);
        assertRejected(truncated, "glb length");
        byte[] badChunk = ok.clone();
        ByteBuffer.wrap(badChunk).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 0x7FFFFFF0);
        assertRejected(badChunk, "overruns");
        byte[] badVersion = ok.clone();
        ByteBuffer.wrap(badVersion).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 1);
        assertRejected(badVersion, "version");
    }

    @Test
    void rejectsOutOfRangeReferences() {
        // バッファの外を読む accessor
        assertRejected(glb(simpleTri().replace("\"count\":3,\"type\":\"VEC3\"", "\"count\":30,\"type\":\"VEC3\""), TRI_BIN), "outside");
        // 頂点数を超えるインデックス
        byte[] badIdx = cat(floats(0, 0, 0, 1, 0, 0, 0, 1, 0), u16(0, 1, 7));
        assertRejected(glb(simpleTri(), badIdx), "out of vertex range");
        // 存在しない mesh・material
        assertRejected(glb(simpleTri().replace("\"mesh\":0", "\"mesh\":5"), TRI_BIN), "node.mesh");
        assertRejected(glb(triJson("", ",\"material\":3", "\"nodes\":[{\"mesh\":0}]"), TRI_BIN), "material out of range");
    }

    @Test
    void rejectsUnsupportedFeatures() {
        assertRejected(glb(simpleTri().replace("\"buffers\":[{\"byteLength\":42}]", "\"buffers\":[{\"byteLength\":42,\"uri\":\"x.bin\"}]"), TRI_BIN), "external");
        assertRejected(glb(simpleTri().replace("\"type\":\"SCALAR\"}", "\"type\":\"SCALAR\",\"sparse\":{}}"), TRI_BIN), "sparse");
        assertRejected(glb(simpleTri().replace("\"asset\"", "\"extensionsRequired\":[\"KHR_draco_mesh_compression\"],\"asset\""), TRI_BIN), "extension");
    }

    @Test
    void rejectsCyclesAndSharedChildren() {
        String cyc = "\"nodes\":[{\"children\":[1]},{\"children\":[0]}],\"scenes\":[{\"nodes\":[0]}]";
        assertRejected(glb(triJson("", "", cyc), TRI_BIN), "cycle");
        String shared = "\"nodes\":[{\"children\":[2]},{\"children\":[2]},{\"mesh\":0}],\"scenes\":[{\"nodes\":[0,1]}]";
        assertRejected(glb(triJson("", "", shared), TRI_BIN), "more than one parent");
    }

    @Test
    void wrongJsonTypesAreReportedAsGltfExceptionNotRuntimeCrashes() {
        assertRejected(glb(simpleTri().replace("\"count\":3,\"type\":\"VEC3\"", "\"count\":\"three\",\"type\":\"VEC3\""), TRI_BIN), "malformed");
    }

    // ------------------------------------------------------------------ 描く・撃つ・メモリ

    @Test
    void aLoadedModelRendersAndCanBeRaycast() {
        Scene3DWorld w = new Scene3DWorld();
        w.clearColorArgb(0xFF000000);
        w.camera().lookAt(0.3f, 0.3f, 3, 0.3f, 0.3f, 0, 0, 1, 0);
        w.camera().setPerspective((float) Math.toRadians(45), 1f, 0.1f, 20f);
        String mats = "\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,0,0,1]},\"extensions\":{\"KHR_materials_unlit\":{}},\"doubleSided\":true}],";
        GltfBinaryLoader.Result r = GltfBinaryLoader.load(glb(triJson(mats, ",\"material\":0", "\"nodes\":[{\"mesh\":0}]"), TRI_BIN), w);
        Pixmap p = SoftwareScene3DRasterizer.render(w, 64, 64);
        assertEquals(0xFF0000, p.get(32, 36) & 0xFFFFFF, "三角形の中は赤（unlit）");
        assertEquals(0, p.get(2, 2) & 0xFFFFFF, "外は背景");
        Raycaster3D.Hit hit = new Raycaster3D.Hit();
        assertTrue(new Raycaster3D().cast(w, 0.25f, 0.25f, 5, 0, 0, -1, 100f, hit));
        assertEquals(r.rootNodes()[0], hit.node);
    }

    @Test
    void repeatedLoadsDoNotLeakDirectMemory() {
        byte[] data = glb(simpleTri(), TRI_BIN);
        BufferPoolMXBean direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(b -> b.getName().equals("direct")).findFirst().orElseThrow();
        for (int i = 0; i < 20; i++) GltfBinaryLoader.load(data, new Scene3DWorld()); // 暖機
        System.gc();
        long before = settledDirect(direct);
        for (int i = 0; i < 300; i++) GltfBinaryLoader.load(data, new Scene3DWorld());
        long after = settledDirect(direct);
        // 300 回分の世界（1 つ数十 KB〜）が回収されていれば、増分は小さい。リークなら数十 MB になる
        assertTrue(after - before < 8L * 1024 * 1024, "ダイレクトメモリが増え続けている: " + (after - before) + " B");
    }

    private static long settledDirect(BufferPoolMXBean direct) {
        long last = Long.MAX_VALUE;
        for (int i = 0; i < 10; i++) {
            System.gc();
            long now = direct.getMemoryUsed();
            if (now >= last) break;
            last = now;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return direct.getMemoryUsed();
    }

    /** 世界行列を読む小道具。 */
    private static final class OffHeapAccess {
        final Scene3DWorld w;
        final int node;

        OffHeapAccess(Scene3DWorld w, int node) {
            this.w = w;
            this.node = node;
        }

        float m(int i) {
            return w.arena().worldMat().get(node * Scene3DArena.MAT_STRIDE + i);
        }
    }
}
