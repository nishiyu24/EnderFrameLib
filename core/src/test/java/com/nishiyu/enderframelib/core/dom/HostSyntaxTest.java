package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.HostSyntax;
import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.parser.MarkdownParser;
import com.nishiyu.enderframelib.core.dom.node.HostElementProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.HostServices;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** ホスト構文（属性の別名・Markdown ブロックのタグ）の SPI。コアは具体名を持たない。 */
class HostSyntaxTest {

    private static final Path CORE = Path.of("core/src/main/java/com/nishiyu/enderframelib/core");

    /** 新しい文脈（global とは独立）で {@code body} を流す。構文の追加は文脈ごと（A13。以前は static の登録表と後始末）。 */
    private static void withContext(UnaryOperator<HostServices> host, Runnable body) {
        EngineContext ctx = new EngineContext();
        ctx.updateHost(host);
        EngineContext.runWith(ctx, body);
    }

    @Test
    void providerAliasesAreDiscovered() {
        assertArrayEquals(new String[] {"mc-if"}, HostSyntax.aliasesOf("data-if"));
        assertArrayEquals(new String[] {"mc-for"}, HostSyntax.aliasesOf("data-for"));
        assertEquals(0, HostSyntax.aliasesOf("data-show").length);
        assertEquals("data-if", HostSyntax.canonicalOf("mc-if"));
        assertEquals("data-if", HostSyntax.canonicalOf("data-if"));
        assertEquals("class", HostSyntax.canonicalOf("class"));
        assertTrue(HostSyntax.isMarkdownTag("mc-markdown"));
    }

    @Test
    void attrPrefersCanonicalThenAlias() {
        assertEquals("a", HostSyntax.attr(Map.of("data-if", "a", "mc-if", "b"), "data-if"));
        assertEquals("b", HostSyntax.attr(Map.of("mc-if", "b"), "data-if"));
        assertNull(HostSyntax.attr(Map.of("class", "x"), "data-if"));
    }

    @Test
    void registrationIsScopedToItsContext() {
        withContext(h -> h.withAttributeAlias("X-When", "data-if").withMarkdownTag("x-md"), () -> {
            assertEquals("data-if", HostSyntax.canonicalOf("x-when"));
            assertEquals("v", HostSyntax.attr(Map.of("x-when", "v"), "data-if"));
            assertTrue(HostSyntax.isMarkdownTag("x-md"));
            assertEquals("data-if", HostSyntax.canonicalOf("mc-if"), "SPI の宣言は追加の上にも残る");
        });
        assertEquals("x-when", HostSyntax.canonicalOf("x-when"), "global には効かない");
        assertFalse(HostSyntax.isMarkdownTag("x-md"));
        assertThrows(IllegalArgumentException.class, () -> HostServices.DEFAULTS.withMarkdownTag(""));
        assertThrows(IllegalStateException.class, () -> HostServices.DEFAULTS.withAttributeAlias("a", "a"));
    }

    @Test
    void providerDefaultsAreEmpty() {
        HostElementProvider none = new HostElementProvider() {};
        assertEquals(Map.of(), none.attributeAliases());
        assertEquals(Set.of(), none.markdownTags());
    }

    @Test
    void hostMarkdownTagIsExpandedAndUnregisteredIsNot() {
        String out = MarkdownParser.processDocumentOrBlocks("<div><mc-markdown class=\"k\"># Hi</mc-markdown></div>");
        assertTrue(out.contains("<h1"), out);
        assertTrue(out.contains("<article"), out);
        assertFalse(out.contains("mc-markdown"), out);
        withContext(h -> h.withMarkdownTag("x-md"), () -> {
            String out2 = MarkdownParser.processDocumentOrBlocks("<div><x-md># Yo</x-md></div>");
            assertTrue(out2.contains("<h1"), out2);
        });
    }

    /** data-if 相当の指令を 1 つ付けた要素へ sync を掛け、hidden になったか。 */
    private static boolean hiddenAfterSync(String attrName, String expr, Map<String, String> values) {
        var root = com.nishiyu.enderframelib.core.dom.parser.HtmlParser.parse(
                "<div><p id=\"t\" " + attrName + "=\"" + expr + "\">x</p></div>");
        new ReactiveDomBinder().sync(root, values, Map.of(), Set.of(TemplateDeps.ALWAYS));
        var p = com.nishiyu.enderframelib.core.css.SelectorMatcher.SelectorEngine.querySelectorAll(root, "#t").get(0);
        return p.attrs.containsKey("hidden");
    }

    @Test
    void aliasDirectivesBehaveLikeCanonicalOnes() {
        Map<String, String> v = Map.of("ready", "true", "off", "false");
        assertTrue(hiddenAfterSync("data-if", "off", v), "data-if が偽なら隠れる（前提）");
        assertFalse(hiddenAfterSync("data-if", "ready", v));
        assertTrue(hiddenAfterSync("mc-if", "off", v), "別名も同じに隠す");
        assertFalse(hiddenAfterSync("mc-if", "ready", v));
        assertFalse(hiddenAfterSync("x-when", "off", v), "未登録の名前は指令ではない");
        withContext(h -> h.withAttributeAlias("x-when", "data-if"), () -> assertTrue(hiddenAfterSync("x-when", "off", v), "文脈に足した別名も効く"));
    }

    @Test
    void aliasDependenciesAreIndexedLikeCanonicalOnes() {
        var root = com.nishiyu.enderframelib.core.dom.parser.HtmlParser.parse("<div><p mc-if=\"ready\">x</p><p mc-for=\"it in rows\">y</p></div>");
        Set<String> deps = new TemplateDeps().index(root);
        assertTrue(deps.contains("ready"), deps.toString());
        assertTrue(deps.contains("rows"), deps.toString());
    }

    @Test
    void coreHoldsNoMinecraftSyntaxNames() throws IOException {
        for (String f : new String[] {"dom/Bindings.java", "dom/parser/MarkdownParser.java", "dom/node/HostSyntax.java"}) {
            String s = Files.readString(CORE.resolve(f));
            for (String t : new String[] {"\"mc-if\"", "\"mc-for\"", "\"mc-markdown\""}) {
                assertFalse(s.contains(t), f + " が " + t + " を直書きしている（HostElementProvider 経由にする）");
            }
        }
    }
}
