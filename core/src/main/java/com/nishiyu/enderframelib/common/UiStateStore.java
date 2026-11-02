package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.Bindings;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class UiStateStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(UiStateStore.class);
    private static final String DEP_ALWAYS = Bindings.DEP_ALWAYS;

    private final String sessionId;
    private final Supplier<? extends UiView> viewSupplier;
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

    /** @param viewSupplier このストアが書き換える文書を持つビューを引く口（ビューが無ければ null を返す）。 */
    public UiStateStore(String sessionId, Supplier<? extends UiView> viewSupplier) {
        this.sessionId = sessionId;
        this.viewSupplier = viewSupplier;
        this.dirty = true;
        this.dirtyKeys.add(DEP_ALWAYS);
    }

    public UiStateStore set(String key, Object value) {
        return setInternal(key, value, true);
    }

    private void warnCallbackFailureOnce(String kind, String key, Throwable t) {
        if (loggedCallbackFailures.add(kind + ":" + key)) {
            LOGGER.warn(
                    "[UiStateStore] session={} の {} '{}' が例外を投げました。"
                            + "このバインディングは以降更新されない可能性があります（以後この組み合わせでは警告しません）",
                    sessionId, kind, key, t);
        }
    }

    private UiStateStore setInternal(String key, Object value, boolean notifyWatchers) {
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

    public UiStateStore bind(String key, Supplier<?> supplier) {
        if (key != null && supplier != null) {
            boundSuppliers.put(key, supplier);
            try {
                setInternal(key, supplier.get(), false);
            } catch (Exception e) { warnCallbackFailureOnce("bind supplier", key, e); }
        }
        return this;
    }

    public UiStateStore bindList(String key, Supplier<List<Map<String, String>>> listSupplier) {
        if (key != null && listSupplier != null) {
            boundListSuppliers.put(key, listSupplier);
            try {
                setList(key, listSupplier.get());
            } catch (Exception e) { warnCallbackFailureOnce("bindList supplier", key, e); }
        }
        return this;
    }

    public UiStateStore watch(String key, Consumer<String> listener) {
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
        UiView session = viewSupplier.get();
        HtmlNode vRoot = session != null ? session.rootNode() : null;
        if (vRoot == null || elementId == null) return;
        HtmlNode node = vRoot.getNodeById(elementId);
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

    public UiStateStore setList(String key, List<Map<String, String>> items) {
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

    /** 文書を作り直したとき用。値が変わっていなくても、次の {@link #syncToDom()} で全体を当て直させる。 */
    public void markAllDirty() {
        dirty = true;
        dirtyKeys.add(DEP_ALWAYS);
    }

    public void syncToDom() {
        pollBoundSuppliers();
        if (!dirty) return;
        UiView session = viewSupplier.get();
        HtmlNode root = session != null ? session.rootNode() : null;
        if (root == null) return;

        Set<String> changedKeys = Set.copyOf(dirtyKeys);
        dirty = false;
        dirtyKeys.clear();

        boolean changed = binder.sync(root, values, lists, changedKeys);
        // 書き換えた要素はキャッシュ済みの算出スタイルを捨てさせる（印が無いと古いスタイルが使い回される）
        for (HtmlNode node : binder.drainChangedNodes()) {
            session.interactionState().markDirty(node);
        }
        boolean layoutTreeChanged = binder.drainLayoutTreeChanged();
        if (changed) {
            // 見た目（style / class）だけの書き換えは、箱の木を作り直さず再スタイルから必要な段だけ走らせる。
            // ドラッグで left / top を束ねている場合は位置の決め直しだけで済む
            if (layoutTreeChanged) {
                session.requestRender();
            } else {
                session.requestRestyle();
            }
        }
    }
}
