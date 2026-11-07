package com.nishiyu.enderframelib.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * ゲーム内のフレーム時間を {@link StageProfiler} で取るための入口（クライアントコマンド {@code /enderframe_profile}）。
 *
 * <ul>
 *   <li>{@code start [alloc]}: 集計を消して計測を始める。{@code alloc} を付けると段ごとの割り当て量も取る。</li>
 *   <li>{@code report}: 現在の表をチャットとログに出す。</li>
 *   <li>{@code stop}: 計測を止めて表を出す。</li>
 *   <li>{@code reset}: 集計を消す。</li>
 * </ul>
 * 計測中は約 30 秒ごとに表をログへ出す。{@code -Denderframe.profile=true} で起動時から有効にもできる。
 * 無効の間のコストは各段 volatile の読み 1 回だけ。段の意味は {@code core/MEASUREMENT.md}。
 */
public final class InGameProfiler {

    private static final int LOG_INTERVAL_TICKS = 20 * 30;
    private static int ticks;

    private InGameProfiler() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(InGameProfiler::registerCommands);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            if (StageProfiler.enabled() && ++ticks >= LOG_INTERVAL_TICKS) {
                ticks = 0;
                EnderFrameLib.LOGGER.info("[EnderFrameLib] in-game profile\n{}", StageProfiler.report());
            }
        });
    }

    private static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("enderframe_profile")
                .then(Commands.literal("start")
                        .executes(c -> start(c.getSource(), false))
                        .then(Commands.argument("alloc", BoolArgumentType.bool())
                                .executes(c -> start(c.getSource(), BoolArgumentType.getBool(c, "alloc")))))
                .then(Commands.literal("report").executes(c -> report(c.getSource(), false)))
                .then(Commands.literal("stop").executes(c -> report(c.getSource(), true)))
                .then(Commands.literal("reset").executes(c -> {
                    StageProfiler.reset();
                    c.getSource().sendSuccess(() -> Component.literal("EnderFrame profile: reset"), false);
                    return 1;
                })));
    }

    private static int start(net.minecraft.commands.CommandSourceStack src, boolean alloc) {
        StageProfiler.reset();
        StageProfiler.trackAllocations(alloc);
        StageProfiler.enable(true);
        ticks = 0;
        src.sendSuccess(() -> Component.literal("EnderFrame profile: started" + (alloc ? " (with allocations)" : "")), false);
        return 1;
    }

    private static int report(net.minecraft.commands.CommandSourceStack src, boolean stop) {
        if (stop) StageProfiler.enable(false);
        String table = StageProfiler.report();
        EnderFrameLib.LOGGER.info("[EnderFrameLib] in-game profile\n{}", table);
        for (String line : table.split("\\R")) {
            if (!line.isBlank()) src.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
