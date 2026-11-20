package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.node.ReplacedKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 置換要素の台帳（Minecraft 固有タグの SPI 化）。 */
class ReplacedElementsTest {
    private static void reg(String tag, com.nishiyu.enderframelib.core.dom.node.CustomElementPainter p) {
        com.nishiyu.enderframelib.core.EngineContext.global().updateHost(h -> h.withReplacedElement(tag, p));
    }

    private static void reg(String tag, com.nishiyu.enderframelib.core.dom.node.ReplacedKind kind) {
        if (kind == null) throw new IllegalArgumentException("tag/kind");
        reg(tag, new com.nishiyu.enderframelib.core.dom.node.CustomElementPainter() {
            @Override public com.nishiyu.enderframelib.core.dom.node.ReplacedKind kind() { return kind; }
        });
    }


    private static final Path CORE = Path.of("core/src/main/java/com/nishiyu/enderframelib/core");

    @AfterEach
    void reset() {
        com.nishiyu.enderframelib.core.EngineContext.global().updateHost(h -> h.withReplacedElements(null));
    }

    @Test
    void defaultsKeepTheExistingBehaviour() {
        for (String t : List.of("img", "canvas", "svg", "input", "select", "button", "progress", "mc-fluid", "mc-entity", "mc-scene")) {
            assertTrue(ReplacedElements.isReplacedTag(t), t);
        }
        assertFalse(ReplacedElements.isReplacedTag("div"));
        assertFalse(ReplacedElements.isReplacedTag("mc-slot"));
        assertTrue(ReplacedElements.isHostImageLike("mc-fluid"));
        assertTrue(ReplacedElements.isHostImageLike("mc-entity"));
        assertFalse(ReplacedElements.isHostImageLike("mc-scene"));
        assertTrue(ReplacedElements.isHostScene("mc-scene"));
        assertFalse(ReplacedElements.isHost("img"), "HTML 標準タグはホスト登録ではない");
    }

    @Test
    void hostCanRegisterItsOwnReplacedElement() {
        assertFalse(ReplacedElements.isReplacedTag("x-gauge"));
        reg("x-gauge", ReplacedKind.IMAGE_LIKE);
        assertTrue(ReplacedElements.isReplacedTag("x-gauge"));
        assertTrue(ReplacedElements.isHostImageLike("x-gauge"));
        com.nishiyu.enderframelib.core.EngineContext.global().updateHost(h -> h.withReplacedElements(null));
        assertFalse(ReplacedElements.isReplacedTag("x-gauge"));
    }

    @Test
    void invalidRegistrationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> reg("", ReplacedKind.SCENE));
        assertThrows(IllegalArgumentException.class, () -> reg(null, ReplacedKind.SCENE));
        assertThrows(IllegalArgumentException.class, () -> reg("x-a", (ReplacedKind) null));
    }

    @Test
    void registeredTagBehavesAsInlineReplacedInLayout() {
        reg("x-gauge", ReplacedKind.IMAGE_LIKE);
        var node = new com.nishiyu.enderframelib.core.dom.node.HtmlNode("x-gauge");
        var style = new com.nishiyu.enderframelib.core.css.ComputedStyle(java.util.Map.of());
        assertEquals(LayoutBox.Display.INLINE, LayoutBox.Display.of(node, style));
        com.nishiyu.enderframelib.core.EngineContext.global().updateHost(h -> h.withReplacedElements(null));
        assertEquals(LayoutBox.Display.BLOCK, LayoutBox.Display.of(node, style));
    }

    @Test
    void layoutAndPaintNoLongerHardcodeHostTags() throws IOException {
        for (String f : List.of("layout/LayoutBox.java", "layout/GridLayoutEngine.java", "layout/FlexLayoutEngine.java",
                "layout/BlockLayoutEngine.java", "layout/BoxMeasurer.java")) {
            String s = Files.readString(CORE.resolve(f));
            for (String t : List.of("\"mc-fluid\"", "\"mc-entity\"")) {
                assertFalse(s.contains(t), f + " が " + t + " を直書きしている（ReplacedElements 経由にする）");
            }
        }
        String dlb = Files.readString(CORE.resolve("paint/DisplayListBuilder.java"));
        assertFalse(dlb.contains("NON_FRAGMENT_TAGS = Set.of(\n            \"#text\", \"img\", \"mc-"), "NON_FRAGMENT_TAGS にホスト固有タグを戻さない");
        assertFalse(dlb.contains("!\"mc-fluid\".equals(tag)"));
    }
}
