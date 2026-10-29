package com.nishiyu.enderframelib.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.api.ClientSession;
import com.nishiyu.enderframelib.api.EnderFrameAPI;
import com.nishiyu.enderframelib.api.EnderFrameClientAPI;
import com.nishiyu.enderframelib.api.Ui;
import com.nishiyu.enderframelib.api.WebDocument;
import com.nishiyu.enderframelib.api.WebSessionHandle;
import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.api.template.EnderScreenBlock;
import com.nishiyu.enderframelib.api.template.EnderScreenBlockEntity;
import com.nishiyu.enderframelib.api.template.EnderScreenItem;
import com.nishiyu.enderframelib.client.Surfaces;
import com.nishiyu.enderframelib.client.renderer.surface.ItemSurfaceSpec;
import com.nishiyu.enderframelib.network.OpenUiS2CPacket;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.network.StateNbtConverter;
import com.nishiyu.enderframelib.server.EnderFrameCommands;
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
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.RegisterEvent;

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
    private static final String BATCH_SURFACE = "enderframe_surface";

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
        api.open(player, Ui.html("<div id='root'>hello</div>").size(320, 200));
        api.hud(player, Ui.html("<div id='hud'>hud</div>"));

        if (api.findSession(screenId) == null) {
            helper.fail("open 後にセッションが見つかりません: " + screenId);
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
        api.open(player, Ui.html("""
                <style>#count { color: #ff0000; }</style>
                <p>Diamonds: <span id="count" class="value">0</span></p>
                """).size(320, 200));

        WebSessionHandle session = api.findSession("script_" + player.getStringUUID());
        if (session == null) {
            helper.fail("open 後にセッションが見つかりません");
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
        com.nishiyu.enderframelib.server.WebSession internal = (com.nishiyu.enderframelib.server.WebSession) session;
        internal.patchText("count", "42");
        internal.patchAttribute("count", "data-ready", "1", false);
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

        UIStateImpl state = (UIStateImpl) api.stateOf(be, STATE_TEMPLATE);
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
        api.open(alice, Ui.template(STATE_TEMPLATE).at(be));
        if (api.stateOf(be, STATE_TEMPLATE) != state) {
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
                    api.open(bob, Ui.template(STATE_TEMPLATE).at(be).state(state));
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

                    // 閲覧者でも、ブロックから遠く離れていればアクションは届かない
                    bob.setPos(helper.absoluteVec(net.minecraft.world.phys.Vec3.atCenterOf(pos)).add(200, 0, 0));
                    if (UIStateManager.handleAction(instanceId, "toggle", bob, new CompoundTag()) || toggles.get() != 0) {
                        helper.fail("離れた閲覧者のアクションが通りました");
                    }
                    bob.setPos(helper.absoluteVec(net.minecraft.world.phys.Vec3.atCenterOf(pos)));
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
        UIState first = api.stateOf(helper.getBlockEntity(pos), STATE_TEMPLATE);
        first.set("progress", 0.9);

        // 同じ tick のうちに壊して置き直す（tick の後始末より先に新しいブロックが状態を取りに来る）
        helper.destroyBlock(pos);
        helper.setBlock(pos, Blocks.FURNACE);
        UIState second = api.stateOf(helper.getBlockEntity(pos), STATE_TEMPLATE);
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

    /**
     * 並べたスクリーンブロックは欠けのない長方形で 1 枚の画面になり、壊すと画面は破棄されて残りが初期化され、
     * draw の命令で再びまとまる（左下が master）。
     */
    @GameTest(template = TEMPLATE, batch = BATCH_SURFACE, timeoutTicks = 100)
    public static void screenBlocksFormRectangleAndSplitOnBreak(GameTestHelper helper) {
        Block block = SurfaceDevHarness.BLOCK;
        if (block == null) helper.fail("テスト用のスクリーンブロックが登録されていません");

        Direction facing = Direction.SOUTH;
        Direction right = facing.getCounterClockWise();
        BlockPos origin = new BlockPos(0, 1, 1);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) {
                BlockPos p = origin.relative(right, x).above(y);
                helper.setBlock(p, block);
                screenAt(helper, p).setFacing(facing);
            }
        }
        EnderScreenBlockEntity.draw(helper.getLevel(), helper.absolutePos(origin));

        BlockPos absoluteOrigin = helper.absolutePos(origin);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) {
                EnderScreenBlockEntity be = screenAt(helper, origin.relative(right, x).above(y));
                if (be.getScreenWidth() != 3 || be.getScreenHeight() != 2 || !be.getScreenMasterPos().equals(absoluteOrigin)) {
                    helper.fail("3×2 の画面にまとまっていません (" + x + "," + y + "): " + be.getScreenWidth() + "x"
                            + be.getScreenHeight() + " master=" + be.getScreenMasterPos());
                }
                if (be.isMaster() != (x == 0 && y == 0)) helper.fail("master が左下ではありません (" + x + "," + y + ")");
            }
        }

        // 上の段の真ん中を壊す → 画面は破棄され、残りのブロックは全て初期化される（組み直されない）
        BlockPos gap = origin.relative(right, 1).above(1);
        helper.destroyBlock(gap);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) {
                BlockPos p = origin.relative(right, x).above(y);
                if (p.equals(gap)) continue;
                EnderScreenBlockEntity be = screenAt(helper, p);
                if (be.isActive() || be.isMaster() || be.getScreenWidth() != 1 || be.getScreenHeight() != 1) {
                    helper.fail("残りのブロックが初期化されていません (" + x + "," + y + ")");
                }
            }
        }

        // 描く命令（draw）が来ると、初期化されたブロックも普通に画面になる。上の段を全部壊した下の段は 3×1
        helper.destroyBlock(origin.above(1));
        helper.destroyBlock(origin.relative(right, 2).above(1));
        EnderScreenBlockEntity.draw(helper.getLevel(), absoluteOrigin);
        for (int x = 0; x < 3; x++) {
            EnderScreenBlockEntity be = screenAt(helper, origin.relative(right, x));
            if (!be.isActive() || be.getScreenWidth() != 3 || be.getScreenHeight() != 1 || !be.getScreenMasterPos().equals(absoluteOrigin)) {
                helper.fail("draw で下の段が 3×1 になっていません (" + x + ")");
            }
        }

        // 統合コマンド (/ef screen load / patch / clear, /ef hologram spawn / clear) と権限チェックの検証
        CommandSourceStack adminSrc = helper.getLevel().getServer().createCommandSourceStack().withSuppressedOutput();
        if (!EnderFrameCommands.canUseCommand(adminSrc)) {
            helper.fail("管理者権限の CommandSourceStack で canUseCommand が false になりました");
        }
        if (EnderFrameCommands.canUseCommand(adminSrc.withPermission(0))) {
            helper.fail("OP 権限なし (permission 0) で canUseCommand が true になりました");
        }
        ServerPlayer survivalPlayer = mockPlayer(helper);
        survivalPlayer.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        if (!EnderFrameCommands.canUseCommand(survivalPlayer.createCommandSourceStack().withPermission(4))) {
            helper.fail("サバイバルモードの OP プレイヤーで canUseCommand が false になりました");
        }
        if (EnderFrameCommands.canUseCommand(survivalPlayer.createCommandSourceStack().withPermission(0))) {
            helper.fail("サバイバルモードの非 OP プレイヤーで canUseCommand が true になりました");
        }
        EnderFrameServerState.onPlayerLogout(survivalPlayer);
        survivalPlayer.connection.disconnect(Component.literal("gametest done"));

        var commands = helper.getLevel().getServer().getCommands();
        String posArgs = absoluteOrigin.getX() + " " + absoluteOrigin.getY() + " " + absoluteOrigin.getZ();
        commands.performPrefixedCommand(adminSrc, "ef screen load " + posArgs + " enderframelib:surface_test 512 384 custom_screen_sess");
        EnderScreenBlockEntity masterBe = screenAt(helper, origin);
        if (!"custom_screen_sess".equals(masterBe.getSessionId())) {
            helper.fail("/ef screen load でカスタムセッション ID が反映されていません: " + masterBe.getSessionId());
        }
        commands.performPrefixedCommand(adminSrc, "ef hologram spawn test_holo " + posArgs + " enderframelib:surface_test");
        if (com.nishiyu.enderframelib.server.HologramRegistry.count() != 1) {
            helper.fail("/ef hologram spawn でホログラムが登録されていません");
        }
        commands.performPrefixedCommand(adminSrc, "ef hologram clear");
        if (com.nishiyu.enderframelib.server.HologramRegistry.count() != 0) {
            helper.fail("/ef hologram clear でホログラムが消えていません");
        }
        commands.performPrefixedCommand(adminSrc, "ef screen clear " + posArgs + " true");
        commands.performPrefixedCommand(adminSrc, "ef session clear_all");
        helper.succeed();
    }

    private static EnderScreenBlockEntity screenAt(GameTestHelper helper, BlockPos pos) {
        if (!(helper.getBlockEntity(pos) instanceof EnderScreenBlockEntity be)) {
            helper.fail("スクリーンのブロックエンティティがありません: " + pos);
            throw new IllegalStateException();
        }
        return be;
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
            dispatcher.register(Commands.literal("efl_dev").requires(EnderFrameCommands::canUseCommand)
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
                    .then(SurfaceDevHarness.command())
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
            UIState state = api.stateOf(be, STATE_TEMPLATE);
            initState(state);
            for (ServerPlayer player : targets) {
                api.open(player, Ui.template(STATE_TEMPLATE).at(be).state(state));
            }
            source.sendSuccess(() -> Component.literal("[efl_dev] opened " + state.instanceId()
                    + " for " + targets.size() + " player(s)"), false);
            return targets.size();
        }

        /** 状態の初期値とアクションを一度だけ入れる（{@code label} が無ければ未初期化とみなす）。 */
        static void initState(UIState state) {
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
            ClientSession hud = api.hud(Ui.html("""
                    <div style="position:absolute; left:8px; top:8px; padding:6px; background:#000a; color:#fff; font-size:16px;">
                      client HUD frame <span id="frame">0</span>
                    </div>
                    """).session(HUD_ID));
            Document doc = hud.document();
            AtomicInteger count = new AtomicInteger();
            Runnable[] loop = new Runnable[1];
            loop[0] = () -> hud.requestAnimationFrame(time -> {
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

    /**
     * ブロック／手持ちアイテム表面への Web 描画を行う標準ブロック＆アイテム登録と開発ハーネス。
     * サバイバルモードでの不正な設置・破壊を防ぐため、管理者（OP 2 以上＆非サバイバル）のみが設置でき、
     * サバイバルでは破壊不能（岩盤同等の強度＆ドロップなし）としている。
     */
    public static final class SurfaceDevHarness {

        public static final String BLOCK_SESSION = "efl_surface_block";
        /** 真なら、ブロックの面をセッションではなくテンプレート + UIState で動かす（開発用のスイッチ）。 */
        static volatile boolean TEMPLATE_MODE;
        public static final String ITEM_SESSION = "efl_surface_item";
        public static final String HOLOGRAM_SESSION = "efl_surface_hologram";
        /** どの面もページの設計どおりの解像度（512x384）で読み込む。面の大きさ（ブロック数）は、それを収める物理的な大きさ。 */
        static final String UI_ID = "enderframelib:surface_test";
        static final int UI_WIDTH = 512;
        static final int UI_HEIGHT = 384;

        private static final ResourceLocation BLOCK_ID = ResourceLocation.fromNamespaceAndPath(EnderFrameLib.MODID, "surface_test_block");
        private static final ResourceLocation ITEM_ID = ResourceLocation.fromNamespaceAndPath(EnderFrameLib.MODID, "surface_test_item");

        // Block / Item のコンストラクタはレジストリに触るので、凍結後の mod 構築中には作れない。登録イベントの中で作る。
        public static Block BLOCK;
        public static BlockEntityType<SurfaceBlockEntity> BLOCK_ENTITY;
        public static EnderScreenItem ITEM;

        private SurfaceDevHarness() {}

        /** 開発環境でのみ呼ばれる（EnderFrameLib 側で本番ビルドを除外）。サバイバルでは破壊・設置不可。 */
        public static void init(IEventBus modEventBus) {
            modEventBus.addListener(RegisterEvent.class, event -> {
                event.register(Registries.BLOCK, helper -> {
                    BLOCK = new EnderScreenBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(-1.0f, 3600000.0f)
                                    .noLootTable()
                                    .noOcclusion(),
                            () -> BLOCK_ENTITY);
                    helper.register(BLOCK_ID, BLOCK);
                });
                event.register(Registries.BLOCK_ENTITY_TYPE, helper -> {
                    BLOCK_ENTITY = BlockEntityType.Builder.of(SurfaceBlockEntity::new, BLOCK).build(null);
                    helper.register(BLOCK_ID, BLOCK_ENTITY);
                });
                event.register(Registries.ITEM, helper -> {
                    ITEM = new EnderScreenItem(new Item.Properties().stacksTo(1));
                    BlockItem blockItem = new BlockItem(BLOCK, new Item.Properties()) {
                        @Override
                        protected boolean canPlace(BlockPlaceContext context, BlockState state) {
                            Player player = context.getPlayer();
                            if (player != null && (!player.canUseGameMasterBlocks() || (!player.isCreative() && !player.isSpectator()))) {
                                return false;
                            }
                            return super.canPlace(context, state);
                        }
                    };
                    helper.register(BLOCK_ID, blockItem);
                    helper.register(ITEM_ID, ITEM);
                });
            });
        }

        static LiteralArgumentBuilder<CommandSourceStack> command() {
            return Commands.literal("surface")
                    .then(Commands.literal("place").then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .then(Commands.argument("facing", StringArgumentType.word())
                                    .suggests((ctx, b) -> {
                                        for (Direction d : Direction.Plane.HORIZONTAL) b.suggest(d.getName());
                                        return b.buildFuture();
                                    })
                                    .then(Commands.argument("w", IntegerArgumentType.integer(1, 16))
                                            .then(Commands.argument("h", IntegerArgumentType.integer(1, 16))
                                                     .executes(ctx -> place(ctx.getSource(),
                                                            BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                            StringArgumentType.getString(ctx, "facing"),
                                                            IntegerArgumentType.getInteger(ctx, "w"),
                                                            IntegerArgumentType.getInteger(ctx, "h"))))))))
                    .then(Commands.literal("hologram").then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .then(Commands.argument("billboard", BoolArgumentType.bool())
                                    .executes(ctx -> hologram(ctx.getSource(),
                                            BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                            BoolArgumentType.getBool(ctx, "billboard"))))))
                    .then(Commands.literal("template").then(Commands.argument("on", BoolArgumentType.bool())
                            .executes(ctx -> {
                                TEMPLATE_MODE = BoolArgumentType.getBool(ctx, "on");
                                return 1;
                            })))
                    .then(Commands.literal("give").then(Commands.argument("targets", EntityArgument.players())
                            .executes(ctx -> give(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))))
                    .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())));
        }

        private static int place(CommandSourceStack source, BlockPos pos, String facingName, int w, int h) {
            Direction facing = Direction.byName(facingName);
            if (facing == null || facing.getAxis().isVertical()) {
                source.sendFailure(Component.literal("[efl_dev] facing must be north/south/east/west"));
                return 0;
            }
            ServerLevel level = source.getLevel();
            // w×h 個のブロックを並べ、1 枚の画面にまとめさせる（pos が左下 = master）。セッションは onScreenFormed が読み込む
            Direction right = facing.getCounterClockWise();
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    BlockPos p = pos.relative(right, x).above(y);
                    level.setBlockAndUpdate(p, BLOCK.defaultBlockState());
                    if (level.getBlockEntity(p) instanceof SurfaceBlockEntity be) be.setFacing(facing);
                }
            }
            EnderScreenBlockEntity.draw(level, pos);
            source.sendSuccess(() -> Component.literal("[efl_dev] surface block at " + pos.toShortString()
                    + " facing=" + facing.getName() + " " + w + "x" + h + " blocks (layout " + UI_WIDTH + "x" + UI_HEIGHT + " px)"), false);
            return 1;
        }

        private static int hologram(CommandSourceStack source, BlockPos pos, boolean billboard) {
            String holoId = "efl_dev_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
            Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            String orient = billboard ? "billboard" : "south";
            if (TEMPLATE_MODE) {
                // テンプレート + UIState で動かす。状態はホログラムごとに分け、見せる相手は全員
                String sessionId = "efl_hologram_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
                Ui.Template ui = Ui.template(STATE_TEMPLATE).instance(sessionId);
                DevHarness.initState(EnderFrameAPI.get().stateOf(STATE_TEMPLATE, sessionId));
                for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
                    EnderFrameAPI.get().showSurface(player, ui, UI_WIDTH, UI_HEIGHT);
                }
                return EnderFrameCommands.bindHologramSession(source, holoId, center, sessionId, 2.0f, 1.5f, orient, true);
            } else {
                loadSession(source, HOLOGRAM_SESSION, UI_WIDTH, UI_HEIGHT);
                return EnderFrameCommands.bindHologramSession(source, holoId, center, HOLOGRAM_SESSION, 2.0f, 1.5f, orient, true);
            }
        }

        private static int give(CommandSourceStack source, Collection<ServerPlayer> targets) {
            String sessionId = ITEM_SESSION;
            if (TEMPLATE_MODE) {
                // テンプレート + UIState で動かす。持ち主に面を見せる
                sessionId = "efl_item_state";
                Ui.Template ui = Ui.template(STATE_TEMPLATE).instance(sessionId);
                DevHarness.initState(EnderFrameAPI.get().stateOf(STATE_TEMPLATE, sessionId));
                for (ServerPlayer player : targets) EnderFrameAPI.get().showSurface(player, ui, UI_WIDTH, UI_HEIGHT);
            } else {
                loadSession(source, ITEM_SESSION, UI_WIDTH, UI_HEIGHT);
            }
            for (ServerPlayer player : targets) {
                ItemStack stack = ITEM.createStack(sessionId);
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("EnderFrame Surface Tablet"));
                if (!player.getInventory().add(stack)) player.drop(stack, false);
            }
            source.sendSuccess(() -> Component.literal("[efl_dev] gave surface item to " + targets.size() + " player(s)"), false);
            return targets.size();
        }

        private static int clear(CommandSourceStack source) {
            runCommand(source, "enderframe session clear " + BLOCK_SESSION);
            runCommand(source, "enderframe session clear " + ITEM_SESSION);
            runCommand(source, "enderframe session clear " + HOLOGRAM_SESSION);
            EnderFrameCommands.clearHologramsCmd(source);
            return 1;
        }

        private static void loadSession(CommandSourceStack source, String sessionId, int w, int h) {
            runCommand(source, "enderframe session load " + sessionId + " " + UI_ID + " " + w + " " + h);
        }

        private static void runCommand(CommandSourceStack source, String command) {
            source.getServer().getCommands().performPrefixedCommand(source.withSuppressedOutput(), command);
        }

        /** ひな形（{@link EnderScreenBlockEntity}）の上に、セッションと LOD 色だけを足したブロックエンティティ。 */
        public static final class SurfaceBlockEntity extends EnderScreenBlockEntity {
            public SurfaceBlockEntity(BlockPos pos, BlockState state) {
                super(BLOCK_ENTITY, pos, state);
            }

            @Override
            public String getSessionId() {
                if (getTemplateId() != null) return super.getSessionId();
                if (getDatapackUiId() != null || getCustomSessionId() != null) return super.getSessionId();
                return BLOCK_SESSION;
            }

            /** 明示的なテンプレート指定、または {@code /efl_dev surface template true} のときにテンプレートで動かす。 */
            @Override
            public String getTemplateId() {
                String custom = super.getTemplateId();
                if (custom != null) return custom;
                return TEMPLATE_MODE ? STATE_TEMPLATE : null;
            }

            /** 遠距離 LOD の確認用に、はっきり分かる色を返す。 */
            @Override public int getLodColor() { return 0xFF00E5FF; }

            @Override
            protected void onScreenFormed(int width, int height) {
                if (getTemplateId() != null) {
                    DevHarness.initState(screenState());
                } else if (getDatapackUiId() != null) {
                    EnderFrameCommands.ensureBlockScreenSessionLoaded(this);
                } else if (level instanceof ServerLevel serverLevel) {
                    loadSession(serverLevel.getServer().createCommandSourceStack(), getSessionId(), UI_WIDTH, UI_HEIGHT);
                }
            }
        }
    }

    /** {@link SurfaceDevHarness} のクライアント側。ブロックは {@code WebBlockRenderer}、アイテムは {@code WebItemRendererHelper} に通す。 */
    public static final class SurfaceDevHarnessClient {

        /** アイテム面（512x384）。アイテム空間の幅 0.8、一人称では 0.6 までに収める。 */
        private static final ItemSurfaceSpec ITEM_SURFACE = new ItemSurfaceSpec(
                SurfaceDevHarness.UI_WIDTH, SurfaceDevHarness.UI_HEIGHT, 0.8f, 0.6f, ItemSurfaceSpec.Anchor.CENTER, 0f);

        private SurfaceDevHarnessClient() {}

        public static void init(IEventBus modEventBus) {
            Surfaces.block(modEventBus, () -> SurfaceDevHarness.BLOCK_ENTITY);
            Surfaces.item(modEventBus, () -> SurfaceDevHarness.ITEM, ITEM_SURFACE,
                    ResourceLocation.fromNamespaceAndPath(EnderFrameLib.MODID, "item/surface_test_paper"));
            // 開いた地図: 128x128 の地図の (8, 16) から幅 112 の矩形（320x240 の比）
            Surfaces.map(
                    stack -> stack.is(net.minecraft.world.item.Items.FILLED_MAP)
                            && com.nishiyu.enderframelib.client.ClientScreenInteractionManager.getSessionIdFromItem(stack) != null,
                    ItemSurfaceSpec.onTexture(SurfaceDevHarness.UI_WIDTH, SurfaceDevHarness.UI_HEIGHT, 128, 128, 8, 16, 112));
        }
    }
}
