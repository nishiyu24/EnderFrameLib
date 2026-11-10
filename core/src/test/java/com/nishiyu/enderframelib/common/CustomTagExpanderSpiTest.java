package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.dom.HostElementProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** CustomTagExpander は具体的なタグを持たず、ホストが登録したマクロだけを展開する。 */
class CustomTagExpanderSpiTest {

    @AfterEach
    void reset() {
        CustomTagExpander.resetToDefaults();
    }

    private static HtmlNode find(HtmlNode n, String tag) {
        if (tag.equals(n.tag)) return n;
        for (HtmlNode c : n.children) {
            HtmlNode r = find(c, tag);
            if (r != null) return r;
        }
        return null;
    }

    private static HtmlNode expandedDoc(String html) {
        HtmlNode root = HtmlParser.parse(html);
        CustomTagExpander.expand(root);
        return root;
    }

    @Test
    void unregisteredTagIsLeftAlone() {
        assertNotNull(find(expandedDoc("<div><x-card></x-card></div>"), "x-card"));
    }

    @Test
    void registeredMacroReplacesTheSubtreeAndIsCaseInsensitive() {
        CustomTagExpander.register("X-Card", src -> {
            HtmlNode d = new HtmlElement("section");
            d.attrs.put("data-from", src.attrs.getOrDefault("name", ""));
            return List.of(d);
        });
        HtmlNode root = expandedDoc("<div><x-card name=\"a\"></x-card></div>");
        assertNull(find(root, "x-card"));
        HtmlNode s = find(root, "section");
        assertNotNull(s);
        assertEquals("a", s.attrs.get("data-from"));
        assertSame(find(root, "div"), s.parent, "親が付け替わること");
    }

    @Test
    void macroReturningNullKeepsTheElementAndExpandsInside() {
        CustomTagExpander.register("x-keep", src -> null);
        CustomTagExpander.register("x-in", src -> List.of(new HtmlElement("b")));
        HtmlNode root = expandedDoc("<x-keep><x-in></x-in></x-keep>");
        assertNotNull(find(root, "x-keep"));
        assertNotNull(find(root, "b"));
    }

    @Test
    void resetDropsDynamicRegistrationsButKeepsProviderOnes() {
        CustomTagExpander.register("x-tmp", src -> List.of(new HtmlElement("i")));
        CustomTagExpander.resetToDefaults();
        assertNotNull(find(expandedDoc("<x-tmp></x-tmp>"), "x-tmp"));
    }

    @Test
    void invalidRegistrationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CustomTagExpander.register("", s -> null));
        assertThrows(IllegalArgumentException.class, () -> CustomTagExpander.register("x-a", null));
    }

    @Test
    void providerDefaultHasNoMacros() {
        assertEquals(Map.of(), new HostElementProvider() {}.tagMacros());
    }

    @Test
    void coreExpanderHoldsNoMinecraftTagNames() throws IOException {
        String s = Files.readString(Path.of("core/src/main/java/com/nishiyu/enderframelib/common/CustomTagExpander.java"));
        assertFalse(s.contains("\"mc-"), "タグ名の直書きを戻さない（HostElementProvider.tagMacros 経由）");
        assertFalse(s.contains("mc-slot\""));
    }
}
