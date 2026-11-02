package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.api.dom.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

public class FocusManagementTest {

    @Test
    @DisplayName("フォーカス移動時のイベント発火順序 (focusout -> blur -> focusin -> focus)")
    public void testFocusTransitionEventSequence() {
        HtmlDocument document = new HtmlDocument();
        HtmlElement body = new HtmlElement("body");
        HtmlElement input1 = new HtmlElement("input");
        input1.setId("input1");
        HtmlElement input2 = new HtmlElement("input");
        input2.setId("input2");

        document.appendChild(body);
        body.appendChild(input1);
        body.appendChild(input2);

        // 初期状態で input1 にフォーカス
        document.setFocusedElement(input1, false);
        assertSame(input1, document.getActiveElement());

        List<String> events = new ArrayList<>();

        input1.addEventListener("focusout", ev -> {
            UIEvents.Focus fe = (UIEvents.Focus) ev;
            events.add("input1:focusout:bubbles=" + ev.isBubbles() + ":related=" + ((Element) fe.getRelatedTarget()).getId());
        });
        input1.addEventListener("blur", ev -> {
            UIEvents.Focus fe = (UIEvents.Focus) ev;
            events.add("input1:blur:bubbles=" + ev.isBubbles() + ":related=" + ((Element) fe.getRelatedTarget()).getId());
        });

        input2.addEventListener("focusin", ev -> {
            UIEvents.Focus fe = (UIEvents.Focus) ev;
            events.add("input2:focusin:bubbles=" + ev.isBubbles() + ":related=" + ((Element) fe.getRelatedTarget()).getId());
        });
        input2.addEventListener("focus", ev -> {
            UIEvents.Focus fe = (UIEvents.Focus) ev;
            events.add("input2:focus:bubbles=" + ev.isBubbles() + ":related=" + ((Element) fe.getRelatedTarget()).getId());
        });

        // input2 へフォーカス移動
        input2.focus();

        assertSame(input2, document.getActiveElement(), "activeElement が input2 に更新されること");

        List<String> expected = List.of(
                "input1:focusout:bubbles=true:related=input2",
                "input1:blur:bubbles=false:related=input2",
                "input2:focusin:bubbles=true:related=input1",
                "input2:focus:bubbles=false:related=input1"
        );
        assertEquals(expected, events, "標準仕様通りのイベント順序と bubbles/relatedTarget であること");
    }

    @Test
    @DisplayName("blur() によるフォーカス解除と activeElement の初期化")
    public void testBlurClearsActiveElement() {
        HtmlDocument document = new HtmlDocument();
        HtmlElement body = new HtmlElement("body");
        HtmlElement input = new HtmlElement("input");
        document.appendChild(body);
        body.appendChild(input);

        input.focus();
        assertSame(input, document.getActiveElement());

        List<String> blurEvents = new ArrayList<>();
        input.addEventListener("blur", ev -> blurEvents.add("blurred"));

        input.blur();

        assertEquals(List.of("blurred"), blurEvents);
        assertSame(body, document.getActiveElement(), "フォーカス解除後は body が activeElement になること");
    }

    @Test
    @DisplayName("disabled 要素への focus() は無視されること")
    public void testDisabledElementCannotBeFocused() {
        HtmlDocument document = new HtmlDocument();
        HtmlElement body = new HtmlElement("body");
        HtmlElement disabledInput = new HtmlElement("input");
        disabledInput.setAttribute("disabled", "true");
        document.appendChild(body);
        body.appendChild(disabledInput);

        disabledInput.focus();
        assertNotSame(disabledInput, document.getActiveElement(), "disabled 要素はフォーカスされないこと");
    }
}
