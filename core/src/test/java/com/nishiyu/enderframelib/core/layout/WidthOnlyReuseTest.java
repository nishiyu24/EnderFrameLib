package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 幅だけが変わるとき、箱自身の横の寸法が確定していて子孫が箱の外を読まない部分木は、前回の測定を使い回す。
 * 使い回した結果は、毎回一から組み直した結果と箱の位置・大きさまで完全に一致しなければならない。
 */
class WidthOnlyReuseTest {

    private static final int H = 600;

    private static String dump(LayoutBox b) {
        StringBuilder sb = new StringBuilder();
        dump(b, sb, 0);
        return sb.toString();
    }

    private static void dump(LayoutBox b, StringBuilder sb, int depth) {
        sb.append(depth).append(' ').append(b.node.tag).append(' ')
                .append(b.x).append(',').append(b.y).append(' ').append(b.w).append('x').append(b.h)
                .append(" m").append(b.mT).append(',').append(b.mR).append(',').append(b.mB).append(',').append(b.mL).append('\n');
        for (LayoutBox c : b.children) dump(c, sb, depth + 1);
    }

    private static String randomDoc(Random rnd) {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:0}.card{box-sizing:border-box}</style></head><body>");
        sb.append("<div style=\"padding:").append(rnd.nextInt(12)).append("px\">");
        for (int i = 0, n = 4 + rnd.nextInt(12); i < n; i++) sb.append(node(rnd, 0));
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static String node(Random rnd, int depth) {
        String[] text = {"alpha beta gamma delta", "x", "a longer piece of text that wraps around when narrow enough to do so",
                "日本語の長めのテキストがここに入ります", "ok"};
        String t = text[rnd.nextInt(text.length)];
        StringBuilder style = new StringBuilder();
        switch (rnd.nextInt(9)) {
            case 0, 1, 2 -> style.append("width:").append(60 + rnd.nextInt(240)).append("px;"); // 確定した幅
            case 3 -> style.append("width:").append(20 + rnd.nextInt(60)).append("%;"); // %
            case 4 -> style.append("width:").append(10 + rnd.nextInt(30)).append("vw;"); // ビューポート単位
            case 5 -> style.append("display:inline-block;width:").append(50 + rnd.nextInt(120)).append("px;");
            case 6 -> style.append("width:").append(80 + rnd.nextInt(150)).append("px;float:left;");
            case 7 -> style.append("display:flex;flex-wrap:wrap;gap:4px;width:").append(100 + rnd.nextInt(300)).append("px;");
            default -> { } // auto
        }
        if (rnd.nextInt(5) == 0) style.append("margin:").append(rnd.nextInt(10)).append("px;");
        if (rnd.nextInt(6) == 0) style.append("padding:").append(rnd.nextInt(10)).append("px ").append(rnd.nextInt(10)).append("px;");
        if (rnd.nextInt(5) == 0) style.append("padding-top:").append(2 + rnd.nextInt(8)).append("%;margin-bottom:").append(1 + rnd.nextInt(5)).append("%;");
        if (rnd.nextInt(4) == 0) style.append("margin-left:auto;margin-right:auto;");
        if (rnd.nextInt(10) == 0) style.append("max-width:").append(30 + rnd.nextInt(60)).append("%;");
        if (rnd.nextInt(8) == 0) style.append("border:").append(1 + rnd.nextInt(3)).append("px solid #888;");
        StringBuilder sb = new StringBuilder("<div class=\"card\" style=\"").append(style).append("\">").append(t);
        if (depth < 3) for (int i = 0, n = rnd.nextInt(4); i < n; i++) {
            if (rnd.nextInt(4) == 0) { // 絶対配置の子（箱の外を読む）
                sb.append("<div style=\"position:absolute;right:").append(rnd.nextInt(30)).append("px;top:0;width:").append(20 + rnd.nextInt(40)).append("px\">abs</div>");
                if (rnd.nextBoolean()) sb.append("<div style=\"position:absolute;left:").append(rnd.nextInt(50)).append("%;bottom:0;width:30px\">abs2</div>");
            } else if (rnd.nextInt(12) == 0) {
                sb.append("<div style=\"width:").append(5 + rnd.nextInt(20)).append("vw\">vw child</div>");
            } else {
                sb.append(node(rnd, depth + 1));
            }
        }
        return sb.append("</div>").toString();
    }

    private static final int[] WIDTHS = {800, 640, 700, 520, 800, 333, 1024, 480, 480, 900};

    @Test
    void incrementalResizeMatchesAFreshLayoutAtEveryWidth() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        for (int seed = 0; seed < 60; seed++) {
            Random rnd = new Random(seed);
            String html = randomDoc(rnd);
            var document = parser.parseDocument(html, "", 800, H);
            InteractionState state = new InteractionState();
            LayoutBox previous = null;
            for (int step = 0; step < WIDTHS.length; step++) {
                int w = WIDTHS[step];
                LayoutBox inc = previous == null
                        ? parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state)
                        : parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, previous);
                String got = dump(inc);
                LayoutBox fresh = parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());
                String want = dump(fresh);
                if (!want.equals(got)) {
                    fail("seed " + seed + " 幅 " + w + " (手 " + step + ") で一から組んだ結果と違う\n" + html + "\n--- want\n" + want + "\n--- got\n" + got);
                }
                previous = inc;
            }
        }
    }

    @Test
    void fixedWidthSubtreesAreNotMeasuredAgainWhenOnlyTheWidthChanges() {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:0}</style></head><body><div>");
        for (int i = 0; i < 60; i++) {
            sb.append("<div style=\"width:140px;padding:6px;border:1px solid #888\"><p>card ").append(i)
                    .append(" with some text inside that wraps</p><span>tail</span></div>");
        }
        sb.append("</div></body></html>");
        UiDocumentCompiler parser = new UiDocumentCompiler();
        var document = parser.parseDocument(sb.toString(), "", 800, H);

        long withReuse = measureCountOfResize(parser, document, true);
        long without = measureCountOfResize(parser, document, false);
        System.out.println("WIDTHREUSE measurements after a width change: with=" + withReuse + " without=" + without);
        assertTrue(withReuse * 3 < without, "確定した幅の部分木は測り直さない: " + withReuse + " vs " + without);
    }

    /** 親の幅が変わると値が変わる指定（% の余白・パディング、auto の余白）を持つ確定幅の箱は、使い回さずに正しく測り直す。 */
    @Test
    void boxesWhoseEdgesDependOnTheParentWidthAreStillCorrect() {
        String[] inner = {
                "width:100px;padding-top:10%;",
                "width:100px;margin-bottom:8%;",
                "width:100px;margin-left:auto;margin-right:auto;",
                "width:100px;margin-left:auto;",
                "width:100px;max-width:50%;",
                "width:100px;min-width:30%;",
                "width:100px;position:relative;left:10%;",
        };
        UiDocumentCompiler parser = new UiDocumentCompiler();
        for (String css : inner) {
            String html = "<html><head><style>body{margin:0}</style></head><body><div><div style=\"" + css + "\">text inside</div><p>after</p></div></body></html>";
            var document = parser.parseDocument(html, "", 800, H);
            InteractionState state = new InteractionState();
            LayoutBox prev = null;
            for (int w : new int[]{800, 500, 900, 300}) {
                LayoutBox inc = prev == null
                        ? parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state)
                        : parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, prev);
                LayoutBox fresh = parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());
                assertEquals(dump(fresh), dump(inc), css + " 幅 " + w);
                prev = inc;
            }
        }
    }

    /** 実際の文書（showcase）と、確定幅のカードが並ぶ文書で、幅を往復させたときの測定回数と時間（表示のみ）。 */
    @Test
    void reportResizeCostOnRealisticDocuments() throws Exception {
        String showcase = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/data/enderframelib/enderframe_ui/showcase.html"),
                java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
        StringBuilder cards = new StringBuilder("<html><head><style>body{margin:0}.row{display:flex;flex-wrap:wrap;gap:6px}.c{width:96px;height:96px;padding:4px;border:1px solid #666}</style></head><body><div class=\"row\">");
        for (int i = 0; i < 200; i++) cards.append("<div class=\"c\"><b>Item ").append(i).append("</b><span>x").append(i % 64).append("</span></div>");
        cards.append("</div></body></html>");
        for (String[] doc : new String[][]{{"showcase", showcase}, {"cards", cards.toString()}}) {
            for (boolean reuse : new boolean[]{false, true}) {
                boolean saved = LayoutBox.widthIndependentReuse;
                LayoutBox.widthIndependentReuse = reuse;
                try {
                    UiDocumentCompiler parser = new UiDocumentCompiler();
                    var document = parser.parseDocument(doc[1], "", 800, H);
                    InteractionState state = new InteractionState();
                    LayoutBox root = parser.layoutDocument(document, 800, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
                    int[] widths = {800, 700};
                    long m0 = LayoutBox.measurementsComputed();
                    int rounds = 400;
                    long t0 = 0;
                    for (int i = 0; i < rounds + 100; i++) {
                        if (i == 100) { t0 = System.nanoTime(); m0 = LayoutBox.measurementsComputed(); }
                        root = parser.layoutDocument(document, widths[(i + 1) & 1], H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, root);
                    }
                    double ms = (System.nanoTime() - t0) / 1e6 / rounds;
                    System.out.printf("WIDTHREUSE %s reuse=%s  %.3f ms/resize  measurements/resize=%.1f%n", doc[0], reuse, ms,
                            (LayoutBox.measurementsComputed() - m0) / (double) rounds);
                } finally {
                    LayoutBox.widthIndependentReuse = saved;
                }
            }
        }
    }

    private static long measureCountOfResize(UiDocumentCompiler parser, com.nishiyu.enderframelib.common.Document document, boolean reuse) {
        boolean saved = LayoutBox.widthIndependentReuse;
        LayoutBox.widthIndependentReuse = reuse;
        try {
            InteractionState state = new InteractionState();
            LayoutBox root = parser.layoutDocument(document, 800, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
            long before = LayoutBox.measurementsComputed();
            parser.layoutDocument(document, 640, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, root);
            return LayoutBox.measurementsComputed() - before;
        } finally {
            LayoutBox.widthIndependentReuse = saved;
        }
    }
}
