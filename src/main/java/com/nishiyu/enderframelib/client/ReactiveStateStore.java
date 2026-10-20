package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.core.dom.Bindings;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * リアクティブ状態（値・リスト・ウォッチャー・サプライヤ）の保持と管理を行うストア。
 * <p>STRUCTURE_PLAN S6 に準拠し、DOM へのバインディング反映・条件解釈は
 * {@link Bindings} に委譲する。</p>
 */
public class ReactiveStateStore {

    private static final String DEP_ALWAYS = Bindings.TemplateDeps.ALWAYS;

    private final String sessionId;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> lists = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> lastRawBoundLists = new ConcurrentHashMap<>();

    private final Map<String, Supplier<?>> boundSuppliers = new ConcurrentHashMap<>();
    private final Map<String, Supplier<List<Map<String, String>>>> boundListSuppliers = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> watchers = new ConcurrentHashMap<>();

    private final Set<String> dirtyKeys = ConcurrentHashMap.newKeySet();
    private boolean dirty = false;

    private final Bindings.ReactiveDomBinder binder = new Bindings.ReactiveDomBinder();
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
        binder.clear();
    }

    public void notifyElementModelChanged(String elementId, String newValue) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode vRoot = session != null ? session.rootNode() : null;
        if (vRoot == null || elementId == null) return;
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        HtmlNode node = vRoot.getNodeById(baseId);
        if (node != null) {
            String modelKey = Bindings.getModelKey(node);
            if (modelKey != null && !modelKey.isEmpty()) {
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
        binder.clear();
        lastRawBoundLists.clear();
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
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode root = session != null ? session.rootNode() : null;
        if (root == null) return;

        Set<String> changedKeys = Set.copyOf(dirtyKeys);
        dirty = false;
        dirtyKeys.clear();

        if (binder.sync(root, values, lists, changedKeys)) {
            session.requestRender();
        }
    }

    // ------------------------------------------------------------------
    // 下位互換用ヘルパー委譲（Bindings へ委譲）
    // ------------------------------------------------------------------

    public static List<HtmlNode> extractTemplateChildren(HtmlNode src) {
        return Bindings.extractTemplateChildren(src);
    }

    public static Boolean evaluateCondition(String expr, Map<String, String> scopeData) {
        return Bindings.evaluateCondition(expr, scopeData);
    }

    public static final class TemplateDeps {
        public static final String ALWAYS = Bindings.TemplateDeps.ALWAYS;
        private final Bindings.TemplateDeps delegate = new Bindings.TemplateDeps();

        public Set<String> index(HtmlNode root) { return delegate.index(root); }
        public Set<String> lookup(HtmlNode node) { return delegate.lookup(node); }
        public void clear() { delegate.clear(); }

        public static boolean canSkip(Set<String> deps, Set<String> changedKeys) {
            return Bindings.TemplateDeps.canSkip(deps, changedKeys);
        }
    }
}
