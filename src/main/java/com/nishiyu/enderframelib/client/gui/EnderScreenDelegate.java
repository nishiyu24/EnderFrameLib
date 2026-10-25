package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.common.EnderFrameBuiltInConfig;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.client.renderer.WebFontManager;
import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.client.ClientDomEventDispatcher;
import com.nishiyu.enderframelib.client.gui.WebTextInputHost;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.SessionInput;
import com.nishiyu.enderframelib.client.gui.TextareaEditor.TextEditState;
import com.nishiyu.enderframelib.client.ReactiveStateStore;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.text.model.TextSelection;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import com.nishiyu.enderframelib.network.WebActionC2SPacket;
import com.nishiyu.enderframelib.network.UiActionC2SPacket;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

public class EnderScreenDelegate implements SessionInput.Host {

    private final String sessionId;
    private final ReactiveStateStore state;
    private final WebViewport viewport;
    private final WebActionRouter router;
    private final WebSelectDropdownWidget selectWidget;
    private final WebDragDropController dragDropController;
    private final WebFocusNavigator focusNavigator;
    private final TextareaEditor textareaEditor;

    private String activeSceneId = "";
    private int activeSceneMouseButton = 0;

    private WebTextInputHost inputHost;
    private final ClientScreenInteractionManager.IClientEventCallback eventCallback;

    /** 押下から解放まで有効な、GUI のコールバックをまとめたクリックの受け皿。 */
    private SessionInput.ClickSink lastClickSink = SessionInput.ClickSink.NONE;
    private boolean suppressNextRelease = false;
    private boolean suppressNextClick = false;
    private Screen gestureScreen;
    private Font gestureFont;
    private BiConsumer<String, String> gestureInputChanged;

    public EnderScreenDelegate(String sessionId, ReactiveStateStore state) {
        this.sessionId = sessionId;
        this.state = state;
        this.viewport = new WebViewport();
        this.router = new WebActionRouter(sessionId);
        this.selectWidget = new WebSelectDropdownWidget();
        this.dragDropController = new WebDragDropController();
        this.focusNavigator = new WebFocusNavigator(sessionId);
        this.textareaEditor = new TextareaEditor(sessionId);

        this.eventCallback = (sid, elementId, eventName, actionName, value) -> {
            if (!this.sessionId.equals(sid)) return false;
            return router.dispatch(actionName, elementId, eventName, value);
        };
        ClientWebManager.getOrCreateSession(sessionId).input().setLocalEventCallback(this.eventCallback);
    }

    public WebViewport viewport() { return viewport; }
    public WebActionRouter router() { return router; }
    public WebSelectDropdownWidget selectWidget() { return selectWidget; }
    public boolean isSelectDropdownHovered(double webX, double webY) {
        return selectWidget.isOpen() && selectWidget.isWithinPopup(webX, webY);
    }
    public WebDragDropController dragDropController() { return dragDropController; }
    public WebFocusNavigator focusNavigator() { return focusNavigator; }
    public TextareaEditor textareaEditor() { return textareaEditor; }

    public int getVirtualWidth() { return viewport.virtualWidth(); }
    public int getVirtualHeight() { return viewport.virtualHeight(); }
    public float getGuiScaleFactor() { return viewport.guiScaleFactor(); }
    public float getOffsetX() { return viewport.offsetX(); }
    public float getOffsetY() { return viewport.offsetY(); }
    public double toWebX(double mouseX) { return viewport.toWebX(mouseX); }
    public double toWebY(double mouseY) { return viewport.toWebY(mouseY); }
    public Rect2i toScreenRect(int webX, int webY, int webW, int webH) { return viewport.toScreenRect(webX, webY, webW, webH); }
    public Rect2i getViewportBounds() { return viewport.getViewportBounds(); }

    public void clearHandlers() { router.clear(); }
    public void onAction(String actionName, Runnable handler) { router.onAction(actionName, handler); }
    public void onActionPrefix(String prefix, Consumer<String> payloadHandler) { router.onActionPrefix(prefix, payloadHandler); }
    public void sendServerAction(String actionName, Object value) { router.sendServerAction(actionName, value); }
    public boolean dispatchRoutedAction(String action) { return router.dispatch(action); }

    public void renderDevToolsInspector(GuiGraphics guiGraphics, Font font, List<DisplayItem> elements, int webX, int webY, int virtualWidth, int virtualHeight) {
        WebDevTools.renderInspector(guiGraphics, font, elements, webX, webY, virtualWidth, virtualHeight, sessionId);
    }

    public void focusWebTextarea(Font font, DisplayItem taEl, double localX, double localY) {
        textareaEditor.focus(font, taEl, localX, localY);
    }

    public void updateViewport(int screenW, int screenH, int currentVirtualW, int currentVirtualH, boolean fullscreenResponsive, float marginRatio) {
        viewport.update(screenW, screenH, currentVirtualW, currentVirtualH, fullscreenResponsive, marginRatio, sessionId);
    }

    public EditBox createHiddenEditBox(Font font, BiConsumer<String, String> onInputChanged) {
        this.inputHost = new WebTextInputHost(sessionId, font, newText -> {
            SessionContext session = ClientWebManager.getSession(sessionId);
            if (session != null && !session.activeInputId().isEmpty() && !textareaEditor.isEditing()) {
                String baseId = ClientWebManager.getBaseNodeId(session.activeInputId());
                syncInputValueToDomNodes(baseId, newText);
                if (this.state != null) this.state.notifyElementModelChanged(baseId, newText);
                if (onInputChanged != null) onInputChanged.accept(baseId, newText);
            }
        });
        return this.inputHost.editBox();
    }

    public void syncInputValueToDomNodes(String baseId, String safeVal) {
        String cleanVal = safeVal != null ? safeVal : "";
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode vNode = session != null ? session.findNode(baseId) : null;
        if (vNode != null) vNode.setAttribute("value", cleanVal);
        ClientWebManager.getOrCreateSession(sessionId).input().updateInputLocally(baseId + "_input", cleanVal);
    }

    public void focusWebInput(Screen screen, String inputElementId, String currentText) {
        if (textareaEditor.isEditing()) {
            unfocusWebInput();
        }
        textareaEditor.unfocus();
        String baseId = ClientWebManager.getBaseNodeId(inputElementId);
        setFocusedDomElement(baseId);
        String renderInputId = baseId + "_input";
        String safeVal = currentText != null ? currentText : "";

        ClientWebManager.getOrCreateSession(sessionId).activateInput(renderInputId);
        ClientWebManager.getOrCreateSession(sessionId).input().setLastCommittedValue(baseId, safeVal);
        syncInputValueToDomNodes(baseId, safeVal);
        if (this.inputHost != null) {
            this.inputHost.setValue(safeVal);
            if (screen != null) screen.setFocused(this.inputHost.editBox());
            this.inputHost.activate();
        }
    }

    public void unfocusWebInput() {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session != null && !session.activeInputId().isEmpty()) {
            String activeId = session.activeInputId();
            String baseId = ClientWebManager.getBaseNodeId(activeId);
            SessionInput input = session.input();
            if (textareaEditor.isEditing()) {
                String fullVal = textareaEditor.getValue(baseId);
                input.submitInput(baseId, fullVal);
                textareaEditor.unfocus();
            } else {
                String val = (this.inputHost != null) ? this.inputHost.getValue() : "";
                input.submitInput(baseId, val);
            }
            session.setActiveInputId("");
        }
        if (this.inputHost != null) {
            this.inputHost.deactivate();
        }
    }

    public void setFocusedDomElement(String baseId) {
        setFocusedDomElement(baseId, false);
    }

    public void setFocusedDomElement(String baseId, boolean focusVisible) {
        String safeNext = baseId != null ? baseId : "";
        focusNavigator.setFocused(safeNext);
        SessionContext session = safeNext.isEmpty()
                ? ClientWebManager.getSession(sessionId)
                : ClientWebManager.getOrCreateSession(sessionId);
        if (session != null) session.setFocusedElement(safeNext, focusVisible);
    }

    public void renderOverlays(GuiGraphics guiGraphics, Font font, List<DisplayItem> elements,
                               int webX, int webY, int virtualWidth, int virtualHeight, boolean devToolsEnabled) {
        RenderSystem.disableDepthTest();
        try {
            SessionContext session = ClientWebManager.getSession(sessionId);
            focusNavigator.renderFocusRing(guiGraphics, elements);
            selectWidget.renderOverlay(guiGraphics, font, webX, webY, session != null ? session.rootNode() : null);
            dragDropController.renderGhost(guiGraphics, font, webX, webY);
            if (devToolsEnabled) {
                WebDevTools.renderInspector(guiGraphics, font, elements, webX, webY, virtualWidth, virtualHeight, sessionId);
            }
        } finally {
            RenderSystem.enableDepthTest();
        }
    }

    public boolean handleMouseClicked(
            Screen screen, Font font, double webX, double webY, int button,
            BiConsumer<String, String> onInputChanged,
            ClientDomEventDispatcher.TriActionCallback onWebAction
    ) {
        if (selectWidget.isOpen() && button == 0) {
            if (selectWidget.isWithinPopup(webX, webY)) {
                boolean handled = selectWidget.handleClick(webX, webY, (baseId, chosenVal) -> {
                    commitValue(baseId, chosenVal, onInputChanged);
                });
                if (handled) {
                    this.suppressNextRelease = true;
                    return true;
                }
            } else {
                SessionContext session = ClientWebManager.getSession(sessionId);
                SessionContext.UIHitResult hit = session != null ? session.hitTest(webX, webY) : null;
                String hitBaseId = hit != null && hit.element() != null ? hit.element().baseId() : "";
                if (selectWidget.openSelectBaseId().equals(hitBaseId)) {
                    selectWidget.close();
                    this.suppressNextRelease = true;
                    return true;
                }
                selectWidget.close();
            }
        }

        this.gestureScreen = screen;
        this.gestureFont = font;
        this.gestureInputChanged = onInputChanged;
        this.lastClickSink = new GuiClickSink(onInputChanged, onWebAction);

        SessionContext session = ClientWebManager.getOrCreateSession(sessionId);
        SessionInput.PressResult result = session.input().press(webX, webY, button, this);
        return result != null && shouldConsumePress(result);
    }

    /**
     * 押下を GUI 画面が消費するか。以前の {@code handleMouseClicked} は「操作を起こしたときだけ true」を
     * 返していたため、コンテナ画面では DOM の背景をクリックしたときにバニラのスロット操作へ抜けられた。
     * クリック合成を解放時に移した後もこの境界を保つ。
     */
    private static boolean shouldConsumePress(SessionInput.PressResult result) {
        if (result == null || result.element() == null) return false;
        if (result.consumed()) return true;
        return switch (result.element().type()) {
            case SCENE, TEXTAREA, INPUT, SELECT, RANGE -> true;
            default -> hasActionFor(result.pressedNode(), result.minecraftButton());
        };
    }

    private static boolean hasActionFor(HtmlNode node, int minecraftButton) {
        int domButton = SessionInput.domButton(minecraftButton);
        String attribute = domButton == 0 ? "onclick" : (domButton == 2 ? "oncontextmenu" : "onauxclick");
        String eventType = domButton == 0 ? "click" : (domButton == 2 ? "contextmenu" : "auxclick");
        for (HtmlNode current = node; current != null; current = current.parent) {
            String inlineAction = current.attrs.get(attribute);
            if (inlineAction != null && !inlineAction.isEmpty()) return true;
            if (current.getEventListenersSnapshot().stream().anyMatch(entry -> eventType.equalsIgnoreCase(entry.type()))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void pressed(SessionInput.PressResult result) {
        if (result == null || result.consumed() || result.element() == null) return;

        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null || result.hit() == null) return;

        DisplayItem clicked = result.element();
        SessionContext.UIHitResult hit = result.hit();
        int button = result.minecraftButton();

        if (clicked.type() == ElementType.SCENE) {
            unfocusWebInput();
            String baseId = clicked.baseId();
            if (button == 2) {
                WebSceneContext.getOrCreate(sessionId, baseId).resetCamera();
            } else {
                activeSceneId = baseId;
                activeSceneMouseButton = button;
            }
            return;
        }

        if (clicked.type() == ElementType.TEXTAREA && button == 0) {
            unfocusWebInput();
            String baseId = clicked.baseId();
            focusNavigator.setFocused(baseId);
            session.setFocusedElement(baseId, false);
            textareaEditor.focus(gestureFont, clicked, hit.localX(), hit.localY());
            return;
        }

        boolean isInputOrPlaceholder = clicked.type() == ElementType.INPUT
                || (clicked.type() == ElementType.TEXT && clicked.id().endsWith("_ph"));
        if (isInputOrPlaceholder && button == 0) {
            String baseId = clicked.baseId();
            focusNavigator.setFocused(baseId);
            session.setFocusedElement(baseId, false);
            String curVal = getInputValue(baseId);
            if (curVal.isEmpty() && clicked.text() != null && !clicked.id().endsWith("_ph")) curVal = clicked.text();
            focusWebInput(gestureScreen, baseId, curVal);
            return;
        }

        unfocusWebInput();

        if (clicked.type() == ElementType.TEXT && button == 0) {
            TextSelection sel = session.textSelection();
            if (sel != null && clicked.text() != null) {
                int charOffset = estimateCharOffset(clicked, gestureFont, hit.localX());
                sel.startSelection(clicked.baseId(), charOffset);
            }
        }

        String baseId = clicked.baseId();
        focusNavigator.setFocused(baseId);
        session.setFocusedElement(baseId, false);

        HtmlNode domNode = session.findInteractiveNode(baseId);
        boolean isSelectElement = clicked.type() == ElementType.SELECT || (domNode != null && "select".equalsIgnoreCase(domNode.tag));
        if (isSelectElement && button == 0) {
            selectWidget.open(baseId, clicked, session.rootNode());
            return;
        }

        if (clicked.type() == ElementType.RANGE && button == 0) {
            // 値の確定は SessionInput が押下・ドラッグ・解放で行う。ここでは GUI の購読者へ
            // 押下直後の値を知らせるだけにして、ワールド内と同じイベント順を保つ。
            DisplayItem updated = session.elementById(baseId);
            if (updated != null && updated.text() != null) {
                String val = updated.text().split(":")[0];
                if (this.state != null) this.state.notifyElementModelChanged(baseId, val);
                if (gestureInputChanged != null) gestureInputChanged.accept(baseId, val);
            }
            return;
        }

        if (button == 0 && clicked.events() != null && "true".equalsIgnoreCase(clicked.events().get("draggable"))) {
            String label = (domNode != null && !domNode.getTextContent().isEmpty()) ? domNode.getTextContent().trim() : baseId;
            dragDropController.startDrag(baseId, label);
        }
    }

    @Override
    public boolean scrollHandled(SessionContext.UIHitResult hit, double scrollY) {
        if (hit == null || hit.element() == null) return false;
        if (hit.element().type() == ElementType.SCENE) {
            WebSceneContext.getOrCreate(sessionId, hit.element().baseId()).addZoom((float) (scrollY * 0.12));
            return true;
        }
        if (hit.element().type() == ElementType.TEXTAREA) {
            String taId = hit.element().id();
            int curScroll = TextEditState.getTextareaScrollRow(sessionId, taId);
            int nextScroll = Math.max(0, curScroll - (int) Math.signum(scrollY) * 3);
            TextEditState.setTextareaScrollRow(sessionId, taId, nextScroll);
            return true;
        }
        return false;
    }

    /** 押下から解放まで GUI のコールバックを運ぶ。 */
    private final class GuiClickSink implements SessionInput.ClickSink {
        private final BiConsumer<String, String> onInputChanged;
        private final ClientDomEventDispatcher.TriActionCallback onWebAction;
        private boolean invoked;

        private GuiClickSink(BiConsumer<String, String> onInputChanged,
                             ClientDomEventDispatcher.TriActionCallback onWebAction) {
            this.onInputChanged = onInputChanged;
            this.onWebAction = onWebAction;
        }

        @Override
        public void action(String action, DisplayItem clicked, int button) {
            invoked = true;
            if (onWebAction != null) onWebAction.onAction(action, clicked, button);
        }

        @Override
        public void inputChanged(String elementId, String value) {
            if (onInputChanged != null) onInputChanged.accept(elementId, value);
        }

        @Override
        public void clickDelegate(SessionInput input, HtmlNode target, double x, double y, int minecraftButton) {
            SessionContext session = ClientWebManager.getSession(sessionId);
            DisplayItem targetItem = session != null ? session.elementById(target.getId()) : null;
            input.dispatchClick(target, x, y, minecraftButton, targetItem, this);
        }

        private boolean invoked() {
            return invoked;
        }
    }

    private void commitValue(String baseId, String value, BiConsumer<String, String> onInputChanged) {
        ClientWebManager.getOrCreateSession(sessionId).input().submitInput(baseId, value);
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session != null) session.updateNodeAttributeLocally(baseId, "value", value, false);
        if (this.state != null) this.state.notifyElementModelChanged(baseId, value);
        if (onInputChanged != null) onInputChanged.accept(baseId, value);
    }

    /**
     * 要素の書体で測った、{@code localX}（要素の左端からの CSS px）に最も近い字の境目。
     * システムの書体なら描画と同じ字の位置（詰めを含む）で決める。
     */
    public static int estimateCharOffset(DisplayItem el, Font fallback, double localX) {
        String text = el.text();
        OutlineFontFace face = WebFontManager.outlineFaceFor(el);
        if (face == null || text == null) return estimateCharOffset(fallback, text, localX);
        float fontH = el.meta() != null ? el.meta().fontHeight() : el.height();
        return OutlineFontFace.nearestBoundary(text, face.positions(text, WebFontManager.fontStyleFor(el, fontH)), localX);
    }

    public static int estimateCharOffset(Font font, String text, double localX) {
        if (text == null || text.isEmpty() || font == null || localX <= 0) return 0;
        int len = text.length();
        int prevW = 0;
        for (int i = 1; i <= len; i++) {
            int curW = font.width(text.substring(0, i));
            if (localX < (prevW + curW) / 2.0) {
                return i - 1;
            }
            prevW = curW;
        }
        return len;
    }

    public boolean handleMouseDragged(double webX, double webY, double dragX, double dragY, float guiScaleFactor, BiConsumer<String, String> onInputChanged) {
        if (!activeSceneId.isEmpty()) {
            WebSceneContext scene = WebSceneContext.getOrCreate(sessionId, activeSceneId);
            if (activeSceneMouseButton == 0) {
                scene.addRotation((float) (dragY * 0.8), (float) (dragX * 0.8));
            } else if (activeSceneMouseButton == 1) {
                scene.addPan((float) (dragX / guiScaleFactor), (float) (dragY / guiScaleFactor));
            }
            return true;
        }

        SessionContext inputSession = ClientWebManager.getOrCreateSession(sessionId);
        if (inputSession.input().drag(webX, webY)) {
            SessionContext session = ClientWebManager.getSession(sessionId);
            if (session != null && !session.activePressedId().isEmpty()) {
                String baseId = ClientWebManager.getBaseNodeId(session.activePressedId());
                DisplayItem updated = session.elementById(baseId);
                if (updated != null && updated.type() == ElementType.RANGE && updated.text() != null) {
                    String val = updated.text().split(":")[0];
                    if (this.state != null) this.state.notifyElementModelChanged(baseId, val);
                    if (onInputChanged != null) onInputChanged.accept(baseId, val);
                }
            }
            return true;
        }

        SessionContext session = ClientWebManager.getSession(sessionId);
        TextSelection sel = session != null ? session.textSelection() : null;
        if (sel != null && !sel.anchorNodeId().isEmpty()) {
            SessionContext.UIHitResult dragHit = session.hitTest(webX, webY);
            if (dragHit != null && dragHit.element() != null && dragHit.element().text() != null) {
                if (sel.anchorNodeId().equals(dragHit.element().baseId())) {
                    Font font = Minecraft.getInstance().font;
                    int offset = estimateCharOffset(dragHit.element(), font, dragHit.localX());
                    sel.updateWithinNode(sel.anchorNodeId(), offset, dragHit.element().text());
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 次の解放でクリックを合成しない（押下状態の解除は通常どおり行う）。
     * 画面側が押下をドラッグ操作として扱った場合、解放位置の要素へクリックが届かないようにする。
     */
    public void suppressNextClick() {
        this.suppressNextClick = true;
    }

    public void handleMouseReleased(double webX, double webY, int button, WebDragDropController.DropCallback onWebDrop) {
        boolean synthesizeClick = !suppressNextClick;
        suppressNextClick = false;
        if (suppressNextRelease) {
            suppressNextRelease = false;
            activeSceneId = "";
            lastClickSink = SessionInput.ClickSink.NONE;
            return;
        }
        activeSceneId = "";
        SessionContext session = ClientWebManager.getOrCreateSession(sessionId);
        session.input().release(webX, webY, button, synthesizeClick, lastClickSink);
        lastClickSink = SessionInput.ClickSink.NONE;
        dragDropController.handleRelease(sessionId, webX, webY, onWebDrop);
    }

    public boolean handleMouseScrolled(double webX, double webY, double scrollX, double scrollY) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null) return false;
        return session.input().scroll(webX, webY, scrollX, scrollY,
                safeHasControlDown(), safeHasShiftDown(), safeHasAltDown(), this);
    }

    public boolean handleCharTyped(char codePoint, int modifiers, BiConsumer<String, String> onInputChanged) {
        String data = String.valueOf(codePoint);
        SessionInput input = ClientWebManager.getOrCreateSession(sessionId).input();
        if (textareaEditor.isEditing()) {
            String baseId = textareaEditor.activeBaseId();
            if (!input.beginTextEdit(baseId, data, "insertText")) return true;
            boolean handled = textareaEditor.handleCharTyped(codePoint, onInputChanged, () -> {
                if (this.state != null) this.state.notifyElementModelChanged(baseId, textareaEditor.getValue(baseId));
            });
            if (handled) {
                input.commitTextEdit(baseId, textareaEditor.getValue(baseId), data, "insertText");
            }
            return handled;
        } else {
            SessionContext session = ClientWebManager.getSession(sessionId);
            if (session != null && !session.activeInputId().isEmpty() && this.inputHost != null) {
                String baseId = ClientWebManager.getBaseNodeId(session.activeInputId());
                if (!input.beginTextEdit(baseId, data, "insertText")) return true;
                if (this.inputHost.charTyped(codePoint, modifiers)) {
                    input.commitTextEdit(baseId, this.inputHost.getValue(), data, "insertText");
                    return true;
                }
            }
        }
        return false;
    }

    private enum KeyboardMode {
        TEXTAREA_EDITING, SINGLE_LINE_INPUT, NAVIGATION
    }

    private KeyboardMode keyboardMode() {
        SessionContext session = ClientWebManager.getSession(sessionId);
        boolean hasActiveInput = session != null && !session.activeInputId().isEmpty();
        if (!hasActiveInput) return KeyboardMode.NAVIGATION;
        return textareaEditor.isEditing() ? KeyboardMode.TEXTAREA_EDITING : KeyboardMode.SINGLE_LINE_INPUT;
    }


    private static boolean safeHasControlDown() {
        try {
            return Screen.hasControlDown();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean safeHasShiftDown() {
        try {
            return Screen.hasShiftDown();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean safeHasAltDown() {
        try {
            return Screen.hasAltDown();
        } catch (Throwable t) {
            return false;
        }
    }

    private HtmlNode keyboardEventTarget() {
        SessionContext session = ClientWebManager.getSession(sessionId);
        String focusedId = focusNavigator.focusedId();
        HtmlNode target = session != null && focusedId != null && !focusedId.isEmpty() ? session.findNode(focusedId) : null;
        return target != null ? target : (session != null ? session.rootNode() : null);
    }

    private UIEvents.Keyboard dispatchKeyEvent(String type, int keyCode, int modifiers) {
        HtmlNode target = keyboardEventTarget();
        if (target == null) return null;
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0 || safeHasControlDown();
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 || safeHasShiftDown();
        boolean alt = (modifiers & GLFW.GLFW_MOD_ALT) != 0 || safeHasAltDown();
        boolean meta = (modifiers & GLFW.GLFW_MOD_SUPER) != 0;
        return ClientWebManager.getOrCreateSession(sessionId).input()
                .keyEvent(type, target, keyCode, ctrl, shift, alt, meta);
    }

    public boolean handleKeyReleased(int keyCode, int modifiers) {
        UIEvents.Keyboard keyUp = dispatchKeyEvent("keyup", keyCode, modifiers);
        return keyUp != null && keyUp.isDefaultPrevented();
    }

    public boolean handleKeyPressed(Screen screen, Font font, int keyCode, int scanCode, int modifiers,
                                    boolean blockInventoryKey,
                                    BiConsumer<String, String> onInputChanged,
                                    BiConsumer<String, String> onInputSubmitted,
                                    ClientDomEventDispatcher.TriActionCallback onWebAction) {
        if (selectWidget.isOpen()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                selectWidget.close();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP) {
                selectWidget.moveHighlight(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                selectWidget.moveHighlight(1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                selectWidget.confirmHighlight((baseId, chosenVal) -> {
                    commitValue(baseId, chosenVal, onInputChanged);
                });
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                selectWidget.close();
            }
        }

        SessionContext session = ClientWebManager.getSession(sessionId);
        UIEvents.Keyboard keyDown = dispatchKeyEvent("keydown", keyCode, modifiers);
        if (keyDown != null && keyDown.isDefaultPrevented()) {
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_TAB && !textareaEditor.isEditing()) {
            boolean reverse = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 || safeHasShiftDown();
            return focusNavigator.cycleSequentialFocus(screen, reverse, candidate -> {
                applyFocusCandidate(screen, candidate);
            });
        }

        switch (keyboardMode()) {
            case TEXTAREA_EDITING -> {
                String baseId = textareaEditor.activeBaseId();
                if (keyCode == GLFW.GLFW_KEY_SPACE) {
                    return true;
                }
                String inputType = SessionInput.editingInputType(keyCode, modifiers, true);
                SessionInput input = ClientWebManager.getOrCreateSession(sessionId).input();
                if (inputType != null && !input.beginTextEdit(baseId, null, inputType)) return true;
                if (textareaEditor.handleKeyPressed(keyCode, onInputChanged, () -> {
                    if (this.state != null) this.state.notifyElementModelChanged(baseId, textareaEditor.getValue(baseId));
                })) {
                    input.commitTextEdit(baseId, textareaEditor.getValue(baseId), null, inputType != null ? inputType : "insertText");
                    return true;
                }
                if (isInventoryKey(keyCode, scanCode)) return true;
            }
            case SINGLE_LINE_INPUT -> {
                String activeId = session != null ? session.activeInputId() : "";
                String baseId = ClientWebManager.getBaseNodeId(activeId);
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    unfocusWebInput();
                    return true;
                }
                SessionInput input = ClientWebManager.getOrCreateSession(sessionId).input();
                if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    String val = (this.inputHost != null) ? this.inputHost.getValue() : "";
                    syncInputValueToDomNodes(baseId, val);
                    input.submitInput(activeId, val);
                    if (onInputSubmitted != null) onInputSubmitted.accept(baseId, val);
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_SPACE) {
                    return true;
                }
                String inputType = SessionInput.editingInputType(keyCode, modifiers, false);
                if (inputType != null && !input.beginTextEdit(baseId, null, inputType)) return true;
                if (this.inputHost != null && this.inputHost.keyPressed(keyCode, scanCode, modifiers)) {
                    input.commitTextEdit(baseId, this.inputHost.getValue(), null, inputType != null ? inputType : "insertText");
                    return true;
                }
                if (isInventoryKey(keyCode, scanCode)) return true;
            }
            case NAVIGATION -> { }
        }

        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            String focusedId = focusNavigator.focusedId();
            if (!focusedId.isEmpty()) {
                HtmlNode node = session != null ? session.findNode(focusedId) : null;
                if (node != null && "input".equalsIgnoreCase(node.tag) && "range".equalsIgnoreCase(node.attrs.getOrDefault("type", ""))) {
                    int dir = (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_RIGHT) ? 1 : -1;
                    return adjustFocusedRangeSlider(focusedId, dir, onInputChanged);
                }
            }
            return focusNavigator.navigateSpatialFocus(screen, keyCode, candidate -> applyFocusCandidate(screen, candidate));
        }

        if (keyCode == GLFW.GLFW_KEY_C && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            TextSelection sel = session != null ? session.textSelection() : null;
            if (sel != null && !sel.isEmpty()) {
                Minecraft.getInstance().keyboardHandler.setClipboard(sel.selectedText());
                return true;
            }
        }

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
            String focusedId = focusNavigator.focusedId();
            if (!focusedId.isEmpty()) {
                HtmlNode node = session != null ? session.findNode(focusedId) : null;
                if (node != null) {
                    return dispatchActivateToNode(node, onInputChanged, onWebAction);
                }
            }
        }

        return false;
    }

    private static boolean isInventoryKey(int keyCode, int scanCode) {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.options != null && mc.options.keyInventory != null && mc.options.keyInventory.matches(keyCode, scanCode);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean dispatchActivateToNode(HtmlNode node,
                                           BiConsumer<String, String> onInputChanged,
                                           ClientDomEventDispatcher.TriActionCallback onWebAction) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        DisplayItem el = session != null ? session.elementById(node.getId()) : null;
        if (el != null) {
            ClientScreenInteractionManager.playElementSound(el);
        }
        GuiClickSink sink = new GuiClickSink(onInputChanged, onWebAction);
        ClientWebManager.getOrCreateSession(sessionId).input().dispatchClick(node, 0, 0, 0, el, sink);
        return sink.invoked();
    }

    private void applyFocusCandidate(Screen screen, WebFocusNavigator.FocusCandidate candidate) {
        unfocusWebInput();
        String baseId = candidate.node().getId();
        setFocusedDomElement(baseId, true);

        HtmlNode node = candidate.node();
        if ("input".equalsIgnoreCase(node.tag)) {
            String inputType = node.attrs.getOrDefault("type", "text").toLowerCase(Locale.ROOT);
            if (!"checkbox".equals(inputType) && !"radio".equals(inputType) && !"range".equals(inputType) && !"color".equals(inputType)) {
                if (screen != null) {
                    SessionContext session = ClientWebManager.getSession(sessionId);
                    HtmlNode vNode = session != null ? session.findNode(baseId) : null;
                    String val = (vNode != null) ? vNode.attrs.getOrDefault("value", "") : "";
                    focusWebInput(screen, baseId, val);
                    ClientWebManager.getOrCreateSession(sessionId).setFocusedElement(baseId, true);
                }
            }
        }
    }

    private boolean adjustFocusedRangeSlider(String focusedId, int dir, BiConsumer<String, String> onInputChanged) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode node = session != null ? session.findNode(focusedId) : null;
        if (node == null || !"input".equalsIgnoreCase(node.tag)) return false;

        double min = 0, max = 100, step = 1, cur = 50;
        try { min = Double.parseDouble(node.attrs.getOrDefault("min", "0")); } catch (Exception ignored) {}
        try { max = Double.parseDouble(node.attrs.getOrDefault("max", "100")); } catch (Exception ignored) {}
        try { step = Double.parseDouble(node.attrs.getOrDefault("step", String.valueOf((max - min) / 20.0))); } catch (Exception ignored) {}
        try { cur = Double.parseDouble(node.attrs.getOrDefault("value", String.valueOf(min))); } catch (Exception ignored) {}
        if (step <= 0) step = 1;

        double next = Math.clamp(cur + dir * step, min, max);
        String formatted = (step == Math.floor(step) && min == Math.floor(min))
                ? String.valueOf(Math.round(next))
                : String.format(Locale.ROOT, "%.2f", next);

        session.updateNodeAttributeLocally(focusedId, "value", formatted, false);
        if (this.state != null) this.state.notifyElementModelChanged(focusedId, formatted);
        if (onInputChanged != null) onInputChanged.accept(focusedId, formatted);
        return true;
    }

    public void setElementText(String id, String text) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode target = session != null ? session.findNode(id) : null;
        if (target != null) {
            target.setTextContent(text);
            session.requestRender();
        }
    }

    public void setInputValue(String id, String value) {
        String baseId = ClientWebManager.getBaseNodeId(id);
        String safeVal = value != null ? value : "";
        syncInputValueToDomNodes(baseId, safeVal);
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session != null && !textareaEditor.isEditing() && session.isFocusedBase(baseId)) {
            if (this.inputHost != null && !Objects.equals(this.inputHost.getValue(), safeVal)) {
                this.inputHost.setValue(safeVal);
            }
        }
    }

    public void setTextareaValue(String id, String value) {
        String baseId = ClientWebManager.getBaseNodeId(id);
        textareaEditor.setValue(baseId, value);
    }

    public String getTextareaValue(String id) {
        String baseId = ClientWebManager.getBaseNodeId(id);
        return textareaEditor.getValue(baseId);
    }

    public String getInputValue(String id) {
        String baseId = ClientWebManager.getBaseNodeId(id);
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session != null && !textareaEditor.isEditing() && session.isFocusedBase(baseId)) {
            if (this.inputHost != null) return this.inputHost.getValue();
        }
        HtmlNode node = session != null ? session.findNode(baseId) : null;
        return node != null ? node.attrs.getOrDefault("value", "") : "";
    }

    public void addClass(String id, String cls) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode target = session != null ? session.findNode(id) : null;
        if (target != null && !target.hasClass(cls)) {
            target.addClass(cls);
            session.requestRender();
        }
    }

    public void removeClass(String id, String cls) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode target = session != null ? session.findNode(id) : null;
        if (target != null && target.hasClass(cls)) {
            target.removeClass(cls);
            session.requestRender();
        }
    }

    public void toggleClass(String id, String cls, boolean enable) {
        if (enable) addClass(id, cls);
        else removeClass(id, cls);
    }

    public void onClose() {
        ClientScreenInteractionManager.clearSessionEventCallback(sessionId, this.eventCallback);
        unfocusWebInput();
        setFocusedDomElement("");
        activeSceneId = "";
        selectWidget.close();
        dragDropController.clear();
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session != null) session.setActivePressedId("");
    }

    /**
     * 画面が表示から外れたとき（{@code Screen#removed()}）に呼ぶ。
     *
     * <p>ESC による {@code onClose} だけでなく、サーバー側からのコンテナクローズや
     * 別画面への差し替えでも呼ばれるため、Headless State の閲覧者解除はここで行う。</p>
     */
    public void onScreenRemoved() {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null || !session.isHeadlessState()) return;
        try {
            ClientScreenInteractionManager.sendToServer(UiActionC2SPacket.close(sessionId));
        } catch (Exception ignored) {
            // 切断処理中で接続が無い場合。サーバー側はログアウトで閲覧者を外す
        }
    }

    public static class WebDevTools {
        public static void renderInspector(
                GuiGraphics guiGraphics, Font font, List<DisplayItem> elements,
                int webX, int webY, int virtualWidth, int virtualHeight,
                String sessionId
        ) {
            if (elements == null) return;
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(0, 0, com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.ZLayers.DEVTOOLS_OVERLAY_Z);

            String banner = " [F12] EnderFrame DevTools | [F5] Hot Reload | [Tab/Arrows] Focus | Viewport: "
                    + virtualWidth + "x" + virtualHeight + " | Mouse: (" + webX + ", " + webY + ") ";
            guiGraphics.fill(0, 0, font.width(banner) + 8, 13, 0xDD11111B);
            guiGraphics.drawString(font, banner, 4, 2, 0xFFA6E3A1, false);

            DisplayItem targetAnchor = null;
            for (int i = elements.size() - 1; i >= 0; i--) {
                DisplayItem el = elements.get(i);
                if (el.type() == ElementType.NODE && !"html_root".equals(el.id()) && el.width() > 0 && el.height() > 0) {
                    if (webX >= el.x() && webX <= el.x() + el.width() && webY >= el.y() && webY <= el.y() + el.height()) {
                        targetAnchor = el;
                        break;
                    }
                }
            }

            if (targetAnchor != null) {
                SessionContext session = ClientWebManager.getSession(sessionId);
                HtmlNode domNode = session != null ? session.findNode(targetAnchor.id()) : null;
                int x = targetAnchor.x(), y = targetAnchor.y(), w = targetAnchor.width(), h = targetAnchor.height();

                guiGraphics.renderOutline(x, y, w, h, 0xFF89B4FA);

                List<String> infoLines = new ArrayList<>();
                String tagLabel = domNode != null ? "<" + domNode.tag + ">" : "<node>";
                String idLabel = (domNode != null && !domNode.getId().isEmpty()) ? "#" + domNode.getId() : "#" + targetAnchor.id();
                String clsLabel = (domNode != null && !domNode.getClasses().isEmpty()) ? "." + String.join(".", domNode.getClasses()) : "";
                infoLines.add("§b" + tagLabel + " §e" + idLabel + "§a" + clsLabel);
                infoLines.add("§7Rect: §f" + w + "x" + h + " §7at (" + x + ", " + y + ")");
                if (domNode != null) {
                    if (domNode.attrs.containsKey("data-model")) infoLines.add("§ddata-model: §f" + domNode.attrs.get("data-model"));
                    if (domNode.attrs.containsKey("data-bind")) infoLines.add("§ddata-bind: §f" + domNode.attrs.get("data-bind"));
                    if (domNode.attrs.containsKey("data-slot")) infoLines.add("§6data-slot: §f#" + domNode.attrs.get("data-slot"));
                    if (domNode.attrs.containsKey("onclick")) infoLines.add("§conclick: §f" + domNode.attrs.get("onclick"));
                }

                int cardW = 0;
                for (String line : infoLines) cardW = Math.max(cardW, font.width(line) + 10);
                int cardH = infoLines.size() * 11 + 6;
                int cardX = Math.clamp(x, 4, Math.max(4, virtualWidth - cardW - 4));
                int cardY = (y - cardH - 4 >= 16) ? (y - cardH - 4) : Math.min(virtualHeight - cardH - 4, y + h + 4);

                guiGraphics.fill(cardX - 1, cardY - 1, cardX + cardW + 1, cardY + cardH + 1, 0xFF89B4FA);
                guiGraphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0xEE181825);
                for (int i = 0; i < infoLines.size(); i++) {
                    guiGraphics.drawString(font, infoLines.get(i), cardX + 5, cardY + 4 + i * 11, 0xFFFFFFFF, false);
                }
            }

            pose.popPose();
        }
    }

    public static String glfwKeyToDomKey(int keyCode) {
        return switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> "Enter";
            case GLFW.GLFW_KEY_ESCAPE -> "Escape";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_SPACE -> " ";
            case GLFW.GLFW_KEY_UP -> "ArrowUp";
            case GLFW.GLFW_KEY_DOWN -> "ArrowDown";
            case GLFW.GLFW_KEY_LEFT -> "ArrowLeft";
            case GLFW.GLFW_KEY_RIGHT -> "ArrowRight";
            case GLFW.GLFW_KEY_PAGE_UP -> "PageUp";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PageDown";
            case GLFW.GLFW_KEY_HOME -> "Home";
            case GLFW.GLFW_KEY_END -> "End";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            default -> {
                String name = GLFW.glfwGetKeyName(keyCode, 0);
                yield (name != null && !name.isEmpty()) ? name : "Unidentified";
            }
        };
    }

    public static String glfwKeyToDomCode(int keyCode) {
        return switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_KP_ENTER -> "NumpadEnter";
            case GLFW.GLFW_KEY_ESCAPE -> "Escape";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_UP -> "ArrowUp";
            case GLFW.GLFW_KEY_DOWN -> "ArrowDown";
            case GLFW.GLFW_KEY_LEFT -> "ArrowLeft";
            case GLFW.GLFW_KEY_RIGHT -> "ArrowRight";
            case GLFW.GLFW_KEY_PAGE_UP -> "PageUp";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PageDown";
            case GLFW.GLFW_KEY_HOME -> "Home";
            case GLFW.GLFW_KEY_END -> "End";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            default -> "Key" + keyCode;
        };
    }

    public static class WebActionRouter {
        private final String sessionId;
        private final Map<String, Runnable> exactActionHandlers = new HashMap<>();
        private final Map<String, Consumer<String>> prefixActionHandlers = new LinkedHashMap<>();

        public WebActionRouter(String sessionId) {
            this.sessionId = sessionId;
        }

        public void clear() {
            exactActionHandlers.clear();
            prefixActionHandlers.clear();
        }

        public void onAction(String actionName, Runnable handler) {
            exactActionHandlers.put(actionName, handler);
        }

        public void onActionPrefix(String prefix, Consumer<String> payloadHandler) {
            prefixActionHandlers.put(prefix, payloadHandler);
        }

        public void sendServerAction(String actionName, Object value) {
            sendServerAction(actionName, value, "", "rpc");
        }

        /**
         * DOM のイベントから来たアクションは、要素 ID・イベント名・値（入力欄の値など）を付けて送る。
         * 以前は文脈を捨てて {@code rpc} と空の値で送っていたため、{@code oninput="server:…"} で
         * サーバーが入力値を受け取れなかった（実機で確認）。
         */
        public void sendServerAction(String actionName, Object value, String elementId, String eventName) {
            CompoundTag tag = new CompoundTag();
            tag.putString("ElementId", elementId != null ? elementId : "");
            tag.putString("EventName", eventName != null && !eventName.isEmpty() ? eventName : "rpc");
            tag.putString("ActionName", actionName != null ? actionName : "");
            tag.putString("Value", value != null ? String.valueOf(value) : "");

            SessionContext session = ClientWebManager.getSession(this.sessionId);
            if (session != null && session.isHeadlessState()) {
                String cleanAction = actionName != null && actionName.startsWith("server:")
                        ? actionName.substring(7)
                        : (actionName != null ? actionName : "");
                ClientScreenInteractionManager.sendToServer(new UiActionC2SPacket(this.sessionId, cleanAction, tag));
            } else {
                ClientScreenInteractionManager.sendToServer(new WebActionC2SPacket(this.sessionId, "ui_event", tag));
            }
        }

        public boolean dispatch(String action) {
            return dispatch(action, "", "rpc", "");
        }

        /**
         * アクションを振り分ける。{@code server:name:payload} は書かれた payload を、{@code server:name} は
         * イベントの値（{@code eventValue}）を値として送る。
         */
        public boolean dispatch(String action, String elementId, String eventName, String eventValue) {
            if (action == null || action.isEmpty()) return false;

            if (action.startsWith("server:")) {
                String rest = action.substring(7);
                if (rest.contains(":")) {
                    String[] sp = rest.split(":", 2);
                    sendServerAction(sp[0], sp[1], elementId, eventName);
                } else {
                    sendServerAction(rest, eventValue != null ? eventValue : "", elementId, eventName);
                }
                return true;
            }

            Runnable handler = exactActionHandlers.get(action);
            if (handler != null) {
                handler.run();
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
    }

    public static class WebViewport {
        private int virtualWidth = 854;
        private int virtualHeight = 480;
        private float guiScaleFactor = 1.0f;
        private float offsetX = 0.0f;
        private float offsetY = 0.0f;

        public WebViewport() {}

        public WebViewport(int initialVirtualW, int initialVirtualH) {
            this.virtualWidth = initialVirtualW;
            this.virtualHeight = initialVirtualH;
        }

        public void update(int screenW, int screenH, int currentVirtualW, int currentVirtualH,
                           boolean fullscreenResponsive, float marginRatio, String sessionId) {
            int targetW = currentVirtualW;
            int targetH = currentVirtualH;

            double guiScale = guiScale();
            SessionContext session = ClientWebManager.getSession(sessionId);
            if (fullscreenResponsive) {
                // ブラウザと同じく、ビューポート（CSS px）= 実ピクセル ÷ デバイスピクセル比（OS の拡大率 × ズーム）。
                // 以前は 854×480 を下限にした固定の大きさを画面いっぱいに引き伸ばしていた
                var viewport = LayoutMetrics.DeviceViewport.of(
                        (int) Math.round(screenW * marginRatio * guiScale), (int) Math.round(screenH * marginRatio * guiScale),
                        LayoutMetrics.DeviceViewport.devicePixelRatio(osContentScale(), EnderFrameBuiltInConfig.uiZoomPercent));
                targetW = viewport.cssWidth();
                targetH = viewport.cssHeight();
            }

            float scaleX = (screenW * marginRatio) / Math.max(1, targetW);
            float scaleY = (screenH * marginRatio) / Math.max(1, targetH);
            float scale = Math.min(scaleX, scaleY);
            if (session != null && session.document() != null) {
                // 1 CSS px が実際に何ピクセルで描かれるか（@media (resolution) の値）
                session.setDevicePixelRatio((float) (scale * guiScale));
                if (fullscreenResponsive && (currentVirtualW != targetW || currentVirtualH != targetH)) {
                    // その場で作り直さず予約だけする。ウィンドウをつかんで広げている間は毎フレーム大きさが変わるので、
                    // 描画キューが 1 フレーム 1 回にまとめ、重い文書では間引いて反映する
                    session.requestResize(targetW, targetH);
                }
            }

            this.virtualWidth = targetW;
            this.virtualHeight = targetH;
            this.guiScaleFactor = scale;
            this.offsetX = Math.round((screenW - targetW * this.guiScaleFactor) / 2.0f);
            this.offsetY = Math.round((screenH - targetH * this.guiScaleFactor) / 2.0f);
        }

        /**
         * OS の画面の拡大率（Windows の「拡大/縮小」、macOS の Retina など）。ブラウザの devicePixelRatio の元になる値。
         * GLFW はウィンドウのある画面ごとの値を返す。取れないときは 1。
         */
        private static float osContentScale() {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getWindow() == null) return 1.0f;
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                java.nio.FloatBuffer x = stack.mallocFloat(1);
                java.nio.FloatBuffer y = stack.mallocFloat(1);
                org.lwjgl.glfw.GLFW.glfwGetWindowContentScale(mc.getWindow().getWindow(), x, y);
                float scale = x.get(0);
                return Float.isFinite(scale) && scale > 0 ? scale : 1.0f;
            } catch (RuntimeException | LinkageError e) {
                return 1.0f;
            }
        }

        private static double guiScale() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.getWindow() != null ? mc.getWindow().getGuiScale() : 1.0;
        }

        public int virtualWidth() { return virtualWidth; }
        public int virtualHeight() { return virtualHeight; }
        public float guiScaleFactor() { return guiScaleFactor; }
        public float offsetX() { return offsetX; }
        public float offsetY() { return offsetY; }

        public double toWebX(double mouseX) { return (mouseX - offsetX) / guiScaleFactor; }
        public double toWebY(double mouseY) { return (mouseY - offsetY) / guiScaleFactor; }

        public Rect2i toScreenRect(int webX, int webY, int webW, int webH) {
            return new Rect2i(
                    Math.round(this.offsetX + webX * this.guiScaleFactor),
                    Math.round(this.offsetY + webY * this.guiScaleFactor),
                    Math.round(webW * this.guiScaleFactor),
                    Math.round(webH * this.guiScaleFactor)
            );
        }

        public Rect2i getViewportBounds() {
            return new Rect2i(
                    Math.round(this.offsetX),
                    Math.round(this.offsetY),
                    Math.round(this.virtualWidth * this.guiScaleFactor),
                    Math.round(this.virtualHeight * this.guiScaleFactor)
            );
        }
    }

    public static class WebDragDropController {
        public interface DropCallback {
            void onDrop(String draggedId, String dropTargetId, String dropAction);
        }

        private String draggingElementId = "";
        private String draggingLabel = "";

        public boolean isDragging() { return !draggingElementId.isEmpty(); }
        public String draggingElementId() { return draggingElementId; }

        public void startDrag(String elementId, String label) {
            this.draggingElementId = elementId != null ? elementId : "";
            this.draggingLabel = label != null ? label : "";
        }

        public void clear() {
            this.draggingElementId = "";
            this.draggingLabel = "";
        }

        public void renderGhost(GuiGraphics guiGraphics, Font font, int webX, int webY) {
            if (!isDragging()) return;
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(0, 0, com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.ZLayers.DRAG_DROP_OVERLAY_Z);
            int w = Math.max(60, font.width(draggingLabel) + 12);
            guiGraphics.fill(webX + 6, webY + 6, webX + 6 + w, webY + 24, 0xAA89B4FA);
            guiGraphics.drawString(font, draggingLabel, webX + 12, webY + 11, 0xFF11111B, false);
            pose.popPose();
        }

        public void handleRelease(String sessionId, double webX, double webY, DropCallback onWebDrop) {
            if (!isDragging()) return;
            SessionContext session = ClientWebManager.getSession(sessionId);
            SessionContext.UIHitResult hit = session != null ? session.hitTest(webX, webY) : null;
            if (hit != null && hit.element() != null) {
                String targetBaseId = hit.element().baseId();
                String dropAction = hit.element().events() != null ? hit.element().events().getOrDefault("ondrop", "") : "";
                if (!targetBaseId.equals(draggingElementId) && onWebDrop != null) {
                    onWebDrop.onDrop(draggingElementId, targetBaseId, dropAction);
                }
            }
            clear();
        }
    }

    public static class WebSelectDropdownWidget {
        public record SelectOption(String value, String label) {}
        private String openSelectBaseId = "";
        private int popupX, popupY, popupW;
        private int highlightedIndex = -1;
        private final List<SelectOption> options = new ArrayList<>();

        public boolean isOpen() { return !openSelectBaseId.isEmpty() && !options.isEmpty(); }
        public String openSelectBaseId() { return openSelectBaseId; }
        public int highlightedIndex() { return highlightedIndex; }

        public void setHighlightedIndex(int index) {
            if (index >= 0 && index < options.size()) {
                this.highlightedIndex = index;
            }
        }

        public void moveHighlight(int delta) {
            if (options.isEmpty()) return;
            if (highlightedIndex < 0) highlightedIndex = 0;
            else highlightedIndex = Math.floorMod(highlightedIndex + delta, options.size());
        }

        public boolean confirmHighlight(BiConsumer<String, String> onSelected) {
            if (!isOpen() || highlightedIndex < 0 || highlightedIndex >= options.size()) return false;
            String chosenVal = options.get(highlightedIndex).value();
            String baseId = openSelectBaseId;
            close();
            if (onSelected != null) onSelected.accept(baseId, chosenVal);
            return true;
        }

        public boolean isWithinPopup(double webX, double webY) {
            if (!isOpen()) return false;
            int itemH = 18;
            int totalH = options.size() * itemH + 2;
            return webX >= popupX && webX <= popupX + popupW && webY >= popupY && webY <= popupY + totalH;
        }

        public void open(String baseId, DisplayItem selectEl, HtmlNode virtualRoot) {
            if (baseId.equals(openSelectBaseId)) {
                close();
                return;
            }
            HtmlNode selectNode = virtualRoot != null ? virtualRoot.getNodeById(baseId) : null;
            if (selectNode == null) return;

            options.clear();
            for (HtmlNode child : selectNode.children) {
                if ("option".equalsIgnoreCase(child.tag)) {
                    String val = child.attrs.getOrDefault("value", child.getTextContent().trim());
                    String label = child.getTextContent().trim();
                    options.add(new SelectOption(val, label.isEmpty() ? val : label));
                }
            }

            if (!options.isEmpty()) {
                this.openSelectBaseId = baseId;
                this.popupX = selectEl.x();
                this.popupY = selectEl.y() + selectEl.height() + 2;
                this.popupW = Math.max(selectEl.width(), 90);
                this.highlightedIndex = -1;
                String currentVal = getCurrentValue(virtualRoot);
                for (int i = 0; i < options.size(); i++) {
                    if (options.get(i).value().equals(currentVal)) {
                        this.highlightedIndex = i;
                        break;
                    }
                }
                if (this.highlightedIndex == -1) {
                    this.highlightedIndex = 0;
                }
            }
        }

        public void close() {
            this.openSelectBaseId = "";
            this.options.clear();
            this.highlightedIndex = -1;
        }

        public boolean handleClick(double webX, double webY, BiConsumer<String, String> onSelected) {
            if (!isOpen()) return false;
            int itemH = 18;
            int totalH = options.size() * itemH + 2;
            boolean withinBounds = webX >= popupX && webX <= popupX + popupW && webY >= popupY && webY <= popupY + totalH;

            if (withinBounds) {
                int idx = (int) ((webY - popupY - 1) / itemH);
                if (idx >= 0 && idx < options.size()) {
                    String chosenVal = options.get(idx).value();
                    String baseId = openSelectBaseId;
                    close();
                    if (onSelected != null) onSelected.accept(baseId, chosenVal);
                    return true;
                }
            }
            close();
            return withinBounds;
        }

        public void renderOverlay(GuiGraphics guiGraphics, Font font, int webX, int webY, HtmlNode virtualRoot) {
            if (!isOpen()) return;
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(0, 0, com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.ZLayers.DROPDOWN_POPUP_OVERLAY_Z);

            int itemH = 18;
            int totalH = options.size() * itemH + 2;
            guiGraphics.fill(popupX, popupY, popupX + popupW, popupY + totalH, 0xFF89B4FA);
            guiGraphics.fill(popupX + 1, popupY + 1, popupX + popupW - 1, popupY + totalH - 1, 0xFF181825);

            String currentVal = getCurrentValue(virtualRoot);
            for (int i = 0; i < options.size(); i++) {
                SelectOption opt = options.get(i);
                int iy = popupY + 1 + i * itemH;
                boolean hov = webX >= popupX && webX <= popupX + popupW && webY >= iy && webY < iy + itemH;
                boolean isHighlighted = (i == highlightedIndex) || hov;
                boolean sel = opt.value().equals(currentVal);
                if (isHighlighted) guiGraphics.fill(popupX + 1, iy, popupX + popupW - 1, iy + itemH, 0xFF45475A);
                else if (sel) guiGraphics.fill(popupX + 1, iy, popupX + popupW - 1, iy + itemH, 0xFF313244);
                guiGraphics.drawString(font, opt.label(), popupX + 6, iy + 5, (isHighlighted || sel) ? 0xFF89B4FA : 0xFFCDD6F4, false);
            }
            pose.popPose();
        }

        private String getCurrentValue(HtmlNode virtualRoot) {
            HtmlNode node = virtualRoot != null ? virtualRoot.getNodeById(openSelectBaseId) : null;
            if (node == null) return "";
            if (node.attrs.containsKey("value") && !node.attrs.get("value").isEmpty()) return node.attrs.get("value");
            return !options.isEmpty() ? options.getFirst().value() : "";
        }
    }
}