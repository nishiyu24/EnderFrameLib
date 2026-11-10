package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 測定の再利用の鍵から「結果に効かない成分」を外す最適化（高さの % の基準を、高さ方向の割合を持たない部分木では無視する:
 * {@link LayoutBox#blockPercentFreeEnabled}）が、毎回別の入力として測る場合と、表示リストが完全に一致すること。
 * 高さの割合（height / min-height / max-height / flex-basis / top）を混ぜた flex・grid・block の入れ子の乱数文書と、
 * 忠実度コーパス、ビューポートを変えながらの連続レイアウトで突き合わせる。
 */
class LayoutKeyEquivalenceTest {

    @AfterEach
    void restore() {
        LayoutBox.blockPercentFreeEnabled = true;
    }

    private static List<DisplayItem> flush(StandaloneView v) {
        v.ui().flushInvalidation();
        return new ArrayList<>(v.ui().renderCache());
    }

    private static void sameItems(List<DisplayItem> expected, List<DisplayItem> actual, String what) {
        assertEquals(expected.size(), actual.size(), what + ": 項目の数");
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), actual.get(i), what + ": 項目 " + i);
    }

    /** 同じ文書を、鍵の最適化の OFF / ON で、ビューポートを変えながら描いて全ステップで一致を見る。 */
    private static void same(String html, int[][] sizes, String what) {
        LayoutBox.blockPercentFreeEnabled = false;
        StandaloneView off = new StandaloneView(html, sizes[0][0], sizes[0][1], FontResolver.FALLBACK);
        List<List<DisplayItem>> expected = new ArrayList<>();
        expected.add(flush(off));
        for (int i = 1; i < sizes.length; i++) {
            off.ui().recomputeLayout(sizes[i][0], sizes[i][1]); // 即時（resize は時間でまとめられるので使わない）
            expected.add(new ArrayList<>(off.ui().renderCache()));
        }
        LayoutBox.blockPercentFreeEnabled = true;
        StandaloneView on = new StandaloneView(html, sizes[0][0], sizes[0][1], FontResolver.FALLBACK);
        sameItems(expected.get(0), flush(on), what + " 初回");
        for (int i = 1; i < sizes.length; i++) {
            on.ui().recomputeLayout(sizes[i][0], sizes[i][1]);
            sameItems(expected.get(i), new ArrayList<>(on.ui().renderCache()), what + " 手順 " + i + " @" + sizes[i][0] + "x" + sizes[i][1]);
        }
    }

    private static final int[][] SIZES = {{360, 600}, {640, 400}, {360, 600}, {900, 700}, {320, 200}};

    @Test
    void fidelityCorpusIsIdentical() {
        int cases = 0;
        for (String name : FidelityDumper.caseNames()) {
            same(FidelityDumper.caseHtml(name), SIZES, name);
            cases++;
        }
        assertTrue(cases > 100, "コーパスが読めている: " + cases);
    }

    @Test
    void showcaseIsIdentical() throws Exception {
        int found = 0;
        for (String p : new String[]{"../src/main/resources/data/enderframelib/enderframe_ui/showcase.html",
                "src/main/resources/data/enderframelib/enderframe_ui/showcase.html", "demo/resources/showcase.html"}) {
            java.nio.file.Path path = java.nio.file.Path.of(p);
            if (!java.nio.file.Files.exists(path)) continue;
            same(java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n"), SIZES, "showcase " + p);
            found++;
        }
        assertTrue(found > 0, "ショーケースの文書が読めている");
    }

    private static final String[] WORDS = {"a", "bb", "item", "lorem", "ipsum", "dolor sit", "amet consectetur", "x", "wide-word-here",
            "日本語のテキスト", "text with several words to wrap around"};
    private static final String[] WIDTHS = {"", "", "", "width:50%", "width:80px", "width:120px", "width:30%", "max-width:90px",
            "min-width:60px", "width:fit-content"};
    private static final String[] HEIGHTS = {"", "", "", "", "height:50%", "height:80px", "height:100%", "min-height:30%", "max-height:70%",
            "max-height:100px", "height:calc(100% - 10px)", "min-height:20px", "height:30px"};
    private static final String[] FLEX = {"", "", "flex:1", "flex:1 1 0", "flex:0 1 90px", "flex:2 1 auto", "flex-shrink:0", "flex-grow:1",
            "margin-left:auto", "align-self:center", "align-self:stretch", "align-self:baseline", "flex:0 0 40%", "flex-basis:35%"};
    private static final String[] BOX = {"", "padding:3px", "padding:2px 6%", "border:2px solid #888", "margin:4px", "padding:5px;border:1px solid #444"};
    private static final String[] POS = {"", "", "", "", "", "position:relative;top:10%", "position:relative;top:5px", "position:absolute;top:10%;left:3px"};

    private static void container(StringBuilder sb, Random r, int depth, int[] counter) {
        String kind = switch (r.nextInt(6)) {
            case 0 -> "display:flex;flex-direction:row";
            case 1 -> "display:flex;flex-wrap:wrap";
            case 2 -> "display:flex;flex-direction:column";
            case 3 -> "display:grid;grid-template-columns:" + new String[]{"auto 1fr", "1fr 1fr 1fr", "repeat(auto-fill,minmax(70px,1fr))", "auto auto", "80px 1fr"}[r.nextInt(5)]
                    + (r.nextInt(3) == 0 ? ";grid-template-rows:" + new String[]{"50px 1fr", "30% auto", "auto"}[r.nextInt(3)] : "");
            case 4 -> "display:inline-flex";
            default -> "display:block";
        };
        String w = r.nextInt(3) == 0 ? "" : new String[]{"width:300px", "width:60%", "width:100%", "max-width:250px", "width:fit-content"}[r.nextInt(5)];
        String hh = r.nextInt(3) == 0 ? new String[]{"height:240px", "height:60%", "height:100%", "min-height:120px"}[r.nextInt(4)] : "";
        String extra = r.nextInt(4) == 0 ? "gap:" + (1 + r.nextInt(8)) + (r.nextInt(3) == 0 ? "%" : "px") : "";
        String align = r.nextInt(3) == 0 ? "align-items:" + new String[]{"center", "stretch", "flex-start", "baseline"}[r.nextInt(4)] : "";
        sb.append("<div id=n").append(counter[0]++).append(" style='").append(kind).append(';').append(w).append(';').append(hh).append(';')
                .append(extra).append(';').append(align).append(';').append(BOX[r.nextInt(BOX.length)]).append("'>");
        int items = 1 + r.nextInt(6);
        for (int i = 0; i < items; i++) {
            sb.append("<div id=n").append(counter[0]++).append(" style='").append(WIDTHS[r.nextInt(WIDTHS.length)]).append(';')
                    .append(HEIGHTS[r.nextInt(HEIGHTS.length)]).append(';').append(FLEX[r.nextInt(FLEX.length)]).append(';')
                    .append(BOX[r.nextInt(BOX.length)]).append(';').append(POS[r.nextInt(POS.length)]).append("'>");
            if (depth < 3 && r.nextInt(3) == 0) {
                container(sb, r, depth + 1, counter);
            } else if (r.nextInt(5) == 0) {
                sb.append("<span>").append(WORDS[r.nextInt(WORDS.length)]).append("</span><b>").append(WORDS[r.nextInt(WORDS.length)]).append("</b>");
            } else {
                sb.append(WORDS[r.nextInt(WORDS.length)]);
                if (r.nextInt(3) == 0) sb.append(' ').append(WORDS[r.nextInt(WORDS.length)]);
            }
            sb.append("</div>");
        }
        sb.append("</div>");
    }

    @Test
    void randomDocumentsWithHeightPercentagesAreIdentical() {
        for (long seed = 1; seed <= 300; seed++) {
            Random r = new Random(seed);
            StringBuilder sb = new StringBuilder("<style>body{margin:8px;font-size:14px}</style>");
            int[] counter = {0};
            for (int i = 0, tops = 1 + r.nextInt(3); i < tops; i++) container(sb, r, 0, counter);
            same(sb.toString(), SIZES, "seed " + seed);
        }
    }
}
