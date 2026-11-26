package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code ::first-line}（および旧 {@code :first-line}）疑似要素のレイアウト・描画検証。 */
public class FirstLinePseudoElementTest {

    private static final int W = 320, H = 200;

    private static List<DisplayItem> displayItems(String html) {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", W, H);
        PaintView root = compiler.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        return com.nishiyu.enderframelib.common.FrameRender.document(doc, W, H, java.util.Map.of(), new ScrollState(), FontResolver.FALLBACK, new com.nishiyu.enderframelib.core.dom.InteractionState()).items();
    }

    private static int[] paint(String html) {
        StandaloneView view = new StandaloneView(html, W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            view.frame(1000, cpu, true);
            return cpu.pixmap().pixels().clone();
        } finally {
            view.close();
        }
    }

    @Test
    @Disabled("G6 ::first-line の text-transform が新経路に無い。計画書 §3.2「新経路の穴（M2c-3 の移植で見えた）」")
    public void firstLineAppliesColorFontWeightTextTransformAndBackgroundOnlyToLine1() {
        String html = "<style>body{margin:0;background:#fff} "
                + "p{margin:0;width:90px;font:16px/22px monospace;color:#000} "
                + "p::first-line{color:#e11;font-weight:bold;background-color:#ff0;text-transform:uppercase}</style>"
                + "<p>alpha beta gamma delta</p>";
        List<DisplayItem> items = displayItems(html);
        List<DisplayItem> texts = items.stream().filter(i -> i.type() == ElementType.TEXT).toList();
        assertTrue(texts.size() >= 3, "2 行以上に折り返す: " + texts.size());

        float firstY = texts.get(0).y();
        boolean sawLine1 = false, sawLine2 = false;
        for (DisplayItem t : texts) {
            boolean isLine1 = Math.abs(t.y() - firstY) < 1f;
            if (isLine1) {
                sawLine1 = true;
                assertEquals(0xFFEE1111, t.color(), "1 行目は赤: " + t.text());
                assertEquals("bold", t.events().get("font-weight"), "1 行目は bold: " + t.text());
                assertEquals(t.text().toUpperCase(), t.text(), "1 行目は uppercase: " + t.text());
            } else {
                sawLine2 = true;
                assertEquals(0xFF000000, t.color(), "2 行目以降は黒: " + t.text());
                assertEquals("normal", t.events().get("font-weight"), "2 行目以降は通常太さ: " + t.text());
                assertEquals(t.text().toLowerCase(), t.text(), "2 行目以降は小文字のまま: " + t.text());
            }
        }
        assertTrue(sawLine1 && sawLine2, "1 行目と 2 行目が揃う");

        List<DisplayItem> flBgs = items.stream()
                .filter(i -> i.type() == ElementType.RECT && i.id().contains("_flbg_"))
                .toList();
        assertEquals(1, flBgs.size(), "::first-line の背景 RECT が 1 つ出る");
        assertEquals(0xFFFFFF00, flBgs.get(0).bgColor(), "::first-line 背景色が黄色");

        int[] px = paint(html);
        boolean sawYellow = false;
        for (int y = 0; y < 22; y++) {
            for (int x = 0; x < 90; x++) {
                if ((px[y * W + x] & 0xFFFFFF) == 0xFFFF00) sawYellow = true;
            }
        }
        assertTrue(sawYellow, "1 行目領域に黄色背景が描画される");
        for (int y = 26; y < 44; y++) {
            for (int x = 0; x < 90; x++) {
                assertNotEquals(0xFFFF00, px[y * W + x] & 0xFFFFFF, "2 行目領域に黄色背景は出ない");
            }
        }
    }

    @Test
    public void firstLineFontSizeAffectsLineWrappingAndResumesNormalSizeOnLine2() {
        String html = "<style>body{margin:0} p{margin:0;width:110px;font:12px/16px monospace;color:#000} "
                + "p::first-line{font-size:28px;color:#00f}</style>"
                + "<p>WWWWWWWWWW tail</p>";
        List<DisplayItem> texts = displayItems(html).stream().filter(i -> i.type() == ElementType.TEXT).toList();
        assertEquals(2, texts.size(), "28px により 2 行に折り返す: " + texts);
        assertTrue(texts.get(0).meta().fontHeight() >= 27.5f, "1 行目は 28px");
        assertTrue(texts.get(1).meta().fontHeight() <= 12.5f, "2 行目は 12px に戻る");
        assertEquals(0xFF0000FF, texts.get(0).color());
        assertEquals(0xFF000000, texts.get(1).color());
    }

    @Test
    public void legacySingleColonFirstLineInheritsIntoInlineSpanUnlessOverridden() {
        String html = "<style>body{margin:0} p{margin:0;width:180px;font:16px/20px monospace;color:#000} "
                + "p:first-line{color:#c00;font-weight:bold} "
                + ".keep{color:#080}</style>"
                + "<p><span>one</span> <span class=keep>two</span> threefourfive sixseven</p>";
        List<DisplayItem> texts = displayItems(html).stream().filter(i -> i.type() == ElementType.TEXT).toList();
        float firstY = texts.get(0).y();
        DisplayItem one = texts.stream().filter(t -> t.text().contains("one")).findFirst().orElseThrow();
        DisplayItem two = texts.stream().filter(t -> t.text().contains("two")).findFirst().orElseThrow();
        DisplayItem tail = texts.stream().filter(t -> Math.abs(t.y() - firstY) > 10f).findFirst().orElseThrow();

        assertEquals(0xFFCC0000, one.color(), "明示上書きのない span は ::first-line の色を受け継ぐ");
        assertEquals("bold", one.events().get("font-weight"));
        assertEquals(0xFF008800, two.color(), "自身が color を持つ span は自前色を保つ");
        assertEquals("bold", two.events().get("font-weight"), "上書きしていない font-weight は ::first-line を受け継ぐ");
        assertEquals(0xFF000000, tail.color(), "2 行目は要素本来の黒");
    }

    @Test
    public void firstLineHoverRestyleUpdatesFirstLineColor() {
        String html = "<style>body{margin:0;background:#fff} p{margin:0;width:160px;font:bold 20px/24px monospace;color:#000} "
                + "p::first-line{color:#c00} p:hover::first-line{color:#00f}</style>"
                + "<p id=t>WWWW</p>";
        StandaloneView view = new StandaloneView(html, W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            view.frame(1000, cpu, true);
            int[] before = cpu.pixmap().pixels().clone();
            boolean sawRed = false;
            for (int c : before) if ((c & 0xFFFFFF) == 0xCC0000) sawRed = true;
            assertTrue(sawRed, "ホバー前の ::first-line は赤");

            view.ui().setHoveredElement(view.root().getNodeById("t").key());
            view.frame(1016, cpu, true);
            int[] after = cpu.pixmap().pixels().clone();
            boolean sawBlue = false;
            for (int c : after) if ((c & 0xFFFFFF) == 0x0000FF) sawBlue = true;
            assertTrue(sawBlue, "ホバー後の :hover::first-line は青に更新される");
        } finally {
            view.close();
        }
    }

    @Test
    public void firstLineWorksInVerticalRlWritingMode() {
        // vertical-rl では 1 行目＝右端の列。右端に ::first-line の赤文字・黄背景、左の 2 列目に通常黒文字が出る
        String html = "<style>body{margin:0;background:#fff} "
                + "p{margin:0;position:absolute;left:40px;top:20px;writing-mode:vertical-rl;height:90px;font:bold 20px/24px monospace;color:#000} "
                + "p::first-line{color:#e11;background-color:#ff0}</style>"
                + "<p>WW WW WW WW</p>";
        int[] px = paint(html);
        int yellowMinX = Integer.MAX_VALUE, redMaxX = -1, blackMinX = Integer.MAX_VALUE;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int rgb = px[y * W + x] & 0xFFFFFF;
                if (rgb == 0xFFFF00) yellowMinX = Math.min(yellowMinX, x);
                else if ((rgb >> 16) > 180 && ((rgb >> 8) & 0xFF) < 80 && (rgb & 0xFF) < 80) redMaxX = Math.max(redMaxX, x);
                else if (((rgb >> 16) & 0xFF) < 40 && ((rgb >> 8) & 0xFF) < 40 && (rgb & 0xFF) < 40) blackMinX = Math.min(blackMinX, x);
            }
        }
        assertTrue(redMaxX > 0 && yellowMinX < Integer.MAX_VALUE, "縦書き 1 列目に赤文字と黄背景がある");
        assertTrue(blackMinX < Integer.MAX_VALUE, "縦書き 2 列目に黒文字がある");
        assertTrue(blackMinX < yellowMinX && blackMinX < redMaxX,
                "vertical-rl の 1 行目（赤/黄）は 2 列目（黒）より右にある: blackMinX=" + blackMinX + ", yellowMinX=" + yellowMinX);
    }
}
