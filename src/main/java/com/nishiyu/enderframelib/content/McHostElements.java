package com.nishiyu.enderframelib.content;

import com.nishiyu.enderframelib.core.layout.CustomElementPainter;
import com.nishiyu.enderframelib.core.layout.HostElementProvider;
import com.nishiyu.enderframelib.core.layout.ReplacedElements.Kind;
import com.nishiyu.enderframelib.core.dom.node.TagMacro;
import java.util.Map;
import java.util.Set;

/**
 * Minecraft ホストが持ち込む独自タグ（{@code mc-fluid} / {@code mc-entity} / {@code mc-scene} と、空要素の
 * {@code mc-slot} など）をエンジンへ宣言する。エンジン本体（core）はこれらのタグ名を知らない。
 */
public final class McHostElements implements HostElementProvider {

    /** {@code <mc-fluid fluid="...">} → {@code fluid:...} のように参照先へ接頭辞を付ける画像系の置換要素。 */
    private static CustomElementPainter prefixed(String prefix, String attr, String emptyDefault) {
        return new CustomElementPainter() {
            @Override public Kind kind() { return Kind.IMAGE_LIKE; }
            @Override public String resolveSrc(Map<String, String> attrs, String src) {
                if (src.startsWith(prefix)) return src;
                return prefix + attrs.getOrDefault(attr, src.isEmpty() ? emptyDefault : src);
            }
        };
    }

    private static final CustomElementPainter SCENE = new CustomElementPainter() {
        @Override public Kind kind() { return Kind.SCENE; }
    };

    @Override
    public Map<String, CustomElementPainter> replacedElements() {
        return Map.of(
                // 流体は属性が無いときだけ src を使う（空なら空のまま）
                "mc-fluid", prefixed("fluid:", "fluid", ""),
                "mc-entity", prefixed("entity:", "entity", "@self"),
                "mc-scene", SCENE);
    }

    @Override
    public Map<String, TagMacro> tagMacros() {
        return McTagMacros.all();
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
