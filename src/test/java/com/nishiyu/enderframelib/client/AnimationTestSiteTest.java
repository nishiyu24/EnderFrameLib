package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.CssAnimator;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * animation_test.html のパース・キーフレーム解決・レイアウト・描画および補間アニメーションの総合テスト。
 */
public class AnimationTestSiteTest {

    private static final Path ANIMATION_TEST_PATH = Path.of("src/main/resources/data/enderframelib/enderframe_ui/animation_test.html");

    @Test
    @DisplayName("animation_test.html が存在し、パーサーとCSSキーフレームが正しく読み込まれること")
    void testFileExistsAndKeyframesParsed() throws Exception {
        assertTrue(Files.exists(ANIMATION_TEST_PATH), "animation_test.html がリソースディレクトリに存在すること");
        String html = Files.readString(ANIMATION_TEST_PATH, StandardCharsets.UTF_8);
        assertFalse(html.isBlank());

        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", 854, 480);
        assertNotNull(doc);
        assertNotNull(doc.root);

        // キーフレーム定義がパースされていること
        assertNotNull(doc.sheet);
        assertNotNull(doc.sheet.keyframes);
        assertTrue(doc.sheet.keyframes.containsKey("kfSpin"), "kfSpin がパースされていること");
        assertTrue(doc.sheet.keyframes.containsKey("kfBob"), "kfBob がパースされていること");
        assertTrue(doc.sheet.keyframes.containsKey("kfPulse"), "kfPulse がパースされていること");
        assertTrue(doc.sheet.keyframes.containsKey("kfSwing"), "kfSwing がパースされていること");
        assertTrue(doc.sheet.keyframes.containsKey("kfBeacon"), "kfBeacon がパースされていること");
        assertTrue(doc.sheet.keyframes.containsKey("kfColorCycle"), "kfColorCycle がパースされていること");
        assertTrue(doc.sheet.keyframes.containsKey("kfTextFlash"), "kfTextFlash がパースされていること");
        assertTrue(doc.sheet.keyframes.containsKey("kfSlideLane"), "kfSlideLane がパースされていること");
    }

    @Test
    @DisplayName("animation_test.html のレイアウトおよび DisplayList 生成が正常に完了し、アニメーション定義が保持されること")
    void testLayoutAndDisplayList() throws Exception {
        String html = Files.readString(ANIMATION_TEST_PATH, StandardCharsets.UTF_8);

        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", 854, 480);

        ScrollState scrollState = new ScrollState();
        InteractionState interactionState = new InteractionState();
        LayoutBox rootBox = compiler.layoutDocument(doc, 854, 480, Map.of(), scrollState, FontResolver.FALLBACK, interactionState);
        assertNotNull(rootBox);

        DisplayListBuilder renderer = new DisplayListBuilder(854, 480, doc.sheet.keyframes, scrollState);
        List<DisplayItem> items = renderer.render(rootBox);
        assertNotNull(items);
        assertTrue(items.size() >= 50, "アニメーション要素やカード群が DisplayList として多数生成されていること (size=" + items.size() + ")");

        // アニメーションが適用されている要素を検出
        List<DisplayItem> animatedItems = items.stream()
                .filter(item -> item.animDef() != null && !item.animDef().isEmpty())
                .toList();
        assertFalse(animatedItems.isEmpty(), "アニメーション指定された DisplayItem が存在すること (count=" + animatedItems.size() + ")");

        // 各チャネルのアニメーション要素が含まれていること
        boolean hasTransform = animatedItems.stream().anyMatch(i -> i.animDef().animates(AnimationSpec.CH_TRANSFORM));
        boolean hasOpacity = animatedItems.stream().anyMatch(i -> i.animDef().animates(AnimationSpec.CH_OPACITY));
        boolean hasColor = animatedItems.stream().anyMatch(i -> i.animDef().animates(AnimationSpec.CH_COLOR));
        boolean hasBgColor = animatedItems.stream().anyMatch(i -> i.animDef().animates(AnimationSpec.CH_BG_COLOR));

        assertTrue(hasTransform, "CH_TRANSFORM (rotate/translate/scale) を含むアニメーション要素が存在すること");
        assertTrue(hasOpacity, "CH_OPACITY を含むアニメーション要素が存在すること");
        assertTrue(hasColor, "CH_COLOR を含むアニメーション要素が存在すること");
        assertTrue(hasBgColor, "CH_BG_COLOR を含むアニメーション要素が存在すること");

        // CssAnimator による補間値の計算が例外なく動作すること
        String sessionId = "test_anim_lab";
        ClientWebManager.clearSession(sessionId);
        long now = 100_000L;
        for (DisplayItem el : animatedItems) {
            CssAnimator.AnimatedValues v0 = CssAnimator.getValuesAt(sessionId, el, now);
            assertNotNull(v0);
            CssAnimator.AnimatedValues vMid = CssAnimator.getValuesAt(sessionId, el, now + 500L);
            assertNotNull(vMid);
        }
        ClientWebManager.clearSession(sessionId);
    }

    @Test
    @DisplayName("EnderFrameStandalone によりデスクトップ画像として正常にレンダリングできること")
    void testStandaloneImageRendering() throws Exception {
        String html = Files.readString(ANIMATION_TEST_PATH, StandardCharsets.UTF_8);
        BufferedImage image = EnderFrameStandalone.renderToImage(html, 854, 480);
        assertNotNull(image);
        assertTrue(image.getWidth() == 854);
        assertTrue(image.getHeight() == 480);
    }
}
