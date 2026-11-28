package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.layout.fragment.FragmentPipeline;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.paint.SceneDisplayListAdapter;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneEmitter;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 描画は DOM を読まない（M3b-1・決定 F。計画書 §3.4）: 描画が読む DOM の値（属性・入力の値・textarea の中身・状態の擬似クラスの規則が
 * 掛かるか）は箱の木（{@code BoxNode.values}）が写して持つ。スタイルも寸法も変えない属性だけの変化でも最新の箱が変わり、増分で描いた
 * 表示リストが、同じ DOM をゼロから組んで描いた表示リストと項目ごとに一致すること。描画の記憶は台帳に載った節の祖先を数えずに、
 * 変わった枝だけを作り直すこと。
 */
class PaintDomValuesTest {
    private static final int W = 480, H = 360;

    private static final String DOC = "<html><head><style>body{margin:0;font:14px sans-serif} li{padding:2px}</style></head>"
            + "<body><div id=t title=first>tip</div><input id=in value=abc><input id=cb type=checkbox>"
            + "<textarea id=ta>one</textarea><input id=rg type=range min=0 max=10 value=3><div id=d>plain</div>"
            + "<ul id=list>" + rows(120) + "</ul></body></html>";

    private static String rows(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) sb.append("<li id=li").append(i).append(">row ").append(i).append("</li>");
        return sb.toString();
    }

    /** スタイルも寸法も変えない属性・入力の値・textarea の中身の変化が、次のフレームの表示リストに出る。 */
    @Test
    void attributeOnlyChangesReachThePaintedItems() {
        try (StandaloneView sv = new StandaloneView(DOC, W, H, FontResolver.FALLBACK)) {
            SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
            long now = 1000;
            sv.frame(now, live, true);
            assertSameAsFresh("初回", sv);
            String[][] steps = {
                    {"t", "title", "second"}, {"in", "value", "typed text"}, {"cb", "checked", ""}, {"rg", "value", "8"},
                    {"li7", "title", "row tip"}, {"t", "data-tooltip", "tt"}, {"cb", null, "checked"}, {"t", null, "title"},
            };
            for (String[] s : steps) {
                HtmlElement e = (HtmlElement) sv.ui().findNode(s[0]);
                if (s[1] != null) e.setAttribute(s[1], s[2]);
                else e.removeAttribute(s[2]);
                sv.frame(now += 16, live, false);
                assertSameAsFresh(s[0] + " " + (s[1] != null ? s[1] + "=" + s[2] : "-" + s[2]), sv);
            }
            sv.ui().findNode("ta").children.getFirst().updateTextContent("two lines\nof text");
            sv.frame(now += 16, live, false);
            assertSameAsFresh("textarea の中身", sv);
        }
    }

    /** 属性だけの変化で作り直す描画の箱は、変わった箱と祖先だけ（台帳の祖先の照合ではなく、最新の箱の同一性で決まる）。 */
    @Test
    void attributeChangeRepaintsOnlyTheChangedBranch() {
        try (StandaloneView sv = new StandaloneView(DOC, W, H, FontResolver.FALLBACK)) {
            SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
            long now = 1000;
            sv.frame(now, live, true);
            ((HtmlElement) sv.ui().findNode("li60")).setAttribute("title", "tip");
            sv.frame(now + 16, live, false);
            SceneEmitter emitter = sv.ui().v3.emitter();
            assertTrue(emitter.builtLayers() < 10, "作り直した節 " + emitter.builtLayers());
            assertTrue(emitter.reusedLayers() + emitter.reusedChunks() > 5,
                    "使い回した節/チャンク " + emitter.reusedLayers() + " / " + emitter.reusedChunks());
            assertSameAsFresh("li60 title", sv);
        }
    }

    /** ビューの直近の表示リストが、同じ DOM・同じ状態からゼロから組んだ新経路の表示リストと項目ごとに一致すること。 */
    private static void assertSameAsFresh(String what, StandaloneView sv) {
        UiView ui = sv.ui();
        int w = ui.rootW(), h = ui.rootH();
        Document doc = ui.document();
        LayoutSession session = new LayoutSession(FontResolver.FALLBACK);
        FragmentPipeline p = new FragmentPipeline(new StyleSource(doc.sheet, ui.interactionState(), ui.env(), w, h), session);
        LayoutFragment f = p.frame(doc.root, null, w, h);
        DisplayScene scene = new SceneEmitter(session, doc.sheet.keyframes).emit(f, p.boxRoot(), w, h);
        List<DisplayItem> fresh = new SceneDisplayListAdapter(w, h, doc.sheet.keyframes)
                .emit(scene, DynamicSceneState.of(ui.scrollState(), w, h));
        List<DisplayItem> inc = sv.ui().relativeElements();
        for (int i = 0; i < Math.min(inc.size(), fresh.size()); i++) {
            if (!inc.get(i).equals(fresh.get(i)) || inc.get(i).hasHover() != fresh.get(i).hasHover()) {
                throw new AssertionError(what + " #" + i + "\n  ゼロから: " + fresh.get(i) + "\n  増分:     " + inc.get(i));
            }
        }
        assertEquals(fresh.size(), inc.size(), what + ": 項目の数");
    }
}
