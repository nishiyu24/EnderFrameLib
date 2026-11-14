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

    // ---- fit 再利用: width:auto の箱（内容で寸法が決まる）。収まる幅と収まらない幅を跨いで、一から組んだ結果と一致すること ----

    private static String fitNode(Random rnd, int depth) {
        String[] text = {"ok", "label", "alpha beta", "a bit longer label text", "x", "日本語", "a longer piece of text that wraps when narrow",
                "wordwordwordwordwordword", "two  spaces   here", "a<br>b", "<b>bold</b> and <i>it</i>"};
        String t = text[rnd.nextInt(text.length)];
        String[] tags = {"span", "div", "button", "label", "b", "p"};
        String tag = tags[rnd.nextInt(tags.length)];
        StringBuilder style = new StringBuilder();
        switch (rnd.nextInt(10)) {
            case 0 -> style.append("display:inline-block;");
            case 1 -> style.append("display:flex;gap:").append(rnd.nextInt(8)).append("px;");
            case 2 -> style.append("display:inline-flex;");
            case 3 -> style.append("display:inline-block;padding:").append(rnd.nextInt(8)).append("px;border:1px solid #888;");
            case 4 -> style.append("display:flex;flex-wrap:wrap;");
            case 5 -> style.append("float:left;");
            case 6 -> style.append("display:inline-block;white-space:nowrap;");
            case 7 -> style.append("display:grid;grid-template-columns:auto auto;");
            default -> { }
        }
        if (rnd.nextInt(6) == 0) style.append("margin:0 ").append(rnd.nextInt(10)).append("px;");
        if (rnd.nextInt(8) == 0) style.append("max-width:").append(40 + rnd.nextInt(200)).append("px;");
        if (rnd.nextInt(10) == 0) style.append("min-width:").append(20 + rnd.nextInt(100)).append("px;");
        if (rnd.nextInt(10) == 0) style.append("text-align:").append(rnd.nextBoolean() ? "center" : "right").append(";");
        StringBuilder sb = new StringBuilder("<").append(tag).append(" style=\"").append(style).append("\">").append(t);
        if (depth < 3) for (int i = 0, n = rnd.nextInt(4); i < n; i++) sb.append(fitNode(rnd, depth + 1));
        return sb.append("</").append(tag).append(">").toString();
    }

    private static String fitDoc(Random rnd) {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:0}</style></head><body>");
        for (int r = 0, rows = 3 + rnd.nextInt(8); r < rows; r++) {
            String container = switch (rnd.nextInt(4)) {
                case 0 -> "display:flex;gap:6px;";
                case 1 -> "display:flex;justify-content:space-between;";
                case 2 -> "display:flex;flex-wrap:wrap;";
                default -> "";
            };
            sb.append("<div style=\"").append(container).append("padding:").append(rnd.nextInt(10)).append("px\">");
            for (int i = 0, n = 1 + rnd.nextInt(5); i < n; i++) sb.append(fitNode(rnd, 0));
            sb.append("</div>");
        }
        return sb.append("</body></html>").toString();
    }

    private static boolean mismatches(UiDocumentCompiler parser, String html, int[] widths) {
        var document = parser.parseDocument(html, "", 800, H);
        InteractionState state = new InteractionState();
        LayoutBox prev = null;
        for (int w : widths) {
            LayoutBox inc = prev == null ? parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state)
                    : parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, prev);
            String fresh = dump(parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState()));
            if (!fresh.equals(dump(inc))) return true;
            prev = inc;
        }
        return false;
    }

    /**
     * 子を持たない箱（ここでは空の {@code p}）の min-content が今の幅を読むと、flex の項目の縮み方が、その箱を先に測ったか後で測るかで
     * 変わり、差分レイアウトが一から組んだ結果と 1px ずれた。
     */
    @Test
    void flexMinWidthPercentAndIntrinsicMarginsDoNotDependOnPreviousLayouts() {
        String[] items = {
                "<div style=\"display:flex\"><span style=\"min-width:30%\">alpha beta gamma</span><span>x</span></div>",
                "<div style=\"display:flex\"><div style=\"min-width:50%;flex:0 1 auto\">alpha beta</div><div style=\"flex:1\">gamma delta</div></div>",
                "<div style=\"display:inline-flex\"><span style=\"margin:0 auto\">alpha beta gamma delta</span><b>x</b></div>",
                "<div style=\"display:flex\"><div style=\"margin:0 10%\">alpha beta</div><div style=\"margin-left:auto\">y</div></div>",
                "<div style=\"display:flex;flex-wrap:wrap\"><span style=\"min-width:40%\">a long label here</span><span style=\"min-width:40%\">another one</span></div>",
        };
        UiDocumentCompiler parser = new UiDocumentCompiler();
        for (String item : items) {
            String html = "<html><head><style>body{margin:0}</style></head><body><div style=\"width:fit-content\">" + item + "</div></body></html>";
            assertFalse(mismatches(parser, html, new int[]{800, 799, 800, 300, 301, 120, 121, 800}), item);
        }
        // 旧実装（親の箱が前回に測った幅・子の前回の余白を読む）では、差分が一から組んだ結果とずれた組み合わせ
        String[] containers = {"display:inline-flex", "display:flex;width:fit-content", "display:flex"};
        for (String container : containers) {
            String html = "<html><head><style>body{margin:0}</style></head><body><div style=\"width:fit-content\"><div style=\"" + container
                    + "\"><span style=\"min-width:100%\">alpha beta gamma delta</span><div style=\"display:flex\"><b style=\"margin:0 10%\">item one two three</b><i>z</i></div></div></div></body></html>";
            assertFalse(mismatches(parser, html, new int[]{800, 300, 801, 120, 800, 500, 90, 800}), container);
        }
    }

    @Test
    void emptyBoxMinContentDoesNotDependOnItsCurrentWidth() {
        String html = "<html><head><style>body{margin:0}</style></head><body><div style=\"display:flex;\">ok<span><p></p>"
                + "<div style=\"display:inline-block;white-space:nowrap;\">a bit longer label text</div>"
                + "<b style=\"display:inline-block;white-space:nowrap;\">a longer piece of text that wraps when narrow</b></span></div></body></html>";
        assertFalse(mismatches(new UiDocumentCompiler(), html, new int[]{800, 799, 800, 801, 400, 401, 800}));
    }

    private static final int[] FIT_WIDTHS = {800, 799, 800, 200, 201, 120, 119, 121, 60, 61, 400, 399, 150, 151, 800, 90, 89, 300, 1000, 999};

    /** 1/1000 px 未満の差（箱の幅を別の経路で出した float の丸め誤差）は同じとみなす。 */
    private static String roundedDump(String dump) {
        return java.util.regex.Pattern.compile("-?\\d+\\.\\d+").matcher(dump)
                .replaceAll(m -> String.format(java.util.Locale.ROOT, "%.3f", Double.parseDouble(m.group())).replace("$", ""));
    }

    /** 差分レイアウトを 1 本の連鎖として進め、各手の結果の dump を返す。 */
    private static java.util.List<String> chain(UiDocumentCompiler parser, com.nishiyu.enderframelib.common.Document document, boolean fit) {
        boolean saved = LayoutBox.fitReuseEnabled;
        LayoutBox.fitReuseEnabled = fit;
        try {
            InteractionState state = new InteractionState();
            LayoutBox previous = null;
            java.util.List<String> out = new java.util.ArrayList<>();
            for (int w : FIT_WIDTHS) {
                LayoutBox inc = previous == null
                        ? parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state)
                        : parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, previous);
                out.add(dump(inc));
                previous = inc;
            }
            return out;
        } finally {
            LayoutBox.fitReuseEnabled = saved;
        }
    }

    /**
     * fit 再利用ありの差分レイアウトは、なしの差分レイアウトと全ての手で一致する（再利用が結果を変えない）。
     * なしの差分レイアウトも一から組んだ結果と一致しなければならない（空の箱の min-content が今の幅を読んでいた不具合の再発防止）。
     */
    @Test
    void fitReuseDoesNotChangeTheIncrementalResultAcrossFittingAndWrappingWidths() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        int baselineMismatches = 0;
        StringBuilder firstMismatch = new StringBuilder();
        for (int seed = 0; seed < 300; seed++) {
            Random rnd = new Random(1000 + seed);
            String html = fitDoc(rnd);
            var document = parser.parseDocument(html, "", 800, H);
            java.util.List<String> with = chain(parser, document, true);
            java.util.List<String> without = chain(parser, document, false);
            for (int step = 0; step < FIT_WIDTHS.length; step++) {
                if (!roundedDump(with.get(step)).equals(roundedDump(without.get(step)))) {
                    fail("seed " + seed + " 幅 " + FIT_WIDTHS[step] + " (手 " + step + ") で fit 再利用が結果を変えた\n" + html
                            + "\n--- without\n" + without.get(step) + "\n--- with\n" + with.get(step));
                }
                boolean savedFit = LayoutBox.fitReuseEnabled;
                LayoutBox.fitReuseEnabled = false;
                String fresh;
                try {
                    fresh = dump(parser.layoutDocument(document, FIT_WIDTHS[step], H, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState()));
                } finally {
                    LayoutBox.fitReuseEnabled = savedFit;
                }
                if (!fresh.equals(without.get(step))) {
                    if (firstMismatch.isEmpty()) {
                        firstMismatch.append("seed ").append(seed).append(" w=").append(FIT_WIDTHS[step])
                                .append(" step=").append(step).append("\n").append(html)
                                .append("\n--- fresh\n").append(fresh)
                                .append("\n--- inc\n").append(without.get(step));
                    }
                    baselineMismatches++;
                    break;
                }
            }
        }
        assertEquals(0, baselineMismatches, "差分レイアウトが一から組んだ結果と違う文書がある（fit 再利用の有無によらない）\n" + firstMismatch);
    }

    @Test
    void fitReuseSkipsMeasuringBoxesThatStillFit() {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:0}</style></head><body>");
        for (int i = 0; i < 40; i++) {
            sb.append("<div style=\"display:flex;gap:6px\"><span>label ").append(i).append("</span><span>value</span><button>go</button></div>");
        }
        sb.append("</body></html>");
        UiDocumentCompiler parser = new UiDocumentCompiler();
        var document = parser.parseDocument(sb.toString(), "", 800, H);
        long[] counts = new long[2];
        for (int k = 0; k < 2; k++) {
            boolean saved = LayoutBox.fitReuseEnabled;
            LayoutBox.fitReuseEnabled = k == 1;
            try {
                InteractionState state = new InteractionState();
                LayoutBox root = parser.layoutDocument(document, 800, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
                long before = LayoutBox.measurementsComputed();
                parser.layoutDocument(document, 799, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, root);
                counts[k] = LayoutBox.measurementsComputed() - before;
            } finally {
                LayoutBox.fitReuseEnabled = saved;
            }
        }
        System.out.println("FITREUSE measurements after a 1px width change: without=" + counts[0] + " with=" + counts[1]);
        assertTrue(counts[1] * 2 < counts[0], "収まっている箱とストレッチ前後の flex 項目は測り直さない: " + counts[1] + " vs " + counts[0]);
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

    /** fill 幅のブロック・段落・列 flex 項目が、段落の折り返し境界を跨ぐ全幅で全再構築と一致する。 */
    @Test
    void fillWidthBlocksAndFlexItemsMatchFreshAcrossWrappingBoundary() {
        String html = """
                <html><head><style>body{margin:0}
                .col{display:flex;flex-direction:column;gap:6px;padding:8px}
                .box{padding:4px 6px;border:1px solid #888}
                .row{display:flex;gap:6px}
                </style></head><body>
                <div class="col">
                  <div class="box"><p><b>bold</b> and <i>italic words</i> inside paragraph</p></div>
                  <div class="box">alpha beta gamma delta epsilon</div>
                  <div class="row"><span>short label</span><span><b>left</b> and <i>right</i></span></div>
                </div></body></html>""";
        UiDocumentCompiler parser = new UiDocumentCompiler();
        var document = parser.parseDocument(html, "", 800, H);
        // 第1ブロックの折り返し境界（およそ 250px 前後）を中心に 1px 刻みと往復で境界を跨ぐ
        int[] widths = {800, 799, 600, 320, 260, 250, 249, 248, 240, 220, 180, 140, 139, 141, 180, 248, 249, 250, 260, 500, 800};
        InteractionState state = new InteractionState();
        LayoutBox prev = null;
        for (int step = 0; step < widths.length; step++) {
            int w = widths[step];
            boolean saved = LayoutBox.fitReuseEnabled;
            LayoutBox.fitReuseEnabled = true;
            LayoutBox inc;
            try {
                inc = prev == null
                        ? parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state)
                        : parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, prev);
            } finally {
                LayoutBox.fitReuseEnabled = saved;
            }
            LayoutBox fresh = parser.layoutDocument(document, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());
            assertEquals(roundedDump(dump(fresh)), roundedDump(dump(inc)), "step " + step + " w=" + w);
            prev = inc;
        }
    }

    /** 収まり切る fill 幅ブロックと列 flex 項目は、幅変更で測定を省く。 */
    @Test
    void fillWidthBlocksSkipRemeasureWhenContentStillFits() {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:0}.col{display:flex;flex-direction:column;gap:4px}.p{padding:4px;border:1px solid #888}</style></head><body><div class=\"col\">");
        for (int i = 0; i < 30; i++) {
            sb.append("<div class=\"p\"><p>item ").append(i).append("</p><div><b>a</b> and <i>b</i></div></div>");
        }
        sb.append("</div></body></html>");
        UiDocumentCompiler parser = new UiDocumentCompiler();
        var document = parser.parseDocument(sb.toString(), "", 800, H);
        long[] counts = new long[2];
        LayoutBox[] last = new LayoutBox[2];
        for (int k = 0; k < 2; k++) {
            boolean saved = LayoutBox.fitReuseEnabled;
            LayoutBox.fitReuseEnabled = k == 1;
            try {
                InteractionState state = new InteractionState();
                LayoutBox root = parser.layoutDocument(document, 800, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
                long before = LayoutBox.measurementsComputed();
                root = parser.layoutDocument(document, 790, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, root);
                root = parser.layoutDocument(document, 800, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, root);
                counts[k] = LayoutBox.measurementsComputed() - before;
                last[k] = root;
            } finally {
                LayoutBox.fitReuseEnabled = saved;
            }
        }
        assertEquals(roundedDump(dump(last[0])), roundedDump(dump(last[1])));
        assertTrue(counts[1] * 3 < counts[0], "fill 幅の収まるブロックは測り直さない: with=" + counts[1] + " without=" + counts[0]);
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
