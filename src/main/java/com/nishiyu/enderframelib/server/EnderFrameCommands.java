package com.nishiyu.enderframelib.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.nishiyu.enderframelib.common.UIParser;
import com.nishiyu.enderframelib.common.WebDevKitExporter;
import com.nishiyu.enderframelib.network.s2c.WebSyncS2CPacket;
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
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EnderFrameCommands {

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_DATAPACK_UIS = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(EnderFrameDataPackManager.getAvailableShortIds(), builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PATCH_MODES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(new String[]{"add", "remove", "toggle"}, builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("enderframe")
                .requires(src -> src.hasPermission(2))

                // 1. /enderframe open
                .then(Commands.literal("open")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                        .suggests(SUGGEST_DATAPACK_UIS)
                                        .executes(ctx -> openDatapackUi(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"), ResourceLocationArgument.getId(ctx, "ui_id"), "", 0, 0))
                                        .then(Commands.argument("session_id", StringArgumentType.word())
                                                .executes(ctx -> openDatapackUi(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"), ResourceLocationArgument.getId(ctx, "ui_id"), StringArgumentType.getString(ctx, "session_id"), 0, 0))
                                                .then(Commands.argument("width", IntegerArgumentType.integer(0, 3840))
                                                        .then(Commands.argument("height", IntegerArgumentType.integer(0, 2160))
                                                                .executes(ctx -> openDatapackUi(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"), ResourceLocationArgument.getId(ctx, "ui_id"), StringArgumentType.getString(ctx, "session_id"), IntegerArgumentType.getInteger(ctx, "width"), IntegerArgumentType.getInteger(ctx, "height")))
                                                        )
                                                )
                                        )
                                )
                        )
                )

                // 2. /enderframe open_inline
                .then(Commands.literal("open_inline")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("html", StringArgumentType.greedyString())
                                        .executes(ctx -> openInlineUi(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"), StringArgumentType.getString(ctx, "html")))
                                )
                        )
                )

                // 3. /enderframe hud
                .then(Commands.literal("hud")
                        .then(Commands.literal("set")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                                .suggests(SUGGEST_DATAPACK_UIS)
                                                .executes(ctx -> setPlayerHud(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"), ResourceLocationArgument.getId(ctx, "ui_id")))
                                        )
                                )
                        )
                        .then(Commands.literal("clear")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> clearPlayerHud(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))
                                )
                        )
                )

                // 4. /enderframe session
                .then(Commands.literal("session")
                        .then(Commands.literal("load")
                                .then(Commands.argument("session_id", StringArgumentType.string())
                                        .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                                .suggests(SUGGEST_DATAPACK_UIS)
                                                .executes(ctx -> loadSessionFromDatapack(ctx.getSource(), StringArgumentType.getString(ctx, "session_id"), ResourceLocationArgument.getId(ctx, "ui_id"), 854, 480))
                                                .then(Commands.argument("width", IntegerArgumentType.integer(32, 3840))
                                                        .then(Commands.argument("height", IntegerArgumentType.integer(32, 2160))
                                                                .executes(ctx -> loadSessionFromDatapack(ctx.getSource(), StringArgumentType.getString(ctx, "session_id"), ResourceLocationArgument.getId(ctx, "ui_id"), IntegerArgumentType.getInteger(ctx, "width"), IntegerArgumentType.getInteger(ctx, "height")))
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("clear")
                                .then(Commands.argument("session_id", StringArgumentType.string())
                                        .executes(ctx -> {
                                            String sid = StringArgumentType.getString(ctx, "session_id");
                                            WebSessionManager.removeStandaloneSession(sid);
                                            ctx.getSource().sendSuccess(() -> Component.literal("Cleared session: " + sid), true);
                                            return 1;
                                        })
                                )
                        )
                )

                // 5. /enderframe patch
                .then(Commands.literal("patch")
                        .then(buildPatchCommand("text", (s, el, arg) -> s.patchText(el, arg)))
                        .then(Commands.literal("style")
                                .then(Commands.argument("session_id", StringArgumentType.string())
                                        .then(Commands.argument("element_id", StringArgumentType.word())
                                                .then(Commands.argument("property", StringArgumentType.word())
                                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                                .executes(ctx -> {
                                                                    WebSession s = WebSessionManager.getOrCreateStandaloneSession(StringArgumentType.getString(ctx, "session_id"), 854, 480);
                                                                    s.patchStyle(StringArgumentType.getString(ctx, "element_id"), StringArgumentType.getString(ctx, "property"), StringArgumentType.getString(ctx, "value"));
                                                                    return 1;
                                                                })
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("attr")
                                .then(Commands.argument("session_id", StringArgumentType.string())
                                        .then(Commands.argument("element_id", StringArgumentType.word())
                                                .then(Commands.argument("attr_name", StringArgumentType.word())
                                                        .then(Commands.argument("attr_value", StringArgumentType.greedyString())
                                                                .executes(ctx -> {
                                                                    WebSession s = WebSessionManager.getOrCreateStandaloneSession(StringArgumentType.getString(ctx, "session_id"), 854, 480);
                                                                    s.patchAttribute(StringArgumentType.getString(ctx, "element_id"), StringArgumentType.getString(ctx, "attr_name"), StringArgumentType.getString(ctx, "attr_value"), false);
                                                                    return 1;
                                                                })
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("class")
                                .then(Commands.argument("session_id", StringArgumentType.string())
                                        .then(Commands.argument("element_id", StringArgumentType.word())
                                                .then(Commands.argument("mode", StringArgumentType.word()).suggests(SUGGEST_PATCH_MODES)
                                                        .then(Commands.argument("class_name", StringArgumentType.greedyString())
                                                                .executes(ctx -> {
                                                                    WebSession s = WebSessionManager.getOrCreateStandaloneSession(StringArgumentType.getString(ctx, "session_id"), 854, 480);
                                                                    s.patchClass(StringArgumentType.getString(ctx, "element_id"), StringArgumentType.getString(ctx, "class_name"), StringArgumentType.getString(ctx, "mode"));
                                                                    return 1;
                                                                })
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(buildPatchCommand("html", (s, el, arg) -> s.patchInnerHtml(el, arg)))
                )

                // 6. /enderframe publish
                .then(Commands.literal("publish")
                        .then(Commands.argument("channel", StringArgumentType.word())
                                .then(Commands.argument("snbt", StringArgumentType.greedyString())
                                        .executes(ctx -> publishChannelData(ctx.getSource(), StringArgumentType.getString(ctx, "channel"), StringArgumentType.getString(ctx, "snbt")))
                                )
                        )
                )

                // 7. /enderframe devkit
                .then(Commands.literal("devkit")
                        .executes(ctx -> exportBrowserDevKit(ctx.getSource()))
                )
        );
    }

    private interface PatchAction { void apply(WebSession s, String elementId, String arg); }

    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> buildPatchCommand(String name, PatchAction action) {
        return Commands.literal(name)
                .then(Commands.argument("session_id", StringArgumentType.string())
                        .then(Commands.argument("element_id", StringArgumentType.word())
                                .then(Commands.argument("arg", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            WebSession s = WebSessionManager.getOrCreateStandaloneSession(StringArgumentType.getString(ctx, "session_id"), 854, 480);
                                            action.apply(s, StringArgumentType.getString(ctx, "element_id"), StringArgumentType.getString(ctx, "arg"));
                                            return 1;
                                        })
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

        if (html != null) {
            WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, w > 0 ? w : 854, h > 0 ? h : 480);
            session.loadHtml(html);
        }

        CompoundTag payload = new CompoundTag();
        payload.putString("Title", uiId.toString());
        payload.putInt("Width", w);
        payload.putInt("Height", h);
        payload.putString("ResourceLocation", uiId.toString());

        for (ServerPlayer player : targets) {
            if (html != null) WebSessionManager.forceSyncToPlayer(player, sessionId);
            PacketDistributor.sendToPlayer(player, new WebSyncS2CPacket(sessionId, "open_screen", payload));
        }
        source.sendSuccess(() -> Component.literal("Opened Web UI '" + uiId + "' for " + targets.size() + " player(s)."), false);
        return targets.size();
    }

    private static int openInlineUi(CommandSourceStack source, Collection<ServerPlayer> targets, String html) {
        String sessionId = "inline_ui_" + Math.abs(html.hashCode());
        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, 854, 480);
        session.loadHtml(html);

        CompoundTag payload = new CompoundTag();
        payload.putString("Title", "EnderFrame UI");
        payload.putInt("Width", 0);
        payload.putInt("Height", 0);

        for (ServerPlayer player : targets) {
            WebSessionManager.forceSyncToPlayer(player, sessionId);
            PacketDistributor.sendToPlayer(player, new WebSyncS2CPacket(sessionId, "open_screen", payload));
        }
        return targets.size();
    }

    private static int setPlayerHud(CommandSourceStack source, Collection<ServerPlayer> targets, ResourceLocation uiId) {
        String html = resolveServerHtml(uiId);
        String sessionId = "hud_" + uiId.getNamespace() + "_" + uiId.getPath().replace('/', '_');

        if (html != null) {
            WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, 1280, 720);
            session.loadHtml(html);
        }

        CompoundTag payload = new CompoundTag();
        payload.putString("ResourceLocation", uiId.toString());

        for (ServerPlayer player : targets) {
            if (html != null) WebSessionManager.forceSyncToPlayer(player, sessionId);
            PacketDistributor.sendToPlayer(player, new WebSyncS2CPacket(sessionId, "set_hud", payload));
        }
        source.sendSuccess(() -> Component.literal("Set Web HUD '" + uiId + "' for " + targets.size() + " player(s)."), true);
        return targets.size();
    }

    private static int clearPlayerHud(CommandSourceStack source, Collection<ServerPlayer> targets) {
        CompoundTag payload = new CompoundTag();
        for (ServerPlayer player : targets) {
            PacketDistributor.sendToPlayer(player, new WebSyncS2CPacket("", "clear_hud", payload));
        }
        source.sendSuccess(() -> Component.literal("Cleared Web HUD for " + targets.size() + " player(s)."), true);
        return targets.size();
    }

    private static int loadSessionFromDatapack(CommandSourceStack source, String sessionId, ResourceLocation uiId, int w, int h) {
        String html = resolveServerHtml(uiId);
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