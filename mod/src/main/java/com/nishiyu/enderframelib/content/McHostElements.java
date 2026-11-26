package com.nishiyu.enderframelib.content;

import com.nishiyu.enderframelib.api.spi.ElementPainter;
import com.nishiyu.enderframelib.api.spi.ElementProvider;
import com.nishiyu.enderframelib.api.spi.ReplacedKind;
import com.nishiyu.enderframelib.api.spi.TagTemplate;
import java.util.Map;
import java.util.Set;

/**
 * Minecraft ホストが持ち込む独自タグ（{@code mc-fluid} / {@code mc-entity} / {@code mc-scene} と、空要素の
 * {@code mc-slot} など）をエンジンへ宣言する。公開 SPI（{@code api.spi}）だけで書かれ、エンジン内部の型には触れない。
 * エンジン本体（core）はこれらのタグ名を知らない。
 */
public final class McHostElements implements ElementProvider {

    /** {@code <mc-fluid fluid="...">} → {@code fluid:...} のように参照先へ接頭辞を付ける画像系の置換要素。 */
    private static ElementPainter prefixed(String prefix, String attr, String emptyDefault) {
        return new ElementPainter() {
            @Override public ReplacedKind kind() { return ReplacedKind.IMAGE_LIKE; }
            @Override public String resolveSrc(Map<String, String> attrs, String src) {
                if (src.startsWith(prefix)) return src;
                return prefix + attrs.getOrDefault(attr, src.isEmpty() ? emptyDefault : src);
            }
        };
    }

    private static final ElementPainter SCENE = new ElementPainter() {
        @Override public ReplacedKind kind() { return ReplacedKind.SCENE; }
    };

    @Override
    public Map<String, ElementPainter> replacedElements() {
        return Map.of(
                // 流体は属性が無いときだけ src を使う（空なら空のまま）
                "mc-fluid", prefixed("fluid:", "fluid", ""),
                "mc-entity", prefixed("entity:", "entity", "@self"),
                "mc-scene", SCENE);
    }

    @Override
    public Map<String, TagTemplate> tagTemplates() {
        return McTagTemplates.all();
    }

    @Override
    public Map<String, String> attributeAliases() {
        return Map.of("mc-if", "data-if", "mc-for", "data-for");
    }

    @Override
    public Set<String> markdownTags() {
        return Set.of("mc-markdown");
    }

    @Override
    public Set<String> voidElements() {
        return Set.of("mc-fluid", "mc-entity", "mc-scene", "mc-slot", "mc-slot-grid", "mc-inventory", "mc-bar");
    }
}
