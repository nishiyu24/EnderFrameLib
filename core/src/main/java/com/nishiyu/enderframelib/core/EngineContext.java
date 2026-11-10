package com.nishiyu.enderframelib.core;

import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * エンジンがホストから受け取る差し込み（翻訳・JSON リスト解析・画像の固有サイズ解決）をまとめた文脈。
 * 以前は {@code Bindings} / {@code RenderPipeline} / {@code UiDocumentCompiler} のプロセス全体の static
 * フィールドだったもの。
 *
 * <p>{@link #global()} がホスト（Minecraft など）の既定の文脈。複数のホストやテストを同じ JVM で独立に動かすときは
 * {@link #callWith} / {@link #runWith} で、そのスレッドの間だけ別の文脈に切り替える（他スレッドと global は変わらない）。
 * 各フィールドは差し込み時に代入する単純な値で、読み取りは {@link #current()} 経由。
 */
public final class EngineContext {
    private static final EngineContext GLOBAL = new EngineContext();
    private static final ThreadLocal<EngineContext> SCOPED = new ThreadLocal<>();

    /** {@code {{i18n:key}}} などの翻訳。既定はキーをそのまま返す。 */
    public volatile Function<String, String> i18nResolver = key -> key;
    /** 引数つきの翻訳（{@code {{i18n:key, arg…}}}）。 */
    public volatile BiFunction<String, Object[], String> i18nFormatter = (key, args) -> key;
    /** {@code data-for} の JSON 配列の解析。null なら解析しない。 */
    public volatile Function<String, List<Map<String, String>>> jsonListParser = null;
    /** 画像などの固有サイズの解決。null なら解決しない。 */
    public volatile IntrinsicSizeResolver intrinsicSizeResolver = null;

    /** 既定値で始まる新しい文脈（global とは独立）。 */
    public EngineContext() {}

    /** プロセス既定の文脈。 */
    public static EngineContext global() {
        return GLOBAL;
    }

    /** このスレッドで有効な文脈（{@link #callWith} の中ならその文脈、外なら global）。 */
    public static EngineContext current() {
        EngineContext c = SCOPED.get();
        return c != null ? c : GLOBAL;
    }

    /** {@code ctx} をこのスレッドの文脈にして実行し、終わったら元へ戻す。 */
    public static <T> T callWith(EngineContext ctx, Callable<T> body) throws Exception {
        EngineContext prev = SCOPED.get();
        SCOPED.set(ctx);
        try {
            return body.call();
        } finally {
            if (prev == null) SCOPED.remove();
            else SCOPED.set(prev);
        }
    }

    public static void runWith(EngineContext ctx, Runnable body) {
        EngineContext prev = SCOPED.get();
        SCOPED.set(ctx);
        try {
            body.run();
        } finally {
            if (prev == null) SCOPED.remove();
            else SCOPED.set(prev);
        }
    }
}
