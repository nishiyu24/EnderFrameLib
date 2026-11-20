package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.dom.node.HostElementProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.HostServices;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** CustomTagExpander は具体的なタグを持たず、ホストが登録したマクロだけを展開する。 */
class CustomTagExpanderSpiTest {

    /** 新しい文脈（global とは独立）で {@code body} を流す。タグマクロは文脈ごと（A13。以前は static の登録表と後始末）。 */
    private static void withContext(UnaryOperator<HostServices> host, Runnable body) {
        EngineContext ctx = new EngineContext();
        ctx.updateHost(host);
        EngineContext.runWith(ctx, body);
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
        withContext(h -> h.withTagMacro("X-Card", src -> {
            HtmlNode d = new HtmlElement("section");
            d.attrs.put("data-from", src.attrs.getOrDefault("name", ""));
            return List.of(d);
        }), () -> {
            HtmlNode root = expandedDoc("<div><x-card name=\"a\"></x-card></div>");
            assertNull(find(root, "x-card"));
            HtmlNode s = find(root, "section");
            assertNotNull(s);
            assertEquals("a", s.attrs.get("data-from"));
            assertSame(find(root, "div"), s.parent, "親が付け替わること");
        });
    }

    @Test
    void macroReturningNullKeepsTheElementAndExpandsInside() {
        withContext(h -> h.withTagMacro("x-keep", src -> null).withTagMacro("x-in", src -> List.of(new HtmlElement("b"))), () -> {
            HtmlNode root = expandedDoc("<x-keep><x-in></x-in></x-keep>");
            assertNotNull(find(root, "x-keep"));
            assertNotNull(find(root, "b"));
        });
    }

    /** 登録は文脈ごと: 別の文脈（global・別の面）には効かず、後始末も要らない。 */
    @Test
    void registrationsAreScopedToTheirContext() {
        withContext(h -> h.withTagMacro("x-tmp", src -> List.of(new HtmlElement("i"))), () -> {
            assertNotNull(find(expandedDoc("<x-tmp></x-tmp>"), "i"), "登録した文脈では展開される");
            withContext(h2 -> h2, () -> assertNotNull(find(expandedDoc("<x-tmp></x-tmp>"), "x-tmp"), "別の文脈には効かない"));
        });
        assertNotNull(find(expandedDoc("<x-tmp></x-tmp>"), "x-tmp"), "global にも効かない");
    }

    @Test
    void invalidRegistrationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> HostServices.DEFAULTS.withTagMacro("", s -> null));
        assertThrows(IllegalArgumentException.class, () -> HostServices.DEFAULTS.withTagMacro("x-a", null));
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
