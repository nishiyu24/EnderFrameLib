package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.raster.DecodedBitmap;
import com.nishiyu.enderframelib.core.raster.ImageDecoder;
import com.nishiyu.enderframelib.core.scene3d.BlendMode3D;
import com.nishiyu.enderframelib.core.scene3d.GltfBinaryLoader;
import com.nishiyu.enderframelib.core.scene3d.LightDesc;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DArena;
import com.nishiyu.enderframelib.core.scene3d.Scene3DTags;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import com.nishiyu.enderframelib.core.scene3d.Texture3D;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * 宣言的 3D（X3-3）: {@code <scene-3d>} の子 {@code <camera-3d>}・{@code <light-3d>}・{@code <mesh-3d>}・{@code <group-3d>} の属性を
 * {@link Scene3DWorld} へ写す。DOM は 2D の箱を作らないので、ここが記述の唯一の読み手。{@code data-bind="rotation:heroRot"} のような
 * 属性への束縛（{@link Bindings}）は属性を書き換えるだけなので、次の同期でそのまま反映される。
 *
 * <p>差分更新: 各要素の属性の署名が変わったときだけ世界へ書く（静止した文書では世界の変更回数が増えない = 描き直されない）。
 * 消えた要素のノードは解放する。形（箱・球・板）と材質と画像は {@code <scene-3d>} ごとに同じ記述を共有するので、記述を変え続けても
 * プールは増えない。</p>
 *
 * <p>属性（数値は空白かカンマ区切り。角度は度）:</p>
 * <ul>
 *   <li>{@code <scene-3d>}: {@code data-background}（色）・{@code data-bloom}（{@code true}）・{@code data-bloom-threshold}・
 *       {@code data-bloom-intensity}・{@code data-bloom-sigma}・{@code data-msaa}・{@code data-fov}（{@code <camera-3d>} が無いときの既定）</li>
 *   <li>{@code <camera-3d>}: {@code position}・{@code look-at}・{@code up}・{@code fov}・{@code near}・{@code far}</li>
 *   <li>{@code <light-3d>}: {@code type}（{@code directional}・{@code ambient}・{@code point}・{@code spot}）・{@code direction}・
 *       {@code position}・{@code color}・{@code intensity}・{@code range}・{@code inner}・{@code outer}</li>
 *   <li>{@code <group-3d>} / {@code <mesh-3d>}: {@code position}・{@code rotation}・{@code scale}・{@code hidden}。{@code <mesh-3d>} は
 *       {@code shape}（{@code box}・{@code sphere}・{@code plane}）と {@code size}・{@code radius}、または {@code src}（{@code data:} か
 *       {@code classpath:} の glTF）、材質 {@code color}・{@code metallic}・{@code roughness}・{@code emissive}・
 *       {@code emissive-strength}・{@code unlit}・{@code double-sided}・{@code texture}（{@code data:} か {@code classpath:} の画像）・
 *       {@code texture-repeat}</li>
 * </ul>
 */
public final class Scene3DDomSync {
    private final LongFunction<Scene3DWorld> worlds;
    private final Map<Long, SceneState> scenes = new HashMap<>();
    private final List<HtmlNode> foundScratch = new ArrayList<>(2);

    public Scene3DDomSync(LongFunction<Scene3DWorld> worlds) {
        this.worlds = worlds;
    }

    /** 文書の中の全 {@code <scene-3d>} を同期する。消えたシーンの状態は捨てる。 */
    public void sync(HtmlNode root) {
        if (root == null) return;
        // シーンが無い文書は、節が新しく作られていないかぎり（同じ文書の同じ確保の通し番号）、文書全体を歩かない
        com.nishiyu.enderframelib.core.kernel.memory.DocumentArena arena = scenes.isEmpty() ? root.arena() : null;
        if (arena != null && arena == lastArena && arena.allocSerial() == lastAllocSerial && lastRoot == root) return;
        foundScratch.clear();
        find(root, foundScratch);
        if (arena != null && foundScratch.isEmpty()) {
            lastArena = arena;
            lastAllocSerial = arena.allocSerial();
            lastRoot = root;
        } else {
            lastArena = null;
        }
        if (foundScratch.isEmpty() && scenes.isEmpty()) return;
        java.util.Set<Long> alive = new java.util.HashSet<>();
        for (int i = 0, fn = foundScratch.size(); i < fn; i++) {
            HtmlNode scene = foundScratch.get(i);
            long id = scene.nodeId();
            alive.add(id);
            SceneState st = scenes.computeIfAbsent(id, k -> new SceneState(worlds.apply(id)));
            st.sync(scene);
        }
        scenes.keySet().retainAll(alive);
        foundScratch.clear();
    }

    private com.nishiyu.enderframelib.core.kernel.memory.DocumentArena lastArena;
    private int lastAllocSerial;
    private HtmlNode lastRoot;

    public int sceneCount() { return scenes.size(); }

    private static void find(HtmlNode n, List<HtmlNode> out) {
        if (Scene3DTags.isScene(n.tag)) {
            out.add(n);
            return;
        }
        List<HtmlNode> ch = n.children;
        for (int i = 0, cn = ch.size(); i < cn; i++) find(ch.get(i), out);
    }

    // ------------------------------------------------------------------ 1 つのシーン

    private static final class Elem {
        int anchor = Scene3DArena.NULL_NODE;
        Elem parent;
        int sig;
        boolean seen;
        String src;
        boolean hasShape;
    }

    private static final class SceneState {
        final Scene3DWorld world;
        final Map<HtmlNode, Elem> elems = new IdentityHashMap<>();
        final Map<String, MeshHandle> meshes = new HashMap<>();
        final Map<String, MaterialDesc> materials = new HashMap<>();
        final Map<String, Texture3D> textures = new HashMap<>();
        int sceneSig = Integer.MIN_VALUE, cameraSig = Integer.MIN_VALUE, lightsSig = Integer.MIN_VALUE;
        boolean hadCamera;
        // 1 回の同期の作業用
        HtmlNode camera;
        final List<HtmlNode> lights = new ArrayList<>();

        SceneState(Scene3DWorld world) {
            this.world = world;
        }

        void sync(HtmlNode scene) {
            int ss = hash(scene.attrs);
            if (ss != sceneSig) {
                sceneSig = ss;
                applyScene(scene);
            }
            camera = null;
            lights.clear();
            for (Elem e : elems.values()) e.seen = false;
            walk(scene, Scene3DArena.NULL_NODE, null);
            removeUnseen();
            applyCamera(scene);
            applyLights();
        }

        private void walk(HtmlNode parent, int parentNode, Elem parentElem) {
            for (HtmlNode c : parent.children) {
                String tag = c.tag;
                if (tag == null) continue;
                switch (tag) {
                    case "camera-3d" -> { if (camera == null) camera = c; }
                    case "light-3d" -> lights.add(c);
                    case "group-3d", "mesh-3d" -> {
                        Elem e = elems.get(c);
                        int sig = hash(c.attrs);
                        if (e == null) {
                            e = new Elem();
                            e.anchor = world.arena().allocateNode(parentNode);
                            e.parent = parentElem;
                            elems.put(c, e);
                            e.sig = sig + 1; // 初回は必ず適用
                        } else if (e.parent != parentElem) {
                            world.arena().setParent(e.anchor, parentNode);
                            e.parent = parentElem;
                        }
                        e.seen = true;
                        if (e.sig != sig) {
                            e.sig = sig;
                            apply(c, e);
                        }
                        if ("group-3d".equals(tag)) walk(c, e.anchor, e);
                    }
                    default -> { }
                }
            }
        }

        private void removeUnseen() {
            List<Map.Entry<HtmlNode, Elem>> gone = new ArrayList<>();
            for (Map.Entry<HtmlNode, Elem> en : elems.entrySet()) if (!en.getValue().seen) gone.add(en);
            if (gone.isEmpty()) return;
            // 祖先も消えた要素は、祖先のサブツリーの解放で一緒に解放される（二重に解放しない）
            for (Map.Entry<HtmlNode, Elem> en : gone) {
                Elem e = en.getValue();
                boolean ancestorGone = false;
                for (Elem p = e.parent; p != null; p = p.parent) if (!p.seen) { ancestorGone = true; break; }
                if (!ancestorGone && world.arena().isAlive(e.anchor)) world.arena().freeNode(e.anchor);
            }
            for (Map.Entry<HtmlNode, Elem> en : gone) elems.remove(en.getKey());
        }

        // ---------------------------------------------------------------- 属性 → 世界

        private void applyScene(HtmlNode scene) {
            Map<String, String> a = scene.attrs;
            String bg = a.get("data-background");
            world.clearColorArgb(bg != null && ColorParser.isColor(bg) ? ColorParser.parseColor(bg) : 0x00000000);
            boolean bloom = isTrue(a.get("data-bloom"));
            world.bloom(bloom, num(a.get("data-bloom-threshold"), 1f), num(a.get("data-bloom-intensity"), 0.8f),
                    num(a.get("data-bloom-sigma"), 12f));
            world.msaaSamples((int) num(a.get("data-msaa"), 0f));
        }

        private void applyCamera(HtmlNode scene) {
            int sig = camera != null ? hash(camera.attrs) : 31 * hash(scene.attrs) + 7;
            if (hadCamera == (camera != null) && sig == cameraSig) return;
            hadCamera = camera != null;
            cameraSig = sig;
            Map<String, String> a = camera != null ? camera.attrs : Map.of();
            float[] pos = vec(a.get("position"), 3, new float[]{0f, 0f, 5f});
            float[] at = vec(a.get("look-at"), 3, new float[]{0f, 0f, 0f});
            float[] up = vec(a.get("up"), 3, new float[]{0f, 1f, 0f});
            float fov = num(camera != null ? a.get("fov") : scene.attrs.get("data-fov"), 45f);
            world.camera().lookAt(pos[0], pos[1], pos[2], at[0], at[1], at[2], up[0], up[1], up[2]);
            world.camera().setPerspective((float) Math.toRadians(fov), world.camera().aspect(),
                    num(a.get("near"), 0.1f), num(a.get("far"), 100f));
        }

        private void applyLights() {
            int sig = lights.size();
            for (HtmlNode l : lights) sig = 31 * sig + hash(l.attrs);
            if (sig == lightsSig) return;
            lightsSig = sig;
            LightDesc ld = world.light();
            ld.clearExtras();
            if (lights.isEmpty()) return; // 記述が無ければ既定のライトのまま
            boolean directional = false;
            ld.setColor(0f, 0f, 0f, 0f); // 記述したライトだけにする
            ld.setAmbient(0f, 0f, 0f);
            for (HtmlNode l : lights) {
                Map<String, String> a = l.attrs;
                String type = a.getOrDefault("type", "directional").trim().toLowerCase(java.util.Locale.ROOT);
                int argb = color(a.get("color"), 0xFFFFFFFF);
                float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
                float inten = num(a.get("intensity"), 1f);
                float[] pos = vec(a.get("position"), 3, new float[]{0f, 3f, 0f});
                switch (type) {
                    case "ambient" -> ld.setAmbient(r * inten, g * inten, b * inten);
                    case "point" -> ld.addPoint(pos[0], pos[1], pos[2], r, g, b, inten, num(a.get("range"), 10f));
                    case "spot" -> {
                        float[] dir = vec(a.get("direction"), 3, new float[]{0f, -1f, 0f});
                        ld.addSpot(pos[0], pos[1], pos[2], dir[0], dir[1], dir[2], r, g, b, inten, num(a.get("range"), 10f),
                                num(a.get("inner"), 20f), num(a.get("outer"), 30f));
                    }
                    default -> { // directional: 最初の 1 灯だけが主ライト
                        if (!directional) {
                            directional = true;
                            float[] dir = vec(a.get("direction"), 3, new float[]{-0.4f, -0.8f, -0.45f});
                            ld.setDirection(dir[0], dir[1], dir[2]);
                            ld.setColor(r, g, b, inten);
                        }
                    }
                }
            }
        }

        private void apply(HtmlNode el, Elem e) {
            Scene3DArena arena = world.arena();
            Map<String, String> a = el.attrs;
            float[] p = vec(a.get("position"), 3, new float[]{0f, 0f, 0f});
            float[] r = vec(a.get("rotation"), 3, new float[]{0f, 0f, 0f});
            float[] s = vec(a.get("scale"), 3, null);
            if (s == null) {
                float u = num(a.get("scale"), 1f);
                s = new float[]{u, u, u};
            }
            arena.setPosition(e.anchor, p[0], p[1], p[2]);
            arena.setRotationEulerRadians(e.anchor, (float) Math.toRadians(r[0]), (float) Math.toRadians(r[1]), (float) Math.toRadians(r[2]));
            arena.setScale(e.anchor, s[0], s[1], s[2]);
            arena.setVisible(e.anchor, !a.containsKey("hidden"));
            if (!"mesh-3d".equals(el.tag)) return;

            String src = a.get("src");
            if (src != null && !src.isBlank()) {
                if (!src.equals(e.src)) { // 読み込み直し: 前の glTF の子ノードを外す
                    freeChildren(e.anchor);
                    e.src = src;
                    byte[] bytes = bytes(src);
                    if (bytes != null) {
                        try {
                            GltfBinaryLoader.load(bytes, world, e.anchor);
                        } catch (RuntimeException ex) {
                            // 読めないモデルは何も出さない（文書は壊さない）
                        }
                    }
                }
                if (e.hasShape) { arena.setMesh(e.anchor, -1); e.hasShape = false; }
                return;
            }
            if (e.src != null) { freeChildren(e.anchor); e.src = null; }
            MeshHandle mesh = shape(a);
            arena.setMesh(e.anchor, mesh.meshId());
            arena.setMaterial(e.anchor, material(a).materialId());
            e.hasShape = true;
        }

        private void freeChildren(int anchor) {
            Scene3DArena arena = world.arena();
            int child = arena.topology().get(anchor * Scene3DArena.TOPO_STRIDE + Scene3DArena.TOPO_FIRST_CHILD);
            while (child != Scene3DArena.NULL_NODE) {
                int next = arena.topology().get(child * Scene3DArena.TOPO_STRIDE + Scene3DArena.TOPO_NEXT_SIBLING);
                arena.freeNode(child);
                child = next;
            }
        }

        private MeshHandle shape(Map<String, String> a) {
            String shape = a.getOrDefault("shape", "box").trim().toLowerCase(java.util.Locale.ROOT);
            String key;
            switch (shape) {
                case "sphere" -> {
                    float rad = Math.max(1e-3f, num(a.get("radius"), 0.5f));
                    key = "sphere:" + rad;
                    MeshHandle h = meshes.get(key);
                    if (h == null) meshes.put(key, h = world.geometry().createSphere(rad, 16, 24));
                    return h;
                }
                case "plane" -> {
                    float[] sz = vec(a.get("size"), 2, new float[]{1f, 1f});
                    key = "plane:" + sz[0] + "," + sz[1];
                    MeshHandle h = meshes.get(key);
                    if (h == null) meshes.put(key, h = world.geometry().createPlane(sz[0], sz[1]));
                    return h;
                }
                default -> {
                    float[] sz = vec(a.get("size"), 3, null);
                    if (sz == null) {
                        float u = num(a.get("size"), 1f);
                        sz = new float[]{u, u, u};
                    }
                    key = "box:" + sz[0] + "," + sz[1] + "," + sz[2];
                    MeshHandle h = meshes.get(key);
                    if (h == null) meshes.put(key, h = world.geometry().createBox(sz[0], sz[1], sz[2]));
                    return h;
                }
            }
        }

        private MaterialDesc material(Map<String, String> a) {
            int argb = color(a.get("color"), 0xFFFFFFFF);
            float metallic = clamp01(num(a.get("metallic"), 0f)), rough = clamp01(num(a.get("roughness"), 0.55f));
            int emissive = color(a.get("emissive"), 0xFF000000) & 0xFFFFFF;
            float es = Math.max(0f, num(a.get("emissive-strength"), emissive != 0 ? 1f : 0f));
            boolean unlit = isTrue(a.get("unlit")), dbl = isTrue(a.get("double-sided"));
            String tex = a.get("texture");
            boolean rep = isTrue(a.get("texture-repeat"));
            String key = argb + "|" + metallic + "|" + rough + "|" + emissive + "|" + es + "|" + unlit + "|" + dbl + "|" + tex + "|" + rep;
            MaterialDesc m = materials.get(key);
            if (m != null) return m;
            Texture3D map = tex == null || tex.isBlank() ? null : texture(tex);
            BlendMode3D mode = (argb >>> 24) < 255 ? BlendMode3D.TRANSPARENT : BlendMode3D.OPAQUE;
            m = world.materials().register(argb, metallic, rough, emissive, es, mode, dbl, unlit, map, rep);
            materials.put(key, m);
            return m;
        }

        private Texture3D texture(String src) {
            if (textures.containsKey(src)) return textures.get(src);
            Texture3D t = null;
            byte[] bytes = bytes(src);
            if (bytes != null) {
                try {
                    DecodedBitmap bmp = ImageDecoder.standard().decode(bytes);
                    if (bmp != null && (long) bmp.width() * bmp.height() <= 4096L * 4096L) t = new Texture3D(bmp.width(), bmp.height(), bmp.argb());
                } catch (IOException | RuntimeException ex) {
                    t = null;
                }
            }
            textures.put(src, t);
            return t;
        }
    }

    // ------------------------------------------------------------------ 小道具

    /** {@code data:…;base64,…} か {@code classpath:}・{@code /} から始まるクラスパス資源。それ以外（ネットワーク・ファイル）は読まない。 */
    static byte[] bytes(String src) {
        try {
            if (src.startsWith("data:")) {
                int comma = src.indexOf(',');
                if (comma < 0 || !src.substring(0, comma).endsWith(";base64")) return null;
                return Base64.getMimeDecoder().decode(src.substring(comma + 1));
            }
            if (src.startsWith("classpath:") || src.startsWith("/")) {
                String path = src.startsWith("classpath:") ? src.substring(10) : src;
                try (InputStream in = Scene3DDomSync.class.getResourceAsStream(path.startsWith("/") ? path : "/" + path)) {
                    return in == null ? null : in.readAllBytes();
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    /** 属性の集合の署名（順序に依らない）。 */
    private static int hash(Map<String, String> attrs) {
        int h = attrs.size();
        for (Map.Entry<String, String> en : attrs.entrySet()) {
            h += en.getKey().hashCode() * 31 + (en.getValue() == null ? 0 : en.getValue().hashCode()) * 17;
        }
        return h;
    }

    private static boolean isTrue(String v) {
        return v != null && (v.isEmpty() || v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on"));
    }

    private static float clamp01(float v) { return v < 0f ? 0f : Math.min(v, 1f); }

    private static float num(String v, float def) {
        if (v == null) return def;
        try {
            float f = Float.parseFloat(v.trim());
            return Float.isFinite(f) ? f : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** {@code n} 個の数。個数が合わない・数でないときは {@code def}。 */
    private static float[] vec(String v, int n, float[] def) {
        if (v == null) return def;
        String[] parts = v.trim().split("[\\s,]+");
        if (parts.length != n) return def;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            try {
                out[i] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException e) {
                return def;
            }
            if (!Float.isFinite(out[i])) return def;
        }
        return out;
    }

    private static int color(String v, int def) {
        return v != null && ColorParser.isColor(v) ? ColorParser.parseColor(v) : def;
    }
}
