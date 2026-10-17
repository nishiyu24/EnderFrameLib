package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.network.s2c.PubSubUpdateS2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServerTargetSyncHandler {

    public record ArSubscription(
            boolean needsHud,
            boolean needsTracker,
            double radius,
            String targetType,
            List<String> hudPaths,
            List<String> trackerPaths
    ) {}

    private static final Map<UUID, ArSubscription> SUBSCRIPTIONS = new ConcurrentHashMap<>();
    // 前回送信した完全な同期データ状態を保持（Diff計算用）
    private static final Map<UUID, Map<String, Object>> LAST_SYNC_STATE = new ConcurrentHashMap<>();

    static {
        NeoForge.EVENT_BUS.addListener(ServerTargetSyncHandler::onPlayerTick);
    }

    public static void setSubscription(ServerPlayer player, boolean needsHud, boolean needsTracker, double radius, String targetType, List<String> hudPaths, List<String> trackerPaths) {
        if (!needsHud && !needsTracker) {
            SUBSCRIPTIONS.remove(player.getUUID());
            LAST_SYNC_STATE.remove(player.getUUID());
        } else {
            SUBSCRIPTIONS.put(player.getUUID(), new ArSubscription(needsHud, needsTracker, Math.min(radius, 64.0), targetType, hudPaths, trackerPaths));
        }
    }

    public static void removePlayer(UUID uuid) {
        if (uuid != null) {
            SUBSCRIPTIONS.remove(uuid);
            LAST_SYNC_STATE.remove(uuid);
        }
    }

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
            Map<String, Object> trackerMap = new HashMap<>();
            AABB box = player.getBoundingBox().inflate(sub.radius());
            Class<? extends LivingEntity> targetClass = getTargetClass(sub.targetType());

            for (LivingEntity le : player.level().getEntitiesOfClass(targetClass, box)) {
                if (le == player) continue;
                CompoundTag nbt = new CompoundTag();
                le.saveWithoutId(nbt);
                Map<String, Object> entityData = extractPaths(nbt, sub.trackerPaths());
                if (!entityData.isEmpty()) {
                    trackerMap.put(String.valueOf(le.getId()), entityData);
                }
            }
            if (!trackerMap.isEmpty()) {
                currentPayload.put("tracker_data", trackerMap);
            }
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
            PacketDistributor.sendToPlayer(player, new PubSubUpdateS2CPacket("ar_sync", false, diffPayload));
        }
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
            return extractPaths(nbt, paths);
        }

        HitResult blockHit = player.pick(reach, 1.0F, false);
        if (blockHit.getType() == HitResult.Type.BLOCK && blockHit instanceof BlockHitResult bhr) {
            BlockPos pos = bhr.getBlockPos();
            BlockEntity be = player.level().getBlockEntity(pos);
            if (be != null) {
                CompoundTag nbt = be.saveWithFullMetadata(player.registryAccess());
                return extractPaths(nbt, paths);
            }
        }
        return null;
    }

    private static Map<String, Object> extractPaths(CompoundTag root, List<String> paths) {
        Map<String, Object> result = new HashMap<>();
        for (String path : paths) {
            if (path == null || path.isEmpty()) continue;
            String cleanPath = path.startsWith("nbt.") ? path.substring(4) : path;
            String[] parts = cleanPath.split("\\.");
            Tag current = root;
            for (String part : parts) {
                if (current instanceof CompoundTag ct && ct.contains(part)) {
                    current = ct.get(part);
                } else {
                    current = null;
                    break;
                }
            }
            if (current != null) {
                result.put(cleanPath, current.getAsString());
            }
        }
        return result;
    }

    private static Class<? extends LivingEntity> getTargetClass(String targetType) {
        if ("player".equalsIgnoreCase(targetType)) return Player.class;
        else if ("monster".equalsIgnoreCase(targetType)) return Monster.class;
        else if ("animal".equalsIgnoreCase(targetType)) return Animal.class;
        return LivingEntity.class;
    }
}