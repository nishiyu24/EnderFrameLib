package com.nishiyu.enderframelib.server.state;

import com.nishiyu.enderframelib.api.WebScreenBlockEntity;
import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.network.UiActionC2SPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * サーバー側でアクティブな全 {@link UIState} を統括管理するレジストリ。
 *
 * <p>サーバー tick での全状態一括 flush、プレイヤー切断時の安全な viewer 除去、
 * およびクライアントからのアクションパケット（{@code UiActionC2SPacket}）のディスパッチを担当します。</p>
 */
@ApiStatus.Internal
public final class UIStateManager {

    private static final Map<String, UIStateImpl> STATES = new ConcurrentHashMap<>();

    private UIStateManager() {}

    /**
     * 新しい {@link UIState} を作成・登録します。
     * 同一 instanceId の古いインスタンスが既に存在する場合は破棄（close）した上で再生成します。
     */
    @NotNull
    public static UIState createState(@NotNull String templateId, @NotNull String instanceId) {
        Objects.requireNonNull(templateId, "templateId cannot be null");
        Objects.requireNonNull(instanceId, "instanceId cannot be null");

        UIStateImpl old = STATES.remove(instanceId);
        if (old != null) {
            old.close();
        }

        UIStateImpl created = new UIStateImpl(templateId, instanceId);
        STATES.put(instanceId, created);
        return created;
    }

    /**
     * 既存の {@link UIState} を取得するか、存在しない場合は新しく作成します。
     */
    @NotNull
    public static UIState getOrCreateState(@NotNull String templateId, @NotNull String instanceId) {
        Objects.requireNonNull(templateId, "templateId cannot be null");
        Objects.requireNonNull(instanceId, "instanceId cannot be null");

        return STATES.computeIfAbsent(instanceId, id -> new UIStateImpl(templateId, id));
    }

    /**
     * ブロックエンティティの {@link UIState} を取得するか、無ければ作ります（インスタンス ID は {@code block:x,y,z}）。
     *
     * <p>作った状態はブロックエンティティを持ち主として覚え、破壊・アンロードされたら次の tick で閉じます。
     * 同じ座標に残っている状態の持ち主が既に消えていれば（ブロックを置き直した場合）、使い回さずに作り直します。
     * 持ち主の無い状態（{@code openUi} で先に作られたもの）は、このブロックエンティティを持ち主にして使います。</p>
     */
    @NotNull
    public static UIState getOrCreateBlockEntityState(@NotNull String templateId, @NotNull BlockEntity be) {
        Objects.requireNonNull(templateId, "templateId cannot be null");
        Objects.requireNonNull(be, "be cannot be null");
        String instanceId = blockInstanceId(be.getBlockPos());

        UIStateImpl existing = STATES.get(instanceId);
        if (existing != null) {
            if (!existing.isOwnerGone()) {
                if (!existing.hasOwner()) existing.setOwner(be);
                return existing;
            }
            existing.close();
        }
        UIStateImpl created = new UIStateImpl(templateId, instanceId);
        created.setOwner(be);
        STATES.put(instanceId, created);
        if (be instanceof WebScreenBlockEntity screen) screen.onStateCreated(created);
        return created;
    }

    /** ブロックの UI のインスタンス ID（{@code block:x,y,z}）。 */
    @NotNull
    public static String blockInstanceId(@NotNull BlockPos pos) {
        return "block:" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /**
     * 指定された instanceId の {@link UIState} を取得します。
     */
    @Nullable
    public static UIState findState(@NotNull String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId cannot be null");
        return STATES.get(instanceId);
    }

    /**
     * 指定された instanceId の {@link UIState} を登録解除します。
     */
    @Nullable
    public static UIState removeState(@NotNull String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId cannot be null");
        return STATES.remove(instanceId);
    }

    /** 閉じた状態の登録解除。同じ ID で作り直された新しい状態は外さない。 */
    static void unregister(@NotNull UIStateImpl state) {
        STATES.remove(state.instanceId(), state);
    }

    /**
     * サーバー tick ごとに呼ばれ、全アクティブ状態の変更差分を一括でクライアントへ配信します。
     * 持ち主のブロックエンティティが消えた状態はここで閉じます（閲覧者の画面も閉じる）。
     */
    public static void tickAll() {
        for (UIStateImpl state : STATES.values()) {
            if (state.isOwnerGone()) {
                state.close();
                continue;
            }
            state.flush();
        }
    }

    /**
     * プレイヤー切断時・ディメンション移動時に呼ばれ、全アクティブ状態の閲覧者リストから対象プレイヤーを除去します。
     */
    public static void removePlayerAll(@Nullable ServerPlayer player) {
        if (player == null) return;
        for (UIStateImpl state : STATES.values()) {
            state.removeViewer(player);
        }
    }

    /**
     * クライアントから受信した UI アクションを該当する UIState にルーティングして実行します。
     *
     * <p>instanceId（例: {@code block:x,y,z}）はクライアントが自由に指定できるため、
     * 送信者がその UIState の閲覧者である場合のみ実行します。予約アクション
     * {@link UiActionC2SPacket#CLOSE_ACTION} は画面クローズ通知として閲覧者から外します。</p>
     *
     * @return アクションを受け付けた場合 true（閲覧者でない・状態が存在しない場合は false）
     */
    public static boolean handleAction(@NotNull String instanceId, @NotNull String actionName,
                                       @NotNull ServerPlayer player, @Nullable CompoundTag payload) {
        Objects.requireNonNull(instanceId, "instanceId cannot be null");
        Objects.requireNonNull(actionName, "actionName cannot be null");
        Objects.requireNonNull(player, "player cannot be null");

        UIStateImpl state = STATES.get(instanceId);
        if (state == null || !state.isViewer(player)) {
            return false;
        }
        if (UiActionC2SPacket.CLOSE_ACTION.equals(actionName)) {
            state.removeViewer(player);
            return true;
        }
        state.dispatchAction(actionName, player, payload != null ? payload : new CompoundTag());
        return true;
    }

    /**
     * サーバー停止時に全状態を破棄し、静的マップを完全に初期化します。
     */
    public static void clearAll() {
        for (UIStateImpl state : STATES.values()) {
            state.close();
        }
        STATES.clear();
    }

    /**
     * 現在登録されている UIState の総数を返します。
     */
    public static int stateCount() {
        return STATES.size();
    }

    /**
     * 現在登録されている全 UIState の instanceId 一覧を返します。
     */
    @NotNull
    public static Set<String> stateIds() {
        return Set.copyOf(STATES.keySet());
    }

    /**
     * 現在すべての UIState に登録されている総閲覧者数を返します。
     */
    public static int viewerCount() {
        int total = 0;
        for (UIStateImpl state : STATES.values()) {
            total += state.viewers().size();
        }
        return total;
    }
}
