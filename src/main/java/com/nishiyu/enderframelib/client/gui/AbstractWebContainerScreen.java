package com.nishiyu.enderframelib.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.binding.WebStateStore;
import com.nishiyu.enderframelib.client.renderer.WebCanvasContext;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

/**
 * WebStateStore、ラムダ式イベントルーター、<textarea> マルチライン編集、<canvas>、
 * <select> ドロップダウン、ドラッグ＆ドロップをすべて標準内蔵した汎用コンテナ画面基底クラス
 */
public abstract class AbstractWebContainerScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {

    protected final String sessionId;
    public final WebStateStore state;

    protected int virtualWidth;
    protected int virtualHeight;
    protected boolean fullscreenResponsive;
    protected float viewportMarginRatio = 0.94f;
    protected float guiScaleFactor = 1.0f;
    protected float offsetX = 0.0f;
    protected float offsetY = 0.0f;
    protected EditBox hiddenEditBox;
    protected String hoveredId = "";

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

    public AbstractWebContainerScreen(T menu, Inventory playerInventory, Component title, String sessionId) {
        this(menu, playerInventory, title, sessionId, 0, 0);
    }

    public AbstractWebContainerScreen(T menu, Inventory playerInventory, Component title, String sessionId, int virtualWidth, int virtualHeight) {
        super(menu, playerInventory, title);
        this.sessionId = sessionId;
        this.state = new WebStateStore(sessionId);
        if (virtualWidth <= 0 || virtualHeight <= 0) {
            this.fullscreenResponsive = true;
            this.virtualWidth = 854;
            this.virtualHeight = 480;
            this.imageWidth = 854;
            this.imageHeight = 480;
        } else {
            this.fullscreenResponsive = false;
            this.virtualWidth = virtualWidth;
            this.virtualHeight = virtualHeight;
            this.imageWidth = virtualWidth;
            this.imageHeight = virtualHeight;
        }
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 10000;
        this.inventoryLabelY = 10000;

        this.hiddenEditBox = new EditBox(this.font, -2000, -2000, 400, 20, Component.empty());
        this.hiddenEditBox.setMaxLength(2048);
        this.hiddenEditBox.setResponder(newText -> {
            if (!WebRenderCore.activeInputId.isEmpty() && !isEditingTextarea) {
                String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId);
                ClientWebManager.updateElementTextLocally(sessionId, WebRenderCore.activeInputId, newText);
                ClientWebManager.updateNodeAttributeLocally(sessionId, baseId, "value", newText, false);
                onWebInputChanged(baseId, newText);
            }
        });
        this.addWidget(this.hiddenEditBox);

        exactActionHandlers.clear();
        prefixActionHandlers.clear();
        registerWebActions();

        updateViewportTransform();
        String initialHtml = buildInitialHtml();
        if (initialHtml != null && !initialHtml.isEmpty()) {
            ClientWebManager.loadLocalHtml(this.sessionId, initialHtml, this.virtualWidth, this.virtualHeight);
            this.state.forceSync();
        }
    }

    protected abstract String buildInitialHtml();

    /**
     * onAction(...) や onActionPrefix(...) を登録するための初期化フック
     */
    protected void registerWebActions() {}

    protected void onAction(String actionName, Runnable handler) {
        exactActionHandlers.put(actionName, handler);
    }

    protected void onActionPrefix(String prefix, Consumer<String> payloadHandler) {
        prefixActionHandlers.put(prefix, payloadHandler);
    }

    public WebCanvasContext getCanvas(String canvasId) {
        return WebCanvasContext.getOrCreate(this.sessionId, canvasId);
    }

    protected void onWebAction(String action, UIElement clickedElement, int mouseButton) {}
    protected void onWebInputChanged(String elementId, String newText) {}
    protected void onWebInputSubmitted(String elementId, String text) {}
    protected void onWebDrop(String draggedId, String dropTargetId, String dropAction) {}

    protected void updateViewportTransform() {
        if (this.fullscreenResponsive) {
            int targetH = 480;
            float aspect = (this.height > 0) ? ((float) this.width / (float) this.height) : (16.0f / 9.0f);
            int targetW = Math.max(320, Math.round(targetH * aspect));

            if (this.virtualWidth != targetW || this.virtualHeight != targetH) {
                this.virtualWidth = targetW;
                this.virtualHeight = targetH;
                if (ClientWebManager.hasDocument(sessionId)) {
                    ClientWebManager.recomputeLayout(sessionId, this.virtualWidth, this.virtualHeight);
                }
            }

            float scaleX = (this.width * viewportMarginRatio) / virtualWidth;
            float scaleY = (this.height * viewportMarginRatio) / virtualHeight;
            this.guiScaleFactor = Math.min(scaleX, scaleY);
            this.offsetX = Math.round((this.width - virtualWidth * guiScaleFactor) / 2.0f);
            this.offsetY = Math.round((this.height - virtualHeight * guiScaleFactor) / 2.0f);
            this.leftPos = (int) this.offsetX;
            this.topPos = (int) this.offsetY;
        } else {
            this.leftPos = (this.width - this.imageWidth) / 2;
            this.topPos = (this.height - this.imageHeight) / 2;
            this.guiScaleFactor = (float) this.imageWidth / (float) this.virtualWidth;
            this.offsetX = this.leftPos;
            this.offsetY = this.topPos;
        }
    }

    protected double toWebX(double mouseX) {
        return (mouseX - offsetX) / guiScaleFactor;
    }

    protected double toWebY(double mouseY) {
        return (mouseY - offsetY) / guiScaleFactor;
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        this.state.syncToDom();
        ClientWebManager.processRenderQueue();
        updateViewportTransform();

        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);

        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        String newHover = (hit != null && hit.element() != null) ? ClientWebManager.getBaseNodeId(hit.element().id()) : "";
        if (!Objects.equals(hoveredId, newHover)) {
            if (!hoveredId.isEmpty()) ClientWebManager.updateNodeAttributeLocally(sessionId, hoveredId, "hover", "", true);
            if (!newHover.isEmpty()) ClientWebManager.updateNodeAttributeLocally(sessionId, newHover, "hover", "true", false);
            hoveredId = newHover;
        }

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
                    false, true, sessionId,
                    null, null, 0
            );
            bufferSource.endBatch();

            // <select> ドロップダウンポップアップの描画
            if (!openSelectBaseId.isEmpty() && !selectOptions.isEmpty()) {
                renderSelectDropdownOverlay(guiGraphics, (int) webX, (int) webY);
            }

            // ドラッグ＆ドロップ中のゴースト描画
            if (!draggingElementId.isEmpty()) {
                renderDragGhostOverlay(guiGraphics, (int) webX, (int) webY);
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

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {}

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);

        // 1. <select> ドロップダウンが開いている場合のクリック判定
        if (!openSelectBaseId.isEmpty() && !selectOptions.isEmpty() && button == 0) {
            int itemH = 18;
            int totalH = selectOptions.size() * itemH + 2;
            if (webX >= selectPopupX && webX <= selectPopupX + selectPopupW && webY >= selectPopupY && webY <= selectPopupY + totalH) {
                int idx = (int) ((webY - selectPopupY - 1) / itemH);
                if (idx >= 0 && idx < selectOptions.size()) {
                    String chosenVal = selectOptions.get(idx).value();
                    ClientWebManager.updateNodeAttributeLocally(sessionId, openSelectBaseId, "value", chosenVal, false);
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

        // 2. <textarea> のクリック＆カーソル位置計算
        if (clicked != null && "textarea".equals(clicked.type()) && button == 0) {
            focusWebTextarea(clicked, hit.localX(), hit.localY());
            return true;
        }

        // 3. 1行 <input> のクリック
        if (clicked != null && "input".equals(clicked.type()) && button == 0) {
            isEditingTextarea = false;
            focusWebInput(clicked.id(), clicked.text() != null ? clicked.text() : "");
            return true;
        } else {
            unfocusWebInput();
        }

        if (clicked != null) {
            String baseId = ClientWebManager.getBaseNodeId(clicked.id());
            WebRenderCore.activePressedId = clicked.id();

            // 4. <select> のドロップダウン展開
            if ("select".equals(clicked.type()) && button == 0) {
                openSelectDropdown(baseId, clicked);
                return true;
            }

            // 5. <input type="range"> または 체크ボックスの標準インタラクション委譲
            if (("range".equals(clicked.type()) || "toggle".equals(clicked.type()) || "checkbox".equals(clicked.type())) && button == 0) {
                ClientScreenInteractionManager.handleClick(sessionId, webX, webY);
                if ("range".equals(clicked.type())) {
                    UIElement updated = ClientWebManager.getElementById(sessionId, baseId);
                    if (updated != null && updated.text() != null) {
                        onWebInputChanged(baseId, updated.text().split(":")[0]);
                    }
                }
                return true;
            }

            // 6. draggable="true" 要素のドラッグ開始
            if (button == 0 && clicked.events() != null && "true".equalsIgnoreCase(clicked.events().get("draggable"))) {
                draggingElementId = baseId;
                HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
                draggingLabel = (node != null && !node.getTextContent().isEmpty()) ? node.getTextContent().trim() : baseId;
            }

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
        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);
        if (ClientScreenInteractionManager.handleDrag(sessionId, webX, webY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
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

        // <textarea> 上でのホイールスクロール
        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        if (hit != null && hit.element() != null && "textarea".equals(hit.element().type())) {
            String taId = hit.element().id();
            int curScroll = WebRenderCore.textareaScrollRow.getOrDefault(taId, 0);
            int nextScroll = Math.max(0, curScroll - (int) Math.signum(scrollY) * 3);
            WebRenderCore.textareaScrollRow.put(taId, nextScroll);
            return true;
        }

        if (ClientWebManager.handleScrollLocally(sessionId, webX, webY, scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isEditingTextarea && !WebRenderCore.activeInputId.isEmpty()) {
            if (handleTextareaKeyPressed(keyCode, modifiers)) return true;
            if (Objects.requireNonNull(this.minecraft).options.keyInventory.matches(keyCode, scanCode)) return true;
        } else if (!WebRenderCore.activeInputId.isEmpty()) {
            String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId);
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                onWebInputSubmitted(baseId, this.hiddenEditBox.getValue());
                return true;
            }
            if (this.hiddenEditBox.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (Objects.requireNonNull(this.minecraft).options.keyInventory.matches(keyCode, scanCode)) return true;
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

    // ==========================================
    // <textarea> マルチライン編集エンジン
    // ==========================================
    private void focusWebTextarea(UIElement taEl, double localX, double localY) {
        this.isEditingTextarea = true;
        this.activeTextareaBaseId = ClientWebManager.getBaseNodeId(taEl.id());
        WebRenderCore.activeInputId = taEl.id();

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
                    onWebInputChanged(activeTextareaBaseId, next);
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (cursor < text.length()) {
                    String next = text.substring(0, cursor) + text.substring(cursor + 1);
                    setTextareaValue(activeTextareaBaseId, next);
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
        onWebInputChanged(activeTextareaBaseId, next);
    }

    public void setTextareaValue(String id, String value) {
        String safeVal = value != null ? value.replace("\r\n", "\n") : "";
        ClientWebManager.updateNodeAttributeLocally(sessionId, id, "value", safeVal, false);
        ClientWebManager.updateElementTextLocally(sessionId, id + "_textarea", safeVal);
    }

    public String getTextareaValue(String id) {
        HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), id);
        if (node == null) return "";
        return node.attrs.getOrDefault("value", node.getTextContent());
    }

    public void focusWebInput(String inputElementId, String currentText) {
        this.isEditingTextarea = false;
        String renderInputId = inputElementId.endsWith("_input") ? inputElementId : inputElementId + "_input";
        WebRenderCore.activeInputId = renderInputId;
        this.hiddenEditBox.setValue(currentText != null ? currentText : "");
        this.setFocused(this.hiddenEditBox);
        this.hiddenEditBox.setFocused(true);
    }

    public void unfocusWebInput() {
        this.isEditingTextarea = false;
        this.activeTextareaBaseId = "";
        if (!WebRenderCore.activeInputId.isEmpty()) {
            WebRenderCore.activeInputId = "";
            if (this.hiddenEditBox != null) this.hiddenEditBox.setFocused(false);
        }
    }

    // ==========================================
    // DOM 操作ショートカットメソッド群
    // ==========================================
    public void setElementText(String id, String text) {
        HtmlNode root = ClientWebManager.getVirtualRoot(sessionId);
        if (root == null) return;
        HtmlNode target = DomUtils.findNodeById(root, id);
        if (target != null) {
            target.setTextContent(text);
            ClientWebManager.requestRender(sessionId);
        }
    }

    public void setInputValue(String id, String value) {
        String safeVal = value != null ? value : "";
        ClientWebManager.updateNodeAttributeLocally(sessionId, id, "value", safeVal, false);
        ClientWebManager.updateElementTextLocally(sessionId, id + "_input", safeVal);
    }

    public String getInputValue(String id) {
        HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), id);
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
        WebRenderCore.activePressedId = "";
        ClientWebManager.clearSession(this.sessionId);
        super.onClose();
    }
}