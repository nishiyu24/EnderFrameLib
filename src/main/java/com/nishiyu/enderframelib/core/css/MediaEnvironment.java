package com.nishiyu.enderframelib.core.css;

import java.util.function.Function;

/**
 * スタイルシートを読むときの外部環境（{@code @media} の評価と {@code @import} の解決）。
 *
 * @param width              ビューポートの幅（px）
 * @param height             ビューポートの高さ（px）。0 は「分からない」
 * @param prefersDark        {@code prefers-color-scheme: dark} を真にするか
 * @param prefersReducedMotion {@code prefers-reduced-motion: reduce} を真にするか
 * @param importResolver     {@code @import} の URL → CSS テキスト。null なら {@code @import} を無視する
 */
public record MediaEnvironment(int width, int height, boolean prefersDark, boolean prefersReducedMotion,
                               Function<String, String> importResolver) {

    /**
     * 幅と高さだけが分かっている環境。色の好みは light、動きの抑制は無し
     * （どちらも CSS で「好みを示していない」ときの値）。
     */
    public static MediaEnvironment of(int width, int height) {
        return new MediaEnvironment(width, height, false, false, null);
    }

    public MediaEnvironment withImportResolver(Function<String, String> resolver) {
        return new MediaEnvironment(width, height, prefersDark, prefersReducedMotion, resolver);
    }
}
