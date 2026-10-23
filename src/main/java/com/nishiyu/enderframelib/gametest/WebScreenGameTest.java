package com.nishiyu.enderframelib.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.api.EnderFrameAPI;
import com.nishiyu.enderframelib.api.EnderFrameClientAPI;
import com.nishiyu.enderframelib.api.WebDocument;
import com.nishiyu.enderframelib.api.WebSessionHandle;
import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.network.OpenUiS2CPacket;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.network.StateNbtConverter;
import com.nishiyu.enderframelib.server.EnderFrameServerState;
import com.nishiyu.enderframelib.server.state.UIStateImpl;
import com.nishiyu.enderframelib.server.state.UIStateManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * サーバー側のセッション寿命のゲームテスト。
 *
 * <p>{@code build.gradle} は 3 つの run 設定で {@code enabledGameTestNamespaces} を
 * 有効にしていたが、中身が 0 件だった。ここで検証するのは
 * 「画面を開く → サーバー側に文書が立つ → プレイヤーが落ちる → static マップが空に戻る」
 * という I-6（リーク）の修正そのもので、ユニットテストでは Minecraft が要るため届かず、
 * 手動確認では「空に戻ったこと」を見落としやすかった部分。</p>
 *
 * <p>描画そのものはクライアントが要るのでここでは検証できない（{@code runGameTestServer}
 * は専用サーバー）。サーバー側で確認できる「描画の手前」として、文書が実際に
 * パースされ、要素が id で引けることを見る。</p>
 *
 * <p>Headless State（H0〜H4）は、実物のブロックエンティティを置いて「開く → tick の差分 → 2 人目の閲覧者 →
 * アクション → ブロックの破壊で後始末」までを本物のサーバー tick で通し、パケットは本物のコーデックで往復させる
 * （{@link #headlessStateFollowsBlockEntityLifecycle}）。</p>
 *
 * <p>実行:</p>
 * <pre>./gradlew runGameTestServer</pre>
 *
 * <p>クライアントが要る確認（テンプレートの読み込み・入力の DOM イベント化・HUD の無送信）は、開発用の run
 * （{@code runClient} / {@code runServer}）でだけ有効になる {@link DevHarness} のコマンドで行う。</p>
 */
@GameTestHolder(EnderFrameLib.MODID)
@PrefixGameTestTemplate(false)
public final class WebScreenGameTest {

    /** テストが置かれる 3x1x3 の石床。中身は使わないが、ゲームテストは構造物を要求する。 */
    private static final String TEMPLATE = "empty_floor";

    // 同一バッチ内のテストは並列に走り、サーバー側の static 状態を共有してしまう。
    // ここで見たいのは「状態が空に戻るか」なので、1 テスト 1 バッチにして直列化する。
    // バッチはバッチ単位で順に実行されるため、前のテストの取りこぼしは
    // 次のテストの開始前チェックで検出される。
    private static final String BATCH_LIFECYCLE = "enderframe_lifecycle";
    private static final String BATCH_DOCUMENT = "enderframe_document";
    private static final String BATCH_HEADLESS = "enderframe_headless";
    private static final String BATCH_HEADLESS_REPLACE = "enderframe_headless_replace";

    /** 実機確認用のテンプレート（{@code assets/enderframelib/enderframe_ui/state_test.html}）。 */
    private static final String STATE_TEMPLATE = "enderframelib:state_test";

    private WebScreenGameTest() {}

    /**
     * 画面を開くとセッションが立ち、ログアウトで全ての static 状態が空に戻る。
     */
    @GameTest(template = TEMPLATE, batch = BATCH_LIFECYCLE, timeoutTicks = 200)
    public static void screenSessionIsDroppedOnLogout(GameTestHelper helper) {
        requireCleanState(helper, "テスト開始前");

        ServerPlayer player = mockPlayer(helper);
        String screenId = "script_" + player.getStringUUID();
        String hudId = "custom_hud_" + player.getStringUUID();

        EnderFrameAPI api = EnderFrameAPI.get();
        api.openScreen(player, null, "<div id='root'>hello</div>", 320, 200);
        api.setHud(player, null, "<div id='hud'>hud</div>");

        if (api.findSession(screenId) == null) {
            helper.fail("openScreen 後にセッションが見つかりません: " + screenId);
        }
        if (api.findSession(hudId) == null) {
            helper.fail("setHud 後に HUD セッションが見つかりません: " + hudId);
        }
        if (EnderFrameServerState.isEmpty()) {
            helper.fail("セッションを 2 つ開いたのに状態が空と報告されました: "
                    + EnderFrameServerState.describeState());
        }

        // PlayerLoggedOutEvent のリスナが呼ぶのと同じ経路。イベントを合成すると
        // 他の Mod のリスナまで巻き込むので、破棄の契約だけを直接叩く。
        EnderFrameServerState.onPlayerLogout(player);
        player.connection.disconnect(Component.literal("gametest done"));

        if (api.findSession(screenId) != null) {
            helper.fail("ログアウト後もセッションが残っています: " + screenId);
        }
        if (api.findSession(hudId) != null) {
            helper.fail("ログアウト後も HUD セッションが残っています: " + hudId);
        }
        requireCleanState(helper, "ログアウト後");

        helper.succeed();
    }

    /**
     * サーバー側で HTML が実際に文書になり、id で要素が引けて、patch が通る。
     * 描画の一歩手前までをここで押さえる。
     */
    @GameTest(template = TEMPLATE, batch = BATCH_DOCUMENT, timeoutTicks = 200)
    public static void screenHtmlBecomesADocument(GameTestHelper helper) {
        requireCleanState(helper, "テスト開始前");

        ServerPlayer player = mockPlayer(helper);
        EnderFrameAPI api = EnderFrameAPI.get();
        api.openScreen(player, null, """
                <style>#count { color: #ff0000; }</style>
                <p>Diamonds: <span id="count" class="value">0</span></p>
                """, 320, 200);

        WebSessionHandle session = api.findSession("script_" + player.getStringUUID());
        if (session == null) {
            helper.fail("openScreen 後にセッションが見つかりません");
            return;
        }

        WebDocument document = session.getDocument();
        if (!document.hasElement("count")) {
            helper.fail("id=count の要素が文書にありません（HTML がパースされていない）");
        }
        if (!"span".equals(document.tagOf("count"))) {
            helper.fail("id=count のタグが span ではありません: " + document.tagOf("count"));
        }
        if (!"value".equals(document.attributeOf("count", "class"))) {
            helper.fail("id=count の class が拾えていません: " + document.attributeOf("count", "class"));
        }
        if (!document.authorCss().contains("#count")) {
            helper.fail("インライン <style> が作者 CSS に取り込まれていません");
        }

        // 文書が差分更新できること。ここで落ちると patch 経路が壊れている。
        session.patchText("count", "42");
        session.patchAttribute("count", "data-ready", "1", false);
        if (!"1".equals(session.getDocument().attributeOf("count", "data-ready"))) {
            helper.fail("patchAttribute が文書に反映されていません");
        }

        EnderFrameServerState.onPlayerLogout(player);
        player.connection.disconnect(Component.literal("gametest done"));
        requireCleanState(helper, "ログアウト後");

        helper.succeed();
    }

    /**
     * Headless State をブロックエンティティの一生に沿って通す。tick ごとの差分は本物のサーバー tick
     * （{@code UIStateManager.tickAll}）が送り、パケットは本物のコーデックで往復させて名前に戻ることを見る。
     */
    @GameTest(template = TEMPLATE, batch = BATCH_HEADLESS, timeoutTicks = 200)
    public static void headlessStateFollowsBlockEntityLifecycle(GameTestHelper helper) {
        requireCleanState(helper, "テスト開始前");
        StateNbtConverter.clearReceivedKeyTables();

        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.FURNACE);
        BlockEntity be = helper.getBlockEntity(pos);
        EnderFrameAPI api = EnderFrameAPI.get();

        UIStateImpl state = (UIStateImpl) api.createBlockEntityState(be, STATE_TEMPLATE);
        Map<UUID, List<CustomPacketPayload>> sent = new ConcurrentHashMap<>();
        state.setPacketSender((player, packet) ->
                sent.computeIfAbsent(player.getUUID(), k -> new ArrayList<>()).add(packet));
        AtomicInteger toggles = new AtomicInteger();
        state.onAction("toggle", (player, payload) -> toggles.incrementAndGet());

        ServerPlayer alice = mockPlayer(helper);
        ServerPlayer bob = mockPlayer(helper);
        ServerPlayer stranger = mockPlayer(helper);
        String instanceId = state.instanceId();

        state.set("progress", 0.0).set("status", "idle");
        api.openBlockEntityUi(alice, be, STATE_TEMPLATE, null);
        if (api.createBlockEntityState(be, STATE_TEMPLATE) != state) {
            helper.fail("同じブロックエンティティで別の状態が返りました");
        }
        OpenUiS2CPacket aliceOpen = roundTrip(helper, sent, alice, 0, OpenUiS2CPacket.class);
        if (!instanceId.equals(aliceOpen.instanceId()) || !"idle".equals(aliceOpen.initialState().getString("status"))) {
            helper.fail("開くパケットが往復で崩れました: " + aliceOpen);
        }

        helper.startSequence()
                .thenIdle(2) // 開く前に set した分の差分を tick に流させる
                .thenExecute(() -> {
                    sent.get(alice.getUUID()).subList(1, sent.get(alice.getUUID()).size()).clear();
                    state.set("progress", 0.5);
                })
                .thenIdle(2) // サーバー tick の flush を待つ
                .thenExecute(() -> {
                    StateDeltaS2CPacket delta = roundTrip(helper, sent, alice, 1, StateDeltaS2CPacket.class);
                    if (!instanceId.equals(delta.instanceId()) || delta.deltas().getDouble("progress") != 0.5) {
                        helper.fail("tick の差分が往復で崩れました: " + delta.deltas());
                    }
                    api.openBlockEntityUi(bob, be, STATE_TEMPLATE, state);
                    state.set("energy", 1200); // Bob が開いた後に増えたキー
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    // Alice は Alice の受信側、Bob は Bob の受信側として、それぞれ送信順にデコードする
                    StateDeltaS2CPacket aliceDelta = roundTrip(helper, sent, alice, 2, StateDeltaS2CPacket.class);
                    StateNbtConverter.clearReceivedKeyTables();
                    roundTrip(helper, sent, bob, 0, OpenUiS2CPacket.class);
                    StateDeltaS2CPacket bobDelta = roundTrip(helper, sent, bob, 1, StateDeltaS2CPacket.class);
                    if (aliceDelta.deltas().getInt("energy") != 1200 || bobDelta.deltas().getInt("energy") != 1200) {
                        helper.fail("2 人目が開いた後のキーが両方に届いていません: alice=" + aliceDelta.deltas()
                                + " bob=" + bobDelta.deltas());
                    }

                    if (!UIStateManager.handleAction(instanceId, "toggle", bob, new CompoundTag()) || toggles.get() != 1) {
                        helper.fail("閲覧者のアクションが届きません");
                    }
                    if (UIStateManager.handleAction(instanceId, "toggle", stranger, new CompoundTag()) || toggles.get() != 1) {
                        helper.fail("閲覧者でないプレイヤーのアクションが通りました");
                    }
                    EnderFrameServerState.onPlayerLogout(bob);
                    if (state.viewers().size() != 1) {
                        helper.fail("ログアウトした閲覧者が残っています: " + state.viewers().size());
                    }

                    helper.destroyBlock(pos);
                })
                .thenIdle(2) // 破壊の次の tick で閉じる
                .thenExecute(() -> {
                    if (UIStateManager.findState(instanceId) != null) {
                        helper.fail("ブロックを壊した後も状態が残っています");
                    }
                    List<CustomPacketPayload> alicePackets = sent.get(alice.getUUID());
                    if (!(alicePackets.get(alicePackets.size() - 1) instanceof StateDeltaS2CPacket last) || !last.closed()) {
                        helper.fail("閲覧者に破棄の通知が届いていません: " + alicePackets);
                    }
                    for (ServerPlayer player : List.of(alice, bob, stranger)) {
                        EnderFrameServerState.onPlayerLogout(player);
                        player.connection.disconnect(Component.literal("gametest done"));
                    }
                    StateNbtConverter.clearReceivedKeyTables();
                    requireCleanState(helper, "後始末の後");
                })
                .thenSucceed();
    }

    /**
     * 同じ座標にブロックを置き直したら、前のブロックの状態（と閲覧者）を使い回さない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH_HEADLESS_REPLACE, timeoutTicks = 200)
    public static void headlessStateIsNotReusedByReplacedBlock(GameTestHelper helper) {
        requireCleanState(helper, "テスト開始前");

        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.FURNACE);
        EnderFrameAPI api = EnderFrameAPI.get();
        UIState first = api.createBlockEntityState(helper.getBlockEntity(pos), STATE_TEMPLATE);
        first.set("progress", 0.9);

        // 同じ tick のうちに壊して置き直す（tick の後始末より先に新しいブロックが状態を取りに来る）
        helper.destroyBlock(pos);
        helper.setBlock(pos, Blocks.FURNACE);
        UIState second = api.createBlockEntityState(helper.getBlockEntity(pos), STATE_TEMPLATE);
        if (second == first || second.get("progress") != null) {
            helper.fail("置き直したブロックが前の状態を使い回しました");
        }

        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    if (UIStateManager.findState(second.instanceId()) != second) {
                        helper.fail("置き直したブロックの状態が tick で閉じられました");
                    }
                    helper.destroyBlock(pos);
                })
                .thenIdle(2)
                .thenExecute(() -> requireCleanState(helper, "後始末の後"))
                .thenSucceed();
    }

    /** 送ったパケットを本物のコーデックで書いて読み直す（受信側の辞書への登録・名前への復元まで）。 */
    private static <T extends CustomPacketPayload> T roundTrip(GameTestHelper helper, Map<UUID, List<CustomPacketPayload>> sent,
                                                               ServerPlayer player, int index, Class<T> type) {
        List<CustomPacketPayload> packets = sent.getOrDefault(player.getUUID(), List.of());
        if (packets.size() <= index || !type.isInstance(packets.get(index))) {
            helper.fail(type.getSimpleName() + " が " + index + " 番目に届いていません: " + packets);
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CustomPacketPayload decoded;
            if (packets.get(index) instanceof OpenUiS2CPacket open) {
                OpenUiS2CPacket.STREAM_CODEC.encode(buf, open);
                decoded = OpenUiS2CPacket.STREAM_CODEC.decode(buf);
            } else {
                StateDeltaS2CPacket.STREAM_CODEC.encode(buf, (StateDeltaS2CPacket) packets.get(index));
                decoded = StateDeltaS2CPacket.STREAM_CODEC.decode(buf);
            }
            if (buf.readableBytes() != 0) {
                helper.fail(type.getSimpleName() + " の読み残し: " + buf.readableBytes() + " bytes");
            }
            return type.cast(decoded);
        } finally {
            buf.release();
        }
    }

    /**
     * 擬似プレイヤーを 1 人作ってプレイヤーリストに入れる。
     * 専用サーバーのゲームテストには本物の接続が無いため、これが唯一の手段。
     */
    @SuppressWarnings({"deprecation", "removal"}) // makeMockServerPlayerInLevel は 1.21.1 時点でこれしかない
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    /**
     * static 状態が空であることを要求する。
     * 前のテストの取りこぼしを次のテストの失敗として報告しないため、前後の両方で見る。
     */
    private static void requireCleanState(GameTestHelper helper, String whenDescription) {
        if (!EnderFrameServerState.isEmpty()) {
            helper.fail(whenDescription + "のサーバー状態が空ではありません: "
                    + EnderFrameServerState.describeState());
        }
    }

    /**
     * 開発用の run（{@code runClient} / {@code runServer}。{@code build.gradle} が
     * {@code neoforge.enabledGameTestNamespaces} を付ける）でだけ有効になる、実機確認用のコマンド。
     *
     * <ul>
     *   <li>{@code /efl_dev state open <pos> <targets>}: その座標に（無ければかまどを置いて）
     *       {@code enderframelib:state_test} の Headless State を作り、対象に開く。クリック・入力・ホイールの
     *       アクションは状態を書き換え、ログにも出す。</li>
     *   <li>{@code /efl_dev state set <pos> <key> <value>} / {@code /efl_dev state info <pos>}</li>
     *   <li>{@code /efl_dev count start|show <target>}: そのプレイヤーから届いた EnderFrame の C2S パケットを数える
     *       （接続のパイプラインに数えるだけのハンドラを挟む）。</li>
     *   <li>{@code /efl_dev hud <targets> <frames>}: クライアントだけで開く HUD を毎フレーム更新させる
     *       （システムメッセージで {@link Client} に伝える）。</li>
     * </ul>
     */
    @EventBusSubscriber(modid = EnderFrameLib.MODID)
    public static final class DevHarness {

        static final String HUD_TRIGGER = "[efl_dev] hud ";
        private static final String COUNTER_NAME = "efl_dev_counter";
        private static final Map<UUID, AtomicInteger> RECEIVED = new ConcurrentHashMap<>();

        private DevHarness() {}

        static boolean enabled() {
            String namespaces = System.getProperty("neoforge.enabledGameTestNamespaces", "");
            return Arrays.asList(namespaces.split(",")).contains(EnderFrameLib.MODID);
        }

        @SubscribeEvent
        static void onRegisterCommands(RegisterCommandsEvent event) {
            if (enabled()) register(event.getDispatcher());
        }

        private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
            dispatcher.register(Commands.literal("efl_dev").requires(src -> src.hasPermission(2))
                    .then(Commands.literal("state")
                            .then(Commands.literal("open").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                    .then(Commands.argument("targets", EntityArgument.players())
                                            .executes(ctx -> openState(ctx.getSource(),
                                                    BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                    EntityArgument.getPlayers(ctx, "targets"))))))
                            .then(Commands.literal("set").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                    .then(Commands.argument("key", StringArgumentType.word())
                                            .then(Commands.argument("value", StringArgumentType.greedyString())
                                                    .executes(ctx -> setState(ctx.getSource(),
                                                            BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                            StringArgumentType.getString(ctx, "key"),
                                                            StringArgumentType.getString(ctx, "value")))))))
                            .then(Commands.literal("info").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                    .executes(ctx -> info(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))))
                    .then(Commands.literal("count")
                            .then(Commands.literal("start").then(Commands.argument("target", EntityArgument.player())
                                    .executes(ctx -> countStart(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))))
                            .then(Commands.literal("show").then(Commands.argument("target", EntityArgument.player())
                                    .executes(ctx -> countShow(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"))))))
                    .then(Commands.literal("hud").then(Commands.argument("targets", EntityArgument.players())
                            .then(Commands.argument("frames", IntegerArgumentType.integer(1, 100000))
                                    .executes(ctx -> {
                                        int frames = IntegerArgumentType.getInteger(ctx, "frames");
                                        for (ServerPlayer player : EntityArgument.getPlayers(ctx, "targets")) {
                                            player.sendSystemMessage(Component.literal(HUD_TRIGGER + frames));
                                        }
                                        return 1;
                                    })))));
        }

        private static int openState(CommandSourceStack source, BlockPos pos, Collection<ServerPlayer> targets) {
            ServerLevel level = source.getLevel();
            if (level.getBlockEntity(pos) == null) {
                level.setBlockAndUpdate(pos, Blocks.FURNACE.defaultBlockState());
            }
            BlockEntity be = level.getBlockEntity(pos);
            EnderFrameAPI api = EnderFrameAPI.get();
            UIState state = api.createBlockEntityState(be, STATE_TEMPLATE);
            if (state.get("label") == null) {
                state.set("label", state.instanceId()).set("status", "idle").set("running", false)
                        .set("progress", 0.0).set("progress_css", "0%")
                        .set("clicks", 0).set("wheels", 0).set("typed", "");
                state.onAction("toggle", (player, payload) -> {
                    log(state, player, "toggle", payload);
                    boolean running = !state.getBoolean("running", false);
                    state.set("running", running)
                            .set("status", running ? "running" : "idle")
                            .set("clicks", state.getInt("clicks", 0) + 1);
                });
                state.onAction("typed", (player, payload) -> {
                    log(state, player, "typed", payload);
                    state.set("typed", payload.getString("Value"));
                });
                state.onAction("wheel", (player, payload) -> {
                    log(state, player, "wheel", payload);
                    state.set("wheels", state.getInt("wheels", 0) + 1);
                });
            }
            for (ServerPlayer player : targets) {
                api.openBlockEntityUi(player, be, STATE_TEMPLATE, state);
            }
            source.sendSuccess(() -> Component.literal("[efl_dev] opened " + state.instanceId()
                    + " for " + targets.size() + " player(s)"), false);
            return targets.size();
        }

        private static void log(UIState state, ServerPlayer player, String action, CompoundTag payload) {
            EnderFrameLib.LOGGER.info("[efl_dev] action {} from {} on {}: event={} value={}", action,
                    player.getGameProfile().getName(), state.instanceId(),
                    payload.getString("EventName"), payload.getString("Value"));
        }

        private static int setState(CommandSourceStack source, BlockPos pos, String key, String value) {
            UIState state = UIStateManager.findState(UIStateManager.blockInstanceId(pos));
            if (state == null) {
                source.sendFailure(Component.literal("[efl_dev] no state at " + pos));
                return 0;
            }
            Object parsed = value;
            try {
                parsed = Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                if ("true".equals(value) || "false".equals(value)) parsed = Boolean.parseBoolean(value);
            }
            state.set(key, parsed);
            if ("progress".equals(key) && parsed instanceof Double d) {
                state.set("progress_css", Math.round(d * 100) + "%");
            }
            source.sendSuccess(() -> Component.literal("[efl_dev] set " + key + "=" + value), false);
            return 1;
        }

        private static int info(CommandSourceStack source, BlockPos pos) {
            UIState state = UIStateManager.findState(UIStateManager.blockInstanceId(pos));
            String text = state == null
                    ? "[efl_dev] no state at " + pos
                    : "[efl_dev] " + state.instanceId()
                    + " viewers=" + state.viewers().stream().map(p -> p.getGameProfile().getName()).sorted().toList()
                    + " values=" + new TreeMap<>(state.snapshot());
            EnderFrameLib.LOGGER.info(text);
            source.sendSuccess(() -> Component.literal(text), false);
            return state == null ? 0 : 1;
        }

        private static int countStart(CommandSourceStack source, ServerPlayer player) {
            AtomicInteger counter = new AtomicInteger();
            RECEIVED.put(player.getUUID(), counter);
            ChannelPipeline pipeline = player.connection.getConnection().channel().pipeline();
            if (pipeline.get(COUNTER_NAME) != null) pipeline.remove(COUNTER_NAME);
            pipeline.addBefore("packet_handler", COUNTER_NAME, new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (msg instanceof ServerboundCustomPayloadPacket packet
                            && EnderFrameLib.MODID.equals(packet.payload().type().id().getNamespace())) {
                        counter.incrementAndGet();
                        EnderFrameLib.LOGGER.info("[efl_dev] C2S {} from {}", packet.payload().type().id(),
                                player.getGameProfile().getName());
                    }
                    super.channelRead(ctx, msg);
                }
            });
            source.sendSuccess(() -> Component.literal("[efl_dev] counting C2S of " + player.getGameProfile().getName()), false);
            return 1;
        }

        private static int countShow(CommandSourceStack source, ServerPlayer player) {
            AtomicInteger counter = RECEIVED.get(player.getUUID());
            String text = "[efl_dev] C2S from " + player.getGameProfile().getName() + " = "
                    + (counter != null ? counter.get() : "not counting");
            EnderFrameLib.LOGGER.info(text);
            source.sendSuccess(() -> Component.literal(text), false);
            return 1;
        }
    }

    /**
     * {@link DevHarness} のクライアント側。{@code /efl_dev hud} のシステムメッセージを受けて、クライアントだけの HUD を
     * {@code requestAnimationFrame} で指定フレーム数だけ書き換え、終わったらログに出す。
     */
    @EventBusSubscriber(modid = EnderFrameLib.MODID, value = Dist.CLIENT)
    public static final class Client {

        private static final String HUD_ID = "efl_dev_hud";

        private Client() {}

        @SubscribeEvent
        static void onChat(ClientChatReceivedEvent event) {
            if (!DevHarness.enabled()) return;
            String text = event.getMessage().getString();
            if (!text.startsWith(DevHarness.HUD_TRIGGER)) return;
            int frames = Integer.parseInt(text.substring(DevHarness.HUD_TRIGGER.length()).trim());

            EnderFrameClientAPI api = EnderFrameClientAPI.get();
            Document doc = api.setHud(HUD_ID, """
                    <div style="position:absolute; left:8px; top:8px; padding:6px; background:#000a; color:#fff; font-size:16px;">
                      client HUD frame <span id="frame">0</span>
                    </div>
                    """);
            AtomicInteger count = new AtomicInteger();
            Runnable[] loop = new Runnable[1];
            loop[0] = () -> api.requestAnimationFrame(HUD_ID, time -> {
                Element frame = doc.getElementById("frame");
                if (frame != null) frame.setTextContent(Integer.toString(count.incrementAndGet()));
                if (count.get() < frames) {
                    loop[0].run();
                } else {
                    EnderFrameLib.LOGGER.info("[efl_dev] client HUD updated {} frames", count.get());
                }
            });
            loop[0].run();
        }
    }
}
