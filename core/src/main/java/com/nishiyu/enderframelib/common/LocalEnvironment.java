package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.kernel.Clock;
import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.HostServices;
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
    /** 写しの覚え。どの文脈（{@link HostServices}）の写しかを持ち、別の面の写しを返さない。 */
    private record Snapshot(HostServices host, long time, Map<String, String> env) {}

    private static volatile Snapshot cache = new Snapshot(null, 0, Collections.emptyMap());

    private LocalEnvironment() {}

    /**
     * 基本の変数（キーは {@code @} 付き）を返す供給元を、このスレッドの文脈へ差し込む。{@code null} か、供給元が null を返したときは既定値を使う。
     */
    public static void useBaseSource(Supplier<Map<String, String>> source) {
        EngineContext.current().updateHost(h -> h.withLocalEnvBase(source));
        invalidate();
    }

    /**
     * 自前の変数をこのスレッドの文脈へ登録する。キーの先頭の {@code @} は有っても無くてもよい。{@code supplier} が null なら無視する。
     */
    public static void registerCustomVariable(String key, Supplier<String> supplier) {
        if (key == null || supplier == null) return;
        EngineContext.current().updateHost(h -> h.withLocalEnvVariable(key, supplier));
        invalidate();
    }

    /** このスレッドの文脈に登録した自前の変数をすべて外す（テスト用）。 */
    public static void clearCustomVariables() {
        EngineContext.current().updateHost(h -> h.withLocalEnvVariables(null));
        invalidate();
    }

    /** キャッシュを捨てる。 */
    public static void invalidate() {
        cache = new Snapshot(null, 0, Collections.emptyMap());
    }

    /** 現在の変数の読み取り専用の写し（キーは {@code @} 付き）。 */
    public static Map<String, String> capture() {
        long now = Clock.SYSTEM.millis();
        HostServices host = EngineContext.current().host();
        Snapshot cached = cache;
        if (cached.host() == host && now - cached.time() < CACHE_MS && !cached.env().isEmpty()) {
            return cached.env();
        }
        Map<String, String> env = new HashMap<>();
        Map<String, String> base = null;
        Supplier<Map<String, String>> source = host.localEnvBase();
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
        Map<String, Supplier<String>> custom = host.localEnvVariables() == null ? Map.of() : host.localEnvVariables();
        for (Map.Entry<String, Supplier<String>> entry : custom.entrySet()) {
            try {
                String val = entry.getValue().get();
                env.put("@" + entry.getKey(), val != null ? val : "");
            } catch (Exception ignored) {
            }
        }
        Map<String, String> unmodifiable = Collections.unmodifiableMap(env);
        cache = new Snapshot(host, now, unmodifiable);
        return unmodifiable;
    }
}
