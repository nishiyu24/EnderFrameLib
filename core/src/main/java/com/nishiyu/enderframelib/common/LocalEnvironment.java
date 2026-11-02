package com.nishiyu.enderframelib.common;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * HTML/CSS のローカル変数（{@code @lang}・{@code @fps}・自前の {@code @key}）の提供元。
 * 値の集め方（Minecraft のプレイヤー情報など）は {@link #setBaseSource} で差し込む。
 * 何も差し込まなければ、言語 {@code en_us} とフレームレート 60 だけを返す（窓で単体動作するときの既定）。
 * 結果は 100ms だけキャッシュする。
 */
public final class LocalEnvironment {

    private static final long CACHE_MS = 100;
    private static final Map<String, Supplier<String>> CUSTOM_PROVIDERS = new ConcurrentHashMap<>();
    private static volatile Supplier<Map<String, String>> baseSource;
    private static volatile Map<String, String> cachedSnapshot = Collections.emptyMap();
    private static volatile long lastSnapshotTime = 0;

    private LocalEnvironment() {}

    /**
     * 基本の変数（キーは {@code @} 付き）を返す供給元。{@code null} か、供給元が null を返したときは既定値を使う。
     */
    public static void setBaseSource(Supplier<Map<String, String>> source) {
        baseSource = source;
        invalidate();
    }

    /**
     * 自前の変数を登録する。キーの先頭の {@code @} は有っても無くてもよい。{@code supplier} が null なら無視する。
     */
    public static void registerCustomVariable(String key, Supplier<String> supplier) {
        if (key == null || supplier == null) return;
        String cleanKey = key.startsWith("@") ? key.substring(1) : key;
        CUSTOM_PROVIDERS.put(cleanKey, supplier);
        invalidate();
    }

    /** 登録した自前の変数をすべて外す（テスト用）。 */
    public static void clearCustomVariables() {
        CUSTOM_PROVIDERS.clear();
        invalidate();
    }

    /** キャッシュを捨てる。 */
    public static void invalidate() {
        lastSnapshotTime = 0;
        cachedSnapshot = Collections.emptyMap();
    }

    /** 現在の変数の読み取り専用の写し（キーは {@code @} 付き）。 */
    public static Map<String, String> capture() {
        long now = System.currentTimeMillis();
        Map<String, String> cached = cachedSnapshot;
        if (now - lastSnapshotTime < CACHE_MS && !cached.isEmpty()) {
            return cached;
        }
        Map<String, String> env = new HashMap<>();
        Map<String, String> base = null;
        Supplier<Map<String, String>> source = baseSource;
        if (source != null) {
            try {
                base = source.get();
            } catch (Throwable ignored) {
            }
        }
        if (base == null) {
            env.put("@lang", "en_us");
            env.put("@fps", "60");
        } else {
            env.putAll(base);
        }
        for (Map.Entry<String, Supplier<String>> entry : CUSTOM_PROVIDERS.entrySet()) {
            try {
                String val = entry.getValue().get();
                env.put("@" + entry.getKey(), val != null ? val : "");
            } catch (Exception ignored) {
            }
        }
        Map<String, String> unmodifiable = Collections.unmodifiableMap(env);
        cachedSnapshot = unmodifiable;
        lastSnapshotTime = now;
        return unmodifiable;
    }
}
