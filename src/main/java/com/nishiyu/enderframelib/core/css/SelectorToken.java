package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SelectorToken {
    private static final Pattern NOT_SELECTOR_PATTERN = Pattern.compile(":not\\(([^)]+)\\)");
    private static final Pattern NTH_CHILD_PATTERN = Pattern.compile(":nth-child\\(([^)]+)\\)");
    private static final Pattern ATTR_SELECTOR_PATTERN = Pattern.compile("\\[([^]=^*$~|]+)(?:([^*$=~|]?=)[\\\"']?([^]\\\"']*)[\\\"']?)?]");
    private static final Pattern BRACKET_STRIP_PATTERN = Pattern.compile("\\[.*?]");

    public String rawSelector;
    public String tag = "*";
    public String id = null;
    public List<String> classes = new ArrayList<>();
    public Map<String, String> requiredAttrs = new HashMap<>();
    public Map<String, String> prefixAttrs = new HashMap<>();
    public Map<String, String> suffixAttrs = new HashMap<>();
    public Map<String, String> containsAttrs = new HashMap<>();
    public List<String> requiredExistsAttrs = new ArrayList<>();
    public List<SelectorToken> notSelectors = new ArrayList<>();

    public boolean isHover = false;
    public boolean parsedHover = false;
    public boolean parsedActive = false;
    public boolean parsedFocus = false;
    public boolean isChecked = false;
    public boolean isDisabled = false;
    public boolean isFirstChild = false;
    public boolean isLastChild = false;
    public boolean isEmpty = false;
    public int nthChild = -1;

    public SelectorToken(String sel) {
        this.rawSelector = sel;

        Matcher notMatcher = NOT_SELECTOR_PATTERN.matcher(sel);
        while (notMatcher.find()) {
            String notSel = notMatcher.group(1).trim();
            notSelectors.add(new SelectorToken(notSel));
        }
        sel = notMatcher.replaceAll("");

        if (sel.contains(":hover")) { parsedHover = true; sel = sel.replace(":hover", ""); }
        if (sel.contains(":active")) { parsedActive = true; sel = sel.replace(":active", ""); }
        if (sel.contains(":focus-visible")) { parsedFocus = true; sel = sel.replace(":focus-visible", ""); }
        if (sel.contains(":focus")) { parsedFocus = true; sel = sel.replace(":focus", ""); }
        if (sel.contains(":checked")) { isChecked = true; sel = sel.replace(":checked", ""); }
        if (sel.contains(":disabled")) { isDisabled = true; sel = sel.replace(":disabled", ""); }
        if (sel.contains(":first-child")) { isFirstChild = true; sel = sel.replace(":first-child", ""); }
        if (sel.contains(":last-child")) { isLastChild = true; sel = sel.replace(":last-child", ""); }
        if (sel.contains(":empty")) { isEmpty = true; sel = sel.replace(":empty", ""); }

        if (sel.contains(":nth-child")) {
            Matcher m = NTH_CHILD_PATTERN.matcher(sel);
            if (m.find()) {
                String arg = m.group(1).trim().toLowerCase();
                if ("odd".equals(arg) || "2n+1".equals(arg)) nthChild = -2;
                else if ("even".equals(arg) || "2n".equals(arg)) nthChild = -3;
                else try { nthChild = Integer.parseInt(arg); } catch (Exception ignored) {}
                sel = m.replaceAll("");
            }
        }

        Matcher attrMatcher = ATTR_SELECTOR_PATTERN.matcher(sel);
        while (attrMatcher.find()) {
            String attrKey = attrMatcher.group(1).trim();
            String op = attrMatcher.group(2);
            String attrVal = attrMatcher.group(3);
            if (op != null && attrVal != null) {
                switch (op) {
                    case "^=" -> prefixAttrs.put(attrKey, attrVal);
                    case "$=" -> suffixAttrs.put(attrKey, attrVal);
                    case "*=" -> containsAttrs.put(attrKey, attrVal);
                    default -> requiredAttrs.put(attrKey, attrVal);
                }
            } else requiredExistsAttrs.add(attrKey);
        }
        sel = BRACKET_STRIP_PATTERN.matcher(sel).replaceAll("");

        if (sel.contains("#")) {
            int hashIdx = sel.indexOf("#");
            String afterHash = sel.substring(hashIdx + 1);
            id = afterHash.split("\\.")[0];
            sel = sel.substring(0, hashIdx) + (afterHash.contains(".") ? afterHash.substring(afterHash.indexOf(".")) : "");
        }
        if (sel.contains(".")) {
            String[] cls = sel.split("\\.");
            tag = cls[0].isEmpty() ? "*" : cls[0];
            for (int i = 1; i < cls.length; i++) {
                if (!cls[i].isEmpty()) classes.add(cls[i]);
            }
        } else if (!sel.isEmpty()) {
            tag = sel.trim();
        }
    }

    public boolean matches(HtmlNode node) {
        for (SelectorToken notToken : notSelectors) {
            if (notToken.matches(node)) return false;
        }
        if (id != null && !id.equals(node.getId())) return false;
        if (!tag.equals("*") && !tag.equals(node.tag)) return false;
        for (String c : classes) {
            if (!node.hasClass(c)) return false;
        }
        if (isDisabled && !node.attrs.containsKey("disabled")) return false;

        // HtmlNodeから対話状態(Hover等)を切り離したため、静的属性のみチェックします
        if (isChecked && !node.attrs.containsKey("checked")) return false;

        if (isEmpty) {
            boolean hasContent = (node.text != null && !node.text.trim().isEmpty());
            if (!hasContent) {
                for (HtmlNode c : node.children) {
                    if (!c.isPseudoNode && (!c.tag.equals("#text") || (c.text != null && !c.text.trim().isEmpty()))) {
                        hasContent = true;
                        break;
                    }
                }
            }
            if (hasContent) return false;
        }

        for (String req : requiredExistsAttrs) {
            if (!node.attrs.containsKey(req)) return false;
        }
        for (Map.Entry<String, String> req : requiredAttrs.entrySet()) {
            if (!node.attrs.containsKey(req.getKey())) return false;
            if (req.getValue() != null && !req.getValue().equals(node.attrs.get(req.getKey()))) return false;
        }
        for (Map.Entry<String, String> req : prefixAttrs.entrySet()) {
            String actual = node.attrs.get(req.getKey());
            if (actual == null || !actual.startsWith(req.getValue())) return false;
        }
        for (Map.Entry<String, String> req : suffixAttrs.entrySet()) {
            String actual = node.attrs.get(req.getKey());
            if (actual == null || !actual.endsWith(req.getValue())) return false;
        }
        for (Map.Entry<String, String> req : containsAttrs.entrySet()) {
            String actual = node.attrs.get(req.getKey());
            if (actual == null || !actual.contains(req.getValue())) return false;
        }

        if ((nthChild != -1 || isFirstChild || isLastChild) && node.parent != null) {
            List<HtmlNode> elementSiblings = new ArrayList<>();
            for (HtmlNode child : node.parent.children) {
                if (!child.tag.startsWith("#") && !child.tag.equals("template") && !child.isPseudoNode) {
                    elementSiblings.add(child);
                }
            }
            int idx = elementSiblings.indexOf(node) + 1; // 1-based
            if (idx <= 0) return false;
            if (isFirstChild && idx != 1) return false;
            if (isLastChild && idx != elementSiblings.size()) return false;
            if (nthChild > 0 && idx != nthChild) return false;
            if (nthChild == -2 && (idx % 2 == 0)) return false; // odd
            if (nthChild == -3 && (idx % 2 != 0)) return false; // even
        }
        return true;
    }
}