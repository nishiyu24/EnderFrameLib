package com.nishiyu.enderframelib.client;

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

    public static BiConsumer<String, String> scriptLoaderCallback = null;
    public static Consumer<String> sessionClearedCallback = null;
    private static final List<Consumer<String>> SESSION_CLEARED_LISTENERS = new CopyOnWriteArrayList<>();
    public static Consumer<String> applyOverridesCallback = ClientScreenInteractionManager::applyLocalOverrides;

    private static final Map<String, List<UIElement>> RENDER_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, UIParser.Document> DOM_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, HtmlNode> VIRTUAL_DOM_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, HtmlNode> TEMPLATE_DOM_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> SERVER_MANAGED_SESSIONS = ConcurrentHashMap.newKeySet();
    private static final Set<String> DIRTY_SESSIONS = ConcurrentHashMap.newKeySet();
    private static final Set<String> REQUESTED_SESSIONS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> SYNC_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> DOM_HASH_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> LAST_ROOT_W = new ConcurrentHashMap<>();
    private static final Map<String, Integer> LAST_ROOT_H = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> SESSION_ENV = new ConcurrentHashMap<>();

    // セッション単位のスクロール管理
    private static final Map<String, ScrollState> SESSION_SCROLL_STATES = new ConcurrentHashMap<>();

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

        if (!forceReload && SERVER_MANAGED_SESSIONS.contains(sessionId) && DOM_CACHE.containsKey(sessionId)) {
            if (getLastRootW(sessionId) != targetW || getLastRootH(sessionId) != targetH) {
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

        SERVER_MANAGED_SESSIONS.add(sessionId);
        ClientScreenInteractionManager.ensureSynced(sessionId);
        if (DOM_CACHE.containsKey(sessionId) && (getLastRootW(sessionId) != targetW || getLastRootH(sessionId) != targetH)) {
            recomputeLayout(sessionId, targetW, targetH);
        }
        return false;
    }

    public static boolean isServerManagedSession(String sessionId) {
        return sessionId != null && SERVER_MANAGED_SESSIONS.contains(sessionId);
    }

    public static void recomputeAllLayouts() {
        for (String sessionId : DOM_CACHE.keySet()) {
            recomputeLayout(sessionId, LAST_ROOT_W.getOrDefault(sessionId, 1920), LAST_ROOT_H.getOrDefault(sessionId, 1080));
        }
    }

    public static void handleSyncPacket(String sessionId, CompoundTag tag) {
        if (tag.getBoolean("IsEmpty")) {
            clearSession(sessionId);
        } else if (tag.contains("DomTree")) {
            SERVER_MANAGED_SESSIONS.add(sessionId);
            CompoundTag domTag = tag.getCompound("DomTree");
            String cssString = tag.getString("CssString");
            String clientScript = tag.getString("ClientScript");

            int currentHash = Objects.hash(domTag.hashCode(), cssString.hashCode(), clientScript.hashCode());
            if (DOM_HASH_CACHE.getOrDefault(sessionId, 0) == currentHash && DOM_CACHE.containsKey(sessionId)) {
                REQUESTED_SESSIONS.remove(sessionId);
                return;
            }
            DOM_HASH_CACHE.put(sessionId, currentHash);

            int rootW = LAST_ROOT_W.getOrDefault(sessionId, tag.contains("RootW") ? tag.getInt("RootW") : 854);
            int rootH = LAST_ROOT_H.getOrDefault(sessionId, tag.contains("RootH") ? tag.getInt("RootH") : 480);

            HtmlNode rootNode = HtmlNodeSerializer.deserialize(domTag, null);
            installDocumentInternal(sessionId, rootNode, cssString, clientScript, rootW, rootH, true);
        }

        REQUESTED_SESSIONS.remove(sessionId);
    }

    public static void loadLocalHtml(String sessionId, String html, int width, int height) {
        UIParser parser = new UIParser();
        UIParser.Document parsedDoc = parser.parseDocument(html, "", width, height);
        installDocumentInternal(sessionId, parsedDoc.root, parsedDoc.authorCss, parsedDoc.clientScript, width, height, false);
    }

    private static void installDocumentInternal(String sessionId, HtmlNode rootNode, String authorCss, String clientScript, int rootW, int rootH, boolean fromServer) {
        ClientPubSubManager.clearSession(sessionId);
        notifySessionCleared(sessionId);

        LAST_ROOT_W.put(sessionId, rootW);
        LAST_ROOT_H.put(sessionId, rootH);

        UIParser parser = new UIParser();
        StyleSheet sheet = parser.buildStyleSheet(authorCss, rootW);
        UIParser.Document doc = new UIParser.Document(rootNode, sheet, authorCss, clientScript);
        DOM_CACHE.put(sessionId, doc);

        HtmlNode templateRoot = rootNode.cloneNode();
        TEMPLATE_DOM_CACHE.put(sessionId, templateRoot);

        HtmlNode virtualRoot = rootNode.cloneNode();
        VIRTUAL_DOM_CACHE.put(sessionId, virtualRoot);

        DeclarativeBindingManager.initializeBindings(sessionId, virtualRoot);
        ClientLocalEnvProvider.tickSessionLocalVariables(sessionId, templateRoot, virtualRoot);
        if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);
        DomDiffEngine.diffAndPatch(doc.root, virtualRoot);

        Map<String, String> currentEnv = SESSION_ENV.getOrDefault(sessionId, new HashMap<>());
        ScrollState scrollState = SESSION_SCROLL_STATES.computeIfAbsent(sessionId, k -> new ScrollState());
        List<UIElement> elements = parser.renderDocument(doc, rootW, rootH, currentEnv, scrollState, CLIENT_FONT_METRICS);
        RENDER_CACHE.put(sessionId, elements);

        if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);
        if (fromServer && scriptLoaderCallback != null && clientScript != null && !clientScript.isEmpty()) {
            scriptLoaderCallback.accept(sessionId, clientScript);
        }
    }

    public static void handlePatchPacket(String sessionId, CompoundTag data) {
        HtmlNode vRoot = getVirtualRoot(sessionId);
        if (vRoot == null || data == null) return;

        HtmlNode tmplRoot = TEMPLATE_DOM_CACHE.get(sessionId);
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

    public static HtmlNode getVirtualRoot(String sessionId) { return VIRTUAL_DOM_CACHE.get(sessionId); }
    public static HtmlNode getTemplateRoot(String sessionId) { return TEMPLATE_DOM_CACHE.get(sessionId); }

    public static void requestRender(String sessionId) {
        if (sessionId != null && !sessionId.isEmpty()) DIRTY_SESSIONS.add(sessionId);
    }

    public static void processRenderQueue() {
        if (DIRTY_SESSIONS.isEmpty()) return;
        for (String sessionId : new ArrayList<>(DIRTY_SESSIONS)) {
            DIRTY_SESSIONS.remove(sessionId);
            if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);
            UIParser.Document doc = DOM_CACHE.get(sessionId);
            HtmlNode virtualRoot = VIRTUAL_DOM_CACHE.get(sessionId);

            if (doc != null && doc.root != null && virtualRoot != null) {
                boolean changed = DomDiffEngine.diffAndPatch(doc.root, virtualRoot);
                if (changed) {
                    recomputeLayout(sessionId, LAST_ROOT_W.getOrDefault(sessionId, 1920), LAST_ROOT_H.getOrDefault(sessionId, 1080));
                }
            }
        }
    }

    public static void updateEnvAndRecomputeIfChanged(String sessionId, Map<String, String> newEnv) {
        Map<String, String> oldEnv = SESSION_ENV.getOrDefault(sessionId, new HashMap<>());
        if (!Objects.equals(oldEnv, newEnv)) {
            SESSION_ENV.put(sessionId, new HashMap<>(newEnv));
            recomputeLayout(sessionId, LAST_ROOT_W.getOrDefault(sessionId, 1920), LAST_ROOT_H.getOrDefault(sessionId, 1080));
        }
    }

    public static void recomputeLayout(String sessionId, int newRootW, int newRootH) {
        int oldRootW = LAST_ROOT_W.getOrDefault(sessionId, 0);
        LAST_ROOT_W.put(sessionId, newRootW);
        LAST_ROOT_H.put(sessionId, newRootH);

        if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);

        UIParser.Document doc = DOM_CACHE.get(sessionId);
        HtmlNode vRoot = VIRTUAL_DOM_CACHE.get(sessionId);
        if (doc != null && vRoot != null) {
            DomDiffEngine.diffAndPatch(doc.root, vRoot);
            UIParser parser = new UIParser();

            if (doc.sheet == null || oldRootW != newRootW) {
                doc.sheet = parser.buildStyleSheet(doc.authorCss, newRootW);
            }

            Map<String, String> currentEnv = SESSION_ENV.getOrDefault(sessionId, new HashMap<>());
            ScrollState scrollState = SESSION_SCROLL_STATES.computeIfAbsent(sessionId, k -> new ScrollState());
            List<UIElement> elements = parser.renderDocument(doc, newRootW, newRootH, currentEnv, scrollState, CLIENT_FONT_METRICS);
            RENDER_CACHE.put(sessionId, elements);
            if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);
        }
    }

    public static List<UIElement> getElements(String sessionId) {
        HtmlNode tmpl = TEMPLATE_DOM_CACHE.get(sessionId);
        HtmlNode vRoot = VIRTUAL_DOM_CACHE.get(sessionId);
        if (tmpl != null && vRoot != null) {
            ClientLocalEnvProvider.tickSessionLocalVariables(sessionId, tmpl, vRoot);
        }
        if (!DIRTY_SESSIONS.isEmpty()) {
            processRenderQueue();
        }
        return RENDER_CACHE.getOrDefault(sessionId, new ArrayList<>());
    }

    public static boolean shouldRequestSync(String sessionId) {
        long now = System.currentTimeMillis();
        long lastReq = SYNC_REQUESTS.getOrDefault(sessionId, 0L);
        if (now - lastReq > 2000) {
            SYNC_REQUESTS.put(sessionId, now);
            return true;
        }
        return false;
    }

    public static void markRequested(String sessionId) { REQUESTED_SESSIONS.add(sessionId); }

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
        List<UIElement> elements = RENDER_CACHE.getOrDefault(sessionId, Collections.emptyList());
        for (UIElement el : elements) {
            if (el.id().equals(id) && !"node".equals(el.type())) {
                return el;
            }
            if (targetBase.equals(getBaseNodeId(el.id()))) {
                if ("node".equals(el.type()) || el.id().endsWith("_bbg") || el.id().endsWith("_ph") || el.id().contains("_shadow") || el.id().contains("_b_")) {
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
            HtmlNode tmplTarget = DomUtils.findNodeById(TEMPLATE_DOM_CACHE.get(sessionId), baseId);
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

            HtmlNode tmplTarget = DomUtils.findNodeById(TEMPLATE_DOM_CACHE.get(sessionId), baseId);
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
            HtmlNode tmplTarget = DomUtils.findNodeById(TEMPLATE_DOM_CACHE.get(sessionId), baseId);
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
            HtmlNode tmplTarget = DomUtils.findNodeById(TEMPLATE_DOM_CACHE.get(sessionId), baseId);
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
            if (el.events() != null && el.events().containsKey("mc-scrollable")) {
                WebAnimator.AnimatedValues anim = WebAnimator.getCurrentValues(sessionId, el);
                double[] localPt = inverseTransform(pixelX, pixelY, el, anim);
                double localX = localPt[0] - el.x();
                double localY = localPt[1] - el.y();

                if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
                    String[] maxStrs = el.events().get("mc-scrollable").split(",");
                    float maxW = Float.parseFloat(maxStrs[0]), maxH = Float.parseFloat(maxStrs[1]);

                    ScrollState state = SESSION_SCROLL_STATES.computeIfAbsent(sessionId, k -> new ScrollState());
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

    private static double[] inverseTransform(double px, double py, UIElement el, WebAnimator.AnimatedValues anim) {
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

    private static double[] getLocalPointRecursive(String sessionId, UIElement el, double px, double py, Map<String, UIElement> elementMap) {
        if (el.events() != null && el.events().containsKey("mc-parent")) {
            UIElement parent = elementMap.get(el.events().get("mc-parent"));
            if (parent != null) {
                double[] p = getLocalPointRecursive(sessionId, parent, px, py, elementMap);
                px = p[0];
                py = p[1];
            }
        }
        WebAnimator.AnimatedValues anim = WebAnimator.getCurrentValues(sessionId, el);
        return inverseTransform(px, py, el, anim);
    }

    public static UIHitResult getHitElement(String sessionId, double pixelX, double pixelY) {
        List<UIElement> elements = getElements(sessionId);
        Map<String, UIElement> elementMap = new HashMap<>(elements.size());
        for (UIElement el : elements) elementMap.put(el.id(), el);

        for (int i = elements.size() - 1; i >= 0; i--) {
            UIElement el = elements.get(i);
            if ("node".equals(el.type()) || "text".equals(el.type()) || el.id().endsWith("_shadow") || el.id().contains("_sb_")
                    || el.id().endsWith("_bbg") || el.id().endsWith("_ph") || el.id().contains("_b_")) {
                continue;
            }

            boolean isInput = "input".equals(el.type()) || "textarea".equals(el.type()) || "select".equals(el.type())
                    || "range".equals(el.type()) || "toggle".equals(el.type()) || "checkbox".equals(el.type())
                    || "button".equals(el.type()) || "a".equals(el.type()) || "scene".equals(el.type());
            boolean hasInteraction = false;
            if (el.events() != null) {
                for (String key : el.events().keySet()) {
                    if (key.startsWith("on") || "mc-title".equals(key) || "draggable".equals(key)) {
                        hasInteraction = true;
                        break;
                    }
                }
            }
            if (el.type() == ElementType.IMAGE && el.text() != null && (el.text().startsWith("item:") || el.text().startsWith("fluid:"))) {
                hasInteraction = true;
            }
            UIElement parentAnchor = elementMap.get(el.events() != null ? el.events().get("mc-parent") : null);
            boolean hasHoverAnim = parentAnchor != null && (parentAnchor.hasHover() || parentAnchor.hasPseudo());

            if (!isInput && !hasInteraction && !hasHoverAnim) continue;

            double[] localPt = getLocalPointRecursive(sessionId, el, pixelX, pixelY, elementMap);
            double localX = localPt[0] - el.x();
            double localY = localPt[1] - el.y();

            if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
                return new UIHitResult(el, localX, localY);
            }
        }
        return null;
    }

    public static UIHitResult getLocalCoords(String sessionId, String elementId, double pixelX, double pixelY) {
        UIElement el = getElementById(sessionId, elementId);
        if (el == null) return null;
        List<UIElement> elements = RENDER_CACHE.getOrDefault(sessionId, Collections.emptyList());
        Map<String, UIElement> elementMap = new HashMap<>(elements.size());
        for (UIElement e : elements) elementMap.put(e.id(), e);

        double[] localPt = getLocalPointRecursive(sessionId, el, pixelX, pixelY, elementMap);
        return new UIHitResult(el, localPt[0] - el.x(), localPt[1] - el.y());
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
        HtmlNode tmplTarget = DomUtils.findNodeById(TEMPLATE_DOM_CACHE.get(sessionId), baseId);
        if (tmplTarget != null && !Objects.equals(tmplTarget.attrs.get("value"), domValue)) {
            DomUtils.updateAttribute(tmplTarget, "value", domValue, false);
        }

        List<UIElement> elements = RENDER_CACHE.get(sessionId);
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
        RENDER_CACHE.remove(sessionId);
        DOM_CACHE.remove(sessionId);
        VIRTUAL_DOM_CACHE.remove(sessionId);
        TEMPLATE_DOM_CACHE.remove(sessionId);
        SERVER_MANAGED_SESSIONS.remove(sessionId);
        DIRTY_SESSIONS.remove(sessionId);
        SESSION_ENV.remove(sessionId);
        SESSION_SCROLL_STATES.remove(sessionId);
        DOM_HASH_CACHE.remove(sessionId);
        LAST_ROOT_W.remove(sessionId);
        LAST_ROOT_H.remove(sessionId);
        SYNC_REQUESTS.remove(sessionId);
        REQUESTED_SESSIONS.remove(sessionId);
        ClientPubSubManager.clearSession(sessionId);
        WebCanvasContext.clearSession(sessionId);
        WebSceneContext.clearSession(sessionId);
        ClientLocalEnvProvider.clearSession(sessionId);
        notifySessionCleared(sessionId);
    }

    public static UIParser.Document getDocument(String sessionId) { return DOM_CACHE.get(sessionId); }
    public static boolean hasDocument(String sessionId) { return DOM_CACHE.containsKey(sessionId); }
    public static Map<String, String> getEnv(String sessionId) { return SESSION_ENV.getOrDefault(sessionId, new HashMap<>()); }
    public static int getLastRootW(String sessionId) { return LAST_ROOT_W.getOrDefault(sessionId, 1920); }
    public static int getLastRootH(String sessionId) { return LAST_ROOT_H.getOrDefault(sessionId, 1080); }

    public record UIHitResult(UIElement element, double localX, double localY) {}
}