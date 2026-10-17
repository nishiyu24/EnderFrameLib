package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.function.BiConsumer;

/**
 * 複数行テキストエリアのエディタ実装（キャレット移動、改行・インデント、編集・クリップボード貼り付け）。
 */
public class WebTextareaEditor {

    private final String sessionId;
    private boolean isEditing = false;
    private String activeBaseId = "";

    public WebTextareaEditor(String sessionId) {
        this.sessionId = sessionId;
    }

    public boolean isEditing() {
        return isEditing;
    }

    public String activeBaseId() {
        return activeBaseId;
    }

    public void focus(Font font, UIElement taEl, double localX, double localY) {
        this.isEditing = true;
        this.activeBaseId = taEl.baseId();
        String renderTaId = taEl.id().endsWith("_textarea") ? taEl.id() : this.activeBaseId + "_textarea";
        ClientWebManager.setActiveInputId(sessionId, renderTaId);

        String fullText = getValue(activeBaseId);
        if (localX < 0 || localY < 0) {
            TextEditState.setTextareaCursor(renderTaId, fullText.length());
            return;
        }

        String[] lines = fullText.split("\n", -1);
        int scrollRow = TextEditState.getTextareaScrollRow(renderTaId);
        int clickedRow = Math.clamp(scrollRow + (int) ((localY - 4.0) / 11.0), 0, Math.max(0, lines.length - 1));

        String syntax = taEl.events() != null ? taEl.events().getOrDefault("data-syntax", "") : "";
        boolean showLineNums = "lua".equalsIgnoreCase(syntax) || (taEl.events() != null && "true".equalsIgnoreCase(taEl.events().get("data-line-numbers")));
        float gutterW = showLineNums ? 28.0f : 4.0f;

        String lineStr = lines[clickedRow];
        int clickedCol = font.plainSubstrByWidth(lineStr, Math.max(0, (int) (localX - gutterW))).length();

        int index = 0;
        for (int i = 0; i < clickedRow; i++) index += lines[i].length() + 1;
        index += Math.min(clickedCol, lineStr.length());
        TextEditState.setTextareaCursor(renderTaId, index);
    }

    public void unfocus() {
        this.isEditing = false;
        this.activeBaseId = "";
    }

    public String getValue(String baseId) {
        HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
        if (node == null) return "";
        return node.attrs.getOrDefault("value", node.getTextContent());
    }

    public void setValue(String baseId, String value) {
        String safeVal = value != null ? value.replace("\r\n", "\n") : "";
        HtmlNode vNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
        if (vNode != null) vNode.attrs.put("value", safeVal);
        HtmlNode tNode = DomUtils.findNodeById(ClientWebManager.getTemplateRoot(sessionId), baseId);
        if (tNode != null) tNode.attrs.put("value", safeVal);
        ClientScreenInteractionManager.updateInputLocally(sessionId, baseId + "_textarea", safeVal);
    }

    public void insert(String insertion, BiConsumer<String, String> onInputChanged, Runnable onModelChanged) {
        String renderId = ClientWebManager.getActiveInputId(sessionId);
        String text = getValue(activeBaseId);
        int cursor = Math.clamp(TextEditState.getTextareaCursor(renderId, text.length()), 0, text.length());
        String next = text.substring(0, cursor) + insertion + text.substring(cursor);
        TextEditState.setTextareaCursor(renderId, cursor + insertion.length());
        setValue(activeBaseId, next);
        if (onModelChanged != null) onModelChanged.run();
        if (onInputChanged != null) onInputChanged.accept(activeBaseId, next);
    }

    public boolean handleCharTyped(char codePoint, BiConsumer<String, String> onInputChanged, Runnable onModelChanged) {
        if (!isEditing || ClientWebManager.getActiveInputId(sessionId).isEmpty()) return false;
        if (codePoint >= 32 && codePoint != 127) {
            insert(String.valueOf(codePoint), onInputChanged, onModelChanged);
            return true;
        }
        return false;
    }

    public boolean handleKeyPressed(int keyCode, BiConsumer<String, String> onInputChanged, Runnable onModelChanged) {
        if (!isEditing || ClientWebManager.getActiveInputId(sessionId).isEmpty()) return false;
        String renderId = ClientWebManager.getActiveInputId(sessionId);
        String text = getValue(activeBaseId);
        int cursor = Math.clamp(TextEditState.getTextareaCursor(renderId, text.length()), 0, text.length());

        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                unfocus();
                ClientWebManager.setActiveInputId(sessionId, "");
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (cursor > 0) TextEditState.setTextareaCursor(renderId, cursor - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (cursor < text.length()) TextEditState.setTextareaCursor(renderId, cursor + 1);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                int lineStart = text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
                TextEditState.setTextareaCursor(renderId, lineStart);
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                int lineEnd = text.indexOf('\n', cursor);
                TextEditState.setTextareaCursor(renderId, lineEnd == -1 ? text.length() : lineEnd);
                return true;
            }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                String[] lines = text.split("\n", -1);
                int row = 0, col = 0, acc = 0;
                for (int i = 0; i < lines.length; i++) {
                    if (cursor <= acc + lines[i].length()) { row = i; col = cursor - acc; break; }
                    acc += lines[i].length() + 1;
                }
                int targetRow = (keyCode == GLFW.GLFW_KEY_UP) ? row - 1 : row + 1;
                if (targetRow >= 0 && targetRow < lines.length) {
                    int newCursor = 0;
                    for (int i = 0; i < targetRow; i++) newCursor += lines[i].length() + 1;
                    newCursor += Math.min(col, lines[targetRow].length());
                    TextEditState.setTextareaCursor(renderId, newCursor);
                }
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                int lineStart = text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
                String currentLine = text.substring(lineStart, cursor);
                int indentLen = 0;
                while (indentLen < currentLine.length() && currentLine.charAt(indentLen) == ' ') indentLen++;
                insert("\n" + " ".repeat(indentLen), onInputChanged, onModelChanged);
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                insert("  ", onInputChanged, onModelChanged);
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (cursor > 0) {
                    String next = text.substring(0, cursor - 1) + text.substring(cursor);
                    TextEditState.setTextareaCursor(renderId, cursor - 1);
                    setValue(activeBaseId, next);
                    if (onModelChanged != null) onModelChanged.run();
                    if (onInputChanged != null) onInputChanged.accept(activeBaseId, next);
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (cursor < text.length()) {
                    String next = text.substring(0, cursor) + text.substring(cursor + 1);
                    setValue(activeBaseId, next);
                    if (onModelChanged != null) onModelChanged.run();
                    if (onInputChanged != null) onInputChanged.accept(activeBaseId, next);
                }
                return true;
            }
            case GLFW.GLFW_KEY_V -> {
                if (Screen.hasControlDown()) {
                    String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                    if (clip != null && !clip.isEmpty()) {
                        insert(clip.replace("\r\n", "\n"), onInputChanged, onModelChanged);
                    }
                    return true;
                }
            }
        }
        return false;
    }
}
