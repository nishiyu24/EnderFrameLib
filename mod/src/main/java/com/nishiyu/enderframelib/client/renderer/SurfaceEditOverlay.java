package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.gui.TextareaEditor.TextEditState;
import com.nishiyu.enderframelib.client.gui.WebTextInputHost;
import com.nishiyu.enderframelib.common.TextFieldOverlay;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.scene.SceneLocation;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;

import java.util.List;

/**
 * 編集中の欄のキャレットを、面の項目の後ろに足す（M3a）。位置の計算はスタンドアロンのビューと同じ {@link TextFieldOverlay}。
 * 欄の位置・書体はシーンから引き（{@link SessionContext#locate}。表示項目の列は読まない。M3b-4c-2b-3b-2b-1）、値とキャレットの出所は
 * MOD の編集の状態（入力欄は隠した {@code EditBox}、textarea は {@link TextEditState}）。
 */
final class SurfaceEditOverlay {

    private SurfaceEditOverlay() {}

    static List<DisplayItem> caretItems(SessionContext session, String sessionId) {
        if (session == null) return List.of();
        String renderId = session.activeInputId(), baseId = session.activeInputBaseId();
        if (renderId == null || renderId.isEmpty() || baseId == null || baseId.isEmpty()) return List.of();
        SceneLocation field = session.locate(baseId); // 画面の上の位置（スクロール・sticky を足した位置）
        if (field == null || field.node() == null) return List.of();
        boolean multiline = ElementTraits.isTextarea(field.node().tag);
        if (!multiline && !ElementTraits.isTextControl(field.node())) return List.of();
        OutlineFontFace face = TextFieldOverlay.face(WebFontManager.McFontResolver.INSTANCE, field);
        if (face == null) return List.of();
        String text;
        int caret;
        if (multiline) {
            var values = field.layer().values();
            text = values != null ? values.attrs().getOrDefault("value", values.text() != null ? values.text() : "") : "";
            caret = TextEditState.getTextareaCursor(sessionId, renderId, text.length());
        } else {
            WebTextInputHost host = session.activeTextInputHost();
            String attr = field.layer().values() != null ? field.layer().values().attr("value", "") : "";
            text = host != null ? host.getValue() : attr;
            caret = host != null ? host.getCursorPosition() : text.length();
        }
        return TextFieldOverlay.caret(baseId, field, face, text, multiline, caret);
    }
}
