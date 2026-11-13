package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * スクロールで箱の位置の決め直しを後回しにしている間の文字の更新を、決め直さずに局所パッチで済ませても（{@link UiView#staleTextPatchEnabled}）、
 * 表示リストと（読んだときに済む）箱の位置が、後回しにしない場合と同じになることを確かめる。
 */
class StaleTextPatchTest {
    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 80; i++) rows.append("<div class=\"row\" id=\"r").append(i).append("\">row ").append(i).append(" x</div>");
        return "<style>body{margin:0;font-size:13px}.row{height:24px;margin:2px;padding:2px 6px;background:#eef}"
                + "#outer{position:absolute;left:0;top:0;width:190px;height:230px;overflow-y:auto}</style><div id=\"outer\">" + rows + "</div>";
    }

    private static void collect(LayoutBox b, List<String> out) {
        out.add(b.x + "," + b.y + "," + b.w + "," + b.h);
        for (LayoutBox c : b.children) collect(c, out);
    }

    private record Result(List<String> items, List<String> boxes) {}

    private static Result run(boolean defer, boolean stalePatch) {
        boolean savedDefer = UiView.deferScrollLayout, savedStale = UiView.staleTextPatchEnabled;
        UiView.deferScrollLayout = defer;
        UiView.staleTextPatchEnabled = stalePatch;
        try {
            StandaloneView view = new StandaloneView(html(), 320, 240, FontResolver.FALLBACK);
            try (var backend = new MutationScenario.NoDraw()) {
                long now = 1000;
                view.frame(now, backend, true);
                String sid = backend.sessionId();
                view.mouseMove(60, 100, sid);
                for (int i = 0; i < 6; i++) { view.scroll(0, 2, sid); now += 16; view.frame(now, backend, true); }
                for (int i = 0; i < 4; i++) {
                    view.ui().domDocument().getElementById("r" + (3 + i)).updateTextContent("row " + (3 + i) + " " + (char) ('x' + i % 2));
                    now += 16;
                    view.frame(now, backend, true);
                    view.scroll(0, i % 2 == 0 ? 1 : -2, sid);
                    now += 16;
                    view.frame(now, backend, true);
                }
                List<String> boxes = new ArrayList<>();
                collect(view.ui().layoutRoot(), boxes); // 箱の位置を読むと、後回しにした決め直しが済む
                // 表示リストは、後回しの間は決め直す前の位置の枠で持つ（動いた分は節の移動）。同じ絵になるか比べるため、全体を作り直させる
                view.resize(321, 240);
                now += 16;
                view.frame(now, backend, true);
                List<String> items = new ArrayList<>();
                for (DisplayItem it : view.ui().renderCache()) items.add(it.id() + "@" + it.x() + "," + it.y() + "," + it.width() + "," + it.height());
                return new Result(items, boxes);
            } finally {
                view.close();
            }
        } finally {
            UiView.deferScrollLayout = savedDefer;
            UiView.staleTextPatchEnabled = savedStale;
        }
    }

    @Test
    void textUpdatesWhileLayoutIsStaleMatchTheEagerResult() {
        long hits = UiView.STALE_TEXT_PATCH_HITS.get();
        Result lazy = run(true, true);
        assertTrue(UiView.STALE_TEXT_PATCH_HITS.get() > hits, "後回しの間の文字パッチが働いていない（テストの前提が崩れている）");
        Result eager = run(false, false);
        assertEquals(eager.boxes(), lazy.boxes());
        assertEquals(eager.items(), lazy.items());
    }
}
