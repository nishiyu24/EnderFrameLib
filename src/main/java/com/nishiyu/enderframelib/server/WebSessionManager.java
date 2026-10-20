package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.server.ServerPubSubManager.ServerPacketSender;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.api.WebActionReceiver;
import com.nishiyu.enderframelib.api.WebSessionProvider;
import com.nishiyu.enderframelib.api.WebSessionHandle;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.network.DomMutationRecord.HtmlNodeSerializer;
import com.nishiyu.enderframelib.network.EnderFrameNetwork.WebSyncS2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;

/**
 * サーバー側セッションの登録簿・配信・クライアント要求の検証。
 *
 * <p>公開 API は {@link com.nishiyu.enderframelib.api.EnderFrameAPI} 側。
 * ここはその実装で、クライアントから届く値は全て「検証前の入力」として扱う。</p>
 */
@ApiStatus.Internal
public class WebSessionManager {

    /** セッション ID の文字種。クライアントから届いた ID をそのままキーにしないための最低条件。 */
    private static final Pattern VALID_SESSION_ID = Pattern.compile("[A-Za-z0-9_.:,/\\-]{1,128}");
    /** アクション名の文字種。 */
    private static final Pattern VALID_ACTION_NAME = Pattern.compile("[A-Za-z0-9_.:/\\-]{1,128}");

    /** 閲覧を許す距離（ワールド内画面は離れた場所から見えるので操作距離より広い）。 */
    private static final double VIEW_RANGE_SQR = 48.0 * 48.0;
    /**
     * 操作を許す距離。
     * セッション ID が指すのはマルチブロック画面の master 1 点なので、
     * プレイヤーの操作距離（約 5）＋ 大きな画面の master からの距離分をマージンに取る。
     * 以前は 64 ブロック（distSqr &lt;= 4096）で、見えない画面すら操作できた。
     */
    private static final double INTERACT_RANGE_SQR = 12.0 * 12.0;

    private static final int MAX_STANDALONE_SESSIONS = 256;
    private static final long SESSION_IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    private static final int MAX_ELEMENT_ID_LENGTH = 64;
    private static final int MAX_VALUE_LENGTH = 4096;

    private static final Set<WebSessionProvider> PROVIDERS = new CopyOnWriteArraySet<>();
    private static final Map<String, WebSession> STANDALONE_SESSIONS = new ConcurrentHashMap<>();

    /** 特定のセッションを現在閲覧・購読しているプレイヤーの追跡用マップ */
    private static final Map<String, Set<ServerPlayer>> SESSION_VIEWERS = new ConcurrentHashMap<>();

    /**
     * プレイヤー専用に開いたセッションの所有者。
     * 所有者が居るセッションは、その本人以外が同期要求もアクション送出もできない（I-2）。
     */
    private static final Map<String, UUID> SESSION_OWNERS = new ConcurrentHashMap<>();

    private static volatile boolean initialized = false;

    /**
     * サーバー側でも DOM をパースできるようにフックを張る。
     * 以前は static イニシャライザで、クラスが最初に触られた時点に依存していた。
     */
    public static synchronized void init() {
        if (initialized) return;
        DomUtils.innerHtmlParser = UiDocumentCompiler.INNER_HTML_PARSER;
        initialized = true;
    }

    public static void registerProvider(WebSessionProvider provider) {
        if (provider != null) PROVIDERS.add(provider);
    }

    public static void unregisterProvider(WebSessionProvider provider) {
        PROVIDERS.remove(provider);
    }

    // ==========================================
    // セッションの生成・破棄
    // ==========================================

    public static WebSession getOrCreateStandaloneSession(String sessionId, int width, int height) {
        if (!isValidSessionId(sessionId)) {
            throw new IllegalArgumentException("Invalid session id: " + sessionId);
        }
        WebSession existing = STANDALONE_SESSIONS.get(sessionId);
        if (existing == null) {
            evictIfOverCapacity();
            existing = STANDALONE_SESSIONS.computeIfAbsent(sessionId, id -> {
                WebSession s = new WebSession(id, WebSessionManager::broadcastDom);
                s.updateSize(width, height);
                return s;
            });
        }
        existing.updateSize(width, height);
        existing.markAccessed();
        return existing;
    }

    /** 既存セッションのみを返す（クライアント要求で新規作成しないための入口）。 */
    @Nullable
    public static WebSession findStandaloneSession(String sessionId) {
        if (sessionId == null) return null;
        return STANDALONE_SESSIONS.get(sessionId);
    }

    public static void removeStandaloneSession(String sessionId) {
        WebSession session = STANDALONE_SESSIONS.remove(sessionId);
        if (session != null) session.clear();
        SESSION_VIEWERS.remove(sessionId);
        SESSION_OWNERS.remove(sessionId);
    }

    /** セッションをプレイヤー専用にする。HUD や個別に開いた画面はこれを通す。 */
    public static void setSessionOwner(String sessionId, ServerPlayer owner) {
        if (sessionId == null || owner == null) return;
        SESSION_OWNERS.put(sessionId, owner.getUUID());
    }

    public static void clearSessionOwner(String sessionId) {
        if (sessionId != null) SESSION_OWNERS.remove(sessionId);
    }

    public static boolean isValidSessionId(String sessionId) {
        return sessionId != null && VALID_SESSION_ID.matcher(sessionId).matches();
    }

    /** 推測しにくいセッション ID を作る（プレイヤー個別画面向け）。 */
    public static String newSessionId(String prefix) {
        String safePrefix = (prefix == null || prefix.isEmpty()) ? "s" : prefix;
        return safePrefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void evictIfOverCapacity() {
        if (STANDALONE_SESSIONS.size() < MAX_STANDALONE_SESSIONS) return;
        long now = System.currentTimeMillis();
        // まず期限切れを落とし、それでも空かなければ最も古いものを落とす
        STANDALONE_SESSIONS.entrySet().removeIf(e -> {
            boolean idle = (now - e.getValue().lastAccessMillis()) > SESSION_IDLE_TIMEOUT_MS
                    && !hasViewers(e.getKey());
            if (idle) {
                SESSION_VIEWERS.remove(e.getKey());
                SESSION_OWNERS.remove(e.getKey());
            }
            return idle;
        });
        while (STANDALONE_SESSIONS.size() >= MAX_STANDALONE_SESSIONS) {
            String oldest = STANDALONE_SESSIONS.entrySet().stream()
                    .filter(e -> !hasViewers(e.getKey()))
                    .min(Comparator.comparingLong(e -> e.getValue().lastAccessMillis()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                EnderFrameLib.LOGGER.warn("スタンドアロンセッションが上限 {} に達し、全てに閲覧者が居るため追い出せません。",
                        MAX_STANDALONE_SESSIONS);
                return;
            }
            EnderFrameLib.LOGGER.debug("アイドルなスタンドアロンセッションを追い出しました: {}", oldest);
            removeStandaloneSession(oldest);
        }
    }

    /** アイドルなセッションの定期掃除（サーバー tick から呼ばれる）。 */
    static void evictIdleSessions() {
        long now = System.currentTimeMillis();
        STANDALONE_SESSIONS.entrySet().removeIf(e -> {
            if (hasViewers(e.getKey())) return false;
            if ((now - e.getValue().lastAccessMillis()) <= SESSION_IDLE_TIMEOUT_MS) return false;
            SESSION_VIEWERS.remove(e.getKey());
            SESSION_OWNERS.remove(e.getKey());
            return true;
        });
    }

    private static boolean hasViewers(String sessionId) {
        Set<ServerPlayer> viewers = SESSION_VIEWERS.get(sessionId);
        return viewers != null && !viewers.isEmpty();
    }

    // ==========================================
    // 閲覧者
    // ==========================================

    public static void addViewer(String sessionId, ServerPlayer player) {
        if (sessionId == null || player == null) return;
        SESSION_VIEWERS.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>()).add(player);
    }

    public static void removeViewer(String sessionId, ServerPlayer player) {
        if (sessionId == null || player == null) return;
        Set<ServerPlayer> viewers = SESSION_VIEWERS.get(sessionId);
        if (viewers != null) {
            viewers.remove(player);
            if (viewers.isEmpty()) SESSION_VIEWERS.remove(sessionId);
        }
    }

    public static void removePlayerAll(ServerPlayer player) {
        if (player == null) return;
        SESSION_VIEWERS.values().forEach(v -> v.remove(player));
        SESSION_VIEWERS.entrySet().removeIf(e -> e.getValue().isEmpty());

        // 本人専用に開いたセッションはログアウト・ディメンション移動で畳む（I-6）
        UUID uuid = player.getUUID();
        SESSION_OWNERS.entrySet().stream()
                .filter(e -> uuid.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(WebSessionManager::removeStandaloneSession);
    }

    // ==========================================
    // アクセス検証（I-2 / I-3）
    // ==========================================

    /**
     * {@code player} がこのセッションを閲覧・操作できるか。
     *
     * @param interacting 操作（アクション送出）なら true、閲覧（同期要求）なら false
     */
    private static boolean canAccess(ServerPlayer player, String sessionId, boolean interacting) {
        if (player == null || !isValidSessionId(sessionId)) return false;
        if (player.hasDisconnected()) return false;

        UUID owner = SESSION_OWNERS.get(sessionId);
        if (owner != null && !owner.equals(player.getUUID())) {
            return false;
        }

        if (sessionId.startsWith("block:")) {
            BlockPos pos = parseBlockSessionPos(sessionId);
            if (pos == null) return false;
            if (player.level() == null || !player.level().isLoaded(pos)) return false;
            double limit = interacting ? INTERACT_RANGE_SQR : VIEW_RANGE_SQR;
            return player.blockPosition().distSqr(pos) <= limit;
        }

        return true;
    }

    /** {@code "block:x,y,z"} 形式のセッション ID から座標を取り出す。 */
    @Nullable
    static BlockPos parseBlockSessionPos(String sessionId) {
        if (sessionId == null || !sessionId.startsWith("block:")) return null;
        String[] coords = sessionId.substring(6).split(",");
        if (coords.length < 3) return null;
        try {
            return new BlockPos(
                    Integer.parseInt(coords[0].trim()),
                    Integer.parseInt(coords[1].trim()),
                    Integer.parseInt(coords[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==========================================
    // 配信
    // ==========================================

    public static CompoundTag packDom(WebSession session) {
        CompoundTag tag = new CompoundTag();
        if (session != null && session.getDocument() != null && session.getDocument().root != null) {
            tag.put("DomTree", HtmlNodeSerializer.serialize(session.getDocument().root));
            tag.putString("CssString", session.getDocument().authorCss != null ? session.getDocument().authorCss : "");
            tag.putInt("RootW", session.getWidth());
            tag.putInt("RootH", session.getHeight());
        } else {
            tag.putBoolean("IsEmpty", true);
        }
        return tag;
    }

    public static void broadcastDom(WebSession session) {
        if (session == null) return;
        CompoundTag tag = null;
        for (ServerPlayer player : viewersOf(session.getSessionId())) {
            if (tag == null) tag = packDom(session);
            ServerPacketSender.send(player, new WebSyncS2CPacket(session.getSessionId(), "dom_sync", tag));
        }
    }

    public static void broadcastMutations(String sessionId, java.util.List<com.nishiyu.enderframelib.network.DomMutationRecord> mutations) {
        if (mutations == null || mutations.isEmpty()) return;
        ListTag list = new ListTag();
        for (com.nishiyu.enderframelib.network.DomMutationRecord mut : mutations) {
            list.add(mut.toTag());
        }
        CompoundTag payload = new CompoundTag();
        payload.put("Mutations", list);
        for (ServerPlayer player : viewersOf(sessionId)) {
            ServerPacketSender.send(player, new WebSyncS2CPacket(sessionId, "dom_mutations", payload));
        }
    }

    public static void broadcastMutation(String sessionId, com.nishiyu.enderframelib.network.DomMutationRecord mutation) {
        if (mutation == null) return;
        broadcastMutations(sessionId, java.util.List.of(mutation));
    }


    /**
     * 送信対象の閲覧者。切断済みのプレイヤーはここで取り除く
     * （{@code ServerPubSubManager.publish} には元々あったガードを揃えた）。
     */
    private static Iterable<ServerPlayer> viewersOf(String sessionId) {
        Set<ServerPlayer> viewers = SESSION_VIEWERS.get(sessionId);
        if (viewers == null || viewers.isEmpty()) return Set.of();
        viewers.removeIf(ServerPlayer::hasDisconnected);
        if (viewers.isEmpty()) {
            SESSION_VIEWERS.remove(sessionId);
            return Set.of();
        }
        return viewers;
    }

    /**
     * クライアントの同期要求。
     * 閲覧者への登録は「そのセッションが存在し、かつ本人に見せて良い」と分かってから行う（I-7）。
     */
    public static void forceSyncToPlayer(ServerPlayer player, String sessionId) {
        if (!canAccess(player, sessionId, false)) {
            EnderFrameLib.LOGGER.debug("セッション同期要求を拒否しました: session={} player={}",
                    sessionId, player != null ? player.getGameProfile().getName() : "null");
            return;
        }

        WebSession standalone = STANDALONE_SESSIONS.get(sessionId);
        if (standalone != null) {
            standalone.markAccessed();
            addViewer(sessionId, player);
            ServerPacketSender.send(player, new WebSyncS2CPacket(sessionId, "dom_sync", packDom(standalone)));
            return;
        }

        WebSession resolved = resolveFromProviders(player, sessionId);
        if (resolved != null) {
            addViewer(sessionId, player);
            ServerPacketSender.send(player, new WebSyncS2CPacket(sessionId, "dom_sync", packDom(resolved)));
        }
    }

    /** プロバイダ経由の解決。プロバイダは信頼するが、本人への可視性だけは聞く。 */
    @Nullable
    private static WebSession resolveFromProviders(ServerPlayer player, String sessionId) {
        for (WebSessionProvider provider : PROVIDERS) {
            if (!provider.matchesSession(sessionId)) continue;
            if (!provider.allowsPlayer(player, sessionId)) continue;
            WebSessionHandle handle = provider.getSessionForClient(sessionId);
            if (handle instanceof WebSession session) {
                return session;
            }
            if (handle != null) {
                EnderFrameLib.LOGGER.warn(
                        "WebSessionProvider が未知の WebSessionHandle 実装を返しました（同期できません）: {}",
                        handle.getClass().getName());
            }
            return null;
        }
        return null;
    }

    public static void handleFetchRequest(ServerPlayer player, String sessionId, String requestId, String endpoint, String jsonPayload) {
        if (!canAccess(player, sessionId, true)) return;
        for (WebSessionProvider provider : PROVIDERS) {
            if (provider.matchesSession(sessionId)) {
                if (!provider.allowsPlayer(player, sessionId)) return;
                addViewer(sessionId, player);
                provider.onFetchRequest(player, sessionId, requestId, endpoint, jsonPayload);
                return;
            }
        }
    }

    // ==========================================
    // クライアントイベント
    // ==========================================

    public static void handleClientEvent(ServerPlayer player, String sessionId, String elementId, String eventName, String actionName, String value) {
        if (player == null || player.hasDisconnected()) return;
        if (elementId != null && elementId.length() > MAX_ELEMENT_ID_LENGTH) return;
        if (value != null && value.length() > MAX_VALUE_LENGTH) {
            EnderFrameLib.LOGGER.debug("クライアントイベントの値が長すぎます ({} 文字): session={}", value.length(), sessionId);
            return;
        }

        ParsedAction action = parseAction(actionName, value);
        if (action == null) {
            EnderFrameLib.LOGGER.debug("アクション名の文字種が不正です: {}", actionName);
            return;
        }

        // セッション ID を持たない画面（Menu に紐づくだけの下流画面）には守る対象のセッションが無い。
        // 開いている Menu 自身の stillValid が唯一の権限判定になる
        if (sessionId == null || sessionId.isEmpty()) {
            dispatchToMenu(player, "", elementId, eventName, action);
            return;
        }

        if (!canAccess(player, sessionId, true)) {
            EnderFrameLib.LOGGER.debug("クライアントイベントを拒否しました（アクセス不可）: session={} action={}", sessionId, actionName);
            return;
        }

        WebSession standalone = STANDALONE_SESSIONS.get(sessionId);

        // サーバーが DOM を持っているセッションだけは「その画面が実際に出していたアクションか」を検証できる。
        // 画面 HTML がクライアント側にしか無いセッション（AbstractEnderContainerScreen など）は
        // 検証材料が無いので、レシーバ側で自前に確認する必要がある。
        if (standalone != null && standalone.getDocument() != null
                && !action.declared().isEmpty()
                && !standalone.declaresAction(action.declared())) {
            EnderFrameLib.LOGGER.debug("送出 DOM に無いアクション名を拒否しました: session={} action={}", sessionId, actionName);
            return;
        }

        addViewer(sessionId, player);
        if (standalone != null) {
            standalone.markAccessed();
            if (isValueEvent(eventName)) {
                applyClientValue(standalone, elementId, value);
            }
        }

        dispatchToMenu(player, sessionId, elementId, eventName, action);
        dispatchToBlockEntity(player, sessionId, elementId, eventName, action);

        for (WebSessionProvider provider : PROVIDERS) {
            if (provider.matchesSession(sessionId)) {
                if (!provider.allowsPlayer(player, sessionId)) return;
                provider.onClientEvent(player, sessionId, elementId, eventName, actionName, value);
                return;
            }
        }
    }

    /**
     * クライアントが送ってきたアクション名の解釈。
     *
     * @param declared 送出 DOM と突き合わせる名前（{@code "server:"} を落としたもの）
     * @param name     レシーバに渡す名前（{@code "name:arg"} の name 部分）
     * @param param    レシーバに渡す値
     */
    private record ParsedAction(String declared, String name, String param) {}

    /** 文字種が不正なら null を返す。 */
    @Nullable
    private static ParsedAction parseAction(String actionName, String value) {
        String declared = actionName == null ? "" : actionName;
        if (declared.startsWith("server:")) declared = declared.substring(7);

        String name = declared;
        String param = value != null ? value : "";
        int colon = name.indexOf(':');
        if (colon >= 0) {
            if (param.isEmpty()) param = name.substring(colon + 1);
            name = name.substring(0, colon);
        }
        if (!name.isEmpty() && !VALID_ACTION_NAME.matcher(name).matches()) return null;
        return new ParsedAction(declared, name, param);
    }

    private static CompoundTag buildRpcPayload(String sessionId, String elementId, String eventName, ParsedAction action) {
        CompoundTag rpcPayload = new CompoundTag();
        rpcPayload.putString("SessionId", sessionId != null ? sessionId : "");
        rpcPayload.putString("ElementId", elementId != null ? elementId : "");
        rpcPayload.putString("EventName", eventName != null ? eventName : "");
        rpcPayload.putString("Value", action.param());
        return rpcPayload;
    }

    private static void dispatchToMenu(ServerPlayer player, String sessionId, String elementId, String eventName, ParsedAction action) {
        if (!(player.containerMenu instanceof WebActionReceiver menuReceiver)) return;
        if (!player.containerMenu.stillValid(player)) return;
        try {
            menuReceiver.onWebServerAction(player, action.name(), action.param(),
                    buildRpcPayload(sessionId, elementId, eventName, action));
        } catch (Exception e) {
            EnderFrameLib.LOGGER.warn("Menu のアクション処理が例外を投げました: action={}", action.name(), e);
        }
    }

    private static boolean isValueEvent(String eventName) {
        return "oninput".equalsIgnoreCase(eventName) || "onchange".equalsIgnoreCase(eventName);
    }

    /**
     * クライアントが報告した入力値をサーバー DOM に書き戻す。
     * 書き戻し先は「送出した DOM に居る入力要素」に限る（I-3）。
     */
    private static void applyClientValue(WebSession session, String elementId, String value) {
        if (elementId == null || elementId.isEmpty()) return;
        if (!session.allowsValueWrite(elementId)) {
            EnderFrameLib.LOGGER.debug("入力要素でない対象への値の書き戻しを拒否しました: {}", elementId);
            return;
        }
        UiDocumentCompiler.Document doc = session.getDocument();
        if (doc == null || doc.root == null) return;
        HtmlNode target = doc.root.getElementById(elementId);
        if (target == null) return;

        String type = target.attrs.getOrDefault("type", "");
        if ("radio".equalsIgnoreCase(type)) {
            if (isChecked(value)) DomUtils.selectRadioButton(doc.root, target);
        } else if ("checkbox".equalsIgnoreCase(type)) {
            boolean checked = isChecked(value);
            DomUtils.updateAttribute(target, "checked", checked ? "true" : null, !checked);
        } else {
            DomUtils.updateAttribute(target, "value", value != null ? value : "", false);
        }
    }

    private static boolean isChecked(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    /**
     * {@code block:x,y,z} セッションの BlockEntity へのディスパッチ。
     * 距離は {@link #canAccess} で既に操作距離まで絞られている。
     */
    private static void dispatchToBlockEntity(ServerPlayer player, String sessionId, String elementId, String eventName, ParsedAction action) {
        BlockPos pos = parseBlockSessionPos(sessionId);
        if (pos == null || player.level() == null) return;
        BlockEntity be = player.level().getBlockEntity(pos);
        if (!(be instanceof WebActionReceiver beReceiver)) return;

        // セッション ID が指す座標と BlockEntity の実座標が一致していることを確かめる
        if (!be.getBlockPos().equals(pos)) return;

        try {
            beReceiver.onWebServerAction(player, action.name(), action.param(),
                    buildRpcPayload(sessionId, elementId, eventName, action));
        } catch (Exception e) {
            EnderFrameLib.LOGGER.warn("BlockEntity のアクション処理が例外を投げました: pos={} action={}", pos, action.name(), e);
        }
    }

    // ==========================================
    // ライフサイクル
    // ==========================================

    /** サーバー停止時に全ての static 状態を捨てる（I-6）。 */
    static void clearAll() {
        // clear() ではなく discard()。停止中に空の DOM を配信しようとすると、
        // 畳まれた接続への送信例外が ServerStoppingEvent のリスナから抜けてしまう
        STANDALONE_SESSIONS.values().forEach(WebSession::discard);
        STANDALONE_SESSIONS.clear();
        SESSION_VIEWERS.clear();
        SESSION_OWNERS.clear();
        PROVIDERS.clear();
    }

    static int standaloneSessionCount() {
        return STANDALONE_SESSIONS.size();
    }

    static int viewerMapSize() {
        return SESSION_VIEWERS.size();
    }

    static int providerCount() {
        return PROVIDERS.size();
    }
}
