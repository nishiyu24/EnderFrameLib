package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.binding.ClientPubSubManager;
import com.nishiyu.enderframelib.client.renderer.WebAnimator;
import com.nishiyu.enderframelib.client.renderer.WebCanvasContext;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.common.UIParser;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.core.model.UIElement.ClipRect;
import com.nishiyu.enderframelib.core.model.UIElement.ElementType;
import com.nishiyu.enderframelib.core.model.UIElement.RenderKeys;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1つのWebセッション（GUI画面、コンテナ画面、ブロック面、HUD、アイテム等）に関する
 * 全クライアント状態（DOMツリー、スタイル、レンダー要素キャッシュ、環境変数、スクロール、フォーカス等）を
 * 1オブジェクトに集約管理するコンテキスト。
 */
public class SessionContext implements AutoCloseable {

    /**
     * 画面更新の無効化段階（F3-2）。
     * <ul>
     *   <li>{@link #NONE}: 再計算不要</li>
     *   <li>{@link #REPAINT}: 既存の {@link LayoutBox} から描画要素リストのみ再生成（measure/layout を省略）</li>
     *   <li>{@link #RESTYLE}: 状態変化した部分木のみ再スタイルし、レイアウト影響があれば {@link #RELAYOUT}、無ければ {@link #REPAINT} に分岐</li>
     *   <li>{@link #RELAYOUT}: スタイル解決・measure・layout・描画要素生成の全パスを実行</li>
     * </ul>
     */
    public enum InvalidationLevel {
        NONE,
        REPAINT,
        RESTYLE,
        RELAYOUT
    }

    private final String sessionId;
    private volatile List<UIElement> renderCache = Collections.emptyList();
    private UIParser.Document document;
    private LayoutBox layoutRoot;
    private HtmlNode virtualDom;
    private HtmlNode templateDom;
    private boolean serverManaged = false;
    private boolean requested = false;
    private long lastSyncRequestTime = 0L;
    private int domHash = 0;
    private int rootW = 854;
    private int rootH = 480;
    private final Map<String, String> env = new ConcurrentHashMap<>();
    private final ScrollState scrollState = new ScrollState();
    private final InteractionState interactionState = new InteractionState();
    private volatile InvalidationLevel invalidationLevel = InvalidationLevel.NONE;

    private String activeInputId = "";
    private String activePressedId = "";
    private String focusedElementBaseId = "";
    private final com.nishiyu.enderframelib.core.model.TextSelection textSelection = new com.nishiyu.enderframelib.core.model.TextSelection();
    private volatile HitTestIndex hitTestIndex;

    public SessionContext(String sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    }

    public String sessionId() {
        return sessionId;
    }

    public List<UIElement> renderCache() {
        return renderCache;
    }

    public void setRenderCache(List<UIElement> renderCache) {
        this.renderCache = (renderCache != null && !renderCache.isEmpty())
                ? List.copyOf(renderCache)
                : Collections.emptyList();
        this.hitTestIndex = new HitTestIndex(this.renderCache);
    }

    public HitTestIndex hitTestIndex() {
        HitTestIndex idx = this.hitTestIndex;
        if (idx == null) {
            idx = new HitTestIndex(this.renderCache);
            this.hitTestIndex = idx;
        }
        return idx;
    }

    public UIParser.Document document() {
        return document;
    }

    public void setDocument(UIParser.Document document) {
        this.document = document;
    }

    public LayoutBox layoutRoot() {
        return layoutRoot;
    }

    public void setLayoutRoot(LayoutBox layoutRoot) {
        this.layoutRoot = layoutRoot;
    }

    public InteractionState interactionState() {
        return interactionState;
    }

    public InvalidationLevel invalidationLevel() {
        return invalidationLevel;
    }

    public synchronized void markInvalidation(InvalidationLevel level) {
        if (level != null && level.ordinal() > this.invalidationLevel.ordinal()) {
            this.invalidationLevel = level;
        }
    }

    public synchronized InvalidationLevel consumeInvalidationLevel() {
        InvalidationLevel current = this.invalidationLevel;
        this.invalidationLevel = InvalidationLevel.NONE;
        return current;
    }

    public HtmlNode virtualDom() {
        return virtualDom;
    }

    public void setVirtualDom(HtmlNode virtualDom) {
        this.virtualDom = virtualDom;
    }

    public HtmlNode templateDom() {
        return templateDom;
    }

    public void setTemplateDom(HtmlNode templateDom) {
        this.templateDom = templateDom;
    }

    public boolean isServerManaged() {
        return serverManaged;
    }

    public void setServerManaged(boolean serverManaged) {
        this.serverManaged = serverManaged;
    }

    public boolean isRequested() {
        return requested;
    }

    public void setRequested(boolean requested) {
        this.requested = requested;
    }

    public long lastSyncRequestTime() {
        return lastSyncRequestTime;
    }

    public void setLastSyncRequestTime(long lastSyncRequestTime) {
        this.lastSyncRequestTime = lastSyncRequestTime;
    }

    public int domHash() {
        return domHash;
    }

    public void setDomHash(int domHash) {
        this.domHash = domHash;
    }

    public int rootW() {
        return rootW;
    }

    public void setRootW(int rootW) {
        this.rootW = rootW;
    }

    public int rootH() {
        return rootH;
    }

    public void setRootH(int rootH) {
        this.rootH = rootH;
    }

    public Map<String, String> env() {
        return env;
    }

    public void setEnv(Map<String, String> newEnv) {
        this.env.clear();
        if (newEnv != null) {
            this.env.putAll(newEnv);
        }
    }

    public ScrollState scrollState() {
        return scrollState;
    }

    public String activeInputId() {
        return activeInputId;
    }

    public void setActiveInputId(String activeInputId) {
        this.activeInputId = activeInputId != null ? activeInputId : "";
    }

    public String activePressedId() {
        return activePressedId;
    }

    public void setActivePressedId(String activePressedId) {
        this.activePressedId = activePressedId != null ? activePressedId : "";
    }

    public String focusedElementBaseId() {
        return focusedElementBaseId;
    }

    public void setFocusedElementBaseId(String focusedElementBaseId) {
        this.focusedElementBaseId = focusedElementBaseId != null ? focusedElementBaseId : "";
    }

    public com.nishiyu.enderframelib.core.model.TextSelection textSelection() {
        return textSelection;
    }

    @Override
    public void close() {
        renderCache = Collections.emptyList();
        hitTestIndex = null;
        document = null;
        layoutRoot = null;
        virtualDom = null;
        templateDom = null;
        env.clear();
        interactionState.clear();
        invalidationLevel = InvalidationLevel.NONE;
        activeInputId = "";
        activePressedId = "";
        focusedElementBaseId = "";
        textSelection.clear();

        ClientPubSubManager.clearSession(sessionId);
        WebCanvasContext.clearSession(sessionId);
        WebSceneContext.clearSession(sessionId);
        ClientLocalEnvProvider.clearSession(sessionId);
        WebAnimator.clearSession(sessionId);
    }

    /**
     * レイアウト更新ごとに1回生成されるヒットテスト用インデックス。
     * 対話可能要素の事前抽出、Z順序の保持、親チェーンの事前解決、clipRect判定を行い、
     * 毎フレームの全要素スキャン・HashMapアロケーションを排除する。
     */
    public static class HitTestIndex {

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
}
