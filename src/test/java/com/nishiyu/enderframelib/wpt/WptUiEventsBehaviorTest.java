package com.nishiyu.enderframelib.wpt;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.client.UiEventFixture;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.wpt.WptHarness.WptCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.nishiyu.enderframelib.client.UiEventFixture.MC_LEFT;
import static com.nishiyu.enderframelib.wpt.WptHarness.assertEqualsWpt;
import static com.nishiyu.enderframelib.wpt.WptHarness.assertFalseWpt;
import static com.nishiyu.enderframelib.wpt.WptHarness.assertTrueWpt;
import static com.nishiyu.enderframelib.wpt.WptHarness.test;

/**
 * WPT の {@code uievents/} 領域から採用したケースの移植。ブラウザのポインタ操作（{@code test_driver.Actions}）の代わりに、
 * 本物のヒットテストを通る {@link UiEventFixture} の押下・解放・ホバーで同じ操作を再現する。
 * 採用の基準は他の WPT ケースと同じ（仕様に依存しない・エンジンのスコープ外）。
 */
public class WptUiEventsBehaviorTest extends WptBehaviorTest {

    private static final String SESSION = "wpt-uievents";

    @Test
    @DisplayName("uievents/mouse/attributes.html")
    @WptCase(
            path = "uievents/mouse/attributes.html",
            sha = WptHarness.PINNED_SHA,
            description = "Mouse events are trusted MouseEvents; bubbles/cancelable/composed are true except for mouseenter and mouseleave"
    )
    public void testMouseEventAttributes() {
        try (UiEventFixture fixture = new UiEventFixture(SESSION)) {
            fixture.load("<span id=\"left\" style=\"display:block;width:100px;height:40px\"></span>"
                    + "<span id=\"right\" style=\"display:block;width:100px;height:40px\"></span>");
            Map<String, Event> captured = new TreeMap<>();
            for (String type : List.of("mouseover", "mouseenter", "mousemove", "mousedown", "mouseup", "mouseout", "mouseleave")) {
                fixture.node("right").addEventListener(type, (Event event) -> captured.putIfAbsent(type, event));
            }

            fixture.hover("left");
            fixture.press("right", MC_LEFT);
            fixture.release("right", MC_LEFT);
            fixture.hover("left");

            test("every mouse event type is observed", () -> {
                assertEqualsWpt(7, captured.size(), "captured: " + captured.keySet());
            });
            for (Map.Entry<String, Event> entry : captured.entrySet()) {
                String type = entry.getKey();
                Event event = entry.getValue();
                boolean boundary = type.equals("mouseenter") || type.equals("mouseleave");
                test(type + " is a trusted MouseEvent", () -> {
                    assertTrueWpt(event instanceof UIEvents.Mouse, type + " instanceof MouseEvent");
                    assertTrueWpt(event.isTrusted(), type + ".isTrusted");
                });
                test(type + " bubbles/cancelable/composed", () -> {
                    assertEqualsWpt(!boundary, event.isBubbles(), type + ".bubbles");
                    assertEqualsWpt(!boundary, event.isCancelable(), type + ".cancelable");
                    assertEqualsWpt(!boundary, event.isComposed(), type + ".composed");
                });
            }
        }
    }

    @Test
    @DisplayName("uievents/order-of-events/mouse-events/click-order.html")
    @WptCase(
            path = "uievents/order-of-events/mouse-events/click-order.html",
            sha = WptHarness.PINNED_SHA,
            description = "Double click yields mousedown, mouseup, click, mousedown, mouseup, click, dblclick (mousemove optional)"
    )
    public void testClickOrder() {
        try (UiEventFixture fixture = new UiEventFixture(SESSION)) {
            fixture.load("<div id=\"blue\" style=\"width:300px;height:200px\"></div>");
            fixture.record(fixture.node("blue"), "blue", "mousedown", "mouseup", "mousemove", "click", "dblclick");

            fixture.hover("blue");
            fixture.click("blue", MC_LEFT);
            fixture.click("blue", MC_LEFT);

            test("events arrive in the expected order, ignoring optional mousemove", () -> {
                List<String> withoutMoves = fixture.log().stream().filter(e -> !e.endsWith(":mousemove")).toList();
                assertEqualsWpt(List.of(
                        "blue:mousedown", "blue:mouseup", "blue:click",
                        "blue:mousedown", "blue:mouseup", "blue:click", "blue:dblclick"), withoutMoves,
                        "click order");
            });
            test("every recorded event bubbles", () -> {
                fixture.node("blue").addEventListener("click", (Event event) -> assertTrueWpt(event.isBubbles(), "click.bubbles"));
                fixture.click("blue", MC_LEFT);
            });
        }
    }

    @Test
    @DisplayName("uievents/order-of-events/mouse-events/mouseover-out.html")
    @WptCase(
            path = "uievents/order-of-events/mouse-events/mouseover-out.html",
            sha = WptHarness.PINNED_SHA,
            description = "mouseover/mouseout order while moving between an outer element, its inner child and a sibling"
    )
    public void testMouseOverOut() {
        try (UiEventFixture fixture = new UiEventFixture(SESSION)) {
            fixture.load("<div id=\"outer\" style=\"padding:10px;width:200px;height:60px\">"
                    + "<div id=\"inner\" style=\"height:40px\"></div></div>"
                    + "<div id=\"released\" style=\"height:40px\"></div>");
            List<String> events = new ArrayList<>();
            for (String type : List.of("mouseover", "mouseout")) {
                fixture.node("inner").addEventListener(type, (Event e) -> events.add("inner:" + type + "/" + targetId(e)));
                fixture.node("outer").addEventListener(type, (Event e) -> events.add("outer:" + type + "/" + targetId(e)));
            }
            double[] outerPadding = fixture.insetCorner("outer", 3);

            fixture.hoverAt(outerPadding);
            fixture.hover("inner");
            fixture.hover("released");
            fixture.hover("inner");
            fixture.hoverAt(outerPadding);
            fixture.hoverOutside();

            test("events follow the spec order for every transition", () -> {
                assertEqualsWpt(List.of(
                        "outer:mouseover/outer",
                        "outer:mouseout/outer", "inner:mouseover/inner", "outer:mouseover/inner",
                        "inner:mouseout/inner", "outer:mouseout/inner",
                        "inner:mouseover/inner", "outer:mouseover/inner",
                        "inner:mouseout/inner", "outer:mouseout/inner", "outer:mouseover/outer",
                        "outer:mouseout/outer"), events, "mouseover/mouseout order");
            });
        }
    }

    @Test
    @DisplayName("uievents/order-of-events/mouse-events/click-cancel.html")
    @WptCase(
            path = "uievents/order-of-events/mouse-events/click-cancel.html",
            sha = WptHarness.PINNED_SHA,
            description = "Default action is canceled if the click event is canceled"
    )
    public void testClickCancel() {
        try (UiEventFixture fixture = new UiEventFixture(SESSION)) {
            fixture.load("<input id=\"checkbox\" type=\"checkbox\" style=\"width:30px;height:30px\">"
                    + "<button id=\"button\" type=\"button\" style=\"width:80px;height:30px\">Button</button>");
            HtmlNode box = fixture.node("checkbox");
            boolean[] boxClicked = {false};
            box.addEventListener("click", (Event event) -> {
                boxClicked[0] = true;
                event.preventDefault();
            });

            fixture.click("checkbox", MC_LEFT);
            fixture.click("button", MC_LEFT);

            test("the canceled click leaves the checkbox unchecked", () -> {
                assertTrueWpt(boxClicked[0], "the checkbox click listener ran");
                assertFalseWpt(box.attrs.containsKey("checked"), "box.checked");
            });
        }
    }

    private static String targetId(Event event) {
        return event.getTarget() instanceof HtmlNode node ? node.getId() : "?";
    }
}
