package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 共有したスタイルの結果を再スタイルをまたいで残しても、class の付け外しの往復が使わないときと同じ見た目になり、戻りは解き直さないこと。 */
class StyleShareAcrossPassesTest {

    private static final String HTML = """
            <style>
              body { margin: 0; font-size: 14px }
              .card { padding: 4px; border: 1px solid #ccc; color: #222 }
              .card.on { background: #cde; color: #036; border-color: #036 }
              .card.big { padding: 12px }
              .card span { margin: 2px }
            </style>
            <div id=a class=card><span>a</span><span>b</span></div>
            <div id=b class=card><span>c</span><span>d</span></div>
            <div id=c class=card><span>e</span></div>
            """;

    @AfterEach
    void restore() {
        StyleResolver.ShareCache.enabled = true;
    }

    private static List<String> sig(StandaloneView v) {
        v.ui().flushInvalidation();
        List<String> out = new ArrayList<>();
        for (DisplayItem i : v.ui().renderCache()) {
            out.add(i.id() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height()
                    + "|" + i.color() + "|" + i.bgColor());
        }
        return out;
    }

    private static final String[] CLASSES = {"card", "card on", "card big", "card on big", "card"};

    private static List<List<String>> run(boolean share) {
        StyleResolver.ShareCache.enabled = share;
        StandaloneView v = new StandaloneView(HTML, 300, 300, FontResolver.FALLBACK);
        List<List<String>> out = new ArrayList<>();
        out.add(sig(v));
        HtmlNode target = v.root().getElementById("b");
        for (int round = 0; round < 3; round++) {
            for (String c : CLASSES) {
                target.setAttribute("class", c);
                out.add(sig(v));
            }
        }
        return out;
    }

    @Test
    void togglingClassesMatchesUnsharedAndReturnsHitTheKeptResults() {
        List<List<String>> plain = run(false);
        long before = StyleResolver.ShareCache.totalHits();
        List<List<String>> shared = run(true);
        assertEquals(plain, shared, "class の往復の各手で、使い回しありと無しの見た目が一致");
        assertTrue(StyleResolver.ShareCache.totalHits() - before > 20, "往復の戻りは前に解いた結果を使い回すはず");
    }
}
