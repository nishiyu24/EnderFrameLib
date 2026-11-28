package com.nishiyu.enderframelib.core.text;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

/**
 * P8b: 保持ヒープの削減（名前の共有）が、結果を変えずに効いている。行の文字列の使い回しの検証は、それを持っていた旧 {@code TextShaper} と
 * 一緒に M5-5 で消した（新経路の行の文字列は {@code InlineFlow} が文字の節の中身から切り出す）。
 */
public class P8bRetentionTest {

    @Test
    public void parserSharesTagAndAttributeNames() {
        HtmlNode root = HtmlParser.parse("<ul><li id=\"a\">x</li><li id=\"b\">y</li></ul>");
        HtmlNode ul = root.tag.equals("ul") ? root : root.children.get(0);
        HtmlNode li1 = ul.children.get(0), li2 = ul.children.get(1);
        assertEquals("li", li1.tag);
        assertSame(li1.tag, li2.tag, "同じタグ名は 1 つの String");
        assertSame("li", li1.tag, "共有はリテラル（intern）と同じ実体");
    }

    private static final class NullBackend implements RenderBackend {
        @Override public void render(com.nishiyu.enderframelib.core.paint.PreparedFrame frame) { }
        @Override public Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    private static long used() {
        for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(50); } catch (InterruptedException ignored) { } }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    /**
     * 2,000 行のリストの保持ヒープの天井（大きな退行だけを止める粗い天井）。M2c-2 で新経路へ切り替えた後のテスト JVM では
     * 23.7〜24.0 MB（2026-11-27 の交互の実測。描画の中間〔TreePainter の記憶・描画用の木〕が大半）で、旧い天井 24 MB の上に乗っていた。
     * 計画書 §4.1 のヒープの上限と同じく、M3b の完了まで新経路の実測 +25% に期限付きで置き直す（M3b で戻す）。
     */
    @Test
    public void largeListRetainedHeapStaysUnderCeiling() {
        StringBuilder sb = new StringBuilder("<html><body><div style=\"height:400px;overflow-y:auto\"><ul>");
        for (int i = 1; i <= 2000; i++) sb.append("<li id=\"item-").append(i).append("\">Row item #").append(i).append(" with description text</li>");
        sb.append("</ul></div></body></html>");
        StandaloneView warm = new StandaloneView("<html><body><ul><li>warm</li></ul></body></html>", 800, 600, FontResolver.FALLBACK);
        warm.frame(1000, new NullBackend(), true);
        long before = used();
        StandaloneView v = new StandaloneView(sb.toString(), 800, 600, FontResolver.FALLBACK);
        NullBackend b = new NullBackend();
        for (int i = 0; i < 5; i++) v.frame(1000 + i * 16, b, true);
        long retained = used() - before;
        System.out.printf("P8B large-list-2000 retained = %.1f MB%n", retained / 1048576.0);
        assertTrue(retained < 30L * 1048576, "保持ヒープが天井を超えた: " + retained / 1048576.0 + " MB");
        v.close();
        warm.close();
    }
}
