package com.nishiyu.enderframelib.api;

import com.nishiyu.enderframelib.api.spi.ElementPainter;
import com.nishiyu.enderframelib.api.spi.ElementProvider;
import com.nishiyu.enderframelib.api.spi.ReplacedKind;
import com.nishiyu.enderframelib.api.spi.TagTemplate;
import java.util.Map;
import java.util.Set;

/** A9: {@code api.spi} だけで書いた独自タグの宣言（内部の型には触れない）。ServiceLoader で読まれる。 */
public final class SpiTestProvider implements ElementProvider {

    @Override
    public Map<String, ElementPainter> replacedElements() {
        return Map.of("x-spi-img", new ElementPainter() {
            @Override public ReplacedKind kind() { return ReplacedKind.IMAGE_LIKE; }
            @Override public String resolveSrc(Map<String, String> attrs, String src) {
                return "spi:" + attrs.getOrDefault("name", src);
            }
        }, "x-spi-scene", new ElementPainter() {
            @Override public ReplacedKind kind() { return ReplacedKind.SCENE; }
            @Override public int sceneHeight() { return 77; }
            @Override public boolean sceneFillsWidth() { return false; }
            @Override public int sceneMinWidth() { return 55; }
        });
    }

    @Override
    public Map<String, TagTemplate> tagTemplates() {
        return Map.of(
                "x-spi-box", (attrs, inner) ->
                        "<div id=\"" + TagTemplate.escape(attrs.getOrDefault("id", "box")) + "\" style=\"width:30px;height:20px;background:"
                                + TagTemplate.escape(attrs.getOrDefault("color", "#00ff00")) + "\">" + inner + "</div>",
                "x-spi-skip", (attrs, inner) -> null);
    }

    @Override
    public Map<String, String> attributeAliases() {
        return Map.of("x-spi-if", "data-spi-canon");
    }

    @Override
    public Set<String> markdownTags() {
        return Set.of("x-spi-md");
    }

    @Override
    public Set<String> voidElements() {
        return Set.of("x-spi-sep");
    }
}
