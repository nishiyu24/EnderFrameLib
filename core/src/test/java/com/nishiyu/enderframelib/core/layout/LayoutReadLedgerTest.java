package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * 読み取りの台帳（K1: {@link ReadKind}・{@link LayoutReads}）の契約テスト。
 *
 * <p>①全 27 種別（測定 18＋配置 9）が {@link ReadKind} に登録され 32-bit ビット集合へ収まること、
 * ②配置の入口を通るだけで読む種別が自動記録され、読まない種別は立たないこと、
 * ③子のスクロール読みが親の {@link ReadKind#PLACE_DESCENDANT_SCROLL} へ伝播すること、
 * ④スコープ解決規則 {@link ReadKind#isInsideSubtree} が内外を正しく分けること、
 * ⑤並列配置でもスレッドごとに独立して記録されることを検証する。</p>
 */
class LayoutReadLedgerTest {

    @Test
    void readKindRegistryCoversAll27KindsIn32Bits() {
        ReadKind[] all = ReadKind.values();
        assertEquals(27, all.length, "測定入力 9 + 親読取 9 + 配置 9 = 27 種");
        int union = 0;
        Set<Integer> globals = new HashSet<>();
        for (ReadKind k : all) {
            int bit = k.globalBit();
            assertNotEquals(0, bit);
            assertTrue(globals.add(bit), "globalBit が重複: " + k);
            union |= bit;
        }
        assertEquals((1 << 27) - 1, union);

        // MeasureReads の定数と 1:1 で一致すること
        assertEquals(MeasureReads.READ_AVAIL_INLINE, ReadKind.AVAIL_INLINE.stageBit());
        assertEquals(MeasureReads.READ_PERCENT_BLOCK, ReadKind.PERCENT_BLOCK.stageBit());
        assertEquals(MeasureReads.READ_SHRINK, ReadKind.SHRINK.stageBit());
        assertEquals(MeasureReads.READ_STRETCH, ReadKind.STRETCH.stageBit());
        assertEquals(MeasureReads.READ_VIEWPORT_W, ReadKind.VIEWPORT_W.stageBit());
        assertEquals(MeasureReads.READ_VIEWPORT_H, ReadKind.VIEWPORT_H.stageBit());
        assertEquals(MeasureReads.READ_SHAPE_EPOCH, ReadKind.SHAPE_EPOCH.stageBit());
        assertEquals(MeasureReads.READ_FIT_BOUNDED, ReadKind.FIT_BOUNDED.stageBit());
        assertEquals(MeasureReads.READ_FILL_FIT, ReadKind.FILL_FIT.stageBit());

        assertEquals(MeasureReads.PARENT_READ_OUTER, ReadKind.PARENT_OUTER.stageBit());
        assertEquals(MeasureReads.PARENT_READ_CONTENT_EXTENT, ReadKind.PARENT_CONTENT_EXTENT.stageBit());
        assertEquals(MeasureReads.PARENT_READ_MIN_CONTENT, ReadKind.PARENT_MIN_CONTENT.stageBit());
        assertEquals(MeasureReads.PARENT_READ_FIRST_BASELINE, ReadKind.PARENT_FIRST_BASELINE.stageBit());
        assertEquals(MeasureReads.PARENT_READ_LAST_BASELINE, ReadKind.PARENT_LAST_BASELINE.stageBit());
        assertEquals(MeasureReads.PARENT_READ_FIT_NEED, ReadKind.PARENT_FIT_NEED.stageBit());
        assertEquals(MeasureReads.PARENT_READ_STRETCHED, ReadKind.PARENT_STRETCHED.stageBit());
        assertEquals(MeasureReads.PARENT_READ_MULTI_SPACE, ReadKind.PARENT_MULTI_SPACE.stageBit());
        assertEquals(MeasureReads.PARENT_READ_OPAQUE, ReadKind.PARENT_OPAQUE.stageBit());
    }

    @Test
    void placementRecordsOnlyActuallyReadExternalCategories() {
        String html = "<style>"
                + "body{margin:0;font-size:12px}"
                + "#plain{width:100px;height:20px}"
                + "#relPct{position:relative;left:10%;width:50px;height:20px}"
                + "#relVw{position:relative;left:5vw;width:50px;height:20px}"
                + "#fix{position:fixed;top:10px;left:10px;width:40px;height:20px}"
                + "#cb{position:relative;width:200px;height:100px;overflow:auto}"
                + "#abs{position:absolute;right:5px;top:5px;width:20px;height:10px}"
                + "#anc{anchor-name:--a;width:40px;height:20px}"
                + "#tip{position:absolute;position-anchor:--a;top:anchor(bottom);left:0;width:20px;height:10px}"
                + "#wrapScroll{width:180px;height:60px}"
                + "#sc{width:150px;height:40px;overflow:auto}"
                + "#st{position:sticky;top:4px;height:10px}"
                + "#flWrap{width:150px;overflow:hidden}"
                + "#fl{float:left;width:30px;height:10px}"
                + "</style>"
                + "<div id=plain>p</div>"
                + "<div id=relPct>rp</div>"
                + "<div id=relVw>rv</div>"
                + "<div id=fix>fx</div>"
                + "<div id=cb><div id=abs>ab</div></div>"
                + "<div id=anc>an</div>"
                + "<div id=tip>tp</div>"
                + "<div id=wrapScroll><div id=sc><div id=st>st</div><div style='height:120px'>t</div></div></div>"
                + "<div id=flWrap><div id=fl>fl</div>in</div>";

        Map<String, Integer> masks = new HashMap<>();
        LayoutReads.withPlaceRecorder((box, mask) -> {
            if (box.node != null) {
                String id = box.node.attrs.get("id");
                if (id != null && !id.isEmpty()) masks.put(id, mask);
            }
        }, () -> {
            StandaloneView v = new StandaloneView(html, 400, 300, FontResolver.FALLBACK);
            v.ui().flushInvalidation();
        });

        int plain = masks.get("plain");
        assertHas(plain, ReadKind.PLACE_ORIGIN);
        assertLacks(plain, ReadKind.PLACE_PARENT_OUTER, ReadKind.PLACE_SELF_SCROLL,
                ReadKind.PLACE_DESCENDANT_SCROLL, ReadKind.PLACE_CONTAINING_BLOCK,
                ReadKind.PLACE_VIEWPORT, ReadKind.PLACE_ANCHOR, ReadKind.PLACE_FLOAT, ReadKind.PLACE_STICKY);

        int relPct = masks.get("relPct");
        assertHas(relPct, ReadKind.PLACE_ORIGIN, ReadKind.PLACE_PARENT_OUTER);
        assertLacks(relPct, ReadKind.PLACE_VIEWPORT, ReadKind.PLACE_CONTAINING_BLOCK);

        int relVw = masks.get("relVw");
        assertHas(relVw, ReadKind.PLACE_ORIGIN, ReadKind.PLACE_PARENT_OUTER, ReadKind.PLACE_VIEWPORT);

        int fix = masks.get("fix");
        assertHas(fix, ReadKind.PLACE_VIEWPORT);
        assertLacks(fix, ReadKind.PLACE_ORIGIN);

        int abs = masks.get("abs");
        assertHas(abs, ReadKind.PLACE_CONTAINING_BLOCK, ReadKind.PLACE_VIEWPORT, ReadKind.PLACE_DESCENDANT_SCROLL);

        int tip = masks.get("tip");
        assertHas(tip, ReadKind.PLACE_CONTAINING_BLOCK, ReadKind.PLACE_ANCHOR, ReadKind.PLACE_VIEWPORT);

        int sc = masks.get("sc");
        assertHas(sc, ReadKind.PLACE_SELF_SCROLL, ReadKind.PLACE_STICKY);

        int wrapScroll = masks.get("wrapScroll");
        assertHas(wrapScroll, ReadKind.PLACE_DESCENDANT_SCROLL, ReadKind.PLACE_STICKY);

        int st = masks.get("st");
        assertHas(st, ReadKind.PLACE_STICKY, ReadKind.PLACE_PARENT_OUTER, ReadKind.PLACE_VIEWPORT);

        int flWrap = masks.get("flWrap");
        assertHas(flWrap, ReadKind.PLACE_FLOAT);
    }

    @Test
    void scopeRuleDistinguishesInsideVsOutsideSubtree() {
        StandaloneView v = new StandaloneView(
                "<div id=outer style='position:relative'>"
                        + "<div id=sub style='position:relative'>"
                        + "<div id=inAbs style='position:absolute;left:0;top:0'>i</div>"
                        + "</div>"
                        + "<div id=wrap><div id=outAbs style='position:absolute;left:0;top:0'>o</div></div>"
                        + "</div>",
                400, 300, FontResolver.FALLBACK);
        v.ui().flushInvalidation();

        LayoutBox root = v.ui().layoutRoot();
        LayoutBox outer = root.findByNode(v.root().getNodeById("outer"));
        LayoutBox sub = root.findByNode(v.root().getNodeById("sub"));
        LayoutBox wrap = root.findByNode(v.root().getNodeById("wrap"));
        LayoutBox inAbs = root.findByNode(v.root().getNodeById("inAbs"));
        LayoutBox outAbs = root.findByNode(v.root().getNodeById("outAbs"));

        assertTrue(ReadKind.isInsideSubtree(sub, inAbs.findContainingBlock()));
        assertFalse(ReadKind.isInsideSubtree(wrap, outAbs.findContainingBlock()));
        assertTrue(ReadKind.isInsideSubtree(outer, outAbs.findContainingBlock()));
    }

    @Test
    void parallelPlacementRecordsIndependentlyPerThread() throws Exception {
        StringBuilder sb = new StringBuilder("<div id=list style='width:200px'>");
        for (int i = 0; i < 60; i++) {
            sb.append("<div id='r").append(i).append("' style='height:10px'>row</div>");
        }
        sb.append("</div>");
        StandaloneView v = new StandaloneView(sb.toString(), 400, 300, FontResolver.FALLBACK);
        v.ui().flushInvalidation();

        // 並列レイアウト後もメインスレッドの配置スタックが空で漏れがないこと
        assertEquals(0, MeasureReads.state().placeDepth);

        // 複数スレッドから独立に push/pop しても読み取りビットが混ざらないこと
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int idx = i;
                Callable<Integer> task = () -> {
                    LayoutBox dummy = v.ui().layoutRoot();
                    int slot = LayoutReads.pushPlacing(dummy, 0, 0, 100, 100);
                    try {
                        if (idx % 2 == 0) LayoutReads.recordPlace(ReadKind.PLACE_ANCHOR);
                        else LayoutReads.recordPlace(ReadKind.PLACE_STICKY);
                    } finally {
                        return LayoutReads.popPlacing(slot);
                    }
                };
                futures.add(pool.submit(task));
            }
            for (int i = 0; i < futures.size(); i++) {
                int mask = futures.get(i).get();
                int expected = (i % 2 == 0) ? ReadKind.PLACE_ANCHOR.stageBit() : ReadKind.PLACE_STICKY.stageBit();
                assertEquals(expected, mask);
            }
        } finally {
            pool.shutdown();
        }
    }

    private static void assertHas(int mask, ReadKind... kinds) {
        for (ReadKind k : kinds) {
            assertTrue((mask & k.stageBit()) != 0, "記録されているべき種別が無い: " + k + " mask=" + Integer.toBinaryString(mask));
        }
    }

    private static void assertLacks(int mask, ReadKind... kinds) {
        for (ReadKind k : kinds) {
            assertEquals(0, mask & k.stageBit(), "読んでいない種別が記録された: " + k + " mask=" + Integer.toBinaryString(mask));
        }
    }
}
