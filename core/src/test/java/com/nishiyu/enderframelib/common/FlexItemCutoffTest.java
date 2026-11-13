package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * flex コンテナの中の確定幅のアイテム（overflow:hidden のカード）の中身が変わっても、外寸が同じならコンテナの測定を
 * 使い回す。使い回した結果が、毎回全体を再レイアウトした結果と項目ごとに同じであること（高さが変わる・stretch で
 * 伸ばされている・baseline 揃え・伸縮するコンテナを含む）と、測り直しの数が全アイテムに及ばないこと。
 */
class FlexItemCutoffTest {
    private static String html() {
        StringBuilder wrap = new StringBuilder();
        StringBuilder base = new StringBuilder();
        StringBuilder shrink = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            wrap.append("<div class=card id=w").append(i).append("><div class=n id=wn").append(i).append(">name ").append(i)
                    .append("</div><div class=d>fixed text here</div><div class=row><span id=wp").append(i).append(">$").append(10 + i)
                    .append("</span><span>stock</span></div></div>");
        }
        for (int i = 0; i < 6; i++) {
            base.append("<div class=card2 id=b").append(i).append("><span id=bt").append(i).append(">base ").append(i).append("</span></div>");
            shrink.append("<div class=card3 id=s").append(i).append("><span id=st").append(i).append(">shrink ").append(i).append("</span></div>");
        }
        return "<style>body{margin:0;font-size:12px}"
                + ".card{width:90px;overflow:hidden;background:#eee;margin:2px}.n{font-weight:bold}.d{height:14px;overflow:hidden}"
                + ".row{display:flex;justify-content:space-between}"
                + "#wrap{display:flex;flex-wrap:wrap;gap:4px;width:420px}"
                + "#base{display:flex;align-items:baseline;width:420px}.card2{width:60px;overflow:hidden;font-size:13px}"
                + "#shrink{display:flex;width:150px}.card3{width:80px;overflow:hidden;background:#ddd}"
                + "#after{height:20px;background:#fc0}</style>"
                + "<div id=wrap>" + wrap + "</div><div id=base>" + base + "</div><div id=shrink>" + shrink + "</div><div id=after>after</div>";
    }

    @AfterEach
    void restore() {
        UiView.textPatchEnabled = true;
    }

    private static String sig(DisplayItem i) {
        return i.id() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height() + "|" + i.text() + "|" + i.color() + "|" + i.bgColor();
    }

    private static List<String> sigs(StandaloneView view) {
        view.ui().flushInvalidation();
        List<String> out = new ArrayList<>();
        for (DisplayItem i : view.ui().renderCache()) out.add(sig(i));
        return out;
    }

    private static HtmlNode textChild(StandaloneView v, String id) {
        HtmlNode el = v.root().getNodeById(id);
        for (HtmlNode c : el.children) if (c instanceof HtmlText) return c;
        throw new IllegalStateException("no text in " + id);
    }

    private static void step(StandaloneView patched, StandaloneView full, String name, Consumer<StandaloneView> mutate) {
        mutate.accept(patched);
        UiView.textPatchEnabled = false;
        try {
            mutate.accept(full);
        } finally {
            UiView.textPatchEnabled = true;
        }
        List<String> a = sigs(patched);
        List<String> b = sigs(full);
        assertEquals(b.size(), a.size(), name + ": 項目の数");
        for (int i = 0; i < b.size(); i++) assertEquals(b.get(i), a.get(i), name + ": 項目 " + i);
    }

    @Test
    void cutoffMatchesFullRelayout() {
        StandaloneView patched = new StandaloneView(html(), 440, 400, FontResolver.FALLBACK);
        StandaloneView full = new StandaloneView(html(), 440, 400, FontResolver.FALLBACK);
        sigs(patched);
        sigs(full);
        String[] texts = {"$5", "$1234", "x", "a considerably longer name that wraps over lines", "name", "w", "two words", "$99999999"};
        Random rnd = new Random(11);
        for (int s = 0; s < 120; s++) {
            int k = rnd.nextInt(40);
            String id;
            switch (rnd.nextInt(4)) {
                case 0 -> id = "wp" + k;
                case 1 -> id = "wn" + k;
                case 2 -> id = "bt" + rnd.nextInt(6);
                default -> id = "st" + rnd.nextInt(6);
            }
            String t = texts[rnd.nextInt(texts.length)];
            final String fid = id;
            step(patched, full, "手 " + s + " " + id + " -> " + t, v -> ((HtmlText) textChild(v, fid)).setData(t));
            if (s % 7 == 0) {
                // 同じフレームに複数
                String a = "wp" + rnd.nextInt(40), b = "wn" + rnd.nextInt(40), t2 = texts[rnd.nextInt(texts.length)];
                step(patched, full, "手 " + s + " 複数", v -> {
                    ((HtmlText) textChild(v, a)).setData(t2);
                    ((HtmlText) textChild(v, b)).setData(t2);
                });
            }
        }
    }

    /** 同じ行の背の高いカードに stretch で伸ばされているカードの中身が、行より高くなる（行の高さが増える）。 */
    @Test
    void stretchedCardGrowingBeyondLineStillGrowsTheLine() {
        StandaloneView patched = new StandaloneView(html(), 440, 400, FontResolver.FALLBACK);
        StandaloneView full = new StandaloneView(html(), 440, 400, FontResolver.FALLBACK);
        sigs(patched);
        sigs(full);
        String mid = "a considerably longer name that wraps over lines";
        String big = mid + " and even more words to wrap further down the narrow card width until it is taller";
        step(patched, full, "背の高いカード", v -> ((HtmlText) textChild(v, "wn0")).setData(mid));
        step(patched, full, "伸ばされているカードの文字（外寸は同じ）", v -> ((HtmlText) textChild(v, "wp1")).setData("$7"));
        step(patched, full, "伸ばされているカードが行より高くなる", v -> ((HtmlText) textChild(v, "wn1")).setData(big));
        step(patched, full, "さらに戻す", v -> ((HtmlText) textChild(v, "wn1")).setData("n"));
    }

    @Test
    void stableCardChangeDoesNotRemeasureEveryItem() {
        StandaloneView v = new StandaloneView(html(), 440, 400, FontResolver.FALLBACK);
        sigs(v);
        // 幅は変わるが高さは変わらない文字（カードの外寸は同じ）
        long before = LayoutBox.measurementsComputed();
        ((HtmlText) textChild(v, "wp3")).setData("$99");
        sigs(v);
        long measured = LayoutBox.measurementsComputed() - before;
        assertTrue(measured < 40 * 3, "カード 40 枚の測り直しが起きた: " + measured);
    }
}
