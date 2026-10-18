package com.nishiyu.enderframelib.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.nishiyu.enderframelib.common.UIParser;
import com.nishiyu.enderframelib.common.WebDevKitExporter;
import com.nishiyu.enderframelib.network.WebSyncS2CPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@org.jetbrains.annotations.ApiStatus.Internal
public class EnderFrameCommands {

    private static final int DEFAULT_WIDTH = 854;
    private static final int DEFAULT_HEIGHT = 480;
    private static final int HUD_WIDTH = 1280;
    private static final int HUD_HEIGHT = 720;
    private static final int MIN_SIZE = 32;
    private static final int MAX_WIDTH = 3840;
    private static final int MAX_HEIGHT = 2160;

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_DATAPACK_UIS = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(EnderFrameDataPackManager.getAvailableShortIds(), builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PATCH_MODES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(new String[]{"add", "remove", "toggle"}, builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("enderframe")
                .requires(src -> src.hasPermission(2))
                .then(buildOpen())
                .then(buildOpenInline())
                .then(buildHud())
                .then(buildSession())
                .then(buildPatch())
                .then(buildPublish())
                .then(Commands.literal("devkit").executes(ctx -> exportBrowserDevKit(ctx.getSource())))
        );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildOpen() {
        return Commands.literal("open")
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                .suggests(SUGGEST_DATAPACK_UIS)
                                .executes(ctx -> openDatapackUi(ctx.getSource(), targets(ctx), uiId(ctx), "", 0, 0))
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
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                        .suggests(SUGGEST_DATAPACK_UIS)
                                        .executes(ctx -> setPlayerHud(ctx.getSource(), targets(ctx), uiId(ctx)))
                                )
                        )
                )
                .then(Commands.literal("clear")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> clearPlayerHud(ctx.getSource(), targets(ctx)))
                        )
                );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildSession() {
        return Commands.literal("session")
                .then(Commands.literal("load")
                        .then(Commands.argument("session_id", StringArgumentType.string())
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
                                .executes(ctx -> clearSession(ctx.getSource(), str(ctx, "session_id")))
                        )
                );
    }

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

    private static ArgumentBuilder<CommandSourceStack, ?> buildPublish() {
        return Commands.literal("publish")
                .then(Commands.argument("channel", StringArgumentType.word())
                        .then(Commands.argument("snbt", StringArgumentType.greedyString())
                                .executes(ctx -> publishChannelData(ctx.getSource(), str(ctx, "channel"), str(ctx, "snbt")))
                        )
                );
    }

    // ==========================================
    // 引数取得のヘルパ（Brigadier の呼び出しが長くなるのを避ける）
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

    /**
     * {@code session_id} 引数のセッションを取得する。
     * ID の文字種が不正な場合は失敗メッセージを出して null を返す。
     */
    private static WebSession sessionOf(CommandContext<CommandSourceStack> ctx) {
        String sessionId = str(ctx, "session_id");
        if (!WebSessionManager.isValidSessionId(sessionId)) {
            ctx.getSource().sendFailure(Component.literal("Invalid session id: " + sessionId));
            return null;
        }
        return WebSessionManager.getOrCreateStandaloneSession(sessionId, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    private static int clearSession(CommandSourceStack source, String sessionId) {
        WebSessionManager.removeStandaloneSession(sessionId);
        source.sendSuccess(() -> Component.literal("Cleared session: " + sessionId), true);
        return 1;
    }

    /** {@code patch <op> <session> <element> <arg>} */
    private interface PatchAction { void apply(WebSession s, String elementId, String arg); }

    /** {@code patch <op> <session> <element> <key> <arg>} */
    private interface KeyedPatchAction { void apply(WebSession s, String elementId, String key, String arg); }

    private static ArgumentBuilder<CommandSourceStack, ?> buildSingleArgPatch(String name, PatchAction action) {
        return Commands.literal(name)
                .then(Commands.argument("session_id", StringArgumentType.string())
                        .then(Commands.argument("element_id", StringArgumentType.word())
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
                        .then(Commands.argument("element_id", StringArgumentType.word())
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
        if ((html == null || html.isEmpty()) && UIParser.resourceTextResolver != null) {
            html = UIParser.resourceTextResolver.apply(uiId.toString());
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

    private static int setPlayerHud(CommandSourceStack source, Collection<ServerPlayer> targets, ResourceLocation uiId) {
        String html = resolveServerHtml(uiId);
        String sessionId = "hud_" + uiId.getNamespace() + "_" + uiId.getPath().replace('/', '_');

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
}