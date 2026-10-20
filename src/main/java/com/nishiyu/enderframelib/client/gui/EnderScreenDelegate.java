package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.client.ClientDomEventDispatcher;
import com.nishiyu.enderframelib.client.gui.InvisibleInputScreen.WebTextInputHost;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.gui.TextareaEditor.TextEditState;
import com.nishiyu.enderframelib.client.ReactiveStateStore;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.event.CoreEvent.DomEvent;
import com.nishiyu.enderframelib.core.event.EventDispatcher;
import com.nishiyu.enderframelib.core.event.StandardDefaultActions;
import com.nishiyu.enderframelib.core.event.UiEvents;
import com.nishiyu.enderframelib.core.layout.DisplayItem.ElementType;
import com.nishiyu.enderframelib.core.layout.TextSelection;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
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
import com.nishiyu.enderframelib.network.EnderFrameNetwork.WebActionC2SPacket;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

public class EnderScreenDelegate {

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
            return router.dispatch(actionName);
        };
        ClientScreenInteractionManager.setClientEventCallback(this.eventCallback);
    }

    public WebViewport viewport() { return viewport; }
    public WebActionRouter router() { return router; }
    public WebSelectDropdownWidget selectWidget() { return selectWidget; }
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
            if (!ClientWebManager.getActiveInputId(sessionId).isEmpty() && !textareaEditor.isEditing()) {
                String baseId = ClientWebManager.getBaseNodeId(ClientWebManager.getActiveInputId(sessionId));
                syncInputValueToDomNodes(baseId, newText);
                if (this.state != null) this.state.notifyElementModelChanged(baseId, newText);
                if (onInputChanged != null) onInputChanged.accept(baseId, newText);
            }
        });
        return this.inputHost.editBox();
    }

    public void syncInputValueToDomNodes(String baseId, String safeVal) {
        String cleanVal = safeVal != null ? safeVal : "";
        HtmlNode vNode = ClientWebManager.findNode(sessionId, baseId);
        if (vNode != null) vNode.attrs.put("value", cleanVal);
        ClientScreenInteractionManager.updateInputLocally(sessionId, baseId + "_input", cleanVal);
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

        ClientWebManager.setActiveInputId(sessionId, renderInputId);
        syncInputValueToDomNodes(baseId, safeVal);
        if (this.inputHost != null) {
            this.inputHost.setValue(safeVal);
            if (screen != null) screen.setFocused(this.inputHost.editBox());
            this.inputHost.activate();
        }
    }

    public void unfocusWebInput() {
        if (!ClientWebManager.getActiveInputId(sessionId).isEmpty()) {
            String activeId = ClientWebManager.getActiveInputId(sessionId);
            String baseId = ClientWebManager.getBaseNodeId(activeId);
            if (textareaEditor.isEditing()) {
                String fullVal = textareaEditor.getValue(baseId);
                ClientScreenInteractionManager.submitInput(sessionId, baseId, fullVal);
                textareaEditor.unfocus();
            } else {
                String val = (this.inputHost != null) ? this.inputHost.getValue() : "";
                ClientScreenInteractionManager.submitInput(sessionId, baseId, val);
            }
            ClientWebManager.setActiveInputId(sessionId, "");
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
        ClientWebManager.setFocusedElement(sessionId, safeNext, focusVisible);
    }

    public void renderOverlays(GuiGraphics guiGraphics, Font font, List<DisplayItem> elements,
                               int webX, int webY, int virtualWidth, int virtualHeight, boolean devToolsEnabled) {
        RenderSystem.disableDepthTest();
        try {
            focusNavigator.renderFocusRing(guiGraphics, elements);
            selectWidget.renderOverlay(guiGraphics, font, webX, webY, ClientWebManager.getRootNode(sessionId));
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
            boolean handled = selectWidget.handleClick(webX, webY, (baseId, chosenVal) -> {
                commitValue(baseId, chosenVal, onInputChanged);
            });
            if (handled) return true;
        }

        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        DisplayItem clicked = hit != null ? hit.element() : null;

        if (button == 0 && clicked != null) {
            String elId = clicked.id();
            if (elId.endsWith("_sb_vt") || elId.endsWith("_sb_ht")) {
                ClientWebManager.handleScrollbarClick(sessionId, clicked, webX, webY);
                return true;
            } else if (elId.endsWith("_sb_v") || elId.endsWith("_sb_h")) {
                ClientScreenInteractionManager.setDraggingId(sessionId, elId);
                return true;
            }
        }

        HtmlNode pressedNode = clicked != null ? ClientWebManager.findInteractiveNode(sessionId, clicked.baseId()) : null;
        if (pressedNode != null) {
            ClientDomEventDispatcher.dispatchPress(pressedNode, hit.localX(), hit.localY(), button);
        }

        if (clicked != null && clicked.type() == ElementType.SCENE) {
            unfocusWebInput();
            String baseId = clicked.baseId();
            if (button == 2) {
                WebSceneContext.getOrCreate(sessionId, baseId).resetCamera();
            } else {
                activeSceneId = baseId;
                activeSceneMouseButton = button;
            }
            return true;
        }

        if (clicked != null && clicked.type() == ElementType.TEXTAREA && button == 0) {
            unfocusWebInput();
            String baseId = clicked.baseId();
            focusNavigator.setFocused(baseId);
            ClientWebManager.setFocusedElement(sessionId, baseId, false);
            textareaEditor.focus(font, clicked, hit.localX(), hit.localY());
            return true;
        }

        if (clicked != null && clicked.type() == ElementType.INPUT && button == 0) {
            String baseId = clicked.baseId();
            focusNavigator.setFocused(baseId);
            ClientWebManager.setFocusedElement(sessionId, baseId, false);
            String curVal = getInputValue(baseId);
            if (curVal.isEmpty() && clicked.text() != null) curVal = clicked.text();
            focusWebInput(screen, baseId, curVal);
            return true;
        } else {
            unfocusWebInput();
        }

        if (clicked != null && clicked.type() == ElementType.TEXT && button == 0) {
            TextSelection sel = ClientWebManager.getTextSelection(sessionId);
            if (sel != null && clicked.text() != null) {
                int charOffset = estimateCharOffset(font, clicked.text(), hit.localX());
                sel.startSelection(clicked.baseId(), charOffset);
            }
        }

        if (clicked != null) {
            String baseId = clicked.baseId();
            focusNavigator.setFocused(baseId);
            ClientWebManager.setFocusedElement(sessionId, baseId, false);
            ClientWebManager.setActivePressedId(sessionId, clicked.id());

            HtmlNode domNode = ClientWebManager.findInteractiveNode(sessionId, baseId);

            boolean isSelectElement = clicked.type() == ElementType.SELECT || (domNode != null && "select".equalsIgnoreCase(domNode.tag));
            if (isSelectElement && button == 0) {
                ClientScreenInteractionManager.playElementSound(clicked);
                selectWidget.open(baseId, clicked, ClientWebManager.getRootNode(sessionId));
                return true;
            }

            if (clicked.type() == ElementType.RANGE && button == 0) {
                ClientScreenInteractionManager.beginDrag(sessionId, webX, webY);
                DisplayItem updated = ClientWebManager.getElementById(sessionId, baseId);
                if (updated != null && updated.text() != null) {
                    String val = updated.text().split(":")[0];
                    commitValue(baseId, val, onInputChanged);
                }
                return true;
            }

            if (button == 0 && clicked.events() != null && "true".equalsIgnoreCase(clicked.events().get("draggable"))) {
                String label = (domNode != null && !domNode.getTextContent().isEmpty()) ? domNode.getTextContent().trim() : baseId;
                dragDropController.startDrag(baseId, label);
            }

            ClientScreenInteractionManager.playElementSound(clicked);
            boolean[] actionInvoked = new boolean[]{false};

            if (domNode != null) {
                if (button == 0) {
                    DomEvent clickEvent = DomEvent.click(hit.localX(), hit.localY(), 0);
                    StandardDefaultActions.Context defaults = ClientDomEventDispatcher.createClickDefaults(
                            sessionId, hit.localX(), hit.localY(), clicked, button, onInputChanged, onWebAction,
                            target -> handleMouseClicked(screen, font, hit.localX(), hit.localY(), 0, onInputChanged, onWebAction)
                    );
                    actionInvoked[0] = ClientDomEventDispatcher.dispatchWithActions(
                            sessionId, domNode, clickEvent, clicked, button, onWebAction, eventCallback,
                            evt -> StandardDefaultActions.execute(evt, defaults)
                    );
                    ClientDomEventDispatcher.dispatchDoubleClickIfRepeated(sessionId, domNode, hit.localX(), hit.localY());
                } else {
                    int domButton = ClientDomEventDispatcher.domButton(button);
                    if (domButton == 2) {
                        actionInvoked[0] = ClientDomEventDispatcher.dispatchWithActions(sessionId, domNode,
                                UiEvents.mouse("contextmenu", hit.localX(), hit.localY(), domButton, 0, false, false, false, false, null),
                                clicked, button, onWebAction, eventCallback, null);
                    }
                    actionInvoked[0] |= ClientDomEventDispatcher.dispatchWithActions(sessionId, domNode,
                            UiEvents.mouse("auxclick", hit.localX(), hit.localY(), domButton, 0, false, false, false, false, null),
                            clicked, button, onWebAction, eventCallback, null);
                }
            } else {
                String action = clicked.events() != null ? clicked.events().get("onclick") : null;
                if (action != null && onWebAction != null) {
                    onWebAction.onAction(action, clicked, button);
                    actionInvoked[0] = true;
                }
            }
            if (actionInvoked[0]) return true;
        }
        return false;
    }

    private void commitValue(String baseId, String value, BiConsumer<String, String> onInputChanged) {
        ClientScreenInteractionManager.submitInput(sessionId, baseId, value);
        ClientWebManager.updateNodeAttributeLocally(sessionId, baseId, "value", value, false);
        if (this.state != null) this.state.notifyElementModelChanged(baseId, value);
        if (onInputChanged != null) onInputChanged.accept(baseId, value);
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

        if (ClientScreenInteractionManager.handleDrag(sessionId, webX, webY)) {
            if (!ClientWebManager.getActivePressedId(sessionId).isEmpty()) {
                String baseId = ClientWebManager.getBaseNodeId(ClientWebManager.getActivePressedId(sessionId));
                DisplayItem updated = ClientWebManager.getElementById(sessionId, baseId);
                if (updated != null && updated.type() == ElementType.RANGE && updated.text() != null) {
                    String val = updated.text().split(":")[0];
                    if (this.state != null) this.state.notifyElementModelChanged(baseId, val);
                    if (onInputChanged != null) onInputChanged.accept(baseId, val);
                }
            }
            return true;
        }

        TextSelection sel = ClientWebManager.getTextSelection(sessionId);
        if (sel != null && !sel.anchorNodeId().isEmpty()) {
            ClientWebManager.UIHitResult dragHit = ClientWebManager.getHitElement(sessionId, webX, webY);
            if (dragHit != null && dragHit.element() != null && dragHit.element().text() != null) {
                if (sel.anchorNodeId().equals(dragHit.element().baseId())) {
                    Font font = Minecraft.getInstance().font;
                    int offset = estimateCharOffset(font, dragHit.element().text(), dragHit.localX());
                    sel.updateWithinNode(sel.anchorNodeId(), offset, dragHit.element().text());
                    return true;
                }
            }
        }
        return false;
    }

    public void handleMouseReleased(double webX, double webY, int button, WebDragDropController.DropCallback onWebDrop) {
        activeSceneId = "";
        ClientScreenInteractionManager.handleRelease(sessionId, webX, webY, button, false);
        dragDropController.handleRelease(sessionId, webX, webY, onWebDrop);
    }

    public boolean handleMouseScrolled(double webX, double webY, double scrollX, double scrollY) {
        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        if (hit != null && hit.element() != null) {
            String baseId = hit.element().baseId();
            HtmlNode domNode = ClientWebManager.findNode(sessionId, baseId);
            if (domNode != null) {
                var wheelEv = UiEvents.wheel(hit.localX(), hit.localY(), scrollX, scrollY, Screen.hasControlDown(), Screen.hasShiftDown(), Screen.hasAltDown(), false);
                domNode.dispatchEvent(wheelEv);
                if (wheelEv.isDefaultPrevented()) {
                    return true;
                }
            }
            if (hit.element().type() == ElementType.SCENE) {
                String sceneBaseId = hit.element().baseId();
                WebSceneContext.getOrCreate(sessionId, sceneBaseId).addZoom((float) (scrollY * 0.12));
                return true;
            }
            if (hit.element().type() == ElementType.TEXTAREA) {
                String taId = hit.element().id();
                int curScroll = TextEditState.getTextareaScrollRow(sessionId, taId);
                int nextScroll = Math.max(0, curScroll - (int) Math.signum(scrollY) * 3);
                TextEditState.setTextareaScrollRow(sessionId, taId, nextScroll);
                return true;
            }
        }
        return ClientWebManager.handleScrollLocally(sessionId, webX, webY, scrollX, scrollY);
    }

    public boolean handleCharTyped(char codePoint, int modifiers, BiConsumer<String, String> onInputChanged) {
        String data = String.valueOf(codePoint);
        if (textareaEditor.isEditing()) {
            String baseId = textareaEditor.activeBaseId();
            if (!ClientScreenInteractionManager.beginTextEdit(sessionId, baseId, data, "insertText")) return true;
            boolean handled = textareaEditor.handleCharTyped(codePoint, onInputChanged, () -> {
                if (this.state != null) this.state.notifyElementModelChanged(baseId, textareaEditor.getValue(baseId));
            });
            if (handled) {
                ClientScreenInteractionManager.commitTextEdit(sessionId, baseId, textareaEditor.getValue(baseId), data, "insertText");
            }
            return handled;
        } else if (!ClientWebManager.getActiveInputId(sessionId).isEmpty() && this.inputHost != null) {
            String baseId = ClientWebManager.getBaseNodeId(ClientWebManager.getActiveInputId(sessionId));
            if (!ClientScreenInteractionManager.beginTextEdit(sessionId, baseId, data, "insertText")) return true;
            if (this.inputHost.charTyped(codePoint, modifiers)) {
                ClientScreenInteractionManager.commitTextEdit(sessionId, baseId, this.inputHost.getValue(), data, "insertText");
                return true;
            }
        }
        return false;
    }

    private enum KeyboardMode {
        TEXTAREA_EDITING, SINGLE_LINE_INPUT, NAVIGATION
    }

    private KeyboardMode keyboardMode() {
        boolean hasActiveInput = !ClientWebManager.getActiveInputId(sessionId).isEmpty();
        if (!hasActiveInput) return KeyboardMode.NAVIGATION;
        return textareaEditor.isEditing() ? KeyboardMode.TEXTAREA_EDITING : KeyboardMode.SINGLE_LINE_INPUT;
    }

    private HtmlNode keyboardEventTarget() {
        String focusedId = focusNavigator.focusedId();
        HtmlNode target = focusedId != null && !focusedId.isEmpty() ? ClientWebManager.findNode(sessionId, focusedId) : null;
        return target != null ? target : ClientWebManager.getRootNode(sessionId);
    }

    private UIEvents.Keyboard dispatchKeyEvent(String type, int keyCode, int modifiers) {
        HtmlNode target = keyboardEventTarget();
        if (target == null) return null;
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0 || Screen.hasControlDown();
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 || Screen.hasShiftDown();
        boolean alt = (modifiers & GLFW.GLFW_MOD_ALT) != 0 || Screen.hasAltDown();
        boolean meta = (modifiers & GLFW.GLFW_MOD_SUPER) != 0;
        UIEvents.Keyboard event = UiEvents.keyboard(type, glfwKeyToDomKey(keyCode), glfwKeyToDomCode(keyCode), 0, ctrl, shift, alt, meta, false, false);
        target.dispatchEvent(event);
        return event;
    }

    public boolean handleKeyReleased(int keyCode, int modifiers) {
        UIEvents.Keyboard keyUp = dispatchKeyEvent("keyup", keyCode, modifiers);
        return keyUp != null && keyUp.isDefaultPrevented();
    }

    private static String editingInputType(int keyCode, int modifiers, boolean multiLine) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) return ctrl ? "deleteWordBackward" : "deleteContentBackward";
        if (keyCode == GLFW.GLFW_KEY_DELETE) return ctrl ? "deleteWordForward" : "deleteContentForward";
        if (ctrl && keyCode == GLFW.GLFW_KEY_V) return "insertFromPaste";
        if (ctrl && keyCode == GLFW.GLFW_KEY_X) return "deleteByCut";
        if (multiLine && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) return "insertLineBreak";
        return null;
    }

    public boolean handleKeyPressed(Screen screen, Font font, int keyCode, int scanCode, int modifiers,
                                    boolean blockInventoryKey,
                                    BiConsumer<String, String> onInputChanged,
                                    BiConsumer<String, String> onInputSubmitted,
                                    ClientDomEventDispatcher.TriActionCallback onWebAction) {
        UIEvents.Keyboard keyDown = dispatchKeyEvent("keydown", keyCode, modifiers);
        if (keyDown != null && keyDown.isDefaultPrevented()) {
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_TAB && !textareaEditor.isEditing()) {
            boolean reverse = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 || Screen.hasShiftDown();
            return focusNavigator.cycleSequentialFocus(screen, reverse, candidate -> {
                applyFocusCandidate(screen, candidate);
            });
        }

        switch (keyboardMode()) {
            case TEXTAREA_EDITING -> {
                String baseId = textareaEditor.activeBaseId();
                String inputType = editingInputType(keyCode, modifiers, true);
                if (inputType != null && !ClientScreenInteractionManager.beginTextEdit(sessionId, baseId, null, inputType)) return true;
                if (textareaEditor.handleKeyPressed(keyCode, onInputChanged, () -> {
                    if (this.state != null) this.state.notifyElementModelChanged(baseId, textareaEditor.getValue(baseId));
                })) {
                    ClientScreenInteractionManager.commitTextEdit(sessionId, baseId, textareaEditor.getValue(baseId), null, inputType != null ? inputType : "insertText");
                    return true;
                }
                if (blockInventoryKey && Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) return true;
            }
            case SINGLE_LINE_INPUT -> {
                String activeId = ClientWebManager.getActiveInputId(sessionId);
                String baseId = ClientWebManager.getBaseNodeId(activeId);
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    unfocusWebInput();
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    String val = (this.inputHost != null) ? this.inputHost.getValue() : "";
                    syncInputValueToDomNodes(baseId, val);
                    ClientScreenInteractionManager.submitInput(sessionId, activeId, val);
                    if (onInputSubmitted != null) onInputSubmitted.accept(baseId, val);
                    return true;
                }
                String inputType = editingInputType(keyCode, modifiers, false);
                if (inputType != null && !ClientScreenInteractionManager.beginTextEdit(sessionId, baseId, null, inputType)) return true;
                if (this.inputHost != null && this.inputHost.keyPressed(keyCode, scanCode, modifiers)) {
                    ClientScreenInteractionManager.commitTextEdit(sessionId, baseId, this.inputHost.getValue(), null, inputType != null ? inputType : "insertText");
                    return true;
                }
                if (blockInventoryKey && Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) return true;
            }
            case NAVIGATION -> { }
        }

        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            String focusedId = focusNavigator.focusedId();
            if (!focusedId.isEmpty()) {
                HtmlNode node = ClientWebManager.findNode(sessionId, focusedId);
                if (node != null && "input".equalsIgnoreCase(node.tag) && "range".equalsIgnoreCase(node.attrs.getOrDefault("type", ""))) {
                    int dir = (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_RIGHT) ? 1 : -1;
                    return adjustFocusedRangeSlider(focusedId, dir, onInputChanged);
                }
            }
            return focusNavigator.navigateSpatialFocus(screen, keyCode, candidate -> applyFocusCandidate(screen, candidate));
        }

        if (keyCode == GLFW.GLFW_KEY_C && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            TextSelection sel = ClientWebManager.getTextSelection(sessionId);
            if (sel != null && !sel.isEmpty()) {
                Minecraft.getInstance().keyboardHandler.setClipboard(sel.selectedText());
                return true;
            }
        }

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
            String focusedId = focusNavigator.focusedId();
            if (!focusedId.isEmpty()) {
                HtmlNode node = ClientWebManager.findNode(sessionId, focusedId);
                if (node != null) {
                    return dispatchActivateToNode(screen, font, node, onInputChanged, onWebAction);
                }
            }
        }

        return false;
    }

    private boolean dispatchActivateToNode(Screen screen, Font font, HtmlNode node,
                                           BiConsumer<String, String> onInputChanged,
                                           ClientDomEventDispatcher.TriActionCallback onWebAction) {
        DisplayItem el = ClientWebManager.getElementById(sessionId, node.getId());
        if (el != null) {
            ClientScreenInteractionManager.playElementSound(el);
        }
        DomEvent clickEvent = DomEvent.click(0);
        StandardDefaultActions.Context defaults = ClientDomEventDispatcher.createClickDefaults(
                sessionId, 0, 0, el, 0, onInputChanged, onWebAction,
                target -> dispatchActivateToNode(screen, font, target, onInputChanged, onWebAction)
        );
        return ClientDomEventDispatcher.dispatchWithActions(
                sessionId, node, clickEvent, el, 0, onWebAction, eventCallback,
                evt -> StandardDefaultActions.execute(evt, defaults)
        );
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
                    HtmlNode vNode = ClientWebManager.findNode(sessionId, baseId);
                    String val = (vNode != null) ? vNode.attrs.getOrDefault("value", "") : "";
                    focusWebInput(screen, baseId, val);
                    ClientWebManager.setFocusedElement(sessionId, baseId, true);
                }
            }
        }
    }

    private boolean adjustFocusedRangeSlider(String focusedId, int dir, BiConsumer<String, String> onInputChanged) {
        HtmlNode node = ClientWebManager.findNode(sessionId, focusedId);
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

        ClientWebManager.updateNodeAttributeLocally(sessionId, focusedId, "value", formatted, false);
        if (this.state != null) this.state.notifyElementModelChanged(focusedId, formatted);
        if (onInputChanged != null) onInputChanged.accept(focusedId, formatted);
        return true;
    }

    public void setElementText(String id, String text) {
        HtmlNode target = ClientWebManager.findNode(sessionId, id);
        if (target != null) {
            target.setTextContent(text);
            ClientWebManager.requestRender(sessionId);
        }
    }

    public void setInputValue(String id, String value) {
        String baseId = ClientWebManager.getBaseNodeId(id);
        String safeVal = value != null ? value : "";
        syncInputValueToDomNodes(baseId, safeVal);
        if (!textareaEditor.isEditing() && ClientWebManager.isFocusedBase(sessionId, baseId)) {
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
        if (!textareaEditor.isEditing() && ClientWebManager.isFocusedBase(sessionId, baseId)) {
            if (this.inputHost != null) return this.inputHost.getValue();
        }
        HtmlNode node = ClientWebManager.findNode(sessionId, baseId);
        return node != null ? node.attrs.getOrDefault("value", "") : "";
    }

    public void addClass(String id, String cls) {
        HtmlNode target = ClientWebManager.findNode(sessionId, id);
        if (target != null && !target.hasClass(cls)) {
            target.addClass(cls);
            ClientWebManager.requestRender(sessionId);
        }
    }

    public void removeClass(String id, String cls) {
        HtmlNode target = ClientWebManager.findNode(sessionId, id);
        if (target != null && target.hasClass(cls)) {
            target.removeClass(cls);
            ClientWebManager.requestRender(sessionId);
        }
    }

    public void toggleClass(String id, String cls, boolean enable) {
        if (enable) addClass(id, cls);
        else removeClass(id, cls);
    }

    public void onClose() {
        ClientScreenInteractionManager.removeClientEventCallback(this.eventCallback);
        unfocusWebInput();
        setFocusedDomElement("");
        activeSceneId = "";
        selectWidget.close();
        dragDropController.clear();
        ClientWebManager.setActivePressedId(sessionId, "");
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
                HtmlNode domNode = ClientWebManager.findNode(sessionId, targetAnchor.id());
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
            CompoundTag tag = new CompoundTag();
            tag.putString("ElementId", "");
            tag.putString("EventName", "rpc");
            tag.putString("ActionName", actionName != null ? actionName : "");
            tag.putString("Value", value != null ? String.valueOf(value) : "");
            ClientScreenInteractionManager.sendToServer(new WebActionC2SPacket(this.sessionId, "ui_event", tag));
        }

        public boolean dispatch(String action) {
            if (action == null || action.isEmpty()) return false;

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

            if (fullscreenResponsive) {
                float aspect = (screenH > 0) ? ((float) screenW / (float) screenH) : (16.0f / 9.0f);
                targetW = Math.max(854, Math.round(480 * aspect));
                targetH = Math.max(480, Math.round(targetW / aspect));

                if (currentVirtualW != targetW || currentVirtualH != targetH) {
                    if (sessionId != null && ClientWebManager.hasDocument(sessionId)) {
                        ClientWebManager.recomputeLayout(sessionId, targetW, targetH);
                    }
                }
            }

            this.virtualWidth = targetW;
            this.virtualHeight = targetH;
            float scaleX = (screenW * marginRatio) / Math.max(1, targetW);
            float scaleY = (screenH * marginRatio) / Math.max(1, targetH);
            this.guiScaleFactor = Math.min(scaleX, scaleY);
            this.offsetX = Math.round((screenW - targetW * this.guiScaleFactor) / 2.0f);
            this.offsetY = Math.round((screenH - targetH * this.guiScaleFactor) / 2.0f);
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
            ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
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
        private final List<SelectOption> options = new ArrayList<>();

        public boolean isOpen() { return !openSelectBaseId.isEmpty() && !options.isEmpty(); }
        public String openSelectBaseId() { return openSelectBaseId; }

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
            }
        }

        public void close() {
            this.openSelectBaseId = "";
            this.options.clear();
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
                boolean sel = opt.value().equals(currentVal);
                if (hov) guiGraphics.fill(popupX + 1, iy, popupX + popupW - 1, iy + itemH, 0xFF45475A);
                else if (sel) guiGraphics.fill(popupX + 1, iy, popupX + popupW - 1, iy + itemH, 0xFF313244);
                guiGraphics.drawString(font, opt.label(), popupX + 6, iy + 5, sel ? 0xFF89B4FA : 0xFFCDD6F4, false);
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