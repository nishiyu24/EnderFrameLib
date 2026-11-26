package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.gui.TextareaEditor.TextEditState;
import com.nishiyu.enderframelib.client.gui.WebTextInputHost;
import com.nishiyu.enderframelib.common.TextFieldOverlay;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;

import java.util.ArrayList;
import java.util.List;

/**
 * 編集中の欄のキャレットを、面の項目の後ろに足す（M3a）。位置の計算はスタンドアロンのビューと同じ {@link TextFieldOverlay}。
 * 値とキャレットの出所は MOD の編集の状態（入力欄は隠した {@code EditBox}、textarea は {@link TextEditState}）。
 */
final class SurfaceEditOverlay {

    private SurfaceEditOverlay() {}

    /** {@code items}（親相対でよい）にキャレットを足した一覧。編集中の欄が無ければ {@code items} そのもの。 */
    static List<DisplayItem> withCaret(SessionContext session, String sessionId, List<DisplayItem> items) {
        List<DisplayItem> caret = caretItems(session, sessionId, items);
        if (caret.isEmpty()) return items;
        List<DisplayItem> all = new ArrayList<>(items.size() + caret.size());
        all.addAll(items);
        all.addAll(caret);
        return all;
    }

    static List<DisplayItem> caretItems(SessionContext session, String sessionId, List<DisplayItem> items) {
        if (session == null || items.isEmpty()) return List.of();
        String renderId = session.activeInputId();
        if (renderId == null || renderId.isEmpty()) return List.of();
        DisplayItem field = null;
        for (DisplayItem it : items) {
            if (renderId.equals(it.id())) {
                field = it;
                break;
            }
        }
        if (field == null || (field.type() != ElementType.INPUT && field.type() != ElementType.TEXTAREA)) return List.of();
        field = DisplayItem.resolveAbsolute(items, field);
        OutlineFontFace face = TextFieldOverlay.face(WebFontManager.McFontResolver.INSTANCE, field);
        if (face == null) return List.of();
        boolean multiline = field.type() == ElementType.TEXTAREA;
        String text;
        int caret;
        if (multiline) {
            text = field.text() != null ? field.text() : "";
            caret = TextEditState.getTextareaCursor(sessionId, renderId, text.length());
        } else {
            WebTextInputHost host = session.activeTextInputHost();
            text = host != null ? host.getValue() : (field.text() != null ? field.text() : "");
            caret = host != null ? host.getCursorPosition() : text.length();
        }
        return TextFieldOverlay.caret(field.baseId(), field, face, text, multiline, caret);
    }
}
