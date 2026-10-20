package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.TemplateEngine;
import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReactiveStateStore {

    private static final Pattern FOR_EXPR_PATTERN = Pattern.compile("^([a-zA-Z0-9_]+)\\s+in\\s+([a-zA-Z0-9_.-]+)$");
    private static final Pattern COMPARE_EXPR_PATTERN = Pattern.compile("^([a-zA-Z0-9_.-]+)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");

    private static final String DEP_ALWAYS = TemplateDeps.ALWAYS;

    private final String sessionId;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> lists = new ConcurrentHashMap<>();
    private final Map<String, List<HtmlNode>> listTemplates = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> lastRenderedLists = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> lastRawBoundLists = new ConcurrentHashMap<>();
    private final Map<String, String> originalShowDisplay = new ConcurrentHashMap<>();

    private final Map<HtmlNode, String> originalNodeTexts = new IdentityHashMap<>();
    private final Map<HtmlNode, Map<String, String>> originalNodeAttrs = new IdentityHashMap<>();

    private final Map<String, Supplier<?>> boundSuppliers = new ConcurrentHashMap<>();
    private final Map<String, Supplier<List<Map<String, String>>>> boundListSuppliers = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> watchers = new ConcurrentHashMap<>();

    private final Set<String> dirtyKeys = ConcurrentHashMap.newKeySet();
    private boolean dirty = false;

    private final TemplateDeps templateDeps = new TemplateDeps();
    private final Set<String> loggedCallbackFailures = ConcurrentHashMap.newKeySet();

    public ReactiveStateStore(String sessionId) {
        this.sessionId = sessionId;
        this.dirty = true;
        this.dirtyKeys.add(DEP_ALWAYS);
    }

    public ReactiveStateStore set(String key, Object value) {
        return setInternal(key, value, true);
    }

    private void warnCallbackFailureOnce(String kind, String key, Throwable t) {
        if (loggedCallbackFailures.add(kind + ":" + key)) {
            EnderFrameLib.LOGGER.warn(
                    "[ReactiveStateStore] session={} の {} '{}' が例外を投げました。"
                            + "このバインディングは以降更新されない可能性があります（以後この組み合わせでは警告しません）",
                    sessionId, kind, key, t);
        }
    }

    private ReactiveStateStore setInternal(String key, Object value, boolean notifyWatchers) {
        String strVal = value != null ? String.valueOf(value) : "";
        String prev = values.put(key, strVal);
        if (!Objects.equals(prev, strVal)) {
            dirty = true;
            dirtyKeys.add(key);
            if (notifyWatchers) {
                List<Consumer<String>> list = watchers.get(key);
                if (list != null) {
                    for (Consumer<String> listener : list) {
                        try { listener.accept(strVal); } catch (Exception e) { warnCallbackFailureOnce("watcher", key, e); }
                    }
                }
            }
            syncToDom();
        }
        return this;
    }

    public ReactiveStateStore bind(String key, Supplier<?> supplier) {
        if (key != null && supplier != null) {
            boundSuppliers.put(key, supplier);
            try {
                setInternal(key, supplier.get(), false);
            } catch (Exception e) { warnCallbackFailureOnce("bind supplier", key, e); }
        }
        return this;
    }

    public ReactiveStateStore bindList(String key, Supplier<List<Map<String, String>>> listSupplier) {
        if (key != null && listSupplier != null) {
            boundListSuppliers.put(key, listSupplier);
            try {
                setList(key, listSupplier.get());
            } catch (Exception e) { warnCallbackFailureOnce("bindList supplier", key, e); }
        }
        return this;
    }

    public ReactiveStateStore watch(String key, Consumer<String> listener) {
        if (key != null && listener != null) {
            watchers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
        }
        return this;
    }

    public void clearBindings() {
        boundSuppliers.clear();
        boundListSuppliers.clear();
        watchers.clear();
        originalNodeTexts.clear();
        originalNodeAttrs.clear();
    }

    public void notifyElementModelChanged(String elementId, String newValue) {
        HtmlNode vRoot = ClientWebManager.getRootNode(sessionId);
        if (vRoot == null || elementId == null) return;
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        HtmlNode node = vRoot.getNodeById(baseId);
        if (node != null && node.attrs.containsKey("data-model")) {
            String modelKey = node.attrs.get("data-model").trim();
            if (!modelKey.isEmpty()) {
                setInternal(modelKey, newValue, true);
            }
        }
    }

    public String get(String key) { return values.getOrDefault(key, ""); }

    public int getInt(String key, int defaultVal) {
        try {
            return Integer.parseInt(values.getOrDefault(key, String.valueOf(defaultVal)).trim());
        } catch (NumberFormatException e) { return defaultVal; }
    }

    public float getFloat(String key, float defaultVal) {
        try {
            return Float.parseFloat(values.getOrDefault(key, String.valueOf(defaultVal)).trim());
        } catch (NumberFormatException e) { return defaultVal; }
    }

    public boolean getBoolean(String key) {
        String v = values.getOrDefault(key, "false").trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    public ReactiveStateStore setList(String key, List<Map<String, String>> items) {
        List<Map<String, String>> copy = copyListItems(items);
        List<Map<String, String>> prev = lists.put(key, copy);
        if (!Objects.equals(prev, copy)) {
            dirty = true;
            dirtyKeys.add(key);
            syncToDom();
        }
        return this;
    }

    private static List<Map<String, String>> copyListItems(List<Map<String, String>> items) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        List<Map<String, String>> copy = new ArrayList<>(items.size());
        for (Map<String, String> item : items) {
            copy.add(new HashMap<>(item));
        }
        return copy;
    }

    public void forceSync() {
        dirty = true;
        dirtyKeys.add(DEP_ALWAYS);
        templateDeps.clear();
        listTemplates.clear();
        lastRenderedLists.clear();
        lastRawBoundLists.clear();
        originalNodeTexts.clear();
        originalNodeAttrs.clear();
        syncToDom();
    }

    private void pollBoundSuppliers() {
        for (Map.Entry<String, Supplier<?>> entry : boundSuppliers.entrySet()) {
            try {
                Object val = entry.getValue().get();
                String strVal = val != null ? String.valueOf(val) : "";
                String prev = values.put(entry.getKey(), strVal);
                if (!Objects.equals(prev, strVal)) {
                    dirty = true;
                    dirtyKeys.add(entry.getKey());
                }
            } catch (Exception e) { warnCallbackFailureOnce("bind supplier", entry.getKey(), e); }
        }
        for (Map.Entry<String, Supplier<List<Map<String, String>>>> entry : boundListSuppliers.entrySet()) {
            try {
                List<Map<String, String>> raw = entry.getValue().get();
                List<Map<String, String>> prevRaw = lastRawBoundLists.get(entry.getKey());
                if (raw != null && raw == prevRaw) continue;
                if (raw == null && prevRaw == null) continue;
                lastRawBoundLists.put(entry.getKey(), raw);

                List<Map<String, String>> currentList = lists.get(entry.getKey());
                if (currentList != null && raw != null && currentList.size() == raw.size() && currentList.equals(raw)) continue;

                List<Map<String, String>> copy = copyListItems(raw);
                List<Map<String, String>> prev = lists.put(entry.getKey(), copy);
                if (!Objects.equals(prev, copy)) {
                    dirty = true;
                    dirtyKeys.add(entry.getKey());
                }
            } catch (Exception e) { warnCallbackFailureOnce("bindList supplier", entry.getKey(), e); }
        }
    }

    public void syncToDom() {
        pollBoundSuppliers();
        if (!dirty) return;
        HtmlNode root = ClientWebManager.getRootNode(sessionId);
        if (root == null) return;

        Set<String> changedKeys = Set.copyOf(dirtyKeys);
        dirty = false;
        dirtyKeys.clear();

        Set<String> scopeKeys = null;
        if (!changedKeys.contains(DEP_ALWAYS)) {
            if (TemplateDeps.canSkip(templateDeps.index(root), changedKeys)) return;
            scopeKeys = changedKeys;
        }

        if (applyRecursive(root, values, scopeKeys)) {
            ClientWebManager.requestRender(sessionId);
        }
    }

    private boolean applyRecursive(HtmlNode vNode, Map<String, String> scopeData, Set<String> changedKeys) {
        if (vNode == null) return false;
        if (changedKeys != null && TemplateDeps.canSkip(templateDeps.lookup(vNode), changedKeys)) return false;

        boolean changed = ClientLocalEnvProvider.applyI18nAttributes(vNode);

        if (applyTextPlaceholders(vNode, scopeData)) changed = true;
        if (applyAttributePlaceholders(vNode, scopeData)) changed = true;
        if (applyDataModel(vNode, scopeData)) changed = true;
        if (applyDataBind(vNode, scopeData)) changed = true;
        if (applyConditionalVisibility(vNode, scopeData)) changed = true;
        if (applyDataClass(vNode, scopeData)) changed = true;

        return handleListIterationOrChildren(vNode, scopeData, changed, changedKeys);
    }

    private boolean applyTextPlaceholders(HtmlNode vNode, Map<String, String> scopeData) {
        if (vNode.text == null && !originalNodeTexts.containsKey(vNode)) return false;
        String rawText = originalNodeTexts.computeIfAbsent(vNode, n -> n.text != null ? n.text : "");
        if (rawText.contains("{{") && !rawText.contains("{{@")) {
            String resolved = TemplateEngine.resolve(rawText, scopeData, null);
            if (!Objects.equals(resolved, vNode.text)) {
                vNode.text = resolved;
                return true;
            }
        }
        return false;
    }

    private boolean applyAttributePlaceholders(HtmlNode vNode, Map<String, String> scopeData) {
        if (vNode.attrs.isEmpty() && !originalNodeAttrs.containsKey(vNode)) return false;
        Map<String, String> origAttrs = originalNodeAttrs.computeIfAbsent(vNode, n -> new HashMap<>(n.attrs));
        boolean changed = false;
        for (Map.Entry<String, String> entry : new ArrayList<>(origAttrs.entrySet())) {
            String k = entry.getKey();
            String rawVal = entry.getValue();
            if (rawVal != null && rawVal.contains("{{") && !rawVal.contains("{{@")) {
                String resolved = TemplateEngine.resolve(rawVal, scopeData, null);
                if (!Objects.equals(resolved, vNode.attrs.get(k))) {
                    DomUtils.updateAttribute(vNode, k, resolved, false);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean applyDataModel(HtmlNode vNode, Map<String, String> scopeData) {
        if (!vNode.attrs.containsKey("data-model")) return false;
        String modelKey = vNode.attrs.get("data-model").trim();
        if (scopeData.containsKey(modelKey)) {
            String modelVal = scopeData.getOrDefault(modelKey, "");
            String type = vNode.attrs.getOrDefault("type", "text");
            if ("checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type)) {
                boolean shouldCheck = "true".equalsIgnoreCase(modelVal) || "1".equals(modelVal);
                boolean isCurrentlyChecked = vNode.attrs.containsKey("checked");
                if (shouldCheck != isCurrentlyChecked) {
                    DomUtils.updateAttribute(vNode, "checked", shouldCheck ? "true" : null, !shouldCheck);
                    return true;
                }
            } else if (!Objects.equals(vNode.attrs.get("value"), modelVal)) {
                DomUtils.updateAttribute(vNode, "value", modelVal, false);
                return true;
            }
        } else {
            String type = vNode.attrs.getOrDefault("type", "text");
            if ("checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type)) {
                values.put(modelKey, Boolean.toString(vNode.attrs.containsKey("checked")));
            } else {
                values.put(modelKey, vNode.attrs.getOrDefault("value", vNode.getTextContent().trim()));
            }
        }
        return false;
    }

    private boolean applyDataBind(HtmlNode vNode, Map<String, String> scopeData) {
        if (!vNode.attrs.containsKey("data-bind")) return false;
        boolean changed = false;
        for (String part : vNode.attrs.get("data-bind").split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            String target = "text";
            String key = part;
            int colon = part.indexOf(':');
            if (colon > 0) {
                target = part.substring(0, colon).trim();
                key = part.substring(colon + 1).trim();
            }
            if (scopeData.containsKey(key)) {
                String val = scopeData.get(key);
                if ("text".equalsIgnoreCase(target)) {
                    if (!Objects.equals(vNode.getTextContent(), val)) {
                        vNode.setTextContent(val);
                        changed = true;
                    }
                } else if (target.startsWith("style.")) {
                    String prop = target.substring(6).trim();
                    String prevStyle = vNode.attrs.getOrDefault("style", "");
                    vNode.setStyleProperty(prop, val);
                    if (!Objects.equals(prevStyle, vNode.attrs.getOrDefault("style", ""))) {
                        changed = true;
                    }
                } else if (!Objects.equals(vNode.attrs.get(target), val)) {
                    DomUtils.updateAttribute(vNode, target, val, false);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean applyConditionalVisibility(HtmlNode vNode, Map<String, String> scopeData) {
        boolean changed = false;
        String ifExpr = vNode.attrs.getOrDefault("data-if", vNode.attrs.get("mc-if"));
        if (ifExpr != null && !ifExpr.isBlank()) {
            Boolean evalResult = evaluateCondition(ifExpr.trim(), scopeData);
            if (evalResult != null) {
                boolean currentlyHidden = vNode.attrs.containsKey("hidden");
                boolean shouldBeHidden = !evalResult;
                if (currentlyHidden != shouldBeHidden) {
                    DomUtils.updateAttribute(vNode, "hidden", shouldBeHidden ? "true" : null, !shouldBeHidden);
                    changed = true;
                }
            }
        }
        if (vNode.attrs.containsKey("data-show")) {
            Boolean visibleObj = evaluateCondition(vNode.attrs.get("data-show").trim(), scopeData);
            if (visibleObj != null) {
                String origDisp = originalShowDisplay.computeIfAbsent(vNode.getId(), id -> vNode.attrs.getOrDefault("data-orig-display", "flex"));
                String targetDisp = visibleObj ? origDisp : "none";
                String curStyle = vNode.attrs.getOrDefault("style", "");
                if (!curStyle.contains("display: " + targetDisp)) {
                    vNode.setStyleProperty("display", targetDisp);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean applyDataClass(HtmlNode vNode, Map<String, String> scopeData) {
        if (!vNode.attrs.containsKey("data-class")) return false;
        boolean changed = false;
        for (String item : vNode.attrs.get("data-class").split(",")) {
            int colon = item.indexOf(':');
            if (colon > 0) {
                String className = item.substring(0, colon).trim();
                String cond = item.substring(colon + 1).trim();
                Boolean shouldHaveObj = evaluateCondition(cond, scopeData);
                if (shouldHaveObj != null) {
                    if (shouldHaveObj && !vNode.hasClass(className)) {
                        vNode.addClass(className);
                        changed = true;
                    } else if (!shouldHaveObj && vNode.hasClass(className)) {
                        vNode.removeClass(className);
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private boolean handleListIterationOrChildren(HtmlNode vNode, Map<String, String> scopeData,
                                                  boolean changedSoFar, Set<String> changedKeys) {
        String listKey = null;
        String itemAlias = null;
        if (vNode.attrs.containsKey("data-list")) {
            listKey = vNode.attrs.get("data-list").trim();
        } else {
            String forAttr = vNode.attrs.getOrDefault("data-for", vNode.attrs.get("mc-for"));
            if (forAttr != null && !forAttr.isBlank()) {
                Matcher fm = FOR_EXPR_PATTERN.matcher(forAttr.trim());
                if (fm.matches()) {
                    itemAlias = fm.group(1).trim();
                    listKey = fm.group(2).trim();
                } else {
                    listKey = forAttr.trim();
                }
            }
        }

        if (listKey != null && !listKey.isEmpty()) {
            if (lists.containsKey(listKey)) {
                List<Map<String, String>> itemDataList = lists.get(listKey);
                List<Map<String, String>> lastList = lastRenderedLists.get(vNode.getId());
                if (Objects.equals(itemDataList, lastList)) {
                    return changedSoFar;
                }

                List<HtmlNode> tmplNodes = listTemplates.computeIfAbsent(vNode.getId(), id -> extractTemplateChildren(vNode));
                if (!tmplNodes.isEmpty()) {
                    Map<String, HtmlNode> existingKeyedNodes = new LinkedHashMap<>();
                    List<HtmlNode> nonKeyedNodes = new ArrayList<>();
                    for (HtmlNode child : vNode.children) {
                        if ("template".equalsIgnoreCase(child.tag)) continue;
                        String rowKey = child.attrs.get("data-row-key");
                        if (rowKey != null && !rowKey.isEmpty()) {
                            existingKeyedNodes.put(rowKey, child);
                        } else {
                            nonKeyedNodes.add(child);
                        }
                    }

                    List<HtmlNode> newChildren = new ArrayList<>();
                    for (HtmlNode child : vNode.children) {
                        if ("template".equalsIgnoreCase(child.tag)) {
                            newChildren.add(child);
                        }
                    }

                    int idx = 0;
                    for (Map<String, String> itemMap : itemDataList) {
                        Map<String, String> merged = new HashMap<>(scopeData);
                        merged.putAll(itemMap);
                        if (itemAlias != null && !itemAlias.isEmpty()) {
                            for (Map.Entry<String, String> ie : itemMap.entrySet()) {
                                merged.put(itemAlias + "." + ie.getKey(), ie.getValue());
                            }
                        }
                        merged.put("_index", String.valueOf(idx));

                        String rawKey = itemMap.getOrDefault("data-key", itemMap.getOrDefault("id", itemMap.get("key")));
                        String key = (rawKey != null && !rawKey.isEmpty()) ? rawKey : String.valueOf(idx);

                        for (int tIdx = 0; tIdx < tmplNodes.size(); tIdx++) {
                            HtmlNode tmpl = tmplNodes.get(tIdx);
                            String itemSubKey = key + "_" + tIdx;
                            HtmlNode instance = existingKeyedNodes.remove(itemSubKey);
                            if (instance == null && !nonKeyedNodes.isEmpty()) {
                                instance = nonKeyedNodes.remove(0);
                            }

                            if (instance == null) {
                                instance = tmpl.cloneNode(true);
                                uniquifyIds(instance, idx, rawKey);
                                instance.attrs.put("data-row-key", itemSubKey);
                            }

                            applyRecursive(instance, merged, null);
                            instance.parent = vNode;
                            newChildren.add(instance);
                        }
                        idx++;
                    }

                    for (HtmlNode leftover : existingKeyedNodes.values()) leftover.parent = null;
                    for (HtmlNode leftover : nonKeyedNodes) leftover.parent = null;

                    vNode.children.clear();
                    vNode.children.addAll(newChildren);
                    lastRenderedLists.put(vNode.getId(), new ArrayList<>(itemDataList));
                    return true;
                }
            }
            return changedSoFar;
        }

        boolean changed = changedSoFar;
        int vSize = vNode.children.size();
        for (int i = 0; i < vSize; i++) {
            HtmlNode vChild = vNode.children.get(i);
            if (applyRecursive(vChild, scopeData, changedKeys)) {
                changed = true;
            }
        }
        return changed;
    }

    public static List<HtmlNode> extractTemplateChildren(HtmlNode src) {
        List<HtmlNode> extracted = new ArrayList<>();
        for (HtmlNode c : src.children) {
            if ("template".equalsIgnoreCase(c.tag)) {
                for (HtmlNode tc : c.children) extracted.add(tc.cloneNode(true));
            }
        }
        if (extracted.isEmpty()) {
            for (HtmlNode c : src.children) {
                extracted.add(c.cloneNode(true));
            }
        }
        return extracted;
    }

    public static Boolean evaluateCondition(String expr, Map<String, String> scopeData) {
        if (expr == null || expr.isEmpty()) return null;
        Matcher cm = COMPARE_EXPR_PATTERN.matcher(expr);
        if (cm.matches()) {
            String key = cm.group(1).trim();
            String op = cm.group(2).trim();
            String rhsRaw = cm.group(3).trim().replaceAll("^['\"]+|['\"]+$", "");
            if (!scopeData.containsKey(key)) return null;
            String lhsRaw = scopeData.getOrDefault(key, "");

            try {
                double lhsNum = Double.parseDouble(lhsRaw);
                double rhsNum = Double.parseDouble(rhsRaw);
                return switch (op) {
                    case "==" -> Math.abs(lhsNum - rhsNum) < 1e-6;
                    case "!=" -> Math.abs(lhsNum - rhsNum) >= 1e-6;
                    case ">" -> lhsNum > rhsNum;
                    case ">=" -> lhsNum >= rhsNum;
                    case "<" -> lhsNum < rhsNum;
                    case "<=" -> lhsNum <= rhsNum;
                    default -> false;
                };
            } catch (NumberFormatException ignored) {
                int cmp = lhsRaw.compareTo(rhsRaw);
                return switch (op) {
                    case "==" -> lhsRaw.equals(rhsRaw);
                    case "!=" -> !lhsRaw.equals(rhsRaw);
                    case ">" -> cmp > 0;
                    case ">=" -> cmp >= 0;
                    case "<" -> cmp < 0;
                    case "<=" -> cmp <= 0;
                    default -> false;
                };
            }
        }

        boolean invert = expr.startsWith("!");
        String key = invert ? expr.substring(1).trim() : expr;
        if (!scopeData.containsKey(key)) return null;
        String val = scopeData.getOrDefault(key, "");
        boolean truthy = !val.isEmpty() && !"false".equalsIgnoreCase(val) && !"0".equals(val);
        return invert != truthy;
    }

    private static void uniquifyIds(HtmlNode node, int index, String rowKey) {
        if (!node.getId().isEmpty()) {
            String suffix = (rowKey != null && !rowKey.isEmpty()) ? "_k_" + rowKey : "_row_" + index;
            node.setId(node.getId() + suffix);
        }
        for (HtmlNode child : node.children) uniquifyIds(child, index, rowKey);
    }

    public static final class TemplateDeps {

        private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(?!@)([^}]+)}}");
        private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_.-]*");

        public static final String ALWAYS = "*";

        private final Map<HtmlNode, Set<String>> subtreeDeps = new IdentityHashMap<>();
        private HtmlNode indexedRoot = null;

        public Set<String> index(HtmlNode root) {
            if (root == null) return Set.of(ALWAYS);
            if (indexedRoot != root) {
                subtreeDeps.clear();
                indexedRoot = root;
                build(root);
            }
            return depsOf(root);
        }

        public Set<String> lookup(HtmlNode node) {
            return node != null ? subtreeDeps.get(node) : null;
        }

        public void clear() {
            subtreeDeps.clear();
            indexedRoot = null;
        }

        public static boolean canSkip(Set<String> deps, Set<String> changedKeys) {
            return deps != null
                    && !deps.contains(ALWAYS)
                    && Collections.disjoint(deps, changedKeys);
        }

        private Set<String> depsOf(HtmlNode node) {
            Set<String> deps = subtreeDeps.get(node);
            return deps != null ? deps : Set.of(ALWAYS);
        }

        private Set<String> build(HtmlNode node) {
            Set<String> deps = new HashSet<>();
            collectOwnDeps(node, deps);
            for (HtmlNode child : node.children) {
                deps.addAll(build(child));
            }
            Set<String> frozen = Set.copyOf(deps);
            subtreeDeps.put(node, frozen);
            return frozen;
        }

        static void collectOwnDeps(HtmlNode node, Set<String> out) {
            addPlaceholderDeps(node.text, out);

            for (Map.Entry<String, String> entry : node.attrs.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                addPlaceholderDeps(value, out);
                if (value == null) continue;

                switch (name) {
                    case "data-i18n", "data-i18n-placeholder", "data-i18n-title" -> out.add(ALWAYS);
                    case "data-model", "data-list" -> addKey(value.trim(), out);
                    case "data-bind" -> {
                        for (String part : value.split(",")) {
                            String key = part.trim();
                            int colon = key.indexOf(':');
                            if (colon > 0) key = key.substring(colon + 1).trim();
                            addKey(key, out);
                        }
                    }
                    case "data-if", "mc-if", "data-show" -> addExpressionDeps(value, out);
                    case "data-class" -> {
                        for (String item : value.split(",")) {
                            int colon = item.indexOf(':');
                            if (colon > 0) addExpressionDeps(item.substring(colon + 1), out);
                        }
                    }
                    case "data-for", "mc-for" -> {
                        Matcher fm = FOR_EXPR_PATTERN.matcher(value.trim());
                        addKey(fm.matches() ? fm.group(2).trim() : value.trim(), out);
                    }
                    default -> { }
                }
            }
        }

        private static void addPlaceholderDeps(String text, Set<String> out) {
            if (text == null || !text.contains("{{")) return;
            if (text.contains("{{@")) out.add(ALWAYS);
            Matcher m = PLACEHOLDER_PATTERN.matcher(text);
            while (m.find()) {
                addKey(m.group(1).trim(), out);
            }
        }

        private static void addExpressionDeps(String expr, Set<String> out) {
            if (expr == null) return;
            Matcher m = IDENTIFIER_PATTERN.matcher(expr);
            while (m.find()) {
                addKey(m.group(), out);
            }
        }

        private static void addKey(String key, Set<String> out) {
            if (key == null || key.isEmpty()) return;
            out.add(key);
            int dot = key.indexOf('.');
            if (dot > 0) out.add(key.substring(0, dot));
        }
    }
}