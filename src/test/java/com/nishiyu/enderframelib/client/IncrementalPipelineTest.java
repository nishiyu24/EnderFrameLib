package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.GoldenFiles;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.MarkdownParser;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 差分更新の近道（ビューポート変更でのスタイルキャッシュの再利用、measure を飛ばす再配置）が、
 * 何もかも作り直したときと同じディスプレイリストを出すことを確かめる。
 */
public class IncrementalPipelineTest {

    private static final UiDocumentCompiler PARSER = new UiDocumentCompiler();

    /** ビューポート単位・var()・疑似要素・@media を混ぜた文書。キャッシュを使い回してはいけない所を突く。 */
    private static final String VIEWPORT_UNITS_HTML = """
            <html><head><style>
              :root { --gap: 2vw; }
              body { margin: 0; font-size: 14px; }
              .hero { width: 50vw; height: 10vh; padding: var(--gap); background: #335; }
              .title { font-size: calc(10px + 1vw); letter-spacing: 0.1vw; }
              .title::before { content: "*"; margin-right: 1vw; }
              .plain { width: 120px; height: 20px; background: #533; }
              .child { color: #fff; }
              .card { container-type: inline-size; width: 40%; }
              .card-inner { width: 50cqw; height: 8px; background: #353; }
              @media (max-width: 900px) { .plain { width: 80px; } }
            </style></head><body>
              <div class="hero"><span class="title">Title</span><p class="child">inherits <b>font</b></p></div>
              <div class="plain">plain</div>
              <div class="card"><div class="card-inner"></div></div>
            </body></html>
            """;

    @Test
    public void resizeWithCachedStylesMatchesFreshLayout() {
        for (String html : List.of(GoldenFiles.readFixture("showcase.html"),
                MarkdownParser.toHtml(GoldenFiles.readFixture("guidebook.md")),
                VIEWPORT_UNITS_HTML)) {
            Document doc = PARSER.parseDocument(html, "", 800, 600);
            InteractionState state = new InteractionState();
            PARSER.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);

            // 幅を少しずつ、@media の境界（900px）もまたいで変える
            for (int[] size : new int[][]{{801, 600}, {850, 640}, {1000, 700}, {1000, 520}, {700, 600}}) {
                int w = size[0];
                int h = size[1];
                LayoutBox reused = PARSER.layoutDocument(doc, w, h, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
                List<DisplayItem> incremental = render(doc, reused, w, h, new ScrollState());

                Document freshDoc = PARSER.parseDocument(html, "", w, h);
                LayoutBox fresh = PARSER.layoutDocument(freshDoc, w, h, Map.of(), new ScrollState(),
                        FontResolver.FALLBACK, new InteractionState());
                assertSameDisplayList(render(freshDoc, fresh, w, h, new ScrollState()), incremental, w + "x" + h);
            }
        }
    }

    @Test
    public void scrollRepositionMatchesFullLayout() {
        String html = largeListHtml();
        Document doc = PARSER.parseDocument(html, "", 800, 600);
        HtmlNode scroller = doc.root.getElementById("scroller");
        ScrollState scroll = new ScrollState();
        LayoutBox root = PARSER.layoutDocument(doc, 800, 600, Map.of(), scroll, FontResolver.FALLBACK, new InteractionState());

        for (int y : new int[]{20, 160, 3000, 0}) {
            scroll.setScroll(scroller, 0, y);
            RenderPipeline.repositionLayout(root, 800, 600, scroll, FontResolver.FALLBACK);
            List<DisplayItem> repositioned = render(doc, root, 800, 600, scroll);

            ScrollState freshScroll = new ScrollState();
            Document freshDoc = PARSER.parseDocument(html, "", 800, 600);
            freshScroll.setScroll(freshDoc.root.getElementById("scroller"), 0, y);
            LayoutBox fresh = PARSER.layoutDocument(freshDoc, 800, 600, Map.of(), freshScroll,
                    FontResolver.FALLBACK, new InteractionState());
            assertSameDisplayList(render(freshDoc, fresh, 800, 600, freshScroll), repositioned, "scrollY=" + y);
        }
    }

    @Test
    public void movingPositionedElementsNeedsOnlyReposition() {
        String html = """
                <html><head><style>
                  body { margin: 0; }
                  #desk { position: relative; width: 600px; height: 400px; overflow: hidden; }
                  #win { position: absolute; left: 10px; top: 20px; width: 200px; background: #223; }
                  #win .title { height: 18px; background: #446; }
                  #badge { position: relative; left: 0; top: 0; width: 30px; height: 10px; background: #f00; }
                </style></head><body>
                  <div id="desk">
                    <div id="win"><div class="title">Window</div><p>Some body text that wraps inside.</p></div>
                    <div id="badge"></div>
                  </div>
                </body></html>
                """;
        Document doc = PARSER.parseDocument(html, "", 800, 600);
        InteractionState state = new InteractionState();
        LayoutBox root = PARSER.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);

        HtmlNode win = doc.root.getElementById("win");
        HtmlNode badge = doc.root.getElementById("badge");
        int[][] moves = {{40, 60}, {300, 250}, {-50, 10}};
        for (int[] move : moves) {
            win.setStyleProperty("left", move[0] + "px");
            win.setStyleProperty("top", move[1] + "px");
            badge.setStyleProperty("left", (move[0] / 2) + "px");
            state.markDirty(win);
            state.markDirty(badge);

            RenderPipeline.RestyleResult res = PARSER.restyleLayoutTree(root, doc, 800, 600, Map.of(), state);
            assertTrue(res.styleChanged());
            assertTrue(res.repositionRequired(), "left / top の変化は位置の決め直しで足りる");
            assertFalse(res.layoutRequired(), "left / top の変化で寸法を測り直してはいけない");
            RenderPipeline.repositionLayout(root, 800, 600, new ScrollState(), FontResolver.FALLBACK);
            List<DisplayItem> repositioned = render(doc, root, 800, 600, new ScrollState());

            Document freshDoc = PARSER.parseDocument(html, "", 800, 600);
            freshDoc.root.getElementById("win").setStyleProperty("left", move[0] + "px");
            freshDoc.root.getElementById("win").setStyleProperty("top", move[1] + "px");
            freshDoc.root.getElementById("badge").setStyleProperty("left", (move[0] / 2) + "px");
            LayoutBox fresh = PARSER.layoutDocument(freshDoc, 800, 600, Map.of(), new ScrollState(),
                    FontResolver.FALLBACK, new InteractionState());
            assertSameDisplayList(render(freshDoc, fresh, 800, 600, new ScrollState()), repositioned,
                    "move " + move[0] + "," + move[1]);
        }
    }

    @Test
    public void insetsThatDetermineSizeStillRequireMeasure() {
        String html = """
                <html><head><style>
                  body { margin: 0; }
                  #panel { position: absolute; left: 10px; right: 10px; top: 0; height: 20px; }
                </style></head><body><div id="panel">stretch</div></body></html>
                """;
        Document doc = PARSER.parseDocument(html, "", 800, 600);
        InteractionState state = new InteractionState();
        LayoutBox root = PARSER.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);

        HtmlNode panel = doc.root.getElementById("panel");
        panel.setStyleProperty("left", "100px");
        state.markDirty(panel);
        RenderPipeline.RestyleResult res = PARSER.restyleLayoutTree(root, doc, 800, 600, Map.of(), state);
        assertTrue(res.layoutRequired(), "left と right の両方があると幅が inset で決まるので measure が要る");
    }

    private static List<DisplayItem> render(Document doc, LayoutBox root, int w, int h, ScrollState scroll) {
        return new DisplayListBuilder(w, h, doc.sheet.keyframes, scroll).render(root);
    }

    private static void assertSameDisplayList(List<DisplayItem> expected, List<DisplayItem> actual, String label) {
        assertFalse(expected.isEmpty(), label + ": 描画要素が空");
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            assertEquals(expected.get(i), actual.get(i), label + ": " + i + " 番目の描画要素が違う");
        }
        assertEquals(expected.size(), actual.size(), label + ": 描画要素の数が違う");
    }

    private static String largeListHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; }")
                .append(" li { padding: 4px; border-bottom: 1px solid #ccc; }")
                .append(" .pin { position: sticky; top: 0; background: #222; }</style></head><body>");
        sb.append("<div id=\"scroller\" style=\"height: 400px; overflow-y: auto;\"><div class=\"pin\">Header</div><ul>");
        for (int i = 1; i <= 200; i++) {
            sb.append("<li id=\"item-").append(i).append("\">Row item #").append(i).append("</li>");
        }
        sb.append("</ul></div></body></html>");
        return sb.toString();
    }
}
