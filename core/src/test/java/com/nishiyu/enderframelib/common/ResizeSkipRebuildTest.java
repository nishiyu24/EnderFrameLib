package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Q7: ビューポートの幅だけが変わるとき、メディア・コンテナ・ビューポート単位に依らない文書は、ルート以外の再スタイルと箱の木の組み直しを飛ばす。
 * 結果は毎回一から組んだ結果と一致し、依存する文書では飛ばさない。
 */
class ResizeSkipRebuildTest {

    private static final int H = 600;
    private static final int[] WIDTHS = {800, 799, 800, 640, 641, 900, 899, 500, 501, 800, 800, 1024};

    @AfterEach
    void restore() {
        RenderPipeline.RESIZE_SKIP_REBUILD = true;
    }

    private static String dump(LayoutBox b) {
        StringBuilder sb = new StringBuilder();
        dump(b, sb, 0);
        return sb.toString();
    }

    private static void dump(LayoutBox b, StringBuilder sb, int d) {
        sb.append(d).append(' ').append(b.node.tag).append(' ').append(b.x).append(',').append(b.y).append(' ')
                .append(b.w).append('x').append(b.h).append(' ').append(b.style.asMap().hashCode()).append('\n');
        for (LayoutBox c : b.children) dump(c, sb, d + 1);
    }

    private static String rows(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("<div class=\"f\">row ").append(i).append(" text <b>bold</b></div>");
            sb.append("<div style=\"display:flex\"><div style=\"flex:0 0 200px\"><p>item ").append(i)
                    .append(" wraps inside</p></div><div style=\"flex:1\">grow</div></div>");
            sb.append("<p>fill row ").append(i).append(" with enough words to wrap in the narrow viewports we test</p>");
        }
        return sb.toString();
    }

    private static String page(String css, String body) {
        return "<html><head><style>body{margin:0}.f{width:300px;padding:4px;border:1px solid #888}" + css
                + "</style></head><body>" + body + "</body></html>";
    }

    /** 幅の列を同じ状態・前回の箱の木の連鎖で測り、各幅の結果と、一から組んだ結果との一致を確かめる。返すのは飛ばした回数。 */
    private static long runChain(String html, int[] widths, List<String> out) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        var doc = parser.parseDocument(html, "", widths[0], H);
        InteractionState state = new InteractionState();
        LayoutBox previous = null;
        long skips0 = RenderPipeline.resizeRebuildSkips();
        for (int w : widths) {
            LayoutBox inc = previous == null
                    ? parser.layoutDocument(doc, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state)
                    : parser.layoutDocument(doc, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, previous);
            out.add(dump(inc));
            previous = inc;
        }
        long skipped = RenderPipeline.resizeRebuildSkips() - skips0;
        // 節の状態は節が持つので、一から組む（別の InteractionState）のは連鎖を測り終えたあとにする
        for (int i = 0; i < widths.length; i++) {
            LayoutBox fresh = parser.layoutDocument(doc, widths[i], H, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());
            assertEquals(dump(fresh), out.get(i), "一から組んだ結果と一致 幅 " + widths[i]);
        }
        return skipped;
    }

    @Test
    void independentDocumentSkipsRebuildAndMatchesFresh() {
        long skips = runChain(page("", rows(30)), WIDTHS, new ArrayList<>());
        assertEquals(WIDTHS.length - 1, skips, "最初以外の全ての幅変更で飛ばす");
    }

    @Test
    void switchOffNeverSkipsAndGivesSameResult() {
        List<String> on = new ArrayList<>();
        runChain(page("", rows(10)), WIDTHS, on);
        RenderPipeline.RESIZE_SKIP_REBUILD = false;
        List<String> off = new ArrayList<>();
        long skips = runChain(page("", rows(10)), WIDTHS, off);
        assertEquals(0, skips);
        assertEquals(off, on);
    }

    @Test
    void viewportUnitsInRuleDisableSkip() {
        long skips = runChain(page(".v{width:20vw}", rows(5) + "<div class=\"v\">vw row</div>"), WIDTHS, new ArrayList<>());
        assertEquals(0, skips);
    }

    @Test
    void viewportUnitsInInlineStyleDisableSkip() {
        long skips = runChain(page("", rows(5) + "<div style=\"width:30vw\">inline vw</div>"), WIDTHS, new ArrayList<>());
        assertEquals(0, skips);
    }

    @Test
    void varDisablesSkip() {
        long skips = runChain(page(":root{--w:50vw}.v{width:var(--w)}", rows(5) + "<div class=\"v\">var row</div>"),
                WIDTHS, new ArrayList<>());
        assertEquals(0, skips);
    }

    @Test
    void mediaQueryOnlySkipsWhereTheResultDoesNotChange() {
        String css = "@media (max-width:700px){.f{width:100px;color:red}}";
        int[] widths = {800, 799, 800, 640, 641, 500, 501, 800, 900};
        long skips = runChain(page(css, rows(10)), widths, new ArrayList<>());
        // 700 をまたぐ幅変更（800→640、501→800）では飛ばさない。ほかは飛ばす
        assertEquals(widths.length - 1 - 2, skips);
    }

    @Test
    void containerQueriesDisableSkip() {
        String css = ".c{container-type:inline-size}@container (min-width:300px){.f{color:blue}}";
        long skips = runChain(page(css, "<div class=\"c\">" + rows(5) + "</div>"), WIDTHS, new ArrayList<>());
        assertEquals(0, skips);
    }

    @Test
    void generatedContentWithoutViewportUnitsStillSkips() {
        String css = ".f::before{content:'>> ';color:#080}li{margin-left:4px}";
        long skips = runChain(page(css, rows(8) + "<ul><li>a</li><li>b</li></ul>"), WIDTHS, new ArrayList<>());
        assertEquals(WIDTHS.length - 1, skips);
    }

    @Test
    void generatedContentWithViewportUnitsDoesNotSkip() {
        String css = ".f::before{content:'>> ';display:inline-block;width:5vw}";
        long skips = runChain(page(css, rows(8)), WIDTHS, new ArrayList<>());
        assertEquals(0, skips);
    }

    @Test
    void tablesAreNeverSkipped() {
        String table = "<table><tr><td>a cell with some text</td><td>b</td></tr><tr><td>c</td><td>d d d d d d</td></tr></table>";
        long skips = runChain(page("td{border:1px solid #888}", rows(4) + table), WIDTHS, new ArrayList<>());
        assertEquals(0, skips);
    }

    @Test
    void dirtyNodesDisableSkipUntilCleaned() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        var doc = parser.parseDocument(page(".f:hover{color:red}", rows(5)), "", 800, H);
        InteractionState state = new InteractionState();
        LayoutBox root = parser.layoutDocument(doc, 800, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        root = parser.layoutDocument(doc, 799, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, root);
        long before = RenderPipeline.resizeRebuildSkips();
        // 要素を汚した直後の幅変更は、汚れた要素を再スタイルするために飛ばさない
        var target = doc.root.getNodeById(firstKey(doc.root));
        assertNotNull(target);
        state.markDirty(target);
        root = parser.layoutDocument(doc, 700, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, root);
        assertEquals(before, RenderPipeline.resizeRebuildSkips());
        // 片付いたあとは、また飛ばす
        root = parser.layoutDocument(doc, 701, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, root);
        assertEquals(before + 1, RenderPipeline.resizeRebuildSkips());
        LayoutBox fresh = parser.layoutDocument(doc, 701, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());
        assertEquals(dump(fresh), dump(root));
    }

    private static String firstKey(com.nishiyu.enderframelib.core.dom.node.HtmlNode n) {
        if (!n.key().isEmpty() && n.parent != null) return n.key();
        for (var c : n.children) {
            String k = firstKey(c);
            if (k != null) return k;
        }
        return null;
    }

    @Test
    void randomWidthSequencesMatchFresh() {
        Random rnd = new Random(7);
        String html = page("@media (max-width:600px){.f{width:120px}}", rows(12));
        int[] widths = new int[40];
        for (int i = 0; i < widths.length; i++) widths[i] = 300 + rnd.nextInt(900);
        runChain(html, widths, new ArrayList<>());
        String free = page("", rows(12));
        runChain(free, widths, new ArrayList<>());
    }
}
