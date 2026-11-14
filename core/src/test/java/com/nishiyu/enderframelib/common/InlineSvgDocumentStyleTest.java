package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 文書の {@code <style>} の規則が、ページ中のインライン {@code <svg>} の要素へ当たる（画素で確認）。 */
public class InlineSvgDocumentStyleTest {

    private static final int W = 60, H = 40;

    private static int pixelAtIcon(String html) {
        StandaloneView view = new StandaloneView(html, W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(W, H, 1f)) {
            view.frame(1000, backend, true);
            // アイコンは (10,10)〜(30,30)。中央を見る
            return backend.pixmap().pixels()[20 * W + 20] & 0xFFFFFF;
        } finally {
            view.close();
        }
    }

    private static String page(String css, String bodyClass) {
        return "<html><body class='" + bodyClass + "' style='margin:0;background:#fff'><style>" + css + "</style>"
                + "<svg width='20' height='20' viewBox='0 0 20 20' style='position:absolute;left:10px;top:10px'>"
                + "<rect class='icon' width='20' height='20'/></svg></body></html>";
    }

    @Test
    public void documentStyleRuleColorsInlineSvg() {
        assertEquals(0x00FF00, pixelAtIcon(page(".icon{fill:#0f0}", "")));
    }

    @Test
    public void ancestorClassChangesTheResult() {
        String css = ".icon{fill:#f00} .dark .icon{fill:#00f}";
        assertEquals(0xFF0000, pixelAtIcon(page(css, "")));
        assertEquals(0x0000FF, pixelAtIcon(page(css, "dark")));
    }

    @Test
    public void svgOwnStyleElementInsideDocument() {
        String html = "<html><body style='margin:0;background:#fff'>"
                + "<svg width='20' height='20' viewBox='0 0 20 20' style='position:absolute;left:10px;top:10px'>"
                + "<style>rect{fill:#0ff}</style><rect width='20' height='20'/></svg></body></html>";
        assertEquals(0x00FFFF, pixelAtIcon(html));
    }
}
