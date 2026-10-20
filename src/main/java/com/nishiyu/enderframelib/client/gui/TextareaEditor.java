package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.function.BiConsumer;

/**
 * 複数行テキストエリアのエディタ実装（キャレット移動、改行・インデント、編集・クリップボード貼り付け）。
 */
public class TextareaEditor {

    private final String sessionId;
    private boolean isEditing = false;
    private String activeBaseId = "";

    public TextareaEditor(String sessionId) {
        this.sessionId = sessionId;
    }

    public boolean isEditing() {
        return isEditing;
    }

    public String activeBaseId() {
        return activeBaseId;
    }

    public void focus(Font font, DisplayItem taEl, double localX, double localY) {
        this.isEditing = true;
        this.activeBaseId = taEl.baseId();
        String renderTaId = taEl.id().endsWith("_textarea") ? taEl.id() : this.activeBaseId + "_textarea";
        ClientWebManager.setActiveInputId(sessionId, renderTaId);

        String fullText = getValue(activeBaseId);
        if (localX < 0 || localY < 0) {
            TextEditState.setTextareaCursor(sessionId, renderTaId, fullText.length());
            return;
        }

        // 行送り・ガター幅・フォントの倍率は描画側と同じ出所から取る。
        // 以前はここが行高 11px / ガター 28px を固定で持ち、倍率も掛けていなかったため、
        // クリックした位置と実際に動くキャレットの位置が食い違っていた。
        DisplayItem.RenderMeta meta = taEl.meta() != null ? taEl.meta() : DisplayItem.RenderMeta.EMPTY;
        float textScale = meta.textScale();
        float lineHeight = meta.resolvedLineHeight();
        float gutterW = DisplayItemDrawer.textareaGutterWidth(taEl, textScale);

        String[] lines = fullText.split("\n", -1);
        int scrollRow = TextEditState.getTextareaScrollRow(sessionId, renderTaId);
        int clickedRow = Math.clamp(scrollRow + (int) ((localY - 4.0) / lineHeight), 0, Math.max(0, lines.length - 1));

        String lineStr = lines[clickedRow];
        int clickedCol = font.plainSubstrByWidth(lineStr,
                Math.max(0, (int) ((localX - gutterW) / textScale))).length();

        int index = 0;
        for (int i = 0; i < clickedRow; i++) index += lines[i].length() + 1;
        index += Math.min(clickedCol, lineStr.length());
        TextEditState.setTextareaCursor(sessionId, renderTaId, index);
    }

    public void unfocus() {
        this.isEditing = false;
        this.activeBaseId = "";
    }

    public String getValue(String baseId) {
        HtmlNode node = ClientWebManager.findNode(sessionId, baseId);
        if (node == null) return "";
        return node.attrs.getOrDefault("value", node.getTextContent());
    }

    public void setValue(String baseId, String value) {
        String safeVal = value != null ? value.replace("\r\n", "\n") : "";
        HtmlNode vNode = ClientWebManager.findNode(sessionId, baseId);
        if (vNode != null) vNode.attrs.put("value", safeVal);
        ClientScreenInteractionManager.updateInputLocally(sessionId, baseId + "_textarea", safeVal);
    }

    public void insert(String insertion, BiConsumer<String, String> onInputChanged, Runnable onModelChanged) {
        String renderId = ClientWebManager.getActiveInputId(sessionId);
        String text = getValue(activeBaseId);
        int cursor = Math.clamp(TextEditState.getTextareaCursor(sessionId, renderId, text.length()), 0, text.length());
        String next = text.substring(0, cursor) + insertion + text.substring(cursor);
        TextEditState.setTextareaCursor(sessionId, renderId, cursor + insertion.length());
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
        int cursor = Math.clamp(TextEditState.getTextareaCursor(sessionId, renderId, text.length()), 0, text.length());

        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                unfocus();
                ClientWebManager.setActiveInputId(sessionId, "");
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (cursor > 0) TextEditState.setTextareaCursor(sessionId, renderId, cursor - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (cursor < text.length()) TextEditState.setTextareaCursor(sessionId, renderId, cursor + 1);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                int lineStart = text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
                TextEditState.setTextareaCursor(sessionId, renderId, lineStart);
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                int lineEnd = text.indexOf('\n', cursor);
                TextEditState.setTextareaCursor(sessionId, renderId, lineEnd == -1 ? text.length() : lineEnd);
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
                    TextEditState.setTextareaCursor(sessionId, renderId, newCursor);
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
                    TextEditState.setTextareaCursor(sessionId, renderId, cursor - 1);
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

    /**
     * テキスト入力・テキストエリアのキャレット位置とスクロール状態。
     *
     * 何が壊れていたか: 同じ状態が DisplayItemDrawer 側に 3 本の static マップとして
     * 二重に存在し、編集側 (TextareaEditor / EnderScreenDelegate) が書くのは
     * こちら、描画側が読むのはあちらだったため、textarea のキャレットは常に
     * 文末に描かれ、矢印キー・Home / End・クリックでの移動もホイールスクロールも
     * 画面に反映されなかった。ここが唯一の出所。
     *
     * 実体はセッションの {@link SessionContext} が所有する。このクラスは
     * 描画・編集の境界で使う文字列 ID をセッションのマップへ橋渡しするだけ。
     */
    public static class TextEditState {

        public static int getInputScrollOffset(String sessionId, String renderId) {
            SessionContext session = ClientWebManager.getSession(sessionId);
            return session != null ? session.inputScrollOffsets().getOrDefault(renderId, 0) : 0;
        }

        public static void setInputScrollOffset(String sessionId, String renderId, int offset) {
            ClientWebManager.getOrCreateSession(sessionId).inputScrollOffsets().put(renderId, offset);
        }

        public static int getTextareaCursor(String sessionId, String renderId, int fallback) {
            SessionContext session = ClientWebManager.getSession(sessionId);
            return session != null ? session.textareaCursorIndex().getOrDefault(renderId, fallback) : fallback;
        }

        public static void setTextareaCursor(String sessionId, String renderId, int cursor) {
            ClientWebManager.getOrCreateSession(sessionId).textareaCursorIndex().put(renderId, cursor);
        }

        public static int getTextareaScrollRow(String sessionId, String renderId) {
            SessionContext session = ClientWebManager.getSession(sessionId);
            return session != null ? session.textareaScrollRow().getOrDefault(renderId, 0) : 0;
        }

        public static void setTextareaScrollRow(String sessionId, String renderId, int row) {
            ClientWebManager.getOrCreateSession(sessionId).textareaScrollRow().put(renderId, row);
        }

        /** 全セッションから {@code renderId} のキャレット状態を消す（要素の破棄時に使う）。 */
        public static void clearForElement(String renderId) {
            for (SessionContext session : ClientWebManager.sessions()) {
                session.clearTextEditState(renderId);
            }
        }
    }
}
