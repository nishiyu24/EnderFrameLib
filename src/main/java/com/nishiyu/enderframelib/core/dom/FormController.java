package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.api.dom.DOMException;
import com.nishiyu.enderframelib.api.dom.FormData;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * HTMLFormElement の送信・リセット・FormData 構築・関連コントロール解決を担うコントローラ。
 */
public final class FormController {

    private FormController() {
    }

    @Nullable
    public static HtmlElement getFormOwner(HtmlElement control) {
        if (control == null) return null;
        String formId = control.getAttribute("form");
        if (formId != null && !formId.isEmpty()) {
            com.nishiyu.enderframelib.api.dom.Document ownerDoc = control.getOwnerDocument();
            if (ownerDoc instanceof HtmlDocument doc) {
                HtmlElement form = (HtmlElement) doc.getElementById(formId);
                if (form != null && "form".equalsIgnoreCase(form.getTagName())) {
                    return form;
                }
            } else {
                HtmlNode root = getRoot(control);
                if (root instanceof HtmlDocument doc) {
                    HtmlElement form = (HtmlElement) doc.getElementById(formId);
                    if (form != null && "form".equalsIgnoreCase(form.getTagName())) {
                        return form;
                    }
                } else {
                    HtmlNode found = root.getElementById(formId);
                    if (found instanceof HtmlElement form && "form".equalsIgnoreCase(form.getTagName())) {
                        return form;
                    }
                }
            }
        }
        for (HtmlNode cur = control.parent; cur != null; cur = cur.parent) {
            if (cur instanceof HtmlElement el && "form".equalsIgnoreCase(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    public static List<HtmlElement> getFormControls(HtmlElement form) {
        if (form == null || !"form".equalsIgnoreCase(form.getTagName())) {
            return Collections.emptyList();
        }
        List<HtmlElement> controls = new ArrayList<>();
        HtmlNode root = getRoot(form);
        collectControls(root, form, controls);
        return controls;
    }

    private static void collectControls(HtmlNode node, HtmlElement targetForm, List<HtmlElement> out) {
        if (node instanceof HtmlElement el && isFormControl(el)) {
            HtmlElement owner = getFormOwner(el);
            if (owner == targetForm) {
                out.add(el);
            }
        }
        for (HtmlNode child : node.children) {
            collectControls(child, targetForm, out);
        }
    }

    public static boolean checkFormValidity(HtmlElement form) {
        List<HtmlElement> controls = getFormControls(form);
        boolean allValid = true;
        for (HtmlElement control : controls) {
            if (!control.checkValidity()) {
                allValid = false;
            }
        }
        return allValid;
    }

    public static boolean reportFormValidity(HtmlElement form) {
        List<HtmlElement> controls = getFormControls(form);
        boolean allValid = true;
        for (HtmlElement control : controls) {
            if (!control.reportValidity()) {
                allValid = false;
            }
        }
        return allValid;
    }

    public static void requestSubmit(HtmlElement form, @Nullable HtmlElement submitter) {
        if (form == null || !"form".equalsIgnoreCase(form.getTagName())) return;

        if (submitter != null) {
            HtmlElement owner = getFormOwner(submitter);
            if (owner != form) {
                throw new DOMException("The submitter is not associated with this form.", "NotFoundError", DOMException.NOT_FOUND_ERR);
            }
        }

        // 1. 各コントロールの制約検証
        List<HtmlElement> controls = getFormControls(form);
        for (HtmlElement control : controls) {
            if (!control.checkValidity()) {
                control.focus();
                return; // 制約違反がある場合は送信中止
            }
        }

        // 2. submit イベントのディスパッチ
        CoreEvent.CoreSubmitEvent submitEvent = new CoreEvent.CoreSubmitEvent(submitter);
        form.dispatchEvent(submitEvent);

        if (submitEvent.isDefaultPrevented()) {
            return;
        }

        // 3. FormData の構築と formdata イベントのディスパッチ
        FormData formData = constructFormData(form, submitter);
        CoreEvent.CoreFormDataEvent formDataEvent = new CoreEvent.CoreFormDataEvent(formData);
        form.dispatchEvent(formDataEvent);
    }

    public static void resetForm(HtmlElement form) {
        if (form == null || !"form".equalsIgnoreCase(form.getTagName())) return;

        CoreEvent resetEvent = new CoreEvent("reset", true, true);
        form.dispatchEvent(resetEvent);

        if (resetEvent.isDefaultPrevented()) {
            return;
        }

        List<HtmlElement> controls = getFormControls(form);
        for (HtmlElement control : controls) {
            String tag = control.getTagName().toLowerCase(Locale.ROOT);
            if ("input".equals(tag)) {
                String type = control.getAttribute("type");
                String lowerType = type != null ? type.toLowerCase(Locale.ROOT) : "text";
                if ("checkbox".equals(lowerType) || "radio".equals(lowerType)) {
                    if (control.isDefaultChecked()) {
                        control.setAttribute("checked", "true");
                    } else {
                        control.removeAttribute("checked");
                    }
                } else {
                    String initialVal = control.getDefaultValue();
                    if (!initialVal.isEmpty()) {
                        control.setAttribute("value", initialVal);
                    } else {
                        control.removeAttribute("value");
                    }
                }
            } else if ("textarea".equals(tag)) {
                String initialVal = control.getDefaultValue();
                if (!initialVal.isEmpty()) {
                    control.setAttribute("value", initialVal);
                } else {
                    control.removeAttribute("value");
                }
            }
            control.dispatchEvent(new CoreEvent.CoreUIEvent("input", true, false, 0));
            control.dispatchEvent(new CoreEvent.CoreUIEvent("change", true, false, 0));
        }
    }

    public static FormData constructFormData(HtmlElement form, @Nullable HtmlElement submitter) {
        FormData data = new FormData();
        List<HtmlElement> controls = getFormControls(form);
        for (HtmlElement control : controls) {
            if (control.hasAttribute("disabled")) continue;
            String name = control.getAttribute("name");
            if (name == null || name.isEmpty()) continue;

            String tag = control.getTagName().toLowerCase(Locale.ROOT);
            if ("input".equals(tag)) {
                String type = control.getAttribute("type");
                String lowerType = type != null ? type.toLowerCase(Locale.ROOT) : "text";
                if ("checkbox".equals(lowerType) || "radio".equals(lowerType)) {
                    if (control.hasAttribute("checked")) {
                        String val = control.getAttribute("value");
                        data.append(name, val != null ? val : "on");
                    }
                } else if ("submit".equals(lowerType) || "reset".equals(lowerType) || "button".equals(lowerType)) {
                    // 通常のボタンスキップ（submitter のみ追加）
                    if (control == submitter) {
                        String val = control.getAttribute("value");
                        data.append(name, val != null ? val : "");
                    }
                } else {
                    String val = control.getAttribute("value");
                    data.append(name, val != null ? val : "");
                }
            } else if ("button".equals(tag)) {
                if (control == submitter) {
                    String val = control.getAttribute("value");
                    data.append(name, val != null ? val : "");
                }
            } else if ("textarea".equals(tag)) {
                String val = control.getAttribute("value");
                if (val == null) val = control.getTextContent();
                data.append(name, val != null ? val : "");
            }
        }
        return data;
    }

    private static boolean isFormControl(HtmlElement node) {
        String tag = node.getTagName().toLowerCase(Locale.ROOT);
        return tag.equals("input") || tag.equals("button") || tag.equals("select") || tag.equals("textarea");
    }

    private static HtmlNode getRoot(HtmlNode node) {
        HtmlNode cur = node;
        while (cur.parent != null) cur = cur.parent;
        return cur;
    }
}