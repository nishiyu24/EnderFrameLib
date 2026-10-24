package com.nishiyu.enderframelib.server.state;

import com.nishiyu.enderframelib.api.WebSessionHandle;
import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.network.OpenUiS2CPacket;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.network.StateNbtConverter.KeyTable;
import com.nishiyu.enderframelib.network.StateNbtConverter;
import com.nishiyu.enderframelib.server.ServerPubSubManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * {@link UIState} の標準実装。
 *
 * <p>DOM ノードを一切保持せず、純粋なスレッドセーフな Key-Value マップと変更差分（dirtyKeys）のみを管理します。
 * 初回オープン時は {@link OpenUiS2CPacket}、稼働中の flush 時は最小差分の {@link StateDeltaS2CPacket} を配信します。</p>
 */
@ApiStatus.Internal
public class UIStateImpl implements UIState {

    /** パケット上でインスタンスを指す番号の採番。作り直した状態は別の番号になる。 */
    private static final AtomicInteger NEXT_WIRE_ID = new AtomicInteger();

    private final String templateId;
    private final String instanceId;
    private final int wireId = NEXT_WIRE_ID.incrementAndGet();

    /**
     * キー辞書。追記だけで、{@link #addViewer} と {@link #flush} が {@code this} で同期して更新する。
     * {@code announcedKeys} は差分パケットで全閲覧者に名前を知らせ終えた件数。開いたときの辞書は全体を送るので、
     * それ以降に（開く処理を含めて）増えた分は次の差分に名前を添える。
     */
    private KeyTable keyTable = KeyTable.EMPTY;
    private int announcedKeys = 0;

    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Set<String> dirtyKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> removedKeys = ConcurrentHashMap.newKeySet();
    private final Set<ServerPlayer> viewers = ConcurrentHashMap.newKeySet();
    private final Map<String, BiConsumer<ServerPlayer, CompoundTag>> actionHandlers = new ConcurrentHashMap<>();

    private volatile BiConsumer<ServerPlayer, CustomPacketPayload> packetSender = (player, payload) -> {
        ServerPubSubManager.ServerPacketSender.send(player, payload);
    };

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 持ち主のブロックエンティティ（{@code createBlockEntityState} で作った状態だけ）。破壊・アンロードで
     * {@link BlockEntity#isRemoved()} になったら {@link UIStateManager#tickAll()} が閉じる。
     * 弱参照なので、この状態がブロックエンティティを生かし続けることはない。
     */
    private volatile WeakReference<BlockEntity> owner;

    public UIStateImpl(@NotNull String templateId, @NotNull String instanceId) {
        this.templateId = Objects.requireNonNull(templateId, "templateId cannot be null");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId cannot be null");
    }

    /**
     * テスト用などにパケット送信関数を注入します。
     */
    public void setPacketSender(@NotNull BiConsumer<ServerPlayer, CustomPacketPayload> sender) {
        this.packetSender = Objects.requireNonNull(sender, "sender cannot be null");
    }

    @Override
    public @NotNull String templateId() {
        return templateId;
    }

    @Override
    public @NotNull String instanceId() {
        return instanceId;
    }

    void setOwner(@Nullable BlockEntity be) {
        this.owner = be != null ? new WeakReference<>(be) : null;
    }

    boolean hasOwner() {
        return owner != null;
    }

    /** 持ち主のブロックエンティティが破壊・アンロードされた（または回収された）かどうか。持ち主が無ければ false。 */
    boolean isOwnerGone() {
        WeakReference<BlockEntity> ref = owner;
        if (ref == null) return false;
        BlockEntity be = ref.get();
        return be == null || be.isRemoved();
    }

    /** パケット上でこのインスタンスを指す番号。 */
    public int wireId() {
        return wireId;
    }

    @Override
    public @NotNull UIState set(@NotNull String key, @Nullable Object value) {
        Objects.requireNonNull(key, "key cannot be null");
        if (closed.get()) return this;

        Object previous = values.get(key);
        if (Objects.deepEquals(previous, value)) {
            // 値が変わっていない場合は dirty にしない
            return this;
        }

        if (value == null) {
            values.remove(key);
            removedKeys.add(key);
        } else {
            // 配列は呼び出し側が中身を書き換えて同じ参照を set し直すことがあるので、複製して持つ
            values.put(key, value instanceof byte[] a ? a.clone() : value instanceof int[] a ? a.clone()
                    : value instanceof long[] a ? a.clone() : value);
            removedKeys.remove(key);
        }
        dirtyKeys.add(key);
        return this;
    }

    /** 持ち主のブロックエンティティに、保存が要ることを伝える（状態は NBT に保存されるため）。 */
    private void markOwnerChanged() {
        WeakReference<BlockEntity> ref = owner;
        BlockEntity be = ref != null ? ref.get() : null;
        if (be != null && !be.isRemoved()) be.setChanged();
    }

    @Override
    public @NotNull UIState load(@NotNull CompoundTag tag) {
        Objects.requireNonNull(tag, "tag cannot be null");
        for (String key : tag.getAllKeys()) {
            set(key, StateNbtConverter.fromTag(tag.get(key)));
        }
        return this;
    }

    @Override
    public @Nullable Object get(@NotNull String key) {
        Objects.requireNonNull(key, "key cannot be null");
        return values.get(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <T> T get(@NotNull String key, @NotNull Class<T> type) {
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
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    @Override
    public long getLong(@NotNull String key, long defaultValue) {
        Object val = get(key);
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    @Override
    public double getDouble(@NotNull String key, double defaultValue) {
        Object val = get(key);
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        if (val instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    @Override
    public boolean getBoolean(@NotNull String key, boolean defaultValue) {
        Object val = get(key);
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof Number n) {
            return n.intValue() != 0;
        }
        if (val instanceof String s) {
            String lower = s.trim().toLowerCase();
            if ("true".equals(lower) || "1".equals(lower)) return true;
            if ("false".equals(lower) || "0".equals(lower)) return false;
        }
        return defaultValue;
    }

    @Override
    public @NotNull Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(values));
    }

    @Override
    public @NotNull CompoundTag toCompoundTag() {
        return StateNbtConverter.toCompoundTag(values);
    }

    @Override
    public void addViewer(@NotNull ServerPlayer player) {
        open(player, 0, 0);
    }

    @Override
    public void addSurfaceViewer(@NotNull ServerPlayer player, int width, int height) {
        open(player, Math.max(1, width), Math.max(1, height));
    }

    @Override
    public void removeSurfaceViewer(@NotNull ServerPlayer player) {
        if (player == null) return;
        boolean wasViewer = isViewer(player);
        removeViewer(player);
        if (wasViewer && !closed.get() && !player.hasDisconnected()) {
            sendPacket(player, StateDeltaS2CPacket.closed(wireId, instanceId));
        }
    }

    private void open(@NotNull ServerPlayer player, int surfaceWidth, int surfaceHeight) {
        Objects.requireNonNull(player, "player cannot be null");
        if (closed.get()) return;

        // 新規閲覧者には現在の最新スナップショットを OpenUiS2CPacket で即時送信
        OpenUiS2CPacket packet;
        synchronized (this) {
            boolean firstViewer = viewers.isEmpty();
            viewers.add(player);
            CompoundTag snapshot = toCompoundTag();
            for (String key : snapshot.getAllKeys()) {
                keyTable = keyTable.with(key);
            }
            packet = new OpenUiS2CPacket(templateId, instanceId, wireId, keyTable, snapshot, surfaceWidth, surfaceHeight);
            // 他に閲覧者がいなければ、辞書の全体を知っているのはこの閲覧者だけで、差分で名前を添え直す必要はない
            if (firstViewer) announcedKeys = keyTable.size();
        }
        sendPacket(player, packet);
    }

    @Override
    public void removeViewer(@NotNull ServerPlayer player) {
        if (player == null) return;
        // リスポーン等で ServerPlayer インスタンスが差し替わっても外せるよう UUID で照合する
        viewers.removeIf(v -> isSamePlayer(v, player));
    }

    /**
     * 指定プレイヤーがこの UI の閲覧者かどうかを返します（UUID で照合）。
     * クライアントからのアクション受付可否の判定に使用します。
     */
    public boolean isViewer(@Nullable ServerPlayer player) {
        if (player == null) return false;
        for (ServerPlayer v : viewers) {
            if (isSamePlayer(v, player)) return true;
        }
        return false;
    }

    private static boolean isSamePlayer(ServerPlayer a, ServerPlayer b) {
        if (a == b) return true;
        UUID ua = a.getUUID();
        return ua != null && ua.equals(b.getUUID());
    }

    @Override
    public @NotNull Set<ServerPlayer> viewers() {
        return Collections.unmodifiableSet(viewers);
    }

    @Override
    public boolean hasChanges() {
        return !dirtyKeys.isEmpty();
    }

    @Override
    public synchronized void flush() {
        if (closed.get() || dirtyKeys.isEmpty()) {
            return;
        }

        if (viewers.isEmpty()) {
            // 閲覧者がいなくても dirty はリセットしてメモリ肥大化を防止
            dirtyKeys.clear();
            removedKeys.clear();
            // 次に開く閲覧者には辞書の全体が届くので、ここまでの分は知らせ済みとしてよい
            announcedKeys = keyTable.size();
            return;
        }

        // 保存が要ることは tick に 1 度だけ伝える（set のたびに setChanged しない）
        markOwnerChanged();

        CompoundTag deltas = new CompoundTag();
        for (String key : dirtyKeys) {
            keyTable = keyTable.with(key);
            Object val = values.get(key);
            if (val != null) {
                StateNbtConverter.putValue(deltas, key, val);
            }
        }
        if (!removedKeys.isEmpty()) {
            ListTag removedList = new ListTag();
            for (String rem : removedKeys) {
                removedList.add(StringTag.valueOf(rem));
            }
            deltas.put("__removed", removedList);
        }

        dirtyKeys.clear();
        removedKeys.clear();

        StateDeltaS2CPacket packet = new StateDeltaS2CPacket(wireId, instanceId, keyTable, announcedKeys, deltas, false);
        announcedKeys = keyTable.size();

        // 切断済みプレイヤーを自動パージ
        viewers.removeIf(ServerPlayer::hasDisconnected);

        for (ServerPlayer viewer : viewers) {
            sendPacket(viewer, packet);
        }
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
        if (closed.get()) return;

        BiConsumer<ServerPlayer, CompoundTag> handler = actionHandlers.get(actionName);
        if (handler != null) {
            handler.accept(player, payload != null ? payload : new CompoundTag());
        }
    }

    private void sendPacket(ServerPlayer player, CustomPacketPayload packet) {
        try {
            packetSender.accept(player, packet);
        } catch (Exception ignored) {
            // 切断時などのパケット送信失敗でクラッシュさせない
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // 画面を開いたままの閲覧者に破棄を知らせて閉じさせる（知らせないと古い値の画面が残り、操作も届かない）
            StateDeltaS2CPacket closedPacket = StateDeltaS2CPacket.closed(wireId, instanceId);
            for (ServerPlayer viewer : viewers) {
                if (!viewer.hasDisconnected()) sendPacket(viewer, closedPacket);
            }
            viewers.clear();
            values.clear();
            dirtyKeys.clear();
            removedKeys.clear();
            actionHandlers.clear();
            owner = null;
            UIStateManager.unregister(this);
        }
    }
}
