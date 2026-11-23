package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * C9c: 表（枠線を統合するものも含む）を部分木ごと保持してよい。保持した結果は、同じ状態を最初から作った結果と同じでなければならない。
 * 変更のない兄弟の表が保持される場面・変更のある表の列幅の変化・枠線の統合の切り替え・relative の中の表と絶対配置を、毎手比べる。
 */
class PlacementKeepTableTest {

    static final String HTML = "<style>body{margin:0;font-size:12px}table{width:200px}td,th{padding:2px;border:1px solid #888}"
            + ".c{border-collapse:collapse}.s{border-collapse:separate;border-spacing:3px}</style>"
            + "<div id=a>one</div>"
            + "<table id=t1 class=c><tr><th id=h1>h1</th><th>h2</th></tr><tr><td id=c11>c11</td><td id=c12>c12</td></tr><tr><td id=c21>c21</td><td id=c22>c22</td></tr></table>"
            + "<div id=mid>mid</div>"
            + "<table id=t2 class=s><tr><td id=d11>d11</td><td id=d12>d12</td></tr><tr><td id=d21>d21</td><td id=d22>d22</td></tr></table>"
            + "<div id=wrap style='position:relative'><table id=t3 class=c><tr><td id=e11>e11</td></tr></table>"
            + "<div id=badge style='position:absolute;right:0;top:0;width:10px;height:10px'>b</div></div>"
            + "<div id=c>three</div>";

    static List<String> signatures(StandaloneView view) {
        view.ui().flushInvalidation();
        List<String> out = new ArrayList<>();
        collect(view.ui().layoutRoot(), out);
        return out;
    }

    private static void collect(LayoutBox b, List<String> out) {
        out.add(b.node.tag + "#" + b.node.key() + "|" + b.x() + "," + b.y() + "," + b.w() + "," + b.h() + "|" + b.scrollWidth() + "," + b.scrollHeight());
        for (LayoutBox c : b.children) collect(c, out);
    }

    private static void text(StandaloneView v, String id, String data) {
        for (HtmlNode c : v.root().getNodeById(id).children) if (c instanceof HtmlText t) { t.setData(data); return; }
        throw new IllegalStateException("no text under " + id);
    }

    private static void attr(StandaloneView v, String id, String name, String value) {
        ((HtmlElement) v.root().getNodeById(id)).setAttribute(name, value);
    }

    static List<Consumer<StandaloneView>> steps() {
        List<Consumer<StandaloneView>> s = new ArrayList<>();
        s.add(v -> text(v, "a", "one changed"));
        s.add(v -> text(v, "c11", "c11 much longer text in this cell"));   // 列幅が変わる
        s.add(v -> text(v, "d11", "d11 changed"));                           // 兄弟の表（統合しない）
        s.add(v -> text(v, "a", "one again"));                                // 表はどちらも変更の鎖の外
        s.add(v -> attr(v, "t1", "class", "s"));                              // 枠線の統合 → 分離
        s.add(v -> text(v, "c12", "c12 changed"));
        s.add(v -> attr(v, "t1", "class", "c"));                              // 分離 → 統合
        s.add(v -> text(v, "mid", "mid changed"));
        s.add(v -> attr(v, "t2", "style", "width:300px"));
        s.add(v -> text(v, "e11", "e11 much longer cell text"));              // relative の中の表
        s.add(v -> attr(v, "t3", "class", "s"));
        s.add(v -> text(v, "c", "three changed"));
        s.add(v -> attr(v, "t1", "style", "width:120px"));
        s.add(v -> text(v, "d22", "d22 changed too"));
        s.add(v -> text(v, "a", "one 3"));
        return s;
    }

    private static StandaloneView fresh(List<Consumer<StandaloneView>> steps, int upTo) {
        StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
        signatures(v);
        for (int i = 0; i < upTo; i++) steps.get(i).accept(v);
        return v;
    }

    @Test
    void keptTablesMatchFreshLayoutAfterEveryStep() {
        List<Consumer<StandaloneView>> steps = steps();
        StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
        signatures(v);
        long verified0 = PlacementVerifier.verified(), tables0 = PlacementVerifier.verifiedTables();
        long skipped0 = LayoutBox.layoutsSkipped();
        for (int i = 0; i < steps.size(); i++) {
            steps.get(i).accept(v);
            assertEquals(signatures(fresh(steps, i + 1)), signatures(v), "手 " + (i + 1) + " の後");
        }
        assertTrue(LayoutBox.layoutsSkipped() > skipped0, "配置の保持が 1 度も効いていない");
        assertTrue(PlacementVerifier.verified() > verified0, "照合モードが 1 度も走っていない");
        assertTrue(PlacementVerifier.verifiedTables() > tables0, "表を含む部分木の保持が 1 度も検証されていない（表が保持されていない）");
    }
}
