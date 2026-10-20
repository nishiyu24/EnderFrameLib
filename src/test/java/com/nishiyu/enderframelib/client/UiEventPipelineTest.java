package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R2: 入力経路の DOM イベント化。実際の文書を解析・レイアウト・描画してヒットテストの対象にし、
 * 押下・解放・ホバー・打鍵が UI Events の順序で DOM に届き、{@code on*} 属性のアクション名が
 * 文書の送り先経由で 1 回だけ呼ばれることを確かめる。
 */
public class UiEventPipelineTest {

    private static final String SESSION = "ui-event-pipeline";
    private static final int MC_LEFT = UiEventFixture.MC_LEFT;
    private static final int MC_RIGHT = UiEventFixture.MC_RIGHT;
    private static final int MC_MIDDLE = UiEventFixture.MC_MIDDLE;

    private UiEventFixture fixture;
    private List<String> log;
    private UiDocumentCompiler.Document document;

    @BeforeEach
    void setUp() {
        fixture = new UiEventFixture(SESSION);
        log = fixture.log();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private void load(String bodyHtml) {
        fixture.load(bodyHtml);
        document = fixture.document();
    }

    private HtmlNode node(String id) {
        return fixture.node(id);
    }

    private double[] center(String id) {
        return fixture.center(id);
    }

    private void record(HtmlNode target, String name, String... types) {
        fixture.record(target, name, types);
    }

    private void hover(String id) {
        fixture.hover(id);
    }

    private void press(String id, int button) {
        fixture.press(id, button);
    }

    private void release(String id, int button) {
        fixture.release(id, button);
    }

    private void click(String id, int button) {
        fixture.click(id, button);
    }

    @Test
    @DisplayName("文書の送り先が結ばれ、node.dispatchEvent だけで on* 属性のアクション名が呼ばれる")
    void inlineActionReachesCallbackThroughDocumentSink() {
        load("<div id=\"a\" onclick=\"doA\" style=\"width:100px;height:50px\"></div>");
        assertInstanceOf(HtmlDocument.class, document.root);
        assertNotNull(((HtmlDocument) document.root).inlineActionSink());

        node("a").dispatchEvent(com.nishiyu.enderframelib.core.event.CoreEvent.DomEvent.click(3, 4, 0));

        assertEquals(List.of("action:onclick=doA@a[a|3.0,4.0]"), log);
    }

    @Test
    @DisplayName("左クリックは pointerdown → mousedown → pointerup → mouseup → click の順で届き、アクションは 1 回")
    void leftClickIsSynthesizedFromPressAndRelease() {
        load("<div id=\"btn\" onclick=\"go\" style=\"width:100px;height:50px\"></div>");
        record(node("btn"), "btn", "pointerdown", "mousedown", "pointerup", "mouseup", "click");

        click("btn", MC_LEFT);

        assertEquals(List.of("btn:pointerdown", "btn:mousedown", "btn:pointerup", "btn:mouseup", "btn:click"),
                log.subList(0, 5));
        assertEquals(6, log.size(), log.toString());
        assertTrue(log.get(5).startsWith("action:onclick=go@btn[btn|"), log.get(5));
    }

    @Test
    @DisplayName("押下と解放が別の要素なら click は最も近い共通祖先に届く（どちらの要素にも届かない）")
    void clickTargetsNearestCommonAncestorOfPressAndRelease() {
        load("<div id=\"parent\" style=\"width:300px;height:100px\">"
                + "<div id=\"left\" style=\"width:100px;height:100px\"></div>"
                + "<div id=\"right\" style=\"width:100px;height:100px\"></div></div>");
        record(node("left"), "left", "click", "mousedown");
        record(node("right"), "right", "click", "mouseup");
        record(node("parent"), "parent", "click");

        press("left", MC_LEFT);
        release("right", MC_LEFT);

        assertEquals(List.of("left:mousedown", "right:mouseup", "parent:click"), log);
    }

    @Test
    @DisplayName("右クリックは contextmenu → auxclick(button=2)、中クリックは auxclick(button=1)。click は出ない")
    void secondaryButtonsProduceContextMenuAndAuxClick() {
        load("<div id=\"t\" oncontextmenu=\"menu\" style=\"width:100px;height:50px\"></div>");
        HtmlNode target = node("t");
        List<String> buttons = new ArrayList<>();
        for (String type : List.of("contextmenu", "auxclick", "click")) {
            target.addEventListener(type, (Event event) ->
                    buttons.add(type + ":" + ((UIEvents.Mouse) event).getButton()));
        }

        click("t", MC_RIGHT);
        click("t", MC_MIDDLE);

        assertEquals(List.of("contextmenu:2", "auxclick:2", "auxclick:1"), buttons);
        assertEquals(1, log.stream().filter(entry -> entry.startsWith("action:oncontextmenu=menu@t")).count());
    }

    @Test
    @DisplayName("500ms 以内の 2 回目のクリックで dblclick が click の後に届く")
    void secondQuickClickProducesDoubleClick() {
        load("<div id=\"t\" ondblclick=\"twice\" style=\"width:100px;height:50px\"></div>");
        record(node("t"), "t", "click", "dblclick");

        click("t", MC_LEFT);
        click("t", MC_LEFT);

        assertEquals(List.of("t:click", "t:click", "t:dblclick"),
                log.stream().filter(entry -> entry.startsWith("t:")).toList());
        assertEquals(1, log.stream().filter(entry -> entry.startsWith("action:ondblclick=twice@t[")).count());
    }

    @Test
    @DisplayName("ホバー遷移: out/over は浮上し、leave は内側から・enter は外側から、共通祖先には送らない")
    void hoverTransitionFollowsUiEventsOrdering() {
        load("<div id=\"outer\" style=\"width:200px;height:100px\">"
                + "<div id=\"inner\" style=\"width:100px;height:100px\"></div></div>"
                + "<div id=\"other\" style=\"width:200px;height:100px\"></div>");
        for (String id : List.of("outer", "inner", "other")) {
            record(node(id), id, "mouseover", "mouseout", "mouseenter", "mouseleave");
        }

        hover("inner");
        assertEquals(List.of(
                "inner:mouseover", "outer:mouseover", "outer:mouseenter", "inner:mouseenter"), log);

        log.clear();
        hover("other");
        assertEquals(List.of(
                "inner:mouseout", "outer:mouseout", "inner:mouseleave", "outer:mouseleave",
                "other:mouseover", "other:mouseenter"), log);
    }

    @Test
    @DisplayName("onmouseenter / onmouseleave のアクション名はホバー遷移で 1 回ずつ呼ばれる")
    void hoverActionsFireOncePerTransition() {
        load("<div id=\"a\" onmouseenter=\"in\" onmouseleave=\"out\" style=\"width:100px;height:50px\"></div>"
                + "<div id=\"b\" style=\"width:100px;height:50px\"></div>");

        hover("a");
        hover("a");
        hover("b");

        List<String> actions = log.stream().filter(entry -> entry.startsWith("action:")).toList();
        assertEquals(2, actions.size(), actions.toString());
        assertTrue(actions.get(0).startsWith("action:onmouseenter=in@a["));
        assertEquals("action:onmouseleave=out@a[]", actions.get(1));
    }

    @Test
    @DisplayName("click の既定動作はリスナーの preventDefault で止まる。止めなければチェックが入り change は 1 回だけ")
    void clickDefaultActionHonorsPreventDefault() {
        load("<input id=\"box\" type=\"checkbox\" onchange=\"changed\" style=\"width:40px;height:40px\">"
                + "<input id=\"locked\" type=\"checkbox\" onchange=\"never\" style=\"width:40px;height:40px\">");
        node("locked").addEventListener("click", Event::preventDefault);

        click("box", MC_LEFT);
        click("locked", MC_LEFT);

        assertTrue(node("box").attrs.containsKey("checked"));
        assertFalse(node("locked").attrs.containsKey("checked"));
        assertEquals(List.of("action:onchange=changed@box[true]"),
                log.stream().filter(entry -> entry.startsWith("action:onchange")).toList());
    }

    @Test
    @DisplayName("打鍵: beforeinput を止めれば値は変わらず、通れば input が 1 回、確定では change だけが続く")
    void typingProducesBeforeInputThenInputThenChange() {
        load("<input id=\"name\" type=\"text\" oninput=\"typed\" onchange=\"done\" style=\"width:120px;height:30px\">");
        HtmlNode input = node("name");
        record(input, "name", "beforeinput", "input", "change");
        input.addEventListener("beforeinput", event -> {
            if ("x".equals(((UIEvents.Input) event).getData())) {
                event.preventDefault();
            }
        });

        assertFalse(ClientScreenInteractionManager.beginTextEdit(SESSION, "name", "x", "insertText"));
        assertTrue(ClientScreenInteractionManager.beginTextEdit(SESSION, "name", "a", "insertText"));
        ClientScreenInteractionManager.commitTextEdit(SESSION, "name", "a", "a", "insertText");
        ClientScreenInteractionManager.commitTextEdit(SESSION, "name", "a", null, "insertText");
        ClientScreenInteractionManager.submitInput(SESSION, "name", "a");

        assertEquals(List.of(
                "name:beforeinput", "name:beforeinput",
                "name:input", "action:oninput=typed@name[a]",
                "name:change", "action:onchange=done@name[a]"), log);
        assertEquals("a", input.attrs.get("value"));
    }

    @Test
    @DisplayName("フォーカス移動は focusout → blur → focusin → focus を送り、onfocus のアクション名も届く")
    void focusChangeDispatchesFocusEventsAndInlineAction() {
        load("<button id=\"one\" onfocus=\"focusedOne\" style=\"width:60px;height:30px\">1</button>"
                + "<button id=\"two\" style=\"width:60px;height:30px\">2</button>");
        record(node("one"), "one", "focusin", "focus", "blur", "focusout");
        record(node("two"), "two", "focusin", "focus");

        ClientWebManager.setFocusedElement(SESSION, "one", true);
        ClientWebManager.setFocusedElement(SESSION, "two", true);

        assertEquals(List.of(
                "one:focusin", "one:focus", "action:onfocus=focusedOne@one[]",
                "one:focusout", "one:blur", "two:focusin", "two:focus"), log);
    }

    @Test
    @DisplayName("ホイールは DOM の wheel として届き、preventDefault されれば素のスクロールも on* のアクションも起きない")
    void wheelIsDispatchedAndCancelable() {
        load("<div id=\"pane\" onwheel=\"scrolled\" style=\"width:100px;height:50px\"></div>");
        node("pane").addEventListener("wheel", Event::preventDefault);
        double[] point = center("pane");

        boolean consumed = ClientScreenInteractionManager.handleScroll(SESSION, point[0], point[1], 0, -1);

        assertTrue(consumed);
        assertEquals(0, log.stream().filter(entry -> entry.startsWith("action:onwheel=")).count(),
                "クライアントのリスナーが取り消したイベントは、on* のアクション名にも届かない");
    }

    @Test
    @DisplayName("ホイールを取り消さなければ on* のアクションが 1 回だけ呼ばれる")
    void uncancelledWheelReachesInlineAction() {
        load("<div id=\"pane\" onwheel=\"scrolled\" style=\"width:100px;height:50px\"></div>");
        double[] point = center("pane");

        ClientScreenInteractionManager.handleScroll(SESSION, point[0], point[1], 0, -1);

        assertEquals(1, log.stream().filter(entry -> entry.startsWith("action:onwheel=scrolled@pane[")).count());
    }

    @Test
    @DisplayName("スライダー: 押下で input、解放で input と change（値は DOM の value に入る）")
    void rangeSliderEmitsInputOnPressAndChangeOnRelease() {
        load("<input id=\"vol\" type=\"range\" min=\"0\" max=\"100\" value=\"10\" style=\"width:200px;height:20px\">");
        HtmlNode slider = node("vol");
        record(slider, "vol", "input", "change");

        double[] point = center("vol");
        ClientScreenInteractionManager.handleHover(SESSION, point[0], point[1]);
        DisplayItem pressed = ClientScreenInteractionManager.handleClick(SESSION, point[0], point[1], MC_LEFT);
        assertEquals(DisplayItem.ElementType.RANGE, pressed.type());
        ClientScreenInteractionManager.handleRelease(SESSION, point[0], point[1], MC_LEFT, true);

        assertEquals(List.of("vol:input", "vol:input", "vol:change"), log.stream().filter(e -> e.startsWith("vol:")).toList());
        assertEquals("50", slider.attrs.get("value"));
    }
}