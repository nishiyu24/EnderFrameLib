package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlockLayoutEngine#containsFloats}（子孫に float があるか）のメモが、毎回走査したときと同じ結果になること。
 * 同じ手順（class / style の書き換え・要素の追加削除・文字の変更・リサイズ）を、メモあり／なしで走らせて表示リストを比べる。
 * さらにテストでは検証モード（{@code enderframe.layout.verifyfloats}）が常に有効で、メモが使える場面でも走査し直して食い違えば落ちる。
 */
public class FloatContainmentMemoTest {

    private static final String HTML = """
            <style>
              body { margin: 0; font: 12px sans-serif }
              .row { margin: 2px; padding: 2px; border: 1px solid #888 }
              .fl { float: left; width: 30px; height: 20px; background: #ccc }
              .fr { float: right; width: 30px; height: 20px; background: #acd }
              .clr { clear: both }
              .abs { position: absolute; left: 5px; top: 5px }
              .root { display: flow-root }
              .hid { display: none }
            </style>
            <div id=a class=row><div id=a1 class="row fl">f</div><div id=a2 class=row>text <b>b</b> more text more text</div></div>
            <div id=b class=row><div id=b1 class=row><div id=b2 class="row fr">r</div><p id=b3>para</p></div><div id=b4 class="row clr">c</div></div>
            <div id=c class="row root"><div id=c1 class="row fl">x</div><div id=c2 class=row>y</div></div>
            <fieldset id=f><legend id=lg>L</legend><div id=f1 class="row fl">z</div><div id=f2>w</div></fieldset>
            <div id=d class=row><div id=d1 class=row><div id=d2 class=row><span id=s>deep</span></div></div></div>
            <div id=e class=row><ul id=u><li id=l1>one</li><li id=l2 class="fl">two</li><li id=l3>three</li></ul></div>
            """;

    private static final String[] CLASSES = {"row", "row fl", "row fr", "row clr", "row abs", "row root", "row hid", "fl", "root", "", "row fl clr"};
    private static final String[] STYLES = {"", "float:left", "float:right;width:40px", "clear:both", "position:absolute", "display:flow-root",
            "display:none", "overflow:hidden", "display:inline-block", "float:none", "display:flex"};

    /** テスト JVM は検証モード常時有効（走査し直すため速度は測れない）。ベンチの間だけ切る。 */
    private static final boolean VERIFY_DEFAULT = BlockLayoutEngine.verifyFloatMemo;

    @AfterEach
    public void restore() {
        BlockLayoutEngine.floatMemoEnabled = true;
        BlockLayoutEngine.verifyFloatMemo = VERIFY_DEFAULT;
    }

    @Test
    public void memoGivesTheSameDisplayListAsScanningEveryTime() {
        long hitsBefore = BlockLayoutEngine.floatMemoHits;
        for (long seed = 1; seed <= 16; seed++) {
            BlockLayoutEngine.floatMemoEnabled = true;
            List<List<String>> memo = MutationScenario.run(HTML, CLASSES, STYLES, seed, 70);
            BlockLayoutEngine.floatMemoEnabled = false;
            List<List<String>> scan = MutationScenario.run(HTML, CLASSES, STYLES, seed, 70);
            assertEquals(scan.size(), memo.size());
            for (int step = 0; step < scan.size(); step++) {
                assertEquals(scan.get(step).size(), memo.get(step).size(), "seed " + seed + " step " + step + ": 項目の数");
                for (int k = 0; k < scan.get(step).size(); k++) {
                    assertEquals(scan.get(step).get(k), memo.get(step).get(k), "seed " + seed + " step " + step + " 項目 " + k);
                }
            }
        }
        assertTrue(BlockLayoutEngine.floatMemoHits - hitsBefore > 0, "メモが使われている");
    }

    /** 文字だけが変わる更新では形が変わらないので、定常状態ではほぼ走査せずメモで済む。 */
    @Test
    public void steadyTextPatchesReuseTheMemoInsteadOfScanning() {
        StringBuilder html = new StringBuilder("<style>body{margin:0;font-size:12px}.row{height:16px}</style><div id=hud><span id=hp>0</span></div>");
        for (int i = 0; i < 300; i++) html.append("<div class=row><span>item ").append(i).append("</span> <b>x</b></div>");
        StandaloneView v = new StandaloneView(html.toString(), 800, 600, FontResolver.FALLBACK);
        HtmlNode hp = v.root().getNodeById("hp");
        for (int i = 0; i < 20; i++) { hp.updateTextContent("w" + i); v.ui().flushInvalidation(); }
        long scans0 = BlockLayoutEngine.floatMemoScans, hits0 = BlockLayoutEngine.floatMemoHits;
        for (int i = 0; i < 50; i++) { hp.updateTextContent("HP " + i); v.ui().flushInvalidation(); }
        long scans = BlockLayoutEngine.floatMemoScans - scans0, hits = BlockLayoutEngine.floatMemoHits - hits0;
        System.out.println("[float-memo] 300 rows, 50 text patches: hits=" + hits + " scans=" + scans);
        assertTrue(hits > 50 * 100, "毎フレーム多数の箱でメモが使われる: " + hits);
        assertTrue(scans < 50 * 10, "走査はほとんど起きない: " + scans);
    }

    /** 前後比較（CPU 時間、1 フレームごとに交互。表示するだけで、合否には使わない）。 */
    @Test
    public void benchmarkTextPatchInLargeDocument() {
        BlockLayoutEngine.verifyFloatMemo = false;
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        StringBuilder html = new StringBuilder("<style>body{margin:0;font-size:12px}.row{height:16px}#hud{width:200px;height:20px}</style><div id=hud><span id=hp>0</span></div>");
        for (int i = 0; i < 480; i++) html.append("<div class=row><span>item ").append(i).append("</span> <b>x</b></div>");
        StandaloneView v = new StandaloneView(html.toString(), 800, 600, FontResolver.FALLBACK);
        HtmlNode hp = v.root().getNodeById("hp");
        long[] ns = new long[2];
        for (int i = 0; i < 1200; i++) {
            int mode = i & 1;
            BlockLayoutEngine.floatMemoEnabled = mode == 0;
            hp.updateTextContent("HP " + i);
            long t0 = System.nanoTime();
            v.ui().flushInvalidation();
            if (i >= 200) ns[mode] += System.nanoTime() - t0;
        }
        System.out.println("[float-memo benchmark] 480 rows, 500 frames each (wall、1 フレームごとに交互): memo=" + ns[0] / 1_000_000 + "ms scan=" + ns[1] / 1_000_000 + "ms");
    }

    /**
     * 本物の 1,500 行（HTML は {@code HtmlParser.MAX_NODES = 5000} で打ち切られるため DOM API で作る。各要素に一意の ID）。
     * 前後比較（CPU 時間、1 フレームごとに交互。表示するだけ）。
     */
    @Test
    public void benchmarkTextPatchIn1500DomRows() {
        BlockLayoutEngine.verifyFloatMemo = false;
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        StandaloneView v = new StandaloneView("<style>body{margin:0;font-size:12px}.row{height:16px}#hud{width:200px;height:20px}</style><div id=hud><span id=hp>0</span></div><div id=inv></div>",
                800, 600, FontResolver.FALLBACK);
        HtmlNode inv = v.root().getNodeById("inv");
        var doc = v.root().ownerDocument;
        for (int i = 0; i < 1500; i++) {
            HtmlNode r = (HtmlNode) doc.createElement("div"); r.setAttribute("class", "row"); r.setAttribute("id", "r" + i);
            HtmlNode sp = (HtmlNode) doc.createElement("span"); sp.setAttribute("id", "s" + i); sp.appendChild(doc.createTextNode("item " + i));
            HtmlNode b = (HtmlNode) doc.createElement("b"); b.setAttribute("id", "b" + i); b.appendChild(doc.createTextNode("x"));
            r.appendChild(sp); r.appendChild(doc.createTextNode(" ")); r.appendChild(b);
            inv.appendChild(r);
        }
        v.ui().flushInvalidation();
        assertEquals(1500, inv.children.size());
        HtmlNode hp = v.root().getNodeById("hp");
        long[] ns = new long[2];
        long hits0 = 0, scans0 = 0, hitsMemo = 0, scansMemo = 0;
        for (int i = 0; i < 1500; i++) {
            int mode = i & 1;
            BlockLayoutEngine.floatMemoEnabled = mode == 0;
            hp.updateTextContent("HP " + i);
            hits0 = BlockLayoutEngine.floatMemoHits; scans0 = BlockLayoutEngine.floatMemoScans;
            long t0 = System.nanoTime();
            v.ui().flushInvalidation();
            if (i >= 300) { ns[mode] += System.nanoTime() - t0; if (mode == 0) { hitsMemo += BlockLayoutEngine.floatMemoHits - hits0; scansMemo += BlockLayoutEngine.floatMemoScans - scans0; } }
        }
        System.out.println("[float-memo benchmark] 1500 DOM rows, 600 frames each (wall、1 フレームごとに交互): memo=" + ns[0] / 1_000_000 + "ms scan=" + ns[1] / 1_000_000 + "ms; memo frames: hits/frame=" + hitsMemo / 600 + " scans/frame=" + scansMemo / 600);
    }
}
