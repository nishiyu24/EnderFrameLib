package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.dom.HostElementProvider;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser.VoidElements;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** ホスト独自タグの SPI（HostElementProvider）: コアは具体名を持たず、ServiceLoader の宣言だけで決まる。 */
class HostElementProviderTest {

    private static final Path CORE = Path.of("core/src/main/java/com/nishiyu/enderframelib/core");

    @AfterEach
    void reset() {
        ReplacedElements.resetToDefaults();
        VoidElements.resetHost();
    }

    @Test
    void providerDeclarationsAreDiscoveredByServiceLoader() {
        // core のテストは McTestHostElements を META-INF/services で差し込んでいる
        for (String t : List.of("mc-fluid", "mc-entity", "mc-scene")) assertTrue(ReplacedElements.isHost(t), t);
        for (String t : List.of("mc-fluid", "mc-entity", "mc-scene", "mc-slot", "mc-slot-grid", "mc-inventory", "mc-bar")) {
            assertTrue(VoidElements.isVoid(t), t);
        }
        assertTrue(VoidElements.isVoid("MC-SLOT"), "大小文字を区別しない");
        assertFalse(VoidElements.isVoid("div"));
        assertFalse(VoidElements.TAGS.contains("mc-slot"), "標準の空要素表にホスト名を入れない");
    }

    @Test
    void hostVoidElementChangesParsing() {
        assertFalse(VoidElements.isVoid("x-gap"));
        var before = find(HtmlParser.parse("<div><x-gap>after</div>"), "x-gap");
        assertFalse(before.children.isEmpty(), "未登録なら後続の文字は子になる");
        VoidElements.registerHost("x-gap");
        assertTrue(VoidElements.isVoid("x-gap"));
        var gap = find(HtmlParser.parse("<div><x-gap>after</div>"), "x-gap");
        assertNotNull(gap);
        assertTrue(gap.children.isEmpty(), "空要素は子を持たない");
        VoidElements.resetHost();
        assertFalse(VoidElements.isVoid("x-gap"));
    }

    @Test
    void invalidHostVoidRegistrationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> VoidElements.registerHost(""));
        assertThrows(IllegalArgumentException.class, () -> VoidElements.registerHost(null));
    }

    @Test
    void providerContractDefaultsAreEmpty() {
        HostElementProvider none = new HostElementProvider() {};
        assertEquals(Map.of(), none.replacedElements());
        assertEquals(Set.of(), none.voidElements());
    }

    @Test
    void coreHoldsNoMinecraftTagLiteralsInRegistries() throws IOException {
        for (String f : List.of("layout/ReplacedElements.java", "dom/parser/HtmlParser.java", "paint/DisplayListBuilder.java",
                "paint/ControlPainter.java", "layout/BoxMeasurer.java")) {
            String s = Files.readString(CORE.resolve(f));
            for (String t : List.of("\"mc-fluid\"", "\"mc-entity\"", "\"mc-scene\"", "\"mc-slot\"", "\"mc-slot-grid\"",
                    "\"mc-inventory\"", "\"mc-bar\"", "startsWith(\"mc-\")")) {
                assertFalse(s.contains(t), f + " が " + t + " を直書きしている（HostElementProvider 経由にする）");
            }
        }
    }

    @Test
    void elementTypeHasNoGameSpecificConstants() {
        for (var t : com.nishiyu.enderframelib.core.paint.model.ElementType.values()) {
            assertFalse(List.of("ITEM", "ENTITY", "FLUID").contains(t.name()), t.name());
        }
    }

    private static com.nishiyu.enderframelib.core.dom.node.HtmlNode find(com.nishiyu.enderframelib.core.dom.node.HtmlNode n, String tag) {
        if (tag.equals(n.tag)) return n;
        for (var ch : n.children) {
            var r = find(ch, tag);
            if (r != null) return r;
        }
        return null;
    }
}
