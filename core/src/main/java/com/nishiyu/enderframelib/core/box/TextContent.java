package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

import java.util.List;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.Locale;

/**
 * 文字の節の中身（Engine v3 M2b-1）。DOM の文字列から、レイアウトと描画が読む {@link BoxNode#text} を作る純粋な関数。
 *
 * <ol>
 *   <li>{@code white-space} による空白の正規化（{@link TextShaper#normalize}）。</li>
 *   <li>{@code text-transform}（{@code uppercase} / {@code lowercase} / {@code capitalize}）。大文字・小文字の写像は言語に依らない
 *       {@link Locale#ROOT}。</li>
 *   <li>描かれず幅も持たない書式文字（ゼロ幅の空白・双方向の制御文字・BOM。CSS Text 3 §4.1.1 の「描かない」制御文字）の除去。</li>
 * </ol>
 *
 * <p>入力は節自身の文字列と、親の継承群を共有した文字の節のスタイル。要素の境目をまたぐ語の続き（{@code p<span>ass</span>} の
 * {@code capitalize} は {@code ass} の頭を大文字にしない）だけは前の節の文字に依る非局所の依存で、{@link #continuesWord} が DOM の
 * 前の兄弟（と語の中で開く書式の要素の外）を見る（旧 {@code IntrinsicSizing.endsWithWordChar} と同じ規則）。{@code capitalize} を使う
 * 文書は {@link BoxTreeCompiler} が台帳に変更があれば全体を作り直す（カウンタと同じ扱い）ので、増分の結果は全体と同じ。</p>
 */
public final class TextContent {
    private TextContent() { }

    /** 文字の節 {@code raw} の、スタイル {@code st}（文字の節のスタイル）での中身。 */
    public static String of(String raw, Style st) {
        return of(raw, st, false);
    }

    /** {@code continues} は前の節の文字が語の途中で終わっているか（{@code capitalize} で最初の語の頭を大文字にしない）。 */
    static String of(String raw, Style st, boolean continues) {
        String text = TextShaper.normalize(raw, st.facts.whiteSpace);
        if (text.isEmpty()) return text;
        CssKeywords.TextTransform tt = st.inherited.textTransform;
        if (tt != null) {
            text = switch (tt) {
                case UPPERCASE -> text.toUpperCase(Locale.ROOT);
                case LOWERCASE -> text.toLowerCase(Locale.ROOT);
                case CAPITALIZE -> capitalize(text, continues);
                case NONE -> text;
            };
        }
        return stripIgnorable(text);
    }

    /** 語（空白で区切った並び）の頭の文字を大文字にする（{@code continues} なら最初の語は前の節の語の続き）。 */
    static String capitalize(String text, boolean continues) {
        StringBuilder sb = null;
        boolean start = !(continues && Character.isLetterOrDigit(text.charAt(0)));
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (start && !Character.isWhitespace(c)) {
                char u = Character.toUpperCase(c);
                if (u != c) {
                    if (sb == null) sb = new StringBuilder(text);
                    sb.setCharAt(i, u);
                }
            }
            start = Character.isWhitespace(c);
        }
        return sb == null ? text : sb.toString();
    }

    /**
     * 文字の節 {@code n} の前の文字が語の途中（英数字）で終わっているか。前の兄弟を後ろから辿り、無ければ語の中で開く書式の要素
     * （{@code span}・{@code b}・{@code i}・{@code a}・{@code em}）の外へ出て続ける。それ以外の要素の境目は語を区切る。
     */
    static boolean continuesWord(HtmlNode n) {
        for (HtmlNode cur = n; cur != null && cur.parent != null; cur = cur.parent) {
            List<HtmlNode> sib = cur.parent.children;
            for (int i = sib.indexOf(cur) - 1; i >= 0; i--) {
                int c = lastChar(sib.get(i));
                if (c >= 0) return Character.isLetterOrDigit(c);
            }
            if (cur.parent.nodeType == NodeType.ELEMENT && !ElementTraits.isWordInlineFormatting(cur.parent.tag)) return false;
        }
        return false;
    }

    private static int lastChar(HtmlNode n) {
        if (n.nodeType == NodeType.TEXT) return n.text == null || n.text.isEmpty() ? -1 : n.text.charAt(n.text.length() - 1);
        for (int i = n.children.size() - 1; i >= 0; i--) {
            int c = lastChar(n.children.get(i));
            if (c >= 0) return c;
        }
        return -1;
    }

    /** 描かない書式文字を除く（無ければ同じ参照）。 */
    static String stripIgnorable(String s) {
        int n = s.length(), i = 0;
        while (i < n && !ignorable(s.charAt(i))) i++;
        if (i == n) return s;
        StringBuilder sb = new StringBuilder(n);
        sb.append(s, 0, i);
        for (; i < n; i++) {
            char c = s.charAt(i);
            if (!ignorable(c)) sb.append(c);
        }
        return sb.toString();
    }

    private static boolean ignorable(char c) {
        if (c < 0x200B) return false;
        return c <= 0x200F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2060 && c <= 0x2064) || (c >= 0x2066 && c <= 0x2069) || c == 0xFEFF;
    }
}
