package com.nishiyu.enderframelib.client.template;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.Ownership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H2: ClientTemplateManager の単体テスト。
 * テンプレートの登録、取得、DOM展開、クライアント所有権モデルの適用を検証。
 */
public class ClientTemplateManagerTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        ClientTemplateManager.clearCache();
    }

    @Test
    @DisplayName("テンプレートの登録と取得、パース結果の保持を検証")
    void testRegisterAndGetTemplate() {
        String html = """
                <html>
                <head>
                    <style>
                        .progress-bar { width: 100px; background-color: #333; }
                    </style>
                </head>
                <body>
                    <div id="machine">
                        <span id="title">Crusher</span>
                        <div id="bar" class="progress-bar"></div>
                    </div>
                </body>
                </html>
                """;

        ParsedTemplate template = ClientTemplateManager.registerTemplate("mymod:crusher", html);
        assertNotNull(template);
        assertEquals("mymod:crusher", template.templateId());
        assertTrue(ClientTemplateManager.hasTemplate("mymod:crusher"));
        assertEquals(1, ClientTemplateManager.cachedCount());

        // getTemplate から同一キャッシュエントリが取得できること
        ParsedTemplate fetched = ClientTemplateManager.getTemplate("mymod:crusher");
        assertSame(template, fetched);

        // CSS が抽出されていること
        assertTrue(template.authorCss().contains(".progress-bar"));
    }

    @Test
    @DisplayName("クローンされた DOM がクライアント所有権（Ownership.CLIENT）を持つことを検証")
    void testCloneDocumentClientOwnership() {
        String html = """
                <div id="container">
                    <button id="btn">Click me</button>
                    <p id="label">Status</p>
                </div>
                """;

        ParsedTemplate template = ClientTemplateManager.registerTemplate("mymod:panel", html);
        HtmlDocument doc = template.cloneDocument();

        assertNotNull(doc);
        assertEquals(Ownership.CLIENT, doc.ownership());
        assertEquals("client", doc.getAttribute("data-owner"));

        HtmlNode container = doc.getElementById("container");
        assertNotNull(container);
        assertEquals(Ownership.CLIENT, container.ownership());

        HtmlNode btn = doc.getElementById("btn");
        assertNotNull(btn);
        assertEquals(Ownership.CLIENT, btn.ownership());
    }

    @Test
    @DisplayName("instantiate により独立した Document/DOM ツリーが展開されることを検証")
    void testInstantiateIndependentDom() {
        String html = "<div id='root'><span id='txt'>Initial</span></div>";
        ParsedTemplate template = ClientTemplateManager.registerTemplate("mymod:test", html);

        Document doc1 = template.instantiate(800, 600);
        Document doc2 = template.instantiate(800, 600);

        assertNotSame(doc1.root, doc2.root);

        HtmlNode txt1 = doc1.root.getElementById("txt");
        HtmlNode txt2 = doc2.root.getElementById("txt");

        assertNotNull(txt1);
        assertNotNull(txt2);
        assertNotSame(txt1, txt2);

        // doc1 のノードを変更しても doc2 には波及しないこと
        txt1.setTextContent("Modified");
        assertEquals("Modified", txt1.getTextContent());
        assertEquals("Initial", txt2.getTextContent());
    }

    @Test
    @DisplayName("存在しないテンプレートの適切なハンドリングを検証")
    void testNotFoundTemplate() {
        ParsedTemplate template = ClientTemplateManager.getTemplate("nonexistent:dummy_template_xyz");
        assertNull(template);
    }

    @Test
    @DisplayName("キャッシュクリアと個別削除の検証")
    void testCacheClearAndRemove() {
        ClientTemplateManager.registerTemplate("mymod:t1", "<div>1</div>");
        ClientTemplateManager.registerTemplate("mymod:t2", "<div>2</div>");
        assertEquals(2, ClientTemplateManager.cachedCount());

        ClientTemplateManager.removeTemplate("mymod:t1");
        assertFalse(ClientTemplateManager.hasTemplate("mymod:t1"));
        assertTrue(ClientTemplateManager.hasTemplate("mymod:t2"));
        assertEquals(1, ClientTemplateManager.cachedCount());

        ClientTemplateManager.clearCache();
        assertEquals(0, ClientTemplateManager.cachedCount());
    }
}
