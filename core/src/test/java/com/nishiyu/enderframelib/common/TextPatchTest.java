package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文字だけの更新（TEXT_PATCH）が、全体を再レイアウトしたときと項目ごとに同じ結果になること。
 * 同じ文書を 2 つ用意し、片方は局所パッチ、もう片方は {@link UiView#textPatchEnabled} を切って全体の再レイアウトで更新して比べる。
 */
public class TextPatchTest {

    private static final String HTML = """
            <style>
              body { margin: 0; font-size: 14px }
              #hud { width: 200px; height: 40px; overflow: hidden; background: #123; color: #fff }
              #c { text-align: center; width: 180px }
              #r { text-align: right; width: 180px }
              .box { margin: 4px; padding: 4px; border: 1px solid #888 }
              span.b { font-weight: bold }
            </style>
            <div id=hud><span id=hp>HP 100</span></div>
            <div id=c class=box>center</div>
            <div id=r class=box>right</div>
            <p id=para>hello <span class=b id=sp>bold</span> world, some wrapping text in a narrow viewport so it spans lines</p>
            <div id=tail class=box>tail</div>
            """;

    @AfterEach
    void restoreFlag() {
        UiView.textPatchEnabled = true;
    }

    private static String sig(DisplayItem i) {
        return i.id() + "|" + i.baseId() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height()
                + "|" + i.text() + "|" + i.color() + "|" + i.bgColor();
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
        throw new IllegalStateException("no text child in " + id);
    }

    /** 局所パッチ側と全体再レイアウト側に同じ変更を加え、結果が一致することを確かめる。 */
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
    public void patchMatchesFullRelayoutAcrossTextMutations() {
        StandaloneView patched = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK);
        StandaloneView full = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK);
        sigs(patched);
        sigs(full);
        long hitsBefore = UiView.TEXT_PATCH_HITS.get();

        step(patched, full, "固定サイズの中", v -> ((HtmlText) textChild(v, "hp")).setData("HP 99"));
        step(patched, full, "固定サイズの中・長く", v -> ((HtmlText) textChild(v, "hp")).setData("HP 100 / 100 (regenerating)"));
        step(patched, full, "中央寄せ", v -> ((HtmlText) textChild(v, "c")).setData("a considerably longer centered text"));
        step(patched, full, "右寄せ", v -> ((HtmlText) textChild(v, "r")).setData("x"));
        step(patched, full, "折り返して高さが増える（後ろの箱が動く）",
                v -> ((HtmlText) textChild(v, "c")).setData("this centered text is long enough that it must wrap onto several lines in a narrow box"));
        step(patched, full, "段落の先頭の文字", v -> ((HtmlText) v.root().getNodeById("para").children.get(0)).setData("goodbye "));
        step(patched, full, "インライン要素の中", v -> ((HtmlText) textChild(v, "sp")).setData("BOLD BOLD BOLD"));
        step(patched, full, "高さが減る", v -> ((HtmlText) textChild(v, "c")).setData("short"));
        // 同じフレームに複数
        step(patched, full, "複数まとめて", v -> {
            ((HtmlText) textChild(v, "hp")).setData("HP 1");
            ((HtmlText) textChild(v, "tail")).setData("tail changed");
            ((HtmlText) textChild(v, "r")).setData("right changed twice");
        });
        // 要素への updateTextContent（バインディングの経路）
        step(patched, full, "updateTextContent", v -> v.root().getNodeById("tail").updateTextContent("via updateTextContent"));
        step(patched, full, "updateTextContent 2 回", v -> {
            v.root().getNodeById("hp").updateTextContent("HP 50");
            v.root().getNodeById("hp").updateTextContent("HP 49");
        });

        assertTrue(UiView.TEXT_PATCH_HITS.get() - hitsBefore >= 10, "局所パッチの経路に乗っている: " + (UiView.TEXT_PATCH_HITS.get() - hitsBefore));
    }

    /** 大きな文書で 1 か所の文字を毎フレーム書き換えるときの、局所パッチと全体再レイアウトの所要時間（表示するだけ）。 */
    @Test
    public void benchmarkHudCounterInLargeDocument() {
        StringBuilder html = new StringBuilder("<style>body{margin:0;font-size:12px}.row{height:16px}</style><div id=hud style=\"width:120px;height:20px;overflow:hidden\"><span id=hp>0</span></div>");
        for (int i = 0; i < 1500; i++) html.append("<div class=row><span>item ").append(i).append("</span> <b>x</b></div>");
        long[] ms = new long[2];
        for (int mode = 0; mode < 2; mode++) {
            UiView.textPatchEnabled = mode == 0;
            StandaloneView v = new StandaloneView(html.toString(), 800, 600, FontResolver.FALLBACK);
            HtmlNode hp = v.root().getNodeById("hp");
            for (int i = 0; i < 30; i++) { hp.updateTextContent("w" + i); v.ui().flushInvalidation(); }
            long t0 = System.nanoTime();
            for (int i = 0; i < 200; i++) { hp.updateTextContent("HP " + i); v.ui().flushInvalidation(); }
            ms[mode] = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("[text-patch breakdown] mode=" + mode + " " + v.ui().lastBreakdown());
        }
        UiView.textPatchEnabled = true;
        System.out.println("[text-patch benchmark] 1500 rows, 200 updates: patch=" + ms[0] + "ms full=" + ms[1] + "ms");
        // 局所の印付けと木全体の印付けを 1 フレームごとに交互に切り替えて比べる（機械の負荷の揺れを打ち消す）
        try {
            StandaloneView v = new StandaloneView(html.toString(), 800, 600, FontResolver.FALLBACK);
            HtmlNode hp = v.root().getNodeById("hp");
            long[] ns = new long[2];
            for (int i = 0; i < 600; i++) {
                int mode = i & 1;
                RenderPipeline.localRelayout = mode == 0;
                hp.updateTextContent("HP " + i);
                long t0 = System.nanoTime();
                v.ui().flushInvalidation();
                if (i >= 100) ns[mode] += System.nanoTime() - t0;
            }
            System.out.println("[text-patch benchmark] interleaved 250 frames each: local=" + ns[0] / 1_000_000
                    + "ms whole-tree-mark=" + ns[1] / 1_000_000 + "ms");
        } finally {
            RenderPipeline.localRelayout = true;
        }
    }

    /** 経路だけの印付け（局所）が、木全体の印付けと項目ごとに同じ結果になり、実際に局所の経路に乗ること。 */
    @Test
    public void localMarkingMatchesFullMarkingAndIsTaken() {
        StandaloneView local = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK);
        StandaloneView whole = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK);
        sigs(local);
        sigs(whole);
        String[] ids = {"hp", "c", "r", "tail", "hp", "c", "hp", "tail", "r"};
        String[] texts = {"HP 9", "a considerably longer centered text that wraps in the box for sure yes", "y",
                "tail text that is long enough to wrap onto a second line in this narrow viewport", "HP 100 / 100",
                "s", "H", "t", "right aligned and rather long, long enough to wrap in this box as well"};
        int localHits = 0;
        try {
            for (int i = 0; i < ids.length; i++) {
                final String id = ids[i], text = texts[i];
                ((HtmlText) textChild(local, id)).setData(text);
                RenderPipeline.localRelayout = true;
                List<String> a = sigs(local);
                if (RenderPipeline.lastRelayoutWasLocal) localHits++;
                ((HtmlText) textChild(whole, id)).setData(text);
                RenderPipeline.localRelayout = false;
                List<String> b = sigs(whole);
                assertEquals(b, a, "手順 " + i + " (" + id + ")");
            }
        } finally {
            RenderPipeline.localRelayout = true;
        }
        assertTrue(localHits >= ids.length - 1, "局所の印付けで済んだ回数: " + localHits);
    }

    /**
     * 箱の変更印で署名の比較を省いた結果が、省かない結果と項目ごとに同じで、実際に比較が省かれること。
     * 高さが変わる更新（後ろの箱が動く）と境界の中だけの更新を混ぜる。検証モード（テストでは常に有効）が、
     * 「変わっていない」と言った箱の署名の食い違いを例外にする。
     */
    @Test
    public void changeStampsMatchFullSignatureComparison() {
        StringBuilder html = new StringBuilder("<style>body{margin:0;font-size:12px}.row{margin:2px}</style>"
                + "<div id=hud style=\"width:120px;height:20px;overflow:hidden\"><span id=hp>0</span></div>"
                + "<p id=para style=\"width:150px\">short</p>");
        for (int i = 0; i < 80; i++) html.append("<div class=row><span>item ").append(i).append("</span> <b>x</b></div>");
        StandaloneView stamped = new StandaloneView(html.toString(), 320, 400, FontResolver.FALLBACK);
        StandaloneView plain = new StandaloneView(html.toString(), 320, 400, FontResolver.FALLBACK);
        sigs(stamped);
        sigs(plain);
        String[] ids = {"hp", "para", "hp", "para", "para", "hp", "hp", "para"};
        String[] texts = {"HP 9", "now this paragraph is long enough that it wraps onto several lines in a 150px box",
                "HP 100 / 100", "tiny", "wrap wrap wrap wrap wrap wrap wrap wrap wrap wrap wrap wrap wrap wrap", "7", "8", "end"};
        long before = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_STAMPED.sum();
        try {
            for (int i = 0; i < ids.length; i++) {
                UiView.changeStampsEnabled = true;
                ((HtmlText) textChild(stamped, ids[i])).setData(texts[i]);
                List<String> a = sigs(stamped);
                UiView.changeStampsEnabled = false;
                ((HtmlText) textChild(plain, ids[i])).setData(texts[i]);
                List<String> b = sigs(plain);
                assertEquals(b, a, "手順 " + i + " (" + ids[i] + ")");
            }
        } finally {
            UiView.changeStampsEnabled = true;
        }
        long skipped = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_STAMPED.sum() - before;
        assertTrue(skipped > 100, "変更印で比較を省いた箱の数: " + skipped);
    }

    /** 定常の文字パッチ 1 フレームで、描画順の分類用のスタイル値（StyleFacts）を新しく算出しないこと（キャッシュ退行の検出）。 */
    @Test
    public void steadyPatchComputesNoStyleFacts() {
        StringBuilder html = new StringBuilder("<style>body{margin:0;font-size:12px}</style><div id=hud style=\"width:120px;height:20px;overflow:hidden\"><span id=hp>0</span></div>");
        for (int i = 0; i < 100; i++) html.append("<div class=row><span>item ").append(i).append("</span> <b>x</b></div>");
        StandaloneView v = new StandaloneView(html.toString(), 320, 400, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        HtmlNode hp = v.root().getNodeById("hp");
        for (int i = 0; i < 3; i++) { hp.updateTextContent("w" + i); v.ui().flushInvalidation(); }
        long before = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.styleFactsComputed();
        for (int i = 0; i < 5; i++) { hp.updateTextContent("HP " + i); v.ui().flushInvalidation(); }
        assertTrue(RenderPipeline.lastRelayoutWasLocal);
        long computed = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.styleFactsComputed() - before;
        assertTrue(computed < 10, "StyleFacts を算出した回数: " + computed);
    }

    /** 固定サイズの境界の中の更新は、境界の外の箱を測り直さない（測り直した箱の数で固定する）。 */
    @Test
    public void updateInsideBoundaryMeasuresOnlyThePath() {
        StringBuilder html = new StringBuilder("<style>body{margin:0;font-size:12px}.row{height:16px}</style>"
                + "<div id=hud style=\"width:120px;height:20px;overflow:hidden\"><span id=hp>0</span></div>");
        for (int i = 0; i < 300; i++) html.append("<div class=row><span>item ").append(i).append("</span> <b>x</b></div>");
        StandaloneView v = new StandaloneView(html.toString(), 800, 600, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        HtmlNode hp = v.root().getNodeById("hp");
        for (int i = 0; i < 3; i++) { hp.updateTextContent("w" + i); v.ui().flushInvalidation(); }
        long before = com.nishiyu.enderframelib.core.layout.LayoutBox.measurementsComputed();
        hp.updateTextContent("HP 77");
        v.ui().flushInvalidation();
        long measured = com.nishiyu.enderframelib.core.layout.LayoutBox.measurementsComputed() - before;
        assertTrue(RenderPipeline.lastRelayoutWasLocal);
        assertTrue(measured < 10, "境界の中だけ測り直す: " + measured);
    }

    @Test
    public void textOnlyChangeRaisesTextPatchNotRelayout() {
        StandaloneView v = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK);
        sigs(v);
        v.root().getNodeById("hp").updateTextContent("HP 7");
        assertEquals(UiView.InvalidationLevel.TEXT_PATCH, v.ui().invalidationLevel());
        // 文字が同じなら何も起きない
        v.ui().flushInvalidation();
        v.root().getNodeById("hp").updateTextContent("HP 7");
        assertEquals(UiView.InvalidationLevel.NONE, v.ui().invalidationLevel());
    }

    @Test
    public void unsafeChangesFallBackToFullRelayoutAndStayCorrect() {
        StandaloneView patched = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK);
        StandaloneView full = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK);
        sigs(patched);
        sigs(full);
        long fallbacksBefore = UiView.TEXT_PATCH_FALLBACKS.get();

        step(patched, full, "空白だけにする", v -> ((HtmlText) textChild(v, "tail")).setData("   "));
        step(patched, full, "空白から戻す", v -> ((HtmlText) textChild(v, "tail")).setData("back again"));
        step(patched, full, "空にする", v -> ((HtmlText) textChild(v, "tail")).setData(""));
        step(patched, full, "空から戻す", v -> v.root().getNodeById("tail").updateTextContent("restored"));
        step(patched, full, "要素の setTextContent", v -> v.root().getNodeById("tail").setTextContent("replaced node"));
        step(patched, full, "文字と属性の同時変更", v -> {
            ((HtmlText) textChild(v, "tail")).setData("mixed change");
            ((com.nishiyu.enderframelib.core.dom.node.HtmlElement) v.root().getNodeById("tail")).setAttribute("class", "box hot");
        });

        assertTrue(UiView.TEXT_PATCH_FALLBACKS.get() - fallbacksBefore >= 1, "条件に合わない更新は全体の再レイアウトへ退く");
    }
}
