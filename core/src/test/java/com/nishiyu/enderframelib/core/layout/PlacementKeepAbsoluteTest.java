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
 * C9b・C9c: 絶対配置の包含ブロックが部分木の内側にあれば、インセットを持たない relative とその中の絶対配置を含む部分木も保持してよい。
 * 保持した結果は、同じ状態を最初から作った結果と同じでなければならない。インセットの付け外し（足したオフセットが保持した子孫に残らない）・
 * 包含ブロックの切り替え（relative・transform）・ビューポート単位・リサイズを毎手比べる。
 */
class PlacementKeepAbsoluteTest {

    static final String HTML = "<style>body{margin:0;font-size:12px}"
            + "#app{position:relative;width:300px}.card{height:30px}"
            + ".badge{position:absolute;right:4px;top:4px;width:30px;height:12px}"
            + "#out{position:absolute;left:10px;top:50%;width:50px;height:10px}</style>"
            + "<div id=a>one</div>"
            + "<div id=app><div class=card id=c1>c1</div><div id=wrap><div id=bd class=badge>b</div><div class=card id=t2>t2</div></div>"
            + "<div id=grow class=card>grow</div>"
            // 包含ブロック（app）が部分木の外にある絶対配置。app の高さが変わると動く（hold は変更の鎖の外の兄弟）
            + "<div id=hold><div id=pin style='position:absolute;bottom:0;left:0;width:10px;height:10px'>p</div></div></div>"
            + "<div id=holder><div id=out>out</div></div><div id=c>three</div>"
            // インセットがパーセントの relative: 親の高さが変わるとオフセットが変わる（app2 自身は鎖の外）
            + "<div id=shell style='height:200px'><div id=app2 style='position:relative;top:10%;height:40px'><div id=in2>in2</div></div></div>"
            // アンカー位置指定: アンカーが動くと動く（ph は変更の鎖の外の兄弟）
            + "<div id=anc style='anchor-name:--a;height:20px'>anchor</div>"
            + "<div id=ph style='position:relative'><div id=tip style='position:absolute;position-anchor:--a;top:anchor(bottom);left:0;width:10px;height:10px'>t</div></div>";

    private static List<String> signatures(StandaloneView view) {
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

    private static void style(StandaloneView v, String id, String css) {
        ((HtmlElement) v.root().getNodeById(id)).setAttribute("style", css);
    }

    /** 状態を代入する手（何度適用しても同じ）。毎手の後の状態を、最初から作った画面にも順に適用して比べる。 */
    static List<Consumer<StandaloneView>> steps() {
        List<Consumer<StandaloneView>> s = new ArrayList<>();
        s.add(v -> text(v, "a", "one changed"));
        s.add(v -> text(v, "c1", "c1 changed"));
        s.add(v -> style(v, "app", "top:5px;left:3px"));        // relative にインセットが付く（オフセットが子孫へ入る）
        s.add(v -> text(v, "c1", "c1 again"));                    // オフセットを持つ祖先の下の文字の変更
        s.add(v -> style(v, "app", ""));                          // インセットが消える（入っていたオフセットが残らない）
        s.add(v -> text(v, "t2", "t2 changed"));
        s.add(v -> style(v, "bd", "right:20px;top:6px"));         // 絶対配置のインセットの変更
        s.add(v -> text(v, "a", "one 3"));
        s.add(v -> style(v, "wrap", "position:relative"));        // 包含ブロックが app から wrap へ移る
        s.add(v -> text(v, "c1", "c1 third"));
        s.add(v -> style(v, "wrap", "position:relative;top:2px")); // wrap にインセット
        s.add(v -> text(v, "a", "one 4"));
        s.add(v -> style(v, "wrap", "transform:translateX(1px)"));  // transform も包含ブロックになる
        s.add(v -> text(v, "c", "three changed"));
        s.add(v -> style(v, "bd", "width:50vw;height:12px"));       // ビューポート単位
        s.add(v -> text(v, "t2", "t2 again"));
        s.add(v -> style(v, "wrap", ""));                           // 包含ブロックが app へ戻る
        s.add(v -> text(v, "a", "one 5"));
        s.add(v -> style(v, "grow", "height:60px"));                // 包含ブロックの高さが変わる（外の包含ブロックの絶対配置は保持しない）
        s.add(v -> text(v, "c", "three 2"));
        s.add(v -> style(v, "shell", "height:300px"));              // パーセントのインセットの基準が変わる
        s.add(v -> text(v, "a", "one 6"));
        s.add(v -> style(v, "anc", "anchor-name:--a;height:50px")); // アンカーが動く
        s.add(v -> text(v, "c", "three 3"));
        return s;
    }

    private static StandaloneView fresh(List<Consumer<StandaloneView>> steps, int upTo, int w, int h) {
        StandaloneView v = new StandaloneView(HTML, w, h, FontResolver.FALLBACK);
        signatures(v);
        for (int i = 0; i < upTo; i++) steps.get(i).accept(v);
        return v;
    }

    @Test
    void keptSubtreesMatchFreshLayoutAfterEveryStep() {
        List<Consumer<StandaloneView>> steps = steps();
        StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
        signatures(v);
        long skipped0 = LayoutBox.layoutsSkipped();
        for (int i = 0; i < steps.size(); i++) {
            steps.get(i).accept(v);
            assertEquals(signatures(fresh(steps, i + 1, 400, 300)), signatures(v), "手 " + (i + 1) + " の後");
        }
        assertTrue(LayoutBox.layoutsSkipped() > skipped0, "配置の保持が 1 度も効いていない（最適化が効いていない）");
    }

    @Test
    void resizeAfterKeptPlacementsMatchesFreshLayout() {
        List<Consumer<StandaloneView>> steps = steps();
        StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
        signatures(v);
        for (Consumer<StandaloneView> s : steps) { s.accept(v); signatures(v); }
        v.resize(520, 360);
        assertEquals(signatures(fresh(steps, steps.size(), 520, 360)), signatures(v), "リサイズ");
        text(v, "c1", "after resize");
        StandaloneView f = fresh(steps, steps.size(), 520, 360);
        text(f, "c1", "after resize");
        assertEquals(signatures(f), signatures(v), "リサイズの後の文字の変更");
    }
}
