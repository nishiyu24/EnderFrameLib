package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.api.spi.ElementPainter;
import com.nishiyu.enderframelib.api.spi.ElementProvider;
import com.nishiyu.enderframelib.api.spi.TagTemplate;
import com.nishiyu.enderframelib.core.dom.CustomElementPainter;
import com.nishiyu.enderframelib.core.dom.HostElementProvider;
import com.nishiyu.enderframelib.core.dom.ReplacedKind;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.TagMacro;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * 公開 SPI（{@code api.spi.ElementProvider}。署名は JDK と {@code api.*} だけ）の宣言を、内部の
 * {@link HostElementProvider} へ橋渡しする（A9）。内部の台帳（置換要素・タグマクロ・構文・空要素）は
 * {@code META-INF/services/com.nishiyu.enderframelib.core.dom.HostElementProvider} に登録されたこのクラスを
 * 従来どおり読むだけで、{@code core} は {@code api.spi} を知らない（層の向きを保つ）。
 *
 * <p>公開 SPI の実装は全てここで 1 つにまとめる。同じタグ・別名が複数の実装にあれば、後に読んだ方が勝つ。
 */
public final class ApiSpiBridge implements HostElementProvider {

    private final List<ElementProvider> providers = new ArrayList<>();

    public ApiSpiBridge() {
        for (ElementProvider p : ServiceLoader.load(ElementProvider.class, ElementProvider.class.getClassLoader())) providers.add(p);
    }

    @Override
    public Map<String, CustomElementPainter> replacedElements() {
        Map<String, CustomElementPainter> m = new HashMap<>();
        for (ElementProvider p : providers) {
            for (Map.Entry<String, ElementPainter> e : p.replacedElements().entrySet()) {
                m.put(e.getKey(), adapt(e.getValue()));
            }
        }
        return m;
    }

    @Override
    public Map<String, TagMacro> tagMacros() {
        Map<String, TagMacro> m = new HashMap<>();
        for (ElementProvider p : providers) {
            for (Map.Entry<String, TagTemplate> e : p.tagTemplates().entrySet()) {
                m.put(e.getKey(), macro(e.getValue()));
            }
        }
        return m;
    }

    @Override
    public Map<String, String> attributeAliases() {
        Map<String, String> m = new HashMap<>();
        for (ElementProvider p : providers) m.putAll(p.attributeAliases());
        return m;
    }

    @Override
    public Set<String> markdownTags() {
        Set<String> s = new HashSet<>();
        for (ElementProvider p : providers) s.addAll(p.markdownTags());
        return s;
    }

    @Override
    public Set<String> voidElements() {
        Set<String> s = new HashSet<>();
        for (ElementProvider p : providers) s.addAll(p.voidElements());
        return s;
    }

    static CustomElementPainter adapt(ElementPainter p) {
        if (p == null || p.kind() == null) return null;
        ReplacedKind kind = switch (p.kind()) {
            case IMAGE_LIKE -> ReplacedKind.IMAGE_LIKE;
            case SCENE -> ReplacedKind.SCENE;
        };
        return new CustomElementPainter() {
            @Override public ReplacedKind kind() { return kind; }
            @Override public String resolveSrc(Map<String, String> attrs, String src) { return p.resolveSrc(attrs, src); }
            @Override public int sceneMinWidth() { return p.sceneMinWidth(); }
            @Override public boolean sceneFillsWidth() { return p.sceneFillsWidth(); }
            @Override public int sceneHeight() { return p.sceneHeight(); }
        };
    }

    static TagMacro macro(TagTemplate t) {
        if (t == null) return null;
        return source -> {
            String html = t.expand(Map.copyOf(source.attrs), source.getInnerHTML());
            if (html == null) return null;
            HtmlNode root = HtmlParser.parseFragment(html, "div");
            List<HtmlNode> out = new ArrayList<>();
            for (HtmlNode child : new ArrayList<>(root.children)) out.add(child.cloneNode(true));
            return out;
        };
    }
}
