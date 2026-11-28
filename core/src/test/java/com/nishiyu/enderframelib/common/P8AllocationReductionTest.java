package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 折り返す段落の多い文書で、文字の大きさ・幅の変更を増分で組んだ結果が最初から組んだ結果と一致し、文書全体の文字の大きさを切り替える
 * 再レイアウトの 1 行あたりの確保量が上限を超えないこと（確保量のラチェット）。
 */
public class P8AllocationReductionTest {

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

    private static List<String> sigs(List<DisplayItem> items) {
        List<String> out = new ArrayList<>();
        for (DisplayItem item : items) out.add(MutationScenario.sig(item));
        return out;
    }

    private static List<String> sigs(StandaloneView v) {
        v.ui().flushInvalidation();
        return sigs(TestItems.of(v.ui()));
    }

    @Test
    void wrappedParagraphsMatchFreshLayoutAcrossFontSizeAndWidthChanges() {
        StandaloneView inc = new StandaloneView(html(24), 320, 400, FontResolver.FALLBACK);
        List<String> first = sigs(inc);
        assertEquals(sigs(V3Fresh.displayList(inc.ui())), first);
        assertTrue(first.size() > 24, "折り返しで行ごとの表示項目が出ていること");
        String[] styles = {"font-size:16px", "font-size:11px", "font-size:13px", "font-size:20px", "font-size:13px", "width:210px",
                "width:480px;font-size:15px"};
        for (String style : styles) {
            inc.root().getNodeById("host").setAttribute("style", style);
            assertEquals(sigs(V3Fresh.displayList(inc.ui())), sigs(inc), style);
        }
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
            v.ui().flushInvalidation();
        }
        int frames = 200;
        long a0 = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < frames; i++) {
            v.root().getNodeById("host").setAttribute("style", "font-size:" + sizes[i & 1]);
            v.ui().flushInvalidation();
        }
        long perFrame = (mx.getThreadAllocatedBytes(tid) - a0) / frames;
        long perRow = perFrame / rows;
        System.out.println("P8 fontsize relayout alloc/frame=" + perFrame + " B (" + perRow + " B/row)");
        assertTrue(perRow < CEILING_BYTES_PER_ROW, "1 行あたり " + perRow + " B（上限 " + CEILING_BYTES_PER_ROW + " B）");
    }

    /**
     * 新経路（M2c ④）の実測 約 41 KB/行（表示項目の署名を作る分を除く。文書の全要素の解き直し 32%・表示リスト 36%〔語ごとの項目〕・
     * 描画用の木 17%・レイアウト 9%）に余裕を足した上限。旧経路（可変の箱の上で段落を共有）は 16 KB/行だった。④-3 で下げる。
     * <p>期限付きの緩和（M3b-4a。計画書 §0）: 全部の層を作り直すこの場面では、層に記憶する描画命令と、それを毎フレーム表示項目に
     * 組み立てる分を二重に持つ（単独 47 KB・全件の中 49 KB/行）。表示項目への組み立てを消す M3b-4b で 48,000 へ戻す。</p>
     */
    private static final long CEILING_BYTES_PER_ROW = 52_000;
}
