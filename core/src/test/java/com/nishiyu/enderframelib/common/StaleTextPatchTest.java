package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * スクロールで箱の位置の決め直しを後回しにしている間の文字の更新を、決め直さずに局所パッチで済ませても（{@link com.nishiyu.enderframelib.core.kernel.Shortcut#STALE_TEXT_PATCH}）、
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
        out.add(b.x() + "," + b.y() + "," + b.w() + "," + b.h());
        for (LayoutBox c : b.children) collect(c, out);
    }

    private record Result(List<String> items, List<String> boxes) {}

    private static Result run(boolean defer, boolean stalePatch) throws InterruptedException {
        boolean savedDefer = com.nishiyu.enderframelib.core.kernel.Shortcut.DEFER_SCROLL_LAYOUT.on(), savedStale = com.nishiyu.enderframelib.core.kernel.Shortcut.STALE_TEXT_PATCH.on();
        com.nishiyu.enderframelib.core.kernel.Shortcut.DEFER_SCROLL_LAYOUT.set(defer);
        com.nishiyu.enderframelib.core.kernel.Shortcut.STALE_TEXT_PATCH.set(stalePatch);
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
                // リサイズは直前のレイアウト時間の 2 倍が経つまで保留される（壁時計。UiView.resizeDue）。適用されるまでフレームを回す。
                // 保留されたときだけ増えるフレームで仮想時間を進めると、スムーズスクロール（StandaloneView.stepScroll）が余計に進んで
                // 位置が変わる。時刻を進めない（dt = 0 ならスクロールは進まない）ので、保留の有無で結果が変わらない。
                // 以前は 1 フレームだけ回して、保留のまま比べていた（全件の連続実行で赤になった原因）
                now += 16;
                for (int tries = 0; tries < 2000 && view.ui().rootW() != 321; tries++) {
                    view.frame(now, backend, true);
                    if (view.ui().rootW() != 321) Thread.sleep(1);
                }
                assertEquals(321, view.ui().rootW(), "リサイズが適用されていない");
                List<String> items = new ArrayList<>();
                for (DisplayItem it : view.ui().renderCache()) items.add(it.id() + "@" + it.x() + "," + it.y() + "," + it.width() + "," + it.height());
                return new Result(items, boxes);
            } finally {
                view.close();
            }
        } finally {
            com.nishiyu.enderframelib.core.kernel.Shortcut.DEFER_SCROLL_LAYOUT.set(savedDefer);
            com.nishiyu.enderframelib.core.kernel.Shortcut.STALE_TEXT_PATCH.set(savedStale);
        }
    }

    @Test
    void textUpdatesWhileLayoutIsStaleMatchTheEagerResult() throws InterruptedException {
        long hits = UiView.STALE_TEXT_PATCH_HITS.get();
        Result lazy = run(true, true);
        assertTrue(UiView.STALE_TEXT_PATCH_HITS.get() > hits, "後回しの間の文字パッチが働いていない（テストの前提が崩れている）");
        Result eager = run(false, false);
        assertEquals(eager.boxes(), lazy.boxes());
        assertEquals(eager.items(), lazy.items());
    }
}
