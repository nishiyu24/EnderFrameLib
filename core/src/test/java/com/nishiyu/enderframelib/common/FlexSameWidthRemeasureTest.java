package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.layout.FlexLayoutEngine;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * flex の項目の主軸の幅が、仮の測定（shrink-to-fit）で出た幅と同じなら測り直しを省く
 * （{@link FlexLayoutEngine#skipSameWidthRemeasure}）と、毎回測り直す場合とで、表示リストが完全に一致すること。
 * コーパス（忠実度のケース・ショーケース）と、flex / grid の入れ子・割合幅・折り返し・auto マージンを混ぜた乱数の文書で突き合わせる。
 */
class FlexSameWidthRemeasureTest {

    @AfterEach
    void restore() {
        FlexLayoutEngine.skipSameWidthRemeasure = true;
        RenderPipeline.FRESH_LAYOUT_PASS_REUSE = true;
    }

    /** 切り替える最適化。flex の再測定の省略と、新品の箱のレイアウトでの同一パス内の測定の再利用。 */
    private static boolean fresh;

    private static List<DisplayItem> render(String html, int w, int h, boolean skip) {
        if (fresh) RenderPipeline.FRESH_LAYOUT_PASS_REUSE = skip;
        else FlexLayoutEngine.skipSameWidthRemeasure = skip;
        StandaloneView v = new StandaloneView(html, w, h, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        return new ArrayList<>(v.ui().renderCache());
    }

    private static void same(String html, int w, int h, String what) {
        List<DisplayItem> expected = render(html, w, h, false);
        List<DisplayItem> actual = render(html, w, h, true);
        assertEquals(expected.size(), actual.size(), what + " @" + w + "x" + h + ": 項目の数");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), what + " @" + w + "x" + h + ": 項目 " + i);
        }
    }

    @Test
    void freshLayoutPassReuseGivesTheSameDisplayListsOnTheCorpus() {
        fresh = true;
        try {
            for (String name : FidelityDumper.caseNames()) {
                String html = FidelityDumper.caseHtml(name);
                for (int[] vp : VIEWPORTS) same(html, vp[0], vp[1], "fresh " + name);
            }
            for (long seed = 1; seed <= 250; seed++) {
                Random r = new Random(seed);
                StringBuilder sb = new StringBuilder("<style>body{margin:8px;font-size:14px}</style>");
                int[] counter = {0};
                for (int i = 0, tops = 1 + r.nextInt(3); i < tops; i++) container(sb, r, 0, counter);
                for (int[] vp : new int[][]{{360, 600}, {640, 600}, {900, 600}}) same(sb.toString(), vp[0], vp[1], "fresh seed " + seed);
            }
        } finally {
            fresh = false;
        }
    }

    private static final int[][] VIEWPORTS = {{320, 200}, {480, 600}, {800, 600}, {1024, 700}};

    @Test
    void fidelityCorpusIsIdentical() {
        int cases = 0;
        for (String name : FidelityDumper.caseNames()) {
            String html = FidelityDumper.caseHtml(name);
            for (int[] vp : VIEWPORTS) same(html, vp[0], vp[1], name);
            cases++;
        }
        assertTrue(cases > 100, "コーパスが読めている: " + cases);
    }

    @Test
    void showcaseIsIdentical() throws Exception {
        int found = 0;
        for (String p : new String[]{"../src/main/resources/data/enderframelib/enderframe_ui/showcase.html",
                "src/main/resources/data/enderframelib/enderframe_ui/showcase.html", "demo/resources/showcase.html"}) {
            Path path = Path.of(p);
            if (!Files.exists(path)) continue;
            String html = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
            for (int[] vp : VIEWPORTS) same(html, vp[0], vp[1], "showcase " + p);
            found++;
        }
        assertTrue(found > 0, "ショーケースの文書が読めている");
    }

    private static final String[] WORDS = {"a", "bb", "item", "lorem", "ipsum", "dolor sit", "amet consectetur", "x", "wide-word-here",
            "日本語のテキスト", "text with several words to wrap around"};
    private static final String[] WIDTHS = {"", "", "", "width:50%", "width:80px", "width:120px", "width:30%", "max-width:90px",
            "min-width:60px", "width:fit-content", "width:max-content"};
    private static final String[] FLEX = {"", "", "flex:1", "flex:1 1 0", "flex:0 1 90px", "flex:2 1 auto", "flex-shrink:0", "flex-grow:1",
            "margin-left:auto", "align-self:center", "align-self:stretch", "align-self:baseline", "flex:0 0 40%"};
    private static final String[] BOX = {"", "padding:3px", "padding:2px 6%", "border:2px solid #888", "margin:4px", "padding:5px;border:1px solid #444"};

    private static void container(StringBuilder sb, Random r, int depth, int[] counter) {
        String kind = switch (r.nextInt(6)) {
            case 0 -> "display:flex;flex-direction:row";
            case 1 -> "display:flex;flex-wrap:wrap";
            case 2 -> "display:flex;flex-direction:column";
            case 3 -> "display:grid;grid-template-columns:" + new String[]{"auto 1fr", "1fr 1fr 1fr", "repeat(auto-fill,minmax(70px,1fr))", "auto auto", "80px 1fr"}[r.nextInt(5)];
            case 4 -> "display:inline-flex";
            default -> "display:block";
        };
        String w = r.nextInt(3) == 0 ? "" : new String[]{"width:300px", "width:60%", "width:100%", "max-width:250px", "width:fit-content"}[r.nextInt(5)];
        String extra = r.nextInt(4) == 0 ? "gap:" + (1 + r.nextInt(8)) + "px" : "";
        String align = r.nextInt(3) == 0 ? "align-items:" + new String[]{"center", "stretch", "flex-start", "baseline"}[r.nextInt(4)] : "";
        String just = r.nextInt(4) == 0 ? "justify-content:" + new String[]{"space-between", "center", "flex-end", "space-around"}[r.nextInt(4)] : "";
        sb.append("<div id=n").append(counter[0]++).append(" style='").append(kind).append(';').append(w).append(';').append(extra)
                .append(';').append(align).append(';').append(just).append(';').append(BOX[r.nextInt(BOX.length)]).append("'>");
        int items = 1 + r.nextInt(6);
        for (int i = 0; i < items; i++) {
            sb.append("<div id=n").append(counter[0]++).append(" style='").append(WIDTHS[r.nextInt(WIDTHS.length)]).append(';')
                    .append(FLEX[r.nextInt(FLEX.length)]).append(';').append(BOX[r.nextInt(BOX.length)]).append("'>");
            if (depth < 3 && r.nextInt(3) == 0) {
                container(sb, r, depth + 1, counter);
            } else if (r.nextInt(5) == 0) {
                sb.append("<span>").append(WORDS[r.nextInt(WORDS.length)]).append("</span><b>")
                        .append(WORDS[r.nextInt(WORDS.length)]).append("</b>");
            } else {
                sb.append(WORDS[r.nextInt(WORDS.length)]);
                if (r.nextInt(3) == 0) sb.append(' ').append(WORDS[r.nextInt(WORDS.length)]);
            }
            sb.append("</div>");
        }
        sb.append("</div>");
    }

    @Test
    void randomNestedFlexAndGridDocumentsAreIdentical() {
        for (long seed = 1; seed <= 250; seed++) {
            Random r = new Random(seed);
            StringBuilder sb = new StringBuilder("<style>body{margin:8px;font-size:14px}</style>");
            int[] counter = {0};
            int tops = 1 + r.nextInt(3);
            for (int i = 0; i < tops; i++) container(sb, r, 0, counter);
            String html = sb.toString();
            for (int[] vp : new int[][]{{360, 600}, {640, 600}, {900, 600}}) same(html, vp[0], vp[1], "seed " + seed);
        }
    }
}
