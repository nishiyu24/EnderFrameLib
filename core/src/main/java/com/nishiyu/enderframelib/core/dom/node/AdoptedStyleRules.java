package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.api.dom.StyleRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * 文書が採用したスタイル規則（Java の型付きスタイルが組んだ規則。{@code document.adoptedStyleSheets} に相当）。
 * 作者スタイルの末尾に足され、採用が増えるたびに版が進む（持ち主のセッションはそれを見てシートを作り直す）。
 * 採用したら文書は持ち主へ変更を知らせる（{@link HtmlDocument#adoptStyleRules}）。
 */
public final class AdoptedStyleRules {

    /** 持ち主（スタイル名）→ その規則を CSS に書き出したもの。採用した順。 */
    private final LinkedHashMap<String, String> byOwner = new LinkedHashMap<>();
    private int version;

    /**
     * 規則を採用する。同じ持ち主は 1 度だけで、2 度目以降は同じ規則なら何もしない。
     *
     * @return 新しく採用した（シートを作り直す必要がある）なら true
     * @throws IllegalStateException 同じ持ち主が別の規則で採用済み（スタイル名の衝突）
     */
    public boolean adopt(String owner, List<StyleRule> rules) {
        Objects.requireNonNull(owner, "owner");
        StringBuilder css = new StringBuilder();
        for (StyleRule rule : rules) css.append(rule.toCss()).append('\n');
        String text = css.toString();
        String existing = byOwner.putIfAbsent(owner, text);
        if (existing != null) {
            if (!existing.equals(text)) throw new IllegalStateException("スタイル名 '" + owner + "' は別の規則で採用済み（名前の衝突）");
            return false;
        }
        version++;
        return true;
    }

    /** 採用が増えるたびに進む版。 */
    public int version() {
        return version;
    }

    /** 採用した規則を採用順に連ねた CSS。 */
    public String css() {
        return byOwner.isEmpty() ? "" : String.join("", byOwner.values());
    }
}
