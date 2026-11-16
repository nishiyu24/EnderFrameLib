package com.nishiyu.enderframelib.core.scene3d;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * glTF 2.0 の読み込み（{@code .glb} と、{@code data:} URI で埋め込んだ {@code .gltf}）。gson と {@link ByteBuffer} だけで、
 * {@link GeometryPool}・{@link MaterialPool}・{@link Scene3DArena} へ登録する（GL・AWT・paint に依存しない）。
 *
 * <p>対応: 三角形（TRIANGLES・STRIP・FAN）・{@code POSITION}/{@code NORMAL}/{@code TEXCOORD_0}/{@code TANGENT}・
 * 8/16/32 bit インデックス・{@code byteStride}・シーンのノード階層（TRS と {@code matrix}）・
 * {@code pbrMetallicRoughness}・{@code emissiveFactor}・{@code alphaMode}・{@code doubleSided}・{@code KHR_materials_unlit}・
 * {@code KHR_materials_emissive_strength}。法線が無ければ面の法線を平均して作る。
 * 未対応（読むと {@link GltfException}）: 外部ファイル参照の URI・sparse accessor・Draco 等の必須拡張。
 * テクスチャは現行のレンダラーが材質の色だけを使うので読み飛ばす（画像は読まない）。
 *
 * <p>色は glTF の線形値を sRGB へ符号化して {@link MaterialDesc#baseColorArgb()} にする（レンダラーは表示空間で計算する）。
 * 解析と検証を終えてから登録するので、失敗したときに {@link Scene3DWorld} は変わらない。
 * 解析用の一時領域はヒープ配列だけで、ダイレクトメモリは {@link GeometryPool}・{@link Scene3DArena} が持つ分だけ増える。
 */
public final class GltfBinaryLoader {
    /** 復号した画像の画素数の上限（これを超える画像は画像なしで読み込む）。 */
    private static final long MAX_IMAGE_PIXELS = 4096L * 4096L;

    private GltfBinaryLoader() {}

    /** 読み込めなかった理由（形式の誤り・未対応・上限超え）。 */
    public static final class GltfException extends IllegalArgumentException {
        public GltfException(String message) {
            super(message);
        }
    }

    /** 登録した結果。{@code rootNodes} はシーンの根（{@code parent} 直下）、{@code allNodes} は作ったノード全部。 */
    public record Result(int[] rootNodes, int[] allNodes, int meshCount, int materialCount, int skippedPrimitives) {}

    private static final int GLB_MAGIC = 0x46546C67; // "glTF"
    private static final int CHUNK_JSON = 0x4E4F534A, CHUNK_BIN = 0x004E4942;
    private static final int MAX_NODES = 1 << 20;
    private static final int MAX_DEPTH = 256;

    /** {@code world} の根に置く。 */
    public static Result load(byte[] data, Scene3DWorld world) {
        return load(data, world, Scene3DArena.NULL_NODE);
    }

    /** {@code parent} の子として置く（{@link Scene3DArena#NULL_NODE} なら根）。 */
    public static Result load(byte[] data, Scene3DWorld world, int parent) {
        if (data == null) throw new GltfException("data is null");
        Parsed p;
        try {
            p = parse(data);
        } catch (GltfException e) {
            throw e;
        } catch (RuntimeException e) {
            // JSON の型の食い違い（数値のはずが文字列など）・配列の欠けは、読めない文書として一括して扱う
            throw new GltfException("malformed glTF: " + e);
        }
        return commit(p, world, parent);
    }

    // ------------------------------------------------------------------ 解析（world には触れない）

    private static final class Prim {
        float[] vertices; // 12 floats / 頂点
        int[] indices;
        int material;     // glTF の材質の番号（-1 = 既定）
    }

    private static final class Node {
        float[] trs = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1}; // pos3 quat4 scale3
        int[] children = new int[0];
        int mesh = -1;
    }

    private static final class Mat {
        int argb = 0xFFFFFFFF;
        float metallic = 1f, roughness = 1f, emissiveStrength;
        int emissiveRgb;
        BlendMode3D blend = BlendMode3D.OPAQUE;
        boolean doubleSided, unlit;
        Texture3D map;      // baseColorTexture（無い・読めないときは null）
        boolean mapRepeat = true;
    }

    private static final class Parsed {
        final List<Node> nodes = new ArrayList<>();
        final List<List<Prim>> meshes = new ArrayList<>();
        final List<Mat> materials = new ArrayList<>();
        int[] sceneRoots = new int[0];
        int skipped;
    }

    private static Parsed parse(byte[] data) {
        byte[] bin = null;
        String json;
        if (data.length >= 12 && ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == GLB_MAGIC) {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(4) != 2) throw new GltfException("unsupported glb version " + b.getInt(4));
            long total = b.getInt(8) & 0xFFFFFFFFL;
            if (total > data.length || total < 12) throw new GltfException("glb length " + total + " exceeds data " + data.length);
            int pos = 12;
            String j = null;
            while (pos + 8 <= total) {
                long len = b.getInt(pos) & 0xFFFFFFFFL;
                int type = b.getInt(pos + 4);
                int start = pos + 8;
                if (len > total - start) throw new GltfException("glb chunk overruns the file");
                if (type == CHUNK_JSON && j == null) {
                    j = new String(data, start, (int) len, StandardCharsets.UTF_8);
                } else if (type == CHUNK_BIN && bin == null) {
                    bin = java.util.Arrays.copyOfRange(data, start, start + (int) len);
                }
                pos = start + (int) ((len + 3) & ~3L);
            }
            if (j == null) throw new GltfException("glb has no JSON chunk");
            json = j;
        } else {
            json = new String(data, StandardCharsets.UTF_8);
        }
        JsonObject root;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) throw new GltfException("glTF root is not an object");
            root = e.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            throw new GltfException("invalid glTF JSON: " + e.getMessage());
        }
        JsonObject asset = obj(root, "asset");
        if (asset == null || !"2.0".equals(str(asset, "version"))) throw new GltfException("glTF asset.version must be 2.0");
        JsonArray required = arr(root, "extensionsRequired");
        if (required != null) {
            for (JsonElement e : required) {
                String name = e.getAsString();
                if (!name.equals("KHR_materials_unlit") && !name.equals("KHR_materials_emissive_strength")) {
                    throw new GltfException("unsupported required extension " + name);
                }
            }
        }

        Ctx c = new Ctx(root, bin);
        Parsed out = new Parsed();
        JsonArray mats = arr(root, "materials");
        if (mats != null) for (JsonElement m : mats) out.materials.add(material(c, m.getAsJsonObject()));
        JsonArray meshes = arr(root, "meshes");
        if (meshes != null) {
            for (JsonElement m : meshes) out.meshes.add(mesh(c, m.getAsJsonObject(), out));
        }
        JsonArray nodes = arr(root, "nodes");
        if (nodes != null) {
            if (nodes.size() > MAX_NODES) throw new GltfException("too many nodes: " + nodes.size());
            for (JsonElement n : nodes) out.nodes.add(node(n.getAsJsonObject(), out));
        }
        for (Node n : out.nodes) {
            for (int ch : n.children) check(ch, out.nodes.size(), "node child");
            if (n.mesh >= out.meshes.size()) throw new GltfException("node.mesh out of range: " + n.mesh);
        }
        out.sceneRoots = sceneRoots(root, out.nodes.size());
        checkAcyclic(out);
        return out;
    }

    private static int[] sceneRoots(JsonObject root, int nodeCount) {
        JsonArray scenes = arr(root, "scenes");
        if (scenes != null && scenes.size() > 0) {
            int idx = root.has("scene") ? root.get("scene").getAsInt() : 0;
            check(idx, scenes.size(), "scene");
            JsonArray ns = arr(scenes.get(idx).getAsJsonObject(), "nodes");
            if (ns == null) return new int[0];
            int[] r = new int[ns.size()];
            for (int i = 0; i < r.length; i++) {
                r[i] = ns.get(i).getAsInt();
                check(r[i], nodeCount, "scene node");
            }
            return r;
        }
        // シーンが無ければ、他のノードの子になっていないノードを根にする
        boolean[] child = new boolean[nodeCount];
        JsonArray nodes = arr(root, "nodes");
        if (nodes != null) {
            for (JsonElement n : nodes) {
                JsonArray ch = arr(n.getAsJsonObject(), "children");
                if (ch != null) for (JsonElement e : ch) {
                    int i = e.getAsInt();
                    if (i >= 0 && i < nodeCount) child[i] = true;
                }
            }
        }
        List<Integer> r = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) if (!child[i]) r.add(i);
        return r.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void checkAcyclic(Parsed p) {
        int[] state = new int[p.nodes.size()]; // 0 未訪問 1 訪問中 2 済
        for (int r : p.sceneRoots) visit(p, r, state, 0);
    }

    private static void visit(Parsed p, int n, int[] state, int depth) {
        if (depth > MAX_DEPTH) throw new GltfException("node hierarchy deeper than " + MAX_DEPTH);
        if (state[n] == 1) throw new GltfException("node hierarchy has a cycle at node " + n);
        if (state[n] == 2) throw new GltfException("node " + n + " is referenced by more than one parent");
        state[n] = 1;
        for (int c : p.nodes.get(n).children) visit(p, c, state, depth + 1);
        state[n] = 2;
    }

    private static Node node(JsonObject o, Parsed out) {
        Node n = new Node();
        if (o.has("mesh")) n.mesh = o.get("mesh").getAsInt();
        JsonArray ch = arr(o, "children");
        if (ch != null) {
            n.children = new int[ch.size()];
            for (int i = 0; i < n.children.length; i++) n.children[i] = ch.get(i).getAsInt();
        }
        JsonArray m = arr(o, "matrix");
        if (m != null) {
            if (m.size() != 16) throw new GltfException("node.matrix must have 16 elements");
            float[] f = new float[16];
            for (int i = 0; i < 16; i++) f[i] = m.get(i).getAsFloat();
            decompose(f, n.trs);
        } else {
            JsonArray t = arr(o, "translation"), r = arr(o, "rotation"), s = arr(o, "scale");
            if (t != null) fill(t, 3, n.trs, 0, "translation");
            if (r != null) fill(r, 4, n.trs, 3, "rotation");
            if (s != null) fill(s, 3, n.trs, 7, "scale");
        }
        return n;
    }

    private static void fill(JsonArray a, int n, float[] dst, int off, String what) {
        if (a.size() != n) throw new GltfException("node." + what + " must have " + n + " elements");
        for (int i = 0; i < n; i++) dst[off + i] = a.get(i).getAsFloat();
    }

    /** 列優先 4×4 を 平行移動・回転（四元数）・拡大縮小へ分ける（せん断は捨てる）。 */
    static void decompose(float[] m, float[] trs) {
        trs[0] = m[12];
        trs[1] = m[13];
        trs[2] = m[14];
        float sx = len(m[0], m[1], m[2]), sy = len(m[4], m[5], m[6]), sz = len(m[8], m[9], m[10]);
        float det = m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2]);
        if (det < 0) sx = -sx;
        trs[7] = sx;
        trs[8] = sy;
        trs[9] = sz;
        if (sx == 0 || sy == 0 || sz == 0) {
            trs[3] = trs[4] = trs[5] = 0;
            trs[6] = 1;
            return;
        }
        float r00 = m[0] / sx, r10 = m[1] / sx, r20 = m[2] / sx;
        float r01 = m[4] / sy, r11 = m[5] / sy, r21 = m[6] / sy;
        float r02 = m[8] / sz, r12 = m[9] / sz, r22 = m[10] / sz;
        float tr = r00 + r11 + r22, qx, qy, qz, qw;
        if (tr > 0) {
            float s = (float) Math.sqrt(tr + 1f) * 2f;
            qw = 0.25f * s;
            qx = (r21 - r12) / s;
            qy = (r02 - r20) / s;
            qz = (r10 - r01) / s;
        } else if (r00 > r11 && r00 > r22) {
            float s = (float) Math.sqrt(1f + r00 - r11 - r22) * 2f;
            qw = (r21 - r12) / s;
            qx = 0.25f * s;
            qy = (r01 + r10) / s;
            qz = (r02 + r20) / s;
        } else if (r11 > r22) {
            float s = (float) Math.sqrt(1f + r11 - r00 - r22) * 2f;
            qw = (r02 - r20) / s;
            qx = (r01 + r10) / s;
            qy = 0.25f * s;
            qz = (r12 + r21) / s;
        } else {
            float s = (float) Math.sqrt(1f + r22 - r00 - r11) * 2f;
            qw = (r10 - r01) / s;
            qx = (r02 + r20) / s;
            qy = (r12 + r21) / s;
            qz = 0.25f * s;
        }
        trs[3] = qx;
        trs[4] = qy;
        trs[5] = qz;
        trs[6] = qw;
    }

    private static float len(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static Mat material(Ctx c, JsonObject o) {
        Mat m = new Mat();
        JsonObject pbr = obj(o, "pbrMetallicRoughness");
        float[] base = {1, 1, 1, 1};
        if (pbr != null) {
            JsonArray bc = arr(pbr, "baseColorFactor");
            if (bc != null) {
                if (bc.size() != 4) throw new GltfException("baseColorFactor must have 4 elements");
                for (int i = 0; i < 4; i++) base[i] = bc.get(i).getAsFloat();
            }
            if (pbr.has("metallicFactor")) m.metallic = clamp01(pbr.get("metallicFactor").getAsFloat());
            if (pbr.has("roughnessFactor")) m.roughness = clamp01(pbr.get("roughnessFactor").getAsFloat());
            JsonObject bt = obj(pbr, "baseColorTexture");
            // TEXCOORD_0 だけ読む（texCoord が 1 以上の材質は画像なしで読み込む）
            if (bt != null && bt.has("index") && (!bt.has("texCoord") || bt.get("texCoord").getAsInt() == 0)) {
                m.map = c.baseColorImage(bt.get("index").getAsInt(), m);
            }
        }
        m.argb = (byteOf(base[3]) << 24) | (byteOf(srgb(base[0])) << 16) | (byteOf(srgb(base[1])) << 8) | byteOf(srgb(base[2]));
        JsonObject ext = obj(o, "extensions");
        float strength = 1f;
        if (ext != null) {
            m.unlit = ext.has("KHR_materials_unlit");
            JsonObject es = obj(ext, "KHR_materials_emissive_strength");
            if (es != null && es.has("emissiveStrength")) strength = Math.max(0f, es.get("emissiveStrength").getAsFloat());
        }
        JsonArray em = arr(o, "emissiveFactor");
        if (em != null) {
            if (em.size() != 3) throw new GltfException("emissiveFactor must have 3 elements");
            float r = em.get(0).getAsFloat() * strength, g = em.get(1).getAsFloat() * strength, b = em.get(2).getAsFloat() * strength;
            float mx = Math.max(r, Math.max(g, b));
            if (mx > 0f) {
                // 色は最大成分で正規化し、強さ（0..4）へ分ける（MaterialDesc の emissive は「色 × 強さ」）
                m.emissiveStrength = Math.min(4f, mx);
                float k = 1f / Math.max(mx, 1e-6f);
                m.emissiveRgb = (byteOf(srgb(r * k)) << 16) | (byteOf(srgb(g * k)) << 8) | byteOf(srgb(b * k));
            }
        }
        String mode = str(o, "alphaMode");
        if ("MASK".equals(mode)) m.blend = BlendMode3D.MASK;
        else if ("BLEND".equals(mode)) m.blend = BlendMode3D.TRANSPARENT;
        else if (mode != null && !"OPAQUE".equals(mode)) throw new GltfException("unknown alphaMode " + mode);
        m.doubleSided = o.has("doubleSided") && o.get("doubleSided").getAsBoolean();
        return m;
    }

    private static List<Prim> mesh(Ctx c, JsonObject o, Parsed out) {
        List<Prim> prims = new ArrayList<>();
        JsonArray ps = arr(o, "primitives");
        if (ps == null) return prims;
        for (JsonElement pe : ps) {
            JsonObject p = pe.getAsJsonObject();
            int mode = p.has("mode") ? p.get("mode").getAsInt() : 4;
            if (mode != 4 && mode != 5 && mode != 6) {
                out.skipped++; // 点・線は描かない
                continue;
            }
            JsonObject attrs = obj(p, "attributes");
            if (attrs == null || !attrs.has("POSITION")) throw new GltfException("primitive has no POSITION");
            float[] pos = c.floats(attrs.get("POSITION").getAsInt(), 3);
            int n = pos.length / 3;
            float[] nor = attrs.has("NORMAL") ? c.floats(attrs.get("NORMAL").getAsInt(), 3) : null;
            float[] uv = attrs.has("TEXCOORD_0") ? c.floats(attrs.get("TEXCOORD_0").getAsInt(), 2) : null;
            float[] tan = attrs.has("TANGENT") ? c.floats(attrs.get("TANGENT").getAsInt(), 4) : null;
            if ((nor != null && nor.length / 3 != n) || (uv != null && uv.length / 2 != n) || (tan != null && tan.length / 4 != n)) {
                throw new GltfException("attribute counts differ from POSITION");
            }
            int[] idx;
            if (p.has("indices")) idx = c.indices(p.get("indices").getAsInt());
            else {
                idx = new int[n];
                for (int i = 0; i < n; i++) idx[i] = i;
            }
            idx = toTriangles(idx, mode);
            for (int i : idx) if (i < 0 || i >= n) throw new GltfException("index " + i + " out of vertex range " + n);
            if (nor == null) nor = smoothNormals(pos, idx);
            Prim prim = new Prim();
            prim.vertices = new float[n * GeometryPool.VERTEX_FLOATS];
            for (int i = 0; i < n; i++) {
                int o12 = i * GeometryPool.VERTEX_FLOATS;
                System.arraycopy(pos, i * 3, prim.vertices, o12, 3);
                System.arraycopy(nor, i * 3, prim.vertices, o12 + 3, 3);
                if (uv != null) System.arraycopy(uv, i * 2, prim.vertices, o12 + 6, 2);
                if (tan != null) System.arraycopy(tan, i * 4, prim.vertices, o12 + 8, 4);
                else prim.vertices[o12 + 11] = 1f;
            }
            prim.indices = idx;
            prim.material = p.has("material") ? p.get("material").getAsInt() : -1;
            if (prim.material >= out.materials.size() && prim.material >= 0) throw new GltfException("material out of range: " + prim.material);
            if (idx.length == 0) continue;
            prims.add(prim);
        }
        return prims;
    }

    private static int[] toTriangles(int[] idx, int mode) {
        if (mode == 4) {
            if (idx.length % 3 != 0) throw new GltfException("TRIANGLES index count is not a multiple of 3");
            return idx;
        }
        if (idx.length < 3) return new int[0];
        int tris = idx.length - 2;
        int[] out = new int[tris * 3];
        for (int i = 0; i < tris; i++) {
            if (mode == 5) { // STRIP: 奇数番目は向きを揃えるため入れ替える
                out[i * 3] = idx[i + (i & 1)];
                out[i * 3 + 1] = idx[i + 1 - (i & 1)];
                out[i * 3 + 2] = idx[i + 2];
            } else { // FAN
                out[i * 3] = idx[0];
                out[i * 3 + 1] = idx[i + 1];
                out[i * 3 + 2] = idx[i + 2];
            }
        }
        return out;
    }

    private static float[] smoothNormals(float[] pos, int[] idx) {
        float[] n = new float[pos.length];
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3;
            float e1x = pos[b] - pos[a], e1y = pos[b + 1] - pos[a + 1], e1z = pos[b + 2] - pos[a + 2];
            float e2x = pos[c] - pos[a], e2y = pos[c + 1] - pos[a + 1], e2z = pos[c + 2] - pos[a + 2];
            float x = e1y * e2z - e1z * e2y, y = e1z * e2x - e1x * e2z, z = e1x * e2y - e1y * e2x; // 面積で重み付け
            for (int v : new int[]{a, b, c}) {
                n[v] += x;
                n[v + 1] += y;
                n[v + 2] += z;
            }
        }
        for (int i = 0; i < n.length; i += 3) {
            float l = len(n[i], n[i + 1], n[i + 2]);
            if (l > 1e-20f) {
                n[i] /= l;
                n[i + 1] /= l;
                n[i + 2] /= l;
            } else {
                n[i + 1] = 1f;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ バッファ・accessor

    private static final class Ctx {
        final JsonObject root;
        final byte[] glbBin;
        final Map<Integer, byte[]> buffers = new HashMap<>();

        Ctx(JsonObject root, byte[] glbBin) {
            this.root = root;
            this.glbBin = glbBin;
        }

        private final java.util.Map<Integer, Texture3D> images = new java.util.HashMap<>();
        private final java.util.Set<Integer> failedImages = new java.util.HashSet<>();

        /**
         * {@code textures[index]} の画像を {@link Texture3D} にする（同じ画像は 1 回だけ復号して共有）。{@code m.mapRepeat} へ
         * sampler の wrap を写す（既定は REPEAT。S・T の片方でも REPEAT でなければ張り付き）。復号できない・大きすぎる画像は
         * null（モデルは画像なしで読む）。
         */
        Texture3D baseColorImage(int textureIndex, Mat m) {
            JsonArray texs = arr(root, "textures");
            if (texs == null) throw new GltfException("no textures");
            check(textureIndex, texs.size(), "texture");
            JsonObject t = texs.get(textureIndex).getAsJsonObject();
            m.mapRepeat = true;
            if (t.has("sampler")) {
                JsonArray ss = arr(root, "samplers");
                int si = t.get("sampler").getAsInt();
                if (ss == null) throw new GltfException("no samplers");
                check(si, ss.size(), "sampler");
                JsonObject s = ss.get(si).getAsJsonObject();
                int ws = s.has("wrapS") ? s.get("wrapS").getAsInt() : 10497;
                int wt = s.has("wrapT") ? s.get("wrapT").getAsInt() : 10497;
                m.mapRepeat = ws == 10497 && wt == 10497;
            }
            if (!t.has("source")) return null;
            return image(t.get("source").getAsInt());
        }

        private Texture3D image(int i) {
            Texture3D cached = images.get(i);
            if (cached != null) return cached;
            if (failedImages.contains(i)) return null;
            JsonArray imgs = arr(root, "images");
            if (imgs == null) throw new GltfException("no images");
            check(i, imgs.size(), "image");
            JsonObject o = imgs.get(i).getAsJsonObject();
            byte[] bytes = null;
            String uri = str(o, "uri");
            if (uri != null) {
                if (!uri.startsWith("data:")) throw new GltfException("external image files are not supported: " + uri);
                int comma = uri.indexOf(',');
                if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) throw new GltfException("only base64 data URIs are supported");
                try {
                    bytes = Base64.getDecoder().decode(uri.substring(comma + 1));
                } catch (IllegalArgumentException e) {
                    throw new GltfException("invalid base64 in image " + i);
                }
            } else if (o.has("bufferView")) {
                JsonArray bvs = arr(root, "bufferViews");
                int bvi = o.get("bufferView").getAsInt();
                if (bvs == null) throw new GltfException("no bufferViews");
                check(bvi, bvs.size(), "bufferView");
                JsonObject bv = bvs.get(bvi).getAsJsonObject();
                byte[] buf = buffer(bv.get("buffer").getAsInt());
                long off = bv.has("byteOffset") ? bv.get("byteOffset").getAsLong() : 0;
                long len = bv.get("byteLength").getAsLong();
                if (off < 0 || len < 0 || off + len > buf.length) throw new GltfException("image " + i + " reads outside its buffer");
                bytes = java.util.Arrays.copyOfRange(buf, (int) off, (int) (off + len));
            }
            Texture3D tex = null;
            if (bytes != null) {
                try {
                    com.nishiyu.enderframelib.core.raster.DecodedBitmap bmp =
                            com.nishiyu.enderframelib.core.raster.ImageDecoder.standard().decode(bytes);
                    if (bmp != null && (long) bmp.width() * bmp.height() <= MAX_IMAGE_PIXELS) {
                        tex = new Texture3D(bmp.width(), bmp.height(), bmp.argb());
                    }
                } catch (java.io.IOException | RuntimeException e) {
                    tex = null; // 読めない画像は無いものとして扱う
                }
            }
            if (tex == null) failedImages.add(i);
            else images.put(i, tex);
            return tex;
        }

        byte[] buffer(int i) {
            byte[] cached = buffers.get(i);
            if (cached != null) return cached;
            JsonArray bs = arr(root, "buffers");
            if (bs == null) throw new GltfException("no buffers");
            check(i, bs.size(), "buffer");
            JsonObject b = bs.get(i).getAsJsonObject();
            long byteLength = b.has("byteLength") ? b.get("byteLength").getAsLong() : -1;
            byte[] data;
            String uri = str(b, "uri");
            if (uri == null) {
                if (i != 0 || glbBin == null) throw new GltfException("buffer " + i + " has no uri and no GLB binary chunk");
                data = glbBin;
            } else if (uri.startsWith("data:")) {
                int comma = uri.indexOf(',');
                if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) throw new GltfException("only base64 data URIs are supported");
                try {
                    data = Base64.getDecoder().decode(uri.substring(comma + 1));
                } catch (IllegalArgumentException e) {
                    throw new GltfException("invalid base64 in buffer " + i);
                }
            } else {
                throw new GltfException("external buffer files are not supported: " + uri);
            }
            if (byteLength > data.length) throw new GltfException("buffer " + i + " is shorter than byteLength");
            buffers.put(i, data);
            return data;
        }

        /** accessor の要素を float の平坦な配列で（normalized 整数は 0..1 / -1..1 へ）。 */
        float[] floats(int accessor, int expectedComponents) {
            Acc a = acc(accessor);
            if (a.components != expectedComponents) {
                throw new GltfException("accessor " + accessor + " has " + a.components + " components, expected " + expectedComponents);
            }
            float[] out = new float[a.count * a.components];
            ByteBuffer bb = a.view.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < a.count; i++) {
                for (int k = 0; k < a.components; k++) {
                    out[i * a.components + k] = a.read(bb, i, k);
                }
            }
            return out;
        }

        int[] indices(int accessor) {
            Acc a = acc(accessor);
            if (a.components != 1) throw new GltfException("index accessor must be SCALAR");
            if (a.ctype != 5121 && a.ctype != 5123 && a.ctype != 5125) throw new GltfException("index componentType must be unsigned");
            int[] out = new int[a.count];
            ByteBuffer bb = a.view.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < a.count; i++) {
                int pos = a.offset(i, 0);
                out[i] = switch (a.ctype) {
                    case 5121 -> bb.get(pos) & 0xFF;
                    case 5123 -> bb.getShort(pos) & 0xFFFF;
                    default -> {
                        int v = bb.getInt(pos);
                        if (v < 0) throw new GltfException("index above 2^31");
                        yield v;
                    }
                };
            }
            return out;
        }

        Acc acc(int i) {
            JsonArray as = arr(root, "accessors");
            if (as == null) throw new GltfException("no accessors");
            check(i, as.size(), "accessor");
            JsonObject o = as.get(i).getAsJsonObject();
            if (o.has("sparse")) throw new GltfException("sparse accessors are not supported");
            if (!o.has("bufferView")) throw new GltfException("accessor " + i + " has no bufferView");
            Acc a = new Acc();
            a.ctype = o.get("componentType").getAsInt();
            a.normalized = o.has("normalized") && o.get("normalized").getAsBoolean();
            a.count = o.get("count").getAsInt();
            if (a.count < 0 || a.count > (1 << 26)) throw new GltfException("accessor count out of range: " + a.count);
            a.components = switch (str(o, "type")) {
                case "SCALAR" -> 1;
                case "VEC2" -> 2;
                case "VEC3" -> 3;
                case "VEC4" -> 4;
                default -> throw new GltfException("unsupported accessor type " + str(o, "type"));
            };
            int csize = switch (a.ctype) {
                case 5120, 5121 -> 1;
                case 5122, 5123 -> 2;
                case 5125, 5126 -> 4;
                default -> throw new GltfException("unsupported componentType " + a.ctype);
            };
            JsonArray bvs = arr(root, "bufferViews");
            int bvi = o.get("bufferView").getAsInt();
            if (bvs == null) throw new GltfException("no bufferViews");
            check(bvi, bvs.size(), "bufferView");
            JsonObject bv = bvs.get(bvi).getAsJsonObject();
            byte[] buf = buffer(bv.get("buffer").getAsInt());
            long viewOff = bv.has("byteOffset") ? bv.get("byteOffset").getAsLong() : 0;
            long viewLen = bv.get("byteLength").getAsLong();
            long accOff = o.has("byteOffset") ? o.get("byteOffset").getAsLong() : 0;
            a.stride = bv.has("byteStride") ? bv.get("byteStride").getAsInt() : a.components * csize;
            a.csize = csize;
            long start = viewOff + accOff;
            long need = a.count == 0 ? 0 : (long) (a.count - 1) * a.stride + (long) a.components * csize;
            if (viewOff < 0 || accOff < 0 || viewOff + viewLen > buf.length || accOff + need > viewLen) {
                throw new GltfException("accessor " + i + " reads outside its bufferView/buffer");
            }
            a.view = ByteBuffer.wrap(buf, (int) start, (int) Math.max(need, 0)).slice();
            return a;
        }
    }

    private static final class Acc {
        int ctype, count, components, stride, csize;
        boolean normalized;
        ByteBuffer view;

        int offset(int i, int k) {
            return i * stride + k * csize;
        }

        float read(ByteBuffer bb, int i, int k) {
            int pos = offset(i, k);
            return switch (ctype) {
                case 5126 -> bb.getFloat(pos);
                case 5120 -> normalized ? Math.max(bb.get(pos) / 127f, -1f) : bb.get(pos);
                case 5121 -> normalized ? (bb.get(pos) & 0xFF) / 255f : bb.get(pos) & 0xFF;
                case 5122 -> normalized ? Math.max(bb.getShort(pos) / 32767f, -1f) : bb.getShort(pos);
                case 5123 -> normalized ? (bb.getShort(pos) & 0xFFFF) / 65535f : bb.getShort(pos) & 0xFFFF;
                default -> bb.getInt(pos);
            };
        }
    }

    // ------------------------------------------------------------------ 登録

    private static Result commit(Parsed p, Scene3DWorld world, int parent) {
        Scene3DArena arena = world.arena();
        if (parent != Scene3DArena.NULL_NODE && !arena.isAlive(parent)) throw new GltfException("parent node is not alive");
        MaterialDesc[] mats = new MaterialDesc[p.materials.size()];
        for (int i = 0; i < mats.length; i++) {
            Mat m = p.materials.get(i);
            mats[i] = world.materials().register(m.argb, m.metallic, m.roughness, m.emissiveRgb, m.emissiveStrength,
                    m.blend, m.doubleSided, m.unlit, m.map, m.mapRepeat);
        }
        MaterialDesc[] defaultMat = new MaterialDesc[1];
        MeshHandle[][] handles = new MeshHandle[p.meshes.size()][];
        int meshCount = 0;
        for (int mi = 0; mi < handles.length; mi++) {
            List<Prim> ps = p.meshes.get(mi);
            handles[mi] = new MeshHandle[ps.size()];
            for (int k = 0; k < ps.size(); k++) {
                handles[mi][k] = world.geometry().registerMesh(ps.get(k).vertices, ps.get(k).indices);
                meshCount++;
            }
        }
        List<Integer> all = new ArrayList<>();
        List<Integer> roots = new ArrayList<>();
        for (int r : p.sceneRoots) roots.add(place(p, world, r, parent, mats, defaultMat, handles, all));
        return new Result(roots.stream().mapToInt(Integer::intValue).toArray(), all.stream().mapToInt(Integer::intValue).toArray(),
                meshCount, mats.length + (defaultMat[0] != null ? 1 : 0), p.skipped);
    }

    private static int place(Parsed p, Scene3DWorld world, int nodeIdx, int parent, MaterialDesc[] mats,
                             MaterialDesc[] defaultMat, MeshHandle[][] handles, List<Integer> all) {
        Scene3DArena arena = world.arena();
        Node n = p.nodes.get(nodeIdx);
        int id = parent == Scene3DArena.NULL_NODE ? arena.allocateNode() : arena.allocateNode(parent);
        all.add(id);
        arena.setPosition(id, n.trs[0], n.trs[1], n.trs[2]);
        arena.setRotationQuat(id, n.trs[3], n.trs[4], n.trs[5], n.trs[6]);
        arena.setScale(id, n.trs[7], n.trs[8], n.trs[9]);
        if (n.mesh >= 0) {
            List<Prim> ps = p.meshes.get(n.mesh);
            for (int k = 0; k < ps.size(); k++) {
                // 1 つ目の形は節自身へ、2 つ目以降は同じ姿勢の子の節へ
                int target = id;
                if (k > 0) {
                    target = arena.allocateNode(id);
                    all.add(target);
                }
                arena.setMesh(target, handles[n.mesh][k].meshId());
                int mi = ps.get(k).material;
                MaterialDesc md;
                if (mi >= 0) md = mats[mi];
                else {
                    if (defaultMat[0] == null) defaultMat[0] = world.materials().register(0xFFFFFFFF, 1f, 1f, 0, 0f, null, false, false);
                    md = defaultMat[0];
                }
                arena.setMaterial(target, md.materialId());
            }
        }
        for (int c : n.children) place(p, world, c, id, mats, defaultMat, handles, all);
        return id;
    }

    // ------------------------------------------------------------------ 小道具

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static void check(int i, int size, String what) {
        if (i < 0 || i >= size) throw new GltfException(what + " index out of range: " + i + " (size " + size + ")");
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static int byteOf(float v) {
        return Math.round(clamp01(v) * 255f);
    }

    private static float srgb(float lin) {
        float c = clamp01(lin);
        return c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1.0 / 2.4) - 0.055);
    }
}
