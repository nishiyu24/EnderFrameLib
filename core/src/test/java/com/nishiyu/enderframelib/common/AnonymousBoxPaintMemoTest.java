package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 混在の内容〔インラインとブロックの兄弟〕で、インラインの途中へブロックを差し込んだとき、再利用された匿名ボックスの描画の記憶が
 * 古いまま当たって項目が二重に出る不具合（K5b L2 の調査で、差分でない基準と比べて見つかった。それ以前から存在）。
 * 原因: 子を足した作りたての匿名ボックスが、親へ付く前に印〔{@code PaintStamps.mark}〕を付けて自分だけの待ちリストへ積まれ、
 * 印が立ったまま取り残されて、再利用後の構造の印が確定しなかった。同じ状態を、記憶の無いビルダーで最初から作った表示リストと比べる。
 */
public class AnonymousBoxPaintMemoTest {
    private static final String BASE = "<style>.row{padding:3px;margin:2px 0}</style>";
    private static final String MIXED = BASE + "<div id=m>lead text <b id=b>bold</b> <div class=row id=r0>r0</div><div class=row id=r1>r1</div>tail</div>";

    private static HtmlElement block(String text) {
        HtmlElement e = new HtmlElement("div");
        e.setAttribute("class", "row");
        e.updateTextContent(text);
        return e;
    }

    private static void check(String html, Consumer<StandaloneView> mutate, String what) {
        try (StandaloneView v = new StandaloneView(html, 420, 520, FontResolver.FALLBACK)) {
            UiView ui = v.ui();
            ui.flushInvalidation();
            mutate.accept(v);
            ui.flushInvalidation();
            List<String> live = new ArrayList<>(), fresh = new ArrayList<>();
            for (DisplayItem i : ui.renderCache()) live.add(i.id() + "|" + i.type() + "|" + i.text());
            for (DisplayItem i : new DisplayListBuilder(420, 520, ui.document().sheet.keyframes, ui.scrollState()).render(ui.layoutRoot(), false)) {
                fresh.add(i.id() + "|" + i.type() + "|" + i.text());
            }
            assertEquals(fresh, live, what);
        }
    }

    @Test
    void blockInsertedBeforeInlineContentDoesNotLeaveStaleItems() {
        check(MIXED, v -> v.root().getNodeById("m").insertBefore(block("new"), v.root().getNodeById("b")), "インラインの途中（<b> の前）");
        check(MIXED, v -> v.root().getNodeById("m").insertBefore(block("new w w w w"), v.root().getNodeById("b")), "長い文字のブロック");
        check(MIXED, v -> {
            HtmlNode m = v.root().getNodeById("m");
            m.insertBefore(block("new"), m.children.get(0));
        }, "先頭");
        check(MIXED, v -> v.root().getNodeById("m").insertBefore(block("new"), v.root().getNodeById("r0")), "ブロックの前");
        check(MIXED, v -> v.root().getNodeById("m").appendChild(block("new")), "末尾");
    }

    @Test
    void repeatedInsertAndRemoveInMixedContentStaysConsistent() {
        try (StandaloneView v = new StandaloneView(MIXED, 420, 520, FontResolver.FALLBACK)) {
            UiView ui = v.ui();
            ui.flushInvalidation();
            HtmlNode m = v.root().getNodeById("m");
            for (int i = 0; i < 8; i++) {
                HtmlElement add = block("n" + i);
                m.insertBefore(add, i % 2 == 0 ? v.root().getNodeById("b") : v.root().getNodeById("r1"));
                ui.flushInvalidation();
                if (i % 3 == 2) {
                    m.removeChild(add);
                    ui.flushInvalidation();
                }
                List<String> live = new ArrayList<>(), fresh = new ArrayList<>();
                for (DisplayItem it : ui.renderCache()) live.add(it.id() + "|" + it.type() + "|" + it.text());
                for (DisplayItem it : new DisplayListBuilder(420, 520, ui.document().sheet.keyframes, ui.scrollState()).render(ui.layoutRoot(), false)) {
                    fresh.add(it.id() + "|" + it.type() + "|" + it.text());
                }
                assertEquals(fresh, live, "手 " + i);
            }
        }
    }
}
