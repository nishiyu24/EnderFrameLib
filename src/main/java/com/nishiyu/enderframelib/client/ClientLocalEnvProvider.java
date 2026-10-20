package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.dom.TemplateEngine;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientLocalEnvProvider {

    private static final Pattern LOCAL_PLACEHOLDER = Pattern.compile("\\{\\{@([a-zA-Z0-9_.:-]+)}}");
    private static final Map<String, Supplier<String>> CUSTOM_PROVIDERS = new ConcurrentHashMap<>();
    private static volatile Map<String, String> CACHED_SNAPSHOT = Collections.emptyMap();
    private static volatile long lastSnapshotTime = 0;

    static {
        TemplateEngine.i18nResolver = ClientLocalEnvProvider::translateKey;
    }

    public static String translateKey(String key) {
        if (key == null || key.isEmpty()) return "";
        try {
            return I18n.get(key);
        } catch (Throwable t) {
            return key;
        }
    }

    public static void registerCustomVariable(String key, Supplier<String> supplier) {
        if (key == null || supplier == null) return;
        String cleanKey = key.startsWith("@") ? key.substring(1) : key;
        CUSTOM_PROVIDERS.put(cleanKey, supplier);
    }

    public static Map<String, String> captureCurrentEnv() {
        long now = System.currentTimeMillis();
        Map<String, String> cached = CACHED_SNAPSHOT;
        if (now - lastSnapshotTime < 100 && !cached.isEmpty()) {
            return cached;
        }

        Minecraft mc = null;
        try {
            mc = Minecraft.getInstance();
        } catch (Throwable ignored) {
        }

        Map<String, String> env = new HashMap<>();
        if (mc == null) {
            env.put("@lang", "en_us");
            env.put("@fps", "60");
            CACHED_SNAPSHOT = env;
            lastSnapshotTime = now;
            return env;
        }

        String lang = (mc.getLanguageManager() != null && mc.getLanguageManager().getSelected() != null)
                ? mc.getLanguageManager().getSelected() : "en_us";
        env.put("@lang", lang);
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

        Map<String, String> unmodifiable = Collections.unmodifiableMap(env);
        CACHED_SNAPSHOT = unmodifiable;
        lastSnapshotTime = now;
        return unmodifiable;
    }

    public static boolean applyI18nAttributes(HtmlNode vNode) {
        if (vNode == null) return false;
        boolean changed = false;

        if (vNode.attrs.containsKey("data-i18n")) {
            String key = vNode.attrs.get("data-i18n").trim();
            if (!key.isEmpty()) {
                String tr = translateKey(key);
                if (!Objects.equals(vNode.getTextContent(), tr)) {
                    vNode.setTextContent(tr);
                    changed = true;
                }
            }
        }
        if (vNode.attrs.containsKey("data-i18n-placeholder")) {
            String tr = translateKey(vNode.attrs.get("data-i18n-placeholder").trim());
            if (!Objects.equals(vNode.attrs.get("placeholder"), tr)) {
                vNode.attrs.put("placeholder", tr);
                changed = true;
            }
        }
        if (vNode.attrs.containsKey("data-i18n-title")) {
            String tr = translateKey(vNode.attrs.get("data-i18n-title").trim());
            if (!Objects.equals(vNode.attrs.get("title"), tr)) {
                vNode.attrs.put("title", tr);
                changed = true;
            }
        }
        return changed;
    }

    public static void tickSessionLocalVariables(String sessionId, HtmlNode templateRoot, HtmlNode virtualRoot) {
        if (templateRoot == null || virtualRoot == null) return;
        SessionContext session = ClientWebManager.getOrCreateSession(sessionId);
        long now = System.currentTimeMillis();
        if (now - session.envLastUpdate() < 100) return;
        session.setEnvLastUpdate(now);

        Map<String, String> currentEnv = captureCurrentEnv();
        Set<String> usedKeys = session.envUsedKeys();
        if (usedKeys == null) {
            Set<String> keys = new java.util.HashSet<>();
            collectUsedKeys(templateRoot, keys);
            usedKeys = Collections.unmodifiableSet(keys);
            session.setEnvUsedKeys(usedKeys);
        }

        Map<String, String> lastApplied = session.envApplied();
        if (lastApplied != null) {
            boolean anyChanged = false;
            for (String key : usedKeys) {
                if (!Objects.equals(lastApplied.get(key), currentEnv.get(key))) {
                    anyChanged = true;
                    break;
                }
            }
            if (!anyChanged) {
                return;
            }
        }

        session.setEnvApplied(currentEnv);
        if (applyLocalVarsRecursive(templateRoot, virtualRoot, currentEnv)) {
            ClientWebManager.requestRender(sessionId);
        }
    }

    private static void collectUsedKeys(HtmlNode node, Set<String> keys) {
        if (node == null) return;
        if (node.text != null && node.text.contains("{{@")) {
            Matcher m = LOCAL_PLACEHOLDER.matcher(node.text);
            while (m.find()) {
                String rawKey = m.group(1);
                if (rawKey.startsWith("i18n:")) keys.add("@lang");
                else keys.add("@" + rawKey);
            }
        }
        if (node.attrs.containsKey("data-i18n") || node.attrs.containsKey("data-i18n-placeholder") || node.attrs.containsKey("data-i18n-title")) {
            keys.add("@lang");
        }
        for (String val : node.attrs.values()) {
            if (val != null && val.contains("{{@")) {
                Matcher m = LOCAL_PLACEHOLDER.matcher(val);
                while (m.find()) {
                    String rawKey = m.group(1);
                    if (rawKey.startsWith("i18n:")) keys.add("@lang");
                    else keys.add("@" + rawKey);
                }
            }
        }
        for (HtmlNode child : node.children) {
            collectUsedKeys(child, keys);
        }
    }

    private static boolean applyLocalVarsRecursive(HtmlNode origNode, HtmlNode vNode, Map<String, String> env) {
        if (origNode == null || vNode == null) return false;
        boolean changed = applyI18nAttributes(vNode);

        if (origNode.text != null && (origNode.text.contains("{{@") || origNode.text.contains("{{i18n:"))) {
            String resolved = TemplateEngine.resolve(origNode.text, null, env);
            if (!resolved.equals(vNode.text)) {
                vNode.text = resolved;
                changed = true;
            }
        }

        for (String key : new ArrayList<>(origNode.attrs.keySet())) {
            String origVal = origNode.attrs.get(key);
            if (origVal != null && (origVal.contains("{{@") || origVal.contains("{{i18n:"))) {
                String resolved = TemplateEngine.resolve(origVal, null, env);
                if (!resolved.equals(vNode.attrs.get(key))) {
                    vNode.attrs.put(key, resolved);
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
}
