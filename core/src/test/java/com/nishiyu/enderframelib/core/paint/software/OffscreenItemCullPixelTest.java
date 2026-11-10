package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * W1: クリップの範囲に掛からない項目を塗らない（{@link SoftwareRenderBackend#cullOffscreen}）。省いても全フレームの全画素が、
 * 省かない場合と同じであること（影・あふれる文字・スクロール箱・変形を含む）、画面外の項目が実際に省かれることを確かめる。
 */
class OffscreenItemCullPixelTest {
    private static final int W = 360, H = 300, ROWS = 400;

    @AfterEach
    void restore() {
        SoftwareRenderBackend.cullOffscreen = true;
    }

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < ROWS; i++) {
            rows.append("<div class=\"row\" id=\"r").append(i).append("\">row <span id=\"t").append(i).append("\">").append(i)
                    .append("</span> long trailing text that overflows the box and keeps going on and on</div>");
        }
        return "<style>body{margin:0;font-size:13px;background:#123}.row{height:22px;margin:2px;padding:2px 6px;background:#eef;color:#223;"
                + "border:1px solid #99a;border-radius:4px;white-space:nowrap;box-shadow:0 2px 8px #000}.row:hover{background:#fed}"
                + ".sh{box-shadow:0 0 40px 10px #f00}#box{position:absolute;left:200px;top:20px;width:140px;height:120px;overflow-y:auto;background:#cde}"
                + "#box div{height:30px;border-bottom:1px solid #888}</style>"
                + "<div id=\"box\">" + "<div>a</div>".repeat(40) + "</div>" + rows;
    }

    private record Result(List<int[]> frames, long culled) {}

    private static Result run(boolean cull, long seed, int steps) {
        SoftwareRenderBackend.cullOffscreen = cull;
        List<int[]> out = new ArrayList<>();
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        long culled;
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            Random rnd = new Random(seed);
            long now = 1000;
            view.frame(now, cpu, true);
            out.add(cpu.pixmap().pixels().clone());
            for (int step = 0; step < steps; step++) {
                int pick = rnd.nextInt(4);
                int n = pick == 3 ? rnd.nextInt(12) : rnd.nextInt(ROWS);
                switch (pick) {
                    case 0 -> {
                        HtmlNode t = view.root().getNodeById("t" + n);
                        if (t != null) t.updateTextContent("n" + rnd.nextInt(10000));
                    }
                    case 1 -> {
                        HtmlNode r = view.root().getNodeById("r" + n);
                        if (r instanceof HtmlElement e) e.setAttribute("class", "row sh".equals(e.attrs.get("class")) ? "row" : "row sh");
                    }
                    case 2 -> view.ui().setHoveredElement(view.root().getNodeById("r" + n).key());
                    default -> {
                        HtmlNode r = view.root().getNodeById("r" + n);
                        if (r instanceof HtmlElement e) e.setAttribute("style", "transform:translateY(" + (rnd.nextInt(700) - 300) + "px)");
                    }
                }
                now += 16;
                view.frame(now, cpu, true);
                out.add(cpu.pixmap().pixels().clone());
            }
            culled = cpu.culledItems();
        } finally {
            view.close();
        }
        return new Result(out, culled);
    }

    @Test
    void culledFramesAreIdenticalToUnculledFrames() {
        for (long seed = 1; seed <= 3; seed++) {
            Result expected = run(false, seed, 40);
            Result actual = run(true, seed, 40);
            assertTrue(expected.culled() == 0, "省かない設定では 0");
            assertTrue(actual.culled() > 0, "画面外の項目が省かれている: " + actual.culled());
            for (int i = 0; i < expected.frames().size(); i++) {
                assertArrayEquals(expected.frames().get(i), actual.frames().get(i), "seed " + seed + " frame " + i);
            }
        }
    }
}
