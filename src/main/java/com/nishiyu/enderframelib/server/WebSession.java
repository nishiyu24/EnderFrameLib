package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.common.UIParser;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.function.Consumer;

public class WebSession {
    private final String sessionId;
    private final Consumer<WebSession> broadcastCallback;
    private final UIParser uiParser = new UIParser();
    private int width = 3 * (int) WebRenderCore.RESOLUTION;
    private int height = 4 * (int) WebRenderCore.RESOLUTION;
    private UIParser.Document currentDocument;
    private String currentCss = "";
    private RenderSource lastSource;

    public WebSession(String sessionId) {
        this(sessionId, WebSessionManager::broadcastDom);
    }

    public WebSession(String sessionId, Consumer<WebSession> broadcastCallback) {
        this.sessionId = sessionId;
        this.broadcastCallback = broadcastCallback;
    }

    public String getSessionId() { return sessionId; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getCurrentCss() { return currentCss; }
    public UIParser.Document getDocument() { return currentDocument; }

    public void updateSize(int newWidth, int newHeight) {
        if (this.width != newWidth || this.height != newHeight) {
            this.width = newWidth;
            this.height = newHeight;
            if (currentDocument != null) {
                requestReRender();
            }
        }
    }

    public void loadHtml(String html) {
        this.lastSource = new RenderSource(SourceType.STRING, html, "");
        this.currentCss = "";
        this.currentDocument = uiParser.parseDocument(html, "", width, height);
        requestReRender();
    }

    public void loadWithCss(String html, String css) {
        this.lastSource = new RenderSource(SourceType.STRING_WITH_CSS, html, css);
        this.currentCss = css != null ? css : "";
        this.currentDocument = uiParser.parseDocument(html, css, width, height);
        requestReRender();
    }

    public void reload() {
        if (lastSource == null) return;
        switch (lastSource.type) {
            case STRING -> loadHtml(lastSource.data1);
            case STRING_WITH_CSS -> loadWithCss(lastSource.data1, lastSource.data2);
        }
    }

    public void clear() {
        this.currentDocument = null;
        this.lastSource = null;
        this.currentCss = "";
        if (broadcastCallback != null) broadcastCallback.accept(this);
    }

    public void requestReRender() {
        if (currentDocument == null) return;
        if (broadcastCallback != null) broadcastCallback.accept(this);
    }

    public void patchElement(String elementId, Consumer<HtmlNode> domPatcher) {
        if (currentDocument == null || currentDocument.root == null) return;
        HtmlNode target = currentDocument.root.getElementById(elementId);
        if (target != null) {
            domPatcher.accept(target);
            if (broadcastCallback != null) broadcastCallback.accept(this);
        }
    }

    // ==========================================
    // 軽量差分パッチ配信 API (DOM全体を再送しない高速更新)
    // ==========================================

    public void patchText(String elementId, String newText) {
        if (currentDocument == null || currentDocument.root == null) return;
        HtmlNode target = currentDocument.root.getElementById(elementId);
        if (target != null) {
            target.setTextContent(newText);
            sendSinglePatch(elementId, "text", "", newText != null ? newText : "", false);
        }
    }

    public void patchAttribute(String elementId, String attrName, String attrValue, boolean remove) {
        if (currentDocument == null || currentDocument.root == null) return;
        HtmlNode target = currentDocument.root.getElementById(elementId);
        if (target != null) {
            DomUtils.updateAttribute(target, attrName, attrValue, remove);
            sendSinglePatch(elementId, "attr", attrName, attrValue != null ? attrValue : "", remove);
        }
    }

    public void patchStyle(String elementId, String property, String value) {
        if (currentDocument == null || currentDocument.root == null) return;
        HtmlNode target = currentDocument.root.getElementById(elementId);
        if (target != null) {
            target.setStyleProperty(property, value);
            sendSinglePatch(elementId, "style", property, value != null ? value : "", false);
        }
    }

    public void patchClass(String elementId, String className, String mode) {
        if (currentDocument == null || currentDocument.root == null) return;
        HtmlNode target = currentDocument.root.getElementById(elementId);
        if (target != null) {
            String op = switch (mode != null ? mode.toLowerCase() : "add") {
                case "remove" -> { target.removeClass(className); yield "remove_class"; }
                case "toggle" -> { target.toggleClass(className); yield "toggle_class"; }
                default -> { target.addClass(className); yield "add_class"; }
            };
            sendSinglePatch(elementId, op, "", className != null ? className : "", false);
        }
    }

    public void patchInnerHtml(String elementId, String htmlFragment) {
        if (currentDocument == null || currentDocument.root == null) return;
        HtmlNode target = currentDocument.root.getElementById(elementId);
        if (target != null) {
            target.setInnerHTML(htmlFragment);
            sendSinglePatch(elementId, "inner_html", "", htmlFragment != null ? htmlFragment : "", false);
        }
    }

    private void sendSinglePatch(String targetId, String op, String key, String value, boolean remove) {
        CompoundTag patch = new CompoundTag();
        patch.putString("TargetId", targetId);
        patch.putString("Op", op);
        if (key != null && !key.isEmpty()) patch.putString("Key", key);
        if (value != null) patch.putString("Value", value);
        if (remove) patch.putBoolean("Remove", true);

        ListTag list = new ListTag();
        list.add(patch);
        CompoundTag payload = new CompoundTag();
        payload.put("Patches", list);

        WebSessionManager.broadcastPatch(this.sessionId, payload);
    }

    public enum SourceType {STRING, STRING_WITH_CSS, FILE, URL}
    public record RenderSource(SourceType type, String data1, String data2) {}
}