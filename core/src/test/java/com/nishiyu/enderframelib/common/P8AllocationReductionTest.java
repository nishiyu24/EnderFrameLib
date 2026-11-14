package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P8（確保量の削減）の正当性と確保量ラチェット。
 * 制約オブジェクトの再利用（{@link ConstraintSpace#reuseOrCreate}）と、折り返し行（スライス行）の段落単位共有が、
 * 一から組み直した結果と一致し、再レイアウトあたりの確保量が上限を超えないことを固定する。
 */
public class P8AllocationReductionTest {

    @AfterEach
    void restoreGlobals() {
        EngineContext.global().incrementalLayout = true;
        EngineContext.global().inPlaceRestyleRelayout = true;
    }

    @Test
    void reuseOrCreateReturnsSameInstanceOnlyForEqualValues() {
        FormattingContext c = FormattingContext.of(FormattingContext.Display.BLOCK, true, false);
        ConstraintSpace a = ConstraintSpace.reuseOrCreate(null, 100f, Float.NaN, c);
        assertEquals(100f, a.availableInlineSize());
        assertTrue(Float.isNaN(a.percentageBlockSize()));
        // NaN 同士は同じ値として再利用される
        assertSame(a, ConstraintSpace.reuseOrCreate(a, 100f, Float.NaN, c));
        // 幅・高さ基準・文脈のどれかが違えば新しい実体
        assertNotSame(a, ConstraintSpace.reuseOrCreate(a, 101f, Float.NaN, c));
        assertNotSame(a, ConstraintSpace.reuseOrCreate(a, 100f, 5f, c));
        assertNotSame(a, ConstraintSpace.reuseOrCreate(a,
                100f, Float.NaN, FormattingContext.of(FormattingContext.Display.BLOCK, false, false)));
        // 親が寸法を固定した制約は再利用しない（固定値つきは別の意味）
        ConstraintSpace fixed = a.withFixed(50f, 20f);
        ConstraintSpace fresh = ConstraintSpace.reuseOrCreate(fixed, 100f, Float.NaN, c);
        assertNotSame(fixed, fresh);
        assertTrue(Float.isNaN(fresh.fixedInlineSize()) && Float.isNaN(fresh.fixedBlockSize()));
        // 0.0 と -0.0 はビットが違うので別物として扱う（再利用しないだけで結果は変わらない）
        ConstraintSpace z = ConstraintSpace.reuseOrCreate(null, 0f, 0f, c);
        assertNotSame(z, ConstraintSpace.reuseOrCreate(z, -0f, 0f, c));
    }

    private static String html(int rows) {
        StringBuilder sb = new StringBuilder("""
                <style>
                  body { margin: 0; font-size: 13px; }
                  .row { padding: 2px 4px; }
                  .c { text-align: center; }
                  .r { text-align: right; }
                </style>
                <div id="host">
                """);
        for (int i = 0; i < rows; i++) {
            String cls = i % 3 == 0 ? "row c" : i % 3 == 1 ? "row r" : "row";
            sb.append("<p class=\"").append(cls).append("\">item ").append(i)
                    .append(" lorem ipsum dolor sit amet <b>consectetur</b> adipiscing elit sed do eiusmod tempor ")
                    .append("incididunt ut labore et dolore magna aliqua ut enim ad minim veniam</p>\n");
        }
        return sb.append("</div>").toString();
    }

    private static List<String> sigs(StandaloneView v) {
        v.ui().flushInvalidation();
        List<String> out = new ArrayList<>();
        for (DisplayItem item : v.ui().renderCache()) {
            out.add(MutationScenario.sig(item));
        }
        return out;
    }

    private static void runBoth(StandaloneView inc, StandaloneView fresh, Consumer<StandaloneView> mutate) {
        EngineContext.global().incrementalLayout = false;
        mutate.accept(fresh);
        List<String> exp = sigs(fresh);
        EngineContext.global().incrementalLayout = true;
        mutate.accept(inc);
        assertEquals(exp, sigs(inc));
    }

    @Test
    void wrappedParagraphsMatchFreshLayoutAcrossFontSizeAndWidthChanges() {
        String html = html(24);
        StandaloneView inc = new StandaloneView(html, 320, 400, FontResolver.FALLBACK);
        StandaloneView fresh = new StandaloneView(html, 320, 400, FontResolver.FALLBACK);
        EngineContext.global().incrementalLayout = false;
        List<String> exp0 = sigs(fresh);
        EngineContext.global().incrementalLayout = true;
        assertEquals(exp0, sigs(inc));
        assertTrue(exp0.size() > 24, "折り返しで行ごとの表示項目が出ていること");

        String[] sizes = {"16px", "11px", "13px", "20px", "13px"};
        for (String size : sizes) {
            runBoth(inc, fresh, v -> v.root().getNodeById("host").setAttribute("style", "font-size:" + size));
        }
        runBoth(inc, fresh, v -> v.root().getNodeById("host").setAttribute("style", "width:210px"));
        runBoth(inc, fresh, v -> v.root().getNodeById("host").setAttribute("style", "width:480px;font-size:15px"));
    }

    @Test
    void contentSpaceIsReusedWhileTheConstraintIsUnchanged() {
        StandaloneView v = new StandaloneView(html(6), 320, 400, FontResolver.FALLBACK);
        sigs(v);
        LayoutBox host = find(v.ui().layoutRoot(), v.root().getNodeById("host"));
        assertNotNull(host);
        assertNotNull(host.contentSpaceCache, "測定後は内容の制約が記録される");
        ConstraintSpace before = host.contentSpaceCache;
        ConstraintSpace beforeChild = host.childSpaceCache;
        // 見た目だけが変わる再レイアウト（幅は同じ）では、制約オブジェクトが同一のまま
        v.root().getNodeById("host").setAttribute("style", "font-size:15px");
        sigs(v);
        LayoutBox host2 = find(v.ui().layoutRoot(), v.root().getNodeById("host"));
        assertSame(before, host2.contentSpaceCache);
        assertSame(beforeChild, host2.childSpaceCache);
    }

    private static LayoutBox find(LayoutBox box, Object node) {
        if (box.node == node) return box;
        for (int i = 0; i < box.children.size(); i++) {
            LayoutBox r = find(box.children.get(i), node);
            if (r != null) return r;
        }
        return null;
    }

    @Test
    void fontSizeRelayoutAllocationStaysUnderCeiling() {
        int rows = 300;
        StandaloneView v = new StandaloneView(html(rows), 320, 400, FontResolver.FALLBACK);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        String[] sizes = {"12px", "14px"};
        for (int i = 0; i < 300; i++) {
            v.root().getNodeById("host").setAttribute("style", "font-size:" + sizes[i & 1]);
            sigs(v);
        }
        int frames = 200;
        long a0 = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < frames; i++) {
            v.root().getNodeById("host").setAttribute("style", "font-size:" + sizes[i & 1]);
            sigs(v);
        }
        long perFrame = (mx.getThreadAllocatedBytes(tid) - a0) / frames;
        long perRow = perFrame / rows;
        System.out.println("P8 fontsize relayout alloc/frame=" + perFrame + " B (" + perRow + " B/row)");
        assertTrue(perRow < CEILING_BYTES_PER_ROW, "1 行あたり " + perRow + " B（上限 " + CEILING_BYTES_PER_ROW + " B）");
    }

    private static final long CEILING_BYTES_PER_ROW = 16_000;
}
