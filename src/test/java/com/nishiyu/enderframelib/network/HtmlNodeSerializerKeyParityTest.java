package com.nishiyu.enderframelib.network;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.DocumentLoader;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** Milestone 1: 明示 id の無い節の鍵（~番号）は、サーバーで作った文書とクライアントで復元した文書で一致する。 */
class HtmlNodeSerializerKeyParityTest {

    private static final String HTML = "<html><body><div id=\"hud\"><span>a</span> <b>b</b></div>"
            + "<ul><li>1</li><li>2</li><li>3</li></ul><p>tail</p></body></html>";

    private static List<HtmlNode> flatten(HtmlNode root) {
        List<HtmlNode> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(HtmlNode n, List<HtmlNode> out) {
        out.add(n);
        for (HtmlNode c : n.children) collect(c, out);
    }

    @Test
    void clientRestoresServerKeysEvenAfterServerSideMutations() {
        Document serverDoc = DocumentLoader.parseDocument(HTML, "", 800, 600);
        HtmlNode server = serverDoc.root;
        assertInstanceOf(HtmlDocument.class, server);

        // 読み込み後にサーバーが木を変える: 静的な節の削除（番号は欠番）と、動的な節の追加
        HtmlNode ul = server.querySelector("ul");
        ul.removeChild(ul.children.get(1));
        HtmlElement added = new HtmlElement("li");
        ul.appendChild(added);
        String addedKey = added.key();

        CompoundTag wire = HtmlNodeSerializer.serialize(server);
        HtmlNode client = HtmlNodeSerializer.deserialize(wire, null);

        List<HtmlNode> s = flatten(server), c = flatten(client);
        assertEquals(s.size(), c.size());
        for (int i = 0; i < s.size(); i++) {
            assertEquals(s.get(i).key(), c.get(i).key(), "pre-order " + i + " (" + s.get(i).tag + ")");
        }
        // ネットワークの変異記録は鍵で節を指す。クライアントはその鍵から同じ節を引ける
        for (int i = 0; i < s.size(); i++) {
            assertSame(c.get(i), client.getNodeById(s.get(i).key()));
        }
        assertSame(c.get(s.indexOf(added)), client.getNodeById(addedKey));
        // 合成 id は属性として運ばれない
        for (HtmlNode n : c) {
            String id = n.attrs.get("id");
            assertTrue(id == null || !(id.startsWith("gen_") || id.startsWith("txt_")), String.valueOf(id));
        }
        assertEquals(server.staticNodeCount(), client.staticNodeCount());
    }
}
