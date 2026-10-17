package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.model.RenderKeys;
import com.nishiyu.enderframelib.core.model.ElementType;
import com.nishiyu.enderframelib.client.binding.ClientPubSubManager;
import com.nishiyu.enderframelib.client.binding.DeclarativeBindingManager;
import com.nishiyu.enderframelib.client.renderer.WebAnimator;
import com.nishiyu.enderframelib.client.renderer.WebCanvasContext;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.common.EnderFrameBuiltInConfig;
import com.nishiyu.enderframelib.common.UIParser;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.DomDiffEngine;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.layout.ScrollState;
import com.nishiyu.enderframelib.core.text.FontMetrics;
import com.nishiyu.enderframelib.network.dom.HtmlNodeSerializer;
import com.nishiyu.enderframelib.core.model.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ClientWebManager {

    public static Consumer<String> sessionClearedCallback = null;
    private static final List<Consumer<String>> SESSION_CLEARED_LISTENERS = new CopyOnWriteArrayList<>();
    public static Consumer<String> applyOverridesCallback = ClientScreenInteractionManager::applyLocalOverrides;

    private static final Map<String, SessionContext> SESSIONS = new ConcurrentHashMap<>();
    private static final Set<String> DIRTY_SESSIONS = ConcurrentHashMap.newKeySet();

    public static SessionContext getSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        return SESSIONS.get(sessionId);
    }

    public static SessionContext getOrCreateSession(String sessionId) {
        String safeId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        return SESSIONS.computeIfAbsent(safeId, SessionContext::new);
    }

    /**
     * フォーカス中の入力要素のレンダー ID。セッション単位で保持されるため、
     * GUI 画面とブロック画面が同時に開いていてもフォーカスは混ざらない。
     */
    public static String getActiveInputId(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.activeInputId() : "";
    }

    public static void setActiveInputId(String sessionId, String id) {
        // クリアだけのために空のセッションを作らない。
        SessionContext session = (id == null || id.isEmpty()) ? getSession(sessionId) : getOrCreateSession(sessionId);
        if (session != null) session.setActiveInputId(id);
    }

    public static boolean isActiveInput(String sessionId, String renderId) {
        String active = getActiveInputId(sessionId);
        return !active.isEmpty() && active.equals(renderId);
    }

    /** 押下中（:active）の要素の基底ノード ID。セッション単位。 */
    public static String getActivePressedId(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.activePressedId() : "";
    }

    public static void setActivePressedId(String sessionId, String id) {
        SessionContext session = (id == null || id.isEmpty()) ? getSession(sessionId) : getOrCreateSession(sessionId);
        if (session != null) session.setActivePressedId(id);
    }

    /** フォーカス中の入力の基底ノード ID が {@code baseId} と一致するか。 */
    public static boolean isFocusedBase(String sessionId, String baseId) {
        String active = getActiveInputId(sessionId);
        return !active.isEmpty() && getBaseNodeId(active).equals(baseId);
    }

    /** 押下中の要素の基底ノード ID が {@code baseId} と一致するか。 */
    public static boolean isPressedBase(String sessionId, String baseId) {
        String pressed = getActivePressedId(sessionId);
        return !pressed.isEmpty() && getBaseNodeId(pressed).equals(baseId);
    }

    public static final FontMetrics CLIENT_FONT_METRICS = (text, scale, isBold) -> {
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        float adjustedScale = scale * (16.0f / 12.0f);
        String measureText = isBold ? ("§l" + text) : text;
        return (int) Math.ceil(font.width(measureText) * adjustedScale);
    };

    private static final String[] RENDER_SUFFIXES = {
            "_ghost_img", "_b_bottom", "_b_right", "_textarea", "_progress", "_b_left", "_select", "_canvas",
            "_b_top", "_sb_vt", "_sb_ht", "_scene", "_input", "_sb_v", "_sb_h", "_bbg", "_dot", "_img", "_cb", "_bg", "_ph"
    };

    public static void addSessionClearedListener(Consumer<String> listener) {
        if (listener != null && !SESSION_CLEARED_LISTENERS.contains(listener)) {
            SESSION_CLEARED_LISTENERS.add(listener);
        }
    }

    private static void notifySessionCleared(String sessionId) {
        if (sessionClearedCallback != null) {
            try { sessionClearedCallback.accept(sessionId); } catch (Exception ignored) {}
        }
        for (Consumer<String> listener : SESSION_CLEARED_LISTENERS) {
            try { listener.accept(sessionId); } catch (Exception ignored) {}
        }
    }

    public static boolean initOrBindSession(String sessionId, String rawHtml, ResourceLocation htmlResource, int width, int height, boolean forceReload) {
        int targetW = width > 0 ? width : 854;
        int targetH = height > 0 ? height : 480;

        SessionContext ctx = getSession(sessionId);
        if (!forceReload && ctx != null && ctx.isServerManaged() && ctx.document() != null) {
            if (ctx.rootW() != targetW || ctx.rootH() != targetH) {
                recomputeLayout(sessionId, targetW, targetH);
            } else {
                requestRender(sessionId);
                processRenderQueue();
            }
            return false;
        }

        String resolvedHtml = rawHtml;
        if ((resolvedHtml == null || resolvedHtml.isEmpty()) && htmlResource != null) {
            resolvedHtml = UIParser.resourceTextResolver != null
                    ? UIParser.resourceTextResolver.apply(htmlResource.toString())
                    : "";
        }

        if (resolvedHtml != null && !resolvedHtml.isEmpty()) {
            loadLocalHtml(sessionId, resolvedHtml, targetW, targetH);
            return true;
        }

        SessionContext session = getOrCreateSession(sessionId);
        session.setServerManaged(true);
        ClientScreenInteractionManager.ensureSynced(sessionId);
        if (session.document() != null && (session.rootW() != targetW || session.rootH() != targetH)) {
            recomputeLayout(sessionId, targetW, targetH);
        }
        return false;
    }

    public static boolean isServerManagedSession(String sessionId) {
        SessionContext ctx = getSession(sessionId);
        return ctx != null && ctx.isServerManaged();
    }

    public static void recomputeAllLayouts() {
        for (SessionContext ctx : SESSIONS.values()) {
            if (ctx.document() != null) {
                recomputeLayout(ctx.sessionId(), ctx.rootW(), ctx.rootH());
            }
        }
    }

    public static void handleSyncPacket(String sessionId, CompoundTag tag) {
        if (tag.getBoolean("IsEmpty")) {
            clearSession(sessionId);
        } else if (tag.contains("DomTree")) {
            SessionContext session = getOrCreateSession(sessionId);
            session.setServerManaged(true);
            CompoundTag domTag = tag.getCompound("DomTree");
            String cssString = tag.getString("CssString");

            int currentHash = Objects.hash(domTag.hashCode(), cssString.hashCode());
            if (session.domHash() == currentHash && session.document() != null) {
                session.setRequested(false);
                return;
            }
            session.setDomHash(currentHash);

            int rootW = session.rootW() > 0 ? session.rootW() : (tag.contains("RootW") ? tag.getInt("RootW") : 854);
            int rootH = session.rootH() > 0 ? session.rootH() : (tag.contains("RootH") ? tag.getInt("RootH") : 480);

            HtmlNode rootNode = HtmlNodeSerializer.deserialize(domTag, null);
            installDocumentInternal(sessionId, rootNode, cssString, rootW, rootH, true);
        }

        SessionContext session = getSession(sessionId);
        if (session != null) {
            session.setRequested(false);
        }
    }

    public static void loadLocalHtml(String sessionId, String html, int width, int height) {
        UIParser parser = new UIParser();
        UIParser.Document parsedDoc = parser.parseDocument(html, "", width, height);
        installDocumentInternal(sessionId, parsedDoc.root, parsedDoc.authorCss, width, height, false);
    }

    private static void installDocumentInternal(String sessionId, HtmlNode rootNode, String authorCss, int rootW, int rootH, boolean fromServer) {
        SessionContext session = getOrCreateSession(sessionId);
        session.close();
        SESSIONS.put(sessionId, session);
        notifySessionCleared(sessionId);

        session.setRootW(rootW);
        session.setRootH(rootH);
        if (fromServer) session.setServerManaged(true);

        UIParser parser = new UIParser();
        StyleSheet sheet = parser.buildStyleSheet(authorCss, rootW);
        UIParser.Document doc = new UIParser.Document(rootNode, sheet, authorCss);
        session.setDocument(doc);

        HtmlNode templateRoot = rootNode.cloneNode();
        session.setTemplateDom(templateRoot);

        HtmlNode virtualRoot = rootNode.cloneNode();
        session.setVirtualDom(virtualRoot);

        DeclarativeBindingManager.initializeBindings(sessionId, virtualRoot);
        ClientLocalEnvProvider.tickSessionLocalVariables(sessionId, templateRoot, virtualRoot);
        if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);
        DomDiffEngine.diffAndPatch(doc.root, virtualRoot);

        List<UIElement> elements = parser.renderDocument(doc, rootW, rootH, session.env(), session.scrollState(), CLIENT_FONT_METRICS);
        session.setRenderCache(elements);

        if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);
    }

    public static void handlePatchPacket(String sessionId, CompoundTag data) {
        HtmlNode vRoot = getVirtualRoot(sessionId);
        if (vRoot == null || data == null) return;

        HtmlNode tmplRoot = getTemplateRoot(sessionId);
        ListTag patches = data.getList("Patches", Tag.TAG_COMPOUND);
        boolean anyChanged = false;

        for (int i = 0; i < patches.size(); i++) {
            CompoundTag patch = patches.getCompound(i);
            String targetId = getBaseNodeId(patch.getString("TargetId"));
            String op = patch.getString("Op");

            HtmlNode target = DomUtils.findNodeById(vRoot, targetId);
            if (target == null) continue;
            HtmlNode tmplTarget = tmplRoot != null ? DomUtils.findNodeById(tmplRoot, targetId) : null;

            switch (op) {
                case "text" -> {
                    String val = patch.getString("Value");
                    target.setTextContent(val);
                    if (tmplTarget != null) tmplTarget.setTextContent(val);
                    anyChanged = true;
                }
                case "attr" -> {
                    String key = patch.getString("Key");
                    boolean remove = patch.getBoolean("Remove");
                    String val = patch.getString("Value");
                    DomUtils.updateAttribute(target, key, val, remove);
                    if (tmplTarget != null) DomUtils.updateAttribute(tmplTarget, key, val, remove);
                    if ("value".equalsIgnoreCase(key)) {
                        ClientScreenInteractionManager.syncServerValueOverride(sessionId, targetId, remove ? "" : val);
                    }
                    anyChanged = true;
                }
                case "style" -> {
                    String prop = patch.getString("Key");
                    String val = patch.getString("Value");
                    target.setStyleProperty(prop, val);
                    if (tmplTarget != null) tmplTarget.setStyleProperty(prop, val);
                    anyChanged = true;
                }
                case "add_class" -> {
                    String val = patch.getString("Value");
                    target.addClass(val);
                    if (tmplTarget != null) tmplTarget.addClass(val);
                    anyChanged = true;
                }
                case "remove_class" -> {
                    String val = patch.getString("Value");
                    target.removeClass(val);
                    if (tmplTarget != null) tmplTarget.removeClass(val);
                    anyChanged = true;
                }
                case "toggle_class" -> {
                    String val = patch.getString("Value");
                    target.toggleClass(val);
                    if (tmplTarget != null) tmplTarget.toggleClass(val);
                    anyChanged = true;
                }
                case "inner_html" -> {
                    String val = patch.getString("Value");
                    target.setInnerHTML(val);
                    if (tmplTarget != null) tmplTarget.setInnerHTML(val);
                    DeclarativeBindingManager.initializeBindings(sessionId, target);
                    anyChanged = true;
                }
                case "remove_node" -> {
                    target.remove();
                    if (tmplTarget != null) tmplTarget.remove();
                    anyChanged = true;
                }
            }
        }

        if (anyChanged) {
            if (tmplRoot != null) {
                ClientLocalEnvProvider.tickSessionLocalVariables(sessionId, tmplRoot, vRoot);
            }
            requestRender(sessionId);
            processRenderQueue();
        }
    }

    public static HtmlNode getVirtualRoot(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.virtualDom() : null;
    }

    public static HtmlNode getTemplateRoot(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.templateDom() : null;
    }

    public static void requestRender(String sessionId) {
        if (sessionId != null && !sessionId.isEmpty()) DIRTY_SESSIONS.add(sessionId);
    }

    public static void processRenderQueue() {
        if (DIRTY_SESSIONS.isEmpty()) return;
        for (String sessionId : new ArrayList<>(DIRTY_SESSIONS)) {
            DIRTY_SESSIONS.remove(sessionId);
            if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);
            SessionContext session = getSession(sessionId);
            if (session != null && session.document() != null && session.document().root != null && session.virtualDom() != null) {
                boolean changed = DomDiffEngine.diffAndPatch(session.document().root, session.virtualDom());
                if (changed) {
                    recomputeLayout(sessionId, session.rootW(), session.rootH());
                }
            }
        }
    }

    public static void updateEnvAndRecomputeIfChanged(String sessionId, Map<String, String> newEnv) {
        SessionContext session = getOrCreateSession(sessionId);
        if (!Objects.equals(session.env(), newEnv)) {
            session.setEnv(newEnv);
            recomputeLayout(sessionId, session.rootW(), session.rootH());
        }
    }

    public static void recomputeLayout(String sessionId, int newRootW, int newRootH) {
        SessionContext session = getSession(sessionId);
        if (session == null) return;
        int oldRootW = session.rootW();
        session.setRootW(newRootW);
        session.setRootH(newRootH);

        if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);

        UIParser.Document doc = session.document();
        HtmlNode vRoot = session.virtualDom();
        if (doc != null && vRoot != null) {
            DomDiffEngine.diffAndPatch(doc.root, vRoot);
            UIParser parser = new UIParser();

            if (doc.sheet == null || oldRootW != newRootW) {
                doc.sheet = parser.buildStyleSheet(doc.authorCss, newRootW);
            }

            List<UIElement> elements = parser.renderDocument(doc, newRootW, newRootH, session.env(), session.scrollState(), CLIENT_FONT_METRICS);
            session.setRenderCache(elements);
            if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);
        }
    }

    public static List<UIElement> getElements(String sessionId) {
        SessionContext session = getSession(sessionId);
        if (session != null) {
            HtmlNode tmpl = session.templateDom();
            HtmlNode vRoot = session.virtualDom();
            if (tmpl != null && vRoot != null) {
                ClientLocalEnvProvider.tickSessionLocalVariables(sessionId, tmpl, vRoot);
            }
        }
        if (!DIRTY_SESSIONS.isEmpty()) {
            processRenderQueue();
        }
        return session != null ? session.renderCache() : Collections.emptyList();
    }

    public static boolean shouldRequestSync(String sessionId) {
        SessionContext session = getOrCreateSession(sessionId);
        long now = System.currentTimeMillis();
        long lastReq = session.lastSyncRequestTime();
        if (now - lastReq > 2000) {
            session.setLastSyncRequestTime(now);
            return true;
        }
        return false;
    }

    public static void markRequested(String sessionId) {
        getOrCreateSession(sessionId).setRequested(true);
    }

    public static String getBaseNodeId(String renderId) {
        if (renderId == null || renderId.isEmpty()) return "";

        for (String suffix : RENDER_SUFFIXES) {
            if (renderId.length() > suffix.length() && renderId.endsWith(suffix)) {
                return renderId.substring(0, renderId.length() - suffix.length());
            }
        }
        return renderId;
    }

    public static UIElement getElementById(String sessionId, String id) {
        if (id == null) return null;
        String targetBase = getBaseNodeId(id);
        UIElement fallback = null;
        SessionContext session = getSession(sessionId);
        List<UIElement> elements = session != null ? session.renderCache() : Collections.emptyList();
        for (UIElement el : elements) {
            if (el.id().equals(id) && el.type() != ElementType.NODE) {
                return el;
            }
            if (targetBase.equals(getBaseNodeId(el.id()))) {
                if (el.type() == ElementType.NODE || el.id().endsWith("_bbg") || el.id().endsWith("_ph") || el.id().contains("_shadow") || el.id().contains("_b_")) {
                    if (fallback == null) fallback = el;
                    continue;
                }
                return el;
            }
        }
        return fallback;
    }

    public static void updateNodeAttributeLocally(String sessionId, String elementId, String attrName, String attrValue, boolean remove) {
        String baseId = getBaseNodeId(elementId);
        HtmlNode target = DomUtils.findNodeById(getVirtualRoot(sessionId), baseId);
        if (target != null) {
            DomUtils.updateAttribute(target, attrName, attrValue, remove);
            HtmlNode tmplTarget = DomUtils.findNodeById(getTemplateRoot(sessionId), baseId);
            if (tmplTarget != null && !"hover".equals(attrName)) {
                DomUtils.updateAttribute(tmplTarget, attrName, attrValue, remove);
            }
            if ("value".equalsIgnoreCase(attrName)) {
                updateElementTextLocally(sessionId, baseId, remove || attrValue == null ? "" : attrValue);
            }
            requestRender(sessionId);
        }
    }

    public static void toggleNodeAttributeLocally(String sessionId, String elementId, String attrName) {
        String baseId = getBaseNodeId(elementId);
        HtmlNode target = DomUtils.findNodeById(getVirtualRoot(sessionId), baseId);
        if (target != null) {
            DomUtils.toggleAttribute(target, attrName);
            boolean hasNow = target.attrs.containsKey(attrName);
            String valNow = target.attrs.get(attrName);

            HtmlNode tmplTarget = DomUtils.findNodeById(getTemplateRoot(sessionId), baseId);
            if (tmplTarget != null) {
                DomUtils.updateAttribute(tmplTarget, attrName, valNow, !hasNow);
            }
            requestRender(sessionId);
        }
    }

    public static void updateNodeInnerHtmlLocally(String sessionId, String elementId, String html) {
        String baseId = getBaseNodeId(elementId);
        HtmlNode target = DomUtils.findNodeById(getVirtualRoot(sessionId), baseId);
        if (target != null) {
            target.setInnerHTML(html);
            HtmlNode tmplTarget = DomUtils.findNodeById(getTemplateRoot(sessionId), baseId);
            if (tmplTarget != null) {
                tmplTarget.setInnerHTML(html);
            }
            DeclarativeBindingManager.initializeBindings(sessionId, target);
            requestRender(sessionId);
        }
    }

    public static void updateNodeStyleLocally(String sessionId, String elementId, String prop, String val) {
        String baseId = getBaseNodeId(elementId);
        HtmlNode target = DomUtils.findNodeById(getVirtualRoot(sessionId), baseId);
        if (target != null) {
            target.setStyleProperty(prop, val);
            HtmlNode tmplTarget = DomUtils.findNodeById(getTemplateRoot(sessionId), baseId);
            if (tmplTarget != null) {
                tmplTarget.setStyleProperty(prop, val);
            }
            requestRender(sessionId);
        }
    }

    public static boolean handleScrollLocally(String sessionId, double pixelX, double pixelY, double scrollX, double scrollY) {
        List<UIElement> elements = getElements(sessionId);
        float sens = EnderFrameBuiltInConfig.scrollSensitivity;

        for (int i = elements.size() - 1; i >= 0; i--) {
            UIElement el = elements.get(i);
            if (el.events() != null && el.events().containsKey(RenderKeys.MC_SCROLLABLE)) {
                WebAnimator.AnimatedValues anim = WebAnimator.getCurrentValues(sessionId, el);
                double[] localPt = inverseTransform(pixelX, pixelY, el, anim);
                double localX = localPt[0] - el.x();
                double localY = localPt[1] - el.y();

                if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
                    String[] maxStrs = el.events().get(RenderKeys.MC_SCROLLABLE).split(",");
                    float maxW = Float.parseFloat(maxStrs[0]), maxH = Float.parseFloat(maxStrs[1]);

                    SessionContext session = getOrCreateSession(sessionId);
                    ScrollState state = session.scrollState();
                    float curX = state.getScrollX(el.id());
                    float curY = state.getScrollY(el.id());

                    float limitX = Math.max(0, maxW - el.width()), limitY = Math.max(0, maxH - el.height());
                    if (limitX > 0 || limitY > 0) {
                        float nextX = Mth.clamp(curX - (float) scrollX * sens, 0, limitX);
                        float nextY = Mth.clamp(curY - (float) scrollY * sens, 0, limitY);

                        if (Math.abs(nextX - curX) > 0.1f || Math.abs(nextY - curY) > 0.1f) {
                            state.setScroll(el.id(), (int) nextX, (int) nextY);
                            requestRender(sessionId);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public static double[] inverseTransform(double px, double py, UIElement el, WebAnimator.AnimatedValues anim) {
        UIElement.RenderMeta meta = el.meta();
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

    public static UIHitResult getHitElement(String sessionId, double pixelX, double pixelY) {
        SessionContext session = getSession(sessionId);
        if (session == null) return null;
        return session.hitTestIndex().hitTest(sessionId, pixelX, pixelY);
    }

    public static UIHitResult getLocalCoords(String sessionId, String elementId, double pixelX, double pixelY) {
        UIElement el = getElementById(sessionId, elementId);
        if (el == null) return null;
        SessionContext session = getSession(sessionId);
        if (session == null) return null;
        return session.hitTestIndex().getLocalCoords(sessionId, el, pixelX, pixelY);
    }

    public static String resolveSelectDisplayLabel(HtmlNode selectNode, String currentVal) {
        if (selectNode == null) return currentVal != null ? currentVal : "";
        String val = currentVal != null ? currentVal : selectNode.attrs.getOrDefault("value", "");
        String firstLabel = null;
        for (HtmlNode child : selectNode.children) {
            if ("option".equalsIgnoreCase(child.tag)) {
                String optVal = child.attrs.getOrDefault("value", child.getTextContent().trim());
                String optLabel = child.getTextContent().trim();
                if (firstLabel == null) firstLabel = optLabel.isEmpty() ? optVal : optLabel;
                if (val.isEmpty() || val.equals(optVal)) {
                    return optLabel.isEmpty() ? optVal : optLabel;
                }
            }
        }
        return firstLabel != null ? firstLabel : val;
    }

    public static void updateElementTextLocally(String sessionId, String elementId, String newText) {
        String safeText = newText != null ? newText : "";
        String baseId = getBaseNodeId(elementId);

        HtmlNode target = DomUtils.findNodeById(getVirtualRoot(sessionId), baseId);
        boolean isRange = target != null && "range".equalsIgnoreCase(target.attrs.getOrDefault("type", ""));
        boolean isSelect = target != null && "select".equalsIgnoreCase(target.tag);

        String domValue = safeText;
        if (isRange && safeText.contains(":")) {
            domValue = safeText.split(":")[0];
        }

        if (target != null && !Objects.equals(target.attrs.get("value"), domValue)) {
            DomUtils.updateAttribute(target, "value", domValue, false);
        }
        HtmlNode tmplTarget = DomUtils.findNodeById(getTemplateRoot(sessionId), baseId);
        if (tmplTarget != null && !Objects.equals(tmplTarget.attrs.get("value"), domValue)) {
            DomUtils.updateAttribute(tmplTarget, "value", domValue, false);
        }

        SessionContext session = getSession(sessionId);
        List<UIElement> elements = session != null ? session.renderCache() : null;
        if (elements != null) {
            String phId = baseId + "_ph";
            String inputRenderId = baseId + "_input";
            String textareaRenderId = baseId + "_textarea";
            String selectRenderId = baseId + "_select";
            String placeholderOrig = target != null ? target.attrs.getOrDefault("placeholder", "") : "";

            for (int i = 0; i < elements.size(); i++) {
                UIElement el = elements.get(i);
                if (el.id().equals(elementId) || el.id().equals(inputRenderId) || el.id().equals(textareaRenderId) || el.id().equals(selectRenderId)) {
                    String renderText = safeText;
                    if (el.type() == ElementType.RANGE && !safeText.contains(":")) {
                        String min = target != null ? target.attrs.getOrDefault("min", "0") : "0";
                        String max = target != null ? target.attrs.getOrDefault("max", "100") : "100";
                        renderText = safeText + ":" + min + ":" + max;
                    } else if (el.type() == ElementType.SELECT || isSelect) {
                        renderText = resolveSelectDisplayLabel(target, domValue) + " \u25be";
                    }
                    elements.set(i, el.withText(renderText));
                } else if (el.id().equals(phId)) {
                    String nextPh = domValue.isEmpty() ? placeholderOrig : "";
                    elements.set(i, el.withText(nextPh));
                }
            }
        }
    }

    public static void clearSession(String sessionId) {
        if (sessionId == null) return;
        DIRTY_SESSIONS.remove(sessionId);
        SessionContext ctx = SESSIONS.remove(sessionId);
        if (ctx != null) {
            ctx.close();
        } else {
            ClientPubSubManager.clearSession(sessionId);
            WebCanvasContext.clearSession(sessionId);
            WebSceneContext.clearSession(sessionId);
            ClientLocalEnvProvider.clearSession(sessionId);
            WebAnimator.clearSession(sessionId);
        }
        notifySessionCleared(sessionId);
    }

    public static UIParser.Document getDocument(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.document() : null;
    }

    public static boolean hasDocument(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null && session.document() != null;
    }

    public static Map<String, String> getEnv(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.env() : new HashMap<>();
    }

    public static int getLastRootW(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.rootW() : 1920;
    }

    public static int getLastRootH(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.rootH() : 1080;
    }

    public record UIHitResult(UIElement element, double localX, double localY) {}
}