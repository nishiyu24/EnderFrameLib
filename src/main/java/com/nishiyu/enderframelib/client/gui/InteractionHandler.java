package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
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

        // 2. スクロールバーのトラッククリック・サムドラッグ
        if (button == 0 && clicked != null) {
            String elId = clicked.id();
            if (elId.endsWith("_sb_vt") || elId.endsWith("_sb_ht")) {
                ClientWebManager.handleScrollbarClick(sessionId, clicked, webX, webY);
                return true;
            } else if (elId.endsWith("_sb_v") || elId.endsWith("_sb_h")) {
                // スクロールバーのサムドラッグ開始
                ClientScreenInteractionManager.setDraggingId(sessionId, elId);
                return true;
            }
        }

        // 3. scene 要素
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

        // 4. textarea 要素
        if (clicked != null && clicked.type() == ElementType.TEXTAREA && button == 0) {
            if (unfocusInputCallback != null) unfocusInputCallback.run();
            String baseId = clicked.baseId();
            focusNavigator.setFocused(baseId);
            ClientWebManager.setFocusedElement(sessionId, baseId, false);
            textareaEditor.focus(font, clicked, hit.localX(), hit.localY());
            return true;
        }

        // 5. input 要素
        if (clicked != null && clicked.type() == ElementType.INPUT && button == 0) {
            String baseId = clicked.baseId();
            focusNavigator.setFocused(baseId);
            ClientWebManager.setFocusedElement(sessionId, baseId, false);
            String curVal = getInputValue(baseId);
            if (curVal.isEmpty() && clicked.text() != null) curVal = clicked.text();
            if (focusInputCallback != null) focusInputCallback.accept(baseId, curVal);
            return true;
        } else {
            if (unfocusInputCallback != null) unfocusInputCallback.run();
        }

        // 6. テキスト要素のクリックによるテキスト選択開始
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

            HtmlNode domNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);

            // 7. select 要素
            boolean isSelectElement = clicked.type() == ElementType.SELECT || (domNode != null && "select".equalsIgnoreCase(domNode.tag));
            if (isSelectElement && button == 0) {
                ClientScreenInteractionManager.playElementSound(clicked);
                selectWidget.open(baseId, clicked, ClientWebManager.getVirtualRoot(sessionId));
                return true;
            }

            // 8. range スライダー要素
            if (clicked.type() == ElementType.RANGE && button == 0) {
                ClientScreenInteractionManager.handleClick(sessionId, webX, webY);
                UIElement updated = ClientWebManager.getElementById(sessionId, baseId);
                if (updated != null && updated.text() != null) {
                    String val = updated.text().split(":")[0];
                    commitValue(baseId, val, onInputChanged);
                }
                return true;
            }

            // 9. draggable 要素
            if (button == 0 && clicked.events() != null && "true".equalsIgnoreCase(clicked.events().get("draggable"))) {
                String label = (domNode != null && !domNode.getTextContent().isEmpty()) ? domNode.getTextContent().trim() : baseId;
                dragDropController.startDrag(baseId, label);
            }

            // 10. W3C DOM EventDispatcher によるバブリングイベントディスパッチと標準既定動作
            ClientScreenInteractionManager.playElementSound(clicked);

            boolean[] actionInvoked = new boolean[]{false};

            if (domNode != null) {
                DomEvent clickEvent = DomEvent.click(hit.localX(), hit.localY(), button);
                EventDispatcher.dispatchEvent(domNode, clickEvent, (targetNode, action) -> {
                    actionInvoked[0] = true;
                    if (onWebAction != null) {
                        onWebAction.onAction(action, clicked, button);
                    }
                    ClientScreenInteractionManager.triggerLocalEvent(
                            sessionId, targetNode.getId(), "onclick", action,
                            targetNode.getId() + "|" + String.format(Locale.US, "%.1f,%.1f", hit.localX(), hit.localY())
                    );
                }, (evt) -> {
                    StandardDefaultActions.execute(evt, new StandardDefaultActions.Context() {
                        @Override
                        public void onAttributeChanged(HtmlNode node, String attrName, String attrValue) {
                            if (attrValue == null) {
                                ClientWebManager.updateNodeAttributeLocally(sessionId, node.getId(), attrName, null, true);
                            } else {
                                ClientWebManager.updateNodeAttributeLocally(sessionId, node.getId(), attrName, attrValue, false);
                            }
                            if ("checked".equalsIgnoreCase(attrName)) {
                                commitValue(node.getId(), Boolean.toString(attrValue != null), onInputChanged);
                            }
                        }

                        @Override
                        public void dispatchClickTo(HtmlNode target) {
                            if (target != null) {
                                UIElement targetEl = ClientWebManager.getElementById(sessionId, target.getId());
                                if (targetEl != null) {
                                    double cx = targetEl.x() + targetEl.width() / 2.0;
                                    double cy = targetEl.y() + targetEl.height() / 2.0;
                                    handleMouseClicked(screen, font, cx, cy, 0, textareaEditor, selectWidget, dragDropController,
                                            focusNavigator, focusInputCallback, unfocusInputCallback, onInputChanged, onWebAction);
                                }
                            }
                        }

                        @Override
                        public void navigateTo(String href) {
                            if (onWebAction != null) {
                                onWebAction.onAction(href, clicked, button);
                            }
                        }

                        @Override
                        public void executeFormAction(HtmlNode form, String eventType) {
                            String act = form.attrs.get(eventType);
                            if (act != null && onWebAction != null) {
                                onWebAction.onAction(act, clicked, button);
                            }
                        }
                    });
                });
            } else {
                // フォールバック: DOM ノードが存在しない要素
                String action = clicked.events() != null ? clicked.events().get("onclick") : null;
                if (action != null && onWebAction != null) {
                    onWebAction.onAction(action, clicked, button);
                    actionInvoked[0] = true;
                }
            }

            if (actionInvoked[0]) {
                return true;
            }
        }
        return false;
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

    private void commitValue(String baseId, String value, BiConsumer<String, String> onInputChanged) {
        ClientScreenInteractionManager.submitInput(sessionId, baseId, value);
        ClientWebManager.updateNodeAttributeLocally(sessionId, baseId, "value", value, false);
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
