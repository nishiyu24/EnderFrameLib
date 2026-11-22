package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * C9: スクロールコンテナを子孫に持つ祖先の配置は、スクロール量の版（{@code ScrollState.version()}）が前回と同じなら保持してよい。
 * 保持した結果は、毎回作り直した結果と同じでなければならない（外れると、スクロールの後に古い位置が残る）。
 * 増分で動かした画面と、同じ状態を最初から作った画面の表示項目を、変更とスクロールのたびに比べる。
 */
class PlacementKeepModuloScrollTest {

    private static final String HTML = "<style>body{margin:0;font-size:12px}.sc{width:200px;height:50px;overflow:auto}.row{height:20px}</style>"
            + "<div id=a>one</div>"
            + "<div id=wrap><div id=sc class=sc><div class=row>r1</div><div class=row>r2</div><div class=row>r3</div><div class=row>r4</div>"
            + "<div class=row>r5</div><div class=row>r6</div><div class=row>r7</div><div class=row>r8</div></div><div id=b>two</div></div>"
            + "<div id=c>three</div>";

    /** 箱の木の位置・大きさ（スクロールの描画上のずらしは表示項目の外にあるので、箱の座標で比べる）。 */
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

    private static HtmlText textOf(StandaloneView v, String id) {
        for (HtmlNode c : v.root().getNodeById(id).children) if (c instanceof HtmlText t) return t;
        throw new IllegalStateException("no text under " + id);
    }

    private static void scrollTo(StandaloneView v, float y) {
        v.ui().scrollState().setScroll(v.root().getNodeById("sc"), 0, y);
        v.ui().requestScrollReposition();
    }

    /** 同じ状態（a・c の文字とスクロール量）を最初から作った画面。 */
    private static StandaloneView fresh(String a, String c, float scrollY) {
        return fresh(HTML, a, c, scrollY, 0);
    }

    private static StandaloneView fresh(String html, String a, String c, float scrollY, float innerY) {
        StandaloneView v = new StandaloneView(html, 400, 300, FontResolver.FALLBACK);
        signatures(v);
        textOf(v, "a").setData(a);
        textOf(v, "c").setData(c);
        if (scrollY != 0 || innerY != 0) {
            v.ui().scrollState().setScroll(v.root().getNodeById("sc"), 0, scrollY);
            if (v.root().getNodeById("inner") != null) v.ui().scrollState().setScroll(v.root().getNodeById("inner"), 0, innerY);
            v.ui().requestScrollReposition();
        }
        return v;
    }

    @Test
    void keptAncestorsMatchFreshLayoutAcrossTextChangesAndScrolls() {
        StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
        signatures(v);
        long skippedBefore = LayoutBox.layoutsSkipped();

        textOf(v, "a").setData("one changed");
        assertEquals(signatures(fresh("one changed", "three", 0)), signatures(v), "文字の変更");
        assertTrue(LayoutBox.layoutsSkipped() > skippedBefore, "スクロールコンテナを含む祖先の配置が保持されない（最適化が効いていない）");

        scrollTo(v, 35);
        assertEquals(signatures(fresh("one changed", "three", 35)), signatures(v), "スクロール");

        textOf(v, "c").setData("three changed");
        assertEquals(signatures(fresh("one changed", "three changed", 35)), signatures(v), "スクロール後の文字の変更（古いスクロール位置が残らない）");

        scrollTo(v, 0);
        assertEquals(signatures(fresh("one changed", "three changed", 0)), signatures(v), "スクロールを戻す");

        textOf(v, "a").setData("one");
        assertEquals(signatures(fresh("one", "three changed", 0)), signatures(v), "戻した後の文字の変更");
    }

    /** 入れ子のスクロールコンテナ: 内側だけ・外側だけ・両方を動かしても、文字の変更の後でも、最初から作った結果と同じ。 */
    private static final String NESTED = "<style>body{margin:0;font-size:12px}.sc{width:200px;height:60px;overflow:auto}.in{width:150px;height:30px;overflow:auto}.row{height:20px}</style>"
            + "<div id=a>one</div><div id=wrap><div id=sc class=sc><div class=row>o1</div>"
            + "<div id=inner class=in><div class=row>i1</div><div class=row>i2</div><div class=row>i3</div><div class=row>i4</div></div>"
            + "<div class=row>o2</div><div class=row>o3</div><div class=row>o4</div></div></div><div id=c>three</div>";

    @Test
    void nestedScrollContainersMatchFreshLayout() {
        StandaloneView v = new StandaloneView(NESTED, 400, 300, FontResolver.FALLBACK);
        signatures(v);
        textOf(v, "a").setData("one x");
        assertEquals(signatures(fresh(NESTED, "one x", "three", 0, 0)), signatures(v), "文字");
        v.ui().scrollState().setScroll(v.root().getNodeById("inner"), 0, 25);
        v.ui().requestScrollReposition();
        assertEquals(signatures(fresh(NESTED, "one x", "three", 0, 25)), signatures(v), "内側のスクロール");
        textOf(v, "c").setData("three x");
        assertEquals(signatures(fresh(NESTED, "one x", "three x", 0, 25)), signatures(v), "内側のスクロール後の文字");
        v.ui().scrollState().setScroll(v.root().getNodeById("sc"), 0, 30);
        v.ui().requestScrollReposition();
        assertEquals(signatures(fresh(NESTED, "one x", "three x", 30, 25)), signatures(v), "外側のスクロール");
        v.ui().scrollState().setScroll(v.root().getNodeById("inner"), 0, 0);
        v.ui().requestScrollReposition();
        textOf(v, "a").setData("one");
        assertEquals(signatures(fresh(NESTED, "one", "three x", 30, 0)), signatures(v), "内側を戻した後の文字");
    }
}
