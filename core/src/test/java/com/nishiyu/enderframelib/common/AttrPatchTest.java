package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 描画だけに効く属性（progress / meter の value など）の更新が、全体を再レイアウトしたときと項目ごとに同じ結果になり、
 * 実際に変更印の経路（レイアウトなし・署名の比較を省く）に乗ること。
 */
public class AttrPatchTest {

    private static String html(int rows) {
        StringBuilder h = new StringBuilder("<style>body{margin:0;font-size:12px}.row{height:16px}"
                + "progress[value=\"0\"]{opacity:.5}</style>"
                + "<progress id=hp value=10 max=100 style=\"width:100px\"></progress>"
                + "<meter id=mp value=3 min=0 max=10></meter><progress id=plain></progress>");
        for (int i = 0; i < rows; i++) h.append("<div class=row><span>item ").append(i).append("</span> <b>x</b></div>");
        return h.toString();
    }

    @AfterEach
    void restore() {
        UiView.attrPatchEnabled = true;
        UiView.changeStampsEnabled = true;
    }

    private static String sig(DisplayItem i) {
        return i.id() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height()
                + "|" + i.text() + "|" + i.color() + "|" + i.bgColor() + "|" + i.opacity();
    }

    private static List<String> sigs(StandaloneView v) {
        v.ui().flushInvalidation();
        List<String> out = new ArrayList<>();
        for (DisplayItem i : v.ui().renderCache()) out.add(sig(i));
        return out;
    }

    @Test
    public void attributePatchMatchesFullRelayoutAndIsTaken() {
        StandaloneView patched = new StandaloneView(html(80), 320, 400, FontResolver.FALLBACK);
        StandaloneView full = new StandaloneView(html(80), 320, 400, FontResolver.FALLBACK);
        sigs(patched);
        sigs(full);
        long hits = UiView.ATTR_PATCH_HITS.get();
        long skipped = DisplayListBuilder.STAT_STAMPED.sum();
        // value="0" は属性セレクタに掛かる（再スタイルで見た目が変わる）ので、パッチの経路でも結果は一致するはず
        String[][] steps = {{"hp", "value", "55"}, {"mp", "value", "7"}, {"hp", "value", "0"}, {"hp", "value", "80"},
                {"mp", "max", "20"}, {"plain", "value", "5"}, {"hp", "max", "200"}, {"mp", "min", "2"}};
        for (String[] s : steps) {
            patched.root().getNodeById(s[0]).setAttribute(s[1], s[2]);
            List<String> a = sigs(patched);
            UiView.attrPatchEnabled = false;
            try {
                full.root().getNodeById(s[0]).setAttribute(s[1], s[2]);
                List<String> b = sigs(full);
                assertEquals(b, a, String.join(" ", s));
            } finally {
                UiView.attrPatchEnabled = true;
            }
        }
        assertTrue(UiView.ATTR_PATCH_HITS.get() - hits >= 4, "変更印の経路に乗った回数: " + (UiView.ATTR_PATCH_HITS.get() - hits));
        assertTrue(DisplayListBuilder.STAT_STAMPED.sum() - skipped > 100, "比較を省いた箱の数");
    }

    @Test
    public void setAttributeApiAlsoTakesThePathAndRemovalIsCorrect() {
        StandaloneView patched = new StandaloneView(html(20), 320, 400, FontResolver.FALLBACK);
        StandaloneView full = new StandaloneView(html(20), 320, 400, FontResolver.FALLBACK);
        sigs(patched);
        sigs(full);
        HtmlNode hp = patched.root().getNodeById("hp");
        hp.removeAttribute("value");
        List<String> a = sigs(patched);
        UiView.attrPatchEnabled = false;
        full.root().getNodeById("hp").removeAttribute("value");
        List<String> b = sigs(full);
        assertEquals(b, a);
    }

    @Test
    public void benchmarkGaugeInLargeDocument() {
        long[] ms = new long[2];
        for (int mode = 0; mode < 2; mode++) {
            UiView.attrPatchEnabled = mode == 0;
            StandaloneView v = new StandaloneView(html(1500), 800, 600, FontResolver.FALLBACK);
            HtmlNode hp = v.root().getNodeById("hp");
            for (int i = 0; i < 30; i++) { hp.setAttribute("value", String.valueOf(i + 1)); v.ui().flushInvalidation(); }
            long t0 = System.nanoTime();
            for (int i = 0; i < 200; i++) { hp.setAttribute("value", String.valueOf(i + 1)); v.ui().flushInvalidation(); }
            ms[mode] = (System.nanoTime() - t0) / 1_000_000;
        }
        System.out.println("[attr-patch benchmark] 1500 rows, 200 updates: patch=" + ms[0] + "ms full=" + ms[1] + "ms");
    }
}
