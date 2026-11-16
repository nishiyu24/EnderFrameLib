package com.nishiyu.enderframelib.core.scene3d;

import java.util.Set;

/**
 * 宣言的 3D シーンのタグ台帳（ENGINE_REARCHITECTURE_PLAN §3.1）。L2 相当の独立パッケージで、
 * {@code dom} / {@code layout} / {@code css} / {@code paint} / GL に依存しない（上位層がこの定数を読む）。
 *
 * <p>{@link #SCENE} は置換要素（固有サイズ 300×150。CSS の {@code canvas} と同じ）。子の {@link #CHILDREN} は
 * 2D の箱を作らず、シーンの記述としてだけ読まれる。
 */
public final class Scene3DTags {
    /** 3D ビューポートの置換要素。 */
    public static final String SCENE = "scene-3d";

    /** 固有の幅（CSS px）。 */
    public static final int DEFAULT_WIDTH = 300;

    /** 固有の高さ（CSS px）。 */
    public static final int DEFAULT_HEIGHT = 150;

    /** 表示リスト項目へ載せる、シーンの識別キー（要素の nodeId）。 */
    public static final String WORLD_ATTR = "data-scene-world";

    /** 表示リスト項目へ載せる、シーンの種類（ビルトイン 3D。ホストの {@code <mc-scene>} と区別する）。 */
    public static final String KIND_ATTR = "data-scene-kind";

    /** 表示リスト項目へ載せる、ドラッグでカメラを回せるか（{@code <scene-3d data-orbit>}）。値は {@code "true"}。 */
    public static final String ORBIT_ATTR = "data-scene-orbit";

    /** {@link #KIND_ATTR} の値。 */
    public static final String KIND_3D = "3d";

    /** シーン内の記述要素（2D の箱を作らない）。 */
    public static final Set<String> CHILDREN = Set.of("camera-3d", "light-3d", "mesh-3d", "group-3d", "ui-panel-3d");

    private Scene3DTags() {}

    public static boolean isScene(String tag) {
        return SCENE.equals(tag);
    }

    public static boolean isSceneChild(String tag) {
        return tag != null && CHILDREN.contains(tag);
    }
}
