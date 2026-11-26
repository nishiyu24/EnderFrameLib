package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * テキスト欄の編集の見た目（{@link TextFieldOverlay}）。スタンドアロンのビューと MOD の画面が共有する計算で、
 * キャレットは描画（欄の左上 + {@link TextFieldOverlay#PAD}）と同じ書体の字の境目に置かれ、点からの位置の逆算と一致する。
 */
class TextFieldOverlayTest {

    private static DisplayItem field(String html, String part) {
        List<DisplayItem> items = SoftwareRendering.displayList(html, 300, 150);
        for (DisplayItem it : items) {
            if (it.isPart(part)) return DisplayItem.resolveAbsolute(items, it);
        }
        throw new AssertionError("欄の項目が無い: " + part);
    }

    private static OutlineFontFace faceOrSkip(DisplayItem el) {
        OutlineFontFace face = TextFieldOverlay.face(SystemFonts.system(), el);
        Assumptions.assumeTrue(face != null, "no system fonts");
        return face;
    }

    private static DisplayItem part(List<DisplayItem> items, String part) {
        return items.stream().filter(it -> it.id().endsWith(part)).findFirst().orElse(null);
    }

    @Test
    void caretSitsOnTheGlyphBoundaryTheCoreDrawsAt() {
        DisplayItem el = field("<body style='margin:0'><input value='hello' style='width:200px;height:30px;font-size:16px'></body>", "_input");
        OutlineFontFace face = faceOrSkip(el);
        TextEditing.State st = new TextEditing.State(3);
        List<DisplayItem> out = TextFieldOverlay.build("f", el, face, "hello", false, st);
        DisplayItem caret = part(out, "_caret");
        assertNotNull(caret);
        float expected = el.x() + TextFieldOverlay.PAD + face.positions("hello", TextFieldOverlay.style(el))[3];
        assertEquals(Math.round(expected), caret.x(), "キャレットが 3 文字目の境目に無い");
        assertEquals("f", caret.baseId());
        // 点からの逆算はキャレットの位置へ戻る
        assertEquals(3, TextFieldOverlay.caretAt(el, face, "hello", false, expected, el.y() + 5));
        assertEquals(0, TextFieldOverlay.caretAt(el, face, "hello", false, el.x() - 50, el.y() + 5));
        assertEquals(5, TextFieldOverlay.caretAt(el, face, "hello", false, el.x() + 1000, el.y() + 5));
    }

    @Test
    void selectionSpansRowsOfATextareaAndTheCaretFollowsTheRow() {
        DisplayItem el = field("<body style='margin:0'><textarea style='width:200px;height:80px;font-size:16px'>ab\ncd</textarea></body>",
                "_textarea");
        OutlineFontFace face = faceOrSkip(el);
        String text = "ab\ncd";
        TextEditing.State st = new TextEditing.State(4); // 2 行目の "c" の後
        st.anchor = 1;                                   // 1 行目の "a" の後から選択
        List<DisplayItem> out = TextFieldOverlay.build("t", el, face, text, true, st);
        DisplayItem sel0 = part(out, "_sel0"), sel1 = part(out, "_sel1"), caret = part(out, "_caret");
        assertNotNull(sel0, "1 行目の選択が無い");
        assertNotNull(sel1, "2 行目の選択が無い");
        assertNotNull(caret);
        assertTrue(sel1.y() > sel0.y(), "2 行目の選択が 1 行目より下に無い");
        assertEquals(sel1.y(), caret.y(), "キャレットが 2 行目に無い");
        // 2 行目の中の点は 2 行目の添字（3 以上）になる
        int at = TextFieldOverlay.caretAt(el, face, text, true, el.x() + 1000, sel1.y() + 2);
        assertEquals(text.length(), at);
    }

    @Test
    void compositionDrawsThePreeditTextAndPutsTheCaretInsideIt() {
        DisplayItem el = field("<body style='margin:0'><input value='ab' style='width:200px;height:30px;font-size:16px'></body>", "_input");
        OutlineFontFace face = faceOrSkip(el);
        TextEditing.State st = new TextEditing.State(2);
        st.startComposition();
        st.updateComposition("xyz", 1);
        List<DisplayItem> out = TextFieldOverlay.build("f", el, face, "ab", false, st);
        DisplayItem pre = part(out, "_ime_txt"), caret = part(out, "_caret");
        assertNotNull(pre);
        assertEquals("xyz", pre.text());
        assertTrue(caret.x() > pre.x() && caret.x() < pre.x() + pre.width(), "キャレットが未確定文字の中に無い");
    }
}
