package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.TextureRef;
import com.nishiyu.enderframelib.core.paint.model.HostContent;
import com.nishiyu.enderframelib.core.paint.resource.HostTextures;
import com.nishiyu.enderframelib.core.paint.resource.PaintResources;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ホストが描く中身（{@link HostContent}。M3b-4c-2b-3b-2b-2）: 描画器の準備は描くフレームを組みながらホストに聞いて中身を集め、ホストは表示項目の
 * 列を読まずにそれだけを用意する。集めた中身は描く項目のうちホストが欲しいものと 1 対 1 に引け（{@link HostContent#matches}。描くフレームは
 * ビューポートにかかるチャンクだけを組むので、画面の外のチャンクの中身も用意しない）、描かない節の中身は含まず、重ね描きの中身は含む。
 */
public class HostContentTest {
    private static final int W = 200, H = 100;

    /** {@code item:} の画像だけを自分で描くホスト。 */
    private static final HostTextures ITEMS = new HostTextures() {
        @Override public TextureRef texture(DisplayItem item, int deviceW, int deviceH) { return null; }
        @Override public boolean wants(ElementType type, String text, String fontFamily, Map<String, String> events) {
            return type == ElementType.IMAGE && text != null && text.startsWith("item:");
        }
    };

    private static SoftwareRenderBackend backend() {
        return new SoftwareRenderBackend(W, H, 1f, "host-content", new PaintResources().hostTextures(ITEMS));
    }

    private static StandaloneView view(String body) {
        StandaloneView v = new StandaloneView("<html><body style='margin:0'>" + body + "</body></html>", W, H, FontResolver.FALLBACK);
        v.frame(1000L, new SoftwareRenderBackend(W, H, 1f), true);
        return v;
    }

    @Test
    public void contentsAreTheWantedDrawnItemsOneToOne() {
        try (StandaloneView v = view("<img id=a src='item:minecraft:stone' style='width:16px;height:16px'>"
                + "<img id=b src='data:,x' style='width:16px;height:16px'><span>text</span>")) {
            PreparedFrame p = backend().prepare(v.ui().renderFrame(List.of(), null, 1000L));
            List<HostContent> contents = p.hostContents();
            assertEquals(1, contents.size(), "ホストが欲しいと答えた中身だけ: " + contents);
            HostContent c = contents.getFirst();
            assertEquals("item:minecraft:stone", c.text());
            assertEquals(ElementType.IMAGE, c.type());
            long matching = p.drawList().stream().filter(c::matches).count();
            assertEquals(1, matching, "描く項目の 1 つと引ける");
            assertTrue(p.drawList().stream().filter(c::matches).allMatch(it -> it.width() == 16 && it.height() == 16));
        }
    }

    @Test
    public void contentsFollowTheCulledDrawList() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 400; i++) rows.append("<div style='height:20px'><img src='item:minecraft:stone' style='width:16px;height:16px'></div>");
        try (StandaloneView v = view(rows.toString())) {
            PreparedFrame p = backend().prepare(v.ui().renderFrame(List.of(), null, 1000L));
            List<DisplayItem> wanted = p.drawList().stream()
                    .filter(it -> it.type() == ElementType.IMAGE && "item:minecraft:stone".equals(it.text())).toList();
            assertTrue(wanted.size() < 400, "前提: 描くフレームは見えるチャンクだけを組む（" + wanted.size() + "）");
            assertEquals(wanted.size(), p.hostContents().size(), "描く分だけ用意する");
            for (int i = 0; i < wanted.size(); i++) assertTrue(p.hostContents().get(i).matches(wanted.get(i)), "描く順に 1 対 1: " + i);
        }
    }

    @Test
    public void excludedNodesAreLeftOutButOverlaysAreIn() {
        try (StandaloneView v = view("<img src='item:minecraft:stone' data-hide style='width:16px;height:16px'>"
                + "<img src='item:minecraft:dirt' style='width:16px;height:16px'>")) {
            RenderFrame f = v.ui().renderFrame(List.of(), new ExcludedNodes("data-hide"), 1000L);
            List<HostContent> kept = backend().prepare(f).hostContents();
            assertEquals(1, kept.size(), "描かない節の中身は用意しない: " + kept);
            assertEquals("item:minecraft:dirt", kept.getFirst().text());
            f = v.ui().renderFrame(List.of(), null, 1000L);

            DisplayItem overlay = new DisplayItem("ov", "ov", ElementType.IMAGE, 0, 0, 8, 8, "item:minecraft:apple", 0, 0, Map.of(), 0, 1f,
                    0, 0, 1f, 1f, 0, 0, com.nishiyu.enderframelib.core.css.model.AnimationSpec.NONE,
                    com.nishiyu.enderframelib.core.paint.model.ImageFitMode.FILL, null);
            List<HostContent> withOverlay = backend().prepare(f.withOverlays(List.of(overlay))).hostContents();
            assertEquals(3, withOverlay.size(), "重ね描きの中身は含む（シーンの 2 つの後ろ）");
            assertTrue(withOverlay.getLast().matches(overlay));
        }
    }

    @Test
    public void hostThatWantsNothingCollectsNothing() {
        try (StandaloneView v = view("<img src='item:minecraft:stone' style='width:16px;height:16px'>")) {
            PreparedFrame p = new SoftwareRenderBackend(W, H, 1f).prepare(v.ui().renderFrame(List.of(), null, 1000L));
            assertEquals(List.of(), p.hostContents());
        }
    }
}
