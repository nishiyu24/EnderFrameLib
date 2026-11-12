package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.api.event.UIEvents;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * IME（日本語・多言語入力 / Preedit Composition）のテスト。
 * compositionstart -> compositionupdate -> compositionend のライフサイクルと
 * イベント発火、未確定文字列の描画オーバーレイ、キャレット座標取得を検証する。
 */
class ImeCompositionTest {

    private static final String PAGE = """
            <html>
              <body style="margin:0; padding:10px;">
                <input id="txt" type="text" value="abc" style="width:200px; height:30px; font-size:16px;" />
              </body>
            </html>
            """;

    @Test
    void imeCompositionFlowUpdatesValueAndDispatchesEvents() {
        try (WebViewSurface surface = EnderEngine.html(PAGE).size(300, 150).build()) {
            surface.frame(0, true);

            // input にフォーカスを当てる（(20, 20) をクリック）
            surface.mouseMove(20, 20);
            surface.mouseButton(MouseButton.LEFT, true);
            surface.mouseButton(MouseButton.LEFT, false);
            surface.frame(16, false);

            assertEquals("txt", surface.focusedId(), "テキスト欄にフォーカスが当たる");

            List<String> eventLog = new ArrayList<>();
            assertNotNull(surface.document().dom());
            surface.document().dom().getElementById("txt").addEventListener("compositionstart", ev -> {
                eventLog.add("start:" + ((UIEvents.Composition) ev).getData());
            });
            surface.document().dom().getElementById("txt").addEventListener("compositionupdate", ev -> {
                eventLog.add("update:" + ((UIEvents.Composition) ev).getData());
            });
            surface.document().dom().getElementById("txt").addEventListener("compositionend", ev -> {
                eventLog.add("end:" + ((UIEvents.Composition) ev).getData());
            });
            surface.document().dom().getElementById("txt").addEventListener("input", ev -> {
                eventLog.add("input:" + ((UIEvents.Input) ev).getData());
            });

            // 1. IME 開始
            surface.compositionStart();
            assertEquals(List.of("start:"), eventLog);

            // 2. IME 未確定文字列の更新（"にほんご"）
            surface.compositionUpdate("にほんご", 2);
            surface.frame(32, true);
            assertEquals(List.of("start:", "update:にほんご"), eventLog);

            // 3. IME 候補窓用の座標取得
            float[] cursorRect = surface.imeCursorRect();
            assertNotNull(cursorRect, "IME 未確定時のキャレット座標が得られる");
            assertTrue(cursorRect[0] > 0, "X 座標は正");
            assertTrue(cursorRect[1] > 0, "Y 座標は正");
            assertTrue(cursorRect[2] >= 1, "幅は 1 以上");
            assertTrue(cursorRect[3] > 0, "高さは正");

            // 4. 変換確定（"日本語"）
            surface.compositionEnd("日本語");
            surface.frame(48, true);

            assertTrue(eventLog.contains("end:日本語"), "compositionend が発火");
            assertTrue(eventLog.stream().anyMatch(e -> e.startsWith("input:")), "input イベントが発火");

            // 5. DOM の値が更新されたことを確認（キャレット位置 'a' の直後 (index 1) に挿入される）
            assertEquals("a日本語bc", surface.document().attributeOf("txt", "value"),
                    "確定した文字列がキャレット位置の input の値に反映される");
        }
    }

    @Test
    void imeCursorRectReturnsNullWhenNoFocusedControl() {
        try (WebViewSurface surface = EnderEngine.html("<div>Not editable</div>").size(200, 100).build()) {
            surface.frame(0, true);
            assertNull(surface.imeCursorRect(), "フォーカスがない場合は null");
        }
    }
}
