package com.nishiyu.enderframelib.core.event;

import com.nishiyu.enderframelib.core.event.CoreEvent;
import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

public class EventDispatcherTest {

    @Test
    public void testEventBubblingFiresContainerOnClick() {
        // コンテナの onclick が子のクリックで発火すること（F5 の完了条件）
        HtmlNode root = HtmlParser.parse("<div id=\"container\" onclick=\"container_clicked\"><button id=\"btn\">Click Me</button></div>");
        HtmlNode container = root.children.get(0);
        HtmlNode btn = container.children.get(0);

        List<String> actions = new ArrayList<>();
        UIEvents.Mouse clickEvent = CoreEvent.click(10, 10, 0);

        EventDispatcher.dispatchEvent(btn, clickEvent, (node, action) -> {
            actions.add(node.getId() + ":" + action);
        }, null);

        // ボタン自身には onclick は無いが、バブリングによって親コンテナの onclick が呼ばれる
        assertEquals(List.of("container:container_clicked"), actions);
    }

    @Test
    public void testStopPropagationPreventsBubbling() {
        HtmlNode root = HtmlParser.parse("<div id=\"parent\" onclick=\"parent_act\"><button id=\"child\" onclick=\"child_act\">Btn</button></div>");
        HtmlNode parent = root.children.get(0);
        HtmlNode child = parent.children.get(0);

        List<String> actions = new ArrayList<>();
        UIEvents.Mouse clickEvent = CoreEvent.click(10, 10, 0);

        EventDispatcher.dispatchEvent(child, clickEvent, (node, action) -> {
            actions.add(node.getId() + ":" + action);
            if ("child".equals(node.getId())) {
                clickEvent.stopPropagation();
            }
        }, null);

        // child のハンドラ内で stopPropagation されたため、parent のハンドラは呼ばれない
        assertEquals(List.of("child:child_act"), actions);
    }

    @Test
    public void testSummaryTogglesDetailsOpenDefaultAction() {
        HtmlNode root = HtmlParser.parse("<details id=\"d\"><summary id=\"s\">Title</summary><p>Content</p></details>");
        HtmlNode details = root.children.get(0);
        HtmlNode summary = details.children.get(0);

        assertFalse(details.attrs.containsKey("open"), "初期状態は閉じている");

        UIEvents.Mouse click1 = CoreEvent.click(5, 5, 0);
        EventDispatcher.dispatchEvent(summary, click1, null, ev -> StandardDefaultActions.execute(ev, null));
        assertTrue(details.attrs.containsKey("open"), "1 回目のクリックで開く");

        UIEvents.Mouse click2 = CoreEvent.click(5, 5, 0);
        EventDispatcher.dispatchEvent(summary, click2, null, ev -> StandardDefaultActions.execute(ev, null));
        assertFalse(details.attrs.containsKey("open"), "2 回目のクリックで閉じる");
    }

    @Test
    public void testPreventDefaultCancelsDefaultAction() {
        HtmlNode root = HtmlParser.parse("<details id=\"d\"><summary id=\"s\" onclick=\"cancel_me\">Title</summary></details>");
        HtmlNode details = root.children.get(0);
        HtmlNode summary = details.children.get(0);

        UIEvents.Mouse click = CoreEvent.click(5, 5, 0);
        EventDispatcher.dispatchEvent(summary, click, (node, act) -> {
            click.preventDefault();
        }, ev -> StandardDefaultActions.execute(ev, null));

        assertTrue(click.isDefaultPrevented());
        assertFalse(details.attrs.containsKey("open"), "preventDefault されたので開かない");
    }

    @Test
    public void testCheckboxToggleDefaultAction() {
        HtmlNode root = HtmlParser.parse("<div><input type=\"checkbox\" id=\"cb\"></div>");
        HtmlNode cb = root.children.get(0).children.get(0);

        assertFalse(cb.attrs.containsKey("checked"));

        UIEvents.Mouse click1 = CoreEvent.click(5, 5, 0);
        EventDispatcher.dispatchEvent(cb, click1, null, ev -> StandardDefaultActions.execute(ev, null));
        assertTrue(cb.attrs.containsKey("checked"));

        UIEvents.Mouse click2 = CoreEvent.click(5, 5, 0);
        EventDispatcher.dispatchEvent(cb, click2, null, ev -> StandardDefaultActions.execute(ev, null));
        assertFalse(cb.attrs.containsKey("checked"));
    }

    @Test
    public void testRadioGroupExclusivity() {
        HtmlNode root = HtmlParser.parse("<div>" +
                "<input type=\"radio\" name=\"r\" id=\"r1\" checked=\"true\">" +
                "<input type=\"radio\" name=\"r\" id=\"r2\">" +
                "</div>");
        HtmlNode parent = root.children.get(0);
        HtmlNode r1 = parent.children.get(0);
        HtmlNode r2 = parent.children.get(1);

        assertTrue(r1.attrs.containsKey("checked"));
        assertFalse(r2.attrs.containsKey("checked"));

        UIEvents.Mouse clickR2 = CoreEvent.click(5, 5, 0);
        EventDispatcher.dispatchEvent(r2, clickR2, null, ev -> StandardDefaultActions.execute(ev, null));

        assertFalse(r1.attrs.containsKey("checked"), "r1 は解除される");
        assertTrue(r2.attrs.containsKey("checked"), "r2 がチェックされる");
    }

    @Test
    public void testLabelForDispatchesClickToTarget() {
        HtmlNode root = HtmlParser.parse("<div><label for=\"chk\">Check me</label><input type=\"checkbox\" id=\"chk\"></div>");
        HtmlNode label = root.children.get(0).children.get(0);
        HtmlNode chk = root.children.get(0).children.get(1);

        AtomicReference<HtmlNode> forwardedNode = new AtomicReference<>();
        StandardDefaultActions.Context ctx = new StandardDefaultActions.Context() {
            @Override public void onAttributeChanged(HtmlNode node, String attrName, String attrValue) {}
            @Override public void dispatchClickTo(HtmlNode target) { forwardedNode.set(target); }
            @Override public void navigateTo(String href) {}
            @Override public void executeFormAction(HtmlNode form, String eventType) {}
        };

        UIEvents.Mouse click = CoreEvent.click(5, 5, 0);
        EventDispatcher.dispatchEvent(label, click, null, ev -> StandardDefaultActions.execute(ev, ctx));

        assertSame(chk, forwardedNode.get(), "label のクリックが対象の input へ転送される");
    }

    @Test
    public void testSubmitButtonTriggersFormOnSubmit() {
        HtmlNode root = HtmlParser.parse("<form id=\"myform\" onsubmit=\"submit_action\"><button type=\"submit\" id=\"btn\">Submit</button></form>");
        HtmlNode form = root.children.get(0);
        HtmlNode btn = form.children.get(0);

        AtomicBoolean formSubmitted = new AtomicBoolean(false);
        StandardDefaultActions.Context ctx = new StandardDefaultActions.Context() {
            @Override public void onAttributeChanged(HtmlNode node, String attrName, String attrValue) {}
            @Override public void dispatchClickTo(HtmlNode target) {}
            @Override public void navigateTo(String href) {}
            @Override public void executeFormAction(HtmlNode f, String eventType) {
                if (f == form && "onsubmit".equals(eventType)) {
                    formSubmitted.set(true);
                }
            }
        };

        UIEvents.Mouse click = CoreEvent.click(5, 5, 0);
        EventDispatcher.dispatchEvent(btn, click, null, ev -> StandardDefaultActions.execute(ev, ctx));

        assertTrue(formSubmitted.get(), "フォームの onsubmit が実行される");
    }
}
