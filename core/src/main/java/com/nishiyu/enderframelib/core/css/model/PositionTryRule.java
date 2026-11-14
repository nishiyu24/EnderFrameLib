package com.nishiyu.enderframelib.core.css.model;

import java.util.Map;

/**
 * {@code @position-try <dashed-ident> { ... }} 1 件分の宣言群。
 * 宣言はロングハンドの物理プロパティ名（小文字）へ展開済みの不変マップで保持する。
 *
 * @param name         {@code --} で始まる識別子（大文字小文字を区別）
 * @param declarations 展開済み宣言（{@code top}・{@code margin-top}・{@code width}・{@code position-area} 等）
 */
public record PositionTryRule(String name, Map<String, String> declarations) {
    public PositionTryRule {
        declarations = declarations == null ? Map.of() : Map.copyOf(declarations);
    }
}
