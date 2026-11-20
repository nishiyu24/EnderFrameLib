package com.nishiyu.enderframelib.sample;

import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.WebViewSurface;
import com.nishiyu.enderframelib.api.spi.ElementPainter;
import com.nishiyu.enderframelib.api.spi.ElementProvider;
import com.nishiyu.enderframelib.api.spi.ReplacedKind;
import com.nishiyu.enderframelib.api.spi.TagTemplate;
import java.util.Map;

/**
 * 外部の利用者が {@code api} だけで書く拡張のサンプル（A9）。このファイルは {@code com.nishiyu.enderframelib.api.*} と JDK
 * しか参照しない。エンジン内部のパッケージに触れずに、独自タグ・翻訳を差し込める。
 *
 * <p>使い方: クラス名を {@code META-INF/services/com.nishiyu.enderframelib.api.spi.ElementProvider} に書く。
 * 文書では {@code <x-badge color="#ff0000">本文</x-badge>} が、色つきの箱に展開される。
 */
public final class SampleElements implements ElementProvider {

    @Override
    public Map<String, TagTemplate> tagTemplates() {
        return Map.of("x-badge", (attrs, inner) ->
                "<div class=\"x-badge\" style=\"width:30px;height:20px;background:"
                        + TagTemplate.escape(attrs.getOrDefault("color", "#336699")) + "\">" + inner + "</div>");
    }

    @Override
    public Map<String, ElementPainter> replacedElements() {
        return Map.of("x-avatar", new ElementPainter() {
            @Override public ReplacedKind kind() { return ReplacedKind.IMAGE_LIKE; }
            @Override public String resolveSrc(Map<String, String> attrs, String src) {
                return "avatar:" + attrs.getOrDefault("user", "anonymous");
            }
        });
    }

    /** 利用者のコードから面を作る最小の例。翻訳を差し込み、タグを使った文書を描いて画素を返す。 */
    public static int[] renderBadge(String color, int width, int height) {
        String html = "<html><body style='margin:0'><x-badge color='" + TagTemplate.escape(color) + "'></x-badge></body></html>";
        try (WebViewSurface surface = EnderEngine.html(html)
                .size(width, height)
                .clearColor(0xFFFFFFFF)
                .i18n(key -> key)
                .build()) {
            surface.frame(0, true);
            return surface.pixels();
        }
    }
}
