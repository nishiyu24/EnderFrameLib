package com.nishiyu.enderframelib.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.nishiyu.enderframelib.network.s2c.WebSyncS2CPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * データパック(.mcfunction)やコマンドブロックからWeb UI・HUD・PubSub・DOMパッチを制御するコマンド群
 *
 * 使用例:
 * - /enderframe open @p mypack:quest_menu
 * - /enderframe open_inline @p "<div class='mc-panel'><div class='mc-title'>Hello {{@player_name}}!</div></div>"
 * - /enderframe hud set @a mypack:rpg_overlay
 * - /enderframe hud clear @a
 * - /enderframe session load global_board mypack:leaderboard 320 240
 * - /enderframe patch text global_board title_id "New Title"
 * - /enderframe patch style global_board box_id background-color "#89b4fa"
 * - /enderframe publish server_stats "{online:12, boss_hp:450, status:'Active'}"
 */
public class EnderFrameCommands {

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_DATAPACK_UIS = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(EnderFrameDataPackManager.getAvailableShortIds(), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("enderframe")
                .requires(src -> src.hasPermission(2))

                // 1. /enderframe open <targets> <ui_id> [session_id] [width] [height]
                .then(Commands.literal("open")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                        .suggests(SUGGEST_DATAPACK_UIS)
                                        .executes(ctx -> openDatapackUi(
                                                ctx.getSource(),
                                                EntityArgument.getPlayers(ctx, "targets"),
                                                ResourceLocationArgument.getId(ctx, "ui_id"),
                                                "", 0, 0
                                        ))
                                        .then(Commands.argument("session_id", StringArgumentType.word())
                                                .executes(ctx -> openDatapackUi(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayers(ctx, "targets"),
                                                        ResourceLocationArgument.getId(ctx, "ui_id"),
                                                        StringArgumentType.getString(ctx, "session_id"), 0, 0
                                                ))
                                                .then(Commands.argument("width", IntegerArgumentType.integer(0, 3840))
                                                        .then(Commands.argument("height", IntegerArgumentType.integer(0, 2160))
                                                                .executes(ctx -> openDatapackUi(
                                                                        ctx.getSource(),
                                                                        EntityArgument.getPlayers(ctx, "targets"),
                                                                        ResourceLocationArgument.getId(ctx, "ui_id"),
                                                                        StringArgumentType.getString(ctx, "session_id"),
                                                                        IntegerArgumentType.getInteger(ctx, "width"),
                                                                        IntegerArgumentType.getInteger(ctx, "height")
                                                                ))
                                                        )
                                                )
                                        )
                                )
                        )
                )

                // 2. /enderframe open_inline <targets> <html_string>
                .then(Commands.literal("open_inline")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("html", StringArgumentType.greedyString())
                                        .executes(ctx -> openInlineUi(
                                                ctx.getSource(),
                                                EntityArgument.getPlayers(ctx, "targets"),
                                                StringArgumentType.getString(ctx, "html")
                                        ))
                                )
                        )
                )

                // 3. /enderframe hud (set / clear)
                .then(Commands.literal("hud")
                        .then(Commands.literal("set")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                                .suggests(SUGGEST_DATAPACK_UIS)
                                                .executes(ctx -> setPlayerHud(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayers(ctx, "targets"),
                                                        ResourceLocationArgument.getId(ctx, "ui_id")
                                                ))
                                        )
                                )
                        )
                        .then(Commands.literal("clear")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> clearPlayerHud(
                                                ctx.getSource(),
                                                EntityArgument.getPlayers(ctx, "targets")
                                        ))
                                )
                        )
                )

                // 4. /enderframe session (load / clear)
                .then(Commands.literal("session")
                        .then(Commands.literal("load")
                                .then(Commands.argument("session_id", StringArgumentType.string())
                                        .then(Commands.argument("ui_id", ResourceLocationArgument.id())
                                                .suggests(SUGGEST_DATAPACK_UIS)
                                                .executes(ctx -> loadSessionFromDatapack(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "session_id"),
                                                        ResourceLocationArgument.getId(ctx, "ui_id"),
                                                        854, 480
                                                ))
                                                .then(Commands.argument("width", IntegerArgumentType.integer(32, 3840))
                                                        .then(Commands.argument("height", IntegerArgumentType.integer(32, 2160))
                                                                .executes(ctx -> loadSessionFromDatapack(
                                                                        ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "session_id"),
                                                                        ResourceLocationArgument.getId(ctx, "ui_id"),
                                                                        IntegerArgumentType.getInteger(ctx, "width"),
                                                                        IntegerArgumentType.getInteger(ctx, "height")
                                                                ))
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

                // 5. /enderframe patch (text / style / attr / class / html)
                .then(Commands.literal("patch")
                        .then(Commands.literal("text")
                                .then(Commands.argument("session_id", StringArgumentType.string())
                                        .then(Commands.argument("element_id", StringArgumentType.word())
                                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                                        .executes(ctx -> {
                                                            String sid = StringArgumentType.getString(ctx, "session_id");
                                                            String elId = StringArgumentType.getString(ctx, "element_id");
                                                            String txt = StringArgumentType.getString(ctx, "text");
                                                            WebSession s = WebSessionManager.getOrCreateStandaloneSession(sid, 854, 480);
                                                            s.patchText(elId, txt);
                                                            return 1;
                                                        })
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("style")
                                .then(Commands.argument("session_id", StringArgumentType.string())
                                        .then(Commands.argument("element_id", StringArgumentType.word())
                                                .then(Commands.argument("property", StringArgumentType.word())
                                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                                .executes(ctx -> {
                                                                    String sid = StringArgumentType.getString(ctx, "session_id");
                                                                    String elId = StringArgumentType.getString(ctx, "element_id");
                                                                    String prop = StringArgumentType.getString(ctx, "property");
                                                                    String val = StringArgumentType.getString(ctx, "value");
                                                                    WebSession s = WebSessionManager.getOrCreateStandaloneSession(sid, 854, 480);
                                                                    s.patchStyle(elId, prop, val);
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
                                                                    String sid = StringArgumentType.getString(ctx, "session_id");
                                                                    String elId = StringArgumentType.getString(ctx, "element_id");
                                                                    String attr = StringArgumentType.getString(ctx, "attr_name");
                                                                    String val = StringArgumentType.getString(ctx, "attr_value");
                                                                    WebSession s = WebSessionManager.getOrCreateStandaloneSession(sid, 854, 480);
                                                                    s.patchAttribute(elId, attr, val, false);
                                                                    return 1;
                                                                })
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("html")
                                .then(Commands.argument("session_id", StringArgumentType.string())
                                        .then(Commands.argument("element_id", StringArgumentType.word())
                                                .then(Commands.argument("html_fragment", StringArgumentType.greedyString())
                                                        .executes(ctx -> {
                                                            String sid = StringArgumentType.getString(ctx, "session_id");
                                                            String elId = StringArgumentType.getString(ctx, "element_id");
                                                            String html = StringArgumentType.getString(ctx, "html_fragment");
                                                            WebSession s = WebSessionManager.getOrCreateStandaloneSession(sid, 854, 480);
                                                            s.patchInnerHtml(elId, html);
                                                            return 1;
                                                        })
                                                )
                                        )
                                )
                        )
                )

                // 6. /enderframe publish <channel> <snbt_json>
                .then(Commands.literal("publish")
                        .then(Commands.argument("channel", StringArgumentType.word())
                                .then(Commands.argument("snbt", StringArgumentType.greedyString())
                                        .executes(ctx -> publishChannelData(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "channel"),
                                                StringArgumentType.getString(ctx, "snbt")
                                        ))
                                )
                        )
                )
        );
    }

    private static int openDatapackUi(CommandSourceStack source, Collection<ServerPlayer> targets, ResourceLocation uiId, String customSessionId, int w, int h) {
        String html = EnderFrameDataPackManager.getHtml(uiId);
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
        if (html != null) {
            payload.putString("Html", html);
        } else {
            payload.putString("ResourceLocation", uiId.toString());
        }

        for (ServerPlayer player : targets) {
            if (html != null) {
                WebSessionManager.forceSyncToPlayer(player, sessionId);
            }
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
        payload.putString("Html", html);
        payload.putInt("Width", 0);
        payload.putInt("Height", 0);

        for (ServerPlayer player : targets) {
            WebSessionManager.forceSyncToPlayer(player, sessionId);
            PacketDistributor.sendToPlayer(player, new WebSyncS2CPacket(sessionId, "open_screen", payload));
        }
        return targets.size();
    }

    private static int setPlayerHud(CommandSourceStack source, Collection<ServerPlayer> targets, ResourceLocation uiId) {
        String html = EnderFrameDataPackManager.getHtml(uiId);
        String sessionId = "hud_" + uiId.getNamespace() + "_" + uiId.getPath().replace('/', '_');

        if (html != null) {
            WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, 1280, 720);
            session.loadHtml(html);
        }

        CompoundTag payload = new CompoundTag();
        if (html != null) {
            payload.putString("Html", html);
        } else {
            payload.putString("ResourceLocation", uiId.toString());
        }

        for (ServerPlayer player : targets) {
            if (html != null) {
                WebSessionManager.forceSyncToPlayer(player, sessionId);
            }
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
        String html = EnderFrameDataPackManager.getHtml(uiId);
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
            Map<String, Object> map = new HashMap<>();
            for (String key : parsed.getAllKeys()) {
                Tag t = parsed.get(key);
                if (t instanceof NumericTag num) {
                    if (t.getId() == Tag.TAG_DOUBLE || t.getId() == Tag.TAG_FLOAT) {
                        map.put(key, num.getAsDouble());
                    } else {
                        map.put(key, num.getAsInt());
                    }
                } else if (t != null) {
                    map.put(key, t.getAsString());
                }
            }
            ServerPubSubManager.publish(channel, map);
            source.sendSuccess(() -> Component.literal("Published data to channel '" + channel + "'."), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Invalid SNBT/JSON format: " + e.getMessage()));
            return 0;
        }
    }
}