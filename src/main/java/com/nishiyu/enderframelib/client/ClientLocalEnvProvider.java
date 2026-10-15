package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.dom.HtmlNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * サーバー通信を介さず、クライアント自身のローカル状態（プレイヤー情報、FPS、座標、時刻など）および
 * 多言語翻訳（{{@i18n:key}} / {{i18n:key}} / data-i18n="key"）を仮想DOMに自動バインドするプロバイダ
 */
public class ClientLocalEnvProvider {

    private static final Pattern LOCAL_PLACEHOLDER = Pattern.compile("\\{\\{@([a-zA-Z0-9_.:-]+)}}");
    private static final Pattern I18N_PLACEHOLDER = Pattern.compile("\\{\\{@?i18n:([a-zA-Z0-9_.:-]+)}}");
    private static final Map<String, Supplier<String>> CUSTOM_PROVIDERS = new ConcurrentHashMap<>();
    private static final Map<String, String> CACHED_SNAPSHOT = new ConcurrentHashMap<>();
    private static final Map<String, Long> SESSION_LAST_UPDATE = new ConcurrentHashMap<>();
    private static long lastSnapshotTime = 0;

    static {
        // DomUtils 側の {{i18n:key}} 解決からもクライアントの I18n を安全に呼べるよう登録
        com.nishiyu.enderframelib.core.dom.DomUtils.i18nResolver = ClientLocalEnvProvider::translateKey;
    }

    public static String translateKey(String key) {
        if (key == null || key.isEmpty()) return "";
        try {
            return I18n.get(key);
        } catch (Throwable t) {
            return key;
        }
    }

    /**
     * 外部MODから独自のローカル変数（例: "mana" -> {{@mana}}）を登録する
     */
    public static void registerCustomVariable(String key, Supplier<String> supplier) {
        String cleanKey = key.startsWith("@") ? key.substring(1) : key;
        CUSTOM_PROVIDERS.put(cleanKey, supplier);
    }

    public static Map<String, String> captureCurrentEnv() {
        long now = System.currentTimeMillis();
        if (now - lastSnapshotTime < 100 && !CACHED_SNAPSHOT.isEmpty()) {
            return CACHED_SNAPSHOT;
        }

        Minecraft mc = Minecraft.getInstance();
        Map<String, String> env = new HashMap<>();
        env.put("@lang", mc.getLanguageManager().getSelected());
        env.put("@fps", String.valueOf(mc.getFps()));

        if (mc.player != null && mc.level != null) {
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
        }

        for (Map.Entry<String, Supplier<String>> entry : CUSTOM_PROVIDERS.entrySet()) {
            try {
                String val = entry.getValue().get();
                env.put("@" + entry.getKey(), val != null ? val : "");
            } catch (Exception ignored) {}
        }

        CACHED_SNAPSHOT.clear();
        CACHED_SNAPSHOT.putAll(env);
        lastSnapshotTime = now;
        return CACHED_SNAPSHOT;
    }

    /**
     * テンプレートツリー（初期状態）に {{@...}} や {{i18n:...}}、data-i18n が含まれている場合、仮想DOMを更新する
     */
    public static void tickSessionLocalVariables(String sessionId, HtmlNode templateRoot, HtmlNode virtualRoot) {
        if (templateRoot == null || virtualRoot == null) return;
        long now = System.currentTimeMillis();
        long lastSessionTick = SESSION_LAST_UPDATE.getOrDefault(sessionId, 0L);
        if (now - lastSessionTick < 100) return;
        SESSION_LAST_UPDATE.put(sessionId, now);

        Map<String, String> currentEnv = captureCurrentEnv();
        if (applyLocalVarsRecursive(templateRoot, virtualRoot, currentEnv)) {
            ClientWebManager.requestRender(sessionId);
        }
    }

    private static boolean applyLocalVarsRecursive(HtmlNode origNode, HtmlNode vNode, Map<String, String> env) {
        if (origNode == null || vNode == null) return false;
        boolean changed = false;

        // 1. data-i18n / data-i18n-placeholder / data-i18n-title 属性による多言語解決
        if (vNode.attrs.containsKey("data-i18n")) {
            String tr = translateKey(vNode.attrs.get("data-i18n").trim());
            if (!Objects.equals(vNode.getTextContent(), tr)) {
                vNode.setTextContent(tr);
                changed = true;
            }
        }
        if (vNode.attrs.containsKey("data-i18n-placeholder")) {
            String tr = translateKey(vNode.attrs.get("data-i18n-placeholder").trim());
            if (!Objects.equals(vNode.attrs.get("placeholder"), tr)) {
                vNode.attrs.put("placeholder", tr);
                vNode.markDirty();
                changed = true;
            }
        }
        if (vNode.attrs.containsKey("data-i18n-title")) {
            String tr = translateKey(vNode.attrs.get("data-i18n-title").trim());
            if (!Objects.equals(vNode.attrs.get("title"), tr)) {
                vNode.attrs.put("title", tr);
                vNode.markDirty();
                changed = true;
            }
        }

        // 2. テキストの {{@...}} および {{i18n:...}} 解決
        if (origNode.text != null && (origNode.text.contains("{{@") || origNode.text.contains("{{i18n:"))) {
            String resolved = replaceLocalPlaceholders(origNode.text, env);
            if (!resolved.equals(vNode.text)) {
                vNode.text = resolved;
                vNode.markDirty();
                changed = true;
            }
        }

        // 3. 属性の {{@...}} および {{i18n:...}} 解決
        for (String key : new ArrayList<>(origNode.attrs.keySet())) {
            String origVal = origNode.attrs.get(key);
            if (origVal != null && (origVal.contains("{{@") || origVal.contains("{{i18n:"))) {
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
        if (text == null) return "";
        String result = text;

        if (result.contains("i18n:")) {
            Matcher im = I18N_PLACEHOLDER.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (im.find()) {
                String tr = translateKey(im.group(1));
                im.appendReplacement(sb, Matcher.quoteReplacement(tr));
            }
            im.appendTail(sb);
            result = sb.toString();
        }

        if (result.contains("{{@") && env != null && !env.isEmpty()) {
            Matcher m = LOCAL_PLACEHOLDER.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String rawKey = m.group(1);
                if (rawKey.startsWith("i18n:")) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(translateKey(rawKey.substring(5))));
                    continue;
                }
                String key = "@" + rawKey;
                String replacement = env.getOrDefault(key, m.group(0));
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            result = sb.toString();
        }

        return result;
    }
}