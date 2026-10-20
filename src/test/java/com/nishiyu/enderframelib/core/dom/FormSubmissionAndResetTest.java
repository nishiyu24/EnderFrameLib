package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.api.dom.FormData;
import com.nishiyu.enderframelib.core.dom.FormController;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.DisplayName;
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

public class FormSubmissionAndResetTest {

    @Test
    @DisplayName("requestSubmit 成功時: submit イベント発火 -> FormData 構築 -> formdata イベント発火")
    public void testRequestSubmitSuccess() {
        HtmlDocument doc = new HtmlDocument();
        HtmlElement body = new HtmlElement("body");
        HtmlElement form = new HtmlElement("form");
        doc.appendChild(body);
        body.appendChild(form);

        HtmlElement textInput = new HtmlElement("input");
        textInput.setAttribute("type", "text");
        textInput.setAttribute("name", "username");
        textInput.setAttribute("value", "alice");

        HtmlElement chk1 = new HtmlElement("input");
        chk1.setAttribute("type", "checkbox");
        chk1.setAttribute("name", "subscribe");
        chk1.setAttribute("value", "yes");
        chk1.setAttribute("checked", "true");

        HtmlElement chk2 = new HtmlElement("input");
        chk2.setAttribute("type", "checkbox");
        chk2.setAttribute("name", "newsletter");
        chk2.setAttribute("value", "weekly"); // unchecked

        HtmlElement submitBtn = new HtmlElement("button");
        submitBtn.setAttribute("type", "submit");
        submitBtn.setAttribute("name", "action");
        submitBtn.setAttribute("value", "save");

        form.appendChild(textInput);
        form.appendChild(chk1);
        form.appendChild(chk2);
        form.appendChild(submitBtn);

        AtomicReference<UIEvents.Submit> receivedSubmitEvent = new AtomicReference<>();
        AtomicReference<FormData> receivedFormData = new AtomicReference<>();

        form.addEventListener("submit", ev -> {
            receivedSubmitEvent.set((UIEvents.Submit) ev);
        });

        form.addEventListener("formdata", ev -> {
            UIEvents.FormDataCollect fe = (UIEvents.FormDataCollect) ev;
            receivedFormData.set(fe.getFormData());
        });

        form.requestSubmit(submitBtn);

        assertNotNull(receivedSubmitEvent.get(), "submit イベントが発火すること");
        assertSame(submitBtn, receivedSubmitEvent.get().getSubmitter(), "submitter が正しく渡されること");
        assertTrue(receivedSubmitEvent.get().isBubbles(), "submit はバブリングすること");
        assertTrue(receivedSubmitEvent.get().isCancelable(), "submit はキャンセル可能であること");

        assertNotNull(receivedFormData.get(), "formdata イベントが発火すること");
        FormData data = receivedFormData.get();
        assertEquals("alice", data.get("username"));
        assertEquals("yes", data.get("subscribe"));
        assertNull(data.get("newsletter"), "未チェックのチェックボックスは含まれないこと");
        assertEquals("save", data.get("action"), "クリックされた submitter ボタンの値が含まれること");
    }

    @Test
    @DisplayName("requestSubmit 制約違反時: submit は発火せず違反コントロールにフォーカス")
    public void testRequestSubmitBlockedByConstraint() {
        HtmlDocument doc = new HtmlDocument();
        HtmlElement body = new HtmlElement("body");
        HtmlElement form = new HtmlElement("form");
        doc.appendChild(body);
        body.appendChild(form);

        HtmlElement invalidInput = new HtmlElement("input");
        invalidInput.setAttribute("type", "text");
        invalidInput.setAttribute("required", "true");
        // value は空
        form.appendChild(invalidInput);

        AtomicBoolean submitFired = new AtomicBoolean(false);
        AtomicBoolean invalidFired = new AtomicBoolean(false);

        form.addEventListener("submit", ev -> submitFired.set(true));
        invalidInput.addEventListener("invalid", ev -> invalidFired.set(true));

        form.requestSubmit(null);

        assertTrue(invalidFired.get(), "制約違反コントロールで invalid イベントが発火すること");
        assertFalse(submitFired.get(), "制約違反がある場合 submit イベントは発火しないこと");
        assertSame(invalidInput, doc.getActiveElement(), "制約違反コントロールにフォーカスが移動すること");
    }

    @Test
    @DisplayName("submit ハンドラで preventDefault した場合 formdata は発火しないこと")
    public void testSubmitPreventDefaultCancelsFormData() {
        HtmlElement form = new HtmlElement("form");
        AtomicBoolean formdataFired = new AtomicBoolean(false);

        form.addEventListener("submit", ev -> {
            ev.preventDefault();
        });

        form.addEventListener("formdata", ev -> {
            formdataFired.set(true);
        });

        form.requestSubmit(null);

        assertFalse(formdataFired.get(), "preventDefault された場合 formdata イベントは発火しないこと");
    }

    @Test
    @DisplayName("form.reset() によるリセットイベント発火とコントロール初期値の復元")
    public void testFormReset() {
        HtmlElement form = new HtmlElement("form");

        HtmlElement input = new HtmlElement("input");
        input.setAttribute("type", "text");
        input.setAttribute("value", "initial");
        input.getDefaultValue(); // 初期値を初期化

        HtmlElement checkbox = new HtmlElement("input");
        checkbox.setAttribute("type", "checkbox");
        checkbox.setAttribute("checked", "true");
        checkbox.isDefaultChecked(); // 初期状態を初期化

        form.appendChild(input);
        form.appendChild(checkbox);

        // ユーザーが値を書き換え
        input.setAttribute("value", "modified");
        checkbox.removeAttribute("checked");

        List<String> resetEvents = new ArrayList<>();
        form.addEventListener("reset", ev -> {
            resetEvents.add("form:reset:cancelable=" + ev.isCancelable());
        });

        List<String> inputChanges = new ArrayList<>();
        input.addEventListener("input", ev -> inputChanges.add("input:input"));
        input.addEventListener("change", ev -> inputChanges.add("input:change"));
        checkbox.addEventListener("change", ev -> inputChanges.add("checkbox:change"));

        form.reset();

        assertEquals(List.of("form:reset:cancelable=true"), resetEvents);
        assertEquals("initial", input.getAttribute("value"), "input の値が初期値に戻ること");
        assertTrue(checkbox.hasAttribute("checked"), "checkbox が初期の checked 状態に戻ること");
        assertTrue(inputChanges.contains("input:input") && inputChanges.contains("input:change"),
                "リセット時に input / change イベントが通知されること");
    }

    @Test
    @DisplayName("form 属性によりフォーム外の要素が正しく紐付くこと")
    public void testExternalControlsLinkedViaFormAttribute() {
        HtmlDocument doc = new HtmlDocument();
        HtmlElement body = new HtmlElement("body");
        doc.appendChild(body);

        HtmlElement form = new HtmlElement("form");
        form.setId("myForm");
        body.appendChild(form);

        // form の外側に配置
        HtmlElement outsideInput = new HtmlElement("input");
        outsideInput.setAttribute("type", "text");
        outsideInput.setAttribute("name", "outsideField");
        outsideInput.setAttribute("value", "outsideValue");
        outsideInput.setAttribute("form", "myForm");
        body.appendChild(outsideInput);

        HtmlElement outsideSubmitBtn = new HtmlElement("button");
        outsideSubmitBtn.setAttribute("type", "submit");
        outsideSubmitBtn.setAttribute("name", "submitBtn");
        outsideSubmitBtn.setAttribute("value", "btnClicked");
        outsideSubmitBtn.setAttribute("form", "myForm");
        body.appendChild(outsideSubmitBtn);

        // 関連付けの検証
        assertSame(form, FormController.getFormOwner(outsideInput));
        assertSame(form, FormController.getFormOwner(outsideSubmitBtn));

        List<HtmlElement> controls = FormController.getFormControls(form);
        assertTrue(controls.contains(outsideInput), "フォーム外の input が controls に含まれること");
        assertTrue(controls.contains(outsideSubmitBtn), "フォーム外の button が controls に含まれること");

        // requestSubmit 実行
        AtomicReference<FormData> receivedFormData = new AtomicReference<>();
        form.addEventListener("formdata", ev -> {
            receivedFormData.set(((UIEvents.FormDataCollect) ev).getFormData());
        });

        form.requestSubmit(outsideSubmitBtn);

        assertNotNull(receivedFormData.get());
        assertEquals("outsideValue", receivedFormData.get().get("outsideField"),
                "フォーム外の input の値が FormData に含まれること");
        assertEquals("btnClicked", receivedFormData.get().get("submitBtn"),
                "フォーム外の submit ボタンの値が FormData に含まれること");
    }
}