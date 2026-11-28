package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 不変の {@link DisplayScene} と {@link DynamicSceneState} から要素の位置・寸法・索引を直接答える
 * 状態を持たない静的問い合わせユーティリティ（M3b-3。旧 {@code SceneBox}/{@code PaintBox} を置き換える）。
 */
public final class SceneQuery {
    private SceneQuery() { }

    /** 1 つの子 {@link SceneLayer} の絶対位置（親の動的状態適用済み）を {@code out = {x, y, stickyDy}} へ書く。 */
    static void resolveChildPos(SceneLayer parent, float px, float py, float originX, float originY,
                                float portX, float portY, float portW, float portH,
                                float chunkOx, float chunkOy, SceneLayer child,
                                DynamicSceneState dynamic, float[] out3) {
        resolveChildPos(parent, px, py, originX, originY, portX, portY, portW, portH,
                chunkOx, chunkOy, child, dynamic, null, out3);
    }

    /** 配列確保を避ける当たり判定専用の {@link HitScratch} 出力版。 */
    static void resolveChildPos(SceneLayer parent, float px, float py, float originX, float originY,
                                float portX, float portY, float portW, float portH,
                                float chunkOx, float chunkOy, SceneLayer child,
                                DynamicSceneState dynamic, HitScratch scratch) {
        resolveChildPos(parent, px, py, originX, originY, portX, portY, portW, portH,
                chunkOx, chunkOy, child, dynamic, scratch, null);
    }

    private static void resolveChildPos(SceneLayer parent, float px, float py, float originX, float originY,
                                        float portX, float portY, float portW, float portH,
                                        float chunkOx, float chunkOy, SceneLayer child,
                                        DynamicSceneState dynamic, HitScratch scratch, float[] out3) {
        float cx = originX + chunkOx + child.x;
        float cy = originY + chunkOy + child.y;
        float sdy = 0f;
        if (child.follow != null) {
            float[] d = dynamic.anchorShift(child);
            if (d != null) {
                cx += d[0];
                cy += d[1];
            }
        }
        if (child.sticky != null) {
            float[] stick = dynamic.stickyOffset(child, cx, cy, portX, portY, portW, portH,
                    px + parent.bL + parent.pL, py + parent.bT + parent.pT,
                    px + parent.w - parent.bR - parent.pR, py + parent.h - parent.bB - parent.pB);
            if (stick != null) {
                cx += stick[0];
                cy += stick[1];
                sdy = stick[1];
            }
        }
        if (scratch != null) {
            scratch.resX = cx;
            scratch.resY = cy;
        } else {
            out3[0] = cx;
            out3[1] = cy;
            out3[2] = sdy;
        }
    }

    static float nextOriginX(SceneLayer layer, float lx, float curOriginX, DynamicSceneState dynamic) {
        if (!layer.isOrigin()) return curOriginX;
        return layer.isScrollContainer() ? lx - dynamic.scrollX(layer) : lx;
    }

    static float nextOriginY(SceneLayer layer, float ly, float curOriginY, DynamicSceneState dynamic) {
        if (!layer.isOrigin()) return curOriginY;
        return layer.isScrollContainer() ? ly - dynamic.scrollY(layer) : ly;
    }

    static DynamicSceneState normalize(DisplayScene scene, DynamicSceneState dynamic) {
        if (dynamic != null) return dynamic;
        return DynamicSceneState.of(null, scene != null ? scene.root.w : 0f, scene != null ? scene.root.h : 0f);
    }

    /** {@code target} に対応する最初の非匿名要素を返す（無ければ null）。 */
    public static SceneLocation locate(DisplayScene scene, DynamicSceneState dynamic, HtmlNode target) {
        if (scene == null || target == null) return null;
        DynamicSceneState dyn = normalize(scene, dynamic);
        return findFirst(scene.root, 0f, 0f, 0f, 0f, 0f, 0f, dyn.viewportW, dyn.viewportH, 0f, dyn, target, null, 0, new float[3]);
    }

    /** DOM の {@code id} 属性（または {@code node.key()}）が {@code id} の最初の非匿名要素を返す。 */
    public static SceneLocation locateById(DisplayScene scene, DynamicSceneState dynamic, String id) {
        if (scene == null || id == null || id.isEmpty()) return null;
        DynamicSceneState dyn = normalize(scene, dynamic);
        return findFirst(scene.root, 0f, 0f, 0f, 0f, 0f, 0f, dyn.viewportW, dyn.viewportH, 0f, dyn, null, id, 0, new float[3]);
    }

    /** 文書内の一意な数値 {@code nodeId} で要素を引く（無ければ null）。 */
    public static SceneLocation locateByNodeId(DisplayScene scene, DynamicSceneState dynamic, int nodeId) {
        if (scene == null || nodeId <= 0) return null;
        DynamicSceneState dyn = normalize(scene, dynamic);
        return findFirst(scene.root, 0f, 0f, 0f, 0f, 0f, 0f, dyn.viewportW, dyn.viewportH, 0f, dyn, null, null, nodeId, new float[3]);
    }

    private static SceneLocation findFirst(SceneLayer layer, float lx, float ly, float parentOriginX, float parentOriginY,
                                     float portX, float portY, float portW, float portH, float stickyDy,
                                     DynamicSceneState dynamic, HtmlNode targetNode, String targetId, int targetNodeId, float[] tmp3) {
        if (!layer.anonymous()) {
            if (targetNode != null && layer.node == targetNode && !targetNode.isPseudoNode) {
                return new SceneLocation(layer, lx, ly, parentOriginX, parentOriginY, stickyDy);
            }
            if (targetNodeId > 0 && layer.nodeId == targetNodeId) {
                return new SceneLocation(layer, lx, ly, parentOriginX, parentOriginY, stickyDy);
            }
            if (targetId != null && layer.node != null && !layer.node.isPseudoNode
                    && (targetId.equals(layer.node.getId()) || targetId.equals(layer.node.key()))) {
                return new SceneLocation(layer, lx, ly, parentOriginX, parentOriginY, stickyDy);
            }
        }
        SceneChildren sc = layer.children;
        if (sc.size == 0) return null;
        float cox = nextOriginX(layer, lx, parentOriginX, dynamic);
        float coy = nextOriginY(layer, ly, parentOriginY, dynamic);
        float px = portX, py = portY, pw = portW, ph = portH;
        if (layer.isScrollContainer()) {
            px = lx + layer.bL;
            py = ly + layer.bT;
            pw = Math.max(0f, layer.w - layer.bL - layer.bR);
            ph = Math.max(0f, layer.h - layer.bT - layer.bB);
        }
        for (int k = 0; k < sc.chunks.length; k++) {
            SceneChunk c = sc.chunks[k];
            float cx = sc.chunkX[k], cy = sc.chunkY[k];
            for (SceneLayer child : c.layers) {
                resolveChildPos(layer, lx, ly, cox, coy, px, py, pw, ph, cx, cy, child, dynamic, tmp3);
                SceneLocation hit = findFirst(child, tmp3[0], tmp3[1], cox, coy, px, py, pw, ph, tmp3[2],
                        dynamic, targetNode, targetId, targetNodeId, tmp3);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** 全要素（疑似要素を除く）の {@code HtmlNode -> SceneLocation} 索引を 1 パス（O(N)）で作る。 */
    public static Map<HtmlNode, SceneLocation> indexElements(DisplayScene scene, DynamicSceneState dynamic) {
        Map<HtmlNode, SceneLocation> out = new IdentityHashMap<>();
        if (scene == null) return out;
        DynamicSceneState dyn = normalize(scene, dynamic);
        forEachElement(scene, dyn, loc -> {
            if (loc.layer().node != null && !loc.layer().node.isPseudoNode) {
                out.putIfAbsent(loc.layer().node, loc);
            }
        });
        return out;
    }

    /** シーン内の全 {@link SceneLayer} を解決済み位置つきで走査する。 */
    public static void forEachElement(DisplayScene scene, DynamicSceneState dynamic, Consumer<SceneLocation> visitor) {
        if (scene == null || visitor == null) return;
        DynamicSceneState dyn = normalize(scene, dynamic);
        walk(scene.root, 0f, 0f, 0f, 0f, 0f, 0f, dyn.viewportW, dyn.viewportH, 0f, dyn, visitor, new float[3]);
    }

    private static void walk(SceneLayer layer, float lx, float ly, float parentOriginX, float parentOriginY,
                             float portX, float portY, float portW, float portH, float stickyDy,
                             DynamicSceneState dynamic, Consumer<SceneLocation> visitor, float[] tmp3) {
        visitor.accept(new SceneLocation(layer, lx, ly, parentOriginX, parentOriginY, stickyDy));
        SceneChildren sc = layer.children;
        if (sc.size == 0) return;
        float cox = nextOriginX(layer, lx, parentOriginX, dynamic);
        float coy = nextOriginY(layer, ly, parentOriginY, dynamic);
        float px = portX, py = portY, pw = portW, ph = portH;
        if (layer.isScrollContainer()) {
            px = lx + layer.bL;
            py = ly + layer.bT;
            pw = Math.max(0f, layer.w - layer.bL - layer.bR);
            ph = Math.max(0f, layer.h - layer.bT - layer.bB);
        }
        for (int k = 0; k < sc.chunks.length; k++) {
            SceneChunk c = sc.chunks[k];
            float cx = sc.chunkX[k], cy = sc.chunkY[k];
            for (SceneLayer child : c.layers) {
                resolveChildPos(layer, lx, ly, cox, coy, px, py, pw, ph, cx, cy, child, dynamic, tmp3);
                walk(child, tmp3[0], tmp3[1], cox, coy, px, py, pw, ph, tmp3[2], dynamic, visitor, tmp3);
            }
        }
    }

    /** 1 つの親 {@link SceneLocation} の直接の子一覧を位置解決して返す（テスト・木走査補助用）。 */
    public static List<SceneLocation> children(SceneLocation parent, DynamicSceneState dynamic) {
        if (parent == null || parent.layer().children.size == 0) return List.of();
        DynamicSceneState dyn = dynamic != null ? dynamic : DynamicSceneState.of(null, 0f, 0f);
        SceneLayer layer = parent.layer();
        float cox = nextOriginX(layer, parent.x(), parent.originX(), dyn);
        float coy = nextOriginY(layer, parent.y(), parent.originY(), dyn);
        // 最も近いスクロールポートはこのメソッド単体では持たないため、直近自身がスクロールコンテナならそのポート、そうでなければビューポートを使う
        float px = layer.isScrollContainer() ? parent.x() + layer.bL : 0f;
        float py = layer.isScrollContainer() ? parent.y() + layer.bT : 0f;
        float pw = layer.isScrollContainer() ? Math.max(0f, layer.w - layer.bL - layer.bR) : dyn.viewportW;
        float ph = layer.isScrollContainer() ? Math.max(0f, layer.h - layer.bT - layer.bB) : dyn.viewportH;
        SceneChildren sc = layer.children;
        List<SceneLocation> out = new ArrayList<>(sc.size);
        float[] tmp3 = new float[3];
        for (int k = 0; k < sc.chunks.length; k++) {
            SceneChunk c = sc.chunks[k];
            float cx = sc.chunkX[k], cy = sc.chunkY[k];
            for (SceneLayer child : c.layers) {
                resolveChildPos(layer, parent.x(), parent.y(), cox, coy, px, py, pw, ph, cx, cy, child, dyn, tmp3);
                out.add(new SceneLocation(child, tmp3[0], tmp3[1], cox, coy, tmp3[2]));
            }
        }
        return out;
    }

    /** {@code <fieldset>} 内の描画される {@code <legend>}（無ければ null）。 */
    public static SceneLocation renderedLegend(SceneLocation fieldset, DynamicSceneState dynamic) {
        for (SceneLocation c : children(fieldset, dynamic)) {
            if (fieldset.layer().children.isRenderedLegend(c.layer())) {
                return c.layer().display != null && c.layer().display.isNone() ? null : c;
            }
        }
        return null;
    }
}
