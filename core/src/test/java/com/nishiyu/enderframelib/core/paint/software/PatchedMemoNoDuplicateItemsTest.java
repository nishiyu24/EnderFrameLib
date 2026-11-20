package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 〔不具合〕差分パッチが箱のスライスを差し替えるとき、箱の記憶の項目リストへスクロールの中身の節（{@code _scrollc}）を混ぜていた
 * （記憶と同じリストへ後から足していた）。次の通常の組み立てが記憶を出したうえで節を足し直し、同じ id の項目が 2 つ並んだ
 * （画素は同じなので、画素比較のテストでは見えなかった。C0 ②の検証モードが見つけた）。
 */
class PatchedMemoNoDuplicateItemsTest {
    private static final int W = 360, H = 300;

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 30; i++) rows.append("<div class=\"row\" id=\"r").append(i).append("\">row ").append(i).append(" long trailing text</div>");
        return "<style>body{margin:0;font-size:13px}.row{height:22px;margin:2px;padding:2px 6px;background:#eef;overflow:hidden;white-space:nowrap}"
                + ".row:hover{background:#fed}.dim{opacity:.5}"
                + "#outer{position:absolute;left:10px;top:10px;width:200px;height:260px;overflow-y:auto}</style>"
                + "<div id=\"outer\">" + rows + "</div>";
    }

    @Test
    void noFrameHasTwoItemsWithTheSameId() {
        for (long seed = 1; seed <= 3; seed++) {
            StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
            try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
                Random rnd = new Random(seed);
                long now = 1000;
                view.frame(now, cpu, true);
                for (int step = 0; step < 60; step++) {
                    HtmlNode r = view.root().getNodeById("r" + rnd.nextInt(30));
                    switch (rnd.nextInt(3)) {
                        case 0 -> { if (r instanceof HtmlElement e) e.setAttribute("class", "row dim".equals(e.attrs.get("class")) ? "row" : "row dim"); }
                        case 1 -> view.ui().setHoveredElement(r.key());
                        default -> { if (r instanceof HtmlElement e) e.setAttribute("style", "border-radius:" + rnd.nextInt(12) + "px"); }
                    }
                    now += 16;
                    view.frame(now, cpu, true);
                    Set<String> seen = new HashSet<>();
                    for (DisplayItem it : view.items()) assertTrue(seen.add(it.id()), "seed " + seed + " 手 " + step + ": 同じ id の項目が 2 つある: " + it.id());
                }
            } finally {
                view.close();
            }
        }
    }
}
