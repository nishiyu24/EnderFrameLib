package com.nishiyu.enderframelib.core.paint.scene;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.Objects;

/**
 * 不変の階層的描画シーン（Engine v3 M3b-2。計画書 §3.4）。
 * {@link SceneEmitter} が {@code (LayoutFragment, 最新の BoxNode)} から直接構築する。
 *
 * <p>{@link #byNodeId()} は動的オフセット・当たり判定・スクロール用の O(1) 索引で、最初に参照されたときに遅延構築する。</p>
 */
public final class DisplayScene {
    public final SceneLayer root;
    private Int2ObjectOpenHashMap<SceneLayer> byNodeIdIndex;
    /** {@link #hasBackdropFilter} の答え（0 = まだ数えていない、1 = 無い、2 = ある）。 */
    private byte backdrop;

    public DisplayScene(SceneLayer root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** {@code nodeId -> SceneLayer} の読み取り専用索引（DOM 要素のみ。生成節・疑似要素は除く）。 */
    public Int2ObjectMap<SceneLayer> byNodeId() {
        if (byNodeIdIndex == null) {
            Int2ObjectOpenHashMap<SceneLayer> built = new Int2ObjectOpenHashMap<>();
            index(root, built);
            byNodeIdIndex = built;
        }
        return Int2ObjectMaps.unmodifiable(byNodeIdIndex);
    }

    public SceneLayer layerByNodeId(int nodeId) {
        return nodeId > 0 ? byNodeId().get(nodeId) : null;
    }

    public boolean hasAnchoredDescendants() {
        return root.anchoredBelow();
    }

    /**
     * {@code backdrop-filter} を持つ層があるか（最初に聞かれたときに 1 度だけ数える）。後ろの絵を読む層は、後ろのどこが変わっても
     * 描き直す範囲が層の外へ広がるので、部分の描き直し（{@code SceneDamage}）は全面にする。
     */
    public boolean hasBackdropFilter() {
        if (backdrop == 0) backdrop = anyBackdrop(root) ? (byte) 2 : (byte) 1;
        return backdrop == 2;
    }

    private static boolean anyBackdrop(SceneLayer layer) {
        if (layer.effects != null && layer.effects.hasBackdrop()) return true;
        for (SceneChunk c : layer.children.chunks) {
            for (SceneLayer child : c.layers) if (anyBackdrop(child)) return true;
        }
        return false;
    }

    private static void index(SceneLayer layer, Int2ObjectOpenHashMap<SceneLayer> out) {
        if (layer.nodeId > 0) out.putIfAbsent(layer.nodeId, layer);
        for (SceneChunk c : layer.children.chunks) {
            for (SceneLayer child : c.layers) index(child, out);
        }
    }
}
