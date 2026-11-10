package com.nishiyu.enderframelib.core.dom;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link Bindings} がホストから受け取る差し込み（翻訳・JSON リスト解析）の窓口。L3（DOM）が定義する SPI で、
 * 実装は上位層の {@code core.EngineContext}（L6）が担う。DOM 層から上位層の型を参照しないための逆転。
 *
 * <p>{@link #current()} は上位層が {@link #install} した供給元を引く。未設置（エンジン文脈を一度も触っていない）なら
 * 何もしない既定（キーをそのまま返す・JSON は解析しない）。
 */
public interface BindingHooks {
    /** {@code {{i18n:key}}} などの翻訳。 */
    Function<String, String> i18nResolver();

    /** 引数つきの翻訳（{@code {{i18n:key, arg…}}}）。 */
    BiFunction<String, Object[], String> i18nFormatter();

    /** {@code data-for} の JSON 配列の解析。null なら解析しない。 */
    Function<String, List<Map<String, String>>> jsonListParser();

    /** 何も差し込まれていないときの既定。 */
    BindingHooks IDENTITY = new BindingHooks() {
        @Override public Function<String, String> i18nResolver() { return key -> key; }
        @Override public BiFunction<String, Object[], String> i18nFormatter() { return (key, args) -> key; }
        @Override public Function<String, List<Map<String, String>>> jsonListParser() { return null; }
    };

    /** このスレッドで有効な差し込み。 */
    static BindingHooks current() {
        return Holder.source.get();
    }

    /** 供給元を設置する（上位層が 1 回だけ呼ぶ）。null で既定へ戻す。 */
    static void install(Supplier<BindingHooks> source) {
        Holder.source = source != null ? source : () -> IDENTITY;
    }

    /** 設置済みの供給元を持つだけの入れ物（インターフェースの定数は final のため）。 */
    final class Holder {
        static volatile Supplier<BindingHooks> source = () -> IDENTITY;

        private Holder() {}
    }
}
