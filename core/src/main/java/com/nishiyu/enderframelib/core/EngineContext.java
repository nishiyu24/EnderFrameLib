package com.nishiyu.enderframelib.core;

import com.nishiyu.enderframelib.core.dom.BindingHooks;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * エンジンがホストから受け取る差し込み（翻訳・JSON リスト解析・画像の固有サイズ解決・箱の描画関数・リソースの代替取得）をまとめた文脈。
 * 以前は {@code Bindings} / {@code RenderPipeline} / {@code UiDocumentCompiler} のプロセス全体の static
 * フィールドだったもの。
 *
 * <p>{@link #global()} がホスト（Minecraft など）の既定の文脈。複数のホストやテストを同じ JVM で独立に動かすときは
 * {@link #callWith} / {@link #runWith} で、そのスレッドの間だけ別の文脈に切り替える（他スレッドと global は変わらない）。
 * 文脈が持つのは不変の 2 つの値（{@link HostServices} と {@link ContextFlags}）だけで、差し替えは {@link #updateHost} / {@link #updateFlags}、
 * 読み取りは {@link #current()} 経由（S1 ②。以前は public volatile の欄 14 個で、差し込み口と近道の切り替えが同じ所に並んでいた）。
 */
public final class EngineContext implements BindingHooks {
    private static final EngineContext GLOBAL = new EngineContext();
    private static final ThreadLocal<EngineContext> SCOPED = new ThreadLocal<>();

    static {
        // L3（DOM）の Bindings が上位層を参照せずに、このスレッドの文脈の翻訳・JSON 解析を引けるようにする（層の逆転）。
        BindingHooks.install(EngineContext::current);
        // L5 の PaintServices が、このスレッドの文脈（HostServices）の SVG・書体・固有サイズを引けるようにする。
        com.nishiyu.enderframelib.core.paint.PaintServices.bindSource(() -> current().host);
        com.nishiyu.enderframelib.core.raster.svg.SvgSources.bindSource(() -> current().host);
        com.nishiyu.enderframelib.core.text.font.FontFaceRules.bindSource(() -> current().host);
        com.nishiyu.enderframelib.core.paint.CssAnimator.bindSource(() -> current().host);
        com.nishiyu.enderframelib.core.dom.parser.HtmlParser.bindSource(() -> current().host);
        com.nishiyu.enderframelib.core.css.GlyphUnits.bindSource(() -> current().host);
        com.nishiyu.enderframelib.core.replaced.ReplacedElements.bindSource(() -> current().host);
        com.nishiyu.enderframelib.core.dom.node.HostSyntax.bindSource(() -> current().host);
        com.nishiyu.enderframelib.core.raster.ImageDecoder.bindSource(() -> current().host);
        // 並列のワーカーが、呼び出したスレッドの面の文脈の中で仕事をするようにする（global の呼び出しは包まない）
        com.nishiyu.enderframelib.core.layout.LayoutTaskPool.bindScopeCapture(() -> {
            EngineContext active = current();
            if (active == GLOBAL) return null;
            return r -> {
                if (current() == active) r.run();
                else runWith(active, r);
            };
        });
        com.nishiyu.enderframelib.core.paint.DisplayListBuilder.bindParentRelativeSource(() -> current().flags.parentRelativeDisplayItems());
    }

    /** ホストの差し込み口（不変。{@link #updateHost} で丸ごと差し替える）。 */
    private volatile HostServices host = HostServices.DEFAULTS;
    /** 文脈ごとの切り替え（不変。{@link #updateFlags} で丸ごと差し替える）。 */
    private volatile ContextFlags flags = ContextFlags.DEFAULTS;

    /** ホストの差し込み口。 */
    public HostServices host() { return host; }

    /** 文脈ごとの切り替え。 */
    public ContextFlags flags() { return flags; }

    /** 差し込み口を {@code change} の結果へ差し替える（読み替えの間に他のスレッドの差し替えを失わない）。 */
    public synchronized void updateHost(UnaryOperator<HostServices> change) { host = java.util.Objects.requireNonNull(change.apply(host)); }

    /** 切り替えを {@code change} の結果へ差し替える。 */
    public synchronized void updateFlags(UnaryOperator<ContextFlags> change) { flags = java.util.Objects.requireNonNull(change.apply(flags)); }

    @Override public Function<String, String> i18nResolver() { return host.i18nResolver(); }

    @Override public BiFunction<String, Object[], String> i18nFormatter() { return host.i18nFormatter(); }

    @Override public Function<String, List<Map<String, String>>> jsonListParser() { return host.jsonListParser(); }

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
