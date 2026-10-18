package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 複合セレクタ（結合子で区切られた 1 単位、例: {@code div#main.active[data-x]:first-child}）のデータ。
 *
 * 以前はこのクラスのコンストラクタが正規表現と {@code contains} + {@code replace} による
 * 破壊的な文字列手術でパースしていた。現在は {@link SelectorParser} が実トークナイズして
 * このオブジェクトを組み立てるだけで、ここはデータと照合の入口に限定されている。
 */
public class SelectorToken {

    public String rawSelector = "";
    public String tag = "*";
    public String id = null;
    public List<String> classes = new ArrayList<>();

    /** {@code [attr=val]}（完全一致） */
    public Map<String, String> requiredAttrs = new LinkedHashMap<>();
    /** {@code [attr^=val]} */
    public Map<String, String> prefixAttrs = new LinkedHashMap<>();
    /** {@code [attr$=val]} */
    public Map<String, String> suffixAttrs = new LinkedHashMap<>();
    /** {@code [attr*=val]} */
    public Map<String, String> containsAttrs = new LinkedHashMap<>();
    /** {@code [attr~=val]}（空白区切りのいずれかに一致） */
    public Map<String, String> wordAttrs = new LinkedHashMap<>();
    /** {@code [attr|=val]}（値そのもの、または {@code val-} で始まる） */
    public Map<String, String> dashAttrs = new LinkedHashMap<>();
    /** {@code [attr]}（存在のみ） */
    public List<String> requiredExistsAttrs = new ArrayList<>();

    public List<SelectorToken> notSelectors = new ArrayList<>();

    /** この複合セレクタが要求する {@link ElementState} ビット集合。 */
    public int stateFlags = 0;

    public boolean parsedHover = false;
    public boolean parsedActive = false;
    public boolean parsedFocus = false;
    public boolean parsedFocusVisible = false;
    public boolean parsedFocusWithin = false;
    public boolean isChecked = false;
    public boolean isDisabled = false;
    public boolean isIndeterminate = false;
    public boolean isOpen = false;
    public boolean isFirstChild = false;
    public boolean isLastChild = false;
    public boolean isEmpty = false;
    /** :root（文書のルート要素） */
    public boolean isRoot = false;

    /** 正の値: n 番目、{@link SelectorParser#NTH_ODD}: odd、{@link SelectorParser#NTH_EVEN}: even、-1: 指定なし。 */
    public int nthChild = -1;

    public SelectorToken() {}

    /** 後方互換用。セレクタ文字列から複合セレクタ 1 つをパースする。 */
    public SelectorToken(String sel) {
        SelectorParser.parseCompoundInto(sel, this);
    }

    public boolean matches(HtmlNode node) {
        return SelectorMatcher.matchesCompound(this, node);
    }

    public boolean matches(HtmlNode node, SelectorMatcher.StateLookup stateLookup) {
        return SelectorMatcher.matchesCompound(this, node, stateLookup);
    }
}
