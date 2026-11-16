package com.nishiyu.enderframelib.common.three;

import com.nishiyu.enderframelib.core.scene3d.Scene3DArena;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * シーンに置けるものの基底（Three.js の {@code Object3D}）。そのまま使うと子をまとめる箱（{@code Group}）になる。
 * 位置・回転（ラジアン）・拡大率は親からの相対。
 */
public class Object3D {
    public final Vec3 position = new Vec3(0, 0, 0, this::pushTransform);
    /** オイラー角（ラジアン）。 */
    public final Vec3 rotation = new Vec3(0, 0, 0, this::pushTransform);
    public final Vec3 scale = new Vec3(1, 1, 1, this::pushTransform);

    public String name = "";
    /** 利用側が自由に持たせる値。 */
    public Object userData;

    private boolean visible = true;
    private Object3D parent;
    private final List<Object3D> children = new ArrayList<>();
    Scene3D scene;
    int node = Scene3DArena.NULL_NODE;
    private Consumer<Scene3D.Hit> onClick, onPointerEnter, onPointerLeave;

    public Object3D add(Object3D child) {
        if (child.parent != null || child == this) throw new IllegalArgumentException("already has a parent");
        child.parent = this;
        children.add(child);
        if (scene != null) child.attach(scene, node);
        return this;
    }

    public Object3D remove(Object3D child) {
        if (children.remove(child)) {
            child.parent = null;
            child.detach();
        }
        return this;
    }

    public Object3D parent() { return parent; }

    public List<Object3D> children() { return Collections.unmodifiableList(children); }

    public boolean visible() { return visible; }

    public Object3D visible(boolean v) {
        this.visible = v;
        if (scene != null) scene.world().arena().setVisible(node, v);
        return this;
    }

    /** クリック（ドラッグでカメラを回した後は除く）。当たった点を受け取る。 */
    public Object3D onClick(Consumer<Scene3D.Hit> l) { this.onClick = l; return this; }

    public Object3D onPointerEnter(Consumer<Scene3D.Hit> l) { this.onPointerEnter = l; return this; }

    public Object3D onPointerLeave(Consumer<Scene3D.Hit> l) { this.onPointerLeave = l; return this; }

    void fireClick(Scene3D.Hit h) { if (onClick != null) onClick.accept(h); }
    void fireEnter(Scene3D.Hit h) { if (onPointerEnter != null) onPointerEnter.accept(h); }
    void fireLeave(Scene3D.Hit h) { if (onPointerLeave != null) onPointerLeave.accept(h); }

    /** 自分用のノードの中身（メッシュと材質）を書く。{@link Mesh} が上書きする。 */
    void bind(Scene3D s, int n) {}

    final void attach(Scene3D s, int parentNode) {
        scene = s;
        Scene3DArena a = s.world().arena();
        node = parentNode == Scene3DArena.NULL_NODE ? a.allocateNode() : a.allocateNode(parentNode);
        s.register(node, this);
        bind(s, node);
        pushTransform();
        a.setVisible(node, visible);
        for (Object3D c : children) c.attach(s, node);
    }

    final void detach() {
        if (scene == null) return;
        Scene3D s = scene;
        int n = node;
        forgetNodes(s);
        s.world().arena().freeNode(n); // 子孫ごと外れる
    }

    private void forgetNodes(Scene3D s) {
        for (Object3D c : children) c.forgetNodes(s);
        s.unregister(node);
        scene = null;
        node = Scene3DArena.NULL_NODE;
    }

    private void pushTransform() {
        if (scene == null) return;
        Scene3DArena a = scene.world().arena();
        a.setPosition(node, position.fx(), position.fy(), position.fz());
        a.setRotationEulerRadians(node, rotation.fx(), rotation.fy(), rotation.fz());
        a.setScale(node, scale.fx(), scale.fy(), scale.fz());
    }
}
