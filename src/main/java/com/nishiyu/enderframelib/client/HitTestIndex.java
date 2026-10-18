package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.WebAnimator;
import com.nishiyu.enderframelib.core.model.ClipRect;
import com.nishiyu.enderframelib.core.model.ElementType;
import com.nishiyu.enderframelib.core.model.RenderKeys;
import com.nishiyu.enderframelib.core.model.UIElement;

import java.util.*;

/**
 * レイアウト更新ごとに1回生成されるヒットテスト用インデックス。
 * 対話可能要素の事前抽出、Z順序の保持、親チェーンの事前解決、clipRect判定を行い、
 * 毎フレームの全要素スキャン・HashMapアロケーションを排除する。
 */
public class HitTestIndex {

    public static class Entry {
        public final UIElement element;
        public final ClipRect clipRect;
        public final List<UIElement> transformChain; // ルートから自身までの順序

        public Entry(UIElement element, ClipRect clipRect, List<UIElement> transformChain) {
            this.element = element;
            this.clipRect = clipRect;
            this.transformChain = transformChain;
        }
    }

    private final List<Entry> interactiveEntries; // z順序の逆順（上位優先）
    private final Map<String, UIElement> elementMap;

    public HitTestIndex(List<UIElement> elements) {
        if (elements == null || elements.isEmpty()) {
            this.interactiveEntries = Collections.emptyList();
            this.elementMap = Collections.emptyMap();
            return;
        }

        this.elementMap = new HashMap<>(elements.size());
        for (UIElement el : elements) {
            elementMap.put(el.id(), el);
        }

        List<Entry> entries = new ArrayList<>();
        // elements は描画順（下層から上層）なので、逆順で探索できるように保持
        for (int i = elements.size() - 1; i >= 0; i--) {
            UIElement el = elements.get(i);
            if (isIgnoredElement(el)) continue;
            if (!isInteractiveOrOpaque(el, elementMap)) continue;

            List<UIElement> chain = buildTransformChain(el, elementMap);
            ClipRect clip = el.meta() != null ? el.meta().clipRect() : null;
            entries.add(new Entry(el, clip, chain));
        }
        this.interactiveEntries = entries;
    }

    private static boolean isPointerEventsNone(UIElement el) {
        if (el.events() != null) {
            String pe = el.events().get("pointer-events");
            if ("none".equalsIgnoreCase(pe)) return true;
        }
        return false;
    }

    private static boolean isIgnoredElement(UIElement el) {
        if (el == null) return true;
        if (el.type() == ElementType.NODE) return true;
        if (isPointerEventsNone(el)) return true;

        String id = el.id();
        return id.endsWith("_shadow")
                || id.endsWith("_bbg")
                || id.endsWith("_ph")
                || id.contains("_b_");
    }

    private static boolean isInteractiveOrOpaque(UIElement el, Map<String, UIElement> elementMap) {
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

        UIElement parentAnchor = elementMap.get(el.events() != null ? el.events().get(RenderKeys.MC_PARENT) : null);
        return parentAnchor != null && (parentAnchor.hasHover() || parentAnchor.hasPseudo());
    }

    private static List<UIElement> buildTransformChain(UIElement el, Map<String, UIElement> map) {
        List<UIElement> chain = new ArrayList<>(4);
        UIElement curr = el;
        while (curr != null) {
            chain.add(curr);
            String parentId = curr.events() != null ? curr.events().get(RenderKeys.MC_PARENT) : null;
            curr = parentId != null ? map.get(parentId) : null;
        }
        Collections.reverse(chain);
        return chain;
    }

    public ClientWebManager.UIHitResult hitTest(String sessionId, double pixelX, double pixelY) {
        for (Entry entry : interactiveEntries) {
            UIElement el = entry.element;

            // クリップ判定 (D-9)
            // entry.clipRect は仮想座標での可視矩形。pixelX, pixelY がクリップ範囲外なら判定除外
            if (entry.clipRect != null && !entry.clipRect.contains(pixelX, pixelY)) {
                continue;
            }

            double px = pixelX;
            double py = pixelY;
            for (UIElement ancestor : entry.transformChain) {
                WebAnimator.AnimatedValues anim = WebAnimator.getCurrentValues(sessionId, ancestor);
                double[] p = ClientWebManager.inverseTransform(px, py, ancestor, anim);
                px = p[0];
                py = p[1];
            }

            double localX = px - el.x();
            double localY = py - el.y();

            if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
                return new ClientWebManager.UIHitResult(el, localX, localY);
            }
        }
        return null;
    }

    public ClientWebManager.UIHitResult getLocalCoords(String sessionId, UIElement el, double pixelX, double pixelY) {
        List<UIElement> chain = buildTransformChain(el, elementMap);
        double px = pixelX;
        double py = pixelY;
        for (UIElement ancestor : chain) {
            WebAnimator.AnimatedValues anim = WebAnimator.getCurrentValues(sessionId, ancestor);
            double[] p = ClientWebManager.inverseTransform(px, py, ancestor, anim);
            px = p[0];
            py = p[1];
        }
        return new ClientWebManager.UIHitResult(el, px - el.x(), py - el.y());
    }

    public UIElement getElementById(String id) {
        return elementMap.get(id);
    }

    public Map<String, UIElement> elementMap() {
        return elementMap;
    }
}
