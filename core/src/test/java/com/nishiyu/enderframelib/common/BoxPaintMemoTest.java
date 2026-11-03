package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 箱ごとの表示項目の記憶（{@code DisplayListBuilder} の BoxMemo）が、描き直した結果と同じものを返すこと。
 * 窓と同じく 1 つのビルダーを使い回して DOM を書き換え続け、毎回、新しいビルダーで作った結果と項目ごとに比べる。
 * あわせて、部品の ID が箱ごとに数えられ、他の箱の増減で動かないこと。
 */
public class BoxPaintMemoTest {

    private static final String HTML = """
            <style>
              body { margin: 0; font-size: 14px }
              .card { margin: 4px; padding: 6px; border: 1px solid #888; border-radius: 6px; background: #eef }
              .hot { background: #fdd; color: #900 }
              .fade { animation: pulse 2s infinite }
              @keyframes pulse { from { opacity: .4 } to { opacity: 1 } }
              span.b { font-weight: bold; text-shadow: 1px 1px 2px #000 }
            </style>
            <div id=list></div>
            <p id=para>hello <span class=b id=sp>bold</span> world, wrapped text that is long enough to need a few lines in a narrow box</p>
            <div id=tail class="card fade">tail</div>
            """;

    private static String sig(DisplayItem i) {
        return i.id() + "|" + i.baseId() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height()
                + "|" + i.text() + "|" + i.color() + "|" + i.bgColor() + "|" + i.radius() + "|" + i.opacity()
                + "|" + i.translateX() + "," + i.translateY() + "," + i.scaleX() + "," + i.scaleY() + "," + i.rotate()
                + "|" + i.transitionDuration() + "|" + i.events() + "|" + (i.animDef() == null ? "" : i.animDef().frames().size());
    }

    private static List<String> sigs(List<DisplayItem> items) {
        List<String> out = new ArrayList<>();
        for (DisplayItem i : items) out.add(sig(i));
        return out;
    }

    private static void assertSameAsFresh(StandaloneView view, String step) {
        UiView ui = view.ui();
        ui.flushInvalidation();
        List<String> memoized = sigs(ui.renderCache());
        DisplayListBuilder fresh = new DisplayListBuilder(320, 240, ui.document().sheet.keyframes, ui.scrollState());
        List<String> expected = sigs(fresh.render(ui.layoutRoot(), false));
        assertEquals(expected.size(), memoized.size(), step + ": 項目の数");
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), memoized.get(i), step + ": 項目 " + i);
    }

    private static int madeIds;

    private static HtmlElement el(StandaloneView view, String tag, String cls, String text) {
        HtmlElement e = (HtmlElement) view.root().ownerDocument.createElement(tag);
        e.setAttribute("id", "made" + (++madeIds)); // DOM API で作った要素は id を持たないので明示する
        e.setAttribute("class", cls);
        e.setTextContent(text);
        return e;
    }

    @Test
    public void reusedBuilderMatchesFreshBuilderAcrossMutations() {
        StandaloneView view = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK);
        HtmlNode list = view.root().getNodeById("list");
        assertSameAsFresh(view, "初回");

        for (int i = 0; i < 6; i++) {
            list.appendChild(el(view, "div", "card", "row " + i));
            assertSameAsFresh(view, "追加 " + i);
        }
        list.children.get(2).setTextContent("changed text that is somewhat longer than before");
        assertSameAsFresh(view, "文字の書き換え");

        ((HtmlElement) list.children.get(3)).setAttribute("class", "card hot");
        assertSameAsFresh(view, "class の書き換え");

        ((HtmlElement) list.children.get(1)).setAttribute("style", "width:120px;padding:12px");
        assertSameAsFresh(view, "style の書き換え");

        ((HtmlElement) view.root().getNodeById("sp")).setAttribute("class", "");
        assertSameAsFresh(view, "インライン要素の書き換え");

        list.removeChild(list.children.get(0));
        assertSameAsFresh(view, "先頭の削除");

        view.root().getNodeById("para").setTextContent("replaced paragraph");
        assertSameAsFresh(view, "段落の置き換え");
    }

    @Test
    public void partIdsDoNotShiftWhenAnEarlierBoxAppears() {
        StandaloneView view = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK);
        UiView ui = view.ui();
        Set<String> before = new HashSet<>();
        for (DisplayItem i : ui.renderCache()) if (i.baseId().equals("tail") || i.baseId().equals("para")) before.add(i.id());
        assertTrue(before.size() > 2, "tail と para の部品がある");

        HtmlNode list = view.root().getNodeById("list");
        list.appendChild(el(view, "div", "card", "a new earlier row with text"));
        ui.flushInvalidation();
        Set<String> after = new HashSet<>();
        for (DisplayItem i : ui.renderCache()) if (i.baseId().equals("tail") || i.baseId().equals("para")) after.add(i.id());
        assertEquals(before, after, "前に箱が増えても、後ろの箱の部品の ID は変わらない");
    }

    private static final String MEDIA_HTML = """
            <style>
              body { margin: 0; font-size: 14px }
              .hp { width: 120px; height: 12px }
              .icon { width: 16px; height: 16px }
            </style>
            <div id=hud>
              <progress id=hp class=hp value=30 max=100></progress>
              <meter id=mp class=hp value=5 min=0 max=10></meter>
              <img id=ic class=icon src="data:image/png;base64,iVBORw0KGgo=">
              <mc-fluid id=fl class=icon fluid="minecraft:water"></mc-fluid>
              <mc-entity id=en class=icon entity="@self"></mc-entity>
            </div>
            <div id=after>after</div>
            """;

    @Test
    public void mediaBoxesAreMemoizedAndStayCorrectAcrossAttributeChanges() {
        StandaloneView view = new StandaloneView(MEDIA_HTML, 320, 240, FontResolver.FALLBACK);
        UiView ui = view.ui();
        assertSameAsFresh(view, "初回");

        // 属性が変わらない再描画では、media 箱 5 つは記憶からそのまま出る
        long exact = DisplayListBuilder.STAT_EXACT.sum();
        view.root().getNodeById("after").updateTextContent("after!"); // 箱を作り直さない局所パッチ
        ui.flushInvalidation();
        assertTrue(DisplayListBuilder.STAT_EXACT.sum() - exact >= 5, "progress/meter/img/mc-* が記憶から出る");
        assertSameAsFresh(view, "無関係な書き換え");

        ((HtmlElement) view.root().getNodeById("hp")).setAttribute("value", "80");
        assertSameAsFresh(view, "progress value");
        ((HtmlElement) view.root().getNodeById("hp")).setAttribute("max", "200");
        assertSameAsFresh(view, "progress max");
        ((HtmlElement) view.root().getNodeById("mp")).setAttribute("value", "9");
        assertSameAsFresh(view, "meter value");
        ((HtmlElement) view.root().getNodeById("ic")).setAttribute("src", "data:image/png;base64,AAAA");
        assertSameAsFresh(view, "img src");
        ((HtmlElement) view.root().getNodeById("fl")).setAttribute("fluid", "minecraft:lava");
        assertSameAsFresh(view, "mc-fluid");
        ((HtmlElement) view.root().getNodeById("en")).setAttribute("entity", "@target");
        assertSameAsFresh(view, "mc-entity");
        ((HtmlElement) view.root().getNodeById("ic")).setAttribute("srcset", "a.png 1x, b.png 2x");
        assertSameAsFresh(view, "srcset 付き img");
    }
}
