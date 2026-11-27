package com.nishiyu.enderframelib.core.dom;

import static com.nishiyu.enderframelib.core.dom.Bindings.FOR_EXPR_PATTERN;
import static com.nishiyu.enderframelib.core.dom.Bindings.applyI18nAttributes;
import static com.nishiyu.enderframelib.core.dom.Bindings.evaluateCondition;
import static com.nishiyu.enderframelib.core.dom.Bindings.extractTemplateChildren;
import static com.nishiyu.enderframelib.core.dom.Bindings.resolve;
import static com.nishiyu.enderframelib.core.dom.Bindings.uniquifyIds;

import com.nishiyu.enderframelib.core.dom.node.DomUtils;
import com.nishiyu.enderframelib.core.dom.node.HostSyntax;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.InlineStyleOverlay;
import com.nishiyu.enderframelib.core.kernel.compiler.BindingPatchTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * 状態の値を DOM へ同期する（旧 {@code ReactiveStateStore} の DOM 同期処理。M5-2 で {@link Bindings} の入れ子から出した）。
 * 置換前のテンプレート（文字・属性・{@code data-show} の元の display）を節ごとに覚え、変わったキーに依る部分木（{@link TemplateDeps}）だけを
 * 解き直す。{@code data-list} / {@code data-for} の行は {@code data-row-key} で使い回す。
 */
public final class ReactiveDomBinder {
    private final Map<HtmlNode, String> originalNodeTexts = new IdentityHashMap<>();
    private final Map<HtmlNode, Map<String, String>> originalNodeAttrs = new IdentityHashMap<>();
    private final Map<HtmlNode, String> originalShowDisplay = new IdentityHashMap<>();
    private final Map<String, List<HtmlNode>> listTemplates = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> lastRenderedLists = new ConcurrentHashMap<>();
    private final TemplateDeps templateDeps = new TemplateDeps();
    private final List<HtmlNode> changedNodes = new ArrayList<>();
    /**
     * 直前までの {@link #sync} で、見た目（style / class / hidden）以外を書き換えたか。文字・子の並び・その他の属性は
     * 箱の木の作り直しが要る。見た目だけなら呼び出し側は再スタイルで済ませられる。
     */
    private boolean layoutTreeChanged = false;

    /** {@link #layoutTreeChanged} を返して倒す。 */
    public boolean drainLayoutTreeChanged() {
        boolean out = layoutTreeChanged;
        layoutTreeChanged = false;
        return out;
    }

    /**
     * 直前までの {@link #sync} で属性・文字を書き換えた要素を返して空にする。
     *
     * <p>算出スタイルは要素ごとにキャッシュされ、汚れた印が無いと使い回される。バインディングで
     * style や class を書き換えても印を付けなければ古いスタイルのまま描かれる（以前はクリックで
     * フォーカスが動いて祖先ごと再スタイルされたときにだけ反映されていた）ので、呼び出し側はここで
     * 返した要素を汚してから再レイアウトを要求する。</p>
     */
    public List<HtmlNode> drainChangedNodes() {
        List<HtmlNode> out = List.copyOf(changedNodes);
        changedNodes.clear();
        return out;
    }

    public void clear() {
        if (patchTable != null) patchTable.clearValues();
        patchTable = null;
        patchRoot = null;
        originalNodeTexts.clear();
        originalNodeAttrs.clear();
        originalShowDisplay.clear();
        changedNodes.clear();
        layoutTreeChanged = false;
        listTemplates.clear();
        lastRenderedLists.clear();
        templateDeps.clear();
    }

    public TemplateDeps templateDeps() {
        return templateDeps;
    }

    public boolean sync(HtmlNode root, Map<String, String> values,
                        Map<String, List<Map<String, String>>> lists,
                        Set<String> changedKeys) {
        if (root == null) return false;
        templateDeps.index(root);

        Set<String> scopeKeys = null;
        if (!changedKeys.contains(TemplateDeps.ALWAYS)) {
            if (TemplateDeps.canSkip(templateDeps.index(root), changedKeys)) return false;
            scopeKeys = changedKeys;
        }

        patchRoot = root;
        rootValues = values;
        try {
            return applyRecursive(root, values, lists, scopeKeys);
        } finally {
            rootValues = null;
        }
    }

    private BindingPatchTable patchTable;
    private HtmlNode patchRoot;
    /** 実行中の {@link #sync} が根のスコープとして受け取った値の表（局所スコープの値とは別物）。 */
    private Map<String, String> rootValues;

    /**
     * 数値のスタイルバインディング（{@code style="width: {{k}}%"} など）を、属性の文字列を書き換えずに直接書いた表。
     * スタイル解決は {@link InlineStyleOverlay} を通してこの値を読む。最初に必要になるまで作らない。
     */
    public BindingPatchTable patchTable() {
        return patchTable;
    }

    private BindingPatchTable tableFor(HtmlNode vNode) {
        if (patchRoot == null) return null;
        var ids = patchRoot.nodeIds();
        if (ids == null || !vNode.hasNodeId() || vNode.nodeIds() != ids) return null;
        if (patchTable == null || !patchTable.isFor(ids)) {
            patchTable = new BindingPatchTable(ids);
        }
        return patchTable;
    }

    /** 有限の数値（CSS にそのまま書ける形）だけを読む。それ以外は NaN（従来の属性経路が扱う）。 */
    private static float finiteNumber(String raw) {
        if (raw == null) return Float.NaN;
        String t = raw.trim();
        if (t.isEmpty()) return Float.NaN;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!(c >= '0' && c <= '9') && c != '.' && c != '-' && c != '+' && c != 'e' && c != 'E') return Float.NaN;
        }
        try {
            float f = Float.parseFloat(t);
            return Float.isFinite(f) ? f : Float.NaN;
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /**
     * style 属性のテンプレートを解く。数値の {@code prop: {{key}}unit} は属性に書かず {@link #patchTable} の列へ直接書き、
     * それ以外の宣言（数値でない値・式・同じ属性の重複）は従来どおり文字列に解く。
     *
     * @param patchChanged 直接書き込みの値が変わったら [0] を true にする
     */
    private String resolveInlineStyle(HtmlNode vNode, String rawStyle, Map<String, String> scopeData,
                                      boolean[] patchChanged) {
        var table = scopeData == rootValues ? tableFor(vNode) : null;
        if (table == null) return resolve(rawStyle, scopeData, null);
        String[] decls = rawStyle.split(";", -1);
        Map<String, Integer> propCount = new HashMap<>();
        for (String decl : decls) {
            int colon = decl.indexOf(':');
            if (colon > 0) propCount.merge(decl.substring(0, colon).trim().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        int id = vNode.nodeId();
        StringBuilder kept = new StringBuilder();
        boolean armed = false;
        for (String decl : decls) {
            var patch = BindingPatchTable.parsePatch(decl);
            if (patch != null && propCount.get(patch.prop()) == 1) {
                float v = finiteNumber(scopeData.get(patch.key()));
                if (table.set(id, patch.field(), v)) patchChanged[0] = true;
                if (!Float.isNaN(v)) {
                    armed = true;
                    continue; // 属性には書かない
                }
            }
            if (decl.isBlank()) continue;
            if (kept.length() > 0) kept.append(';');
            kept.append(decl);
        }
        if (armed && vNode.inlineOverlay != table) vNode.inlineOverlay = table;
        if (patchChanged[0]) vNode.touchInlineOverlay();
        return resolve(kept.toString(), scopeData, null);
    }

    /** 節の「置換前の」style 属性（同期で解決済みになる前のテンプレート文字列）。 */
    private String templateStyleOf(HtmlNode n) {
        Map<String, String> orig = originalNodeAttrs.get(n);
        return orig != null && orig.get("style") != null ? orig.get("style") : n.attrs.get("style");
    }

    private boolean applyRecursive(HtmlNode vNode, Map<String, String> scopeData,
                                   Map<String, List<Map<String, String>>> lists,
                                   Set<String> changedKeys) {
        if (vNode == null) return false;
        if (changedKeys != null && TemplateDeps.canSkip(templateDeps.lookup(vNode), changedKeys)) return false;

        boolean changed = applyI18nAttributes(vNode);
        if (changed) layoutTreeChanged = true;

        if (applyTextPlaceholders(vNode, scopeData)) {
            changed = true;
            layoutTreeChanged = true;
        }
        if (applyAttributePlaceholders(vNode, scopeData)) changed = true;
        if (applyDataModel(vNode, scopeData)) {
            changed = true;
            layoutTreeChanged = true;
        }
        if (applyDataBind(vNode, scopeData)) changed = true;
        if (applyConditionalVisibility(vNode, scopeData)) changed = true;
        if (applyDataClass(vNode, scopeData)) changed = true;
        if (changed) changedNodes.add(vNode);

        return handleListIterationOrChildren(vNode, scopeData, lists, changed, changedKeys);
    }

    private boolean applyTextPlaceholders(HtmlNode vNode, Map<String, String> scopeData) {
        if (vNode.text == null && !originalNodeTexts.containsKey(vNode)) return false;
        String rawText = originalNodeTexts.computeIfAbsent(vNode, n -> n.text != null ? n.text : "");
        if (rawText.contains("{{") && !rawText.contains("{{@")) {
            String resolved = resolve(rawText, scopeData, null);
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
                boolean[] patchChanged = {false};
                String resolved = "style".equalsIgnoreCase(k)
                        ? resolveInlineStyle(vNode, rawVal, scopeData, patchChanged)
                        : resolve(rawVal, scopeData, null);
                if (patchChanged[0]) changed = true; // 属性は変わらなくても、見た目は変わった
                if (!Objects.equals(resolved, vNode.attrs.get(k))) {
                    DomUtils.updateAttribute(vNode, k, resolved, false);
                    changed = true;
                    if (!isPresentationalAttribute(k)) layoutTreeChanged = true;
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
                scopeData.put(modelKey, Boolean.toString(vNode.attrs.containsKey("checked")));
            } else {
                scopeData.put(modelKey, vNode.attrs.getOrDefault("value", vNode.getTextContent().trim()));
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
                        vNode.updateTextContent(val);
                        changed = true;
                        layoutTreeChanged = true;
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
                    if (!isPresentationalAttribute(target)) layoutTreeChanged = true;
                }
            }
        }
        return changed;
    }

    private boolean applyConditionalVisibility(HtmlNode vNode, Map<String, String> scopeData) {
        boolean changed = false;
        String ifExpr = HostSyntax.attr(vNode.attrs, "data-if");
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
                // 表示時は元のインライン display を復元する。インライン指定が無ければ
                // display を外してスタイルシートの値（例: block / flex）に委ねる。
                String origDisp = originalShowDisplay.computeIfAbsent(vNode, n -> {
                    String explicit = n.attrs.get("data-orig-display");
                    if (explicit != null && !explicit.isBlank()) return explicit.trim();
                    String inline = n.getStyleProperty("display");
                    return inline == null || "none".equalsIgnoreCase(inline) ? "" : inline;
                });
                String targetDisp = visibleObj ? origDisp : "none";
                String curDisp = vNode.getStyleProperty("display");
                if (!Objects.equals(targetDisp.isEmpty() ? null : targetDisp, curDisp)) {
                    vNode.setStyleProperty("display", targetDisp);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** 書き換えても再スタイルだけで済む属性か（カスケードの入力にしかならない）。 */
    private static boolean isPresentationalAttribute(String name) {
        return "style".equalsIgnoreCase(name) || "class".equalsIgnoreCase(name) || "hidden".equalsIgnoreCase(name);
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
                                                  Map<String, List<Map<String, String>>> lists,
                                                  boolean changedSoFar, Set<String> changedKeys) {
        String listKey = null;
        String itemAlias = null;
        if (vNode.attrs.containsKey("data-list")) {
            listKey = vNode.attrs.get("data-list").trim();
        } else {
            String forAttr = HostSyntax.attr(vNode.attrs, "data-for");
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
                List<Map<String, String>> lastList = lastRenderedLists.get(vNode.key());
                if (Objects.equals(itemDataList, lastList)) {
                    return changedSoFar;
                }

                List<HtmlNode> tmplNodes = listTemplates.computeIfAbsent(vNode.key(), id -> extractTemplateChildren(vNode));
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
                                instance.setAttribute("data-row-key", itemSubKey);
                            }

                            applyRecursive(instance, merged, lists, null);
                            instance.parent = vNode;
                            newChildren.add(instance);
                        }
                        idx++;
                    }

                    for (HtmlNode leftover : existingKeyedNodes.values()) leftover.parent = null;
                    for (HtmlNode leftover : nonKeyedNodes) leftover.parent = null;

                    vNode.children.clear();
                    vNode.children.addAll(newChildren);
                    lastRenderedLists.put(vNode.key(), new ArrayList<>(itemDataList));
                    layoutTreeChanged = true;
                    return true;
                }
            }
            return changedSoFar;
        }

        boolean changed = changedSoFar;
        int vSize = vNode.children.size();
        for (int i = 0; i < vSize; i++) {
            HtmlNode vChild = vNode.children.get(i);
            if (applyRecursive(vChild, scopeData, lists, changedKeys)) {
                changed = true;
            }
        }
        return changed;
    }
}
