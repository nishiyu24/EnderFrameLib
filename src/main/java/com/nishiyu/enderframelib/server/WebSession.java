package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.api.WebSessionHandle;
import com.nishiyu.enderframelib.common.UIParser;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * サーバー側セッションの実装。公開面は {@link WebSessionHandle}。
 */
@ApiStatus.Internal
public class WebSession implements WebSessionHandle {
    public static final int DEFAULT_BLOCK_RESOLUTION = 32;

    private final String sessionId;
    private final Consumer<WebSession> broadcastCallback;
    private final UIParser uiParser = new UIParser();
    private int width = 3 * DEFAULT_BLOCK_RESOLUTION;
    private int height = 4 * DEFAULT_BLOCK_RESOLUTION;
    private UIParser.Document currentDocument;
    private String currentCss = "";
    private RenderSource lastSource;

    /**
     * 直近に送出した DOM が宣言していたサーバーアクション名。
     * クライアントは任意の文字列を送れるので、ここに無い名前のディスパッチは拒否する（I-3）。
     */
    private volatile Set<String> declaredActions = Set.of();
    /** 値の書き戻しを許可する要素 ID（input/textarea/select のみ）。 */
    private volatile Set<String> valueTargets = Set.of();
    /**
     * 画面側が DOM に無いアクション名を {@code sendServerAction} で直接送る設計の場合、
     * サーバーには検証材料が無いのでホストが明示的に検証を外す。
     */
    private volatile boolean actionValidation = true;
    private volatile long lastAccessMillis = System.currentTimeMillis();

    public WebSession(String sessionId) {
        this(sessionId, WebSessionManager::broadcastDom);
    }

    public WebSession(String sessionId, Consumer<WebSession> broadcastCallback) {
        this.sessionId = sessionId;
        this.broadcastCallback = broadcastCallback;
    }

    @Override public String getSessionId() { return sessionId; }
    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return height; }
    public String getCurrentCss() { return currentCss; }
    @Override public UIParser.Document getDocument() { return currentDocument; }

    @Override
    public void updateSize(int newWidth, int newHeight) {
        if (this.width != newWidth || this.height != newHeight) {
            this.width = newWidth;
            this.height = newHeight;
            if (currentDocument != null) {
                requestReRender();
            }
        }
    }

    @Override
    public void loadHtml(String html) {
        this.lastSource = new RenderSource(SourceType.STRING, html, "");
        this.currentCss = "";
        this.currentDocument = uiParser.parseDocument(html, "", width, height);
        indexDocument();
        requestReRender();
    }

    @Override
    public void loadWithCss(String html, String css) {
        this.lastSource = new RenderSource(SourceType.STRING_WITH_CSS, html, css);
        this.currentCss = css != null ? css : "";
        this.currentDocument = uiParser.parseDocument(html, css, width, height);
        indexDocument();
        requestReRender();
    }

    @Override
    public void reload() {
        if (lastSource == null) return;
        switch (lastSource.type) {
            case STRING -> loadHtml(lastSource.data1);
            case STRING_WITH_CSS -> loadWithCss(lastSource.data1, lastSource.data2);
        }
    }

    @Override
    public void clear() {
        discard();
        if (broadcastCallback != null) broadcastCallback.accept(this);
    }

    /**
     * 文書を捨てるが、閲覧者には知らせない。
     *
     * <p>サーバー停止時の後片付け専用。{@link #clear()} は空の DOM を配信するが、
     * 停止中は接続が既に畳まれていることがあり、そこへ送ろうとした例外が
     * {@code ServerStoppingEvent} のリスナから抜けてサーバーをクラッシュさせていた。
     * 相手がもういない場面で「空になった」と伝える意味も無い。</p>
     */
    void discard() {
        this.currentDocument = null;
        this.lastSource = null;
        this.currentCss = "";
        this.declaredActions = Set.of();
        this.valueTargets = Set.of();
    }

    @Override
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
    // クライアントイベントの検証（I-3）
    // ==========================================

    /**
     * 送出した DOM が宣言していたアクション名かどうか。
     * {@code "name:arg"} 形式は {@code name} 部分だけを見る。
     */
    public boolean declaresAction(String actionName) {
        if (!actionValidation) return true;
        if (actionName == null || actionName.isEmpty()) return false;
        String head = actionName.contains(":") ? actionName.substring(0, actionName.indexOf(':')) : actionName;
        return declaredActions.contains(head);
    }

    /** 値の書き戻し先として妥当な要素 ID か（input/textarea/select のみ）。 */
    public boolean allowsValueWrite(String elementId) {
        return elementId != null && valueTargets.contains(elementId);
    }

    /**
     * {@code sendServerAction} で DOM に無いアクション名を送る画面のためのエスケープハッチ。
     * 外すとクライアントは任意のアクション名をこのセッションのレシーバに届けられる。
     */
    public void setActionValidationEnabled(boolean enabled) {
        this.actionValidation = enabled;
    }

    public boolean isActionValidationEnabled() {
        return actionValidation;
    }

    long lastAccessMillis() {
        return lastAccessMillis;
    }

    void markAccessed() {
        this.lastAccessMillis = System.currentTimeMillis();
    }

    /** 送出予定の DOM からアクション名と値の書き戻し先を収集する。 */
    private void indexDocument() {
        Set<String> actions = new HashSet<>();
        Set<String> inputs = new HashSet<>();
        if (currentDocument != null && currentDocument.root != null) {
            collectDeclarations(currentDocument.root, actions, inputs);
        }
        this.declaredActions = Set.copyOf(actions);
        this.valueTargets = Set.copyOf(inputs);
    }

    private static final Set<String> VALUE_TAGS = Set.of("input", "textarea", "select", "option");

    private static void collectDeclarations(HtmlNode node, Set<String> actions, Set<String> inputs) {
        for (Map.Entry<String, String> attr : node.attrs.entrySet()) {
            String name = attr.getKey();
            String value = attr.getValue();
            if (value == null || value.isEmpty()) continue;
            if (!name.regionMatches(true, 0, "on", 0, 2)) continue;
            if (!value.startsWith("server:")) continue;
            String rest = value.substring("server:".length());
            int colon = rest.indexOf(':');
            String head = colon >= 0 ? rest.substring(0, colon) : rest;
            if (!head.isEmpty()) actions.add(head);
        }
        if (VALUE_TAGS.contains(node.tag.toLowerCase()) && !node.getId().isEmpty()) {
            inputs.add(node.getId());
        }
        for (HtmlNode child : node.children) {
            collectDeclarations(child, actions, inputs);
        }
    }

    // ==========================================
    // 軽量差分パッチ配信 API
    // ==========================================

    @Override
    public void patchText(String elementId, String newText) {
        if (currentDocument == null || currentDocument.root == null) return;
        HtmlNode target = currentDocument.root.getElementById(elementId);
        if (target != null) {
            target.setTextContent(newText);
            sendSinglePatch(elementId, "text", "", newText != null ? newText : "", false);
        }
    }

    @Override
    public void patchAttribute(String elementId, String attrName, String attrValue, boolean remove) {
        if (currentDocument == null || currentDocument.root == null) return;
        HtmlNode target = currentDocument.root.getElementById(elementId);
        if (target != null) {
            DomUtils.updateAttribute(target, attrName, attrValue, remove);
            sendSinglePatch(elementId, "attr", attrName, attrValue != null ? attrValue : "", remove);
        }
    }

    @Override
    public void patchStyle(String elementId, String property, String value) {
        if (currentDocument == null || currentDocument.root == null) return;
        HtmlNode target = currentDocument.root.getElementById(elementId);
        if (target != null) {
            target.setStyleProperty(property, value);
            sendSinglePatch(elementId, "style", property, value != null ? value : "", false);
        }
    }

    @Override
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

    @Override
    public void patchInnerHtml(String elementId, String htmlFragment) {
        if (currentDocument == null || currentDocument.root == null) return;
        HtmlNode target = currentDocument.root.getElementById(elementId);
        if (target != null) {
            target.setInnerHTML(htmlFragment);
            // 差し替えた部分木が新たなアクションや入力要素を持ち込むので索引を取り直す
            indexDocument();
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
