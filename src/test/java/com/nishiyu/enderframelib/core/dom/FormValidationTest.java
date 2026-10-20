package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.api.dom.ValidityState;
import com.nishiyu.enderframelib.core.dom.HtmlElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class FormValidationTest {

    @Test
    @DisplayName("required 属性による valueMissing の検証 (text, checkbox, radio)")
    public void testRequiredValidation() {
        // 1. Text input
        HtmlElement textInput = new HtmlElement("input");
        textInput.setAttribute("type", "text");
        textInput.setAttribute("required", "true");

        ValidityState state1 = textInput.getValidity();
        assertTrue(state1.isValueMissing(), "値が空の場合は valueMissing が true");
        assertFalse(state1.isValid(), "全体としても valid でないこと");

        textInput.setAttribute("value", "hello");
        ValidityState state2 = textInput.getValidity();
        assertFalse(state2.isValueMissing(), "値が入っていれば valueMissing は false");
        assertTrue(state2.isValid(), "有効であること");

        // 2. Checkbox
        HtmlElement checkbox = new HtmlElement("input");
        checkbox.setAttribute("type", "checkbox");
        checkbox.setAttribute("required", "true");

        assertTrue(checkbox.getValidity().isValueMissing(), "未チェック時は valueMissing");
        checkbox.setAttribute("checked", "true");
        assertFalse(checkbox.getValidity().isValueMissing(), "チェック時は valid");

        // 3. Radio Group
        HtmlElement parent = new HtmlElement("div");
        HtmlElement radio1 = new HtmlElement("input");
        radio1.setAttribute("type", "radio");
        radio1.setAttribute("name", "color");
        radio1.setAttribute("required", "true");

        HtmlElement radio2 = new HtmlElement("input");
        radio2.setAttribute("type", "radio");
        radio2.setAttribute("name", "color");

        parent.appendChild(radio1);
        parent.appendChild(radio2);

        assertTrue(radio1.getValidity().isValueMissing(), "グループ内にチェック済みがないため valueMissing");

        radio2.setAttribute("checked", "true");
        assertFalse(radio1.getValidity().isValueMissing(), "グループ内の別ラジオがチェックされたので radio1 も valid");
    }

    @Test
    @DisplayName("typeMismatch の検証 (email, url, color)")
    public void testTypeMismatch() {
        // Email
        HtmlElement emailInput = new HtmlElement("input");
        emailInput.setAttribute("type", "email");
        emailInput.setAttribute("value", "invalid-email");
        assertTrue(emailInput.getValidity().isTypeMismatch());

        emailInput.setAttribute("value", "user@example.com");
        assertFalse(emailInput.getValidity().isTypeMismatch());

        // Multiple email
        emailInput.setAttribute("multiple", "true");
        emailInput.setAttribute("value", "user1@example.com, bad-address");
        assertTrue(emailInput.getValidity().isTypeMismatch());

        emailInput.setAttribute("value", "user1@example.com, user2@domain.org");
        assertFalse(emailInput.getValidity().isTypeMismatch());

        // URL
        HtmlElement urlInput = new HtmlElement("input");
        urlInput.setAttribute("type", "url");
        urlInput.setAttribute("value", "not_a_url");
        assertTrue(urlInput.getValidity().isTypeMismatch());

        urlInput.setAttribute("value", "https://example.com/test");
        assertFalse(urlInput.getValidity().isTypeMismatch());

        // Color
        HtmlElement colorInput = new HtmlElement("input");
        colorInput.setAttribute("type", "color");
        colorInput.setAttribute("value", "#ff00zz");
        assertTrue(colorInput.getValidity().isTypeMismatch());

        colorInput.setAttribute("value", "#ff55aa");
        assertFalse(colorInput.getValidity().isTypeMismatch());
    }

    @Test
    @DisplayName("数値範囲とステップの検証 (min, max, step, badInput)")
    public void testNumericRangeAndStep() {
        HtmlElement numInput = new HtmlElement("input");
        numInput.setAttribute("type", "number");
        numInput.setAttribute("min", "10");
        numInput.setAttribute("max", "20");
        numInput.setAttribute("step", "2");

        // min 未満
        numInput.setAttribute("value", "8");
        assertTrue(numInput.getValidity().isRangeUnderflow());
        assertFalse(numInput.getValidity().isValid());

        // max 超過
        numInput.setAttribute("value", "22");
        assertTrue(numInput.getValidity().isRangeOverflow());
        assertFalse(numInput.getValidity().isValid());

        // step 不一致 (10基準でステップ2 -> 11は不一致)
        numInput.setAttribute("value", "11");
        assertTrue(numInput.getValidity().isStepMismatch());
        assertFalse(numInput.getValidity().isValid());

        // 有効値
        numInput.setAttribute("value", "14");
        assertTrue(numInput.getValidity().isValid());

        // 不正な数値文字列
        numInput.setAttribute("value", "abc");
        assertTrue(numInput.getValidity().isBadInput());
        assertFalse(numInput.getValidity().isValid());
    }

    @Test
    @DisplayName("長さ検証 (minlength, maxlength)")
    public void testLengthValidation() {
        HtmlElement textInput = new HtmlElement("input");
        textInput.setAttribute("type", "text");
        textInput.setAttribute("minlength", "3");
        textInput.setAttribute("maxlength", "5");

        textInput.setAttribute("value", "ab");
        assertTrue(textInput.getValidity().isTooShort());

        textInput.setAttribute("value", "abcdef");
        assertTrue(textInput.getValidity().isTooLong());

        textInput.setAttribute("value", "abcd");
        assertTrue(textInput.getValidity().isValid());
    }

    @Test
    @DisplayName("pattern 属性による正規表現検証")
    public void testPatternValidation() {
        HtmlElement input = new HtmlElement("input");
        input.setAttribute("type", "text");
        input.setAttribute("pattern", "[0-9]{3}-[0-9]{4}");

        input.setAttribute("value", "123-abcd");
        assertTrue(input.getValidity().isPatternMismatch());

        input.setAttribute("value", "123-4567");
        assertFalse(input.getValidity().isPatternMismatch());
        assertTrue(input.getValidity().isValid());
    }

    @Test
    @DisplayName("setCustomValidity によるカスタムエラーメッセージ")
    public void testCustomValidity() {
        HtmlElement input = new HtmlElement("input");
        input.setAttribute("type", "text");
        input.setAttribute("value", "valid");

        assertTrue(input.getValidity().isValid());
        assertEquals("", input.getValidationMessage());

        input.setCustomValidity("Username already taken.");
        assertTrue(input.getValidity().isCustomError());
        assertFalse(input.getValidity().isValid());
        assertEquals("Username already taken.", input.getValidationMessage());

        input.setCustomValidity(""); // クリア
        assertFalse(input.getValidity().isCustomError());
        assertTrue(input.getValidity().isValid());
        assertEquals("", input.getValidationMessage());
    }

    @Test
    @DisplayName("checkValidity() が制約違反時に invalid イベントを発火すること")
    public void testCheckValidityDispatchesInvalidEvent() {
        HtmlElement input = new HtmlElement("input");
        input.setAttribute("type", "text");
        input.setAttribute("required", "true");

        List<String> events = new ArrayList<>();
        input.addEventListener("invalid", ev -> {
            events.add("invalid:bubbles=" + ev.isBubbles() + ":cancelable=" + ev.isCancelable());
        });

        boolean valid = input.checkValidity();
        assertFalse(valid, "空の required input なので false を返す");
        assertEquals(List.of("invalid:bubbles=false:cancelable=true"), events,
                "DOM仕様通り bubbles=false, cancelable=true で invalid イベントが発火すること");
    }

    @Test
    @DisplayName("form.checkValidity() による全コントロールの検証と invalid 発火")
    public void testFormCheckValidity() {
        HtmlElement form = new HtmlElement("form");
        HtmlElement input1 = new HtmlElement("input");
        input1.setId("f1");
        input1.setAttribute("type", "text");
        input1.setAttribute("required", "true");

        HtmlElement input2 = new HtmlElement("input");
        input2.setId("f2");
        input2.setAttribute("type", "email");
        input2.setAttribute("value", "valid@test.com");

        form.appendChild(input1);
        form.appendChild(input2);

        AtomicBoolean input1Invalid = new AtomicBoolean(false);
        AtomicBoolean input2Invalid = new AtomicBoolean(false);

        input1.addEventListener("invalid", ev -> input1Invalid.set(true));
        input2.addEventListener("invalid", ev -> input2Invalid.set(true));

        boolean formValid = form.checkValidity();
        assertFalse(formValid, "input1 が無効なため form.checkValidity は false");
        assertTrue(input1Invalid.get(), "input1 で invalid イベントが発火すること");
        assertFalse(input2Invalid.get(), "input2 は有効なので invalid は発火しないこと");
    }
}