package com.nishiyu.enderframelib.api.dom;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Objects;

/**
 * 文書へ採用させる 1 つのスタイル規則（セレクタと、プロパティ・値の並び）。
 *
 * <p>Java の型付きスタイル（{@link com.nishiyu.enderframelib.api.ui.style}）が組み立てて {@link Document#adoptStyleRules} へ渡す中間表現。
 * 書く側がこの型を直接作ることは想定しない。</p>
 *
 * @param selector     セレクタ（例 {@code .card:hover}）
 * @param declarations 宣言の並び（後勝ち）
 */
@ApiStatus.Internal
public record StyleRule(String selector, List<Declaration> declarations) {

    public StyleRule {
        Objects.requireNonNull(selector, "selector");
        declarations = List.copyOf(declarations);
    }

    /** 1 つの宣言。{@code property} は kebab-case（カスタムプロパティは {@code --name}）、{@code value} は直列化済みの値。 */
    public record Declaration(String property, String value) {
        public Declaration {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(value, "value");
        }
    }

    /** CSS の規則として書き出す（{@code selector{prop:value;...}}）。 */
    public String toCss() {
        StringBuilder sb = new StringBuilder(selector.length() + declarations.size() * 24);
        sb.append(selector).append('{');
        for (Declaration d : declarations) {
            sb.append(d.property()).append(':').append(d.value()).append(';');
        }
        return sb.append('}').toString();
    }
}
