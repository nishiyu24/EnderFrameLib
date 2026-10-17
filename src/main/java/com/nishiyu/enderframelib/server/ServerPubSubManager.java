package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.network.s2c.PubSubUpdateS2CPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class ServerPubSubManager {
    private static final Map<String, Map<String, Object>> channelData = new ConcurrentHashMap<>();
    private static final Map<String, Set<ServerPlayer>> subscribers = new ConcurrentHashMap<>();

    public static void subscribe(ServerPlayer player, String channel) {
        if (player == null || channel == null || channel.isEmpty()) return;
        subscribers.computeIfAbsent(channel, k -> new CopyOnWriteArraySet<>()).add(player);
        if (channelData.containsKey(channel)) {
            PacketDistributor.sendToPlayer(player, new PubSubUpdateS2CPacket(channel, true, channelData.get(channel)));
        }
    }

    public static void unsubscribe(ServerPlayer player, String channel) {
        if (player == null || channel == null || channel.isEmpty()) return;
        Set<ServerPlayer> subs = subscribers.get(channel);
        if (subs != null) {
            subs.remove(player);
            if (subs.isEmpty()) {
                subscribers.remove(channel);
            }
        }
    }

    public static void removePlayerAll(ServerPlayer player) {
        if (player == null) return;
        subscribers.values().forEach(subs -> subs.remove(player));
        subscribers.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public static void publish(String channel, Map<String, Object> newData) {
        if (channel == null || newData == null) return;
        Map<String, Object> current = channelData.computeIfAbsent(channel, k -> new ConcurrentHashMap<>());
        Map<String, Object> diff = new HashMap<>();

        for (Map.Entry<String, Object> entry : newData.entrySet()) {
            String key = entry.getKey();
            Object newVal = entry.getValue();
            if (!current.containsKey(key) || !Objects.equals(newVal, current.get(key))) {
                diff.put(key, newVal);
                if (newVal == null) current.remove(key);
                else current.put(key, newVal);
            }
        }

        for (String key : current.keySet()) {
            if (!newData.containsKey(key)) {
                diff.put(key, null);
                current.remove(key);
            }
        }

        if (!diff.isEmpty()) {
            Set<ServerPlayer> subs = subscribers.get(channel);
            if (subs != null && !subs.isEmpty()) {
                PubSubUpdateS2CPacket packet = new PubSubUpdateS2CPacket(channel, false, diff);
                for (ServerPlayer player : subs) {
                    if (!player.hasDisconnected()) {
                        PacketDistributor.sendToPlayer(player, packet);
                    }
                }
            }
        }
    }

    public static void updateArSubscription(ServerPlayer player, boolean needsHud, boolean needsTracker, double radius, String targetType, List<String> hudPaths, List<String> trackerPaths) {
        ServerTargetSyncHandler.setSubscription(player, needsHud, needsTracker, radius, targetType, hudPaths, trackerPaths);
    }
}