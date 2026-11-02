package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.TextEditing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 5-6 残件: ダブル／トリプルクリックの選択とリサイズ追従。 */
class StandaloneMultiClickTest {

    @Test
    void wordAndLineRanges() {
        assertArrayEquals(new int[]{6, 11}, TextEditing.wordRange("hello world", 8));
        assertArrayEquals(new int[]{0, 5}, TextEditing.wordRange("hello world", 0));
        assertArrayEquals(new int[]{6, 11}, TextEditing.wordRange("hello world", 11), "末尾は左の単語");
        assertArrayEquals(new int[]{5, 7}, TextEditing.wordRange("hello  world", 5), "空白の連なり");
        assertArrayEquals(new int[]{0, 0}, TextEditing.wordRange("", 0));
        assertArrayEquals(new int[]{3, 6}, TextEditing.lineRange("ab\ncde\nf", 4));
        assertArrayEquals(new int[]{0, 2}, TextEditing.lineRange("ab\ncde\nf", 1));
    }

    private static final String HTML = "<html><body style='margin:0'>"
            + "<input id='t' value='foo bar baz' style='position:absolute;left:0;top:0;width:180px;height:24px;font-size:16px'>"
            + "<textarea id='ta' style='position:absolute;left:0;top:40px;width:180px;height:80px;font-size:16px'>ab cd\nef gh</textarea>"
            + "</body></html>";

    @Test
    void doubleClickSelectsAWordAndTripleSelectsAll() {
        StandaloneView v = new StandaloneView(HTML, 200, 200);
        long[] clock = {1000};
        v.setClickClock(() -> clock[0]);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            String s = cpu.sessionId();
            var t = v.root().getNodeById("t");
            v.mouseMove(2, 12, s); // 先頭 → 'foo'
            v.mouseButton(true, s);
            v.mouseButton(false, s);
            assertFalse(v.editState(t).hasSelection());
            clock[0] += 200;
            v.mouseButton(true, s);
            v.mouseButton(false, s);
            assertEquals("foo", v.editState(t).selected("foo bar baz"));
            clock[0] += 200;
            v.mouseButton(true, s);
            v.mouseButton(false, s);
            assertEquals("foo bar baz", v.editState(t).selected("foo bar baz"));
            // 間が空けば 1 回目に戻る
            clock[0] += 2000;
            v.mouseButton(true, s);
            v.mouseButton(false, s);
            assertFalse(v.editState(t).hasSelection());
        }
    }

    @Test
    void tripleClickInTextareaSelectsTheLine() {
        StandaloneView v = new StandaloneView(HTML, 200, 200);
        long[] clock = {0};
        v.setClickClock(() -> clock[0]);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            String s = cpu.sessionId();
            var ta = v.root().getNodeById("ta");
            v.mouseMove(6, 44 + 22, s); // 2 行目
            for (int i = 0; i < 3; i++) {
                clock[0] += 100;
                v.mouseButton(true, s);
                v.mouseButton(false, s);
            }
            assertEquals("ef gh", v.editState(ta).selected("ab cd\nef gh"));
        }
    }

    @Test
    void resizeRelayoutsToTheNewViewport() {
        String html = "<html><body style='margin:0'><div id='d' style='width:100%;height:20px;background:#f00'></div></body></html>";
        StandaloneView v = new StandaloneView(html, 100, 100);
        try (SoftwareRenderBackend a = new SoftwareRenderBackend(100, 100, 1f)) {
            v.frame(0, a, true);
            assertEquals(100, v.items().stream().filter(i -> i.baseId().equals("d")).findFirst().orElseThrow().width());
        }
        v.resize(240, 80);
        assertEquals(240, v.width());
        assertEquals(80, v.height());
        assertTrue(v.needsFrame());
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(240, 80, 1f)) {
            long t = 0;
            for (int i = 0; i < 50 && v.items().stream().noneMatch(it -> it.baseId().equals("d") && it.width() == 240); i++) {
                v.frame(t += 50, b, true);
            }
            assertEquals(240, v.items().stream().filter(i -> i.baseId().equals("d")).findFirst().orElseThrow().width());
        }
    }
}
