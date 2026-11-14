package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * P9b: 文字項目の下ごしらえ（書体・スタイル・BiDi 並べ替え・送り位置・影・下線・縁取り）を項目の実体で覚えても、
 * 画素は覚えない場合と完全に同じ（同じ画面を続けて描いても、色や文字を変えても）。
 */
class TextSetupReuseTest {
    private static final long T0 = 10_000L;

    private static final String DOC = "<html><body style='margin:4px;font:15px sans-serif;color:#223'>"
            + "<p>Plain paragraph with several words that wrap over lines for the shaping path to run.</p>"
            + "<p style='text-decoration:underline'>Underlined text</p>"
            + "<p style='text-decoration:line-through;font-weight:bold'>Struck bold text</p>"
            + "<p style='text-shadow:1px 1px 2px #888;font-style:italic'>Shadowed italic</p>"
            + "<p style='-webkit-text-stroke:1px #c00;color:#fff'>Stroked outline</p>"
            + "<p dir='rtl'>שלום world עולם</p>"
            + "<input type='text' value='field value'><button>Button label</button>"
            + "</body></html>";

    @AfterEach
    void restore() {
        SoftwareRenderBackend.textSetupReuse = true;
    }

    private static int[] frames(boolean reuse, String html, String html2) {
        SoftwareRenderBackend.textSetupReuse = reuse;
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(360, 300, 1f)) {
            SoftwareRendering.renderHtml(html, 360, 300, T0, b);
            SoftwareRendering.renderHtml(html, 360, 300, T0 + 16, b); // 同じ画面をもう一度（覚えた下ごしらえを使う）
            if (html2 != null) SoftwareRendering.renderHtml(html2, 360, 300, T0 + 32, b);
            return b.pixmap().pixels().clone();
        }
    }

    @Test
    void sameFrameTwiceMatchesTheUncachedRender() {
        int[] off = frames(false, DOC, null);
        assertTrue(java.util.Arrays.stream(off).distinct().count() > 30, "文字が描かれている（空白の画面ではない）");
        assertArrayEquals(off, frames(true, DOC, null));
    }

    @Test
    void changingColorAndTextAfterwardsMatchesToo() {
        String changed = DOC.replace("color:#223", "color:#a33").replace("Plain paragraph", "Changed paragraph");
        assertArrayEquals(frames(false, DOC, changed), frames(true, DOC, changed));
    }
}
