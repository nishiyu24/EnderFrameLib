package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * CPU ラスタの「単色の内側を行ごとに塗る」近道（{@code fastSolid}）の検証と、1 フレームの時間の表示。
 * 近道は結果を変えてはならない: 近道あり／なしで画素が完全に一致することを、実文書と乱数の図形で確かめる。
 */
class SoftwarePaintCostTest {

    private static final String CRLF = "\r\n", LF = "\n";
    private static final Path FIXTURES = Path.of("src/main/resources/data/enderframelib/enderframe_ui");

    private static List<DisplayItem> items(String html, int w, int h) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        var document = parser.parseDocument(html, "", w, h);
        LayoutBox root = parser.layoutDocument(document, w, h, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());
        return new DisplayListBuilder(w, h, document.sheet.keyframes, new ScrollState()).render(root);
    }

    private static int[] render(List<DisplayItem> items, int w, int h, boolean fast, boolean tiled) {
        boolean savedFast = SoftwareRenderBackend.fastSolid, savedTiled = TileQueue.tiled;
        SoftwareRenderBackend.fastSolid = fast;
        TileQueue.tiled = tiled;
        try {
            SoftwareRenderBackend cpu = new SoftwareRenderBackend(w, h, 1f);
            try {
                cpu.render(items, 1000);
                return cpu.pixmap().pixels().clone();
            } finally {
                cpu.close();
            }
        } finally {
            SoftwareRenderBackend.fastSolid = savedFast;
            TileQueue.tiled = savedTiled;
        }
    }

    /** 基準（近道なし・タイルなし）と、近道／タイルの 3 つの組み合わせで画素が完全に一致する。 */
    private static void assertSame(String what, List<DisplayItem> items, int w, int h) {
        int[] base = render(items, w, h, false, false);
        boolean[][] modes = {{true, false}, {false, true}, {true, true}};
        for (boolean[] m : modes) {
            int[] a = render(items, w, h, m[0], m[1]);
            for (int i = 0; i < a.length; i++) {
                if (a[i] != base[i]) fail(what + " [近道=" + m[0] + " タイル=" + m[1] + "]: 画素 (" + (i % w) + "," + (i / w) + ") 基準=" + Integer.toHexString(base[i]) + " 実際=" + Integer.toHexString(a[i]));
            }
        }
    }

    @Test
    void realDocumentsAreIdenticalWithAndWithoutTheShortcut() throws Exception {
        for (String f : new String[]{"showcase.html"}) {
            String html = Files.readString(FIXTURES.resolve(f), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertSame(f, items(html, 800, 600), 800, 600);
        }
    }

    @Test
    void randomShapesAreIdentical() {
        Random rnd = new Random(7);
        for (int doc = 0; doc < 120; doc++) {
            StringBuilder sb = new StringBuilder("<html><head><style>body{margin:0;background:#" + Integer.toHexString(0x100000 + rnd.nextInt(0xEFFFFF)) + "}</style></head><body>");
            for (int i = 0; i < 12; i++) {
                int x = rnd.nextInt(300), y = rnd.nextInt(200), w = 1 + rnd.nextInt(200), h = 1 + rnd.nextInt(120);
                int alpha = 20 + rnd.nextInt(236);
                String color = String.format("rgba(%d,%d,%d,%.3f)", rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), alpha / 255.0);
                String radius = switch (rnd.nextInt(4)) {
                    case 0 -> "0";
                    case 1 -> rnd.nextInt(30) + "px";
                    case 2 -> rnd.nextInt(30) + "px " + rnd.nextInt(30) + "px " + rnd.nextInt(30) + "px " + rnd.nextInt(30) + "px";
                    default -> "50%";
                };
                String border = switch (rnd.nextInt(4)) {
                    case 0 -> "";
                    case 1 -> "border:" + (1 + rnd.nextInt(6)) + "px solid " + rgb(rnd) + ";";
                    case 2 -> "border-style:solid;border-width:" + rnd.nextInt(6) + "px " + rnd.nextInt(6) + "px " + rnd.nextInt(6) + "px " + rnd.nextInt(6)
                            + "px;border-color:" + rgb(rnd) + " " + rgb(rnd) + " " + rgb(rnd) + " " + rgb(rnd) + ";";
                    default -> "border:" + (1 + rnd.nextInt(4)) + "px " + (rnd.nextBoolean() ? "dashed" : "dotted") + " " + rgb(rnd) + ";";
                };
                String extra = border + switch (rnd.nextInt(6)) {
                    case 0 -> "opacity:" + (0.2 + rnd.nextInt(80) / 100.0) + ";";
                    case 1 -> "transform:scale(" + (0.4 + rnd.nextInt(180) / 100.0) + ");";
                    case 2 -> "transform:rotate(" + rnd.nextInt(90) + "deg);";
                    case 3 -> "overflow:hidden;";
                    case 4 -> "transform:translate(" + rnd.nextInt(20) + ".37px," + rnd.nextInt(20) + ".61px) scale(1.3, 0.8);";
                    default -> "";
                };
                String bg = switch (rnd.nextInt(7)) {
                    case 0 -> "background:linear-gradient(" + rgb(rnd) + "," + rgb(rnd) + ");";
                    case 1 -> "background:linear-gradient(to right," + rgb(rnd) + "," + rgb(rnd) + " 70%," + rgb(rnd) + ");";
                    case 2 -> "background:linear-gradient(" + rnd.nextInt(360) + "deg," + rgb(rnd) + "," + rgb(rnd) + ");";
                    case 3 -> "background:radial-gradient(circle at 30% 40%," + rgb(rnd) + "," + rgb(rnd) + ");";
                    case 4 -> "background:repeating-linear-gradient(to bottom," + rgb(rnd) + " 0," + rgb(rnd) + " " + (3 + rnd.nextInt(12)) + "px);";
                    case 5 -> "background:conic-gradient(" + rgb(rnd) + "," + rgb(rnd) + "," + rgb(rnd) + ");";
                    default -> "";
                };
                String text = rnd.nextInt(3) == 0 ? "" : "<span style=\"color:" + rgb(rnd) + ";font-size:" + (8 + rnd.nextInt(20)) + "px;" + (rnd.nextInt(4) == 0 ? "-webkit-text-stroke:" + (1 + rnd.nextInt(3)) + "px " + rgb(rnd) + ";" : "") + "\">Hello World " + doc + " " + (i * 7) + "</span>";
                sb.append("<div style=\"position:absolute;left:").append(x).append("px;top:").append(y).append("px;width:").append(w)
                        .append("px;height:").append(h).append("px;background:").append(color).append(";border-radius:").append(radius)
                        .append(";").append(bg).append(extra).append("\">").append(text);
                if (rnd.nextBoolean()) { // クリップされる子
                    sb.append("<div style=\"width:").append(w + 20).append("px;height:").append(h / 2 + 3).append("px;background:#3a7;\"></div>");
                }
                sb.append("</div>");
            }
            sb.append("</body></html>");
            List<DisplayItem> its = items(sb.toString(), 640, 480);
            assertSame("乱数 " + doc, its, 640, 480);
            // 命令の置き場を使い回す（2 フレーム目以降は前のフレームの命令を再利用する）。何度目のフレームも毎回新しく作った基準と一致する
            int[] base = render(its, 640, 480, false, false);
            SoftwareRenderBackend.fastSolid = true;
            TileQueue.tiled = true;
            SoftwareRenderBackend reuse = new SoftwareRenderBackend(640, 480, 1f);
            try {
                for (int frame = 0; frame < 4; frame++) {
                    reuse.render(its, 1000);
                    assertArrayEquals(base, reuse.pixmap().pixels(), "使い回し 文書 " + doc + " フレーム " + frame);
                }
            } finally {
                reuse.close();
            }
        }
    }

    private static String rgb(Random rnd) {
        return String.format("rgba(%d,%d,%d,%.2f)", rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), 0.3 + rnd.nextInt(70) / 100.0);
    }

    /** 並列に実行しても毎回同じ画素になる（タイルの中で命令の順が守られ、タイル同士は独立）。 */
    @Test
    void tiledRenderIsDeterministic() throws Exception {
        String html = Files.readString(FIXTURES.resolve("showcase.html"), StandardCharsets.UTF_8).replace(CRLF, LF);
        List<DisplayItem> items = items(html, 800, 600);
        int[] first = render(items, 800, 600, true, true);
        for (int i = 0; i < 25; i++) assertArrayEquals(first, render(items, 800, 600, true, true), "繰り返し " + i);
    }

    @Test
    void shortcutAndTilesAreFasterOnTheShowcase() throws Exception {
        String html = Files.readString(FIXTURES.resolve("showcase.html"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        List<DisplayItem> items = items(html, 800, 600);
        double none = frameMs(items, false, false), fast = frameMs(items, true, false), both = frameMs(items, true, true);
        System.out.printf("SOFTWARE_PAINT showcase items=%d  none=%.2f ms  shortcut=%.2f ms  shortcut+tiles=%.2f ms%n", items.size(), none, fast, both);
        assertTrue(fast < none, "近道ありのほうが速い");
        assertTrue(both < fast, "タイルの並列実行のほうが速い: " + both + " vs " + fast);
    }

    private static double frameMs(List<DisplayItem> items, boolean fast, boolean tiled) {
        boolean savedFast = SoftwareRenderBackend.fastSolid, savedTiled = TileQueue.tiled;
        SoftwareRenderBackend.fastSolid = fast;
        TileQueue.tiled = tiled;
        SoftwareRenderBackend cpu = new SoftwareRenderBackend(800, 600, 1f);
        try {
            long now = 1000;
            for (int i = 0; i < 150; i++) cpu.render(items, now += 16);
            int n = 150;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) cpu.render(items, now += 16);
            double ms = (System.nanoTime() - t0) / 1e6 / n;
            return ms;
        } finally {
            cpu.close();
            SoftwareRenderBackend.fastSolid = savedFast;
            TileQueue.tiled = savedTiled;
        }
    }
}
