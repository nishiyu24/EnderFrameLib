package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import org.junit.jupiter.api.Test;

/**
 * 手元で打った入力欄の値（{@link UiInput#updateInputLocally}・{@link UiView#updateElementTextLocally}。MOD は隠した {@code EditBox} の値を
 * ここへ入れる）は DOM へだけ書き、描画はシーンから作り直す（M3b-4c-2b-1 で表示項目の列への書き戻し〔出力のその場書き換え〕を削除した）。
 *
 * <ul>
 *   <li>値を書くと再描画を予約し、次のフレームの欄の文字・プレースホルダ・range の位置・select の表示が新しい値になる。値はシーンの写しにも入る。</li>
 *   <li>書くまで列は前のまま（書き換えない）。同じ値なら何も予約しない。</li>
 * </ul>
 */
class LocalInputValueTest {
    private static final String HTML = "<html><body style='margin:0'>"
            + "<input id='t' placeholder='name' style='width:120px'>"
            + "<input id='r' type='range' min='0' max='10' value='2' style='width:100px'>"
            + "<select id='s'><option value='a'>Alpha</option><option value='b'>Beta</option></select>"
            + "<textarea id='ta'>old</textarea>"
            + "</body></html>";

    private static UiView view() {
        UiView v = new UiView("local-input");
        v.loadLocalHtml(HTML, 320, 200);
        return v;
    }

    private static DisplayItem item(UiView v, String id) {
        for (DisplayItem it : TestItems.of(v)) if (id.equals(it.id())) return it;
        return null;
    }

    @Test
    void typedValuesGoThroughTheDomAndTheNextFrame() {
        try (UiView v = view()) {
            assertEquals("", item(v, "t_input").text());
            assertEquals("name", item(v, "t_ph").text(), "値が空ならプレースホルダを描く");
            new UiInput(v).updateInputLocally("t", "hello");
            assertEquals("", item(v, "t_input").text(), "列は書き換えない（出力のその場書き換えをしない。次のフレームまで前のシーン）");
            assertNotEquals(UiView.InvalidationLevel.NONE, v.invalidationLevel(), "値の変化は再描画を予約する");
            v.flushInvalidation();
            assertEquals("hello", item(v, "t_input").text(), "次のフレームの欄の文字は DOM の値");
            assertEquals("", item(v, "t_ph").text(), "値があればプレースホルダは描かない");
            assertEquals("hello", v.locate("t").values().attr("value"), "シーンの写しにも値が入る");

            v.updateElementTextLocally("t", "hello");
            assertEquals(UiView.InvalidationLevel.NONE, v.invalidationLevel(), "同じ値なら何も予約しない");
        }
    }

    @Test
    void rangeSelectAndTextareaValuesAreRenderedFromTheDomValue() {
        try (UiView v = view()) {
            String rangeBefore = item(v, "r_input").text();
            v.updateElementTextLocally("r", "7:0:10"); // MOD のドラッグは「値:最小:最大」で入れる
            v.flushInvalidation();
            assertEquals("7", v.findNode("r").attrs.get("value"), "DOM には値だけを書く");
            String rangeAfter = item(v, "r_input").text();
            assertNotEquals(rangeBefore, rangeAfter);
            assertTrue(rangeAfter.startsWith("7"), "range の描画の値は新しい値: " + rangeAfter);

            v.updateElementTextLocally("s", "b");
            v.flushInvalidation();
            assertTrue(item(v, "s_select").text().startsWith("Beta"), "select の表示は値の選択肢の文字: " + item(v, "s_select").text());

            assertEquals("old", item(v, "ta_textarea").text());
            v.updateElementTextLocally("ta", "new\ntext");
            v.flushInvalidation();
            assertEquals("new\ntext", item(v, "ta_textarea").text(), "textarea は値が中身より先");
        }
    }
}
