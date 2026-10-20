package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import com.nishiyu.enderframelib.api.dom.ValidityState;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * HTML5 フォーム制約検証（Constraint Validation）エンジン。
 */
public final class ConstraintValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");

    private ConstraintValidator() {
    }

    public static record HtmlValidityState(
            boolean badInput,
            boolean customError,
            boolean patternMismatch,
            boolean rangeOverflow,
            boolean rangeUnderflow,
            boolean stepMismatch,
            boolean tooLong,
            boolean tooShort,
            boolean typeMismatch,
            boolean valueMissing,
            boolean valid
    ) implements ValidityState {
        @Override public boolean isBadInput() { return badInput; }
        @Override public boolean isCustomError() { return customError; }
        @Override public boolean isPatternMismatch() { return patternMismatch; }
        @Override public boolean isRangeOverflow() { return rangeOverflow; }
        @Override public boolean isRangeUnderflow() { return rangeUnderflow; }
        @Override public boolean isStepMismatch() { return stepMismatch; }
        @Override public boolean isTooLong() { return tooLong; }
        @Override public boolean isTooShort() { return tooShort; }
        @Override public boolean isTypeMismatch() { return typeMismatch; }
        @Override public boolean isValueMissing() { return valueMissing; }
        @Override public boolean isValid() { return valid; }
    }

    /** {@code readonly} 属性が当てはまる {@code <input>} の種類（HTML §4.10.5.3.4）。 */
    private static boolean supportsReadonly(String type) {
        return switch (type) {
            case "hidden", "range", "color", "checkbox", "radio", "file", "submit", "image", "reset", "button" -> false;
            default -> true;
        };
    }

    public static boolean willValidate(HtmlElement element) {
        if (element == null) return false;
        String tag = element.getTagName().toLowerCase(Locale.ROOT);
        if (!tag.equals("input") && !tag.equals("button") && !tag.equals("select") && !tag.equals("textarea")) {
            return false;
        }
        if (element.hasAttribute("disabled")) return false;

        if (tag.equals("input")) {
            String type = element.getAttribute("type");
            String lowerType = type != null ? type.toLowerCase(Locale.ROOT) : "text";
            if (lowerType.equals("hidden") || lowerType.equals("reset") || lowerType.equals("button")) {
                return false;
            }
            // readonly が効く種類（値を打ち込む入力欄）は、readonly だと制約検証の対象から外れる
            if (element.hasAttribute("readonly") && supportsReadonly(lowerType)) return false;
        } else if (tag.equals("textarea")) {
            if (element.hasAttribute("readonly")) return false;
        } else if (tag.equals("button")) {
            String type = element.getAttribute("type");
            String lowerType = type != null ? type.toLowerCase(Locale.ROOT) : "submit";
            if (lowerType.equals("reset") || lowerType.equals("button")) {
                return false;
            }
        }

        // datalist の子孫であれば対象外
        for (HtmlNode cur = element.parent; cur != null; cur = cur.parent) {
            if ("datalist".equalsIgnoreCase(cur.tag)) {
                return false;
            }
        }

        return true;
    }

    public static ValidityState computeValidity(HtmlElement element, @Nullable String customError) {
        boolean custom = customError != null && !customError.isEmpty();
        if (!willValidate(element)) {
            return new HtmlValidityState(false, custom, false, false, false, false, false, false, false, false, !custom);
        }

        boolean badInput = false;
        boolean patternMismatch = false;
        boolean rangeOverflow = false;
        boolean rangeUnderflow = false;
        boolean stepMismatch = false;
        boolean tooLong = false;
        boolean tooShort = false;
        boolean typeMismatch = false;
        boolean valueMissing = false;

        String tag = element.getTagName().toLowerCase(Locale.ROOT);
        String type = element.getAttribute("type");
        String lowerType = type != null ? type.toLowerCase(Locale.ROOT) : (tag.equals("input") ? "text" : tag);
        String val = getControlValue(element);
        boolean empty = val.isEmpty();

        // 1. required 検査 (valueMissing)
        if (element.hasAttribute("required")) {
            if ("checkbox".equals(lowerType)) {
                valueMissing = !element.hasAttribute("checked");
            } else if ("radio".equals(lowerType)) {
                valueMissing = !radioGroupHasChecked(element);
            } else if ("select".equals(tag)) {
                valueMissing = empty;
            } else {
                valueMissing = empty;
            }
        }

        // 2. 非空値に対する検証
        if (!empty) {
            if ("email".equals(lowerType)) {
                if (element.hasAttribute("multiple")) {
                    for (String part : val.split(",")) {
                        if (!EMAIL_PATTERN.matcher(part.trim()).matches()) {
                            typeMismatch = true;
                            break;
                        }
                    }
                } else {
                    typeMismatch = !EMAIL_PATTERN.matcher(val).matches();
                }
            } else if ("url".equals(lowerType)) {
                try {
                    URI uri = new URI(val);
                    if (uri.getScheme() == null || uri.getScheme().isBlank()) {
                        typeMismatch = true;
                    }
                } catch (URISyntaxException ignored) {
                    typeMismatch = true;
                }
            } else if ("color".equals(lowerType)) {
                typeMismatch = !val.matches("^#[0-9a-fA-F]{6}$");
            }

            // 数値系属性 (number, range 等)
            if (isNumericInput(lowerType)) {
                Double num = parseDouble(val);
                if (num == null) {
                    badInput = true;
                } else {
                    String minStr = element.getAttribute("min");
                    Double min = parseDouble(minStr);
                    if (min != null && num < min) {
                        rangeUnderflow = true;
                    }

                    String maxStr = element.getAttribute("max");
                    Double max = parseDouble(maxStr);
                    if (max != null && num > max) {
                        rangeOverflow = true;
                    }

                    String stepStr = element.getAttribute("step");
                    if (stepStr != null && !"any".equalsIgnoreCase(stepStr.trim())) {
                        Double step = parseDouble(stepStr);
                        if (step != null && step > 0) {
                            double base = min != null ? min : 0.0;
                            double diff = Math.abs((num - base) / step);
                            if (Math.abs(diff - Math.round(diff)) > 1e-7) {
                                stepMismatch = true;
                            }
                        }
                    }
                }
            }

            // pattern 属性
            if (element.hasAttribute("pattern") && supportsPattern(tag, lowerType)) {
                String patternStr = element.getAttribute("pattern");
                if (patternStr != null) {
                    try {
                        Pattern p = Pattern.compile("^(?:" + patternStr + ")$");
                        patternMismatch = !p.matcher(val).matches();
                    } catch (PatternSyntaxException ignored) {
                    }
                }
            }

            // minlength / maxlength
            if (supportsPattern(tag, lowerType)) {
                int minLength = parseInt(element.getAttribute("minlength"), -1);
                int maxLength = parseInt(element.getAttribute("maxlength"), -1);
                if (minLength >= 0 && val.length() < minLength) {
                    tooShort = true;
                }
                if (maxLength >= 0 && val.length() > maxLength) {
                    tooLong = true;
                }
            }
        }

        boolean valid = !(badInput || custom || patternMismatch || rangeOverflow
                || rangeUnderflow || stepMismatch || tooLong || tooShort
                || typeMismatch || valueMissing);

        return new HtmlValidityState(badInput, custom, patternMismatch, rangeOverflow,
                rangeUnderflow, stepMismatch, tooLong, tooShort, typeMismatch, valueMissing, valid);
    }

    public static String getValidationMessage(HtmlElement element, ValidityState state, @Nullable String customError) {
        if (state.isValid()) return "";
        if (customError != null && !customError.isEmpty()) return customError;
        if (state.isValueMissing()) return "Please fill out this field.";
        if (state.isTypeMismatch()) return "Please enter a valid value.";
        if (state.isPatternMismatch()) return "Please match the requested format.";
        if (state.isTooShort()) return "Please lengthen this text.";
        if (state.isTooLong()) return "Please shorten this text.";
        if (state.isRangeUnderflow()) return "Value must be greater than or equal to minimum.";
        if (state.isRangeOverflow()) return "Value must be less than or equal to maximum.";
        if (state.isStepMismatch()) return "Please enter a valid value.";
        if (state.isBadInput()) return "Please enter a number.";
        return "Validation failed.";
    }

    public static boolean radioGroupHasChecked(HtmlElement element) {
        String name = element.getAttribute("name");
        if (name == null || name.isEmpty()) {
            return element.hasAttribute("checked");
        }
        HtmlNode root = getRoot(element);
        List<HtmlElement> radios = new ArrayList<>();
        collectRadiosByName(root, name, radios);
        for (HtmlElement r : radios) {
            if (r.hasAttribute("checked")) {
                return true;
            }
        }
        return false;
    }

    private static void collectRadiosByName(HtmlNode node, String name, List<HtmlElement> out) {
        if (node instanceof HtmlElement el && "input".equalsIgnoreCase(el.getTagName())) {
            if ("radio".equalsIgnoreCase(el.getAttribute("type")) && name.equals(el.getAttribute("name"))) {
                out.add(el);
            }
        }
        for (HtmlNode child : node.children) {
            collectRadiosByName(child, name, out);
        }
    }

    public static String getControlValue(HtmlElement element) {
        String tag = element.getTagName().toLowerCase(Locale.ROOT);
        if ("textarea".equals(tag)) {
            String val = element.getAttribute("value");
            return val != null ? val : (element.getTextContent() != null ? element.getTextContent() : "");
        }
        String val = element.getAttribute("value");
        return val != null ? val : "";
    }

    private static boolean isNumericInput(String type) {
        return "number".equals(type) || "range".equals(type);
    }

    private static boolean supportsPattern(String tag, String type) {
        if ("textarea".equals(tag)) return true;
        if (!"input".equals(tag)) return false;
        return switch (type) {
            case "text", "search", "url", "tel", "email", "password" -> true;
            default -> false;
        };
    }

    private static Double parseDouble(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(String str, int fallback) {
        if (str == null || str.trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static HtmlNode getRoot(HtmlNode node) {
        HtmlNode cur = node;
        while (cur.parent != null) cur = cur.parent;
        return cur;
    }
}