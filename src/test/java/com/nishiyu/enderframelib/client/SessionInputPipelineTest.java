package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.gui.EnderScreenDelegate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * STRUCTURE_PLAN T3（S4）: GUI 画面とワールド内で、同じ入力が同じ順序で DOM に届くことを確かめる。
 *
 * <p>ホスト側の仕事は座標変換と GUI 固有のポリシーだけ。押下・解放・クリック合成・ホイール・
 * テキスト編集は {@link SessionInput} の 1 本の経路を通るため、どちらのホストでも結果が一致する。</p>
 */
public class SessionInputPipelineTest {

    private static final String HTML = "<div id=\"stage\" style=\"width:300px;height:300px\">"
            + "<button id=\"btn\" onclick=\"go\" style=\"width:100px;height:40px\">Go</button>"
            + "<input id=\"box\" type=\"checkbox\" onchange=\"toggled\" style=\"width:40px;height:40px\">"
            + "<input id=\"vol\" type=\"range\" min=\"0\" max=\"100\" value=\"10\" style=\"width:200px;height:20px\">"
            + "<div id=\"pane\" onwheel=\"wheel\" style=\"width:100px;height:50px\"></div>"
            + "<input id=\"name\" type=\"text\" oninput=\"typed\" onchange=\"done\" style=\"width:120px;height:30px\">"
            + "</div>";

    @Test
    @DisplayName("GUI とワールド内で同じポインタ・ホイール・テキスト編集が同じ順序で DOM に届く")
    void guiAndWorldShareTheSameInputPipeline() {
        List<String> worldLog;
        boolean worldChecked;
        String worldRangeValue;
        String worldNameValue;

        try (UiEventFixture world = new UiEventFixture("pipeline-world")) {
            world.load(HTML);
            recordEvents(world);
            runSequence(new HostDriver(world, null));

            worldLog = new ArrayList<>(world.log());
            worldChecked = world.node("box").attrs.containsKey("checked");
            worldRangeValue = world.node("vol").attrs.get("value");
            worldNameValue = world.node("name").attrs.get("value");
        }

        EnderScreenDelegate guiDelegate = new EnderScreenDelegate(
                "pipeline-gui", new ReactiveStateStore("pipeline-gui"));
        List<String> guiLog;
        boolean guiChecked;
        String guiRangeValue;
        String guiNameValue;

        try (UiEventFixture gui = new UiEventFixture("pipeline-gui")) {
            // デリゲートのコンストラクタがグローバルなコールバックを差し替えるため、記録役を戻す。
            gui.attachActionRecorder();
            gui.load(HTML);
            recordEvents(gui);
            runSequence(new HostDriver(gui, guiDelegate));

            guiLog = new ArrayList<>(gui.log());
            guiChecked = gui.node("box").attrs.containsKey("checked");
            guiRangeValue = gui.node("vol").attrs.get("value");
            guiNameValue = gui.node("name").attrs.get("value");
        } finally {
            guiDelegate.onClose();
        }

        assertEquals(worldLog, guiLog, "GUI とワールド内で DOM イベントの順序が違う");
        assertTrue(worldChecked, "ワールド内でチェックボックスが切り替わっていない");
        assertEquals(worldChecked, guiChecked, "GUI とワールド内でチェックボックスの結果が違う");
        assertNotNull(worldRangeValue, "ワールド内でレンジの値が入っていない");
        assertEquals(worldRangeValue, guiRangeValue, "GUI とワールド内でレンジの値が違う");
        assertEquals("a", worldNameValue);
        assertEquals(worldNameValue, guiNameValue, "GUI とワールド内でテキスト入力の結果が違う");
    }

    @Test
    @DisplayName("label のクリックは GUI とワールド内で同じ既定動作（for 先のコントロール）になる")
    void labelClicksDelegateTheSameWay() {
        String html = "<div id=\"stage\" style=\"width:300px;height:300px\">"
                + "<label id=\"lbl\" for=\"box2\" style=\"width:100px;height:30px;display:block\">Toggle</label>"
                + "<input id=\"box2\" type=\"checkbox\" onchange=\"toggled2\" style=\"width:40px;height:40px\">"
                + "</div>";

        List<String> worldLog;
        boolean worldChecked;

        try (UiEventFixture world = new UiEventFixture("label-world")) {
            world.load(html);
            world.record(world.node("lbl"), "lbl", "click");
            world.record(world.node("box2"), "box2", "click", "input", "change");
            new HostDriver(world, null).clickAllowingFallthrough("lbl", UiEventFixture.MC_LEFT);

            worldLog = new ArrayList<>(world.log());
            worldChecked = world.node("box2").attrs.containsKey("checked");
        }

        EnderScreenDelegate guiDelegate = new EnderScreenDelegate(
                "label-gui", new ReactiveStateStore("label-gui"));
        List<String> guiLog;
        boolean guiChecked;

        try (UiEventFixture gui = new UiEventFixture("label-gui")) {
            gui.attachActionRecorder();
            gui.load(html);
            gui.record(gui.node("lbl"), "lbl", "click");
            gui.record(gui.node("box2"), "box2", "click", "input", "change");
            new HostDriver(gui, guiDelegate).clickAllowingFallthrough("lbl", UiEventFixture.MC_LEFT);

            guiLog = new ArrayList<>(gui.log());
            guiChecked = gui.node("box2").attrs.containsKey("checked");
        } finally {
            guiDelegate.onClose();
        }

        assertTrue(worldChecked, "ワールド内で label の for 先が切り替わっていない");
        assertEquals(worldChecked, guiChecked, "GUI とワールド内で label の既定動作が違う");
        assertEquals(worldLog, guiLog, "GUI とワールド内で label のクリックイベントが違う");
    }

    @Test
    @DisplayName("range 要素のドラッグは GUI とワールド内で同じ値更新とイベント順になる")
    void rangeDragDelegatesTheSameWay() {
        String html = "<div id=\"stage\" style=\"width:300px;height:300px\">"
                + "<input id=\"slider\" type=\"range\" min=\"0\" max=\"100\" value=\"0\" style=\"width:100px;height:20px\">"
                + "</div>";

        String worldValue;
        List<String> worldLog;

        try (UiEventFixture world = new UiEventFixture("drag-world")) {
            world.load(html);
            world.record(world.node("slider"), "slider", "pointerdown", "mousedown", "input", "change", "pointerup", "mouseup", "click");
            double[] start = world.center("slider");
            double dragX = start[0] + 50.0; // 50% 右へドラッグ
            double dragY = start[1];

            world.hoverAt(start);
            ClientScreenInteractionManager.handleClick("drag-world", start[0], start[1], UiEventFixture.MC_LEFT);
            ClientScreenInteractionManager.handleDrag("drag-world", dragX, dragY);
            ClientScreenInteractionManager.handleRelease("drag-world", dragX, dragY, UiEventFixture.MC_LEFT, true);

            worldValue = world.node("slider").attrs.get("value");
            worldLog = new ArrayList<>(world.log());
        }

        EnderScreenDelegate guiDelegate = new EnderScreenDelegate(
                "drag-gui", new ReactiveStateStore("drag-gui"));
        String guiValue;
        List<String> guiLog;

        try (UiEventFixture gui = new UiEventFixture("drag-gui")) {
            gui.attachActionRecorder();
            gui.load(html);
            gui.record(gui.node("slider"), "slider", "pointerdown", "mousedown", "input", "change", "pointerup", "mouseup", "click");
            double[] start = gui.center("slider");
            double dragX = start[0] + 50.0;
            double dragY = start[1];

            gui.hoverAt(start);
            guiDelegate.handleMouseClicked(null, null, start[0], start[1], UiEventFixture.MC_LEFT, null, null);
            guiDelegate.handleMouseDragged(dragX, dragY, 50.0, 0.0, 1.0f, null);
            guiDelegate.handleMouseReleased(dragX, dragY, UiEventFixture.MC_LEFT, null);

            guiValue = gui.node("slider").attrs.get("value");
            guiLog = new ArrayList<>(gui.log());
        } finally {
            guiDelegate.onClose();
        }

        assertNotNull(worldValue);
        assertEquals(worldValue, guiValue, "GUI とワールド内でドラッグ後のレンジ値が違う");
        assertEquals(worldLog, guiLog, "GUI とワールド内でドラッグ時のイベント順が違う");
    }

    @Test
    @DisplayName("EnderScreenHost のデフォルトメソッドが正しく委譲される")
    void enderScreenHostDefaultMethodsWork() {
        String sessionId = "host-test";
        ReactiveStateStore state = new ReactiveStateStore(sessionId);
        EnderScreenDelegate delegate = new EnderScreenDelegate(sessionId, state);

        com.nishiyu.enderframelib.client.gui.EnderScreenHost host = new com.nishiyu.enderframelib.client.gui.EnderScreenHost() {
            @Override
            public String getWebSessionId() {
                return sessionId;
            }

            @Override
            public EnderScreenDelegate getUiDelegate() {
                return delegate;
            }

            @Override
            public net.minecraft.client.gui.screens.Screen getWebScreen() {
                return null;
            }
        };

        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div id=\"box\" class=\"initial\" style=\"color:red\">text</div>");

            host.setAttribute("box", "data-test", "val");
            assertEquals("val", fixture.node("box").attrs.get("data-test"));

            host.setStyle("box", "color", "blue");
            assertTrue(fixture.node("box").attrs.get("style").contains("color: blue"));

            host.setElementText("box", "updated");
            assertEquals("updated", fixture.node("box").getTextContent());

            host.addClass("box", "extra");
            assertTrue(fixture.node("box").hasClass("extra"));

            host.removeClass("box", "initial");
            assertFalse(fixture.node("box").hasClass("initial"));

            host.toggleClass("box", "toggled", true);
            assertTrue(fixture.node("box").hasClass("toggled"));

            assertNotNull(host.getCanvas("c1"));
            assertNotNull(host.getScene("s1"));
        } finally {
            delegate.onClose();
        }
    }

    private static void recordEvents(UiEventFixture fixture) {
        fixture.record(fixture.node("btn"), "btn",
                "pointerover", "pointerenter", "pointerdown", "mousedown", "pointerup", "mouseup", "click");
        fixture.record(fixture.node("box"), "box",
                "pointerover", "pointerenter", "pointerdown", "mousedown", "pointerup", "mouseup", "click", "input", "change");
        fixture.record(fixture.node("vol"), "vol",
                "pointerover", "pointerenter", "pointerdown", "mousedown", "pointerup", "mouseup", "click", "input", "change");
        fixture.record(fixture.node("pane"), "pane", "pointerover", "pointerenter", "wheel");
        fixture.record(fixture.node("name"), "name", "beforeinput", "input", "change");
    }

    private static void runSequence(HostDriver driver) {
        driver.hover("btn");
        driver.click("btn", UiEventFixture.MC_LEFT);

        driver.hover("box");
        driver.click("box", UiEventFixture.MC_LEFT);

        driver.hover("vol");
        driver.click("vol", UiEventFixture.MC_LEFT);

        driver.hover("pane");
        driver.scroll("pane", -1);

        driver.type("name", "a");
    }

    /** ホスト差だけを吸収する。押下以降はどちらも SessionInput に届く。 */
    private static final class HostDriver {
        private final UiEventFixture fixture;
        private final EnderScreenDelegate guiDelegate;
        private final String sessionId;

        private HostDriver(UiEventFixture fixture, EnderScreenDelegate guiDelegate) {
            this.fixture = fixture;
            this.guiDelegate = guiDelegate;
            this.sessionId = fixture.sessionId();
        }

        private void hover(String id) {
            // GUI の基底クラスも描画時に同じホバー経路を呼ぶ。
            fixture.hover(id);
        }

        private void click(String id, int button) {
            double[] point = fixture.center(id);
            fixture.hoverAt(point);
            if (guiDelegate == null) {
                ClientScreenInteractionManager.handleClick(sessionId, point[0], point[1], button);
                ClientScreenInteractionManager.handleRelease(sessionId, point[0], point[1], button, true);
            } else {
                assertTrue(guiDelegate.handleMouseClicked(null, null, point[0], point[1], button, null, null),
                        "#" + id + " の押下が GUI に届いていない");
                guiDelegate.handleMouseReleased(point[0], point[1], button, null);
            }
        }

        /** 既定動作だけを持ち、押下自体は画面に消費されない要素（label など）向け。 */
        private void clickAllowingFallthrough(String id, int button) {
            double[] point = fixture.center(id);
            fixture.hoverAt(point);
            if (guiDelegate == null) {
                ClientScreenInteractionManager.handleClick(sessionId, point[0], point[1], button);
                ClientScreenInteractionManager.handleRelease(sessionId, point[0], point[1], button, true);
            } else {
                guiDelegate.handleMouseClicked(null, null, point[0], point[1], button, null, null);
                guiDelegate.handleMouseReleased(point[0], point[1], button, null);
            }
        }

        private void scroll(String id, double deltaY) {
            double[] point = fixture.center(id);
            fixture.hoverAt(point);
            SessionInput input = ClientWebManager.getOrCreateSession(sessionId).input();
            if (guiDelegate == null) {
                input.scroll(point[0], point[1], 0, deltaY, false, false, false, SessionInput.Host.NONE);
            } else {
                // 基底クラスの handleMouseScrolled は修飾キーを足してこのホストを渡すだけ。
                input.scroll(point[0], point[1], 0, deltaY, false, false, false, guiDelegate);
            }
        }

        private void type(String id, String value) {
            SessionInput input = ClientWebManager.getOrCreateSession(sessionId).input();
            assertTrue(input.beginTextEdit(id, value, "insertText"), "#" + id + " の beforeinput が取り消された");
            input.commitTextEdit(id, value, value, "insertText");
            input.submitInput(id, value);
        }
    }
}
