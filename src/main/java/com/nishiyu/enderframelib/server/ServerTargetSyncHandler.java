package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.network.s2c.PubSubUpdateS2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 視線先 / 周囲のエンティティの NBT を購読しているプレイヤーに配信する（AR HUD / トラッカー）。
 *
 * <p>配信する値はクライアントが指定したパスで選ばれるので、ここは
 * <b>クライアントからの NBT 読み出し要求</b>として扱う。許可リストに載っている
 * ルートキーの、スカラー値（数値・文字列）だけを返す。部分木をまるごと
 * 文字列化して返す経路は持たない（I-1）。</p>
 */
@ApiStatus.Internal
public final class ServerTargetSyncHandler {

    public record ArSubscription(
            boolean needsHud,
            boolean needsTracker,
            double radius,
            String targetType,
            List<String> hudPaths,
            List<String> trackerPaths
    ) {}

    private static final int MAX_VALUE_LENGTH = 256;

    private static final Map<UUID, ArSubscription> SUBSCRIPTIONS = new ConcurrentHashMap<>();
    // 前回送信した完全な同期データ状態を保持（Diff計算用）
    private static final Map<UUID, Map<String, Object>> LAST_SYNC_STATE = new ConcurrentHashMap<>();

    private static volatile boolean initialized = false;

    private ServerTargetSyncHandler() {}

    /**
     * tick リスナーを登録する。
     * 以前は static イニシャライザで、クラスが最初に触られた時点に登録が依存していた。
     */
    public static synchronized void init() {
        if (initialized) return;
        NeoForge.EVENT_BUS.addListener(ServerTargetSyncHandler::onPlayerTick);
        initialized = true;
    }

    public static void setSubscription(ServerPlayer player, boolean needsHud, boolean needsTracker, double radius, String targetType, List<String> hudPaths, List<String> trackerPaths) {
        if (!needsHud && !needsTracker) {
            SUBSCRIPTIONS.remove(player.getUUID());
            LAST_SYNC_STATE.remove(player.getUUID());
            return;
        }
        SUBSCRIPTIONS.put(player.getUUID(), new ArSubscription(
                needsHud,
                needsTracker,
                Math.min(radius, 64.0),
                targetType,
                NbtPathFilter.sanitize(hudPaths),
                NbtPathFilter.sanitize(trackerPaths)));
    }

    public static void removePlayer(UUID uuid) {
        if (uuid != null) {
            SUBSCRIPTIONS.remove(uuid);
            LAST_SYNC_STATE.remove(uuid);
        }
    }

    /** サーバー停止時に全ての static 状態を捨てる（I-6）。 */
    static void clearAll() {
        SUBSCRIPTIONS.clear();
        LAST_SYNC_STATE.clear();
    }

    static int subscriptionCount() {
        return SUBSCRIPTIONS.size();
    }

    // ==========================================
    // tick
    // ==========================================

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % 5 != 0) return;

        ArSubscription sub = SUBSCRIPTIONS.get(player.getUUID());
        if (sub == null) return;

        Map<String, Object> currentPayload = new HashMap<>();

        if (sub.needsHud() && !sub.hudPaths().isEmpty()) {
            Map<String, Object> targetMap = extractLookTargetData(player, sub.hudPaths());
            if (targetMap != null && !targetMap.isEmpty()) {
                currentPayload.put("target", targetMap);
            }
        }

        if (sub.needsTracker() && !sub.trackerPaths().isEmpty()) {
            currentPayload.putAll(collectTrackerData(player, sub));
        }

        Map<String, Object> lastPayload = LAST_SYNC_STATE.getOrDefault(player.getUUID(), new HashMap<>());
        Map<String, Object> diffPayload = new HashMap<>();

        for (Map.Entry<String, Object> entry : currentPayload.entrySet()) {
            if (!Objects.equals(entry.getValue(), lastPayload.get(entry.getKey()))) {
                diffPayload.put(entry.getKey(), entry.getValue());
            }
        }
        for (String key : lastPayload.keySet()) {
            if (!currentPayload.containsKey(key)) {
                diffPayload.put(key, null); // 削除された要素の伝達
            }
        }

        // 差分がある場合のみ送信（通信量とクライアント負荷を大幅に削減）
        if (!diffPayload.isEmpty()) {
            LAST_SYNC_STATE.put(player.getUUID(), currentPayload);
            // ar_sync は isFull=false（差分パッチ）として送ることで、クライアント側も差分マージが可能になる
            ServerPacketSender.send(player, new PubSubUpdateS2CPacket("ar_sync", false, diffPayload));
        }
    }

    private static Map<String, Object> collectTrackerData(ServerPlayer player, ArSubscription sub) {
        Map<String, Object> trackerMap = new HashMap<>();
        AABB box = player.getBoundingBox().inflate(sub.radius());
        Class<? extends LivingEntity> targetClass = getTargetClass(sub.targetType());

        for (LivingEntity le : player.level().getEntitiesOfClass(targetClass, box)) {
            if (le == player) continue;
            CompoundTag nbt = new CompoundTag();
            le.saveWithoutId(nbt);
            Map<String, Object> entityData = extractPaths(nbt, sub.trackerPaths(), le instanceof Player);
            if (!entityData.isEmpty()) {
                trackerMap.put(String.valueOf(le.getId()), entityData);
            }
        }
        return trackerMap.isEmpty() ? Map.of() : Map.of("tracker_data", trackerMap);
    }

    private static Map<String, Object> extractLookTargetData(ServerPlayer player, List<String> paths) {
        double reach = 20.0;
        Vec3 eyePos = player.getEyePosition();
        Vec3 viewVec = player.getViewVector(1.0F);
        Vec3 endPos = eyePos.add(viewVec.scale(reach));

        AABB searchBox = player.getBoundingBox().expandTowards(viewVec.scale(reach)).inflate(1.0D);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(player, eyePos, endPos, searchBox, e -> !e.isSpectator() && e.isPickable(), reach * reach);

        if (entityHit != null) {
            Entity entity = entityHit.getEntity();
            CompoundTag nbt = new CompoundTag();
            entity.saveWithoutId(nbt);
            return extractPaths(nbt, paths, entity instanceof Player);
        }

        HitResult blockHit = player.pick(reach, 1.0F, false);
        if (blockHit.getType() == HitResult.Type.BLOCK && blockHit instanceof BlockHitResult bhr) {
            BlockPos pos = bhr.getBlockPos();
            BlockEntity be = player.level().getBlockEntity(pos);
            if (be != null) {
                // saveWithFullMetadata は id と座標を足すだけで、読み出し範囲は許可リストが決める。
                // 余計なキーを載せない saveWithoutMetadata を使う。
                CompoundTag nbt = be.saveWithoutMetadata(player.registryAccess());
                return extractPaths(nbt, paths, false);
            }
        }
        return null;
    }

    /**
     * 許可されたパスのスカラー値だけを取り出す。
     * CompoundTag / ListTag に当たった場合は何も返さない（部分木ダンプの防止）。
     */
    private static Map<String, Object> extractPaths(CompoundTag root, List<String> paths, boolean playerTarget) {
        Map<String, Object> result = new HashMap<>();
        Set<String> rejected = null;
        for (String path : paths) {
            if (!NbtPathFilter.isRootAllowed(path, playerTarget)) {
                if (rejected == null) rejected = new HashSet<>();
                rejected.add(path);
                continue;
            }
            Tag value = resolve(root, path);
            if (value instanceof NumericTag || value instanceof StringTag) {
                String asString = value.getAsString();
                if (asString.length() > MAX_VALUE_LENGTH) asString = asString.substring(0, MAX_VALUE_LENGTH);
                result.put(path, asString);
            }
        }
        if (rejected != null) {
            EnderFrameLib.LOGGER.debug("許可されていない NBT パスを無視しました: {}", rejected);
        }
        return result;
    }

    private static Tag resolve(CompoundTag root, String path) {
        Tag current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof CompoundTag ct && ct.contains(part)) {
                current = ct.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private static Class<? extends LivingEntity> getTargetClass(String targetType) {
        if ("player".equalsIgnoreCase(targetType)) return Player.class;
        else if ("monster".equalsIgnoreCase(targetType)) return Monster.class;
        else if ("animal".equalsIgnoreCase(targetType)) return Animal.class;
        return LivingEntity.class;
    }
}
