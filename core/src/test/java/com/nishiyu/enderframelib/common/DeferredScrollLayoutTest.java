package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * スクロールだけの更新で箱の位置の決め直しを後回しにしても（{@link UiView#deferScrollLayout}）、箱の位置を読む側
 * （{@code layoutRoot()}）と、その後の別の更新の結果が、後回しにしない場合と同じになることを確かめる。
 */
class DeferredScrollLayoutTest {
    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 80; i++) rows.append("<div class=\"row\" id=\"r").append(i).append("\">row ").append(i).append("</div>");
        return "<style>body{margin:0;font-size:13px}.row{height:24px;margin:2px;padding:2px 6px;background:#eef}"
                + "#outer{position:absolute;left:0;top:0;width:190px;height:230px;overflow-y:auto}"
                + ".hot{background:#f00}</style><div id=\"outer\">" + rows + "</div>";
    }

    private static void collect(LayoutBox b, List<float[]> out) {
        out.add(new float[]{b.x, b.y, b.w, b.h});
        for (LayoutBox c : b.children) collect(c, out);
    }

    private static List<float[]> boxes(UiView ui) {
        List<float[]> out = new ArrayList<>();
        collect(ui.layoutRoot(), out);
        return out;
    }

    @Test
    void deferredPositionsMatchEagerOnesWhenRead() {
        boolean saved = UiView.deferScrollLayout;
        try {
            List<List<float[]>> results = new ArrayList<>();
            for (boolean defer : new boolean[]{false, true}) {
                UiView.deferScrollLayout = defer;
                StandaloneView view = new StandaloneView(html(), 320, 240, FontResolver.FALLBACK);
                try (var backend = new MutationScenario.NoDraw()) {
                    long now = 1000;
                    view.frame(now, backend, true);
                    String sid = backend.sessionId();
                    view.mouseMove(60, 100, sid);
                    Random rnd = new Random(3);
                    for (int i = 0; i < 40; i++) {
                        view.scroll(0, rnd.nextInt(7) - 3, sid);
                        now += 16;
                        view.frame(now, backend, true);
                    }
                    results.add(boxes(view.ui())); // 箱の位置を読むと、後回しにした決め直しが済む
                } finally {
                    view.close();
                }
            }
            assertEquals(results.get(0).size(), results.get(1).size());
            for (int i = 0; i < results.get(0).size(); i++) assertArrayEquals(results.get(0).get(i), results.get(1).get(i), "box " + i);
        } finally {
            UiView.deferScrollLayout = saved;
        }
    }

    @Test
    void aStyleChangeAfterDeferredScrollsGivesTheSameDisplayList() {
        boolean saved = UiView.deferScrollLayout;
        try {
            List<List<String>> results = new ArrayList<>();
            for (boolean defer : new boolean[]{false, true}) {
                UiView.deferScrollLayout = defer;
                StandaloneView view = new StandaloneView(html(), 320, 240, FontResolver.FALLBACK);
                try (var backend = new MutationScenario.NoDraw()) {
                    long now = 1000;
                    view.frame(now, backend, true);
                    String sid = backend.sessionId();
                    view.mouseMove(60, 100, sid);
                    for (int i = 0; i < 12; i++) {
                        view.scroll(0, 2, sid);
                        now += 16;
                        view.frame(now, backend, true);
                    }
                    // スクロールの後で、位置に効く変更（行の高さ）を入れる
                    view.ui().domDocument().getElementById("r5").setAttribute("style", "height:60px");
                    now += 16;
                    view.frame(now, backend, true);
                    List<String> sig = new ArrayList<>();
                    for (DisplayItem it : view.ui().renderCache()) sig.add(it.id() + "@" + it.x() + "," + it.y() + "," + it.width() + "," + it.height());
                    results.add(sig);
                } finally {
                    view.close();
                }
            }
            assertEquals(results.get(0), results.get(1));
        } finally {
            UiView.deferScrollLayout = saved;
        }
    }
}
