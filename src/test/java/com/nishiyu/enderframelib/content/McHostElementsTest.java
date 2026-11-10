package com.nishiyu.enderframelib.content;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.parser.HtmlParser.VoidElements;
import com.nishiyu.enderframelib.core.dom.HostElementProvider;
import com.nishiyu.enderframelib.core.layout.ReplacedElements;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/** Minecraft ホストの独自タグが、コアの SPI（ServiceLoader）経由でエンジンへ届くこと。 */
class McHostElementsTest {

    @Test
    void serviceLoaderFindsTheMinecraftProvider() {
        boolean found = false;
        for (HostElementProvider p : ServiceLoader.load(HostElementProvider.class)) {
            if (p instanceof McHostElements) found = true;
        }
        assertTrue(found, "META-INF/services/...HostElementProvider に McHostElements が登録されていること");
    }

    @Test
    void engineSeesTheMinecraftTags() {
        for (String t : new String[] {"mc-fluid", "mc-entity", "mc-scene"}) assertTrue(ReplacedElements.isReplacedTag(t), t);
        for (String t : new String[] {"mc-slot", "mc-slot-grid", "mc-inventory", "mc-bar"}) assertTrue(VoidElements.isVoid(t), t);
        assertTrue(ReplacedElements.isHostScene("mc-scene"));
        assertTrue(ReplacedElements.isHostImageLike("mc-fluid"));
    }

    @Test
    void srcResolutionKeepsTheLegacyRules() {
        var fluid = ReplacedElements.painterOf("mc-fluid");
        assertEquals("fluid:minecraft:water", fluid.resolveSrc(Map.of("fluid", "minecraft:water"), ""));
        assertEquals("fluid:", fluid.resolveSrc(Map.of(), ""));
        assertEquals("fluid:x", fluid.resolveSrc(Map.of(), "fluid:x"));
        var entity = ReplacedElements.painterOf("mc-entity");
        assertEquals("entity:@self", entity.resolveSrc(Map.of(), ""));
        assertEquals("entity:zombie", entity.resolveSrc(Map.of("entity", "zombie"), ""));
    }
}
