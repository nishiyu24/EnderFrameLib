package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.dom.HtmlNode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * サーバー通信を介さず、クライアント自身のローカル状態（プレイヤー情報、FPS、座標、時刻など）を
 * HTMLの {{@key}} プレースホルダーや CSS環境変数に自動バインドするプロバイダ
 */
public class ClientLocalEnvProvider {

    private static final Pattern LOCAL_PLACEHOLDER = Pattern.compile("\\{\\{@([a-zA-Z0-9_.-]+)\\}\\}");
    private static final Map<String, Supplier<String>> CUSTOM_PROVIDERS = new ConcurrentHashMap<>();
    private static final Map<String, String> LAST_SNAPSHOT = new ConcurrentHashMap<>();
    private static long lastUpdateTick = 0;

    /**
     * 外部MODから独自のローカル変数（例: "mana" -> {{@mana}}）を登録する
     */
    public static void registerCustomVariable(String key, Supplier<String> supplier) {
        String cleanKey = key.startsWith("@") ? key.substring(1) : key;
        CUSTOM_PROVIDERS.put(cleanKey, supplier);
    }

    public static Map<String, String> captureCurrentEnv() {
        Minecraft mc = Minecraft.getInstance();
        Map<String, String> env = new HashMap<>();
        if (mc.player == null || mc.level == null) return env;

        Player p = mc.player;
        Level level = mc.level;

        env.put("@player_name", p.getName().getString());
        env.put("@player_uuid", p.getStringUUID());
        env.put("@hp", String.valueOf((int) Math.ceil(p.getHealth())));
        env.put("@max_hp", String.valueOf((int) Math.ceil(p.getMaxHealth())));
        env.put("@hp_percent", String.valueOf(Math.round((p.getHealth() / Math.max(1f, p.getMaxHealth())) * 100)));
        env.put("@food", String.valueOf(p.getFoodData().getFoodLevel()));
        env.put("@armor", String.valueOf(p.getArmorValue()));
        env.put("@xp_level", String.valueOf(p.experienceLevel));
        env.put("@air", String.valueOf(p.getAirSupply()));

        BlockPos pos = p.blockPosition();
        env.put("@x", String.valueOf(pos.getX()));
        env.put("@y", String.valueOf(pos.getY()));
        env.put("@z", String.valueOf(pos.getZ()));
        env.put("@dimension", level.dimension().location().toString());

        Holder<Biome> biomeHolder = level.getBiome(pos);
        String biomeName = biomeHolder.unwrapKey().map(k -> k.location().getPath()).orElse("unknown");
        env.put("@biome", biomeName);

        long dayTime = (level.getDayTime() + 6000) % 24000;
        int hours = (int) (dayTime / 1000);
        int minutes = (int) ((dayTime % 1000) * 60 / 1000);
        env.put("@time", String.format("%02d:%02d", hours, minutes));
        env.put("@fps", String.valueOf(mc.getFps()));

        ItemStack mainHand = p.getMainHandItem();
        if (!mainHand.isEmpty()) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(mainHand.getItem());
            env.put("@held_item", itemId.toString());
            env.put("@held_item_name", mainHand.getHoverName().getString());
            env.put("@held_item_count", String.valueOf(mainHand.getCount()));
        } else {
            env.put("@held_item", "minecraft:air");
            env.put("@held_item_name", "");
            env.put("@held_item_count", "0");
        }

        for (Map.Entry<String, Supplier<String>> entry : CUSTOM_PROVIDERS.entrySet()) {
            try {
                String val = entry.getValue().get();
                env.put("@" + entry.getKey(), val != null ? val : "");
            } catch (Exception ignored) {}
        }

        return env;
    }

    /**
     * テンプレートツリー（初期状態）に {{@...}} が含まれている場合、現在のローカル変数で仮想DOMを更新する
     */
    public static void tickSessionLocalVariables(String sessionId, HtmlNode templateRoot, HtmlNode virtualRoot) {
        if (templateRoot == null || virtualRoot == null) return;
        long now = System.currentTimeMillis();
        if (now - lastUpdateTick < 100) return; // 秒間最大10回に制限

        Map<String, String> currentEnv = captureCurrentEnv();
        if (currentEnv.isEmpty()) return;

        boolean envChanged = false;
        for (Map.Entry<String, String> entry : currentEnv.entrySet()) {
            if (!entry.getValue().equals(LAST_SNAPSHOT.get(entry.getKey()))) {
                envChanged = true;
                break;
            }
        }
        if (envChanged) {
            LAST_SNAPSHOT.clear();
            LAST_SNAPSHOT.putAll(currentEnv);
            lastUpdateTick = now;
        }

        if (applyLocalVarsRecursive(templateRoot, virtualRoot, currentEnv)) {
            ClientWebManager.requestRender(sessionId);
        }
    }

    private static boolean applyLocalVarsRecursive(HtmlNode origNode, HtmlNode vNode, Map<String, String> env) {
        if (origNode == null || vNode == null) return false;
        boolean changed = false;

        if (origNode.text != null && origNode.text.contains("{{@")) {
            String resolved = replaceLocalPlaceholders(origNode.text, env);
            if (!resolved.equals(vNode.text)) {
                vNode.text = resolved;
                vNode.markDirty();
                changed = true;
            }
        }

        for (String key : new ArrayList<>(origNode.attrs.keySet())) {
            String origVal = origNode.attrs.get(key);
            if (origVal != null && origVal.contains("{{@")) {
                String resolved = replaceLocalPlaceholders(origVal, env);
                if (!resolved.equals(vNode.attrs.get(key))) {
                    vNode.attrs.put(key, resolved);
                    vNode.markDirty();
                    changed = true;
                }
            }
        }

        int min = Math.min(origNode.children.size(), vNode.children.size());
        for (int i = 0; i < min; i++) {
            if (applyLocalVarsRecursive(origNode.children.get(i), vNode.children.get(i), env)) {
                changed = true;
            }
        }
        return changed;
    }

    public static String replaceLocalPlaceholders(String text, Map<String, String> env) {
        Matcher m = LOCAL_PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = "@" + m.group(1);
            String replacement = env.getOrDefault(key, m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}