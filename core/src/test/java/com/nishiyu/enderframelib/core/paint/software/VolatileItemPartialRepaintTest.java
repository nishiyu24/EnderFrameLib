package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * R4: 入力欄・進捗バーなど「内容が項目の同一性に現れない種類（volatile）」を含むページでも、差分描画が全面へ戻らず、
 * 毎フレーム全面を描き直す場合と全画素で同じ絵になること（以前は volatile が 1 つでもあると毎フレーム全面描画だった）。
 */
class VolatileItemPartialRepaintTest {
    private static final int W = 360, H = 300;

    private record Run(List<int[]> frames, long partial, long full, long aborts) {}

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            rows.append("<div class=\"row\" id=\"r").append(i).append("\">row <span id=\"t").append(i).append("\">")
                    .append(i).append("</span></div>");
        }
        return "<style>body{margin:0;font-size:13px;background:#123}"
                + ".row{height:20px;margin:2px;padding:2px 6px;background:#eef;color:#223}"
                + ".row:hover{background:#fed}"
                + "#side{position:absolute;left:220px;top:10px;width:120px}"
                + "#side input{width:100px;display:block;margin:4px 0}"
                + "#side progress{width:100px;display:block;margin:4px 0}"
                + "</style><div id=\"rows\">" + rows + "</div>"
                + "<div id=\"side\"><input id=\"in\" type=\"text\" value=\"abc\"><progress id=\"pg\" value=\"30\" max=\"100\"></progress></div>";
    }

    private static Run run(boolean damage, long seed, int steps) {
        boolean saved = SoftwareRenderBackend.damageEnabled;
        SoftwareRenderBackend.damageEnabled = damage;
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            List<int[]> frames = new ArrayList<>();
            Random rnd = new Random(seed);
            long now = 1000;
            view.frame(now, cpu, true);
            for (int step = 0; step < steps; step++) {
                switch (rnd.nextInt(4)) {
                    case 0 -> view.ui().setHoveredElement(view.root().getNodeById("r" + rnd.nextInt(12)).key());
                    case 1 -> {
                        HtmlNode t = view.root().getNodeById("t" + rnd.nextInt(12));
                        if (t != null) t.updateTextContent("n" + rnd.nextInt(10000));
                    }
                    case 2 -> {
                        if (view.root().getNodeById("pg") instanceof HtmlElement e) {
                            e.setAttribute("value", String.valueOf(rnd.nextInt(100)));
                        }
                    }
                    default -> {
                        if (view.root().getNodeById("in") instanceof HtmlElement e) {
                            e.setAttribute("value", "v" + rnd.nextInt(1000));
                        }
                    }
                }
                now += 16;
                view.frame(now, cpu, true);
                frames.add(cpu.pixmap().pixels().clone());
            }
            return new Run(frames, cpu.partialFrames(), cpu.fullFrames(), cpu.damageAborts());
        } finally {
            view.close();
            SoftwareRenderBackend.damageEnabled = saved;
        }
    }

    @Test
    void volatileItemsDoNotForceFullRepaintAndPixelsMatch() {
        for (long seed = 1; seed <= 3; seed++) {
            Run expected = run(false, seed, 50);
            Run actual = run(true, seed, 50);
            assertEquals(expected.frames().size(), actual.frames().size());
            for (int i = 0; i < expected.frames().size(); i++) {
                assertArrayEquals(expected.frames().get(i), actual.frames().get(i), "seed " + seed + " フレーム " + i);
            }
            assertTrue(actual.partial() > actual.full(),
                    "seed " + seed + ": volatile を含んでも差分描画が主になる（partial=" + actual.partial() + " full=" + actual.full() + "）");
            assertEquals(0, actual.aborts(), "seed " + seed + ": 見積もりが外れて全面でやり直した");
        }
    }
}
