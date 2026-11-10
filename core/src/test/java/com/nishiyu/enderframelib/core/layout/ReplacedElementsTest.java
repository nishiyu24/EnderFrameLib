package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.layout.ReplacedElements.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 置換要素の台帳（Minecraft 固有タグの SPI 化）。 */
class ReplacedElementsTest {

    private static final Path CORE = Path.of("core/src/main/java/com/nishiyu/enderframelib/core");

    @AfterEach
    void reset() {
        ReplacedElements.resetToDefaults();
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
        ReplacedElements.register("x-gauge", Kind.IMAGE_LIKE);
        assertTrue(ReplacedElements.isReplacedTag("x-gauge"));
        assertTrue(ReplacedElements.isHostImageLike("x-gauge"));
        ReplacedElements.resetToDefaults();
        assertFalse(ReplacedElements.isReplacedTag("x-gauge"));
    }

    @Test
    void invalidRegistrationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ReplacedElements.register("", Kind.SCENE));
        assertThrows(IllegalArgumentException.class, () -> ReplacedElements.register(null, Kind.SCENE));
        assertThrows(IllegalArgumentException.class, () -> ReplacedElements.register("x-a", (Kind) null));
    }

    @Test
    void registeredTagBehavesAsInlineReplacedInLayout() {
        ReplacedElements.register("x-gauge", Kind.IMAGE_LIKE);
        var node = new com.nishiyu.enderframelib.core.dom.node.HtmlNode("x-gauge");
        var style = new com.nishiyu.enderframelib.core.css.ComputedStyle(java.util.Map.of());
        assertEquals(LayoutBox.Display.INLINE, LayoutBox.Display.of(node, style));
        ReplacedElements.resetToDefaults();
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
