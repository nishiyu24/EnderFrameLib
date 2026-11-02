package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.client.renderer.TextElementRenderer;
import com.nishiyu.enderframelib.common.TextEditing;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.client.renderer.WebFontManager;
import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
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
        String renderTaId = taEl.isPart("_textarea") ? taEl.id() : this.activeBaseId + "_textarea";
        ClientWebManager.getOrCreateSession(sessionId).activateInput(renderTaId, activeBaseId);

        String fullText = getValue(activeBaseId);
        if (localX < 0 || localY < 0) {
            TextEditState.setTextareaCursor(sessionId, renderTaId, fullText.length());
            return;
        }

        // 行送り・ガター幅・フォントの倍率は描画側と同じ出所から取る。
        // 以前はここが行高 11px / ガター 28px を固定で持ち、倍率も掛けていなかったため、
        // クリックした位置と実際に動くキャレットの位置が食い違っていた。
        RenderMeta meta = taEl.meta() != null ? taEl.meta() : RenderMeta.EMPTY;
        float textScale = meta.textScale();
        float lineHeight = meta.resolvedLineHeight();
        float gutterW = TextElementRenderer.textareaGutterWidth(taEl, textScale);

        String[] lines = fullText.split("\n", -1);
        int scrollRow = TextEditState.getTextareaScrollRow(sessionId, renderTaId);
        int clickedRow = Math.clamp(scrollRow + (int) ((localY - 4.0) / lineHeight), 0, Math.max(0, lines.length - 1));

        String lineStr = lines[clickedRow];
        OutlineFontFace outline = WebFontManager.outlineFaceFor(taEl);
        int clickedCol;
        if (outline != null) {
            // 描画（DisplayItemDrawer）は行を左端 + ガター + 2px から置く
            float[] pos = outline.positions(lineStr, WebFontManager.fontStyleFor(taEl, meta.fontHeight()));
            clickedCol = OutlineFontFace.nearestBoundary(lineStr, pos, localX - gutterW - 2.0);
        } else {
            clickedCol = font.plainSubstrByWidth(lineStr,
                    Math.max(0, (int) ((localX - gutterW) / textScale))).length();
        }

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
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode node = session != null ? session.findNode(baseId) : null;
        if (node == null) return "";
        return node.attrs.getOrDefault("value", node.getTextContent());
    }

    public void setValue(String baseId, String value) {
        String safeVal = value != null ? value.replace("\r\n", "\n") : "";
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode vNode = session != null ? session.findNode(baseId) : null;
        if (vNode != null) vNode.setAttribute("value", safeVal);
        ClientScreenInteractionManager.updateInputLocally(sessionId, baseId + "_textarea", safeVal);
    }

    public void insert(String insertion, BiConsumer<String, String> onInputChanged, Runnable onModelChanged) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        String renderId = session != null ? session.activeInputId() : "";
        String text = getValue(activeBaseId);
        int cursor = Math.clamp(TextEditState.getTextareaCursor(sessionId, renderId, text.length()), 0, text.length());
        String next = text.substring(0, cursor) + insertion + text.substring(cursor);
        TextEditState.setTextareaCursor(sessionId, renderId, cursor + insertion.length());
        setValue(activeBaseId, next);
        if (onModelChanged != null) onModelChanged.run();
        if (onInputChanged != null) onInputChanged.accept(activeBaseId, next);
    }

    public boolean handleCharTyped(char codePoint, BiConsumer<String, String> onInputChanged, Runnable onModelChanged) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (!isEditing || session == null || session.activeInputId().isEmpty()) return false;
        if (codePoint >= 32 && codePoint != 127) {
            insert(String.valueOf(codePoint), onInputChanged, onModelChanged);
            return true;
        }
        return false;
    }

    /** Minecraft のクリップボードを core の編集規則へ渡す。 */
    private static final TextEditing.Clipboard CLIPBOARD = new TextEditing.Clipboard() {
        @Override
        public String get() {
            try {
                String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                return clip != null ? clip : "";
            } catch (Throwable t) {
                return "";
            }
        }

        @Override
        public void set(String text) {
            try {
                Minecraft.getInstance().keyboardHandler.setClipboard(text);
            } catch (Throwable ignored) {
            }
        }
    };

    private static int currentModifiers(int modifiers) {
        int mods = modifiers;
        try {
            if (Screen.hasControlDown()) mods |= GLFW.GLFW_MOD_CONTROL;
            if (Screen.hasShiftDown()) mods |= GLFW.GLFW_MOD_SHIFT;
            if (Screen.hasAltDown()) mods |= GLFW.GLFW_MOD_ALT;
        } catch (Throwable ignored) {
        }
        return mods;
    }

    /**
     * キー入力。矢印・Home / End・Backspace / Delete・Ctrl+V などの規則は core の {@link TextEditing}（窓と同じ）に任せ、
     * ここは Minecraft 固有の作法（Esc で編集を抜ける・Enter の自動インデント・Tab の空白 2 つ）だけを持つ。
     * 範囲選択の描画はこちらに無いので、選択は保持しない（キャレットだけ）。
     */
    public boolean handleKeyPressed(int keyCode, int modifiers, BiConsumer<String, String> onInputChanged, Runnable onModelChanged) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (!isEditing || session == null || session.activeInputId().isEmpty()) return false;
        String renderId = session.activeInputId();
        String text = getValue(activeBaseId);
        int cursor = Math.clamp(TextEditState.getTextareaCursor(sessionId, renderId, text.length()), 0, text.length());
        int mods = currentModifiers(modifiers);
        boolean plain = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) == 0;

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            unfocus();
            session.setActiveInputId("");
            return true;
        }
        if (plain && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            int lineStart = text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
            String currentLine = text.substring(lineStart, cursor);
            int indentLen = 0;
            while (indentLen < currentLine.length() && currentLine.charAt(indentLen) == ' ') indentLen++;
            insert("\n" + " ".repeat(indentLen), onInputChanged, onModelChanged);
            return true;
        }
        if (plain && keyCode == GLFW.GLFW_KEY_TAB) {
            insert("  ", onInputChanged, onModelChanged);
            return true;
        }

        TextEditing.Result r = TextEditing.key(new TextEditing.State(cursor), text, keyCode, mods, true, CLIPBOARD);
        if (!r.handled()) return false;
        TextEditState.setTextareaCursor(sessionId, renderId, r.caret());
        if (r.changed()) {
            setValue(activeBaseId, r.text());
            if (onModelChanged != null) onModelChanged.run();
            if (onInputChanged != null) onInputChanged.accept(activeBaseId, r.text());
        }
        return true;
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
