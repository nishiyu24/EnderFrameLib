package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 配置の記憶の検証モード（{@code Shortcut.VERIFY_PLACEMENT}。{@link PlacementVerifier}）の検査。
 * ここでは比べる側の画面を作らず、操作を流すだけにする: 配置を保持してはいけない部分木を保持すると、検証モードが {@link AssertionError} を出す
 * （出さなければこのテストは通る）。保持の条件を外した変異で赤になることで、検証モードが保持の誤りを見つけられることを確かめる。
 */
class PlacementVerifierTest {

    @Test
    void verificationIsOnInTests() {
        assertTrue(Shortcut.VERIFY_PLACEMENT.on(), "core/build.gradle の test が enderframe.verify.placement を立てていない");
    }

    @Test
    void absoluteAndRelativeScenarioKeepsOnlyWhatFreshPlacementWouldProduce() {
        long before = PlacementVerifier.verified();
        StandaloneView v = new StandaloneView(PlacementKeepAbsoluteTest.HTML, 400, 300, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        List<Consumer<StandaloneView>> steps = PlacementKeepAbsoluteTest.steps();
        for (Consumer<StandaloneView> s : steps) {
            s.accept(v);
            v.ui().flushInvalidation();
        }
        v.resize(520, 360);
        v.ui().flushInvalidation();
        System.out.println("VERIFIED abs=" + (PlacementVerifier.verified() - before));
        assertTrue(PlacementVerifier.verified() > before, "検証が 1 度も走っていない（保持が起きていない）");
    }

    @Test
    void scrollScenarioKeepsOnlyWhatFreshPlacementWouldProduce() {
        long before = PlacementVerifier.verified();
        StandaloneView v = new StandaloneView(PlacementKeepModuloScrollTest.NESTED, 400, 300, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        text(v, "a", "one x");
        v.ui().flushInvalidation();
        scroll(v, "inner", 25);
        text(v, "c", "three x");
        v.ui().flushInvalidation();
        scroll(v, "sc", 30);
        text(v, "a", "one");
        v.ui().flushInvalidation();
        scroll(v, "inner", 0);
        text(v, "c", "three");
        v.ui().flushInvalidation();
        // スクロールと文字の変更を同じフラッシュに入れる（再配置が先に位置を直さないので、スクロール量の版を見ない保持は古い位置を残す）
        v.ui().scrollState().setScroll(v.root().getNodeById("inner"), 0, 15);
        text(v, "a", "one y");
        v.ui().flushInvalidation();
        v.ui().scrollState().setScroll(v.root().getNodeById("sc"), 0, 40);
        text(v, "c", "three y");
        v.ui().flushInvalidation();
        System.out.println("VERIFIED scroll=" + (PlacementVerifier.verified() - before));
        assertTrue(PlacementVerifier.verified() > before, "検証が 1 度も走っていない（保持が起きていない）");
    }

    private static void scroll(StandaloneView v, String id, float y) {
        v.ui().scrollState().setScroll(v.root().getNodeById(id), 0, y);
        v.ui().requestScrollReposition();
        v.ui().flushInvalidation();
    }

    private static void text(StandaloneView v, String id, String data) {
        for (HtmlNode c : v.root().getNodeById(id).children) if (c instanceof HtmlText t) { t.setData(data); return; }
        throw new IllegalStateException("no text under " + id);
    }
}
