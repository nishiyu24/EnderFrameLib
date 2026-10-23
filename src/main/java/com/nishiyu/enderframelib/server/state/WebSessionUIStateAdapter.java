package com.nishiyu.enderframelib.server.state;

import com.nishiyu.enderframelib.api.WebSessionHandle;
import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.network.StateNbtConverter;
import com.nishiyu.enderframelib.server.WebSession;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 後方互換性アダプタ (H4):
 * 従来の {@link WebSession}（動的インライン HTML 画面 / Ad-hoc セッション）を
 * 新アーキテクチャの {@link UIState} として操作できるようにするアダプタ。
 *
 * <p>Mod 開発者は、既存の動的インライン画面セッションに対しても統一的な
 * {@link UIState#set(String, Object)} API を使用してリアクティブな更新を行うことができます。</p>
 */
@ApiStatus.Internal
public class WebSessionUIStateAdapter implements UIState {

    private final WebSession session;
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Set<String> dirtyKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, BiConsumer<ServerPlayer, CompoundTag>> actionHandlers = new ConcurrentHashMap<>();

    public WebSessionUIStateAdapter(@NotNull WebSession session) {
        this.session = Objects.requireNonNull(session, "session cannot be null");
    }

    /**
     * ラップしている {@link WebSession} を取得します。
     */
    @NotNull
    public WebSession getSession() {
        return session;
    }

    @Override
    public @NotNull String templateId() {
        return "adhoc:" + session.getSessionId();
    }

    @Override
    public @NotNull String instanceId() {
        return session.getSessionId();
    }

    @Override
    public @NotNull UIState set(@NotNull String key, @Nullable Object value) {
        Objects.requireNonNull(key, "key cannot be null");
        Object oldValue;
        if (value == null) {
            oldValue = values.remove(key);
        } else {
            oldValue = values.put(key, value);
        }

        if (!Objects.equals(oldValue, value)) {
            dirtyKeys.add(key);

            // 従来の WebSession DOM / 変異配信へ反映
            applyToSession(key, value);
        }
        return this;
    }

    private void applyToSession(String key, @Nullable Object value) {
        if (key.contains(":")) {
            // 例: "elementId:property" (スタイル変異またはクラス変異)
            int colon = key.indexOf(':');
            String elementId = key.substring(0, colon);
            String property = key.substring(colon + 1);
            if (property.startsWith("class:")) {
                String className = property.substring(6);
                session.patchClass(elementId, className, value != null ? String.valueOf(value) : "add");
            } else {
                session.patchStyle(elementId, property, value != null ? String.valueOf(value) : "");
            }
        } else if (key.contains("@")) {
            // 例: "elementId@attrName" (属性変異)
            int at = key.indexOf('@');
            String elementId = key.substring(0, at);
            String attrName = key.substring(at + 1);
            session.patchAttribute(elementId, attrName, value != null ? String.valueOf(value) : "", value == null);
        } else {
            // 標準テキスト変異
            session.patchText(key, value != null ? String.valueOf(value) : "");
        }
    }

    @Override
    public @Nullable Object get(@NotNull String key) {
        Objects.requireNonNull(key, "key cannot be null");
        return values.get(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(@NotNull String key, @NotNull Class<T> type) {
        Object val = get(key);
        if (val == null) return null;
        if (type.isInstance(val)) {
            return (T) val;
        }
        return null;
    }

    @Override
    public @Nullable String getString(@NotNull String key) {
        Object val = get(key);
        return val != null ? String.valueOf(val) : null;
    }

    @Override
    public int getInt(@NotNull String key, int defaultValue) {
        Object val = get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    @Override
    public long getLong(@NotNull String key, long defaultValue) {
        Object val = get(key);
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    @Override
    public double getDouble(@NotNull String key, double defaultValue) {
        Object val = get(key);
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    @Override
    public boolean getBoolean(@NotNull String key, boolean defaultValue) {
        Object val = get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        if (val instanceof String s) {
            String lower = s.trim().toLowerCase();
            if ("true".equals(lower) || "1".equals(lower)) return true;
            if ("false".equals(lower) || "0".equals(lower)) return false;
        }
        return defaultValue;
    }

    @Override
    public @NotNull Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(values));
    }

    @Override
    public @NotNull CompoundTag toCompoundTag() {
        return StateNbtConverter.toCompoundTag(values);
    }

    @Override
    public void addViewer(@NotNull ServerPlayer player) {
        session.addViewer(player);
    }

    @Override
    public void removeViewer(@NotNull ServerPlayer player) {
        session.removeViewer(player);
    }

    @Override
    public @NotNull Set<ServerPlayer> viewers() {
        return session.viewers();
    }

    @Override
    public boolean hasChanges() {
        return !dirtyKeys.isEmpty();
    }

    @Override
    public void flush() {
        dirtyKeys.clear();
        session.requestReRender();
    }

    @Override
    public @NotNull UIState onAction(@NotNull String actionName, @NotNull BiConsumer<ServerPlayer, CompoundTag> handler) {
        Objects.requireNonNull(actionName, "actionName cannot be null");
        Objects.requireNonNull(handler, "handler cannot be null");
        actionHandlers.put(actionName, handler);
        return this;
    }

    @Override
    public void dispatchAction(@NotNull String actionName, @NotNull ServerPlayer player, @NotNull CompoundTag payload) {
        Objects.requireNonNull(actionName, "actionName cannot be null");
        Objects.requireNonNull(player, "player cannot be null");
        BiConsumer<ServerPlayer, CompoundTag> handler = actionHandlers.get(actionName);
        if (handler != null) {
            handler.accept(player, payload != null ? payload : new CompoundTag());
        }
    }

    @Override
    public void close() {
        dirtyKeys.clear();
        values.clear();
        actionHandlers.clear();
        session.clear();
    }

    @Override
    public @NotNull WebSessionHandle asSessionHandle() {
        return session;
    }
}
