package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.api.EnderFrameAPI;
import com.nishiyu.enderframelib.network.PubSubUpdateS2CPacket;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * チャンネル単位の pub/sub。
 *
 * <p>購読要求はクライアントから来るので、チャンネル名の検証・購読数の上限・
 * アクセス制御を通す（I-4）。{@code player:<uuid>} で始まるチャンネルは
 * その本人だけが購読できる名前空間として予約している。</p>
 */
@ApiStatus.Internal
public class ServerPubSubManager {

    /** チャンネル名の文字種。 */
    private static final Pattern VALID_CHANNEL = Pattern.compile("[A-Za-z0-9_.:/\\-]{1,128}");
    /** プレイヤー専用チャンネルの接頭辞。 */
    private static final String PLAYER_SCOPE_PREFIX = "player:";
    /** サーバーが自分で配信するために予約しているチャンネル（クライアントからの購読は要らない）。 */
    private static final Set<String> RESERVED_CHANNELS = Set.of("ar_sync");

    private static final int MAX_CHANNELS_PER_PLAYER = 64;
    private static final int MAX_CHANNELS = 256;
    private static final int MAX_ENTRIES_PER_CHANNEL = 256;

    private static final Map<String, Map<String, Object>> channelData = new ConcurrentHashMap<>();
    private static final Map<String, Set<ServerPlayer>> subscribers = new ConcurrentHashMap<>();
    /** publish された順。上限を超えたチャンネルを追い出すために持つ。 */
    private static final Map<String, Long> channelLastPublish = new ConcurrentHashMap<>();
    /** 購読時に全状態を再生しないチャンネル。 */
    private static final Set<String> noReplayChannels = ConcurrentHashMap.newKeySet();

    private static final List<EnderFrameAPI.SubscriptionCheck> ACCESS_CHECKS = new CopyOnWriteArrayList<>();

    /**
     * 購読の可否を決めるフックを登録する。
     * 登録順に全て呼ばれ、1 つでも拒否したら購読は成立しない。
     */
    public static void addAccessCheck(EnderFrameAPI.SubscriptionCheck check) {
        if (check != null) ACCESS_CHECKS.add(check);
    }

    public static void subscribe(ServerPlayer player, String channel) {
        if (player == null || !isValidChannel(channel)) return;
        if (!canSubscribe(player, channel)) {
            EnderFrameLib.LOGGER.debug("購読を拒否しました: channel={} player={}", channel, player.getGameProfile().getName());
            return;
        }
        if (countSubscriptions(player) >= MAX_CHANNELS_PER_PLAYER && !isSubscribed(player, channel)) {
            EnderFrameLib.LOGGER.debug("購読数の上限 {} に達しています: player={}",
                    MAX_CHANNELS_PER_PLAYER, player.getGameProfile().getName());
            return;
        }

        subscribers.computeIfAbsent(channel, k -> new CopyOnWriteArraySet<>()).add(player);

        Map<String, Object> current = channelData.get(channel);
        if (current != null && !current.isEmpty() && !noReplayChannels.contains(channel)) {
            // 全状態の再生は購読が成立した本人にだけ、かつ上限付きで送る
            ServerPacketSender.send(player, new PubSubUpdateS2CPacket(channel, true, limited(current)));
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
        if (!isValidChannel(channel) || newData == null) return;

        evictChannelsIfNeeded(channel);
        Map<String, Object> current = channelData.computeIfAbsent(channel, k -> new ConcurrentHashMap<>());
        channelLastPublish.put(channel, System.currentTimeMillis());
        Map<String, Object> diff = new HashMap<>();

        for (Map.Entry<String, Object> entry : newData.entrySet()) {
            String key = entry.getKey();
            Object newVal = entry.getValue();
            if (!current.containsKey(key) && current.size() >= MAX_ENTRIES_PER_CHANNEL) {
                EnderFrameLib.LOGGER.debug("チャンネル {} のエントリ上限 {} を超えたキーを無視しました: {}",
                        channel, MAX_ENTRIES_PER_CHANNEL, key);
                continue;
            }
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
                subs.removeIf(ServerPlayer::hasDisconnected);
                for (ServerPlayer player : subs) {
                    ServerPacketSender.send(player, packet);
                }
            }
        }
    }

    public static void updateArSubscription(ServerPlayer player, boolean needsHud, boolean needsTracker, double radius, String targetType, List<String> hudPaths, List<String> trackerPaths) {
        ServerTargetSyncHandler.setSubscription(player, needsHud, needsTracker, radius, targetType, hudPaths, trackerPaths);
    }

    // ==========================================
    // ACL
    // ==========================================

    public static boolean isValidChannel(String channel) {
        return channel != null && !channel.isEmpty() && VALID_CHANNEL.matcher(channel).matches();
    }

    private static boolean canSubscribe(ServerPlayer player, String channel) {
        if (RESERVED_CHANNELS.contains(channel)) return false;

        // player:<uuid>[:...] は本人専用の名前空間
        if (channel.startsWith(PLAYER_SCOPE_PREFIX)) {
            String rest = channel.substring(PLAYER_SCOPE_PREFIX.length());
            int sep = rest.indexOf(':');
            String owner = sep >= 0 ? rest.substring(0, sep) : rest;
            try {
                if (!UUID.fromString(owner).equals(player.getUUID())) return false;
            } catch (IllegalArgumentException e) {
                // UUID でなければ名前での指定として扱う
                if (!owner.equalsIgnoreCase(player.getGameProfile().getName())) return false;
            }
        }

        for (EnderFrameAPI.SubscriptionCheck check : ACCESS_CHECKS) {
            try {
                if (!check.allows(player, channel)) return false;
            } catch (Exception e) {
                EnderFrameLib.LOGGER.warn("購読チェックが例外を投げたため購読を拒否しました: channel={}", channel, e);
                return false;
            }
        }
        return true;
    }

    private static boolean isSubscribed(ServerPlayer player, String channel) {
        Set<ServerPlayer> subs = subscribers.get(channel);
        return subs != null && subs.contains(player);
    }

    private static int countSubscriptions(ServerPlayer player) {
        int count = 0;
        for (Set<ServerPlayer> subs : subscribers.values()) {
            if (subs.contains(player)) count++;
        }
        return count;
    }

    /** 再生する全状態のエントリ数を上限で切る。 */
    private static Map<String, Object> limited(Map<String, Object> source) {
        if (source.size() <= MAX_ENTRIES_PER_CHANNEL) return new HashMap<>(source);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            if (out.size() >= MAX_ENTRIES_PER_CHANNEL) break;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** チャンネル数の上限。購読者が居ない、最も古い publish のチャンネルから捨てる。 */
    private static void evictChannelsIfNeeded(String incoming) {
        if (channelData.containsKey(incoming) || channelData.size() < MAX_CHANNELS) return;
        channelData.keySet().stream()
                .filter(c -> {
                    Set<ServerPlayer> subs = subscribers.get(c);
                    return subs == null || subs.isEmpty();
                })
                .min((a, b) -> Long.compare(
                        channelLastPublish.getOrDefault(a, 0L),
                        channelLastPublish.getOrDefault(b, 0L)))
                .ifPresentOrElse(oldest -> {
                    channelData.remove(oldest);
                    channelLastPublish.remove(oldest);
                    EnderFrameLib.LOGGER.debug("購読者の居ない pub/sub チャンネルを追い出しました: {}", oldest);
                }, () -> EnderFrameLib.LOGGER.warn(
                        "pub/sub チャンネルが上限 {} に達し、全てに購読者が居るため追い出せません。", MAX_CHANNELS));
    }

    // ==========================================
    // ライフサイクル
    // ==========================================

    /** サーバー停止時に全ての static 状態を捨てる（I-6）。 */
    static void clearAll() {
        channelData.clear();
        subscribers.clear();
        channelLastPublish.clear();
        noReplayChannels.clear();
        ACCESS_CHECKS.clear();
    }

    static int channelCount() {
        return channelData.size();
    }

    static Set<String> channelNames() {
        return Set.copyOf(channelData.keySet());
    }

    static int subscriberMapSize() {
        return subscribers.size();
    }

    /**
     * サーバー → クライアントの送信口。サーバー側の送信はすべてここを通す。
     *
     * <p>{@code PacketDistributor.sendToPlayer} は、相手の接続がそのチャンネルを
     * ネゴシエートしていないと {@link UnsupportedOperationException} を投げる。
     * これは Mod を入れていないクライアント（Vanilla 接続）、接続途中のプレイヤー、
     * ゲームテストの擬似プレイヤーで実際に起きる。</p>
     *
     * <p>送れない相手が 1 人いただけで例外が呼び出し元まで抜けるため、
     * 以前は全員への配信が途中で止まり、さらにサーバー停止時の後片付け
     * （{@code ServerStoppingEvent} → {@code clearAll}）が例外で中断して
     * サーバーがクラッシュしていた。送信は「できる相手にだけ送る」操作であって、
     * 失敗が呼び出し元の処理を壊してよい操作ではない。</p>
     */
    @ApiStatus.Internal
    public static final class ServerPacketSender {

        private ServerPacketSender() {}

        /**
         * 送れる相手にだけ送る。送れない理由（切断済み・チャンネル未ネゴシエート）は
         * 黙って捨てるのではなく debug ログに残す。
         *
         * @return 実際に送ったら true
         */
        public static boolean send(@Nullable ServerPlayer player, @Nullable CustomPacketPayload payload) {
            if (player == null || payload == null) return false;
            if (player.hasDisconnected()) return false;

            ServerGamePacketListenerImpl connection = player.connection;
            if (connection == null) return false;
            if (!connection.hasChannel(payload)) {
                EnderFrameLib.LOGGER.debug("ペイロード {} を送れません（チャンネル未対応）: player={}",
                        payload.type().id(), player.getGameProfile().getName());
                return false;
            }

            try {
                PacketDistributor.sendToPlayer(player, payload);
                return true;
            } catch (RuntimeException e) {
                // ネゴシエーション後に接続が落ちた等。1 人の失敗で配信全体を止めない
                EnderFrameLib.LOGGER.debug("ペイロード {} の送信に失敗しました: player={}",
                        payload.type().id(), player.getGameProfile().getName(), e);
                return false;
            }
        }
    }
}
