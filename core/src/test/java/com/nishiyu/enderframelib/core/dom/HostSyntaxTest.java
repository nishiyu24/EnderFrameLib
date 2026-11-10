package com.nishiyu.enderframelib.core.dom;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.parser.MarkdownParser;
import com.nishiyu.enderframelib.core.dom.HostElementProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** ホスト構文（属性の別名・Markdown ブロックのタグ）の SPI。コアは具体名を持たない。 */
class HostSyntaxTest {

    private static final Path CORE = Path.of("core/src/main/java/com/nishiyu/enderframelib/core");

    @AfterEach
    void reset() {
        HostSyntax.resetToDefaults();
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
    void dynamicRegistrationAndReset() {
        HostSyntax.registerAttributeAlias("X-When", "data-if");
        assertEquals("data-if", HostSyntax.canonicalOf("x-when"));
        assertEquals("v", HostSyntax.attr(Map.of("x-when", "v"), "data-if"));
        HostSyntax.registerMarkdownTag("x-md");
        assertTrue(HostSyntax.isMarkdownTag("x-md"));
        HostSyntax.resetToDefaults();
        assertEquals("x-when", HostSyntax.canonicalOf("x-when"));
        assertFalse(HostSyntax.isMarkdownTag("x-md"));
        assertThrows(IllegalArgumentException.class, () -> HostSyntax.registerMarkdownTag(""));
        assertThrows(IllegalStateException.class, () -> HostSyntax.registerAttributeAlias("a", "a"));
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
        HostSyntax.registerMarkdownTag("x-md");
        String out2 = MarkdownParser.processDocumentOrBlocks("<div><x-md># Yo</x-md></div>");
        assertTrue(out2.contains("<h1"), out2);
    }

    /** data-if 相当の指令を 1 つ付けた要素へ sync を掛け、hidden になったか。 */
    private static boolean hiddenAfterSync(String attrName, String expr, Map<String, String> values) {
        var root = com.nishiyu.enderframelib.core.dom.parser.HtmlParser.parse(
                "<div><p id=\"t\" " + attrName + "=\"" + expr + "\">x</p></div>");
        new Bindings.ReactiveDomBinder().sync(root, values, Map.of(), Set.of(Bindings.TemplateDeps.ALWAYS));
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
        HostSyntax.registerAttributeAlias("x-when", "data-if");
        assertTrue(hiddenAfterSync("x-when", "off", v), "動的に足した別名も効く");
    }

    @Test
    void aliasDependenciesAreIndexedLikeCanonicalOnes() {
        var root = com.nishiyu.enderframelib.core.dom.parser.HtmlParser.parse("<div><p mc-if=\"ready\">x</p><p mc-for=\"it in rows\">y</p></div>");
        Set<String> deps = new Bindings.TemplateDeps().index(root);
        assertTrue(deps.contains("ready"), deps.toString());
        assertTrue(deps.contains("rows"), deps.toString());
    }

    @Test
    void coreHoldsNoMinecraftSyntaxNames() throws IOException {
        for (String f : new String[] {"dom/Bindings.java", "dom/parser/MarkdownParser.java", "dom/HostSyntax.java"}) {
            String s = Files.readString(CORE.resolve(f));
            for (String t : new String[] {"\"mc-if\"", "\"mc-for\"", "\"mc-markdown\""}) {
                assertFalse(s.contains(t), f + " が " + t + " を直書きしている（HostElementProvider 経由にする）");
            }
        }
    }
}
