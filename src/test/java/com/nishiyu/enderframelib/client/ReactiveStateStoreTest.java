package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.ReactiveStateStore;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    public void testTextPlaceholderMultipleUpdates() {
        String html = "<div id='root'><p id='msg'>Hello {{name}}, count is {{count}}</p></div>";
        ClientWebManager.loadLocalHtml(SESSION_ID, html, 800, 600);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.set("name", "Alice").set("count", 1);
        store.syncToDom();

        HtmlNode msg = ClientWebManager.getRootNode(SESSION_ID).getElementById("msg");
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
        ClientWebManager.loadLocalHtml(SESSION_ID, html, 800, 600);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.set("url", "https://example.com").set("tooltip", "Example Site");
        store.syncToDom();

        HtmlNode link = ClientWebManager.getRootNode(SESSION_ID).getElementById("link");
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
        ClientWebManager.loadLocalHtml(SESSION_ID, html, 800, 600);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.syncToDom();

        // 初回同期で input の value が store に取り込まれる
        assertEquals("initial", store.get("user"));

        // store の変更が DOM へ反映される
        store.set("user", "changedFromStore");
        store.syncToDom();
        HtmlNode input = ClientWebManager.getRootNode(SESSION_ID).getElementById("username");
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
        ClientWebManager.loadLocalHtml(SESSION_ID, html, 800, 600);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.set("ready", false).set("count", 0);
        store.syncToDom();

        HtmlNode ifTarget = ClientWebManager.getRootNode(SESSION_ID).getElementById("if_target");
        HtmlNode showTarget = ClientWebManager.getRootNode(SESSION_ID).getElementById("show_target");

        assertTrue(ifTarget.attrs.containsKey("hidden"), "data-if が false の場合 hidden 属性が付く");
        assertTrue(showTarget.attrs.get("style").contains("display: none"), "data-show が false の場合 display: none になる");

        // 条件を反転
        store.set("ready", true).set("count", 5);
        store.syncToDom();

        assertFalse(ifTarget.attrs.containsKey("hidden"), "data-if が true の場合 hidden 属性が外れる");
        assertFalse(showTarget.attrs.get("style").contains("display: none"), "data-show が true の場合 display: none が解除される");
    }

    @Test
    public void testDataClass() {
        String html = "<div id='root'><div id='box' data-class='active:isActive, alert:errorCount > 0'>Box</div></div>";
        ClientWebManager.loadLocalHtml(SESSION_ID, html, 800, 600);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.set("isActive", true).set("errorCount", 0);
        store.syncToDom();

        HtmlNode box = ClientWebManager.getRootNode(SESSION_ID).getElementById("box");
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
        ClientWebManager.loadLocalHtml(SESSION_ID, html, 800, 600);

        ReactiveStateStore store = new ReactiveStateStore(SESSION_ID);
        store.setList("items", List.of(
                Map.of("id", "k1", "title", "First"),
                Map.of("id", "k2", "title", "Second")
        ));
        store.syncToDom();

        HtmlNode list = ClientWebManager.getRootNode(SESSION_ID).getElementById("user_list");
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
}