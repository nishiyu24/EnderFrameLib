package com.nishiyu.enderframelib.demo;

import static com.nishiyu.enderframelib.api.ui.UI.onClick;

import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.StandaloneViewer;
import com.nishiyu.enderframelib.common.three.Geometry;
import com.nishiyu.enderframelib.common.three.Material;
import com.nishiyu.enderframelib.common.three.Mesh;
import com.nishiyu.enderframelib.common.three.Object3D;
import com.nishiyu.enderframelib.common.three.Scene3D;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * インタラクティブな 3D のデモ（{@link Scene3D} の使い方の見本）。{@code <scene-3d>} 2 枚:
 * ①粗さ × 金属度の GGX PBR の球の格子 ②コードで組み立てた glTF（.gltf の埋め込み）の回転する角錐と、小さな球・床。
 * どちらもドラッグで回転・ホイールでズーム、物体にマウスを乗せると光り、クリックで選択できる。
 * 窓では GL、{@code --png} ではソフトウェアの 3D ラスタライザで描かれる（同じシーンモデル）。
 *
 * <pre>
 * ./gradlew :core:runScene3d                                          可変ウィンドウで表示（最大化・マウスリサイズ可）
 * ./gradlew :core:runScene3d --args="false"                           固定ウィンドウで表示
 * ./gradlew :core:runScene3d --args="1280 800 true"                   幅・高さ・可変ウィンドウを指定
 * ./gradlew :core:runScene3d --args="--png scene3d.png --after 1500"  窓なしで PNG
 * </pre>
 */
public final class Scene3DApp {
    private Scene3DApp() {}

    public static void main(String[] args) throws Exception {
        Path html = Path.of("core/demo/resources/scene3d.html");
        List<String> a = new ArrayList<>(List.of(html.toString()));

        // 画面サイズ（幅・高さ）と可変ウィンドウ（true指定: 最大化・マウスリサイズ可。argsでfalse指定された場合は可変しない）
        boolean sized = args.length >= 2 && args[0].matches("[0-9]+") && args[1].matches("[0-9]+");
        boolean hasResizable = sized
                ? (args.length >= 3 && ("true".equalsIgnoreCase(args[2]) || "false".equalsIgnoreCase(args[2])))
                : (args.length >= 1 && ("true".equalsIgnoreCase(args[0]) || "false".equalsIgnoreCase(args[0])));
        boolean resizable = hasResizable ? Boolean.parseBoolean(sized ? args[2] : args[0]) : true;

        int pos = 0;
        if (sized) {
            a.add(args[0]);
            a.add(args[1]);
            pos = 2;
        } else {
            a.addAll(List.of("1100", "600"));
        }

        a.add(String.valueOf(resizable));
        if (hasResizable) {
            pos++;
        }

        for (int i = pos; i < args.length; i++) {
            a.add(args[i]);
        }

        StandaloneViewer.launch(a.toArray(new String[0]), Scene3DApp::wire);
    }

    static void wire(StandaloneView view) {
        Document doc = view.document();
        Scene3D grid = Scene3D.of(view, "grid");
        Scene3D model = Scene3D.of(view, "model");
        buildGrid(grid, doc);
        Object3D pyramid = buildModel(model, doc);

        boolean[] spinning = {true};
        onClick(doc.getElementById("bloom"), e -> {
            boolean on = !model.bloomEnabled();
            model.bloom(on);
            grid.bloom(on);
            doc.getElementById("bloomState").setTextContent(on ? "Bloom ON" : "Bloom OFF");
        });
        onClick(doc.getElementById("spin"), e -> {
            spinning[0] = !spinning[0];
            doc.getElementById("state").setTextContent(spinning[0] ? "回転中" : "停止中");
        });
        // 角度は「経過時間の積算」で進める（絶対時刻から計算すると、停止して再開したときに止まっていた時間ぶん角度が飛ぶ）
        double[] angle = {0};
        model.animate((dt, t) -> {
            if (!spinning[0]) return;
            angle[0] += dt * 0.9;
            pyramid.rotation.set(0.25, angle[0], 0);
        });
    }

    /** 4 × 5 の球。行 = 金属度（1 → 0）、列 = 粗さ（0.15 → 0.95）。乗せると光り、クリックで選ぶ。 */
    private static void buildGrid(Scene3D scene, Document doc) {
        scene.background(0x141B34).orbitControls(true).bloom(true, 1.0, 0.7, 12);
        scene.camera().position(0, 1.2, 9).lookAt(0, 0, 0).fov(32);
        scene.directionalLight(0xFFF7EB, 1.1, -0.4, -0.6, -1);
        scene.ambientLight(0x57 << 16 | 0x5C << 8 | 0x70, 1);

        Geometry sphere = Geometry.sphere(0.42, 32, 48);
        int rows = 4, cols = 5;
        int[] base = {0xE8B04A, 0xB8C4D0, 0xD2503C, 0x4B8FE0};
        Mesh[] selected = {null};
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double metal = 1.0 - r / (double) (rows - 1);
                double rough = 0.15 + 0.8 * c / (cols - 1);
                Mesh ball = new Mesh(sphere, new Material(base[r]).metalness(metal).roughness(rough));
                ball.name = String.format("金属度 %.2f・粗さ %.2f", metal, rough);
                ball.position.set((c - (cols - 1) / 2.0) * 1.25, ((rows - 1) / 2.0 - r) * 1.2, 0);
                ball.onPointerEnter(h -> ball.scale.set(1.12));
                ball.onPointerLeave(h -> ball.scale.set(1));
                ball.onClick(h -> {
                    if (selected[0] != null) selected[0].material().emissive(0, 0);
                    selected[0] = ball;
                    ball.material().emissive(0x60A0FF, 0.6);
                    doc.getElementById("gridPicked").setTextContent(ball.name);
                });
                scene.add(ball);
            }
        }
    }

    /** 埋め込み .gltf（角錐）を読み、まわりに小さな球と床を置く。回す対象を返す。 */
    private static Object3D buildModel(Scene3D scene, Document doc) {
        scene.background(0x141B34).orbitControls(true).bloom(true, 0.9, 0.9, 14);
        scene.camera().position(0, 1.4, 5.2).lookAt(0, 0.5, 0).fov(38);
        scene.directionalLight(0xFFFAF2, 1.2, -0.5, -0.8, -0.6);
        scene.ambientLight(0x2E3342, 1);

        Object3D pyramid = scene.loadGltf(pyramidGltf());
        pyramid.name = "角錐";
        pyramid.onClick(h -> doc.getElementById("picked").setTextContent(pyramid.name));

        // 小さな球は発光するランプ（強さ 3 = HDR で 1 を超えるので、Bloom で光って見える）
        Geometry ball = Geometry.sphere(0.16, 16, 24);
        Material lampOff = new Material(0x302010).roughness(0.5).emissive(0xFFC060, 3);
        for (int i = 0; i < 6; i++) {
            double ang = i * Math.PI / 3;
            Mesh lamp = new Mesh(ball, lampOff);
            lamp.name = "ランプ " + (i + 1);
            lamp.position.set(Math.cos(ang) * 1.7, -0.55, Math.sin(ang) * 1.7);
            lamp.onClick(h -> doc.getElementById("picked").setTextContent(lamp.name));
            scene.add(lamp);
        }
        Mesh floor = new Mesh(Geometry.plane(6, 6), new Material(0x2A3355).roughness(0.8));
        floor.name = "床";
        floor.position.set(0, -0.7, 0);
        scene.add(floor);
        return pyramid;
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
