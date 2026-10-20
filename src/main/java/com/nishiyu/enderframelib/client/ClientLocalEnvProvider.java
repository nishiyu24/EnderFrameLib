package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.dom.Bindings;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
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


    private static final Map<String, Supplier<String>> CUSTOM_PROVIDERS = new ConcurrentHashMap<>();
    private static volatile Map<String, String> CACHED_SNAPSHOT = Collections.emptyMap();
    private static volatile long lastSnapshotTime = 0;

    static {
        Bindings.i18nResolver = ClientLocalEnvProvider::translateKey;
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
     * クライアント固有のカスタム環境変数サプライヤを登録する。
     * <p>
     * 登録された変数は、HTML/CSS テンプレート中のプレースホルダ（{@code {{@key}}}）や
     * スタイルシートのローカル変数として参照できる。キーの先頭に {@code @} が付いていても内部で正規化される。
     *
     * @param key 変数名（例: {@code "player_mana"} または {@code "@player_mana"}）
     * @param supplier 変数の現在値を返すサプライヤ。null の場合は登録を無視する
     */
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
        return Bindings.applyI18nAttributes(vNode);
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
            Bindings.collectLocalVariableKeys(templateRoot, keys);
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
        if (Bindings.applyLocalVariables(templateRoot, virtualRoot, currentEnv)) {
            session.requestRender();
        }
    }
}
