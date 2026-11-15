package com.nishiyu.enderframelib.demo;

import static com.nishiyu.enderframelib.api.ui.UI.onClick;

import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.StandaloneViewer;
import com.nishiyu.enderframelib.core.scene3d.GltfBinaryLoader;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 3D のデモ。{@code <scene-3d>} 2 枚: ①粗さ × 金属度の GGX PBR の球の格子 ②コードで組み立てた glTF（.gltf の埋め込み）の
 * 回転する角錐と、小さな球・床。窓では GL、{@code --png} ではソフトウェアの 3D ラスタライザで描かれる（同じシーンモデル）。
 *
 * <pre>
 * ./gradlew :core:runScene3d                                          窓で表示（回転する）
 * ./gradlew :core:runScene3d --args="--png scene3d.png --after 1500"  窓なし（ソフトウェア描画）で PNG
 * </pre>
 */
public final class Scene3DApp {
    private Scene3DApp() {}

    public static void main(String[] args) throws Exception {
        String[] a = new String[args.length + 3];
        a[0] = "core/demo/resources/scene3d.html";
        a[1] = "1100";
        a[2] = "560";
        System.arraycopy(args, 0, a, 3, args.length);
        StandaloneViewer.launch(a, Scene3DApp::wire);
    }

    static void wire(StandaloneView view) {
        Document doc = view.document();
        Scene3DWorld grid = view.scene3dWorldById("grid");
        Scene3DWorld model = view.scene3dWorldById("model");
        buildGrid(grid);
        int[] pyramid = buildModel(model);

        AtomicBoolean spinning = new AtomicBoolean(true);
        onClick(doc.getElementById("spin"), e -> {
            boolean on = !spinning.get();
            spinning.set(on);
            doc.getElementById("state").setTextContent(on ? "回転中" : "停止中");
        });
        long start = System.nanoTime();
        view.timers().setInterval(() -> {
            if (!spinning.get()) return;
            float t = (System.nanoTime() - start) / 1e9f;
            for (int n : pyramid) model.arena().setRotationEulerRadians(n, 0.25f, t * 0.9f, 0f);
            // 格子はカメラを少し揺らして、鏡面の動きで素材の違いが見えるようにする
            grid.camera().lookAt((float) Math.sin(t * 0.5) * 2.2f, 1.2f, 9f, 0, 0, 0, 0, 1, 0);
        }, 16);
    }

    /** 4 × 5 の球。行 = 金属度（1 → 0）、列 = 粗さ（0.15 → 0.95）。 */
    private static void buildGrid(Scene3DWorld w) {
        w.clearColorArgb(0xFF141b34);
        w.camera().lookAt(0, 1.2f, 9f, 0, 0, 0, 0, 1, 0);
        w.camera().setPerspective((float) Math.toRadians(32), 640f / 420f, 0.1f, 50f);
        w.light().setDirection(-0.4f, -0.6f, -1f);
        w.light().setColor(1f, 0.97f, 0.92f, 1.1f);
        w.light().setAmbient(0.34f, 0.36f, 0.44f);
        MeshHandle sphere = w.geometry().createSphere(0.42f, 32, 48);
        int rows = 4, cols = 5;
        int[] base = {0xFFE8B04A, 0xFFB8C4D0, 0xFFD2503C, 0xFF4B8FE0};
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                float metallic = 1f - r / (float) (rows - 1);
                float rough = 0.15f + 0.8f * c / (cols - 1);
                MaterialDesc m = w.materials().register(base[r], metallic, rough, 0, 0f, null, false, false);
                int n = w.arena().allocateNode();
                w.arena().setPosition(n, (c - (cols - 1) / 2f) * 1.25f, ((rows - 1) / 2f - r) * 1.2f, 0);
                w.arena().setMesh(n, sphere.meshId());
                w.arena().setMaterial(n, m.materialId());
            }
        }
    }

    /** 埋め込み .gltf（角錐）を読み、まわりに小さな球と床を置く。回す対象の節を返す。 */
    private static int[] buildModel(Scene3DWorld w) {
        w.clearColorArgb(0xFF141b34);
        w.camera().lookAt(0, 1.4f, 5.2f, 0, 0.5f, 0, 0, 1, 0);
        w.camera().setPerspective((float) Math.toRadians(38), 360f / 260f, 0.1f, 50f);
        w.light().setDirection(-0.5f, -0.8f, -0.6f);
        w.light().setColor(1f, 0.98f, 0.95f, 1.2f);
        w.light().setAmbient(0.18f, 0.2f, 0.26f);
        GltfBinaryLoader.Result r = GltfBinaryLoader.load(pyramidGltf(), w);
        MeshHandle ball = w.geometry().createSphere(0.16f, 16, 24);
        MaterialDesc gold = w.materials().register(0xFFFFD166, 1f, 0.25f, 0, 0f, null, false, false);
        for (int i = 0; i < 6; i++) {
            double ang = i * Math.PI / 3;
            int n = w.arena().allocateNode();
            w.arena().setPosition(n, (float) Math.cos(ang) * 1.7f, -0.55f, (float) Math.sin(ang) * 1.7f);
            w.arena().setMesh(n, ball.meshId());
            w.arena().setMaterial(n, gold.materialId());
        }
        MeshHandle floor = w.geometry().createPlane(6f, 6f);
        MaterialDesc ground = w.materials().register(0xFF2A3355, 0f, 0.8f, 0, 0f, null, false, false);
        int f = w.arena().allocateNode();
        w.arena().setPosition(f, 0, -0.7f, 0);
        w.arena().setMesh(f, floor.meshId());
        w.arena().setMaterial(f, ground.materialId());
        return r.rootNodes();
    }

    /** 4 面の角錐（面ごとに頂点を持つ・底面なし）を data: URI で埋め込んだ .gltf。面ごとに材質を変える。 */
    private static byte[] pyramidGltf() {
        float h = 1.4f, s = 1.1f;
        float[][] corner = {{-s, -0.7f, -s}, {s, -0.7f, -s}, {s, -0.7f, s}, {-s, -0.7f, s}};
        float[] apex = {0, h - 0.7f, 0};
        ByteBuffer b = ByteBuffer.allocate(4 * 3 * 3 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) {
            float[] p = corner[i], q = corner[(i + 1) % 4];
            // 三角形は頂点の並びが (頂点, 次の角, 今の角)。向きは doubleSided で面倒を見る
            for (float v : apex) b.putFloat(v);
            for (float v : q) b.putFloat(v);
            for (float v : p) b.putFloat(v);
        }
        String uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(b.array());
        String[] colors = {"[0.9,0.25,0.2,1]", "[0.2,0.7,0.9,1]", "[0.95,0.8,0.2,1]", "[0.4,0.85,0.4,1]"};
        StringBuilder prims = new StringBuilder(), mats = new StringBuilder(), views = new StringBuilder(), accs = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                prims.append(',');
                mats.append(',');
                views.append(',');
                accs.append(',');
            }
            views.append("{\"buffer\":0,\"byteOffset\":").append(i * 36).append(",\"byteLength\":36}");
            accs.append("{\"bufferView\":").append(i).append(",\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}");
            prims.append("{\"attributes\":{\"POSITION\":").append(i).append("},\"material\":").append(i).append('}');
            mats.append("{\"pbrMetallicRoughness\":{\"baseColorFactor\":").append(colors[i])
                    .append(",\"metallicFactor\":0.1,\"roughnessFactor\":0.35},\"doubleSided\":true}");
        }
        String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":144,\"uri\":\"" + uri + "\"}],"
                + "\"bufferViews\":[" + views + "],\"accessors\":[" + accs + "],\"materials\":[" + mats + "],"
                + "\"meshes\":[{\"primitives\":[" + prims + "]}],\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
