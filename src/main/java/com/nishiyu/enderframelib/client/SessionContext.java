package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.binding.ClientPubSubManager;
import com.nishiyu.enderframelib.client.renderer.WebAnimator;
import com.nishiyu.enderframelib.client.renderer.WebCanvasContext;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.common.UIParser;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.layout.ScrollState;
import com.nishiyu.enderframelib.core.model.UIElement;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1つのWebセッション（GUI画面、コンテナ画面、ブロック面、HUD、アイテム等）に関する
 * 全クライアント状態（DOMツリー、スタイル、レンダー要素キャッシュ、環境変数、スクロール、フォーカス等）を
 * 1オブジェクトに集約管理するコンテキスト。
 */
public class SessionContext implements AutoCloseable {

    private final String sessionId;
    private List<UIElement> renderCache = new ArrayList<>();
    private UIParser.Document document;
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

    private String activeInputId = "";
    private String activePressedId = "";

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
        this.renderCache = renderCache != null ? renderCache : new ArrayList<>();
    }

    public UIParser.Document document() {
        return document;
    }

    public void setDocument(UIParser.Document document) {
        this.document = document;
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

    @Override
    public void close() {
        renderCache.clear();
        document = null;
        virtualDom = null;
        templateDom = null;
        env.clear();
        activeInputId = "";
        activePressedId = "";

        ClientPubSubManager.clearSession(sessionId);
        WebCanvasContext.clearSession(sessionId);
        WebSceneContext.clearSession(sessionId);
        ClientLocalEnvProvider.clearSession(sessionId);
        WebAnimator.clearSession(sessionId);
    }
}
