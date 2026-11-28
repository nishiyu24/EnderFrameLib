package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.function.Predicate;

/**
 * フレームの値 {@link RenderFrame} の、描かない節の述語（M3b-4c-2b-1）: 属性 {@code attribute} を持つ要素が自分で塗る部品を描かない。
 * 節の錨（子の原点・層）と子の要素は描く。ホストがシーンを組み直さずに一部を省く（MOD の HUD は {@code data-mount-entity} の要素を
 * エンティティの上に描くので HUD に描かない）ための口で、列を絞って渡す代わりに使う（絞った列にはシーンが無く、毎フレーム全面を描いていた）。
 *
 * <p>節の属性はシーンの層の DOM の写し（{@code SceneLayer#values}）で読み、DOM も表示項目の属性の写し（{@code DisplayItem#events}。決まった
 * 数個の属性しか通さない）も読まない。値で比べる（描画器は前のフレームと違えば全面を描く）。シーンの無いフレームでは何も除かない。</p>
 *
 * @param attribute この属性を持つ要素を除く
 */
public record ExcludedNodes(String attribute) {
    public ExcludedNodes {
        java.util.Objects.requireNonNull(attribute, "attribute");
    }

    /** シーン {@code scene} で除く要素の節の番号（無ければ空）。 */
    public IntSet nodesIn(DisplayScene scene) {
        IntSet out = new IntOpenHashSet();
        if (scene == null) return out;
        for (SceneLayer layer : scene.byNodeId().values()) {
            if (layer.values().has(attribute)) out.add(layer.nodeId);
        }
        return out;
    }

    /**
     * シーン {@code scene} で描かない項目の述語（除く要素が無ければ null）。1 つのフレームに 1 度作り、項目ごとに引く。項目の持ち主は
     * 項目の鍵の節の番号（文字列の鍵なら親の節の鍵の番号）。
     */
    public Predicate<DisplayItem> itemsIn(DisplayScene scene) {
        IntSet nodes = nodesIn(scene);
        if (nodes.isEmpty()) return null;
        return it -> it.type() != ElementType.NODE && nodes.contains(ownerOf(it));
    }

    /** 項目 {@code it} の持ち主の節の番号（節の鍵から。文字列の鍵なら親の節の鍵から。分からなければ 0）。 */
    static int ownerOf(DisplayItem it) {
        return com.nishiyu.enderframelib.core.paint.model.HostContent.ownerOf(it);
    }
}
