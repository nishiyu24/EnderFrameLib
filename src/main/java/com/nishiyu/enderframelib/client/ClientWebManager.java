package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * セッションの登録簿。取得・作成・破棄・描画キューだけを持ち、
 * セッションに対する操作は {@link SessionContext} のインスタンスメソッドに置く（S3）。
 */
public class ClientWebManager {

    private static Consumer<String> applyOverridesCallback = null;

    private static final Map<String, SessionContext> SESSIONS = new ConcurrentHashMap<>();
    private static final Set<String> DIRTY_SESSIONS = ConcurrentHashMap.newKeySet();

    public static SessionContext getSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        return SESSIONS.get(sessionId);
    }

    /** 現在生きている全セッションのスナップショット。描画キューや HUD の横断処理だけが使う。 */
    public static Collection<SessionContext> sessions() {
        return List.copyOf(SESSIONS.values());
    }

    public static SessionContext getOrCreateSession(String sessionId) {
        String safeId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        return SESSIONS.computeIfAbsent(safeId, SessionContext::new);
    }

    /**
     * ローカル HTML・サーバー同期のどちらでも使う画面の入口。既存のサーバー管理セッションがあれば
     * サイズだけ合わせて再利用し、そうでなければ HTML を読み込むかサーバー同期を待つ。
     */
    public static boolean initOrBindSession(String sessionId, String rawHtml, ResourceLocation htmlResource, int width, int height, boolean forceReload) {
        int targetW = width > 0 ? width : 854;
        int targetH = height > 0 ? height : 480;

        SessionContext ctx = getSession(sessionId);
        if (ctx != null) ctx.markExplicitSize();
        if (!forceReload && ctx != null && ctx.isServerManaged() && ctx.document() != null) {
            if (ctx.rootW() != targetW || ctx.rootH() != targetH) {
                ctx.recomputeLayout(targetW, targetH);
            } else {
                ctx.requestRender();
            }
            return false;
        }

        String resolvedHtml = rawHtml;
        if ((resolvedHtml == null || resolvedHtml.isEmpty()) && htmlResource != null) {
            resolvedHtml = UiDocumentCompiler.resourceTextResolver != null
                    ? UiDocumentCompiler.resourceTextResolver.apply(htmlResource.toString())
                    : "";
        }

        if (resolvedHtml != null && !resolvedHtml.isEmpty()) {
            getOrCreateSession(sessionId).loadLocalHtml(resolvedHtml, targetW, targetH);
            return true;
        }

        SessionContext session = getOrCreateSession(sessionId);
        session.markExplicitSize();
        session.setServerManaged(true);
        ClientScreenInteractionManager.ensureSynced(sessionId);
        if (session.document() != null && (session.rootW() != targetW || session.rootH() != targetH)) {
            session.recomputeLayout(targetW, targetH);
        }
        return false;
    }

    public static void recomputeAllLayouts() {
        // 画像の読み込みなど箱の外の資源が変わったので、前回の measure の結果は使わずに測り直す
        com.nishiyu.enderframelib.core.layout.LayoutBox.invalidateAllMeasurements();
        for (SessionContext ctx : SESSIONS.values()) {
            if (ctx.document() != null) {
                ctx.recomputeLayout(ctx.rootW(), ctx.rootH());
            }
        }
    }

    static void markDirty(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        DIRTY_SESSIONS.add(sessionId);
    }

    static boolean hasPendingRenders() {
        return !DIRTY_SESSIONS.isEmpty();
    }

    static void applyOverrides(String sessionId) {
        if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);
    }

    static void setApplyOverridesCallback(Consumer<String> callback) {
        applyOverridesCallback = callback;
    }

    public static void processRenderQueue() {
        ClientScheduler.onRenderFrame(System.currentTimeMillis());
        if (DIRTY_SESSIONS.isEmpty()) return;
        for (String sessionId : new ArrayList<>(DIRTY_SESSIONS)) {
            DIRTY_SESSIONS.remove(sessionId);
            applyOverrides(sessionId);
            SessionContext session = SESSIONS.get(sessionId);
            if (session == null) continue;
            session.flushInvalidation();
        }
    }

    /** セッションの登録を外し、そのセッションが持つ全状態を片付ける。 */
    public static void clearSession(String sessionId) {
        if (sessionId == null) return;
        DIRTY_SESSIONS.remove(sessionId);
        SessionContext ctx = SESSIONS.remove(sessionId);
        if (ctx != null) {
            ctx.close();
        }
    }

}
