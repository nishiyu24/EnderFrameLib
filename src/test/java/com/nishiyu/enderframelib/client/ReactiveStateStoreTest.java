package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.ReactiveStateStore;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * R3: ReactiveStateStore の宣言的バインディング載せ替え（実 DOM 直接変更、
 * テンプレート文字列保持、data-for のキーによるノード再利用）を検証するテスト。
 */
public class ReactiveStateStoreTest {

    private static final String SESSION_ID = "test_store_session";

    @BeforeEach
    public void setup() {
        ClientWebManager.clearSession(SESSION_ID);
    }

    @AfterEach
    public void teardown() {
        ClientWebManager.clearSession(SESSION_ID);
    }

    private static SessionContext session() {
        return ClientWebManager.getOrCreateSession(SESSION_ID);
    }

    @Test
    public void testTextPlaceholderMultipleUpdates() {
        String html = "<div id='root'><p id='msg'>Hello {{name}}, count is {{count}}</p></div>";
        session().loadLocalHtml(html, 800, 600);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.set("name", "Alice").set("count", 1);
        store.syncToDom();

        HtmlNode msg = session().rootNode().getElementById("msg");
        assertNotNull(msg);
        assertEquals("Hello Alice, count is 1", msg.getTextContent());

        // 2回目の更新: テンプレート文字列が保持され、正しく再置換されること
        store.set("name", "Bob").set("count", 42);
        store.syncToDom();
        assertEquals("Hello Bob, count is 42", msg.getTextContent());
    }

    @Test
    public void testAttributePlaceholderUpdates() {
        String html = "<div id='root'><a id='link' href='{{url}}' title='{{tooltip}}'>Click</a></div>";
        session().loadLocalHtml(html, 800, 600);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.set("url", "https://example.com").set("tooltip", "Example Site");
        store.syncToDom();

        HtmlNode link = session().rootNode().getElementById("link");
        assertNotNull(link);
        assertEquals("https://example.com", link.attrs.get("href"));
        assertEquals("Example Site", link.attrs.get("title"));

        // 更新
        store.set("url", "https://google.com").set("tooltip", "Google");
        store.syncToDom();
        assertEquals("https://google.com", link.attrs.get("href"));
        assertEquals("Google", link.attrs.get("title"));
    }

    @Test
    public void testDataModelTwoWayBinding() {
        String html = "<div id='root'><input id='username' type='text' data-model='user' value='initial' /></div>";
        session().loadLocalHtml(html, 800, 600);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.syncToDom();

        // 初回同期で input の value が store に取り込まれる
        assertEquals("initial", store.get("user"));

        // store の変更が DOM へ反映される
        store.set("user", "changedFromStore");
        store.syncToDom();
        HtmlNode input = session().rootNode().getElementById("username");
        assertEquals("changedFromStore", input.attrs.get("value"));

        // UI 側の入力変更が store へ反映される
        store.notifyElementModelChanged("username", "userInputText");
        assertEquals("userInputText", store.get("user"));
    }

    @Test
    public void testDataIfAndDataShow() {
        String html = "<div id='root'>"
                + "<div id='if_target' data-if='ready'>Ready</div>"
                + "<div id='show_target' data-show='count > 0' style='display: block;'>Count</div>"
                + "</div>";
        session().loadLocalHtml(html, 800, 600);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.set("ready", false).set("count", 0);
        store.syncToDom();

        HtmlNode ifTarget = session().rootNode().getElementById("if_target");
        HtmlNode showTarget = session().rootNode().getElementById("show_target");

        assertTrue(ifTarget.attrs.containsKey("hidden"), "data-if が false の場合 hidden 属性が付く");
        assertTrue(showTarget.attrs.get("style").contains("display: none"), "data-show が false の場合 display: none になる");

        // 条件を反転
        store.set("ready", true).set("count", 5);
        store.syncToDom();

        assertFalse(ifTarget.attrs.containsKey("hidden"), "data-if が true の場合 hidden 属性が外れる");
        assertFalse(showTarget.attrs.get("style").contains("display: none"), "data-show が true の場合 display: none が解除される");
    }

    /**
     * バインディングで書き換えた style・class・表示が、他の操作（クリックによるフォーカス移動など）無しで
     * 次のレイアウトに反映されること。以前は書き換えた要素の算出スタイルのキャッシュが捨てられず、
     * 古いスタイルのまま描かれていた。
     */
    @Test
    public void testBoundStyleChangesReachLayoutWithoutOtherInvalidation() {
        com.nishiyu.enderframelib.common.RenderPipeline.ILayoutBoxRenderer prev = com.nishiyu.enderframelib.common.UiDocumentCompiler.clientBoxRenderer;
        com.nishiyu.enderframelib.common.UiDocumentCompiler.clientBoxRenderer = (rootBox, w, h, kf, ss) ->
                new com.nishiyu.enderframelib.client.renderer.DisplayListBuilder(w, h, kf, ss).render(rootBox);
        try {
            String html = "<style>#mover{position:absolute;width:10px;height:10px;background:red}"
                    + "#overlay{display:none;position:absolute;width:20px;height:20px;background:blue}"
                    + "#mover.wide{width:40px}</style>"
                    + "<div id='mover' data-bind='style.left:x' data-class='wide:isWide'></div>"
                    + "<div id='overlay' data-show='shown' data-orig-display='block'></div>";
            session().loadLocalHtml(html, 200, 200);
            ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
            store.set("x", "5px").set("isWide", false).set("shown", false);
            store.syncToDom();
            assertEquals(5, itemById("mover").x(), "初回のバインディングが反映される");
            assertNull(itemById("overlay"));

            store.set("x", "50px").set("isWide", true).set("shown", true);
            store.syncToDom();
            assertEquals(50, itemById("mover").x(), "style.left の変更が反映される");
            assertEquals(40, itemById("mover").width(), "data-class の変更が反映される");
            assertNotNull(itemById("overlay"), "スタイルシートで display:none の要素を data-show で表示できる");
        } finally {
            com.nishiyu.enderframelib.common.UiDocumentCompiler.clientBoxRenderer = prev;
        }
    }

    private static com.nishiyu.enderframelib.core.paint.model.DisplayItem itemById(String id) {
        return session().elements().stream().filter(i -> id.equals(i.id())).findFirst().orElse(null);
    }

    @Test
    public void testDataShowRestoresOriginalDisplayInsteadOfForcingFlex() {
        // id の無い要素が複数あっても、それぞれ元の display を保つこと
        String html = "<div id='root'>"
                + "<div class='page' data-show='a'><h2>A</h2><p>a</p></div>"
                + "<div class='page' data-show='b' style='display: grid; color: red;'><h2>B</h2></div>"
                + "<div class='page' data-show='c' data-orig-display='inline-block'>C</div>"
                + "</div>";
        session().loadLocalHtml(html, 800, 600);
        HtmlNode root = session().rootNode().getElementById("root");
        HtmlNode a = root.children.get(0);
        HtmlNode b = root.children.get(1);
        HtmlNode c = root.children.get(2);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.set("a", false).set("b", false).set("c", false);
        store.syncToDom();
        assertEquals("none", a.getStyleProperty("display"));
        assertEquals("none", b.getStyleProperty("display"));
        assertEquals("none", c.getStyleProperty("display"));

        store.set("a", true).set("b", true).set("c", true);
        store.syncToDom();
        assertNull(a.getStyleProperty("display"),
                "インライン display が無い要素は display を外してスタイルシート（div なら block）に委ねる。flex を付けない");
        assertEquals("grid", b.getStyleProperty("display"), "元のインライン display を復元する");
        assertEquals("red", b.getStyleProperty("color"), "他のインライン宣言は残す");
        assertEquals("inline-block", c.getStyleProperty("display"), "data-orig-display の指定を優先する");

        store.set("a", false).set("b", false);
        store.syncToDom();
        store.set("a", true).set("b", true);
        store.syncToDom();
        assertNull(a.getStyleProperty("display"), "切り替えを繰り返しても元に戻る");
        assertEquals("grid", b.getStyleProperty("display"));
    }

    @Test
    public void testDataClass() {
        String html = "<div id='root'><div id='box' data-class='active:isActive, alert:errorCount > 0'>Box</div></div>";
        session().loadLocalHtml(html, 800, 600);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.set("isActive", true).set("errorCount", 0);
        store.syncToDom();

        HtmlNode box = session().rootNode().getElementById("box");
        assertTrue(box.hasClass("active"));
        assertFalse(box.hasClass("alert"));

        store.set("isActive", false).set("errorCount", 2);
        store.syncToDom();

        assertFalse(box.hasClass("active"));
        assertTrue(box.hasClass("alert"));
    }

    @Test
    public void testDataForReusesNodesByKey() {
        String html = "<div id='root'>"
                + "<ul id='user_list' data-for='item in items'>"
                + "<li id='row' data-key='{{item.id}}'>{{item.title}}</li>"
                + "</ul>"
                + "</div>";
        session().loadLocalHtml(html, 800, 600);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.setList("items", List.of(
                Map.of("id", "k1", "title", "First"),
                Map.of("id", "k2", "title", "Second")
        ));
        store.syncToDom();

        HtmlNode list = session().rootNode().getElementById("user_list");
        assertNotNull(list);
        assertEquals(2, list.children.size());

        HtmlNode firstNode = list.children.get(0);
        HtmlNode secondNode = list.children.get(1);
        assertEquals("First", firstNode.getTextContent());
        assertEquals("Second", secondNode.getTextContent());

        // 順序入れ替え + 更新 + 新規追加:
        // k2 (先頭へ移動・更新), k1 (2番目へ移動・更新), k3 (新規追加)
        store.setList("items", List.of(
                Map.of("id", "k2", "title", "Second Updated"),
                Map.of("id", "k1", "title", "First Updated"),
                Map.of("id", "k3", "title", "Third New")
        ));
        store.syncToDom();

        assertEquals(3, list.children.size());

        // 鍵による同一ノード再利用の検証 (R3要件)
        assertSame(secondNode, list.children.get(0), "k2 のノードインスタンスが再利用されること");
        assertSame(firstNode, list.children.get(1), "k1 のノードインスタンスが再利用されること");

        assertEquals("Second Updated", list.children.get(0).getTextContent());
        assertEquals("First Updated", list.children.get(1).getTextContent());
        assertEquals("Third New", list.children.get(2).getTextContent());
    }

    // ------------------------------------------------------------------
    // R0+: 宣言的バインディング・入力同期が MutationObserver を通ること（監査 2026-10-25）
    // DOM API（setAttribute）を通すので観測される（Phase 1 / T8）。
    // ------------------------------------------------------------------

    private static java.util.List<com.nishiyu.enderframelib.api.dom.MutationObserver.Record> observeAttributes(
            com.nishiyu.enderframelib.api.dom.MutationObserver observer, HtmlNode target) {
        observer.observe(target, com.nishiyu.enderframelib.api.dom.MutationObserver.Init.builder()
                .attributes(true).attributeOldValue(true).subtree(true).build());
        return new java.util.ArrayList<>();
    }

    private static boolean hasAttributeRecord(java.util.List<com.nishiyu.enderframelib.api.dom.MutationObserver.Record> records,
                                              String name) {
        return records.stream().anyMatch(r -> "attributes".equals(r.getType()) && name.equals(r.getAttributeName()));
    }

    @Test
    public void testApplyI18nAttributesFiresMutationObserver() {
        java.util.function.Function<String, String> saved = com.nishiyu.enderframelib.core.dom.Bindings.i18nResolver;
        com.nishiyu.enderframelib.core.dom.Bindings.i18nResolver = key -> "tr:" + key;
        try {
            HtmlNode input = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("input");
            input.setAttribute("data-i18n-placeholder", "ui.name");
            java.util.List<com.nishiyu.enderframelib.api.dom.MutationObserver.Record> received = new java.util.ArrayList<>();
            var observer = com.nishiyu.enderframelib.api.dom.MutationObserver.create((records, obs) -> received.addAll(records));
            observeAttributes(observer, input);

            assertTrue(com.nishiyu.enderframelib.core.dom.Bindings.applyI18nAttributes(input));
            received.addAll(observer.takeRecords());

            assertEquals("tr:ui.name", input.getAttribute("placeholder"));
            assertTrue(hasAttributeRecord(received, "placeholder"), "placeholder の変更が MutationObserver に届くこと");
        } finally {
            com.nishiyu.enderframelib.core.dom.Bindings.i18nResolver = saved;
        }
    }

    @Test
    public void testApplyLocalVariablesFiresMutationObserver() {
        HtmlNode template = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        template.setAttribute("title", "Level {{@level}}");
        HtmlNode live = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        live.setAttribute("title", "Level ?");
        java.util.List<com.nishiyu.enderframelib.api.dom.MutationObserver.Record> received = new java.util.ArrayList<>();
        var observer = com.nishiyu.enderframelib.api.dom.MutationObserver.create((records, obs) -> received.addAll(records));
        observeAttributes(observer, live);

        assertTrue(com.nishiyu.enderframelib.core.dom.Bindings.applyLocalVariables(template, live, Map.of("@level", "7")));
        received.addAll(observer.takeRecords());

        assertEquals("Level 7", live.getAttribute("title"));
        assertTrue(hasAttributeRecord(received, "title"), "局所変数展開による属性変更が MutationObserver に届くこと");
    }

    @Test
    public void testInputValueSyncFiresMutationObserver() {
        session().loadLocalHtml("<div><input id='name' type='text' value='a'></div>", 800, 600);
        HtmlNode input = session().rootNode().getElementById("name");
        java.util.List<com.nishiyu.enderframelib.api.dom.MutationObserver.Record> received = new java.util.ArrayList<>();
        var observer = com.nishiyu.enderframelib.api.dom.MutationObserver.create((records, obs) -> received.addAll(records));
        observeAttributes(observer, input);

        new com.nishiyu.enderframelib.client.gui.EnderScreenDelegate(SESSION_ID, session().stateStore())
                .syncInputValueToDomNodes("name", "typed");
        received.addAll(observer.takeRecords());

        assertEquals("typed", input.getAttribute("value"));
        assertTrue(hasAttributeRecord(received, "value"), "入力値の DOM 同期が MutationObserver に届くこと");
    }
}
