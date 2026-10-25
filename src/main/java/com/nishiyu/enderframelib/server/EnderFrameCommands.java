package com.nishiyu.enderframelib.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.api.WebScreenBlockEntity;
import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.api.template.EnderScreenBlockEntity;
import com.nishiyu.enderframelib.api.template.EnderScreenItem;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.common.WebDevKitExporter;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.network.WebSyncS2CPacket;
import com.nishiyu.enderframelib.server.HologramRegistry.ServerHologram;
import com.nishiyu.enderframelib.server.ServerPubSubManager.ServerPacketSender;
import com.nishiyu.enderframelib.server.state.UIStateManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@org.jetbrains.annotations.ApiStatus.Internal
public class EnderFrameCommands {

    private static final int DEFAULT_WIDTH = 854;
    private static final int DEFAULT_HEIGHT = 480;
    private static final int SURFACE_DEFAULT_WIDTH = 512;
    private static final int SURFACE_DEFAULT_HEIGHT = 384;
    private static final int HUD_WIDTH = 1280;
    private static final int HUD_HEIGHT = 720;
    private static final int MIN_SIZE = 32;
    private static final int MAX_WIDTH = 3840;
    private static final int MAX_HEIGHT = 2160;

    private static final ResourceLocation SCREEN_BLOCK_ID =
            ResourceLocation.fromNamespaceAndPath(EnderFrameLib.MODID, "surface_test_block");
    private static final ResourceLocation SCREEN_ITEM_ID =
            ResourceLocation.fromNamespaceAndPath(EnderFrameLib.MODID, "surface_test_item");
    private static final ResourceLocation DEFAULT_SURFACE_UI =
            ResourceLocation.fromNamespaceAndPath(EnderFrameLib.MODID, "surface_test");

    /**
     * コマンド実行権限の判定:
     * <ul>
     *   <li>マルチ・シングル問わず管理者権限（OP レベル 2 以上）が必須</li>
     *   <li>プレイヤー自身がサバイバルモードの場合は実行不可（データパック関数・コマンドブロックからの実行は OP 2 権限を持つため可能だが、サバイバルプレイヤー本人の直接実行は拒否）</li>
     * </ul>
     */
    public static boolean canUseCommand(CommandSourceStack src) {
        if (!src.hasPermission(2)) {
            return false;
        }
        if (src.getEntity() instanceof ServerPlayer player && player.gameMode.isSurvival()) {
            return false;
        }
        return true;
    }

    // ==========================================
    // Suggestion Providers (Tab 補完)
    // ==========================================

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_DATAPACK_UIS = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(EnderFrameDataPackManager.getAvailableShortIds(), builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PATCH_MODES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(new String[]{"add", "remove", "toggle"}, builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_SESSIONS = (ctx, builder) -> {
        Set<String> all = new LinkedHashSet<>(WebSessionManager.standaloneSessionIds());
        all.addAll(UIStateManager.stateIds());
        return SharedSuggestionProvider.suggest(all, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ELEMENTS = (ctx, builder) -> {
        try {
            String sessionId = StringArgumentType.getString(ctx, "session_id");
            WebSession session = WebSessionManager.findStandaloneSession(sessionId);
            if (session != null && session.getDocument() != null && session.getDocument().root != null) {
                Set<String> ids = new LinkedHashSet<>();
                collectElementIds(session.getDocument().root, ids);
                return SharedSuggestionProvider.suggest(ids, builder);
            }
        } catch (Exception ignored) {
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_SCREEN_ELEMENTS = (ctx, builder) -> {
        try {
            BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
            BlockEntity be = ctx.getSource().getLevel().getBlockEntity(pos);
            if (be instanceof WebScreenBlockEntity screenBe) {
                WebSession session = WebSessionManager.findStandaloneSession(screenBe.getSessionId());
                if (session != null && session.getDocument() != null && session.getDocument().root != null) {
                    Set<String> ids = new LinkedHashSet<>();
                    collectElementIds(session.getDocument().root, ids);
                    return SharedSuggestionProvider.suggest(ids, builder);
                }
            }
        } catch (Exception ignored) {
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_HOLOGRAMS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(HologramRegistry.ids(), builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_STATES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(UIStateManager.stateIds(), builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_CHANNELS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(ServerPubSubManager.channelNames(), builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_FACING = (ctx, builder) ->
            SharedSuggestionProvider.suggest(new String[]{"north", "south", "east", "west"}, builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ORIENTATION = (ctx, builder) ->
            SharedSuggestionProvider.suggest(new String[]{"billboard", "north", "south", "east", "west", "0", "90", "180", "270"}, builder);

    private static void collectElementIds(HtmlNode node, Set<String> out) {
        if (node == null) return;
        String id = node.getId();
        if (id != null && !id.isEmpty()) {
            out.add(id);
        }
        if (node.children != null) {
            for (HtmlNode child : node.children) {
                collectElementIds(child, out);
            }
        }
    }

    // ==========================================
    // コマンドツリー登録 (/enderframe および /ef)
    // ==========================================

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(buildRoot("enderframe"));
        dispatcher.register(buildRoot("ef"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String rootName) {
        return Commands.literal(rootName)
                .requires(EnderFrameCommands::canUseCommand)
                .then(buildDesktop())
                .then(buildOpen())
                .then(buildOpenInline())
                .then(buildHud())
                .then(buildScreen("screen"))
                .then(buildScreen("block"))
                .then(buildHologram())
                .then(buildItem())
                .then(buildSession())
                .then(buildPatch())
                .then(buildState())
                .then(buildPublish())
                .then(buildDev())
                .then(Commands.literal("devkit")
                        .executes(ctx -> exportBrowserDevKit(ctx.getSource())));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildDev() {
        return Commands.literal("dev")
                .then(buildDesktop())
                .then(Commands.literal("devkit")
                        .executes(ctx -> exportBrowserDevKit(ctx.getSource())));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildDesktop() {
        return Commands.literal("desktop")
                .executes(ctx -> openDesktopScreen(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException()), "docs"))
                .then(Commands.argument("targets", EntityArgument.players())
                        .executes(ctx -> openDesktopScreen(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"), "docs")));
    }

    // ==========================================
    // 1. open / open_inline / hud
    // ==========================================

    private static ArgumentBuilder<CommandSourceStack, ?> buildOpen() {
        return Commands.literal("open")
                // 省略形: /ef open <ui_id> (実行者自身に開く)
                .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                        .suggests(SUGGEST_DATAPACK_UIS)
                        .executes(ctx -> openDatapackUi(ctx.getSource(),
                                List.of(ctx.getSource().getPlayerOrException()), uiId(ctx), "", 0, 0)))
                // フル形: /ef open <targets> <ui_id> ...
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                .suggests(SUGGEST_DATAPACK_UIS)
                                .executes(ctx -> openDatapackUi(ctx.getSource(), targets(ctx), uiId(ctx), "", 0, 0))
                                // サイズ先行: /ef open <targets> <ui_id> <width> <height> [<session_id>]
                                .then(Commands.argument("width", IntegerArgumentType.integer(0, MAX_WIDTH))
                                        .then(Commands.argument("height", IntegerArgumentType.integer(0, MAX_HEIGHT))
                                                .executes(ctx -> openDatapackUi(ctx.getSource(), targets(ctx), uiId(ctx),
                                                        "", num(ctx, "width"), num(ctx, "height")))
                                                .then(Commands.argument("session_id", StringArgumentType.word())
                                                        .executes(ctx -> openDatapackUi(ctx.getSource(), targets(ctx), uiId(ctx),
                                                                str(ctx, "session_id"), num(ctx, "width"), num(ctx, "height"))))
                                        )
                                )
                                // 既存互換: /ef open <targets> <ui_id> <session_id> [<width> <height>]
                                .then(Commands.argument("session_id", StringArgumentType.word())
                                        .executes(ctx -> openDatapackUi(ctx.getSource(), targets(ctx), uiId(ctx), str(ctx, "session_id"), 0, 0))
                                        .then(Commands.argument("width", IntegerArgumentType.integer(0, MAX_WIDTH))
                                                .then(Commands.argument("height", IntegerArgumentType.integer(0, MAX_HEIGHT))
                                                        .executes(ctx -> openDatapackUi(ctx.getSource(), targets(ctx), uiId(ctx),
                                                                str(ctx, "session_id"), num(ctx, "width"), num(ctx, "height")))
                                                )
                                        )
                                )
                        )
                );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildOpenInline() {
        return Commands.literal("open_inline")
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("html", StringArgumentType.greedyString())
                                .executes(ctx -> openInlineUi(ctx.getSource(), targets(ctx), str(ctx, "html")))
                        )
                );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildHud() {
        return Commands.literal("hud")
                .then(Commands.literal("set")
                        // 省略形: /ef hud set <ui_id>
                        .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                .suggests(SUGGEST_DATAPACK_UIS)
                                .executes(ctx -> setPlayerHud(ctx.getSource(),
                                        List.of(ctx.getSource().getPlayerOrException()), uiId(ctx), "")))
                        // フル形: /ef hud set <targets> <ui_id> [<session_id>]
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                        .suggests(SUGGEST_DATAPACK_UIS)
                                        .executes(ctx -> setPlayerHud(ctx.getSource(), targets(ctx), uiId(ctx), ""))
                                        .then(Commands.argument("session_id", StringArgumentType.word())
                                                .executes(ctx -> setPlayerHud(ctx.getSource(), targets(ctx), uiId(ctx), str(ctx, "session_id"))))
                                )
                        )
                )
                .then(Commands.literal("clear")
                        .executes(ctx -> clearPlayerHud(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> clearPlayerHud(ctx.getSource(), targets(ctx)))
                        )
                );
    }

    // ==========================================
    // 2. screen (または block) — ブロックスクリーン操作
    // ==========================================

    private static ArgumentBuilder<CommandSourceStack, ?> buildScreen(String literalName) {
        return Commands.literal(literalName)
                // /ef screen place <pos> <facing> <blocks_w> <blocks_h> [<ui_id> [<px_w> <px_h> [<session_id>]]]
                .then(Commands.literal("place")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("facing", StringArgumentType.word())
                                        .suggests(SUGGEST_FACING)
                                        .then(Commands.argument("blocks_w", IntegerArgumentType.integer(1, 16))
                                                .then(Commands.argument("blocks_h", IntegerArgumentType.integer(1, 16))
                                                        .executes(ctx -> placeBlockScreen(
                                                                ctx.getSource(),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                str(ctx, "facing"),
                                                                num(ctx, "blocks_w"),
                                                                num(ctx, "blocks_h"),
                                                                DEFAULT_SURFACE_UI,
                                                                0, 0, ""))
                                                        .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                                                .suggests(SUGGEST_DATAPACK_UIS)
                                                                .executes(ctx -> placeBlockScreen(
                                                                        ctx.getSource(),
                                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                        str(ctx, "facing"),
                                                                        num(ctx, "blocks_w"),
                                                                        num(ctx, "blocks_h"),
                                                                        uiId(ctx),
                                                                        0, 0, ""))
                                                                .then(Commands.argument("px_w", IntegerArgumentType.integer(MIN_SIZE, MAX_WIDTH))
                                                                        .then(Commands.argument("px_h", IntegerArgumentType.integer(MIN_SIZE, MAX_HEIGHT))
                                                                                .executes(ctx -> placeBlockScreen(
                                                                                        ctx.getSource(),
                                                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                                        str(ctx, "facing"),
                                                                                        num(ctx, "blocks_w"),
                                                                                        num(ctx, "blocks_h"),
                                                                                        uiId(ctx),
                                                                                        num(ctx, "px_w"),
                                                                                        num(ctx, "px_h"),
                                                                                        ""))
                                                                                .then(Commands.argument("session_id", StringArgumentType.word())
                                                                                        .executes(ctx -> placeBlockScreen(
                                                                                                ctx.getSource(),
                                                                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                                                str(ctx, "facing"),
                                                                                                num(ctx, "blocks_w"),
                                                                                                num(ctx, "blocks_h"),
                                                                                                uiId(ctx),
                                                                                                num(ctx, "px_w"),
                                                                                                num(ctx, "px_h"),
                                                                                                str(ctx, "session_id"))))
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
                // /ef screen load <pos> <ui_id> [<px_w> <px_h> [<session_id>]]
                .then(Commands.literal("load")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                        .suggests(SUGGEST_DATAPACK_UIS)
                                        .executes(ctx -> loadBlockScreenUi(
                                                ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                uiId(ctx), 0, 0, ""))
                                        .then(Commands.argument("px_w", IntegerArgumentType.integer(MIN_SIZE, MAX_WIDTH))
                                                .then(Commands.argument("px_h", IntegerArgumentType.integer(MIN_SIZE, MAX_HEIGHT))
                                                        .executes(ctx -> loadBlockScreenUi(
                                                                ctx.getSource(),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                uiId(ctx), num(ctx, "px_w"), num(ctx, "px_h"), ""))
                                                        .then(Commands.argument("session_id", StringArgumentType.word())
                                                                .executes(ctx -> loadBlockScreenUi(
                                                                        ctx.getSource(),
                                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                        uiId(ctx), num(ctx, "px_w"), num(ctx, "px_h"), str(ctx, "session_id"))))
                                                )
                                        )
                                )
                        )
                )
                // /ef screen draw <pos> [<facing>]
                .then(Commands.literal("draw")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> drawBlockScreen(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"), ""))
                                .then(Commands.argument("facing", StringArgumentType.word())
                                        .suggests(SUGGEST_FACING)
                                        .executes(ctx -> drawBlockScreen(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"), str(ctx, "facing"))))
                        )
                )
                // /ef screen template <pos> <template_id>
                .then(Commands.literal("template")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                        .suggests(SUGGEST_DATAPACK_UIS)
                                        .executes(ctx -> setBlockScreenTemplate(
                                                ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                ResourceLocationArgument.getId(ctx, "template_id").toString())))
                        )
                )
                // /ef screen patch <pos> text|html|style|attr|class ...
                .then(Commands.literal("patch")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(buildScreenSingleArgPatch("text", (s, el, arg) -> s.patchText(el, arg)))
                                .then(buildScreenSingleArgPatch("html", (s, el, arg) -> s.patchInnerHtml(el, arg)))
                                .then(buildScreenKeyedPatch("style", "property", "value", null,
                                        (s, el, key, arg) -> s.patchStyle(el, key, arg)))
                                .then(buildScreenKeyedPatch("attr", "attr_name", "attr_value", null,
                                        (s, el, key, arg) -> s.patchAttribute(el, key, arg, false)))
                                .then(buildScreenKeyedPatch("class", "mode", "class_name", SUGGEST_PATCH_MODES,
                                        (s, el, mode, className) -> s.patchClass(el, className, mode)))
                        )
                )
                // /ef screen info <pos>
                .then(Commands.literal("info")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> infoBlockScreen(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos")))
                        )
                )
                // /ef screen clear <pos> [<destroy_blocks>]
                .then(Commands.literal("clear")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> clearBlockScreen(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"), false))
                                .then(Commands.argument("destroy_blocks", BoolArgumentType.bool())
                                        .executes(ctx -> clearBlockScreen(
                                                ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                BoolArgumentType.getBool(ctx, "destroy_blocks"))))
                        )
                );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildScreenSingleArgPatch(String name, PatchAction action) {
        return Commands.literal(name)
                .then(Commands.argument("element_id", StringArgumentType.word())
                        .suggests(SUGGEST_SCREEN_ELEMENTS)
                        .then(Commands.argument("arg", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    WebSession session = screenSessionOf(ctx);
                                    if (session == null) return 0;
                                    action.apply(session, str(ctx, "element_id"), str(ctx, "arg"));
                                    return 1;
                                })
                        )
                );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildScreenKeyedPatch(
            String name, String keyArg, String valueArg,
            SuggestionProvider<CommandSourceStack> keySuggestions, KeyedPatchAction action) {
        var keyNode = Commands.argument(keyArg, StringArgumentType.word());
        if (keySuggestions != null) keyNode.suggests(keySuggestions);
        return Commands.literal(name)
                .then(Commands.argument("element_id", StringArgumentType.word())
                        .suggests(SUGGEST_SCREEN_ELEMENTS)
                        .then(keyNode
                                .then(Commands.argument(valueArg, StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            WebSession session = screenSessionOf(ctx);
                                            if (session == null) return 0;
                                            action.apply(session, str(ctx, "element_id"), str(ctx, keyArg), str(ctx, valueArg));
                                            return 1;
                                        })
                                )
                        )
                );
    }

    // ==========================================
    // 3. hologram — ワールド内空間ホログラム操作
    // ==========================================

    private static ArgumentBuilder<CommandSourceStack, ?> buildHologram() {
        return Commands.literal("hologram")
                // /ef hologram spawn <id> <pos> <ui_id> [<width_blocks> <height_blocks> [<orientation> [<interactive> [<px_w> <px_h>]]]]
                .then(Commands.literal("spawn")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(SUGGEST_HOLOGRAMS)
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                                .suggests(SUGGEST_DATAPACK_UIS)
                                                .executes(ctx -> spawnHologramUi(
                                                        ctx.getSource(),
                                                        str(ctx, "id"),
                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                        uiId(ctx),
                                                        2.4f, 1.5f, "billboard", true,
                                                        SURFACE_DEFAULT_WIDTH, SURFACE_DEFAULT_HEIGHT))
                                                .then(Commands.argument("width_blocks", FloatArgumentType.floatArg(0.1f, 32.0f))
                                                        .then(Commands.argument("height_blocks", FloatArgumentType.floatArg(0.1f, 32.0f))
                                                                .executes(ctx -> spawnHologramUi(
                                                                        ctx.getSource(),
                                                                        str(ctx, "id"),
                                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                                        uiId(ctx),
                                                                        FloatArgumentType.getFloat(ctx, "width_blocks"),
                                                                        FloatArgumentType.getFloat(ctx, "height_blocks"),
                                                                        "billboard", true,
                                                                        SURFACE_DEFAULT_WIDTH, SURFACE_DEFAULT_HEIGHT))
                                                                .then(Commands.argument("orientation", StringArgumentType.word())
                                                                        .suggests(SUGGEST_ORIENTATION)
                                                                        .executes(ctx -> spawnHologramUi(
                                                                                ctx.getSource(),
                                                                                str(ctx, "id"),
                                                                                Vec3Argument.getVec3(ctx, "pos"),
                                                                                uiId(ctx),
                                                                                FloatArgumentType.getFloat(ctx, "width_blocks"),
                                                                                FloatArgumentType.getFloat(ctx, "height_blocks"),
                                                                                str(ctx, "orientation"), true,
                                                                                SURFACE_DEFAULT_WIDTH, SURFACE_DEFAULT_HEIGHT))
                                                                        .then(Commands.argument("interactive", BoolArgumentType.bool())
                                                                                .executes(ctx -> spawnHologramUi(
                                                                                        ctx.getSource(),
                                                                                        str(ctx, "id"),
                                                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                                                        uiId(ctx),
                                                                                        FloatArgumentType.getFloat(ctx, "width_blocks"),
                                                                                        FloatArgumentType.getFloat(ctx, "height_blocks"),
                                                                                        str(ctx, "orientation"),
                                                                                        BoolArgumentType.getBool(ctx, "interactive"),
                                                                                        SURFACE_DEFAULT_WIDTH, SURFACE_DEFAULT_HEIGHT))
                                                                                .then(Commands.argument("px_w", IntegerArgumentType.integer(MIN_SIZE, MAX_WIDTH))
                                                                                        .then(Commands.argument("px_h", IntegerArgumentType.integer(MIN_SIZE, MAX_HEIGHT))
                                                                                                .executes(ctx -> spawnHologramUi(
                                                                                                        ctx.getSource(),
                                                                                                        str(ctx, "id"),
                                                                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                                                                        uiId(ctx),
                                                                                                        FloatArgumentType.getFloat(ctx, "width_blocks"),
                                                                                                        FloatArgumentType.getFloat(ctx, "height_blocks"),
                                                                                                        str(ctx, "orientation"),
                                                                                                        BoolArgumentType.getBool(ctx, "interactive"),
                                                                                                        num(ctx, "px_w"),
                                                                                                        num(ctx, "px_h")))))
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
                // /ef hologram bind <id> <pos> <session_id> [<width_blocks> <height_blocks> [<orientation> [<interactive>]]]
                .then(Commands.literal("bind")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(SUGGEST_HOLOGRAMS)
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .then(Commands.argument("session_id", StringArgumentType.string())
                                                .suggests(SUGGEST_SESSIONS)
                                                .executes(ctx -> bindHologramSession(
                                                        ctx.getSource(),
                                                        str(ctx, "id"),
                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                        str(ctx, "session_id"),
                                                        2.4f, 1.5f, "billboard", true))
                                                .then(Commands.argument("width_blocks", FloatArgumentType.floatArg(0.1f, 32.0f))
                                                        .then(Commands.argument("height_blocks", FloatArgumentType.floatArg(0.1f, 32.0f))
                                                                .executes(ctx -> bindHologramSession(
                                                                        ctx.getSource(),
                                                                        str(ctx, "id"),
                                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                                        str(ctx, "session_id"),
                                                                        FloatArgumentType.getFloat(ctx, "width_blocks"),
                                                                        FloatArgumentType.getFloat(ctx, "height_blocks"),
                                                                        "billboard", true))
                                                                .then(Commands.argument("orientation", StringArgumentType.word())
                                                                        .suggests(SUGGEST_ORIENTATION)
                                                                        .executes(ctx -> bindHologramSession(
                                                                                ctx.getSource(),
                                                                                str(ctx, "id"),
                                                                                Vec3Argument.getVec3(ctx, "pos"),
                                                                                str(ctx, "session_id"),
                                                                                FloatArgumentType.getFloat(ctx, "width_blocks"),
                                                                                FloatArgumentType.getFloat(ctx, "height_blocks"),
                                                                                str(ctx, "orientation"), true))
                                                                        .then(Commands.argument("interactive", BoolArgumentType.bool())
                                                                                .executes(ctx -> bindHologramSession(
                                                                                        ctx.getSource(),
                                                                                        str(ctx, "id"),
                                                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                                                        str(ctx, "session_id"),
                                                                                        FloatArgumentType.getFloat(ctx, "width_blocks"),
                                                                                        FloatArgumentType.getFloat(ctx, "height_blocks"),
                                                                                        str(ctx, "orientation"),
                                                                                        BoolArgumentType.getBool(ctx, "interactive"))))
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
                // /ef hologram remove <id>
                .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(SUGGEST_HOLOGRAMS)
                                .executes(ctx -> removeHologramCmd(ctx.getSource(), str(ctx, "id")))
                        )
                )
                // /ef hologram clear
                .then(Commands.literal("clear")
                        .executes(ctx -> clearHologramsCmd(ctx.getSource()))
                )
                // /ef hologram list
                .then(Commands.literal("list")
                        .executes(ctx -> listHologramsCmd(ctx.getSource()))
                );
    }

    // ==========================================
    // 4. item — 手持ちスクリーンアイテム操作
    // ==========================================

    private static ArgumentBuilder<CommandSourceStack, ?> buildItem() {
        return Commands.literal("item")
                // /ef item give [<targets> [<ui_id> [<px_w> <px_h> [<session_id>]]]]
                .then(Commands.literal("give")
                        .executes(ctx -> giveScreenItem(
                                ctx.getSource(),
                                List.of(ctx.getSource().getPlayerOrException()),
                                DEFAULT_SURFACE_UI,
                                SURFACE_DEFAULT_WIDTH, SURFACE_DEFAULT_HEIGHT, ""))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> giveScreenItem(
                                        ctx.getSource(),
                                        targets(ctx),
                                        DEFAULT_SURFACE_UI,
                                        SURFACE_DEFAULT_WIDTH, SURFACE_DEFAULT_HEIGHT, ""))
                                .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                        .suggests(SUGGEST_DATAPACK_UIS)
                                        .executes(ctx -> giveScreenItem(
                                                ctx.getSource(),
                                                targets(ctx),
                                                uiId(ctx),
                                                SURFACE_DEFAULT_WIDTH, SURFACE_DEFAULT_HEIGHT, ""))
                                        .then(Commands.argument("px_w", IntegerArgumentType.integer(MIN_SIZE, MAX_WIDTH))
                                                .then(Commands.argument("px_h", IntegerArgumentType.integer(MIN_SIZE, MAX_HEIGHT))
                                                        .executes(ctx -> giveScreenItem(
                                                                ctx.getSource(),
                                                                targets(ctx),
                                                                uiId(ctx),
                                                                num(ctx, "px_w"), num(ctx, "px_h"), ""))
                                                        .then(Commands.argument("session_id", StringArgumentType.word())
                                                                .executes(ctx -> giveScreenItem(
                                                                        ctx.getSource(),
                                                                        targets(ctx),
                                                                        uiId(ctx),
                                                                        num(ctx, "px_w"), num(ctx, "px_h"),
                                                                        str(ctx, "session_id"))))
                                                )
                                        )
                                )
                        )
                )
                // /ef item bind <ui_id> [<targets> [<px_w> <px_h> [<session_id>]]]
                .then(Commands.literal("bind")
                        .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                .suggests(SUGGEST_DATAPACK_UIS)
                                .executes(ctx -> bindHeldItemUi(
                                        ctx.getSource(),
                                        List.of(ctx.getSource().getPlayerOrException()),
                                        uiId(ctx),
                                        SURFACE_DEFAULT_WIDTH, SURFACE_DEFAULT_HEIGHT, ""))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> bindHeldItemUi(
                                                ctx.getSource(),
                                                targets(ctx),
                                                uiId(ctx),
                                                SURFACE_DEFAULT_WIDTH, SURFACE_DEFAULT_HEIGHT, ""))
                                        .then(Commands.argument("px_w", IntegerArgumentType.integer(MIN_SIZE, MAX_WIDTH))
                                                .then(Commands.argument("px_h", IntegerArgumentType.integer(MIN_SIZE, MAX_HEIGHT))
                                                        .executes(ctx -> bindHeldItemUi(
                                                                ctx.getSource(),
                                                                targets(ctx),
                                                                uiId(ctx),
                                                                num(ctx, "px_w"), num(ctx, "px_h"), ""))
                                                        .then(Commands.argument("session_id", StringArgumentType.word())
                                                                .executes(ctx -> bindHeldItemUi(
                                                                        ctx.getSource(),
                                                                        targets(ctx),
                                                                        uiId(ctx),
                                                                        num(ctx, "px_w"), num(ctx, "px_h"),
                                                                        str(ctx, "session_id"))))
                                                )
                                        )
                                )
                        )
                )
                // /ef item session <session_id> [<targets>]
                .then(Commands.literal("session")
                        .then(Commands.argument("session_id", StringArgumentType.string())
                                .suggests(SUGGEST_SESSIONS)
                                .executes(ctx -> bindHeldItemSession(
                                        ctx.getSource(),
                                        List.of(ctx.getSource().getPlayerOrException()),
                                        str(ctx, "session_id")))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> bindHeldItemSession(
                                                ctx.getSource(),
                                                targets(ctx),
                                                str(ctx, "session_id"))))
                        )
                );
    }

    // ==========================================
    // 5. session — セッション管理
    // ==========================================

    private static ArgumentBuilder<CommandSourceStack, ?> buildSession() {
        return Commands.literal("session")
                .then(Commands.literal("load")
                        .then(Commands.argument("session_id", StringArgumentType.string())
                                .suggests(SUGGEST_SESSIONS)
                                .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                        .suggests(SUGGEST_DATAPACK_UIS)
                                        .executes(ctx -> loadSessionFromDatapack(ctx.getSource(), str(ctx, "session_id"), uiId(ctx),
                                                DEFAULT_WIDTH, DEFAULT_HEIGHT))
                                        .then(Commands.argument("width", IntegerArgumentType.integer(MIN_SIZE, MAX_WIDTH))
                                                .then(Commands.argument("height", IntegerArgumentType.integer(MIN_SIZE, MAX_HEIGHT))
                                                        .executes(ctx -> loadSessionFromDatapack(ctx.getSource(), str(ctx, "session_id"), uiId(ctx),
                                                                num(ctx, "width"), num(ctx, "height")))
                                                )
                                        )
                                )
                        )
                )
                .then(Commands.literal("clear")
                        .then(Commands.argument("session_id", StringArgumentType.string())
                                .suggests(SUGGEST_SESSIONS)
                                .executes(ctx -> removeSessionCmd(ctx.getSource(), str(ctx, "session_id")))
                        )
                )
                .then(Commands.literal("clear_all")
                        .executes(ctx -> clearAllSessionsCmd(ctx.getSource()))
                )
                .then(Commands.literal("list")
                        .executes(ctx -> listSessionsCmd(ctx.getSource()))
                )
                .then(Commands.literal("info")
                        .then(Commands.argument("session_id", StringArgumentType.string())
                                .suggests(SUGGEST_SESSIONS)
                                .executes(ctx -> infoSessionCmd(ctx.getSource(), str(ctx, "session_id")))
                        )
                );
    }

    // ==========================================
    // 6. patch — サーバー権威 DOM の部分更新
    // ==========================================

    private static ArgumentBuilder<CommandSourceStack, ?> buildPatch() {
        return Commands.literal("patch")
                .then(buildSingleArgPatch("text", (s, el, arg) -> s.patchText(el, arg)))
                .then(buildSingleArgPatch("html", (s, el, arg) -> s.patchInnerHtml(el, arg)))
                .then(buildKeyedPatch("style", "property", "value",
                        (s, el, key, arg) -> s.patchStyle(el, key, arg)))
                .then(buildKeyedPatch("attr", "attr_name", "attr_value",
                        (s, el, key, arg) -> s.patchAttribute(el, key, arg, false)))
                .then(buildKeyedPatch("class", "mode", "class_name", SUGGEST_PATCH_MODES,
                        (s, el, mode, className) -> s.patchClass(el, className, mode)));
    }

    // ==========================================
    // 7. state — テンプレート駆動 UIState 操作
    // ==========================================

    private static ArgumentBuilder<CommandSourceStack, ?> buildState() {
        return Commands.literal("state")
                // /ef state set <instance_id> <key> <value>
                .then(Commands.literal("set")
                        .then(Commands.argument("instance_id", StringArgumentType.string())
                                .suggests(SUGGEST_STATES)
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> setUiStateValue(
                                                        ctx.getSource(),
                                                        str(ctx, "instance_id"),
                                                        str(ctx, "key"),
                                                        str(ctx, "value")))
                                        )
                                )
                        )
                )
                // /ef state set_block <pos> <key> <value>
                .then(Commands.literal("set_block")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                                    BlockEntity be = ctx.getSource().getLevel().getBlockEntity(pos);
                                                    BlockPos targetPos = (be instanceof EnderScreenBlockEntity screenBe && screenBe.getScreenMasterPos() != null)
                                                            ? screenBe.getScreenMasterPos() : pos;
                                                    return setUiStateValue(
                                                            ctx.getSource(),
                                                            UIStateManager.blockInstanceId(targetPos),
                                                            str(ctx, "key"),
                                                            str(ctx, "value"));
                                                })
                                        )
                                )
                        )
                )
                // /ef state open <pos> [<targets> [<template_id>]]
                .then(Commands.literal("open")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> openBlockUiState(
                                        ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                        List.of(ctx.getSource().getPlayerOrException()),
                                        ""))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> openBlockUiState(
                                                ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                targets(ctx),
                                                ""))
                                        .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                                .suggests(SUGGEST_DATAPACK_UIS)
                                                .executes(ctx -> openBlockUiState(
                                                        ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        targets(ctx),
                                                        ResourceLocationArgument.getId(ctx, "template_id").toString())))
                                )
                        )
                )
                // /ef state info <instance_id>
                .then(Commands.literal("info")
                        .then(Commands.argument("instance_id", StringArgumentType.string())
                                .suggests(SUGGEST_STATES)
                                .executes(ctx -> infoUiState(ctx.getSource(), str(ctx, "instance_id")))
                        )
                )
                // /ef state info_block <pos>
                .then(Commands.literal("info_block")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                    BlockEntity be = ctx.getSource().getLevel().getBlockEntity(pos);
                                    BlockPos targetPos = (be instanceof EnderScreenBlockEntity screenBe && screenBe.getScreenMasterPos() != null)
                                            ? screenBe.getScreenMasterPos() : pos;
                                    return infoUiState(ctx.getSource(), UIStateManager.blockInstanceId(targetPos));
                                })
                        )
                )
                // /ef state clear <instance_id>
                .then(Commands.literal("clear")
                        .then(Commands.argument("instance_id", StringArgumentType.string())
                                .suggests(SUGGEST_STATES)
                                .executes(ctx -> clearUiState(ctx.getSource(), str(ctx, "instance_id")))
                        )
                )
                // /ef state list
                .then(Commands.literal("list")
                        .executes(ctx -> listUiStates(ctx.getSource()))
                );
    }

    // ==========================================
    // 8. publish — Pub/Sub チャンネル配信
    // ==========================================

    private static ArgumentBuilder<CommandSourceStack, ?> buildPublish() {
        return Commands.literal("publish")
                .then(Commands.argument("channel", StringArgumentType.word())
                        .suggests(SUGGEST_CHANNELS)
                        .then(Commands.argument("snbt", StringArgumentType.greedyString())
                                .executes(ctx -> publishChannelData(ctx.getSource(), str(ctx, "channel"), str(ctx, "snbt")))
                        )
                );
    }

    // ==========================================
    // 引数取得のヘルパ
    // ==========================================

    private static Collection<ServerPlayer> targets(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return EntityArgument.getPlayers(ctx, "targets");
    }

    private static ResourceLocation uiId(CommandContext<CommandSourceStack> ctx) {
        return ResourceLocationArgument.getId(ctx, "ui_id");
    }

    private static String str(CommandContext<CommandSourceStack> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    private static int num(CommandContext<CommandSourceStack> ctx, String name) {
        return IntegerArgumentType.getInteger(ctx, name);
    }

    private static WebSession sessionOf(CommandContext<CommandSourceStack> ctx) {
        String sessionId = str(ctx, "session_id");
        if (!WebSessionManager.isValidSessionId(sessionId)) {
            ctx.getSource().sendFailure(Component.literal("Invalid session id: " + sessionId));
            return null;
        }
        return WebSessionManager.getOrCreateStandaloneSession(sessionId, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    private static WebSession screenSessionOf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        BlockEntity be = ctx.getSource().getLevel().getBlockEntity(pos);
        if (!(be instanceof WebScreenBlockEntity screenBe)) {
            ctx.getSource().sendFailure(Component.literal("No WebScreenBlockEntity at " + pos.toShortString()));
            return null;
        }
        String sessionId = screenBe.getSessionId();
        if (!WebSessionManager.isValidSessionId(sessionId)) {
            ctx.getSource().sendFailure(Component.literal("Invalid screen session id: " + sessionId));
            return null;
        }
        int w = Math.max(MIN_SIZE, screenBe.getScreenWidth() * screenBe.getPixelsPerBlock());
        int h = Math.max(MIN_SIZE, screenBe.getScreenHeight() * screenBe.getPixelsPerBlock());
        return WebSessionManager.getOrCreateStandaloneSession(sessionId, w, h);
    }

    private interface PatchAction { void apply(WebSession s, String elementId, String arg); }
    private interface KeyedPatchAction { void apply(WebSession s, String elementId, String key, String arg); }

    private static ArgumentBuilder<CommandSourceStack, ?> buildSingleArgPatch(String name, PatchAction action) {
        return Commands.literal(name)
                .then(Commands.argument("session_id", StringArgumentType.string())
                        .suggests(SUGGEST_SESSIONS)
                        .then(Commands.argument("element_id", StringArgumentType.word())
                                .suggests(SUGGEST_ELEMENTS)
                                .then(Commands.argument("arg", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            WebSession session = sessionOf(ctx);
                                            if (session == null) return 0;
                                            action.apply(session, str(ctx, "element_id"), str(ctx, "arg"));
                                            return 1;
                                        })
                                )
                        )
                );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildKeyedPatch(String name, String keyArg, String valueArg, KeyedPatchAction action) {
        return buildKeyedPatch(name, keyArg, valueArg, null, action);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildKeyedPatch(
            String name, String keyArg, String valueArg,
            SuggestionProvider<CommandSourceStack> keySuggestions, KeyedPatchAction action) {
        var keyNode = Commands.argument(keyArg, StringArgumentType.word());
        if (keySuggestions != null) keyNode.suggests(keySuggestions);
        return Commands.literal(name)
                .then(Commands.argument("session_id", StringArgumentType.string())
                        .suggests(SUGGEST_SESSIONS)
                        .then(Commands.argument("element_id", StringArgumentType.word())
                                .suggests(SUGGEST_ELEMENTS)
                                .then(keyNode
                                        .then(Commands.argument(valueArg, StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    WebSession session = sessionOf(ctx);
                                                    if (session == null) return 0;
                                                    action.apply(session, str(ctx, "element_id"), str(ctx, keyArg), str(ctx, valueArg));
                                                    return 1;
                                                })
                                        )
                                )
                        )
                );
    }

    // ==========================================
    // 実装: ブロックスクリーン操作
    // ==========================================

    private static Direction parseHorizontalFacing(String raw) {
        if (raw == null) return null;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "north", "n" -> Direction.NORTH;
            case "south", "s" -> Direction.SOUTH;
            case "east", "e" -> Direction.EAST;
            case "west", "w" -> Direction.WEST;
            default -> null;
        };
    }

    private static int placeBlockScreen(
            CommandSourceStack source,
            BlockPos origin,
            String facingStr,
            int blocksW,
            int blocksH,
            ResourceLocation uiId,
            int pxW,
            int pxH,
            String customSessionId
    ) {
        Direction facing = parseHorizontalFacing(facingStr);
        if (facing == null) {
            source.sendFailure(Component.literal("Invalid facing '" + facingStr + "'. Use north, south, east, or west."));
            return 0;
        }
        if (!BuiltInRegistries.BLOCK.containsKey(SCREEN_BLOCK_ID)) {
            source.sendFailure(Component.literal("Screen block is not registered: " + SCREEN_BLOCK_ID));
            return 0;
        }
        Block screenBlock = BuiltInRegistries.BLOCK.get(SCREEN_BLOCK_ID);
        ServerLevel level = source.getLevel();
        Direction right = facing.getCounterClockWise();
        BlockState state = screenBlock.defaultBlockState();

        for (int dy = 0; dy < blocksH; dy++) {
            for (int dx = 0; dx < blocksW; dx++) {
                BlockPos p = origin.relative(right, dx).above(dy);
                level.setBlock(p, state, 3);
                if (level.getBlockEntity(p) instanceof EnderScreenBlockEntity be) {
                    be.setFacing(facing);
                }
            }
        }

        EnderScreenBlockEntity.draw(level, origin);

        if (level.getBlockEntity(origin) instanceof EnderScreenBlockEntity be) {
            BlockPos masterPos = be.getScreenMasterPos() != null ? be.getScreenMasterPos() : origin;
            if (level.getBlockEntity(masterPos) instanceof EnderScreenBlockEntity master) {
                if (customSessionId != null && !customSessionId.isEmpty()) {
                    if (!WebSessionManager.isValidSessionId(customSessionId)) {
                        source.sendFailure(Component.literal("Invalid session id: " + customSessionId));
                        return 0;
                    }
                    master.setCustomSessionId(customSessionId);
                }
                int resolvedW = pxW > 0 ? pxW : Math.max(MIN_SIZE, master.getScreenWidth() * master.getPixelsPerBlock());
                int resolvedH = pxH > 0 ? pxH : Math.max(MIN_SIZE, master.getScreenHeight() * master.getPixelsPerBlock());
                master.setDatapackUi(uiId.toString(), resolvedW, resolvedH);
                ensureBlockScreenSessionLoaded(master);
            }
        }

        source.sendSuccess(() -> Component.literal(
                "Placed " + blocksW + "x" + blocksH + " block screen at " + origin.toShortString()
                        + " (facing=" + facing.getName() + ", ui=" + uiId + ")"), true);
        return 1;
    }

    private static int loadBlockScreenUi(
            CommandSourceStack source,
            BlockPos pos,
            ResourceLocation uiId,
            int pxW,
            int pxH,
            String customSessionId
    ) {
        ServerLevel level = source.getLevel();
        BlockEntity rawBe = level.getBlockEntity(pos);
        if (!(rawBe instanceof EnderScreenBlockEntity screenBe)) {
            source.sendFailure(Component.literal("No EnderScreenBlockEntity at " + pos.toShortString()));
            return 0;
        }
        if (!screenBe.isActive()) {
            EnderScreenBlockEntity.draw(level, pos);
        }
        BlockPos masterPos = screenBe.getScreenMasterPos() != null ? screenBe.getScreenMasterPos() : pos;
        if (!(level.getBlockEntity(masterPos) instanceof EnderScreenBlockEntity master)) {
            source.sendFailure(Component.literal("Master screen block not found at " + masterPos.toShortString()));
            return 0;
        }
        if (customSessionId != null && !customSessionId.isEmpty()) {
            if (!WebSessionManager.isValidSessionId(customSessionId)) {
                source.sendFailure(Component.literal("Invalid session id: " + customSessionId));
                return 0;
            }
            master.setCustomSessionId(customSessionId);
        }
        int resolvedW = pxW > 0 ? pxW : Math.max(MIN_SIZE, master.getScreenWidth() * master.getPixelsPerBlock());
        int resolvedH = pxH > 0 ? pxH : Math.max(MIN_SIZE, master.getScreenHeight() * master.getPixelsPerBlock());
        master.setCustomTemplateId(null);
        master.setDatapackUi(uiId.toString(), resolvedW, resolvedH);

        if (!ensureBlockScreenSessionLoaded(master)) {
            source.sendFailure(Component.literal("Datapack UI not found: " + uiId));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Loaded UI '" + uiId + "' onto block screen at " + masterPos.toShortString()
                        + " (session=" + master.getSessionId() + ", " + resolvedW + "x" + resolvedH + "px)"), true);
        return 1;
    }

    /**
     * ブロックスクリーン（master）に設定された {@code datapackUiId} からサーバーセッションへ HTML を読み込む。
     * チャンク送信時やスクリーン形成時にも呼ばれる。
     */
    public static boolean ensureBlockScreenSessionLoaded(EnderScreenBlockEntity be) {
        if (be == null || !be.isMaster()) return false;
        String uiIdStr = be.getDatapackUiId();
        if (uiIdStr == null || uiIdStr.isEmpty()) return false;
        ResourceLocation uiId = ResourceLocation.tryParse(uiIdStr);
        if (uiId == null) return false;

        String html = resolveServerHtml(uiId);
        if (html == null) return false;

        String sessionId = be.getSessionId();
        if (!WebSessionManager.isValidSessionId(sessionId)) return false;

        int w = be.getUiWidth() > 0 ? be.getUiWidth() : Math.max(MIN_SIZE, be.getScreenWidth() * be.getPixelsPerBlock());
        int h = be.getUiHeight() > 0 ? be.getUiHeight() : Math.max(MIN_SIZE, be.getScreenHeight() * be.getPixelsPerBlock());

        WebSession existing = WebSessionManager.findStandaloneSession(sessionId);
        if (existing == null || existing.getDocument() == null || existing.getDocument().root == null) {
            WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, w, h);
            session.loadHtml(html);
        } else {
            WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, w, h);
            session.loadHtml(html);
        }
        return true;
    }

    private static int drawBlockScreen(CommandSourceStack source, BlockPos pos, String facingStr) {
        ServerLevel level = source.getLevel();
        if (!(level.getBlockEntity(pos) instanceof EnderScreenBlockEntity screenBe)) {
            source.sendFailure(Component.literal("No EnderScreenBlockEntity at " + pos.toShortString()));
            return 0;
        }
        if (facingStr != null && !facingStr.isEmpty()) {
            Direction facing = parseHorizontalFacing(facingStr);
            if (facing == null) {
                source.sendFailure(Component.literal("Invalid facing '" + facingStr + "'. Use north, south, east, or west."));
                return 0;
            }
            screenBe.setFacing(facing);
        }
        EnderScreenBlockEntity.draw(level, pos);
        BlockPos masterPos = screenBe.getScreenMasterPos() != null ? screenBe.getScreenMasterPos() : pos;
        if (level.getBlockEntity(masterPos) instanceof EnderScreenBlockEntity master && master.getDatapackUiId() != null) {
            ensureBlockScreenSessionLoaded(master);
        }
        source.sendSuccess(() -> Component.literal("Formed block screen at " + masterPos.toShortString()
                + " (" + screenBe.getScreenWidth() + "x" + screenBe.getScreenHeight() + " blocks, session=" + screenBe.getSessionId() + ")"), true);
        return 1;
    }

    private static int setBlockScreenTemplate(CommandSourceStack source, BlockPos pos, String templateId) {
        ServerLevel level = source.getLevel();
        if (!(level.getBlockEntity(pos) instanceof EnderScreenBlockEntity screenBe)) {
            source.sendFailure(Component.literal("No EnderScreenBlockEntity at " + pos.toShortString()));
            return 0;
        }
        if (!screenBe.isActive()) {
            EnderScreenBlockEntity.draw(level, pos);
        }
        BlockPos masterPos = screenBe.getScreenMasterPos() != null ? screenBe.getScreenMasterPos() : pos;
        if (!(level.getBlockEntity(masterPos) instanceof EnderScreenBlockEntity master)) {
            source.sendFailure(Component.literal("Master screen block not found at " + masterPos.toShortString()));
            return 0;
        }
        master.setCustomTemplateId(templateId);
        SurfaceViewers.show(master);
        source.sendSuccess(() -> Component.literal("Set template '" + templateId + "' on block screen at " + masterPos.toShortString()), true);
        return 1;
    }

    private static int infoBlockScreen(CommandSourceStack source, BlockPos pos) {
        ServerLevel level = source.getLevel();
        BlockEntity rawBe = level.getBlockEntity(pos);
        if (!(rawBe instanceof WebScreenBlockEntity screenBe)) {
            source.sendFailure(Component.literal("No WebScreenBlockEntity at " + pos.toShortString()));
            return 0;
        }
        StringBuilder sb = new StringBuilder("§b[Screen Info]§r pos=").append(pos.toShortString())
                .append(" active=").append(screenBe.isActive())
                .append(" master=").append(screenBe.isMaster())
                .append(" facing=").append(screenBe.getFacing().getName())
                .append(" size=").append(screenBe.getScreenWidth()).append("x").append(screenBe.getScreenHeight())
                .append(" session=").append(screenBe.getSessionId());
        if (screenBe.getTemplateId() != null) {
            sb.append(" template=").append(screenBe.getTemplateId());
        }
        if (rawBe instanceof EnderScreenBlockEntity enderBe) {
            if (enderBe.getScreenMasterPos() != null) {
                sb.append(" masterPos=").append(enderBe.getScreenMasterPos().toShortString());
            }
            if (enderBe.getDatapackUiId() != null) {
                sb.append(" ui=").append(enderBe.getDatapackUiId());
            }
        }
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int clearBlockScreen(CommandSourceStack source, BlockPos pos, boolean destroyBlocks) {
        ServerLevel level = source.getLevel();
        if (!(level.getBlockEntity(pos) instanceof EnderScreenBlockEntity screenBe)) {
            source.sendFailure(Component.literal("No EnderScreenBlockEntity at " + pos.toShortString()));
            return 0;
        }
        BlockPos masterPos = screenBe.getScreenMasterPos() != null ? screenBe.getScreenMasterPos() : pos;
        Direction facing = screenBe.getFacing();
        Direction right = facing.getCounterClockWise();
        int w = screenBe.getScreenWidth();
        int h = screenBe.getScreenHeight();
        boolean wasActive = screenBe.isActive();

        screenBe.clearScreen();

        if (destroyBlocks) {
            if (wasActive) {
                for (int dy = 0; dy < h; dy++) {
                    for (int dx = 0; dx < w; dx++) {
                        BlockPos p = masterPos.relative(right, dx).above(dy);
                        if (level.getBlockEntity(p) instanceof EnderScreenBlockEntity) {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            } else {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }

        source.sendSuccess(() -> Component.literal("Cleared block screen at " + masterPos.toShortString()
                + (destroyBlocks ? " (blocks removed)" : "")), true);
        return 1;
    }

    // ==========================================
    // 実装: ホログラム操作 & 同期
    // ==========================================

    private static record OrientationSpec(boolean billboard, float yaw) {}

    private static OrientationSpec parseOrientation(String raw) {
        if (raw == null || raw.isEmpty() || "billboard".equalsIgnoreCase(raw)) {
            return new OrientationSpec(true, 0.0f);
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "south", "s" -> new OrientationSpec(false, 0.0f);
            case "west", "w" -> new OrientationSpec(false, 90.0f);
            case "north", "n" -> new OrientationSpec(false, 180.0f);
            case "east", "e" -> new OrientationSpec(false, 270.0f);
            default -> {
                try {
                    yield new OrientationSpec(false, Float.parseFloat(raw));
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
        };
    }

    public static int spawnHologramUi(
            CommandSourceStack source,
            String id,
            Vec3 pos,
            ResourceLocation uiId,
            float widthBlocks,
            float heightBlocks,
            String orientationStr,
            boolean interactive,
            int pxW,
            int pxH
    ) {
        String html = resolveServerHtml(uiId);
        if (html == null) {
            source.sendFailure(Component.literal("Datapack UI not found: " + uiId));
            return 0;
        }
        String sessionId = "holo_" + id.replaceAll("[^a-zA-Z0-9_\\-:.]", "_");
        if (!WebSessionManager.isValidSessionId(sessionId)) {
            source.sendFailure(Component.literal("Invalid hologram id: " + id));
            return 0;
        }
        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, pxW, pxH);
        session.loadHtml(html);

        return registerAndBroadcastHologram(source, id, pos, sessionId, widthBlocks, heightBlocks, orientationStr, interactive);
    }

    public static int bindHologramSession(
            CommandSourceStack source,
            String id,
            Vec3 pos,
            String sessionId,
            float widthBlocks,
            float heightBlocks,
            String orientationStr,
            boolean interactive
    ) {
        if (!WebSessionManager.isValidSessionId(sessionId)) {
            source.sendFailure(Component.literal("Invalid session id: " + sessionId));
            return 0;
        }
        return registerAndBroadcastHologram(source, id, pos, sessionId, widthBlocks, heightBlocks, orientationStr, interactive);
    }

    private static int registerAndBroadcastHologram(
            CommandSourceStack source,
            String id,
            Vec3 pos,
            String sessionId,
            float widthBlocks,
            float heightBlocks,
            String orientationStr,
            boolean interactive
    ) {
        OrientationSpec orient = parseOrientation(orientationStr);
        if (orient == null) {
            source.sendFailure(Component.literal("Invalid orientation '" + orientationStr + "'. Use billboard, north, south, east, west, or yaw angle."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        ServerHologram holo = new ServerHologram(
                id,
                level.dimension().location(),
                sessionId,
                pos.x, pos.y, pos.z,
                widthBlocks, heightBlocks,
                orient.yaw(),
                orient.billboard(),
                32.0,
                interactive
        );
        HologramRegistry.register(holo);

        CompoundTag payload = holo.toPayload();
        WebSession standalone = WebSessionManager.findStandaloneSession(sessionId);
        for (ServerPlayer player : level.players()) {
            if (standalone != null) {
                WebSessionManager.forceSyncToPlayer(player, sessionId);
            }
            ServerPacketSender.send(player, new WebSyncS2CPacket(sessionId, "set_hologram", payload));
        }

        source.sendSuccess(() -> Component.literal(
                "Spawned hologram '" + id + "' at (" + String.format(Locale.ROOT, "%.1f, %.1f, %.1f", pos.x, pos.y, pos.z)
                        + ") session=" + sessionId), true);
        return 1;
    }

    public static int removeHologramCmd(CommandSourceStack source, String id) {
        ServerHologram removed = HologramRegistry.remove(id);
        if (removed == null) {
            source.sendFailure(Component.literal("Hologram not found: " + id));
            return 0;
        }
        CompoundTag payload = new CompoundTag();
        payload.putString("HologramId", id);
        MinecraftServer server = source.getServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerPacketSender.send(player, new WebSyncS2CPacket("", "remove_hologram", payload));
            }
        }
        source.sendSuccess(() -> Component.literal("Removed hologram: " + id), true);
        return 1;
    }

    public static int clearHologramsCmd(CommandSourceStack source) {
        int count = HologramRegistry.clear();
        CompoundTag payload = new CompoundTag();
        MinecraftServer server = source.getServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerPacketSender.send(player, new WebSyncS2CPacket("", "clear_holograms", payload));
            }
        }
        source.sendSuccess(() -> Component.literal("Cleared " + count + " hologram(s)."), true);
        return count;
    }

    private static int listHologramsCmd(CommandSourceStack source) {
        if (HologramRegistry.count() == 0) {
            source.sendSuccess(() -> Component.literal("No active holograms."), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder("§b[Active Holograms (").append(HologramRegistry.count()).append(")]§r");
        for (ServerHologram h : HologramRegistry.all()) {
            sb.append("\n - ").append(h.id())
                    .append(" [").append(h.dimension()).append("] ")
                    .append(String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", h.x(), h.y(), h.z()))
                    .append(" size=").append(h.widthBlocks()).append("x").append(h.heightBlocks())
                    .append(" session=").append(h.sessionId());
        }
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return HologramRegistry.count();
    }

    // ==========================================
    // 実装: 手持ちアイテムスクリーン操作
    // ==========================================

    private static int giveScreenItem(
            CommandSourceStack source,
            Collection<ServerPlayer> targets,
            ResourceLocation uiId,
            int pxW,
            int pxH,
            String customSessionId
    ) {
        String html = resolveServerHtml(uiId);
        if (html == null) {
            source.sendFailure(Component.literal("Datapack UI not found: " + uiId));
            return 0;
        }
        String sessionId = (customSessionId == null || customSessionId.isEmpty())
                ? ("item_" + uiId.getNamespace() + "_" + uiId.getPath().replace('/', '_'))
                : customSessionId;
        if (!WebSessionManager.isValidSessionId(sessionId)) {
            source.sendFailure(Component.literal("Invalid session id: " + sessionId));
            return 0;
        }

        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, pxW, pxH);
        session.loadHtml(html);

        Item item = BuiltInRegistries.ITEM.containsKey(SCREEN_ITEM_ID)
                ? BuiltInRegistries.ITEM.get(SCREEN_ITEM_ID)
                : Items.FILLED_MAP;

        for (ServerPlayer player : targets) {
            ItemStack stack = new ItemStack(item);
            setItemSessionId(stack, sessionId);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("EnderScreen [" + uiId + "]"));
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            WebSessionManager.forceSyncToPlayer(player, sessionId);
        }

        source.sendSuccess(() -> Component.literal(
                "Gave screen item (" + uiId + ", session=" + sessionId + ") to " + targets.size() + " player(s)."), true);
        return targets.size();
    }

    private static int bindHeldItemUi(
            CommandSourceStack source,
            Collection<ServerPlayer> targets,
            ResourceLocation uiId,
            int pxW,
            int pxH,
            String customSessionId
    ) {
        String html = resolveServerHtml(uiId);
        if (html == null) {
            source.sendFailure(Component.literal("Datapack UI not found: " + uiId));
            return 0;
        }
        String sessionId = (customSessionId == null || customSessionId.isEmpty())
                ? ("item_" + uiId.getNamespace() + "_" + uiId.getPath().replace('/', '_'))
                : customSessionId;
        if (!WebSessionManager.isValidSessionId(sessionId)) {
            source.sendFailure(Component.literal("Invalid session id: " + sessionId));
            return 0;
        }

        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, pxW, pxH);
        session.loadHtml(html);

        int updated = bindSessionToHeldItems(source, targets, sessionId);
        if (updated > 0) {
            for (ServerPlayer player : targets) {
                WebSessionManager.forceSyncToPlayer(player, sessionId);
            }
        }
        return updated;
    }

    private static int bindHeldItemSession(
            CommandSourceStack source,
            Collection<ServerPlayer> targets,
            String sessionId
    ) {
        if (!WebSessionManager.isValidSessionId(sessionId)) {
            source.sendFailure(Component.literal("Invalid session id: " + sessionId));
            return 0;
        }
        return bindSessionToHeldItems(source, targets, sessionId);
    }

    private static int bindSessionToHeldItems(CommandSourceStack source, Collection<ServerPlayer> targets, String sessionId) {
        int count = 0;
        for (ServerPlayer player : targets) {
            ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (held.isEmpty()) continue;
            if (!(held.getItem() instanceof EnderScreenItem) && !held.is(Items.FILLED_MAP)) {
                Item screenItem = BuiltInRegistries.ITEM.containsKey(SCREEN_ITEM_ID)
                        ? BuiltInRegistries.ITEM.get(SCREEN_ITEM_ID)
                        : Items.FILLED_MAP;
                ItemStack replaced = new ItemStack(screenItem, held.getCount());
                setItemSessionId(replaced, sessionId);
                player.setItemInHand(InteractionHand.MAIN_HAND, replaced);
            } else {
                setItemSessionId(held, sessionId);
            }
            count++;
        }
        if (count == 0) {
            source.sendFailure(Component.literal("Target player(s) are not holding an item in main hand."));
            return 0;
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("Bound session '" + sessionId + "' to held item for " + finalCount + " player(s)."), true);
        return count;
    }

    private static void setItemSessionId(ItemStack stack, String sessionId) {
        CustomData existing = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = existing.copyTag();
        tag.putString("SessionId", sessionId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    // ==========================================
    // 実装: UIState 操作
    // ==========================================

    private static Object parseStateValue(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if ("true".equalsIgnoreCase(trimmed)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(trimmed)) return Boolean.FALSE;
        try {
            if (trimmed.contains(".")) {
                return Double.parseDouble(trimmed);
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
        }
        if (trimmed.length() >= 2 && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return raw;
    }

    private static int setUiStateValue(CommandSourceStack source, String instanceId, String key, String rawValue) {
        UIState state = UIStateManager.findState(instanceId);
        if (state == null) {
            source.sendFailure(Component.literal("UIState not found: " + instanceId));
            return 0;
        }
        Object parsed = parseStateValue(rawValue);
        state.set(key, parsed);
        source.sendSuccess(() -> Component.literal("Updated UIState '" + instanceId + "' [" + key + " = " + parsed + "]"), true);
        return 1;
    }

    private static int openBlockUiState(
            CommandSourceStack source,
            BlockPos pos,
            Collection<ServerPlayer> targets,
            String explicitTemplateId
    ) {
        ServerLevel level = source.getLevel();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            source.sendFailure(Component.literal("No BlockEntity at " + pos.toShortString()));
            return 0;
        }
        if (be instanceof EnderScreenBlockEntity screenBe && screenBe.getScreenMasterPos() != null) {
            BlockEntity master = level.getBlockEntity(screenBe.getScreenMasterPos());
            if (master != null) be = master;
        }
        String templateId = explicitTemplateId;
        if ((templateId == null || templateId.isEmpty()) && be instanceof WebScreenBlockEntity webBe) {
            templateId = webBe.getTemplateId();
        }
        if (templateId == null || templateId.isEmpty()) {
            templateId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString();
        }
        UIState state = UIStateManager.getOrCreateBlockEntityState(templateId, be);
        for (ServerPlayer player : targets) {
            state.addViewer(player);
        }
        String finalTemplate = templateId;
        source.sendSuccess(() -> Component.literal(
                "Opened UIState '" + state.instanceId() + "' (template=" + finalTemplate + ") for " + targets.size() + " player(s)."), true);
        return targets.size();
    }

    private static int infoUiState(CommandSourceStack source, String instanceId) {
        UIState state = UIStateManager.findState(instanceId);
        if (state == null) {
            source.sendFailure(Component.literal("UIState not found: " + instanceId));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "§b[UIState Info]§r id=" + state.instanceId()
                        + " template=" + state.templateId()
                        + " viewers=" + state.viewers().size()
                        + " data=" + state.snapshot()), false);
        return 1;
    }

    private static int clearUiState(CommandSourceStack source, String instanceId) {
        if (UIStateManager.findState(instanceId) == null) {
            source.sendFailure(Component.literal("UIState not found: " + instanceId));
            return 0;
        }
        UIStateManager.removeState(instanceId);
        source.sendSuccess(() -> Component.literal("Cleared UIState: " + instanceId), true);
        return 1;
    }

    private static int listUiStates(CommandSourceStack source) {
        Set<String> ids = UIStateManager.stateIds();
        if (ids.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active UIState instances."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§b[Active UIStates (" + ids.size() + ")]§r " + String.join(", ", ids)), false);
        return ids.size();
    }

    // ==========================================
    // 実装: セッション・画面・HUD・Pub/Sub・DevKit
    // ==========================================

    private static int removeSessionCmd(CommandSourceStack source, String sessionId) {
        WebSessionManager.removeStandaloneSession(sessionId);
        source.sendSuccess(() -> Component.literal("Cleared session: " + sessionId), true);
        return 1;
    }

    private static int clearAllSessionsCmd(CommandSourceStack source) {
        int count = WebSessionManager.standaloneSessionCount();
        WebSessionManager.clearStandaloneSessions();
        source.sendSuccess(() -> Component.literal("Cleared all " + count + " standalone session(s)."), true);
        return count;
    }

    private static int listSessionsCmd(CommandSourceStack source) {
        Set<String> ids = WebSessionManager.standaloneSessionIds();
        if (ids.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No standalone sessions."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§b[Active Sessions (" + ids.size() + ")]§r " + String.join(", ", ids)), false);
        return ids.size();
    }

    private static int infoSessionCmd(CommandSourceStack source, String sessionId) {
        WebSession session = WebSessionManager.findStandaloneSession(sessionId);
        if (session == null) {
            source.sendFailure(Component.literal("Session not found: " + sessionId));
            return 0;
        }
        Set<String> elementIds = new LinkedHashSet<>();
        if (session.getDocument() != null && session.getDocument().root != null) {
            collectElementIds(session.getDocument().root, elementIds);
        }
        source.sendSuccess(() -> Component.literal(
                "§b[Session Info]§r id=" + session.getSessionId()
                        + " size=" + session.getWidth() + "x" + session.getHeight()
                        + " elements=" + (elementIds.isEmpty() ? "(none)" : String.join(", ", elementIds))), false);
        return 1;
    }

    private static int exportBrowserDevKit(CommandSourceStack source) {
        try {
            Path outDir = WebDevKitExporter.exportDevKit(FMLPaths.GAMEDIR.get());
            source.sendSuccess(() -> Component.literal("§a[EnderFrameLib] Exported Browser Preview DevKit to: §f" + outDir.toAbsolutePath()), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to export Browser DevKit: " + e.getMessage()));
            return 0;
        }
    }

    private static String resolveServerHtml(ResourceLocation uiId) {
        String html = EnderFrameDataPackManager.getHtml(uiId);
        if ((html == null || html.isEmpty()) && UiDocumentCompiler.resourceTextResolver != null) {
            html = UiDocumentCompiler.resourceTextResolver.apply(uiId.toString());
        }
        return (html != null && !html.isEmpty()) ? html : null;
    }

    private static int openDatapackUi(CommandSourceStack source, Collection<ServerPlayer> targets, ResourceLocation uiId, String customSessionId, int w, int h) {
        String html = resolveServerHtml(uiId);
        String sessionId = (customSessionId == null || customSessionId.isEmpty())
                ? ("dp_ui_" + uiId.getNamespace() + "_" + uiId.getPath().replace('/', '_'))
                : customSessionId;

        if (!WebSessionManager.isValidSessionId(sessionId)) {
            source.sendFailure(Component.literal("Invalid session id: " + sessionId));
            return 0;
        }

        if (html != null) {
            WebSession session = WebSessionManager.getOrCreateStandaloneSession(
                    sessionId, w > 0 ? w : DEFAULT_WIDTH, h > 0 ? h : DEFAULT_HEIGHT);
            session.loadHtml(html);
        }

        CompoundTag payload = new CompoundTag();
        payload.putString("Title", uiId.toString());
        payload.putInt("Width", w);
        payload.putInt("Height", h);
        payload.putString("ResourceLocation", uiId.toString());

        for (ServerPlayer player : targets) {
            if (html != null) WebSessionManager.forceSyncToPlayer(player, sessionId);
            ServerPacketSender.send(player, new WebSyncS2CPacket(sessionId, "open_screen", payload));
        }
        source.sendSuccess(() -> Component.literal("Opened Web UI '" + uiId + "' for " + targets.size() + " player(s)."), false);
        return targets.size();
    }

    private static int openInlineUi(CommandSourceStack source, Collection<ServerPlayer> targets, String html) {
        String sessionId = "inline_ui_" + Math.abs(html.hashCode());
        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        session.loadHtml(html);

        CompoundTag payload = new CompoundTag();
        payload.putString("Title", "EnderFrame UI");
        payload.putInt("Width", 0);
        payload.putInt("Height", 0);

        for (ServerPlayer player : targets) {
            WebSessionManager.forceSyncToPlayer(player, sessionId);
            ServerPacketSender.send(player, new WebSyncS2CPacket(sessionId, "open_screen", payload));
        }
        return targets.size();
    }

    private static int setPlayerHud(CommandSourceStack source, Collection<ServerPlayer> targets, ResourceLocation uiId, String customSessionId) {
        String html = resolveServerHtml(uiId);
        String sessionId = (customSessionId == null || customSessionId.isEmpty())
                ? ("hud_" + uiId.getNamespace() + "_" + uiId.getPath().replace('/', '_'))
                : customSessionId;

        if (!WebSessionManager.isValidSessionId(sessionId)) {
            source.sendFailure(Component.literal("Invalid session id: " + sessionId));
            return 0;
        }

        if (html != null) {
            WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, HUD_WIDTH, HUD_HEIGHT);
            session.loadHtml(html);
        }

        CompoundTag payload = new CompoundTag();
        payload.putString("ResourceLocation", uiId.toString());

        for (ServerPlayer player : targets) {
            if (html != null) WebSessionManager.forceSyncToPlayer(player, sessionId);
            ServerPacketSender.send(player, new WebSyncS2CPacket(sessionId, "set_hud", payload));
        }
        source.sendSuccess(() -> Component.literal("Set Web HUD '" + uiId + "' for " + targets.size() + " player(s)."), true);
        return targets.size();
    }

    private static int clearPlayerHud(CommandSourceStack source, Collection<ServerPlayer> targets) {
        CompoundTag payload = new CompoundTag();
        for (ServerPlayer player : targets) {
            ServerPacketSender.send(player, new WebSyncS2CPacket("", "clear_hud", payload));
        }
        source.sendSuccess(() -> Component.literal("Cleared Web HUD for " + targets.size() + " player(s)."), true);
        return targets.size();
    }

    private static int loadSessionFromDatapack(CommandSourceStack source, String sessionId, ResourceLocation uiId, int w, int h) {
        String html = resolveServerHtml(uiId);
        if (!WebSessionManager.isValidSessionId(sessionId)) {
            source.sendFailure(Component.literal("Invalid session id: " + sessionId));
            return 0;
        }
        if (html == null) {
            source.sendFailure(Component.literal("Datapack UI not found: " + uiId + " (Place at data/" + uiId.getNamespace() + "/enderframe_ui/" + uiId.getPath() + ".html)"));
            return 0;
        }
        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, w, h);
        session.loadHtml(html);
        source.sendSuccess(() -> Component.literal("Loaded UI '" + uiId + "' into session '" + sessionId + "'."), true);
        return 1;
    }

    private static int publishChannelData(CommandSourceStack source, String channel, String snbt) {
        try {
            CompoundTag parsed = TagParser.parseTag(snbt);
            Object converted = convertNbtToObject(parsed);
            if (converted instanceof Map map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> castedMap = (Map<String, Object>) map;
                ServerPubSubManager.publish(channel, castedMap);
            }
            source.sendSuccess(() -> Component.literal("Published data to channel '" + channel + "'."), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Invalid SNBT/JSON format: " + e.getMessage()));
            return 0;
        }
    }

    private static Object convertNbtToObject(Tag t) {
        if (t instanceof NumericTag num) {
            if (t.getId() == Tag.TAG_DOUBLE) return num.getAsDouble();
            if (t.getId() == Tag.TAG_FLOAT) return num.getAsFloat();
            if (t.getId() == Tag.TAG_BYTE) {
                String str = t.getAsString();
                if ("true".equals(str) || "false".equals(str) || str.endsWith("b")) {
                    return num.getAsByte() != 0;
                }
            }
            if (t.getId() == Tag.TAG_INT || t.getId() == Tag.TAG_SHORT) return num.getAsInt();
            return num.getAsLong();
        } else if (t instanceof StringTag st) {
            return st.getAsString();
        } else if (t instanceof ListTag lt) {
            List<Object> list = new ArrayList<>();
            for (Tag child : lt) list.add(convertNbtToObject(child));
            return list;
        } else if (t instanceof CompoundTag ct) {
            Map<String, Object> map = new HashMap<>();
            for (String key : ct.getAllKeys()) map.put(key, convertNbtToObject(ct.get(key)));
            return map;
        }
        return t != null ? t.getAsString() : null;
    }

    private static int openDesktopScreen(CommandSourceStack source, Collection<ServerPlayer> targets, String initialWindow) {
        CompoundTag payload = new CompoundTag();
        if (initialWindow != null && !initialWindow.isEmpty()) {
            payload.putString("InitialWindow", initialWindow);
        }
        for (ServerPlayer player : targets) {
            ServerPacketSender.send(player, new WebSyncS2CPacket("", "open_desktop", payload));
        }
        source.sendSuccess(() -> Component.literal("Opened EnderFrame UI (" + (initialWindow != null ? initialWindow : "default") + ") for " + targets.size() + " player(s)."), false);
        return targets.size();
    }
}
