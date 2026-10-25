package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.GoldenFiles;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * DisplayListBuilder の出力（ディスプレイリスト）を型付きテキストとして固定するゴールデンテスト。
 *
 * <p>R6 の描画合成化（レイヤー・合成・transform）によって描画の意味が変わらないこと
 * （および意図した変更の差分）をテキスト差分として追跡可能にする。
 */
public class DisplayListGoldenTest {

    private static final int VIEWPORT_W = 800;
    private static final int VIEWPORT_H = 600;

    @Test
    public void testShowcaseDisplayListGolden() {
        String html = GoldenFiles.readFixture("showcase.html");
        String displayList = dumpDisplayList(html, VIEWPORT_W, VIEWPORT_H);
        assertFalse(displayList.isEmpty());
        GoldenFiles.assertMatches("display-list/showcase.txt", displayList);
    }

    @Test
    public void testDecorationsDisplayListGolden() {
        String html = """
                <html>
                <head>
                  <style>
                    body { margin: 10px; background-color: #1a1a24; color: #ffffff; }
                    .card {
                      width: 200px; height: 120px; margin: 10px;
                      background-color: rgba(255, 255, 255, 0.1);
                      border: 2px solid #89b4fa; border-radius: 8px;
                      box-shadow: 4px 4px 8px rgba(0, 0, 0, 0.5);
                    }
                    .gradient-box {
                      width: 150px; height: 40px; margin: 10px;
                      background: linear-gradient(to right, #f38ba8, #a6e3a1);
                      border-radius: 4px;
                    }
                    .clipped-scroll {
                      width: 160px; height: 80px; overflow: hidden;
                      border: 1px dashed #f9e2af; border-radius: 6px;
                    }
                  </style>
                </head>
                <body>
                  <div id="c1" class="card">Card content</div>
                  <div id="g1" class="gradient-box"></div>
                  <div id="s1" class="clipped-scroll">
                    <div id="inner" style="height: 150px;">Tall content</div>
                  </div>
                </body>
                </html>
                """;
        String displayList = dumpDisplayList(html, 320, 300);
        assertFalse(displayList.isEmpty());
        GoldenFiles.assertMatches("display-list/decorations.txt", displayList);
    }

    @Test
    public void testFlexAndGridDisplayListGolden() {
        String html = """
                <html>
                <head>
                  <style>
                    body { margin: 8px; }
                    .flex-container {
                      display: flex; gap: 8px; width: 240px; padding: 4px;
                      border: 1px solid #333;
                    }
                    .flex-item { flex: 1; height: 30px; background-color: #89dceb; }
                    .grid-container {
                      display: grid; grid-template-columns: 60px 1fr; gap: 4px;
                      width: 200px; margin-top: 10px;
                    }
                    .grid-cell { height: 25px; background-color: #fab387; }
                  </style>
                </head>
                <body>
                  <div id="flex" class="flex-container">
                    <div id="f1" class="flex-item">F1</div>
                    <div id="f2" class="flex-item">F2</div>
                  </div>
                  <div id="grid" class="grid-container">
                    <div id="g1" class="grid-cell">A</div>
                    <div id="g2" class="grid-cell">B</div>
                  </div>
                </body>
                </html>
                """;
        String displayList = dumpDisplayList(html, 320, 240);
        assertFalse(displayList.isEmpty());
        GoldenFiles.assertMatches("display-list/flex-grid.txt", displayList);
    }

    public static String dumpDisplayList(String html, int viewportW, int viewportH) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(html, "", viewportW, viewportH);
        LayoutBox root = parser.layoutDocument(doc, viewportW, viewportH, Map.of(),
                new ScrollState(), FontResolver.FALLBACK);
        DisplayListBuilder renderer = new DisplayListBuilder(viewportW, viewportH, doc.sheet.keyframes, new ScrollState());
        List<DisplayItem> elements = renderer.render(root);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            DisplayItem e = elements.get(i);
            sb.append(String.format(Locale.ROOT, "%03d: [%s] %s rect=[%d,%d,%d,%d]",
                    i, e.id(), e.type(), e.x(), e.y(), e.width(), e.height()));
            if (e.color() != 0) sb.append(String.format(Locale.ROOT, " color=#%08X", e.color()));
            if (e.bgColor() != 0) sb.append(String.format(Locale.ROOT, " bg=#%08X", e.bgColor()));
            if (e.text() != null && !e.text().isEmpty()) {
                sb.append(" text=\"").append(escape(e.text())).append("\"");
            }
            if (e.radius() > 0) sb.append(" r=").append(e.radius());
            if (e.opacity() < 1.0f) sb.append(String.format(Locale.ROOT, " opacity=%.2f", e.opacity()));
            if (e.translateX() != 0 || e.translateY() != 0 || e.scaleX() != 1.0f || e.scaleY() != 1.0f || e.rotate() != 0) {
                sb.append(String.format(Locale.ROOT, " transform=[tx=%.1f,ty=%.1f,sx=%.2f,sy=%.2f,rot=%.1f]",
                        e.translateX(), e.translateY(), e.scaleX(), e.scaleY(), e.rotate()));
            }
            if (e.meta() != null) {
                RenderMeta meta = e.meta();
                if (meta.hasClip()) {
                    ClipRect c = meta.clipRect();
                    sb.append(String.format(Locale.ROOT, " clip=[%.1f,%.1f,%.1f,%.1f]", c.minX(), c.minY(), c.maxX(), c.maxY()));
                }
                if (meta.hasBorderRadii()) {
                    Corners cr = meta.borderRadii();
                    sb.append(String.format(Locale.ROOT, " radii=[%d,%d,%d,%d]", cr.topLeft(), cr.topRight(), cr.bottomRight(), cr.bottomLeft()));
                }
                if (meta.hints().shaderGradient() != null) {
                    var g = meta.hints().shaderGradient();
                    sb.append(String.format(Locale.ROOT, " shader=[kind=%d,#%08X,#%08X,bounds=%d]",
                            g.kind(), g.color0(), g.color1(), g.bounds()));
                }
                if (meta.roundedBorder() != null) {
                    RoundedBorderSpec rb = meta.roundedBorder();
                    sb.append(String.format(Locale.ROOT, " roundedBorder=[w=%.1f,%.1f,%.1f,%.1f,c=#%08X,#%08X,#%08X,#%08X]",
                            rb.widthTop(), rb.widthRight(), rb.widthBottom(), rb.widthLeft(),
                            rb.colorTop(), rb.colorRight(), rb.colorBottom(), rb.colorLeft()));
                }
            }
            if (e.hints().pointerEventsNone()) {
                sb.append(" pointer-events=none");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\"", "\\\"");
    }
}