package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.FrameOracle;
import com.nishiyu.enderframelib.common.MutationFuzz;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.kernel.Kernel;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * K2 回帰・受け入れテスト: 読み取り集合の鍵（{@link PlacementMemo}・{@link PlacementDeps}）による配置再利用が
 * 実文書・表・絶対配置・差し替えファズで {@link FrameOracle} と一致し、配置回数を抑えることを確かめる
 * （{@code DESIGN_INCREMENTAL_KERNEL.md} §5）。
 */
class K2PlacementKeyTest {

    private static final Path FIXTURES = Path.of("src/main/resources/data/enderframelib/enderframe_ui");

    private static String loadDoc(String name) throws IOException {
        return Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static HtmlText findFirstTextOutsideButton(HtmlNode n) {
        for (HtmlNode c : n.children) {
            if (c instanceof HtmlText tx && !tx.getData().isBlank()) {
                boolean btn = n.tag != null && (n.tag.equals("button") || n.tag.equals("label"));
                if (!btn) return tx;
                continue;
            }
            HtmlText r = findFirstTextOutsideButton(c);
            if (r != null) return r;
        }
        return null;
    }

    private static HtmlNode firstBody(HtmlNode n) {
        if ("body".equals(n.tag)) return n;
        for (HtmlNode c : n.children) {
            HtmlNode r = firstBody(c);
            if (r != null) return r;
        }
        return null;
    }

    @Test
    void realDocsTextReuseMaintainsFrameOracleAndLowPlacementCount() throws IOException {
        List<String> docs = List.of("voidwave.html", "showcase.html", "item_block_test.html", "surface_test.html");
        for (String doc : docs) {
            String html = loadDoc(doc);
            StandaloneView v = new StandaloneView(html, 800, 600, FontResolver.FALLBACK);
            v.ui().flushInvalidation();
            HtmlText target = findFirstTextOutsideButton(firstBody(v.root()));
            long skipped0 = 0;
            for (int i = 0; i < 60; i++) {
                if (i == 10) {
                    skipped0 = LayoutBox.layoutsSkipped();
                }
                target.setData((i & 1) == 0 ? "Row item A with text " + (i & 7) : "Row item B with text " + (i & 7));
                v.ui().flushInvalidation();
                if (i >= 55) {
                    assertNull(FrameOracle.firstDifference(v), doc + " step " + i);
                }
            }
            long keptPerFrame = (LayoutBox.layoutsSkipped() - skipped0) / 50;
            v.close();
            System.out.printf("K2 %-22s kept/f=%d (stats enabled=%s)%n", doc, keptPerFrame, Kernel.stats != null);
            assertTrue(keptPerFrame > 0, doc + " had 0 kept placements per frame");
        }
    }

    @Test
    void keyDrivenPlacementPassesKeepSuitesAndFuzzWithOracle() {
        List<Consumer<StandaloneView>> absSteps = PlacementKeepAbsoluteTest.steps();
        StandaloneView vAbs = new StandaloneView(PlacementKeepAbsoluteTest.HTML, 400, 300, FontResolver.FALLBACK);
        vAbs.ui().flushInvalidation();
        for (int i = 0; i < absSteps.size(); i++) {
            absSteps.get(i).accept(vAbs);
            vAbs.ui().flushInvalidation();
            assertNull(FrameOracle.firstDifference(vAbs), "abs step " + (i + 1));
        }
        vAbs.resize(520, 360);
        vAbs.ui().flushInvalidation();
        assertNull(FrameOracle.firstDifference(vAbs), "abs resize");
        vAbs.close();

        List<Consumer<StandaloneView>> tblSteps = PlacementKeepTableTest.steps();
        StandaloneView vTbl = new StandaloneView(PlacementKeepTableTest.HTML, 400, 300, FontResolver.FALLBACK);
        vTbl.ui().flushInvalidation();
        for (int i = 0; i < tblSteps.size(); i++) {
            tblSteps.get(i).accept(vTbl);
            vTbl.ui().flushInvalidation();
            assertNull(FrameOracle.firstDifference(vTbl), "table step " + (i + 1));
        }
        vTbl.close();

        for (MutationFuzz.Doc doc : MutationFuzz.DOCS.values()) {
            for (long seed = 1; seed <= 300; seed++) {
                String m = MutationFuzz.firstMismatch(doc, seed, doc.steps());
                assertNull(m, "K2 placement mismatch: " + m);
            }
        }
    }
}
