package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.binding.WebStateStore;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.ElementType;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;

import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * クリックインタラクションのディスパッチ（scene, textarea, input, summary, select, controls, draggable, onclick）。
 */
public class InteractionHandler {

    @FunctionalInterface
    public interface TriActionCallback {
        void onAction(String action, UIElement clickedElement, int mouseButton);
    }

    private final String sessionId;
    private final WebStateStore state;
    private String activeSceneId = "";
    private int activeSceneMouseButton = 0;

    public InteractionHandler(String sessionId, WebStateStore state) {
        this.sessionId = sessionId;
        this.state = state;
    }

    public String activeSceneId() {
        return activeSceneId;
    }

    public int activeSceneMouseButton() {
        return activeSceneMouseButton;
    }

    public void clearScene() {
        this.activeSceneId = "";
    }

    public boolean handleMouseClicked(
            Screen screen, Font font, double webX, double webY, int button,
            WebTextareaEditor textareaEditor,
            SelectDropdownWidget selectWidget,
            DragDropController dragDropController,
            WebFocusNavigator focusNavigator,
            BiConsumer<String, String> focusInputCallback,
            Runnable unfocusInputCallback,
            BiConsumer<String, String> onInputChanged,
            TriActionCallback onWebAction
    ) {
        // 1. セレクトドロップダウンのクリック判定
        if (selectWidget.isOpen() && button == 0) {
            boolean handled = selectWidget.handleClick(webX, webY, (baseId, chosenVal) -> {
                commitValue(baseId, chosenVal, onInputChanged);
            });
            if (handled) return true;
        }

        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        UIElement clicked = hit != null ? hit.element() : null;

        // 2. scene 要素
        if (clicked != null && clicked.type() == ElementType.SCENE) {
            if (unfocusInputCallback != null) unfocusInputCallback.run();
            String baseId = clicked.baseId();
            if (button == 2) {
                WebSceneContext.getOrCreate(sessionId, baseId).resetCamera();
            } else {
                activeSceneId = baseId;
                activeSceneMouseButton = button;
            }
            return true;
        }

        // 3. textarea 要素
        if (clicked != null && clicked.type() == ElementType.TEXTAREA && button == 0) {
            if (unfocusInputCallback != null) unfocusInputCallback.run();
            String baseId = clicked.baseId();
            focusNavigator.setFocused(baseId);
            textareaEditor.focus(font, clicked, hit.localX(), hit.localY());
            return true;
        }

        // 4. input 要素
        if (clicked != null && clicked.type() == ElementType.INPUT && button == 0) {
            String baseId = clicked.baseId();
            focusNavigator.setFocused(baseId);
            String curVal = getInputValue(baseId);
            if (curVal.isEmpty() && clicked.text() != null) curVal = clicked.text();
            if (focusInputCallback != null) focusInputCallback.accept(baseId, curVal);
            return true;
        } else {
            if (unfocusInputCallback != null) unfocusInputCallback.run();
        }

        if (clicked != null) {
            String baseId = clicked.baseId();
            focusNavigator.setFocused(baseId);
            WebRenderCore.activePressedId = clicked.id();

            HtmlNode domNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);

            // 5. details/summary 要素
            HtmlNode summaryNode = findAncestorByTag(domNode, "summary");
            if (summaryNode != null && summaryNode.parent != null && "details".equalsIgnoreCase(summaryNode.parent.tag) && button == 0) {
                ClientScreenInteractionManager.playElementSound(clicked);
                ClientWebManager.toggleNodeAttributeLocally(sessionId, summaryNode.parent.getId(), "open");
                ClientWebManager.processRenderQueue();
                return true;
            }

            // 6. label[for] 要素
            HtmlNode labelNode = findAncestorByTag(domNode, "label");
            if (labelNode != null && labelNode.attrs.containsKey("for") && button == 0) {
                String targetId = labelNode.attrs.get("for");
                UIElement targetEl = ClientWebManager.getElementById(sessionId, targetId);
                if (targetEl != null) {
                    double cx = targetEl.x() + targetEl.width() / 2.0;
                    double cy = targetEl.y() + targetEl.height() / 2.0;
                    return handleMouseClicked(screen, font, cx, cy, 0, textareaEditor, selectWidget, dragDropController,
                            focusNavigator, focusInputCallback, unfocusInputCallback, onInputChanged, onWebAction);
                }
            }

            // 7. select 要素
            boolean isSelectElement = clicked.type() == ElementType.SELECT || (domNode != null && "select".equalsIgnoreCase(domNode.tag));
            if (isSelectElement && button == 0) {
                ClientScreenInteractionManager.playElementSound(clicked);
                selectWidget.open(baseId, clicked, ClientWebManager.getVirtualRoot(sessionId));
                return true;
            }

            // 8. range / toggle / checkbox 要素
            if ((clicked.type() == ElementType.RANGE || clicked.type() == ElementType.TOGGLE
                    || clicked.type() == ElementType.CHECKBOX || clicked.type() == ElementType.RADIO) && button == 0) {
                ClientScreenInteractionManager.handleClick(sessionId, webX, webY);
                if (clicked.type() == ElementType.RANGE) {
                    UIElement updated = ClientWebManager.getElementById(sessionId, baseId);
                    if (updated != null && updated.text() != null) {
                        String val = updated.text().split(":")[0];
                        commitValue(baseId, val, onInputChanged);
                    }
                } else {
                    HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
                    String checkedStr = Boolean.toString(node != null && node.attrs.containsKey("checked"));
                    commitValue(baseId, checkedStr, onInputChanged);
                }
                return true;
            }

            // 9. draggable 要素
            if (button == 0 && clicked.events() != null && "true".equalsIgnoreCase(clicked.events().get("draggable"))) {
                String label = (domNode != null && !domNode.getTextContent().isEmpty()) ? domNode.getTextContent().trim() : baseId;
                dragDropController.startDrag(baseId, label);
            }

            // 10. onclick / form submit
            ClientScreenInteractionManager.handleClick(sessionId, webX, webY);
            String action = clicked.events() != null ? clicked.events().get("onclick") : null;
            if ((action == null || action.equals(baseId)) && domNode != null && "button".equalsIgnoreCase(domNode.tag)) {
                String btnType = domNode.attrs.getOrDefault("type", "").toLowerCase(Locale.ROOT);
                HtmlNode parentForm = findAncestorByTag(domNode, "form");
                if (parentForm != null) {
                    if ("submit".equals(btnType) && parentForm.attrs.containsKey("onsubmit")) {
                        action = parentForm.attrs.get("onsubmit");
                    } else if ("reset".equals(btnType) && parentForm.attrs.containsKey("onreset")) {
                        action = parentForm.attrs.get("onreset");
                    }
                }
            }

            if (action != null && onWebAction != null) {
                onWebAction.onAction(action, clicked, button);
                return true;
            }
        }
        return false;
    }

    private void commitValue(String baseId, String value, BiConsumer<String, String> onInputChanged) {
        ClientScreenInteractionManager.submitInput(sessionId, baseId, value);
        ClientWebManager.updateNodeAttributeLocally(sessionId, baseId, "value", value, false);
        ClientWebManager.processRenderQueue();
        if (this.state != null) this.state.notifyElementModelChanged(baseId, value);
        if (onInputChanged != null) onInputChanged.accept(baseId, value);
    }

    private String getInputValue(String baseId) {
        HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
        if (node == null) return "";
        return node.attrs.getOrDefault("value", "");
    }

    private static HtmlNode findAncestorByTag(HtmlNode node, String tag) {
        HtmlNode cur = node;
        while (cur != null) {
            if (tag.equalsIgnoreCase(cur.tag)) return cur;
            cur = cur.parent;
        }
        return null;
    }
}
