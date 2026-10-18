package com.nishiyu.enderframelib.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.TextEditState;
import com.nishiyu.enderframelib.client.binding.WebStateStore;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.event.DomEvent;
import com.nishiyu.enderframelib.core.event.EventDispatcher;
import com.nishiyu.enderframelib.core.event.StandardDefaultActions;
import com.nishiyu.enderframelib.core.model.UIElement.ElementType;
import com.nishiyu.enderframelib.core.model.TextSelection;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
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
    private final ClientScreenInteractionManager.IClientEventCallback eventCallback;

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

        // 何が壊れていたか: 以前はこの画面が独自にホバーを追跡して
        // onmouseenter / onmouseleave を発火し、ClientScreenInteractionManager 側も
        // 同じイベントを発火していたため、1 回のホバーで 2 回飛んでいた
        // （server: アクションならサーバーにも 2 回届いた）。
        // 追跡は ClientScreenInteractionManager に 1 本化し、ローカルのルータは
        // そこからのコールバックで受ける。
        this.eventCallback = (sid, elementId, eventName, actionName, value) -> {
            if (!this.sessionId.equals(sid)) return false;
            return router.dispatch(actionName);
        };
        ClientScreenInteractionManager.setClientEventCallback(this.eventCallback);
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
            if (!ClientWebManager.getActivePressedId(sessionId).isEmpty()) {
                String baseId = ClientWebManager.getBaseNodeId(ClientWebManager.getActivePressedId(sessionId));
                UIElement updated = ClientWebManager.getElementById(sessionId, baseId);
                if (updated != null && updated.type() == ElementType.RANGE && updated.text() != null) {
                    String val = updated.text().split(":")[0];
                    if (this.state != null) this.state.notifyElementModelChanged(baseId, val);
                    if (onInputChanged != null) onInputChanged.accept(baseId, val);
                }
            }
            return true;
        }

        // テキスト選択のドラッグ更新
        TextSelection sel = ClientWebManager.getTextSelection(sessionId);
        if (sel != null && !sel.anchorNodeId().isEmpty()) {
            ClientWebManager.UIHitResult dragHit = ClientWebManager.getHitElement(sessionId, webX, webY);
            if (dragHit != null && dragHit.element() != null && dragHit.element().text() != null) {
                if (sel.anchorNodeId().equals(dragHit.element().baseId())) {
                    Font font = Minecraft.getInstance().font;
                    int offset = InteractionHandler.estimateCharOffset(font, dragHit.element().text(), dragHit.localX());
                    sel.updateWithinNode(sel.anchorNodeId(), offset, dragHit.element().text());
                    return true;
                }
            }
        }
        return false;
    }

    public void handleMouseReleased(double webX, double webY, DragDropController.DropCallback onWebDrop) {
        interactionHandler.clearScene();
        ClientWebManager.setActivePressedId(sessionId, "");
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
        } else if (!ClientWebManager.getActiveInputId(sessionId).isEmpty()) {
            if (this.inputHost != null && this.inputHost.charTyped(codePoint, modifiers)) return true;
        }
        return false;
    }

    /**
     * キー入力をどう解釈するか。以前は
     * {@code textareaEditor.isEditing()} と {@code activeInputId.isEmpty()} の
     * 組み合わせが条件式に埋まっていて、どの状態が有効なのか読み取れなかった。
     */
    private enum KeyboardMode {
        /** 複数行テキストエリアの編集中。キーはエディタが食う。 */
        TEXTAREA_EDITING,
        /** 単一行入力にフォーカスがある。Enter で確定、ESC で解除。 */
        SINGLE_LINE_INPUT,
        /** 入力にフォーカスが無い。Tab / 矢印でのフォーカス移動と Enter/Space の起動。 */
        NAVIGATION
    }

    private KeyboardMode keyboardMode() {
        boolean hasActiveInput = !ClientWebManager.getActiveInputId(sessionId).isEmpty();
        if (!hasActiveInput) return KeyboardMode.NAVIGATION;
        return textareaEditor.isEditing() ? KeyboardMode.TEXTAREA_EDITING : KeyboardMode.SINGLE_LINE_INPUT;
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

        switch (keyboardMode()) {
            case TEXTAREA_EDITING -> {
                if (textareaEditor.handleKeyPressed(keyCode, onInputChanged, () -> {
                    if (this.state != null) this.state.notifyElementModelChanged(textareaEditor.activeBaseId(), textareaEditor.getValue(textareaEditor.activeBaseId()));
                })) return true;
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
                if (this.inputHost != null && this.inputHost.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
                if (blockInventoryKey && Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) return true;
            }
            // フォーカス移動とウィジェット起動は以降の共通処理が扱う。
            case NAVIGATION -> { }
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
                HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), focusedId);
                if (node != null) {
                    return dispatchActivateToNode(screen, font, node, onInputChanged, onWebAction);
                }
            }
        }

        return false;
    }

    private boolean dispatchActivateToNode(Screen screen, Font font, HtmlNode node,
                                           BiConsumer<String, String> onInputChanged,
                                           InteractionHandler.TriActionCallback onWebAction) {
        UIElement el = ClientWebManager.getElementById(sessionId, node.getId());
        if (el != null) {
            ClientScreenInteractionManager.playElementSound(el);
        }
        DomEvent clickEvent = DomEvent.click(0);
        boolean[] handled = new boolean[]{false};
        EventDispatcher.dispatchEvent(node, clickEvent, (targetNode, action) -> {
            handled[0] = true;
            if (onWebAction != null) {
                onWebAction.onAction(action, el, 0);
            }
            ClientScreenInteractionManager.triggerLocalEvent(
                    sessionId, targetNode.getId(), "onclick", action, targetNode.getId() + "|0.0,0.0"
            );
        }, (evt) -> {
            StandardDefaultActions.execute(evt, new StandardDefaultActions.Context() {
                @Override
                public void onAttributeChanged(HtmlNode n, String attrName, String attrValue) {
                    if (attrValue == null) {
                        ClientWebManager.updateNodeAttributeLocally(sessionId, n.getId(), attrName, null, true);
                    } else {
                        ClientWebManager.updateNodeAttributeLocally(sessionId, n.getId(), attrName, attrValue, false);
                    }
                    if ("checked".equalsIgnoreCase(attrName)) {
                        commitInputValue(n.getId(), Boolean.toString(attrValue != null), onInputChanged);
                    }
                }

                @Override
                public void dispatchClickTo(HtmlNode target) {
                    if (target != null) {
                        dispatchActivateToNode(screen, font, target, onInputChanged, onWebAction);
                    }
                }

                @Override
                public void navigateTo(String href) {
                    if (onWebAction != null) {
                        onWebAction.onAction(href, el, 0);
                    }
                }

                @Override
                public void executeFormAction(HtmlNode form, String eventType) {
                    String act = form.attrs.get(eventType);
                    if (act != null && onWebAction != null) {
                        onWebAction.onAction(act, el, 0);
                    }
                }
            });
        });
        return handled[0];
    }

    private void commitInputValue(String baseId, String value, BiConsumer<String, String> onInputChanged) {
        ClientScreenInteractionManager.submitInput(sessionId, baseId, value);
        ClientWebManager.updateNodeAttributeLocally(sessionId, baseId, "value", value, false);
        if (this.state != null) this.state.notifyElementModelChanged(baseId, value);
        if (onInputChanged != null) onInputChanged.accept(baseId, value);
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
                    HtmlNode vNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
                    String val = (vNode != null) ? vNode.attrs.getOrDefault("value", "") : "";
                    focusWebInput(screen, baseId, val);
                    ClientWebManager.setFocusedElement(sessionId, baseId, true);
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
        ClientScreenInteractionManager.removeClientEventCallback(this.eventCallback);
        unfocusWebInput();
        setFocusedDomElement("");
        interactionHandler.clearScene();
        selectWidget.close();
        dragDropController.clear();
        ClientWebManager.setActivePressedId(sessionId, "");
    }

    /**
     * F12キー押下時のDevToolsインスペクタ（UI要素・DOM属性・寸法・マージンの可視化）描画を担当するクラス。
     */
    public static class WebDevTools {

        public static void renderInspector(
                GuiGraphics guiGraphics, Font font, List<UIElement> elements,
                int webX, int webY, int virtualWidth, int virtualHeight,
                String sessionId
        ) {
            if (elements == null) return;

            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(0, 0, com.nishiyu.enderframelib.client.renderer.WebRenderCore.ZLayers.DEVTOOLS_OVERLAY_Z);

            String banner = " [F12] EnderFrame DevTools | [F5] Hot Reload | [Tab/Arrows] Focus | Viewport: "
                    + virtualWidth + "x" + virtualHeight + " | Mouse: (" + webX + ", " + webY + ") ";
            guiGraphics.fill(0, 0, font.width(banner) + 8, 13, 0xDD11111B);
            guiGraphics.drawString(font, banner, 4, 2, 0xFFA6E3A1, false);

            UIElement targetAnchor = null;
            for (int i = elements.size() - 1; i >= 0; i--) {
                UIElement el = elements.get(i);
                if (el.type() == ElementType.NODE && !"html_root".equals(el.id()) && el.width() > 0 && el.height() > 0) {
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
}
