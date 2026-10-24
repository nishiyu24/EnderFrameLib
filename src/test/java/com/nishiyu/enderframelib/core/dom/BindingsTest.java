package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

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
 * core/dom/Bindings の単体テスト。純 Java で実行可能。
 */
public class BindingsTest {

    @BeforeEach
    public void setup() {
        Bindings.i18nResolver = key -> "TR:" + key;
        Bindings.jsonListParser = jsonStr -> {
            if (jsonStr.contains("item1")) {
                return List.of(
                        Map.of("_key", "0", "_value", "item1"),
                        Map.of("_key", "1", "_value", "item2")
                );
            }
            return Collections.emptyList();
        };
    }

    @AfterEach
    public void teardown() {
        Bindings.i18nResolver = key -> key;
        Bindings.i18nFormatter = (key, args) -> key;
        Bindings.jsonListParser = null;
    }

    @Test
    public void testResolvePlaceholders() {
        Map<String, String> data = Map.of("name", "Steve", "score", "100", "i18nKey", "gui.done");
        Map<String, String> env = Map.of("@fps", "144", "@lang", "ja_jp");

        // 通常キー
        assertEquals("Hello Steve (100)", Bindings.resolve("Hello {{name}} ({{score}})", data, env));

        // @環境変数
        assertEquals("FPS: 144 on ja_jp", Bindings.resolve("FPS: {{@fps}} on {{@lang}}", data, env));

        // i18n
        assertEquals("TR:gui.yes", Bindings.resolve("{{i18n:gui.yes}}", data, env));
        assertEquals("TR:gui.no", Bindings.resolve("{{@i18n:gui.no}}", data, env));
        assertEquals("TR:gui.done", Bindings.resolve("{{i18nKey|i18n}}", data, env));
        assertEquals("TR:literal.key", Bindings.resolve("{{'literal.key'|i18n}}", data, env));

        // 引数つき: 状態のキーはその値、引用符つきは中身（カンマも可）
        Bindings.i18nFormatter = (key, args) -> key + java.util.Arrays.toString(args);
        assertEquals("m.hello[Steve, 100, a, b]", Bindings.resolve("{{i18n:m.hello, name, score, 'a, b'}}", data, env));
        assertEquals("TR:m.plain", Bindings.resolve("{{i18n:m.plain}}", data, env));

        // 未知のキーはそのまま残る
        assertEquals("{{unknown}}", Bindings.resolve("{{unknown}}", data, env));
        assertEquals("{{@unknown_env}}", Bindings.resolve("{{@unknown_env}}", data, env));
    }

    @Test
    public void testEvaluateCondition() {
        Map<String, String> data = Map.of(
                "ready", "true",
                "disabled", "false",
                "count", "10",
                "empty", "",
                "status", "active"
        );

        // 存在・真偽値
        assertTrue(Bindings.evaluateCondition("ready", data));
        assertFalse(Bindings.evaluateCondition("disabled", data));
        assertFalse(Bindings.evaluateCondition("empty", data));
        assertNull(Bindings.evaluateCondition("missing", data));

        // 否定
        assertFalse(Bindings.evaluateCondition("!ready", data));
        assertTrue(Bindings.evaluateCondition("!disabled", data));
        assertTrue(Bindings.evaluateCondition("!empty", data));

        // 数値比較
        assertTrue(Bindings.evaluateCondition("count == 10", data));
        assertTrue(Bindings.evaluateCondition("count >= 10", data));
        assertTrue(Bindings.evaluateCondition("count > 5", data));
        assertTrue(Bindings.evaluateCondition("count < 20", data));
        assertFalse(Bindings.evaluateCondition("count != 10", data));
        assertFalse(Bindings.evaluateCondition("count <= 5", data));

        // 文字列比較
        assertTrue(Bindings.evaluateCondition("status == 'active'", data));
        assertTrue(Bindings.evaluateCondition("status != 'inactive'", data));
    }

    @Test
    public void testApplyI18nAttributes() {
        HtmlNode node = new HtmlElement("div");
        node.attrs.put("data-i18n", "gui.title");
        node.attrs.put("data-i18n-placeholder", "gui.search");
        node.attrs.put("data-i18n-title", "gui.tooltip");

        boolean changed = Bindings.applyI18nAttributes(node);
        assertTrue(changed);
        assertEquals("TR:gui.title", node.getTextContent());
        assertEquals("TR:gui.search", node.attrs.get("placeholder"));
        assertEquals("TR:gui.tooltip", node.attrs.get("title"));
    }

    @Test
    public void testScanDeclarativeBindings() {
        String html = "<div id='root'>"
                + "  <div id='hud1' data-hud data-hud-display='block' data-hud-require-nbt='tag1,tag2'></div>"
                + "  <div id='tracker1' data-tracker data-tracker-radius='15.5' data-tracker-y-offset='1.2' data-tracker-type='player'></div>"
                + "  <div id='bind1' data-channel='my_channel' data-bind='user.name'></div>"
                + "  <div id='list1' data-channel='items_ch' data-list='items'>"
                + "    <template><span id='tmpl'>Item</span></template>"
                + "  </div>"
                + "</div>";

        HtmlNode root = HtmlParser.parse(html);
        List<String> events = new ArrayList<>();

        Bindings.scanDeclarativeBindings(root, new Bindings.DeclarativeBindingHandler() {
            @Override
            public void bindData(String targetId, String channel, String path, String targetAttr) {
                events.add("data:" + targetId + ":" + channel + ":" + path + ":" + targetAttr);
            }

            @Override
            public void bindVirtualList(String targetId, String channel, HtmlNode templateNode, int itemW, int itemH) {
                events.add("vlist:" + targetId + ":" + channel + ":" + (templateNode != null));
            }

            @Override
            public void bindHud(String targetId, String origDisplay, HtmlNode templateRoot, List<String> requireNbt) {
                events.add("hud:" + targetId + ":" + origDisplay + ":" + requireNbt);
            }

            @Override
            public void bindTracker(HtmlNode templateNode, double radius, double yOffset, String targetType, List<String> requireNbt) {
                events.add("tracker:" + radius + ":" + yOffset + ":" + targetType);
            }
        });

        assertTrue(events.contains("hud:hud1:block:[tag1, tag2]"), events.toString());
        assertTrue(events.contains("tracker:15.5:1.2:player"), events.toString());
        assertTrue(events.contains("data:bind1:my_channel:user.name:text"), events.toString());
        assertTrue(events.contains("vlist:list1:items_ch:true"), events.toString());
    }

    @Test
    public void testRenderVirtualList() {
        HtmlNode target = new HtmlElement("div");
        target.setId("vlist_target");
        target.attrs.put("data-scroll-y", "0");
        target.attrs.put("data-container-width", "400");
        target.attrs.put("data-visible-rows", "5");

        HtmlNode template = new HtmlElement("template");
        HtmlNode itemTmpl = new HtmlElement("div");
        itemTmpl.attrs.put("id", "row");
        itemTmpl.text = "{{name}} - {{count}}";
        template.children.add(itemTmpl);

        List<Map<String, String>> data = List.of(
                Map.of("name", "Apple", "count", "5"),
                Map.of("name", "Banana", "count", "12")
        );

        boolean rendered = Bindings.renderVirtualList(target, template, data, 100, 20, 400, 5);
        assertTrue(rendered);
        assertEquals("20", target.attrs.get("data-scroll-h")); // 1 row total * 20px
        // topSpacer + 2 items + botSpacer = 4 children
        assertEquals(4, target.children.size());
        assertEquals("Apple - 5", target.children.get(1).text);
        assertEquals("Banana - 12", target.children.get(2).text);
    }

    @Test
    public void testApplyHudTemplateData() {
        HtmlNode root = new HtmlElement("div");
        HtmlNode showNode = new HtmlElement("p");
        showNode.attrs.put("data-show", "visible");
        showNode.attrs.put("style", "display: flex; color: red;");
        showNode.text = "Hello {{target}}";
        root.children.add(showNode);

        HtmlNode listNode = new HtmlElement("div");
        listNode.attrs.put("data-list", "['item1', 'item2']");
        HtmlNode rowTmpl = new HtmlElement("span");
        rowTmpl.text = "Val: {{_value}}";
        listNode.children.add(rowTmpl);
        root.children.add(listNode);

        Map<String, String> data = Map.of("visible", "false", "target", "World");
        Bindings.applyHudTemplateData(root, data, Map.of());

        // data-show が false のため display: none になり、data-show 属性は消去される
        assertTrue(showNode.attrs.get("style").contains("display: none"));
        assertFalse(showNode.attrs.containsKey("data-show"));
        assertEquals("Hello World", showNode.text);

        // data-list の展開
        assertEquals(2, listNode.children.size());
        assertEquals("Val: item1", listNode.children.get(0).text);
        assertEquals("Val: item2", listNode.children.get(1).text);
    }
}
