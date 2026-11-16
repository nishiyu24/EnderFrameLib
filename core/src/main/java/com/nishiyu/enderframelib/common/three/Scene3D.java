package com.nishiyu.enderframelib.common.three;

import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.scene3d.CameraSpec;
import com.nishiyu.enderframelib.core.scene3d.GltfBinaryLoader;
import com.nishiyu.enderframelib.core.scene3d.Scene3DArena;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.util.HashMap;
import java.util.Map;

/**
 * HTML の {@code <scene-3d id="…">} 1 つを、Three.js のように組み立てて動かす入口。
 *
 * <pre>
 * Scene3D scene = Scene3D.of(view, "model");
 * scene.background(0x141B34).orbitControls(true);
 * scene.camera().position(0, 1.4, 5).lookAt(0, 0.5, 0).fov(38);
 * scene.directionalLight(0xFFF9F2, 1.2, -0.5, -0.8, -0.6);
 *
 * Mesh ball = new Mesh(Geometry.sphere(0.5), new Material(0x4B8FE0).metalness(0.8).roughness(0.3));
 * ball.onClick(hit -&gt; ball.material().emissive(0xFFFFFF, 0.5));
 * scene.add(ball);
 *
 * scene.animate((dt, t) -&gt; ball.rotation.y(t));   // 毎フレーム（dt・t は秒）
 * </pre>
 *
 * 窓では GL / Vulkan、窓なし（{@code --png}）ではソフトウェアで、同じシーンが描かれる。
 */
public final class Scene3D {
    /** 当たった物体と、その点（ワールド座標）・レイの始点からの距離。 */
    public record Hit(Object3D object, double x, double y, double z, double distance) {}

    /** 毎フレームの更新。{@code dt} は前回からの秒（最大 0.05）、{@code t} は開始からの秒。 */
    @FunctionalInterface
    public interface FrameListener {
        void onFrame(double dt, double t);
    }

    private static final double CLICK_SLOP_PX = 4;

    private final StandaloneView view;
    private final Scene3DWorld world;
    private final Element element;
    private final Camera camera;
    private final Map<Integer, Object3D> byNode = new HashMap<>();
    private Object3D hovered;
    private double downX = Double.NaN, downY = Double.NaN;
    private int hoverTimer = -1;
    private double lastX = Double.NaN, lastY = Double.NaN;
    private long lastChange = -1;

    private Scene3D(StandaloneView view, String elementId) {
        this.view = view;
        this.element = view.document().getElementById(elementId);
        this.world = view.scene3dWorldById(elementId);
        if (element == null || world == null) throw new IllegalArgumentException("no <scene-3d> with id: " + elementId);
        this.camera = new Camera(world.camera());
        wireInput();
    }

    /** {@code id} の {@code <scene-3d>} をシーンとして扱う。文書に無ければ {@link IllegalArgumentException}。 */
    public static Scene3D of(StandaloneView view, String elementId) {
        return new Scene3D(view, elementId);
    }

    // ---------------------------------------------------------------- 物体

    /** シーンの根へ置く。 */
    public Scene3D add(Object3D... objects) {
        for (Object3D o : objects) {
            if (o.scene != null) throw new IllegalArgumentException("already in a scene");
            o.attach(this, Scene3DArena.NULL_NODE);
        }
        return this;
    }

    /** 外す（子孫ごと）。 */
    public Scene3D remove(Object3D object) {
        if (object.scene == this) {
            if (object.parent() != null) object.parent().remove(object);
            else object.detach();
        }
        return this;
    }

    /**
     * glTF（{@code .glb}、または {@code data:} URI で埋め込んだ {@code .gltf}）を読んで、1 つの {@link Object3D} にまとめて置く。
     * 壊れた入力は {@link IllegalArgumentException}（シーンは変わらない）。
     */
    public Object3D loadGltf(byte[] data) {
        GltfBinaryLoader.Result r;
        try {
            r = GltfBinaryLoader.load(data, world);
        } catch (GltfBinaryLoader.GltfException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        Object3D group = new Object3D();
        add(group);
        for (int root : r.rootNodes()) world.arena().setParent(root, group.node);
        return group;
    }

    // ---------------------------------------------------------------- カメラ・ライト・見た目

    public Camera camera() { return camera; }

    public Scene3D background(int rgb) {
        world.clearColorArgb(0xFF000000 | rgb);
        return this;
    }

    /** 光が進む向き {@code (dx, dy, dz)} の主ライト。 */
    public Scene3D directionalLight(int rgb, double intensity, double dx, double dy, double dz) {
        world.light().setDirection((float) dx, (float) dy, (float) dz);
        world.light().setColor(r(rgb), g(rgb), b(rgb), (float) intensity);
        return this;
    }

    public Scene3D ambientLight(int rgb, double intensity) {
        world.light().setAmbient(r(rgb) * (float) intensity, g(rgb) * (float) intensity, b(rgb) * (float) intensity);
        return this;
    }

    /** 点光源を足す（最大 8 灯。満杯なら {@code false}）。 */
    public boolean pointLight(int rgb, double intensity, double range, double x, double y, double z) {
        return world.light().addPoint((float) x, (float) y, (float) z, r(rgb), g(rgb), b(rgb), (float) intensity, (float) range);
    }

    /** HDR の光のにじみ。{@code threshold} を超えた明るさが {@code sigmaPx} 画素ぶん滲む。 */
    public Scene3D bloom(boolean enabled, double threshold, double intensity, double sigmaPx) {
        world.bloom(enabled, (float) threshold, (float) intensity, (float) sigmaPx);
        return this;
    }

    public Scene3D bloom(boolean enabled) {
        world.bloom(enabled);
        return this;
    }

    public boolean bloomEnabled() { return world.bloomEnabled(); }

    /** ドラッグでカメラを回し、ホイールでズームする（既定は無効）。 */
    public Scene3D orbitControls(boolean enabled) {
        world.orbitControls(enabled);
        return this;
    }

    private static float r(int rgb) { return ((rgb >> 16) & 0xFF) / 255f; }
    private static float g(int rgb) { return ((rgb >> 8) & 0xFF) / 255f; }
    private static float b(int rgb) { return (rgb & 0xFF) / 255f; }

    // ---------------------------------------------------------------- ループ

    /** 毎フレーム呼ばれる更新（{@code requestAnimationFrame} 相当。窓が止まっていた間は進めない）。 */
    public Scene3D animate(FrameListener l) {
        long[] last = {System.nanoTime()};
        double[] t = {0};
        view.timers().setInterval(() -> {
            long now = System.nanoTime();
            double dt = Math.min((now - last[0]) / 1e9, 0.05);
            last[0] = now;
            t[0] += dt;
            l.onFrame(dt, t[0]);
        }, 16);
        return this;
    }

    // ---------------------------------------------------------------- 当たり判定

    /** 面の座標（文書の左上が原点の CSS px）にある物体。2D の要素が上に重なっていれば当たらない。当たりが無ければ null。 */
    public Hit pick(double x, double y) {
        UiView.Scene3DPick p = view.pickScene3d(x, y);
        if (p == null || view.scene3dWorld(p.worldId()) != world) return null;
        Object3D o = objectOf(p.hit().node);
        return o == null ? null : new Hit(o, p.hit().x, p.hit().y, p.hit().z, p.hit().t);
    }

    private void wireInput() {
        // イベントの clientX/Y は要素基準なので、面の座標はビューの現在のポインタ位置から取る
        element.addEventListener("mousedown", e -> {
            downX = view.pointerX();
            downY = view.pointerY();
        });
        element.addEventListener("click", e -> {
            // ドラッグでカメラを回した後の離しはクリックにしない
            if (Math.abs(view.pointerX() - downX) > CLICK_SLOP_PX || Math.abs(view.pointerY() - downY) > CLICK_SLOP_PX) return;
            Hit h = pick(view.pointerX(), view.pointerY());
            for (Object3D o = h == null ? null : h.object(); o != null; o = o.parent()) o.fireClick(h);
        });
        // mousemove は一定間隔に間引かれ、最後の動きが落ちる（物体から外れて止まると光ったままになる）。要素の上にいる間だけ、
        // ポインタ位置の変化を毎フレーム見る（アニメーションで物体が動いてポインタの下に来た場合にも追従する）
        element.addEventListener("mouseenter", e -> {
            if (hoverTimer < 0) hoverTimer = view.timers().setInterval(this::updateHover, 16);
        });
        element.addEventListener("mousemove", e -> updateHover());
        element.addEventListener("mouseleave", e -> {
            if (hoverTimer >= 0) view.timers().clearInterval(hoverTimer);
            hoverTimer = -1;
            setHovered(null, null);
        });
    }

    /** ポインタの下の物体を見直し、変わっていれば enter / leave を送る。 */
    void updateHover() {
        double x = view.pointerX(), y = view.pointerY();
        if (x == lastX && y == lastY && lastChange == world.changeCount()) return;
        lastX = x;
        lastY = y;
        lastChange = world.changeCount();
        Hit h = pick(x, y);
        setHovered(h == null ? null : h.object(), h);
    }

    private void setHovered(Object3D now, Hit h) {
        if (now == hovered) return;
        Object3D old = hovered;
        hovered = now;
        if (old != null) old.fireLeave(new Hit(old, 0, 0, 0, 0));
        if (now != null) now.fireEnter(h);
    }

    private Object3D objectOf(int node) {
        // glTF の内側のノードのように登録されていない節は、登録済みの祖先（読み込んだ Object3D）として扱う
        for (int n = node; n != Scene3DArena.NULL_NODE; n = world.arena().parentOf(n)) {
            Object3D o = byNode.get(n);
            if (o != null) return o;
        }
        return null;
    }

    // ---------------------------------------------------------------- 内部

    Scene3DWorld world() { return world; }

    void register(int node, Object3D o) { byNode.put(node, o); }

    void unregister(int node) { byNode.remove(node); }

    /** 透視投影カメラ。位置と注視点（ワールド座標）・画角（度）を、メソッドチェーンで指定する。 */
    public static final class Camera {
        private final CameraSpec spec;

        private Camera(CameraSpec spec) {
            this.spec = spec;
        }

        public Camera position(double x, double y, double z) {
            spec.lookAt((float) x, (float) y, (float) z, spec.targetX(), spec.targetY(), spec.targetZ(), 0, 1, 0);
            return this;
        }

        public Camera lookAt(double x, double y, double z) {
            spec.lookAt(spec.eyeX(), spec.eyeY(), spec.eyeZ(), (float) x, (float) y, (float) z, 0, 1, 0);
            return this;
        }

        /** 縦方向の画角（度）。 */
        public Camera fov(double degrees) {
            spec.setPerspective((float) Math.toRadians(degrees), spec.aspect(), spec.nearZ(), spec.farZ());
            return this;
        }

        public Camera clip(double near, double far) {
            spec.setPerspective(spec.fovYRadians(), spec.aspect(), (float) near, (float) far);
            return this;
        }

        /** 現在の位置（オービットで動いた後の値も返る）。 */
        public double[] position() { return new double[] {spec.eyeX(), spec.eyeY(), spec.eyeZ()}; }

        public double[] target() { return new double[] {spec.targetX(), spec.targetY(), spec.targetZ()}; }
    }
}
