package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.client.renderer.CssAnimator;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderKeys;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * レイアウト更新ごとに1回生成されるヒットテスト用インデックス（STRUCTURE_PLAN 目標配置）。
 * 対話可能要素の事前抽出、Z順序の保持、親チェーンの事前解決、clipRect判定を行い、
 * 毎フレームの全要素スキャン・HashMapアロケーションを排除する。
 */
public class HitTestIndex {

    public static class Entry {
        public final DisplayItem element;
        public final ClipRect clipRect;
        public final List<DisplayItem> transformChain; // ルートから自身までの順序

        public Entry(DisplayItem element, ClipRect clipRect, List<DisplayItem> transformChain) {
            this.element = element;
            this.clipRect = clipRect;
            this.transformChain = transformChain;
        }
    }

    private final List<Entry> interactiveEntries; // z順序の逆順（上位優先）
    private final Map<String, DisplayItem> elementMap;

    public HitTestIndex(List<DisplayItem> elements) {
        if (elements == null || elements.isEmpty()) {
            this.interactiveEntries = Collections.emptyList();
            this.elementMap = Collections.emptyMap();
            return;
        }

        this.elementMap = new HashMap<>(elements.size());
        for (DisplayItem el : elements) {
            elementMap.put(el.id(), el);
        }

        List<Entry> entries = new ArrayList<>();
        // elements は描画順（下層から上層）なので、逆順で探索できるように保持
        for (int i = elements.size() - 1; i >= 0; i--) {
            DisplayItem el = elements.get(i);
            if (isIgnoredElement(el)) continue;
            if (!isInteractiveOrOpaque(el, elementMap)) continue;

            List<DisplayItem> chain = buildTransformChain(el, elementMap);
            ClipRect clip = el.meta() != null ? el.meta().clipRect() : null;
            entries.add(new Entry(el, clip, chain));
        }
        this.interactiveEntries = entries;
    }

    /** 描画側が {@code pointer-events: none} の箱の部品に載せた印（{@link RenderKeys#POINTER_EVENTS}）。 */
    private static boolean isPointerEventsNone(DisplayItem el) {
        return el.events() != null && "none".equalsIgnoreCase(el.events().get(RenderKeys.POINTER_EVENTS));
    }

    /** 装飾だけの部品（影・枠線・輪郭線・背景のレイヤー・プレースホルダ）。クリックを受けず、下の要素を隠さない。 */
    private static final String[] DECORATION_PARTS = {"_shadow", "_ph", "_b_", "_o_", "_bgl"};

    /**
     * 部品の id は持ち主の id + 接尾辞なので、持ち主の id に "_b_" などが含まれても誤判定しないよう接尾辞だけを見る。
     *
     * 何が壊れていたか: 以前は "_shadow" で終わる id だけを除いていたので、番号付きの影（"_shadow_1" など）が
     * ヒットテストに残り、影の上のカーソルが存在しない id でホバーとクリックを起こしていた。
     */
    private static boolean isIgnoredElement(DisplayItem el) {
        if (el == null) return true;
        if (el.type() == ElementType.NODE) return true;
        if (isPointerEventsNone(el)) return true;

        String id = el.id();
        String part = id.startsWith(el.baseId()) ? id.substring(el.baseId().length()) : id;
        for (String prefix : DECORATION_PARTS) {
            if (part.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isInteractiveOrOpaque(DisplayItem el, Map<String, DisplayItem> elementMap) {
        if (isPointerEventsNone(el)) return false;

        ElementType t = el.type();
        boolean isInput = t == ElementType.INPUT || t == ElementType.TEXTAREA || t == ElementType.SELECT
                || t == ElementType.RANGE || t == ElementType.TOGGLE || t == ElementType.CHECKBOX
                || t == ElementType.RADIO || t == ElementType.BUTTON || t == ElementType.A || t == ElementType.SCENE;
        if (isInput) return true;

        // スクロールバー
        if (el.id().contains("_sb_")) return true;

        // テキストノード
        if (t == ElementType.TEXT) return true;

        // イベントや対話属性がある
        if (el.events() != null) {
            for (String key : el.events().keySet()) {
                if (key.startsWith("on") || RenderKeys.MC_TITLE.equals(key) || "draggable".equals(key)) {
                    return true;
                }
            }
        }

        // 置換要素（画像・キャンバス）
        if (t == ElementType.IMAGE || t == ElementType.CANVAS) {
            return true;
        }

        // 不透明な背景色（Alpha > 0）を持つ要素（遮蔽物として下のボタン貫通を防止）
        if ((el.bgColor() & 0xFF000000) != 0) {
            return true;
        }

        DisplayItem parentAnchor = elementMap.get(el.events() != null ? el.events().get(RenderKeys.MC_PARENT) : null);
        return parentAnchor != null && (parentAnchor.hasHover() || parentAnchor.hasPseudo());
    }

    private static List<DisplayItem> buildTransformChain(DisplayItem el, Map<String, DisplayItem> map) {
        List<DisplayItem> chain = new ArrayList<>(4);
        DisplayItem curr = el;
        while (curr != null) {
            chain.add(curr);
            String parentId = curr.events() != null ? curr.events().get(RenderKeys.MC_PARENT) : null;
            curr = parentId != null ? map.get(parentId) : null;
        }
        Collections.reverse(chain);
        return chain;
    }

    public static double[] inverseTransform(double px, double py, DisplayItem el, CssAnimator.AnimatedValues anim) {
        RenderMeta meta = el.meta();
        float originX = meta != null ? meta.originX() : 0.5f;
        float originY = meta != null ? meta.originY() : 0.5f;

        float cx = el.x() + el.width() * originX;
        float cy = el.y() + el.height() * originY;

        double dx = px - cx - anim.tx();
        double dy = py - cy - anim.ty();
        if (anim.rot() != 0.0f) {
            double rad = -Math.toRadians(anim.rot());
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            double nx = dx * cos - dy * sin;
            double ny = dx * sin + dy * cos;
            dx = nx;
            dy = ny;
        }
        if (anim.sx() != 0 && anim.sx() != 1.0f) dx /= anim.sx();
        if (anim.sy() != 0 && anim.sy() != 1.0f) dy /= anim.sy();

        return new double[]{dx + cx, dy + cy};
    }

    public SessionContext.UIHitResult hitTest(String sessionId, double pixelX, double pixelY) {
        for (Entry entry : interactiveEntries) {
            DisplayItem el = entry.element;

            // クリップ判定 (D-9)
            // entry.clipRect は仮想座標での可視矩形。pixelX, pixelY がクリップ範囲外なら判定除外
            if (entry.clipRect != null && !entry.clipRect.contains(pixelX, pixelY)) {
                continue;
            }

            double px = pixelX;
            double py = pixelY;
            for (DisplayItem ancestor : entry.transformChain) {
                CssAnimator.AnimatedValues anim = CssAnimator.getCurrentValues(sessionId, ancestor);
                double[] p = inverseTransform(px, py, ancestor, anim);
                px = p[0];
                py = p[1];
            }

            double localX = px - el.x();
            double localY = py - el.y();

            if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
                return new SessionContext.UIHitResult(el, localX, localY);
            }
        }
        return null;
    }

    public SessionContext.UIHitResult getLocalCoords(String sessionId, DisplayItem el, double pixelX, double pixelY) {
        List<DisplayItem> chain = buildTransformChain(el, elementMap);
        double px = pixelX;
        double py = pixelY;
        for (DisplayItem ancestor : chain) {
            CssAnimator.AnimatedValues anim = CssAnimator.getCurrentValues(sessionId, ancestor);
            double[] p = inverseTransform(px, py, ancestor, anim);
            px = p[0];
            py = p[1];
        }
        return new SessionContext.UIHitResult(el, px - el.x(), py - el.y());
    }

    public DisplayItem getElementById(String id) {
        return elementMap.get(id);
    }

    public Map<String, DisplayItem> elementMap() {
        return elementMap;
    }
}
