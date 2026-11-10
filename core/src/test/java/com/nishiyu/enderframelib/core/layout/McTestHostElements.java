package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.dom.CustomElementPainter;
import com.nishiyu.enderframelib.core.dom.HostElementProvider;
import com.nishiyu.enderframelib.core.dom.ReplacedKind;
import java.util.Map;
import java.util.Set;

/**
 * テスト用に Minecraft ホスト（ルートモッドの McHostElements）と同じ宣言を再現する。
 * core 単体のテストが {@code mc-*} を使うため、test の ServiceLoader 登録で差し込む。
 */
public final class McTestHostElements implements HostElementProvider {

    /** {@code <mc-fluid fluid="...">} → {@code fluid:...} のように参照先へ接頭辞を付ける画像系の置換要素。 */
    private static CustomElementPainter prefixed(String prefix, String attr, String emptyDefault) {
        return new CustomElementPainter() {
            @Override public ReplacedKind kind() { return ReplacedKind.IMAGE_LIKE; }
            @Override public String resolveSrc(Map<String, String> attrs, String src) {
                if (src.startsWith(prefix)) return src;
                return prefix + attrs.getOrDefault(attr, src.isEmpty() ? emptyDefault : src);
            }
        };
    }

    private static final CustomElementPainter SCENE = new CustomElementPainter() {
        @Override public ReplacedKind kind() { return ReplacedKind.SCENE; }
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
