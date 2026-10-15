package com.nishiyu.enderframelib.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.binding.WebStateStore;
import com.nishiyu.enderframelib.client.renderer.WebCanvasContext;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.network.c2s.WebActionC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * 2D GUIとしてHTML/CSS画面を描画・操作するための汎用Screen基底クラス。
 * WebStateStore、ラムダ式イベントルーター、<textarea>、<select>、<canvas>、<mc-scene>、
 * ドラッグ＆ドロップ、1行サーバーRPC送信、F12 DevToolsインスペクタ、F5 ホットリロードを標準搭載。
 */
public class AbstractWebScreen extends Screen {
    public static final float DEFAULT_VIRTUAL_WIDTH = 854.0f;
    public static final float DEFAULT_VIRTUAL_HEIGHT = 480.0f;

    protected final String sessionId;
    public final WebStateStore state;

    protected int virtualWidth;
    protected int virtualHeight;
    protected boolean fullscreenResponsive = false;
    protected float viewportMarginRatio = 0.94f;
    protected float guiScaleFactor = 1.0f;
    protected float offsetX = 0.0f;
    protected float offsetY = 0.0f;
    protected EditBox hiddenEditBox;
    protected String hoveredId = "";
    private boolean suppressInputResponder = false;
    private boolean isLocalHtmlSession = false;

    // ラムダ式イベントルーター
    private final Map<String, Runnable> exactActionHandlers = new HashMap<>();
    private final Map<String, Consumer<String>> prefixActionHandlers = new LinkedHashMap<>();

    // <textarea> 状態管理
    private boolean isEditingTextarea = false;
    private String activeTextareaBaseId = "";

    // <select> ドロップダウンポップアップ状態
    private String openSelectBaseId = "";
    private int selectPopupX, selectPopupY, selectPopupW;
    private final List<SelectOption> selectOptions = new ArrayList<>();
    private record SelectOption(String value, String label) {}

    // ドラッグ＆ドロップ状態
    private String draggingElementId = "";
    private String draggingLabel = "";

    // <mc-scene> 3D操作状態
    private String activeSceneId = "";
    private int activeSceneMouseButton = 0;

    // DevTools (F12 インスペクタ & F5 ホットリロード)
    protected boolean devToolsEnabled = false;
    protected ResourceLocation htmlResourceLocation = null;

    public AbstractWebScreen(Component title) {
        this(title, "local_screen_" + UUID.randomUUID(), 0, 0);
    }

    public AbstractWebScreen(Component title, ResourceLocation htmlResource) {
        this(title, "local_screen_" + UUID.randomUUID(), 0, 0);
        this.htmlResourceLocation = htmlResource;
    }

    public AbstractWebScreen(Component title, String sessionId) {
        this(title, sessionId, (int) DEFAULT_VIRTUAL_WIDTH, (int) DEFAULT_VIRTUAL_HEIGHT);
    }

    /**
     * virtualWidth または virtualHeight に 0 以下を指定すると、
     * 画面全体の比率・解像度に自動追従するフルスクリーン・レスポンシブモードとして動作する
     */
    public AbstractWebScreen(Component title, String sessionId, int virtualWidth, int virtualHeight) {
        super(title);
        this.sessionId = sessionId;
        this.state = new WebStateStore(sessionId);
        if (virtualWidth <= 0 || virtualHeight <= 0) {
            this.fullscreenResponsive = true;
            this.virtualWidth = (int) DEFAULT_VIRTUAL_WIDTH;
            this.virtualHeight = (int) DEFAULT_VIRTUAL_HEIGHT;
        } else {
            this.virtualWidth = virtualWidth;
            this.virtualHeight = virtualHeight;
        }
    }

    protected void setHtmlResource(ResourceLocation location) {
        this.htmlResourceLocation = location;
    }

    /**
     * リソースパックまたはMODのassetsからHTMLファイルを読み込む汎用ヘルパー
     */
    public static String loadHtmlResource(ResourceLocation loc) {
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
            if (res.isPresent()) {
                try (InputStream is = res.get().open()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}
        try (InputStream is = AbstractWebScreen.class.getResourceAsStream("/assets/" + loc.getNamespace() + "/" + loc.getPath())) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return "<html><body><div style='padding:20px;color:#f38ba8;'>Failed to load HTML resource: " + loc + "</div></body></html>";
    }

    @Override
    protected void init() {
        super.init();
        this.hiddenEditBox = new EditBox(this.font, -2000, -2000, 400, 20, Component.empty());
        this.hiddenEditBox.setMaxLength(2048);
        this.hiddenEditBox.setResponder(newText -> {
            if (suppressInputResponder) return;
            if (!WebRenderCore.activeInputId.isEmpty() && !isEditingTextarea) {
                String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId);
                syncInputValueToDomNodes(baseId, newText);
                this.state.notifyElementModelChanged(baseId, newText);
                ClientScreenInteractionManager.updateInputLocally(sessionId, WebRenderCore.activeInputId, newText);
                onInputChanged(baseId, newText);
                onWebInputChanged(baseId, newText);
            }
        });
        this.addWidget(this.hiddenEditBox);

        reloadWebUI();
    }

    public void reloadWebUI() {
        exactActionHandlers.clear();
        prefixActionHandlers.clear();
        this.state.clearBindings();
        registerWebActions();

        updateViewportTransform();

        String initialHtml = buildInitialHtml();
        if ((initialHtml == null || initialHtml.isEmpty()) && this.htmlResourceLocation != null) {
            initialHtml = loadHtmlResource(this.htmlResourceLocation);
        }

        if (initialHtml != null && !initialHtml.isEmpty()) {
            this.isLocalHtmlSession = true;
            ClientWebManager.loadLocalHtml(this.sessionId, initialHtml, this.virtualWidth, this.virtualHeight);
            this.state.forceSync();
        } else {
            ClientScreenInteractionManager.ensureSynced(sessionId);
            if (ClientWebManager.getLastRootW(sessionId) != virtualWidth || ClientWebManager.getLastRootH(sessionId) != virtualHeight) {
                ClientWebManager.recomputeLayout(sessionId, virtualWidth, virtualHeight);
            }
        }
    }

    protected String buildInitialHtml() {
        return null;
    }

    protected void registerWebActions() {}

    protected void onAction(String actionName, Runnable handler) {
        exactActionHandlers.put(actionName, handler);
    }

    protected void onActionPrefix(String prefix, Consumer<String> payloadHandler) {
        prefixActionHandlers.put(prefix, payloadHandler);
    }

    public void sendServerAction(String actionName) {
        sendServerAction(actionName, "");
    }

    public void sendServerAction(String actionName, Object value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("ElementId", "");
        tag.putString("EventName", "rpc");
        tag.putString("ActionName", actionName != null ? actionName : "");
        tag.putString("Value", value != null ? String.valueOf(value) : "");
        PacketDistributor.sendToServer(new WebActionC2SPacket(this.sessionId, "ui_event", tag));
    }

    public WebCanvasContext getCanvas(String canvasId) {
        return WebCanvasContext.getOrCreate(this.sessionId, canvasId);
    }

    public WebSceneContext getScene(String sceneId) {
        return WebSceneContext.getOrCreate(this.sessionId, sceneId);
    }

    protected void onInputChanged(String elementId, String newText) {}
    protected void onWebAction(String action, UIElement clickedElement, int mouseButton) {}
    protected void onWebInputChanged(String elementId, String newText) {}
    protected void onWebInputSubmitted(String elementId, String text) {}
    protected void onWebDrop(String draggedId, String dropTargetId, String dropAction) {}

    protected void updateViewportTransform() {
        if (this.fullscreenResponsive) {
            float aspect = (this.height > 0) ? ((float) this.width / (float) this.height) : (16.0f / 9.0f);
            // ウィンドウが半分(縦長)になっても仮想幅が 854px 未満に潰れないよう保護し、スケールで縮小フィットさせる
            int targetW = Math.max(854, Math.round(480 * aspect));
            int targetH = Math.max(480, Math.round(targetW / aspect));

            if (this.virtualWidth != targetW || this.virtualHeight != targetH) {
                this.virtualWidth = targetW;
                this.virtualHeight = targetH;
                if (ClientWebManager.hasDocument(sessionId)) {
                    ClientWebManager.recomputeLayout(sessionId, this.virtualWidth, this.virtualHeight);
                }
            }
        }

        float scaleX = (this.width * viewportMarginRatio) / virtualWidth;
        float scaleY = (this.height * viewportMarginRatio) / virtualHeight;
        this.guiScaleFactor = Math.min(scaleX, scaleY);
        this.offsetX = Math.round((this.width - virtualWidth * guiScaleFactor) / 2.0f);
        this.offsetY = Math.round((this.height - virtualHeight * guiScaleFactor) / 2.0f);
    }

    protected double toWebX(double mouseX) {
        return (mouseX - offsetX) / guiScaleFactor;
    }

    protected double toWebY(double mouseY) {
        return (mouseY - offsetY) / guiScaleFactor;
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        this.state.syncToDom();
        ClientWebManager.processRenderQueue();
        if (!isLocalHtmlSession) {
            ClientScreenInteractionManager.ensureSynced(sessionId);
        }
        updateViewportTransform();

        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);
        hoveredId = ClientScreenInteractionManager.handleHover(sessionId, webX, webY);

        List<UIElement> elements = ClientWebManager.getElements(sessionId);
        if (elements != null && !elements.isEmpty()) {
            PoseStack poseStack = guiGraphics.pose();
            poseStack.pushPose();
            poseStack.translate(offsetX, offsetY, 0);
            poseStack.scale(guiScaleFactor, guiScaleFactor, 1.0f);

            var bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
            int logicW = (int) Math.ceil(virtualWidth / WebRenderCore.RESOLUTION);
            int logicH = (int) Math.ceil(virtualHeight / WebRenderCore.RESOLUTION);

            WebRenderCore.renderElements(
                    elements, logicW, logicH,
                    poseStack, bufferSource,
                    0xF000F0, OverlayTexture.NO_OVERLAY,
                    true, true, true,
                    0.05f, 0.02f,
                    hoveredId,
                    0x00000000, false,
                    Minecraft.getInstance().level,
                    true, true, sessionId,
                    null, null, 0
            );
            bufferSource.endBatch();

            if (!openSelectBaseId.isEmpty() && !selectOptions.isEmpty()) {
                renderSelectDropdownOverlay(guiGraphics, (int) webX, (int) webY);
            }

            if (!draggingElementId.isEmpty()) {
                renderDragGhostOverlay(guiGraphics, (int) webX, (int) webY);
            }

            if (devToolsEnabled) {
                renderDevToolsInspector(guiGraphics, elements, (int) webX, (int) webY);
            }

            poseStack.popPose();
        }
    }

    private void renderSelectDropdownOverlay(GuiGraphics guiGraphics, int webX, int webY) {
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 50.0f);
        int itemH = 18;
        int totalH = selectOptions.size() * itemH + 2;
        guiGraphics.fill(selectPopupX, selectPopupY, selectPopupX + selectPopupW, selectPopupY + totalH, 0xFF494D6E);
        guiGraphics.fill(selectPopupX + 1, selectPopupY + 1, selectPopupX + selectPopupW - 1, selectPopupY + totalH - 1, 0xFF161622);

        String currentVal = getInputValue(openSelectBaseId);
        for (int i = 0; i < selectOptions.size(); i++) {
            SelectOption opt = selectOptions.get(i);
            int iy = selectPopupY + 1 + i * itemH;
            boolean hov = webX >= selectPopupX && webX <= selectPopupX + selectPopupW && webY >= iy && webY < iy + itemH;
            boolean sel = opt.value().equals(currentVal);
            if (hov) guiGraphics.fill(selectPopupX + 1, iy, selectPopupX + selectPopupW - 1, iy + itemH, 0xFF3E405C);
            else if (sel) guiGraphics.fill(selectPopupX + 1, iy, selectPopupX + selectPopupW - 1, iy + itemH, 0xFF283A5E);
            guiGraphics.drawString(this.font, opt.label(), selectPopupX + 6, iy + 5, sel ? 0xFF89B4FA : 0xFFCDD6F4, false);
        }
        pose.popPose();
    }

    private void renderDragGhostOverlay(GuiGraphics guiGraphics, int webX, int webY) {
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 60.0f);
        int w = Math.max(60, this.font.width(draggingLabel) + 12);
        guiGraphics.fill(webX + 6, webY + 6, webX + 6 + w, webY + 24, 0xAA89B4FA);
        guiGraphics.drawString(this.font, draggingLabel, webX + 12, webY + 11, 0xFF11111B, false);
        pose.popPose();
    }

    protected void renderDevToolsInspector(GuiGraphics guiGraphics, List<UIElement> elements, int webX, int webY) {
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 80.0f);

        String banner = " [F12] EnderFrame DevTools | [F5] Hot Reload | Viewport: " + virtualWidth + "x" + virtualHeight + " | Mouse: (" + webX + ", " + webY + ") ";
        guiGraphics.fill(0, 0, this.font.width(banner) + 8, 13, 0xDD11111B);
        guiGraphics.drawString(this.font, banner, 4, 2, 0xFFA6E3A1, false);

        UIElement targetAnchor = null;
        for (int i = elements.size() - 1; i >= 0; i--) {
            UIElement el = elements.get(i);
            if ("node".equals(el.type()) && !"html_root".equals(el.id()) && el.width() > 0 && el.height() > 0) {
                if (webX >= el.x() && webX <= el.x() + el.width() && webY >= el.y() && webY <= el.y() + el.height()) {
                    targetAnchor = el;
                    break;
                }
            }
        }

        if (targetAnchor != null) {
            HtmlNode vRoot = ClientWebManager.getVirtualRoot(sessionId);
            HtmlNode domNode = vRoot != null ? DomUtils.findNodeById(vRoot, targetAnchor.id()) : null;
            int x = targetAnchor.x(), y = targetAnchor.y(), w = targetAnchor.width(), h = targetAnchor.height();

            Map<String, String> st = (domNode != null && domNode.computedStyle != null) ? domNode.computedStyle : Map.of();
            String[] mEdges = CssParser.parseBoxEdgesStr(st.get("margin"));
            int mT = CssParser.parseDim(st.getOrDefault("margin-top", mEdges[0]), h, virtualWidth, virtualHeight);
            int mR = CssParser.parseDim(st.getOrDefault("margin-right", mEdges[1]), w, virtualWidth, virtualHeight);
            int mB = CssParser.parseDim(st.getOrDefault("margin-bottom", mEdges[2]), h, virtualWidth, virtualHeight);
            int mL = CssParser.parseDim(st.getOrDefault("margin-left", mEdges[3]), w, virtualWidth, virtualHeight);

            String[] pEdges = CssParser.parseBoxEdgesStr(st.get("padding"));
            int pT = CssParser.parseDim(st.getOrDefault("padding-top", pEdges[0]), h, virtualWidth, virtualHeight);
            int pR = CssParser.parseDim(st.getOrDefault("padding-right", pEdges[1]), w, virtualWidth, virtualHeight);
            int pB = CssParser.parseDim(st.getOrDefault("padding-bottom", pEdges[2]), h, virtualWidth, virtualHeight);
            int pL = CssParser.parseDim(st.getOrDefault("padding-left", pEdges[3]), w, virtualWidth, virtualHeight);

            if (mT > 0) guiGraphics.fill(x - mL, y - mT, x + w + mR, y, 0x44FAB387);
            if (mB > 0) guiGraphics.fill(x - mL, y + h, x + w + mR, y + h + mB, 0x44FAB387);
            if (mL > 0) guiGraphics.fill(x - mL, y, x, y + h, 0x44FAB387);
            if (mR > 0) guiGraphics.fill(x + w, y, x + w + mR, y + h, 0x44FAB387);

            if (pT > 0) guiGraphics.fill(x, y, x + w, y + pT, 0x44A6E3A1);
            if (pB > 0) guiGraphics.fill(x, y + h - pB, x + w, y + h, 0x44A6E3A1);
            if (pL > 0) guiGraphics.fill(x, y + pT, x + pL, y + h - pB, 0x44A6E3A1);
            if (pR > 0) guiGraphics.fill(x + w - pR, y + pT, x + w, y + h - pB, 0x44A6E3A1);

            guiGraphics.fill(x + pL, y + pT, x + w - pR, y + h - pB, 0x3389B4FA);
            guiGraphics.renderOutline(x, y, w, h, 0xFF89B4FA);

            List<String> infoLines = new ArrayList<>();
            String tagLabel = domNode != null ? "<" + domNode.tag + ">" : "<node>";
            String idLabel = (domNode != null && !domNode.id.isEmpty()) ? "#" + domNode.id : "#" + targetAnchor.id();
            String clsLabel = (domNode != null && !domNode.classes.isEmpty()) ? "." + String.join(".", domNode.classes) : "";
            infoLines.add("§b" + tagLabel + " §e" + idLabel + "§a" + clsLabel);
            infoLines.add("§7Rect: §f" + w + "x" + h + " §7at (" + x + ", " + y + ")");
            if (!st.isEmpty()) {
                infoLines.add("§7display: §f" + st.getOrDefault("display", "block")
                        + (st.containsKey("flex-direction") ? " (" + st.get("flex-direction") + ")" : ""));
            }
            if (domNode != null) {
                if (domNode.attrs.containsKey("data-model")) infoLines.add("§ddata-model: §f" + domNode.attrs.get("data-model"));
                if (domNode.attrs.containsKey("data-bind")) infoLines.add("§ddata-bind: §f" + domNode.attrs.get("data-bind"));
                if (domNode.attrs.containsKey("onclick")) infoLines.add("§conclick: §f" + domNode.attrs.get("onclick"));
            }

            int cardW = 0;
            for (String line : infoLines) cardW = Math.max(cardW, this.font.width(line) + 10);
            int cardH = infoLines.size() * 11 + 6;
            int cardX = Math.clamp(x, 4, Math.max(4, virtualWidth - cardW - 4));
            int cardY = (y - cardH - 4 >= 16) ? (y - cardH - 4) : Math.min(virtualHeight - cardH - 4, y + h + 4);

            guiGraphics.fill(cardX - 1, cardY - 1, cardX + cardW + 1, cardY + cardH + 1, 0xFF89B4FA);
            guiGraphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0xEE181825);
            for (int i = 0; i < infoLines.size(); i++) {
                guiGraphics.drawString(this.font, infoLines.get(i), cardX + 5, cardY + 4 + i * 11, 0xFFFFFFFF, false);
            }
        }

        pose.popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);

        // 1. 開いている <select> ドロップダウンのクリック判定 (最優先)
        if (!openSelectBaseId.isEmpty() && !selectOptions.isEmpty() && button == 0) {
            int itemH = 18;
            int totalH = selectOptions.size() * itemH + 2;
            if (webX >= selectPopupX && webX <= selectPopupX + selectPopupW && webY >= selectPopupY && webY <= selectPopupY + totalH) {
                int idx = (int) ((webY - selectPopupY - 1) / itemH);
                if (idx >= 0 && idx < selectOptions.size()) {
                    String chosenVal = selectOptions.get(idx).value();
                    ClientWebManager.updateNodeAttributeLocally(sessionId, openSelectBaseId, "value", chosenVal, false);
                    ClientWebManager.processRenderQueue();
                    this.state.notifyElementModelChanged(openSelectBaseId, chosenVal);
                    onWebInputChanged(openSelectBaseId, chosenVal);
                }
                openSelectBaseId = "";
                selectOptions.clear();
                return true;
            }
            openSelectBaseId = "";
            selectOptions.clear();
        }

        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        UIElement clicked = hit != null ? hit.element() : null;

        if (clicked != null && "scene".equals(clicked.type())) {
            unfocusWebInput();
            String baseId = ClientWebManager.getBaseNodeId(clicked.id());
            if (button == 2) {
                getScene(baseId).resetCamera();
            } else {
                activeSceneId = baseId;
                activeSceneMouseButton = button;
            }
            return true;
        }

        if (clicked != null && "textarea".equals(clicked.type()) && button == 0) {
            unfocusWebInput();
            focusWebTextarea(clicked, hit.localX(), hit.localY());
            return true;
        }

        if (clicked != null && "input".equals(clicked.type()) && button == 0) {
            String baseId = ClientWebManager.getBaseNodeId(clicked.id());
            String curVal = getInputValue(baseId);
            if (curVal.isEmpty() && clicked.text() != null) curVal = clicked.text();
            focusWebInput(baseId, curVal);
            return true;
        } else {
            unfocusWebInput();
        }

        if (clicked != null) {
            String baseId = ClientWebManager.getBaseNodeId(clicked.id());
            WebRenderCore.activePressedId = clicked.id();

            if ("select".equals(clicked.type()) && button == 0) {
                ClientScreenInteractionManager.playElementSound(clicked);
                openSelectDropdown(baseId, clicked);
                return true;
            }

            if (("range".equals(clicked.type()) || "toggle".equals(clicked.type()) || "checkbox".equals(clicked.type())) && button == 0) {
                ClientScreenInteractionManager.handleClick(sessionId, webX, webY);
                if ("range".equals(clicked.type())) {
                    UIElement updated = ClientWebManager.getElementById(sessionId, baseId);
                    if (updated != null && updated.text() != null) {
                        String val = updated.text().split(":")[0];
                        this.state.notifyElementModelChanged(baseId, val);
                        onWebInputChanged(baseId, val);
                    }
                } else {
                    HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
                    String checkedStr = Boolean.toString(node != null && node.attrs.containsKey("checked"));
                    this.state.notifyElementModelChanged(baseId, checkedStr);
                    onWebInputChanged(baseId, checkedStr);
                }
                return true;
            }

            if (button == 0 && clicked.events() != null && "true".equalsIgnoreCase(clicked.events().get("draggable"))) {
                draggingElementId = baseId;
                HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
                draggingLabel = (node != null && !node.getTextContent().isEmpty()) ? node.getTextContent().trim() : baseId;
            }

            ClientScreenInteractionManager.handleClick(sessionId, webX, webY);
            String action = clicked.events() != null ? clicked.events().get("onclick") : null;
            if (action != null) {
                if (button == 0 && dispatchRoutedAction(action)) {
                    return true;
                }
                onWebAction(action, clicked, button);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    protected boolean dispatchRoutedAction(String action) {
        if (action.startsWith("server:")) {
            String rest = action.substring(7);
            if (rest.contains(":")) {
                String[] sp = rest.split(":", 2);
                sendServerAction(sp[0], sp[1]);
            } else {
                sendServerAction(rest, "");
            }
            return true;
        }
        if (exactActionHandlers.containsKey(action)) {
            exactActionHandlers.get(action).run();
            return true;
        }
        for (Map.Entry<String, Consumer<String>> entry : prefixActionHandlers.entrySet()) {
            if (action.startsWith(entry.getKey())) {
                entry.getValue().accept(action.substring(entry.getKey().length()));
                return true;
            }
        }
        return false;
    }

    private void openSelectDropdown(String baseId, UIElement selectEl) {
        HtmlNode selectNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
        if (selectNode == null) return;
        selectOptions.clear();
        for (HtmlNode child : selectNode.children) {
            if ("option".equalsIgnoreCase(child.tag)) {
                String val = child.attrs.getOrDefault("value", child.getTextContent().trim());
                String label = child.getTextContent().trim();
                selectOptions.add(new SelectOption(val, label.isEmpty() ? val : label));
            }
        }
        if (!selectOptions.isEmpty()) {
            openSelectBaseId = baseId;
            selectPopupX = selectEl.x();
            selectPopupY = selectEl.y() + selectEl.height() + 2;
            selectPopupW = Math.max(selectEl.width(), 90);
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!activeSceneId.isEmpty()) {
            WebSceneContext scene = getScene(activeSceneId);
            if (activeSceneMouseButton == 0) {
                scene.addRotation((float) (dragY * 0.8), (float) (dragX * 0.8));
            } else if (activeSceneMouseButton == 1) {
                scene.addPan((float) (dragX / guiScaleFactor), (float) (dragY / guiScaleFactor));
            }
            return true;
        }

        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);
        if (ClientScreenInteractionManager.handleDrag(sessionId, webX, webY)) {
            if (!WebRenderCore.activePressedId.isEmpty()) {
                String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activePressedId);
                UIElement updated = ClientWebManager.getElementById(sessionId, baseId);
                if (updated != null && "range".equals(updated.type()) && updated.text() != null) {
                    String val = updated.text().split(":")[0];
                    this.state.notifyElementModelChanged(baseId, val);
                    onWebInputChanged(baseId, val);
                }
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        activeSceneId = "";
        WebRenderCore.activePressedId = "";
        ClientScreenInteractionManager.handleRelease(sessionId);

        if (!draggingElementId.isEmpty()) {
            double webX = toWebX(mouseX);
            double webY = toWebY(mouseY);
            ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
            if (hit != null && hit.element() != null) {
                String targetBaseId = ClientWebManager.getBaseNodeId(hit.element().id());
                String dropAction = hit.element().events() != null ? hit.element().events().getOrDefault("ondrop", "") : "";
                if (!targetBaseId.equals(draggingElementId)) {
                    onWebDrop(draggingElementId, targetBaseId, dropAction);
                }
            }
            draggingElementId = "";
            draggingLabel = "";
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);

        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        if (hit != null && hit.element() != null && "textarea".equals(hit.element().type())) {
            String taId = hit.element().id();
            int curScroll = WebRenderCore.textareaScrollRow.getOrDefault(taId, 0);
            int nextScroll = Math.max(0, curScroll - (int) Math.signum(scrollY) * 3);
            WebRenderCore.textareaScrollRow.put(taId, nextScroll);
            return true;
        }

        if (hit != null && hit.element() != null && "scene".equals(hit.element().type())) {
            String baseId = ClientWebManager.getBaseNodeId(hit.element().id());
            getScene(baseId).addZoom((float) (scrollY * 0.12));
            return true;
        }

        if (ClientScreenInteractionManager.handleScroll(sessionId, webX, webY, scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F12) {
            this.devToolsEnabled = !this.devToolsEnabled;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F5) {
            reloadWebUI();
            return true;
        }

        if (isEditingTextarea && !WebRenderCore.activeInputId.isEmpty()) {
            if (handleTextareaKeyPressed(keyCode, modifiers)) return true;
        } else if (!WebRenderCore.activeInputId.isEmpty()) {
            String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId);
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                String val = this.hiddenEditBox.getValue();
                ClientScreenInteractionManager.submitInput(sessionId, WebRenderCore.activeInputId, val);
                onWebInputSubmitted(baseId, val);
                return true;
            }
            if (this.hiddenEditBox.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (isEditingTextarea && !WebRenderCore.activeInputId.isEmpty()) {
            if (codePoint >= 32 && codePoint != 127) {
                insertIntoTextarea(String.valueOf(codePoint));
                return true;
            }
        } else if (!WebRenderCore.activeInputId.isEmpty()) {
            if (this.hiddenEditBox.charTyped(codePoint, modifiers)) return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void focusWebTextarea(UIElement taEl, double localX, double localY) {
        this.isEditingTextarea = true;
        this.activeTextareaBaseId = ClientWebManager.getBaseNodeId(taEl.id());
        WebRenderCore.activeInputId = taEl.id();
        if (this.hiddenEditBox != null) this.hiddenEditBox.setFocused(false);

        String fullText = getTextareaValue(activeTextareaBaseId);
        String[] lines = fullText.split("\n", -1);
        int scrollRow = WebRenderCore.textareaScrollRow.getOrDefault(taEl.id(), 0);
        int clickedRow = Math.clamp(scrollRow + (int) ((localY - 4.0) / 11.0), 0, Math.max(0, lines.length - 1));

        String syntax = taEl.events() != null ? taEl.events().getOrDefault("data-syntax", "") : "";
        boolean showLineNums = "lua".equalsIgnoreCase(syntax) || (taEl.events() != null && "true".equalsIgnoreCase(taEl.events().get("data-line-numbers")));
        float gutterW = showLineNums ? 28.0f : 4.0f;

        String lineStr = lines[clickedRow];
        int clickedCol = font.plainSubstrByWidth(lineStr, Math.max(0, (int) (localX - gutterW))).length();

        int index = 0;
        for (int i = 0; i < clickedRow; i++) index += lines[i].length() + 1;
        index += Math.min(clickedCol, lineStr.length());
        WebRenderCore.textareaCursorIndex.put(taEl.id(), index);
    }

    private boolean handleTextareaKeyPressed(int keyCode, int modifiers) {
        String renderId = WebRenderCore.activeInputId;
        String text = getTextareaValue(activeTextareaBaseId);
        int cursor = Math.clamp(WebRenderCore.textareaCursorIndex.getOrDefault(renderId, text.length()), 0, text.length());

        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                unfocusWebInput();
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (cursor > 0) WebRenderCore.textareaCursorIndex.put(renderId, cursor - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (cursor < text.length()) WebRenderCore.textareaCursorIndex.put(renderId, cursor + 1);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                int lineStart = text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
                WebRenderCore.textareaCursorIndex.put(renderId, lineStart);
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                int lineEnd = text.indexOf('\n', cursor);
                WebRenderCore.textareaCursorIndex.put(renderId, lineEnd == -1 ? text.length() : lineEnd);
                return true;
            }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                String[] lines = text.split("\n", -1);
                int row = 0, col = 0, acc = 0;
                for (int i = 0; i < lines.length; i++) {
                    if (cursor <= acc + lines[i].length()) { row = i; col = cursor - acc; break; }
                    acc += lines[i].length() + 1;
                }
                int targetRow = keyCode == GLFW.GLFW_KEY_UP ? row - 1 : row + 1;
                if (targetRow >= 0 && targetRow < lines.length) {
                    int newCursor = 0;
                    for (int i = 0; i < targetRow; i++) newCursor += lines[i].length() + 1;
                    newCursor += Math.min(col, lines[targetRow].length());
                    WebRenderCore.textareaCursorIndex.put(renderId, newCursor);
                }
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                int lineStart = text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
                String currentLine = text.substring(lineStart, cursor);
                int indentLen = 0;
                while (indentLen < currentLine.length() && currentLine.charAt(indentLen) == ' ') indentLen++;
                insertIntoTextarea("\n" + " ".repeat(indentLen));
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                insertIntoTextarea("  ");
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (cursor > 0) {
                    String next = text.substring(0, cursor - 1) + text.substring(cursor);
                    WebRenderCore.textareaCursorIndex.put(renderId, cursor - 1);
                    setTextareaValue(activeTextareaBaseId, next);
                    this.state.notifyElementModelChanged(activeTextareaBaseId, next);
                    onWebInputChanged(activeTextareaBaseId, next);
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (cursor < text.length()) {
                    String next = text.substring(0, cursor) + text.substring(cursor + 1);
                    setTextareaValue(activeTextareaBaseId, next);
                    this.state.notifyElementModelChanged(activeTextareaBaseId, next);
                    onWebInputChanged(activeTextareaBaseId, next);
                }
                return true;
            }
            case GLFW.GLFW_KEY_V -> {
                if (Screen.hasControlDown()) {
                    String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                    if (clip != null && !clip.isEmpty()) {
                        insertIntoTextarea(clip.replace("\r\n", "\n"));
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private void insertIntoTextarea(String insertion) {
        String renderId = WebRenderCore.activeInputId;
        String text = getTextareaValue(activeTextareaBaseId);
        int cursor = Math.clamp(WebRenderCore.textareaCursorIndex.getOrDefault(renderId, text.length()), 0, text.length());
        String next = text.substring(0, cursor) + insertion + text.substring(cursor);
        WebRenderCore.textareaCursorIndex.put(renderId, cursor + insertion.length());
        setTextareaValue(activeTextareaBaseId, next);
        this.state.notifyElementModelChanged(activeTextareaBaseId, next);
        onWebInputChanged(activeTextareaBaseId, next);
    }

    public void setTextareaValue(String id, String value) {
        String safeVal = value != null ? value.replace("\r\n", "\n") : "";
        String baseId = ClientWebManager.getBaseNodeId(id);
        HtmlNode vNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
        if (vNode != null) vNode.attrs.put("value", safeVal);
        HtmlNode tNode = DomUtils.findNodeById(ClientWebManager.getTemplateRoot(sessionId), baseId);
        if (tNode != null) tNode.attrs.put("value", safeVal);
        ClientWebManager.updateElementTextLocally(sessionId, baseId + "_textarea", safeVal);
    }

    public String getTextareaValue(String id) {
        String baseId = ClientWebManager.getBaseNodeId(id);
        HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
        if (node == null) return "";
        return node.attrs.getOrDefault("value", node.getTextContent());
    }

    private void syncInputValueToDomNodes(String baseId, String safeVal) {
        HtmlNode vNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
        if (vNode != null) vNode.attrs.put("value", safeVal);
        HtmlNode tNode = DomUtils.findNodeById(ClientWebManager.getTemplateRoot(sessionId), baseId);
        if (tNode != null) tNode.attrs.put("value", safeVal);
        ClientWebManager.updateElementTextLocally(sessionId, baseId + "_input", safeVal);
    }

    public void focusWebInput(String inputElementId, String currentText) {
        this.isEditingTextarea = false;
        this.activeTextareaBaseId = "";
        String baseId = ClientWebManager.getBaseNodeId(inputElementId);
        String renderInputId = baseId + "_input";
        String safeVal = currentText != null ? currentText : "";

        this.suppressInputResponder = true;
        try {
            WebRenderCore.activeInputId = renderInputId;
            syncInputValueToDomNodes(baseId, safeVal);
            if (this.hiddenEditBox != null) {
                this.hiddenEditBox.setValue(safeVal);
                this.hiddenEditBox.moveCursorToEnd(false);
                this.setFocused(this.hiddenEditBox);
                this.hiddenEditBox.setFocused(true);
            }
        } finally {
            this.suppressInputResponder = false;
        }
    }

    public void unfocusWebInput() {
        this.isEditingTextarea = false;
        this.activeTextareaBaseId = "";
        if (!WebRenderCore.activeInputId.isEmpty()) {
            String activeId = WebRenderCore.activeInputId;
            String baseId = ClientWebManager.getBaseNodeId(activeId);
            String finalVal = this.hiddenEditBox != null ? this.hiddenEditBox.getValue() : "";
            syncInputValueToDomNodes(baseId, finalVal);
            ClientScreenInteractionManager.submitInput(sessionId, activeId, finalVal);
            WebRenderCore.activeInputId = "";
            if (this.hiddenEditBox != null) this.hiddenEditBox.setFocused(false);
        }
    }

    public void setElementText(String id, String text) {
        HtmlNode root = ClientWebManager.getVirtualRoot(sessionId);
        if (root == null) return;
        HtmlNode target = DomUtils.findNodeById(root, id);
        if (target != null) {
            target.setTextContent(text);
            HtmlNode tNode = DomUtils.findNodeById(ClientWebManager.getTemplateRoot(sessionId), id);
            if (tNode != null) tNode.setTextContent(text);
            ClientWebManager.requestRender(sessionId);
        }
    }

    public void setInputValue(String id, String value) {
        String baseId = ClientWebManager.getBaseNodeId(id);
        String safeVal = value != null ? value : "";
        syncInputValueToDomNodes(baseId, safeVal);
        if (!WebRenderCore.activeInputId.isEmpty() && ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId).equals(baseId)) {
            if (this.hiddenEditBox != null && !Objects.equals(this.hiddenEditBox.getValue(), safeVal)) {
                this.suppressInputResponder = true;
                try {
                    this.hiddenEditBox.setValue(safeVal);
                } finally {
                    this.suppressInputResponder = false;
                }
            }
        }
    }

    public String getInputValue(String id) {
        String baseId = ClientWebManager.getBaseNodeId(id);
        if (!WebRenderCore.activeInputId.isEmpty() && ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId).equals(baseId)) {
            if (this.hiddenEditBox != null) return this.hiddenEditBox.getValue();
        }
        HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
        return node != null ? node.attrs.getOrDefault("value", "") : "";
    }

    public void setInnerHtml(String id, String html) {
        ClientWebManager.updateNodeInnerHtmlLocally(sessionId, id, html);
    }

    public void setStyle(String id, String prop, String val) {
        ClientWebManager.updateNodeStyleLocally(sessionId, id, prop, val);
    }

    public void setAttribute(String id, String attr, String val) {
        ClientWebManager.updateNodeAttributeLocally(sessionId, id, attr, val, false);
    }

    public void addClass(String id, String cls) {
        HtmlNode root = ClientWebManager.getVirtualRoot(sessionId);
        if (root == null) return;
        HtmlNode target = DomUtils.findNodeById(root, id);
        if (target != null && !target.classes.contains(cls)) {
            target.addClass(cls);
            ClientWebManager.requestRender(sessionId);
        }
    }

    public void removeClass(String id, String cls) {
        HtmlNode root = ClientWebManager.getVirtualRoot(sessionId);
        if (root == null) return;
        HtmlNode target = DomUtils.findNodeById(root, id);
        if (target != null && target.classes.contains(cls)) {
            target.removeClass(cls);
            ClientWebManager.requestRender(sessionId);
        }
    }

    public void toggleClass(String id, String cls, boolean enable) {
        if (enable) addClass(id, cls);
        else removeClass(id, cls);
    }

    @Override
    public void onClose() {
        unfocusWebInput();
        activeSceneId = "";
        WebRenderCore.activePressedId = "";
        if (isLocalHtmlSession) {
            WebCanvasContext.clearSession(this.sessionId);
            WebSceneContext.clearSession(this.sessionId);
            ClientWebManager.clearSession(this.sessionId);
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}