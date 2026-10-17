package com.nishiyu.enderframelib.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.binding.WebStateStore;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.ElementType;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * AbstractWebScreen と AbstractWebContainerScreen の共通UI処理を集約する委譲クラス。
 */
public class WebScreenDelegate {

    private final String sessionId;
    private final WebStateStore state;
    private final WebViewport viewport;
    private final WebActionRouter router;
    private final SelectDropdownWidget selectWidget;
    private final DragDropController dragDropController;
    private final WebFocusNavigator focusNavigator;
    private final WebTextareaEditor textareaEditor;
    private final InteractionHandler interactionHandler;

    private WebTextInputHost inputHost;
    private String lastHoveredElementId = "";

    public WebScreenDelegate(String sessionId, WebStateStore state) {
        this.sessionId = sessionId;
        this.state = state;
        this.viewport = new WebViewport();
        this.router = new WebActionRouter(sessionId);
        this.selectWidget = new SelectDropdownWidget();
        this.dragDropController = new DragDropController();
        this.focusNavigator = new WebFocusNavigator(sessionId);
        this.textareaEditor = new WebTextareaEditor(sessionId);
        this.interactionHandler = new InteractionHandler(sessionId, state);
    }

    public WebViewport viewport() { return viewport; }
    public WebActionRouter router() { return router; }
    public SelectDropdownWidget selectWidget() { return selectWidget; }
    public DragDropController dragDropController() { return dragDropController; }
    public WebFocusNavigator focusNavigator() { return focusNavigator; }
    public WebTextareaEditor textareaEditor() { return textareaEditor; }
    public InteractionHandler interactionHandler() { return interactionHandler; }

    public void updateViewport(int screenW, int screenH, int currentVirtualW, int currentVirtualH, boolean fullscreenResponsive, float marginRatio) {
        viewport.update(screenW, screenH, currentVirtualW, currentVirtualH, fullscreenResponsive, marginRatio, sessionId);
    }

    public EditBox createHiddenEditBox(Font font, BiConsumer<String, String> onInputChanged) {
        this.inputHost = new WebTextInputHost(font, newText -> {
            if (!WebRenderCore.activeInputId.isEmpty() && !textareaEditor.isEditing()) {
                String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId);
                syncInputValueToDomNodes(baseId, newText);
                if (this.state != null) this.state.notifyElementModelChanged(baseId, newText);
                if (onInputChanged != null) onInputChanged.accept(baseId, newText);
            }
        });
        return this.inputHost.editBox();
    }

    public void syncInputValueToDomNodes(String baseId, String safeVal) {
        String cleanVal = safeVal != null ? safeVal : "";
        HtmlNode vNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
        if (vNode != null) vNode.attrs.put("value", cleanVal);
        HtmlNode tNode = DomUtils.findNodeById(ClientWebManager.getTemplateRoot(sessionId), baseId);
        if (tNode != null) tNode.attrs.put("value", cleanVal);
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

        WebRenderCore.activeInputId = renderInputId;
        syncInputValueToDomNodes(baseId, safeVal);
        if (this.inputHost != null) {
            this.inputHost.setValue(safeVal);
            if (screen != null) screen.setFocused(this.inputHost.editBox());
            this.inputHost.activate();
        }
    }

    public void unfocusWebInput() {
        if (!WebRenderCore.activeInputId.isEmpty()) {
            String activeId = WebRenderCore.activeInputId;
            String baseId = ClientWebManager.getBaseNodeId(activeId);
            if (textareaEditor.isEditing()) {
                String fullVal = textareaEditor.getValue(baseId);
                ClientScreenInteractionManager.submitInput(sessionId, baseId, fullVal);
                textareaEditor.unfocus();
            } else {
                String val = (this.inputHost != null) ? this.inputHost.getValue() : "";
                ClientScreenInteractionManager.submitInput(sessionId, baseId, val);
            }
            WebRenderCore.activeInputId = "";
        }
        if (this.inputHost != null) {
            this.inputHost.deactivate();
        }
    }

    public void setFocusedDomElement(String baseId) {
        String safeNext = baseId != null ? baseId : "";
        if (Objects.equals(focusNavigator.focusedId(), safeNext)) return;
        focusNavigator.setFocused(safeNext);
        HtmlNode vRoot = ClientWebManager.getVirtualRoot(sessionId);
        if (vRoot != null) {
            ClientWebManager.requestRender(sessionId);
        }
    }

    public void updateHoverState(double webX, double webY) {
        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        String currentHoveredId = (hit != null && hit.element() != null) ? hit.element().baseId() : "";

        if (!currentHoveredId.equals(lastHoveredElementId)) {
            if (!lastHoveredElementId.isEmpty()) {
                triggerLocalEvent(lastHoveredElementId, "onmouseleave", null);
            }
            if (!currentHoveredId.isEmpty()) {
                triggerLocalEvent(currentHoveredId, "onmouseenter", null);
            }
            lastHoveredElementId = currentHoveredId;
        }
    }

    private void triggerLocalEvent(String elementId, String eventType, String value) {
        HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), elementId);
        if (node != null && node.attrs.containsKey(eventType)) {
            String action = node.attrs.get(eventType);
            router.dispatch(action);
        }
    }

    public void renderOverlays(GuiGraphics guiGraphics, Font font, List<UIElement> elements,
                               int webX, int webY, int virtualWidth, int virtualHeight, boolean devToolsEnabled) {
        RenderSystem.disableDepthTest();
        try {
            focusNavigator.renderFocusRing(guiGraphics, elements);
            selectWidget.renderOverlay(guiGraphics, font, webX, webY, ClientWebManager.getVirtualRoot(sessionId));
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
            InteractionHandler.TriActionCallback onWebAction
    ) {
        return interactionHandler.handleMouseClicked(
                screen, font, webX, webY, button,
                textareaEditor, selectWidget, dragDropController, focusNavigator,
                (id, val) -> focusWebInput(screen, id, val),
                this::unfocusWebInput,
                onInputChanged,
                onWebAction
        );
    }

    public boolean handleMouseDragged(double webX, double webY, double dragX, double dragY, float guiScaleFactor, BiConsumer<String, String> onInputChanged) {
        if (!interactionHandler.activeSceneId().isEmpty()) {
            WebSceneContext scene = WebSceneContext.getOrCreate(sessionId, interactionHandler.activeSceneId());
            if (interactionHandler.activeSceneMouseButton() == 0) {
                scene.addRotation((float) (dragY * 0.8), (float) (dragX * 0.8));
            } else if (interactionHandler.activeSceneMouseButton() == 1) {
                scene.addPan((float) (dragX / guiScaleFactor), (float) (dragY / guiScaleFactor));
            }
            return true;
        }

        if (ClientScreenInteractionManager.handleDrag(sessionId, webX, webY)) {
            if (!WebRenderCore.activePressedId.isEmpty()) {
                String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activePressedId);
                UIElement updated = ClientWebManager.getElementById(sessionId, baseId);
                if (updated != null && updated.type() == ElementType.RANGE && updated.text() != null) {
                    String val = updated.text().split(":")[0];
                    if (this.state != null) this.state.notifyElementModelChanged(baseId, val);
                    if (onInputChanged != null) onInputChanged.accept(baseId, val);
                }
            }
            return true;
        }
        return false;
    }

    public void handleMouseReleased(double webX, double webY, DragDropController.DropCallback onWebDrop) {
        interactionHandler.clearScene();
        WebRenderCore.activePressedId = "";
        ClientScreenInteractionManager.handleRelease(sessionId);
        dragDropController.handleRelease(sessionId, webX, webY, onWebDrop);
    }

    public boolean handleMouseScrolled(double webX, double webY, double scrollX, double scrollY) {
        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        if (hit != null && hit.element() != null) {
            if (hit.element().type() == ElementType.SCENE) {
                String baseId = hit.element().baseId();
                WebSceneContext.getOrCreate(sessionId, baseId).addZoom((float) (scrollY * 0.12));
                return true;
            }
            if (hit.element().type() == ElementType.TEXTAREA) {
                String taId = hit.element().id();
                int curScroll = TextEditState.getTextareaScrollRow(taId);
                int nextScroll = Math.max(0, curScroll - (int) Math.signum(scrollY) * 3);
                TextEditState.setTextareaScrollRow(taId, nextScroll);
                return true;
            }
        }
        return ClientWebManager.handleScrollLocally(sessionId, webX, webY, scrollX, scrollY);
    }

    public boolean handleCharTyped(char codePoint, int modifiers, BiConsumer<String, String> onInputChanged) {
        if (textareaEditor.isEditing()) {
            return textareaEditor.handleCharTyped(codePoint, onInputChanged, () -> {
                if (this.state != null) this.state.notifyElementModelChanged(textareaEditor.activeBaseId(), textareaEditor.getValue(textareaEditor.activeBaseId()));
            });
        } else if (!WebRenderCore.activeInputId.isEmpty()) {
            if (this.inputHost != null && this.inputHost.charTyped(codePoint, modifiers)) return true;
        }
        return false;
    }

    public boolean handleKeyPressed(Screen screen, Font font, int keyCode, int scanCode, int modifiers,
                                   boolean blockInventoryKey,
                                   BiConsumer<String, String> onInputChanged,
                                   BiConsumer<String, String> onInputSubmitted,
                                   InteractionHandler.TriActionCallback onWebAction) {
        if (keyCode == GLFW.GLFW_KEY_TAB && !textareaEditor.isEditing()) {
            boolean reverse = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 || Screen.hasShiftDown();
            return focusNavigator.cycleSequentialFocus(screen, reverse, candidate -> {
                applyFocusCandidate(screen, candidate);
            });
        }

        if (textareaEditor.isEditing() && !WebRenderCore.activeInputId.isEmpty()) {
            if (textareaEditor.handleKeyPressed(keyCode, onInputChanged, () -> {
                if (this.state != null) this.state.notifyElementModelChanged(textareaEditor.activeBaseId(), textareaEditor.getValue(textareaEditor.activeBaseId()));
            })) return true;
            if (blockInventoryKey && Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) return true;
        } else if (!WebRenderCore.activeInputId.isEmpty()) {
            String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId);
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                unfocusWebInput();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                String val = (this.inputHost != null) ? this.inputHost.getValue() : "";
                syncInputValueToDomNodes(baseId, val);
                ClientScreenInteractionManager.submitInput(sessionId, WebRenderCore.activeInputId, val);
                if (onInputSubmitted != null) onInputSubmitted.accept(baseId, val);
                return true;
            }
            if (this.inputHost != null && this.inputHost.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            if (blockInventoryKey && Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) return true;
        }

        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            String focusedId = focusNavigator.focusedId();
            if (!focusedId.isEmpty()) {
                HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), focusedId);
                if (node != null && "input".equalsIgnoreCase(node.tag) && "range".equalsIgnoreCase(node.attrs.getOrDefault("type", ""))) {
                    int dir = (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_RIGHT) ? 1 : -1;
                    return adjustFocusedRangeSlider(focusedId, dir, onInputChanged);
                }
            }
            return focusNavigator.navigateSpatialFocus(screen, keyCode, candidate -> {
                applyFocusCandidate(screen, candidate);
            });
        }

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
            String focusedId = focusNavigator.focusedId();
            if (!focusedId.isEmpty()) {
                UIElement el = ClientWebManager.getElementById(sessionId, focusedId);
                if (el != null) {
                    double cx = el.x() + el.width() / 2.0;
                    double cy = el.y() + el.height() / 2.0;
                    return handleMouseClicked(screen, font, cx, cy, 0, onInputChanged, onWebAction);
                }
            }
        }

        return false;
    }

    private void applyFocusCandidate(Screen screen, WebFocusNavigator.FocusCandidate candidate) {
        unfocusWebInput();
        String baseId = candidate.node().getId();
        setFocusedDomElement(baseId);
        ClientWebManager.processRenderQueue();

        HtmlNode node = candidate.node();
        if ("input".equalsIgnoreCase(node.tag)) {
            String inputType = node.attrs.getOrDefault("type", "text").toLowerCase(Locale.ROOT);
            if (!"checkbox".equals(inputType) && !"radio".equals(inputType) && !"range".equals(inputType) && !"color".equals(inputType)) {
                if (screen != null) {
                    HtmlNode vNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
                    String val = (vNode != null) ? vNode.attrs.getOrDefault("value", "") : "";
                    focusWebInput(screen, baseId, val);
                }
            }
        }
    }

    private boolean adjustFocusedRangeSlider(String focusedId, int dir, BiConsumer<String, String> onInputChanged) {
        HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), focusedId);
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
        ClientWebManager.processRenderQueue();
        if (this.state != null) this.state.notifyElementModelChanged(focusedId, formatted);
        if (onInputChanged != null) onInputChanged.accept(focusedId, formatted);
        return true;
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
        if (!WebRenderCore.activeInputId.isEmpty() && !textareaEditor.isEditing() && ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId).equals(baseId)) {
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
        if (!WebRenderCore.activeInputId.isEmpty() && !textareaEditor.isEditing() && ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId).equals(baseId)) {
            if (this.inputHost != null) return this.inputHost.getValue();
        }
        HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
        return node != null ? node.attrs.getOrDefault("value", "") : "";
    }

    public void addClass(String id, String cls) {
        HtmlNode root = ClientWebManager.getVirtualRoot(sessionId);
        if (root == null) return;
        HtmlNode target = DomUtils.findNodeById(root, id);
        if (target != null && !target.hasClass(cls)) {
            target.addClass(cls);
            ClientWebManager.requestRender(sessionId);
        }
    }

    public void removeClass(String id, String cls) {
        HtmlNode root = ClientWebManager.getVirtualRoot(sessionId);
        if (root == null) return;
        HtmlNode target = DomUtils.findNodeById(root, id);
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
        unfocusWebInput();
        setFocusedDomElement("");
        interactionHandler.clearScene();
        selectWidget.close();
        dragDropController.clear();
        WebRenderCore.activePressedId = "";
    }
}
